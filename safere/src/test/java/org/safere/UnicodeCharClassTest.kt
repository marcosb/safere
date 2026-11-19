// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [Pattern.UNICODE_CHARACTER_CLASS] flag behavior. When this flag is set, `\d`, `\w`, `\s`, and `\b` use Unicode definitions instead of ASCII-only.
 */
internal object UnicodeCharClassTest {
    private val UCC = Pattern.UNICODE_CHARACTER_CLASS

    @Nested
    internal inner class PosixUnicodeCharacterClassTests {
        @Test
        fun posixAlphaUsesUnicodeWithFlag() {
            val p = Pattern.compile("\\p{Alpha}+", UCC)
            Assertions.assertThat(p.matcher("é中").matches()).isTrue()
        }

        @Test
        fun posixLowerAndUpperUseUnicodeWithFlag() {
            Assertions.assertThat(Pattern.compile("\\p{Lower}", UCC).matcher("é").matches()).isTrue()
            Assertions.assertThat(Pattern.compile("\\p{Upper}", UCC).matcher("É").matches()).isTrue()
        }

        @Test
        fun posixDigitUsesUnicodeWithFlag() {
            val p = Pattern.compile("\\p{Digit}+", UCC)
            Assertions.assertThat(p.matcher("\u0661").matches()).isTrue()
        }

        @Test
        fun posixPunctUsesUnicodePunctuationWithFlag() {
            val p = Pattern.compile("\\p{Punct}", UCC)
            Assertions.assertThat(p.matcher("\u3002").matches()).isTrue()
            Assertions.assertThat(p.matcher("$").matches()).isFalse()
        }
    }

    // -------------------------------------------------------------------------
    // \d — Unicode digit (category Nd)
    // -------------------------------------------------------------------------
    @Nested
    internal inner class DigitTests {
        @Test
        fun asciiDigitsMatchWithAndWithoutFlag() {
            val pAscii = Pattern.compile("\\d+")
            val pUnicode = Pattern.compile("\\d+", UCC)
            Assertions.assertThat(pAscii.matcher("12345").matches()).isTrue()
            Assertions.assertThat(pUnicode.matcher("12345").matches()).isTrue()
        }

        @Test
        fun arabicIndicDigitsMatchWithFlag() {
            // Arabic-Indic digits: ٠١٢٣ (U+0660–U+0663)
            val p = Pattern.compile("\\d+", UCC)
            Assertions.assertThat(p.matcher("\u0660\u0661\u0662\u0663").matches()).isTrue()
        }

        @Test
        fun arabicIndicDigitsDoNotMatchWithoutFlag() {
            val p = Pattern.compile("\\d+")
            Assertions.assertThat(p.matcher("\u0660\u0661\u0662\u0663").matches()).isFalse()
        }

        @Test
        fun fullwidthDigitsMatchWithFlag() {
            // Fullwidth digits: ０１２ (U+FF10–U+FF12)
            val p = Pattern.compile("\\d+", UCC)
            Assertions.assertThat(p.matcher("\uFF10\uFF11\uFF12").matches()).isTrue()
        }

        @Test
        fun fullwidthDigitsDoNotMatchWithoutFlag() {
            val p = Pattern.compile("\\d+")
            Assertions.assertThat(p.matcher("\uFF10\uFF11\uFF12").matches()).isFalse()
        }

        @Test
        fun devanagariDigitsMatchWithFlag() {
            // Devanagari digits: ०१२ (U+0966–U+0968)
            val p = Pattern.compile("\\d+", UCC)
            Assertions.assertThat(p.matcher("\u0966\u0967\u0968").matches()).isTrue()
        }

        @Test
        fun superscriptDigitsDoNotMatch() {
            // Superscript ² (U+00B2) is NOT Unicode Nd category — it's No.
            val p = Pattern.compile("\\d", UCC)
            Assertions.assertThat(p.matcher("\u00B2").matches()).isFalse()
        }

        @Test
        fun negatedUnicodeDigit() {
            val p = Pattern.compile("\\D+", UCC)
            // Latin letters should match \D
            Assertions.assertThat(p.matcher("abc").matches()).isTrue()
            // Arabic-Indic digits should NOT match \D
            Assertions.assertThat(p.matcher("\u0660").matches()).isFalse()
        }
    }

    // -------------------------------------------------------------------------
    // \s — Unicode whitespace (White_Space property)
    // -------------------------------------------------------------------------
    @Nested
    internal inner class SpaceTests {
        @Test
        fun asciiWhitespaceMatchesWithAndWithoutFlag() {
            val pAscii = Pattern.compile("\\s")
            val pUnicode = Pattern.compile("\\s", UCC)
            Assertions.assertThat(pAscii.matcher(" ").matches()).isTrue()
            Assertions.assertThat(pUnicode.matcher(" ").matches()).isTrue()
            Assertions.assertThat(pAscii.matcher("\t").matches()).isTrue()
            Assertions.assertThat(pUnicode.matcher("\t").matches()).isTrue()
        }

        @Test
        fun noBreakSpaceMatchesWithFlag() {
            // NBSP (U+00A0) is Unicode White_Space.
            val p = Pattern.compile("\\s", UCC)
            Assertions.assertThat(p.matcher("\u00A0").matches()).isTrue()
        }

        @Test
        fun noBreakSpaceDoesNotMatchWithoutFlag() {
            val p = Pattern.compile("\\s")
            Assertions.assertThat(p.matcher("\u00A0").matches()).isFalse()
        }

        @Test
        fun emSpaceMatchesWithFlag() {
            // Em space (U+2003) is Unicode White_Space.
            val p = Pattern.compile("\\s", UCC)
            Assertions.assertThat(p.matcher("\u2003").matches()).isTrue()
        }

        @Test
        fun nextLineMatchesWithFlag() {
            // NEL (U+0085) is Unicode White_Space.
            val p = Pattern.compile("\\s", UCC)
            Assertions.assertThat(p.matcher("\u0085").matches()).isTrue()
        }

        @Test
        fun lineSeparatorMatchesWithFlag() {
            // Line separator (U+2028) is Unicode White_Space.
            val p = Pattern.compile("\\s", UCC)
            Assertions.assertThat(p.matcher("\u2028").matches()).isTrue()
        }

        @Test
        fun paragraphSeparatorMatchesWithFlag() {
            // Paragraph separator (U+2029) is Unicode White_Space.
            val p = Pattern.compile("\\s", UCC)
            Assertions.assertThat(p.matcher("\u2029").matches()).isTrue()
        }

        @Test
        fun zeroWidthSpaceDoesNotMatch() {
            // Zero-width space (U+200B) is NOT Unicode White_Space.
            val p = Pattern.compile("\\s", UCC)
            Assertions.assertThat(p.matcher("\u200B").matches()).isFalse()
        }

        @Test
        fun negatedUnicodeSpace() {
            val p = Pattern.compile("\\S+", UCC)
            Assertions.assertThat(p.matcher("abc").matches()).isTrue()
            // NBSP should NOT match \S
            Assertions.assertThat(p.matcher("\u00A0").matches()).isFalse()
        }
    }

    // -------------------------------------------------------------------------
    // \w — Unicode word character (Alpha + M + Nd + Nl + Pc + Join_Control)
    // -------------------------------------------------------------------------
    @Nested
    internal inner class WordTests {
        @Test
        fun asciiWordCharsMatchWithAndWithoutFlag() {
            val pAscii = Pattern.compile("\\w+")
            val pUnicode = Pattern.compile("\\w+", UCC)
            Assertions.assertThat(pAscii.matcher("abc123_").matches()).isTrue()
            Assertions.assertThat(pUnicode.matcher("abc123_").matches()).isTrue()
        }

        @Test
        fun accentedLettersMatchWithFlag() {
            // é (U+00E9) is category L — should match Unicode \w.
            val p = Pattern.compile("\\w+", UCC)
            Assertions.assertThat(p.matcher("café").matches()).isTrue()
            Assertions.assertThat(p.matcher("naïve").matches()).isTrue()
        }

        @Test
        fun accentedLettersDoNotMatchWithoutFlag() {
            val p = Pattern.compile("\\w+")
            // Without UCC, \w is [A-Za-z0-9_], so 'é' doesn't match.
            Assertions.assertThat(p.matcher("é").matches()).isFalse()
        }

        @Test
        fun cyrillicMatchesWithFlag() {
            val p = Pattern.compile("\\w+", UCC)
            Assertions.assertThat(p.matcher("Привет").matches()).isTrue()
        }

        @Test
        fun cjkMatchesWithFlag() {
            // CJK Unified Ideographs are category Lo (Letter, Other) — part of L.
            val p = Pattern.compile("\\w+", UCC)
            Assertions.assertThat(p.matcher("漢字").matches()).isTrue()
        }

        @Test
        fun alphabeticSymbolsMatchWithFlag() {
            val p = Pattern.compile("\\w+", UCC)
            Assertions.assertThat(p.matcher("ⓓⓔⓕ").matches()).isTrue()
        }

        @Test
        fun bracketedWordIntersectionUsesUnicodeAlphabeticWithFlag() {
            val p = Pattern.compile("[\\w&&[^_]]{3,20}", UCC)
            Assertions.assertThat(p.matcher("Aᶛᶜⓓⓔⓕäöüकफ").matches()).isTrue()
            Assertions.assertThat(p.matcher("_").matches()).isFalse()
        }

        @Test
        fun connectorPunctuationMatchesWithFlag() {
            // Undertie ‿ (U+203F) and ﹏ (U+FE4F) are category Pc.
            val p = Pattern.compile("\\w", UCC)
            Assertions.assertThat(p.matcher("\u203F").matches()).isTrue()
            Assertions.assertThat(p.matcher("\uFE4F").matches()).isTrue()
        }

        @Test
        fun hyphenDoesNotMatch() {
            // Hyphen-minus is NOT \w even with Unicode.
            val p = Pattern.compile("\\w", UCC)
            Assertions.assertThat(p.matcher("-").matches()).isFalse()
        }

        @Test
        fun negatedUnicodeWord() {
            val p = Pattern.compile("\\W+", UCC)
            // Spaces and punctuation should match \W
            Assertions.assertThat(p.matcher(" !@#").matches()).isTrue()
            // Accented letters should NOT match \W
            Assertions.assertThat(p.matcher("é").matches()).isFalse()
        }
    }

    @Nested
    @DisabledForCrosscheck("Checks SafeRE's bundled Unicode tables, which intentionally differ from older JDKs")
    internal inner class TableConsistencyTests {
        @Test
        fun caseInsensitiveCasedPropertiesIncludeCaseSensitiveMembers() {
            // The case-insensitive closure and the case-sensitive tables use the same Unicode data, so
            // folding never drops a member on any JDK.
            val folded = Pattern.compile("(?i)\\p{IsLowercase}").matcher("")
            val cased =
                Pattern.compile("[\\p{IsLowercase}\\p{IsUppercase}\\p{IsTitlecase}]").matcher("")
            for (cp in 0..Character.MAX_CODE_POINT) {
                val s = Character.toString(cp)
                if (cased.reset(s).matches()) {
                    Assertions.assertThat(folded.reset(s).matches()).`as`("U+%04X", cp).isTrue()
                }
            }
        }

        @Test
        fun unicodePrintIsGraphPlusSpaceSeparators() {
            val print = Pattern.compile("\\p{Print}", UCC).matcher("")
            val expected = Pattern.compile("[\\p{Graph}\\p{Zs}]", UCC).matcher("")
            for (cp in 0..Character.MAX_CODE_POINT) {
                val s = Character.toString(cp)
                Assertions.assertThat(print.reset(s).matches())
                    .`as`("U+%04X", cp)
                    .isEqualTo(expected.reset(s).matches())
            }
        }

        @Test
        fun unicodeBlankIsTabPlusSpaceSeparators() {
            val blank = Pattern.compile("\\p{Blank}", UCC).matcher("")
            val expected = Pattern.compile("[\\t\\p{Zs}]", UCC).matcher("")
            for (cp in 0..Character.MAX_CODE_POINT) {
                val s = Character.toString(cp)
                Assertions.assertThat(blank.reset(s).matches())
                    .`as`("U+%04X", cp)
                    .isEqualTo(expected.reset(s).matches())
            }
        }
    }

    @Nested
    internal inner class PosixUnicodeEdgeTests {
        @Test
        fun xdigitIncludesAllUnicodeDigitsWithFlag() {
            val p = Pattern.compile("\\p{XDigit}+", UCC)
            Assertions.assertThat(p.matcher("०۱۲٣").matches()).isTrue()
        }

        @Test
        fun printExcludesControlBlankWithFlag() {
            val p = Pattern.compile("\\p{Print}", UCC)
            Assertions.assertThat(p.matcher(" ").matches()).isTrue()
            Assertions.assertThat(p.matcher("\u00A0").matches()).isTrue()
            Assertions.assertThat(p.matcher("\t").matches()).isFalse()
        }
    }

    // -------------------------------------------------------------------------
    // \b — Unicode word boundary
    // -------------------------------------------------------------------------
    @Nested
    internal inner class WordBoundaryTests {
        @Test
        fun asciiWordBoundaryWorksBothWays() {
            val pAscii = Pattern.compile("\\bword\\b")
            val pUnicode = Pattern.compile("\\bword\\b", UCC)
            Assertions.assertThat(pAscii.matcher("a word here").find()).isTrue()
            Assertions.assertThat(pUnicode.matcher("a word here").find()).isTrue()
        }

        @Test
        fun unicodeWordBoundaryWithAccentedWord() {
            // With UCC, \b should recognize accented characters as word chars.
            val p = Pattern.compile("\\bcafé\\b", UCC)
            Assertions.assertThat(p.matcher("a café here").find()).isTrue()
            Assertions.assertThat(p.matcher("café").matches()).isTrue()
        }

        @Test
        fun unicodeWordBoundaryDoesNotSplitAccentedWord() {
            // Without UCC, \b sees 'é' as a non-word char, so \bcaf\b matches inside "café".
            val pAscii = Pattern.compile("\\bcaf\\b")
            Assertions.assertThat(pAscii.matcher("café").find()).isTrue()

            // With UCC, 'é' is a word char so \bcaf\b should NOT match inside "café" —
            // there's no word boundary between 'f' and 'é'.
            val pUnicode = Pattern.compile("\\bcaf\\b", UCC)
            Assertions.assertThat(pUnicode.matcher("café").find()).isFalse()
        }

        @Test
        fun unicodeWordBoundaryWithCyrillic() {
            val p = Pattern.compile("\\bслово\\b", UCC)
            Assertions.assertThat(p.matcher("это слово тут").find()).isTrue()
        }

        @Test
        fun unicodeNonWordBoundary() {
            // \B is the negation of \b.
            val p = Pattern.compile("\\Bfé\\B", UCC)
            // In "café", between 'a' and 'f' there's no boundary (\B matches),
            // and between 'é' and end... actually "café" at the end has a boundary.
            // Let's use a word that has \B in the middle.
            Assertions.assertThat(p.matcher("caféine").find()).isTrue()
        }
    }

    // -------------------------------------------------------------------------
    // Combined behavior and edge cases
    // -------------------------------------------------------------------------
    @Nested
    internal inner class CombinedTests {
        @Test
        fun findAllUnicodeDigits() {
            val p = Pattern.compile("\\d+", UCC)
            val m = p.matcher("price: ١٢٣ or 456")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("\u0661\u0662\u0663")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("456")
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        fun replaceWithUnicodeCharClass() {
            val p = Pattern.compile("\\s+", UCC)
            // Replace all whitespace including NBSP with a single space.
            val input = "hello\u00A0world\u2003here"
            Assertions.assertThat(p.matcher(input).replaceAll(" ")).isEqualTo("hello world here")
        }

        @Test
        fun splitOnUnicodeWhitespace() {
            val p = Pattern.compile("\\s+", UCC)
            val input = "a\u00A0b\u2003c"
            val parts = p.split(input)
            Assertions.assertThat<String?>(parts).containsExactly("a", "b", "c")
        }

        @Test
        fun unicodeCharClassWithCaseInsensitive() {
            val p = Pattern.compile("\\w+", UCC or Pattern.CASE_INSENSITIVE)
            Assertions.assertThat(p.matcher("Café").matches()).isTrue()
            Assertions.assertThat(p.matcher("CAFÉ").matches()).isTrue()
        }

        @Test
        fun withoutFlagDigitIsAsciiOnly() {
            // Baseline: without UCC, \d matches only [0-9].
            val p = Pattern.compile("^\\d+$")
            Assertions.assertThat(p.matcher("123").matches()).isTrue()
            Assertions.assertThat(p.matcher("\u0660").matches()).isFalse()
        }

        @Test
        fun withoutFlagWordIsAsciiOnly() {
            // Baseline: without UCC, \w matches only [A-Za-z0-9_].
            val p = Pattern.compile("^\\w+$")
            Assertions.assertThat(p.matcher("abc123").matches()).isTrue()
            Assertions.assertThat(p.matcher("café").matches()).isFalse()
        }

        @Test
        fun withoutFlagSpaceIsAsciiOnly() {
            // Baseline: without UCC, \s matches only [ \t\n\r\f\x0B].
            val p = Pattern.compile("\\s")
            Assertions.assertThat(p.matcher(" ").matches()).isTrue()
            Assertions.assertThat(p.matcher("\u00A0").matches()).isFalse()
        }
    }
}
