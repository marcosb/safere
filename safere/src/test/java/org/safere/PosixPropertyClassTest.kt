// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.regex.Pattern
import java.util.stream.Stream

/**
 * Tests for POSIX character classes accessed via the `\p{...}` property syntax (e.g., `\p{Lower}`, `\p{Upper}`, etc.). These are the 13 POSIX classes defined in the JDK's `java.util.regex.Pattern` documentation. Regression test for [#112](https://github.com/safere/safere/issues/112).
 */
@DisplayName("POSIX \\p{...} property classes")
internal class PosixPropertyClassTest {
    @JvmRecord
    internal data class TestCase(val className: String, val input: String?, val expectedMatch: Boolean)      {
        override fun toString(): String {
            val displayInput =
                (if (input.length == 1 && input.get(0).code < 0x20) String.format(
                    "\\x%02X",
                    input.get(0).code
                ) else
                    input)!!
            return "\\p{" + className + "} on \"" + displayInput + "\" → " + expectedMatch
        }
    }

    @ParameterizedTest
    @MethodSource("crossCheckCases")
    fun matchesJdkBehavior(tc: TestCase) {
        // Verify JDK expectation first
        val jdkPattern = Pattern.compile("\\p{" + tc.className + "}")
        Assertions.assertThat(jdkPattern.matcher(tc.input).matches())
            .`as`("JDK baseline for \\p{%s} on \"%s\"", tc.className, tc.input)
            .isEqualTo(tc.expectedMatch)

        // SafeRE must match
        val safePattern = org.safere.Pattern.compile("\\p{" + tc.className + "}")
        Assertions.assertThat(safePattern.matcher(tc.input).matches())
            .`as`("SafeRE for \\p{%s} on \"%s\"", tc.className, tc.input)
            .isEqualTo(tc.expectedMatch)
    }

    @Test
    @DisplayName("All 13 POSIX class names are accepted by Pattern.compile")
    fun allClassNamesAccepted() {
        for (name in ALL_POSIX_CLASS_NAMES) {
            org.safere.Pattern.compile("\\p{" + name + "}")
        }
    }

    @Nested
    @DisplayName("Negation")
    internal inner class NegationTests {
        @Test
        @DisplayName("\\P{Lower} matches non-lowercase")
        fun upperPNegation() {
            val p = org.safere.Pattern.compile("\\P{Lower}")
            Assertions.assertThat(p.matcher("A").matches()).isTrue()
            Assertions.assertThat(p.matcher("0").matches()).isTrue()
            Assertions.assertThat(p.matcher(" ").matches()).isTrue()
            Assertions.assertThat(p.matcher("a").matches()).isFalse()
        }

        @Test
        @DisplayName("\\P{Digit} matches non-digit")
        fun upperPDigitNegation() {
            val p = org.safere.Pattern.compile("\\P{Digit}")
            Assertions.assertThat(p.matcher("a").matches()).isTrue()
            Assertions.assertThat(p.matcher(" ").matches()).isTrue()
            Assertions.assertThat(p.matcher("0").matches()).isFalse()
            Assertions.assertThat(p.matcher("9").matches()).isFalse()
        }

        @ParameterizedTest
        @ValueSource(
            strings = ["Lower", "Upper", "ASCII", "Alpha", "Digit", "Alnum", "Punct", "Graph", "Print", "Blank", "Cntrl", "XDigit", "Space"
            ]
        )
        @DisplayName("\\P{...} negation compiles for all POSIX classes")
        fun allNegationsCompile(className: String?) {
            org.safere.Pattern.compile("\\P{" + className + "}")
        }
    }

    @Nested
    @DisplayName("In character classes")
    internal inner class InCharacterClassTests {
        @Test
        @DisplayName("\\p{Lower} works inside a character class")
        fun posixInsideCharClass() {
            val p = org.safere.Pattern.compile("[\\p{Lower}\\p{Digit}]+")
            Assertions.assertThat(p.matcher("abc123").matches()).isTrue()
            Assertions.assertThat(p.matcher("ABC").matches()).isFalse()
        }

        @Test
        @DisplayName("\\p{Punct} combined with literal characters")
        fun posixCombinedWithLiterals() {
            val p = org.safere.Pattern.compile("[\\p{Punct}a]+")
            Assertions.assertThat(p.matcher("a!.@").matches()).isTrue()
            Assertions.assertThat(p.matcher("b").matches()).isFalse()
        }
    }

    @Nested
    @DisplayName("In compound patterns")
    internal inner class CompoundPatternTests {
        @Test
        @DisplayName("\\p{Alpha}\\p{Digit}+ matches identifier-like strings")
        fun alphaDigit() {
            val p = org.safere.Pattern.compile("\\p{Alpha}\\p{Digit}+")
            Assertions.assertThat(p.matcher("a123").matches()).isTrue()
            Assertions.assertThat(p.matcher("123").matches()).isFalse()
            Assertions.assertThat(p.matcher("abc").matches()).isFalse()
        }

        @Test
        @DisplayName("Quantified POSIX class in find mode")
        fun findMode() {
            val m = org.safere.Pattern.compile("\\p{XDigit}+").matcher("zz=DEADBEEF;")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("DEADBEEF")
        }

        @Test
        @DisplayName("Alternation with POSIX classes")
        fun alternation() {
            val p = org.safere.Pattern.compile("\\p{Lower}+|\\p{Digit}+")
            Assertions.assertThat(p.matcher("abc").matches()).isTrue()
            Assertions.assertThat(p.matcher("123").matches()).isTrue()
            Assertions.assertThat(p.matcher("ABC").matches()).isFalse()
        }
    }

    @Nested
    @DisplayName("Exhaustive ASCII scan")
    internal inner class ExhaustiveAsciiScanTests {
        /**
         * For each POSIX class, scan all 128 ASCII code points and verify SafeRE matches the JDK on
         * every single one.
         */
        @ParameterizedTest
        @ValueSource(
            strings = ["Lower", "Upper", "ASCII", "Alpha", "Digit", "Alnum", "Punct", "Graph", "Print", "Blank", "Cntrl", "XDigit", "Space"
            ]
        )
        @DisplayName("Exhaustive ASCII scan matches JDK for all 128 code points")
        fun exhaustiveAsciiScan(className: String?) {
            val jdkPattern = Pattern.compile("\\p{" + className + "}")
            val safePattern = org.safere.Pattern.compile("\\p{" + className + "}")

            for (cp in 0..127) {
                val s = String(Character.toChars(cp))
                val jdkMatch = jdkPattern.matcher(s).matches()
                val safeMatch = safePattern.matcher(s).matches()
                Assertions.assertThat(safeMatch)
                    .`as`("\\p{%s} on U+%04X: JDK=%s SafeRE=%s", className, cp, jdkMatch, safeMatch)
                    .isEqualTo(jdkMatch)
            }
        }
    }

    companion object {
        /** All 13 POSIX character class names from the JDK Pattern javadoc.  */
        private val ALL_POSIX_CLASS_NAMES = arrayOf<String?>(
            "Lower", "Upper", "ASCII", "Alpha", "Digit", "Alnum", "Punct", "Graph", "Print", "Blank",
            "Cntrl", "XDigit", "Space",
        )

        /**
         * Cross-checks SafeRE against `java.util.regex` for representative inputs across all 13
         * POSIX classes. Each class is tested with positive matches, negative matches, and boundary
         * cases.
         */
        fun crossCheckCases(): Stream<TestCase?> {
            return Stream.of<TestCase?>( // Lower: [a-z]
                TestCase("Lower", "a", true),
                TestCase("Lower", "z", true),
                TestCase("Lower", "m", true),
                TestCase("Lower", "A", false),
                TestCase("Lower", "Z", false),
                TestCase("Lower", "0", false),
                TestCase("Lower", " ", false),
                TestCase("Lower", "\u00E9", false),  // é — NOT matched without UNICODE_CHARACTER_CLASS
                // Upper: [A-Z]

                TestCase("Upper", "A", true),
                TestCase("Upper", "Z", true),
                TestCase("Upper", "M", true),
                TestCase("Upper", "a", false),
                TestCase("Upper", "z", false),
                TestCase("Upper", "0", false),
                TestCase("Upper", "\u00C9", false),  // É — NOT matched without UNICODE_CHARACTER_CLASS
                // ASCII: [\x00-\x7F]

                TestCase("ASCII", "\u0000", true),
                TestCase("ASCII", " ", true),
                TestCase("ASCII", "a", true),
                TestCase("ASCII", "~", true),
                TestCase("ASCII", "\u007F", true),
                TestCase("ASCII", "\u0080", false),
                TestCase("ASCII", "\u00FF", false),  // Alpha: [a-zA-Z]

                TestCase("Alpha", "a", true),
                TestCase("Alpha", "Z", true),
                TestCase("Alpha", "0", false),
                TestCase("Alpha", " ", false),
                TestCase("Alpha", "!", false),  // Digit: [0-9]

                TestCase("Digit", "0", true),
                TestCase("Digit", "9", true),
                TestCase("Digit", "5", true),
                TestCase("Digit", "a", false),
                TestCase("Digit", " ", false),
                TestCase("Digit", "\u0660", false),  // Arabic-Indic digit — NOT matched (ASCII only)
                // Alnum: [a-zA-Z0-9]

                TestCase("Alnum", "a", true),
                TestCase("Alnum", "Z", true),
                TestCase("Alnum", "5", true),
                TestCase("Alnum", " ", false),
                TestCase("Alnum", "!", false),  // Punct: !"#$%&'()*+,-./:;<=>?@[\]^_`{|}~

                TestCase("Punct", "!", true),
                TestCase("Punct", ".", true),
                TestCase("Punct", "@", true),
                TestCase("Punct", "~", true),
                TestCase("Punct", "{", true),
                TestCase("Punct", "a", false),
                TestCase("Punct", "0", false),
                TestCase("Punct", " ", false),  // Graph: [\p{Alnum}\p{Punct}] i.e. [!-~]

                TestCase("Graph", "a", true),
                TestCase("Graph", "0", true),
                TestCase("Graph", "!", true),
                TestCase("Graph", "~", true),
                TestCase("Graph", " ", false),
                TestCase("Graph", "\t", false),  // Print: [\p{Graph}\x20] i.e. [ -~]

                TestCase("Print", "a", true),
                TestCase("Print", "0", true),
                TestCase("Print", "!", true),
                TestCase("Print", " ", true),
                TestCase("Print", "\t", false),
                TestCase("Print", "\n", false),  // Blank: [ \t]

                TestCase("Blank", " ", true),
                TestCase("Blank", "\t", true),
                TestCase("Blank", "a", false),
                TestCase("Blank", "\n", false),
                TestCase("Blank", "\r", false),  // Cntrl: [\x00-\x1F\x7F]

                TestCase("Cntrl", "\u0000", true),
                TestCase("Cntrl", "\u001F", true),
                TestCase("Cntrl", "\u007F", true),
                TestCase("Cntrl", "\u0001", true),
                TestCase("Cntrl", " ", false),
                TestCase("Cntrl", "a", false),  // XDigit: [0-9a-fA-F]

                TestCase("XDigit", "0", true),
                TestCase("XDigit", "9", true),
                TestCase("XDigit", "a", true),
                TestCase("XDigit", "f", true),
                TestCase("XDigit", "A", true),
                TestCase("XDigit", "F", true),
                TestCase("XDigit", "g", false),
                TestCase("XDigit", "G", false),
                TestCase("XDigit", " ", false),  // Space: [ \t\n\x0B\f\r]

                TestCase("Space", " ", true),
                TestCase("Space", "\t", true),
                TestCase("Space", "\n", true),
                TestCase("Space", "\u000B", true),  // vertical tab
                TestCase("Space", "\u000c", true),
                TestCase("Space", "\r", true),
                TestCase("Space", "a", false),
                TestCase("Space", "0", false)
            )
        }
    }
}
