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
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.math.min

/** Offline differential sweep for ordinary scalar-consuming atoms under matcher regions.  */
object RegionScalarDivergenceSweep {
    private const val FIND_LIMIT = 8
    private val DIVERGENCE_CLASSES: MutableList<DivergenceClass?> = List.of<DivergenceClass?>(
        DivergenceClass.REGION_LOCAL_SCALAR_CONSUMPTION_AT_SPLIT_SURROGATE_END,
        DivergenceClass.UNKNOWN
    )

    // Wrappers that only group or quantify the scalar atom. Other wrappers can introduce
    // independent anchor, alternation, or multi-atom effects and remain subject to review.
    private val REGION_LOCAL_SCALAR_WRAPPERS = mutableSetOf<String?>(
        "bare",
        "captured",
        "nonCapturing",
        "optionalGreedy",
        "optionalReluctant",
        "starGreedy",
        "plusGreedy",
        "countOne",
        "countOptional",
        "capturedOptional",
        "capturedPlus"
    )

    private val ATOMS: MutableList<AtomCase> = List.of<AtomCase?>(
        atom("dot", "."),
        atom("anyClass", "[\\s\\S]"),
        atom("emptyComplement", "[^\\s\\S]"),
        atom("literalA", "a"),
        atom("notLiteralA", "[^a]"),
        atom("asciiRange", "[a-z]"),
        atom("notAsciiRange", "[^a-z]"),
        atom("digit", "\\d"),
        atom("nonDigit", "\\D"),
        atom("space", "\\s"),
        atom("nonSpace", "\\S"),
        atom("word", "\\w"),
        atom("nonWord", "\\W"),
        atom("horizontalSpace", "\\h"),
        atom("nonHorizontalSpace", "\\H"),
        atom("verticalSpace", "\\v"),
        atom("nonVerticalSpace", "\\V"),
        atom("posixLower", "\\p{Lower}"),
        atom("notPosixLower", "\\P{Lower}"),
        atom("posixAscii", "\\p{ASCII}"),
        atom("notPosixAscii", "\\P{ASCII}"),
        atom("posixControl", "\\p{Cntrl}"),
        atom("notPosixControl", "\\P{Cntrl}"),
        atom("javaWhitespace", "\\p{javaWhitespace}"),
        atom("notJavaWhitespace", "\\P{javaWhitespace}"),
        atom("javaLetter", "\\p{javaLetter}"),
        atom("notJavaLetter", "\\P{javaLetter}"),
        atom("unicodeAlphabetic", "\\p{IsAlphabetic}"),
        atom("notUnicodeAlphabetic", "\\P{IsAlphabetic}"),
        atom("surrogateCategory", "\\p{Cs}"),
        atom("notSurrogateCategory", "\\P{Cs}"),
        atom("surrogateCategoryClass", "[\\p{Cs}]"),
        atom("notSurrogateCategoryClass", "[^\\p{Cs}]"),
        atom("symbolOther", "\\p{So}"),
        atom("notSymbolOther", "\\P{So}"),
        atom("unassigned", "\\p{Cn}"),
        atom("notUnassigned", "\\P{Cn}")
    )

    private val WRAPPERS: MutableList<WrapperCase> = List.of<WrapperCase?>(
        wrapper("bare", "%s"),
        wrapper("captured", "(%s)"),
        wrapper("nonCapturing", "(?:%s)"),
        wrapper("anchoredStart", "^%s"),
        wrapper("anchoredEnd", "%s$"),
        wrapper("anchoredBoth", "^%s$"),
        wrapper("optionalGreedy", "%s?"),
        wrapper("optionalReluctant", "%s??"),
        wrapper("starGreedy", "%s*"),
        wrapper("plusGreedy", "%s+"),
        wrapper("countOne", "%s{1}"),
        wrapper("countOptional", "%s{0,1}"),
        wrapper("countTwo", "%s{2}"),
        wrapper("capturedOptional", "(%s)?"),
        wrapper("capturedPlus", "(%s)+"),
        wrapper("doubleAtom", "%s%s"),
        wrapper("capturedDoubleAtom", "(%s)(%s)"),
        wrapper("literalPrefix", "a%s"),
        wrapper("literalSuffix", "%sa"),
        wrapper("alternativeAfterLiteral", "a|%s"),
        wrapper("alternativeBeforeLiteral", "%s|a"),
        wrapper("capturedAlternativeAfterLiteral", "(a)|(%s)"),
        wrapper("capturedAlternativeBeforeLiteral", "(%s)|(a)"),
        wrapper("optionalThenLiteral", "%s?a"),
        wrapper("literalThenOptional", "a%s?")
    )

    private val FLAG_MODES: MutableList<FlagMode> = List.of<FlagMode?>(
        flags("none", 0),
        flags("dotall", Pattern.DOTALL),
        flags("caseInsensitive", Pattern.CASE_INSENSITIVE),
        flags(
            "caseInsensitiveUnicodeCase",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        flags("unicodeCharacterClass", Pattern.UNICODE_CHARACTER_CLASS),
        flags(
            "unicodeCharacterClassDotall",
            Pattern.UNICODE_CHARACTER_CLASS or Pattern.DOTALL
        ),
        flags("multiline", Pattern.MULTILINE),
        flags("unixLines", Pattern.UNIX_LINES),
        flags("comments", Pattern.COMMENTS),
        flags(
            "dotallMultiline",
            Pattern.DOTALL or Pattern.MULTILINE
        ),
        flags(
            "unicodeCaseDotall",
            (Pattern.CASE_INSENSITIVE
                    or Pattern.UNICODE_CASE
                    or Pattern.DOTALL)
        )
    )

    private val BOUNDS_MODES: MutableList<BoundsMode?> = List.of<BoundsMode?>(
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
                Path.of("target", "exhaustive-reports", "region-scalar-sweep"),
                "region-scalar-divergences.jsonl",
                1000000
            )
        Files.createDirectories(options.outputDir)
        if (options.replayFile != null) {
            Files.deleteIfExists(options.jsonlPath())
        }
        options.printStartup("region-scalar")

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
        return (ATOMS.size.toLong() * WRAPPERS.size
                * FLAG_MODES.size
                * TEXT_REGIONS.size
                * BOUNDS_MODES.size)
    }

    @JvmStatic
    fun compactReplayJson(caseIndex: Long, classification: String?): String {
        return compactReplayJson(caseIndex, classification, caseFromIndex(caseIndex))
    }

    @JvmStatic
    fun containsGeneratedCaseForTesting(
        atom: String?, wrapper: String?, flags: Int, text: String?, start: Int, end: Int
    ): Boolean {
        return generatedCaseForTesting(atom, wrapper, flags, text, start, end) != null
    }

    @JvmStatic
    fun classifyDivergenceShapeForTesting(
        atom: String?, wrapper: String?, flags: Int, text: String?, start: Int, end: Int
    ): String {
        val spec = generatedCaseForTesting(atom, wrapper, flags, text, start, end)
        requireNotNull(spec) { "case is not generated by the region scalar sweep" }
        return classifyDivergence(spec).name
    }

    private fun generatedCaseForTesting(
        atom: String?, wrapper: String?, flags: Int, text: String?, start: Int, end: Int
    ): CaseSpec? {
        for (atomCase in ATOMS) {
            if (atomCase.regex != atom) {
                continue
            }
            for (wrapperCase in WRAPPERS) {
                if (wrapperCase.template != wrapper) {
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
                            return CaseSpec(
                                atomCase, wrapperCase, flagMode, textRegion, BOUNDS_MODES.getFirst()
                            )
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
                "region-scalar",
                totalCases(),
                DIVERGENCE_CLASSES.stream().map<String?> { obj: DivergenceClass? -> obj!!.name }.toList(),
                DIVERGENCE_CLASSES.stream().map<DivergenceStatus?> { obj: DivergenceClass? -> obj!!.status() }.toList()
            )
            SweepWorkers.run(
                options.threads,
                "region-scalar-sweep-",
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
                        "region-scalar-replay-",
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
        if (REGION_LOCAL_SCALAR_WRAPPERS.contains(spec.wrapperCase!!.label)
            && RegionScalarDivergenceSweep.regionEndsInsideSurrogatePair(spec.textRegion!!)
        ) {
            return DivergenceClass.REGION_LOCAL_SCALAR_CONSUMPTION_AT_SPLIT_SURROGATE_END
        }
        return DivergenceClass.UNKNOWN
    }

    private fun regionEndsInsideSurrogatePair(region: TextRegion): Boolean {
        val text = region.text
        val end = region.end
        return end > 0 && end < text.length && Character.isHighSurrogate(text.get(end - 1))
                && Character.isLowSurrogate(text.get(end))
    }

    private fun jdkOutcome(spec: CaseSpec): Outcome {
        try {
            val pattern =
                Pattern.compile(spec.regex(), spec.flagMode!!.flags)
            return Outcome(true, operationTrace(pattern, spec), "")
        } catch (e: PatternSyntaxException) {
            return Outcome(false, "", e.getDescription())
        } catch (e: StackOverflowError) {
            return Outcome(false, "", "StackOverflowError")
        } catch (e: RuntimeException) {
            return Outcome(false, "", e.javaClass.getSimpleName() + ": " + e.message)
        }
    }

    private fun safeReOutcome(spec: CaseSpec): Outcome {
        try {
            val pattern =
                org.safere.Pattern.compile(spec.regex(), spec.flagMode!!.flags)
            return Outcome(true, operationTrace(pattern, spec), "")
        } catch (e: PatternSyntaxException) {
            return Outcome(false, "", e.getDescription())
        } catch (e: StackOverflowError) {
            return Outcome(false, "", "StackOverflowError")
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

    private fun appendTrace(
        result: StringBuilder, operation: String?, matched: Boolean, matcher: Matcher
    ) {
        appendSeparator(result)
        result.append(operation).append('=').append(matched)
        if (matched) {
            appendMatch(
                result,
                matcher.start(),
                matcher.end(),
                matcher.groupCount(),
                RegionScalarDivergenceSweep.GroupInt { group: Int -> matcher.start(group) },
                RegionScalarDivergenceSweep.GroupInt { group: Int -> matcher.end(group) },
                RegionScalarDivergenceSweep.GroupString { group: Int -> matcher.group(group) })
        }
    }

    private fun appendTrace(
        result: StringBuilder, operation: String?, matched: Boolean, matcher: org.safere.Matcher
    ) {
        appendSeparator(result)
        result.append(operation).append('=').append(matched)
        if (matched) {
            appendMatch(
                result,
                matcher.start(),
                matcher.end(),
                matcher.groupCount(),
                RegionScalarDivergenceSweep.GroupInt { group: Int -> matcher.start(group) },
                RegionScalarDivergenceSweep.GroupInt { group: Int -> matcher.end(group) },
                RegionScalarDivergenceSweep.GroupString { group: Int -> matcher.group(group) })
        }
    }

    private fun appendFindTrace(
        result: StringBuilder, operation: String?, matcher: Matcher
    ) {
        for (count in 0..<FIND_LIMIT) {
            val matched = matcher.find()
            appendSeparator(result)
            result.append(operation).append(count).append('=').append(matched)
            if (matched) {
                appendMatch(
                    result,
                    matcher.start(),
                    matcher.end(),
                    matcher.groupCount(),
                    RegionScalarDivergenceSweep.GroupInt { group: Int -> matcher.start(group) },
                    RegionScalarDivergenceSweep.GroupInt { group: Int -> matcher.end(group) },
                    RegionScalarDivergenceSweep.GroupString { group: Int -> matcher.group(group) })
            } else {
                return
            }
        }
    }

    private fun appendFindTrace(
        result: StringBuilder, operation: String?, matcher: org.safere.Matcher
    ) {
        for (count in 0..<FIND_LIMIT) {
            val matched = matcher.find()
            appendSeparator(result)
            result.append(operation).append(count).append('=').append(matched)
            if (matched) {
                appendMatch(
                    result,
                    matcher.start(),
                    matcher.end(),
                    matcher.groupCount(),
                    RegionScalarDivergenceSweep.GroupInt { group: Int -> matcher.start(group) },
                    RegionScalarDivergenceSweep.GroupInt { group: Int -> matcher.end(group) },
                    RegionScalarDivergenceSweep.GroupString { group: Int -> matcher.group(group) })
            } else {
                return
            }
        }
    }

    private fun appendMatch(
        result: StringBuilder,
        start: Int,
        end: Int,
        groupCount: Int,
        groupStart: GroupInt,
        groupEnd: GroupInt,
        groupText: GroupString
    ) {
        result.append('@').append(start).append('-').append(end)
        if (groupCount == 0) {
            return
        }
        result.append("{groups=").append(groupCount)
        for (group in 1..groupCount) {
            val text = groupText.get(group)
            result.append(";g").append(group).append('=')
            if (text == null) {
                result.append("null")
            } else {
                result
                    .append(groupStart.get(group))
                    .append('-')
                    .append(groupEnd.get(group))
                    .append(':')
                    .append(escape(text))
            }
        }
        result.append('}')
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
        val wrapperIndex = (index % WRAPPERS.size).toInt()
        index /= WRAPPERS.size.toLong()
        val atomIndex = index.toInt()
        return CaseSpec(
            ATOMS.get(atomIndex),
            WRAPPERS.get(wrapperIndex),
            FLAG_MODES.get(flagIndex),
            TEXT_REGIONS.get(textRegionIndex),
            BOUNDS_MODES.get(boundsIndex)
        )
    }

    private fun replayCase(line: String?): CaseSpec {
        val `object` = SweepJson.parseObject(line)
        val caseObject = SweepJson.`object`(`object`, "case")
        return CaseSpec(
            atom(SweepJson.string(caseObject, "atomLabel"), SweepJson.string(caseObject, "atomRegex")),
            wrapper(
                SweepJson.string(caseObject, "wrapperLabel"),
                SweepJson.string(caseObject, "wrapperTemplate")
            ),
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
            region("nel", "\u0085", 0, 1),
            region("combiningMark", "\u0301", 0, 1),
            region("zwj", "\u200D", 0, 1),
            region("arabicNumberSign", "\u0600", 0, 1),
            region("devanagariSpacingMark", "\u0903", 0, 1),
            region("hangulL", "\u1100", 0, 1),
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
            region("regionalIndicatorFull", "\uD83C\uDDFA", 0, 2),
            region("regionalIndicatorHighOnly", "\uD83C\uDDFA", 0, 1),
            region("regionalIndicatorLowOnly", "\uD83C\uDDFA", 1, 2),
            region("deseretUpperFull", "\uD801\uDC00", 0, 2),
            region("deseretUpperHighOnly", "\uD801\uDC00", 0, 1),
            region("deseretUpperLowOnly", "\uD801\uDC00", 1, 2),
            region("mathDigitFull", "\uD835\uDFCE", 0, 2),
            region("mathDigitHighOnly", "\uD835\uDFCE", 0, 1),
            region("mathDigitLowOnly", "\uD835\uDFCE", 1, 2),
            region("invalidLowHighFull", "\uDE00\uD83D", 0, 2),
            region("invalidLowHighLowOnly", "\uDE00\uD83D", 0, 1),
            region("invalidLowHighHighOnly", "\uDE00\uD83D", 1, 2),
            region("splitBeforeCombining", "\uD83D\uDE00\u0301", 0, 1),
            region("lowBeforeCombining", "\uD83D\uDE00\u0301", 1, 3),
            region("splitBeforeZwj", "\uD83D\uDE00\u200D", 0, 1),
            region("lowBeforeZwj", "\uD83D\uDE00\u200D", 1, 3),
            region("splitBeforeModifier", "\uD83D\uDC4D\uD83C\uDFFD", 0, 1),
            region("lowBeforeModifier", "\uD83D\uDC4D\uD83C\uDFFD", 1, 3),
            region("asciiThenSplitThenAscii", "x\uD83D\uDE00y", 1, 2),
            region("asciiThenLowThenAscii", "x\uD83D\uDE00y", 2, 3),
            region("asciiThroughSplitHigh", "x\uD83D\uDE00y", 0, 2),
            region("splitHighThroughAscii", "x\uD83D\uDE00y", 1, 4),
            region("emptyBeforeHigh", "x\uD83D\uDE00y", 1, 1),
            region("emptyInsidePair", "x\uD83D\uDE00y", 2, 2),
            region("emptyAfterLow", "x\uD83D\uDE00y", 3, 3),
            region("finalCrLfSplitCrOnly", "\r\n", 0, 1),
            region("finalCrLfLfOnly", "\r\n", 1, 2),
            region("baseBeforeMark", "a\u0301", 0, 1),
            region("markOnlyAfterBase", "a\u0301", 1, 2)
        )
    }

    private fun atom(label: String, regex: String?): AtomCase {
        return AtomCase(label, regex)
    }

    private fun wrapper(label: String?, template: String?): WrapperCase {
        return WrapperCase(label, template)
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

    private fun escape(value: String): String {
        val result = StringBuilder()
        for (i in 0..<value.length) {
            val c = value.get(i)
            when (c) {
                '\\' -> result.append("\\\\")
                '\n' -> result.append("\\n")
                '\r' -> result.append("\\r")
                '\t' -> result.append("\\t")
                '"' -> result.append("\\\"")
                else -> {
                    if (Character.isISOControl(c) || Character.isSurrogate(c)) {
                        result.append(String.format(Locale.ROOT, "\\u%04X", c.code))
                    } else {
                        result.append(c)
                    }
                }
            }
        }
        return result.toString()
    }

    private fun classificationId(classification: DivergenceClass): Int {
        return when (classification) {
            DivergenceClass.REGION_LOCAL_SCALAR_CONSUMPTION_AT_SPLIT_SURROGATE_END -> 0
            DivergenceClass.UNKNOWN -> 1
        }
    }

    @JvmRecord
    private data class AtomCase(val label: String, val regex: String?)

    @JvmRecord
    private data class WrapperCase(val label: String?, val template: String?) {
        fun regex(atom: String?): String {
            return template.formatted(atom, atom)
        }
    }

    @JvmRecord
    private data class FlagMode(val label: String?, val flags: Int)

    @JvmRecord
    private data class BoundsMode(val label: String?, val transparentBounds: Boolean, val anchoringBounds: Boolean)

    @JvmRecord
    private data class TextRegion(val label: String?, val text: String, val start: Int, val end: Int)

    @JvmRecord
    private data class CaseSpec(
        val atomCase: AtomCase?,
        val wrapperCase: WrapperCase?,
        val flagMode: FlagMode?,
        val textRegion: TextRegion?,
        val boundsMode: BoundsMode?
    ) {
        fun regex(): String {
            return wrapperCase!!.regex(atomCase!!.regex)
        }
    }

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
            `object`.addProperty("regex", spec.regex())
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
            return ("atom="
                    + spec!!.atomCase!!.label
                    + ";wrapper="
                    + spec.wrapperCase!!.label
                    + ";region="
                    + spec.textRegion!!.label
                    + ";bounds="
                    + spec.boundsMode!!.label)
        }

        companion object {
            private fun caseJson(spec: CaseSpec): JsonObject {
                val `object` = SweepJson.`object`()
                `object`.addProperty("atomLabel", spec.atomCase!!.label)
                `object`.addProperty("atomRegex", spec.atomCase.regex)
                `object`.addProperty("wrapperLabel", spec.wrapperCase!!.label)
                `object`.addProperty("wrapperTemplate", spec.wrapperCase.template)
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

    private interface GroupString {
        fun get(group: Int): String?
    }

    private enum class DivergenceClass(status: DivergenceStatus, rationale: String) {
        REGION_LOCAL_SCALAR_CONSUMPTION_AT_SPLIT_SURROGATE_END(
            DivergenceStatus.KNOWN_INTENTIONAL,
            ("SafeRE decodes ordinary consuming atoms within the matcher region, so a high surrogate"
                    + " at a split region end is unpaired. JDK scalar atom behavior at this boundary"
                    + " depends on atom and quantifier spelling.")
        ),
        UNKNOWN(DivergenceStatus.UNKNOWN, "Unclassified SafeRE/JDK region scalar divergence.");

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
