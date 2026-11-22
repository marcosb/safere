// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

@DisabledForCrosscheck("tests internal reject char-class plan tables")
internal class RejectCharClassPlanTest {
  @Test
  fun singleAsciiClassUsesIntrinsicMember() {
    val scanInfo =
      CharClassScanInfo.fromCharClass(CharClassBuilder().addRune('x'.code).build())!!
    val plan = RejectCharClassPlan.from(scanInfo)
    Assertions.assertThat(plan.singleAscii).isEqualTo('x'.code)
    Assertions.assertThat(plan.smallChars).isNull()
    Assertions.assertThat(plan.nonAsciiUtf8).isNull()
  }

  @Test
  fun twoMemberMixedPairCapturesNonAsciiUtf8() {
    val mixed =
      CharClassScanInfo.fromCharClass(
        CharClassBuilder().addRune(']'.code).addRune('\uFF3D'.code).build()
      )!!
    val plan = RejectCharClassPlan.from(mixed)
    Assertions.assertThat(plan.smallChars).isNotNull()
    Assertions.assertThat(plan.smallChars!!.size).isEqualTo(2)
    Assertions.assertThat(plan.nonAsciiUtf8).isEqualTo("\uFF3D".encodeToByteArray())
    Assertions.assertThat(plan.singleAscii).isEqualTo(-1)
  }

  @Test
  fun roundTripsThroughRejectPrefilterCharClass() {
    val scanInfo =
      CharClassScanInfo.fromCharClass(
        CharClassBuilder().addRange('a'.code, 'z'.code).build()
      )!!
    val plan = RejectCharClassPlan.from(scanInfo)
    val filter = RejectPrefilter.CharClass.from(plan)
    Assertions.assertThat(filter.ranges).isEqualTo(plan.ranges)
    Assertions.assertThat(filter.bitmap0).isEqualTo(plan.bitmap0)
    Assertions.assertThat(filter.bitmap1).isEqualTo(plan.bitmap1)
    Assertions.assertThat(filter.singleAscii).isEqualTo(plan.singleAscii)
    Assertions.assertThat(filter.smallChars).isEqualTo(plan.smallChars)
    Assertions.assertThat(filter.nonAsciiUtf8).isEqualTo(plan.nonAsciiUtf8)
  }
}
