// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets

@DisabledForCrosscheck("Internal scanner coordinates and opaque storage bounds")
internal class StringRegionInputScannerTest {
    @Test
    fun foldedVerificationFallbackHonorsBothStorageBounds() {
        val literal = "a".repeat(128) + "b"
        val text = "#" + "A".repeat(4096) + "b!"
        val clipped = StringRegionInputScanner(text, 1, text.length - 2)
        val complete = StringRegionInputScanner(text, 1, text.length - 1)
        // Deliberately use a dense anchor to exercise verification-budget exhaustion and KMP.
        Assertions.assertThat(clipped.indexOfIgnoreCase(literal, 0, 'a', 'A', 0)).isEqualTo(-1)
        Assertions.assertThat(complete.indexOfIgnoreCase(literal, 0, 'a', 'A', 0)).isEqualTo(4096 - 128)
        Assertions.assertThat(complete.indexOfIgnoreCase(literal, 0, 'a', 'A', 4096)).isEqualTo(-1)
    }

    @Test
    fun terminalSuffixFiltersAgreeWithTheCopiedRegion() {
        val options = EnginePathOptions.allEnabled()
        for (text in arrayOf<String>("!ab!", "!ab\r\n!", "!ab\n!", "!ab\u0085!", "!Kk!")) {
            for (start in 0..text.length) {
                for (end in start..text.length) {
                    val copy = text.substring(start, end)
                    val view = StringRegionInputScanner(text, start, end)
                    for (suffix in arrayOf<String>("b", "ab", "k", "\r")) {
                        for (dollar in booleanArrayOf(false, true)) {
                            for (unix in booleanArrayOf(false, true)) {
                                for (fold in booleanArrayOf(false, true)) {
                                    val filter: RejectPrefilter =
                                        RejectPrefilter.EndAnchoredSuffix(
                                            suffix, suffix.toByteArray(StandardCharsets.UTF_8), dollar, unix, fold
                                        )
                                    Assertions.assertThat(filter.canReject(null, view, 0, options))
                                        .isEqualTo(filter.canReject(copy, null, 0, options))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["!abc!", "x😀y", "\r\n", "a\u0085b\u2028c\u2029", "\uD800a\uDC00"])
    fun boundedDecodingAndSearchAgreeWithAnIndependentSubstring(text: String) {
        for (start in 0..text.length) {
            for (end in start..text.length) {
                val copy = StringInputScanner(text.substring(start, end))
                val view = StringRegionInputScanner(text, start, end)
                Assertions.assertThat(view.length).isEqualTo(copy.length)
                for (pos in 0..view.length) {
                    Assertions.assertThat(view.decodeForward(pos)).isEqualTo(copy.decodeForward(pos))
                    Assertions.assertThat(view.decodeBackward(pos)).isEqualTo(copy.decodeBackward(pos))
                    for (limit in pos..view.length) {
                        Assertions.assertThat(view.decodeForward(pos, limit)).isEqualTo(copy.decodeForward(pos, limit))
                    }
                    for (lower in 0..pos) {
                        Assertions.assertThat(view.decodeBackward(pos, lower))
                            .isEqualTo(copy.decodeBackward(pos, lower))
                    }
                    Assertions.assertThat(view.isCodePointBoundary(pos)).isEqualTo(copy.isCodePointBoundary(pos))
                    if (pos < view.length) {
                        Assertions.assertThat(view.asciiAt(pos)).isEqualTo(copy.asciiAt(pos))
                        Assertions.assertThat(view.singleUnitCodePointAt(pos))
                            .isEqualTo(copy.singleUnitCodePointAt(pos))
                    }
                    if (pos > 0) {
                        Assertions.assertThat(view.singleUnitCodePointBefore(pos))
                            .isEqualTo(copy.singleUnitCodePointBefore(pos))
                    }
                    for (limit in pos..view.length) {
                        Assertions.assertThat(view.indexOfAscii('a'.code, pos, limit))
                            .isEqualTo(copy.indexOfAscii('a'.code, pos, limit))
                        Assertions.assertThat(view.indexOfAsciiPair('a'.code, 'b'.code, pos, limit))
                            .isEqualTo(copy.indexOfAsciiPair('a'.code, 'b'.code, pos, limit))
                        Assertions.assertThat(view.indexOfAsciiTriple('a'.code, 'b'.code, 'c'.code, pos, limit))
                            .isEqualTo(copy.indexOfAsciiTriple('a'.code, 'b'.code, 'c'.code, pos, limit))
                        Assertions.assertThat(view.indexOfAsciiOrNonAscii('a'.code, pos, limit))
                            .isEqualTo(copy.indexOfAsciiOrNonAscii('a'.code, pos, limit))
                        Assertions.assertThat(view.indexOfAsciiPairOrNonAscii('a'.code, 'b'.code, pos, limit))
                            .isEqualTo(copy.indexOfAsciiPairOrNonAscii('a'.code, 'b'.code, pos, limit))
                        Assertions.assertThat(
                            view.indexOfAsciiTripleOrNonAscii(
                                'a'.code,
                                'b'.code,
                                'c'.code,
                                pos,
                                limit
                            )
                        )
                            .isEqualTo(copy.indexOfAsciiTripleOrNonAscii('a'.code, 'b'.code, 'c'.code, pos, limit))
                    }
                    for (unix in booleanArrayOf(false, true)) {
                        Assertions.assertThat(view.trailingLineTerminatorStart(unix, pos))
                            .isEqualTo(copy.trailingLineTerminatorStart(unix, pos))
                    }
                }
            }
        }
    }
}
