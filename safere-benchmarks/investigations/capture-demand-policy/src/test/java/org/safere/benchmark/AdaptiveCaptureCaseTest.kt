// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import org.assertj.core.api.Assertions.assertThat

internal class AdaptiveCaptureCaseTest {
    @Test
    fun orderingChangesClusteringWithoutChangingHitCounts() {
        for (every in intArrayOf(0, 1, 2, 16)) {
            for (order in List.of("periodic", "blocked", "shuffled")) {
                val actual: IntArray? = AdaptiveCaptureCase.schedule(512, every, order, 64, 19)
                assertThat(Arrays.stream(actual).filter({ slot -> slot === 0 }).count())
                    .isEqualTo(if (every == 0) 0 else 512 / every)
                assertThat(actual).isEqualTo(AdaptiveCaptureCase.schedule(512, every, order, 64, 19))
            }
        }
        assertThat(AdaptiveCaptureCase.schedule(512, 2, "shuffled", 64, 19))
            .isNotEqualTo(AdaptiveCaptureCase.schedule(512, 2, "shuffled", 64, 20))
    }

    @Test
    @kotlin.Throws(Exception::class)
    fun everyDeclaredLifetimeAndPhaseScheduleMatchesIndependentJdkCaptures() {
        var path: Path? = Path.of("investigations/capture-demand-policy/benchmark-data.json")
        if (!Files.exists(path)) {
            path = Path.of("safere-benchmarks").resolve(path)
        }
        val source: JsonObject = JsonParser.parseString(Files.readString(path)).getAsJsonObject()
        val inputs: Map<String?, ByteArray?> = BenchmarkInputMaterializer.materialize(source)
        val plan: CrossEngineBenchmarkPlan =
            CrossEngineBenchmarkPlan.fromMaterialized(
                MaterializedExecutionPlan.parse(ResolvedBenchmarkPlan.create(source))
                    .entriesForRunner("java")
            )
        val config: JsonObject =
            source
                .getAsJsonObject("configuration")
                .getAsJsonObject("investigation")
                .getAsJsonObject("adaptive")
        for (scenario in config.getAsJsonObject("scenarios").keySet()) {
            for (representation in List.of("string", "utf8")) {
                val selected: AdaptiveCaptureCase =
                    AdaptiveCaptureCase(
                        config,
                        scenario,
                        representation,
                        plan::resolve,
                        { key -> Input(inputs.get(key)) })
                selected.validate()
                val steps: AdaptiveCaptureCase.Task = selected.task(false)
                var sampled: Long = 0
                for (i in 0..1535) {
                    sampled += steps.runOne()
                }
                assertThat(sampled).isEqualTo(selected.oracle(false))
                val task: AdaptiveCaptureCase.Task = selected.task(false)
                assertThat(task.run(null))
                    .`as`(scenario.toString() + " " + representation)
                    .isEqualTo(selected.oracle(false))
                assertThat(task.run(null)).isEqualTo(selected.oracle(false))
                val counters: AdaptivePhaseCounters = AdaptivePhaseCounters()
                assertThat(task.run(counters)).isEqualTo(selected.oracle(false))
                assertThat(counters.phase0Documents).isEqualTo(512)
                assertThat(counters.phase1Documents).isEqualTo(512)
                assertThat(counters.phase2Documents).isEqualTo(512)
                val shared: AdaptiveCaptureCase.Shared = selected.shared()
                assertThat(shared.task(true).run(null)).isEqualTo(selected.oracle(true))
                assertThat(shared.task(false).run(null)).isEqualTo(selected.oracle(false))
            }
        }
    }
}
