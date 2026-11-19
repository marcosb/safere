// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class AsciiTest {
    @Test
    fun toLower_convertsUppercaseChar() {
        Assertions.assertThat(Ascii.toLowerCase('A')).isEqualTo('a')
        Assertions.assertThat(Ascii.toLowerCase('Z')).isEqualTo('z')
        Assertions.assertThat(Ascii.toLowerCase('M')).isEqualTo('m')
    }

    @Test
    fun toLower_leavesOthersUnchangedChar() {
        Assertions.assertThat(Ascii.toLowerCase('a')).isEqualTo('a')
        Assertions.assertThat(Ascii.toLowerCase('0')).isEqualTo('0')
        Assertions.assertThat(Ascii.toLowerCase('!')).isEqualTo('!')
        Assertions.assertThat(Ascii.toLowerCase('é')).isEqualTo('é')
    }

    @Test
    fun toUpper_convertsLowercaseChar() {
        Assertions.assertThat(Ascii.toUpperCase('a')).isEqualTo('A')
        Assertions.assertThat(Ascii.toUpperCase('z')).isEqualTo('Z')
        Assertions.assertThat(Ascii.toUpperCase('m')).isEqualTo('M')
    }

    @Test
    fun toUpper_leavesOthersUnchangedChar() {
        Assertions.assertThat(Ascii.toUpperCase('A')).isEqualTo('A')
        Assertions.assertThat(Ascii.toUpperCase('0')).isEqualTo('0')
        Assertions.assertThat(Ascii.toUpperCase('!')).isEqualTo('!')
        Assertions.assertThat(Ascii.toUpperCase('é')).isEqualTo('é')
    }

    @Test
    fun toLower_convertsUppercaseInt() {
        Assertions.assertThat(Ascii.toLowerCase('A'.code)).isEqualTo('a'.code)
        Assertions.assertThat(Ascii.toLowerCase('Z'.code)).isEqualTo('z'.code)
    }

    @Test
    fun toUpper_convertsLowercaseInt() {
        Assertions.assertThat(Ascii.toUpperCase('a'.code)).isEqualTo('A'.code)
        Assertions.assertThat(Ascii.toUpperCase('z'.code)).isEqualTo('Z'.code)
    }

    @Test
    fun equalsIgnoreCase() {
        Assertions.assertThat(Ascii.equalsIgnoreCase('a', 'A')).isTrue()
        Assertions.assertThat(Ascii.equalsIgnoreCase('A', 'a')).isTrue()
        Assertions.assertThat(Ascii.equalsIgnoreCase('x', 'x')).isTrue()
        Assertions.assertThat(Ascii.equalsIgnoreCase('a', 'b')).isFalse()
        Assertions.assertThat(Ascii.equalsIgnoreCase('!', '!')).isTrue()
    }

    @Test
    fun isUpperAndIsLower() {
        Assertions.assertThat(Ascii.isUpper('A'.code)).isTrue()
        Assertions.assertThat(Ascii.isUpper('Z'.code)).isTrue()
        Assertions.assertThat(Ascii.isUpper('a'.code)).isFalse()
        Assertions.assertThat(Ascii.isUpper('0'.code)).isFalse()

        Assertions.assertThat(Ascii.isLower('a'.code)).isTrue()
        Assertions.assertThat(Ascii.isLower('z'.code)).isTrue()
        Assertions.assertThat(Ascii.isLower('A'.code)).isFalse()
        Assertions.assertThat(Ascii.isLower('0'.code)).isFalse()
    }

    @Test
    fun isAlphaAndIsDigit() {
        Assertions.assertThat(Ascii.isAlpha('A'.code)).isTrue()
        Assertions.assertThat(Ascii.isAlpha('z'.code)).isTrue()
        Assertions.assertThat(Ascii.isAlpha('0'.code)).isFalse()
        Assertions.assertThat(Ascii.isAlpha('_'.code)).isFalse()

        Assertions.assertThat(Ascii.isDigit('0'.code)).isTrue()
        Assertions.assertThat(Ascii.isDigit('9'.code)).isTrue()
        Assertions.assertThat(Ascii.isDigit('a'.code)).isFalse()
    }

    @Test
    fun isAlnumAndIsWordChar() {
        Assertions.assertThat(Ascii.isAlnum('A'.code)).isTrue()
        Assertions.assertThat(Ascii.isAlnum('0'.code)).isTrue()
        Assertions.assertThat(Ascii.isAlnum('_'.code)).isFalse()

        Assertions.assertThat(Ascii.isWordChar('A'.code)).isTrue()
        Assertions.assertThat(Ascii.isWordChar('0'.code)).isTrue()
        Assertions.assertThat(Ascii.isWordChar('_'.code)).isTrue()
        Assertions.assertThat(Ascii.isWordChar('-'.code)).isFalse()
    }

    @Test
    fun hexMethods() {
        Assertions.assertThat(Ascii.isHexDigit('0'.code)).isTrue()
        Assertions.assertThat(Ascii.isHexDigit('9'.code)).isTrue()
        Assertions.assertThat(Ascii.isHexDigit('A'.code)).isTrue()
        Assertions.assertThat(Ascii.isHexDigit('F'.code)).isTrue()
        Assertions.assertThat(Ascii.isHexDigit('a'.code)).isTrue()
        Assertions.assertThat(Ascii.isHexDigit('f'.code)).isTrue()
        Assertions.assertThat(Ascii.isHexDigit('G'.code)).isFalse()

        Assertions.assertThat(Ascii.unhex('0'.code)).isEqualTo(0)
        Assertions.assertThat(Ascii.unhex('9'.code)).isEqualTo(9)
        Assertions.assertThat(Ascii.unhex('A'.code)).isEqualTo(10)
        Assertions.assertThat(Ascii.unhex('F'.code)).isEqualTo(15)
        Assertions.assertThat(Ascii.unhex('a'.code)).isEqualTo(10)
        Assertions.assertThat(Ascii.unhex('f'.code)).isEqualTo(15)
        Assertions.assertThat(Ascii.unhex('G'.code)).isEqualTo(-1)
    }

    @Test
    fun ignoreCaseFailure() {
        Assertions.assertThat(Ascii.ignoreCaseFailure("")).isEmpty()
        Assertions.assertThat(Ascii.ignoreCaseFailure("a")).containsExactly(0)
        Assertions.assertThat(Ascii.ignoreCaseFailure("aA")).containsExactly(0, 1)
        Assertions.assertThat(Ascii.ignoreCaseFailure("abAb")).containsExactly(0, 0, 1, 2)
    }

    @Test
    fun indexOfIgnoreCase() {
        Assertions.assertThat(Ascii.indexOfIgnoreCase("hello World", 'w', 0)).isEqualTo(6)
        Assertions.assertThat(Ascii.indexOfIgnoreCase("hello World", 'W', 0)).isEqualTo(6)
        Assertions.assertThat(Ascii.indexOfIgnoreCase("hello World", 'o', 0)).isEqualTo(4)
        Assertions.assertThat(Ascii.indexOfIgnoreCase("hello World", 'o', 5)).isEqualTo(7)
        Assertions.assertThat(Ascii.indexOfIgnoreCase("hello World", 'z', 0)).isEqualTo(-1)
        Assertions.assertThat(Ascii.indexOfIgnoreCase("hello 123", '1', 0)).isEqualTo(6)
        Assertions.assertThat(Ascii.indexOfIgnoreCase("hello é", 'é', 0)).isEqualTo(6)
    }

    @Test
    fun minNonNegative() {
        Assertions.assertThat(Ascii.minNonNegative(-1, -1)).isEqualTo(-1)
        Assertions.assertThat(Ascii.minNonNegative(-1, 5)).isEqualTo(5)
        Assertions.assertThat(Ascii.minNonNegative(3, -1)).isEqualTo(3)
        Assertions.assertThat(Ascii.minNonNegative(10, 4)).isEqualTo(4)
        Assertions.assertThat(Ascii.minNonNegative(2, 7)).isEqualTo(2)
    }
}
