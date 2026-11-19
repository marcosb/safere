// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

@EnabledIfSystemProperty(named = "safere.fuzz.workCounters", matches = "true")
internal class WorkBoundFuzzerTest {
    @Test
    fun zeroWorkPrefiltersAndNonemptySubjectsRemainWithinTheBound() {
        for (width in intArrayOf(1, 8, 16)) {
            for (length in intArrayOf(0, 16, 32, 256)) {
                WorkBoundFuzzer.Companion.check(width, length, false)
                WorkBoundFuzzer.Companion.check(width, length, true)
            }
        }
    }
}
