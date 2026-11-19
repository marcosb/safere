// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.safere.ExhaustiveUtils.TestResult
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.math.min

/**
 * Exhaustive cross-engine tests comparing SafeRE against JDK regex.
 * 
 * 
 * These tests systematically generate all possible regular expressions within given parameters,
 * generate all possible strings over a given alphabet, and verify that SafeRE and JDK agree on
 * every (regexp, string) pair. This is the mechanical approach to finding bugs — enumerate
 * combinations and check agreement.
 * 
 * 
 * Each test configuration targets a specific gap identified in issue #28: feature combinations,
 * engine-specific paths (short vs long text), flag interactions, and real-world pattern
 * constructions.
 * 
 * 
 * Inspired by RE2's exhaustive_test infrastructure.
 */
@DisplayName("Cross-Engine Exhaustive Tests (SafeRE vs JDK)")
internal class CrossEngineExhaustiveTest {
    // ===== 1. Flag + Anchor combinations (bugs #1, #8, #9 from issue #28) =====
    @Nested
    @DisplayName("MULTILINE + anchors")
    internal inner class MultilineAnchors {
        @Test
        @DisplayName("MULTILINE ^/$ with \\n in text, short + long")
        fun multilineAnchorsNewline() {
            val tests =
                ExhaustiveUtils.run(
                    ExhaustiveUtils.Config( /* maxAtoms= */
                        2,  /* maxOps= */
                        2,  /* atoms= */
                        mutableListOf<String?>("a", "b", "(?:^)", "(?:$)", "."),  /* ops= */
                        ExhaustiveUtils.EGREP_OPS,  /* maxStrLen= */
                        4,  /* strAlphabet= */
                        mutableListOf<String?>("a", "b", "\n"),  /* wrapper= */
                        "",  /* flags= */
                        Pattern.MULTILINE,  /* testLongText= */
                        true
                    )
                )
            System.err.printf("multilineAnchorsNewline: %,d tests%n", tests)
        }

        @Test
        @DisplayName("MULTILINE ^/$ with multiple \\n in text, short + long")
        fun multilineAnchorsCrLf() {
            // Test with multiple newlines to exercise DFA boundary handling
            val tests =
                ExhaustiveUtils.run(
                    ExhaustiveUtils.Config( /* maxAtoms= */
                        2,  /* maxOps= */
                        1,  /* atoms= */
                        mutableListOf<String?>("a", "(?:^)", "(?:$)", "."),  /* ops= */
                        mutableListOf<String?>("%s%s", "%s*", "%s+"),  /* maxStrLen= */
                        4,  /* strAlphabet= */
                        mutableListOf<String?>("a", "b", "\n"),  /* wrapper= */
                        "",  /* flags= */
                        Pattern.MULTILINE,  /* testLongText= */
                        true
                    )
                )
            System.err.printf("multilineAnchorsCrLf: %,d tests%n", tests)
        }

        @Test
        @DisplayName("MULTILINE $ with captures on long text (Spring Boot pattern)")
        fun multilineDollarCapturesLongText() {
            // Directly test patterns similar to the Spring Boot security password pattern
            val failures: MutableList<TestResult?> = ArrayList<TestResult?>()
            var tests = 0

            val patterns = arrayOf<String?>(
                "^(.+)$",
                "^(a*)$",
                "^(.*)$",
                "^(a+)$",
                "^([ab]+)$",
                "^a(.*)b$",
                "^(.+?)$",
                "^(.*?)$",
                "(^.+$)",
                "^(.*)\\n(.*)$",
            )

            for (pat in patterns) {
                val texts = arrayOf<String?>(
                    "hello",
                    "a\nb",
                    "abc\ndef\nghi",
                    "a".repeat(200) + "\n" + "b".repeat(200),
                    "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10",
                    "#".repeat(260) + "\n" + "target",
                    "prefix\n" + "x".repeat(300) + "\nsuffix",
                )
                for (text in texts) {
                    tests += ExhaustiveUtils.testPair(pat, text, Pattern.MULTILINE, failures)
                }
            }

            System.err.printf("multilineDollarCapturesLongText: %,d tests%n", tests)
            if (!failures.isEmpty()) {
                Assertions.fail<Any?>(
                    failures.size.toString() + " failures:\n" + failures.subList(
                        0,
                        min(20, failures.size)
                    )
                )
            }
        }
    }

    // ===== 2. CASE_INSENSITIVE + DFA path (bug #3 from issue #28) =====
    @Nested
    @DisplayName("CASE_INSENSITIVE + DFA")
    internal inner class CaseInsensitiveDfa {
        @Test
        @DisplayName("Case-insensitive with letters near equivalence class boundaries")
        fun caseInsensitiveBoundaries() {
            // Characters near each other in Unicode that should/shouldn't match under /i
            val tests =
                ExhaustiveUtils.run(
                    ExhaustiveUtils.Config( /* maxAtoms= */
                        2,  /* maxOps= */
                        2,  /* atoms= */
                        mutableListOf<String?>("a", "b", "c", "[a-c]", "[b-d]", "."),  /* ops= */
                        ExhaustiveUtils.EGREP_OPS,  /* maxStrLen= */
                        4,  /* strAlphabet= */
                        mutableListOf<String?>("a", "A", "b", "B", "c", "C", "d", "D"),  /* wrapper= */
                        "",  /* flags= */
                        Pattern.CASE_INSENSITIVE,  /* testLongText= */
                        true
                    )
                )
            System.err.printf("caseInsensitiveBoundaries: %,d tests%n", tests)
        }

        @Test
        @DisplayName("Case-insensitive + MULTILINE combined")
        fun caseInsensitiveMultiline() {
            val tests =
                ExhaustiveUtils.run(
                    ExhaustiveUtils.Config( /* maxAtoms= */
                        2,  /* maxOps= */
                        1,  /* atoms= */
                        mutableListOf<String?>("a", "b", "(?:^)", "(?:$)", "[a-c]"),  /* ops= */
                        mutableListOf<String?>("%s%s", "%s*", "%s+", "%s?"),  /* maxStrLen= */
                        3,  /* strAlphabet= */
                        mutableListOf<String?>("a", "A", "b", "B", "\n"),  /* wrapper= */
                        "",  /* flags= */
                        Pattern.CASE_INSENSITIVE or Pattern.MULTILINE,  /* testLongText= */
                        true
                    )
                )
            System.err.printf("caseInsensitiveMultiline: %,d tests%n", tests)
        }

        @Test
        @DisplayName("Case-insensitive character classes on long text")
        fun caseInsensitiveCharClassLong() {
            // This specifically targets the Lucene bug: CASE_INSENSITIVE with DFA equivalence classes
            val failures: MutableList<TestResult?> = ArrayList<TestResult?>()
            var tests = 0

            val patterns = arrayOf<String?>(
                "birds", "bird", "[b][i][r][d]", "b[aeiou]rd", "[a-z]+", "b.*d", "^birds$", "the bird",
            )

            // Test with various texts that exercise DFA equivalence class boundaries
            // Key: 'B' and 'C' may share equivalence class, but 'B' matches [b]/i while 'C' doesn't
            val texts = arrayOf<String?>(
                "BIRDS",
                "birds",
                "Birds",
                "CARDS",  // C in same range as B but shouldn't match 'b'
                "the BIRDS flew",
                "A".repeat(260) + "birds",
                "C".repeat(260) + "birds",  // C characters may poison DFA cache
                "ABCDEFG".repeat(40) + "birds",
                "birds" + "X".repeat(260),
            )

            for (pat in patterns) {
                for (text in texts) {
                    tests += ExhaustiveUtils.testPair(pat, text, Pattern.CASE_INSENSITIVE, failures)
                }
            }

            System.err.printf("caseInsensitiveCharClassLong: %,d tests%n", tests)
            if (!failures.isEmpty()) {
                Assertions.fail<Any?>(
                    failures.size.toString() + " failures:\n" + failures.subList(
                        0,
                        min(20, failures.size)
                    )
                )
            }
        }
    }

    // ===== 3. DOTALL flag combinations =====
    @Nested
    @DisplayName("DOTALL combinations")
    internal inner class DotallCombinations {
        @Test
        @DisplayName("DOTALL: dot matches \\n")
        fun dotallDotMatchesNewline() {
            val tests =
                ExhaustiveUtils.run(
                    ExhaustiveUtils.Config( /* maxAtoms= */
                        2,  /* maxOps= */
                        2,  /* atoms= */
                        mutableListOf<String?>("a", ".", "[^a]"),  /* ops= */
                        mutableListOf<String?>("%s%s", "%s*", "%s+", "%s?"),  /* maxStrLen= */
                        4,  /* strAlphabet= */
                        mutableListOf<String?>("a", "b", "\n"),  /* wrapper= */
                        "",  /* flags= */
                        Pattern.DOTALL,  /* testLongText= */
                        true
                    )
                )
            System.err.printf("dotallDotMatchesNewline: %,d tests%n", tests)
        }

        @Test
        @DisplayName("DOTALL + MULTILINE combined")
        fun dotallMultiline() {
            val tests =
                ExhaustiveUtils.run(
                    ExhaustiveUtils.Config( /* maxAtoms= */
                        2,  /* maxOps= */
                        1,  /* atoms= */
                        mutableListOf<String?>("a", ".", "(?:^)", "(?:$)"),  /* ops= */
                        mutableListOf<String?>("%s%s", "%s*", "%s+"),  /* maxStrLen= */
                        3,  /* strAlphabet= */
                        mutableListOf<String?>("a", "\n"),  /* wrapper= */
                        "",  /* flags= */
                        Pattern.DOTALL or Pattern.MULTILINE,  /* testLongText= */
                        true
                    )
                )
            System.err.printf("dotallMultiline: %,d tests%n", tests)
        }

        @Test
        @DisplayName("All three flags: DOTALL + MULTILINE + CASE_INSENSITIVE")
        fun allThreeFlags() {
            val tests =
                ExhaustiveUtils.run(
                    ExhaustiveUtils.Config( /* maxAtoms= */
                        2,  /* maxOps= */
                        1,  /* atoms= */
                        mutableListOf<String?>("a", ".", "(?:^)", "(?:$)"),  /* ops= */
                        mutableListOf<String?>("%s%s", "%s*", "%s+"),  /* maxStrLen= */
                        3,  /* strAlphabet= */
                        mutableListOf<String?>("a", "A", "\n"),  /* wrapper= */
                        "",  /* flags= */
                        Pattern.DOTALL or Pattern.MULTILINE or Pattern.CASE_INSENSITIVE,  /* testLongText= */
                        true
                    )
                )
            System.err.printf("allThreeFlags: %,d tests%n", tests)
        }
    }

    // ===== 4. Word boundaries on long text =====
    @Nested
    @DisplayName("Word boundaries")
    internal inner class WordBoundaries {
        @Test
        @DisplayName("\\b and \\B with various text, short + long")
        fun wordBoundaries() {
            val tests =
                ExhaustiveUtils.run(
                    ExhaustiveUtils.Config( /* maxAtoms= */
                        2,  /* maxOps= */
                        2,  /* atoms= */
                        mutableListOf<String?>("a", "(?:\\b)", "(?:\\B)", "\\w", "\\W"),  /* ops= */
                        mutableListOf<String?>("%s%s", "%s|%s", "%s*", "%s+"),  /* maxStrLen= */
                        4,  /* strAlphabet= */
                        mutableListOf<String?>("a", "b", " ", "_"),  /* wrapper= */
                        "",  /* flags= */
                        0,  /* testLongText= */
                        true
                    )
                )
            System.err.printf("wordBoundaries: %,d tests%n", tests)
        }
    }

    // ===== 5. Anchor stripping + find() (bug #5 from issue #28) =====
    @Nested
    @DisplayName("Anchor stripping")
    internal inner class AnchorStripping {
        @Test
        @DisplayName("Anchored patterns: find() from position 0 and replaceAll")
        fun anchoredFindAndReplace() {
            val failures: MutableList<TestResult?> = ArrayList<TestResult?>()
            var tests = 0

            // Patterns where the compiler strips ^/\A into anchorStart
            val patterns = arrayOf<String?>(
                "^a",
                "^abc",
                "^[a-c]+",
                "^a*",
                "^a+b",
                "^(a)(b)",
                "^.*",
                "^.+",
                "\\Aa",
                "\\Aabc",
                "\\A[a-c]+",
                "\\A.*",
            )

            val texts = arrayOf<String?>(
                "",
                "a",
                "abc",
                "xabc",
                "abcabc",
                "abc\nabc",
                "x".repeat(300) + "abc",
                "abc" + "x".repeat(300),
            )

            for (pat in patterns) {
                for (text in texts) {
                    // Test without MULTILINE (anchor stripping applies)
                    tests += ExhaustiveUtils.testPair(pat, text, 0, failures)

                    // Also compare replaceAll
                    tests++
                    try {
                        val jdkPat = Pattern.compile(pat)
                        val saferePat = org.safere.Pattern.compile(pat)

                        val jdkResult = jdkPat.matcher(text).replaceAll("X")
                        val safereResult = saferePat.matcher(text).replaceAll("X")

                        if (jdkResult != safereResult) {
                            failures.add(
                                TestResult(
                                    pat, text, 0, "replaceAll", safereResult, jdkResult
                                )
                            )
                        }
                    } catch (e: PatternSyntaxException) {
                        // skip if either rejects
                    }
                }
            }

            System.err.printf("anchoredFindAndReplace: %,d tests%n", tests)
            if (!failures.isEmpty()) {
                Assertions.fail<Any?>(
                    failures.size.toString() + " failures:\n" + failures.subList(
                        0,
                        min(20, failures.size)
                    )
                )
            }
        }

        @Test
        @DisplayName("End-anchored patterns: $ and \\z")
        fun endAnchoredPatterns() {
            val failures: MutableList<TestResult?> = ArrayList<TestResult?>()
            var tests = 0

            val patterns = arrayOf<String?>(
                "a$", "abc$", "[a-c]+$", "a*$", "(a)(b)$", ".*$", ".+$", "a\\z", "abc\\z", "[a-c]+\\z",
            )

            val texts = arrayOf<String?>(
                "",
                "a",
                "abc",
                "xabc",
                "abcabc",
                "abc\nabc",
                "x".repeat(300) + "abc",
                "abc" + "x".repeat(300),
            )

            for (pat in patterns) {
                for (text in texts) {
                    tests += ExhaustiveUtils.testPair(pat, text, 0, failures)
                    tests += ExhaustiveUtils.testPair(pat, text, org.safere.Pattern.MULTILINE, failures)
                }
            }

            System.err.printf("endAnchoredPatterns: %,d tests%n", tests)
            if (!failures.isEmpty()) {
                Assertions.fail<Any?>(
                    failures.size.toString() + " failures:\n" + failures.subList(
                        0,
                        min(20, failures.size)
                    )
                )
            }
        }
    }

    // ===== 6. Dynamic pattern construction (bugs #2, #7 from issue #28) =====
    @Nested
    @DisplayName("Dynamic patterns")
    internal inner class DynamicPatterns {
        @Test
        @DisplayName("Pattern.quote() inside character classes")
        fun patternQuoteInCharClass() {
            val failures: MutableList<TestResult?> = ArrayList<TestResult?>()
            var tests = 0

            // Characters that Pattern.quote() wraps in \Q..\E
            val specialChars = arrayOf<String?>(
                "*", ".", "+", "?", "[", "]", "{", "}", "(", ")", "^", "$", "|", "\\"
            )

            for (ch in specialChars) {
                val quoted = org.safere.Pattern.quote(ch)
                val pat = "[" + quoted + "]+"

                val texts = arrayOf<String?>(ch, ch + ch, "a" + ch + "b", "abc", ch!!.repeat(5), "#".repeat(260) + ch)

                for (text in texts) {
                    tests += ExhaustiveUtils.testPair(pat, text, 0, failures)
                }
            }

            // Compound patterns: multiple quoted chars in a class
            val compoundPatterns = arrayOf<String?>(
                "[" + org.safere.Pattern.quote("*") + org.safere.Pattern.quote(".") + "]+",
                "[" + org.safere.Pattern.quote("\\") + org.safere.Pattern.quote("/") + "]+",
                "[a-z" + org.safere.Pattern.quote("*") + "]+",
                "[" + org.safere.Pattern.quote("[") + org.safere.Pattern.quote("]") + "]+",
            )

            val compoundTexts = arrayOf<String?>("*.", "\\/", "abc*", "[]", "a*b.c", "#".repeat(260) + "*.")

            for (pat in compoundPatterns) {
                for (text in compoundTexts) {
                    tests += ExhaustiveUtils.testPair(pat, text, 0, failures)
                }
            }

            System.err.printf("patternQuoteInCharClass: %,d tests%n", tests)
            if (!failures.isEmpty()) {
                Assertions.fail<Any?>(
                    failures.size.toString() + " failures:\n" + failures.subList(
                        0,
                        min(20, failures.size)
                    )
                )
            }
        }

        @Test
        @DisplayName("Pattern.quote() composed into larger patterns")
        fun patternQuoteComposed() {
            val failures: MutableList<TestResult?> = ArrayList<TestResult?>()
            var tests = 0

            // Patterns built like real code does
            val configs = arrayOf<Array<String?>?>(
                // { pattern template, key, text }
                arrayOf<String?>(org.safere.Pattern.quote("key") + "=(.+)", "key=value", "key=value"),
                arrayOf<String?>(org.safere.Pattern.quote("key") + "=(.+)", "key=", "key="),
                arrayOf<String?>(org.safere.Pattern.quote("key") + "=(\\d+)", "key=123", "key=123abc"),
                arrayOf<String?>("(" + org.safere.Pattern.quote("hello") + ")\\s+(\\w+)", "hello world", "hello world"),
                arrayOf<String?>(org.safere.Pattern.quote("$") + "\\{(\\w+)\\}", "\${var}", "\${var}"),
                arrayOf<String?>(
                    org.safere.Pattern.quote("[") + "(\\d+)" + org.safere.Pattern.quote("]"),
                    "[42]",
                    "item[42]"
                ),
            )

            for (config in configs) {
                val pat = config!![0]
                // Test against all the example texts
                for (i in 1..<config.size) {
                    tests += ExhaustiveUtils.testPair(pat, config[i], 0, failures)
                    // Also long text
                    tests += ExhaustiveUtils.testPair(pat, "#".repeat(260) + config[i], 0, failures)
                }
            }

            System.err.printf("patternQuoteComposed: %,d tests%n", tests)
            if (!failures.isEmpty()) {
                Assertions.fail<Any?>(
                    failures.size.toString() + " failures:\n" + failures.subList(
                        0,
                        min(20, failures.size)
                    )
                )
            }
        }

        @Test
        @DisplayName("Nested character classes: [[a-z]], [a-z[0-9]], etc.")
        fun nestedCharClasses() {
            val failures: MutableList<TestResult?> = ArrayList<TestResult?>()
            var tests = 0

            val patterns = arrayOf<String?>(
                "[[a-f]]",
                "[[a-f][0-9]]",
                "[a-z[A-Z]]",
                "[[a-z]]",
                "[a[b[c]]]",
                "[[a-f]]+",
                "[[A-Fa-f0-9]]+",
                "[[a-z][0-9]]+",
                "[^[aeiou]]",  // Note: [a-z&&[^aeiou]] (intersection) is not tested — SafeRE doesn't support &&
            )

            val texts = arrayOf<String?>(
                "a",
                "f",
                "g",
                "0",
                "5",
                "Z",
                "abc123",
                "HELLO",
                "aeiou",
                "bcdfg",
                "x".repeat(300),
                "#".repeat(260) + "abc123",
            )

            for (pat in patterns) {
                for (text in texts) {
                    // Skip if both reject the pattern (e.g., && intersection)
                    var jdkRejects = false
                    var safereRejects = false
                    try {
                        Pattern.compile(pat)
                    } catch (e: PatternSyntaxException) {
                        jdkRejects = true
                    }
                    try {
                        org.safere.Pattern.compile(pat)
                    } catch (e: PatternSyntaxException) {
                        safereRejects = true
                    }
                    if (jdkRejects || safereRejects) {
                        continue
                    }
                    tests += ExhaustiveUtils.testPair(pat, text, 0, failures)
                    tests += ExhaustiveUtils.testPair(pat, text, org.safere.Pattern.CASE_INSENSITIVE, failures)
                }
            }

            System.err.printf("nestedCharClasses: %,d tests%n", tests)
            if (!failures.isEmpty()) {
                Assertions.fail<Any?>(
                    failures.size.toString() + " failures:\n" + failures.subList(
                        0,
                        min(20, failures.size)
                    )
                )
            }
        }
    }

    // ===== 7. Engine path: short vs long text =====
    @Nested
    @DisplayName("Engine path: short vs long")
    internal inner class EnginePathTests {
        @Test
        @DisplayName("All assertions on short vs long text")
        fun assertionsShortVsLong() {
            // Same patterns tested at ≤256 and >256 char text to force different engines
            val tests =
                ExhaustiveUtils.run(
                    ExhaustiveUtils.Config( /* maxAtoms= */
                        2,  /* maxOps= */
                        2,  /* atoms= */
                        mutableListOf<String?>("a", ".", "(?:^)", "(?:$)", "(?:\\b)", "\\d", "\\w"),  /* ops= */
                        ExhaustiveUtils.EGREP_OPS,  /* maxStrLen= */
                        3,  /* strAlphabet= */
                        mutableListOf<String?>("a", "1", " "),  /* wrapper= */
                        "",  /* flags= */
                        0,  /* testLongText= */
                        true
                    )
                )
            System.err.printf("assertionsShortVsLong: %,d tests%n", tests)
        }

        @Test
        @DisplayName("Captures on long text (DFA deferred resolution)")
        fun capturesLongText() {
            val failures: MutableList<TestResult?> = ArrayList<TestResult?>()
            var tests = 0

            val patterns = arrayOf<String?>(
                "(a+)",
                "(a)(b)",
                "(a+)(b+)",
                "(a|b)+",
                "(.)(.)(.)",
                "(a+)b(c+)",
                "(?:(a)|(b))+",
                "(\\d+)\\.(\\d+)",
                "([a-z]+)([0-9]+)",
            )

            val shortTexts = arrayOf<String?>("ab", "aab", "abc", "aabcc", "1.2", "abc123", "abab")

            for (pat in patterns) {
                for (shortText in shortTexts) {
                    // Short text (OnePass/BitState path)
                    tests += ExhaustiveUtils.testPair(pat, shortText, 0, failures)

                    // Long text (DFA path) — same content, padded
                    val longText = "x".repeat(260) + shortText
                    tests += ExhaustiveUtils.testPair(pat, longText, 0, failures)

                    // Long text with content in the middle
                    val midText = "x".repeat(130) + shortText + "x".repeat(130)
                    tests += ExhaustiveUtils.testPair(pat, midText, 0, failures)
                }
            }

            System.err.printf("capturesLongText: %,d tests%n", tests)
            if (!failures.isEmpty()) {
                Assertions.fail<Any?>(
                    failures.size.toString() + " failures:\n" + failures.subList(
                        0,
                        min(20, failures.size)
                    )
                )
            }
        }

        @Test
        @DisplayName("Repetition operators on long text")
        fun repetitionLongText() {
            val tests =
                ExhaustiveUtils.run(
                    ExhaustiveUtils.Config( /* maxAtoms= */
                        3,  /* maxOps= */
                        2,  /* atoms= */
                        mutableListOf<String?>("a", "b", "(a)"),  /* ops= */
                        mutableListOf<String?>(
                            "%s{0}",
                            "%s{0,}", "%s{1}", "%s{1,}", "%s{0,1}", "%s{0,2}", "%s{1,2}", "%s{2}",
                            "%s{2,}", "%s*", "%s+", "%s?", "%s*?", "%s+?", "%s??"
                        ),  /* maxStrLen= */
                        4,  /* strAlphabet= */
                        mutableListOf<String?>("a", "b"),  /* wrapper= */
                        "(?:%s)",  /* flags= */
                        0,  /* testLongText= */
                        true
                    )
                )
            System.err.printf("repetitionLongText: %,d tests%n", tests)
        }
    }

    // ===== 8. Matcher reuse and lifecycle (bugs #4, #6 from issue #28) =====
    @Nested
    @DisplayName("Matcher lifecycle")
    internal inner class MatcherLifecycle {
        @Test
        @DisplayName("Mutable CharSequence with reset()")
        fun mutableCharSequenceReset() {
            val patterns = arrayOf<String?>("a+", "\\d+", "[a-z]+", "a.*b", "^a+$", "(a)(b)")

            for (pat in patterns) {
                val saferePat = org.safere.Pattern.compile(pat)
                val jdkPat = Pattern.compile(pat)

                val sb = StringBuilder()
                val safereM = saferePat.matcher(sb)
                val jdkM = jdkPat.matcher(sb)

                val inputs = arrayOf<String?>("aaa", "123", "hello", "ab", "aaaa", "bbb")

                for (input in inputs) {
                    sb.setLength(0)
                    sb.append(input)

                    safereM.reset()
                    jdkM.reset()

                    val safereResult = safereM.find()
                    val jdkResult = jdkM.find()

                    Assertions.assertThat(safereResult).`as`("find() pat=%s text=%s", pat, input).isEqualTo(jdkResult)

                    if (safereResult && jdkResult) {
                        Assertions.assertThat(safereM.start())
                            .`as`("start() pat=%s text=%s", pat, input)
                            .isEqualTo(jdkM.start())
                        Assertions.assertThat(safereM.end()).`as`("end() pat=%s text=%s", pat, input)
                            .isEqualTo(jdkM.end())
                    }
                }
            }
        }

        @Test
        @DisplayName("Sequential reset(CharSequence) + matches() — no state leakage")
        fun sequentialResetMatches() {
            val patterns = arrayOf<String?>("a+", "[a-z]+b", "\\d{3}", "^abc$", "(a|b)+")

            for (pat in patterns) {
                val saferePat = org.safere.Pattern.compile(pat)
                val jdkPat = Pattern.compile(pat)

                val safereM = saferePat.matcher("")
                val jdkM = jdkPat.matcher("")

                // Mix of matching and non-matching inputs
                val inputs = arrayOf<String?>("aaa", "xyz", "aab", "123", "456", "abc", "def", "", "a", "bb")

                for (input in inputs) {
                    safereM.reset(input)
                    jdkM.reset(input)

                    Assertions.assertThat(safereM.matches())
                        .`as`("matches() pat=%s text=%s", pat, input)
                        .isEqualTo(jdkM.matches())
                }
            }
        }

        @Test
        @DisplayName("find() loop after reset with different-length inputs")
        fun findLoopAfterReset() {
            val patterns = arrayOf<String?>("a", "ab", "a+", "[a-z]")

            for (pat in patterns) {
                val saferePat = org.safere.Pattern.compile(pat)
                val jdkPat = Pattern.compile(pat)

                val safereM = saferePat.matcher("")
                val jdkM = jdkPat.matcher("")

                val inputs = arrayOf<String?>("aaa", "abab", "a", "xaxax", "aaaa".repeat(100))

                for (input in inputs) {
                    safereM.reset(input)
                    jdkM.reset(input)

                    val safereMatches: MutableList<String?> = ArrayList<String?>()
                    val jdkMatches: MutableList<String?> = ArrayList<String?>()

                    while (safereM.find()) {
                        safereMatches.add(
                            String.format("[%d,%d)=%s", safereM.start(), safereM.end(), safereM.group())
                        )
                    }
                    while (jdkM.find()) {
                        jdkMatches.add(String.format("[%d,%d)=%s", jdkM.start(), jdkM.end(), jdkM.group()))
                    }

                    Assertions.assertThat<String?>(safereMatches)
                        .`as`(
                            "findAll pat=%s text=%s (len=%d)",
                            pat, if (input!!.length > 20) input.substring(0, 20) + "..." else input, input.length
                        )
                        .isEqualTo(jdkMatches)
                }
            }
        }

        @Test
        @DisplayName("Custom CharSequence without toString() override")
        fun customCharSequenceNoToString() {
            // Simulates Ghidra's ByteCharSequence — a CharSequence backed by data
            // that does NOT override toString()
            val patterns = arrayOf<String?>("a+", "abc", "a.c", "[a-z]+", "\\d+", "a+b+")
            val texts = arrayOf<String?>("abc", "aabcc", "a1c", "hello123", "aaabbb")

            for (pat in patterns) {
                val saferePat = org.safere.Pattern.compile(pat)
                val jdkPat = Pattern.compile(pat)

                for (text in texts) {
                    // Create a CharSequence that does NOT override toString()
                    val cs: CharSequence =
                        object : CharSequence {
                            override fun length(): Int {
                                return text!!.length
                            }

                            override fun charAt(index: Int): Char {
                                return text!!.get(index)
                            }

                            override fun subSequence(start: Int, end: Int): CharSequence {
                                return text!!.subSequence(start, end)
                            } // Deliberately do NOT override toString()
                        }

                    val safereM = saferePat.matcher(cs)
                    val jdkM = jdkPat.matcher(cs)

                    Assertions.assertThat(safereM.find())
                        .`as`("find() pat=%s text=%s (custom CS)", pat, text)
                        .isEqualTo(jdkM.find())

                    if (safereM.find(0) && jdkM.find(0)) {
                        Assertions.assertThat(safereM.group())
                            .`as`("group() pat=%s text=%s", pat, text)
                            .isEqualTo(jdkM.group())
                    }
                }
            }
        }

        @Test
        @DisplayName("Byte-backed CharSequence (Ghidra-style)")
        fun byteBackedCharSequence() {
            val patterns = arrayOf<String?>("[a-z]+", "\\w+", "abc", "a.c", "[\\x00-\\x7f]+")
            val byteArrays = arrayOf<ByteArray>(
                byteArrayOf(0x61, 0x62, 0x63),  // "abc"
                byteArrayOf(0x68, 0x65, 0x6c, 0x6c, 0x6f),  // "hello"
                byteArrayOf(0x41, 0x42, 0x43),  // "ABC"
                byteArrayOf(0x30, 0x31, 0x32),  // "012"
                byteArrayOf(0x61, 0x20, 0x62),  // "a b"
            )

            for (pat in patterns) {
                val saferePat: org.safere.Pattern?
                val jdkPat: Pattern?
                try {
                    saferePat = org.safere.Pattern.compile(pat)
                    jdkPat = Pattern.compile(pat)
                } catch (e: PatternSyntaxException) {
                    continue
                }

                for (bytes in byteArrays) {
                    val cs: CharSequence =
                        object : CharSequence {
                            override fun length(): Int {
                                return bytes.size
                            }

                            override fun charAt(index: Int): Char {
                                return (bytes[index].toInt() and 0xFF).toChar()
                            }

                            override fun subSequence(start: Int, end: Int): CharSequence {
                                val chars = CharArray(end - start)
                                for (i in start..<end) {
                                    chars[i - start] = (bytes[i].toInt() and 0xFF).toChar()
                                }
                                return String(chars)
                            }
                        }

                    val safereM = saferePat.matcher(cs)
                    val jdkM = jdkPat.matcher(cs)

                    val safereFound = safereM.find()
                    val jdkFound = jdkM.find()

                    Assertions.assertThat(safereFound)
                        .`as`("find() pat=%s bytes=%s", pat, bytes.contentToString())
                        .isEqualTo(jdkFound)

                    if (safereFound && jdkFound) {
                        Assertions.assertThat(safereM.start()).`as`("start() pat=%s", pat).isEqualTo(jdkM.start())
                        Assertions.assertThat(safereM.end()).`as`("end() pat=%s", pat).isEqualTo(jdkM.end())
                    }
                }
            }
        }

        @Test
        @DisplayName("replaceAll/replaceFirst after reset")
        fun replaceAfterReset() {
            val patterns = arrayOf<String?>("a+", "[0-9]+", "(a)(b)", "\\s+")
            val texts = arrayOf<String?>("aaa bbb", "x1y2z3", "abab", "  hello  world  ")

            for (pat in patterns) {
                val saferePat = org.safere.Pattern.compile(pat)
                val jdkPat = Pattern.compile(pat)

                val safereM = saferePat.matcher("")
                val jdkM = jdkPat.matcher("")

                for (text in texts) {
                    safereM.reset(text)
                    jdkM.reset(text)

                    Assertions.assertThat(safereM.replaceAll("X"))
                        .`as`("replaceAll pat=%s text=%s", pat, text)
                        .isEqualTo(jdkM.replaceAll("X"))

                    safereM.reset(text)
                    jdkM.reset(text)

                    Assertions.assertThat(safereM.replaceFirst("X"))
                        .`as`("replaceFirst pat=%s text=%s", pat, text)
                        .isEqualTo(jdkM.replaceFirst("X"))
                }
            }
        }
    }

    // ===== 9. Line endings (RE2 exhaustive2_test.cc port) =====
    @Nested
    @DisplayName("Line endings exhaustive")
    internal inner class LineEndings {
        @Test
        @DisplayName("^/$/.\\A\\z with line endings (port of RE2 exhaustive2_test)")
        fun lineEndsExhaustive() {
            val tests =
                ExhaustiveUtils.run(
                    ExhaustiveUtils.Config( /* maxAtoms= */
                        2,  /* maxOps= */
                        2,  /* atoms= */
                        mutableListOf<String?>("(?:^)", "(?:$)", ".", "a", "\\n", "(?:\\A)", "(?:\\z)"),  /* ops= */
                        ExhaustiveUtils.EGREP_OPS,  /* maxStrLen= */
                        3,  /* strAlphabet= */
                        mutableListOf<String?>("a", "b", "\n"),  /* wrapper= */
                        "",  /* flags= */
                        0,  /* testLongText= */
                        true
                    )
                )
            System.err.printf("lineEndsExhaustive: %,d tests%n", tests)
        }

        @Test
        @DisplayName("Mixed line endings: \\n, \\r\\n, \\r")
        fun mixedLineEndings() {
            val failures: MutableList<TestResult?> = ArrayList<TestResult?>()
            var tests = 0

            val patterns = arrayOf<String?>(
                "^a$", "^.*$", "^.+$", "^a+$", "^[a-z]+$", "^(a+)$", "(?m)^a$", "(?m)^.*$", "(?m)^.+$",
            )

            // Test \n endings only. \r and \r\n tests are skipped by ExhaustiveUtils because
            // JDK treats \r as a line terminator (dot doesn't match it, $ matches before it)
            // but SafeRE (like RE2) only gives special treatment to \n. This is a known design
            // difference documented in #28.
            val texts = arrayOf<String?>(
                "a\nb",
                "a\nb\nc",
                "\na\n",
                "a".repeat(260) + "\nb",
                "\n".repeat(5) + "a" + "\n".repeat(5),
                "a\n" + "b".repeat(260) + "\nc",
            )

            for (pat in patterns) {
                for (text in texts) {
                    tests += ExhaustiveUtils.testPair(pat, text, 0, failures)
                    tests += ExhaustiveUtils.testPair(pat, text, org.safere.Pattern.MULTILINE, failures)
                }
            }

            System.err.printf("mixedLineEndings: %,d tests%n", tests)
            if (!failures.isEmpty()) {
                Assertions.fail<Any?>(
                    failures.size.toString() + " failures:\n" + failures.subList(
                        0,
                        min(20, failures.size)
                    )
                )
            }
        }
    }

    // ===== 10. Character classes exhaustive (RE2 exhaustive3_test.cc port) =====
    @Nested
    @DisplayName("Character classes exhaustive")
    internal inner class CharacterClassesExhaustive {
        @Test
        @DisplayName("Character class combinations")
        fun charClassCombinations() {
            val tests =
                ExhaustiveUtils.run(
                    ExhaustiveUtils.Config( /* maxAtoms= */
                        2,  /* maxOps= */
                        1,  /* atoms= */
                        mutableListOf<String?>(
                            "[a]", "[b]", "[ab]", "[^bc]", "[b-d]", "[^b-d]", "a", "b", "."
                        ),  /* ops= */
                        ExhaustiveUtils.EGREP_OPS,  /* maxStrLen= */
                        4,  /* strAlphabet= */
                        mutableListOf<String?>("a", "b", "c"),  /* wrapper= */
                        "",  /* flags= */
                        0,  /* testLongText= */
                        true
                    )
                )
            System.err.printf("charClassCombinations: %,d tests%n", tests)
        }

        @Test
        @DisplayName("Shorthand character classes (\\d, \\w, \\s)")
        fun shorthandCharClasses() {
            val tests =
                ExhaustiveUtils.run(
                    ExhaustiveUtils.Config( /* maxAtoms= */
                        2,  /* maxOps= */
                        1,  /* atoms= */
                        mutableListOf<String?>("\\d", "\\D", "\\w", "\\W", "\\s", "\\S", ".", "a"),  /* ops= */
                        mutableListOf<String?>("%s%s", "%s*", "%s+"),  /* maxStrLen= */
                        3,  /* strAlphabet= */
                        mutableListOf<String?>("a", "1", " ", "!"),  /* wrapper= */
                        "",  /* flags= */
                        0,  /* testLongText= */
                        true
                    )
                )
            System.err.printf("shorthandCharClasses: %,d tests%n", tests)
        }
    }

    // ===== 11. Case folding (RE2 exhaustive_test.cc FoldCase port) =====
    @Nested
    @DisplayName("Case folding exhaustive")
    internal inner class CaseFolding {
        @Test
        @DisplayName("Case-insensitive with punctuation near A-Z/a-z boundaries")
        fun caseFoldWithPunctuation() {
            // RE2 comment: "The punctuation characters surround A-Z and a-z in the ASCII table.
            // This looks for bugs in the bytemap range code in the DFA."
            val tests =
                ExhaustiveUtils.run(
                    ExhaustiveUtils.Config( /* maxAtoms= */
                        2,  /* maxOps= */
                        2,  /* atoms= */
                        mutableListOf<String?>("a", "b", "A", "B", "."),  /* ops= */
                        ExhaustiveUtils.EGREP_OPS,  /* maxStrLen= */
                        3,  /* strAlphabet= */
                        mutableListOf<String?>("a", "B", "c", "@", "_", "~"),  /* wrapper= */
                        "(?i:%s)",  /* flags= */
                        0,  /* testLongText= */
                        true
                    )
                )
            System.err.printf("caseFoldWithPunctuation: %,d tests%n", tests)
        }
    }
}
