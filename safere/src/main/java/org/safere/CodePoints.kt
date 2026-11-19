// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere
internal object CodePoints {
  const val MIN_CODE_POINT: Int = 0
  const val MAX_CODE_POINT: Int = 0x10FFFF
  const val MIN_SUPPLEMENTARY_CODE_POINT: Int = 0x10000

  fun charCount(cp: Int): Int = if (cp >= MIN_SUPPLEMENTARY_CODE_POINT) 2 else 1

  fun isSupplementaryCodePoint(cp: Int): Boolean = cp >= MIN_SUPPLEMENTARY_CODE_POINT

  fun isValidCodePoint(cp: Int): Boolean = cp in MIN_CODE_POINT..MAX_CODE_POINT

  fun isBmpCodePoint(cp: Int): Boolean = cp in 0..0xFFFF

  fun codePointAt(seq: CharSequence, index: Int): Int {
    val c1 = seq[index]
    if (c1.isHighSurrogate() && index + 1 < seq.length) {
      val c2 = seq[index + 1]
      if (c2.isLowSurrogate()) {
        return toCodePoint(c1, c2)
      }
    }
    return c1.code
  }

  fun codePointBefore(seq: CharSequence, index: Int): Int {
    val i = index - 1
    val c2 = seq[i]
    if (c2.isLowSurrogate() && i > 0) {
      val c1 = seq[i - 1]
      if (c1.isHighSurrogate()) {
        return toCodePoint(c1, c2)
      }
    }
    return c2.code
  }

  fun codePointCount(seq: CharSequence, begin: Int, end: Int): Int {
    var i = begin
    var count = 0
    while (i < end) {
      val c = seq[i]
      i += if (c.isHighSurrogate() && i + 1 < end && seq[i + 1].isLowSurrogate()) 2 else 1
      count++
    }
    return count
  }

  fun offsetByCodePoints(seq: CharSequence, index: Int, codePointOffset: Int): Int {
    var i = index
    var offset = codePointOffset
    if (offset >= 0) {
      val end = seq.length
      while (offset > 0 && i < end) {
        val c = seq[i]
        i += if (c.isHighSurrogate() && i + 1 < end && seq[i + 1].isLowSurrogate()) 2 else 1
        offset--
      }
      check(offset == 0)
    } else {
      while (offset < 0 && i > 0) {
        val c = seq[i - 1]
        i -= if (c.isLowSurrogate() && i - 1 > 0 && seq[i - 2].isHighSurrogate()) 2 else 1
        offset++
      }
      check(offset == 0)
    }
    return i
  }

  fun toCodePoint(high: Char, low: Char): Int =
    ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000

  fun highSurrogate(cp: Int): Char =
    (((cp - 0x10000) ushr 10) + 0xD800).toChar()

  fun lowSurrogate(cp: Int): Char =
    (((cp - 0x10000) and 0x3FF) + 0xDC00).toChar()

  fun toChars(cp: Int): CharArray =
    if (cp < MIN_SUPPLEMENTARY_CODE_POINT) charArrayOf(cp.toChar())
    else charArrayOf(highSurrogate(cp), lowSurrogate(cp))

  fun digit(cp: Int, radix: Int): Int {
    if (radix !in 2..36) return -1
    val value =
      when {
        cp in '0'.code..'9'.code -> cp - '0'.code
        cp in 'a'.code..'z'.code -> cp - 'a'.code + 10
        cp in 'A'.code..'Z'.code -> cp - 'A'.code + 10
        else -> return -1
      }
    return if (value < radix) value else -1
  }

  fun forDigit(digit: Int, radix: Int): Char {
    if (digit < 0 || digit >= radix || radix !in 2..36) return 0.toChar()
    return if (digit < 10) ('0'.code + digit).toChar() else ('a'.code + digit - 10).toChar()
  }

  // BMP-oriented predicates (full Unicode general-category checks stay on JVM Character /
  // SafeRE Unicode tables where required). Suitable for lexer/ASCII-adjacent call sites.
  fun isDigit(cp: Int): Boolean = cp in 0..0xFFFF && cp.toChar().isDigit()

  fun isLetter(cp: Int): Boolean = cp in 0..0xFFFF && cp.toChar().isLetter()

  fun isLetterOrDigit(cp: Int): Boolean = cp in 0..0xFFFF && cp.toChar().isLetterOrDigit()

  fun isWhitespace(cp: Int): Boolean = cp in 0..0xFFFF && cp.toChar().isWhitespace()

  fun isSpaceChar(cp: Int): Boolean = isWhitespace(cp)

  fun isUpperCase(cp: Int): Boolean = cp in 0..0xFFFF && cp.toChar().isUpperCase()

  fun isLowerCase(cp: Int): Boolean = cp in 0..0xFFFF && cp.toChar().isLowerCase()

  fun isISOControl(cp: Int): Boolean = cp in 0..0xFFFF && cp.toChar().isISOControl()

  fun isHighSurrogate(cp: Int): Boolean = cp in 0..0xFFFF && cp.toChar().isHighSurrogate()

  fun isLowSurrogate(cp: Int): Boolean = cp in 0..0xFFFF && cp.toChar().isLowSurrogate()

  fun isSurrogate(cp: Int): Boolean = cp in 0..0xFFFF && cp.toChar().isSurrogate()

  fun toLowerCase(cp: Int): Int =
    if (cp in 0..0xFFFF) cp.toChar().lowercaseChar().code else cp

  fun toUpperCase(cp: Int): Int =
    if (cp in 0..0xFFFF) cp.toChar().uppercaseChar().code else cp

  fun isDigit(ch: Char): Boolean = ch.isDigit()

  fun isLetter(ch: Char): Boolean = ch.isLetter()

  fun isLetterOrDigit(ch: Char): Boolean = ch.isLetterOrDigit()

  fun isWhitespace(ch: Char): Boolean = ch.isWhitespace()

  fun isSpaceChar(ch: Char): Boolean = ch.isWhitespace()

  fun isUpperCase(ch: Char): Boolean = ch.isUpperCase()

  fun isLowerCase(ch: Char): Boolean = ch.isLowerCase()

  fun isISOControl(ch: Char): Boolean = ch.isISOControl()

  fun isHighSurrogate(ch: Char): Boolean = ch.isHighSurrogate()

  fun isLowSurrogate(ch: Char): Boolean = ch.isLowSurrogate()

  fun isSurrogate(ch: Char): Boolean = ch.isSurrogate()

  fun toLowerCase(ch: Char): Char = ch.lowercaseChar()

  fun toUpperCase(ch: Char): Char = ch.uppercaseChar()

  fun toLowerCase(ch: Char, localeIgnored: Boolean): Char = ch.lowercaseChar()

}

