// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test

internal class ExhaustiveUtilsTest {
    @Test
    fun utf8CoordinatesMapOnlyUnicodeScalarBoundaries() {
        val coordinates: ExhaustiveUtils.Utf8Coordinates = ExhaustiveUtils.Utf8Coordinates.Companion.forText("Aé😀Z")

        Assertions.assertThat(coordinates.toUtf16(0)).isEqualTo(0)
        Assertions.assertThat(coordinates.toUtf16(1)).isEqualTo(1)
        Assertions.assertThat(coordinates.toUtf16(3)).isEqualTo(2)
        Assertions.assertThat(coordinates.toUtf16(7)).isEqualTo(4)
        Assertions.assertThat(coordinates.toUtf16(8)).isEqualTo(5)
        Assertions.assertThatThrownBy(ThrowingCallable { coordinates.toUtf16(2) })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not a Unicode scalar boundary")
        Assertions.assertThatThrownBy(ThrowingCallable { coordinates.toUtf16(5) })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not a Unicode scalar boundary")
    }

    @Test
    fun utf8CoordinatesRejectUnpairedSurrogates() {
        Assertions.assertThatThrownBy(ThrowingCallable { ExhaustiveUtils.Utf8Coordinates.Companion.forText("a\ud800b") })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unpaired surrogate")
        Assertions.assertThatThrownBy(ThrowingCallable { ExhaustiveUtils.Utf8Coordinates.Companion.forText("a\udc00b") })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unpaired surrogate")
    }
}
