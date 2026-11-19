// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.safere.Matcher
import org.safere.Pattern
import org.safere.fuzz.FuzzSupport.CompiledPattern
import java.util.List
import java.util.function.IntFunction
import java.util.function.IntPredicate

class RegionBoundsFuzzer {
    @JvmRecord
    private data class GraphemeRegion(val input: String?, val start: Int, val end: Int)

    @JvmRecord
    private data class SafeReModelCase(
        val regex: String?,
        val input: String?,
        val start: Int,
        val end: Int,
        val expectedFinds: MutableList<String?>?
    )

    @JvmRecord
    private data class MixedGraphemeScalarPattern(val regex: String?, val firstMatchEndOffset: Int)

    @FuzzTest(maxDuration = "30s")
    fun regionBounds(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    @FuzzTest(maxDuration = "30s")
    fun assertionComposition(data: FuzzedDataProvider) {
        compareGeneratedAssertionComposition(data)
    }

    @FuzzTest(maxDuration = "30s")
    fun opaqueSubstring(data: FuzzedDataProvider) {
        val anchoring = data.consumeBoolean()
        val regex: String =
            (data.pickValue<String?>(OPAQUE_CONSUMERS)
                    + data.pickValue<String>(if (anchoring) ASSERTION_PREDICATES else OPAQUE_LOCAL_ASSERTIONS)
                    + data.pickValue<String?>(OPAQUE_RIGHT_ATOMS))
        val builder = StringBuilder()
        val fragments = data.consumeInt(0, 8)
        for (i in 0..<fragments) {
            builder.append(data.pickValue<String?>(ASSERTION_INPUT_FRAGMENTS))
        }
        val input = builder.toString()
        val start = data.consumeInt(0, input.length)
        val end = data.consumeInt(start, input.length)
        val pattern = Pattern.compile(regex)
        for (operation in 0..2) {
            val region = pattern.matcher(input).region(start, end).useAnchoringBounds(anchoring)
            val substring = pattern.matcher(input.substring(start, end))
            val expected: MutableList<String?> = opaqueTrace(substring, operation, 0, input.length)
            val actual: MutableList<String?> = opaqueTrace(region, operation, start, input.length)
            if (actual != expected) {
                throw AssertionError(
                    ("Opaque substring mismatch: "
                            + regex
                            + " input="
                            + input.codePoints().mapToObj<String?>(IntFunction { i: Int -> Integer.toHexString(i) })
                        .toList()
                            + " region="
                            + start
                            + ":"
                            + end
                            + " anchoring="
                            + anchoring
                            + " operation="
                            + operation
                            + " expected="
                            + expected
                            + " actual="
                            + actual)
                )
            }
        }
    }

    companion object {
        private val GRAPHEME_REGIONS: MutableList<GraphemeRegion> = List.of<GraphemeRegion?>(
            GraphemeRegion("\uD83C\uDDE6".repeat(3), 2, 6),
            GraphemeRegion("\uD83D\uDE00", 0, 1),
            GraphemeRegion("e\u0301", 1, 2),
            GraphemeRegion("a\u0301b", 1, 2),
            GraphemeRegion("\u1100\u1161", 1, 2),
            GraphemeRegion("\u0915\u094D\u0937", 0, 3)
        )

        private val GRAPHEME_REGION_REGEXES =
            mutableListOf<String?>("\\X", "^\\X$", "\\b{g}", "\\b{g}\\X", "\\X\\b{g}", "\\b{g}\\X\\b{g}")

        private val SPLIT_REGIONAL_GRAPHEME_REGIONS: MutableList<GraphemeRegion> = List.of<GraphemeRegion?>(
            GraphemeRegion("\uD83C\uDDE6\uD83C\uDDE6", 0, 1),
            GraphemeRegion("\uD83C\uDDE6\uD83C\uDDE6", 1, 3)
        )

        private val SPLIT_REGIONAL_GRAPHEME_REGEXES = mutableListOf<String?>("\\X", "\\b{g}", "\\X\\b{g}")

        private val REGION_LOCAL_SCALAR_ATOMS: MutableList<String> = mutableListOf<String?>(
            ".",
            ".*",
            ".+",
            "(.)",
            "(.+)",
            "([^a]+)",
            "[^a]",
            "[\\s\\S]",
            "[\\s\\S]*",
            "\\D",
            "\\p{Cs}",
            "\\P{Cs}",
            "[^\\p{Cs}]"
        )

        private val MIXED_GRAPHEME_SCALAR_ATOMS: MutableList<MixedGraphemeScalarPattern> =
            List.of<MixedGraphemeScalarPattern?>(
                MixedGraphemeScalarPattern("(?:\\X|.)", 1),
                MixedGraphemeScalarPattern("(?:.|\\X)", 1),
                MixedGraphemeScalarPattern("(?:\\X|[\\s\\S])", 1),
                MixedGraphemeScalarPattern("(?:\\b{g}|.)", 0)
            )

        private val SCALAR_CONTEXTS: MutableList<String> = mutableListOf<String?>("", "x", "\uD83D", "\uDC4D")

        private val OPAQUE_CONSUMERS = mutableListOf<String?>(
            "",
            ".",
            "(.)?",
            "(.)*",
            "[\\s\\S]",
            "\\p{Cs}",
            "\\P{Cs}",
            "\\uD83D",
            "(\\uDE00)",
            "\\X?\\uDE00",
            "a?\\uDE00\\X?",
            "[a\\uDE00]{2}\\X?",
            "\\X?\\p{Cs}",
            "a\\uD83D",
            "\\X",
            "(\\X)*",
            "(\\X)|(.)",
            "(.)|(\\X)"
        )
        private val OPAQUE_LOCAL_ASSERTIONS = mutableListOf<String?>("", "\\b", "\\B", "(?U:\\b)", "(?U:\\B)", "\\b{g}")
        private val OPAQUE_RIGHT_ATOMS = mutableListOf<String?>("", ".", "(.)?", "a?", "\\X?", "(\\X)*", "|(?:\\Xz)")

        // Same bounded pattern grammar as RegionAssertionCompositionTest. Fuzzing extends its curated
        // corpus by concatenating Unicode/context fragments and choosing arbitrary regions within them.
        private val ASSERTION_LEFT_ATOMS =
            mutableListOf<String?>(".", "(.)?", "(.)*", "(.)+?", "(a+?)b", "([a-z]+)", "[\\s\\S]", "\\p{Cs}", "\\P{Cs}")
        private val ASSERTION_PREDICATES: MutableList<String> = mutableListOf<String?>(
            "\\b",
            "\\B",
            "(?U:\\b)",
            "(?U:\\B)",
            "\\b{g}",
            "^",
            "$",
            "\\A",
            "\\z",
            "\\Z",
            "(?m:^)",
            "(?m:$)",
            "(?dm:$)",
            "\\b{g}$",
            "(?U:\\b)\\b{g}",
            "\\b{g}\\b{g}"
        )
        private val ASSERTION_RIGHT_ATOMS = mutableListOf<String?>("", ".", "(.)?")
        private val ASSERTION_INPUT_FRAGMENTS = mutableListOf<String?>(
            "a",
            "b",
            " ",
            "\r",
            "\u0085",
            "\n",
            "\uD83D\uDC4D",
            "\uD83D\uDE00",
            "\uD801\uDC00",
            "\uD83C\uDDE6",
            "a\u0301",
            "\uD83D\uDC69\u200D\uD83D\uDC69",
            "\u1100\u1161",
            "\u0915\u094D\u0937",
            "\uD83D",
            "\uDC4D",
            "\uDE00"
        )

        private val TRANSPARENT_GRAPHEME_CONTEXT_REGIONS: MutableList<GraphemeRegion> =
            List.of<GraphemeRegion?>(GraphemeRegion("\uD83D\uDC4D\uD83C\uDFFB", 1, 3))

        private val TRANSPARENT_GRAPHEME_CONTEXT_REGEXES = mutableListOf<String?>("\\X\\b{g}")

        private val NON_ANCHORING_FINAL_CRLF_GRAPHEME_REGIONS: MutableList<GraphemeRegion> =
            List.of<GraphemeRegion?>(GraphemeRegion("\r\r\r\n", 0, 3))

        private val NON_ANCHORING_FINAL_CRLF_GRAPHEME_REGEXES =
            mutableListOf<String?>("^\\X\\X$", "\\X\\X$", "^(?:\\X){2}$", "^\\X{2}$")

        private val SAFE_RE_GRAPHEME_MODEL_CASES: MutableList<SafeReModelCase> = List.of<SafeReModelCase?>(
            SafeReModelCase("\\b{g}\\X\\b{g}", "ab", 0, 2, mutableListOf<String?>("0-1", "1-2")),
            SafeReModelCase(
                "\\X\\b{g}", "\uD83D\uDC69\u200D\uD83D\uDC69", 2, 5, mutableListOf<String?>("2-3", "3-5")
            ),
            SafeReModelCase(".\\b{g}.", "\uD83C\uDDE6".repeat(2), 1, 3, mutableListOf<String?>("1-3")),
            SafeReModelCase(
                "(?:.\\b{g}.)|(?:\\Xz)", "\uD83C\uDDE6".repeat(2), 1, 3, mutableListOf<String?>("1-3")
            ),
            SafeReModelCase("\\X\\X", "\u0915\u094D\u0937", 0, 3, mutableListOf<String?>("1-3"))
        )

        private fun opaqueTrace(
            matcher: Matcher, operation: Int, offset: Int, inputLength: Int
        ): MutableList<String?> {
            val trace: MutableList<String?> = ArrayList<String?>()
            for (step in 0..inputLength + 1) {
                val matched =
                    when (operation) {
                        0 -> matcher.matches()
                        1 -> matcher.lookingAt()
                        else -> matcher.find()
                    }
                trace.add(matched.toString())
                if (matched) {
                    for (group in 0..matcher.groupCount()) {
                        val start = matcher.start(group)
                        val end = matcher.end(group)
                        trace.add((if (start < 0) start else start - offset).toString() + ":" + (if (end < 0) end else end - offset))
                        trace.add(matcher.group(group))
                    }
                }
                if (!matched || operation != 2) {
                    return trace
                }
            }
            throw AssertionError("Opaque substring search did not exhaust: " + matcher.pattern())
        }

        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            compareGraphemeRegions()
            compareLineAnchorPaths(data)
            compareAdvancingRegions(data)

            compareRegionLocalScalarModel(data)
            compareSplitSurrogateBoundaryModel(data)
            compareMixedGraphemeScalarModel(data)
            compareMixedAssertionModel(data)
            compareGeneratedAssertionComposition(data)

            val regex = data.consumeString(256)
            val flags = FuzzSupport.consumeFlags(data)
            val input = data.consumeString(2048)
            val pattern = FuzzSupport.compileOrSkip(regex, flags)
            if (pattern == null) {
                return
            }

            val matcher = pattern.matcher(input)
            val region = FuzzSupport.consumeRegion(data, input)
            matcher.region(region[0], region[1])
            matcher.useAnchoringBounds(data.consumeBoolean())
            matcher.useTransparentBounds(data.consumeBoolean())
            matcher.regionStart()
            matcher.regionEnd()
            matcher.hasAnchoringBounds()
            matcher.hasTransparentBounds()
            matcher.find()
            matcher.reset()
            matcher.region(region[0], region[1])
            matcher.matches()
            matcher.reset()
            matcher.region(region[0], region[1])
            matcher.lookingAt()
        }

        private fun compareAdvancingRegions(data: FuzzedDataProvider) {
            val token = data.pickValue<String>(mutableListOf<String?>("a", "abc", "é", "😀", "123"))
            val separator = data.pickValue<String>(mutableListOf<String?>(" ", "\t", "\n"))
            val unit = token + separator
            val body = unit.repeat(data.consumeInt(1, 24))
            val input = "!" + body + "!"
            val atom =
                data.pickValue<String?>(
                    List.of<String?>("[\\p{L}\\p{N}😀]+|\\s+", "(" + Pattern.quote(token) + ")|\\s+", "(?s:.)")
                )
            val pattern = FuzzSupport.compileOrSkip(atom, 0)
            if (pattern == null) {
                return
            }
            val matcher = pattern.matcher(input)
            val end = input.length - 1
            var pos = 1
            while (pos < end) {
                matcher.region(pos, end)
                val found = if (data.consumeBoolean()) matcher.lookingAt() else matcher.find()
                if (!found) {
                    throw AssertionError("Advancing region lost a token")
                }
                val next = matcher.end()
                if (next <= pos) {
                    throw AssertionError("Advancing region made no progress")
                }
                pos = next
            }
        }

        private fun compareLineAnchorPaths(data: FuzzedDataProvider) {
            for (prefix in mutableListOf<String?>("", "(.)?", "([\\s\\S])?", "([\\s\\S])")) {
                for (anchor in mutableListOf<String?>("$", "\\Z", "(?m:$)", "(?m:^)", "(?dm:^)")) {
                    compareLineAnchorPaths(prefix + anchor, "\r\na", 1, 2, true, false)
                    compareLineAnchorPaths(prefix + anchor, "\na", 0, 1, true, false)
                }
            }
            val terminator =
                data.pickValue<String>(mutableListOf<String?>("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029"))
            val input = "a\r" + terminator + "a".repeat(data.consumeInt(0, 4))
            val start = data.consumeInt(0, 2)
            val end = data.consumeInt(start, input.length)
            val prefix =
                data.pickValue<String?>(mutableListOf<String?>("", "(.)?", "([\\s\\S])?", "([\\s\\S])*?", "([\\s\\S])"))
            val anchor =
                data.pickValue<String?>(
                    mutableListOf<String?>("^", "(?m:^)", "(?dm:^)", "$", "\\Z", "(?m:$)", "(?dm:$)", "(?d:\\Z)")
                )
            compareLineAnchorPaths(
                prefix + anchor, input, start, end, data.consumeBoolean(), data.consumeBoolean()
            )
        }

        private fun compareLineAnchorPaths(
            regex: String?, input: String?, start: Int, end: Int, anchoring: Boolean, transparent: Boolean
        ) {
            val scalar =
                Pattern.compile(regex)
                    .matcher(input)
                    .region(start, end)
                    .useAnchoringBounds(anchoring)
                    .useTransparentBounds(transparent)
            val mixed =
                Pattern.compile("(?:" + regex + ")|(?:\\Xz)")
                    .matcher(input)
                    .region(start, end)
                    .useAnchoringBounds(anchoring)
                    .useTransparentBounds(transparent)
            // Engine equivalence is also the oracle for the approved opaque CRLF divergence.
            for (find in mutableListOf<Boolean?>(false, true)) {
                scalar.region(start, end)
                mixed.region(start, end)
                var found: Boolean
                do {
                    found = if (find) scalar.find() else scalar.lookingAt()
                    val mixedFound = if (find) mixed.find() else mixed.lookingAt()
                    if (found != mixedFound
                        || (found && lineAnchorCaptures(scalar) != lineAnchorCaptures(mixed))
                    ) {
                        throw AssertionError(
                            ("Line anchor engine mismatch: regex="
                                    + regex
                                    + " region=["
                                    + start
                                    + ","
                                    + end
                                    + "] input="
                                    + input
                                    + " anchoring="
                                    + anchoring
                                    + " transparent="
                                    + transparent
                                    + " find="
                                    + find)
                        )
                    }
                } while (find && found)
            }
        }

        private fun lineAnchorCaptures(matcher: Matcher): MutableList<String?> {
            val captures: MutableList<String?> = ArrayList<String?>()
            for (group in 0..matcher.groupCount()) {
                captures.add(matcher.start(group).toString() + "-" + matcher.end(group) + ":" + matcher.group(group))
            }
            return captures
        }

        private fun compareRegionLocalScalarModel(data: FuzzedDataProvider) {
            val regex: String =
                REGION_LOCAL_SCALAR_ATOMS.get(data.consumeInt(0, REGION_LOCAL_SCALAR_ATOMS.size - 1))
            val prefix: String = SCALAR_CONTEXTS.get(data.consumeInt(0, SCALAR_CONTEXTS.size - 1))
            val suffix: String? = SCALAR_CONTEXTS.get(data.consumeInt(0, SCALAR_CONTEXTS.size - 1))
            val exposedSurrogate = if (data.consumeBoolean()) "\uD83D" else "\uDC4D"
            val input = prefix + exposedSurrogate + suffix
            val start = prefix.length
            val end = start + 1
            val expected = regex != "\\P{Cs}" && regex != "[^\\p{Cs}]"
            val matcher =
                Pattern.compile(regex)
                    .matcher(input)
                    .region(start, end)
                    .useTransparentBounds(data.consumeBoolean())
            if (matcher.matches() != expected) {
                throw AssertionError("Region-local scalar matches mismatch: " + regex)
            }
            matcher.reset(input).region(start, end)
            if (matcher.find() != expected) {
                throw AssertionError("Region-local scalar find mismatch: " + regex)
            }
            if (expected && (matcher.start() != start || matcher.end() != end)) {
                throw AssertionError("Region-local scalar match crossed the region: " + regex)
            }
            if (expected) {
                for (group in 1..matcher.groupCount()) {
                    if (matcher.start(group) != start || matcher.end(group) != end || (exposedSurrogate != matcher.group(
                            group
                        ))
                    ) {
                        throw AssertionError("Region-local scalar capture mismatch: " + regex)
                    }
                }
            }
        }

        private fun compareSplitSurrogateBoundaryModel(data: FuzzedDataProvider) {
            val prefix: String = SCALAR_CONTEXTS.get(data.consumeInt(0, SCALAR_CONTEXTS.size - 1))
            val suffix: String? = SCALAR_CONTEXTS.get(data.consumeInt(0, SCALAR_CONTEXTS.size - 1))
            val input = prefix + "\uD801\uDC00" + suffix
            val start = prefix.length
            val regex = if (data.consumeBoolean()) "(?U)\\b." else "(?U)\\B."
            val expected = regex == "(?U)\\B."
            if (Pattern.compile(regex).matcher(input).region(start, start + 1).matches() != expected) {
                throw AssertionError("Region-local Unicode word boundary mismatch: " + regex)
            }
        }

        private fun compareMixedGraphemeScalarModel(data: FuzzedDataProvider) {
            val pattern: MixedGraphemeScalarPattern =
                MIXED_GRAPHEME_SCALAR_ATOMS.get(data.consumeInt(0, MIXED_GRAPHEME_SCALAR_ATOMS.size - 1))
            val prefix: String = SCALAR_CONTEXTS.get(data.consumeInt(0, SCALAR_CONTEXTS.size - 1))
            val suffix: String? = SCALAR_CONTEXTS.get(data.consumeInt(0, SCALAR_CONTEXTS.size - 1))
            val input = prefix + "\uD83D\uDC4D" + suffix
            val start = prefix.length
            val compiled = Pattern.compile(pattern.regex)
            if (!compiled.matcher(input).region(start, start + 1).matches()) {
                throw AssertionError(
                    "Mixed grapheme/scalar full match crossed region: " + pattern.regex
                )
            }
            for (find in booleanArrayOf(false, true)) {
                val matcher = compiled.matcher(input).region(start, start + 1)
                if (!(if (find) matcher.find() else matcher.lookingAt()) || matcher.start() != start || matcher.end() != start + pattern.firstMatchEndOffset) {
                    throw AssertionError("Mixed grapheme/scalar priority mismatch: " + pattern.regex)
                }
            }
        }

        private fun compareMixedAssertionModel(data: FuzzedDataProvider) {
            val prefix = (if (data.consumeBoolean()) "a" else " ").repeat(data.consumeInt(0, 8))
            val boundary = if (data.consumeBoolean()) "(?U:\\b)" else "(?U:\\B)"
            val scalar =
                if (data.consumeBoolean())
                    boundary + "."
                else
                    (if (data.consumeBoolean()) "(.)\\b{g}" else "\\b{g}(.)\\b{g}")
            val interior = data.consumeBoolean()
            val regex = if (interior) "(.)\\b{g}(.)" else Pattern.quote(prefix) + scalar
            val ordinary = Pattern.compile(regex)
            // No z occurs inside this region, so adding this alternative cannot create a match.
            val mixed =
                Pattern.compile(
                    if (data.consumeBoolean()) "(?:" + regex + ")|(?:\\Xz)" else "(?:\\Xz)|(?:" + regex + ")"
                )
            val suffix: String? = SCALAR_CONTEXTS.get(data.consumeInt(0, SCALAR_CONTEXTS.size - 1))
            val pair =
                mutableListOf<String>("\uD801\uDC00", "\uD83D\uDC4D", "\uD83C\uDDE6").get(data.consumeInt(0, 2))
            val input = prefix + pair.repeat(if (interior) 2 else 1) + suffix
            val start = if (interior) prefix.length + 1 else 0
            val end = prefix.length + (if (interior) 3 else 1)
            for (transparent in booleanArrayOf(false, true)) {
                for (operation in 0..2) {
                    val expected =
                        ordinary.matcher(input).region(start, end).useTransparentBounds(transparent)
                    val actual = mixed.matcher(input).region(start, end).useTransparentBounds(transparent)
                    val expectedMatch =
                        when (operation) {
                            0 -> expected.matches()
                            1 -> expected.lookingAt()
                            else -> expected.find()
                        }
                    val actualMatch =
                        when (operation) {
                            0 -> actual.matches()
                            1 -> actual.lookingAt()
                            else -> actual.find()
                        }
                    if (actualMatch != expectedMatch
                        || (actualMatch
                                && (actual.start() != expected.start() || actual.end() != expected.end()))
                    ) {
                        throw AssertionError("Unreachable grapheme branch changed assertion: " + regex)
                    }
                    if (actualMatch) {
                        for (group in 1..actual.groupCount()) {
                            if (actual.start(group) != expected.start(group)
                                || actual.end(group) != expected.end(group)
                            ) {
                                throw AssertionError("Unreachable grapheme branch changed capture: " + regex)
                            }
                        }
                    }
                }
            }
        }

        private fun compareGeneratedAssertionComposition(data: FuzzedDataProvider) {
            val left: String? = ASSERTION_LEFT_ATOMS.get(data.consumeInt(0, ASSERTION_LEFT_ATOMS.size - 1))
            val assertion: String =
                ASSERTION_PREDICATES.get(data.consumeInt(0, ASSERTION_PREDICATES.size - 1))
            val right: String? = ASSERTION_RIGHT_ATOMS.get(data.consumeInt(0, ASSERTION_RIGHT_ATOMS.size - 1))
            val regex = left + assertion + right
            val inputBuilder = StringBuilder()
            val fragments = data.consumeInt(0, 8)
            for (i in 0..<fragments) {
                inputBuilder.append(
                    ASSERTION_INPUT_FRAGMENTS.get(data.consumeInt(0, ASSERTION_INPUT_FRAGMENTS.size - 1))
                )
            }
            val input = inputBuilder.toString()
            val start = data.consumeInt(0, input.length)
            val end = data.consumeInt(start, input.length)
            val transparent = data.consumeBoolean()
            val anchoring = data.consumeBoolean()
            val ordinary = Pattern.compile(regex)
            val appended = Pattern.compile("(?:" + regex + ")|(?:\\Xz)")
            val prepended = Pattern.compile("(?:\\Xz)|(?:" + regex + ")")
            val context =
                (regex
                        + " input="
                        + input.codePoints().mapToObj<String?>(IntFunction { i: Int -> Integer.toHexString(i) })
                    .toList()
                        + " region="
                        + start
                        + ":"
                        + end
                        + " transparent="
                        + transparent
                        + " anchoring="
                        + anchoring)
            for (full in booleanArrayOf(false, true)) {
                val expected: Matcher = assertionMatcher(ordinary, input, start, end, transparent, anchoring)
                val matched = if (full) expected.matches() else expected.lookingAt()
                for (mixed in List.of<Pattern>(appended, prepended)) {
                    val actual: Matcher = assertionMatcher(mixed, input, start, end, transparent, anchoring)
                    val actualMatch = if (full) actual.matches() else actual.lookingAt()
                    if (matched != actualMatch) {
                        throw AssertionError(
                            ("Unreachable branch changed anchored assertion: "
                                    + context
                                    + " full="
                                    + full
                                    + " mixed="
                                    + mixed)
                        )
                    }
                    if (matched) {
                        if (actual.groupCount() != expected.groupCount()) {
                            throw AssertionError("Unreachable branch changed group count: " + context)
                        }
                        for (group in 0..expected.groupCount()) {
                            if (expected.start(group) != actual.start(group) || expected.end(group) != actual.end(group) || (expected.group(
                                    group
                                ) != actual.group(group))
                            ) {
                                throw AssertionError(
                                    ("Unreachable branch changed assertion capture: "
                                            + context
                                            + " full="
                                            + full
                                            + " mixed="
                                            + mixed
                                            + " group="
                                            + group)
                                )
                            }
                        }
                    }
                }
            }
            // Supplementary find() candidate policies are checked separately by explicit model cases.
            if (input.codePoints().allMatch(IntPredicate { codePoint: Int -> Character.isBmpCodePoint(codePoint) })) {
                val expected: MutableList<String?> =
                    assertionFindTrace(
                        assertionMatcher(ordinary, input, start, end, transparent, anchoring),
                        input.length
                    )
                for (mixed in List.of<Pattern>(appended, prepended)) {
                    val actual: MutableList<String?> =
                        assertionFindTrace(
                            assertionMatcher(mixed, input, start, end, transparent, anchoring), input.length
                        )
                    if (actual != expected) {
                        throw AssertionError(
                            ("Unreachable branch changed assertion find sequence: "
                                    + context
                                    + " mixed="
                                    + mixed
                                    + " expected="
                                    + expected
                                    + " actual="
                                    + actual)
                        )
                    }
                }
            }
        }

        private fun assertionMatcher(
            pattern: Pattern, input: String?, start: Int, end: Int, transparent: Boolean, anchoring: Boolean
        ): Matcher {
            return pattern
                .matcher(input)
                .region(start, end)
                .useTransparentBounds(transparent)
                .useAnchoringBounds(anchoring)
        }

        private fun assertionFindTrace(matcher: Matcher, inputLength: Int): MutableList<String?> {
            val trace: MutableList<String?> = ArrayList<String?>()
            for (step in 0..inputLength + 1) {
                if (!matcher.find()) {
                    trace.add("false")
                    return trace
                }
                for (group in 0..matcher.groupCount()) {
                    trace.add(matcher.start(group).toString() + ":" + matcher.end(group))
                }
            }
            throw AssertionError("Assertion find sequence did not exhaust: " + matcher.pattern())
        }

        private fun compareGraphemeRegions() {
            compareSafeReGraphemeModelCases()
            for (regex in GRAPHEME_REGION_REGEXES) {
                val pattern = FuzzSupport.compileOrSkip(regex, 0)
                if (pattern == null) {
                    continue
                }
                for (region in GRAPHEME_REGIONS) {
                    compareGraphemeRegion(pattern, region, false)
                    compareGraphemeRegion(pattern, region, true)
                }
            }
            for (regex in SPLIT_REGIONAL_GRAPHEME_REGEXES) {
                val pattern = FuzzSupport.compileOrSkip(regex, 0)
                if (pattern == null) {
                    continue
                }
                for (region in SPLIT_REGIONAL_GRAPHEME_REGIONS) {
                    compareGraphemeRegion(pattern, region, false)
                    compareGraphemeRegion(pattern, region, true)
                }
            }
            for (regex in TRANSPARENT_GRAPHEME_CONTEXT_REGEXES) {
                val pattern = FuzzSupport.compileOrSkip(regex, 0)
                if (pattern == null) {
                    continue
                }
                for (region in TRANSPARENT_GRAPHEME_CONTEXT_REGIONS) {
                    compareGraphemeRegion(pattern, region, false)
                    compareGraphemeRegion(pattern, region, true)
                }
            }
            for (regex in NON_ANCHORING_FINAL_CRLF_GRAPHEME_REGEXES) {
                val pattern = FuzzSupport.compileOrSkip(regex, 0)
                if (pattern == null) {
                    continue
                }
                for (region in NON_ANCHORING_FINAL_CRLF_GRAPHEME_REGIONS) {
                    compareNonAnchoringGraphemeRegion(pattern, region, false)
                    compareNonAnchoringGraphemeRegion(pattern, region, true)
                }
            }
        }

        private fun compareSafeReGraphemeModelCases() {
            for (testCase in SAFE_RE_GRAPHEME_MODEL_CASES) {
                val matcher =
                    Pattern.compile(testCase.regex)
                        .matcher(testCase.input)
                        .region(testCase.start, testCase.end)
                val actual: MutableList<String?> = ArrayList<String?>()
                while (matcher.find()) {
                    actual.add(matcher.start().toString() + "-" + matcher.end())
                }
                if (actual != testCase.expectedFinds) {
                    throw AssertionError(
                        ("SafeRE grapheme model mismatch"
                                + "\nRegex: "
                                + testCase.regex
                                + "\nRegion: ["
                                + testCase.start
                                + ","
                                + testCase.end
                                + "]"
                                + "\nExpected: "
                                + testCase.expectedFinds
                                + "\nActual: "
                                + actual)
                    )
                }
            }
        }

        private fun compareGraphemeRegion(
            pattern: CompiledPattern, region: GraphemeRegion, transparentBounds: Boolean
        ) {
            if (!transparentBounds) {
                for (operation in 0..2) {
                    val actual =
                        pattern.safeRePattern.matcher(region.input).region(region.start, region.end)
                    val expected =
                        pattern.safeRePattern.matcher(region.input!!.substring(region.start, region.end))
                    if (opaqueTrace(actual, operation, region.start, region.input.length)
                        != opaqueTrace(expected, operation, 0, region.input.length)
                    ) {
                        throw AssertionError(
                            "Opaque grapheme region differs from substring: " + pattern.regex
                        )
                    }
                }
                return
            }
            if (region.end > 0 && region.end < region.input!!.length && Character.isHighSurrogate(
                    region.input.get(
                        region.end - 1
                    )
                )
                && Character.isLowSurrogate(region.input.get(region.end))
            ) {
                // JDK-8394894: the JDK can consume beyond this region. Its answers are not a
                // consumption oracle here. The explicit unit matrix checks transparent assertion results;
                // this matrix checks confinement for all operations, captures, and successive searches.
                for (operation in 0..2) {
                    val bounded =
                        pattern
                            .safeRePattern
                            .matcher(region.input)
                            .region(region.start, region.end)
                            .useTransparentBounds(true)
                    var matched: Boolean
                    do {
                        matched =
                            when (operation) {
                                0 -> bounded.matches()
                                1 -> bounded.lookingAt()
                                else -> bounded.find()
                            }
                        if (matched) {
                            for (group in 0..bounded.groupCount()) {
                                if (bounded.start(group) >= 0
                                    && (bounded.start(group) < region.start || bounded.end(group) > region.end)
                                ) {
                                    throw AssertionError(
                                        "Transparent match escaped its region: " + pattern.regex
                                    )
                                }
                            }
                        }
                    } while (operation == 2 && matched)
                }
                return
            }
            val matcher = pattern.matcher(region.input)
            matcher.region(region.start, region.end).useTransparentBounds(transparentBounds)
            while (matcher.find()) {
                // Walk the whole sequence so extra or missing zero-width boundaries are compared.
            }
            matcher.reset().region(region.start, region.end).useTransparentBounds(transparentBounds)
            matcher.matches()
            matcher.reset().region(region.start, region.end).useTransparentBounds(transparentBounds)
            matcher.lookingAt()
        }

        private fun compareNonAnchoringGraphemeRegion(
            pattern: CompiledPattern, region: GraphemeRegion, transparentBounds: Boolean
        ) {
            val matcher = pattern.matcher(region.input)
            matcher
                .region(region.start, region.end)
                .useAnchoringBounds(false)
                .useTransparentBounds(transparentBounds)
            while (matcher.find()) {
                // Walk the whole sequence so extra or missing zero-width boundaries are compared.
            }
            matcher
                .reset()
                .region(region.start, region.end)
                .useAnchoringBounds(false)
                .useTransparentBounds(transparentBounds)
            matcher.matches()
            matcher
                .reset()
                .region(region.start, region.end)
                .useAnchoringBounds(false)
                .useTransparentBounds(transparentBounds)
            matcher.lookingAt()
        }
    }
}
