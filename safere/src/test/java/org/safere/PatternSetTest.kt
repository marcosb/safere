// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.function.IntConsumer
import java.util.regex.PatternSyntaxException

/** Tests for [PatternSet].  */
@DisabledForCrosscheck("PatternSet is a SafeRE-only API with no java.util.regex equivalent")
internal class PatternSetTest {
    // ---------------------------------------------------------------------------
    // Builder
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Builder")
    internal inner class BuilderTests {
        @Test
        fun addReturnsSequentialIndices() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            Assertions.assertThat(b.add("foo")).isEqualTo(0)
            Assertions.assertThat(b.add("bar")).isEqualTo(1)
            Assertions.assertThat(b.add("baz")).isEqualTo(2)
        }

        @Test
        fun addRejectsInvalidPattern() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            Assertions.assertThatThrownBy(ThrowingCallable { b.add("[invalid") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun addAfterCompileThrows() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("foo")
            b.compile()
            Assertions.assertThatThrownBy(ThrowingCallable { b.add("bar") })
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Cannot add")
        }

        @Test
        fun compileWithNoPatternsThrows() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            Assertions.assertThatThrownBy(ThrowingCallable { b.compile() })
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("No patterns")
        }

        @Test
        fun doubleCompileThrows() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("foo")
            b.compile()
            Assertions.assertThatThrownBy(ThrowingCallable { b.compile() })
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("already been called")
        }
    }

    // ---------------------------------------------------------------------------
    // Unanchored matching
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Unanchored")
    internal inner class UnanchoredTests {
        @Test
        fun singlePatternMatches() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("foo")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("foo")).containsExactly(0)
            Assertions.assertThat<Int?>(set.match("hello foo world")).containsExactly(0)
            Assertions.assertThat<Int?>(set.match("bar")).isEmpty()
        }

        @Test
        fun multiplePatterns() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("foo")
            b.add("bar")
            b.add("baz")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("foo")).containsExactly(0)
            Assertions.assertThat<Int?>(set.match("bar")).containsExactly(1)
            Assertions.assertThat<Int?>(set.match("baz")).containsExactly(2)
            Assertions.assertThat<Int?>(set.match("nothing")).isEmpty()
        }

        @Test
        fun multipleMatchesInSameText() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("foo")
            b.add("bar")
            b.add("baz")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("foobar")).containsExactly(0, 1)
            Assertions.assertThat<Int?>(set.match("foobarbaz")).containsExactly(0, 1, 2)
        }

        @Test
        fun overlappingPatterns() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("abc")
            b.add("bcd")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("abcd")).containsExactly(0, 1)
        }

        @Test
        fun patternWithQuantifiers() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("[0-9]+")
            b.add("[a-z]+")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("abc123")).containsExactly(0, 1)
            Assertions.assertThat<Int?>(set.match("!!!")).isEmpty()
        }

        @Test
        fun matchesConvenience() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("foo")
            val set = b.compile()

            Assertions.assertThat(set.matches("foo bar")).isTrue()
            Assertions.assertThat(set.matches("nothing")).isFalse()
        }

        @Test
        @Tag("work-counter")
        fun regionalIndicatorGraphemeBoundaryMissesStayNearLinear() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("\\b{g}z")
            val set = b.compile()

            assertFourXInputStaysNearLinear(
                "PatternSet regional-indicator boundary miss",
                IntConsumer { length: Int ->
                    Assertions.assertThat<Int?>(set.match("\uD83C\uDDE6".repeat(length))).isEmpty()
                })
        }

        @Test
        fun graphemeClusterPatternMatches() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("\\X")
            b.add("z")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("a")).containsExactly(0)
            Assertions.assertThat<Int?>(set.match("e\u0301")).containsExactly(0)
            Assertions.assertThat<Int?>(set.match("z")).containsExactly(0, 1)
        }

        @Test
        fun graphemeFallbackDoesNotRecompileMembersPerMatch() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            val patterns: MutableList<String?> = ArrayList<String?>()
            patterns.add("\\X")
            b.add(patterns.getFirst())
            for (i in 0..63) {
                val pattern = "literal" + i
                patterns.add(pattern)
                b.add(pattern)
            }
            val set = b.compile()
            val allocationTracker: AllocationTracker = allocationTracker()
            val threadId = Thread.currentThread().threadId()

            set.match("a")
            compileAndMatchEachPattern(patterns, "a")
            val compileEachTimeAllocated: Long =
                allocatedFor(
                    allocationTracker,
                    threadId,
                    Runnable {
                        for (i in 0..49) {
                            compileAndMatchEachPattern(patterns, "a")
                        }
                    })
            val cachedSetAllocated: Long =
                allocatedFor(
                    allocationTracker,
                    threadId,
                    Runnable {
                        for (i in 0..49) {
                            Assertions.assertThat<Int?>(set.match("a")).containsExactly(0)
                        }
                    })

            Assertions.assertThat(cachedSetAllocated)
                .`as`("grapheme PatternSet fallback should reuse compile-time member patterns")
                .isLessThan(compileEachTimeAllocated / 2)
        }
    }

    // ---------------------------------------------------------------------------
    // Anchor start
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Anchor start")
    internal inner class AnchorStartTests {
        @Test
        fun matchesAtStart() {
            val b = PatternSet.Builder(PatternSet.Anchor.ANCHOR_START)
            b.add("foo")
            b.add("bar")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("foobar")).containsExactly(0)
            Assertions.assertThat<Int?>(set.match("barfoo")).containsExactly(1)
            Assertions.assertThat<Int?>(set.match("xfoo")).isEmpty()
        }

        @Test
        fun prefixMatch() {
            val b = PatternSet.Builder(PatternSet.Anchor.ANCHOR_START)
            b.add("[0-9]+")
            b.add("[a-z]+")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("123abc")).containsExactly(0)
            Assertions.assertThat<Int?>(set.match("abc123")).containsExactly(1)
            Assertions.assertThat<Int?>(set.match("!!!")).isEmpty()
        }
    }

    // ---------------------------------------------------------------------------
    // Anchor both
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Anchor both")
    internal inner class AnchorBothTests {
        @Test
        fun fullMatchOnly() {
            val b = PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH)
            b.add("foo")
            b.add("bar")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("foo")).containsExactly(0)
            Assertions.assertThat<Int?>(set.match("bar")).containsExactly(1)
            Assertions.assertThat<Int?>(set.match("foobar")).isEmpty()
            Assertions.assertThat<Int?>(set.match("fo")).isEmpty()
        }

        @Test
        fun multipleFullMatches() {
            val b = PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH)
            b.add("abc")
            b.add("[a-z]+")
            b.add("...")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("abc")).containsExactly(0, 1, 2)
            Assertions.assertThat<Int?>(set.match("xyz")).containsExactly(1, 2)
            Assertions.assertThat<Int?>(set.match("abcd")).containsExactly(1)
        }

        @Test
        fun graphemeClusterPatternMatchesWholeText() {
            val b = PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH)
            b.add("\\X")
            b.add("\\X+")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("a")).containsExactly(0, 1)
            Assertions.assertThat<Int?>(set.match("e\u0301")).containsExactly(0, 1)
            Assertions.assertThat<Int?>(set.match("ab")).containsExactly(1)
        }
    }

    // ---------------------------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Metadata")
    internal inner class MetadataTests {
        @Test
        fun sizeReturnsCount() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("a")
            b.add("b")
            b.add("c")
            val set = b.compile()

            Assertions.assertThat(set.size()).isEqualTo(3)
        }

        @Test
        fun patternReturnsOriginal() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("hello")
            b.add("[0-9]+")
            val set = b.compile()

            Assertions.assertThat(set.pattern(0)).isEqualTo("hello")
            Assertions.assertThat(set.pattern(1)).isEqualTo("[0-9]+")
        }
    }

    // ---------------------------------------------------------------------------
    // Edge cases
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Edge cases")
    internal inner class EdgeCases {
        @Test
        fun emptyPattern() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("anything")).containsExactly(0)
            Assertions.assertThat<Int?>(set.match("")).containsExactly(0)
        }

        @Test
        fun emptyText() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("foo")
            b.add("")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("")).containsExactly(1)
        }

        @Test
        fun duplicatePatterns() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("foo")
            b.add("foo")
            val set = b.compile()

            val result = set.match("foo")
            Assertions.assertThat<Int?>(result).containsExactly(0, 1)
        }

        @Test
        fun unicodePatterns() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("caf\u00e9")
            b.add("\\p{IsGreek}+")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("caf\u00e9")).containsExactly(0)
            Assertions.assertThat<Int?>(set.match("\u03b1\u03b2\u03b3")).containsExactly(1)
        }

        @Test
        fun manyPatterns() {
            val b = PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH)
            for (i in 0..99) {
                b.add("pattern" + i)
            }
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("pattern42")).containsExactly(42)
            Assertions.assertThat<Int?>(set.match("pattern99")).containsExactly(99)
            Assertions.assertThat<Int?>(set.match("pattern100")).isEmpty()
        }

        @Test
        fun singlePattern() {
            val b = PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH)
            b.add("hello")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("hello")).containsExactly(0)
            Assertions.assertThat<Int?>(set.match("world")).isEmpty()
        }
    }

    // ---------------------------------------------------------------------------
    // Builder edge cases
    // ---------------------------------------------------------------------------
    @Test
    fun compileEmptyBuilderThrows() {
        val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
        Assertions.assertThatThrownBy(ThrowingCallable { b.compile() }).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun compileAlreadyCompiledThrows() {
        val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
        b.add("abc")
        b.compile()
        Assertions.assertThatThrownBy(ThrowingCallable { b.compile() }).isInstanceOf(IllegalStateException::class.java)
    }

    // ---------------------------------------------------------------------------
    // Anchor-mode coverage (non-fallback paths)
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Anchor mode coverage")
    internal inner class AnchorModeCoverage {
        @Test
        fun unanchoredMultipleOverlapping() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("[a-z]+")
            b.add("abc")
            b.add("xyz")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("abc")).containsExactly(0, 1)
            Assertions.assertThat<Int?>(set.match("xyz")).containsExactly(0, 2)
            Assertions.assertThat<Int?>(set.match("abcxyz")).containsExactly(0, 1, 2)
            Assertions.assertThat<Int?>(set.match("123")).isEmpty()
        }

        @Test
        fun anchorStartPartialMatch() {
            val b = PatternSet.Builder(PatternSet.Anchor.ANCHOR_START)
            b.add("foo")
            b.add("bar")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("foobar")).containsExactly(0)
            Assertions.assertThat<Int?>(set.match("barfoo")).containsExactly(1)
            Assertions.assertThat<Int?>(set.match("xfoo")).isEmpty()
        }

        @Test
        fun anchorBothNoPartial() {
            val b = PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH)
            b.add("foo")
            b.add("foobar")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("foo")).containsExactly(0)
            Assertions.assertThat<Int?>(set.match("foobar")).containsExactly(1)
            Assertions.assertThat<Int?>(set.match("foob")).isEmpty()
        }

        @Test
        fun matchesConvenience() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("hello")
            val set = b.compile()

            Assertions.assertThat(set.matches("say hello")).isTrue()
            Assertions.assertThat(set.matches("goodbye")).isFalse()
        }

        @Test
        fun sizeAndPatternAccessors() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("alpha")
            b.add("beta")
            val set = b.compile()

            Assertions.assertThat(set.size()).isEqualTo(2)
            Assertions.assertThat(set.pattern(0)).isEqualTo("alpha")
            Assertions.assertThat(set.pattern(1)).isEqualTo("beta")
        }

        @Test
        fun addAfterCompileThrows() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("x")
            b.compile()
            Assertions.assertThatThrownBy(ThrowingCallable { b.add("y") })
                .isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        fun invalidPatternThrows() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            Assertions.assertThatThrownBy(ThrowingCallable { b.add("[invalid") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }
    }

    // ---------------------------------------------------------------------------
    // DFA fallback (matchFallback)
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("DFA fallback")
    internal inner class FallbackTests {
        @Test
        fun fallbackUnanchored() {
            // Use maxDfaStates=1 to force immediate DFA bailout → NFA fallback
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("foo")
            b.add("bar")
            b.add("[0-9]+")
            val set = b.compile(1)
            val matches = set.match("foobar123")
            Assertions.assertThat<Int?>(matches).containsExactly(0, 1, 2)
        }

        @Test
        fun fallbackAnchorStart() {
            val b = PatternSet.Builder(PatternSet.Anchor.ANCHOR_START)
            b.add("foo")
            b.add("bar")
            val set = b.compile(1)
            val matches = set.match("foobar")
            Assertions.assertThat<Int?>(matches).containsExactly(0)
        }

        @Test
        fun fallbackAnchorBoth() {
            val b = PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH)
            b.add("hello")
            b.add("world")
            val set = b.compile(1)
            Assertions.assertThat<Int?>(set.match("hello")).containsExactly(0)
            Assertions.assertThat<Int?>(set.match("world")).containsExactly(1)
            Assertions.assertThat<Int?>(set.match("other")).isEmpty()
        }

        @Test
        fun fallbackNoMatch() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("xyz")
            val set = b.compile(1)
            Assertions.assertThat<Int?>(set.match("abc")).isEmpty()
        }
    }

    // ---------------------------------------------------------------------------
    // Tests ported from RE2 C++ set_test.cc (P3)
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("RE2 C++ set_test.cc ports")
    internal inner class RE2SetTests {
        @Test
        @DisplayName("Unanchored with overlapping factored patterns")
        fun unanchoredFactored() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("foo")
            b.add("foobar")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("foobar")).containsExactly(0, 1)
            Assertions.assertThat<Int?>(set.match("obarfoobaroo")).containsExactly(0, 1)
            Assertions.assertThat<Int?>(set.match("fooba")).containsExactly(0)
            Assertions.assertThat<Int?>(set.match("oobar")).isEmpty()
        }

        @Test
        @DisplayName("Unanchored with $ (end-of-string) anchor")
        fun unanchoredDollar() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("foo$")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("foo")).containsExactly(0)
            Assertions.assertThat<Int?>(set.match("foobar")).isEmpty()
        }

        @Test
        @DisplayName("Unanchored with \\b (word boundary)")
        fun unanchoredWordBoundary() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            b.add("foo\\b")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("foo")).containsExactly(0)
            Assertions.assertThat<Int?>(set.match("foobar")).isEmpty()
            Assertions.assertThat<Int?>(set.match("foo bar")).containsExactly(0)
        }

        @Test
        @DisplayName("Anchored with prefix and digit quantifier")
        fun anchoredPrefix() {
            val b = PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH)
            b.add("/prefix/\\d*")
            val set = b.compile()

            Assertions.assertThat<Int?>(set.match("/prefix")).isEmpty()
            Assertions.assertThat<Int?>(set.match("/prefix/")).containsExactly(0)
            Assertions.assertThat<Int?>(set.match("/prefix/42")).containsExactly(0)
        }

        @Test
        @DisplayName("Empty set unanchored rejects compile")
        fun emptySetUnanchored() {
            val b = PatternSet.Builder(PatternSet.Anchor.UNANCHORED)
            Assertions.assertThatThrownBy(ThrowingCallable { b.compile() })
                .isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        @DisplayName("Empty set anchored rejects compile")
        fun emptySetAnchored() {
            val b = PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH)
            Assertions.assertThatThrownBy(ThrowingCallable { b.compile() })
                .isInstanceOf(IllegalStateException::class.java)
        }
    }

    @JvmRecord
    private data class AllocationTracker(val threadBean: Any?, val allocatedBytesMethod: Method?) {
        fun allocatedBytes(threadId: Long): Long {
            try {
                return (allocatedBytesMethod.invoke(threadBean, threadId) as Long?)!!
            } catch (e: IllegalAccessException) {
                throw AssertionError(e)
            } catch (e: InvocationTargetException) {
                throw AssertionError(e.cause)
            }
        }
    }

    companion object {
        private fun assertFourXInputStaysNearLinear(scenario: String?, task: IntConsumer) {
            task.accept(1000)
            val smallerWork = WorkCounter.countForTesting(Runnable { task.accept(5000) })
            val largerWork = WorkCounter.countForTesting(Runnable { task.accept(20000) })

            Assertions.assertThat(smallerWork).`as`("%s: smaller input should use matcher work", scenario).isPositive()
            Assertions.assertThat(largerWork)
                .`as`(
                    "%s: 4x input should stay near-linear, smallerWork=%d largerWork=%d",
                    scenario, smallerWork, largerWork
                )
                .isLessThan(smallerWork * 5)
        }

        private fun allocatedFor(
            allocationTracker: AllocationTracker, threadId: Long, task: Runnable
        ): Long {
            val before = allocationTracker.allocatedBytes(threadId)
            task.run()
            return allocationTracker.allocatedBytes(threadId) - before
        }

        private fun compileAndMatchEachPattern(patterns: MutableList<String?>, text: String?) {
            for (pattern in patterns) {
                Pattern.compile(pattern).matcher(text).find()
            }
        }

        private fun allocationTracker(): AllocationTracker {
            try {
                val managementFactoryClass = Class.forName("java.lang.management.ManagementFactory")
                val threadBean = managementFactoryClass.getMethod("getThreadMXBean").invoke(null)
                val allocationBeanClass = Class.forName("com.sun.management.ThreadMXBean")
                Assumptions.assumeTrue(allocationBeanClass.isInstance(threadBean))

                val supportedMethod = allocationBeanClass.getMethod("isThreadAllocatedMemorySupported")
                val enabledMethod = allocationBeanClass.getMethod("isThreadAllocatedMemoryEnabled")
                val setEnabledMethod =
                    allocationBeanClass.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType)
                val allocatedBytesMethod =
                    allocationBeanClass.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)

                Assumptions.assumeTrue((supportedMethod.invoke(threadBean) as Boolean?)!!)
                if (!enabledMethod.invoke(threadBean) as Boolean?) {
                    setEnabledMethod.invoke(threadBean, true)
                }
                return AllocationTracker(threadBean, allocatedBytesMethod)
            } catch (e: ReflectiveOperationException) {
                Assumptions.abort<Any?>("thread allocation tracking is unavailable: " + e)
                throw AssertionError("unreachable")
            }
        }
    }
}
