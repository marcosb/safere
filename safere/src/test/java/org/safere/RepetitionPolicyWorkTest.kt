// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/** Progress checks must not turn bounded-repeat fallback into input partition enumeration.  */
@DisabledForCrosscheck("deterministic work accounting and package-private engine controls")
@Tag("work-counter")
internal class RepetitionPolicyWorkTest {
    @Test
    fun nestedFiniteInvocationsKeepLinearWorkAcrossInputLengths() {
        for (bitState in mutableListOf<Boolean>(false, true)) {
            val options =
                EnginePathOptions.builder()
                    .literalFastPaths(false)
                    .charClassMatchFastPaths(false)
                    .keywordAlternationFastPath(false)
                    .startAcceleration(false)
                    .shiftDfa(false)
                    .onePass(false)
                    .dfa(false)
                    .reverseDfa(false)
                    .bitState(bitState!!)
                    .lazyCaptureExtraction(false)
                    .build()
            for (regex in mutableListOf<String>("^(?:(?:()|a){0,3})*b$", "^(?:(a?){1,3})+b$")) {
                val pattern = Pattern.compile(regex, 0, options)
                for (rejects in mutableListOf<Boolean>(false, true)) {
                    for (utf8 in mutableListOf<Boolean>(false, true)) {
                        var previous: Long = 0
                        for (length in mutableListOf<Int>(32, 256, 2048)) {
                            val input = "a".repeat(length!!) + (if (rejects) "!b" else "b")
                            val bytes = Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8))
                            val work =
                                WorkCounter.countForTesting(
                                    Runnable {
                                        if (utf8) {
                                            Assertions.assertThat(pattern.matcher(bytes).matches())
                                                .isEqualTo(!rejects!!)
                                        } else {
                                            Assertions.assertThat(pattern.matcher(input).matches())
                                                .isEqualTo(!rejects!!)
                                        }
                                    })
                            Assertions.assertThat(work).isPositive()
                            if (previous > 0) {
                                Assertions.assertThat(work)
                                    .`as`("/%s/ bitState=%s utf8=%s rejects=%s", regex, bitState, utf8, rejects)
                                    .isLessThanOrEqualTo(previous * 8 * 3)
                            }
                            previous = work
                        }
                    }
                }
            }
        }
    }
}
