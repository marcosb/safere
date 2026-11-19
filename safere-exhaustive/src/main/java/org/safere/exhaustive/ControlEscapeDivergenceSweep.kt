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
import kotlin.plus
import kotlin.text.StringBuilder
import kotlin.text.format
import kotlin.use

/** Offline differential sweep for `\cX` control-escape target bugs.  */
object ControlEscapeDivergenceSweep {
    private val TARGET_COUNT = Character.MAX_CODE_POINT + 1

    private val CONTEXTS: MutableList<Context?> = List.of<Context?>(
        context("bare", "\\c%s"),
        context("anchored", "^\\c%s$"),
        context("class", "[\\c%s]"),
        context("negatedClass", "[^\\c%s]"),
        context("prefixLiteral", "a\\c%s"),
        context("suffixLiteral", "\\c%sa"),
        context("captured", "(\\c%s)"),
        context("optional", "\\c%s?")
    )

    private val FLAG_MODES: MutableList<FlagMode?> = List.of<FlagMode?>(
        FlagMode("none", "", 0),
        FlagMode("comments", "(?x)", Pattern.COMMENTS),
        FlagMode("caseInsensitive", "(?i)", Pattern.CASE_INSENSITIVE),
        FlagMode(
            "commentsCaseInsensitive",
            "(?xi)",
            Pattern.COMMENTS or Pattern.CASE_INSENSITIVE
        )
    )

    @Throws(IOException::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val options: SweepOptions =
            SweepOptions.Companion.parse(
                args,
                Path.of("target", "exhaustive-reports", "control-escape-sweep"),
                "control-escape-divergences.jsonl",
                1000000
            )
        Files.createDirectories(options.outputDir)
        if (options.replayFile != null) {
            Files.deleteIfExists(options.jsonlPath())
        }
        options.printStartup("control-escape")

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
                "control-escape",
                totalCases(),
                mutableListOf<String?>("UNKNOWN"),
                List.of<DivergenceStatus?>(DivergenceStatus.UNKNOWN)
            )
            SweepWorkers.run(
                options.threads,
                "control-escape-sweep-",
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
                        "control-escape-replay-",
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
            SweepJson.integer(caseObject, "target"),
            Context(
                SweepJson.string(caseObject, "contextLabel"),
                SweepJson.string(caseObject, "contextTemplate")
            ),
            FlagMode(
                SweepJson.string(caseObject, "flagLabel"),
                SweepJson.string(caseObject, "flagPrefix"),
                SweepJson.integer(caseObject, "flags")
            )
        )
    }

    @JvmStatic
    fun totalCases(): Long {
        return TARGET_COUNT.toLong() * CONTEXTS.size * FLAG_MODES.size
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
        val contextIndex = (index % CONTEXTS.size).toInt()
        index /= CONTEXTS.size.toLong()
        val target = index.toInt()
        return CaseSpec(target, CONTEXTS.get(contextIndex), FLAG_MODES.get(flagIndex))
    }

    private fun jdkOutcome(regex: String, flags: Int, inputs: MutableList<String>): Outcome {
        try {
            return Outcome(true, matches(Pattern.compile(regex, flags), inputs), "")
        } catch (e: PatternSyntaxException) {
            return Outcome(false, "", e.getDescription())
        } catch (e: RuntimeException) {
            return Outcome(false, "", e.javaClass.getSimpleName() + ": " + e.message)
        }
    }

    private fun safeReOutcome(regex: String?, flags: Int, inputs: MutableList<String>): Outcome {
        try {
            return Outcome(true, matches(org.safere.Pattern.compile(regex, flags), inputs), "")
        } catch (e: PatternSyntaxException) {
            return Outcome(false, "", e.getDescription())
        } catch (e: RuntimeException) {
            return Outcome(false, "", e.javaClass.getSimpleName() + ": " + e.message)
        }
    }

    private fun matches(pattern: Pattern, inputs: MutableList<String>): String {
        val result = StringBuilder()
        for (input in inputs) {
            if (pattern.matcher(input).matches()) {
                appendInput(result, input)
            }
        }
        return result.toString()
    }

    private fun matches(pattern: org.safere.Pattern, inputs: MutableList<String>): String {
        val result = StringBuilder()
        for (input in inputs) {
            if (pattern.matcher(input).matches()) {
                appendInput(result, input)
            }
        }
        return result.toString()
    }

    private fun inputsFor(spec: CaseSpec): MutableList<String> {
        val target = stringForCodePoint(spec.target)
        val transformed = stringForCodePoint(spec.target xor 0x40)
        val inputs: MutableSet<String?> = LinkedHashSet<String?>()
        inputs.add("")
        inputs.add(transformed)
        inputs.add(target)
        addIfValid(inputs, (spec.target xor 0x40) - 1)
        addIfValid(inputs, (spec.target xor 0x40) + 1)
        inputs.add("a")
        inputs.add("A")
        inputs.add("!")
        inputs.add("\u0001")
        inputs.add("\u001b")
        inputs.add("\u0140")
        inputs.add("\uf57f")
        inputs.add("\uD83D\uDE40")
        if (spec.context!!.needsPrefixA()) {
            inputs.add("a" + transformed)
            inputs.add("aa")
            inputs.add("a" + target)
        }
        if (spec.context.needsSuffixA()) {
            inputs.add(transformed + "a")
            inputs.add(target + "a")
        }
        return List.copyOf<String?>(inputs)
    }

    private fun addIfValid(inputs: MutableSet<String?>, codePoint: Int) {
        if (Character.isValidCodePoint(codePoint)) {
            inputs.add(stringForCodePoint(codePoint))
        }
    }

    private fun stringForCodePoint(codePoint: Int): String {
        if (codePoint <= Character.MAX_VALUE.code) {
            return Character.toString(codePoint.toChar())
        }
        return String(Character.toChars(codePoint))
    }

    private fun appendInput(result: StringBuilder, input: String) {
        if (result.length > 0) {
            result.append(',')
        }
        result.append(escape(input))
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
            "context=" + spec.context!!.label,
            "flags=" + spec.flagMode!!.label,
            "targetClass=" + targetClass(spec.target)
        )
    }

    private fun direction(jdk: Outcome, safere: Outcome): kotlin.String {
        if (jdk.accepted && !safere.accepted) {
            return "jdk-accepts"
        }
        if (!jdk.accepted && safere.accepted) {
            return "safere-accepts"
        }
        return "matches"
    }

    private fun targetClass(target: Int): kotlin.String {
        if (Character.isHighSurrogate(target.toChar())) {
            return "high-surrogate"
        }
        if (Character.isLowSurrogate(target.toChar())) {
            return "low-surrogate"
        }
        if (target < 0x20 || target == 0x7F) {
            return "ascii-control"
        }
        if (target < 0x80) {
            return "ascii-printable"
        }
        if (target <= Character.MAX_VALUE.code) {
            return "bmp"
        }
        return "supplementary"
    }

    private fun semanticallyEqual(left: Outcome, right: Outcome): Boolean {
        if (left.accepted != right.accepted) {
            return false
        }
        return !left.accepted || left.matches == right.matches
    }

    private fun context(label: kotlin.String?, template: kotlin.String?): Context {
        return Context(label, template)
    }

    private fun evaluateCase(
        runState: SweepRunState, spec: CaseSpec, workerIndex: Int, caseIndex: Long
    ) {
        val regex = spec.regex()
        val inputs = inputsFor(spec)
        val jdk = jdkOutcome(regex, spec.flagMode!!.flags, inputs)
        val safere = safeReOutcome(regex, spec.flagMode.flags, inputs)
        if (semanticallyEqual(jdk, safere)) {
            return
        }
        val bucketName = bucketFor(spec, jdk, safere)
        if (workerIndex >= 0) {
            runState.recordCompactDivergence(workerIndex, caseIndex, 0)
        } else {
            runState.recordDivergence()
            runState.appendJsonl(Divergence(spec, regex, jdk, safere, bucketName).toJson())
        }
    }

    @JvmRecord
    private data class Context(val label: kotlin.String?, val template: kotlin.String?) {
        fun regex(target: kotlin.String?): kotlin.String {
            return template.formatted(target)
        }

        fun needsPrefixA(): Boolean {
            return label == "prefixLiteral"
        }

        fun needsSuffixA(): Boolean {
            return label == "suffixLiteral"
        }
    }

    @JvmRecord
    private data class FlagMode(val label: kotlin.String?, val prefix: kotlin.String?, val flags: Int)

    @JvmRecord
    private data class CaseSpec(val target: Int, val context: Context?, val flagMode: FlagMode?) {
        fun regex(): kotlin.String {
            return flagMode!!.prefix + context!!.regex(stringForCodePoint(target))
        }

        fun labels(): kotlin.String {
            return ("target="
                    + kotlin.String.format(Locale.ROOT, "U+%04X", target) + ",context="
                    + context!!.label
                    + ",flags="
                    + flagMode!!.label
                    + ",targetClass="
                    + targetClass(target))
        }
    }

    @JvmRecord
    private data class Outcome(val accepted: Boolean, val matches: kotlin.String?, val error: kotlin.String?)

    @JvmRecord
    private data class Divergence(
        val spec: CaseSpec?,
        val regex: kotlin.String?,
        val jdk: Outcome?,
        val safere: Outcome?,
        val bucket: kotlin.String?
    ) {
        fun toJson(): kotlin.String {
            val `object` = SweepJson.`object`()
            `object`.add("case", Companion.caseJson(spec!!))
            `object`.addProperty("bucket", bucket)
            `object`.addProperty("labels", spec.labels())
            `object`.addProperty("regex", regex)
            `object`.addProperty("target", spec.target)
            `object`.addProperty("context", spec.context!!.label)
            `object`.addProperty("flags", spec.flagMode!!.label)
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
                `object`.addProperty("target", spec.target)
                `object`.addProperty("contextLabel", spec.context!!.label)
                `object`.addProperty("contextTemplate", spec.context.template)
                `object`.addProperty("flagLabel", spec.flagMode!!.label)
                `object`.addProperty("flagPrefix", spec.flagMode.prefix)
                `object`.addProperty("flags", spec.flagMode.flags)
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
            ControlEscapeDivergenceSweep.evaluateCase(runState!!, spec, workerIndex, caseIndex)
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
