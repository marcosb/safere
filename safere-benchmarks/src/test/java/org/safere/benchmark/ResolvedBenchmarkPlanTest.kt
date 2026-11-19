// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowingConsumer
import org.assertj.core.api.iterable.ThrowingExtractor
import org.junit.jupiter.api.Test
import org.safere.benchmark.CrossEngineWorkload.TimingGroup
import org.safere.benchmark.DeclarativeBenchmarkPlan.EngineDeclaration
import org.safere.benchmark.DeclarativeBenchmarkPlan.MeasurementMode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.List
import java.util.Set
import java.util.function.Function
import java.util.function.Predicate
import kotlin.Exception
import kotlin.RuntimeException
import kotlin.String
import kotlin.Throws
import kotlin.text.startsWith
import kotlin.text.trimIndent

internal class ResolvedBenchmarkPlanTest {
    @Test
    @Throws(IOException::class)
    fun ffmMatcherOperationsPreserveConversionTimingAndUnsupportedExclusions() {
        val source =
            if (Files.exists(Path.of("benchmark-data.json")))
                Path.of("benchmark-data.json")
            else
                Path.of("safere-benchmarks", "benchmark-data.json")
        val plan: JsonObject =
            create(
                PatternProfiles.normalizeInline(
                    JsonParser.parseString(Files.readString(source)).asJsonObject
                )
            )
        for (workload in List.of<String?>(
            "MatcherApiBenchmark.lookingAt",
            "MatcherApiBenchmark.newMatcherRegionFind",
            "MatcherApiBenchmark.newMatcherRegionMatches"
        )) {
            val row: JsonObject = entry(plan, workload + "@re2-ffm-string-conversion")
            Assertions.assertThat(row.get("status").asString).`as`(workload).isEqualTo("runnable")
            Assertions.assertThat(row.get("inputRepresentation").asString)
                .isEqualTo("javaStringWithTimedUtf8Conversion")
            Assertions.assertThat(row.getAsJsonObject("lifecycle").get("matcher").asString)
                .isEqualTo("newPerInvocation")
        }
        for (workload in List.of<String?>(
            "MatcherApiBenchmark.regionFind",
            "MatcherApiBenchmark.regionTokenize.16384",
            "MatcherApiBenchmark.resetAndFind"
        )) {
            val row: JsonObject = entry(plan, workload + "@re2-ffm-string-conversion")
            Assertions.assertThat(row.get("status").asString).`as`(workload).isEqualTo("excluded")
            Assertions.assertThat(row.getAsJsonObject("exclusion").get("kind").asString)
                .isEqualTo("unsupportedTimingBoundary")
            Assertions.assertThat(row.getAsJsonObject("exclusion").get("reason").asString)
                .contains("conversion", "setup")
        }
        val append: JsonObject = entry(plan, "ReplaceBenchmark.manualReplaceAll@re2-ffm-string-conversion")
        Assertions.assertThat(append.get("status").asString).isEqualTo("excluded")
        Assertions.assertThat(append.getAsJsonObject("exclusion").get("reason").asString)
            .contains("appendReplacement")
    }

    @Test
    @Throws(IOException::class)
    fun nativeColdUnicodeRowsKeepExactSyntaxAndExcludeUnsupportedOptions() {
        val benchmarkDirectory =
            if (Files.exists(Path.of("benchmark-data.json"))) Path.of(".") else Path.of("safere-benchmarks")
        val data =
            JsonParser.parseString(Files.readString(benchmarkDirectory.resolve("benchmark-data.json")))
                .asJsonObject
        val plan: JsonObject = create(PatternProfiles.normalizeInline(data))
        for (engine in List.of<String?>("re2_cpp", "pcre2_jit", "go_regexp", "rust_regex")) {
            val rows =
                plan.getAsJsonArray("entries").asList().stream()
                    .map<JsonObject?>(Function { obj: JsonElement? -> JsonObject::class.java.cast(obj) })
                    .filter(Predicate { row: JsonObject? -> row!!.get("engineId").asString == engine })
                    .filter(
                        Predicate { row: JsonObject? ->
                            row!!.get("workloadId")
                                .asString
                                .startsWith("UnicodeFirstCompileBenchmark.firstCompile.")
                        })
                    .toList()
            Assertions.assertThat<JsonObject?>(rows).hasSize(36)
            Assertions.assertThat<JsonObject?>(
                rows.stream().filter(Predicate { row: JsonObject? -> row!!.get("status").asString == "runnable" })
            )
                .hasSize(if (engine == "pcre2_jit" || engine == "rust_regex") 12 else 9)
                .allSatisfy(
                    ThrowingConsumer { row: JsonObject? ->
                        Assertions.assertThat(row!!.getAsJsonObject("measurement").get("mode").asString)
                            .isEqualTo("singleShotColdStart")
                        Assertions.assertThat(row.getAsJsonObject("measurement").get("timingUnit").asString)
                            .isEqualTo("milliseconds")
                        Assertions.assertThat<JsonElement?>(row.getAsJsonArray("options")).isEmpty()
                    })
            Assertions.assertThat(
                entry(
                    plan,
                    ("UnicodeFirstCompileBenchmark.firstCompile.letter."
                            + "CASE_INSENSITIVE_UNICODE_CASE@"
                            + engine)
                )
                    .getAsJsonObject("exclusion")
                    .get("kind")
                    .asString
            )
                .isEqualTo("unsupportedOptions")
            Assertions.assertThat(
                entry(
                    plan,
                    "UnicodeFirstCompileBenchmark.firstCompile.word.UNICODE_CHARACTER_CLASS@"
                            + engine
                )
                    .getAsJsonObject("exclusion")
                    .get("kind")
                    .asString
            )
                .isEqualTo("unsupportedOptions")
            Assertions.assertThat(
                entry(plan, "UnicodeCompileBenchmark.compile.letter.0@" + engine)
                    .getAsJsonObject("exclusion")
                    .get("kind")
                    .asString
            )
                .isEqualTo("unsupportedFeature")
            if (engine == "re2_cpp" || engine == "go_regexp") {
                Assertions.assertThat(
                    entry(plan, "UnicodeFirstCompileBenchmark.firstCompile.alphabetic.0@" + engine)
                        .getAsJsonObject("exclusion")
                        .get("kind")
                        .asString
                )
                    .isEqualTo("unsupportedSyntax")
            } else {
                Assertions.assertThat(
                    entry(plan, "UnicodeFirstCompileBenchmark.firstCompile.alphabetic.0@" + engine)
                        .getAsJsonArray("patterns")
                        .get(0)
                        .asString
                )
                    .isEqualTo("\\p{Alphabetic}+")
            }
        }
        Assertions.assertThat(
            entry(plan, "UnicodeFirstCompileBenchmark.firstCompile.scriptLatin.0@re2_cpp")
                .getAsJsonArray("patterns")
                .get(0)
                .asString
        )
            .isEqualTo("\\p{Latin}+")
        Assertions.assertThat(
            entry(plan, "UnicodeFirstCompileBenchmark.firstCompile.scriptLatin.0@pcre2_jit")
                .getAsJsonArray("patterns")
                .get(0)
                .asString
        )
            .isEqualTo("\\p{sc=Latin}+")
        Assertions.assertThat(
            entry(plan, "UnicodeFirstCompileBenchmark.firstCompile.word.0@rust_regex")
                .getAsJsonArray("patterns")
                .get(0)
                .asString
        )
            .isEqualTo("[A-Za-z0-9_]+")
    }

    @Test
    @Throws(Exception::class)
    fun nativeUtf8CaptureBoundsRowsHaveExactProfilesAndExclusions() {
        val source =
            if (Files.exists(Path.of("benchmark-data.json")))
                Path.of("benchmark-data.json")
            else
                Path.of("safere-benchmarks", "benchmark-data.json")
        val data: JsonObject =
            PatternProfiles.normalizeInline(
                JsonParser.parseString(Files.readString(source)).asJsonObject
            )
        val plan: JsonObject = create(data)
        val rows =
            List.of<String?>(
                "Utf8MatchingBenchmark.repeatedFind.ascii",
                "Utf8MatchingBenchmark.repeatedFind.multibyte",
                "Utf8MatchingBenchmark.captureBounds.numbered",
                "Utf8MatchingBenchmark.captureBounds.named",
                "Utf8MatchingBenchmark.captureBounds.nonparticipating",
                "Utf8MatchingBenchmark.emptyMatchIteration"
            )
        for (row in rows) {
            Assertions.assertThat(entry(plan, row + "@safere-utf8").get("status").asString)
                .isEqualTo("runnable")
            for (engine in List.of<String?>("re2_cpp", "pcre2_jit", "go_regexp", "rust_regex")) {
                val runnable: JsonObject = entry(plan, row + "@" + engine)
                Assertions.assertThat(runnable.get("status").asString).isEqualTo("runnable")
                Assertions.assertThat<JsonElement?>(
                    runnable.getAsJsonObject("arguments").getAsJsonArray("expectedBounds")
                )
                    .isNotEmpty()
                Assertions.assertThat(runnable.getAsJsonObject("expected").get("type").asString)
                    .isEqualTo("integer")
            }
            val excluded: JsonObject = entry(plan, row + "@dotnet_nonbacktracking")
            Assertions.assertThat(excluded.get("status").asString).isEqualTo("excluded")
            Assertions.assertThat(excluded.getAsJsonObject("exclusion").get("kind").asString)
                .isEqualTo("unsupportedOperation")
            for (engine in List.of<String?>(
                "safere-string",
                "jdk-string",
                "re2j-string",
                "re2-ffm-string-conversion"
            )) {
                val stringEntry: JsonObject = entry(plan, row + "@" + engine)
                Assertions.assertThat(stringEntry.get("status").asString).isEqualTo("excluded")
                Assertions.assertThat(stringEntry.getAsJsonObject("exclusion").get("kind").asString)
                    .isEqualTo("unsupportedFeature")
            }
        }
        Assertions.assertThat(
            entry(plan, "Utf8MatchingBenchmark.captureBounds.named@re2_cpp")
                .getAsJsonArray("patterns")
                .get(0)
                .asString
        )
            .isEqualTo("(?P<key>[A-Za-z]+)=(?P<value>[^&]+)")
    }

    @Test
    fun materializationAccountsForCompleteSyntheticWorkloadAndEngineJoin() {
        val plan =
            ResolvedBenchmarkPlan.create(
                normalizedData(),
                List.of<ResolvedBenchmarkPlan.Engine?>(
                    engine(
                        "find-only",
                        "java",
                        EnumSet.of<DeclarativeBenchmarkPlan.Feature?>(DeclarativeBenchmarkPlan.Feature.FIND),
                        EnumSet.of<DeclarativeBenchmarkPlan.Operation?>(DeclarativeBenchmarkPlan.Operation.FIND)
                    ),
                    engine(
                        "find-and-replace",
                        "alternate",
                        EnumSet.of<DeclarativeBenchmarkPlan.Feature?>(
                            DeclarativeBenchmarkPlan.Feature.FIND,
                            DeclarativeBenchmarkPlan.Feature.REPLACE,
                            DeclarativeBenchmarkPlan.Feature.NUMBERED_REPLACEMENT
                        ),
                        EnumSet.of<DeclarativeBenchmarkPlan.Operation?>(
                            DeclarativeBenchmarkPlan.Operation.FIND,
                            DeclarativeBenchmarkPlan.Operation.REPLACE_ALL
                        )
                    )
                )
            )

        Assertions.assertThat(plan.get("version").asInt).isEqualTo(ResolvedBenchmarkPlan.VERSION)
        Assertions.assertThat(plan.get("workloadCount").asInt).isEqualTo(2)
        Assertions.assertThat(plan.get("engineCount").asInt).isEqualTo(2)
        val entries = plan.getAsJsonArray("entries")
        Assertions.assertThat<JsonElement?>(entries).hasSize(4)
        Assertions.assertThat<String?>(
            entries!!.asList().stream()
                .map<JsonObject?>(Function { obj: JsonElement? -> JsonObject::class.java.cast(obj) })
                .map<String?>(Function { entry: JsonObject? -> id(entry!!) })
        )
            .containsExactly(
                "Synthetic.find@find-only",
                "Synthetic.find@find-and-replace",
                "Synthetic.replace@find-only",
                "Synthetic.replace@find-and-replace"
            )
        Assertions.assertThat<JsonObject?>(
            entries.asList().stream()
                .map<JsonObject?>(Function { obj: JsonElement? -> JsonObject::class.java.cast(obj) })
                .filter(Predicate { entry: JsonObject? -> entry!!.get("status").asString == "runnable" })
        )
            .hasSize(3)
        Assertions.assertThat<JsonObject?>(
            entries.asList().stream()
                .map<JsonObject?>(Function { obj: JsonElement? -> JsonObject::class.java.cast(obj) })
                .filter(Predicate { entry: JsonObject? -> entry!!.get("status").asString == "excluded" })
        )
            .singleElement()
            .satisfies(
                ThrowingConsumer { entry: JsonObject? ->
                    Assertions.assertThat(id(entry!!)).isEqualTo("Synthetic.replace@find-only")
                    Assertions.assertThat(entry.getAsJsonObject("exclusion").get("kind").asString)
                        .isEqualTo("unsupportedOperation")
                })
    }

    @Test
    fun runnableEntriesContainExactSelectedSyntaxAndUnchangedFallback() {
        val plan =
            ResolvedBenchmarkPlan.create(
                normalizedData(),
                List.of<ResolvedBenchmarkPlan.Engine?>(
                    engine(
                        "alternate-engine",
                        "alternate",
                        EnumSet.of<DeclarativeBenchmarkPlan.Feature?>(
                            DeclarativeBenchmarkPlan.Feature.FIND,
                            DeclarativeBenchmarkPlan.Feature.REPLACE,
                            DeclarativeBenchmarkPlan.Feature.NUMBERED_REPLACEMENT
                        ),
                        EnumSet.of<DeclarativeBenchmarkPlan.Operation?>(
                            DeclarativeBenchmarkPlan.Operation.FIND,
                            DeclarativeBenchmarkPlan.Operation.REPLACE_ALL
                        )
                    )
                )
            )

        val find: JsonObject = entry(plan, "Synthetic.find@alternate-engine")
        val replace: JsonObject = entry(plan, "Synthetic.replace@alternate-engine")
        Assertions.assertThat(find.getAsJsonArray("patterns").get(0).asString).isEqualTo("alternate-pattern")
        Assertions.assertThat(replace.getAsJsonArray("patterns").get(0).asString)
            .isEqualTo("unchanged-pattern")
        Assertions.assertThat(replace.getAsJsonObject("arguments").get("replacement").asString)
            .isEqualTo("alternate-replacement")
    }

    @Test
    fun explicitTrialExclusionsSurviveResolutionAndMaterializedConsumption() {
        val data: JsonObject = normalizedData()
        val find = data.getAsJsonArray("workloads").get(0).asJsonObject
        find.add(
            "trialExclusions",
            JsonParser.parseString(
                """
            [{
              "engineIds": ["jdk-string"],
              "reason": "OpenJDK 26.0.2 throws StackOverflowError for this trial"
            }]
            
            """.trimIndent()
            )
        )

        val resolved =
            ResolvedBenchmarkPlan.create(
                data,
                List.of<ResolvedBenchmarkPlan.Engine?>(
                    engine(
                        "safere-string",
                        "alternate",
                        EnumSet.of<DeclarativeBenchmarkPlan.Feature?>(DeclarativeBenchmarkPlan.Feature.FIND),
                        EnumSet.of<DeclarativeBenchmarkPlan.Operation?>(DeclarativeBenchmarkPlan.Operation.FIND)
                    ),
                    engine(
                        "jdk-string",
                        "alternate",
                        EnumSet.of<DeclarativeBenchmarkPlan.Feature?>(DeclarativeBenchmarkPlan.Feature.FIND),
                        EnumSet.of<DeclarativeBenchmarkPlan.Operation?>(DeclarativeBenchmarkPlan.Operation.FIND)
                    )
                )
            )

        val exclusion: JsonObject = entry(resolved, "Synthetic.find@jdk-string")
        Assertions.assertThat(exclusion.get("status").asString).isEqualTo("excluded")
        Assertions.assertThat(exclusion.getAsJsonObject("exclusion").get("kind").asString)
            .isEqualTo("explicitTrialExclusion")
        Assertions.assertThat(exclusion.getAsJsonObject("exclusion").get("reason").asString)
            .isEqualTo("OpenJDK 26.0.2 throws StackOverflowError for this trial")
        Assertions.assertThat(entry(resolved, "Synthetic.find@safere-string").get("status").asString)
            .isEqualTo("runnable")

        val materialized: MaterializedExecutionPlan = MaterializedExecutionPlan.parse(resolved)
        Assertions.assertThat<MaterializedExecutionPlan.Exclusion?>(materialized.resolve("Synthetic.find@jdk-string").exclusion)
            .isEqualTo(
                MaterializedExecutionPlan.Exclusion(
                    "explicitTrialExclusion",
                    "OpenJDK 26.0.2 throws StackOverflowError for this trial"
                )
            )
        val collection: CrossEngineBenchmarkPlan =
            CrossEngineBenchmarkPlan.Companion.fromMaterialized(materialized.entriesForRunner("java"))
        Assertions.assertThat<CrossEngineBenchmarkPlan.Trial?>(collection.trials(TimingGroup.NANOSECONDS))
            .extracting<String?, RuntimeException?>(ThrowingExtractor { id() })
            .contains("Synthetic.find@safere-string")
            .doesNotContain("Synthetic.find@jdk-string")
        Assertions.assertThat<MaterializedExecutionPlan.Entry?>(collection.exclusions())
            .extracting<String?, RuntimeException?>(MaterializedExecutionPlan.Entry::id)
            .contains("Synthetic.find@jdk-string")
    }

    companion object {
        private fun engine(
            id: String?,
            profile: String?,
            features: EnumSet<DeclarativeBenchmarkPlan.Feature?>?,
            operations: EnumSet<DeclarativeBenchmarkPlan.Operation?>?
        ): ResolvedBenchmarkPlan.Engine {
            return ResolvedBenchmarkPlan.Engine(
                id,
                id,
                "java",
                profile,
                profile,
                EngineDeclaration(
                    id,
                    DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING,
                    features,
                    EnumSet.noneOf<DeclarativeBenchmarkPlan.Flag?>(DeclarativeBenchmarkPlan.Flag::class.java),
                    true
                ),
                operations,
                EnumSet.of<MeasurementMode?>(MeasurementMode.AVERAGE_TIME),
                Set.of<String?>("0")
            )
        }

        private fun normalizedData(): JsonObject {
            return JsonParser.parseString(
                """
            {
              "schemaVersion": 2,
              "inputs": [{
                "id": "input",
                "recipe": {"kind": "literal", "text": "text"},
                "shared": true
              }],
              "workloads": [
                {
                  "id": "Synthetic.find",
                  "operation": "find",
                  "patterns": ["canonical-pattern"],
                  "inputs": ["input"],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                },
                {
                  "id": "Synthetic.replace",
                  "operation": "replaceAll",
                  "patterns": ["unchanged-pattern"],
                  "inputs": ["input"],
                  "arguments": {"replacement": "canonical-replacement"},
                  "requirements": ["numberedReplacement"],
                  "resultConsumption": "string",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }
              ],
              "patternProfiles": {
                "alternate": [{
                  "java": "canonical-pattern",
                  "alternate": "alternate-pattern",
                  "reason": "synthetic alternate"
                }]
              },
              "replacementProfiles": {
                "alternate": [{
                  "java": "canonical-replacement",
                  "alternate": "alternate-replacement",
                  "reason": "synthetic alternate"
                }]
              }
            }
            
            """.trimIndent()
            )
                .asJsonObject
        }

        private fun entry(plan: JsonObject, id: String?): JsonObject {
            return plan.getAsJsonArray("entries").asList().stream()
                .map<JsonObject>(Function { obj: JsonElement? -> JsonObject::class.java.cast(obj) })
                .filter(Predicate { entry: JsonObject? -> id(entry!!) == id })
                .findFirst()
                .orElseThrow()
        }

        private fun id(entry: JsonObject): String? {
            return entry.get("id").asString
        }
    }
}
