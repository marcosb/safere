// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*
import java.util.Set

/** Immutable static analysis of a compiled pattern.  */
@JvmRecord
data class PatternAnalysis(
    val features: MutableSet<PatternFeature?>?,
    val capabilities: MutableSet<PatternCapability?>?,
    val limitations: MutableSet<PatternLimitation?>?,
    val programSize: Int,
    val captureCount: Int
) {

    /** Creates an immutable pattern analysis.  */
    init {
        var features = features
        var capabilities = capabilities
        var limitations = limitations
        features =
            Set.copyOf<PatternFeature?>(Objects.requireNonNull<MutableSet<PatternFeature?>?>(features, "features"))
        capabilities = Set.copyOf<PatternCapability?>(
            Objects.requireNonNull<MutableSet<PatternCapability?>?>(
                capabilities,
                "capabilities"
            )
        )
        limitations = Set.copyOf<PatternLimitation?>(
            Objects.requireNonNull<MutableSet<PatternLimitation?>?>(
                limitations,
                "limitations"
            )
        )
        require(programSize >= 0) { "programSize must be non-negative" }
        require(captureCount >= 0) { "captureCount must be non-negative" }
    }
}
