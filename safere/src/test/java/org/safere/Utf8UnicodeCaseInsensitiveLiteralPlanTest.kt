// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

@DisabledForCrosscheck("tests internal UTF-8 Unicode case-insensitive start-plan tables")
internal class Utf8UnicodeCaseInsensitiveLiteralPlanTest {
  @Test
  fun buildsFoldTablesForNonAsciiPrefix() {
    val plan = Utf8UnicodeCaseInsensitiveLiteralPlan.from("café")
    Assertions.assertThat(plan).isNotNull()
    Assertions.assertThat(plan!!.prefixFoldVariants).hasSize(4)
    Assertions.assertThat(plan.anchorVariants).isNotEmpty()
    Assertions.assertThat(plan.anchorByteOffset).isGreaterThanOrEqualTo(0)
  }

  @Test
  fun rejectsSurrogateCodePoint() {
    Assertions.assertThat(Utf8UnicodeCaseInsensitiveLiteralPlan.from("\uD800")).isNull()
  }

  @Test
  fun roundTripsThroughUtf8StartAccelerator() {
    val plan = Utf8UnicodeCaseInsensitiveLiteralPlan.from("café")!!
    val literal = Utf8StartAccelerator.UnicodeCaseInsensitiveLiteral.from(plan)
    val bytes = "CAFÉ".encodeToByteArray()
    val scanner = Utf8InputScanner(bytes, 0, bytes.size)
    Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(literal, scanner, 0)).isEqualTo(0)
  }
}
