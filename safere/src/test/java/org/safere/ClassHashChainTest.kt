// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

@DisabledForCrosscheck("package-private ClassHashChain tests exercise SafeRE internals")
internal class ClassHashChainTest {
    @Test
    fun classHashChainReturnsNullForShort() {
        Assertions.assertThat<ClassHashChain?>(ClassHashChain.compileCaseInsensitive(null)).isNull()
        Assertions.assertThat<ClassHashChain?>(ClassHashChain.compileCaseInsensitive("")).isNull()
        Assertions.assertThat<ClassHashChain?>(ClassHashChain.compileCaseInsensitive("abc")).isNull()
        Assertions.assertThat<ClassHashChain?>(ClassHashChain.compileCaseInsensitive("абв")).isNull()
    }

    @Test
    fun classHashChainCaseInsensitiveAsciiMatches() {
        val pattern = "content-length"
        val chc = ClassHashChain.compileCaseInsensitive(pattern)
        Assertions.assertThat<ClassHashChain?>(chc).isNotNull()

        val variants = mutableListOf<String>(
            "content-length",
            "CONTENT-LENGTH",
            "Content-Length",
            "CoNtEnT-LeNgTh",
            "cOnTeNt-lEnGtH"
        )

        for (variant in variants) {
            for (prefixLen in 0..32) {
                for (suffixLen in 0..32) {
                    val text = "x".repeat(prefixLen) + variant + "y".repeat(suffixLen)
                    val found = chc!!.search(text, 0, WorkLimit.forRemaining(text.length))
                    Assertions.assertThat(found).`as`("Variant %s in %s", variant, text).isEqualTo(prefixLen)
                }
            }
        }
    }

    @Test
    fun classHashChainCaseInsensitiveAsciiNonMatching() {
        val chc = ClassHashChain.compileCaseInsensitive("content-length")
        Assertions.assertThat<ClassHashChain?>(chc).isNotNull()

        val text = "The quick brown fox jumps over the lazy dog."
        val found = chc!!.search(text, 0, WorkLimit.forRemaining(text.length))
        Assertions.assertThat(found).isEqualTo(-1)
    }

    @Test
    fun classHashChainMatchesCyrillicAcrossCasing() {
        val pattern = "привет_мир"
        val chc = ClassHashChain.compileCaseInsensitive(pattern)
        Assertions.assertThat<ClassHashChain?>(chc).isNotNull()

        val variants = mutableListOf<String>("привет_мир", "ПРИВЕТ_МИР", "Привет_Мир", "пРиВеТ_мИр")
        for (variant in variants) {
            for (prefixLen in 0..16) {
                for (suffixLen in 0..16) {
                    val text = "х".repeat(prefixLen) + variant + "у".repeat(suffixLen)
                    val found = chc!!.search(text, 0, WorkLimit.forRemaining(text.length))
                    Assertions.assertThat(found).`as`("Variant %s in %s", variant, text).isEqualTo(prefixLen)
                }
            }
        }
    }

    @Test
    fun classHashChainMatchesGermanAndGreekAcrossCasing() {
        val germanPattern = "über_spitzen"
        val germanChc = ClassHashChain.compileCaseInsensitive(germanPattern)
        Assertions.assertThat<ClassHashChain?>(germanChc).isNotNull()

        val germanVariants = mutableListOf<String>("über_spitzen", "ÜBER_SPITZEN", "Über_Spitzen")
        for (variant in germanVariants) {
            val text = "Vor dem Text " + variant + " nach dem Text"
            val found = germanChc!!.search(text, 0, WorkLimit.forRemaining(text.length))
            Assertions.assertThat(found).isEqualTo(13)
        }

        val greekPattern = "αβγδεζηθ"
        val greekChc = ClassHashChain.compileCaseInsensitive(greekPattern)
        Assertions.assertThat<ClassHashChain?>(greekChc).isNotNull()
        Assertions.assertThat(greekChc!!.search("Τεστ ΑΒΓΔΕΖΗΘ τέλος", 0, 1000)).isEqualTo(5)
    }

    @Test
    fun classHashChainSurrogatePairs() {
        val pattern = "👋🏼👋🏼👋🏼👋🏼"
        val chc = ClassHashChain.compileCaseInsensitive(pattern)
        Assertions.assertThat<ClassHashChain?>(chc).isNotNull()

        val text = "Prefix 👋🏼👋🏼👋🏼👋🏼 Suffix"
        val found = chc!!.search(text, 0, WorkLimit.forRemaining(text.length))
        Assertions.assertThat(found).isEqualTo(7)
    }

    @Test
    fun classHashChainMatchesSupplementaryCaseFolds() {
        val upper = String(Character.toChars(0x10400)).repeat(4)
        val lower = String(Character.toChars(0x10428)).repeat(4)
        val chain = ClassHashChain.compileCaseInsensitive(upper)
        Assertions.assertThat<ClassHashChain?>(chain).isNotNull()
        Assertions.assertThat(chain!!.search(lower, 0, 100)).isZero()

        val matcher = Pattern.compile("(?iu)" + upper).matcher(lower)
        Assertions.assertThat(matcher.find()).isTrue()
    }

    @Test
    fun classHashChainAdversarialCollisionsTripWorkLimit() {
        val chc = ClassHashChain.compileCaseInsensitive("cbabab")
        Assertions.assertThat<ClassHashChain?>(chc).isNotNull()

        val haystack = "aBaBaB".repeat(100)
        val result = chc!!.search(haystack, 0, WorkLimit.forRemaining(haystack.length))
        Assertions.assertThat(result).isEqualTo(-2)

        val cyrillicChc = ClassHashChain.compileCaseInsensitive("сбабаб")
        Assertions.assertThat<ClassHashChain?>(cyrillicChc).isNotNull()

        val cyrillicHaystack = "аБаБаБ".repeat(100)
        val cyrillicResult =
            cyrillicChc!!.search(cyrillicHaystack, 0, WorkLimit.forRemaining(cyrillicHaystack.length))
        Assertions.assertThat(cyrillicResult).isEqualTo(-2)
    }

    @Test
    fun shiftAtReturnsZeroForMatchingTerminal2Gram() {
        val chc = ClassHashChain.compileCaseInsensitive("keyword_to_find")
        Assertions.assertThat<ClassHashChain?>(chc).isNotNull()

        // "KEYWORD_TO_FIND" has terminal 2-gram "ND"
        Assertions.assertThat(chc!!.shiftAt("KEYWORD_TO_FIND", 0)).isEqualTo(0)
        Assertions.assertThat(chc.shiftAt("prefix_keyword_to_find", 7)).isEqualTo(0)

        val cyrillicChc = ClassHashChain.compileCaseInsensitive("конфигурация")
        Assertions.assertThat<ClassHashChain?>(cyrillicChc).isNotNull()
        Assertions.assertThat(cyrillicChc!!.shiftAt("КОНФИГУРАЦИЯ", 0)).isEqualTo(0)
        Assertions.assertThat(cyrillicChc.shiftAt("префикс_конфигурация", 8)).isEqualTo(0)
    }

    @Test
    fun shiftAtReturnsPositiveShiftForMismatchedTerminal2Gram() {
        val chc = ClassHashChain.compileCaseInsensitive("keyword_to_find")
        Assertions.assertThat<ClassHashChain?>(chc).isNotNull()

        // Candidate window [0..14] ends with "xy" -> not in pattern -> shifts 14
        Assertions.assertThat(chc!!.shiftAt("k_other_words_xy_rest", 0)).isEqualTo(14)

        val cyrillicChc = ClassHashChain.compileCaseInsensitive("конфигурация")
        Assertions.assertThat<ClassHashChain?>(cyrillicChc).isNotNull()
        Assertions.assertThat(cyrillicChc!!.shiftAt("к_другие_слова_хх_конец", 0)).isGreaterThan(0)
    }

    @Test
    fun shiftAtOutOfBoundsReturnsZero() {
        val chc = ClassHashChain.compileCaseInsensitive("keyword_to_find")
        Assertions.assertThat<ClassHashChain?>(chc).isNotNull()
        Assertions.assertThat(chc!!.shiftAt("short", 0)).isEqualTo(0)
        Assertions.assertThat(chc.shiftAt("keyword_to_find", 50)).isEqualTo(0)
    }

    @Test
    fun stringCaseInsensitiveSearchFallsBackAfterWorkLimitExhaustion() {
        val literal = "c" + "ab".repeat(20)
        val prefix = "ab".repeat(10000)
        val matcher =
            Pattern.compile("(?i)c(?:ab){20}").matcher(prefix + literal.uppercase())

        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(prefix.length)
    }

    @Test
    fun repeatedFindAdvancesCorrectlyOnStringWithClassHashChain() {
        val pattern = Pattern.compile("(?i)apple_pie")
        val text = "apple_pie and APPLE_PIE and Apple_Pie!"
        val matcher = pattern.matcher(text)

        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(0)
        Assertions.assertThat(matcher.group()).isEqualTo("apple_pie")

        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(14)
        Assertions.assertThat(matcher.group()).isEqualTo("APPLE_PIE")

        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(28)
        Assertions.assertThat(matcher.group()).isEqualTo("Apple_Pie")

        Assertions.assertThat(matcher.find()).isFalse()
    }
}
