// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

@DisabledForCrosscheck("tests internal reject end-anchored suffix plan tables")
internal class RejectEndAnchoredSuffixPlanTest {
  @Test
  fun buildsUtf8SuffixTables() {
    val info = SuffixInfo("bar", true, false, true)
    val plan = RejectEndAnchoredSuffixPlan.from(info)
    Assertions.assertThat(plan).isNotNull()
    Assertions.assertThat(plan!!.suffix).isEqualTo("bar")
    Assertions.assertThat(plan.suffixUtf8).isEqualTo("bar".encodeToByteArray())
    Assertions.assertThat(plan.wasDollar).isTrue()
    Assertions.assertThat(plan.unixLines).isFalse()
    Assertions.assertThat(plan.foldCase).isTrue()
  }

  @Test
  fun rejectsNullOrEmptySuffix() {
    Assertions.assertThat(RejectEndAnchoredSuffixPlan.from(null)).isNull()
    Assertions.assertThat(RejectEndAnchoredSuffixPlan.from(SuffixInfo(null, true))).isNull()
    Assertions.assertThat(RejectEndAnchoredSuffixPlan.from(SuffixInfo("", true))).isNull()
  }

  @Test
  fun roundTripsThroughRejectPrefilter() {
    val info = SuffixInfo("xyz", false, true, false)
    val plan = RejectEndAnchoredSuffixPlan.from(info)!!
    val filter = RejectPrefilter.EndAnchoredSuffix.from(plan)
    Assertions.assertThat(filter.suffix).isEqualTo(plan.suffix)
    Assertions.assertThat(filter.suffixUtf8).isEqualTo(plan.suffixUtf8)
    Assertions.assertThat(filter.wasDollar).isEqualTo(plan.wasDollar)
    Assertions.assertThat(filter.unixLines).isEqualTo(plan.unixLines)
    Assertions.assertThat(filter.foldCase).isEqualTo(plan.foldCase)
  }
}
