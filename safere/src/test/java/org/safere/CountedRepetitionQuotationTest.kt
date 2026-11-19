// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.regex.PatternSyntaxException
import java.util.stream.Stream

internal class CountedRepetitionQuotationTest {
    @ParameterizedTest
    @MethodSource("countsWithEmptyQuotes")
    fun emptyQuotesDisappearThroughoutCountedRepeatTokens(count: String) {
        // JDK 26 documents quotes and repetition separately; its lexical interaction is unspecified.
        // Match observed JDK behavior, including gaps within numbers and before delimiters. Refs #1060.
        for (operand in mutableListOf<String>("a", "(a)", "[a]", "\\Qa\\E", "😀", "")) {
            for (modifier in java.util.List.of<String?>("", "?", EMPTY_QUOTE + "?")) {
                val regex = operand + count + modifier
                assertSameMatches(regex, 0)
                assertSameMatches(regex, Pattern.COMMENTS)
            }
        }
    }

    @ParameterizedTest
    @MethodSource("countsWithEmptyQuotes")
    @DisabledForCrosscheck("empty-capture participation follows SafeRE's explicit repetition policy")
    fun emptyQuotesPreserveEmptyCaptureParticipation(count: String) {
        val unquotedCount: String = count.replace(EMPTY_QUOTE, "")
        val comma = unquotedCount.indexOf(',')
        val minimum = unquotedCount.substring(1, if (comma < 0) unquotedCount.length - 1 else comma).toInt()
        for (modifier in java.util.List.of<String?>("", "?", EMPTY_QUOTE + "?")) {
            val participates = minimum > 0 || !modifier.endsWith("?")
            for (flags in intArrayOf(0, Pattern.COMMENTS)) {
                // Both spellings must satisfy the independent capture policy. Comparing only with the
                // JDK would instead require its intentionally different direct-empty suppression.
                assertEmptyCaptureMatches(Pattern.compile("()" + count + modifier, flags), participates)
                assertEmptyCaptureMatches(
                    Pattern.compile("()" + unquotedCount + modifier.replace(EMPTY_QUOTE, ""), flags),
                    participates
                )
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["a{1 \\Q\\E, #count\n \\Q\\E3}", "a{1\\Q\\E #count\n ,3\\Q\\E }\\Q\\E ?", "a{1, #count\n \\Q\\E}"
        ]
    )
    fun emptyQuotesInterleaveWithCommentsAfterTheFirstDigit(regex: String) {
        assertSameMatches(regex, Pattern.COMMENTS)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["a{\\Q\\E}", "a{\\Q\\E,2}", "a{1\\Q\\E,0}", "a{1\\Q\\E", "a{1\\Q", "a{1,2\\Q\\E,3}", "a{1\\Q2\\E}", "a{1,\\Q2\\E}", "a{1\\Q,\\E2}", "a{1\\Q}\\E", "a{1\\Q\\E}?+", "a{\\Q\\E+1}", "a{1,\\Q\\E-2}", "a{\\Q\\E١}", "a{21474\\Q\\E83648}", "a{1,21474\\Q\\E83648}"
        ]
    )
    fun emptyQuotesDoNotMakeMalformedCountsOrNonemptyQuotesValid(regex: String) {
        Assertions.assertThatThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex) })
            .isInstanceOf(PatternSyntaxException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
            .isInstanceOf(PatternSyntaxException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["a{ \\Q\\E1}", "a{\\Q\\E 1}", "a{\\Q\\E#c\n1}"])
    fun commentsTriviaBeforeTheFirstCountDigitIsStillRejected(regex: String) {
        Assertions.assertThatThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex, org.safere.Pattern.COMMENTS) })
            .isInstanceOf(PatternSyntaxException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable {
            org.safere.Pattern.compile(
                regex,
                org.safere.Pattern.COMMENTS
            )
        })
            .isInstanceOf(PatternSyntaxException::class.java)
    }

    @Test
    @DisabledForCrosscheck("SafeRE rejects consuming possessive quantifiers")
    fun emptyQuotesDoNotBypassConsumingPossessiveRejection() {
        for (regex in mutableListOf<String>("a{1\\Q\\E,3}+", "a{1,3\\Q\\E}\\Q\\E+")) {
            java.util.regex.Pattern.compile(regex)
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
                .isInstanceOf(PatternSyntaxException::class.java)
                .hasMessageContaining("possessive")
        }
    }

    @Test
    @DisabledForCrosscheck("SafeRE rejects bounds over its repeat budget")
    fun emptyQuotesDoNotBypassRepeatBudgets() {
        for (regex in mutableListOf<String>("a{1\\Q\\E001}", "a{0,1\\Q\\E001}", "(?:a{1\\Q\\E,100}){1\\Q\\E,100}")) {
            java.util.regex.Pattern.compile(regex)
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
                .isInstanceOf(PatternSyntaxException::class.java)
                .hasMessageContaining("invalid repeat count")
        }
    }

    @Test
    fun zeroWidthPossessiveAndClassLiteralsKeepTheirMeaning() {
        for (regex in mutableListOf<String>(
            "(){1\\Q\\E,3}\\Q\\E+",
            "{1\\Q\\E,3}+",
            "[a{1\\Q\\E,3}]",
            "\\Qa{1\\Q\\E,3}"
        )) {
            assertSameMatches(regex!!, 0)
        }
    }

    @Test
    fun longRunsOfEmptyQuotesAndLeadingZerosAreStackSafe() {
        val regex =
            ("a{"
                    + EMPTY_QUOTE.repeat(20000)
                    + "0".repeat(20000)
                    + EMPTY_QUOTE.repeat(20000)
                    + "1,2"
                    + EMPTY_QUOTE.repeat(20000)
                    + "}")
        assertSameMatches(regex, 0)
    }

    companion object {
        private const val EMPTY_QUOTE = "\\Q\\E"
        private val INPUTS: MutableList<String> =
            java.util.List.of<String>("", "a", "aa", "aaa", "aaaa", "baaaabaaa", "😀😀😀", "a".repeat(13), "{1,3}")

        @JvmStatic
        fun countsWithEmptyQuotes(): Stream<String?>? {
            return Stream.of<String?>("{02}", "{12}", "{1,2}", "{01,03}", "{01,}", "{00,02}")
                .flatMap<String?> { count: String? ->
                    Stream.of<String?>(EMPTY_QUOTE, EMPTY_QUOTE.repeat(3))
                        .flatMap<String?> { gap: String? ->
                            val variants = Stream.builder<String?>()
                            val allGaps = StringBuilder("{")
                            for (i in 1..<count!!.length) {
                                variants.add(count.substring(0, i) + gap + count.substring(i))
                                allGaps.append(gap).append(count.get(i))
                            }
                            variants.add(allGaps.toString())
                            variants.build()
                        }
                }
        }

        private fun assertSameMatches(regex: String, flags: Int) {
            val expectedPattern = java.util.regex.Pattern.compile(regex, flags)
            val actualPattern = org.safere.Pattern.compile(regex, flags)
            for (input in INPUTS) {
                val expected = expectedPattern.matcher(input)
                val actual = actualPattern.matcher(input)
                Assertions.assertThat(actual.matches())
                    .`as`("matches /%s/ on %s", regex, input)
                    .isEqualTo(expected.matches())
                expected.reset()
                actual.reset()
                while (true) {
                    val found = expected.find()
                    Assertions.assertThat(actual.find()).`as`("find /%s/ on %s", regex, input).isEqualTo(found)
                    if (!found) break
                    for (group in 0..expected.groupCount()) {
                        Assertions.assertThat(actual.start(group)).isEqualTo(expected.start(group))
                        Assertions.assertThat(actual.end(group)).isEqualTo(expected.end(group))
                        Assertions.assertThat(actual.group(group)).isEqualTo(expected.group(group))
                    }
                }
            }
        }

        private fun assertEmptyCaptureMatches(pattern: org.safere.Pattern, participates: Boolean) {
            for (input in INPUTS) {
                val matcher = pattern.matcher(input)
                Assertions.assertThat(matcher.groupCount()).isEqualTo(1)
                Assertions.assertThat(matcher.matches())
                    .`as`("matches /%s/ on %s", pattern, input)
                    .isEqualTo(input.isEmpty())
                if (input.isEmpty()) {
                    assertEmptyCapture(matcher, 0, participates)
                }
                matcher.reset()
                Assertions.assertThat(matcher.lookingAt()).isTrue()
                assertEmptyCapture(matcher, 0, participates)
                matcher.reset()
                for (position in 0..input.length) {
                    Assertions.assertThat(matcher.find()).`as`("find /%s/ at %d on %s", pattern, position, input)
                        .isTrue()
                    assertEmptyCapture(matcher, position, participates)
                }
                Assertions.assertThat(matcher.find()).isFalse()
            }
        }

        private fun assertEmptyCapture(matcher: Matcher, position: Int, participates: Boolean) {
            Assertions.assertThat(matcher.start()).isEqualTo(position)
            Assertions.assertThat(matcher.end()).isEqualTo(position)
            val expected = if (participates) position else -1
            Assertions.assertThat(matcher.start(1)).isEqualTo(expected)
            Assertions.assertThat(matcher.end(1)).isEqualTo(expected)
            Assertions.assertThat(matcher.group(1)).isEqualTo(if (participates) "" else null)
        }
    }
}
