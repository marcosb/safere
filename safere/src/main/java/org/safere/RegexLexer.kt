// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Streaming source interpretation for [Parser]. Only this class reads the original source.
 * Tokens are borrowed until the next read; no object is allocated for an individual source rune.
 * The parser owns flag scope and updates this lexer before scanning beyond a group boundary.
 */
internal class RegexLexer(private val source: String, flags: Int) {
    internal enum class Kind {
        END,
        LITERAL,
        DOT,
        CARET,
        DOLLAR,
        OPEN,
        CLOSE,
        ALTERNATION,
        CLASS_OPEN,
        CLASS_CLOSE,
        HYPHEN,
        AMPERSAND,
        INTERSECTION,
        PROPERTY,
        PREDEFINED,
        BOUNDARY,
        BEGIN,
        END_TEXT,
        FINAL_END,
        LINEBREAK,
        GRAPHEME,
        GRAPHEME_BOUNDARY,
        REPEAT,
        FLAGS,
        SCOPED_FLAGS,
        NONCAPTURE
    }

    internal enum class RepetitionMode {
        GREEDY,
        RELUCTANT,
        POSSESSIVE
    }

    /**
     * Mutable token storage owned by the lexer, including original UTF-16 source coordinates. Only
     * fields applicable to the current kind carry a value; other payload fields may retain old data.
     */
    internal class Token {
        var kind: Kind? = null
        var start: Int = 0
        var end: Int = 0
        var rune: Int = 0
        var min: Int = 0
        var max: Int = 0
        var flags: Int = 0
        var text: String? = null
        var negated: Boolean = false
        var leadingTrivia: Boolean = false
        var mode: RepetitionMode? = null
    }

    private var flags: Int = 0
    private var sourcePosition = 0
    private var quoting = false
    private var firstQuotedRune = false
    private var quoteStart = 0
    private var escapedSourceRune = false
    private var commentQuoting = false

    // The common path caches only the current logical code point and its source span. Additional
    // lookahead is bounded and allocated only for multi-character boundary/surrogate recognition.
    private var currentRune: Int = UNREAD
    private var currentStart = 0
    private var currentEnd = 0
    private var lookahead: IntArray? = null
    private var lookaheadCount = 0

    // Quoted punctuation contributes an escaped pair; a leading quoted ASCII digit contributes
    // a hexadecimal escape. Most patterns never need this expansion storage.
    private var pending: IntArray? = null
    private var pendingIndex = 0
    private var pendingCount = 0
    private var pendingStart = 0
    private var pendingEnd = 0
    private var lastEnd = 0
    private val token = Token()
    private var classToken: Token? = null
    private var classTokenReady = false

    // At most one decoded Unicode escape can be held while deciding whether a high surrogate
    // forms a pair. This lets the ordinary component reader consume arbitrarily long trivia once.
    private var unicodeUnitReady = false
    private var unicodeUnit = 0
    private var unicodeStart = 0
    private var unicodeEnd = 0

    init {
        this.flags = flags
    }

    fun setFlags(flags: Int) {
        check(!classTokenReady) { "lexical mode changed with an outstanding class token" }
        this.flags = flags
    }

    fun sawCommentQuoting(): Boolean {
        return commentQuoting
    }

    fun length(): Int {
        return source.length
    }

    fun error(description: String?, index: Int, cause: Throwable? = null): RuntimeException {
        return PatternSyntaxExceptions.create(description, source, index, cause)
    }

    fun classError(description: String?, index: Int): RuntimeException {
        return PatternSyntaxExceptions.createClass(description, source, index)
    }

    fun next(): Token {
        scan(token, false, false)
        return token
    }

    fun peekClass(): Token {
        if (!classTokenReady) {
            if (classToken == null) classToken = Token()
            scan(classToken!!, true, false)
            classTokenReady = true
        }
        return classToken!!
    }

    /** Peeks at a possible range endpoint, preserving brackets and reading ampersands singly.  */
    fun peekClassRangeEndpoint(): Token {
        check(!classTokenReady) { "range endpoint requested after expression lookahead" }
        if (classToken == null) classToken = Token()
        scan(classToken!!, true, true)
        classTokenReady = true
        return classToken!!
    }

    fun takeClass(): Token {
        val result = peekClass()
        classTokenReady = false
        return result
    }

    private fun scan(out: Token, inClass: Boolean, rangeEndpoint: Boolean) {
        if (unicodeUnitReady) {
            out.leadingTrivia = false
            out.kind = Kind.LITERAL
            out.start = unicodeStart
            out.end = unicodeEnd
            val unit = unicodeUnit
            unicodeUnitReady = false
            unicodeTail(out, unit)
            return
        }
        if ((flags and ParseFlags.LITERAL) != 0) {
            out.leadingTrivia = false
            out.start = sourcePosition
            if (sourcePosition == source.length) {
                out.kind = Kind.END
            } else {
                out.kind = Kind.LITERAL
                out.rune = CodePoints.codePointAt(source, sourcePosition)
                sourcePosition += CodePoints.charCount(out.rune)
            }
            out.end = sourcePosition
            return
        }
        out.leadingTrivia = skipTrivia()
        val c = peekRaw()
        out.start = position()
        readRaw()
        out.rune = c
        if (c < 0) {
            out.kind = Kind.END
            out.end = source.length
        } else if (c == '\\'.code) {
            out.end = -1
            if (!takeQuotedLiteral(out)) escape(out, inClass)
            if (out.end < 0) out.end = lastEnd
        } else if (inClass) {
            classOperator(out, c, rangeEndpoint)
            out.end = lastEnd
        } else {
            operator(out, c)
            out.end = lastEnd
        }
    }

    private fun takeQuotedLiteral(out: Token): Boolean {
        // At a token boundary, an untouched synthetic escape represents exactly one quoted scalar.
        // Component readers still see its logical spelling, including leading-digit disambiguation.
        if (pendingIndex != 1 || pendingCount <= 1 || currentRune != UNREAD || lookaheadCount != 0) {
            return false
        }
        out.kind = Kind.LITERAL
        out.rune = pending!![pendingCount - 1]
        pendingIndex = pendingCount
        return true
    }

    private fun classOperator(out: Token, c: Int, rangeEndpoint: Boolean) {
        out.kind =
            when (c) {
                '['.code -> Kind.CLASS_OPEN
                ']'.code -> Kind.CLASS_CLOSE
                '-'.code -> Kind.HYPHEN
                '^'.code -> Kind.CARET
                else -> Kind.LITERAL
            }
        if (c == '&'.code && !rangeEndpoint) {
            out.kind = Kind.AMPERSAND
            skipTrivia()
            if (peekRaw() == '&'.code) {
                readRaw()
                out.kind = Kind.INTERSECTION
            }
        }
    }

    private fun operator(out: Token, c: Int) {
        out.kind =
            when (c) {
                '('.code -> Kind.OPEN
                ')'.code -> Kind.CLOSE
                '|'.code -> Kind.ALTERNATION
                '['.code -> Kind.CLASS_OPEN
                '^'.code -> Kind.CARET
                '$'.code -> Kind.DOLLAR
                '.'.code -> Kind.DOT
                '*'.code, '+'.code, '?'.code, '{'.code -> Kind.REPEAT
                else -> Kind.LITERAL
            }
        if (out.kind == Kind.REPEAT) repetition(out, c)
        if (out.kind == Kind.OPEN) {
            out.text = null
            if ((flags and ParseFlags.PERL_X) != 0) {
                skipTrivia()
                if (peekRaw() == '?'.code) {
                    readRaw()
                    groupHeader(out)
                }
            }
        }
    }

    private fun repetition(out: Token, operator: Int) {
        out.mode = RepetitionMode.GREEDY
        if (operator == '{'.code) {
            out.min = decimal()
            out.max = out.min
            skipTrivia()
            if (peekRaw() == ','.code) {
                readRaw()
                skipTrivia()
                out.max = if (peekRaw() == '}'.code) -1 else decimal()
            }
            skipTrivia()
            if (out.min < 0 || out.max < -1 || readRaw() != '}'.code) {
                throw error("Illegal repetition", out.start)
            }
        } else {
            out.min = if (operator == '+'.code) 1 else 0
            out.max = if (operator == '?'.code) 1 else -1
        }
        if ((flags and ParseFlags.PERL_X) != 0) {
            skipTrivia()
            if (peekRaw() == '?'.code || peekRaw() == '+'.code) {
                out.mode = if (readRaw() == '?'.code) RepetitionMode.RELUCTANT else RepetitionMode.POSSESSIVE
            }
        }
    }

    private fun decimal(): Int {
        if (!Utils.isDigit(peekRaw())) return -2
        var n = 0
        while (Utils.isDigit(peekRaw())) {
            if (n >= 100000000) throw error("invalid repeat count", position())
            n = n * 10 + readRaw() - '0'.code
            skipTrivia()
        }
        return n
    }

    private fun escape(out: Token, inClass: Boolean) {
        val c = readRaw()
        if (c < 0) throw error("trailing backslash", out.start)
        out.kind = Kind.LITERAL
        when (c) {
            'p'.code, 'P'.code -> {
                if ((flags and ParseFlags.UNICODE_GROUPS) == 0) {
                    throw error("invalid escape sequence", out.start)
                }
                out.kind = Kind.PROPERTY
                out.negated = c == 'P'.code
                skipTrivia()
                val first = readRaw()
                if (first < 0) throw error("invalid Unicode group", out.start)
                if (first == '{'.code) {
                    skipTrivia()
                    out.text = payload('}'.code, "invalid Unicode group", out.start)
                } else {
                    out.text = CodePoints.toChars(first).concatToString()
                }
            }

            'd'.code, 'D'.code, 'h'.code, 'H'.code, 's'.code, 'S'.code, 'v'.code, 'V'.code, 'w'.code, 'W'.code -> {
                if ((flags and ParseFlags.PERL_CLASSES) == 0) {
                    throw error("invalid escape sequence", out.start)
                }
                out.kind = Kind.PREDEFINED
                out.rune = CodePoints.toLowerCase(c)
                out.negated = c >= 'A'.code && c <= 'Z'.code
            }

            'b'.code, 'B'.code -> {
                if (inClass || (flags and ParseFlags.PERL_B) == 0) {
                    throw error("invalid escape sequence", out.start)
                }
                out.kind = Kind.BOUNDARY
                out.negated = c == 'B'.code
                if (c == 'b'.code && peekRaw() == '{'.code && peekRaw(1) == 'g'.code && peekRaw(2) == '}'.code) {
                    readRaw()
                    readRaw()
                    readRaw()
                    out.kind = Kind.GRAPHEME_BOUNDARY
                }
            }

            'A'.code, 'z'.code, 'Z'.code, 'G'.code -> {
                if (inClass || (flags and ParseFlags.PERL_X) == 0 || c == 'G'.code) {
                    throw error(
                        if (c == 'G'.code) "\\G (end of previous match) is not supported" else "invalid escape sequence",
                        out.start
                    )
                }
                out.kind = if (c == 'A'.code) Kind.BEGIN else if (c == 'z'.code) Kind.END_TEXT else Kind.FINAL_END
            }

            'R'.code, 'X'.code -> {
                if (inClass) throw error("invalid escape sequence", out.start)
                out.kind = if (c == 'R'.code) Kind.LINEBREAK else Kind.GRAPHEME
            }

            'N'.code -> {
                if (readRaw() != '{'.code) throw error("invalid escape sequence", out.start)
                val name = payload('}'.code, "invalid escape sequence", out.start)
                try {
                    out.rune = UnicodeCharacterNames.codePointOf(name)
                } catch (e: IllegalArgumentException) {
                    throw error("unknown Unicode character name: " + name, out.start + 3, e)
                }
            }

            '1'.code, '2'.code, '3'.code, '4'.code, '5'.code, '6'.code, '7'.code, '8'.code, '9'.code -> throw error(
                "backreferences are not supported",
                out.start
            )

            '0'.code -> {
                skipTrivia()
                if (peekRaw() < '0'.code || peekRaw() > '7'.code) throw error(
                    "Illegal octal escape sequence",
                    position()
                )
                var code = 0
                var i = 0
                while (i < 3 && peekRaw() >= '0'.code && peekRaw() <= '7'.code) {
                    val next = code * 8 + peekRaw() - '0'.code
                    if (next > 255) break
                    readRaw()
                    code = next
                    skipTrivia()
                    i++
                }
                out.rune = code
            }

            'x'.code -> {
                skipTrivia()
                if (peekRaw() == '{'.code) {
                    readRaw()
                    var code = 0
                    var count = 0
                    skipTrivia()
                    while (Utils.isHexDigit(peekRaw())) {
                        code = code * 16 + Utils.unhex(readRaw())
                        count++
                        if (code > Utils.MAX_RUNE) throw error("invalid escape sequence", position())
                        skipTrivia()
                    }
                    if (count == 0 || readRaw() != '}'.code) throw error("invalid escape sequence", position())
                    out.rune = code
                } else {
                    out.rune = exactHex(2, out.start)
                }
            }

            'u'.code -> {
                val code = exactHex(4, out.start)
                out.end = lastEnd
                unicodeTail(out, code)
            }

            'n'.code -> out.rune = '\n'.code
            'r'.code -> out.rune = '\r'.code
            't'.code -> out.rune = '\t'.code
            'a'.code -> out.rune = '\u0007'.code
            'e'.code -> out.rune = '\u001b'.code
            'f'.code -> out.rune = '\u000c'.code
            'c'.code -> {
                skipTrivia()
                val target = readRaw()
                if (target < 0) throw error("invalid escape sequence", out.start)
                out.rune = target xor 0x40
            }

            else -> {
                if (Utils.isAlpha(c)) throw error("invalid escape sequence", out.start)
                out.rune = c
            }
        }
    }

    private fun unicodeTail(out: Token, code: Int) {
        out.rune = code
        if (!CodePoints.isHighSurrogate(code.toChar())) return
        skipTrivia()
        if (peekRaw() != '\\'.code || peekRaw(1) != 'u'.code) return
        val start = position()
        readRaw()
        readRaw()
        val low = exactHex(4, start)
        if (CodePoints.isLowSurrogate(low.toChar())) {
            out.rune = CodePoints.toCodePoint(code.toChar(), low.toChar())
            out.end = lastEnd
        } else {
            unicodeUnitReady = true
            unicodeUnit = low
            unicodeStart = start
            unicodeEnd = lastEnd
        }
    }

    private fun exactHex(digits: Int, start: Int): Int {
        val direct = sourceHex(digits)
        if (direct >= 0) return direct
        var code = 0
        for (i in 0..<digits) {
            skipTrivia()
            if (!Utils.isHexDigit(peekRaw())) throw error("invalid escape sequence", start)
            code = code * 16 + Utils.unhex(readRaw())
        }
        return code
    }

    private fun sourceHex(digits: Int): Int {
        if (currentRune != UNREAD || lookaheadCount != 0 || pendingIndex != pendingCount || quoting
            || escapedSourceRune
            || source.length - sourcePosition < digits
        ) {
            return -1
        }
        // A fixed-width all-ASCII payload has no quote/trivia transitions. Probe its bounded width
        // before committing so malformed or interrupted payloads retain the general reader's errors.
        var code = 0
        for (i in 0..<digits) {
            val digit = Utils.unhex(source[sourcePosition + i].code)
            if (digit < 0) return -1
            code = code * 16 + digit
        }
        if (WorkCounterConfig.ENABLED) WorkCounter.record(digits.toLong())
        sourcePosition += digits
        lastEnd = sourcePosition
        return code
    }

    private fun payload(terminator: Int, message: String?, start: Int): String {
        val result = StringBuilder()
        while (peekRaw() >= 0 && peekRaw() != terminator) appendCodePoint(result, readRaw())
        if (readRaw() != terminator) throw error(message, start)
        return result.toString()
    }

    private fun groupHeader(out: Token) {
        // Recognize headers without scanning any of the following body. The parser commits/restores
        // flag scope before requesting that body's first token.
        skipTrivia()
        if (peekRaw() == '<'.code || peekRaw() == 'P'.code) {
            val pythonName = readRaw() == 'P'.code
            if (pythonName && readRaw() != '<'.code) throw error("invalid Perl operator", out.start)
            if (peekRaw() == '='.code || peekRaw() == '!'.code) throw error("invalid Perl operator", out.start)
            val name = StringBuilder()
            skipTrivia()
            while (peekRaw() >= 0 && peekRaw() != '>'.code) {
                appendCodePoint(name, readRaw())
                skipTrivia()
            }
            if (readRaw() != '>'.code) throw error("invalid named capture", out.start)
            out.text = name.toString()
            if (!validCaptureName(out.text!!, pythonName)) throw error(
                "invalid named capture: " + out.text,
                out.start
            )
            return
        }
        if (peekRaw() == '='.code || peekRaw() == '!'.code || peekRaw() == '>'.code) {
            throw error("invalid Perl operator", out.start)
        }
        var nextFlags = flags
        var negated = false
        var sawFlags = false
        while (true) {
            // A header's lexical mode changes as its flag letters are read, before its closing token.
            val previousFlags = flags
            flags = nextFlags
            skipTrivia()
            flags = previousFlags
            val c = readRaw()
            val mask =
                when (c) {
                    'd'.code -> ParseFlags.UNIX_LINES
                    'i'.code -> ParseFlags.FOLD_CASE
                    'm'.code -> ParseFlags.ONE_LINE
                    's'.code -> ParseFlags.DOT_NL
                    'u'.code -> ParseFlags.UNICODE_CASE
                    'U'.code -> ParseFlags.UNICODE_CASE or ParseFlags.UNICODE_GROUPS or ParseFlags.UNICODE_CHAR_CLASS
                    'x'.code -> ParseFlags.COMMENTS
                    else -> 0
                }
            if (mask != 0) {
                sawFlags = true
                if (negated xor (c == 'm'.code)) nextFlags = nextFlags and mask.inv()
                else nextFlags = nextFlags or mask
            } else if (c == '-'.code && !negated) {
                negated = true
                sawFlags = false
            } else if ((c == ':'.code || c == ')'.code) && (!negated || sawFlags)) {
                out.kind = if (c == ':'.code) Kind.SCOPED_FLAGS else Kind.FLAGS
                out.flags = nextFlags
                return
            } else {
                throw error("invalid Perl operator", out.start)
            }
        }
    }

    private fun skipTrivia(): Boolean {
        if ((flags and ParseFlags.COMMENTS) == 0) return false
        var skipped = false
        if (currentRune == UNREAD && lookaheadCount == 0 && pendingIndex == pendingCount && !quoting && !escapedSourceRune) {
            skipped = skipSourceTrivia()
        }
        while (true) {
            val c = peekRaw()
            if (c == ' '.code || (c >= '\t'.code && c <= '\r'.code)) {
                readRaw()
                skipped = true
            } else if (c == '#'.code) {
                skipComment()
                skipped = true
            } else {
                return skipped
            }
        }
    }

    private fun skipSourceTrivia(): Boolean {
        // No logical characters are buffered here. Read ordinary source trivia without populating
        // and consuming the cursor for each whitespace rune; quotes still use logical interpretation.
        var skipped = false
        while (sourcePosition < source.length) {
            val c = CodePoints.codePointAt(source, sourcePosition)
            if (c == ' '.code || (c >= '\t'.code && c <= '\r'.code)) {
                if (WorkCounterConfig.ENABLED) WorkCounter.record()
                sourcePosition += CodePoints.charCount(c)
                lastEnd = sourcePosition
            } else if (c == '#'.code) {
                if (WorkCounterConfig.ENABLED) WorkCounter.record()
                currentStart = sourcePosition
                skipComment()
            } else {
                break
            }
            skipped = true
        }
        return skipped
    }

    private fun skipComment() {
        // Comment text is discarded before quote interpretation (#858). Discard bounded
        // lookahead too, then scan the original comment once without interpreting its escapes.
        sourcePosition = currentStart + 1
        currentRune = UNREAD
        lookaheadCount = 0
        pendingCount = 0
        pendingIndex = pendingCount
        quoting = false
        escapedSourceRune = false
        while (sourcePosition < source.length) {
            if (WorkCounterConfig.ENABLED) WorkCounter.record()
            val cp = CodePoints.codePointAt(source, sourcePosition)
            if (cp == 0 || cp == '\n'.code || ((flags and ParseFlags.UNIX_LINES) == 0 && Nfa.isLineTerminator(
                    cp
                ))
            ) break
            if (cp == '\\'.code && sourcePosition + 1 < source.length && (source[sourcePosition + 1] == 'Q'
                        || source[sourcePosition + 1] == 'E')
            ) {
                commentQuoting = true
            }
            sourcePosition += CodePoints.charCount(cp)
        }
    }

    private fun position(): Int {
        return if (currentRune == UNREAD) sourcePosition else currentStart
    }

    private fun peekRaw(): Int {
        if (currentRune == UNREAD) advance()
        return currentRune
    }

    private fun peekRaw(offset: Int): Int {
        if (offset == 0) return peekRaw()
        require(!(offset < 0 || offset > MAX_LOOKAHEAD)) { "logical lookahead exceeds its fixed bound" }
        if (peekRaw() < 0) return -1
        if (lookahead == null) lookahead = IntArray(MAX_LOOKAHEAD * 3)
        while (lookaheadCount < offset) {
            val savedRune = currentRune
            val savedStart = currentStart
            val savedEnd = currentEnd
            advance()
            val nextRune = currentRune
            val nextStart = currentStart
            val nextEnd = currentEnd
            currentRune = savedRune
            currentStart = savedStart
            currentEnd = savedEnd
            if (nextRune < 0) return -1
            val index = lookaheadCount * 3
            lookahead!![index] = nextRune
            lookahead!![index + 1] = nextStart
            lookahead!![index + 2] = nextEnd
            lookaheadCount++
        }
        return lookahead!![(offset - 1) * 3]
    }

    private fun readRaw(): Int {
        val result = peekRaw()
        if (result >= 0) {
            lastEnd = currentEnd
            if (lookaheadCount == 0) {
                currentRune = UNREAD
            } else {
                currentRune = lookahead!![0]
                currentStart = lookahead!![1]
                currentEnd = lookahead!![2]
                lookaheadCount--
                if (lookaheadCount != 0) {
                    lookahead!![0] = lookahead!![3]
                    lookahead!![1] = lookahead!![4]
                    lookahead!![2] = lookahead!![5]
                }
            }
        }
        return result
    }

    private fun advance() {
        // Keep ordinary source reads small enough to inline into numeric readers and token scanning.
        // Quote expansion and backslash state transitions share the general path below.
        if (!quoting && pendingIndex == pendingCount && sourcePosition < source.length) {
            val cp = CodePoints.codePointAt(source, sourcePosition)
            if (cp != '\\'.code || escapedSourceRune
                || (flags and ParseFlags.PERL_X) == 0 || sourcePosition + 1 == source.length || source[
                    sourcePosition + 1
                ] != 'Q'
            ) {
                if (WorkCounterConfig.ENABLED) WorkCounter.record()
                currentStart = sourcePosition
                sourcePosition += CodePoints.charCount(cp)
                currentRune = cp
                currentEnd = sourcePosition
                escapedSourceRune = cp == '\\'.code && !escapedSourceRune
                return
            }
        }
        advanceExpanded()
    }

    private fun advanceExpanded() {
        val value: Int
        var start: Int = 0
        var end: Int = 0
        while (true) {
            if (pendingIndex < pendingCount) {
                value = pending!![pendingIndex++]
                start = pendingStart
                end = pendingEnd
                break
            }
            if (sourcePosition == source.length) {
                currentRune = -1
                currentEnd = sourcePosition
                currentStart = currentEnd
                return
            }
            start = sourcePosition
            if (WorkCounterConfig.ENABLED) WorkCounter.record()
            val cp = CodePoints.codePointAt(source, sourcePosition)
            sourcePosition += CodePoints.charCount(cp)
            end = sourcePosition
            if (quoting) {
                if (cp == '\\'.code && sourcePosition < source.length && source[sourcePosition] == 'E') {
                    sourcePosition++
                    quoting = false
                    continue
                }
                val first = firstQuotedRune
                firstQuotedRune = false
                if (first) start = quoteStart
                pendingStart = start
                pendingEnd = end
                pendingIndex = 0
                if (first && cp >= '0'.code && cp <= '9'.code) {
                    if (pending == null) pending = IntArray(4)
                    pending!![0] = '\\'.code
                    pending!![1] = 'x'.code
                    pending!![2] = '3'.code
                    pending!![3] = cp
                    pendingCount = 4
                } else if (cp < 128 && !Utils.isAlpha(cp) && !Utils.isDigit(cp)) {
                    if (pending == null) pending = IntArray(4)
                    pending!![0] = '\\'.code
                    pending!![1] = cp
                    pendingCount = 2
                } else {
                    pendingCount = 0
                    value = cp
                    break
                }
                continue
            }
            if (escapedSourceRune) {
                escapedSourceRune = false
            } else if (cp == '\\'.code) {
                if ((flags and ParseFlags.PERL_X) != 0 && sourcePosition < source.length && source[
                        sourcePosition
                    ] == 'Q'
                ) {
                    sourcePosition++
                    quoting = true
                    firstQuotedRune = true
                    quoteStart = start
                    continue
                }
                escapedSourceRune = true
            }
            value = cp
            break
        }
        currentRune = value
        currentStart = start
        currentEnd = end
    }


    private fun appendCodePoint(sb: StringBuilder, cp: Int) {
        if (cp <= 0xFFFF) {
            sb.append(cp.toChar())
        } else {
            sb.append(CodePoints.highSurrogate(cp))
            sb.append(CodePoints.lowSurrogate(cp))
        }
    }

    companion object {
        private val UNREAD = -2
        private const val MAX_LOOKAHEAD = 2

        fun isClassSyntaxError(error: Throwable?): Boolean {
            return PatternSyntaxExceptions.isClassSyntaxError(error)
        }

        private fun validCaptureName(name: String, underscores: Boolean): Boolean {
            if (name.isEmpty()) return false
            for (i in 0..<name.length) {
                val c: Char = name[i]
                if (Utils.isAlpha(c.code) || (i > 0 && Utils.isDigit(c.code)) || (underscores && c == '_')) continue
                return false
            }
            return true
        }
    }
}
