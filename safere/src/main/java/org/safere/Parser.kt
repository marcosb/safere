// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.safere.RegexLexer.RepetitionMode
import java.util.*
import java.util.List
import java.util.regex.PatternSyntaxException

/**
 * Parser for SafeRE's [Pattern] syntax. Converts a JDK-compatible regular expression string
 * into a [Regexp] AST, rejecting unsupported non-regular constructs rather than accepting
 * another regex dialect as an extension.
 * 
 * 
 * This is a stack-based operator-precedence parser derived from RE2's `parse.cc`. The
 * parser's accepted language is governed by `java.util.regex.Pattern` compatibility and the
 * linear-time execution contract, not by RE2 source syntax compatibility.
 */
internal class Parser private constructor(pattern: String?, flags: Int) {
    // Stack entry: wraps a Regexp with linked-list pointer and extra metadata for parens.
    private class StackEntry(re: Regexp?) {
        var re: Regexp?
        var down: StackEntry? = null

        // For LEFT_PAREN entries:
        var cap: Int = 0 // capture index, or -1 for non-capturing
        var name: String? = null // capture name, or null
        var savedFlags: Int = 0 // flags at time of paren open

        init {
            this.re = re
        }
    }

    private class RepeatCount(cost: Int, hasRepeat: Boolean) {
        val cost: Int
        val hasRepeat: Boolean

        init {
            this.cost = cost
            this.hasRepeat = hasRepeat
        }
    }

    private class RepeatCountFrame(re: Regexp) {
        val re: Regexp
        val multiplier: Int
        var nextSub: Int = 0
        var cost: Int
        var hasRepeat: Boolean = false

        init {
            this.re = re
            if (re.op == RegexpOp.REPEAT) {
                var repeatMultiplier = re.max
                if (repeatMultiplier < 0) {
                    repeatMultiplier = re.min
                }
                if (repeatMultiplier <= 0) {
                    repeatMultiplier = 1
                }
                multiplier = repeatMultiplier
            } else {
                multiplier = 1
            }
            cost = if (re.op == RegexpOp.CONCAT) 0 else 1
        }

        fun addChild(child: RepeatCount, limit: Int) {
            when (re.op) {
                RegexpOp.ALTERNATE -> {
                    cost = Math.max(cost, child.cost)
                    hasRepeat = hasRepeat or child.hasRepeat
                }

                RegexpOp.CONCAT -> {
                    if (child.hasRepeat) {
                        cost = addSaturated(cost, child.cost, limit)
                        hasRepeat = true
                    }
                }

                else -> {
                    if (child.cost > cost) {
                        cost = child.cost
                        hasRepeat = child.hasRepeat
                    }
                }
            }
        }

        fun finish(limit: Int): RepeatCount {
            var subCost = cost
            val subHasRepeat = hasRepeat
            if (re.op == RegexpOp.CONCAT && !subHasRepeat) {
                subCost = 1
            }
            val totalCost: Int = multiplySaturated(multiplier, subCost, limit)
            return RepeatCount(totalCost, re.op == RegexpOp.REPEAT || subHasRepeat)
        }
    }

    // Parse state
    private var flags: Int
    private val lexer: RegexLexer
    private var tokenStart = 0
    private var stacktop: StackEntry?

    // Ordinary literals share one pending run within a flag scope. Flush before every nonliteral
    // token; before a quantifier, leave the final code point as a separate operand on the stack.
    private var firstLiteral = 0
    private var literalCount = 0
    private var literalRunes: IntArray? = null
    private var ncap: Int
    private val namedCaptures: MutableSet<String?> = HashSet<String?>()

    init {
        this.lexer = RegexLexer(pattern!!, flags)
        this.flags = flags
        this.stacktop = null
        this.ncap = 0
    }

    private fun reportIntentionalDivergences(classSyntaxFailure: Boolean) {
        val listener: SafeReMatchDiagnostics = Pattern.diagnostics()
        if (lexer.sawCommentQuoting()) {
            listener.onIntentionalDivergence(IntentionalDivergence.COMMENT_QUOTING)
        }
        if (classSyntaxFailure) {
            listener.onIntentionalDivergence(IntentionalDivergence.CHARACTER_CLASS_SYNTAX)
        }
    }

    // ---- Token-driven iterative parse method ----
    private fun doParse(): Regexp? {
        var lastUnary = false
        var lastUnaryWasBareGreedyPlus = false
        var lastTokenNonRepeatable = false
        while (true) {
            lexer.setFlags(flags)
            val token = lexer.next()
            tokenStart = token.start
            if (literalCount != 0 && token.kind != RegexLexer.Kind.LITERAL) {
                flushLiterals(token.kind == RegexLexer.Kind.REPEAT)
            }
            if (token.kind == RegexLexer.Kind.END) break
            if (WorkCounterConfig.ENABLED) WorkCounter.record()
            var unary = false
            var barePlus = false
            var nonRepeatable = false
            when (token.kind) {
                RegexLexer.Kind.LITERAL -> pushLiteral(token.rune)
                RegexLexer.Kind.OPEN -> {
                    if (token.text != null || (flags and ParseFlags.NEVER_CAPTURE) == 0) {
                        doLeftParen(token.text)
                    } else {
                        doLeftParenNoCapture()
                    }
                }

                RegexLexer.Kind.NONCAPTURE, RegexLexer.Kind.SCOPED_FLAGS -> {
                    doLeftParenNoCapture()
                    flags = token.flags
                }

                RegexLexer.Kind.FLAGS -> {
                    flags = token.flags
                    nonRepeatable = true
                }

                RegexLexer.Kind.CLOSE -> doRightParen()
                RegexLexer.Kind.ALTERNATION -> doVerticalBar()
                RegexLexer.Kind.CARET -> pushCaret()
                RegexLexer.Kind.DOLLAR -> pushDollar()
                RegexLexer.Kind.DOT -> pushDot()
                RegexLexer.Kind.CLASS_OPEN -> pushRegexp(parseCharClass(token.start))
                RegexLexer.Kind.PROPERTY, RegexLexer.Kind.PREDEFINED -> {
                    val ccb = CharClassBuilder()
                    expandClassToken(token, ccb)
                    pushRegexp(finishCharClassBuilder(ccb))
                }

                RegexLexer.Kind.BOUNDARY -> pushWordBoundary(!token.negated)
                RegexLexer.Kind.GRAPHEME_BOUNDARY -> pushSimpleOp(RegexpOp.GRAPHEME_CLUSTER_BOUNDARY)
                RegexLexer.Kind.BEGIN -> pushSimpleOp(RegexpOp.BEGIN_TEXT)
                RegexLexer.Kind.END_TEXT -> pushSimpleOp(RegexpOp.END_TEXT)
                RegexLexer.Kind.FINAL_END -> {
                    val savedFlags = flags
                    flags = flags or ParseFlags.WAS_DOLLAR
                    pushSimpleOp(RegexpOp.END_TEXT)
                    flags = savedFlags
                }

                RegexLexer.Kind.LINEBREAK -> pushRegexp(buildLinebreakRegexp())
                RegexLexer.Kind.GRAPHEME -> pushRegexp(Regexp.graphemeCluster(flags))
                RegexLexer.Kind.REPEAT -> {
                    val counted = token.rune == '{'.code
                    val reluctant = token.mode == RepetitionMode.RELUCTANT
                    val possessive = token.mode == RepetitionMode.POSSESSIVE
                    val implicitEmpty =
                        counted
                                && (lastTokenNonRepeatable
                                || lastUnary
                                || stacktop == null || isMarker(stacktop))
                    if (possessive && !implicitEmpty && !canIgnorePossessiveModifierOnZeroWidthOperand()) {
                        throw lexer.error("possessive quantifiers are not supported", token.start)
                    }
                    if (counted) {
                        validateRepeatCount(token.min, token.max, token.start)
                        if (lastTokenNonRepeatable) {
                            nonRepeatable = true
                        } else if (lastUnary) {
                            unary = true
                        } else {
                            if (possessive
                                && token.min == 0 && token.max != 0 && canObservePossessiveZeroWidthIteration()
                            ) {
                                pushPossessiveZeroWidthRepeatRange(token.max)
                            } else {
                                pushRepetition(token.min, token.max, token.start, reluctant)
                            }
                            unary = true
                        }
                    } else {
                        if (lastTokenNonRepeatable) {
                            throw lexer.error("missing argument to repetition operator", token.start)
                        }
                        val op =
                            if (token.rune == '*'.code)
                                RegexpOp.STAR
                            else
                                if (token.rune == '+'.code) RegexpOp.PLUS else RegexpOp.QUEST
                        if ((flags and ParseFlags.PERL_X) != 0 && lastUnary
                            && !canRepeatAfterUnary(op, lastUnaryWasBareGreedyPlus)
                        ) {
                            throw lexer.error("invalid nested repetition operator", token.start)
                        }
                        if (possessive && op == RegexpOp.STAR && canObservePossessiveZeroWidthIteration()) {
                            pushPossessiveZeroWidthRepeatOp(op)
                        } else {
                            pushRepeatOp(op, token.start, reluctant)
                        }
                        unary = true
                        barePlus = op == RegexpOp.PLUS && !reluctant && !possessive
                    }
                }

                else -> throw AssertionError("unexpected token: " + token.kind)
            }
            lastUnary = unary
            lastUnaryWasBareGreedyPlus = barePlus
            lastTokenNonRepeatable = nonRepeatable
        }
        return doFinish()
    }

    private fun newMarker(markerTag: Int): StackEntry {
        val re: Regexp = Regexp.noMatch(flags)
        re.matchId = markerTag
        val e = StackEntry(re)
        if (markerTag == LEFT_PAREN) {
            e.savedFlags = flags
        }
        return e
    }

    private fun pushRegexp(re: Regexp) {
        // Special case: a character class of one character is just a literal.
        var re = re
        if (re.op == RegexpOp.CHAR_CLASS && re.charClass != null) {
            val cc = re.charClass!!
            if (cc.numRanges() == 1 && cc.lo(0) == cc.hi(0)) {
                val r = cc.lo(0)
                re = Regexp.literal(r, re.flags)
            }
        }

        val e = StackEntry(re)
        e.down = stacktop
        stacktop = e
    }

    private fun pushLiteral(r: Int) {
        if ((flags and (ParseFlags.FOLD_CASE or ParseFlags.NEVER_NL)) == 0) {
            appendLiteral(r)
            return
        }
        // Do case folding if needed.
        if ((flags and ParseFlags.FOLD_CASE) != 0 && (flags and ParseFlags.UNICODE_CASE) != 0) {
            if (UnicodeCaseFolding.hasUnicodeCaseVariant(r) || UnicodeCaseFolding.cycleFoldRune(r) != r) {
                val ccb = CharClassBuilder()
                UnicodeCaseFolding.addUnicodeFoldedRange(ccb, r, r)
                val re = finishCharClassBuilder(ccb)
                pushRegexp(re)
                return
            }
        }

        // Exclude newline if applicable.
        if ((flags and ParseFlags.NEVER_NL) != 0 && r == '\n'.code) {
            pushRegexp(Regexp.noMatch(flags))
            return
        }

        // No fancy stuff worked. Ordinary literal.
        var literalFlags = flags
        if ((flags and ParseFlags.FOLD_CASE) != 0 && (flags and ParseFlags.UNICODE_CASE) == 0 && UnicodeCaseFolding.asciiFoldRune(
                r
            ) == r && !('a'.code <= r && r <= 'z'.code)
        ) {
            literalFlags = literalFlags and ParseFlags.FOLD_CASE.inv()
        }
        val re: Regexp = Regexp.literal(r, literalFlags)
        pushRegexp(re)
    }

    private fun appendLiteral(rune: Int) {
        if (literalCount == 0) {
            firstLiteral = rune
        } else {
            if (literalRunes == null) {
                literalRunes = IntArray(8)
            } else {
                val runes = literalRunes!!
                if (literalCount == runes.size) {
                    if (WorkCounterConfig.ENABLED) WorkCounter.record(runes.size.toLong())
                    val capacity =
                        if (runes.size <= Integer.MAX_VALUE / 2)
                            runes.size * 2
                        else
                            Integer.MAX_VALUE
                    literalRunes = Arrays.copyOf(runes, capacity)
                }
            }
            if (literalCount == 1) literalRunes!![0] = firstLiteral
            literalRunes!![literalCount] = rune
        }
        literalCount++
    }

    private fun flushLiterals(separateLast: Boolean) {
        val length = literalCount - (if (separateLast) 1 else 0)
        if (length == 1) {
            pushRegexp(Regexp.literal(firstLiteral, flags))
        } else if (length > 1) {
            if (WorkCounterConfig.ENABLED) WorkCounter.record(2L * length)
            // Regexp takes its own copy, allowing the parser's scratch array to serve later runs.
            pushRegexp(Regexp.literalString(Arrays.copyOf(literalRunes, length), flags))
        }
        if (separateLast) {
            val last = if (literalCount == 1) firstLiteral else literalRunes!![literalCount - 1]
            pushRegexp(Regexp.literal(last, flags))
        }
        literalCount = 0
    }

    private fun pushCaret() {
        if ((flags and ParseFlags.ONE_LINE) != 0) {
            pushSimpleOp(RegexpOp.BEGIN_TEXT)
        } else {
            pushSimpleOp(RegexpOp.BEGIN_LINE)
        }
    }

    private fun pushDollar() {
        if ((flags and ParseFlags.ONE_LINE) != 0) {
            val oflags = flags
            flags = flags or ParseFlags.WAS_DOLLAR
            pushSimpleOp(RegexpOp.END_TEXT)
            flags = oflags
        } else {
            pushSimpleOp(RegexpOp.END_LINE)
        }
    }

    private fun pushDot() {
        if ((flags and ParseFlags.DOT_NL) != 0 && (flags and ParseFlags.NEVER_NL) == 0) {
            pushSimpleOp(RegexpOp.ANY_CHAR)
        } else if ((flags and ParseFlags.UNIX_LINES) != 0) {
            // UNIX_LINES: . matches everything except \n
            val ccb = CharClassBuilder()
            ccb.addRange(0, '\n'.code - 1)
            ccb.addRange('\n'.code + 1, Utils.MAX_RUNE)
            val re: Regexp = Regexp.charClass(ccb.build(), flags and ParseFlags.FOLD_CASE.inv())
            pushRegexp(re)
        } else {
            // Default JDK behavior: . matches everything except line terminators
            // (\n, \r, \u0085, \u2028, \u2029)
            val ccb = CharClassBuilder()
            ccb.addRange(0, '\n'.code - 1) // 0x00–0x09
            ccb.addRange('\n'.code + 1, '\r'.code - 1) // 0x0B–0x0C
            ccb.addRange('\r'.code + 1, '\u0085'.code - 1) // 0x0E–0x0084
            ccb.addRange('\u0085'.code + 1, '\u2028'.code - 1) // 0x0086–0x2027
            ccb.addRange('\u2029'.code + 1, Utils.MAX_RUNE) // 0x202A–max
            val re: Regexp = Regexp.charClass(ccb.build(), flags and ParseFlags.FOLD_CASE.inv())
            pushRegexp(re)
        }
    }

    private fun pushWordBoundary(word: Boolean) {
        if (word) {
            pushSimpleOp(RegexpOp.WORD_BOUNDARY)
        } else {
            pushSimpleOp(RegexpOp.NO_WORD_BOUNDARY)
        }
    }

    private fun pushSimpleOp(op: RegexpOp) {
        val re: Regexp =
            when (op) {
                RegexpOp.BEGIN_LINE -> Regexp.beginLine(flags)
                RegexpOp.END_LINE -> Regexp.endLine(flags)
                RegexpOp.BEGIN_TEXT -> Regexp.beginText(flags)
                RegexpOp.END_TEXT -> Regexp.endText(flags)
                RegexpOp.ANY_CHAR -> Regexp.anyChar(flags)
                RegexpOp.WORD_BOUNDARY -> Regexp.wordBoundary(flags)
                RegexpOp.NO_WORD_BOUNDARY -> Regexp.noWordBoundary(flags)
                RegexpOp.GRAPHEME_CLUSTER_BOUNDARY -> Regexp.graphemeClusterBoundary(flags)
                RegexpOp.GRAPHEME_CLUSTER -> Regexp.graphemeCluster(flags)
                else -> Regexp.emptyMatch(flags)
            }
        pushRegexp(re)
    }

    private fun pushRepeatOp(op: RegexpOp?, opStart: Int, nongreedy: Boolean) {
        if (stacktop == null || isMarker(stacktop)) {
            throw lexer.error("missing argument to repetition operator", opStart)
        }

        var fl = flags
        if (nongreedy) {
            fl = fl xor ParseFlags.NON_GREEDY
        }

        // Squash **, ++ and ??.
        if (op == stacktop!!.re!!.op && fl == stacktop!!.re!!.flags) {
            return
        }

        // Squash *+, *?, +*, +?, ?* and ?+. They all squash to *.
        if ((stacktop!!.re!!.op == RegexpOp.STAR || stacktop!!.re!!.op == RegexpOp.PLUS || stacktop!!.re!!.op == RegexpOp.QUEST)
            && fl == stacktop!!.re!!.flags
        ) {
            // Replace with star. Since Regexp is immutable, rebuild.
            val sub: Regexp? = stacktop!!.re!!.subs!!.first()
            stacktop!!.re = Regexp.star(sub, fl)
            return
        }

        val sub = stacktop!!.re
        val re: Regexp =
            when (op) {
                RegexpOp.STAR -> Regexp.star(sub, fl)
                RegexpOp.PLUS -> Regexp.plus(sub, fl)
                RegexpOp.QUEST -> Regexp.quest(sub, fl)
                else -> throw IllegalStateException("unexpected repeat op: " + op)
            }
        stacktop!!.re = re
    }

    private fun pushPossessiveZeroWidthRepeatOp(op: RegexpOp) {
        when (op) {
            RegexpOp.STAR -> {
                val sub = stacktop!!.re
                stacktop!!.re = Regexp.rawQuantifier(RegexpOp.QUEST, Regexp.plus(sub, flags), flags)
            }

            RegexpOp.PLUS -> pushRepeatOp(RegexpOp.PLUS, tokenStart, false)
            RegexpOp.QUEST -> {
                // Best-effort zero-width possessive normalization: prefer the one-iteration capture path.
            }

            else -> throw IllegalStateException("unexpected repeat op: " + op)
        }
    }

    private fun pushPossessiveZeroWidthRepeatRange(max: Int) {
        val sub = stacktop!!.re
        val positiveRepeat: Regexp? = if (max == 1) sub else Regexp.repeat(sub, flags, 1, max)
        stacktop!!.re = Regexp.rawQuantifier(RegexpOp.QUEST, positiveRepeat, flags)
    }

    private fun canRepeatAfterUnary(op: RegexpOp?, lastUnaryWasBareGreedyPlus: Boolean): Boolean {
        return lastUnaryWasBareGreedyPlus
                && op == RegexpOp.PLUS && stacktop != null && stacktop!!.re!!.op == RegexpOp.PLUS && !stacktop!!.re!!.nonGreedy() && isQuantifiedZeroWidth(
            stacktop!!.re!!
        )
    }

    private fun canIgnorePossessiveModifierOnZeroWidthOperand(): Boolean {
        return stacktop != null && !isMarker(stacktop) && isZeroWidth(stacktop!!.re!!)
    }

    private fun canObservePossessiveZeroWidthIteration(): Boolean {
        return stacktop != null && !isMarker(stacktop) && hasCapture(stacktop!!.re!!)
                && isZeroWidth(stacktop!!.re!!)
    }

    private fun pushRepetition(min: Int, max: Int, opStart: Int, nongreedy: Boolean) {
        validateRepeatCount(min, max, opStart)
        if (stacktop == null || isMarker(stacktop)) {
            pushRegexp(Regexp.emptyMatch(flags))
        }

        var fl = flags
        if (nongreedy) {
            fl = fl xor ParseFlags.NON_GREEDY
        }

        val sub = stacktop!!.re
        val re: Regexp = Regexp.repeat(sub, fl, min, max)
        stacktop!!.re = re

        // Check for too-deep nesting of repeats.
        if (min >= 2 || max >= 2) {
            if (countRepeat(stacktop!!.re!!, MAX_REPEAT) == 0) {
                throw lexer.error("invalid repeat count", opStart)
            }
        }
    }

    private fun validateRepeatCount(min: Int, max: Int, opStart: Int) {
        if ((max != -1 && max < min) || min > MAX_REPEAT || max > MAX_REPEAT) {
            throw lexer.error("invalid repeat count", opStart)
        }
    }

    private fun doLeftParen(name: String?) {
        if (name != null && !namedCaptures.add(name)) {
            throw lexer.error("named capturing group <" + name + "> is already defined", tokenStart)
        }
        val e = newMarker(LEFT_PAREN)
        e.cap = ++ncap
        e.name = name
        e.savedFlags = flags
        e.down = stacktop
        stacktop = e
    }

    private fun doLeftParenNoCapture() {
        val e = newMarker(LEFT_PAREN)
        e.cap = -1
        e.savedFlags = flags
        e.down = stacktop
        stacktop = e
    }

    private fun doVerticalBar() {
        doConcatenation()

        // Below the vertical bar is a list to alternate.
        // Above the vertical bar is a list to concatenate.
        // We just did the concatenation, so either swap
        // the result below the vertical bar or push a new one.
        val r1 = stacktop
        val r2 = if (r1 != null) r1.down else null
        if (r1 != null && r2 != null && isVerticalBar(r2)) {
            // Swap r1 below vertical bar (r2).
            r1.down = r2.down
            r2.down = r1
            stacktop = r2
            return
        }

        // Push a vertical bar marker.
        val vbar = newMarker(VERTICAL_BAR)
        vbar.down = stacktop
        stacktop = vbar
    }

    private fun doRightParen() {
        // Finish current concatenation and alternation.
        doAlternation()

        // The stack should be: LeftParen regexp
        val r1 = stacktop
        val r2: StackEntry = (if (r1 != null) r1.down else null)!!
        if (r1 == null || r2 == null || !isLeftParen(r2)) {
            throw lexer.error("unexpected )", tokenStart)
        }

        // Pop off r1, r2.
        stacktop = r2.down

        // Restore flags from when paren opened.
        flags = r2.savedFlags

        // Rewrite LeftParen as capture if needed.
        if (r2.cap > 0) {
            val re: Regexp = Regexp.capture(r1.re, flags, r2.cap, r2.name)
            pushRegexp(re)
        } else {
            pushRegexp(Regexp.nonCapture(r1.re, flags))
        }
    }

    private fun doFinish(): Regexp? {
        doAlternation()
        val top = stacktop
        if (top != null && top.down != null) {
            throw lexer.error("missing closing )", lexer.length())
        }
        if (top == null) {
            return Regexp.emptyMatch(flags)
        }
        stacktop = null
        return top.re
    }

    private fun doConcatenation() {
        val r1 = stacktop
        if (r1 == null || isMarker(r1)) {
            // Empty concatenation.
            val re: Regexp = Regexp.emptyMatch(flags)
            pushRegexp(re)
        }
        doCollapse(RegexpOp.CONCAT)
    }

    private fun doAlternation() {
        doVerticalBar()
        // Now stack top is kVerticalBar.
        val r1 = stacktop
        stacktop = r1!!.down
        doCollapse(RegexpOp.ALTERNATE)
    }

    private fun doCollapse(op: RegexpOp?) {
        // Scan backward to marker, counting children of composite.
        var n = 0
        run {
            var e = stacktop
            while (e != null && !isMarker(e)) {
                if (e.re!!.op == op && e.re!!.subs != null) {
                    n += e.re!!.subs!!.size
                } else {
                    n++
                }
                e = e.down
            }
        }

        // If there's just one child, leave it alone.
        if (stacktop != null && !isMarker(stacktop)) {
            val first = stacktop
            val belowFirst = first!!.down
            if (belowFirst == null || isMarker(belowFirst)) {
                return  // just one
            }
        }

        // Construct op (alternation or concatenation), flattening op of op.
        // We build the list in reverse order (walking the stack from top), then reverse.
        var subs: MutableList<Regexp> = ArrayList<Regexp>(n)
        var next: StackEntry?
        var e = stacktop
        while (e != null && !isMarker(e)) {
            next = e.down
            if (e.re!!.op == op && e.re!!.subs != null) {
                for (k in e.re!!.subs!!.indices.reversed()) {
                    subs.add(e.re!!.subs!!.get(k)!!)
                }
            } else {
                subs.add(e.re!!)
            }
            if (next == null || isMarker(next)) {
                stacktop = next
                break
            }
            e = next
        }
        Collections.reverse(subs)
        if (op == RegexpOp.CONCAT) {
            subs = coalesceLiteralStrings(subs)
        }

        val re: Regexp?
        if (op == RegexpOp.CONCAT && subs.size == 1) {
            re = subs.first()
        } else {
            re = if (op == RegexpOp.CONCAT) Regexp.concat(subs, flags) else Regexp.alternate(
                subs,
                flags
            )
        }
        val entry = StackEntry(re)
        entry.down = stacktop
        stacktop = entry
    }

    // ---- Character-class grammar over shared lexical tokens ----
    private fun parseCharClass(rootStart: Int): Regexp {
        val stack: ArrayDeque<ClassExpressionFrame> = ArrayDeque<ClassExpressionFrame>()
        stack.push(newClassFrame(rootStart))
        while (!stack.isEmpty()) {
            val frame = stack.peek()
            val token = lexer.takeClass()
            val start = token!!.start
            when (token!!.kind) {
                RegexLexer.Kind.END -> throw lexer.error("missing closing ]", frame.classStart)
                RegexLexer.Kind.CLASS_CLOSE -> {
                    if (!frame.hasItems && !frame.afterIntersection) {
                        addScalarClassItem(frame.currentUnion, ']'.code)
                        frame.hasItems = true
                        continue
                    }
                    if (frame.afterIntersection) throw lexer.classError(
                        "empty right side of character class intersection",
                        start
                    )
                    val completed = completeClassExpression(frame)!!
                    stack.pop()
                    if (stack.isEmpty()) return finishCharClassBuilder(completed)
                    val parent = stack.peek()
                    parent.currentUnion.addCharClass(completed)
                    parent.hasItems = true
                    parent.afterIntersection = false
                }

                RegexLexer.Kind.CLASS_OPEN -> stack.push(newClassFrame(start))
                RegexLexer.Kind.INTERSECTION -> {
                    if (!frame.hasItems || frame.afterIntersection) {
                        throw lexer.classError("empty left side of character class intersection", start)
                    }
                    if (frame.leftOperand == null) frame.leftOperand = frame.currentUnion
                    else frame.leftOperand!!.intersect(frame.currentUnion)
                    frame.currentUnion = CharClassBuilder()
                    frame.afterIntersection = true
                    val next = lexer.peekClass().kind
                    if (next == RegexLexer.Kind.AMPERSAND || next == RegexLexer.Kind.INTERSECTION) {
                        throw lexer.classError("invalid character class intersection", start)
                    }
                    if (next == RegexLexer.Kind.HYPHEN) throw lexer.classError(
                        "dangling character class '-'",
                        lexer.peekClass().start
                    )
                }

                RegexLexer.Kind.PROPERTY, RegexLexer.Kind.PREDEFINED -> {
                    val verticalTab =
                        token!!.kind == RegexLexer.Kind.PREDEFINED && token!!.rune == 'v'.code && !token!!.negated
                    expandClassToken(token, frame.currentUnion)
                    if (verticalTab
                        && lexer.peekClass().kind == RegexLexer.Kind.HYPHEN && !lexer.peekClass().leadingTrivia
                    ) {
                        throw lexer.classError(
                            "ambiguous \\v before '-' in a character class: use \\x0B for U+000B, or escape the"
                                    + " hyphen as \\-",
                            start
                        )
                    }
                    frame.hasItems = true
                    frame.afterIntersection = false
                }

                RegexLexer.Kind.LITERAL, RegexLexer.Kind.CARET, RegexLexer.Kind.HYPHEN, RegexLexer.Kind.AMPERSAND -> {
                    if (frame.afterIntersection && token!!.kind == RegexLexer.Kind.HYPHEN) {
                        throw lexer.classError("dangling character class '-'", start)
                    }
                    addScalarClassItem(frame.currentUnion, token!!.rune)
                    frame.hasItems = true
                    frame.afterIntersection = false
                }

                else -> throw lexer.error("bad class syntax", start)
            }
        }
        throw AssertionError("class stack exhausted")
    }

    private fun newClassFrame(start: Int): ClassExpressionFrame {
        val first = lexer.peekClass()
        val negated = first.kind == RegexLexer.Kind.CARET && !first.leadingTrivia
        if (negated) lexer.takeClass()
        return ClassExpressionFrame(start, negated)
    }

    private fun addScalarClassItem(ccb: CharClassBuilder?, lo: Int) {
        if (lexer.peekClass().kind == RegexLexer.Kind.HYPHEN) {
            lexer.takeClass()
            val endpoint = lexer.peekClassRangeEndpoint()
            if (endpoint.kind == RegexLexer.Kind.CLASS_CLOSE || endpoint.kind == RegexLexer.Kind.CLASS_OPEN || endpoint.kind == RegexLexer.Kind.END) {
                addRangeFlags(ccb, lo, lo, flags or ParseFlags.CLASS_NL)
                addRangeFlags(ccb, '-'.code, '-'.code, flags or ParseFlags.CLASS_NL)
                return
            }
            if (endpoint.kind == RegexLexer.Kind.PREDEFINED && endpoint.rune == 'v'.code && !endpoint.negated) {
                throw lexer.classError(
                    "ambiguous \\v as a character class range end: use \\x0B for U+000B, or escape the"
                            + " hyphen as \\-",
                    endpoint.start
                )
            }
            if (endpoint.kind != RegexLexer.Kind.LITERAL && endpoint.kind != RegexLexer.Kind.CARET && endpoint.kind != RegexLexer.Kind.HYPHEN && endpoint.kind != RegexLexer.Kind.AMPERSAND) {
                throw lexer.error("invalid escape sequence", endpoint.start)
            }
            val hi = endpoint.rune
            val end = endpoint.start
            lexer.takeClass()
            if (hi < lo) throw lexer.error("invalid character class range", end)
            addRangeFlags(ccb, lo, hi, flags or ParseFlags.CLASS_NL)
        } else {
            addRangeFlags(ccb, lo, lo, flags or ParseFlags.CLASS_NL)
        }
    }

    private fun expandClassToken(token: RegexLexer.Token, ccb: CharClassBuilder?) {
        val group =
            if (token.kind == RegexLexer.Kind.PROPERTY)
                CharClassExpander.lookupUnicodeGroup(
                    token.text!!, (flags and ParseFlags.UNICODE_CHAR_CLASS) != 0
                )
            else
                CharClassExpander.lookupPerlGroup(
                    "\\" + token.rune.toChar(), (flags and ParseFlags.UNICODE_CHAR_CLASS) != 0
                )
        if (group == null) throw lexer.error("invalid Unicode group: " + token.text, token.start)
        if (token.negated) CharClassExpander.addNegatedGroup(ccb!!, group, flags)
        else CharClassExpander.addPositiveGroup(ccb!!, group, flags)
    }

    private fun completeClassExpression(frame: ClassExpressionFrame): CharClassBuilder {
        val result: CharClassBuilder?
        if (frame.leftOperand != null) {
            frame.leftOperand!!.intersect(frame.currentUnion)
            result = frame.leftOperand
        } else {
            result = frame.currentUnion
        }
        if (frame.negated) {
            if ((flags and ParseFlags.CLASS_NL) == 0 || (flags and ParseFlags.NEVER_NL) != 0) {
                result!!.addRune('\n'.code)
            }
            result!!.negate()
        }
        return result!!
    }

    private class ClassExpressionFrame(classStart: Int, negated: Boolean) {
        val classStart: Int
        val negated: Boolean
        var leftOperand: CharClassBuilder? = null
        var currentUnion: CharClassBuilder = CharClassBuilder()
        var afterIntersection: Boolean = false
        var hasItems: Boolean = false

        init {
            this.classStart = classStart
            this.negated = negated
        }
    }

    // ---- Helper: finish CharClassBuilder into a Regexp ----
    private fun finishCharClassBuilder(ccb: CharClassBuilder): Regexp {
        return Regexp.charClass(ccb.build(), flags and ParseFlags.FOLD_CASE.inv())
    }

    // ---- \R expansion helper ----
    /**
     * Builds a Regexp equivalent to `(?:\r\n|[\n\x0B\f\r\x{85}\x{2028}\x{2029}])`, which
     * matches any Unicode linebreak sequence. With POSIX leftmost-longest semantics, the two-char
     * `\r\n` alternative naturally wins over a single `\r`.
     */
    private fun buildLinebreakRegexp(): Regexp {
        // Alternative 1: \r\n (CRLF as a single unit)
        val crLf: Regexp = Regexp.literalString(intArrayOf('\r'.code, '\n'.code), flags)

        // Alternative 2: any single linebreak character
        val ccb = CharClassBuilder()
        ccb.addRune('\n'.code) // U+000A LINE FEED
        ccb.addRune(0x000B) // U+000B VERTICAL TAB
        ccb.addRune(0x000C) // U+000C FORM FEED
        ccb.addRune('\r'.code) // U+000D CARRIAGE RETURN
        ccb.addRune(0x85) // U+0085 NEXT LINE
        ccb.addRune(0x2028) // U+2028 LINE SEPARATOR
        ccb.addRune(0x2029) // U+2029 PARAGRAPH SEPARATOR
        val singleLinebreak: Regexp = Regexp.charClass(ccb.build()!!, flags)

        return Regexp.alternate(List.of<Regexp?>(crLf, singleLinebreak), flags)
    }

    companion object {
        // Maximum repeat count to prevent excessive AST expansion. Matches RE2's kMaxRepeat.
        private const val MAX_REPEAT = 1000

        // Pseudo-ops used only on the parse stack (never in the final AST).
        // Values must be negative so that isMarker()/tag() can distinguish them from
        // real HAVE_MATCH matchIds (which are non-negative).
        private val LEFT_PAREN = -1
        private val VERTICAL_BAR = -2

        /**
         * Parses a regular expression pattern into a [Regexp] AST.
         * 
         * @param pattern the regular expression pattern
         * @param flags parse flags from [ParseFlags]
         * @return the parsed AST
         * @throws PatternSyntaxException if the pattern is invalid
         */
        fun parse(pattern: String?, flags: Int): Regexp? {
            val p = Parser(pattern, flags)
            val result: Regexp?
            try {
                result = p.doParse()
            } catch (syntaxFailure: PatternSyntaxException) {
                try {
                    p.reportIntentionalDivergences(RegexLexer.isClassSyntaxError(syntaxFailure))
                } catch (notificationFailure: RuntimeException) {
                    if (syntaxFailure != notificationFailure) {
                        syntaxFailure.addSuppressed(notificationFailure)
                    }
                } catch (notificationFailure: Error) {
                    if (syntaxFailure != notificationFailure) {
                        syntaxFailure.addSuppressed(notificationFailure)
                    }
                }
                throw syntaxFailure
            }
            p.reportIntentionalDivergences(false)
            return result
        }

        // ---- Stack operations ----
        private fun isMarker(e: StackEntry?): Boolean {
            return e != null && e.re != null && e.re!!.matchId < 0 && e.re!!.op == RegexpOp.NO_MATCH
        }

        private fun isLeftParen(e: StackEntry?): Boolean {
            return isMarker(e) && e!!.re!!.matchId == LEFT_PAREN
        }

        private fun isVerticalBar(e: StackEntry?): Boolean {
            return isMarker(e) && e!!.re!!.matchId == VERTICAL_BAR
        }

        private fun hasCapture(re: Regexp): Boolean {
            val pending = ArrayDeque<Regexp>()
            pending.add(re)
            while (!pending.isEmpty()) {
                val current = pending.removeLast()
                if (current.op == RegexpOp.CAPTURE && current.cap > 0) {
                    return true
                }
                if (current.subs != null) {
                    pending.addAll(current.subs!!)
                }
            }
            return false
        }

        private fun isQuantifiedZeroWidth(re: Regexp): Boolean {
            return when (re.op) {
                RegexpOp.STAR, RegexpOp.PLUS, RegexpOp.QUEST, RegexpOp.REPEAT -> isZeroWidth(re.subs!!.first()!!)
                else -> false
            }
        }

        private fun isZeroWidth(re: Regexp): Boolean {
            val pending = ArrayDeque<Regexp>()
            pending.add(re)
            while (!pending.isEmpty()) {
                val current = pending.removeLast()
                when (current.op) {
                    RegexpOp.EMPTY_MATCH, RegexpOp.BEGIN_LINE, RegexpOp.END_LINE, RegexpOp.WORD_BOUNDARY, RegexpOp.NO_WORD_BOUNDARY, RegexpOp.GRAPHEME_CLUSTER_BOUNDARY, RegexpOp.BEGIN_TEXT, RegexpOp.END_TEXT -> {
                        // Zero-width by definition.
                    }

                    RegexpOp.CAPTURE, RegexpOp.NON_CAPTURE, RegexpOp.STAR, RegexpOp.PLUS, RegexpOp.QUEST -> pending.add(
                        current.subs!!.first()
                    )

                    RegexpOp.REPEAT -> {
                        // A zero-count repetition never executes its child, regardless of the child's width.
                        if (current.max != 0) {
                            pending.add(current.subs!!.first())
                        }
                    }

                    RegexpOp.CONCAT, RegexpOp.ALTERNATE -> pending.addAll(current.subs!!)
                    else -> {
                        return false
                    }
                }
            }
            return true
        }

        // Walk the regexp tree to check that nested repetitions don't exceed limit.
        private fun countRepeat(re: Regexp, limit: Int): Int {
            val count: RepeatCount = repeatCount(re, limit)
            if (count.cost > limit) {
                return 0
            }
            return limit / count.cost
        }

        private fun repeatCount(re: Regexp, limit: Int): RepeatCount {
            val stack: ArrayDeque<RepeatCountFrame> = ArrayDeque<RepeatCountFrame>()
            stack.push(RepeatCountFrame(re))
            var result: RepeatCount? = null
            while (!stack.isEmpty()) {
                val frame = stack.peek()
                val nsub = if (frame.re.subs == null) 0 else frame.re.subs!!.size
                if (frame.nextSub < nsub) {
                    stack.push(RepeatCountFrame(frame.re!!.subs!!.get(frame.nextSub)!!))
                    frame.nextSub++
                    continue
                }

                result = frame.finish(limit)
                stack.pop()
                if (!stack.isEmpty()) {
                    stack.peek().addChild(result, limit)
                }
            }
            return result!!
        }

        private fun multiplySaturated(a: Int, b: Int, limit: Int): Int {
            if (a != 0 && b > limit / a) {
                return limit + 1
            }
            return a * b
        }

        private fun addSaturated(a: Int, b: Int, limit: Int): Int {
            if (b > limit - a) {
                return limit + 1
            }
            return a + b
        }

        // ---- Literal string coalescing ----
        private fun coalesceLiteralStrings(subs: MutableList<Regexp>): MutableList<Regexp> {
            val coalesced: MutableList<Regexp> = ArrayList<Regexp>(subs.size)
            var literalStart = -1
            var literalLength = 0
            var literalFlags = 0
            for (i in subs.indices) {
                val re = subs.get(i)
                if (!isLiteralStringPart(re)
                    || (literalStart >= 0 && literalFold(literalFlags) != literalFold(re.flags))
                ) {
                    flushLiteralString(subs, coalesced, literalStart, i, literalLength, literalFlags)
                    literalStart = -1
                    literalLength = 0
                    coalesced.add(re)
                    continue
                }

                if (literalStart < 0) {
                    literalStart = i
                    literalFlags = re.flags
                }
                literalLength += literalLength(re)
            }
            flushLiteralString(subs, coalesced, literalStart, subs.size, literalLength, literalFlags)
            return coalesced
        }

        private fun flushLiteralString(
            source: MutableList<Regexp>,
            target: MutableList<Regexp>,
            start: Int,
            end: Int,
            runeCount: Int,
            literalFlags: Int
        ) {
            if (start < 0) {
                return
            }
            if (end - start == 1) {
                target.add(source.get(start))
                return
            }

            val runes = IntArray(runeCount)
            var out = 0
            for (i in start..<end) {
                val re = source.get(i)
                if (re.op == RegexpOp.LITERAL) {
                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.record()
                    }
                    runes[out++] = re.rune
                } else {
                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.record(re.runes.size.toLong())
                    }
                    System.arraycopy(re.runes, 0, runes, out, re.runes.size)
                    out += re.runes.size
                }
            }
            target.add(Regexp.literalString(runes, literalFlags))
        }

        private fun isLiteralStringPart(re: Regexp): Boolean {
            return re.op == RegexpOp.LITERAL || re.op == RegexpOp.LITERAL_STRING
        }

        private fun literalFold(flags: Int): Boolean {
            return (flags and ParseFlags.FOLD_CASE) != 0
        }

        private fun literalLength(re: Regexp): Int {
            return if (re.op == RegexpOp.LITERAL) 1 else re.runes.size
        }

        private fun addRangeFlags(ccb: CharClassBuilder?, lo: Int, hi: Int, parseFlags: Int) {
            CharClassExpander.addRange(ccb!!, lo, hi, parseFlags)
        }
    }
}
