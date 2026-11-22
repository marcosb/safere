// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.math.max
import kotlin.math.min

internal class AsciiWidthRangeWalker : Walker<AsciiWidthRange>() {
  override fun postVisit(
    re: Regexp,
    parentArg: AsciiWidthRange?,
    preArg: AsciiWidthRange?,
    childArgs: MutableList<AsciiWidthRange>,
  ): AsciiWidthRange {
    return when (re.op) {
      RegexpOp.CAPTURE, RegexpOp.NON_CAPTURE ->
        if (childArgs.isEmpty()) AsciiWidthRange.INVALID else childArgs.first()
      RegexpOp.EMPTY_MATCH,
      RegexpOp.BEGIN_LINE,
      RegexpOp.END_LINE,
      RegexpOp.BEGIN_TEXT,
      RegexpOp.END_TEXT,
      RegexpOp.WORD_BOUNDARY,
      RegexpOp.NO_WORD_BOUNDARY -> AsciiWidthRange.ZERO
      RegexpOp.LITERAL ->
        if (re.rune >= 0 && re.rune < 128 && (re.flags and ParseFlags.FOLD_CASE) == 0)
          AsciiWidthRange.ONE
        else
          AsciiWidthRange.INVALID
      RegexpOp.LITERAL_STRING -> literalStringWidth(re)
      RegexpOp.CHAR_CLASS -> characterClassWidth(re)
      RegexpOp.REPEAT ->
        if (childArgs.isEmpty()) AsciiWidthRange.INVALID else repeatWidth(re, childArgs.first())
      RegexpOp.QUEST ->
        if (childArgs.isEmpty()) AsciiWidthRange.INVALID else optionalWidth(childArgs.first())
      RegexpOp.ALTERNATE -> alternateWidth(childArgs)
      RegexpOp.CONCAT -> AsciiWidthRange.concatenate(childArgs)
      else -> AsciiWidthRange.INVALID
    }
  }

  override fun shortVisit(re: Regexp, parentArg: AsciiWidthRange?): AsciiWidthRange {
    return AsciiWidthRange.INVALID
  }

  companion object {
    internal fun literalStringWidth(node: Regexp): AsciiWidthRange {
      if ((node.flags and ParseFlags.FOLD_CASE) != 0 || node.runes == null) {
        return AsciiWidthRange.INVALID
      }
      for (rune in node.runes) {
        if (rune < 0 || rune >= 128) {
          return AsciiWidthRange.INVALID
        }
      }
      return AsciiWidthRange.exact(node.runes.size)
    }

    internal fun characterClassWidth(node: Regexp): AsciiWidthRange {
      if (node.charClass == null || node.charClass!!.isEmpty()) {
        return AsciiWidthRange.INVALID
      }
      return if (node.charClass!!.hi(node.charClass!!.numRanges() - 1) < 128)
        AsciiWidthRange.ONE
      else
        AsciiWidthRange.NON_DISCRETE_ONE
    }

    internal fun repeatWidth(node: Regexp, child: AsciiWidthRange): AsciiWidthRange {
      if (node.min < 0 || node.max < 0) {
        return AsciiWidthRange.INVALID
      }
      if (!child.isValid) {
        return AsciiWidthRange.INVALID
      }
      val minWidth = AsciiWidthRange.multiplyWidth(child.minWidth, node.min)
      val maxWidth = AsciiWidthRange.multiplyWidth(child.maxWidth, node.max)
      if (minWidth < 0 || maxWidth < 0) {
        return AsciiWidthRange.INVALID
      }
      if (child.discreteWidths != null && child.isExact && node.max - node.min <= 8) {
        if (child.minWidth == 0) {
          return AsciiWidthRange.ZERO
        }
        val discrete = IntArray(node.max - node.min + 1)
        for (index in discrete.indices) {
          val width = AsciiWidthRange.multiplyWidth(child.minWidth, node.min + index)
          if (width < 0) {
            return AsciiWidthRange.INVALID
          }
          discrete[index] = width
        }
        return AsciiWidthRange(minWidth, maxWidth, discrete)
      }
      return AsciiWidthRange(minWidth, maxWidth, null)
    }

    internal fun optionalWidth(child: AsciiWidthRange): AsciiWidthRange {
      if (!child.isValid) {
        return AsciiWidthRange.INVALID
      }
      if (child.discreteWidths == null) {
        return AsciiWidthRange(0, child.maxWidth, null)
      }
      val discrete = IntArray(child.discreteWidths.size + 1)
      var size = 1
      for (width in child.discreteWidths) {
        size = AsciiWidthRange.insertWidth(discrete, size, width)
      }
      return AsciiWidthRange(
        0, child.maxWidth, if (size <= 16) discrete.copyOf(size) else null
      )
    }

    internal fun alternateWidth(childArgs: MutableList<AsciiWidthRange>): AsciiWidthRange {
      if (childArgs.isEmpty()) {
        return AsciiWidthRange.INVALID
      }
      var minWidth = Int.MAX_VALUE
      var maxWidth = Int.MIN_VALUE
      val discrete = IntArray(8)
      var size = 0
      var allDiscrete = true
      for (child in childArgs) {
        if (!child.isValid) {
          return AsciiWidthRange.INVALID
        }
        minWidth = min(minWidth, child.minWidth)
        maxWidth = max(maxWidth, child.maxWidth)
        if (allDiscrete && child.discreteWidths != null) {
          for (width in child.discreteWidths) {
            size = AsciiWidthRange.insertWidth(discrete, size, width)
            if (size > discrete.size) {
              allDiscrete = false
              break
            }
          }
        } else {
          allDiscrete = false
        }
      }
      return AsciiWidthRange(
        minWidth, maxWidth, if (allDiscrete) discrete.copyOf(size) else null
      )
    }
  }
}
