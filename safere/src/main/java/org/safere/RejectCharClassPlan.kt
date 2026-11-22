// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.safere.CharClassScanInfo.AsciiSmallSet

/**
 * Portable scan-table payload for a required reject character class
 * ([RejectPrefilter.CharClass]).
 */
internal class RejectCharClassPlan(
  val ranges: IntArray?,
  val bitmap0: Long,
  val bitmap1: Long,
  val singleAscii: Int,
  val smallChars: CharArray?,
  val nonAsciiUtf8: ByteArray?,
) {
  companion object {
    fun from(scanInfo: CharClassScanInfo): RejectCharClassPlan {
      val small = smallChars(scanInfo)
      return RejectCharClassPlan(
        scanInfo.ranges,
        scanInfo.bitmap0,
        scanInfo.bitmap1,
        singleAscii(scanInfo),
        small,
        nonAsciiUtf8OfMixedPair(small),
      )
    }

    /**
     * Returns the UTF-8 encoding of the non-ASCII member of a small set with one ASCII and one
     * non-ASCII member, such as `[\]\uFF3D]`, or `null` for every other class. Members
     * are sorted, so the ASCII one comes first. Small-set members are never surrogates.
     */
    fun nonAsciiUtf8OfMixedPair(small: CharArray?): ByteArray? {
      return if (small != null && small.size == 2 && small[0].code < 0x80 && small[1].code >= 0x80)
        small[1].toString().encodeToByteArray()
      else
        null
    }

    /**
     * Returns the sole member of a one-character ASCII class, or `-1` for every other class.
     *
     *
     * A one-character reject class is a character search, not a class scan.
     * [CharClassScanInfo.AsciiSmallSet] already records its enumerated members; this reads
     * that back so the distinction survives construction.
     */
    fun singleAscii(scanInfo: CharClassScanInfo?): Int {
      return if (scanInfo is AsciiSmallSet &&
        scanInfo.chars != null &&
        scanInfo.chars.size == 1
      ) {
        scanInfo.chars[0].code
      } else {
        -1
      }
    }

    /**
     * Returns the members of a two-member small class, or `null`. A single ASCII member is
     * already one intrinsic search through [singleAscii]; a three-member set would cost three
     * intrinsic passes over a gap.
     */
    fun smallChars(scanInfo: CharClassScanInfo?): CharArray? {
      return if (scanInfo is CharClassScanInfo.SmallSet && scanInfo.chars.size == 2)
        scanInfo.chars
      else
        null
    }
  }
}
