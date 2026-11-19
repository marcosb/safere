// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.List
import java.util.regex.Pattern
import java.util.stream.Stream

/** Split searches that start from the input end or scan for keywords must agree with the JDK.
 * 
 * End-anchored probes only run on inputs of at least 1,024 code units, so most inputs are padded
 * with filler; the short inputs remain as controls. */
internal class SplitSearchPathTest {
    @ParameterizedTest
    @MethodSource("cases")
    fun splitMatchesJdk(regex: String, flags: Int, input: String?) {
        val pattern = Pattern.compile(regex, flags)
        val expected = Pattern.compile(regex, flags)
        for (limit in intArrayOf(-1, 0, 1, 2, 3)) {
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
        private val FILLER = "the quick brown fox jumps over the lazy dog ".repeat(50).trim { it <= ' ' }

        private fun cases(): Stream<Arguments?>? {
            val regexes = mutableListOf<String?>(
                "(?:foo|fax)$",
                "\\s*(?:foo|fax)\\z",
                "(?:foo|fax)\\Z",
                "\\s*$",
                "o*\\z",
                "\\b(?:foo|bar|baz)\\b"
            )
            val inputs =
                List.of<String?>(
                    FILLER + " foo",
                    FILLER + " foo\n",
                    FILLER + " fax\r\n",
                    FILLER + "foo",
                    FILLER + "  \n",
                    FILLER + " Bar " + FILLER + " baz",
                    "foo " + FILLER + " BAZ\nfoo",
                    FILLER,
                    " foo",
                    "foo\n",
                    ""
                )
            return regexes.stream()
                .flatMap<Arguments?> { regex: String? ->
                    Stream.of<Int?>(0, org.safere.Pattern.CASE_INSENSITIVE)
                        .flatMap<Arguments?> { flags: Int? ->
                            inputs.stream().map<Arguments?> { input: String? -> Arguments.of(regex, flags, input) }
                        }
                }
        }
    }
}
