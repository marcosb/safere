// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/** Splitting must preserve matches when anchored and unanchored searches share cached states.  */
internal class SplitDfaCachingTest {
    @Test
    fun splitKeepsIntermediateDelimiterAfterGreedyContinuation() {
        // JDK 26 Pattern.split requires every delimiter, with trailing empty tokens omitted. Refs #889.
        Assertions.assertThat<String?>(Pattern.compile("[0-9]*0").split("0xxx011a----------------0"))
            .containsExactly("", "xxx", "11a----------------")
    }

    @ParameterizedTest
    @MethodSource("splitCases")
    fun splitApisPreserveDelimitersAcrossCacheReuse(regex: String, input: String, limit: Int) {
        val pattern = Pattern.compile(regex)
        val expected = java.util.regex.Pattern.compile(regex)
        for (reuse in 0..2) {
            Assertions.assertThat<String?>(pattern.split(input, limit)).containsExactly(*expected.split(input, limit))
            Assertions.assertThat<String?>(pattern.splitWithDelimiters(input, limit))
                .containsExactly(*expected.splitWithDelimiters(input, limit))
            Assertions.assertThat<String?>(pattern.splitAsStream(input).toList())
                .containsExactlyElementsOf(expected.splitAsStream(input).toList())
            val actualMatcher = pattern.matcher(input)
            val expectedMatcher = expected.matcher(input)
            while (expectedMatcher.find()) {
                Assertions.assertThat(actualMatcher.find()).isTrue()
                Assertions.assertThat(actualMatcher.start()).isEqualTo(expectedMatcher.start())
                Assertions.assertThat(actualMatcher.end()).isEqualTo(expectedMatcher.end())
                Assertions.assertThat(actualMatcher.group()).isEqualTo(expectedMatcher.group())
            }
            Assertions.assertThat(actualMatcher.find()).isFalse()
            Assertions.assertThat(pattern.matcher(input).lookingAt()).isEqualTo(expected.matcher(input).lookingAt())
            Assertions.assertThat(pattern.matcher(input).matches()).isEqualTo(expected.matcher(input).matches())
        }
    }

    companion object {
        @JvmStatic
        private fun splitCases(): Stream<Arguments?>? {
            return Stream.of<Array<String?>?>(
                arrayOf<String?>("[0-9]", "0", "1"),
                arrayOf<String?>("[a-z]", "a", "b"),
                arrayOf<String?>("[\\x00-y]", "\u0000", "1"),
                arrayOf<String?>("[😀😁]", "😀", "😁")
            )
                .flatMap<Arguments?> { alphabet: Array<String?>? ->
                    Stream.of<String>("*", "+", "*?", "{0,3}")
                        .flatMap<Arguments?> { quantifier: String? ->
                            Stream.of<Boolean?>(false, true)
                                .flatMap<Arguments?> { capture: Boolean? ->
                                    val body = alphabet!![0] + quantifier + alphabet[1]
                                    val regex = if (capture == true) "(" + body + ")" else body
                                    Stream.of<Int?>(16, 512)
                                        .flatMap<Arguments?> { gap: Int? ->
                                            val input =
                                                (alphabet[1]
                                                        + "~~~"
                                                        + alphabet[1]
                                                        + alphabet[2]!!.repeat(2)
                                                        + "~".repeat(gap!!)
                                                        + alphabet[1])
                                            Stream.of<Int?>(-1, 0, 1, 2, 3, 10)
                                                .map<Arguments?> { limit: Int? -> Arguments.of(regex, input, limit) }
                                        }
                                }
                        }
                }
        }
    }
}
