// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

/** Tests for [InstOp].  */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class InstOpTest {
    @Test
    fun allOpsAreDefined() {
        Assertions.assertThat(InstOp.entries.size).isEqualTo(12)
    }

    @Test
    fun orderMatchesRe2() {
        Assertions.assertThat(InstOp.ALT.ordinal).isEqualTo(0)
        Assertions.assertThat(InstOp.ALT_MATCH.ordinal).isEqualTo(1)
        Assertions.assertThat(InstOp.CHAR_RANGE.ordinal).isEqualTo(2)
        Assertions.assertThat(InstOp.CAPTURE.ordinal).isEqualTo(3)
        Assertions.assertThat(InstOp.EMPTY_WIDTH.ordinal).isEqualTo(4)
        Assertions.assertThat(InstOp.MATCH.ordinal).isEqualTo(5)
        Assertions.assertThat(InstOp.NOP.ordinal).isEqualTo(6)
        Assertions.assertThat(InstOp.FAIL.ordinal).isEqualTo(7)
        Assertions.assertThat(InstOp.CHAR_CLASS.ordinal).isEqualTo(8)
        Assertions.assertThat(InstOp.GRAPHEME_CLUSTER.ordinal).isEqualTo(9)
        Assertions.assertThat(InstOp.PROGRESS_CHECK.ordinal).isEqualTo(10)
        Assertions.assertThat(InstOp.LOOP_ENTER.ordinal).isEqualTo(InstOp.OP_LOOP_ENTER)
    }
}
