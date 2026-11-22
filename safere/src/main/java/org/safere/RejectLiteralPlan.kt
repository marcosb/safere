// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Portable UTF-8 / String scan tables for a required reject literal
 * ([RejectPrefilter.Literal]).
 */
internal class RejectLiteralPlan(
  val literal: String,
  val utf8: ByteArray,
  val failure: IntArray,
  val shifts: IntArray?,
  val anchorOffset: Int,
  val anchor: Char,
) {
  companion object {
    fun from(literal: String): RejectLiteralPlan {
      val utf8 = literal.encodeToByteArray()
      val anchorOffset = StringLiteralSearch.anchorOffset(literal)
      return RejectLiteralPlan(
        literal,
        utf8,
        ByteLiteralScan.literalFailure(utf8),
        ByteLiteralScan.literalShifts(utf8),
        anchorOffset,
        StringLiteralSearch.anchorAt(literal, anchorOffset),
      )
    }
  }
}
