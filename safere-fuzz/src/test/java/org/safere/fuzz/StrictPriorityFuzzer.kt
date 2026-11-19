// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest

/** Strict matching-priority comparisons over the reviewed finite PriorityCases domain.  */
class StrictPriorityFuzzer {
    @FuzzTest(maxDuration = "30s")
    fun priority(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    companion object {
        /** Shared local and standalone entry point.  */
        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            val selected = PriorityCases.choose(data)
            StrictDifferential.check(selected.regex, data.pickValue<String?>(selected.inputs))
        }
    }
}
