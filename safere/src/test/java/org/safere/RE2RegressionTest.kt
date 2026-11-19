// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.regex.PatternSyntaxException
import java.util.stream.Stream

/**
 * Tests ported from RE2 C++ `re2_test.cc`. Focuses on bug regression tests, edge cases,
 * Unicode classes, replace operations, named groups, and error handling.
 */
@DisplayName("RE2 C++ re2_test.cc Ports")
internal class RE2RegressionTest {
    // ---------------------------------------------------------------------------
    // Replace / ReplaceAll
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Replace operations")
    internal class ReplaceTests {
        @JvmRecord
        internal data class ReplaceCase(
            val pattern: String?,
            val rewrite: String?,
            val original: String?,
            val expectedFirst: String?,
            val expectedAll: String?,
            val expectedCount: Int
        )

        @ParameterizedTest
        @MethodSource("replaceCases")
        fun replaceFirstAndAll(
            pattern: String?,
            rewrite: String?,
            original: String?,
            expectedFirst: String?,
            expectedAll: String?,
            expectedCount: Int
        ) {
            val m = Pattern.compile(pattern).matcher(original)

            // replaceFirst
            val first = m.replaceFirst(rewrite)
            Assertions.assertThat(first).isEqualTo(expectedFirst)

            // replaceAll — Java semantics (differs from C++ RE2 for empty matches after non-empty)
            m.reset()
            val all = m.replaceAll(rewrite)
            Assertions.assertThat(all).isEqualTo(expectedAll)
        }

        @Test
        @DisplayName("Replace with $ anchor on empty string")
        fun replaceEndAnchorEmpty() {
            val m = Pattern.compile("$").matcher("")
            Assertions.assertThat(m.replaceFirst("(END)")).isEqualTo("(END)")
            m.reset()
            Assertions.assertThat(m.replaceAll("(END)")).isEqualTo("(END)")
        }

        @Test
        @DisplayName("Replace with newline handling (dot doesn't match newline)")
        fun replaceNewlineHandling() {
            val m = Pattern.compile("a.*a").matcher("aba\naba")
            Assertions.assertThat(m.replaceFirst("($0)")).isEqualTo("(aba)\naba")
            m.reset()
            Assertions.assertThat(m.replaceAll("($0)")).isEqualTo("(aba)\n(aba)")
        }

        companion object {
            @JvmStatic
            fun replaceCases(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of(
                        "(qu|[b-df-hj-np-tv-z]*)([a-z]+)",
                        "$2$1ay",
                        "the quick brown fox jumps over the lazy dogs.",
                        "ethay quick brown fox jumps over the lazy dogs.",
                        "ethay ickquay ownbray oxfay umpsjay overay ethay azylay ogsday.",
                        9
                    ),
                    Arguments.of(
                        "\\w+",
                        "$0-NOSPAM",
                        "abcd.efghi@google.com",
                        "abcd-NOSPAM.efghi@google.com",
                        "abcd-NOSPAM.efghi-NOSPAM@google-NOSPAM.com-NOSPAM",
                        4
                    ),
                    Arguments.of("b", "bb", "ababababab", "abbabababab", "abbabbabbabbabb", 5),
                    Arguments.of("b", "bb", "bbbbbb", "bbbbbbb", "bbbbbbbbbbbb", 6),
                    Arguments.of(
                        "b+",
                        "bb",
                        "bbbbbb",
                        "bb",
                        "bb",
                        1
                    ),  // Note: C++ RE2 gives replaceAll="bb"/count=1, but Java gives "bbbb"/count=2
                    // because Java includes the empty match at the end after a non-empty match.
                    Arguments.of("b*", "bb", "bbbbbb", "bb", "bbbb", 2),
                    Arguments.of("b*", "bb", "aaaaa", "bbaaaaa", "bbabbabbabbabbabb", 6)
                )
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Empty charsets
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Empty charsets")
    internal inner class EmptyCharsetTests {
        @ParameterizedTest
        @ValueSource(strings = ["[^\\S\\s]", "[^\\S\\p{Zs}]", "[^\\D\\d]", "[^\\D\\p{Nd}]"])
        fun emptyCharsetNeverMatches(pattern: String?) {
            val m = Pattern.compile(pattern).matcher("abc")
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("BitState assumptions: empty charset in captures")
        fun bitstateAssumptions() {
            // Captures trigger BitState. Empty charset alternated with nothing should still match.
            val patterns = arrayOf<String?>(
                "((((()))))[^\\S\\s]?",
                "((((()))))([^\\S\\s])?",
                "((((()))))([^\\S\\s]|[^\\S\\s])?",
                "((((()))))((([^\\S\\s]|[^\\S\\s])|))"
            )
            for (pat in patterns) {
                Assertions.assertThat(Pattern.compile(pat).matcher("").matches())
                    .`as`("Pattern %s should match empty string", pat)
                    .isTrue()
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Named groups
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Named capturing groups")
    internal inner class NamedGroupTests {
        @Test
        @DisplayName("Unnamed group has no named captures")
        fun unnamedGroupHasNoNames() {
            val p = Pattern.compile("(hello world)")
            val m = p.matcher("")
            Assertions.assertThat(m.groupCount()).isEqualTo(1)
            Assertions.assertThat<String?, Int?>(p.namedGroups()).isEmpty()
        }

        @Test
        @DisplayName("Multiple named and unnamed groups")
        fun multipleNamedGroups() {
            val p = Pattern.compile("(?<A>expr(?<B>expr)(?<C>expr))((expr)(?<D>expr))")
            val m = p.matcher("")
            Assertions.assertThat(m.groupCount()).isEqualTo(6)
            val names = p.namedGroups()
            Assertions.assertThat<String?, Int?>(names).hasSize(4)
            Assertions.assertThat(names.get("A")).isEqualTo(1)
            Assertions.assertThat(names.get("B")).isEqualTo(2)
            Assertions.assertThat(names.get("C")).isEqualTo(3)
            Assertions.assertThat(names.get("D")).isEqualTo(6) // $4 and $5 are anonymous
        }

        @Test
        @DisplayName("Named group match extraction")
        fun namedGroupMatch() {
            val p = Pattern.compile("directions from (?<S>.*) to (?<D>.*)")
            val m = p.matcher("directions from mountain view to san jose")
            Assertions.assertThat(m.groupCount()).isEqualTo(2)
            Assertions.assertThat(m.matches()).isTrue()

            val names = p.namedGroups()
            Assertions.assertThat(names.get("S")).isEqualTo(1)
            Assertions.assertThat(names.get("D")).isEqualTo(2)

            Assertions.assertThat(m.group(names.get("S")!!)).isEqualTo("mountain view")
            Assertions.assertThat(m.group(names.get("D")!!)).isEqualTo("san jose")
        }
    }

    // ---------------------------------------------------------------------------
    // Unicode classes (Bug 609710 port)
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Unicode character classes")
    internal inner class UnicodeClassTests {
        @Test
        @DisplayName("\\p{L}, \\p{Lu}, \\p{Ll} for ASCII uppercase")
        fun asciiUppercase() {
            Assertions.assertThat(Pattern.matches("\\p{L}", "A")).isTrue()
            Assertions.assertThat(Pattern.matches("\\p{Lu}", "A")).isTrue()
            Assertions.assertThat(Pattern.matches("\\p{Ll}", "A")).isFalse()
            Assertions.assertThat(Pattern.matches("\\P{L}", "A")).isFalse()
            Assertions.assertThat(Pattern.matches("\\P{Lu}", "A")).isFalse()
            Assertions.assertThat(Pattern.matches("\\P{Ll}", "A")).isTrue()
        }

        @Test
        @DisplayName("\\p{L} for CJK characters (Lo category)")
        fun cjkCharacters() {
            for (ch in arrayOf<String>("譚", "永", "鋒")) {
                Assertions.assertThat(Pattern.matches("\\p{L}", ch)).`as`("\\p{L} matches %s", ch).isTrue()
                Assertions.assertThat(Pattern.matches("\\p{Lu}", ch)).`as`("\\p{Lu} does not match %s", ch).isFalse()
                Assertions.assertThat(Pattern.matches("\\p{Ll}", ch)).`as`("\\p{Ll} does not match %s", ch).isFalse()
                Assertions.assertThat(Pattern.matches("\\P{L}", ch)).`as`("\\P{L} does not match %s", ch).isFalse()
                Assertions.assertThat(Pattern.matches("\\P{Lu}", ch)).`as`("\\P{Lu} matches %s", ch).isTrue()
                Assertions.assertThat(Pattern.matches("\\P{Ll}", ch)).`as`("\\P{Ll} matches %s", ch).isTrue()
            }
        }

        @Test
        @DisplayName("Partial match with Unicode and \\p{Lu}\\p{Lo}")
        fun partialMatchUnicode() {
            val str = "ABCDEFGHI譚永鋒"
            val pLazy = Pattern.compile("(.).*?(.).*?(.)")
            var m = pLazy.matcher(str)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("A")
            Assertions.assertThat(m.group(2)).isEqualTo("B")
            Assertions.assertThat(m.group(3)).isEqualTo("C")

            val pUniLetter = Pattern.compile("(.).*?([\\p{L}]).*?(.)")
            m = pUniLetter.matcher(str)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("A")
            Assertions.assertThat(m.group(2)).isEqualTo("B")
            Assertions.assertThat(m.group(3)).isEqualTo("C")

            Assertions.assertThat(Pattern.compile("\\P{L}").matcher(str).find())
                .`as`("No non-letter characters in the string")
                .isFalse()

            // Greedy .*  then Lu|Lo captures Chinese chars
            val pGreedyLu = Pattern.compile(".*(.).*?([\\p{Lu}\\p{Lo}]).*?(.)")
            m = pGreedyLu.matcher(str)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("譚")
            Assertions.assertThat(m.group(2)).isEqualTo("永")
            Assertions.assertThat(m.group(3)).isEqualTo("鋒")
        }
    }

    // ---------------------------------------------------------------------------
    // Complicated patterns and edge cases
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Complicated patterns and edge cases")
    internal inner class EdgeCaseTests {
        @Test
        @DisplayName("Full match with alternations and char classes")
        fun complicatedAlternation() {
            Assertions.assertThat(Pattern.matches("foo|bar|[A-Z]", "foo")).isTrue()
            Assertions.assertThat(Pattern.matches("foo|bar|[A-Z]", "bar")).isTrue()
            Assertions.assertThat(Pattern.matches("foo|bar|[A-Z]", "X")).isTrue()
            Assertions.assertThat(Pattern.matches("foo|bar|[A-Z]", "XY")).isFalse()
        }

        @Test
        @DisplayName("Full match with $ in alternation")
        fun fullMatchEnd() {
            Assertions.assertThat(Pattern.matches("fo|foo", "fo")).isTrue()
            Assertions.assertThat(Pattern.matches("fo|foo", "foo")).isTrue()
            Assertions.assertThat(Pattern.matches("fo|foo$", "fo")).isTrue()
            Assertions.assertThat(Pattern.matches("fo|foo$", "foo")).isTrue()
            Assertions.assertThat(Pattern.matches("foo$", "foo")).isTrue()
            Assertions.assertThat(Pattern.matches("fo|bar", "fox")).isFalse()
        }

        @Test
        @DisplayName("UTF-8 three-character string matches dot patterns")
        fun utf8ThreeChars() {
            // Three Japanese characters: 日本語
            val nihongo = "\u65E5\u672C\u8A9E"
            Assertions.assertThat(Pattern.matches("...", nihongo)).isTrue()
            // Each dot matches one code point
            val m = Pattern.compile("(.)").matcher(nihongo)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("\u65E5")
        }

        @Test
        @DisplayName("Case-insensitive match (OnePass case folding)")
        fun caseInsensitiveMatch() {
            // Port of CaseInsensitive.MatchAndConsume — validates OnePass case-folding.
            val p = Pattern.compile("(?i)([wand]{5})")
            val text = "A fish named *Wanda*"
            val m = p.matcher(text)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("Wanda")
        }

        @Test
        @DisplayName("Deep nesting doesn't crash")
        fun deepNesting() {
            // .{512}x should compile and match
            val p = Pattern.compile(".{512}x")
            val s = "c".repeat(515) + "x"
            Assertions.assertThat(p.matcher(s).find()).isTrue()
        }

        @Test
        @DisabledForCrosscheck("JDK stack overflows on this linear-time SafeRE stress case")
        @DisplayName("Long input with deep recursion pattern")
        fun longInputDeepRecursion() {
            // RE2 C++ test: ((?:\s|xx.*\n|x[*](?:\n|.)*?[*]x)*)
            val comment = "x*" + "a".repeat(8192) + "*x"
            val p = Pattern.compile("((?:\\s|xx.*\\n|x[*](?:\\n|.)*?[*]x)*)")
            Assertions.assertThat(p.matcher(comment).matches()).isTrue()
        }

        @Test
        @DisplayName("Big counted repetition")
        fun bigCountedRepetition() {
            val p = Pattern.compile("[a-z]{1000}x")
            val s = "a".repeat(1000) + "x"
            Assertions.assertThat(p.matcher(s).matches()).isTrue()
            Assertions.assertThat(p.matcher("a".repeat(999) + "x").matches()).isFalse()
        }

        @Test
        @DisplayName("Null vs empty string")
        fun nullVsEmptyString() {
            val p = Pattern.compile(".*")
            Assertions.assertThat(p.matcher("").matches()).isTrue()
        }
    }

    // ---------------------------------------------------------------------------
    // Bug regressions
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Bug regressions")
    internal inner class BugRegressions {
        @Test
        @DisplayName("Bug 3061120: Kelvin and long-s case folding")
        fun bug3061120KelvinCaseFold() {
            // (?i)\W should not match 'k' or 's' — they are word characters.
            // Bug was caused by Kelvin sign K (U+212A) and Latin long s ſ (U+017F).
            val p = Pattern.compile("(?i)\\W")
            Assertions.assertThat(p.matcher("x").find()).isFalse()
            Assertions.assertThat(p.matcher("k").find()).isFalse()
            Assertions.assertThat(p.matcher("s").find()).isFalse()
        }

        @Test
        @DisplayName("CL8622304: backslash in negated char class with capture")
        fun cl8622304BackslashCapture() {
            val m = Pattern.compile("([^\\\\])").matcher("D")
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("D")
        }

        @Test
        @DisplayName("CL8622304: complex pattern with escaped chars")
        fun cl8622304ComplexPattern() {
            val text = "bar:1,0x2F,030,4,5;baz:true;fooby:false,true"
            val p = Pattern.compile("(\\w+)(?::((?:[^;\\\\]|\\\\.)*))?;?")
            val m = p.matcher(text)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("bar")
            Assertions.assertThat(m.group(2)).isEqualTo("1,0x2F,030,4,5")
        }

        @Test
        @DisplayName("nullable alternation repeats match the empty branch first")
        fun nullableAlternationRepeatsMatchEmptyBranchFirst() {
            // RE2 regression issue 310.
            // (?:|a)* should match empty (first-match semantics).
            // Bug was that * matched more than + did.
            val star = Pattern.compile("(?:|a)*")
            val mStar = star.matcher("aaa")
            Assertions.assertThat(mStar.find()).isTrue()
            Assertions.assertThat(mStar.group()).isEmpty()

            val plus = Pattern.compile("(?:|a)+")
            val mPlus = plus.matcher("aaa")
            Assertions.assertThat(mPlus.find()).isTrue()
            Assertions.assertThat(mPlus.group()).isEmpty()
        }

        @Test
        @DisplayName("replaceAll advances correctly past non-ASCII characters")
        fun replaceAllAdvancesPastNonAsciiCharacters() {
            // RE2 regression issue 104.
            // Replacing with a* (matches empty too) should not clobber multibyte chars.
            val p = Pattern.compile("a*")
            val m = p.matcher("bc")
            Assertions.assertThat(m.replaceAll("d")).isEqualTo("dbdcd")

            // Unicode multibyte
            val p2 = Pattern.compile("\u0106*") // Ć*
            val m2 = p2.matcher("\u0105\u0107") // ąć
            Assertions.assertThat(m2.replaceAll("\u0108")).isEqualTo("\u0108\u0105\u0108\u0107\u0108") // ĈąĈćĈ

            // CJK characters
            val p3 = Pattern.compile("\u5927*") // 大*
            val m3 = p3.matcher("\u4EBA\u7C7B") // 人类
            Assertions.assertThat(m3.replaceAll("\u5C0F")).isEqualTo("\u5C0F\u4EBA\u5C0F\u7C7B\u5C0F") // 小人小类小
        }

        @Test
        @DisplayName("Bug 1816809: complex nested repetition with captures")
        fun bug1816809NestedRepetition() {
            val p = Pattern.compile("(((((llx((-3)|(4)))(;(llx((-3)|(4))))*))))")
            val m = p.matcher("llx-3;llx4")
            Assertions.assertThat(m.find()).isTrue()
        }

        @Test
        @DisplayName("b/529118477: ArrayIndexOutOfBoundsException in DFA computeNext")
        fun bug529118477DfaArrayIndexOutOfBounds() {
            val regex = "(\u0000A\u0000||(?Um)$|\uFFFF){741}^NA^ .?K"
            val input =
                ("\r"
                        + "A\u0000|(?Um)~\r"
                        + "A\u0000|(?Um)+?\u0002u\u0225C\uFFFF\uFFFF\uFFFF\uF007\u0183\u011A\u00A3###j")
            val pattern = Pattern.compile(regex)
            val matcher = pattern.matcher(input)
            while (matcher.find()) {
                matcher.group()
                for (i in 0..matcher.groupCount()) {
                    matcher.start(i)
                    matcher.end(i)
                }
            }
            matcher.matches()
            pattern.split(input)
        }
    }

    // ---------------------------------------------------------------------------
    // Rejection of invalid patterns
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Pattern rejection")
    internal inner class RejectionTests {
        @ParameterizedTest
        @ValueSource(
            strings = ["(a)\\1" // backreference
                , "a[x" // unclosed bracket
                , "a[z-a]" // invalid range
                , "a(b" // unclosed paren
                , "a\\" // trailing backslash
            ]
        )
        fun rejectsInvalidPatterns(pattern: String?) {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile(pattern) }).isInstanceOf(
                PatternSyntaxException::class.java
            )
        }

        @Test
        @DisplayName("No crash on invalid pattern match attempt")
        fun noCrashOnBadPattern() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("a\\") }).isInstanceOf(
                PatternSyntaxException::class.java
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Braces and special syntax
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Braces and special syntax")
    internal inner class BracesTests {
        @Test
        @DisplayName("Full match with braces")
        fun fullMatchBraces() {
            Assertions.assertThat(Pattern.matches("0[xX][0-9a-fA-F]+\\{?", "0x21")).isTrue()
            Assertions.assertThat(Pattern.matches("0[xX][0-9a-fA-F]+\\{?", "0x21{")).isTrue()
            Assertions.assertThat(Pattern.matches("0[xX][0-9a-fA-F]+\\}?", "0x21}")).isTrue()
        }
    }
}
