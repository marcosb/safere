// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.gson.JsonElement
import org.safere.benchmark.CrossEngineWorkload.TimingGroup
import org.safere.benchmark.DeclarativeBenchmarkPlan.*
import java.util.*
import java.util.List
import java.util.function.Consumer
import java.util.function.Supplier
import java.util.stream.Collectors

/** Expands and validates the declarative ordinary cross-engine workload matrix.  */
internal class CrossEngineBenchmarkPlan private constructor(
    workloads: MutableMap<String?, CrossEngineWorkload?>?,
    trials: MutableMap<String?, Trial?>?,
    exclusions: MutableList<MaterializedExecutionPlan.Entry?>
) {
    private val workloads: MutableMap<String?, CrossEngineWorkload>
    private val trials: MutableMap<String?, Trial?>
    private val exclusions: MutableList<MaterializedExecutionPlan.Entry>

    init {
        this.workloads = Collections.unmodifiableMap<String?, CrossEngineWorkload?>(
            LinkedHashMap<String?, CrossEngineWorkload?>(workloads)
        )
        this.trials = Collections.unmodifiableMap<String?, Trial?>(LinkedHashMap<String?, Trial?>(trials))
        this.exclusions = List.copyOf<MaterializedExecutionPlan.Entry?>(exclusions)
    }

    fun trials(timingGroup: TimingGroup?): MutableList<Trial?> {
        return trials.values.stream()
            .filter { trial: Trial? -> trial!!.workload!!.timingGroup == timingGroup }
            .toList()
    }

    fun trials(
        timingGroup: TimingGroup?,
        mode: MeasurementMode?,
        noFork: Boolean
    ): MutableList<Trial?> {
        return trials(timingGroup).stream()
            .filter { trial: Trial? -> trial!!.workload!!.measurement.mode == mode }
            .filter { trial: Trial? ->
                (trial!!
                    .workload!!
                    .measurement
                    .constraints()
                    .contains(ExecutionConstraint.NO_FORK)
                        == noFork)
            }
            .toList()
    }

    fun workloads(): MutableList<CrossEngineWorkload?> {
        return List.copyOf<CrossEngineWorkload?>(workloads.values)
    }

    fun exclusions(): MutableList<MaterializedExecutionPlan.Entry> {
        return exclusions
    }

    fun resolve(trialId: String): Trial {
        val separator = trialId.lastIndexOf('@')
        require(!(separator <= 0 || separator == trialId.length - 1)) { "Cross-engine trial must be <workload-id>@<variant-id>: " + trialId }
        val trial = trials.get(trialId)
        if (trial != null) {
            return trial
        }
        val workloadId = trialId.substring(0, separator)
        val variantId = trialId.substring(separator + 1)
        val workload: CrossEngineWorkload = workloads.get(workloadId)!!
        requireNotNull(workload) { "Unknown cross-engine workload ID: " + workloadId }
        RegexEngineVariant.Companion.fromId(variantId)
        val exclusion =
            exclusions.stream()
                .filter { candidate: MaterializedExecutionPlan.Entry? ->
                    candidate!!.workloadId == workloadId
                            && candidate.engineId == variantId
                }
                .findFirst()
                .orElseThrow<IllegalStateException?>(
                    Supplier {
                        IllegalStateException(
                            "Unaccounted cross-engine combination: " + workloadId + "@" + variantId
                        )
                    })
        throw IllegalArgumentException(
            ("Unsupported cross-engine combination: "
                    + trialId
                    + " uses "
                    + workload.operation
                    + "; "
                    + exclusion.exclusion.kind
                    + ": "
                    + exclusion.exclusion.reason)
        )
    }

    @JvmRecord
    private data class Query(
        val timingGroup: TimingGroup?,
        val mode: MeasurementMode?,
        val noFork: Boolean
    )

    @JvmRecord
    internal data class Trial(val workload: CrossEngineWorkload?, val variant: RegexEngineVariant?) {
        fun id(): String {
            return workload!!.id + "@" + variant!!.id()
        }

        fun validate(actual: Any?) {
            require(!(workload!!.expected != null && workload.expected != actual)) {
                (workload.id
                        + " "
                        + variant!!.id()
                        + " expected "
                        + workload.expected
                        + " but was "
                        + actual)
            }
        }

        init {
            Objects.requireNonNull<CrossEngineWorkload?>(workload)
            Objects.requireNonNull<RegexEngineVariant?>(variant)
        }
    }

    companion object {
        private val CROSS_ENGINE_OPERATIONS: EnumSet<DeclarativeBenchmarkPlan.Operation?> =
            EnumSet.of<DeclarativeBenchmarkPlan.Operation?>(
                DeclarativeBenchmarkPlan.Operation.MATCHES,
                DeclarativeBenchmarkPlan.Operation.FIND,
                DeclarativeBenchmarkPlan.Operation.LOOKING_AT,
                DeclarativeBenchmarkPlan.Operation.FIND_ALL_COUNT,
                DeclarativeBenchmarkPlan.Operation.MATCHES_CORPUS,
                DeclarativeBenchmarkPlan.Operation.MATCHES_GROUP_LENGTH_SUM,
                DeclarativeBenchmarkPlan.Operation.FIND_ALL_LENGTH_SUM,
                DeclarativeBenchmarkPlan.Operation.FIND_ALL_GROUP_LENGTH_SUM,
                DeclarativeBenchmarkPlan.Operation.CAPTURE_GROUPS,
                DeclarativeBenchmarkPlan.Operation.REPLACE_FIRST,
                DeclarativeBenchmarkPlan.Operation.REPLACE_ALL,
                DeclarativeBenchmarkPlan.Operation.REPLACE_ALL_LENGTH_SUM,
                DeclarativeBenchmarkPlan.Operation.MANUAL_REPLACE_ALL,
                DeclarativeBenchmarkPlan.Operation.SPLIT_LENGTH_SUM,
                DeclarativeBenchmarkPlan.Operation.COMPILE,
                DeclarativeBenchmarkPlan.Operation.COMPILE_AND_FIND,
                DeclarativeBenchmarkPlan.Operation.COMPILE_AND_MATCHES,
                DeclarativeBenchmarkPlan.Operation.FIND_ROTATING_UTF16,
                DeclarativeBenchmarkPlan.Operation.COMPILE_AND_FIND_ROTATING_UTF16,
                DeclarativeBenchmarkPlan.Operation.MATCHER_RESET_FIND,
                DeclarativeBenchmarkPlan.Operation.MATCHER_REGION_FIND,
                DeclarativeBenchmarkPlan.Operation.MATCHER_REGION_MATCHES,
                DeclarativeBenchmarkPlan.Operation.MATCHER_REGION_TOKENIZE,
                DeclarativeBenchmarkPlan.Operation.FIND_SAMPLED_GROUP,
                DeclarativeBenchmarkPlan.Operation.FIND_GROUP_PRESENT,
                DeclarativeBenchmarkPlan.Operation.FIND_GROUP
            )

        fun load(): CrossEngineBenchmarkPlan {
            return fromMaterialized(MaterializedExecutionPlan.Companion.load().entriesForRunner("java"))
        }

        fun fromMaterialized(entries: MutableList<MaterializedExecutionPlan.Entry>): CrossEngineBenchmarkPlan {
            val workloads: MutableMap<String?, CrossEngineWorkload?> = LinkedHashMap<String?, CrossEngineWorkload?>()
            val trials: MutableMap<String?, Trial?> = LinkedHashMap<String?, Trial?>()
            val exclusions: MutableList<MaterializedExecutionPlan.Entry?> =
                ArrayList<MaterializedExecutionPlan.Entry?>()
            for (entry in entries) {
                if (!CROSS_ENGINE_OPERATIONS.contains(entry.operation)
                    || !entry.measurement.timingUnit.isTime()
                ) {
                    continue
                }
                if (!entry.runnable()) {
                    exclusions.add(entry)
                    continue
                }
                val workload: CrossEngineWorkload = convert(entry.workload)
                workloads.putIfAbsent(entry.workloadId, workload)
                val variant: RegexEngineVariant = RegexEngineVariant.Companion.fromId(entry.engineId)
                val converted = Trial(workload, variant)
                require(
                    trials.put(
                        converted.id(),
                        converted
                    ) == null
                ) { "Duplicate cross-engine trial ID: " + converted.id() }
            }
            return CrossEngineBenchmarkPlan(workloads, trials, exclusions)
        }

        @JvmStatic
        fun main(args: Array<String>) {
            val argumentFile = args.size > 0 && args[0] == "--argument-file"
            val queryArgs = if (argumentFile) Arrays.copyOfRange<String?>(args, 1, args.size) else args
            require(queryArgs.size >= 1) {
                ("Usage: CrossEngineBenchmarkPlan [--argument-file] "
                        + "<nanoseconds|microseconds|no-fork-microseconds|cold-start> "
                        + "[workload-prefix ...]")
            }
            val query =
                when (queryArgs[0]) {
                    "nanoseconds" -> Query(TimingGroup.NANOSECONDS, null, false)
                    "microseconds" -> Query(TimingGroup.MICROSECONDS, null, false)
                    "no-fork-microseconds" -> Query(
                        TimingGroup.MICROSECONDS,
                        MeasurementMode.AVERAGE_TIME,
                        true
                    )

                    "cold-start" -> Query(
                        TimingGroup.MILLISECONDS,
                        MeasurementMode.SINGLE_SHOT_COLD_START,
                        false
                    )

                    else -> throw IllegalArgumentException(
                        "Unknown cross-engine timing group: " + queryArgs[0]
                    )
                }
            val plan: CrossEngineBenchmarkPlan = load()
            val trialIds =
                plan.trials(query.timingGroup).stream()
                    .filter { trial: Trial? ->
                        System.getProperty("safere.benchmark.scanProvider") == null
                                || BenchmarkCollectionPlan.Companion.matchesScanProvider(
                            trial!!.id(), System.getProperty("safere.benchmark.scanProvider")
                        )
                    }
                    .filter { trial: Trial? -> query.mode == null || trial!!.workload!!.measurement.mode == query.mode }
                    .filter { trial: Trial? ->
                        (trial!!
                            .workload!!
                            .measurement
                            .constraints()
                            .contains(ExecutionConstraint.NO_FORK)
                                == query.noFork)
                    }
                    .filter { trial: Trial? ->
                        queryArgs.size == 1
                                || Arrays.stream<String?>(queryArgs, 1, queryArgs.size)
                            .anyMatch { prefix: String? -> trial!!.workload!!.id.startsWith(prefix!!) }
                    }
                    .map<String?> { obj: Trial? -> obj!!.id() }
                    .collect(Collectors.joining(","))
            check(!trialIds.isEmpty()) { "No cross-engine trials for " + queryArgs[0] }
            if (argumentFile) {
                val parameter =
                    when (queryArgs[0]) {
                        "nanoseconds" -> "crossEngineTrial"
                        "microseconds" -> "crossEngineScalingTrial"
                        "no-fork-microseconds" -> "crossEngineNoForkTrial"
                        "cold-start" -> "crossEngineColdStartTrial"
                        else -> throw IllegalArgumentException(queryArgs[0])
                    }
                println("-p")
                println(BenchmarkCollectionPlan.Companion.argumentFileToken(parameter + "=" + trialIds))
            } else {
                println(trialIds)
            }
        }

        private fun convert(workload: ExpandedWorkload): CrossEngineWorkload {
            val groups: IntArray? = groups(workload.arguments)
            val replacement: String? = stringArgument(workload.arguments, "replacement")
            val limit: Int = integerArgument(workload.arguments, "limit", 0)
            val timingGroup =
                when (workload.measurement.timingUnit) {
                    TimingUnit.NANOSECONDS -> TimingGroup.NANOSECONDS
                    TimingUnit.MICROSECONDS -> TimingGroup.MICROSECONDS
                    TimingUnit.MILLISECONDS -> TimingGroup.MILLISECONDS
                    else -> throw IllegalArgumentException(
                        workload.id + " is not a time-based cross-engine workload"
                    )
                }
            return CrossEngineWorkload(
                workload.id,
                BenchmarkOperation.Companion.fromDeclarative(workload.operation),
                workload.patterns,
                workload.inputIds,
                groups,
                replacement,
                expectedValue(workload.expected),
                limit,
                workload.lifecycle,
                stringArgument(workload.arguments, "flagSet"),
                integerArgument(workload.arguments, "seed", 0),
                integerArgument(workload.arguments, "count", 0),
                stringArgument(workload.arguments, "mode"),
                workload.measurement,
                timingGroup
            )
        }

        private fun groups(arguments: MutableMap<String?, RecipeValue?>): IntArray? {
            val groups = arguments.get("groups")
            if (groups != null) {
                return (groups as RecipeIntegerList)
                    .values.stream().mapToInt { obj: Int? -> obj!!.toInt() }.toArray()
            }
            val group = arguments.get("group")
            if (group != null) {
                return intArrayOf((group as RecipeInteger).value)
            }
            return IntArray(0)
        }

        private fun stringArgument(
            arguments: MutableMap<String?, RecipeValue?>, name: String?
        ): String? {
            val value = arguments.get(name)
            return if (value == null) null else (value as RecipeString).value
        }

        private fun integerArgument(
            arguments: MutableMap<String?, RecipeValue?>, name: String?, defaultValue: Int
        ): Int {
            val value = arguments.get(name)
            return if (value == null) defaultValue else (value as RecipeInteger).value
        }

        private fun expectedValue(expected: ExpectedResult?): Any? {
            if (expected == null) {
                return null
            }
            val value = expected.value()
            return when (expected.type) {
                ResultType.BOOLEAN -> value!!.getAsBoolean()
                ResultType.INTEGER -> value!!.getAsInt()
                ResultType.STRING -> value!!.getAsString()
                ResultType.STRING_LIST -> {
                    val values: MutableList<String?> = ArrayList<String?>()
                    value!!.getAsJsonArray()
                        .forEach(Consumer { element: JsonElement? -> values.add(element!!.getAsString()) })
                    List.copyOf<String?>(values)
                }
            }
        }
    }
}
