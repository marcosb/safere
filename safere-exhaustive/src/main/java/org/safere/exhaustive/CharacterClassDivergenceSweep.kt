// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.safere.exhaustive.SweepWorkers.LineWorker
import org.safere.exhaustive.SweepWorkers.ProgressReporter
import java.io.*
import java.lang.String
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.List
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.Array
import kotlin.Boolean
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.Long
import kotlin.RuntimeException
import kotlin.Throws
import kotlin.check
import kotlin.io.println
import kotlin.io.use
import kotlin.require
import kotlin.text.StringBuilder
import kotlin.text.contains
import kotlin.text.isEmpty
import kotlin.text.replace
import kotlin.text.startsWith
import kotlin.use

/** Offline differential sweep for SafeRE character-class expression bugs.  */
object CharacterClassDivergenceSweep {
    private val INPUTS: MutableList<String> = mutableListOf<String?>(
        "", "a", "b", "c", "&", "-", "0", "1", "9", "A", "Z", "_", "`", "x", " ", "\t", "\u0100",
        "\u00e9", "\n", "]"
    )

    private val BASE_PIECES: MutableList<Piece> = List.of<Piece?>(
        piece("empty", ""),
        piece("a", "a"),
        piece("b", "b"),
        piece("ab", "ab"),
        piece("rangeAB", "a-b"),
        piece("zero", "0"),
        piece("range01", "0-1"),
        piece("rawAmp", "&"),
        piece("escapedAmp", "\\&"),
        piece("quoteAmp", "\\Q&\\E"),
        piece("quoteA", "\\Qa\\E"),
        piece("quoteAB", "\\Qab\\E"),
        piece("quoteEmpty", "\\Q\\E"),
        piece("nonInline", "\u0100"),
        piece("escapedNonAscii", "\\\u0100"),
        piece("nestedA", "[a]"),
        piece("nestedB", "[b]"),
        piece("nestedAB", "[ab]"),
        piece("nestedNotB", "[^b]"),
        piece("digit", "\\d"),
        piece("nonDigit", "\\D"),
        piece("word", "\\w"),
        piece("nonWord", "\\W"),
        piece("propertyLower", "\\p{Lower}"),
        piece("propertyNotLower", "\\P{Lower}"),
        piece("propertyJavaLower", "\\p{javaLowerCase}")
    )

    private val LEFT_PIECES: MutableList<Piece> = List.of<Piece?>(
        piece("a", "a"),
        piece("b", "b"),
        piece("ab", "ab"),
        piece("rangeAB", "a-b"),
        piece("zero", "0"),
        piece("digit", "\\d"),
        piece("word", "\\w"),
        piece("nestedA", "[a]"),
        piece("nestedB", "[b]"),
        piece("nestedAB", "[ab]"),
        piece("quoteA", "\\Qa\\E"),
        piece("quoteAB", "\\Qab\\E")
    )

    private val AMPERSAND_PIECES: MutableList<Piece> =
        List.of<Piece?>(piece("rawAmp", "&"), piece("escapedAmp", "\\&"), piece("quoteAmp", "\\Q&\\E"))

    private val RIGHT_PIECES: MutableList<Piece> = List.of<Piece?>(
        piece("empty", ""),
        piece("a", "a"),
        piece("b", "b"),
        piece("ab", "ab"),
        piece("rangeAB", "a-b"),
        piece("zero", "0"),
        piece("range01", "0-1"),
        piece("rawAmp", "&"),
        piece("escapedAmp", "\\&"),
        piece("quoteAmp", "\\Q&\\E"),
        piece("quoteA", "\\Qa\\E"),
        piece("quoteEmpty", "\\Q\\E"),
        piece("nonInline", "\u0100"),
        piece("escapedNonAscii", "\\\u0100"),
        piece("nestedA", "[a]"),
        piece("nestedB", "[b]"),
        piece("nestedAB", "[ab]"),
        piece("digit", "\\d"),
        piece("word", "\\w"),
        piece("nonDigit", "\\D"),
        piece("propertyLower", "\\p{Lower}"),
        piece("propertyNotLower", "\\P{Lower}"),
        piece("propertyJavaLower", "\\p{javaLowerCase}")
    )

    private val TRAILING_PIECES: MutableList<Piece> = List.of<Piece?>(
        piece("none", ""),
        piece("rawAmp", "&"),
        piece("escapedAmp", "\\&"),
        piece("quoteAmp", "\\Q&\\E"),
        piece("rangeToNonDigit", "-\\D"),
        piece("rangeToA", "-a"),
        piece("rangeToAmp", "-&"),
        piece("rawAmpRangeToAmp", "&-&"),
        piece("rawAmpRangeToA", "&-a"),
        piece("rangeToIntersection", "-&&"),
        piece("zeroWidthRangeToNonDigit", "\\Q\\E-\\D"),
        piece("doubleClose", "]")
    )

    private val SEPARATORS: MutableList<Piece?> = List.of<Piece?>(
        piece("none", ""),
        piece("emptyQuote", "\\Q\\E"),
        piece("twoEmptyQuotes", "\\Q\\E\\Q\\E"),
        piece("space", " "),
        piece("comment", " #x\n"),
        piece("emptyQuoteSpace", "\\Q\\E "),
        piece("spaceEmptyQuote", " \\Q\\E")
    )

    private val COMMENT_SEPARATORS: MutableList<Piece> = List.of<Piece?>(
        piece("none", ""),
        piece("emptyQuote", "\\Q\\E"),
        piece("twoEmptyQuotes", "\\Q\\E\\Q\\E"),
        piece("space", " "),
        piece("comment", " #x\n"),
        piece("emptyQuoteSpace", "\\Q\\E "),
        piece("spaceEmptyQuote", " \\Q\\E")
    )

    private val OPERATORS: MutableList<Piece> = List.of<Piece?>(
        piece("&&", "&&"),
        piece("&&&", "&&&"),
        piece("&&&&", "&&&&"),
        piece("&&&&&", "&&&&&"),
        piece("&&&&&&", "&&&&&&")
    )

    private val SECOND_OPERATORS: MutableList<Piece> =
        List.of<Piece?>(piece("&&", "&&"), piece("&&&", "&&&"), piece("&&&&", "&&&&"))

    private val GRAMMAR_LEFT_ATOMS: MutableList<Piece> = List.of<Piece?>(
        piece("empty", ""),
        piece("a", "a"),
        piece("dash", "-"),
        piece("rangeAB", "a-b"),
        piece("digit", "\\d"),
        piece("nestedA", "[a]"),
        piece("nestedB", "[b]"),
        piece("quoteAmp", "\\Q&\\E")
    )

    private val GRAMMAR_RHS_ATOMS: MutableList<Piece> = List.of<Piece?>(
        piece("empty", ""),
        piece("a", "a"),
        piece("b", "b"),
        piece("nestedA", "[a]"),
        piece("nestedB", "[b]"),
        piece("nestedAB", "[ab]"),
        piece("propertyNotLower", "\\P{Lower}"),
        piece("digit", "\\d")
    )

    private val GRAMMAR_TAILS: MutableList<Piece> = List.of<Piece?>(
        piece("none", ""),
        piece("rangeToAmp", "-&"),
        piece("rawAmpRangeToAmp", "&-&"),
        piece("rawAmpRangeToA", "&-a"),
        piece("rangeToA", "-a"),
        piece("propertyNotLower", "\\P{Lower}"),
        piece("doubleClose", "]")
    )

    private val GRAMMAR_ZERO_WIDTH_AND_TRIVIA: MutableList<Piece> = List.of<Piece?>(
        piece("none", ""),
        piece("emptyQuote", "\\Q\\E"),
        piece("twoEmptyQuotes", "\\Q\\E\\Q\\E"),
        piece("space", " "),
        piece("comment", " #x\n"),
        piece("emptyQuoteSpace", "\\Q\\E "),
        piece("spaceEmptyQuote", " \\Q\\E")
    )

    private val RAW_AMPERSAND_CLOSE_TAILS: MutableList<Piece> = List.of<Piece?>(
        piece("rawAmpSpace", "& "),
        piece("rawAmpEmptyQuote", "&\\Q\\E"),
        piece("rawAmpEmptyQuoteSpace", "&\\Q\\E "),
        piece("rangeToAmpRawAmp", "-&&"),
        piece("rangeToAmpRawAmpSpace", "-& &"),
        piece("rangeToAmpEmptyQuoteRawAmp", "-&\\Q\\E&"),
        piece("rangeToAmpCommentRawAmp", "-& #x\n&"),
        piece("rangeToARawAmp", "-a&"),
        piece("rangeToARawAmpSpace", "-a& "),
        piece("rangeToAEmptyQuoteRawAmpSpace", "-a\\Q\\E& ")
    )

    private val NESTED_RIGHTS: MutableList<Piece> = List.of<Piece?>(
        piece("nestedA", "[a]"),
        piece("nestedB", "[b]"),
        piece("nestedAB", "[ab]"),
        piece("nestedNotB", "[^b]")
    )

    @Throws(IOException::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val options: SweepOptions =
            SweepOptions.Companion.parse(
                args,
                Path.of("target", "exhaustive-reports", "character-class-sweep"),
                "character-class-divergences.jsonl",
                1000000
            )
        Files.createDirectories(options.outputDir)
        if (options.replayFile != null) {
            Files.deleteIfExists(options.jsonlPath())
        }
        options.printStartup("character-class")

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
                "character-class",
                totalCases(),
                mutableListOf<String?>("UNKNOWN"),
                List.of<DivergenceStatus?>(DivergenceStatus.UNKNOWN)
            )
            SweepWorkers.run(
                options.threads,
                "character-class-sweep-",
                SweepWorkers.Worker { workerIndex: Int ->
                    val worker = SweepState(runState, workerIndex)
                    runSeparatedSingleAmpersandMatrix(worker)
                    runClassicMatrix(worker)
                    runChainedAmpersandMatrix(worker)
                    runGrammarSequenceMatrix(worker)
                    runRawAmpersandBeforeCloseMatrix(worker)
                    worker.finish()
                })
            return runState
        }
    }

    @JvmStatic
    fun totalCases(): Long {
        return sum(
            separatedSingleAmpersandCases(),
            classicCases(),
            chainedAmpersandCases(),
            grammarSequenceCases(),
            rawAmpersandBeforeCloseCases()
        )
    }

    @Throws(IOException::class)
    private fun runReplay(options: SweepOptions) {
        Files.newBufferedReader(options.replayFile, StandardCharsets.UTF_8).use { reader ->
            SweepRunState(options, 0).use { runState ->
                val generated =
                    SweepWorkers.runStreamingLines(
                        options.threads,
                        "character-class-replay-",
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
        val pieces: MutableList<Piece> = ArrayList<Piece>()
        for (element in SweepJson.array(caseObject, "pieces")) {
            val piece = element.getAsJsonObject()
            pieces.add(Piece(SweepJson.string(piece, "label"), SweepJson.string(piece, "text")))
        }
        return CaseSpec(
            SweepJson.string(caseObject, "template"),
            SweepJson.bool(caseObject, "comments"),
            SweepJson.bool(caseObject, "negated"),
            pieces
        )
    }

    private fun classicCases(): Long {
        var cases: Long = 0
        for (comments in mutableListOf<Boolean?>(false, true)) {
            val separators = (if (comments) COMMENT_SEPARATORS.size else nonCommentSeparators().size).toLong()
            val twoLeftCases =
                product(
                    BASE_PIECES.size.toLong(),
                    BASE_PIECES.size.toLong(),
                    separators,
                    OPERATORS.size.toLong(),
                    separators,
                    RIGHT_PIECES.size.toLong(),
                    TRAILING_PIECES.size.toLong()
                )
            val rawAmpLeftCases =
                product(
                    BASE_PIECES.size.toLong(),
                    AMPERSAND_PIECES.size.toLong(),
                    separators,
                    OPERATORS.size.toLong(),
                    separators,
                    RIGHT_PIECES.size.toLong(),
                    TRAILING_PIECES.size.toLong()
                )
            val noLeftCases =
                product(
                    separators,
                    OPERATORS.size.toLong(),
                    separators,
                    RIGHT_PIECES.size.toLong(),
                    TRAILING_PIECES.size.toLong()
                )
            cases = Math.addExact(cases, product(2, sum(twoLeftCases, rawAmpLeftCases, noLeftCases)))
        }
        return cases
    }

    private fun runClassicMatrix(state: SweepState) {
        for (comments in mutableListOf<Boolean?>(false, true)) {
            for (negated in mutableListOf<Boolean?>(false, true)) {
                val separators = if (comments) COMMENT_SEPARATORS else nonCommentSeparators()
                for (first in BASE_PIECES) {
                    for (second in BASE_PIECES) {
                        for (separator in separators) {
                            for (operator in OPERATORS) {
                                for (afterOperator in separators) {
                                    for (right in RIGHT_PIECES) {
                                        for (trailing in TRAILING_PIECES) {
                                            state.check7(
                                                "classic-two-lefts",
                                                comments!!,
                                                negated!!,
                                                first,
                                                second,
                                                separator,
                                                operator,
                                                afterOperator,
                                                right,
                                                trailing
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                for (first in BASE_PIECES) {
                    for (ampersand in AMPERSAND_PIECES) {
                        for (separator in separators) {
                            for (operator in OPERATORS) {
                                for (afterOperator in separators) {
                                    for (right in RIGHT_PIECES) {
                                        for (trailing in TRAILING_PIECES) {
                                            state.check7(
                                                "classic-raw-amp-left",
                                                comments!!,
                                                negated!!,
                                                first,
                                                ampersand,
                                                separator,
                                                operator,
                                                afterOperator,
                                                right,
                                                trailing
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                for (separator in separators) {
                    for (operator in OPERATORS) {
                        for (afterOperator in separators) {
                            for (right in RIGHT_PIECES) {
                                for (trailing in TRAILING_PIECES) {
                                    state.check5(
                                        "classic-no-left",
                                        comments!!,
                                        negated!!,
                                        separator,
                                        operator,
                                        afterOperator,
                                        right,
                                        trailing
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun chainedAmpersandCases(): Long {
        val rawAmpCases =
            product(
                LEFT_PIECES.size.toLong(),
                COMMENT_SEPARATORS.size.toLong(),
                OPERATORS.size.toLong(),
                COMMENT_SEPARATORS.size.toLong(),
                AMPERSAND_PIECES.size.toLong(),
                COMMENT_SEPARATORS.size.toLong(),
                SECOND_OPERATORS.size.toLong(),
                COMMENT_SEPARATORS.size.toLong(),
                RIGHT_PIECES.size.toLong(),
                TRAILING_PIECES.size.toLong()
            )
        val nestedRhsCases =
            product(
                LEFT_PIECES.size.toLong(),
                COMMENT_SEPARATORS.size.toLong(),
                OPERATORS.size.toLong(),
                COMMENT_SEPARATORS.size.toLong(),
                nestedRights().size.toLong(),
                COMMENT_SEPARATORS.size.toLong(),
                SECOND_OPERATORS.size.toLong(),
                COMMENT_SEPARATORS.size.toLong(),
                RIGHT_PIECES.size.toLong()
            )
        return product(2, sum(rawAmpCases, nestedRhsCases))
    }

    private fun runChainedAmpersandMatrix(state: SweepState) {
        for (negated in mutableListOf<Boolean?>(false, true)) {
            for (left in LEFT_PIECES) {
                for (separator1 in COMMENT_SEPARATORS) {
                    for (firstOperator in OPERATORS) {
                        for (separator2 in COMMENT_SEPARATORS) {
                            for (ampersand in AMPERSAND_PIECES) {
                                for (separator3 in COMMENT_SEPARATORS) {
                                    for (secondOperator in SECOND_OPERATORS) {
                                        for (separator4 in COMMENT_SEPARATORS) {
                                            for (right in RIGHT_PIECES) {
                                                for (trailing in TRAILING_PIECES) {
                                                    state.check10(
                                                        "chained-raw-amp",
                                                        true,
                                                        negated!!,
                                                        left,
                                                        separator1,
                                                        firstOperator,
                                                        separator2,
                                                        ampersand,
                                                        separator3,
                                                        secondOperator,
                                                        separator4,
                                                        right,
                                                        trailing
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            for (left in LEFT_PIECES) {
                for (separator1 in COMMENT_SEPARATORS) {
                    for (firstOperator in OPERATORS) {
                        for (separator2 in COMMENT_SEPARATORS) {
                            for (nested in nestedRights()) {
                                for (separator3 in COMMENT_SEPARATORS) {
                                    for (secondOperator in SECOND_OPERATORS) {
                                        for (separator4 in COMMENT_SEPARATORS) {
                                            for (right in RIGHT_PIECES) {
                                                state.check9(
                                                    "chained-nested-rhs",
                                                    true,
                                                    negated!!,
                                                    left,
                                                    separator1,
                                                    firstOperator,
                                                    separator2,
                                                    nested,
                                                    separator3,
                                                    secondOperator,
                                                    separator4,
                                                    right
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun grammarSequenceCases(): Long {
        var cases: Long = 0
        for (comments in mutableListOf<Boolean?>(false, true)) {
            val trivia = (if (comments) GRAMMAR_ZERO_WIDTH_AND_TRIVIA.size else nonCommentSeparators().size).toLong()
            cases =
                Math.addExact(
                    cases,
                    product(
                        2,
                        GRAMMAR_LEFT_ATOMS.size.toLong(),
                        OPERATORS.size.toLong(),
                        GRAMMAR_RHS_ATOMS.size.toLong(),
                        GRAMMAR_TAILS.size.toLong(),
                        trivia,
                        GRAMMAR_TAILS.size.toLong()
                    )
                )
        }
        return cases
    }

    private fun runGrammarSequenceMatrix(state: SweepState) {
        for (comments in mutableListOf<Boolean?>(false, true)) {
            for (negated in mutableListOf<Boolean?>(false, true)) {
                val trivia = if (comments) GRAMMAR_ZERO_WIDTH_AND_TRIVIA else nonCommentSeparators()
                for (left in GRAMMAR_LEFT_ATOMS) {
                    for (operator in OPERATORS) {
                        for (right in GRAMMAR_RHS_ATOMS) {
                            for (tail1 in GRAMMAR_TAILS) {
                                for (separator in trivia) {
                                    for (tail2 in GRAMMAR_TAILS) {
                                        state.check6(
                                            "grammar-sequence",
                                            comments!!,
                                            negated!!,
                                            left,
                                            operator,
                                            right,
                                            tail1,
                                            separator,
                                            tail2
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun rawAmpersandBeforeCloseCases(): Long {
        return product(
            2,
            LEFT_PIECES.size.toLong(),
            AMPERSAND_PIECES.size.toLong(),
            COMMENT_SEPARATORS.size.toLong(),
            OPERATORS.size.toLong(),
            COMMENT_SEPARATORS.size.toLong(),
            RAW_AMPERSAND_CLOSE_TAILS.size.toLong()
        )
    }

    private fun runRawAmpersandBeforeCloseMatrix(state: SweepState) {
        for (negated in mutableListOf<Boolean?>(false, true)) {
            for (left in LEFT_PIECES) {
                for (ampersand in AMPERSAND_PIECES) {
                    for (beforeOperator in COMMENT_SEPARATORS) {
                        for (operator in OPERATORS) {
                            for (afterOperator in COMMENT_SEPARATORS) {
                                for (tail in RAW_AMPERSAND_CLOSE_TAILS) {
                                    state.check6(
                                        "raw-ampersand-before-close",
                                        true,
                                        negated!!,
                                        left,
                                        ampersand,
                                        beforeOperator,
                                        operator,
                                        afterOperator,
                                        tail
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun separatedSingleAmpersandCases(): Long {
        return product(
            2,
            LEFT_PIECES.size.toLong(),
            COMMENT_SEPARATORS.size.toLong(),
            AMPERSAND_PIECES.size.toLong(),
            COMMENT_SEPARATORS.size.toLong(),
            AMPERSAND_PIECES.size.toLong(),
            nestedRights().size.toLong(),
            RAW_AMPERSAND_CLOSE_TAILS.size.toLong()
        )
    }

    private fun runSeparatedSingleAmpersandMatrix(state: SweepState) {
        for (negated in mutableListOf<Boolean?>(false, true)) {
            for (left in LEFT_PIECES) {
                for (beforeFirstAmp in COMMENT_SEPARATORS) {
                    for (firstAmp in AMPERSAND_PIECES) {
                        for (betweenAmps in COMMENT_SEPARATORS) {
                            for (secondAmp in AMPERSAND_PIECES) {
                                for (nested in nestedRights()) {
                                    for (tail in RAW_AMPERSAND_CLOSE_TAILS) {
                                        state.check7(
                                            "separated-single-ampersands-before-nested",
                                            true,
                                            negated!!,
                                            left,
                                            beforeFirstAmp,
                                            firstAmp,
                                            betweenAmps,
                                            secondAmp,
                                            nested,
                                            tail
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun nestedRights(): MutableList<Piece> {
        return NESTED_RIGHTS
    }

    private fun nonCommentSeparators(): MutableList<Piece?> {
        return SEPARATORS.stream().filter { separator: Piece? -> !separator!!.isCommentsOnly }.toList()
    }

    private fun piece(label: String?, text: String?): Piece {
        return Piece(label, text)
    }

    private fun product(vararg factors: Long): Long {
        var result: Long = 1
        for (factor in factors) {
            result = Math.multiplyExact(result, factor)
        }
        return result
    }

    private fun sum(vararg values: Long): Long {
        var result: Long = 0
        for (value in values) {
            result = Math.addExact(result, value)
        }
        return result
    }

    private fun jdkOutcome(regex: String): Outcome {
        try {
            return Outcome(true, matches(Pattern.compile(regex)), "")
        } catch (e: PatternSyntaxException) {
            return Outcome(false, "", e.getDescription())
        } catch (e: RuntimeException) {
            return Outcome(false, "", e.javaClass.getSimpleName() + ": " + e.message)
        }
    }

    private fun safeReOutcome(regex: String?): Outcome {
        try {
            return Outcome(true, matches(org.safere.Pattern.compile(regex)), "")
        } catch (e: PatternSyntaxException) {
            return Outcome(false, "", e.getDescription())
        } catch (e: RuntimeException) {
            return Outcome(false, "", e.javaClass.getSimpleName() + ": " + e.message)
        }
    }

    private fun matches(pattern: Pattern): String {
        val result = StringBuilder()
        for (input in INPUTS) {
            if (pattern.matcher(input).matches()) {
                appendInput(result, input)
            }
        }
        return result.toString()
    }

    private fun matches(pattern: org.safere.Pattern): String {
        val result = StringBuilder()
        for (input in INPUTS) {
            if (pattern.matcher(input).matches()) {
                appendInput(result, input)
            }
        }
        return result.toString()
    }

    private fun appendInput(result: StringBuilder, input: String) {
        if (result.length > 0) {
            result.append(',')
        }
        result.append(escape(input))
    }

    private fun escape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
            .replace("\"", "\\\"")
    }

    private fun bucketFor(spec: CaseSpec, jdk: Outcome, safere: Outcome): String {
        val kind = if (jdk.accepted != safere.accepted) "compile" else "membership"
        val direction = direction(jdk, safere)
        return String.join(
            "|",
            "kind=" + kind,
            "direction=" + direction,
            "template=" + spec.template,
            "comments=" + spec.comments,
            "negated=" + spec.negated,
            "ops=" + operatorLabels(spec),
            "seps=" + separatorClass(spec),
            "rawAmp=" + containsLabel(spec, "rawAmp"),
            "escapedAmp=" + containsLabel(spec, "escapedAmp"),
            "quoteEmpty=" + containsLabel(spec, "quoteEmpty"),
            "nested=" + containsPrefix(spec, "nested"),
            "rangeTail=" + containsRangeTail(spec),
            "doubleClose=" + containsLabel(spec, "doubleClose")
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

    private fun operatorLabels(spec: CaseSpec): kotlin.String {
        val labels: MutableList<kotlin.String?> = ArrayList<kotlin.String?>()
        for (piece in spec.pieces!!) {
            if (piece.text!!.startsWith("&&")) {
                labels.add(piece.label)
            }
        }
        return String.join(",", labels)
    }

    private fun separatorClass(spec: CaseSpec): kotlin.String {
        val hasComment = containsLabel(spec, "comment")
        val hasSpace =
            containsLabel(spec, "space")
                    || containsLabel(spec, "emptyQuoteSpace")
                    || containsLabel(spec, "spaceEmptyQuote")
        val hasEmptyQuote =
            containsLabel(spec, "emptyQuote")
                    || containsLabel(spec, "twoEmptyQuotes")
                    || containsLabel(spec, "emptyQuoteSpace")
                    || containsLabel(spec, "spaceEmptyQuote")
        return ((if (hasComment) "comment" else "no-comment")
                + ","
                + (if (hasSpace) "space" else "no-space")
                + ","
                + (if (hasEmptyQuote) "emptyQuote" else "no-emptyQuote"))
    }

    private fun containsLabel(spec: CaseSpec, label: kotlin.String?): Boolean {
        return spec.pieces!!.stream().anyMatch { piece: Piece? -> piece!!.label == label }
    }

    private fun containsPrefix(spec: CaseSpec, prefix: kotlin.String): Boolean {
        return spec.pieces!!.stream().anyMatch { piece: Piece? -> piece!!.label!!.startsWith(prefix) }
    }

    private fun containsRangeTail(spec: CaseSpec): Boolean {
        return spec.pieces!!.stream()
            .anyMatch { piece: Piece? -> piece!!.label!!.contains("range") || piece.label.contains("Range") }
    }

    private fun evaluateCase(
        runState: SweepRunState, spec: CaseSpec, workerIndex: Int, caseIndex: Long
    ) {
        val regex = spec.regex()
        val jdk = jdkOutcome(regex)
        val safere = safeReOutcome(regex)
        if (semanticallyEqual(jdk, safere)) {
            return
        }
        val bucketName = bucketFor(spec, jdk, safere)
        if (workerIndex >= 0) {
            runState.recordCompactDivergence(workerIndex, caseIndex, 0)
            return
        }
        runState.recordDivergence()
        val divergence =
            Divergence(spec, regex, jdk, safere, bucketName, reduce(spec, jdk, safere))
        runState.appendJsonl(divergence.toJson())
    }

    @JvmStatic
    fun compactReplayJson(caseIndex: Long, classification: kotlin.String?): kotlin.String {
        return CharacterClassDivergenceSweep.compactReplayJson(caseIndex, classification, locateCase(caseIndex)!!)
    }

    @Throws(IOException::class)
    fun writeCompactReplayJson(
        writer: BufferedWriter, classification: kotlin.String?, caseIndices: MutableList<Long?>
    ) {
        val cases = locateCases(caseIndices)
        for (i in caseIndices.indices) {
            writer.write(
                CharacterClassDivergenceSweep.compactReplayJson(
                    caseIndices.get(i)!!,
                    classification,
                    cases.get(i)!!
                )
            )
            writer.newLine()
        }
    }

    @Throws(IOException::class)
    fun writeAllCompactReplayJson(
        writer: BufferedWriter?, classification: kotlin.String?, indicesPath: Path
    ) {
        DataInputStream(BufferedInputStream(Files.newInputStream(indicesPath))).use { input ->
            try {
                val state = SweepState(writer, classification, input)
                runSeparatedSingleAmpersandMatrix(state)
                runClassicMatrix(state)
                runChainedAmpersandMatrix(state)
                runGrammarSequenceMatrix(state)
                runRawAmpersandBeforeCloseMatrix(state)
            } catch (complete: LocatedCasesComplete) {
                return
            } catch (e: CompactExpansionException) {
                throw e.ioException
            }
        }
        throw IllegalStateException("failed to locate all character-class cases")
    }

    private fun compactReplayJson(caseIndex: Long, classification: kotlin.String?, spec: CaseSpec): kotlin.String {
        val `object` = SweepJson.`object`()
        `object`.addProperty("caseIndex", caseIndex)
        `object`.addProperty("classification", classification)
        `object`.add("case", Divergence.Companion.caseJson(spec))
        return SweepJson.toJson(`object`)
    }

    private fun locateCases(caseIndices: MutableList<Long?>): MutableList<CaseSpec?> {
        if (caseIndices.isEmpty()) {
            return mutableListOf<CaseSpec?>()
        }
        var previous: Long = -1
        for (caseIndex in caseIndices) {
            require(!(caseIndex!! <= previous || caseIndex >= totalCases())) { "case indices must be sorted, unique, and in range" }
            previous = caseIndex
        }
        val state = SweepState(caseIndices)
        try {
            runSeparatedSingleAmpersandMatrix(state)
            runClassicMatrix(state)
            runChainedAmpersandMatrix(state)
            runGrammarSequenceMatrix(state)
            runRawAmpersandBeforeCloseMatrix(state)
        } catch (complete: LocatedCasesComplete) {
            return state.locatedCases!!
        }
        throw IllegalStateException("failed to locate character-class cases")
    }

    private fun locateCase(caseIndex: Long): CaseSpec? {
        require(!(caseIndex < 0 || caseIndex >= totalCases())) { "case index out of range: " + caseIndex }
        val state = SweepState(caseIndex)
        try {
            runSeparatedSingleAmpersandMatrix(state)
            runClassicMatrix(state)
            runChainedAmpersandMatrix(state)
            runGrammarSequenceMatrix(state)
            runRawAmpersandBeforeCloseMatrix(state)
        } catch (located: LocatedCase) {
            return located.spec
        }
        throw IllegalStateException("failed to locate character-class case: " + caseIndex)
    }

    private fun reduce(spec: CaseSpec, expectedJdk: Outcome, expectedSafeRe: Outcome): kotlin.String {
        var pieces: MutableList<Piece> = ArrayList<Piece>(spec.pieces)
        var changed: Boolean
        do {
            changed = false
            var i = 0
            while (i < pieces.size) {
                if (pieces.get(i).text!!.isEmpty()) {
                    i++
                    continue
                }
                val candidate: MutableList<Piece> = ArrayList<Piece>(pieces)
                candidate.set(i, piece("removed", ""))
                val regex =
                    CaseSpec(spec.template, spec.comments, spec.negated, candidate).regex()
                val jdk = jdkOutcome(regex)
                val safere = safeReOutcome(regex)
                if (semanticallyEqual(expectedJdk, jdk) && semanticallyEqual(expectedSafeRe, safere)) {
                    pieces = candidate
                    changed = true
                    break
                }
                i++
            }
        } while (changed)
        return CaseSpec(spec.template, spec.comments, spec.negated, pieces).regex()
    }

    private fun semanticallyEqual(left: Outcome, right: Outcome): Boolean {
        if (left.accepted != right.accepted) {
            return false
        }
        return !left.accepted || left.matches == right.matches
    }

    @JvmRecord
    private data class Piece(val label: kotlin.String?, val text: kotlin.String?) {
        val isCommentsOnly: Boolean
            get() = text!!.contains(" ") || text.contains("#")
    }

    @JvmRecord
    private data class CaseSpec(
        val template: kotlin.String?,
        val comments: Boolean,
        val negated: Boolean,
        val pieces: MutableList<Piece>?
    ) {
        fun regex(): kotlin.String {
            val regex = StringBuilder()
            if (comments) {
                regex.append("(?x)")
            }
            regex.append('[')
            if (negated) {
                regex.append('^')
            }
            for (piece in pieces!!) {
                regex.append(piece.text)
            }
            regex.append(']')
            return regex.toString()
        }

        fun labels(): kotlin.String {
            val labels: MutableList<kotlin.String?> = ArrayList<kotlin.String?>()
            for (piece in pieces!!) {
                if (!piece.text!!.isEmpty()) {
                    labels.add(piece.label)
                }
            }
            return String.join(",", labels)
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
        val bucket: kotlin.String?,
        val reduced: kotlin.String?
    ) {
        fun toJson(): kotlin.String {
            val `object` = SweepJson.`object`()
            `object`.add("case", Companion.caseJson(spec!!))
            `object`.addProperty("bucket", bucket)
            `object`.addProperty("template", spec.template)
            `object`.addProperty("labels", spec.labels())
            `object`.addProperty("regex", regex)
            `object`.addProperty("reduced", reduced)
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
                `object`.addProperty("template", spec.template)
                `object`.addProperty("comments", spec.comments)
                `object`.addProperty("negated", spec.negated)
                val pieces = JsonArray()
                for (piece in spec.pieces!!) {
                    val pieceObject = SweepJson.`object`()
                    pieceObject.addProperty("label", piece.label)
                    pieceObject.addProperty("text", piece.text)
                    pieces.add(pieceObject)
                }
                `object`.add("pieces", pieces)
                return `object`
            }
        }
    }

    private class SweepState {
        val runState: SweepRunState?
        val options: SweepOptions?
        val progressReporter: ProgressReporter?
        val workerIndex: Int
        val targetCaseIndex: Long
        val targetCaseIndices: MutableList<Long?>?
        val locatedCases: MutableList<CaseSpec?>?
        val compactWriter: BufferedWriter?
        val compactClassification: kotlin.String?
        val compactIndices: DataInputStream?
        var targetPosition: Int = 0
        var nextCompactCaseIndex: Long = 0
        var generated: Long = 0
        var currentCaseIndex: Long = 0

        internal constructor(runState: SweepRunState, workerIndex: Int) {
            this.runState = runState
            this.options = runState.options
            this.progressReporter = ProgressReporter(runState, workerIndex)
            this.workerIndex = workerIndex
            this.targetCaseIndex = -1
            this.targetCaseIndices = null
            this.locatedCases = null
            this.compactWriter = null
            this.compactClassification = null
            this.compactIndices = null
        }

        internal constructor(targetCaseIndex: Long) {
            this.runState = null
            this.options = null
            this.progressReporter = null
            this.workerIndex = -1
            this.targetCaseIndex = targetCaseIndex
            this.targetCaseIndices = null
            this.locatedCases = null
            this.compactWriter = null
            this.compactClassification = null
            this.compactIndices = null
        }

        internal constructor(targetCaseIndices: MutableList<Long?>?) {
            this.runState = null
            this.options = null
            this.progressReporter = null
            this.workerIndex = -1
            this.targetCaseIndex = -1
            this.targetCaseIndices = targetCaseIndices
            this.locatedCases = ArrayList<CaseSpec?>()
            this.compactWriter = null
            this.compactClassification = null
            this.compactIndices = null
        }

        internal constructor(
            compactWriter: BufferedWriter?,
            compactClassification: kotlin.String?,
            compactIndices: DataInputStream?
        ) {
            this.runState = null
            this.options = null
            this.progressReporter = null
            this.workerIndex = -1
            this.targetCaseIndex = -1
            this.targetCaseIndices = null
            this.locatedCases = null
            this.compactWriter = compactWriter
            this.compactClassification = compactClassification
            this.compactIndices = compactIndices
            advanceCompactCaseIndex()
        }

        fun check5(
            template: kotlin.String?,
            comments: Boolean,
            negated: Boolean,
            p1: Piece,
            p2: Piece,
            p3: Piece,
            p4: Piece,
            p5: Piece
        ) {
            if (!beginCase()) {
                return
            }
            evaluateCase(CaseSpec(template, comments, negated, List.of<Piece?>(p1, p2, p3, p4, p5)))
        }

        fun check6(
            template: kotlin.String?,
            comments: Boolean,
            negated: Boolean,
            p1: Piece,
            p2: Piece,
            p3: Piece,
            p4: Piece,
            p5: Piece,
            p6: Piece
        ) {
            if (!beginCase()) {
                return
            }
            evaluateCase(CaseSpec(template, comments, negated, List.of<Piece?>(p1, p2, p3, p4, p5, p6)))
        }

        fun check7(
            template: kotlin.String?,
            comments: Boolean,
            negated: Boolean,
            p1: Piece,
            p2: Piece,
            p3: Piece,
            p4: Piece,
            p5: Piece,
            p6: Piece,
            p7: Piece
        ) {
            if (!beginCase()) {
                return
            }
            evaluateCase(CaseSpec(template, comments, negated, List.of<Piece?>(p1, p2, p3, p4, p5, p6, p7)))
        }

        fun check9(
            template: kotlin.String?,
            comments: Boolean,
            negated: Boolean,
            p1: Piece,
            p2: Piece,
            p3: Piece,
            p4: Piece,
            p5: Piece,
            p6: Piece,
            p7: Piece,
            p8: Piece,
            p9: Piece
        ) {
            if (!beginCase()) {
                return
            }
            evaluateCase(
                CaseSpec(template, comments, negated, List.of<Piece?>(p1, p2, p3, p4, p5, p6, p7, p8, p9))
            )
        }

        fun check10(
            template: kotlin.String?,
            comments: Boolean,
            negated: Boolean,
            p1: Piece,
            p2: Piece,
            p3: Piece,
            p4: Piece,
            p5: Piece,
            p6: Piece,
            p7: Piece,
            p8: Piece,
            p9: Piece,
            p10: Piece
        ) {
            if (!beginCase()) {
                return
            }
            evaluateCase(
                CaseSpec(
                    template, comments, negated, List.of<Piece?>(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10)
                )
            )
        }

        fun beginCase(): Boolean {
            if (compactIndices != null) {
                currentCaseIndex = generated++
                return currentCaseIndex == nextCompactCaseIndex
            }
            if (targetCaseIndices != null) {
                currentCaseIndex = generated++
                return currentCaseIndex == targetCaseIndices.get(targetPosition)
            }
            if (targetCaseIndex >= 0) {
                currentCaseIndex = generated++
                return currentCaseIndex == targetCaseIndex
            }
            if (generated >= options!!.rangeEndExclusive) {
                return false
            }
            val caseIndex = generated++
            currentCaseIndex = caseIndex
            if (caseIndex < options.rangeStartInclusive) {
                reportProgressIfNeeded()
                return false
            }
            if (caseIndex % options.threads != workerIndex.toLong()) {
                return false
            }
            progressReporter!!.checked()
            return true
        }

        fun evaluateCase(spec: CaseSpec) {
            if (compactIndices != null) {
                try {
                    compactWriter!!.write(compactReplayJson(currentCaseIndex, compactClassification, spec))
                    compactWriter.newLine()
                } catch (e: IOException) {
                    throw CompactExpansionException(e)
                }
                advanceCompactCaseIndex()
                return
            }
            if (targetCaseIndices != null) {
                locatedCases!!.add(spec)
                targetPosition++
                if (targetPosition == targetCaseIndices.size) {
                    throw LocatedCasesComplete()
                }
                return
            }
            if (targetCaseIndex >= 0) {
                throw LocatedCase(spec)
            }
            CharacterClassDivergenceSweep.evaluateCase(runState!!, spec, workerIndex, currentCaseIndex)
            reportProgressIfNeeded()
        }

        fun finish() {
            runState!!.recordGenerated(generated)
            runState.updateWorkerNextCaseIndex(workerIndex, generated)
        }

        fun reportProgressIfNeeded() {
            progressReporter!!.reportIfNeeded(generated)
        }

        // EOFException marks the normal end of the index file, and LocatedCasesComplete is a
        // control-flow signal, so the EOFException carries nothing worth keeping as a cause.
        fun advanceCompactCaseIndex() {
            try {
                nextCompactCaseIndex = compactIndices!!.readLong()
            } catch (e: EOFException) {
                throw LocatedCasesComplete()
            } catch (e: IOException) {
                throw CompactExpansionException(e)
            }
        }
    }

    private class LocatedCase(@field:Transient val spec: CaseSpec?) : RuntimeException() {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private object LocatedCasesComplete : RuntimeException() {
        private const val serialVersionUID = 1L
    }

    private class CompactExpansionException(@field:Transient val ioException: IOException) : RuntimeException() {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}
