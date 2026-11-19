// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/** UTF-16 code unit traversal and search utilities.  */
internal object Utf16 {
    /** Returns the UTF-16 code unit at `index` in little-endian byte representation.  */
    fun getChar(bytes: ByteArray, offset: Int, index: Int): Char {
        val byteIndex = offset + (index shl 1)
        return ((bytes[byteIndex].toInt() and 0xFF) or ((bytes[byteIndex + 1].toInt() and 0xFF) shl 8)).toChar()
    }

    /** Finds an ASCII prefix in UTF-16 code units in linear time.  */
    fun indexOfIgnoreCase(input: CharArray, offset: Int, length: Int, pattern: String, start: Int): Int {
        if (pattern.isEmpty()) {
            return Math.min(Math.max(0, start), length)
        }
        val failure = Ascii.ignoreCaseFailure(pattern)
        var matched = 0
        for (i in Math.max(0, start) until length) {
            val ch = input[offset + i]
            while (matched > 0 && !Ascii.equalsIgnoreCase(ch, pattern[matched])) {
                matched = failure[matched - 1]
            }
            if (Ascii.equalsIgnoreCase(ch, pattern[matched])) {
                matched++
                if (matched == pattern.length) {
                    return i - pattern.length + 1
                }
            }
        }
        return -1
    }

    /** Finds an ASCII prefix in little-endian UTF-16 bytes in linear time.  */
    fun indexOfIgnoreCaseUtf16(
        input: ByteArray, offset: Int, length: Int, pattern: String, start: Int
    ): Int {
        if (pattern.isEmpty()) {
            return Math.min(Math.max(0, start), length)
        }
        val failure = Ascii.ignoreCaseFailure(pattern)
        var matched = 0
        for (i in Math.max(0, start) until length) {
            val ch = getChar(input, offset, i)
            while (matched > 0 && !Ascii.equalsIgnoreCase(ch, pattern[matched])) {
                matched = failure[matched - 1]
            }
            if (Ascii.equalsIgnoreCase(ch, pattern[matched])) {
                matched++
                if (matched == pattern.length) {
                    return i - pattern.length + 1
                }
            }
        }
        return -1
    }

    /** Finds a Unicode simple-folded prefix in a String in linear time.  */
    fun indexOfUnicodeIgnoreCase(input: CharSequence, pattern: String, start: Int): Int {
        if (pattern.isEmpty()) {
            return Math.min(Math.max(0, start), input.length)
        }
        val patternCodePoints = pattern.codePoints().toArray()
        val failure = unicodeFailure(patternCodePoints)
        var matched = 0
        var i = Math.max(0, start)
        while (i < input.length) {
            val codePoint = Character.codePointAt(input, i)
            val next = i + Character.charCount(codePoint)
            while (matched > 0 && !unicodeEqualsIgnoreCase(codePoint, patternCodePoints[matched])) {
                matched = failure[matched - 1]
            }
            if (unicodeEqualsIgnoreCase(codePoint, patternCodePoints[matched])) {
                matched++
                if (matched == patternCodePoints.size) {
                    return next - pattern.length
                }
            }
            i = next
        }
        return -1
    }

    fun regionMatchesUnicodeIgnoreCase(input: CharSequence, offset: Int, pattern: String): Boolean {
        var inputIndex = offset
        var patternIndex = 0
        while (patternIndex < pattern.length) {
            if (inputIndex >= input.length) {
                return false
            }
            val inputCodePoint = Character.codePointAt(input, inputIndex)
            val patternCodePoint = pattern.codePointAt(patternIndex)
            if (!unicodeEqualsIgnoreCase(inputCodePoint, patternCodePoint)) {
                return false
            }
            inputIndex += Character.charCount(inputCodePoint)
            patternIndex += Character.charCount(patternCodePoint)
        }
        return inputIndex == offset + pattern.length
    }

    private fun unicodeFailure(pattern: IntArray): IntArray {
        val failure = IntArray(pattern.size)
        var matched = 0
        for (i in 1 until pattern.size) {
            while (matched > 0 && !unicodeEqualsIgnoreCase(pattern[i], pattern[matched])) {
                matched = failure[matched - 1]
            }
            if (unicodeEqualsIgnoreCase(pattern[i], pattern[matched])) {
                matched++
            }
            failure[i] = matched
        }
        return failure
    }

    private fun unicodeEqualsIgnoreCase(left: Int, right: Int): Boolean {
        if (left == right) {
            return true
        }
        var folded: Int = Inst.Companion.simpleFold(left)
        while (folded != left) {
            if (folded == right) {
                return true
            }
            folded = Inst.Companion.simpleFold(folded)
        }
        return false
    }

    /** Returns whether a character sequence matches a pattern prefix ignoring ASCII case.  */
    fun regionMatchesIgnoreCase(chars: CharArray, offset: Int, prefix: String, prefixLen: Int): Boolean {
        for (i in 0 until prefixLen) {
            val c = chars[offset + i]
            val p = prefix[i]
            if (c != p && Ascii.toLowerCase(c) != p) {
                return false
            }
        }
        return true
    }

    /** Returns whether little-endian UTF-16 bytes match a pattern prefix ignoring ASCII case.  */
    fun regionMatchesIgnoreCaseUtf16(
        bytes: ByteArray, byteOffset: Int, prefix: String, prefixLen: Int
    ): Boolean {
        for (i in 0 until prefixLen) {
            val c =
                ((bytes[byteOffset + (i shl 1)].toInt() and 0xFF)
                        or ((bytes[byteOffset + (i shl 1) + 1].toInt() and 0xFF) shl 8)).toChar()
            val p = prefix[i]
            if (c != p && Ascii.toLowerCase(c) != p) {
                return false
            }
        }
        return true
    }
}
