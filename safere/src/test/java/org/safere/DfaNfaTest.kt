// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Systematic consistency tests verifying that the DFA and NFA engines agree on match results for
 * the same pattern and input.
 * 
 * 
 * The DFA only reports match/no-match and match end position (no capture groups). The DFA and
 * NFA have different semantics for "first" and "longest" match:
 * 
 * 
 *  * DFA first-match: earliest end position (considering all start positions via .*? prefix)
 *  * NFA FIRST_MATCH: leftmost start, greedy end (Perl-like)
 *  * DFA unanchored longest: latest end position (from any start)
 *  * NFA LONGEST_MATCH: leftmost start, longest end (POSIX-like)
 * 
 * 
 * 
 * Because of these semantic differences, end positions only reliably agree in **anchored +
 * longest** mode (both start at position 0, both maximize match length). All modes should agree
 * on match/no-match.
 */
@DisplayName("DFA vs NFA consistency")
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class DfaNfaTest {
    /** A single test case: pattern + input text.  */
    @JvmRecord
    internal data class TestCase(val pattern: String?, val input: String?) {
        override fun toString(): String {
            return "/" + pattern!!.replace("\n", "\\n") + "/ on \"" + input!!.replace("\n", "\\n") + "\""
        }
    }

    // ---------------------------------------------------------------------------
    // Parameterized tests — one per mode combination
    // ---------------------------------------------------------------------------
    @ParameterizedTest(name = "unanchored first: {0}")
    @MethodSource("testCases")
    fun unanchoredFirstMatch(tc: TestCase) {
        assertMatchAgreement(tc, false, false)
    }

    @ParameterizedTest(name = "unanchored longest: {0}")
    @MethodSource("testCases")
    fun unanchoredLongestMatch(tc: TestCase) {
        assertMatchAgreement(tc, false, true)
    }

    @ParameterizedTest(name = "anchored first: {0}")
    @MethodSource("testCases")
    fun anchoredFirstMatch(tc: TestCase) {
        assertMatchAgreement(tc, true, false)
    }

    @ParameterizedTest(name = "anchored longest: {0}")
    @MethodSource("testCases")
    fun anchoredLongestMatch(tc: TestCase) {
        assertMatchAndEndPosition(tc, true, true)
    }

    companion object {
        private val FLAGS =
            ParseFlags.PERL_X or ParseFlags.PERL_CLASSES or ParseFlags.PERL_B or ParseFlags.UNICODE_GROUPS

        // ---------------------------------------------------------------------------
        // Test corpus
        // ---------------------------------------------------------------------------
        @JvmStatic
        fun testCases(): Stream<TestCase?> {
            val cases: MutableList<TestCase?> = ArrayList<TestCase?>()

            // -- Literals --
            cases.add(TestCase("abc", "abc"))
            cases.add(TestCase("abc", "xabcy"))
            cases.add(TestCase("abc", "def"))
            cases.add(TestCase("abc", ""))
            cases.add(TestCase("abc", "ab"))
            cases.add(TestCase("abc", "abcabc"))
            cases.add(TestCase("hello world", "hello world"))
            cases.add(TestCase("hello world", "say hello world!"))
            cases.add(TestCase("hello world", "hello"))

            // -- Single characters --
            cases.add(TestCase("a", "a"))
            cases.add(TestCase("a", "b"))
            cases.add(TestCase("a", "ba"))
            cases.add(TestCase("a", ""))

            // -- Dot --
            cases.add(TestCase(".", "a"))
            cases.add(TestCase(".", ""))
            cases.add(TestCase("..", "ab"))
            cases.add(TestCase("..", "a"))
            cases.add(TestCase("a.c", "abc"))
            cases.add(TestCase("a.c", "aXc"))
            cases.add(TestCase("a.c", "ac"))

            // -- Character classes --
            cases.add(TestCase("[abc]", "a"))
            cases.add(TestCase("[abc]", "b"))
            cases.add(TestCase("[abc]", "d"))
            cases.add(TestCase("[abc]", ""))
            cases.add(TestCase("[a-z]", "m"))
            cases.add(TestCase("[a-z]", "M"))
            cases.add(TestCase("[a-z]+", "hello"))
            cases.add(TestCase("[a-z]+", "HELLO"))
            cases.add(TestCase("[^a-z]", "A"))
            cases.add(TestCase("[^a-z]", "a"))
            cases.add(TestCase("[a-zA-Z0-9_]", "x"))
            cases.add(TestCase("[a-zA-Z0-9_]", " "))

            // -- Perl character classes --
            cases.add(TestCase("\\d", "5"))
            cases.add(TestCase("\\d", "a"))
            cases.add(TestCase("\\d+", "abc123def"))
            cases.add(TestCase("\\D+", "abc123def"))
            cases.add(TestCase("\\w+", "hello world"))
            cases.add(TestCase("\\W+", "hello world"))
            cases.add(TestCase("\\s", " "))
            cases.add(TestCase("\\s", "a"))
            cases.add(TestCase("\\s+", "a b\tc\nd"))

            // -- Quantifiers: star --
            cases.add(TestCase("a*", ""))
            cases.add(TestCase("a*", "a"))
            cases.add(TestCase("a*", "aaa"))
            cases.add(TestCase("a*", "b"))
            cases.add(TestCase("a*", "baaab"))
            cases.add(TestCase("xa*y", "xy"))
            cases.add(TestCase("xa*y", "xaaay"))
            cases.add(TestCase("xa*y", "xaaa"))

            // -- Quantifiers: plus --
            cases.add(TestCase("a+", ""))
            cases.add(TestCase("a+", "a"))
            cases.add(TestCase("a+", "aaa"))
            cases.add(TestCase("a+", "b"))
            cases.add(TestCase("a+", "baaab"))
            cases.add(TestCase("xa+y", "xy"))
            cases.add(TestCase("xa+y", "xay"))
            cases.add(TestCase("xa+y", "xaaay"))

            // -- Quantifiers: question --
            cases.add(TestCase("a?", ""))
            cases.add(TestCase("a?", "a"))
            cases.add(TestCase("a?", "b"))
            cases.add(TestCase("xa?y", "xy"))
            cases.add(TestCase("xa?y", "xay"))
            cases.add(TestCase("xa?y", "xaay"))

            // -- Quantifiers: repetition --
            cases.add(TestCase("a{3}", "aaa"))
            cases.add(TestCase("a{3}", "aa"))
            cases.add(TestCase("a{3}", "aaaa"))
            cases.add(TestCase("a{2,4}", "a"))
            cases.add(TestCase("a{2,4}", "aa"))
            cases.add(TestCase("a{2,4}", "aaa"))
            cases.add(TestCase("a{2,4}", "aaaa"))
            cases.add(TestCase("a{2,4}", "aaaaa"))
            cases.add(TestCase("a{2,}", "a"))
            cases.add(TestCase("a{2,}", "aa"))
            cases.add(TestCase("a{2,}", "aaaaaaa"))

            // -- Non-greedy quantifiers --
            cases.add(TestCase("a*?", "aaa"))
            cases.add(TestCase("a+?", "aaa"))
            cases.add(TestCase("a??", "a"))
            cases.add(TestCase("a{2,4}?", "aaaa"))

            // -- Alternation --
            cases.add(TestCase("cat|dog", "cat"))
            cases.add(TestCase("cat|dog", "dog"))
            cases.add(TestCase("cat|dog", "bird"))
            cases.add(TestCase("cat|dog", "catdog"))
            cases.add(TestCase("a|b|c", "a"))
            cases.add(TestCase("a|b|c", "b"))
            cases.add(TestCase("a|b|c", "c"))
            cases.add(TestCase("a|b|c", "d"))
            cases.add(TestCase("ab|cd|ef", "cd"))
            cases.add(TestCase("ab|cd|ef", "abcdef"))

            // -- Groups --
            cases.add(TestCase("(abc)", "abc"))
            cases.add(TestCase("(abc)", "def"))
            cases.add(TestCase("(a)(b)(c)", "abc"))
            cases.add(TestCase("(a|b)(c|d)", "ac"))
            cases.add(TestCase("(a|b)(c|d)", "bd"))
            cases.add(TestCase("(a|b)(c|d)", "ae"))
            cases.add(TestCase("(ab)+", "ababab"))
            cases.add(TestCase("(ab)+", "abcab"))

            // -- Nested groups --
            cases.add(TestCase("((a)(b))", "ab"))
            cases.add(TestCase("(a(b(c)))", "abc"))
            cases.add(TestCase("((a|b)+)", "abba"))

            // -- Anchors --
            cases.add(TestCase("^abc", "abc"))
            cases.add(TestCase("^abc", "abcdef"))
            cases.add(TestCase("^abc", "xabc"))
            cases.add(TestCase("abc$", "abc"))
            cases.add(TestCase("abc$", "xyzabc"))
            cases.add(TestCase("abc$", "abcx"))
            cases.add(TestCase("^abc$", "abc"))
            cases.add(TestCase("^abc$", "abcx"))
            cases.add(TestCase("^abc$", "xabc"))
            cases.add(TestCase("^$", ""))
            cases.add(TestCase("^$", "a"))
            cases.add(TestCase("^", "anything"))
            cases.add(TestCase("$", "anything"))

            // -- Word boundaries --
            cases.add(TestCase("\\bfoo\\b", "foo"))
            cases.add(TestCase("\\bfoo\\b", "foo bar"))
            cases.add(TestCase("\\bfoo\\b", "bar foo baz"))
            cases.add(TestCase("\\bfoo\\b", "foobar"))
            cases.add(TestCase("\\bfoo\\b", "barfoo"))
            cases.add(TestCase("\\bbar\\b", "foo bar baz"))
            cases.add(TestCase("\\bbar\\b", "foobar"))
            cases.add(TestCase("\\Bfoo\\B", "xfoox"))
            cases.add(TestCase("\\Bfoo\\B", "foo"))

            // -- Escaped metacharacters --
            cases.add(TestCase("a\\.b", "a.b"))
            cases.add(TestCase("a\\.b", "axb"))
            cases.add(TestCase("a\\*b", "a*b"))
            cases.add(TestCase("a\\+b", "a+b"))
            cases.add(TestCase("a\\?b", "a?b"))
            cases.add(TestCase("\\(a\\)", "(a)"))
            cases.add(TestCase("\\[a\\]", "[a]"))

            // -- Unicode --
            cases.add(TestCase("café", "café"))
            cases.add(TestCase("café", "cafe"))
            cases.add(TestCase(".", "\uD83D\uDE00")) // U+1F600 emoji (surrogate pair)
            cases.add(TestCase("..", "\uD83D\uDE00\uD83D\uDE01")) // two emojis
            cases.add(TestCase("\\w+", "naïve"))
            cases.add(TestCase("[à-ÿ]+", "naïve"))
            cases.add(TestCase("\\p{L}+", "Ωmega"))

            // -- Empty pattern / empty text --
            cases.add(TestCase("", ""))
            cases.add(TestCase("", "abc"))
            cases.add(TestCase("a*", ""))
            cases.add(TestCase("()", ""))
            cases.add(TestCase("()*", ""))

            // -- Complex patterns --
            cases.add(TestCase("(a|b)*c", "aababc"))
            cases.add(TestCase("(a|b)*c", "aabab"))
            cases.add(TestCase("[a-z]+@[a-z]+\\.[a-z]+", "user@host.com"))
            cases.add(TestCase("[a-z]+@[a-z]+\\.[a-z]+", "user@host"))
            cases.add(TestCase("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "192.168.1.1"))
            cases.add(TestCase("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "999.999.999.999"))
            cases.add(TestCase("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "1.2.3"))
            cases.add(TestCase("(https?://)?[\\w.-]+\\.[a-z]{2,}", "https://example.com"))
            cases.add(TestCase("(https?://)?[\\w.-]+\\.[a-z]{2,}", "example.com"))
            cases.add(TestCase("(https?://)?[\\w.-]+\\.[a-z]{2,}", "not a url"))

            // -- Pathological for backtracking (should be fine for linear-time engines) --
            cases.add(TestCase("a?a?a?aaa", "aaa"))
            cases.add(TestCase("a?a?a?a?a?aaaaa", "aaaaa"))
            cases.add(TestCase("(a+)+b", "aaab"))
            cases.add(TestCase("(a+)+b", "aaa"))
            cases.add(TestCase("([a-z]+)*x", "abcx"))
            cases.add(TestCase("([a-z]+)*x", "abc"))

            // -- Multiline-like patterns --
            cases.add(TestCase("a.*b", "aXXXb"))
            cases.add(TestCase("a.*b", "a\nb"))
            cases.add(TestCase("a.+b", "axb"))
            cases.add(TestCase("a.+b", "ab"))

            // -- Nested quantifiers --
            cases.add(TestCase("(a{2}){3}", "aaaaaa"))
            cases.add(TestCase("(a{2}){3}", "aaaaa"))
            cases.add(TestCase("((ab){2}(cd){2})", "ababcdcd"))
            cases.add(TestCase("((ab){2}(cd){2})", "ababcd"))

            // -- Long text --
            cases.add(TestCase("needle", "hay".repeat(100) + "needle" + "hay".repeat(100)))
            cases.add(TestCase("needle", "hay".repeat(1000)))
            cases.add(TestCase("[a-z]+", "a".repeat(1000)))
            cases.add(TestCase("a{100}", "a".repeat(100)))
            cases.add(TestCase("a{100}", "a".repeat(99)))

            // -- Case sensitivity --
            cases.add(TestCase("(?i)abc", "ABC"))
            cases.add(TestCase("(?i)abc", "abc"))
            cases.add(TestCase("(?i)abc", "AbC"))
            cases.add(TestCase("(?i)[a-z]+", "HeLLo"))

            return cases.stream()
        }

        // ---------------------------------------------------------------------------
        // Assertion helper
        // ---------------------------------------------------------------------------
        /**
         * Asserts that DFA and NFA agree on match/no-match only (not end position). Used for modes where
         * end-position semantics legitimately differ between the two engines.
         */
        private fun assertMatchAgreement(tc: TestCase, anchored: Boolean, longest: Boolean) {
            val re = Parser.parse(tc.pattern, FLAGS)!!
            val prog = Compiler.compile(re)!!

            // Run DFA.
            val dfaResult = Dfa.search(prog, tc.input, anchored, longest)

            // Run NFA with matching settings.
            val nfaAnchor = if (anchored) Nfa.Anchor.ANCHORED else Nfa.Anchor.UNANCHORED
            val nfaKind = if (longest) Nfa.MatchKind.LONGEST_MATCH else Nfa.MatchKind.FIRST_MATCH
            val nfaResult = Nfa.search(prog, tc.input!!, nfaAnchor, nfaKind, 1)

            if (dfaResult == null) {
                // DFA bailed out due to budget -- skip consistency check.
                return
            }

            val nfaMatched = (nfaResult != null)
            val mode = (if (anchored) "anchored" else "unanchored") + " " + (if (longest) "longest" else "first")

            Assertions.assertThat(dfaResult.matched)
                .`as`("match/no-match for /%s/ on \"%s\" (%s)", tc.pattern, tc.input, mode)
                .isEqualTo(nfaMatched)
        }

        /**
         * Asserts that DFA and NFA agree on match/no-match AND end position. Only valid for anchored +
         * longest mode where both engines have the same semantics (start at position 0, maximize).
         */
        private fun assertMatchAndEndPosition(tc: TestCase, anchored: Boolean, longest: Boolean) {
            val re = Parser.parse(tc.pattern, FLAGS)!!
            val prog = Compiler.compile(re)!!

            val dfaResult = Dfa.search(prog, tc.input, anchored, longest)

            val nfaAnchor = if (anchored) Nfa.Anchor.ANCHORED else Nfa.Anchor.UNANCHORED
            val nfaKind = if (longest) Nfa.MatchKind.LONGEST_MATCH else Nfa.MatchKind.FIRST_MATCH
            val nfaResult = Nfa.search(prog, tc.input!!, nfaAnchor, nfaKind, 1)

            if (dfaResult == null) {
                return
            }

            val nfaMatched = (nfaResult != null)
            val mode = (if (anchored) "anchored" else "unanchored") + " " + (if (longest) "longest" else "first")

            Assertions.assertThat(dfaResult.matched)
                .`as`("match/no-match for /%s/ on \"%s\" (%s)", tc.pattern, tc.input, mode)
                .isEqualTo(nfaMatched)

            if (dfaResult.matched && nfaMatched) {
                val nfaEnd = nfaResult[1] // group(0) end
                Assertions.assertThat(dfaResult.pos)
                    .`as`(
                        "match end for /%s/ on \"%s\" (%s): DFA=%d, NFA=%d",
                        tc.pattern, tc.input, mode, dfaResult.pos, nfaEnd
                    )
                    .isEqualTo(nfaEnd)
            }
        }
    }
}
