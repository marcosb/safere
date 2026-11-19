// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.safere.exhaustive.SweepWorkers.LineWorker
import org.safere.exhaustive.SweepWorkers.ProgressReporter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.List
import java.util.regex.Pattern
import kotlin.math.min

/** Offline differential sweep for reverse-DFA sandwich leftmost-start semantics.  */
object DfaSandwichLeftmostStartDivergenceSweep {
    private const val FIND_LIMIT = 8
    private const val FIRST_UNKNOWN_LIMIT = 100
    private const val ACTIONABLE_SAMPLE_LIMIT = 100
    private const val DEFAULT_PROGRESS_INTERVAL: Long = 100000

    private val PREFIXES: MutableList<PrefixCase?> = List.of<PrefixCase?>(
        prefix("empty", ""),
        prefix("emptyNonCapturing", "(?:)"),
        prefix("nonWordBoundary", "\\B"),
        prefix("wordBoundary", "\\b"),
        prefix("emptyThenNonWordBoundary", "(?:)\\B"),
        prefix("emptyThenWordBoundary", "(?:)\\b")
    )

    private val MIDDLES: MutableList<MiddleCase?> = List.of<MiddleCase?>(
        middle("empty", ""),
        middle("negatedAStar", "[^a]*"),
        middle("negatedAStarCaptured", "([^a])*"),
        middle("negatedAStarNonCapturing", "(?:[^a])*"),
        middle("dotStar", ".*"),
        middle("dotStarCaptured", "(.)*"),
        middle("shortFirstAlternativeStar", "(?:b|bb)*"),
        middle("longFirstAlternativeStar", "(?:bb|b)*"),
        middle("shortFirstAlternativeStarCaptured", "(b|bb)*"),
        middle("boundedNegatedA", "[^a]{0,3}"),
        middle("boundedNegatedARepeated", "(?:[^a]{1,2})*"),
        middle("nullableBRepeated", "(?:b?)*")
    )

    private val SUFFIXES: MutableList<SuffixCase?> = List.of<SuffixCase?>(
        suffix("negatedAPair", "[^a][^a]"),
        suffix("negatedATriple", "[^a][^a][^a]"),
        suffix("literalBPair", "bb"),
        suffix("literalBTriple", "bbb"),
        suffix("literalBThenNegatedA", "b[^a]"),
        suffix("anyClassPair", "[\\s\\S][\\s\\S]"),
        suffix("dotPair", "..")
    )

    private val CONTEXTS: MutableList<ContextCase?> = List.of<ContextCase?>(
        context("bare", "%s"),
        context("capturedWhole", "(%s)"),
        context("nonCapturedWhole", "(?:%s)"),
        context("alternativeAfterLiteralA", "a|%s"),
        context("alternativeBeforeLiteralA", "%s|a"),
        context("alternativeAfterLiteralBB", "bb|%s"),
        context("alternativeBeforeLiteralBB", "%s|bb")
    )

    private val FLAG_MODES: MutableList<FlagMode?> = List.of<FlagMode?>(
        flags("none", 0),
        flags("dotall", Pattern.DOTALL),
        flags("multiline", Pattern.MULTILINE),
        flags("unicodeCharacterClass", Pattern.UNICODE_CHARACTER_CLASS)
    )

    private val INPUTS = mutableListOf<String?>(
        "", "a", "b", "bb", "bbb", "bbbb", "bbbbb", "abbbb", "bbbbx", "xbbbb", "baabbbb", "!!!!",
        "!!", "a_bbbb", "bb\nbb", "\nbbbb", "bbbb\n"
    )

    @Throws(IOException::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val options: SweepOptions =
            SweepOptions.Companion.parse(
                args,
                Path.of("target", "exhaustive-reports", "dfa-sandwich-leftmost-start-sweep"),
                "dfa-sandwich-leftmost-start-divergences.jsonl",
                DEFAULT_PROGRESS_INTERVAL
            )
        Files.createDirectories(options.outputDir)
        if (options.replayFile != null) {
            Files.deleteIfExists(options.jsonlPath())
        }
        options.printStartup("dfa-sandwich-leftmost-start")

        if (options.replayFile != null) {
            runReplay(options)
            return
        }

        val summary = newSummary()
        val state = runSweep(options, summary)
        summary.writeReports(options.outputDir)
        println("checked=" + state.checked.sum())
        println("generated=" + state.generated)
        println("totalCases=" + totalCases())
        println("divergences=" + state.divergences.sum())
        println("actionableDivergences=" + summary.actionableCount())
        println("unknownDivergences=" + summary.count(DivergenceClass.UNKNOWN))
        println("threads=" + options.threads)
    }

    fun totalCases(): Long {
        return (PREFIXES.size.toLong() * MIDDLES.size
                * SUFFIXES.size
                * CONTEXTS.size
                * FLAG_MODES.size)
    }

    fun compactReplayJson(caseIndex: Long, classification: String?): String {
        val spec = caseFromIndex(caseIndex)
        return divergenceJson(
            spec,
            spec.regex(),
            RegexSweep.Outcome(false, "", ""),
            RegexSweep.Outcome(false, "", ""),
            DivergenceClass.valueOf(classification!!),
            caseIndex
        )
    }

    @Throws(IOException::class)
    private fun runSweep(
        options: SweepOptions, summary: ClassifiedDivergenceSummary<DivergenceClass?>
    ): SweepRunState {
        SweepRunState(options, options.totalChecks(totalCases())).use { runState ->
            runState.enableCompactLogs(
                "dfa-sandwich-leftmost-start",
                totalCases(),
                divergenceClassificationNames(),
                divergenceClassificationStatuses()
            )
            SweepWorkers.run(
                options.threads,
                "dfa-sandwich-leftmost-start-sweep-",
                SweepWorkers.Worker { workerIndex: Int ->
                    val progressReporter =
                        ProgressReporter(runState, workerIndex)
                    var generated: Long = 0
                    val end = min(options.rangeEndExclusive, totalCases())
                    for (caseIndex in options.rangeStartInclusive..<end) {
                        generated = caseIndex + 1
                        if (caseIndex % options.threads != workerIndex.toLong()) {
                            continue
                        }
                        progressReporter.checked()
                        evaluateCase(runState, summary, caseFromIndex(caseIndex), workerIndex, caseIndex)
                        progressReporter.reportIfNeeded(generated)
                    }
                    runState.recordGenerated(generated)
                    runState.updateWorkerNextCaseIndex(workerIndex, generated)
                })
            return runState
        }
    }

    @Throws(IOException::class)
    private fun runReplay(options: SweepOptions) {
        val summary = newSummary()
        Files.newBufferedReader(options.replayFile, StandardCharsets.UTF_8).use { reader ->
            SweepRunState(options, 0).use { runState ->
                val generated =
                    SweepWorkers.runStreamingLines(
                        options.threads,
                        "dfa-sandwich-leftmost-start-replay-",
                        reader,
                        LineWorker { line: String? ->
                            val spec = replayCaseOrNull(line)
                            if (spec == null) {
                                return@runStreamingLines
                            }
                            runState.checked.increment()
                            evaluateCase(runState, summary, spec, -1, -1)
                        })
                runState.recordGenerated(generated)
                summary.writeReports(options.outputDir)
                println("checked=" + runState.checked.sum())
                println("generated=" + runState.generated)
                println("divergences=" + runState.divergences.sum())
                println("actionableDivergences=" + summary.actionableCount())
                println("unknownDivergences=" + summary.count(DivergenceClass.UNKNOWN))
                println("threads=" + options.threads)
                println("jsonl=" + options.jsonlPath())
                check(!(summary.actionableCount() > 0 || summary.count(DivergenceClass.UNKNOWN) > 0)) {
                    ("replay found "
                            + summary.actionableCount()
                            + " actionable and "
                            + summary.count(DivergenceClass.UNKNOWN)
                            + " unknown behavioral divergences")
                }
            }
        }
    }

    private fun replayCaseOrNull(line: String?): CaseSpec? {
        val `object` = SweepJson.parseObjectOrNull(line)
        if (`object` == null) {
            return null
        }
        val caseObject = SweepJson.`object`(`object`, "case")
        return CaseSpec(
            PrefixCase(
                SweepJson.string(caseObject, "prefixLabel"),
                SweepJson.string(caseObject, "prefixRegex")
            ),
            MiddleCase(
                SweepJson.string(caseObject, "middleLabel"),
                SweepJson.string(caseObject, "middleRegex")
            ),
            SuffixCase(
                SweepJson.string(caseObject, "suffixLabel"),
                SweepJson.string(caseObject, "suffixRegex")
            ),
            ContextCase(
                SweepJson.string(caseObject, "contextLabel"),
                SweepJson.string(caseObject, "contextTemplate")
            ),
            FlagMode(
                SweepJson.string(caseObject, "flagLabel"), SweepJson.integer(caseObject, "flags")
            )
        )
    }

    private fun evaluateCase(
        runState: SweepRunState,
        summary: ClassifiedDivergenceSummary<DivergenceClass?>,
        spec: CaseSpec,
        workerIndex: Int,
        caseIndex: Long
    ) {
        val regex = spec.regex()
        val jdk =
            RegexSweep.jdkTraceOutcome(regex, spec.flagMode!!.flags, INPUTS, FIND_LIMIT)
        val safere =
            RegexSweep.safeReTraceOutcome(regex, spec.flagMode.flags, INPUTS, FIND_LIMIT)
        if (RegexSweep.semanticallyEqual(jdk, safere)) {
            return
        }

        val classification = classify(jdk, safere)
        val json = divergenceJson(spec, regex, jdk, safere, classification, caseIndex)
        if (workerIndex >= 0 && caseIndex >= 0) {
            runState.recordCompactDivergence(workerIndex, caseIndex, classificationId(classification))
        } else {
            runState.recordDivergence()
        }
        runState.appendJsonl(json)
        summary.record(classification, caseIndex, json)
    }

    private fun classify(jdk: RegexSweep.Outcome, safere: RegexSweep.Outcome): DivergenceClass {
        if (jdk.accepted != safere.accepted) {
            return DivergenceClass.ACCEPTANCE_MISMATCH
        }
        if (jdk.accepted) {
            return DivergenceClass.TRACE_MISMATCH
        }
        return DivergenceClass.UNKNOWN
    }

    private fun divergenceJson(
        spec: CaseSpec,
        regex: String?,
        jdk: RegexSweep.Outcome,
        safere: RegexSweep.Outcome,
        classification: DivergenceClass,
        caseIndex: Long
    ): String {
        val root = SweepJson.`object`()
        root.addProperty("classification", classification.name)
        root.addProperty("caseIndex", caseIndex)
        root.add("case", caseJson(spec, regex))
        root.add("jdk", outcomeJson(jdk))
        root.add("safere", outcomeJson(safere))
        return SweepJson.toJson(root)
    }

    private fun caseJson(spec: CaseSpec, regex: String?): JsonObject {
        val `object` = SweepJson.`object`()
        `object`.addProperty("prefixLabel", spec.prefix!!.label)
        `object`.addProperty("prefixRegex", spec.prefix.regex)
        `object`.addProperty("middleLabel", spec.middle!!.label)
        `object`.addProperty("middleRegex", spec.middle.regex)
        `object`.addProperty("suffixLabel", spec.suffix!!.label)
        `object`.addProperty("suffixRegex", spec.suffix.regex)
        `object`.addProperty("contextLabel", spec.context!!.label)
        `object`.addProperty("contextTemplate", spec.context.template)
        `object`.addProperty("flagLabel", spec.flagMode!!.label)
        `object`.addProperty("flags", spec.flagMode.flags)
        `object`.addProperty("regex", regex)
        val inputs = JsonArray()
        for (input in INPUTS) {
            inputs.add(input)
        }
        `object`.add("inputs", inputs)
        return `object`
    }

    private fun outcomeJson(outcome: RegexSweep.Outcome): JsonObject {
        val `object` = SweepJson.`object`()
        `object`.addProperty("accepted", outcome.accepted)
        `object`.addProperty("trace", outcome.trace)
        `object`.addProperty("error", outcome.error)
        return `object`
    }

    private fun newSummary(): ClassifiedDivergenceSummary<DivergenceClass?> {
        return ClassifiedDivergenceSummary<DivergenceClass?>(
            DivergenceClass::class.java,
            DivergenceClass.UNKNOWN,
            "dfa-sandwich-leftmost-start",
            FIRST_UNKNOWN_LIMIT,
            ACTIONABLE_SAMPLE_LIMIT
        )
    }

    private fun divergenceClassificationNames(): MutableList<String?> {
        return Arrays.stream<DivergenceClass?>(DivergenceClass.entries.toTypedArray())
            .map<String?> { obj: DivergenceClass? -> obj!!.name }.toList()
    }

    private fun divergenceClassificationStatuses(): MutableList<DivergenceStatus?> {
        return Arrays.stream<DivergenceClass?>(DivergenceClass.entries.toTypedArray())
            .map<DivergenceStatus?> { obj: DivergenceClass? -> obj!!.status() }.toList()
    }

    private fun caseFromIndex(index: Long): CaseSpec {
        var index = index
        val flagIndex = (index % FLAG_MODES.size).toInt()
        index /= FLAG_MODES.size.toLong()
        val contextIndex = (index % CONTEXTS.size).toInt()
        index /= CONTEXTS.size.toLong()
        val suffixIndex = (index % SUFFIXES.size).toInt()
        index /= SUFFIXES.size.toLong()
        val middleIndex = (index % MIDDLES.size).toInt()
        index /= MIDDLES.size.toLong()
        val prefixIndex = (index % PREFIXES.size).toInt()
        return CaseSpec(
            PREFIXES.get(prefixIndex),
            MIDDLES.get(middleIndex),
            SUFFIXES.get(suffixIndex),
            CONTEXTS.get(contextIndex),
            FLAG_MODES.get(flagIndex)
        )
    }

    private fun prefix(label: String?, regex: String?): PrefixCase {
        return PrefixCase(label, regex)
    }

    private fun middle(label: String?, regex: String?): MiddleCase {
        return MiddleCase(label, regex)
    }

    private fun suffix(label: String?, regex: String?): SuffixCase {
        return SuffixCase(label, regex)
    }

    private fun context(label: String?, template: String?): ContextCase {
        return ContextCase(label, template)
    }

    private fun flags(label: String?, flags: Int): FlagMode {
        return FlagMode(label, flags)
    }

    private fun classificationId(classification: DivergenceClass): Int {
        return when (classification) {
            DivergenceClass.ACCEPTANCE_MISMATCH -> 0
            DivergenceClass.TRACE_MISMATCH -> 1
            DivergenceClass.UNKNOWN -> 2
        }
    }

    private enum class DivergenceClass(status: DivergenceStatus, rationale: String) : DivergenceClassification {
        ACCEPTANCE_MISMATCH(
            DivergenceStatus.EXPECTED_ZERO,
            "DFA sandwich leftmost-start cases use supported regex syntax and must compile the same as"
                    + " java.util.regex."
        ),
        TRACE_MISMATCH(
            DivergenceStatus.EXPECTED_ZERO,
            "DFA sandwich optimizations must preserve java.util.regex traces for matches(),"
                    + " lookingAt(), find(), captures, and replacements."
        ),
        UNKNOWN(DivergenceStatus.UNKNOWN, "Unclassified DFA sandwich leftmost-start sweep divergence.");

        private val status: DivergenceStatus?
        private val rationale: String?

        init {
            this.status = status
            this.rationale = rationale
        }

        override fun status(): DivergenceStatus? {
            return status
        }

        override fun rationale(): String? {
            return rationale
        }
    }

    @JvmRecord
    private data class PrefixCase(val label: String?, val regex: String?)

    @JvmRecord
    private data class MiddleCase(val label: String?, val regex: String?)

    @JvmRecord
    private data class SuffixCase(val label: String?, val regex: String?)

    @JvmRecord
    private data class ContextCase(val label: String?, val template: String?)

    @JvmRecord
    private data class FlagMode(val label: String?, val flags: Int)

    @JvmRecord
    private data class CaseSpec(
        val prefix: PrefixCase?,
        val middle: MiddleCase?,
        val suffix: SuffixCase?,
        val context: ContextCase?,
        val flagMode: FlagMode?
    ) {
        fun regex(): String {
            return context!!.template.formatted(prefix!!.regex + middle!!.regex + suffix!!.regex)
        }
    }
}
