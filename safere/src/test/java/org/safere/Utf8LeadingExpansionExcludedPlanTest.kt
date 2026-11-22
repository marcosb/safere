// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.safere.MultiAnchorDescriptor.StartPlan
import org.safere.Utf8StartAccelerator.LeadingExpansion.ExcludedSet

@DisabledForCrosscheck("tests internal UTF-8 leading-expansion excluded-set plan tables")
internal class Utf8LeadingExpansionExcludedPlanTest {
  @Test
  fun buildsAsciiExcludedSetForNarrowComplement() {
    val le = Pattern.compile("[^;]*error").startPlan() as StartPlan.LeadingExpansion
    val plan = Utf8LeadingExpansionExcludedPlan.from(le)
    Assertions.assertThat(plan).isNotNull()
    Assertions.assertThat(plan!!.ascii).containsExactly(';'.code)
    Assertions.assertThat(plan.nonAscii).isEmpty()
  }

  @Test
  fun rejectsMinRepetitionAboveOne() {
    val base = Pattern.compile("[^;]*error").startPlan() as StartPlan.LeadingExpansion
    val le =
      StartPlan.LeadingExpansion(
        base.leadingClass,
        /* minRepetition= */ 2,
        Int.MAX_VALUE,
        base.hasLeadingAssertions,
        base.innerPlan,
      )
    Assertions.assertThat(Utf8LeadingExpansionExcludedPlan.from(le)).isNull()
  }

  @Test
  fun roundTripsThroughExcludedSet() {
    val le = Pattern.compile("[^;]*error").startPlan() as StartPlan.LeadingExpansion
    val plan = Utf8LeadingExpansionExcludedPlan.from(le)!!
    val excluded = ExcludedSet.from(plan)
    Assertions.assertThat(excluded.ascii).isEqualTo(plan.ascii)
    Assertions.assertThat(excluded.nonAscii).isEqualTo(plan.nonAscii)
  }
}
