// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest

/** Small syntax, arbitrary UTF-16 subjects, and reviewed stateful operation sequences.  */
class StrictStateFuzzer {
    @FuzzTest(maxDuration = "30s")
    fun state(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    companion object {
        /** Shared local and standalone entry point.  */
        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            val regex = StrictDomain.generate(data).regex
            val input = StrictDomain.input(data)
            val start = data.consumeInt(0, input.size)
            var end = data.consumeInt(start, input.size)
            // Split-surrogate region ends are a documented intentional scalar-quantifier boundary.
            // Interior starts remain included, as do arbitrary lone surrogates.
            if (end > 0 && end < input.size && Character.isHighSurrogate(input.get(end - 1))
                && Character.isLowSurrogate(input.get(end))
            ) {
                end++
            }
            StrictStateChecks.check(regex, input, start, end, data.consumeBoolean(), data.consumeBoolean())
        }
    }
}
