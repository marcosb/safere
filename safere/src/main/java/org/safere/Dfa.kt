// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.safere.StateAccelerator.*
import org.safere.Utf8StartAccelerator.SearchCursor
import java.util.*

/**
 * Lazy DFA execution engine. Builds DFA states on demand from the compiled NFA program using the
 * subset construction. Each DFA state represents a set of simultaneously active NFA states.
 * 
 * 
 * The DFA provides fast boolean matching and match-end detection. It cannot track submatch
 * (capture group) boundaries — that requires the NFA. The typical usage pattern is:
 * 
 * 
 *  1. Use the forward DFA to quickly determine if a match exists and where it ends.
 *  1. Use the NFA on the narrowed range to extract capture groups.
 * 
 * 
 * 
 * This is a port of RE2's `dfa.cc`, adapted for Java's Unicode code point model:
 * 
 * 
 *  * Operates on Unicode code points (0–0x10FFFF), not bytes (0–255).
 *  * Uses code point equivalence classes (derived from CHAR_RANGE instructions) instead of a
 * byte-level bytemap.
 *  * Single-threaded (no lock-free atomic transitions).
 * 
 */
internal class Dfa {
    /** Result of a DFA search.  */
    @JvmRecord
    internal data class SearchResult(
        val matched: Boolean,
        val pos: Int,
        val ambiguous: Boolean,
        val scanLimitReached: Boolean
    ) {
        constructor(matched: Boolean, pos: Int) : this(matched, pos, false, false)

        constructor(matched: Boolean, pos: Int, ambiguous: Boolean) : this(matched, pos, ambiguous, false)
    }

    /** Result of a multi-match DFA search.  */ // TODO(#98): Replace int[] with Guava ImmutableIntArray to get proper value semantics.
    @JvmRecord
    internal data class ManyMatchResult(val matched: Boolean, val matchIds: IntArray?)

    // ---------------------------------------------------------------------------
    // State representation
    // ---------------------------------------------------------------------------
    /**
     * A DFA state: a set of NFA instruction IDs (the "frontier" of consuming/accepting instructions)
     * plus position-dependent flags. States are cached and shared across transitions to avoid
     * recomputation.
     */
    internal class State(
        val id: Int,
        // sorted NFA instruction IDs (CHAR_RANGE, EMPTY_WIDTH, and MATCH only)
        val insts: IntArray,
        val flags: Int,
        /**
         * Match IDs from word-boundary expansion (for PatternSet multi-match). Null if not applicable.
         */
        val wordBoundaryMatchIds: IntArray?,
        numClasses: Int,
        val isHighestPriorityMatch: Boolean,
        val accelerator: StateAccelerator?
    ) {
        // Only forward unanchored starts may use start-position acceleration.
        var isStartState: Boolean = false

        /** Transitions indexed by equivalence class; null entry = not yet computed.  */
        val next: Array<State?>

        init {
            this.next = arrayOfNulls<State>(numClasses)
        }

        val isMatch: Boolean
            get() = (flags and FLAG_MATCH) != 0
    }

    /** Cache key for state deduplication.  */
    private class StateKey {
        private var insts: IntArray? = null
        private var flags = 0

        constructor()

        constructor(insts: IntArray?, flags: Int) {
            this.insts = insts
            this.flags = flags
        }

        fun reset(insts: IntArray?, flags: Int) {
            this.insts = insts
            this.flags = flags
        }

        fun copy(): StateKey {
            return StateKey(insts, flags)
        }

        override fun hashCode(): Int {
            return Arrays.hashCode(insts) * 31 + flags
        }

        override fun equals(o: Any?): Boolean {
            return (o is StateKey)
                    && flags == o.flags && Arrays.equals(insts, o.insts)
        }
    }

    // ---------------------------------------------------------------------------
    // Instance fields
    // ---------------------------------------------------------------------------
    private val prog: Prog
    private val maxStates: Int
    private val longest: Boolean
    private val hasGraphemeSemantics: Boolean

    /** Whether consuming transitions can depend on the absolute input position.  */
    private val hasPositionDependentTransitions: Boolean
    private val stateEmptyFlagsMask: Int
    private val startCacheEmptyFlagsMask: Int
    private val anchoredCacheBit: Int
    private val reverseCacheBit: Int
    private val crLfCacheBit: Int
    private val hasCrLfContext: Boolean

    /** Sorted code point boundaries defining equivalence classes.  */
    private val boundaries: IntArray

    /**
     * Total transition classes, including any Unicode word split and a separate end-of-text class.
     */
    private val numClasses: Int

    /** Whether each interval is split by Unicode word-character membership.  */
    private val splitUnicodeWordClasses: Boolean

    /**
     * Fast ASCII-to-class lookup table. For code points 0–127, `asciiClassMap[cp]` gives the
     * equivalence class index directly, avoiding binary search. -1 means "not populated" (should not
     * happen for valid ASCII).
     */
    private val asciiClassMap: IntArray

    /** Cache for mapping code points >= 128 to their equivalence class.  */
    private val cacheCps = IntArray(256)

    private val cacheClasses = IntArray(256)

    /** State cache: maps instruction-set + flags to canonical State instance.  */
    private val cache: MutableMap<StateKey?, State?> = HashMap<StateKey?, State?>()

    /** Pre-allocated mutable key to perform allocation-free cache lookups.  */
    private val lookupKey = StateKey()

    /** Sentinel dead state: no instructions, no transitions possible.  */
    private val deadState: State

    /** Pre-allocated visited generation array for [.expand], reused across calls.  */
    private val expandVisitedGen: IntArray

    private var expandGeneration = 0

    /**
     * Pre-allocated stack array for [.expand]. Sized to the program length (worst case: every
     * instruction pushed once).
     */
    private val expandStack: IntArray

    /**
     * Pre-allocated frontier array for [.expand]. Sized to the program length (worst case:
     * every instruction is a frontier instruction).
     */
    private val expandFrontier: IntArray

    /** Pre-allocated workspace for seed/successor collection in computeNext().  */
    private val computeBuf: IntArray

    /**
     * Bounded direct-mapped start cache. Every hit checks the complete context key; collisions only
     * evict an optimization and never merge semantic contexts. Its allocation is independent of the
     * number or bit positions of assertion flags.
     */
    private val startStateByContext = arrayOfNulls<State>(256)

    private val startStateContextKeys = IntArray(256)

    /**
     * Start states for programs whose start context is fully described by direction, anchoring and
     * the word-character class of the adjacent characters: no text anchors, no CRLF context and no
     * grapheme semantics. Indexed by [.fastStartIndex]; cleared with the other start caches.
     */
    private val fastStartStates: Array<State?>?

    /** Per-search grapheme context. Set only while a search method is active.  */
    private var graphemeContext: GraphemeSupport.Context? = null

    /**
     * Input positions this DFA has covered since the state cache was last emptied.
     * 
     * 
     * A cache generation that covers at least [.MIN_PROGRESS_PER_CACHED_STATE] positions per
     * state before filling the budget has paid for the cost of building those states, and may discard
     * the cache immediately when it fills. Spans covered by the caller's NFA fallback after a DFA
     * bailout and input before a search's starting position are excluded so NFA or unvisited input is
     * never mistaken for DFA cache productivity. Each search records the position it actually reached
     * on exit, including no-match, reverse, and multi-pattern searches.
     */
    private var dfaStepsSinceReset: Long = 0

    /**
     * Input positions this DFA covered, plus a bounded credit for each NFA fallback, since the state
     * cache was last emptied. This waits out the exponential backoff after an unproductive generation
     * without counting unscanned gaps between searches.
     */
    private var backoffProgress: Long = 0

    /** Position from which the active search (or post-bailout span) is measured.  */
    private var progressAnchor = 0

    /** Whether a search has established [.progressAnchor].  */
    private var hasProgressAnchor = false

    /**
     * Whether the most recent search on this DFA returned null because the state budget was
     * exhausted. Every null search result sets this; see [.lastSearchBailed].
     */
    private var lastSearchBailed = false

    /**
     * Start-acceleration backoff state (see [AdaptiveBackoff]) at the end of the most recent
     * forward search. Written once per search, never read inside a search; see [ ][.startBackoff].
     */
    private var lastStartBackoff: Long = 0

    private var innerProbeState = 0
    private var innerProbeResumePos: Long = 0

    // Notifications removed after a probe are rearmed at the next unanchored search. A slot is
    // queued only when its negative notification is visited, so maintenance is charged to DFA work.
    private var pausedFailureTransitions: IntArray = EMPTY_INSTS
    private var pausedFailureTransitionCount = 0
    private var pendingFailureTransitionIndex = -1

    /**
     * Whether the current cache generation has already been penalized for exhausting its state budget
     * before covering [.MIN_PROGRESS_PER_CACHED_STATE] positions per state, so repeated hits on
     * the same full cache do not increment [.unproductiveResets] more than once per generation.
     */
    private var currentGenerationPenalized = false

    /**
     * Consecutive cache generations that filled the state budget without covering [ ][.MIN_PROGRESS_PER_CACHED_STATE] positions per state!!. Each such generation doubles the input
     * span required before the next cache reset is permitted (`(10 << unproductiveResets) * cache.size`), bounding total rebuild work on adversarial inputs to a geometric series while
     * allowing long-lived `ThreadLocal<Dfa>` instances to recover on later inputs.
     */
    private var unproductiveResets = 0

    private lateinit var transitions: IntArray
    private lateinit var offsetToState: Array<State?>
    private lateinit var isAcceleratedStateOffset: BooleanArray
    private var hasStateAccelerators = false
    private var nextStateId = 0

    private val utf8StartAccelerator: Utf8StartAccelerator?
    private val stringStartAccelerator: StringStartAccelerator?
    private val innerLiteralSearch: InnerLiteralSearch?
    private val hasStartAcceleration: Boolean
    // ---------------------------------------------------------------------------
    // Construction
    // ---------------------------------------------------------------------------
    /**
     * Pre-computed immutable DFA setup: equivalence class boundaries and ASCII lookup table. These
     * are derived solely from the compiled [Prog] and can be shared across all DFA instances
     * for the same program (e.g., across multiple [Matcher] instances).
     */
    // TODO(#98): Replace int[] with Guava ImmutableIntArray to get proper value semantics.
    @JvmRecord
    internal data class Setup(
        val boundaries: IntArray,
        val numClasses: Int,
        val asciiClassMap: IntArray,
        val splitUnicodeWordClasses: Boolean
    )

    @JvmOverloads
    constructor(
        prog: Prog,
        maxStates: Int,
        setup: Setup,
        longest: Boolean,
        utf8StartAccelerator: Utf8StartAccelerator? = null,
        stringStartAccelerator: StringStartAccelerator? = null,
        innerLiteralSearch: InnerLiteralSearch? = null
    ) {
        this.prog = prog
        this.maxStates = maxStates
        this.longest = longest
        this.utf8StartAccelerator = utf8StartAccelerator
        this.stringStartAccelerator = stringStartAccelerator
        this.innerLiteralSearch = innerLiteralSearch
        this.hasStartAcceleration = utf8StartAccelerator != null || stringStartAccelerator != null
        this.hasGraphemeSemantics = prog.hasGraphemeSemantics()
        this.hasCrLfContext = hasStandardLineBoundary(prog)
        this.fastStartStates =
            if (!hasGraphemeSemantics && !prog.hasTextAnchor() && !hasCrLfContext)
                arrayOfNulls<State>(if (prog.hasWordBoundary()) 64 else 4)
            else
                null
        this.hasPositionDependentTransitions = hasGraphemeSemantics || prog.hasTextAnchor()
        this.stateEmptyFlagsMask =
            if (hasGraphemeSemantics)
                EmptyOp.ALL_FLAGS
            else
                EmptyOp.ALL_FLAGS and EmptyOp.GRAPHEME_CLUSTER_BOUNDARY.inv()
        this.startCacheEmptyFlagsMask =
            if (hasGraphemeSemantics)
                EmptyOp.ALL_FLAGS
            else
                (EmptyOp.ALL_FLAGS
                        and (EmptyOp.GRAPHEME_CLUSTER_BOUNDARY or EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY).inv())
        this.reverseCacheBit = (startCacheEmptyFlagsMask + 1) shl 2
        this.anchoredCacheBit = reverseCacheBit shl 1
        this.crLfCacheBit = if (hasCrLfContext) anchoredCacheBit shl 1 else 0
        this.boundaries = setup.boundaries
        this.numClasses = setup.numClasses
        this.splitUnicodeWordClasses = setup.splitUnicodeWordClasses
        this.asciiClassMap = setup.asciiClassMap
        Arrays.fill(this.cacheCps, -1)
        this.expandVisitedGen = IntArray(prog.size)
        this.expandStack = IntArray(prog.size)
        this.expandFrontier = IntArray(prog.size)
        this.computeBuf = IntArray(prog.size)
        this.deadState = State(0, EMPTY_INSTS, 0, null, numClasses, false, null)
        this.nextStateId = 1
        this.transitions = IntArray(1024)
        this.offsetToState = arrayOfNulls<State>(1024)
        this.isAcceleratedStateOffset = BooleanArray(1024)
        addStateToFlatArrays(deadState)
    }

    private fun addStateToFlatArrays(s: State) {
        val minTransLen = (s!!.id + 1) * numClasses
        if (minTransLen > transitions.size) {
            val newLen = Math.max(transitions.size * 2, minTransLen)
            transitions = Arrays.copyOf(transitions, newLen)
            offsetToState = Arrays.copyOf<State?>(offsetToState, newLen)
            isAcceleratedStateOffset = Arrays.copyOf(isAcceleratedStateOffset, newLen)
        }
        offsetToState[s!!.id * numClasses] = s
        isAcceleratedStateOffset[s!!.id * numClasses] = s!!.isStartState || s!!.accelerator != null
        if (s!!.accelerator != null) {
            hasStateAccelerators = true
        }
    }

    private fun stateAtStorage(offset: Int): State = offsetToState[offset]!!

    private fun setTransition(fromId: Int, cls: Int, toId: Int) {
        val fromBase = fromId * numClasses
        val toState = stateAtStorage(toId * numClasses)
        // Negative entries already leave the hot loop for match handling. On DFAs with inner
        // narrowing, also flag failure frontiers so observing failure adds no per-character test.
        val needsAttention =
            toState.isMatch || (innerLiteralSearch != null && toState.isStartState)
        var storedId = toId * numClasses
        if (needsAttention) {
            storedId = -storedId
        }
        transitions[fromBase + cls] = storedId
    }

    private fun pauseFailureTransition(index: Int, offset: Int) {
        if (pausedFailureTransitionCount == pausedFailureTransitions.size) {
            pausedFailureTransitions =
                Arrays.copyOf(pausedFailureTransitions, Math.max(8, pausedFailureTransitionCount * 2))
        }
        pausedFailureTransitions[pausedFailureTransitionCount++] = index
        // This positive entry cannot be queued again before it is restored.
        transitions[index] = offset
        if (WorkCounterConfig.ENABLED) {
            WorkCounter.record()
        }
    }

    private fun restoreFailureTransitions() {
        for (i in 0..<pausedFailureTransitionCount) {
            val index = pausedFailureTransitions[i]
            transitions[index] = -transitions[index]
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
        }
        pausedFailureTransitionCount = 0
    }

    private fun addTransition(from: State, cls: Int, to: State) {
        from.next[cls] = to
        setTransition(from.id, cls, to.id)
    }

    /** Maps a code point (or -1 for end-of-text) to its equivalence class index.  */
    private fun classOf(cp: Int): Int {
        if (cp < 0) {
            return numClasses - 1
        }
        if (cp < 128) {
            return asciiClassMap[cp]
        }
        val cacheIdx = cp and 255
        if (cacheCps[cacheIdx] == cp) {
            return cacheClasses[cacheIdx]
        }
        val idx = Arrays.binarySearch(boundaries, cp)
        var cls = if (idx >= 0) idx else (-idx - 1) - 1
        if (splitUnicodeWordClasses) {
            cls = cls * 2 + (if (Nfa.isUnicodeWordChar(cp)) 1 else 0)
        }
        cacheCps[cacheIdx] = cp
        cacheClasses[cacheIdx] = cls
        return cls
    }

    // ---------------------------------------------------------------------------
    // Subset construction
    // ---------------------------------------------------------------------------
    /**
     * Starting from a set of instruction IDs, follows all empty transitions (ALT, NOP, CAPTURE,
     * EMPTY_WIDTH) and returns the sorted frontier of CHAR_RANGE, MATCH, and unsatisfied EMPTY_WIDTH
     * instruction IDs.
     * 
     * 
     * Unsatisfied EMPTY_WIDTH instructions are retained in the frontier so they can be re-checked
     * when context changes (e.g., at end-of-text, the EndText flag becomes true and `$` can
     * fire).
     */
    private fun expand(seeds: IntArray, seedCount: Int, emptyFlags: Int): IntArray {
        val gen = ++expandGeneration
        val visitedGen = expandVisitedGen
        val stack = expandStack
        val frontier = expandFrontier
        var stackTop = 0
        var frontierSize = 0

        // Push seeds onto stack in reverse priority order.
        for (i in seedCount - 1 downTo 0) {
            stack[stackTop++] = seeds[i]
        }

        val flat = prog.didFlatten()

        while (stackTop > 0) {
            val id = stack[--stackTop]
            if (id == 0 || id >= prog.size || visitedGen[id] == gen) {
                continue
            }
            visitedGen[id] = gen

            val ip = prog.inst(id)

            if (flat) {
                // Flat program list iteration
                if (!ip.last) {
                    stack[stackTop++] = id + 1
                }

                when (ip.opCode) {
                    InstOp.OP_FAIL -> {}
                    InstOp.OP_ALT, InstOp.OP_ALT_MATCH -> {
                        // AltMatch and ALT only transition to id + 1 (already pushed above)
                    }

                    InstOp.OP_NOP, InstOp.OP_CAPTURE, InstOp.OP_LOOP_ENTER -> {
                        stack[stackTop++] = ip.out
                    }

                    InstOp.OP_PROGRESS_CHECK -> {
                        stack[stackTop++] = ip.out1
                        stack[stackTop++] = ip.out
                    }

                    InstOp.OP_EMPTY_WIDTH -> {
                        if ((ip.arg and emptyFlags.inv()) == 0) {
                            stack[stackTop++] = ip.out
                        } else {
                            frontier[frontierSize++] = id
                        }
                    }

                    InstOp.OP_CHAR_RANGE, InstOp.OP_CHAR_CLASS -> frontier[frontierSize++] = id
                    InstOp.OP_MATCH -> {
                        frontier[frontierSize++] = id
                        if (!longest && !prog.anchorEnd()) {
                            stackTop = 0
                        }
                    }

                    else -> {}
                }
            } else {
                // Standard unflattened program traversal
                when (ip.opCode) {
                    InstOp.OP_FAIL -> {}
                    InstOp.OP_ALT, InstOp.OP_ALT_MATCH -> {
                        stack[stackTop++] = ip.out1
                        stack[stackTop++] = ip.out
                    }

                    InstOp.OP_NOP, InstOp.OP_CAPTURE, InstOp.OP_LOOP_ENTER -> {
                        stack[stackTop++] = ip.out
                    }

                    InstOp.OP_PROGRESS_CHECK -> {
                        stack[stackTop++] = ip.out1
                        stack[stackTop++] = ip.out
                    }

                    InstOp.OP_EMPTY_WIDTH -> {
                        if ((ip.arg and emptyFlags.inv()) == 0) {
                            stack[stackTop++] = ip.out
                        } else {
                            frontier[frontierSize++] = id
                        }
                    }

                    InstOp.OP_CHAR_RANGE, InstOp.OP_CHAR_CLASS -> frontier[frontierSize++] = id
                    InstOp.OP_MATCH -> {
                        frontier[frontierSize++] = id
                        if (!longest && !prog.anchorEnd()) {
                            stackTop = 0
                        }
                    }

                    else -> {}
                }
            }
        }

        if (longest) {
            sortSmall(frontier, frontierSize)
        }
        return Arrays.copyOf(frontier, frontierSize)
    }

    /** Returns true if any instruction ID in the sorted array is a MATCH instruction.  */
    private fun hasMatch(insts: IntArray): Boolean {
        for (id in insts) {
            if (prog.inst(id).opCode == InstOp.OP_MATCH) {
                return true
            }
        }
        return false
    }

    /** Returns a new array with all MATCH instructions removed from the input array.  */
    private fun stripMatch(insts: IntArray): IntArray {
        var count = 0
        for (id in insts) {
            if (prog.inst(id).opCode != InstOp.OP_MATCH) {
                count++
            }
        }
        if (count == insts.size) {
            return insts
        }
        val result = IntArray(count)
        var idx = 0
        for (id in insts) {
            if (prog.inst(id).opCode != InstOp.OP_MATCH) {
                result[idx++] = id
            }
        }
        return result
    }

    /** Performs a stable deduplication of the input array, preserving insertion order.  */
    private fun stableDedup(insts: IntArray, count: Int): IntArray {
        var uniqueCount = 0
        var gen = ++expandGeneration
        val visitedGen = expandVisitedGen
        for (i in 0..<count) {
            val id = insts[i]
            if (visitedGen[id] != gen) {
                visitedGen[id] = gen
                uniqueCount++
            }
        }
        if (uniqueCount == count) {
            val result = IntArray(count)
            System.arraycopy(insts, 0, result, 0, count)
            return result
        }
        val result = IntArray(uniqueCount)
        gen = ++expandGeneration
        var idx = 0
        for (i in 0..<count) {
            val id = insts[i]
            if (visitedGen[id] != gen) {
                visitedGen[id] = gen
                result[idx++] = id
            }
        }
        return result
    }

    /** Collects all match IDs (MATCH instruction arg values) from a DFA state's NFA instructions.  */
    private fun collectMatchIds(insts: IntArray): IntArray {
        var count = 0
        for (id in insts) {
            if (prog.inst(id).opCode == InstOp.OP_MATCH) {
                count++
            }
        }
        if (count == 0) {
            return IntArray(0)
        }
        val ids = IntArray(count)
        var idx = 0
        for (id in insts) {
            val ip = prog.inst(id)
            if (ip.opCode == InstOp.OP_MATCH) {
                ids[idx++] = ip.arg
            }
        }
        return ids
    }

    /** Gets or creates a cached state!!. Returns null if the state budget is exceeded.  */
    private fun getOrCreate(insts: IntArray, flags: Int): State? {
        return getOrCreate(insts, flags, null)
    }

    /**
     * Gets or creates a cached state with optional word-boundary match IDs. Returns null if the state
     * budget is exceeded.
     */
    private fun getOrCreate(insts: IntArray, flags: Int, wordBoundaryMatchIds: IntArray?): State? {
        if (insts.size == 0 && (flags and FLAG_MATCH) == 0) {
            return deadState
        }
        lookupKey.reset(insts, flags)
        var s = cache.get(lookupKey)
        if (s != null) {
            return s
        }
        if (cache.size >= maxStates) {
            return null
        }
        val isHighestPriorityMatch =
            insts.size > 0 && prog.inst(insts[0]).opCode == InstOp.OP_MATCH
        val accelerator = analyzeStateAcceleration(insts, flags, isHighestPriorityMatch)
        s =
            State(
                nextStateId++,
                insts,
                flags,
                wordBoundaryMatchIds,
                numClasses,
                isHighestPriorityMatch,
                accelerator
            )
        addStateToFlatArrays(s)
        cache.put(lookupKey.copy(), s)
        return s
    }

    /** Anchors the next scan without crediting input that the DFA has not actually visited.  */
    private fun beginSearch(anchorPos: Int) {
        if (hasProgressAnchor && lastSearchBailed) {
            // The caller may have used the NFA after a bailout, but its exact work is unknown here.
            // Give each fallback one bounded retry credit, never the possibly unscanned gap to the
            // next search position (which may even belong to a different input).
            backoffProgress += 64
            lastSearchBailed = false
        }
        progressAnchor = anchorPos
        hasProgressAnchor = true
    }

    /** Credits only the span reached while the current DFA search was active.  */
    private fun recordSearchProgress(pos: Int) {
        val span = Math.abs(pos.toLong() - progressAnchor)
        dfaStepsSinceReset += span
        backoffProgress += span
        progressAnchor = pos
    }

    /** Records the visited span before returning from any DFA search.  */
    private fun <T> completeSearch(result: T?, pos: Int): T? {
        recordSearchProgress(pos)
        return result
    }

    /** Completes a forward search, publishing its final start-acceleration backoff state!!.  */
    private fun completeForwardSearch(result: SearchResult?, pos: Int, startBackoff: Long): SearchResult? {
        lastStartBackoff = startBackoff
        return completeSearch<SearchResult?>(result, pos)
    }

    /**
     * Creates a search entry state, retrying after a permitted cache reset when the budget is full.
     */
    private fun startStateOrReset(text: InputScanner, pos: Int, anchored: Boolean, reverse: Boolean): State? {
        var s = startState(text, pos, anchored, reverse)
        if (s != null) {
            return s
        }
        if (!resetCache(pos)) {
            return null
        }
        s = startState(text, pos, anchored, reverse)
        return if (s == null) budgetExhaustedAfterReset() else s
    }

    /**
     * Discards every cached state, freeing the whole state budget for the rest of the search.
     * 
     * 
     * Returns false if the current cache generation did not cover [ ][.MIN_PROGRESS_PER_CACHED_STATE] DFA positions per state before filling the budget and the
     * exponential backoff window (`(10 << unproductiveResets) * cache.size`) has not yet
     * elapsed. The caller must then abandon the DFA for the active search and let the NFA finish it.
     * 
     * 
     * A reset invalidates every [State] the caller holds. State ids restart from zero and
     * the flat arrays indexed by `state!!.id * numClasses` are rebuilt from scratch, so any
     * `State` reference, state offset, or local copy of the flat arrays held across the call is
     * stale.
     */
    private fun resetCache(pos: Int): Boolean {
        val delta = Math.abs(pos.toLong() - progressAnchor)
        dfaStepsSinceReset += delta
        backoffProgress += delta
        progressAnchor = pos

        val baseThreshold: Long = MIN_PROGRESS_PER_CACHED_STATE.toLong() * cache.size
        if (dfaStepsSinceReset >= baseThreshold) {
            unproductiveResets = 0
        } else {
            val shifts =
                if (currentGenerationPenalized)
                    unproductiveResets
                else
                    Math.min(unproductiveResets + 1, MAX_RESET_BACKOFF_SHIFT)
            unproductiveResets = shifts
            if (backoffProgress < (baseThreshold shl shifts)) {
                currentGenerationPenalized = true
                lastSearchBailed = true
                return false
            }
        }

        dfaStepsSinceReset = 0
        backoffProgress = 0
        lastSearchBailed = false
        currentGenerationPenalized = false
        val used = Math.min(nextStateId * numClasses, transitions.size)
        Arrays.fill(transitions, 0, used, 0)
        pausedFailureTransitionCount = 0
        pendingFailureTransitionIndex = -1
        Arrays.fill(offsetToState, 0, used, null)
        Arrays.fill(isAcceleratedStateOffset, 0, used, false)
        // The dead state outlives the reset, so clear the transitions recorded on it by hand.
        Arrays.fill(deadState!!.next, null)
        // A surviving start-cache entry would hand back a state the flat arrays no longer describe.
        Arrays.fill(startStateByContext, null)
        if (fastStartStates != null) {
            Arrays.fill(fastStartStates, null)
        }
        cache.clear()
        hasStateAccelerators = false
        nextStateId = 1
        addStateToFlatArrays(deadState)
        return true
    }

    /**
     * Records that a freshly reset cache still could not hold the states the search needs, and
     * returns null so the caller abandons the DFA. This only happens with budgets of a few states; it
     * is still budget exhaustion, so it must set [.lastSearchBailed] like a refused reset.
     */
    private fun budgetExhaustedAfterReset(): State? {
        lastSearchBailed = true
        return null
    }

    /**
     * Returns whether the most recent search on this DFA returned null because the state budget was
     * exhausted: the cache was full and [.resetCache] refused to clear it, or the freshly
     * cleared cache still could not hold the states the search needed.
     * 
     * 
     * Every null result from [.doSearch], [.doSearchReverse], and [ ][.doSearchMany] sets this, which is what lets callers report a null result as [ ][StrategyReason.DFA_BUDGET_EXCEEDED]. A new way for a search to return null for any other reason
     * must not set it, and needs its own [StrategyReason].
     */
    fun lastSearchBailed(): Boolean {
        return lastSearchBailed
    }

    /**
     * Re-creates `s` in a freshly reset cache. The result is a distinct object with a different
     * id, so callers must adopt it in place of `s`.
     */
    private fun recreateAfterReset(s: State): State? {
        val recreated = getOrCreate(s!!.insts, s!!.flags, s!!.wordBoundaryMatchIds)
        if (recreated != null && s!!.isStartState) {
            recreated.isStartState = true
            isAcceleratedStateOffset[recreated.id * numClasses] = true
        }
        return recreated
    }

    /**
     * Computes the transition out of `s` on `cp`, resetting the state cache and retrying
     * once if the state budget is exhausted. The transition is not recorded on `s`, for callers
     * whose destination position makes it unsafe to cache.
     * 
     * 
     * Returns null if the DFA must give up and let the NFA finish the search. Otherwise callers
     * must replace `s` with the returned state and must not reuse anything else derived from
     * it; see [.resetCache].
     */
    private fun computeNextOrReset(s: State, cp: Int, text: InputScanner, nextPos: Int, pos: Int): State? {
        val ns = computeNext(s, cp, text, nextPos)
        if (ns != null) {
            return ns
        }
        if (!resetCache(pos)) {
            return null
        }
        val recreated = recreateAfterReset(s)
        val next = if (recreated == null) null else computeNext(recreated, cp, text, nextPos)
        return if (next == null) budgetExhaustedAfterReset() else next
    }

    /**
     * As [.computeNextOrReset], additionally recording the transition under `cls` so
     * later visits take it straight from the cache. The transition is recorded on whichever state is
     * live after a reset, not on the now-discarded `s`.
     */
    private fun transitionOrReset(
        s: State?, cls: Int, cp: Int, text: InputScanner, nextPos: Int, pos: Int
    ): State? {
        var s = s
        var ns = computeNext(s!!, cp, text, nextPos)
        if (ns == null) {
            if (!resetCache(pos)) {
                return null
            }
            s = recreateAfterReset(s)
            if (s == null) {
                return budgetExhaustedAfterReset()
            }
            ns = computeNext(s, cp, text, nextPos)
            if (ns == null) {
                return budgetExhaustedAfterReset()
            }
        }
        addTransition(s, cls, ns)
        return ns
    }

    private fun analyzeStateAcceleration(
        insts: IntArray, flags: Int, isHighestPriorityMatch: Boolean
    ): StateAccelerator? {
        if (isHighestPriorityMatch || insts.size == 0 || (flags and FLAG_MATCH) != 0) {
            return null
        }
        for (id in insts) {
            val ip = prog.inst(id)
            if (ip.opCode == InstOp.OP_EMPTY_WIDTH || ip.opCode == InstOp.OP_MATCH) {
                return null
            }
        }

        var escapeCount = 0
        val escapes = IntArray(3)
        val seeds = IntArray(insts.size)

        for (ch in 0..127) {
            var seedCount = 0
            for (id in insts) {
                val ip = prog.inst(id)
                if (instMatches(ip, ch)) {
                    seeds[seedCount++] = ip.out
                }
            }
            if (seedCount == 0 || !Arrays.equals(expand(seeds, seedCount, 0), insts)) {
                if (escapeCount < 3) {
                    escapes[escapeCount] = ch
                }
                escapeCount++
                if (escapeCount > 3) {
                    return null
                }
            }
        }

        // The scanner skips directly to an ASCII escape and therefore also skips over non-ASCII code
        // points. Prove that every non-ASCII equivalence class remains in this state before enabling
        // the accelerator. The DFA boundaries make one representative per interval sufficient.
        var i = 0
        while (i + 1 < boundaries.size) {
            val ch = Math.max(128, boundaries[i])
            if (ch >= boundaries[i + 1]) {
                i++
                continue
            }
            var seedCount = 0
            for (id in insts) {
                val ip = prog.inst(id)
                if (instMatches(ip, ch)) {
                    seeds[seedCount++] = ip.out
                }
            }
            if (seedCount == 0 || !Arrays.equals(expand(seeds, seedCount, 0), insts)) {
                return null
            }
            i++
        }

        return when (escapeCount) {
            1 -> SingleAsciiEscape(escapes[0])
            2 -> AsciiPairEscape(escapes[0], escapes[1])
            3 -> AsciiTripleEscape(escapes[0], escapes[1], escapes[2])
            else -> null
        }
    }

    /** Returns whether this DFA was constructed with start-position acceleration enabled.  */
    fun hasStartAcceleration(): Boolean {
        return hasStartAcceleration
    }

    /** Returns whether this DFA can accelerate start positions for the supplied input substrate.  */
    fun hasStartAcceleration(text: InputScanner): Boolean {
        return startAccelerationPolicy(text, null) != null
    }

    private fun startAccelerationPolicy(text: InputScanner, startState: State?): AcceleratorPolicy? {
        val policy =
            when (text) {
                is Utf8InputScanner -> if (utf8StartAccelerator != null) utf8StartAccelerator.policy() else null
                is StringInputScanner -> if (stringStartAccelerator != null) stringStartAccelerator.policy() else null
                is StringRegionInputScanner -> if (stringStartAccelerator != null) stringStartAccelerator.policy() else null
                else -> null
            }
        if (policy != null) {
            return policy
        }
        if (startState != null && startState!!.accelerator != null) {
            return startState!!.accelerator.policy()
        }
        return null
    }

    private fun emptyFlags(text: InputScanner?, pos: Int): Int {
        if (!hasGraphemeSemantics && !prog.hasWordBoundary() && !prog.hasTextAnchor()) {
            return 0
        }
        var flags: Int =
            Nfa.emptyFlags(
                text!!,
                pos,
                hasGraphemeSemantics,
                graphemeContext!!,
                prog.hasWordBoundary(),
                prog.hasTextAnchor()
            )
        if (prog.reversed()) {
            var rev =
                (flags
                        and (EmptyOp.BEGIN_TEXT
                        or EmptyOp.END_TEXT
                        or EmptyOp.BEGIN_LINE
                        or EmptyOp.END_LINE
                        or EmptyOp.UNIX_BEGIN_LINE
                        or EmptyOp.UNIX_END_LINE).inv())
            if ((flags and EmptyOp.BEGIN_TEXT) != 0) {
                rev = rev or EmptyOp.END_TEXT
            }
            if ((flags and EmptyOp.END_TEXT) != 0) {
                rev = rev or EmptyOp.BEGIN_TEXT
            }
            if ((flags and EmptyOp.BEGIN_LINE) != 0) {
                rev = rev or EmptyOp.END_LINE
            }
            if ((flags and EmptyOp.END_LINE) != 0) {
                rev = rev or EmptyOp.BEGIN_LINE
            }
            if ((flags and EmptyOp.UNIX_BEGIN_LINE) != 0) {
                rev = rev or EmptyOp.UNIX_END_LINE
            }
            if ((flags and EmptyOp.UNIX_END_LINE) != 0) {
                rev = rev or EmptyOp.UNIX_BEGIN_LINE
            }
            flags = rev
        }
        return flags
    }

    /**
     * Computes the start state with an explicit "last word" override for reverse searches.
     * 
     * @param reverseContext if true, FLAG_LAST_WORD is set based on the character AT pos (the char to
     * the right of where a reverse scan begins), rather than the character BEFORE pos
     */
    /**
     * Computes the start state for the given position context.
     * 
     * 
     * For unanchored searches, uses `prog.startUnanchored()` which enters the `.*?`
     * prefix loop that the compiler generates. This keeps all start positions alive within the DFA
     * state without needing to restart at each position (unlike the NFA).
     */
    @JvmOverloads
    fun startState(text: InputScanner, pos: Int, anchored: Boolean, reverseContext: Boolean = false): State? {
        if (fastStartStates == null) {
            return computeStartState(text, pos, anchored, reverseContext)
        }
        val index = fastStartIndex(text, pos, anchored, reverseContext)
        var s = fastStartStates[index]
        if (s == null) {
            s = computeStartState(text, pos, anchored, reverseContext)
            fastStartStates[index] = s
        }
        return s
    }

    /**
     * Without text anchors, CRLF context or grapheme semantics, a start state depends only on its
     * direction and anchoring and, for `\b` programs, on whether the characters on either side
     * of `pos` are ASCII and Unicode word characters (see [Nfa.emptyFlags]).
     */
    private fun fastStartIndex(text: InputScanner, pos: Int, anchored: Boolean, reverseContext: Boolean): Int {
        var index = (if (anchored) 1 else 0) or (if (reverseContext) 2 else 0)
        if (fastStartStates!!.size == 4) {
            return index
        }
        if (pos > 0) {
            val cp = text.codePointBefore(pos)
            val word: Boolean = Nfa.isWordChar(cp)
            index =
                index or ((if (word) 4 else 0) or (if (if (cp < 128) word else Nfa.isUnicodeWordChar(cp)) 8 else 0))
        }
        if (pos < text.length) {
            val cp = text.codePointAt(pos)
            val word: Boolean = Nfa.isWordChar(cp)
            index =
                index or ((if (word) 16 else 0) or (if (if (cp < 128) word else Nfa.isUnicodeWordChar(cp)) 32 else 0))
        }
        return index
    }

    private fun computeStartState(
        text: InputScanner, pos: Int, anchored: Boolean, reverseContext: Boolean
    ): State? {
        val startInst = if (anchored) prog.start() else prog.startUnanchored()
        if (startInst == 0) {
            return deadState
        }
        val emptyFlags = emptyFlags(text, pos)

        // Determine word-character context for \b/\B support.
        var lastWord = false
        var lastUnicodeWord = false
        if (prog.hasWordBoundary()) {
            if (reverseContext) {
                if (pos < text.length) {
                    val cp = text.codePointAt(pos)
                    lastWord = Nfa.isWordChar(cp)
                    lastUnicodeWord = if (cp < 128) lastWord else Nfa.isUnicodeWordChar(cp)
                }
            } else {
                if (pos > 0) {
                    val cp = text.codePointBefore(pos)
                    lastWord = Nfa.isWordChar(cp)
                    lastUnicodeWord = if (cp < 128) lastWord else Nfa.isUnicodeWordChar(cp)
                }
            }
        }

        val crLfContext =
            hasCrLfContext
                    && (if (prog.reversed())
                pos < text.length && text.asciiAt(pos) == '\n'.code
            else
                pos > 0 && text.asciiAt(pos - 1) == '\r'.code)

        // Include the adjacent scan character: deferred line assertions distinguish CRLF from
        // standalone CR/LF even when the frontier and all empty-width flags are identical.
        val cacheKey =
            ((if (anchored) anchoredCacheBit else 0)
                    or (if (reverseContext) reverseCacheBit else 0)
                    or (if (crLfContext) crLfCacheBit else 0)
                    or ((emptyFlags and startCacheEmptyFlagsMask) shl 2)
                    or (if (lastWord) 2 else 0)
                    or (if (lastUnicodeWord) 1 else 0))
        // Mix high assertion/context bits into the index instead of indexing the full key space.
        val cacheIndex = (cacheKey * -0x61c88647) ushr 24
        val cached = startStateByContext[cacheIndex]
        if (cached != null && startStateContextKeys[cacheIndex] == cacheKey) {
            return cached
        }

        computeBuf[0] = startInst
        val insts = expand(computeBuf, 1, emptyFlags)
        var flags = emptyFlags and stateEmptyFlagsMask
        if (hasMatch(insts)) {
            flags = flags or FLAG_MATCH
        }
        if (lastWord) {
            flags = flags or FLAG_LAST_WORD
        }
        if (lastUnicodeWord) {
            flags = flags or FLAG_LAST_UNICODE_WORD
        }
        if (crLfContext) {
            flags = flags or FLAG_CRLF_CONTEXT
        }
        val s = getOrCreate(insts, flags)
        if (s != null) {
            // An anchored start can also be an in-progress unanchored frontier. Marking it as
            // idle would allow start acceleration to skip characters belonging to that match.
            if (!anchored && !reverseContext) {
                s!!.isStartState = true
                isAcceleratedStateOffset[s!!.id * numClasses] = true
            }
            startStateContextKeys[cacheIndex] = cacheKey
            startStateByContext[cacheIndex] = s
        }
        return s
    }

    /**
     * Returns the lowest position at which text-length-dependent emptyFlags (END_TEXT or DOLLAR_END)
     * are active. Transitions whose destination is at or beyond this threshold must bypass the `State.next[]` cache, because the cache is keyed by (state, character-class) which is
     * position-independent, but END_TEXT/DOLLAR_END depend on the absolute position relative to
     * `text.length`.
     * 
     * 
     *  * END_TEXT is set at `pos == text.length`.
     *  * DOLLAR_END is set at `pos == text.length` and also before the trailing line
     * terminator at end of text. For `\r\n` this can be up to 2 characters back.
     * 
     * 
     * 
     * For most of the text, transitions are cached normally. Only the last 1–3 character
     * transitions (near end-of-text) bypass the cache. The end-of-text sentinel (`cp < 0`) is
     * always safe to cache because it always represents "at text end".
     */
    private fun positionDependentThreshold(text: InputScanner): Int {
        if (!hasPositionDependentTransitions) {
            // Without position-dependent assertions, trailing line endings cannot affect cached steps.
            // The reverse ASCII fast path can also include those trailing line endings.
            return text.length
        }
        if (hasGraphemeSemantics) {
            return 0
        }
        // BEGIN_TEXT is represented by the start state, and BEGIN_LINE is normally derived from the
        // consumed character. The only position-dependent transitions are therefore near text end:
        // END_TEXT/DOLLAR_END, plus the exception that a final line terminator must not create a
        // BEGIN_LINE assertion past the end of the input.
        // Either scoped mode may occur in an instruction, even if the stripped anchor uses LF only.
        val trailing = text.trailingLineTerminatorStart(false, text.length)
        return if (trailing >= 0) trailing else text.length
    }

    private fun trailingLineStart(text: InputScanner): Int {
        val len = text.length
        if (len == 0) {
            return len
        }
        val trailing = text.trailingLineTerminatorStart(prog.dollarAnchorUnixLines(), len)
        return if (trailing >= 0) trailing else len
    }

    private fun transitionDependsOnPosition(cp: Int, position: Int, threshold: Int): Boolean {
        if (!hasPositionDependentTransitions || cp < 0) {
            return false
        }
        // In the default line-ending mode, BEGIN_LINE after a carriage return depends on whether the
        // following character is a line feed. A transition cached for CRLF therefore cannot be reused
        // for a standalone carriage return, or vice versa.
        return position >= threshold || cp == '\r'.code
    }

    private fun hasSurrogateWordContext(cp: Int): Boolean {
        return cp >= Character.MIN_SURROGATE.code && cp <= Character.MAX_SURROGATE.code && prog.hasWordBoundary()
    }

    /**
     * Computes the next DFA state from the current state for a given code point.
     * 
     * 
     * For each CHAR_RANGE instruction in the current state, checks if it matches the code point.
     * Collects the successor instructions, expands them through empty transitions, and returns the
     * resulting state!!.
     */
    private fun computeNext(s: State, cp: Int, text: InputScanner, nextPos: Int): State? {
        // At end of text, re-expand the current instruction set with end-of-text empty flags.
        // This allows empty-width assertions like $ and \b to fire.
        if (cp < 0) {
            // Compute empty flags for end-of-text, but override word boundary using state context.
            var emptyFlags = emptyFlags(text, nextPos)
            // At end-of-text the "current" character is not a word char.
            val wasWord = (s!!.flags and FLAG_LAST_WORD) != 0
            if (wasWord) {
                emptyFlags = (emptyFlags or EmptyOp.WORD_BOUNDARY) and EmptyOp.NON_WORD_BOUNDARY.inv()
            } else {
                emptyFlags = (emptyFlags or EmptyOp.NON_WORD_BOUNDARY) and EmptyOp.WORD_BOUNDARY.inv()
            }
            val wasUnicodeWord = (s!!.flags and FLAG_LAST_UNICODE_WORD) != 0
            if (wasUnicodeWord) {
                emptyFlags =
                    (emptyFlags or EmptyOp.UNICODE_WORD_BOUNDARY) and EmptyOp.UNICODE_NON_WORD_BOUNDARY.inv()
            } else {
                emptyFlags =
                    (emptyFlags or EmptyOp.UNICODE_NON_WORD_BOUNDARY) and EmptyOp.UNICODE_WORD_BOUNDARY.inv()
            }
            // Re-expand from the successors of EMPTY_WIDTH instructions that now pass.
            var seedCount = 0
            for (id in s!!.insts) {
                val ip = prog.inst(id)
                if (ip.opCode == InstOp.OP_EMPTY_WIDTH && (ip.arg and emptyFlags.inv()) == 0) {
                    computeBuf[seedCount++] = ip.out
                }
            }
            if (seedCount == 0) {
                return deadState
            }
            val nextInsts = expand(computeBuf, seedCount, emptyFlags)
            if (nextInsts.size == 0) {
                return deadState
            }
            var flags = emptyFlags and stateEmptyFlagsMask
            if (hasMatch(nextInsts)) {
                flags = flags or (FLAG_MATCH or FLAG_MATCH_BEFORE)
            }
            // End-of-text is not a word char, so FLAG_LAST_WORD is not set.
            return getOrCreate(nextInsts, flags)
        }

        val pos = if (cp >= 0) nextPos - Character.charCount(cp) else text.length
        // Step 1: Re-evaluate unsatisfied EMPTY_WIDTH instructions that are now satisfiable.
        // Two kinds of assertions fire BEFORE consuming cp and depend on context that can't
        // be predicted when the state was built:
        //
        //   (a) Word boundary (\b, \B): depends on whether cp is a word character, combined
        //       with the previous character (FLAG_LAST_WORD in the state).
        //   (b) END_LINE ($): fires when cp is a line terminator. The DFA caches transitions
        //       per (state, char-class). END_LINE at position P depends on whether text[P] is
        //       a line terminator, but two positions with the same (state, char-class) may have
        //       different characters at P. So END_LINE is deferred and re-expanded here when
        //       the current character is a line terminator.
        //
        // Both are re-expanded before consuming the character so that MATCH instructions
        // reached through these assertions are detected at the correct position.
        var isWord = false
        var isUnicodeWord = false
        var wordBeforeFlags = 0
        var unicodeWordBeforeFlags = 0
        if (prog.hasWordBoundary()) {
            isWord = Nfa.isWordChar(cp)
            val wasWord = (s!!.flags and FLAG_LAST_WORD) != 0
            wordBeforeFlags = if (isWord != wasWord) EmptyOp.WORD_BOUNDARY else EmptyOp.NON_WORD_BOUNDARY
            isUnicodeWord = if (cp < 128) isWord else Nfa.isUnicodeWordChar(cp)
            val wasUnicodeWord = (s!!.flags and FLAG_LAST_UNICODE_WORD) != 0
            unicodeWordBeforeFlags =
                if (isUnicodeWord != wasUnicodeWord)
                    EmptyOp.UNICODE_WORD_BOUNDARY
                else
                    EmptyOp.UNICODE_NON_WORD_BOUNDARY
            if (hasSurrogateWordContext(cp)) {
                // A cursor can start inside a pair. Consumption then sees an unpaired half, but the
                // assertions on either side of it still classify their adjacent character in the input
                // context. The following assertion is recorded in the next state!!. Reverse search that
                // resumes inside a pair also consumes the low half; the assertion at its right edge must
                // see the whole pair, not the half.
                // Only split-pair cursors bypass the cache; truly unpaired halves remain cacheable.
                if (prog.reversed()
                    && Character.isLowSurrogate(cp.toChar())
                    && !text.isCodePointBoundary(nextPos)
                ) {
                    val pairIsUnicodeWord: Boolean = Nfa.isUnicodeWordChar(text.codePointBefore(nextPos + 1))
                    unicodeWordBeforeFlags =
                        if (pairIsUnicodeWord != wasUnicodeWord)
                            EmptyOp.UNICODE_WORD_BOUNDARY
                        else
                            EmptyOp.UNICODE_NON_WORD_BOUNDARY
                }
                val adjacent = if (prog.reversed()) text.codePointAt(nextPos) else text.codePointBefore(nextPos)
                isUnicodeWord = Nfa.isUnicodeWordChar(adjacent)
            }
        }
        var endLineHere: Boolean = Nfa.isLineTerminator(cp)
        if ((s!!.flags and FLAG_CRLF_CONTEXT) != 0 && cp == (if (prog.reversed()) '\r' else '\n').code) {
            endLineHere = false
        }

        // In reverse, END_LINE represents an original BEGIN_LINE assertion: after a CR is a
        // line start only when the character to its right (already scanned) is not LF.
        val nextCrLfFlags =
            if (hasCrLfContext && cp == (if (prog.reversed()) '\n' else '\r').code) FLAG_CRLF_CONTEXT else 0
        var reExpandEmptyFlags =
            (s!!.flags and stateEmptyFlagsMask) or wordBeforeFlags or unicodeWordBeforeFlags
        if (endLineHere) {
            reExpandEmptyFlags = reExpandEmptyFlags or EmptyOp.END_LINE
        }
        if (cp == '\n'.code) {
            reExpandEmptyFlags = reExpandEmptyFlags or EmptyOp.UNIX_END_LINE
        }
        val instsWithoutMatch = stripMatch(s!!.insts)

        // Collect expanded instructions and detect deferred matches in priority order.
        var tempExpanded = IntArray(prog.instructions.size)
        var tempCount = 0
        var hasMatchFromDeferred = false
        var deferredMatchIsPrimary = false
        var deferredMatchIds: IntArray? = null
        var isMatchValid = false

        for (id in instsWithoutMatch) {
            val ip = prog.inst(id)
            if (ip.opCode == InstOp.OP_EMPTY_WIDTH && (ip.arg and reExpandEmptyFlags.inv()) == 0) {
                val expanded = expand(intArrayOf(ip.out), 1, reExpandEmptyFlags)
                if (hasMatch(expanded)) {
                    hasMatchFromDeferred = true
                    deferredMatchIds = collectMatchIds(expanded)
                    deferredMatchIsPrimary = isMatchPrimary(expanded)
                    // Add non-MATCH instructions from this expansion.
                    for (x in expanded) {
                        if (prog.inst(x).opCode != InstOp.OP_MATCH) {
                            if (tempCount == tempExpanded.size) {
                                tempExpanded = Arrays.copyOf(tempExpanded, tempExpanded.size * 2)
                            }
                            tempExpanded[tempCount++] = x
                        }
                    }
                    // Check if this match is valid.
                    if (!prog.anchorEnd()) {
                        isMatchValid = true
                    } else {
                        val textLen = text.length
                        val trailingTermStart = if (prog.dollarAnchorEnd()) trailingLineStart(text) else textLen
                        if (pos == textLen || (trailingTermStart < textLen && pos == trailingTermStart)) {
                            isMatchValid = true
                        }
                    }
                    if (!longest && isMatchValid && !prog.reversed()) {
                        // Only first-match search can discard lower-priority consuming branches.
                        // Longest-match search must retain them because they may extend this match.
                        break
                    }
                } else {
                    for (x in expanded) {
                        if (tempCount == tempExpanded.size) {
                            tempExpanded = Arrays.copyOf(tempExpanded, tempExpanded.size * 2)
                        }
                        tempExpanded[tempCount++] = x
                    }
                }
            } else {
                if (tempCount == tempExpanded.size) {
                    tempExpanded = Arrays.copyOf(tempExpanded, tempExpanded.size * 2)
                }
                tempExpanded[tempCount++] = id
            }
        }
        val expandedInsts = stableDedup(tempExpanded, tempCount)

        // Step 2: Process CHAR_RANGE/CHAR_CLASS transitions against cp.
        var successorCount = 0
        var hasUnanchoredLoopTransition = false
        val unanchoredStart = if (prog.anchorStart()) 0 else prog.startUnanchored()
        for (id in expandedInsts) {
            val ip = prog.inst(id)
            if (ip.opCode == InstOp.OP_CHAR_RANGE && ip.matchesChar(cp)) {
                if (unanchoredStart != 0 && ip.out == unanchoredStart) {
                    hasUnanchoredLoopTransition = true
                } else {
                    computeBuf[successorCount++] = ip.out
                }
            } else if (ip.opCode == InstOp.OP_CHAR_CLASS && ip.matchesCharClass(cp)) {
                if (unanchoredStart != 0 && ip.out == unanchoredStart) {
                    hasUnanchoredLoopTransition = true
                } else {
                    computeBuf[successorCount++] = ip.out
                }
            }
        }
        if (hasUnanchoredLoopTransition && !isMatchValid) {
            computeBuf[successorCount++] = unanchoredStart
        }

        if (successorCount == 0) {
            // No character transition matched, but if deferred assertion expansion revealed a MATCH,
            // return a match state!!. FLAG_MATCH_BEFORE indicates the match position should be
            // recorded at the current position (before consuming cp), not after.
            if (hasMatchFromDeferred) {
                return getOrCreate(
                    EMPTY_INSTS,
                    (FLAG_MATCH
                            or FLAG_MATCH_BEFORE
                            or nextCrLfFlags
                            or (if (isWord) FLAG_LAST_WORD else 0)
                            or (if (isUnicodeWord) FLAG_LAST_UNICODE_WORD else 0)),
                    deferredMatchIds
                )
            }
            return deadState
        }

        // Compute empty flags at nextPos (after consuming cp). Omit deferred flags:
        // word boundary (depends on the next character) and END_LINE (depends on what's at
        // nextPos, not deterministic for cache). Unsatisfied EMPTY_WIDTH instructions will
        // remain in the frontier for re-evaluation when the next character arrives.
        var emptyFlags = emptyFlags(text, nextPos)
        emptyFlags = emptyFlags and
                ((EmptyOp.WORD_BOUNDARY
                        or EmptyOp.NON_WORD_BOUNDARY
                        or EmptyOp.UNICODE_WORD_BOUNDARY
                        or EmptyOp.UNICODE_NON_WORD_BOUNDARY
                        or EmptyOp.END_LINE
                        or EmptyOp.UNIX_END_LINE)).inv()

        val nextInsts = expand(computeBuf, successorCount, emptyFlags)

        if (nextInsts.size == 0) {
            if (hasMatchFromDeferred) {
                return getOrCreate(
                    EMPTY_INSTS,
                    (FLAG_MATCH
                            or FLAG_MATCH_BEFORE
                            or nextCrLfFlags
                            or (if (isWord) FLAG_LAST_WORD else 0)
                            or (if (isUnicodeWord) FLAG_LAST_UNICODE_WORD else 0)),
                    deferredMatchIds
                )
            }
            return deadState
        }

        var flags = (emptyFlags and stateEmptyFlagsMask) or nextCrLfFlags
        if (hasMatchFromDeferred) {
            // A deferred assertion (\b, \B, or multiline $) fired before consuming the current
            // character and reached a MATCH instruction.
            if (deferredMatchIsPrimary) {
                flags = flags or (FLAG_MATCH or FLAG_MATCH_BEFORE)
                if (hasMatch(nextInsts)) {
                    flags = flags or FLAG_MATCH_AFTER_DEFERRED
                }
            } else {
                if (hasMatch(nextInsts)) {
                    flags = flags or (FLAG_MATCH or (if (prog.reversed()) FLAG_MATCH_BEFORE_SHADOWED else 0))
                } else {
                    flags = flags or (FLAG_MATCH or FLAG_MATCH_BEFORE)
                }
            }
        } else if (hasMatch(nextInsts)) {
            flags = flags or FLAG_MATCH
        }
        if (isWord) {
            flags = flags or FLAG_LAST_WORD
        }
        if (isUnicodeWord) {
            flags = flags or FLAG_LAST_UNICODE_WORD
        }
        return getOrCreate(nextInsts, flags, deferredMatchIds)
    }

    /**
     * Main DFA search loop, starting from position 0.
     * 
     * @see .doSearch
     */
    fun doSearch(text: String?, anchored: Boolean, longest: Boolean): SearchResult? {
        return doSearch(StringInputScanner(text!!), 0, anchored, longest)
    }

    fun doSearch(text: String?, startPos: Int, anchored: Boolean, longest: Boolean): SearchResult? {
        return doSearch(StringInputScanner(text!!), startPos, anchored, longest)
    }

    fun doSearch(text: InputScanner, anchored: Boolean, longest: Boolean): SearchResult? {
        return doSearch(text, 0, anchored, longest)
    }

    /**
     * Searches from a position that the caller may already have selected with this DFA's start
     * accelerator.
     * 
     * 
     * A preselected position must still be matched normally because it is only a candidate. The
     * flag suppresses the redundant accelerator call at exactly `startPos`; acceleration
     * remains available after the DFA consumes input and returns to its start state!!.
     */
    fun doSearch(
        text: InputScanner,
        startPos: Int,
        anchored: Boolean,
        longest: Boolean,
        startPositionPreselected: Boolean
    ): SearchResult? {
        return doSearchInternal(
            text, startPos, anchored, longest, startPositionPreselected, AdaptiveBackoff.NEUTRAL
        )
    }

    /**
     * Like [.doSearch], but starts from the
     * start-acceleration backoff state `startBackoff` left by an earlier search of the same
     * input. The updated state is available from [.startBackoff] when this returns.
     */
    fun doSearch(
        text: InputScanner,
        startPos: Int,
        anchored: Boolean,
        longest: Boolean,
        startPositionPreselected: Boolean,
        startBackoff: Long
    ): SearchResult? {
        return doSearchInternal(
            text, startPos, anchored, longest, startPositionPreselected, startBackoff
        )
    }

    /**
     * Returns the start-acceleration backoff state at the end of the most recent forward search.
     * 
     * 
     * Searches that cannot use start acceleration return their initial state unchanged.
     */
    fun startBackoff(): Long {
        return lastStartBackoff
    }

    /**
     * Fast-forwards the start position of unanchored search matching when returning to the start
     * state!!.
     */
    private fun fastForward(
        text: InputScanner,
        pos: Int,
        posDepThreshold: Int,
        startState: State?,
        utf8Cursor: SearchCursor?,
        allowInnerProbe: Boolean
    ): Int {
        val candidate = findStartCandidate(text, pos, posDepThreshold, startState, utf8Cursor)
        if (candidate < 0 || !allowInnerProbe) {
            return candidate
        }
        if (innerProbeState == INNER_PROBE_WAITING) {
            innerProbeResumePos += Math.max(0, candidate - pos).toLong()
            return candidate
        }
        if (innerProbeState != INNER_PROBE_READY) {
            return candidate
        }
        innerProbeState = INNER_PROBE_USED
        val narrowed = innerLiteralSearch!!.searchStart(text, candidate)
        if (narrowed <= candidate) {
            return narrowed
        }
        // Preserve the ordinary accelerator's terminal context boundary. The DFA evaluates
        // position-dependent transitions from there rather than skipping over that boundary.
        if (hasPositionDependentTransitions && narrowed >= posDepThreshold - 1) {
            return Math.max(candidate, posDepThreshold - 1)
        }
        return findStartCandidate(text, narrowed, posDepThreshold, startState, utf8Cursor)
    }

    private fun updateInnerProbeState(pos: Int) {
        if (innerProbeState == INNER_PROBE_WAITING && pos >= innerProbeResumePos) {
            innerProbeState = INNER_PROBE_READY
            innerProbeResumePos = 0
        }
    }

    private fun findStartCandidate(
        text: InputScanner,
        pos: Int,
        posDepThreshold: Int,
        startState: State?,
        utf8Cursor: SearchCursor?
    ): Int {
        if (text is Utf8InputScanner && utf8StartAccelerator != null) {
            val idx =
                if (utf8Cursor != null)
                    utf8Cursor.findCandidate(text, pos)
                else
                    Utf8StartAccelerator.findNextCandidate(utf8StartAccelerator, text, pos)
            return if (idx >= 0) Math.min(idx, posDepThreshold - 1) else -1
        }
        if (text is StringInputScanner && stringStartAccelerator != null) {
            return StringStartAccelerator.findNextCandidate(
                stringStartAccelerator, text.text(), pos, prog.lineStartUnixLines()
            )
        }
        if (text is StringRegionInputScanner && stringStartAccelerator != null) {
            return StringStartAccelerator.findNextCandidate(
                stringStartAccelerator, text, pos, prog.lineStartUnixLines()
            )
        }
        return fastForwardStartState(text, pos, posDepThreshold, startState)
    }

    private fun fastForwardStartState(
        text: InputScanner, pos: Int, posDepThreshold: Int, startState: State?
    ): Int {
        if (startState != null && startState!!.accelerator != null) {
            val limit =
                if (hasPositionDependentTransitions)
                    Math.min(text.length, posDepThreshold - 1)
                else
                    text.length
            val idx: Int = StateAccelerator.findNextEscape(startState!!.accelerator, text, pos, limit)
            if (idx >= 0) {
                return idx
            }
            if (hasPositionDependentTransitions && limit < text.length) {
                return limit
            }
            return -1
        }
        return pos
    }

    /**
     * Main DFA search loop.
     * 
     * 
     * Iterates over each code point in the text starting from `startPos`, following
     * transitions. When a match state is reached, records the position. In earliest-match mode,
     * returns immediately. In longest-match mode, continues to find the longest match.
     * 
     * @param text the full input text
     * @param startPos the char index in `text` at which to begin searching
     * @param anchored whether to anchor the search at `startPos`
     * @param longest whether to find the longest match
     * @return search result with positions relative to `text`, or `null` if the DFA
     * exceeded its state budget
     */
    fun doSearch(text: InputScanner, startPos: Int, anchored: Boolean, longest: Boolean): SearchResult? {
        return doSearchInternal(text, startPos, anchored, longest, false, AdaptiveBackoff.NEUTRAL)
    }

    private fun doSearchInternal(
        text: InputScanner,
        startPos: Int,
        anchored: Boolean,
        longest: Boolean,
        startPositionPreselected: Boolean,
        startBackoff: Long
    ): SearchResult? {
        graphemeContext = GraphemeSupport.Context.create(text, hasGraphemeSemantics)
        beginSearch(startPos)
        var pos = startPos
        val textLen = text.length
        // If the compiled program requires end-of-text matching (stripped $ or \z), enforce it.
        val needEndMatch = prog.anchorEnd()
        val dollarEnd = prog.dollarAnchorEnd()
        // $ allows matching before a trailing line terminator at end of text (JDK default $
        // behavior).
        // Compute the start position of the trailing line terminator for dollarAnchorEnd matching.
        val trailingTermStart = if (dollarEnd) trailingLineStart(text) else textLen

        // Position-dependent flag threshold: emptyFlags at positions >= this threshold contain
        // text-length-dependent flags (END_TEXT at textLen, DOLLAR_END near the end when text ends
        // with a line terminator). Transitions computed at such positions must NOT be cached
        // because the same (state, character-class) pair at a different position (in the same or
        // a different text) would produce a different result. See the class-level comment on
        // DFA caching invariants.
        val posDepThreshold = positionDependentThreshold(text)

        var s = startStateOrReset(text, startPos, anchored, false)
        if (s == null) {
            return completeForwardSearch(null, pos, startBackoff)
        }

        var matched = false
        var matchEnd = -1
        // Check if start state is already a match (e.g., empty pattern or .*? prefix).
        if (s!!.isMatch) {
            if (isRequiredEndMatch(startPos, needEndMatch, textLen, trailingTermStart)) {
                matched = true
                matchEnd = startPos
                if (!longest && canStopAtFirstMatch(s, text, startPos, needEndMatch)) {
                    return completeForwardSearch(SearchResult(true, startPos), pos, startBackoff)
                }
            }
        }

        if (s == deadState) {
            return completeForwardSearch(SearchResult(matched, matchEnd), pos, startBackoff)
        }

        val activePolicy = startAccelerationPolicy(text, s)
        val canAccelerate = activePolicy != null && !anchored
        val utf8Cursor: SearchCursor? =
            if (canAccelerate && text is Utf8InputScanner)
                Utf8StartAccelerator.searchCursor(utf8StartAccelerator)
            else
                null
        val tuning: AcceleratorPolicy = if (canAccelerate) activePolicy else AcceleratorPolicy.DEFAULT
        val minSkip = tuning.minProfitableSkip
        val lossLimit = tuning.strikeBudget * minSkip
        val initialQuarantineWindow = tuning.initialQuarantineWindow
        val maxQuarantineWindow = tuning.maxQuarantineWindow

        // Compare each scan's saved DFA work with that accelerator's minimum profitable skip.
        // Candidate spacing includes work already done by the DFA, especially during quarantine;
        // crediting that work would restore an accelerator that is still unprofitable. Only actual
        // skips in excess of the estimated call cost can repay the deficit and reset quarantine.
        // One strike's allowance is one call's cost: a break-even call cannot erase another call's
        // loss. The deficit stays bounded by the allowance, regardless of the size of the input.
        // The caller may carry the backoff state across searches of the same input (see
        // AdaptiveBackoff), so a sequence of find() calls pays the loss limit once rather than once per
        // search. It is read here once and written back once when the search completes.
        var backoff = startBackoff
        // The first position at which the loops below may call the accelerator, or MAX_VALUE when
        // they never may. Folding canAccelerate in keeps the per-step test to one comparison.
        var accelerationResumePos =
            if (canAccelerate) AdaptiveBackoff.resumePos(backoff) else Integer.MAX_VALUE
        // A preselected start position is never re-scanned; -1 when there is none.
        val preselectedPos = if (startPositionPreselected) startPos else -1
        // A successful first candidate pays no suffix scan or backward probe. Returning to the
        // unanchored start state after consuming input proves that every earlier candidate failed.
        // A small native-work budget avoids probing for isolated short failures. Only ordinary
        // prefix-scan skips move its threshold; input consumed by native matching pays the budget.
        // Probe at most once per search, independently of ordinary prefix-scan quarantine. A failed
        // bounded backward probe falls back to this same DFA pass; it is never retried at each start.
        innerProbeState =
            if (canAccelerate && innerLiteralSearch != null && !longest)
                INNER_PROBE_WAITING
            else
                INNER_PROBE_DISABLED
        innerProbeResumePos = startPos.toLong() + InnerLiteralSearch.MIN_FAILED_INPUT_UNITS
        pendingFailureTransitionIndex = -1
        if (innerProbeState == INNER_PROBE_WAITING && pausedFailureTransitionCount != 0) {
            restoreFailureTransitions()
        }

        var transitions = this.transitions
        var offsetToState = this.offsetToState
        var isAcceleratedStateOffset = this.isAcceleratedStateOffset
        val asciiClassMap = this.asciiClassMap
        // Fast path: loop through ASCII characters (characters < 128)
        while (pos < textLen) {
            updateInnerProbeState(pos)
            if (s!!.isStartState
                && ((pos >= accelerationResumePos && pos != preselectedPos && textLen - pos >= minSkip)
                        || (!matched && innerProbeState == INNER_PROBE_READY))
            ) {
                val nextPos = fastForward(text, pos, posDepThreshold, s, utf8Cursor, !matched)
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.recordStartScan(if (nextPos < 0) textLen - pos else nextPos - pos)
                }
                if (nextPos == -1) {
                    pos = textLen
                    return completeForwardSearch(SearchResult(matched, matchEnd), pos, backoff)
                }
                backoff =
                    AdaptiveBackoff.recordSkip(
                        backoff,
                        nextPos - pos,
                        nextPos,
                        textLen,
                        minSkip,
                        lossLimit,
                        initialQuarantineWindow,
                        maxQuarantineWindow
                    )
                accelerationResumePos = AdaptiveBackoff.resumePos(backoff)
                if (nextPos > pos) {
                    pos = nextPos
                    if (pos >= textLen) {
                        break
                    }
                    s = startStateOrReset(text, pos, anchored, false)
                    if (s == null) {
                        return completeForwardSearch(null, pos, backoff)
                    }
                    // Creating a start state, or resetting the cache, can grow or replace the contents of
                    // the flat arrays, just like computeNext below.
                    transitions = this.transitions
                    offsetToState = this.offsetToState
                    isAcceleratedStateOffset = this.isAcceleratedStateOffset
                    if (s!!.isMatch) {
                        if (isRequiredEndMatch(pos, needEndMatch, textLen, trailingTermStart)) {
                            matched = true
                            matchEnd = pos
                            if (!longest && canStopAtFirstMatch(s, text, pos, needEndMatch)) {
                                return completeForwardSearch(SearchResult(true, pos), pos, backoff)
                            }
                        }
                    }
                    if (s == deadState) {
                        return completeForwardSearch(SearchResult(matched, matchEnd), pos, backoff)
                    }
                }
            }
            if (s!!.accelerator != null && (!s.isStartState || anchored) && (textLen - pos >= 16)) {
                val nextPos: Int = StateAccelerator.findNextEscape(s!!.accelerator, text, pos, textLen)
                if (nextPos == -1) {
                    pos = textLen
                    break
                }
                if (nextPos > pos) {
                    pos = nextPos
                    if (pos >= textLen) {
                        break
                    }
                }
            }
            val breakOnAcceleratedState =
                innerProbeState == INNER_PROBE_READY || pos >= accelerationResumePos || hasStateAccelerators
            var hitAcceleratedState = false
            var limit =
                if (hasPositionDependentTransitions) Math.min(textLen, posDepThreshold - 1) else textLen
            if (innerProbeState == INNER_PROBE_WAITING) {
                // Reuse the scan limit to pay the budget without polling a long threshold in the loop.
                limit = Math.min(limit.toLong(), innerProbeResumePos).toInt()
            }
            var sId = s!!.id * numClasses
            if (breakOnAcceleratedState) {
                while (pos < limit) {
                    val ch = text.asciiAt(pos)
                    if (ch < 0 || transitionDependsOnPosition(ch, pos + 1, posDepThreshold)) {
                        break
                    }
                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.recordForwardDfaStep()
                    }
                    val cls = asciiClassMap[ch]
                    var nsId = transitions[sId + cls]
                    if (nsId == 0) {
                        break
                    }
                    if (nsId < 0) {
                        nsId = -nsId
                        val ns = stateAtStorage(nsId)
                        if (ns.isMatch && !needEndMatch) {
                            val useBefore =
                                (ns.flags and (FLAG_MATCH_BEFORE or FLAG_MATCH_AFTER_DEFERRED)) == FLAG_MATCH_BEFORE
                            val endPos = if (useBefore) pos else pos + 1
                            if (!longest && ns.isHighestPriorityMatch) {
                                return completeForwardSearch(SearchResult(true, endPos), pos, backoff)
                            }
                            matched = true
                            matchEnd = endPos
                        }
                        if (ns.isStartState
                            && (innerProbeState == INNER_PROBE_READY || innerProbeState == INNER_PROBE_USED)
                        ) {
                            if (innerProbeState == INNER_PROBE_USED) {
                                pendingFailureTransitionIndex = sId + cls
                            }
                            sId = nsId
                            pos++
                            hitAcceleratedState = true
                            break
                        }
                    }
                    if (nsId == sId && isAcceleratedStateOffset[sId]) {
                        sId = nsId
                        pos++
                        hitAcceleratedState = true
                        break
                    }
                    sId = nsId
                    pos++
                }
            } else {
                while (pos < limit) {
                    val ch = text.asciiAt(pos)
                    if (ch < 0 || transitionDependsOnPosition(ch, pos + 1, posDepThreshold)) {
                        break
                    }
                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.recordForwardDfaStep()
                    }
                    val cls = asciiClassMap[ch]
                    var nsId = transitions[sId + cls]
                    if (nsId == 0) {
                        break
                    }
                    if (nsId < 0) {
                        nsId = -nsId
                        val ns = stateAtStorage(nsId)
                        if (ns.isMatch && !needEndMatch) {
                            val useBefore =
                                (ns.flags and (FLAG_MATCH_BEFORE or FLAG_MATCH_AFTER_DEFERRED)) == FLAG_MATCH_BEFORE
                            val endPos = if (useBefore) pos else pos + 1
                            if (!longest && ns.isHighestPriorityMatch) {
                                return completeForwardSearch(SearchResult(true, endPos), pos, backoff)
                            }
                            matched = true
                            matchEnd = endPos
                        }
                    }
                    sId = nsId
                    pos++
                }
            }
            s = stateAtStorage(sId)
            if (pendingFailureTransitionIndex >= 0) {
                // Keep all transition-table writes outside the cached scalar loop.
                pauseFailureTransition(pendingFailureTransitionIndex, sId)
                pendingFailureTransitionIndex = -1
            }

            if (pos >= textLen) {
                break
            }
            if (hitAcceleratedState) {
                continue
            }
            if (hasPositionDependentTransitions && pos + 1 >= posDepThreshold) {
                break // fall back to general loop for position-dependent flags
            }

            val ch = text.asciiAt(pos)
            if (ch < 0 || transitionDependsOnPosition(ch, pos + 1, posDepThreshold)) {
                break // fall back to general loop
            }
            val cls = asciiClassMap[ch]
            var ns = s!!.next[cls]
            if (ns == null) {
                val effectiveNextPos = pos + 1
                ns = transitionOrReset(s, cls, ch, text, effectiveNextPos, pos)
                if (ns == null) {
                    return completeForwardSearch(null, pos, backoff) // budget exceeded
                }
                // computeNext can grow the flat arrays and a cache reset replaces their contents. Either
                // way s is no longer usable, but the loop reloads sId from ns on the next iteration.
                transitions = this.transitions
                offsetToState = this.offsetToState
                isAcceleratedStateOffset = this.isAcceleratedStateOffset
            }
            s = ns
            if (s == deadState) {
                break
            }
            if (s!!.isMatch) {
                val useBefore =
                    (s!!.flags and (FLAG_MATCH_BEFORE or FLAG_MATCH_AFTER_DEFERRED)) == FLAG_MATCH_BEFORE
                val endPos = if (useBefore) pos else pos + 1
                if (isRequiredEndMatch(endPos, needEndMatch, textLen, trailingTermStart)) {
                    matched = true
                    matchEnd = endPos
                    if (!longest && canStopAtFirstMatch(s, text, endPos, needEndMatch)) {
                        return completeForwardSearch(SearchResult(true, matchEnd), pos, backoff)
                    }
                }
            }
            pos++
        }

        // General loop handles non-ASCII, position-dependent checks, and trailing end-of-text
        // sentinel
        generalLoop@ while (pos <= textLen) {
            updateInnerProbeState(pos)
            if (s!!.isStartState
                && ((pos >= accelerationResumePos && pos != preselectedPos && textLen - pos >= minSkip)
                        || (!matched && innerProbeState == INNER_PROBE_READY))
            ) {
                val nextPos = fastForward(text, pos, posDepThreshold, s, utf8Cursor, !matched)
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.recordStartScan(if (nextPos < 0) textLen - pos else nextPos - pos)
                }
                if (nextPos == -1) {
                    pos = textLen
                    return completeForwardSearch(SearchResult(matched, matchEnd), pos, backoff)
                }
                backoff =
                    AdaptiveBackoff.recordSkip(
                        backoff,
                        nextPos - pos,
                        nextPos,
                        textLen,
                        minSkip,
                        lossLimit,
                        initialQuarantineWindow,
                        maxQuarantineWindow
                    )
                accelerationResumePos = AdaptiveBackoff.resumePos(backoff)
                if (nextPos > pos) {
                    pos = nextPos
                    if (pos > textLen) {
                        break@generalLoop
                    }
                    s = startStateOrReset(text, pos, anchored, false)
                    if (s == null) {
                        return completeForwardSearch(null, pos, backoff)
                    }
                    if (s!!.isMatch) {
                        if (isRequiredEndMatch(pos, needEndMatch, textLen, trailingTermStart)) {
                            matched = true
                            matchEnd = pos
                            if (!longest && canStopAtFirstMatch(s, text, pos, needEndMatch)) {
                                return completeForwardSearch(SearchResult(true, pos), pos, backoff)
                            }
                        }
                    }
                    if (s == deadState) {
                        return completeForwardSearch(SearchResult(matched, matchEnd), pos, backoff)
                    }
                }
            }
            // Step the DFA until it may call the start accelerator again. Making that test the exit of
            // this inner loop keeps the accelerator path out of the hot per-character loop.
            val stepLimit =
                if (innerProbeState == INNER_PROBE_WAITING) Math.min(Integer.MAX_VALUE.toLong(), innerProbeResumePos)
                    .toInt() else
                    Integer.MAX_VALUE
            do {
                if (s!!.accelerator != null && (textLen - pos >= 16) && (!s.isStartState || anchored)) {
                    val nextPos: Int = StateAccelerator.findNextEscape(s!!.accelerator, text, pos, textLen)
                    if (nextPos == -1) {
                        pos = textLen
                    } else if (nextPos > pos) {
                        pos = nextPos
                        if (pos > textLen) {
                            break@generalLoop
                        }
                    }
                }
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record()
                    WorkCounter.recordForwardDfaStep()
                }
                val cp: Int
                val nextPos: Int
                val cls: Int
                if (pos < textLen) {
                    val singleUnitCodePoint = text.singleUnitCodePointAt(pos)
                    if (singleUnitCodePoint >= 0) {
                        cp = singleUnitCodePoint
                        nextPos = pos + 1
                        cls =
                            if (singleUnitCodePoint < asciiClassMap.size)
                                asciiClassMap[singleUnitCodePoint]
                            else
                                classOf(singleUnitCodePoint)
                    } else {
                        val decoded = text.decodeForward(pos)
                        cp = InputScanner.codePoint(decoded)
                        nextPos = InputScanner.position(decoded)
                        cls = classOf(cp)
                    }
                } else {
                    cp = -1
                    nextPos = textLen + 1
                    cls = numClasses - 1
                }

                // Compute the next state, using the transition cache when safe.
                // Transitions where the destination position has text-length-dependent emptyFlags
                // (END_TEXT, DOLLAR_END) must bypass the cache to preserve the invariant that
                // cached transitions are position-independent. The end-of-text sentinel (cp < 0)
                // is always safe to cache because it always means "at text end".
                val effectiveNextPos = Math.min(nextPos, textLen)
                var ns: State?
                if ((hasSurrogateWordContext(cp) && !text.isCodePointBoundary(pos))
                    || transitionDependsOnPosition(cp, effectiveNextPos, posDepThreshold)
                ) {
                    ns = computeNextOrReset(s, cp, text, effectiveNextPos, pos)
                    if (ns == null) {
                        return completeForwardSearch(null, pos, backoff) // budget exceeded
                    }
                } else {
                    ns = s!!.next[cls]
                    if (ns == null) {
                        ns = transitionOrReset(s, cls, cp, text, effectiveNextPos, pos)
                        if (ns == null) {
                            return completeForwardSearch(null, pos, backoff) // budget exceeded
                        }
                    }
                }

                s = ns

                if (s == deadState) {
                    break@generalLoop
                }

                if (s!!.isMatch) {
                    // FLAG_MATCH_BEFORE indicates a deferred assertion (\b, multiline $) fired before
                    // consuming the current character and reached MATCH. Try the before-consume position
                    // first (it's at an earlier position, preserving leftmost-first semantics).
                    if ((s!!.flags and (FLAG_MATCH_BEFORE or FLAG_MATCH_AFTER_DEFERRED)) == FLAG_MATCH_BEFORE) {
                        val endPos = pos
                        if (isRequiredEndMatch(endPos, needEndMatch, textLen, trailingTermStart)) {
                            matched = true
                            matchEnd = endPos
                            if (!longest && canStopAtFirstMatch(s, text, endPos, needEndMatch)) {
                                return completeForwardSearch(SearchResult(true, matchEnd), pos, backoff)
                            }
                        }
                    }
                    // Try the after-consume position: either no before-consume match exists, or it was
                    // rejected (e.g., needEndMatch but pos != textLen) and an after-consume match also
                    // exists (FLAG_MATCH_AFTER_DEFERRED).
                    if ((s!!.flags and (FLAG_MATCH_BEFORE or FLAG_MATCH_AFTER_DEFERRED)) != FLAG_MATCH_BEFORE) {
                        val endPos = Math.min(nextPos, textLen)
                        if (isRequiredEndMatch(endPos, needEndMatch, textLen, trailingTermStart)) {
                            matched = true
                            matchEnd = endPos
                            if (!longest && canStopAtFirstMatch(s, text, endPos, needEndMatch)) {
                                return completeForwardSearch(SearchResult(true, matchEnd), pos, backoff)
                            }
                        }
                    }
                }

                if (pos >= textLen) {
                    break@generalLoop
                }
                pos = nextPos
            } while (pos < stepLimit
                && (!s.isStartState
                        || (pos < accelerationResumePos && innerProbeState != INNER_PROBE_READY))
            )
        }

        return completeForwardSearch(SearchResult(matched, matchEnd), pos, backoff)
    }

    // ---------------------------------------------------------------------------
    // Reverse search
    // ---------------------------------------------------------------------------
    /**
     * Reverse DFA search: scans backward through text from `endPos` to find match start. Used
     * with a reversed program to find where the leftmost match begins after the forward DFA has
     * determined where it ends.
     * 
     * 
     * This enables a critical optimization: instead of running the expensive NFA/BitState engine
     * on the entire remaining text, we can narrow the search to just `[matchStart, matchEnd]`.
     * 
     * @param text the full input text
     * @param endPos the position to start scanning backward from (exclusive upper bound of the match)
     * @param startLimit the earliest position to scan back to (inclusive), typically 0 or the
     * prefix-acceleration start
     * @param anchored if true, the reverse match must start at `endPos` (meaning the forward
     * match ends exactly there)
     * @param longest if true, find the longest reverse match (earliest start position)
     * @return search result where `pos` is the match start position, or `null` if the DFA
     * exceeded its state budget
     */
    fun doSearchReverse(
        text: String?, endPos: Int, startLimit: Int, anchored: Boolean, longest: Boolean
    ): SearchResult? {
        return doSearchReverse(StringInputScanner(text!!), endPos, startLimit, anchored, longest)
    }

    fun doSearchReverse(
        text: InputScanner, endPos: Int, startLimit: Int, anchored: Boolean, longest: Boolean
    ): SearchResult? {
        return doSearchReverse(text, endPos, startLimit, anchored, longest, Integer.MAX_VALUE)
    }

    /**
     * Reverse search with a speculative input-unit limit. A live frontier at the cutoff is
     * inconclusive, even if it contains a match: an earlier start may exist outside the window. The
     * cutoff never substitutes for `startLimit` in assertion context. Decoding may finish one
     * code point across the cutoff, and assertions retain their normal adjacent context.
     */
    fun doSearchReverse(
        text: InputScanner,
        endPos: Int,
        startLimit: Int,
        anchored: Boolean,
        longest: Boolean,
        maxInputUnits: Int
    ): SearchResult? {
        val scanLimit = Math.max(startLimit, endPos - maxInputUnits)
        graphemeContext = GraphemeSupport.Context.create(text, hasGraphemeSemantics)
        beginSearch(endPos)
        var pos = endPos
        // The reversed program's "start of text" corresponds to endPos (the right edge of the match
        // region), and its "end of text" corresponds to startLimit (the left edge). We scan from
        // endPos backward to startLimit, feeding characters in reverse order.
        val needEndMatch = prog.anchorEnd()

        // Position-dependent threshold: same invariant as doSearch — bypass the transition cache
        // for positions where text-length-dependent emptyFlags (END_TEXT, DOLLAR_END) are active.
        val posDepThreshold = positionDependentThreshold(text)

        // Compute empty flags at the reverse start position (= endPos in the original text).
        var s = startStateOrReset(text, endPos, anchored, true)
        if (s == null) {
            return completeSearch<SearchResult?>(null, pos)
        }

        var matched = false
        var matchStart = -1
        var ambiguous = false

        // Check if start state is already a match (e.g., empty pattern).
        if (s!!.isMatch) {
            if (!needEndMatch || endPos == startLimit) {
                matched = true
                matchStart = endPos
                if (!longest && !needEndMatch) {
                    return completeSearch<SearchResult?>(SearchResult(true, matchStart), pos)
                }
            }
        }

        if (s == deadState) {
            return completeSearch<SearchResult?>(SearchResult(matched, matchStart), pos)
        }

        var transitions = this.transitions
        var offsetToState = this.offsetToState
        val asciiClassMap = this.asciiClassMap
        // Fast path: scan backward through ASCII characters
        while (pos > scanLimit) {
            if (pos <= posDepThreshold) {
                val limit = scanLimit
                var sId = s!!.id * numClasses
                while (pos > limit) {
                    // Probe work includes cached transitions; keep ordinary-search counter behavior stable.
                    if (WorkCounterConfig.ENABLED && maxInputUnits != Integer.MAX_VALUE) {
                        WorkCounter.record()
                    }
                    val ch = text.asciiAt(pos - 1)
                    if (ch < 0 || transitionDependsOnPosition(ch, pos - 1, posDepThreshold)) {
                        break
                    }
                    val cls = asciiClassMap[ch]
                    var nsId = transitions[sId + cls]
                    if (nsId == 0) {
                        break
                    }
                    if (nsId < 0) {
                        nsId = -nsId
                        val ns = stateAtStorage(nsId)
                        if (ns.isMatch) {
                            val flags = ns.flags
                            if ((flags and FLAG_MATCH_BEFORE) != 0) {
                                if (pos >= startLimit && (!needEndMatch || pos == startLimit)) {
                                    val alreadyMatched = matched
                                    matched = true
                                    matchStart = if (longest && alreadyMatched) Math.min(matchStart, pos) else pos
                                    ambiguous = ambiguous or ((flags and FLAG_MATCH_AFTER_DEFERRED) != 0)
                                    if (!longest && !needEndMatch) {
                                        return completeSearch<SearchResult?>(
                                            SearchResult(true, matchStart, ambiguous),
                                            pos
                                        )
                                    }
                                }
                            }
                            val hasOnlyBeforeMatch =
                                (flags and (FLAG_MATCH_BEFORE or FLAG_MATCH_AFTER_DEFERRED)) == FLAG_MATCH_BEFORE
                            val prevPos = pos - 1
                            if (!hasOnlyBeforeMatch) {
                                if (prevPos >= startLimit && (!needEndMatch || prevPos == startLimit)) {
                                    val alreadyMatched = matched
                                    matched = true
                                    matchStart =
                                        if (longest && alreadyMatched) Math.min(matchStart, prevPos) else prevPos
                                    ambiguous = ambiguous or ((flags and FLAG_MATCH_AFTER_DEFERRED) != 0)
                                    if (!longest && !needEndMatch) {
                                        return completeSearch<SearchResult?>(
                                            SearchResult(true, matchStart, ambiguous),
                                            pos
                                        )
                                    }
                                }
                            }
                        }
                    }
                    sId = nsId
                    pos--
                }
                s = stateAtStorage(sId)
            }

            if (pos <= scanLimit) {
                break
            }
            if (hasPositionDependentTransitions && pos - 1 >= posDepThreshold) {
                break // fall back to general loop for position-dependent context
            }

            if (WorkCounterConfig.ENABLED && maxInputUnits != Integer.MAX_VALUE) {
                WorkCounter.record()
            }
            val ch = text.asciiAt(pos - 1)
            if (ch < 0 || transitionDependsOnPosition(ch, pos - 1, posDepThreshold)) {
                break // fall back
            }
            val cls = asciiClassMap[ch]
            var ns = s!!.next[cls]
            if (ns == null) {
                val effectivePrevPos = pos - 1
                ns = transitionOrReset(s, cls, ch, text, effectivePrevPos, pos)
                if (ns == null) {
                    return completeSearch<SearchResult?>(null, pos) // budget exceeded
                }
                transitions = this.transitions
                offsetToState = this.offsetToState
            }
            s = ns
            if (s == deadState) {
                break
            }
            if (s!!.isMatch) {
                if ((s!!.flags and FLAG_MATCH_BEFORE) != 0) {
                    if (pos >= startLimit && (!needEndMatch || pos == startLimit)) {
                        val alreadyMatched = matched
                        matched = true
                        matchStart = if (longest && alreadyMatched) Math.min(matchStart, pos) else pos
                        ambiguous = ambiguous or ((s!!.flags and FLAG_MATCH_AFTER_DEFERRED) != 0)
                        if (!longest && !needEndMatch) {
                            return completeSearch<SearchResult?>(SearchResult(true, matchStart, ambiguous), pos)
                        }
                    }
                }
                val hasOnlyBeforeMatch =
                    (s!!.flags and (FLAG_MATCH_BEFORE or FLAG_MATCH_AFTER_DEFERRED)) == FLAG_MATCH_BEFORE
                val prevPos = pos - 1
                if (!hasOnlyBeforeMatch) {
                    if (prevPos >= startLimit && (!needEndMatch || prevPos == startLimit)) {
                        val alreadyMatched = matched
                        matched = true
                        matchStart = if (longest && alreadyMatched) Math.min(matchStart, prevPos) else prevPos
                        ambiguous = ambiguous or ((s!!.flags and FLAG_MATCH_AFTER_DEFERRED) != 0)
                        if (!longest && !needEndMatch) {
                            return completeSearch<SearchResult?>(SearchResult(true, matchStart, ambiguous), pos)
                        }
                    }
                }
            }
            pos--
        }

        if (s == deadState) {
            return completeSearch<SearchResult?>(SearchResult(matched, matchStart, ambiguous), pos)
        }

        // General reverse loop
        while (pos >= startLimit) {
            if (pos <= scanLimit && pos > startLimit) {
                return completeSearch<SearchResult?>(SearchResult(matched, matchStart, ambiguous, true), pos)
            }
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            var cp: Int
            var prevPos: Int
            val cls: Int
            if (pos > 0) {
                // Read the code point just before pos (scanning backward).
                val singleUnitCodePoint = text.singleUnitCodePointBefore(pos)
                if (singleUnitCodePoint >= 0) {
                    cp = singleUnitCodePoint
                    prevPos = pos - 1
                    cls =
                        if (singleUnitCodePoint < asciiClassMap.size)
                            asciiClassMap[singleUnitCodePoint]
                        else
                            classOf(singleUnitCodePoint)
                } else {
                    val decoded = text.decodeBackward(pos)
                    cp = InputScanner.codePoint(decoded)
                    prevPos = InputScanner.position(decoded)
                    if (pos > startLimit && prevPos < startLimit) {
                        // Starting inside an encoded character leaves a lone UTF-16 low surrogate or UTF-8
                        // continuation bytes. Forward decoding consumes each remaining unit separately, so
                        // reverse decoding must do the same. At pos == startLimit, preserve the preceding
                        // code point instead: it supplies context for leading assertions.
                        prevPos = pos - 1
                        cp = text.codePointAt(prevPos)
                    }
                    cls = classOf(cp)
                }
            } else {
                // Reached the start limit — present end-of-text to the reversed DFA.
                cp = -1
                prevPos = startLimit - 1
                cls = numClasses - 1
            }

            // Bypass cache for position-dependent transitions (same invariant as doSearch).
            val effectivePrevPos = Math.max(prevPos, startLimit)
            var ns: State?
            if ((hasSurrogateWordContext(cp)
                        && !(text.isCodePointBoundary(pos) && text.isCodePointBoundary(effectivePrevPos)))
                || transitionDependsOnPosition(cp, effectivePrevPos, posDepThreshold)
            ) {
                ns = computeNextOrReset(s!!, cp, text, effectivePrevPos, pos)
                if (ns == null) {
                    return completeSearch<SearchResult?>(null, pos) // budget exceeded
                }
            } else {
                ns = s!!.next[cls]
                if (ns == null) {
                    ns = transitionOrReset(s, cls, cp, text, effectivePrevPos, pos)
                    if (ns == null) {
                        return completeSearch<SearchResult?>(null, pos) // budget exceeded
                    }
                }
            }
            s = ns

            if (s == deadState) {
                break
            }

            if (s!!.isMatch) {
                if ((s!!.flags and FLAG_MATCH_BEFORE) != 0) {
                    if (pos >= startLimit && (!needEndMatch || pos == startLimit)) {
                        val alreadyMatched = matched
                        matched = true
                        matchStart = if (longest && alreadyMatched) Math.min(matchStart, pos) else pos
                        ambiguous = ambiguous or ((s!!.flags and FLAG_MATCH_AFTER_DEFERRED) != 0)
                        if (!longest && !needEndMatch) {
                            return completeSearch<SearchResult?>(SearchResult(true, matchStart, ambiguous), pos)
                        }
                    }
                }
                val hasOnlyBeforeMatch =
                    (s!!.flags and (FLAG_MATCH_BEFORE or FLAG_MATCH_AFTER_DEFERRED)) == FLAG_MATCH_BEFORE
                if (!hasOnlyBeforeMatch) {
                    if (prevPos >= startLimit && (!needEndMatch || prevPos == startLimit)) {
                        val alreadyMatched = matched
                        matched = true
                        matchStart = if (longest && alreadyMatched) Math.min(matchStart, prevPos) else prevPos
                        ambiguous = ambiguous or ((s!!.flags and FLAG_MATCH_AFTER_DEFERRED) != 0)
                        if (!longest && !needEndMatch) {
                            return completeSearch<SearchResult?>(SearchResult(true, matchStart, ambiguous), pos)
                        }
                    } else if (pos == startLimit && (s!!.flags and FLAG_MATCH_BEFORE_SHADOWED) != 0) {
                        // The after-consume match starts left of startLimit, but the deferred match still
                        // starts at startLimit, with the preceding character as its assertion context.
                        val alreadyMatched = matched
                        matched = true
                        matchStart = if (longest && alreadyMatched) Math.min(matchStart, pos) else pos
                        if (!longest && !needEndMatch) {
                            return completeSearch<SearchResult?>(SearchResult(true, matchStart, ambiguous), pos)
                        }
                    }
                }
            }

            if (pos <= startLimit) {
                break
            }
            pos = prevPos
        }
        return completeSearch<SearchResult?>(SearchResult(matched, matchStart, ambiguous), pos)
    }

    /**
     * Multi-match DFA search loop. Processes the entire text and collects all match IDs from match
     * states reached along the way.
     */
    fun doSearchMany(text: String?, anchored: Boolean): ManyMatchResult? {
        return doSearchMany(StringInputScanner(text!!), anchored)
    }

    fun doSearchMany(text: InputScanner, anchored: Boolean): ManyMatchResult? {
        graphemeContext = GraphemeSupport.Context.create(text, hasGraphemeSemantics)
        beginSearch(0)
        var pos = 0
        val textLen = text.length
        val needEndMatch = prog.anchorEnd()
        val dollarEnd = prog.dollarAnchorEnd()
        val trailingTermStart = if (dollarEnd) trailingLineStart(text) else textLen

        // Position-dependent threshold: same invariant as doSearch.
        val posDepThreshold = positionDependentThreshold(text)

        var s = startStateOrReset(text, 0, anchored, false)
        if (s == null) {
            return completeSearch<ManyMatchResult?>(null, pos)
        }

        // Use a bitset to track which match IDs have been seen.
        val seen = BitSet()

        // Check if start state is already a match.
        if (s!!.isMatch) {
            if (!needEndMatch || textLen == 0 || (trailingTermStart < textLen && trailingTermStart == 0)) {
                for (id in collectMatchIds(s!!.insts)) {
                    seen.set(id)
                }
            }
        }

        if (s != deadState) {
            var transitions = this.transitions
            var offsetToState = this.offsetToState
            val asciiClassMap = this.asciiClassMap
            // Fast path: scan forward through ASCII characters
            var sId = s!!.id * numClasses
            while (pos < textLen) {
                if (hasPositionDependentTransitions && pos + 1 >= posDepThreshold) {
                    break // fall back to general loop for position-dependent context
                }
                val ch = text.asciiAt(pos)
                if (ch < 0 || transitionDependsOnPosition(ch, pos + 1, posDepThreshold)) {
                    break // fall back
                }
                val cls = asciiClassMap[ch]
                var nsId = transitions[sId + cls]
                if (nsId == 0) {
                    s = stateAtStorage(sId)
                    val effectiveNextPos = pos + 1
                    var ns = computeNext(s, ch, text, effectiveNextPos)
                    if (ns == null) {
                        // This loop is the only one that carries a state offset across iterations, and a
                        // reset renumbers every state, so re-derive sId from the re-created state!!.
                        if (!resetCache(pos)) {
                            return completeSearch<ManyMatchResult?>(null, pos) // budget exceeded
                        }
                        s = recreateAfterReset(s)
                        if (s == null) {
                            budgetExhaustedAfterReset()
                            return completeSearch<ManyMatchResult?>(null, pos) // budget exceeded
                        }
                        sId = s!!.id * numClasses
                        ns = computeNext(s, ch, text, effectiveNextPos)
                        if (ns == null) {
                            budgetExhaustedAfterReset()
                            return completeSearch<ManyMatchResult?>(null, pos) // budget exceeded
                        }
                    }
                    addTransition(s, cls, ns)
                    transitions = this.transitions
                    offsetToState = this.offsetToState
                    nsId = transitions[sId + cls]
                }
                if (nsId == 0) { // deadState
                    break
                }
                val realNsId: Int
                if (nsId < 0) {
                    realNsId = -nsId
                    val ns = stateAtStorage(realNsId)
                    val flags = ns.flags
                    val endPos =
                        if ((flags and (FLAG_MATCH_BEFORE or FLAG_MATCH_AFTER_DEFERRED)) == FLAG_MATCH_BEFORE)
                            pos
                        else
                            pos + 1
                    if (!needEndMatch || endPos == textLen || (trailingTermStart < textLen && endPos == trailingTermStart)) {
                        // Collect match IDs from the state's instructions.
                        for (id in collectMatchIds(ns.insts)) {
                            seen.set(id)
                        }
                        if (ns.wordBoundaryMatchIds != null) {
                            for (id in ns.wordBoundaryMatchIds) {
                                seen.set(id)
                            }
                        }
                    }
                } else {
                    realNsId = nsId
                }
                sId = realNsId
                pos++
            }
            s = stateAtStorage(sId)

            // General loop
            while (pos <= textLen) {
                val cp: Int
                val nextPos: Int
                val cls: Int
                if (pos < textLen) {
                    val singleUnitCodePoint = text.singleUnitCodePointAt(pos)
                    if (singleUnitCodePoint >= 0) {
                        cp = singleUnitCodePoint
                        nextPos = pos + 1
                        cls =
                            if (singleUnitCodePoint < asciiClassMap.size)
                                asciiClassMap[singleUnitCodePoint]
                            else
                                classOf(singleUnitCodePoint)
                    } else {
                        val decoded = text.decodeForward(pos)
                        cp = InputScanner.codePoint(decoded)
                        nextPos = InputScanner.position(decoded)
                        cls = classOf(cp)
                    }
                } else {
                    cp = -1
                    nextPos = textLen + 1
                    cls = numClasses - 1
                }

                // Bypass cache for position-dependent transitions (same invariant as doSearch).
                val effectiveNextPos = Math.min(nextPos, textLen)
                var ns: State?
                if ((hasSurrogateWordContext(cp) && !text.isCodePointBoundary(pos))
                    || transitionDependsOnPosition(cp, effectiveNextPos, posDepThreshold)
                ) {
                    ns = computeNextOrReset(s!!, cp, text, effectiveNextPos, pos)
                    if (ns == null) {
                        return completeSearch<ManyMatchResult?>(null, pos) // budget exceeded
                    }
                } else {
                    ns = s!!.next[cls]
                    if (ns == null) {
                        ns = transitionOrReset(s, cls, cp, text, effectiveNextPos, pos)
                        if (ns == null) {
                            return completeSearch<ManyMatchResult?>(null, pos) // budget exceeded
                        }
                    }
                }
                s = ns

                if (s == deadState) {
                    break
                }

                if (s!!.isMatch) {
                    val endPos =
                        if ((s!!.flags and (FLAG_MATCH_BEFORE or FLAG_MATCH_AFTER_DEFERRED)) == FLAG_MATCH_BEFORE)
                            pos
                        else
                            Math.min(nextPos, textLen)
                    if (!needEndMatch || endPos == textLen || (trailingTermStart < textLen && endPos == trailingTermStart)) {
                        // Collect match IDs from the state's instructions.
                        for (id in collectMatchIds(s!!.insts)) {
                            seen.set(id)
                        }
                        // Also collect match IDs from word-boundary expansion (if any).
                        if (s!!.wordBoundaryMatchIds != null) {
                            for (id in s!!.wordBoundaryMatchIds) {
                                seen.set(id)
                            }
                        }
                    }
                }

                if (pos >= textLen) {
                    break
                }
                pos = nextPos
            }
        }

        val matched = !seen.isEmpty
        val matchIds = seen.stream().toArray()
        return completeSearch<ManyMatchResult?>(ManyMatchResult(matched, matchIds), pos)
    }

    private fun isMatchPrimary(expanded: IntArray): Boolean {
        var firstMatch = -1
        var firstActive = -1
        for (i in expanded.indices) {
            val op = prog.inst(expanded[i]).opCode
            if (op == InstOp.OP_MATCH) {
                if (firstMatch == -1) {
                    firstMatch = i
                }
            } else if (op == InstOp.OP_CHAR_RANGE || op == InstOp.OP_CHAR_CLASS) {
                if (firstActive == -1) {
                    firstActive = i
                }
            }
        }
        if (firstMatch == -1) {
            return false
        }
        if (firstActive == -1) {
            return true
        }
        return firstMatch < firstActive
    }

    private fun canStopAtFirstMatch(s: State, text: InputScanner, pos: Int, needEndMatch: Boolean): Boolean {
        if (!needEndMatch) {
            return s!!.isHighestPriorityMatch
        }
        val cp = codePointAt(text, pos)
        for (id in s!!.insts) {
            val inst = prog.inst(id)
            when (inst.opCode) {
                InstOp.OP_MATCH -> {
                    return true
                }

                InstOp.OP_CHAR_RANGE -> {
                    if (cp >= 0 && inst.matchesChar(cp)) {
                        return false
                    }
                }

                InstOp.OP_CHAR_CLASS -> {
                    if (cp >= 0 && inst.matchesCharClass(cp)) {
                        return false
                    }
                }

                else -> {}
            }
        }
        return false
    }


    companion object {
        /** Flag bit: this state contains a MATCH instruction.  */
        private val FLAG_MATCH = 1 shl 14

        /** Flag bit: last consumed character was a word character (for `\b`/\B}).  */
        private val FLAG_LAST_WORD = 1 shl 15

        /**
         * Flag bit: match was triggered by a word-boundary assertion BEFORE consuming the transition
         * character. The match position should be recorded at the current position, not after the
         * character.
         */
        private val FLAG_MATCH_BEFORE = 1 shl 16

        /**
         * Flag bit: when [.FLAG_MATCH_BEFORE] is set, indicates that an after-consume match ALSO
         * exists (from character transitions reaching MATCH). The search loop should try the
         * before-consume match first (earlier position) and fall back to the after-consume match if the
         * before-consume match is rejected (e.g., by `needEndMatch` requiring end-of-text).
         */
        private val FLAG_MATCH_AFTER_DEFERRED = 1 shl 17

        /**
         * Flag bit: last consumed character was a Unicode word character (for Unicode `\b`/\B}).
         */
        private val FLAG_LAST_UNICODE_WORD = 1 shl 18

        /** Previous scan character was CR forward or LF backward, for atomic CRLF assertions.  */
        private val FLAG_CRLF_CONTEXT = 1 shl 19

        /**
         * Flag bit, reversed programs only: a deferred assertion also reached MATCH before the transition
         * character, but a higher-priority branch consuming that character reaches MATCH too, so [ ][.FLAG_MATCH_BEFORE] is not set. A longest reverse search prefers the after-consume match (an
         * earlier start). In the final iteration at `startLimit`, though, the consumed character
         * lies left of `startLimit` and only supplies assertion context, so the search must record
         * the before-consume match at `startLimit` instead.
         */
        private val FLAG_MATCH_BEFORE_SHADOWED = 1 shl 20

        /** Maximum number of DFA states before bailing out to NFA.  */
        private const val DEFAULT_MAX_STATES = 10000

        /**
         * Input positions of progress per cached state that this DFA must have covered before the state
         * cache may be discarded again.
         * 
         * 
         * RE2 has the same guard and supplies the cost model: recomputing a DFA state on every input
         * byte runs at roughly 0.2 MB/s where the NFA runs at roughly 2 MB/s, so ten input positions per
         * state computation is where a DFA that is rebuilding its cache stops accelerating anything.
         * 
         * 
         * Measurement on `[A-Za-z]{10}\s+[\s\S]{0,100}Result[\s\S]{0,100}\s+[A-Za-z]{10}` puts
         * SafeRE's break-even in the same place. Over 7.4 MB of source text the DFA sustains 22 to 64
         * positions per state computation and beats the NFA by 3.0x, and over a 1 MB haystack that holds
         * it to one position per state, discarding and rebuilding is 2.0x slower than the NFA.
         */
        private const val MIN_PROGRESS_PER_CACHED_STATE = 10

        /** Shared empty instruction array to avoid repeated zero-length allocations.  */
        private val EMPTY_INSTS = IntArray(0)

        // Per-search narrowing state!!. Kept out of the scalar loop's live local variables; this DFA
        // is thread-confined and no search calls another search before it returns.
        private const val INNER_PROBE_DISABLED = 0
        private const val INNER_PROBE_WAITING = 1
        private const val INNER_PROBE_READY = 2
        private const val INNER_PROBE_USED = 3

        private const val MAX_RESET_BACKOFF_SHIFT = 20

        /**
         * Builds a reusable [Setup] from a compiled program. The result is immutable and can be
         * stored on a [Pattern] for sharing across matchers.
         */
        fun buildSetup(prog: Prog): Setup {
            val boundaries: IntArray = buildBoundaries(prog)
            var splitUnicodeWordClasses = false
            for (i in 0..<prog.size) {
                val inst = prog.inst(i)
                if (inst.opCode == InstOp.OP_EMPTY_WIDTH
                    && ((inst.arg and (EmptyOp.UNICODE_WORD_BOUNDARY or EmptyOp.UNICODE_NON_WORD_BOUNDARY))
                            != 0)
                ) {
                    splitUnicodeWordClasses = true
                    break
                }
            }
            // Unicode word membership is a separate discriminator, avoiding a transition column for
            // every Unicode word range. Each original interval needs at most two columns, and EOF
            // retains its own column. Classification must agree with computeNext's word predicate.
            val numClasses: Int = (boundaries.size + 1) * (if (splitUnicodeWordClasses) 2 else 1) + 1
            val asciiClassMap: IntArray = buildAsciiClassMap(boundaries)
            if (splitUnicodeWordClasses) {
                for (cp in asciiClassMap.indices) {
                    asciiClassMap[cp] = asciiClassMap[cp] * 2 + (if (Nfa.isWordChar(cp)) 1 else 0)
                }
            }
            return Setup(boundaries, numClasses, asciiClassMap, splitUnicodeWordClasses)
        }

        /**
         * Collects all code point range boundaries from the program's CHAR_RANGE instructions. The
         * boundaries define equivalence classes: code points within the same interval between consecutive
         * boundaries have identical consuming-instruction behavior. Unicode word-boundary programs
         * additionally split these intervals by word membership in [.buildSetup].
         * 
         * 
         * When the program contains word-boundary assertions (`\b` or `\B`), additional
         * boundaries are added at the edges of the word-character ranges (`[A-Za-z0-9_]`) so that
         * no equivalence class straddles the ASCII word/non-word boundary. This is necessary because the
         * DFA caches transitions per (state, class) and the word-boundary computation depends on whether
         * the current character is a word character.
         */
        private fun buildBoundaries(prog: Prog): IntArray {
            val bounds = IntArrayList()
            bounds.add(0)
            bounds.add(Utils.MAX_RUNE + 1)
            var hasWordBoundary = false
            var hasLineBoundary = false
            for (i in 0..<prog.size) {
                val inst = prog.inst(i)
                if (inst.opCode == InstOp.OP_CHAR_RANGE) {
                    addRangeBoundaries(bounds, inst.lo, inst.hi)
                    if (inst.foldCase) {
                        addCaseFoldBoundaries(bounds, inst.lo, inst.hi)
                    }
                } else if (inst.opCode == InstOp.OP_CHAR_CLASS) {
                    var j = 0
                    while (j < inst.ranges.size) {
                        bounds.add(inst.ranges[j])
                        if (inst.ranges[j + 1] < Utils.MAX_RUNE) {
                            bounds.add(inst.ranges[j + 1] + 1)
                        }
                        j += 2
                    }
                } else if (inst.opCode == InstOp.OP_EMPTY_WIDTH) {
                    if ((inst.arg and (EmptyOp.WORD_BOUNDARY or EmptyOp.NON_WORD_BOUNDARY)) != 0) {
                        hasWordBoundary = true
                    }
                    if ((inst.arg and (EmptyOp.UNICODE_WORD_BOUNDARY or EmptyOp.UNICODE_NON_WORD_BOUNDARY)) != 0) {
                        hasWordBoundary = true
                    }
                    if ((inst.arg
                                and (EmptyOp.BEGIN_LINE
                                or EmptyOp.END_LINE
                                or EmptyOp.UNIX_BEGIN_LINE
                                or EmptyOp.UNIX_END_LINE))
                        != 0
                    ) {
                        hasLineBoundary = true
                    }
                }
            }
            if (hasLineBoundary) {
                // Line terminator characters trigger BEGIN_LINE (at the position after them) and
                // END_LINE (at their position). Give each its own equivalence class so the DFA can
                // cache different transitions for line terminators vs other characters.
                bounds.add(0x0A) // '\n'
                bounds.add(0x0B) // '\n' + 1
                bounds.add(0x0D) // '\r'
                bounds.add(0x0E) // '\r' + 1
                // Additional JDK line terminators: \u0085, \u2028, \u2029
                bounds.add(0x0085) // NEXT LINE
                bounds.add(0x0086) // NEXT LINE + 1
                bounds.add(0x2028) // LINE SEPARATOR
                bounds.add(0x202A) // PARAGRAPH SEPARATOR + 1 (covers both \u2028 and \u2029)
            }
            if (hasWordBoundary) {
                // Add boundaries at the edges of word-character ranges [0-9A-Za-z_].
                bounds.add(0x30) // '0'
                bounds.add(0x3A) // '9' + 1
                bounds.add(0x41) // 'A'
                bounds.add(0x5B) // 'Z' + 1
                bounds.add(0x5F) // '_'
                bounds.add(0x60) // '_' + 1
                bounds.add(0x61) // 'a'
                bounds.add(0x7B) // 'z' + 1
            }
            return bounds.toSortedUniqueArray()
        }

        private fun hasStandardLineBoundary(prog: Prog): Boolean {
            for (i in 0..<prog.size) {
                val inst = prog.inst(i)
                if (inst.opCode == InstOp.OP_EMPTY_WIDTH
                    && (inst.arg and (EmptyOp.BEGIN_LINE or EmptyOp.END_LINE)) != 0
                ) {
                    return true
                }
            }
            return false
        }

        /** Adds boundaries for a [lo, hi] code point range.  */
        private fun addRangeBoundaries(bounds: IntArrayList, lo: Int, hi: Int) {
            bounds.add(lo)
            if (hi < Utils.MAX_RUNE) {
                bounds.add(hi + 1)
            }
        }

        /**
         * Adds boundaries for all case-fold equivalents of each code point in [lo, hi]. When a CHAR_RANGE
         * instruction has the fold-case flag, characters outside [lo, hi] that fold into the range must
         * have their own equivalence classes so the DFA doesn't conflate them with non-matching
         * characters in the same class.
         */
        private fun addCaseFoldBoundaries(bounds: IntArrayList, lo: Int, hi: Int) {
            for (cp in lo..hi) {
                var folded: Int = Inst.simpleFold(cp)
                while (folded != cp) {
                    addRangeBoundaries(bounds, folded, folded)
                    folded = Inst.simpleFold(folded)
                }
            }
        }

        /**
         * Builds a 128-element lookup table mapping ASCII code points (0–127) to their equivalence class
         * indices. This avoids binary search for the most common characters.
         */
        private fun buildAsciiClassMap(boundaries: IntArray): IntArray {
            val map = IntArray(128)
            for (cp in 0..127) {
                val idx = Arrays.binarySearch(boundaries, cp)
                map[cp] = if (idx >= 0) idx else (-idx - 1) - 1
            }
            return map
        }

        /** Sorts DFA frontiers, keeping insertion sort only for genuinely small arrays.  */
        private fun sortSmall(a: IntArray, len: Int) {
            if (len > 32) {
                Arrays.sort(a, 0, len)
                return
            }
            for (i in 1..<len) {
                val key = a[i]
                var j = i - 1
                while (j >= 0 && a[j] > key) {
                    a[j + 1] = a[j]
                    j--
                }
                a[j + 1] = key
            }
        }

        private fun instMatches(ip: Inst, ch: Int): Boolean {
            if (ip.opCode == InstOp.OP_CHAR_RANGE || ip.opCode == InstOp.OP_ALT_MATCH) {
                return ip.matchesChar(ch)
            }
            if (ip.opCode == InstOp.OP_CHAR_CLASS) {
                return ip.matchesCharClass(ch)
            }
            return false
        }

        // ---------------------------------------------------------------------------
        // Search
        // ---------------------------------------------------------------------------
        /**
         * Searches for a match using the DFA, starting from position 0.
         * 
         * @param prog the compiled program
         * @param text the input text
         * @param anchored whether to anchor the search at the start
         * @param longest whether to find the longest match (vs. earliest/first match)
         * @return search result, or `null` if the DFA exceeded its state budget (caller should fall
         * back to NFA)
         */
        fun search(prog: Prog, text: String?, anchored: Boolean, longest: Boolean): SearchResult? {
            return search(prog, StringInputScanner(text!!), 0, anchored, longest, DEFAULT_MAX_STATES)
        }

        /** Search with explicit state budget, starting from position 0.  */
        fun search(
            prog: Prog, text: String?, anchored: Boolean, longest: Boolean, maxStates: Int
        ): SearchResult? {
            return search(prog, StringInputScanner(text!!), 0, anchored, longest, maxStates)
        }

        /**
         * Search with explicit start position and state budget.
         * 
         * @param prog the compiled program
         * @param text the input text
         * @param startPos the char index in `text` at which to begin searching
         * @param anchored whether to anchor the search at `startPos`
         * @param longest whether to find the longest match (vs. earliest/first match)
         * @param maxStates maximum DFA state budget
         * @return search result, or `null` if the DFA exceeded its state budget
         */
        fun search(
            prog: Prog, text: String?, startPos: Int, anchored: Boolean, longest: Boolean, maxStates: Int
        ): SearchResult? {
            return search(prog, StringInputScanner(text!!), startPos, anchored, longest, maxStates)
        }

        fun search(prog: Prog, text: InputScanner, anchored: Boolean, longest: Boolean): SearchResult? {
            return search(prog, text, 0, anchored, longest, DEFAULT_MAX_STATES)
        }

        fun search(
            prog: Prog, text: InputScanner, anchored: Boolean, longest: Boolean, maxStates: Int
        ): SearchResult? {
            return search(prog, text, 0, anchored, longest, maxStates)
        }

        fun search(
            prog: Prog,
            text: InputScanner,
            startPos: Int,
            anchored: Boolean,
            longest: Boolean,
            maxStates: Int
        ): SearchResult? {
            val dfa = Dfa(prog, maxStates, buildSetup(prog), longest)
            return dfa.doSearch(text, startPos, anchored, longest)
        }

        /** Multi-match search with explicit state budget.  */ // ---------------------------------------------------------------------------
        // Multi-match search (for PatternSet)
        // ---------------------------------------------------------------------------
        /**
         * Searches for all matching patterns in a multi-pattern program.
         * 
         * 
         * Unlike [.search], this method does not stop at the first match. It processes the
         * entire text and collects the match IDs (from [Inst.arg]) of all MATCH instructions
         * reached. This is used by [PatternSet] to determine which patterns matched!!.
         * 
         * @param prog the compiled multi-pattern program (built with HAVE_MATCH markers)
         * @param text the input text
         * @param anchored whether to anchor the search at the start
         * @return multi-match result, or `null` if the DFA exceeded its state budget
         */
        @JvmOverloads
        fun searchMany(
            prog: Prog,
            text: String?,
            anchored: Boolean,
            maxStates: Int = DEFAULT_MAX_STATES
        ): ManyMatchResult? {
            val dfa = Dfa(prog, maxStates, buildSetup(prog), true)
            return dfa.doSearchMany(text, anchored)
        }

        private fun codePointAt(text: InputScanner, pos: Int): Int {
            if (pos >= text.length) {
                return -1
            }
            return text.codePointAt(pos)
        }

        private fun isRequiredEndMatch(
            pos: Int, needEndMatch: Boolean, textLen: Int, trailingTermStart: Int
        ): Boolean {
            return !needEndMatch || pos == textLen || (trailingTermStart < textLen && pos == trailingTermStart)
        }
    }
}
