// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.jvm.JvmRecord
import kotlin.math.*
/**
 * Encapsulates pre-computed character class metadata for scanning, start acceleration, and
 * rejection.
 */
internal sealed interface CharClassScanInfo {
  fun contains(cp: Int): Boolean

  val bitmap0: Long

  val bitmap1: Long

  val isAscii: Boolean

  val ranges: IntArray

  /**
   * Returns whether this character class is sufficiently selective to drive start acceleration.
   * Classes with more than 3 characters or single-character classes matching high-frequency
   * characters (e.g. `[ ]`) are not selective and delegate to DFA matching.
   */
  val isSelective: Boolean
    get() {
      val classRanges = ranges
      val asciiCount = java.lang.Long.bitCount(bitmap0) + java.lang.Long.bitCount(bitmap1)
      if (asciiCount > 3) {
        return false
      }
      if (classRanges.size == 2 && classRanges[0] == classRanges[1]) {
        val ch = classRanges[0]
        if (ch < 128 && RarityOracle.byteRarity(ch) <= RarityOracle.POISONOUS_ANCHOR_MAX_RARITY) {
          return false
        }
      }
      return true
    }

  /**
   * Common interface for small character classes whose members can be searched for one `char`
   * at a time: up to three ASCII characters, or up to two BMP characters otherwise.
   */
  sealed interface SmallSet {
    val chars: CharArray
  }

  /** Matches 1, 2, or 3 exact ASCII characters via single-instruction SIMD equality.  */
  // Arrays are immutable, privately owned scanner metadata; array identity is never observed.
  @JvmRecord
  data class AsciiSmallSet(
    override val chars: CharArray,
    override val ranges: IntArray,
    override val bitmap0: Long,
    override val bitmap1: Long,
  ) : CharClassScanInfo, SmallSet {
    override val isAscii: Boolean
      get() = true

    override fun contains(cp: Int): Boolean {
      if (cp < 64) {
        return cp >= 0 && (bitmap0 and (1L shl cp)) != 0L
      }
      if (cp < 128) {
        return (bitmap1 and (1L shl (cp - 64))) != 0L
      }
      return false
    }
  }

  /**
   * Matches 1 or 2 non-surrogate BMP characters, at least one of them non-ASCII. The String paths
   * search a small set one `String.indexOf` pass per member and do so for at most two
   * members, so a larger set would only be misclassified.
   */
  // Arrays are immutable, privately owned scanner metadata; array identity is never observed.
  @JvmRecord
  data class UnicodeSmallSet(
    override val chars: CharArray,
    override val ranges: IntArray,
    override val bitmap0: Long,
    override val bitmap1: Long,
  ) : CharClassScanInfo, SmallSet {
    override val isAscii: Boolean
      get() = false

    override fun contains(cp: Int): Boolean {
      if (cp < 64) {
        return cp >= 0 && (bitmap0 and (1L shl cp)) != 0L
      }
      if (cp < 128) {
        return (bitmap1 and (1L shl (cp - 64))) != 0L
      }
      return Matcher.binarySearchRanges(ranges, cp)
    }
  }

  /** Matches contiguous ASCII intervals (1-4 ranges, e.g. [a-zA-Z0-9_]) via SIMD range tests.  */
  // The array is immutable, privately owned scanner metadata; array identity is never observed.
  @JvmRecord
  data class AsciiRanges(
    override val ranges: IntArray,
    override val bitmap0: Long,
    override val bitmap1: Long,
  ) : CharClassScanInfo {
    override val isAscii: Boolean
      get() = true

    override fun contains(cp: Int): Boolean {
      if (cp < 64) {
        return cp >= 0 && (bitmap0 and (1L shl cp)) != 0L
      }
      if (cp < 128) {
        return (bitmap1 and (1L shl (cp - 64))) != 0L
      }
      return false
    }
  }

  /** Matches arbitrary ASCII character distributions via 128-bit bitmap lookup.  */
  // The array is immutable, privately owned scanner metadata; array identity is never observed.
  @JvmRecord
  data class AsciiBitmapClass(
    override val ranges: IntArray,
    override val bitmap0: Long,
    override val bitmap1: Long,
  ) : CharClassScanInfo {
    override val isAscii: Boolean
      get() = true

    override fun contains(cp: Int): Boolean {
      if (cp < 64) {
        return cp >= 0 && (bitmap0 and (1L shl cp)) != 0L
      }
      if (cp < 128) {
        return (bitmap1 and (1L shl (cp - 64))) != 0L
      }
      return false
    }
  }

  /**
   * Matches BMP/Unicode character classes with ASCII acceleration bitmap + binary search ranges.
   */
  // The array is immutable, privately owned scanner metadata; array identity is never observed.
  @JvmRecord
  data class UnicodeGeneral(
    override val ranges: IntArray,
    override val bitmap0: Long,
    override val bitmap1: Long,
  ) : CharClassScanInfo {
    override val isAscii: Boolean
      get() = false

    override fun contains(cp: Int): Boolean {
      if (cp < 64) {
        return cp >= 0 && (bitmap0 and (1L shl cp)) != 0L
      }
      if (cp < 128) {
        return (bitmap1 and (1L shl (cp - 64))) != 0L
      }
      return Matcher.binarySearchRanges(ranges, cp)
    }
  }

  companion object {
    fun fromAsciiBitmap(asciiClass: AsciiBitmap?): CharClassScanInfo? {
      if (asciiClass == null || asciiClass.isEmpty()) {
        return null
      }
      val b0 = asciiClass.bitmap0
      val b1 = asciiClass.bitmap1
      val count = java.lang.Long.bitCount(b0) + java.lang.Long.bitCount(b1)
      val ranges = buildRangesFromBitmaps(b0, b1)
      if (count > 0 && count <= 3) {
        val chars = CharArray(count)
        var idx = 0
        for (i in 0 until 64) {
          if ((b0 and (1L shl i)) != 0L) {
            chars[idx++] = i.toChar()
          }
        }
        for (i in 0 until 64) {
          if ((b1 and (1L shl i)) != 0L) {
            chars[idx++] = (i + 64).toChar()
          }
        }
        return AsciiSmallSet(chars, ranges, b0, b1)
      }

      if (ranges.size / 2 <= 4) {
        return AsciiRanges(ranges, b0, b1)
      }
      return AsciiBitmapClass(ranges, b0, b1)
    }

    fun fromCharClass(cc: CharClass?): CharClassScanInfo? {
      if (cc == null || cc.isEmpty()) {
        return null
      }
      val numRanges = cc.numRanges()
      val ranges = IntArray(numRanges * 2)
      var b0 = 0L
      var b1 = 0L
      for (i in 0 until numRanges) {
        val lo = cc.lo(i)
        val hi = cc.hi(i)
        ranges[i * 2] = lo
        ranges[i * 2 + 1] = hi
        val start = max(0, lo)
        val end = min(127, hi)
        for (cp in start..end) {
          if (cp < 64) {
            b0 = b0 or (1L shl cp)
          } else {
            b1 = b1 or (1L shl (cp - 64))
          }
        }
      }

      val isAscii = numRanges > 0 && cc.hi(numRanges - 1) <= 127
      if (isAscii) {
        val count = java.lang.Long.bitCount(b0) + java.lang.Long.bitCount(b1)
        if (count > 0 && count <= 3) {
          val chars = CharArray(count)
          var idx = 0
          for (i in 0 until 64) {
            if ((b0 and (1L shl i)) != 0L) {
              chars[idx++] = i.toChar()
            }
          }
          for (i in 0 until 64) {
            if ((b1 and (1L shl i)) != 0L) {
              chars[idx++] = (i + 64).toChar()
            }
          }
          return AsciiSmallSet(chars, ranges, b0, b1)
        }
        if (numRanges <= 4) {
          return AsciiRanges(ranges, b0, b1)
        }
        return AsciiBitmapClass(ranges, b0, b1)
      }

      val count = cc.numRunes()
      if (count > 0 && count <= 2 && cc.hi(numRanges - 1) <= 0xFFFF && !overlapsSurrogates(cc)) {
        val chars = CharArray(count)
        var idx = 0
        for (i in 0 until numRanges) {
          for (cp in cc.lo(i)..cc.hi(i)) {
            chars[idx++] = cp.toChar()
          }
        }
        return UnicodeSmallSet(chars, ranges, b0, b1)
      }

      return UnicodeGeneral(ranges, b0, b1)
    }

    /**
     * Whether any member of `cc` is a surrogate code point. A lone surrogate in a class matches
     * only an unpaired surrogate code unit, but a `char` search for it would also stop inside
     * every valid pair, so such classes are not treated as small sets.
     */
    private fun overlapsSurrogates(cc: CharClass): Boolean {
      for (i in 0 until cc.numRanges()) {
        if (cc.lo(i) <= Character.MAX_SURROGATE.code && cc.hi(i) >= Character.MIN_SURROGATE.code) {
          return true
        }
      }
      return false
    }

    fun buildRangesFromBitmaps(b0: Long, b1: Long): IntArray {
      val temp = IntArray(128 * 2)
      var rangeCount = 0
      var value = 0
      while (value < 128) {
        val contains =
          if (value < 64) (b0 and (1L shl value)) != 0L else (b1 and (1L shl (value - 64))) != 0L
        if (!contains) {
          value++
          continue
        }
        val low = value
        while (value + 1 < 128) {
          val next = value + 1
          val nextContains =
            if (next < 64) (b0 and (1L shl next)) != 0L else (b1 and (1L shl (next - 64))) != 0L
          if (!nextContains) {
            break
          }
          value++
        }
        val high = value
        temp[rangeCount * 2] = low
        temp[rangeCount * 2 + 1] = high
        rangeCount++
        value++
      }
      if (rangeCount == 0) {
        return IntArray(0)
      }
      return (temp).copyOf(rangeCount * 2)
    }
  }
}
