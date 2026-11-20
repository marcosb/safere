// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.safere.MultiAnchorDescriptor.StartPlan
import java.nio.charset.StandardCharsets

@DisabledForCrosscheck("uses internal work counters to verify acceleration bounds")
@Tag("work-counter")
internal class FixedOffsetClassAccelerationWorkTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun denseInteriorHitsWithNoLeadingCharacterCostOneLeadingScan(utf8: Boolean) {
        val input = "1234567890".repeat(10000)
        for (regex in mutableListOf<String>(" [0-9]{4,}", "[a-z]{2}[0-9]+", "[ \\t\\r\\n]{3}[0-9]+")) {
            val pattern = Pattern.compile(regex)
            val plan = pattern.startPlan() as StartPlan.FixedOffsetClass
            val scanner =
                if (utf8) Utf8InputScanner(input.toByteArray(StandardCharsets.UTF_8)) else StringInputScanner(input)
            val leadingWork =
                WorkCounter.countForTesting({ Assertions.assertThat(scanner.indexOfCharClass(plan.leadingClass, 0)).isEqualTo(-1) })
            val acceleratedWork =
                WorkCounter.countForTesting({
                        val candidate =
                            if (utf8)
                                Utf8StartAccelerator.findNextCandidate(
                                    pattern.utf8StartAccelerator()!!, scanner as Utf8InputScanner, 0
                                )
                            else
                                StringStartAccelerator.findNextCandidate(
                                    pattern.stringStartAccelerator()!!, input, 0, false
                                )
                        Assertions.assertThat(candidate).isEqualTo(-1)
                    })
            Assertions.assertThat(acceleratedWork).`as`(regex).isLessThanOrEqualTo(leadingWork + 8)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun absentInteriorClassRejectsBeforeDfaSearch(utf8: Boolean) {
        val pattern = Pattern.compile(" [0-9]{4,}")
        val input = "words with frequent spaces é😀 ".repeat(2000)
        val work =
            WorkCounter.countStartAccelerationForTesting({ Assertions.assertThat(find(pattern, input, utf8)).isFalse() })
        Assertions.assertThat(work.calls).isZero()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun earlySuccessDoesNotScanTheRemainingInput(utf8: Boolean) {
        val pattern = Pattern.compile(" [0-9]{4,}")
        // Warm engine setup before comparing searches with the same early successful match.
        Assertions.assertThat(find(pattern, " 1234x", utf8)).isTrue()
        val small: Long = successWork(pattern, " 1234x" + " words é😀".repeat(4000), utf8)
        val large: Long = successWork(pattern, " 1234x" + " words é😀".repeat(20000), utf8)
        Assertions.assertThat(small).isPositive()
        Assertions.assertThat(large).isLessThanOrEqualTo(small + 32)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun falseCandidatesAndLongContinuationsRemainLinear(utf8: Boolean) {
        for (regex in mutableListOf<String>(" [0-9]{4,}", " ([0-9]+)Z", "[a-z]{2}([0-9]+)Z")) {
            val pattern = Pattern.compile(regex)
            for (unit in mutableListOf<String>("1111x", " 1x", "aa1234Y", "é😀1111x")) {
                val small: Long = failureWork(pattern, unit!!.repeat(2000) + "Z", utf8)
                val large: Long = failureWork(pattern, unit.repeat(10000) + "Z", utf8)
                Assertions.assertThat(small).`as`("%s on %s", regex, unit).isPositive()
                Assertions.assertThat(large).`as`("%s on %s", regex, unit).isLessThan(small * 6)
            }
        }
    }

    companion object {
        private fun successWork(pattern: Pattern, input: String, utf8: Boolean): Long {
            return WorkCounter.countForTesting({ Assertions.assertThat(find(pattern, input, utf8)).isTrue() })
        }

        private fun failureWork(pattern: Pattern, input: String, utf8: Boolean): Long {
            return WorkCounter.countForTesting({ Assertions.assertThat(find(pattern, input, utf8)).isFalse() })
        }

        private fun find(pattern: Pattern, input: String, utf8: Boolean): Boolean {
            return if (utf8)
                pattern.matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8))).find()
            else
                pattern.matcher(input).find()
        }
    }
}
