// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere


import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmRecord
import kotlin.math.*
/** Package-private deterministic work accounting for performance regression tests.  */
internal object WorkCounter {
    private val COUNTER = ThreadLocal<Counter?>()

    fun countForTesting(task: Runnable): Long {
        return count(task).units
    }

    fun countWithForwardDfaStepsForTesting(task: Runnable): Long {
        val counter = count(task)
        return counter.units + counter.forwardDfaSteps
    }

    fun countForwardDfaStepsForTesting(task: Runnable): Long {
        return count(task).forwardDfaSteps
    }

    fun recordForwardDfaStep() {
        val counter = COUNTER.get()
        if (counter != null) {
            counter.forwardDfaSteps++
        }
    }

    fun countInnerLiteralProbesForTesting(task: Runnable): Long {
        return count(task).innerLiteralProbes
    }

    fun recordInnerLiteralProbe() {
        val counter = COUNTER.get()
        if (counter != null) {
            counter.innerLiteralProbes++
        }
    }

    fun countStartAccelerationForTesting(task: Runnable): StartAccelerationWork {
        val counter = count(task)
        return StartAccelerationWork(
            counter.startScanCalls,
            counter.startSkippedUnits,
            counter.quarantineCount,
            counter.largestQuarantine
        )
    }

    private fun count(task: Runnable): Counter {
        check(WorkCounterConfig.ENABLED) { "WorkCounter is disabled; run tests with -Pwork-counters" }
        val previous = COUNTER.get()
        val current = Counter()
        COUNTER.set(current)
        try {
            task.run()
            return current
        } finally {
            if (previous == null) {
                COUNTER.remove()
            } else {
                COUNTER.set(previous)
            }
        }
    }

    @JvmOverloads
    fun record(units: Long = 1) {
        val counter = COUNTER.get()
        if (counter != null) {
            counter.units += units
        }
    }

    fun recordStartScan(skippedUnits: Int) {
        val counter = COUNTER.get()
        if (counter != null) {
            counter.startScanCalls++
            counter.startSkippedUnits += skippedUnits.toLong()
        }
    }

    fun recordStartQuarantine(window: Int) {
        val counter = COUNTER.get()
        if (counter != null) {
            counter.quarantineCount++
            counter.largestQuarantine = max(counter.largestQuarantine, window)
        }
    }

    private class Counter {
        var units: Long = 0
        var forwardDfaSteps: Long = 0
        var innerLiteralProbes: Long = 0
        var startScanCalls: Long = 0
        var startSkippedUnits: Long = 0
        var quarantineCount: Long = 0
        var largestQuarantine = 0
    }

    @JvmRecord
    internal data class StartAccelerationWork(
        val calls: Long,
        val skippedUnits: Long,
        val quarantines: Long,
        val largestQuarantine: Int
    )
}
