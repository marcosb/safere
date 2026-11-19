// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.jvm.JvmRecord

/** Immutable static analysis of a compiled pattern.  */
@JvmRecord
data class PatternAnalysis(
    val features: Set<PatternFeature>,
    val capabilities: Set<PatternCapability>,
    val limitations: Set<PatternLimitation>,
    val programSize: Int,
    val captureCount: Int
) {
    /** Creates an immutable pattern analysis.  */
    init {
        // Kotlin @JvmRecord cannot reassign primary-constructor properties the way a Java record
        // compact constructor can. Construction sites pass Set.copyOf(...) so these remain
        // unmodifiable views; requireNonNull preserves the Java null-check contract.
        requireNotNull(features) { "features" }
        requireNotNull(capabilities) { "capabilities" }
        requireNotNull(limitations) { "limitations" }
        require(programSize >= 0) { "programSize must be non-negative" }
        require(captureCount >= 0) { "captureCount must be non-negative" }
    }
}
