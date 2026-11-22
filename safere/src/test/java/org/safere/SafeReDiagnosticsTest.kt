// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

/** Tests for [SafeReDiagnostics].  */
@DisabledForCrosscheck("diagnostic exporter test uses SafeRE-specific bytecode shape")
internal class SafeReDiagnosticsTest {
    @Test
    fun exportsNfaProgramShapeFromPatternProg() {
        val json =
            SafeReDiagnosticsPattern.bytecodeCaseToJsonLine(
                "literal-a",
                "a",
                0,
                "a",
                SafeReDiagnostics.BytecodeMatchMode.ANCHORED,
                Pattern.compile("a")
            )

        Assertions.assertThat(json).contains("\"schema\":\"safere-bytecode-v1\"")
        Assertions.assertThat(json).contains("\"producer\":{\"name\":\"safere\"}")
        Assertions.assertThat(json).contains("\"matchKind\":\"FIRST_MATCH\"")
        Assertions.assertThat(json).contains("\"shape\":\"nfa-prog\"")
        Assertions.assertThat(json).contains("\"didFlatten\":false")
        Assertions.assertThat(json).contains("\"requiresPikeNfaCaptureSemantics\":false")
        Assertions.assertThat(json).contains("\"op\":\"CHAR_RANGE\"")
        Assertions.assertThat(json).contains("\"lo\":97")
        Assertions.assertThat(json).contains("\"hi\":97")
        Assertions.assertThat(json).contains("\"foldCase\":false")
        Assertions.assertThat(json).contains("\"op\":\"MATCH\"")
    }

    @Test
    fun nestedRepeatsExportAcceptedPathCapturesWithLegacyMetadata() {
        val json =
            SafeReDiagnosticsPattern.bytecodeCaseToJsonLine(
                "nested-repeat",
                "(?:(a){0,2})*",
                0,
                "aaaa",
                SafeReDiagnostics.BytecodeMatchMode.ANCHORED,
                Pattern.compile("(?:(a){0,2})*")
            )

        Assertions.assertThat(json).contains("\"requiresPikeNfaCaptureSemantics\":false")
        Assertions.assertThat(json).contains("{\"group\":1,\"matched\":true,\"start\":3,\"end\":4}")
    }

    @Test
    fun exportsAnchoredNfaCaptureGroups() {
        val json =
            SafeReDiagnosticsPattern.bytecodeCaseToJsonLine(
                "capture",
                "(a)",
                0,
                "a",
                SafeReDiagnostics.BytecodeMatchMode.ANCHORED,
                Pattern.compile("(a)")
            )

        Assertions.assertThat(json).contains("\"matched\":true")
        Assertions.assertThat(json).contains("\"numCaptures\":2")
        Assertions.assertThat(json).contains("\"numCaptureSlots\":4")
        Assertions.assertThat(json).contains("{\"group\":0,\"matched\":true,\"start\":0,\"end\":1}")
        Assertions.assertThat(json).contains("{\"group\":1,\"matched\":true,\"start\":0,\"end\":1}")
    }

    @Test
    fun exportsNonparticipatingCapturesWithExplicitMatchedFlagAndMinusOneOffsets() {
        val json =
            SafeReDiagnosticsPattern.bytecodeCaseToJsonLine(
                "optional-capture",
                "(a)?",
                0,
                "",
                SafeReDiagnostics.BytecodeMatchMode.ANCHORED,
                Pattern.compile("(a)?")
            )

        Assertions.assertThat(json).contains("{\"group\":0,\"matched\":true,\"start\":0,\"end\":0}")
        Assertions.assertThat(json).contains("{\"group\":1,\"matched\":false,\"start\":-1,\"end\":-1}")
    }

    @Test
    fun exportsUnmatchedAnchoredNfaResult() {
        val json =
            SafeReDiagnosticsPattern.bytecodeCaseToJsonLine(
                "miss",
                "a",
                0,
                "b",
                SafeReDiagnostics.BytecodeMatchMode.ANCHORED,
                Pattern.compile("a")
            )

        Assertions.assertThat(json).contains("\"matched\":false")
        Assertions.assertThat(json).contains("\"groups\":[]")
    }

    @Test
    fun compilesPatternInConvenienceOverload() {
        val json =
            SafeReDiagnosticsPattern.bytecodeCaseToJsonLine(
                "compiled", "a", 0, "a", SafeReDiagnostics.BytecodeMatchMode.ANCHORED
            )

        Assertions.assertThat(json).contains("\"pattern\":\"a\"")
        Assertions.assertThat(json).contains("\"matched\":true")
        Assertions.assertThat(json).contains("\"op\":\"CHAR_RANGE\"")
    }

    @Test
    fun exportsCharClassRangesAsPairs() {
        val json =
            SafeReDiagnosticsPattern.bytecodeCaseToJsonLine(
                "digits",
                "[0-9A-Z]",
                0,
                "5",
                SafeReDiagnostics.BytecodeMatchMode.ANCHORED,
                Pattern.compile("[0-9A-Z]")
            )

        Assertions.assertThat(json).contains("\"op\":\"CHAR_CLASS\"")
        Assertions.assertThat(json).contains("\"ranges\":[[48,57],[65,90]]")
    }

    @Test
    fun exportsFlagsAndEscapedStringsDeterministically() {
        val flags = Pattern.CASE_INSENSITIVE or Pattern.MULTILINE
        val json =
            SafeReDiagnosticsPattern.bytecodeCaseToJsonLine(
                "escape",
                "a\\nb",
                flags,
                "a\nb",
                SafeReDiagnostics.BytecodeMatchMode.ANCHORED,
                Pattern.compile("a\\nb", flags)
            )

        Assertions.assertThat(json).contains("\"pattern\":\"a\\\\nb\"")
        Assertions.assertThat(json).contains("\"input\":\"a\\nb\"")
        Assertions.assertThat(json).contains("\"flags\":[\"CASE_INSENSITIVE\",\"MULTILINE\"]")
        Assertions.assertThat(json).contains("\"flagsValue\":" + flags)
    }

    @Test
    fun escapesLoneSurrogateCodeUnitsInJsonStrings() {
        val loneHighSurrogate = 0xD800.toChar().toString()
        val json =
            SafeReDiagnosticsPattern.bytecodeCaseToJsonLine(
                "surrogate",
                "a",
                0,
                loneHighSurrogate,
                SafeReDiagnostics.BytecodeMatchMode.ANCHORED,
                Pattern.compile("a")
            )

        Assertions.assertThat(json).contains("\"input\":\"\\ud800\"")
        Assertions.assertThat(json).doesNotContain(loneHighSurrogate)
    }

    @Test
    fun exportsProgressCheckGreedinessBySemanticName() {
        val json =
            SafeReDiagnosticsPattern.bytecodeCaseToJsonLine(
                "nullable-loop",
                "(?:a?)*?",
                0,
                "",
                SafeReDiagnostics.BytecodeMatchMode.ANCHORED,
                Pattern.compile("(?:a?)*?")
            )

        val progressCheck: String = instructionWithOp(json, "PROGRESS_CHECK")
        Assertions.assertThat(progressCheck).contains("\"nonGreedy\":true")
        Assertions.assertThat(progressCheck).doesNotContain("\"foldCase\"")
    }

    @Test
    fun omitsUnsupportedMatcherEndpointMetadata() {
        val json =
            SafeReDiagnosticsPattern.bytecodeCaseToJsonLine(
                "literal-a",
                "a",
                0,
                "a",
                SafeReDiagnostics.BytecodeMatchMode.ANCHORED,
                Pattern.compile("a")
            )

        Assertions.assertThat(json).doesNotContain("hitEnd")
        Assertions.assertThat(json).doesNotContain("requireEnd")
    }

    companion object {
        private fun instructionWithOp(json: String, op: String): String {
            val marker = "\"op\":\"" + op + "\""
            val opIndex = json.indexOf(marker)
            Assertions.assertThat(opIndex).isGreaterThanOrEqualTo(0)
            val start = json.lastIndexOf('{', opIndex)
            val end = json.indexOf('}', opIndex)
            Assertions.assertThat(start).isGreaterThanOrEqualTo(0)
            Assertions.assertThat(end).isGreaterThan(opIndex)
            return json.substring(start, end + 1)
        }
    }
}
