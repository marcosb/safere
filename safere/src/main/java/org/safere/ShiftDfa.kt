// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*

/**
 * Shift-based Scalar DFA engine for small deterministic regular expressions ( 10 states).
 * 
 * 
 * Implements Per Vognsen's Shift DFA model with integrated [StateAccelerator] vector
 * acceleration for self-loop states. Instead of looking up transition destinations by indexing
 * array memory with `state`, transition tables are indexed by the incoming character `c` to retrieve a 64-bit integer packing next-state destinations for all states:
 * 
 * <pre>`long row = table[c]; state = (int) ((row >>> state) & 0x3F); `</pre>
 * 
 * 
 * This decouples memory load latency from the loop-carried state dependency, reducing the
 * critical-path state transition to a single 1-cycle ALU shift instruction (`shrx`) and
 * achieving ~1 byte / clock cycle (~4.5 GB/s) scalar throughput. When in a self-loop state, it
 * fast-forwards using vector SIMD intrinsics (~30 GB/s).
 */
internal class ShiftDfa private constructor(
    table: LongArray,
    acceptMask: Long,
    initialShiftState: Int,
    numStates: Int,
    accelerators: Array<StateAccelerator?>?
) {
    private val table: LongArray
    private val acceptMask: Long
    private val initialShiftState: Int
    private val numStates: Int
    private val accelerators: Array<StateAccelerator?>?

    init {
        this.table = table
        this.acceptMask = acceptMask
        this.initialShiftState = initialShiftState
        this.numStates = numStates
        this.accelerators = accelerators
    }

    fun numStates(): Int {
        return numStates
    }

    fun accelerators(): Array<StateAccelerator?>? {
        return accelerators
    }

    /** Compilation-local scratch; only interned DFA frontiers receive an owned array.  */
    private class Builder(prog: Prog) {
        private val prog: Prog
        private val stack: IntArray
        private val frontier: IntArray
        private val visited: IntArray
        private var stackTop = 0
        private var frontierSize = 0
        private var generation = 0

        init {
            this.prog = prog
            stack = IntArray(prog.size() * 2 + 16)
            frontier = IntArray(prog.size())
            visited = IntArray(prog.size())
        }

        fun copyFrontier(): IntArray {
            return Arrays.copyOf(frontier, frontierSize)
        }

        fun findState(states: MutableList<IntArray>): Int {
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return -1
        }

        fun expand(): Boolean {
            frontierSize = 0
            // There are at most 1 + MAX_STATES * 128 expansions, so the stamp cannot overflow.
            generation++
            while (stackTop > 0) {
                val id = stack[--stackTop]
                if (id == 0 || id >= prog.size() || visited[id] == generation) {
                    continue
                }
                visited[id] = generation

                val ip = prog.inst(id)
                when (ip.opCode) {
                    InstOp.Companion.OP_FAIL -> {}
                    InstOp.Companion.OP_ALT, InstOp.Companion.OP_ALT_MATCH, InstOp.Companion.OP_PROGRESS_CHECK -> {
                        stack[stackTop++] = ip.out1
                        stack[stackTop++] = ip.out
                    }

                    InstOp.Companion.OP_NOP, InstOp.Companion.OP_CAPTURE, InstOp.Companion.OP_LOOP_ENTER -> stack[stackTop++] =
                        ip.out

                    InstOp.Companion.OP_CHAR_RANGE -> {
                        if (ip.hi >= 128) {
                            return false // Non-ASCII character range not supported in ShiftDfa
                        }
                        frontier[frontierSize++] = id
                    }

                    InstOp.Companion.OP_CHAR_CLASS -> {
                        if (ip.ranges != null && ip.ranges.length > 0 && ip.ranges[ip.ranges.length - 1] >= 128) {
                            return false // Non-ASCII character class not supported in ShiftDfa
                        }
                        frontier[frontierSize++] = id
                    }

                    InstOp.Companion.OP_MATCH -> frontier[frontierSize++] = id
                    else -> {
                        return false // Unsupported instruction (e.g. EMPTY_WIDTH, GRAPHEME_CLUSTER)
                    }
                }
            }
            Arrays.sort(frontier, 0, frontierSize)
            return true
        }

        fun step(currentInsts: IntArray, c: Int): Boolean {
            stackTop = 0
            for (id in currentInsts) {
                val ip = prog.inst(id)
                if (ip.opCode == InstOp.Companion.OP_CHAR_RANGE) {
                    if (ip.lo <= c && c <= ip.hi) {
                        stack[stackTop++] = ip.out
                    }
                } else if (ip.opCode == InstOp.Companion.OP_CHAR_CLASS) {
                    val bitmap = if (c < 64) ip.bitmap0 else ip.bitmap1
                    if ((bitmap and (1L shl (c and 63))) != 0L) {
                        stack[stackTop++] = ip.out
                    }
                }
            }
            return expand()
        }
    }

    /** Matches full input string from `start` to `end`.  */
    fun matches(text: String, start: Int, end: Int): Boolean {
        var state = initialShiftState
        val tab = this.table
        val accs = this.accelerators
        val scanner = if (accs != null) StringInputScanner(text) else null
        var i = start
        while (i < end) {
            if (accs != null && end - i >= 16) {
                val acc = accs[state / STATE_SHIFT_STEP]
                if (acc != null) {
                    val nextPos: Int = StateAccelerator.Companion.findNextAsciiOrNonAsciiEscape(acc, scanner, i, end)
                    if (nextPos == -1) {
                        break
                    }
                    if (nextPos > i) {
                        i = nextPos
                        if (i >= end) {
                            break
                        }
                    }
                }
            }
            if (WorkCounterConfig.ENABLED) {
                record()
            }
            val c: Char = text.charAt(i++)
            if (c.code >= 128) {
                return false
            }
            state = ((tab[c.code] ushr state) and STATE_MASK.toLong()).toInt()
            if (state == DEAD_STATE) {
                return false
            }
        }
        return (acceptMask and (1L shl state)) != 0L
    }

    /** Matches full input UTF-8 byte scanner from `start` to `end`.  */
    fun matches(scanner: Utf8InputScanner, start: Int, end: Int): Boolean {
        var state = initialShiftState
        val tab = this.table
        val accs = this.accelerators
        val bytes = scanner.bytes()
        var i = start
        while (i < end) {
            if (accs != null && end - i >= 16) {
                val acc = accs[state / STATE_SHIFT_STEP]
                if (acc != null) {
                    val nextPos: Int = StateAccelerator.Companion.findNextAsciiOrNonAsciiEscape(acc, scanner, i, end)
                    if (nextPos == -1) {
                        break
                    }
                    if (nextPos > i) {
                        i = nextPos
                        if (i >= end) {
                            break
                        }
                    }
                }
            }
            if (WorkCounterConfig.ENABLED) {
                record()
            }
            val b = bytes[scanner.offset() + i++].toInt() and 0xFF
            state = ((tab[b] ushr state) and STATE_MASK.toLong()).toInt()
            if (state == DEAD_STATE) {
                return false
            }
        }
        return (acceptMask and (1L shl state)) != 0L
    }

    companion object {
        const val MAX_STATES: Int = 10
        const val STATE_SHIFT_STEP: Int = 6
        val DEAD_STATE: Int = MAX_STATES * STATE_SHIFT_STEP // 60
        const val STATE_MASK: Int = 0x3F

        /**
         * Attempts to compile the compiled [Prog] into a 64-bit [ShiftDfa].
         * 
         * @param prog the compiled NFA program
         * @return a compiled [ShiftDfa] if the DFA determinizes to  10 states, or `null`
         * if it exceeds 10 states or contains unsupported constructs.
         */
        fun compile(prog: Prog?): ShiftDfa? {
            if (prog == null || prog.numCaptures() > 1) {
                return null // Only group 0 supported
            }
            if (prog.hasGraphemeSemantics()
                || prog.hasWordBoundary()
                || prog.anchorEnd()
                || prog.dollarAnchorEnd()
            ) {
                return null
            }

            val startInst = prog.start()
            if (startInst == 0) {
                return null
            }

            val builder = Builder(prog)
            builder.stack[builder.stackTop++] = startInst
            if (!builder.expand()) {
                return null
            }
            val dfaStates: MutableList<IntArray> = ArrayList<IntArray>(MAX_STATES + 1)
            dfaStates.add(builder.copyFrontier())
            val boundaries: IntArray = asciiBoundaries(prog)

            // Transitions are recorded per boundary interval; every character in an interval behaves the
            // same, so the 128-entry rows are only expanded when the table is built.
            val numClasses: Int = boundaries.length - 1
            val transitions = arrayOfNulls<IntArray>(MAX_STATES)

            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */

            val numDfaStates: Int = dfaStates.size()
            val table = LongArray(256)

            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */

            // For non-ASCII bytes (128..255), all states transition to DEAD_STATE
            var nonAsciiDeadRow = 0L
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            Arrays.fill(table, 128, 256, nonAsciiDeadRow)

            var acceptMask = 0L
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */

            // Analyze StateAccelerator for self-loop states
            var accelerators: Array<StateAccelerator?>? = null
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */

            return ShiftDfa(table, acceptMask, 0, numDfaStates, accelerators)
        }

        private fun asciiBoundaries(prog: Prog): IntArray {
            val boundary = BooleanArray(129)
            boundary[0] = true
            boundary[128] = true
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            val boundaries = IntArray(129)
            var count = 0
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return Arrays.copyOf(boundaries, count)
        }

        private fun addAsciiBoundaries(boundary: BooleanArray, lo: Int, hi: Int) {
            if (lo < 128 && hi >= 0) {
                boundary[Math.max(0, lo)] = true
                boundary[Math.min(127, hi) + 1] = true
            }
        }

        private fun hasMatch(prog: Prog, insts: IntArray): Boolean {
            for (id in insts) {
                if (prog.inst(id).opCode == InstOp.Companion.OP_MATCH) {
                    return true
                }
            }
            return false
        }
    }
}
