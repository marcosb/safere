// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.safere.CharClassScanInfo.UnicodeSmallSet

@DisabledForCrosscheck("tests internal UTF-8 char-class start-plan tables")
internal class Utf8CharClassStartPlanTest {
  @Test
  fun singleNonAsciiMemberEncodesUtf8() {
    val scanInfo =
      CharClassScanInfo.fromCharClass(CharClassBuilder().addRune(0xFF3B).build())!!
    Assertions.assertThat(scanInfo).isInstanceOf(UnicodeSmallSet::class.java)
    val plan = Utf8CharClassStartPlan.from(scanInfo)
    Assertions.assertThat(plan.nonAsciiUtf8).isEqualTo("\uFF3B".encodeToByteArray())
  }

  @Test
  fun mixedAsciiNonAsciiPairEncodesNonAsciiMember() {
    val scanInfo =
      CharClassScanInfo.fromCharClass(
        CharClassBuilder().addRune('['.code).addRune(0xFF3B).build()
      )!!
    Assertions.assertThat(scanInfo).isInstanceOf(UnicodeSmallSet::class.java)
    val plan = Utf8CharClassStartPlan.from(scanInfo)
    Assertions.assertThat(plan.nonAsciiUtf8).isEqualTo("\uFF3B".encodeToByteArray())
  }

  @Test
  fun asciiOnlyClassHasNoNonAsciiUtf8() {
    val scanInfo =
      CharClassScanInfo.fromCharClass(CharClassBuilder().addRune('x'.code).build())!!
    val plan = Utf8CharClassStartPlan.from(scanInfo)
    Assertions.assertThat(plan.nonAsciiUtf8).isNull()
  }

  @Test
  fun roundTripsThroughUtf8StartAcceleratorCharClass() {
    val scanInfo =
      CharClassScanInfo.fromCharClass(CharClassBuilder().addRune(0xFF3B).build())!!
    val plan = Utf8CharClassStartPlan.from(scanInfo)
    val accelerator = Utf8StartAccelerator.CharClass.from(plan)
    Assertions.assertThat(accelerator.scanInfo).isSameAs(plan.scanInfo)
    Assertions.assertThat(accelerator.nonAsciiUtf8).isEqualTo(plan.nonAsciiUtf8)
  }
}
