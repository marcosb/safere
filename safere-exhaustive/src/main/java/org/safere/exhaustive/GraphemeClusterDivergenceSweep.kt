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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.Array
import kotlin.Boolean
import kotlin.IllegalArgumentException
import kotlin.IndexOutOfBoundsException
import kotlin.Int
import kotlin.Long
import kotlin.RuntimeException
import kotlin.Throws
import kotlin.arrayOfNulls
import kotlin.check
import kotlin.code
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashMap
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.contains
import kotlin.collections.indices
import kotlin.collections.mutableListOf
import kotlin.collections.toTypedArray
import kotlin.io.println
import kotlin.io.use
import kotlin.math.min
import kotlin.plus
import kotlin.use

/** Offline differential sweep for `\X` and `\b{g}` grapheme-cluster bugs.  */
object GraphemeClusterDivergenceSweep {
    private const val FIND_LIMIT = 32
    private const val FIRST_UNKNOWN_LIMIT = 100
    private const val ACTIONABLE_SAMPLE_LIMIT = 100
    private const val DEFAULT_PROGRESS_INTERVAL: Long = 10000000
    private val JDK_PATTERN_CACHE: ConcurrentMap<String?, Pattern> = ConcurrentHashMap<String?, Pattern>()
    private val SAFERE_PATTERN_CACHE: ConcurrentMap<String?, org.safere.Pattern> =
        ConcurrentHashMap<String?, org.safere.Pattern>()

    private val GRAPHEME_CLASS_ATOMS: MutableList<GraphemeAtom> = List.of<GraphemeAtom?>(
        atom("CR", "\r"),
        atom("LF", "\n"),
        atom("Control", "\u0000"),
        atom("Extend", "\u0301"),
        atom("Extend2", "\u0327"),
        atom("ZWJ", "\u200D"),
        atom("RegionalIndicator", "\uD83C\uDDFA"),
        atom("Prepend", "\u0600"),
        atom("SpacingMark", "\u0903"),
        atom("HangulL", "\u1100"),
        atom("HangulV", "\u1161"),
        atom("HangulT", "\u11A8"),
        atom("HangulLV", "\uAC00"),
        atom("HangulLVT", "\uAC01"),
        atom("EmojiModifier", "\uD83C\uDFFD"),
        atom("ExtendedPictographic", "\uD83D\uDC69"),
        atom("OtherBmp", "a"),
        atom("OtherSupplementary", "\uD83D\uDE00"),
        atom("HighSurrogate", "\uD83D"),
        atom("LowSurrogate", "\uDE00")
    )

    private val HIGH_RISK_GRAPHEME_CLASS_ATOMS: MutableList<GraphemeAtom> = List.of<GraphemeAtom?>(
        atom("CR", "\r"),
        atom("LF", "\n"),
        atom("Control", "\u0000"),
        atom("Extend", "\u0301"),
        atom("ZWJ", "\u200D"),
        atom("RegionalIndicator", "\uD83C\uDDFA"),
        atom("Prepend", "\u0600"),
        atom("SpacingMark", "\u0903"),
        atom("HangulL", "\u1100"),
        atom("HangulV", "\u1161"),
        atom("HangulT", "\u11A8"),
        atom("ExtendedPictographic", "\uD83D\uDC69"),
        atom("OtherBmp", "a"),
        atom("LowSurrogate", "\uDE00")
    )

    private val HIGH_RISK_LONG_GRAPHEME_CLASS_ATOMS: MutableList<GraphemeAtom> = List.of<GraphemeAtom?>(
        atom("Extend", "\u0301"),
        atom("ZWJ", "\u200D"),
        atom("RegionalIndicator", "\uD83C\uDDFA"),
        atom("ExtendedPictographic", "\uD83D\uDC69"),
        atom("OtherBmp", "a"),
        atom("LowSurrogate", "\uDE00")
    )

    private val REGEX_TEMPLATES: MutableList<RegexTemplate?> = List.of<RegexTemplate?>(
        regex("oneCluster", "\\X"),
        regex("twoClusters", "\\X\\X"),
        regex("anchoredTwoClusters", "^\\X\\X"),
        regex("anchoredOptionalCaretTwoClusters", "^\\^?\\X\\X"),
        regex("exactTwoClusters", "^\\X\\X$"),
        regex("twoClusterRepeat", "\\X{2}"),
        regex("anchoredTwoClusterRepeat", "^\\X{2}"),
        regex("exactTwoClusterRepeat", "^\\X{2}$"),
        regex("nonCapturingTwoClusters", "(?:\\X)(?:\\X)"),
        regex("anchoredNonCapturingTwoClusters", "^(?:\\X)(?:\\X)"),
        regex("exactNonCapturingTwoClusters", "^(?:\\X)(?:\\X)$"),
        regex("oneRepeatThenCluster", "\\X{1}\\X"),
        regex("anchoredOneRepeatThenCluster", "^\\X{1}\\X"),
        regex("exactOneRepeatThenCluster", "^\\X{1}\\X$"),
        regex("nonCapturingClusterRepeat", "(?:\\X){2}"),
        regex("anchoredNonCapturingClusterRepeat", "^(?:\\X){2}"),
        regex("exactNonCapturingClusterRepeat", "^(?:\\X){2}$"),
        regex("capturedCluster", "(\\X)"),
        regex("capturedTwoClusters", "(\\X)(\\X)"),
        regex("anchoredCapturedTwoClusters", "^(\\X)(\\X)"),
        regex("clusterPlus", "\\X+"),
        regex("anchoredClusterPlus", "^\\X+"),
        regex("invalidClusterInClass", "[\\X]"),
        regex("boundary", "\\b{g}"),
        regex("capturedBoundary", "(\\b{g})"),
        regex("optionalBoundary", "\\b{g}?"),
        regex("invalidBoundaryInClass", "[\\b{g}]"),
        regex("boundaryBetweenBaseAndMark", "a\\b{g}\\u0300"),
        regex("nonCapturingBaseBoundaryMark", "(?:a)\\b{g}\\u0300"),
        regex("capturedBaseBoundaryMark", "(a)\\b{g}\\u0300"),
        regex("baseNonCapturingBoundaryMark", "a(?:\\b{g})\\u0300"),
        regex("escapedBaseBoundaryEscapedMark", "\\u0061\\b{g}\\u0300"),
        regex("boundaryAroundBaseMark", "\\b{g}a\\u0300\\b{g}"),
        regex("boundaryBetweenCrLf", "\\r\\b{g}\\n"),
        regex("nonCapturingCrBoundaryLf", "(?:\\r)\\b{g}\\n"),
        regex("capturedCrBoundaryLf", "(\\r)\\b{g}\\n"),
        regex("crNonCapturingBoundaryLf", "\\r(?:\\b{g})\\n"),
        regex("escapedCrBoundaryEscapedLf", "\\u000D\\b{g}\\u000A"),
        regex("clusterThenBoundary", "\\X\\b{g}"),
        regex("anchoredClusterThenBoundary", "^\\X\\b{g}"),
        regex("boundaryClusterBoundary", "\\b{g}\\X\\b{g}"),
        regex("anchoredBoundaryClusterBoundary", "^\\b{g}\\X\\b{g}"),
        regex("boundaryOnlyAlternativeAfterAscii", "a|\\b{g}"),
        regex("boundaryOnlyAlternativeBeforeAscii", "\\b{g}|a"),
        regex("groupedBoundaryOnlyAlternativeAfterAscii", "(?:a|\\b{g})"),
        regex("groupedBoundaryOnlyAlternativeBeforeAscii", "(?:\\b{g}|a)"),
        regex("capturedBoundaryOnlyAlternativeAfterAscii", "(a)|(\\b{g})"),
        regex("capturedBoundaryOnlyAlternativeBeforeAscii", "(\\b{g})|(a)"),
        regex("nestedBoundaryOnlyAlternativeAfterAscii", "(?:a|(?:\\b{g}))"),
        regex("nestedBoundaryOnlyAlternativeBeforeAscii", "(?:(?:\\b{g})|a)"),
        regex("boundaryOnlyAlternativeAfterCluster", "\\X|\\b{g}"),
        regex("boundaryOnlyAlternativeBeforeCluster", "\\b{g}|\\X"),
        regex("groupedBoundaryOnlyAlternativeAfterCluster", "(?:\\X|\\b{g})"),
        regex("groupedBoundaryOnlyAlternativeBeforeCluster", "(?:\\b{g}|\\X)"),
        regex("capturedBoundaryOnlyAlternativeAfterCluster", "(\\X)|(\\b{g})"),
        regex("capturedBoundaryOnlyAlternativeBeforeCluster", "(\\b{g})|(\\X)"),
        regex("boundaryClusterAlternativeAfterLiteral", "b|\\b{g}\\X"),
        regex("boundaryClusterAlternativeBeforeLiteral", "\\b{g}\\X|b"),
        regex("groupedBoundaryClusterAlternativeAfterLiteral", "(?:b|\\b{g}\\X)"),
        regex("groupedBoundaryClusterAlternativeBeforeLiteral", "(?:\\b{g}\\X|b)"),
        regex("capturedBoundaryClusterAlternativeAfterLiteral", "(b)|(\\b{g}\\X)"),
        regex("capturedBoundaryClusterAlternativeBeforeLiteral", "(\\b{g}\\X)|(b)"),
        regex("boundaryClusterAlternativeAfterCluster", "\\X|\\b{g}\\X"),
        regex("boundaryClusterAlternativeBeforeCluster", "\\b{g}\\X|\\X"),
        regex("groupedBoundaryClusterAlternativeAfterCluster", "(?:\\X|\\b{g}\\X)"),
        regex("groupedBoundaryClusterAlternativeBeforeCluster", "(?:\\b{g}\\X|\\X)"),
        regex("boundaryClusterAlternativeAfterAscii", "a|\\b{g}\\X"),
        regex("boundaryClusterAlternativeBeforeAscii", "\\b{g}\\X|a"),
        regex("nestedBoundaryClusterAlternativeAfterLiteral", "(?:a|(?:\\b{g}\\X))"),
        regex("nestedBoundaryClusterAlternativeBeforeLiteral", "(?:(?:\\b{g}\\X)|a)")
    )

    private val INPUT_SPACE: InputSpace = buildInputSpace()
    private val INPUT_CASES = INPUT_SPACE.size()

    private val REGION_MODES: MutableList<RegionMode?> = List.of<RegionMode?>(
        region("full", "", "", 0, 0),
        region("wrapped", "#", "$", 0, 0),
        region("prefixed", "zz", "", 0, 0),
        region("insideSupplementaryPrefix", "\uD83D", "\uDE00", 0, 0),
        region("afterBaseBeforeMark", "a", "\u0300", 0, 0),
        fixedRegion("emptyInsideSupplementaryPrefix", "\uD83D", "\uDE00", 1, 1),
        fixedRegion("lowSurrogateOnlyPrefix", "\uD83D\uDE00", "", 1, 2),
        region("startAtLowSurrogatePrefix", "\uD83D", "", 0, 0),
        region("startAtLowSurrogateBeforeExtend", "\uD83D", "\u0301", 0, 0),
        region("startAtLowSurrogateBeforeZwj", "\uD83D", "\u200D", 0, 0),
        region("startAtLowSurrogateBeforeSpacingMark", "\uD83D", "\u0903", 0, 0),
        region("startAtLowSurrogateBeforeEmojiModifier", "\uD83D", "\uD83C\uDFFD", 0, 0),
        region("startAtLowSurrogateBeforeLowSurrogate", "\uD83D", "\uDE00", 0, 0),
        region("endBeforeSuffixExtend", "", "\u0301", 0, 0),
        region("endBeforeSuffixZwj", "", "\u200D", 0, 0),
        region("endBeforeSuffixSpacingMark", "", "\u0903", 0, 0),
        region("endBeforeSuffixEmojiModifier", "", "\uD83C\uDFFD", 0, 0),
        region("endBeforeSuffixRegionalIndicator", "", "\uD83C\uDDFA", 0, 0),
        region("endBeforeSuffixHangulL", "", "\u1100", 0, 0),
        region("endBeforeSuffixHangulV", "", "\u1161", 0, 0),
        region("endBeforeSuffixHangulT", "", "\u11A8", 0, 0),
        region("endBeforeSuffixLf", "", "\n", 0, 0),
        region("endBeforeSuffixExtendedPictographic", "", "\uD83D\uDC69", 0, 0),
        region("endBeforeSuffixLowSurrogate", "", "\uDE00", 0, 0),
        region("bothEndsSplitSurrogates", "\uD83D", "\uDE00", 0, 0)
    )

    private val BOUNDS_MODES: MutableList<BoundsMode> = List.of<BoundsMode?>(
        bounds("opaqueAnchoring", false, true),
        bounds("opaqueNonAnchoring", false, false),
        bounds("transparentAnchoring", true, true),
        bounds("transparentNonAnchoring", true, false)
    )

    private val OPERATION_MODES: MutableList<OperationMode> =
    // Matcher.find() is specified in terms of the first find in a region and previous successful
    // find() invocations. Keep this sweep focused on those specified find sequences rather than
        // implementation-specific matcher state after matches() or lookingAt().
        List.of<OperationMode?>(
            operation(
                "freshTrace",
                JdkTrace { obj: Pattern?, pattern: CaseSpec? -> GraphemeClusterDivergenceSweep.freshTrace(pattern) },
                SafeReTrace { obj: org.safere.Pattern?, pattern: CaseSpec? ->
                    GraphemeClusterDivergenceSweep.freshTrace(
                        pattern
                    )
                }),
            operation(
                "resetRegionReuse",
                JdkTrace { obj: Pattern?, pattern: CaseSpec? ->
                    GraphemeClusterDivergenceSweep.resetRegionReuseTrace(
                        pattern
                    )
                },
                SafeReTrace { obj: org.safere.Pattern?, pattern: CaseSpec? ->
                    GraphemeClusterDivergenceSweep.resetRegionReuseTrace(
                        pattern
                    )
                })
        )

    private val TARGETED_REGION_INPUTS: MutableList<TargetedRegionInput> = List.of<TargetedRegionInput?>(
        targetedRegion("splitTrailingRegionalBeforeRegional", "\uD83C\uDDE6\uD83C\uDDE6", 0, 1),
        targetedRegion("splitLeadingRegionalBeforeRegional", "\uD83C\uDDE6\uD83C\uDDE6", 1, 3),
        targetedRegion(
            "splitLeadingRegionalBeforeRegionalFullSecond", "\uD83C\uDDE6\uD83C\uDDE6", 1, 4
        ),
        targetedRegion(
            "splitTrailingRegionalTriple", "\uD83C\uDDE6\uD83C\uDDE6\uD83C\uDDE6", 0, 1
        ),
        targetedRegion(
            "splitLeadingRegionalTriple", "\uD83C\uDDE6\uD83C\uDDE6\uD83C\uDDE6", 1, 5
        ),
        targetedRegion("splitTrailingZwjEmoji", "\uD83D\uDC69\u200D\uD83D\uDC69", 0, 1),
        targetedRegion("splitLeadingZwjEmoji", "\uD83D\uDC69\u200D\uD83D\uDC69", 1, 5),
        targetedRegion("splitLeadingZwjEmojiSplitSecond", "\uD83D\uDC69\u200D\uD83D\uDC69", 1, 4),
        targetedRegion("splitTrailingEmojiModifier", "\uD83D\uDC4D\uD83C\uDFFB", 0, 1),
        targetedRegion("splitLeadingEmojiModifier", "\uD83D\uDC4D\uD83C\uDFFB", 1, 3),
        targetedRegion("splitLeadingEmojiModifierFullModifier", "\uD83D\uDC4D\uD83C\uDFFB", 1, 4),
        targetedRegion("splitTrailingSupplementaryBeforeExtend", "\uD83D\uDE00\u0301", 0, 1),
        targetedRegion("splitLeadingSupplementaryBeforeExtend", "\uD83D\uDE00\u0301", 1, 3),
        targetedRegion(
            "splitTrailingSupplementaryBeforeZwjEmoji", "\uD83D\uDE00\u200D\uD83D\uDE00", 0, 1
        ),
        targetedRegion(
            "splitLeadingSupplementaryBeforeZwjEmoji", "\uD83D\uDE00\u200D\uD83D\uDE00", 1, 5
        ),
        targetedRegion(
            "splitLeadingSupplementaryBeforeZwjEmojiSplitSecond",
            "\uD83D\uDE00\u200D\uD83D\uDE00",
            1,
            4
        ),
        targetedRegion("indicConjunctFullRegion", "\u0915\u094D\u0937", 0, 3),
        targetedRegion("indicConjunctAfterFirstConsonant", "\u0915\u094D\u0937", 1, 3)
    )

    private val GRAPHEME_CANDIDATE_START_REGEX_LABELS = mutableListOf<String?>(
        "twoClusters",
        "twoClusterRepeat",
        "nonCapturingTwoClusters",
        "oneRepeatThenCluster",
        "nonCapturingClusterRepeat",
        "capturedTwoClusters"
    )

    private val TARGETED_REGION_CASES: MutableList<CaseSpec> = buildTargetedRegionCases()

    @Throws(IOException::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val options = GraphemeClusterDivergenceSweep.parseOptions(args)
        Files.createDirectories(options.sweep.outputDir)
        if (options.sweep.replayFile != null) {
            Files.deleteIfExists(options.sweep.jsonlPath())
        }
        options.printStartup()

        if (options.sweep.replayFile != null) {
            runReplay(options)
            return
        }

        val result = runSweep(options)

        println("checked=" + result.checked)
        println("generated=" + result.generated)
        println("totalCases=" + totalCases())
        println("inputCases=" + INPUT_CASES)
        println("inputFamilies=" + INPUT_SPACE.familySummary())
        println("divergences=" + result.divergences)
        println("actionableDivergences=" + result.summary!!.actionableCount())
        println("unknownDivergences=" + result.summary.count(DivergenceClass.UNKNOWN))
        println("threads=" + options.sweep.threads)
    }

    private fun parseOptions(args: Array<String?>): GraphemeSweepOptions {
        val sweepOptions: SweepOptions =
            SweepOptions.Companion.parse(
                args,
                Path.of("target", "exhaustive-reports", "grapheme-cluster-sweep"),
                "grapheme-cluster-divergences.jsonl",
                DEFAULT_PROGRESS_INTERVAL
            )
        return GraphemeSweepOptions(sweepOptions)
    }

    @Throws(IOException::class)
    private fun runSweep(options: GraphemeSweepOptions): GraphemeRunResult {
        val sweepOptions = options.sweep
        val selectedCaseCount = sweepOptions.totalChecks(totalCases())
        val workerSummaries: MutableList<ClassifiedDivergenceSummary<DivergenceClass?>?> =
            ArrayList<ClassifiedDivergenceSummary<DivergenceClass?>?>()
        for (i in 0..<sweepOptions.threads) {
            workerSummaries.add(null)
        }
        SweepRunState(sweepOptions, selectedCaseCount).use { runState ->
            runState.enableCompactLogs(
                "grapheme-cluster",
                totalCases(),
                divergenceClassificationNames(),
                divergenceClassificationStatuses()
            )
            SweepWorkers.run(
                sweepOptions.threads,
                "grapheme-cluster-sweep-",
                SweepWorkers.Worker { workerIndex: Int ->
                    val workerSummary =
                        newGraphemeDivergenceSummary()
                    val worker = SweepState(runState, workerSummary, workerIndex)
                    worker.run()
                    worker.finish()
                    workerSummaries.set(workerIndex, workerSummary)
                })
            val summary = newGraphemeDivergenceSummary()
            for (workerSummary in workerSummaries) {
                summary.merge(workerSummary)
            }
            return GraphemeRunResult(
                runState.checked.sum(), runState.generated, runState.divergences.sum(), summary
            )
        }
    }

    @Throws(IOException::class)
    private fun runReplay(options: GraphemeSweepOptions) {
        val sweepOptions = options.sweep
        val summary = newGraphemeDivergenceSummary()
        val replayCaseIndex = AtomicLong()
        Files.newBufferedReader(sweepOptions.replayFile, StandardCharsets.UTF_8).use { reader ->
            SweepRunState(sweepOptions, 0).use { runState ->
                val generated =
                    SweepWorkers.runStreamingLines(
                        sweepOptions.threads,
                        "grapheme-cluster-replay-",
                        reader,
                        LineWorker { line: String? ->
                            runState.checked.increment()
                            evaluateCase(
                                runState, summary, replayCase(line), replayCaseIndex.getAndIncrement(), -1
                            )
                        })
                runState.recordGenerated(generated)
                summary.writeReports(sweepOptions.outputDir)
                println("checked=" + runState.checked.sum())
                println("generated=" + runState.generated)
                println("divergences=" + runState.divergences.sum())
                println("actionableDivergences=" + summary.actionableCount())
                println("unknownDivergences=" + summary.count(DivergenceClass.UNKNOWN))
                println("threads=" + sweepOptions.threads)
                println("jsonl=" + sweepOptions.jsonlPath())
                check(summary.actionableCount() <= 0) { "replay found " + summary.actionableCount() + " actionable behavioral divergences" }
            }
        }
    }

    fun totalCases(): Long {
        return cartesianCases() + TARGETED_REGION_CASES.size
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
        val cartesianCases = cartesianCases()
        if (index >= cartesianCases) {
            return TARGETED_REGION_CASES.get(Math.toIntExact(index - cartesianCases))
        }
        val operationIndex = (index % OPERATION_MODES.size).toInt()
        index /= OPERATION_MODES.size.toLong()
        val boundsIndex = (index % BOUNDS_MODES.size).toInt()
        index /= BOUNDS_MODES.size.toLong()
        val regionIndex = (index % REGION_MODES.size).toInt()
        index /= REGION_MODES.size.toLong()
        val inputIndex = index % INPUT_CASES
        index /= INPUT_CASES
        val regexIndex = index.toInt()
        return CaseSpec(
            REGEX_TEMPLATES.get(regexIndex),
            INPUT_SPACE.inputAt(inputIndex),
            REGION_MODES.get(regionIndex),
            BOUNDS_MODES.get(boundsIndex),
            OPERATION_MODES.get(operationIndex)
        )
    }

    private fun cartesianCases(): Long {
        return (REGEX_TEMPLATES.size.toLong() * INPUT_CASES
                * REGION_MODES.size
                * BOUNDS_MODES.size
                * OPERATION_MODES.size)
    }

    private fun jdkOutcome(spec: CaseSpec): Outcome {
        try {
            val pattern =
                JDK_PATTERN_CACHE.computeIfAbsent(spec.regex()) { regex: String? -> Pattern.compile(regex) }
            return Outcome(true, operationTrace(pattern, spec), "")
        } catch (e: PatternSyntaxException) {
            return Outcome(false, "", e.getDescription())
        } catch (e: RuntimeException) {
            return Outcome(false, "", e.javaClass.getSimpleName() + ": " + e.message)
        }
    }

    private fun safeReOutcome(spec: CaseSpec): Outcome {
        try {
            val pattern =
                SAFERE_PATTERN_CACHE.computeIfAbsent(spec.regex()) { regex: String? -> org.safere.Pattern.compile(regex) }
            return Outcome(true, operationTrace(pattern, spec), "")
        } catch (e: PatternSyntaxException) {
            return Outcome(false, "", e.getDescription())
        } catch (e: RuntimeException) {
            return Outcome(false, "", e.javaClass.getSimpleName() + ": " + e.message)
        }
    }

    private fun operationTrace(pattern: Pattern?, spec: CaseSpec): String? {
        return spec.operationMode!!.jdkTrace!!.trace(pattern, spec)
    }

    private fun operationTrace(pattern: org.safere.Pattern?, spec: CaseSpec): String? {
        return spec.operationMode!!.safeReTrace!!.trace(pattern, spec)
    }

    private fun freshTrace(pattern: Pattern, spec: CaseSpec): String {
        val result = StringBuilder()
        val text = spec.text()
        val start = spec.regionStart()
        val end = spec.regionEnd()
        var matcher = configure(pattern.matcher(text).region(start, end), spec)
        result.append("matches=").append(matchResult(matcher.matches(), matcher))
        matcher = configure(matcher.reset(text).region(start, end), spec)
        result.append(";lookingAt=").append(matchResult(matcher.lookingAt(), matcher))
        matcher = configure(matcher.reset(text).region(start, end), spec)
        result.append(";find=")
        appendFindTrace(result, matcher)
        return result.toString()
    }

    private fun freshTrace(pattern: org.safere.Pattern, spec: CaseSpec): String {
        val result = StringBuilder()
        val text = spec.text()
        val start = spec.regionStart()
        val end = spec.regionEnd()
        var matcher = configure(pattern.matcher(text).region(start, end), spec)
        result.append("matches=").append(matchResult(matcher.matches(), matcher))
        matcher = configure(matcher.reset(text).region(start, end), spec)
        result.append(";lookingAt=").append(matchResult(matcher.lookingAt(), matcher))
        matcher = configure(matcher.reset(text).region(start, end), spec)
        result.append(";find=")
        appendFindTrace(result, matcher)
        return result.toString()
    }

    private fun resetRegionReuseTrace(pattern: Pattern, spec: CaseSpec): String {
        var matcher =
            configure(pattern.matcher(spec.text()).region(spec.regionStart(), spec.regionEnd()), spec)
        val first = findTrace(matcher)
        matcher =
            configure(matcher.reset(spec.text()).region(spec.regionStart(), spec.regionEnd()), spec)
        return "firstFind=" + first + ";afterResetFind=" + findTrace(matcher)
    }

    private fun resetRegionReuseTrace(pattern: org.safere.Pattern, spec: CaseSpec): String {
        var matcher =
            configure(pattern.matcher(spec.text()).region(spec.regionStart(), spec.regionEnd()), spec)
        val first = findTrace(matcher)
        matcher =
            configure(matcher.reset(spec.text()).region(spec.regionStart(), spec.regionEnd()), spec)
        return "firstFind=" + first + ";afterResetFind=" + findTrace(matcher)
    }

    private fun configure(matcher: Matcher, spec: CaseSpec): Matcher {
        return matcher
            .useTransparentBounds(spec.boundsMode!!.transparentBounds)
            .useAnchoringBounds(spec.boundsMode.anchoringBounds)
    }

    private fun configure(matcher: org.safere.Matcher, spec: CaseSpec): org.safere.Matcher {
        return matcher
            .useTransparentBounds(spec.boundsMode!!.transparentBounds)
            .useAnchoringBounds(spec.boundsMode.anchoringBounds)
    }

    private fun matchResult(matched: Boolean, matcher: Matcher): String {
        if (!matched) {
            return "false"
        }
        return "true@" + matchSpan(matcher)
    }

    private fun matchResult(matched: Boolean, matcher: org.safere.Matcher): String {
        if (!matched) {
            return "false"
        }
        return "true@" + matchSpan(matcher)
    }

    private fun appendFindTrace(result: StringBuilder, matcher: Matcher) {
        var count = 0
        while (matcher.find()) {
            if (count > 0) {
                result.append('|')
            }
            result.append(matchSpan(matcher))
            if (++count >= FIND_LIMIT) {
                result.append("|...")
                return
            }
        }
        if (count == 0) {
            result.append("false")
        }
    }

    private fun findTrace(matcher: Matcher): String {
        val result = StringBuilder()
        appendFindTrace(result, matcher)
        return result.toString()
    }

    private fun appendFindTrace(result: StringBuilder, matcher: org.safere.Matcher) {
        var count = 0
        while (matcher.find()) {
            if (count > 0) {
                result.append('|')
            }
            result.append(matchSpan(matcher))
            if (++count >= FIND_LIMIT) {
                result.append("|...")
                return
            }
        }
        if (count == 0) {
            result.append("false")
        }
    }

    private fun findTrace(matcher: org.safere.Matcher): String {
        val result = StringBuilder()
        appendFindTrace(result, matcher)
        return result.toString()
    }

    private fun matchSpan(matcher: Matcher): String {
        return matchSpan(
            matcher.start(),
            matcher.end(),
            matcher.groupCount(),
            GroupValue { group: Int -> matcher.group(group) })
    }

    private fun matchSpan(matcher: org.safere.Matcher): String {
        return matchSpan(
            matcher.start(),
            matcher.end(),
            matcher.groupCount(),
            GroupValue { group: Int -> matcher.group(group) })
    }

    private fun matchSpan(start: Int, end: Int, groupCount: Int, groupValue: GroupValue): String {
        val result = StringBuilder()
        result.append(start).append('-').append(end).append(':')
            .append(GraphemeClusterDivergenceSweep.escape(groupValue.group(0)!!))
        for (i in 1..groupCount) {
            result.append(':')
            val group = groupValue.group(i)
            result.append(if (group == null) "<null>" else escape(group))
        }
        return result.toString()
    }

    private fun replayCase(line: String?): CaseSpec {
        val `object` = SweepJson.parseObject(line)
        val caseObject = SweepJson.`object`(`object`, "case")
        val regex = SweepJson.string(caseObject, "regex")
        val regexLabel = SweepJson.string(caseObject, "regexLabel")
        val input = SweepJson.string(caseObject, "input")
        val inputLabel = SweepJson.string(caseObject, "inputLabel")
        val regionLabel = SweepJson.string(caseObject, "region")
        val prefix = SweepJson.string(caseObject, "prefix")
        val suffix = SweepJson.string(caseObject, "suffix")
        val regionStart = SweepJson.integer(caseObject, "regionStart")
        val regionEnd = SweepJson.integer(caseObject, "regionEnd")
        return CaseSpec(
            RegexTemplate(regexLabel, regex),
            InputTemplate(inputLabel, input),
            RegionMode(regionLabel, prefix, suffix, 0, 0, regionStart, regionEnd),
            replayBoundsMode(SweepJson.string(caseObject, "bounds")),
            operationMode(SweepJson.string(caseObject, "operation"))
        )
    }

    private fun bucketFor(spec: CaseSpec, jdk: Outcome, safere: Outcome): String {
        val kind = if (jdk.accepted != safere.accepted) "compile" else "behavior"
        return String.join(
            "|",
            "kind=" + kind,
            "direction=" + direction(jdk, safere),
            "regex=" + spec.regexTemplate!!.label,
            "input=" + spec.inputTemplate!!.label,
            "region=" + spec.regionMode!!.label,
            "bounds=" + spec.boundsMode!!.label,
            "operation=" + spec.operationMode!!.label
        )
    }

    private fun direction(jdk: Outcome, safere: Outcome): kotlin.String {
        if (jdk.accepted && !safere.accepted) {
            return "jdk-accepts"
        }
        if (!jdk.accepted && safere.accepted) {
            return "safere-accepts"
        }
        return "trace"
    }

    private fun semanticallyEqual(left: Outcome, right: Outcome): Boolean {
        if (left.accepted != right.accepted) {
            return false
        }
        return !left.accepted || left.trace == right.trace
    }

    private fun classifyDivergence(spec: CaseSpec): DivergenceClass {
        val regexLabel = spec.regexTemplate!!.label
        if (isNonAnchoringDollarRegionEnd(spec)) {
            return DivergenceClass.NON_ANCHORING_DOLLAR_REGION_END
        }
        if (isGraphemeCandidateStartDedup(spec)) {
            return DivergenceClass.GRAPHEME_CANDIDATE_START_DEDUP
        }
        if (regexLabel == "boundaryClusterBoundary"
            || regexLabel == "anchoredBoundaryClusterBoundary"
        ) {
            return DivergenceClass.BOUNDARY_CLUSTER_BOUNDARY_COMPOSITION
        }
        if (regexLabel.contains("BoundaryOnlyAlternative")
            || regexLabel.contains("boundaryOnlyAlternative")
        ) {
            return DivergenceClass.BOUNDARY_ONLY_ALTERNATIVE_FIND_CURSOR
        }
        if (regexLabel.contains("BoundaryClusterAlternative")
            || regexLabel.contains("boundaryClusterAlternative")
        ) {
            return DivergenceClass.BOUNDARY_CLUSTER_ALTERNATIVE_FIND_CURSOR
        }
        if (regexLabel == "clusterThenBoundary"
            || regexLabel == "anchoredClusterThenBoundary"
        ) {
            return DivergenceClass.REGION_LOCAL_CONTINUATION_CLUSTER
        }
        if (regexLabel == "boundary" || regexLabel == "capturedBoundary") {
            return DivergenceClass.TRANSPARENT_BOUNDARY_JDK_DETAIL
        }
        return DivergenceClass.UNKNOWN
    }

    private fun isNonAnchoringDollarRegionEnd(spec: CaseSpec): Boolean {
        return !spec.boundsMode!!.anchoringBounds && spec.regex()!!.endsWith("$")
                && spec.regionEnd() < spec.text().length
    }

    private fun isGraphemeCandidateStartDedup(spec: CaseSpec): Boolean {
        return GRAPHEME_CANDIDATE_START_REGEX_LABELS.contains(spec.regexTemplate!!.label)
                && spec.inputTemplate!!.label!!.startsWith("indicConjunctLinkerZwj")
    }

    private fun escape(value: kotlin.String): kotlin.String {
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
                    if (Character.isISOControl(c) || Character.isSurrogate(c)) {
                        result.append(kotlin.String.format(Locale.ROOT, "\\u%04X", c.code))
                    } else {
                        result.append(c)
                    }
                }
            }
        }
        return result.toString()
    }

    private fun regex(label: kotlin.String, regex: kotlin.String?): RegexTemplate {
        return RegexTemplate(label, regex)
    }

    private fun input(label: kotlin.String?, input: kotlin.String): InputTemplate {
        return InputTemplate(label, input)
    }

    private fun atom(label: kotlin.String?, input: kotlin.String?): GraphemeAtom {
        return GraphemeAtom(label, input)
    }

    private fun region(
        label: kotlin.String?, prefix: kotlin.String?, suffix: kotlin.String?, startAdjustment: Int, endAdjustment: Int
    ): RegionMode {
        return RegionMode(label, prefix, suffix, startAdjustment, endAdjustment, null, null)
    }

    private fun fixedRegion(
        label: kotlin.String?, prefix: kotlin.String?, suffix: kotlin.String?, regionStart: Int, regionEnd: Int
    ): RegionMode {
        return RegionMode(label, prefix, suffix, 0, 0, regionStart, regionEnd)
    }

    private fun bounds(
        label: kotlin.String?, transparentBounds: Boolean, anchoringBounds: Boolean
    ): BoundsMode {
        return BoundsMode(label, transparentBounds, anchoringBounds)
    }

    private fun operation(label: kotlin.String?, jdkTrace: JdkTrace?, safeReTrace: SafeReTrace?): OperationMode {
        return OperationMode(label, jdkTrace, safeReTrace)
    }

    private fun targetedRegion(
        label: kotlin.String?, text: kotlin.String?, regionStart: Int, regionEnd: Int
    ): TargetedRegionInput {
        return TargetedRegionInput(label, text, regionStart, regionEnd)
    }

    private fun buildTargetedRegionCases(): MutableList<CaseSpec> {
        val cases: MutableList<CaseSpec?> = ArrayList<CaseSpec?>()
        for (regex in REGEX_TEMPLATES) {
            for (input in TARGETED_REGION_INPUTS) {
                val inputTemplate = GraphemeClusterDivergenceSweep.input(input.label, input.text!!)
                val region =
                    fixedRegion(input.label, "", "", input.regionStart, input.regionEnd)
                for (bounds in BOUNDS_MODES) {
                    for (operation in OPERATION_MODES) {
                        cases.add(CaseSpec(regex, inputTemplate, region, bounds, operation))
                    }
                }
            }
        }
        return List.copyOf<CaseSpec?>(cases)
    }

    private fun boundsMode(label: kotlin.String?): BoundsMode {
        return BOUNDS_MODES.stream()
            .filter { mode: BoundsMode? -> mode!!.label == label }
            .findFirst()
            .orElseThrow<IllegalArgumentException?>(Supplier { IllegalArgumentException("unknown bounds mode: " + label) })
    }

    private fun replayBoundsMode(label: kotlin.String): BoundsMode {
        return when (label) {
            "transparentAnchoring" -> bounds("transparentAnchoring", true, true)
            "transparentNonAnchoring" -> bounds("transparentNonAnchoring", true, false)
            else -> boundsMode(label)
        }
    }

    private fun operationMode(label: kotlin.String?): OperationMode {
        return OPERATION_MODES.stream()
            .filter { mode: OperationMode? -> mode!!.label == label }
            .findFirst()
            .orElseThrow<IllegalArgumentException?>(Supplier { IllegalArgumentException("unknown operation mode: " + label) })
    }

    private fun buildInputSpace(): InputSpace {
        val families: MutableList<InputFamily> = ArrayList<InputFamily>()
        families.add(ExplicitInputFamily("curated", buildCuratedInputs()))
        for (length in 1..4) {
            families.add(
                SequenceInputFamily("allGcbClassLength" + length, GRAPHEME_CLASS_ATOMS, length)
            )
        }
        families.add(
            SequenceInputFamily("highRiskGcbClassLength5", HIGH_RISK_GRAPHEME_CLASS_ATOMS, 5)
        )
        families.add(
            SequenceInputFamily(
                "highRiskLongGcbClassLength6", HIGH_RISK_LONG_GRAPHEME_CLASS_ATOMS, 6
            )
        )
        families.add(ExplicitInputFamily("targetedLong", buildTargetedLongInputs()))
        families.add(
            ExplicitInputFamily("indicConjunctLinkerZwj", buildIndicConjunctLinkerZwjInputs())
        )
        return InputSpace(families)
    }

    private fun buildCuratedInputs(): MutableList<InputTemplate?> {
        val inputs: MutableMap<kotlin.String?, InputTemplate?> = LinkedHashMap<kotlin.String?, InputTemplate?>()
        addInput(inputs, input("empty", ""))
        addInput(inputs, input("ascii", "a"))
        addInput(inputs, input("twoAscii", "ab"))
        addInput(inputs, input("baseMark", "a\u0300"))
        addInput(inputs, input("baseExtend", "e\u0301"))
        addInput(inputs, input("baseExtendAscii", "e\u0301a"))
        addInput(inputs, input("leadingExtend", "\u0301"))
        addInput(inputs, input("twoLeadingExtends", "\u0301\u0301"))
        addInput(inputs, input("leadingExtendsThenBase", "\u0301\u0301a"))
        addInput(inputs, input("longLeadingExtendsThenBase", "\u0301".repeat(44) + "a".repeat(8)))
        addInput(inputs, input("crlf", "\r\n"))
        addInput(inputs, input("prependBase", "\u0600a"))
        addInput(inputs, input("hangulJamo", "\u1100\u1161"))
        addInput(inputs, input("hangulSyllableTail", "\uAC00\u11A8"))
        addInput(inputs, input("regionalPair", "\uD83C\uDDFA\uD83C\uDDF8"))
        addInput(inputs, input("regionalTriple", "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDE8"))
        addInput(inputs, input("regionalQuad", "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDE8\uD83C\uDDE6"))
        addInput(inputs, input("emojiModifier", "\uD83D\uDC4D\uD83C\uDFFD"))
        addInput(inputs, input("emojiModifierThenAscii", "\uD83D\uDC4D\uD83C\uDFFDa"))
        addInput(inputs, input("zwjEmoji", "\uD83D\uDC69\u200D\uD83D\uDCBB"))
        addInput(inputs, input("lowSurrogateZwj", "\uDC69\u200D\uD83D\uDCBB"))
        addInput(inputs, input("zwjEmojiModifier", "\uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDCBB"))
        addInput(inputs, input("zwjEmojiThenAscii", "\uD83D\uDC69\u200D\uD83D\uDCBBa"))
        addInput(inputs, input("hangulLeadingVowel", "\u1161\u11A8"))
        addInput(inputs, input("hangulLeadingTrail", "\u11A8\u11A8"))
        addInput(inputs, input("supplementary", "\uD83D\uDE00"))
        addInput(inputs, input("twoSupplementary", "\uD83D\uDE00\uD83D\uDE01"))
        addInput(inputs, input("zwjAfterAscii", "a\u200D"))

        val atoms: MutableList<InputTemplate> =
            List.of<InputTemplate?>(
                input("atomHighSurrogate", "\uD83D"),
                input("atomLowSurrogate", "\uDE00"),
                input("atomZwj", "\u200D"),
                input("atomCombiningMark", "\u0301"),
                input("atomEmojiModifier", "\uD83C\uDFFD"),
                input("atomExtendedPictographic", "\uD83D\uDC69"),
                input("atomRegionalIndicator", "\uD83C\uDDFA"),
                input("atomCr", "\r"),
                input("atomLf", "\n"),
                input("atomPrepend", "\u0600"),
                input("atomAscii", "a")
            )
        for (atom in atoms) {
            addInput(inputs, atom)
        }
        addInput(inputs, input("generatedHighLow", "\uD83D\uDE00"))
        addInput(inputs, input("generatedLowHigh", "\uDE00\uD83D"))
        addInput(inputs, input("generatedLowZwj", "\uDE00\u200D"))
        addInput(inputs, input("generatedZwjPictographic", "\u200D\uD83D\uDC69"))
        addInput(inputs, input("generatedPictographicZwj", "\uD83D\uDC69\u200D"))
        addInput(inputs, input("generatedAsciiMark", "a\u0301"))
        addInput(inputs, input("generatedMarkAscii", "\u0301a"))
        addInput(inputs, input("generatedCrLf", "\r\n"))
        addInput(inputs, input("generatedRegionalPair", "\uD83C\uDDFA\uD83C\uDDF8"))
        addInput(inputs, input("generatedPrependAscii", "\u0600a"))
        addInput(inputs, input("generatedLowZwjPictographic", "\uDE00\u200D\uD83D\uDC69"))
        addInput(inputs, input("generatedHighLowZwj", "\uD83D\uDE00\u200D"))
        addInput(inputs, input("generatedCrLfAscii", "\r\na"))
        addInput(inputs, input("generatedRegionalPairAscii", "\uD83C\uDDFA\uD83C\uDDF8a"))
        return List.copyOf<InputTemplate?>(inputs.values)
    }

    private fun buildTargetedLongInputs(): MutableList<InputTemplate?> {
        val inputs: MutableMap<kotlin.String?, InputTemplate?> = LinkedHashMap<kotlin.String?, InputTemplate?>()
        val suffixes: MutableList<InputTemplate> =
            List.of<InputTemplate?>(
                input("End", ""),
                input("OtherBmp", "a"),
                input("Extend", "\u0301"),
                input("ExtendedPictographic", "\uD83D\uDC69"),
                input("LowSurrogate", "\uDE00")
            )

        for (extendCount in 1..12) {
            for (zwjCount in 1..4) {
                for (suffix in suffixes) {
                    addInput(
                        inputs,
                        input(
                            "targetLeadingExtend" + extendCount + "Zwj" + zwjCount + suffix.label,
                            "\u0301".repeat(extendCount) + "\u200D".repeat(zwjCount) + suffix.input
                        )
                    )
                }
            }
        }

        for (regionalCount in 1..12) {
            val regionalIndicators = "\uD83C\uDDFA".repeat(regionalCount)
            addInput(inputs, input("targetRegionalIndicators" + regionalCount, regionalIndicators))
            addInput(
                inputs,
                input("targetRegionalIndicators" + regionalCount + "Other", regionalIndicators + "a")
            )
            addInput(
                inputs,
                input(
                    "targetRegionalIndicators" + regionalCount + "Extend",
                    regionalIndicators + "\u0301"
                )
            )
        }

        for (chainLength in 2..6) {
            addInput(inputs, input("targetEmojiZwjChain" + chainLength, emojiZwjChain(chainLength, "")))
            addInput(
                inputs,
                input(
                    "targetEmojiZwjChain" + chainLength + "Extend",
                    emojiZwjChain(chainLength, "\u0301")
                )
            )
            addInput(
                inputs,
                input(
                    "targetEmojiZwjModifierChain" + chainLength,
                    "\uD83D\uDC69\uD83C\uDFFD"
                            + "\u200D\uD83D\uDC69\uD83C\uDFFD".repeat(chainLength - 1)
                )
            )
        }

        for (leadingCount in 1..6) {
            for (vowelCount in 1..4) {
                for (trailingCount in 0..4) {
                    addInput(
                        inputs,
                        input(
                            "targetHangulL" + leadingCount + "V" + vowelCount + "T" + trailingCount,
                            ("\u1100".repeat(leadingCount)
                                    + "\u1161".repeat(vowelCount)
                                    + "\u11A8".repeat(trailingCount))
                        )
                    )
                }
            }
        }

        for (count in 1..8) {
            addInput(inputs, input("targetHighLowPairs" + count, "\uD83D\uDE00".repeat(count)))
            addInput(inputs, input("targetLowHighPairs" + count, "\uDE00\uD83D".repeat(count)))
            addInput(inputs, input("targetHighLowZwj" + count, "\uD83D\uDE00\u200D".repeat(count)))
            addInput(inputs, input("targetLowZwj" + count, "\uDE00\u200D".repeat(count)))
        }

        return List.copyOf<InputTemplate?>(inputs.values)
    }

    private fun buildIndicConjunctLinkerZwjInputs(): MutableList<InputTemplate?> {
        val inputs: MutableMap<kotlin.String?, InputTemplate?> = LinkedHashMap<kotlin.String?, InputTemplate?>()
        addIndicConjunctLinkerZwjInputs(inputs, "Devanagari", "\u0915", "\u094D")
        addIndicConjunctLinkerZwjInputs(inputs, "Bengali", "\u0995", "\u09CD")
        addIndicConjunctLinkerZwjInputs(inputs, "Gujarati", "\u0A95", "\u0ACD")
        addIndicConjunctLinkerZwjInputs(inputs, "Odia", "\u0B15", "\u0B4D")
        addIndicConjunctLinkerZwjInputs(inputs, "Telugu", "\u0C15", "\u0C4D")
        addIndicConjunctLinkerZwjInputs(inputs, "Malayalam", "\u0D15", "\u0D4D")
        return List.copyOf<InputTemplate?>(inputs.values)
    }

    private fun addIndicConjunctLinkerZwjInputs(
        inputs: MutableMap<kotlin.String?, InputTemplate?>,
        script: kotlin.String,
        consonant: kotlin.String?,
        linker: kotlin.String
    ) {
        val prefix = "indicConjunctLinkerZwj" + script
        val conjunct = consonant + linker + "\u200D" + consonant
        addInput(inputs, input(prefix, conjunct))
        addInput(inputs, input(prefix + "Extend", conjunct + "\u0301"))
        addInput(inputs, input(prefix + "Ascii", conjunct + "a"))
        addInput(inputs, input(prefix + "Chain", conjunct + linker + "\u200D" + consonant))
        addInput(inputs, input(prefix + "LeadingLinker", linker + "\u200D" + consonant))
        addInput(inputs, input(prefix + "TrailingLinker", consonant + linker + "\u200D"))
        addInput(
            inputs, input(prefix + "LinkerExtendZwj", consonant + linker + "\u0301\u200D" + consonant)
        )
        addInput(
            inputs, input(prefix + "LinkerZwjExtend", consonant + linker + "\u200D\u0301" + consonant)
        )
    }

    private fun emojiZwjChain(chainLength: Int, suffixAfterEachPictographic: kotlin.String?): kotlin.String {
        val result = StringBuilder("\uD83D\uDC69").append(suffixAfterEachPictographic)
        for (i in 1..<chainLength) {
            result.append('\u200D').append("\uD83D\uDC69").append(suffixAfterEachPictographic)
        }
        return result.toString()
    }

    private fun addInput(inputs: MutableMap<kotlin.String?, InputTemplate?>, input: InputTemplate) {
        inputs.putIfAbsent(input.label, input)
    }

    private fun newGraphemeDivergenceSummary(): ClassifiedDivergenceSummary<DivergenceClass?> {
        return ClassifiedDivergenceSummary<DivergenceClass?>(
            DivergenceClass::class.java,
            DivergenceClass.UNKNOWN,
            "grapheme-cluster",
            FIRST_UNKNOWN_LIMIT,
            ACTIONABLE_SAMPLE_LIMIT
        )
    }

    fun divergenceClassificationNames(): MutableList<kotlin.String?> {
        return Arrays.stream<DivergenceClass?>(DivergenceClass.entries.toTypedArray())
            .map<kotlin.String?> { obj: DivergenceClass? -> obj!!.name }.toList()
    }

    fun divergenceClassificationStatuses(): MutableList<DivergenceStatus?> {
        return Arrays.stream<DivergenceClass?>(DivergenceClass.entries.toTypedArray())
            .map<DivergenceStatus?> { obj: DivergenceClass? -> obj!!.status() }.toList()
    }

    private fun classificationId(classification: DivergenceClass): Int {
        return when (classification) {
            DivergenceClass.BOUNDARY_CLUSTER_BOUNDARY_COMPOSITION -> 0
            DivergenceClass.BOUNDARY_ONLY_ALTERNATIVE_FIND_CURSOR -> 1
            DivergenceClass.BOUNDARY_CLUSTER_ALTERNATIVE_FIND_CURSOR -> 2
            DivergenceClass.REGION_LOCAL_CONTINUATION_CLUSTER -> 3
            DivergenceClass.TRANSPARENT_BOUNDARY_JDK_DETAIL -> 4
            DivergenceClass.NON_ANCHORING_DOLLAR_REGION_END -> 5
            DivergenceClass.GRAPHEME_CANDIDATE_START_DEDUP -> 6
            DivergenceClass.UNKNOWN -> 7
        }
    }

    private fun evaluateCase(
        runState: SweepRunState,
        summary: ClassifiedDivergenceSummary<DivergenceClass?>,
        spec: CaseSpec,
        caseIndex: Long,
        workerIndex: Int
    ) {
        val jdk = jdkOutcome(spec)
        val safere = safeReOutcome(spec)
        if (semanticallyEqual(jdk, safere)) {
            return
        }
        val bucketName = bucketFor(spec, jdk, safere)
        val classification = classifyDivergence(spec)
        val divergence =
            Divergence(caseIndex, spec, jdk, safere, bucketName, classification)
        if (workerIndex >= 0) {
            runState.recordCompactDivergence(workerIndex, caseIndex, classificationId(classification))
            summary.record(classification, caseIndex, null)
            return
        }
        runState.recordDivergence()
        val json = divergence.toJson()
        summary.record(classification, caseIndex, json)
        if (classification.actionable()) {
            runState.appendJsonl(json)
        }
    }

    private interface GroupValue {
        fun group(group: Int): kotlin.String?
    }

    private interface JdkTrace {
        fun trace(pattern: Pattern?, spec: CaseSpec?): kotlin.String?
    }

    private interface SafeReTrace {
        fun trace(pattern: org.safere.Pattern?, spec: CaseSpec?): kotlin.String?
    }

    @JvmRecord
    private data class RegexTemplate(val label: kotlin.String, val regex: kotlin.String?)

    @JvmRecord
    private data class GraphemeAtom(val label: kotlin.String?, val input: kotlin.String?)

    @JvmRecord
    private data class InputTemplate(val label: kotlin.String?, val input: kotlin.String)

    @JvmRecord
    private data class TargetedRegionInput(
        val label: kotlin.String?,
        val text: kotlin.String?,
        val regionStart: Int,
        val regionEnd: Int
    )

    private interface InputFamily {
        fun label(): kotlin.String?

        fun size(): Long

        fun inputAt(index: Long): InputTemplate?
    }

    @JvmRecord
    private data class InputSpace(val families: MutableList<InputFamily>?) {
        fun size(): Long {
            var total: Long = 0
            for (family in families!!) {
                total = Math.addExact(total, family.size())
            }
            return total
        }

        fun inputAt(index: Long): InputTemplate? {
            var remaining = index
            for (family in families!!) {
                if (remaining < family.size()) {
                    return family.inputAt(remaining)
                }
                remaining -= family.size()
            }
            throw IndexOutOfBoundsException("input index " + index + " >= " + size())
        }

        fun familySummary(): kotlin.String {
            val result = StringBuilder()
            for (i in families!!.indices) {
                if (i > 0) {
                    result.append(',')
                }
                val family = families.get(i)
                result.append(family.label()).append('=').append(family.size())
            }
            return result.toString()
        }
    }

    @JvmRecord
    private data class ExplicitInputFamily(val label: kotlin.String?, val inputs: MutableList<InputTemplate?>?) :
        InputFamily {
        override fun size(): Long {
            return inputs!!.size.toLong()
        }

        override fun inputAt(index: Long): InputTemplate? {
            return inputs!!.get(Math.toIntExact(index))
        }
    }

    @JvmRecord
    private data class SequenceInputFamily(
        val label: kotlin.String?,
        val atoms: MutableList<GraphemeAtom>?,
        val length: Int
    ) : InputFamily {
        override fun size(): Long {
            return pow(atoms!!.size, length)
        }

        override fun inputAt(index: Long): InputTemplate {
            var remaining = index
            val atomLabels = arrayOfNulls<kotlin.String>(length)
            val atomInputs = arrayOfNulls<kotlin.String>(length)
            for (i in length - 1 downTo 0) {
                val atomIndex = (remaining % atoms!!.size).toInt()
                remaining /= atoms.size.toLong()
                val atom = atoms.get(atomIndex)
                atomLabels[i] = atom.label
                atomInputs[i] = atom.input
            }

            val input = StringBuilder()
            val inputLabel = StringBuilder(label).append('[')
            for (i in 0..<length) {
                if (i > 0) {
                    inputLabel.append('+')
                }
                inputLabel.append(atomLabels[i])
                input.append(atomInputs[i])
            }
            inputLabel.append(']')
            return InputTemplate(inputLabel.toString(), input.toString())
        }

        companion object {
            private fun pow(base: Int, exponent: Int): Long {
                var result: Long = 1
                for (i in 0..<exponent) {
                    result = Math.multiplyExact(result, base)
                }
                return result
            }
        }
    }

    @JvmRecord
    private data class RegionMode(
        val label: kotlin.String?,
        val prefix: kotlin.String?,
        val suffix: kotlin.String?,
        val startAdjustment: Int,
        val endAdjustment: Int,
        val fixedStart: Int?,
        val fixedEnd: Int?
    )

    @JvmRecord
    private data class BoundsMode(
        val label: kotlin.String?,
        val transparentBounds: Boolean,
        val anchoringBounds: Boolean
    )

    @JvmRecord
    private data class OperationMode(val label: kotlin.String?, val jdkTrace: JdkTrace?, val safeReTrace: SafeReTrace?)

    @JvmRecord
    private data class CaseSpec(
        val regexTemplate: RegexTemplate?,
        val inputTemplate: InputTemplate?,
        val regionMode: RegionMode?,
        val boundsMode: BoundsMode?,
        val operationMode: OperationMode?
    ) {
        fun regex(): kotlin.String? {
            return regexTemplate!!.regex
        }

        fun input(): kotlin.String {
            return inputTemplate!!.input
        }

        fun text(): kotlin.String {
            return regionMode!!.prefix + input() + regionMode.suffix
        }

        fun regionStart(): Int {
            if (regionMode!!.fixedStart != null) {
                return regionMode.fixedStart
            }
            return regionMode.prefix!!.length + regionMode.startAdjustment
        }

        fun regionEnd(): Int {
            if (regionMode!!.fixedEnd != null) {
                return regionMode.fixedEnd
            }
            return regionMode.prefix!!.length + input().length + regionMode.endAdjustment
        }

        fun labels(): kotlin.String {
            return ("regex="
                    + regexTemplate!!.label
                    + ",input="
                    + inputTemplate!!.label
                    + ",region="
                    + regionMode!!.label
                    + ",bounds="
                    + boundsMode!!.label
                    + ",operation="
                    + operationMode!!.label)
        }
    }

    @JvmRecord
    private data class Outcome(val accepted: Boolean, val trace: kotlin.String?, val error: kotlin.String?)

    @JvmRecord
    private data class GraphemeRunResult(
        val checked: Long,
        val generated: Long,
        val divergences: Long,
        val summary: ClassifiedDivergenceSummary<DivergenceClass?>?
    )

    @JvmRecord
    private data class GraphemeSweepOptions(val sweep: SweepOptions) {
        fun printStartup() {
            sweep.printStartup("grapheme-cluster")
        }
    }

    private enum class DivergenceClass(status: DivergenceStatus, rationale: kotlin.String) : DivergenceClassification {
        BOUNDARY_CLUSTER_BOUNDARY_COMPOSITION(
            DivergenceStatus.KNOWN_INTENTIONAL,
            "SafeRE composes explicit \\b{g}, primitive \\X, and trailing \\b{g};"
                    + " observed JDK repeated-find traces skip some candidate starts."
        ),
        BOUNDARY_ONLY_ALTERNATIVE_FIND_CURSOR(
            DivergenceStatus.KNOWN_INTENTIONAL,
            "Observed JDK repeated-find cursor behavior for alternatives involving a bare"
                    + " explicit \\b{g} is not part of SafeRE's compositional grapheme model."
        ),
        BOUNDARY_CLUSTER_ALTERNATIVE_FIND_CURSOR(
            DivergenceStatus.KNOWN_INTENTIONAL,
            "Observed JDK traces for alternatives involving \\b{g}\\X expose matcher cursor and"
                    + " transparent-boundary implementation details that SafeRE does not copy."
        ),
        REGION_LOCAL_CONTINUATION_CLUSTER(
            DivergenceStatus.KNOWN_INTENTIONAL,
            "SafeRE keeps opaque region-local continuation clusters compositional instead of"
                    + " copying selected JDK region-edge traces."
        ),
        TRANSPARENT_BOUNDARY_JDK_DETAIL(
            DivergenceStatus.KNOWN_INTENTIONAL,
            "Observed JDK transparent-boundary traces expose implementation details that SafeRE"
                    + " does not target when they conflict with the documented grapheme model."
        ),
        NON_ANCHORING_DOLLAR_REGION_END(
            DivergenceStatus.EXPECTED_ZERO,
            "Non-anchoring bounds require $ to use full-input trailing-line context, not the"
                    + " region-local end."
        ),
        GRAPHEME_CANDIDATE_START_DEDUP(
            DivergenceStatus.EXPECTED_ZERO,
            "Unanchored search must preserve concurrent consuming \\X candidates with different"
                    + " starts."
        ),
        UNKNOWN(DivergenceStatus.UNKNOWN, "Unclassified SafeRE/JDK grapheme divergence.");

        private val status: DivergenceStatus?
        private val rationale: kotlin.String?

        init {
            this.status = status
            this.rationale = rationale
        }

        override fun status(): DivergenceStatus? {
            return status
        }

        override fun rationale(): kotlin.String? {
            return rationale
        }
    }

    @JvmRecord
    private data class Divergence(
        val caseIndex: Long,
        val spec: CaseSpec?,
        val jdk: Outcome?,
        val safere: Outcome?,
        val bucket: kotlin.String?,
        val classification: DivergenceClass?
    ) {
        fun toJson(): kotlin.String {
            val `object` = SweepJson.`object`()
            `object`.addProperty("caseIndex", caseIndex)
            `object`.add("case", Companion.caseJson(spec!!))
            `object`.addProperty("bucket", bucket)
            `object`.addProperty("classification", classification!!.name)
            `object`.addProperty("classificationStatus", classification.status()!!.name)
            `object`.addProperty("labels", spec.labels())
            `object`.addProperty("regex", spec.regex())
            `object`.addProperty("input", spec.input())
            `object`.addProperty("prefix", spec.regionMode!!.prefix)
            `object`.addProperty("suffix", spec.regionMode.suffix)
            `object`.addProperty("bounds", spec.boundsMode!!.label)
            `object`.addProperty("operation", spec.operationMode!!.label)
            `object`.addProperty("regionStart", spec.regionStart())
            `object`.addProperty("regionEnd", spec.regionEnd())
            `object`.addProperty("jdkAccepted", jdk!!.accepted)
            `object`.addProperty("safeReAccepted", safere!!.accepted)
            `object`.addProperty("jdkTrace", jdk.trace)
            `object`.addProperty("safeReTrace", safere.trace)
            `object`.addProperty("jdkError", jdk.error)
            `object`.addProperty("safeReError", safere.error)
            return SweepJson.toJson(`object`)
        }

        companion object {
            private fun caseJson(spec: CaseSpec): JsonObject {
                val `object` = SweepJson.`object`()
                `object`.addProperty("regexLabel", spec.regexTemplate!!.label)
                `object`.addProperty("regex", spec.regex())
                `object`.addProperty("inputLabel", spec.inputTemplate!!.label)
                `object`.addProperty("input", spec.input())
                `object`.addProperty("region", spec.regionMode!!.label)
                `object`.addProperty("prefix", spec.regionMode.prefix)
                `object`.addProperty("suffix", spec.regionMode.suffix)
                `object`.addProperty("regionStart", spec.regionStart())
                `object`.addProperty("regionEnd", spec.regionEnd())
                `object`.addProperty("bounds", spec.boundsMode!!.label)
                `object`.addProperty("operation", spec.operationMode!!.label)
                return `object`
            }
        }
    }

    private class SweepState(
        runState: SweepRunState,
        summary: ClassifiedDivergenceSummary<DivergenceClass?>,
        workerIndex: Int
    ) {
        val runState: SweepRunState?
        val summary: ClassifiedDivergenceSummary<DivergenceClass?>
        val options: SweepOptions
        val progressReporter: ProgressReporter
        val workerIndex: Int
        var generated: Long = 0

        init {
            this.runState = runState
            this.summary = summary
            this.options = runState.options
            this.progressReporter = ProgressReporter(runState, workerIndex)
            this.workerIndex = workerIndex
        }

        fun run() {
            val end = min(options.rangeEndExclusive, totalCases())
            generated = firstOwnedCaseIndex()
            while (generated < end) {
                val caseIndex = generated
                generated += options.threads.toLong()
                progressReporter.checked()
                evaluateCase(caseIndex, caseAt(caseIndex))
            }
        }

        fun firstOwnedCaseIndex(): Long {
            return SweepWorkers.firstOwnedCaseIndex(
                options.rangeStartInclusive, endExclusive(), options.threads, workerIndex
            )
        }

        fun evaluateCase(caseIndex: Long, spec: CaseSpec) {
            GraphemeClusterDivergenceSweep.evaluateCase(runState!!, summary, spec, caseIndex, workerIndex)
            reportProgressIfNeeded()
        }

        fun finish() {
            runState!!.recordGenerated(min(generated, endExclusive()))
            runState.updateWorkerNextCaseIndex(workerIndex, min(generated, endExclusive()))
        }

        fun reportProgressIfNeeded() {
            progressReporter.reportIfNeeded(min(generated, endExclusive()))
        }

        fun endExclusive(): Long {
            return min(options.rangeEndExclusive, totalCases())
        }
    }
}
