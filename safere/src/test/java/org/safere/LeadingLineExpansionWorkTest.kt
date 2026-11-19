// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
import java.util.stream.Stream

/** Work bounds for leading expansions over a class that excludes only a few code points.  */
@DisabledForCrosscheck("WorkCounter is an internal SafeRE API")
@Tag("work-counter")
internal class LeadingLineExpansionWorkTest {
    @ParameterizedTest
    @ValueSource(strings = [".*X![0-9]", ".*?X![0-9]", "[^;]*X![0-9]", "(.*)X![0-9]"])
    fun tinyUtf8ExistenceDoesNotAddScanningToNativeVerification(regex: String?) {
        val pattern = Pattern.compile(regex)
        for (expected in booleanArrayOf(false, true)) {
            val bytes = (if (expected) "X!7" else "X!-7").toByteArray(StandardCharsets.UTF_8)
            val nativeWork =
                WorkCounter.countWithForwardDfaStepsForTesting(
                    Runnable {
                        val result =
                            pattern
                                .forwardFirstMatchDfa()
                                .doSearch(Utf8InputScanner(bytes), 0, false, false, true)
                        Assertions.assertThat<Dfa.SearchResult?>(result).isNotNull()
                        Assertions.assertThat(result!!.matched).isEqualTo(expected)
                    })
            for (diagnostics in booleanArrayOf(false, true)) {
                val work =
                    WorkCounter.countWithForwardDfaStepsForTesting(
                        Runnable {
                            val found =
                                if (diagnostics)
                                    pattern.findWithDiagnostics(Utf8InputScanner(bytes))
                                else
                                    pattern.find(Utf8Input.trusted(bytes))
                            Assertions.assertThat(found).isEqualTo(expected)
                        })
                Assertions.assertThat(work)
                    .`as`("%s expected=%s diagnostics=%s", regex, expected, diagnostics)
                    .isLessThanOrEqualTo(nativeWork)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [".*error: \\w+", ".*?error: \\w+", "[^;]*error: \\w+", "(.*)error: \\w+"])
    fun utf8ExistenceDoesNotTraverseTheLeadingRunInTheDfa(regex: String?) {
        val pattern = Pattern.compile(regex)
        val suffix = " error: disk"
        for (repetitions in intArrayOf(64, 512)) {
            val bytes = ("abcdefghijklmnopqrstuvwxyz".repeat(repetitions) + suffix).toByteArray(StandardCharsets.UTF_8)
            for (diagnostics in booleanArrayOf(false, true)) {
                val steps =
                    WorkCounter.countForwardDfaStepsForTesting(
                        Runnable {
                            val found =
                                if (diagnostics)
                                    pattern.findWithDiagnostics(Utf8InputScanner(bytes))
                                else
                                    pattern.find(Utf8Input.trusted(bytes))
                            Assertions.assertThat(found).isTrue()
                        })
                // Only the literal and its tail need DFA verification for an existence-only search.
                Assertions.assertThat(steps)
                    .`as`("%s diagnostics=%s", regex, diagnostics)
                    .isLessThan(4L * suffix.length)
            }
        }
    }

    @Test
    fun matchOnEveryLineKeepsDfaStartStateFastForward() {
        // With a hit on every line, the expansion keeps returning the search start. That must not
        // quarantine the matcher's start backoff, which also gates the forward DFA's fast-forward
        // through its start state: without it the DFA steps through every byte of every line.
        val line =
            "request handled in 23 ms path=/api/v1/items/12345" + " ".repeat(60) + "error: disk\n"
        val lines = 2000
        val text = line.repeat(lines)
        val pattern = Pattern.compile(".*error: \\w+")
        val stringSteps =
            WorkCounter.countForwardDfaStepsForTesting(
                Runnable { Assertions.assertThat(count(pattern.matcher(text))).isEqualTo(lines) })
        val utf8Steps =
            WorkCounter.countForwardDfaStepsForTesting(
                Runnable {
                    Assertions.assertThat(count(pattern.matcher(Utf8Input.trusted(text.toByteArray(StandardCharsets.UTF_8)))))
                        .isEqualTo(lines)
                })
        Assertions.assertThat(stringSteps).isLessThan((text.size / 2).toLong())
        Assertions.assertThat(utf8Steps).isLessThan((text.size / 2).toLong())
    }

    @ParameterizedTest
    @MethodSource("excludedSeparatorCases")
    fun rejectedInnerHitsDoNotRescanThePrefix(regex: String?, separator: String, utf8: Boolean) {
        val pattern = Pattern.compile(regex)
        val rejected = "p".repeat(256) + separator + ("error" + separator).repeat(1024)
        for (tail in arrayOf<String>("", "perror")) {
            val input = rejected + tail
            val expected = if (tail.isEmpty()) 0 else 1
            val work =
                WorkCounter.countWithForwardDfaStepsForTesting(
                    Runnable {
                        val matches: Int =
                            if (utf8)
                                count(pattern.matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8))))
                            else
                                count(pattern.matcher(input))
                        Assertions.assertThat(matches).isEqualTo(expected)
                    })
            Assertions.assertThat(work).isLessThan(10L * input.size)
        }
    }

    companion object {
        fun excludedSeparatorCases(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of(".+error", "\r", false),
                Arguments.of(".+error", "\r", true),
                Arguments.of("\\S+error", " ", false),
                Arguments.of("\\S+error", " ", true)
            )
        }

        private fun count(matcher: Matcher): Int {
            var n = 0
            while (matcher.find()) {
                n++
            }
            return n
        }

        private fun count(matcher: Utf8Matcher): Int {
            var n = 0
            while (matcher.find()) {
                n++
            }
            return n
        }
    }
}
