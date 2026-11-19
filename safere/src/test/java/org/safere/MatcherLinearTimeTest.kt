// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.function.Consumer
import java.util.function.IntConsumer

/** Work-bound regression tests for matcher operations.  */
@DisabledForCrosscheck("work accounting is a SafeRE linear-time implementation check")
@Tag("work-counter")
internal class MatcherLinearTimeTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun captureHistoryDoesNotAddWorkBeforeDfaRejection(utf8: Boolean) {
        for (repeats in intArrayOf(3, 12, 24)) {
            val pattern = Pattern.compile("(error:\\[)[A-Z](\\] code:500)")
            val rejected = "error:[x] code:500 ".repeat(repeats) + "C"
            val bytes = Utf8Input.validated(rejected.toByteArray(StandardCharsets.UTF_8))
            val reject =
                Runnable {
                    Assertions.assertThat(if (utf8) pattern.matcher(bytes).find() else pattern.matcher(rejected).find())
                        .isFalse()
                }
            val cold = WorkCounter.countWithForwardDfaStepsForTesting(reject)
            val primer = pattern.matcher("error:[C] code:500")
            Assertions.assertThat(primer.find()).isTrue()
            Assertions.assertThat(primer.group(1)).isEqualTo("error:[")
            val warm = WorkCounter.countWithForwardDfaStepsForTesting(reject)

            Assertions.assertThat(cold).isPositive()
            Assertions.assertThat(warm)
                .`as`("capture history must not add rejecting work, repeats=%s", repeats)
                .isLessThanOrEqualTo(cold)
        }
    }

    @Test
    @DisplayName("group access stays linear for ambiguous repeated captures")
    fun groupAccessWithAmbiguousRepeatedCapturesStaysLinear() {
        val pattern = Pattern.compile("((a|aa))*")

        assertNoWorkCliff(
            "matches()+group(1)",
            "a".repeat(100),
            "a".repeat(5000),
            Consumer { input: String? ->
                val matcher = pattern.matcher(input)
                Assertions.assertThat(matcher.matches()).isTrue()
                Assertions.assertThat(matcher.group(1)).isEqualTo("a")
                Assertions.assertThat(matcher.group(2)).isEqualTo("a")
            })
    }

    @Test
    @DisplayName("matches() stays linear for repeated dot-star with bounded captures")
    fun matchesWithRepeatedDotStarAndBoundedCaptures() {
        val pattern: Pattern = repeatedDotStarSqlUnionPattern()

        assertNoWorkCliff(
            "matches()",
            IntConsumer { blocks: Int ->
                val input: String = repeatedDotStarSqlUnionInput(blocks)
                val matcher = pattern.matcher(input)
                Assertions.assertThat(matcher.matches()).isTrue()
                Assertions.assertThat(matcher.group()).isEqualTo(input)
                Assertions.assertThat(matcher.start()).isEqualTo(0)
                Assertions.assertThat(matcher.end()).isEqualTo(input.length)
            })
    }

    @Test
    @DisplayName("lookingAt() stays linear for repeated dot-star with bounded captures")
    fun lookingAtWithRepeatedDotStarAndBoundedCaptures() {
        val pattern: Pattern = repeatedDotStarSqlUnionPattern()

        assertNoWorkCliff(
            "lookingAt()",
            IntConsumer { blocks: Int ->
                val matcher: Matcher = pattern.matcher(repeatedDotStarSqlUnionInput(blocks))
                Assertions.assertThat(matcher.lookingAt()).isTrue()
                Assertions.assertThat(matcher.group(1)).contains("INFORMATION_SCHEMA")
            })
    }

    @Test
    @DisplayName("group access after find() stays linear for repeated dot-star captures")
    fun findGroupWithRepeatedDotStarAndBoundedCaptures() {
        val pattern: Pattern = repeatedDotStarSqlUnionPattern()

        assertNoWorkCliff(
            "find()+group(1)",
            IntConsumer { blocks: Int ->
                val matcher: Matcher = pattern.matcher(repeatedDotStarSqlUnionInput(blocks))
                Assertions.assertThat(matcher.find()).isTrue()
                Assertions.assertThat(matcher.group(1)).contains("INFORMATION_SCHEMA")
            })
    }

    @Test
    @DisplayName("region find stays linear for repeated dot-star captures")
    fun regionFindWithRepeatedDotStarAndBoundedCaptures() {
        val pattern: Pattern = repeatedDotStarSqlUnionPattern()

        assertNoWorkCliff(
            "region().find()",
            IntConsumer { blocks: Int ->
                val input = "prefix\n" + repeatedDotStarSqlUnionInput(blocks) + "suffix\n"
                val matcher = pattern.matcher(input)
                matcher.region("prefix\n".length, input.length - "suffix\n".length)
                Assertions.assertThat(matcher.find()).isTrue()
                Assertions.assertThat(matcher.group(1)).contains("INFORMATION_SCHEMA")
            })
    }

    companion object {
        private val SCENARIO_TIMEOUT: Duration = Duration.ofSeconds(30)

        private fun repeatedDotStarSqlUnionInput(selectCount: Int): String {
            val input = StringBuilder()
            for (i in 1..selectCount) {
                input
                    .append("(SELECT *, PARSE_DATE('%Y-%m-%d', '2025-06-25') AS snapshot_date FROM ")
                    .append("`project-")
                    .append("%02d".formatted(i))
                    .append("`.`region2`.INFORMATION_SCHEMA.TABLE_OPTIONS)\n")
                    .append("UNION ALL\n")
            }
            return input.toString()
        }

        private fun repeatedDotStarSqlUnionPattern(): Pattern {
            return Pattern.compile(
                ".*SELECT.*FROM.*(.*INFORMATION_SCHEMA.*){5,}.*",
                Pattern.CASE_INSENSITIVE or Pattern.DOTALL
            )
        }

        private fun assertNoWorkCliff(api: String?, scenario: IntConsumer) {
            val largerPositiveWork: Long = countedWork(Runnable { scenario.accept(16) })
            val nearMinimumWork: Long = countedWork(Runnable { scenario.accept(5) })

            Assertions.assertThat(largerPositiveWork).`as`("%s should use matcher work", api).isPositive()
            Assertions.assertThat(nearMinimumWork)
                .`as`(
                    "%s near-minimum input should not be dramatically slower than a larger "
                            + "positive input; nearWork=%d largerWork=%d",
                    api, nearMinimumWork, largerPositiveWork
                )
                .isLessThan(largerPositiveWork * 50)
        }

        private fun assertNoWorkCliff(
            api: String?, nearMinimumInput: String?, largerPositiveInput: String?, scenario: Consumer<String?>
        ) {
            scenario.accept(nearMinimumInput)
            scenario.accept(largerPositiveInput)
            val largerPositiveWork: Long = countedWork(Runnable { scenario.accept(largerPositiveInput) })
            val nearMinimumWork: Long = countedWork(Runnable { scenario.accept(nearMinimumInput) })

            Assertions.assertThat(largerPositiveWork).`as`("%s should use matcher work", api).isPositive()
            Assertions.assertThat(nearMinimumWork)
                .`as`(
                    "%s near-minimum input should not be dramatically slower than a larger "
                            + "positive input; nearWork=%d largerWork=%d",
                    api, nearMinimumWork, largerPositiveWork
                )
                .isLessThan(largerPositiveWork * 50)
        }

        private fun countedWork(task: Runnable?): Long {
            return org.junit.jupiter.api.Assertions.assertTimeoutPreemptively<Long?>(
                SCENARIO_TIMEOUT,
                ThrowingSupplier { WorkCounter.countForTesting(task) })!!
        }
    }
}
