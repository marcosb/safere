// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import org.assertj.core.api.Assertions.assertThat

internal class CaptureCutoffBenchmarkTest {
    @Test
    @kotlin.Throws(Exception::class)
    fun schedulesAndCaptureTextAgreeWithJdkAcrossTheDeclaredCorpus() {
        var source: Path? = Path.of("investigations/capture-recovery/benchmark-data.json")
        if (!Files.exists(source)) {
            source = Path.of("safere-benchmarks").resolve(source)
        }
        val data: JsonObject? = JsonParser.parseString(Files.readString(source)).getAsJsonObject()
        val inputs: Map<String?, ByteArray?> = BenchmarkInputMaterializer.materialize(data)
        val plan: CrossEngineBenchmarkPlan =
            CrossEngineBenchmarkPlan.fromMaterialized(
                MaterializedExecutionPlan.parse(
                    ResolvedBenchmarkPlan.create(PatternProfiles.normalizeInline(data))
                )
                    .entriesForRunner("java")
            )
        for (trial in plan.trials(CrossEngineWorkload.TimingGroup.NANOSECONDS)) {
            if (!List.of(
                    RegexEngineVariant.SAFERE_STRING,
                    RegexEngineVariant.SAFERE_UTF8,
                    RegexEngineVariant.JDK_STRING
                )
                    .contains(trial.variant())
            ) {
                continue
            }
            val workload: CrossEngineWorkload = trial.workload()
            val size: Int = Integer.parseInt(workload.id().substring(workload.id().lastIndexOf('.') + 1))
            val prepared: List<RegexInput?>? =
                workload.inputKeys().stream()
                    .map(
                        { key ->
                            val bytes: ByteArray = inputs.get(key)!!
                            assertThat(bytes.size).isEqualTo(size)
                            (if (trial.variant() === RegexEngineVariant.SAFERE_UTF8)
                                RegexEngineVariant.utf8Input(bytes)
                            else
                                StringRegexInput(
                                    String(bytes, StandardCharsets.UTF_8)
                                ))
                        })
                    .toList()
            val captured = if (workload.id().contains(".stack.")) "com.example.Service" else "word"
            for (operation in List.of("matches", "lookingAt", "find")) {
                for (hitEvery in intArrayOf(0, 1, 16)) {
                    for (readEvery in intArrayOf(0, 1, 4)) {
                        var expected: Long = 0
                        var hits = 0
                        for (doc in 0..15) {
                            if (hitEvery > 0 && doc % hitEvery == 0) {
                                expected++
                                if (readEvery > 0 && hits++ % readEvery == 0) {
                                    expected = expected * 31 + captured.hashCode()
                                }
                            }
                        }
                        CaptureCutoffTask(
                            trial.variant(),
                            workload.patterns().getFirst(),
                            prepared,
                            operation,
                            16,
                            hitEvery,
                            readEvery
                        ).use { task ->
                            assertThat(task.run())
                                .`as`("%s/%s/%s/%s", trial.id(), operation, hitEvery, readEvery)
                                .isEqualTo(expected)
                            assertThat(task.run()).isEqualTo(expected)
                        }
                    }
                }
            }
        }
    }
}
