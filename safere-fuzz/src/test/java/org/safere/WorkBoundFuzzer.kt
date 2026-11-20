// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/** Instrumented, operation-specific work bounds for forced NFA matching.  */
@EnabledIfSystemProperty(named = "safere.fuzz.workCounters", matches = "true")
class WorkBoundFuzzer {
    @FuzzTest(maxDuration = "30s")
    fun work(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    companion object {
        /** Requires a library built with the work-counters Maven profile.  */
        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            check(data.consumeInt(1, 16), data.consumeInt(16, 256), data.consumeBoolean())
        }

        fun check(width: Int, length: Int, unicode: Boolean) {
            val pattern =
                Pattern.compile(
                    "(?:a?){" + width + "}b",
                    0,
                    EnginePathOptions.builder()
                        .literalFastPaths(false)
                        .charClassMatchFastPaths(false)
                        .keywordAlternationFastPath(false)
                        .startAcceleration(false)
                        .shiftDfa(false)
                        .onePass(false)
                        .dfa(false)
                        .bitState(false)
                        .build()
                )
            val input = (if (unicode) "é" else "a").repeat(length)
            val work = WorkCounter.countForTesting({ pattern.matcher(input).matches() })
            // countForTesting already rejects a build without instrumentation. Prefilters can
            // legitimately return without visiting any NFA instructions.
            Assertions.assertThat(work).isNotNegative()
            // A forced NFA visits each instruction a bounded number of times per input position.
            // Budget scales with compiled program size, not just subject size; compilation is excluded.
            val bound = 64L * pattern.prog().size * (input.size + 1L)
            Assertions.assertThat(work).`as`("NFA work versus program/input bound").isLessThanOrEqualTo(bound)
        }
    }
}
