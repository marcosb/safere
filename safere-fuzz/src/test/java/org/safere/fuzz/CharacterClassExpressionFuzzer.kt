// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class CharacterClassExpressionFuzzer {
    @FuzzTest(maxDuration = "30s")
    fun characterClassExpressions(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    @JvmRecord
    private data class Separator(val text: String, val commentsModeOnly: Boolean)
    companion object {
        private val BASE_PIECES = arrayOf<String?>(
            "",
            "a",
            "ab",
            "a-b",
            " ^a",
            "#c\n^a",
            "!-&",
            "0",
            "0-1",
            "&",
            "\\&",
            "\\Q&\\E",
            "\\Qa\\E",
            "\\Qab\\E",
            "\\Q\\E",
            "Ā",
            "\\Ā",
            "[a]",
            "[b]",
            "[ab]",
            "[^b]",
            "\\d",
            "\\D",
            "\\w",
            "\\W",
            "\\p{Lower}",
            "\\p\\Q\\EL",
            "\\p\\QL\\E",
            "\\p{ L}",
            "\\p{L }",
            "\\c\\Qa\\E",
            "\\P{Lower}",
            "\\p{javaLowerCase}"
        )
        private val AMPERSAND_PIECES = arrayOf<String?>("&", "\\&", "\\Q&\\E")
        private val TRAILING_PIECES = arrayOf<String?>(
            "",
            "&",
            "\\&",
            "\\Q&\\E",
            "-\\D",
            "-a",
            "-&",
            "&-&",
            "&-a",
            "-& -a",
            "-&\\Q\\E -a",
            "-&a",
            "-&&",
            "&-&&-&",
            "&-& \\P{Lower}",
            "\\Q\\E-\\D",
            "\\Q\\E]"
        )
        private val SEPARATORS = arrayOf<Separator?>(
            Separator("", false),
            Separator("\\Q\\E", false),
            Separator("\\Q\\E\\Q\\E", false),
            Separator(" ", true),
            Separator(" #x\n", true),
            Separator("\\Q\\E ", true),
            Separator(" \\Q\\E", true)
        )
        private val OPERATORS = arrayOf<String?>("&&")
        private val RIGHT_PIECES = arrayOf<String?>(
            "",
            "a",
            "b",
            "a-b",
            " ^a",
            "#c\n^a",
            "!-&",
            "0",
            "0-1",
            "&",
            "\\&",
            "\\Q&\\E",
            "\\Qa\\E",
            "\\Q\\E",
            "Ā",
            "\\Ā",
            "[a]",
            "[b]",
            "[ab]",
            "\\d",
            "\\D",
            "\\w",
            "\\p{Lower}",
            "\\p\\Q\\EL",
            "\\p\\QL\\E",
            "\\p{ L}",
            "\\p{L }",
            "\\c\\Qa\\E",
            "\\P{Lower}",
            "\\p{javaLowerCase}"
        )
        private val INPUTS: MutableList<String> = mutableListOf<String?>(
            "", "a", "b", "c", "^", "&", "-", "0", "1", "9", "A", "Z", "_", "`", "x", " ", "\t", "Ā",
            "é", "\n", "]", "!", ".", "\\", "!]", "-]", "A]"
        )
        private val REGRESSION_REGEXES = arrayOf<String?>(
            "[\\Qab\\E&&[b]]",
            "[[ab]&&[^b]]",
            "[&\\Q\\E &&\\d]",
            "[a-z&&[def]]",
            "[a-z&&[^bc]]",
            "[a-z&&d-f&&e]",
            "[!-&&]",
            "(?x)[ ^a]",
            "(?x)[#c\n^a]",
            "[&-&&]",
            "[\\Q&\\E-&&]"
        )

        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            // #1070: model checks use unspaced references, not the JDK's trivia-sensitive parsing.
            assertRangeTriviaIgnored("(?x)[!-", " ", "]]")
            assertRangeTriviaIgnored("(?x)[!-", " ", "]a]")
            assertRangeTriviaIgnored("(?x)[!-", " #c\n", "]]")
            assertRangeTriviaIgnored("(?x)[!-", " ", "[A]]")
            assertRangeTriviaIgnored("(?x)[a-", " #c\n", "]")
            for (regex in REGRESSION_REGEXES) {
                FuzzSupport.assertFullMatchesJdk(regex, 0, INPUTS)
            }

            val comments = data.consumeBoolean()
            val negated = data.consumeBoolean()
            val prefix = (if (comments) "(?x)" else "") + "[" + (if (negated) "^" else "")
            val regex =
                when (data.consumeInt(0, 2)) {
                    0 -> (prefix
                            + data.pickValue<String?>(BASE_PIECES)
                            + data.pickValue<String?>(BASE_PIECES)
                            + pickSeparator(data, comments)
                            + data.pickValue<String?>(OPERATORS)
                            + pickSeparator(data, comments)
                            + data.pickValue<String?>(RIGHT_PIECES)
                            + data.pickValue<String?>(TRAILING_PIECES)
                            + "]")

                    1 -> (prefix
                            + data.pickValue<String?>(BASE_PIECES)
                            + data.pickValue<String?>(AMPERSAND_PIECES)
                            + pickSeparator(data, comments)
                            + data.pickValue<String?>(OPERATORS)
                            + pickSeparator(data, comments)
                            + data.pickValue<String?>(RIGHT_PIECES)
                            + data.pickValue<String?>(TRAILING_PIECES)
                            + "]")

                    2 -> (prefix
                            + pickSeparator(data, comments)
                            + data.pickValue<String?>(OPERATORS)
                            + pickSeparator(data, comments)
                            + data.pickValue<String?>(RIGHT_PIECES)
                            + data.pickValue<String?>(TRAILING_PIECES)
                            + "]")

                    else -> throw AssertionError()
                }

            FuzzSupport.assertFullMatchesJdk(regex, 0, INPUTS)
            // Append the range-shape axis so saved inputs retain their original expression.
            val before = prefix + data.pickValue<String>(mutableListOf<String?>("!", "A", "\\x41", "\\Q!\\E")) + "-"
            val gap: String = pickSeparator(data, comments)
            val after =
                data.pickValue<String?>(
                    mutableListOf<String?>(
                        "[",
                        "]"
                    )
                ) + data.pickValue<String>(mutableListOf<String?>("", "a", "\\d", "[A-Z]")) + "]"
            assertRangeTriviaIgnored(before, gap, after)
            // Broad differential discovery still reports intentional differences; never waive them.
            FuzzSupport.assertFullMatchesJdk(before + gap + after, 0, INPUTS)
        }

        private fun assertRangeTriviaIgnored(before: String?, gap: String, after: String?) {
            val regex = before + gap + after
            val reference: Pattern?
            try {
                reference = Pattern.compile(before + after)
            } catch (e: PatternSyntaxException) {
                Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
                    .`as`("range trivia preserves rejection of /%s/ in /%s/", before + after, regex)
                    .isInstanceOf(PatternSyntaxException::class.java)
                return
            }
            val pattern = org.safere.Pattern.compile(regex)
            for (input in INPUTS) {
                Assertions.assertThat(pattern.matcher(input).matches())
                    .`as`("/%s/ equivalent to unspaced /%s/ on <%s>", regex, before + after, input)
                    .isEqualTo(reference.matcher(input).matches())
            }
        }

        private fun pickSeparator(data: FuzzedDataProvider, comments: Boolean): String {
            var separator: Separator
            do {
                separator = data.pickValue<Separator>(SEPARATORS)
            } while (separator.commentsModeOnly && !comments)
            return separator.text
        }
    }
}
