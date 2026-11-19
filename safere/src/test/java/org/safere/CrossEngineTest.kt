// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.regex.PatternSyntaxException
import java.util.stream.Stream

/**
 * Cross-engine test that runs the same regex patterns and input strings through both SafeRE (`org.safere.Pattern`/`Matcher`) and `java.util.regex.Pattern`/`Matcher`,
 * comparing results.
 * 
 * 
 * Each test case specifies a [Divergence] describing whether the two engines are expected
 * to agree, whether SafeRE rejects the pattern, or whether results may legitimately differ.
 */
@DisplayName("Cross-engine: SafeRE vs java.util.regex")
internal class CrossEngineTest {
    /** Describes expected divergence between SafeRE and java.util.regex for a test case.  */
    internal enum class Divergence {
        /** Both engines should produce identical results.  */
        NONE,

        /** SafeRE rejects this pattern (backreferences, lookahead, etc.).  */
        SAFERE_REJECTS
    }

    /** A single cross-engine test case.  */
    @JvmRecord
    internal data class TestCase(val pattern: String?, val input: String?, val divergence: Divergence?)      {
        constructor(pattern: String?, input: String?) : this(pattern, input, Divergence.NONE)

        override fun toString(): String {
            return ("/"
                    + pattern!!.replace("\n", "\\n")
                    + "/ on \""
                    + input!!.replace("\n", "\\n")
                    + "\" ("
                    + divergence
                    + ")")
        }
    }

    /** A single captured match from a `find()` call.  */
    @JvmRecord
    private data class MatchResult(
        val group: String?,
        val start: Int,
        val end: Int,
        val groupCount: Int,
        val groups: MutableList<String?>?
    )

    // ---------------------------------------------------------------------------
    // Main parameterized test
    // ---------------------------------------------------------------------------
    @ParameterizedTest(name = "{0}")
    @MethodSource("testCases")
    @DisplayName("Cross-engine comparison")
    fun crossEngineComparison(tc: TestCase) {
        // --- Phase 0: Compile with both engines ---
        val safeRePattern: org.safere.Pattern
        val jdkPattern: java.util.regex.Pattern

        // Compile with java.util.regex first (it supports everything).
        try {
            jdkPattern = java.util.regex.Pattern.compile(tc.pattern)
        } catch (e: PatternSyntaxException) {
            // If java.util.regex can't compile it, neither engine can; skip.
            return
        }

        // Compile with SafeRE.
        if (tc.divergence == Divergence.SAFERE_REJECTS) {
            try {
                org.safere.Pattern.compile(tc.pattern)
                Assertions.fail<Any?>(
                    "Expected SafeRE to reject pattern /" + tc.pattern + "/ with PatternSyntaxException"
                )
            } catch (expected: PatternSyntaxException) {
                // Good — SafeRE correctly rejects this pattern.
            }
            return
        }

        try {
            safeRePattern = org.safere.Pattern.compile(tc.pattern)
        } catch (e: PatternSyntaxException) {
            Assertions.fail<Any?>("SafeRE unexpectedly rejected pattern /" + tc.pattern + "/: " + e.message)
            return  // unreachable, but keeps the compiler happy
        }

        val expectEqual = tc.divergence == Divergence.NONE

        // --- Phase 1: matches() ---
        compareMatches(safeRePattern, jdkPattern, tc, expectEqual)

        // --- Phase 2: find() loop ---
        compareFind(safeRePattern, jdkPattern, tc, expectEqual)

        // --- Phase 3: replaceAll() ---
        compareReplaceAll(safeRePattern, jdkPattern, tc, expectEqual)

        // --- Phase 4: split() ---
        compareSplit(safeRePattern, jdkPattern, tc, expectEqual)
    }

    companion object {
        // ---------------------------------------------------------------------------
        // Test corpus
        // ---------------------------------------------------------------------------
        @JvmStatic
        fun testCases(): Stream<TestCase?> {
            return Stream.of<TestCase?>( // ===== Literals =====
                TestCase("abc", "abc"),
                TestCase("abc", "xabcy"),
                TestCase("abc", "def"),
                TestCase("", "abc"),
                TestCase("", ""),
                TestCase("hello world", "hello world"),
                TestCase("hello world", "say hello world now"),
                TestCase("aaa", "aaaa"),  // ===== Character classes =====

                TestCase("[abc]", "a"),
                TestCase("[abc]", "b"),
                TestCase("[abc]", "d"),
                TestCase("[a-z]", "m"),
                TestCase("[a-z]", "M"),
                TestCase("[^abc]", "d"),
                TestCase("[^abc]", "a"),
                TestCase("[a-zA-Z0-9]", "Z"),
                TestCase("[a-zA-Z0-9]", "!"),
                TestCase("[^\\s]", "a"),
                TestCase("[^\\s]", " "),  // ===== Shorthand character classes =====

                TestCase("\\d", "5"),
                TestCase("\\d", "a"),
                TestCase("\\w", "a"),
                TestCase("\\w", "!"),
                TestCase("\\s", " "),
                TestCase("\\s", "a"),
                TestCase("\\D", "a"),
                TestCase("\\D", "5"),
                TestCase("\\W", " "),
                TestCase("\\W", "a"),
                TestCase("\\S", "a"),
                TestCase("\\S", " "),
                TestCase("\\d+", "abc123def456"),
                TestCase("\\w+", "hello world"),  // ===== Quantifiers =====

                TestCase("a*", ""),
                TestCase("a*", "aaa"),
                TestCase("a*", "bbb"),
                TestCase("a+", "aaa"),
                TestCase("a+", ""),
                TestCase("a?", "a"),
                TestCase("a?", ""),
                TestCase("a?", "b"),
                TestCase("a{3}", "aaa"),
                TestCase("a{3}", "aa"),
                TestCase("a{3}", "aaaa"),
                TestCase("a{2,4}", "a"),
                TestCase("a{2,4}", "aa"),
                TestCase("a{2,4}", "aaa"),
                TestCase("a{2,4}", "aaaa"),
                TestCase("a{2,4}", "aaaaa"),
                TestCase("a{0,}", "aaa"),
                TestCase("a{1,}", "aaa"),  // ===== Non-greedy quantifiers =====

                TestCase("a*?", "aaa"),
                TestCase("a+?", "aaa"),
                TestCase("a??", "a"),
                TestCase("a{2,4}?", "aaaa"),  // ===== Dot =====

                TestCase(".", "a"),
                TestCase(".", "\n"),
                TestCase(".+", "hello"),
                TestCase("a.b", "axb"),
                TestCase("a.b", "a\nb"),
                TestCase("a.b", "ab"),
                TestCase("...", "ab"),
                TestCase("...", "abc"),
                TestCase("...", "abcd"),  // ===== Anchors =====

                TestCase("^abc", "abc"),
                TestCase("^abc", "xabc"),
                TestCase("abc$", "abc"),
                TestCase("abc$", "abcx"),
                TestCase("^abc$", "abc"),
                TestCase("^abc$", "abcx"),
                TestCase("^abc$", "xabc"),
                TestCase("^$", ""),
                TestCase("^$", "a"),
                TestCase("^", ""),
                TestCase("^", "abc"),
                TestCase("$", ""),
                TestCase("$", "abc"),  // ===== Alternation =====

                TestCase("cat|dog", "cat"),
                TestCase("cat|dog", "dog"),
                TestCase("cat|dog", "bird"),
                TestCase("cat|dog", "my cat is nice"),
                TestCase("ab|a", "ab"),
                TestCase("a|b|c", "b"),
                TestCase("a|b|c", "d"),
                TestCase("abc|def|ghi", "def"),
                TestCase("a|ab", "ab"),  // ===== Groups and captures =====

                TestCase("(a)(b)(c)", "abc"),
                TestCase("(a+)(b+)", "aaabbb"),
                TestCase("(a|(b))", "a"),
                TestCase("(a|(b))", "b"),
                TestCase("(?:abc)", "abc"),
                TestCase("(?:abc)", "def"),
                TestCase("((a)(b))", "ab"),
                TestCase("(a(b(c)))", "abc"),
                TestCase("(\\w+)@(\\w+)", "user@host"),
                TestCase("(a)?b", "b"),
                TestCase("(a)?b", "ab"),  // ===== Word boundaries =====

                TestCase("\\bfoo\\b", "foo bar"),
                TestCase("\\bfoo\\b", "foobar"),
                TestCase("\\bfoo\\b", "barfoo"),
                TestCase("\\bbar\\b", "foo bar baz"),
                TestCase("\\bbar\\b", "foobar"),
                TestCase("\\b\\w+\\b", "hello"),  // ===== Repetition with groups =====

                TestCase("(ab)+", "ababab"),
                TestCase("(a)+", "aaa"),
                TestCase("(a)+(b)+", "aaabbb"),
                TestCase("([ab])+", "abab"),  // ===== Complex patterns =====

                TestCase("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", "user@example.com"),
                TestCase("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", "no email here"),
                TestCase(
                    "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", "contact a@b.co and x@y.org"
                ),
                TestCase("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "192.168.1.1"),
                TestCase("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "not an ip"),
                TestCase("https?://[^\\s]+", "visit https://example.com today"),
                TestCase("https?://[^\\s]+", "http://foo.bar/baz?q=1"),
                TestCase("https?://[^\\s]+", "no url here"),
                TestCase("(\\d{4})-(\\d{2})-(\\d{2})", "2025-03-17"),
                TestCase("(\\d{4})-(\\d{2})-(\\d{2})", "date is 2025-03-17 ok"),
                TestCase("(\\d{4})-(\\d{2})-(\\d{2})", "2025-03-17 and 2024-12-25"),  // ===== Unicode =====

                TestCase(".", "\u00e9"),
                TestCase(".", "\ud83d\ude00"),
                TestCase("[\u00e0-\u00ff]", "\u00e9"),
                TestCase("[\u00e0-\u00ff]", "a"),  // ===== Character escapes =====
                // Octal

                TestCase("\\012", "\n"),
                TestCase("\\012", "a"),
                TestCase("\\0", "\u0000"),
                TestCase("\\077", "?"),  // 077 octal = 63 = '?'
                // \e (escape char)
                TestCase("\\e", "\u001B"),
                TestCase("\\e", "a"),  // \cx (control chars)
                TestCase("\\cA", "\u0001"),
                TestCase("\\cA", "a"),
                TestCase("\\cZ", "\u001A"),
                TestCase("\\c@", "\u0000"),  // \N{name} (named Unicode character)
                TestCase("\\N{LATIN SMALL LETTER A}", "a"),
                TestCase("\\N{LATIN SMALL LETTER A}", "b"),
                TestCase("\\N{WHITE SMILING FACE}", "\u263A"),  // ===== Escaped metacharacters =====

                TestCase("\\.", "a.b"),
                TestCase("\\.", "abc"),
                TestCase("\\*", "a*b"),
                TestCase("\\+", "a+b"),
                TestCase("\\?", "a?b"),
                TestCase("\\(", "("),
                TestCase("\\)", ")"),
                TestCase("\\[", "["),
                TestCase("\\{", "{"),
                TestCase("\\\\", "a\\b"),  // ===== Backreferences (SAFERE_REJECTS) =====

                TestCase("(a)\\1", "aa", Divergence.SAFERE_REJECTS),
                TestCase("(\\w+) \\1", "hello hello", Divergence.SAFERE_REJECTS),
                TestCase("([abc])\\1", "aa", Divergence.SAFERE_REJECTS),
                TestCase("(?<name>a)\\k<name>", "aa", Divergence.SAFERE_REJECTS),
                TestCase(
                    "(?<word>\\w+) \\k<word>",
                    "hello hello",
                    Divergence.SAFERE_REJECTS
                ),  // ===== Lookahead (SAFERE_REJECTS) =====

                TestCase("(?=a)a", "a", Divergence.SAFERE_REJECTS),
                TestCase("(?!b)a", "a", Divergence.SAFERE_REJECTS),
                TestCase("a(?=b)", "ab", Divergence.SAFERE_REJECTS),
                TestCase("a(?!b)", "ac", Divergence.SAFERE_REJECTS),  // ===== Lookbehind (SAFERE_REJECTS) =====

                TestCase("(?<=a)b", "ab", Divergence.SAFERE_REJECTS),
                TestCase(
                    "(?<!a)b",
                    "cb",
                    Divergence.SAFERE_REJECTS
                ),  // ===== Possessive quantifiers (SAFERE_REJECTS) =====

                TestCase("a++", "aaa", Divergence.SAFERE_REJECTS),
                TestCase("a*+", "aaa", Divergence.SAFERE_REJECTS),
                TestCase("a?+", "a", Divergence.SAFERE_REJECTS),
                TestCase("a{2}+", "aa", Divergence.SAFERE_REJECTS),
                TestCase("a{2,}+", "aaa", Divergence.SAFERE_REJECTS),
                TestCase("a{2,5}+", "aaa", Divergence.SAFERE_REJECTS),  // ===== Atomic groups (SAFERE_REJECTS) =====

                TestCase("(?>a+)", "aaa", Divergence.SAFERE_REJECTS),  // ===== Edge cases =====

                TestCase("(a*)", ""),
                TestCase("a*b", "b"),
                TestCase("a*b", "aaab"),
                TestCase(".{0}", "abc"),
                TestCase("a{100}", "a".repeat(100)),
                TestCase("a{100}", "a".repeat(99)),
                TestCase("a?a?a?aaa", "aaa"),  // ===== Multiple find() matches =====

                TestCase("\\d+", "a1b22c333"),
                TestCase("[aeiou]", "hello world"),
                TestCase("\\b\\w+\\b", "hello world foo"),
                TestCase("[0-9]+", "no digits here"),
                TestCase("a", "banana"),
                TestCase("an", "banana"),
                TestCase("[a-z]+", "Hello World 123"),  // ===== Replacement-oriented patterns =====

                TestCase("\\s+", "hello   world   foo"),
                TestCase(",", "a,b,c,d"),
                TestCase("\\d", "a1b2c3"),  // ===== Split-oriented patterns =====

                TestCase(":", "a:b:c"),
                TestCase("[,;]", "a,b;c,d"),
                TestCase("\\s+", "one two  three"),  // ===== Nested quantifiers =====

                TestCase("(a+)+", "aaa"),
                TestCase("((a|b)+)", "abab"),  // ===== Character class edge cases =====

                TestCase("[]]", "]"),
                TestCase("[-a]", "-"),
                TestCase("[-a]", "a"),
                TestCase("[a-]", "-"),
                TestCase("[a-]", "a"),
                TestCase("[\\-]", "-"),  // ===== Miscellaneous =====

                TestCase("(?i)abc", "ABC"),
                TestCase("(?i)abc", "AbC"),
                TestCase("(?i)abc", "def"),
                TestCase("(?i)[a-z]+", "HeLLo"),
                TestCase("a{0}", "a"),
                TestCase("a{0}", ""),
                TestCase("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)", "abcdefghij"),
                TestCase("x*", "y")
            )
        }

        // ---------------------------------------------------------------------------
        // Comparison helpers
        // ---------------------------------------------------------------------------
        private fun compareMatches(
            safeRePattern: org.safere.Pattern, jdkPattern: java.util.regex.Pattern, tc: TestCase, expectEqual: Boolean
        ) {
            val safeReResult = safeRePattern.matcher(tc.input).matches()
            val jdkResult = jdkPattern.matcher(tc.input).matches()

            if (expectEqual) {
                Assertions.assertThat(safeReResult)
                    .`as`(
                        "matches() for /%s/ on \"%s\": SafeRE=%s, JDK=%s",
                        tc.pattern, tc.input, safeReResult, jdkResult
                    )
                    .isEqualTo(jdkResult)
            }
            // For SAFERE_REJECTS, we just verify SafeRE didn't crash (it already ran).
        }

        private fun compareFind(
            safeRePattern: org.safere.Pattern, jdkPattern: java.util.regex.Pattern, tc: TestCase, expectEqual: Boolean
        ) {
            val safeReMatches: MutableList<MatchResult> = collectFinds(safeRePattern.matcher(tc.input))
            val jdkMatches: MutableList<MatchResult> = collectFindsJdk(jdkPattern.matcher(tc.input))

            if (!expectEqual) {
                return
            }

            Assertions.assertThat(safeReMatches.size)
                .`as`("find() match count for /%s/ on \"%s\"", tc.pattern, tc.input)
                .isEqualTo(jdkMatches.size)

            for (i in safeReMatches.indices) {
                val sr = safeReMatches.get(i)
                val jdk = jdkMatches.get(i)

                Assertions.assertThat(sr.group)
                    .`as`("find() match #%d group() for /%s/ on \"%s\"", i, tc.pattern, tc.input)
                    .isEqualTo(jdk.group)
                Assertions.assertThat(sr.start)
                    .`as`("find() match #%d start() for /%s/ on \"%s\"", i, tc.pattern, tc.input)
                    .isEqualTo(jdk.start)
                Assertions.assertThat(sr.end)
                    .`as`("find() match #%d end() for /%s/ on \"%s\"", i, tc.pattern, tc.input)
                    .isEqualTo(jdk.end)

                // Compare capturing groups.
                Assertions.assertThat(sr.groupCount)
                    .`as`("find() match #%d groupCount() for /%s/ on \"%s\"", i, tc.pattern, tc.input)
                    .isEqualTo(jdk.groupCount)
                Assertions.assertThat<String?>(sr.groups)
                    .`as`("find() match #%d groups for /%s/ on \"%s\"", i, tc.pattern, tc.input)
                    .isEqualTo(jdk.groups)
            }
        }

        private fun compareReplaceAll(
            safeRePattern: org.safere.Pattern, jdkPattern: java.util.regex.Pattern, tc: TestCase, expectEqual: Boolean
        ) {
            val replacement = "X"
            val safeReResult = safeRePattern.matcher(tc.input).replaceAll(replacement)
            val jdkResult = jdkPattern.matcher(tc.input).replaceAll(replacement)

            if (expectEqual) {
                Assertions.assertThat(safeReResult)
                    .`as`("replaceAll(\"X\") for /%s/ on \"%s\"", tc.pattern, tc.input)
                    .isEqualTo(jdkResult)
            }
        }

        private fun compareSplit(
            safeRePattern: org.safere.Pattern, jdkPattern: java.util.regex.Pattern, tc: TestCase, expectEqual: Boolean
        ) {
            val safeReParts = safeRePattern.split(tc.input!!)
            val jdkParts = jdkPattern.split(tc.input)

            if (expectEqual) {
                Assertions.assertThat<String?>(safeReParts)
                    .`as`("split() for /%s/ on \"%s\"", tc.pattern, tc.input)
                    .isEqualTo(jdkParts)
            }
        }

        // ---------------------------------------------------------------------------
        // Match-collection helpers
        // ---------------------------------------------------------------------------
        /** Collects all `find()` results from a SafeRE [Matcher].  */
        private fun collectFinds(matcher: org.safere.Matcher): MutableList<MatchResult> {
            val results: MutableList<MatchResult> = ArrayList<MatchResult>()
            while (matcher.find()) {
                val gc = matcher.groupCount()
                val groups: MutableList<String?> = ArrayList<String?>()
                for (g in 0..gc) {
                    groups.add(matcher.group(g))
                }
                results.add(MatchResult(matcher.group(), matcher.start(), matcher.end(), gc, groups))
            }
            return results
        }

        /** Collects all `find()` results from a JDK [Matcher].  */
        private fun collectFindsJdk(matcher: java.util.regex.Matcher): MutableList<MatchResult> {
            val results: MutableList<MatchResult> = ArrayList<MatchResult>()
            while (matcher.find()) {
                val gc = matcher.groupCount()
                val groups: MutableList<String?> = ArrayList<String?>()
                for (g in 0..gc) {
                    groups.add(matcher.group(g))
                }
                results.add(MatchResult(matcher.group(), matcher.start(), matcher.end(), gc, groups))
            }
            return results
        }
    }
}
