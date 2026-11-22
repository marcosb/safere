// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
@DisabledForCrosscheck("tests internal ASCII width proofs")
internal class MultiAnchorWidthAnalysisTest {
    @Test
    fun consumingAtomsWithoutAsciiWidthProofsInvalidateConcatenatedWidths() {
        for (op in java.util.List.of<RegexpOp?>(RegexpOp.ANY_CHAR, RegexpOp.ANY_BYTE, RegexpOp.GRAPHEME_CLUSTER)) {
            val atom = Regexp(op, 0)
            val concat =
                Regexp.concat(java.util.List.of<Regexp?>(Regexp.literal(' '.code, 0), atom, Regexp.literal('x'.code, 0)), 0)
            Assertions.assertThat<NodeAnalysis?>(MultiAnchorCompiler.analyze(concat))
                .`as`(op.toString())
                .extracting("width.minWidth", "width.maxWidth")
                .containsExactly(-1, -1)
        }
    }

    @Test
    fun knownZeroWidthAssertionsPreserveExactAsciiWidths() {
        for (op in java.util.List.of<RegexpOp?>(
            RegexpOp.EMPTY_MATCH,
            RegexpOp.BEGIN_TEXT,
            RegexpOp.END_TEXT,
            RegexpOp.BEGIN_LINE,
            RegexpOp.END_LINE,
            RegexpOp.WORD_BOUNDARY,
            RegexpOp.NO_WORD_BOUNDARY
        )) {
            val concat =
                Regexp.concat(
                    java.util.List.of<Regexp?>(Regexp.literal(' '.code, 0), Regexp(op, 0), Regexp.literal('x'.code, 0)), 0
                )
            Assertions.assertThat<NodeAnalysis?>(MultiAnchorCompiler.analyze(concat))
                .`as`(op.toString())
                .extracting("width.minWidth", "width.maxWidth")
                .containsExactly(2, 2)
        }
    }
}
