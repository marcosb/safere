// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.*

internal class DfaArrayGrowthTest {
    @Test
    fun acceleratedSearchRetainsStatesAcrossArrayGrowth() {
        // Original fuzz input from #882, including UTF-8 replacement characters.
        val regex: String =
            decode(
                "7862777c00000000000000006eebba80e78182ef8095d48437f28d9393e3acbddfaf0dd294"
                        + "efbfbf022c271854df802e2f696173d494d4bf80dfa5dfbfdfb37a24e3a081d99ede9e"
            )
        val input: String = decode("9e00000000000000002a0a00242a015e0a242a00a6188c5d7224a2247c9f45737a24")
        for (flags in intArrayOf(0, Pattern.CASE_INSENSITIVE, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)) {
            for (prefix in java.util.List.of<String?>("", "!", "\n", "a", "😀", "!".repeat(300))) {
                for (suffix in mutableListOf<String>("", "xbw", "XBW", "xbw xbw")) {
                    val pattern = Pattern.compile(regex, flags)
                    val text = prefix + input + suffix
                    for (pass in 0..1) {
                        val actual = pattern.matcher(text)
                        val expected =
                            java.util.regex.Pattern.compile(regex, flags).matcher(text)
                        while (expected.find()) {
                            Assertions.assertThat(actual.find()).isTrue()
                            Assertions.assertThat(actual.start()).isEqualTo(expected.start())
                            Assertions.assertThat(actual.end()).isEqualTo(expected.end())
                        }
                        Assertions.assertThat(actual.find()).isFalse()
                    }
                }
            }
        }
    }

    @Test
    fun acceleratedRestartHandlesDifferentStateAndAlphabetSizes() {
        // Long alternatives vary the alphabet size; partial prefixes populate the state cache.
        // A later candidate after a newline requires a different start context (#882).
        for (count in intArrayOf(4, 5, 8, 16)) {
            for (alphabetSize in intArrayOf(32, 64, 105, 113, 128)) {
                val tail = StringBuilder()
                for (i in 0..<alphabetSize) {
                    tail.appendCodePoint(0x400 + i)
                }
                val prefix = "\u0000".repeat(count)
                val regex = "xbw|" + prefix + tail + "$"
                val pattern = Pattern.compile(
                    regex,
                    org.safere.Pattern.CASE_INSENSITIVE or org.safere.Pattern.UNICODE_CASE
                )
                for (suffix in java.util.List.of<String?>("", "xbw", prefix + tail)) {
                    val text = "!" + prefix + "!\n\u0000" + "!".repeat(40) + suffix
                    for (pass in 0..1) {
                        val actual = pattern.matcher(text)
                        val expected =
                            java.util.regex.Pattern.compile(
                                regex, org.safere.Pattern.CASE_INSENSITIVE or org.safere.Pattern.UNICODE_CASE
                            )
                                .matcher(text)
                        while (expected.find()) {
                            Assertions.assertThat(actual.find()).isTrue()
                            Assertions.assertThat(actual.start()).isEqualTo(expected.start())
                            Assertions.assertThat(actual.end()).isEqualTo(expected.end())
                        }
                        Assertions.assertThat(actual.find()).isFalse()
                    }
                }
            }
        }
    }

    companion object {
        private fun decode(hex: String): String {
            return String(HexFormat.of().parseHex(hex), StandardCharsets.UTF_8)
        }
    }
}
