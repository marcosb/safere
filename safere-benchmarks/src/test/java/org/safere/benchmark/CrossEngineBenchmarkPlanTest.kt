// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.re2j.Pattern
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.assertj.core.api.ThrowingConsumer
import org.assertj.core.api.iterable.ThrowingExtractor
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.openjdk.jmh.infra.Blackhole
import org.safere.benchmark.BenchmarkCollectionPlan.CollectionTrial
import org.safere.benchmark.BenchmarkCollectionPlan.ReportExclusion
import org.safere.benchmark.CrossEngineWorkload.TimingGroup
import org.safere.benchmark.DeclarativeBenchmarkPlan.*
import org.safere.benchmark.RegexEngineVariant.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.List
import java.util.function.Consumer
import java.util.function.Predicate
import kotlin.collections.ArrayList
import kotlin.collections.HashSet
import kotlin.collections.MutableList
import kotlin.collections.MutableSet
import kotlin.collections.filter
import kotlin.collections.first
import kotlin.collections.map
import kotlin.collections.mutableListOf
import kotlin.collections.mutableSetOf
import kotlin.collections.toList
import kotlin.collections.toTypedArray
import kotlin.map
import kotlin.run
import kotlin.sequences.filter
import kotlin.sequences.map
import kotlin.sequences.toList
import kotlin.streams.asStream
import kotlin.text.contains
import kotlin.text.endsWith
import kotlin.text.lastIndexOf
import kotlin.text.lineSequence
import kotlin.text.map
import kotlin.text.split
import kotlin.text.startsWith
import kotlin.text.substring
import kotlin.text.toByteArray
import kotlin.text.toList
import kotlin.text.toRegex
import kotlin.toList

internal class CrossEngineBenchmarkPlanTest {
    @Test
    fun everyRunnableRe2jPatternCompiles() {
        val entries: MutableList<MaterializedExecutionPlan.Entry> =
            MaterializedExecutionPlan.load().entries().stream()
                .filter { entry: MaterializedExecutionPlan.Entry -> entry.engineId == "re2j-string" && entry.runnable() }
                .toList()
        Assertions.assertThat<MaterializedExecutionPlan.Entry?>(entries).isNotEmpty()
        for (entry in entries) {
            for (pattern in entry.workload.patterns) {
                Assertions.assertThatCode(ThrowingCallable { Pattern.compile(pattern) })
                    .`as`("%s pattern %s", entry.id, pattern)
                    .doesNotThrowAnyException()
            }
        }
    }

    @Test
    fun workloadIdsAreUniqueStableReportIdentities() {
        val ids: MutableList<String> =
            CrossEngineBenchmarkPlan.load().workloads().stream().map<String?>(CrossEngineWorkload::id)
                .toList()

        Assertions.assertThat<String?>(ids).doesNotHaveDuplicates()
        Assertions.assertThat<String?>(ids)
            .contains(
                "RegexBenchmark.literalMatch",
                "ApplicationBenchmark.uuidValidation",
                "RealWorldRegexBenchmark.runBenchmark.mapFieldPath.match.1000",
                "HttpBenchmark.httpFull",
                "SearchScalingBenchmark.searchEasyFail.1024",
                "FanoutBenchmark.fanoutUnicode.1024"
            )
        Assertions.assertThat<String?>(ids).hasSizeGreaterThanOrEqualTo(MINIMUM_CROSS_ENGINE_WORKLOADS)
    }

    @Test
    fun everyWorkloadEnginePairIsScheduledOrExplicitlyExcluded() {
        val plan: CrossEngineBenchmarkPlan = CrossEngineBenchmarkPlan.load()
        val allTrials =
            List.of<MutableList<CrossEngineBenchmarkPlan.Trial?>?>(
                plan.trials(TimingGroup.NANOSECONDS),
                plan.trials(TimingGroup.MICROSECONDS),
                plan.trials(TimingGroup.MILLISECONDS)
            )
                .stream()
                .flatMap<CrossEngineBenchmarkPlan.Trial?> { obj: MutableList<CrossEngineBenchmarkPlan.Trial?>? -> obj!!.stream() }
                .toList()
        val accounted: MutableSet<String?> = HashSet<String?>()
        allTrials.forEach(Consumer { trial: CrossEngineBenchmarkPlan.Trial? ->
            Assertions.assertThat(accounted.add(trial!!.id())).isTrue()
        })
        plan.exclusions()
            .forEach(
                Consumer { exclusion: MaterializedExecutionPlan.Entry? ->
                    Assertions.assertThat(accounted.add(exclusion!!.workloadId + "@" + exclusion.engineId))
                        .isTrue()
                })

        for (workload in plan.workloads()) {
            Assertions.assertThat<RegexEngineVariant?>(
                allTrials.stream()
                    .filter { trial: CrossEngineBenchmarkPlan.Trial? -> trial!!.workload.id == workload.id }
                    .map<RegexEngineVariant?>(CrossEngineBenchmarkPlan.Trial::variant))
                .containsAnyOf(SAFERE_STRING, SAFERE_UTF8)
        }
        Assertions.assertThat<CrossEngineBenchmarkPlan.Trial?>(allTrials)
            .hasSizeGreaterThanOrEqualTo(MINIMUM_RUNNABLE_CROSS_ENGINE_TRIALS)
        val expected: MutableSet<String> = HashSet<String>()
        for (workload in plan.workloads()) {
            for (variant in RegexEngineVariant.entries) {
                expected.add(workload.id + "@" + variant.id())
            }
        }
        Assertions.assertThat<String?>(accounted).containsExactlyInAnyOrderElementsOf(expected)
    }

    @Test
    fun utf8TrialPreparationRejectsTheWrongProviderBeforeMeasurement() {
        val property = "org.safere.experimental.vectorScanProvider"
        val previous = System.getProperty(property)
        try {
            System.clearProperty(property)
            Assertions.assertThatThrownBy(
                ThrowingCallable {
                    CrossEngineTrialRunner.prepare(
                        "RegexBenchmark.emailFind@safere-utf8-vector",
                        TimingGroup.NANOSECONDS
                    )
                })
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("requires -D" + property + "=vector")
            Assertions.assertThatThrownBy(
                ThrowingCallable {
                    SpecializedTrialRunner.prepare(
                        "Utf8MatchingBenchmark.captureBounds.numbered@safere-utf8-vector"
                    )
                })
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("requires -D" + property + "=vector")
            System.setProperty(property, "vector")
            Assertions.assertThatThrownBy(
                ThrowingCallable {
                    CrossEngineTrialRunner.prepare(
                        "RegexBenchmark.emailFind@safere-utf8",
                        TimingGroup.NANOSECONDS
                    )
                })
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("requires default provider selection")
        } finally {
            if (previous == null) {
                System.clearProperty(property)
            } else {
                System.setProperty(property, previous)
            }
        }
    }

    @Test
    fun vectorUtf8PreservesWorkloadsExclusionsAndProviderMetadata() {
        val plan: BenchmarkCollectionPlan = BenchmarkCollectionPlan.load()
        val defaults =
            plan.reportPlan().trials.stream()
                .filter { trial: CollectionTrial? -> trial!!.executionVariant == "safere-utf8" }
                .toList()
        val vector =
            plan.reportPlan().trials.stream()
                .filter { trial: CollectionTrial -> trial.executionVariant == "safere-utf8-vector" }
                .toList()
        Assertions.assertThat<CollectionTrial?>(vector)
            .isNotEmpty()
            .allSatisfy(ThrowingConsumer { trial: CollectionTrial? ->
                Assertions.assertThat(trial!!.requestedScanProvider).isEqualTo("vector")
            })
        Assertions.assertThat<CollectionTrial?>(vector)
            .extracting<String?, RuntimeException?>(CollectionTrial::workloadId)
            .containsExactlyElementsOf(
                defaults.stream().map<String?>(CollectionTrial::workloadId).toList()
            )
        Assertions.assertThat<CollectionTrial?>(vector)
            .extracting<Measurement?, RuntimeException?>(CollectionTrial::measurement)
            .containsExactlyElementsOf(
                defaults.stream().map<Measurement?>(CollectionTrial::measurement).toList()
            )
        Assertions.assertThat<String?>(
            plan.reportPlan().exclusions.stream()
                .filter { row: ReportExclusion? -> row!!.executionVariant == "safere-utf8-vector" }
                .map<String?> { row: ReportExclusion? -> row!!.workloadId + ":" + row.kind + ":" + row.reason })
            .containsExactlyElementsOf(
                plan.reportPlan().exclusions.stream()
                    .filter { row: ReportExclusion? -> row!!.executionVariant == "safere-utf8" }
                    .map<String?> { row: ReportExclusion? -> row!!.workloadId + ":" + row.kind + ":" + row.reason }
                    .toList())
    }

    @Test
    fun executionGroupsIsolateProvidersAndPreserveFullAndSmokeMembership() {
        val plan: BenchmarkCollectionPlan = BenchmarkCollectionPlan.load()
        for (smoke in mutableListOf<Boolean?>(false, true)) {
            val groups = plan.executionRunners(false, smoke!!)
            Assertions.assertThat<BenchmarkCollectionPlan.Runner?>(groups)
                .allSatisfy(
                    ThrowingConsumer { group: BenchmarkCollectionPlan.Runner? ->
                        val provider: String =
                            RegexEngineVariant.fromId(
                                group!!
                                    .trialIds
                                    .first()
                                    .substring(group.trialIds.first().lastIndexOf('@') + 1)
                            )
                                .scanProvider()
                        Assertions.assertThat<String?>(group.trialIds)
                            .allMatch(Predicate { id: String? ->
                                BenchmarkCollectionPlan.Companion.matchesScanProvider(
                                    id,
                                    provider
                                )
                            })
                    })
            Assertions.assertThat<String?>(
                groups.stream()
                    .flatMap<String?> { group: BenchmarkCollectionPlan.Runner? -> group!!.trialIds.stream() })
                .containsExactlyInAnyOrderElementsOf(
                    plan.reportPlan(smoke).trials.stream()
                        .map<String?>(CollectionTrial::id)
                        .toList()
                )
        }
        Assertions.assertThat<BenchmarkCollectionPlan.Runner?>(plan.forScanProvider("vector").runners())
            .flatExtracting<String?, RuntimeException?>(BenchmarkCollectionPlan.Runner::trialIds)
            .isNotEmpty()
            .allMatch(Predicate { id: String? -> id!!.endsWith("@safere-utf8-vector") })
        Assertions.assertThat<BenchmarkCollectionPlan.Runner?>(plan.forScanProvider("default").allocationRunners())
            .flatExtracting<String?, RuntimeException?>(BenchmarkCollectionPlan.Runner::trialIds)
            .noneMatch(Predicate { id: String? -> id!!.endsWith("@safere-utf8-vector") })
        Assertions.assertThat<String?>(
            plan.executionRunners(true, false).stream()
                .flatMap<String?> { group: BenchmarkCollectionPlan.Runner? -> group!!.trialIds.stream() })
            .containsExactlyInAnyOrderElementsOf(
                plan.allocationRunners().stream()
                    .flatMap<String?> { group: BenchmarkCollectionPlan.Runner? -> group!!.trialIds.stream() }.toList()
            )
    }

    @Test
    fun longRecitationListsExcludeOnlyJdkTrialsThatOverflowItsStack() {
        val exclusions: MutableList<MaterializedExecutionPlan.Entry> =
            CrossEngineBenchmarkPlan.load().exclusions().stream()
                .filter { exclusion: MaterializedExecutionPlan.Entry ->
                    exclusion
                        .workloadId
                        .startsWith("RealWorldRegexBenchmark.runBenchmark.recitation.")
                            && exclusion.engineId == "jdk-string"
                }
                .toList()

        Assertions.assertThat<MaterializedExecutionPlan.Entry?>(exclusions)
            .extracting<String?, RuntimeException?>(MaterializedExecutionPlan.Entry::id)
            .containsExactly(
                "RealWorldRegexBenchmark.runBenchmark.recitation.match.10000@jdk-string",
                "RealWorldRegexBenchmark.runBenchmark.recitation.match.100000@jdk-string",
                "RealWorldRegexBenchmark.runBenchmark.recitation.noMatch.10000@jdk-string",
                "RealWorldRegexBenchmark.runBenchmark.recitation.noMatch.100000@jdk-string"
            )
        Assertions.assertThat<MaterializedExecutionPlan.Entry?>(exclusions)
            .allSatisfy(
                ThrowingConsumer { exclusion: MaterializedExecutionPlan.Entry? ->
                    Assertions.assertThat(exclusion!!.exclusion.kind).isEqualTo("explicitTrialExclusion")
                    Assertions.assertThat(exclusion.exclusion.reason)
                        .isEqualTo(
                            "JDK's backtracking matcher throws StackOverflowError for the nested "
                                    + "quantified recitation pattern at these input sizes"
                        )
                })
    }

    @Test
    fun trialExpansionIsDeterministicAndPreservesTimingGroups() {
        val first: CrossEngineBenchmarkPlan = CrossEngineBenchmarkPlan.load()
        val second: CrossEngineBenchmarkPlan = CrossEngineBenchmarkPlan.load()

        val trialIds: MutableSet<String?> = HashSet<String?>()
        for (timingGroup in TimingGroup.entries) {
            val firstTrials = first.trials(timingGroup)
            Assertions.assertThat<CrossEngineBenchmarkPlan.Trial?>(firstTrials)
                .`as`(timingGroup.name)
                .isNotEmpty()
                .allMatch(Predicate { trial: CrossEngineBenchmarkPlan.Trial? -> trial!!.workload.timingGroup == timingGroup })
                .extracting<String?, RuntimeException?>(ThrowingExtractor { id() })
                .containsExactlyElementsOf(
                    second.trials(timingGroup).stream().map<String?> { id() }.toList()
                )
            firstTrials.forEach(Consumer { trial: CrossEngineBenchmarkPlan.Trial? ->
                Assertions.assertThat(
                    trialIds.add(
                        trial!!.id()
                    )
                ).`as`(trial.id()).isTrue()
            })
        }
    }

    @Test
    fun utf8ParticipatesInEveryOperationSupportedByItsNativeApi() {
        val plan: CrossEngineBenchmarkPlan = CrossEngineBenchmarkPlan.load()
        val utf8Trials =
            List.of<MutableList<CrossEngineBenchmarkPlan.Trial?>?>(
                plan.trials(TimingGroup.NANOSECONDS),
                plan.trials(TimingGroup.MICROSECONDS)
            )
                .stream()
                .flatMap<CrossEngineBenchmarkPlan.Trial?> { obj: MutableList<CrossEngineBenchmarkPlan.Trial?>? -> obj!!.stream() }
                .filter { trial: CrossEngineBenchmarkPlan.Trial -> trial.variant === SAFERE_UTF8 }
                .toList()

        Assertions.assertThat<CrossEngineBenchmarkPlan.Trial?>(utf8Trials).isNotEmpty()
        Assertions.assertThat<CrossEngineBenchmarkPlan.Trial?>(utf8Trials)
            .extracting<BenchmarkOperation?, RuntimeException?>(ThrowingExtractor { trial: CrossEngineBenchmarkPlan.Trial? -> trial!!.workload.operation })
            .contains(
                BenchmarkOperation.FIND,
                BenchmarkOperation.FIND_ALL_COUNT,
                BenchmarkOperation.MATCHES,
                BenchmarkOperation.MATCHES_CORPUS,
                BenchmarkOperation.LOOKING_AT,
                BenchmarkOperation.MATCHER_RESET_FIND,
                BenchmarkOperation.MATCHER_REGION_FIND,
                BenchmarkOperation.FIND_GROUP_PRESENT,
                BenchmarkOperation.REPLACE_FIRST,
                BenchmarkOperation.REPLACE_ALL,
                BenchmarkOperation.REPLACE_ALL_LENGTH_SUM,
                BenchmarkOperation.MANUAL_REPLACE_ALL,
                BenchmarkOperation.CAPTURE_GROUPS,
                BenchmarkOperation.FIND_GROUP
            )
            .doesNotContain(BenchmarkOperation.SPLIT_LENGTH_SUM)
    }

    @Test
    fun utf8ReplacementTrialsCoverGenericBenchmarkFamilies() {
        val plan: CrossEngineBenchmarkPlan = CrossEngineBenchmarkPlan.load()

        Assertions.assertThat<String?>(
            List.of<MutableList<CrossEngineBenchmarkPlan.Trial?>?>(
                plan.trials(TimingGroup.NANOSECONDS),
                plan.trials(TimingGroup.MICROSECONDS)
            )
                .stream()
                .flatMap<CrossEngineBenchmarkPlan.Trial?> { obj: MutableList<CrossEngineBenchmarkPlan.Trial?>? -> obj!!.stream() }
                .filter { trial: CrossEngineBenchmarkPlan.Trial? -> trial!!.variant === SAFERE_UTF8 }
                .map<String?> { id() })
            .contains(
                "ApplicationBenchmark.secretRedaction@safere-utf8",
                "RealWorldRegexBenchmark.runBenchmark.markupImageLink.match.1000@safere-utf8",
                "ReplaceBenchmark.literalReplaceFirst@safere-utf8",
                "ReplaceBenchmark.literalReplaceFirstNoMatch@safere-utf8",
                "ReplaceBenchmark.startAnchoredReplaceFirstNoMatch.literal@safere-utf8",
                "ReplaceBenchmark.startAnchoredReplaceFirstNoMatch.charClass@safere-utf8",
                "ReplaceBenchmark.startAnchoredReplaceAllNoMatch.literal@safere-utf8",
                "ReplaceBenchmark.startAnchoredReplaceAllNoMatch.charClass@safere-utf8",
                "ReplaceBenchmark.pigLatinReplaceAll@safere-utf8",
                "ReplaceBenchmark.anchoredReplace@safere-utf8",
                "ReplaceBenchmark.manualReplaceAll@safere-utf8"
            )
    }

    @Test
    fun unsupportedCombinationIsDistinctFromMissingImplementation() {
        val plan: CrossEngineBenchmarkPlan = CrossEngineBenchmarkPlan.load()

        Assertions.assertThat<CrossEngineBenchmarkPlan.Trial?>(plan.resolve("RegexBenchmark.literalMatch@safere-utf8"))
            .isNotNull()
        Assertions.assertThat<CrossEngineBenchmarkPlan.Trial?>(plan.resolve("HttpBenchmark.httpExtract@safere-utf8"))
            .isNotNull()
        Assertions.assertThatThrownBy(ThrowingCallable { plan.resolve("Issue481ScalingBenchmark.splitWords.128@safere-utf8") })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unsupported cross-engine combination")
            .hasMessageContaining("split")
        Assertions.assertThatThrownBy(ThrowingCallable { plan.resolve("missing.workload@safere-string") })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Unknown cross-engine workload ID: missing.workload")
    }

    @Test
    fun inputRepresentationsExposeTheirTimingBoundaries() {
        Assertions.assertThat<RegexEngineVariant.InputRepresentation?>(SAFERE_STRING.inputRepresentation())
            .isEqualTo(RegexEngineVariant.InputRepresentation.JAVA_STRING)
        Assertions.assertThat<RegexEngineVariant.InputRepresentation?>(SAFERE_UTF8.inputRepresentation())
            .isEqualTo(RegexEngineVariant.InputRepresentation.PREEXISTING_UTF8)
        Assertions.assertThat<RegexEngineVariant.InputRepresentation?>(RE2_FFM_STRING_CONVERSION.inputRepresentation())
            .isEqualTo(RegexEngineVariant.InputRepresentation.JAVA_STRING_WITH_TIMED_UTF8_CONVERSION)
    }

    @Test
    fun re2BasedAdaptersShareARegexSyntaxProfile() {
        Assertions.assertThat(RE2J_STRING.patternProfile()).isEqualTo("re2")
        Assertions.assertThat(RE2_FFM_STRING_CONVERSION.patternProfile()).isEqualTo("re2")
    }

    @ParameterizedTest
    @CsvSource(
        "0,15", "CASE_INSENSITIVE,8", "CASE_INSENSITIVE_UNICODE_CASE,7", "CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS,5"
    )
    fun unicodeCompileRowsAdmitOnlyEquivalentRe2jOptions(flagSet: String?, expectedRunnable: Int) {
        val plan: MaterializedExecutionPlan = MaterializedExecutionPlan.load()
        val rows =
            plan.entries().stream()
                .filter { entry: MaterializedExecutionPlan.Entry -> entry.engineId == "re2j-string" }
                .filter { entry: MaterializedExecutionPlan.Entry -> entry.workloadId.startsWith("UnicodeCompileBenchmark.compile.") }
                .filter { entry: MaterializedExecutionPlan.Entry -> entry.workloadId.endsWith("." + flagSet) }
                .toList()
        Assertions.assertThat<MaterializedExecutionPlan.Entry?>(rows).hasSize(19)
        Assertions.assertThat<MaterializedExecutionPlan.Entry?>(rows.stream().filter { runnable() })
            .hasSize(expectedRunnable)
        for (row in rows) {
            if (!row.runnable()) {
                Assertions.assertThat(row.exclusion.kind).isIn("unsupportedSyntax", "explicitTrialExclusion")
                Assertions.assertThat(row.exclusion.reason).isNotBlank()
            }
        }
        for (property in mutableListOf<String?>("alphabetic", "ideographic", "emoji", "extendedPictographic")) {
            val row =
                plan.resolve(
                    "UnicodeCompileBenchmark.compile." + property + "." + flagSet + "@re2j-string"
                )
            Assertions.assertThat(row.runnable()).isFalse()
            Assertions.assertThat(row.exclusion.kind).isEqualTo("unsupportedSyntax")
        }
    }

    @Test
    fun admittedUnicodeCompileRowsPreserveDeclaredSemantics() {
        val plan: MaterializedExecutionPlan = MaterializedExecutionPlan.load()
        // Stable characters exercise folding, categories, scripts, blocks and predefined classes.
        // Engine Unicode database versions differ; this is not a Unicode-version conformance test.
        val inputs: MutableList<String> = mutableListOf<String?>(
            "", "a", "A", "I", "i", "İ", "ı", "ſ", "K", "ß", "ẞ", "ǅ", "ǆ", "Ǆ", "α", "Α", "Σ", "ς",
            "σ", "中", "𠀀", "𐐀", "𐐨", "1", "١", "_", " ", "\t", "\n", "\r", "\u000c", "\u000B",
            "\u00A0", "\u2003", "!", "abc_12"
        )
        for (row in plan.entries()) {
            if ((row.engineId != "re2j-string") || !row.runnable() || !(row.workloadId.startsWith("UnicodeCompileBenchmark.compile.")
                        || row.workloadId.startsWith("UnicodeFirstCompileBenchmark.firstCompile."))
            ) {
                continue
            }
            val flagSet =
                (row.workload.arguments.get("flagSet") as RecipeString)
                    .value
            val canonical: String =
                plan.resolve(row.workloadId + "@jdk-string").workload.patterns.first()
            val jdk =
                java.util.regex.Pattern.compile(canonical, BenchmarkFlags.parse(flagSet))
            val re2j =
                RE2J_STRING.compileForBenchmark(
                    row.workload.patterns.first(), flagSet
                ) as Pattern
            for (input in inputs) {
                Assertions.assertThat(re2j.matcher(input).matches())
                    .`as`("%s input %s", row.id, input)
                    .isEqualTo(jdk.matcher(input).matches())
            }
            if (flagSet != "0") {
                val unflaggedJdk = java.util.regex.Pattern.compile(canonical)
                val unflaggedRe2j =
                    Pattern.compile(row.workload.patterns.first())
                var codePoint = 0
                while (codePoint <= Character.MAX_VALUE.code) {
                    if (Character.isSurrogate(codePoint.toChar())) {
                        codePoint++
                        continue
                    }
                    val input = Character.toString(codePoint)
                    // Ignore existing Unicode database differences; isolate the admitted flag mapping.
                    if (unflaggedJdk.matcher(input).matches() != unflaggedRe2j.matcher(input).matches()) {
                        codePoint++
                        continue
                    }
                    Assertions.assertThat(re2j.matcher(input).matches())
                        .`as`("%s U+%04X", row.id, codePoint)
                        .isEqualTo(jdk.matcher(input).matches())
                    codePoint++
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource("0,9", "CASE_INSENSITIVE_UNICODE_CASE,4", "UNICODE_CHARACTER_CLASS,0")
    fun coldUnicodeCompileRowsRetainEquivalentRe2jOptions(flagSet: String?, expectedRunnable: Int) {
        Assertions.assertThat<MaterializedExecutionPlan.Entry?>(
            MaterializedExecutionPlan.load().entries().stream()
                .filter { entry: MaterializedExecutionPlan.Entry? -> entry!!.engineId == "re2j-string" && entry.runnable() }
                .filter { entry: MaterializedExecutionPlan.Entry? -> entry!!.workloadId.startsWith("UnicodeFirstCompileBenchmark.firstCompile.") }
                .filter { entry: MaterializedExecutionPlan.Entry? -> entry!!.workloadId.endsWith("." + flagSet) })
            .hasSize(expectedRunnable)
    }

    @ParameterizedTest
    @CsvSource(
        "uppercaseLetter,CASE_INSENSITIVE_UNICODE_CASE,ı",
        "lowercaseLetter,CASE_INSENSITIVE,A",
        "titlecaseLetter,CASE_INSENSITIVE,A",
        "letterIntersection,CASE_INSENSITIVE,a",
        "blockBasicLatin,CASE_INSENSITIVE,K",
        "asciiLower,CASE_INSENSITIVE,ſ",
        "asciiRange,CASE_INSENSITIVE_UNICODE_CASE,İ",
        "word,CASE_INSENSITIVE_UNICODE_CASE,K",
        "word,CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS,α",
        "digit,CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS,١",
        "space,CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS,'\u2003'"
    )
    fun excludedUnicodeCompileRowsHaveDemonstrablyDifferentFlagSemantics(
        regex: String, flagSet: String, input: String
    ) {
        val plan: MaterializedExecutionPlan = MaterializedExecutionPlan.load()
        val prefix = "UnicodeCompileBenchmark.compile." + regex + "."
        Assertions.assertThat(plan.resolve(prefix + flagSet + "@re2j-string").exclusion.kind)
            .isEqualTo("explicitTrialExclusion")
        val canonical: String =
            plan.resolve(prefix + flagSet + "@jdk-string").workload.patterns.first()
        val alternate: String = plan.resolve(prefix + "0@re2j-string").workload.patterns.first()
        Assertions.assertThat(
            Pattern.compile(alternate, Pattern.CASE_INSENSITIVE)
                .matcher(input)
                .matches()
        )
            .isNotEqualTo(
                java.util.regex.Pattern.compile(canonical, BenchmarkFlags.parse(flagSet))
                    .matcher(input)
                    .matches()
            )
    }

    @ParameterizedTest
    @CsvSource(
        "0,0", "CASE_INSENSITIVE,1", "CASE_INSENSITIVE_UNICODE_CASE,1", "CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS,1"
    )
    fun re2jCompileAdapterMapsFlagIdentitiesExplicitly(flagSet: String?, expectedFlags: Int) {
        val pattern =
            RE2J_STRING.compileForBenchmark("a", flagSet) as Pattern
        Assertions.assertThat(pattern.flags()).isEqualTo(expectedFlags)
        Assertions.assertThat(pattern.matcher("A").matches()).isEqualTo(expectedFlags != 0)
        Assertions.assertThat(pattern.matcher("\n").matches()).isFalse()
    }

    @Test
    fun re2jCompileAdapterRejectsUnsupportedFlagIdentities() {
        Assertions.assertThatThrownBy(
            ThrowingCallable { RE2J_STRING.compileForBenchmark("a", "UNICODE_CHARACTER_CLASS") })
            .isInstanceOf(UnsupportedOperationException::class.java)
            .hasMessageContaining("UNICODE_CHARACTER_CLASS")
        Assertions.assertThatThrownBy(ThrowingCallable {
            RE2J_STRING.compileForBenchmark(
                "a",
                "unknown"
            )
        })
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @ParameterizedTest
    @CsvSource(
        "newPerInvocation,capture,5,3", "retained,capture,1,3", "newPerInvocation,boolean,5,0", "retained,boolean,1,0"
    )
    fun sampledCaptureWorkloadHonorsMatcherLifetimeAndReadFrequency(
        reuse: String, mode: String?, expectedMatchers: Int, expectedReads: Int
    ) {
        val creations = intArrayOf(0)
        val resets = intArrayOf(0)
        val reads = intArrayOf(0)
        val pattern: CompiledRegex =
            object : CompiledRegex {
                override fun matcher(input: RegexInput?): MatchCursor {
                    creations[0]++
                    return object : MatchCursor {
                        override fun find(): Boolean {
                            return true
                        }

                        override fun start(group: Int): Int {
                            Assertions.assertThat(group).isEqualTo(1)
                            reads[0]++
                            return 0
                        }

                        override fun reset() {
                            resets[0]++
                        }
                    }
                }
            }
        val task =
            BenchmarkOperation.FIND_SAMPLED_GROUP.bind(
                SAFERE_STRING,
                mutableListOf<String?>("(a)"),
                List.of<CompiledRegex?>(pattern),
                List.of<RegexInput?>(StringRegexInput("a")),
                intArrayOf(1),
                null,
                0,
                DeclarativeBenchmarkPlan.MatcherLifecycle(
                    MatcherReuse.fromJson(reuse), mutableListOf<LifecycleStep?>()
                ),
                null,
                0,
                2,
                mode
            )
        val blackhole =
            Blackhole(
                "Today's password is swordfish. I understand instantiating Blackholes directly is"
                        + " dangerous."
            )
        for (i in 0..4) {
            task.run(blackhole)
        }
        Assertions.assertThat(creations[0]).isEqualTo(expectedMatchers)
        Assertions.assertThat(reads[0]).isEqualTo(expectedReads)
        Assertions.assertThat(resets[0]).isEqualTo(if (reuse == "retained") 5 else 0)
    }

    @Test
    fun matcherLifecycleControlsCursorConstruction() {
        val blackhole =
            Blackhole(
                "Today's password is swordfish. I understand instantiating Blackholes directly is"
                        + " dangerous."
            )
        for (operation in List.of<BenchmarkOperation?>(
            BenchmarkOperation.MATCHER_REGION_FIND,
            BenchmarkOperation.MATCHER_RESET_FIND
        )) {
            for (reuse in List.of<MatcherReuse?>(
                MatcherReuse.RETAINED,
                MatcherReuse.NEW_PER_INVOCATION
            )) {
                val created: MutableList<MatchCursor> = ArrayList<MatchCursor>()
                val pattern: CompiledRegex =
                    object : CompiledRegex {
                        override fun matcher(input: RegexInput?): MatchCursor {
                            val cursor: MatchCursor =
                                object : MatchCursor {
                                    private var found = false

                                    override fun find(): Boolean {
                                        val result = !found
                                        found = true
                                        return result
                                    }

                                    override fun reset() {
                                        found = false
                                    }

                                    override fun region(start: Int, end: Int) {
                                        Assertions.assertThat(start).isEqualTo(1)
                                        Assertions.assertThat(end).isEqualTo(3)
                                        found = false
                                    }
                                }
                            created.add(cursor)
                            return cursor
                        }
                    }
                val steps =
                    if (operation == BenchmarkOperation.MATCHER_REGION_FIND)
                        List.of<LifecycleStep?>(
                            LifecycleStep(
                                LifecycleStepKind.REGION, 1, 3, null
                            )
                        )
                    else
                        List.of<LifecycleStep?>(
                            LifecycleStep(
                                LifecycleStepKind.RESET, null, null, null
                            )
                        )
                val task =
                    operation.bind(
                        SAFERE_STRING,
                        mutableListOf<String?>("x"),
                        List.of<CompiledRegex?>(pattern),
                        List.of<RegexInput?>(StringRegexInput("abcd")),
                        IntArray(0),
                        null,
                        0,
                        MatcherLifecycle(reuse, steps),
                        null,
                        0,
                        0,
                        "boolean"
                    )
                val retained = reuse == MatcherReuse.RETAINED
                Assertions.assertThat<MatchCursor?>(created).`as`("%s %s binding", operation, reuse)
                    .hasSize(if (retained) 1 else 0)
                task.run(blackhole)
                task.run(blackhole)
                Assertions.assertThat<MatchCursor?>(created).`as`("%s %s invocations", operation, reuse)
                    .hasSize(if (retained) 1 else 2)
            }
        }
    }

    @Test
    fun boundTaskReusesInputPreparedBeforeTimedInvocation() {
        val preparedInput: RegexInput =
            StringRegexInput("materialized before binding")
        val observedInputs: MutableList<RegexInput> = ArrayList<RegexInput>()
        val pattern: CompiledRegex =
            object : CompiledRegex {
                override fun find(input: RegexInput?): Boolean {
                    observedInputs.add(input!!)
                    return true
                }
            }
        val task =
            BenchmarkOperation.FIND.bind(
                SAFERE_STRING,
                mutableListOf<String?>("x"),
                List.of<CompiledRegex?>(pattern),
                List.of<RegexInput?>(preparedInput),
                IntArray(0),
                null,
                0,
                MatcherLifecycle.NONE,
                null,
                0,
                0,
                null
            )
        val blackhole =
            Blackhole(
                "Today's password is swordfish. I understand instantiating Blackholes directly is"
                        + " dangerous."
            )

        task.run(blackhole)
        task.run(blackhole)

        Assertions.assertThat<RegexInput?>(observedInputs)
            .hasSize(2)
            .allSatisfy(ThrowingConsumer { input: RegexInput? ->
                Assertions.assertThat<RegexInput?>(input).isSameAs(preparedInput)
            })
    }

    @Test
    fun preparationValidatesDeclaredExpectedResult() {
        CrossEngineTrialRunner.prepare(
            "ApplicationBenchmark.uuidValidation@safere-string",
            TimingGroup.NANOSECONDS
        ).use { ignored ->
            Assertions.assertThat<CrossEngineTrialRunner?>(ignored).isNotNull()
        }
    }

    @Test
    fun expandedUtf8OperationsPrepareAndRunThroughTheGenericAdapter() {
        val plan: CrossEngineBenchmarkPlan = CrossEngineBenchmarkPlan.load()
        val blackhole =
            Blackhole(
                "Today's password is swordfish. I understand instantiating Blackholes directly is"
                        + " dangerous."
            )
        for (id in mutableListOf<String?>(
            "RegexBenchmark.literalMatch@safere-utf8",
            "ApplicationBenchmark.uuidValidation@safere-utf8",
            "HttpBenchmark.httpFull@safere-utf8",
            "HttpBenchmark.httpExtract@safere-utf8",
            "ApplicationBenchmark.stackTraceExtract@safere-utf8",
            "CaptureScalingBenchmark.capture1@safere-utf8",
            "CaptureRecoveryBenchmark.stack.text@safere-utf8",
            "CaptureRecoveryBenchmark.stack.mixed@safere-utf8",
            "CaptureRecoveryBenchmark.scheme.rejection@safere-utf8",
            "CaptureRecoveryBenchmark.stack.matches.512@safere-utf8",
            "CaptureRecoveryBenchmark.stack.anchoredFind.513@safere-utf8",
            "MatcherApiBenchmark.lookingAt@safere-utf8",
            "MatcherApiBenchmark.regionFind@safere-utf8",
            "MatcherApiBenchmark.resetAndFind@safere-utf8",
            "Issue481ScalingBenchmark.blockFind.128@safere-utf8",
            "PathologicalBenchmark.pathological.10@safere-utf8"
        )) {
            val trial = plan.resolve(id)
            CrossEngineTrialRunner.Companion.prepare(id, trial.workload.timingGroup).use { runner ->
                runner.run(blackhole)
            }
        }
        Assertions.assertThatThrownBy(
            ThrowingCallable { plan.resolve("MatcherApiBenchmark.resetAndFind@re2-ffm-string-conversion") })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unsupportedTimingBoundary")
    }

    @Test
    fun ffmPublicMatcherOperationsPrepareAndRunThroughTheGenericAdapter() {
        val plan: CrossEngineBenchmarkPlan = CrossEngineBenchmarkPlan.load()
        val blackhole =
            Blackhole(
                "Today's password is swordfish. I understand instantiating Blackholes directly is"
                        + " dangerous."
            )
        for (workload in mutableListOf<String?>(
            "MatcherApiBenchmark.lookingAt",
            "MatcherApiBenchmark.newMatcherRegionFind",
            "MatcherApiBenchmark.newMatcherRegionMatches"
        )) {
            val id = workload + "@re2-ffm-string-conversion"
            val trial = plan.resolve(id)
            CrossEngineTrialRunner.prepare(id, trial.workload.timingGroup).use { runner ->
                runner.run(blackhole)
                runner.run(blackhole)
            }
        }
        Assertions.assertThat<EngineCapability?>(RE2_FFM_STRING_CONVERSION.capabilities())
            .doesNotContain(EngineCapability.APPEND_REPLACEMENT)
    }

    @Test
    fun utf8ReplacementOperationsPrepareAndRunThroughTheGenericAdapter() {
        val plan: CrossEngineBenchmarkPlan = CrossEngineBenchmarkPlan.load()
        val blackhole =
            Blackhole(
                "Today's password is swordfish. I understand instantiating Blackholes directly is"
                        + " dangerous."
            )
        for (id in mutableListOf<String?>(
            "ReplaceBenchmark.literalReplaceFirst@safere-utf8",
            "ReplaceBenchmark.literalReplaceFirstNoMatch@safere-utf8",
            "ReplaceBenchmark.startAnchoredReplaceFirstNoMatch.literal@safere-utf8",
            "ReplaceBenchmark.startAnchoredReplaceFirstNoMatch.charClass@safere-utf8",
            "ReplaceBenchmark.startAnchoredReplaceAllNoMatch.literal@safere-utf8",
            "ReplaceBenchmark.startAnchoredReplaceAllNoMatch.charClass@safere-utf8",
            "ReplaceBenchmark.emptyReplaceAll@safere-utf8",
            "ReplaceBenchmark.pigLatinReplaceAll@safere-utf8",
            "ReplaceBenchmark.manualReplaceAll@safere-utf8",
            "ApplicationBenchmark.secretRedaction@safere-utf8",
            "RealWorldRegexBenchmark.runBenchmark.markupImageLink.match.1000@safere-utf8"
        )) {
            val trial = plan.resolve(id)
            CrossEngineTrialRunner.Companion.prepare(id, trial.workload.timingGroup).use { runner ->
                runner.run(blackhole)
            }
        }
    }

    @Test
    fun utf8ReplacementAdapterExpandsLiteralNumberedAndNamedTemplates() {
        val variant = SAFERE_UTF8
        val input: RegexInput =
            utf8Input("alpha=one&beta=two".toByteArray(StandardCharsets.UTF_8))

        variant.compile("&").use { literal ->
            variant.compile("([a-z]+)=([a-z]+)").use { numbered ->
                variant.compile("(?<key>[a-z]+)=(?<value>[a-z]+)").use { named ->
                    Assertions.assertThat(
                        literal.replaceFirst(input, variant.prepareReplacement(";")).validationValue()
                    )
                        .isEqualTo("alpha=one;beta=two")
                    Assertions.assertThat(
                        numbered.replaceAll(input, variant.prepareReplacement("$2:$1")).validationValue()
                    )
                        .isEqualTo("one:alpha&two:beta")
                    Assertions.assertThat(
                        named
                            .replaceAll(input, variant.prepareReplacement("\${key}=[\${value}]"))
                            .validationValue()
                    )
                        .isEqualTo("alpha=[one]&beta=[two]")
                }
            }
        }
    }

    @Test
    fun groupParticipationUsesBoundsWithoutMaterializingCaptureText() {
        val cursor: MatchCursor =
            object : MatchCursor {
                override fun start(group: Int): Int {
                    return if (group == 1) 4 else -1
                }

                override fun group(group: Int): String? {
                    throw AssertionError("group participation must not materialize capture text")
                }
            }

        Assertions.assertThat(cursor.groupParticipated(1)).isTrue()
        Assertions.assertThat(cursor.groupParticipated(2)).isFalse()
    }

    @Test
    fun coldStartPreparationDefersCompilationUntilTheMeasuredTask() {
        val workload =
            CrossEngineWorkload(
                "Synthetic.coldCompile",
                BenchmarkOperation.COMPILE,
                mutableListOf<String?>("("),
                mutableListOf<String?>(),
                IntArray(0),
                null,
                null,
                0,
                MatcherLifecycle.NONE,
                "",
                0,
                1,
                null,
                Measurement(
                    MeasurementMode.SINGLE_SHOT_COLD_START,
                    TimingUnit.MILLISECONDS,
                    EnumSet.of<ExecutionConstraint?>(
                        ExecutionConstraint.FRESH_PROCESS_PER_INVOCATION
                    )
                ),
                TimingGroup.MILLISECONDS
            )
        val trial =
            CrossEngineBenchmarkPlan.Trial(workload, SAFERE_STRING)
        val blackhole =
            Blackhole(
                "Today's password is swordfish. I understand instantiating Blackholes directly is"
                        + " dangerous."
            )

        CrossEngineTrialRunner.prepareColdStart(
            BenchmarkData.get(), trial, TimingGroup.MILLISECONDS
        ).use { runner ->
            Assertions.assertThatThrownBy(ThrowingCallable { runner.run(blackhole) })
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun migratedOperationsReplayCorrectnessForEveryCompatibleEngine() {
        val trials: MutableList<String> = mutableListOf<String?>(
            "CompileBenchmark.compileSimple@safere-string",
            "CompileBenchmark.compileSimple@jdk-string",
            "CompileBenchmark.compileSimple@re2j-string",
            "CaptureScalingBenchmark.capture3@safere-string",
            "CaptureScalingBenchmark.capture3@jdk-string",
            "CaptureScalingBenchmark.capture3@re2j-string",
            "ReplaceBenchmark.literalReplaceFirst@safere-string",
            "ReplaceBenchmark.literalReplaceFirst@jdk-string",
            "ReplaceBenchmark.literalReplaceFirst@re2j-string",
            "ReplaceBenchmark.anchoredReplace@safere-string",
            "ReplaceBenchmark.anchoredReplace@jdk-string",
            "ReplaceBenchmark.anchoredReplace@re2j-string",
            "ReplaceBenchmark.manualReplaceAll@safere-string",
            "ReplaceBenchmark.manualReplaceAll@jdk-string",
            "ReplaceBenchmark.manualReplaceAll@re2j-string",
            "Issue481ScalingBenchmark.splitWords.128@safere-string",
            "Issue481ScalingBenchmark.splitWords.128@jdk-string",
            "Issue481ScalingBenchmark.splitWords.128@re2j-string",
            "Issue481ScalingBenchmark.schemeExtract.128@safere-string",
            "Issue481ScalingBenchmark.schemeExtract.128@jdk-string",
            "Issue481ScalingBenchmark.schemeExtract.128@re2j-string",
            "ScrubberBenchmark.scrubWithDirectives@safere-string",
            "ScrubberBenchmark.scrubWithDirectives@jdk-string",
            "ScrubberBenchmark.scrubWithDirectives@re2j-string",
            "MatcherApiBenchmark.lookingAt@safere-string",
            "MatcherApiBenchmark.lookingAt@jdk-string",
            "MatcherApiBenchmark.lookingAt@re2j-string",
            "MatcherApiBenchmark.regionFind@safere-string",
            "MatcherApiBenchmark.regionFind@jdk-string",
            "MatcherApiBenchmark.resetAndFind@safere-string",
            "MatcherApiBenchmark.resetAndFind@jdk-string",
            "MatcherApiBenchmark.resetAndFind@re2j-string",
            "JavaCharacterClassBenchmark.compileAndFindJavaLetter@safere-string",
            "JavaCharacterClassBenchmark.compileAndFindJavaLetter@jdk-string",
            "JavaCharacterClassBenchmark.findJavaLetter@safere-string",
            "JavaCharacterClassBenchmark.findJavaLetter@jdk-string"
        )
        val plan: CrossEngineBenchmarkPlan = CrossEngineBenchmarkPlan.load()

        for (trialId in trials) {
            val timingGroup = plan.resolve(trialId).workload.timingGroup
            CrossEngineTrialRunner.prepare(trialId, timingGroup).use { ignored ->
                Assertions.assertThat<CrossEngineTrialRunner?>(ignored).`as`(trialId).isNotNull()
            }
        }
    }

    @Test
    fun specializedMeasurementModesSelectStableGenericTrials() {
        val first: SpecializedBenchmarkPlan = SpecializedBenchmarkPlan.load()
        val second: SpecializedBenchmarkPlan = SpecializedBenchmarkPlan.load()

        Assertions.assertThat<SpecializedBenchmarkPlan.Trial?>(first.averageTimeTrials())
            .hasSizeGreaterThanOrEqualTo(MINIMUM_SPECIALIZED_AVERAGE_TIME_TRIALS)
            .allMatch(
                Predicate { trial: SpecializedBenchmarkPlan.Trial? ->
                    (trial!!.workload.measurement.mode
                            == MeasurementMode.AVERAGE_TIME)
                })
            .extracting<String?, RuntimeException?>(ThrowingExtractor { id() })
            .containsExactlyElementsOf(
                second.averageTimeTrials().stream().map<String?> { id() }.toList()
            )
        Assertions.assertThat<SpecializedBenchmarkPlan.Trial?>(first.retainedMemoryTrials())
            .hasSizeGreaterThanOrEqualTo(MINIMUM_SPECIALIZED_RETAINED_MEMORY_TRIALS)
            .allMatch(
                Predicate { trial: SpecializedBenchmarkPlan.Trial? ->
                    (trial!!.workload.measurement.mode
                            == MeasurementMode.RETAINED_MEMORY)
                })
            .extracting<String?, RuntimeException?>(ThrowingExtractor { id() })
            .containsExactlyElementsOf(
                second.retainedMemoryTrials().stream()
                    .map<String?> { id() }
                    .toList())
    }

    @Test
    fun collectionPlanDiscoversGenericRunnersAndStableReportRows() {
        val plan: BenchmarkCollectionPlan = BenchmarkCollectionPlan.load()

        Assertions.assertThat<BenchmarkCollectionPlan.Runner?>(plan.runners())
            .extracting<String?, RuntimeException?>(BenchmarkCollectionPlan.Runner::benchmark)
            .containsExactly(
                "org.safere.benchmark.CrossEngineBenchmark.run",
                "org.safere.benchmark.CrossEngineScalingBenchmark.run",
                "org.safere.benchmark.CrossEngineNoForkBenchmark.run",
                "org.safere.benchmark.CrossEngineColdStartBenchmark.run",
                "org.safere.benchmark.SpecializedBenchmark.run"
            )
        val runnerTrialIds =
            plan.runners().stream()
                .flatMap<String?> { runner: BenchmarkCollectionPlan.Runner? -> runner!!.trialIds.stream() }.toList()
        Assertions.assertThat<BenchmarkCollectionPlan.Runner?>(plan.runners())
            .allMatch(Predicate { runner: BenchmarkCollectionPlan.Runner? -> !runner!!.trialIds.isEmpty() })
        Assertions.assertThat<String?>(runnerTrialIds)
            .hasSizeGreaterThanOrEqualTo(MINIMUM_COLLECTION_RUNNER_TRIALS)
            .doesNotHaveDuplicates()
        Assertions.assertThat<CollectionTrial?>(plan.reportPlan().trials)
            .extracting<String?, RuntimeException?>(CollectionTrial::id)
            .containsExactlyInAnyOrderElementsOf(runnerTrialIds)
        Assertions.assertThat<ReportExclusion?>(plan.reportPlan().exclusions).isNotEmpty().doesNotHaveDuplicates()
        val expectedSmokeTrialIds: MutableSet<String> = HashSet<String>()
        for (runner in plan.runners()) {
            val firstTrial: String = runner.trialIds.first()
            val firstWorkload = firstTrial.substring(0, firstTrial.lastIndexOf('@'))
            runner.trialIds.stream()
                .filter { trialId: String? -> trialId!!.startsWith(firstWorkload + "@") }
                .forEach { e: String? -> expectedSmokeTrialIds.add(e!!) }
        }
        Assertions.assertThat<CollectionTrial?>(plan.reportPlan(true).trials)
            .extracting<String?, RuntimeException?>(CollectionTrial::id)
            .containsExactlyInAnyOrderElementsOf(expectedSmokeTrialIds)
    }

    @Test
    fun collectionPlanSelectsOnlyRunnersMatchedByJmhFilter() {
        val plan: BenchmarkCollectionPlan = BenchmarkCollectionPlan.load()

        Assertions.assertThat<BenchmarkCollectionPlan.Runner?>(
            plan.runnersMatching("^org\\.safere\\.benchmark\\.CrossEngineScalingBenchmark\\.run$")
        )
            .extracting<String?, RuntimeException?>(BenchmarkCollectionPlan.Runner::parameter)
            .containsExactly("crossEngineScalingTrial")
        Assertions.assertThat<BenchmarkCollectionPlan.Runner?>(plan.runnersMatching("CrossEngine(?:Scaling|NoFork)Benchmark"))
            .extracting<String?, RuntimeException?>(BenchmarkCollectionPlan.Runner::parameter)
            .containsExactly("crossEngineScalingTrial", "crossEngineNoForkTrial")
        Assertions.assertThat<BenchmarkCollectionPlan.Runner?>(plan.runnersMatching("UnparameterizedBenchmark"))
            .isEmpty()
        Assertions.assertThat<String?>(
            plan.launcherArguments(
                "CrossEngineScalingBenchmark", "/tmp/benchmark jar.jar", mutableSetOf<String?>()
            )
        )
            .startsWith("-jar", "\"/tmp/benchmark jar.jar\"", "-p")
            .element(3)
            .asString()
            .startsWith("\"crossEngineScalingTrial=")
        Assertions.assertThat<String?>(
            plan.launcherArguments(
                "CrossEngineScalingBenchmark",
                "/tmp/benchmark.jar",
                mutableSetOf<String?>("crossEngineScalingTrial")
            )
        )
            .containsExactly("-jar", "\"/tmp/benchmark.jar\"")
    }

    @ParameterizedTest
    @CsvSource(
        "nanoseconds, RegexBenchmark.literalMatch, crossEngineTrial",
        "microseconds, SearchScalingBenchmark.searchEasyFail.1024, crossEngineScalingTrial"
    )
    @ResourceLock(Resources.SYSTEM_OUT)
    fun prefixSelectionsCanBeWrittenDirectlyToArgumentFiles(
        timingGroup: String, prefix: String, parameter: String?
    ) {
        val group =
            if (timingGroup == "nanoseconds")
                TimingGroup.NANOSECONDS
            else
                TimingGroup.MICROSECONDS
        val expected: MutableList<String> =
            CrossEngineBenchmarkPlan.load().trials(group).stream()
                .filter { trial: CrossEngineBenchmarkPlan.Trial? -> trial!!.workload.id.startsWith(prefix) }
                .map<String?> { id() }
                .toList()
        Assertions.assertThat<String?>(expected).isNotEmpty()
        val output = ByteArrayOutputStream()
        val original = System.out
        try {
            PrintStream(output, true, StandardCharsets.UTF_8).use { capture ->
                System.setOut(capture)
                CrossEngineBenchmarkPlan.main(arrayOf<String>("--argument-file", timingGroup, prefix))
            }
        } finally {
            System.setOut(original)
        }
        Assertions.assertThat<String?>(output.toString(StandardCharsets.UTF_8).lineSequence().asStream().toList())
            .containsExactly("-p", "\"" + parameter + "=" + String.join(",", expected) + "\"")
    }

    @Test
    fun declaredRunnerArgumentsUseTheSelectedTimingOrAllocationTrials() {
        val plan: BenchmarkCollectionPlan = BenchmarkCollectionPlan.load()
        val benchmark = "org.safere.benchmark.CrossEngineBenchmark.run"
        val jar = "/tmp/benchmark jar.jar"
        val timing =
            plan.runners().stream()
                .filter { runner: BenchmarkCollectionPlan.Runner? -> runner!!.benchmark == benchmark }
                .findFirst()
                .orElseThrow()
        val allocation =
            plan.allocationRunners().stream()
                .filter { runner: BenchmarkCollectionPlan.Runner? -> runner!!.benchmark == benchmark }
                .findFirst()
                .orElseThrow()

        Assertions.assertThat<String?>(plan.declaredLauncherArguments(benchmark, jar, false, false, null))
            .containsExactly(
                "-jar",
                "\"/tmp/benchmark jar.jar\"",
                "-p",
                "\"crossEngineTrial=" + String.join(",", timing.trialIds) + "\""
            )
        Assertions.assertThat<String?>(plan.declaredLauncherArguments(benchmark, jar, true, false, null))
            .containsExactly(
                "-jar",
                "\"/tmp/benchmark jar.jar\"",
                "-p",
                "\"crossEngineTrial=" + String.join(",", allocation.trialIds) + "\""
            )
        val firstWorkload: String =
            allocation.trialIds.first().split("@".toRegex(), limit = 2).toTypedArray()[0]
        val smokeTrials =
            allocation.trialIds.stream()
                .filter { trialId: String -> trialId.startsWith(firstWorkload + "@") }
                .toList()
        Assertions.assertThat<String?>(plan.declaredLauncherArguments(benchmark, jar, true, true, null))
            .containsExactly(
                "-jar",
                "\"/tmp/benchmark jar.jar\"",
                "-p",
                "\"crossEngineTrial=" + String.join(",", smokeTrials) + "\""
            )
        Assertions.assertThatThrownBy(ThrowingCallable {
            plan.declaredLauncherArguments(
                "missing",
                jar,
                false,
                false,
                null
            )
        })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Unknown declared benchmark runner: missing")
    }

    @Test
    fun declaredArgumentFilesPreserveProviderGroupsAndSmokeSelection() {
        val plan: BenchmarkCollectionPlan = BenchmarkCollectionPlan.load()
        val jar = "/tmp/benchmark \"quoted\" \\ jar.jar"
        for (allocation in mutableListOf<Boolean?>(false, true)) {
            for (smoke in mutableListOf<Boolean?>(false, true)) {
                for (runner in plan.executionRunners(allocation!!, smoke!!)) {
                    val provider: String =
                        RegexEngineVariant.fromId(
                            runner
                                .trialIds
                                .first()
                                .substring(runner.trialIds.first().lastIndexOf('@') + 1)
                        )
                            .scanProvider()
                    Assertions.assertThat<String?>(
                        plan.declaredLauncherArguments(
                            runner.benchmark, jar, allocation, smoke, provider
                        )
                    )
                        .containsExactly(
                            "-jar",
                            BenchmarkCollectionPlan.argumentFileToken(jar),
                            "-p",
                            BenchmarkCollectionPlan.argumentFileToken(
                                runner.parameter + "=" + String.join(",", runner.trialIds)
                            )
                        )
                }
            }
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_OUT)
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    fun declaredArgumentFileCommandFiltersProvidersAfterSmokeSelection() {
        val plan: BenchmarkCollectionPlan = BenchmarkCollectionPlan.load()
        val property = "safere.benchmark.scanProvider"
        val previous = System.getProperty(property)
        val original = System.out
        try {
            for (runner in plan.executionRunners(false, true)) {
                val provider: String =
                    RegexEngineVariant.fromId(
                        runner
                            .trialIds
                            .first()
                            .substring(runner.trialIds.first().lastIndexOf('@') + 1)
                    )
                        .scanProvider()
                System.setProperty(property, provider)
                val output = ByteArrayOutputStream()
                try {
                    PrintStream(output, true, StandardCharsets.UTF_8).use { capture ->
                        System.setOut(capture)
                        BenchmarkCollectionPlan.Companion.main(
                            arrayOf<String?>(
                                "declared-runner-arguments",
                                "runners",
                                runner.benchmark,
                                "/tmp/benchmark jar.jar",
                                "--smoke"
                            )
                        )
                    }
                } finally {
                    System.setOut(original)
                }
                Assertions.assertThat<String?>(
                    output.toString(StandardCharsets.UTF_8).lineSequence().asStream().toList()
                )
                    .containsExactly(
                        "-jar",
                        "\"/tmp/benchmark jar.jar\"",
                        "-p",
                        BenchmarkCollectionPlan.argumentFileToken(
                            runner.parameter + "=" + String.join(",", runner.trialIds)
                        )
                    )
            }
        } finally {
            System.setOut(original)
            if (previous == null) {
                System.clearProperty(property)
            } else {
                System.setProperty(property, previous)
            }
        }
    }

    @Test
    fun allocationCollectionPreservesMemoryScalingWithoutAddingTimingTrials() {
        val plan: BenchmarkCollectionPlan = BenchmarkCollectionPlan.load()

        Assertions.assertThat<BenchmarkCollectionPlan.Runner?>(plan.allocationRunners())
            .flatExtracting<String?, RuntimeException?>(BenchmarkCollectionPlan.Runner::trialIds)
            .contains(
                "MemoryScalingBenchmark.searchEasy.1024@safere-string",
                "MemoryScalingBenchmark.searchEasy.1024@jdk-string",
                "MemoryScalingBenchmark.searchEasy.1024@re2j-string",
                "MemoryScalingBenchmark.searchMedium.1048576@safere-string",
                "MemoryScalingBenchmark.searchMedium.1048576@jdk-string",
                "MemoryScalingBenchmark.searchMedium.1048576@re2j-string"
            )
            .noneMatch(Predicate { trial: String? ->
                trial!!.startsWith("MemoryScalingBenchmark.") && trial.contains(
                    "utf8"
                )
            })
        Assertions.assertThat<BenchmarkCollectionPlan.Runner?>(plan.runners())
            .flatExtracting<String?, RuntimeException?>(BenchmarkCollectionPlan.Runner::trialIds)
            .noneMatch(Predicate { trial: String? -> trial!!.startsWith("MemoryScalingBenchmark.") })
    }

    @Test
    fun collectionPlanQueriesModeTimingPrefixAndExecutionVariant() {
        val plan: BenchmarkCollectionPlan = BenchmarkCollectionPlan.load()
        val query =
            BenchmarkCollectionPlan.Query(
                "averageTime", "nanoseconds", "RegexBenchmark.", "safere-utf8"
            )

        Assertions.assertThat<CollectionTrial?>(plan.trials(query))
            .isNotEmpty()
            .allMatch(
                Predicate { trial: CollectionTrial? ->
                    trial!!.workloadId.startsWith("RegexBenchmark.")
                            && trial.executionVariant == "safere-utf8"
                            && (trial.measurement.timingUnit
                            == TimingUnit.NANOSECONDS)
                })
    }

    @Test
    @Throws(Exception::class)
    fun everyJmhMethodIsGenericOrExplicitMeasurementInfrastructure() {
        val sourceDirectory =
            if (Files.exists(Path.of("src/main/java/org/safere/benchmark")))
                Path.of("src/main/java/org/safere/benchmark")
            else
                Path.of("safere-benchmarks/src/main/java/org/safere/benchmark")

        Files.list(sourceDirectory).use { files ->
            Assertions.assertThat<String?>(
                files
                    .filter { path: Path? -> path.toString().endsWith(".java") }
                    .filter { path: Path? ->
                        try {
                            return@filter Files.readString(path).contains("@Benchmark")
                        } catch (exception: Exception) {
                            throw IllegalStateException(exception)
                        }
                    }
                    .map<String?> { path: Path? -> path!!.fileName.toString() }
                    .sorted())
                .containsExactly(
                    "CrossEngineBenchmark.java",
                    "CrossEngineColdStartBenchmark.java",
                    "CrossEngineNoForkBenchmark.java",
                    "CrossEngineScalingBenchmark.java",
                    "CrosscheckOverheadBenchmark.java",
                    "SpecializedBenchmark.java"
                )
        }
    }

    @Test
    fun everySpecializedOperationPreparesAndRunsThroughOneEntryPoint() {
        val trials = mutableListOf<String?>(
            "PatternSetBenchmark.unanchoredMatch.4@safere-string",
            "Utf8MatchingBenchmark.repeatedFind.ascii@safere-utf8",
            "Utf8MatchingBenchmark.repeatedFind.multibyte@safere-utf8",
            "Utf8MatchingBenchmark.captureBounds.numbered@safere-utf8",
            "Utf8MatchingBenchmark.captureBounds.named@safere-utf8",
            "Utf8MatchingBenchmark.captureBounds.nonparticipating@safere-utf8",
            "Utf8MatchingBenchmark.emptyMatchIteration@safere-utf8",
            "Utf8MatchingBenchmark.captureFreeDecode.asciiEarly@safere-utf8",
            "ByteReplacementBenchmark.numbered@safere-utf8",
            "Utf8MatchingBenchmark.window@safere-utf8",
            "Utf8MatchingBenchmark.construction.validated@safere-utf8",
            "PatternAnalysisBenchmark.cachedAnalysis@safere-string",
            "PatternAnalysisBenchmark.compileOnly@safere-string",
            "PatternAnalysisBenchmark.compileAndAnalyze@safere-string",
            "DiagnosticsDisabledBenchmark.tinyLiteralFindHit@safere-string",
            "DiagnosticsEnabledBenchmark.charClassDenseReplaceAll.longAdder@safere-string"
        )
        val blackhole =
            Blackhole(
                "Today's password is swordfish. I understand instantiating Blackholes directly is"
                        + " dangerous."
            )

        for (trial in trials) {
            SpecializedTrialRunner.prepare(trial).use { runner ->
                runner.run(blackhole)
            }
        }
    }

    companion object {
        // This is a historical floor, not a snapshot. Additions do not raise it.
        private const val MINIMUM_CROSS_ENGINE_WORKLOADS = 606
        private const val MINIMUM_RUNNABLE_CROSS_ENGINE_TRIALS = 2448
        private const val MINIMUM_SPECIALIZED_AVERAGE_TIME_TRIALS = 63
        private const val MINIMUM_SPECIALIZED_RETAINED_MEMORY_TRIALS = 34
        private const val MINIMUM_COLLECTION_RUNNER_TRIALS = 2487

        @TempDir
        var temporaryDirectory: Path? = null

        @BeforeAll
        @Throws(Exception::class)
        fun materializeCorpus() {
            val benchmarkDirectory =
                if (Files.exists(Path.of("benchmark-data.json"))) Path.of(".") else Path.of("safere-benchmarks")
            BenchmarkInputMaterializer.main(
                arrayOf<String>(benchmarkDirectory.toString(), temporaryDirectory.toString())
            )
            System.setProperty("safere.benchmark.corpus", temporaryDirectory.toString())
        }
    }
}
