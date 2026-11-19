// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.lang.String
import java.nio.charset.StandardCharsets
import java.util.function.Consumer
import kotlin.arrayOf
import kotlin.booleanArrayOf

/** Linear-time regression tests for grapheme cluster matching.  */
@DisabledForCrosscheck("java.util.regex is not the SafeRE linear-time engine")
@Tag("work-counter")
internal class GraphemeLinearTimeTest {
    @Test
    @DisplayName("repeated find() over grapheme clusters reuses per-input context")
    fun repeatedFindOverGraphemeClustersReusesPerInputContext() {
        val pattern = Pattern.compile("\\X")

        assertFourXInputStaysNearLinear(
            "repeated find() over grapheme clusters",
            "a".repeat(20000),
            "a".repeat(80000),
            Consumer { text: String ->
                val matcher = pattern.matcher(text)
                var count = 0
                while (matcher.find()) {
                    count++
                }
                Assertions.assertThat(count).isEqualTo(text.length)
            })
    }

    @Test
    @DisplayName("JDK-compatible low-surrogate search positions stay near-linear")
    fun lowSurrogateSearchPositionsStayNearLinear() {
        val pattern = Pattern.compile(".*\\b{g}z")

        assertFourXInputStaysNearLinear(
            "low-surrogate search-position miss",
            "\uDC00".repeat(20000),
            "\uDC00".repeat(80000),
            Consumer { text: String -> Assertions.assertThat(pattern.matcher(text).find()).isFalse() })
    }

    @Test
    @DisplayName("regional-indicator grapheme boundary misses stay near-linear")
    fun regionalIndicatorBoundaryMissesStayNearLinear() {
        val pattern = Pattern.compile("\\b{g}z")

        assertFourXInputStaysNearLinear(
            "regional-indicator boundary miss",
            "\uD83C\uDDE6".repeat(20000),
            "\uD83C\uDDE6".repeat(80000),
            Consumer { text: String -> Assertions.assertThat(pattern.matcher(text).find()).isFalse() })
    }

    @Test
    @DisplayName("failed unanchored \\X suffix misses stay near-linear")
    fun failedUnanchoredGraphemeSuffixMissesStayNearLinear() {
        val pattern = Pattern.compile("\\Xz")

        assertFourXInputStaysNearLinear(
            "failed unanchored \\X suffix miss",
            "a" + "\u0301".repeat(20000),
            "a" + "\u0301".repeat(80000),
            Consumer { text: String -> Assertions.assertThat(pattern.matcher(text).find()).isFalse() })
        assertFourXInputStaysNearLinear(
            "failed unanchored regional-indicator \\X suffix miss",
            "\uD83C\uDDE6".repeat(20000),
            "\uD83C\uDDE6".repeat(80000),
            Consumer { text: String -> Assertions.assertThat(pattern.matcher(text).find()).isFalse() })
    }

    @Test
    fun mixedRuleInteractionsAndExplicitBoundariesStayLinear() {
        val unit =
            String.join(
                "",
                "\u0600",
                "👩\u0300\u200D👩",
                "\u0915\u094D\u200D\u0915",
                "🇦🇧",
                "\u1100\u1160\u11A8",
                "\r",
                "\n"
            )
        for (regex in arrayOf<kotlin.String>("\\X", "(?s).\\b{g}", "\\b{g}\\X\\b{g}")) {
            val pattern = Pattern.compile(regex)
            for (utf8 in booleanArrayOf(false, true)) {
                assertFourXInputStaysNearLinear(
                    "mixed Unicode rules " + regex + ", UTF-8=" + utf8,
                    unit.repeat(500),
                    unit.repeat(2000),
                    Consumer { text: kotlin.String ->
                        if (utf8) {
                            val matcher = pattern.matcher(Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8)))
                            while (matcher.find()) {
                                Assertions.assertThat(matcher.end()).isGreaterThan(matcher.start())
                            }
                        } else {
                            val matcher = pattern.matcher(text)
                            while (matcher.find()) {
                                Assertions.assertThat(matcher.end()).isGreaterThan(matcher.start())
                            }
                        }
                    })
            }
        }
    }

    @Test
    fun cachedContextQueriesWithChangingVisibleStartsStayLinear() {
        for (unit in arrayOf<kotlin.String>("🇦", "\u094D", "\u0300")) {
            assertFourXInputStaysNearLinear(
                "cached context for " + unit,
                unit.repeat(2000),
                unit.repeat(8000),
                Consumer { middle: kotlin.String ->
                    val text = "👩\u0915" + middle + "\u200D\u0915"
                    for (scanner in arrayOf<InputScanner>(
                        StringInputScanner(text), Utf8InputScanner(
                            text.toByteArray(
                                StandardCharsets.UTF_8
                            )
                        )
                    )) {
                        val context = GraphemeSupport.Context.create(scanner, true)
                        val pos = InputScanner.position(scanner.decodeBackward(scanner.length()))
                        // Each query changes the clipping threshold, but the caches are built once.
                        var start = 0
                        while (start < pos
                        ) {
                            context.hasEvenRegionalIndicatorsBefore(pos, start)
                            context.hasExtendedPictographicBefore(pos, start)
                            context.hasIndicConjunctLinkerBefore(pos, start)
                            context.hasIndicConjunctSequenceBefore(pos, start)
                            start = InputScanner.position(scanner.decodeForward(start))
                        }
                    }
                })
        }
    }

    @Test
    fun immediateOpaqueGraphemeMatchDoesNotScanTheWholeRegion() {
        val pattern = Pattern.compile("\\X")
        val small = "a".repeat(256)
        val large = "a".repeat(65536)
        for (find in booleanArrayOf(false, true)) {
            val match =
                Consumer { input: kotlin.String? ->
                    val matcher = pattern.matcher(input).region(1, input!!.length)
                    Assertions.assertThat(if (find) matcher.find() else matcher.lookingAt()).isTrue()
                    Assertions.assertThat(matcher.start()).isEqualTo(1)
                    Assertions.assertThat(matcher.end()).isEqualTo(2)
                }
            val smallWork = WorkCounter.countForTesting(Runnable { match.accept(small) })
            val largeWork = WorkCounter.countForTesting(Runnable { match.accept(large) })
            Assertions.assertThat(smallWork).isPositive()
            Assertions.assertThat(largeWork)
                .`as`("first match must not copy or scan the full region")
                .isLessThanOrEqualTo(smallWork * 2)
        }
    }

    @Test
    fun opaqueFindSequencesReuseTheirRegionAndGraphemeContext() {
        for (regex in arrayOf<kotlin.String>("\\X", "\\b{g}\\X\\b{g}", "\\X(?m:$)?")) {
            val pattern = Pattern.compile(regex)
            for (anchoring in booleanArrayOf(true, false)) {
                assertFourXInputStaysNearLinear(
                    "opaque repeated find: " + regex + " anchoring=" + anchoring,
                    "\uD83C\uDDE6\uD83C\uDDE7".repeat(500),
                    "\uD83C\uDDE6\uD83C\uDDE7".repeat(2000),
                    Consumer { middle: kotlin.String ->
                        val matcher =
                            pattern
                                .matcher("x" + middle + "y")
                                .region(1, middle.length + 1)
                                .useAnchoringBounds(anchoring)
                        var matches = 0
                        while (matcher.find()) {
                            Assertions.assertThat(matcher.start()).isGreaterThanOrEqualTo(1)
                            Assertions.assertThat(matcher.end()).isLessThanOrEqualTo(middle.length + 1)
                            matches++
                        }
                        Assertions.assertThat(matches).isPositive()
                    })
            }
        }
    }

    companion object {
        private fun assertFourXInputStaysNearLinear(
            scenario: kotlin.String?,
            smallerInput: kotlin.String?,
            largerInput: kotlin.String?,
            task: Consumer<kotlin.String>
        ) {
            task.accept(smallerInput!!)
            task.accept(largerInput!!)
            val smallerWork = WorkCounter.countForTesting(Runnable { task.accept(smallerInput) })
            val largerWork = WorkCounter.countForTesting(Runnable { task.accept(largerInput) })

            Assertions.assertThat(smallerWork).`as`("%s: smaller input should use grapheme work", scenario).isPositive()
            Assertions.assertThat(largerWork)
                .`as`(
                    "%s: 4x input should stay near-linear, smallerWork=%d largerWork=%d",
                    scenario, smallerWork, largerWork
                )
                .isLessThan(smallerWork * 5)
        }
    }
}
