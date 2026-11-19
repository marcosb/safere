// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest

class EscapeSyntaxFuzzer {
    @FuzzTest(maxDuration = "30s")
    fun escapeSyntax(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    companion object {
        // "[\\t-" makes each escape the end of a class range.
        private val PREFIXES = arrayOf<String?>("", "^", "(?i)", "(?x)", "[", "[^", "[\\t-")
        private val ESCAPES = arrayOf<String?>(
            "\\0",
            "\\00",
            "\\07",
            "\\08",
            "\\077",
            "\\078",
            "\\123",
            "\\400",
            "\\777",
            "\\u0041",
            "\\u{41}",
            "\\x41",
            "\\x{41}",
            "\\h",
            "\\H",
            "\\v",
            "\\V",
            "\\e",
            "\\cA",
            "\\ca",
            "\\c\t",
            "\\c#\na",
            "\\c!",
            "\\cĀ",
            "\\c\uD800",
            "\\c",
            "\\c😀",
            "\\©",
            "\\Ā",
            "\\é",
            "\\☃",
            "\\😀",
            "\\Q\\E",
            "\\Q&\\E",
            "\\Q-\\E",
            "\\Qab\\E"
        )
        private val SUFFIXES = arrayOf<String?>(
            "",
            "$",
            "]",
            "*",
            "+",
            "?",
            "2*",
            "22*",
            "a",
            "-",
            "-z",
            "&&[a]",
            "\\Q\\E-z",
            " -z",
            "- z",
            "\\-z"
        )
        private val INPUTS = mutableListOf<String?>(
            "", "a", "A", "0", "7", "@", "\u001b", "&", "-", "©", "Ā", "é", "☃", "😀", "𠀀", "🙀",
            "\u0000", "\u0140", "\uD800", "\uD840", "\uf57f"
        )

        // Reviewed intentional differences have separate expected-outcome tests, not fixed agreement
        // probes. FuzzSupportTest pins unsupported backreference outcomes. Prefix/escape/suffix
        // generation still explores those spellings and the ambiguous class-range spellings.
        private val REGRESSION_REGEXES = arrayOf<String?>(
            "^\\©",
            "[\\©]",
            "\\Ā",
            "[\\Ā]",
            "\\☃",
            "[\\☃]",
            "\\😀",
            "[\\😀]",
            "\\0",
            "\\08",
            "\\h",
            "\\H",
            "\\v",
            "\\V",
            "\\c!",
            "[\\c!]",
            "^\\c",
            "[\\c]",
            "\\cĀ",
            "[\\cĀ]",
            "\\c😀",
            "(?x)\\c\ta",
            "(?x)\\c#\na",
            "[^\\c\uD800]",
            "(?x)[\\v -z]",
            "[\\v\\-z]",
            "[\\t\\-\\v]"
        )

        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            for (regex in REGRESSION_REGEXES) {
                FuzzSupport.assertFullMatchesJdk(regex, 0, INPUTS)
            }

            val prefix: String = data.pickValue<String>(PREFIXES)
            var suffix: String = data.pickValue<String>(SUFFIXES)
            if (prefix.startsWith("[") && !suffix.contains("]")) {
                suffix = suffix + "]"
            }
            if (!prefix.startsWith("[") && suffix == "]") {
                suffix = ""
            }

            val regex = prefix + data.pickValue<String>(ESCAPES) + suffix
            FuzzSupport.assertFullMatchesJdk(regex, FuzzSupport.consumeParserFlags(data), INPUTS)
            val introducer = data.pickValue<String?>(mutableListOf<String?>("\\p", "\\P", "\\c", "\\x", "\\u"))
            val gap = data.pickValue<String>(mutableListOf<String?>("", "\\Q\\E", "\\Q\\E\\Q\\E", " ", "#c\n"))
            var component =
                data.pickValue<String?>(mutableListOf<String?>("L", "{L}", "{ L}", "{L }", "a", "41", "0041"))
            if (data.consumeBoolean()) component = "\\Q" + component + "\\E"
            var expression = introducer + gap + component
            if (data.consumeBoolean()) expression = "[" + expression + "]"
            FuzzSupport.assertFullMatchesJdk(expression, FuzzSupport.consumeParserFlags(data), INPUTS)
            val high = data.pickValue<String?>(mutableListOf<String?>("\\uD83D", "\\uD840", "\\uD800"))
            val low = data.pickValue<String>(mutableListOf<String?>("DE00", "DC00", "D800", "0041"))
            val between = data.pickValue<String?>(mutableListOf<String?>("", " ", "\\Q\\E", "#c\n"))
            val digitGap = data.pickValue<String?>(mutableListOf<String?>("", " ", "\\Q\\E", "#c\n"))
            var pair = high + between + "\\u" + low.substring(0, 2) + digitGap + low.substring(2)
            if (data.consumeBoolean()) pair += "\\uDE00"
            if (data.consumeBoolean()) pair = "[" + pair + "]"
            FuzzSupport.assertFullMatchesJdk(pair, FuzzSupport.consumeParserFlags(data), INPUTS)
        }
    }
}
