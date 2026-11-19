// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class AsciiBitmapTest {
    @Test
    fun emptyBitmapContainsNothing() {
        val bitmap = AsciiBitmap.EMPTY
        Assertions.assertThat(bitmap.isEmpty()).isTrue()
        Assertions.assertThat(bitmap.cardinality()).isZero()
        for (cp in -1..256) {
            Assertions.assertThat(bitmap.contains(cp)).isFalse()
        }
    }

    @Test
    fun singleCodePointContainsOnlyThatCodePoint() {
        for (target in 0..127) {
            val bitmap = AsciiBitmap.of(target)
            Assertions.assertThat(bitmap.isEmpty()).isFalse()
            Assertions.assertThat(bitmap.cardinality()).isEqualTo(1)
            Assertions.assertThat(bitmap.contains(target)).isTrue()
            Assertions.assertThat(bitmap.contains(target - 1)).isFalse()
            Assertions.assertThat(bitmap.contains(target + 1)).isFalse()
            Assertions.assertThat(bitmap.contains(-1)).isFalse()
            Assertions.assertThat(bitmap.contains(128)).isFalse()
        }
    }

    @Test
    fun builderAddRangeAndContains() {
        val digits = AsciiBitmap.Builder().addRange('0'.code, '9'.code).build()
        Assertions.assertThat(digits.cardinality()).isEqualTo(10)
        var c = '0'
        while (c <= '9') {
            Assertions.assertThat(digits.contains(c.code)).isTrue()
            c++
        }
        Assertions.assertThat(digits.contains('a'.code)).isFalse()
        Assertions.assertThat(digits.contains('/'.code)).isFalse()
        Assertions.assertThat(digits.contains(':'.code)).isFalse()
    }

    @Test
    fun unionCombinesSets() {
        val digits = AsciiBitmap.Builder().addRange('0'.code, '9'.code).build()
        val letters = AsciiBitmap.Builder().addRange('a'.code, 'z'.code).build()
        val alphaNum = digits.union(letters)

        Assertions.assertThat(alphaNum.cardinality()).isEqualTo(36)
        Assertions.assertThat(alphaNum.contains('5'.code)).isTrue()
        Assertions.assertThat(alphaNum.contains('x'.code)).isTrue()
        Assertions.assertThat(alphaNum.contains('A'.code)).isFalse()
        Assertions.assertThat(alphaNum.contains('@'.code)).isFalse()
    }

    @Test
    fun toBooleanArrayMatchesContains() {
        val bitmap =
            AsciiBitmap.Builder().add('a'.code).add('z'.code).addRange('0'.code, '9'.code).add('E'.code).build()
        val array = bitmap.toBooleanArray()

        Assertions.assertThat(array).hasSize(128)
        for (cp in 0..127) {
            Assertions.assertThat(array[cp]).isEqualTo(bitmap.contains(cp))
        }
    }
}
