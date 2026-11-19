// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import org.assertj.core.api.Assertions.assertThat

internal class CapturePolicyProtocolTest {
    @ParameterizedTest
    @CsvSource(["text,1,1404", "text,4,472", "phase,1,938", "boolean,1,6", "offset,1,12"])
    fun readsAreConditionalOnHitsAndUseNativeOffsets(mode: String, every: Int, expected: Int) {
        for (variant in List.of(RegexEngineVariant.SAFERE_STRING, RegexEngineVariant.SAFERE_UTF8)) {
            for (retained in booleanArrayOf(false, true)) {
                val inputs: List<Input?>? = List.of(
                    org.safere.benchmark.CapturePolicyProtocolTest.Companion.input("é é"),
                    org.safere.benchmark.CapturePolicyProtocolTest.Companion.input("x")
                )
                val task: CapturePolicyProtocol.Task =
                    CapturePolicyProtocol.bind(
                        CapturePolicyProtocol.compile("(é)", variant),
                        inputs,
                        1,
                        retained,
                        1,
                        Usage(mode, every)
                    )
                val actualExpected =
                    if (mode.equals("offset") && variant === RegexEngineVariant.SAFERE_UTF8) 18 else expected
                assertThat(task.run()).isEqualTo(actualExpected)
                assertThat(task.run()).isEqualTo(actualExpected)
            }
        }
    }

    @ParameterizedTest
    @CsvSource(["true,2,24", "false,24,0"])
    fun cursorsFollowTheDeclaredLifetimeAcrossCompleteCycles(
        retained: Boolean, expectedCreations: Int, expectedResets: Int
    ) {
        val creations = intArrayOf(0)
        val resets = intArrayOf(0)
        val reads = intArrayOf(0)
        val compiled: CapturePolicyProtocol.Compiled =
            CapturePolicyProtocol.Compiled { input ->
                creations[0]++
                object : Cursor() {
                    private var exhausted = false

                    @Override
                    fun find(): Boolean {
                        if (exhausted) {
                            return@Compiled false
                        }
                        exhausted = true
                        return@Compiled input.text.equals("hit")
                    }

                    @Override
                    fun reset() {
                        resets[0]++
                        exhausted = false
                    }

                    @Override
                    fun start(group: Int): Int {
                        return@Compiled 0
                    }

                    @Override
                    fun end(group: Int): Int {
                        return@Compiled 7
                    }

                    @Override
                    fun group(group: Int): String? {
                        reads[0]++
                        return@Compiled "payload"
                    }
                }
            }
        val task: CapturePolicyProtocol.Task =
            CapturePolicyProtocol.bind(
                compiled,
                List.of(
                    org.safere.benchmark.CapturePolicyProtocolTest.Companion.input("hit"),
                    org.safere.benchmark.CapturePolicyProtocolTest.Companion.input("miss")
                ),
                1,
                retained,
                2,
                Usage("phase", 1)
            )
        task.run()
        task.run()
        assertThat(creations[0]).isEqualTo(expectedCreations)
        assertThat(resets[0]).isEqualTo(expectedResets)
        assertThat(reads[0]).isEqualTo(8)
    }

    @Test
    @kotlin.Throws(Exception::class)
    fun investigationCasesKeepTheirHistoricalCycleChecksums() {
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
        val config: JsonObject = source.getAsJsonObject("configuration").getAsJsonObject("investigation")
        for (family in List.of("stack", "scheme")) {
            for (encoding in List.of("ascii", "multibyte")) {
                for (rate in List.of("all", "rare")) {
                    for (reuse in List.of("retained", "newPerInvocation")) {
                        for (engine in List.of("safere-string", "safere-utf8")) {
                            val id =
                                ("CapturePolicyBenchmark."
                                        + family
                                        + "."
                                        + encoding
                                        + ".128."
                                        + rate
                                        + "."
                                        + reuse
                                        + "@"
                                        + engine)
                            for (usage in config.getAsJsonObject("usages").keySet()) {
                                val selected: CapturePolicyCase =
                                    CapturePolicyCase(
                                        plan.resolve(id),
                                        { key -> Input(inputs.get(key)) },
                                        config,
                                        usage
                                    )
                                selected.validate()
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private fun input(text: String): Input {
            return Input(text.getBytes(StandardCharsets.UTF_8))
        }
    }
}
