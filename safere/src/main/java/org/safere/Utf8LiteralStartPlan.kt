// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Portable UTF-8 literal tables for [Utf8StartAccelerator.Literal]: encoded prefix, KMP/Horspool
 * metadata, and the rare-byte scan offset.
 */
internal class Utf8LiteralStartPlan(
  val prefixUtf8: ByteArray,
  val prefixUtf8Failure: IntArray,
  val prefixUtf8Shifts: IntArray?,
  val rareByteOffset: Int,
) {
  companion object {
    fun from(prefix: String): Utf8LiteralStartPlan {
      val utf8 = prefix.encodeToByteArray()
      return Utf8LiteralStartPlan(
        utf8,
        ByteLiteralScan.literalFailure(utf8),
        ByteLiteralScan.literalShifts(utf8),
        RarityOracle.rarestUtf8LiteralByteOffset(utf8),
      )
    }
  }
}
