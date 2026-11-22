// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

@DisabledForCrosscheck("tests internal reject-literal plan tables")
internal class RejectLiteralPlanTest {
  @Test
  fun buildsLiteralTables() {
    val plan = RejectLiteralPlan.from("</block>")
    val expected = "</block>".encodeToByteArray()
    Assertions.assertThat(plan.literal).isEqualTo("</block>")
    Assertions.assertThat(plan.utf8).isEqualTo(expected)
    Assertions.assertThat(plan.failure).isEqualTo(ByteLiteralScan.literalFailure(expected))
    Assertions.assertThat(plan.shifts).isEqualTo(ByteLiteralScan.literalShifts(expected))
    Assertions.assertThat(plan.anchorOffset).isEqualTo(StringLiteralSearch.anchorOffset("</block>"))
  }

  @Test
  fun roundTripsThroughRejectPrefilterLiteral() {
    val plan = RejectLiteralPlan.from("needle")
    val filter = RejectPrefilter.Literal.from(plan)
    Assertions.assertThat(filter.literal).isEqualTo(plan.literal)
    Assertions.assertThat(filter.utf8).isEqualTo(plan.utf8)
    Assertions.assertThat(filter.failure).isEqualTo(plan.failure)
    Assertions.assertThat(filter.shifts).isEqualTo(plan.shifts)
  }

  @Test
  fun disjointPlanOmitsUnpairedSurrogatesFromUtf8Tables() {
    val unpaired = "\uD800"
    val plan =
      RejectDisjointLiteralsPlan.from(arrayOf("ab", unpaired, "cd"))
    Assertions.assertThat(plan).isNotNull()
    Assertions.assertThat(plan!!.literals).containsExactly("ab", unpaired, "cd")
    Assertions.assertThat(plan.utf8Literals.size).isEqualTo(2)
    Assertions.assertThat(plan.utf8Literals[0]).isEqualTo("ab".encodeToByteArray())
    Assertions.assertThat(plan.utf8Literals[1]).isEqualTo("cd".encodeToByteArray())
    Assertions.assertThat(RejectDisjointLiteralsPlan.hasUnpairedSurrogate(unpaired)).isTrue()
    Assertions.assertThat(RejectDisjointLiteralsPlan.hasUnpairedSurrogate("ab")).isFalse()
  }
}
