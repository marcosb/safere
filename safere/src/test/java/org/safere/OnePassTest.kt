// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.List
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tests for [OnePass].
 * 
 * 
 * Tests verify: (1) correct one-pass detection, (2) match results agree with the NFA, and (3)
 * capture groups are correctly extracted.
 */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal object OnePassTest {
    private val FLAGS = ParseFlags.PERL_X or ParseFlags.PERL_CLASSES or ParseFlags.PERL_B or ParseFlags.UNICODE_GROUPS

    /** Helper: build a OnePass automaton, or null if not one-pass.  */
    private fun build(pattern: String?): OnePass? {
        val re = Parser.parse(pattern, FLAGS)
        val prog = Compiler.compile(re)
        return OnePass.build(prog)
    }

    private fun distinctLiteralRun(count: Int): String {
        val pattern = StringBuilder(count)
        for (i in 0..<count) {
            pattern.appendCodePoint(0x1000 + i * 2)
        }
        return pattern.toString()
    }

    /** Helper: run one-pass search (anchored, endMatch=false).  */
    private fun search(pattern: String?, text: String?): IntArray? {
        val re = Parser.parse(pattern, FLAGS)
        val prog = Compiler.compile(re)
        val op = OnePass.build(prog)
        Assertions.assertThat<OnePass?>(op).`as`("Pattern '%s' should be one-pass", pattern).isNotNull()
        return op!!.search(text, false, prog.numCaptures())
    }

    /** Helper: run one-pass search (anchored, endMatch=true, full match).  */
    private fun fullMatch(pattern: String?, text: String?): IntArray? {
        val re = Parser.parse(pattern, FLAGS)
        val prog = Compiler.compile(re)
        val op = OnePass.build(prog)
        Assertions.assertThat<OnePass?>(op).`as`("Pattern '%s' should be one-pass", pattern).isNotNull()
        return op!!.search(text, true, prog.numCaptures())
    }

    /** Asserts that OnePass and NFA agree on an anchored search.  */
    private fun assertConsistentWithNfa(pattern: String?, text: String?, endMatch: Boolean) {
        val re = Parser.parse(pattern, FLAGS)
        val prog = Compiler.compile(re)
        val op = OnePass.build(prog)
        if (op == null) {
            return  // not one-pass; skip
        }
        val opResult = op.search(text, endMatch, prog.numCaptures())
        val kind = if (endMatch) Nfa.MatchKind.FULL_MATCH else Nfa.MatchKind.FIRST_MATCH
        val nfaResult = Nfa.search(prog, text, Nfa.Anchor.ANCHORED, kind, prog.numCaptures())

        if (nfaResult == null) {
            Assertions.assertThat(opResult)
                .`as`("/%s/ on \"%s\": NFA=null, OnePass should too", pattern, text)
                .isNull()
        } else {
            Assertions.assertThat(opResult)
                .`as`("/%s/ on \"%s\": NFA matched, OnePass should too", pattern, text)
                .isNotNull()
            // Compare capture groups.
            val len = min(opResult!!.size, nfaResult.size)
            for (i in 0..<len) {
                Assertions.assertThat(opResult[i])
                    .`as`("/%s/ on \"%s\": cap[%d]", pattern, text, i)
                    .isEqualTo(nfaResult[i])
            }
        }
    }

    // ---------------------------------------------------------------------------
    // One-pass detection
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("One-pass detection")
    internal inner class Detection {
        @Test
        fun simpleOnePassPatterns() {
            // These should all be one-pass.
            Assertions.assertThat<OnePass?>(build("abc")).isNotNull()
            Assertions.assertThat<OnePass?>(build("a")).isNotNull()
            Assertions.assertThat<OnePass?>(build("[a-z]")).isNotNull()
            Assertions.assertThat<OnePass?>(build("a.c")).isNotNull()
            Assertions.assertThat<OnePass?>(build("x(y|z)")).isNotNull()
            Assertions.assertThat<OnePass?>(build("\\d+-\\d+")).isNotNull()
            Assertions.assertThat<OnePass?>(build("[^ ]+ .*")).isNotNull()
            Assertions.assertThat<OnePass?>(build("(?i)abc")).isNotNull()
            Assertions.assertThat<OnePass?>(build("(?i)[a-z]+")).isNotNull()
            Assertions.assertThat<OnePass?>(build("(?i)id:([0-9]+)")).isNotNull()
        }

        @Test
        fun caseInsensitiveOnePass() {
            Assertions.assertThat<OnePass?>(build("(?i)apple")).isNotNull()
            Assertions.assertThat<OnePass?>(build("(?i)key:([a-z]+)")).isNotNull()
            val r = search("(?i)abc", "ABCdef")
            Assertions.assertThat(r).isNotNull()
            Assertions.assertThat(r!![0]).isEqualTo(0)
            Assertions.assertThat(r[1]).isEqualTo(3)
        }

        @Test
        @Throws(InterruptedException::class)
        fun unicodeClassLookupIsSafeAcrossConcurrentMatchers() {
            val onePass = build("(?iu)\u0100")
            Assertions.assertThat<OnePass?>(onePass).isNotNull()

            val threadCount = 8
            val start = CountDownLatch(1)
            val failure = AtomicReference<String?>()
            val threads: MutableList<Thread> = ArrayList<Thread>()
            for (threadIndex in 0..<threadCount) {
                val expected = (threadIndex and 1) == 0
                val input = if (expected) "\u0100" else "\u0200"
                val thread =
                    Thread.ofPlatform()
                        .start(
                            Runnable {
                                try {
                                    start.await()
                                    val scanner: InputScanner = StringInputScanner(input)
                                    val result = IntArray(2)
                                    val scratch = IntArray(2)
                                    var iteration = 0
                                    while (iteration < 100000 && failure.get() == null
                                    ) {
                                        val matched =
                                            (onePass!!.search(scanner, 0, scanner.length, true, 1, result, scratch)
                                                    != null)
                                        if (matched != expected) {
                                            failure.compareAndSet(
                                                null,
                                                ("expected "
                                                        + expected
                                                        + " for U+"
                                                        + Integer.toHexString(input.get(0).code))
                                            )
                                        }
                                        iteration++
                                    }
                                } catch (e: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    failure.compareAndSet(null, "worker interrupted")
                                }
                            })
                threads.add(thread!!)
            }

            start.countDown()
            for (thread in threads) {
                thread.join()
            }
            Assertions.assertThat(failure.get()).isNull()
        }

        @Test
        fun notOnePassPatterns() {
            // These should NOT be one-pass.
            Assertions.assertThat<OnePass?>(build("(xy|xz)")).isNull() // both start with x
            Assertions.assertThat<OnePass?>(build("(.*)(.*)")).isNull() // two greedy .* compete
        }

        @Test
        fun tooManyCaptures() {
            // More than 16 capture groups (including group 0) -> not one-pass.
            // 16 user groups = 17 total -> exceeds MAX_CAPTURE_GROUPS.
            Assertions.assertThat<OnePass?>(build("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)(l)(m)(n)(o)(p)")).isNull()
        }

        @Test
        fun maxCaptures() {
            // Exactly 16 total captures (15 user groups + group 0) -> still one-pass.
            Assertions.assertThat<OnePass?>(build("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)(l)(m)(n)(o)")).isNotNull()
        }

        @Test
        fun emptyPattern() {
            Assertions.assertThat<OnePass?>(build("")).isNotNull()
        }

        @Test
        @DisplayName("oversized transition tables are rejected before allocation")
        fun oversizedTransitionTablesAreRejectedBeforeAllocation() {
            val literalCount = ceil(sqrt(OnePass.MAX_ACTION_CELLS / 2.0)).toInt() + 64

            Assertions.assertThat<OnePass?>(build(distinctLiteralRun(literalCount))).isNull()
        }

        @Test
        @DisplayName("action offsets must fit alongside assertion and capture bits")
        fun oversizedActionOffsetsUseFallback() {
            // These tables fit the memory budget, but their offsets exceed the packed action field.
            Assertions.assertThat<OnePass?>(build("(" + distinctLiteralRun(300) + ")")).isNull()
        }

        @Test
        @DisplayName("the memory budget includes both dense action tables")
        fun memoryBudgetIncludesBothDenseActionTables() {
            // A distinct literal run has roughly N states and 2N equivalence classes. At this size each
            // dense table fits the budget independently, but the two tables exceed it together.
            val literalCount = ceil(sqrt(OnePass.MAX_ACTION_CELLS / 4.0)).toInt() + 4

            Assertions.assertThat<OnePass?>(build(distinctLiteralRun(literalCount))).isNull()
        }
    }

    // ---------------------------------------------------------------------------
    // Basic matching
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Basic matching")
    internal inner class BasicMatching {
        @Test
        fun literalMatch() {
            val r = search("abc", "abcdef")
            Assertions.assertThat(r).isNotNull()
            Assertions.assertThat(r!![0]).isEqualTo(0)
            Assertions.assertThat(r[1]).isEqualTo(3)
        }

        @Test
        fun literalNoMatch() {
            val r = search("abc", "xyzdef")
            Assertions.assertThat(r).isNull()
        }

        @Test
        fun fullMatchSuccess() {
            val r = fullMatch("abc", "abc")
            Assertions.assertThat(r).isNotNull()
            Assertions.assertThat(r!![0]).isEqualTo(0)
            Assertions.assertThat(r[1]).isEqualTo(3)
        }

        @Test
        fun fullMatchFailure() {
            val r = fullMatch("abc", "abcd")
            Assertions.assertThat(r).isNull()
        }

        @Test
        fun emptyMatch() {
            val r = search("", "anything")
            Assertions.assertThat(r).isNotNull()
            Assertions.assertThat(r!![0]).isEqualTo(0)
            Assertions.assertThat(r[1]).isEqualTo(0)
        }

        @Test
        fun dotMatch() {
            val r = search("a.c", "abc")
            Assertions.assertThat(r).isNotNull()
            Assertions.assertThat(r!![1]).isEqualTo(3)
        }

        @Test
        fun charClassMatch() {
            val r = search("[a-z]+", "hello123")
            Assertions.assertThat(r).isNotNull()
            Assertions.assertThat(r!![0]).isEqualTo(0)
            Assertions.assertThat(r[1]).isEqualTo(5)
        }
    }

    // ---------------------------------------------------------------------------
    // Capture groups
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Capture groups")
    internal inner class CaptureGroups {
        @Test
        fun singleGroup() {
            val r = search("(abc)", "abcdef")
            Assertions.assertThat(r).isNotNull()
            Assertions.assertThat(r!![0]).isEqualTo(0) // group 0 start
            Assertions.assertThat(r[1]).isEqualTo(3) // group 0 end
            Assertions.assertThat(r[2]).isEqualTo(0) // group 1 start
            Assertions.assertThat(r[3]).isEqualTo(3) // group 1 end
        }

        @Test
        fun twoGroups() {
            val r = search("(\\d+)-(\\d+)", "123-456rest")
            Assertions.assertThat(r).isNotNull()
            Assertions.assertThat(r!![0]).isEqualTo(0)
            Assertions.assertThat(r[1]).isEqualTo(7)
            Assertions.assertThat(r[2]).isEqualTo(0) // group 1: "123"
            Assertions.assertThat(r[3]).isEqualTo(3)
            Assertions.assertThat(r[4]).isEqualTo(4) // group 2: "456"
            Assertions.assertThat(r[5]).isEqualTo(7)
        }

        @Test
        fun alternationGroup() {
            val r = search("x(y|z)", "xz")
            Assertions.assertThat(r).isNotNull()
            Assertions.assertThat(r!![0]).isEqualTo(0)
            Assertions.assertThat(r[1]).isEqualTo(2)
            Assertions.assertThat(r[2]).isEqualTo(1) // group 1: "z"
            Assertions.assertThat(r[3]).isEqualTo(2)
        }

        @Test
        fun nestedGroup() {
            val r = search("(a(b)c)", "abc")
            Assertions.assertThat(r).isNotNull()
            Assertions.assertThat(r!![0]).isEqualTo(0)
            Assertions.assertThat(r[1]).isEqualTo(3)
            Assertions.assertThat(r[2]).isEqualTo(0) // group 1: "abc"
            Assertions.assertThat(r[3]).isEqualTo(3)
            Assertions.assertThat(r[4]).isEqualTo(1) // group 2: "b"
            Assertions.assertThat(r[5]).isEqualTo(2)
        }

        @Test
        fun fiveGroups() {
            val r = search("(a)(b)(c)(d)(e)", "abcde")
            Assertions.assertThat(r).isNotNull()
            Assertions.assertThat(r!![2]).isEqualTo(0)
            Assertions.assertThat(r[3]).isEqualTo(1)
            Assertions.assertThat(r[4]).isEqualTo(1)
            Assertions.assertThat(r[5]).isEqualTo(2)
            Assertions.assertThat(r[6]).isEqualTo(2)
            Assertions.assertThat(r[7]).isEqualTo(3)
            Assertions.assertThat(r[8]).isEqualTo(3)
            Assertions.assertThat(r[9]).isEqualTo(4)
            Assertions.assertThat(r[10]).isEqualTo(4)
            Assertions.assertThat(r[11]).isEqualTo(5)
        }
    }

    // ---------------------------------------------------------------------------
    // Quantifiers
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Quantifiers")
    internal inner class Quantifiers {
        @Test
        fun starGreedy() {
            val r = search("[a-z]*", "hello123")
            Assertions.assertThat(r).isNotNull()
            Assertions.assertThat(r!![1]).isEqualTo(5) // greedy: consume all letters
        }

        @Test
        fun starEmpty() {
            val r = search("[a-z]*", "123")
            Assertions.assertThat(r).isNotNull()
            Assertions.assertThat(r!![1]).isEqualTo(0) // zero-length match
        }

        @Test
        fun plusGreedy() {
            val r = search("[a-z]+", "hello123")
            Assertions.assertThat(r).isNotNull()
            Assertions.assertThat(r!![1]).isEqualTo(5)
        }

        @Test
        fun plusNoMatch() {
            val r = search("[a-z]+", "123")
            Assertions.assertThat(r).isNull()
        }

        @Test
        fun questionPresent() {
            val r = search("ab?c", "abc")
            Assertions.assertThat(r).isNotNull()
            Assertions.assertThat(r!![1]).isEqualTo(3)
        }

        @Test
        fun questionAbsent() {
            val r = search("ab?c", "ac")
            Assertions.assertThat(r).isNotNull()
            Assertions.assertThat(r!![1]).isEqualTo(2)
        }
    }

    // ---------------------------------------------------------------------------
    // Consistency with NFA
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("OnePass vs NFA consistency")
    internal inner class Consistency {
        @Test
        fun characterClassTransitionsPreserveRangeEndpointsAndGaps() {
            val cases = arrayOf<Array<String?>?>(
                arrayOf<String?>(
                    ("^([\\x{0}-\\x{2}\\x{7F}-\\x{81}\\x{D7FF}-\\x{D801}"
                            + "\\x{FFFE}-\\x{10001}\\x{10FFFD}-\\x{10FFFF}])"
                            + "([\\x{1}-\\x{80}\\x{D800}\\x{FFFF}-\\x{10000}\\x{10FFFF}])!$"),
                    "\u0001",
                    "\u0001"
                ),
                arrayOf<String?>("^(\\p{L})([\\p{Lu}\\p{Nd}_])!$", "a", "A"),
                arrayOf<String?>("^([\\p{L}&&[^\\p{Lu}]])([\\p{Nd}\\p{Lu}_])!$", "a", "A")
            )
            for (testCase in cases) {
                val prog = Compiler.compile(Parser.parse(testCase!![0], FLAGS))
                val onePass = OnePass.build(prog)
                Assertions.assertThat<OnePass?>(onePass).`as`("/%s/ should be one-pass", testCase[0]).isNotNull()

                // Probe both sides of every endpoint, including boundaries introduced by other
                // instructions that split a character-class range into finer equivalence classes.
                val probes: MutableSet<Int?> = TreeSet<Int?>()
                probes.add(0)
                probes.add(Character.MAX_CODE_POINT)
                for (id in 0..<prog.size) {
                    val inst = prog.inst(id)
                    if (inst.op == InstOp.CHAR_CLASS) {
                        for (endpoint in inst.ranges) {
                            addEndpointProbes(probes, endpoint)
                        }
                    } else if (inst.op == InstOp.CHAR_RANGE) {
                        addEndpointProbes(probes, inst.lo)
                        addEndpointProbes(probes, inst.hi)
                    }
                }
                for (cp in probes) {
                    val codePoint = String(Character.toChars(cp!!))
                    for (text in List.of<String?>(codePoint + testCase[2] + "!", testCase[1] + codePoint + "!")) {
                        for (scanner in List.of<InputScanner?>(
                            StringInputScanner(text), Utf8InputScanner(
                                text.toByteArray(
                                    StandardCharsets.UTF_8
                                )
                            )
                        )) {
                            for (endMatch in booleanArrayOf(false, true)) {
                                val expected =
                                    Nfa.search(
                                        prog,
                                        scanner,
                                        0,
                                        scanner.length,
                                        scanner.length,
                                        0,
                                        Nfa.Anchor.ANCHORED,
                                        if (endMatch) Nfa.MatchKind.FULL_MATCH else Nfa.MatchKind.FIRST_MATCH,
                                        prog.numCaptures(),
                                        null
                                    )
                                Assertions.assertThat(onePass!!.search(scanner, endMatch, prog.numCaptures()))
                                    .`as`(
                                        "/%s/ at U+%X (%s, endMatch=%s)",
                                        testCase[0], cp, scanner.javaClass.getSimpleName(), endMatch
                                    )
                                    .isEqualTo(expected)
                            }
                        }
                    }
                }
            }
        }

        @Test
        fun disjointCharacterClassAlternativesPreserveCaptures() {
            val pattern = "([a-cx-z])|([d-fu-w])"
            Assertions.assertThat<OnePass?>(build(pattern)).isNotNull()
            var ch = 'a'
            while (ch <= 'z') {
                assertConsistentWithNfa(pattern, ch.toString(), true)
                ch++
            }
        }

        @Test
        fun overlappingCharacterClassesWithDifferentActionsAreNotOnePass() {
            Assertions.assertThat<OnePass?>(build("([a-fx-z])|([d-mz])")).isNull()
            Assertions.assertThat<OnePass?>(build("()[a-fx-z]|[d-mz]")).isNull()
            Assertions.assertThat<OnePass?>(build("\\b[a-fx-z]|[d-mz]")).isNull()
        }

        @Test
        fun literals() {
            assertConsistentWithNfa("abc", "abc", false)
            assertConsistentWithNfa("abc", "abcdef", false)
            assertConsistentWithNfa("abc", "xyz", false)
            assertConsistentWithNfa("abc", "abc", true)
            assertConsistentWithNfa("abc", "abcd", true)
        }

        @Test
        fun captureGroups() {
            assertConsistentWithNfa("(abc)", "abc", false)
            assertConsistentWithNfa("(a)(b)", "ab", false)
            assertConsistentWithNfa("(a)(b)(c)", "abc", true)
            assertConsistentWithNfa("(\\d+)-(\\d+)", "12-34", true)
        }

        @Test
        fun quantifiers() {
            assertConsistentWithNfa("[a-z]+", "hello", false)
            assertConsistentWithNfa("[a-z]*", "hello", false)
            assertConsistentWithNfa("[a-z]*", "", false)
            assertConsistentWithNfa("a?b", "ab", false)
            assertConsistentWithNfa("a?b", "b", false)
        }

        @Test
        fun alternation() {
            assertConsistentWithNfa("x(y|z)", "xy", false)
            assertConsistentWithNfa("x(y|z)", "xz", false)
            assertConsistentWithNfa("x(y|z)", "xa", false)
        }

        @Test
        fun anchorsAndAssertions() {
            assertConsistentWithNfa("^abc", "abc", false)
            assertConsistentWithNfa("abc$", "abc", true)
            assertConsistentWithNfa("\\bfoo", "foo bar", false)
        }

        @Test
        fun complex() {
            assertConsistentWithNfa("(\\w+)@(\\w+)", "user@host", false)
            assertConsistentWithNfa("[a-z]+-[0-9]+", "abc-123", false)
            assertConsistentWithNfa("(a(b(c)))", "abc", false)
        }

        @Test
        fun unicode() {
            assertConsistentWithNfa("caf\u00e9", "caf\u00e9", false)
            assertConsistentWithNfa(".", "\uD83D\uDE00", false)
        }

        @Test
        fun emptyPattern() {
            assertConsistentWithNfa("", "", false)
            assertConsistentWithNfa("", "abc", false)
        }

        companion object {
            private fun addEndpointProbes(probes: MutableSet<Int?>, endpoint: Int) {
                for (cp in max(0, endpoint - 1)..min(Character.MAX_CODE_POINT, endpoint + 1)) {
                    probes.add(cp)
                }
            }
        }
    }
}
