// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

@DisabledForCrosscheck("tests internal UTF-8 start-plan tables")
internal class Utf8LiteralStartPlanTest {
  @Test
  fun buildsUtf8TablesAndRareByteOffset() {
    val plan = Utf8LiteralStartPlan.from("needle")
    val expected = "needle".encodeToByteArray()
    Assertions.assertThat(plan.prefixUtf8).isEqualTo(expected)
    Assertions.assertThat(plan.prefixUtf8Failure).isEqualTo(ByteLiteralScan.literalFailure(expected))
    Assertions.assertThat(plan.prefixUtf8Shifts).isEqualTo(ByteLiteralScan.literalShifts(expected))
    Assertions.assertThat(plan.rareByteOffset)
      .isEqualTo(RarityOracle.rarestUtf8LiteralByteOffset(expected))
  }

  @Test
  fun roundTripsThroughUtf8StartAcceleratorLiteral() {
    val plan = Utf8LiteralStartPlan.from("abc")
    val literal = Utf8StartAccelerator.Literal.from(plan)
    Assertions.assertThat(literal.prefixUtf8).isEqualTo(plan.prefixUtf8)
    Assertions.assertThat(literal.prefixUtf8Failure).isEqualTo(plan.prefixUtf8Failure)
    Assertions.assertThat(literal.prefixUtf8Shifts).isEqualTo(plan.prefixUtf8Shifts)
    Assertions.assertThat(literal.rareByteOffset).isEqualTo(plan.rareByteOffset)
  }
}
