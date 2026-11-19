// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class DeferredAssertionFullMatchTest {
    @ParameterizedTest
    @ValueSource(strings = ["a", "!", "α", "😀", "\n", "\r\n", "\u2028"])
    fun deferredMatchKeepsConsumingAlternatives(atom: String) {
        val literal = Pattern.quote(atom)
        for (assertion in mutableListOf<String>("\\b", "\\B", "(?m:$)")) {
            for (quantifier in mutableListOf<String>("*", "*?")) {
                for (assertionFirst in booleanArrayOf(false, true)) {
                    val consuming = literal + literal
                    val alternatives =
                        if (assertionFirst) assertion + "|" + consuming else consuming + "|" + assertion
                    val regex = "(?U)(?:" + literal + ")" + quantifier + "(?:" + alternatives + ")"
                    val pattern = Pattern.compile(regex)
                    for (size in intArrayOf(2, 3, 300, 2)) {
                        val input = atom.repeat(size)
                        // The consuming alternative covers the entire input, regardless of assertion priority.
                        Assertions.assertThat(pattern.matcher(input).matches()).`as`("%s, size=%s", regex, size)
                            .isTrue()
                        Assertions.assertThat(pattern.matcher("x" + input + "x").region(1, 1 + input.length).matches())
                            .`as`("region: %s, size=%s", regex, size)
                            .isTrue()
                        val actual = pattern.matcher(input)
                        val expected =
                            java.util.regex.Pattern.compile(regex).matcher(input)
                        Assertions.assertThat(actual.lookingAt()).isEqualTo(expected.lookingAt())
                        Assertions.assertThat(actual.end()).`as`("lookingAt: %s", regex).isEqualTo(expected.end())
                        actual.reset()
                        expected.reset()
                        while (expected.find()) {
                            Assertions.assertThat(actual.find()).`as`("find: %s", regex).isTrue()
                            Assertions.assertThat(actual.start()).isEqualTo(expected.start())
                            Assertions.assertThat(actual.end()).isEqualTo(expected.end())
                        }
                        Assertions.assertThat(actual.find()).isFalse()
                    }
                    Assertions.assertThat(pattern.matcher(atom + "x").matches()).`as`(regex).isFalse()
                }
            }
        }
    }
}
