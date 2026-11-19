// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest

/** Checks representation parity and UTF-8 replacement properties without consulting the JDK.  */
class RepresentationPropertyFuzzer {
    @FuzzTest(maxDuration = "30s")
    fun representation(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    companion object {
        /** Shared entry point for local Jazzer and OSS-Fuzz.  */
        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            RepresentationChecks.assertLiteralSearchMatchesString("XXXXXX", "..XXXXXX")
            RepresentationChecks.assertKeywordAlternationMatchesString(data)
            RepresentationChecks.assertFixedOffsetAccelerationMatchesString(data)
            RepresentationChecks.assertGraphemeSearchMatchesString(data)
            RepresentationChecks.assertMultibyteMultiAnchorReverseWindowMatchesString(data)
            RepresentationChecks.assertUtf8ReplacementMatchesAppendLoop(data)
        }
    }
}
