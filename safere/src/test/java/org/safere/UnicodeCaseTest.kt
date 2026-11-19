// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.charset.StandardCharsets

/**
 * Tests for [Pattern.UNICODE_CASE] flag behavior.
 * 
 * 
 * `UNICODE_CASE` controls *how* case folding works (Unicode vs ASCII-only) when
 * [Pattern.CASE_INSENSITIVE] is also set. By itself, it should have no effect on matching.
 * 
 * 
 * `CASE_INSENSITIVE` alone folds ASCII literals and ranges only, matching the JDK. Add
 * `UNICODE_CASE` for Unicode-aware literal and range folding. Unicode case-sensitive
 * character properties such as `\p{Lu}`, `\p{Ll}`, and `\p{Lt}` are themselves
 * Unicode predicates, and JDK case-insensitive matching applies a Unicode cased-letter closure to
 * those predicates even when `UNICODE_CASE` is not set.
 */
internal class UnicodeCaseTest {
    @Test
    fun asciiCaseFoldingWithCaseInsensitive() {
        // Basic ASCII case folding — works the same in SafeRE and JDK.
        val p = Pattern.compile("hello", Pattern.CASE_INSENSITIVE)
        Assertions.assertThat(p.matcher("HELLO").matches()).isTrue()
        Assertions.assertThat(p.matcher("Hello").matches()).isTrue()
        Assertions.assertThat(p.matcher("hElLo").matches()).isTrue()
    }

    @Test
    fun unicodeCaseFoldingWithBothFlags() {
        // With both CASE_INSENSITIVE | UNICODE_CASE, Unicode folding should work.
        val p = Pattern.compile("café", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        Assertions.assertThat(p.matcher("CAFÉ").matches()).isTrue()
        Assertions.assertThat(p.matcher("Café").matches()).isTrue()
    }

    @Test
    fun unicodeCaseFoldingRequiresUnicodeCase() {
        val p = Pattern.compile("café", Pattern.CASE_INSENSITIVE)
        Assertions.assertThat(p.matcher("CAFÉ").matches()).isFalse()
        Assertions.assertThat(p.matcher("Café").matches()).isTrue()
    }

    @Test
    fun germanEszett() {
        // German ß — SafeRE folds case for non-ASCII characters.
        val p = Pattern.compile("straße", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        Assertions.assertThat(p.matcher("STRASSE").matches()).isFalse() // ß doesn't fold to SS in simple folding
        Assertions.assertThat(p.matcher("STRAßE").matches()).isTrue()
    }

    @Test
    fun greekLettersFolding() {
        val p = Pattern.compile("αβγ", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        Assertions.assertThat(p.matcher("ΑΒΓ").matches()).isTrue()
        Assertions.assertThat(p.matcher("αβγ").matches()).isTrue()
    }

    @Test
    fun cyrillicLettersFolding() {
        val p = Pattern.compile("привет", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        Assertions.assertThat(p.matcher("ПРИВЕТ").matches()).isTrue()
        Assertions.assertThat(p.matcher("Привет").matches()).isTrue()
    }

    @Test
    fun characterClassWithUnicodeCase() {
        val p = Pattern.compile("[à-ã]", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        Assertions.assertThat(p.matcher("á").matches()).isTrue()
        Assertions.assertThat(p.matcher("Á").matches()).isTrue()
        Assertions.assertThat(p.matcher("ã").matches()).isTrue()
        Assertions.assertThat(p.matcher("Ã").matches()).isTrue()
    }

    @Test
    fun characterClassUnicodeCaseRequiresUnicodeCase() {
        val p = Pattern.compile("[à-ã]", Pattern.CASE_INSENSITIVE)
        Assertions.assertThat(p.matcher("á").matches()).isTrue()
        Assertions.assertThat(p.matcher("Á").matches()).isFalse()
    }

    @Test
    fun findWithUnicodeCase() {
        val p = Pattern.compile("über", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        val m = p.matcher("Das ist ÜBER cool")
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.group()).isEqualTo("ÜBER")
    }

    @Test
    fun findWithShortMultiMemberSimpleFoldPrefix() {
        val p = Pattern.compile("(?iu)ϑϑ")
        val m = p.matcher("ϴϴ")
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.group()).isEqualTo("ϴϴ")
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = ["ß|ẞ", "ﬅ|ﬆ"])
    fun asciiCaseInsensitivePrefixKeepsNonAsciiLiteralCaseSensitive(
        patternCharacter: String?, foldedCharacter: String
    ) {
        val input = "A" + foldedCharacter

        Assertions.assertThat(Pattern.compile("(?i:a" + patternCharacter + ")").matcher(input).find()).isFalse()
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = ["ϑ|ϴ"])
    fun replacementFindsSingleCodeUnitUnicodeFoldedPrefix(
        patternCharacter: String?, foldedCharacter: String
    ) {
        assertReplacementFindsSingleCodeUnitUnicodeFoldedPrefix(patternCharacter, foldedCharacter)
    }

    @Test
    @DisabledForCrosscheck("SafeRE follows JDK 26 Unicode folding for ß and ẞ; JDK 21 through 25 differ")
    fun replacementFindsCapitalSharpSUnicodeFoldedPrefix() {
        assertReplacementFindsSingleCodeUnitUnicodeFoldedPrefix("ß", "ẞ")
    }

    @Test
    fun splitWithUnicodeCase() {
        val p = Pattern.compile("café", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        val parts = p.split("xCAFÉyCaféz")
        Assertions.assertThat<String?>(parts).containsExactly("x", "y", "z")
    }

    @Test
    fun replaceAllWithUnicodeCase() {
        val p = Pattern.compile("naïve", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        Assertions.assertThat(p.matcher("He is NAÏVE").replaceAll("smart")).isEqualTo("He is smart")
    }

    @Test
    fun replaceAllWithCapturedCaseInsensitiveLiteral() {
        val p = Pattern.compile("(?i)(abc)")
        Assertions.assertThat(p.matcher("ABC abc").replaceAll("x")).isEqualTo("x x")
    }

    @Test
    fun caseInsensitiveWithoutUnicodeCaseMatchesAscii() {
        // Even without UNICODE_CASE, ASCII folding works.
        val p = Pattern.compile("abc", Pattern.CASE_INSENSITIVE)
        Assertions.assertThat(p.matcher("ABC").matches()).isTrue()
        Assertions.assertThat(p.matcher("Abc").matches()).isTrue()
    }

    @Test
    fun unicodeCaseWithoutCaseInsensitiveDoesNotEnableFolding() {
        // UNICODE_CASE alone should NOT enable case folding — matches JDK behavior.
        val p = Pattern.compile("hello", Pattern.UNICODE_CASE)
        Assertions.assertThat(p.matcher("HELLO").matches()).isFalse()
        Assertions.assertThat(p.matcher("hello").matches()).isTrue()
    }

    @Test
    fun inlineFlagCaseInsensitive() {
        val p = Pattern.compile("(?i)café")
        Assertions.assertThat(p.matcher("CAFÉ").matches()).isFalse()
        Assertions.assertThat(p.matcher("Café").matches()).isTrue()
    }

    @Test
    fun inlineFlagUnicodeCaseEnablesUnicodeFolding() {
        val p = Pattern.compile("(?iu)café")
        Assertions.assertThat(p.matcher("CAFÉ").matches()).isTrue()
    }

    @Test
    fun turkishDotlessI() {
        // Java single-code-point casing joins dotted and dotless I to the I/i simple-fold family.
        val p = Pattern.compile("i", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        Assertions.assertThat(p.matcher("I").matches()).isTrue()
    }

    @ParameterizedTest
    @CsvSource(
        "AI, !A\u0131!",
        "AI, !A\u0130!",
        "AIB, !A\u0131B!",
        "ABCDI, !ABCD\u0131!",
        "\u0131A, !\u0131A!",
        "\u0130A, !iA!",
        "\u00e9I, !\u00e9\u0131!",
        "\u00e9i, !\u00e9\u0130!",
        "\u00e9ABCI, !\u00e9ABC\u0131!"
    )
    fun unicodeCaseFindsDottedAndDotlessIInLiteralPrefix(regex: String, input: String) {
        // Under UNICODE_CASE, I and i also match U+0130 and U+0131, which the simple-fold cycle used by
        // case-insensitive literal search does not visit.
        val flags = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        Assertions.assertThat(java.util.regex.Pattern.compile(regex, flags).matcher(input).find()).isTrue()

        val pattern = org.safere.Pattern.compile(regex, flags)
        val matcher = pattern.matcher(input)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(1)
        Assertions.assertThat(matcher.end()).isEqualTo(input.length - 1)
        Assertions.assertThat(pattern.matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))).find())
            .isTrue()
    }

    @ParameterizedTest
    @CsvSource(
        "\\p{Lu}, A, true",
        "\\p{Lu}, a, true",
        "\\p{Lu}, \u01C4, true",
        "\\p{Lu}, \u01C5, true",
        "\\p{Lu}, \u01C6, true",
        "\\p{Ll}, A, true",
        "\\p{Ll}, a, true",
        "\\p{Lt}, A, true",
        "\\p{Lt}, a, true",
        "\\p{Lt}, \u01C4, true",
        "\\p{Lt}, \u01C5, true",
        "\\p{Lt}, \u01C6, true",
        "\\P{Lt}, A, false",
        "[\\p{Lt}], a, true",
        "[^\\p{Lt}], a, false",
        "\\p{Lt}{4}, abcd, true",
        "\\p{javaUpperCase}, \u00AA, true",
        "\\p{javaLowerCase}, \u02B0, true",
        "\\p{javaTitleCase}, \u01C5, true",
        "\\p{IsUppercase}, \u0345, true",
        "\\p{IsLowercase}, \u2160, true",
        "\\p{Lt}, 1, false",
        "\\p{javaUpperCase}, 1, false",
        "\\P{Lt}, 1, true"
    )
    fun caseInsensitiveUnicodeLetterCategoriesUseCasedLetterClosure(
        regex: String?, input: String?, expected: Boolean
    ) {
        Assertions.assertThat(
            org.safere.Pattern.compile(regex, org.safere.Pattern.CASE_INSENSITIVE).matcher(input).matches()
        )
            .isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "[h-j], \u0130, true",
        "[h-j], \u0131, true",
        "[H-J], \u0131, true",
        "[^h-j], \u0130, false",
        "[A-Z], \u0130, true",
        "[I-I], \u0130, true",
        "[A-Z], \u212A, true",
        "[K-K], \u212A, true",
        "[a-z], \u212A, true",
        "[k-k], \u212A, true",
        "[A-Z], \u017F, true",
        "[S-S], \u017F, true",
        "[\\x{212A}], K, true",
        "[\\x{17F}], S, true",
        "\\x{49}, \u0130, true",
        "\\x{49}, \u0131, true",
        "\\x{69}, \u0130, true",
        "\\x{69}, \u0131, true"
    )
    @DisabledForCrosscheck("SafeRE intentionally follows Unicode case closure when observed JDK range behavior diverges")
    fun unicodeCaseInsensitiveRangesUseUnicodeCasedClosure(
        regex: String?, input: String?, expected: Boolean
    ) {
        val flags = org.safere.Pattern.CASE_INSENSITIVE or org.safere.Pattern.UNICODE_CASE
        Assertions.assertThat(org.safere.Pattern.compile(regex, flags).matcher(input).matches()).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "\\p{L}, \u0345, false",
        "\\P{L}, \u0345, true",
        "\\p{gc=L}, \u0345, false",
        "\\p{Lower}, \u212A, false",
        "\\p{ASCII}, \u212A, false"
    )
    fun caseInsensitivePropertiesDoNotUseRangeCaseClosure(
        regex: String?, input: String?, expected: Boolean
    ) {
        val flags = org.safere.Pattern.CASE_INSENSITIVE or org.safere.Pattern.UNICODE_CASE
        Assertions.assertThat(org.safere.Pattern.compile(regex, flags).matcher(input).matches()).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "\\p{L}, \u0345, false", "\\P{L}, \u0345, true", "\\p{Lower}, \u0345, true", "\\p{Upper}, \u0345, true"
    )
    fun unicodeCharacterClassPropertiesUseUnicodeCompatibilityPredicates(
        regex: String?, input: String?, expected: Boolean
    ) {
        val flags = org.safere.Pattern.CASE_INSENSITIVE or org.safere.Pattern.UNICODE_CHARACTER_CLASS
        Assertions.assertThat(org.safere.Pattern.compile(regex, flags).matcher(input).matches()).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource("[h-j], \u0130, false", "[h-j], \u0131, false", "[^h-j], \u0130, true")
    fun caseInsensitiveRangesWithoutUnicodeCaseKeepAsciiOnlyFolding(
        regex: String?, input: String?, expected: Boolean
    ) {
        Assertions.assertThat(
            org.safere.Pattern.compile(regex, org.safere.Pattern.CASE_INSENSITIVE).matcher(input).matches()
        )
            .isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "k, \u212A", "K, \u212A", "[k], \u212A", "[K], \u212A", "i, \u0130", "I, \u0131", "[i], \u0130", "[I], \u0131"
    )
    fun caseInsensitiveLiteralsWithoutUnicodeCaseDoNotUseUnicodeFolds(regex: String?, input: String?) {
        Assertions.assertThat(
            org.safere.Pattern.compile(regex, org.safere.Pattern.CASE_INSENSITIVE).matcher(input).matches()
        ).isFalse()
    }

    companion object {
        private fun assertReplacementFindsSingleCodeUnitUnicodeFoldedPrefix(
            patternCharacter: String?, foldedCharacter: String
        ) {
            val pattern = org.safere.Pattern.compile("(?iu)" + patternCharacter + ".")
            val input = "--" + foldedCharacter + "x--" + foldedCharacter + "y--"

            Assertions.assertThat(pattern.matcher(input).replaceFirst("z"))
                .isEqualTo("--z--" + foldedCharacter + "y--")
            Assertions.assertThat(pattern.matcher(input).replaceAll("z")).isEqualTo("--z--z--")
        }
    }
}
