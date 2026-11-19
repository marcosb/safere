// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import org.openjdk.jmh.infra.Blackhole
import org.safere.*
import org.safere.benchmark.DeclarativeBenchmarkPlan.*
import org.safere.crosscheck.Pattern.Companion.compile
import java.lang.AutoCloseable
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.LongAdder
import java.util.function.Predicate
import java.util.function.ToIntFunction

/** Prepared execution state for one SafeRE-specific declarative trial.  */
internal class SpecializedTrialRunner private constructor(private val task: Task, private val diagnostics: Boolean) :
    AutoCloseable {
    fun run(blackhole: Blackhole?) {
        task.run(blackhole)
    }

    override fun close() {
        if (diagnostics) {
            Pattern.Companion.setDiagnostics(SafeReMatchDiagnostics.Companion.NONE)
        }
    }

    private interface Task {
        fun run(blackhole: Blackhole?)
    }

    private class CountingSink : Utf8Sink {
        private var length = 0

        override fun append(bytes: ByteArray?, offset: Int, rangeLength: Int) {
            length += rangeLength
        }

        fun reset() {
            length = 0
        }

        fun length(): Int {
            return length
        }
    }

    private class AggregatingDiagnostics : SafeReMatchDiagnostics() {
        private val operations = LongAdder()
        private val matches = LongAdder()

        override fun onOperationCompleted(event: OperationDiagnostics) {
            operations.increment()
            matches.add(event.matchCount.toLong())
        }
    }

    companion object {
        private val NO_OP: SafeReMatchDiagnostics = object : SafeReMatchDiagnostics() {}

        fun prepare(trialId: String?): SpecializedTrialRunner {
            val trial: SpecializedBenchmarkPlan.Trial = SpecializedBenchmarkPlan.Companion.load().resolve(trialId)
            require(!(trial.variant !== RegexEngineVariant.SAFERE_STRING && trial.variant !== RegexEngineVariant.SAFERE_UTF8 && trial.variant !== RegexEngineVariant.SAFERE_UTF8_VECTOR)) { "Specialized SafeRE trial has wrong variant: " + trialId }
            trial.variant.validateScanProvider()
            val workload = trial.workload
            return when (workload.operation) {
                DeclarativeBenchmarkPlan.Operation.PATTERN_SET_MATCHES -> SpecializedTrialRunner(
                    patternSet(workload),
                    false
                )

                DeclarativeBenchmarkPlan.Operation.UTF8_CAPTURE_BOUNDS -> SpecializedTrialRunner(
                    captureBounds(workload),
                    false
                )

                DeclarativeBenchmarkPlan.Operation.UTF8_DECODE_FIND -> SpecializedTrialRunner(
                    decodeFind(workload),
                    false
                )

                DeclarativeBenchmarkPlan.Operation.UTF8_REPLACEMENT -> SpecializedTrialRunner(
                    utf8Replacement(workload),
                    false
                )

                DeclarativeBenchmarkPlan.Operation.FIND_IN_WINDOW -> SpecializedTrialRunner(
                    findInWindow(workload),
                    false
                )

                DeclarativeBenchmarkPlan.Operation.MATCHER_CONSTRUCTION -> SpecializedTrialRunner(
                    constructUtf8(workload),
                    false
                )

                DeclarativeBenchmarkPlan.Operation.ANALYZE_PATTERN -> SpecializedTrialRunner(analyze(workload), false)
                DeclarativeBenchmarkPlan.Operation.CACHED_ANALYSIS -> SpecializedTrialRunner(
                    cachedAnalysis(workload),
                    false
                )

                DeclarativeBenchmarkPlan.Operation.COMPILE_AND_ANALYZE -> SpecializedTrialRunner(
                    compileAndAnalyze(
                        workload
                    ), false
                )

                DeclarativeBenchmarkPlan.Operation.DIAGNOSTICS_FIND -> SpecializedTrialRunner(
                    diagnostics(
                        workload,
                        trial.variant
                    ), true
                )

                else -> throw IllegalArgumentException(
                    "Unsupported specialized operation: " + workload.operation
                )
            }
        }

        private fun patternSet(workload: ExpandedWorkload): Task {
            val anchor: String = stringArgument(workload, "anchor", "unanchored")
            val patternCount: Int = integerArgument(workload, "patternCount", workload.patterns.size())
            val patternAnchor =
                if (anchor == "anchored") PatternSet.Anchor.ANCHOR_START else PatternSet.Anchor.UNANCHORED
            val builder = PatternSet.Builder(patternAnchor)
            for (i in 0..<patternCount) {
                builder.add(workload.patterns.get(i % workload.patterns.size()))
            }
            val patternSet = builder.compile()
            val input: String? = inputString(workload)
            return SpecializedTrialRunner.Task { blackhole: Blackhole? -> blackhole!!.consume(patternSet.match(input)) }
        }

        private fun captureBounds(workload: ExpandedWorkload): Task {
            val pattern: Pattern = compile(workload.patterns.getFirst())
            val input: Utf8Input = utf8Input(workload)
            val groups =
                (workload.arguments.get("groups") as RecipeIntegerList)
                    .values.stream().mapToInt(ToIntFunction { obj: Int? -> obj.intValue() }).toArray()
            val includeEnd = stringArgument(workload, "bounds", "startEnd") == "startEnd"
            val expectedBounds =
                (workload.arguments.get("expectedBounds") as RecipeIntegerList)
                    .values
            val observed: MutableList<Int?> = ArrayList<Int?>()
            val verification = pattern.matcher(input)
            while (verification.find()) {
                for (group in groups) {
                    observed.add(verification.start(group))
                    observed.add(verification.end(group))
                }
            }
            check(observed == expectedBounds) { workload.id + " capture bounds mismatch: " + observed }
            return SpecializedTrialRunner.Task { blackhole: Blackhole? ->
                val matcher = pattern.matcher(input)
                var sum = 0
                while (matcher.find()) {
                    for (group in groups) {
                        sum += matcher.start(group)
                        if (includeEnd) {
                            sum += matcher.end(group)
                        }
                    }
                }
                blackhole!!.consume(sum)
            }
        }

        private fun decodeFind(workload: ExpandedWorkload): Task {
            val pattern: Pattern = compile(workload.patterns.getFirst())
            val bytes: ByteArray = BenchmarkData.Companion.get().getInputBytes(workload.inputIds.getFirst())
            return SpecializedTrialRunner.Task { blackhole: Blackhole? ->
                blackhole!!.consume(
                    pattern.matcher(
                        String(
                            bytes,
                            StandardCharsets.UTF_8
                        )
                    ).find()
                )
            }
        }

        private fun utf8Replacement(workload: ExpandedWorkload): Task {
            val pattern: Pattern = compile(workload.patterns.getFirst())
            val input: Utf8Input = utf8Input(workload)
            val replacement: Utf8Input =
                Utf8Input.Companion.validated(
                    stringArgument(workload, "replacement", "").getBytes(StandardCharsets.UTF_8)
                )
            val sink = CountingSink()
            return SpecializedTrialRunner.Task { blackhole: Blackhole? ->
                sink.reset()
                val matcher = pattern.matcher(input)
                matcher.replaceAll(sink, replacement)
                blackhole!!.consume(sink.length())
            }
        }

        private fun findInWindow(workload: ExpandedWorkload): Task {
            val storage: ByteArray = BenchmarkData.Companion.get().getInputBytes(workload.inputIds.getFirst())
            val region =
                workload.lifecycle.steps.stream()
                    .filter(Predicate { step: LifecycleStep? -> step!!.kind == LifecycleStepKind.REGION })
                    .findFirst()
                    .orElseThrow()
            val input: Utf8Input = Utf8Input.Companion.trusted(storage, region.start, region.end - region.start)
            val pattern: Pattern = compile(workload.patterns.getFirst())
            return SpecializedTrialRunner.Task { blackhole: Blackhole? -> blackhole!!.consume(pattern.find(input)) }
        }

        private fun constructUtf8(workload: ExpandedWorkload): Task {
            val bytes: ByteArray = BenchmarkData.Companion.get().getInputBytes(workload.inputIds.getFirst())
            val validated = stringArgument(workload, "mode", "trusted") == "validated"
            return SpecializedTrialRunner.Task { blackhole: Blackhole? ->
                blackhole.consume(
                    if (validated) Utf8Input.Companion.validated(
                        bytes
                    ) else Utf8Input.Companion.trusted(bytes)
                )
            }
        }

        private fun analyze(workload: ExpandedWorkload): Task {
            val regex: String? = workload.patterns.getFirst()
            return SpecializedTrialRunner.Task { blackhole: Blackhole? -> blackhole!!.consume(compile(regex!!)) }
        }

        private fun cachedAnalysis(workload: ExpandedWorkload): Task {
            val pattern: Pattern = compile(workload.patterns.getFirst())
            pattern.analysis()
            return SpecializedTrialRunner.Task { blackhole: Blackhole? -> blackhole!!.consume(pattern.analysis()) }
        }

        private fun compileAndAnalyze(workload: ExpandedWorkload): Task {
            val regex: String? = workload.patterns.getFirst()
            return SpecializedTrialRunner.Task { blackhole: Blackhole? -> blackhole.consume(compile(regex!!).analysis()) }
        }

        private fun diagnostics(
            workload: ExpandedWorkload, variant: RegexEngineVariant?
        ): Task {
            val listener: String = stringArgument(workload, "listener", "disabled")
            Pattern.Companion.setDiagnostics(
                when (listener) {
                    "disabled" -> SafeReMatchDiagnostics.Companion.NONE
                    "noop" -> NO_OP
                    "longAdder" -> AggregatingDiagnostics()
                    else -> throw IllegalArgumentException("Unknown diagnostics listener: " + listener)
                }
            )
            val pattern: Pattern = compile(workload.patterns.getFirst())
            val action: String = stringArgument(workload, "action", "find")
            if (variant !== RegexEngineVariant.SAFERE_STRING) {
                val input: Utf8Input = utf8Input(workload)
                return when (action) {
                    "find" -> SpecializedTrialRunner.Task { blackhole: Blackhole? ->
                        blackhole!!.consume(
                            pattern.matcher(
                                input
                            ).find()
                        )
                    }

                    "booleanFind" -> SpecializedTrialRunner.Task { blackhole: Blackhole? ->
                        blackhole!!.consume(
                            pattern.find(
                                input
                            )
                        )
                    }

                    "matches" -> SpecializedTrialRunner.Task { blackhole: Blackhole? ->
                        blackhole!!.consume(
                            pattern.matcher(
                                input
                            ).matches()
                        )
                    }

                    else -> throw IllegalArgumentException("Unknown UTF-8 diagnostics action: " + action)
                }
            }
            val input: String? = inputString(workload)
            val replacement: String = stringArgument(workload, "replacement", "")
            return when (action) {
                "find" -> SpecializedTrialRunner.Task { blackhole: Blackhole? ->
                    blackhole!!.consume(
                        pattern.matcher(
                            input
                        ).find()
                    )
                }

                "matches" -> SpecializedTrialRunner.Task { blackhole: Blackhole? ->
                    blackhole!!.consume(
                        pattern.matcher(
                            input
                        ).matches()
                    )
                }

                "replaceAll" -> SpecializedTrialRunner.Task { blackhole: Blackhole? ->
                    blackhole!!.consume(
                        pattern.matcher(
                            input
                        ).replaceAll(replacement)
                    )
                }

                else -> throw IllegalArgumentException("Unknown diagnostics action: " + action)
            }
        }

        private fun inputString(workload: ExpandedWorkload): String? {
            return BenchmarkData.Companion.get().getInputString(workload.inputIds.getFirst())
        }

        private fun utf8Input(workload: ExpandedWorkload): Utf8Input {
            return Utf8Input.Companion.trusted(
                BenchmarkData.Companion.get().getInputBytes(workload.inputIds.getFirst())
            )
        }

        private fun stringArgument(
            workload: ExpandedWorkload, name: String?, defaultValue: String
        ): String {
            val value = workload.arguments.get(name)
            return if (value == null) defaultValue else (value as RecipeString).value
        }

        private fun integerArgument(
            workload: ExpandedWorkload, name: String?, defaultValue: Int
        ): Int {
            val value = workload.arguments.get(name)
            return if (value == null) defaultValue else (value as RecipeInteger).value
        }
    }
}
