// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Opcodes for compiled regular expression instructions. Each instruction in a compiled [Prog]
 * has an opcode that determines how it operates.
 * 
 * 
 * These correspond to RE2's InstOp enum in prog.h.
 */
internal enum class InstOp {
    /** Branch: choose between two successors (`out` and `out1`).  */
    ALT,

    /**
     * Optimized Alt: one branch is a byte range and loops back, the other is a match. Used to
     * optimize patterns like `.*` before a match.
     */
    ALT_MATCH,

    /**
     * Match a character in the range `[lo, hi]`. In the C++ version this is called ByteRange;
     * in Java we match Unicode code points, so this is a character range.
     */
    CHAR_RANGE,

    /** Capturing parenthesis. Records the current position as submatch boundary `cap`.  */
    CAPTURE,

    /**
     * Empty-width assertion. Matches if the current position satisfies the conditions given by the
     * [EmptyOp] flags (e.g., beginning of line, word boundary).
     */
    EMPTY_WIDTH,

    /** Found a match. Halts the machine with a successful match.  */
    MATCH,

    /** No-op. Passes through to the next instruction. Occasionally unavoidable.  */
    NOP,

    /** Unconditional failure  */
    FAIL,

    /**
     * Match a code point against a multi-range character class. Stores a flat array of `[lo, hi]` range pairs and optional ASCII bitmap for O(1) lookup.
     */
    CHAR_CLASS,

    /** Match one Unicode extended grapheme cluster.  */
    GRAPHEME_CLUSTER,

    /**
     * Loop progress check for zero-width repetition breaking. Emitted at the entry point of each loop
     * iteration for `*`/`+` with nullable bodies. On first visit (saved == -1), saves the
     * current position and enters the body. On subsequent visits, compares position to saved: if
     * unchanged (zero-width body match), follows only the exit; if different, updates saved position
     * and chooses between body and exit according to greediness.
     * 
     * 
     * The register index is stored in [Inst.arg]. Each engine maintains per-thread loop
     * registers to track positions.
     */
    PROGRESS_CHECK,

    /**
     * Fresh entry to a nullable repetition. Resets the loop register in [Inst.arg] to `-1` and continues to `out`. The repetition's back edge bypasses this instruction.
     */
    LOOP_ENTER;

    companion object {
        // Int constants for hot-loop switches, avoiding Enum.ordinal() + synthetic switch-map overhead.
        // Values must match enum declaration order (ALT=0, ALT_MATCH=1, ..., LOOP_ENTER=11).
        const val OP_ALT: Int = 0
        const val OP_ALT_MATCH: Int = 1
        const val OP_CHAR_RANGE: Int = 2
        const val OP_CAPTURE: Int = 3
        const val OP_EMPTY_WIDTH: Int = 4
        const val OP_MATCH: Int = 5
        const val OP_NOP: Int = 6
        const val OP_FAIL: Int = 7
        const val OP_CHAR_CLASS: Int = 8
        const val OP_GRAPHEME_CLUSTER: Int = 9
        const val OP_PROGRESS_CHECK: Int = 10
        const val OP_LOOP_ENTER: Int = 11
    }
}
