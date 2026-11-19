// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*
import kotlin.jvm.JvmOverloads

/**
 * One-pass NFA execution engine. For patterns where the NFA is deterministic (at most one possible
 * path for any input), this engine extracts submatch boundaries in a single linear pass over the
 * input text — no backtracking, no thread management.
 * 
 * 
 * A pattern is "one-pass" if, after following all epsilon transitions from any state:
 * 
 * 
 *  1. At most one CHAR_RANGE instruction matches any given input code point.
 *  1. At most one MATCH instruction is reachable.
 *  1. No instruction is reachable via two different epsilon paths.
 * 
 * 
 * 
 * One-pass matching only works for **anchored** searches. For unanchored searches, use the
 * DFA to find the match region first, then run OnePass on that region.
 * 
 * 
 * This is a port of RE2's `onepass.cc`, adapted for Java's Unicode code point model.
 */
internal class OnePass {
    // -------------------------------------------------------------------------
    // Instance fields
    // -------------------------------------------------------------------------
    /**
     * Flattened transition table: `flatActions[stateOffset + eqClass]` = encoded action with
     * pre-scaled nextStateOffset.
     */
    private val flatActions: LongArray

    /**
     * Match actions: `matchAction[stateOffset]` = encoded action when at a match state ([ ][.NO_ACTION] otherwise).
     */
    private val matchAction: LongArray

    /** Sorted code point boundaries defining equivalence classes.  */
    private val boundaries: IntArray

    /** Direct lookup table mapping ASCII code points (0–127) to equivalence class indices.  */
    private val asciiClassMap: IntArray

    /** Whether the program requires end-of-text matching (stripped trailing `$` or \z).  */
    private val anchorEnd: Boolean

    /**
     * When true, the end anchor was `$` (not `\z`), meaning the match may end before a
     * trailing line terminator at end of text. Only meaningful when [.anchorEnd] is true.
     */
    private val dollarAnchorEnd: Boolean
    private val dollarAnchorUnixLines: Boolean

    /** Whether empty-width checks need the grapheme-cluster boundary flag.  */
    private val hasGraphemeSemantics: Boolean

    private constructor(
        flatActions: LongArray,
        matchAction: LongArray,
        boundaries: IntArray,
        anchorEnd: Boolean,
        dollarAnchorEnd: Boolean,
        dollarAnchorUnixLines: Boolean,
        hasGraphemeSemantics: Boolean
    ) {
        this.flatActions = flatActions
        this.matchAction = matchAction
        this.boundaries = boundaries
        this.asciiClassMap = buildAsciiClassMap(boundaries)
        this.anchorEnd = anchorEnd
        this.dollarAnchorEnd = dollarAnchorEnd
        this.dollarAnchorUnixLines = dollarAnchorUnixLines
        this.hasGraphemeSemantics = hasGraphemeSemantics
    }

    private class BuildTables(private val numClasses: Int, private val maxStates: Int) {
        private var actions = LongArray(0)
        private var matchActions = LongArray(0)
        private var allocatedStates = 0
        private var stateCount = 0


        fun stateCount(): Int {
            return stateCount
        }

        fun addState(): Int {
            ensureStateCapacity(stateCount + 1)
            val state = stateCount++
            val stateOffset = state * numClasses
            Arrays.fill(actions, stateOffset, stateOffset + numClasses, NO_ACTION)
            Arrays.fill(matchActions, stateOffset, stateOffset + numClasses, NO_ACTION)
            return state
        }

        fun action(state: Int, cls: Int): Long {
            return actions[state * numClasses + cls]
        }

        fun setAction(state: Int, cls: Int, action: Long) {
            actions[state * numClasses + cls] = action
        }

        fun matchAction(state: Int): Long {
            return matchActions[state * numClasses]
        }

        fun setMatchAction(state: Int, action: Long) {
            matchActions[state * numClasses] = action
        }

        fun actions(): LongArray {
            return actions
        }

        fun matchActions(): LongArray {
            return Arrays.copyOf(matchActions, stateCount * numClasses)
        }

        fun ensureStateCapacity(minStates: Int) {
            if (allocatedStates >= minStates) {
                return
            }
            var newStates = Math.max(minStates, Math.max(4, allocatedStates * 2))
            newStates = Math.min(newStates, maxStates)
            actions = Arrays.copyOf(actions, Math.toIntExact(newStates.toLong() * numClasses))
            matchActions = Arrays.copyOf(matchActions, Math.toIntExact(newStates.toLong() * numClasses))
            allocatedStates = newStates
        }
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------
    /**
     * Searches for a match in the given text starting at position 0. Convenience overload that
     * delegates to [.search].
     * 
     * @param text the input text
     * @param endMatch if true, the match must cover the entire text
     * @param nsubmatch number of submatch groups to track (including group 0)
     * @return submatch positions as `int[2*nsubmatch]`, or null if no match
     */
    fun search(text: String, endMatch: Boolean, nsubmatch: Int): IntArray? {
        return search(StringInputScanner(text!!), 0, text.length, endMatch, nsubmatch)
    }

    fun search(text: InputScanner, endMatch: Boolean, nsubmatch: Int): IntArray? {
        return search(text, 0, text.length, endMatch, nsubmatch)
    }

    fun search(text: InputScanner, endMatch: Boolean, nsubmatch: Int, reuseGroups: IntArray?): IntArray? {
        return search(text, 0, text.length, endMatch, nsubmatch, reuseGroups)
    }

    fun search(text: String, endMatch: Boolean, nsubmatch: Int, reuseGroups: IntArray?): IntArray? {
        return search(StringInputScanner(text!!), 0, text.length, endMatch, nsubmatch, reuseGroups)
    }

    fun search(text: String?, startPos: Int, endPos: Int, endMatch: Boolean, nsubmatch: Int): IntArray? {
        return search(StringInputScanner(text!!), startPos, endPos, endMatch, nsubmatch)
    }

    fun search(
        text: String?, startPos: Int, endPos: Int, endMatch: Boolean, nsubmatch: Int, reuseGroups: IntArray?
    ): IntArray? {
        return search(StringInputScanner(text!!), startPos, endPos, endMatch, nsubmatch, reuseGroups)
    }

    @JvmOverloads
    fun search(
        text: InputScanner,
        startPos: Int,
        endPos: Int,
        endMatch: Boolean,
        nsubmatch: Int,
        reuseGroups: IntArray? = null,
        scratchCap: IntArray? = null
    ): IntArray? {
        val graphemeContext: GraphemeSupport.Context =
            GraphemeSupport.Context.create(text, hasGraphemeSemantics)
        return search(
            text, startPos, endPos, endMatch, nsubmatch, reuseGroups, scratchCap, graphemeContext
        )
    }

    private fun search(
        text: InputScanner,
        startPos: Int,
        endPos: Int,
        endMatch: Boolean,
        nsubmatch: Int,
        reuseGroups: IntArray?,
        scratchCap: IntArray?,
        graphemeContext: GraphemeSupport.Context?
    ): IntArray? {
        val ncap = 2 * Math.max(nsubmatch, 1)
        val cap = if (scratchCap != null && scratchCap.size >= ncap) scratchCap else IntArray(ncap)
        Arrays.fill(cap, 0, ncap, -1)
        cap[0] = startPos

        var stateOffset = 0
        var matched = false
        val bestCap = if (reuseGroups != null && reuseGroups.size >= ncap) reuseGroups else IntArray(ncap)

        val fa = flatActions
        val ma = matchAction
        val ascMap = asciiClassMap
        val stringText =
            if (text is StringInputScanner) text.text() else null

        var pos = startPos
        // Main loop: process characters from startPos to endPos-1.  The match check at endPos is
        // handled after the loop to avoid a redundant pos >= endPos comparison on every iteration.
        while (pos < endPos) {
            // Check match condition at current state BEFORE consuming next character.
            var shouldCopy = false
            if (!endMatch && !(anchorEnd && !dollarAnchorEnd)) {
                val matchAct = ma[stateOffset]
                if (matchAct != NO_ACTION) {
                    val reqEmpty = (matchAct and EMPTY_MASK).toInt()
                    if (reqEmpty == 0
                        || ((reqEmpty and Nfa.emptyFlags(text, pos, hasGraphemeSemantics, graphemeContext).inv())
                                == 0)
                    ) {
                        val capMask = ((matchAct ushr CAP_SHIFT) and CAP_REG_MASK).toInt()
                        if (capMask != 0) {
                            applyCaptures(capMask, pos, cap)
                        }
                        if (ncap > 1) {
                            cap[1] = pos
                        }
                        matched = true
                        shouldCopy = true
                    }
                }
            }

            // Read next character — ASCII fast path avoids codePointAt/charCount overhead.
            val nextPos: Int
            val cls: Int
            if (stringText != null) {
                val ch: Char = stringText[pos]
                if (ch.code < 128) {
                    nextPos = pos + 1
                    cls = ascMap[ch.code]
                } else if (Character.isHighSurrogate(ch)
                    && pos + 1 < endPos && Character.isLowSurrogate(stringText[pos + 1])
                ) {
                    nextPos = pos + 2
                    cls = classOf(Character.toCodePoint(ch, stringText[pos + 1]))
                } else {
                    nextPos = pos + 1
                    cls = classOf(ch.code)
                }
            } else {
                val c = text.asciiAt(pos)
                if (c >= 0) {
                    nextPos = pos + 1
                    cls = ascMap[c]
                } else {
                    val decoded = text.decodeForward(pos)
                    val cp: Int = InputScanner.codePoint(decoded)
                    nextPos = InputScanner.position(decoded)
                    cls = classOf(cp)
                }
            }

            // Equivalence classes and state indices are always valid for a well-formed OnePass
            // automaton, so no bounds check is needed on the flat actions array.
            val action = fa[stateOffset + cls]
            if (action == NO_ACTION) {
                if (shouldCopy) {
                    System.arraycopy(cap, 0, bestCap, 0, ncap)
                }
                break
            }

            if (shouldCopy) {
                var skipCopy = false
                if ((action and MATCH_WINS_MASK) == 0L) {
                    val nextStateOffset = (action ushr INDEX_SHIFT).toInt()
                    val nextMatchAct = ma[nextStateOffset]
                    if (nextMatchAct != NO_ACTION && (nextMatchAct and EMPTY_MASK) == 0L) {
                        skipCopy = true
                    }
                }
                if (!skipCopy) {
                    System.arraycopy(cap, 0, bestCap, 0, ncap)
                }
            }

            // Combined condition check: bits below INDEX_SHIFT encode empty-width flags and capture
            // registers. When all zero, skip both the empty-flags gate and capture application.
            val conditions = action and CONDITION_MASK
            if (conditions != 0L) {
                val reqEmpty = (conditions and EMPTY_MASK).toInt()
                if (reqEmpty != 0) {
                    val curEmpty: Int = Nfa.emptyFlags(text, pos, hasGraphemeSemantics, graphemeContext)
                    if ((reqEmpty and curEmpty.inv()) != 0) {
                        break
                    }
                }
                val capMask = ((conditions ushr CAP_SHIFT) and CAP_REG_MASK).toInt()
                if (capMask != 0) {
                    applyCaptures(capMask, pos, cap)
                }
            }
            stateOffset = (action ushr INDEX_SHIFT).toInt()
            pos = nextPos
        }

        // Final match check at endPos (the position after the last character).
        if (pos == endPos) {
            val matchAct = ma[stateOffset]
            if (matchAct != NO_ACTION) {
                val reqEmpty = (matchAct and EMPTY_MASK).toInt()
                if (reqEmpty == 0
                    || ((reqEmpty and Nfa.emptyFlags(text, pos, hasGraphemeSemantics, graphemeContext).inv())
                            == 0)
                ) {
                    val capMask = ((matchAct ushr CAP_SHIFT) and CAP_REG_MASK).toInt()
                    if (capMask != 0) {
                        applyCaptures(capMask, pos, cap)
                    }
                    if (ncap > 1) {
                        cap[1] = pos
                    }
                    matched = true
                    System.arraycopy(cap, 0, bestCap, 0, ncap)
                }
            }
        }

        if (!matched) {
            return null
        }
        if (endMatch && bestCap[1] != endPos) {
            return null
        }
        if (anchorEnd && bestCap[1] != endPos) {
            // $ (dollarAnchorEnd) allows the match to end before a trailing line terminator.
            if (!dollarAnchorEnd
                || !Nfa.isAtTrailingLineTerminator(text, bestCap[1], dollarAnchorUnixLines)
            ) {
                return null
            }
        }
        return bestCap
    }

    /**
     * Unanchored search: scans from `startPos` through the text, trying an anchored OnePass
     * match at each position. Returns the first (leftmost) match found, with longest-match (greedy)
     * semantics at that position.
     * 
     * @param text the full input text
     * @param startPos first position to try
     * @param searchLimit upper bound on start positions to try
     * @param nsubmatch number of submatch groups to track (including group 0)
     * @return submatch positions relative to `text`, or null if no match
     */
    fun searchUnanchored(text: String?, startPos: Int, searchLimit: Int, nsubmatch: Int): IntArray? {
        return searchUnanchored(StringInputScanner(text!!), startPos, searchLimit, nsubmatch)
    }

    fun searchUnanchored(text: InputScanner, startPos: Int, searchLimit: Int, nsubmatch: Int): IntArray? {
        val textLen = text.length
        val limit = Math.min(searchLimit, textLen) + 1
        val graphemeContext: GraphemeSupport.Context =
            GraphemeSupport.Context.create(text, hasGraphemeSemantics)
        var start = startPos
        while (start < limit) {
            val result = search(text, start, textLen, false, nsubmatch, null, null, graphemeContext)
            if (result != null) {
                return result
            }
            // Advance to next code point boundary.
            if (start < textLen) {
                start = InputScanner.position(text.decodeForward(start)) - 1
            }
            start++
        }
        return null
    }

    /** Maps a code point to its equivalence class index.  */
    private fun classOf(cp: Int): Int {
        if (cp < 128 && cp >= 0) {
            return asciiClassMap[cp]
        }
        val idx = Arrays.binarySearch(boundaries, cp)
        return if (idx >= 0) idx else (-idx - 1) - 1
    }

    /** Prevents instantiation via no-arg constructor.  */

    companion object {
        /**
         * Maximum number of capture groups the one-pass engine supports (including group 0). This limit
         * exists because capture group tracking is encoded in a bitmask within each action integer.
         * Matches RE2's `kMaxCap`.
         */
        const val MAX_CAPTURE_GROUPS: Int = 16

        private val MAX_CAP_REGS: Int = 2 * MAX_CAPTURE_GROUPS

        // OnePass is an optional accelerator. Keep its dense action tables within a fixed raw payload
        // budget and fall back to the other engines when a pattern would exceed it.
        private const val MAX_STATES = 65000
        private val MAX_ACTION_BYTES = 16L * 1024L * 1024L
        val MAX_ACTION_CELLS: Long = MAX_ACTION_BYTES / java.lang.Long.BYTES

        // -------------------------------------------------------------------------
        // Action encoding: each action is packed into a single long.
        //
        //   bits  0-13: empty-width flags required for this transition
        //   bits 14-45: capture mask (which capture registers to set)
        //   bit     46: match priority
        //   bits 47-63: next state index
        //
        // Special value: NO_ACTION (-1L) means no valid transition.
        // -------------------------------------------------------------------------
        private const val EMPTY_BITS = 14
        private val CAP_SHIFT: Int = EMPTY_BITS
        private val INDEX_SHIFT: Int = CAP_SHIFT + MAX_CAP_REGS + 1
        private val EMPTY_MASK = (1L shl EMPTY_BITS) - 1
        private val CAP_REG_MASK = (1L shl MAX_CAP_REGS) - 1
        private val NO_ACTION = -1L

        /**
         * Mask covering all "condition" bits in an action (empty-width flags + capture registers). When
         * `(action & CONDITION_MASK) == 0`, both the empty-flags check and the capture application
         * can be skipped entirely — a single branch instead of two.
         */
        private val CONDITION_MASK = (1L shl (CAP_SHIFT + MAX_CAP_REGS)) - 1

        private val MATCH_WINS_MASK = 1L shl (CAP_SHIFT + MAX_CAP_REGS)

        private fun encodeAction(nextState: Int, capMask: Int, emptyFlags: Int, matchWins: Boolean = false): Long {
            var action =
                ((nextState.toLong() shl INDEX_SHIFT)
                        or ((capMask.toLong() and CAP_REG_MASK) shl CAP_SHIFT)
                        or (emptyFlags.toLong() and EMPTY_MASK))
            if (matchWins) {
                action = action or MATCH_WINS_MASK
            }
            return action
        }

        // -------------------------------------------------------------------------
        // Building the one-pass automaton
        // -------------------------------------------------------------------------
        /**
         * Attempts to build a one-pass automaton from the compiled program. Returns `null` if the
         * pattern is not one-pass, exceeds the capture group limit, or exceeds the transition-table
         * memory budget.
         */
        fun build(prog: Prog): OnePass? {
            if (prog.start() == 0) {
                return null
            }
            if (prog.numCaptures() > MAX_CAPTURE_GROUPS) {
                return null
            }

            // Case-folding CHAR_RANGE instructions are supported via case-fold boundary expansion.
            val boundaries: IntArray = buildBoundaries(prog)
            val numClasses: Int = boundaries.size

            val maxStates: Int = maxOnePassStates(prog)
            if (maxStates > MAX_STATES || exceedsActionBudget(maxStates, numClasses)) {
                return null
            }

            // State table. States are identified by instruction IDs.
            // nodeMap: instruction ID -> state index (-1 if unallocated).
            val nodeMap = IntArray(prog.size)
            Arrays.fill(nodeMap, -1)
            val tables = BuildTables(numClasses, maxStates)

            // BFS worklist of instruction IDs.
            val worklist = IntArray(maxStates)
            var workHead = 0
            var workTail = 0

            val startInst = prog.start()
            nodeMap[startInst] = tables.addState()
            worklist[workTail++] = startInst

            // Scratch buffers for BFS and epsilon closure traversal.
            val visitedEpoch = IntArray(prog.size)
            var currentEpoch = 0

            // Stack entries: triplets of (instId, capMask, emptyFlags).
            var stack = IntArray(prog.size * 3)

            val nextStates = IntArray(numClasses)
            val capMasks = IntArray(numClasses)
            val emptyFlagsList = IntArray(numClasses)

            while (workHead < workTail) {
                val instId = worklist[workHead++]
                val stateIndex = nodeMap[instId]

                currentEpoch++
                var stackTop = 0
                stack[stackTop++] = instId
                stack[stackTop++] = 0 // capMask
                stack[stackTop++] = 0 // emptyFlags

                Arrays.fill(nextStates, -1)
                var matched = false

                while (stackTop > 0) {
                    val emptyFlags = stack[--stackTop]
                    val capMask = stack[--stackTop]
                    val id = stack[--stackTop]

                    if (id == 0 || id >= prog.size) {
                        continue
                    }
                    if (visitedEpoch[id] == currentEpoch) {
                        // Same instruction reachable via two epsilon paths -> not one-pass.
                        return null
                    }
                    visitedEpoch[id] = currentEpoch

                    val ip = prog.inst(id)
                    when (ip.op) {
                        InstOp.FAIL -> {}
                        InstOp.ALT, InstOp.ALT_MATCH -> {
                            if (stackTop + 6 > stack.size) {
                                stack = Arrays.copyOf(stack, stack.size * 2)
                            }
                            stack[stackTop++] = ip.out
                            stack[stackTop++] = capMask
                            stack[stackTop++] = emptyFlags
                            stack[stackTop++] = ip.out1
                            stack[stackTop++] = capMask
                            stack[stackTop++] = emptyFlags
                        }

                        InstOp.NOP, InstOp.LOOP_ENTER -> {
                            if (stackTop + 3 > stack.size) {
                                stack = Arrays.copyOf(stack, stack.size * 2)
                            }
                            stack[stackTop++] = ip.out
                            stack[stackTop++] = capMask
                            stack[stackTop++] = emptyFlags
                        }

                        InstOp.PROGRESS_CHECK -> {
                            if (stackTop + 6 > stack.size) {
                                stack = Arrays.copyOf(stack, stack.size * 2)
                            }
                            stack[stackTop++] = ip.out
                            stack[stackTop++] = capMask
                            stack[stackTop++] = emptyFlags
                            stack[stackTop++] = ip.out1
                            stack[stackTop++] = capMask
                            stack[stackTop++] = emptyFlags
                        }

                        InstOp.CAPTURE -> {
                            if (stackTop + 3 > stack.size) {
                                stack = Arrays.copyOf(stack, stack.size * 2)
                            }
                            val reg = ip.arg
                            val newCapMask = if (reg < MAX_CAP_REGS) (capMask or (1 shl reg)) else capMask
                            stack[stackTop++] = ip.out
                            stack[stackTop++] = newCapMask
                            stack[stackTop++] = emptyFlags
                        }

                        InstOp.EMPTY_WIDTH -> {
                            if (stackTop + 3 > stack.size) {
                                stack = Arrays.copyOf(stack, stack.size * 2)
                            }
                            val newEmpty = emptyFlags or ip.arg
                            stack[stackTop++] = ip.out
                            stack[stackTop++] = capMask
                            stack[stackTop++] = newEmpty
                        }

                        InstOp.CHAR_RANGE -> {
                            // For each equivalence class this CHAR_RANGE covers, set transition.
                            for (cls in 0..<numClasses) {
                                val classLo = boundaries[cls]
                                if (!ip.matchesChar(classLo)) {
                                    continue
                                }

                                // Get or create state for ip.out.
                                var nextState = nodeMap[ip.out]
                                if (nextState == -1) {
                                    if (tables.stateCount() >= maxStates) {
                                        return null // too many states
                                    }
                                    nextState = tables.addState()
                                    nodeMap[ip.out] = nextState
                                    worklist[workTail++] = ip.out
                                }

                                if (nextStates[cls] != -1
                                    && (nextStates[cls] != nextState || capMasks[cls] != capMask || emptyFlagsList[cls] != emptyFlags)
                                ) {
                                    // Two different transitions for the same equivalence class -> not one-pass.
                                    return null
                                }
                                nextStates[cls] = nextState
                                capMasks[cls] = capMask
                                emptyFlagsList[cls] = emptyFlags
                            }
                        }

                        InstOp.CHAR_CLASS -> {
                            // Ranges are sorted and disjoint, and buildBoundaries includes every range start
                            // and end + 1. Walk both ordered lists once: no equivalence class straddles a range
                            // endpoint, so its lower bound determines membership.
                            var cls = 0
                            var ri = 0
                            while (ri < ip.ranges.size) {
                                val rLo = ip.ranges[ri]
                                val rHi = ip.ranges[ri + 1]
                                while (cls < numClasses && boundaries[cls] < rLo) {
                                    cls++
                                }
                                while (cls < numClasses && boundaries[cls] <= rHi) {
                                    var nextState = nodeMap[ip.out]
                                    if (nextState == -1) {
                                        if (tables.stateCount() >= maxStates) {
                                            return null
                                        }
                                        nextState = tables.addState()
                                        nodeMap[ip.out] = nextState
                                        worklist[workTail++] = ip.out
                                    }

                                    if (nextStates[cls] != -1
                                        && (nextStates[cls] != nextState || capMasks[cls] != capMask || emptyFlagsList[cls] != emptyFlags)
                                    ) {
                                        return null
                                    }
                                    nextStates[cls] = nextState
                                    capMasks[cls] = capMask
                                    emptyFlagsList[cls] = emptyFlags
                                    cls++
                                }
                                ri += 2
                            }
                        }

                        InstOp.MATCH -> {
                            val action = encodeAction(0, capMask, emptyFlags)
                            val existing = tables.matchAction(stateIndex)
                            if (existing != NO_ACTION && existing != action) {
                                // Two match paths with different conditions -> not one-pass.
                                return null
                            }
                            tables.setMatchAction(stateIndex, action)
                            matched = true
                        }

                        else -> {}
                    }
                }

                for (cls in 0..<numClasses) {
                    if (nextStates[cls] != -1) {
                        val action: Long =
                            encodeAction(
                                nextStates[cls] * numClasses, capMasks[cls], emptyFlagsList[cls], matched
                            )
                        val existing = tables.action(stateIndex, cls)
                        if (existing != NO_ACTION && existing != action) {
                            return null
                        }
                        tables.setAction(stateIndex, cls, action)
                    }
                }
            }

            return OnePass(
                tables.actions(),
                tables.matchActions(),
                boundaries,
                prog.anchorEnd(),
                prog.dollarAnchorEnd(),
                prog.dollarAnchorUnixLines(),
                prog.hasGraphemeSemantics()
            )
        }

        private fun exceedsActionBudget(maxStates: Int, numClasses: Int): Boolean {
            // Both dense tables count toward the memory budget. Every offset must also fit in the
            // action word after the assertion flags, capture mask, and priority bit.
            val maxCells = Math.min(MAX_ACTION_CELLS / 2, 1L shl (java.lang.Long.SIZE - INDEX_SHIFT))
            return maxStates > maxCells / numClasses
        }

        private fun maxOnePassStates(prog: Prog): Int {
            var maxStates = 1 // start state
            for (i in 0..<prog.size) {
                val inst = prog.inst(i)
                if (inst.op == InstOp.CHAR_RANGE || inst.op == InstOp.CHAR_CLASS) {
                    maxStates++
                }
            }
            return maxStates
        }

        /** Builds sorted code point boundaries from all CHAR_RANGE and CHAR_CLASS instructions.  */
        private fun buildBoundaries(prog: Prog): IntArray {
            val bounds = IntArrayList()
            bounds.add(0)
            bounds.add(Utils.MAX_RUNE + 1)
            for (i in 0..<prog.size) {
                val inst = prog.inst(i)
                if (inst.op == InstOp.CHAR_RANGE) {
                    bounds.add(inst.lo)
                    if (inst.hi < Utils.MAX_RUNE) {
                        bounds.add(inst.hi + 1)
                    }
                    if (inst.foldCase) {
                        for (r in inst.lo..inst.hi) {
                            var folded: Int = Inst.simpleFold(r)
                            while (folded != r) {
                                bounds.add(folded)
                                if (folded < Utils.MAX_RUNE) {
                                    bounds.add(folded + 1)
                                }
                                folded = Inst.simpleFold(folded)
                            }
                        }
                    }
                } else if (inst.op == InstOp.CHAR_CLASS) {
                    var j = 0
                    while (j < inst.ranges.size) {
                        bounds.add(inst.ranges[j])
                        if (inst.ranges[j + 1] < Utils.MAX_RUNE) {
                            bounds.add(inst.ranges[j + 1] + 1)
                        }
                        j += 2
                    }
                }
            }
            return bounds.toSortedUniqueArray()
        }

        /**
         * Builds a 128-element lookup table mapping ASCII code points (0–127) to their equivalence class
         * indices, avoiding binary search for the most common characters.
         */
        private fun buildAsciiClassMap(boundaries: IntArray): IntArray {
            val map = IntArray(128)
            for (cp in 0..127) {
                val idx = Arrays.binarySearch(boundaries, cp)
                map[cp] = if (idx >= 0) idx else (-idx - 1) - 1
            }
            return map
        }

        /** Applies capture register updates from a pre-extracted capture mask at the given position.  */
        private fun applyCaptures(mask: Int, pos: Int, cap: IntArray) {
            var mask = mask
            while (mask != 0) {
                val reg = Integer.numberOfTrailingZeros(mask)
                if (reg >= cap.size) {
                    break
                }
                cap[reg] = pos
                mask = mask and mask - 1 // clear lowest set bit
            }
        }
    }
}
