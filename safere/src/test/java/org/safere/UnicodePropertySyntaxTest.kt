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
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Tests for JDK-compatible Unicode property syntax in `\p{...}` and `\P{...}`.
 * 
 * 
 * Covers: `Is` prefix (scripts, categories, binary properties), `In` prefix
 * (blocks), and keyword forms (`script=`, `block=`, `general_category=` and their
 * short forms).
 */
internal object UnicodePropertySyntaxTest {
    // ---- Helper methods ----
    /** Returns true if the regex matches anywhere in the input.  */
    private fun find(regex: String?, input: String?): Boolean {
        return Pattern.compile(regex).matcher(input).find()
    }

    /** Cross-validates against JDK: asserts SafeRE and JDK agree on find().  */
    private fun assertMatchesJdk(regex: String, input: String) {
        val safere = find(regex, input)
        val jdk = Pattern.compile(regex).matcher(input).find()
        Assertions.assertThat(safere)
            .`as`("SafeRE and JDK should agree for %s on \"%s\"", regex, input)
            .isEqualTo(jdk)
    }

    // =========================================================================
    // Is prefix — scripts
    // =========================================================================
    @Nested
    @DisplayName("\\p{IsScript} — Is prefix for scripts")
    internal inner class IsScriptTest {
        @Test
        @DisplayName("\\p{IsLatin} matches Latin letters")
        fun isLatinMatchesLatinLetters() {
            Assertions.assertThat(find("\\p{IsLatin}", "A")).isTrue()
            Assertions.assertThat(find("\\p{IsLatin}", "z")).isTrue()
            Assertions.assertThat(find("\\p{IsLatin}", "é")).isTrue()
        }

        @Test
        @DisplayName("\\p{IsLatin} does not match non-Latin characters")
        fun isLatinDoesNotMatchNonLatin() {
            Assertions.assertThat(find("\\p{IsLatin}", "α")).isFalse() // Greek
            Assertions.assertThat(find("\\p{IsLatin}", "Б")).isFalse() // Cyrillic
            Assertions.assertThat(find("\\p{IsLatin}", "中")).isFalse() // Han
        }

        @Test
        @DisplayName("\\p{IsGreek} matches Greek letters")
        fun isGreekMatchesGreekLetters() {
            Assertions.assertThat(find("\\p{IsGreek}", "α")).isTrue()
            Assertions.assertThat(find("\\p{IsGreek}", "Ω")).isTrue()
            Assertions.assertThat(find("\\p{IsGreek}", "A")).isFalse()
        }

        @Test
        @DisplayName("\\p{IsCyrillic} matches Cyrillic letters")
        fun isCyrillicMatchesCyrillicLetters() {
            Assertions.assertThat(find("\\p{IsCyrillic}", "Б")).isTrue()
            Assertions.assertThat(find("\\p{IsCyrillic}", "A")).isFalse()
        }

        @Test
        @DisplayName("\\p{IsArabic} matches Arabic characters")
        fun isArabicMatchesArabicCharacters() {
            Assertions.assertThat(find("\\p{IsArabic}", "\u0627")).isTrue() // Arabic Alef
            Assertions.assertThat(find("\\p{IsArabic}", "A")).isFalse()
        }

        @Test
        @DisplayName("\\p{IsHan} matches Han/CJK characters")
        fun isHanMatchesHanCharacters() {
            Assertions.assertThat(find("\\p{IsHan}", "中")).isTrue()
            Assertions.assertThat(find("\\p{IsHan}", "A")).isFalse()
        }

        @Test
        @DisplayName("Is prefix is case-insensitive for the script name")
        fun isPrefixCaseInsensitiveForScriptName() {
            Assertions.assertThat(find("\\p{IsLATIN}", "A")).isTrue()
            Assertions.assertThat(find("\\p{Islatin}", "A")).isTrue()
            Assertions.assertThat(find("\\p{IsLatin}", "A")).isTrue()
        }

        @Test
        @DisplayName("\\p{IsUnknown} matches unknown script characters")
        fun isUnknownMatchesUnknownScript() {
            Assertions.assertThat(find("\\p{IsUnknown}", "\uE000")).isTrue()
            Assertions.assertThat(find("\\p{IsUnknown}", "A")).isFalse()
        }

        @ParameterizedTest
        @CsvSource(
            "\\p{IsLatin}, A",
            "\\p{IsLatin}, z",
            "\\p{IsGreek}, α",
            "\\p{IsCyrillic}, Б",
            "\\p{IsLATIN}, A",
            "\\p{Islatin}, A",
            "\\p{IsUnknown}, \uE000"
        )
        @DisplayName("Is prefix scripts cross-validate against JDK")
        fun isScriptCrossValidation(regex: String, input: String) {
            assertMatchesJdk(regex, input)
        }
    }

    // =========================================================================
    // Is prefix — categories
    // =========================================================================
    @Nested
    @DisplayName("\\p{IsCategory} — Is prefix for general categories")
    internal inner class IsCategoryTest {
        @Test
        @DisplayName("\\p{IsL} matches letters")
        fun isLMatchesLetters() {
            Assertions.assertThat(find("\\p{IsL}", "A")).isTrue()
            Assertions.assertThat(find("\\p{IsL}", "1")).isFalse()
        }

        @Test
        @DisplayName("\\p{IsLu} matches uppercase letters")
        fun isLuMatchesUppercase() {
            Assertions.assertThat(find("\\p{IsLu}", "A")).isTrue()
            Assertions.assertThat(find("\\p{IsLu}", "a")).isFalse()
        }

        @Test
        @DisplayName("\\p{IsLl} matches lowercase letters")
        fun isLlMatchesLowercase() {
            Assertions.assertThat(find("\\p{IsLl}", "a")).isTrue()
            Assertions.assertThat(find("\\p{IsLl}", "A")).isFalse()
        }

        @Test
        @DisplayName("\\p{IsN} matches numbers")
        fun isNMatchesNumbers() {
            Assertions.assertThat(find("\\p{IsN}", "5")).isTrue()
            Assertions.assertThat(find("\\p{IsN}", "A")).isFalse()
        }

        @Test
        @DisplayName("\\p{IsNd} matches decimal digits")
        fun isNdMatchesDecimalDigits() {
            Assertions.assertThat(find("\\p{IsNd}", "5")).isTrue()
            Assertions.assertThat(find("\\p{IsNd}", "A")).isFalse()
        }

        @Test
        @DisplayName("\\p{IsP} matches punctuation")
        fun isPMatchesPunctuation() {
            Assertions.assertThat(find("\\p{IsP}", ".")).isTrue()
            Assertions.assertThat(find("\\p{IsP}", "A")).isFalse()
        }

        @Test
        @DisplayName("\\p{IsS} matches symbols")
        fun isSMatchesSymbols() {
            Assertions.assertThat(find("\\p{IsS}", "$")).isTrue()
            Assertions.assertThat(find("\\p{IsS}", "A")).isFalse()
        }

        @Test
        @DisplayName("\\p{IsZ} matches separators")
        fun isZMatchesSeparators() {
            Assertions.assertThat(find("\\p{IsZ}", " ")).isTrue()
            Assertions.assertThat(find("\\p{IsZ}", "A")).isFalse()
        }

        @Test
        @DisplayName("\\p{IsC} matches control/other characters")
        fun isCMatchesControlChars() {
            Assertions.assertThat(find("\\p{IsC}", "\u0000")).isTrue()
            Assertions.assertThat(find("\\p{IsC}", "A")).isFalse()
        }

        @ParameterizedTest
        @CsvSource(
            "\\p{IsL}, A", "\\p{IsLu}, A", "\\p{IsLl}, a", "\\p{IsN}, 5", "\\p{IsNd}, 5", "\\p{IsP}, ."
        )
        @DisplayName("Is prefix categories cross-validate against JDK")
        fun isCategoryCrossValidation(regex: String, input: String) {
            assertMatchesJdk(regex, input)
        }
    }

    // =========================================================================
    // Is prefix — binary properties
    // =========================================================================
    @Nested
    @DisplayName("\\p{IsBinaryProperty} — binary properties")
    internal inner class BinaryPropertyTest {
        @Test
        @DisplayName("\\p{IsAlphabetic}")
        fun isAlphabetic() {
            Assertions.assertThat(find("\\p{IsAlphabetic}", "A")).isTrue()
            Assertions.assertThat(find("\\p{IsAlphabetic}", "1")).isFalse()
            Assertions.assertThat(find("\\p{IsAlphabetic}", ".")).isFalse()
            assertMatchesJdk("\\p{IsAlphabetic}", "A")
            assertMatchesJdk("\\p{IsAlphabetic}", "1")
        }

        @Test
        @DisplayName("\\p{IsLetter}")
        fun isLetter() {
            Assertions.assertThat(find("\\p{IsLetter}", "A")).isTrue()
            Assertions.assertThat(find("\\p{IsLetter}", "1")).isFalse()
            assertMatchesJdk("\\p{IsLetter}", "A")
            assertMatchesJdk("\\p{IsLetter}", "1")
        }

        @Test
        @DisplayName("\\p{IsLowercase}")
        fun isLowercase() {
            Assertions.assertThat(find("\\p{IsLowercase}", "a")).isTrue()
            Assertions.assertThat(find("\\p{IsLowercase}", "A")).isFalse()
            assertMatchesJdk("\\p{IsLowercase}", "a")
            assertMatchesJdk("\\p{IsLowercase}", "A")
        }

        @Test
        @DisplayName("\\p{IsUppercase}")
        fun isUppercase() {
            Assertions.assertThat(find("\\p{IsUppercase}", "A")).isTrue()
            Assertions.assertThat(find("\\p{IsUppercase}", "a")).isFalse()
            assertMatchesJdk("\\p{IsUppercase}", "A")
            assertMatchesJdk("\\p{IsUppercase}", "a")
        }

        @Test
        @DisplayName("\\p{IsTitlecase}")
        fun isTitlecase() {
            Assertions.assertThat(find("\\p{IsTitlecase}", "\u01C5")).isTrue() // Dz with caron
            Assertions.assertThat(find("\\p{IsTitlecase}", "A")).isFalse()
            assertMatchesJdk("\\p{IsTitlecase}", "\u01C5")
        }

        @Test
        @DisplayName("\\p{IsDigit}")
        fun isDigit() {
            Assertions.assertThat(find("\\p{IsDigit}", "5")).isTrue()
            Assertions.assertThat(find("\\p{IsDigit}", "A")).isFalse()
            assertMatchesJdk("\\p{IsDigit}", "5")
            assertMatchesJdk("\\p{IsDigit}", "A")
        }

        @Test
        @DisplayName("\\p{IsHex_Digit}")
        fun isHexDigit() {
            Assertions.assertThat(find("\\p{IsHex_Digit}", "A")).isTrue()
            Assertions.assertThat(find("\\p{IsHex_Digit}", "f")).isTrue()
            Assertions.assertThat(find("\\p{IsHex_Digit}", "0")).isTrue()
            Assertions.assertThat(find("\\p{IsHex_Digit}", "G")).isFalse()
            assertMatchesJdk("\\p{IsHex_Digit}", "A")
            assertMatchesJdk("\\p{IsHex_Digit}", "G")
        }

        @Test
        @DisplayName("\\p{IsWhite_Space}")
        fun isWhiteSpace() {
            Assertions.assertThat(find("\\p{IsWhite_Space}", " ")).isTrue()
            Assertions.assertThat(find("\\p{IsWhite_Space}", "\t")).isTrue()
            Assertions.assertThat(find("\\p{IsWhite_Space}", "\n")).isTrue()
            Assertions.assertThat(find("\\p{IsWhite_Space}", "\u00A0")).isTrue() // NBSP
            Assertions.assertThat(find("\\p{IsWhite_Space}", "A")).isFalse()
            assertMatchesJdk("\\p{IsWhite_Space}", " ")
            assertMatchesJdk("\\p{IsWhite_Space}", "\u00A0")
        }

        @ParameterizedTest
        @ValueSource(
            ints = [0x08, 0x09, 0x0D, 0x0E, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x21, 0x84, 0x85, 0x86, 0xA0, 0x1680, 0x180E, 0x2000, 0x200A, 0x200B, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0x10000
            ]
        )
        fun unicodeWhiteSpaceFormsAgreeAtPropertyBoundaries(cp: Int) {
            val input = Character.toString(cp)
            val expected =
                Pattern.compile("\\p{IsWhite_Space}").matcher(input).matches()
            for (regex in arrayOf<String>(
                "\\p{IsWhite_Space}",
                "[\\p{IsWhite_Space}]",
                "(?U)\\s",
                "(?U)\\p{Space}",
                "[\\p{IsWhite_Space}&&[\\p{Cc}\\p{Z}]]"
            )) {
                Assertions.assertThat(org.safere.Pattern.compile(regex).matcher(input).matches())
                    .`as`("%s at U+%04X", regex, cp)
                    .isEqualTo(expected)
            }
            for (regex in arrayOf<String>("\\P{IsWhite_Space}", "[^\\p{IsWhite_Space}]", "(?U)\\S")) {
                Assertions.assertThat(org.safere.Pattern.compile(regex).matcher(input).matches())
                    .`as`("%s at U+%04X", regex, cp)
                    .isEqualTo(!expected)
            }
        }

        @ParameterizedTest
        @ValueSource(
            ints = [0x30, 0x39, 0x41, 0x46, 0x61, 0x66, 0xFF10, 0xFF19, 0xFF21, 0xFF26, 0xFF41, 0xFF46, 0x2F, 0x3A, 0x47, 0x67, 0xFF0F, 0xFF1A, 0xFF27, 0xFF47, 0x0660, 0x0966, 0x1D7CE
            ]
        )
        @DisabledForCrosscheck("SafeRE follows Unicode Hex_Digit; JDK also matches other decimal digits")
        fun hexDigitUsesUnicodePropertyRatherThanAllDecimalDigits(cp: Int) {
            // Issue #985: preserve the Unicode property despite JDK's broader implementation.
            val expected =
                (cp >= '0'.code && cp <= '9'.code)
                        || (cp >= 'A'.code && cp <= 'F'.code)
                        || (cp >= 'a'.code && cp <= 'f'.code)
                        || (cp >= 0xFF10 && cp <= 0xFF19)
                        || (cp >= 0xFF21 && cp <= 0xFF26)
                        || (cp >= 0xFF41 && cp <= 0xFF46)
            val input = Character.toString(cp)
            for (regex in arrayOf<String>("\\p{IsHex_Digit}", "[\\p{IsHex_Digit}]")) {
                Assertions.assertThat(org.safere.Pattern.compile(regex).matcher(input).matches())
                    .`as`("%s at U+%04X", regex, cp)
                    .isEqualTo(expected)
            }
            for (regex in arrayOf<String>("\\P{IsHex_Digit}", "[^\\p{IsHex_Digit}]")) {
                Assertions.assertThat(org.safere.Pattern.compile(regex).matcher(input).matches())
                    .`as`("%s at U+%04X", regex, cp)
                    .isEqualTo(!expected)
            }
            // Unicode XDigit is deliberately broader: [gc=Nd IsHex_Digit].
            Assertions.assertThat(org.safere.Pattern.compile("(?U)\\p{XDigit}").matcher(input).matches())
                .isEqualTo(expected || Character.isDigit(cp))
        }

        @Test
        @DisplayName("\\p{IsPunctuation}")
        fun isPunctuation() {
            Assertions.assertThat(find("\\p{IsPunctuation}", ".")).isTrue()
            Assertions.assertThat(find("\\p{IsPunctuation}", ",")).isTrue()
            Assertions.assertThat(find("\\p{IsPunctuation}", "(")).isTrue()
            Assertions.assertThat(find("\\p{IsPunctuation}", "A")).isFalse()
            assertMatchesJdk("\\p{IsPunctuation}", ".")
            assertMatchesJdk("\\p{IsPunctuation}", "A")
        }

        @Test
        @DisplayName("\\p{IsControl}")
        fun isControl() {
            Assertions.assertThat(find("\\p{IsControl}", "\u0001")).isTrue()
            Assertions.assertThat(find("\\p{IsControl}", "\u0000")).isTrue()
            Assertions.assertThat(find("\\p{IsControl}", "A")).isFalse()
            assertMatchesJdk("\\p{IsControl}", "\u0001")
            assertMatchesJdk("\\p{IsControl}", "A")
        }

        @Test
        @DisplayName("\\p{IsIdeographic}")
        fun isIdeographic() {
            Assertions.assertThat(find("\\p{IsIdeographic}", "\u4E00")).isTrue() // CJK
            Assertions.assertThat(find("\\p{IsIdeographic}", "A")).isFalse()
            assertMatchesJdk("\\p{IsIdeographic}", "\u4E00")
        }

        @Test
        @DisplayName("\\p{IsJoin_Control}")
        fun isJoinControl() {
            Assertions.assertThat(find("\\p{IsJoin_Control}", "\u200C")).isTrue() // ZWNJ
            Assertions.assertThat(find("\\p{IsJoin_Control}", "\u200D")).isTrue() // ZWJ
            Assertions.assertThat(find("\\p{IsJoin_Control}", "A")).isFalse()
            assertMatchesJdk("\\p{IsJoin_Control}", "\u200C")
        }

        @Test
        @DisplayName("\\p{IsNoncharacter_Code_Point}")
        fun isNoncharacterCodePoint() {
            Assertions.assertThat(find("\\p{IsNoncharacter_Code_Point}", "\uFDD0")).isTrue()
            Assertions.assertThat(find("\\p{IsNoncharacter_Code_Point}", "\uFDEF")).isTrue()
            Assertions.assertThat(find("\\p{IsNoncharacter_Code_Point}", "\uFFFE")).isTrue()
            Assertions.assertThat(find("\\p{IsNoncharacter_Code_Point}", "A")).isFalse()
            assertMatchesJdk("\\p{IsNoncharacter_Code_Point}", "\uFDD0")
        }

        @Test
        @DisplayName("\\p{IsAssigned}")
        fun isAssigned() {
            Assertions.assertThat(find("\\p{IsAssigned}", "A")).isTrue()
            assertMatchesJdk("\\p{IsAssigned}", "A")
        }

        @Test
        @DisplayName("\\p{IsEmoji}")
        fun isEmoji() {
            // U+1F600 GRINNING FACE
            val grin = String(Character.toChars(0x1F600))
            Assertions.assertThat(find("\\p{IsEmoji}", grin)).isTrue()
            assertMatchesJdk("\\p{IsEmoji}", grin)
        }

        @Test
        @DisplayName("\\p{IsEmoji_Presentation}")
        fun isEmojiPresentation() {
            val grin = String(Character.toChars(0x1F600))
            Assertions.assertThat(find("\\p{IsEmoji_Presentation}", grin)).isTrue()
            assertMatchesJdk("\\p{IsEmoji_Presentation}", grin)
        }

        @Test
        @DisplayName("\\p{IsExtended_Pictographic}")
        fun isExtendedPictographic() {
            val grin = String(Character.toChars(0x1F600))
            Assertions.assertThat(find("\\p{IsExtended_Pictographic}", grin)).isTrue()
            assertMatchesJdk("\\p{IsExtended_Pictographic}", grin)
        }
    }

    // =========================================================================
    // In prefix — blocks
    // =========================================================================
    @Nested
    @DisplayName("\\p{InBlock} — In prefix for Unicode blocks")
    internal inner class InBlockTest {
        @Test
        @DisplayName("\\p{InBasicLatin} matches ASCII range")
        fun inBasicLatinMatchesAscii() {
            Assertions.assertThat(find("\\p{InBasicLatin}", "A")).isTrue()
            Assertions.assertThat(find("\\p{InBasicLatin}", "z")).isTrue()
            Assertions.assertThat(find("\\p{InBasicLatin}", "0")).isTrue()
            Assertions.assertThat(find("\\p{InBasicLatin}", " ")).isTrue()
        }

        @Test
        @DisplayName("\\p{InBasicLatin} does not match non-ASCII")
        fun inBasicLatinDoesNotMatchNonAscii() {
            Assertions.assertThat(find("\\p{InBasicLatin}", "é")).isFalse()
            Assertions.assertThat(find("\\p{InBasicLatin}", "α")).isFalse()
        }

        @Test
        @DisplayName("\\p{InGreek} matches Greek and Coptic block")
        fun inGreekMatchesGreekBlock() {
            Assertions.assertThat(find("\\p{InGreek}", "α")).isTrue()
            Assertions.assertThat(find("\\p{InGreek}", "A")).isFalse()
        }

        @Test
        @DisplayName("\\p{InGreekandCoptic} matches Greek and Coptic block")
        fun inGreekAndCopticMatchesGreekBlock() {
            Assertions.assertThat(find("\\p{InGreekandCoptic}", "α")).isTrue()
            assertMatchesJdk("\\p{InGreekandCoptic}", "α")
        }

        @Test
        @DisplayName("\\p{InCyrillic} matches Cyrillic block")
        fun inCyrillicMatchesCyrillicBlock() {
            Assertions.assertThat(find("\\p{InCyrillic}", "Б")).isTrue()
            Assertions.assertThat(find("\\p{InCyrillic}", "A")).isFalse()
        }

        @Test
        @DisplayName("In prefix is case-insensitive for the block name")
        fun inPrefixCaseInsensitive() {
            Assertions.assertThat(find("\\p{InBASICLATIN}", "A")).isTrue()
            Assertions.assertThat(find("\\p{InBASIC_LATIN}", "A")).isTrue()
            Assertions.assertThat(find("\\p{Inbasiclatin}", "A")).isTrue()
        }

        @ParameterizedTest
        @CsvSource(
            "\\p{InBasicLatin}, A",
            "\\p{InBasicLatin}, z",
            "\\p{InCyrillic}, Б",
            "\\p{InBASICLATIN}, A",
            "\\p{Inbasiclatin}, A"
        )
        @DisplayName("In prefix blocks cross-validate against JDK")
        fun inBlockCrossValidation(regex: String, input: String) {
            assertMatchesJdk(regex, input)
        }
    }

    // =========================================================================
    // Keyword forms — script= / sc=
    // =========================================================================
    @Nested
    @DisplayName("\\p{script=X} and \\p{sc=X} — keyword forms for scripts")
    internal inner class ScriptKeywordTest {
        @Test
        @DisplayName("\\p{script=Latin} matches Latin characters")
        fun scriptEqualsLatin() {
            Assertions.assertThat(find("\\p{script=Latin}", "A")).isTrue()
            Assertions.assertThat(find("\\p{script=Latin}", "α")).isFalse()
            assertMatchesJdk("\\p{script=Latin}", "A")
            assertMatchesJdk("\\p{script=Latin}", "α")
        }

        @Test
        @DisplayName("\\p{sc=Latin} matches Latin characters")
        fun scEqualsLatin() {
            Assertions.assertThat(find("\\p{sc=Latin}", "A")).isTrue()
            Assertions.assertThat(find("\\p{sc=Latin}", "α")).isFalse()
            assertMatchesJdk("\\p{sc=Latin}", "A")
        }

        @Test
        @DisplayName("\\p{sc=Greek} matches Greek characters")
        fun scEqualsGreek() {
            Assertions.assertThat(find("\\p{sc=Greek}", "α")).isTrue()
            Assertions.assertThat(find("\\p{sc=Greek}", "A")).isFalse()
            assertMatchesJdk("\\p{sc=Greek}", "α")
        }

        @Test
        @DisplayName("Script keyword is case-insensitive for both key and value")
        fun scriptKeywordCaseInsensitive() {
            Assertions.assertThat(find("\\p{SCRIPT=Latin}", "A")).isTrue()
            Assertions.assertThat(find("\\p{Script=Latin}", "A")).isTrue()
            Assertions.assertThat(find("\\p{script=latin}", "A")).isTrue()
            Assertions.assertThat(find("\\p{SC=Latin}", "A")).isTrue()
        }

        @ParameterizedTest
        @CsvSource(
            "\\p{SCRIPT=Latin}, A", "\\p{script=latin}, A", "\\p{SC=Latin}, A"
        )
        @DisplayName("Script keyword cross-validates against JDK")
        fun scriptKeywordCrossValidation(regex: String, input: String) {
            assertMatchesJdk(regex, input)
        }
    }

    // =========================================================================
    // Keyword forms — block= / blk=
    // =========================================================================
    @Nested
    @DisplayName("\\p{block=X} and \\p{blk=X} — keyword forms for blocks")
    internal inner class BlockKeywordTest {
        @Test
        @DisplayName("\\p{block=BasicLatin} matches ASCII")
        fun blockEqualsBasicLatin() {
            Assertions.assertThat(find("\\p{block=BasicLatin}", "A")).isTrue()
            Assertions.assertThat(find("\\p{block=BasicLatin}", "é")).isFalse()
            assertMatchesJdk("\\p{block=BasicLatin}", "A")
        }

        @Test
        @DisplayName("\\p{blk=BasicLatin} matches ASCII")
        fun blkEqualsBasicLatin() {
            Assertions.assertThat(find("\\p{blk=BasicLatin}", "A")).isTrue()
            assertMatchesJdk("\\p{blk=BasicLatin}", "A")
        }

        @Test
        @DisplayName("\\p{block=GreekandCoptic} matches Greek block")
        fun blockEqualsGreek() {
            Assertions.assertThat(find("\\p{block=GreekandCoptic}", "α")).isTrue()
            assertMatchesJdk("\\p{block=GreekandCoptic}", "α")
        }

        @Test
        @DisplayName("Block keyword is case-insensitive")
        fun blockKeywordCaseInsensitive() {
            Assertions.assertThat(find("\\p{BLOCK=BasicLatin}", "A")).isTrue()
            Assertions.assertThat(find("\\p{Block=BasicLatin}", "A")).isTrue()
        }

        @ParameterizedTest
        @CsvSource(
            "\\p{block=BasicLatin}, A",
            "\\p{blk=BasicLatin}, A",
            "\\p{BLOCK=BasicLatin}, A",
            "\\p{block=GreekandCoptic}, α",
            "\\p{blk=GreekandCoptic}, α"
        )
        @DisplayName("Block keyword cross-validates against JDK")
        fun blockKeywordCrossValidation(regex: String, input: String) {
            assertMatchesJdk(regex, input)
        }
    }

    // =========================================================================
    // Keyword forms — general_category= / gc=
    // =========================================================================
    @Nested
    @DisplayName("\\p{general_category=X} and \\p{gc=X}")
    internal inner class CategoryKeywordTest {
        @Test
        @DisplayName("\\p{general_category=Lu} matches uppercase letters")
        fun gcLu() {
            Assertions.assertThat(find("\\p{general_category=Lu}", "A")).isTrue()
            Assertions.assertThat(find("\\p{general_category=Lu}", "a")).isFalse()
            assertMatchesJdk("\\p{general_category=Lu}", "A")
            assertMatchesJdk("\\p{general_category=Lu}", "a")
        }

        @Test
        @DisplayName("\\p{gc=Lu} matches uppercase letters")
        fun gcShortLu() {
            Assertions.assertThat(find("\\p{gc=Lu}", "A")).isTrue()
            Assertions.assertThat(find("\\p{gc=Lu}", "a")).isFalse()
            assertMatchesJdk("\\p{gc=Lu}", "A")
        }

        @Test
        @DisplayName("\\p{gc=Ll} matches lowercase letters")
        fun gcLl() {
            Assertions.assertThat(find("\\p{gc=Ll}", "a")).isTrue()
            Assertions.assertThat(find("\\p{gc=Ll}", "A")).isFalse()
            assertMatchesJdk("\\p{gc=Ll}", "a")
        }

        @Test
        @DisplayName("\\p{gc=L} matches all letters")
        fun gcL() {
            Assertions.assertThat(find("\\p{gc=L}", "A")).isTrue()
            Assertions.assertThat(find("\\p{gc=L}", "a")).isTrue()
            Assertions.assertThat(find("\\p{gc=L}", "1")).isFalse()
            assertMatchesJdk("\\p{gc=L}", "A")
        }

        @Test
        @DisplayName("\\p{gc=Nd} matches decimal digits")
        fun gcNd() {
            Assertions.assertThat(find("\\p{gc=Nd}", "5")).isTrue()
            Assertions.assertThat(find("\\p{gc=Nd}", "A")).isFalse()
            assertMatchesJdk("\\p{gc=Nd}", "5")
        }

        @Test
        @DisplayName("Category keyword is case-insensitive for the key")
        fun categoryKeywordCaseInsensitive() {
            Assertions.assertThat(find("\\p{GENERAL_CATEGORY=Lu}", "A")).isTrue()
            Assertions.assertThat(find("\\p{General_Category=Lu}", "A")).isTrue()
            Assertions.assertThat(find("\\p{GC=Lu}", "A")).isTrue()
        }

        @ParameterizedTest
        @CsvSource(
            "\\p{general_category=Lu}, A",
            "\\p{GENERAL_CATEGORY=Lu}, A",
            "\\p{gc=Lu}, A",
            "\\p{GC=Lu}, A",
            "\\p{gc=Ll}, a",
            "\\p{gc=L}, A",
            "\\p{gc=Nd}, 5"
        )
        @DisplayName("Category keyword cross-validates against JDK")
        fun categoryKeywordCrossValidation(regex: String, input: String) {
            assertMatchesJdk(regex, input)
        }
    }

    // =========================================================================
    // Negation — \P{...}
    // =========================================================================
    @Nested
    @DisplayName("Negation with \\P{}")
    internal inner class NegationTest {
        @Test
        @DisplayName("\\P{IsLatin} matches non-Latin characters")
        fun negatedIsLatin() {
            Assertions.assertThat(find("\\P{IsLatin}", "α")).isTrue()
            Assertions.assertThat(find("\\P{IsLatin}", "A")).isFalse()
            assertMatchesJdk("\\P{IsLatin}", "α")
            assertMatchesJdk("\\P{IsLatin}", "A")
        }

        @Test
        @DisplayName("\\P{InBasicLatin} matches non-ASCII characters")
        fun negatedInBasicLatin() {
            Assertions.assertThat(find("\\P{InBasicLatin}", "é")).isTrue()
            Assertions.assertThat(find("\\P{InBasicLatin}", "A")).isFalse()
            assertMatchesJdk("\\P{InBasicLatin}", "é")
        }

        @Test
        @DisplayName("\\P{script=Latin} matches non-Latin characters")
        fun negatedScriptKeyword() {
            Assertions.assertThat(find("\\P{script=Latin}", "α")).isTrue()
            Assertions.assertThat(find("\\P{script=Latin}", "A")).isFalse()
            assertMatchesJdk("\\P{script=Latin}", "α")
        }

        @Test
        @DisplayName("\\P{gc=Lu} matches non-uppercase characters")
        fun negatedGcKeyword() {
            Assertions.assertThat(find("\\P{gc=Lu}", "a")).isTrue()
            Assertions.assertThat(find("\\P{gc=Lu}", "A")).isFalse()
            assertMatchesJdk("\\P{gc=Lu}", "a")
        }

        @Test
        @DisplayName("\\P{IsAlphabetic} matches non-alphabetic characters")
        fun negatedBinaryProperty() {
            Assertions.assertThat(find("\\P{IsAlphabetic}", "1")).isTrue()
            Assertions.assertThat(find("\\P{IsAlphabetic}", "A")).isFalse()
            assertMatchesJdk("\\P{IsAlphabetic}", "1")
        }
    }

    // =========================================================================
    // Error cases
    // =========================================================================
    @Nested
    @DisplayName("Error cases")
    internal inner class ErrorCaseTest {
        @Test
        @DisplayName("lowercase 'is' prefix is not recognized")
        fun lowercaseIsPrefix() {
            // "isLatin" doesn't match any script/category, "is" is not a recognized prefix.
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile("\\p{isLatin}") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        @DisplayName("lowercase 'in' prefix is not recognized")
        fun lowercaseInPrefix() {
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile("\\p{inBasicLatin}") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @ParameterizedTest
        @ValueSource(
            strings = ["\\p{^IsLatin}", "\\p{^InBasicLatin}", "\\p{^script=Latin}", "\\p{^gc=Lu}", "\\p{^javaLowerCase}"
            ]
        )
        @DisplayName("Caret negation in property names is rejected")
        fun caretNegationInPropertyNameRejected(regex: String?) {
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) }).isInstanceOf(
                PatternSyntaxException::class.java
            )
        }

        @Test
        @DisplayName("Invalid script name throws")
        fun invalidScriptName() {
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile("\\p{IsNotARealScript}") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        @DisplayName("Invalid block name throws")
        fun invalidBlockName() {
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile("\\p{InNotARealBlock}") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        @DisplayName("Invalid binary property name throws")
        fun invalidBinaryProperty() {
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile("\\p{IsNotAProperty}") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        @DisplayName("Invalid keyword key throws")
        fun invalidKeywordKey() {
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile("\\p{foo=bar}") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        @DisplayName("Invalid keyword value throws")
        fun invalidKeywordValue() {
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile("\\p{script=NotAScript}") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        @DisplayName("Invalid block keyword value throws")
        fun invalidBlockKeywordValue() {
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile("\\p{block=NotABlock}") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        @DisplayName("Invalid gc keyword value throws")
        fun invalidGcKeywordValue() {
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile("\\p{gc=XX}") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }
    }

    // =========================================================================
    // In character class context — [...\p{...}...]
    // =========================================================================
    @Nested
    @DisplayName("Unicode properties inside character classes")
    internal inner class InCharClassTest {
        @Test
        @DisplayName("[\\p{IsLatin}] in character class")
        fun isLatinInCharClass() {
            Assertions.assertThat(find("[\\p{IsLatin}]", "A")).isTrue()
            Assertions.assertThat(find("[\\p{IsLatin}]", "α")).isFalse()
        }

        @Test
        @DisplayName("[\\p{InBasicLatin}] in character class")
        fun blockInCharClass() {
            Assertions.assertThat(find("[\\p{InBasicLatin}]", "A")).isTrue()
            Assertions.assertThat(find("[\\p{InBasicLatin}]", "é")).isFalse()
        }

        @Test
        @DisplayName("[\\p{IsAlphabetic}\\p{IsDigit}] union")
        fun binaryPropertyUnion() {
            Assertions.assertThat(find("[\\p{IsAlphabetic}\\p{IsDigit}]", "A")).isTrue()
            Assertions.assertThat(find("[\\p{IsAlphabetic}\\p{IsDigit}]", "5")).isTrue()
            Assertions.assertThat(find("[\\p{IsAlphabetic}\\p{IsDigit}]", ".")).isFalse()
        }
    }

    // =========================================================================
    // Bare JDK category forms
    // =========================================================================
    @Nested
    @DisplayName("Bare JDK category forms")
    internal inner class BareCategoryTest {
        @Test
        @DisplayName("\\p{Lu} still works (no prefix)")
        fun directCategoryName() {
            Assertions.assertThat(find("\\p{Lu}", "A")).isTrue()
            Assertions.assertThat(find("\\p{Lu}", "a")).isFalse()
        }

        @Test
        @DisplayName("\\p{L} still works (no prefix)")
        fun directMajorCategory() {
            Assertions.assertThat(find("\\p{L}", "A")).isTrue()
            Assertions.assertThat(find("\\p{L}", "1")).isFalse()
        }

        @Test
        @DisplayName("\\pL single-char syntax still works")
        fun singleCharSyntax() {
            Assertions.assertThat(find("\\pL", "A")).isTrue()
            Assertions.assertThat(find("\\pL", "1")).isFalse()
        }

        @Test
        @DisplayName("\\P{Lu} negation still works")
        fun directNegation() {
            Assertions.assertThat(find("\\P{Lu}", "a")).isTrue()
            Assertions.assertThat(find("\\P{Lu}", "A")).isFalse()
        }

        @Test
        @DisplayName("\\p{Cn} and \\P{Cn} match java.util.regex")
        fun unassignedCategoryMatchesJdk() {
            assertMatchesJdk("\\p{Cn}", "a")
            assertMatchesJdk("\\P{Cn}", "a")
            assertMatchesJdk("\\p{Cn}", "\uE000")
            assertMatchesJdk("\\P{Cn}", "\uE000")
        }

        @Test
        @DisplayName("\\p{javaLowerCase} still works")
        fun javaCharacterClass() {
            Assertions.assertThat(find("\\p{javaLowerCase}", "a")).isTrue()
            Assertions.assertThat(find("\\p{javaLowerCase}", "A")).isFalse()
        }

        @ParameterizedTest
        @ValueSource(strings = ["\\p{Latin}", "\\p{Braille}", "\\p{Any}"])
        @DisplayName("RE2-style bare Unicode property names are rejected")
        fun re2StyleBareUnicodePropertyNamesAreRejected(regex: String) {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile(regex) })
                .isInstanceOf(PatternSyntaxException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) }).isInstanceOf(
                PatternSyntaxException::class.java
            )
        }
    }
}
