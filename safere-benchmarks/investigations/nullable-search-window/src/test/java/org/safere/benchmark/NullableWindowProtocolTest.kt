// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import java.nio.charset.StandardCharsets.UTF_8

internal class NullableWindowProtocolTest {
    @Test
    @kotlin.Throws(Exception::class)
    fun captureReplacementControlsProduceTheJdkOutputInBothRepresentations() {
        val data: JsonObject =
            org.safere.benchmark.NullableWindowProtocolTest.Companion.read(Path.of("investigations/nullable-search-window/benchmark-data.json"))
        val inputs: Map<String?, ByteArray?> = BenchmarkInputMaterializer.materialize(data)
        for (element in data.getAsJsonArray("workloads")) {
            val workload: JsonObject = element.getAsJsonObject()
            if (workload.get("operation").getAsString().equals("replaceAll")) {
                val inputId: String? = workload.getAsJsonArray("inputs").get(0).getAsString()
                org.safere.benchmark.NullableWindowProtocolTest.Companion.assertReplacement(
                    workload,
                    inputs.get(inputId)
                )
            }
        }
    }

    @Test
    @kotlin.Throws(Exception::class)
    fun protocolLinkReplacementOutputsAgreeWithTheJdkOnTheMeasuredInputs() {
        val data: JsonObject =
            org.safere.benchmark.NullableWindowProtocolTest.Companion.read(Path.of("benchmark-data.json"))
        val selected: JsonArray = JsonArray()
        for (element in data.getAsJsonArray("inputs")) {
            if (element
                    .getAsJsonObject()
                    .get("id")
                    .getAsString()
                    .startsWith("realWorldRegex.customProtocolLink.")
            ) {
                selected.add(element)
            }
        }
        data.add("inputs", selected)
        val inputs: Map<String?, ByteArray?> = BenchmarkInputMaterializer.materialize(data)
        var workload: JsonObject? = null
        for (element in data.getAsJsonArray("workloads")) {
            if (element
                    .getAsJsonObject()
                    .get("id")
                    .getAsString()
                    .startsWith("RealWorldRegexBenchmark.runBenchmark.customProtocolLinkReplace.")
            ) {
                workload = element.getAsJsonObject()
                break
            }
        }
        assertThat(workload).isNotNull()
        for (input in inputs.values()) {
            org.safere.benchmark.NullableWindowProtocolTest.Companion.assertReplacement(workload, input)
        }
    }

    companion object {
        private fun assertReplacement(workload: JsonObject, bytes: ByteArray?) {
            val regex: String? = workload.getAsJsonArray("patterns").get(0).getAsString()
            val replacement: String? = workload.getAsJsonObject("arguments").get("replacement").getAsString()
            val text = String(bytes, UTF_8)
            val expected: String? = java.util.regex.Pattern.compile(regex).matcher(text).replaceAll(replacement)
            for (variant in List.of(RegexEngineVariant.SAFERE_STRING, RegexEngineVariant.SAFERE_UTF8)) {
                val input: RegexInput? =
                    if (variant === RegexEngineVariant.SAFERE_UTF8)
                        RegexEngineVariant.utf8Input(bytes)
                    else
                        StringRegexInput(text)
                variant.compile(regex).use { compiled ->
                    assertThat(
                        compiled
                            .replaceAll(input, variant.prepareReplacement(replacement))
                            .validationValue()
                    )
                        .`as`("%s, %s", workload.get("id").getAsString(), variant)
                        .isEqualTo(expected)
                }
            }
        }

        @kotlin.Throws(Exception::class)
        private fun read(path: Path?): JsonObject {
            return JsonParser.parseString(Files.readString(path)).getAsJsonObject()
        }
    }
}
