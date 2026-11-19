// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import org.openjdk.jmh.infra.Blackhole
import org.safere.benchmark.DeclarativeBenchmarkPlan.*
import org.safere.benchmark.RegexEngineVariant.*
import java.util.*
import java.util.function.Supplier

/** Generic operation semantics and result consumption for cross-engine regex workloads.  */
internal enum class BenchmarkOperation(vararg requiredCapabilities: EngineCapability?) {
    MATCHES(EngineCapability.MATCHES),
    FIND(EngineCapability.FIND),
    LOOKING_AT(EngineCapability.LOOKING_AT),
    FIND_ALL_COUNT(EngineCapability.FIND),
    MATCHES_CORPUS(EngineCapability.MATCHES),
    MATCHES_GROUP_LENGTH_SUM(EngineCapability.MATCHES, EngineCapability.GROUP_TEXT),
    FIND_ALL_LENGTH_SUM(EngineCapability.FIND, EngineCapability.GROUP_TEXT),
    FIND_ALL_GROUP_LENGTH_SUM(EngineCapability.FIND, EngineCapability.GROUP_TEXT),
    CAPTURE_GROUPS(EngineCapability.MATCHES, EngineCapability.GROUP_TEXT),
    REPLACE_FIRST(EngineCapability.REPLACE),
    REPLACE_ALL(EngineCapability.REPLACE),
    REPLACE_ALL_LENGTH_SUM(EngineCapability.REPLACE),
    MANUAL_REPLACE_ALL(EngineCapability.APPEND_REPLACEMENT),
    SPLIT_LENGTH_SUM(EngineCapability.SPLIT),
    COMPILE(EngineCapability.COMPILE),
    COMPILE_AND_FIND(EngineCapability.COMPILE, EngineCapability.FIND),
    COMPILE_AND_MATCHES(EngineCapability.COMPILE, EngineCapability.MATCHES),
    FIND_ROTATING_UTF16(EngineCapability.FIND),
    COMPILE_AND_FIND_ROTATING_UTF16(EngineCapability.COMPILE, EngineCapability.FIND),
    MATCHER_RESET_FIND(EngineCapability.FIND, EngineCapability.MATCHER_RESET),
    MATCHER_REGION_FIND(EngineCapability.FIND, EngineCapability.REGIONS),
    MATCHER_REGION_MATCHES(EngineCapability.MATCHES, EngineCapability.REGIONS),
    MATCHER_REGION_TOKENIZE(EngineCapability.LOOKING_AT, EngineCapability.REGIONS),
    FIND_SAMPLED_GROUP(
        EngineCapability.FIND, EngineCapability.GROUP_PARTICIPATION, EngineCapability.MATCHER_RESET
    ),
    FIND_GROUP_PRESENT(EngineCapability.FIND, EngineCapability.GROUP_PARTICIPATION),
    FIND_GROUP(EngineCapability.FIND, EngineCapability.GROUP_TEXT);

    private val requiredCapabilities: EnumSet<EngineCapability?>

    init {
        this.requiredCapabilities = EnumSet.noneOf<EngineCapability?>(EngineCapability::class.java)
        for (capability in requiredCapabilities) {
            this.requiredCapabilities.add(capability)
        }
    }

    fun isSupportedBy(variant: RegexEngineVariant): Boolean {
        return variant.capabilities().containsAll(requiredCapabilities)
    }

    fun bind(
        variant: RegexEngineVariant,
        patternSources: MutableList<String?>,
        patterns: MutableList<CompiledRegex>,
        inputs: MutableList<RegexInput>,
        groups: IntArray,
        replacement: PreparedReplacement?,
        limit: Int,
        lifecycle: MatcherLifecycle,
        flagSet: String?,
        seed: Int,
        count: Int,
        mode: String
    ): BenchmarkTask {
        if (this == COMPILE) {
            return BenchmarkTask { blackhole: Blackhole? ->
                blackhole!!.consume(
                    variant.compileForBenchmark(
                        patternSources.first(),
                        flagSet
                    )
                )
            }
        }
        if (this == FIND_ROTATING_UTF16 || this == COMPILE_AND_FIND_ROTATING_UTF16) {
            return rotatingUtf16Task(
                variant,
                patternSources.first(),
                patterns,
                seed,
                count,
                this == COMPILE_AND_FIND_ROTATING_UTF16
            )
        }
        val pattern: CompiledRegex = patterns.first()
        val input: RegexInput = inputs.first()
        return when (this) {
            MATCHES -> BenchmarkTask { blackhole: Blackhole? ->
                blackhole!!.consume(
                    pattern.matches(
                        input
                    )
                )
            }

            FIND -> if (lifecycle.matcher == MatcherReuse.NONE)
                BenchmarkTask { blackhole: Blackhole? -> blackhole!!.consume(pattern.find(input)) }
            else
                BenchmarkTask { blackhole: Blackhole? -> blackhole!!.consume(pattern.matcher(input).find()) }

            LOOKING_AT -> BenchmarkTask { blackhole: Blackhole? ->
                blackhole!!.consume(
                    pattern.matcher(
                        input
                    ).lookingAt()
                )
            }

            FIND_ALL_COUNT -> BenchmarkTask { blackhole: Blackhole? ->
                blackhole!!.consume(
                    findAllCount(pattern.matcher(input))
                )
            }

            MATCHES_CORPUS -> BenchmarkTask { blackhole: Blackhole? ->
                blackhole!!.consume(
                    matchesCorpus(pattern, inputs)
                )
            }

            MATCHES_GROUP_LENGTH_SUM -> BenchmarkTask { blackhole: Blackhole? ->
                blackhole!!.consume(
                    matchesGroupLengthSum(pattern, inputs, groups)
                )
            }

            FIND_ALL_LENGTH_SUM -> BenchmarkTask { blackhole: Blackhole? ->
                blackhole!!.consume(
                    findAllLengthSum(pattern.matcher(input))
                )
            }

            FIND_ALL_GROUP_LENGTH_SUM -> BenchmarkTask { blackhole: Blackhole? ->
                blackhole!!.consume(
                    findAllGroupLengthSum(pattern.matcher(input), groups)
                )
            }

            CAPTURE_GROUPS -> BenchmarkTask { blackhole: Blackhole? ->
                blackhole!!.consume(
                    captureGroups(pattern.matcher(input), groups)
                )
            }

            REPLACE_FIRST -> BenchmarkTask { blackhole: Blackhole? ->
                pattern.replaceFirst(
                    input,
                    replacement
                ).consume(blackhole)
            }

            REPLACE_ALL -> BenchmarkTask { blackhole: Blackhole? ->
                pattern.replaceAll(
                    input,
                    replacement
                ).consume(blackhole)
            }

            REPLACE_ALL_LENGTH_SUM -> BenchmarkTask { blackhole: Blackhole? ->
                blackhole!!.consume(
                    replaceAllLengthSum(patterns, input, replacement)
                )
            }

            MANUAL_REPLACE_ALL -> BenchmarkTask { blackhole: Blackhole? ->
                pattern.manualReplaceAll(
                    input,
                    replacement
                ).consume(blackhole)
            }

            SPLIT_LENGTH_SUM -> BenchmarkTask { blackhole: Blackhole? ->
                blackhole!!.consume(
                    splitLengthSum(pattern.split(input, limit))
                )
            }

            COMPILE_AND_FIND -> BenchmarkTask { blackhole: Blackhole? ->
                variant.compile(patternSources.first()).use { compiled ->
                    blackhole!!.consume(compiled.find(input))
                }
            }

            COMPILE_AND_MATCHES -> BenchmarkTask { blackhole: Blackhole? ->
                variant.compile(patternSources.first()).use { compiled ->
                    blackhole!!.consume(compiled.matches(input))
                }
            }

            MATCHER_RESET_FIND -> {
                val retainedMatcher =
                    if (lifecycle.matcher == MatcherReuse.RETAINED)
                        pattern.matcher(input)
                    else
                        null
                BenchmarkTask { blackhole: Blackhole? ->
                    val matcher =
                        if (retainedMatcher != null) retainedMatcher else pattern.matcher(input)
                    matcher.reset()
                    blackhole!!.consume(findAllCount(matcher))
                }
            }

            MATCHER_REGION_FIND -> {
                val retainedMatcher =
                    if (lifecycle.matcher == MatcherReuse.RETAINED)
                        pattern.matcher(input)
                    else
                        null
                val region: LifecycleStep = region(lifecycle)
                BenchmarkTask { blackhole: Blackhole? ->
                    val matcher =
                        if (retainedMatcher != null) retainedMatcher else pattern.matcher(input)
                    matcher.region(region.start, region.end)
                    blackhole!!.consume(matcher.find())
                }
            }

            MATCHER_REGION_MATCHES -> {
                val retainedMatcher =
                    if (lifecycle.matcher == MatcherReuse.RETAINED)
                        pattern.matcher(input)
                    else
                        null
                val region: LifecycleStep = region(lifecycle)
                BenchmarkTask { blackhole: Blackhole? ->
                    val matcher =
                        if (retainedMatcher != null) retainedMatcher else pattern.matcher(input)
                    matcher.region(region.start, region.end)
                    blackhole!!.consume(matcher.matches())
                }
            }

            MATCHER_REGION_TOKENIZE -> {
                val retainedMatcher =
                    if (lifecycle.matcher == MatcherReuse.RETAINED)
                        pattern.matcher(input)
                    else
                        null
                BenchmarkTask { blackhole: Blackhole? ->
                    blackhole!!.consume(
                        tokenize(
                            if (retainedMatcher != null) retainedMatcher else pattern.matcher(input),
                            input.nativeLength(),
                            mode
                        )
                    )
                }
            }

            FIND_SAMPLED_GROUP -> {
                val capture = mode == "capture"
                primeCaptureDemand(pattern, inputs, groups[0], capture)
                val retained =
                    if (lifecycle.matcher == MatcherReuse.RETAINED)
                        pattern.matcher(input)
                    else
                        null
                val invocation = intArrayOf(0)
                BenchmarkTask { blackhole: Blackhole? ->
                    val matcher =
                        if (retained == null) pattern.matcher(input) else retained
                    if (retained != null) {
                        matcher.reset()
                    }
                    val readGroup = capture && invocation[0] == 0
                    invocation[0] = (invocation[0] + 1) % count
                    blackhole!!.consume(findSampledGroup(matcher, groups[0], readGroup))
                }
            }

            FIND_GROUP_PRESENT -> BenchmarkTask { blackhole: Blackhole? ->
                blackhole!!.consume(
                    findGroupPresent(pattern.matcher(input), groups[0])
                )
            }

            FIND_GROUP -> BenchmarkTask { blackhole: Blackhole? ->
                blackhole!!.consume(
                    findGroup(
                        pattern.matcher(input),
                        groups[0]
                    )
                )
            }

            COMPILE -> throw AssertionError()
            FIND_ROTATING_UTF16, COMPILE_AND_FIND_ROTATING_UTF16 -> throw AssertionError()
        }
    }

    fun execute(
        variant: RegexEngineVariant,
        patternSources: MutableList<String?>,
        patterns: MutableList<CompiledRegex>,
        inputs: MutableList<RegexInput>,
        groups: IntArray,
        replacement: PreparedReplacement?,
        limit: Int,
        lifecycle: MatcherLifecycle,
        flagSet: String?,
        seed: Int,
        count: Int,
        mode: String
    ): Any? {
        if (this == COMPILE) {
            return true
        }
        if (this == FIND_ROTATING_UTF16 || this == COMPILE_AND_FIND_ROTATING_UTF16) {
            val input: String? = rotatingUtf16Inputs(seed, count)[0]
            if (this == FIND_ROTATING_UTF16) {
                return patterns.first().find(StringRegexInput(input))
            }
            variant.compile(patternSources.first()).use { compiled ->
                return compiled.find(StringRegexInput(input))
            }
        }
        val pattern: CompiledRegex = patterns.first()
        val input: RegexInput = inputs.first()
        return when (this) {
            MATCHES -> pattern.matches(input)
            FIND -> if (lifecycle.matcher == MatcherReuse.NONE)
                pattern.find(input)
            else
                pattern.matcher(input).find()

            LOOKING_AT -> pattern.matcher(input).lookingAt()
            FIND_ALL_COUNT -> findAllCount(pattern.matcher(input))
            MATCHES_CORPUS -> matchesCorpus(pattern, inputs)
            MATCHES_GROUP_LENGTH_SUM -> matchesGroupLengthSum(pattern, inputs, groups)
            FIND_ALL_LENGTH_SUM -> findAllLengthSum(pattern.matcher(input))
            FIND_ALL_GROUP_LENGTH_SUM -> findAllGroupLengthSum(pattern.matcher(input), groups)
            CAPTURE_GROUPS -> captureGroups(pattern.matcher(input), groups)
            REPLACE_FIRST -> pattern.replaceFirst(input, replacement).validationValue()
            REPLACE_ALL -> pattern.replaceAll(input, replacement).validationValue()
            REPLACE_ALL_LENGTH_SUM -> replaceAllLengthSum(patterns, input, replacement)
            MANUAL_REPLACE_ALL -> pattern.manualReplaceAll(input, replacement).validationValue()
            SPLIT_LENGTH_SUM -> splitLengthSum(pattern.split(input, limit))
            COMPILE_AND_FIND -> {
                variant.compile(patternSources.first()).use { compiled ->
                    compiled.find(input)
                }
            }

            COMPILE_AND_MATCHES -> {
                variant.compile(patternSources.first()).use { compiled ->
                    compiled.matches(input)
                }
            }

            MATCHER_RESET_FIND -> {
                val matcher = pattern.matcher(input)
                matcher.reset()
                findAllCount(matcher)
            }

            MATCHER_REGION_FIND -> {
                val matcher = pattern.matcher(input)
                val region: LifecycleStep = region(lifecycle)
                matcher.region(region.start, region.end)
                matcher.find()
            }

            MATCHER_REGION_MATCHES -> {
                val matcher = pattern.matcher(input)
                val region: LifecycleStep = region(lifecycle)
                matcher.region(region.start, region.end)
                matcher.matches()
            }

            MATCHER_REGION_TOKENIZE -> tokenize(pattern.matcher(input), input.nativeLength(), mode)
            FIND_SAMPLED_GROUP -> {
                primeCaptureDemand(pattern, inputs, groups[0], mode == "capture")
                findSampledGroup(pattern.matcher(input), groups[0], mode == "capture")
            }

            FIND_GROUP_PRESENT -> findGroupPresent(pattern.matcher(input), groups[0])
            FIND_GROUP -> findGroup(pattern.matcher(input), groups[0])
            COMPILE -> throw AssertionError()
            FIND_ROTATING_UTF16, COMPILE_AND_FIND_ROTATING_UTF16 -> throw AssertionError()
        }
    }

    internal fun interface BenchmarkTask {
        fun run(blackhole: Blackhole?)
    }

    companion object {
        fun fromDeclarative(operation: Operation): BenchmarkOperation {
            return when (operation) {
                Operation.MATCHES -> MATCHES
                Operation.FIND -> FIND
                Operation.LOOKING_AT -> LOOKING_AT
                Operation.FIND_ALL_COUNT -> FIND_ALL_COUNT
                Operation.MATCHES_CORPUS -> MATCHES_CORPUS
                Operation.MATCHES_GROUP_LENGTH_SUM -> MATCHES_GROUP_LENGTH_SUM
                Operation.FIND_ALL_LENGTH_SUM -> FIND_ALL_LENGTH_SUM
                Operation.FIND_ALL_GROUP_LENGTH_SUM -> FIND_ALL_GROUP_LENGTH_SUM
                Operation.CAPTURE_GROUPS -> CAPTURE_GROUPS
                Operation.REPLACE_FIRST -> REPLACE_FIRST
                Operation.REPLACE_ALL -> REPLACE_ALL
                Operation.REPLACE_ALL_LENGTH_SUM -> REPLACE_ALL_LENGTH_SUM
                Operation.MANUAL_REPLACE_ALL -> MANUAL_REPLACE_ALL
                Operation.SPLIT_LENGTH_SUM -> SPLIT_LENGTH_SUM
                Operation.COMPILE -> COMPILE
                Operation.COMPILE_AND_FIND -> COMPILE_AND_FIND
                Operation.COMPILE_AND_MATCHES -> COMPILE_AND_MATCHES
                Operation.FIND_ROTATING_UTF16 -> FIND_ROTATING_UTF16
                Operation.COMPILE_AND_FIND_ROTATING_UTF16 -> COMPILE_AND_FIND_ROTATING_UTF16
                Operation.MATCHER_RESET_FIND -> MATCHER_RESET_FIND
                Operation.MATCHER_REGION_FIND -> MATCHER_REGION_FIND
                Operation.MATCHER_REGION_MATCHES -> MATCHER_REGION_MATCHES
                Operation.MATCHER_REGION_TOKENIZE -> MATCHER_REGION_TOKENIZE
                Operation.FIND_SAMPLED_GROUP -> FIND_SAMPLED_GROUP
                Operation.FIND_GROUP_PRESENT -> FIND_GROUP_PRESENT
                Operation.FIND_GROUP -> FIND_GROUP
                else -> throw IllegalArgumentException(
                    "Operation is not implemented by the cross-engine runner: " + operation
                )
            }
        }

        private fun tokenize(matcher: MatchCursor, length: Int, mode: String): Int {
            val independent = mode.startsWith("independent")
            if (independent) {
                matcher.reset()
            }
            var count = 0
            var pos = 0
            while (pos < length) {
                if (!independent) {
                    matcher.region(pos, length)
                }
                val found = if (mode.isEmpty()) matcher.lookingAt() else matcher.find()
                check(found) { "Tokenizer failed at " + pos }
                val end = matcher.end(0)
                if (mode.endsWith("Bounds")) {
                    check(matcher.start(0) == pos) { "Tokenizer skipped input" }
                } else if (mode.endsWith("Capture")) {
                    check(!(matcher.start(1) != pos || matcher.end(1) <= pos || matcher.end(1) > end)) { "Tokenizer capture mismatch" }
                }
                check(end > pos) { "Tokenizer did not advance at " + pos }
                pos = end
                count++
            }
            return count
        }

        private fun rotatingUtf16Task(
            variant: RegexEngineVariant,
            patternSource: String?,
            patterns: MutableList<CompiledRegex>,
            seed: Int,
            count: Int,
            compileEachInvocation: Boolean
        ): BenchmarkTask {
            val inputs: Array<String?> = rotatingUtf16Inputs(seed, count)
            val index = intArrayOf(0)
            if (!compileEachInvocation) {
                val pattern: CompiledRegex = patterns.first()
                return BenchmarkTask { blackhole: Blackhole? ->
                    val input = inputs[index[0]]
                    index[0] = (index[0] + 1) % inputs.size
                    blackhole!!.consume(pattern.find(StringRegexInput(input)))
                }
            }
            return BenchmarkTask { blackhole: Blackhole? ->
                val input = inputs[index[0]]
                index[0] = (index[0] + 1) % inputs.size
                variant.compile(patternSource).use { compiled ->
                    blackhole!!.consume(compiled.find(StringRegexInput(input)))
                }
            }
        }

        private fun rotatingUtf16Inputs(seed: Int, count: Int): Array<String?> {
            val random = Random(seed.toLong())
            val inputs = arrayOfNulls<String>(count)
            for (i in 0..<count) {
                inputs[i] = String(charArrayOf(random.nextInt().toChar()))
            }
            return inputs
        }

        private fun region(
            lifecycle: MatcherLifecycle
        ): LifecycleStep {
            return lifecycle.steps.stream()
                .filter { step: LifecycleStep? -> step!!.kind == LifecycleStepKind.REGION }
                .findFirst()
                .orElseThrow<IllegalArgumentException?>(
                    Supplier { IllegalArgumentException("Matcher region operation requires region") })
        }

        private fun findAllCount(matcher: MatchCursor): Int {
            var count = 0
            while (matcher.find()) {
                count++
            }
            return count
        }

        private fun matchesCorpus(
            pattern: CompiledRegex, inputs: MutableList<RegexInput>
        ): Int {
            var count = 0
            for (input in inputs) {
                if (pattern.matches(input)) {
                    count++
                }
            }
            return count
        }

        private fun matchesGroupLengthSum(
            pattern: CompiledRegex,
            inputs: MutableList<RegexInput>,
            groups: IntArray
        ): Int {
            var sum = 0
            for (input in inputs) {
                val matcher = pattern.matcher(input)
                if (matcher.matches()) {
                    sum += groupLengthSum(matcher, groups)
                }
            }
            return sum
        }

        private fun findAllLengthSum(matcher: MatchCursor): Int {
            var sum = 0
            while (matcher.find()) {
                sum += matcher.group(0).length
            }
            return sum
        }

        private fun findAllGroupLengthSum(matcher: MatchCursor, groups: IntArray): Int {
            var sum = 0
            while (matcher.find()) {
                sum += groupLengthSum(matcher, groups)
            }
            return sum
        }

        private fun groupLengthSum(matcher: MatchCursor, groups: IntArray): Int {
            var sum = 0
            for (group in groups) {
                val value = matcher.group(group)
                if (value != null) {
                    sum += value.size
                }
            }
            return sum
        }

        private fun captureGroups(matcher: MatchCursor, groups: IntArray): String? {
            if (!matcher.matches()) {
                return ""
            }
            if (groups.size == 1) {
                return matcher.group(groups[0])
            }
            if (groups.size == 3) {
                return matcher.group(groups[0]) + matcher.group(groups[1]) + matcher.group(groups[2])
            }
            val result = StringBuilder()
            for (group in groups) {
                result.append(matcher.group(group))
            }
            return result.toString()
        }

        private fun replaceAllLengthSum(
            patterns: MutableList<CompiledRegex>,
            input: RegexInput?,
            replacement: PreparedReplacement?
        ): Int {
            var sum = 0
            for (pattern in patterns) {
                sum += pattern.replaceAll(input, replacement).nativeLength()
            }
            return sum
        }

        private fun splitLengthSum(parts: Array<String>): Int {
            var sum = parts.size
            for (part in parts) {
                sum += part.size
            }
            return sum
        }

        private fun primeCaptureDemand(
            pattern: CompiledRegex,
            inputs: MutableList<RegexInput>,
            group: Int,
            capture: Boolean
        ) {
            if (capture && inputs.size > 1) {
                val primer = pattern.matcher(inputs.get(1))
                require(!(!primer.matches() || !primer.groupParticipated(group))) { "Capture primer must match and participate" }
            }
        }

        private fun findSampledGroup(
            matcher: MatchCursor, group: Int, readGroup: Boolean
        ): Boolean {
            return matcher.find() && (!readGroup || matcher.groupParticipated(group))
        }

        private fun findGroupPresent(matcher: MatchCursor, group: Int): Boolean {
            return matcher.find() && matcher.groupParticipated(group)
        }

        private fun findGroup(matcher: MatchCursor, group: Int): String? {
            if (!matcher.find()) {
                return null
            }
            return matcher.group(group)
        }
    }
}
