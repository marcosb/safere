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

  fun isValidCodePoint(cp: Int): Boolean = cp in MIN_CODE_POINT..MAX_CODE_POINT

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

  /** Returns the code points of [s] in order (UTF-16 surrogates as unpaired scalars). */
  fun toCodePointArray(s: CharSequence): IntArray {
    val count = codePointCount(s, 0, s.length)
    val codePoints = IntArray(count)
    var i = 0
    var index = 0
    while (i < s.length) {
      val cp = codePointAt(s, i)
      codePoints[index++] = cp
      i += charCount(cp)
    }
    return codePoints
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

  fun toChars(cp: Int): CharArray {
    require(isValidCodePoint(cp)) { "Not a valid Unicode code point: 0x${cp.toString(16)}" }
    return if (cp < MIN_SUPPLEMENTARY_CODE_POINT) charArrayOf(cp.toChar())
    else charArrayOf(highSurrogate(cp), lowSurrogate(cp))
  }

  // Int overloads for call sites that hold a rune; Char call sites use the stdlib directly
  // (`ch.isHighSurrogate()` etc.).
  fun isHighSurrogate(cp: Int): Boolean = cp in 0..0xFFFF && cp.toChar().isHighSurrogate()

  fun isLowSurrogate(cp: Int): Boolean = cp in 0..0xFFFF && cp.toChar().isLowSurrogate()

  fun isSurrogate(cp: Int): Boolean = cp in 0..0xFFFF && cp.toChar().isSurrogate()
}
