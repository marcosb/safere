// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.safere.benchmark.DeclarativeBenchmarkPlan.*
import java.util.*
import java.util.List
import java.util.Set
import java.util.function.*
import java.util.function.Function
import java.util.stream.Collectors

/**
 * Materializes the complete engine/workload compatibility join for every benchmark runner.
 * 
 * 
 * The resulting execution plan is the only workload contract consumed by Java and cross-runtime
 * runners. Every concrete workload/engine pair is represented exactly once as either runnable, with
 * engine-selected syntax and resolved arguments, or excluded with a durable reason.
 */
internal object ResolvedBenchmarkPlan {
    const val VERSION: Int = 1

    private val PORTABLE_OPERATIONS: EnumSet<DeclarativeBenchmarkPlan.Operation?> =
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
            DeclarativeBenchmarkPlan.Operation.SPLIT_LENGTH_SUM,
            DeclarativeBenchmarkPlan.Operation.COMPILE,
            DeclarativeBenchmarkPlan.Operation.COMPILE_AND_FIND,
            DeclarativeBenchmarkPlan.Operation.FIND_GROUP_PRESENT,
            DeclarativeBenchmarkPlan.Operation.FIND_GROUP,
            DeclarativeBenchmarkPlan.Operation.UTF8_CAPTURE_BOUNDS
        )

    private val PORTABLE_MODES: EnumSet<MeasurementMode?> = EnumSet.of<MeasurementMode?>(
        MeasurementMode.AVERAGE_TIME,
        MeasurementMode.COMPILE_ONLY,
        MeasurementMode.RETAINED_MEMORY,
        MeasurementMode.SINGLE_SHOT_COLD_START
    )

    @JvmOverloads
    fun create(normalizedBenchmarkData: JsonObject, engines: MutableList<Engine> = engines()): JsonObject {
        val planData = JsonObject()
        planData.add("schemaVersion", normalizedBenchmarkData.get("schemaVersion").deepCopy())
        planData.add("inputs", normalizedBenchmarkData.getAsJsonArray("inputs").deepCopy())
        planData.add("workloads", normalizedBenchmarkData.getAsJsonArray("workloads").deepCopy())

        val plan: DeclarativeBenchmarkPlan = DeclarativeBenchmarkPlan.Companion.parse(planData)
        val patternProfiles: PatternProfiles =
            PatternProfiles.Companion.parse(normalizedBenchmarkData.get("patternProfiles"))
                .withFallback("re2-cpp", "re2")
        val replacementProfiles: PatternProfiles =
            PatternProfiles.Companion.parse(normalizedBenchmarkData.get("replacementProfiles"))

        validateEngines(engines)
        val result = JsonObject()
        result.addProperty("version", VERSION)
        result.add(
            "engines",
            array<Engine?>(
                engines,
                Function { engine: Engine? ->
                    val json = JsonObject()
                    json.addProperty("id", engine!!.id)
                    json.addProperty("reportEngine", engine.reportEngine)
                    json.addProperty("runner", engine.runner)
                    json
                })
        )

        val workloads = plan.expandedWorkloads()
        plan.validateTrialExclusions(
            workloads,
            engines.stream().map<String?>(Engine::id).collect(Collectors.toCollection(Supplier { LinkedHashSet() }))
        )
        patternProfiles.validateReferences(
            workloads.stream()
                .flatMap<String?>(Function { workload: ExpandedWorkload? -> workload!!.patterns.stream() })
                .collect(Collectors.toSet()),
            "pattern"
        )
        replacementProfiles.validateReferences(
            workloads.stream()
                .map<RecipeValue?>(Function { workload: ExpandedWorkload? -> workload!!.arguments.get("replacement") })
                .filter(Predicate { obj: RecipeValue? -> RecipeString::class.java.isInstance(obj) })
                .map<RecipeString?>(Function { obj: RecipeValue? -> RecipeString::class.java.cast(obj) })
                .map<String?>(RecipeString::value)
                .collect(Collectors.toSet()),
            "replacement"
        )
        val entries = JsonArray()
        for (workload in workloads) {
            for (engine in engines) {
                entries.add(toEntry(workload, engine, patternProfiles, replacementProfiles))
            }
        }
        result.addProperty("workloadCount", workloads.size())
        result.addProperty("engineCount", engines.size())
        result.add("entries", entries)
        return result
    }

    fun engines(): MutableList<Engine> {
        val result: MutableList<Engine?> = ArrayList<Engine?>()
        for (variant in RegexEngineVariant.entries) {
            result.add(
                Engine(
                    variant.id(),
                    variant.reportEngine(),
                    "java",
                    variant.patternProfile(),
                    "java",
                    variant.declaration(),
                    EnumSet.allOf<DeclarativeBenchmarkPlan.Operation?>(DeclarativeBenchmarkPlan.Operation::class.java),
                    EnumSet.allOf<MeasurementMode?>(MeasurementMode::class.java),
                    if (variant === RegexEngineVariant.SAFERE_STRING || variant === RegexEngineVariant.SAFERE_UTF8 || variant === RegexEngineVariant.SAFERE_UTF8_VECTOR || variant === RegexEngineVariant.JDK_STRING)
                        allFlagSets()
                    else
                        if (variant === RegexEngineVariant.RE2J_STRING)
                            RegexEngineVariant.Companion.re2jCompileFlagSets()
                        else
                            Set.of<String?>("0")
                )
            )
        }

        result.add(
            portableEngine(
                "re2_cpp",
                "re2_cpp",
                "cpp",
                "re2-cpp",
                "re2-cpp",
                true,
                true,
                false,
                PORTABLE_OPERATIONS,
                PORTABLE_MODES
            )
        )
        result.add(
            portableEngine(
                "pcre2_jit",
                "pcre2_jit",
                "cpp",
                "pcre2",
                "pcre2",
                false,
                false,
                false,
                withoutReplacement(PORTABLE_OPERATIONS),
                PORTABLE_MODES
            )
        )
        result.add(
            portableEngine(
                "go_regexp",
                "go_regexp",
                "go",
                "re2",
                "go-regexp",
                true,
                true,
                true,
                PORTABLE_OPERATIONS,
                PORTABLE_MODES
            )
        )
        result.add(
            portableEngine(
                "rust_regex",
                "rust_regex",
                "rust",
                "rust-regex",
                "rust-regex",
                true,
                true,
                true,
                PORTABLE_OPERATIONS,
                PORTABLE_MODES
            )
        )

        val dotnetFeatures = portableFeatures(false, true, false)
        dotnetFeatures.remove(DeclarativeBenchmarkPlan.Feature.UTF8_INPUT)
        dotnetFeatures.add(DeclarativeBenchmarkPlan.Feature.FLAGGED_COMPILE)
        dotnetFeatures.add(DeclarativeBenchmarkPlan.Feature.JAVA_CHARACTER_CLASS)
        val dotnetOperations =
            EnumSet.copyOf<DeclarativeBenchmarkPlan.Operation?>(PORTABLE_OPERATIONS)
        dotnetOperations.remove(DeclarativeBenchmarkPlan.Operation.UTF8_CAPTURE_BOUNDS)
        dotnetOperations.add(DeclarativeBenchmarkPlan.Operation.FIND_ROTATING_UTF16)
        dotnetOperations.add(DeclarativeBenchmarkPlan.Operation.COMPILE_AND_FIND_ROTATING_UTF16)
        result.add(
            Engine(
                "dotnet_nonbacktracking",
                "dotnet_nonbacktracking",
                "dotnet",
                "dotnet",
                "dotnet",
                EngineDeclaration(
                    "dotnet_nonbacktracking",
                    DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
                    dotnetFeatures,
                    EnumSet.noneOf<DeclarativeBenchmarkPlan.Flag?>(DeclarativeBenchmarkPlan.Flag::class.java),
                    true
                ),
                dotnetOperations,
                EnumSet.of<MeasurementMode?>(
                    MeasurementMode.AVERAGE_TIME,
                    MeasurementMode.COMPILE_ONLY,
                    MeasurementMode.SINGLE_SHOT_COLD_START
                ),
                Set.of<String?>(
                    "0",
                    "CASE_INSENSITIVE_UNICODE_CASE",
                    "UNICODE_CHARACTER_CLASS",
                    "CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS"
                )
            )
        )
        return List.copyOf<Engine?>(result)
    }

    private fun portableEngine(
        id: String?,
        reportEngine: String?,
        runner: String?,
        patternProfile: String?,
        replacementProfile: String?,
        linearTime: Boolean,
        replacement: Boolean,
        retainedHeap: Boolean,
        operations: EnumSet<DeclarativeBenchmarkPlan.Operation?>?,
        modes: EnumSet<MeasurementMode?>?
    ): Engine {
        return Engine(
            id,
            reportEngine,
            runner,
            patternProfile,
            replacementProfile,
            EngineDeclaration(
                id,
                DeclarativeBenchmarkPlan.InputRepresentation.PREEXISTING_UTF8,
                portableFeatures(linearTime, replacement, retainedHeap),
                EnumSet.noneOf<DeclarativeBenchmarkPlan.Flag?>(DeclarativeBenchmarkPlan.Flag::class.java),
                true
            ),
            operations,
            modes,
            Set.of<String?>("0")
        )
    }

    private fun portableFeatures(
        linearTime: Boolean, replacement: Boolean, retainedHeap: Boolean
    ): EnumSet<DeclarativeBenchmarkPlan.Feature?> {
        val features =
            EnumSet.of<DeclarativeBenchmarkPlan.Feature?>(
                DeclarativeBenchmarkPlan.Feature.FIND,
                DeclarativeBenchmarkPlan.Feature.MATCHES,
                DeclarativeBenchmarkPlan.Feature.LOOKING_AT,
                DeclarativeBenchmarkPlan.Feature.CAPTURE_PARTICIPATION,
                DeclarativeBenchmarkPlan.Feature.CAPTURE_TEXT,
                DeclarativeBenchmarkPlan.Feature.NAMED_GROUPS,
                DeclarativeBenchmarkPlan.Feature.SPLIT,
                DeclarativeBenchmarkPlan.Feature.UTF8_INPUT
            )
        if (linearTime) {
            features.add(DeclarativeBenchmarkPlan.Feature.LINEAR_TIME)
        }
        if (replacement) {
            features.add(DeclarativeBenchmarkPlan.Feature.REPLACE)
            features.add(DeclarativeBenchmarkPlan.Feature.NUMBERED_REPLACEMENT)
            features.add(DeclarativeBenchmarkPlan.Feature.NAMED_REPLACEMENT)
        }
        if (retainedHeap) {
            features.add(DeclarativeBenchmarkPlan.Feature.RETAINED_HEAP)
        }
        return features
    }

    private fun withoutReplacement(
        operations: EnumSet<DeclarativeBenchmarkPlan.Operation?>
    ): EnumSet<DeclarativeBenchmarkPlan.Operation?> {
        val result = EnumSet.copyOf<DeclarativeBenchmarkPlan.Operation?>(operations)
        result.remove(DeclarativeBenchmarkPlan.Operation.REPLACE_FIRST)
        result.remove(DeclarativeBenchmarkPlan.Operation.REPLACE_ALL)
        result.remove(DeclarativeBenchmarkPlan.Operation.REPLACE_ALL_LENGTH_SUM)
        return result
    }

    private fun allFlagSets(): MutableSet<String?> {
        return Set.of<String?>(
            "0",
            "CASE_INSENSITIVE",
            "CASE_INSENSITIVE_UNICODE_CASE",
            "UNICODE_CHARACTER_CLASS",
            "CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS"
        )
    }

    private fun validateEngines(engines: MutableList<Engine>) {
        require(!engines.isEmpty()) { "Execution plan requires at least one engine" }
        val ids: MutableSet<String?> = LinkedHashSet<String?>()
        for (engine in engines) {
            require(ids.add(engine.id)) { "Duplicate execution-plan engine ID: " + engine.id }
            require(engine.id == engine.declaration!!.id) { "Execution-plan engine declaration ID differs from engine ID: " + engine.id }
        }
    }

    private fun toEntry(
        workload: ExpandedWorkload,
        engine: Engine,
        patternProfiles: PatternProfiles,
        replacementProfiles: PatternProfiles
    ): JsonObject {
        val entry = JsonObject()
        entry.addProperty("id", workload.id + "@" + engine.id)
        entry.addProperty("workloadId", workload.id)
        entry.addProperty("engineId", engine.id)
        entry.addProperty("reportEngine", engine.reportEngine)
        entry.addProperty("operation", workload.operation.jsonName())
        entry.add("measurement", measurement(workload.measurement))
        val flagSet = flagSet(workload)

        val exclusion = exclusion(workload, engine, patternProfiles, replacementProfiles)
        if (exclusion != null) {
            entry.addProperty("status", "excluded")
            val reason = JsonObject()
            reason.addProperty("kind", exclusion.kind)
            reason.addProperty("reason", exclusion.reason)
            entry.add("exclusion", reason)
            return entry
        }

        entry.addProperty("status", "runnable")
        entry.add(
            "patterns",
            strings(
                workload.patterns.stream()
                    .map<String?>(Function { pattern: String? ->
                        patternProfiles.select(
                            engine.patternProfile,
                            pattern,
                            flagSet
                        )
                    })
                    .toList()
            )
        )
        entry.add("inputs", strings(workload.inputIds))
        entry.add("arguments", arguments(workload.arguments, engine, replacementProfiles, flagSet))
        entry.add("options", strings(options(flagSet)))
        entry.addProperty("inputRepresentation", engine.declaration!!.inputRepresentation.jsonName())
        entry.addProperty("resultConsumption", workload.resultConsumption.jsonName())
        if (workload.expected != null) {
            val expected = JsonObject()
            expected.addProperty("type", workload.expected.type.jsonName())
            expected.add("value", workload.expected.value())
            entry.add("expected", expected)
        }
        entry.add("lifecycle", lifecycle(workload.lifecycle))
        return entry
    }

    private fun exclusion(
        workload: ExpandedWorkload,
        engine: Engine,
        patternProfiles: PatternProfiles,
        replacementProfiles: PatternProfiles
    ): Exclusion? {
        val trialExclusion = workload.trialExclusion(engine.id)
        if (trialExclusion != null) {
            return Exclusion("explicitTrialExclusion", trialExclusion.reason)
        }
        if (workload.disabledReason != null) {
            return Exclusion("workloadDisabled", workload.disabledReason)
        }
        if (!engine.declaration!!.adapterAvailable) {
            return Exclusion("missingEngineAdapter", "engine adapter is not available")
        }
        if (!engine.operations()!!.contains(workload.operation)) {
            return Exclusion(
                "unsupportedOperation",
                "engine does not implement operation " + workload.operation.jsonName()
            )
        }
        if (!engine.measurementModes()!!.contains(workload.measurement.mode)) {
            return Exclusion(
                "unsupportedMeasurementMode",
                "engine does not implement measurement mode " + workload.measurement.mode.jsonName()
            )
        }
        if ((!workload.inputIds.isEmpty() || engine.runner == "java")
            && !workload.inputRepresentations().contains(engine.declaration.inputRepresentation)
        ) {
            return Exclusion(
                "unsupportedInputRepresentation",
                "workload does not accept " + engine.declaration.inputRepresentation.jsonName()
            )
        }
        val unsupportedFlags = workload.flags()
        unsupportedFlags.removeAll(engine.declaration.supportedFlags())
        if (!unsupportedFlags.isEmpty()) {
            return Exclusion("unsupportedFlag", "engine lacks flags " + flagNames(unsupportedFlags))
        }
        val flagSet = flagSet(workload)
        if (!engine.flagSets!!.contains(flagSet)) {
            return Exclusion("unsupportedOptions", "engine does not support flag set " + flagSet)
        }
        if ((engine.declaration.inputRepresentation
                    == DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING_WITH_TIMED_UTF8_CONVERSION)
            && workload.lifecycle.matcher == MatcherReuse.RETAINED
        ) {
            return Exclusion(
                "unsupportedTimingBoundary",
                "retaining the matcher moves String-to-UTF-8 conversion into setup"
            )
        }
        val missing = workload.requirements()
        missing.removeAll(engine.declaration.features())
        // A cold, unflagged compile needs no engine-specific flag adapter. The native
        // runners implement this case without claiming support for other compile modes.
        if ((workload.measurement.mode
                    == MeasurementMode.SINGLE_SHOT_COLD_START)
            && flagSet == "0"
        ) {
            missing.remove(DeclarativeBenchmarkPlan.Feature.FLAGGED_COMPILE)
        }
        if (!missing.isEmpty()) {
            return Exclusion("unsupportedFeature", "engine lacks " + featureNames(missing))
        }
        for (pattern in workload.patterns) {
            val reason =
                patternProfiles.resolve(engine.patternProfile, pattern, flagSet).unsupportedReason
            if (reason != null) {
                return Exclusion("unsupportedSyntax", reason)
            }
        }
        val replacement = workload.arguments.get("replacement")
        if (replacement != null) {
            val value = (replacement as RecipeString).value
            val reason =
                replacementProfiles
                    .resolve(engine.replacementProfile, value, flagSet)
                    .unsupportedReason
            if (reason != null) {
                return Exclusion("unsupportedSyntax", reason)
            }
        }
        return null
    }

    private fun arguments(
        arguments: MutableMap<String?, RecipeValue?>,
        engine: Engine,
        replacementProfiles: PatternProfiles,
        flagSet: String?
    ): JsonObject {
        val result = JsonObject()
        arguments.forEach(
            BiConsumer { name: String?, value: RecipeValue? ->
                var json = ResolvedBenchmarkPlan.recipeValue(value!!)
                if (name == "replacement") {
                    json =
                        JsonPrimitive(
                            replacementProfiles.select(
                                engine.replacementProfile, json.getAsString(), flagSet
                            )
                        )
                }
                result.add(name, json)
            })
        return result
    }

    private fun recipeValue(value: RecipeValue): JsonElement {
        return when (value) {
            -> JsonPrimitive(string.value)
            -> JsonPrimitive(integer.value)
            -> strings(strings.values)
            -> {
                val result = JsonArray()
                integers.values.forEach(Consumer { number: Int? -> result.add(number) })
                result
            }

            -> throw IllegalStateException(
                "Expanded workload retains unresolved argument axis: " + reference.axis
            )
        }
    }

    private fun flagSet(workload: ExpandedWorkload): String {
        val value = workload.arguments.get("flagSet")
        return if (value == null) "0" else (value as RecipeString).value
    }

    private fun options(flagSet: String): MutableList<String?> {
        return when (flagSet) {
            "0" -> List.of<String?>()
            "CASE_INSENSITIVE" -> List.of<String?>("caseInsensitive")
            "CASE_INSENSITIVE_UNICODE_CASE" -> List.of<String?>("caseInsensitive", "unicodeCase")
            "UNICODE_CHARACTER_CLASS" -> List.of<String?>("unicodeCharacterClass")
            "CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS" -> List.of<String?>(
                "caseInsensitive",
                "unicodeCase",
                "unicodeCharacterClass"
            )

            else -> throw IllegalArgumentException("Unknown benchmark flag set: " + flagSet)
        }
    }

    private fun lifecycle(lifecycle: DeclarativeBenchmarkPlan.MatcherLifecycle): JsonObject {
        val result = JsonObject()
        result.addProperty("matcher", lifecycle.matcher.jsonName())
        val steps = JsonArray()
        for (step in lifecycle.steps) {
            val item = JsonObject()
            item.addProperty("kind", step.kind.jsonName())
            if (step.start != null) {
                item.addProperty("start", step.start)
            }
            if (step.end != null) {
                item.addProperty("end", step.end)
            }
            if (step.enabled != null) {
                item.addProperty("enabled", step.enabled)
            }
            steps.add(item)
        }
        result.add("steps", steps)
        return result
    }

    private fun measurement(measurement: DeclarativeBenchmarkPlan.Measurement): JsonObject {
        val result = JsonObject()
        result.addProperty("mode", measurement.mode.jsonName())
        result.addProperty("timingUnit", measurement.timingUnit.jsonName())
        result.add(
            "constraints",
            names<ExecutionConstraint?>(measurement.constraints(), Function { jsonName() })
        )
        return result
    }

    private fun flagNames(values: MutableSet<DeclarativeBenchmarkPlan.Flag?>): String? {
        return values.stream()
            .map<String?>(Function { jsonName() })
            .sorted()
            .toList()
            .toString()
    }

    private fun featureNames(values: MutableSet<DeclarativeBenchmarkPlan.Feature?>): String? {
        return values.stream()
            .map<String?>(Function { jsonName() })
            .sorted()
            .toList()
            .toString()
    }

    private fun strings(values: Iterable<String?>): JsonArray {
        val result = JsonArray()
        values.forEach(Consumer { string: String? -> result.add(string) })
        return result
    }

    private fun <T> array(values: Iterable<T?>, converter: Function<T?, JsonElement?>): JsonArray {
        val result = JsonArray()
        values.forEach(Consumer { value: T? -> result.add(converter.apply(value)) })
        return result
    }

    private fun <T> names(values: Iterable<T?>, name: Function<T?, String?>): JsonArray {
        return array<T?>(values, Function { value: T? -> JsonPrimitive(name.apply(value)) })
    }

    internal class Engine(
        val id: String?,
        val reportEngine: String?,
        val runner: String?,
        val patternProfile: String?,
        val replacementProfile: String?,
        val declaration: EngineDeclaration?,
        operations: EnumSet<DeclarativeBenchmarkPlan.Operation?>?,
        measurementModes: EnumSet<MeasurementMode?>?,
        flagSets: MutableSet<String?>?
    ) {
        public override fun operations(): EnumSet<DeclarativeBenchmarkPlan.Operation?>? {
            return operations!!.clone()
        }

        public override fun measurementModes(): EnumSet<MeasurementMode?>? {
            return measurementModes!!.clone()
        }

        val operations: EnumSet<DeclarativeBenchmarkPlan.Operation?>?
        val measurementModes: EnumSet<MeasurementMode?>?
        val flagSets: MutableSet<String?>?

        init {
            var operations = operations
            var measurementModes = measurementModes
            var flagSets = flagSets
            operations = operations!!.clone()
            measurementModes = measurementModes!!.clone()
            flagSets = Collections.unmodifiableSet<String?>(LinkedHashSet<String?>(flagSets))
            this.operations = operations
            this.measurementModes = measurementModes
            this.flagSets = flagSets
        }
    }

    @JvmRecord
    private data class Exclusion(val kind: String?, val reason: String?)
}
