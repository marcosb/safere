// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.lang.String
import java.util.*
import kotlin.Array
import kotlin.Boolean
import kotlin.BooleanArray
import kotlin.Int
import kotlin.IntArray
import kotlin.arrayOfNulls
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.indices
import kotlin.collections.minus
import kotlin.collections.plus
import kotlin.concurrent.Volatile
import kotlin.plus
import kotlin.sequences.minus
import kotlin.sequences.plus
import kotlin.text.StringBuilder
import kotlin.times

/**
 * A compiled regular expression program, consisting of an array of [Inst] instructions. This
 * is the output of the `Compiler` and the input to the execution engines (NFA, DFA, etc.).
 * 
 * 
 * A Prog is produced by compiling a [Regexp] AST via Thompson NFA construction. Each
 * instruction represents a state in the NFA.
 */
internal class Prog {
    val instructions: MutableList<Inst> = ArrayList<Inst>()
    private var didFlatten = false
    private var instArray: Array<Inst>?
    private var start = 0
    private var startUnanchored = 0
    private var numCaptures = 0
    private var anchorStart = false
    private var anchorEnd = false

    /**
     * True when the end anchor was `$` (not `\z`). When true, the match may end before a
     * trailing `\n` at the end of the text, not just at the absolute end. This mirrors JDK's
     * default (non-MULTILINE) `$` behavior.
     */
    private var dollarAnchorEnd = false

    private var dollarAnchorUnixLines = false

    private var reversed = false
    private var unixLines = false
    private var lineStartUnixLines = false
    private var numLoopRegs = 0
    private var hasGraphemeSemantics = false
    private var hasGraphemeClusterInstruction = false
    private var hasWordBoundary = false
    private var hasTextAnchor = false

    /** Creates an empty program.  */
    constructor()

    /** Creates a copy of another program.  */
    constructor(other: Prog) : this(other,  /* copyInstructions= */true)

    /**
     * Copies another program's fields. With `copyInstructions` false, the copy shares the other
     * program's [Inst] objects, so it must not modify them.
     */
    private constructor(other: Prog, copyInstructions: Boolean) {
        if (copyInstructions) {
            for (inst in other.instructions) {
                this.instructions.add(Inst(inst))
            }
        } else {
            this.instructions.addAll(other.instructions)
        }
        this.didFlatten = other.didFlatten
        this.start = other.start
        this.startUnanchored = other.startUnanchored
        this.numCaptures = other.numCaptures
        this.anchorStart = other.anchorStart
        this.anchorEnd = other.anchorEnd
        this.dollarAnchorEnd = other.dollarAnchorEnd
        this.dollarAnchorUnixLines = other.dollarAnchorUnixLines
        this.reversed = other.reversed
        this.unixLines = other.unixLines
        this.lineStartUnixLines = other.lineStartUnixLines
        this.numLoopRegs = other.numLoopRegs
        this.hasGraphemeSemantics = other.hasGraphemeSemantics
        this.hasGraphemeClusterInstruction = other.hasGraphemeClusterInstruction
        this.hasWordBoundary = other.hasWordBoundary
        this.hasTextAnchor = other.hasTextAnchor
    }

    /** Returns the instruction at the given index. Must be called after [.freeze].  */
    fun inst(index: Int): Inst {
        return instArray!![index]
    }

    /** Returns the frozen instruction array. Callers must not modify it or its instructions.  */
    fun frozenInstructions(): Array<Inst>? {
        return instArray
    }

    /**
     * Returns the instruction at the given index from the mutable instruction list. Used during
     * compilation before the program is frozen.
     */
    fun mutableInst(index: Int): Inst? {
        return instructions.get(index)
    }

    /** Returns the total number of instructions.  */
    fun size(): Int {
        return if (instArray != null) instArray.length else instructions.size()
    }

    /** Returns true if the program has been flattened.  */
    fun didFlatten(): Boolean {
        return didFlatten
    }

    /**
     * Allocates a new instruction at the end of the program and returns its index. Must be called
     * before [.freeze].
     * 
     * @return the index of the newly allocated instruction
     */
    fun allocInst(): Int {
        val index: Int = instructions.size()
        instructions.add(Inst())
        return index
    }

    /**
     * Freezes the instruction list into a flat array for fast indexed access. Must be called after
     * all instructions have been allocated and initialized (typically at the end of compilation).
     * After freezing, [.inst] reads directly from the array, avoiding ArrayList overhead.
     */
    fun freeze() {
        instArray = instructions.toArray<Inst?>(arrayOfNulls<Inst>(0))
        hasGraphemeSemantics = computeHasGraphemeSemantics()
        hasGraphemeClusterInstruction = computeHasGraphemeClusterInstruction()
        hasWordBoundary = computeHasWordBoundary()
        hasTextAnchor = computeHasTextAnchor()
        lineStartUnixLines = true
        for (inst in instArray!!) {
            if (inst.op == InstOp.EMPTY_WIDTH && (inst.arg and EmptyOp.BEGIN_LINE) != 0) {
                lineStartUnixLines = false
                break
            }
        }
    }

    /** Returns the start instruction index for anchored matching.  */
    fun start(): Int {
        return start
    }

    /** Sets the start instruction index for anchored matching.  */
    fun setStart(start: Int) {
        this.start = start
    }

    /**
     * Returns the start instruction index for unanchored matching. This typically points to a `.*?` loop that skips to the real start.
     */
    fun startUnanchored(): Int {
        return startUnanchored
    }

    /** Sets the start instruction index for unanchored matching.  */
    fun setStartUnanchored(startUnanchored: Int) {
        this.startUnanchored = startUnanchored
    }

    /** Returns the number of capturing groups (including the implicit group 0 for the full match).  */
    fun numCaptures(): Int {
        return numCaptures
    }

    /** Sets the number of capturing groups.  */
    fun setNumCaptures(numCaptures: Int) {
        this.numCaptures = numCaptures
    }

    /** Returns true if the pattern is anchored at the start.  */
    fun anchorStart(): Boolean {
        return anchorStart
    }

    /** Sets whether the pattern is anchored at the start.  */
    fun setAnchorStart(anchorStart: Boolean) {
        this.anchorStart = anchorStart
    }

    /** Returns true if the pattern is anchored at the end.  */
    fun anchorEnd(): Boolean {
        return anchorEnd
    }

    /** Sets whether the pattern is anchored at the end.  */
    fun setAnchorEnd(anchorEnd: Boolean) {
        this.anchorEnd = anchorEnd
    }

    /**
     * Returns true if the end anchor was `$` (not `\z`), meaning the match may end before
     * a trailing newline at the end of the text.
     */
    fun dollarAnchorEnd(): Boolean {
        return dollarAnchorEnd
    }

    /** Sets whether the end anchor allows a trailing newline.  */
    fun setDollarAnchorEnd(dollarAnchorEnd: Boolean) {
        this.dollarAnchorEnd = dollarAnchorEnd
    }

    /** Returns the effective line mode of the stripped trailing end anchor.  */
    fun dollarAnchorUnixLines(): Boolean {
        return dollarAnchorUnixLines
    }

    /** Sets the effective line mode of the stripped trailing end anchor.  */
    fun setDollarAnchorUnixLines(unixLines: Boolean) {
        dollarAnchorUnixLines = unixLines
    }

    /** Returns true if this program runs in reverse (for finding match starts).  */
    fun reversed(): Boolean {
        return reversed
    }

    /** Sets whether this program runs in reverse.  */
    fun setReversed(reversed: Boolean) {
        this.reversed = reversed
    }

    /** Returns the number of loop progress-check registers allocated by the compiler.  */
    fun numLoopRegs(): Int {
        return numLoopRegs
    }

    /** Sets the number of loop progress-check registers.  */
    fun setNumLoopRegs(numLoopRegs: Int) {
        this.numLoopRegs = numLoopRegs
    }

    /**
     * Returns true if Unix lines mode is active. When true, only `'\n'` is recognized as a line
     * terminator. When false (default), all JDK line terminators are recognized.
     */
    fun unixLines(): Boolean {
        return unixLines
    }

    /** Returns whether line-start acceleration can restrict candidates to LF boundaries.  */
    fun lineStartUnixLines(): Boolean {
        return lineStartUnixLines
    }

    /** Sets whether Unix lines mode is active.  */
    fun setUnixLines(unixLines: Boolean) {
        this.unixLines = unixLines
    }

    /**
     * Returns true if the program contains EMPTY_WIDTH instructions with [ ][EmptyOp.WORD_BOUNDARY], [EmptyOp.NON_WORD_BOUNDARY], [ ][EmptyOp.UNICODE_WORD_BOUNDARY], or [EmptyOp.UNICODE_NON_WORD_BOUNDARY] flags. The DFA
     * handles these as deferred assertions that are re-expanded when the word-character context is
     * known.
     */
    fun hasWordBoundary(): Boolean {
        return hasWordBoundary
    }

    fun hasTextAnchor(): Boolean {
        return hasTextAnchor
    }

    private fun computeHasWordBoundary(): Boolean {
        val mask =
            (EmptyOp.WORD_BOUNDARY
                    or EmptyOp.NON_WORD_BOUNDARY
                    or EmptyOp.UNICODE_WORD_BOUNDARY
                    or EmptyOp.UNICODE_NON_WORD_BOUNDARY)
        val n = size()
        for (i in 0..<n) {
            val ip = inst(i)
            if (ip.op == InstOp.EMPTY_WIDTH && (ip.arg and mask) != 0) {
                return true
            }
        }
        return false
    }

    private fun computeHasTextAnchor(): Boolean {
        if (anchorStart || anchorEnd) {
            return true
        }
        val mask =
            (EmptyOp.BEGIN_LINE
                    or EmptyOp.END_LINE
                    or EmptyOp.BEGIN_TEXT
                    or EmptyOp.END_TEXT
                    or EmptyOp.DOLLAR_END
                    or EmptyOp.UNIX_DOLLAR_END
                    or EmptyOp.UNIX_BEGIN_LINE
                    or EmptyOp.UNIX_END_LINE)
        val n = size()
        for (i in 0..<n) {
            val ip = inst(i)
            if (ip.op == InstOp.EMPTY_WIDTH && (ip.arg and mask) != 0) {
                return true
            }
        }
        return false
    }

    /** Returns true if this program contains grapheme-sensitive matching.  */
    fun hasGraphemeSemantics(): Boolean {
        return hasGraphemeSemantics
    }

    /** Returns true if this program contains a consuming `\X` instruction.  */
    fun hasGraphemeClusterInstruction(): Boolean {
        return hasGraphemeClusterInstruction
    }

    private fun computeHasGraphemeSemantics(): Boolean {
        val n: Int = instArray.length
        for (i in 0..<n) {
            val ip = instArray!![i]
            if (ip.op == InstOp.GRAPHEME_CLUSTER
                || (ip.op == InstOp.EMPTY_WIDTH
                        && ((ip.arg
                        and (EmptyOp.GRAPHEME_CLUSTER_BOUNDARY
                        or EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY))
                        != 0))
            ) {
                return true
            }
        }
        return false
    }

    private fun computeHasGraphemeClusterInstruction(): Boolean {
        val n: Int = instArray.length
        for (i in 0..<n) {
            if (instArray!![i].op == InstOp.GRAPHEME_CLUSTER) {
                return true
            }
        }
        return false
    }

    /**
     * Returns a human-readable dump of the program, useful for debugging.
     * 
     * 
     * Example output:
     * 
     * <pre>
     * 0. char [0x61-0x61] -> 1
     * 1. match 0
    </pre> * 
     */
    fun dump(): String {
        val n = size()
        val sb = StringBuilder()
        for (i in 0..<n) {
            if (i == start) {
                sb.append('>')
            } else {
                sb.append(' ')
            }
            sb.append(
                String.format(
                    Locale.ROOT, "%d. %s\n", i, if (instArray != null) instArray!![i] else instructions.get(i)
                )
            )
        }
        return sb.toString()
    }

    override fun toString(): kotlin.String {
        return String.format(
            Locale.ROOT,
            "Prog{size=%d, start=%d, startUnanchored=%d, captures=%d}",
            size(),
            start,
            startUnanchored,
            numCaptures
        )
    }

    // ---------------------------------------------------------------------------
    // Program analysis for BitState
    // ---------------------------------------------------------------------------
    // Computed lazily on first use by BitState, possibly by several threads sharing one Pattern. The
    // computation is deterministic, so a racing duplicate computation is harmless, but the analysis
    // must be published through a volatile field so that readers see its arrays' elements.
    @Volatile
    private var bitStateAnalysis: BitStateAnalysis? = null

    private class BitStateAnalysis(
        epsilonCycleAlts: BooleanArray?,
        captureRunes: Array<CaptureRune?>?,
        loopEntries: Array<LoopEntry?>?,
        captureProgressChecks: Array<Inst?>?,
        greedyRunes: Array<Inst?>?,
        lazyRuneLoops: Array<LazyRuneLoop?>?
    ) {
        val epsilonCycleAlts: BooleanArray?
        val captureRunes: Array<CaptureRune?>?
        val loopEntries: Array<LoopEntry?>?
        val captureProgressChecks: Array<Inst?>?
        val greedyRunes: Array<Inst?>?
        val lazyRuneLoops: Array<LazyRuneLoop?>?

        init {
            this.epsilonCycleAlts = epsilonCycleAlts
            this.captureRunes = captureRunes
            this.loopEntries = loopEntries
            this.captureProgressChecks = captureProgressChecks
            this.greedyRunes = greedyRunes
            this.lazyRuneLoops = lazyRuneLoops
        }
    }

    /**
     * Returns a boolean array indexed by instruction ID. An entry is `true` if that instruction
     * is an ALT/ALT_MATCH that participates in an epsilon cycle — a path back to itself through only
     * epsilon transitions (ALT, NOP, CAPTURE, EMPTY_WIDTH). Only these ALT instructions need the
     * BitState visited bitmap to prevent infinite loops; other ALTs can be safely revisited from
     * different DFS paths.
     * 
     * 
     * The result is computed once and cached.
     */
    fun epsilonCycleAlts(): BooleanArray? {
        return bitStateAnalysis().epsilonCycleAlts
    }

    /** Returns shared read-only fusion metadata, or null when no sequences can be fused.  */
    fun captureRunes(): Array<CaptureRune?>? {
        return bitStateAnalysis().captureRunes
    }

    /** Returns shared entry/check fusion targets, or null when no pairs can be fused.  */
    fun loopEntries(): Array<LoopEntry?>? {
        return bitStateAnalysis().loopEntries
    }

    /** Returns shared capture/check fusion targets, or null when no pairs can be fused.  */
    fun captureProgressChecks(): Array<Inst?>? {
        return bitStateAnalysis().captureProgressChecks
    }

    /**
     * Returns consuming bodies of simple greedy loops, or null when none exist. Each body is the
     * ALT's preferred branch and consumes one rune before jumping directly back to that ALT.
     */
    fun greedyRunes(): Array<Inst?>? {
        return bitStateAnalysis().greedyRunes
    }

    private fun computeGreedyRunes(reachable: BooleanArray): Array<Inst?>? {
        var runes: Array<Inst?>? = null
        for (i in 0..<size()) {
            val alt = inst(i)
            if (reachable[i] && (alt.opCode == InstOp.Companion.OP_ALT || alt.opCode == InstOp.Companion.OP_ALT_MATCH)) {
                val rune = inst(alt.out)
                if ((rune.opCode == InstOp.Companion.OP_CHAR_RANGE || rune.opCode == InstOp.Companion.OP_CHAR_CLASS)
                    && rune.out == i
                ) {
                    if (runes == null) {
                        runes = arrayOfNulls<Inst>(size())
                    }
                    runes[i] = rune
                }
            }
        }
        return runes
    }

    /** Returns simple lazy character loops with a literal exit, or null if none exist.  */
    fun lazyRuneLoops(): Array<LazyRuneLoop?>? {
        return bitStateAnalysis().lazyRuneLoops
    }

    private fun computeLazyRuneLoops(reachable: BooleanArray): Array<LazyRuneLoop?>? {
        var loops: Array<LazyRuneLoop?>? = null
        for (i in 0..<size()) {
            val alt = inst(i)
            if (!reachable[i] || alt.op != InstOp.ALT) {
                continue
            }
            val rune = inst(alt.out1)
            if ((rune.op != InstOp.CHAR_RANGE && rune.op != InstOp.CHAR_CLASS) || rune.out != i) {
                continue
            }
            var exit = alt.out
            for (steps in 0..<size()) {
                val next = inst(exit)
                if (next.op == InstOp.CAPTURE || next.op == InstOp.NOP) {
                    exit = next.out
                    continue
                }
                if (next.op == InstOp.CHAR_RANGE && next.lo == next.hi && next.lo < 128 && !next.foldCase) {
                    if (loops == null) {
                        loops = arrayOfNulls<LazyRuneLoop>(size())
                    }
                    loops[i] = LazyRuneLoop(rune, next.lo, alt.out)
                }
                break
            }
        }
        return loops
    }

    private fun bitStateAnalysis(): BitStateAnalysis {
        var result = bitStateAnalysis
        if (result == null) {
            val reachable = computeReachableInstructions()
            val captures = computeCaptureRunes(reachable)
            result =
                BitStateAnalysis(
                    computeEpsilonCycleAlts(reachable),
                    captures,
                    computeLoopEntries(reachable, captures),
                    computeCaptureProgressChecks(reachable),
                    computeGreedyRunes(reachable),
                    computeLazyRuneLoops(reachable)
                )
            bitStateAnalysis = result
        }
        return result
    }

    private fun computeEpsilonCycleAlts(reachable: BooleanArray): BooleanArray {
        val n = size()
        val inCycle = BooleanArray(n)

        for (i in 0..<n) {
            val inst = inst(i)
            if (!reachable[i] || (inst.op != InstOp.ALT && inst.op != InstOp.ALT_MATCH)) {
                continue
            }
            if (canReachSelfViaEpsilon(i, reachable)) {
                inCycle[i] = true
            }
        }
        return inCycle
    }

    private fun computeCaptureRunes(reachable: BooleanArray): Array<CaptureRune?>? {
        var fused: Array<CaptureRune?>? = null
        for (i in 0..<size()) {
            val instruction = inst(i)
            // Discarded fragments may retain unresolved patch-list links instead of successor IDs.
            if (reachable[i] && instruction.opCode == InstOp.Companion.OP_CAPTURE) {
                val rune = inst(instruction.out)
                if (rune.opCode == InstOp.Companion.OP_CHAR_RANGE || rune.opCode == InstOp.Companion.OP_CHAR_CLASS) {
                    val end = inst(rune.out)
                    if (end.opCode == InstOp.Companion.OP_CAPTURE) {
                        if (fused == null) {
                            fused = arrayOfNulls<CaptureRune>(size())
                        }
                        fused[i] = CaptureRune(rune, end.arg, end.out)
                    }
                }
            }
        }
        return fused
    }

    private fun computeLoopEntries(reachable: BooleanArray, captures: Array<CaptureRune?>?): Array<LoopEntry?>? {
        var entries: Array<LoopEntry?>? = null
        for (i in 0..<size()) {
            val entry = inst(i)
            if (reachable[i] && entry.opCode == InstOp.Companion.OP_LOOP_ENTER) {
                val check = inst(entry.out)
                if (check.opCode == InstOp.Companion.OP_PROGRESS_CHECK && check.arg == entry.arg && check.out != 0) {
                    if (entries == null) {
                        entries = arrayOfNulls<LoopEntry>(size())
                    }
                    val body = inst(check.out)
                    // Retain the existing capture/consumer/capture fusion when it can do more work.
                    if (body.opCode == InstOp.Companion.OP_CAPTURE
                        && (captures == null || captures[check.out] == null)
                    ) {
                        entries[i] = LoopEntry(body.out, body.arg)
                    } else {
                        entries[i] = LoopEntry(check.out, -1)
                    }
                }
            }
        }
        return entries
    }

    private fun computeCaptureProgressChecks(reachable: BooleanArray): Array<Inst?>? {
        var checks: Array<Inst?>? = null
        for (i in 0..<size()) {
            val capture = inst(i)
            if (reachable[i] && capture.opCode == InstOp.Companion.OP_CAPTURE) {
                val next = inst(capture.out)
                if (next.opCode == InstOp.Companion.OP_PROGRESS_CHECK) {
                    if (checks == null) {
                        checks = arrayOfNulls<Inst>(size())
                    }
                    checks[i] = next
                }
            }
        }
        return checks
    }

    /**
     * Returns instruction reachability from both entry points. Only reachable instructions are
     * guaranteed to have patched successor fields; discarded fragments may retain patch-list links.
     * Grapheme consumption reaches its successor here, but breaks paths in the epsilon-cycle walk.
     */
    private fun computeReachableInstructions(): BooleanArray {
        val n = size()
        val reachable = BooleanArray(n)
        val stack = ArrayDeque<Int?>()
        addSuccessor(start, reachable, stack)
        addSuccessor(startUnanchored, reachable, stack)

        while (!stack.isEmpty()) {
            val id: Int = stack.pop()
            val inst = inst(id)
            when (inst.op) {
                InstOp.ALT, InstOp.ALT_MATCH, InstOp.PROGRESS_CHECK -> {
                    addSuccessor(inst.out, reachable, stack)
                    addSuccessor(inst.out1, reachable, stack)
                }

                InstOp.NOP, InstOp.CAPTURE, InstOp.LOOP_ENTER, InstOp.EMPTY_WIDTH, InstOp.CHAR_RANGE, InstOp.CHAR_CLASS, InstOp.GRAPHEME_CLUSTER -> addSuccessor(
                    inst.out,
                    reachable,
                    stack
                )

                else -> {}
            }
        }
        return reachable
    }

    /**
     * Returns true if instruction `target` can reach itself via a path that consists entirely
     * of epsilon transitions (ALT, ALT_MATCH, NOP, CAPTURE, EMPTY_WIDTH). CHAR_RANGE and MATCH
     * consume input or terminate, so they break any epsilon path.
     */
    private fun canReachSelfViaEpsilon(target: Int, reachable: BooleanArray): Boolean {
        val n = size()
        val visited = BooleanArray(n)
        val stack = ArrayDeque<Int?>()

        // Seed with epsilon successors of target (don't count target itself as a starting node).
        addEpsilonSuccessors(target, reachable, visited, stack)

        while (!stack.isEmpty()) {
            val id: Int = stack.pop()
            if (id == target) {
                return true
            }
            addEpsilonSuccessors(id, reachable, visited, stack)
        }
        return false
    }

    private fun addEpsilonSuccessors(
        id: Int, reachable: BooleanArray, visited: BooleanArray, stack: ArrayDeque<Int?>
    ) {
        val inst = inst(id)
        when (inst.op) {
            InstOp.ALT, InstOp.ALT_MATCH -> {
                addEpsilonSuccessor(inst.out, reachable, visited, stack)
                addEpsilonSuccessor(inst.out1, reachable, visited, stack)
            }

            InstOp.NOP, InstOp.CAPTURE, InstOp.LOOP_ENTER, InstOp.EMPTY_WIDTH -> {
                addEpsilonSuccessor(inst.out, reachable, visited, stack)
            }

            else -> {}
        }
    }

    private class PredecessorGraph(ninst: Int) {
        val head: IntArray
        var nextEdge: IntArray
        var predInst: IntArray
        var edgeCount: Int = 0

        init {
            head = IntArray(ninst)
            Arrays.fill(head, -1)
            nextEdge = IntArray(Math.max(16, ninst * 2))
            predInst = IntArray(Math.max(16, ninst * 2))
        }

        fun addPredecessor(to: Int, from: Int) {
            if (to < 0 || to >= head.length) {
                return
            }
            if (edgeCount == nextEdge.length) {
                val newCap: Int = nextEdge.length * 2
                nextEdge = Arrays.copyOf(nextEdge, newCap)
                predInst = Arrays.copyOf(predInst, newCap)
            }
            val edge = edgeCount++
            predInst[edge] = from
            nextEdge[edge] = head[to]
            head[to] = edge
        }
    }

    /**
     * Returns a flattened, frozen copy of this program, leaving this program unchanged. This avoids
     * copying every instruction first: [.flatten] reads the original instructions and emits
     * new ones, so the copy can share them until it replaces its instruction list.
     */
    fun flattenedCopy(): Prog {
        val copy = Prog(this,  /* copyInstructions= */false)
        copy.flatten()
        copy.freeze()
        return copy
    }

    fun flatten() {
        if (didFlatten) {
            return
        }

        val ninst: Int = instructions.size()
        val rootmap = IntArray(ninst)
        Arrays.fill(rootmap, -1)
        val roots = IntArrayList(ninst)
        val predecessors = PredecessorGraph(ninst)

        val reachable = BooleanArray(ninst)
        val reachableList = IntArrayList(ninst)
        val stack = IntArrayList(ninst)

        // Pass 1: Mark successor roots and predecessors.
        markSuccessors(rootmap, roots, predecessors, reachable, reachableList, stack)

        // Pass 2: Mark dominator roots by working backwards.
        val sortedRoots = roots.toArray()
        Arrays.sort(sortedRoots)
        for (i in sortedRoots.indices.reversed()) {
            val root = sortedRoots[i]
            markDominator(root, rootmap, roots, predecessors, reachable, reachableList, stack)
        }

        // Pass 3: Emit lists. Remap outs to root-ids.
        val flatmap = IntArray(roots.size())
        val flat: MutableList<Inst> = ArrayList<Inst>(ninst)

        for (r in 0..<roots.size()) {
            val root = roots.get(r)
            flatmap[r] = flat.size()
            emitList(root, rootmap, flat, reachable, reachableList, stack)
            flat.get(flat.size() - 1).last = true
        }

        // Pass 4: Remap outs from root-ids to flat-ids.
        for (inst in flat) {
            if (inst.op == InstOp.ALT_MATCH) {
                // AltMatch's outs are already flat offsets (flat.len() and flat.len() + 1)
            } else if (inst.op == InstOp.PROGRESS_CHECK) {
                inst.out = flatmap[inst.out]
                inst.out1 = flatmap[inst.out1]
            } else {
                inst.out = flatmap[inst.out]
            }
        }

        // Remap startUnanchored and start.
        if (startUnanchored == 0) {
            // do nothing
        } else if (startUnanchored == start) {
            startUnanchored = flatmap[1]
            start = flatmap[1]
        } else {
            startUnanchored = flatmap[1]
            start = flatmap[2]
        }
        instructions.clear()
        instructions.addAll(flat)
        didFlatten = true
    }

    private fun markSuccessors(
        rootmap: IntArray,
        roots: IntArrayList,
        predecessors: PredecessorGraph,
        reachable: BooleanArray,
        reachableList: IntArrayList,
        stack: IntArrayList
    ) {
        // Mark the kInstFail instruction.

        rootmap[0] = roots.size()
        roots.add(0)

        // Mark the startUnanchored and start instructions.
        if (rootmap[startUnanchored] == -1) {
            rootmap[startUnanchored] = roots.size()
            roots.add(startUnanchored)
        }
        if (rootmap[start] == -1) {
            rootmap[start] = roots.size()
            roots.add(start)
        }

        reachableList.clear()
        stack.clear()
        stack.add(startUnanchored)

        var nextId = -1
        while (!stack.isEmpty() || nextId != -1) {
            var id = nextId
            if (id == -1) {
                id = stack.removeLast()
            } else {
                nextId = -1
            }

            if (reachable[id]) {
                continue
            }
            reachable[id] = true
            reachableList.add(id)

            val inst = instructions.get(id)
            when (inst.op) {
                InstOp.ALT, InstOp.ALT_MATCH -> {
                    val out = inst.out
                    val out1 = inst.out1
                    predecessors.addPredecessor(out, id)
                    predecessors.addPredecessor(out1, id)
                    stack.add(out1)
                    nextId = out
                }

                InstOp.CHAR_RANGE, InstOp.CHAR_CLASS, InstOp.CAPTURE, InstOp.LOOP_ENTER, InstOp.EMPTY_WIDTH, InstOp.GRAPHEME_CLUSTER -> {
                    val out = inst.out
                    if (rootmap[out] == -1) {
                        rootmap[out] = roots.size()
                        roots.add(out)
                    }
                    nextId = out
                }

                InstOp.PROGRESS_CHECK -> {
                    val out = inst.out
                    val out1 = inst.out1
                    if (rootmap[out] == -1) {
                        rootmap[out] = roots.size()
                        roots.add(out)
                    }
                    if (rootmap[out1] == -1) {
                        rootmap[out1] = roots.size()
                        roots.add(out1)
                    }
                    stack.add(out1)
                    nextId = out
                }

                InstOp.NOP -> {
                    nextId = inst.out
                }

                InstOp.MATCH, InstOp.FAIL -> {
                    // terminates
                }
            }
        }

        for (i in 0..<reachableList.size()) {
            reachable[reachableList.get(i)] = false
        }
    }

    private fun markDominator(
        root: Int,
        rootmap: IntArray,
        roots: IntArrayList,
        predecessors: PredecessorGraph,
        reachable: BooleanArray,
        reachableList: IntArrayList,
        stack: IntArrayList
    ) {
        reachableList.clear()
        stack.clear()
        stack.add(root)

        var nextId = -1
        while (!stack.isEmpty() || nextId != -1) {
            var id = nextId
            if (id == -1) {
                id = stack.removeLast()
            } else {
                nextId = -1
            }

            if (reachable[id]) {
                continue
            }
            reachable[id] = true
            reachableList.add(id)

            if (id != root && rootmap[id] != -1) {
                continue
            }

            val inst = instructions.get(id)
            when (inst.op) {
                InstOp.ALT, InstOp.ALT_MATCH -> {
                    stack.add(inst.out1)
                    nextId = inst.out
                }

                InstOp.NOP -> {
                    nextId = inst.out
                }

                InstOp.CHAR_RANGE, InstOp.CHAR_CLASS, InstOp.CAPTURE, InstOp.LOOP_ENTER, InstOp.EMPTY_WIDTH, InstOp.GRAPHEME_CLUSTER, InstOp.PROGRESS_CHECK, InstOp.MATCH, InstOp.FAIL -> {
                    // Leaf/non-epsilon
                }
            }
        }

        for (idx in 0..<reachableList.size()) {
            val i = reachableList.get(idx)
            var edge = predecessors.head[i]
            while (edge != -1) {
                val pred = predecessors.predInst[edge]
                if (!reachable[pred]) {
                    if (rootmap[i] == -1) {
                        rootmap[i] = roots.size()
                        roots.add(i)
                    }
                }
                edge = predecessors.nextEdge[edge]
            }
        }

        for (idx in 0..<reachableList.size()) {
            reachable[reachableList.get(idx)] = false
        }
    }

    private fun emitList(
        root: Int,
        rootmap: IntArray,
        flat: MutableList<Inst>,
        reachable: BooleanArray,
        reachableList: IntArrayList,
        stack: IntArrayList
    ) {
        reachableList.clear()
        stack.clear()
        stack.add(root)

        var nextId = -1
        while (!stack.isEmpty() || nextId != -1) {
            var id = nextId
            if (id == -1) {
                id = stack.removeLast()
            } else {
                nextId = -1
            }

            if (reachable[id]) {
                continue
            }
            reachable[id] = true
            reachableList.add(id)

            if (id != root && rootmap[id] != -1) {
                val nop = Inst()
                nop.initNop(rootmap[id])
                flat.add(nop)
                continue
            }

            val inst = instructions.get(id)
            when (inst.op) {
                InstOp.ALT -> {
                    stack.add(inst.out1)
                    nextId = inst.out
                }

                InstOp.ALT_MATCH -> {
                    val flatAltMatch = Inst()
                    flatAltMatch.op = InstOp.ALT_MATCH
                    flatAltMatch.opCode = InstOp.Companion.OP_ALT_MATCH
                    flatAltMatch.out = flat.size()
                    flatAltMatch.out1 = flat.size() + 1
                    flat.add(flatAltMatch)

                    stack.add(inst.out1)
                    nextId = inst.out
                }

                InstOp.NOP -> {
                    nextId = inst.out
                }

                InstOp.PROGRESS_CHECK -> {
                    val newInst = Inst(inst)
                    newInst.out = rootmap[inst.out]
                    newInst.out1 = rootmap[inst.out1]
                    flat.add(newInst)
                }

                InstOp.CAPTURE, InstOp.LOOP_ENTER, InstOp.EMPTY_WIDTH, InstOp.CHAR_RANGE, InstOp.CHAR_CLASS, InstOp.GRAPHEME_CLUSTER -> {
                    val newInst = Inst(inst)
                    newInst.out = rootmap[inst.out]
                    flat.add(newInst)
                }

                InstOp.MATCH, InstOp.FAIL -> {
                    val newInst = Inst(inst)
                    flat.add(newInst)
                }
            }
        }

        for (idx in 0..<reachableList.size()) {
            reachable[reachableList.get(idx)] = false
        }
    }

    companion object {
        private fun addEpsilonSuccessor(
            id: Int, reachable: BooleanArray, visited: BooleanArray, stack: ArrayDeque<Int?>
        ) {
            if (id > 0 && id < reachable.length && reachable[id] && !visited[id]) {
                visited[id] = true
                stack.push(id)
            }
        }

        private fun addSuccessor(id: Int, reachable: BooleanArray, stack: ArrayDeque<Int?>) {
            if (id > 0 && id < reachable.length && !reachable[id]) {
                reachable[id] = true
                stack.push(id)
            }
        }
    }
}
