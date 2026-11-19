// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.assertj.core.api.ThrowingConsumer
import org.assertj.core.api.iterable.ThrowingExtractor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.safere.benchmark.DeclarativeBenchmarkPlan.ExpandedWorkload
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*
import java.util.function.Supplier
import java.util.stream.Collectors

internal class BenchmarkInputMaterializerTest {
    @TempDir
    var tempDirectory: Path? = null

    @Test
    @Throws(IOException::class)
    fun declaredBenchmarkCorpusMaterializesDeterministically() {
        val benchmarkData =
            JsonParser.parseString(Files.readString(Path.of("benchmark-data.json"))).getAsJsonObject()

        val first: MutableMap<String?, ByteArray?> = materialize(benchmarkData)
        val second: MutableMap<String?, ByteArray?> = materialize(benchmarkData)

        val declaredInputIds: MutableSet<String?> =
            DeclarativeBenchmarkPlan.Companion.parseInputDeclarations(benchmarkData.getAsJsonArray("inputs"))
                .keys
        Assertions.assertThat<String?, ByteArray?>(first).hasSizeGreaterThanOrEqualTo(MINIMUM_MATERIALIZED_INPUTS)
        Assertions.assertThat<String?>(first.keys).containsExactlyElementsOf(declaredInputIds)
        Assertions.assertThat<String?>(second.keys).containsExactlyElementsOf(first.keys)
        first.forEach { (id: String?, bytes: ByteArray?) ->
            Assertions.assertThat(second.get(id)).`as`(id).containsExactly(*bytes)
        }
        Assertions.assertThat(text(first, "crossEngine.RegexBenchmark.literalMatch.input")).isEqualTo("hello")
        Assertions.assertThat(text(first, "pathological.pattern.10")).isEqualTo("a?".repeat(10) + "a".repeat(10))
        Assertions.assertThat(text(first, "searchScaling.success.1024"))
            .hasSize(1050)
            .endsWith("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        Assertions.assertThat(text(first, "fanout.unicode.1024")).hasSize(1024)
    }

    @Test
    @Throws(IOException::class, NoSuchAlgorithmException::class)
    fun fileRecipePreservesExactUtf8Bytes() {
        val source = "fn main() {value}\nπ\n".toByteArray(StandardCharsets.UTF_8)
        Files.createDirectories(tempDirectory!!.resolve("data"))
        Files.write(tempDirectory!!.resolve("data/source.txt"), source)
        val checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source))

        val materialized: MutableMap<String?, ByteArray?> =
            BenchmarkInputMaterializer.Companion.materialize(
                fileInput("data/source.txt", checksum), tempDirectory
            )

        Assertions.assertThat(materialized.get("source.code")).containsExactly(*source)
    }

    @Test
    @Throws(IOException::class)
    fun fileRecipeRejectsChecksumMismatchAndPathTraversal() {
        Files.createDirectories(tempDirectory!!.resolve("data"))
        Files.writeString(tempDirectory!!.resolve("data/source.txt"), "fixture")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                BenchmarkInputMaterializer.Companion.materialize(
                    fileInput("data/source.txt", "0".repeat(64)), tempDirectory
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("File recipe SHA-256 mismatch: data/source.txt")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                BenchmarkInputMaterializer.Companion.materialize(
                    fileInput("data/../source.txt", "0".repeat(64)), tempDirectory
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("File recipe path must stay within data: data/../source.txt")
    }

    @Test
    fun appendRecipeRejectsUnknownInput() {
        val benchmarkData =
            JsonParser.parseString(
                """
                {
                  "schemaVersion": 2,
                  "inputs": [{
                    "id": "derived",
                    "recipe": {"kind": "appendInput", "input": "missing", "suffix": "!"},
                    "shared": true
                  }]
                }
                
                """.trimIndent()
            )
                .getAsJsonObject()

        Assertions.assertThatThrownBy(ThrowingCallable { materialize(benchmarkData) })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Input recipe references unknown materialized input: missing")
    }

    @Test
    fun appendRecipeRejectsDependencyCycle() {
        val benchmarkData =
            JsonParser.parseString(
                """
                {
                  "schemaVersion": 2,
                  "inputs": [
                    {
                      "id": "first",
                      "recipe": {"kind": "appendInput", "input": "second", "suffix": "!"},
                      "shared": true
                    },
                    {
                      "id": "second",
                      "recipe": {"kind": "appendInput", "input": "first", "suffix": "?"},
                      "shared": true
                    }
                  ]
                }
                
                """.trimIndent()
            )
                .getAsJsonObject()

        Assertions.assertThatThrownBy(ThrowingCallable { materialize(benchmarkData) })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Cyclic materialized input recipe dependency: first -> second -> first")
    }

    @Test
    fun deeplyChainedAppendRecipesMaterializeWithoutUsingTheCallStack() {
        val dependencyCount = 5000
        val benchmarkData = JsonObject()
        benchmarkData.addProperty("schemaVersion", 2)
        val inputs = JsonArray()
        for (index in dependencyCount downTo 1) {
            val declaration = JsonObject()
            declaration.addProperty("id", "input" + index)
            val recipe = JsonObject()
            recipe.addProperty("kind", "appendInput")
            recipe.addProperty("input", "input" + (index - 1))
            recipe.addProperty("suffix", "x")
            declaration.add("recipe", recipe)
            declaration.addProperty("shared", true)
            inputs.add(declaration)
        }
        val base = JsonObject()
        base.addProperty("id", "input0")
        val baseRecipe = JsonObject()
        baseRecipe.addProperty("kind", "literal")
        baseRecipe.addProperty("text", "")
        base.add("recipe", baseRecipe)
        base.addProperty("shared", true)
        inputs.add(base)
        benchmarkData.add("inputs", inputs)

        val materialized: MutableMap<String?, ByteArray?> = materialize(benchmarkData)

        Assertions.assertThat(text(materialized, "input" + dependencyCount))
            .isEqualTo("x".repeat(dependencyCount))
    }

    @Test
    @Throws(Exception::class)
    fun manifestAttributesInputsAndRecordsExactEncodingMetadata() {
        val declaredData =
            JsonParser.parseString(Files.readString(Path.of("benchmark-data.json"))).getAsJsonObject()
        BenchmarkInputMaterializer.Companion.main(
            arrayOf<String>(
                Path.of(".").toAbsolutePath().normalize().toString(), tempDirectory.toString()
            )
        )

        val manifest =
            JsonParser.parseString(Files.readString(tempDirectory!!.resolve("manifest.json")))
                .getAsJsonObject()
        val entry =
            manifest
                .getAsJsonObject("inputs")
                .getAsJsonObject("crossEngine.RegexBenchmark.literalMatch.input")

        Assertions.assertThat(entry.get("file").getAsString())
            .isEqualTo("crossEngine/RegexBenchmark/literalMatch/input.txt")
        Assertions.assertThat(entry.get("shared").getAsBoolean()).isTrue()
        Assertions.assertThat(entry.get("utf8Bytes").getAsInt()).isEqualTo(5)
        Assertions.assertThat(entry.get("utf16CodeUnits").getAsInt()).isEqualTo(5)
        Assertions.assertThat(entry.get("unicodeScalars").getAsInt()).isEqualTo(5)
        Assertions.assertThat(entry.get("sha256").getAsString())
            .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
        val resolvedData = manifest.getAsJsonObject("benchmarkData")
        // These are historical floors: adding syntax alternates must not require a test update.
        Assertions.assertThat<JsonElement?>(resolvedData.getAsJsonObject("patternProfiles").getAsJsonArray("re2"))
            .hasSizeGreaterThanOrEqualTo(8)
        Assertions.assertThat<JsonElement?>(
            resolvedData.getAsJsonObject("patternProfiles").getAsJsonArray("rust-regex")
        )
            .hasSizeGreaterThanOrEqualTo(28)
        Assertions.assertThat<JsonElement?>(
            resolvedData.getAsJsonObject("replacementProfiles").getAsJsonArray("go-regexp")
        )
            .hasSizeGreaterThanOrEqualTo(1)
        Assertions.assertThat<JsonElement?>(
            resolvedData.getAsJsonObject("replacementProfiles").getAsJsonArray("re2-cpp")
        )
            .hasSizeGreaterThanOrEqualTo(8)
        Assertions.assertThat<JsonElement?>(
            resolvedData.getAsJsonObject("replacementProfiles").getAsJsonArray("rust-regex")
        )
            .hasSizeGreaterThanOrEqualTo(1)
        Assertions.assertThat<JsonElement?>(resolvedData.getAsJsonObject("patternProfiles").getAsJsonArray("dotnet"))
            .hasSizeGreaterThanOrEqualTo(40)
        Assertions.assertThat<String?>(declarationIds(resolvedData.getAsJsonArray("inputs")))
            .containsExactlyElementsOf(declarationIds(declaredData.getAsJsonArray("inputs")))
        Assertions.assertThat<String?>(manifest.getAsJsonObject("inputs").keySet())
            .containsExactlyElementsOf(
                DeclarativeBenchmarkPlan.Companion.parseInputDeclarations(declaredData.getAsJsonArray("inputs"))
                    .keys
            )
        val declaredWorkloadIds: MutableSet<String?> = declarationIds(declaredData.getAsJsonArray("workloads"))
        Assertions.assertThat<String?>(declaredWorkloadIds).hasSizeGreaterThanOrEqualTo(MINIMUM_DECLARED_WORKLOADS)
        Assertions.assertThat<String?>(declarationIds(resolvedData.getAsJsonArray("workloads")))
            .containsExactlyElementsOf(declaredWorkloadIds)
        val executionPlan = manifest.getAsJsonObject("executionPlan")
        Assertions.assertThat(executionPlan.get("version").getAsInt()).isEqualTo(1)
        val workloadIds: MutableSet<String?> = executionPlanIds(executionPlan, "workloadId")
        val expectedWorkloadIds: MutableSet<String?> = expandedWorkloadIds(resolvedData)
        Assertions.assertThat<String?>(expectedWorkloadIds).hasSizeGreaterThanOrEqualTo(MINIMUM_EXPANDED_WORKLOADS)
        Assertions.assertThat<String?>(workloadIds).containsExactlyInAnyOrderElementsOf(expectedWorkloadIds)
        val engineIds: MutableSet<String?> = declarationIds(executionPlan.getAsJsonArray("engines"))
        val expectedExecutionIds: MutableSet<String?> = LinkedHashSet<String?>()
        for (workloadId in workloadIds) {
            for (engineId in engineIds) {
                expectedExecutionIds.add(workloadId + "@" + engineId)
            }
        }
        Assertions.assertThat(executionPlan.get("workloadCount").getAsInt()).isEqualTo(expectedWorkloadIds.size)
        Assertions.assertThat(executionPlan.get("engineCount").getAsInt()).isEqualTo(engineIds.size)
        Assertions.assertThat<JsonElement?>(executionPlan.getAsJsonArray("entries")).hasSize(expectedExecutionIds.size)
        Assertions.assertThat<String?>(executionPlanIds(executionPlan, "id"))
            .containsExactlyInAnyOrderElementsOf(expectedExecutionIds)
        Assertions.assertThat(
            executionEntry(
                executionPlan, "UnicodeCompileBenchmark.compile.word.0@dotnet_nonbacktracking"
            )
                .getAsJsonArray("patterns")
                .get(0)
                .getAsString()
        )
            .isEqualTo("[A-Za-z0-9_]+")
        Assertions.assertThat(
            executionEntry(
                executionPlan,
                "RealWorldRegexBenchmark.runBenchmark.fruitSearchQuery.match.1000@dotnet_nonbacktracking"
            )
                .get("exclusion")
                .getAsJsonObject()
                .get("kind")
                .getAsString()
        )
            .isEqualTo("unsupportedSyntax")
        for (input in arrayOf<String>("absent", "late")) {
            for (engineId in arrayOf<String>("re2_cpp", "go_regexp", "pcre2_jit", "dotnet_nonbacktracking")) {
                val excludedAlphabetic: JsonObject =
                    executionEntry(
                        executionPlan, "UnicodePrefixBenchmark.alphabetic." + input + ".1024@" + engineId
                    )
                Assertions.assertThat(excludedAlphabetic.get("status").getAsString()).isEqualTo("excluded")
                Assertions.assertThat(excludedAlphabetic.getAsJsonObject("exclusion").get("reason").getAsString())
                    .contains("Alphabetic")
            }
            Assertions.assertThat(
                executionEntry(
                    executionPlan,
                    "UnicodePrefixBenchmark.alphabetic." + input + ".1024@rust_regex"
                )
                    .getAsJsonArray("patterns")
                    .get(0)
                    .getAsString()
            )
                .isEqualTo("[\\p{Alphabetic}]+[0-9]+")

            Assertions.assertThat(
                executionEntry(executionPlan, "UnicodePrefixBenchmark.cjk." + input + ".1024@re2_cpp")
                    .getAsJsonArray("patterns")
                    .get(0)
                    .getAsString()
            )
                .isEqualTo("[\\x{4E00}-\\x{9FFF}]+[0-9]+")
            Assertions.assertThat(
                executionEntry(
                    executionPlan, "UnicodePrefixBenchmark.cjk." + input + ".1024@go_regexp"
                )
                    .getAsJsonArray("patterns")
                    .get(0)
                    .getAsString()
            )
                .isEqualTo("[\\x{4E00}-\\x{9FFF}]+[0-9]+")
            Assertions.assertThat(
                executionEntry(
                    executionPlan, "UnicodePrefixBenchmark.cjk." + input + ".1024@rust_regex"
                )
                    .getAsJsonArray("patterns")
                    .get(0)
                    .getAsString()
            )
                .isEqualTo("[\\x{4E00}-\\x{9FFF}]+[0-9]+")
            Assertions.assertThat(
                executionEntry(
                    executionPlan, "UnicodePrefixBenchmark.cjk." + input + ".1024@pcre2_jit"
                )
                    .getAsJsonArray("patterns")
                    .get(0)
                    .getAsString()
            )
                .isEqualTo("[\\x{4E00}-\\x{9FFF}]+[0-9]+")
            Assertions.assertThat(
                executionEntry(
                    executionPlan,
                    "UnicodePrefixBenchmark.cjk." + input + ".1024@dotnet_nonbacktracking"
                )
                    .getAsJsonArray("patterns")
                    .get(0)
                    .getAsString()
            )
                .isEqualTo("[\\u4E00-\\u9FFF]+[0-9]+")
        }
        for (engineElement in executionPlan.getAsJsonArray("engines")) {
            val engineId = engineElement.getAsJsonObject().get("id").getAsString()
            Assertions.assertThat<JsonObject?>(
                executionPlan.getAsJsonArray("entries").asList().stream()
                    .map<JsonObject?> { obj: JsonElement? -> obj!!.getAsJsonObject() }
                    .filter { planEntry: JsonObject? -> planEntry!!.get("engineId").getAsString() == engineId }
                    .filter { planEntry: JsonObject? ->
                        mutableSetOf<String?>(
                            "runnable",
                            "excluded"
                        ).contains(planEntry!!.get("status").getAsString())
                    })
                .`as`(engineId)
                .extracting<String?, RuntimeException?>(ThrowingExtractor { planEntry: JsonObject? ->
                    planEntry!!.get("workloadId").getAsString()
                })
                .containsExactlyInAnyOrderElementsOf(workloadIds)
        }
        Assertions.assertThat<JsonObject?>(
            executionPlan.getAsJsonArray("entries").asList().stream()
                .map<JsonObject?> { obj: JsonElement? -> obj!!.getAsJsonObject() }
                .filter { planEntry: JsonObject? ->
                    (planEntry!!
                        .get("workloadId")
                        .getAsString()
                            == "CompileBenchmark.compileSimple")
                }
                .filter { planEntry: JsonObject? ->
                    mutableSetOf<String?>(
                        "re2_cpp",
                        "pcre2_jit",
                        "go_regexp",
                        "rust_regex"
                    ).contains(planEntry!!.get("engineId").getAsString())
                })
            .hasSize(4)
            .allSatisfy(
                ThrowingConsumer { planEntry: JsonObject? ->
                    Assertions.assertThat(
                        planEntry!!.get("status").getAsString()
                    ).isEqualTo("runnable")
                })
        Assertions.assertThat(
            resolvedData.getAsJsonArray("workloads").asList().stream()
                .filter { workload: JsonElement? ->
                    (workload!!
                        .getAsJsonObject()
                        .get("id")
                        .getAsString()
                            == "UnicodeCompileBenchmark.compile.{regex}.{flagSet}")
                }
                .findFirst()
                .orElseThrow()
                .getAsJsonObject()
                .getAsJsonObject("axes")
                .getAsJsonArray("regex")
                .get(4)
                .getAsJsonObject()
                .get("value")
                .getAsString())
            .isEqualTo("\\p{IsAlphabetic}+")
    }

    @Test
    fun emptyRepeatUnitIsRejectedWhenOutputIsRequired() {
        Assertions.assertThatThrownBy(ThrowingCallable { BenchmarkInputMaterializer.Companion.repeatToSize("", 1) })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Repeat unit must not be empty when size is positive")
    }

    @Test
    fun emptyRepeatUnitCanProduceEmptyOutput() {
        Assertions.assertThat(BenchmarkInputMaterializer.Companion.repeatToSize("", 0)).isEmpty()
    }

    companion object {
        // Historical floors guard against accidental corpus loss. Additions do not raise them.
        private const val MINIMUM_MATERIALIZED_INPUTS = 419
        private const val MINIMUM_DECLARED_WORKLOADS = 354
        private const val MINIMUM_EXPANDED_WORKLOADS = 683

        private fun fileInput(path: String?, sha256: String?): JsonObject {
            val data =
                JsonParser.parseString(
                    """
                {
                  "schemaVersion": 2,
                  "inputs": [{
                    "id": "source.code",
                    "recipe": {"kind": "file"}
                  }]
                }
                
                """.trimIndent()
                )
                    .getAsJsonObject()
            val recipe =
                data.getAsJsonArray("inputs").get(0).getAsJsonObject().getAsJsonObject("recipe")
            recipe.addProperty("path", path)
            recipe.addProperty("sha256", sha256)
            return data
        }

        private fun text(inputs: MutableMap<String?, ByteArray?>, id: String?): String {
            return kotlin.text.String(inputs.get(id)!!, StandardCharsets.UTF_8)
        }

        private fun executionEntry(plan: JsonObject, id: String?): JsonObject {
            return plan.getAsJsonArray("entries").asList().stream()
                .map<JsonObject> { obj: JsonElement? -> obj!!.getAsJsonObject() }
                .filter { entry: JsonObject? -> entry!!.get("id").getAsString() == id }
                .findFirst()
                .orElseThrow()
        }

        private fun declarationIds(declarations: JsonArray): MutableSet<String?> {
            return declarations.asList().stream()
                .map<JsonObject?> { obj: JsonElement? -> obj!!.getAsJsonObject() }
                .map<String?> { declaration: JsonObject? -> declaration!!.get("id").getAsString() }
                .collect(Collectors.toCollection(Supplier { LinkedHashSet() }))
        }

        private fun executionPlanIds(plan: JsonObject, property: String?): MutableSet<String?> {
            return plan.getAsJsonArray("entries").asList().stream()
                .map<JsonObject?> { obj: JsonElement? -> obj!!.getAsJsonObject() }
                .map<String?> { entry: JsonObject? -> entry!!.get(property).getAsString() }
                .collect(Collectors.toCollection(Supplier { LinkedHashSet() }))
        }

        private fun expandedWorkloadIds(data: JsonObject): MutableSet<String?> {
            val planData = JsonObject()
            planData.add("schemaVersion", data.get("schemaVersion").deepCopy())
            planData.add("inputs", data.getAsJsonArray("inputs").deepCopy())
            planData.add("workloads", data.getAsJsonArray("workloads").deepCopy())
            return DeclarativeBenchmarkPlan.Companion.parse(planData).expandedWorkloads().stream()
                .map<String?>(ExpandedWorkload::id)
                .collect(Collectors.toCollection(Supplier { LinkedHashSet() }))
        }
    }
}
