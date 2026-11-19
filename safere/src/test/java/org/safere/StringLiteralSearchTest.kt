// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.*

/**
 * [StringLiteralSearch] must be indistinguishable from [String.indexOf].
 * It is a pure throughput change, so every test here is a differential against the JDK.
 */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class StringLiteralSearchTest {
    @Test
    fun agreesWithJdkOnRandomInputs() {
        // A small alphabet makes accidental near-matches common, which is what exercises the
        // candidate-verification path rather than just the scan.
        val random = Random(20260914L)
        val alphabet = "abcq:-/x"
        for (trial in 0..2999) {
            val textLength = random.nextInt(40)
            val text = StringBuilder(textLength)
            for (i in 0..<textLength) {
                text.append(alphabet.get(random.nextInt(alphabet.length)))
            }
            val literalLength = 1 + random.nextInt(5)
            val literal = StringBuilder(literalLength)
            for (i in 0..<literalLength) {
                literal.append(alphabet.get(random.nextInt(alphabet.length)))
            }
            assertAgreesAtEveryStart(text.toString(), literal.toString())
        }
    }

    @Test
    fun agreesWithJdkWhenTheAnchorIsDenseEnoughToExhaustTheStrikeBudget() {
        // The rarity model says 'q' is rare. This haystack says otherwise, which is exactly the case
        // adaptive defeat exists for: every position is a candidate and every one fails.
        val literal = "qx"
        Assertions.assertThat(StringLiteralSearch.anchorOffset(literal))
            .isNotEqualTo(StringLiteralSearch.NO_ANCHOR)
        val dense = "q".repeat(5000)
        Assertions.assertThat(anchored(dense, literal, 0)).isEqualTo(-1)
        Assertions.assertThat(anchored(dense + "qx", literal, 0)).isEqualTo(dense.length)
        // A match beyond the point where the budget is exhausted must still be found. The prefix has
        // to clear MIN_ANCHORED_WINDOW, or the scan never anchors and the budget is never spent.
        val prefix = 4 * StringLiteralSearch.MIN_ANCHORED_WINDOW
        Assertions.assertThat(anchored("q".repeat(prefix) + "qx" + "q".repeat(200), literal, 0)).isEqualTo(prefix)
    }

    @Test
    fun agreesWithJdkWhenTheAnchorRecursJustInsideTheDensityStride() {
        // The shape the strike charge is calibrated on: an anchor the rarity model rates highly, which
        // nevertheless recurs every 46 characters -- the stride measured for this literal on a log
        // line, and inside MIN_DENSITY_STRIDE -- so every observation is a dense one and none of them
        // verifies. How soon the scan concedes is not visible in the result, only in the benchmark;
        // what is pinned here is that conceding still finds everything the JDK finds.
        val literal = "error:["
        val offset = StringLiteralSearch.anchorOffset(literal)
        Assertions.assertThat(offset).isNotEqualTo(StringLiteralSearch.NO_ANCHOR)
        val unit = StringLiteralSearch.anchorAt(literal, offset).toString() + "y".repeat(45)
        val noise = unit.repeat(64)
        Assertions.assertThat(noise.size).isGreaterThan(StringLiteralSearch.MIN_ANCHORED_WINDOW)
        Assertions.assertThat(anchored(noise, literal, 0)).isEqualTo(-1)
        Assertions.assertThat(anchored(noise + literal, literal, 0)).isEqualTo(noise.size)
        val surrounded = noise + literal + noise
        var from = 0
        while (from < surrounded.size) {
            assertAgreesAt(surrounded, literal, from)
            from += 137
        }
    }

    @Test
    fun agreesWithJdkWhenTheAnchorClumpsAndThenThinsOut() {
        // Repayment has to keep a mostly sparse anchor alive, so a handful of dense observations
        // followed by a long sparse run must not be treated the same as a uniformly dense anchor.
        val literal = "error:["
        val anchor = StringLiteralSearch.anchorAt(literal, StringLiteralSearch.anchorOffset(literal))
        val text =
            ((anchor.toString() + "y".repeat(9)).repeat(5) + (anchor.toString() + "y".repeat(400)).repeat(3)).repeat(4)
        Assertions.assertThat(text.length).isGreaterThan(StringLiteralSearch.MIN_ANCHORED_WINDOW)
        Assertions.assertThat(anchored(text, literal, 0)).isEqualTo(-1)
        assertAgreesAt(text + literal, literal, 0)
        assertAgreesAt(text + literal + text, literal, 0)
    }

    @Test
    fun agreesWithJdkOnWindowsTooShortToAnchor() {
        // Below MIN_ANCHORED_WINDOW the search delegates rather than anchoring; it still has to be
        // indistinguishable from the JDK, including when the window shrinks only because fromIndex has
        // advanced into a long text.
        val literal = "qx"
        val text = "q".repeat(StringLiteralSearch.MIN_ANCHORED_WINDOW * 2) + "qx" + "q".repeat(50)
        var from = text.length - 1
        while (from >= 0) {
            assertAgreesAt(text, literal, from)
            from -= 7
        }
        assertAgreesAt("q".repeat(StringLiteralSearch.MIN_ANCHORED_WINDOW - 1) + "qx", literal, 0)
    }

    @Test
    fun agreesWithJdkOnOverlappingAndRepeatedLiterals() {
        assertAgreesAtEveryStart("aqaqaqaqaq", "aqa")
        assertAgreesAtEveryStart("qqqqqqqq", "qqq")
        assertAgreesAtEveryStart("://://://", "://")
        assertAgreesAtEveryStart("xxxxxxxxxx", "xy")
    }

    @Test
    fun agreesWithJdkOnBoundaryConditions() {
        Assertions.assertThat(anchored("", "qx", 0)).isEqualTo("".indexOf("qx", 0))
        Assertions.assertThat(anchored("q", "qx", 0)).isEqualTo("q".indexOf("qx", 0))
        Assertions.assertThat(anchored("qx", "qx", 0)).isEqualTo(0)
        Assertions.assertThat(anchored("qx", "qx", 1)).isEqualTo(-1)
        Assertions.assertThat(anchored("qx", "qx", 99)).isEqualTo(-1)
        Assertions.assertThat(anchored("qx", "qx", -5)).isEqualTo("qx".indexOf("qx", -5))
        Assertions.assertThat(anchored("abc", "abcd", 0)).isEqualTo(-1)
    }

    @Test
    fun agreesWithJdkOnNonAsciiText() {
        // A non-ASCII literal has no anchor, but a non-ASCII haystack with an ASCII literal does, and
        // the haystack is UTF16-coded there, which is a different JDK kernel.
        assertAgreesAtEveryStart("日本語 q: 日本語", "q:")
        assertAgreesAtEveryStart("ünïcödé-qx-ünïcödé", "qx")
        assertAgreesAtEveryStart("日本語日本語", "日本")
        assertAgreesAtEveryStart("aa\uD83D\uDE00bb", "\uD83D\uDE00b")
    }

    @Test
    fun declinesToAnchorWhereAnchoringCannotPayOff() {
        Assertions.assertThat(StringLiteralSearch.anchorOffset(null)).isEqualTo(StringLiteralSearch.NO_ANCHOR)
        Assertions.assertThat(StringLiteralSearch.anchorOffset("")).isEqualTo(StringLiteralSearch.NO_ANCHOR)
        // The JDK already routes a single-character needle to its own character kernel.
        Assertions.assertThat(StringLiteralSearch.anchorOffset("q")).isEqualTo(StringLiteralSearch.NO_ANCHOR)
        // No ASCII character to anchor on; the rarity model has nothing to say.
        Assertions.assertThat(StringLiteralSearch.anchorOffset("日本")).isEqualTo(StringLiteralSearch.NO_ANCHOR)
        // Every character is common enough to be a poisonous anchor.
        Assertions.assertThat(StringLiteralSearch.anchorOffset("   ")).isEqualTo(StringLiteralSearch.NO_ANCHOR)
    }

    @Test
    fun anchorsOnTheRarestCharacter() {
        Assertions.assertThat(StringLiteralSearch.anchorOffset("id:")).isEqualTo(2)
        Assertions.assertThat(StringLiteralSearch.anchorOffset("error:")).isEqualTo(5)
        Assertions.assertThat(StringLiteralSearch.anchorAt("id:", 2)).isEqualTo(':')
    }

    /**
     * Literals whose rarest character is also their last one, searched over haystacks that carry
     * false candidates — an anchor occurrence that is not part of a match.
     * 
     * 
     * Both halves matter, and the rest of the suite has neither. When the anchor is the last
     * character, the candidate pre-check re-reads the byte the anchor scan just returned, so it
     * always passes and every candidate runs a full verification. A haystack whose only anchor
     * occurrences are real matches then never reaches that verification with anything to reject.
     */
    @Test
    fun agreesWithJdkWhereTheAnchorIsTheLiteralsLastCharacter() {
        for (literal in arrayOf<String>("id:", "record:", "users/")) {
            Assertions.assertThat(StringLiteralSearch.anchorOffset(literal)).isEqualTo(literal.length - 1)
        }
        assertAgreesAtEveryStart("other:x id:7 more:y", "id:")
        assertAgreesAtEveryStart("tag:a record:b tag:c", "record:")
        assertAgreesAtEveryStart("/a/ users/ /b/", "users/")
        // The anchor present without the rest of the literal, and the rest of the literal present
        // without the anchor.
        assertAgreesAtEveryStart("rrrr: record:", "record:")
        assertAgreesAtEveryStart("recorx: record:", "record:")
        // A match at the very start of the text, and one whose anchor is its last character.
        assertAgreesAtEveryStart("record: trailing", "record:")
        assertAgreesAtEveryStart("leading record:", "record:")
    }

    @Test
    fun declinedLiteralsStillAgreeWithJdk() {
        assertAgreesAtEveryStart("hello world", "o")
        assertAgreesAtEveryStart("a b  c   d", "  ")
        assertAgreesAtEveryStart("日本語テキスト", "テキ")
    }

    @Test
    fun routesOnlyOneCharacterAsciiLiteralsToTheCharacterKernel() {
        Assertions.assertThat(StringLiteralSearch.singleAsciiChar("-")).isEqualTo('-'.code)
        Assertions.assertThat(StringLiteralSearch.singleAsciiChar("\n")).isEqualTo('\n'.code)
        Assertions.assertThat(StringLiteralSearch.singleAsciiChar("\u0000")).isEqualTo(0)
        // 0x7f is the last character the two kernels agree on.
        Assertions.assertThat(StringLiteralSearch.singleAsciiChar("\u007f")).isEqualTo(0x7f)

        Assertions.assertThat(StringLiteralSearch.singleAsciiChar(null))
            .isEqualTo(StringLiteralSearch.NOT_SINGLE_ASCII)
        Assertions.assertThat(StringLiteralSearch.singleAsciiChar(""))
            .isEqualTo(StringLiteralSearch.NOT_SINGLE_ASCII)
        Assertions.assertThat(StringLiteralSearch.singleAsciiChar("ab"))
            .isEqualTo(StringLiteralSearch.NOT_SINGLE_ASCII)
        // Above ASCII String.indexOf(int, int) matches by code point, so it is a different search.
        Assertions.assertThat(StringLiteralSearch.singleAsciiChar("\u0080"))
            .isEqualTo(StringLiteralSearch.NOT_SINGLE_ASCII)
        Assertions.assertThat(StringLiteralSearch.singleAsciiChar("é"))
            .isEqualTo(StringLiteralSearch.NOT_SINGLE_ASCII)
        // One code point, but two chars, so not one character by the length test either.
        Assertions.assertThat(StringLiteralSearch.singleAsciiChar("\uD83D\uDE00"))
            .isEqualTo(StringLiteralSearch.NOT_SINGLE_ASCII)
    }

    @Test
    fun plannedSearchAgreesWithJdk() {
        // Single characters, which is what the dispatch is for.
        assertPlannedAgreesAtEveryStart("hello world", "o")
        assertPlannedAgreesAtEveryStart("---a---", "-")
        assertPlannedAgreesAtEveryStart("no hit here", "q")
        assertPlannedAgreesAtEveryStart("", "q")
        assertPlannedAgreesAtEveryStart("line\nbreak", "\n")
        // A haystack the JDK stores as UTF16, where the two kernels index differently internally.
        assertPlannedAgreesAtEveryStart("日本語-日本語", "-")
        // Characters the dispatch declines, which must still reach the anchored path unchanged.
        assertPlannedAgreesAtEveryStart("ünïcödé", "é")
        assertPlannedAgreesAtEveryStart("a q: b q: c", "q:")
        assertPlannedAgreesAtEveryStart("日本語テキスト", "テキ")
    }

    @Test
    fun plannedSearchAgreesWithJdkOnOutOfRangeStarts() {
        val text = "a-b-c"
        for (literal in arrayOf<String>("-", "q", "-b")) {
            for (from in intArrayOf(Int.MIN_VALUE, -7, -1, 6, 99, Int.MAX_VALUE)) {
                Assertions.assertThat(planned(text, literal, from))
                    .`as`("literal=%s fromIndex=%s", literal, from)
                    .isEqualTo(text.indexOf(literal, from))
            }
        }
    }

    @Test
    fun plannedSearchAgreesWithJdkOnRandomInputs() {
        val random = Random(20260918L)
        val alphabet = "abcq:-/x"
        for (trial in 0..2999) {
            val text = StringBuilder()
            for (i in random.nextInt(40) downTo 1) {
                text.append(alphabet.get(random.nextInt(alphabet.length)))
            }
            val literal = alphabet.get(random.nextInt(alphabet.length)).toString()
            assertPlannedAgreesAtEveryStart(text.toString(), literal)
        }
    }

    /**
     * The dispatched and undispatched kernels have to record identical work. `ScanDispatchAudit` compares recorded work against the path taken, so a search that charges
     * differently depending on which kernel its plan picked would show up there as a bug somewhere
     * else entirely.
     */
    @Test
    @Tag("work-counter")
    fun chargesTheSameWorkWhicheverKernelThePlanChose() {
        val text = "abc-def-ghi" + FILLER + "-tail"
        for (literal in arrayOf<String>("-", "a", "z", "\n")) {
            for (from in intArrayOf(-3, 0, 1, 5, text.length - 1, text.length, text.length + 4)) {
                val dispatched = WorkCounter.countForTesting(Runnable { planned(text, literal, from) })
                val undispatched = WorkCounter.countForTesting(Runnable { anchored(text, literal, from) })
                Assertions.assertThat(dispatched).`as`("literal=%s fromIndex=%s", literal, from).isEqualTo(undispatched)
            }
        }
    }

    companion object {
        /**
         * Filler long enough to push the remaining window past [ ][StringLiteralSearch.MIN_ANCHORED_WINDOW], made of a character none of the literals here
         * contains so that padding cannot introduce a match.
         */
        private val FILLER = "z".repeat(StringLiteralSearch.MIN_ANCHORED_WINDOW)

        private fun anchored(text: String, literal: String?, fromIndex: Int): Int {
            val offset = StringLiteralSearch.anchorOffset(literal)
            return StringLiteralSearch.indexOf(
                text, literal, offset, StringLiteralSearch.anchorAt(literal, offset), fromIndex
            )
        }

        private fun assertAgreesAt(text: String, literal: String, from: Int) {
            Assertions.assertThat(anchored(text, literal, from))
                .`as`("literal=%s text=%s fromIndex=%s", literal, text, from)
                .isEqualTo(text.indexOf(literal, from))
        }

        /**
         * Runs the differential twice: once on `text` as given, and once on `text` followed
         * by enough filler that every start index tested leaves a window long enough to anchor. Without
         * the second pass the short-window guard would route these cases straight to the JDK and the
         * anchored loop would go untested.
         */
        private fun assertAgreesAtEveryStart(text: String, literal: String) {
            val padded = text + FILLER
            for (from in 0..text.length + 1) {
                assertAgreesAt(text, literal, from)
                assertAgreesAt(padded, literal, from)
            }
        }

        private fun planned(text: String?, literal: String?, fromIndex: Int): Int {
            val offset = StringLiteralSearch.anchorOffset(literal)
            return StringLiteralSearch.indexOfPlanned(
                text,
                literal,
                offset,
                StringLiteralSearch.anchorAt(literal, offset),
                StringLiteralSearch.singleAsciiChar(literal),
                fromIndex
            )
        }

        private fun assertPlannedAgreesAtEveryStart(text: String, literal: String) {
            val padded = text + FILLER
            for (from in 0..text.length + 1) {
                Assertions.assertThat(planned(text, literal, from))
                    .`as`("literal=%s text=%s fromIndex=%s", literal, text, from)
                    .isEqualTo(text.indexOf(literal, from))
                Assertions.assertThat(planned(padded, literal, from))
                    .`as`("literal=%s paddedText=%s fromIndex=%s", literal, padded, from)
                    .isEqualTo(padded.indexOf(literal, from))
            }
        }
    }
}
