// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

@DisabledForCrosscheck("tests internal literal-alternation compile shapes")
internal class LiteralAlternationPlanTest {
  @Test
  fun compilesExactAsciiAlternation() {
    val flags =
      ParseFlags.PERL_X or ParseFlags.PERL_CLASSES or ParseFlags.PERL_B or ParseFlags.UNICODE_GROUPS
    val plan = LiteralAlternationPlan.compile(Parser.parse("foo|bar|baz", flags)!!, true)
    Assertions.assertThat(plan).isNotNull()
    Assertions.assertThat(plan!!.literals).containsExactly("foo", "bar", "baz")
    Assertions.assertThat(plan.wholeInputRejection).isTrue()
  }

  @Test
  fun rejectsNonLiteralOrOversizedAlternation() {
    val flags =
      ParseFlags.PERL_X or ParseFlags.PERL_CLASSES or ParseFlags.PERL_B or ParseFlags.UNICODE_GROUPS
    Assertions.assertThat(LiteralAlternationPlan.compile(Parser.parse("foo|b.r", flags)!!, false))
      .isNull()
    Assertions.assertThat(LiteralAlternationPlan.compile(Parser.parse("foo", flags)!!, false)).isNull()
    Assertions.assertThat(
        LiteralAlternationPlan.compile(
          Parser.parse("abcdefghijklm|xyz", flags)!!,
          false,
        )
      )
      .isNull()
  }
}
