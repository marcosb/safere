// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import org.safere.benchmark.DeclarativeBenchmarkPlan.ExpandedWorkload
import org.safere.benchmark.DeclarativeBenchmarkPlan.MeasurementMode
import java.util.*
import java.util.List
import java.util.function.Function
import java.util.function.Predicate
import java.util.stream.Collectors
import kotlin.Array
import kotlin.Boolean
import kotlin.IllegalArgumentException
import kotlin.String
import kotlin.check
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashMap
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.filter
import kotlin.collections.toList
import kotlin.plus
import kotlin.require
import kotlin.requireNotNull
import kotlin.sequences.filter
import kotlin.sequences.toList
import kotlin.text.filter
import kotlin.text.toList
import kotlin.toList

/** Resolves data-declared workloads that use specialized measurement machinery.  */
internal class SpecializedBenchmarkPlan private constructor(
    trials: MutableMap<String?, Trial?>?,
    exclusions: MutableList<MaterializedExecutionPlan.Entry?>
) {
    private val trials: MutableMap<String?, Trial>
    private val exclusions: MutableList<MaterializedExecutionPlan.Entry?>

    init {
        this.trials = Collections.unmodifiableMap<String?, Trial?>(LinkedHashMap<String?, Trial?>(trials))
        this.exclusions = List.copyOf<MaterializedExecutionPlan.Entry?>(exclusions)
    }

    fun resolve(id: String?): Trial {
        val trial: Trial = trials.get(id)!!
        requireNotNull(trial) { "Unknown specialized benchmark trial: " + id }
        return trial
    }

    fun patternSetTrials(): MutableList<Trial?> {
        return trials.values().stream()
            .filter(
                Predicate { trial: Trial? ->
                    (trial!!.workload!!.operation
                            == DeclarativeBenchmarkPlan.Operation.PATTERN_SET_MATCHES)
                })
            .toList()
    }

    fun averageTimeTrials(): MutableList<Trial?> {
        return trials.values().stream()
            .filter(
                Predicate { trial: Trial? ->
                    (trial!!.workload!!.measurement.mode
                            == MeasurementMode.AVERAGE_TIME)
                })
            .toList()
    }

    fun retainedMemoryTrials(): MutableList<Trial?> {
        return trials.values().stream()
            .filter(
                Predicate { trial: Trial? ->
                    (trial!!.workload!!.measurement.mode
                            == MeasurementMode.RETAINED_MEMORY)
                })
            .toList()
    }

    fun exclusions(): MutableList<MaterializedExecutionPlan.Entry?> {
        return exclusions
    }

    @JvmRecord
    internal data class Trial(val workload: ExpandedWorkload?, val variant: RegexEngineVariant?) {
        fun id(): String {
            return workload!!.id + "@" + variant!!.id()
        }

        init {
            Objects.requireNonNull<ExpandedWorkload?>(workload)
            Objects.requireNonNull<RegexEngineVariant?>(variant)
        }
    }

    companion object {
        fun load(): SpecializedBenchmarkPlan {
            val trials: MutableMap<String?, Trial?> = LinkedHashMap<String?, Trial?>()
            val exclusions: MutableList<MaterializedExecutionPlan.Entry?> =
                ArrayList<MaterializedExecutionPlan.Entry?>()
            for (entry in MaterializedExecutionPlan.load().entriesForRunner("java")) {
                if (!isSpecialized(entry.operation, entry.measurement)) {
                    continue
                }
                if (!entry.runnable()) {
                    exclusions.add(entry)
                    continue
                }
                val converted = Trial(entry.workload, RegexEngineVariant.fromId(entry.engineId))
                require(
                    trials.put(
                        converted.id(),
                        converted
                    ) == null
                ) { "Duplicate specialized trial ID: " + converted.id() }
            }
            return SpecializedBenchmarkPlan(trials, exclusions)
        }

        private fun isSpecialized(
            operation: DeclarativeBenchmarkPlan.Operation,
            measurement: DeclarativeBenchmarkPlan.Measurement
        ): Boolean {
            return when (operation) {
                DeclarativeBenchmarkPlan.Operation.PATTERN_SET_MATCHES, DeclarativeBenchmarkPlan.Operation.UTF8_CAPTURE_BOUNDS, DeclarativeBenchmarkPlan.Operation.UTF8_DECODE_FIND, DeclarativeBenchmarkPlan.Operation.UTF8_REPLACEMENT, DeclarativeBenchmarkPlan.Operation.FIND_IN_WINDOW, DeclarativeBenchmarkPlan.Operation.MATCHER_CONSTRUCTION, DeclarativeBenchmarkPlan.Operation.ANALYZE_PATTERN, DeclarativeBenchmarkPlan.Operation.CACHED_ANALYSIS, DeclarativeBenchmarkPlan.Operation.COMPILE_AND_ANALYZE, DeclarativeBenchmarkPlan.Operation.DIAGNOSTICS_FIND -> true
                else -> false
            }
                    || measurement.mode == MeasurementMode.RETAINED_MEMORY || measurement.mode == MeasurementMode.SUBPROCESS_MEMORY
        }

        @JvmStatic
        fun main(args: Array<String>) {
            require(args.size == 1) { "Usage: SpecializedBenchmarkPlan <average-time|pattern-set|retained-memory>" }
            val plan: SpecializedBenchmarkPlan = load()
            val selected =
                when (args[0]) {
                    "average-time" -> plan.averageTimeTrials()
                    "pattern-set" -> plan.patternSetTrials()
                    "retained-memory" -> plan.retainedMemoryTrials()
                    else -> throw IllegalArgumentException("Unknown specialized mode: " + args[0])
                }
            check(!selected.isEmpty()) { "No specialized trials for " + args[0] }
            System.out.println(
                selected.stream().map<String?>(Function { obj: Trial? -> obj!!.id() }).collect(Collectors.joining(","))
            )
        }
    }
}
