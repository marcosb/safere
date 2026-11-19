// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.List
import java.util.stream.Stream
import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.minus
import kotlin.collections.mutableListOf
import kotlin.collections.plus
import kotlin.intArrayOf
import kotlin.plus
import kotlin.sequences.minus
import kotlin.sequences.plus
import kotlin.text.StringBuilder
import kotlin.text.plus
import kotlin.text.substring
import kotlin.text.toByteArray

/** Substring and original-input anchor models from docs/OPAQUE_REGION_BOUNDS.md.  */
@DisabledForCrosscheck("Opaque substring semantics intentionally differ from JDK region behavior")
internal class OpaqueRegionContractTest {
    @Test
    fun anchoredOpaqueRegionsMatchIndependentSubstrings() {
        val patterns: MutableList<String?> = ArrayList<String?>(LOCAL_PATTERNS)
        patterns.addAll(
            mutableListOf<String?>(
                "^.*$",
                "\\A[\\s\\S]*\\z",
                "^\\X$",
                "^\\X\\b{g}$",
                "\\A\\X\\z",
                "\\X$",
                "\\X\\Z",
                "(?m)^",
                "(?m)$",
                "(?m)^\\X$",
                "(?d)(?m)^\\X$",
                "(.)?\\b{g}$"
            )
        )
        checkSubstrings(patterns, true)
    }

    @Test
    fun disablingAnchoringDoesNotExposeContextToOtherConstructs() {
        checkSubstrings(LOCAL_PATTERNS, false)
    }

    @Test
    fun opaqueSubstringsPreserveTheContractAcrossEnginePaths() {
        for (options in List.of<EnginePathOptions?>(
            EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .startAcceleration(false)
                .shiftDfa(false)
                .onePass(false)
                .dfa(false)
                .bitState(false)
                .build(),
            EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .shiftDfa(false)
                .onePass(false)
                .dfa(false)
                .build(),
            EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .shiftDfa(false)
                .onePass(false)
                .bitState(false)
                .build()
        )) {
            checkSubstrings(LOCAL_PATTERNS, true, options)
        }
    }

    @ParameterizedTest
    @MethodSource("graphemeCandidateCases")
    fun opaqueGraphemeRegionsPreserveSubstringCandidateStarts(
        regex: String?, visible: String, prefix: String, anchoring: Boolean
    ) {
        val pattern = Pattern.compile(regex)
        val input = prefix + visible + "!"
        val start = prefix.length
        for (operation in 0..2) {
            val region =
                pattern
                    .matcher(input)
                    .region(start, start + visible.length)
                    .useAnchoringBounds(anchoring)
            Assertions.assertThat<String?>(OpaqueRegionChecks.trace(region, operation, start))
                .`as`("%s regionStart=%s anchoring=%s operation=%s", regex, start, anchoring, operation)
                .isEqualTo(OpaqueRegionChecks.trace(pattern.matcher(visible), operation, 0))
        }
    }

    @Test
    fun scalarGraphemeSearchKeepsAnchorsInTheirConfiguredCoordinates() {
        val input = "\na😀😀\n"
        for (regex in mutableListOf<String?>("(?m)^(\\X?)(\\p{Cs})", "(\\X?)(\\p{Cs})(?m:$)")) {
            val pattern = Pattern.compile(regex)
            val anchored = pattern.matcher(input).region(2, 5)
            Assertions.assertThat(anchored.find()).`as`(regex).isTrue()
            Assertions.assertThat(anchored.start()).isEqualTo(2)
            Assertions.assertThat(anchored.end()).isEqualTo(5)
            Assertions.assertThat(anchored.group(1)).isEqualTo("😀")
            Assertions.assertThat(anchored.group(2)).isEqualTo("\uD83D")
            Assertions.assertThat(pattern.matcher(input).region(2, 5).useAnchoringBounds(false).find())
                .`as`(regex)
                .isFalse()
        }
    }

    @Test
    fun scalarClassSearchStateSurvivesRegionChangesAndIsClearedForNewInputsAndPatterns() {
        val pattern = Pattern.compile("\\X?\\uDE00")
        val matcher = pattern.matcher("")
        for (visible in mutableListOf<String?>("😀😀\uDE00!", "😀😀!", "😀\uD83D!", "\uDE00😀!")) {
            val input = "a" + visible + "!"
            matcher.reset(input)
            for (direction in intArrayOf(1, -1)) {
                for (step in 1..<input.length) {
                    val end = if (direction == 1) step else input.length - step
                    for (start in 1..end) {
                        matcher.region(start, end)
                        Assertions.assertThat<String?>(OpaqueRegionChecks.trace(matcher, 2, start))
                            .isEqualTo(
                                OpaqueRegionChecks.trace(pattern.matcher(input.substring(start, end)), 2, 0)
                            )
                    }
                }
            }
        }
        val input = StringBuilder("a😀😀\uDE00!")
        matcher.reset(input).region(1, input.length)
        Assertions.assertThat(matcher.find()).isTrue()
        input.setCharAt(input.length - 2, '!')
        matcher.reset().region(1, input.length)
        Assertions.assertThat(matcher.find()).isFalse()
        matcher.usePattern(Pattern.compile("\\X?\\p{Cs}")).region(1, 4)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(1)
        Assertions.assertThat(matcher.end()).isEqualTo(4)
    }

    @Test
    fun disabledAnchorsUseOriginalInputWhileOtherAssertionsStayOpaque() {
        for (regex in mutableListOf<String?>("cat", "\\bcat\\b", "\\b{g}cat\\b{g}")) {
            Assertions.assertThat(
                Pattern.compile(regex)
                    .matcher("xcaty")
                    .region(1, 4)
                    .useAnchoringBounds(false)
                    .matches()
            )
                .`as`(regex)
                .isTrue()
        }
        for (regex in mutableListOf<String?>("^cat$", "\\Acat\\z", "cat\\Z")) {
            Assertions.assertThat(
                Pattern.compile(regex)
                    .matcher("xcaty")
                    .region(1, 4)
                    .useAnchoringBounds(false)
                    .matches()
            )
                .`as`(regex)
                .isFalse()
        }
        for (regex in mutableListOf<String?>("(?m)^cat$", "cat$", "cat\\Z")) {
            Assertions.assertThat(
                Pattern.compile(regex)
                    .matcher("x\ncat\n")
                    .region(2, 5)
                    .useAnchoringBounds(false)
                    .matches()
            )
                .`as`(regex)
                .isTrue()
        }
        Assertions.assertThat(Pattern.compile("\\Acat\\z").matcher("cat").useAnchoringBounds(false).matches())
            .isTrue()
        Assertions.assertThat(
            Pattern.compile("(?m)^")
                .matcher("\r\n")
                .region(1, 2)
                .useAnchoringBounds(false)
                .lookingAt()
        )
            .isFalse()
        Assertions.assertThat(
            Pattern.compile("\\b{g}")
                .matcher("\r\n")
                .region(1, 2)
                .useAnchoringBounds(false)
                .lookingAt()
        )
            .isTrue()
    }

    @Test
    fun repeatedRegionsAndInputResetsKeepTheCorrectIndependentText() {
        val pattern = Pattern.compile("(\\X)|(.)")
        val actual = pattern.matcher("")
        for (input in INPUTS) {
            actual.reset(input)
            for (repeat in 0..1) {
                for (start in 0..input.length) {
                    for (end in start..input.length) {
                        actual.region(start, end)
                        Assertions.assertThat<String?>(OpaqueRegionChecks.trace(actual, 2, start))
                            .isEqualTo(
                                OpaqueRegionChecks.trace(pattern.matcher(input.substring(start, end)), 2, 0)
                            )
                    }
                }
            }
            actual.reset()
            Assertions.assertThat<String?>(OpaqueRegionChecks.trace(actual, 2, 0))
                .isEqualTo(OpaqueRegionChecks.trace(pattern.matcher(input), 2, 0))
        }

        val mutable = StringBuilder("x\uD83D\uDE00y")
        actual.reset(mutable).region(1, 2)
        Assertions.assertThat(actual.matches()).isTrue()
        Assertions.assertThat(actual.group()).isEqualTo("\uD83D")
        mutable.replace(1, 3, "ab")
        actual.reset().region(1, 2)
        Assertions.assertThat(actual.matches()).isTrue()
        Assertions.assertThat(actual.group()).isEqualTo("a")
    }

    @Test
    fun transparentAssertionContextDoesNotAllowConsumptionPastTheRegion() {
        val input = "\uD83D\uDC4D\uD83C\uDFFB"
        for (regex in mutableListOf<String?>("\\X\\b{g}", "\\b{g}\\X\\b{g}")) {
            val pattern = Pattern.compile(regex)
            Assertions.assertThat(pattern.matcher(input).region(1, 3).useTransparentBounds(true).matches())
                .`as`(regex)
                .isFalse()
            Assertions.assertThat(pattern.matcher(input).region(1, 3).useTransparentBounds(true).lookingAt())
                .`as`(regex)
                .isFalse()
            Assertions.assertThat(pattern.matcher(input).region(1, 3).useTransparentBounds(true).find())
                .`as`(regex)
                .isFalse()
        }
    }

    @Test
    fun originalInputAnchorsDoNotWidenOpaqueGraphemeContext() {
        val startsAfterLineFeed =
            Pattern.compile("(?m)^\\X\\b{g}")
                .matcher("\n\uD83D\uDE00\n")
                .region(1, 2)
                .useAnchoringBounds(false)
        Assertions.assertThat(startsAfterLineFeed.matches()).isTrue()
        Assertions.assertThat(startsAfterLineFeed.group()).isEqualTo("\uD83D")
        val startsInsidePair =
            Pattern.compile("(?m)^\\X\\b{g}")
                .matcher("\n\uD83D\uDE00\n")
                .region(2, 3)
                .useAnchoringBounds(false)
        Assertions.assertThat(startsInsidePair.matches()).isFalse()
        val endsBeforeLineFeed =
            Pattern.compile("\\b{g}\\X(?m:$)")
                .matcher("\uD83D\uDE00\n")
                .region(1, 2)
                .useAnchoringBounds(false)
        Assertions.assertThat(endsBeforeLineFeed.matches()).isTrue()
        Assertions.assertThat(endsBeforeLineFeed.group()).isEqualTo("\uDE00")
    }

    @Test
    fun sharedByteEngineKeepsOriginalAnchorsSeparateFromLocalGraphemeCoordinates() {
        val bytes = "x\uD83C\uDDE6\uD83C\uDDE7\uD83C\uDDE8y".toByteArray(StandardCharsets.UTF_8)
        val pattern = Pattern.compile("\\b{g}\\X\\b{g}($)?")
        // Exercise the shared engine directly; Utf8Matcher does not expose bounds switches.
        val actual = Matcher(pattern, Utf8InputScanner(bytes)).useAnchoringBounds(false)
        for (start in intArrayOf(1, 5, 9)) {
            actual.region(start, 13)
            val expected =
                pattern.matcher(Utf8Input.validated(Arrays.copyOfRange(bytes, start, 13)))
            val expectedBounds: MutableList<Int?> = ArrayList<Int?>()
            while (expected.find()) {
                expectedBounds.add(expected.start())
                expectedBounds.add(expected.end())
            }
            val actualBounds: MutableList<Int?> = ArrayList<Int?>()
            while (actual.find()) {
                actualBounds.add(actual.start() - start)
                actualBounds.add(actual.end() - start)
            }
            Assertions.assertThat<Int?>(actualBounds).isEqualTo(expectedBounds)
        }
    }

    companion object {
        private val INPUTS: MutableList<String> = mutableListOf<String?>(
            "",
            "xcaty",
            "x\uD801\uDC00b\r\n",
            "\uDC4D\uD801\uDC00\r\uD801\uDC00b\r\n\u0085",
            "x\ncat\ny",
            "\r\na",
            "\r\nb",
            "\r\r\n",
            "a\u0085b",
            "\uD83D\uDE00\u0301",
            "x\uDE00\u0301",
            "\uD83D\uDE00\uD83C\uDFFB",
            "\uD83C\uDDE6\uD83C\uDDE7\uD83C\uDDE8",
            "\uD83D\uDC69\u200D\uD83D\uDCBB",
            "\u0600\uD83D\uDC69\u200D\uD83D\uDCBB",
            "a\u0301\u0300b",
            "\u0915\u094D\u0915",
            "\u1100\u1161\u11A8",
            "\uD83Dx\uDE00"
        )

        private val LOCAL_PATTERNS = mutableListOf<String?>(
            ".",
            ".*",
            "(.)*",
            "[\\s\\S]+",
            "\\p{Cs}+",
            "\\P{Cs}+",
            "\\uD83D",
            "(\\uDE00)",
            "[\\uD83D]",
            "a\\uD83D",
            "\\x{1F600}",
            "\\b.",
            "(?U:\\b).",
            "(?U:\\B).",
            "(?U:\\b)a?",
            "(?U:\\B)a?",
            "\\ba?",
            "\\X",
            "(\\X)*",
            "\\b{g}",
            ".\\b{g}",
            "(.)\\b{g}(.)?",
            "[\\s\\S]\\b{g}a?",
            "\\X\\b{g}",
            "\\b{g}\\X",
            "\\b{g}\\X\\b{g}",
            "(\\X)|(.)",
            "(.)|(\\X)",
            "(?:.\\b{g})|(?:\\Xz)",
            "(?:\\Xz)|(?:.\\b{g})",
            "\\X|",
            "|\\X"
        )

        private fun graphemeCandidateCases(): Stream<Arguments?> {
            val cases = Stream.builder<Arguments?>()
            for (regex in mutableListOf<String?>(
                "\\uDE00\\X?",
                "\\p{Cs}\\X?",
                "[a\\uDE00]\\X?",
                "(?<half>\\p{Cs})(?<cluster>\\X*)",
                "\\X?\\p{Cs}",
                "\\X\\p{Cs}",
                ".\\uDE00\\X?",
                "a?\\uDE00\\X?",
                "[a\\uDE00]{2}\\X?",
                "(\\uDE00)(\\X?)",
                "\\X?\\uDE00",
                "(?:\\p{Cs})|(?:\\Xz)",
                "(?:\\Xz)|(?:\\p{Cs})",
                "(?:\\uDE00\\X?)|(?m:^Q)",
                "(?:\\p{Cs}\\X?)|(?m:^Q)",
                "(?:\\X?\\uDE00)|(?m:^Q)"
            )) {
                for (visible in mutableListOf<String?>(
                    "😀",
                    "😀😀",
                    "😀\uDE00",
                    "\uDE00😀",
                    "x😀y",
                    "\uDE00",
                    "😀\uD83D",
                    "😀\uDE00!",
                    "😀\uD83D!"
                )) {
                    for (prefix in mutableListOf<String?>("", "a", "ab", "\uD83D")) {
                        for (anchoring in mutableListOf<Boolean?>(false, true)) {
                            cases.add(Arguments.of(regex, visible, prefix, anchoring))
                        }
                    }
                }
            }
            return cases.build()
        }

        private fun checkSubstrings(
            patterns: MutableList<String?>,
            anchoring: Boolean,
            options: EnginePathOptions? = EnginePathOptions.allEnabled()
        ) {
            for (regex in patterns) {
                val pattern = Pattern.compile(regex, 0, options)
                val reference = Pattern.compile(regex)
                for (input in INPUTS) {
                    for (start in 0..input.length) {
                        for (end in start..input.length) {
                            for (operation in 0..2) {
                                val actual =
                                    pattern.matcher(input).region(start, end).useAnchoringBounds(anchoring)
                                val expected = reference.matcher(input.substring(start, end))
                                Assertions.assertThat<String?>(OpaqueRegionChecks.trace(actual, operation, start))
                                    .`as`(
                                        "%s input=%s region=%s:%s anchoring=%s operation=%s",
                                        regex, input, start, end, anchoring, operation
                                    )
                                    .isEqualTo(OpaqueRegionChecks.trace(expected, operation, 0))
                            }
                        }
                    }
                }
            }
        }
    }
}
