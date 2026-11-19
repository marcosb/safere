// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Checks ordered choice independently of the lengths consumed by its alternatives.  */
@DisabledForCrosscheck("Includes intentional SafeRE region-local surrogate semantics")
internal class GraphemeAlternativePriorityTest {
    @Test
    @Tag("work-counter")
    fun splitBoundaryCompletionPreservesLinearWork() {
        for (regex in arrayOf<String>("(?:(\\X)|(.))*", "(?:(.)|(\\X))*", "(?:\\X|.)*z|(?:.|\\X)*")) {
            val pattern = Pattern.compile(regex)
            for (full in booleanArrayOf(false, true)) {
                var previous: Long = -1
                for (size in intArrayOf(256, 512, 1024, 2048)) {
                    val input = "a".repeat(size) + "\uD83D\uDC4D"
                    val matcher = pattern.matcher(input).region(0, input.length - 1)
                    val work =
                        WorkCounter.countForTesting(
                            Runnable {
                                Assertions.assertThat(if (full) matcher.matches() else matcher.find()).isTrue()
                            })
                    Assertions.assertThat(work).isPositive()
                    if (previous >= 0) {
                        Assertions.assertThat(work).`as`("%s full=%s size=%s", regex, full, size)
                            .isLessThan(previous * 3)
                    }
                    previous = work
                }
            }
        }
    }

    @Test
    fun firstSuccessfulAlternativeDeterminesBoundsAndCaptures() {
        val atoms = arrayOf<String?>(
            ".",
            "\\X",
            "\\b{g}",
            ".?",
            "\\X?",
            ".+",
            "\\X+",
            ".*?",
            "\\X*?",
            ".\\b{g}",
            "\\X\\b{g}",
            "(?U:\\b).",
            "(?m:^.)",
            ".(?m:$)"
        )
        val inputs = arrayOf<String?>(
            "ab",
            "a\u0301b",
            "\r\nx",
            "\uD83D\uDC4Dx",
            "a\uD83D\uDC4D",
            "\uD83C\uDDE6".repeat(3),
            "\uD83D\uDC69\u200D\uD83D\uDC69",
            "\uD801\uDC00a"
        )
        for (left in atoms) {
            for (right in atoms) {
                val alternatives = Pattern.compile("(" + left + ")|(" + right + ")")
                val leftPattern = Pattern.compile(left)
                val rightPattern = Pattern.compile(right)
                for (input in inputs) {
                    for (start in 0..input!!.length) {
                        for (end in start..input.length) {
                            for (transparent in booleanArrayOf(false, true)) {
                                for (full in booleanArrayOf(false, true)) {
                                    val first: Matcher = matcher(leftPattern, input, start, end, transparent)
                                    val second: Matcher = matcher(rightPattern, input, start, end, transparent)
                                    val firstMatches = if (full) first.matches() else first.lookingAt()
                                    val secondMatches = if (full) second.matches() else second.lookingAt()
                                    val actual: Matcher = matcher(alternatives, input, start, end, transparent)
                                    val matched = if (full) actual.matches() else actual.lookingAt()
                                    val label =
                                        (alternatives
                                            .toString() + " region="
                                                + start
                                                + ":"
                                                + end
                                                + " transparent="
                                                + transparent
                                                + " full="
                                                + full
                                                + " input="
                                                + input)
                                    Assertions.assertThat(matched).`as`(label).isEqualTo(firstMatches || secondMatches)
                                    if (matched) {
                                        val expected = if (firstMatches) first else second
                                        Assertions.assertThat(actual.start()).`as`(label).isEqualTo(expected.start())
                                        Assertions.assertThat(actual.end()).`as`(label).isEqualTo(expected.end())
                                        Assertions.assertThat(actual.group(if (firstMatches) 1 else 2))
                                            .`as`(label)
                                            .isEqualTo(expected.group())
                                        Assertions.assertThat(actual.group(if (firstMatches) 2 else 1)).`as`(label)
                                            .isNull()
                                        if (!full) {
                                            val find: Matcher = matcher(alternatives, input, start, end, transparent)
                                            Assertions.assertThat(find.find()).`as`(label).isTrue()
                                            Assertions.assertThat(find.start()).`as`(label).isEqualTo(expected.start())
                                            Assertions.assertThat(find.end()).`as`(label).isEqualTo(expected.end())
                                            Assertions.assertThat(find.group(if (firstMatches) 1 else 2))
                                                .`as`(label)
                                                .isEqualTo(expected.group())
                                            Assertions.assertThat(find.group(if (firstMatches) 2 else 1)).`as`(label)
                                                .isNull()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun reusedMatcherPreservesPriorityAsConsumptionLimitsChange() {
        val matcher = Pattern.compile("(.|(\\X))").matcher("\uD83D\uDC4D")
        for (end in intArrayOf(2, 1, 2, 1)) {
            matcher.region(0, end)
            Assertions.assertThat(matcher.matches()).isTrue()
            matcher.region(0, end)
            Assertions.assertThat(matcher.lookingAt()).isTrue()
            Assertions.assertThat(matcher.end()).isEqualTo(end)
            Assertions.assertThat(matcher.group(2)).isNull()
            matcher.region(0, end)
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.end()).isEqualTo(end)
            Assertions.assertThat(matcher.group(2)).isNull()
        }
    }

    @Test
    fun earlierCandidateRetainsPriorityOverLaterCompletions() {
        val matcher = Pattern.compile("(\\Xb)|([b\uDC4D])").matcher("z\uD83D\uDC4Db")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(1)
        Assertions.assertThat(matcher.end()).isEqualTo(4)
        Assertions.assertThat(matcher.group(1)).isEqualTo("\uD83D\uDC4Db")
        Assertions.assertThat(matcher.group(2)).isNull()
        Assertions.assertThat(matcher.find()).isFalse()
    }

    companion object {
        private fun matcher(
            pattern: Pattern, input: String?, start: Int, end: Int, transparent: Boolean
        ): Matcher {
            return pattern.matcher(input).region(start, end).useTransparentBounds(transparent)
        }
    }
}
