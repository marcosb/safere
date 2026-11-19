// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.safere.Pattern
import java.time.Duration
import java.util.regex.PatternSyntaxException

internal class FuzzSupportOracleTimeoutTest {
    @Test
    @DisplayName("JDK oracle timeout marks pathological backtracking input unavailable")
    fun jdkOracleTimeoutMarksPathologicalBacktrackingInputUnavailable() {
        val previous = System.getProperty(TIMEOUT_PROPERTY)
        System.setProperty(TIMEOUT_PROPERTY, "1")
        try {
            Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                Executable {
                    Assertions.assertFalse(
                        FuzzSupport.jdkOracleCompletesForTesting(
                            "(?:(?:(a|aa))+)+y", "a".repeat(64) + "x"
                        )
                    )
                })
        } finally {
            if (previous == null) {
                System.clearProperty(TIMEOUT_PROPERTY)
            } else {
                System.setProperty(TIMEOUT_PROPERTY, previous)
            }
        }
    }

    @Test
    @DisplayName("JDK oracle stack overflow marks recursive matching unavailable")
    fun jdkOracleStackOverflowMarksRecursiveMatchingUnavailable() {
        Assertions.assertFalse(FuzzSupport.jdkOracleStackOverflowIsAvailableForTesting())
    }

    @Test
    @DisplayName("intentional syntax rejection remains an exploratory finding")
    fun intentionalSyntaxRejectionRemainsAnExploratoryFinding() {
        for (regex in arrayOf<String>("[a&&&b]", "(?x:[a& & &b])", "(?ix)[a& & &b]")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile(regex) })
                .isInstanceOf(PatternSyntaxException::class.java)
            org.assertj.core.api.Assertions.assertThatThrownBy(ThrowingCallable {
                FuzzSupport.compileCompatibleOrSkip(
                    regex,
                    0
                )
            })
                .isInstanceOf(AssertionError::class.java)
                .hasMessageContaining("compile divergence")
        }
    }

    companion object {
        private const val TIMEOUT_PROPERTY = "safere.fuzz.jdkOracleTimeoutMillis"
    }
}
