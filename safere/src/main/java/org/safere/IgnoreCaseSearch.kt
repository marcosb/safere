// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.math.max
import kotlin.math.min

/** ASCII / Unicode case-insensitive literal search helpers.  */
internal object IgnoreCaseSearch {
  /** ASCII case-insensitive indexOf for Java's default CASE_INSENSITIVE semantics.  */
  fun indexOfIgnoreCase(text: String, prefix: String, fromIndex: Int): Int {
    val prefixLen: Int = prefix.length
    if (prefixLen == 0) {
      return min(max(0, fromIndex), text.length)
    }
    if (prefixLen == 1) {
      return if (Ascii.isAscii(prefix))
        Ascii.indexOfIgnoreCase(text, prefix[0], fromIndex)
      else
        Utf16.indexOfUnicodeIgnoreCase(text, prefix, fromIndex)
    }
    val anchorOffset = RarityOracle.rarestAsciiOffset(prefix, prefixLen, true)
    val anchor: Char = prefix[anchorOffset]
    val low = Ascii.toLowerCase(anchor)
    val high = Ascii.toUpperCase(anchor)
    return indexOfIgnoreCase(text, prefix, anchorOffset, low, high, fromIndex)
  }

  fun indexOfIgnoreCase(
    text: String, prefix: String, anchorOffset: Int, low: Char, high: Char, fromIndex: Int
  ): Int {
    val classHashChain: ClassHashChain? =
      if (prefix.length >= 4) ClassHashChain.compileCaseInsensitive(prefix) else null
    return indexOfIgnoreCase(text, prefix, anchorOffset, low, high, classHashChain, fromIndex)
  }

  fun indexOfIgnoreCase(
    text: String,
    prefix: String,
    anchorOffset: Int,
    low: Char,
    high: Char,
    classHashChain: ClassHashChain?,
    fromIndex: Int,
  ): Int {
    val prefixLen: Int = prefix.length
    when (prefixLen) {
      0 -> {
        return min(max(0, fromIndex), text.length)
      }
      1 -> {
        return if (Ascii.isAscii(prefix))
          Ascii.indexOfIgnoreCase(text, low, high, fromIndex)
        else
          Utf16.indexOfUnicodeIgnoreCase(text, prefix, fromIndex)
      }
      else -> {}
    }
    val length: Int = text.length
    var pos = max(0, fromIndex)
    val isAsciiPrefix = Ascii.isAscii(prefix)
    if (!isAsciiPrefix) {
      if (classHashChain != null) {
        val limit = WorkLimit.forRemaining(length - pos)
        val result = classHashChain.search(text, pos, limit)
        return if (result == -2) Utf16.indexOfUnicodeIgnoreCase(text, prefix, pos) else result
      }
      return Utf16.indexOfUnicodeIgnoreCase(text, prefix, pos)
    }
    var verificationWork: Long = 0
    var workLimit: Long = -1
    var hasLow = true
    var hasHigh = (low != high)
    var nextLow = -1
    var nextHigh = -1
    val startFrom = if (anchorOffset == 0) 1 else 0

    while (pos <= length - prefixLen) {
      // Short scalar search for nearby anchor (handles whitespace / short delimiters without
      // indexOf overhead)
      val scalarLimit = min(length - prefixLen + 1, pos + 32)
      while (pos < scalarLimit) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record()
        }
        val c: Char = text[pos + anchorOffset]
        if (if (hasHigh) (c.code or 0x20) == low.code else c == low) {
          if (classHashChain != null) {
            val shift = classHashChain.shiftAt(text, pos)
            if (shift == 0) {
              if (Ascii.regionMatchesIgnoreCase(text, pos, prefix, startFrom, prefixLen)) {
                return pos
              }
            } else {
              pos += shift - 1
              pos++
              continue
            }
          } else if (Ascii.regionMatchesIgnoreCase(text, pos, prefix, startFrom, prefixLen)) {
            return pos
          }
          verificationWork += prefixLen.toLong()
          if (workLimit < 0) {
            workLimit = WorkLimit.forRemaining(length - pos)
          }
          if (WorkLimit.isExhausted(verificationWork, workLimit)) {
            if (classHashChain != null) {
              val result = classHashChain.search(text, pos + 1, workLimit)
              return if (result == -2) Ascii.indexOfLinearIgnoreCase(
                text,
                prefix,
                pos + 1,
              ) else result
            }
            return Ascii.indexOfLinearIgnoreCase(text, prefix, pos + 1)
          }
        }
        pos++
      }

      if (pos > length - prefixLen) {
        return -1
      }

      if (workLimit < 0) {
        workLimit = WorkLimit.forRemaining(length - pos)
      }

      val searchFrom = pos + anchorOffset
      if (nextLow >= 0 && nextLow < searchFrom) {
        nextLow = -1
      }
      if (nextHigh >= 0 && nextHigh < searchFrom) {
        nextHigh = -1
      }
      if (hasLow && nextLow < 0) {
        nextLow = text.indexOf(low, searchFrom)
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record((if (nextLow < 0) length - searchFrom else nextLow - searchFrom + 1).toLong())
        }
        if (nextLow < 0) {
          hasLow = false
        }
      }

      if (hasHigh && nextHigh < 0 && nextLow != searchFrom) {
        nextHigh = text.indexOf(high, searchFrom)
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record((if (nextHigh < 0) length - searchFrom else nextHigh - searchFrom + 1).toLong())
        }
        if (nextHigh < 0) {
          hasHigh = false
        }
      }

      val nextAnchor = Ascii.minNonNegative(nextLow, nextHigh)
      if (nextAnchor < 0) {
        return -1
      }
      if (nextLow == nextAnchor) {
        nextLow = -1
      }
      if (nextHigh == nextAnchor) {
        nextHigh = -1
      }
      val candidatePos = nextAnchor - anchorOffset
      if (WorkLimit.candidateInBounds(candidatePos, pos, length, prefixLen)) {
        if (classHashChain != null) {
          val shift = classHashChain.shiftAt(text, candidatePos)
          if (shift == 0) {
            if (Ascii.regionMatchesIgnoreCase(text, candidatePos, prefix, prefixLen)) {
              return candidatePos
            }
            pos = candidatePos + 1
          } else {
            pos = candidatePos + shift
            continue
          }
        } else {
          if (Ascii.regionMatchesIgnoreCase(text, candidatePos, prefix, prefixLen)) {
            return candidatePos
          }
          pos = candidatePos + 1
        }
        verificationWork += prefixLen.toLong()
        if (WorkLimit.isExhausted(verificationWork, workLimit)) {
          if (classHashChain != null) {
            val result = classHashChain.search(text, pos, workLimit)
            return if (result == -2) Ascii.indexOfLinearIgnoreCase(text, prefix, pos) else result
          }
          return Ascii.indexOfLinearIgnoreCase(text, prefix, pos)
        }
      } else {
        pos = candidatePos + 1
      }
    }
    return -1
  }
}
