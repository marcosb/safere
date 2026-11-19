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
    private val table: LongArray,
    private val acceptMask: Long,
    private val initialShiftState: Int,
    private val numStates: Int,
    private val accelerators: Array<StateAccelerator?>?
) {
    fun numStates(): Int {
        return numStates
    }

    fun accelerators(): Array<StateAccelerator?>? {
        return accelerators
    }

    /** Compilation-local scratch; only interned DFA frontiers receive an owned array.  */
    private class Builder(private val prog: Prog) {
        internal val stack: IntArray
        private val frontier: IntArray
        private val visited: IntArray
        internal var stackTop = 0
        internal var frontierSize = 0
        private var generation = 0

        init {
            stack = IntArray(prog.size * 2 + 16)
            frontier = IntArray(prog.size)
            visited = IntArray(prog.size)
        }

        fun copyFrontier(): IntArray {
            return Arrays.copyOf(frontier, frontierSize)
        }

        fun findState(states: MutableList<IntArray>): Int {
            for (i in 0 until states.size) {
                val state = states[i]
                if (Arrays.equals(state, 0, state.size, frontier, 0, frontierSize)) {
                    return i
                }
            }
            return -1
        }

        fun expand(): Boolean {
            frontierSize = 0
            // There are at most 1 + MAX_STATES * 128 expansions, so the stamp cannot overflow.
            generation++
            while (stackTop > 0) {
                val id = stack[--stackTop]
                if (id == 0 || id >= prog.size || visited[id] == generation) {
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
                        if (ip.ranges != null && ip.ranges.size > 0 && ip.ranges[ip.ranges.size - 1] >= 128) {
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
        val scanner: InputScanner? = if (accs != null) StringInputScanner(text) else null
        var i = start
        while (i < end) {
            if (accs != null && end - i >= 16) {
                val acc = accs[state / STATE_SHIFT_STEP]
                if (acc != null) {
                    val nextPos: Int = StateAccelerator.Companion.findNextAsciiOrNonAsciiEscape(acc, scanner!!, i, end)
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
                WorkCounter.record()
            }
            val c = text[i++].code
            if (c >= 128) {
                return false
            }
            state = ((tab[c] ushr state) and STATE_MASK.toLong()).toInt()
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
                    val nextPos: Int = StateAccelerator.Companion.findNextAsciiOrNonAsciiEscape(acc, scanner!!, i, end)
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
                WorkCounter.record()
            }
            val b = bytes!![scanner.offset() + i++].toInt() and 0xFF
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
            val numClasses: Int = boundaries.size - 1
            val transitions = arrayOfNulls<IntArray>(MAX_STATES)
            var accelerators: Array<StateAccelerator?>? = null

            // The state list grows while it is scanned, so the bound must be re-read each iteration.
            var s = 0
            while (s < dfaStates.size) {
                val currentInsts = dfaStates[s]
                val row = IntArray(numClasses)
                transitions[s] = row

                // Every consuming instruction has constant membership between adjacent boundaries.
                // Expanding one representative therefore gives the transition for the entire interval.
                for (cls in 0 until numClasses) {
                    if (!builder.step(currentInsts, boundaries[cls])) {
                        return null // Unsupported instruction encountered
                    }
                    var target: Int
                    if (builder.frontierSize == 0) {
                        target = MAX_STATES // DEAD_STATE index 10
                    } else {
                        target = builder.findState(dfaStates)
                        if (target < 0) {
                            if (dfaStates.size >= MAX_STATES) {
                                return null // State budget exceeded (> 10 states)
                            }
                            target = dfaStates.size
                            dfaStates.add(builder.copyFrontier())
                        }
                    }
                    row[cls] = target
                }
                s++
            }

            val numDfaStates: Int = dfaStates.size
            val table = LongArray(256)

            for (cls in 0 until numClasses) {
                var row = 0L
                for (s in 0 until numDfaStates) {
                    val targetState = transitions[s]!![cls] * STATE_SHIFT_STEP
                    row = row or (targetState.toLong() shl (s * STATE_SHIFT_STEP))
                }
                // Dead state slot (slot 10 at bit offset 60) self-loops to dead state (60)
                row = row or (DEAD_STATE.toLong() shl DEAD_STATE)
                Arrays.fill(table, boundaries[cls], boundaries[cls + 1], row)
            }

            // For non-ASCII bytes (128..255), all states transition to DEAD_STATE
            var nonAsciiDeadRow = 0L
            for (s in 0..MAX_STATES) {
                nonAsciiDeadRow = nonAsciiDeadRow or (DEAD_STATE.toLong() shl (s * STATE_SHIFT_STEP))
            }
            Arrays.fill(table, 128, 256, nonAsciiDeadRow)

            var acceptMask = 0L
            for (s in 0 until numDfaStates) {
                if (hasMatch(prog, dfaStates[s])) {
                    acceptMask = acceptMask or (1L shl (s * STATE_SHIFT_STEP))
                }
            }

            // Analyze StateAccelerator for self-loop states
            for (s in 0 until numDfaStates) {
                var selfLoopCount = 0
                var escapeCount = 0
                val escapes = IntArray(4)

                for (cls in 0 until numClasses) {
                    val lo = boundaries[cls]
                    val hi = boundaries[cls + 1]
                    if (transitions[s]!![cls] == s) {
                        selfLoopCount += hi - lo
                    } else {
                        // Record the first four escape characters, in ascending order.
                        var c = lo
                        while (c < hi && escapeCount + (c - lo) < 4) {
                            escapes[escapeCount + (c - lo)] = c
                            c++
                        }
                        escapeCount += hi - lo
                    }
                }

                // Only accelerate if this is genuinely a dominant self-loop state (>= 120 self-loops)
                var acc: StateAccelerator? = null
                if (selfLoopCount >= 120 && escapeCount >= 1 && escapeCount <= 3) {
                    acc = if (escapeCount == 1) {
                        StateAccelerator.SingleAsciiEscape(escapes[0])
                    } else if (escapeCount == 2) {
                        StateAccelerator.AsciiPairEscape(escapes[0], escapes[1])
                    } else {
                        StateAccelerator.AsciiTripleEscape(escapes[0], escapes[1], escapes[2])
                    }
                }

                if (acc != null) {
                    if (accelerators == null) {
                        accelerators = arrayOfNulls<StateAccelerator>(numDfaStates)
                    }
                    accelerators!![s] = acc
                }
            }

            return ShiftDfa(table, acceptMask, 0, numDfaStates, accelerators)
        }

        private fun asciiBoundaries(prog: Prog): IntArray {
            val boundary = BooleanArray(129)
            boundary[0] = true
            boundary[128] = true
            for (id in 1 until prog.size) {
                val ip = prog.inst(id)
                if (ip.opCode == InstOp.Companion.OP_CHAR_RANGE) {
                    addAsciiBoundaries(boundary, ip.lo, ip.hi)
                } else if (ip.opCode == InstOp.Companion.OP_CHAR_CLASS && ip.ranges != null) {
                    var i = 0
                    while (i < ip.ranges.size && ip.ranges[i] < 128) {
                        addAsciiBoundaries(boundary, ip.ranges[i], ip.ranges[i + 1])
                        i += 2
                    }
                }
            }
            val boundaries = IntArray(129)
            var count = 0
            for (c in 0..128) {
                if (boundary[c]) {
                    boundaries[count++] = c
                }
            }
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
