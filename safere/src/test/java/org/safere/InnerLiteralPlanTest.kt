// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

@DisabledForCrosscheck("tests internal inner-literal compile shapes")
internal class InnerLiteralPlanTest {
  @Test
  fun compilesPrefixGapSuffixShape() {
    val ast =
      Parser.parse(
        "record:[^;]*val=200",
        (ParseFlags.PERL_X or ParseFlags.PERL_CLASSES or ParseFlags.PERL_B or ParseFlags.UNICODE_GROUPS),
      )
    val plan = InnerLiteralPlan.compile(ast)
    Assertions.assertThat(plan).isNotNull()
    Assertions.assertThat(plan!!.literal).isEqualTo("val=200")
    Assertions.assertThat(plan.prefixCodePoints).isEqualTo(7)
    Assertions.assertThat(plan.gap).isNotNull()
    Assertions.assertThat(plan.utf8).isEqualTo("val=200".encodeToByteArray())
  }

  @Test
  fun rejectsFoldCaseLiterals() {
    val ast =
      Parser.parse(
        "(?i:record:[^;]*val=200)",
        (ParseFlags.PERL_X or ParseFlags.PERL_CLASSES or ParseFlags.PERL_B or ParseFlags.UNICODE_GROUPS),
      )
    Assertions.assertThat(InnerLiteralPlan.compile(ast)).isNull()
  }

  @Test
  fun rejectsMissingGapOrSuffix() {
    val flags =
      ParseFlags.PERL_X or ParseFlags.PERL_CLASSES or ParseFlags.PERL_B or ParseFlags.UNICODE_GROUPS
    Assertions.assertThat(InnerLiteralPlan.compile(Parser.parse("record:val=200", flags))).isNull()
    Assertions.assertThat(InnerLiteralPlan.compile(Parser.parse("record:[^;]*", flags))).isNull()
  }
}
