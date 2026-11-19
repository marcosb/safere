// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*

/**
 * Bit-state backtracking execution engine. Uses explicit stack-based backtracking with a visited
 * bitmap to guarantee O(|prog|  |text|) time complexity, preventing exponential blowup.
 * 
 * 
 * The visited bitmap tracks which (instruction, position) pairs have been explored. If a pair
 * has already been visited, it is skipped — this ensures each pair is processed at most once.
 * 
 * 
 * BitState is faster than the general NFA (Pike VM) for small-to-medium texts because it avoids
 * per-step thread queue management. It is used when:
 * 
 * 
 *  * The pattern is not one-pass (or requires unanchored matching).
 *  * The text is small enough that the visited bitmap fits in memory.
 *  * Submatch (capture group) information is needed.
 * 
 * 
 * 
 * This is a port of RE2's `bitstate.cc`, adapted for Java's Unicode code point model.
 */
internal class BitState(// -------------------------------------------------------------------------
    // Instance fields
    // -------------------------------------------------------------------------
    private val prog: Prog,
    text: InputScanner,
    startPos: Int,
    endPos: Int,
    ncap: Int,
    longest: Boolean,
    endMatch: Boolean,
    greedyRunes: Array<Inst?>?
) {
    /**
     * Runs the bit-state search from the given start position, writing successful captures into
     * `resultBuffer` when it is large enough.
     */
    /**
     * Runs the bit-state search from the given start position.
     * 
     * @param startPos the char index at which to begin searching
     * @param searchLimit upper bound on start positions to try
     * @param anchored if true, match must start at `startPos`
     * @return submatch positions, or null if no match
     */
    @JvmOverloads
    fun doSearch(startPos: Int, searchLimit: Int, anchored: Boolean, resultBuffer: IntArray? = null): IntArray? {
        return doSearch(startPos, searchLimit, anchored, resultBuffer, false)
    }

    /**
     * Speculates within the allocated window while assertions still see the full input. A match is
     * authoritative only if no higher-priority path needs to consume beyond the window. An unresolved
     * path aborts the whole attempt before a lower-priority match can be returned.
     */
    fun doSearchWindow(startPos: Int, searchLimit: Int, anchored: Boolean, resultBuffer: IntArray?): IntArray? {
        check(!(longest || endMatch || prog.hasGraphemeSemantics())) { "Search windows require unconstrained first-match semantics" }
        return doSearch(startPos, searchLimit, anchored, resultBuffer, true)
    }

    private fun doSearch(
        startPos: Int, searchLimit: Int, anchored: Boolean, resultBuffer: IntArray?, speculative: Boolean
    ): IntArray? {
        this.speculative = speculative
        windowExceeded = false
        budgetExceeded = false
        stepCount = 0
        stepBudget = Math.max(4096L, MAX_WORK_PER_SLOT.toLong() * prog.size * textSlots)
        bestMatch = null
        matchResult =
            if (resultBuffer != null && resultBuffer.size >= ncap) resultBuffer else IntArray(ncap)
        val limit = if (anchored) startPos + 1 else Math.min(searchLimit + 1, textLen + 1)
        pruneAcrossStarts = false
        unvisitedKindLimit = ALT
        nextStepThreshold =
            if (limit - startPos > 1) Math.min(stepBudget, PRUNE_ACTIVATION_STEPS) else stepBudget
        var searchStart = startPos
        while (searchStart < limit) {
            if (speculative && searchStart > endPos) {
                windowExceeded = true
                return null
            }
            if (trySearch(prog.start(), searchStart)) {
                return bestMatch
            }
            if (budgetExceeded || windowExceeded) {
                return null
            }
            if (pruneAcrossStarts) {
                foldVisitedIntoDead()
            }
            if (searchStart < textLen) {
                searchStart = InputScanner.position(text!!.decodeForward(searchStart)) - 1
            }
            searchStart++
        }
        return null
    }

    private val instructions: Array<Inst?>
    private var text: InputScanner? = null
    private var textLen: Int = 0
    private var endPos: Int = 0
    private var longest: Boolean = false
    private var endMatch: Boolean = false
    private var ncap: Int = 0
    private var graphemeContext: GraphemeSupport.Context? = null

    /**
     * Per-start visited bitmap: bit `((pos - basePos) * progSize + instId)` tracks which (ALT,
     * position) pairs the current start position's search has reached. Position-major layout keeps
     * one start's marks contiguous, so folding them into [.dead] costs no more than the search
     * that produced them. Sized for the full text so the instance can be reused across searches with
     * different start/end bounds.
     */
    private val visited: LongArray

    /**
     * Cross-start pruning bitmap, same layout as [.visited]: set for every ALT reached by an
     * earlier start position whose search failed. Whether a match is reachable from an (ALT,
     * position) pair does not depend on capture values, so a pair that led nowhere from one start
     * leads nowhere from any later start either. Checking it makes unanchored search linear (RE2 gets
     * the same effect by never clearing its visited bitmap between starts) while still letting
     * non-cycle ALTs be revisited within a single start, which capture priority requires.
     * 
     * 
     * PROGRESS_CHECK loop registers do not break this. They only guard nullable loop bodies, so a
     * first visit's body-only branch still reaches the exit through a zero-width iteration, and a
     * path that arrives with the register equal to the current position (exit-only) exists only
     * because the same start already took the body from that position. Either way the failed start
     * explored every future a later start could have through the pruned pair.
     * 
     * 
     * Allocated unconditionally alongside [.visited], even though anchored-only instances
     * never use it: lazily allocating it on first unanchored use was tried and measured 40-60% slower
     * on unanchored searches (the case that actually prunes), for a memory saving that only applies
     * to anchored-only instances. Not a good trade.
     */
    private val dead: LongArray

    /** Dirty word ranges of [.visited] and [.dead]; clears touch only these words.  */
    private var visitedLo: Int = 0
    private var visitedHi: Int = 0
    private var deadLo: Int = 0
    private var deadHi: Int = 0
    private var textSlots: Int = 0
    private val progSize: Int

    /** Whether the current [.doSearch] prunes later starts with [.dead].  */
    private var pruneAcrossStarts = false

    /**
     * Highest [.altKind] value that [.shouldVisit] accepts without touching the bitmaps:
     * [.ALT] while cross-start pruning is inactive, [.NOT_ALT] once active.
     */
    private var unvisitedKindLimit: Byte = 0.toByte()

    /**
     * Step count at which [.trySearch] either activates cross-start pruning or trips [ ][.stepBudget]. Merging both checks keeps a single branch on the hot instruction loop.
     */
    private var nextStepThreshold: Long = 0L

    /**
     * Per-instruction kind for [.shouldVisit]: 0 = not an ALT, 1 = ALT, 2 = epsilon-cycle ALT.
     */
    private val altKind: ByteArray

    /** Straight-line capture/consumer/capture sequences, or null when none exist.  */
    private val captureRunes: Array<CaptureRune?>?

    /** Loop entry/check continuations, optionally including one adjacent capture write.  */
    private val loopEntries: Array<LoopEntry?>?
    private val lazyRuneLoops: Array<LazyRuneLoop?>?

    /** Progress checks immediately following captures, or null when none exist.  */
    private val captureProgressChecks: Array<Inst?>?

    /** Consuming bodies of simple greedy loops, or null when none exist.  */
    private val greedyRunes: Array<Inst?>?

    /** Current capture registers.  */
    private lateinit var cap: IntArray

    /** Current loop progress-check registers.  */
    private val loopRegs: IntArray

    /** Best match found so far.  */
    private var bestMatch: IntArray? = null

    /** Caller-owned or BitState-owned array that receives successful capture results.  */
    private lateinit var matchResult: IntArray

    /** Work-budget accounting for falling back when BitState backtracking is too expensive.  */
    private var stepBudget: Long = 0L
    private var stepCount: Long = 0L
    private var budgetExceeded: Boolean = false
    private var speculative = false
    private var windowExceeded = false

    /** Explicit job stack for backtracking.  */
    private lateinit var jobInstId: IntArray

    private lateinit var jobPos: IntArray
    private var jobCount: Int = 0
    private var basePos: Int = 0

    /** Creates a fresh workspace. Passing null greedy-loop metadata uses ordinary ALT dispatch.  */
    init {
        this.instructions = prog.frozenInstructions()!!
        this.text = text
        this.textLen = text.length
        this.basePos = startPos
        this.endPos = endPos
        this.longest = longest
        this.endMatch = endMatch || prog.anchorEnd()
        this.ncap = ncap
        this.textSlots = (endPos - basePos) + 2
        this.progSize = prog.size
        this.graphemeContext = GraphemeSupport.Context.create(text, prog.hasGraphemeSemantics())

        val cycleAlts = prog.epsilonCycleAlts()!!
        this.altKind = ByteArray(progSize)
        for (i in 0..<progSize) {
            val instruction = prog.inst(i)
            if (cycleAlts!![i]) {
                altKind[i] = CYCLE_ALT
            } else {
                val op = instruction!!.opCode
                if (op == InstOp.OP_ALT || op == InstOp.OP_ALT_MATCH) {
                    altKind[i] = ALT
                }
            }
        }
        this.captureRunes = prog.captureRunes()
        this.loopEntries = prog.loopEntries()
        this.lazyRuneLoops = prog.lazyRuneLoops()
        this.captureProgressChecks = prog.captureProgressChecks()
        this.greedyRunes = greedyRunes

        val totalBits = progSize * textSlots
        val visitedLen = (totalBits + 63) / 64
        this.visited = LongArray(visitedLen)
        this.dead = LongArray(visitedLen)
        this.visitedLo = Integer.MAX_VALUE
        this.visitedHi = -1
        this.deadLo = Integer.MAX_VALUE
        this.deadHi = -1

        this.cap = IntArray(ncap)
        Arrays.fill(cap, -1)
        val nlr = prog.numLoopRegs()
        this.loopRegs = IntArray(nlr)
        if (nlr > 0) {
            Arrays.fill(loopRegs, -1)
        }

        val maxJobs = Math.min(totalBits, 4096)
        this.jobInstId = IntArray(maxJobs)
        this.jobPos = IntArray(maxJobs)
        this.jobCount = 0
        this.stepBudget = Math.max(4096L, MAX_WORK_PER_SLOT.toLong() * prog.size * textSlots)
        this.nextStepThreshold = this.stepBudget
        this.unvisitedKindLimit = ALT
        this.stepCount = 0
        this.budgetExceeded = false
    }

    /**
     * Returns true if (instId, pos) should be explored. Records every ALT reached once cross-start
     * pruning is active so a failed start can prune later ones via [.dead]; within one start,
     * only epsilon-cycle ALTs are blocked.
     * 
     * 
     * Only ALT/ALT_MATCH instructions that participate in epsilon cycles are blocked on revisit.
     * An epsilon cycle is a path from an ALT back to itself through only epsilon transitions (ALT,
     * NOP, CAPTURE, EMPTY_WIDTH) — without any CHAR_RANGE to consume input. Only these can cause
     * infinite loops.
     * 
     * 
     * Non-cycle ALTs can be safely revisited. This is critical for nested quantifiers where an
     * inner repetition (e.g., `.+?`) and an outer repetition (e.g., `*`) share the same
     * ALT entry instruction. If the visited bitmap blocked the shared ALT, the outer repetition could
     * not re-enter its body, causing a premature match.
     * 
     * 
     * All other instruction types are always revisitable:
     * 
     * 
     *  * MATCH — terminal, no outgoing edges
     *  * FAIL — terminal, no outgoing edges
     *  * CAPTURE, NOP, EMPTY_WIDTH — epsilon with a single outgoing edge, cannot form cycles alone
     *  * CHAR_RANGE — consumes input (advances position), cannot form cycles
     * 
     * 
     * 
     * Kept tiny so C2 inlines the array-indexed fast exit across all call sites in [ ][.trySearch].
     */
    @MaxBytecodeSize(
        value = 35, reason = ("C2 always inlines methods of at most MaxInlineSize (35) bytes. trySearch calls this from"
                + " many sites. At 149 bytes it inflated the hot loop, one of the causes of the 36%"
                + " short-search regression in #1116. Move new work into shouldVisitTracked.")
    )
    private fun shouldVisit(instId: Int, pos: Int): Boolean {
        val kind = altKind[instId].toInt()
        return kind <= unvisitedKindLimit || shouldVisitTracked(kind, instId, pos)
    }

    private fun shouldVisitTracked(kind: Int, instId: Int, pos: Int): Boolean {
        val bit = (pos - basePos) * progSize + instId
        val word = bit ushr 6
        val mask = 1L shl (bit and 63)
        if (pruneAcrossStarts && (dead[word] and mask) != 0L) {
            return false // an earlier start already exhausted this pair without matching
        }
        val w = visited[word]
        if ((w and mask) != 0L) {
            // Cycle ALT: block the epsilon loop. Non-cycle ALT: revisitable, and already recorded.
            return kind == ALT.toInt()
        }
        visited[word] = w or mask
        if (word < visitedLo) {
            visitedLo = word
        }
        if (word > visitedHi) {
            visitedHi = word
        }
        return true
    }

    /** Moves the failed start's marks into [.dead] and clears them for the next start.  */
    private fun foldVisitedIntoDead() {
        if (visitedHi < visitedLo) {
            return
        }
        for (w in visitedLo..visitedHi) {
            dead[w] = dead[w] or visited[w]
            visited[w] = 0L
        }
        if (visitedLo < deadLo) {
            deadLo = visitedLo
        }
        if (visitedHi > deadHi) {
            deadHi = visitedHi
        }
        visitedLo = Integer.MAX_VALUE
        visitedHi = -1
    }

    /** Pushes a job onto the stack, growing if needed.  */
    private fun push(instId: Int, pos: Int) {
        if (jobCount >= jobInstId.size) {
            val newLen: Int = jobInstId.size * 2
            jobInstId = Arrays.copyOf(jobInstId, newLen)
            jobPos = Arrays.copyOf(jobPos, newLen)
        }
        jobInstId[jobCount] = instId
        jobPos[jobCount] = pos
        jobCount++
    }

    /** Charges every logical instruction, including instructions executed without a stack job.  */
    private fun stepWithinBudget(): Boolean {
        if (WorkCounterConfig.ENABLED) {
            WorkCounter.record()
        }
        return ++stepCount <= nextStepThreshold || onStepThresholdExceeded()
    }

    private fun onStepThresholdExceeded(): Boolean {
        if (stepCount > stepBudget) {
            budgetExceeded = true
            return false
        }
        pruneAcrossStarts = true
        unvisitedKindLimit = NOT_ALT
        nextStepThreshold = stepBudget
        return true
    }

    /**
     * Attempts a search starting from the given instruction and position. Returns true if a match is
     * found (stored in [.bestMatch]).
     */
    private fun trySearch(startInst: Int, startPos: Int): Boolean {
        var matched = false

        // Initialize captures and loop registers.
        Arrays.fill(cap, -1)
        if (ncap > 0) {
            cap[0] = startPos
        }
        if (loopRegs.size > 0) {
            Arrays.fill(loopRegs, -1)
        }

        // Seed the search.
        jobCount = 0
        if (shouldVisit(startInst, startPos)) {
            push(startInst, startPos)
        }

        while (jobCount > 0) {
            jobCount--
            var id = jobInstId[jobCount]
            var pos = jobPos[jobCount]
            // Keep immediate continuations local. The stack contains deferred alternatives and
            // register restores; every instruction still consumes the same logical work budget.
            while (true) {
                if (!stepWithinBudget()) {
                    return false
                }

                // Negative IDs are restore sentinels: cap or loopReg restore on backtrack.
                // Capture sentinels use -(reg+1) where reg < ncap.
                // Loop-reg sentinels use -(ncap+reg+1) where reg is the loop register index.
                if (id < 0) {
                    val idx = -id - 1
                    if (idx < ncap) {
                        cap[idx] = pos
                    } else {
                        loopRegs[idx - ncap] = pos
                    }
                    break
                }

                val ip = instructions[id]!!
                when (ip.opCode) {
                    InstOp.OP_FAIL -> {}
                    InstOp.OP_ALT, InstOp.OP_ALT_MATCH -> {
                        val rune = if (greedyRunes == null) null else greedyRunes[id]
                        if (rune != null) {
                            runGreedyLoop(id, ip, rune, pos)
                            if (budgetExceeded || windowExceeded) {
                                return false
                            }
                            break
                        }
                        val loop = if (lazyRuneLoops == null) null else lazyRuneLoops[id]
                        if (loop != null) {
                            pos = skipFailedLazyExits(loop, id, pos)
                            if (pos < 0) {
                                if (budgetExceeded) {
                                    return false
                                }
                                break
                            }
                        }
                        // Push second alternative first (it will be tried if first fails).
                        if (shouldVisit(ip.out1, pos)) {
                            push(ip.out1, pos)
                        }
                        // Follow the first alternative immediately at higher priority.
                        if (shouldVisit(ip.out, pos)) {
                            id = ip.out
                            continue
                        }
                    }

                    InstOp.OP_NOP -> {
                        if (shouldVisit(ip.out, pos)) {
                            id = ip.out
                            continue
                        }
                    }

                    InstOp.OP_CAPTURE -> {
                        val reg = ip.arg
                        if (reg < ncap) {
                            // Push restore sentinel: if we backtrack past this, undo the capture.
                            push(-(reg + 1), cap[reg])
                            cap[reg] = pos
                        }
                        val fused = if (captureRunes == null) null else captureRunes[id]
                        if (fused != null) {
                            // These two successors are not alternatives, assertions, or loop instructions.
                            // Execute the same writes/restores and budget charges without redispatching them.
                            // Charge each logical successor before continuation pruning, including revisits,
                            // so the budget conservatively accounts for all fused work.
                            if (!stepWithinBudget()) {
                                return false
                            }
                            if (pos < endPos) {
                                var cp = if (WorkCounterConfig.ENABLED) -1 else text!!.asciiAt(pos)
                                var nextPos = pos + 1
                                if (cp < 0) {
                                    val decoded = text!!.decodeForward(pos)
                                    cp = InputScanner.codePoint(decoded)
                                    nextPos = InputScanner.position(decoded)
                                    if (speculative && nextPos > endPos) {
                                        windowExceeded = true
                                        return false
                                    }
                                }
                                val rune = fused.rune!!
                                val matches =
                                    if (rune.opCode == InstOp.OP_CHAR_RANGE)
                                        rune.matchesChar(cp)
                                    else
                                        rune.matchesCharClass(cp)
                                if (matches) {
                                    if (!stepWithinBudget()) {
                                        return false
                                    }
                                    val endRegister = fused.endRegister
                                    if (endRegister < ncap) {
                                        push(-(endRegister + 1), cap[endRegister])
                                        cap[endRegister] = nextPos
                                    }
                                    if (shouldVisit(fused.out, nextPos)) {
                                        id = fused.out
                                        pos = nextPos
                                        continue
                                    }
                                }
                            } else if (speculative && endPos < textLen) {
                                windowExceeded = true
                                return false
                            }
                        } else {
                            val check = if (captureProgressChecks == null) null else captureProgressChecks[id]
                            if (check != null) {
                                // CAPTURE has already installed its undo. The adjacent check cannot be pruned,
                                // so execute it with the same work charge and register/alternative ordering.
                                if (!stepWithinBudget()) {
                                    return false
                                }
                                val next = progressContinuation(check, pos)
                                if (next >= 0) {
                                    id = next
                                    continue
                                }
                            } else if (shouldVisit(ip.out, pos)) {
                                id = ip.out
                                continue
                            }
                        }
                    }

                    InstOp.OP_EMPTY_WIDTH -> {
                        val curFlags: Int = Nfa.emptyFlags(text!!, pos, prog.hasGraphemeSemantics(), graphemeContext)
                        if ((ip.arg and curFlags.inv()) == 0) {
                            if (shouldVisit(ip.out, pos)) {
                                id = ip.out
                                continue
                            }
                        }
                    }

                    InstOp.OP_LOOP_ENTER -> {
                        val reg = ip.arg
                        push(-(ncap + reg + 1), loopRegs[reg])
                        loopRegs[reg] = -1
                        val entry = if (loopEntries == null) null else loopEntries[id]
                        if (entry != null) {
                            // The adjacent check observes the register just reset by this entry, so it must
                            // enter the body. Keep one restore of the original value: the intermediate -1
                            // cannot be observed by an alternative. Still charge both logical instructions.
                            if (!stepWithinBudget()) {
                                return false
                            }
                            loopRegs[reg] = pos
                            val capture = entry.captureRegister
                            if (capture >= 0) {
                                if (!stepWithinBudget()) {
                                    return false
                                }
                                if (capture < ncap) {
                                    push(-(capture + 1), cap[capture])
                                    cap[capture] = pos
                                }
                            }
                            if (shouldVisit(entry.out, pos)) {
                                id = entry.out
                                continue
                            }
                            break
                        }
                        if (shouldVisit(ip.out, pos)) {
                            id = ip.out
                            continue
                        }
                    }

                    InstOp.OP_PROGRESS_CHECK -> {
                        val next = progressContinuation(ip, pos)
                        if (next >= 0) {
                            id = next
                            continue
                        }
                    }

                    InstOp.OP_CHAR_RANGE -> {
                        if (pos < endPos) {
                            var cp = if (WorkCounterConfig.ENABLED) -1 else text!!.asciiAt(pos)
                            var nextPos = pos + 1
                            if (cp < 0) {
                                val decoded = text!!.decodeForward(pos)
                                cp = InputScanner.codePoint(decoded)
                                nextPos = InputScanner.position(decoded)
                                if (speculative && nextPos > endPos) {
                                    windowExceeded = true
                                    return false
                                }
                            }
                            if (ip.matchesChar(cp)) {
                                if (shouldVisit(ip.out, nextPos)) {
                                    id = ip.out
                                    pos = nextPos
                                    continue
                                }
                            }
                        } else if (speculative && endPos < textLen) {
                            windowExceeded = true
                            return false
                        }
                    }

                    InstOp.OP_CHAR_CLASS -> {
                        if (pos < endPos) {
                            var cp = if (WorkCounterConfig.ENABLED) -1 else text!!.asciiAt(pos)
                            var nextPos = pos + 1
                            if (cp < 0) {
                                val decoded = text!!.decodeForward(pos)
                                cp = InputScanner.codePoint(decoded)
                                nextPos = InputScanner.position(decoded)
                                if (speculative && nextPos > endPos) {
                                    windowExceeded = true
                                    return false
                                }
                            }
                            if (ip.matchesCharClass(cp)) {
                                if (shouldVisit(ip.out, nextPos)) {
                                    id = ip.out
                                    pos = nextPos
                                    continue
                                }
                            }
                        } else if (speculative && endPos < textLen) {
                            windowExceeded = true
                            return false
                        }
                    }

                    InstOp.OP_MATCH -> {
                        if (endMatch && pos != endPos) {
                            // $ (dollarAnchorEnd) allows ending before a trailing line terminator at the actual
                            // text end. Use text.length (not endPos) because dollarAnchorEnd is a property of
                            // the text boundary, not the search range.
                            if (!prog.dollarAnchorEnd()
                                || !Nfa.isAtTrailingLineTerminator(text!!, pos, prog.dollarAnchorUnixLines())
                            ) {
                                break // must match at the end boundary
                            }
                        }
                        if (ncap > 1) {
                            cap[1] = pos // match end
                        }

                        if (!matched || (longest && pos > bestMatch!![1])) {
                            matched = true
                            System.arraycopy(cap, 0, matchResult, 0, ncap)
                            bestMatch = matchResult
                        }

                        if (!longest) {
                            return true // first match is sufficient
                        }
                    }

                    else -> {}
                }
                break
            }
        }

        return matched
    }

    /**
     * Executes a greedy ALT/consumer cycle without redispatching each instruction. The entry ALT has
     * already been charged. Keep every deferred exit and visitation in the original order, and charge
     * each logical instruction so pruning activation and the work limit remain unchanged. Keep the
     * helper small because HotSpot can inline it into the main interpreter loop when it is hot.
     */
    private fun runGreedyLoop(id: Int, alt: Inst, rune: Inst, pos: Int) {
        var pos = pos
        while (true) {
            if (shouldVisit(alt.out1, pos)) {
                push(alt.out1, pos)
            }
            if (!shouldVisit(alt.out, pos) || !stepWithinBudget()) {
                return
            }
            if (pos >= endPos) {
                if (speculative && endPos < textLen) {
                    windowExceeded = true
                }
                return
            }
            var cp = if (WorkCounterConfig.ENABLED) -1 else text!!.asciiAt(pos)
            var nextPos = pos + 1
            if (cp < 0) {
                val decoded = text!!.decodeForward(pos)
                cp = InputScanner.codePoint(decoded)
                nextPos = InputScanner.position(decoded)
                if (speculative && nextPos > endPos) {
                    windowExceeded = true
                    return
                }
            }
            val matches =
                if (rune.opCode == InstOp.OP_CHAR_RANGE) rune.matchesChar(cp) else rune.matchesCharClass(cp)
            if (!matches || !shouldVisit(id, nextPos) || !stepWithinBudget()) {
                return
            }
            pos = nextPos
        }
    }

    /** Skips only exits that necessarily fail before any assertion or branch can be reached.  */
    private fun skipFailedLazyExits(loop: LazyRuneLoop, id: Int, pos: Int): Int {
        var pos = pos
        val rune = loop.rune
        var steps = 0
        while (pos < endPos) {
            val cp = text!!.asciiAt(pos)
            if (cp < 0 || cp == loop.exitChar || !(if (rune!!.opCode == InstOp.OP_CHAR_RANGE)
                    rune.matchesChar(cp)
                else
                    rune.matchesCharClass(cp))
            ) {
                break
            }
            if (steps == 0) {
                // The failed literal and the body each take one step. Walk the compiled exit once
                // to count its NOPs, captures, and restores of active capture registers.
                steps = 2
                var exit = instructions[loop.exitStart]!!
                while (exit.opCode == InstOp.OP_CAPTURE || exit.opCode == InstOp.OP_NOP) {
                    steps++
                    if (exit.opCode == InstOp.OP_CAPTURE && exit.arg < ncap) {
                        steps++
                    }
                    exit = instructions[exit.out]!!
                }
            }
            if (WorkCounterConfig.ENABLED) {
                // The interpreter decodes the byte once for the failed literal and once for the body.
                WorkCounter.record((steps + (if (text is Utf8InputScanner) 2 else 0)).toLong())
            }
            // No tracked ALT intervenes between the two consumers. Check the budget before the
            // next ALT visit so that it observes the same pruning state as ordinary dispatch.
            stepCount += steps.toLong()
            if (stepCount > nextStepThreshold && !onStepThresholdExceeded()) {
                return -1
            }
            pos++
            // Keep cross-start pruning and logical ALT charges identical to the interpreter. At the
            // window edge resume ordinary dispatch, which retains speculative bailout semantics.
            if (!shouldVisit(id, pos) || !stepWithinBudget()) {
                return -1
            }
        }
        return pos
    }

    /** Executes one progress check, returning a continuation or -1 when none is eligible.  */
    private fun progressContinuation(check: Inst, pos: Int): Int {
        val reg = check.arg
        val saved = loopRegs[reg]
        if (saved == -1) {
            // First visit: must enter the body at least once.
            push(-(ncap + reg + 1), saved)
            loopRegs[reg] = pos
            return if (shouldVisit(check.out, pos)) check.out else -1
        }
        if (saved == pos) {
            // Zero-width body match: only exit.
            return if (shouldVisit(check.out1, pos)) check.out1 else -1
        }
        push(-(ncap + reg + 1), saved)
        loopRegs[reg] = pos
        val preferred = if (check.foldCase) check.out1 else check.out
        val deferred = if (check.foldCase) check.out else check.out1
        if (shouldVisit(deferred, pos)) {
            push(deferred, pos)
        }
        return if (shouldVisit(preferred, pos)) preferred else -1
    }

    /** Returns whether the previous search stopped because BitState exceeded its work budget.  */
    fun budgetExceeded(): Boolean {
        return budgetExceeded
    }

    /** Returns whether the last speculative search encountered an unresolved path.  */
    fun windowExceeded(): Boolean {
        return windowExceeded
    }

    /** Returns the current work budget. Package-private for testing.  */
    fun stepBudgetForTesting(): Long {
        return stepBudget
    }

    /** Returns the logical instruction charges from the last search.  */
    fun stepCountForTesting(): Long {
        return stepCount
    }

    /**
     * Returns whether this BitState can be reused for the given parameters. Reuse is possible when
     * the program is the same and the search buffers are large enough for the requested range.
     * Capture storage can grow separately when a group-zero search is followed by capture extraction.
     */
    fun canReuse(prog: Prog, text: InputScanner?, startPos: Int, endPos: Int): Boolean {
        if (this.prog != prog) {
            return false
        }
        val newTextSlots = (endPos - startPos) + 2
        val totalBits = prog.size * newTextSlots
        val requiredVisitedLen = (totalBits + 63) / 64
        return visited.size >= requiredVisitedLen && jobInstId.size >= Math.min(totalBits, 4096)
    }

    /** Clears input-dependent references before this object enters the Pattern-level reuse cache.  */
    fun releaseInput() {
        text = null
        graphemeContext = null
    }

    /**
     * Resets this BitState for a new search, clearing the visited bitmap and capture arrays without
     * reallocating search buffers. Capture storage grows on demand. The caller must verify [ ][.canReuse] first.
     */
    private fun reset(
        text: InputScanner, startPos: Int, endPos: Int, ncap: Int, longest: Boolean, endMatch: Boolean
    ) {
        this.text = text
        this.textLen = text.length
        this.basePos = startPos
        this.endPos = endPos
        this.longest = longest
        this.endMatch = endMatch || prog.anchorEnd()
        this.ncap = ncap
        this.textSlots = (endPos - basePos) + 2
        this.graphemeContext = GraphemeSupport.Context.create(text, prog.hasGraphemeSemantics())
        this.bestMatch = null
        this.jobCount = 0
        if (visitedHi >= visitedLo) {
            Arrays.fill(visited, visitedLo, visitedHi + 1, 0L)
            visitedLo = Integer.MAX_VALUE
            visitedHi = -1
        }
        if (deadHi >= deadLo) {
            Arrays.fill(dead, deadLo, deadHi + 1, 0L)
            deadLo = Integer.MAX_VALUE
            deadHi = -1
        }
        if (cap.size < ncap) {
            cap = IntArray(ncap)
        }
        Arrays.fill(cap, 0, ncap, -1)
        if (loopRegs.size > 0) {
            Arrays.fill(loopRegs, -1)
        }
    }

    companion object {
        /** Default speculative consumption window when no later DFA end hint is available.  */
        const val SPECULATIVE_WINDOW_SIZE: Int = 256

        /** Maximum consumption window for a speculative search sized by a forward DFA hint.  */
        const val MAX_HINTED_WINDOW_SIZE: Int = 2048

        /** Maximum bitmap size in bits. Limits the product of prog size × text length.  */
        private val MAX_BITMAP_BITS = 256 * 1024

        /** Maximum BitState jobs to run per instruction/position slot before falling back to NFA.  */
        private const val MAX_WORK_PER_SLOT = 8

        /**
         * Step count at which multi-start searches begin recording non-cycle ALTs for cross-start
         * pruning. Short searches that finish before this bound skip bitmap maintenance entirely, while
         * long failing runs activate pruning within the first start and stay linear overall.
         */
        private const val PRUNE_ACTIVATION_STEPS = 64L

        /**
         * Returns the maximum text length (in chars) for which BitState can be used with the given
         * program, or -1 if the program is too large for BitState.
         */
        fun maxTextSize(prog: Prog): Int {
            val instCount = prog.size
            if (instCount == 0) {
                return -1
            }
            return MAX_BITMAP_BITS / instCount - 1
        }

        /**
         * Searches for a match using bit-state backtracking, starting from position 0.
         * 
         * @param prog the compiled program
         * @param text the input text
         * @param anchored if true, match must start at position 0
         * @param longest if true, find the longest match; otherwise find the first (greedy) match
         * @param endMatch if true, match must extend to end of text
         * @param nsubmatch number of submatch groups to track (including group 0)
         * @return submatch positions as `int[2*nsubmatch]`, or null if no match
         */
        fun search(
            prog: Prog, text: String, anchored: Boolean, longest: Boolean, endMatch: Boolean, nsubmatch: Int
        ): IntArray? {
            return search(prog, text, 0, text.length, anchored, longest, endMatch, nsubmatch)
        }

        /**
         * Searches for a match using bit-state backtracking, starting from the specified position.
         * 
         * @param prog the compiled program
         * @param text the full input text
         * @param startPos the char index in `text` at which to begin searching
         * @param anchored if true, match must start at `startPos`
         * @param longest if true, find the longest match; otherwise find the first (greedy) match
         * @param endMatch if true, match must extend to end of text
         * @param nsubmatch number of submatch groups to track (including group 0)
         * @return submatch positions as `int[2*nsubmatch]`, or null if no match. Positions are char
         * indices into the full text.
         */
        fun search(
            prog: Prog,
            text: String,
            startPos: Int,
            anchored: Boolean,
            longest: Boolean,
            endMatch: Boolean,
            nsubmatch: Int
        ): IntArray? {
            return search(prog, text, startPos, text.length, anchored, longest, endMatch, nsubmatch)
        }

        /**
         * Searches for a match using bit-state backtracking, with bounded search range.
         * 
         * @param prog the compiled program
         * @param text the full input text
         * @param startPos the char index in `text` at which to begin searching
         * @param searchLimit upper bound on where to try start positions; only positions up to this index
         * are tried. The inner search may still match characters beyond this position. Use `text.length` for unbounded search.
         * @param anchored if true, match must start at `startPos`
         * @param longest if true, find the longest match; otherwise find the first (greedy) match
         * @param endMatch if true, match must extend to end of text
         * @param nsubmatch number of submatch groups to track (including group 0)
         * @return submatch positions as `int[2*nsubmatch]`, or null if no match. Positions are char
         * indices into the full text.
         */
        fun search(
            prog: Prog,
            text: String?,
            startPos: Int,
            searchLimit: Int,
            anchored: Boolean,
            longest: Boolean,
            endMatch: Boolean,
            nsubmatch: Int
        ): IntArray? {
            return search(null, prog, text, startPos, searchLimit, anchored, longest, endMatch, nsubmatch)
        }

        /**
         * Searches using bit-state backtracking, writing successful captures into `resultBuffer`
         * when it is large enough. This keeps the mutable backtracking capture registers separate from
         * the returned result while allowing tight find loops to avoid one result-array allocation per
         * match.
         */
        /**
         * Searches using bit-state backtracking, optionally reusing a cached instance to avoid
         * allocations. If `cached` is non-null and its arrays are large enough for the current
         * text, it is reset and reused; otherwise a new instance is created.
         * 
         * @param cached a previously created BitState to reuse, or null
         * @param prog the compiled program
         * @param text the full input text
         * @param startPos the char index at which to begin searching
         * @param searchLimit upper bound on start positions to try
         * @param anchored if true, match must start at `startPos`
         * @param longest if true, find the longest match
         * @param endMatch if true, match must extend to end of text
         * @param nsubmatch number of submatch groups to track (including group 0)
         * @return submatch positions as `int[2*nsubmatch]`, or null if no match
         */
        @JvmOverloads
        fun search(
            cached: BitState?,
            prog: Prog,
            text: String?,
            startPos: Int,
            searchLimit: Int,
            anchored: Boolean,
            longest: Boolean,
            endMatch: Boolean,
            nsubmatch: Int,
            resultBuffer: IntArray? = null
        ): IntArray? {
            return search(
                cached,
                prog,
                StringInputScanner(text!!),
                startPos,
                searchLimit,
                anchored,
                longest,
                endMatch,
                nsubmatch,
                resultBuffer
            )
        }

        fun search(
            cached: BitState?,
            prog: Prog,
            text: InputScanner,
            startPos: Int,
            searchLimit: Int,
            anchored: Boolean,
            longest: Boolean,
            endMatch: Boolean,
            nsubmatch: Int,
            resultBuffer: IntArray?
        ): IntArray? {
            var anchored = anchored
            var endMatch = endMatch
            val textLen = text.length
            val maxLen: Int = maxTextSize(prog)
            if (maxLen < 0 || textLen > maxLen) {
                return null // text too large for BitState
            }

            if (prog.anchorStart()) {
                anchored = true
            }
            if (prog.anchorEnd()) {
                endMatch = true
            }

            val ncap = 2 * Math.max(nsubmatch, 1)
            val bs: BitState?
            if (cached != null && cached.canReuse(prog, text, startPos, textLen)) {
                bs = cached
                bs.reset(text, startPos, textLen, ncap, longest, endMatch)
            } else {
                bs = BitState(prog, text, startPos, textLen, ncap, longest, endMatch, prog.greedyRunes())
            }

            return bs.doSearch(startPos, searchLimit, anchored, resultBuffer)
        }

        /**
         * Returns a BitState instance suitable for the given parameters, either by resetting `cached` (if compatible) or by creating a new one.
         */
        fun getOrCreate(
            cached: BitState?,
            prog: Prog,
            text: String?,
            startPos: Int,
            endPos: Int,
            ncap: Int,
            longest: Boolean,
            endMatch: Boolean
        ): BitState {
            return getOrCreate(
                cached, prog, StringInputScanner(text!!), startPos, endPos, ncap, longest, endMatch
            )
        }

        /**
         * Returns a BitState instance suitable for the given parameters, either by resetting `cached` (if compatible) or by creating a new one.
         */
        fun getOrCreate(
            cached: BitState?,
            prog: Prog,
            text: InputScanner,
            startPos: Int,
            endPos: Int,
            ncap: Int,
            longest: Boolean,
            endMatch: Boolean
        ): BitState {
            if (cached != null && cached.canReuse(prog, text, startPos, endPos)) {
                cached.reset(text, startPos, endPos, ncap, longest, endMatch)
                return cached
            }
            return BitState(prog, text, startPos, endPos, ncap, longest, endMatch, prog.greedyRunes())
        }

        private const val NOT_ALT: Byte = 0
        private const val ALT: Byte = 1
        private const val CYCLE_ALT: Byte = 2
    }
}
