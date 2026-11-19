// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.safere.StateAccelerator.*
import java.lang.reflect.Array
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.List
import java.util.regex.Pattern
import kotlin.Any
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Exception
import kotlin.Int
import kotlin.IntArray
import kotlin.String
import kotlin.Throws
import kotlin.booleanArrayOf
import kotlin.code
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.indices
import kotlin.collections.last
import kotlin.collections.mutableListOf
import kotlin.collections.plus
import kotlin.collections.reversed
import kotlin.compareTo
import kotlin.intArrayOf
import kotlin.plus
import kotlin.sequences.plus
import kotlin.text.StringBuilder
import kotlin.text.plus
import kotlin.text.repeat
import kotlin.text.substring
import kotlin.text.toByteArray

/**
 * Tests for [Dfa].
 * 
 * 
 * Many tests verify that the DFA produces the same match/no-match result as the NFA. The DFA
 * only reports match boundaries (not capture groups), so tests focus on match detection and end
 * position.
 */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class DfaTest {
    @Test
    fun unpairedSurrogateWordTransitionsRemainCacheableInEveryScanDirection() {
        val pattern = Pattern.compile("(?U)\\b")
        for (unit in mutableListOf<String?>("\uD800", "\uDC00")) {
            val scanner: InputScanner = StringInputScanner(unit!!.repeat(64))
            for (operation in 0..2) {
                val reverse = operation == 1
                val prog = if (reverse) pattern.reverseProg() else pattern.prog()
                val dfa = Dfa(prog, 10000, Dfa.buildSetup(prog), false)
                val start = dfa.startState(scanner, if (reverse) scanner.length else 0, false, reverse)
                val matched =
                    when (operation) {
                        0 -> dfa.doSearch(scanner, false, false).matched
                        1 -> dfa.doSearchReverse(scanner, scanner.length, 0, false, false).matched
                        else -> dfa.doSearchMany(scanner, false).matched
                    }
                Assertions.assertThat(matched).isFalse()
                // Exclude the EOF slot: ordinary unpaired halves must populate a consuming transition.
                Assertions.assertThat(
                    Arrays.stream<Dfa.State?>(start.next, 0, start.next.size - 1)
                        .anyMatch { next: Dfa.State? -> next != null })
                    .`as`("unpaired %s operation=%s", Integer.toHexString(unit.get(0).code), operation)
                    .isTrue()
            }
        }
    }

    @Test
    fun anchoredStartCachingDoesNotDiscardAnUnanchoredMatch() {
        val prog = Compiler.compile(Parser.parse("[0-9]*0", FLAGS))
        val text = "011" + "~".repeat(512) + "0"
        for (scanner in List.of<InputScanner?>(
            StringInputScanner(text), Utf8InputScanner(
                text.toByteArray(
                    StandardCharsets.UTF_8
                )
            )
        )) {
            for (reverseContext in booleanArrayOf(false, true)) {
                val dfa = Dfa(prog, 10000, Dfa.buildSetup(prog), false)
                dfa.startState(scanner, 0, true, reverseContext)
                for (reuse in 0..1) {
                    val result = dfa.doSearch(scanner, false, false)
                    Assertions.assertThat<Dfa.SearchResult?>(result).isNotNull()
                    Assertions.assertThat(result!!.matched).isTrue()
                    Assertions.assertThat(result.pos).isEqualTo(1)
                }
            }
        }
    }

    @Test
    fun longestDeferredMatchRetainsEveryConsumingBranch() {
        // Issue #857: each assertion succeeds before the consuming alternative finishes.
        for (assertion in mutableListOf<String?>("\\b", "\\B", "(?m:$)")) {
            val text =
                when (assertion) {
                    "\\b" -> "a!a"
                    "\\B" -> "aaa"
                    else -> "a\na"
                }
            for (assertionFirst in booleanArrayOf(false, true)) {
                val suffix = Pattern.quote(text.substring(1))
                val alternatives = if (assertionFirst) assertion + "|" + suffix else suffix + "|" + assertion
                val pattern = Pattern.compile("a(?:" + alternatives + ")")
                val longest = Dfa(pattern.prog(), 10000, Dfa.buildSetup(pattern.prog()), true)
                for (pass in 0..1) {
                    for (anchored in booleanArrayOf(true, false)) {
                        val result = longest.doSearch(text, anchored, true)
                        Assertions.assertThat<Dfa.SearchResult?>(result).`as`(assertion).isNotNull()
                        Assertions.assertThat(result!!.matched).`as`(assertion).isTrue()
                        Assertions.assertThat(result.pos).`as`(assertion).isEqualTo(text.length)
                    }
                }
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun startCacheAllocationIsBoundedIndependentlyOfAssertionBits() {
        val cache = Dfa::class.java.getDeclaredField("startStateByContext")
        cache.setAccessible(true)
        for (regex in mutableListOf<String?>("a+b", "(?m)^a+$", "(?-d:(?m:$))(?dm:$)", "\\b{g}a")) {
            val pattern = Pattern.compile(regex)
            for (prog in List.of<Prog?>(pattern.prog(), pattern.reverseProg())) {
                val dfa = Dfa(prog, 10000, Dfa.buildSetup(prog), false)
                Assertions.assertThat(Array.getLength(cache.get(dfa))).`as`(regex).isLessThanOrEqualTo(256)
            }
        }
    }

    @Test
    fun startCacheRetainsCompleteContextAcrossReplacements() {
        for (regex in mutableListOf<String?>("a+b", "(?m)^a+$", "(?-d:(?m:$))(?dm:$)", "\\b{g}a")) {
            val pattern = Pattern.compile(regex)
            for (prog in List.of<Prog?>(pattern.prog(), pattern.reverseProg())) {
                val cached = Dfa(prog, 10000, Dfa.buildSetup(prog), false)
                val inputs: MutableList<String> =
                    mutableListOf<String?>("a\r\na\na", "a\ra", "é a\u0301\na", "", "a\u2028b")
                for (pass in 0..1) {
                    for (text in if (pass == 0) inputs else inputs.reversed()) {
                        val scanner: InputScanner = StringInputScanner(text)
                        for (pos in 0..text.length) {
                            for (anchored in booleanArrayOf(false, true)) {
                                for (reverse in booleanArrayOf(false, true)) {
                                    val fresh = Dfa(prog, 10000, Dfa.buildSetup(prog), false)
                                    val expected = fresh.startState(scanner, pos, anchored, reverse)
                                    val actual = cached.startState(scanner, pos, anchored, reverse)
                                    Assertions.assertThat(actual.flags).`as`(regex).isEqualTo(expected.flags)
                                    Assertions.assertThat(actual.insts).`as`(regex).containsExactly(*expected.insts)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Basic matching
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Literals")
    internal inner class Literals {
        @Test
        fun singleChar() {
            val r: Dfa.SearchResult = search("a", "a")
            Assertions.assertThat(r.matched).isTrue()
        }

        @Test
        fun multiChar() {
            val r: Dfa.SearchResult = search("abc", "xabcy")
            Assertions.assertThat(r.matched).isTrue()
        }

        @Test
        fun noMatch() {
            val r: Dfa.SearchResult = search("abc", "def")
            Assertions.assertThat(r.matched).isFalse()
        }

        @Test
        fun emptyPattern() {
            val r: Dfa.SearchResult = search("", "hello")
            Assertions.assertThat(r.matched).isTrue()
        }

        @Test
        fun emptyText() {
            val r: Dfa.SearchResult = search("", "")
            Assertions.assertThat(r.matched).isTrue()
        }

        @Test
        fun atEnd() {
            val r: Dfa.SearchResult = search("xyz", "abcxyz")
            Assertions.assertThat(r.matched).isTrue()
            Assertions.assertThat(r.pos).isEqualTo(6)
        }
    }

    @Nested
    @DisplayName("Character classes")
    internal inner class CharClasses {
        @Test
        fun nonAsciiClassCachePreservesLargeClassIds() {
            val rangeCount = 32769
            val firstCp = 0x10000
            val ranges = IntArray(rangeCount * 2)
            for (i in 0..<rangeCount) {
                val cp = firstCp + i * 2
                ranges[i * 2] = cp
                ranges[i * 2 + 1] = cp
            }

            val prog = Prog()
            prog.allocInst()
            val charClass = prog.allocInst()
            val match = prog.allocInst()
            prog.mutableInst(charClass).initCharClass(match, ranges)
            prog.mutableInst(match).initMatch(0)
            prog.setStart(charClass)
            prog.setStartUnanchored(charClass)
            prog.freeze()

            val dfa = Dfa(prog, 10000, Dfa.buildSetup(prog), false)
            val text = Character.toString(firstCp + (rangeCount - 1) * 2)

            Assertions.assertThat(dfa.doSearch(text, true, false).matched).isTrue()
            Assertions.assertThat(dfa.doSearch(text, true, false).matched).isTrue()
        }

        @Test
        fun digitClass() {
            val r: Dfa.SearchResult = search("\\d+", "abc123def")
            Assertions.assertThat(r.matched).isTrue()
        }

        @Test
        fun wordClass() {
            val r: Dfa.SearchResult = search("\\w+", "hello")
            Assertions.assertThat(r.matched).isTrue()
        }

        @Test
        fun range() {
            val r: Dfa.SearchResult = search("[a-z]+", "HELLO world")
            Assertions.assertThat(r.matched).isTrue()
        }

        @Test
        fun negatedClass() {
            val r: Dfa.SearchResult = search("[^0-9]+", "123abc456")
            Assertions.assertThat(r.matched).isTrue()
        }
    }

    @Nested
    @DisplayName("Quantifiers")
    internal inner class Quantifiers {
        @Test
        fun star() {
            val r: Dfa.SearchResult? = fullMatch("a*", "aaa")
            Assertions.assertThat(r!!.matched).isTrue()
            Assertions.assertThat(r.pos).isEqualTo(3)
        }

        @Test
        fun plus() {
            val r: Dfa.SearchResult? = fullMatch("a+", "aaa")
            Assertions.assertThat(r!!.matched).isTrue()
            Assertions.assertThat(r.pos).isEqualTo(3)
        }

        @Test
        fun plusNoMatch() {
            val r: Dfa.SearchResult? = fullMatch("a+", "")
            Assertions.assertThat(r!!.matched).isFalse()
        }

        @Test
        fun quest() {
            val r: Dfa.SearchResult? = fullMatch("a?", "a")
            Assertions.assertThat(r!!.matched).isTrue()
        }

        @Test
        fun questEmpty() {
            val r: Dfa.SearchResult? = fullMatch("a?", "")
            Assertions.assertThat(r!!.matched).isTrue()
        }

        @Test
        fun repeat() {
            val r: Dfa.SearchResult? = fullMatch("a{3}", "aaa")
            Assertions.assertThat(r!!.matched).isTrue()
        }

        @Test
        fun repeatRange() {
            val r: Dfa.SearchResult? = fullMatch("a{2,4}", "aaa")
            Assertions.assertThat(r!!.matched).isTrue()
        }
    }

    @Nested
    @DisplayName("Alternation")
    internal inner class Alternation {
        @Test
        fun firstAlt() {
            val r: Dfa.SearchResult = search("cat|dog", "I have a cat")
            Assertions.assertThat(r.matched).isTrue()
        }

        @Test
        fun secondAlt() {
            val r: Dfa.SearchResult = search("cat|dog", "I have a dog")
            Assertions.assertThat(r.matched).isTrue()
        }

        @Test
        fun noAlt() {
            val r: Dfa.SearchResult = search("cat|dog", "I have a bird")
            Assertions.assertThat(r.matched).isFalse()
        }
    }

    @Nested
    @DisplayName("Anchors")
    internal inner class Anchors {
        @Test
        fun startAnchor() {
            val r: Dfa.SearchResult = search("^hello", "hello world")
            Assertions.assertThat(r.matched).isTrue()
        }

        @Test
        fun startAnchorFail() {
            val r: Dfa.SearchResult = search("^hello", "say hello")
            Assertions.assertThat(r.matched).isFalse()
        }

        @Test
        fun endAnchor() {
            val r: Dfa.SearchResult = search("world$", "hello world")
            Assertions.assertThat(r.matched).isTrue()
        }

        @Test
        fun endAnchorFail() {
            val r: Dfa.SearchResult = search("world$", "world cup")
            Assertions.assertThat(r.matched).isFalse()
        }

        @Test
        fun fullAnchor() {
            val r: Dfa.SearchResult = search("^abc$", "abc")
            Assertions.assertThat(r.matched).isTrue()
        }
    }

    @Nested
    @DisplayName("Dot")
    internal inner class Dot {
        @Test
        fun dotMatchesChar() {
            val r: Dfa.SearchResult? = fullMatch("a.c", "abc")
            Assertions.assertThat(r!!.matched).isTrue()
        }

        @Test
        fun dotDoesNotMatchNewline() {
            val r: Dfa.SearchResult? = fullMatch("a.c", "a\nc")
            Assertions.assertThat(r!!.matched).isFalse()
        }

        @Test
        fun dotPlus() {
            val r: Dfa.SearchResult? = fullMatch(".+", "hello")
            Assertions.assertThat(r!!.matched).isTrue()
            Assertions.assertThat(r.pos).isEqualTo(5)
        }
    }

    @Nested
    @DisplayName("Word boundary")
    internal inner class WordBoundary {
        @Test
        fun wordBoundaryMatch() {
            val r: Dfa.SearchResult = search("\\bfoo\\b", "foo bar")
            Assertions.assertThat(r.matched).isTrue()
        }

        @Test
        fun wordBoundaryNoMatch() {
            val r: Dfa.SearchResult = search("\\bfoo\\b", "foobar")
            Assertions.assertThat(r.matched).isFalse()
        }

        @Test
        fun wordBoundaryInMiddle() {
            val r: Dfa.SearchResult = search("\\bbar\\b", "foo bar baz")
            Assertions.assertThat(r.matched).isTrue()
            Assertions.assertThat(r.pos).isEqualTo(7)
        }

        @Test
        fun unicodeWordBoundaryStartStateCacheDistinguishesNextCharacterContext() {
            val re = Parser.parse("\\b.", FLAGS or ParseFlags.UNICODE_CHAR_CLASS)
            val prog = Compiler.compile(re)
            val dfa = Dfa(prog, 10000, Dfa.buildSetup(prog), false)

            val boundary = dfa.doSearch("!\u00E9", 1, true, false)
            val nonBoundary = dfa.doSearch("!!", 1, true, false)

            Assertions.assertThat(boundary.matched).isTrue()
            Assertions.assertThat(boundary.pos).isEqualTo(2)
            Assertions.assertThat(nonBoundary.matched).isFalse()
        }
    }

    @Nested
    @DisplayName("Match modes")
    internal inner class MatchModes {
        @Test
        fun fullMatchSuccess() {
            val r: Dfa.SearchResult? = fullMatch("abc", "abc")
            Assertions.assertThat(r!!.matched).isTrue()
            Assertions.assertThat(r.pos).isEqualTo(3)
        }

        @Test
        fun fullMatchFailure() {
            val r: Dfa.SearchResult? = fullMatch("abc", "abcd")
            Assertions.assertThat(r!!.matched).isFalse()
        }

        @Test
        fun longestMatchGreedy() {
            val r: Dfa.SearchResult = longestMatch("a+", "aaa")
            Assertions.assertThat(r.matched).isTrue()
            Assertions.assertThat(r.pos).isEqualTo(3)
        }

        @Test
        fun firstMatchShortest() {
            val r: Dfa.SearchResult = search("a+", "aaa")
            Assertions.assertThat(r.matched).isTrue()
            // First match should still match (may not return the shortest match
            // since DFA with .*? prefix finds leftmost).
        }
    }

    @Nested
    @DisplayName("Unicode")
    internal inner class Unicode {
        @Test
        fun supplementaryPlane() {
            val r: Dfa.SearchResult = search(".", "😀")
            Assertions.assertThat(r.matched).isTrue()
        }

        @Test
        fun unicodeLetters() {
            val r: Dfa.SearchResult = search("[à-ÿ]+", "café")
            Assertions.assertThat(r.matched).isTrue()
        }
    }

    @Nested
    @DisplayName("Complex patterns")
    internal inner class Complex {
        @Test
        fun emailLike() {
            val r: Dfa.SearchResult = search("[a-z]+@[a-z]+\\.[a-z]+", "user@example.com")
            Assertions.assertThat(r.matched).isTrue()
        }

        @Test
        fun ipAddress() {
            val r: Dfa.SearchResult = search("\\d+\\.\\d+\\.\\d+\\.\\d+", "ip is 192.168.1.1 here")
            Assertions.assertThat(r.matched).isTrue()
        }

        @Test
        fun alternationWithQuantifiers() {
            val r: Dfa.SearchResult? = fullMatch("(ab|cd)+", "ababcdab")
            Assertions.assertThat(r!!.matched).isTrue()
        }
    }

    @Nested
    @DisplayName("State budget")
    internal inner class Budget {
        @Test
        fun exceedBudgetReturnsNull() {
            // Alternation with many branches forces many DFA states.
            val re = Parser.parse("(a|b)(c|d)(e|f)(g|h)(i|j)", FLAGS)
            val prog = Compiler.compile(re)
            // Budget of 2 is too small for this pattern.
            val r = Dfa.search(prog, "acegi", false, false, 2)
            // Budget exceeded -- should return null to signal fallback to NFA.
            Assertions.assertThat<Dfa.SearchResult?>(r).isNull()
        }

        @Test
        fun linearTimeGuarantee() {
            // Pathological for backtracking: a?^n a^n
            val n = 25
            val pattern = "a?".repeat(n) + "a".repeat(n)
            val text = "a".repeat(n)
            val re = Parser.parse(pattern, FLAGS)
            val prog = Compiler.compile(re)
            val r = Dfa.search(prog, text, true, true)
            // May bail out (return null) due to state explosion, which is fine.
            // The important thing is it completes quickly, not exponentially.
            if (r != null) {
                Assertions.assertThat(r.matched).isTrue()
            }
        }

        /**
         * A budget too small to hold the working set, over an input long enough that rebuilding the
         * cache is cheaper than finishing on the NFA. Every one of these searches returned null before
         * the DFA could discard its cache and carry on.
         */
        @Test
        fun discardingTheStateCacheAgreesWithABudgetLargeEnoughToAvoidIt() {
            val text: String = sourceShapedText(100000,  /* withTrailingMatch= */true)
            for (pattern in mutableListOf<String?>(
                "[A-Za-z]{10}\\s+[\\s\\S]{0,100}Result[\\s\\S]{0,100}\\s+[A-Za-z]{10}",
                "[A-Za-z]{10}\\s+[\\s\\S]{0,100}Result[\\s\\S]{0,100}\\s+[A-Za-z]{10}#"
            )) {
                val prog = Compiler.compile(Parser.parse(pattern, FLAGS))
                for (longest in booleanArrayOf(false, true)) {
                    val ample = Dfa.search(prog, text, false, longest, 4000000)
                    Assertions.assertThat<Dfa.SearchResult?>(ample).isNotNull()
                    val resetting = Dfa.search(prog, text, false, longest, 500)
                    Assertions.assertThat<Dfa.SearchResult?>(resetting).isNotNull()
                    Assertions.assertThat(resetting!!.matched).isEqualTo(ample!!.matched)
                    Assertions.assertThat(resetting.pos).isEqualTo(ample.pos)
                }
            }
        }

        /**
         * Discarding the cache renumbers every state and rebuilds the arrays the search loops index by
         * state id, so a missed invalidation shows up as a wrong answer rather than a crash. This walks
         * a whole find loop against `java.util.regex` to catch that.
         */
        @Test
        fun repeatedFindsOverAResettingDfaMatchTheJdk() {
            val text: String = sourceShapedText(400000,  /* withTrailingMatch= */true)
            val regex = "[A-Za-z]{10}\\s+[\\s\\S]{0,100}Result[\\s\\S]{0,100}\\s+[A-Za-z]{10}"
            val actual = Pattern.compile(regex).matcher(text)
            val expected = Pattern.compile(regex).matcher(text)
            while (expected.find()) {
                Assertions.assertThat(actual.find()).isTrue()
                Assertions.assertThat(actual.start()).isEqualTo(expected.start())
                Assertions.assertThat(actual.end()).isEqualTo(expected.end())
            }
            Assertions.assertThat(actual.find()).isFalse()
        }

        /**
         * A [Dfa] instance lives in a per-[Pattern] [ThreadLocal], so an input that
         * fills the cache faster than `MIN_PROGRESS_PER_CACHED_STATE` positions per state must
         * back off rather than permanently bricking the [Dfa] for later searches on the same
         * thread.
         */
        @Test
        fun unproductiveCacheExhaustionBacksOffAndThenRecovers() {
            val pattern = "[A-Za-z]{10}\\s+[\\s\\S]{0,100}Result[\\s\\S]{0,100}\\s+[A-Za-z]{10}"
            val prog = Compiler.compile(Parser.parse(pattern, FLAGS))
            val dfa = Dfa(prog, 500, Dfa.buildSetup(prog), false)

            // Irregular 10..14-character words vary the spacing between [A-Za-z]{10}\s+ starts so the
            // active subset of {0..100} offsets is distinct at almost every position, filling the
            // 500-state budget in ~600 characters (well under the 5,000-character threshold).
            val rng = Random(1)
            val denseBuilder = StringBuilder()
            while (denseBuilder.length < 3000) {
                denseBuilder.append("abcdefghijklmno", 0, 10 + rng.nextInt(5)).append(' ')
            }
            val dense = denseBuilder.toString()
            Assertions.assertThat<Dfa.SearchResult?>(dfa.doSearch(dense, 0, false, false)).isNull()
            // Within the 2x backoff window (10,000 positions), the full cache refuses to flush again.
            Assertions.assertThat<Dfa.SearchResult?>(dfa.doSearch(dense, 1000, false, false)).isNull()

            // Moving to another input and starting far into it does not prove that the fallback scanned
            // the skipped text. Repeated fallback attempts eventually allow a bounded reset retry.
            val productive: String = sourceShapedText(100000,  /* withTrailingMatch= */true)
            val ample =
                Dfa(prog, 4000000, Dfa.buildSetup(prog), false)
                    .doSearch(productive, 12000, false, false)
            Assertions.assertThat<Dfa.SearchResult?>(ample).isNotNull()
            Assertions.assertThat<Dfa.SearchResult?>(dfa.doSearch(productive, 12000, false, false)).isNull()
            var recovered: Dfa.SearchResult? = null
            var attempt = 0
            while (attempt < 1000 && recovered == null) {
                recovered = dfa.doSearch(productive, 12000, false, false)
                attempt++
            }
            Assertions.assertThat<Dfa.SearchResult?>(recovered).isNotNull()
            Assertions.assertThat(recovered!!.matched).isEqualTo(ample.matched)
            Assertions.assertThat(recovered.pos).isEqualTo(ample.pos)
        }

        @Test
        fun fullCacheCanRecoverWhenNextSearchNeedsANewStartContext() {
            val pattern = "(?m)(\\Aa|^b)"
            val prog = Compiler.compile(Parser.parse(pattern, FLAGS))
            val dfa = Dfa(prog, 1, Dfa.buildSetup(prog), false)
            Assertions.assertThat<Dfa.SearchResult?>(dfa.doSearch("a", 0, false, false)).isNull()
            val productive = " ".repeat(999) + "\n" + "z"
            val expected =
                Dfa(prog, 4000000, Dfa.buildSetup(prog), false)
                    .doSearch(productive, 1000, true, false)
            Assertions.assertThat<Dfa.SearchResult?>(expected).isNotNull()
            val actual = dfa.doSearch(productive, 1000, true, false)
            Assertions.assertThat<Dfa.SearchResult?>(actual).isNotNull()
            Assertions.assertThat(actual.matched).isEqualTo(expected.matched)
            Assertions.assertThat(actual.pos).isEqualTo(expected.pos)
        }

        @Test
        @Throws(ReflectiveOperationException::class)
        fun startingAtANonzeroOffsetDoesNotCreditTheSkippedPrefix() {
            val prog = Compiler.compile(Parser.parse("a", FLAGS))
            val dfa = Dfa(prog, 100, Dfa.buildSetup(prog), false)
            val result = dfa.doSearch(" ".repeat(1000) + "a", 1000, true, false)
            Assertions.assertThat<Dfa.SearchResult?>(result).isNotNull()
            Assertions.assertThat(result.matched).isTrue()

            val steps = Dfa::class.java.getDeclaredField("dfaStepsSinceReset")
            steps.setAccessible(true)
            Assertions.assertThat(steps.getLong(dfa)).isLessThanOrEqualTo(1)
        }

        @Test
        @Throws(ReflectiveOperationException::class)
        fun completedNoMatchScanCountsTowardCacheProductivity() {
            val prog = Compiler.compile(Parser.parse("[ab]*c", FLAGS))
            val dfa = Dfa(prog, 10000, Dfa.buildSetup(prog), false)
            val result = dfa.doSearch("a".repeat(1000), 0, false, false)
            Assertions.assertThat<Dfa.SearchResult?>(result).isNotNull()
            Assertions.assertThat(result.matched).isFalse()

            val steps = Dfa::class.java.getDeclaredField("dfaStepsSinceReset")
            steps.setAccessible(true)
            Assertions.assertThat(steps.getLong(dfa)).isGreaterThanOrEqualTo(1000)
        }

        @Test
        @Throws(ReflectiveOperationException::class)
        fun completedNonAsciiNoMatchScanCountsTowardCacheProductivity() {
            val prog = Compiler.compile(Parser.parse("abc", FLAGS))
            val dfa = Dfa(prog, 10000, Dfa.buildSetup(prog), false)
            val result = dfa.doSearch("é" + "x".repeat(1000), 0, false, false)
            Assertions.assertThat<Dfa.SearchResult?>(result).isNotNull()
            Assertions.assertThat(result.matched).isFalse()

            val steps = Dfa::class.java.getDeclaredField("dfaStepsSinceReset")
            steps.setAccessible(true)
            Assertions.assertThat(steps.getLong(dfa)).isGreaterThanOrEqualTo(1001)
        }

        @Test
        @Throws(ReflectiveOperationException::class)
        fun bailoutBackoffDoesNotCountAnUnscannedStartGap() {
            val prog = Compiler.compile(Parser.parse("a", FLAGS))
            val dfa = Dfa(prog, 100, Dfa.buildSetup(prog), false)
            Assertions.assertThat<Dfa.SearchResult?>(dfa.doSearch("a", 0, true, false)).isNotNull()

            val bailed = Dfa::class.java.getDeclaredField("lastSearchBailed")
            bailed.setAccessible(true)
            bailed.setBoolean(dfa, true)
            Assertions.assertThat<Dfa.SearchResult?>(dfa.doSearch(" ".repeat(1000) + "a", 1000, true, false))
                .isNotNull()

            val progress = Dfa::class.java.getDeclaredField("backoffProgress")
            progress.setAccessible(true)
            Assertions.assertThat(progress.getLong(dfa)).isLessThanOrEqualTo(66)
        }

        /**
         * With a one-state budget the cache can be productive enough to earn a reset and still be too
         * small to continue afterwards. That is budget exhaustion too, so it must be reported the same
         * way as a refused reset.
         */
        @Test
        @Throws(ReflectiveOperationException::class)
        fun failureAfterAGrantedResetIsReportedAsBudgetExhaustion() {
            val prog = Compiler.compile(Parser.parse("a", FLAGS))
            val dfa = Dfa(prog, 1, Dfa.buildSetup(prog), false)
            Assertions.assertThat<Dfa.SearchResult?>(dfa.doSearch(" ".repeat(20) + "a", 0, false, false)).isNull()

            // A granted reset zeroes the productivity counter; a refused one leaves the scan credited.
            val steps = Dfa::class.java.getDeclaredField("dfaStepsSinceReset")
            steps.setAccessible(true)
            Assertions.assertThat(steps.getLong(dfa)).isZero()
            Assertions.assertThat(dfa.lastSearchBailed()).isTrue()
        }

        /**
         * Callers report every null DFA result as [StrategyReason.DFA_BUDGET_EXCEEDED]. This pins
         * the contract that makes that accurate: a search returns null exactly when it sets [ ][Dfa.lastSearchBailed]. A new null exit for some other reason fails here and needs its own
         * reason instead.
         */
        @Test
        fun everyNullResultIsBudgetExhaustion() {
            val regexes =
                mutableListOf<String?>("a", "[ab]*c", "(?m)^b|\\Aa", "\\bfoo\\b", "x[a-z]{3,8}y", "(a|b)*abb", "é+[^a]")
            val texts =
                List.of<String?>(
                    "",
                    "a",
                    " ".repeat(20) + "a",
                    "abababababc",
                    "foo bar foo",
                    "xabcdy xqqqqqqqqqqy",
                    "abbabbaabb".repeat(10),
                    "ééé b\n b",
                    sourceShapedText(2000,  /* withTrailingMatch= */true)
                )
            var nullResults = 0
            var nonNullResults = 0
            for (regex in regexes) {
                val prog = Compiler.compile(Parser.parse(regex, FLAGS))
                val reverseProg = org.safere.Pattern.compile(regex).flatReverseDfaProg()
                for (budget in intArrayOf(1, 2, 3, 4, 8, 64)) {
                    // Reuse each DFA across searches so a flag left over from an earlier search is caught.
                    val first = Dfa(prog, budget, Dfa.buildSetup(prog), false)
                    val longest = Dfa(prog, budget, Dfa.buildSetup(prog), true)
                    val reverse =
                        if (reverseProg == null)
                            null
                        else
                            Dfa(reverseProg, budget, Dfa.buildSetup(reverseProg), true)
                    for (text in texts) {
                        for (anchored in booleanArrayOf(false, true)) {
                            val where = regex + " budget=" + budget + " anchored=" + anchored
                            val results: MutableList<Any?> = ArrayList<Any?>()
                            results.add(first.doSearch(text, 0, anchored, false))
                            assertBailFlagMatches(first, results.last(), "doSearch " + where)
                            results.add(longest.doSearch(text, 0, anchored, true))
                            assertBailFlagMatches(longest, results.last(), "doSearch longest " + where)
                            results.add(longest.doSearchMany(text, anchored))
                            assertBailFlagMatches(longest, results.last(), "doSearchMany " + where)
                            if (reverse != null) {
                                results.add(
                                    reverse.doSearchReverse(
                                        StringInputScanner(text), text.length, 0, anchored, true
                                    )
                                )
                                assertBailFlagMatches(reverse, results.last(), "doSearchReverse " + where)
                            }
                            for (result in results) {
                                if (result == null) {
                                    nullResults++
                                } else {
                                    nonNullResults++
                                }
                            }
                        }
                    }
                }
            }
            Assertions.assertThat(nullResults).isPositive()
            Assertions.assertThat(nonNullResults).isPositive()
        }

        companion object {
            private fun assertBailFlagMatches(dfa: Dfa, result: Any?, description: String?) {
                Assertions.assertThat(dfa.lastSearchBailed()).`as`(description).isEqualTo(result == null)
            }

            /**
             * Text shaped like source code: mostly short words, an occasional long one, and a marker
             * literal every so often. The pattern above only has live state within a hundred characters of
             * a long word or a marker, so states accumulate slowly enough for rebuilding the cache to be
             * worth it -- uniformly random text of any alphabet either needs a new state on every character
             * or never fills the cache at all.
             */
            private fun sourceShapedText(size: Int, withTrailingMatch: Boolean): String {
                val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_"
                val random = Random(7)
                val text = StringBuilder(size + 64)
                var word = 0
                while (text.length < size) {
                    if (word % 120 == 119) {
                        text.append("Result")
                    } else {
                        val length = if (word % 20 == 19) 10 + random.nextInt(5) else 2 + random.nextInt(7)
                        for (i in 0..<length) {
                            text.append(alphabet.get(random.nextInt(alphabet.length)))
                        }
                    }
                    text.append(if (word % 11 == 10) '\n' else ' ')
                    word++
                }
                text.setLength(size)
                return if (withTrailingMatch) text.toString() + " abcdefghij Result klmnopqrst " else text.toString()
            }
        }
    }

    @Test
    fun reverseProbeReportsLiveFrontierAsInconclusive() {
        val text = "x".repeat(10000) + "abc"
        for (scanner in List.of<InputScanner?>(
            StringInputScanner(text), Utf8InputScanner(
                text.toByteArray(
                    StandardCharsets.UTF_8
                )
            )
        )) {
            val reverse = org.safere.Pattern.compile("(?s:.*)abc$").reverseDfa()
            for (pass in 0..1) {
                val probe =
                    reverse.doSearchReverse(scanner, scanner.length, 0, true, true, 64)
                Assertions.assertThat<Dfa.SearchResult?>(probe).isNotNull()
                Assertions.assertThat(probe!!.scanLimitReached).isTrue()
                Assertions.assertThat(reverse.lastSearchBailed()).isFalse()

                val full = reverse.doSearchReverse(scanner, scanner.length, 0, true, true)
                Assertions.assertThat<Dfa.SearchResult?>(full).isNotNull()
                Assertions.assertThat(full!!.scanLimitReached).isFalse()
                Assertions.assertThat(full.matched).isTrue()
                Assertions.assertThat(full.pos).isZero()

                val existence =
                    reverse.doSearchReverse(scanner, scanner.length, 0, true, false, 64)
                Assertions.assertThat<Dfa.SearchResult?>(existence).isNotNull()
                Assertions.assertThat(existence!!.scanLimitReached).isFalse()
                Assertions.assertThat(existence.matched).isTrue()
                Assertions.assertThat(existence.pos).isEqualTo(scanner.length - 3)
            }
        }
    }

    @Test
    fun reverseProbeRetainsAssertionContextAndCodePointBoundaries() {
        for (prefix in List.of<String?>("x".repeat(1024), "é😀".repeat(400), "x".repeat(1024) + " ")) {
            for (tail in mutableListOf<String?>("abc", "終点😀")) {
                val text = prefix + tail
                for (scanner in List.of<InputScanner?>(
                    StringInputScanner(text), Utf8InputScanner(
                        text.toByteArray(
                            StandardCharsets.UTF_8
                        )
                    )
                )) {
                    for (regex in List.of<String?>("\\b" + tail + "$", "\\B" + tail + "$", tail + "$")) {
                        val reverse = org.safere.Pattern.compile(regex).reverseDfa()
                        val full =
                            reverse.doSearchReverse(scanner, scanner.length, 0, true, true)
                        for (limit in 0..16) {
                            val probe =
                                reverse.doSearchReverse(scanner, scanner.length, 0, true, true, limit)
                            Assertions.assertThat<Dfa.SearchResult?>(probe).`as`("/%s/ limit %s", regex, limit)
                                .isNotNull()
                            Assertions.assertThat(reverse.lastSearchBailed()).isFalse()
                            if (!probe!!.scanLimitReached) {
                                Assertions.assertThat<Dfa.SearchResult?>(probe).`as`("/%s/ limit %s", regex, limit)
                                    .isEqualTo(full)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun reverseDfaPreservesContextAtStartLimit() {
        for (prefix in mutableListOf<String?>("xy", "é", "€", "😀", "\n")) {
            val text = prefix + "a"
            for (scanner in List.of<InputScanner?>(
                StringInputScanner(text), Utf8InputScanner(
                    text.toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
            )) {
                for (regex in mutableListOf<String?>("a", "\\ba|a", "\\Ba|a", "\\ba", "\\Ba", "^a|b", "(?m)^a")) {
                    val expected =
                        Pattern.compile(regex).matcher(text).find(prefix!!.length)
                    val reverse = org.safere.Pattern.compile(regex).reverseDfa()
                    for (pass in 0..1) {
                        val result =
                            reverse.doSearchReverse(
                                scanner, scanner.length, scanner.length - 1, true, true
                            )
                        Assertions.assertThat<Dfa.SearchResult?>(result)
                            .`as`("%s after %s (%s)", regex, prefix, scanner.javaClass)
                            .isNotNull()
                        Assertions.assertThat(result!!.matched)
                            .`as`("%s after %s (%s)", regex, prefix, scanner.javaClass)
                            .isEqualTo(expected)
                        if (expected) {
                            Assertions.assertThat(result.pos).isEqualTo(scanner.length - 1)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun reverseDfaDecodesFromInteriorStartLimit() {
        for (text in mutableListOf<String?>("é", "€", "😀")) {
            for (scanner in List.of<InputScanner?>(
                StringInputScanner(text), Utf8InputScanner(
                    text!!.toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
            )) {
                for (regex in mutableListOf<String?>(".", ".|", ".+")) {
                    val reverse = org.safere.Pattern.compile(regex).reverseDfa()
                    for (start in 1..<scanner.length) {
                        for (pass in 0..1) {
                            val result =
                                reverse.doSearchReverse(scanner, scanner.length, start, true, true)
                            Assertions.assertThat<Dfa.SearchResult?>(result)
                                .`as`("%s on %s from %s (%s)", regex, text, start, scanner.javaClass)
                                .isNotNull()
                            Assertions.assertThat(result!!.matched).isTrue()
                            Assertions.assertThat(result.pos)
                                .isEqualTo(if (regex == ".+") start else scanner.length - 1)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun reverseDfaRecordsDeferredMatchAtStartLimitWhenPreferredBranchCrossesIt() {
        // The character before startLimit only supplies assertion context. A higher-priority branch
        // that consumes it and reaches MATCH starts left of startLimit, so it must not hide the
        // deferred-assertion match that starts exactly at startLimit.
        data class Case(val regex: String?, val text: String?, val startLimit: Int)
        for (c in List.of<Case?>(
            Case("\\w?\\b-", "1b-", 2),
            Case("(\\w?)(b|\\b-)?", "1b-", 2),
            Case("x?\\Bb", "xb", 1),
            Case("(x?)(x|\\Bb)?", "xxb", 2),
            Case("(?m)\\n?^b", "\nb", 1),
            Case("\\w?\\b😀", "b😀", 1)
        )) {
            for (scanner in List.of<InputScanner?>(
                StringInputScanner(c.text), Utf8InputScanner(c.text!!.toByteArray(StandardCharsets.UTF_8))
            )) {
                val startLimit =
                    if (scanner is Utf8InputScanner)
                        c.text.substring(0, c.startLimit).toByteArray(StandardCharsets.UTF_8).size
                    else
                        c.startLimit
                val reverse = org.safere.Pattern.compile(c.regex).reverseDfa()
                for (pass in 0..1) {
                    val result =
                        reverse.doSearchReverse(scanner, scanner.length, startLimit, true, true)
                    Assertions.assertThat<Dfa.SearchResult?>(result).`as`("%s (%s)", c, scanner.javaClass).isNotNull()
                    Assertions.assertThat(result!!.matched).`as`("%s (%s)", c, scanner.javaClass).isTrue()
                    Assertions.assertThat(result.pos).`as`("%s (%s)", c, scanner.javaClass).isEqualTo(startLimit)
                }
            }
        }
    }

    @Test
    fun utf8FindAllKeepsDeferredAssertionMatchAtPreviousMatchEnd() {
        data class Case(val regex: String?, val text: String?, val expected: MutableList<String?>?)
        for (c in List.of<Case?>(
            Case("(\\w?)(b|\\b-)?", "1b-", mutableListOf<String?>("0:2", "2:3", "3:3")),
            Case("(x?)(x|\\Bb)?", "xxb", mutableListOf<String?>("0:2", "2:3", "3:3")),
            Case("(?m)(\\n?)(\\n|^b)?", "\n\nb", mutableListOf<String?>("0:2", "2:3", "3:3")),
            Case("(\\w?)(b|\\b😀)?", "1b😀", mutableListOf<String?>("0:2", "2:6", "6:6"))
        )) {
            val pattern = org.safere.Pattern.compile(c.regex)
            for (pass in 0..1) {
                val matcher = pattern.matcher(Utf8Input.validated(c.text!!.toByteArray(StandardCharsets.UTF_8)))
                val spans: MutableList<String?> = ArrayList<String?>()
                while (matcher.find()) {
                    spans.add(matcher.start().toString() + ":" + matcher.end())
                    // The second pass reads captures, which records capture demand on the pattern.
                    if (pass == 1) {
                        Assertions.assertThat(matcher.start(1)).isGreaterThanOrEqualTo(matcher.start())
                    }
                }
                Assertions.assertThat<String?>(spans).`as`("%s pass %s", c, pass).isEqualTo(c.expected)
            }
        }
    }

    @Test
    fun reverseDfaPruningCorrectness() {
        val p = org.safere.Pattern.compile("\\B([^a])*[^a][^a]")
        val revDfa = p.reverseDfa()
        Assertions.assertThat<Dfa?>(revDfa).isNotNull()

        val text = "bbbb"
        val r = revDfa.doSearchReverse(text, 4, 0, true, true)
        Assertions.assertThat<Dfa.SearchResult?>(r).isNotNull()
        Assertions.assertThat(r!!.matched).isTrue()
        Assertions.assertThat(r.pos).isEqualTo(1)
    }

    @Test
    fun reverseDfaDeferredMatchCorrectness() {
        val p = org.safere.Pattern.compile("\\ba|a")
        val revDfa = p.reverseDfa()
        Assertions.assertThat<Dfa?>(revDfa).isNotNull()

        val text = "a"
        val r = revDfa.doSearchReverse(text, 1, 0, true, true)
        Assertions.assertThat<Dfa.SearchResult?>(r).isNotNull()
        Assertions.assertThat(r!!.matched).isTrue()
        Assertions.assertThat(r.pos).isEqualTo(0)

        val text2 = "aa"
        val r2 = revDfa.doSearchReverse(text2, 2, 1, true, true)
        Assertions.assertThat<Dfa.SearchResult?>(r2).isNotNull()
        Assertions.assertThat(r2!!.matched).isTrue()
        Assertions.assertThat(r2.pos).isEqualTo(1)
    }

    @Test
    fun reverseDfaMixedDeferredAndConsumingMatchPreservesDeferredStart() {
        val p = org.safere.Pattern.compile("(?:(?:\\ba?)|\\B|[^a])a?")
        val revDfa = p.reverseDfa()
        Assertions.assertThat<Dfa?>(revDfa).isNotNull()

        val r = revDfa.doSearchReverse("ba", 2, 1, true, true)

        Assertions.assertThat<Dfa.SearchResult?>(r).isNotNull()
        Assertions.assertThat(r!!.matched).isTrue()
        Assertions.assertThat(r.pos).isEqualTo(1)
    }

    @Test
    fun reverseDfaAsciiFastPathPreservesAnchoredStartFilter() {
        val p = org.safere.Pattern.compile("^a")
        val revDfa = p.reverseDfa()
        Assertions.assertThat<Dfa?>(revDfa).isNotNull()

        val first = revDfa.doSearchReverse("xa", 2, 0, true, true)
        val second = revDfa.doSearchReverse("xa", 2, 0, true, true)

        Assertions.assertThat<Dfa.SearchResult?>(first).isNotNull()
        Assertions.assertThat<Dfa.SearchResult?>(second).isNotNull()
        Assertions.assertThat(first!!.matched).isFalse()
        Assertions.assertThat(second!!.matched).isFalse()
    }

    @Test
    fun reverseDfaAsciiFastPathPreservesDeferredMatchAmbiguity() {
        val p = org.safere.Pattern.compile("(?:\\B{1}|a).")
        val revDfa = p.reverseDfa()
        Assertions.assertThat<Dfa?>(revDfa).isNotNull()

        val first = revDfa.doSearchReverse("ab", 2, 0, true, true)
        val second = revDfa.doSearchReverse("ab", 2, 0, true, true)

        Assertions.assertThat<Dfa.SearchResult?>(first).isNotNull()
        Assertions.assertThat<Dfa.SearchResult?>(second).isNotNull()
        Assertions.assertThat(first!!.matched).isTrue()
        Assertions.assertThat(second!!.matched).isTrue()
        Assertions.assertThat(first.ambiguous).isTrue()
        Assertions.assertThat(second.ambiguous).isTrue()
    }

    @Test
    fun reverseDfaAsciiFastPathPreservesAcceptedAfterMatchAmbiguity() {
        val p = org.safere.Pattern.compile("^(?:\\B|a)b")
        val revDfa = p.reverseDfa()
        Assertions.assertThat<Dfa?>(revDfa).isNotNull()

        val first = revDfa.doSearchReverse("ab", 2, 0, true, true)
        val second = revDfa.doSearchReverse("ab", 2, 0, true, true)

        Assertions.assertThat<Dfa.SearchResult?>(first).isNotNull()
        Assertions.assertThat<Dfa.SearchResult?>(second).isNotNull()
        Assertions.assertThat(first!!.matched).isTrue()
        Assertions.assertThat(second!!.matched).isTrue()
        Assertions.assertThat(first.ambiguous).isTrue()
        Assertions.assertThat(second.ambiguous).isTrue()
    }

    @Nested
    @DisplayName("Self-Loop Escape Acceleration")
    internal inner class SelfLoopEscapeAcceleration {
        @Test
        fun quotedStringLongPayload() {
            val filler = "x".repeat(5000)
            val text = "prefix \"" + filler + "\" suffix"
            val result: Dfa.SearchResult = search("\"[^\"]*\"", text)
            Assertions.assertThat<Dfa.SearchResult?>(result).isNotNull()
            Assertions.assertThat(result.matched).isTrue()
            Assertions.assertThat(result.pos).isEqualTo(8 + 5000 + 1)
        }

        @Test
        fun headerLineTerminatorLongPayload() {
            val filler = "a".repeat(10000)
            val text = "header:" + filler + "\nrest"
            val result: Dfa.SearchResult = search("header:[^\n]*\n", text)
            Assertions.assertThat<Dfa.SearchResult?>(result).isNotNull()
            Assertions.assertThat(result.matched).isTrue()
            Assertions.assertThat(result.pos).isEqualTo(7 + 10000 + 1)
        }

        @Test
        fun htmlTagLongPayload() {
            val filler = "content".repeat(1000)
            val text = "before <div " + filler + "> after"
            val result: Dfa.SearchResult = search("<[^>]*>", text)
            Assertions.assertThat<Dfa.SearchResult?>(result).isNotNull()
            Assertions.assertThat(result.matched).isTrue()
            Assertions.assertThat(result.pos).isEqualTo(12 + 7000 + 1)
        }

        @Test
        fun multiEscapeBytePair() {
            val filler = "data".repeat(1000)
            val text = "prefix " + filler + ",suffix"
            val result: Dfa.SearchResult = search("[^,\n]*,", text)
            Assertions.assertThat<Dfa.SearchResult?>(result).isNotNull()
            Assertions.assertThat(result.matched).isTrue()
            Assertions.assertThat(result.pos).isEqualTo(7 + 4000 + 1)
        }

        @Test
        fun selfLoopNoEscapeFoundUntilEof() {
            val filler = "x".repeat(5000)
            val text = "start \"" + filler
            val result: Dfa.SearchResult = search("\"[^\"]*\"", text)
            Assertions.assertThat<Dfa.SearchResult?>(result).isNotNull()
            Assertions.assertThat(result.matched).isFalse()
        }

        @Test
        fun selfLoopStopsAtNonAsciiTransitions() {
            assertNonAsciiEscapeMatches("é")
            assertNonAsciiEscapeMatches("😀")
        }

        @Test
        fun startAccelerationUsesOnlyTheActiveInputSubstrate() {
            val pattern = org.safere.Pattern.compile("(?m)^foo")
            val dfa = pattern.forwardFirstMatchDfa()

            Assertions.assertThat(dfa.hasStartAcceleration(StringInputScanner("foo"))).isTrue()
            Assertions.assertThat(dfa.hasStartAcceleration(Utf8InputScanner("foo".toByteArray(StandardCharsets.UTF_8))))
                .isFalse()
        }

        @Test
        fun selfLoopUtf8ScannerEquivalence() {
            val filler = "x".repeat(2000)
            val text = "start \"" + filler + "\" end"
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            val re = Parser.parse("\"[^\"]*\"", FLAGS)
            val prog = Compiler.compile(re)
            val scanner = Utf8InputScanner(bytes, 0, bytes.size)
            val dfa = Dfa(prog, 1000, Dfa.buildSetup(prog), false)
            val result = dfa.doSearch(scanner, 0, false, false)
            Assertions.assertThat<Dfa.SearchResult?>(result).isNotNull()
            Assertions.assertThat(result!!.matched).isTrue()
            Assertions.assertThat(result.pos).isEqualTo(7 + 2000 + 1)
        }

        companion object {
            private fun assertNonAsciiEscapeMatches(escape: String) {
                val pattern = "A[\\x00-\\x21\\x23-\\x7F]*" + escape
                val text = "A" + "x".repeat(1000) + escape
                val compiledPattern = org.safere.Pattern.compile(pattern)
                Assertions.assertThat(compiledPattern.matcher(text).find()).isTrue()
                Assertions.assertThat(compiledPattern.find(Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8))))
                    .isTrue()

                val stringResult: Dfa.SearchResult = search(pattern, text)
                Assertions.assertThat<Dfa.SearchResult?>(stringResult).isNotNull()
                Assertions.assertThat(stringResult.matched).isTrue()

                val bytes = text.toByteArray(StandardCharsets.UTF_8)
                val re = Parser.parse(pattern, FLAGS)
                val prog = Compiler.compile(re)
                val utf8Result =
                    Dfa(prog, 1000, Dfa.buildSetup(prog), false)
                        .doSearch(Utf8InputScanner(bytes, 0, bytes.size), 0, false, false)
                Assertions.assertThat<Dfa.SearchResult?>(utf8Result).isNotNull()
                Assertions.assertThat(utf8Result!!.matched).isTrue()
            }
        }
    }

    @Test
    fun multilineAnchorAlternationRejectsFalseUtf8Candidate() {
        val regex = "(b|(?m:^a))cd[0-9]"
        val input = "x".repeat(100) + "0cb\r1bacd19c1__19x y_"
        val enabled = EnginePathOptions.builder().startAcceleration(true).build()
        val disabled = EnginePathOptions.builder().startAcceleration(false).build()
        val accelerated = org.safere.Pattern.compile(regex, 0, enabled)
        val control = org.safere.Pattern.compile(regex, 0, disabled)
        val utf8 = Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))

        Assertions.assertThat(control.find(utf8)).isFalse()
        Assertions.assertThat(accelerated.find(utf8))
            .`as`("Accelerated DFA must not match (?m:^a) when 'a' is not after line terminator")
            .isEqualTo(control.find(utf8))
    }

    @Test
    fun multilineAnchorAlternationRejectsFalseStringCandidate() {
        val regex = "(b|(?m:^a))cd[0-9]"
        val input = "x".repeat(100) + "0cb\r1bacd19c1__19x y_"
        val enabled = EnginePathOptions.builder().startAcceleration(true).build()
        val disabled = EnginePathOptions.builder().startAcceleration(false).build()
        val accelerated = org.safere.Pattern.compile(regex, 0, enabled)
        val control = org.safere.Pattern.compile(regex, 0, disabled)

        Assertions.assertThat(control.matcher(input).find()).isFalse()
        Assertions.assertThat(accelerated.matcher(input).find())
            .`as`("Accelerated DFA String search must match unaccelerated control")
            .isEqualTo(control.matcher(input).find())
    }

    @Test
    fun wordBoundaryAnchorAlternationWithFalseCandidate() {
        val regex = "(b|\\ba)cd[0-9]"
        val input = "x".repeat(100) + "0cb_bacd19c1__19x y_"
        val enabled = EnginePathOptions.builder().startAcceleration(true).build()
        val disabled = EnginePathOptions.builder().startAcceleration(false).build()
        val accelerated = org.safere.Pattern.compile(regex, 0, enabled)
        val control = org.safere.Pattern.compile(regex, 0, disabled)

        Assertions.assertThat(control.matcher(input).find()).isFalse()
        Assertions.assertThat(accelerated.matcher(input).find()).isEqualTo(control.matcher(input).find())
    }

    @Test
    fun startOfTextAnchorAlternationWithFalseCandidate() {
        val regex = "(b|\\Aa)cd[0-9]"
        val input = "x".repeat(100) + "0cb1bacd19c1__19x y_"
        val enabled = EnginePathOptions.builder().startAcceleration(true).build()
        val disabled = EnginePathOptions.builder().startAcceleration(false).build()
        val accelerated = org.safere.Pattern.compile(regex, 0, enabled)
        val control = org.safere.Pattern.compile(regex, 0, disabled)

        Assertions.assertThat(control.matcher(input).find()).isFalse()
        Assertions.assertThat(accelerated.matcher(input).find()).isEqualTo(control.matcher(input).find())
    }

    @Test
    fun multilineAnchorTrueCandidateMatches() {
        val regex = "(b|(?m:^a))cd[0-9]"
        val input = "x".repeat(100) + "0cb\nacd19c1__19x y_"
        val enabled = EnginePathOptions.builder().startAcceleration(true).build()
        val accelerated = org.safere.Pattern.compile(regex, 0, enabled)

        Assertions.assertThat(accelerated.matcher(input).find()).isTrue()
        Assertions.assertThat(accelerated.find(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))).isTrue()
    }

    @Test
    fun automataDerivedStartStateAcceleratesOptionalWhitespaceBracket() {
        val pattern = org.safere.Pattern.compile("[ \\t]*\\[\\[.*?\\]\\]")
        val text = "x".repeat(1000) + "[[test]]" + "y".repeat(1000)
        Assertions.assertThat(pattern.matcher(text).find()).isTrue()
        Assertions.assertThat(pattern.find(Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8)))).isTrue()
    }

    @Test
    fun automataDerivedStartStateAcceleratesAlternations() {
        val re = Parser.parse("apple|banana|cherry", FLAGS)
        val prog = Compiler.compile(re)
        val dfa = Dfa(prog, 1000, Dfa.buildSetup(prog), false)
        val scanner: InputScanner = StringInputScanner("x".repeat(100))
        val s = dfa.startState(scanner, 0, false)
        Assertions.assertThat<StateAccelerator?>(s.accelerator).isNotNull()
        Assertions.assertThat<StateAccelerator?>(s.accelerator).isInstanceOf(AsciiTripleEscape::class.java)

        val pattern = org.safere.Pattern.compile("apple|banana|cherry")
        val text = "x".repeat(500) + "banana" + "y".repeat(500)
        Assertions.assertThat(pattern.matcher(text).find()).isTrue()
        Assertions.assertThat(pattern.find(Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8)))).isTrue()

        val noMatch = "x".repeat(1000)
        Assertions.assertThat(pattern.matcher(noMatch).find()).isFalse()
        Assertions.assertThat(pattern.find(Utf8Input.validated(noMatch.toByteArray(StandardCharsets.UTF_8)))).isFalse()
    }

    @Test
    fun automataDerivedStartStateAcceleratesMultiTokenAlternation() {
        val pattern = org.safere.Pattern.compile("(?:image-tokens|video-tokens|pdf-tokens)")
        val text = "x".repeat(500) + "video-tokens" + "y".repeat(500)
        Assertions.assertThat(pattern.matcher(text).find()).isTrue()
        Assertions.assertThat(pattern.find(Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8)))).isTrue()

        val noMatch = "x".repeat(1000)
        Assertions.assertThat(pattern.matcher(noMatch).find()).isFalse()
        Assertions.assertThat(pattern.find(Utf8Input.validated(noMatch.toByteArray(StandardCharsets.UTF_8)))).isFalse()
    }

    @Test
    fun automataDerivedStartStateAcceleratesDisjointRangeAlternation() {
        val pattern = org.safere.Pattern.compile("[0-9]{3}|[a-z]{3}")
        val text = "---".repeat(100) + "123" + "---".repeat(100)
        Assertions.assertThat(pattern.matcher(text).find()).isTrue()
        Assertions.assertThat(pattern.find(Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8)))).isTrue()

        val noMatch = "---".repeat(200)
        Assertions.assertThat(pattern.matcher(noMatch).find()).isFalse()
        Assertions.assertThat(pattern.find(Utf8Input.validated(noMatch.toByteArray(StandardCharsets.UTF_8)))).isFalse()
    }

    @Test
    fun wordBoundaryLongText() {
        val regex = "(?:\\w(?:\\b))"
        val input = "#".repeat(260) + "a"
        val pattern = org.safere.Pattern.compile(regex)
        Assertions.assertThat(pattern.matcher(input).find()).isTrue()
        Assertions.assertThat(pattern.find(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))).isTrue()
    }

    @Test
    fun matchDenseNumericFindAllDoesNotUseStartAcceleration() {
        val pattern = org.safere.Pattern.compile("\\d{3}/\\d{3}/\\d{4}")
        val text = ByteArray(32768)
        val random = Random(42)
        for (i in text.indices) {
            text[i] = (47 + random.nextInt(11)).toByte() // '/', '0'-'9'
        }
        val matcher = pattern.matcher(Utf8Input.trusted(text, 0, text.length))
        var matches = 0
        while (matcher.find()) {
            matches++
        }
        Assertions.assertThat(matches).isEqualTo(112)
    }

    @Test
    fun automataDerivedAnchoredStartStateAcceleratesSelfLoop() {
        val regex = "[^\"]*\""
        val re = Parser.parse(regex, FLAGS or ParseFlags.DOT_NL or ParseFlags.CLASS_NL)
        val prog = Compiler.compile(re)
        val dfa = Dfa(prog, 1000, Dfa.buildSetup(prog), false)
        val scanner: InputScanner = StringInputScanner("a".repeat(100) + "\"")
        val s = dfa.startState(scanner, 0, true)
        Assertions.assertThat<StateAccelerator?>(s.accelerator).isNotNull()
        Assertions.assertThat<StateAccelerator?>(s.accelerator).isInstanceOf(SingleAsciiEscape::class.java)

        val pattern = org.safere.Pattern.compile(regex)
        val matching = "a\n".repeat(2048) + "\""
        Assertions.assertThat(pattern.matcher(matching).matches()).isTrue()
        Assertions.assertThat(
            pattern.matcher(Utf8Input.validated(matching.toByteArray(StandardCharsets.UTF_8))).matches()
        ).isTrue()

        val noMatch = "a\n".repeat(2048)
        Assertions.assertThat(pattern.matcher(noMatch).matches()).isFalse()
        Assertions.assertThat(
            pattern.matcher(Utf8Input.validated(noMatch.toByteArray(StandardCharsets.UTF_8))).matches()
        ).isFalse()
    }

    @Test
    fun automataDerivedAnchoredStartStateAcceleratesPairEscapes() {
        val regex = "[^\"]*\""
        val re = Parser.parse(regex, FLAGS)
        val prog = Compiler.compile(re)
        val dfa = Dfa(prog, 1000, Dfa.buildSetup(prog), false)
        val scanner: InputScanner = StringInputScanner("a".repeat(100) + "\"")
        val s = dfa.startState(scanner, 0, true)
        Assertions.assertThat<StateAccelerator?>(s.accelerator).isNotNull()
        Assertions.assertThat<StateAccelerator?>(s.accelerator).isInstanceOf(AsciiPairEscape::class.java)

        val pattern = org.safere.Pattern.compile(regex)
        val matching = "a".repeat(4096) + "\""
        Assertions.assertThat(pattern.matcher(matching).matches()).isTrue()
        Assertions.assertThat(
            pattern.matcher(Utf8Input.validated(matching.toByteArray(StandardCharsets.UTF_8))).matches()
        ).isTrue()

        val noMatch = "a".repeat(4096)
        Assertions.assertThat(pattern.matcher(noMatch).matches()).isFalse()
        Assertions.assertThat(
            pattern.matcher(Utf8Input.validated(noMatch.toByteArray(StandardCharsets.UTF_8))).matches()
        ).isFalse()
    }

    @Test
    fun automataDerivedInteriorStateAcceleratesSelfLoop() {
        val regex = "prefix:\\s*[^;]+;\\s*suffix"
        val pattern = org.safere.Pattern.compile(regex)

        val matching = "prefix: " + "a".repeat(4096) + "; suffix"
        Assertions.assertThat(pattern.matcher(matching).find()).isTrue()
        Assertions.assertThat(pattern.matcher(matching).matches()).isTrue()
        Assertions.assertThat(pattern.matcher(Utf8Input.validated(matching.toByteArray(StandardCharsets.UTF_8))).find())
            .isTrue()
        Assertions.assertThat(
            pattern.matcher(Utf8Input.validated(matching.toByteArray(StandardCharsets.UTF_8))).matches()
        ).isTrue()

        val noMatch = "prefix: " + "a".repeat(4096)
        Assertions.assertThat(pattern.matcher(noMatch).find()).isFalse()
        Assertions.assertThat(pattern.matcher(noMatch).matches()).isFalse()
        Assertions.assertThat(pattern.matcher(Utf8Input.validated(noMatch.toByteArray(StandardCharsets.UTF_8))).find())
            .isFalse()
        Assertions.assertThat(
            pattern.matcher(Utf8Input.validated(noMatch.toByteArray(StandardCharsets.UTF_8))).matches()
        ).isFalse()
    }

    @Test
    fun adaptiveQuarantineOnAdversarialPeriodicNoise() {
        val regex = "q[0-9]{4}z"
        val pattern = org.safere.Pattern.compile(regex)

        // 2,000 repetitions of "q1234a" (12,000 bytes). Distance between 'q' is 6 bytes.
        // Downstream fails on 'a' instead of 'z'.
        val adversarialNoise = "q1234a".repeat(2000)
        Assertions.assertThat(pattern.matcher(adversarialNoise).find()).isFalse()
        Assertions.assertThat(
            pattern.matcher(Utf8Input.validated(adversarialNoise.toByteArray(StandardCharsets.UTF_8))).find()
        )
            .isFalse()

        // Adversarial noise followed by an actual valid match at the end
        val withMatch = adversarialNoise + "q1234z"
        val stringMatcher = pattern.matcher(withMatch)
        Assertions.assertThat(stringMatcher.find()).isTrue()
        Assertions.assertThat(stringMatcher.start()).isEqualTo(adversarialNoise.length)
        Assertions.assertThat(stringMatcher.end()).isEqualTo(withMatch.size)

        val utf8Matcher = pattern.matcher(Utf8Input.validated(withMatch.toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(utf8Matcher.find()).isTrue()
        Assertions.assertThat(utf8Matcher.start()).isEqualTo(adversarialNoise.length)
        Assertions.assertThat(utf8Matcher.end()).isEqualTo(withMatch.size)
    }

    @Test
    fun burstyHeaderRecoversAccelerationInCleanBody() {
        val regex = "q[0-9]{4}z"
        val pattern = org.safere.Pattern.compile(regex)

        // 100 repetitions of noise (600 bytes header) followed by 50 KB of clean text, ending in a
        // match
        val header = "q1234a".repeat(100)
        val cleanBody = "the quick brown fox jumps over the lazy dog. ".repeat(1000)
        val target = "q1234z"
        val input = header + cleanBody + target

        val stringMatcher = pattern.matcher(input)
        Assertions.assertThat(stringMatcher.find()).isTrue()
        Assertions.assertThat(stringMatcher.start()).isEqualTo(header.length + cleanBody.length)
        Assertions.assertThat(stringMatcher.end()).isEqualTo(input.length)

        val utf8Matcher = pattern.matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(utf8Matcher.find()).isTrue()
        Assertions.assertThat(utf8Matcher.start()).isEqualTo(header.length + cleanBody.length)
        Assertions.assertThat(utf8Matcher.end()).isEqualTo(input.length)
    }

    @Test
    fun denseConsecutiveValidMatchesNotThrottled() {
        val regex = "q[0-9]{4}z"
        val pattern = org.safere.Pattern.compile(regex)

        // 50 valid consecutive matches back-to-back
        val denseMatches = "q1234z".repeat(50)
        val stringMatcher = pattern.matcher(denseMatches)
        var matchCount = 0
        while (stringMatcher.find()) {
            matchCount++
        }
        Assertions.assertThat(matchCount).isEqualTo(50)

        val utf8Matcher = pattern.matcher(Utf8Input.validated(denseMatches.toByteArray(StandardCharsets.UTF_8)))
        matchCount = 0
        while (utf8Matcher.find()) {
            matchCount++
        }
        Assertions.assertThat(matchCount).isEqualTo(50)
    }

    @Test
    fun dfaReacceleratesStartStateAfterFalseCandidate() {
        val pattern = org.safere.Pattern.compile("fo[0-9]+")
        val text = "foox" + "a".repeat(100000)
        val utf8Input = Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8))
        Assertions.assertThat(pattern.find(utf8Input)).isFalse()

        val stringMatcher = pattern.matcher(text)
        Assertions.assertThat(stringMatcher.find()).isFalse()
    }

    @Test
    fun dfaReacceleratesToNextMatchAfterFalseCandidate() {
        val pattern = org.safere.Pattern.compile("fo[0-9]+")
        val text = "foox" + "a".repeat(100000) + "fo123"
        val utf8Input = Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8))
        Assertions.assertThat(pattern.find(utf8Input)).isTrue()

        val stringMatcher = pattern.matcher(text)
        Assertions.assertThat(stringMatcher.find()).isTrue()
        Assertions.assertThat(stringMatcher.start()).isEqualTo(4 + 100000)
        Assertions.assertThat(stringMatcher.end()).isEqualTo(4 + 100000 + 5)
    }

    @Test
    fun dfaFastForwardAcceptingStartStateMatch() {
        val regexp = "^(?:(?:b|(?:$)))"
        val text = "#".repeat(260) + "\n\nbb"
        val utf8 = text.toByteArray(StandardCharsets.UTF_8)

        val pat = org.safere.Pattern.compile(regexp, org.safere.Pattern.MULTILINE)
        val utfM = pat.matcher(Utf8Input.validated(utf8))
        Assertions.assertThat(utfM.find()).isTrue()
        Assertions.assertThat(utfM.start()).isEqualTo(261)
        Assertions.assertThat(utfM.end()).isEqualTo(261)
        Assertions.assertThat(utfM.find()).isTrue()
        Assertions.assertThat(utfM.start()).isEqualTo(262)
        Assertions.assertThat(utfM.end()).isEqualTo(263)
        Assertions.assertThat(utfM.find()).isFalse()

        val strM = pat.matcher(text)
        Assertions.assertThat(strM.find()).isTrue()
        Assertions.assertThat(strM.start()).isEqualTo(261)
        Assertions.assertThat(strM.end()).isEqualTo(261)
    }

    companion object {
        private val FLAGS =
            ParseFlags.PERL_X or ParseFlags.PERL_CLASSES or ParseFlags.PERL_B or ParseFlags.UNICODE_GROUPS

        /** Compiles a pattern and searches with the DFA (unanchored, first match).  */
        private fun search(pattern: String?, text: String?): Dfa.SearchResult {
            val re = Parser.parse(pattern, FLAGS)
            val prog = Compiler.compile(re)
            return Dfa.search(prog, text, false, false)
        }

        /** Compiles a pattern and searches with the DFA (anchored, longest match = full match).  */
        private fun fullMatch(pattern: String?, text: String): Dfa.SearchResult? {
            val re = Parser.parse(pattern, FLAGS)
            val prog = Compiler.compile(re)
            val r = Dfa.search(prog, text, true, true)
            if (r != null && r.matched && r.pos != text.length) {
                // Match didn't cover the entire text — not a full match.
                return Dfa.SearchResult(false, r.pos)
            }
            return r
        }

        /** Compiles a pattern and searches with the DFA (unanchored, longest match).  */
        private fun longestMatch(pattern: String?, text: String?): Dfa.SearchResult {
            val re = Parser.parse(pattern, FLAGS)
            val prog = Compiler.compile(re)
            return Dfa.search(prog, text, false, true)
        }
    }
}
