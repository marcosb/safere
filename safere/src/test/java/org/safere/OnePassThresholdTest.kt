// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@DisabledForCrosscheck("verifies SafeRE engine selection thresholds and lifecycle invariants")
internal class OnePassThresholdTest {
    private val diagnostics = RecordingDiagnostics()

    @BeforeEach
    fun setUp() {
        Pattern.setDiagnostics(diagnostics)
    }

    @AfterEach
    fun tearDown() {
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
    }

    @Test
    fun noDeclaredGroups_underThreshold_usesOnePass() {
        val options = EnginePathOptions.builder().shiftDfa(false).build()
        val pattern = Pattern.compile("^[a-z]+:[0-9]+$", 0, options)
        val input = "abc:" + "1".repeat(50) // ~55 bytes <= 256

        val matcher = pattern.matcher(input)
        Assertions.assertThat(matcher.matches()).isTrue()

        val op = lastOperationFor(pattern)
        Assertions.assertThat<MatchStrategy?>(op.boundaryStrategy).isEqualTo(MatchStrategy.ONE_PASS)
    }

    @Test
    fun noDeclaredGroups_overThreshold_routesToDfa() {
        val options = EnginePathOptions.builder().shiftDfa(false).build()
        val pattern = Pattern.compile("^[a-z]+:[0-9]+$", 0, options)
        val input = "abc:" + "1".repeat(1000) // 1005 bytes > 256

        val matcher = pattern.matcher(input)
        Assertions.assertThat(matcher.matches()).isTrue()

        val op = lastOperationFor(pattern)
        Assertions.assertThat<MatchStrategy?>(op.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
    }

    @Test
    fun declaredGroups_underThreshold_usesOnePass() {
        val pattern = Pattern.compile("^([a-z]+):([0-9]+)$")
        val input = "abc:" + "1".repeat(1000) // 1005 bytes > 256 B, <= 64 KB

        val matcher = pattern.matcher(input)
        Assertions.assertThat(matcher.matches()).isTrue()
        Assertions.assertThat(matcher.group(1)).isEqualTo("abc")

        // Pattern has capturing groups -> uses OnePass up to 64 KB in single pass
        val op = lastOperationFor(pattern)
        Assertions.assertThat<MatchStrategy?>(op.boundaryStrategy).isEqualTo(MatchStrategy.ONE_PASS)
    }

    @Test
    fun declaredGroups_overThreshold_routesToDfa() {
        val pattern = Pattern.compile("^([a-z]+):([0-9]+)$")
        val input = "abc:" + "1".repeat(70000) // 70005 bytes > 64 KB

        val matcher = pattern.matcher(input)
        Assertions.assertThat(matcher.matches()).isTrue()
        Assertions.assertThat(matcher.group(1)).isEqualTo("abc")

        // Over 64 KB -> routes to DFA
        val op = lastOperationFor(pattern)
        Assertions.assertThat<MatchStrategy?>(op.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
    }

    @Test
    fun replaceAll_groupZero_staysInGroupZeroTier() {
        val pattern = Pattern.compile("^([a-z]+):([0-9]+)$")
        val input = "abc:" + "1".repeat(1000) // 1005 bytes > 256 B

        val matcher = pattern.matcher(input)
        val replaced = matcher.replaceAll("[$0]")
        Assertions.assertThat(replaced).isEqualTo("[" + input + "]")

        // $0 does not need inner captures, so input > 256 B routes to DFA even with declared groups
        val op = lastOperationFor(pattern)
        Assertions.assertThat<MatchStrategy?>(op.boundaryStrategy).isNotEqualTo(MatchStrategy.ONE_PASS)
    }

    @ParameterizedTest
    @ValueSource(strings = ["$01", "$09", "$099999999999999999999999999999999999999"])
    fun replaceAll_groupZeroFollowedByDigits_staysInGroupZeroTier(replacement: String) {
        val pattern = Pattern.compile("^[a-z]+$")
        val input = "a".repeat(1000)

        Assertions.assertThat(pattern.matcher(input).replaceAll(replacement))
            .isEqualTo(input + replacement.substring(2))
        Assertions.assertThat<MatchStrategy?>(lastOperationFor(pattern).boundaryStrategy)
            .isNotEqualTo(MatchStrategy.ONE_PASS)
    }

    @Test
    fun replaceAll_innerNumberedGroup_usesOnePassTier() {
        val pattern = Pattern.compile("^([a-z]+):([0-9]+)$")
        val input = "abc:" + "1".repeat(1000)

        val matcher = pattern.matcher(input)
        val replaced = matcher.replaceAll("[$1]")
        Assertions.assertThat(replaced).isEqualTo("[abc]")

        val op = lastOperationFor(pattern)
        Assertions.assertThat<MatchStrategy?>(op.boundaryStrategy).isEqualTo(MatchStrategy.ONE_PASS)
    }

    @Test
    fun replaceAll_namedGroup_usesOnePassTier() {
        val pattern = Pattern.compile("^(?<prefix>[a-z]+):(?<suffix>[0-9]+)$")
        val input = "abc:" + "1".repeat(1000)

        val matcher = pattern.matcher(input)
        val replaced = matcher.replaceAll("[\${prefix}]")
        Assertions.assertThat(replaced).isEqualTo("[abc]")

        val op = lastOperationFor(pattern)
        Assertions.assertThat<MatchStrategy?>(op.boundaryStrategy).isEqualTo(MatchStrategy.ONE_PASS)
    }

    private fun lastOperationFor(pattern: Pattern): OperationDiagnostics {
        val patternId = pattern.descriptor().patternId
        val ops =
            diagnostics.operations.stream()
                .filter { event: OperationDiagnostics -> event.pattern.patternId == patternId }
                .toList()
        Assertions.assertThat<OperationDiagnostics?>(ops).isNotEmpty()
        return ops.get(ops.size - 1)
    }

    private class RecordingDiagnostics : SafeReMatchDiagnostics() {
        val operations: MutableList<OperationDiagnostics?> = ArrayList<OperationDiagnostics?>()

        override fun onOperationCompleted(event: OperationDiagnostics?) {
            operations.add(event)
        }
    }
}
