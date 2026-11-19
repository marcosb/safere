// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import java.util.regex.PatternSyntaxException

internal class FuzzDivergenceEvidenceTest {
    @Test
    fun defaultEvidenceCollectionLeavesDiagnosticsUnclaimed() {
        val previous = Pattern.diagnostics()
        val property = System.getProperty("safere.fuzz.divergenceEvidence")
        System.clearProperty("safere.fuzz.divergenceEvidence")
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
        try {
            FuzzDivergenceEvidence.begin()
            Assertions.assertThat<SafeReMatchDiagnostics?>(Pattern.diagnostics()).isSameAs(SafeReMatchDiagnostics.NONE)
        } finally {
            Pattern.setDiagnostics(previous)
            if (property == null) System.clearProperty("safere.fuzz.divergenceEvidence")
            else System.setProperty("safere.fuzz.divergenceEvidence", property)
        }
    }

    @Test
    fun signalsComeFromExecutingTheSemanticPathAndClearBetweenCases() {
        val property = System.getProperty("safere.fuzz.divergenceEvidence")
        System.setProperty("safere.fuzz.divergenceEvidence", "true")
        val previous = Pattern.diagnostics()
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
        try {
            FuzzDivergenceEvidence.begin()
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("[a&&&b]") })
                .isInstanceOf(PatternSyntaxException::class.java)
            Assertions.assertThat(FuzzDivergenceEvidence.observed()).contains("CHARACTER_CLASS_SYNTAX")
            FuzzDivergenceEvidence.begin()
            Assertions.assertThat(Pattern.compile("(?x)#\\Q\n.").matcher("a").matches()).isTrue()
            Assertions.assertThat(FuzzDivergenceEvidence.observed())
                .contains("COMMENT_QUOTING")
                .doesNotContain("CHARACTER_CLASS_SYNTAX")
            FuzzDivergenceEvidence.begin()
            val matcher = Pattern.compile("a*").matcher("a")
            matcher.replaceAll("-")
            Assertions.assertThat(matcher.hasMatch()).isFalse()
            Assertions.assertThat(FuzzDivergenceEvidence.observed()).contains("EXHAUSTED_EMPTY_MATCH")
            FuzzDivergenceEvidence.begin()
            matcher.reset()
            while (matcher.find()) {
                /* Exercise terminal-empty find directly as well. */
            }
            Assertions.assertThat(FuzzDivergenceEvidence.observed()).contains("EXHAUSTED_EMPTY_MATCH")
            FuzzDivergenceEvidence.begin()
            matcher.reset()
            matcher.find()
            matcher.usePattern(Pattern.compile("b"))
            Assertions.assertThat(matcher.group()).isEqualTo("a")
            Assertions.assertThat(FuzzDivergenceEvidence.observed()).contains("USE_PATTERN_GROUP_ZERO")
        } finally {
            Pattern.setDiagnostics(previous)
            if (property == null) System.clearProperty("safere.fuzz.divergenceEvidence")
            else System.setProperty("safere.fuzz.divergenceEvidence", property)
        }
    }
}
