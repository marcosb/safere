// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import org.openjdk.jmh.annotations.AuxCounters

/** Phase-diagnostic totals; divide each phase's nanos counter by its documents counter.  */
@AuxCounters(AuxCounters.Type.EVENTS)
@State(Scope.Thread)
class AdaptivePhaseCounters {
    var phase0Nanos: Long = 0
    var phase1Nanos: Long = 0
    var phase2Nanos: Long = 0
    var phase0Documents: Long = 0
    var phase1Documents: Long = 0
    var phase2Documents: Long = 0

    /** Starts fresh totals for each JMH iteration.  */
    @Setup(Level.Iteration)
    fun reset() {
        phase0Nanos = 0
        phase1Nanos = 0
        phase2Nanos = 0
        phase0Documents = 0
        phase1Documents = 0
        phase2Documents = 0
    }

    fun record(phase: Int, nanos: Long, documents: Int) {
        when (phase) {
            0 -> {
                phase0Nanos += nanos
                phase0Documents += documents.toLong()
            }

            1 -> {
                phase1Nanos += nanos
                phase1Documents += documents.toLong()
            }

            2 -> {
                phase2Nanos += nanos
                phase2Documents += documents.toLong()
            }

            else -> throw IllegalArgumentException("Expected three adaptation phases")
        }
    }
}
