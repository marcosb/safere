// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

/** Tests for [RegexpOp].  */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class RegexpOpTest {
    @Test
    fun allOpsAreDefined() {
        Assertions.assertThat<RegexpOp?>(RegexpOp.entries.toTypedArray()).contains(RegexpOp.NON_CAPTURE)
    }

    @Test
    fun firstOpIsNoMatch() {
        Assertions.assertThat<RegexpOp?>(RegexpOp.entries[0]).isEqualTo(RegexpOp.NO_MATCH)
    }

    @Test
    fun lastOpIsHaveMatch() {
        Assertions.assertThat<RegexpOp?>(RegexpOp.entries[RegexpOp.entries.size - 1]).isEqualTo(RegexpOp.HAVE_MATCH)
    }

    @Test
    fun valueOfRoundTrips() {
        for (op in RegexpOp.entries) {
            Assertions.assertThat(op.name).isNotNull()
            Assertions.assertThat<RegexpOp?>(RegexpOp.valueOf(op.name)).isEqualTo(op)
        }
    }
}
