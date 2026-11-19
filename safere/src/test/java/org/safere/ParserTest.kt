// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.regex.PatternSyntaxException

/** Tests for [Parser].  */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class ParserTest {
    // ---------------------------------------------------------------------------
    // 1. Literals
    // ---------------------------------------------------------------------------
    @Nested
    internal inner class Literals {
        @Test
        fun singleCharacter() {
            val re: Regexp = parse("a")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('a'.code)
        }

        @Test
        fun singleDigit() {
            val re: Regexp = parse("1")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('1'.code)
        }

        @Test
        fun singlePunctuation() {
            val re: Regexp = parse("!")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('!'.code)
        }

        @Test
        fun multiCharBecomesLiteralString() {
            val re: Regexp = parse("abc")
            // Parser coalesces adjacent literals into a LITERAL_STRING.
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL_STRING)
            Assertions.assertThat(re.toString()).isEqualTo("abc")
        }

        @Test
        fun fiveCharLiteralString() {
            val re: Regexp = parse("abcde")
            Assertions.assertThat(re.toString()).isEqualTo("abcde")
        }

        @Test
        fun escapedDot() {
            val re: Regexp = parse("\\.")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('.'.code)
        }

        @Test
        fun escapedStar() {
            val re: Regexp = parse("\\*")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('*'.code)
        }

        @Test
        fun escapedPlus() {
            val re: Regexp = parse("\\+")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('+'.code)
        }

        @Test
        fun escapedQuestion() {
            val re: Regexp = parse("\\?")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('?'.code)
        }

        @Test
        fun escapedBackslash() {
            val re: Regexp = parse("\\\\")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('\\'.code)
        }

        @Test
        fun escapedCaret() {
            val re: Regexp = parse("\\^")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('^'.code)
        }

        @Test
        fun escapedDollar() {
            val re: Regexp = parse("\\$")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('$'.code)
        }

        @Test
        fun escapedPipe() {
            val re: Regexp = parse("\\|")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('|'.code)
        }

        @Test
        fun escapedDash() {
            val re: Regexp = parse("\\-")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('-'.code)
        }

        @Test
        fun escapedUnderscore() {
            val re: Regexp = parse("\\_")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('_'.code)
        }

        @ParameterizedTest
        @ValueSource(strings = ["\\©", "\\é", "\\Ā", "\\☃", "\\😀"])
        fun escapedNonAsciiLiteral(regex: String) {
            val re: Regexp = parse(regex)
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo(regex.codePointAt(1))
        }

        @Test
        fun multipleEscapedPunctuation() {
            val re: Regexp = parse("\\.\\^\\$\\\\")
            Assertions.assertThat(re.toString()).isEqualTo("\\.\\^\\$\\\\")
        }

        @Test
        fun literalFlag_entirePatternAsLiteral() {
            val re: Regexp = parse("a.b*c", ParseFlags.LITERAL)
            Assertions.assertThat(re.toString()).isEqualTo("a\\.b\\*c")
        }

        @Test
        fun literalFlag_specialCharsNotInterpreted() {
            val re: Regexp = parse("[abc]+", ParseFlags.LITERAL)
            Assertions.assertThat(re.toString()).isEqualTo("\\[abc\\]\\+")
        }

        @Test
        fun dashLiteral() {
            val re: Regexp = parse("-")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('-'.code)
        }

        @Test
        fun escapedBrace() {
            val re: Regexp = parse("\\{")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('{'.code)
        }

        @Test
        fun literalCloseBrace() {
            val re: Regexp = parse("}")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('}'.code)
        }
    }

    // ---------------------------------------------------------------------------
    // 2. Character Classes
    // ---------------------------------------------------------------------------
    @Nested
    internal inner class CharacterClasses {
        @Test
        fun simpleCharClass() {
            val re: Regexp = parse("[abc]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('b'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('c'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('d'.code)).isFalse()
        }

        @Test
        fun rangeCharClass() {
            val re: Regexp = parse("[a-z]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('m'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('z'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('A'.code)).isFalse()
        }

        @Test
        fun multiRange() {
            val re: Regexp = parse("[0-9A-Fa-f]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('0'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('9'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('A'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('F'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('f'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('g'.code)).isFalse()
            Assertions.assertThat(re.charClass!!.contains('G'.code)).isFalse()
        }

        @Test
        fun negatedCharClass() {
            val re: Regexp = parse("[^abc]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isFalse()
            Assertions.assertThat(re.charClass!!.contains('b'.code)).isFalse()
            Assertions.assertThat(re.charClass!!.contains('d'.code)).isTrue()
        }

        @Test
        fun negatedRange() {
            val re: Regexp = parse("[^a-z]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isFalse()
            Assertions.assertThat(re.charClass!!.contains('z'.code)).isFalse()
            Assertions.assertThat(re.charClass!!.contains('A'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('0'.code)).isTrue()
        }

        @ParameterizedTest
        @ValueSource(
            strings = ["[&&-a]", "[^&&-a]", "[&&  -a]", "[&&a]", "[^&&a]", "[a&&]", "[^a&&]", "[a&&&b]", "[a&&&&b]", "[a&&\\Q\\E&&b]", "[a&&\\Q\\E]", "[\\Q\\E&&a]", "[a&&-b]", "(?x)[a&& -b]"
            ]
        )
        fun malformedCharacterClassIntersectionsAreRejected(pattern: String) {
            val flags = (if (pattern.contains(" ")) ParseFlags.COMMENTS else 0) or ParseFlags.MATCH_NL

            Assertions.assertThatThrownBy(ThrowingCallable { fullMatch(pattern, "a", flags) })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun literalAmpersandAndPrincipledIntersection() {
            // Single ampersand is a literal character
            Assertions.assertThat(fullMatch("[&]", "&", ParseFlags.MATCH_NL)).isTrue()
            Assertions.assertThat(fullMatch("[&]", "a", ParseFlags.MATCH_NL)).isFalse()
            Assertions.assertThat(fullMatch("[a&b]", "&", ParseFlags.MATCH_NL)).isTrue()
            Assertions.assertThat(fullMatch("[a&b]", "a", ParseFlags.MATCH_NL)).isTrue()
            Assertions.assertThat(fullMatch("[a&b]", "b", ParseFlags.MATCH_NL)).isTrue()
            Assertions.assertThat(fullMatch("[&-a]", "&", ParseFlags.MATCH_NL)).isTrue()
            Assertions.assertThat(fullMatch("[&-a]", "'", ParseFlags.MATCH_NL)).isTrue()

            // Infix intersection
            Assertions.assertThat(fullMatch("[a-z&&[def]]", "d", ParseFlags.MATCH_NL)).isTrue()
            Assertions.assertThat(fullMatch("[a-z&&[def]]", "a", ParseFlags.MATCH_NL)).isFalse()
            Assertions.assertThat(fullMatch("[a-z&&[^bc]]", "a", ParseFlags.MATCH_NL)).isTrue()
            Assertions.assertThat(fullMatch("[a-z&&[^bc]]", "b", ParseFlags.MATCH_NL)).isFalse()
            Assertions.assertThat(fullMatch("[a-z&&d-f&&e]", "e", ParseFlags.MATCH_NL)).isTrue()
            Assertions.assertThat(fullMatch("[a-z&&d-f&&e]", "d", ParseFlags.MATCH_NL)).isFalse()
        }

        @Test
        fun deeplyNestedCharacterClassesAreStackSafe() {
            val pattern: String = nestedCharacterClass(5000)

            Assertions.assertThat(fullMatch(pattern, "a", ParseFlags.LIKE_PERL)).isTrue()
            Assertions.assertThat(fullMatch(pattern, "b", ParseFlags.LIKE_PERL)).isFalse()
        }

        @Test
        @Tag("work-counter")
        fun manyPlainCharacterClassesParseWithLinearScaling() {
            val smaller: String = repeatedPlainCharacterClasses(8000)
            val larger: String = repeatedPlainCharacterClasses(32000)

            val smallerWork: Long = parseWork(smaller)
            val largerWork: Long = parseWork(larger)

            Assertions.assertThat(smallerWork).isPositive()
            Assertions.assertThat(largerWork)
                .`as`(
                    "4x input should stay near linear, smallerWork=%d largerWork=%d",
                    smallerWork, largerWork
                )
                .isLessThan(smallerWork * 5)
        }

        @Test
        fun singleCharInClass_optimizedToLiteral() {
            val re: Regexp = parse("[a]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('a'.code)
        }

        @Test
        fun mixedRangeAndLiterals() {
            val re: Regexp = parse("[a-zABC]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('A'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('B'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('C'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('z'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('D'.code)).isFalse()
        }

        // -- POSIX bracket-class spelling is ordinary JDK character-class text --
        @Test
        fun posixLowerSpellingIsLiteralText() {
            val re: Regexp = parse("[[:lower:]]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains(':'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('l'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('r'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isFalse()
            Assertions.assertThat(re.charClass!!.contains('z'.code)).isFalse()
        }

        @Test
        fun posixAlphaSpellingIsLiteralText() {
            val re: Regexp = parse("[[:alpha:]]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('p'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains(':'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('0'.code)).isFalse()
            Assertions.assertThat(re.charClass!!.contains('Z'.code)).isFalse()
        }

        @Test
        fun posixDigitSpellingIsLiteralText() {
            val re: Regexp = parse("[[:digit:]]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('d'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('t'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains(':'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('0'.code)).isFalse()
            Assertions.assertThat(re.charClass!!.contains('9'.code)).isFalse()
        }

        @Test
        fun posixNegatedSpellingIsLiteralText() {
            val re: Regexp = parse("[[:^space:]]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('^'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('s'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains(':'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains(' '.code)).isFalse()
            Assertions.assertThat(re.charClass!!.contains('x'.code)).isFalse()
        }

        @Test
        fun negatedPosixSpellingNegatesLiteralText() {
            val re: Regexp = parse("[^[:lower:]]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('l'.code)).isFalse()
            Assertions.assertThat(re.charClass!!.contains(':'.code)).isFalse()
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('A'.code)).isTrue()
        }

        // -- Perl shorthand classes --
        @Test
        fun perlDigit() {
            val re: Regexp = parse("\\d")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('0'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('9'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isFalse()
        }

        @Test
        fun perlNonDigit() {
            val re: Regexp = parse("\\D")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('0'.code)).isFalse()
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isTrue()
        }

        @Test
        fun perlSpace() {
            val re: Regexp = parse("\\s")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains(' '.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('\t'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isFalse()
        }

        @Test
        fun perlNonSpace() {
            val re: Regexp = parse("\\S")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains(' '.code)).isFalse()
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isTrue()
        }

        @Test
        fun perlWord() {
            val re: Regexp = parse("\\w")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('Z'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('0'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('_'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains(' '.code)).isFalse()
        }

        @Test
        fun perlNonWord() {
            val re: Regexp = parse("\\W")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isFalse()
            Assertions.assertThat(re.charClass!!.contains(' '.code)).isTrue()
        }

        @Test
        fun caseInsensitiveWord() {
            val re: Regexp = parse("(?i)\\w")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('Z'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('_'.code)).isTrue()
        }

        // -- Unicode property classes --
        @Test
        fun unicodePropertyGreekBlock() {
            val re: Regexp = parse("\\p{InGreek}")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains(0x0391)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('A'.code)).isFalse()
        }

        @Test
        fun unicodePropertyGreekBlockNegated() {
            val re: Regexp = parse("\\P{InGreek}")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains(0x0391)).isFalse()
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isTrue()
        }

        @Test
        fun unicodePropertyUppercaseLetter() {
            val re: Regexp = parse("\\p{Lu}")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('A'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isFalse()
        }

        @ParameterizedTest
        @ValueSource(strings = ["\\p{^Braille}", "\\p{^Lu}", "\\P{^Braille}"])
        fun unicodePropertyCaretNegationRejected(regex: String?) {
            Assertions.assertThatThrownBy(ThrowingCallable { parse(regex) })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun unicodePropertyLatinScript() {
            val re: Regexp = parse("\\p{IsLatin}")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('z'.code)).isTrue()
        }

        @ParameterizedTest
        @ValueSource(strings = ["\\p{Braille}", "\\P{Braille}", "\\p{Latin}", "[\\p{Latin}]"])
        fun bareUnicodeScriptAndBlockNamesRejected(pattern: String?) {
            Assertions.assertThatThrownBy(ThrowingCallable { parse(pattern) })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun unicodePropertyNdNegated() {
            val re: Regexp = parse("\\P{Nd}")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('0'.code)).isFalse()
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isTrue()
        }

        // -- Special character class contents --
        @Test
        fun escapeSequencesInClass() {
            val re: Regexp = parse("[\\n\\t]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('\n'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('\t'.code)).isTrue()
        }

        @ParameterizedTest
        @ValueSource(strings = ["[\\©]", "[\\é]", "[\\Ā]", "[\\☃]", "[\\😀]"])
        fun escapedNonAsciiLiteralInClass(regex: String) {
            val codePoint = regex.codePointAt(2)
            val re: Regexp = parse(regex)
            if (re.op == RegexpOp.LITERAL) {
                Assertions.assertThat(re.rune).isEqualTo(codePoint)
            } else {
                Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
                Assertions.assertThat(re.charClass!!.contains(codePoint)).isTrue()
            }
        }

        @Test
        fun hexInClass() {
            // [\\x41] is a single-char class → optimized to LITERAL 'A'
            val re: Regexp = parse("[\\x41]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('A'.code)
        }

        @Test
        fun dashAlone() {
            val re: Regexp = parse("[-]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('-'.code)
        }

        @Test
        fun dashAtEnd() {
            val re: Regexp = parse("[a-]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('-'.code)).isTrue()
        }

        @Test
        fun dashAtStart() {
            val re: Regexp = parse("[-a]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('-'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isTrue()
        }

        @Test
        fun negatedBackslash() {
            val re: Regexp = parse("[^\\\\]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('\\'.code)).isFalse()
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isTrue()
        }

        @Test
        fun charClassMatchesAll() {
            // [\s\S] matches every codepoint; parser may promote to ANY_CHAR.
            val re: Regexp = parse("[\\s\\S]")
            Assertions.assertThat<RegexpOp?>(re.op).isIn(RegexpOp.ANY_CHAR, RegexpOp.CHAR_CLASS)
        }
    }

    // ---------------------------------------------------------------------------
    // 3. Quantifiers
    // ---------------------------------------------------------------------------
    @Nested
    internal inner class Quantifiers {
        @Test
        fun star() {
            val re: Regexp = parse("a*")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.STAR)
            Assertions.assertThat<RegexpOp?>(re.sub().op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.sub().rune).isEqualTo('a'.code)
            Assertions.assertThat(re.nonGreedy()).isFalse()
        }

        @Test
        fun plus() {
            val re: Regexp = parse("a+")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.PLUS)
            Assertions.assertThat<RegexpOp?>(re.sub().op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.nonGreedy()).isFalse()
        }

        @Test
        fun quest() {
            val re: Regexp = parse("a?")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.QUEST)
            Assertions.assertThat<RegexpOp?>(re.sub().op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.nonGreedy()).isFalse()
        }

        @Test
        fun nonGreedyStar() {
            val re: Regexp = parse("a*?")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.STAR)
            Assertions.assertThat(re.nonGreedy()).isTrue()
        }

        @Test
        fun nonGreedyPlus() {
            val re: Regexp = parse("a+?")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.PLUS)
            Assertions.assertThat(re.nonGreedy()).isTrue()
        }

        @Test
        fun nonGreedyQuest() {
            val re: Regexp = parse("a??")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.QUEST)
            Assertions.assertThat(re.nonGreedy()).isTrue()
        }

        @Test
        fun repeatExact() {
            val re: Regexp = parse("a{2}")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.REPEAT)
            Assertions.assertThat(re.min).isEqualTo(2)
            Assertions.assertThat(re.max).isEqualTo(2)
        }

        @Test
        fun repeatRange() {
            val re: Regexp = parse("a{2,5}")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.REPEAT)
            Assertions.assertThat(re.min).isEqualTo(2)
            Assertions.assertThat(re.max).isEqualTo(5)
        }

        @Test
        fun repeatRange_2_3() {
            val re: Regexp = parse("a{2,3}")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.REPEAT)
            Assertions.assertThat(re.min).isEqualTo(2)
            Assertions.assertThat(re.max).isEqualTo(3)
        }

        @Test
        fun repeatUnbounded() {
            val re: Regexp = parse("a{3,}")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.REPEAT)
            Assertions.assertThat(re.min).isEqualTo(3)
            Assertions.assertThat(re.max).isEqualTo(-1)
        }

        @Test
        fun repeatNonGreedy() {
            val re: Regexp = parse("a{2,5}?")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.REPEAT)
            Assertions.assertThat(re.nonGreedy()).isTrue()
            Assertions.assertThat(re.min).isEqualTo(2)
            Assertions.assertThat(re.max).isEqualTo(5)
        }

        @Test
        fun repeatNonGreedyExact() {
            val re: Regexp = parse("a{2}?")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.REPEAT)
            Assertions.assertThat(re.nonGreedy()).isTrue()
            Assertions.assertThat(re.min).isEqualTo(2)
            Assertions.assertThat(re.max).isEqualTo(2)
        }

        @Test
        fun repeatNonGreedyUnbounded() {
            val re: Regexp = parse("a{2,}?")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.REPEAT)
            Assertions.assertThat(re.nonGreedy()).isTrue()
            Assertions.assertThat(re.min).isEqualTo(2)
            Assertions.assertThat(re.max).isEqualTo(-1)
        }

        @Test
        fun quantifierOnNonCapturingGroup() {
            val re: Regexp = parse("(?:ab)*")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.STAR)
            Assertions.assertThat<RegexpOp?>(re.sub().op).isEqualTo(RegexpOp.NON_CAPTURE)
            Assertions.assertThat(re.sub().sub().toString()).isEqualTo("ab")
        }

        @Test
        fun quantifierOnCapturingGroup() {
            val re: Regexp = parse("(ab)*")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.STAR)
            Assertions.assertThat<RegexpOp?>(re.sub().op).isEqualTo(RegexpOp.CAPTURE)
        }

        @Test
        fun quantifierOnCharClass() {
            val re: Regexp = parse("[a-z]+")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.PLUS)
            Assertions.assertThat<RegexpOp?>(re.sub().op).isEqualTo(RegexpOp.CHAR_CLASS)
        }

        @Test
        fun boundedRepeatOfConcatenatedBoundedRepeats() {
            val re: Regexp = parse("(?:a{0,63}b{0,99}){0,5}")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.REPEAT)
            Assertions.assertThat(re.min).isEqualTo(0)
            Assertions.assertThat(re.max).isEqualTo(5)
        }

        @Test
        fun countedRepeatAfterDeeplyNestedGroupedAtomIsStackSafe() {
            val re: Regexp = parse(nestedNonCapturingGroups(5000, "a") + "{2}")

            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.REPEAT)
            Assertions.assertThat(re.min).isEqualTo(2)
            Assertions.assertThat(re.max).isEqualTo(2)
        }

        @Test
        fun possessiveRepeatAfterDeeplyNestedZeroCountOperandIsStackSafe() {
            val re: Regexp = parse(nestedNonCapturingGroups(5000, "a{0}") + "++")

            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.PLUS)
        }

        @Test
        fun starAfterEscapedBrace() {
            val re: Regexp = parse("a*\\{")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CONCAT)
        }

        @ParameterizedTest
        @ValueSource(strings = ["^+?+", "$*?+", "\\b+?+", "()+?+", "a+?+"])
        fun danglingQuantifierOnReluctantQuantifierRejected(pattern: String?) {
            Assertions.assertThatThrownBy(ThrowingCallable { parse(pattern) })
                .isInstanceOf(PatternSyntaxException::class.java)
        }
    }

    // ---------------------------------------------------------------------------
    // 4. Groups
    // ---------------------------------------------------------------------------
    @Nested
    internal inner class Groups {
        @Test
        fun capturingGroup() {
            val re: Regexp = parse("(a)")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CAPTURE)
            Assertions.assertThat(re.cap).isEqualTo(1)
            Assertions.assertThat(re.name).isNull()
            Assertions.assertThat<RegexpOp?>(re.sub().op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.sub().rune).isEqualTo('a'.code)
        }

        @Test
        fun capturingGroupWithAlternation() {
            val re: Regexp = parse("(ab|cd)")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CAPTURE)
            Assertions.assertThat<RegexpOp?>(re.sub().op).isEqualTo(RegexpOp.ALTERNATE)
        }

        @Test
        fun nonCapturingGroup_preservesSourceBoundary() {
            val re: Regexp = parse("(?:a)")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.NON_CAPTURE)
            Assertions.assertThat<RegexpOp?>(re.sub().op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.sub().rune).isEqualTo('a'.code)
        }

        @Test
        fun nonCapturingGroup_simplifiesAway() {
            val re: Regexp = simplify("(?:a)")!!
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('a'.code)
        }

        @Test
        fun nonCapturingGroupConcat() {
            // (?:ab)(?:cd) should coalesce to "abcd"
            val re: Regexp = simplify("(?:ab)(?:cd)")!!
            Assertions.assertThat(re.toString()).isEqualTo("abcd")
        }

        @Test
        fun nonCapturingGroupWithAlternation() {
            val re: Regexp = simplify("(?:ab|cd)")!!
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.ALTERNATE)
        }

        @Test
        fun namedCapture_pythonSyntax() {
            val re: Regexp = parse("(?P<name>a)")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CAPTURE)
            Assertions.assertThat(re.name).isEqualTo("name")
        }

        @Test
        fun namedCapture_angleBracketSyntax() {
            val re: Regexp = parse("(?<name>a)")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CAPTURE)
            Assertions.assertThat(re.name).isEqualTo("name")
        }

        @Test
        fun namedCapture_unicodeName_rejected() {
            // JDK only allows ASCII letters/digits in group names.
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(?<\u4e2d\u6587>a)") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun nestedCaptures() {
            val re: Regexp = parse("((a)(b))")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CAPTURE)
            Assertions.assertThat(re.cap).isEqualTo(1)
            val inner = re.sub()
            Assertions.assertThat<RegexpOp?>(inner.op).isEqualTo(RegexpOp.CONCAT)
            Assertions.assertThat<Regexp?>(inner.subs).hasSize(2)
            Assertions.assertThat<RegexpOp?>(inner.subs!!.get(0)!!.op).isEqualTo(RegexpOp.CAPTURE)
            Assertions.assertThat(inner.subs!!.get(0)!!.cap).isEqualTo(2)
            Assertions.assertThat<RegexpOp?>(inner.subs!!.get(1)!!.op).isEqualTo(RegexpOp.CAPTURE)
            Assertions.assertThat(inner.subs!!.get(1)!!.cap).isEqualTo(3)
        }

        @Test
        fun neverCapture_flag() {
            val re: Regexp = parse("(a)", PERL or ParseFlags.NEVER_CAPTURE)
            // With NEVER_CAPTURE, all parens are non-capturing, so it simplifies to literal.
            Assertions.assertThat<RegexpOp?>(re.op).isNotEqualTo(RegexpOp.CAPTURE)
        }

        @Test
        fun multipleCaptures() {
            val re: Regexp = parse("(a)(b)")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CONCAT)
            Assertions.assertThat<RegexpOp?>(re.subs!!.get(0)!!.op).isEqualTo(RegexpOp.CAPTURE)
            Assertions.assertThat(re.subs!!.get(0)!!.cap).isEqualTo(1)
            Assertions.assertThat<RegexpOp?>(re.subs!!.get(1)!!.op).isEqualTo(RegexpOp.CAPTURE)
            Assertions.assertThat(re.subs!!.get(1)!!.cap).isEqualTo(2)
        }
    }

    // ---------------------------------------------------------------------------
    // 5. Alternation
    // ---------------------------------------------------------------------------
    @Nested
    internal inner class Alternation {
        @Test
        fun twoSingleChars() {
            // a|b may be optimized to a char class by the parser.
            val re: Regexp = parse("a|b")
            Assertions.assertThat<RegexpOp?>(re.op).isIn(RegexpOp.ALTERNATE, RegexpOp.CHAR_CLASS)
        }

        @Test
        fun threeSingleChars() {
            val re: Regexp = parse("a|b|c")
            Assertions.assertThat<RegexpOp?>(re.op).isIn(RegexpOp.ALTERNATE, RegexpOp.CHAR_CLASS)
        }

        @Test
        fun complexAlternation() {
            val re: Regexp = parse("ab|cd")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.ALTERNATE)
            Assertions.assertThat<Regexp?>(re.subs).hasSize(2)
        }

        @Test
        fun alternationWithGroup() {
            val re: Regexp = parse("(a|b)c")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CONCAT)
        }

        @Test
        fun emptyAlternation() {
            val re: Regexp = parse("|")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.ALTERNATE)
        }

        @Test
        fun emptyMiddle() {
            val re: Regexp = parse("|x|")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.ALTERNATE)
        }

        @Test
        fun alternationWithCapture() {
            val re: Regexp = parse("(a)|b")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.ALTERNATE)
        }

        @Test
        fun alternationWithCharClass() {
            // [ab]|c may be merged into a single char class.
            val re: Regexp = parse("[ab]|c")
            Assertions.assertThat<RegexpOp?>(re.op).isIn(RegexpOp.ALTERNATE, RegexpOp.CHAR_CLASS)
        }

        @Test
        fun alternationWithDot() {
            // a|. — the parser may or may not simplify this depending on optimizations.
            val re: Regexp = parse("a|.", RE2_TEST)
            Assertions.assertThat<RegexpOp?>(re.op).isIn(RegexpOp.ANY_CHAR, RegexpOp.ALTERNATE, RegexpOp.CHAR_CLASS)
        }

        @Test
        fun dotAlternationDot() {
            val re: Regexp = parse(".|.", RE2_TEST)
            Assertions.assertThat<RegexpOp?>(re.op).isIn(RegexpOp.ANY_CHAR, RegexpOp.ALTERNATE, RegexpOp.CHAR_CLASS)
        }

        @Test
        fun alternationMergesCharClasses() {
            // (?:a|b)|(?:c|d) should merge into a single char class.
            val re: Regexp = parse("(?:a|b)|(?:c|d)")
            Assertions.assertThat<RegexpOp?>(re.op).isIn(RegexpOp.ALTERNATE, RegexpOp.CHAR_CLASS)
        }

        @Test
        fun anchorAlternation() {
            // a|^ mixes a literal and an anchor.
            val re: Regexp = parse("a|^", RE2_TEST)
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.ALTERNATE)
        }
    }

    // ---------------------------------------------------------------------------
    // 6. Anchors
    // ---------------------------------------------------------------------------
    @Nested
    internal inner class Anchors {
        @Test
        fun caret_withOneLine() {
            // LIKE_PERL includes ONE_LINE, so ^ → BEGIN_TEXT.
            val re: Regexp = parse("^")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.BEGIN_TEXT)
        }

        @Test
        fun dollar_withOneLine() {
            val re: Regexp = parse("$")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.END_TEXT)
        }

        @Test
        fun caret_multiline() {
            // (?m) clears ONE_LINE, so ^ → BEGIN_LINE.
            val re: Regexp = parse("(?m)^")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.BEGIN_LINE)
        }

        @Test
        fun dollar_multiline() {
            val re: Regexp = parse("(?m)$")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.END_LINE)
        }

        @Test
        fun caret_withoutOneLine() {
            // When ONE_LINE is not set, ^ → BEGIN_LINE.
            val re: Regexp = parse("^", RE2_TEST)
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.BEGIN_LINE)
        }

        @Test
        fun dollar_withoutOneLine() {
            val re: Regexp = parse("$", RE2_TEST)
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.END_LINE)
        }

        @Test
        fun beginText_explicit() {
            val re: Regexp = parse("\\A")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.BEGIN_TEXT)
        }

        @Test
        fun endText_explicit() {
            val re: Regexp = parse("\\z")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.END_TEXT)
        }

        @Test
        fun wordBoundary() {
            val re: Regexp = parse("\\b")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.WORD_BOUNDARY)
        }

        @Test
        fun noWordBoundary() {
            val re: Regexp = parse("\\B")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.NO_WORD_BOUNDARY)
        }

        @Test
        fun anchorCombinedWithLiteral() {
            val re: Regexp = parse("^abc$")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CONCAT)
        }
    }

    // ---------------------------------------------------------------------------
    // 7. Dot
    // ---------------------------------------------------------------------------
    @Nested
    internal inner class Dot {
        @Test
        fun singleDot() {
            // Without DOT_NL, dot becomes a CHAR_CLASS that excludes '\n'.
            val re: Regexp = parse(".")
            Assertions.assertThat<RegexpOp?>(re.op).isIn(RegexpOp.ANY_CHAR, RegexpOp.CHAR_CLASS)
        }

        @Test
        fun singleDot_withDotNL() {
            val re: Regexp = parse(".", PERL or ParseFlags.DOT_NL)
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.ANY_CHAR)
        }

        @Test
        fun dotInConcat() {
            val re: Regexp = parse("a.b")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CONCAT)
            Assertions.assertThat<Regexp?>(re.subs).hasSize(3)
            Assertions.assertThat<RegexpOp?>(re.subs!!.get(0)!!.op).isEqualTo(RegexpOp.LITERAL)
            // Dot is CHAR_CLASS (excluding \n) when DOT_NL is off.
            Assertions.assertThat<RegexpOp?>(re.subs!!.get(1)!!.op).isIn(RegexpOp.ANY_CHAR, RegexpOp.CHAR_CLASS)
            Assertions.assertThat<RegexpOp?>(re.subs!!.get(2)!!.op).isEqualTo(RegexpOp.LITERAL)
        }

        @Test
        fun dotInLargerPattern() {
            val re: Regexp = parse("a.b.c")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CONCAT)
            Assertions.assertThat<Regexp?>(re.subs).hasSize(5)
        }

        @Test
        fun dotWithSFlag() {
            // (?s) enables DOT_NL.
            val re: Regexp = parse("(?s).")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.ANY_CHAR)
        }
    }

    // ---------------------------------------------------------------------------
    // 8. Escape Sequences
    // ---------------------------------------------------------------------------
    @Nested
    internal inner class EscapeSequences {
        @Test
        fun newline() {
            val re: Regexp = parse("\\n")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('\n'.code)
        }

        @Test
        fun carriageReturn() {
            val re: Regexp = parse("\\r")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('\r'.code)
        }

        @Test
        fun tab() {
            val re: Regexp = parse("\\t")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('\t'.code)
        }

        @Test
        fun formFeed() {
            val re: Regexp = parse("\\f")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo(0x0C)
        }

        @Test
        fun bell() {
            val re: Regexp = parse("\\a")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo(0x07)
        }

        @Test
        fun hexTwoDigit() {
            val re: Regexp = parse("\\x41")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('A'.code)
        }

        @Test
        fun hexBraces() {
            val re: Regexp = parse("\\x{1F600}")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo(0x1F600)
        }

        @Test
        fun hexBracesSmall() {
            val re: Regexp = parse("\\x{61}")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('a'.code)
        }

        @Test
        fun quotedLiteral_specialChars() {
            val re: Regexp = parse("\\Q+|*?{[\\E")
            Assertions.assertThat(re.toString()).isEqualTo("\\+\\|\\*\\?\\{\\[")
        }

        @Test
        fun quotedLiteral_thenPlus() {
            val re: Regexp = parse("\\Q+\\E+")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.PLUS)
            Assertions.assertThat(re.sub().rune).isEqualTo('+'.code)
        }

        @Test
        fun quotedBackslash() {
            val re: Regexp = parse("\\Q\\\\E")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('\\'.code)
        }

        @Test
        fun quotedLiteral_thenStar() {
            val re: Regexp = parse("\\Qa\\E*")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.STAR)
            Assertions.assertThat(re.sub().rune).isEqualTo('a'.code)
        }

        @Test
        fun quotedLiteral_twoCharsThenStar() {
            // \Qab\E* → cat{lit{a}star{lit{b}}}
            val re: Regexp = parse("\\Qab\\E*")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CONCAT)
        }

        // ---- Octal escapes ----
        @Test
        fun octal_nul() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("\\0") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun octal_singleDigit() {
            val re: Regexp = parse("\\07")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo(7)
        }

        @Test
        fun octal_twoDigits() {
            val re: Regexp = parse("\\012")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('\n'.code) // 012 octal = 10 = newline
        }

        @Test
        fun octal_maxTwoDigitsAfterZero() {
            val re: Regexp = parse("\\077")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo(63) // 077 octal = 63
        }

        @ParameterizedTest
        @ValueSource(strings = ["\\1", "\\9", "\\12", "\\123", "\\1*", "\\12*", "\\2222*", "3*\\2222*"])
        fun numericBackreferenceWithoutGroupRejected(pattern: String?) {
            Assertions.assertThatThrownBy(ThrowingCallable { parse(pattern) })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @ParameterizedTest
        @ValueSource(strings = ["(a)\\1", "(a)\\12", "(a)\\123", "(a)(b)\\12"])
        fun numericBackreferenceWithGroupRejected(pattern: String?) {
            Assertions.assertThatThrownBy(ThrowingCallable { parse(pattern) })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @ParameterizedTest
        @ValueSource(strings = ["[\\1]", "[\\9]", "[\\12]", "[\\123]"])
        fun numericBackreferenceInCharClassRejected(pattern: String?) {
            Assertions.assertThatThrownBy(ThrowingCallable { parse(pattern) })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        // ---- Escape char and control chars ----
        @Test
        fun escape() {
            val re: Regexp = parse("\\e")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo(0x1B)
        }

        @Test
        fun controlChar_A() {
            val re: Regexp = parse("\\cA")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo(0x01) // ctrl-A
        }

        @Test
        fun controlChar_Z() {
            val re: Regexp = parse("\\cZ")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo(0x1A) // ctrl-Z
        }

        @Test
        fun controlChar_at() {
            val re: Regexp = parse("\\c@")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo(0x00) // ctrl-@ = NUL
        }

        @Test
        fun controlChar_openBracket() {
            val re: Regexp = parse("\\c[")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo(0x1B) // ctrl-[ = ESC
        }

        // ---- Named Unicode character ----
        @Test
        fun namedUnicode_latinA() {
            val re: Regexp = parse("\\N{LATIN SMALL LETTER A}")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('a'.code)
        }

        @Test
        fun namedUnicode_smiley() {
            val re: Regexp = parse("\\N{WHITE SMILING FACE}")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo(0x263A)
        }

        @Test
        fun namedUnicode_unknown() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("\\N{NOT A REAL NAME}") })
                .isInstanceOf(PatternSyntaxException::class.java)
                .hasMessageContaining("unknown Unicode character name")
        }

        @Test
        fun namedUnicode_missingBrace() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("\\N{LATIN SMALL LETTER A") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }
    }

    // ---------------------------------------------------------------------------
    // 9. Flags
    // ---------------------------------------------------------------------------
    @Nested
    internal inner class Flags {
        @Test
        fun caseInsensitive_inline() {
            Assertions.assertThat(fullMatch("(?i)a", "A", PERL)).isTrue()
            Assertions.assertThat(fullMatch("(?i)a", "b", PERL)).isFalse()
        }

        @Test
        fun caseInsensitive_scoped() {
            Assertions.assertThat(fullMatch("(?i:abc)", "ABC", PERL)).isTrue()
            Assertions.assertThat(fullMatch("(?i:abc)", "ABD", PERL)).isFalse()
        }

        @Test
        fun caseInsensitive_turnedOff() {
            Assertions.assertThat(fullMatch("(?i)a(?-i)b", "Ab", PERL)).isTrue()
            Assertions.assertThat(fullMatch("(?i)a(?-i)b", "AB", PERL)).isFalse()
        }

        @Test
        fun dotMatchesNewline_sFlag() {
            val re: Regexp = parse("(?s).")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.ANY_CHAR)
        }

        @Test
        fun multiline_mFlag() {
            val re: Regexp = parse("(?m)^")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.BEGIN_LINE)
        }

        @Test
        fun multiline_mFlag_dollar() {
            val re: Regexp = parse("(?m)$")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.END_LINE)
        }

        @Test
        fun unicodeCharClass_UFlag() {
            // (?U) is JDK's UNICODE_CHARACTER_CLASS, not RE2's non-greedy.
            val re: Regexp = parse("(?U)a*")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.STAR)
            Assertions.assertThat(re.nonGreedy()).isFalse()
            Assertions.assertThat((re.flags and ParseFlags.UNICODE_CHAR_CLASS) != 0).isTrue()
        }

        @Test
        fun combinedFlags() {
            Assertions.assertThat(fullMatch("(?is)a.b", "A\nb", PERL)).isTrue()
            Assertions.assertThat(fullMatch("(?is)a.b", "a\nc", PERL)).isFalse()
        }

        @Test
        fun turnOffMultiline() {
            // (?-m)^ should be BEGIN_TEXT when ONE_LINE is restored.
            val re: Regexp = parse("(?-m)^", RE2_TEST)
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.BEGIN_TEXT)
        }

        @Test
        fun turnOffMultiline_dollar() {
            val re: Regexp = parse("(?-m)$", RE2_TEST)
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.END_TEXT)
        }

        @Test
        fun caseInsensitive_charClass() {
            // (?i)[a-z] should include uppercase letters.
            val re: Regexp = parse("(?i)[a-z]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('A'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('Z'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isTrue()
        }

        @Test
        fun caseInsensitive_posixLowerSpellingIsLiteralText() {
            val re: Regexp = parse("(?i)[[:lower:]]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
            Assertions.assertThat(re.charClass!!.contains('L'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('l'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains(':'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('A'.code)).isFalse()
            Assertions.assertThat(re.charClass!!.contains('a'.code)).isFalse()
        }
    }

    // ---------------------------------------------------------------------------
    // 10. Parse Errors
    // ---------------------------------------------------------------------------
    @Nested
    internal inner class ParseErrors {
        @Test
        fun missingCloseParen() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(abc") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun missingCloseBracket() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("[abc") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun unexpectedCloseParen() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("abc)") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun nothingToRepeat_star() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("*") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun nothingToRepeat_plus() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("+abc") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @ParameterizedTest
        @ValueSource(strings = ["{", "{?", "a{?", "a{,2}", "a{x}", "\\\\Q{?\\\\E"])
        fun malformedUnescapedCountedRepetition(pattern: String?) {
            Assertions.assertThatThrownBy(ThrowingCallable { parse(pattern) })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun trailingBackslash() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("\\") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun repeatTooLarge_1001() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("a{1001}") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun repeatTooLarge_unbounded() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("a{1001,}") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun repeatTooLarge_huge() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("a{100000}") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun repeatTooLarge_hugeUnbounded() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("a{100000,}") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun pythonStyleNamedCapture_emptyNameRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(?P<>a)") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun invalidNamedCapture_empty_angle() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(?<>a)") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun pythonStyleNamedCapture_spaceInNameRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(?P<x y>a)") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun invalidNamedCapture_spaceInName_angle() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(?<x y>a)") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun invalidNamedCapture_underscore() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(?<test_group>.*)") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun pythonStyleNamedCapture_underscoreAccepted() {
            val re: Regexp = parse("(?P<test_group>.*)")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CAPTURE)
            Assertions.assertThat(re.name).isEqualTo("test_group")
        }

        @Test
        fun invalidNamedCapture_startsWithDigit() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(?<1abc>a)") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun pythonStyleNamedCapture_startsWithDigitRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(?P<1abc>a)") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun invalidNamedCapture_unicodeLetter() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(?<caf\u00e9>a)") }).isInstanceOf(
                PatternSyntaxException::class.java
            )
        }

        @Test
        fun invalidNamedCapture_connectorPunctuation() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(?<foo\u203fbar>a)") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun invalidNamedCapture_allDigits() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(?<123>a)") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun invalidPerlOp() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(?z)") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun unclosedGroup_withAlternation() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(a|b|") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @ParameterizedTest
        @ValueSource(strings = ["a|(", "|(", "(a|b)|(", "a|b|(", "a|(?:b)|("])
        fun unclosedGroup_afterAlternationBoundary(pattern: String?) {
            Assertions.assertThatThrownBy(ThrowingCallable { parse(pattern) })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @ParameterizedTest
        @ValueSource(
            strings = ["a#\u0000|(", "#\u0000(", "a#\u0000b|(", "a#\u0000(?:b)|(", "a#\r(", "a#\u0085(", "a#\u2028(", "a#\u2029("
            ]
        )
        fun unclosedGroup_afterCommentsModeTerminatedComment(pattern: String?) {
            Assertions.assertThatThrownBy(ThrowingCallable { parse(pattern, PERL or ParseFlags.COMMENTS) })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @ParameterizedTest
        @ValueSource(strings = ["a#\u0000|(", "#\u0000("])
        fun unclosedGroup_afterUnixLinesCommentsModeNulTerminatedComment(pattern: String?) {
            Assertions.assertThatThrownBy(ThrowingCallable {
                parse(
                    pattern,
                    PERL or ParseFlags.COMMENTS or ParseFlags.UNIX_LINES
                )
            })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun unclosedGroup_partial() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(a|b") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun mismatchedBrackets() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("([a-z)") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun pythonStyleNamedCapture_unclosedGroupRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(?P<name>a") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun unclosedNamedCapture_angle() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(?<name>a") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun invalidCharRange() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("[z-a]") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun invalidCharRange_caseInsensitive() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(?i)[a-Z]") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @ParameterizedTest
        @ValueSource(strings = ["a**", "a++", "a+*", "a?*"])
        fun invalidNestedRepetition(pattern: String?) {
            Assertions.assertThatThrownBy(ThrowingCallable { parse(pattern) })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun unclosedParen_solo() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun unexpectedCloseParen_solo() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse(")") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun pythonStyleNamedCapture_incompleteRejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(?P<name") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun incompleteNamedCapture_angle() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(?<name") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        fun unclosedGroup_solo_a() {
            Assertions.assertThatThrownBy(ThrowingCallable { parse("(a") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }
    }

    // ---------------------------------------------------------------------------
    // 11. Edge Cases
    // ---------------------------------------------------------------------------
    @Nested
    internal inner class EdgeCases {
        @Test
        fun emptyPattern() {
            val re: Regexp = parse("")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.EMPTY_MATCH)
        }

        @Test
        fun singleDot() {
            // Without DOT_NL, dot is a CHAR_CLASS excluding '\n'.
            val re: Regexp = parse(".")
            Assertions.assertThat<RegexpOp?>(re.op).isIn(RegexpOp.ANY_CHAR, RegexpOp.CHAR_CLASS)
        }

        @Test
        fun supplementaryUnicode_hex() {
            val re: Regexp = parse("\\x{1F600}")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo(0x1F600)
        }

        @Test
        fun supplementaryUnicode_inClass() {
            val re: Regexp = parse("[\\x{1F600}]")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo(0x1F600)
        }

        @Test
        fun nestedRepetition_starStar() {
            // (?:(?:a)*)* simplifies to a*
            val re: Regexp = simplify("(?:(?:a)*)*")!!
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.STAR)
            Assertions.assertThat<RegexpOp?>(re.sub().op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.sub().rune).isEqualTo('a'.code)
        }

        @Test
        fun nestedRepetition_plusPlus() {
            // (?:(?:a)+)+ simplifies to a+
            val re: Regexp = simplify("(?:(?:a)+)+")!!
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.PLUS)
            Assertions.assertThat<RegexpOp?>(re.sub().op).isEqualTo(RegexpOp.LITERAL)
        }

        @Test
        fun nestedRepetition_questQuest() {
            // (?:(?:a)?)? simplifies to a?
            val re: Regexp = simplify("(?:(?:a)?)?")!!
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.QUEST)
        }

        @Test
        fun nestedRepetition_starPlus() {
            // (?:(?:a)*)+ simplifies to a*
            val re: Regexp = simplify("(?:(?:a)*)+")!!
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.STAR)
        }

        @Test
        fun nestedRepetition_starQuest() {
            // (?:(?:a)*)? simplifies to a*
            val re: Regexp = simplify("(?:(?:a)*)?")!!
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.STAR)
        }

        @Test
        fun nestedRepetition_plusStar() {
            // (?:(?:a)+)* simplifies to a*
            val re: Regexp = simplify("(?:(?:a)+)*")!!
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.STAR)
        }

        @Test
        fun nestedRepetition_plusQuest() {
            // (?:(?:a)+)? simplifies to a*
            val re: Regexp = simplify("(?:(?:a)+)?")!!
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.STAR)
        }

        @Test
        fun nestedRepetition_questStar() {
            // (?:(?:a)?)* simplifies to a*
            val re: Regexp = simplify("(?:(?:a)?)*")!!
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.STAR)
        }

        @Test
        fun nestedRepetition_questPlus() {
            // (?:(?:a)?)+ simplifies to a*
            val re: Regexp = simplify("(?:(?:a)?)+")!!
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.STAR)
        }

        @Test
        fun nonCapturingNoop() {
            val re: Regexp = simplify("(?:a)")!!
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat(re.rune).isEqualTo('a'.code)
        }

        @Test
        fun escapedBraceWithCommaIsLiteral() {
            val re: Regexp = parse("a\\{,2}")
            Assertions.assertThat(re.toString()).isEqualTo("a\\{,2\\}")
        }

        @Test
        fun maxRepeatBoundary() {
            // 1000 is the maximum allowed repeat count.
            val re: Regexp = parse("a{1000}")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.REPEAT)
            Assertions.assertThat(re.min).isEqualTo(1000)
            Assertions.assertThat(re.max).isEqualTo(1000)
        }

        @Test
        fun countedRepeatLimitCheckIsStackSafeThroughDeepGroups() {
            val pattern = "(".repeat(10000) + "a{2}" + ")".repeat(10000) + "{2}"

            Assertions.assertThatNoException().isThrownBy(ThrowingCallable { parse(pattern) })
        }

        @Test
        fun repeatZero() {
            val re: Regexp = parse("a{0}")
            // a{0} matches empty string
            Assertions.assertThat<RegexpOp?>(re.op).isIn(RegexpOp.REPEAT, RegexpOp.EMPTY_MATCH)
        }

        @Test
        fun repeatZeroToOne() {
            val re: Regexp = parse("a{0,1}")
            Assertions.assertThat<RegexpOp?>(re.op).isIn(RegexpOp.QUEST, RegexpOp.REPEAT)
        }

        @Test
        fun repeatOneOrMore() {
            val re: Regexp = parse("a{1,}")
            Assertions.assertThat<RegexpOp?>(re.op).isIn(RegexpOp.PLUS, RegexpOp.REPEAT)
        }

        @Test
        fun repeatZeroOrMore() {
            val re: Regexp = parse("a{0,}")
            Assertions.assertThat<RegexpOp?>(re.op).isIn(RegexpOp.STAR, RegexpOp.REPEAT)
        }

        @Test
        fun caseInsensitiveUnicodeCaseCharClassAddsUnicodeFolds() {
            // (?iu)[a-z] should include the Kelvin sign (0x212A) and long-s (0x17F).
            val re: Regexp = parse("(?iu)[a-z]")
            Assertions.assertThat(re.charClass!!.contains(0x17F)).isTrue() // ſ (long s)
            Assertions.assertThat(re.charClass!!.contains(0x212A)).isTrue() // K (Kelvin sign)
        }

        @Test
        fun caseInsensitiveCharClassUsesAsciiFoldsWithoutUnicodeCase() {
            val re: Regexp = parse("(?i)[a-z]")
            Assertions.assertThat(re.charClass!!.contains('A'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains('Z'.code)).isTrue()
            Assertions.assertThat(re.charClass!!.contains(0x17F)).isFalse()
            Assertions.assertThat(re.charClass!!.contains(0x212A)).isFalse()
        }

        @Test
        fun beginEndText_withExplicitEscapes() {
            val re: Regexp = parse("\\A")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.BEGIN_TEXT)
            val re2: Regexp = parse("\\z")
            Assertions.assertThat<RegexpOp?>(re2.op).isEqualTo(RegexpOp.END_TEXT)
        }

        @Test
        fun dotConcatPattern() {
            val re: Regexp = parse("a.")
            Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CONCAT)
            Assertions.assertThat<RegexpOp?>(re.subs!!.get(0)!!.op).isEqualTo(RegexpOp.LITERAL)
            Assertions.assertThat<RegexpOp?>(re.subs!!.get(1)!!.op).isIn(RegexpOp.ANY_CHAR, RegexpOp.CHAR_CLASS)
        }
    }

    @Test
    fun unknownNamedCharacterKeepsCause() {
        Assertions.assertThatThrownBy(ThrowingCallable { parse("\\N{NO SUCH CHARACTER}") })
            .isInstanceOf(PatternSyntaxException::class.java)
            .hasMessageContaining("unknown Unicode character name: NO SUCH CHARACTER")
            .hasCauseInstanceOf(IllegalArgumentException::class.java)
    }

    companion object {
        /** Perl-compatible flags, used as the default for most tests.  */
        private val PERL = ParseFlags.LIKE_PERL

        /**
         * Flags matching the RE2 C++ test suite's default (`MatchNL | PerlX | PerlClasses | UnicodeGroups`). These differ from [ParseFlags.LIKE_PERL] in that they omit `ONE_LINE` and `PERL_B`.
         */
        private val RE2_TEST = (ParseFlags.MATCH_NL
                or ParseFlags.PERL_X
                or ParseFlags.PERL_CLASSES
                or ParseFlags.PERL_B
                or ParseFlags.UNICODE_GROUPS)

        private fun parse(pattern: String?): Regexp {
            return Parser.parse(pattern, PERL)!!
        }

        private fun parse(pattern: String?, flags: Int): Regexp {
            return Parser.parse(pattern, flags)!!
        }

        private fun fullMatch(pattern: String?, text: String?, flags: Int): Boolean {
            val re = Parser.parse(pattern, flags)!!
            val prog = Compiler.compile(re)!!
            val result =
                Nfa.search(prog, text!!, Nfa.Anchor.UNANCHORED, Nfa.MatchKind.FULL_MATCH, prog.numCaptures())
            return result != null
        }

        private fun parseWork(pattern: String?): Long {
            return WorkCounter.countForTesting(Runnable { parse(pattern) })
        }

        private fun nestedCharacterClass(depth: Int): String {
            val pattern = StringBuilder(depth * 2 + 1)
            for (i in 0..<depth) {
                pattern.append('[')
            }
            pattern.append('a')
            for (i in 0..<depth) {
                pattern.append(']')
            }
            return pattern.toString()
        }

        private fun repeatedPlainCharacterClasses(count: Int): String {
            val pattern = StringBuilder(count * 3)
            for (i in 0..<count) {
                pattern.append("[a]")
            }
            return pattern.toString()
        }

        private fun nestedNonCapturingGroups(depth: Int, atom: String): String {
            val pattern = StringBuilder(depth * 3 + atom.length + depth)
            for (i in 0..<depth) {
                pattern.append("(?:")
            }
            pattern.append(atom)
            for (i in 0..<depth) {
                pattern.append(')')
            }
            return pattern.toString()
        }

        private fun simplify(pattern: String?): Regexp? {
            return Simplifier.simplify(parse(pattern))
        }
    }
}
