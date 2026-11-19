// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.safere.Pattern.*
import java.util.*
import java.util.Spliterators.AbstractSpliterator
import java.util.function.Consumer
import java.util.function.Function
import java.util.regex.MatchResult
import java.util.stream.Stream
import java.util.stream.StreamSupport
import kotlin.concurrent.Volatile

/**
 * An engine that performs match operations on a [character sequence][CharSequence] by
 * interpreting a [Pattern]. This class is a drop-in replacement for [ ] backed by a linear-time matching engine.
 * 
 * 
 * Matching uses a two-phase engine cascade: the DFA quickly determines whether a match exists
 * (and where it ends), then the NFA extracts capture group positions. If the DFA exceeds its state
 * budget, the NFA handles the entire search.
 * 
 * 
 * A matcher is created from a pattern by invoking the pattern's [matcher][Pattern.matcher]
 * method. Once created, a matcher can be used to perform three different kinds of match operations:
 * 
 * 
 *  * The [matches][.matches] method attempts to match the entire input sequence against the
 * pattern.
 *  * The [lookingAt][.lookingAt] method attempts to match the input sequence, starting at
 * the beginning, against the pattern.
 *  * The [find][.find] method scans the input sequence looking for the next subsequence
 * that matches the pattern.
 * 
 */
class Matcher : MatchResult {
    private enum class ResultStatus {
        RESET_NO_ATTEMPT,
        MATCHED,
        FAILED
    }

    private lateinit var parentPattern: Pattern
    private var inputSequence: CharSequence? = null
    private var text: String? = null
    private var textScanner: InputScanner? = null

    // Reuse the independent region input across successive searches and repeated region resets.
    private var cachedRegionScanner: InputScanner? = null
    private var regionClassSearch: RegionClassSearch? = null
    private var cachedRegionStart = 0
    private var cachedRegionEnd = 0
    private lateinit var groups: IntArray
    private var matchOffsets: IntArray? = null
    private var hasMatch = false
    private var resultStatus = ResultStatus.RESET_NO_ATTEMPT
    private var searchFrom = 0

    // Retained across failed attempts; unlike searchFrom, excludes empty-match advancement.
    private var previousMatchEnd = 0
    private var appendPos = 0
    private var transparentBounds = false
    private var anchoringBounds = true
    private var regionStart = 0
    private var regionEnd: Int = 0
    private var fullTextRegionContext = false
    private var findExhaustedAfterTerminalEmptyMatch = false
    private var modCount = 0
    private var diagnosticOperation: DiagnosticOperation? = null
    private var diagnosticCaptureSearch = false

    @JvmRecord
    private data class DiagnosticOperation(
        val listener: SafeReMatchDiagnostics,
        val operation: MatchOperation?,
        val accumulator: DiagnosticAccumulator
    )

    private fun beginDiagnostics(operation: MatchOperation?): DiagnosticOperation? {
        if (diagnosticOperation != null) {
            return null
        }
        val listener: SafeReMatchDiagnostics = Pattern.diagnostics()
        if (!SafeReMatchDiagnostics.isEnabled(listener)) {
            return null
        }
        val started =
            DiagnosticOperation(listener, operation, DiagnosticAccumulator())
        diagnosticOperation = started
        return started
    }

    private fun abortDiagnostics(operation: DiagnosticOperation?) {
        if (operation != null) {
            diagnosticOperation = null
        }
    }

    private fun completeDiagnostics(operation: DiagnosticOperation?, matchCount: Int) {
        if (operation == null) {
            return
        }
        val accumulator = operation.accumulator
        accumulator.matchCount(matchCount)
        val outcome = if (matchCount == 0) MatchOutcome.NO_MATCH else MatchOutcome.MATCH
        val captureMode: CaptureMode?
        if (matchCount == 0 || parentPattern.numGroups() == 0) {
            captureMode = CaptureMode.NONE
        } else {
            captureMode = if (accumulator.captured()) CaptureMode.EAGER else CaptureMode.DEFERRED
        }
        val event =
            accumulator.toEvent(
                parentPattern.descriptor(),
                operation.operation,
                outcome,
                captureMode,
                this.textLength
            )
        diagnosticOperation = null
        accumulator.reportIntentionalDivergences(operation.listener)
        operation.listener.onOperationCompleted(event)
    }

    private fun diagnosticsAccumulator(): DiagnosticAccumulator? {
        return if (diagnosticOperation == null) null else diagnosticOperation!!.accumulator
    }

    private fun diagnosticIntentionalDivergence(divergence: IntentionalDivergence?) {
        val accumulator = diagnosticsAccumulator()
        if (accumulator != null) {
            accumulator.intentionalDivergence(divergence)
        }
    }

    private fun diagnosticBoundary(strategy: MatchStrategy) {
        val accumulator = diagnosticsAccumulator()
        if (accumulator != null) {
            accumulator.boundary(strategy)
        }
    }

    private fun diagnosticExact(strategy: MatchStrategy, resolvedCaptures: Boolean) {
        val accumulator = diagnosticsAccumulator()
        if (accumulator != null) {
            if (diagnosticCaptureSearch) {
                accumulator.capture(strategy)
            } else {
                accumulator.boundary(strategy)
                if (resolvedCaptures) {
                    accumulator.capture(strategy)
                }
            }
        }
    }

    private fun diagnosticCapture(strategy: MatchStrategy) {
        val accumulator = diagnosticsAccumulator()
        if (accumulator != null) {
            accumulator.capture(strategy)
        }
    }

    private fun diagnosticParticipation(strategy: MatchStrategy, role: StrategyRole) {
        val accumulator = diagnosticsAccumulator()
        if (accumulator != null) {
            accumulator.participate(strategy, role)
        }
    }

    private fun diagnosticDecision(
        strategy: MatchStrategy, disposition: StrategyDisposition, reason: StrategyReason
    ) {
        val accumulator = diagnosticsAccumulator()
        if (accumulator != null) {
            accumulator.decision(strategy, disposition, reason)
        }
    }

    private fun rejectWithDiagnostics(
        rejectPrefilter: RejectPrefilter,
        text: String?,
        scanner: InputScanner?,
        searchFrom: Int,
        options: EnginePathOptions
    ): Boolean {
        return reportRejection(rejectPrefilter.rejectionStrategy(text, scanner, searchFrom, options))
    }

    private fun reportRejection(rejectionStrategy: MatchStrategy?): Boolean {
        if (rejectionStrategy == null) {
            return false
        }
        diagnosticParticipation(rejectionStrategy, StrategyRole.REJECT_PREFILTER)
        diagnosticBoundary(rejectionStrategy)
        return true
    }

    private fun searchForwardDfa(
        dfa: Dfa, scanner: InputScanner, anchored: Boolean, longest: Boolean
    ): Dfa.SearchResult? {
        return searchForwardDfa(dfa, scanner, 0, anchored, longest)
    }

    private fun searchForwardDfa(
        dfa: Dfa,
        scanner: InputScanner,
        startPos: Int,
        anchored: Boolean,
        longest: Boolean,
        startPositionPreselected: Boolean = false
    ): Dfa.SearchResult? {
        val result =
            forwardDfaSearch(dfa, scanner, startPos, anchored, longest, startPositionPreselected)
        val activeDiagnostics = diagnosticOperation
        if (activeDiagnostics != null) {
            activeDiagnostics.accumulator.incrementForwardDfaSearchCount()
            diagnosticDfaBudget(result)
        }
        return result
    }

    /** Runs a forward DFA search that continues from, and updates, [.startBackoff].  */
    private fun forwardDfaSearch(
        dfa: Dfa,
        scanner: InputScanner,
        startPos: Int,
        anchored: Boolean,
        longest: Boolean,
        startPositionPreselected: Boolean
    ): Dfa.SearchResult? {
        val result =
            dfa.doSearch(scanner, startPos, anchored, longest, startPositionPreselected, startBackoff)
        startBackoff = dfa.startBackoff()
        return result
    }

    /**
     * Returns whether [.startBackoff] allows a matcher-level start-accelerator call at `pos`.
     * 
     * 
     * An accelerator whose candidates are exact match starts is always allowed: even when it skips
     * nothing, its candidate spares the reverse DFA pass that would otherwise find the match start,
     * and the skip distance does not measure that saving.
     */
    private fun startAccelerationAllowed(pos: Int, policy: AcceleratorPolicy): Boolean {
        return policy.isExactMatchCandidate || pos >= AdaptiveBackoff.resumePos(startBackoff)
    }

    private fun hasLiteralStartAccelerator(scanner: InputScanner?): Boolean {
        return parentPattern.startPrecedesFindReject()
                && (text != null || scanner is Utf8InputScanner
                || scanner is StringRegionInputScanner)
    }

    /**
     * Like [.rejectFindFrom], for the tail after a short input's first start candidate (see
     * [RejectPrefilter.canRejectTail]).
     */
    private fun rejectTailFrom(scanner: InputScanner?, fromIndex: Int, options: EnginePathOptions): Boolean {
        val rejectPrefilter =
            if (scanner is Utf8InputScanner)
                parentPattern.utf8FindRejectPrefilter()
            else
                parentPattern.findRejectPrefilter()
        if (rejectPrefilter == null) {
            return false
        }
        return if (diagnosticsAccumulator() == null)
            rejectPrefilter.canRejectTail(text, scanner, fromIndex, options)
        else
            reportRejection(rejectPrefilter.tailRejectionStrategy(text, scanner, fromIndex, options))
    }

    private fun rejectFindFrom(scanner: InputScanner?, fromIndex: Int, options: EnginePathOptions): Boolean {
        val rejectPrefilter =
            if (scanner is Utf8InputScanner)
                parentPattern.utf8FindRejectPrefilter()
            else
                parentPattern.findRejectPrefilter()
        if (rejectPrefilter == null) {
            return false
        }
        return if (diagnosticsAccumulator() == null)
            rejectPrefilter.canReject(text, scanner, fromIndex, options)
        else
            rejectWithDiagnostics(rejectPrefilter, text, scanner, fromIndex, options)
    }

    /**
     * Charges a matcher-level start-accelerator call from `pos` to [.startBackoff],
     * unless its candidates are exact match starts (see [.startAccelerationAllowed]).
     * 
     * 
     * A leading expansion over a wide class such as non-DOTALL `.` is not charged either. It
     * often returns the search start itself (for a short skip, or a hit on the first line), and
     * charging that as a failed skip would quarantine [.startBackoff], which also stops the
     * forward DFA from fast-forwarding through its start state. Each call already costs at most one
     * scan to the next inner hit plus one walk back over the line holding it, and later calls resume
     * after the previous match, which lies past that hit.
     */
    private fun chargeStartAcceleration(
        pos: Int, candidate: Int, textLen: Int, policy: AcceleratorPolicy, wideLeadingClass: Boolean
    ) {
        if (WorkCounterConfig.ENABLED) {
            WorkCounter.recordStartScan(candidate - pos)
        }
        if (!policy.isExactMatchCandidate && !wideLeadingClass) {
            startBackoff = AdaptiveBackoff.recordSkip(startBackoff, pos, candidate, textLen, policy)
        }
    }

    private fun searchReverseDfa(
        dfa: Dfa,
        scanner: InputScanner,
        endPos: Int,
        startLimit: Int,
        anchored: Boolean,
        longest: Boolean
    ): Dfa.SearchResult? {
        val result = dfa.doSearchReverse(scanner, endPos, startLimit, anchored, longest)
        val activeDiagnostics = diagnosticOperation
        if (activeDiagnostics != null) {
            activeDiagnostics.accumulator.incrementReverseDfaSearchCount()
            diagnosticDfaBudget(result)
        }
        return result
    }

    private fun diagnosticDfaBudget(result: Dfa.SearchResult?) {
        if (result == null) {
            diagnosticDecision(
                MatchStrategy.DFA, StrategyDisposition.FALLBACK, StrategyReason.DFA_BUDGET_EXCEEDED
            )
        }
    }

    private fun diagnosticIncrementMatchCount() {
        val accumulator = diagnosticsAccumulator()
        if (accumulator != null) {
            accumulator.incrementMatchCount()
        }
    }

    private fun diagnosticMatchCount(): Int {
        val accumulator = diagnosticsAccumulator()
        return if (accumulator == null) 0 else accumulator.matchCount()
    }

    /**
     * Cached BitState instance borrowed from the parent Pattern's thread-local cache, reused across
     * `find()` calls. Borrowed on first use, returned on final use within the Matcher's
     * lifetime.
     */
    private var cachedBitState: BitState? = null
    private var bitStateBorrowed = false
    private var bitStateResult: IntArray? = null
    private var consecutiveWindowFailures = 0
    private var windowRetryDelay = 0
    private var windowSearchesUntilRetry = 0
    private var onePassScratchCap: IntArray? = null

    /** Cached Nfa instance borrowed from the parent Pattern's thread-local cache.  */
    private var cachedNfa: Nfa? = null
    private var nfaBorrowed = false

    /**
     * Whether all capture groups have been resolved. When the DFA sandwich determines match
     * boundaries (group 0), inner captures (groups 1+) are deferred until explicitly requested. This
     * avoids the expensive BitState/NFA submatch extraction in find-all loops that only check match
     * existence or read group 0.
     */
    private var capturesResolved = true

    /** Returns whether inner captures are currently resolved without triggering lazy extraction.  */
    internal fun capturesResolved(): Boolean {
        return capturesResolved
    }

    private var groupZeroResolved = true

    /** Stashed match boundaries for deferred capture resolution.  */
    private var deferredMatchStart = 0

    private var deferredMatchEnd = 0
    private var deferredEndMatch = false

    /** Opaque input view retained only until the current region match is consumed or discarded.  */
    private var deferredRegionScanner: InputScanner? = null
    private var deferredRegionOffset = 0

    /**
     * Whether the current String replacement requires inner captures during exact fallback search.
     * Scoped to that invocation; historical consumption belongs to the Pattern, not this matcher.
     */
    private var replacementNeedsCaptures = false

    /**
     * Cached DFA references to avoid repeated ThreadLocal lookups in find-all loops. Populated on
     * first use and reused for subsequent calls within this Matcher's lifetime.
     */
    private var cachedForwardFirstMatchDfa: Dfa? = null

    /**
     * The parent pattern's per-thread cache, fetched on first use so later lookups by this Matcher
     * skip the [ThreadLocal] and [java.lang.ref.SoftReference] dereferences.
     */
    private var threadCache: ThreadCache? = null
    private var cachedForwardLongestMatchDfa: Dfa? = null
    private var cachedReverseDfa: Dfa? = null
    private var reverseDfaLookedUp = false

    /**
     * Start-acceleration backoff state carried across the searches of one sequence of `find()`
     * calls; see [AdaptiveBackoff]. Each forward DFA search reads it once on entry and writes
     * it once on exit, and the matcher-level accelerator calls in `doFindCore` and `findNextMatchPacked` are charged to it too, so a sequence of `find()` calls over input
     * that defeats the accelerator pays the loss limit once rather than once per call. Cleared on
     * reset, region and pattern changes. The state only decides whether to call the accelerator, so a
     * stale value can cost time but never changes a result.
     */
    private var startBackoff: Long = 0

    private var graphemeContextInput: InputScanner? = null
    private var graphemeContextStart = 0
    private var graphemeContextEnd = 0
    private var graphemeContext: GraphemeSupport.Context? = null

    /**
     * Creates a new matcher that will match the given input against the given pattern.
     * 
     * @param pattern the pattern to use
     * @param input the input character sequence
     */
    internal constructor(pattern: Pattern, input: CharSequence) {
        this.parentPattern = pattern
        this.inputSequence = input
        this.text = charSequenceToString(input)
        this.textScanner = null
        this.regionEnd = text!!.length
        this.groups = IntArray(2 * pattern.prog().numCaptures())
    }

    internal constructor(pattern: Pattern, input: Utf8InputScanner) {
        this.parentPattern = pattern
        this.textScanner = input
        this.regionEnd = input.length
        this.groups = IntArray(2 * pattern.prog().numCaptures())
    }

    private fun applyFailedMatchResult(): Boolean {
        hasMatch = false
        resultStatus = ResultStatus.FAILED
        clearDeferredCaptureState()
        return false
    }

    private fun applyFullMatchResult(resultGroups: IntArray?): Boolean {
        findExhaustedAfterTerminalEmptyMatch = false
        hasMatch = resultGroups != null
        if (hasMatch && resultGroups != this.groups) {
            System.arraycopy(resultGroups, 0, this.groups, 0, resultGroups!!.size)
        }
        resultStatus = if (hasMatch) ResultStatus.MATCHED else ResultStatus.FAILED
        clearDeferredCaptureState()
        return hasMatch
    }

    private fun applyGroupZeroMatchResult(start: Int, end: Int): Boolean {
        findExhaustedAfterTerminalEmptyMatch = false
        groups[0] = start
        groups[1] = end
        if (groups.size > 2) {
            Arrays.fill(groups, 2, groups.size, -1)
        }
        deferredMatchStart = start
        deferredMatchEnd = end
        deferredEndMatch = false
        capturesResolved = true
        groupZeroResolved = true
        hasMatch = true
        resultStatus = ResultStatus.MATCHED
        return true
    }

    private fun applyDeferredMatchResult(
        start: Int, end: Int, ncap: Int, groupZeroResolved: Boolean, endMatch: Boolean
    ): Boolean {
        findExhaustedAfterTerminalEmptyMatch = false
        Arrays.fill(groups, -1)
        groups[0] = start
        groups[1] = end
        deferredMatchStart = start
        deferredMatchEnd = end
        deferredEndMatch = endMatch
        capturesResolved = ncap <= 1
        this.groupZeroResolved = groupZeroResolved
        hasMatch = true
        resultStatus = ResultStatus.MATCHED
        return true
    }

    private fun clearCurrentResult() {
        findExhaustedAfterTerminalEmptyMatch = false
        hasMatch = false
        resultStatus = ResultStatus.RESET_NO_ATTEMPT
        clearDeferredCaptureState()
    }

    private fun clearDeferredCaptureState() {
        capturesResolved = true
        groupZeroResolved = true
        deferredMatchStart = 0
        deferredMatchEnd = 0
        deferredEndMatch = false
        deferredRegionScanner = null
        deferredRegionOffset = 0
    }

    private fun resetReplacementState() {
        appendPos = 0
    }

    private fun resetWindowBackoff() {
        consecutiveWindowFailures = 0
        windowRetryDelay = 0
        windowSearchesUntilRetry = 0
    }

    private fun resetSearchStateForInputStart() {
        resetWindowBackoff()
        searchFrom = 0
        previousMatchEnd = 0
        startBackoff = AdaptiveBackoff.NEUTRAL
    }

    private fun resetSearchStateForRegionStart() {
        resetWindowBackoff()
        searchFrom = regionStart
        previousMatchEnd = regionStart
        startBackoff = AdaptiveBackoff.NEUTRAL
    }

    private fun rememberPreviousMatchEnd() {
        if (hasMatch) {
            if (!groupZeroResolved) {
                resolveCaptures()
            }
            previousMatchEnd = groups[1]
        }
    }

    private fun prepareAnchoredAttempt() {
        rememberPreviousMatchEnd()
        clearDeferredCaptureState()
        findExhaustedAfterTerminalEmptyMatch = false
        // An anchored failure invalidates the result, but does not reset find's continuation.
        searchFrom = previousMatchEnd
    }

    private fun resetStateForCurrentInput() {
        if (inputSequence != null) {
            if (inputSequence !is String) {
                textScanner = null
                cachedRegionScanner = null
                regionClassSearch = null
                graphemeContextInput = null
                graphemeContext = null
            }
            text = charSequenceToString(inputSequence!!)
        }
        regionStart = 0
        regionEnd = this.textLength
        resetSearchStateForInputStart()
        resetReplacementState()
        clearCurrentResult()
    }

    private fun resetStateForRegion(start: Int, end: Int) {
        regionStart = start
        regionEnd = end
        resetSearchStateForRegionStart()
        resetReplacementState()
        clearCurrentResult()
    }

    private fun invalidatePatternCaches() {
        regionClassSearch = null
        resetWindowBackoff()
        threadCache = null
        cachedForwardFirstMatchDfa = null
        cachedForwardLongestMatchDfa = null
        cachedReverseDfa = null
        reverseDfaLookedUp = false
        startBackoff = AdaptiveBackoff.NEUTRAL
        if (bitStateBorrowed && cachedBitState != null) {
            bitStateBorrowed = false
            cachedBitState = null
        }
        if (nfaBorrowed && cachedNfa != null) {
            nfaBorrowed = false
            cachedNfa = null
        }
        bitStateResult = null
        graphemeContextInput = null
        graphemeContext = null
    }

    private fun invalidateInputDependentCaches() {
        textScanner = null
        cachedRegionScanner = null
        regionClassSearch = null
        bitStateBorrowed = false
        cachedBitState = null
        bitStateResult = null
        nfaBorrowed = false
        cachedNfa = null
        graphemeContextInput = null
        graphemeContext = null
    }

    private fun preserveResultAcrossBoundsChange() {
        if (hasMatch && !capturesResolved) {
            resolveCaptures()
        }
        clearDeferredCaptureState()
    }

    private fun cannotMatchLength(availableLength: Int): Boolean {
        val min = parentPattern.matchDescriptor().minMatchLength
        if (min > 0 && availableLength < min) {
            diagnosticBoundary(MatchStrategy.LENGTH)
            return true
        }
        return false
    }

    internal fun onePassTextLimit(): Int {
        return onePassTextLimit(parentPattern.numGroups() > 0)
    }

    private fun enginePathOptions(): EnginePathOptions {
        return parentPattern.enginePathOptions()
    }

    private fun recordInnerCaptureDemand() {
        parentPattern.recordInnerCaptureAccess()
    }

    private fun shouldPreferCaptureEngine(prog: Prog, scanner: InputScanner): Boolean {
        return parentPattern.innerCapturesObserved()
                && scanner.length <= CAPTURE_DEMAND_TEXT_LIMIT && enginePathOptions().bitState
                && !fullTextRegionContext && !prog.hasGraphemeSemantics() && BitState.maxTextSize(prog) >= scanner.length
    }

    private fun threadCache(): ThreadCache {
        releaseEnginesOwnedByOtherThread()
        var cache = threadCache
        if (cache == null) {
            cache = parentPattern.orCreateThreadCache
            threadCache = cache
        }
        return cache
    }

    /**
     * Drops engine references that came from another thread's cache.
     * 
     * 
     * Every engine this Matcher holds came from [.threadCache]. After a sequential handoff,
     * the previous thread may still use those engines through the same Pattern, so the Matcher must
     * switch to the executing thread's cache before its next search.
     */
    private fun releaseEnginesOwnedByOtherThread() {
        val cache = threadCache
        if (cache != null && cache.ownerThreadId != Thread.currentThread().threadId()) {
            threadCache = null
            cachedForwardFirstMatchDfa = null
            cachedForwardLongestMatchDfa = null
            cachedReverseDfa = null
            reverseDfaLookedUp = false
            cachedBitState = null
            bitStateBorrowed = false
            cachedNfa = null
            nfaBorrowed = false
        }
    }

    /** Returns the Pattern's thread-local cached forward DFA, caching it for reuse.  */
    private fun dfa(longest: Boolean): Dfa {
        releaseEnginesOwnedByOtherThread()
        if (longest) {
            var d = cachedForwardLongestMatchDfa
            if (d == null) {
                d = parentPattern.forwardLongestMatchDfa(threadCache())
                cachedForwardLongestMatchDfa = d
            }
            return d
        } else {
            var d = cachedForwardFirstMatchDfa
            if (d == null) {
                d = parentPattern.forwardFirstMatchDfa(threadCache())
                cachedForwardFirstMatchDfa = d
            }
            return d
        }
    }

    /** Returns the Pattern's thread-local cached reverse DFA (or null), caching it for reuse.  */
    private fun reverseDfa(): Dfa? {
        releaseEnginesOwnedByOtherThread()
        if (!reverseDfaLookedUp) {
            cachedReverseDfa = parentPattern.reverseDfa(threadCache())
            reverseDfaLookedUp = true
        }
        return cachedReverseDfa
    }

    // ---------------------------------------------------------------------------
    // Core matching methods
    // ---------------------------------------------------------------------------
    /**
     * Fast path for `matches()` when the pattern is a single character class under a quantifier
     * (e.g., `[a-zA-Z]+`, `\d*`). Uses precomputed ASCII bitmaps for O(1) per-character
     * checks and falls back to binary search for non-ASCII code points.
     */
    private fun charClassMatchFastPath(matchInfo: CharClassMatchInfo): Boolean {
        val b0 = matchInfo.bitmap0
        val b1 = matchInfo.bitmap1
        val allowEmpty = matchInfo.allowEmpty

        val len: Int = text!!.length
        if (len == 0) {
            if (allowEmpty) {
                return applyFullMatchResult(intArrayOf(0, 0))
            }
            return applyFailedMatchResult()
        }

        val ranges = matchInfo.ranges!!
        // Scan every code point.
        var i = 0
        while (i < len) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            val cp = text!!.codePointAt(i)
            if (cp < 64) {
                if ((b0 and (1L shl cp)) == 0L) {
                    return applyFailedMatchResult()
                }
            } else if (cp < 128) {
                if ((b1 and (1L shl (cp - 64))) == 0L) {
                    return applyFailedMatchResult()
                }
            } else {
                if (!binarySearchRanges(ranges!!, cp)) {
                    return applyFailedMatchResult()
                }
            }
            i += Character.charCount(cp)
        }

        return applyFullMatchResult(intArrayOf(0, len))
    }

    /**
     * UTF-8 counterpart to [.charClassMatchFastPath]: checks ASCII bytes against precomputed
     * bitmaps and falls back to scalar decoding and range search only on non-ASCII bytes.
     */
    private fun charClassMatchUtf8FastPath(
        utf8: Utf8InputScanner, matchInfo: CharClassMatchInfo
    ): Boolean {
        val len = utf8.length
        if (len == 0) {
            return if (matchInfo.allowEmpty)
                applyFullMatchResult(intArrayOf(0, 0))
            else
                applyFailedMatchResult()
        }

        val b0 = matchInfo.bitmap0
        val b1 = matchInfo.bitmap1
        val ranges = matchInfo.ranges!!
        val asciiOnly = ranges[ranges.size - 1] < 0x80
        var i = 0
        while (i < len) {
            val ascii = utf8.asciiAt(i)
            if (ascii >= 0) {
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record()
                }
                if (ascii < 64) {
                    if ((b0 and (1L shl ascii)) == 0L) {
                        return applyFailedMatchResult()
                    }
                } else {
                    if ((b1 and (1L shl (ascii - 64))) == 0L) {
                        return applyFailedMatchResult()
                    }
                }
                i++
            } else if (asciiOnly) {
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record()
                }
                return applyFailedMatchResult()
            } else {
                val decoded = utf8.decodeForward(i)
                if (!binarySearchRanges(ranges!!, InputScanner.codePoint(decoded))) {
                    return applyFailedMatchResult()
                }
                i = InputScanner.position(decoded)
            }
        }

        return applyFullMatchResult(intArrayOf(0, len))
    }

    /**
     * Fast path for `find()` when the pattern is exactly one character class. Scans code points
     * directly and returns the first matching code point as group 0.
     */
    private fun singleCharClassFindFastPath(scanInfo: CharClassScanInfo, fromIndex: Int): Boolean {
        if (scanInfo.isAscii) {
            val idx = activeScanner().indexOfCharClass(scanInfo, fromIndex)
            if (idx >= 0) {
                return applyFullMatchResult(intArrayOf(idx, idx + 1))
            }
            return applyFailedMatchResult()
        }
        val idx =
            activeScanner()
                .indexOfCodePointClass(
                    scanInfo.ranges,
                    scanInfo.bitmap0,
                    scanInfo.bitmap1,
                    fromIndex,
                    activeScanner().length
                )
        if (idx >= 0) {
            val end =
                if (text != null)
                    idx + Character.charCount(text!!.codePointAt(idx))
                else
                    InputScanner.position(activeScanner().decodeForward(idx))
            return applyFullMatchResult(intArrayOf(idx, end))
        }
        return applyFailedMatchResult()
    }

    /** Finds a greedy, capture-free repeated class over the whole logical UTF-8 input.  */
    private fun repeatedCharClassFindUtf8FastPath(
        scanner: Utf8InputScanner, matchInfo: CharClassMatchInfo, fromIndex: Int
    ): Boolean {
        val length = scanner.length
        if (fromIndex > length) {
            return applyFailedMatchResult()
        }
        var start = fromIndex
        if (!matchInfo.allowEmpty) {
            start =
                scanner.indexOfCodePointClass(
                    matchInfo.ranges!!, matchInfo.bitmap0, matchInfo.bitmap1, fromIndex, length
                )
            if (start < 0) {
                return applyFailedMatchResult()
            }
        }
        var end = start
        while (end < length) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            val ascii = scanner.asciiAt(end)
            val decoded = if (ascii < 0) scanner.decodeForward(end) else 0
            val cp = if (ascii < 0) InputScanner.codePoint(decoded) else ascii
            if (!charClassContains(matchInfo.ranges!!, matchInfo.bitmap0, matchInfo.bitmap1, cp)) {
                break
            }
            end = if (ascii < 0) InputScanner.position(decoded) else end + 1
        }
        return applyGroupZeroMatchResult(start, end)
    }

    /**
     * Checks if the remaining input from `offset` is a prefix of the literal pattern but
     * shorter than it, meaning more input could potentially result in a match.
     */
    private fun isPartialLiteralMatch(literal: String, offset: Int): Boolean {
        val remainingLen: Int = text!!.length - offset
        if (remainingLen >= literal.length) {
            return false
        }
        return literalRegionMatches(literal, offset, remainingLen)
    }

    private fun literalRegionMatches(literal: String, offset: Int, length: Int): Boolean {
        if (parentPattern.literalFoldCase() || parentPattern.prefixFoldCase()) {
            return Ascii.regionMatchesIgnoreCase(text!!, offset, literal, length)
        }
        return text!!.regionMatches(offset, literal, 0, length, false)
    }

    // ---------------------------------------------------------------------------
    // Compiled replacement template
    // ---------------------------------------------------------------------------
    /**
     * A pre-parsed segment of a replacement string. Segments are either literal text or group
     * references (numbered or named). Pre-parsing avoids per-match scanning, `parseInt`, and
     * `substring` allocation.
     */
    internal interface ReplacementSegment {
        /** A literal text segment to be appended verbatim.  */
        @JvmRecord
        data class Literal(val text: String) : ReplacementSegment

        /** A numbered group reference (`$0`, `$1`, etc.).  */
        @JvmRecord
        data class GroupRef(val groupNum: Int) : ReplacementSegment

        /** A named group reference (`${name}`).  */
        @JvmRecord
        data class NamedGroupRef(val name: String?) : ReplacementSegment
    }

    @JvmRecord
    private data class NumericGroupReference(val groupNum: Int, val end: Int)

    /**
     * Applies a compiled replacement template to the current match, appending the result to `sb`. Uses `sb.append(text, start, end)` for group values to avoid substring allocation.
     * 
     * 
     * Captures must already be resolved before calling this method.
     */
    private fun applyReplacementTemplate(sb: StringBuilder, template: Array<ReplacementSegment>) {
        if (templateNeedsCaptures(template)) {
            recordInnerCaptureDemand()
            resolveCaptures()
        }
        for (seg in template) {
            when (seg) {
                is ReplacementSegment.Literal -> sb.append(seg.text)
                is ReplacementSegment.GroupRef -> {
                    val g = seg.groupNum
                    checkGroup(g)
                    val start = groups[2 * g]
                    val end = groups[2 * g + 1]
                    if (start >= 0 && end >= 0) {
                        sb.append(text, start, end)
                    }
                }

                is ReplacementSegment.NamedGroupRef -> {
                    val g: String? = group(seg.name)
                    if (g != null) {
                        sb.append(g)
                    }
                }
            }
        }
    }

    /**
     * Attempts to match the entire input sequence against the pattern.
     * 
     * @return `true` if the entire input sequence matches this matcher's pattern
     */
    fun matches(): Boolean {
        val operation = beginDiagnostics(MatchOperation.MATCHES)
        if (operation == null) {
            return matchesImpl()
        }
        try {
            val matched = matchesImpl()
            completeDiagnostics(operation, if (matched) 1 else 0)
            return matched
        } catch (e: RuntimeException) {
            abortDiagnostics(operation)
            throw e
        } catch (e: Error) {
            abortDiagnostics(operation)
            throw e
        }
    }

    private fun matchesImpl(): Boolean {
        modCount++
        prepareAnchoredAttempt()

        if (cannotMatchLength(regionEnd - regionStart)) {
            return applyFailedMatchResult()
        }

        // --- Region setup ---
        val regionActive = (regionStart != 0 || regionEnd != this.textLength)
        val savedText = text
        val savedTextScanner = textScanner
        var regionSubstituted = false

        try {
            if (regionActive && !anchoringBounds && regionTextAnchorCannotMatch()) {
                return applyFailedMatchResult()
            }
            if (needsFullTextRegionContext(regionActive)) {
                return matchesFullTextRegion()
            }
            if (regionActive) {
                useRegionInput(savedTextScanner)
                regionSubstituted = true
            }
            return parentPattern.preparedMatchRunner(regionActive)!!.matches(this)
        } finally {
            if (regionSubstituted) {
                resolveCapturesBeforeRestoringRegion()
                text = savedText
                textScanner = savedTextScanner
                if (hasMatch) {
                    for (i in groups.indices) {
                        if (groups[i] >= 0) {
                            groups[i] += regionStart
                        }
                    }
                }
            }
            fullTextRegionContext = false
        }
    }

    private fun prefixOrCharClassCannotMatch(searchFrom: Int): Boolean {
        if (parentPattern.prefix() != null && !parentPattern.prefixFoldCase()) {
            if (text != null) {
                if (!text!!.startsWith(parentPattern.prefix()!!, searchFrom)) {
                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.record(parentPattern.prefix()!!.length.toLong())
                    }
                    return true
                }
            } else if (textScanner is StringRegionInputScanner) {
                val regionScanner = textScanner as StringRegionInputScanner
                if (!regionScanner.startsWith(parentPattern.prefix()!!, searchFrom, false)) {
                    return true
                }
            } else if (textScanner is Utf8InputScanner) {
                val utf8Scanner = textScanner as Utf8InputScanner
                if (!utf8Scanner.startsWith(parentPattern.prefixUtf8()!!, searchFrom)) {
                    return true
                }
            }
        } else if (parentPattern.charClassPrefix() != null) {
            val cc = parentPattern.charClassPrefix()
            if (text != null) {
                if (searchFrom >= text!!.length) {
                    return true
                }
                val cp = text!!.codePointAt(searchFrom)
                if (!cc!!.contains(cp)) {
                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.record(1)
                    }
                    return true
                }
            } else if (textScanner is Utf8InputScanner) {
                val utf8Scanner = textScanner as Utf8InputScanner
                if (searchFrom >= utf8Scanner.length) {
                    return true
                }
                val cp = utf8Scanner.codePointAt(searchFrom)
                if (!cc!!.contains(cp)) {
                    return true
                }
            }
        }
        return false
    }

    private fun anchoredPrefixOrCharClassCannotMatch(searchFrom: Int): Boolean {
        if (parentPattern.anchoredPrefix() != null) {
            if (text != null) {
                if (!text!!.startsWith(parentPattern.anchoredPrefix()!!, searchFrom)) {
                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.record(parentPattern.anchoredPrefix()!!.length.toLong())
                    }
                    return true
                }
            } else if (textScanner is StringRegionInputScanner) {
                val regionScanner = textScanner as StringRegionInputScanner
                if (!regionScanner.startsWith(parentPattern.anchoredPrefix()!!, searchFrom, false)) {
                    return true
                }
            } else if (textScanner is Utf8InputScanner) {
                val utf8Scanner = textScanner as Utf8InputScanner
                if (!utf8Scanner.startsWith(parentPattern.anchoredPrefixUtf8()!!, searchFrom)) {
                    return true
                }
            }
        } else if (parentPattern.anchoredCharClassPrefix() != null) {
            val cc = parentPattern.anchoredCharClassPrefix()
            if (text != null) {
                if (searchFrom >= text!!.length) {
                    return true
                }
                val cp = text!!.codePointAt(searchFrom)
                if (!cc!!.contains(cp)) {
                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.record(1)
                    }
                    return true
                }
            } else if (textScanner is Utf8InputScanner) {
                val utf8Scanner = textScanner as Utf8InputScanner
                if (searchFrom >= utf8Scanner.length) {
                    return true
                }
                val cp = utf8Scanner.codePointAt(searchFrom)
                if (!cc!!.contains(cp)) {
                    return true
                }
            }
        }
        return false
    }

    private fun terminalPrefilterRejects(fromIndex: Int): Boolean {
        val prog = parentPattern.prog()
        if ((!prog.anchorEnd() && !prog.dollarAnchorEnd()) || parentPattern.rejectPrefilter() == null) {
            return false
        }
        return parentPattern.terminalPrefilterRejects(
            activeScanner(), fromIndex, diagnosticsAccumulator()
        )
    }

    /** Core matches fallback logic, operates on the (possibly substituted) `text` field.  */
    private fun matchesCore(): Boolean {
        capturesResolved = true

        if (prefixOrCharClassCannotMatch(0) || anchoredPrefixOrCharClassCannotMatch(0)) {
            val strat =
                if (parentPattern.prefix() != null || parentPattern.anchoredPrefix() != null)
                    MatchStrategy.LITERAL
                else
                    MatchStrategy.CHARACTER_CLASS
            diagnosticParticipation(strat, StrategyRole.REJECT_PREFILTER)
            diagnosticBoundary(strat)
            return applyFailedMatchResult()
        }

        val rejectPrefilter = parentPattern.rejectPrefilter()
        if (rejectPrefilter != null
            && (if (diagnosticsAccumulator() == null)
                rejectPrefilter.canReject(text, textScanner, 0, enginePathOptions())
            else
                rejectWithDiagnostics(rejectPrefilter, text, textScanner, 0, enginePathOptions()))
        ) {
            return applyFailedMatchResult()
        }

        val prog = parentPattern.prog()
        val scanner = activeScanner()
        val preferCaptureEngine = shouldPreferCaptureEngine(prog, scanner)
        // Medium path: use DFA to check if a full match exists.
        if (!preferCaptureEngine && enginePathOptions().dfa && parentPattern.forwardDfaSupported()) {
            diagnosticParticipation(MatchStrategy.DFA, StrategyRole.REJECT_PREFILTER)
            val dfaResult = searchForwardDfa(dfa(true), scanner, true, true)
            if (dfaResult != null && !dfaResult.matched) {
                diagnosticBoundary(MatchStrategy.DFA)
                return applyFailedMatchResult()
            }
            if (dfaResult != null && dfaResult.pos != scanner.length) {
                diagnosticBoundary(MatchStrategy.DFA)
                return applyFailedMatchResult()
            }
            if (dfaResult != null) {
                // Empty-stop changes ordered paths and captures, not full-match existence: remove empty
                // iterations before later iterations while retaining the minimum. Input positions and
                // assertion context are unchanged, and captures cannot affect recognition in our dialect.
                // Here the whole region fixes group zero; the guarded program resolves captures on demand.
                // Prefix/search matching still needs exact progress semantics to choose its match bounds.
                diagnosticBoundary(MatchStrategy.DFA)
                return applyDeferredMatchResult(0, scanner.length, prog.numCaptures(), true, true)
            }
            if (dfaResult == null) {
                diagnosticDecision(
                    MatchStrategy.DFA, StrategyDisposition.FALLBACK, StrategyReason.DFA_BUDGET_EXCEEDED
                )
            }
        }

        // Slow path: try BitState (faster than NFA for small texts), then NFA.
        val result =
            searchWithBitStateOrNfa(
                prog,
                scanner,
                0,
                scanner.length,
                scanner.length,
                true,
                false,
                true,
                prog.numCaptures(),
                false,
                this.groups
            )
        return applyFullMatchResult(result)
    }

    /**
     * Attempts to match the input sequence, starting at the beginning, against the pattern.
     * 
     * 
     * Like [.matches], this method always starts at the beginning of the input; unlike
     * that method, it does not require that the entire input sequence be matched.
     * 
     * @return `true` if a prefix of the input sequence matches this matcher's pattern
     */
    fun lookingAt(): Boolean {
        val operation = beginDiagnostics(MatchOperation.LOOKING_AT)
        try {
            val matched = lookingAtImpl()
            completeDiagnostics(operation, if (matched) 1 else 0)
            return matched
        } catch (e: RuntimeException) {
            abortDiagnostics(operation)
            throw e
        } catch (e: Error) {
            abortDiagnostics(operation)
            throw e
        }
    }

    private fun lookingAtImpl(): Boolean {
        modCount++
        prepareAnchoredAttempt()

        if (cannotMatchLength(regionEnd - regionStart)) {
            return applyFailedMatchResult()
        }

        // --- Region setup ---
        val regionActive = (regionStart != 0 || regionEnd != this.textLength)
        val savedText = text
        val savedTextScanner = textScanner
        var regionSubstituted = false

        try {
            if (regionActive && !anchoringBounds && regionTextAnchorCannotMatch()) {
                return applyFailedMatchResult()
            }
            if (needsFullTextRegionContext(regionActive)) {
                return lookingAtFullTextRegion()
            }
            if (regionActive) {
                useRegionInput(savedTextScanner)
                regionSubstituted = true
            }
            return parentPattern.preparedMatchRunner(regionActive)!!.lookingAt(this)
        } finally {
            if (regionSubstituted) {
                resolveCapturesBeforeRestoringRegion()
                text = savedText
                textScanner = savedTextScanner
                if (hasMatch) {
                    for (i in groups.indices) {
                        if (groups[i] >= 0) {
                            groups[i] += regionStart
                        }
                    }
                }
            }
            fullTextRegionContext = false
        }
    }

    /** Core lookingAt fallback logic, operates on the (possibly substituted) `text` field.  */
    private fun lookingAtCore(): Boolean {
        capturesResolved = true

        if (prefixOrCharClassCannotMatch(0) || anchoredPrefixOrCharClassCannotMatch(0)) {
            val strat =
                if (parentPattern.prefix() != null || parentPattern.anchoredPrefix() != null)
                    MatchStrategy.LITERAL
                else
                    MatchStrategy.CHARACTER_CLASS
            diagnosticParticipation(strat, StrategyRole.REJECT_PREFILTER)
            diagnosticBoundary(strat)
            return applyFailedMatchResult()
        }

        val prog = parentPattern.prog()
        val scanner = activeScanner()
        val preferCaptureEngine = shouldPreferCaptureEngine(prog, scanner)
        // Medium path: use DFA to check if an anchored match exists.
        if (!preferCaptureEngine && enginePathOptions().dfa && parentPattern.forwardDfaSupported()) {
            diagnosticParticipation(MatchStrategy.DFA, StrategyRole.REJECT_PREFILTER)
            val dfaResult = searchForwardDfa(dfa(false), scanner, true, false)
            if (dfaResult != null && !dfaResult.matched) {
                diagnosticBoundary(MatchStrategy.DFA)
                return applyFailedMatchResult()
            }
            if (dfaResult != null && prog.numLoopRegs() == 0) {
                diagnosticBoundary(MatchStrategy.DFA)
                return applyDeferredMatchResult(0, dfaResult.pos, prog.numCaptures(), true, false)
            }
            if (dfaResult == null) {
                diagnosticDecision(
                    MatchStrategy.DFA, StrategyDisposition.FALLBACK, StrategyReason.DFA_BUDGET_EXCEEDED
                )
            }
            if (dfaResult != null && dfaResult.matched && prog.numLoopRegs() > 0) {
                diagnosticDecision(
                    MatchStrategy.DFA,
                    StrategyDisposition.BYPASSED,
                    StrategyReason.EXACT_NULLABLE_LOOP_SEMANTICS_REQUIRED
                )
            }
        }

        // Slow path: try BitState (faster than NFA for small texts), then NFA.
        val result =
            searchWithBitStateOrNfa(
                prog,
                scanner,
                0,
                scanner.length,
                scanner.length,
                true,
                false,
                false,
                prog.numCaptures(),
                false,
                this.groups
            )
        return applyFullMatchResult(result)
    }

    /**
     * Attempts to find the next subsequence of the input sequence that matches the pattern.
     * 
     * 
     * This method starts at the beginning of the input on the first invocation, or at the
     * character after the end of the previous match on subsequent invocations. Empty matches cause
     * the search to advance by one character to avoid infinite loops.
     * 
     * @return `true` if a subsequence of the input sequence matches this matcher's pattern
     */
    fun find(): Boolean {
        val operation = beginDiagnostics(MatchOperation.FIND)
        try {
            val matched = findImpl()
            completeDiagnostics(operation, if (matched) 1 else 0)
            return matched
        } catch (e: RuntimeException) {
            abortDiagnostics(operation)
            throw e
        } catch (e: Error) {
            abortDiagnostics(operation)
            throw e
        }
    }

    private fun findImpl(): Boolean {
        modCount++
        rememberPreviousMatchEnd()
        if (findExhaustedAfterTerminalEmptyMatch) {
            applyFailedMatchResult()
            return false
        }
        val continuationAfterFailure = if (hasMatch) previousMatchEnd else searchFrom
        if (hasMatch && !advanceSearchPositionAfterPreviousMatch()) {
            return false
        }
        return findWithContinuation(continuationAfterFailure)
    }

    private fun advanceSearchPositionAfterPreviousMatch(): Boolean {
        if (!groupZeroResolved) {
            resolveCaptures()
        }
        searchFrom = groups[1]
        if (groups[0] == groups[1]) {
            if (searchFrom >= regionEnd) {
                applyFailedMatchResult()
                findExhaustedAfterTerminalEmptyMatch = true
                diagnosticIntentionalDivergence(IntentionalDivergence.EXHAUSTED_EMPTY_MATCH)
                searchFrom = regionEnd + 1
                return false
            }
            if (text == null) {
                searchFrom = InputScanner.position(activeScanner().decodeForward(searchFrom))
            } else {
                searchFrom++
            }
        } else if (parentPattern.hasInternalGraphemeClusterBoundary()
            && searchFrom < regionEnd && endedAfterCrLf(searchFrom)
        ) {
            searchFrom++
        }
        return true
    }

    private fun endedAfterCrLf(pos: Int): Boolean {
        val visibleStart = if (transparentBounds) 0 else regionStart
        if (pos < visibleStart + 2) {
            return false
        }
        val scanner = activeScanner()
        return scanner.asciiAt(pos - 2) == '\r'.code && scanner.asciiAt(pos - 1) == '\n'.code
    }

    private fun graphemeContext(scanner: InputScanner): GraphemeSupport.Context? {
        val bounded = fullTextRegionContext && !transparentBounds
        val start = if (bounded) regionStart else 0
        val end = if (bounded) regionEnd else scanner.length
        if (graphemeContext == null || graphemeContextInput !== scanner || graphemeContextStart != start || graphemeContextEnd != end) {
            graphemeContextInput = scanner
            graphemeContextStart = start
            graphemeContextEnd = end
            graphemeContext =
                if (bounded)
                    GraphemeSupport.Context.createRegion(scanner, start, end)
                else
                    GraphemeSupport.Context.create(
                        scanner, parentPattern.prog().hasGraphemeSemantics()
                    )
        }
        return graphemeContext
    }

    private fun graphemeContextFor(prog: Prog, scanner: InputScanner): GraphemeSupport.Context? {
        return if (prog.hasGraphemeSemantics()) graphemeContext(scanner) else null
    }

    private val textLength: Int
        get() = if (text != null) text!!.length else textScanner!!.length

    private fun canUseForwardDfa(): Boolean {
        return enginePathOptions().dfa && parentPattern.forwardDfaSupported()
    }

    private fun canUseReverseDfa(): Boolean {
        return enginePathOptions().dfa
                && enginePathOptions().reverseDfa
                && parentPattern.canUseReverseDfa()
                && dfaSupportsProgram(parentPattern.flatReverseDfaProg())
    }

    /**
     * Resets this matcher and then attempts to find the next subsequence of the input that matches
     * the pattern, starting at the specified index.
     * 
     * @param start the index at which to start the search
     * @return `true` if a subsequence of the input starting at the given index matches this
     * matcher's pattern
     * @throws IndexOutOfBoundsException if start is negative or greater than the length of the input
     */
    fun find(start: Int): Boolean {
        val operation = beginDiagnostics(MatchOperation.FIND)
        try {
            val matched = findFromImpl(start)
            completeDiagnostics(operation, if (matched) 1 else 0)
            return matched
        } catch (e: RuntimeException) {
            abortDiagnostics(operation)
            throw e
        } catch (e: Error) {
            abortDiagnostics(operation)
            throw e
        }
    }

    private fun findFromImpl(start: Int): Boolean {
        val len = this.textLength
        if (start < 0 || start > len) {
            throw IndexOutOfBoundsException("start=" + start + ", length=" + len)
        }
        modCount++
        reset()
        searchFrom = start
        return findWithContinuation(previousMatchEnd)
    }

    /**
     * Returns a stream of match results for each subsequence of the input sequence that matches the
     * pattern. The match results occur in the same order as the matching subsequences in the input.
     * 
     * 
     * Each match result is produced as if by [.toMatchResult].
     * 
     * 
     * This method does not reset this matcher. Matching starts on a call to [ ][Stream.findFirst] or similar terminal operation, and continues from the current position.
     * 
     * @return a sequential stream of match results
     */
    fun results(): Stream<MatchResult?> {
        val spliterator: Spliterator<MatchResult?> =
            object : AbstractSpliterator<MatchResult?>(
                java.lang.Long.MAX_VALUE, ORDERED or NONNULL
            ) {
                override fun tryAdvance(action: Consumer<in MatchResult?>): Boolean {
                    if (!find()) {
                        return false
                    }
                    val expectedModCount = modCount
                    action.accept(toMatchResult())
                    checkConcurrentModification(expectedModCount)
                    return true
                }
            }
        return StreamSupport.stream<MatchResult?>(spliterator, false)
    }

    /** Restores the next search position when an engine search fails.  */
    private fun findWithContinuation(continuationAfterFailure: Int): Boolean {
        val matched = doFind()
        if (!matched) {
            // Advancement past an empty match belongs only to the immediately following attempt.
            // After failure, restore the continuation before that advancement or explicit find(int)
            // start. Keep positions established by bulk operations such as replaceAll intact.
            searchFrom = continuationAfterFailure
        }
        return matched
    }

    /** Runs the engine search from [.searchFrom] and stores the result.  */
    private fun doFind(): Boolean {
        if (cannotMatchLength(this.textLength - searchFrom)) {
            return applyFailedMatchResult()
        }
        val regionActive = (regionStart != 0 || regionEnd != this.textLength)
        if (regionActive) {
            return doFindRegion(regionActive)
        }
        if (parentPattern.prog().anchorStart()) {
            if (searchFrom > 0) {
                return applyFailedMatchResult()
            }
            if (anchoredPrefixOrCharClassCannotMatch(0)) {
                val strategy =
                    if (parentPattern.anchoredPrefix() != null)
                        MatchStrategy.LITERAL
                    else
                        MatchStrategy.CHARACTER_CLASS
                diagnosticParticipation(strategy, StrategyRole.REJECT_PREFILTER)
                diagnosticBoundary(strategy)
                return applyFailedMatchResult()
            }
        }
        return parentPattern.preparedMatchRunner(false)!!.find(this, false)
    }

    private fun doFindRegion(regionActive: Boolean): Boolean {
        if (cannotMatchLength(regionEnd - searchFrom)) {
            return applyFailedMatchResult()
        }
        // --- Region setup: temporarily substitute a bounded region view ---
        val savedText = text
        val savedTextScanner = textScanner
        val savedSearchFrom = searchFrom
        var regionSubstituted = false

        try {
            if (regionActive && !anchoringBounds && regionTextAnchorCannotMatch()) {
                return applyFailedMatchResult()
            }
            if (needsFullTextRegionContext(regionActive)) {
                return doFindFullTextRegion()
            }
            if (regionActive) {
                useRegionInput(savedTextScanner)
                searchFrom = Math.max(0, savedSearchFrom - regionStart)
                regionSubstituted = true
            }
            return parentPattern.preparedMatchRunner(regionActive)!!.find(this, regionActive)
        } finally {
            if (regionSubstituted) {
                if (hasMatch && !capturesResolved) {
                    deferredRegionScanner = activeScanner()
                    deferredRegionOffset = regionStart
                }
                text = savedText
                textScanner = savedTextScanner
                searchFrom = savedSearchFrom
                if (hasMatch) {
                    for (i in groups.indices) {
                        if (groups[i] >= 0) {
                            groups[i] += regionStart
                        }
                    }
                }
            }
            fullTextRegionContext = false
        }
    }

    private fun needsFullTextRegionContext(regionActive: Boolean): Boolean {
        val prog = parentPattern.prog()
        return regionActive
                && ((!anchoringBounds && prog.hasTextAnchor())
                || transparentBounds
                || (text != null && (regionEndsInsideSurrogatePair() || prog.hasGraphemeSemantics())))
    }

    private fun regionEndsInsideSurrogatePair(): Boolean {
        return regionEnd > 0 && regionEnd < this.textLength && !activeScanner().isCodePointBoundary(regionEnd)
    }

    /** Installs one independent input view for every opaque region operation.  */
    private fun useRegionInput(savedScanner: InputScanner?) {
        if (cachedRegionScanner == null || cachedRegionStart != regionStart || cachedRegionEnd != regionEnd) {
            val currentText = text
            cachedRegionScanner =
                if (currentText != null)
                    StringRegionInputScanner(currentText, regionStart, regionEnd)
                else
                    (savedScanner as Utf8InputScanner).slice(regionStart, regionEnd)
            cachedRegionStart = regionStart
            cachedRegionEnd = regionEnd
        }
        if (text != null) {
            // Raw String fast paths require zero-based storage; bounded paths use the scanner.
            text = null
        }
        textScanner = cachedRegionScanner
    }

    /**
     * Returns true when a stripped text anchor (`^`, `\A`, `$`, `\Z`, or
     * `\z`) cannot be satisfied at this region boundary because anchoring bounds are disabled.
     */
    private fun regionTextAnchorCannotMatch(): Boolean {
        val prog = parentPattern.prog()
        if (prog.anchorStart() && regionStart != 0) {
            return true
        }
        return prog.anchorEnd() && !regionEndCanSatisfyTextEnd(prog)
    }

    private fun regionEndCanSatisfyTextEnd(prog: Prog): Boolean {
        val textLength = this.textLength
        if (regionEnd == textLength) {
            return true
        }
        if (!prog.dollarAnchorEnd()) {
            return false
        }
        val dollarEndPos =
            activeScanner().trailingLineTerminatorStart(prog.dollarAnchorUnixLines(), textLength)
        return dollarEndPos >= regionStart && dollarEndPos <= regionEnd
    }

    private fun matchesFullTextRegion(): Boolean {
        capturesResolved = true
        fullTextRegionContext = true
        val prog = parentPattern.prog()
        val result =
            searchWithBitStateOrNfa(
                prog,
                activeScanner(),
                regionStart,
                regionStart,
                regionEnd,
                true,
                false,
                true,
                prog.numCaptures(),
                false,
                this.groups
            )
        return applyFullMatchResult(result)
    }

    private fun lookingAtFullTextRegion(): Boolean {
        capturesResolved = true
        fullTextRegionContext = true
        val prog = parentPattern.prog()
        val result =
            searchWithBitStateOrNfa(
                prog,
                activeScanner(),
                regionStart,
                regionStart,
                regionEnd,
                true,
                false,
                false,
                prog.numCaptures(),
                false,
                this.groups
            )
        return applyFullMatchResult(result)
    }

    private fun doFindFullTextRegion(): Boolean {
        if (searchFrom > regionEnd) {
            return applyFailedMatchResult()
        }
        capturesResolved = true
        fullTextRegionContext = true
        val prog = parentPattern.prog()
        if (prog.anchorStart() && searchFrom > regionStart) {
            return applyFailedMatchResult()
        }
        var effectiveStart = searchFrom
        if (!transparentBounds && text != null && prog.hasGraphemeClusterInstruction()) {
            val options = enginePathOptions()
            val rejection = parentPattern.findRejectPrefilter()
            if (options.charClassMatchFastPaths && rejection != null) {
                if (regionClassSearch == null) {
                    regionClassSearch = RegionClassSearch()
                }
                val strategy =
                    rejection.regionCharClassRejectionStrategy(
                        activeScanner(), searchFrom, regionEnd, regionClassSearch!!
                    )
                if (strategy != null) {
                    diagnosticParticipation(strategy, StrategyRole.REJECT_PREFILTER)
                    diagnosticBoundary(strategy)
                    return applyFailedMatchResult()
                }
            }
            val accelerator = parentPattern.stringStartAccelerator()
            if (options.startAcceleration
                && !prog.anchorStart() && usesScalarClassStartScan(accelerator)
                && startAccelerationAllowed(searchFrom - regionStart, accelerator!!.policy())
            ) {
                diagnosticParticipation(MatchStrategy.CHARACTER_CLASS, StrategyRole.START_ACCELERATION)
                val region =
                    StringRegionInputScanner(text!!, regionStart, regionEnd)
                val candidate: Int =
                    StringStartAccelerator.findNextCandidate(
                        accelerator, region, searchFrom - regionStart, prog.lineStartUnixLines()
                    )
                if (candidate < 0) {
                    diagnosticBoundary(MatchStrategy.CHARACTER_CLASS)
                    return applyFailedMatchResult()
                }
                chargeStartAcceleration(
                    searchFrom - regionStart, candidate, region.length, accelerator.policy(), false
                )
                effectiveStart = regionStart + candidate
            }
        }
        val result =
            searchWithBitStateOrNfa(
                prog,
                activeScanner(),
                effectiveStart,
                regionEnd,
                regionEnd,
                false,
                false,
                false,
                prog.numCaptures(),
                false,
                this.groups
            )
        return applyFullMatchResult(result)
    }

    private fun verifyEndAnchoredStart(scanner: InputScanner, start: Int): Boolean {
        val prog = parentPattern.prog()
        val exact =
            searchWithBitStateOrNfa(
                prog, scanner, start, start, scanner.length, true, false, false, 1
            )
        return exact != null
                && applyDeferredMatchResult(start, exact[1], prog.numCaptures(), true, false)
    }

    /**
     * Core find logic. Independent region views retain their scanner for deferred capture extraction
     * so assertions are replayed against the same bounded input.
     */
    private fun doFindCore(regionActive: Boolean): Boolean {
        val scanner = activeScanner()
        val independentRegion = regionActive && scanner is StringRegionInputScanner
        if (searchFrom > scanner.length) {
            return applyFailedMatchResult()
        }

        // Reset deferred-capture state; DFA sandwich path may set it to false.
        clearDeferredCaptureState()

        val prog = parentPattern.prog()
        val options = enginePathOptions()
        // Anchored start: if the pattern requires a match at the beginning of the text (e.g., ^
        // without MULTILINE, or \A), there can be no match starting after position 0 (or regionStart
        // when a region is active). Return false immediately to avoid the DFA matching at every
        // position because the compiler strips the anchor into prog.anchorStart().
        if (prog.anchorStart()) {
            if (searchFrom > 0) {
                return applyFailedMatchResult()
            }
            if (anchoredPrefixOrCharClassCannotMatch(searchFrom)) {
                val strat =
                    if (parentPattern.anchoredPrefix() != null)
                        MatchStrategy.LITERAL
                    else
                        MatchStrategy.CHARACTER_CLASS
                diagnosticParticipation(strat, StrategyRole.REJECT_PREFILTER)
                diagnosticBoundary(strat)
                return applyFailedMatchResult()
            }
        }

        var endAnchoredDeferred = false
        // End-anchored searches can identify candidate starts from the terminal endpoints. Do this
        // before prefix acceleration, which would otherwise scan an unrelated prefix on success.
        if (!regionActive || independentRegion) {
            val endAnchoredStart =
                parentPattern.endAnchoredSearchStart(scanner, searchFrom, true, diagnosticsAccumulator())
            endAnchoredDeferred = endAnchoredStart == Pattern.END_ANCHORED_SEARCH_DEFERRED
            if (endAnchoredStart == -1) {
                return applyFailedMatchResult()
            }
            if (endAnchoredStart >= 0 && verifyEndAnchoredStart(scanner, endAnchoredStart)) {
                return true
            }
        }

        val literalStartFirst =
            options.startAcceleration
                    && !prog.anchorStart() && scanner.length - searchFrom < Pattern.MAX_LITERAL_START_FIRST_LENGTH && hasLiteralStartAccelerator(
                scanner
            )
        // A wide-class leading expansion's literal search below fails exactly when this would reject.
        if (!literalStartFirst && !(options.startAcceleration && parentPattern.startSubsumesFindReject()) && rejectFindFrom(
                scanner,
                searchFrom,
                options
            )
        ) {
            return applyFailedMatchResult()
        }

        // Prefix acceleration: if the pattern has a start accelerator (literal, fixed-offset,
        // character-class, or line-anchor), skip ahead to candidate match positions. Literal-driven
        // accelerators run before findRejectPrefilter so a missing prefix rejects in one pass without
        // paying a separate infix prefilter scan, and a matching prefix narrows any remaining prefilter
        // check to [effectiveStart, length].
        var effectiveStart = searchFrom
        var literalPrefixCandidateStart = false
        var startPositionPreselected = false
        if (options.startAcceleration && !prog.anchorStart()) {
            if (scanner is Utf8InputScanner) {
                val accelerator = parentPattern.utf8StartAccelerator()
                if (accelerator != null && startAccelerationAllowed(searchFrom, accelerator.policy())) {
                    val policy = accelerator.policy()
                    val idx = findNextUtf8StartCandidate(accelerator, scanner, searchFrom)
                    if (idx < 0) {
                        return applyFailedMatchResult()
                    }
                    effectiveStart = idx
                    literalPrefixCandidateStart = policy.isExactMatchCandidate
                    startPositionPreselected = true
                }
            } else if (text != null || scanner is StringRegionInputScanner) {
                val accelerator = parentPattern.stringStartAccelerator()
                if (accelerator != null && startAccelerationAllowed(searchFrom, accelerator.policy())) {
                    val policy = accelerator.policy()
                    val idx = findNextStringStartCandidate(accelerator, searchFrom)
                    if (idx < 0) {
                        return applyFailedMatchResult()
                    }
                    effectiveStart = idx
                    literalPrefixCandidateStart = policy.isExactMatchCandidate
                    startPositionPreselected = true
                }
            }
        }
        if (literalStartFirst
            && startPositionPreselected
            && scanner.length - effectiveStart >= Pattern.MIN_REJECT_AFTER_START_REMAINING && rejectTailFrom(
                scanner,
                effectiveStart,
                options
            )
        ) {
            return applyFailedMatchResult()
        }

        // Retry a deferred reverse probe only after rejection and start acceleration narrow its work.
        if (endAnchoredDeferred) {
            val endAnchoredStart =
                parentPattern.endAnchoredSearchStart(
                    scanner, effectiveStart, true, diagnosticsAccumulator(), false
                )
            if (endAnchoredStart == -1) {
                return applyFailedMatchResult()
            }
            if (endAnchoredStart >= 0 && verifyEndAnchoredStart(scanner, endAnchoredStart)) {
                return true
            }
        }

        // Do not use OnePass as an unanchored find() producer. Even when a pattern is OnePass-eligible
        // for anchored matching, trying deterministic anchored matches at successive positions can
        // miss the leftmost start when a greedy leading repetition overlaps a later required literal.
        // The DFA/BitState/NFA pipeline below preserves leftmost find() semantics and remains linear.

        // Preserve capture preference for start-anchored patterns. Unanchored find() keeps DFA
        // rejection ahead of capture work, even after callers have consumed inner captures.
        // On the DFA path, successful small String searches reading captures pay for a reverse DFA pass
        // plus capture replay; see https://github.com/safere/safere/pull/1150 for the tradeoff.
        if (prog.anchorStart() && shouldPreferCaptureEngine(prog, scanner)) {
            val result =
                searchWithBitStateOrNfa(
                    prog,
                    scanner,
                    effectiveStart,
                    scanner.length,
                    scanner.length,
                    false,
                    false,
                    false,
                    prog.numCaptures(),
                    false,
                    this.groups
                )
            return applyFullMatchResult(result)
        }

        // Fast path: use cached DFA to check if a match exists in the remaining text.
        // Use longest=false for a quick existence check — this returns the earliest match end.
        val fwdResult: Dfa.SearchResult?
        if (!options.dfa || !parentPattern.forwardDfaSupported()) {
            fwdResult = null
        } else {
            diagnosticParticipation(MatchStrategy.DFA, StrategyRole.REJECT_PREFILTER)
            fwdResult =
                searchForwardDfa(
                    dfa(false),
                    scanner,
                    effectiveStart,
                    prog.anchorStart(),
                    false,
                    startPositionPreselected
                )
            if (fwdResult != null && !fwdResult.matched) {
                diagnosticBoundary(MatchStrategy.DFA)
                return applyFailedMatchResult()
            }
            if (fwdResult == null) {
                diagnosticDecision(
                    MatchStrategy.DFA, StrategyDisposition.FALLBACK, StrategyReason.DFA_BUDGET_EXCEEDED
                )
            }
        }

        // DFA sandwich (like RE2): the forward DFA found the earliest match end above. Now use the
        // reverse DFA to find the corresponding match start. For programs whose group-0 bounds are
        // reliable, that pair is authoritative: another anchored forward pass would only rediscover
        // the same earliest end. With lazy capture extraction, the sandwich returns group(0) without
        // running BitState/NFA, making it worthwhile even on the first find() call.
        //
        // The reverse DFA is lazily constructed on first use and cached for subsequent calls.
        //
        // Skip when the forward DFA detected an empty match (earlyEnd == effectiveStart): the
        // sandwich uses longest-match for the final forward pass, which would incorrectly expand an
        // empty match into a longer one for nullable patterns like (|a)*.
        //
        // Skip the reverse DFA phase when the pattern is anchored at the start — the match start is
        // already known to be effectiveStart, so the reverse scan is unnecessary.
        //
        // When the DFA's match start is unreliable (patterns with lazy quantifiers, bounded repeats,
        // anchors inside quantifiers, or alternation), the reverse DFA result is only authoritative if
        // it proves the match starts at effectiveStart. Lazy quantifiers can make a non-leftmost match
        // end earlier, causing the DFA to find the wrong start. Alternation can have the same effect:
        // when alternatives match at different start positions with different endpoints, the forward
        // DFA's earliest-end result may come from a non-leftmost match, and the reverse DFA from that
        // wrong endpoint cannot find the leftmost start. In those cases, fall through to the
        // BitState/NFA fallback which correctly handles all semantics.
        //
        // When the accepted start is authoritative, the sandwich still treats group(0) as deferred for
        // programs whose end may be wrong; resolveCaptures() corrects the end position using the
        // submatch engine.
        // Bounded String views use the same DFA result path as independent text. Deferred captures
        // are resolved against that view before restoring the original input and capture offsets.
        if (options.dfa
            && (!regionActive || independentRegion)
            && parentPattern.dfaGroupZeroReliable()
            && fwdResult != null && fwdResult.pos > effectiveStart
        ) {
            val earlyEnd = fwdResult.pos

            if (prog.anchorStart()) {
                // Anchored: match start is effectiveStart. Since it is start-anchored, the match end
                // is guaranteed to be earlyEnd. Avoid redundant third DFA search.
                diagnosticBoundary(MatchStrategy.DFA)
                return applyDeferredMatchResult(
                    effectiveStart,
                    earlyEnd,
                    prog.numCaptures(),
                    parentPattern.dfaGroupZeroReliable(),
                    false
                )
            } else {
                if (literalPrefixCandidateStart) {
                    // A literal prefix occurrence is a candidate match start, even when the prefix is
                    // preceded by zero-width assertions. Verify that candidate directly; if it matches,
                    // return it. Otherwise, fall through to the reverse DFA search below to find the correct
                    // start.
                    diagnosticParticipation(MatchStrategy.DFA, StrategyRole.CANDIDATE_VERIFICATION)
                    val fwdFirst =
                        searchForwardDfa(dfa(false), scanner, effectiveStart, true, false)
                    if (fwdFirst != null && fwdFirst.matched) {
                        val matchEnd = fwdFirst.pos
                        diagnosticBoundary(MatchStrategy.DFA)
                        return applyDeferredMatchResult(
                            effectiveStart,
                            matchEnd,
                            prog.numCaptures(),
                            parentPattern.dfaGroupZeroReliable(),
                            false
                        )
                    }
                }
                if (canUseReverseDfa()) {
                    val revDfa = reverseDfa()
                    if (revDfa != null) {
                        diagnosticParticipation(MatchStrategy.DFA, StrategyRole.CANDIDATE_VERIFICATION)
                        // Step 2: Reverse DFA backward from earliest match end to find match start.
                        val revResult =
                            searchReverseDfa(revDfa, scanner, earlyEnd, effectiveStart, true, true)
                        if (revResult != null && revResult.matched) {
                            var matchStart = revResult.pos
                            var reliableStart = !revResult.ambiguous

                            // For dollarAnchorEnd patterns, the forward DFA's earlyEnd is always textLen
                            // (it can't return early). But the correct leftmost match may end before the
                            // trailing line terminator. The reverse DFA from textLen only finds starts for
                            // matches ending AT textLen, potentially missing an earlier-starting match that
                            // ends before the trailing line terminator. Check all dollar positions.
                            if (prog.dollarAnchorEnd() && earlyEnd == scanner.length) {
                                val trailingStart =
                                    scanner.trailingLineTerminatorStart(prog.dollarAnchorUnixLines(), earlyEnd)
                                if (trailingStart >= effectiveStart) {
                                    val altRevResult =
                                        searchReverseDfa(revDfa, scanner, trailingStart, effectiveStart, true, true)
                                    if (altRevResult != null && altRevResult.matched
                                        && altRevResult.pos < matchStart
                                    ) {
                                        matchStart = altRevResult.pos
                                        reliableStart = !altRevResult.ambiguous
                                    }
                                }
                            }

                            if (!reliableStart) {
                                diagnosticDecision(
                                    MatchStrategy.DFA,
                                    StrategyDisposition.BYPASSED,
                                    StrategyReason.AUTHORITATIVE_BOUNDS_REQUIRED
                                )
                                matchStart = -1
                            }

                            if (matchStart >= 0) {
                                if (prog.hasTextAnchor()) {
                                    val exactEnd =
                                        searchForwardDfa(dfa(false), scanner, matchStart, true, false)
                                    if (exactEnd == null || !exactEnd.matched) {
                                        matchStart = -1
                                    } else {
                                        diagnosticBoundary(MatchStrategy.DFA)
                                        return applyDeferredMatchResult(
                                            matchStart,
                                            exactEnd.pos,
                                            prog.numCaptures(),
                                            parentPattern.dfaGroupZeroReliable(),
                                            false
                                        )
                                    }
                                }
                            }
                            if (matchStart >= 0) {
                                diagnosticBoundary(MatchStrategy.DFA)
                                return applyDeferredMatchResult(
                                    matchStart,
                                    earlyEnd,
                                    prog.numCaptures(),
                                    parentPattern.dfaGroupZeroReliable(),
                                    false
                                )
                            }
                        }
                        // If reverse DFA bails out, fall through to full search.
                    }
                }
            }
        }

        if (fwdResult != null && fwdResult.matched && prog.numLoopRegs() > 0) {
            diagnosticDecision(
                MatchStrategy.DFA,
                StrategyDisposition.BYPASSED,
                StrategyReason.EXACT_NULLABLE_LOOP_SEMANTICS_REQUIRED
            )
        }

        // Fallback: an exact search is already necessary. If an operation requires inner captures or
        // callers have consumed them before, collect them in this pass rather than replaying it on
        // group access. Otherwise group-zero-only loops can still defer capture tracking.
        //
        // Independent region views retain the same scanner for replay; full-context regions still
        // use eager extraction.
        val lazyFallbackCaptures =
            (!regionActive || independentRegion)
                    && !replacementNeedsCaptures && !parentPattern.innerCapturesObserved() && !prog.hasGraphemeSemantics() && options.lazyCaptureExtraction
                    && prog.numCaptures() <= MAX_LAZY_FALLBACK_SUBMATCHES
        val nsubmatch = if (lazyFallbackCaptures) 1 else prog.numCaptures()
        val result =
            searchWithBitStateOrNfa(
                prog,
                scanner,
                effectiveStart,
                scanner.length,
                scanner.length,
                false,
                false,
                false,
                nsubmatch,
                false,
                this.groups,
                if (fwdResult != null && fwdResult.matched) fwdResult.pos else -1
            )
        if (result == null) {
            return applyFailedMatchResult()
        }
        if (!lazyFallbackCaptures || prog.numCaptures() <= 1) {
            return applyFullMatchResult(result)
        } else {
            return applyDeferredMatchResult(result[0], result[1], prog.numCaptures(), true, false)
        }
    }

    /** The UTF-8 counterpart of [.findNextStringStartCandidate].  */
    private fun findNextUtf8StartCandidate(
        accelerator: Utf8StartAccelerator, scanner: Utf8InputScanner, fromIndex: Int
    ): Int {
        val policy = accelerator.policy()
        val strategy = policy.strategy
        if (strategy != null) {
            diagnosticParticipation(strategy, StrategyRole.START_ACCELERATION)
        }
        if (accelerator is Utf8StartAccelerator.LeadingExpansion
            && accelerator.canVerifyAtInner()
            && canUseForwardDfa()
        ) {
            // UTF-8 inner candidates are already code point boundaries.
            val innerMatch = accelerator.findInnerCandidate(scanner, fromIndex)
            if (innerMatch < 0) {
                if (strategy != null) {
                    diagnosticBoundary(strategy)
                }
                return -1
            }
            chargeStartAcceleration(fromIndex, innerMatch, scanner.length, policy, false)
            // As in findNextStringStartCandidate, an unanchored rejection at the inner candidate rules
            // out every expanded start.
            diagnosticParticipation(MatchStrategy.DFA, StrategyRole.CANDIDATE_VERIFICATION)
            val result =
                searchForwardDfa(dfa(false), scanner, innerMatch, false, false, true)
            if (result != null && !result.matched) {
                diagnosticBoundary(MatchStrategy.DFA)
                return -1
            }
            return accelerator.expandBackward(scanner, innerMatch, fromIndex)
        }
        val candidate: Int = Utf8StartAccelerator.findNextCandidate(accelerator, scanner, fromIndex)
        if (candidate < 0) {
            if (strategy != null) {
                diagnosticBoundary(strategy)
            }
        } else {
            chargeStartAcceleration(
                fromIndex,
                candidate,
                scanner.length,
                policy,
                accelerator is Utf8StartAccelerator.LeadingExpansion && accelerator.wideLeadingClass
            )
        }
        return candidate
    }

    /** Selects a start, rejecting nullable leading expansions at the inner candidate first.  */
    private fun findNextStringStartCandidate(accelerator: StringStartAccelerator, fromIndex: Int): Int {
        val prog = parentPattern.prog()
        val scanner = activeScanner()
        val startText = if (text != null) text else scanner as StringRegionInputScanner?
        val policy = accelerator.policy()
        val strategy = policy.strategy
        if (strategy != null) {
            diagnosticParticipation(strategy, StrategyRole.START_ACCELERATION)
        }
        if (accelerator is StringStartAccelerator.LeadingExpansion
            && accelerator.canVerifyAtInner()
            && canUseForwardDfa()
        ) {
            var innerMatch = accelerator.findInnerCandidate(startText, fromIndex, prog.lineStartUnixLines())
            // Literal scans use UTF-16 indices, but newly discovered scalar starts must be code point
            // boundaries. Filter before verification so rejected starts never replay a DFA suffix scan.
            while (innerMatch > fromIndex && !scanner.isCodePointBoundary(innerMatch)) {
                innerMatch = accelerator.findInnerCandidate(startText, innerMatch + 1, prog.lineStartUnixLines())
            }
            if (innerMatch < 0) {
                if (strategy != null) {
                    diagnosticBoundary(strategy)
                }
                return -1
            }
            chargeStartAcceleration(fromIndex, innerMatch, scanner.length, policy, false)
            // Zero repetitions are legal and there are no leading assertions. Every match from an
            // expanded start therefore has a suffix matching from an inner candidate. An unanchored
            // rejection here rules out all later starts without traversing the leading run backward.
            diagnosticParticipation(MatchStrategy.DFA, StrategyRole.CANDIDATE_VERIFICATION)
            val result =
                searchForwardDfa(dfa(false), scanner, innerMatch, false, false, true)
            if (result != null && !result.matched) {
                diagnosticBoundary(MatchStrategy.DFA)
                return -1
            }
            // A successful probe only proves existence; callers still need authoritative bounds and
            // captures. A DFA budget bailout also resumes from the conservatively expanded start.
            return accelerator.expandBackward(startText!!, innerMatch, fromIndex)
        }
        var candidate: Int =
            StringStartAccelerator.findNextCandidate(
                accelerator, startText, fromIndex, prog.lineStartUnixLines()
            )
        if (!prog.hasGraphemeClusterInstruction()) {
            // Literal scans can nominate a low surrogate inside a pair. Scalar searches may start
            // there only when the caller explicitly supplies that initial cursor.
            // Each rejected candidate advances the scan, so filtering remains linear in input length.
            while (candidate > fromIndex && !activeScanner().isCodePointBoundary(candidate)) {
                candidate =
                    StringStartAccelerator.findNextCandidate(
                        accelerator, startText, candidate + 1, prog.lineStartUnixLines()
                    )
            }
        }
        if (candidate < 0) {
            if (strategy != null) {
                diagnosticBoundary(strategy)
            }
        } else {
            chargeStartAcceleration(
                fromIndex,
                candidate,
                scanner.length,
                policy,
                accelerator is StringStartAccelerator.LeadingExpansion
                        && accelerator.wideLeadingClass
            )
        }
        return candidate
    }

    private fun matchUtf8KeywordAlternationAt(
        keywordAlternation: KeywordAlternation, pos: Int, ncap: Int
    ): Boolean {
        val scanner = activeScanner()
        if (pos >= scanner.length) {
            return applyFailedMatchResult()
        }
        val match = keywordAlternation.matchAt(scanner, pos)
        if (match < 0) {
            return applyFailedMatchResult()
        }
        val keywordStart: Int = KeywordAlternation.matchStart(match)
        val keywordEnd: Int = KeywordAlternation.matchEnd(match)
        val keywordGroups = IntArray(2 * ncap)
        Arrays.fill(keywordGroups, -1)
        keywordGroups[0] = if (keywordAlternation.greedyWholeInput) pos else keywordStart
        keywordGroups[1] = if (keywordAlternation.greedyWholeInput) scanner.length else keywordEnd
        if (keywordAlternation.captureGroup > 0) {
            val group = keywordAlternation.captureGroup
            keywordGroups[2 * group] = keywordStart
            keywordGroups[2 * group + 1] = keywordEnd
        }
        return applyFullMatchResult(keywordGroups)
    }

    private fun matchKeywordAlternationAt(
        keywordAlternation: KeywordAlternation, pos: Int, ncap: Int
    ): Boolean {
        if (pos >= text!!.length) {
            return applyFailedMatchResult()
        }
        if (WorkCounterConfig.ENABLED) {
            WorkCounter.record()
        }
        val ch: Char = text!![pos]
        if (ch.code < 128 && keywordAlternation.firstAsciiTable[Ascii.toLowerCase(ch).code]
            && isWordBoundaryAt(pos, keywordAlternation.unicodeWordBoundary)
        ) {
            for (keyword in keywordAlternation.keywords) {
                val end: Int = pos + keyword.length
                if (end <= text!!.length && Ascii.regionMatchesIgnoreCase(text!!, pos, keyword, keyword.length)
                    && isWordBoundaryAt(end, keywordAlternation.unicodeWordBoundary)
                ) {
                    val keywordGroups = IntArray(2 * ncap)
                    Arrays.fill(keywordGroups, -1)
                    keywordGroups[0] = pos
                    keywordGroups[1] = if (keywordAlternation.greedyWholeInput) text!!.length else end
                    if (keywordAlternation.captureGroup > 0) {
                        val group = keywordAlternation.captureGroup
                        keywordGroups[2 * group] = pos
                        keywordGroups[2 * group + 1] = end
                    }
                    return applyFullMatchResult(keywordGroups)
                }
            }
        }
        return applyFailedMatchResult()
    }

    private fun findUtf8KeywordAlternation(
        keywordAlternation: KeywordAlternation, startPos: Int, ncap: Int
    ): Boolean {
        val scanner = activeScanner()
        val matchStart = Math.max(0, startPos)
        val match = keywordAlternation.find(scanner, matchStart)
        if (match < 0) {
            return applyFailedMatchResult()
        }
        val keywordStart: Int = KeywordAlternation.matchStart(match)
        val keywordEnd: Int = KeywordAlternation.matchEnd(match)
        val keywordGroups = IntArray(2 * ncap)
        Arrays.fill(keywordGroups, -1)
        keywordGroups[0] = if (keywordAlternation.greedyWholeInput) matchStart else keywordStart
        keywordGroups[1] = if (keywordAlternation.greedyWholeInput) scanner.length else keywordEnd
        if (keywordAlternation.captureGroup > 0) {
            val group = keywordAlternation.captureGroup
            keywordGroups[2 * group] = keywordStart
            keywordGroups[2 * group + 1] = keywordEnd
        }
        return applyFullMatchResult(keywordGroups)
    }

    private fun findKeywordAlternation(
        keywordAlternation: KeywordAlternation, startPos: Int, ncap: Int
    ): Boolean {
        if (keywordAlternation.greedyWholeInput) {
            return findGreedyWholeInputKeywordAlternation(keywordAlternation, startPos, ncap)
        }
        val packed = findKeywordAlternationPacked(keywordAlternation, startPos)
        if (packed == -1L) {
            return applyFailedMatchResult()
        }
        val start: Int = unpackStart(packed)
        val end: Int = unpackEnd(packed)
        val keywordGroups = IntArray(2 * ncap)
        Arrays.fill(keywordGroups, -1)
        keywordGroups[0] = start
        keywordGroups[1] = end
        if (keywordAlternation.captureGroup > 0) {
            val group = keywordAlternation.captureGroup
            keywordGroups[2 * group] = start
            keywordGroups[2 * group + 1] = end
        }
        return applyFullMatchResult(keywordGroups)
    }

    /** Returns the first keyword match at or after `startPos` as packed positions, or -1.  */
    private fun findKeywordAlternationPacked(
        keywordAlternation: KeywordAlternation, startPos: Int
    ): Long {
        var i = Math.max(0, startPos)
        while (i < text!!.length) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            val ch: Char = text!![i]
            if (ch.code < 128 && keywordAlternation.firstAsciiTable[Ascii.toLowerCase(ch).code]
                && isWordBoundaryAt(i, keywordAlternation.unicodeWordBoundary)
            ) {
                for (keyword in keywordAlternation.keywords) {
                    val end: Int = i + keyword.length
                    if (end <= text!!.length && Ascii.regionMatchesIgnoreCase(text!!, i, keyword, keyword.length)
                        && isWordBoundaryAt(end, keywordAlternation.unicodeWordBoundary)
                    ) {
                        return packPositions(i, end)
                    }
                }
            }
            val cp = text!!.codePointAt(i)
            i += Character.charCount(cp) - 1
            i++
        }
        return -1L
    }

    private fun findGreedyWholeInputKeywordAlternation(
        keywordAlternation: KeywordAlternation, startPos: Int, ncap: Int
    ): Boolean {
        val matchStart = Math.max(0, startPos)
        for (i in text!!.length - 1 downTo matchStart) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            val ch: Char = text!![i]
            if (ch.code < 128 && keywordAlternation.firstAsciiTable[Ascii.toLowerCase(ch).code]
                && isWordBoundaryAt(i, keywordAlternation.unicodeWordBoundary)
            ) {
                for (keyword in keywordAlternation.keywords) {
                    val end: Int = i + keyword.length
                    if (end <= text!!.length && Ascii.regionMatchesIgnoreCase(text!!, i, keyword, keyword.length)
                        && isWordBoundaryAt(end, keywordAlternation.unicodeWordBoundary)
                    ) {
                        val keywordGroups = IntArray(2 * ncap)
                        Arrays.fill(keywordGroups, -1)
                        keywordGroups[0] = matchStart
                        keywordGroups[1] = text!!.length
                        if (keywordAlternation.captureGroup > 0) {
                            val group = keywordAlternation.captureGroup
                            keywordGroups[2 * group] = i
                            keywordGroups[2 * group + 1] = end
                        }
                        return applyFullMatchResult(keywordGroups)
                    }
                }
            }
        }
        return applyFailedMatchResult()
    }

    private fun isWordBoundaryAt(pos: Int, unicodeWordBoundary: Boolean): Boolean {
        val prevWord =
            pos > 0 && isBoundaryWordChar(text!!.codePointBefore(pos), unicodeWordBoundary)
        val nextWord =
            pos < text!!.length && isBoundaryWordChar(text!!.codePointAt(pos), unicodeWordBoundary)
        return prevWord != nextWord
    }

    private fun searchWithBitStateOrNfa(
        prog: Prog,
        text: String?,
        startPos: Int,
        searchLimit: Int,
        endPos: Int,
        anchored: Boolean,
        longest: Boolean,
        endMatch: Boolean,
        nsubmatch: Int,
        preserveOuterEmptyContext: Boolean,
        reuseGroups: IntArray?
    ): IntArray? {
        return searchWithBitStateOrNfa(
            prog,
            StringInputScanner(text!!),
            startPos,
            searchLimit,
            endPos,
            anchored,
            longest,
            endMatch,
            nsubmatch,
            preserveOuterEmptyContext,
            reuseGroups
        )
    }

    /**
     * Tries BitState first (for small texts), falls back to NFA. This is the final capture-extraction
     * step after DFA/OnePass have been tried or are not applicable.
     * 
     * @param prog the compiled program
     * @param text the full input scanner
     * @param startPos the input index at which to begin searching
     * @param searchLimit upper bound on positions where new candidate starts are tried. Use `text.length` for unbounded search.
     * @param endPos logical match end and ordinary-atom consumption limit. Use `text.length`
     * for unbounded search.
     * @param anchored whether the search is anchored at `startPos`
     * @param longest whether to find the longest match
     * @param endMatch whether the match must extend to `endPos`
     * @param nsubmatch number of submatch groups to track (including group 0)
     * @return submatch positions relative to `text`, or null if no match
     */
    private fun searchWithBitStateOrNfa(
        prog: Prog,
        text: InputScanner,
        startPos: Int,
        searchLimit: Int,
        endPos: Int,
        anchored: Boolean,
        longest: Boolean,
        endMatch: Boolean,
        nsubmatch: Int,
        preserveOuterEmptyContext: Boolean = false,
        reuseGroups: IntArray? = null
    ): IntArray? {
        return searchWithBitStateOrNfa(
            prog,
            text,
            startPos,
            searchLimit,
            endPos,
            anchored,
            longest,
            endMatch,
            nsubmatch,
            preserveOuterEmptyContext,
            reuseGroups,
            -1
        )
    }

    /**
     * Runs the exact search, with a bounded BitState attempt when eligible.
     * 
     * @param matchEndHint the forward DFA end from the exact fallback in `doFindCore`, used to
     * size speculative windows for nullable loops. The DFA search must use the same input, start
     * position, and region bounds. Pass `-1` when there is no hint.
     */
    private fun searchWithBitStateOrNfa(
        prog: Prog,
        text: InputScanner,
        startPos: Int,
        searchLimit: Int,
        endPos: Int,
        anchored: Boolean,
        longest: Boolean,
        endMatch: Boolean,
        nsubmatch: Int,
        preserveOuterEmptyContext: Boolean,
        reuseGroups: IntArray?,
        matchEndHint: Int
    ): IntArray? {
        // Use BitState on a bounded search range. Nullable-loop patterns on long inputs can try one
        // bounded window before the NFA: their progress checks prevent authoritative DFA bounds even
        // when the selected match is short. If any preferred path reaches the window edge, or the work
        // budget is exceeded, restart once with the NFA and the original bounds. Both the hint
        // limit and the bitmap limit apply to the window, in UTF-16 code units or UTF-8 bytes. Each
        // BMP CJK ideograph takes one UTF-16 code unit and three UTF-8 bytes, so the same label can
        // fit the String window and exceed the UTF-8 window. There is no sequence of growing-window
        // retries.
        // Two consecutive failures start an exponentially increasing, capped retry delay. Count only
        // eligible searches; a successful window or a reset/pattern/region change clears the delay.
        val maxBitStateLen: Int = BitState.maxTextSize(prog)
        val canUseBitState =
            enginePathOptions().bitState
                    && !fullTextRegionContext && !(prog.hasGraphemeSemantics() && !anchored) && !prog.hasGraphemeSemantics()
        val searchRange = endPos - startPos
        val windowEligible =
            canUseBitState
                    && maxBitStateLen >= BitState.SPECULATIVE_WINDOW_SIZE && searchRange > maxBitStateLen && prog.numLoopRegs() > 0 && !longest && !endMatch && !prog.anchorEnd() && !preserveOuterEmptyContext // Keep interior region ends on the established bounded-range engine path.
                    && endPos == text.length
        var speculate = false
        if (windowEligible) {
            if (windowSearchesUntilRetry == 0) {
                speculate = true
            } else {
                windowSearchesUntilRetry--
            }
        }
        if (canUseBitState && maxBitStateLen >= 0 && (searchRange <= maxBitStateLen || speculate)) {
            val anchoredEffective = anchored || prog.anchorStart()
            val endMatchEffective = endMatch || prog.anchorEnd()
            val ncap = 2 * Math.max(nsubmatch, 1)
            // Borrow from Pattern's thread-local cache on first use.
            releaseEnginesOwnedByOtherThread()
            if (cachedBitState == null && !bitStateBorrowed) {
                cachedBitState = Pattern.borrowBitState(threadCache())
                bitStateBorrowed = true
            }
            // One bounded attempt per search. Never retry with successively larger windows.
            var windowSize: Int = BitState.SPECULATIVE_WINDOW_SIZE
            val hintedLength = if (matchEndHint > startPos) matchEndHint - startPos else 0
            if (speculate && hintedLength <= Math.min(maxBitStateLen, BitState.MAX_HINTED_WINDOW_SIZE)) {
                // A DFA end is only a sizing hint: nullable progress can change the preferred match.
                // Keep a fresh matcher's worst-case speculative work bounded even when the bitmap
                // would allow a much larger window. Oversized hints keep the default window.
                windowSize = Math.max(windowSize, hintedLength)
            }
            val bitStateEnd = if (speculate) startPos + windowSize else endPos
            val bs: BitState =
                BitState.getOrCreate(
                    cachedBitState, prog, text, startPos, bitStateEnd, ncap, longest, endMatchEffective
                )
            var destBuf =
                if (reuseGroups != null && reuseGroups.size >= ncap) reuseGroups else bitStateResult
            if (destBuf == null || destBuf.size < ncap) {
                destBuf = IntArray(ncap)
            }
            if (destBuf != reuseGroups) {
                bitStateResult = destBuf
            }
            val result =
                if (speculate)
                    bs.doSearchWindow(startPos, searchLimit, anchoredEffective, destBuf)
                else
                    bs.doSearch(startPos, searchLimit, anchoredEffective, destBuf)
            cachedBitState = bs
            // Return to Pattern's cache for reuse by future Matchers.
            Pattern.returnBitState(threadCache(), bs)
            if (!bs.budgetExceeded() && (!speculate || result != null)) {
                if (speculate) {
                    // Reset fully so a run of short matches can use every window immediately. Alternating
                    // long and short matches may pay for two failed windows again after each success;
                    // this trades that bounded cost for faster recovery rather than decaying the delay.
                    resetWindowBackoff()
                }
                // A source $ permits BitState to stop before a trailing line terminator. An explicit
                // full-match request, including deferred capture extraction, must consume the whole range.
                // Let NFA FULL_MATCH choose the full-length path if BitState accepted a shorter one.
                if (!endMatch || result == null || result[1] == endPos) {
                    diagnosticExact(MatchStrategy.BIT_STATE, nsubmatch > 1 && result != null)
                    // BitState is complete: if it found no match, NFA won't either.
                    return result
                }
            } else {
                if (speculate) {
                    consecutiveWindowFailures =
                        Math.min(consecutiveWindowFailures + 1, MAX_CONSECUTIVE_WINDOW_FAILURES)
                    if (consecutiveWindowFailures == MAX_CONSECUTIVE_WINDOW_FAILURES) {
                        // Both factors are capped before doubling, so this arithmetic cannot overflow.
                        windowRetryDelay =
                            Math.min(
                                MAX_WINDOW_RETRY_DELAY,
                                Math.max(INITIAL_WINDOW_RETRY_DELAY, windowRetryDelay * 2)
                            )
                        windowSearchesUntilRetry = windowRetryDelay
                    }
                }
                diagnosticDecision(
                    MatchStrategy.BIT_STATE,
                    StrategyDisposition.FALLBACK,
                    if (bs.budgetExceeded())
                        StrategyReason.WORK_BUDGET_EXCEEDED
                    else
                        if (bs.windowExceeded())
                            StrategyReason.SPECULATIVE_WINDOW_EXCEEDED
                        else
                            StrategyReason.INPUT_TOO_LARGE
                )
            }
        } else if (canUseBitState && maxBitStateLen >= 0 && searchRange > maxBitStateLen) {
            diagnosticDecision(
                MatchStrategy.BIT_STATE,
                StrategyDisposition.BYPASSED,
                if (windowEligible) StrategyReason.SPECULATIVE_BACKOFF else StrategyReason.INPUT_TOO_LARGE
            )
        }

        // Fall back to the general NFA when BitState cannot be used or when capture semantics need
        // Pike NFA's priority model.
        val nfaAnchor = if (anchored) Nfa.Anchor.ANCHORED else Nfa.Anchor.UNANCHORED
        val nfaKind: Nfa.MatchKind?
        if (endMatch) {
            nfaKind = Nfa.MatchKind.FULL_MATCH
        } else if (longest) {
            nfaKind = Nfa.MatchKind.LONGEST_MATCH
        } else {
            nfaKind = Nfa.MatchKind.FIRST_MATCH
        }
        val result =
            searchNfa(
                prog,
                text,
                startPos,
                searchLimit,
                endPos,
                nsubmatch,
                nfaAnchor,
                nfaKind,
                preserveOuterEmptyContext,
                reuseGroups
            )
        diagnosticExact(MatchStrategy.NFA, nsubmatch > 1 && result != null)
        return result
    }

    private fun searchNfa(
        prog: Prog,
        scanner: InputScanner,
        startPos: Int,
        searchLimit: Int,
        endPos: Int,
        nsubmatch: Int,
        nfaAnchor: Nfa.Anchor?,
        nfaKind: Nfa.MatchKind?,
        preserveOuterEmptyContext: Boolean,
        reuseGroups: IntArray?
    ): IntArray? {
        var nsubmatch = nsubmatch
        if (prog.start() == 0) {
            return null
        }
        var anchored = (nfaAnchor == Nfa.Anchor.ANCHORED) || prog.anchorStart()
        val longestMode = (nfaKind != Nfa.MatchKind.FIRST_MATCH)
        var endmatch = prog.anchorEnd()

        if (nfaKind == Nfa.MatchKind.FULL_MATCH) {
            anchored = true
            endmatch = true
            if (nsubmatch == 0) {
                nsubmatch = 1
            }
        }

        // We always need at least capture[0..1] to track the match boundaries.
        val ncapture = 2 * Math.max(nsubmatch, 1)

        val graphemeRegionContext = fullTextRegionContext && prog.hasGraphemeSemantics()
        val consumeRegionStart = if (graphemeRegionContext && !transparentBounds) regionStart else 0

        // Independent input views carry their complete assertion context in scanner coordinates.
        // Only full-text region operations use the matcher's original region bounds.
        val assertionRegionStart = if (fullTextRegionContext) regionStart else 0
        val assertionRegionEnd = if (fullTextRegionContext) regionEnd else scanner.length
        val useOuterEmptyContext = fullTextRegionContext || preserveOuterEmptyContext
        val emptyContextEnd = if (preserveOuterEmptyContext) assertionRegionEnd else endPos
        val boundaryRegionStart = if (useOuterEmptyContext && !transparentBounds) assertionRegionStart else 0
        val boundaryEndPos =
            if (useOuterEmptyContext && !transparentBounds) emptyContextEnd else scanner.length
        val anchorEndPos =
            if (useOuterEmptyContext && !anchoringBounds && prog.anchorEnd())
                scanner.length
            else
                emptyContextEnd
        val emptyAnchorStartPos = if (useOuterEmptyContext && anchoringBounds) assertionRegionStart else 0
        val emptyAnchorEndPos =
            if (useOuterEmptyContext && !anchoringBounds) scanner.length else emptyContextEnd

        val context: EngineContext =
            EngineContext.create(
                prog,
                scanner,
                startPos,
                searchLimit,
                endPos,
                consumeRegionStart,
                boundaryRegionStart,
                boundaryEndPos,
                anchorEndPos,
                emptyAnchorStartPos,
                emptyAnchorEndPos,
                graphemeContextFor(prog, scanner)
            )

        releaseEnginesOwnedByOtherThread()
        if (cachedNfa == null && !nfaBorrowed) {
            cachedNfa = Pattern.borrowNfa(threadCache())
            nfaBorrowed = true
        }

        val nfa: Nfa = Nfa.getOrCreate(cachedNfa, prog, context, ncapture, longestMode, endmatch)
        val result = nfa.runSearch(anchored, nfaKind, nsubmatch, endPos, reuseGroups)
        cachedNfa = nfa
        Pattern.returnNfa(threadCache(), nfa)

        return result
    }

    // ---------------------------------------------------------------------------
    // Group access (MatchResult implementation)
    // ---------------------------------------------------------------------------
    /**
     * Returns the number of capturing groups in this matcher's pattern, not counting the implicit
     * group 0 for the full match.
     * 
     * @return the number of capturing groups
     */
    override fun groupCount(): Int {
        return parentPattern.numGroups()
    }

    /**
     * Returns the input subsequence matched by the previous match (equivalent to `group(0)`).
     * 
     * @return the matched subsequence
     * @throws IllegalStateException if no match has yet been attempted, or if the previous match
     * operation failed
     */
    override fun group(): String? {
        return group(0)
    }

    /**
     * Returns the input subsequence captured by the given group during the previous match operation.
     * 
     * @param group the index of a capturing group in this matcher's pattern
     * @return the subsequence captured by the group, or `null` if the group did not participate
     * in the match
     * @throws IllegalStateException if no match has yet been attempted, or if the previous match
     * operation failed
     * @throws IndexOutOfBoundsException if there is no capturing group with the given index
     */
    override fun group(group: Int): String? {
        val s = start(group)
        val e = end(group)
        if (s == -1) {
            return null
        }
        return text!!.substring(s, e)
    }

    /**
     * Returns the input subsequence captured by the given named group during the previous match.
     * 
     * @param name the name of a named-capturing group in this matcher's pattern
     * @return the subsequence captured by the named group, or `null` if the group did not
     * participate in the match
     * @throws IllegalStateException if no match has yet been attempted, or if the previous match
     * operation failed
     * @throws IllegalArgumentException if there is no capturing group with the given name
     */
    override fun group(name: String?): String? {
        return group(namedGroupIndex(name))
    }

    /**
     * Returns the start index of the previous match (equivalent to `start(0)`).
     * 
     * @return the index of the first character matched
     * @throws IllegalStateException if no match has yet been attempted, or if the previous match
     * operation failed
     */
    override fun start(): Int {
        return start(0)
    }

    /**
     * Returns the start index of the subsequence captured by the given group.
     * 
     * @param group the index of a capturing group
     * @return the start index, or `-1` if the group did not participate in the match
     * @throws IllegalStateException if no match has yet been attempted, or if the previous match
     * operation failed
     * @throws IndexOutOfBoundsException if there is no capturing group with the given index
     */
    override fun start(group: Int): Int {
        checkMatch()
        checkGroup(group)
        if (group != 0 || !groupZeroResolved) {
            if (group != 0) {
                recordInnerCaptureDemand()
            }
            resolveCaptures()
        }
        return groups[2 * group]
    }

    /**
     * Returns the offset after the last character of the previous match (equivalent to `end(0)`).
     * 
     * @return the offset after the last character matched
     * @throws IllegalStateException if no match has yet been attempted, or if the previous match
     * operation failed
     */
    override fun end(): Int {
        return end(0)
    }

    /**
     * Returns the offset after the last character of the subsequence captured by the given group.
     * 
     * @param group the index of a capturing group
     * @return the offset after the last character, or `-1` if the group did not participate
     * @throws IllegalStateException if no match has yet been attempted, or if the previous match
     * operation failed
     * @throws IndexOutOfBoundsException if there is no capturing group with the given index
     */
    override fun end(group: Int): Int {
        checkMatch()
        checkGroup(group)
        if (group != 0 || !groupZeroResolved) {
            if (group != 0) {
                recordInnerCaptureDemand()
            }
            resolveCaptures()
        }
        return groups[2 * group + 1]
    }

    /**
     * Returns the start index of the subsequence captured by the given named group.
     * 
     * @param name the name of a named-capturing group in this matcher's pattern
     * @return the start index, or `-1` if the group did not participate in the match
     * @throws IllegalStateException if no match has yet been attempted, or if the previous match
     * operation failed
     * @throws IllegalArgumentException if there is no capturing group with the given name
     */
    override fun start(name: String?): Int {
        return start(namedGroupIndex(name))
    }

    /**
     * Returns `true` if this matcher has a match.
     * 
     * @return `true` if this matcher has a match, `false` otherwise
     * @since 20
     */
    override fun hasMatch(): Boolean {
        return hasMatch
    }

    /**
     * Returns the offset after the last character of the subsequence captured by the given named
     * group.
     * 
     * @param name the name of a named-capturing group in this matcher's pattern
     * @return the offset after the last character, or `-1` if the group did not participate
     * @throws IllegalStateException if no match has yet been attempted, or if the previous match
     * operation failed
     * @throws IllegalArgumentException if there is no capturing group with the given name
     */
    override fun end(name: String?): Int {
        return end(namedGroupIndex(name))
    }

    /**
     * Replaces the first subsequence of the input that matches the pattern with the given replacement
     * string.
     * 
     * @param replacement the replacement string
     * @return the string with the first match replaced
     */
    fun replaceFirst(replacement: String?): String? {
        val operation = beginDiagnostics(MatchOperation.REPLACE_FIRST)
        try {
            val result = replaceFirstImpl(replacement)
            completeDiagnostics(operation, diagnosticMatchCount())
            return result
        } catch (e: RuntimeException) {
            abortDiagnostics(operation)
            throw e
        } catch (e: Error) {
            abortDiagnostics(operation)
            throw e
        }
    }

    private fun replaceFirstImpl(replacement: String?): String? {
        return replaceImpl(replacement, 1)
    }

    private fun replaceImpl(replacement: String?, limit: Int): String? {
        Objects.requireNonNull<String?>(replacement, "replacement")
        reset()
        if (!parentPattern.prog().anchorStart()) {
            // As in doFindCore, the end-anchored search runs before the whole-input reject prefilter: it
            // can reject from the end of the input, and when it proposes a start a match is likely, so
            // scanning the whole input for required literals would be wasted.
            var hasEndAnchoredCandidate = false
            // Keep non-end-anchored literal replacements on their scanner-free path.
            if (parentPattern.prog().anchorEnd()) {
                val endAnchoredStart =
                    parentPattern.endAnchoredSearchStart(
                        activeScanner(), searchFrom, true, diagnosticsAccumulator()
                    )
                if (endAnchoredStart == -1) {
                    return text
                }
                hasEndAnchoredCandidate = endAnchoredStart >= 0
            }
            val rejectPrefilter = parentPattern.findRejectPrefilter()
            if (!hasEndAnchoredCandidate && rejectPrefilter != null && text != null && (if (diagnosticsAccumulator() == null)
                    rejectPrefilter.canReject(text, null, searchFrom, enginePathOptions())
                else
                    rejectWithDiagnostics(
                        rejectPrefilter, text, null, searchFrom, enginePathOptions()
                    ))
            ) {
                return text
            }
        } else if (anchoredPrefixOrCharClassCannotMatch(searchFrom)) {
            // A start-anchored pattern can only match at searchFrom, so a failed anchored prefix or
            // character-class check decides the whole call. Without this arm such patterns get no
            // whole-input rejection at all: the unanchored branch above is skipped for them, and they
            // reach the replacement machinery only to allocate a template and a cursor and step the DFA
            // once before failing. Mirrors the equivalent checks in matchesCore and doFindCore.
            val strategy =
                if (parentPattern.anchoredPrefix() != null)
                    MatchStrategy.LITERAL
                else
                    MatchStrategy.CHARACTER_CLASS
            diagnosticParticipation(strategy, StrategyRole.REJECT_PREFILTER)
            diagnosticBoundary(strategy)
            return text
        }
        if (parentPattern.literalMatch() != null) {
            val literalResult = literalReplaceFastPath(replacement!!, limit)
            if (literalResult != null) {
                return literalResult
            }
        }

        if (parentPattern.matchDescriptor().charClassMatch != null
            || parentPattern.matchDescriptor().singleCharClass != null
        ) {
            val fastResult = charClassReplaceFastPath(replacement, limit)
            if (fastResult != null) {
                return fastResult
            }
        }

        if (this.isFullyAnchoredOnePassPattern) {
            val anchoredOnePassResult = replaceAnchoredOnePass(replacement, limit > 1)
            if (anchoredOnePassResult != null) {
                return anchoredOnePassResult
            }
        }

        val result = replaceDfaOptimized(replacement, limit)
        if (result != null) {
            return result
        }

        // An optimized attempt may have published earlier matches before a later DFA bailout.
        // Restart once so the authoritative fallback also replaces those earlier matches.
        reset()
        val template = LazyTemplate(replacement, groupCount())
        val savedRequirement = replacementNeedsCaptures
        replacementNeedsCaptures = template.needsCaptures()
        try {
            return replaceFallback(replacement!!, limit, template)
        } finally {
            replacementNeedsCaptures = savedRequirement
        }
    }

    private fun replaceFallback(replacement: String, limit: Int, template: LazyTemplate): String? {
        if (!find()) {
            return text
        }
        diagnosticIncrementMatchCount()
        val sb: StringBuilder = StringBuilder(text!!.length)
        if (limit == 1) {
            appendReplacement(sb, replacement)
            appendTail(sb)
            return sb.toString()
        }
        val compiledTemplate = template.get()
        val needsCaptures = template.needsCaptures()
        do {
            if (needsCaptures && !groupZeroResolved) {
                resolveCaptures()
            }
            val matchStart = if (groupZeroResolved) groups[0] else deferredMatchStart
            val matchEnd = if (groupZeroResolved) groups[1] else deferredMatchEnd
            sb.append(text, appendPos, matchStart)
            if (needsCaptures) {
                applyReplacementTemplate(sb, compiledTemplate)
            } else {
                groups[0] = matchStart
                groups[1] = matchEnd
                applyReplacementTemplate(sb, compiledTemplate)
            }
            appendPos = matchEnd
        } while (findAndRecordReplacementMatch())
        appendTail(sb)
        return sb.toString()
    }

    private val isFullyAnchoredOnePassPattern: Boolean
        /**
         * Returns whether the pattern itself admits [.replaceAnchoredOnePass]: OnePass-eligible and
         * anchored at both ends. These are compile-time properties, so `replaceImpl` checks them
         * before calling in and `replaceAnchoredOnePass` only checks the per-call conditions.
         */
        get() {
            val prog = parentPattern.prog()
            return parentPattern.canOnePassFind()
                    && prog.anchorStart()
                    && (prog.anchorEnd() || prog.dollarAnchorEnd())
        }

    /** Callers must first check [.isFullyAnchoredOnePassPattern].  */
    private fun replaceAnchoredOnePass(replacement: String?, replaceAll: Boolean): String? {
        val regionActive = (regionStart != 0 || regionEnd != text!!.length)
        if (regionActive || searchFrom != 0) {
            return null
        }
        if (terminalPrefilterRejects(0)) {
            applyFailedMatchResult()
            return text
        }
        val template = LazyTemplate(replacement, groupCount())
        val requiresCaptures = template.needsCaptures()
        if (text!!.length > onePassTextLimit(requiresCaptures)) {
            return null
        }

        val numCaptures = parentPattern.prog().numCaptures()
        if (groups == null || groups.size < 2 * numCaptures) {
            groups = IntArray(2 * numCaptures)
        }
        Arrays.fill(groups, -1)

        diagnosticParticipation(MatchStrategy.ONE_PASS, StrategyRole.CANDIDATE_VERIFICATION)

        val scanner = activeScanner()
        val result =
            parentPattern.onePass()!!.search(scanner, 0, text!!.length, false, numCaptures, groups)

        if (result == null) {
            diagnosticBoundary(MatchStrategy.ONE_PASS)
            applyFailedMatchResult()
            return text
        }
        diagnosticBoundary(MatchStrategy.ONE_PASS)
        if (parentPattern.numGroups() > 0) {
            diagnosticCapture(MatchStrategy.ONE_PASS)
        }
        diagnosticIncrementMatchCount()

        val matchStart = result[0]
        val matchEnd = result[1]
        applyFullMatchResult(result)

        val sb: StringBuilder = StringBuilder(text!!.length)
        sb.append(text, 0, matchStart)
        applyReplacementTemplate(sb, template.get())
        sb.append(text, matchEnd, text!!.length)
        appendPos = matchEnd
        if (replaceAll) {
            applyFailedMatchResult()
            if (matchStart == matchEnd) {
                searchFrom = if (matchEnd < regionEnd) matchEnd + 1 else regionEnd + 1
                findExhaustedAfterTerminalEmptyMatch = matchEnd >= regionEnd
                if (findExhaustedAfterTerminalEmptyMatch) {
                    diagnosticIntentionalDivergence(IntentionalDivergence.EXHAUSTED_EMPTY_MATCH)
                }
            } else {
                searchFrom = matchEnd
            }
        }
        return sb.toString()
    }

    private fun replaceDfaOptimized(replacement: String?, limit: Int): String? {
        val regionActive = (regionStart != 0 || regionEnd != text!!.length)
        if (!canUseForwardDfa() || !parentPattern.dfaGroupZeroReliable() // End-anchored patterns that are not start-anchored match at most once and benefit from
            // find()'s reverse-first DFA rejection.
            || (parentPattern.prog().anchorEnd() && !parentPattern.prog().anchorStart())
            || parentPattern.matchDescriptor().hasFindFastPath()
            || parentPattern.astAnalysis().hasNullableAlt
            || regionActive
        ) {
            return null
        }

        val isStartAnchored = parentPattern.prog().anchorStart()

        val prefix = parentPattern.prefix()
        val foldCase = parentPattern.prefixFoldCase()
        val hasStartAcceleration =
            enginePathOptions().startAcceleration && prefix != null && !isStartAnchored
        if (hasStartAcceleration) {
            // The literal prefix also accelerates later iterations of findNextDfaMatch.
            diagnosticParticipation(MatchStrategy.LITERAL, StrategyRole.START_ACCELERATION)
        }

        val fwdDfa = dfa(false)
        if (fwdDfa == null) {
            return null
        }

        val cursor = DfaMatchCursor(searchFrom)
        var matchResult =
            findNextDfaMatch(fwdDfa, isStartAnchored, prefix, foldCase, hasStartAcceleration, cursor)

        if (matchResult < 0) {
            return null
        }
        if (matchResult == 0) {
            return text
        }
        diagnosticBoundary(MatchStrategy.DFA)

        val prog = parentPattern.prog()
        val numCaptures = prog.numCaptures()
        if (groups == null || groups.size < 2 * numCaptures) {
            groups = IntArray(2 * numCaptures)
        }
        applyDeferredMatchResult(matchOffsets!![0], matchOffsets!![1], numCaptures, true, false)

        val template = LazyTemplate(replacement, groupCount())
        val compiledTemplate = template.get()

        val needsCaptures = template.needsCaptures()
        if (needsCaptures) {
            recordInnerCaptureDemand()
        }
        val useOnePass = needsCaptures && parentPattern.canOnePassSubmatch()

        val textLen: Int = text!!.length
        val sb = StringBuilder(textLen)
        var builderAppendPos = searchFrom
        var firstMatchStart = -1
        var firstMatchEnd = -1
        var matchesFound = 0

        while (matchResult == 1 && matchesFound < limit) {
            val matchStart = matchOffsets!![0]
            val matchEnd = matchOffsets!![1]
            if (matchesFound == 0) {
                firstMatchStart = matchStart
                firstMatchEnd = matchEnd
            }

            groups[0] = matchStart
            groups[1] = matchEnd
            if (needsCaptures) {
                Arrays.fill(groups, 2, groups.size, -1)
                var resultGroups: IntArray?
                if (useOnePass) {
                    diagnosticCapture(MatchStrategy.ONE_PASS)
                    resultGroups =
                        parentPattern
                            .onePass()!!
                            .search(text, matchStart, matchEnd, false, numCaptures, groups)
                } else {
                    val savedCaptureSearch = diagnosticCaptureSearch
                    diagnosticCaptureSearch = true
                    try {
                        resultGroups =
                            searchWithBitStateOrNfa(
                                prog,
                                text,
                                matchStart,
                                matchEnd,
                                matchEnd,
                                true,
                                false,
                                false,
                                numCaptures,
                                true,
                                groups
                            )
                    } finally {
                        diagnosticCaptureSearch = savedCaptureSearch
                    }
                }
                if (resultGroups != null) {
                    System.arraycopy(resultGroups, 0, groups, 0, groups.size)
                }
                capturesResolved = true
                groupZeroResolved = true
            }

            sb.append(text, builderAppendPos, matchStart)
            this.resultStatus = ResultStatus.MATCHED
            applyReplacementTemplate(sb, compiledTemplate)
            builderAppendPos = matchEnd

            cursor.pos = matchEnd
            matchesFound++
            if (matchStart == matchEnd) {
                if (cursor.pos >= regionEnd) {
                    findExhaustedAfterTerminalEmptyMatch = true
                    diagnosticIntentionalDivergence(IntentionalDivergence.EXHAUSTED_EMPTY_MATCH)
                    break
                }
                cursor.pos++
            }
            if (matchesFound < limit) {
                matchResult =
                    findNextDfaMatch(
                        fwdDfa, isStartAnchored, prefix, foldCase, hasStartAcceleration, cursor
                    )
                if (matchResult < 0) {
                    return null
                }
            }
        }
        sb.append(text, builderAppendPos, textLen)

        if (limit == 1) {
            applyDeferredMatchResult(
                firstMatchStart, firstMatchEnd, parentPattern.prog().numCaptures(), true, false
            )
            this.appendPos = firstMatchEnd
        } else {
            applyFailedMatchResult()
            this.appendPos = textLen
            this.searchFrom = textLen
        }
        diagnosticBoundary(MatchStrategy.DFA)
        val accumulator = diagnosticsAccumulator()
        if (accumulator != null) {
            accumulator.matchCount(matchesFound)
        }
        return sb.toString()
    }

    private fun findNextDfaMatch(
        fwdDfa: Dfa,
        isStartAnchored: Boolean,
        prefix: String?,
        foldCase: Boolean,
        hasStartAcceleration: Boolean,
        cursor: DfaMatchCursor
    ): Int {
        val scanner = activeScanner()
        val textLen = scanner.length
        var pos = cursor.pos

        while (pos <= textLen) {
            if (parentPattern.prog().anchorStart() && pos > 0) {
                break
            }
            var startPositionPreselected = false
            // Every replacement search needs the same candidate verification as find(), including a
            // failing candidate after an earlier match. Keep one backoff budget across the sequence.
            // With a literal prefix, the prefix scan below already finds the next candidate, so only a
            // leading expansion, whose inner candidate can be verified, needs the start accelerator.
            if (enginePathOptions().startAcceleration && !isStartAnchored) {
                val accelerator = parentPattern.stringStartAccelerator()
                if (accelerator != null && (!hasStartAcceleration || canVerifyLeadingExpansion(accelerator))
                    && startAccelerationAllowed(pos, accelerator.policy())
                ) {
                    val candidate = findNextStringStartCandidate(accelerator, pos)
                    if (candidate < 0) {
                        break
                    }
                    pos = candidate
                    startPositionPreselected = true
                }
            }
            if (hasStartAcceleration && pos < textLen && text != null) {
                // Literal prefixes never contain surrogate code units (MultiAnchorCompiler drops literals
                // with surrogate runes), so a prefix match always starts on a code point boundary.
                val literalPrefix = prefix!!
                val idx =
                    if (foldCase) indexOfIgnoreCase(text!!, literalPrefix, pos)
                    else text!!.indexOf(literalPrefix, pos)
                if (idx < 0) {
                    diagnosticBoundary(MatchStrategy.LITERAL)
                    break
                }
                pos = idx
            }

            diagnosticParticipation(MatchStrategy.DFA, StrategyRole.CANDIDATE_VERIFICATION)
            val fwdResult =
                searchForwardDfa(
                    fwdDfa,
                    scanner,
                    pos,
                    isStartAnchored,
                    false,
                    hasStartAcceleration || startPositionPreselected
                )
            if (fwdResult == null) {
                return -1
            }
            if (!fwdResult.matched) {
                diagnosticBoundary(MatchStrategy.DFA)
                break
            }

            val earlyEnd = fwdResult.pos
            if (earlyEnd <= pos) {
                if (matchOffsets == null) {
                    matchOffsets = IntArray(2)
                }
                matchOffsets!![0] = pos
                matchOffsets!![1] = pos
                return 1
            }

            val matchStart: Int
            val matchEnd: Int
            if (isStartAnchored) {
                matchStart = pos
                matchEnd = earlyEnd
            } else if (hasStartAcceleration) {
                val fwdFirst = searchForwardDfa(fwdDfa, scanner, pos, true, false)
                if (fwdFirst != null && fwdFirst.matched) {
                    matchStart = pos
                    matchEnd = fwdFirst.pos
                } else {
                    if (cursor.revDfa == null) {
                        if (!canUseReverseDfa()) {
                            return -1
                        }
                        cursor.revDfa = reverseDfa()
                        if (cursor.revDfa == null) {
                            return -1
                        }
                    }
                    val revResult =
                        searchReverseDfa(cursor.revDfa!!, scanner, earlyEnd, pos, true, true)
                    if (revResult == null || !revResult.matched || revResult.ambiguous) {
                        if (revResult != null && revResult.ambiguous) {
                            diagnosticDecision(
                                MatchStrategy.DFA,
                                StrategyDisposition.BYPASSED,
                                StrategyReason.AUTHORITATIVE_BOUNDS_REQUIRED
                            )
                        }
                        return -1
                    }
                    matchStart = revResult.pos
                    matchEnd = earlyEnd
                }
            } else {
                if (cursor.revDfa == null) {
                    if (!canUseReverseDfa()) {
                        return -1
                    }
                    cursor.revDfa = reverseDfa()
                    if (cursor.revDfa == null) {
                        return -1
                    }
                }
                val revResult =
                    searchReverseDfa(cursor.revDfa!!, scanner, earlyEnd, pos, true, true)
                if (revResult == null || !revResult.matched || revResult.ambiguous) {
                    if (revResult != null && revResult.ambiguous) {
                        diagnosticDecision(
                            MatchStrategy.DFA,
                            StrategyDisposition.BYPASSED,
                            StrategyReason.AUTHORITATIVE_BOUNDS_REQUIRED
                        )
                    }
                    return -1
                }
                matchStart = revResult.pos
                matchEnd = earlyEnd
            }

            if (matchOffsets == null) {
                matchOffsets = IntArray(2)
            }
            matchOffsets!![0] = matchStart
            matchOffsets!![1] = matchEnd
            return 1
        }
        return 0
    }

    /**
     * Replaces the first subsequence of the input that matches the pattern with the result of
     * applying the given replacer function to the match result. The replacer function is called with
     * the match result of the first match.
     * 
     * @param replacer a function that produces a replacement string from a match result
     * @return the string with the first match replaced
     * @throws NullPointerException if the replacer function is null
     */
    fun replaceFirst(replacer: Function<MatchResult?, String?>?): String? {
        val operation = beginDiagnostics(MatchOperation.REPLACE_FIRST)
        try {
            val result = replaceFirstFunctionImpl(replacer)
            completeDiagnostics(operation, diagnosticMatchCount())
            return result
        } catch (e: RuntimeException) {
            abortDiagnostics(operation)
            throw e
        } catch (e: Error) {
            abortDiagnostics(operation)
            throw e
        }
    }

    private fun replaceFirstFunctionImpl(replacer: Function<MatchResult?, String?>?): String? {
        Objects.requireNonNull<Function<MatchResult?, String?>?>(replacer, "replacer")
        reset()
        if (!find()) {
            return text
        }
        diagnosticIncrementMatchCount()
        val sb: StringBuilder = StringBuilder(text!!.length)
        val expectedModCount = modCount
        val replacement = Objects.requireNonNull<String>(replacer!!.apply(toMatchResult()))
        checkConcurrentModification(expectedModCount)
        appendReplacement(sb, replacement)
        appendTail(sb)
        return sb.toString()
    }

    /**
     * Replaces every subsequence of the input that matches the pattern with the given replacement
     * string.
     * 
     * @param replacement the replacement string
     * @return the string with all matches replaced
     */
    fun replaceAll(replacement: String?): String? {
        val operation = beginDiagnostics(MatchOperation.REPLACE_ALL)
        if (operation == null) {
            return replaceAllImpl(replacement)
        }
        try {
            val result = replaceAllImpl(replacement)
            completeDiagnostics(operation, diagnosticMatchCount())
            return result
        } catch (e: RuntimeException) {
            abortDiagnostics(operation)
            throw e
        } catch (e: Error) {
            abortDiagnostics(operation)
            throw e
        }
    }

    private fun replaceAllImpl(replacement: String?): String? {
        return replaceImpl(replacement, Integer.MAX_VALUE)
    }

    private fun findAndRecordReplacementMatch(): Boolean {
        val found = find()
        if (found) {
            diagnosticIncrementMatchCount()
        }
        return found
    }

    /**
     * Replaces every subsequence of the input that matches the pattern with the result of applying
     * the given replacer function to the match result. The replacer function is called for each
     * match, and the result is used as the replacement string.
     * 
     * @param replacer a function that produces a replacement string from a match result
     * @return the string with all matches replaced
     * @throws NullPointerException if the replacer function is null
     */
    fun replaceAll(replacer: Function<MatchResult?, String?>?): String? {
        val operation = beginDiagnostics(MatchOperation.REPLACE_ALL)
        try {
            val result = replaceAllFunctionImpl(replacer)
            completeDiagnostics(operation, diagnosticMatchCount())
            return result
        } catch (e: RuntimeException) {
            abortDiagnostics(operation)
            throw e
        } catch (e: Error) {
            abortDiagnostics(operation)
            throw e
        }
    }

    private fun replaceAllFunctionImpl(replacer: Function<MatchResult?, String?>?): String? {
        Objects.requireNonNull<Function<MatchResult?, String?>?>(replacer, "replacer")
        reset()
        if (!find()) {
            return text
        }
        diagnosticIncrementMatchCount()
        val sb: StringBuilder = StringBuilder(text!!.length)
        do {
            val expectedModCount = modCount
            val replacement = Objects.requireNonNull<String>(replacer!!.apply(toMatchResult()))
            checkConcurrentModification(expectedModCount)
            appendReplacement(sb, replacement)
        } while (findAndRecordReplacementMatch())
        appendTail(sb)
        return sb.toString()
    }

    private fun literalReplaceFastPath(replacement: String, limit: Int): String? {
        if (!enginePathOptions().literalFastPaths) {
            return null
        }

        val literal = parentPattern.literalMatch()
        if (literal == null || literal.isEmpty() || text == null) {
            return null
        }

        val simpleReplacement: Boolean = isSimpleReplacement(replacement)
        var template: LazyTemplate? = null
        if (!simpleReplacement) {
            template = LazyTemplate(replacement, groupCount())
            if (template.needsCaptures()) {
                return null // Cannot handle replacements that reference inner captures yet
            }
        }

        val activeDiagnostics = diagnosticOperation
        val accumulator =
            if (activeDiagnostics == null) null else activeDiagnostics.accumulator
        if (accumulator != null) {
            accumulator.participate(MatchStrategy.LITERAL, StrategyRole.CANDIDATE_VERIFICATION)
        }

        val isStartAnchored = parentPattern.prog().anchorStart()
        if (isStartAnchored) {
            if (searchFrom > 0 || !literalRegionMatches(literal, 0, literal.length)) {
                if (accumulator != null) {
                    accumulator.boundary(MatchStrategy.LITERAL)
                }
                applyFailedMatchResult()
                return text
            }
            val matchStart = 0
            val matchEnd: Int = literal.length
            val sb: StringBuilder = StringBuilder(text!!.length)
            if (!simpleReplacement) {
                applyGroupZeroMatchResult(matchStart, matchEnd)
                val compiledTemplate = template!!.get()
                groups[0] = matchStart
                groups[1] = matchEnd
                applyReplacementTemplate(sb, compiledTemplate)
            } else {
                sb.append(replacement)
            }
            val appendPosition = matchEnd
            this.appendPos = appendPosition
            sb.append(text, appendPosition, text!!.length)
            if (limit == 1) {
                if (groupCount() == 0) {
                    applyGroupZeroMatchResult(matchStart, matchEnd)
                } else {
                    applyDeferredMatchResult(
                        matchStart, matchEnd, parentPattern.prog().numCaptures(), true, false
                    )
                    resolveCaptures()
                }
            } else {
                this.searchFrom = regionEnd
                applyFailedMatchResult()
            }
            if (accumulator != null) {
                accumulator.boundary(MatchStrategy.LITERAL)
                accumulator.matchCount(1)
            }
            return sb.toString()
        }

        val foldCase = parentPattern.literalFoldCase()
        var anchorOffset = 0
        var anchorLow = 0.toChar()
        var anchorHigh = 0.toChar()
        var classHashChain: ClassHashChain? = null
        if (foldCase) {
            val runner = parentPattern.preparedMatchRunner(false)
            if (runner is LiteralPreparedRunner) {
                anchorOffset = runner.anchorOffset()
                anchorLow = runner.anchorLow()
                anchorHigh = runner.anchorHigh()
                classHashChain = runner.classHashChain()
            } else {
                val literalLen: Int = literal.length
                if (literalLen == 1) {
                    val anchor: Char = literal[0]
                    anchorLow = Ascii.toLowerCase(anchor)
                    anchorHigh = Ascii.toUpperCase(anchor)
                } else if (literalLen > 1) {
                    anchorOffset = RarityOracle.rarestAsciiOffset(literal, literalLen, true)
                    val anchor: Char = literal[anchorOffset]
                    anchorLow = Ascii.toLowerCase(anchor)
                    anchorHigh = Ascii.toUpperCase(anchor)
                }
                val descriptor = parentPattern.matchDescriptor()
                classHashChain = if (descriptor != null) descriptor.classHashChain else null
            }
        }

        var sb: StringBuilder? = null
        var appendPosition = 0
        var searchFrom = 0
        var matchStart =
            if (foldCase)
                indexOfIgnoreCase(
                    text!!, literal, anchorOffset, anchorLow, anchorHigh, classHashChain, searchFrom
                )
            else
                indexOfReplacementLiteral(literal, searchFrom)
        if (matchStart == -1) {
            if (accumulator != null) {
                accumulator.boundary(MatchStrategy.LITERAL)
            }
            applyFailedMatchResult()
            return text
        }
        var matchesFound = 0

        var firstMatchStart = -1
        var firstMatchEnd = -1

        var compiledTemplate: Array<ReplacementSegment>? = null
        do {
            if (sb == null) {
                sb = StringBuilder(text!!.length)
            }
            sb!!.append(text, appendPosition, matchStart)

            if (matchesFound == 0) {
                firstMatchStart = matchStart
                firstMatchEnd = matchStart + literal.length
                if (!simpleReplacement) {
                    applyDeferredMatchResult(
                        firstMatchStart, firstMatchEnd, parentPattern.prog().numCaptures(), true, false
                    )
                    if (template == null) {
                        template = LazyTemplate(replacement, groupCount())
                    }
                    compiledTemplate = template!!.get()
                }
            }

            if (simpleReplacement) {
                sb.append(replacement)
            } else {
                groups[0] = matchStart
                groups[1] = matchStart + literal.length
                applyReplacementTemplate(sb, compiledTemplate!!)
            }

            appendPosition = matchStart + literal.length
            searchFrom = appendPosition
            matchesFound++
            if (matchesFound >= limit) {
                break
            }
            matchStart =
                if (foldCase)
                    indexOfIgnoreCase(
                        text!!, literal, anchorOffset, anchorLow, anchorHigh, classHashChain, searchFrom
                    )
                else
                    indexOfReplacementLiteral(literal, searchFrom)
        } while (matchStart != -1)

        if (sb == null) {
            if (accumulator != null) {
                accumulator.boundary(MatchStrategy.LITERAL)
            }
            applyFailedMatchResult()
            return text
        }

        this.appendPos = appendPosition
        sb.append(text, appendPosition, text!!.length)

        if (limit == 1) {
            if (groupCount() == 0) {
                applyGroupZeroMatchResult(firstMatchStart, firstMatchEnd)
            } else {
                applyDeferredMatchResult(
                    firstMatchStart, firstMatchEnd, parentPattern.prog().numCaptures(), true, false
                )
            }
        } else {
            this.searchFrom = regionEnd
            applyFailedMatchResult()
        }

        if (accumulator != null) {
            accumulator.boundary(MatchStrategy.LITERAL)
            accumulator.matchCount(matchesFound)
        }

        return sb.toString()
    }

    private fun indexOfReplacementLiteral(literal: String, fromIndex: Int): Int {
        val matchStart = text!!.indexOf(literal, fromIndex)
        if (WorkCounterConfig.ENABLED) {
            val examinedEnd: Int = if (matchStart >= 0) matchStart + literal.length else text!!.length
            WorkCounter.record(Math.max(0, examinedEnd - fromIndex).toLong())
        }
        return matchStart
    }

    private fun charClassReplaceFastPath(replacement: String?, limit: Int): String? {
        val ccMatch = parentPattern.matchDescriptor().charClassMatch
        // A pattern that is exactly one character class matches one code point at a time.
        val single =
            if (ccMatch == null && !parentPattern.prog().anchorStart())
                parentPattern.matchDescriptor().singleCharClass
            else
                null
        if (!enginePathOptions().charClassReplacementFastPath || (ccMatch == null && single == null)
            || parentPattern.astAnalysis().hasLazy
        ) {
            return null
        }
        val activeDiagnostics = diagnosticOperation
        val accumulator =
            if (activeDiagnostics == null) null else activeDiagnostics.accumulator
        if (accumulator != null) {
            accumulator.participate(MatchStrategy.CHARACTER_CLASS, StrategyRole.CANDIDATE_VERIFICATION)
        }
        if (ccMatch != null && ccMatch.allowEmpty) {
            return nullableCharClassReplaceFastPath(
                LazyTemplate(replacement, groupCount()), limit, ccMatch
            )
        }
        var repText: String? = null
        val textLen: Int = text!!.length
        var pos = searchFrom
        var appendPos = searchFrom
        var matchesFound = 0
        var sb: StringBuilder? = null
        val ranges = if (ccMatch != null) ccMatch.ranges else single!!.ranges
        val b0 = if (ccMatch != null) ccMatch.bitmap0 else single!!.bitmap0
        val b1 = if (ccMatch != null) ccMatch.bitmap1 else single!!.bitmap1
        val asciiScanner = if (single != null && single.isAscii) activeScanner() else null

        var firstMatchStart = -1
        var firstMatchEnd = -1

        while (pos < textLen && matchesFound < limit) {
            var matchStart = -1
            if (asciiScanner != null) {
                matchStart = asciiScanner.indexOfCharClass(single, pos)
                pos = if (matchStart < 0) textLen else matchStart
            } else {
                while (pos < textLen) {
                    val cp = text!!.codePointAt(pos)
                    if (charClassContains(ranges!!, b0, b1, cp)) {
                        matchStart = pos
                        break
                    }
                    pos += Character.charCount(cp)
                }
            }

            if (matchStart == -1) {
                break
            }

            pos += Character.charCount(text!!.codePointAt(pos))
            while (single == null && pos < textLen) {
                val cp = text!!.codePointAt(pos)
                if (!charClassContains(ranges!!, b0, b1, cp)) {
                    break
                }
                pos += Character.charCount(cp)
            }
            val matchEnd = pos

            if (matchesFound == 0) {
                firstMatchStart = matchStart
                firstMatchEnd = matchEnd
                val compiledTemplate: Array<ReplacementSegment>
                val template = LazyTemplate(replacement, groupCount())
                try {
                    compiledTemplate = template.get()
                } catch (e: IllegalArgumentException) {
                    applyFullMatchResult(intArrayOf(firstMatchStart, firstMatchEnd))
                    throw e
                }
                if (compiledTemplate.size != 1
                    || compiledTemplate[0] !is ReplacementSegment.Literal
                ) {
                    clearCurrentResult()
                    return null
                }
                applyFullMatchResult(intArrayOf(firstMatchStart, firstMatchEnd))
                repText = (compiledTemplate[0] as ReplacementSegment.Literal).text
            }

            if (sb == null) {
                sb = StringBuilder(textLen)
            }
            sb.append(text, appendPos, matchStart)
            sb.append(repText)
            appendPos = matchEnd
            matchesFound++
        }

        if (sb == null) {
            if (accumulator != null) {
                accumulator.boundary(MatchStrategy.CHARACTER_CLASS)
            }
            applyFailedMatchResult()
            return text
        }

        this.appendPos = appendPos
        sb.append(text, appendPos, textLen)

        if (limit == 1) {
            applyFullMatchResult(intArrayOf(firstMatchStart, firstMatchEnd))
        } else {
            searchFrom = regionEnd
            applyFailedMatchResult()
        }

        if (accumulator != null) {
            accumulator.boundary(MatchStrategy.CHARACTER_CLASS)
            accumulator.matchCount(matchesFound)
        }

        return sb.toString()
    }

    private fun nullableCharClassReplaceFastPath(
        template: LazyTemplate, limit: Int, ccMatch: CharClassMatchInfo
    ): String? {
        val ranges = ccMatch.ranges
        val b0 = ccMatch.bitmap0
        val b1 = ccMatch.bitmap1
        val textLen: Int = text!!.length
        var firstMatchEnd = 0
        while (firstMatchEnd < textLen) {
            val cp = text!!.codePointAt(firstMatchEnd)
            if (!charClassContains(ranges!!, b0, b1, cp)) {
                break
            }
            firstMatchEnd += Character.charCount(cp)
        }

        applyFullMatchResult(intArrayOf(0, firstMatchEnd))
        val compiledTemplate = template.get()
        if (compiledTemplate.size != 1
            || compiledTemplate[0] !is ReplacementSegment.Literal
        ) {
            clearCurrentResult()
            return null
        }
        val replacement: String = (compiledTemplate[0] as ReplacementSegment.Literal).text
        if (limit == 1) {
            val result: StringBuilder = StringBuilder(textLen + replacement.length)
            result.append(replacement)
            result.append(text, firstMatchEnd, textLen)
            appendPos = firstMatchEnd
            return result.toString()
        }

        val result: StringBuilder = StringBuilder(textLen + replacement.length)
        var pos = 0
        while (pos < textLen) {
            var runEnd = pos
            while (runEnd < textLen) {
                val cp = text!!.codePointAt(runEnd)
                if (!charClassContains(ranges!!, b0, b1, cp)) {
                    break
                }
                runEnd += Character.charCount(cp)
            }
            result.append(replacement)
            if (runEnd > pos) {
                pos = runEnd
            } else {
                // Matcher.find() advances a terminal empty match by one UTF-16 position, matching the JDK
                // Matcher contract even when that position lies between surrogate halves.
                result.append(text!![pos])
                pos++
            }
        }
        result.append(replacement)

        appendPos = textLen
        searchFrom = regionEnd + 1
        applyFailedMatchResult()
        findExhaustedAfterTerminalEmptyMatch = true
        diagnosticIntentionalDivergence(IntentionalDivergence.EXHAUSTED_EMPTY_MATCH)
        return result.toString()
    }

    /**
     * Implements a non-terminal append-and-replace step. Appends the text between the previous append
     * position and the current match, followed by the processed replacement string.
     * 
     * 
     * The replacement string may contain references to captured groups: `$0`, `$1`,
     * etc. for numbered groups, and `${name}` for named groups. Use `\\` for a literal
     * backslash and `\$` for a literal dollar sign.
     * 
     * @param sb the target string builder
     * @param replacement the replacement string
     * @return this matcher
     * @throws IllegalStateException if no match has yet been attempted, or if the previous match
     * operation failed
     */
    fun appendReplacement(sb: StringBuilder, replacement: String): Matcher {
        modCount++
        checkMatch()
        sb.append(text, appendPos, start())
        appendReplacementBody(sb, replacement)
        appendPos = end()
        return this
    }

    /**
     * Implements a terminal append-and-replace step. Appends the remaining input text after the last
     * match to the string builder.
     * 
     * @param sb the target string builder
     * @return the string builder
     */
    fun appendTail(sb: StringBuilder): StringBuilder {
        sb.append(text, appendPos, text!!.length)
        return sb
    }

    /**
     * Implements a non-terminal append-and-replace step using the legacy [StringBuffer] class.
     * This method behaves identically to [.appendReplacement].
     * 
     * @param sb the target string buffer
     * @param replacement the replacement string
     * @return this matcher
     * @throws IllegalStateException if no match has yet been attempted, or if the previous match
     * operation failed
     */
    fun appendReplacement(sb: StringBuffer, replacement: String): Matcher {
        modCount++
        checkMatch()
        // Build into a temporary StringBuilder, then transfer to the StringBuffer.
        val tmp = StringBuilder()
        tmp.append(text, appendPos, start())
        appendReplacementBody(tmp, replacement)
        sb.append(tmp)
        appendPos = end()
        return this
    }

    /**
     * Implements a terminal append-and-replace step using the legacy [StringBuffer] class.
     * Appends the remaining input text after the last match to the string buffer.
     * 
     * @param sb the target string buffer
     * @return the string buffer
     */
    fun appendTail(sb: StringBuffer): StringBuffer {
        sb.append(text, appendPos, text!!.length)
        return sb
    }

    // ---------------------------------------------------------------------------
    // State management
    // ---------------------------------------------------------------------------
    /**
     * Resets this matcher, discarding all match information and setting the search position to the
     * beginning of the input.
     * 
     * @return this matcher
     */
    fun reset(): Matcher {
        modCount++
        resetStateForCurrentInput()
        return this
    }

    /**
     * Resets this matcher with a new input sequence.
     * 
     * @param input the new input character sequence
     * @return this matcher
     */
    fun reset(input: CharSequence?): Matcher {
        this.inputSequence = input
        invalidateInputDependentCaches()
        return reset()
    }

    /**
     * Sets the limits of this matcher's region. The region is the part of the input sequence that
     * will be searched to find a match. Invoking this method resets the matcher and sets the region
     * to start at the character specified by the `start` parameter and end at the character
     * specified by the `end` parameter.
     * 
     * @param start the index to start searching at (inclusive)
     * @param end the index to end searching at (exclusive)
     * @return this matcher
     * @throws IndexOutOfBoundsException if start or end is less than zero, if end is greater than the
     * length of the input sequence, or if start is greater than end
     */
    fun region(start: Int, end: Int): Matcher {
        val len = this.textLength
        if (start < 0 || start > len) {
            throw IndexOutOfBoundsException("start=" + start + ", length=" + len)
        }
        if (end < 0 || end > len) {
            throw IndexOutOfBoundsException("end=" + end + ", length=" + len)
        }
        if (start > end) {
            throw IndexOutOfBoundsException("start=" + start + " > end=" + end)
        }
        modCount++
        resetStateForRegion(start, end)
        return this
    }

    /**
     * Reports the start index of this matcher's region. Searches by this matcher are limited to
     * finding matches within [.regionStart] (inclusive) and [.regionEnd] (exclusive).
     * 
     * @return the starting point of this matcher's region
     */
    fun regionStart(): Int {
        return regionStart
    }

    /**
     * Reports the end index (exclusive) of this matcher's region. Searches by this matcher are
     * limited to finding matches within [.regionStart] (inclusive) and [.regionEnd]
     * (exclusive).
     * 
     * @return the ending point of this matcher's region
     */
    fun regionEnd(): Int {
        return regionEnd
    }

    /**
     * Returns an unmodifiable map of named capturing groups to their 1-based group indices. This
     * method overrides the default [MatchResult.namedGroups] method which
     * throws [UnsupportedOperationException].
     * 
     * @return an unmodifiable map from group names to group numbers
     */
    override fun namedGroups(): MutableMap<String?, Int?> {
        // MatchResult.namedGroups is Map; return Pattern's unmodifiable view (Java did the same).
        return Collections.unmodifiableMap(parentPattern.namedGroups())
    }

    /**
     * Returns the pattern that is interpreted by this matcher.
     * 
     * @return the pattern for which this matcher was created
     */
    fun pattern(): Pattern {
        return parentPattern
    }

    override fun toString(): String {
        var lastMatch = ""
        if (hasMatch && groups[0] >= 0 && groups[1] >= groups[0]) {
            lastMatch = text!!.substring(groups[0], groups[1])
        }
        return ("org.safere.Matcher[pattern="
                + parentPattern.pattern()
                + " region="
                + regionStart
                + ","
                + regionEnd
                + " lastmatch="
                + lastMatch
                + "]")
    }

    /**
     * Changes the [Pattern] that this `Matcher` uses to find matches. This method causes
     * this matcher to lose information about the groups of the last match. The matcher's position in
     * the input is maintained.
     * 
     * @param newPattern the new pattern used by this matcher
     * @return this matcher
     * @throws IllegalArgumentException if newPattern is null
     */
    fun usePattern(newPattern: Pattern?): Matcher {
        requireNotNull(newPattern) { "Pattern cannot be null" }
        modCount++
        val preservedGroupZero = hasMatch
        if (hasMatch) {
            if (!groupZeroResolved) {
                resolveCaptures()
            }
            val prevStart = groups[0]
            val prevEnd = groups[1]
            searchFrom = prevEnd
            if (prevStart == prevEnd && searchFrom < regionEnd) {
                searchFrom++
            }
            this.parentPattern = newPattern
            this.groups = IntArray(2 * newPattern.prog().numCaptures())
            Arrays.fill(this.groups, -1)
            this.groups[0] = prevStart
            this.groups[1] = prevEnd
            clearDeferredCaptureState()
        } else {
            this.parentPattern = newPattern
            this.groups = IntArray(2 * newPattern.prog().numCaptures())
            clearCurrentResult()
        }
        invalidatePatternCaches()
        replacementNeedsCaptures = false
        if (preservedGroupZero) {
            Pattern.diagnostics().onIntentionalDivergence(IntentionalDivergence.USE_PATTERN_GROUP_ZERO)
        }
        return this
    }

    /**
     * Sets the transparency of region bounds for this matcher. Transparent bounds allow lookaround
     * assertions to see beyond the region boundaries. Since SafeRE does not support lookaround
     * assertions, this method stores the flag but it has no effect on matching behavior.
     * 
     * @param b a boolean indicating whether to use transparent bounds
     * @return this matcher
     */
    fun useTransparentBounds(b: Boolean): Matcher {
        preserveResultAcrossBoundsChange()
        transparentBounds = b
        return this
    }

    /**
     * Returns whether this matcher is using transparent bounds.
     * 
     * @return `true` if this matcher is using transparent bounds, `false` otherwise
     */
    fun hasTransparentBounds(): Boolean {
        return transparentBounds
    }

    /**
     * Sets the anchoring of region bounds for this matcher. Anchoring bounds cause `^` and
     * `$` to match at the region boundaries rather than at the start and end of the entire
     * input. This is the default behavior.
     * 
     * @param b a boolean indicating whether to use anchoring bounds
     * @return this matcher
     */
    fun useAnchoringBounds(b: Boolean): Matcher {
        preserveResultAcrossBoundsChange()
        anchoringBounds = b
        return this
    }

    /**
     * Returns whether this matcher is using anchoring bounds.
     * 
     * @return `true` if this matcher is using anchoring bounds, `false` otherwise
     */
    fun hasAnchoringBounds(): Boolean {
        return anchoringBounds
    }

    /**
     * Returns the match state of this matcher as a [MatchResult]. The result is independent of
     * this matcher; subsequent operations on this matcher will not affect the returned result.
     * 
     * @return a [MatchResult] with the state of this matcher
     * @throws IllegalStateException if no match has yet been attempted, or if the previous match
     * operation failed
     */
    fun toMatchResult(): MatchResult {
        if (hasMatch) {
            if (parentPattern.numGroups() > 0) {
                recordInnerCaptureDemand()
            }
            resolveCaptures()
        }
        return SnapshotMatchResult(
            if (hasMatch) groups.clone() else null,
            text,
            groupCount(),
            namedGroups()!!,
            hasMatch
        )
    }

    // ---------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------
    /**
     * Resolves deferred capture groups. Called lazily when the user accesses any group (e.g., `group(0)`, `start(1)`) or when a full snapshot is needed (`toMatchResult()`). Runs
     * the submatch engine (OnePass or BitState/NFA) anchored at the DFA-determined match start,
     * bounded by the DFA's match end. For `find()`, it does not force the match to extend to
     * that end; this allows alternation priority to determine the actual match length (e.g., `(fo|foo)` matching "fo" rather than "foo"). For `matches()`, the deferred search must
     * still cover the whole input.
     */
    private fun resolveCaptures() {
        if (capturesResolved) {
            return
        }
        if (deferredRegionScanner == null) {
            resolveCapturesOnInput(activeScanner())
        } else {
            resolveRegionCaptures()
        }
    }

    private fun resolveRegionCaptures() {
        val offset = deferredRegionOffset
        resolveCapturesOnInput(deferredRegionScanner!!)
        for (i in groups.indices) {
            if (groups[i] >= 0) {
                groups[i] += offset
            }
        }
        deferredRegionScanner = null
        deferredRegionOffset = 0
    }

    private fun resolveCapturesOnInput(scanner: InputScanner) {
        if (capturesResolved) {
            return
        }
        val prog = parentPattern.prog()
        // Search anchored at matchStart, bounded by matchEnd, to extract inner capture groups.
        // The DFA sandwich has already determined group(0) bounds; this pass fills in the inner
        // captures within that range.
        //
        // Prefer OnePass when available — it's a single deterministic pass with no bitmap or job
        // stack overhead. Pattern.canOnePassSubmatch() already excludes lazy quantifiers, nullable
        // alternations, grapheme programs, and disabled OnePass options.
        var result: IntArray?
        if (parentPattern.canOnePassSubmatch()) {
            diagnosticCapture(MatchStrategy.ONE_PASS)
            val ncap = 2 * Math.max(prog.numCaptures(), 1)
            if (onePassScratchCap == null || onePassScratchCap!!.size < ncap) {
                onePassScratchCap = IntArray(ncap)
            }
            result =
                parentPattern
                    .onePass()!!
                    .search(
                        scanner,
                        deferredMatchStart,
                        deferredMatchEnd,
                        true,
                        prog.numCaptures(),
                        groups,
                        onePassScratchCap
                    )
        } else {
            val savedCaptureSearch = diagnosticCaptureSearch
            diagnosticCaptureSearch = true
            try {
                result =
                    searchWithBitStateOrNfa(
                        prog,
                        scanner,
                        deferredMatchStart,
                        deferredMatchEnd,
                        deferredMatchEnd,
                        true,
                        false,
                        deferredEndMatch,
                        prog.numCaptures(),
                        true,
                        groups
                    )
            } finally {
                diagnosticCaptureSearch = savedCaptureSearch
            }
        }
        if (result != null && result != groups) {
            groups = result
        }
        capturesResolved = true
        groupZeroResolved = true
    }

    private fun resolveCapturesBeforeRestoringRegion() {
        // Deferred captures must be replayed against the same opaque region view that produced the
        // match. Restoring the full input first would change empty-width assertion semantics at the
        // region boundaries. The replay also reads regionStart/regionEnd when building its assertion
        // context, so those bounds must use the substituted input's coordinates until replay finishes.
        if (hasMatch && !capturesResolved) {
            val savedRegionStart = regionStart
            val savedRegionEnd = regionEnd
            regionStart = 0
            regionEnd = activeScanner().length
            try {
                resolveCaptures()
            } finally {
                regionStart = savedRegionStart
                regionEnd = savedRegionEnd
            }
        }
    }

    private fun activeScanner(): InputScanner {
        if (textScanner == null) {
            textScanner = StringInputScanner(text!!)
        }
        return textScanner!!
    }

    private fun checkMatch() {
        check(resultStatus == ResultStatus.MATCHED) { "No match found" }
    }

    private fun namedGroupIndex(name: String?): Int {
        Objects.requireNonNull(name, "Group name")
        return parentPattern.namedGroups()[name]
            ?: throw IllegalArgumentException("No group with name <$name>")
    }

    private fun checkGroup(group: Int) {
        if (group < 0 || group > groupCount()) {
            throw IndexOutOfBoundsException(
                "No group " + group + " (groupCount=" + groupCount() + ")"
            )
        }
    }

    private fun checkConcurrentModification(expectedModCount: Int) {
        if (modCount != expectedModCount) {
            throw ConcurrentModificationException()
        }
    }

    /**
     * Processes a replacement string and appends the result to `sb`. Handles `$0`, `$1`, `${name}`, `\\` (literal backslash), and `\$` (literal dollar).
     */
    private fun appendReplacementBody(sb: StringBuilder, replacement: String) {
        if (isSimpleReplacement(replacement)) {
            sb.append(replacement)
            return
        }
        var i = 0
        while (i < replacement.length) {
            val c: Char = replacement[i]
            if (c == '\\') {
                i++
                require(i < replacement.length) { "Trailing backslash in replacement string" }
                sb.append(replacement[i])
                i++
            } else if (c == '$') {
                i++
                require(i < replacement.length) { "Trailing dollar sign in replacement string" }
                if (replacement[i] == '{') {
                    // Named group reference: ${name}
                    i++
                    val nameStart = i
                    while (i < replacement.length && replacement[i] != '}') {
                        i++
                    }
                    require(i < replacement.length) { "Missing closing '}' in replacement string" }
                    val name = replacement.substring(nameStart, i)
                    i++ // skip '}'
                    val g = namedGroupIndex(name)
                    val start = start(g)
                    val end = end(g)
                    if (start >= 0 && end >= 0) {
                        sb.append(text, start, end)
                    }
                } else if (isAsciiDigit(replacement[i])) {
                    // Numeric group reference: $0, $1, $12, etc.
                    val groupRef: NumericGroupReference = parseNumericGroupReference(replacement, i, groupCount())
                    val groupIdx = groupRef.groupNum
                    i = groupRef.end
                    val start = start(groupIdx)
                    val end = end(groupIdx)
                    if (start >= 0 && end >= 0) {
                        sb.append(text, start, end)
                    }
                } else {
                    throw IllegalArgumentException("Invalid group reference in replacement string")
                }
            } else {
                sb.append(c)
                i++
            }
        }
    }

    /** A snapshot of a match result, independent of the matcher that created it.  */
    private class SnapshotMatchResult(
        private val groups: IntArray?,
        private val text: String?,
        private val groupCount: Int,
        namedGroups: MutableMap<String?, Int?>,
        private val hasMatch: Boolean
    ) : MatchResult {
        private val namedGroups: MutableMap<String?, Int?>

        init {
            this.namedGroups = Collections.unmodifiableMap<String?, Int?>(namedGroups)
        }

        override fun hasMatch(): Boolean {
            return hasMatch
        }

        override fun start(): Int {
            return start(0)
        }

        override fun start(group: Int): Int {
            checkMatch()
            validateGroup(group)
            return groups!![2 * group]
        }

        override fun start(name: String?): Int {
            return start(groupIndex(name))
        }

        override fun end(): Int {
            return end(0)
        }

        override fun end(group: Int): Int {
            checkMatch()
            validateGroup(group)
            return groups!![2 * group + 1]
        }

        override fun end(name: String?): Int {
            return end(groupIndex(name))
        }

        override fun group(): String? {
            return group(0)
        }

        override fun group(group: Int): String? {
            checkMatch()
            val s = start(group)
            val e = end(group)
            if (s == -1) {
                return null
            }
            return text!!.substring(s, e)
        }

        override fun group(name: String?): String? {
            return group(groupIndex(name))
        }

        override fun groupCount(): Int {
            return groupCount
        }

        override fun namedGroups(): MutableMap<String?, Int?> {
            return namedGroups
        }

        fun groupIndex(name: String?): Int {
            Objects.requireNonNull<String?>(name, "Group name")
            val idx: Int? = namedGroups[name]
            requireNotNull(idx) { "No group with name <" + name + ">" }
            return idx
        }

        fun validateGroup(group: Int) {
            if (group < 0 || group > groupCount) {
                throw IndexOutOfBoundsException(
                    "No group " + group + " (groupCount=" + groupCount + ")"
                )
            }
        }

        fun checkMatch() {
            check(hasMatch) { "No match found" }
        }
    }

    private class LazyTemplate(private val replacement: String?, private val maxGroup: Int) {
        private var value: Array<ReplacementSegment>? = null
        private var needsCaptures: Boolean? = null
        fun needsCaptures(): Boolean {
            if (needsCaptures == null) {
                needsCaptures = computeNeedsCaptures()
            }
            return needsCaptures!!
        }

        fun computeNeedsCaptures(): Boolean {
            if (replacement == null) {
                return false
            }
            val len: Int = replacement.length
            var i = 0
            while (i < len) {
                val c: Char = replacement[i]
                if (c == '\\') {
                    i += 2
                } else if (c == '$') {
                    i++
                    if (i >= len) {
                        continue
                    }
                    val next: Char = replacement[i]
                    if (next == '{') {
                        return true
                    } else if (isAsciiDigit(next)) {
                        val groupRef: NumericGroupReference = parseNumericGroupReference(replacement, i, maxGroup)
                        if (groupRef.groupNum > 0) {
                            return true
                        }
                        i = groupRef.end
                    } else {
                        i++
                    }
                } else {
                    i++
                }
            }
            return false
        }

        fun get(): Array<ReplacementSegment> {
            if (value == null) {
                value = compileReplacementTemplate(replacement!!, maxGroup)
            }
            return value!!
        }
    }

    private class DfaMatchCursor(var pos: Int) {
        var revDfa: Dfa? = null
    }

    internal class SplitBuffer {
        /** Alternating (start, end) match positions.  */
        var array: IntArray = IntArray(32)

        var size: Int = 0
        fun add(start: Int, end: Int) {
            if (size + 2 > array.size) {
                array = Arrays.copyOf(array, array.size * 2)
            }
            array[size++] = start
            array[size++] = end
        }
    }

    internal fun findSplitPositions(limit: Int, buffer: SplitBuffer): Int {
        // The first search reuses this end-anchored probe instead of repeating it.
        var firstEndAnchoredStart: Int = END_ANCHORED_START_UNKNOWN
        if (!parentPattern.prog().anchorStart()) {
            // As in replaceImpl, try the end-anchored search before the whole-input reject prefilter.
            val endAnchoredStart = parentPattern.endAnchoredSearchStart(activeScanner(), 0, true, null)
            if (endAnchoredStart == -1) {
                return 0
            }
            firstEndAnchoredStart = endAnchoredStart
            val rejectPrefilter = parentPattern.findRejectPrefilter()
            if (endAnchoredStart < 0 && rejectPrefilter != null && text != null) {
                if (rejectPrefilter.canReject(text, null, 0, enginePathOptions())) {
                    return 0
                }
            }
        }
        var last = 0
        var searchFrom = 0
        val textLen: Int = text!!.length
        // This loop has its own search position, so it starts and leaves the backoff state neutral.
        startBackoff = AdaptiveBackoff.NEUTRAL

        while (searchFrom <= textLen) {
            val packed = findNextMatchPacked(searchFrom, firstEndAnchoredStart)
            firstEndAnchoredStart = END_ANCHORED_START_UNKNOWN
            if (packed == -1L) {
                break
            }
            val start: Int = unpackStart(packed)
            val end: Int = unpackEnd(packed)

            if (limit > 0 && (buffer.size / 2) >= limit - 1) {
                break
            }

            if (last == 0 && start == 0 && end == 0) {
                searchFrom = 1
                continue
            }

            buffer.add(start, end)
            last = end
            if (start == end) {
                searchFrom = end + 1
            } else if (parentPattern.hasInternalGraphemeClusterBoundary()
                && end < textLen && endedAfterCrLf(end)
            ) {
                searchFrom = end + 1
            } else {
                searchFrom = end
            }
        }
        startBackoff = AdaptiveBackoff.NEUTRAL
        return buffer.size / 2
    }

    /**
     * Returns the next match at or after `fromIndex` as packed positions, or -1.
     * 
     * @param endAnchoredStart the result of [Pattern.endAnchoredSearchStart] from `fromIndex`, or [.END_ANCHORED_START_UNKNOWN] to probe here
     */
    private fun findNextMatchPacked(fromIndex: Int, endAnchoredStart: Int): Long {
        var endAnchoredStart = endAnchoredStart
        val scanner = activeScanner()
        if (fromIndex > scanner.length) {
            return -1L
        }
        val prog = parentPattern.prog()
        if (prog.anchorStart() && fromIndex > 0) {
            return -1L
        }
        val options = enginePathOptions()

        // Literal fast path
        val literal = parentPattern.literalMatch()
        if (options.literalFastPaths && literal != null) {
            val idx: Int
            if (prog.anchorStart()) {
                // The unchecked folded comparison requires the entire literal to fit in the input.
                if (literal.length > text!!.length) {
                    return -1L
                }
                if (parentPattern.literalFoldCase()) {
                    idx = if (Ascii.regionMatchesIgnoreCase(text!!, 0, literal, literal.length)) 0 else -1
                } else {
                    idx = if (text!!.startsWith(literal)) 0 else -1
                }
            } else if (parentPattern.literalFoldCase()) {
                var anchorOffset = 0
                var anchorLow = 0.toChar()
                var anchorHigh = 0.toChar()
                val classHashChain: ClassHashChain?
                val runner = parentPattern.preparedMatchRunner(false)
                if (runner is LiteralPreparedRunner) {
                    anchorOffset = runner.anchorOffset()
                    anchorLow = runner.anchorLow()
                    anchorHigh = runner.anchorHigh()
                    classHashChain = runner.classHashChain()
                } else {
                    val literalLen: Int = literal.length
                    if (literalLen == 1) {
                        val anchor: Char = literal[0]
                        anchorLow = Ascii.toLowerCase(anchor)
                        anchorHigh = Ascii.toUpperCase(anchor)
                    } else if (literalLen > 1) {
                        anchorOffset = RarityOracle.rarestAsciiOffset(literal, literalLen, true)
                        val anchor: Char = literal[anchorOffset]
                        anchorLow = Ascii.toLowerCase(anchor)
                        anchorHigh = Ascii.toUpperCase(anchor)
                    }
                    val descriptor = parentPattern.matchDescriptor()
                    classHashChain = if (descriptor != null) descriptor.classHashChain else null
                }
                idx =
                    indexOfIgnoreCase(
                        text!!, literal, anchorOffset, anchorLow, anchorHigh, classHashChain, fromIndex
                    )
            } else {
                idx = text!!.indexOf(literal, fromIndex)
            }
            if (idx < 0) {
                return -1L
            }
            return packPositions(idx, idx + literal.length)
        }

        // Char class fast path
        val singleCharClass = parentPattern.matchDescriptor().singleCharClass
        if (options.charClassMatchFastPaths && singleCharClass != null) {
            if (prog.anchorStart() && fromIndex > 0) {
                return -1L
            }
            if (prog.anchorStart()) {
                if (scanner.length == 0) {
                    return -1L
                }
                val cp = scanner.codePointAt(0)
                if (singleCharClass.contains(cp)) {
                    return packPositions(0, Character.charCount(cp))
                }
                return -1L
            }
            if (singleCharClass.isAscii) {
                val idx = scanner.indexOfCharClass(singleCharClass, fromIndex)
                if (idx < 0) {
                    return -1L
                }
                return packPositions(idx, idx + 1)
            }
            val idx =
                scanner.indexOfCodePointClass(
                    singleCharClass.ranges,
                    singleCharClass.bitmap0,
                    singleCharClass.bitmap1,
                    fromIndex,
                    scanner.length
                )
            if (idx < 0) {
                return -1L
            }
            val cp = text!!.codePointAt(idx)
            return packPositions(idx, idx + Character.charCount(cp))
        }

        // As in the prepared find() runner, a keyword alternation scans for its keywords directly.
        val keywordAlternation =
            parentPattern.matchDescriptor().keywordAlternation
        if (options.keywordAlternationFastPath
            && keywordAlternation != null && !keywordAlternation.greedyWholeInput && !prog.anchorStart() && text != null
        ) {
            return findKeywordAlternationPacked(keywordAlternation, fromIndex)
        }

        // As in doFindCore, an end-anchored pattern can locate its next match from the input end
        // instead of scanning forward from fromIndex. Do this before start acceleration.
        var endAnchoredDeferred = false
        if (!prog.anchorStart()) {
            if (endAnchoredStart == END_ANCHORED_START_UNKNOWN) {
                endAnchoredStart = parentPattern.endAnchoredSearchStart(scanner, fromIndex, true, null)
            }
            endAnchoredDeferred = endAnchoredStart == Pattern.END_ANCHORED_SEARCH_DEFERRED
            val endAnchoredMatch = endAnchoredSplitMatch(scanner, endAnchoredStart)
            if (endAnchoredMatch != END_ANCHORED_UNRESOLVED) {
                return endAnchoredMatch
            }
        }

        var effectiveStart = fromIndex
        var startPositionPreselected = false
        if (options.startAcceleration && text != null && !prog.anchorStart()) {
            val accelerator = parentPattern.stringStartAccelerator()
            if (accelerator != null && startAccelerationAllowed(fromIndex, accelerator.policy())) {
                val idx = findNextStringStartCandidate(accelerator, fromIndex)
                if (idx < 0) {
                    return -1L
                }
                effectiveStart = idx
                startPositionPreselected = true
            }
        }

        // Retry a deferred reverse probe only after start acceleration narrows its work.
        if (endAnchoredDeferred) {
            val endAnchoredMatch =
                endAnchoredSplitMatch(
                    scanner,
                    parentPattern.endAnchoredSearchStart(scanner, effectiveStart, true, null, false)
                )
            if (endAnchoredMatch != END_ANCHORED_UNRESOLVED) {
                return endAnchoredMatch
            }
        }

        var fwdResult: Dfa.SearchResult? = null
        if (canUseForwardDfa()) {
            fwdResult =
                forwardDfaSearch(
                    dfa(false),
                    scanner,
                    effectiveStart,
                    prog.anchorStart(),
                    false,
                    startPositionPreselected
                )
            if (fwdResult != null && !fwdResult.matched) {
                return -1L
            }
        }

        if (options.dfa
            && parentPattern.dfaGroupZeroReliable()
            && fwdResult != null && fwdResult.pos > effectiveStart
        ) {
            val earlyEnd = fwdResult.pos

            if (prog.anchorStart()) {
                val fwdFirst = dfa(false).doSearch(scanner, effectiveStart, true, false)
                if (fwdFirst != null && fwdFirst.matched) {
                    return packPositions(effectiveStart, fwdFirst.pos)
                }
            } else if (canUseReverseDfa()) {
                val revDfa = reverseDfa()
                if (revDfa != null) {
                    val revResult =
                        revDfa.doSearchReverse(scanner, earlyEnd, effectiveStart, true, true)
                    if (revResult != null && revResult.matched && !revResult.ambiguous) {
                        val matchStart = revResult.pos
                        val fwdFirst = dfa(false).doSearch(scanner, matchStart, true, false)
                        if (fwdFirst != null && fwdFirst.matched) {
                            return packPositions(matchStart, fwdFirst.pos)
                        }
                    }
                }
            }
        }

        val nsubmatch = 1
        val result =
            searchWithBitStateOrNfa(
                prog,
                scanner,
                effectiveStart,
                scanner.length,
                scanner.length,
                false,
                false,
                false,
                nsubmatch
            )
        if (result == null) {
            return -1L
        }
        return packPositions(result[0], result[1])
    }

    /**
     * Resolves an end-anchored probe result for [.findNextMatchPacked]: -1 when the probe rules
     * out any further match, the match from a candidate start, or [.END_ANCHORED_UNRESOLVED]
     * when the ordinary search must decide.
     */
    private fun endAnchoredSplitMatch(scanner: InputScanner, endAnchoredStart: Int): Long {
        if (endAnchoredStart == -1) {
            return -1L
        }
        if (endAnchoredStart >= 0) {
            val exact =
                searchWithBitStateOrNfa(
                    parentPattern.prog(),
                    scanner,
                    endAnchoredStart,
                    endAnchoredStart,
                    scanner.length,
                    true,
                    false,
                    false,
                    1
                )
            if (exact != null) {
                return packPositions(endAnchoredStart, exact[1])
            }
        }
        return END_ANCHORED_UNRESOLVED
    }

    // ---------------------------------------------------------------------------
    // Prepared Match Runner (Fast Path Dispatch & Setup Elimination)
    // ---------------------------------------------------------------------------
    internal interface PreparedMatchRunner {
        fun find(matcher: Matcher, regionActive: Boolean): Boolean

        fun matches(matcher: Matcher): Boolean

        fun lookingAt(matcher: Matcher): Boolean
    }

    internal class LiteralAlternationPreparedRunner(
        private val alternation: LiteralAlternation,
        private val fallback: PreparedMatchRunner
    ) : PreparedMatchRunner {
        override fun find(matcher: Matcher, regionActive: Boolean): Boolean {
            val result: Long
            val matcherText = matcher.text
            val matcherScanner = matcher.textScanner
            if (matcherText != null) {
                result = alternation.find(matcherText, matcher.searchFrom)
            } else if (matcherScanner is StringRegionInputScanner) {
                result = alternation.find(matcherScanner, matcher.searchFrom)
            } else if (matcherScanner is Utf8InputScanner) {
                result = alternation.find(matcherScanner, matcher.searchFrom)
            } else {
                return fallback.find(matcher, regionActive)
            }
            if (result == LiteralAlternation.FALLBACK) {
                return fallback.find(matcher, regionActive)
            }
            matcher.diagnosticBoundary(MatchStrategy.LITERAL_ALTERNATION)
            return if (result == LiteralAlternation.NO_MATCH)
                matcher.applyFailedMatchResult()
            else
                matcher.applyGroupZeroMatchResult((result ushr 32).toInt(), result.toInt())
        }

        override fun matches(matcher: Matcher): Boolean {
            return fallback.matches(matcher)
        }

        override fun lookingAt(matcher: Matcher): Boolean {
            return fallback.lookingAt(matcher)
        }
    }

    internal class LiteralPreparedRunner(
        private val literal: String?,
        private val foldCase: Boolean,
        private val literalUtf8: ByteArray?,
        private val failure: IntArray?,
        private val shifts: IntArray?,
        private val rareByteOffset: Int,
        isStartAnchored: Boolean,
        fallback: PreparedMatchRunner,
        classHashChain: ClassHashChain?
    ) : PreparedMatchRunner {
        private val classHashChain: ClassHashChain?
        private val anchorOffset: Int
        private val anchorLow: Char
        private val anchorHigh: Char
        private val matchLengthChars: Int
        private val matchLengthBytes: Int
        private val isStartAnchored: Boolean
        private val fallback: PreparedMatchRunner

        init {
            this.classHashChain =
                if (classHashChain != null)
                    classHashChain
                else
                    (if (foldCase && literal != null)
                        ClassHashChain.compileCaseInsensitive(literal)
                    else
                        null)
            val literalLen = if (literal != null) literal.length else 0
            if (foldCase && literalLen > 0) {
                if (literalLen == 1) {
                    this.anchorOffset = 0
                    val c: Char = literal!![0]
                    this.anchorLow = Ascii.toLowerCase(c)
                    this.anchorHigh = Ascii.toUpperCase(c)
                } else {
                    this.anchorOffset = RarityOracle.rarestAsciiOffset(literal!!, literalLen, true)
                    val c: Char = literal[this.anchorOffset]
                    this.anchorLow = Ascii.toLowerCase(c)
                    this.anchorHigh = Ascii.toUpperCase(c)
                }
            } else {
                this.anchorOffset = 0
                this.anchorLow = 0.toChar()
                this.anchorHigh = 0.toChar()
            }
            this.matchLengthChars = literalLen
            this.matchLengthBytes = if (literalUtf8 != null) literalUtf8.size else 0
            this.isStartAnchored = isStartAnchored
            this.fallback = fallback
        }

        fun anchorOffset(): Int {
            return anchorOffset
        }

        fun anchorLow(): Char {
            return anchorLow
        }

        fun anchorHigh(): Char {
            return anchorHigh
        }

        fun classHashChain(): ClassHashChain? {
            return classHashChain
        }

        override fun find(matcher: Matcher, regionActive: Boolean): Boolean {
            if (isStartAnchored && matcher.searchFrom > 0) {
                return matcher.applyFailedMatchResult()
            }
            if (isStartAnchored) {
                return matchLiteral(matcher, false)
            }
            if (foldCase
                && matcher.text == null && (matcher.textScanner !is StringRegionInputScanner)
            ) {
                return fallback.find(matcher, regionActive)
            }
            matcher.diagnosticBoundary(MatchStrategy.LITERAL)
            val idx: Int
            val matchLength: Int
            val matcherText = matcher.text
            val matcherScanner = matcher.textScanner
            if (matcherScanner is StringRegionInputScanner) {
                idx =
                    if (foldCase)
                        matcherScanner.indexOfIgnoreCase(
                            literal!!, anchorOffset, anchorLow, anchorHigh, matcher.searchFrom
                        )
                    else
                        matcherScanner.indexOf(literal!!, matcher.searchFrom, matcherScanner.length)
                matchLength = matchLengthChars
            } else if (foldCase) {
                idx =
                    indexOfIgnoreCase(
                        matcherText!!,
                        literal!!,
                        anchorOffset,
                        anchorLow,
                        anchorHigh,
                        classHashChain,
                        matcher.searchFrom
                    )
                matchLength = matchLengthChars
            } else if (matcher.activeScanner() is Utf8InputScanner) {
                val utf8Scanner = matcher.activeScanner() as Utf8InputScanner
                idx =
                    if (rareByteOffset >= 0)
                        utf8Scanner.indexOf(
                            literalUtf8!!, failure!!, shifts, matcher.searchFrom, rareByteOffset
                        )
                    else
                        utf8Scanner.indexOf(literalUtf8!!, failure!!, shifts, matcher.searchFrom)
                matchLength = matchLengthBytes
            } else if (matcherText != null) {
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record(Math.max(0, matcherText.length - matcher.searchFrom).toLong())
                }
                idx = matcherText.indexOf(literal!!, matcher.searchFrom)
                matchLength = matchLengthChars
            } else {
                return matcher.doFindCore(regionActive)
            }
            if (idx < 0) {
                matcher.diagnosticBoundary(MatchStrategy.LITERAL)
                return matcher.applyFailedMatchResult()
            }
            return matcher.applyGroupZeroMatchResult(idx, idx + matchLength)
        }

        override fun matches(matcher: Matcher): Boolean {
            return matchLiteral(matcher, true)
        }

        override fun lookingAt(matcher: Matcher): Boolean {
            return matchLiteral(matcher, false)
        }

        private fun matchLiteral(matcher: Matcher, fullMatch: Boolean): Boolean {
            if (foldCase
                && matcher.text == null && (matcher.textScanner !is StringRegionInputScanner)
            ) {
                return if (fullMatch) fallback.matches(matcher) else fallback.lookingAt(matcher)
            }
            matcher.capturesResolved = true
            val matcherScanner = matcher.textScanner
            val matcherText = matcher.text
            if (matcherScanner is StringRegionInputScanner) {
                matcher.diagnosticBoundary(MatchStrategy.LITERAL)
                val matched =
                    (!fullMatch || matcherScanner.length == matchLengthChars)
                            && matcherScanner.startsWith(literal!!, 0, foldCase)
                return if (matched)
                    matcher.applyGroupZeroMatchResult(0, matchLengthChars)
                else
                    matcher.applyFailedMatchResult()
            } else if (matcherText != null) {
                matcher.diagnosticBoundary(MatchStrategy.LITERAL)
                val matched: Boolean
                if (foldCase) {
                    matched =
                        (if (fullMatch)
                            matcherText.length == matchLengthChars
                        else
                            matcherText.length >= matchLengthChars)
                                && matcher.literalRegionMatches(literal!!, 0, matchLengthChars)
                } else {
                    matched = if (fullMatch) (matcherText == literal) else matcherText.startsWith(literal!!)
                }
                if (matched) {
                    matcher.applyFullMatchResult(
                        intArrayOf(0, if (fullMatch) matcherText.length else matchLengthChars)
                    )
                } else {
                    if (matcher.isPartialLiteralMatch(literal!!, 0)) {
                    }
                    matcher.applyFailedMatchResult()
                }
                return matcher.hasMatch
            } else if (matcher.activeScanner() is Utf8InputScanner) {
                val utf8Scanner = matcher.activeScanner() as Utf8InputScanner
                matcher.diagnosticBoundary(MatchStrategy.LITERAL)
                val matched: Boolean
                if (literalUtf8 != null) {
                    matched =
                        (if (fullMatch)
                            utf8Scanner.length == matchLengthBytes
                        else
                            utf8Scanner.length >= matchLengthBytes)
                                && utf8Scanner.startsWith(literalUtf8, 0)
                } else {
                    matched = literal!!.isEmpty() && (!fullMatch || utf8Scanner.length == 0)
                }
                if (matched) {
                    matcher.applyFullMatchResult(
                        intArrayOf(0, if (fullMatch) utf8Scanner.length else matchLengthBytes)
                    )
                } else {
                    matcher.applyFailedMatchResult()
                }
                return matcher.hasMatch
            }
            return if (fullMatch) matcher.matchesCore() else matcher.lookingAtCore()
        }
    }

    internal class SingleCharClassPreparedRunner(
        private val singleCharClass: CharClassScanInfo?,
        private val charClassMatch: CharClassMatchInfo?,
        private val isStartAnchored: Boolean,
        private val greedy: Boolean
    ) : PreparedMatchRunner {
        override fun find(matcher: Matcher, regionActive: Boolean): Boolean {
            if (isStartAnchored && matcher.searchFrom > 0) {
                return matcher.applyFailedMatchResult()
            }
            if (singleCharClass == null) {
                if (charClassMatch != null && greedy
                    && !regionActive && matcher.activeScanner() is Utf8InputScanner
                ) {
                    val scanner = matcher.activeScanner() as Utf8InputScanner
                    matcher.diagnosticBoundary(MatchStrategy.CHARACTER_CLASS)
                    return matcher.repeatedCharClassFindUtf8FastPath(
                        scanner, charClassMatch, matcher.searchFrom
                    )
                }
                return matcher.doFindCore(regionActive)
            }
            if (isStartAnchored) {
                return matchSingleCharClass(matcher, false)
            }
            matcher.diagnosticBoundary(MatchStrategy.CHARACTER_CLASS)
            return matcher.singleCharClassFindFastPath(singleCharClass, matcher.searchFrom)
        }

        override fun matches(matcher: Matcher): Boolean {
            return matchSingleCharClass(matcher, true)
        }

        override fun lookingAt(matcher: Matcher): Boolean {
            return matchSingleCharClass(matcher, false)
        }

        private fun matchSingleCharClass(matcher: Matcher, fullMatch: Boolean): Boolean {
            matcher.capturesResolved = true
            if (charClassMatch != null && fullMatch) {
                if (matcher.text != null) {
                    matcher.diagnosticBoundary(MatchStrategy.CHARACTER_CLASS)
                    return matcher.charClassMatchFastPath(charClassMatch)
                }
                val regionScanner = matcher.textScanner
                if (regionScanner is StringRegionInputScanner) {
                    matcher.diagnosticBoundary(MatchStrategy.CHARACTER_CLASS)
                    return if (regionScanner.matchesCharClass(charClassMatch))
                        matcher.applyGroupZeroMatchResult(0, regionScanner.length)
                    else
                        matcher.applyFailedMatchResult()
                }
                if (regionScanner is Utf8InputScanner) {
                    matcher.diagnosticBoundary(MatchStrategy.CHARACTER_CLASS)
                    return matcher.charClassMatchUtf8FastPath(regionScanner, charClassMatch)
                }
            }
            if (singleCharClass != null) {
                matcher.diagnosticBoundary(MatchStrategy.CHARACTER_CLASS)
                val matcherText = matcher.text
                if (matcherText != null) {
                    val len: Int = matcherText.length
                    if (len >= 1) {
                        val c0: Char = matcherText[0]
                        if (len >= 2 && Character.isSurrogatePair(c0, matcherText[1])) {
                            val cp = matcherText.codePointAt(0)
                            if ((!fullMatch || len == 2) && singleCharClass.contains(cp)) {
                                return matcher.applyFullMatchResult(intArrayOf(0, 2))
                            }
                        } else {
                            val cp = c0.code
                            if ((!fullMatch || len == 1) && singleCharClass.contains(cp)) {
                                return matcher.applyFullMatchResult(intArrayOf(0, 1))
                            }
                        }
                    }
                    return matcher.applyFailedMatchResult()
                } else {
                    val scanner = matcher.activeScanner()
                    val len = scanner.length
                    if (len > 0) {
                        val decoded = scanner.decodeForward(0)
                        val cp: Int = InputScanner.codePoint(decoded)
                        val nextPos: Int = InputScanner.position(decoded)
                        if ((!fullMatch || len == nextPos) && singleCharClass.contains(cp)) {
                            return matcher.applyFullMatchResult(intArrayOf(0, nextPos))
                        }
                    }
                    return matcher.applyFailedMatchResult()
                }
            }
            return if (fullMatch) matcher.matchesCore() else matcher.lookingAtCore()
        }
    }

    internal class KeywordAlternationPreparedRunner(
        private val keywordAlternation: KeywordAlternation,
        private val numCaptures: Int,
        private val isStartAnchored: Boolean
    ) : PreparedMatchRunner {
        override fun find(matcher: Matcher, regionActive: Boolean): Boolean {
            if (isStartAnchored && matcher.searchFrom > 0) {
                return matcher.applyFailedMatchResult()
            }
            if (isStartAnchored) {
                matcher.diagnosticBoundary(MatchStrategy.KEYWORD)
                val matched =
                    if (matcher.text != null)
                        matcher.matchKeywordAlternationAt(keywordAlternation, 0, numCaptures)
                    else
                        matcher.matchUtf8KeywordAlternationAt(keywordAlternation, 0, numCaptures)
                if (matched && keywordAlternation.captureGroup > 0) {
                    matcher.diagnosticCapture(MatchStrategy.KEYWORD)
                }
                return matched
            }
            matcher.diagnosticBoundary(MatchStrategy.KEYWORD)
            val matched =
                if (matcher.text != null)
                    matcher.findKeywordAlternation(keywordAlternation, matcher.searchFrom, numCaptures)
                else
                    matcher.findUtf8KeywordAlternation(
                        keywordAlternation, matcher.searchFrom, numCaptures
                    )
            if (matched && keywordAlternation.captureGroup > 0) {
                matcher.diagnosticCapture(MatchStrategy.KEYWORD)
            }
            return matched
        }

        override fun matches(matcher: Matcher): Boolean {
            return matcher.matchesCore()
        }

        override fun lookingAt(matcher: Matcher): Boolean {
            return matcher.lookingAtCore()
        }
    }

    /**
     * Defers ShiftDfa construction until the first whole-input `matches()`, then hands off to
     * the resolved runner: [ShiftDfaPreparedRunner] if construction succeeded, otherwise the
     * prepared fallback.
     */
    internal class LazyShiftDfaPreparedRunner(private val pattern: Pattern, private val fallback: PreparedMatchRunner) :
        PreparedMatchRunner {
        // Racy single-check caching: every candidate value is immutable with final fields, and a
        // duplicate resolution is harmless. Volatile makes the cross-thread publication explicit.
        @Volatile
        private var resolved: PreparedMatchRunner? = null
        override fun find(matcher: Matcher, regionActive: Boolean): Boolean {
            return fallback.find(matcher, regionActive)
        }

        override fun matches(matcher: Matcher): Boolean {
            if (matcher.regionStart != 0 || matcher.regionEnd != matcher.activeScanner()
                    .length || matcher.transparentBounds
                || !matcher.anchoringBounds
            ) {
                return matcher.matchesCore()
            }
            var runner = resolved
            if (runner == null) {
                val shiftDfa = pattern.shiftDfa()
                runner = if (shiftDfa != null) ShiftDfaPreparedRunner(shiftDfa, fallback) else fallback
                resolved = runner
                pattern.replacePreparedMatchRunner(this, runner)
            }
            return runner.matches(matcher)
        }

        override fun lookingAt(matcher: Matcher): Boolean {
            return fallback.lookingAt(matcher)
        }
    }

    internal class ShiftDfaPreparedRunner(private val shiftDfa: ShiftDfa, private val fallback: PreparedMatchRunner) :
        PreparedMatchRunner {
        override fun find(matcher: Matcher, regionActive: Boolean): Boolean {
            return fallback.find(matcher, regionActive)
        }

        override fun matches(matcher: Matcher): Boolean {
            val scanner = matcher.activeScanner()
            val len = scanner.length
            if (matcher.regionStart != 0 || matcher.regionEnd != len || matcher.transparentBounds
                || !matcher.anchoringBounds
            ) {
                return matcher.matchesCore()
            }
            matcher.capturesResolved = true
            val matched: Boolean
            if (matcher.text != null) {
                matched = shiftDfa.matches(matcher.text!!, 0, len)
            } else if (scanner is Utf8InputScanner) {
                matched = shiftDfa.matches(scanner, 0, len)
            } else {
                return matcher.matchesCore()
            }
            matcher.diagnosticBoundary(MatchStrategy.SHIFT_DFA)
            if (matched) {
                return matcher.applyDeferredMatchResult(0, len, 1, true, true)
            }
            return matcher.applyFailedMatchResult()
        }

        override fun lookingAt(matcher: Matcher): Boolean {
            return fallback.lookingAt(matcher)
        }
    }

    internal class OnePassAnchoredPreparedRunner(private val numCaptures: Int) : PreparedMatchRunner {
        override fun find(matcher: Matcher, regionActive: Boolean): Boolean {
            if (!matcher.parentPattern.canOnePassFind()
                || matcher.activeScanner().length > matcher.onePassTextLimit()
            ) {
                return matcher.doFindCore(regionActive)
            }
            if (matcher.searchFrom > 0 || matcher.anchoredPrefixOrCharClassCannotMatch(0)
                || matcher.terminalPrefilterRejects(0)
            ) {
                return matcher.applyFailedMatchResult()
            }
            matcher.diagnosticBoundary(MatchStrategy.ONE_PASS)
            val result =
                matcher
                    .parentPattern
                    .onePass()!!
                    .search(
                        matcher.activeScanner(),
                        0,
                        matcher.activeScanner().length,
                        false,
                        numCaptures,
                        matcher.groups
                    )
            if (result != null && matcher.parentPattern.numGroups() > 0) {
                matcher.diagnosticCapture(MatchStrategy.ONE_PASS)
            }
            return matcher.applyFullMatchResult(result)
        }

        override fun matches(matcher: Matcher): Boolean {
            if (matcher.activeScanner().length > matcher.onePassTextLimit()) {
                return matcher.matchesCore()
            }
            matcher.capturesResolved = true
            val pattern = matcher.parentPattern
            if (pattern.canOnePassPrimary()) {
                if (matcher.terminalPrefilterRejects(0)) {
                    return matcher.applyFailedMatchResult()
                }
                val onePass = pattern.onePass()
                matcher.diagnosticBoundary(MatchStrategy.ONE_PASS)
                val result =
                    if (matcher.text != null)
                        onePass!!.search(matcher.text!!, true, numCaptures, matcher.groups)
                    else
                        onePass!!.search(matcher.activeScanner(), true, numCaptures, matcher.groups)
                if (result != null && pattern.numGroups() > 0) {
                    matcher.diagnosticCapture(MatchStrategy.ONE_PASS)
                }
                return matcher.applyFullMatchResult(result)
            }
            return matcher.matchesCore()
        }

        override fun lookingAt(matcher: Matcher): Boolean {
            if (matcher.activeScanner().length > matcher.onePassTextLimit()) {
                return matcher.lookingAtCore()
            }
            matcher.capturesResolved = true
            val pattern = matcher.parentPattern
            val prog = pattern.prog()
            if (pattern.canOnePassPrimary()
                && !prog.hasGraphemeSemantics() && !prog.anchorEnd() && !prog.dollarAnchorEnd()
            ) {
                matcher.diagnosticBoundary(MatchStrategy.ONE_PASS)
                val onePass = pattern.onePass()
                val result =
                    if (matcher.text != null)
                        onePass!!.search(matcher.text!!, false, numCaptures, matcher.groups)
                    else
                        onePass!!.search(matcher.activeScanner(), false, numCaptures, matcher.groups)
                if (result != null && pattern.numGroups() > 0) {
                    matcher.diagnosticCapture(MatchStrategy.ONE_PASS)
                }
                return matcher.applyFullMatchResult(result)
            }
            return matcher.lookingAtCore()
        }
    }

    internal class FallbackPreparedRunner : PreparedMatchRunner {
        override fun find(matcher: Matcher, regionActive: Boolean): Boolean {
            return matcher.doFindCore(regionActive)
        }

        override fun matches(matcher: Matcher): Boolean {
            return matcher.matchesCore()
        }

        override fun lookingAt(matcher: Matcher): Boolean {
            return matcher.lookingAtCore()
        }

        companion object {
            val INSTANCE: FallbackPreparedRunner = FallbackPreparedRunner()
        }
    }

    companion object {
        /** Maximum text length for anchored OnePass when inner captures are not required.  */
        const val ONEPASS_TEXT_LIMIT_NO_CAPTURES: Int = 256

        /** Maximum text length for anchored OnePass when inner captures are required.  */
        const val ONEPASS_TEXT_LIMIT_WITH_CAPTURES: Int = 65536

        /**
         * Returns the maximum input text length eligible for anchored OnePass execution based on whether
         * inner capture extraction is required.
         * 
         * 
         *  * **No inner captures (&lt;= 256 bytes)**: For short strings, OnePass avoids the lazy
         * DFA initialization overhead (~50-80 ns). For larger texts without captures (including
         * literal/group-0 replacements or boolean-only matching on patterns without groups),
         * Forward DFA's direct transition table loop and SIMD vector acceleration are 3x-10x+
         * faster.
         *  * **With inner captures (&lt;= 65536 bytes)**: OnePass extracts capture group boundaries
         * in a single linear pass, outperforming the multi-pass DFA + BitState/NFA submatch
         * extraction sandwich (2x-28x speedup) and avoiding the Java NFA heap allocation cliff at
         * &gt;= 16 KB.
         * 
         * 
         * 
         * **Tradeoff Note (pattern.matcher(input).matches() -&gt; group()):** In Java's stateful
         * Matcher API, matches() is called before the engine knows whether the caller will subsequently
         * call group(i). If a pattern contains capturing groups (prog.numCaptures() &gt; 0), defaulting
         * to the 64 KB limit ensures one-shot matchers (e.g. pattern.matcher(input).matches() -&gt;
         * group(1)) record captures in a single pass without paying the severe (2.6x) multi-pass fallback
         * submatch penalty on 256 B - 64 KB inputs.
         */
        /**
         * Returns the OnePass text limit for standard Matcher operations based on whether the pattern
         * declares capturing groups.
         */
        fun onePassTextLimit(requiresInnerCaptures: Boolean): Int {
            return if (requiresInnerCaptures)
                ONEPASS_TEXT_LIMIT_WITH_CAPTURES
            else
                ONEPASS_TEXT_LIMIT_NO_CAPTURES
        }

        /**
         * Maximum number of submatches (including group 0) for lazy fallback capture extraction.
         * Deferring captures saves work for find-all loops that only need group 0, but the first inner
         * group access has to rerun the submatch engine. Keep the optimization to small-capture patterns
         * so capture-heavy parsers do not pay that startup penalty.
         */
        private const val MAX_LAZY_FALLBACK_SUBMATCHES = 3

        /**
         * Maximum input length for adapting match operations to observed inner-capture demand. On small
         * inputs BitState can produce the boolean result and all capture bounds in one bounded pass;
         * larger inputs retain the DFA's scanning advantage.
         */
        private const val CAPTURE_DEMAND_TEXT_LIMIT = 512

        // Retry after increasingly many eligible searches when bounded windows keep failing. The capped
        // delay allows recovery after a long-match prefix without paying for a window on every match.
        private const val MAX_CONSECUTIVE_WINDOW_FAILURES = 2
        private const val INITIAL_WINDOW_RETRY_DELAY = 8
        private const val MAX_WINDOW_RETRY_DELAY = 256

        /**
         * Materializes a CharSequence into a String by reading through `charAt()`, so that custom
         * CharSequence implementations that don't override `toString()` work correctly.
         */
        private fun charSequenceToString(cs: CharSequence): String {
            if (cs is String) {
                return cs
            }
            val len: Int = cs.length
            val chars = CharArray(len)
            for (i in 0..<len) {
                chars[i] = cs[i]
            }
            return String(chars)
        }

        private fun charClassContains(ranges: IntArray, b0: Long, b1: Long, cp: Int): Boolean {
            if (cp < 64) {
                return (b0 and (1L shl cp)) != 0L
            }
            if (cp < 128) {
                return (b1 and (1L shl (cp - 64))) != 0L
            }
            return binarySearchRanges(ranges, cp)
        }

        /** Binary search through sorted [lo, hi] ranges to check if `cp` is in any range.  */
        fun binarySearchRanges(ranges: IntArray, cp: Int): Boolean {
            var lo = 0
            var hi: Int = ranges.size / 2 - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val rangeLo = ranges[mid * 2]
                val rangeHi = ranges[mid * 2 + 1]
                if (cp < rangeLo) {
                    hi = mid - 1
                } else if (cp > rangeHi) {
                    lo = mid + 1
                } else {
                    return true
                }
            }
            return false
        }

        /**
         * Returns `true` if the replacement string contains no group references (`$`) or
         * escape sequences (`\`). When true, `replaceAll` can use a fast path that appends
         * the replacement string directly without per-character scanning.
         */
        private fun isSimpleReplacement(replacement: String): Boolean {
            for (i in 0..<replacement.length) {
                val c: Char = replacement[i]
                if (c == '$' || c == '\\') {
                    return false
                }
            }
            return true
        }

        /**
         * Pre-parses a replacement string into a compiled template of segments. The template can be
         * applied repeatedly without re-scanning the replacement string.
         * 
         * @param replacement the replacement string (may contain `$1`, `${name}`, `\\`,
         * `\$`)
         * @param maxGroup the highest legal capturing-group number, excluding group 0
         * @return an array of segments representing the compiled template
         * @throws IllegalArgumentException if the replacement string is malformed
         */
        internal fun compileReplacementTemplate(replacement: String, maxGroup: Int): Array<ReplacementSegment> {
            // Fast path: no special characters → single literal segment.
            if (isSimpleReplacement(replacement)) {
                return arrayOf<ReplacementSegment>(ReplacementSegment.Literal(replacement))
            }

            val segments: MutableList<ReplacementSegment> = ArrayList<ReplacementSegment>()
            val literal = StringBuilder()
            var i = 0

            while (i < replacement.length) {
                val c: Char = replacement[i]
                if (c == '\\') {
                    i++
                    require(i < replacement.length) { "Trailing backslash in replacement string" }
                    literal.append(replacement[i])
                    i++
                } else if (c == '$') {
                    // Flush accumulated literal text.
                    if (!literal.isEmpty()) {
                        segments.add(ReplacementSegment.Literal(literal.toString()))
                        literal.setLength(0)
                    }
                    i++
                    require(i < replacement.length) { "Trailing dollar sign in replacement string" }
                    if (replacement[i] == '{') {
                        // Named group reference: ${name}
                        i++
                        val nameStart = i
                        while (i < replacement.length && replacement[i] != '}') {
                            i++
                        }
                        require(i < replacement.length) { "Missing closing '}' in replacement string" }
                        segments.add(ReplacementSegment.NamedGroupRef(replacement.substring(nameStart, i)))
                        i++ // skip '}'
                    } else if (isAsciiDigit(replacement[i])) {
                        // Numeric group reference: $0, $1, $12, etc.
                        val groupRef: NumericGroupReference = parseNumericGroupReference(replacement, i, maxGroup)
                        segments.add(ReplacementSegment.GroupRef(groupRef.groupNum))
                        i = groupRef.end
                    } else {
                        throw IllegalArgumentException("Invalid group reference in replacement string")
                    }
                } else {
                    literal.append(c)
                    i++
                }
            }
            // Flush any trailing literal.
            if (!literal.isEmpty()) {
                segments.add(ReplacementSegment.Literal(literal.toString()))
            }
            return segments.toTypedArray()
        }

        /**
         * Returns whether `c` is an ASCII digit. Replacement-string group references accept only
         * `'0'` through `'9'`, so this deliberately does not use [ ][Character.isDigit], which also accepts other Unicode decimal digits whose `c - '0'`
         * value is not a digit value.
         */
        private fun isAsciiDigit(c: Char): Boolean {
            return c >= '0' && c <= '9'
        }

        private fun parseNumericGroupReference(
            replacement: String, digitStart: Int, maxGroup: Int
        ): NumericGroupReference {
            var groupNum = replacement[digitStart].code - '0'.code
            var i = digitStart + 1
            while (i < replacement.length && isAsciiDigit(replacement[i])) {
                val nextGroupNum = groupNum * 10 + (replacement[i].code - '0'.code)
                if (nextGroupNum > maxGroup) {
                    break
                }
                groupNum = nextGroupNum
                i++
            }
            return NumericGroupReference(groupNum, i)
        }

        private fun templateNeedsCaptures(template: Array<ReplacementSegment>): Boolean {
            for (seg in template) {
                if (seg is ReplacementSegment.GroupRef) {
                    if (seg.groupNum > 0) {
                        return true
                    }
                } else if (seg is ReplacementSegment.NamedGroupRef) {
                    return true
                }
            }
            return false
        }

        /**
         * Returns whether DFA paths implement every instruction and boundary predicate in `prog`.
         */
        private fun dfaSupportsProgram(prog: Prog?): Boolean {
            return prog != null && !prog.hasGraphemeSemantics() && prog.numLoopRegs() == 0
        }

        // Scalar class scans skip paired low-surrogate starts allowed by the grapheme NFA. Literal
        // scans already nominate UTF-16 positions and do not need the region-local scalar scanner.
        private fun usesScalarClassStartScan(accelerator: StringStartAccelerator?): Boolean {
            var accelerator = accelerator
            while (accelerator is StringStartAccelerator.LeadingExpansion) {
                accelerator = accelerator.inner
            }
            return accelerator is StringStartAccelerator.CharClass
                    || accelerator is StringStartAccelerator.FixedOffsetClass
        }

        private fun canVerifyLeadingExpansion(accelerator: StringStartAccelerator?): Boolean {
            return accelerator is StringStartAccelerator.LeadingExpansion
                    && accelerator.canVerifyAtInner()
        }

        private fun isBoundaryWordChar(cp: Int, unicodeWordBoundary: Boolean): Boolean {
            return if (unicodeWordBoundary) Nfa.isUnicodeWordChar(cp) else Nfa.isWordChar(cp)
        }

        /** ASCII case-insensitive indexOf for Java's default CASE_INSENSITIVE semantics.  */
        fun indexOfIgnoreCase(text: String, prefix: String, fromIndex: Int): Int {
            val prefixLen: Int = prefix.length
            if (prefixLen == 0) {
                return Math.min(Math.max(0, fromIndex), text.length)
            }
            if (prefixLen == 1) {
                return if (Ascii.isAscii(prefix))
                    Ascii.indexOfIgnoreCase(text, prefix[0], fromIndex)
                else
                    Utf16.indexOfUnicodeIgnoreCase(text, prefix, fromIndex)
            }
            val anchorOffset = RarityOracle.rarestAsciiOffset(prefix, prefixLen, true)
            val anchor: Char = prefix[anchorOffset]
            val low = Ascii.toLowerCase(anchor)
            val high = Ascii.toUpperCase(anchor)
            return indexOfIgnoreCase(text, prefix, anchorOffset, low, high, fromIndex)
        }

        fun indexOfIgnoreCase(
            text: String, prefix: String, anchorOffset: Int, low: Char, high: Char, fromIndex: Int
        ): Int {
            val classHashChain: ClassHashChain? =
                if (prefix.length >= 4) ClassHashChain.compileCaseInsensitive(prefix) else null
            return indexOfIgnoreCase(text, prefix, anchorOffset, low, high, classHashChain, fromIndex)
        }

        internal fun indexOfIgnoreCase(
            text: String,
            prefix: String,
            anchorOffset: Int,
            low: Char,
            high: Char,
            classHashChain: ClassHashChain?,
            fromIndex: Int
        ): Int {
            val prefixLen: Int = prefix.length
            when (prefixLen) {
                0 -> {
                    return Math.min(Math.max(0, fromIndex), text.length)
                }

                1 -> {
                    return if (Ascii.isAscii(prefix))
                        Ascii.indexOfIgnoreCase(text, low, high, fromIndex)
                    else
                        Utf16.indexOfUnicodeIgnoreCase(text, prefix, fromIndex)
                }

                else -> {}
            }
            val length: Int = text.length
            var pos = Math.max(0, fromIndex)
            val isAsciiPrefix = Ascii.isAscii(prefix)
            if (!isAsciiPrefix) {
                if (classHashChain != null) {
                    val limit = WorkLimit.forRemaining(length - pos)
                    val result = classHashChain.search(text, pos, limit)
                    return if (result == -2) Utf16.indexOfUnicodeIgnoreCase(text, prefix, pos) else result
                }
                return Utf16.indexOfUnicodeIgnoreCase(text, prefix, pos)
            }
            var verificationWork: Long = 0
            var workLimit: Long = -1
            var hasLow = true
            var hasHigh = (low != high)
            var nextLow = -1
            var nextHigh = -1
            val startFrom = if (anchorOffset == 0) 1 else 0

            while (pos <= length - prefixLen) {
                // Short scalar search for nearby anchor (handles whitespace / short delimiters without
                // indexOf overhead)
                val scalarLimit = Math.min(length - prefixLen + 1, pos + 32)
                while (pos < scalarLimit) {
                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.record()
                    }
                    val c: Char = text[pos + anchorOffset]
                    if (if (hasHigh) (c.code or 0x20) == low.code else c == low) {
                        if (classHashChain != null) {
                            val shift = classHashChain.shiftAt(text, pos)
                            if (shift == 0) {
                                if (Ascii.regionMatchesIgnoreCase(text, pos, prefix, startFrom, prefixLen)) {
                                    return pos
                                }
                            } else {
                                pos += shift - 1
                                pos++
                                continue
                            }
                        } else if (Ascii.regionMatchesIgnoreCase(text, pos, prefix, startFrom, prefixLen)) {
                            return pos
                        }
                        verificationWork += prefixLen.toLong()
                        if (workLimit < 0) {
                            workLimit = WorkLimit.forRemaining(length - pos)
                        }
                        if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                            if (classHashChain != null) {
                                val result = classHashChain.search(text, pos + 1, workLimit)
                                return if (result == -2) Ascii.indexOfLinearIgnoreCase(
                                    text,
                                    prefix,
                                    pos + 1
                                ) else result
                            }
                            return Ascii.indexOfLinearIgnoreCase(text, prefix, pos + 1)
                        }
                    }
                    pos++
                }

                if (pos > length - prefixLen) {
                    return -1
                }

                if (workLimit < 0) {
                    workLimit = WorkLimit.forRemaining(length - pos)
                }

                val searchFrom = pos + anchorOffset
                if (nextLow >= 0 && nextLow < searchFrom) {
                    nextLow = -1
                }
                if (nextHigh >= 0 && nextHigh < searchFrom) {
                    nextHigh = -1
                }
                if (hasLow && nextLow < 0) {
                    nextLow = text.indexOf(low, searchFrom)
                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.record((if (nextLow < 0) length - searchFrom else nextLow - searchFrom + 1).toLong())
                    }
                    if (nextLow < 0) {
                        hasLow = false
                    }
                }

                if (hasHigh && nextHigh < 0 && nextLow != searchFrom) {
                    nextHigh = text.indexOf(high, searchFrom)
                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.record((if (nextHigh < 0) length - searchFrom else nextHigh - searchFrom + 1).toLong())
                    }
                    if (nextHigh < 0) {
                        hasHigh = false
                    }
                }

                val nextAnchor = Ascii.minNonNegative(nextLow, nextHigh)
                if (nextAnchor < 0) {
                    return -1
                }
                if (nextLow == nextAnchor) {
                    nextLow = -1
                }
                if (nextHigh == nextAnchor) {
                    nextHigh = -1
                }
                val candidatePos = nextAnchor - anchorOffset
                if (WorkLimit.candidateInBounds(candidatePos, pos, length, prefixLen)) {
                    if (classHashChain != null) {
                        val shift = classHashChain.shiftAt(text, candidatePos)
                        if (shift == 0) {
                            if (Ascii.regionMatchesIgnoreCase(text, candidatePos, prefix, prefixLen)) {
                                return candidatePos
                            }
                            pos = candidatePos + 1
                        } else {
                            pos = candidatePos + shift
                            continue
                        }
                    } else {
                        if (Ascii.regionMatchesIgnoreCase(text, candidatePos, prefix, prefixLen)) {
                            return candidatePos
                        }
                        pos = candidatePos + 1
                    }
                    verificationWork += prefixLen.toLong()
                    if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                        if (classHashChain != null) {
                            val result = classHashChain.search(text, pos, workLimit)
                            return if (result == -2) Ascii.indexOfLinearIgnoreCase(text, prefix, pos) else result
                        }
                        return Ascii.indexOfLinearIgnoreCase(text, prefix, pos)
                    }
                } else {
                    pos = candidatePos + 1
                }
            }
            return -1
        }

        // ---------------------------------------------------------------------------
        // Replacement methods
        // ---------------------------------------------------------------------------
        /**
         * Returns a literal replacement `String` for the specified `String`. This method
         * produces a `String` that will work as a literal replacement `s` in the `appendReplacement` method of the [Matcher] class. The `String` produced will match
         * the sequence of characters in `s` treated as a literal sequence. Slashes (`'\'`)
         * and dollar signs (`'$'`) will be given no special meaning.
         * 
         * @param s the string to be literalized
         * @return a literal string replacement
         */
        @JvmStatic
        fun quoteReplacement(s: String): String {
            return java.util.regex.Matcher.quoteReplacement(s)
        }

        /** Marks that [.findNextMatchPacked] has not yet probed from the input end.  */
        private val END_ANCHORED_START_UNKNOWN = Integer.MIN_VALUE

        /** [.endAnchoredSplitMatch] found no match from the end; continue the ordinary search.  */
        private val END_ANCHORED_UNRESOLVED = java.lang.Long.MIN_VALUE

        private fun packPositions(start: Int, end: Int): Long {
            return (start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)
        }

        private fun unpackStart(packed: Long): Int {
            return (packed ushr 32).toInt()
        }

        private fun unpackEnd(packed: Long): Int {
            return packed.toInt()
        }
    }
}
