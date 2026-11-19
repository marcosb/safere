// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import java.util.List

class UnicodeFuzzer {
    @FuzzTest(maxDuration = "30s")
    fun unicode(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    companion object {
        private val GRAPHEME_CLUSTER_REGEXES = mutableListOf<String?>(
            "\\X",
            "\\X+",
            "\\Xz",
            "\\X\\X",
            "\\X\\X\\X",
            "\\X{2}",
            "\\X{3}",
            "(\\X)(\\X)",
            "a|\\X",
            "(a)|(\\X)",
            "^\\^?\\X\\X"
        )

        private val GRAPHEME_CLUSTER_INPUTS: MutableList<String> = List.of<String?>(
            "a",
            "ab",
            "a\u0301b",
            "e\u0301",
            "\u0301\u0301a",
            "\u0301".repeat(44) + "a".repeat(8),
            "\r\n",
            "\uD83C\uDDFA\uD83C\uDDF8",
            "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDE8",
            "\uD83D\uDC4D\uD83C\uDFFD",
            "\uD83D\uDC69\u200D\uD83D\uDCBB",
            "a\uD83D\uDC69\u200D\uD83D\uDC69",
            "\uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDCBB",
            "a\u200D",
            "\u1100\u1161",
            "\uAC00\u11A8",
            "\u0600a"
        )

        private val INDIC_CONJUNCT_REGEXES =
            mutableListOf<String?>("\\X", "\\X+", "\\X\\b{g}", "\\X\\X", "\\X{2}", "(?:\\X){2}", "(\\X)(\\X)")

        private val INDIC_CONJUNCT_INPUTS: MutableList<String> = mutableListOf<String?>(
            "\u0915\u094D\u0937",
            "\u0915\u094D\u0937\u093F",
            "\u0915\u094D\u200D\u0915",
            "\u0915\u094D\u0301\u200D\u0915",
            "\u0995\u09CD\u200D\u0995"
        )

        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            for (regex in GRAPHEME_CLUSTER_REGEXES) {
                val graphemePattern = FuzzSupport.compileOrSkip(regex, 0)
                for (input in GRAPHEME_CLUSTER_INPUTS) {
                    val matcher = graphemePattern.matcher(input)
                    while (matcher.find()) {
                        // Continue through the sequence so group boundaries are compared at every cluster.
                    }
                }
            }
            for (regex in INDIC_CONJUNCT_REGEXES) {
                val graphemePattern = FuzzSupport.compileOrSkip(regex, 0)
                for (input in INDIC_CONJUNCT_INPUTS) {
                    val matcher = graphemePattern.matcher(input)
                    while (matcher.find()) {
                        // Continue through the sequence so group boundaries are compared at every cluster.
                    }
                }
            }
            val anchoredSuffix =
                FuzzSupport.compileOrSkip("\\Xz$", 0).matcher("a".repeat(2048) + "z")
            anchoredSuffix.find()

            val regex = data.consumeString(256)
            val flags = FuzzSupport.consumeFlags(data)
            val input = FuzzSupport.consumeUnicodeHeavyString(data, 512)
            val pattern = FuzzSupport.compileOrSkip(regex, flags)
            if (pattern == null) {
                return
            }

            val matcher = pattern.matcher(input)
            matcher.find()
            matcher.reset()
            matcher.matches()
            matcher.reset()
            matcher.lookingAt()
        }
    }
}
