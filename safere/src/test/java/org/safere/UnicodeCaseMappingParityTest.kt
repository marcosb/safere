// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the common-stdlib case mapping in [UnicodeCaseFolding] against the JDK's
 * single-code-point `Character` mappings, including supplementary code points that a
 * `Char`-only mapping would truncate.
 */
internal class UnicodeCaseMappingParityTest {
    @Test
    fun hasUnicodeCaseVariantMatchesJdkForEveryCodePoint() {
        val mismatches = ArrayList<String>()
        for (cp in 0..Utils.MAX_RUNE) {
            if (UnicodeCaseFolding.hasUnicodeCaseVariant(cp) != jdkHasCaseVariant(cp)) {
                mismatches.add("U+%04X".format(cp))
                if (mismatches.size >= 20) break
            }
        }
        assertThat(mismatches).isEmpty()
    }

    @Test
    fun supplementaryCodePointsDoNotAliasTheirLow16Bits() {
        // U+10041 / U+10061 truncate to 'A' / 'a' as a Char; they must not fold to ASCII.
        val flags = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        for (cp in intArrayOf(0x10041, 0x10061, 0x1004B, 0x10053)) {
            val text = String(Character.toChars(cp))
            assertThat(Pattern.compile("[A-Za-z]", flags).matcher(text).matches())
                .`as`("U+%04X", cp)
                .isFalse()
            assertThat(Pattern.compile(Character.toString(cp and 0xFFFF), flags).matcher(text).matches())
                .`as`("U+%04X", cp)
                .isFalse()
        }
    }

    @Test
    fun supplementaryCasePairsStillFold() {
        val flags = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        // Deseret, Osage, Adlam upper/lower pairs.
        val pairs = arrayOf(intArrayOf(0x10400, 0x10428), intArrayOf(0x104B0, 0x104D8), intArrayOf(0x1E900, 0x1E922))
        for ((upper, lower) in pairs) {
            val upperText = String(Character.toChars(upper))
            val lowerText = String(Character.toChars(lower))
            assertThat(Pattern.compile(upperText, flags).matcher(lowerText).matches()).isTrue()
            assertThat(Pattern.compile(lowerText, flags).matcher(upperText).matches()).isTrue()
            assertThat(Pattern.compile("[$upperText]", flags).matcher(lowerText).matches()).isTrue()
        }
    }

    private fun jdkHasCaseVariant(cp: Int): Boolean {
        val upper = Character.toUpperCase(cp)
        val lower = Character.toLowerCase(cp)
        return upper != cp || lower != cp || Character.toTitleCase(cp) != cp
                || Character.toLowerCase(upper) != cp || Character.toUpperCase(lower) != cp
    }
}
