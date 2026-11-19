// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

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

/** Offline differential sweep for Unicode predefined and POSIX character classes.  */
object UnicodeCharacterClassDivergenceSweep {
    private val FLAGS = Pattern.UNICODE_CHARACTER_CLASS
    private val SCALAR_COUNT =
        Character.MAX_CODE_POINT + 1 - (Character.MAX_SURROGATE.code - Character.MIN_SURROGATE.code + 1)
    private val CASES: MutableList<RegexCase?> = List.of<RegexCase?>(
        regexCase("digit", "\\d"),
        regexCase("nonDigit", "\\D"),
        regexCase("space", "\\s"),
        regexCase("nonSpace", "\\S"),
        regexCase("word", "\\w"),
        regexCase("nonWord", "\\W"),
        regexCase("posixLower", "\\p{Lower}"),
        regexCase("posixUpper", "\\p{Upper}"),
        regexCase("posixAscii", "\\p{ASCII}"),
        regexCase("posixAlpha", "\\p{Alpha}"),
        regexCase("posixDigit", "\\p{Digit}"),
        regexCase("posixAlnum", "\\p{Alnum}"),
        regexCase("posixPunct", "\\p{Punct}"),
        regexCase("posixGraph", "\\p{Graph}"),
        regexCase("posixPrint", "\\p{Print}"),
        regexCase("posixBlank", "\\p{Blank}"),
        regexCase("posixCntrl", "\\p{Cntrl}"),
        regexCase("posixXDigit", "\\p{XDigit}"),
        regexCase("posixSpace", "\\p{Space}"),
        regexCase("wordIntersectionWithoutUnderscore", "[\\w&&[^_]]")
    )

    @Throws(IOException::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val options: SweepOptions =
            SweepOptions.Companion.parse(
                args,
                Path.of("target", "exhaustive-reports", "unicode-character-class-sweep"),
                "unicode-character-class-divergences.jsonl",
                1000000
            )
        Files.createDirectories(options.outputDir)
        if (options.replayFile != null) {
            Files.deleteIfExists(options.jsonlPath())
        }
        options.printStartup("unicode-character-class")

        if (options.replayFile != null) {
            runReplay(options)
            return
        }

        val state = runSweep(options)

        println("checked=" + state.checked.sum())
        println("generated=" + state.generated)
        println("divergences=" + state.divergences.sum())
        println("threads=" + options.threads)
    }

    @Throws(IOException::class)
    private fun runSweep(options: SweepOptions): SweepRunState {
        SweepRunState(options, options.totalChecks(totalCases())).use { runState ->
            runState.enableCompactLogs(
                "unicode-character-class",
                totalCases(),
                mutableListOf<String?>("UNKNOWN"),
                List.of<DivergenceStatus?>(DivergenceStatus.UNKNOWN)
            )
            SweepWorkers.run(
                options.threads,
                "unicode-character-class-sweep-",
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
                        evaluateCase(runState, caseFromIndex(caseIndex), workerIndex, caseIndex)
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
        Files.newBufferedReader(options.replayFile, StandardCharsets.UTF_8).use { reader ->
            SweepRunState(options, 0).use { runState ->
                val generated =
                    SweepWorkers.runStreamingLines(
                        options.threads,
                        "unicode-character-class-replay-",
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
            regexCase(SweepJson.string(caseObject, "label"), SweepJson.string(caseObject, "regex")),
            SweepJson.integer(caseObject, "codePoint")
        )
    }

    fun totalCases(): Long {
        return CASES.size.toLong() * SCALAR_COUNT
    }

    fun compactReplayJson(caseIndex: Long, classification: String?): String {
        val spec = caseFromIndex(caseIndex)
        val `object` = SweepJson.`object`()
        `object`.addProperty("caseIndex", caseIndex)
        `object`.addProperty("classification", classification)
        `object`.add("case", Divergence.Companion.caseJson(spec))
        return SweepJson.toJson(`object`)
    }

    private fun caseFromIndex(index: Long): CaseSpec {
        val regexCase = CASES.get((index / SCALAR_COUNT).toInt())
        val codePoint = scalarCodePoint((index % SCALAR_COUNT).toInt())
        return CaseSpec(regexCase, codePoint)
    }

    private fun scalarCodePoint(ordinal: Int): Int {
        if (ordinal < Character.MIN_SURROGATE.code) {
            return ordinal
        }
        return ordinal + (Character.MAX_SURROGATE.code - Character.MIN_SURROGATE.code + 1)
    }

    private fun evaluateCase(
        runState: SweepRunState, spec: CaseSpec, workerIndex: Int, caseIndex: Long
    ) {
        val input = String(Character.toChars(spec.codePoint))
        val jdk = spec.regexCase!!.jdkPattern!!.matcher(input).matches()
        val safere = spec.regexCase.safeRePattern!!.matcher(input).matches()
        if (jdk == safere) {
            return
        }
        if (workerIndex >= 0) {
            runState.recordCompactDivergence(workerIndex, caseIndex, 0)
        } else {
            runState.recordDivergence()
            runState.appendJsonl(Divergence(spec, jdk, safere).toJson())
        }
    }

    private fun regexCase(label: String?, regex: String): RegexCase {
        return RegexCase(
            label,
            regex,
            Pattern.compile(regex, FLAGS),
            org.safere.Pattern.compile(regex, FLAGS)
        )
    }

    @JvmRecord
    private data class RegexCase(
        val label: String?,
        val regex: String?,
        val jdkPattern: Pattern?,
        val safeRePattern: org.safere.Pattern?
    )

    @JvmRecord
    private data class CaseSpec(val regexCase: RegexCase?, val codePoint: Int)

    @JvmRecord
    private data class Divergence(val spec: CaseSpec?, val jdkMatches: Boolean, val safeReMatches: Boolean) {
        fun toJson(): String {
            val `object` = SweepJson.`object`()
            `object`.add("case", Companion.caseJson(spec!!))
            `object`.addProperty("bucket", "regex=" + spec.regexCase!!.label)
            `object`.addProperty("regex", spec.regexCase.regex)
            `object`.addProperty("input", String(Character.toChars(spec.codePoint)))
            `object`.addProperty("codePoint", String.format(Locale.ROOT, "U+%04X", spec.codePoint))
            `object`.addProperty("characterType", Character.getType(spec.codePoint))
            `object`.addProperty("alphabetic", Character.isAlphabetic(spec.codePoint))
            `object`.addProperty("jdkMatches", jdkMatches)
            `object`.addProperty("safeReMatches", safeReMatches)
            return SweepJson.toJson(`object`)
        }

        companion object {
            private fun caseJson(spec: CaseSpec): JsonObject {
                val `object` = SweepJson.`object`()
                `object`.addProperty("label", spec.regexCase!!.label)
                `object`.addProperty("regex", spec.regexCase.regex)
                `object`.addProperty("codePoint", spec.codePoint)
                return `object`
            }
        }
    }
}
