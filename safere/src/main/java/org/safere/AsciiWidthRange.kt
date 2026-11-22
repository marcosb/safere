// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/** Exact or bounded ASCII input-unit width for multi-anchor analysis.  */
internal class AsciiWidthRange(
  val minWidth: Int,
  val maxWidth: Int, // Sorted and duplicate-free when present.
  val discreteWidths: IntArray?,
) {
  val isValid: Boolean
    get() = minWidth >= 0

  val isExact: Boolean
    get() = minWidth >= 0 && minWidth == maxWidth

  val isExactAscii: Boolean
    get() =
      // Non-discrete widths can count Unicode code points rather than ASCII input units.
      this.isExact && discreteWidths != null

  companion object {
    val INVALID: AsciiWidthRange = AsciiWidthRange(-1, -1, null)
    val ZERO: AsciiWidthRange = AsciiWidthRange(0, 0, intArrayOf(0))
    val ONE: AsciiWidthRange = AsciiWidthRange(1, 1, intArrayOf(1))
    val NON_DISCRETE_ONE: AsciiWidthRange = AsciiWidthRange(1, 1, null)

    fun exact(width: Int): AsciiWidthRange {
      return AsciiWidthRange(width, width, intArrayOf(width))
    }

    fun concatenate(widths: List<AsciiWidthRange>): AsciiWidthRange {
      var result = ZERO
      for (width in widths) {
        result = concatenate(result, width)
        if (!result.isValid) {
          return result
        }
      }
      return result
    }

    fun concatenate(left: AsciiWidthRange, right: AsciiWidthRange): AsciiWidthRange {
      if (!left.isValid || !right.isValid) {
        return INVALID
      }
      if (left === ZERO) {
        return right
      }
      if (right === ZERO) {
        return left
      }
      val minWidth = addWidth(left.minWidth, right.minWidth)
      val maxWidth = addWidth(left.maxWidth, right.maxWidth)
      if (minWidth < 0 || maxWidth < 0) {
        return INVALID
      }
      var discrete: IntArray? = null
      if (left.discreteWidths != null &&
        right.discreteWidths != null &&
        left.discreteWidths.size * right.discreteWidths.size <= 16
      ) {
        val combined = IntArray(left.discreteWidths.size * right.discreteWidths.size)
        var size = 0
        for (leftWidth in left.discreteWidths) {
          for (rightWidth in right.discreteWidths) {
            val width = addWidth(leftWidth, rightWidth)
            if (width < 0) {
              return INVALID
            }
            size = insertWidth(combined, size, width)
          }
        }
        discrete = if (size == combined.size) combined else combined.copyOf(size)
      }
      return AsciiWidthRange(minWidth, maxWidth, discrete)
    }

    // Width sets have at most sixteen entries. Keep them sorted without boxing or tree nodes;
    // a return value beyond capacity means the caller must discard the discrete approximation.
    fun insertWidth(widths: IntArray, size: Int, width: Int): Int {
      var index = binarySearch(widths, 0, size, width)
      if (index >= 0) {
        return size
      }
      if (size == widths.size) {
        return size + 1
      }
      index = -index - 1
      widths.copyInto(widths, index + 1, index, size)
      widths[index] = width
      return size + 1
    }

    fun addWidth(left: Int, right: Int): Int {
      return if (left > Int.MAX_VALUE - right) -1 else left + right
    }

    fun multiplyWidth(width: Int, count: Int): Int {
      return if (width != 0 && count > Int.MAX_VALUE / width) -1 else width * count
    }

    private fun binarySearch(a: IntArray, fromIndex: Int, toIndex: Int, key: Int): Int {
      var lo = fromIndex
      var hi = toIndex - 1
      while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val midVal = a[mid]
        when {
          midVal < key -> lo = mid + 1
          midVal > key -> hi = mid - 1
          else -> return mid
        }
      }
      return -(lo + 1)
    }
  }
}
