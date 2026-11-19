// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.regex.PatternSyntaxException
import java.util.stream.Stream

/**
 * Tests for `\p{java...}` character classes that delegate to [Character]
 * predicate methods. Regression test for [#106](https://github.com/safere/safere/issues/106).
 */
@DisplayName("Java character classes (\\p{java...})")
internal class JavaCharacterClassesTest {
    @JvmRecord
    internal data class TestCase(val className: String, val input: String?, val expectedMatch: Boolean) {
        override fun toString(): String {
            return "\\p{" + className + "} on \"" + input + "\" → " + expectedMatch
        }
    }

    @ParameterizedTest
    @MethodSource("crossCheckCases")
    fun matchesJdkBehavior(tc: TestCase) {
        // Verify JDK expectation first
        val jdkPattern = java.util.regex.Pattern.compile("\\p{" + tc.className + "}+")
        Assertions.assertThat(jdkPattern.matcher(tc.input).find())
            .`as`("JDK baseline for \\p{%s} on \"%s\"", tc.className, tc.input)
            .isEqualTo(tc.expectedMatch)

        // SafeRE must match
        val safePattern = org.safere.Pattern.compile("\\p{" + tc.className + "}+")
        Assertions.assertThat(safePattern.matcher(tc.input).find())
            .`as`("SafeRE for \\p{%s} on \"%s\"", tc.className, tc.input)
            .isEqualTo(tc.expectedMatch)
    }

    @Test
    @DisplayName("All 18 java character class names are accepted")
    fun allClassNamesAccepted() {
        for (name in ALL_JAVA_CLASS_NAMES) {
            org.safere.Pattern.compile("\\p{" + name + "}")
        }
    }

    @Test
    @DisplayName("Negation with \\P{...} works")
    fun negationWorks() {
        val p = org.safere.Pattern.compile("\\P{javaLowerCase}+")
        Assertions.assertThat(p.matcher("ABC").find()).isTrue()
        Assertions.assertThat(p.matcher("abc").find()).isFalse()
    }

    @Test
    @DisplayName("Single java character class find matches one code point at a time")
    fun singleJavaCharacterClassFindMatchesOneCodePoint() {
        val safe = org.safere.Pattern.compile("\\p{javaLetter}")
        val jdk = java.util.regex.Pattern.compile("\\p{javaLetter}")
        val text = "1a\uD835\uDD18_"

        val safeMatcher = safe.matcher(text)
        val jdkMatcher = jdk.matcher(text)

        Assertions.assertThat(safeMatcher.find()).isEqualTo(jdkMatcher.find())
        Assertions.assertThat(safeMatcher.start()).isEqualTo(jdkMatcher.start())
        Assertions.assertThat(safeMatcher.end()).isEqualTo(jdkMatcher.end())
        Assertions.assertThat(safeMatcher.group()).isEqualTo(jdkMatcher.group())

        Assertions.assertThat(safeMatcher.find()).isEqualTo(jdkMatcher.find())
        Assertions.assertThat(safeMatcher.start()).isEqualTo(jdkMatcher.start())
        Assertions.assertThat(safeMatcher.end()).isEqualTo(jdkMatcher.end())
        Assertions.assertThat(safeMatcher.group()).isEqualTo(jdkMatcher.group())

        Assertions.assertThat(safeMatcher.find()).isEqualTo(jdkMatcher.find())
    }

    @Test
    @DisplayName("Caret negation in java character class names is rejected")
    fun caretNegationRejected() {
        Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile("\\p{^javaLowerCase}+") })
            .isInstanceOf(PatternSyntaxException::class.java)
    }

    @Test
    @DisplayName("\\p{javaLowerCase}+ matches lowercase text")
    fun javaLowerCaseMatchesLowercaseText() {
        // Regression for issue #106.
        val p = org.safere.Pattern.compile("\\p{javaLowerCase}+", org.safere.Pattern.UNICODE_CHARACTER_CLASS)
        Assertions.assertThat(p.matcher("foo").find()).isTrue()
    }

    @ParameterizedTest(name = "\\p'{'{0}'}' = {1}")
    @MethodSource("javaClassesAndEquivalentProperties")
    @DisabledForCrosscheck(
        ("SafeRE's java* classes use its bundled Unicode tables, which intentionally differ from"
                + " older JDKs")
    )
    fun javaClassesAgreeWithEquivalentUnicodeProperties(javaClass: String?, equivalent: String?) {
        // Both sides use the same Unicode data, so they agree on every JDK.
        val actual = org.safere.Pattern.compile("\\p{" + javaClass + "}").matcher("")
        val expected = org.safere.Pattern.compile(equivalent).matcher("")
        for (cp in 0..java.lang.Character.MAX_CODE_POINT) {
            val s = java.lang.Character.toString(cp)
            Assertions.assertThat(actual.reset(s).matches()).`as`("U+%04X", cp).isEqualTo(expected.reset(s).matches())
        }
    }

    companion object {
        /** All 18 java character class names supported by `java.util.regex.Pattern`.  */
        private val ALL_JAVA_CLASS_NAMES = arrayOf<String?>(
            "javaLowerCase",
            "javaUpperCase",
            "javaWhitespace",
            "javaMirrored",
            "javaAlphabetic",
            "javaIdeographic",
            "javaTitleCase",
            "javaDigit",
            "javaDefined",
            "javaLetter",
            "javaLetterOrDigit",
            "javaSpaceChar",
            "javaISOControl",
            "javaIdentifierIgnorable",
            "javaUnicodeIdentifierStart",
            "javaUnicodeIdentifierPart",
            "javaJavaIdentifierStart",
            "javaJavaIdentifierPart",
        )

        /** Cross-checks SafeRE against `java.util.regex` for a representative sample.  */
        @JvmStatic
        fun crossCheckCases(): Stream<TestCase?> {
            return Stream.of<TestCase?>( // javaLowerCase
                TestCase("javaLowerCase", "abc", true),
                TestCase("javaLowerCase", "ABC", false),
                TestCase("javaLowerCase", "123", false),
                TestCase("javaLowerCase", "\u00E9", true),  // é (Latin small letter e with acute)
                // javaUpperCase

                TestCase("javaUpperCase", "ABC", true),
                TestCase("javaUpperCase", "abc", false),
                TestCase("javaUpperCase", "\u00C9", true),  // É
                // javaWhitespace

                TestCase("javaWhitespace", " ", true),
                TestCase("javaWhitespace", "\t", true),
                TestCase("javaWhitespace", "\n", true),
                TestCase("javaWhitespace", "a", false),
                TestCase("javaWhitespace", "\u00A0", false),  // NBSP is NOT whitespace per Character
                // javaMirrored

                TestCase("javaMirrored", "(", true),
                TestCase("javaMirrored", ")", true),
                TestCase("javaMirrored", "[", true),
                TestCase("javaMirrored", "a", false),  // javaAlphabetic

                TestCase("javaAlphabetic", "abc", true),
                TestCase("javaAlphabetic", "123", false),
                TestCase("javaAlphabetic", "\u00E9", true),  // javaIdeographic

                TestCase("javaIdeographic", "\u4E00", true),  // CJK Unified Ideograph
                TestCase("javaIdeographic", "a", false),  // javaTitleCase

                TestCase("javaTitleCase", "\u01C5", true),  // Dz with caron (titlecase)
                TestCase("javaTitleCase", "a", false),  // javaDigit

                TestCase("javaDigit", "0", true),
                TestCase("javaDigit", "9", true),
                TestCase("javaDigit", "a", false),
                TestCase("javaDigit", "\u0660", true),  // Arabic-Indic digit zero
                // javaDefined

                TestCase("javaDefined", "a", true),
                TestCase("javaDefined", "0", true),  // javaLetter

                TestCase("javaLetter", "a", true),
                TestCase("javaLetter", "0", false),
                TestCase("javaLetter", "\u00E9", true),  // javaLetterOrDigit

                TestCase("javaLetterOrDigit", "a", true),
                TestCase("javaLetterOrDigit", "0", true),
                TestCase("javaLetterOrDigit", " ", false),  // javaSpaceChar

                TestCase("javaSpaceChar", " ", true),
                TestCase("javaSpaceChar", "\u00A0", true),  // NBSP IS a space char
                TestCase("javaSpaceChar", "a", false),  // javaISOControl

                TestCase("javaISOControl", "\u0000", true),
                TestCase("javaISOControl", "\u001F", true),
                TestCase("javaISOControl", "a", false),  // javaIdentifierIgnorable

                TestCase("javaIdentifierIgnorable", "\u0000", true),
                TestCase("javaIdentifierIgnorable", "a", false),  // javaUnicodeIdentifierStart

                TestCase("javaUnicodeIdentifierStart", "a", true),
                TestCase("javaUnicodeIdentifierStart", "0", false),  // javaUnicodeIdentifierPart

                TestCase("javaUnicodeIdentifierPart", "a", true),
                TestCase("javaUnicodeIdentifierPart", "0", true),  // javaJavaIdentifierStart

                TestCase("javaJavaIdentifierStart", "a", true),
                TestCase("javaJavaIdentifierStart", "$", true),
                TestCase("javaJavaIdentifierStart", "_", true),
                TestCase("javaJavaIdentifierStart", "0", false),  // javaJavaIdentifierPart

                TestCase("javaJavaIdentifierPart", "a", true),
                TestCase("javaJavaIdentifierPart", "0", true),
                TestCase("javaJavaIdentifierPart", " ", false)
            )
        }

        @JvmStatic
        fun javaClassesAndEquivalentProperties(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of("javaLowerCase", "\\p{IsLowercase}"),
                Arguments.of("javaUpperCase", "\\p{IsUppercase}"),
                Arguments.of("javaTitleCase", "\\p{IsTitlecase}"),
                Arguments.of("javaAlphabetic", "\\p{IsAlphabetic}"),
                Arguments.of("javaIdeographic", "\\p{IsIdeographic}"),
                Arguments.of("javaDefined", "\\p{IsAssigned}"),
                Arguments.of("javaLetter", "\\p{L}"),
                Arguments.of("javaDigit", "\\p{Nd}"),
                Arguments.of("javaLetterOrDigit", "[\\p{L}\\p{Nd}]"),
                Arguments.of("javaSpaceChar", "\\p{Z}"),
                Arguments.of("javaJavaIdentifierStart", "[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}]")
            )
        }
    }
}
