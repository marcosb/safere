// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.safere.benchmark.DeclarativeBenchmarkPlan.Companion.requireKnownPlaceholders
import java.util.*
import java.util.List
import java.util.Map
import java.util.Set
import java.util.function.Consumer
import java.util.function.IntFunction
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashMap
import kotlin.collections.LinkedHashSet
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach
import kotlin.collections.mutableListOf
import kotlin.collections.mutableSetOf
import kotlin.collections.toTypedArray
import kotlin.text.StringBuilder
import kotlin.text.contains
import kotlin.text.indexOf
import kotlin.text.isBlank
import kotlin.text.matches
import kotlin.text.toRegex

/**
 * Strict, versioned model for the normalized declarative benchmark plan.
 * 
 * 
 * This model deliberately contains no benchmark-family names. Workload declarations describe
 * required behavior, while engine declarations describe capabilities. [.expand] computes
 * their compatibility join.
 */
internal class DeclarativeBenchmarkPlan private constructor(
    inputs: MutableMap<String?, InputDeclaration?>?,
    workloads: MutableList<WorkloadDeclaration>
) {
    private val inputs: MutableMap<String?, InputDeclaration>
    private val workloads: MutableList<WorkloadDeclaration>

    init {
        this.inputs =
            Collections.unmodifiableMap<String?, InputDeclaration?>(LinkedHashMap<String?, InputDeclaration?>(inputs))
        this.workloads = List.copyOf<WorkloadDeclaration?>(workloads)
    }

    fun inputs(): MutableMap<String?, InputDeclaration> {
        return inputs
    }

    fun workloads(): MutableList<WorkloadDeclaration> {
        return workloads
    }

    fun expandedWorkloads(): MutableList<ExpandedWorkload> {
        return expandWorkloads()
    }

    fun expand(engines: MutableList<EngineDeclaration>?, implementedOperations: MutableSet<Operation?>?): ExpandedPlan {
        Objects.requireNonNull<MutableList<EngineDeclaration?>?>(engines)
        Objects.requireNonNull<MutableSet<Operation?>?>(implementedOperations)
        require(!engines!!.isEmpty()) { "Benchmark plan expansion requires at least one engine" }

        val engineIds: MutableSet<String?> = LinkedHashSet<String?>()
        for (engine in engines) {
            require(engineIds.add(engine.id)) { "Duplicate benchmark engine ID: " + engine.id }
        }

        val expandedWorkloads = expandWorkloads()
        validateTrialExclusions(expandedWorkloads, engineIds)
        val trials: MutableList<Trial> = ArrayList<Trial>()
        val exclusions: MutableList<Exclusion> = ArrayList<Exclusion>()
        for (workload in expandedWorkloads) {
            if (workload.disabledReason != null) {
                for (engine in engines) {
                    exclusions.add(
                        Exclusion(
                            workload.id,
                            engine.id,
                            ExclusionKind.WORKLOAD_DISABLED,
                            workload.disabledReason
                        )
                    )
                }
                continue
            }
            for (engine in engines) {
                val exclusion: Exclusion? = exclusion(workload, engine, implementedOperations!!)
                if (exclusion == null) {
                    trials.add(Trial(workload, engine))
                } else {
                    exclusions.add(exclusion)
                }
            }
        }

        val result =
            ExpandedPlan(expandedWorkloads, List.copyOf<Trial?>(trials), List.copyOf<Exclusion?>(exclusions))
        result.validateComplete(engines.size)
        return result
    }

    private fun expandWorkloads(): MutableList<ExpandedWorkload> {
        val result: MutableList<ExpandedWorkload> = ArrayList<ExpandedWorkload>()
        val ids: MutableSet<String?> = LinkedHashSet<String?>()
        for (workload in workloads) {
            for (parameters in parameterBindings(workload.axes!!)) {
                val id = substitute(workload.idTemplate!!, parameters, "workload ID")
                require(!(id.isBlank() || id.indexOf('@') >= 0)) { "Invalid expanded benchmark workload ID: " + id }
                require(ids.add(id)) { "Duplicate expanded benchmark workload ID: " + id }
                val inputIds =
                    workload.inputTemplates!!.stream()
                        .map<String?> { template: String? -> substitute(template!!, parameters, id + " input") }
                        .toList()
                for (inputId in inputIds) {
                    require(inputs.containsKey(inputId)) { id + " references unknown expanded benchmark input: " + inputId }
                }
                val patterns =
                    workload.patternTemplates!!.stream()
                        .map<String?> { template: String? -> Companion.substitutePattern(template!!, parameters) }
                        .toList()
                val arguments: MutableMap<String?, RecipeValue?> = LinkedHashMap<String?, RecipeValue?>()
                for (argument in workload.arguments!!.entries) {
                    arguments.put(
                        argument.key, argument.value!!.substitute(parameters, id, argument.key)
                    )
                }
                workload.operation!!.validateArguments(id, arguments)
                val expected =
                    if (workload.expected == null) null else workload.expected.substitute(parameters, id)
                result.add(
                    ExpandedWorkload(
                        id,
                        workload.operation,
                        patterns,
                        inputIds,
                        parameters,
                        arguments,
                        workload.flags(),
                        workload.requirements(),
                        workload.inputRepresentations(),
                        workload.inputRepresentationReason,
                        workload.resultConsumption,
                        expected,
                        workload.lifecycle,
                        workload.measurement,
                        workload.disabledReason,
                        workload.trialExclusions!!.stream()
                            .filter { exclusion: TrialExclusion? -> exclusion!!.matches(parameters) }
                            .toList()))
            }
        }
        return List.copyOf<ExpandedWorkload?>(result)
    }

    fun validateTrialExclusions(workloads: MutableList<ExpandedWorkload>?, engineIds: MutableSet<String?>?) {
        Objects.requireNonNull<MutableList<ExpandedWorkload?>?>(workloads)
        Objects.requireNonNull<MutableSet<String?>?>(engineIds)
        for (workload in this.workloads) {
            for (exclusion in workload.trialExclusions!!) {
                for (engineId in exclusion.engineIds!!) {
                    require(engineIds!!.contains(engineId)) {
                        (workload.idTemplate
                                + " trial exclusion references unknown engine ID: "
                                + engineId)
                    }
                }
            }
        }
        for (workload in workloads!!) {
            for (engineId in engineIds!!) {
                workload.trialExclusion(engineId)
            }
        }
    }

    private fun validateReferencesAndIdentities() {
        for (workload in workloads) {
            val axisNames = workload.axes!!.keys
            Companion.requirePlaceholderCoverage(workload.idTemplate!!, axisNames, workload.idTemplate)
            for (inputTemplate in workload.inputTemplates!!) {
                requireKnownPlaceholders(inputTemplate, axisNames, workload.idTemplate + " input")
            }
        }
        val referencedInputs: MutableSet<String?> = LinkedHashSet<String?>()
        for (workload in expandWorkloads()) {
            referencedInputs.addAll(workload.inputIds!!)
        }
        for (input in inputs.values) {
            require(!(!input.shared && !referencedInputs.contains(input.id))) { "Benchmark input is not referenced by a workload and is not shared: " + input.id }
        }
    }

    internal class InputDeclaration(
        val id: String?,
        val recipe: InputRecipe?,
        val shared: Boolean,
        parameters: MutableMap<String?, ParameterValue>?
    ) {
        val parameters: MutableMap<String?, ParameterValue>?

        init {
            var parameters = parameters
            Objects.requireNonNull<String?>(id)
            Objects.requireNonNull<InputRecipe?>(recipe)
            parameters =
                Collections.unmodifiableMap<String?, ParameterValue?>(LinkedHashMap<String?, ParameterValue?>(parameters))
        }
    }

    internal class InputRecipe(val kind: RecipeKind?, arguments: MutableMap<String?, RecipeValue?>?) {
        fun validatePlaceholders(inputId: String?, axes: MutableSet<String>) {
            for (argument in arguments!!.entries) {
                val unknown: MutableSet<String?> = LinkedHashSet<String?>(argument.value!!.placeholders())
                unknown.removeAll(axes)
                require(unknown.isEmpty()) {
                    (inputId
                            + " recipe argument "
                            + argument.key
                            + " references unknown axes: "
                            + unknown)
                }
            }
        }

        fun substitute(parameters: MutableMap<String?, ParameterValue>?, inputId: String?): InputRecipe {
            val resolved: MutableMap<String?, RecipeValue?> = LinkedHashMap<String?, RecipeValue?>()
            for (argument in arguments!!.entries) {
                resolved.put(
                    argument.key,
                    argument.value!!.substitute(parameters, inputId, argument.key)
                )
            }
            return InputRecipe(kind, resolved)
        }

        val arguments: MutableMap<String?, RecipeValue?>?

        init {
            var arguments = arguments
            Objects.requireNonNull<RecipeKind?>(kind)
            arguments =
                Collections.unmodifiableMap<String?, RecipeValue?>(LinkedHashMap<String?, RecipeValue?>(arguments))
            if (arguments.values.stream()
                    .noneMatch { obj: RecipeValue? -> RecipeAxisReference::class.java.isInstance(obj) }
            ) {
                kind!!.validate(arguments)
            }
            this.arguments = arguments
        }
    }

    internal class WorkloadDeclaration(
        val idTemplate: String?,
        val operation: Operation?,
        patternTemplates: MutableList<String>?,
        inputTemplates: MutableList<String>?,
        axes: MutableMap<String?, MutableList<ParameterValue?>>?,
        arguments: MutableMap<String?, RecipeValue?>?,
        flags: EnumSet<Flag?>?,
        requirements: EnumSet<Feature?>?,
        inputRepresentations: EnumSet<InputRepresentation?>?,
        val inputRepresentationReason: String?,
        val resultConsumption: ResultConsumption?,
        val expected: ExpectedResult?,
        val lifecycle: MatcherLifecycle?,
        val measurement: Measurement?,
        val disabledReason: String?,
        trialExclusions: MutableList<TrialExclusion>?
    ) {
        public override fun flags(): EnumSet<Flag?> {
            return flags!!.clone()
        }

        public override fun requirements(): EnumSet<Feature?> {
            return requirements!!.clone()
        }

        public override fun inputRepresentations(): EnumSet<InputRepresentation?> {
            return inputRepresentations!!.clone()
        }

        val patternTemplates: MutableList<String>?
        val inputTemplates: MutableList<String>?
        val axes: MutableMap<String?, MutableList<ParameterValue?>>?
        val arguments: MutableMap<String?, RecipeValue?>?
        val flags: EnumSet<Flag?>?
        val requirements: EnumSet<Feature?>?
        val inputRepresentations: EnumSet<InputRepresentation?>?
        val trialExclusions: MutableList<TrialExclusion>?

        init {
            var patternTemplates = patternTemplates
            var inputTemplates = inputTemplates
            var axes = axes
            var arguments = arguments
            var flags = flags
            var requirements = requirements
            var inputRepresentations = inputRepresentations
            var trialExclusions = trialExclusions
            patternTemplates = List.copyOf<String?>(patternTemplates)
            inputTemplates = List.copyOf<String?>(inputTemplates)
            axes = Collections.unmodifiableMap<String?, MutableList<ParameterValue?>?>(
                LinkedHashMap<String?, MutableList<ParameterValue?>?>(axes)
            )
            arguments =
                Collections.unmodifiableMap<String?, RecipeValue?>(LinkedHashMap<String?, RecipeValue?>(arguments))
            flags = flags!!.clone()
            requirements = requirements!!.clone()
            inputRepresentations = inputRepresentations!!.clone()
            trialExclusions = List.copyOf<TrialExclusion?>(trialExclusions)
        }
    }

    internal class ExpandedWorkload(
        val id: String?,
        val operation: Operation?,
        patterns: MutableList<String>?,
        inputIds: MutableList<String>?,
        parameters: MutableMap<String?, ParameterValue>?,
        arguments: MutableMap<String?, RecipeValue?>?,
        flags: EnumSet<Flag?>?,
        requirements: EnumSet<Feature?>?,
        inputRepresentations: EnumSet<InputRepresentation?>?,
        val inputRepresentationReason: String?,
        val resultConsumption: ResultConsumption?,
        val expected: ExpectedResult?,
        val lifecycle: MatcherLifecycle?,
        val measurement: Measurement?,
        val disabledReason: String?,
        trialExclusions: MutableList<TrialExclusion>?
    ) {
        public override fun flags(): EnumSet<Flag?> {
            return flags!!.clone()
        }

        public override fun requirements(): EnumSet<Feature?> {
            return requirements!!.clone()
        }

        public override fun inputRepresentations(): EnumSet<InputRepresentation?> {
            return inputRepresentations!!.clone()
        }

        fun trialExclusion(engineId: String?): TrialExclusion? {
            var result: TrialExclusion? = null
            for (exclusion in trialExclusions!!) {
                if (!exclusion.engineIds!!.contains(engineId!!)) {
                    continue
                }
                require(result == null) { id + " has overlapping trial exclusions for engine " + engineId }
                result = exclusion
            }
            return result
        }

        val patterns: MutableList<String>?
        val inputIds: MutableList<String>?
        val parameters: MutableMap<String?, ParameterValue>?
        val arguments: MutableMap<String?, RecipeValue?>?
        val flags: EnumSet<Flag?>?
        val requirements: EnumSet<Feature?>?
        val inputRepresentations: EnumSet<InputRepresentation?>?
        val trialExclusions: MutableList<TrialExclusion>?

        init {
            var patterns = patterns
            var inputIds = inputIds
            var parameters = parameters
            var arguments = arguments
            var flags = flags
            var requirements = requirements
            var inputRepresentations = inputRepresentations
            var trialExclusions = trialExclusions
            patterns = List.copyOf<String?>(patterns)
            inputIds = List.copyOf<String?>(inputIds)
            parameters =
                Collections.unmodifiableMap<String?, ParameterValue?>(LinkedHashMap<String?, ParameterValue?>(parameters))
            arguments =
                Collections.unmodifiableMap<String?, RecipeValue?>(LinkedHashMap<String?, RecipeValue?>(arguments))
            flags = flags!!.clone()
            requirements = requirements!!.clone()
            inputRepresentations = inputRepresentations!!.clone()
            trialExclusions = List.copyOf<TrialExclusion?>(trialExclusions)
        }
    }

    internal class TrialExclusion(
        engineIds: MutableList<String>?,
        `when`: MutableMap<String?, MutableSet<ParameterValue?>?>?,
        val reason: String?
    ) {
        fun matches(parameters: MutableMap<String?, ParameterValue>): Boolean {
            return `when`!!.entries.stream()
                .allMatch { selector: MutableMap.MutableEntry<String?, MutableSet<ParameterValue?>?>? ->
                    selector!!.value!!.contains(
                        parameters.get(selector.key)
                    )
                }
        }

        val engineIds: MutableList<String>?
        val `when`: MutableMap<String?, MutableSet<ParameterValue?>?>?

        init {
            var engineIds = engineIds
            var `when` = `when`
            engineIds = List.copyOf<String?>(engineIds)
            val copied: MutableMap<String?, MutableSet<ParameterValue?>?> =
                LinkedHashMap<String?, MutableSet<ParameterValue?>?>()
            `when`!!.forEach { (axis: String?, values: MutableSet<ParameterValue?>?) ->
                copied.put(
                    axis,
                    Collections.unmodifiableSet<ParameterValue?>(LinkedHashSet<ParameterValue?>(values))
                )
            }
            `when` = Collections.unmodifiableMap<String?, MutableSet<ParameterValue?>?>(copied)
            this.engineIds = engineIds
            this.`when` = `when`
        }
    }

    internal class ExpectedResult(val type: ResultType?, value: JsonElement?, val axisReference: String?) {
        public override fun value(): JsonElement? {
            return if (value == null) null else value.deepCopy()
        }

        fun substitute(parameters: MutableMap<String?, ParameterValue>, workloadId: String?): ExpectedResult {
            if (axisReference == null) {
                return this
            }
            val parameter: ParameterValue = parameters.get(axisReference)!!
            requireNotNull(parameter) { workloadId + " expected result references unknown axis: " + axisReference }
            val resolved: JsonElement =
                when (parameter.type) {
                    ParameterType.STRING -> JsonPrimitive(parameter.value as String?)
                    ParameterType.INTEGER -> JsonPrimitive(parameter.value as Int?)
                    ParameterType.BOOLEAN -> JsonPrimitive(parameter.value as Boolean?)
                }
            type!!.validate(resolved)
            return ExpectedResult(type, resolved, null)
        }

        val value: JsonElement?

        init {
            var value = value
            Objects.requireNonNull<ResultType?>(type)
            require((value == null) != (axisReference == null)) { "Expected result requires exactly one value or axis reference" }
            if (value != null) {
                value = value.deepCopy()
            }
            this.value = value
        }
    }

    internal class MatcherLifecycle(val matcher: MatcherReuse?, steps: MutableList<LifecycleStep>?) {
        fun requiredFeatures(): EnumSet<Feature?> {
            val result = EnumSet.noneOf<Feature?>(Feature::class.java)
            if (matcher != MatcherReuse.NONE) {
                result.add(Feature.MATCHER_STATE)
            }
            for (step in steps!!) {
                when (step.kind) {
                    LifecycleStepKind.RESET -> result.add(Feature.MATCHER_STATE)
                    LifecycleStepKind.REGION -> result.add(Feature.REGIONS)
                    LifecycleStepKind.TRANSPARENT_BOUNDS, LifecycleStepKind.ANCHORING_BOUNDS -> result.add(Feature.BOUNDS)
                }
            }
            return result
        }

        val steps: MutableList<LifecycleStep>?

        init {
            var steps = steps
            steps = List.copyOf<LifecycleStep?>(steps)
        }

        companion object {
            val NONE: MatcherLifecycle =
                DeclarativeBenchmarkPlan.MatcherLifecycle(MatcherReuse.NONE, mutableListOf<LifecycleStep?>())
        }
    }

    @JvmRecord
    internal data class LifecycleStep(
        val kind: LifecycleStepKind?,
        val start: Int?,
        val end: Int?,
        val enabled: Boolean?
    )

    internal class Measurement(
        val mode: MeasurementMode?,
        val timingUnit: TimingUnit?,
        constraints: EnumSet<ExecutionConstraint?>?
    ) {
        public override fun constraints(): EnumSet<ExecutionConstraint?> {
            return constraints!!.clone()
        }

        val constraints: EnumSet<ExecutionConstraint?>?

        init {
            var constraints = constraints
            constraints = constraints!!.clone()
        }
    }

    internal class EngineDeclaration(
        val id: String?,
        val inputRepresentation: InputRepresentation?,
        features: EnumSet<Feature?>?,
        supportedFlags: EnumSet<Flag?>?,
        val adapterAvailable: Boolean
    ) {
        public override fun features(): EnumSet<Feature?> {
            return features!!.clone()
        }

        public override fun supportedFlags(): EnumSet<Flag?> {
            return supportedFlags!!.clone()
        }

        val features: EnumSet<Feature?>?
        val supportedFlags: EnumSet<Flag?>?

        init {
            var features = features
            var supportedFlags = supportedFlags
            requireSimpleId(id!!, "benchmark engine")
            Objects.requireNonNull<InputRepresentation?>(
                inputRepresentation
            )
            features = features!!.clone()
            supportedFlags = supportedFlags!!.clone()
        }
    }

    @JvmRecord
    internal data class Trial(val workload: ExpandedWorkload?, val engine: EngineDeclaration?) {
        fun id(): String {
            return workload!!.id + "@" + engine!!.id
        }
    }

    @JvmRecord
    internal data class Exclusion(
        val workloadId: String?,
        val engineId: String?,
        val kind: ExclusionKind?,
        val reason: String?
    )

    internal class ExpandedPlan(
        workloads: MutableList<ExpandedWorkload>?,
        trials: MutableList<Trial>?,
        exclusions: MutableList<Exclusion>?
    ) {
        private fun validateComplete(engineCount: Int) {
            val scheduled: MutableMap<String?, Int?> = LinkedHashMap<String?, Int?>()
            for (trial in trials!!) {
                scheduled.merge(trial.workload!!.id, 1) { a: Int?, b: Int? -> Integer.sum(a!!, b!!) }
            }
            val excluded: MutableMap<String?, Int?> = LinkedHashMap<String?, Int?>()
            for (exclusion in exclusions!!) {
                excluded.merge(exclusion.workloadId, 1) { a: Int?, b: Int? -> Integer.sum(a!!, b!!) }
            }
            for (workload in workloads!!) {
                val accounted =
                    scheduled.getOrDefault(workload.id, 0)!! + excluded.getOrDefault(workload.id, 0)!!
                check(accounted == engineCount) {
                    (workload.id
                            + " has "
                            + accounted
                            + " accounted engine pairs, expected "
                            + engineCount)
                }
                if (scheduled.getOrDefault(workload.id, 0) == 0 && workload.disabledReason == null) {
                    val reasons: MutableList<ExclusionKind?> =
                        exclusions.stream()
                            .filter { exclusion: Exclusion? -> exclusion!!.workloadId == workload.id }
                            .map<ExclusionKind?>(Exclusion::kind)
                            .distinct()
                            .toList()
                    throw IllegalArgumentException(
                        (workload.id
                                + " produces no supported trials and has no disabledReason; exclusions: "
                                + reasons)
                    )
                }
            }
        }

        val workloads: MutableList<ExpandedWorkload>?
        val trials: MutableList<Trial>?
        val exclusions: MutableList<Exclusion>?

        init {
            var workloads = workloads
            var trials = trials
            var exclusions = exclusions
            workloads = List.copyOf<ExpandedWorkload?>(workloads)
            trials = List.copyOf<Trial?>(trials)
            exclusions = List.copyOf<Exclusion?>(exclusions)
        }
    }

    internal interface RecipeValue {
        fun placeholders(): MutableSet<String?>?

        fun substitute(
            parameters: MutableMap<String?, ParameterValue>?, inputId: String?, argumentName: String?
        ): RecipeValue?

        companion object {
            fun parse(
                value: JsonElement, type: RecipeValueType, context: String?, field: String?
            ): RecipeValue {
                try {
                    if (type != RecipeValueType.STRING && value.isJsonPrimitive
                        && value.asJsonPrimitive.isString
                    ) {
                        val reference = value.asString
                        val matcher: Matcher = PLACEHOLDER.matcher(reference)
                        if (matcher.matches()) {
                            return RecipeAxisReference(matcher.group(1), type)
                        }
                    }
                    return when (type) {
                        RecipeValueType.STRING -> {
                            require(isString(value))
                            RecipeString(value.asString)
                        }

                        RecipeValueType.INTEGER -> {
                            require(isIntegerPrimitive(value))
                            RecipeInteger(value.asBigDecimal.intValueExact())
                        }

                        RecipeValueType.STRING_LIST -> {
                            val values: MutableList<String> = ArrayList<String>()
                            value
                                .asJsonArray
                                .forEach(
                                    Consumer { item: JsonElement? ->
                                        require(isString(item!!))
                                        values.add(item.asString)
                                    })
                            RecipeStringList(values)
                        }

                        RecipeValueType.INTEGER_LIST -> {
                            val values: MutableList<Int> = ArrayList<Int>()
                            value
                                .asJsonArray
                                .forEach(
                                    Consumer { item: JsonElement? ->
                                        require(isIntegerPrimitive(item!!))
                                        values.add(item.asBigDecimal.intValueExact())
                                    })
                            RecipeIntegerList(values)
                        }
                    }
                } catch (exception: RuntimeException) {
                    throw IllegalArgumentException(
                        context + " field " + field + " must be " + type.jsonName(), exception
                    )
                }
            }
        }
    }

    @JvmRecord
    internal data class RecipeString(val value: String) : RecipeValue {
        override fun placeholders(): MutableSet<String> {
            return placeholders(value)
        }

        override fun substitute(
            parameters: MutableMap<String?, ParameterValue>, inputId: String?, argumentName: String?
        ): RecipeValue {
            return RecipeString(
                substituteValue(
                    value, parameters, inputId + " recipe " + argumentName
                )
            )
        }
    }

    @JvmRecord
    internal data class RecipeInteger(val value: Int) : RecipeValue {
        override fun placeholders(): MutableSet<String?> {
            return mutableSetOf<String?>()
        }

        override fun substitute(
            parameters: MutableMap<String?, ParameterValue?>?, inputId: String?, argumentName: String?
        ): RecipeValue {
            return this
        }
    }

    internal class RecipeStringList(values: MutableList<String>?) : RecipeValue {
        override fun placeholders(): MutableSet<String?> {
            val result: MutableSet<String?> = LinkedHashSet<String?>()
            values!!.forEach(Consumer { value: String? -> result.addAll(placeholders(value!!)) })
            return result
        }

        override fun substitute(
            parameters: MutableMap<String?, ParameterValue>, inputId: String?, argumentName: String?
        ): RecipeValue {
            return RecipeStringList(
                values!!.stream()
                    .map<String?> { value: String? ->
                        substituteValue(
                            value!!, parameters, inputId + " recipe " + argumentName
                        )
                    }
                    .toList())
        }

        val values: MutableList<String>?

        init {
            var values = values
            values = List.copyOf<String?>(values)
        }
    }

    internal class RecipeIntegerList(values: MutableList<Int>) : RecipeValue {
        override fun placeholders(): MutableSet<String?> {
            return mutableSetOf<String?>()
        }

        override fun substitute(
            parameters: MutableMap<String?, ParameterValue?>?, inputId: String?, argumentName: String?
        ): RecipeValue {
            return this
        }

        val values: MutableList<Int>

        init {
            var values = values
            values = List.copyOf<Int?>(values)
        }
    }

    @JvmRecord
    internal data class RecipeAxisReference(val axis: String?, val type: RecipeValueType?) : RecipeValue {
        override fun placeholders(): MutableSet<String?> {
            return Set.of<String?>(axis)
        }

        override fun substitute(
            parameters: MutableMap<String?, ParameterValue>, inputId: String?, argumentName: String?
        ): RecipeValue {
            val value: ParameterValue = parameters.get(axis)!!
            requireNotNull(value) { inputId + " recipe " + argumentName + " references unknown axis: " + axis }
            return when (type) {
                RecipeValueType.INTEGER -> {
                    require(value.type == ParameterType.INTEGER) { inputId + " recipe " + argumentName + " requires integer axis " + axis }
                    RecipeInteger((value.value as Int?)!!)
                }

                RecipeValueType.STRING -> RecipeString(value.valueText()!!)
                RecipeValueType.STRING_LIST, RecipeValueType.INTEGER_LIST -> throw IllegalArgumentException(
                    inputId + " recipe " + argumentName + " cannot reference a scalar axis"
                )
            }
        }
    }

    @JvmRecord
    internal data class ParameterValue(val type: ParameterType?, val value: Any?, val stableText: String) {
        fun valueText(): String {
            return value.toString()
        }

        companion object {
            fun parse(element: JsonElement, axis: String?): ParameterValue {
                return parse(element, axis, true)
            }

            private fun parse(
                element: JsonElement, axis: String?, validateStableValue: Boolean
            ): ParameterValue {
                if (element.isJsonObject) {
                    val labeled: JsonReader = JsonReader.`object`("benchmark axis value", element)
                    labeled.requireOnly("id", "value")
                    val id = labeled.requiredString("id")
                    validateStableText(id)
                    val value = parse(element.asJsonObject.get("value"), axis, false)
                    return ParameterValue(value.type, value.value, id)
                }
                require(element.isJsonPrimitive) { "Benchmark axis values must be scalars or labeled {id,value} objects: " + axis }
                val primitive = element.asJsonPrimitive
                if (primitive.isBoolean) {
                    val value = primitive.asBoolean
                    return ParameterValue(ParameterType.BOOLEAN, value, value.toString())
                }
                if (primitive.isNumber) {
                    try {
                        val value = primitive.asBigDecimal.intValueExact()
                        return ParameterValue(ParameterType.INTEGER, value, value.toString())
                    } catch (exception: ArithmeticException) {
                        throw IllegalArgumentException(
                            "Benchmark axis numeric values must be integers: " + axis, exception
                        )
                    } catch (exception: NumberFormatException) {
                        throw IllegalArgumentException(
                            "Benchmark axis numeric values must be integers: " + axis, exception
                        )
                    }
                }
                val value = primitive.asString
                if (validateStableValue) {
                    validateStableText(value)
                }
                return ParameterValue(ParameterType.STRING, value, value)
            }

            private fun validateStableText(value: String) {
                require(!(value.isBlank() || value.contains("@") || value.contains("{") || value.contains("}"))) { "Invalid stable benchmark axis value: " + value }
            }
        }
    }

    internal enum class ParameterType {
        STRING,
        INTEGER,
        BOOLEAN
    }

    internal enum class RecipeKind(private val jsonName: String?, vararg fields: RecipeField?) : JsonNamed {
        LITERAL("literal", field("text", RecipeValueType.STRING)),
        FILE("file", field("path", RecipeValueType.STRING), field("sha256", RecipeValueType.STRING)),
        REPEAT(
            "repeat", field("value", RecipeValueType.STRING), field("count", RecipeValueType.INTEGER)
        ),
        REPEAT_TO_LENGTH(
            "repeatToLength",
            field("unit", RecipeValueType.STRING),
            field("length", RecipeValueType.INTEGER)
        ),
        REPEAT_AT_LEAST_LENGTH(
            "repeatAtLeastLength",
            field("unit", RecipeValueType.STRING),
            field("minimumLength", RecipeValueType.INTEGER)
        ),
        DELIMITED_REPEAT_TO_LENGTH(
            "delimitedRepeatToLength",
            field("value", RecipeValueType.STRING),
            field("delimiterAlphabet", RecipeValueType.STRING),
            field("seed", RecipeValueType.INTEGER),
            field("length", RecipeValueType.INTEGER)
        ),
        APPEND_INPUT(
            "appendInput",
            field("input", RecipeValueType.STRING),
            field("suffix", RecipeValueType.STRING)
        ),
        RANDOM_CHARS(
            "randomChars",
            field("alphabet", RecipeValueType.STRING),
            field("length", RecipeValueType.INTEGER),
            field("seed", RecipeValueType.INTEGER)
        ),
        RANDOM_CODE_POINTS(
            "randomCodePoints",
            field("codePoints", RecipeValueType.INTEGER_LIST),
            field("minimumCodeUnits", RecipeValueType.INTEGER),
            field("seed", RecipeValueType.INTEGER)
        ),
        SURROUND_TO_LENGTH(
            "surroundToLength",
            field("prefix", RecipeValueType.STRING),
            field("unit", RecipeValueType.STRING),
            field("suffix", RecipeValueType.STRING),
            field("length", RecipeValueType.INTEGER)
        ),
        SUFFIX_TO_LENGTH(
            "suffixToLength",
            field("prefixUnit", RecipeValueType.STRING),
            field("suffix", RecipeValueType.STRING),
            field("length", RecipeValueType.INTEGER)
        ),
        PREFIXED_REPEAT_TO_LENGTH(
            "prefixedRepeatToLength",
            field("prefix", RecipeValueType.STRING),
            field("value", RecipeValueType.STRING),
            field("delimiterAlphabet", RecipeValueType.STRING),
            field("seed", RecipeValueType.INTEGER),
            field("length", RecipeValueType.INTEGER)
        ),
        SPARSE_MATCH_TO_LENGTH(
            "sparseMatchToLength",
            field("match", RecipeValueType.STRING),
            field("nonMatch", RecipeValueType.STRING),
            field("nonMatchRepeats", RecipeValueType.INTEGER),
            field("delimiterAlphabet", RecipeValueType.STRING),
            field("seed", RecipeValueType.INTEGER),
            field("length", RecipeValueType.INTEGER)
        ),
        CENTER_IN_SPACES(
            "centerInSpaces",
            field("body", RecipeValueType.STRING),
            field("length", RecipeValueType.INTEGER)
        ),
        SCALED_CENTER_IN_SPACES(
            "scaledCenterInSpaces",
            field("bodyPrefix", RecipeValueType.STRING),
            field("bodySuffix", RecipeValueType.STRING),
            field("bodyFill", RecipeValueType.STRING),
            field("bodyScalePercent", RecipeValueType.INTEGER),
            field("length", RecipeValueType.INTEGER)
        ),
        LAZY_ALTERNATION_TO_LENGTH(
            "lazyAlternationToLength",
            field("prefixUnit", RecipeValueType.STRING),
            field("match", RecipeValueType.STRING),
            field("suffixUnit", RecipeValueType.STRING),
            field("length", RecipeValueType.INTEGER)
        ),
        PERIODIC_ALTERNATION_TO_LENGTH(
            "periodicAlternationToLength",
            field("hitUnit", RecipeValueType.STRING),
            field("missUnit", RecipeValueType.STRING),
            field("hitInterval", RecipeValueType.INTEGER),
            field("length", RecipeValueType.INTEGER)
        ),
        OPTIONAL_REQUIRED_REPEAT_PATTERN(
            "optionalRequiredRepeatPattern",
            field("literal", RecipeValueType.STRING),
            field("count", RecipeValueType.INTEGER)
        );

        private val fields: MutableList<RecipeField>

        init {
            this.fields = List.of<RecipeField?>(*fields)
        }

        override fun jsonName(): String? {
            return jsonName
        }

        fun fields(): MutableList<RecipeField> {
            return fields
        }

        fun allowedFields(): Array<String?>? {
            val names: MutableList<String?> = ArrayList<String?>()
            names.add("kind")
            fields.forEach(Consumer { field: RecipeField? -> names.add(field!!.name) })
            return names.toTypedArray()
        }

        fun validate(arguments: MutableMap<String?, RecipeValue?>) {
            when (this) {
                LITERAL -> {}
                FILE -> {
                    requireNonEmpty(arguments, "path")
                    require(
                        string(
                            arguments,
                            "sha256"
                        ).matches("[0-9a-f]{64}".toRegex())
                    ) { "file recipe requires a lowercase SHA-256 digest" }
                }

                REPEAT -> requireNonNegative(arguments, "count")
                REPEAT_TO_LENGTH -> {
                    requireNonNegative(arguments, "length")
                    requireUnitWhenOutputIsRequired(arguments, "unit", "length", 0)
                }

                REPEAT_AT_LEAST_LENGTH -> {
                    requireNonNegative(arguments, "minimumLength")
                    requireUnitWhenOutputIsRequired(arguments, "unit", "minimumLength", 0)
                }

                DELIMITED_REPEAT_TO_LENGTH -> {
                    requireNonNegative(arguments, "length")
                    requireNonEmpty(arguments, "value")
                    requireNonEmpty(arguments, "delimiterAlphabet")
                }

                APPEND_INPUT -> requireNonEmpty(arguments, "input")
                RANDOM_CHARS -> {
                    requireNonNegative(arguments, "length")
                    requireNonEmpty(arguments, "alphabet")
                }

                RANDOM_CODE_POINTS -> {
                    requireNonNegative(arguments, "minimumCodeUnits")
                    val codePoints = integers(arguments, "codePoints")
                    require(
                        !(codePoints.isEmpty()
                                || codePoints.stream()
                            .anyMatch { codePoint: Int? ->
                                !Character.isValidCodePoint(codePoint!!)
                                        || (codePoint >= Character.MIN_SURROGATE.code
                                        && codePoint <= Character.MAX_SURROGATE.code)
                            })
                    ) { "randomCodePoints recipe requires valid Unicode scalar codePoints" }
                }

                SURROUND_TO_LENGTH -> {
                    requireNonNegative(arguments, "length")
                    val fixedLength =
                        string(arguments, "prefix").length + string(arguments, "suffix").length
                    requireUnitWhenOutputIsRequired(arguments, "unit", "length", fixedLength)
                }

                SUFFIX_TO_LENGTH -> {
                    requireNonNegative(arguments, "length")
                    requireUnitWhenOutputIsRequired(
                        arguments, "prefixUnit", "length", string(arguments, "suffix").length
                    )
                }

                PREFIXED_REPEAT_TO_LENGTH -> {
                    requireNonNegative(arguments, "length")
                    requireNonEmpty(arguments, "value")
                    requireNonEmpty(arguments, "delimiterAlphabet")
                }

                SPARSE_MATCH_TO_LENGTH -> {
                    requireNonNegative(arguments, "length")
                    requireNonEmpty(arguments, "match")
                    requireNonEmpty(arguments, "nonMatch")
                    requirePositive(arguments, "nonMatchRepeats")
                    requireNonEmpty(arguments, "delimiterAlphabet")
                }

                CENTER_IN_SPACES -> requireNonNegative(arguments, "length")
                SCALED_CENTER_IN_SPACES -> {
                    requireNonNegative(arguments, "length")
                    requireNonEmpty(arguments, "bodyFill")
                    val percent = integer(arguments, "bodyScalePercent")
                    require(!(percent < 0 || percent > 100)) { "scaledCenterInSpaces recipe bodyScalePercent must be between 0 and 100" }
                }

                LAZY_ALTERNATION_TO_LENGTH -> {
                    requireNonNegative(arguments, "length")
                    requireNonEmpty(arguments, "prefixUnit")
                    requireNonEmpty(arguments, "match")
                    requireNonEmpty(arguments, "suffixUnit")
                }

                PERIODIC_ALTERNATION_TO_LENGTH -> {
                    requireNonNegative(arguments, "length")
                    requireNonEmpty(arguments, "hitUnit")
                    requireNonEmpty(arguments, "missUnit")
                    requirePositive(arguments, "hitInterval")
                }

                OPTIONAL_REQUIRED_REPEAT_PATTERN -> {
                    requireNonEmpty(arguments, "literal")
                    requireNonNegative(arguments, "count")
                }
            }
        }

        companion object {
            private fun requireUnitWhenOutputIsRequired(
                arguments: MutableMap<String?, RecipeValue?>, unitField: String?, lengthField: String?, fixedLength: Int
            ) {
                if (integer(arguments, lengthField) > fixedLength) {
                    requireNonEmpty(arguments, unitField)
                }
            }

            private fun requireNonEmpty(arguments: MutableMap<String?, RecipeValue?>, field: String?) {
                require(!string(arguments, field).isEmpty()) { "Recipe field " + field + " must not be empty" }
            }

            private fun requireNonNegative(arguments: MutableMap<String?, RecipeValue?>, field: String?) {
                require(integer(arguments, field) >= 0) { "Recipe field " + field + " must not be negative" }
            }

            private fun requirePositive(arguments: MutableMap<String?, RecipeValue?>, field: String?) {
                require(integer(arguments, field) > 0) { "Recipe field " + field + " must be positive" }
            }

            private fun string(arguments: MutableMap<String?, RecipeValue?>, field: String?): String {
                return (arguments.get(field) as RecipeString).value
            }

            private fun integer(arguments: MutableMap<String?, RecipeValue?>, field: String?): Int {
                return (arguments.get(field) as RecipeInteger).value
            }

            private fun integers(arguments: MutableMap<String?, RecipeValue?>, field: String?): MutableList<Int> {
                return (arguments.get(field) as RecipeIntegerList).values
            }

            fun fromJson(name: String?): RecipeKind {
                return Companion.enumFromJson<RecipeKind>(
                    RecipeKind.entries.toTypedArray(),
                    name,
                    "input recipe kind"
                )!!
            }
        }
    }

    @JvmRecord
    internal data class RecipeField(val name: String?, val type: RecipeValueType?, val required: Boolean)

    internal enum class RecipeValueType(jsonName: String) : JsonNamed {
        STRING("a string"),
        INTEGER("an integer"),
        STRING_LIST("an array of strings"),
        INTEGER_LIST("an array of integers");

        private val jsonName: String?


        override fun jsonName(): String? {
            return jsonName
        }
    }

    internal enum class Operation(
        private val jsonName: String?,
        private val lifecycleRequired: Boolean,
        private val inputRequired: Boolean,
        vararg requiredFeatures: Feature?
    ) : JsonNamed {
        MATCHES("matches", false, true, Feature.MATCHES),
        FIND("find", false, true, Feature.FIND),
        LOOKING_AT("lookingAt", true, true, Feature.LOOKING_AT),
        FIND_ALL_COUNT("findAllCount", false, true, Feature.FIND),
        FIND_ALL_LENGTH_SUM("findAllLengthSum", false, true, Feature.FIND, Feature.CAPTURE_TEXT),
        FIND_ALL_GROUP_LENGTH_SUM(
            "findAllGroupLengthSum", false, true, Feature.FIND, Feature.CAPTURE_TEXT
        ),
        MATCHES_CORPUS("matchesCorpus", false, true, Feature.MATCHES),
        MATCHES_GROUP_LENGTH_SUM(
            "matchesGroupLengthSum", false, true, Feature.MATCHES, Feature.CAPTURE_TEXT
        ),
        FIND_SAMPLED_GROUP(
            "findSampledGroup",
            true,
            true,
            Feature.FIND,
            Feature.CAPTURE_PARTICIPATION,
            Feature.MATCHER_STATE
        ),
        FIND_GROUP_PRESENT(
            "findGroupPresent", false, true, Feature.FIND, Feature.CAPTURE_PARTICIPATION
        ),
        FIND_GROUP("findGroup", false, true, Feature.FIND, Feature.CAPTURE_TEXT),
        CAPTURE_GROUPS("captureGroups", false, true, Feature.MATCHES, Feature.CAPTURE_TEXT),
        REPLACE_FIRST("replaceFirst", false, true, Feature.REPLACE),
        REPLACE_ALL("replaceAll", false, true, Feature.REPLACE),
        REPLACE_ALL_LENGTH_SUM("replaceAllLengthSum", false, true, Feature.REPLACE),
        APPEND_REPLACEMENT("appendReplacement", false, true, Feature.REPLACE),
        MANUAL_REPLACE_ALL(
            "manualReplaceAll", false, true, Feature.REPLACE, Feature.APPEND_REPLACEMENT
        ),
        SPLIT("split", false, true, Feature.SPLIT),
        SPLIT_LENGTH_SUM("splitLengthSum", false, true, Feature.SPLIT),
        COMPILE("compile", false, false),
        COMPILE_AND_FIND("compileAndFind", false, true, Feature.FIND),
        COMPILE_AND_MATCHES("compileAndMatches", false, true, Feature.MATCHES),
        FIND_ROTATING_UTF16("findRotatingUtf16", false, false, Feature.FIND),
        COMPILE_AND_FIND_ROTATING_UTF16("compileAndFindRotatingUtf16", false, false, Feature.FIND),
        MATCHER_CONSTRUCTION("matcherConstruction", false, true, Feature.UTF8_INPUT),
        MATCHER_RESET_FIND("matcherResetFind", true, true, Feature.FIND, Feature.MATCHER_STATE),
        MATCHER_REGION_FIND(
            "matcherRegionFind", true, true, Feature.FIND, Feature.MATCHER_STATE, Feature.REGIONS
        ),
        MATCHER_REGION_MATCHES(
            "matcherRegionMatches",
            true,
            true,
            Feature.MATCHES,
            Feature.MATCHER_STATE,
            Feature.REGIONS
        ),
        MATCHER_REGION_TOKENIZE(
            "matcherRegionTokenize",
            true,
            true,
            Feature.LOOKING_AT,
            Feature.MATCHER_STATE,
            Feature.REGIONS
        ),
        FIND_IN_WINDOW("findInWindow", true, true, Feature.FIND, Feature.MATCHER_STATE),
        PATTERN_SET_COMPILE("patternSetCompile", false, false, Feature.PATTERN_SET),
        PATTERN_SET_FIND("patternSetFind", false, true, Feature.PATTERN_SET),
        PATTERN_SET_MATCHES("patternSetMatches", false, true, Feature.PATTERN_SET),
        UTF8_CAPTURE_BOUNDS("utf8CaptureBounds", false, true, Feature.FIND, Feature.UTF8_INPUT),
        UTF8_DECODE_FIND("utf8DecodeFind", false, true, Feature.FIND, Feature.UTF8_INPUT),
        UTF8_REPLACEMENT("utf8Replacement", false, true, Feature.UTF8_INPUT, Feature.UTF8_REPLACEMENT),
        ANALYZE_PATTERN("analyzePattern", false, false, Feature.DIAGNOSTICS),
        CACHED_ANALYSIS("cachedAnalysis", false, false, Feature.DIAGNOSTICS),
        COMPILE_AND_ANALYZE("compileAndAnalyze", false, false, Feature.DIAGNOSTICS),
        DFA_CACHE_GROWTH("dfaCacheGrowth", false, true, Feature.DFA_CACHE),
        DIAGNOSTICS_FIND("diagnosticsFind", false, true, Feature.FIND, Feature.DIAGNOSTICS);

        private val requiredFeatures: EnumSet<Feature?>

        init {
            this.requiredFeatures = EnumSet.noneOf<Feature?>(Feature::class.java)
            Collections.addAll<Feature?>(this.requiredFeatures, *requiredFeatures)
        }

        override fun jsonName(): String? {
            return jsonName
        }

        fun validate(
            id: String?,
            patterns: MutableList<String>,
            inputs: MutableList<String>,
            consumption: ResultConsumption,
            lifecycle: MatcherLifecycle,
            measurement: Measurement
        ) {
            require(!(inputRequired && inputs.isEmpty())) { id + " operation " + jsonName + " requires inputs" }
            require(!(!inputRequired && !inputs.isEmpty() && (this == COMPILE || this == PATTERN_SET_COMPILE || this == ANALYZE_PATTERN || this == CACHED_ANALYSIS || this == COMPILE_AND_ANALYZE))) { id + " operation " + jsonName + " must not declare inputs" }
            require(!(lifecycleRequired && lifecycle.matcher == MatcherReuse.NONE)) { id + " operation " + jsonName + " requires lifecycle" }
            require(!(!lifecycleRequired && lifecycle.matcher != MatcherReuse.NONE && this != FIND && this != FIND_ALL_COUNT && this != DIAGNOSTICS_FIND)) { id + " operation " + jsonName + " is incompatible with matcher lifecycle" }
            require(
                !((this == PATTERN_SET_COMPILE || this == PATTERN_SET_FIND || this == PATTERN_SET_MATCHES)
                        && patterns.size < 2)
            ) { id + " pattern-set operation requires multiple patterns" }
            require(supports(consumption)) {
                (id
                        + " operation "
                        + jsonName
                        + " cannot use result consumption "
                        + consumption.jsonName())
            }
            val compileOperation = this == COMPILE || this == PATTERN_SET_COMPILE
            require(
                !(compileOperation
                        && measurement.mode != MeasurementMode.COMPILE_ONLY && measurement.mode != MeasurementMode.SINGLE_SHOT_COLD_START && measurement.mode != MeasurementMode.RETAINED_MEMORY && measurement.mode != MeasurementMode.SUBPROCESS_MEMORY)
            ) { id + " compile operation requires compileOnly, singleShotColdStart, or memory mode" }
            require(!(!compileOperation && measurement.mode == MeasurementMode.COMPILE_ONLY)) { id + " operation " + jsonName + " is incompatible with compileOnly mode" }
        }

        fun requiredFeatures(): EnumSet<Feature?> {
            return requiredFeatures.clone()
        }

        private fun supports(consumption: ResultConsumption?): Boolean {
            return when (this) {
                MATCHES, FIND, LOOKING_AT, FIND_GROUP_PRESENT, FIND_SAMPLED_GROUP, COMPILE_AND_FIND, COMPILE_AND_MATCHES, FIND_ROTATING_UTF16, COMPILE_AND_FIND_ROTATING_UTF16, MATCHER_REGION_FIND, MATCHER_REGION_MATCHES, FIND_IN_WINDOW, UTF8_DECODE_FIND -> consumption == ResultConsumption.BOOLEAN
                DIAGNOSTICS_FIND -> consumption == ResultConsumption.BOOLEAN
                        || consumption == ResultConsumption.BLACKHOLE_OBJECT

                PATTERN_SET_FIND, PATTERN_SET_MATCHES -> consumption == ResultConsumption.BOOLEAN
                        || consumption == ResultConsumption.BLACKHOLE_OBJECT

                FIND_ALL_COUNT, FIND_ALL_LENGTH_SUM, FIND_ALL_GROUP_LENGTH_SUM, MATCHES_CORPUS, MATCHES_GROUP_LENGTH_SUM, MATCHER_RESET_FIND, MATCHER_REGION_TOKENIZE, REPLACE_ALL_LENGTH_SUM, SPLIT_LENGTH_SUM, UTF8_CAPTURE_BOUNDS, UTF8_REPLACEMENT -> consumption == ResultConsumption.INTEGER
                FIND_GROUP, REPLACE_FIRST, REPLACE_ALL, APPEND_REPLACEMENT, MANUAL_REPLACE_ALL -> consumption == ResultConsumption.STRING
                CAPTURE_GROUPS -> consumption == ResultConsumption.STRING || consumption == ResultConsumption.STRING_LIST
                SPLIT -> consumption == ResultConsumption.STRING_LIST
                        || consumption == ResultConsumption.BLACKHOLE_OBJECT

                COMPILE, PATTERN_SET_COMPILE -> consumption == ResultConsumption.COMPILED_OBJECT
                MATCHER_CONSTRUCTION, ANALYZE_PATTERN, CACHED_ANALYSIS, COMPILE_AND_ANALYZE -> consumption == ResultConsumption.BLACKHOLE_OBJECT
                DFA_CACHE_GROWTH -> consumption == ResultConsumption.INTEGER
            }
        }

        fun argumentTypes(): MutableMap<String?, RecipeValueType?> {
            return when (this) {
                MATCHER_REGION_TOKENIZE -> Map.of<String?, RecipeValueType?>("mode", RecipeValueType.STRING)
                FIND_ALL_GROUP_LENGTH_SUM, MATCHES_GROUP_LENGTH_SUM, CAPTURE_GROUPS -> Map.of<String?, RecipeValueType?>(
                    "groups",
                    RecipeValueType.INTEGER_LIST
                )

                UTF8_CAPTURE_BOUNDS -> Map.of<String?, RecipeValueType?>(
                    "groups", RecipeValueType.INTEGER_LIST,
                    "bounds", RecipeValueType.STRING,
                    "expectedBounds", RecipeValueType.INTEGER_LIST
                )

                FIND_SAMPLED_GROUP -> Map.of<String?, RecipeValueType?>(
                    "group",
                    RecipeValueType.INTEGER,
                    "count",
                    RecipeValueType.INTEGER,
                    "mode",
                    RecipeValueType.STRING
                )

                FIND_GROUP_PRESENT, FIND_GROUP -> Map.of<String?, RecipeValueType?>(
                    "group",
                    RecipeValueType.INTEGER
                )

                REPLACE_FIRST, REPLACE_ALL, REPLACE_ALL_LENGTH_SUM, APPEND_REPLACEMENT, MANUAL_REPLACE_ALL, UTF8_REPLACEMENT -> Map.of<String?, RecipeValueType?>(
                    "replacement",
                    RecipeValueType.STRING
                )

                SPLIT, SPLIT_LENGTH_SUM -> Map.of<String?, RecipeValueType?>(
                    "limit",
                    RecipeValueType.INTEGER
                )

                COMPILE -> Map.of<String?, RecipeValueType?>("flagSet", RecipeValueType.STRING)
                MATCHER_CONSTRUCTION -> Map.of<String?, RecipeValueType?>("mode", RecipeValueType.STRING)
                FIND_ROTATING_UTF16, COMPILE_AND_FIND_ROTATING_UTF16 -> Map.of<String?, RecipeValueType?>(
                    "seed", RecipeValueType.INTEGER,
                    "count", RecipeValueType.INTEGER
                )

                DIAGNOSTICS_FIND -> Map.of<String?, RecipeValueType?>(
                    "action", RecipeValueType.STRING,
                    "listener", RecipeValueType.STRING,
                    "replacement", RecipeValueType.STRING
                )

                PATTERN_SET_COMPILE, PATTERN_SET_FIND, PATTERN_SET_MATCHES -> Map.of<String?, RecipeValueType?>(
                    "anchor", RecipeValueType.STRING,
                    "patternCount", RecipeValueType.INTEGER
                )

                else -> Map.of<String?, RecipeValueType?>()
            }
        }

        fun requiredArguments(): MutableSet<String?> {
            return when (this) {
                FIND_ALL_GROUP_LENGTH_SUM, MATCHES_GROUP_LENGTH_SUM, CAPTURE_GROUPS -> mutableSetOf<String?>(
                    "groups"
                )

                UTF8_CAPTURE_BOUNDS -> mutableSetOf<String?>("groups", "expectedBounds")
                FIND_SAMPLED_GROUP -> mutableSetOf<String?>("group", "count", "mode")
                FIND_GROUP_PRESENT, FIND_GROUP -> mutableSetOf<String?>("group")
                REPLACE_FIRST, REPLACE_ALL, REPLACE_ALL_LENGTH_SUM, APPEND_REPLACEMENT, MANUAL_REPLACE_ALL, UTF8_REPLACEMENT -> mutableSetOf<String?>(
                    "replacement"
                )

                PATTERN_SET_COMPILE, PATTERN_SET_FIND, PATTERN_SET_MATCHES -> mutableSetOf<String?>(
                    "anchor"
                )

                FIND_ROTATING_UTF16, COMPILE_AND_FIND_ROTATING_UTF16 -> mutableSetOf<String?>(
                    "seed",
                    "count"
                )

                else -> mutableSetOf<String?>()
            }
        }

        fun validateArguments(id: String?, arguments: MutableMap<String?, RecipeValue?>) {
            if (this == FIND_SAMPLED_GROUP) {
                val mode: String = (arguments.get("mode") as RecipeString).value
                require(!(mode != "boolean" && mode != "capture")) { id + " mode must be boolean or capture" }
            }
            val group = arguments.get("group")
            require(!(group != null && (group as RecipeInteger).value < 0)) { id + " group argument must not be negative" }
            val groups = arguments.get("groups")
            if (groups != null) {
                val values: MutableList<Int> = (groups as RecipeIntegerList).values
                require(
                    !(values.isEmpty || values.stream()
                        .anyMatch { value: Int? -> value!! < 0 })
                ) { id + " groups argument must contain nonnegative group numbers" }
            }
            val anchor = arguments.get("anchor")
            if (anchor != null) {
                val value: String = (anchor as RecipeString).value
                require(!(value != "unanchored" && value != "anchored")) { id + " anchor argument must be unanchored or anchored" }
            }
            val patternCount = arguments.get("patternCount")
            require(!(patternCount != null && (patternCount as RecipeInteger).value <= 0)) { id + " patternCount argument must be positive" }
            val count = arguments.get("count")
            require(!(count != null && (count as RecipeInteger).value <= 0)) { id + " count argument must be positive" }
            val bounds = arguments.get("bounds")
            if (bounds != null) {
                val value: String = (bounds as RecipeString).value
                require(!(value != "start" && value != "startEnd")) { id + " bounds argument must be start or startEnd" }
            }
        }

        companion object {
            fun fromJson(name: String?): Operation {
                return Companion.enumFromJson<Operation>(
                    Operation.entries.toTypedArray(),
                    name,
                    "benchmark operation"
                )!!
            }
        }
    }

    internal enum class Flag(jsonName: String) : JsonNamed {
        CASE_INSENSITIVE("caseInsensitive"),
        MULTILINE("multiline"),
        DOTALL("dotAll"),
        UNICODE_CASE("unicodeCase"),
        COMMENTS("comments"),
        LITERAL("literal"),
        UNICODE_CHARACTER_CLASS("unicodeCharacterClass");

        private val jsonName: String?


        override fun jsonName(): String? {
            return jsonName
        }

        companion object {
            fun fromJson(name: String?): Flag {
                return Companion.enumFromJson<Flag>(
                    Flag.entries.toTypedArray(),
                    name,
                    "regex flag"
                )!!
            }
        }
    }

    internal enum class Feature(jsonName: String) : JsonNamed {
        FIND("find"),
        MATCHES("matches"),
        LOOKING_AT("lookingAt"),
        CAPTURE_PARTICIPATION("captureParticipation"),
        CAPTURE_TEXT("captureText"),
        NAMED_GROUPS("namedGroups"),
        REPLACE("replace"),
        NUMBERED_REPLACEMENT("numberedReplacement"),
        NAMED_REPLACEMENT("namedReplacement"),
        APPEND_REPLACEMENT("appendReplacement"),
        FUNCTIONAL_REPLACEMENT("functionalReplacement"),
        SPLIT("split"),
        MATCHER_STATE("matcherState"),
        REGIONS("regions"),
        BOUNDS("bounds"),
        PATTERN_SET("patternSet"),
        UTF8_INPUT("utf8Input"),
        UTF8_REPLACEMENT("utf8Replacement"),
        DIAGNOSTICS("diagnostics"),
        DFA_CACHE("dfaCache"),
        FLAGGED_COMPILE("flaggedCompile"),
        JAVA_CHARACTER_CLASS("javaCharacterClass"),
        LINEAR_TIME("linearTime"),
        RETAINED_HEAP("retainedHeap");

        private val jsonName: String?


        override fun jsonName(): String? {
            return jsonName
        }

        companion object {
            fun fromJson(name: String?): Feature {
                return Companion.enumFromJson<Feature>(
                    Feature.entries.toTypedArray(),
                    name,
                    "engine-neutral feature requirement"
                )!!
            }
        }
    }

    internal enum class InputRepresentation(jsonName: String) : JsonNamed {
        JAVA_STRING("javaString"),
        PREEXISTING_UTF8("preexistingUtf8"),
        JAVA_STRING_WITH_TIMED_UTF8_CONVERSION("javaStringWithTimedUtf8Conversion");

        private val jsonName: String?


        override fun jsonName(): String? {
            return jsonName
        }

        companion object {
            fun fromJson(name: String?): InputRepresentation {
                return Companion.enumFromJson<InputRepresentation>(
                    InputRepresentation.entries.toTypedArray(),
                    name,
                    "input representation"
                )!!
            }
        }
    }

    internal enum class ResultConsumption(jsonName: String, expectedType: ResultType) : JsonNamed {
        BOOLEAN("boolean", ResultType.BOOLEAN),
        INTEGER("integer", ResultType.INTEGER),
        STRING("string", ResultType.STRING),
        STRING_LIST("stringList", ResultType.STRING_LIST),
        COMPILED_OBJECT("compiledObject", null),
        BLACKHOLE_OBJECT("blackholeObject", null);

        private val jsonName: String?
        private val expectedType: ResultType


        override fun jsonName(): String? {
            return jsonName
        }

        fun validateExpected(id: String?, expected: ExpectedResult?) {
            if (expected == null) {
                return
            }
            requireNotNull(expectedType) { id + " result consumption " + jsonName + " cannot declare expected" }
            require(expected.type == expectedType) {
                (id
                        + " result consumption "
                        + jsonName
                        + " requires expected type "
                        + expectedType.jsonName())
            }
        }

        companion object {
            fun fromJson(name: String?): ResultConsumption {
                return Companion.enumFromJson<ResultConsumption>(
                    ResultConsumption.entries.toTypedArray(),
                    name,
                    "result consumption"
                )!!
            }
        }
    }

    internal enum class ResultType(jsonName: String) : JsonNamed {
        BOOLEAN("boolean"),
        INTEGER("integer"),
        STRING("string"),
        STRING_LIST("stringList");

        private val jsonName: String?


        override fun jsonName(): String? {
            return jsonName
        }

        fun validate(value: JsonElement) {
            val valid =
                when (this) {
                    BOOLEAN -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean
                    INTEGER -> (value.isJsonPrimitive
                            && value.asJsonPrimitive.isNumber
                            && isInteger(value))

                    STRING -> value.isJsonPrimitive && value.asJsonPrimitive.isString
                    STRING_LIST -> value.isJsonArray
                            && value.asJsonArray.asList().stream()
                        .allMatch { item: JsonElement? ->
                            item!!.isJsonPrimitive && item.asJsonPrimitive.isString
                        }
                }
            require(valid) { "Malformed " + jsonName + " expected result: " + value }
        }

        companion object {
            private fun isInteger(value: JsonElement): Boolean {
                try {
                    value.asBigDecimal.toBigIntegerExact()
                    return true
                } catch (exception: ArithmeticException) {
                    return false
                } catch (exception: NumberFormatException) {
                    return false
                }
            }

            fun fromJson(name: String?): ResultType {
                return Companion.enumFromJson<ResultType>(
                    ResultType.entries.toTypedArray(),
                    name,
                    "expected result type"
                )!!
            }
        }
    }

    internal enum class MatcherReuse(jsonName: String) : JsonNamed {
        NONE("none"),
        NEW_PER_INVOCATION("newPerInvocation"),
        RETAINED("retained");

        private val jsonName: String?


        override fun jsonName(): String? {
            return jsonName
        }

        companion object {
            fun fromJson(name: String?): MatcherReuse {
                return Companion.enumFromJson<MatcherReuse>(
                    MatcherReuse.entries.toTypedArray(),
                    name,
                    "matcher reuse policy"
                )!!
            }
        }
    }

    internal enum class LifecycleStepKind(jsonName: String) : JsonNamed {
        RESET("reset"),
        REGION("region"),
        TRANSPARENT_BOUNDS("transparentBounds"),
        ANCHORING_BOUNDS("anchoringBounds");

        private val jsonName: String?


        override fun jsonName(): String? {
            return jsonName
        }

        fun validate(start: Int?, end: Int?, enabled: Boolean?) {
            when (this) {
                RESET -> requireAbsent(start, end, enabled)
                REGION -> {
                    require(!(start == null || end == null || start < 0 || end < start || enabled != null)) { "region lifecycle step requires valid start/end and no enabled field" }
                }

                TRANSPARENT_BOUNDS, ANCHORING_BOUNDS -> {
                    require(!(enabled == null || start != null || end != null)) { jsonName + " lifecycle step requires enabled and no start/end" }
                }
            }
        }

        companion object {
            private fun requireAbsent(start: Int?, end: Int?, enabled: Boolean?) {
                require(!(start != null || end != null || enabled != null)) { "reset lifecycle step takes no parameters" }
            }

            fun fromJson(name: String?): LifecycleStepKind {
                return Companion.enumFromJson<LifecycleStepKind>(
                    LifecycleStepKind.entries.toTypedArray(),
                    name,
                    "matcher lifecycle step"
                )!!
            }
        }
    }

    internal enum class MeasurementMode(jsonName: String) : JsonNamed {
        AVERAGE_TIME("averageTime"),
        COMPILE_ONLY("compileOnly"),
        SINGLE_SHOT_COLD_START("singleShotColdStart"),
        RETAINED_MEMORY("retainedMemory"),
        SUBPROCESS_MEMORY("subprocessMemory");

        private val jsonName: String?


        override fun jsonName(): String? {
            return jsonName
        }

        fun validate(unit: TimingUnit, constraints: EnumSet<ExecutionConstraint?>) {
            when (this) {
                AVERAGE_TIME, COMPILE_ONLY -> {
                    require(unit.isTime) { jsonName + " requires a time unit" }
                }

                SINGLE_SHOT_COLD_START -> {
                    require(
                        !(!unit.isTime
                                || !constraints.contains(ExecutionConstraint.FRESH_PROCESS_PER_INVOCATION))
                    ) { jsonName + " requires a time unit and freshProcessPerInvocation" }
                }

                RETAINED_MEMORY, SUBPROCESS_MEMORY -> {
                    require(unit == TimingUnit.BYTES) { jsonName + " requires bytes timingUnit" }
                }
            }
            require(
                !(constraints.contains(ExecutionConstraint.NO_FORK)
                        && constraints.contains(ExecutionConstraint.FRESH_PROCESS_PER_INVOCATION))
            ) { "Measurement constraints noFork and freshProcessPerInvocation are incompatible" }
        }

        companion object {
            fun fromJson(name: String?): MeasurementMode {
                return Companion.enumFromJson<MeasurementMode>(
                    MeasurementMode.entries.toTypedArray(),
                    name,
                    "measurement mode"
                )!!
            }
        }
    }

    internal enum class TimingUnit(jsonName: String, time: Boolean) : JsonNamed {
        NANOSECONDS("nanoseconds", true),
        MICROSECONDS("microseconds", true),
        MILLISECONDS("milliseconds", true),
        BYTES("bytes", false);

        private val jsonName: String?
        val isTime: Boolean

        init {
            this.isTime = time
        }

        override fun jsonName(): String? {
            return jsonName
        }

        companion object {
            fun fromJson(name: String?): TimingUnit {
                return Companion.enumFromJson<TimingUnit>(
                    TimingUnit.entries.toTypedArray(),
                    name,
                    "measurement timing unit"
                )!!
            }
        }
    }

    internal enum class ExecutionConstraint(jsonName: String) : JsonNamed {
        NO_FORK("noFork"),
        FRESH_PROCESS_PER_INVOCATION("freshProcessPerInvocation"),
        RETAIN_STATE("retainState"),
        PREMATERIALIZED_INPUT("prematerializedInput"),
        ALLOCATION_PROFILE("allocationProfile");

        private val jsonName: String?


        override fun jsonName(): String? {
            return jsonName
        }

        companion object {
            fun fromJson(name: String?): ExecutionConstraint {
                return Companion.enumFromJson<ExecutionConstraint>(
                    ExecutionConstraint.entries.toTypedArray(),
                    name,
                    "execution constraint"
                )!!
            }
        }
    }

    internal enum class ExclusionKind {
        EXPLICIT_TRIAL_EXCLUSION,
        WORKLOAD_DISABLED,
        UNSUPPORTED_FEATURE,
        UNSUPPORTED_FLAG,
        UNSUPPORTED_INPUT_REPRESENTATION,
        MISSING_OPERATION_IMPLEMENTATION,
        MISSING_ENGINE_ADAPTER
    }

    private interface JsonNamed {
        fun jsonName(): String?
    }

    private interface EnumParser<E> {
        fun parse(value: String?): E?
    }

    private class JsonReader(private val context: String?, private val `object`: JsonObject) {
        fun requireOnly(vararg allowedNames: String?) {
            val allowed = Set.of<String?>(*allowedNames)
            for (name in `object`.keySet()) {
                require(allowed.contains(name)) { context + " has unknown field: " + name }
            }
        }

        fun has(name: String?): Boolean {
            return `object`.has(name) && !`object`.get(name).isJsonNull
        }

        fun required(name: String?): JsonElement {
            val value = `object`.get(name)
            require(!(value == null || value.isJsonNull)) { context + " requires " + name }
            return value
        }

        fun requiredString(name: String?): String {
            val value = required(name)
            require(isString(value)) { context + " field " + name + " must be a string" }
            return value.asString
        }

        fun optionalString(name: String?): String? {
            val value = `object`.get(name)
            if (value == null || value.isJsonNull) {
                return null
            }
            require(isString(value)) { context + " field " + name + " must be a string" }
            return value.asString
        }

        fun requiredInt(name: String?): Int {
            val value = required(name)
            require(isIntegerPrimitive(value)) { context + " field " + name + " must be an integer" }
            return value.asBigDecimal.intValueExact()
        }

        fun optionalInt(name: String?): Int? {
            val value = `object`.get(name)
            if (value == null || value.isJsonNull) {
                return null
            }
            require(isIntegerPrimitive(value)) { context + " field " + name + " must be an integer" }
            return value.asBigDecimal.intValueExact()
        }

        fun optionalBoolean(name: String?, defaultValue: Boolean): Boolean {
            val value = optionalBoolean(name)
            return if (value == null) defaultValue else value
        }

        fun optionalBoolean(name: String?): Boolean? {
            val value = `object`.get(name)
            if (value == null || value.isJsonNull) {
                return null
            }
            require(
                !(!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean)
            ) { context + " field " + name + " must be a boolean" }
            return value.asBoolean
        }

        fun requiredArray(name: String?): JsonArray {
            try {
                return required(name).asJsonArray
            } catch (exception: RuntimeException) {
                throw IllegalArgumentException(
                    context + " field " + name + " must be an array", exception
                )
            }
        }

        fun optionalArray(name: String?): JsonArray? {
            val value = `object`.get(name)
            if (value == null || value.isJsonNull) {
                return JsonArray()
            }
            try {
                return value.asJsonArray
            } catch (exception: RuntimeException) {
                throw IllegalArgumentException(
                    context + " field " + name + " must be an array", exception
                )
            }
        }

        fun requiredObject(name: String?): JsonObject? {
            try {
                return required(name).asJsonObject
            } catch (exception: RuntimeException) {
                throw IllegalArgumentException(
                    context + " field " + name + " must be an object", exception
                )
            }
        }

        fun optionalObject(name: String?): JsonObject? {
            val value = `object`.get(name)
            if (value == null || value.isJsonNull) {
                return null
            }
            try {
                return value.asJsonObject
            } catch (exception: RuntimeException) {
                throw IllegalArgumentException(
                    context + " field " + name + " must be an object", exception
                )
            }
        }

        fun requiredStringList(name: String?): MutableList<String> {
            required(name)
            return stringList(name)
        }

        fun optionalStringList(name: String?): MutableList<String> {
            val value = `object`.get(name)
            if (value == null || value.isJsonNull) {
                return mutableListOf<String?>()
            }
            return stringList(name)
        }

        fun stringList(name: String?): MutableList<String> {
            val value = `object`.get(name)
            try {
                val result: MutableList<String> = ArrayList<String>()
                value
                    .asJsonArray
                    .forEach(
                        Consumer { item: JsonElement? ->
                            require(isString(item!!))
                            result.add(item.asString)
                        })
                return List.copyOf<String?>(result)
            } catch (exception: RuntimeException) {
                throw IllegalArgumentException(
                    context + " field " + name + " must be an array of strings", exception
                )
            }
        }

        companion object {
            fun `object`(context: String?, element: JsonElement): JsonReader {
                require(!(element == null || !element.isJsonObject)) { context + " must be an object" }
                return JsonReader(context, element.asJsonObject)
            }
        }
    }

    companion object {
        const val SCHEMA_VERSION: Int = 2
        private val PLACEHOLDER: Pattern = Pattern.compile("(?<!\\$)\\{([A-Za-z][A-Za-z0-9_]*)}")

        fun parse(json: JsonObject): DeclarativeBenchmarkPlan {
            val root = JsonReader("plan", json)
            root.requireOnly("schemaVersion", "inputs", "workloads")
            val schemaVersion = root.requiredInt("schemaVersion")
            require(schemaVersion == SCHEMA_VERSION) { "Unsupported benchmark plan schema version: " + schemaVersion }

            val inputs: MutableMap<String?, InputDeclaration?> = parseInputDeclarations(root.requiredArray("inputs"))

            val workloads: MutableList<WorkloadDeclaration> = ArrayList<WorkloadDeclaration>()
            val workloadTemplates: MutableSet<String?> = LinkedHashSet<String?>()
            for (element in root.requiredArray("workloads")) {
                val workload: WorkloadDeclaration = parseWorkload(element)
                require(workloadTemplates.add(workload.idTemplate)) { "Duplicate benchmark workload ID template: " + workload.idTemplate }
                workloads.add(workload)
            }
            require(!workloads.isEmpty()) { "Benchmark plan requires at least one workload" }

            val plan = DeclarativeBenchmarkPlan(inputs, workloads)
            plan.validateReferencesAndIdentities()
            return plan
        }

        fun parseInputDeclarations(declarations: JsonArray): MutableMap<String?, InputDeclaration?> {
            val inputs: MutableMap<String?, InputDeclaration?> = LinkedHashMap<String?, InputDeclaration?>()
            for (element in declarations) {
                for (input in parseInputs(element)) {
                    require(inputs.put(input.id, input) == null) { "Duplicate benchmark input ID: " + input.id }
                }
            }
            return Collections.unmodifiableMap<String?, InputDeclaration?>(inputs)
        }

        private fun exclusion(
            workload: ExpandedWorkload, engine: EngineDeclaration, implementedOperations: MutableSet<Operation?>
        ): Exclusion? {
            val trialExclusion = workload.trialExclusion(engine.id)
            if (trialExclusion != null) {
                return Exclusion(
                    workload.id,
                    engine.id,
                    ExclusionKind.EXPLICIT_TRIAL_EXCLUSION,
                    trialExclusion.reason
                )
            }
            if (!engine.adapterAvailable) {
                return Exclusion(
                    workload.id,
                    engine.id,
                    ExclusionKind.MISSING_ENGINE_ADAPTER,
                    "engine adapter is not available"
                )
            }
            if (!implementedOperations.contains(workload.operation)) {
                return Exclusion(
                    workload.id,
                    engine.id,
                    ExclusionKind.MISSING_OPERATION_IMPLEMENTATION,
                    "operation " + workload.operation!!.jsonName() + " is not implemented"
                )
            }
            if (!workload.inputRepresentations().contains(engine.inputRepresentation)) {
                return Exclusion(
                    workload.id,
                    engine.id,
                    ExclusionKind.UNSUPPORTED_INPUT_REPRESENTATION,
                    "workload does not accept " + engine.inputRepresentation!!.jsonName()
                )
            }
            val unsupportedFlags = workload.flags().clone()
            unsupportedFlags.removeAll(engine.supportedFlags())
            if (!unsupportedFlags.isEmpty()) {
                return Exclusion(
                    workload.id,
                    engine.id,
                    ExclusionKind.UNSUPPORTED_FLAG,
                    "engine lacks flags " + enumNames(unsupportedFlags)
                )
            }
            val missing = workload.requirements().clone()
            missing.removeAll(engine.features())
            if (!missing.isEmpty()) {
                return Exclusion(
                    workload.id,
                    engine.id,
                    ExclusionKind.UNSUPPORTED_FEATURE,
                    "engine lacks " + enumNames(missing)
                )
            }
            return null
        }

        private fun parseInputs(element: JsonElement): MutableList<InputDeclaration> {
            val input: JsonReader = JsonReader.`object`("input", element)
            input.requireOnly("id", "axes", "recipe", "shared")
            val idTemplate = input.requiredString("id")
            val axes: MutableMap<String?, MutableList<ParameterValue?>> = parseAxes(input.optionalObject("axes"))
            Companion.requirePlaceholderCoverage(idTemplate, axes.keys, idTemplate)
            val recipe: InputRecipe = parseRecipe(input.requiredObject("recipe")!!, idTemplate)
            recipe.validatePlaceholders(idTemplate, axes.keys)

            val declarations: MutableList<InputDeclaration> = ArrayList<InputDeclaration>()
            for (parameters in parameterBindings(axes)) {
                val id = substitute(idTemplate, parameters, "input ID")
                requireSimpleId(id, "benchmark input")
                declarations.add(
                    DeclarativeBenchmarkPlan.InputDeclaration(
                        id,
                        recipe.substitute(parameters, id),
                        input.optionalBoolean("shared", false),
                        parameters
                    )
                )
            }
            return List.copyOf<InputDeclaration?>(declarations)
        }

        private fun parseRecipe(`object`: JsonObject, inputId: String?): InputRecipe {
            val recipe = JsonReader(inputId + " recipe", `object`)
            val kindName = recipe.requiredString("kind")
            val kind = RecipeKind.fromJson(kindName)
            recipe.requireOnly(*kind.allowedFields()!!)
            val arguments: MutableMap<String?, RecipeValue?> = LinkedHashMap<String?, RecipeValue?>()
            for (field in kind.fields()) {
                val value = `object`.get(field.name)
                if (value == null) {
                    require(!field.required) { inputId + " " + kindName + " recipe requires " + field.name }
                    continue
                }
                arguments.put(
                    field.name, RecipeValue.parse(value, field.type!!, inputId + " recipe", field.name)
                )
            }
            return InputRecipe(kind, arguments)
        }

        private fun parseWorkload(element: JsonElement): WorkloadDeclaration {
            val workload: JsonReader = JsonReader.`object`("workload", element)
            workload.requireOnly(
                "id",
                "operation",
                "patterns",
                "inputs",
                "axes",
                "arguments",
                "flags",
                "requirements",
                "inputRepresentations",
                "inputRepresentationReason",
                "resultConsumption",
                "expected",
                "lifecycle",
                "measurement",
                "disabledReason",
                "trialExclusions"
            )
            val id = workload.requiredString("id")
            val operation: Operation = Operation.fromJson(workload.requiredString("operation"))
            val patterns = workload.requiredStringList("patterns")
            val inputs = workload.optionalStringList("inputs")
            val axes: MutableMap<String?, MutableList<ParameterValue?>> = parseAxes(workload.optionalObject("axes"))
            val arguments: MutableMap<String?, RecipeValue?> =
                Companion.parseOperationArguments(workload.optionalObject("arguments"), operation, id, axes.keys)
            val flags: EnumSet<Flag?> = Companion.enumSet<Flag?>(
                workload.optionalStringList("flags"),
                EnumParser { name: String? -> Flag.fromJson(name) },
                Flag::class.java
            )
            val requirements: EnumSet<Feature?> =
                Companion.enumSet<Feature?>(
                    workload.optionalStringList("requirements"),
                    EnumParser { name: String? -> Feature.fromJson(name) },
                    Feature::class.java
                )
            val representationsDeclared = workload.has("inputRepresentations")
            val representations: EnumSet<InputRepresentation?>?
            if (representationsDeclared) {
                representations =
                    Companion.enumSet<InputRepresentation?>(
                        workload.requiredStringList("inputRepresentations"),
                        EnumParser { name: String? -> InputRepresentation.fromJson(name) },
                        InputRepresentation::class.java
                    )
            } else {
                representations = EnumSet.allOf<InputRepresentation?>(InputRepresentation::class.java)
            }
            val inputRepresentationReason = workload.optionalString("inputRepresentationReason")
            val consumption =
                ResultConsumption.fromJson(workload.requiredString("resultConsumption"))
            val expected: ExpectedResult? = parseExpected(workload.optionalObject("expected"), axes.keys)
            val lifecycle: MatcherLifecycle = parseLifecycle(workload.optionalObject("lifecycle"))
            val measurement: Measurement = parseMeasurement(workload.requiredObject("measurement")!!)
            val disabledReason = workload.optionalString("disabledReason")
            val trialExclusions: MutableList<TrialExclusion> =
                parseTrialExclusions(workload.optionalArray("trialExclusions")!!, id, axes)

            require(!patterns.isEmpty) { id + " requires at least one pattern" }
            require(!representations.isEmpty()) { id + " requires at least one input representation" }
            require(
                !(representationsDeclared
                        && representations == EnumSet.allOf<InputRepresentation?>(InputRepresentation::class.java))
            ) { id + " accepts every input representation; omit inputRepresentations instead" }
            require(
                !(representationsDeclared
                        && (inputRepresentationReason == null || inputRepresentationReason.isBlank()))
            ) { id + " requires a nonblank inputRepresentationReason when inputRepresentations is set" }
            require(!(!representationsDeclared && inputRepresentationReason != null)) { id + " must not declare inputRepresentationReason without inputRepresentations" }
            require(!(disabledReason != null && disabledReason.isBlank())) { id + " disabledReason must not be blank" }
            require(!(disabledReason != null && !trialExclusions.isEmpty())) { id + " must not declare both disabledReason and trialExclusions" }
            requirements.addAll(operation.requiredFeatures())
            requirements.addAll(lifecycle.requiredFeatures())
            operation.validate(id, patterns, inputs, consumption, lifecycle, measurement)
            consumption.validateExpected(id, expected)
            return WorkloadDeclaration(
                id,
                operation,
                patterns,
                inputs,
                axes,
                arguments,
                flags,
                requirements,
                representations,
                inputRepresentationReason,
                consumption,
                expected,
                lifecycle,
                measurement,
                disabledReason,
                trialExclusions
            )
        }

        private fun parseTrialExclusions(
            declarations: JsonArray, workloadId: String?, axes: MutableMap<String?, MutableList<ParameterValue?>>
        ): MutableList<TrialExclusion> {
            val result: MutableList<TrialExclusion> = ArrayList<TrialExclusion>()
            for (element in declarations) {
                val exclusion: JsonReader = JsonReader.`object`(workloadId + " trial exclusion", element)
                exclusion.requireOnly("engineIds", "when", "reason")
                val engineIds = exclusion.requiredStringList("engineIds")
                require(!engineIds.isEmpty()) { workloadId + " trial exclusion requires at least one engine ID" }
                val distinctEngineIds: MutableSet<String> = LinkedHashSet<String>()
                for (engineId in engineIds) {
                    requireSimpleId(engineId, "benchmark engine")
                    require(distinctEngineIds.add(engineId)) { workloadId + " trial exclusion has duplicate engine ID: " + engineId }
                }

                val selectors: MutableMap<String?, MutableSet<ParameterValue?>?> =
                    LinkedHashMap<String?, MutableSet<ParameterValue?>?>()
                val `when` = exclusion.optionalObject("when")
                if (`when` != null) {
                    for (selector in `when`.entrySet()) {
                        val axis = selector.key
                        val declaredValues: MutableList<ParameterValue?> = axes.get(axis)!!
                        requireNotNull(declaredValues) { workloadId + " trial exclusion references unknown axis: " + axis }
                        val values: JsonArray
                        try {
                            values = selector.value.asJsonArray
                        } catch (exception: RuntimeException) {
                            throw IllegalArgumentException(
                                workloadId + " trial exclusion selector must be an array: " + axis, exception
                            )
                        }
                        require(!values.isEmpty) { workloadId + " trial exclusion selector must not be empty: " + axis }
                        val parsedValues: MutableSet<ParameterValue?> = LinkedHashSet<ParameterValue?>()
                        for (value in values) {
                            val parsed = ParameterValue.parse(value, axis)
                            require(parsedValues.add(parsed)) {
                                (workloadId
                                        + " trial exclusion has duplicate axis value: "
                                        + axis
                                        + "="
                                        + parsed.stableText)
                            }
                            require(declaredValues.contains(parsed)) {
                                (workloadId
                                        + " trial exclusion selects undeclared axis value: "
                                        + axis
                                        + "="
                                        + parsed.stableText)
                            }
                        }
                        selectors.put(axis, Collections.unmodifiableSet<ParameterValue?>(parsedValues))
                    }
                }
                val reason = exclusion.requiredString("reason")
                require(!reason.isBlank()) { workloadId + " trial exclusion reason must not be blank" }
                result.add(
                    TrialExclusion(
                        List.copyOf<String?>(distinctEngineIds),
                        Collections.unmodifiableMap<String?, MutableSet<ParameterValue?>?>(selectors),
                        reason
                    )
                )
            }
            return List.copyOf<TrialExclusion?>(result)
        }

        private fun parseAxes(`object`: JsonObject?): MutableMap<String?, MutableList<ParameterValue?>> {
            if (`object` == null) {
                return Map.of<String?, MutableList<ParameterValue?>?>()
            }
            val axes: MutableMap<String?, MutableList<ParameterValue?>?> =
                LinkedHashMap<String?, MutableList<ParameterValue?>?>()
            for (entry in `object`.entrySet()) {
                requireSimpleId(entry.key, "benchmark axis")
                val values: JsonArray
                try {
                    values = entry.value.asJsonArray
                } catch (exception: RuntimeException) {
                    throw IllegalArgumentException(
                        "Benchmark axis must be an array: " + entry.key, exception
                    )
                }
                require(!values.isEmpty) { "Benchmark axis must not be empty: " + entry.key }
                val parsed: MutableList<ParameterValue> = ArrayList<ParameterValue>()
                val stableValues: MutableSet<String?> = LinkedHashSet<String?>()
                for (value in values) {
                    val parameter = ParameterValue.parse(value, entry.key)
                    require(stableValues.add(parameter.stableText)) { "Duplicate benchmark axis value: " + entry.key + "=" + parameter.stableText }
                    parsed.add(parameter)
                }
                axes.put(entry.key, List.copyOf<ParameterValue?>(parsed))
            }
            return Collections.unmodifiableMap<String?, MutableList<ParameterValue?>?>(axes)
        }

        private fun parseOperationArguments(
            `object`: JsonObject?, operation: Operation, workloadId: String?, axes: MutableSet<String>
        ): MutableMap<String?, RecipeValue?> {
            val fieldTypes = operation.argumentTypes()
            val required = operation.requiredArguments()
            if (`object` == null) {
                require(required.isEmpty()) { workloadId + " operation " + operation.jsonName() + " requires arguments " + required }
                return Map.of<String?, RecipeValue?>()
            }
            for (field in `object`.keySet()) {
                require(fieldTypes.containsKey(field)) { workloadId + " operation " + operation.jsonName() + " has unknown argument: " + field }
            }
            val arguments: MutableMap<String?, RecipeValue?> = LinkedHashMap<String?, RecipeValue?>()
            for (field in fieldTypes.entries) {
                val value = `object`.get(field.key)
                if (value == null || value.isJsonNull) {
                    require(!required.contains(field.key)) {
                        (workloadId
                                + " operation "
                                + operation.jsonName()
                                + " requires argument "
                                + field.key)
                    }
                    continue
                }
                val argument =
                    RecipeValue.parse(value, field.value!!, workloadId + " arguments", field.key)
                val unknownAxes: MutableSet<String?> = LinkedHashSet<String?>(argument.placeholders())
                unknownAxes.removeAll(axes)
                require(unknownAxes.isEmpty()) {
                    (workloadId
                            + " argument "
                            + field.key
                            + " references unknown axes: "
                            + unknownAxes)
                }
                arguments.put(field.key, argument)
            }
            return Collections.unmodifiableMap<String?, RecipeValue?>(arguments)
        }

        private fun parseExpected(`object`: JsonObject?, axes: MutableSet<String?>): ExpectedResult? {
            if (`object` == null) {
                return null
            }
            val expected = JsonReader("expected result", `object`)
            expected.requireOnly("type", "value", "axis")
            val type = ResultType.fromJson(expected.requiredString("type"))
            val value = `object`.get("value")
            val axis = expected.optionalString("axis")
            require((value == null || value.isJsonNull) != (axis == null)) { "Expected result requires exactly one value or axis" }
            if (axis != null) {
                require(axes.contains(axis)) { "Expected result references unknown axis: " + axis }
                return ExpectedResult(type, null, axis)
            }
            type.validate(value!!)
            return ExpectedResult(type, value.deepCopy(), null)
        }

        private fun parseLifecycle(`object`: JsonObject?): MatcherLifecycle {
            if (`object` == null) {
                return MatcherLifecycle.NONE
            }
            val lifecycle = JsonReader("matcher lifecycle", `object`)
            lifecycle.requireOnly("matcher", "steps")
            val matcherReuse = MatcherReuse.fromJson(lifecycle.requiredString("matcher"))
            val steps: MutableList<LifecycleStep> = ArrayList<LifecycleStep>()
            for (element in lifecycle.optionalArray("steps")!!) {
                val step: JsonReader = JsonReader.`object`("matcher lifecycle step", element)
                step.requireOnly("kind", "start", "end", "enabled")
                val kind = LifecycleStepKind.fromJson(step.requiredString("kind"))
                val start = step.optionalInt("start")
                val end = step.optionalInt("end")
                val enabled = step.optionalBoolean("enabled")
                kind.validate(start, end, enabled)
                steps.add(LifecycleStep(kind, start, end, enabled))
            }
            require(!(matcherReuse == MatcherReuse.NONE && !steps.isEmpty())) { "Matcher lifecycle NONE cannot declare steps" }
            return MatcherLifecycle(matcherReuse, steps)
        }

        private fun parseMeasurement(`object`: JsonObject): Measurement {
            val measurement = JsonReader("measurement", `object`)
            measurement.requireOnly("mode", "timingUnit", "constraints")
            val mode = MeasurementMode.fromJson(measurement.requiredString("mode"))
            val timingUnit = TimingUnit.fromJson(measurement.requiredString("timingUnit"))
            val constraints: EnumSet<ExecutionConstraint?> =
                Companion.enumSet<ExecutionConstraint?>(
                    measurement.optionalStringList("constraints"),
                    EnumParser { name: String? -> ExecutionConstraint.fromJson(name) },
                    ExecutionConstraint::class.java
                )
            mode.validate(timingUnit, constraints)
            return Measurement(mode, timingUnit, constraints)
        }

        private fun <E : Enum<E?>?> enumSet(
            names: MutableList<String>, parser: EnumParser<E?>, type: Class<E?>?
        ): EnumSet<E?> {
            val result = EnumSet.noneOf<E?>(type)
            for (name in names) {
                val value = parser.parse(name)
                require(result.add(value)) { "Duplicate declaration value: " + name }
            }
            return result
        }

        private fun parameterBindings(
            axes: MutableMap<String?, MutableList<ParameterValue?>>
        ): MutableList<MutableMap<String?, ParameterValue?>> {
            var bindings: MutableList<MutableMap<String?, ParameterValue?>?> =
                ArrayList<MutableMap<String?, ParameterValue?>?>()
            bindings.add(LinkedHashMap<String?, ParameterValue?>())
            for (axis in axes.entries) {
                val next: MutableList<MutableMap<String?, ParameterValue?>?> =
                    ArrayList<MutableMap<String?, ParameterValue?>?>()
                for (binding in bindings) {
                    for (value in axis.value) {
                        val expanded: MutableMap<String?, ParameterValue?> =
                            LinkedHashMap<String?, ParameterValue?>(binding)
                        expanded.put(axis.key, value)
                        next.add(expanded)
                    }
                }
                bindings = next
            }
            return bindings.stream()
                .map<MutableMap<String?, ParameterValue?>?> { binding: MutableMap<String?, ParameterValue?>? ->
                    Collections.unmodifiableMap<String?, ParameterValue?>(
                        LinkedHashMap<String?, ParameterValue?>(binding)
                    )
                }
                .toList()
        }

        private fun substituteValue(
            template: String, parameters: MutableMap<String?, ParameterValue>, context: String?
        ): String {
            return substitute(template, parameters, context, false)
        }

        private fun substitute(
            template: String,
            parameters: MutableMap<String?, ParameterValue>,
            context: String?,
            useStableText: Boolean = true
        ): String {
            val matcher: Matcher = PLACEHOLDER.matcher(template)
            val result = StringBuilder()
            while (matcher.find()) {
                val value: ParameterValue = parameters.get(matcher.group(1))!!
                requireNotNull(value) { context + " references unknown axis: " + matcher.group(1) }
                val replacement = (if (useStableText) value.stableText else value.valueText())!!
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement))
            }
            matcher.appendTail(result)
            return result.toString()
        }

        private fun substitutePattern(template: String, parameters: MutableMap<String?, ParameterValue>): String {
            val matcher: Matcher = PLACEHOLDER.matcher(template)
            val result = StringBuilder()
            while (matcher.find()) {
                val value = parameters.get(matcher.group(1))
                if (value == null) {
                    matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()))
                } else {
                    matcher.appendReplacement(result, Matcher.quoteReplacement(value.valueText()))
                }
            }
            matcher.appendTail(result)
            return result.toString()
        }

        private fun requirePlaceholderCoverage(
            template: String, axes: MutableSet<String>, context: String?
        ) {
            val placeholders: MutableSet<String> = placeholders(template)
            requireKnownPlaceholders(placeholders, axes, context)
            if (!placeholders.containsAll(axes)) {
                val missing: MutableSet<String?> = LinkedHashSet<String?>(axes)
                missing.removeAll(placeholders)
                throw IllegalArgumentException(
                    context + " ID omits expansion axes and would not be stable: " + missing
                )
            }
        }

        private fun requireKnownPlaceholders(template: String, axes: MutableSet<String>, context: String?) {
            requireKnownPlaceholders(placeholders(template), axes, context)
        }

        private fun requireKnownPlaceholders(
            placeholders: MutableSet<String>, axes: MutableSet<String>, context: String?
        ) {
            val unknown: MutableSet<String?> = LinkedHashSet<String?>(placeholders)
            unknown.removeAll(axes)
            require(unknown.isEmpty()) { context + " references unknown axes: " + unknown }
        }

        private fun placeholders(template: String): MutableSet<String> {
            val placeholders: MutableSet<String> = LinkedHashSet<String>()
            val matcher: Matcher = PLACEHOLDER.matcher(template)
            while (matcher.find()) {
                placeholders.add(matcher.group(1))
            }
            return placeholders
        }

        private fun requireSimpleId(id: String, kind: String?) {
            require(!(id == null || id.isBlank() || !id.matches("[A-Za-z][A-Za-z0-9_.-]*".toRegex()))) { "Invalid " + kind + " ID: " + id }
        }

        private fun enumNames(values: MutableSet<out JsonNamed?>): String {
            return values.stream().map<String?> { obj: JsonNamed? -> obj!!.jsonName() }.sorted().toList().toString()
        }

        private fun field(name: String?, type: RecipeValueType?): RecipeField {
            return RecipeField(name, type, true)
        }

        private fun <E> enumFromJson(
            values: Array<E?>, name: String?, kind: String?
        ): E where E : Enum<E?>?, E : JsonNamed? {
            for (value in values) {
                if (value!!.jsonName() == name) {
                    return value
                }
            }
            throw IllegalArgumentException("Unknown " + kind + ": " + name)
        }

        private fun isString(value: JsonElement): Boolean {
            return value.isJsonPrimitive && value.asJsonPrimitive.isString
        }

        private fun isIntegerPrimitive(value: JsonElement): Boolean {
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
                return false
            }
            try {
                value.asBigDecimal.intValueExact()
                return true
            } catch (exception: ArithmeticException) {
                return false
            } catch (exception: NumberFormatException) {
                return false
            }
        }
    }
}
