// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Checks that SafeRE and the JDK agree on UAX #29 GB11 once the JDK implements it.
 * 
 * 
 * JDK 27 and earlier mishandle interrupted and Prepend-prefixed emoji ZWJ chains (see issue #936
 * and `INTENTIONAL_DIVERGENCES.md`). JDK-8393247 fixes that in JDK 28. [ ] checks SafeRE against the rule itself on every JDK.
 */
@EnabledForJreRange(minVersion = 28)
internal class EmojiZwjGraphemeJdkCompatibilityTest {
    @ParameterizedTest(name = "{1}")
    @MethodSource("gb11Cases")
    fun graphemeClustersMatchJdk(expected: MutableList<String>, description: String?) {
        val input = java.lang.String.join("", expected)
        Assertions.assertThat<String?>(clusters(Pattern.compile("\\X").matcher(input))).isEqualTo(expected)
        Assertions.assertThat<String?>(jdkClusters(java.util.regex.Pattern.compile("\\X").matcher(input)))
            .`as`("JDK \\X clusters")
            .isEqualTo(expected)
        Assertions.assertThat<String?>(org.safere.Pattern.compile("\\b{g}").split(input))
            .containsExactlyElementsOf(expected)
            .containsExactly(*java.util.regex.Pattern.compile("\\b{g}").split(input))
    }

    companion object {
        private const val THUMBS_UP = "\uD83D\uDC4D"
        private const val WOMAN = "\uD83D\uDC69"
        private const val MAN = "\uD83D\uDC68"
        private const val MEDIUM_DARK_SKIN_TONE = "\uD83C\uDFFE"
        private const val ZWJ = "\u200D"
        private const val ACUTE = "\u0301" // Extend
        private const val DEVANAGARI_VISARGA = "\u0903" // SpacingMark
        private const val ARABIC_NUMBER_SIGN = "\u0600" // Prepend

        @JvmStatic
        fun gb11Cases(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of(
                    java.util.List.of<String?>(THUMBS_UP + DEVANAGARI_VISARGA + ZWJ, THUMBS_UP), "SpacingMark interrupts"
                ),
                Arguments.of(java.util.List.of<String?>(THUMBS_UP + ZWJ + ZWJ, THUMBS_UP), "second ZWJ interrupts"),
                Arguments.of(
                    java.util.List.of<String?>(THUMBS_UP + ZWJ + ACUTE, THUMBS_UP),
                    "Extend after ZWJ interrupts"
                ),
                Arguments.of(java.util.List.of<String?>(THUMBS_UP + ACUTE + ZWJ + THUMBS_UP), "Extend before ZWJ joins"),
                Arguments.of(
                    java.util.List.of<String?>(MAN + ZWJ + MEDIUM_DARK_SKIN_TONE + ZWJ, WOMAN),
                    "modifier after ZWJ is not a pictograph"
                ),
                Arguments.of(
                    java.util.List.of<String?>(ARABIC_NUMBER_SIGN + WOMAN + ZWJ + WOMAN), "Prepend before pictograph joins"
                ),
                Arguments.of(
                    java.util.List.of<String?>(ARABIC_NUMBER_SIGN + WOMAN + ACUTE + ZWJ + WOMAN + ZWJ + WOMAN),
                    "Prepend before a longer chain joins"
                ),
                Arguments.of(
                    java.util.List.of<String?>(WOMAN, ARABIC_NUMBER_SIGN + ZWJ, WOMAN),
                    "Prepend inside the prefix interrupts"
                )
            )
        }

        private fun clusters(matcher: org.safere.Matcher): MutableList<String> {
            val clusters: MutableList<String> = ArrayList<String>()
            while (matcher.find()) {
                clusters.add(matcher.group()!!)
            }
            return clusters
        }

        private fun jdkClusters(matcher: java.util.regex.Matcher): MutableList<String> {
            val clusters: MutableList<String> = ArrayList<String>()
            while (matcher.find()) {
                clusters.add(matcher.group()!!)
            }
            return clusters
        }
    }
}
