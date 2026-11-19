// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/** Tests for [Nfa]. End-to-end tests: parse → compile → match.  */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal object NfaTest {
    private val FLAGS = ParseFlags.PERL_X or ParseFlags.PERL_CLASSES or ParseFlags.PERL_B or ParseFlags.UNICODE_GROUPS

    /** Search with default first-match, unanchored semantics.  */
    private fun search(pattern: String?, text: String?): IntArray? {
        val re = Parser.parse(pattern, FLAGS)
        val prog = Compiler.compile(re)
        return Nfa.search(
            prog, text, Nfa.Anchor.UNANCHORED, Nfa.MatchKind.FIRST_MATCH, prog.numCaptures()
        )
    }

    /** Search with full-match semantics.  */
    private fun fullMatch(pattern: String?, text: String?): IntArray? {
        val re = Parser.parse(pattern, FLAGS)
        val prog = Compiler.compile(re)
        return Nfa.search(
            prog, text, Nfa.Anchor.UNANCHORED, Nfa.MatchKind.FULL_MATCH, prog.numCaptures()
        )
    }

    /** Search with longest-match semantics.  */
    private fun longestMatch(pattern: String?, text: String?): IntArray? {
        val re = Parser.parse(pattern, FLAGS)
        val prog = Compiler.compile(re)
        return Nfa.search(
            prog, text, Nfa.Anchor.UNANCHORED, Nfa.MatchKind.LONGEST_MATCH, prog.numCaptures()
        )
    }

    /** Search with anchored semantics.  */
    private fun anchoredSearch(pattern: String?, text: String?): IntArray? {
        val re = Parser.parse(pattern, FLAGS)
        val prog = Compiler.compile(re)
        return Nfa.search(
            prog, text, Nfa.Anchor.ANCHORED, Nfa.MatchKind.FIRST_MATCH, prog.numCaptures()
        )
    }

    @Throws(InterruptedException::class)
    private fun runOnSmallStack(task: Runnable) {
        val failure = AtomicReference<Throwable?>()
        val thread =
            Thread(
                null,
                Runnable {
                    try {
                        task.run()
                    } catch (t: Throwable) {
                        failure.set(t)
                    }
                },
                "nfa-stack-safety-test",
                (256 * 1024).toLong()
            )
        thread.start()
        thread.join()
        val thrown = failure.get()
        if (thrown != null) {
            throw AssertionError(thrown)
        }
    }

    @Nested
    @DisplayName("Simple literals")
    internal inner class Literals {
        @Test
        fun singleChar() {
            val m = search("a", "a")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(1)
        }

        @Test
        fun multiChar() {
            val m = search("abc", "xabcy")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(1)
            Assertions.assertThat(m[1]).isEqualTo(4)
        }

        @Test
        fun atEnd() {
            val m = search("xyz", "abcxyz")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(3)
            Assertions.assertThat(m[1]).isEqualTo(6)
        }
    }

    @Nested
    @DisplayName("No match")
    internal inner class NoMatch {
        @Test
        fun noMatch() {
            Assertions.assertThat(search("xyz", "abc")).isNull()
        }

        @Test
        fun emptyText() {
            Assertions.assertThat(search("a", "")).isNull()
        }
    }

    @Nested
    @DisplayName("Alternation")
    internal inner class Alternation {
        @Test
        fun simpleAlt() {
            val m = search("cat|dog", "I have a dog")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(9)
            Assertions.assertThat(m[1]).isEqualTo(12)
        }

        @Test
        fun leftmostWins() {
            val m = search("a|b", "ba")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("Quantifiers")
    internal inner class Quantifiers {
        @Test
        fun starGreedy() {
            val m = search("a*", "aaa")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(3)
        }

        @Test
        fun starEmptyMatch() {
            val m = search("a*", "bbb")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(0)
        }

        @Test
        fun plusMatch() {
            val m = search("a+", "aaa")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(3)
        }

        @Test
        fun plusNoMatch() {
            Assertions.assertThat(search("a+", "bbb")).isNull()
        }

        @Test
        fun questMatch() {
            val m = search("a?", "a")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(1)
        }

        @Test
        fun questEmpty() {
            val m = search("a?", "b")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("Non-greedy quantifiers")
    internal inner class NonGreedy {
        @Test
        fun starNonGreedy() {
            val m = search("a*?", "aaa")
            Assertions.assertThat(m).isNotNull()
            // Non-greedy star: matches empty at position 0
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(0)
        }

        @Test
        fun plusNonGreedy() {
            val m = search("a+?", "aaa")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("Dot")
    internal inner class Dot {
        @Test
        fun dotSingle() {
            val m = search(".", "x")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(1)
        }

        @Test
        fun dotPlus() {
            val m = search(".+", "hello")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(5)
        }

        @Test
        fun dotDoesNotMatchNewline() {
            Assertions.assertThat(search("^.$", "\n")).isNull()
        }
    }

    @Nested
    @DisplayName("Character classes")
    internal inner class CharClasses {
        @Test
        fun simpleClass() {
            val m = search("[abc]", "b")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(1)
        }

        @Test
        fun negatedClass() {
            val m = search("[^a-z]", "a1b")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(1)
            Assertions.assertThat(m[1]).isEqualTo(2)
        }

        @Test
        fun rangeClass() {
            val m = search("[a-z]+", "hello")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(5)
        }

        @Test
        fun digitClass() {
            val m = search("\\d+", "abc123def")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(3)
            Assertions.assertThat(m[1]).isEqualTo(6)
        }
    }

    @Nested
    @DisplayName("Anchors")
    internal inner class Anchors {
        @Test
        fun beginLine() {
            val m = search("^abc", "abcdef")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(3)
        }

        @Test
        fun beginLineNoMatch() {
            Assertions.assertThat(search("^abc", "xabc")).isNull()
        }

        @Test
        fun endLine() {
            val m = search("abc$", "xyzabc")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(3)
            Assertions.assertThat(m[1]).isEqualTo(6)
        }

        @Test
        fun fullAnchor() {
            val m = search("^abc$", "abc")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(3)
        }

        @Test
        fun fullAnchorNoMatch() {
            Assertions.assertThat(search("^abc$", "abcd")).isNull()
        }
    }

    @Nested
    @DisplayName("Captures")
    internal inner class Captures {
        @Test
        fun twoGroups() {
            val m = search("(a+)(b+)", "aaabb")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0) // full match start
            Assertions.assertThat(m[1]).isEqualTo(5) // full match end
            Assertions.assertThat(m[2]).isEqualTo(0) // group 1 start
            Assertions.assertThat(m[3]).isEqualTo(3) // group 1 end
            Assertions.assertThat(m[4]).isEqualTo(3) // group 2 start
            Assertions.assertThat(m[5]).isEqualTo(5) // group 2 end
        }

        @Test
        fun nestedCaptures() {
            val m = search("((a)(b))", "ab")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0) // full match start
            Assertions.assertThat(m[1]).isEqualTo(2) // full match end
            Assertions.assertThat(m[2]).isEqualTo(0) // group 1 start
            Assertions.assertThat(m[3]).isEqualTo(2) // group 1 end
            Assertions.assertThat(m[4]).isEqualTo(0) // group 2 start
            Assertions.assertThat(m[5]).isEqualTo(1) // group 2 end
            Assertions.assertThat(m[6]).isEqualTo(1) // group 3 start
            Assertions.assertThat(m[7]).isEqualTo(2) // group 3 end
        }

        @Test
        @DisplayName("deeply nested captures remain stack-safe in Pike NFA epsilon traversal")
        fun deeplyNestedCapturesRemainStackSafeInPikeNfa() {
            val depth = 30000
            val pattern = "(".repeat(depth) + "a" + ")".repeat(depth)

            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                Executable {
                    runOnSmallStack(
                        Runnable {
                            val m = anchoredSearch(pattern, "a")
                            Assertions.assertThat(m).isNotNull()
                            Assertions.assertThat(m!![0]).isEqualTo(0)
                            Assertions.assertThat(m[1]).isEqualTo(1)
                            Assertions.assertThat(m[2]).isEqualTo(0)
                            Assertions.assertThat(m[3]).isEqualTo(1)
                        })
                })
        }
    }

    @Nested
    @DisplayName("Word boundary")
    internal inner class WordBoundary {
        @Test
        fun wordBoundaryMatch() {
            val m = search("\\bfoo\\b", "foo bar")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(3)
        }

        @Test
        fun wordBoundaryNoMatch() {
            Assertions.assertThat(search("\\bfoo\\b", "foobar")).isNull()
        }

        @Test
        fun wordBoundaryInMiddle() {
            val m = search("\\bbar\\b", "foo bar baz")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(4)
            Assertions.assertThat(m[1]).isEqualTo(7)
        }
    }

    @Nested
    @DisplayName("Match modes")
    internal inner class MatchModes {
        @Test
        fun fullMatchSuccess() {
            val m = fullMatch("abc", "abc")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(3)
        }

        @Test
        fun fullMatchFailure() {
            Assertions.assertThat(fullMatch("abc", "abcd")).isNull()
        }

        @Test
        fun fullMatchPartialNoMatch() {
            Assertions.assertThat(fullMatch("abc", "xabc")).isNull()
        }

        @Test
        fun longestMatchMode() {
            val m = longestMatch("a+", "aaa")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(3)
        }

        @Test
        fun anchoredMatch() {
            val m = anchoredSearch("abc", "abcdef")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(3)
        }

        @Test
        fun anchoredNoMatch() {
            Assertions.assertThat(anchoredSearch("abc", "xabc")).isNull()
        }
    }

    @Nested
    @DisplayName("Unanchored search")
    internal inner class Unanchored {
        @Test
        fun findsLeftmost() {
            val m = search("abc", "xxabcyy")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(2)
            Assertions.assertThat(m[1]).isEqualTo(5)
        }
    }

    @Nested
    @DisplayName("Empty patterns")
    internal inner class EmptyPatterns {
        @Test
        fun emptyPattern() {
            val m = search("", "abc")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(0)
        }

        @Test
        fun emptyPatternEmptyText() {
            val m = search("", "")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("Unicode")
    internal inner class Unicode {
        @Test
        fun dotMatchesEmoji() {
            // 😀 is U+1F600, encoded as a surrogate pair (2 chars)
            val emoji = "\uD83D\uDE00"
            val m = search(".", emoji)
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(2) // 2 chars for the surrogate pair
        }

        @Test
        fun unicodeLetters() {
            val m = search("[a-z]+", "café")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(3) // "caf" — é is not in [a-z]
        }
    }

    @Nested
    @DisplayName("Complex patterns")
    internal inner class Complex {
        @Test
        fun digitsDotDigits() {
            val m = search("(\\d+)\\.(\\d+)", "version 3.14 here")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(8) // "3.14" start
            Assertions.assertThat(m[1]).isEqualTo(12) // "3.14" end
            Assertions.assertThat(m[2]).isEqualTo(8) // group 1: "3"
            Assertions.assertThat(m[3]).isEqualTo(9)
            Assertions.assertThat(m[4]).isEqualTo(10) // group 2: "14"
            Assertions.assertThat(m[5]).isEqualTo(12)
        }

        @Test
        fun alternationWithQuantifiers() {
            val m = search("(foo|bar)+", "foobarfoo")
            Assertions.assertThat(m).isNotNull()
            Assertions.assertThat(m!![0]).isEqualTo(0)
            Assertions.assertThat(m[1]).isEqualTo(9)
        }
    }

    @Nested
    @DisplayName("Linear time guarantee")
    internal inner class LinearTime {
        @Test
        fun pathologicalPattern() {
            // a?^n a^n matched against a^n — classic pathological case for backtracking.
            // With Pike VM, this should complete in linear time.
            val n = 25
            val questPart = "a?".repeat(n)
            val litPart = "a".repeat(n)
            val pattern = questPart + litPart
            val text = litPart

            val m = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively<IntArray?>(
                Duration.ofSeconds(5),
                ThrowingSupplier { fullMatch(pattern, text) })

            Assertions.assertThat(m).isNotNull()
        }
    }

    @Nested
    @DisplayName("EmptyFlags helper")
    internal inner class EmptyFlags {
        @Test
        fun beginOfText() {
            val flags = Nfa.emptyFlags("abc", 0)
            Assertions.assertThat(flags and EmptyOp.BEGIN_TEXT).isNotZero()
            Assertions.assertThat(flags and EmptyOp.BEGIN_LINE).isNotZero()
        }

        @Test
        fun endOfText() {
            val flags = Nfa.emptyFlags("abc", 3)
            Assertions.assertThat(flags and EmptyOp.END_TEXT).isNotZero()
            Assertions.assertThat(flags and EmptyOp.END_LINE).isNotZero()
        }

        @Test
        fun midText() {
            val flags = Nfa.emptyFlags("abc", 1)
            Assertions.assertThat(flags and EmptyOp.BEGIN_TEXT).isZero()
            Assertions.assertThat(flags and EmptyOp.END_TEXT).isZero()
        }

        @Test
        fun afterNewline() {
            val flags = Nfa.emptyFlags("a\nb", 2)
            Assertions.assertThat(flags and EmptyOp.BEGIN_LINE).isNotZero()
        }

        @Test
        fun wordBoundary() {
            val flags = Nfa.emptyFlags("foo bar", 3)
            Assertions.assertThat(flags and EmptyOp.WORD_BOUNDARY).isNotZero()
        }

        @Test
        fun nonWordBoundary() {
            val flags = Nfa.emptyFlags("foo", 1)
            Assertions.assertThat(flags and EmptyOp.NON_WORD_BOUNDARY).isNotZero()
        }

        @Test
        @DisplayName("BEGIN_LINE after standalone \\r")
        fun beginLineAfterCr() {
            val flags = Nfa.emptyFlags("a\rb", 2)
            Assertions.assertThat(flags and EmptyOp.BEGIN_LINE).isNotZero()
        }

        @Test
        @DisplayName("BEGIN_LINE NOT between \\r and \\n in \\r\\n")
        fun noBeginLineBetweenCrLf() {
            val flags = Nfa.emptyFlags("a\r\nb", 2)
            Assertions.assertThat(flags and EmptyOp.BEGIN_LINE).isZero()
        }

        @Test
        @DisplayName("END_LINE before standalone \\r")
        fun endLineBeforeCr() {
            val flags = Nfa.emptyFlags("a\rb", 1)
            Assertions.assertThat(flags and EmptyOp.END_LINE).isNotZero()
        }

        @Test
        @DisplayName("END_LINE NOT between \\r and \\n in \\r\\n (#78)")
        fun noEndLineBetweenCrLf() {
            // Position 1 is at the \r in "a\r\nb"; END_LINE SHOULD fire here (before the \r\n pair).
            val flags = Nfa.emptyFlags("a\r\nb", 1)
            Assertions.assertThat(flags and EmptyOp.END_LINE).isNotZero()

            // Position 2 is at the \n in "a\r\nb" (between \r and \n in the atomic pair);
            // END_LINE must NOT fire here.
            val flags2 = Nfa.emptyFlags("a\r\nb", 2)
            Assertions.assertThat(flags2 and EmptyOp.END_LINE).isZero()
        }

        @Test
        @DisplayName("END_LINE at standalone \\r not followed by \\n still fires")
        fun endLineAtStandaloneCr() {
            val flags = Nfa.emptyFlags("\r", 0)
            Assertions.assertThat(flags and EmptyOp.END_LINE).isNotZero()
        }

        @Test
        @DisplayName("UNIX_LINES: \\r is not a line terminator")
        fun unixLinesCrNotLineTerm() {
            // After \r: no BEGIN_LINE in UNIX_LINES mode
            val flags = Nfa.emptyFlags("a\rb", 2)
            Assertions.assertThat(flags and EmptyOp.UNIX_BEGIN_LINE).isZero()
            // Before \r: no END_LINE in UNIX_LINES mode
            val flags2 = Nfa.emptyFlags("a\rb", 1)
            Assertions.assertThat(flags2 and EmptyOp.UNIX_END_LINE).isZero()
        }
    }
}
