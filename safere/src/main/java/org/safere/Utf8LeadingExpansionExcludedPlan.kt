// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.safere.MultiAnchorDescriptor.StartPlan

/**
 * Portable excluded-set tables for [Utf8StartAccelerator.LeadingExpansion.ExcludedSet]: ASCII
 * code points and UTF-8 encodings of non-surrogate non-ASCII exclusions.
 */
internal class Utf8LeadingExpansionExcludedPlan(
  val ascii: IntArray,
  val nonAscii: Array<ByteArray>,
) {
  companion object {
    /**
     * Returns the excluded set when a forward search for it can stand in for the backward walk:
     * the repetition must be unbounded with at most one required repetition. Otherwise null.
     */
    fun from(le: StartPlan.LeadingExpansion): Utf8LeadingExpansionExcludedPlan? {
      val excluded = MultiAnchorCompiler.excludedCodePoints(le.leadingClass!!)
      if (excluded == null || le.minRepetition > 1 || le.maxRepetition != Int.MAX_VALUE) {
        return null
      }
      val ascii = ArrayList<Int>()
      val nonAscii = ArrayList<ByteArray>()
      for (cp in excluded) {
        when {
          cp < 0x80 -> ascii.add(cp)
          cp >= 0x80 && (cp > 0xFFFF || !CodePoints.isSurrogate(cp)) ->
            nonAscii.add(Utils.runeToString(cp).encodeToByteArray())
        }
      }
      return Utf8LeadingExpansionExcludedPlan(
        ascii.toIntArray(),
        nonAscii.toTypedArray(),
      )
    }
  }
}
