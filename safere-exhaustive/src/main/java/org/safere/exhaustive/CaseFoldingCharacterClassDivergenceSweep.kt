// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

import com.google.gson.JsonObject
import org.safere.exhaustive.SweepWorkers.LineWorker
import org.safere.exhaustive.SweepWorkers.ProgressReporter
import java.io.IOException
import java.lang.String
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.List
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.Array
import kotlin.Boolean
import kotlin.Int
import kotlin.Long
import kotlin.RuntimeException
import kotlin.Throws
import kotlin.check
import kotlin.code
import kotlin.io.println
import kotlin.io.use
import kotlin.math.min
import kotlin.text.StringBuilder
import kotlin.text.format
import kotlin.use

/** Offline differential sweep for case-insensitive character-class closure bugs.  */
object CaseFoldingCharacterClassDivergenceSweep {
    private val INPUT_COUNT = Character.MAX_CODE_POINT + 1

    private val FLAG_MODES: MutableList<FlagMode?> = List.of<FlagMode?>(
        FlagMode("caseInsensitive", Pattern.CASE_INSENSITIVE),
        FlagMode("unicodeCase", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        FlagMode(
            "unicodeCharacterClass", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CHARACTER_CLASS
        )
    )

    private val PATTERNS: MutableList<PatternSpec?> = List.of<PatternSpec?>(
        pattern("literalLowerI", "\\x{69}"),
        pattern("literalUpperI", "\\x{49}"),
        pattern("literalLowerK", "\\x{6B}"),
        pattern("literalUpperK", "\\x{4B}"),
        pattern("literalLongS", "\\x{17F}"),
        pattern("literalGreekSigma", "\\x{3A3}"),
        pattern("literalGreekFinalSigma", "\\x{3C2}"),
        pattern("classLowerI", "[\\x{69}]"),
        pattern("classUpperI", "[\\x{49}]"),
        pattern("classLowerK", "[\\x{6B}]"),
        pattern("classUpperK", "[\\x{4B}]"),
        pattern("classLongS", "[\\x{17F}]"),
        pattern("rangeLowerAZ", "[a-z]"),
        pattern("rangeUpperAZ", "[A-Z]"),
        pattern("rangeLowerHJ", "[h-j]"),
        pattern("rangeUpperHJ", "[H-J]"),
        pattern("negatedRangeLowerHJ", "[^h-j]"),
        pattern("negatedRangeUpperHJ", "[^H-J]"),
        pattern("categoryUpper", "\\p{Lu}"),
        pattern("categoryLower", "\\p{Ll}"),
        pattern("categoryTitle", "\\p{Lt}"),
        pattern("categoryLetter", "\\p{L}"),
        pattern("categoryIsUpper", "\\p{IsLu}"),
        pattern("categoryIsLower", "\\p{IsLl}"),
        pattern("categoryIsTitle", "\\p{IsLt}"),
        pattern("categoryGeneralUpper", "\\p{gc=Lu}"),
        pattern("categoryGeneralLower", "\\p{gc=Ll}"),
        pattern("categoryGeneralTitle", "\\p{gc=Lt}"),
        pattern("classCategoryUpper", "[\\p{Lu}]"),
        pattern("classCategoryLower", "[\\p{Ll}]"),
        pattern("classCategoryTitle", "[\\p{Lt}]"),
        pattern("negatedCategoryUpper", "\\P{Lu}"),
        pattern("negatedCategoryLower", "\\P{Ll}"),
        pattern("negatedCategoryTitle", "\\P{Lt}"),
        pattern("classNegatedCategoryUpper", "[^\\p{Lu}]"),
        pattern("classNegatedCategoryLower", "[^\\p{Ll}]"),
        pattern("classNegatedCategoryTitle", "[^\\p{Lt}]"),
        pattern("javaLower", "\\p{javaLowerCase}"),
        pattern("javaUpper", "\\p{javaUpperCase}"),
        pattern("titleRepeat4", "\\p{Lt}{4}", 4)
    )

    @Throws(IOException::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val options: SweepOptions =
            SweepOptions.Companion.parse(
                args,
                Path.of("target", "exhaustive-reports", "case-folding-character-class-sweep"),
                "case-folding-character-class-divergences.jsonl",
                1000000
            )
        Files.createDirectories(options.outputDir)
        if (options.replayFile != null) {
            Files.deleteIfExists(options.jsonlPath())
        }
        options.printStartup("case-folding-character-class")

        if (options.replayFile != null) {
            runReplay(options)
            return
        }

        val state = runSweep(options)

        println("checked=" + state.checked.sum())
        println("generated=" + state.generated)
        println("totalCases=" + totalCases())
        println("divergences=" + state.divergences.sum())
        println("threads=" + options.threads)
    }

    @Throws(IOException::class)
    private fun runSweep(options: SweepOptions): SweepRunState {
        SweepRunState(options, options.totalChecks(totalCases())).use { runState ->
            runState.enableCompactLogs(
                "case-folding-character-class",
                totalCases(),
                mutableListOf<String?>("UNKNOWN"),
                List.of<DivergenceStatus?>(DivergenceStatus.UNKNOWN)
            )
            SweepWorkers.run(
                options.threads,
                "case-folding-character-class-sweep-",
                SweepWorkers.Worker { workerIndex: Int ->
                    val worker = SweepState(runState, workerIndex)
                    worker.run()
                    worker.finish()
                })
            return runState
        }
    }

    @Throws(IOException::class)
    private fun runReplay(options: SweepOptions) {
        Files.newBufferedReader(options.replayFile, StandardCharsets.UTF_8).use { reader ->
            SweepRunState(options, 0).use { runState ->
                val generated =
                    SweepWorkers.runStreamingLines(
                        options.threads,
                        "case-folding-character-class-replay-",
                        reader,
                        LineWorker { line: String? ->
                            runState.checked.increment()
                            evaluateCase(runState, replayCase(line), -1, -1)
                        })
                runState.recordGenerated(generated)
                println("checked=" + runState.checked.sum())
                println("generated=" + runState.generated)
                println("divergences=" + runState.divergences.sum())
                println("threads=" + options.threads)
                println("jsonl=" + options.jsonlPath())
                check(runState.divergences.sum() <= 0) { "replay found " + runState.divergences.sum() + " behavioral divergences" }
            }
        }
    }

    private fun replayCase(line: String?): CaseSpec {
        val `object` = SweepJson.parseObject(line)
        val caseObject = SweepJson.`object`(`object`, "case")
        return CaseSpec(
            PatternSpec(
                SweepJson.string(caseObject, "patternLabel"),
                SweepJson.string(caseObject, "regex"),
                SweepJson.integer(caseObject, "inputRepeat")
            ),
            FlagMode(
                SweepJson.string(caseObject, "flagLabel"), SweepJson.integer(caseObject, "flags")
            ),
            SweepJson.integer(caseObject, "inputCodePoint")
        )
    }

    fun totalCases(): Long {
        return INPUT_COUNT.toLong() * PATTERNS.size * FLAG_MODES.size
    }

    fun compactReplayJson(caseIndex: Long, classification: String?): String {
        val spec = caseAt(caseIndex)
        val `object` = SweepJson.`object`()
        `object`.addProperty("caseIndex", caseIndex)
        `object`.addProperty("classification", classification)
        `object`.add("case", Divergence.Companion.caseJson(spec))
        return SweepJson.toJson(`object`)
    }

    private fun caseAt(index: Long): CaseSpec {
        var index = index
        val flagIndex = (index % FLAG_MODES.size).toInt()
        index /= FLAG_MODES.size.toLong()
        val patternIndex = (index % PATTERNS.size).toInt()
        index /= PATTERNS.size.toLong()
        val inputCodePoint = index.toInt()
        return CaseSpec(PATTERNS.get(patternIndex), FLAG_MODES.get(flagIndex), inputCodePoint)
    }

    private fun jdkOutcome(regex: String, flags: Int, input: String): Outcome {
        try {
            return Outcome(
                true, Pattern.compile(regex, flags).matcher(input).matches(), ""
            )
        } catch (e: PatternSyntaxException) {
            return Outcome(false, false, e.getDescription())
        } catch (e: RuntimeException) {
            return Outcome(false, false, e.javaClass.getSimpleName() + ": " + e.message)
        }
    }

    private fun safeReOutcome(regex: String?, flags: Int, input: String?): Outcome {
        try {
            return Outcome(
                true, org.safere.Pattern.compile(regex, flags).matcher(input).matches(), ""
            )
        } catch (e: PatternSyntaxException) {
            return Outcome(false, false, e.getDescription())
        } catch (e: RuntimeException) {
            return Outcome(false, false, e.javaClass.getSimpleName() + ": " + e.message)
        }
    }

    private fun stringForCodePoint(codePoint: Int): String {
        if (codePoint <= Character.MAX_VALUE.code) {
            return Character.toString(codePoint.toChar())
        }
        return String(Character.toChars(codePoint))
    }

    private fun inputFor(spec: CaseSpec): String {
        return stringForCodePoint(spec.inputCodePoint).repeat(spec.pattern!!.inputRepeat)
    }

    private fun escape(value: String): String {
        val result = StringBuilder()
        for (i in 0..<value.length) {
            val c = value.get(i)
            when (c) {
                '\\' -> result.append("\\\\")
                '\n' -> result.append("\\n")
                '\t' -> result.append("\\t")
                '\r' -> result.append("\\r")
                '"' -> result.append("\\\"")
                else -> {
                    if (Character.isISOControl(c)
                        || Character.isSurrogate(c)
                        || Character.getType(c) == Character.PRIVATE_USE.toInt()
                    ) {
                        result.append(String.format(Locale.ROOT, "\\u%04X", c.code))
                    } else {
                        result.append(c)
                    }
                }
            }
        }
        return result.toString()
    }

    private fun bucketFor(spec: CaseSpec, jdk: Outcome, safere: Outcome): String {
        val kind = if (jdk.accepted != safere.accepted) "compile" else "membership"
        return String.join(
            "|",
            "kind=" + kind,
            "direction=" + direction(jdk, safere),
            "pattern=" + spec.pattern!!.label,
            "flags=" + spec.flagMode!!.label,
            "inputClass=" + inputClass(spec.inputCodePoint)
        )
    }

    private fun direction(jdk: Outcome, safere: Outcome): kotlin.String {
        if (jdk.accepted && !safere.accepted) {
            return "jdk-accepts"
        }
        if (!jdk.accepted && safere.accepted) {
            return "safere-accepts"
        }
        if (jdk.matches) {
            return "jdk-matches"
        }
        return "safere-matches"
    }

    private fun inputClass(codePoint: Int): kotlin.String {
        if (codePoint <= Character.MAX_VALUE.code && Character.isHighSurrogate(codePoint.toChar())) {
            return "high-surrogate"
        }
        if (codePoint <= Character.MAX_VALUE.code && Character.isLowSurrogate(codePoint.toChar())) {
            return "low-surrogate"
        }
        val type = Character.getType(codePoint)
        return when (type) {
            Character.UPPERCASE_LETTER -> "uppercase-letter"
            Character.LOWERCASE_LETTER -> "lowercase-letter"
            Character.TITLECASE_LETTER -> "titlecase-letter"
            Character.MODIFIER_LETTER -> "modifier-letter"
            Character.OTHER_LETTER -> "other-letter"
            else -> "type-" + type
        }
    }

    private fun semanticallyEqual(left: Outcome, right: Outcome): Boolean {
        if (left.accepted != right.accepted) {
            return false
        }
        return !left.accepted || left.matches == right.matches
    }

    private fun pattern(label: kotlin.String, regex: kotlin.String?): PatternSpec {
        return PatternSpec(label, regex, 1)
    }

    private fun pattern(label: kotlin.String, regex: kotlin.String?, inputRepeat: Int): PatternSpec {
        return PatternSpec(label, regex, inputRepeat)
    }

    private fun evaluateCase(
        runState: SweepRunState, spec: CaseSpec, workerIndex: Int, caseIndex: Long
    ) {
        val input = inputFor(spec)
        val jdk =
            CaseFoldingCharacterClassDivergenceSweep.jdkOutcome(spec.pattern!!.regex!!, spec.flagMode!!.flags, input)
        val safere = safeReOutcome(spec.pattern.regex, spec.flagMode.flags, input)
        if (semanticallyEqual(jdk, safere)) {
            return
        }
        val bucketName = bucketFor(spec, jdk, safere)
        if (workerIndex >= 0) {
            runState.recordCompactDivergence(workerIndex, caseIndex, 0)
        } else {
            runState.recordDivergence()
            runState.appendJsonl(Divergence(spec, input, jdk, safere, bucketName).toJson())
        }
    }

    @JvmRecord
    private data class PatternSpec(val label: kotlin.String, val regex: kotlin.String?, val inputRepeat: Int)

    @JvmRecord
    private data class FlagMode(val label: kotlin.String?, val flags: Int)

    @JvmRecord
    private data class CaseSpec(val pattern: PatternSpec?, val flagMode: FlagMode?, val inputCodePoint: Int) {
        fun labels(): kotlin.String {
            return ("pattern="
                    + pattern!!.label
                    + ",flags="
                    + flagMode!!.label
                    + ",input="
                    + kotlin.String.format(Locale.ROOT, "U+%04X", inputCodePoint) + ",inputClass="
                    + inputClass(inputCodePoint))
        }
    }

    @JvmRecord
    private data class Outcome(val accepted: Boolean, val matches: Boolean, val error: kotlin.String?)

    @JvmRecord
    private data class Divergence(
        val spec: CaseSpec?,
        val input: kotlin.String?,
        val jdk: Outcome?,
        val safere: Outcome?,
        val bucket: kotlin.String?
    ) {
        fun toJson(): kotlin.String {
            val `object` = SweepJson.`object`()
            `object`.add("case", Companion.caseJson(spec!!))
            `object`.addProperty("bucket", bucket)
            `object`.addProperty("labels", spec.labels())
            `object`.addProperty("regex", spec.pattern!!.regex)
            `object`.addProperty("flags", spec.flagMode!!.label)
            `object`.addProperty("inputCodePoint", spec.inputCodePoint)
            `object`.addProperty("input", CaseFoldingCharacterClassDivergenceSweep.escape(input!!))
            `object`.addProperty("jdkAccepted", jdk!!.accepted)
            `object`.addProperty("safeReAccepted", safere!!.accepted)
            `object`.addProperty("jdkMatches", jdk.matches)
            `object`.addProperty("safeReMatches", safere.matches)
            `object`.addProperty("jdkError", jdk.error)
            `object`.addProperty("safeReError", safere.error)
            return SweepJson.toJson(`object`)
        }

        companion object {
            private fun caseJson(spec: CaseSpec): JsonObject {
                val `object` = SweepJson.`object`()
                `object`.addProperty("patternLabel", spec.pattern!!.label)
                `object`.addProperty("regex", spec.pattern.regex)
                `object`.addProperty("inputRepeat", spec.pattern.inputRepeat)
                `object`.addProperty("flagLabel", spec.flagMode!!.label)
                `object`.addProperty("flags", spec.flagMode.flags)
                `object`.addProperty("inputCodePoint", spec.inputCodePoint)
                return `object`
            }
        }
    }

    private class SweepState(runState: SweepRunState, workerIndex: Int) {
        val runState: SweepRunState?
        val options: SweepOptions
        val progressReporter: ProgressReporter
        val workerIndex: Int
        var generated: Long = 0

        init {
            this.runState = runState
            this.options = runState.options
            this.progressReporter = ProgressReporter(runState, workerIndex)
            this.workerIndex = workerIndex
        }

        fun run() {
            val end = min(options.rangeEndExclusive, totalCases())
            while (generated < end) {
                val caseIndex = generated++
                if (caseIndex < options.rangeStartInclusive) {
                    reportProgressIfNeeded()
                    continue
                }
                if (caseIndex % options.threads != workerIndex.toLong()) {
                    continue
                }
                progressReporter.checked()
                evaluateCase(caseIndex, caseAt(caseIndex))
            }
        }

        fun evaluateCase(caseIndex: Long, spec: CaseSpec) {
            CaseFoldingCharacterClassDivergenceSweep.evaluateCase(runState!!, spec, workerIndex, caseIndex)
            reportProgressIfNeeded()
        }

        fun finish() {
            runState!!.recordGenerated(generated)
            runState.updateWorkerNextCaseIndex(workerIndex, generated)
        }

        fun reportProgressIfNeeded() {
            progressReporter.reportIfNeeded(generated)
        }
    }
}
