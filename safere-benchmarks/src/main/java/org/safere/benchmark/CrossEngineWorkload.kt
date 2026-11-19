// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import java.util.*
import java.util.List

/** One regex workload executed consistently across multiple engines.  */ // groups is internal benchmark metadata; callers do not use record value semantics.
internal class CrossEngineWorkload(
    val id: String?,
    val operation: BenchmarkOperation?,
    patterns: MutableList<String?>?,
    inputKeys: MutableList<String?>?,
    groups: IntArray?,
    val replacement: String?,
    val expected: Any?,
    val limit: Int,
    val lifecycle: DeclarativeBenchmarkPlan.MatcherLifecycle?,
    val flagSet: String?,
    val seed: Int,
    val count: Int,
    val mode: String?,
    val measurement: DeclarativeBenchmarkPlan.Measurement?,
    val timingGroup: TimingGroup?
) {
    /** Benchmark schedule group, kept separate so historical output units remain stable.  */
    internal enum class TimingGroup {
        NANOSECONDS,
        MICROSECONDS,
        MILLISECONDS
    }

    val patterns: MutableList<String?>?
    val inputKeys: MutableList<String?>?
    val groups: IntArray?

    init {
        var patterns = patterns
        var inputKeys = inputKeys
        var groups = groups
        Objects.requireNonNull<String?>(id)
        Objects.requireNonNull<BenchmarkOperation?>(operation)
        patterns = List.copyOf<String?>(patterns)
        inputKeys = List.copyOf<String?>(inputKeys)
        groups = groups!!.clone()
        Objects.requireNonNull<DeclarativeBenchmarkPlan.MatcherLifecycle?>(lifecycle)
        Objects.requireNonNull<DeclarativeBenchmarkPlan.Measurement?>(measurement)
        Objects.requireNonNull<TimingGroup?>(timingGroup)
        require(!id!!.isBlank()) { "Cross-engine workload ID must not be blank" }
        require(id.indexOf('@') < 0) { "Cross-engine workload ID must not contain '@': " + id }
        require(!patterns.isEmpty()) { id + " requires at least one pattern" }
        require(
            !(inputKeys.isEmpty()
                    && operation != BenchmarkOperation.COMPILE && operation != BenchmarkOperation.FIND_ROTATING_UTF16 && operation != BenchmarkOperation.COMPILE_AND_FIND_ROTATING_UTF16)
        ) { id + " requires at least one input" }
        this.patterns = patterns
        this.inputKeys = inputKeys
        this.groups = groups
    }
}
