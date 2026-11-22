// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Shared width heuristics for leading character-class expansions used by multi-anchor plans.
 */
internal object LeadingClassWidth {
  // The most code points a leading class may exclude and still drive a leading expansion when it
  // has more than MAX_LEADING_CLASS_RUNES members. Non-DOTALL `.` excludes five line terminators.
  const val MAX_LEADING_CLASS_COMPLEMENT: Int = 8

  private const val MAX_LEADING_CLASS_RUNES = 150000

  fun runeCount(ranges: IntArray): Int {
    var runes = 0
    var i = 0
    while (i < ranges.size) {
      runes += ranges[i + 1] - ranges[i] + 1
      i += 2
    }
    return runes
  }

  fun isWideLeadingClass(numRunes: Long): Boolean {
    return numRunes > MAX_LEADING_CLASS_RUNES
  }

  fun isWideLeadingClass(leadingClass: CharClassScanInfo): Boolean {
    return isWideLeadingClass(runeCount(leadingClass.ranges).toLong())
  }
}
