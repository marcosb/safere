// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
@DisabledForCrosscheck("Uses SafeRE's internal work instrumentation")
@Tag("work-counter")
internal class InnerLiteralActivationWorkTest {
    @Test
    fun successfulFirstCandidatesNeverProbeTheSuffix() {
        val regexes = arrayOf<String?>(
            "<[^>]*>",
            "\"([^\"]*)\"",
            "\\[\\d+\\]",
            "\"(.*?)\"",
            "\\{([^}]+)\\}",
            "record:[^;\\r\\n]*val=200",
            "record:[^;\\r\\n]*val=200",
            "clé:[^;]*fin"
        )
        val units = arrayOf<String?>(
            "<p>value</p> ",
            "\"field\" \"value\" ",
            "[123] [4567] ",
            "\"field\" \"value\" ",
            "{key=1} {value=2} ",
            "record:target_val=200;",
            "record:" + "x".repeat(1024) + "val=200;",
            "clé:é😀fin;"
        )
        for (i in regexes.indices) {
            val pattern = Pattern.compile(regexes[i])
            val input = units[i]!!.repeat(64)
            for (operation in operations(pattern, input)) {
                Assertions.assertThat(WorkCounter.countInnerLiteralProbesForTesting(operation))
                    .`as`("successful /%s/", regexes[i])
                    .isZero()
            }
        }
    }

    @Test
    fun failedCandidatesActivateRegardlessOfGapLengthOrEncoding() {
        val regexes = arrayOf<String?>(
            "record:[^;\\r\\n]*val=200",
            "line:[^\\r\\n]*done",
            "pre:[^;]*;done",
            "clé:[^;]*fin",
            "😀[^;]*終"
        )
        val prefixes = arrayOf<String?>("record:", "line:", "pre:", "clé:", "😀")
        val failures = arrayOf<String?>(";", "\n", ";bad;", ";", ";")
        val tails = arrayOf<String>(
            "record:target_val=200", "line:target_done", "pre:target;done", "clé:é😀fin", "😀é終"
        )
        for (i in regexes.indices) {
            val pattern = Pattern.compile(regexes[i])
            for (gap in intArrayOf(1, 128, 2048)) {
                val input = (prefixes[i] + "x".repeat(gap) + failures[i]).repeat(128) + tails[i]
                for (operation in operations(pattern, input)) {
                    Assertions.assertThat(WorkCounter.countInnerLiteralProbesForTesting(operation))
                        .`as`("failed /%s/, gap=%d", regexes[i], gap)
                        .isEqualTo(1)
                    Assertions.assertThat(WorkCounter.countForwardDfaStepsForTesting(operation))
                        .`as`("failed /%s/, gap=%d must avoid scanning later candidates", regexes[i], gap)
                        .isLessThan(input.length / 8L)
                }
            }
        }
    }

    @Test
    fun isolatedShortFailuresDoNotActivateTheProbe() {
        val pattern = Pattern.compile("record:[^;]*val=200")
        for (input in java.util.List.of<String?>(
            "record:x;record:ok_val=200;".repeat(256),
            ("record:x;" + "noise;".repeat(1024)).repeat(4) + "record:ok_val=200;"
        )) {
            for (operation in operations(pattern, input)) {
                Assertions.assertThat(WorkCounter.countInnerLiteralProbesForTesting(operation)).isZero()
            }
        }
    }

    @Test
    fun isolatedFailuresDoNotAddCandidateScans() {
        val regexes = arrayOf<String?>("record:[^;]*val=200", "\\[\\d+\\]", "line:[^\\r\\n]*done")
        val units = arrayOf<String?>("record:x;record:ok_val=200;", "[x][123]", "line:x\nline:ok_done\n")
        for (i in regexes.indices) {
            val input = units[i]!!.repeat(256)
            val pattern = Pattern.compile(regexes[i])
            val ordinary =
                Pattern.compile(
                    regexes[i], 0, EnginePathOptions.builder().literalFastPaths(false).build()
                )
            val accelerated = operations(pattern, input)
            val control = operations(ordinary, input)
            for (operation in accelerated.indices) {
                accelerated[operation]()
                control[operation]()
                val expected =
                    WorkCounter.countStartAccelerationForTesting(control[operation]).calls
                val actual =
                    WorkCounter.countStartAccelerationForTesting(accelerated[operation]).calls
                Assertions.assertThat(actual)
                    .`as`("/%s/, operation=%d", regexes[i], operation)
                    .isLessThanOrEqualTo(expected + 2)
            }
        }
    }

    @Test
    fun prefixQuarantineDoesNotSuppressProfitableInnerNarrowing() {
        val pattern = Pattern.compile("record:[^;]*val=200")
        val input = "record:x;".repeat(1024) + "record:target_val=200"
        var backoff = AdaptiveBackoff.NEUTRAL
        for (i in 0..<AcceleratorPolicy.LITERAL.strikeBudget) {
            backoff =
                AdaptiveBackoff.recordSkip(backoff, 0, 0, input.length, AcceleratorPolicy.LITERAL)
        }
        Assertions.assertThat(AdaptiveBackoff.resumePos(backoff)).isGreaterThan(1024)
        val initialBackoff = backoff
        for (scanner in java.util.List.of<InputScanner?>(
            StringInputScanner(input), Utf8InputScanner(
                input.toByteArray(
                    StandardCharsets.UTF_8
                )
            )
        )) {
            val operation = {
                    val result =
                        pattern
                            .forwardFirstMatchDfa()
                            .doSearch(scanner, 0, false, false, true, initialBackoff)!!
                    Assertions.assertThat<Dfa.SearchResult?>(result).isNotNull()
                    Assertions.assertThat(result.matched).isTrue()
                }
            Assertions.assertThat(WorkCounter.countInnerLiteralProbesForTesting(operation)).isEqualTo(1)
            Assertions.assertThat(WorkCounter.countForwardDfaStepsForTesting(operation))
                .isLessThan(input.length / 16L)
        }
    }

    @Test
    fun repeatedSearchesRearmOnlyVisitedFailureNotifications() {
        val prefix = "abcdefghijklmnopqrst:"
        val pattern = Pattern.compile(prefix + "[^;]*Z")
        val noise = StringBuilder((prefix + "x;").repeat(4))
        for (length in 1..<prefix.length) {
            noise.append(prefix, 0, length).append('!')
        }
        val input = noise.toString() + prefix + "x".repeat(512) + "Z"
        var backoff = AdaptiveBackoff.NEUTRAL
        for (i in 0..<AcceleratorPolicy.LITERAL.strikeBudget) {
            backoff =
                AdaptiveBackoff.recordSkip(backoff, 0, 0, input.length, AcceleratorPolicy.LITERAL)
        }
        val initialBackoff = backoff
        for (pass in 0..7) {
            for (scanner in java.util.List.of<InputScanner?>(
                StringInputScanner(input), Utf8InputScanner(
                    input.toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
            )) {
                val operation = {
                        val result =
                            pattern
                                .forwardFirstMatchDfa()
                                .doSearch(scanner, 0, false, false, true, initialBackoff)!!
                        Assertions.assertThat<Dfa.SearchResult?>(result).isNotNull()
                        Assertions.assertThat(result.matched).isTrue()
                    }
                Assertions.assertThat(WorkCounter.countInnerLiteralProbesForTesting(operation)).isEqualTo(1)
                Assertions.assertThat(WorkCounter.countWithForwardDfaStepsForTesting(operation))
                    .isLessThan(input.length * 3L)
            }
        }
    }

    @Test
    fun missingSuffixIsRejectedAfterBoundedNativeWork() {
        val pattern = Pattern.compile("record:[^;]*val=200")
        val input = "record:x;".repeat(1024)
        val bytes = Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))
        for (operation in listOf(
            { Assertions.assertThat(pattern.matcher(input).find()).isFalse() },
            { Assertions.assertThat(pattern.matcher(bytes).find()).isFalse() },
            { Assertions.assertThat(pattern.find(bytes)).isFalse() })) {
            Assertions.assertThat(WorkCounter.countInnerLiteralProbesForTesting(operation)).isEqualTo(1)
            Assertions.assertThat(WorkCounter.countForwardDfaStepsForTesting(operation))
                .isLessThan(input.length / 16L)
        }
    }

    @Test
    fun repeatedFailedProbesRemainLinear() {
        val pattern = Pattern.compile("record:[^;]*val=200")
        for (size in intArrayOf(128, 256, 512)) {
            // The bounded backward probe cannot cross this successful gap. Each search must still
            // consume its own suffix rather than scanning the remaining input on every find().
            val input =
                ("record:x;".repeat(16) + "record:" + "x".repeat(512) + "val=200;").repeat(size)
            val bytes = Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))
            for (operation in listOf(
                { findAll(pattern, input) },
                {
                    val matcher = pattern.matcher(bytes)
                    while (matcher.find()) {
                        Assertions.assertThat(matcher.end()).isGreaterThan(matcher.start())
                    }
                })) {
                Assertions.assertThat(WorkCounter.countInnerLiteralProbesForTesting(operation)).isEqualTo(size.toLong())
                Assertions.assertThat(WorkCounter.countWithForwardDfaStepsForTesting(operation))
                    .isLessThan(input.length * 4L)
            }
        }
    }

    companion object {
        private fun operations(pattern: Pattern, input: String): List<() -> Any?> {
            val bytes = Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))
            return listOf(
                { Assertions.assertThat(pattern.matcher(input).find()).isTrue() },
                { Assertions.assertThat(pattern.matcher(bytes).find()).isTrue() },
                { Assertions.assertThat(pattern.find(bytes)).isTrue() },
                { Assertions.assertThat(pattern.asPredicate().test(input)).isTrue() },
                { findAll(pattern, input) },
                {
                    val matcher = pattern.matcher(bytes)
                    while (matcher.find()) {
                        Assertions.assertThat(matcher.end()).isGreaterThan(matcher.start())
                    }
                },
                { pattern.matcher(input).replaceAll("X"); Unit },
                { pattern.split(input, -1); Unit })
        }

        private fun findAll(pattern: Pattern, input: String?) {
            val matcher = pattern.matcher(input)
            while (matcher.find()) {
                Assertions.assertThat(matcher.end()).isGreaterThan(matcher.start())
            }
        }
    }
}
