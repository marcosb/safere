// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Start-acceleration backoff for a leading-expansion accelerator over a two-member small set.
 * 
 * 
 * For the citation pattern below, the accelerator finds the next `[` or `\uFF3B` and
 * expands the optional leading space backward from it. On dense citations its skips are too short
 * to repay a call, so it must quarantine; on sparse citations every call skips a long gap, so it
 * must keep accelerating.
 */
@DisabledForCrosscheck("WorkCounter and acceleration policies are SafeRE implementation details")
@Tag("work-counter")
internal class LeadingExpansionBackoffTest {
    @Test
    fun patternUsesALeadingExpansionAccelerator() {
        val pattern = Pattern.compile(REGEX)
        Assertions.assertThat<StringStartAccelerator?>(pattern.stringStartAccelerator())
            .isInstanceOf(StringStartAccelerator.LeadingExpansion::class.java)
    }

    @Test
    fun denseCandidatesQuarantine() {
        val input: String = repeatToLength(DENSE_UNIT, LENGTH)
        val starts: MutableList<Int?> = ArrayList<Int?>()

        val work =
            WorkCounter.countStartAccelerationForTesting(Runnable { findAll(input, starts) })

        Assertions.assertThat<Int?>(starts).isEqualTo(jdkStarts(input))
        Assertions.assertThat<Int?>(starts).hasSizeGreaterThan(2000)
        Assertions.assertThat(work.quarantines).`as`("%s", work).isPositive()
        // One call per match would mean the accelerator never backed off.
        Assertions.assertThat(work.calls).`as`("%s", work).isLessThan(starts.size / 8L)
    }

    @Test
    fun sparseCandidatesKeepAccelerating() {
        val input: String = sparse(LENGTH)
        val starts: MutableList<Int?> = ArrayList<Int?>()

        val work =
            WorkCounter.countStartAccelerationForTesting(Runnable { findAll(input, starts) })

        Assertions.assertThat<Int?>(starts).isEqualTo(jdkStarts(input))
        Assertions.assertThat<Int?>(starts).hasSizeGreaterThan(50)
        Assertions.assertThat(work.quarantines).`as`("%s", work).isZero()
        // Every gap between citations is skipped by the accelerator, not stepped by the DFA.
        Assertions.assertThat(work.calls).`as`("%s", work).isEqualTo(starts.size.toLong())
        Assertions.assertThat(work.skippedUnits).`as`("%s", work).isGreaterThan(LENGTH * 9L / 10)
    }

    companion object {
        private const val REGEX = " ?[\\[\uFF3B](?:(?:\\d+\\.){2,}\\d+(?:, )?)+[\\]\uFF3D]"
        private const val LENGTH = 65536

        /** A citation every ~30 characters, with skips that alternate between about 4 and 29.  */
        private const val DENSE_UNIT = "See \uFF3B1.2.3\uFF3D and \uFF3B4.5.6, 7.8.9\uFF3D for version 2.4.1 details. "

        /** One citation per 64 sentences, about 1000 characters apart.  */
        private const val SPARSE_HIT = "这是一个测试句子，参见附录。\uFF3B1.2.3\uFF3D"

        private const val SPARSE_MISS = "这是一个测试句子，参见附录。"

        private fun findAll(input: String?, starts: MutableList<Int?>) {
            val matcher = Pattern.compile(REGEX).matcher(input)
            while (matcher.find()) {
                starts.add(matcher.start())
            }
        }

        private fun jdkStarts(input: String): MutableList<Int?> {
            val starts: MutableList<Int?> = ArrayList<Int?>()
            val matcher = java.util.regex.Pattern.compile(REGEX).matcher(input)
            while (matcher.find()) {
                starts.add(matcher.start())
            }
            return starts
        }

        private fun repeatToLength(unit: String, length: Int): String {
            return unit.repeat(length / unit.length + 1).substring(0, length)
        }

        private fun sparse(length: Int): String {
            val sb = StringBuilder()
            var i = 0
            while (sb.length < length) {
                sb.append(if (i % 64 == 0) SPARSE_HIT else SPARSE_MISS)
                i++
            }
            return sb.substring(0, length)
        }
    }
}
