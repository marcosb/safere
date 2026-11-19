// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/** Utility methods for Unicode code point handling and character classification.  */
internal object Utils {
    /** The maximum valid Unicode code point.  */
    const val MAX_RUNE: Int = 0x10FFFF

    /** The minimum valid Unicode code point.  */
    const val MIN_RUNE: Int = 0

    /** Unicode replacement character, used for invalid code points.  */
    const val REPLACEMENT_CHAR: Int = 0xFFFD

    /** The maximum value of a Basic Multilingual Plane (BMP) code point.  */
    const val MAX_BMP: Int = 0xFFFF

    /** Start of the surrogate range (not valid code points).  */
    const val MIN_SURROGATE: Int = 0xD800

    /** End of the surrogate range (not valid code points).  */
    const val MAX_SURROGATE: Int = 0xDFFF

    /** Returns true if `r` is a valid Unicode code point.  */
    fun isValidRune(r: Int): Boolean {
        return r >= MIN_RUNE && r <= MAX_RUNE && (r < MIN_SURROGATE || r > MAX_SURROGATE)
    }

    /** Returns true if the code point is an ASCII letter or digit.  */
    fun isAlnum(r: Int): Boolean {
        return Ascii.isAlnum(r)
    }

    /** Returns true if the code point is an ASCII letter.  */
    fun isAlpha(r: Int): Boolean {
        return Ascii.isAlpha(r)
    }

    /** Returns true if the code point is an ASCII digit.  */
    fun isDigit(r: Int): Boolean {
        return Ascii.isDigit(r)
    }

    /** Returns true if the code point is an ASCII hex digit.  */
    fun isHexDigit(r: Int): Boolean {
        return Ascii.isHexDigit(r)
    }

    /** Returns true if the code point is an ASCII word character (letter, digit, or underscore).  */
    fun isWordChar(r: Int): Boolean {
        return Ascii.isWordChar(r)
    }

    /** Returns true if the code point is an ASCII uppercase letter.  */
    fun isUpper(r: Int): Boolean {
        return Ascii.isUpper(r)
    }

    /** Returns true if the code point is an ASCII lowercase letter.  */
    fun isLower(r: Int): Boolean {
        return Ascii.isLower(r)
    }

    /**
     * Returns the value of a hex digit, or -1 if not a hex digit.
     * 
     * @param r a code point
     * @return 0-15 for valid hex digits, -1 otherwise
     */
    fun unhex(r: Int): Int {
        return Ascii.unhex(r)
    }

    /**
     * Converts a code point to a Java String, handling supplementary characters correctly.
     * 
     * @param r a Unicode code point
     * @return a String containing the character(s) for that code point
     */
    fun runeToString(r: Int): String {
        return String(Character.toChars(r))
    }

    /**
     * Returns the number of Unicode code points in the string. Unlike [String.length], this
     * correctly counts supplementary (non-BMP) characters as one.
     */
    fun runeCount(s: String): Int {
        return s.codePointCount(0, s.length)
    }

    /**
     * Returns the code point at a given code-point index (not char index) in a string.
     * 
     * @param s the string
     * @param index the code point index
     * @return the code point at that index
     * @throws IndexOutOfBoundsException if index is out of bounds
     */
    fun runeAt(s: String, index: Int): Int {
        val charIndex = s.offsetByCodePoints(0, index)
        return s.codePointAt(charIndex)
    }
}
