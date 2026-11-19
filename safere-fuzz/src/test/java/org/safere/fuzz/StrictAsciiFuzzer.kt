// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.junit.jupiter.api.AfterAll

/** Strict ASCII membership and whole-match sequences; see TARGETS.md for the domain.  */
class StrictAsciiFuzzer {
    @FuzzTest(maxDuration = "30s")
    fun strictAscii(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    companion object {
        @AfterAll
        fun reportOracleStats() {
            StrictOracle.report()
        }

        /** Shared entry point for local Jazzer and OSS-Fuzz.  */
        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            val sample = StrictDomain.generate(data)
            StrictDifferential.check(sample.regex, StrictDomain.input(data))
            StrictDifferential.check(sample.regex, sample.witness)
            StrictDifferential.check(sample.regex, sample.witness + "!")
        }
    }
}
