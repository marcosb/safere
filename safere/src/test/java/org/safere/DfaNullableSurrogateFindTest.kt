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

/** Search-start compatibility when a nullable pattern begins inside a surrogate pair.  */
internal class DfaNullableSurrogateFindTest {
    @ParameterizedTest
    @MethodSource("cases")
    fun findFromSurrogateInteriorMatchesJdk(regex: String, input: String, start: Int) {
        val expected = Pattern.compile(regex).matcher(input)
        val actual = org.safere.Pattern.compile(regex).matcher(input)
        Assertions.assertThat(actual.find(start)).isEqualTo(expected.find(start))
        Assertions.assertThat(actual.start()).isEqualTo(expected.start())
        Assertions.assertThat(actual.end()).isEqualTo(expected.end())
        Assertions.assertThat(actual.group()).isEqualTo(expected.group())
    }

    companion object {
        fun cases(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of(".|", "\uDB7D\uDF02A", 1),
                Arguments.of(".|", "x\uDB7D\uDF02A", 2),
                Arguments.of(".|", "\uDB7D\uDF02", 1),
                Arguments.of(".|", "\uDB7D\uDF02A", 2),
                Arguments.of(".|", "\uDB7D\uDF02" + "A".repeat(300), 1),
                Arguments.of("a|", "\uDB7D\uDF02A", 1),
                Arguments.of(".?", "\uDB7D\uDF02A", 1),
                Arguments.of("(?:.|)A?", "\uDB7D\uDF02A", 1),
                Arguments.of("(.)|", "\uDB7D\uDF02A", 1)
            )
        }
    }
}
