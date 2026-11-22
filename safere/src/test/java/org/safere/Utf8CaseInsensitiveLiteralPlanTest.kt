// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

@DisabledForCrosscheck("tests internal UTF-8 case-insensitive start-plan tables")
internal class Utf8CaseInsensitiveLiteralPlanTest {
  @Test
  fun buildsAsciiIgnoreCaseTables() {
    val plan = Utf8CaseInsensitiveLiteralPlan.from("Needle")
    Assertions.assertThat(plan).isNotNull()
    Assertions.assertThat(plan!!.prefix).isEqualTo("Needle")
    Assertions.assertThat(plan.failure).isEqualTo(Ascii.ignoreCaseFailure("Needle"))
    Assertions.assertThat(plan.anchorOffset).isGreaterThanOrEqualTo(0)
  }

  @Test
  fun rejectsNonAsciiPrefix() {
    Assertions.assertThat(Utf8CaseInsensitiveLiteralPlan.from("café")).isNull()
  }

  @Test
  fun roundTripsThroughUtf8StartAccelerator() {
    val plan = Utf8CaseInsensitiveLiteralPlan.from("abc")!!
    val literal = Utf8StartAccelerator.CaseInsensitiveLiteral.from(plan)
    Assertions.assertThat(literal.prefix).isEqualTo(plan.prefix)
    Assertions.assertThat(literal.failure).isEqualTo(plan.failure)
    Assertions.assertThat(literal.anchorOffset).isEqualTo(plan.anchorOffset)
    Assertions.assertThat(literal.anchorLow).isEqualTo(plan.anchorLow)
    Assertions.assertThat(literal.anchorHigh).isEqualTo(plan.anchorHigh)
    Assertions.assertThat(literal.anchorOffset2).isEqualTo(plan.anchorOffset2)
  }
}
