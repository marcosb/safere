// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.regex.Pattern
import java.util.stream.Stream

/** Literal split delimiters must fit in the input before their characters are compared.  */
internal class SplitLiteralBoundsTest {
    @ParameterizedTest
    @MethodSource("literalCases")
    fun splitApisHandleLiteralLengthBoundaries(regex: String, flags: Int, input: String?) {
        val pattern = Pattern.compile(regex, flags)
        val expected = Pattern.compile(regex, flags)
        Assertions.assertThat<String?>(pattern.split(input)).containsExactly(*expected.split(input))
        Assertions.assertThat<String?>(pattern.splitWithDelimiters(input))
            .containsExactly(*expected.splitWithDelimiters(input, 0))
        for (limit in intArrayOf(-2, -1, 0, 1, 2, 3, Int.Companion.MAX_VALUE)) {
            Assertions.assertThat<String?>(pattern.split(input, limit))
                .`as`("split limit=%s", limit)
                .containsExactly(*expected.split(input, limit))
            Assertions.assertThat<String?>(pattern.splitWithDelimiters(input, limit))
                .`as`("splitWithDelimiters limit=%s", limit)
                .containsExactly(*expected.splitWithDelimiters(input, limit))
        }
        Assertions.assertThat<String?>(pattern.splitAsStream(input).toList())
            .containsExactlyElementsOf(expected.splitAsStream(input).toList())
    }

    companion object {
        private fun literalCases(): Stream<Arguments?>? {
            // Refs #1050. Include unanchored and case-sensitive controls, both absolute anchors,
            // successful delimiters, and short prefixes ending at supplementary code point boundaries.
            return Stream.of<String?>("a", "abc", "a😀b")
                .flatMap<Arguments?> { literal: String? ->
                    Stream.of<String?>("", "^", "\\A")
                        .flatMap<Arguments?> { anchor: String? ->
                            Stream.of<Int?>(
                                0,
                                org.safere.Pattern.CASE_INSENSITIVE,
                                org.safere.Pattern.CASE_INSENSITIVE or org.safere.Pattern.UNICODE_CASE
                            )
                                .flatMap<Arguments?> { flags: Int? ->
                                    Stream.of<String?>(
                                        "",
                                        "a",
                                        "ab",
                                        "AB",
                                        "a😀",
                                        "abc",
                                        "ABC",
                                        "AbCtail",
                                        "a😀b",
                                        "A😀Btail",
                                        "xabc",
                                        "xA😀B"
                                    )
                                        .map<Arguments?> { input: String? ->
                                            Arguments.of(
                                                anchor + literal, flags, input
                                            )
                                        }
                                }
                        }
                }
        }
    }
}
