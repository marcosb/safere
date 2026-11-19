// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
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
import java.nio.charset.StandardCharsets
import java.util.regex.PatternSyntaxException
import java.util.stream.Stream
import kotlin.collections.mutableListOf
import kotlin.text.repeat
import kotlin.text.toByteArray
import kotlin.text.trim

/** Tests for [Pattern].  */
internal class PatternTest {
    private class LiteralCharSequence(private val value: String) : CharSequence {
        override val length: Int
            get() = value.length

        override fun get(index: Int): Char {
            return value.get(index)
        }

        override fun subSequence(start: Int, end: Int): CharSequence {
            return value.subSequence(start, end)
        }
    }

    @Nested
    @DisplayName("compile()")
    internal inner class Compile {
        @Test
        fun simplePattern() {
            val p = Pattern.compile("abc")
            Assertions.assertThat(p.pattern()).isEqualTo("abc")
        }

        @Test
        fun withQuantifiers() {
            val p = Pattern.compile("a+b*c?")
            Assertions.assertThat(p.pattern()).isEqualTo("a+b*c?")
        }

        @Test
        fun invalidPatternThrows() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("[unclosed") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun invalidPatternThrowsUnmatchedParen() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("(abc") }).isInstanceOf(
                PatternSyntaxException::class.java
            )
        }

        @Test
        fun emptyPattern() {
            val p = Pattern.compile("")
            Assertions.assertThat(p.pattern()).isEmpty()
            Assertions.assertThat(p.matcher("").matches()).isTrue()
            Assertions.assertThat(p.matcher("abc").find()).isTrue()
        }

        @Test
        @DisabledForCrosscheck("JDK stack overflows on this SafeRE stack-safety stress case")
        fun deeplyNestedTransparentGroupsRemainStackSafe() {
            val depth = 20000
            val regex = StringBuilder(depth * 3 + 4)
            for (i in 0..<depth) {
                regex.append("(?:")
            }
            regex.append("()")
            regex.append(")".repeat(depth))
            regex.append("*")

            Assertions.assertThatCode(ThrowingCallable { Pattern.compile(regex.toString()) }).doesNotThrowAnyException()
        }
    }

    @Nested
    @DisplayName("Unsupported features")
    internal inner class UnsupportedFeatures {
        @Test
        fun lookaheadRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("(?=a)") }).isInstanceOf(
                PatternSyntaxException::class.java
            )
        }

        @Test
        fun negativeLookaheadRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("(?!a)") }).isInstanceOf(
                PatternSyntaxException::class.java
            )
        }

        @Test
        fun lookbehindRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("(?<=a)") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun negativeLookbehindRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("(?<!a)") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun atomicGroupRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("(?>a)") }).isInstanceOf(
                PatternSyntaxException::class.java
            )
        }

        @Test
        fun backreferenceRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("(a)\\1") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun namedBackreferenceRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("(?<name>a)\\k<name>") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun namedBackreferenceWithoutGroupRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("\\k<name>") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun possessivePlusRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("a++") }).isInstanceOf(
                PatternSyntaxException::class.java
            )
        }

        @Test
        fun possessiveStarRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("a*+") }).isInstanceOf(
                PatternSyntaxException::class.java
            )
        }

        @Test
        fun possessiveQuestRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("a?+") }).isInstanceOf(
                PatternSyntaxException::class.java
            )
        }

        @Test
        fun possessiveBracedExactRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("a{2}+") }).isInstanceOf(
                PatternSyntaxException::class.java
            )
        }

        @Test
        fun possessiveBracedMinRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("a{2,}+") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun possessiveBracedRangeRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("a{2,5}+") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }
    }

    @Nested
    @DisplayName("compile(regex, flags)")
    internal inner class CompileWithFlags {
        @Test
        fun caseInsensitive() {
            val p = Pattern.compile("abc", Pattern.CASE_INSENSITIVE)
            Assertions.assertThat(p.flags()).isEqualTo(Pattern.CASE_INSENSITIVE)
            val m = p.matcher("ABC")
            Assertions.assertThat(m.matches()).isTrue()
        }

        @Test
        fun asciiCaseInsensitiveLiteralFastPathDoesNotUseUnicodeCaseFolding() {
            val p = Pattern.compile("(?i)i")

            Assertions.assertThat(p.matcher("I").matches()).isTrue()
            Assertions.assertThat(p.matcher("\u0130").matches()).isFalse()
            Assertions.assertThat(p.matcher("\u0131").matches()).isFalse()
        }

        @Test
        fun asciiCaseInsensitivePrefixAccelerationSkipsUnicodeCaseVariants() {
            val p = Pattern.compile("(?i)i.")
            val m = p.matcher("\u0130xix")

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(2)
            Assertions.assertThat(m.group()).isEqualTo("ix")
        }

        @Test
        fun dotall() {
            val p = Pattern.compile("a.b", Pattern.DOTALL)
            Assertions.assertThat(p.flags()).isEqualTo(Pattern.DOTALL)
            val m = p.matcher("a\nb")
            Assertions.assertThat(m.matches()).isTrue()
        }

        @Test
        fun dotallWithoutFlag() {
            val p = Pattern.compile("a.b")
            val m = p.matcher("a\nb")
            Assertions.assertThat(m.matches()).isFalse()
        }

        @Test
        fun literal() {
            val p = Pattern.compile("a.b", Pattern.LITERAL)
            Assertions.assertThat(p.matcher("a.b").matches()).isTrue()
            Assertions.assertThat(p.matcher("axb").matches()).isFalse()
        }

        @Test
        fun multipleFlags() {
            val p = Pattern.compile("abc", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
            Assertions.assertThat(p.flags()).isEqualTo(Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        }

        @Test
        fun canonEqAloneThrows() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("abc", java.util.regex.Pattern.CANON_EQ) })
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("CANON_EQ")
        }

        @Test
        fun canonEqCombinedWithSupportedFlagsThrows() {
            val flags = java.util.regex.Pattern.CANON_EQ or org.safere.Pattern.CASE_INSENSITIVE or org.safere.Pattern.DOTALL
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("abc", flags) })
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("CANON_EQ")
        }

        @Test
        fun unknownFlagBitsThrow() {
            // Bit 9 (0x200) is not assigned to any JDK flag.
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("abc", 0x200) })
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Unsupported")
        }

        @Test
        fun zeroFlags() {
            val p = Pattern.compile("abc", 0)
            Assertions.assertThat(p.flags()).isZero()
            Assertions.assertThat(p.matcher("abc").matches()).isTrue()
        }
    }

    @Nested
    @DisplayName("matches()")
    internal inner class StaticMatches {
        @Test
        fun matchesSuccess() {
            Assertions.assertThat(Pattern.matches("\\d+", "12345")).isTrue()
        }

        @Test
        fun matchesFailure() {
            Assertions.assertThat(Pattern.matches("\\d+", "abc")).isFalse()
        }

        @Test
        fun matchesPartialReturnsFalse() {
            Assertions.assertThat(Pattern.matches("\\d+", "123abc")).isFalse()
        }
    }

    @Nested
    @DisplayName("quote()")
    internal inner class Quote {
        @Test
        fun quotesMetacharacters() {
            val quoted = Pattern.quote("a.b+c*")
            Assertions.assertThat(quoted).isEqualTo("\\Qa.b+c*\\E")
            // The quoted pattern should match the literal string.
            Assertions.assertThat(Pattern.matches(quoted, "a.b+c*")).isTrue()
        }

        @Test
        fun quotesEmptyString() {
            val quoted = Pattern.quote("")
            Assertions.assertThat(quoted).isEqualTo("\\Q\\E")
        }

        @Test
        fun quotesStringWithBackslashE() {
            val quoted = Pattern.quote("a\\Eb")
            // Should handle the embedded \E properly.
            Assertions.assertThat(Pattern.matches(quoted, "a\\Eb")).isTrue()
        }

        @Test
        @DisplayName("\\Q...\\E works inside character classes")
        fun quoteInsideCharacterClass() {
            // \Q...\E inside [...] should add each char as a literal member of the class
            val p = Pattern.compile("[\\Qabc\\E]+")
            val m = p.matcher("xxxcbaxxx")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("cba")
        }

        @Test
        @DisplayName("\\Q...\\E with metacharacters inside character class")
        fun quoteMetacharsInsideCharacterClass() {
            // Pattern.quote produces \Q...\E; inside a char class it should still work
            val p =
                "[" + Pattern.quote("\\") + Pattern.quote("/") + Pattern.quote("*") + "]+"
            val pat = Pattern.compile(p)
            val m = pat.matcher("hello/world*foo\\bar")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("/")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("*")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("\\")
        }
    }

    @Nested
    @DisplayName("split()")
    internal inner class Split {
        @Test
        fun splitSimple() {
            val p = Pattern.compile(",")
            val parts = p.split("a,b,c")
            Assertions.assertThat<String?>(parts).containsExactly("a", "b", "c")
        }

        @Test
        fun splitWithLimit() {
            val p = Pattern.compile(",")
            val parts = p.split("a,b,c,d", 3)
            Assertions.assertThat<String?>(parts).containsExactly("a", "b", "c,d")
        }

        @Test
        fun splitTrailingEmpty() {
            val p = Pattern.compile(",")
            val parts = p.split("a,b,,")
            // limit=0: trailing empty strings are removed.
            Assertions.assertThat<String?>(parts).containsExactly("a", "b")
        }

        @Test
        fun splitNegativeLimit() {
            val p = Pattern.compile(",")
            val parts = p.split("a,b,,", -1)
            // Negative limit: trailing empty strings are retained.
            Assertions.assertThat<String?>(parts).containsExactly("a", "b", "", "")
        }

        @Test
        fun splitNoMatch() {
            val p = Pattern.compile(",")
            val parts = p.split("abc")
            Assertions.assertThat<String?>(parts).containsExactly("abc")
        }

        @Test
        fun splitLimit1() {
            val p = Pattern.compile(",")
            val parts = p.split("a,b,c", 1)
            Assertions.assertThat<String?>(parts).containsExactly("a,b,c")
        }

        @Test
        fun splitRegex() {
            val p = Pattern.compile("\\s+")
            val parts = p.split("hello   world  foo")
            Assertions.assertThat<String?>(parts).containsExactly("hello", "world", "foo")
        }

        @Test
        @DisplayName("split reads custom CharSequence content via charAt()")
        fun splitCustomCharSequence() {
            val p = Pattern.compile(",")
            val parts = p.split(LiteralCharSequence("a,b,c"))
            Assertions.assertThat<String?>(parts).containsExactly("a", "b", "c")
        }

        @Test
        @DisabledForCrosscheck("GB3 forbids boundaries inside CRLF; the JDK invents a break")
        @DisplayName("split cannot use a delimiter requiring a boundary inside CRLF")
        fun splitPreservesCrLfWhenDelimiterRequiresInternalGraphemeBoundary() {
            val p = Pattern.compile("\\r\\b{g}\\n")
            val input = "#\r\n\r\n$"
            Assertions.assertThat<String?>(p.split(input, -1)).containsExactly(input)
        }
    }

    @Nested
    @DisplayName("splitWithDelimiters()")
    internal inner class SplitWithDelimiters {
        @Test
        fun splitWithDelimitersSimple() {
            val p = Pattern.compile(",")
            val parts = p.splitWithDelimiters("a,b,c")
            Assertions.assertThat<String?>(parts).containsExactly("a", ",", "b", ",", "c")
        }

        @Test
        fun splitWithDelimitersRegex() {
            val p = Pattern.compile(":+")
            val parts = p.splitWithDelimiters("boo:::and::foo")
            Assertions.assertThat<String?>(parts).containsExactly("boo", ":::", "and", "::", "foo")
        }

        @Test
        fun splitWithDelimitersLimit2() {
            val p = Pattern.compile(":+")
            val parts = p.splitWithDelimiters("boo:::and::foo", 2)
            Assertions.assertThat<String?>(parts).containsExactly("boo", ":::", "and::foo")
        }

        @Test
        fun splitWithDelimitersLimit5() {
            val p = Pattern.compile(":+")
            val parts = p.splitWithDelimiters("boo:::and::foo", 5)
            Assertions.assertThat<String?>(parts).containsExactly("boo", ":::", "and", "::", "foo")
        }

        @Test
        fun splitWithDelimitersLargePositiveLimit() {
            val p = Pattern.compile(",")
            val parts = p.splitWithDelimiters("a,b", Int.MAX_VALUE)
            Assertions.assertThat<String?>(parts).containsExactly("a", ",", "b")
        }

        @Test
        fun splitWithDelimitersNegativeLimit() {
            val p = Pattern.compile(",")
            val parts = p.splitWithDelimiters("a,b,,", -1)
            Assertions.assertThat<String?>(parts).containsExactly("a", ",", "b", ",", "", ",", "")
        }

        @Test
        fun splitWithDelimitersTrailingEmpty() {
            val p = Pattern.compile(",")
            val parts = p.splitWithDelimiters("a,b,,")
            // limit=0: only the trailing empty substring is removed; delimiters are kept.
            Assertions.assertThat<String?>(parts).containsExactly("a", ",", "b", ",", "", ",")
        }

        @Test
        fun splitWithDelimitersNoMatch() {
            val p = Pattern.compile(",")
            val parts = p.splitWithDelimiters("abc")
            Assertions.assertThat<String?>(parts).containsExactly("abc")
        }

        @Test
        fun splitWithDelimitersLimit1() {
            val p = Pattern.compile(",")
            val parts = p.splitWithDelimiters("a,b,c", 1)
            Assertions.assertThat<String?>(parts).containsExactly("a,b,c")
        }

        @Test
        fun splitWithDelimitersWhitespace() {
            val p = Pattern.compile("\\s+")
            val parts = p.splitWithDelimiters("hello   world  foo")
            Assertions.assertThat<String?>(parts).containsExactly("hello", "   ", "world", "  ", "foo")
        }

        @Test
        fun splitWithDelimitersMatchAtStart() {
            // Positive-width match at position 0 produces a leading empty substring.
            val p = Pattern.compile(",")
            val parts = p.splitWithDelimiters(",a,b", -1)
            Assertions.assertThat<String?>(parts).containsExactly("", ",", "a", ",", "b")
        }

        @Test
        @DisplayName("splitWithDelimiters reads custom CharSequence content via charAt()")
        fun splitWithDelimitersCustomCharSequence() {
            val p = Pattern.compile(",")
            val parts = p.splitWithDelimiters(LiteralCharSequence("a,b"))
            Assertions.assertThat<String?>(parts).containsExactly("a", ",", "b")
        }

        @Test
        @DisabledForCrosscheck("GB3 forbids boundaries inside CRLF; the JDK invents a break")
        @DisplayName("splitWithDelimiters cannot use a delimiter requiring a boundary inside CRLF")
        fun splitWithDelimitersPreservesCrLfWhenDelimiterRequiresInternalGraphemeBoundary() {
            val p = Pattern.compile("\\r\\b{g}\\n")
            val input = "#\r\n\r\n$"
            Assertions.assertThat<String?>(p.splitWithDelimiters(input, -1)).containsExactly(input)
        }
    }

    @Nested
    @DisplayName("toString() / pattern() / flags()")
    internal inner class Accessors {
        @Test
        fun toStringReturnsPattern() {
            val p = Pattern.compile("hello")
            Assertions.assertThat(p.toString()).isEqualTo("hello")
        }

        @Test
        fun patternReturnsSource() {
            val p = Pattern.compile("hello")
            Assertions.assertThat(p.pattern()).isEqualTo("hello")
        }

        @Test
        fun flagsDefault() {
            val p = Pattern.compile("hello")
            Assertions.assertThat(p.flags()).isZero()
        }

        @Test
        fun flagsPreserved() {
            val flags = org.safere.Pattern.CASE_INSENSITIVE or org.safere.Pattern.MULTILINE
            val p = Pattern.compile("hello", flags)
            Assertions.assertThat(p.flags()).isEqualTo(flags)
        }

        @Test
        @DisplayName("UNICODE_CHARACTER_CLASS implies UNICODE_CASE in flags()")
        fun unicodeCharacterClassImpliesUnicodeCaseFlag() {
            val p = Pattern.compile("\\w+", org.safere.Pattern.UNICODE_CHARACTER_CLASS)

            Assertions.assertThat(p.flags())
                .isEqualTo(org.safere.Pattern.UNICODE_CHARACTER_CLASS or org.safere.Pattern.UNICODE_CASE)
        }

        @Test
        @DisplayName("namedGroups() returns named capturing groups")
        fun namedGroupsReturnsMap() {
            val p = Pattern.compile("(?<user>\\w+)@(?<host>\\w+)")
            Assertions.assertThat<String?, Int?>(p.namedGroups()).containsEntry("user", 1)
            Assertions.assertThat<String?, Int?>(p.namedGroups()).containsEntry("host", 2)
        }

        @Test
        @DisplayName("namedGroups() returns an empty map for no named groups")
        fun namedGroupsEmpty() {
            val p = Pattern.compile("(\\w+)@(\\w+)")
            Assertions.assertThat<String?, Int?>(p.namedGroups()).isEmpty()
        }

        @Test
        @DisplayName("namedGroups() returns an unmodifiable map")
        fun namedGroupsUnmodifiable() {
            val p = Pattern.compile("(?<user>\\w+)@(?<host>\\w+)")
            Assertions.assertThatThrownBy(
                    ThrowingCallable { (p.namedGroups() as MutableMap<String, Int>).put("foo", 99) })
                .isInstanceOf(UnsupportedOperationException::class.java)
        }

        @Test
        @DisplayName("Python-style named groups support (?P<name>expr) syntax with underscores")
        @DisabledForCrosscheck("SafeRE supports Python-style named capturing groups")
        fun pythonNamedGroups() {
            val p = Pattern.compile("(?P<user_name>[\\w.]+)@(?P<host_name>[\\w.]+)")
            Assertions.assertThat<String?, Int?>(p.namedGroups()).containsEntry("user_name", 1)
            Assertions.assertThat<String?, Int?>(p.namedGroups()).containsEntry("host_name", 2)
            val m = p.matcher("alice_smith@example.com")
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group("user_name")).isEqualTo("alice_smith")
            Assertions.assertThat(m.group("host_name")).isEqualTo("example.com")
        }

        @Test
        @DisplayName("Java-style named groups keep JDK name rules")
        fun javaStyleNamedGroupsRejectUnderscores() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("(?<user_name>a)") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }
    }

    @Nested
    @DisplayName("End-to-end")
    internal inner class EndToEnd {
        @Test
        fun emailExtraction() {
            val p = Pattern.compile("(\\w+)@(\\w+\\.\\w+)")
            val m = p.matcher("Contact: user@example.com for details")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("user@example.com")
            Assertions.assertThat(m.group(1)).isEqualTo("user")
            Assertions.assertThat(m.group(2)).isEqualTo("example.com")
        }

        @Test
        fun replaceAll() {
            val p = Pattern.compile("\\d+")
            val m = p.matcher("a1b22c333")
            Assertions.assertThat(m.replaceAll("N")).isEqualTo("aNbNcN")
        }

        @Test
        fun replaceAllLazyQuantifier() {
            val p = Pattern.compile("a+?")
            val m = p.matcher("aaa")
            while (m.find()) {
                System.err.printf("Found match at [%d, %d)\n", m.start(), m.end())
            }
            val res = p.matcher("aaa").replaceAll("X")
            Assertions.assertThat(res).isEqualTo("XXX")
        }

        @Test
        fun splitAndRejoin() {
            val p = Pattern.compile("-")
            val parts = p.split("2025-03-17")
            Assertions.assertThat<String?>(parts).containsExactly("2025", "03", "17")
            Assertions.assertThat(java.lang.String.join("/", *parts)).isEqualTo("2025/03/17")
        }

        @Test
        fun multilineFindAll() {
            val p = Pattern.compile("^\\w+", org.safere.Pattern.MULTILINE)
            val m = p.matcher("hello\nworld\nfoo")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("hello")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("world")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("foo")
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("MULTILINE ^|$ finds starts and ends for every line")
        fun multilineAnchorsFindAll() {
            // Regression for issue #42: $ before a newline must not be skipped when collecting
            // successive zero-width matches.
            val m = Pattern.compile("(?:^|$)", org.safere.Pattern.MULTILINE).matcher("aa\na")
            val matches: MutableList<IntArray?> = ArrayList<IntArray?>()
            while (m.find()) {
                matches.add(intArrayOf(m.start(), m.end()))
            }

            Assertions.assertThat<IntArray?>(matches).hasSize(4)
            Assertions.assertThat(matches.get(0)).containsExactly(0, 0)
            Assertions.assertThat(matches.get(1)).containsExactly(2, 2)
            Assertions.assertThat(matches.get(2)).containsExactly(3, 3)
            Assertions.assertThat(matches.get(3)).containsExactly(4, 4)
        }

        @Test
        @DisplayName("MULTILINE $|^ finds starts and ends for every line")
        fun multilineAnchorsFindAllReversedAlternation() {
            val m = Pattern.compile("(?:$|^)", org.safere.Pattern.MULTILINE).matcher("aa\na")
            val matches: MutableList<IntArray?> = ArrayList<IntArray?>()
            while (m.find()) {
                matches.add(intArrayOf(m.start(), m.end()))
            }

            Assertions.assertThat<IntArray?>(matches).hasSize(4)
            Assertions.assertThat(matches.get(0)).containsExactly(0, 0)
            Assertions.assertThat(matches.get(1)).containsExactly(2, 2)
            Assertions.assertThat(matches.get(2)).containsExactly(3, 3)
            Assertions.assertThat(matches.get(3)).containsExactly(4, 4)
        }

        @Test
        @DisplayName("MULTILINE ^|$ finds every line boundary in multi-line text")
        fun multilineAnchorsFindMultipleLines() {
            val m = Pattern.compile("(?:^|$)", org.safere.Pattern.MULTILINE).matcher("a\nb\nc")
            val matches: MutableList<IntArray?> = ArrayList<IntArray?>()
            while (m.find()) {
                matches.add(intArrayOf(m.start(), m.end()))
            }

            Assertions.assertThat<IntArray?>(matches).hasSize(6)
            Assertions.assertThat(matches.get(0)).containsExactly(0, 0)
            Assertions.assertThat(matches.get(1)).containsExactly(1, 1)
            Assertions.assertThat(matches.get(2)).containsExactly(2, 2)
            Assertions.assertThat(matches.get(3)).containsExactly(3, 3)
            Assertions.assertThat(matches.get(4)).containsExactly(4, 4)
            Assertions.assertThat(matches.get(5)).containsExactly(5, 5)
        }

        @Test
        @DisplayName("MULTILINE ^|$ first find() returns the start of input")
        fun multilineAnchorsFirstFind() {
            val m = Pattern.compile("(?:^|$)", org.safere.Pattern.MULTILINE).matcher("aa\na")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(0)
        }

        @Test
        @DisplayName("^.*$ with MULTILINE skips the final empty line after a trailing newline")
        fun multilineDotStarWithTrailingNewline() {
            val text = "\na\n"
            val safePattern = Pattern.compile("^.*$", org.safere.Pattern.MULTILINE)
            val jdkPattern =
                java.util.regex.Pattern.compile("^.*$", org.safere.Pattern.MULTILINE)

            val safeMatcher = safePattern.matcher(text)
            val jdkMatcher = jdkPattern.matcher(text)

            val safeMatches: MutableList<String> = ArrayList<String>()
            while (safeMatcher.find()) {
                safeMatches.add("[" + safeMatcher.start() + "," + safeMatcher.end() + ")")
            }

            val jdkMatches: MutableList<String> = ArrayList<String>()
            while (jdkMatcher.find()) {
                jdkMatches.add("[" + jdkMatcher.start() + "," + jdkMatcher.end() + ")")
            }

            Assertions.assertThat<String?>(safeMatches).isEqualTo(jdkMatches).containsExactly("[0,0)", "[1,2)")
        }

        @Test
        @DisplayName("MULTILINE ^ works on long text (DFA code path)")
        fun multilineBolLongText() {
            // Regression test: the DFA path didn't give \n its own equivalence class,
            // so ^ after \n was not detected on text longer than the OnePass threshold.
            val sb = StringBuilder()
            sb.append("header line\n")
            for (i in 0..199) {
                sb.append("some padding line ").append(i).append("\n")
            }
            sb.append("import foo\n")
            sb.append("import bar\n")
            val text = sb.toString()

            val p = Pattern.compile("^import", org.safere.Pattern.MULTILINE)
            val m = p.matcher(text)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("import")
            val first = m.start()
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("import")
            Assertions.assertThat(m.start()).isGreaterThan(first)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("$ matches before \\r\\n in MULTILINE mode")
        fun multilineEolCrLf() {
            // Regression: $ in MULTILINE only matched before \n, not before \r\n.
            val p = Pattern.compile("^abc$", org.safere.Pattern.MULTILINE)
            Assertions.assertThat(p.matcher("abc\r\ndef").find()).isTrue()
            Assertions.assertThat(p.matcher("abc\ndef").find()).isTrue()
        }

        @Test
        @DisplayName("MULTILINE + CASE_INSENSITIVE with \\r\\n line endings (WebSocket header)")
        fun multilineCaseInsensitiveCrLf() {
            val header = "GET / HTTP/1.1\r\nSec-WebSocket-Key: abc123\r\n\r\n"
            val p =
                Pattern.compile(
                    "^sec-websocket-key:(.*)$",
                    org.safere.Pattern.MULTILINE or org.safere.Pattern.CASE_INSENSITIVE
                )
            val m = p.matcher(header)
            Assertions.assertThat(m.find()).isTrue()
            // . now excludes all line terminators including \r (matching JDK behavior).
            Assertions.assertThat(m.group(1)!!.trim { it <= ' ' }).isEqualTo("abc123")
        }

        @Test
        @DisplayName(". does not match \\r (issue #54)")
        fun dotDoesNotMatchCr() {
            // Regression for #54: . should not match \r, matching JDK behavior.
            val p = Pattern.compile(".")
            val m = p.matcher("a\rb")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("a")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("b")
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("$ matches before \\r in MULTILINE mode (issue #54)")
        fun multilineEolStandaloneCr() {
            // Regression for #54: $ in MULTILINE should match before standalone \r.
            val p = Pattern.compile("$", org.safere.Pattern.MULTILINE)
            val m = p.matcher("a\rb")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(1)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(3)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("^ matches after \\r in MULTILINE mode (issue #54)")
        fun multilineBolStandaloneCr() {
            // Regression for #54: ^ in MULTILINE should match after standalone \r.
            val p = Pattern.compile("^", org.safere.Pattern.MULTILINE)
            val m = p.matcher("a\rb")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(2)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("UNIX_LINES restores \\n-only line terminator behavior (issue #54)")
        fun unixLinesFlag() {
            // With UNIX_LINES, only \n is a line terminator — \r is treated as ordinary char.
            val dotPat = Pattern.compile(".", org.safere.Pattern.UNIX_LINES)
            val dm = dotPat.matcher("a\rb")
            Assertions.assertThat(dm.find()).isTrue()
            Assertions.assertThat(dm.group()).isEqualTo("a")
            Assertions.assertThat(dm.find()).isTrue()
            Assertions.assertThat(dm.group()).isEqualTo("\r")
            Assertions.assertThat(dm.find()).isTrue()
            Assertions.assertThat(dm.group()).isEqualTo("b")

            val dollarPat =
                Pattern.compile("$", org.safere.Pattern.MULTILINE or org.safere.Pattern.UNIX_LINES)
            val mm = dollarPat.matcher("a\rb")
            // $ should only match at end of text, not before \r
            Assertions.assertThat(mm.find()).isTrue()
            Assertions.assertThat(mm.start()).isEqualTo(3)
            Assertions.assertThat(mm.find()).isFalse()
        }

        @Test
        @DisplayName("MULTILINE $ in long text: DFA transition caching must defer END_LINE")
        fun multilineDollarDfaCaching() {
            // Regression: the DFA caches transitions per (state, equivalence-class). END_LINE
            // depends on the character at the NEXT position, which varies for the same
            // (state, class). When a character class was consumed at a position where the
            // following char was NOT '\n', the cached transition lacked END_LINE, so a later
            // encounter at a position followed by '\n' missed the $ match.
            //
            // This only manifests when the text is long enough (>256 chars) to bypass OnePass.
            val p = Pattern.compile("^password: (.*)$", org.safere.Pattern.MULTILINE)

            // Short text (uses OnePass, was not affected): sanity check.
            val mShort = p.matcher("line1\npassword: abc\nline3\n")
            Assertions.assertThat(mShort.find()).isTrue()
            Assertions.assertThat(mShort.group(1)).isEqualTo("abc")

            // Long text (>256 chars, uses DFA): this was the failing case.
            val longText = "x".repeat(240) + "\npassword: secret-uuid\nsuffix\n"
            val mLong = p.matcher(longText)
            Assertions.assertThat(mLong.find()).isTrue()
            Assertions.assertThat(mLong.group(1)).isEqualTo("secret-uuid")

            // Simulated Spring Boot log output with password line buried in long text.
            val logOutput =
                ("01:04:45.416 [Test worker] INFO org.boot.TomcatWebServer"
                        + " -- Tomcat initialized\n"
                        + "Mar 29, 2026 1:04:45 AM org.apache.coyote.AbstractProtocol init\n"
                        + "INFO: Initializing ProtocolHandler [\"http-nio-auto-1\"]\n"
                        + "01:04:45.998 [Test worker] WARN ... -- \n"
                        + "\n"
                        + "Using generated security password: 2d27188d-396d-49bb-b1da-90a58fa94f61\n"
                        + "\n"
                        + "This generated password is for development use only.\n"
                        + "01:04:46.031 [Test worker] INFO ... AuthenticationManager configured\n"
                        + "01:04:46.162 [Test worker] INFO ... -- Tomcat started on port 40033\n")
            val pwPattern =
                Pattern.compile("^Using generated security password: (.*)$", org.safere.Pattern.MULTILINE)
            val mLog = pwPattern.matcher(logOutput)
            Assertions.assertThat(mLog.find()).isTrue()
            Assertions.assertThat(mLog.group(1)).isEqualTo("2d27188d-396d-49bb-b1da-90a58fa94f61")
        }
    }

    // -----------------------------------------------------------------------
    // Tests ported from RE2/J (P1 tests)
    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("Compile validation (ported from RE2/J RE2CompileTest)")
    internal inner class CompileValidation {
        /** Patterns that must compile successfully.  */
        @ParameterizedTest(name = "compile(\"{0}\") succeeds")
        @ValueSource(
            strings = ["", ".", "^.$", "a", "a*", "a+", "a?", "a|b", "a*|b*", "(a*|b)(c*|d)", "[a-z]", "[a-abc-c\\-\\]\\[]", "[a-z]+", "[abc]", "[^1234]", "[^\n]", "..|.#|..", "\\!\\\\", "abc]", "a??"
            ]
        )
        fun validPatternsCompile(pattern: String?) {
            Pattern.compile(pattern) // should not throw
        }

        /** Patterns that must fail to compile.  */
        @ParameterizedTest(name = "compile(\"{0}\") throws")
        @ValueSource(strings = ["*", "+", "?", "(abc", "x[a-z", "[z-a]", "abc\\"])
        fun invalidPatternsThrow(pattern: String?) {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile(pattern) }).isInstanceOf(
                PatternSyntaxException::class.java
            )
        }

        @Test
        @DisplayName("duplicate named capturing groups are rejected")
        fun duplicateNamedGroupsRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("(?<word>a)(?<word>b)") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }
    }

    @Nested
    @DisplayName("quote() round-trip (ported from RE2/J RE2QuoteMetaTest)")
    internal class QuoteRoundTrip {
        @ParameterizedTest(name = "quote(\"{0}\") replaceAll round-trip")
        @MethodSource("quoteMetaCases")
        fun quoteAndReplace(metachar: String, source: String?, expected: String?) {
            val quoted = Pattern.quote(metachar)
            val p = Pattern.compile(quoted)
            val replaced = p.matcher(source).replaceAll("xyz")
            Assertions.assertThat(replaced).isEqualTo(expected)
        }

        companion object {
            @JvmStatic
            fun quoteMetaCases(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of("foo", "abcfoodef", "abcxyzdef"),
                    Arguments.of("foo.$", "abcfoo.\$def", "abcxyzdef"),
                    Arguments.of(
                        "!@#$%^&*()_+-=[{]}\\|,<.>/?~", "abc!@#$%^&*()_+-=[{]}\\|,<.>/?~def", "abcxyzdef"
                    )
                )
            }
        }
    }

    @Nested
    @DisplayName("nested character classes")
    internal inner class NestedCharClassTests {
        @Test
        @DisplayName("[[A-F]] matches same as [A-F] (Java-style union)")
        fun nestedCharClassUnion() {
            val p = Pattern.compile("[[A-F]]")
            Assertions.assertThat(p.matcher("A").find()).isTrue()
            Assertions.assertThat(p.matcher("C").find()).isTrue()
            Assertions.assertThat(p.matcher("G").find()).isFalse()
        }

        @Test
        @DisplayName("[[A-Fa-f0-9]]{32,} matches hex strings (Docker digest pattern)")
        fun nestedCharClassHexDigest() {
            val p = Pattern.compile("[[A-Fa-f0-9]]{32,}")
            val m = p.matcher("6e9f67fa63b0323e9a1e587fd71c561ba48a034504fb804fd26fd8800039835d")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group())
                .isEqualTo("6e9f67fa63b0323e9a1e587fd71c561ba48a034504fb804fd26fd8800039835d")
        }

        @Test
        @DisplayName("[a-z[0-9]] matches both ranges")
        fun nestedCharClassMixedRanges() {
            val p = Pattern.compile("[a-z[0-9]]")
            Assertions.assertThat(p.matcher("m").find()).isTrue()
            Assertions.assertThat(p.matcher("5").find()).isTrue()
            Assertions.assertThat(p.matcher("A").find()).isFalse()
        }

        @Test
        @DisplayName("deeply nested [[[[a-z]]]] works")
        fun deeplyNestedCharClass() {
            val p = Pattern.compile("[[[[a-z]]]]")
            Assertions.assertThat(p.matcher("m").find()).isTrue()
            Assertions.assertThat(p.matcher("A").find()).isFalse()
        }

        @Test
        @DisplayName("[a-z&&] is rejected because intersection requires a right operand")
        fun trailingIntersectionIsRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("[a-z&&]") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        @DisplayName("[^a&&[ab]] applies intersection before negating the left side")
        fun negatedIntersectionKeepsNegatedLeftHandClass() {
            val p = Pattern.compile("[^a&&[ab]]")
            Assertions.assertThat(p.matcher("a").matches()).isFalse()
            Assertions.assertThat(p.matcher("b").matches()).isTrue()
            Assertions.assertThat(p.matcher("z").matches()).isTrue()
        }

        @Test
        @DisplayName("[a-z&&[def]1] intersects with the whole right-hand union")
        fun intersectionRightHandSideIncludesRangesAfterNestedClass() {
            val p = Pattern.compile("[a-z&&[def]1]")
            Assertions.assertThat(p.matcher("d").matches()).isTrue()
            Assertions.assertThat(p.matcher("f").matches()).isTrue()
            Assertions.assertThat(p.matcher("1").matches()).isFalse()
        }

        @Test
        @DisplayName("[^[A-F]] negates the union")
        fun negatedNestedCharClass() {
            val p = Pattern.compile("[^[A-F]]")
            Assertions.assertThat(p.matcher("A").find()).isFalse()
            Assertions.assertThat(p.matcher("G").find()).isTrue()
        }
    }

    @Nested
    @DisplayName("octal escapes")
    internal inner class OctalEscapeTests {
        @Test
        @DisplayName("\\0 without octal digits is rejected")
        fun zeroOctalEscapeRequiresDigits() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("\\0") }).isInstanceOf(
                PatternSyntaxException::class.java
            )
        }

        @Test
        @DisplayName("\\08 is rejected because \\0 requires an octal digit")
        fun zeroOctalEscapeRejectsNonOctalDigit() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("\\08") }).isInstanceOf(
                PatternSyntaxException::class.java
            )
        }

        @ParameterizedTest
        @ValueSource(strings = ["\\400", "\\777", "\\2222*", "3*\\2222*"])
        @DisplayName("non-zero numeric escapes are rejected as backreferences")
        fun nonZeroNumericEscapesRejectedAsBackreferences(regex: String?) {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile(regex) }).isInstanceOf(
                PatternSyntaxException::class.java
            )
        }
    }

    @Nested
    @DisplayName("compiler budget")
    internal inner class CompilerBudgetTests {
        @Test
        @DisplayName("large counted nullable subexpressions compile like JDK")
        fun largeCountedNullableSubexpressionsCompileLikeJdk() {
            val regex = "(?:" + "a?".repeat(120) + "){900}"

            Assertions.assertThatCode(ThrowingCallable { java.util.regex.Pattern.compile(regex) }).doesNotThrowAnyException()
            Assertions.assertThatCode(ThrowingCallable { Pattern.compile(regex) }).doesNotThrowAnyException()
        }
    }

    @Nested
    @DisplayName("disjoint reject prefilter")
    internal inner class DisjointRejectPrefilterTests {
        @Test
        @DisplayName("rejects search when none of the disjoint required literals are present")
        fun rejectsWhenNoDisjointLiteralsPresent() {
            val p = Pattern.compile(".*(?:apple|banana|cherry).*")
            val m = p.matcher("grapefruit orange peach plum lemon")
            Assertions.assertThat(m.find()).isFalse()

            val input = Utf8Input.trusted("grapefruit orange peach plum lemon".toByteArray(StandardCharsets.UTF_8))
            Assertions.assertThat(p.find(input)).isFalse()
        }

        @Test
        @DisplayName("matches when at least one disjoint required literal is present")
        fun matchesWhenOneDisjointLiteralIsPresent() {
            val p = Pattern.compile(".*(?:apple|banana|cherry).*")
            val m = p.matcher("grapefruit banana peach pineapple lemon")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("grapefruit banana peach pineapple lemon")

            val input =
                Utf8Input.trusted("grapefruit banana peach pineapple lemon".toByteArray(StandardCharsets.UTF_8))
            Assertions.assertThat(p.find(input)).isTrue()
        }

        @Test
        @DisplayName("correctly handles word boundaries and alternations")
        fun wordBoundaryDisjointAlternation() {
            val p = Pattern.compile("(?:\\bapple\\b|\\bbanana\\b)")
            val m = p.matcher("there is an apple here")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("apple")

            val m2 = p.matcher("there is a pineapple here")
            Assertions.assertThat(m2.find()).isFalse()
        }

        @Test
        @DisplayName("subsumed alternations correctly reject when shortest required literal is missing")
        fun subsumedAlternationRejectionAndMatching() {
            val p = Pattern.compile(".*(?:apple|pineapple|banana).*")
            val m = p.matcher("grapefruit orange peach plum lemon")
            Assertions.assertThat(m.find()).isFalse()

            val m2 = p.matcher("there is a pineapple here")
            Assertions.assertThat(m2.find()).isTrue()
            Assertions.assertThat(m2.group()).isEqualTo("there is a pineapple here")
        }

        @Test
        @DisplayName("supplementary literals are not subsumed by UTF-16 surrogate fragments")
        fun supplementaryLiteralIsNotPrunedBySurrogateFragment() {
            val p = Pattern.compile("(?:a\uD83D|za\uD83D\uDE00|banana)")
            val input = Utf8Input.trusted("za\uD83D\uDE00".toByteArray(StandardCharsets.UTF_8))

            Assertions.assertThat(p.find(input)).isTrue()
        }
    }

    @Nested
    @DisplayName("end-anchored suffix reject prefilter")
    internal inner class EndAnchoredSuffixTests {
        @Test
        @DisplayName("matches and find reject inputs not ending with suffix")
        fun endAnchoredSuffixRejectsMismatchedInputs() {
            val p = Pattern.compile(".*\\.json$")

            Assertions.assertThat(p.matcher("config.json").matches()).isTrue()
            Assertions.assertThat(p.matcher("config.json").find()).isTrue()

            Assertions.assertThat(p.matcher("config.yaml").matches()).isFalse()
            Assertions.assertThat(p.matcher("config.yaml").find()).isFalse()

            Assertions.assertThat(p.matcher(".json").matches()).isTrue()
            Assertions.assertThat(p.matcher(".jso").matches()).isFalse()

            // Utf8Input
            Assertions.assertThat(p.find(Utf8Input.trusted("config.json".toByteArray(StandardCharsets.UTF_8)))).isTrue()
            Assertions.assertThat(p.find(Utf8Input.trusted("config.yaml".toByteArray(StandardCharsets.UTF_8))))
                .isFalse()
        }

        @Test
        @DisplayName("case-insensitive end-anchored suffix rejects mismatched inputs")
        fun caseInsensitiveEndAnchoredSuffixRejectsMismatchedInputs() {
            for (regex in mutableListOf<String>("(?i).*\\.json$", ".*(?i:\\.json)$", "(?i).*\\.json\\z")) {
                val p = Pattern.compile(regex)

                Assertions.assertThat(p.matcher("config.json").matches()).isTrue()
                Assertions.assertThat(p.matcher("config.JSON").matches()).isTrue()
                Assertions.assertThat(p.matcher("config.JsOn").find()).isTrue()

                Assertions.assertThat(p.matcher("config.yaml").matches()).isFalse()
                Assertions.assertThat(p.matcher("config.YAML").find()).isFalse()

                // Utf8Input
                Assertions.assertThat(p.find(Utf8Input.trusted("config.JSON".toByteArray(StandardCharsets.UTF_8))))
                    .isTrue()
                Assertions.assertThat(p.find(Utf8Input.trusted("config.YAML".toByteArray(StandardCharsets.UTF_8))))
                    .isFalse()
            }
        }

        @Test
        @DisplayName("dollar and \\Z anchors match all supported trailing line terminators")
        fun dollarAndZAnchorsMatchAllTrailingLineTerminators() {
            for (regex in mutableListOf<String>(".*\\.json$", ".*\\.json\\Z")) {
                val p = Pattern.compile(regex)
                val terminators = arrayOf<String?>("\n", "\r\n", "\r", "\u0085", "\u2028", "\u2029")
                for (term in terminators) {
                    val input = "config.json" + term
                    Assertions.assertThat(p.matcher(input).find()).isTrue()
                    Assertions.assertThat(p.matcher(input).matches()).isFalse()
                    Assertions.assertThat(p.find(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))).isTrue()
                }
                Assertions.assertThat(p.matcher("config.yaml\u0085").find()).isFalse()
                Assertions.assertThat(p.find(Utf8Input.trusted("config.yaml\u0085".toByteArray(StandardCharsets.UTF_8))))
                    .isFalse()
            }
        }

        @Test
        @DisplayName("strict \\z anchor does not match before trailing line terminators")
        fun strictEndAnchorDoesNotMatchTrailingLineTerminators() {
            val p = Pattern.compile(".*\\.json\\z")

            Assertions.assertThat(p.matcher("config.json").find()).isTrue()
            Assertions.assertThat(p.matcher("config.json").matches()).isTrue()
            Assertions.assertThat(p.find(Utf8Input.trusted("config.json".toByteArray(StandardCharsets.UTF_8)))).isTrue()

            val terminators = arrayOf<String?>("\n", "\r\n", "\r", "\u0085", "\u2028", "\u2029")
            for (term in terminators) {
                val input = "config.json" + term
                Assertions.assertThat(p.matcher(input).find()).isFalse()
                Assertions.assertThat(p.matcher(input).matches()).isFalse()
                Assertions.assertThat(p.find(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))).isFalse()
            }
        }

        @Test
        @DisplayName("UNIX_LINES mode only allows trailing newline for dollar anchor")
        fun unixLinesModeOnlyAllowsTrailingNewline() {
            val p = Pattern.compile(".*\\.json$", org.safere.Pattern.UNIX_LINES)

            Assertions.assertThat(p.matcher("config.json\n").find()).isTrue()
            Assertions.assertThat(p.matcher("config.json\n").matches()).isFalse()
            Assertions.assertThat(p.find(Utf8Input.trusted("config.json\n".toByteArray(StandardCharsets.UTF_8))))
                .isTrue()

            val nonUnixTerminators = arrayOf<String?>("\r", "\r\n", "\u0085", "\u2028", "\u2029")
            for (term in nonUnixTerminators) {
                val input = "config.json" + term
                Assertions.assertThat(p.matcher(input).find()).isFalse()
                Assertions.assertThat(p.matcher(input).matches()).isFalse()
                Assertions.assertThat(p.find(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))).isFalse()
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029"])
        @DisplayName("end anchors recognize every final line terminator")
        fun endAnchorsRecognizeEveryFinalLineTerminator(terminator: String) {
            val input = "config.json" + terminator
            val utf8 = Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8))

            for (anchor in mutableListOf<String>("$", "\\Z")) {
                val suffix = Pattern.compile(".*\\.json" + anchor)
                val characterClass = Pattern.compile(".*[0-9]" + anchor)

                Assertions.assertThat(suffix.matcher(input).find()).isTrue()
                Assertions.assertThat(suffix.find(utf8)).isTrue()
                Assertions.assertThat(characterClass.matcher("item3" + terminator).find()).isTrue()
                Assertions.assertThat(
                    characterClass.find(
                        Utf8Input.trusted(
                            ("item3" + terminator).toByteArray(
                                StandardCharsets.UTF_8
                            )
                        )
                    )
                )
                    .isTrue()
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["\r", "\r\n", "\u0085", "\u2028", "\u2029"])
        @DisplayName("UNIX_LINES restricts final line terminators to newline")
        fun unixLinesRestrictsFinalLineTerminatorsToNewline(terminator: String) {
            val input = "config.json" + terminator
            val utf8 = Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8))

            for (anchor in mutableListOf<String>("$", "\\Z")) {
                val suffix = Pattern.compile(".*\\.json" + anchor, org.safere.Pattern.UNIX_LINES)

                Assertions.assertThat(suffix.matcher(input).find()).isFalse()
                Assertions.assertThat(suffix.find(utf8)).isFalse()
            }
        }

        @Test
        @DisplayName("end-anchored character class rejects inputs not ending with class")
        fun endAnchoredCharClassRejectsMismatchedInputs() {
            for (regex in mutableListOf<String>(".*[0-9]$", ".*[0-9]\\Z")) {
                val p = Pattern.compile(regex)

                Assertions.assertThat(p.matcher("item123").find()).isTrue()
                Assertions.assertThat(p.matcher("item123").matches()).isTrue()

                val terminators = arrayOf<String?>("\n", "\r\n", "\r", "\u0085", "\u2028", "\u2029")
                for (term in terminators) {
                    val input = "item123" + term
                    Assertions.assertThat(p.matcher(input).find()).isTrue()
                    Assertions.assertThat(p.matcher(input).matches()).isFalse()
                    Assertions.assertThat(p.find(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))).isTrue()
                }

                Assertions.assertThat(p.matcher("item123abc").find()).isFalse()
                Assertions.assertThat(p.matcher("item123abc").matches()).isFalse()
                Assertions.assertThat(p.matcher("item123abc\n").find()).isFalse()
                Assertions.assertThat(p.matcher("item123abc\u0085").find()).isFalse()

                Assertions.assertThat(p.matcher("").find()).isFalse()
                Assertions.assertThat(p.matcher("\n").find()).isFalse()
                Assertions.assertThat(p.matcher("\u0085").find()).isFalse()

                // Utf8Input
                Assertions.assertThat(p.find(Utf8Input.trusted("item123".toByteArray(StandardCharsets.UTF_8)))).isTrue()
                Assertions.assertThat(p.find(Utf8Input.trusted("item123abc".toByteArray(StandardCharsets.UTF_8))))
                    .isFalse()
            }
        }

        @Test
        @DisplayName("end-anchored character class with strict \\z and UNIX_LINES")
        fun endAnchoredCharClassStrictAndUnixLines() {
            val strict = Pattern.compile(".*[0-9]\\z")
            Assertions.assertThat(strict.matcher("item123").find()).isTrue()
            Assertions.assertThat(strict.matcher("item123\n").find()).isFalse()
            Assertions.assertThat(strict.matcher("item123\u0085").find()).isFalse()

            val unix = Pattern.compile(".*[0-9]$", org.safere.Pattern.UNIX_LINES)
            Assertions.assertThat(unix.matcher("item123\n").find()).isTrue()
            Assertions.assertThat(unix.matcher("item123\r").find()).isFalse()
            Assertions.assertThat(unix.matcher("item123\u0085").find()).isFalse()
        }
    }

    @Nested
    @DisplayName("start-anchored prefix and char class fast paths")
    internal inner class StartAnchoredPrefixTests {
        @ParameterizedTest
        @ValueSource(strings = ["^target", "\\Atarget"])
        @DisplayName("start-anchored literal rejects a later UTF-8 occurrence")
        fun startAnchoredLiteralRejectsLaterUtf8Occurrence(regex: String?) {
            val pattern = Pattern.compile(regex)

            Assertions.assertThat(pattern.find(Utf8Input.trusted("prefix target".toByteArray(StandardCharsets.UTF_8))))
                .isFalse()
            Assertions.assertThat(pattern.find(Utf8Input.trusted("target suffix".toByteArray(StandardCharsets.UTF_8))))
                .isTrue()
        }

        @Test
        @DisplayName("start-anchored character class rejects empty UTF-8 input safely")
        fun startAnchoredCharacterClassRejectsEmptyUtf8InputSafely() {
            val pattern = Pattern.compile("^[0-9]+")
            val empty = Utf8Input.trusted(ByteArray(0))

            Assertions.assertThat(pattern.find(empty)).isFalse()
            Assertions.assertThat(pattern.matcher(empty).find()).isFalse()
            Assertions.assertThat(pattern.matcher(empty).matches()).isFalse()
            Assertions.assertThat(pattern.matcher(empty).lookingAt()).isFalse()
        }

        @Test
        @DisplayName("start-anchored literal prefix rejects mismatched inputs in find, matches, lookingAt")
        fun startAnchoredLiteralPrefixRejectsMismatchedInputs() {
            val p = Pattern.compile("^https://.*")

            Assertions.assertThat(p.matcher("https://example.com").find()).isTrue()
            Assertions.assertThat(p.matcher("https://example.com").matches()).isTrue()
            Assertions.assertThat(p.matcher("https://example.com").lookingAt()).isTrue()

            Assertions.assertThat(p.matcher("http://example.com").find()).isFalse()
            Assertions.assertThat(p.matcher("http://example.com").matches()).isFalse()
            Assertions.assertThat(p.matcher("http://example.com").lookingAt()).isFalse()

            Assertions.assertThat(p.matcher("prefix https://example.com").find()).isFalse()

            // Utf8Input
            val matchUtf8 = "https://example.com".toByteArray(StandardCharsets.UTF_8)
            val noMatchUtf8 = "http://example.com".toByteArray(StandardCharsets.UTF_8)
            val lateMatchUtf8 = "prefix https://example.com".toByteArray(StandardCharsets.UTF_8)

            Assertions.assertThat(p.find(Utf8Input.trusted(matchUtf8))).isTrue()
            Assertions.assertThat(p.find(Utf8Input.trusted(noMatchUtf8))).isFalse()
            Assertions.assertThat(p.find(Utf8Input.trusted(lateMatchUtf8))).isFalse()
        }

        @Test
        @DisplayName("start-anchored character class rejects mismatched first character")
        fun startAnchoredCharClassRejectsMismatchedFirstChar() {
            val p = Pattern.compile("^[0-9]+[a-z]+")

            Assertions.assertThat(p.matcher("123abc").find()).isTrue()
            Assertions.assertThat(p.matcher("123abc").matches()).isTrue()
            Assertions.assertThat(p.matcher("123abc").lookingAt()).isTrue()

            Assertions.assertThat(p.matcher("abc123").find()).isFalse()
            Assertions.assertThat(p.matcher("abc123").matches()).isFalse()
            Assertions.assertThat(p.matcher("abc123").lookingAt()).isFalse()

            Assertions.assertThat(p.matcher("").find()).isFalse()
            Assertions.assertThat(p.matcher("").matches()).isFalse()
            Assertions.assertThat(p.matcher("").lookingAt()).isFalse()

            // Utf8Input
            val matchUtf8 = "123abc".toByteArray(StandardCharsets.UTF_8)
            val noMatchUtf8 = "abc123".toByteArray(StandardCharsets.UTF_8)

            Assertions.assertThat(p.find(Utf8Input.trusted(matchUtf8))).isTrue()
            Assertions.assertThat(p.find(Utf8Input.trusted(noMatchUtf8))).isFalse()
        }
    }

    @Test
    @DisplayName("find(Utf8Input) agrees with the JDK for patterns its forward DFA can't answer")
    fun utf8FindWithoutForwardDfaAgreesWithJdk() {
        val prose = "fine food offer ".repeat(200)
        for (regex in mutableListOf<String>( // End anchors.
            "(foo|fax)$",
            "(?i)\\s*(foo|fax)$",
            "\\s*(foo|fax)\\z",
            "[a-z]+ (foo|fax)$",
            "o$",  // Loop registers (a nullable repeated body).
            "(x?)+(foo|fax)",
            "(a?)+fax",  // Grapheme semantics.
            "\\X(foo|fax)"
        )) {
            for (text in java.util.List.of<String>(
                "",
                "foo",
                prose,
                prose + "foo",
                prose + "FAX",
                prose + "fax\n",
                "foo" + prose
            )) {
                val expected = java.util.regex.Pattern.compile(regex).matcher(text).find()
                Assertions.assertThat(
                    Pattern.compile(regex).find(
                        Utf8Input.trusted(
                            text.toByteArray(
                                StandardCharsets.UTF_8
                            )
                        )
                    )
                )
                    .`as`("%s on a %s-char input", regex, text.length)
                    .isEqualTo(expected)
            }
        }
    }
}
