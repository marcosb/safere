// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Tests for [Utils].  */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class UtilsTest {
    @Test
    fun isValidRune_validCodePoints() {
        Assertions.assertThat(Utils.isValidRune(0)).isTrue() // NUL
        Assertions.assertThat(Utils.isValidRune('A'.code)).isTrue()
        Assertions.assertThat(Utils.isValidRune(0x7F)).isTrue() // DEL
        Assertions.assertThat(Utils.isValidRune(0x80)).isTrue() // First non-ASCII
        Assertions.assertThat(Utils.isValidRune(0xFFFF)).isTrue() // Last BMP
        Assertions.assertThat(Utils.isValidRune(0x10000)).isTrue() // First supplementary
        Assertions.assertThat(Utils.isValidRune(0x10FFFF)).isTrue() // Max code point
    }

    @Test
    fun isValidRune_invalidCodePoints() {
        Assertions.assertThat(Utils.isValidRune(-1)).isFalse()
        Assertions.assertThat(Utils.isValidRune(0x110000)).isFalse() // Beyond max
        Assertions.assertThat(Utils.isValidRune(0xD800)).isFalse() // Surrogate start
        Assertions.assertThat(Utils.isValidRune(0xDBFF)).isFalse() // High surrogate end
        Assertions.assertThat(Utils.isValidRune(0xDC00)).isFalse() // Low surrogate start
        Assertions.assertThat(Utils.isValidRune(0xDFFF)).isFalse() // Surrogate end
    }

    @ParameterizedTest
    @ValueSource(ints = [0x30, 0x35, 0x39, 0x61, 0x7A, 0x41, 0x5A])
    fun isAlnum_trueForAlnumChars(r: Int) {
        Assertions.assertThat(Utils.isAlnum(r)).isTrue()
    }

    @ParameterizedTest
    @ValueSource(ints = [0x21, 0x20, 0x40, 0x5B, 0x7B, 0x00, 0xFF])
    fun isAlnum_falseForNonAlnumChars(r: Int) {
        Assertions.assertThat(Utils.isAlnum(r)).isFalse()
    }

    @Test
    fun isWordChar_includesUnderscore() {
        Assertions.assertThat(Utils.isWordChar('_'.code)).isTrue()
        Assertions.assertThat(Utils.isWordChar('a'.code)).isTrue()
        Assertions.assertThat(Utils.isWordChar('0'.code)).isTrue()
        Assertions.assertThat(Utils.isWordChar('-'.code)).isFalse()
        Assertions.assertThat(Utils.isWordChar(' '.code)).isFalse()
    }

    @Test
    fun unhex_validDigits() {
        Assertions.assertThat(Utils.unhex('0'.code)).isEqualTo(0)
        Assertions.assertThat(Utils.unhex('9'.code)).isEqualTo(9)
        Assertions.assertThat(Utils.unhex('A'.code)).isEqualTo(10)
        Assertions.assertThat(Utils.unhex('F'.code)).isEqualTo(15)
        Assertions.assertThat(Utils.unhex('a'.code)).isEqualTo(10)
        Assertions.assertThat(Utils.unhex('f'.code)).isEqualTo(15)
    }

    @Test
    fun unhex_invalidChars() {
        Assertions.assertThat(Utils.unhex('G'.code)).isEqualTo(-1)
        Assertions.assertThat(Utils.unhex('g'.code)).isEqualTo(-1)
        Assertions.assertThat(Utils.unhex(' '.code)).isEqualTo(-1)
        Assertions.assertThat(Utils.unhex('/'.code)).isEqualTo(-1)
        Assertions.assertThat(Utils.unhex(':'.code)).isEqualTo(-1)
    }

    @Test
    fun runeToString_ascii() {
        Assertions.assertThat(Utils.runeToString('A'.code)).isEqualTo("A")
        Assertions.assertThat(Utils.runeToString('0'.code)).isEqualTo("0")
    }

    @Test
    fun runeToString_supplementary() {
        // U+1F600 = 😀 (grinning face)
        Assertions.assertThat(Utils.runeToString(0x1F600)).isEqualTo("\uD83D\uDE00")
    }

    @Test
    fun runeCount_ascii() {
        Assertions.assertThat(Utils.runeCount("hello")).isEqualTo(5)
    }

    @Test
    fun runeCount_supplementary() {
        // "A😀B" = 3 code points but 4 chars
        Assertions.assertThat(Utils.runeCount("A\uD83D\uDE00B")).isEqualTo(3)
    }

    @Test
    fun runeAt_ascii() {
        Assertions.assertThat(Utils.runeAt("hello", 0)).isEqualTo('h'.code)
        Assertions.assertThat(Utils.runeAt("hello", 4)).isEqualTo('o'.code)
    }

    @Test
    fun runeAt_supplementary() {
        // "A😀B" — code point index 1 is the emoji
        val s = "A\uD83D\uDE00B"
        Assertions.assertThat(Utils.runeAt(s, 0)).isEqualTo('A'.code)
        Assertions.assertThat(Utils.runeAt(s, 1)).isEqualTo(0x1F600)
        Assertions.assertThat(Utils.runeAt(s, 2)).isEqualTo('B'.code)
    }
}
