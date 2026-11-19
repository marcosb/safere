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
import java.util.function.IntConsumer
import java.util.stream.Stream

/** Tests for SafeRE's documented grapheme-region model where it intentionally diverges from JDK.  */
@DisabledForCrosscheck("SafeRE's documented grapheme-region model intentionally diverges from selected JDK traces")
internal class GraphemeRegionModelTest {
    @Test
    @DisplayName("repeated explicit grapheme boundaries preserve every compositional find candidate")
    fun repeatedExplicitGraphemeBoundariesPreserveEveryCompositionalFindCandidate() {
        Assertions.assertThat<String?>(findTrace(Pattern.compile("\\b{g}{2}").matcher("ab")))
            .containsExactly("0-0;g0=", "1-1;g0=", "2-2;g0=")
    }

    @Test
    @DisplayName("repeated explicit grapheme boundaries compose inside consuming concatenations")
    fun repeatedExplicitGraphemeBoundariesComposeInsideConsumingConcatenations() {
        Assertions.assertThat(Pattern.compile("a\\b{g}{2}b").matcher("ab").matches()).isTrue()
    }

    @Test
    @DisplayName("repeated explicit grapheme boundaries preserve alternative continuation candidates")
    fun repeatedExplicitGraphemeBoundariesPreserveAlternativeContinuationCandidates() {
        Assertions.assertThat<String?>(findTrace(Pattern.compile("(?:\\b{g}{2}|a).").matcher("ab")))
            .containsExactly("0-1;g0=U+0061", "1-2;g0=U+0062")
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("regionLocalCases")
    @DisplayName("opaque region-local grapheme behavior is compositional")
    fun opaqueRegionLocalGraphemeBehaviorIsCompositional(testCase: Case) {
        val matcher =
            Pattern.compile(testCase.regex)
                .matcher(testCase.input)
                .region(testCase.start, testCase.end)

        Assertions.assertThat<String?>(findTrace(matcher)).containsExactlyElementsOf(testCase.expectedFinds)
    }

    @JvmRecord
    internal data class Case(
        val name: String,
        val regex: String?,
        val input: String?,
        val start: Int,
        val end: Int,
        val expectedFinds: MutableList<String>?
    ) {
        override fun toString(): String {
            return name
        }
    }

    companion object {
        @JvmStatic
        private fun regionLocalCases(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of(
                    Case(
                        "explicit boundaries compose across ordinary characters",
                        "\\b{g}\\X\\b{g}",
                        "ab",
                        0,
                        2,
                        mutableListOf<String>("0-1;g0=U+0061", "1-2;g0=U+0062")
                    )
                ),
                Arguments.of(
                    Case(
                        "standalone ZWJ at opaque region start has a trailing boundary",
                        "\\X\\b{g}",
                        "\uD83D\uDC69\u200D\uD83D\uDC69",
                        2,
                        5,
                        mutableListOf<String>("2-3;g0=U+200D", "3-5;g0=U+1F469")
                    )
                ),
                Arguments.of(
                    Case(
                        "explicit leading boundary composes at opaque low-surrogate region start",
                        "\\b{g}\\X",
                        "\uD83D\uDC4D\uD83C\uDFFB",
                        1,
                        3,
                        mutableListOf<String>("1-2;g0=U+DC4D", "2-3;g0=U+D83C")
                    )
                ),
                Arguments.of(
                    Case(
                        "explicit leading boundary composes at opaque ZWJ region start",
                        "\\b{g}\\X",
                        "\uD83D\uDC69\u200D\uD83D\uDC69",
                        2,
                        5,
                        mutableListOf<String>("2-3;g0=U+200D", "3-5;g0=U+1F469")
                    )
                ),
                Arguments.of(
                    Case(
                        "explicit boundaries compose around standalone ZWJ at opaque region start",
                        "\\b{g}\\X\\b{g}",
                        "\uD83D\uDC69\u200D\uD83D\uDC69",
                        2,
                        5,
                        mutableListOf<String>("2-3;g0=U+200D", "3-5;g0=U+1F469")
                    )
                ),
                Arguments.of(
                    Case(
                        "explicit boundaries compose after a control before an extender",
                        "\\b{g}\\X\\b{g}",
                        "a\u0000\u0301",
                        1,
                        3,
                        mutableListOf<String>("1-2;g0=U+0000", "2-3;g0=U+0301")
                    )
                ),
                Arguments.of(
                    Case(
                        "explicit boundaries compose around an Indic suffix region",
                        "\\b{g}\\X\\b{g}",
                        "\u0915\u094D\u0937",
                        1,
                        3,
                        mutableListOf<String>("1-2;g0=U+094D", "2-3;g0=U+0937")
                    )
                ),
                Arguments.of(
                    Case(
                        "unanchored chained clusters can start at an Indic suffix",
                        "\\X\\X",
                        "\u0915\u094D\u0937",
                        0,
                        3,
                        mutableListOf<String>("1-3;g0=U+094D U+0937")
                    )
                ),
                Arguments.of(
                    Case(
                        "quantified clusters can start at an Indic suffix",
                        "\\X{2}",
                        "\u0915\u094D\u0937",
                        0,
                        3,
                        mutableListOf<String>("1-3;g0=U+094D U+0937")
                    )
                ),
                Arguments.of(
                    Case(
                        "captured chained clusters preserve suffix-local captures",
                        "(\\X)(\\X)",
                        "\u0915\u094D\u0937",
                        0,
                        3,
                        mutableListOf<String>("1-3;g0=U+094D U+0937;g1=U+094D;g2=U+0937")
                    )
                )
            )
        }

        private fun findTrace(matcher: Matcher): MutableList<String> {
            val trace: MutableList<String> = ArrayList<String>()
            while (matcher.find()) {
                val builder = StringBuilder()
                builder.append(matcher.start()).append('-').append(matcher.end())
                for (group in 0..matcher.groupCount()) {
                    builder.append(";g").append(group).append('=').append(codePoints(matcher.group(group)))
                }
                trace.add(builder.toString())
            }
            return trace
        }

        private fun codePoints(value: String?): String {
            if (value == null) {
                return "<null>"
            }
            val builder = StringBuilder()
            value
                .codePoints()
                .forEach(
                    IntConsumer { cp: Int ->
                        if (!builder.isEmpty()) {
                            builder.append(' ')
                        }
                        builder.append(String.format("U+%04X", cp))
                    })
            return builder.toString()
        }
    }
}
