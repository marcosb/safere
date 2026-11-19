// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Tests for SafeRE's documented Unicode case-folding model where it intentionally diverges from
 * selected JDK range traces.
 */
@DisabledForCrosscheck(
    ("SafeRE's documented Unicode case-folding model intentionally diverges from selected JDK range"
            + " traces")
)
internal class UnicodeCaseFoldingModelTest {
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("closedRangeCases")
    @DisplayName("Unicode case-insensitive ranges are closed under case folding")
    fun unicodeCaseInsensitiveRangesAreClosedUnderCaseFolding(
        description: String?, regex: String?, input: String?
    ) {
        val pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)

        Assertions.assertThat(pattern.matcher(input).matches()).`as`(description).isTrue()
    }

    @Test
    fun caseFamiliesAreReciprocalAcrossSyntaxAndFlags() {
        val families = arrayOf<String?>(
            "Iiİı",
            "KkK",
            "Ssſ",
            "Σσς",
            "ÅåÅ",
            "ΩωΩ",
            "ßẞ",
            "Ǆǅǆ",
            "Θθϑϴ",
            "\u0390\u1FD3",
            "\u03B0\u1FE3",
            "\uFB05\uFB06",
            "\uD801\uDC00\uD801\uDC28"
        )
        val flags = intArrayOf(
            0,
            Pattern.CASE_INSENSITIVE,
            Pattern.UNICODE_CASE,
            Pattern.UNICODE_CHARACTER_CLASS,
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CHARACTER_CLASS
        )
        for (family in families) {
            for (source in family!!.codePoints().toArray()) {
                val literal = "\\x{" + Integer.toHexString(source) + "}"
                for (flag in flags) {
                    for (regex in arrayOf<String>(
                        literal,
                        "[" + literal + "]",
                        "[" + literal + "-" + literal + "]",
                        "[^" + literal + "]",
                        "[^" + literal + "-" + literal + "]"
                    )) {
                        val pattern = Pattern.compile(regex, flag)
                        for (target in (family + "!").codePoints().toArray()) {
                            val insensitive = (flag and Pattern.CASE_INSENSITIVE) != 0
                            val unicode =
                                (flag and (Pattern.UNICODE_CASE or Pattern.UNICODE_CHARACTER_CLASS)) != 0
                            val member =
                                source == target
                                        || (insensitive
                                        && ((unicode && target != '!'.code)
                                        || (source < 128 && target < 128 && (source.lowercaseChar() == target.lowercaseChar()))))
                            Assertions.assertThat(pattern.matcher(String(Character.toChars(target))).matches())
                                .`as`("%s flags=%s input=U+%04X", regex, flag, target)
                                .isEqualTo(if (regex.startsWith("[^")) !member else member)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun largerRangesCloseBeforeNegationAndIntersection() {
        for (range in arrayOf<String>("H-J", "h-j", "İ-ı", "A-Z")) {
            for (input in arrayOf<String>("I", "i", "İ", "ı")) {
                Assertions.assertThat(Pattern.matches("(?iu)[" + range + "]", input)).isTrue()
                Assertions.assertThat(Pattern.matches("(?iu)[^" + range + "]", input)).isFalse()
                Assertions.assertThat(Pattern.matches("(?iu)[" + range + "&&[İ]]", input)).isTrue()
                Assertions.assertThat(Pattern.matches("(?iu)[" + range + "&&[^İ]]", input)).isFalse()
            }
        }
    }

    @Test
    fun reciprocalLiteralsWorkInSearchCapturesAndScopedFlags() {
        for (source in arrayOf<String>("I", "i", "İ", "ı")) {
            for (target in arrayOf<String>("I", "i", "İ", "ı")) {
                for (padding in intArrayOf(0, 300)) {
                    val input = "!".repeat(padding) + target + target + ":"
                    val matcher = Pattern.compile("(?iu:(" + source + "+))(?-i::)").matcher(input)
                    Assertions.assertThat(matcher.find()).isTrue()
                    Assertions.assertThat(matcher.start()).isEqualTo(padding)
                    Assertions.assertThat(matcher.group(1)).isEqualTo(target + target)
                    Assertions.assertThat(matcher.find()).isFalse()
                }
                Assertions.assertThat(
                    Pattern.compile(
                        source, Pattern.LITERAL or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
                    )
                        .matcher(target)
                        .matches()
                )
                    .isTrue()
            }
        }
    }

    companion object {
        private fun closedRangeCases(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of(
                    "singleton range [K-K] includes Kelvin sign's case-fold equivalent", "[K-K]", "\u212A"
                ),
                Arguments.of(
                    "uppercase range [A-Z] includes Kelvin sign's case-fold equivalent", "[A-Z]", "\u212A"
                ),
                Arguments.of(
                    "singleton range [I-I] includes capital I with dot's case variant", "[I-I]", "\u0130"
                ),
                Arguments.of(
                    "uppercase range [A-Z] includes capital I with dot's case variant", "[A-Z]", "\u0130"
                )
            )
        }
    }
}
