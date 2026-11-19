// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.safere.benchmark.CrossEngineWorkload.TimingGroup
import org.safere.benchmark.DeclarativeBenchmarkPlan.ExecutionConstraint
import org.safere.benchmark.DeclarativeBenchmarkPlan.MeasurementMode
import java.util.*
import java.util.List
import java.util.function.Consumer
import java.util.function.Predicate
import java.util.function.Supplier
import java.util.regex.Pattern
import java.util.stream.Collectors
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashSet
import kotlin.collections.MutableList
import kotlin.collections.MutableSet
import kotlin.collections.first
import kotlin.collections.mutableListOf
import kotlin.collections.mutableSetOf

/** Discovers generic benchmark runners, trials, and report identities from the declared plan.  */
internal class BenchmarkCollectionPlan private constructor(
    private val crossEngine: CrossEngineBenchmarkPlan,
    private val specialized: SpecializedBenchmarkPlan,
    private val scanProvider: String?
) {
    fun forScanProvider(provider: String): BenchmarkCollectionPlan {
        require(!(provider != "default" && provider != "vector")) { "Unknown benchmark scan provider: " + provider }
        return BenchmarkCollectionPlan(crossEngine, specialized, provider)
    }

    fun executionRunners(allocation: Boolean, smoke: Boolean): MutableList<Runner> {
        val result: MutableList<Runner?> = ArrayList<Runner?>()
        // Pick the smoke workload before splitting, so both providers measure the same row.
        for (runner in if (allocation) allocationRunners() else runners()) {
            val ids = if (smoke) smokeTrialIds(runner) else runner.trialIds
            for (provider in mutableListOf<String?>("default", "vector")) {
                val selected: MutableList<String> =
                    ids.stream().filter { id: String -> matchesScanProvider(id, provider) }.toList()
                if (!selected.isEmpty()) {
                    result.add(
                        Runner(runner.profile, runner.benchmark, runner.parameter, selected)
                    )
                }
            }
        }
        return List.copyOf<Runner?>(result)
    }

    fun runners(): MutableList<Runner> {
        val allocationOnly = allocationOnlyTrialIds()
        return filterRunners(allRunners(), Predicate { trialId: String? -> !allocationOnly.contains(trialId) }, false)
    }

    fun runnersMatching(benchmarkRegex: String): MutableList<Runner?> {
        val pattern = Pattern.compile(benchmarkRegex)
        return allRunners().stream()
            .filter { runner: Runner? -> pattern.matcher(runner!!.benchmark).find() }
            .toList()
    }

    fun launcherArguments(
        benchmarkRegex: String, benchmarkJar: String, overriddenParameters: MutableSet<String?>
    ): MutableList<String?> {
        return launcherArguments(runnersMatching(benchmarkRegex), benchmarkJar, overriddenParameters)
    }

    fun declaredLauncherArguments(
        benchmark: String?, benchmarkJar: String, allocation: Boolean, smoke: Boolean, provider: String?
    ): MutableList<String?> {
        val selected =
            (if (allocation) allocationRunners() else runners())
                .stream()
                .filter { runner: Runner? -> runner!!.benchmark == benchmark }
                .map<Runner?> { runner: Runner? ->
                    BenchmarkCollectionPlan.Runner(
                        runner!!.profile,
                        runner.benchmark,
                        runner.parameter,
                        (if (smoke) smokeTrialIds(runner) else runner.trialIds)!!
                            .stream()
                            .filter { id: String? -> provider == null || matchesScanProvider(id!!, provider) }
                            .toList())
                }
                .filter { runner: Runner? -> !runner!!.trialIds!!.isEmpty() }
                .toList()
        require(!selected.isEmpty()) { "Unknown declared benchmark runner: " + benchmark }
        require(selected.size <= 1) { "Ambiguous declared benchmark runner: " + benchmark }
        return launcherArguments(selected, benchmarkJar, mutableSetOf<String?>())
    }

    private fun allRunners(): MutableList<Runner?> {
        val runners = unfilteredRunners()
        return if (scanProvider == null)
            runners
        else
            filterRunners(
                runners,
                Predicate { id: String? -> matchesScanProvider(id!!, scanProvider) },
                false
            )
    }

    private fun unfilteredRunners(): MutableList<Runner?> {
        return List.of<Runner?>(
            runner(
                "standard",
                CrossEngineBenchmark::class.java,
                "crossEngineTrial",
                crossEngine.trials(
                    TimingGroup.NANOSECONDS,
                    MeasurementMode.AVERAGE_TIME,
                    false
                )
            ),
            runner(
                "standard",
                CrossEngineScalingBenchmark::class.java,
                "crossEngineScalingTrial",
                crossEngine.trials(TimingGroup.MICROSECONDS).stream()
                    .filter { trial: CrossEngineBenchmarkPlan.Trial? ->
                        !trial!!
                            .workload
                            .measurement
                            .constraints()
                            .contains(ExecutionConstraint.NO_FORK)
                    }
                    .toList()),
            runner(
                "noFork",
                CrossEngineNoForkBenchmark::class.java,
                "crossEngineNoForkTrial",
                crossEngine.trials(
                    TimingGroup.MICROSECONDS,
                    MeasurementMode.AVERAGE_TIME,
                    true
                )
            ),
            runner(
                "coldStart",
                CrossEngineColdStartBenchmark::class.java,
                "crossEngineColdStartTrial",
                crossEngine.trials(
                    TimingGroup.MILLISECONDS,
                    MeasurementMode.SINGLE_SHOT_COLD_START,
                    false
                )
            ),
            Runner(
                "standard",
                SpecializedBenchmark::class.java.getName() + ".run",
                "specializedTrial",
                specialized.averageTimeTrials().stream()
                    .map<String?> { id() }
                    .toList()))
    }

    fun allocationRunners(): MutableList<Runner> {
        val prefixes: MutableList<String?> =
            BenchmarkData.get().getStringList("configuration.collection.allocationWorkloadPrefixes")
        val allocationOnly = allocationOnlyTrialIds()
        return filterRunners(
            allRunners(),
            Predicate { trialId: String? ->
                allocationOnly.contains(trialId)
                        || prefixes.stream()
                    .anyMatch { prefix: String? ->
                        trialId!!.substring(0, trialId.lastIndexOf('@')).startsWith(prefix!!)
                    }
            },
            true
        )
    }

    private fun allocationOnlyTrialIds(): MutableSet<String?> {
        return trials(Query.Companion.ALL).stream()
            .filter { trial: CollectionTrial? ->
                trial!!
                    .measurement!!
                    .constraints()
                    .contains(ExecutionConstraint.ALLOCATION_PROFILE)
            }
            .map<String?>(CollectionTrial::id)
            .collect(Collectors.toUnmodifiableSet())
    }

    fun trials(query: Query): MutableList<CollectionTrial?> {
        val trials: MutableList<CollectionTrial?> = ArrayList<CollectionTrial?>()
        for (group in TimingGroup.entries) {
            for (trial in crossEngine.trials(group)) {
                trials.add(
                    CollectionTrial(
                        trial.id(),
                        trial.workload.id,
                        trial.variant.id(),
                        trial.workload.measurement,
                        trial.variant.scanProvider()
                    )
                )
            }
        }
        for (trial in specialized.averageTimeTrials()) {
            trials.add(
                CollectionTrial(
                    trial.id(),
                    trial.workload.id,
                    trial.variant.id(),
                    trial.workload.measurement,
                    trial.variant.scanProvider()
                )
            )
        }
        return trials.stream().filter { trial: CollectionTrial? -> query.matches(trial!!) }.toList()
    }

    @JvmOverloads
    fun reportPlan(smoke: Boolean = false): ReportPlan {
        val collectedTrialIds =
            runners().stream()
                .flatMap<String?> { runner: Runner? -> (if (smoke) smokeTrialIds(runner!!) else runner!!.trialIds)!!.stream() }
                .collect(Collectors.toUnmodifiableSet())
        val trials =
            trials(Query.Companion.ALL).stream()
                .filter { trial: CollectionTrial? -> collectedTrialIds.contains(trial!!.id) }.toList()
        val workloadIds: MutableSet<String?> =
            trials.stream()
                .map<String?>(CollectionTrial::workloadId)
                .collect(Collectors.toCollection(Supplier { LinkedHashSet() }))
        val exclusions: MutableList<ReportExclusion?> = ArrayList<ReportExclusion?>()
        crossEngine.exclusions().stream()
            .filter { exclusion: MaterializedExecutionPlan.Entry? -> workloadIds.contains(exclusion!!.workloadId) }
            .map<ReportExclusion?> { exclusion: MaterializedExecutionPlan.Entry? ->
                ReportExclusion.from(
                    exclusion!!
                )
            }
            .forEach { e: ReportExclusion? -> exclusions.add(e) }
        specialized.exclusions().stream()
            .filter { exclusion: MaterializedExecutionPlan.Entry? -> workloadIds.contains(exclusion!!.workloadId) }
            .map<ReportExclusion?> { exclusion: MaterializedExecutionPlan.Entry? ->
                ReportExclusion.from(
                    exclusion!!
                )
            }
            .forEach { e: ReportExclusion? -> exclusions.add(e) }
        return ReportPlan(trials, exclusions.stream().distinct().toList())
    }

    @JvmRecord
    internal data class Runner(
        val profile: String?,
        val benchmark: String?,
        val parameter: String?,
        val trialIds: MutableList<String>?
    )

    @JvmRecord
    internal data class CollectionTrial(
        val id: String?,
        val workloadId: String?,
        val executionVariant: String?,
        val measurement: DeclarativeBenchmarkPlan.Measurement?,
        val requestedScanProvider: String?
    )

    @JvmRecord
    internal data class ReportExclusion(
        val workloadId: String?,
        val executionVariant: String?,
        val kind: String?,
        val reason: String?
    ) {
        companion object {
            fun from(exclusion: MaterializedExecutionPlan.Entry): ReportExclusion {
                return ReportExclusion(
                    exclusion.workloadId,
                    exclusion.engineId,
                    exclusion.exclusion.kind,
                    exclusion.exclusion.reason
                )
            }
        }
    }

    @JvmRecord
    internal data class ReportPlan(
        val trials: MutableList<CollectionTrial?>?,
        val exclusions: MutableList<ReportExclusion?>?
    )

    @JvmRecord
    internal data class Query(val mode: String?, val timing: String?, val prefix: String?, val variant: String?) {
        fun matches(trial: CollectionTrial): Boolean {
            return (mode == null || trial.measurement!!.mode.jsonName() == mode)
                    && (timing == null || trial.measurement!!.timingUnit.jsonName() == timing)
                    && (prefix == null || trial.workloadId!!.startsWith(prefix))
                    && (variant == null || trial.executionVariant == variant)
        }

        companion object {
            private val ALL = Query(null, null, null, null)

            fun parse(args: Array<String?>): Query {
                var mode: String? = null
                var timing: String? = null
                var prefix: String? = null
                var variant: String? = null
                var index = 0
                while (index < args.size) {
                    require(index + 1 < args.size) { "Missing value for query option " + args[index] }
                    when (args[index]) {
                        "--mode" -> mode = args[index + 1]
                        "--timing" -> timing = args[index + 1]
                        "--prefix" -> prefix = args[index + 1]
                        "--variant" -> variant = args[index + 1]
                        else -> throw IllegalArgumentException("Unknown query option: " + args[index])
                    }
                    index += 2
                }
                return Query(mode, timing, prefix, variant)
            }
        }
    }

    companion object {
        private val GSON: Gson = GsonBuilder().disableHtmlEscaping().create()

        fun load(): BenchmarkCollectionPlan {
            return BenchmarkCollectionPlan(
                CrossEngineBenchmarkPlan.load(), SpecializedBenchmarkPlan.load(), null
            )
        }

        fun matchesScanProvider(trialId: String, provider: String?): Boolean {
            val variant = trialId.substring(trialId.lastIndexOf('@') + 1)
            return RegexEngineVariant.fromId(variant).scanProvider() == provider
        }

        private fun launcherArguments(
            runners: MutableList<Runner?>, benchmarkJar: String, overriddenParameters: MutableSet<String?>
        ): MutableList<String?> {
            val arguments: MutableList<String?> = ArrayList<String?>()
            arguments.add("-jar")
            arguments.add(argumentFileToken(benchmarkJar))
            runners.stream()
                .filter { runner: Runner? -> !overriddenParameters.contains(runner!!.parameter) }
                .forEach { runner: Runner? ->
                    arguments.add("-p")
                    arguments.add(
                        argumentFileToken(
                            runner!!.parameter + "=" + String.join(",", runner.trialIds)
                        )
                    )
                }
            return List.copyOf<String?>(arguments)
        }

        private fun filterRunners(
            runners: MutableList<Runner?>, includeTrial: Predicate<String?>?, standardOnly: Boolean
        ): MutableList<Runner> {
            return runners.stream()
                .filter { runner: Runner? -> !standardOnly || runner!!.profile == "standard" }
                .map<Runner?> { runner: Runner? ->
                    Runner(
                        runner!!.profile,
                        runner.benchmark,
                        runner.parameter,
                        runner.trialIds!!.stream().filter(includeTrial).toList()
                    )
                }
                .filter { runner: Runner? -> !runner!!.trialIds!!.isEmpty() }
                .toList()
        }

        private fun runner(
            profile: String?,
            runnerClass: Class<*>,
            parameter: String?,
            trials: MutableList<CrossEngineBenchmarkPlan.Trial?>
        ): Runner {
            return Runner(
                profile,
                runnerClass.getName() + ".run",
                parameter,
                trials.stream().map<String?> { id() }.toList()
            )
        }

        @JvmStatic
        fun main(args: Array<String>) {
            require(args.size != 0) {
                ("Usage: BenchmarkCollectionPlan"
                        + " <runners|allocation-runners|runner-arguments|declared-runner-arguments|trials|report-plan>"
                        + " [query options]")
            }
            var plan: BenchmarkCollectionPlan = load()
            val provider = System.getProperty("safere.benchmark.scanProvider")
            if (provider != null && (args[0] != "report-plan") && (args[0] != "declared-runner-arguments")) {
                plan = plan.forScanProvider(provider)
            }
            when (args[0]) {
                "execution-runners", "allocation-execution-runners" -> {
                    val smoke = args.size == 2 && args[1] == "--smoke"
                    require(!(args.size > 2 || (args.size == 2 && !smoke))) { "Usage: " + args[0] + " [--smoke]" }
                    for (runner in plan.executionRunners(args[0].startsWith("allocation-"), smoke)) {
                        val scanProvider: String =
                            RegexEngineVariant.fromId(
                                runner
                                    .trialIds
                                    .first()
                                    .substring(runner.trialIds.first().lastIndexOf('@') + 1)
                            )
                                .scanProvider()
                        System.out.printf(
                            "%s\t%s\t%s\t%s\t%s%n",
                            runner.profile,
                            runner.benchmark,
                            runner.parameter,
                            String.join(",", runner.trialIds),
                            scanProvider
                        )
                    }
                }

                "runners", "allocation-runners" -> {
                    val smoke = args.size == 2 && args[1] == "--smoke"
                    require(!(args.size > 2 || (args.size == 2 && !smoke))) { "Usage: BenchmarkCollectionPlan " + args[0] + " [--smoke]" }
                    val runners =
                        if (args[0] == "runners") plan.runners() else plan.allocationRunners()
                    runners.forEach(
                        Consumer { runner: Runner? ->
                            System.out.printf(
                                "%s\t%s\t%s\t%s%n",
                                runner!!.profile,
                                runner.benchmark,
                                runner.parameter,
                                String.join(",", if (smoke) smokeTrialIds(runner) else runner.trialIds)
                            )
                        })
                }

                "runner-arguments" -> {
                    require(args.size >= 3) {
                        ("Usage: BenchmarkCollectionPlan runner-arguments "
                                + "<benchmark-regex> <benchmark-jar> [parameter ...]")
                    }
                    val overriddenParameters: MutableSet<String?> =
                        LinkedHashSet<String?>(
                            Arrays.asList<String?>(
                                *Arrays.copyOfRange<String?>(
                                    args,
                                    3,
                                    args.size
                                )
                            )
                        )
                    plan.launcherArguments(args[1], args[2], overriddenParameters)
                        .forEach(Consumer { x: String? -> println(x) })
                }

                "declared-runner-arguments" -> {
                    val smoke = args.size == 5 && args[4] == "--smoke"
                    require(
                        !((args.size != 4 && !smoke)
                                || (args[1] != "runners" && args[1] != "allocation-runners"))
                    ) {
                        ("Usage: BenchmarkCollectionPlan declared-runner-arguments "
                                + "<runners|allocation-runners> <benchmark> <benchmark-jar> [--smoke]")
                    }
                    plan.declaredLauncherArguments(
                        args[2], args[3], args[1] == "allocation-runners", smoke, provider
                    )
                        .forEach(Consumer { x: String? -> println(x) })
                }

                "trials" -> {
                    val query: Query = Query.parse(Arrays.copyOfRange<String?>(args, 1, args.size))
                    val selected = plan.trials(query)
                    check(!selected.isEmpty()) { "No declared trials match the query" }
                    println(
                        selected.stream().map<String?>(CollectionTrial::id).collect(Collectors.joining(","))
                    )
                }

                "report-plan" -> {
                    val smoke = args.size == 2 && args[1] == "--smoke"
                    require(!(args.size > 2 || (args.size == 2 && !smoke))) { "Usage: BenchmarkCollectionPlan report-plan [--smoke]" }
                    println(GSON.toJson(plan.reportPlan(smoke)))
                }

                else -> throw IllegalArgumentException("Unknown collection-plan command: " + args[0])
            }
        }

        fun argumentFileToken(argument: String): String {
            return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }

        private fun smokeTrialIds(runner: Runner): MutableList<String?> {
            val firstTrial: String = runner.trialIds.first()
            val firstWorkload = firstTrial.substring(0, firstTrial.lastIndexOf('@'))
            return runner.trialIds!!.stream()
                .filter { trialId: String? -> trialId!!.startsWith(firstWorkload + "@") }
                .toList()
        }
    }
}
