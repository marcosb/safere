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
import org.junit.jupiter.params.provider.CsvSource
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Tests for leftmost-match semantics when bounded repeats create multiple paths ending at the same
 * position.
 * 
 * 
 * The DFA sandwich can find an earliest match end, then use the reverse DFA to recover a start
 * position. Optional bounded prefixes are ambiguous in that reverse pass: the same overall match
 * may also be valid if the optional prefix is skipped, yielding a later start. These tests verify
 * that [Matcher.find] preserves JDK-compatible leftmost-first boundaries.
 * 
 * 
 * See [issue #159](https://github.com/safere/safere/issues/159).
 */
@DisplayName("Bounded repeats: leftmost position")
internal class BoundedRepeatLeftmostPositionTest {
    @ParameterizedTest
    @CsvSource(
        "'(?:a{1,3})?a{3}', 'aaaa'",
        "'(?:\\+?\\d{1,3} ?)?\\d{3}', '+981 084'",
        "'(?:ab{0,2})?abb', 'abb'",
        "'(?:ab{0,2})?abb', 'ababb'"
    )
    @DisplayName("optional bounded prefixes keep the leftmost start")
    fun optionalBoundedPrefixKeepsLeftmostStart(pattern: String, input: String) {
        assertFindSameAsJdk(pattern, input)
    }

    @Nested
    @DisplayName("Real-world optional prefix patterns")
    internal inner class RealWorldOptionalPrefixPatterns {
        @Test
        @DisplayName("phone-number pattern with bounded repeats")
        fun phoneNumberPatternWithBoundedRepeats() {
            // Regression for issue #159.
            val pattern =
                ("("
                        + "(\\+?\\d{1,3}( )?)?"
                        + "[0-9][ ~,\\-'`_]*[0-9O][ ~,\\-'`_]*[0-9O],?\\s?"
                        + "[\\r\\n() '_~\\-.*]{0,4}\\s?"
                        + "[0-9O][ \\-'`_]*[0-9O][ \\-'`_]*[0-9O]\\s?"
                        + "[() '_\\-~.*]{0,4}\\s?"
                        + "[0-9O][ \\-'`_]*[0-9O][ \\-'`_]*[0-9O][ \\-'`_]*[0-9O]"
                        + ")")
            val input = "user message: Email me on hi@gmail.com and call +981 084 482 1192"

            assertFindSameAsJdk(pattern, input)
        }
    }

    companion object {
        private fun assertFindSameAsJdk(pattern: String, input: String) {
            val jdkMatcher = Pattern.compile(pattern).matcher(input)
            val safereMatcher = org.safere.Pattern.compile(pattern).matcher(input)

            Assertions.assertThat(jdkMatcher.find()).isTrue()
            Assertions.assertThat(safereMatcher.find()).isTrue()
            Assertions.assertThat(safereMatcher.start()).isEqualTo(jdkMatcher.start())
            Assertions.assertThat(safereMatcher.end()).isEqualTo(jdkMatcher.end())
            Assertions.assertThat(safereMatcher.group()).isEqualTo(jdkMatcher.group())
        }
    }
}
