// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Pins `replaceAll` and `replaceFirst` against the JDK for patterns that have a `find()` fast path or an end anchor, which skip the DFA-driven replace loop.
 */
internal class ReplaceFastPathTest {
    @Test
    fun replaceMatchesTheJdk() {
        for (regex in REGEXES) {
            val jdk = java.util.regex.Pattern.compile(regex)
            val safere = Pattern.compile(regex)
            for (input in INPUTS) {
                for (replacement in REPLACEMENTS) {
                    Assertions.assertThat(safere.matcher(input).replaceAll(replacement))
                        .`as`("replaceAll /%s/ on %s with %s", regex, input, replacement)
                        .isEqualTo(jdk.matcher(input).replaceAll(replacement))
                    Assertions.assertThat(safere.matcher(input).replaceFirst(replacement))
                        .`as`("replaceFirst /%s/ on %s with %s", regex, input, replacement)
                        .isEqualTo(jdk.matcher(input).replaceFirst(replacement))
                }
            }
        }
    }

    @Test
    fun matcherStateAfterReplaceFirstMatchesTheJdk() {
        for (regex in mutableListOf<String>("[0-9]", "\\p{L}", "[é😀]")) {
            val input = "ab 😀 é 42"
            val jdk = java.util.regex.Pattern.compile(regex).matcher(input)
            val safere = Pattern.compile(regex).matcher(input)
            jdk.replaceFirst("X")
            safere.replaceFirst("X")
            jdk.reset()
            safere.reset()
            while (jdk.find()) {
                Assertions.assertThat(safere.find()).`as`("/%s/ find after replaceFirst", regex).isTrue()
                Assertions.assertThat(safere.start()).isEqualTo(jdk.start())
                Assertions.assertThat(safere.end()).isEqualTo(jdk.end())
            }
            Assertions.assertThat(safere.find()).isFalse()
        }
    }

    companion object {
        private val REGEXES: MutableList<String> = mutableListOf<String>(
            "[0-9]",
            "\\p{L}",
            "[é😀]",
            "[\\x{1F600}-\\x{1F64F}]",
            "^[0-9]",
            "(?i)\\b(?:foo|bar|baz)\\b",
            "\\s*(?:foo|fax)\\z",
            "\\s*(?:foo|fax)$"
        )

        private val INPUTS: MutableList<String> = mutableListOf<String>(
            "",
            "no digits or keywords here",
            "order 12345 shipped on 2025-03-22",
            "7 at the start",
            "été 😀 and 😃 smile",
            "Foo bar BAZ food barn",
            "ends with a fax",
            "ends with foo\n",
            "ends with foo\r\n",
            "  foo"
        )

        private val REPLACEMENTS: MutableList<String> = mutableListOf<String>("X", "", "<$0>")
    }
}
