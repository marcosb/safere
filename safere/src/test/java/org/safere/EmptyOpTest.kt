// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

/** Tests for [EmptyOp].  */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class EmptyOpTest {
    @Test
    fun flagsArePowersOfTwo() {
        Assertions.assertThat(EmptyOp.BEGIN_LINE).isEqualTo(1)
        Assertions.assertThat(EmptyOp.END_LINE).isEqualTo(2)
        Assertions.assertThat(EmptyOp.BEGIN_TEXT).isEqualTo(4)
        Assertions.assertThat(EmptyOp.END_TEXT).isEqualTo(8)
        Assertions.assertThat(EmptyOp.WORD_BOUNDARY).isEqualTo(16)
        Assertions.assertThat(EmptyOp.NON_WORD_BOUNDARY).isEqualTo(32)
        Assertions.assertThat(EmptyOp.DOLLAR_END).isEqualTo(64)
        Assertions.assertThat(EmptyOp.UNICODE_WORD_BOUNDARY).isEqualTo(128)
        Assertions.assertThat(EmptyOp.UNICODE_NON_WORD_BOUNDARY).isEqualTo(256)
        Assertions.assertThat(EmptyOp.UNIX_DOLLAR_END).isEqualTo(512)
        Assertions.assertThat(EmptyOp.UNIX_BEGIN_LINE).isEqualTo(1024)
        Assertions.assertThat(EmptyOp.UNIX_END_LINE).isEqualTo(2048)
        Assertions.assertThat(EmptyOp.GRAPHEME_CLUSTER_BOUNDARY).isEqualTo(4096)
        Assertions.assertThat(EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY).isEqualTo(8192)
    }

    @Test
    fun allFlagsCombinesAll() {
        val combined =
            (EmptyOp.BEGIN_LINE
                    or EmptyOp.END_LINE
                    or EmptyOp.BEGIN_TEXT
                    or EmptyOp.END_TEXT
                    or EmptyOp.WORD_BOUNDARY
                    or EmptyOp.NON_WORD_BOUNDARY
                    or EmptyOp.DOLLAR_END
                    or EmptyOp.UNICODE_WORD_BOUNDARY
                    or EmptyOp.UNICODE_NON_WORD_BOUNDARY
                    or EmptyOp.UNIX_DOLLAR_END
                    or EmptyOp.UNIX_BEGIN_LINE
                    or EmptyOp.UNIX_END_LINE
                    or EmptyOp.GRAPHEME_CLUSTER_BOUNDARY
                    or EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY)
        Assertions.assertThat(EmptyOp.ALL_FLAGS).isEqualTo(combined)
        Assertions.assertThat(EmptyOp.ALL_FLAGS).isEqualTo(16383)
    }

    @Test
    fun flagsAreOrthogonal() {
        // Each pair of flags should have no bits in common.
        val flags = intArrayOf(
            EmptyOp.BEGIN_LINE,
            EmptyOp.END_LINE,
            EmptyOp.BEGIN_TEXT,
            EmptyOp.END_TEXT,
            EmptyOp.WORD_BOUNDARY,
            EmptyOp.NON_WORD_BOUNDARY,
            EmptyOp.DOLLAR_END,
            EmptyOp.UNICODE_WORD_BOUNDARY,
            EmptyOp.UNICODE_NON_WORD_BOUNDARY,
            EmptyOp.UNIX_DOLLAR_END,
            EmptyOp.UNIX_BEGIN_LINE,
            EmptyOp.UNIX_END_LINE,
            EmptyOp.GRAPHEME_CLUSTER_BOUNDARY,
            EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY
        )
        for (i in flags.indices) {
            for (j in i + 1..<flags.size) {
                Assertions.assertThat(flags[i] and flags[j])
                    .withFailMessage("Flags " + i + " and " + j + " overlap")
                    .isEqualTo(0)
            }
        }
    }
}
