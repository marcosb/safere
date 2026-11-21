// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.math.max

/** Fast-path data for `(?i)\b(keyword|...)\b` and its greedy whole-input form.  */
internal class KeywordAlternation(
  keywords: Array<String>,
  firstAscii: AsciiBitmap,
  captureGroup: Int,
  unicodeWordBoundary: Boolean,
  greedyWholeInput: Boolean,
) {
  val keywords: Array<String>
  val firstAscii: AsciiBitmap?
  val firstAsciiTable: BooleanArray
  val captureGroup: Int
  val unicodeWordBoundary: Boolean
  val greedyWholeInput: Boolean

  init {
    this.keywords = keywords
    this.firstAscii = firstAscii
    this.firstAsciiTable = firstAscii.toBooleanArray()
    this.captureGroup = captureGroup
    this.unicodeWordBoundary = unicodeWordBoundary
    this.greedyWholeInput = greedyWholeInput
  }

  fun find(scanner: InputScanner, startPos: Int): Long {
    val matchStart = max(0, startPos)
    if (greedyWholeInput) {
      for (position in scanner.length - 1 downTo matchStart) {
        val match = matchAt(scanner, position)
        if (match >= 0) {
          return match
        }
      }
      return -1
    }
    var position = matchStart
    while (position < scanner.length) {
      val match = matchAt(scanner, position)
      if (match >= 0) {
        return match
      }
      val ascii = scanner.asciiAt(position)
      position =
        if (ascii >= 0) position + 1 else InputScanner.position(scanner.decodeForward(position))
    }
    return -1
  }

  fun matchAt(scanner: InputScanner, position: Int): Long {
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.record()
    }
    val first = scanner.asciiAt(position)
    if (first < 0 || !firstAsciiTable[Ascii.toLowerCase(first)] || !isWordBoundaryAt(scanner, position)) {
      return -1
    }
    for (keyword in keywords) {
      val end: Int = position + keyword.length
      if (end <= scanner.length &&
        matchesAsciiIgnoreCase(scanner, position, keyword) &&
        isWordBoundaryAt(scanner, end)
      ) {
        return (position.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)
      }
    }
    return -1
  }

  private fun isWordBoundaryAt(scanner: InputScanner, position: Int): Boolean {
    val previousWord =
      position > 0 && isBoundaryWordChar(scanner.codePointBefore(position), unicodeWordBoundary)
    val nextWord =
      position < scanner.length &&
        isBoundaryWordChar(scanner.codePointAt(position), unicodeWordBoundary)
    return previousWord != nextWord
  }

  companion object {
    private fun matchesAsciiIgnoreCase(
      scanner: InputScanner,
      position: Int,
      keyword: String,
    ): Boolean {
      for (index in 0..<keyword.length) {
        val input = scanner.asciiAt(position + index)
        if (input < 0 || Ascii.toLowerCase(input) != keyword[index].code) {
          return false
        }
      }
      return true
    }

    private fun isBoundaryWordChar(codePoint: Int, unicodeWordBoundary: Boolean): Boolean {
      return if (unicodeWordBoundary) {
        WordCharacters.isUnicodeWordChar(codePoint)
      } else {
        WordCharacters.isWordChar(codePoint)
      }
    }

    fun matchStart(match: Long): Int {
      return (match ushr 32).toInt()
    }

    fun matchEnd(match: Long): Int {
      return match.toInt()
    }
  }
}
