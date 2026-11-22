// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Portable UTF-8 suffix tables for [RejectPrefilter.EndAnchoredSuffix].
 */
internal class RejectEndAnchoredSuffixPlan(
  val suffix: String,
  val suffixUtf8: ByteArray,
  val wasDollar: Boolean,
  val unixLines: Boolean,
  val foldCase: Boolean,
) {
  companion object {
    fun from(info: SuffixInfo?): RejectEndAnchoredSuffixPlan? {
      if (info == null || info.suffix == null || info.suffix.isEmpty()) {
        return null
      }
      return RejectEndAnchoredSuffixPlan(
        info.suffix,
        info.suffix.encodeToByteArray(),
        info.wasDollar,
        info.unixLines,
        info.foldCase,
      )
    }
  }
}
