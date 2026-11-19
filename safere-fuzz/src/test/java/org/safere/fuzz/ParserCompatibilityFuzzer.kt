// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.safere.Pattern
import java.util.regex.PatternSyntaxException

class ParserCompatibilityFuzzer {
    @FuzzTest(maxDuration = "30s")
    fun parserCompatibility(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    companion object {
        private val ATOMS = arrayOf<String?>(
            "",
            "a",
            ".",
            "\\d",
            "\\D",
            "\\w",
            "\\W",
            "\\s",
            "\\S",
            "\\p{Lower}",
            "\\P{Lower}",
            "\\Q\\E",
            "\\Q\\E\\Q\\E",
            "\\Q*\\E",
            "^",
            "$",
            "\\b",
            "\\B",
            "\\b{g}",
            "a\\Q\\E",
            "[a]",
            "[^a]",
            "[a-z]",
            "[a-z&&[def]]",
            "()",
            "(?)",
            "(a)",
            "(?:a)",
            "(?<name>a)",
            "a\u001C\\^]",
            "a\u001D\\^]",
            "a\u001E\\^]",
            "a\u001F\\^]",
            "[a\u001Cb]",
            "[a\u001Db]",
            "[a\u001Eb]",
            "[a\u001Fb]"
        )
        private val PREFIXES = arrayOf<String?>("", "^", "(?i)", "(?x)", "(?m)", "(?s)")
        private val CONNECTORS = arrayOf<String?>(
            "",
            "",
            "|",
            "?",
            "??",
            "*",
            "*?",
            "* #q\n ?",
            "* #q\n +",
            "* #q\n + +",
            "+",
            "+?",
            "+ #q\n ?",
            "+ #q\n +",
            "+ #q\n + +",
            "{0}",
            "{00}",
            "{1,3}",
            "{01,03}",
            "{1}",
            "{01}",
            "{2}+",
            "{0,2}+",
            "{1} #q\n ?",
            "{1} #q\n +",
            "{1} #q\n + +",
            "??{1,3}",
            "??{01,03}",
            "?{1,3}",
            "?{01,03}",
            "*{1,3}",
            "*{01,03}",
            "+{1,3}",
            "+{0}?+",
            "+{0}?+{1}",
            "+{1}{0}?+?",
            "+++",
            "++?{0,2}?",
            "+{01,03}",
            "{1,3}{1,3}",
            "{01,03}{02}"
        )
        private val SUFFIXES = arrayOf<String?>("", "$", "?", "*", "+", "{2}", "{02}", "{1,3}")
        private val LEADING_COUNTED_REPEATS = arrayOf<String?>(
            "{0}",
            "{00}",
            "{1}",
            "{01}",
            "{1,3}",
            "{01,03}",
            "{1,}",
            "{01,}",
            "{1,3}?",
            "{01,03}?",
            "{1,3}$",
            "{01,03}$",
            "^{1,3}",
            "^{01,03}",
            "a|{1,3}",
            "a|{01,03}",
            "({1,3})",
            "({01,03})"
        )
        private val COMMENT_TERMINATED_PREFIXES = arrayOf<String?>(
            "a#\u0000", "a#\n", "a#\r", "a#\u0085", "a#\u2028", "a#\u2029"
        )
        private val MALFORMED_GROUP_SUFFIXES = arrayOf<String?>("(", "|(", "b|(", "(?:b)|(")
        private val INPUTS: MutableList<String> = mutableListOf<String?>(
            "",
            "a",
            "aa",
            "abc",
            "def",
            "0",
            " ",
            "\n",
            "*",
            "name",
            "a\u001C^]",
            "a\u001D^]",
            "a\u001E^]",
            "a\u001F^]",
            "a^]",
            "\u001C",
            "\u001D",
            "\u001E",
            "\u001F"
        )

        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            var flags = FuzzSupport.consumeParserFlags(data)
            val regex: String?
            if (data.consumeBoolean()) {
                flags = flags or Pattern.COMMENTS
                regex =
                    data.pickValue<String?>(COMMENT_TERMINATED_PREFIXES) + data.pickValue<String?>(
                        MALFORMED_GROUP_SUFFIXES
                    )
            } else if (data.consumeBoolean()) {
                regex = data.pickValue<String?>(PREFIXES) + data.pickValue<String?>(LEADING_COUNTED_REPEATS)
            } else {
                regex =
                    (data.pickValue<String?>(PREFIXES)
                            + data.pickValue<String?>(ATOMS)
                            + data.pickValue<String?>(CONNECTORS)
                            + data.pickValue<String?>(ATOMS)
                            + data.pickValue<String?>(SUFFIXES))
            }
            FuzzSupport.assertFullMatchesJdk(regex, flags, INPUTS)
            // Append consumers to preserve the interpretation of the existing corpus.
            assertOperandFreePossessiveCountedRepeatMatchesJdk(data)
            assertQuantifierBoundaryFindsMatchJdk(data)
            assertCountedRepeatQuoteGapsMatchJdk(data)
        }

        private fun assertCountedRepeatQuoteGapsMatchJdk(data: FuzzedDataProvider) {
            // Generate gaps inside the token, rather than only between a quantifier and its modifier.
            val count =
                data.pickValue<String>(
                    mutableListOf<String?>(
                        "{1}",
                        "{01,03}",
                        "{1,}",
                        "{0}",
                        "{12}",
                        "{,2}",
                        "{2,1}",
                        "{1,2"
                    )
                )
            val gap =
                data.pickValue<String?>(mutableListOf<String?>("\\Q\\E", "\\Q\\E\\Q\\E", " \\Q\\E#c\n", "\\Q1\\E"))
            val gapPosition = data.consumeInt(1, count.size - 1)
            val allGaps = data.consumeBoolean()
            val quotedCount = StringBuilder("{")
            for (i in 1..<count.size) {
                if (allGaps || i == gapPosition) quotedCount.append(gap)
                quotedCount.append(count.get(i))
            }
            val operand = data.pickValue<String?>(mutableListOf<String?>("a", "(a)", "[a]", "😀", "()", ""))
            val modifier = data.pickValue<String>(mutableListOf<String?>("", "?", "\\Q\\E?", "?+"))
            val flags = if (data.consumeBoolean()) Pattern.COMMENTS else 0
            val regex = operand + quotedCount + modifier
            val compiled = FuzzSupport.compileCompatibleOrSkip(regex, flags)
            if (compiled == null) return
            for (input in mutableListOf<String?>("", "a", "aa", "aaa", "baaaabaaa", "😀😀😀")) {
                compiled.matcher(input).matches()
                val pair = compiled.matcher(input)
                while (pair.find()) {
                    // find() compares bounds and every capture on each successful step.
                }
            }
        }

        private fun assertQuantifierBoundaryFindsMatchJdk(data: FuzzedDataProvider) {
            val operand = data.pickValue<String?>(mutableListOf<String?>("a", "(a)", "(a?)", "()", "(?i)"))
            val repeat = data.pickValue<String>(mutableListOf<String?>("?", "*", "+", "{0}", "{1,3}"))
            val gap = data.pickValue<String?>(mutableListOf<String?>("\\Q\\E", "\\Q\\E\\Q\\E", " \\Q\\E#c\n"))
            val modifier = data.pickValue<String?>(mutableListOf<String?>("?", "?+", "??"))
            val regex = operand + repeat + gap + modifier
            val flags = Pattern.COMMENTS
            FuzzSupport.assertFullMatchesJdk(regex, flags, INPUTS)
            val expected: java.util.regex.Pattern?
            try {
                expected = java.util.regex.Pattern.compile(regex, flags)
            } catch (rejected: PatternSyntaxException) {
                return  // Compile agreement was already checked above.
            }
            val actual = Pattern.compile(regex, flags)
            for (input in INPUTS) {
                val jdk = expected.matcher(input)
                val safe = actual.matcher(input)
                while (true) {
                    val found = jdk.find()
                    if (safe.find() != found) throw AssertionError("find disagreement: " + regex)
                    if (!found) break
                    for (group in 0..jdk.groupCount()) {
                        if (safe.start(group) != jdk.start(group) || safe.end(group) != jdk.end(group) || (safe.group(
                                group
                            ) != jdk.group(group))
                        ) {
                            throw AssertionError("find capture disagreement: " + regex + " on " + input)
                        }
                    }
                }
            }
        }

        private fun assertOperandFreePossessiveCountedRepeatMatchesJdk(data: FuzzedDataProvider) {
            val prefix =
                data.pickValue<String?>(mutableListOf<String?>("", "\\Q\\E", "(?i)", "a*", "a*?", "a{2}", "x|"))
            val count = data.pickValue<String>(mutableListOf<String?>("{0}", "{1}", "{1,2}", "{1,}"))
            val comments = data.consumeBoolean()
            val modifier = if (comments && data.consumeBoolean()) " #q\n +" else "+"
            val suffix = data.pickValue<String?>(mutableListOf<String?>("a", "", "[ab]"))
            val expression = prefix + count + modifier + suffix
            val regex = if (data.consumeBoolean()) "(" + expression + ")" else expression
            val flags = if (comments) Pattern.COMMENTS else 0
            val jdk = java.util.regex.Pattern.compile(regex, flags)
            // These generated forms all have an implicit empty operand. Compare them directly, including
            // captures, alongside broad parser generation.
            val safeRe = Pattern.compile(regex, flags)
            for (input in mutableListOf<String?>("", "a", "aa", "A", "ab", "x", "xa", "b")) {
                val expected = jdk.matcher(input)
                val actual = safeRe.matcher(input)
                val expectedMatch = expected.matches()
                if (actual.matches() != expectedMatch) {
                    throw AssertionError("Operand-free possessive count divergence for /" + regex + "/")
                }
                if (expectedMatch) {
                    for (group in 0..expected.groupCount()) {
                        if (actual.group(group) != expected.group(group)) {
                            throw AssertionError(
                                "Operand-free possessive capture divergence for /" + regex + "/"
                            )
                        }
                    }
                }
            }
        }
    }
}
