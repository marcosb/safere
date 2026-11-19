// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@DisabledForCrosscheck("Deterministic work checks SafeRE region search kernels")
@Tag("work-counter")
internal class StringRegionWorkTest {
    @Test
    fun unusedRegionCapturesSkipExtractionWork() {
        for (size in mutableListOf<Int?>(256, 4096)) {
            val pattern = Pattern.compile("(a+?)b")
            val body = "a".repeat(size!!) + "b"
            val matcher = pattern.matcher("x" + body + "x").region(1, body.length + 1)
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.capturesResolved()).isFalse()
            val boundsWork =
                WorkCounter.countForTesting(
                    Runnable {
                        Assertions.assertThat(matcher.start()).isEqualTo(1)
                        Assertions.assertThat(matcher.end()).isEqualTo(body.length + 1)
                    })
            Assertions.assertThat(boundsWork).isZero()
            val captureWork =
                WorkCounter.countForTesting(Runnable { Assertions.assertThat(matcher.group(1)).hasSize(size) })
            Assertions.assertThat(captureWork).isPositive()
            Assertions.assertThat(WorkCounter.countForTesting(Runnable { matcher.group(1) })).isZero()
        }
    }

    @Test
    fun regionFindDoesComparableWorkToIndependentText() {
        for (regex in mutableListOf<String?>("q[a-z]+k\\s+b", "[a-z]*q[a-z]+k\\s+b")) {
            val pattern = Pattern.compile(regex)
            for (size in mutableListOf<Int?>(256, 4096)) {
                for (matches in mutableListOf<Boolean?>(false, true)) {
                    val visible = "x ".repeat(size!! / 2) + (if (matches) "qabck b" else "")
                    val input = "q!" + visible + "!qabck b"
                    pattern.matcher(visible).find()
                    pattern.matcher(input).region(2, 2 + visible.length).find()
                    val independent =
                        WorkCounter.countWithForwardDfaStepsForTesting(
                            Runnable { Assertions.assertThat(pattern.matcher(visible).find()).isEqualTo(matches) })
                    val region =
                        WorkCounter.countWithForwardDfaStepsForTesting(
                            Runnable {
                                Assertions.assertThat(pattern.matcher(input).region(2, 2 + visible.length).find())
                                    .isEqualTo(matches)
                            })
                    Assertions.assertThat(region)
                        .`as`(
                            "/%s/ size=%s matches=%s against %s independent units",
                            regex, size, matches, independent
                        )
                        .isLessThanOrEqualTo(2 * independent + 100)
                }
            }
        }
    }

    @Test
    fun terminalMismatchRejectsWithoutScanningTheRegion() {
        val input = "!" + "a".repeat(8192) + "!"
        for (regex in arrayOf<String>("^a+b$", "^a+[0-9]$")) {
            val matcher = Pattern.compile(regex).matcher(input).region(1, input.length - 1)
            val work =
                WorkCounter.countWithForwardDfaStepsForTesting(
                    Runnable { Assertions.assertThat(matcher.matches()).isFalse() })
            Assertions.assertThat(work).`as`(regex).isLessThan(32)
        }
    }

    @Test
    fun foldedNearMissVerificationIsBoundedByRegionSize() {
        val literal = "a".repeat(512) + "b"
        val input = "!" + "a".repeat(8192) + "!"
        val matcher = Pattern.compile("(?i:" + literal + ")").matcher(input)
        matcher.region(1, input.length - 1)
        val work = WorkCounter.countForTesting(Runnable { Assertions.assertThat(matcher.find()).isFalse() })
        Assertions.assertThat(work).isLessThan(10L * (input.length + literal.length))
    }

    @Test
    fun repeatedCharacterClassRegionsKeepTheirDirectScan() {
        for (regex in arrayOf<String>("[a-z]+", "[a-z]*")) {
            val input = "!" + "a".repeat(8192) + "!"
            val matcher = Pattern.compile(regex).matcher(input).region(1, input.length - 1)
            val steps =
                WorkCounter.countForwardDfaStepsForTesting(Runnable {
                    Assertions.assertThat(matcher.matches()).isTrue()
                })
            Assertions.assertThat(steps).isZero()
        }
    }
}
