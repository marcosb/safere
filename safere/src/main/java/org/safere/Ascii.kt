// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere


import kotlin.jvm.JvmOverloads
import kotlin.math.max
import kotlin.math.min

/** ASCII character classification and case-folding utilities.  */
internal object Ascii {
    /** Converts an ASCII uppercase character to lowercase; leaves other characters unchanged.  */
    fun toLowerCase(ch: Char): Char {
        return if (ch >= 'A' && ch <= 'Z') (ch.code + 32).toChar() else ch
    }

    /** Converts an ASCII lowercase character to uppercase; leaves other characters unchanged.  */
    fun toUpperCase(ch: Char): Char {
        return if (ch >= 'a' && ch <= 'z') (ch.code - 32).toChar() else ch
    }

    /** Converts an ASCII uppercase code point to lowercase; leaves other code points unchanged.  */
    fun toLowerCase(cp: Int): Int {
        return if (cp >= 'A'.code && cp <= 'Z'.code) (cp + 32) else cp
    }

    /** Converts an ASCII lowercase code point to uppercase; leaves other code points unchanged.  */
    fun toUpperCase(cp: Int): Int {
        return if (cp >= 'a'.code && cp <= 'z'.code) (cp - 32) else cp
    }

    /** Returns whether two characters are equal ignoring ASCII case.  */
    fun equalsIgnoreCase(a: Char, b: Char): Boolean {
        return a == b || toLowerCase(a) == toLowerCase(b)
    }

    /** Returns whether two code points or bytes are equal ignoring ASCII case.  */
    fun equalsIgnoreCase(a: Int, b: Int): Boolean {
        return a == b || toLowerCase(a) == toLowerCase(b)
    }

    /** Returns true if the code point is an ASCII uppercase letter.  */
    fun isUpper(r: Int): Boolean {
        return r >= 'A'.code && r <= 'Z'.code
    }

    /** Returns true if the code point is an ASCII lowercase letter.  */
    fun isLower(r: Int): Boolean {
        return r >= 'a'.code && r <= 'z'.code
    }

    /** Returns true if the code point is an ASCII letter.  */
    fun isAlpha(r: Int): Boolean {
        return (r >= 'A'.code && r <= 'Z'.code) || (r >= 'a'.code && r <= 'z'.code)
    }

    /** Returns true if all characters in the CharSequence are ASCII (<= 127).  */
    fun isAscii(cs: CharSequence?): Boolean {
        if (cs == null) {
            return true
        }
        for (i in 0..<cs.length) {
            if (cs[i].code > 127) {
                return false
            }
        }
        return true
    }

    /** Returns true if the code point is an ASCII digit.  */
    fun isDigit(r: Int): Boolean {
        return r >= '0'.code && r <= '9'.code
    }

    private const val WORD_LOW = 0x03FF000000000000L // '0'-'9' (bits 48-57)
    private const val WORD_HIGH = 0x07FFFFFE87FFFFFEL // 'A'-'Z' (bits 1-26), '_' (bit 31), 'a'-'z' (bits 33-58)
    private const val ALNUM_HIGH = 0x07FFFFFE07FFFFFEL // 'A'-'Z' (bits 1-26), 'a'-'z' (bits 33-58)

    /** Returns true if the code point is an ASCII letter or digit.  */
    fun isAlnum(r: Int): Boolean {
        if ((r and 63.inv()) == 0) {
            return ((WORD_LOW ushr r) and 1L) != 0L
        }
        if ((r and 127.inv()) == 0) {
            return ((ALNUM_HIGH ushr (r - 64)) and 1L) != 0L
        }
        return false
    }

    /** Returns true if the code point is an ASCII word character (letter, digit, or underscore).  */
    fun isWordChar(r: Int): Boolean {
        if ((r and 63.inv()) == 0) {
            return ((WORD_LOW ushr r) and 1L) != 0L
        }
        if ((r and 127.inv()) == 0) {
            return ((WORD_HIGH ushr (r - 64)) and 1L) != 0L
        }
        return false
    }

    /** Returns true if the code point is an ASCII hex digit.  */
    fun isHexDigit(r: Int): Boolean {
        return (r >= '0'.code && r <= '9'.code) || (r >= 'A'.code && r <= 'F'.code) || (r >= 'a'.code && r <= 'f'.code)
    }

    /**
     * Returns the value of a hex digit, or -1 if not a hex digit.
     * 
     * @param r a code point
     * @return 0-15 for valid hex digits, -1 otherwise
     */
    fun unhex(r: Int): Int {
        if (r >= '0'.code && r <= '9'.code) {
            return r - '0'.code
        }
        if (r >= 'A'.code && r <= 'F'.code) {
            return r - 'A'.code + 10
        }
        if (r >= 'a'.code && r <= 'f'.code) {
            return r - 'a'.code + 10
        }
        return -1
    }

    /** Builds the KMP failure function for an ASCII case-insensitive pattern.  */
    fun ignoreCaseFailure(pattern: String): IntArray {
        val failure = IntArray(pattern.length)
        var matched = 0
        for (i in 1..<pattern.length) {
            while (matched > 0 && !Ascii.equalsIgnoreCase(pattern[i], pattern[matched])) {
                matched = failure[matched - 1]
            }
            if (Ascii.equalsIgnoreCase(pattern[i], pattern[matched])) {
                matched++
            }
            failure[i] = matched
        }
        return failure
    }

    fun indexOfLinearIgnoreCase(text: String, prefix: String, start: Int): Int {
        return indexOfLinearIgnoreCase(text, prefix, ignoreCaseFailure(prefix), start)
    }

    /** KMP over a bounded range of the original String, without a substring allocation.  */
    /** Knuth-Morris-Pratt scan on String, strictly linear in text length regardless of pattern.  */
    @JvmOverloads
    fun indexOfLinearIgnoreCase(
        text: String, prefix: String, failure: IntArray, start: Int, end: Int = text.length
    ): Int {
        var matched = 0
        val prefixLen: Int = prefix.length
        val length = end
        for (position in max(0, start)..<length) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            val current: Char = text[position]
            val lower = toLowerCase(current)
            while (matched > 0 && lower != prefix[matched]) {
                matched = failure[matched - 1]
            }
            if (lower == prefix[matched]) {
                matched++
                if (matched == prefixLen) {
                    return position - prefixLen + 1
                }
            }
        }
        return -1
    }

    /** Returns the first index of character `ch` ignoring ASCII case, or -1 if not found.  */
    fun indexOfIgnoreCase(text: String, ch: Char, fromIndex: Int): Int {
        if (ch.code > 127) {
            return text.indexOf(ch, fromIndex)
        }
        val low = toLowerCase(ch)
        val high = toUpperCase(ch)
        return indexOfIgnoreCase(text, low, high, fromIndex)
    }

    /**
     * Returns the first index of character matching `low` or `high`, or -1 if not found.
     */
    fun indexOfIgnoreCase(text: String, low: Char, high: Char, fromIndex: Int): Int {
        if (low == high) {
            return text.indexOf(low, fromIndex)
        }
        for (i in max(0, fromIndex)..<text.length) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            val value: Char = text[i]
            if (value == low || value == high) {
                return i
            }
        }
        return -1
    }

    /** Returns the minimum of two indices that is >= 0, or -1 if both are negative.  */
    fun minNonNegative(a: Int, b: Int): Int {
        if (a < 0) {
            return b
        }
        if (b < 0) {
            return a
        }
        return min(a, b)
    }

    /** Returns whether a string matches a pattern prefix ignoring ASCII case.  */
    fun regionMatchesIgnoreCase(text: String, offset: Int, prefix: String, prefixLen: Int): Boolean {
        return regionMatchesIgnoreCase(text, offset, prefix, 0, prefixLen)
    }

    /**
     * Returns whether a string matches a pattern prefix ignoring ASCII case, starting from startFrom.
     */
    fun regionMatchesIgnoreCase(
        text: String, offset: Int, prefix: String, startFrom: Int, prefixLen: Int
    ): Boolean {
        for (i in startFrom..<prefixLen) {
            val c: Char = text[offset + i]
            val p: Char = prefix[i]
            val la = toLowerCase(c)
            if (la != p && la != toLowerCase(p)) {
                return false
            }
        }
        return true
    }

    /** Returns whether a byte array matches a pattern prefix ignoring ASCII case.  */
    fun regionMatchesIgnoreCase(bytes: ByteArray, offset: Int, prefix: String, prefixLen: Int): Boolean {
        for (i in 0..<prefixLen) {
            val b = bytes[offset + i].toInt() and 0xFF
            val p: Char = prefix[i]
            val lb = toLowerCase(b)
            if (lb != p.code && lb != toLowerCase(p).code) {
                return false
            }
        }
        return true
    }

    /** Returns whether a byte array matches an exact ASCII pattern prefix.  */
    fun regionMatches(bytes: ByteArray, offset: Int, prefix: String, prefixLen: Int): Boolean {
        for (i in 0..<prefixLen) {
            if ((bytes[offset + i].toInt() and 0xFF) != prefix[i].code) {
                return false
            }
        }
        return true
    }
}

/** Bounded index search helpers replacing Java String.indexOf overloads. */
internal fun String.indexOfBounded(literal: String, fromIndex: Int, endIndex: Int): Int {
    val from = maxOf(0, fromIndex)
    val end = minOf(length, endIndex)
    if (literal.isEmpty()) {
        return if (from <= end) from else -1
    }
    val lastStart = end - literal.length
    var i = from
    while (i <= lastStart) {
        if (startsWith(literal, i)) {
            return i
        }
        i++
    }
    return -1
}

internal fun String.indexOfCodePointBounded(codePoint: Int, fromIndex: Int, endIndex: Int): Int {
    val from = maxOf(0, fromIndex)
    val end = minOf(length, endIndex)
    var i = from
    while (i < end) {
        val cp = CodePoints.codePointAt(this, i)
        if (cp == codePoint) {
            return i
        }
        i += CodePoints.charCount(cp)
    }
    return -1
}

internal fun String.indexOfCodePoint(codePoint: Int, fromIndex: Int = 0): Int {
    return indexOfCodePointBounded(codePoint, fromIndex, length)
}
