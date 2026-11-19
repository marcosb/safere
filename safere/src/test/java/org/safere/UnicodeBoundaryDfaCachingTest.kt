// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.charset.StandardCharsets
import java.util.stream.Stream

/** Regression coverage for Unicode word classification in cached DFA transitions.  */
internal class UnicodeBoundaryDfaCachingTest {
    @ParameterizedTest
    @MethodSource("cases")
    fun findSequenceMatchesJdk(regex: String, input: String) {
        val expected = java.util.regex.Pattern.compile(regex).matcher(input)
        val actual = org.safere.Pattern.compile(regex).matcher(input)
        while (expected.find()) {
            Assertions.assertThat(actual.find()).`as`("find for %s", regex).isTrue()
            Assertions.assertThat(actual.start()).isEqualTo(expected.start())
            Assertions.assertThat(actual.end()).isEqualTo(expected.end())
        }
        Assertions.assertThat(actual.find()).isFalse()
    }

    @ParameterizedTest
    @MethodSource("cases")
    @DisabledForCrosscheck("UTF-8 input is a SafeRE extension")
    fun utf8FindSequenceMatchesJdk(regex: String, input: String) {
        val expected = java.util.regex.Pattern.compile(regex).matcher(input)
        val actual =
            org.safere.Pattern.compile(regex).matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
        while (expected.find()) {
            Assertions.assertThat(actual.find()).`as`("UTF-8 find for %s", regex).isTrue()
            Assertions.assertThat(actual.start())
                .isEqualTo(input.substring(0, expected.start()).toByteArray(StandardCharsets.UTF_8).size)
            Assertions.assertThat(actual.end())
                .isEqualTo(input.substring(0, expected.end()).toByteArray(StandardCharsets.UTF_8).size)
        }
        Assertions.assertThat(actual.find()).isFalse()
    }

    @ParameterizedTest
    @MethodSource("supplementaryCases")
    @DisabledForCrosscheck("SafeRE intentionally prevents matching bounds inside surrogate pairs")
    fun supplementaryWordClassesPreserveScalarBounds(regex: String?, bounds: IntArray) {
        val actual = org.safere.Pattern.compile(regex).matcher(" 𐐀`\u180e")
        var i = 0
        while (i < bounds.size) {
            Assertions.assertThat(actual.find()).isTrue()
            Assertions.assertThat(actual.start()).isEqualTo(bounds[i])
            Assertions.assertThat(actual.end()).isEqualTo(bounds[i + 1])
            i += 2
        }
        Assertions.assertThat(actual.find()).isFalse()
    }

    companion object {
        @JvmStatic
        fun supplementaryCases(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of("(?U).\\b", intArrayOf(0, 1, 1, 3)),
                Arguments.of("(?U).\\B", intArrayOf(3, 4, 4, 5)),
                Arguments.of("(?U)\\b.", intArrayOf(1, 3, 3, 4)),
                Arguments.of("(?U)\\B.", intArrayOf(0, 1, 4, 5))
            )
        }

        @JvmStatic
        fun cases(): Stream<Arguments?>? {
            return Stream.of<String?>("α", "中", "\u0301", "\u0660", "\u200c")
                .flatMap<Arguments?> { word: String? ->
                    Stream.of<String?>("\\b", "\\B", "(?:(?-U:\\b)|\\B)")
                        .flatMap<Arguments?> { boundary: String? ->
                            Stream.of<String?>("", "x".repeat(300))
                                .flatMap<Arguments?> { prefix: String? ->
                                    Stream.of<Arguments?>(
                                        Arguments.of(
                                            "(?U)." + boundary,
                                            prefix + " " + word + "`\u180e"
                                        ),
                                        Arguments.of(
                                            "(?U)" + boundary + ".",
                                            prefix + " " + word + "`\u180e"
                                        )
                                    )
                                }
                        }
                }
        }
    }
}
