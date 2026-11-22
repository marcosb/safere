// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.safere.MultiAnchorDescriptor.StartPlan

@DisabledForCrosscheck("tests compile-time MultiLiteralInfo hoist on StartPlan.MultiLiteral")
internal class MultiLiteralInfoPlanHoistTest {
  @Test
  fun compilerAttachesMultiLiteralInfoForEligibleAlternation() {
    val plan = Pattern.compile("apple|banana|cherry").startPlan()
    Assertions.assertThat(plan).isInstanceOf(StartPlan.MultiLiteral::class.java)
    val multi = plan as StartPlan.MultiLiteral
    Assertions.assertThat(multi.multiLiteralInfo).isNotNull()
    val expected = MultiLiteralInfo.create(multi.literals)!!
    Assertions.assertThat(multi.multiLiteralInfo!!.literals).isEqualTo(expected.literals)
    Assertions.assertThat(multi.multiLiteralInfo!!.anchorChars).isEqualTo(expected.anchorChars)
    Assertions.assertThat(multi.multiLiteralInfo!!.anchorOffsets).isEqualTo(expected.anchorOffsets)
    Assertions.assertThat(multi.multiLiteralInfo!!.minLength).isEqualTo(expected.minLength)
  }

  @Test
  fun compilerOmitsMultiLiteralInfoWhenTooManyLiterals() {
    val plan = Pattern.compile("apple|banana|cherry|date|fig").startPlan()
    Assertions.assertThat(plan).isInstanceOf(StartPlan.MultiLiteral::class.java)
    val multi = plan as StartPlan.MultiLiteral
    Assertions.assertThat(multi.multiLiteralInfo).isNull()
    Assertions.assertThat(MultiLiteralInfo.create(multi.literals)).isNull()
  }
}
