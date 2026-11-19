// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.safere.Pattern
import org.safere.fuzz.FuzzSupport.CompiledPattern
import java.util.regex.PatternSyntaxException
import java.util.stream.Stream

internal class FuzzSupportTest {
    @ParameterizedTest
    @MethodSource("unsupportedPatterns")
    fun unsupportedSyntaxRemainsAnExploratoryFinding(regex: String, flags: Int) {
        // These documented SafeRE dialect boundaries are accepted syntax in the JDK 26 Pattern API.
        Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile(regex, flags) })
            .isInstanceOf(PatternSyntaxException::class.java)
        Assertions.assertThatNoException()
            .isThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex, flags) })
        Assertions.assertThatThrownBy(ThrowingCallable { FuzzSupport.compileCompatibleOrSkip(regex, flags) })
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("compile divergence")
    }

    @ParameterizedTest
    @ValueSource(strings = ["(?=a)", "\\1", "a++", "(?>a)", "\\G"])
    fun syntaxLikeLiteralTextIsStillCompared(literal: String) {
        assertLiteralMatch(java.util.regex.Pattern.quote(literal), 0, literal)
        assertLiteralMatch(literal, java.util.regex.Pattern.LITERAL, literal)
    }

    @ParameterizedTest
    @ValueSource(strings = ["[", "(", "\\"])
    fun rejectionByBothEnginesEndsTheComparison(regex: String) {
        Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile(regex) })
            .isInstanceOf(PatternSyntaxException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex) }).isInstanceOf(
            PatternSyntaxException::class.java
        )
        Assertions.assertThat<CompiledPattern?>(FuzzSupport.compileCompatibleOrSkip(regex, 0)).isNull()
    }

    companion object {
        private fun unsupportedPatterns(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of("(?=a)a", 0),
                Arguments.of("(?!b)a", 0),
                Arguments.of("(?<=a)b", 0),
                Arguments.of("a(?<!b)", 0),
                Arguments.of(
                    "(a)\\1",
                    0
                ),  // Fixed probes formerly run by EscapeSyntaxFuzzer; the JDK accepts these as references.
                Arguments.of("\\400", 0),
                Arguments.of("\\777", 0),
                Arguments.of("\\123", 0),
                Arguments.of("\\12*", 0),
                Arguments.of("\\2222*", 0),
                Arguments.of("3*\\2222*", 0),
                Arguments.of("(a)\\12", 0),
                Arguments.of("(?<item>a)\\k<item>", 0),
                Arguments.of("a++", 0),
                Arguments.of("a*+", 0),
                Arguments.of("a?+", 0),
                Arguments.of("a{1,2}+", 0),
                Arguments.of("a+\n+", java.util.regex.Pattern.COMMENTS),
                Arguments.of("a+ #comment\n +", java.util.regex.Pattern.COMMENTS),
                Arguments.of("(?x)a+ #comment\n +", 0),
                Arguments.of("(?x:a+ #comment\n +)", 0),
                Arguments.of("(?>a+)", 0),
                Arguments.of("\\G", 0),
                Arguments.of("\\G\\w+", 0),
                Arguments.of(
                    "a+\\GGGG",
                    java.util.regex.Pattern.COMMENTS or java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNIX_LINES
                )
            )
        }

        private fun assertLiteralMatch(regex: String?, flags: Int, input: String) {
            val compiled = FuzzSupport.compileCompatibleOrSkip(regex, flags)
            Assertions.assertThat<CompiledPattern?>(compiled).isNotNull()
            Assertions.assertThat(compiled!!.matcher(input).matches()).isTrue()
        }
    }
}
