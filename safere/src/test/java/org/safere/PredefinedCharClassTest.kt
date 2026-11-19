// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for predefined character classes `\h`, `\H`, `\v`, `\V`.
 * 
 * 
 * These correspond to the JDK's horizontal and vertical whitespace character classes as
 * documented in [Pattern].
 */
@DisplayName("Predefined character classes: \\h, \\H, \\v, \\V")
internal class PredefinedCharClassTest {
    // --- Horizontal whitespace code points (JDK definition of \h) ---
    private val HORIZ_SPACE_CODEPOINTS = intArrayOf(
        0x09,  // CHARACTER TABULATION (tab)
        0x20,  // SPACE
        0xA0,  // NO-BREAK SPACE
        0x1680,  // OGHAM SPACE MARK
        0x180E,  // MONGOLIAN VOWEL SEPARATOR
        0x2000,  // EN QUAD
        0x2001,  // EM QUAD
        0x2002,  // EN SPACE
        0x2003,  // EM SPACE
        0x2004,  // THREE-PER-EM SPACE
        0x2005,  // FOUR-PER-EM SPACE
        0x2006,  // SIX-PER-EM SPACE
        0x2007,  // FIGURE SPACE
        0x2008,  // PUNCTUATION SPACE
        0x2009,  // THIN SPACE
        0x200A,  // HAIR SPACE
        0x202F,  // NARROW NO-BREAK SPACE
        0x205F,  // MEDIUM MATHEMATICAL SPACE
        0x3000,  // IDEOGRAPHIC SPACE
    )

    // --- Vertical whitespace code points (JDK definition of \v) ---
    private val VERT_SPACE_CODEPOINTS = intArrayOf(
        0x0A,  // LINE FEED
        0x0B,  // VERTICAL TABULATION
        0x0C,  // FORM FEED
        0x0D,  // CARRIAGE RETURN
        0x85,  // NEXT LINE (NEL)
        0x2028,  // LINE SEPARATOR
        0x2029,  // PARAGRAPH SEPARATOR
    )

    // --- Non-whitespace code points for negative testing ---
    private val NON_WHITESPACE_CODEPOINTS = intArrayOf(
        'a'.code, 'Z'.code, '0'.code, '!'.code, '@'.code, '#'.code, 0x0100, 0x4E00,  // CJK character
    )

    @Nested
    @DisplayName("\\h (horizontal whitespace)")
    internal inner class HorizWhitespace {
        @Test
        @DisplayName("matches all JDK horizontal whitespace characters")
        fun matchesAllHorizWhitespace() {
            val p = Pattern.compile("\\h")
            for (cp in HORIZ_SPACE_CODEPOINTS) {
                val s = String(Character.toChars(cp))
                val m = p.matcher(s)
                Assertions.assertThat(m.matches()).`as`("\\h should match U+%04X", cp).isTrue()
            }
        }

        @Test
        @DisplayName("does not match vertical whitespace")
        fun doesNotMatchVertWhitespace() {
            val p = Pattern.compile("\\h")
            for (cp in VERT_SPACE_CODEPOINTS) {
                val s = String(Character.toChars(cp))
                val m = p.matcher(s)
                Assertions.assertThat(m.matches()).`as`("\\h should not match U+%04X", cp).isFalse()
            }
        }

        @Test
        @DisplayName("does not match non-whitespace characters")
        fun doesNotMatchNonWhitespace() {
            val p = Pattern.compile("\\h")
            for (cp in NON_WHITESPACE_CODEPOINTS) {
                val s = String(Character.toChars(cp))
                val m = p.matcher(s)
                Assertions.assertThat(m.matches()).`as`("\\h should not match U+%04X", cp).isFalse()
            }
        }

        @Test
        @DisplayName("finds all horizontal whitespace in mixed string")
        fun findsInMixedString() {
            val p = Pattern.compile("\\h+")
            val m = p.matcher("hello\t world\u00A0!")
            val matches: MutableList<String> = ArrayList<String>()
            while (m.find()) {
                matches.add(m.group()!!)
            }
            Assertions.assertThat<String?>(matches).containsExactly("\t ", "\u00A0")
        }

        @Test
        @DisplayName("works with quantifiers")
        fun worksWithQuantifiers() {
            Assertions.assertThat(Pattern.matches("\\h*", "")).isTrue()
            Assertions.assertThat(Pattern.matches("\\h+", "\t \u00A0")).isTrue()
            Assertions.assertThat(Pattern.matches("\\h{2}", "\t ")).isTrue()
            Assertions.assertThat(Pattern.matches("\\h{2}", "\t")).isFalse()
        }
    }

    @Nested
    @DisplayName("\\H (non-horizontal whitespace)")
    internal inner class NonHorizWhitespace {
        @Test
        @DisplayName("does not match horizontal whitespace")
        fun doesNotMatchHorizWhitespace() {
            val p = Pattern.compile("\\H")
            for (cp in HORIZ_SPACE_CODEPOINTS) {
                val s = String(Character.toChars(cp))
                val m = p.matcher(s)
                Assertions.assertThat(m.matches()).`as`("\\H should not match U+%04X", cp).isFalse()
            }
        }

        @Test
        @DisplayName("matches vertical whitespace")
        fun matchesVertWhitespace() {
            val p = Pattern.compile("\\H")
            for (cp in VERT_SPACE_CODEPOINTS) {
                val s = String(Character.toChars(cp))
                val m = p.matcher(s)
                Assertions.assertThat(m.matches()).`as`("\\H should match U+%04X", cp).isTrue()
            }
        }

        @Test
        @DisplayName("matches non-whitespace characters")
        fun matchesNonWhitespace() {
            val p = Pattern.compile("\\H")
            for (cp in NON_WHITESPACE_CODEPOINTS) {
                val s = String(Character.toChars(cp))
                val m = p.matcher(s)
                Assertions.assertThat(m.matches()).`as`("\\H should match U+%04X", cp).isTrue()
            }
        }

        @Test
        @DisplayName("finds non-horizontal-whitespace runs")
        fun findsInMixedString() {
            val p = Pattern.compile("\\H+")
            val m = p.matcher("hi\tthere")
            val matches: MutableList<String> = ArrayList<String>()
            while (m.find()) {
                matches.add(m.group()!!)
            }
            Assertions.assertThat<String?>(matches).containsExactly("hi", "there")
        }
    }

    @Nested
    @DisplayName("\\v (vertical whitespace)")
    internal inner class VertWhitespace {
        @Test
        @DisplayName("matches all JDK vertical whitespace characters")
        fun matchesAllVertWhitespace() {
            val p = Pattern.compile("\\v")
            for (cp in VERT_SPACE_CODEPOINTS) {
                val s = String(Character.toChars(cp))
                val m = p.matcher(s)
                Assertions.assertThat(m.matches()).`as`("\\v should match U+%04X", cp).isTrue()
            }
        }

        @Test
        @DisplayName("does not match horizontal whitespace")
        fun doesNotMatchHorizWhitespace() {
            val p = Pattern.compile("\\v")
            for (cp in HORIZ_SPACE_CODEPOINTS) {
                val s = String(Character.toChars(cp))
                val m = p.matcher(s)
                Assertions.assertThat(m.matches()).`as`("\\v should not match U+%04X", cp).isFalse()
            }
        }

        @Test
        @DisplayName("does not match non-whitespace characters")
        fun doesNotMatchNonWhitespace() {
            val p = Pattern.compile("\\v")
            for (cp in NON_WHITESPACE_CODEPOINTS) {
                val s = String(Character.toChars(cp))
                val m = p.matcher(s)
                Assertions.assertThat(m.matches()).`as`("\\v should not match U+%04X", cp).isFalse()
            }
        }

        @Test
        @DisplayName("finds all vertical whitespace in mixed string")
        fun findsInMixedString() {
            val p = Pattern.compile("\\v")
            val m = p.matcher("line1\nline2\rline3")
            val matches: MutableList<String> = ArrayList<String>()
            while (m.find()) {
                matches.add(String.format("U+%04X", m.group()!!.get(0).code))
            }
            Assertions.assertThat<String?>(matches).containsExactly("U+000A", "U+000D")
        }

        @Test
        @DisplayName("matches NEL (U+0085) and Unicode line/paragraph separators")
        fun matchesUnicodeLineSeparators() {
            val p = Pattern.compile("\\v")
            Assertions.assertThat(p.matcher("\u0085").matches()).isTrue()
            Assertions.assertThat(p.matcher("\u2028").matches()).isTrue()
            Assertions.assertThat(p.matcher("\u2029").matches()).isTrue()
        }

        @Test
        @DisplayName("works with quantifiers")
        fun worksWithQuantifiers() {
            Assertions.assertThat(Pattern.matches("\\v*", "")).isTrue()
            Assertions.assertThat(Pattern.matches("\\v+", "\n\r\u000B")).isTrue()
            Assertions.assertThat(Pattern.matches("\\v{2}", "\n\r")).isTrue()
            Assertions.assertThat(Pattern.matches("\\v{2}", "\n")).isFalse()
        }
    }

    @Nested
    @DisplayName("\\V (non-vertical whitespace)")
    internal inner class NonVertWhitespace {
        @Test
        @DisplayName("does not match vertical whitespace")
        fun doesNotMatchVertWhitespace() {
            val p = Pattern.compile("\\V")
            for (cp in VERT_SPACE_CODEPOINTS) {
                val s = String(Character.toChars(cp))
                val m = p.matcher(s)
                Assertions.assertThat(m.matches()).`as`("\\V should not match U+%04X", cp).isFalse()
            }
        }

        @Test
        @DisplayName("matches horizontal whitespace")
        fun matchesHorizWhitespace() {
            val p = Pattern.compile("\\V")
            for (cp in HORIZ_SPACE_CODEPOINTS) {
                val s = String(Character.toChars(cp))
                val m = p.matcher(s)
                Assertions.assertThat(m.matches()).`as`("\\V should match U+%04X", cp).isTrue()
            }
        }

        @Test
        @DisplayName("matches non-whitespace characters")
        fun matchesNonWhitespace() {
            val p = Pattern.compile("\\V")
            for (cp in NON_WHITESPACE_CODEPOINTS) {
                val s = String(Character.toChars(cp))
                val m = p.matcher(s)
                Assertions.assertThat(m.matches()).`as`("\\V should match U+%04X", cp).isTrue()
            }
        }

        @Test
        @DisplayName("finds non-vertical-whitespace runs")
        fun findsInMixedString() {
            val p = Pattern.compile("\\V+")
            val m = p.matcher("abc\ndef\rghi")
            val matches: MutableList<String> = ArrayList<String>()
            while (m.find()) {
                matches.add(m.group()!!)
            }
            Assertions.assertThat<String?>(matches).containsExactly("abc", "def", "ghi")
        }
    }

    @Nested
    @DisplayName("Inside character classes [...]")
    internal inner class InsideCharClass {
        @Test
        @DisplayName("\\h inside [...] matches horizontal whitespace")
        fun hInsideCharClass() {
            val p = Pattern.compile("[\\h]")
            Assertions.assertThat(p.matcher("\t").matches()).isTrue()
            Assertions.assertThat(p.matcher(" ").matches()).isTrue()
            Assertions.assertThat(p.matcher("\u00A0").matches()).isTrue()
            Assertions.assertThat(p.matcher("a").matches()).isFalse()
            Assertions.assertThat(p.matcher("\n").matches()).isFalse()
        }

        @Test
        @DisplayName("\\H inside [...] matches non-horizontal whitespace")
        fun hNegatedInsideCharClass() {
            val p = Pattern.compile("[\\H]")
            Assertions.assertThat(p.matcher("a").matches()).isTrue()
            Assertions.assertThat(p.matcher("\n").matches()).isTrue()
            Assertions.assertThat(p.matcher("\t").matches()).isFalse()
            Assertions.assertThat(p.matcher(" ").matches()).isFalse()
        }

        @Test
        @DisplayName("\\v inside [...] matches vertical whitespace")
        fun vInsideCharClass() {
            val p = Pattern.compile("[\\v]")
            Assertions.assertThat(p.matcher("\n").matches()).isTrue()
            Assertions.assertThat(p.matcher("\r").matches()).isTrue()
            Assertions.assertThat(p.matcher("\u000B").matches()).isTrue()
            Assertions.assertThat(p.matcher("\u0085").matches()).isTrue()
            Assertions.assertThat(p.matcher("a").matches()).isFalse()
            Assertions.assertThat(p.matcher("\t").matches()).isFalse()
        }

        @Test
        @DisplayName("\\V inside [...] matches non-vertical whitespace")
        fun vNegatedInsideCharClass() {
            val p = Pattern.compile("[\\V]")
            Assertions.assertThat(p.matcher("a").matches()).isTrue()
            Assertions.assertThat(p.matcher("\t").matches()).isTrue()
            Assertions.assertThat(p.matcher("\n").matches()).isFalse()
            Assertions.assertThat(p.matcher("\r").matches()).isFalse()
        }

        @Test
        @DisplayName("[\\h\\v] matches any whitespace (horizontal or vertical)")
        fun hAndVUnion() {
            val p = Pattern.compile("[\\h\\v]+")
            Assertions.assertThat(p.matcher("\t \n\r\u000B\u000c").matches()).isTrue()
            Assertions.assertThat(p.matcher("abc").matches()).isFalse()
        }

        @Test
        @DisplayName("[^\\h] is equivalent to \\H")
        fun negatedHInCharClass() {
            val negH = Pattern.compile("[^\\h]")
            val bigH = Pattern.compile("\\H")
            for (cp in HORIZ_SPACE_CODEPOINTS) {
                val s = String(Character.toChars(cp))
                Assertions.assertThat(negH.matcher(s).matches()).isEqualTo(bigH.matcher(s).matches())
            }
            for (cp in NON_WHITESPACE_CODEPOINTS) {
                val s = String(Character.toChars(cp))
                Assertions.assertThat(negH.matcher(s).matches()).isEqualTo(bigH.matcher(s).matches())
            }
        }

        @Test
        @DisplayName("[^\\v] is equivalent to \\V")
        fun negatedVInCharClass() {
            val negV = Pattern.compile("[^\\v]")
            val bigV = Pattern.compile("\\V")
            for (cp in VERT_SPACE_CODEPOINTS) {
                val s = String(Character.toChars(cp))
                Assertions.assertThat(negV.matcher(s).matches()).isEqualTo(bigV.matcher(s).matches())
            }
            for (cp in NON_WHITESPACE_CODEPOINTS) {
                val s = String(Character.toChars(cp))
                Assertions.assertThat(negV.matcher(s).matches()).isEqualTo(bigV.matcher(s).matches())
            }
        }

        @Test
        @DisplayName("\\h in range union: [a\\h] matches 'a' and horizontal whitespace")
        fun hInRangeUnion() {
            val p = Pattern.compile("[a\\h]")
            Assertions.assertThat(p.matcher("a").matches()).isTrue()
            Assertions.assertThat(p.matcher("\t").matches()).isTrue()
            Assertions.assertThat(p.matcher(" ").matches()).isTrue()
            Assertions.assertThat(p.matcher("b").matches()).isFalse()
        }

        @Test
        @DisplayName("\\v in range union: [a\\v] matches 'a' and vertical whitespace")
        fun vInRangeUnion() {
            val p = Pattern.compile("[a\\v]")
            Assertions.assertThat(p.matcher("a").matches()).isTrue()
            Assertions.assertThat(p.matcher("\n").matches()).isTrue()
            Assertions.assertThat(p.matcher("\r").matches()).isTrue()
            Assertions.assertThat(p.matcher("b").matches()).isFalse()
        }
    }

    @Nested
    @DisplayName("Combined patterns")
    internal inner class CombinedPatterns {
        @Test
        @DisplayName("\\h and \\v in alternation")
        fun alternation() {
            val p = Pattern.compile("\\h|\\v")
            Assertions.assertThat(p.matcher("\t").matches()).isTrue()
            Assertions.assertThat(p.matcher("\n").matches()).isTrue()
            Assertions.assertThat(p.matcher("a").matches()).isFalse()
        }

        @Test
        @DisplayName("\\h in complex pattern: split on horizontal whitespace")
        fun splitOnHorizWhitespace() {
            val p = Pattern.compile("\\h+")
            val parts = p.split("hello\tworld foo")
            Assertions.assertThat<String?>(parts).containsExactly("hello", "world", "foo")
        }

        @Test
        @DisplayName("\\v in complex pattern: split on vertical whitespace")
        fun splitOnVertWhitespace() {
            val p = Pattern.compile("\\v+")
            val parts = p.split("line1\nline2\r\nline3")
            Assertions.assertThat<String?>(parts).containsExactly("line1", "line2", "line3")
        }

        @Test
        @DisplayName("capture groups with \\h and \\v")
        fun captureGroups() {
            val p = Pattern.compile("(\\H+)(\\h+)(\\H+)")
            val m = p.matcher("hello\tworld")
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("hello")
            Assertions.assertThat(m.group(2)).isEqualTo("\t")
            Assertions.assertThat(m.group(3)).isEqualTo("world")
        }

        @Test
        @DisplayName("\\v with CASE_INSENSITIVE flag")
        fun caseInsensitive() {
            val p = Pattern.compile("\\v+", Pattern.CASE_INSENSITIVE)
            Assertions.assertThat(p.matcher("\n\r").matches()).isTrue()
        }

        @Test
        @DisplayName("\\h with DOTALL flag")
        fun dotall() {
            val p = Pattern.compile("\\h+", Pattern.DOTALL)
            Assertions.assertThat(p.matcher("\t ").matches()).isTrue()
        }

        @Test
        @DisplayName("\\h and \\H compile under Unicode character class flags")
        fun horizontalWhitespaceCompilesWithUnicodeCharacterClassFlags() {
            val h = Pattern.compile("\\h", Pattern.UNIX_LINES or Pattern.UNICODE_CHARACTER_CLASS)
            val bigH =
                Pattern.compile("\\H", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CHARACTER_CLASS)

            Assertions.assertThat(h.matcher("\t").matches()).isTrue()
            Assertions.assertThat(h.matcher("\n").matches()).isFalse()
            Assertions.assertThat(bigH.matcher("a").matches()).isTrue()
            Assertions.assertThat(bigH.matcher(" ").matches()).isFalse()
        }

        @Test
        @DisplayName("\\v and \\V compile under Unicode character class flags")
        fun verticalWhitespaceCompilesWithUnicodeCharacterClassFlags() {
            val v = Pattern.compile("\\v", Pattern.UNICODE_CHARACTER_CLASS)
            val bigV = Pattern.compile("\\V", Pattern.UNICODE_CHARACTER_CLASS)

            Assertions.assertThat(v.matcher("\n").matches()).isTrue()
            Assertions.assertThat(v.matcher(" ").matches()).isFalse()
            Assertions.assertThat(bigV.matcher("a").matches()).isTrue()
            Assertions.assertThat(bigV.matcher("\r").matches()).isFalse()
        }
    }

    @Nested
    @DisplayName("JDK compatibility cross-check")
    internal inner class JdkCompatibility {
        @Test
        @DisplayName("\\h matches same characters as JDK")
        fun hMatchesSameAsJdk() {
            val safeP = Pattern.compile("\\h")
            val jdkP = java.util.regex.Pattern.compile("\\h")
            // Test BMP code points up to 0x3100 (covers all \h members plus surroundings)
            for (cp in 0..0x3100) {
                val s = String(Character.toChars(cp))
                val safeMatch = safeP.matcher(s).matches()
                val jdkMatch = jdkP.matcher(s).matches()
                Assertions.assertThat(safeMatch)
                    .`as`("\\h U+%04X: SafeRE=%s, JDK=%s", cp, safeMatch, jdkMatch)
                    .isEqualTo(jdkMatch)
            }
        }

        @Test
        @DisplayName("\\v matches same characters as JDK")
        fun vMatchesSameAsJdk() {
            val safeP = Pattern.compile("\\v")
            val jdkP = java.util.regex.Pattern.compile("\\v")
            // Test BMP code points up to 0x3000 (covers all \v members plus surroundings)
            for (cp in 0..0x3000) {
                val s = String(Character.toChars(cp))
                val safeMatch = safeP.matcher(s).matches()
                val jdkMatch = jdkP.matcher(s).matches()
                Assertions.assertThat(safeMatch)
                    .`as`("\\v U+%04X: SafeRE=%s, JDK=%s", cp, safeMatch, jdkMatch)
                    .isEqualTo(jdkMatch)
            }
        }

        @Test
        @DisplayName("\\H matches same characters as JDK")
        fun bigHMatchesSameAsJdk() {
            val safeP = Pattern.compile("\\H")
            val jdkP = java.util.regex.Pattern.compile("\\H")
            for (cp in 0..0x3100) {
                val s = String(Character.toChars(cp))
                val safeMatch = safeP.matcher(s).matches()
                val jdkMatch = jdkP.matcher(s).matches()
                Assertions.assertThat(safeMatch)
                    .`as`("\\H U+%04X: SafeRE=%s, JDK=%s", cp, safeMatch, jdkMatch)
                    .isEqualTo(jdkMatch)
            }
        }

        @Test
        @DisplayName("\\V matches same characters as JDK")
        fun bigVMatchesSameAsJdk() {
            val safeP = Pattern.compile("\\V")
            val jdkP = java.util.regex.Pattern.compile("\\V")
            for (cp in 0..0x3000) {
                val s = String(Character.toChars(cp))
                val safeMatch = safeP.matcher(s).matches()
                val jdkMatch = jdkP.matcher(s).matches()
                Assertions.assertThat(safeMatch)
                    .`as`("\\V U+%04X: SafeRE=%s, JDK=%s", cp, safeMatch, jdkMatch)
                    .isEqualTo(jdkMatch)
            }
        }
    }
}
