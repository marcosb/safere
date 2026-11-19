// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets

/**
 * Start-acceleration backoff carried across the searches of a `find()` sequence.
 * 
 * 
 * The accelerator for `[xz][0-9]+y` finds the next `x` or `z`. On input where
 * every `x` starts a match right where the previous match ended, every call skips nothing, so
 * the accelerator only adds overhead. A character class does not identify exact match starts, so
 * the matcher-level accelerator calls are charged too.
 */
@DisabledForCrosscheck("WorkCounter and acceleration policies are SafeRE implementation details")
@Tag("work-counter")
internal class DfaStartAccelerationBackoffTest {
    @Test
    fun patternUsesACharacterClassAccelerator() {
        val pattern = Pattern.compile(REGEX)
        Assertions.assertThat<AcceleratorPolicy?>(pattern.stringStartAccelerator().policy()).isEqualTo(POLICY)
        Assertions.assertThat<AcceleratorPolicy?>(pattern.utf8StartAccelerator().policy()).isEqualTo(POLICY)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun defeatStaysLinearAcrossFindCalls(utf8: Boolean) {
        val input = DENSE_UNIT.repeat(DENSE_COUNT)
        val starts: MutableList<Int?> = ArrayList<Int?>()

        val work =
            WorkCounter.countStartAccelerationForTesting(Runnable { findAll(input, utf8, starts) })

        Assertions.assertThat<Int?>(starts).hasSize(DENSE_COUNT)
        Assertions.assertThat(starts.get(DENSE_COUNT - 1)).isEqualTo((DENSE_COUNT - 1) * DENSE_UNIT.length)
        // Re-learning the defeat in every find() would make one call per match. Carrying the
        // backoff pays the loss limit once and then escalates the quarantine window.
        Assertions.assertThat(work.calls).`as`("%s", work).isLessThan(DENSE_COUNT / 100L)
        Assertions.assertThat(work.largestQuarantine).`as`("%s", work).isEqualTo(POLICY.maxQuarantineWindow)
    }

    @Test
    fun defeatStaysLinearAcrossReplaceAllSearches() {
        // A character-class accelerator has no literal prefix, so replaceAll searches with the DFA's
        // own start acceleration on every match.
        val pattern = Pattern.compile(REGEX)
        val input = DENSE_UNIT.repeat(DENSE_COUNT)
        val result = arrayOfNulls<String>(1)

        val work =
            WorkCounter.countStartAccelerationForTesting(
                Runnable { result[0] = pattern.matcher(input).replaceAll("-") })

        Assertions.assertThat(result[0]).isEqualTo("-".repeat(DENSE_COUNT))
        Assertions.assertThat(work.calls).`as`("%s", work).isLessThan(DENSE_COUNT / 100L)
    }

    @Test
    fun defeatStaysLinearAcrossSplitSearches() {
        val pattern = Pattern.compile(REGEX)
        val input = (DENSE_UNIT + ",").repeat(DENSE_COUNT)
        val result = arrayOfNulls<Array<String?>>(1)

        val work =
            WorkCounter.countStartAccelerationForTesting(Runnable { result[0] = pattern.split(input) })

        Assertions.assertThat<String?>(result[0]).hasSize(DENSE_COUNT + 1).containsOnly("", ",")
        Assertions.assertThat(work.calls).`as`("%s", work).isLessThan(DENSE_COUNT / 100L)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun sparseInputRegainsAccelerationAfterDenseInput(utf8: Boolean) {
        val dense = DENSE_UNIT.repeat(DENSE_COUNT)
        val sparseUnit = "-".repeat(1000) + DENSE_UNIT
        val sparseCount = 1024
        val sparse = sparseUnit.repeat(sparseCount)
        val matcher = Pattern.compile(REGEX).matcher(dense + sparse)
        val utf8Matcher =
            Pattern.compile(REGEX).matcher(Utf8Input.validated((dense + sparse).toByteArray(StandardCharsets.UTF_8)))

        val denseWork =
            WorkCounter.countStartAccelerationForTesting(
                Runnable {
                    for (i in 0..<DENSE_COUNT) {
                        Assertions.assertThat(if (utf8) utf8Matcher.find() else matcher.find()).isTrue()
                    }
                })
        val sparseWork =
            WorkCounter.countStartAccelerationForTesting(
                Runnable {
                    for (i in 0..<sparseCount) {
                        Assertions.assertThat(if (utf8) utf8Matcher.find() else matcher.find()).isTrue()
                    }
                    Assertions.assertThat(if (utf8) utf8Matcher.find() else matcher.find()).isFalse()
                })

        Assertions.assertThat(denseWork.largestQuarantine).isEqualTo(POLICY.maxQuarantineWindow)
        // At most one maximal quarantine window, carried over from the dense input, runs without
        // acceleration. After that, the first long skip repays the deficit and every later call skips
        // the whole gap.
        Assertions.assertThat(sparseWork.skippedUnits)
            .`as`("%s", sparseWork)
            .isGreaterThan((sparse.length - POLICY.maxQuarantineWindow - sparseUnit.length).toLong())
        Assertions.assertThat(sparseWork.quarantines).isZero()
    }

    @Test
    fun resetClearsBackoff() {
        val matcher: Matcher = quarantinedMatcher()

        matcher.reset()

        Assertions.assertThat(findCalls(matcher)).isEqualTo(1)
    }

    @Test
    fun regionClearsBackoff() {
        val matcher: Matcher = quarantinedMatcher()

        matcher.region(matcher.end(), matcher.regionEnd())

        Assertions.assertThat(findCalls(matcher)).isEqualTo(1)
    }

    @Test
    fun usePatternClearsBackoff() {
        val matcher: Matcher = quarantinedMatcher()

        matcher.usePattern(matcher.pattern())

        Assertions.assertThat(findCalls(matcher)).isEqualTo(1)
    }

    companion object {
        private const val REGEX = "[xz][0-9]+y"
        private const val DENSE_UNIT = "x1y"
        private const val DENSE_COUNT = 30000
        private val POLICY: AcceleratorPolicy = AcceleratorPolicy.CHAR_CLASS

        /** Returns a matcher whose next find() is quarantined, so it makes no accelerator call.  */
        private fun quarantinedMatcher(): Matcher {
            val matcher = Pattern.compile(REGEX).matcher(DENSE_UNIT.repeat(2000))
            for (i in 0..99) {
                Assertions.assertThat(matcher.find()).isTrue()
            }
            Assertions.assertThat(findCalls(matcher)).`as`("the carried state quarantines the next find()").isZero()
            return matcher
        }

        private fun findCalls(matcher: Matcher): Long {
            return WorkCounter.countStartAccelerationForTesting(Runnable {
                Assertions.assertThat(matcher.find()).isTrue()
            })
                .calls
        }

        private fun findAll(input: String, utf8: Boolean, starts: MutableList<Int?>) {
            val pattern = Pattern.compile(REGEX)
            if (utf8) {
                val matcher = pattern.matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
                while (matcher.find()) {
                    starts.add(matcher.start())
                }
            } else {
                val matcher = pattern.matcher(input)
                while (matcher.find()) {
                    starts.add(matcher.start())
                }
            }
        }
    }
}
