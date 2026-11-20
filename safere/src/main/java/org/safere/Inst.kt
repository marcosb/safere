// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.math.max
import kotlin.math.min

/**
 * A single instruction in a compiled regular expression program. Each instruction has an [ ] and opcode-specific data.
 * 
 * 
 * Unlike the C++ RE2 implementation, this class does not use bit-packing. Fields are stored
 * directly for clarity.
 * 
 * 
 * The instruction set:
 * 
 * 
 *  * [InstOp.ALT]: Branch to `out` or `out1`
 *  * [InstOp.ALT_MATCH]: Optimized Alt where one branch is a match
 *  * [InstOp.CHAR_RANGE]: Match a code point in `[lo, hi]`, optionally
 * case-insensitive
 *  * [InstOp.CAPTURE]: Record position as submatch boundary for capture `arg`
 *  * [InstOp.EMPTY_WIDTH]: Assert zero-width condition (see [EmptyOp])
 *  * [InstOp.MATCH]: Accept with match ID `arg`
 *  * [InstOp.NOP]: No-op, continue to `out`
 *  * [InstOp.FAIL]: Unconditional failure
 *  * [InstOp.CHAR_CLASS]: Match a code point against a multi-range character class
 *  * [InstOp.GRAPHEME_CLUSTER]: Match one Unicode extended grapheme cluster
 * 
 */
internal class Inst {
    /** The opcode for this instruction.  */
    var op: InstOp? = null

    /**
     * Cached `op.ordinal()` for use in hot-loop switches, avoiding the overhead of `Enum.ordinal()` and the synthetic switch-map array lookup on every iteration.
     */
    var opCode: Int = 0

    /** Primary successor instruction index. Used by all opcodes except MATCH and FAIL.  */
    var out: Int = 0

    /**
     * Secondary successor instruction index. Only used by [InstOp.ALT] and [ ][InstOp.ALT_MATCH].
     */
    var out1: Int = 0

    /**
     * Multipurpose argument:
     * 
     * 
     *  * For [InstOp.CAPTURE]: the capture register index (cap * 2 for start, cap * 2 + 1
     * for end)
     *  * For [InstOp.MATCH]: the match ID
     *  * For [InstOp.EMPTY_WIDTH]: the [EmptyOp] flags
     * 
     */
    var arg: Int = 0

    /** Low end of character range (inclusive). Only used by [InstOp.CHAR_RANGE].  */
    var lo: Int = 0

    /** High end of character range (inclusive). Only used by [InstOp.CHAR_RANGE].  */
    var hi: Int = 0

    /**
     * Whether this character range match is case-insensitive. Only used by [InstOp.CHAR_RANGE].
     */
    var foldCase: Boolean = false

    /** Whether this is the last instruction in a flattened list.  */
    var last: Boolean = false

    /**
     * Flat array of [lo0, hi0, lo1, hi1, ...] range pairs for [InstOp.CHAR_CLASS]. Each pair
     * defines an inclusive Unicode code point range.
     */
    var ranges: IntArray = IntArray(0)

    /**
     * ASCII bitmap for [InstOp.CHAR_CLASS]: bit `i` is set if code point `i`
     * matches at least one range. Covers code points 0–63.
     */
    var bitmap0: Long = 0

    /**
     * ASCII bitmap for [InstOp.CHAR_CLASS]: bit `(i - 64)` is set if code point `i`
     * matches at least one range. Covers code points 64–127.
     */
    var bitmap1: Long = 0

    /** Creates an uninitialized instruction (defaults to FAIL).  */
    constructor() {
        this.op = InstOp.FAIL
        this.opCode = InstOp.OP_FAIL
    }

    /** Creates a copy of another instruction.  */
    constructor(other: Inst) {
        this.op = other.op
        this.opCode = other.opCode
        this.out = other.out
        this.out1 = other.out1
        this.arg = other.arg
        this.lo = other.lo
        this.hi = other.hi
        this.foldCase = other.foldCase
        this.last = other.last
        this.ranges = other.ranges
        this.bitmap0 = other.bitmap0
        this.bitmap1 = other.bitmap1
    }

    /** Initializes as an ALT instruction branching to `out` or `out1`.  */
    fun initAlt(out: Int, out1: Int) {
        this.op = InstOp.ALT
        this.opCode = InstOp.OP_ALT
        this.out = out
        this.out1 = out1
    }

    /** Initializes as a CHAR_RANGE instruction matching code points in `[lo, hi]`.  */
    fun initCharRange(lo: Int, hi: Int, foldCase: Boolean, out: Int) {
        this.op = InstOp.CHAR_RANGE
        this.opCode = InstOp.OP_CHAR_RANGE
        this.lo = lo
        this.hi = hi
        this.foldCase = foldCase
        this.out = out
    }

    /** Initializes as a CAPTURE instruction for capture register `cap`.  */
    fun initCapture(cap: Int, out: Int) {
        this.op = InstOp.CAPTURE
        this.opCode = InstOp.OP_CAPTURE
        this.arg = cap
        this.out = out
    }

    /** Initializes as an EMPTY_WIDTH instruction with the given [EmptyOp] flags.  */
    fun initEmptyWidth(emptyFlags: Int, out: Int) {
        this.op = InstOp.EMPTY_WIDTH
        this.opCode = InstOp.OP_EMPTY_WIDTH
        this.arg = emptyFlags
        this.out = out
    }

    /** Initializes as a MATCH instruction with the given match ID.  */
    fun initMatch(matchId: Int) {
        this.op = InstOp.MATCH
        this.opCode = InstOp.OP_MATCH
        this.arg = matchId
    }

    /** Initializes as a NOP instruction continuing to `out`.  */
    fun initNop(out: Int) {
        this.op = InstOp.NOP
        this.opCode = InstOp.OP_NOP
        this.out = out
    }

    /** Initializes as a FAIL instruction.  */
    fun initFail() {
        this.op = InstOp.FAIL
        this.opCode = InstOp.OP_FAIL
    }

    /**
     * Initializes as a PROGRESS_CHECK instruction with the given loop register index.
     * 
     * 
     * This instruction has two successors and a greediness flag:
     * 
     * 
     *  * `out`: body entry (the loop body)
     *  * `out1`: loop exit (patched to whatever follows the repetition)
     * 
     * 
     * 
     * Behavior:
     * 
     * 
     *  * **First visit** (saved == -1): saves position and enters the body (plus semantics)
     *  * **Progress** (pos != saved): saves position, follows both successors like ALT (greedy
     * → prefer body; non-greedy → prefer exit)
     *  * **Zero-width** (pos == saved): follows only exit (terminates the loop)
     * 
     * 
     * @param loopReg the loop register index (0-based)
     * @param bodyOut successor for body entry
     * @param exitOut successor for loop exit
     * @param nonGreedy true if the enclosing repetition is non-greedy
     */
    fun initProgressCheck(loopReg: Int, bodyOut: Int, exitOut: Int, nonGreedy: Boolean) {
        this.op = InstOp.PROGRESS_CHECK
        this.opCode = InstOp.OP_PROGRESS_CHECK
        this.arg = loopReg
        this.out = bodyOut
        this.out1 = exitOut
        this.foldCase = nonGreedy
    }

    /** Initializes a fresh loop entry resetting `loopReg` before continuing to `out`.  */
    fun initLoopEnter(loopReg: Int, out: Int) {
        this.op = InstOp.LOOP_ENTER
        this.opCode = InstOp.OP_LOOP_ENTER
        this.arg = loopReg
        this.out = out
    }

    /**
     * Initializes as a CHAR_CLASS instruction matching code points against multiple ranges.
     * 
     * @param out successor instruction index
     * @param ranges flat array of [lo0, hi0, lo1, hi1, ...] range pairs (inclusive)
     */
    fun initCharClass(out: Int, ranges: IntArray) {
        this.op = InstOp.CHAR_CLASS
        this.opCode = InstOp.OP_CHAR_CLASS
        this.out = out
        this.ranges = ranges
        // Precompute ASCII bitmap for O(1) lookup of code points 0-127.
        var b0: Long = 0
        var b1: Long = 0
        var i = 0
        while (i < ranges.size) {
            val lo = ranges[i]
            val hi = ranges[i + 1]
            val start = max(lo, 0)
            val end = min(hi, 127)
            for (cp in start..end) {
                if (cp < 64) {
                    b0 = b0 or (1L shl cp)
                } else {
                    b1 = b1 or (1L shl (cp - 64))
                }
            }
            i += 2
        }
        this.bitmap0 = b0
        this.bitmap1 = b1
    }

    /** Initializes as a GRAPHEME_CLUSTER instruction.  */
    fun initGraphemeCluster(out: Int) {
        this.op = InstOp.GRAPHEME_CLUSTER
        this.opCode = InstOp.OP_GRAPHEME_CLUSTER
        this.out = out
    }

    /**
     * Returns true if the given code point matches this CHAR_CLASS instruction. Uses the precomputed
     * ASCII bitmap for code points 0–127, falls back to binary search for non-ASCII.
     */
    fun matchesCharClass(cp: Int): Boolean {
        if (cp < 64) {
            return (bitmap0 and (1L shl cp)) != 0L
        }
        if (cp < 128) {
            return (bitmap1 and (1L shl (cp - 64))) != 0L
        }
        // Binary search for non-ASCII code points.
        var lo = 0
        var hi: Int = (ranges.size ushr 1) - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val rLo = ranges[mid * 2]
            val rHi = ranges[mid * 2 + 1]
            if (cp < rLo) {
                hi = mid - 1
            } else if (cp > rHi) {
                lo = mid + 1
            } else {
                return true
            }
        }
        return false
    }

    /**
     * Returns true if the given code point matches this CHAR_RANGE instruction.
     * 
     * @throws IllegalStateException if this instruction is not a CHAR_RANGE
     */
    fun matchesChar(c: Int): Boolean {
        check(op == InstOp.CHAR_RANGE) { "matchesChar called on " + op }
        if (c >= lo && c <= hi) {
            return true
        }
        if (foldCase) {
            // Try case-folded version.
            var folded: Int = simpleFold(c)
            while (folded != c) {
                if (folded >= lo && folded <= hi) {
                    return true
                }
                folded = simpleFold(folded)
            }
        }
        return false
    }

    override fun toString(): String {
        return when (op!!) {
            InstOp.ALT -> "alt -> $out | $out1"
            InstOp.ALT_MATCH -> "altmatch -> $out | $out1"
            InstOp.CHAR_RANGE ->
                "char [0x${lo.toString(16).uppercase()}-0x${hi.toString(16).uppercase()}]" +
                    "${if (foldCase) "/i" else ""} -> $out"

            InstOp.CAPTURE -> "capture $arg -> $out"
            InstOp.EMPTY_WIDTH -> "empty 0x${arg.toString(16).uppercase()} -> $out"
            InstOp.MATCH -> "match $arg"
            InstOp.NOP -> "nop -> $out"
            InstOp.LOOP_ENTER -> "loop_enter reg=$arg -> $out"
            InstOp.FAIL -> "fail"
            InstOp.GRAPHEME_CLUSTER -> "grapheme_cluster -> $out"
            InstOp.PROGRESS_CHECK ->
                "progress_check reg=$arg body=$out exit=$out1 " +
                    if (foldCase) "non-greedy" else "greedy"

            InstOp.CHAR_CLASS -> {
                val sb = StringBuilder("charclass [")
                var i = 0
                while (i < ranges.size) {
                    if (i > 0) {
                        sb.append(',')
                    }
                    sb.append("0x").append(ranges[i].toString(16).uppercase())
                        .append("-0x").append(ranges[i + 1].toString(16).uppercase())
                    i += 2
                }
                sb.append("] -> ").append(out)
                sb.toString()
            }

            else -> "inst"
        }
    }

    companion object {
        /**
         * Returns the next code point in the case-fold orbit of `r`. For example, 'A' → 'a' → 'A'.
         * For characters with no case folding, returns `r`.
         */
        fun simpleFold(r: Int): Int {
            // Use Unicode case folding tables.
            for (fold in UnicodeTables.CASE_FOLD) {
                if (r < fold[0]) {
                    return r // Past the relevant range.
                }
                if (r > fold[1]) {
                    continue  // Before this range.
                }
                val delta = fold[2]
                if (delta == UnicodeTables.EVEN_ODD) {
                    // Even code points add 1, odd subtract 1.
                    return if (r % 2 == 0) r + 1 else r - 1
                } else if (delta == UnicodeTables.ODD_EVEN) {
                    // Odd code points add 1 (wrapping), even subtract 1.
                    return if (r % 2 == 1) r + 1 else r - 1
                } else if (delta == UnicodeTables.EVEN_ODD_SKIP || delta == UnicodeTables.ODD_EVEN_SKIP) {
                    // These are more complex folding cases; for now treat as simple delta.
                    return r
                } else {
                    return r + delta
                }
            }
            return r
        }
    }
}
