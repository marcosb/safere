// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Portable ASCII case-insensitive literal tables for
 * [Utf8StartAccelerator.CaseInsensitiveLiteral]. Returns null when [prefix] is not pure ASCII.
 */
internal class Utf8CaseInsensitiveLiteralPlan(
  val prefix: String,
  val failure: IntArray,
  val anchorOffset: Int,
  val anchorLow: Byte,
  val anchorHigh: Byte,
  val anchorOffset2: Int,
  val anchorLow2: Byte,
  val anchorHigh2: Byte,
) {
  companion object {
    fun from(prefix: String): Utf8CaseInsensitiveLiteralPlan? {
      for (i in 0..<prefix.length) {
        if (prefix[i] > 127.toChar()) {
          return null
        }
      }
      val len = prefix.length
      if (len >= 2) {
        val pair = RarityOracle.rarestAsciiPairIgnoreCase(prefix, len)
        if (pair != null) {
          return Utf8CaseInsensitiveLiteralPlan(
            prefix,
            Ascii.ignoreCaseFailure(prefix),
            pair.offset1,
            pair.low1,
            pair.high1,
            pair.offset2,
            pair.low2,
            pair.high2,
          )
        }
      }
      val anchorOffset = RarityOracle.rarestAsciiOffset(prefix, len, true)
      val anchor = prefix[anchorOffset]
      val low = Ascii.toLowerCase(anchor).code.toByte()
      val high = Ascii.toUpperCase(anchor).code.toByte()
      return Utf8CaseInsensitiveLiteralPlan(
        prefix,
        Ascii.ignoreCaseFailure(prefix),
        anchorOffset,
        low,
        high,
        -1,
        0.toByte(),
        0.toByte(),
      )
    }
  }
}
