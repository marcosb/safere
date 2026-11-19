// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.safere.Matcher.LiteralAlternationPreparedRunner
import org.safere.Matcher.PreparedMatchRunner
import java.nio.charset.StandardCharsets
import kotlin.math.max

@DisabledForCrosscheck("SafeRE work counters and execution paths")
@Tag("work-counter")
internal class LiteralAlternationWorkTest {
    @Test
    fun earlyMatchesDoNotScanTheUnrelatedTail() {
        val pattern = Pattern.compile("apple|banana|cherry")
        for (literal in arrayOf<String>("apple", "banana", "cherry")) {
            for (offset in intArrayOf(0, 1023, 1024, 2048, 3072, 8192, 16384, 65536)) {
                val prefix = "x".repeat(offset) + literal
                val shorter: Long = work(pattern, prefix + "x".repeat(max(32768, offset * 2)))
                val longer: Long = work(pattern, prefix + "x".repeat(1048576))
                Assertions.assertThat(shorter).isPositive()
                Assertions.assertThat(longer).`as`("%s at %s", literal, offset).isLessThanOrEqualTo(shorter * 2)
            }
        }
    }

    @Test
    fun failedSearchesKeepLinearWorkAcrossGrowingWindows() {
        for (regex in arrayOf<String>("apple|banana|cherry", "apple|banana|cherry|durian|elderberry|fig")) {
            val pattern = Pattern.compile(regex)
            val shorter =
                WorkCounter.countForTesting(
                    Runnable { Assertions.assertThat(pattern.matcher("x".repeat(32768)).find()).isFalse() })
            val longer =
                WorkCounter.countForTesting(
                    Runnable { Assertions.assertThat(pattern.matcher("x".repeat(131072)).find()).isFalse() })
            Assertions.assertThat(shorter).isPositive()
            Assertions.assertThat(longer).`as`(regex).isLessThanOrEqualTo(shorter * 6)
        }
    }

    @Test
    fun repeatedSparseMatchesDoNotRescanTheRemainingTail() {
        val pattern = Pattern.compile("apple|banana|cherry")
        for (gap in intArrayOf(1, 1024, 4096)) {
            val segment = "x".repeat(gap) + "cherry"
            val shorter: Long = allWork(pattern, segment.repeat(16))
            val longer: Long = allWork(pattern, segment.repeat(64))
            Assertions.assertThat(longer).`as`("gap %s", gap).isLessThanOrEqualTo(shorter * 6)
        }
    }

    @Test
    fun alternativesWithoutAWholeInputFilterDoNotScanBeyondALateMatch() {
        for (regex in arrayOf<String>("apple|banana|cherry|durian|elderberry|fig", "foo|barfoo", "a|ab")) {
            val pattern = Pattern.compile(regex)
            Assertions.assertThat<PreparedMatchRunner?>(pattern.preparedMatchRunner(false))
                .`as`(regex)
                .isInstanceOf(LiteralAlternationPreparedRunner::class.java)
            Assertions.assertThat<RejectPrefilter?>(pattern.rejectPrefilter()).`as`(regex).isNull()
            val literal = regex.substring(regex.lastIndexOf('|') + 1)
            val prefix = "x".repeat(8192) + literal
            val shorter: Long = work(pattern, prefix + "x".repeat(8192))
            val longer: Long = work(pattern, prefix + "x".repeat(65536))
            Assertions.assertThat(shorter).isPositive()
            Assertions.assertThat(longer).`as`(regex).isLessThanOrEqualTo(shorter * 2)
        }
    }

    @Test
    fun utf8SearchesOnDenseFirstBytesStayLinearAndLocal() {
        Assumptions.assumeFalse(
            VectorScanProviders.vectorProviderAvailable(),
            "The UTF-8 literal search is off when the vector Teddy scan is available"
        )
        // "ab " starts two of the literals often enough for the UTF-8 literal search to run.
        for (regex in arrayOf<String>("apple|banana|cherry", "blossom|sparkling|gardens", "ab|cherry|abc")) {
            val pattern = Pattern.compile(regex)
            val filler = if (regex.startsWith("ab|")) "aa bb " else "ab bc "
            val shorter: Long = utf8Work(pattern, filler.repeat(5000), false)
            val longer: Long = utf8Work(pattern, filler.repeat(20000), false)
            Assertions.assertThat(shorter).isPositive()
            Assertions.assertThat(longer).`as`(regex).isLessThanOrEqualTo(shorter * 6)
            val literal = regex.substring(regex.lastIndexOf('|') + 1)
            for (offset in intArrayOf(0, 1000, 4000)) {
                val prefix = filler.repeat(offset) + literal
                val early: Long = utf8Work(pattern, prefix + filler.repeat(max(8000, offset * 2)), true)
                val late: Long = utf8Work(pattern, prefix + filler.repeat(200000), true)
                Assertions.assertThat(late).`as`("%s at %s", regex, offset).isLessThanOrEqualTo(early * 2)
            }
        }
    }

    companion object {
        private fun work(pattern: Pattern, text: String?): Long {
            return WorkCounter.countForTesting(Runnable {
                Assertions.assertThat(pattern.matcher(text).find()).isTrue()
            })
        }

        private fun utf8Work(pattern: Pattern, text: String, expected: Boolean): Long {
            val input = Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8))
            return WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher(input).find()).isEqualTo(expected) })
        }

        private fun allWork(pattern: Pattern, text: String?): Long {
            return WorkCounter.countForTesting(
                Runnable {
                    val matcher = pattern.matcher(text)
                    while (matcher.find()) {
                        Assertions.assertThat(matcher.group()).isEqualTo("cherry")
                    }
                })
        }
    }
}
