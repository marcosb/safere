// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import org.openjdk.jmh.infra.Blackhole
import org.safere.benchmark.CrossEngineWorkload.TimingGroup
import org.safere.benchmark.RegexEngineVariant.CompiledRegex
import java.lang.AutoCloseable
import java.util.List

/** Prepared execution state for one supported cross-engine benchmark trial.  */
internal class CrossEngineTrialRunner private constructor(
    private val patterns: MutableList<CompiledRegex>,
    private val task: BenchmarkOperation.BenchmarkTask
) : AutoCloseable {
    fun run(blackhole: Blackhole?) {
        task.run(blackhole)
    }

    override fun close() {
        close(patterns)
    }

    companion object {
        fun prepare(
            trialId: String, expectedTimingGroup: TimingGroup?
        ): CrossEngineTrialRunner {
            val data: BenchmarkData = BenchmarkData.Companion.get()
            val trial: CrossEngineBenchmarkPlan.Trial = CrossEngineBenchmarkPlan.Companion.load().resolve(trialId)
            return prepare(data, trial, expectedTimingGroup, true)
        }

        fun prepareColdStart(
            trialId: String, expectedTimingGroup: TimingGroup?
        ): CrossEngineTrialRunner {
            val data: BenchmarkData = BenchmarkData.Companion.get()
            val trial: CrossEngineBenchmarkPlan.Trial = CrossEngineBenchmarkPlan.Companion.load().resolve(trialId)
            return prepareColdStart(data, trial, expectedTimingGroup)
        }

        fun prepareColdStart(
            data: BenchmarkData?,
            trial: CrossEngineBenchmarkPlan.Trial,
            expectedTimingGroup: TimingGroup?
        ): CrossEngineTrialRunner {
            require(trial.workload.operation == BenchmarkOperation.COMPILE) { trial.id() + " cold-start execution requires the compile operation" }
            return prepare(data, trial, expectedTimingGroup, false)
        }

        private fun prepare(
            data: BenchmarkData?,
            trial: CrossEngineBenchmarkPlan.Trial,
            expectedTimingGroup: TimingGroup?,
            validateBeforeMeasurement: Boolean
        ): CrossEngineTrialRunner {
            trial.variant.validateScanProvider()
            val trialId = trial.id()
            val workload = trial.workload
            require(workload.timingGroup == expectedTimingGroup) { trialId + " belongs to " + workload.timingGroup + ", not " + expectedTimingGroup }

            val inputs =
                trial.variant.prepareInputs(data, workload.inputKeys)
            val replacement =
                trial.variant.prepareReplacement(workload.replacement)
            val patternSources = workload.patterns
            val patterns: MutableList<CompiledRegex> = ArrayList<CompiledRegex>()
            try {
                if (workload.operation != BenchmarkOperation.COMPILE) {
                    for (pattern in patternSources) {
                        patterns.add(trial.variant.compile(pattern))
                    }
                }
                if (validateBeforeMeasurement) {
                    val actual =
                        workload
                            .operation
                            .execute(
                                trial.variant,
                                patternSources,
                                patterns,
                                inputs,
                                workload.groups,
                                replacement,
                                workload.limit,
                                workload.lifecycle,
                                workload.flagSet,
                                workload.seed,
                                workload.count,
                                workload.mode
                            )
                    trial.validate(actual)
                }
                val task =
                    workload
                        .operation
                        .bind(
                            trial.variant,
                            patternSources,
                            patterns,
                            inputs,
                            workload.groups,
                            replacement,
                            workload.limit,
                            workload.lifecycle,
                            workload.flagSet,
                            workload.seed,
                            workload.count,
                            workload.mode
                        )
                return CrossEngineTrialRunner(List.copyOf<CompiledRegex?>(patterns), task)
            } catch (exception: RuntimeException) {
                close(patterns)
                throw exception
            } catch (exception: Error) {
                close(patterns)
                throw exception
            }
        }

        private fun close(patterns: MutableList<CompiledRegex>) {
            for (pattern in patterns.reversed()) {
                pattern.close()
            }
        }
    }
}
