// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.util.*

internal class EngineEquivalenceFuzzerTest {
    @Test
    fun diagnosticsProveExecutionOfDistinctEngines() {
        val seen: MutableSet<MatchStrategy?> = EnumSet.noneOf<MatchStrategy?>(MatchStrategy::class.java)
        val previous = Pattern.diagnostics()
        Pattern.setDiagnostics(
            object : SafeReMatchDiagnostics() {
                override fun onOperationCompleted(event: OperationDiagnostics) {
                    seen.add(event.boundaryStrategy)
                    seen.add(event.captureStrategy)
                }
            })
        try {
            for (regex in arrayOf<String>("a+b", "^a+b$", "(a)?b", ".|")) {
                EngineEquivalenceFuzzer.Companion.check(regex, "aaab", 0)
                EngineEquivalenceFuzzer.Companion.check(regex, "é😀aaab", 0)
            }
        } finally {
            Pattern.setDiagnostics(previous)
        }
        Assertions.assertThat<MatchStrategy?>(seen)
            .contains(
                MatchStrategy.DFA, MatchStrategy.ONE_PASS, MatchStrategy.BIT_STATE, MatchStrategy.NFA
            )
    }

    @Test
    fun modelChecksPreserveAndExerciseTheCallersDiagnostics() {
        val previous = Pattern.diagnostics()
        val operations = intArrayOf(0)
        val listener: SafeReMatchDiagnostics? =
            object : SafeReMatchDiagnostics() {
                override fun onOperationCompleted(event: OperationDiagnostics?) {
                    operations[0]++
                }
            }
        Pattern.setDiagnostics(listener)
        try {
            EngineEquivalenceFuzzer.Companion.check("a+b", "aaab", 0)
            Assertions.assertThat<SafeReMatchDiagnostics?>(Pattern.diagnostics()).isSameAs(listener)
            Assertions.assertThat(operations[0]).isPositive()
        } finally {
            Pattern.setDiagnostics(previous)
        }
    }

    @Test
    fun wideLeadingExistenceChecksRejectedAndLaterCandidates() {
        for (leading in arrayOf<String>(".*", ".*?", "[^;]*", ".+", "^.*", "\\b.*")) {
            for (tail in arrayOf<String>(": -", ": disk", ": -\nerror: disk", ": -;error: disk")) {
                EngineEquivalenceFuzzer.Companion.check(
                    leading + "error: \\w+", "abcdefghijklmnopqrstuvwxyz".repeat(16) + "error" + tail, 0
                )
            }
        }
    }

    @Test
    fun emptyFindContinuationUsesEachRepresentationsOwnProgression() {
        EngineEquivalenceFuzzer.Companion.check("a|", "😀aaa", 0)
        EngineEquivalenceFuzzer.Companion.check("", "😀", 0)
    }

    @Test
    fun surrogateInteriorFindAndUnicodePathsAgree() {
        for (input in arrayOf<String>("\udb7d\udf02A", "\ud800A", "é😀中a", "\udc00a")) {
            for (start in 0..input.size) EngineEquivalenceFuzzer.Companion.check(".|", input, start)
        }
    }

    @Test
    fun leadingRepetitionsPreserveInteriorCursors() {
        for (scalar in arrayOf<String>("\\uDC4D", "[\\uDC00-\\uDFFF]", "\\p{Cs}", ".")) {
            for (quantifier in arrayOf<String>("*", "*?", "+", "+?")) {
                val regex = "(" + scalar + quantifier + ")([ab])(\\b{g})"
                for (input in arrayOf<String>("\uD83D\uDC4Da\uD83D\uDC4Db", "\uDC4Da\uDC4Db")) {
                    for (start in 0..input.size) {
                        EngineEquivalenceFuzzer.Companion.check(regex, input, start)
                        for (end in start..input.size) {
                            for (transparent in booleanArrayOf(false, true)) {
                                for (anchoring in booleanArrayOf(false, true)) {
                                    EngineEquivalenceFuzzer.Companion.checkRegion(
                                        regex, input, start, end, transparent, anchoring
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun surrogateLiteralPlansAgreeAcrossEnginesAndRepresentations() {
        for (regex in arrayOf<String>(
            "\\x{D800}",
            "\\x{DC00}",
            "\"\\x{D800}",
            "\\x{D800}\\x{DC00}",
            "\\x{D800}(?:\\x{DC00})",
            "a\\x{D800}b",
            "(?iu)k\\x{D800}",
            ".*\\x{DC00}$",
            "(?:a\\x{D800}|banana)"
        )) {
            for (input in arrayOf<String>("\"?", "a?b", "\uD800\uDC00", "a\uD800b", "K\uD800", "\uDC00", "banana")) {
                EngineEquivalenceFuzzer.Companion.check(regex, input, 0)
            }
        }
    }

    @Test
    fun scalarPrefixesAndGraphemeBoundariesAgreeAcrossSearchPaths() {
        for (regex in arrayOf<String>("\\p{Cs}\\b{g}", "(\\uDC4D)(\\b{g})", "\\p{So}\\b{g}", "\\b{g}([^a])")) {
            for (input in arrayOf<String>("\uD83D\uDC4D", "a\uD83D\uDC4D\uDC4Da")) {
                for (start in 0..input.size) {
                    EngineEquivalenceFuzzer.Companion.check(regex, input, start)
                    for (end in start..input.size) {
                        for (transparent in booleanArrayOf(false, true)) {
                            for (anchoring in booleanArrayOf(false, true)) {
                                EngineEquivalenceFuzzer.Companion.checkRegion(
                                    regex, input, start, end, transparent, anchoring
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
