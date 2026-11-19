// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.safere.fuzz.FuzzSupport.CompiledPattern
import java.util.List
import java.util.function.Function
import java.util.regex.MatchResult

class ReplacementFuzzer {
    @Test
    fun terminalEmptyReplacementState() {
        // Exercise MatcherPair's state exclusion; MatcherTest pins the public API behavior.
        for (regex in mutableListOf<String?>("x*", "x?", "(x)*", "(?<part>x*)", "", "$", "\\b", "x+")) {
            for (input in mutableListOf<String?>("", "x", "yxxy", "yyyy")) {
                val pattern = FuzzSupport.compileOrSkip(regex, 0)
                Assertions.assertThat(pattern.matcher(input).replaceAll("-")).isTrue()
                Assertions.assertThat(pattern.matcher(input).replaceAll(Function { result: MatchResult? -> "-" }))
                    .isTrue()
                val first = pattern.matcher(input)
                Assertions.assertThat(first.replaceFirst("-")).isTrue()
                first.hasReplacementMatchState()
            }
        }
    }

    @Test
    fun nonAsciiDigitsInGroupReferences() {
        // Only '0'-'9' are group-reference digits. The group counts straddle the values a
        // code-point-derived number would take (U+0661 - '0' == 1585, and "$1" + U+0662 accumulates to
        // 1596), so a non-ASCII digit that is wrongly accepted selects an in-range group.
        for (groups in mutableListOf<Int?>(1, 2, 10, 1585, 1596, 1800)) {
            val pattern = FuzzSupport.compileOrSkip(Companion.manyGroups(groups!!), 0)
            Assertions.assertThat<CompiledPattern?>(pattern).isNotNull()
            val input = "a"
            for (replacement in mutableListOf<String?>(
                "$\u0661",
                "$1\u0662",
                "$12\u0662",
                "$\u06F1",
                "$1\u06F0",
                "$\uFF19"
            )) {
                pattern.matcher(input).replaceAll(replacement)
                pattern.matcher(input).replaceFirst(replacement)
                appendReplacementLoop(pattern, input, replacement, false)
                appendReplacementLoop(pattern, input, replacement, true)
            }
        }
    }

    @FuzzTest(maxDuration = "30s")
    fun replacement(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    companion object {
        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            val regex: String?
            val flags: Int
            val input: String
            val replacement: String?
            if (data.consumeBoolean()) {
                val patternCharacters = mutableListOf<String?>("\u03D1", "\u00DF")
                val foldedCharacters: MutableList<String> = mutableListOf<String?>("\u03F4", "\u1E9E")
                val variant = data.consumeInt(0, patternCharacters.size - 1)
                val suffix = data.pickValue<String?>(mutableListOf<String?>(".", "[xy]", "(?:x|y)"))
                regex = "(?iu)" + patternCharacters.get(variant) + suffix
                flags = 0
                input =
                    (data.consumeString(32)
                            + foldedCharacters.get(variant)
                            + data.pickValue<String?>(mutableListOf<String?>("x", "y"))
                            + data.consumeString(32))
                replacement = data.consumeString(32)
            } else if (data.consumeBoolean()) {
                val atom = data.pickValue<String?>(mutableListOf<String?>("x", "[xy]", "(x)", "(?<part>x)", "(?:x|y)"))
                regex = atom + data.pickValue<String?>(mutableListOf<String?>("*", "?", "{0,3}", "*?", "+"))
                flags = 0
                input = data.consumeString(64)
                replacement = data.pickValue<String?>(mutableListOf<String?>("-", "", "[$0]"))
            } else if (data.consumeBoolean()) {
                val leading = data.pickValue<String?>(mutableListOf<String?>("\\s", "[é😀]", "[ab]"))
                val repetition = data.pickValue<String?>(mutableListOf<String?>("*", "*?", "+", "{0,3}", "{1,3}"))
                regex = "(" + leading + repetition + ")# noqa" + data.pickValue<String?>(
                    mutableListOf<String?>(
                        "[0-9]",
                        "\\b"
                    )
                )
                flags = 0
                input =
                    (data.pickValue<String?>(mutableListOf<String?>(" ", "é😀", "ab")).repeat(data.consumeInt(0, 64))
                            + data.pickValue<String>(mutableListOf<String?>("# noqaX", "# noqa1", "# nopeX"))
                            + data.pickValue<String?>(mutableListOf<String?>("", "    # noqa2", "é😀# noqa3")))
                replacement = data.pickValue<String?>(mutableListOf<String?>("X", "", "<$0>", "<$1>"))
            } else if (data.consumeBoolean()) {
                val low = data.pickValue<String>(mutableListOf<String?>("\uDC00", "\uDE00", "\uDFFF"))
                val high = data.pickValue<String?>(mutableListOf<String?>("\uD800", "\uD83D", "\uDBFF"))
                regex = "(\\s" + data.pickValue<String?>(
                    mutableListOf<String?>(
                        "*",
                        "*?",
                        "+",
                        "{0,3}"
                    )
                ) + ")" + low + "[bc]"
                flags = 0
                input =
                    " " + low + "b;" + (high + low + "b;").repeat(data.consumeInt(1, 64)) + " " + low + "c"
                replacement = data.pickValue<String?>(mutableListOf<String?>("X", "<$0>", "<$1>"))
            } else if (data.consumeBoolean()) {
                val window = data.consumeInt(1, 16)
                regex = "(\\s*)# noqa[ab]*a[ab]{" + window + "}Z"
                flags = 0
                val binary = StringBuilder()
                val length = data.consumeInt(0, 512)
                for (i in 0..<length) {
                    binary.append(if (data.consumeBoolean()) 'a' else 'b')
                }
                val candidate =
                    "# noqa" + binary + "a" + "b".repeat(window) + data.pickValue<String?>(
                        mutableListOf<String?>(
                            "Z",
                            "Y"
                        )
                    )
                input =
                    (data.pickValue<String?>(List.of<String?>("", "# noqa" + "a" + "b".repeat(window) + "Z;"))
                            + " ".repeat(data.consumeInt(0, 64))
                            + candidate)
                replacement = data.pickValue<String?>(mutableListOf<String?>("X", "<$0>", "<$1>"))
            } else if (data.consumeBoolean()) {
                // Non-ASCII decimal digits must not be accepted as group-reference digits, directly after
                // '$' or after an ASCII digit. Use enough groups that a number derived from the code point
                // (or accumulated from it) is a valid group, so a wrongly accepted digit selects a group
                // instead of failing.
                val groups = data.pickValue<Int?>(mutableListOf<Int?>(1, 2, 10, 11, 100, 1585, 1594, 1596, 1738, 1800))
                regex = manyGroups(groups)
                flags = 0
                input = "a" + data.consumeString(8)
                replacement = nonAsciiDigitReplacement(data)
            } else {
                regex = data.consumeString(256)
                flags = FuzzSupport.consumeFlags(data)
                input = data.consumeString(2048)
                replacement = data.consumeString(1024)
            }
            val pattern = FuzzSupport.compileOrSkip(regex, flags)
            if (pattern == null) {
                return
            }

            val replaceAllMatcher = pattern.matcher(input)
            if (!replaceAllMatcher.replaceAll(replacement)) {
                return
            }
            replaceAllMatcher.reset()
            replaceAllMatcher.find()

            val replaceFirstMatcher = pattern.matcher(input)
            if (!replaceFirstMatcher.replaceFirst(replacement)) {
                return
            }
            replaceFirstMatcher.hasReplacementMatchState()

            appendReplacementLoop(pattern, input, replacement, data.consumeBoolean())
        }

        /**
         * Returns a pattern with `groups` capture groups where only group 1 captures text. The
         * other groups are empty, so matching stays cheap even with thousands of groups, while a wrongly
         * selected group still changes the replacement output.
         */
        private fun manyGroups(groups: Int): String {
            return "(a)" + "()".repeat(groups - 1)
        }

        private fun nonAsciiDigitReplacement(data: FuzzedDataProvider): String {
            val nonAscii =
                data.pickValue<String?>(
                    mutableListOf<String?>(
                        "\u0660",
                        "\u0661",
                        "\u0669",
                        "\u06F0",
                        "\u06F1",
                        "\u06F9",
                        "\u0966",
                        "\uFF11",
                        "\uFF19",
                        "\uD835\uDFCE"
                    )
                )
            val digit = data.pickValue<String?>(mutableListOf<String?>("0", "1", "2", "9"))
            val reference =
                when (data.consumeInt(0, 3)) {
                    0 -> "$" + nonAscii
                    1 -> "$" + digit + nonAscii
                    2 -> "$" + digit + digit + nonAscii
                    else -> "$" + digit + nonAscii + digit
                }
            return (data.pickValue<String?>(mutableListOf<String?>("", "[", "x"))
                    + reference
                    + data.pickValue<String?>(mutableListOf<String?>("", "]", "$1")))
        }

        private fun appendReplacementLoop(
            pattern: CompiledPattern,
            input: String,
            replacement: String?,
            useStringBuffer: Boolean
        ) {
            val matcher = pattern.matcher(input)
            if (useStringBuffer) {
                val sb = StringBuffer()
                while (matcher.find()) {
                    if (!matcher.appendReplacement(sb, replacement)) {
                        return
                    }
                }
                matcher.appendTail(sb)
            } else {
                val sb = StringBuilder()
                while (matcher.find()) {
                    if (!matcher.appendReplacement(sb, replacement)) {
                        return
                    }
                }
                matcher.appendTail(sb)
            }
        }
    }
}
