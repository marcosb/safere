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
import java.util.List
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.math.min

/** Offline differential sweep for zero-width and nullable patterns under matcher regions.  */
object RegionZeroWidthDivergenceSweep {
    private const val FIND_LIMIT = 8
    private val DIVERGENCE_CLASSES: MutableList<DivergenceClass?> = List.of<DivergenceClass?>(
        DivergenceClass.ASCII_WORD_BOUNDARY_COMBINING_MARK,
        DivergenceClass.OPAQUE_REGION_CRLF_PAIR_CONTEXT,
        DivergenceClass.BOUNDARY_ANY_CLASS_SPLIT_SURROGATE_SCALAR_COMPOSITION,
        DivergenceClass.NON_WORD_BOUNDARY_SPLIT_SURROGATE_INTERIOR_POSITION,
        DivergenceClass.UNKNOWN
    )

    private val REGEXES: MutableList<RegexCase> = List.of<RegexCase?>(
        regex("empty", ""),
        regex("emptyAlternative", "|a"),
        regex("literalAlternativeEmpty", "a|"),
        regex("startAnchor", "^"),
        regex("endAnchor", "$"),
        regex("anchoredEmpty", "^$"),
        regex("beginText", "\\A"),
        regex("absoluteEnd", "\\z"),
        regex("finalEnd", "\\Z"),
        regex("wordBoundary", "\\b"),
        regex("nonWordBoundary", "\\B"),
        regex("endOrWordBoundary", "$|\\b"),
        regex("wordBoundaryOrEnd", "\\b|$"),
        regex("nonWordBoundaryOrAsciiA", "\\B|a"),
        regex("nonWordBoundaryOrAsciiY", "\\B|y"),
        regex("asciiAOrNonWordBoundary", "a|\\B"),
        regex("asciiYOrNonWordBoundary", "y|\\B"),
        regex("wordBoundaryOrAsciiA", "\\b|a"),
        regex("wordBoundaryOrAsciiY", "\\b|y"),
        regex("asciiAOrWordBoundary", "a|\\b"),
        regex("asciiYOrWordBoundary", "y|\\b"),
        regex("nonWordBoundaryThenDot", "\\B."),
        regex("wordBoundaryThenDot", "\\b."),
        regex("nonWordBoundaryThenAnyClass", "\\B[\\s\\S]"),
        regex("wordBoundaryThenAnyClass", "\\b[\\s\\S]"),
        regex("nonWordBoundaryDotOrAsciiY", "\\B.|y"),
        regex("asciiYOrNonWordBoundaryDot", "y|\\B."),
        regex("nonWordBoundaryAnyClassOrAsciiY", "\\B[\\s\\S]|y"),
        regex("asciiYOrNonWordBoundaryAnyClass", "y|\\B[\\s\\S]"),
        regex("nullableLiteralStar", "a*"),
        regex("nullableLiteralQuestion", "a?"),
        regex("capturedEmpty", "()"),
        regex("capturedNullableLiteral", "(a*)")
    )

    private val FLAG_MODES: MutableList<FlagMode> = List.of<FlagMode?>(
        flags("none", 0),
        flags("multiline", Pattern.MULTILINE),
        flags("unixLines", Pattern.UNIX_LINES),
        flags("dotall", Pattern.DOTALL),
        flags("unicodeCharacterClass", Pattern.UNICODE_CHARACTER_CLASS),
        flags(
            "unicodeCharacterClassMultiline",
            Pattern.UNICODE_CHARACTER_CLASS or Pattern.MULTILINE
        )
    )

    private val BOUNDS_MODES: MutableList<BoundsMode> = List.of<BoundsMode?>(
        bounds("opaqueAnchoring", false, true),
        bounds("opaqueNonAnchoring", false, false),
        bounds("transparentAnchoring", true, true),
        bounds("transparentNonAnchoring", true, false)
    )

    private val TEXT_REGIONS: MutableList<TextRegion> = buildTextRegions()

    @Throws(IOException::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val options: SweepOptions =
            SweepOptions.Companion.parse(
                args,
                Path.of("target", "exhaustive-reports", "region-zero-width-sweep"),
                "region-zero-width-divergences.jsonl",
                1000000
            )
        Files.createDirectories(options.outputDir)
        if (options.replayFile != null) {
            Files.deleteIfExists(options.jsonlPath())
        }
        options.printStartup("region-zero-width")

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

    fun totalCases(): Long {
        return REGEXES.size.toLong() * FLAG_MODES.size * TEXT_REGIONS.size * BOUNDS_MODES.size
    }

    fun compactReplayJson(caseIndex: Long, classification: String?): String {
        return compactReplayJson(caseIndex, classification, caseFromIndex(caseIndex))
    }

    @JvmStatic
    fun containsGeneratedCaseForTesting(
        regex: String?, flags: Int, text: String?, start: Int, end: Int
    ): Boolean {
        return generatedCaseForTesting(regex, flags, text, start, end) != null
    }

    fun classifyDivergenceShapeForTesting(
        regex: String?, flags: Int, text: String?, start: Int, end: Int
    ): String {
        val spec: CaseSpec = generatedCaseForTesting(regex, flags, text, start, end)
        requireNotNull(spec) { "case is not generated by the region zero-width sweep" }
        return classifyDivergence(spec).name
    }

    @JvmStatic
    fun classifyDivergenceShapeForTesting(
        regex: String?,
        flags: Int,
        text: String?,
        start: Int,
        end: Int,
        transparentBounds: Boolean,
        anchoringBounds: Boolean
    ): String {
        val spec =
            generatedCaseForTesting(regex, flags, text, start, end, transparentBounds, anchoringBounds)
        requireNotNull(spec) { "case is not generated by the region zero-width sweep" }
        return classifyDivergence(spec).name
    }

    @JvmStatic
    fun semanticallyEqualForTesting(
        leftAccepted: Boolean, leftTrace: String?, rightAccepted: Boolean, rightTrace: String?
    ): Boolean {
        return semanticallyEqual(
            Outcome(leftAccepted, leftTrace, ""), Outcome(rightAccepted, rightTrace, "")
        )
    }

    private fun generatedCaseForTesting(
        regex: String?,
        flags: Int,
        text: String?,
        start: Int,
        end: Int,
        transparentBounds: Boolean = BOUNDS_MODES.getFirst().transparentBounds,
        anchoringBounds: Boolean = BOUNDS_MODES.getFirst().anchoringBounds
    ): CaseSpec {
        for (regexCase in REGEXES) {
            if (regexCase.regex != regex) {
                continue
            }
            for (flagMode in FLAG_MODES) {
                if (flagMode.flags != flags) {
                    continue
                }
                for (textRegion in TEXT_REGIONS) {
                    if (textRegion.text == text
                        && textRegion.start == start && textRegion.end == end
                    ) {
                        for (boundsMode in BOUNDS_MODES) {
                            if (boundsMode.transparentBounds == transparentBounds
                                && boundsMode.anchoringBounds == anchoringBounds
                            ) {
                                return CaseSpec(regexCase, flagMode, textRegion, boundsMode)
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    @Throws(IOException::class)
    private fun runSweep(options: SweepOptions): SweepRunState {
        SweepRunState(options, options.totalChecks(totalCases())).use { runState ->
            runState.enableCompactLogs(
                "region-zero-width",
                totalCases(),
                DIVERGENCE_CLASSES.stream().map<String?> { obj: DivergenceClass? -> obj!!.name }.toList(),
                DIVERGENCE_CLASSES.stream().map<DivergenceStatus?> { obj: DivergenceClass? -> obj!!.status() }.toList()
            )
            SweepWorkers.run(
                options.threads,
                "region-zero-width-sweep-",
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
                        "region-zero-width-replay-",
                        reader,
                        LineWorker { line: String? ->
                            runState.checked.increment()
                            evaluateCase(runState, replayCase(line), -1, -1)
                        })
                runState.recordGenerated(generated)
                println("checked=" + runState.checked.sum())
                println("generated=" + runState.generated)
                println("totalCases=" + totalCases())
                println("divergences=" + runState.divergences.sum())
                println("threads=" + options.threads)
                println("jsonl=" + options.jsonlPath())
                check(runState.divergences.sum() <= 0) { "replay found " + runState.divergences.sum() + " behavioral divergences" }
            }
        }
    }

    private fun evaluateCase(
        runState: SweepRunState, spec: CaseSpec, workerIndex: Int, caseIndex: Long
    ) {
        val jdk = jdkOutcome(spec)
        val safere = safeReOutcome(spec)
        if (semanticallyEqual(jdk, safere)) {
            return
        }
        val classification = classifyDivergence(spec)
        if (workerIndex >= 0) {
            runState.recordCompactDivergence(workerIndex, caseIndex, classificationId(classification))
            return
        }
        if (classification.status() == DivergenceStatus.KNOWN_INTENTIONAL) {
            return
        }
        runState.recordDivergence()
        runState.appendJsonl(Divergence(spec, jdk, safere, classification).toJson())
    }

    private fun classifyDivergence(spec: CaseSpec): DivergenceClass {
        if (isKnownAsciiWordBoundaryCombiningMarkDivergence(spec)) {
            return DivergenceClass.ASCII_WORD_BOUNDARY_COMBINING_MARK
        }
        if (isOpaqueRegionCrlfPairContextDivergence(spec)) {
            return DivergenceClass.OPAQUE_REGION_CRLF_PAIR_CONTEXT
        }
        if (isBoundaryAnyClassSplitSurrogateScalarCompositionDivergence(spec)) {
            return DivergenceClass.BOUNDARY_ANY_CLASS_SPLIT_SURROGATE_SCALAR_COMPOSITION
        }
        if (isNonWordBoundarySplitSurrogateInteriorPositionDivergence(spec)) {
            return DivergenceClass.NON_WORD_BOUNDARY_SPLIT_SURROGATE_INTERIOR_POSITION
        }
        return DivergenceClass.UNKNOWN
    }

    private fun isKnownAsciiWordBoundaryCombiningMarkDivergence(spec: CaseSpec): Boolean {
        return (spec.textRegion!!.label == "baseBeforeMark"
                || spec.textRegion.label == "markOnlyAfterBase")
                && spec.boundsMode!!.transparentBounds
                && when (spec.regexCase!!.label) {
            "wordBoundary", "nonWordBoundary", "endOrWordBoundary", "wordBoundaryOrEnd", "nonWordBoundaryOrAsciiA", "nonWordBoundaryOrAsciiY", "asciiAOrNonWordBoundary", "asciiYOrNonWordBoundary", "wordBoundaryOrAsciiA", "wordBoundaryOrAsciiY", "asciiAOrWordBoundary", "asciiYOrWordBoundary", "nonWordBoundaryThenDot", "wordBoundaryThenDot", "nonWordBoundaryThenAnyClass", "wordBoundaryThenAnyClass", "nonWordBoundaryDotOrAsciiY", "asciiYOrNonWordBoundaryDot", "nonWordBoundaryAnyClassOrAsciiY", "asciiYOrNonWordBoundaryAnyClass" -> true
            else -> false
        }
    }

    private fun isOpaqueRegionCrlfPairContextDivergence(spec: CaseSpec): Boolean {
        return spec.textRegion!!.label == "finalCrLfLfOnly"
                && !spec.boundsMode!!.transparentBounds && spec.boundsMode.anchoringBounds
                && when (spec.regexCase!!.label) {
            "endAnchor", "finalEnd", "anchoredEmpty", "endOrWordBoundary", "wordBoundaryOrEnd" -> true
            else -> false
        }
    }

    private fun isBoundaryAnyClassSplitSurrogateScalarCompositionDivergence(
        spec: CaseSpec
    ): Boolean {
        return spec.textRegion!!.label == "splitHighThroughAscii"
                && spec.boundsMode!!.transparentBounds
                && when (spec.regexCase!!.label) {
            "nonWordBoundaryThenAnyClass", "nonWordBoundaryAnyClassOrAsciiY", "asciiYOrNonWordBoundaryAnyClass" -> true
            else -> false
        }
    }

    private fun isNonWordBoundarySplitSurrogateInteriorPositionDivergence(spec: CaseSpec): Boolean {
        return spec.textRegion!!.label == "splitHighThroughAscii"
                && spec.boundsMode!!.transparentBounds
                && when (spec.regexCase!!.label) {
            "nonWordBoundary", "nonWordBoundaryOrAsciiA", "nonWordBoundaryOrAsciiY", "asciiAOrNonWordBoundary", "asciiYOrNonWordBoundary", "nonWordBoundaryThenDot", "nonWordBoundaryDotOrAsciiY", "asciiYOrNonWordBoundaryDot" -> true
            else -> false
        }
    }

    private fun jdkOutcome(spec: CaseSpec): Outcome {
        try {
            val pattern =
                Pattern.compile(spec.regexCase!!.regex, spec.flagMode!!.flags)
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
                org.safere.Pattern.compile(spec.regexCase!!.regex, spec.flagMode!!.flags)
            return Outcome(true, operationTrace(pattern, spec), "")
        } catch (e: PatternSyntaxException) {
            return Outcome(false, "", e.getDescription())
        } catch (e: RuntimeException) {
            return Outcome(false, "", e.javaClass.getSimpleName() + ": " + e.message)
        }
    }

    private fun operationTrace(pattern: Pattern, spec: CaseSpec): String {
        val result = StringBuilder()
        val text = spec.textRegion!!.text
        val start = spec.textRegion.start
        val end = spec.textRegion.end
        var matcher = configure(pattern.matcher(text).region(start, end), spec)
        appendTrace(result, "matches", matcher.matches(), matcher)
        matcher = configure(matcher.reset(text).region(start, end), spec)
        appendTrace(result, "lookingAt", matcher.lookingAt(), matcher)
        matcher = configure(matcher.reset(text).region(start, end), spec)
        appendFindTrace(result, "find", matcher)
        matcher = configure(matcher.reset(text).region(start, end), spec)
        appendFindTrace(result, "resetFind", matcher)
        return result.toString()
    }

    private fun operationTrace(pattern: org.safere.Pattern, spec: CaseSpec): String {
        val result = StringBuilder()
        val text = spec.textRegion!!.text
        val start = spec.textRegion.start
        val end = spec.textRegion.end
        var matcher = configure(pattern.matcher(text).region(start, end), spec)
        appendTrace(result, "matches", matcher.matches(), matcher)
        matcher = configure(matcher.reset(text).region(start, end), spec)
        appendTrace(result, "lookingAt", matcher.lookingAt(), matcher)
        matcher = configure(matcher.reset(text).region(start, end), spec)
        appendFindTrace(result, "find", matcher)
        matcher = configure(matcher.reset(text).region(start, end), spec)
        appendFindTrace(result, "resetFind", matcher)
        return result.toString()
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

    private fun appendFindTrace(
        result: StringBuilder, operation: String?, matcher: Matcher
    ) {
        for (i in 0..<FIND_LIMIT) {
            val matched = matcher.find()
            appendTrace(result, operation + i, matched, matcher)
            if (!matched) {
                return
            }
        }
    }

    private fun appendFindTrace(
        result: StringBuilder, operation: String?, matcher: org.safere.Matcher
    ) {
        for (i in 0..<FIND_LIMIT) {
            val matched = matcher.find()
            appendTrace(result, operation + i, matched, matcher)
            if (!matched) {
                return
            }
        }
    }

    private fun appendTrace(
        result: StringBuilder, operation: String?, matched: Boolean, matcher: Matcher
    ) {
        appendSeparator(result)
        result.append(operation).append('=').append(matched)
        if (matched) {
            result.append('@').append(matcher.start()).append('-').append(matcher.end())
            appendGroups(
                result,
                matcher.groupCount(),
                RegionZeroWidthDivergenceSweep.GroupInt { group: Int -> matcher.start(group) },
                RegionZeroWidthDivergenceSweep.GroupInt { group: Int -> matcher.end(group) })
        }
    }

    private fun appendTrace(
        result: StringBuilder, operation: String?, matched: Boolean, matcher: org.safere.Matcher
    ) {
        appendSeparator(result)
        result.append(operation).append('=').append(matched)
        if (matched) {
            result.append('@').append(matcher.start()).append('-').append(matcher.end())
            appendGroups(
                result,
                matcher.groupCount(),
                RegionZeroWidthDivergenceSweep.GroupInt { group: Int -> matcher.start(group) },
                RegionZeroWidthDivergenceSweep.GroupInt { group: Int -> matcher.end(group) })
        }
    }

    private fun appendGroups(
        result: StringBuilder, groupCount: Int, groupStart: GroupInt, groupEnd: GroupInt
    ) {
        if (groupCount == 0) {
            return
        }
        result.append('[')
        for (group in 1..groupCount) {
            if (group > 1) {
                result.append(';')
            }
            val start = groupStart.get(group)
            val end = groupEnd.get(group)
            result.append(group).append('=')
            if (start < 0) {
                result.append("-")
            } else {
                result.append(start).append('-').append(end)
            }
        }
        result.append(']')
    }

    private fun semanticallyEqual(left: Outcome, right: Outcome): Boolean {
        if (left.accepted != right.accepted) {
            return false
        }
        return !left.accepted || left.trace == right.trace
    }

    private fun caseFromIndex(index: Long): CaseSpec {
        var index = index
        val boundsIndex = (index % BOUNDS_MODES.size).toInt()
        index /= BOUNDS_MODES.size.toLong()
        val textRegionIndex = (index % TEXT_REGIONS.size).toInt()
        index /= TEXT_REGIONS.size.toLong()
        val flagIndex = (index % FLAG_MODES.size).toInt()
        index /= FLAG_MODES.size.toLong()
        val regexIndex = index.toInt()
        return CaseSpec(
            REGEXES.get(regexIndex),
            FLAG_MODES.get(flagIndex),
            TEXT_REGIONS.get(textRegionIndex),
            BOUNDS_MODES.get(boundsIndex)
        )
    }

    private fun replayCase(line: String?): CaseSpec {
        val `object` = SweepJson.parseObject(line)
        val caseObject = SweepJson.`object`(`object`, "case")
        return CaseSpec(
            regex(SweepJson.string(caseObject, "regexLabel"), SweepJson.string(caseObject, "regex")),
            flags(SweepJson.string(caseObject, "flagLabel"), SweepJson.integer(caseObject, "flags")),
            TextRegion(
                SweepJson.string(caseObject, "regionLabel"),
                SweepJson.string(caseObject, "text"),
                SweepJson.integer(caseObject, "regionStart"),
                SweepJson.integer(caseObject, "regionEnd")
            ),
            bounds(
                SweepJson.string(caseObject, "boundsLabel"),
                SweepJson.bool(caseObject, "transparentBounds"),
                SweepJson.bool(caseObject, "anchoringBounds")
            )
        )
    }

    private fun compactReplayJson(caseIndex: Long, classification: String?, spec: CaseSpec): String {
        val `object` = SweepJson.`object`()
        `object`.addProperty("caseIndex", caseIndex)
        `object`.addProperty("classification", classification)
        `object`.add("case", Divergence.Companion.caseJson(spec))
        return SweepJson.toJson(`object`)
    }

    private fun buildTextRegions(): MutableList<TextRegion> {
        return List.of<TextRegion?>(
            region("empty", "", 0, 0),
            region("asciiA", "a", 0, 1),
            region("asciiB", "b", 0, 1),
            region("digit", "5", 0, 1),
            region("space", " ", 0, 1),
            region("newline", "\n", 0, 1),
            region("carriageReturn", "\r", 0, 1),
            region("finalCrLfSplitCrOnly", "\r\n", 0, 1),
            region("finalCrLfLfOnly", "\r\n", 1, 2),
            region("combiningMark", "\u0301", 0, 1),
            region("baseBeforeMark", "a\u0301", 0, 1),
            region("markOnlyAfterBase", "a\u0301", 1, 2),
            region("loneHighSurrogate", "\uD83D", 0, 1),
            region("loneLowSurrogate", "\uDE00", 0, 1),
            region("emojiFull", "\uD83D\uDE00", 0, 2),
            region("emojiHighOnly", "\uD83D\uDE00", 0, 1),
            region("emojiLowOnly", "\uD83D\uDE00", 1, 2),
            region("emojiEmptyInside", "\uD83D\uDE00", 1, 1),
            region("emojiPrefixedHighOnly", "a\uD83D\uDE00", 1, 2),
            region("emojiPrefixedLowOnly", "a\uD83D\uDE00", 2, 3),
            region("emojiPrefixedEndsAfterHigh", "a\uD83D\uDE00", 0, 2),
            region("emojiSuffixedHighOnly", "\uD83D\uDE00a", 0, 1),
            region("emojiSuffixedLowOnly", "\uD83D\uDE00a", 1, 2),
            region("emojiSuffixedFull", "\uD83D\uDE00a", 0, 2),
            region("twoEmojiFirstHighOnly", "\uD83D\uDE00\uD83D\uDE00", 0, 1),
            region("twoEmojiMiddleLowHigh", "\uD83D\uDE00\uD83D\uDE00", 1, 3),
            region("twoEmojiSecondHighOnly", "\uD83D\uDE00\uD83D\uDE00", 2, 3),
            region("splitBeforeCombining", "\uD83D\uDE00\u0301", 0, 1),
            region("lowBeforeCombining", "\uD83D\uDE00\u0301", 1, 3),
            region("splitBeforeModifier", "\uD83D\uDC4D\uD83C\uDFFD", 0, 1),
            region("lowBeforeModifier", "\uD83D\uDC4D\uD83C\uDFFD", 1, 3),
            region("asciiThenSplitThenAscii", "x\uD83D\uDE00y", 1, 2),
            region("asciiThenLowThenAscii", "x\uD83D\uDE00y", 2, 3),
            region("asciiThroughSplitHigh", "x\uD83D\uDE00y", 0, 2),
            region("splitHighThroughAscii", "x\uD83D\uDE00y", 1, 4),
            region("emptyBeforeHigh", "x\uD83D\uDE00y", 1, 1),
            region("emptyInsidePair", "x\uD83D\uDE00y", 2, 2),
            region("emptyAfterLow", "x\uD83D\uDE00y", 3, 3)
        )
    }

    private fun regex(label: String, regex: String?): RegexCase {
        return RegexCase(label, regex)
    }

    private fun flags(label: String?, flags: Int): FlagMode {
        return FlagMode(label, flags)
    }

    private fun bounds(
        label: String?, transparentBounds: Boolean, anchoringBounds: Boolean
    ): BoundsMode {
        return BoundsMode(label, transparentBounds, anchoringBounds)
    }

    private fun region(label: String?, text: String, start: Int, end: Int): TextRegion {
        return TextRegion(label, text, start, end)
    }

    private fun appendSeparator(result: StringBuilder) {
        if (result.length > 0) {
            result.append(',')
        }
    }

    private fun classificationId(classification: DivergenceClass): Int {
        return when (classification) {
            DivergenceClass.ASCII_WORD_BOUNDARY_COMBINING_MARK -> 0
            DivergenceClass.OPAQUE_REGION_CRLF_PAIR_CONTEXT -> 1
            DivergenceClass.BOUNDARY_ANY_CLASS_SPLIT_SURROGATE_SCALAR_COMPOSITION -> 2
            DivergenceClass.NON_WORD_BOUNDARY_SPLIT_SURROGATE_INTERIOR_POSITION -> 3
            DivergenceClass.UNKNOWN -> 4
        }
    }

    @JvmRecord
    private data class RegexCase(val label: String, val regex: String?)

    @JvmRecord
    private data class FlagMode(val label: String?, val flags: Int)

    @JvmRecord
    private data class BoundsMode(val label: String?, val transparentBounds: Boolean, val anchoringBounds: Boolean)

    @JvmRecord
    private data class TextRegion(val label: String?, val text: String, val start: Int, val end: Int)

    @JvmRecord
    private data class CaseSpec(
        val regexCase: RegexCase?,
        val flagMode: FlagMode?,
        val textRegion: TextRegion?,
        val boundsMode: BoundsMode?
    )

    @JvmRecord
    private data class Outcome(val accepted: Boolean, val trace: String?, val error: String?)

    @JvmRecord
    private data class Divergence(
        val spec: CaseSpec?,
        val jdk: Outcome?,
        val safere: Outcome?,
        val classification: DivergenceClass?
    ) {
        fun toJson(): String {
            val `object` = SweepJson.`object`()
            `object`.add("case", Companion.caseJson(spec!!))
            `object`.addProperty("bucket", bucket())
            `object`.addProperty("classification", classification!!.name)
            `object`.addProperty("classificationStatus", classification.status()!!.name)
            `object`.addProperty("regex", spec.regexCase!!.regex)
            `object`.addProperty("text", spec.textRegion!!.text)
            `object`.addProperty("regionStart", spec.textRegion.start)
            `object`.addProperty("regionEnd", spec.textRegion.end)
            `object`.addProperty("jdkAccepted", jdk!!.accepted)
            `object`.addProperty("safeReAccepted", safere!!.accepted)
            `object`.addProperty("jdkTrace", jdk.trace)
            `object`.addProperty("safeReTrace", safere.trace)
            `object`.addProperty("jdkError", jdk.error)
            `object`.addProperty("safeReError", safere.error)
            return SweepJson.toJson(`object`)
        }

        fun bucket(): String {
            return ("regex="
                    + spec!!.regexCase!!.label
                    + ";region="
                    + spec.textRegion!!.label
                    + ";bounds="
                    + spec.boundsMode!!.label)
        }

        companion object {
            private fun caseJson(spec: CaseSpec): JsonObject {
                val `object` = SweepJson.`object`()
                `object`.addProperty("regexLabel", spec.regexCase!!.label)
                `object`.addProperty("regex", spec.regexCase.regex)
                `object`.addProperty("flagLabel", spec.flagMode!!.label)
                `object`.addProperty("flags", spec.flagMode.flags)
                `object`.addProperty("regionLabel", spec.textRegion!!.label)
                `object`.addProperty("text", spec.textRegion.text)
                `object`.addProperty("regionStart", spec.textRegion.start)
                `object`.addProperty("regionEnd", spec.textRegion.end)
                `object`.addProperty("boundsLabel", spec.boundsMode!!.label)
                `object`.addProperty("transparentBounds", spec.boundsMode.transparentBounds)
                `object`.addProperty("anchoringBounds", spec.boundsMode.anchoringBounds)
                return `object`
            }
        }
    }

    private interface GroupInt {
        fun get(group: Int): Int
    }

    private enum class DivergenceClass(status: DivergenceStatus, rationale: String) {
        ASCII_WORD_BOUNDARY_COMBINING_MARK(
            DivergenceStatus.KNOWN_INTENTIONAL,
            "SafeRE follows the documented default ASCII word-character model for \\b and \\B"
                    + " without attaching combining marks to preceding base characters."
        ),
        OPAQUE_REGION_CRLF_PAIR_CONTEXT(
            DivergenceStatus.KNOWN_INTENTIONAL,
            ("Observed JDK traces expose a hidden pre-region CR when matching $ and \\Z against an"
                    + " opaque region containing only the LF half of CRLF. SafeRE keeps opaque region"
                    + " end-anchor context region-local.")
        ),
        BOUNDARY_ANY_CLASS_SPLIT_SURROGATE_SCALAR_COMPOSITION(
            DivergenceStatus.KNOWN_INTENTIONAL,
            ("Observed JDK traces distinguish . from [\\s\\S] after \\B at a transparent split-surrogate"
                    + " boundary. SafeRE decodes both consuming atoms within the region while \\B"
                    + " retains its transparent-bound context.")
        ),
        NON_WORD_BOUNDARY_SPLIT_SURROGATE_INTERIOR_POSITION(
            DivergenceStatus.KNOWN_INTENTIONAL,
            ("Observed JDK traces expose a \\B match at the interior UTF-16 position of a"
                    + " transparent split surrogate pair. SafeRE keeps word-boundary matching"
                    + " compositional with its scalar boundary model.")
        ),
        UNKNOWN(DivergenceStatus.UNKNOWN, "Unclassified SafeRE/JDK region zero-width divergence.");

        private val status: DivergenceStatus?
        private val rationale: String?

        init {
            this.status = status
            this.rationale = rationale
        }

        fun status(): DivergenceStatus? {
            return status
        }

        override fun toString(): String {
            return name + ": " + rationale
        }
    }
}
