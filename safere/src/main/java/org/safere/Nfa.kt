// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*
import java.util.function.Function

/**
 * Pike VM NFA execution engine. Simulates all possible NFA threads in lockstep, tracking submatch
 * boundaries. At most one thread exists per NFA state at any time, guaranteeing linear-time
 * matching.
 * 
 * 
 * This is a port of RE2's `nfa.cc`, adapted for Java's Unicode code point model.
 * 
 * 
 * Usage:
 * 
 * <pre>
 * Regexp re = Parser.parse("(\\w+)@(\\w+)", flags);
 * Prog prog = Compiler.compile(re);
 * int[] result = Nfa.search(prog, "user@host", Anchor.UNANCHORED,
 * MatchKind.FIRST_MATCH, prog.numCaptures());
 * // result[0..1] = full match, result[2..3] = group 1, etc.
</pre> * 
 */
internal class Nfa {
    /** Anchor mode for matching.  */
    internal enum class Anchor {
        /** Match anywhere in the text.  */
        UNANCHORED,

        /** Match only at the start of the text.  */
        ANCHORED
    }

    /** Match semantics.  */
    internal enum class MatchKind {
        /** Leftmost-biased match (Perl-like). Stops at the first match found.  */
        FIRST_MATCH,

        /** Leftmost-longest match (POSIX-like). Keeps searching for longer matches.  */
        LONGEST_MATCH,

        /** Match must cover the entire text. Implies anchored + longest.  */
        FULL_MATCH
    }

    // TODO(#98): Replace int[] with Guava ImmutableIntArray to get proper value semantics.
    private class NfaThread(threadArraySize: Int) {
        var id: Int = 0
        val capture: IntArray
        var graphemeStart: Int = 0

        init {
            this.capture = IntArray(threadArraySize)
        }
    }

    private class QueueState(progSize: Int, hasGraphemeSemantics: Boolean) {
        val threads: Array<NfaThread?>
        var size: Int = 0
        val visitedInst: IntArray
        var visitedGeneration: Int = 1
        val visitedGrapheme: MutableSet<Long?>?
        val hasGraphemeSemantics: Boolean

        init {
            this.threads = arrayOfNulls<NfaThread?>(progSize + 1)
            this.visitedInst = IntArray(progSize + 1)
            this.visitedGrapheme = if (hasGraphemeSemantics) HashSet<Long?>() else null
            this.hasGraphemeSemantics = hasGraphemeSemantics
        }

        fun isEmpty(): Boolean = size == 0

        fun clear() {
            size = 0
            if (hasGraphemeSemantics) {
                visitedGrapheme!!.clear()
            } else {
                visitedGeneration++
                if (visitedGeneration == 0) {
                    Arrays.fill(visitedInst, 0)
                    visitedGeneration = 1
                }
            }
        }
    }

    private class AddToThreadqStack {
        // High word: -1 for an instruction, a nonnegative restore slot, or an encoded completion.
        // Low word: instruction ID plus consumed-input bit, or the saved register value.
        private var frames = LongArray(32)
        private var size = 0

        fun clear() {
            size = 0
        }

        fun isEmpty(): Boolean = size == 0

        fun pushInstruction(id: Int, consumedInput: Boolean) {
            ensureCapacity(size + 1)
            frames[size++] = (-1L shl 32) or (id.toLong() shl 1) or (if (consumedInput) 1 else 0).toLong()
        }

        fun pushRestore(restoreIndex: Int, restoreValue: Int) {
            ensureCapacity(size + 1)
            frames[size++] = (restoreIndex.toLong() shl 32) or Integer.toUnsignedLong(restoreValue)
        }

        fun pushCompletion(instructionId: Int) {
            ensureCapacity(size + 1)
            frames[size++] = (-2 - instructionId).toLong() shl 32
        }

        fun pop(): Long {
            return frames[--size]
        }

        fun ensureCapacity(minCapacity: Int) {
            if (minCapacity <= frames.size) {
                return
            }
            var newCapacity: Int = frames.size * 2
            while (newCapacity < minCapacity) {
                newCapacity *= 2
            }
            frames = Arrays.copyOf(frames, newCapacity)
        }
    }

    private var prog: Prog? = null
    private var ncapture = 0

    /** Total thread array size: ncapture slots for captures + numLoopRegs for progress checks.  */
    private var threadArraySize = 0

    private var longest = false
    private var endmatch = false
    private var context: EngineContext? = null
    private var matched = false
    private lateinit var bestMatch: IntArray
    private val addToThreadqStack = AddToThreadqStack()

    // NfaThread pool
    private var threadPool: Array<NfaThread?> = arrayOfNulls<NfaThread?>(16)
    private var threadPoolSize = 0

    // QueueState pool
    private val queueStatePool: MutableList<QueueState> = ArrayList<QueueState>()

    private fun allocThread(id: Int, captureSource: IntArray?, graphemeStart: Int): NfaThread {
        val t: NfaThread
        if (threadPoolSize > 0) {
            threadPoolSize--
            t = threadPool[threadPoolSize]!!
            threadPool[threadPoolSize] = null
        } else {
            t = NfaThread(threadArraySize)
        }
        t.id = id
        if (captureSource != null) {
            System.arraycopy(captureSource, 0, t.capture, 0, threadArraySize)
        } else {
            Arrays.fill(t.capture, -1)
        }
        t.graphemeStart = graphemeStart
        return t
    }

    private fun freeThread(t: NfaThread?) {
        if (threadPool.size <= threadPoolSize) {
            threadPool = Arrays.copyOf<NfaThread?>(threadPool, threadPool.size * 2)
        }
        threadPool[threadPoolSize] = t!!
        threadPoolSize++
    }

    private fun freeQueue(q: QueueState) {
        for (i in 0..<q.size) {
            freeThread(q.threads[i]!!)
            q.threads[i] = null
        }
        q.clear()
    }

    private fun allocQueueState(): QueueState {
        if (!queueStatePool.isEmpty()) {
            return queueStatePool.removeAt(queueStatePool.size - 1)
        }
        return QueueState(prog!!.size, prog!!.hasGraphemeSemantics())
    }

    private fun freeQueueState(q: QueueState) {
        freeQueue(q)
        queueStatePool.add(q)
    }

    private constructor(prog: Prog, context: EngineContext?, ncapture: Int, longest: Boolean, endmatch: Boolean) {
        this.prog = prog
        this.ncapture = ncapture
        this.threadArraySize = ncapture + prog.numLoopRegs()
        this.longest = longest
        this.endmatch = endmatch
        this.context = context
        this.bestMatch = IntArray(ncapture)
        Arrays.fill(bestMatch, -1)
    }

    private fun canReuse(prog: Prog, ncapture: Int): Boolean {
        val requiredThreadArraySize = ncapture + prog.numLoopRegs()
        return this.threadArraySize >= requiredThreadArraySize && this.bestMatch.size >= ncapture && this.prog!!.size >= prog.size && this.prog!!.hasGraphemeSemantics() == prog.hasGraphemeSemantics()
    }

    private fun reset(
        prog: Prog, context: EngineContext?, ncapture: Int, longest: Boolean, endmatch: Boolean
    ) {
        this.prog = prog
        this.ncapture = ncapture
        this.threadArraySize = ncapture + prog.numLoopRegs()
        this.longest = longest
        this.endmatch = endmatch
        this.context = context
        this.matched = false
        if (this.bestMatch.size < ncapture) {
            this.bestMatch = IntArray(ncapture)
        }
        Arrays.fill(this.bestMatch, 0, ncapture, -1)
        this.addToThreadqStack.clear()
    }

    fun releaseInputContext() {
        context = null
    }

    @JvmOverloads
    fun runSearch(
        anchored: Boolean,
        kind: MatchKind?,
        nsubmatch: Int,
        endPos: Int,
        reuseGroups: IntArray? = null
    ): IntArray? {
        if (prog!!.hasGraphemeClusterInstruction()) {
            doSearchEveryCharPosition(anchored)
        } else {
            doSearch(anchored)
        }

        if (!matched) {
            return null
        }
        if (kind == MatchKind.FULL_MATCH && bestMatch[1] != endPos) {
            return null
        }

        val nlen = 2 * nsubmatch
        val result = if (reuseGroups != null && reuseGroups.size >= nlen) reuseGroups else IntArray(nlen)
        val ncopy = Math.min(ncapture, result.size)
        System.arraycopy(bestMatch, 0, result, 0, ncopy)
        if (ncopy < result.size) {
            Arrays.fill(result, ncopy, result.size, -1)
        }
        return result
    }

    /**
     * Main search loop. Iterates over each position in the text starting from `startPos` (plus
     * one past the end), stepping the NFA. At each position, starts a new thread if appropriate, then
     * advances all existing threads by one character.
     * 
     * @param anchored whether to anchor the search at the context start
     */
    private fun doSearch(anchored: Boolean) {
        val text = context!!.text!!
        val startPos = context!!.searchStart
        val searchLimit = context!!.searchLimit
        val endPos = context!!.endPos
        var runq = allocQueueState()
        var nextq = allocQueueState()

        val initialCap = IntArray(threadArraySize)

        var pos = startPos
        while (true) {
            val decoded = decodeAtConsumeBoundary(text!!, pos, endPos)
            val cp: Int = InputScanner.codePoint(decoded)
            val nextPos: Int = InputScanner.position(decoded)

            // Start a new thread if there have not been any matches
            // (no point starting new threads to the right of an existing match).
            // Also don't start threads past searchLimit — the DFA has already determined
            // there's no match starting beyond that position.
            if (!matched && pos <= searchLimit && (!anchored || pos == startPos)) {
                Arrays.fill(initialCap, -1)
                initialCap[0] = pos
                // Always use prog.start() (anchored start). Unanchored matching is achieved
                // by starting a new thread at each position. The startUnanchored() entry point
                // (which includes a .*? prefix) is only for the DFA engine.
                addToThreadq(runq, prog!!.start(), text, pos, initialCap, false)
            }

            // If all threads have died, stop if anchored or we already have a match.
            // For unanchored searches without a match, keep trying new positions.
            if (runq.isEmpty()) {
                if (anchored || matched) {
                    break
                }
                // In unanchored mode with no match yet, advance to the next position and try again.
                if (pos >= endPos) {
                    break
                }
                freeQueue(runq)
                pos = nextPos
                continue
            }

            val done = stepCodePoint(runq, nextq, cp, text, pos, nextPos)

            val tmp = runq
            runq = nextq
            nextq = tmp
            freeQueue(nextq)

            if (done) {
                break
            }

            if (pos >= endPos) {
                break
            }

            pos = nextPos
        }

        freeQueueState(runq)
        freeQueueState(nextq)
    }

    /**
     * Unanchored search variant for programs consuming grapheme clusters.
     * 
     * 
     * Active threads still consume Unicode code points, but new candidate matches are started at
     * every Java character offset. That matches [java.util.regex.Matcher]'s character-index
     * search contract for grapheme constructs without changing the code-point stepping used by
     * ordinary SafeRE programs. A boundary assertion alone does not introduce new candidate starts;
     * programs containing only scalar consumers and `\b{g}` use [.doSearch].
     */
    private fun doSearchEveryCharPosition(anchored: Boolean) {
        val text = context!!.text!!
        val startPos = context!!.searchStart
        val searchLimit = context!!.searchLimit
        val start = Math.max(0, startPos)
        val runq = allocQueueState()

        var delayedBuffer: Array<QueueState?>? = null
        var delayedGrapheme: MutableMap<Int?, QueueState>? = null
        if (prog!!.hasGraphemeSemantics()) {
            delayedGrapheme = HashMap<Int?, QueueState>()
        } else {
            delayedBuffer = arrayOfNulls<QueueState>(4)
        }

        val initialCap = IntArray(threadArraySize)

        val endPos = context!!.endPos
        val isUtf8 = text is Utf8InputScanner
        var pos = start
        while (pos < endPos + 2) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            if (prog!!.hasGraphemeSemantics()) {
                mergeDelayedQueue(delayedGrapheme!!, pos, runq)
            } else {
                mergeDelayedQueue(delayedBuffer!!, pos, runq)
            }
            if (!matched && pos <= searchLimit && (!anchored || pos == startPos)) {
                Arrays.fill(initialCap, -1)
                initialCap[0] = pos
                addToThreadq(runq, prog!!.start(), text, pos, initialCap, false)
            }

            val nextBoundaryPos: Int
            if (!runq.isEmpty()) {
                val stepResult = inputStep(text, pos)
                val cp = (stepResult ushr 32).toInt()
                val nextPos = stepResult.toInt()
                step(runq, delayedBuffer!!, delayedGrapheme!!, cp, text, pos, nextPos)
                nextBoundaryPos = nextPos
            } else {
                freeQueue(runq)
                if (isUtf8 && pos < endPos) {
                    val decoded = text.decodeForward(pos)
                    nextBoundaryPos = Math.max(pos + 1, InputScanner.position(decoded))
                } else {
                    nextBoundaryPos = pos + 1
                }
            }
            if (matched
                && runq.isEmpty()
                && (if (prog!!.hasGraphemeSemantics())
                    delayedGrapheme!!.isEmpty()
                else
                    isDelayedBufferEmpty(delayedBuffer!!))
            ) {
                break
            }
            if (isUtf8 && pos < endPos) {
                pos = nextBoundaryPos
            } else {
                pos++
            }
        }

        freeQueueState(runq)
        if (prog!!.hasGraphemeSemantics()) {
            for (q in delayedGrapheme!!.values) {
                freeQueueState(q)
            }
            delayedGrapheme!!.clear()
        } else {
            for (i in delayedBuffer!!.indices) {
                if (delayedBuffer[i] != null) {
                    freeQueueState(delayedBuffer[i]!!)
                    delayedBuffer[i] = null
                }
            }
        }
    }

    private fun mergeDelayedQueue(delayedBuffer: Array<QueueState?>, pos: Int, destination: QueueState) {
        val idx: Int = pos % delayedBuffer.size
        val source = delayedBuffer[idx]
        if (source == null) {
            return
        }
        mergeQueues(source, destination, pos)
        delayedBuffer[idx] = null
        freeQueueState(source)
    }

    private fun mergeDelayedQueue(
        delayedGrapheme: MutableMap<Int?, QueueState>, pos: Int, destination: QueueState
    ) {
        val source: QueueState? = delayedGrapheme.remove(pos)
        if (source == null) {
            return
        }
        mergeQueues(source, destination, pos)
        freeQueueState(source)
    }

    private fun mergeQueues(source: QueueState, destination: QueueState, pos: Int) {
        val text = context!!.text!!
        for (i in 0..<source.size) {
            val t = source.threads[i]!!
            val visited: Boolean
            if (destination.hasGraphemeSemantics) {
                val key = visitKey(prog!!.inst(t.id), t.id, text, pos, t!!.graphemeStart)
                visited = !destination.visitedGrapheme!!.add(key)
            } else {
                visited = destination.visitedInst[t.id] == destination.visitedGeneration
                destination.visitedInst[t.id] = destination.visitedGeneration
            }
            if (visited) {
                freeThread(t)
            } else {
                destination.threads[destination.size++] = t!!
            }
            source.threads[i] = null
        }
        source.size = 0
    }

    private fun delayedQueueAt(delayedBuffer: Array<QueueState?>, pos: Int): QueueState {
        val idx: Int = pos % delayedBuffer.size
        var q = delayedBuffer[idx]
        if (q == null) {
            q = allocQueueState()
            delayedBuffer[idx] = q
        }
        return q
    }

    private fun delayedQueueAt(delayedGrapheme: MutableMap<Int?, QueueState>, pos: Int): QueueState? {
        val endPos = context!!.endPos
        if (pos < 0 || pos > endPos + 1) {
            return null
        }
        return delayedGrapheme.computeIfAbsent(pos, Function { unused: Int? -> allocQueueState() })
    }

    private fun visitKey(ip: Inst, id: Int, text: InputScanner, pos: Int, graphemeStart: Int): Long {
        val instructionKey = (id.toLong()) shl GraphemeSupport.visitKeyVariantBits()
        if (ip.op != InstOp.GRAPHEME_CLUSTER) {
            return instructionKey
        }
        return (instructionKey
                or GraphemeSupport.visitVariant(
            text,
            pos,
            graphemeStart,
            context!!.consumeRegionStart,
            context!!.endPos,
            context!!.graphemeContext!!
        ).toLong())
    }

    private fun inputStep(text: InputScanner, pos: Int): Long {
        return decodeAtConsumeBoundary(text, pos, context!!.endPos)
    }

    private fun graphemeNextPos(text: InputScanner?, pos: Int): Int {
        return GraphemeSupport.graphemeNextPos(text!!, pos, context!!.endPos)
    }

    /**
     * Accepts a candidate under the end constraints and records it when it wins match selection.
     * 
     * 
     * The caller owns candidate priority, queue lifetime, and whether to stop after acceptance.
     * 
     * @return whether the candidate satisfies the end constraints, even if an existing longest match
     * remains better
     */
    private fun acceptMatch(capture: IntArray, pos: Int): Boolean {
        if (endmatch
            && !(if (prog!!.anchorEnd())
                matchesEndPosition(context!!.text!!, pos)
            else
                pos == context!!.endPos)
        ) {
            return false
        }
        if (!longest || !matched || capture[0] < bestMatch[0] || (capture[0] == bestMatch[0] && pos > bestMatch[1])) {
            System.arraycopy(capture, 0, bestMatch, 0, ncapture)
            bestMatch[1] = pos
            matched = true
        }
        return true
    }

    /**
     * Follows all empty transitions from `id0` and enqueues consuming/accepting instructions
     * (CHAR_RANGE and MATCH) into the thread queue.
     * 
     * @param q the thread queue to add to
     * @param id starting instruction ID
     * @param text the input text
     * @param pos the current position in the text
     * @param t0 the current capture array (shared — will be cloned before mutation)
     */
    private fun addToThreadq(
        q: QueueState, id: Int, text: InputScanner, pos: Int, t0: IntArray, consumedInput: Boolean
    ) {
        var id = id
        var consumedInput = consumedInput
        val stack = addToThreadqStack
        stack.clear()

        var cachedFlags = -1

        // Follow the next instruction directly. Only deferred alternatives and unwind actions need
        // stack frames; their LIFO order still determines branch priority and register restoration.
        while (id != 0 || !stack.isEmpty()) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            if (id == 0) {
                val frame = stack.pop()
                val restoreIndex = (frame shr 32).toInt()
                if (restoreIndex != -1) {
                    if (restoreIndex >= 0) {
                        t0[restoreIndex] = frame.toInt()
                    } else {
                        // This epsilon expansion is complete; later entries at this position are skipped.
                        markExpansionComplete(q, -2 - restoreIndex, text, pos)
                    }
                    continue
                }
                id = frame.toInt() ushr 1
                consumedInput = (frame and 1L) != 0L
                if (id == 0) {
                    continue
                }
            }

            val instructionId = id
            id = 0
            val ip = prog!!.inst(instructionId)
            // Loop instructions are excluded from the visited set: progress registers terminate
            // zero-width iterations, while fresh entries reset only their own loop's register.
            //
            // ALT and CAPTURE can be revisited by a final empty iteration with different captures.
            // With progress loops, NOP and EMPTY_WIDTH are memoized after expansion: a nested visit
            // can carry changed loop progress and must reach the final empty iteration, but later visits
            // after completion are redundant. Marking them before expansion loses the loop exit;
            // omitting memoization entirely enumerates the paths through optional epsilon chains.
            // Without progress loops, marking on entry is sufficient and avoids completion bookkeeping.
            if (ip.op != InstOp.PROGRESS_CHECK && ip.op != InstOp.LOOP_ENTER && ip.op != InstOp.ALT && ip.op != InstOp.CAPTURE && ((ip.op != InstOp.NOP && ip.op != InstOp.EMPTY_WIDTH) || prog!!.numLoopRegs() == 0)) {
                if (q.hasGraphemeSemantics) {
                    val visitKey = visitKey(ip, instructionId, text, pos, pos)
                    if (!q.visitedGrapheme!!.add(visitKey)) {
                        continue
                    }
                } else {
                    if (q.visitedInst[instructionId] == q.visitedGeneration) {
                        continue
                    }
                    q.visitedInst[instructionId] = q.visitedGeneration
                }
            } else if (ip.op == InstOp.NOP || ip.op == InstOp.EMPTY_WIDTH) {
                if (isExpansionComplete(q, instructionId, text, pos)) {
                    continue
                }
                if (ip.op == InstOp.NOP) {
                    stack.pushCompletion(instructionId)
                }
            }

            when (ip.op) {
                InstOp.FAIL -> {}
                InstOp.ALT -> {
                    stack.pushInstruction(ip.out1, consumedInput)
                    id = ip.out
                }

                InstOp.ALT_MATCH -> {
                    q.threads[q.size++] = allocThread(instructionId, t0, -1)
                    stack.pushInstruction(ip.out, consumedInput)
                    id = ip.out1
                }

                InstOp.NOP -> id = ip.out
                InstOp.CAPTURE -> {
                    if (ip.arg < ncapture) {
                        val opos = t0[ip.arg]
                        t0[ip.arg] = pos
                        stack.pushRestore(ip.arg, opos)
                    }
                    id = ip.out
                }

                InstOp.EMPTY_WIDTH -> {
                    if (cachedFlags == -1) {
                        cachedFlags =
                            emptyFlags(
                                text,
                                pos,
                                prog!!.hasGraphemeSemantics(),
                                context!!.graphemeContext,
                                t0[0],
                                context!!.boundaryRegionStart,
                                consumedInput,
                                context!!.emptyAnchorStartPos,
                                context!!.emptyAnchorEndPos,
                                context!!.boundaryEndPos,
                                context!!.boundaryEndPos,
                                prog!!.hasWordBoundary(),
                                prog!!.hasTextAnchor()
                            )
                    }
                    if ((ip.arg and cachedFlags.inv()) == 0) {
                        if (prog!!.numLoopRegs() > 0) {
                            stack.pushCompletion(instructionId)
                        }
                        id = ip.out
                    } else if (prog!!.numLoopRegs() > 0) {
                        // A failed assertion has no continuation; its expansion is already complete.
                        markExpansionComplete(q, instructionId, text, pos)
                    }
                }

                InstOp.LOOP_ENTER -> {
                    // A fresh entry whose earlier expansion at this position has completed can reach only
                    // what that expansion reached, at lower priority: every enclosing loop register is
                    // either an earlier position (the same behavior) or this position (the enclosing body
                    // was already expanded here). An entry nested inside a still-running expansion of the
                    // same loop is higher priority than that expansion's remaining alternatives, so it is
                    // expanded. Without this, alternating lazy/greedy nesting re-expands each inner loop
                    // once per enclosing iteration, exponentially in the nesting depth.
                    if (isExpansionComplete(q, instructionId, text, pos)) {
                        continue
                    }
                    val regIdx = ncapture + ip.arg
                    val saved = t0[regIdx]
                    t0[regIdx] = -1
                    stack.pushCompletion(instructionId)
                    stack.pushRestore(regIdx, saved)
                    id = ip.out
                }

                InstOp.PROGRESS_CHECK -> {
                    val reg = ip.arg
                    val regIdx = ncapture + reg
                    val saved = t0[regIdx]
                    if (saved == -1) {
                        t0[regIdx] = pos
                        stack.pushRestore(regIdx, -1)
                        id = ip.out
                    } else if (saved == pos) {
                        id = ip.out1
                    } else {
                        t0[regIdx] = pos
                        stack.pushRestore(regIdx, saved)
                        if (ip.foldCase) {
                            stack.pushInstruction(ip.out, consumedInput)
                            id = ip.out1
                        } else {
                            stack.pushInstruction(ip.out1, consumedInput)
                            id = ip.out
                        }
                    }
                }

                InstOp.CHAR_RANGE, InstOp.CHAR_CLASS, InstOp.GRAPHEME_CLUSTER, InstOp.MATCH -> {
                    q.threads[q.size++] =
                        allocThread(instructionId, t0, if (ip.op == InstOp.GRAPHEME_CLUSTER) pos else -1)
                }

                else -> {}
            }
        }
    }

    private fun isExpansionComplete(q: QueueState, instructionId: Int, text: InputScanner?, pos: Int): Boolean {
        if (q.hasGraphemeSemantics) {
            return q.visitedGrapheme!!.contains(
                visitKey(prog!!.inst(instructionId), instructionId, text!!, pos, pos)
            )
        }
        return q.visitedInst[instructionId] == q.visitedGeneration
    }

    private fun markExpansionComplete(q: QueueState, instructionId: Int, text: InputScanner?, pos: Int) {
        if (q.hasGraphemeSemantics) {
            q.visitedGrapheme!!.add(visitKey(prog!!.inst(instructionId), instructionId, text!!, pos, pos))
        } else {
            q.visitedInst[instructionId] = q.visitedGeneration
        }
    }

    /**
     * Processes all threads in `rq` for the current input character. Threads at CHAR_RANGE
     * instructions that match the character are advanced to the delayed queues. Threads at MATCH
     * instructions record the match.
     * 
     * @return true if the search should stop (first-match found and remaining threads cut off)
     */
    private fun step(
        rq: QueueState,
        delayedBuffer: Array<QueueState?>,
        delayedGrapheme: MutableMap<Int?, QueueState>,
        cp: Int,
        text: InputScanner,
        matchPos: Int,
        nextPos: Int
    ): Boolean {
        for (threadIndex in 0..<rq.size) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            val t = rq.threads[threadIndex]!!
            val id = t.id
            val capture = t.capture

            if (longest
                && matched
                && bestMatch[0] != -1 && capture[0] != -1 && bestMatch[0] < capture[0]
            ) {
                continue
            }

            val ip = prog!!.inst(id)
            when (ip.op) {
                InstOp.CHAR_RANGE -> {
                    if (cp >= 0 && ip.matchesChar(cp)) {
                        val destination =
                            if (prog!!.hasGraphemeSemantics())
                                delayedQueueAt(delayedGrapheme, nextPos)
                            else
                                delayedQueueAt(delayedBuffer, nextPos)
                        if (destination != null) {
                            addToThreadq(destination, ip.out, text, nextPos, capture, true)
                        }
                    }
                }

                InstOp.CHAR_CLASS -> {
                    if (cp >= 0 && ip.matchesCharClass(cp)) {
                        val destination =
                            if (prog!!.hasGraphemeSemantics())
                                delayedQueueAt(delayedGrapheme, nextPos)
                            else
                                delayedQueueAt(delayedBuffer, nextPos)
                        if (destination != null) {
                            addToThreadq(destination, ip.out, text, nextPos, capture, true)
                        }
                    }
                }

                InstOp.GRAPHEME_CLUSTER -> {
                    if (matchPos < context!!.endPos) {
                        val graphemeStart =
                            Math.max(
                                context!!.consumeRegionStart,
                                if (t.graphemeStart >= 0) t.graphemeStart else matchPos
                            )
                        val scalarEnd = graphemeNextPos(text, matchPos)
                        val destination =
                            if (prog!!.hasGraphemeSemantics())
                                delayedQueueAt(delayedGrapheme, scalarEnd)
                            else
                                delayedQueueAt(delayedBuffer, scalarEnd)
                        if (destination != null) {
                            if (scalarEnd == context!!.endPos
                                || GraphemeSupport.isGraphemeClusterBoundary(
                                    text, scalarEnd, graphemeStart, context!!.graphemeContext
                                )
                            ) {
                                addToThreadq(destination, ip.out, text, scalarEnd, capture, true)
                            } else {
                                val visited: Boolean
                                if (destination.hasGraphemeSemantics) {
                                    val key = visitKey(ip, id, text, scalarEnd, graphemeStart)
                                    visited = !destination.visitedGrapheme!!.add(key)
                                } else {
                                    visited = destination.visitedInst[id] == destination.visitedGeneration
                                    destination.visitedInst[id] = destination.visitedGeneration
                                }
                                if (!visited) {
                                    destination.threads[destination.size++] = allocThread(id, capture, graphemeStart)
                                }
                            }
                        }
                    }
                }

                InstOp.MATCH -> {
                    if (acceptMatch(capture, matchPos) && !longest) {
                        // Discard lower-priority threads here; previously queued successors still run.
                        freeQueue(rq)
                        return false
                    }
                }

                InstOp.ALT_MATCH -> {
                    // Optimization: if this is the first thread and we want the match, take it.
                    if (longest || threadIndex == 0) {
                        System.arraycopy(capture, 0, bestMatch, 0, ncapture)
                        matched = true
                    }
                }

                else -> {}
            }
        }
        freeQueue(rq)
        return false
    }

    private fun stepCodePoint(
        rq: QueueState, nq: QueueState, cp: Int, text: InputScanner, matchPos: Int, nextPos: Int
    ): Boolean {
        nq.clear()

        for (threadIndex in 0..<rq.size) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            val t = rq.threads[threadIndex]!!
            val id = t.id
            val capture = t.capture

            if (longest
                && matched
                && bestMatch[0] != -1 && capture[0] != -1 && bestMatch[0] < capture[0]
            ) {
                continue
            }

            val ip = prog!!.inst(id)
            when (ip.op) {
                InstOp.CHAR_RANGE -> {
                    if (cp >= 0 && ip.matchesChar(cp)) {
                        addToThreadq(nq, ip.out, text, nextPos, capture, true)
                    }
                }

                InstOp.CHAR_CLASS -> {
                    if (cp >= 0 && ip.matchesCharClass(cp)) {
                        addToThreadq(nq, ip.out, text, nextPos, capture, true)
                    }
                }

                InstOp.MATCH -> {
                    if (acceptMatch(capture, matchPos) && !longest) {
                        // Discard lower-priority threads here; previously queued successors still run.
                        freeQueue(rq)
                        return false
                    }
                }

                InstOp.ALT_MATCH -> {
                    // Optimization: if this is the first thread and we want the match, take it.
                    if (longest || threadIndex == 0) {
                        System.arraycopy(capture, 0, bestMatch, 0, ncapture)
                        matched = true
                    }
                }

                else -> {}
            }
        }
        freeQueue(rq)
        return false
    }

    private fun matchesEndPosition(text: InputScanner, matchPos: Int): Boolean {
        if (matchPos == context!!.anchorEndPos) {
            return true
        }
        return prog!!.dollarAnchorEnd()
                && isAtTrailingLineTerminator(
            text,
            matchPos,
            prog!!.dollarAnchorUnixLines(),
            Math.min(context!!.emptyAnchorStartPos, context!!.boundaryRegionStart),
            context!!.anchorEndPos
        )
    }


    companion object {
        fun getOrCreate(
            cached: Nfa?,
            prog: Prog,
            context: EngineContext?,
            ncapture: Int,
            longest: Boolean,
            endmatch: Boolean
        ): Nfa {
            if (cached != null && cached.canReuse(prog, ncapture)) {
                cached.reset(prog, context, ncapture, longest, endmatch)
                return cached
            }
            return Nfa(prog, context, ncapture, longest, endmatch)
        }

        /**
         * Searches for a match in the given text, starting from position 0.
         * 
         * @param prog the compiled program
         * @param text the input text to search
         * @param anchor whether to anchor the match at the start
         * @param kind the match semantics (first, longest, or full)
         * @param nsubmatch number of submatch groups to track (including group 0 for the full match)
         * @return submatch positions as `int[2*nsubmatch]`, or null if no match. Positions are char
         * indices into the text. `result[2*i]` is the start of group i, `result[2*i+1]`
         * is the end. -1 means the group did not participate.
         */
        fun search(prog: Prog, text: String, anchor: Anchor?, kind: MatchKind?, nsubmatch: Int): IntArray? {
            return search(prog, text, 0, text.length, text.length, anchor, kind, nsubmatch)
        }

        /**
         * Searches for a match in the given text, starting from the specified position.
         * 
         * @param prog the compiled program
         * @param text the full input text to search
         * @param startPos the char index in `text` at which to begin searching
         * @param anchor whether to anchor the match at `startPos`
         * @param kind the match semantics (first, longest, or full)
         * @param nsubmatch number of submatch groups to track (including group 0 for the full match)
         * @return submatch positions as `int[2*nsubmatch]`, or null if no match. Positions are char
         * indices into the full text.
         */
        fun search(
            prog: Prog, text: String, startPos: Int, anchor: Anchor?, kind: MatchKind?, nsubmatch: Int
        ): IntArray? {
            return search(prog, text, startPos, text.length, text.length, anchor, kind, nsubmatch)
        }

        /**
         * Searches for a match in the given text, with bounded search range.
         * 
         * @param prog the compiled program
         * @param text the full input text to search
         * @param startPos the char index in `text` at which to begin searching
         * @param searchLimit upper bound on where to try new thread starts; only positions up to this
         * index start new NFA threads. Active threads may still advance beyond this position. Use
         * `text.length` for unbounded search.
         * @param anchor whether to anchor the match at `startPos`
         * @param kind the match semantics (first, longest, or full)
         * @param nsubmatch number of submatch groups to track (including group 0 for the full match)
         * @return submatch positions as `int[2*nsubmatch]`, or null if no match. Positions are char
         * indices into the full text.
         */
        fun search(
            prog: Prog,
            text: String,
            startPos: Int,
            searchLimit: Int,
            anchor: Anchor?,
            kind: MatchKind?,
            nsubmatch: Int
        ): IntArray? {
            return search(prog, text, startPos, searchLimit, text.length, anchor, kind, nsubmatch)
        }

        fun search(
            prog: Prog,
            text: String?,
            startPos: Int,
            searchLimit: Int,
            endPos: Int,
            anchor: Anchor?,
            kind: MatchKind?,
            nsubmatch: Int
        ): IntArray? {
            return search(prog, text, startPos, searchLimit, endPos, 0, anchor, kind, nsubmatch)
        }

        @JvmOverloads
        fun search(
            prog: Prog,
            text: String?,
            startPos: Int,
            searchLimit: Int,
            endPos: Int,
            regionStart: Int,
            anchor: Anchor?,
            kind: MatchKind?,
            nsubmatch: Int,
            graphemeContext: GraphemeSupport.Context? = null
        ): IntArray? {
            return search(
                prog,
                text,
                startPos,
                searchLimit,
                endPos,
                regionStart,
                regionStart,
                endPos,
                endPos,
                0,
                endPos,
                anchor,
                kind,
                nsubmatch,
                graphemeContext
            )
        }

        fun search(
            prog: Prog,
            text: InputScanner?,
            startPos: Int,
            searchLimit: Int,
            endPos: Int,
            regionStart: Int,
            anchor: Anchor?,
            kind: MatchKind?,
            nsubmatch: Int,
            graphemeContext: GraphemeSupport.Context?
        ): IntArray? {
            return search(
                prog,
                text,
                startPos,
                searchLimit,
                endPos,
                regionStart,
                regionStart,
                endPos,
                endPos,
                0,
                endPos,
                anchor,
                kind,
                nsubmatch,
                graphemeContext
            )
        }

        fun search(
            prog: Prog,
            text: String?,
            startPos: Int,
            searchLimit: Int,
            endPos: Int,
            consumeRegionStart: Int,
            boundaryRegionStart: Int,
            boundaryEndPos: Int,
            anchor: Anchor?,
            kind: MatchKind?,
            nsubmatch: Int,
            graphemeContext: GraphemeSupport.Context?
        ): IntArray? {
            return search(
                prog,
                text,
                startPos,
                searchLimit,
                endPos,
                consumeRegionStart,
                boundaryRegionStart,
                boundaryEndPos,
                endPos,
                0,
                endPos,
                anchor,
                kind,
                nsubmatch,
                graphemeContext
            )
        }

        fun search(
            prog: Prog,
            text: String?,
            startPos: Int,
            searchLimit: Int,
            endPos: Int,
            consumeRegionStart: Int,
            boundaryRegionStart: Int,
            boundaryEndPos: Int,
            anchorEndPos: Int,
            emptyAnchorStartPos: Int,
            emptyAnchorEndPos: Int,
            anchor: Anchor?,
            kind: MatchKind?,
            nsubmatch: Int,
            graphemeContext: GraphemeSupport.Context?
        ): IntArray? {
            return search(
                prog,
                StringInputScanner(text!!),
                startPos,
                searchLimit,
                endPos,
                consumeRegionStart,
                boundaryRegionStart,
                boundaryEndPos,
                anchorEndPos,
                emptyAnchorStartPos,
                emptyAnchorEndPos,
                anchor,
                kind,
                nsubmatch,
                graphemeContext
            )
        }

        fun search(
            prog: Prog,
            text: InputScanner?,
            startPos: Int,
            searchLimit: Int,
            endPos: Int,
            consumeRegionStart: Int,
            boundaryRegionStart: Int,
            boundaryEndPos: Int,
            anchorEndPos: Int,
            emptyAnchorStartPos: Int,
            emptyAnchorEndPos: Int,
            anchor: Anchor?,
            kind: MatchKind?,
            nsubmatch: Int,
            graphemeContext: GraphemeSupport.Context?
        ): IntArray? {
            var nsubmatch = nsubmatch
            if (prog.start() == 0) {
                return null
            }

            var anchored = (anchor == Anchor.ANCHORED) || prog.anchorStart()
            val longestMode = (kind != MatchKind.FIRST_MATCH)
            var endmatch = prog.anchorEnd()

            if (kind == MatchKind.FULL_MATCH) {
                anchored = true
                endmatch = true
                if (nsubmatch == 0) {
                    nsubmatch = 1
                }
            }

            // We always need at least capture[0..1] to track the match boundaries.
            val ncapture = 2 * Math.max(nsubmatch, 1)

            val context: EngineContext =
                EngineContext.create(
                    prog,
                    text,
                    startPos,
                    searchLimit,
                    endPos,
                    consumeRegionStart,
                    boundaryRegionStart,
                    boundaryEndPos,
                    anchorEndPos,
                    emptyAnchorStartPos,
                    emptyAnchorEndPos,
                    graphemeContext
                )
            val nfa = Nfa(prog, context, ncapture, longestMode, endmatch)
            return nfa.runSearch(anchored, kind, nsubmatch, endPos)
        }

        private fun isDelayedBufferEmpty(delayedBuffer: Array<QueueState?>): Boolean {
            for (q in delayedBuffer) {
                if (q != null && !q.isEmpty()) {
                    return false
                }
            }
            return true
        }

        private fun decodeAtConsumeBoundary(text: InputScanner, pos: Int, limit: Int): Long {
            if (pos < 0 || pos >= limit) {
                return InputScanner.decoded(-1, nextBoundaryPosition(pos, limit))
            }
            val decoded = text.decodeForward(pos)
            return if (InputScanner.position(decoded) <= limit) decoded else text.decodeForward(pos, limit)
        }

        private fun nextBoundaryPosition(pos: Int, endPos: Int): Int {
            return if (pos < endPos) endPos else endPos + 1
        }

        // ---------------------------------------------------------------------------
        // Empty-width flag computation
        // ---------------------------------------------------------------------------
        /**
         * Returns true if the code point is a JDK line terminator character: `'\n'`, `'\r'`,
         * `'\u0085'` (NEXT LINE), `'\u2028'` (LINE SEPARATOR), or `'\u2029'` (PARAGRAPH
         * SEPARATOR).
         */
        fun isLineTerminator(cp: Int): Boolean {
            return cp == '\n'.code || cp == '\r'.code || cp == '\u0085'.code || cp == '\u2028'.code || cp == '\u2029'.code
        }

        /**
         * Returns true if `pos` is at the start of a trailing line terminator sequence that extends
         * to the end of the text. Used for non-multiline `$` (dollarAnchorEnd) matching.
         * 
         * @param unixLines if true, only `'\n'` is recognized as a line terminator
         */
        fun isAtTrailingLineTerminator(text: InputScanner, pos: Int, unixLines: Boolean): Boolean {
            return isAtTrailingLineTerminator(text, pos, unixLines, text.length)
        }

        fun isAtTrailingLineTerminator(
            text: InputScanner, pos: Int, unixLines: Boolean, logicalEndPos: Int
        ): Boolean {
            return isAtTrailingLineTerminator(text, pos, unixLines, 0, logicalEndPos)
        }

        private fun isAtTrailingLineTerminator(
            text: InputScanner, pos: Int, unixLines: Boolean, logicalStartPos: Int, logicalEndPos: Int
        ): Boolean {
            val terminatorStart = text.trailingLineTerminatorStart(unixLines, logicalEndPos)
            // A logical context starting at the LF half of CRLF contains a standalone LF.
            return terminatorStart >= 0 && pos < logicalEndPos && pos == Math.max(terminatorStart, logicalStartPos)
        }

        /**
         * Computes which empty-width assertions hold at the given position in the text.
         * 
         * @param text the input text
         * @param pos the position (char index) to check
         * @return a bitmask of [EmptyOp] flags
         */
        fun emptyFlags(text: String?, pos: Int): Int {
            return emptyFlags(StringInputScanner(text!!), pos)
        }

        @JvmOverloads
        fun emptyFlags(text: InputScanner, pos: Int, includeGraphemeClusterBoundary: Boolean = true): Int {
            return emptyFlags(
                text, pos, includeGraphemeClusterBoundary, null as GraphemeSupport.Context?, -1, 0, false
            )
        }

        @JvmOverloads
        fun emptyFlags(
            text: InputScanner,
            pos: Int,
            includeGraphemeClusterBoundary: Boolean,
            graphemeContext: GraphemeSupport.Context?,
            hasWordBoundary: Boolean = true,
            hasTextAnchor: Boolean = true
        ): Int {
            return emptyFlags(
                text,
                pos,
                includeGraphemeClusterBoundary,
                graphemeContext,
                -1,
                0,
                false,
                0,
                text.length,
                text.length,
                text.length,
                hasWordBoundary,
                hasTextAnchor
            )
        }

        fun emptyFlags(
            text: InputScanner, pos: Int, includeGraphemeClusterBoundary: Boolean, matchStart: Int
        ): Int {
            return emptyFlags(
                text,
                pos,
                includeGraphemeClusterBoundary,
                null as GraphemeSupport.Context?,
                matchStart,
                0,
                false
            )
        }

        fun emptyFlags(
            text: InputScanner,
            pos: Int,
            includeGraphemeClusterBoundary: Boolean,
            matchStart: Int,
            regionStart: Int
        ): Int {
            return emptyFlags(
                text, pos, includeGraphemeClusterBoundary, null, matchStart, regionStart, false
            )
        }

        private fun emptyFlags(
            text: InputScanner,
            pos: Int,
            includeGraphemeClusterBoundary: Boolean,
            graphemeContext: GraphemeSupport.Context?,
            matchStart: Int,
            regionStart: Int,
            consumedInput: Boolean
        ): Int {
            return emptyFlags(
                text,
                pos,
                includeGraphemeClusterBoundary,
                graphemeContext,
                matchStart,
                regionStart,
                consumedInput,
                0,
                text.length,
                text.length,
                text.length,
                true,
                true
            )
        }

        fun emptyFlags(
            text: InputScanner,
            pos: Int,
            includeGraphemeClusterBoundary: Boolean,
            matchStart: Int,
            regionStart: Int,
            consumedInput: Boolean,
            anchorStartPos: Int,
            anchorEndPos: Int,
            boundaryEndPos: Int,
            hasWordBoundary: Boolean,
            hasTextAnchor: Boolean
        ): Int {
            return emptyFlags(
                text,
                pos,
                includeGraphemeClusterBoundary,
                null as GraphemeSupport.Context?,
                matchStart,
                regionStart,
                consumedInput,
                anchorStartPos,
                anchorEndPos,
                boundaryEndPos,
                boundaryEndPos,
                hasWordBoundary,
                hasTextAnchor
            )
        }

        fun emptyFlags(
            text: InputScanner,
            pos: Int,
            includeGraphemeClusterBoundary: Boolean,
            graphemeContext: GraphemeSupport.Context?,
            matchStart: Int,
            regionStart: Int,
            consumedInput: Boolean,
            anchorStartPos: Int,
            anchorEndPos: Int,
            boundaryEndPos: Int,
            wordBoundaryEndPos: Int,
            hasWordBoundary: Boolean,
            hasTextAnchor: Boolean
        ): Int {
            var flags = 0

            if (hasTextAnchor) {
                flags = flags or anchorFlags(text, pos, anchorStartPos, anchorEndPos, regionStart)
            }

            // \b and \B
            if (hasWordBoundary) {
                val prevCp =
                    if (pos > regionStart) InputScanner.codePoint(
                        text.decodeBackward(
                            pos,
                            regionStart
                        )
                    ) else -1
                val prevWord = prevCp >= 0 && isWordChar(prevCp)
                val nextCp =
                    if (pos < wordBoundaryEndPos)
                        InputScanner.codePoint(text.decodeForward(pos, wordBoundaryEndPos))
                    else
                        -1
                val nextWord = nextCp >= 0 && isWordChar(nextCp)
                if (prevWord != nextWord) {
                    flags = flags or EmptyOp.WORD_BOUNDARY
                } else {
                    flags = flags or EmptyOp.NON_WORD_BOUNDARY
                }

                // Unicode \b and \B
                val prevUnicodeWord =
                    prevCp >= 0 && (if (prevCp < 128) prevWord else isUnicodeWordChar(prevCp))
                val nextUnicodeWord =
                    nextCp >= 0 && (if (nextCp < 128) nextWord else isUnicodeWordChar(nextCp))
                if (prevUnicodeWord != nextUnicodeWord) {
                    flags = flags or EmptyOp.UNICODE_WORD_BOUNDARY
                } else {
                    flags = flags or EmptyOp.UNICODE_NON_WORD_BOUNDARY
                }
            }

            if (includeGraphemeClusterBoundary) {
                flags = flags or
                        GraphemeSupport.boundaryFlags(
                            text, pos, graphemeContext, matchStart, regionStart, consumedInput, boundaryEndPos
                        )
            }

            return flags
        }

        private fun anchorFlags(
            text: InputScanner, pos: Int, anchorStartPos: Int, anchorEndPos: Int, regionStart: Int
        ): Int {
            var flags = 0
            // Anchoring bounds set logical line ends; transparent bounds still expose CRLF context.
            val lineContextStart = Math.min(anchorStartPos, regionStart)
            if (pos == anchorStartPos) {
                flags = flags or EmptyOp.BEGIN_TEXT
                if (pos < anchorEndPos) {
                    flags = flags or (EmptyOp.BEGIN_LINE or EmptyOp.UNIX_BEGIN_LINE)
                }
            } else if (pos > anchorStartPos && pos < anchorEndPos) {
                val prev = text.codePointBefore(pos)
                if (prev == '\n'.code) {
                    flags = flags or (EmptyOp.BEGIN_LINE or EmptyOp.UNIX_BEGIN_LINE)
                } else if (prev == '\u0085'.code || prev == '\u2028'.code || prev == '\u2029'.code || (prev == '\r'.code && text.asciiAt(
                        pos
                    ) != '\n'.code)
                ) {
                    flags = flags or EmptyOp.BEGIN_LINE
                }
            }

            if (pos == anchorEndPos) {
                flags = flags or
                        (EmptyOp.END_TEXT
                                or EmptyOp.END_LINE
                                or EmptyOp.UNIX_END_LINE
                                or EmptyOp.DOLLAR_END
                                or EmptyOp.UNIX_DOLLAR_END)
            } else {
                if (pos >= anchorStartPos && pos < anchorEndPos) {
                    val ch = text.codePointAt(pos)
                    if (ch == '\n'.code) {
                        flags = flags or EmptyOp.UNIX_END_LINE
                    }
                    // Standard line mode treats CRLF as one atomic terminator.
                    if (isLineTerminator(ch)
                        && !(ch == '\n'.code && pos > lineContextStart && text.asciiAt(pos - 1) == '\r'.code)
                    ) {
                        flags = flags or EmptyOp.END_LINE
                    }
                }
                if (isAtTrailingLineTerminator(text, pos, false, lineContextStart, anchorEndPos)) {
                    flags = flags or EmptyOp.DOLLAR_END
                }
                if (isAtTrailingLineTerminator(text, pos, true, lineContextStart, anchorEndPos)) {
                    flags = flags or EmptyOp.UNIX_DOLLAR_END
                }
            }
            return flags
        }

        /**
         * Returns true if `pos` is a grapheme cluster boundary for SafeRE's supported approximation
         * of JDK `\X`.
         */
        fun isGraphemeClusterBoundary(text: String?, pos: Int): Boolean {
            return GraphemeSupport.isGraphemeClusterBoundary(text!!, pos)
        }

        fun isGraphemeClusterBoundary(
            text: String?, pos: Int, graphemeContext: GraphemeSupport.Context?
        ): Boolean {
            return GraphemeSupport.isGraphemeClusterBoundary(text!!, pos, graphemeContext)
        }

        fun isGraphemeClusterBoundary(
            text: String?, pos: Int, regionStart: Int, graphemeContext: GraphemeSupport.Context?
        ): Boolean {
            return GraphemeSupport.isGraphemeClusterBoundary(text!!, pos, regionStart, graphemeContext)
        }

        fun hasGraphemeExtendAt(text: String?, pos: Int): Boolean {
            return GraphemeSupport.hasGraphemeExtendAt(text!!, pos)
        }

        fun isExtendedPictographicAt(text: String?, pos: Int): Boolean {
            return GraphemeSupport.isExtendedPictographicAt(text!!, pos)
        }

        /** Returns true if the code point is a word character (`[A-Za-z0-9_]`).  */
        fun isWordChar(c: Int): Boolean {
            return Ascii.isWordChar(c)
        }

        /** Returns true if the code point is a Unicode word character (matching `\w` under UCC).  */
        fun isUnicodeWordChar(c: Int): Boolean {
            return Character.isAlphabetic(c)
                    || Character.getType(c) == Character.NON_SPACING_MARK.toInt() || Character.getType(c) == Character.ENCLOSING_MARK.toInt() || Character.getType(
                c
            ) == Character.COMBINING_SPACING_MARK.toInt() || Character.isDigit(c)
                    || Character.getType(c) == Character.CONNECTOR_PUNCTUATION.toInt() || c == 0x200C // ZWNJ
                    || c == 0x200D // ZWJ
        }
    }
}
