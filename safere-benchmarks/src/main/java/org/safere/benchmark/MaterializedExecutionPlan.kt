// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.safere.benchmark.DeclarativeBenchmarkPlan.*
import java.util.*
import java.util.List
import java.util.Map
import java.util.function.Consumer

/** Typed Java consumer of the common materialized execution-plan artifact.  */
internal class MaterializedExecutionPlan private constructor(
    engines: MutableMap<String?, Engine>?,
    entries: MutableMap<String?, Entry>?
) {
    private val engines: MutableMap<String?, Engine?>
    private val entries: MutableMap<String?, Entry>

    init {
        this.engines = Collections.unmodifiableMap<String?, Engine?>(LinkedHashMap<String?, Engine?>(engines))
        this.entries = Collections.unmodifiableMap<String?, Entry?>(LinkedHashMap<String?, Entry?>(entries))
    }

    fun entries(): MutableList<Entry?> {
        return List.copyOf<Entry?>(entries.values)
    }

    fun entriesForRunner(runner: String?): MutableList<Entry?> {
        val engineIds: MutableSet<String?> = LinkedHashSet<String?>()
        engines.values.stream()
            .filter { engine: Engine? -> engine!!.runner == runner }
            .map<String?>(Engine::id)
            .forEach { e: String? -> engineIds.add(e) }
        return entries.values.stream().filter { entry: Entry? -> engineIds.contains(entry!!.engineId) }.toList()
    }

    fun resolve(id: String?): Entry {
        val entry: Entry = entries.get(id)!!
        requireNotNull(entry) { "Unknown materialized execution ID: " + id }
        return entry
    }

    @JvmRecord
    internal data class Engine(val id: String?, val reportEngine: String?, val runner: String?)

    @JvmRecord
    internal data class Exclusion(val kind: String?, val reason: String?)

    @JvmRecord
    internal data class Entry(
        val id: String?,
        val workloadId: String?,
        val engineId: String?,
        val reportEngine: String?,
        val operation: DeclarativeBenchmarkPlan.Operation?,
        val measurement: DeclarativeBenchmarkPlan.Measurement?,
        val workload: ExpandedWorkload?,
        val exclusion: Exclusion?
    )      {
        fun runnable(): Boolean {
            return workload != null
        }
    }

    companion object {
        fun load(): MaterializedExecutionPlan {
            return parse(BenchmarkData.Companion.get().executionPlan())
        }

        fun parse(json: JsonObject): MaterializedExecutionPlan {
            val version: Int = required(json, "version").getAsInt()
            require(version == ResolvedBenchmarkPlan.VERSION) { "Unsupported execution-plan version: " + version }
            val engines: MutableMap<String?, Engine> = LinkedHashMap<String?, Engine>()
            for (element in required(json, "engines").getAsJsonArray()) {
                val `object` = element.getAsJsonObject()
                val engine =
                    Engine(
                        string(`object`, "id"), string(`object`, "reportEngine"), string(`object`, "runner")
                    )
                require(engines.put(engine.id, engine) == null) { "Duplicate materialized engine ID: " + engine.id }
            }

            val entries: MutableMap<String?, Entry> = LinkedHashMap<String?, Entry>()
            val workloadCounts: MutableMap<String?, Int?> = LinkedHashMap<String?, Int?>()
            for (element in required(json, "entries").getAsJsonArray()) {
                val entry: Entry = parseEntry(element.getAsJsonObject(), engines)
                require(entries.put(entry.id, entry) == null) { "Duplicate materialized execution ID: " + entry.id }
                workloadCounts.merge(entry.workloadId, 1) { a: Int?, b: Int? -> Integer.sum(a!!, b!!) }
            }
            val expectedWorkloads: Int = required(json, "workloadCount").getAsInt()
            val expectedEngines: Int = required(json, "engineCount").getAsInt()
            require(
                !(engines.size != expectedEngines || workloadCounts.size != expectedWorkloads || entries.size != expectedWorkloads * expectedEngines || workloadCounts.values.stream()
                    .anyMatch { count: Int? -> count != expectedEngines })
            ) { "Execution plan does not contain the complete workload/engine join" }
            return MaterializedExecutionPlan(engines, entries)
        }

        private fun parseEntry(`object`: JsonObject, engines: MutableMap<String?, Engine>): Entry {
            val id: String = string(`object`, "id")
            val workloadId: String = string(`object`, "workloadId")
            val engineId: String = string(`object`, "engineId")
            val engine: Engine = engines.get(engineId)!!
            requireNotNull(engine) { id + " references unknown materialized engine " + engineId }
            require(id == workloadId + "@" + engineId) { "Malformed materialized execution ID: " + id }
            require(
                string(
                    `object`,
                    "reportEngine"
                ) == engine.reportEngine
            ) { id + " report engine differs from engine declaration" }
            val status: String = string(`object`, "status")
            val operation: DeclarativeBenchmarkPlan.Operation =
                DeclarativeBenchmarkPlan.Operation.Companion.fromJson(string(`object`, "operation"))
            val measurement: DeclarativeBenchmarkPlan.Measurement =
                parseMeasurement(required(`object`, "measurement").getAsJsonObject())
            return when (status) {
                "runnable" -> Entry(
                    id,
                    workloadId,
                    engineId,
                    engine.reportEngine,
                    operation,
                    measurement,
                    parseWorkload(`object`),
                    null
                )

                "excluded" -> {
                    val exclusion: JsonObject = required(`object`, "exclusion").getAsJsonObject()
                    Entry(
                        id,
                        workloadId,
                        engineId,
                        engine.reportEngine,
                        operation,
                        measurement,
                        null,
                        Exclusion(string(exclusion, "kind"), string(exclusion, "reason"))
                    )
                }

                else -> throw IllegalArgumentException(id + " has unknown status " + status)
            }
        }

        private fun parseWorkload(`object`: JsonObject): ExpandedWorkload {
            val id: String = string(`object`, "workloadId")
            val operation: DeclarativeBenchmarkPlan.Operation =
                DeclarativeBenchmarkPlan.Operation.Companion.fromJson(string(`object`, "operation"))
            val arguments: MutableMap<String?, RecipeValue?> =
                parseArguments(required(`object`, "arguments").getAsJsonObject())
            var expected: ExpectedResult? = null
            if (`object`.has("expected")) {
                val expectedJson = `object`.getAsJsonObject("expected")
                expected =
                    ExpectedResult(
                        ResultType.Companion.fromJson(string(expectedJson, "type")),
                        required(expectedJson, "value").deepCopy(),
                        null
                    )
            }
            val lifecycle: DeclarativeBenchmarkPlan.MatcherLifecycle =
                parseLifecycle(required(`object`, "lifecycle").getAsJsonObject())
            val measurement: DeclarativeBenchmarkPlan.Measurement =
                parseMeasurement(required(`object`, "measurement").getAsJsonObject())
            val representation: DeclarativeBenchmarkPlan.InputRepresentation =
                DeclarativeBenchmarkPlan.InputRepresentation.Companion.fromJson(
                    string(`object`, "inputRepresentation")
                )
            val options =
                EnumSet.noneOf<DeclarativeBenchmarkPlan.Flag?>(DeclarativeBenchmarkPlan.Flag::class.java)
            for (option in strings(`object`, "options")) {
                options.add(DeclarativeBenchmarkPlan.Flag.Companion.fromJson(option))
            }
            return ExpandedWorkload(
                id,
                operation,
                strings(`object`, "patterns"),
                strings(`object`, "inputs"),
                Map.of<String?, ParameterValue?>(),
                arguments,
                options,
                EnumSet.noneOf<DeclarativeBenchmarkPlan.Feature?>(DeclarativeBenchmarkPlan.Feature::class.java),
                EnumSet.of<DeclarativeBenchmarkPlan.InputRepresentation?>(representation),
                null,
                ResultConsumption.Companion.fromJson(string(`object`, "resultConsumption")),
                expected,
                lifecycle,
                measurement,
                null,
                mutableListOf<TrialExclusion?>()
            )
        }

        private fun parseArguments(
            `object`: JsonObject
        ): MutableMap<String?, RecipeValue?> {
            val result: MutableMap<String?, RecipeValue?> = LinkedHashMap<String?, RecipeValue?>()
            for (entry in `object`.entrySet()) {
                val value = entry.value
                val parsed: RecipeValue?
                if (value.isJsonArray()) {
                    val array = value.getAsJsonArray()
                    if (array.isEmpty() || array.get(0).getAsJsonPrimitive().isString()) {
                        val strings: MutableList<String?> = ArrayList<String?>()
                        array.forEach(Consumer { item: JsonElement? -> strings.add(item!!.getAsString()) })
                        parsed = RecipeStringList(strings)
                    } else {
                        val integers: MutableList<Int?> = ArrayList<Int?>()
                        array.forEach(Consumer { item: JsonElement? -> integers.add(item!!.getAsInt()) })
                        parsed = RecipeIntegerList(integers)
                    }
                } else if (value.getAsJsonPrimitive().isString()) {
                    parsed = RecipeString(value.getAsString())
                } else {
                    parsed = RecipeInteger(value.getAsInt())
                }
                result.put(entry.key, parsed)
            }
            return Collections.unmodifiableMap<String?, RecipeValue?>(result)
        }

        private fun parseLifecycle(`object`: JsonObject): DeclarativeBenchmarkPlan.MatcherLifecycle {
            val matcher: MatcherReuse =
                MatcherReuse.Companion.fromJson(string(`object`, "matcher"))
            val steps: MutableList<LifecycleStep?> = ArrayList<LifecycleStep?>()
            for (element in required(`object`, "steps").getAsJsonArray()) {
                val step = element.getAsJsonObject()
                steps.add(
                    LifecycleStep(
                        LifecycleStepKind.Companion.fromJson(string(step, "kind")),
                        optionalInt(step, "start"),
                        optionalInt(step, "end"),
                        optionalBoolean(step, "enabled")
                    )
                )
            }
            return DeclarativeBenchmarkPlan.MatcherLifecycle(matcher, steps)
        }

        private fun parseMeasurement(`object`: JsonObject): DeclarativeBenchmarkPlan.Measurement {
            val constraints =
                EnumSet.noneOf<ExecutionConstraint?>(ExecutionConstraint::class.java)
            for (constraint in strings(`object`, "constraints")) {
                constraints.add(ExecutionConstraint.Companion.fromJson(constraint))
            }
            return DeclarativeBenchmarkPlan.Measurement(
                MeasurementMode.Companion.fromJson(string(`object`, "mode")),
                TimingUnit.Companion.fromJson(string(`object`, "timingUnit")),
                constraints
            )
        }

        private fun required(`object`: JsonObject, name: String?): JsonElement {
            val value = `object`.get(name)
            require(!(value == null || value.isJsonNull())) { "Materialized execution plan requires " + name }
            return value
        }

        private fun string(`object`: JsonObject, name: String?): String {
            return required(`object`, name).getAsString()
        }

        private fun strings(`object`: JsonObject, name: String?): MutableList<String?> {
            val result: MutableList<String?> = ArrayList<String?>()
            required(`object`, name).getAsJsonArray()
                .forEach(Consumer { item: JsonElement? -> result.add(item!!.getAsString()) })
            return List.copyOf<String?>(result)
        }

        private fun optionalInt(`object`: JsonObject, name: String?): Int? {
            return if (`object`.has(name)) `object`.get(name).getAsInt() else null
        }

        private fun optionalBoolean(`object`: JsonObject, name: String?): Boolean? {
            return if (`object`.has(name)) `object`.get(name).getAsBoolean() else null
        }
    }
}
