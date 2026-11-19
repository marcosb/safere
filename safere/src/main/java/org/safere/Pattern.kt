// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.safere.Matcher.*
import org.safere.MultiAnchorDescriptor.RejectPlan
import org.safere.MultiAnchorDescriptor.StartPlan
import java.io.Serializable
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.invoke.MutableCallSite
import java.lang.ref.SoftReference
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Predicate
import java.util.function.Supplier
import java.util.regex.PatternSyntaxException
import java.util.stream.Stream
import java.util.stream.StreamSupport
import kotlin.concurrent.Volatile

/**
 * A compiled regular expression backed by a linear-time NFA engine. This class provides a drop-in
 * replacement for [java.util.regex.Pattern].
 * 
 * 
 * Unlike `java.util.regex.Pattern`, this implementation guarantees linear-time matching
 * regardless of the pattern or input. Features that require exponential time (backreferences,
 * lookahead, lookbehind) are not supported and will be rejected at compile time.
 * 
 * 
 * Usage:
 * 
 * <pre>
 * Pattern p = Pattern.compile("(\\w+)@(\\w+)");
 * Matcher m = p.matcher("user@host");
 * if (m.matches()) {
 * String user = m.group(1);
 * }
</pre> * 
 */
class Pattern private constructor(
    pattern: String?,
    flags: Int,
    prog: Prog,
    prepared: Compiler.Prepared?,
    ast: Regexp,
    astAnalysis: AstAnalysis,
    matchDescriptor: MatchDescriptor?,
    startsWithGraphemeClusterBoundary: Boolean,
    hasInternalGraphemeClusterBoundary: Boolean,
    multiAnchor: MultiAnchorDescriptor?,
    enginePathOptions: EnginePathOptions
) : Serializable {
    private val pattern: String?
    private val flags: Int

    @Transient
    private val prog: Prog

    /**
     * Flattened copies of [.prog] for the DFA. Built in the constructor unless the default
     * `find()` runner never uses the forward DFA; then built on first use. Patterns with loop
     * registers always build them eagerly, since that requires a separate compilation.
     */
    @Volatile
    @Transient
    private var flatProg: Prog? = null

    @Volatile
    @Transient
    private var flatDfaProg: Prog? = null

    /** Whether the forward DFA supports [.flatDfaProg], known without building it.  */
    @Transient
    private val forwardDfaSupported: Boolean

    @Transient
    private val ast: Regexp

    @Transient
    private val astAnalysis: AstAnalysis

    @Transient
    private val prefix: String?

    @Transient
    private val prefixFoldCase: Boolean

    @Transient
    private val matchDescriptor: MatchDescriptor

    @Transient
    private val literalMatchUtf8: ByteArray?

    @Transient
    private val literalMatchFailure: IntArray?

    @Transient
    private val literalMatchShifts: IntArray?

    @Transient
    private val literalMatchRareByteOffset: Int

    @Transient
    private val prefixUtf8: ByteArray?

    @Transient
    private val anchoredPrefix: String?

    @Transient
    private val anchoredPrefixUtf8: ByteArray?

    @Transient
    private val startsWithGraphemeClusterBoundary: Boolean

    @Transient
    private val hasInternalGraphemeClusterBoundary: Boolean

    @Transient
    private val charClassPrefix: CharClassScanInfo?

    @Transient
    private val anchoredCharClassPrefix: CharClassScanInfo?

    @Transient
    private val utf8StartAccelerator: Utf8StartAccelerator?

    @Transient
    private val stringStartAccelerator: StringStartAccelerator?

    @Transient
    private val enginePathOptions: EnginePathOptions

    @Transient
    private val literalAlternation: LiteralAlternation?

    @Transient
    private val innerLiteralSearch: InnerLiteralSearch?

    // Not final: a lazy ShiftDfa runner replaces itself once its analysis resolves. Volatile because
    // that replacement happens on whichever thread first calls matches(). Every runner has only final
    // fields, so a racy read was already safe, but volatile makes the publication explicit.
    @Volatile
    @Transient
    private var defaultPreparedMatchRunner: PreparedMatchRunner?

    @Transient
    private val regionPreparedMatchRunner: PreparedMatchRunner?
    private val patternId: Long

    @Volatile
    @Transient
    private var patternAnalysis: PatternAnalysis? = null

    @Volatile
    @Transient
    private var patternDescriptor: PatternDescriptor? = null

    @Transient
    private val multiAnchor: MultiAnchorDescriptor

    /**
     * Whole-input rejection filter for Tier 0 acceleration. Non-null when matching can quickly reject
     * by verifying that mandatory literal content or character classes appear anywhere in the input
     * before running automata.
     */
    @Transient
    private val rejectPrefilter: RejectPrefilter?

    @Transient
    private val utf8FindRejectPrefilter: RejectPrefilter?

    private val startPrecedesFindReject: Boolean
    private val dfaStartAcceleration: Boolean
    private val startSubsumesFindReject: Boolean

    /**
     * OnePass analysis results, computed during construction when eligible analysis can support
     * `find()` or capture extraction, otherwise on first request. Holds the OnePass automaton
     * and derived flags (`canOnePassFind`, `canOnePassSubmatch`).
     */
    @Volatile
    @Transient
    private var onePassAnalysis: OnePassAnalysis? = null

    /**
     * Whether a matcher has consumed inner captures, including already resolved captures and
     * successful snapshots. Shared for this Pattern's lifetime across matchers, inputs and threads;
     * independent of any matcher's current result. Exact fallback searches can reuse this hint to
     * collect captures in a required pass; small-input heuristics may also avoid a redundant DFA
     * pass. Internal resolution and group-zero-only access must not set it.
     */
    @Volatile
    @Transient
    private var innerCapturesObserved = false

    /**
     * DFA equivalence-class setup for the forward program, shared across all Matcher instances. Built
     * during construction unless the default `find()` runner is a literal runner; then built on
     * first request.
     */
    @Volatile
    @Transient
    private var forwardDfaSetup: Dfa.Setup? = null

    /**
     * Reverse-compiled program for backward DFA matching. Computed on first use: only unanchored
     * `find()` operations whose forward DFA search finds a match, and end-anchored searches
     * over long inputs, need it.
     */
    @Volatile
    @Transient
    private var reverseProg: Prog? = null

    @Volatile
    @Transient
    private var flatReverseProg: Prog? = null

    @Volatile
    @Transient
    private var flatReverseDfaProg: Prog? = null

    /** DFA setup built once alongside the final [.flatReverseDfaProg].  */
    @Volatile
    @Transient
    private var reverseDfaSetup: Dfa.Setup? = null

    /**
     * Per-thread cached execution state. Shared across all Matchers created from this Pattern within
     * the same thread, so warm DFA state caches and BitState/Nfa scratch buffers persist across the
     * common `pattern.matcher(t).find()` idiom.
     * 
     * 
     * The cache is held via a [SoftReference] (a bootstrap-loaded JDK class) so that when
     * SafeRE is loaded in a child [ClassLoader] and patterns are held in `static final`
     * fields, the [ThreadLocal] entry value in long-lived threads does not strongly reference
     * SafeRE classes and pin the child `ClassLoader`.
     */
    internal class ThreadCache {
        // Use the ID rather than retaining the Thread and its other thread-local values.
        val ownerThreadId: Long = Thread.currentThread().threadId()
        var bitState: BitState? = null
        var nfa: Nfa? = null
        var forwardFirstMatchDfa: Dfa? = null
        var forwardLongestMatchDfa: Dfa? = null
        var reverseDfa: Dfa? = null
    }

    // Per-Pattern ThreadLocal is intentional: each Pattern caches its own DFA/BitState/Nfa state per
    // thread, so warm state caches persist across the common pattern.matcher(t).find() idiom.
    @Transient
    private val threadCache = ThreadLocal<SoftReference<ThreadCache?>?>()

    /** Holder for OnePass analysis results.  */
    private data class OnePassAnalysis(
        val onePass: OnePass?,
        val canPrimary: Boolean,
        val canFind: Boolean,
        val canSubmatch: Boolean
    ) {


        companion object {
            val DISABLED: OnePassAnalysis = OnePassAnalysis(null, false, false, false)
        }
    }

    /**
     * ShiftDfa analysis result, computed on first `matches()` request for eligible group-0
     * patterns.
     */
    @Volatile
    @Transient
    private var shiftDfaAnalysis: ShiftDfaAnalysis? = null

    /** Holder for ShiftDfa analysis results.  */
    private data class ShiftDfaAnalysis(val shiftDfa: ShiftDfa?) {


        companion object {
            val DISABLED: ShiftDfaAnalysis = ShiftDfaAnalysis(null)
        }
    }

    /**
     * Creates a matcher that will match the given input against this pattern.
     * 
     * @param input the character sequence to be matched
     * @return a new matcher for this pattern
     */
    fun matcher(input: CharSequence?): Matcher {
        return Matcher(this, input!!)
    }

    /**
     * Creates a matcher over UTF-8 input whose positions are relative byte offsets.
     * 
     * @param input borrowed UTF-8 input retained for the lifetime of the matcher
     * @return a new, non-thread-safe UTF-8 matcher
     */
    fun matcher(input: Utf8Input?): Utf8Matcher {
        return Utf8Matcher(this, input)
    }

    /**
     * Returns whether this pattern occurs in the supplied UTF-8 input.
     * 
     * @param input borrowed UTF-8 input retained only for this call
     * @return whether the pattern occurs
     */
    fun find(input: Utf8Input?): Boolean {
        val arrayInput = Objects.requireNonNull<Utf8Input?>(input, "input") as ArrayUtf8Input
        if (!canFindWithoutMatcher()) {
            return Utf8Matcher(this, arrayInput).find()
        }
        val scanner = arrayInput.scanner()
        val listener: SafeReMatchDiagnostics = diagnostics()
        if (SafeReMatchDiagnostics.isEnabled(listener)) {
            return findWithDiagnostics(scanner)
        }
        return if (literalAlternation != null)
            findWithLiteralAlternation(scanner)
        else
            findWithoutDiagnostics(scanner)
    }

    /**
     * Answers [.find] with the literal-alternation search when it applies. Kept out
     * of [.findWithoutDiagnostics], whose compiled code is sensitive to its bytecode size.
     */
    private fun findWithLiteralAlternation(scanner: Utf8InputScanner): Boolean {
        val match =
            if (enginePathOptions.literalFastPaths)
                literalAlternation!!.find(scanner, 0)
            else
                LiteralAlternation.FALLBACK
        return if (match == LiteralAlternation.FALLBACK)
            findWithoutDiagnostics(scanner)
        else
            match != LiteralAlternation.NO_MATCH
    }

    /**
     * Returns whether the matcher-free existence searches ([.find] and [ ][.asPredicate]) can answer for this pattern.
     * 
     * 
     * Those paths use cheap checks followed by the forward first-match DFA or an end-anchored
     * reverse search, with exact NFA verification and fallback. Patterns with grapheme semantics or
     * loop registers use a matcher instead, so they get its full strategy sequence.
     */
    private fun canFindWithoutMatcher(): Boolean {
        return enginePathOptions.dfa && !prog.hasGraphemeSemantics() && prog.numLoopRegs() == 0
    }

    internal fun findWithDiagnostics(scanner: Utf8InputScanner): Boolean {
        val listener: SafeReMatchDiagnostics = diagnostics()
        val accumulator = DiagnosticAccumulator()
        val matched = findWithDiagnostics(scanner, accumulator)
        accumulator.matchCount(if (matched) 1 else 0)
        val outcome = if (matched) MatchOutcome.MATCH else MatchOutcome.NO_MATCH
        val event =
            accumulator.toEvent(
                descriptor(), MatchOperation.FIND, outcome, CaptureMode.NONE, scanner.length
            )
        listener.onOperationCompleted(event)
        return matched
    }

    private fun findLiteralMatch(scanner: Utf8InputScanner): Boolean {
        if (prog.anchorStart()) {
            return scanner.startsWith(literalMatchUtf8!!, 0)
        }
        return (scanner.indexOf(
            literalMatchUtf8!!,
            literalMatchFailure!!,
            literalMatchShifts!!,
            0,
            literalMatchRareByteOffset
        )
                >= 0)
    }

    internal fun findWithoutDiagnostics(scanner: Utf8InputScanner): Boolean {
        val length = scanner.length
        if (matchDescriptor.minMatchLength > 0 && length < matchDescriptor.minMatchLength) {
            return false
        }
        if (literalMatchUtf8 != null && !literalFoldCase()) {
            return findLiteralMatch(scanner)
        }
        if (enginePathOptions.keywordAlternationFastPath
            && matchDescriptor.keywordAlternation != null
        ) {
            return matchDescriptor.keywordAlternation.find(scanner, 0) >= 0
        }
        if (prog.anchorStart()) {
            if (anchoredPrefixUtf8 != null) {
                if (!scanner.startsWith(anchoredPrefixUtf8, 0)) {
                    return false
                }
            } else if (anchoredCharClassPrefix != null) {
                if (scanner.length == 0) {
                    return false
                }
                val cp = scanner.codePointAt(0)
                if (!anchoredCharClassPrefix.contains(cp)) {
                    return false
                }
            }
        }
        var endAnchoredStart = endAnchoredSearchStart(scanner, 0, false, null)
        if (endAnchoredStart == -1) {
            return false
        }
        if (endAnchoredStart >= 0 && findAt(scanner, endAnchoredStart)) {
            return true
        }
        val findRejectPrefilter = utf8FindRejectPrefilter()
        val rejectAfterStart =
            findRejectPrefilter != null && startPrecedesFindReject
                    && length < MAX_LITERAL_START_FIRST_LENGTH
        if (findRejectPrefilter != null && !rejectAfterStart && !startSubsumesFindReject && findRejectPrefilter.canReject(
                null,
                scanner,
                0,
                enginePathOptions
            )
        ) {
            return false
        }
        var searchStart = 0
        var startPositionPreselected = false
        if (enginePathOptions.startAcceleration
            && utf8StartAccelerator != null && !prog.anchorStart()
        ) {
            searchStart = findNextUtf8StartCandidate(scanner, null)
            if (searchStart < 0
                || (rejectAfterStart
                        && length - searchStart >= MIN_REJECT_AFTER_START_REMAINING && findRejectPrefilter.canRejectTail(
                    null, scanner, searchStart, enginePathOptions
                ))
            ) {
                return false
            }
            startPositionPreselected = true
        }
        if (endAnchoredStart == END_ANCHORED_SEARCH_DEFERRED) {
            endAnchoredStart = endAnchoredSearchStart(scanner, searchStart, false, null, false)
            if (endAnchoredStart == -1) {
                return false
            }
            if (endAnchoredStart >= 0 && findAt(scanner, endAnchoredStart)) {
                return true
            }
        }
        // The forward DFA checks a stripped end anchor itself, as the matcher's DFA pass relies on, so
        // end-anchored programs that the reverse probe could not settle avoid the NFA here too.
        val result =
            forwardFirstMatchDfa()
                .doSearch(scanner, searchStart, prog.anchorStart(), false, startPositionPreselected)
        if (result != null) {
            return result.matched
        }
        return (Nfa.search(
            prog,
            scanner,
            searchStart,
            length,
            length,
            0,
            Nfa.Anchor.UNANCHORED,
            Nfa.MatchKind.FIRST_MATCH,
            0,
            null
        )
                != null)
    }

    private fun findWithDiagnostics(scanner: Utf8InputScanner, diagnostics: DiagnosticAccumulator): Boolean {
        val length = scanner.length
        if (matchDescriptor.minMatchLength > 0 && length < matchDescriptor.minMatchLength) {
            diagnostics.boundary(MatchStrategy.LENGTH)
            return false
        }
        if (literalMatchUtf8 != null && !literalFoldCase()) {
            val matched = findLiteralMatch(scanner)
            diagnostics.boundary(MatchStrategy.LITERAL)
            return matched
        }
        if (enginePathOptions.keywordAlternationFastPath
            && matchDescriptor.keywordAlternation != null
        ) {
            val matched = matchDescriptor.keywordAlternation.find(scanner, 0) >= 0
            diagnostics.boundary(MatchStrategy.KEYWORD)
            return matched
        }
        if (literalAlternation != null && enginePathOptions.literalFastPaths) {
            val match = literalAlternation.find(scanner, 0)
            if (match != LiteralAlternation.FALLBACK) {
                diagnostics.boundary(MatchStrategy.LITERAL_ALTERNATION)
                return match != LiteralAlternation.NO_MATCH
            }
        }
        if (prog.anchorStart()) {
            if (anchoredPrefixUtf8 != null) {
                if (!scanner.startsWith(anchoredPrefixUtf8, 0)) {
                    diagnostics.participate(MatchStrategy.LITERAL, StrategyRole.REJECT_PREFILTER)
                    diagnostics.boundary(MatchStrategy.LITERAL)
                    return false
                }
            } else if (anchoredCharClassPrefix != null) {
                if (scanner.length == 0) {
                    diagnostics.participate(MatchStrategy.CHARACTER_CLASS, StrategyRole.REJECT_PREFILTER)
                    diagnostics.boundary(MatchStrategy.CHARACTER_CLASS)
                    return false
                }
                val cp = scanner.codePointAt(0)
                if (!anchoredCharClassPrefix.contains(cp)) {
                    diagnostics.participate(MatchStrategy.CHARACTER_CLASS, StrategyRole.REJECT_PREFILTER)
                    diagnostics.boundary(MatchStrategy.CHARACTER_CLASS)
                    return false
                }
            }
        }
        var endAnchoredStart = endAnchoredSearchStart(scanner, 0, false, diagnostics)
        if (endAnchoredStart == -1) {
            return false
        }
        if (endAnchoredStart >= 0 && findAt(scanner, endAnchoredStart, diagnostics)) {
            return true
        }
        val findRejectPrefilter = utf8FindRejectPrefilter()
        val rejectAfterStart =
            findRejectPrefilter != null && startPrecedesFindReject
                    && length < MAX_LITERAL_START_FIRST_LENGTH
        if (findRejectPrefilter != null && !rejectAfterStart && !startSubsumesFindReject && rejectsWithDiagnostics(
                findRejectPrefilter,
                scanner,
                0,
                diagnostics
            )
        ) {
            return false
        }
        var searchStart = 0
        var startPositionPreselected = false
        if (enginePathOptions.startAcceleration
            && utf8StartAccelerator != null && !prog.anchorStart()
        ) {
            searchStart = findNextUtf8StartCandidate(scanner, diagnostics)
            if (searchStart < 0
                || (rejectAfterStart
                        && length - searchStart >= MIN_REJECT_AFTER_START_REMAINING && rejectsTailWithDiagnostics(
                    findRejectPrefilter, scanner, searchStart, diagnostics
                ))
            ) {
                return false
            }
            startPositionPreselected = true
        }
        if (endAnchoredStart == END_ANCHORED_SEARCH_DEFERRED) {
            endAnchoredStart = endAnchoredSearchStart(scanner, searchStart, false, diagnostics, false)
            if (endAnchoredStart == -1) {
                return false
            }
            if (endAnchoredStart >= 0 && findAt(scanner, endAnchoredStart, diagnostics)) {
                return true
            }
        }
        diagnostics.participate(MatchStrategy.DFA, StrategyRole.REJECT_PREFILTER)
        diagnostics.incrementForwardDfaSearchCount()
        val result =
            forwardFirstMatchDfa()
                .doSearch(scanner, searchStart, prog.anchorStart(), false, startPositionPreselected)
        if (result != null) {
            diagnostics.boundary(MatchStrategy.DFA)
            return result.matched
        }
        diagnostics.decision(
            MatchStrategy.DFA, StrategyDisposition.FALLBACK, StrategyReason.DFA_BUDGET_EXCEEDED
        )
        val matched =
            (Nfa.search(
                prog,
                scanner,
                searchStart,
                length,
                length,
                0,
                Nfa.Anchor.UNANCHORED,
                Nfa.MatchKind.FIRST_MATCH,
                0,
                null
            )
                    != null)
        diagnostics.boundary(MatchStrategy.NFA)
        return matched
    }

    /** Shares candidate selection between the diagnostic and non-diagnostic matcher-free paths.  */
    private fun findNextUtf8StartCandidate(
        scanner: Utf8InputScanner, diagnostics: DiagnosticAccumulator?
    ): Int {
        val strategy = utf8StartAccelerator!!.policy().strategy
        if (diagnostics != null && strategy != null) {
            diagnostics.participate(strategy, StrategyRole.START_ACCELERATION)
        }
        if (utf8StartAccelerator is Utf8StartAccelerator.LeadingExpansion
            && utf8StartAccelerator.wideLeadingClass
            && utf8StartAccelerator.minRepetition == 0 && !utf8StartAccelerator.hasLeadingAssertions && utf8StartAccelerator.inner is Utf8StartAccelerator.Literal
        ) {
            // Preserve the wide expansion's short-input decline to avoid adding literal-scan setup
            // to native verification. Nearby hits below retain its minimum profitable skip too.
            if (scanner.length < MultiAnchorCompiler.MIN_WIDE_LEADING_SKIP) {
                return 0
            }
            // Existence does not need the expanded match start. Every match has a suffix at or after
            // the first inner literal, and that suffix is itself a match with zero leading repetitions.
            // Keep the full scanner so assertions retain their input context. One ordinary search from
            // this candidate verifies the tail and handles later candidates without repeated probes.
            val candidate: Int = utf8StartAccelerator.findInnerCandidate(scanner, 0)
            if (candidate >= 0) {
                // Leave nearby hits to the forward search, as in wide leading expansion.
                return if (candidate < MultiAnchorCompiler.MIN_WIDE_LEADING_SKIP) 0 else candidate
            }
        } else if (utf8StartAccelerator is Utf8StartAccelerator.LeadingExpansion
            && utf8StartAccelerator.canVerifyAtInner()
        ) {
            val innerMatch: Int = utf8StartAccelerator.findInnerCandidate(scanner, 0)
            if (innerMatch >= 0) {
                // With zero repetitions and no leading assertions, an unanchored rejection from the
                // inner candidate also rejects every expanded start. Do this before walking backward.
                if (diagnostics != null) {
                    diagnostics.participate(MatchStrategy.DFA, StrategyRole.CANDIDATE_VERIFICATION)
                    diagnostics.incrementForwardDfaSearchCount()
                }
                val result =
                    forwardFirstMatchDfa().doSearch(scanner, innerMatch, false, false, true)
                if (result != null && !result.matched) {
                    if (diagnostics != null) {
                        diagnostics.boundary(MatchStrategy.DFA)
                    }
                    return -1
                }
                if (result == null && diagnostics != null) {
                    diagnostics.decision(
                        MatchStrategy.DFA, StrategyDisposition.FALLBACK, StrategyReason.DFA_BUDGET_EXCEEDED
                    )
                }
                return utf8StartAccelerator.expandBackward(scanner, innerMatch, 0)
            }
        } else {
            val candidate: Int = Utf8StartAccelerator.findNextCandidate(utf8StartAccelerator, scanner, 0)
            if (candidate >= 0) {
                return candidate
            }
        }
        if (diagnostics != null && strategy != null) {
            diagnostics.boundary(strategy)
        }
        return -1
    }

    /** A deferred probe may retry once after ordinary rejection and start acceleration.  */
    /**
     * Proposes a start of an end-anchored search before any forward prefix scan. With `leftmostStart`, scans for the leftmost start needed for spans; otherwise stops at the first
     * accepted candidate for existence-only matching. Returns `-1` for a proven rejection,
     * [.END_ANCHORED_SEARCH_DEFERRED] when the probe needs rejection and start acceleration
     * before one full retry, or [.END_ANCHORED_SEARCH_FALLBACK] when ordinary search is
     * required. A proposed start still requires exact matching; failure to verify it must fall back
     * to ordinary search.
     */
    @JvmOverloads
    internal fun endAnchoredSearchStart(
        scanner: InputScanner,
        searchFrom: Int,
        leftmostStart: Boolean,
        diagnostics: DiagnosticAccumulator?,
        speculative: Boolean = true
    ): Int {
        if (!prog.anchorEnd() || scanner.length < MIN_REVERSE_FIRST_LEN || !enginePathOptions.dfa || !enginePathOptions.reverseDfa || !canUseReverseDfa()) {
            return END_ANCHORED_SEARCH_FALLBACK
        }
        if (terminalPrefilterRejects(scanner, searchFrom, diagnostics)) {
            return -1
        }
        val reverseProgram = flatReverseDfaProg()
        if (reverseProgram == null || reverseProgram.hasGraphemeSemantics()
            || reverseProgram.numLoopRegs() != 0
        ) {
            return END_ANCHORED_SEARCH_FALLBACK
        }
        val reverse = reverseDfa()
        if (reverse == null) {
            return END_ANCHORED_SEARCH_FALLBACK
        }
        // A literal run needs at most four UTF-8 units per consuming instruction. Leave a small
        // minimum for short loops; exceeding the probe defers to ordinary start acceleration.
        val probeUnits = Math.min(
            Integer.MAX_VALUE.toLong(),
            Math.max(MIN_END_ANCHORED_PROBE_UNITS.toLong(), 4L * reverseProgram.size)
        ).toInt()
        return searchEndAnchoredStart(
            reverse,
            scanner,
            searchFrom,
            leftmostStart,
            diagnostics,
            if (speculative) probeUnits else Integer.MAX_VALUE
        )
    }

    private fun searchEndAnchoredStart(
        reverse: Dfa,
        scanner: InputScanner,
        searchFrom: Int,
        leftmostStart: Boolean,
        diagnostics: DiagnosticAccumulator?,
        maxInputUnits: Int
    ): Int {
        if (diagnostics != null) {
            diagnostics.participate(MatchStrategy.DFA, StrategyRole.REJECT_PREFILTER)
            diagnostics.incrementReverseDfaSearchCount()
        }
        val length = scanner.length
        val result =
            reverse.doSearchReverse(scanner, length, searchFrom, true, leftmostStart, maxInputUnits)
        var start = -1
        var ambiguous = false
        var budgetExceeded = result == null
        var windowExceeded = result != null && result.scanLimitReached
        if (result != null && result.matched && result.pos >= searchFrom) {
            start = result.pos
            ambiguous = result.ambiguous
        }
        // Non-multiline $ also accepts the position before a final line terminator. Span searches
        // compare both endpoints for the leftmost start; exact matching decides the prioritized end.
        if (!budgetExceeded && prog.dollarAnchorEnd()) {
            val trailingStart = scanner.trailingLineTerminatorStart(prog.dollarAnchorUnixLines(), length)
            if (trailingStart >= searchFrom) {
                if (diagnostics != null) {
                    diagnostics.incrementReverseDfaSearchCount()
                }
                val alternative =
                    reverse.doSearchReverse(
                        scanner, trailingStart, searchFrom, true, leftmostStart, maxInputUnits
                    )
                budgetExceeded = alternative == null
                windowExceeded = windowExceeded or (alternative != null && alternative.scanLimitReached)
                if (alternative != null && alternative.matched
                    && alternative.pos >= searchFrom && (start < 0 || alternative.pos < start)
                ) {
                    start = alternative.pos
                    ambiguous = alternative.ambiguous
                }
            }
        }
        if (budgetExceeded || (leftmostStart && ambiguous)) {
            if (diagnostics != null) {
                diagnostics.decision(
                    MatchStrategy.DFA,
                    if (budgetExceeded) StrategyDisposition.FALLBACK else StrategyDisposition.BYPASSED,
                    if (budgetExceeded)
                        StrategyReason.DFA_BUDGET_EXCEEDED
                    else
                        StrategyReason.AUTHORITATIVE_BOUNDS_REQUIRED
                )
            }
            return END_ANCHORED_SEARCH_FALLBACK
        }
        if (windowExceeded && (leftmostStart || start < 0)) {
            if (diagnostics != null) {
                diagnostics.decision(
                    MatchStrategy.DFA, StrategyDisposition.FALLBACK, StrategyReason.WORK_BUDGET_EXCEEDED
                )
            }
            return END_ANCHORED_SEARCH_DEFERRED
        }
        if (start < 0 && diagnostics != null) {
            diagnostics.boundary(MatchStrategy.DFA)
        }
        return start
    }

    /**
     * Returns whether a match starts at `start`. Uses BitState when the remaining input fits
     * its bitmap, as `Matcher`'s end-anchored verification does, and the NFA otherwise or when
     * BitState exceeds its work budget.
     */
    private fun findAt(scanner: InputScanner, start: Int, diagnostics: DiagnosticAccumulator? = null): Boolean {
        val length = scanner.length
        val maxBitStateLen: Int = BitState.maxTextSize(prog)
        if (enginePathOptions.bitState && maxBitStateLen >= 0 && length - start <= maxBitStateLen) {
            if (diagnostics != null) {
                diagnostics.participate(MatchStrategy.BIT_STATE, StrategyRole.CANDIDATE_VERIFICATION)
            }
            val cache = this.orCreateThreadCache
            val bitState: BitState =
                BitState.getOrCreate(
                    borrowBitState(cache), prog, scanner, start, length, 2, false, prog.anchorEnd()
                )
            val result = bitState.doSearch(start, start, true)
            returnBitState(cache, bitState)
            if (!bitState.budgetExceeded()) {
                if (result != null && diagnostics != null) {
                    diagnostics.boundary(MatchStrategy.BIT_STATE)
                }
                return result != null
            }
            if (diagnostics != null) {
                diagnostics.decision(
                    MatchStrategy.BIT_STATE,
                    StrategyDisposition.FALLBACK,
                    StrategyReason.WORK_BUDGET_EXCEEDED
                )
            }
        }
        if (diagnostics != null) {
            diagnostics.participate(MatchStrategy.NFA, StrategyRole.CANDIDATE_VERIFICATION)
        }
        val matched =
            (Nfa.search(
                prog,
                scanner,
                start,
                scanner.length,
                scanner.length,
                0,
                Nfa.Anchor.ANCHORED,
                Nfa.MatchKind.FIRST_MATCH,
                0,
                null
            )
                    != null)
        if (matched && diagnostics != null) {
            diagnostics.boundary(MatchStrategy.NFA)
        }
        return matched
    }

    /**
     * Returns the match flags specified when this pattern was compiled.
     * 
     * @return the match flags
     */
    fun flags(): Int {
        return flags
    }

    /**
     * Returns the regular expression from which this pattern was compiled.
     * 
     * @return the source of this pattern
     */
    fun pattern(): String? {
        return pattern
    }

    /**
     * Splits the given input around matches of this pattern.
     * 
     * 
     * The `limit` parameter controls the number of times the pattern is applied:
     * 
     * 
     *  * If `limit > 0`, the pattern is applied at most `limit - 1` times, and the
     * resulting array will have at most `limit` entries.
     *  * If `limit == 0`, the pattern is applied as many times as possible, and trailing
     * empty strings are discarded.
     *  * If `limit < 0`, the pattern is applied as many times as possible, and trailing
     * empty strings are retained.
     * 
     * 
     * @param input the character sequence to be split
     * @param limit the result threshold
     * @return the array of strings computed by splitting the input around matches of this pattern
     */
    /**
     * Splits the given input around matches of this pattern. Trailing empty strings are discarded.
     * 
     * @param input the character sequence to be split
     * @return the array of strings computed by splitting the input around matches of this pattern
     */
    @JvmOverloads
    fun split(input: CharSequence, limit: Int = 0): Array<String?> {
        val text: String = charSequenceToString(input)
        val m = matcher(text)
        val buffer = SplitBuffer()
        val matchesCount = m.findSplitPositions(limit, buffer)
        if (matchesCount == 0) {
            return arrayOf<String?>(text)
        }
        val partsCount = if (limit > 0) Math.min(limit, matchesCount + 1) else matchesCount + 1
        var parts = arrayOfNulls<String>(partsCount)
        var last = 0
        var i = 0
        while (i < partsCount - 1) {
            val start = buffer.array[2 * i]
            val end = buffer.array[2 * i + 1]
            parts[i] = text.substring(last, start)
            last = end
            i++
        }
        parts[i] = text.substring(last)

        if (limit == 0) {
            var end = partsCount
            while (end > 0 && parts[end - 1]!!.isEmpty()) {
                end--
            }
            if (end < partsCount) {
                parts = Arrays.copyOf<String?>(parts, end)
            }
        }
        return parts
    }

    /**
     * Splits the given input around matches of this pattern, returning both the substrings between
     * matches and the matching delimiters, interleaved.
     * 
     * 
     * The `limit` parameter controls the number of times the pattern is applied:
     * 
     * 
     *  * If `limit > 0`, the pattern is applied at most `limit - 1` times, and the
     * resulting array will have at most `2 * limit - 1` entries.
     *  * If `limit == 0`, the pattern is applied as many times as possible, and trailing
     * empty strings are discarded.
     *  * If `limit < 0`, the pattern is applied as many times as possible, and trailing
     * empty strings are retained.
     * 
     * 
     * @param input the character sequence to be split
     * @param limit the result threshold
     * @return the array of strings computed by splitting the input around matches of this pattern,
     * with the matching delimiters interleaved
     * @since 21
     */
    /**
     * Splits the given input around matches of this pattern, returning both the substrings between
     * matches and the matching delimiters, interleaved. The resulting array alternates between
     * substrings and delimiters: `[substring, delimiter, substring, delimiter, ..., substring]`.
     * 
     * 
     * This is equivalent to `splitWithDelimiters(input, 0)`.
     * 
     * @param input the character sequence to be split
     * @return the array of strings computed by splitting the input around matches of this pattern,
     * with the matching delimiters interleaved
     * @since 21
     */
    @JvmOverloads
    fun splitWithDelimiters(input: CharSequence, limit: Int = 0): Array<String?> {
        val text: String = charSequenceToString(input)
        val m = matcher(text)
        val buffer = SplitBuffer()
        val matchesCount = m.findSplitPositions(limit, buffer)
        if (matchesCount == 0) {
            return arrayOf<String?>(text)
        }
        var partsCount = 2 * matchesCount + 1
        if (limit > 0) {
            partsCount = Math.min(2L * limit - 1, partsCount.toLong()).toInt()
        }
        var parts = arrayOfNulls<String>(partsCount)
        var last = 0
        var i = 0
        var matchIdx = 0
        while (i < partsCount - 1) {
            val start = buffer.array[2 * matchIdx]
            val end = buffer.array[2 * matchIdx + 1]
            parts[i++] = text.substring(last, start)
            parts[i++] = text.substring(start, end)
            last = end
            matchIdx++
        }
        parts[i] = text.substring(last)

        if (limit == 0) {
            var end = partsCount
            while (end > 0 && parts[end - 1]!!.isEmpty()) {
                end--
            }
            if (end < partsCount) {
                parts = Arrays.copyOf<String?>(parts, end)
            }
        }
        return parts
    }

    /**
     * Creates a stream of strings split from the given input sequence around matches of this pattern.
     * The stream contains the same strings that [.split] would return, produced
     * lazily.
     * 
     * @param input the character sequence to be split
     * @return a sequential stream of strings computed by splitting the input around matches of this
     * pattern
     */
    fun splitAsStream(input: CharSequence): Stream<String?> {
        return StreamSupport.stream<String?>(
            Supplier { Arrays.spliterator<String?>(split(input, 0)) },
            Spliterator.ORDERED or Spliterator.NONNULL,
            false
        )
    }

    /**
     * Creates a predicate that tests if this pattern is found in a given input string. The predicate
     * behaves as if calling `matcher(input).find()`.
     * 
     * @return a predicate for partial matching
     */
    fun asPredicate(): Predicate<String?> {
        return Predicate { input: String? -> this.findInString(input!!) }
    }

    /**
     * Returns whether this pattern occurs in `input`, with the same result as `matcher(input).find()` but without creating a matcher or computing match bounds. Follows the
     * same strategy sequence as [.findWithoutDiagnostics], with the String
     * literal, prefix and start-accelerator data. With diagnostics enabled, delegates to a matcher so
     * the reported operation is unchanged.
     */
    private fun findInString(input: String): Boolean {
        if (!canFindWithoutMatcher() || SafeReMatchDiagnostics.isEnabled(diagnostics())) {
            return matcher(input).find()
        }
        val length: Int = input.length
        if (matchDescriptor.minMatchLength > 0 && length < matchDescriptor.minMatchLength) {
            return false
        }
        // literalMatchUtf8 is non-null only when literal fast paths are enabled.
        if (literalMatchUtf8 != null && !literalFoldCase()) {
            val literal = matchDescriptor.literalMatch
            return if (prog.anchorStart()) input.startsWith(literal!!) else input.indexOf(literal!!) >= 0
        }
        val scanner = StringInputScanner(input)
        if (enginePathOptions.keywordAlternationFastPath
            && matchDescriptor.keywordAlternation != null
        ) {
            return matchDescriptor.keywordAlternation.find(scanner, 0) >= 0
        }
        if (prog.anchorStart()) {
            if (anchoredPrefixUtf8 != null) {
                if (!input.startsWith(anchoredPrefix!!)) {
                    return false
                }
            } else if (anchoredCharClassPrefix != null) {
                if (length == 0 || !anchoredCharClassPrefix.contains(input.codePointAt(0))) {
                    return false
                }
            }
        }
        var endAnchoredStart = endAnchoredSearchStart(scanner, 0, false, null)
        if (endAnchoredStart == -1) {
            return false
        }
        if (endAnchoredStart >= 0 && findAt(scanner, endAnchoredStart)) {
            return true
        }
        val findRejectPrefilter = findRejectPrefilter()
        val rejectAfterStart =
            findRejectPrefilter != null && startPrecedesFindReject
                    && length < MAX_LITERAL_START_FIRST_LENGTH
        if (findRejectPrefilter != null && !rejectAfterStart && !startSubsumesFindReject && findRejectPrefilter.canReject(
                input,
                null,
                0,
                enginePathOptions
            )
        ) {
            return false
        }
        var searchStart = 0
        var startPositionPreselected = false
        if (enginePathOptions.startAcceleration
            && stringStartAccelerator != null && !prog.anchorStart()
        ) {
            searchStart = findNextStringStartCandidate(scanner, input)
            if (searchStart < 0
                || (rejectAfterStart
                        && length - searchStart >= MIN_REJECT_AFTER_START_REMAINING && findRejectPrefilter.canRejectTail(
                    input,
                    null,
                    searchStart,
                    enginePathOptions
                ))
            ) {
                return false
            }
            startPositionPreselected = true
        }
        if (endAnchoredStart == END_ANCHORED_SEARCH_DEFERRED) {
            endAnchoredStart = endAnchoredSearchStart(scanner, searchStart, false, null, false)
            if (endAnchoredStart == -1) {
                return false
            }
            if (endAnchoredStart >= 0 && findAt(scanner, endAnchoredStart)) {
                return true
            }
        }
        // The forward DFA checks a stripped end anchor itself, as the matcher's DFA pass relies on, so
        // end-anchored programs that the reverse probe could not settle avoid the NFA here too.
        val result =
            forwardFirstMatchDfa()
                .doSearch(scanner, searchStart, prog.anchorStart(), false, startPositionPreselected)
        if (result != null) {
            return result.matched
        }
        return (Nfa.search(
            prog,
            scanner,
            searchStart,
            length,
            length,
            0,
            Nfa.Anchor.UNANCHORED,
            Nfa.MatchKind.FIRST_MATCH,
            0,
            null
        )
                != null)
    }

    /**
     * Selects the first String start candidate for [.findInString]. Literal scans use UTF-16
     * indices, so candidates inside a surrogate pair are skipped, as in the matcher. A nullable
     * leading expansion is first verified at its inner candidate, as in [ ][.findNextUtf8StartCandidate].
     */
    private fun findNextStringStartCandidate(scanner: StringInputScanner, text: String?): Int {
        val accelerator = stringStartAccelerator
        val haystack = text
        if (accelerator == null || haystack == null) {
            return -1
        }
        val unixLines = prog.lineStartUnixLines()
        if (accelerator is StringStartAccelerator.LeadingExpansion
            && accelerator.canVerifyAtInner()
        ) {
            var innerMatch: Int = accelerator.findInnerCandidate(haystack, 0, unixLines)
            while (innerMatch > 0 && !scanner.isCodePointBoundary(innerMatch)) {
                innerMatch = accelerator.findInnerCandidate(haystack, innerMatch + 1, unixLines)
            }
            if (innerMatch < 0) {
                return -1
            }
            // With zero repetitions and no leading assertions, an unanchored rejection from the inner
            // candidate also rejects every expanded start.
            val result =
                forwardFirstMatchDfa().doSearch(scanner, innerMatch, false, false, true)
            if (result != null && !result.matched) {
                return -1
            }
            return accelerator.expandBackward(haystack, innerMatch, 0)
        }
        var candidate: Int =
            StringStartAccelerator.findNextCandidate(accelerator, haystack, 0, unixLines)
        while (candidate > 0 && !scanner.isCodePointBoundary(candidate)) {
            candidate =
                StringStartAccelerator.findNextCandidate(
                    stringStartAccelerator, text, candidate + 1, unixLines
                )
        }
        return candidate
    }

    /**
     * Creates a predicate that tests if this pattern matches a given input string in its entirety.
     * The predicate behaves as if calling `matcher(input).matches()`.
     * 
     * @return a predicate for full matching
     */
    fun asMatchPredicate(): Predicate<String?> {
        return Predicate { input: String? -> matcher(input).matches() }
    }

    override fun toString(): String {
        return pattern!!
    }

    // ---------------------------------------------------------------------------
    // Package-private accessors for Matcher
    // ---------------------------------------------------------------------------
    /** Returns the compiled program.  */
    internal fun prog(): Prog {
        return prog
    }

    internal fun enginePathOptions(): EnginePathOptions {
        return enginePathOptions
    }

    /**
     * Replaces the default prepared runner if it is still `lazy`, so later operations skip the
     * resolved lazy wrapper. Runners are immutable, so a racy publication is safe; a lazy runner
     * nested inside another runner keeps its own cached resolution instead.
     */
    internal fun replacePreparedMatchRunner(
        lazy: PreparedMatchRunner?, resolved: PreparedMatchRunner?
    ) {
        if (defaultPreparedMatchRunner === lazy) {
            defaultPreparedMatchRunner = resolved
        }
    }

    internal fun preparedMatchRunner(regionActive: Boolean): PreparedMatchRunner? {
        return if (regionActive) regionPreparedMatchRunner else defaultPreparedMatchRunner
    }

    private fun createPreparedRunner(regionActive: Boolean): PreparedMatchRunner? {
        val fallback = createBasePreparedRunner(regionActive)
        if (literalAlternation != null && enginePathOptions.literalFastPaths) {
            return LiteralAlternationPreparedRunner(literalAlternation!!, fallback!!)
        }
        return fallback
    }

    private fun createBasePreparedRunner(regionActive: Boolean): PreparedMatchRunner {
        val literal = matchDescriptor.literalMatch
        if (canUseLiteralPreparedRunner(literal, prog.numCaptures(), enginePathOptions)) {
            return LiteralPreparedRunner(
                literal,
                matchDescriptor.literalFoldCase,
                literalMatchUtf8,
                literalMatchFailure,
                literalMatchShifts,
                literalMatchRareByteOffset,
                prog.anchorStart(),
                if (matchDescriptor.literalFoldCase)
                    createLiteralFallbackRunner(regionActive)
                else
                    FallbackPreparedRunner.INSTANCE,
                matchDescriptor.classHashChain
            )
        }

        return createLiteralFallbackRunner(regionActive)
    }

    private fun createLiteralFallbackRunner(regionActive: Boolean): PreparedMatchRunner {
        val singleCharClass = matchDescriptor.singleCharClass
        val charClassMatch = matchDescriptor.charClassMatch
        if (enginePathOptions.charClassMatchFastPaths
            && (singleCharClass != null || charClassMatch != null)
        ) {
            return SingleCharClassPreparedRunner(
                singleCharClass, charClassMatch, prog.anchorStart(), !astAnalysis.hasLazy
            )
        }

        if (regionActive) {
            return FallbackPreparedRunner.INSTANCE
        }

        val keywordAlternation = matchDescriptor.keywordAlternation
        if (enginePathOptions.keywordAlternationFastPath && keywordAlternation != null) {
            return KeywordAlternationPreparedRunner(
                keywordAlternation, prog.numCaptures(), prog.anchorStart()
            )
        }

        val fallback: PreparedMatchRunner =
            if (mayOnePassPrimary())
                OnePassAnchoredPreparedRunner(prog.numCaptures())
            else
                FallbackPreparedRunner.INSTANCE
        if (mayUseShiftDfa()) {
            return LazyShiftDfaPreparedRunner(this, fallback)
        }

        return fallback
    }

    fun innerCapturesObserved(): Boolean {
        return innerCapturesObserved
    }

    fun recordInnerCaptureAccess() {
        if (!innerCapturesObserved) {
            innerCapturesObserved = true
        }
    }

    private fun existingThreadCache(): ThreadCache? {
        val ref = threadCache.get()
        return if (ref != null) ref.get() else null
    }

    internal val orCreateThreadCache: ThreadCache
        /**
         * Returns the current thread's cache, creating it if needed. A [Matcher] fetches this once
         * and passes it to the cache accessors, so each lookup does not repeat the [ThreadLocal]
         * and [SoftReference] dereferences.
         */
        get() {
            var cache = existingThreadCache()
            if (cache == null) {
                cache = ThreadCache()
                threadCache.set(SoftReference<ThreadCache?>(cache))
            }
            return cache
        }

    /** Returns the thread-local cached BitState, or null if none has been cached yet.  */
    internal fun borrowBitState(): BitState? {
        val cache = existingThreadCache()
        if (cache == null) {
            return null
        }
        return borrowBitState(cache)
    }

    /** Returns a BitState to the thread-local cache for reuse by future Matchers.  */
    internal fun returnBitState(bs: BitState) {
        returnBitState(this.orCreateThreadCache, bs)
    }

    internal fun borrowNfa(): Nfa? {
        val cache = existingThreadCache()
        if (cache == null) {
            return null
        }
        return borrowNfa(cache)
    }

    internal fun returnNfa(nfa: Nfa) {
        returnNfa(this.orCreateThreadCache, nfa)
    }

    /**
     * Returns the thread-local cached forward DFA, creating it on first access. The DFA state cache
     * persists across Matcher instances, so repeated `pattern.matcher(t).find()` calls benefit
     * from warm DFA transitions.
     */
    internal fun flatProg(): Prog? {
        var p = flatProg
        if (p == null && enginePathOptions.dfa) {
            synchronized(this) {
                p = flatProg
                if (p == null) {
                    p = prog.flattenedCopy()
                    flatProg = p
                }
            }
        }
        return p
    }

    internal fun flatDfaProg(): Prog? {
        var p = flatDfaProg
        if (p == null && enginePathOptions.dfa) {
            // Only reached without loop registers, where the DFA program is the flattened program.
            p = flatProg()
            flatDfaProg = p
        }
        return p
    }

    private fun dfaStartAcceleration(): Boolean {
        return enginePathOptions.startAcceleration && dfaStartAcceleration
    }

    /** Returns whether DFA paths implement every instruction in [.flatDfaProg].  */
    internal fun forwardDfaSupported(): Boolean {
        return forwardDfaSupported
    }

    @JvmOverloads
    internal fun forwardFirstMatchDfa(cache: ThreadCache = this.orCreateThreadCache): Dfa {
        var dfa = cache.forwardFirstMatchDfa
        if (dfa == null) {
            dfa =
                Dfa(
                    flatDfaProg()!!,
                    MAX_DFA_STATES,
                    forwardDfaSetup(),
                    false,
                    if (dfaStartAcceleration()) utf8StartAccelerator else null,
                    if (dfaStartAcceleration()) stringStartAccelerator else null,
                    innerLiteralSearch
                )
            cache.forwardFirstMatchDfa = dfa
        }
        return dfa
    }

    @JvmOverloads
    internal fun forwardLongestMatchDfa(cache: ThreadCache = this.orCreateThreadCache): Dfa {
        var dfa = cache.forwardLongestMatchDfa
        if (dfa == null) {
            dfa =
                Dfa(
                    flatDfaProg()!!,
                    MAX_DFA_STATES,
                    forwardDfaSetup(),
                    true,
                    if (dfaStartAcceleration()) utf8StartAccelerator else null,
                    if (dfaStartAcceleration()) stringStartAccelerator else null
                )
            cache.forwardLongestMatchDfa = dfa
        }
        return dfa
    }

    /**
     * Returns the thread-local cached reverse DFA, creating it on first access. Triggers lazy
     * compilation of the reverse program if needed.
     */
    internal fun reverseDfa(): Dfa? {
        return reverseDfa(this.orCreateThreadCache)
    }

    internal fun reverseDfa(cache: ThreadCache): Dfa? {
        var dfa = cache.reverseDfa
        if (dfa == null) {
            val rp = flatReverseDfaProg()
            if (rp != null) {
                dfa = Dfa(rp, MAX_DFA_STATES, reverseDfaSetup()!!, true)
                cache.reverseDfa = dfa
            }
        }
        return dfa
    }

    /** Returns the currently cached forward first-match DFA without creating it. For tests.  */
    internal fun cachedForwardFirstMatchDfa(): Dfa? {
        val cache = existingThreadCache()
        return if (cache != null) cache.forwardFirstMatchDfa else null
    }

    /**
     * Returns whether this pattern satisfies the static preconditions shared by all [OnePass]
     * execution paths without building the automaton.
     * 
     * 
     * Lazy quantifiers and nullable alternations are excluded because [OnePass]'s
     * longest-match semantics disagree with leftmost-first quantifier and branch priority, grapheme
     * programs require the exact engine, and capture tracking is bounded by [ ][OnePass.MAX_CAPTURE_GROUPS].
     */
    private fun mayBuildOnePass(): Boolean {
        return enginePathOptions.onePass
                && !astAnalysis.hasLazy && !astAnalysis.hasNullableAlt && prog.numLoopRegs() == 0 && !prog.hasGraphemeSemantics() && prog.numCaptures() <= OnePass.MAX_CAPTURE_GROUPS
    }

    /**
     * Returns the lazily computed OnePass analysis results. The volatile fast path serves initialized
     * readers; the synchronized slow path prepares the analysis once under concurrent first use.
     */
    private fun onePassAnalysis(): OnePassAnalysis {
        var analysis = onePassAnalysis
        if (analysis == null) {
            synchronized(this) {
                analysis = onePassAnalysis
                if (analysis == null) {
                    if (!mayBuildOnePass()) {
                        analysis = OnePassAnalysis.DISABLED
                    } else {
                        val op: OnePass? = OnePass.build(prog)
                        // Primary matching requires the whole pattern to be non-nullable so unanchored / prefix
                        // searches cannot prefer a consuming path over an empty match.
                        val canPrimary = op != null && op.search("", false, 0) == null
                        val canFind = canPrimary && prog.anchorStart()
                        val canSubmatch = op != null && numGroups() > 0
                        analysis = OnePassAnalysis(op, canPrimary, canFind, canSubmatch)
                    }
                    onePassAnalysis = analysis
                }
            }
        }
        return analysis!!
    }

    /** Returns the one-pass automaton, or `null` if the pattern is not one-pass.  */
    internal fun onePass(): OnePass? {
        return onePassAnalysis().onePass
    }

    /**
     * Returns whether OnePass can be used as the primary matching engine, bypassing the DFA entirely.
     * This is true when the pattern is OnePass-eligible, non-nullable, and has no lazy quantifiers.
     * The non-nullable restriction prevents leftmost-first ambiguity bugs where a nullable
     * alternative (e.g., `a*` in `a*|c.`) would incorrectly lose to a longer alternative
     * under OnePass's longest-match semantics.
     */
    fun canOnePassPrimary(): Boolean {
        return onePassAnalysis().canPrimary
    }

    /**
     * Returns whether OnePass can be used directly in `find()` for anchored patterns. This is
     * [.canOnePassPrimary] restricted to patterns anchored at the start.
     */
    fun canOnePassFind(): Boolean {
        return prog.anchorStart() && onePassAnalysis().canFind
    }

    /**
     * Returns whether [.canOnePassPrimary] might be true. If OnePass has not been built, this
     * uses only properties known without building it, so that choosing a prepared runner does not
     * build OnePass; the runner checks [.canOnePassPrimary] before using it.
     */
    private fun mayOnePassPrimary(): Boolean {
        val analysis = onePassAnalysis
        if (analysis != null) {
            return analysis.canPrimary
        }
        return mayBuildOnePass()
    }

    /**
     * Returns whether [.canOnePassSubmatch] might be true without building [OnePass].
     */
    private fun mayOnePassSubmatch(): Boolean {
        val analysis = onePassAnalysis
        if (analysis != null) {
            return analysis.canSubmatch
        }
        return mayBuildOnePass() && numGroups() > 0
    }

    /** Returns whether the OnePass analysis has been computed. For tests.  */
    internal fun onePassAnalyzed(): Boolean {
        return onePassAnalysis != null
    }

    /**
     * Cheap conservative upper bound on `shiftDfa() != null`. Uses only properties known
     * without running [ShiftDfa.compile] so that choosing a prepared runner does not
     * build [ShiftDfa]; [Matcher.LazyShiftDfaPreparedRunner] compiles [ShiftDfa]
     * lazily on the first `matches()` call.
     */
    private fun mayUseShiftDfa(): Boolean {
        val analysis = shiftDfaAnalysis
        if (analysis != null) {
            return analysis.shiftDfa != null
        }
        val literalSupersedesShiftDfa =
            canUseLiteralPreparedRunner(
                matchDescriptor.literalMatch, prog.numCaptures(), enginePathOptions
            )
                    && !matchDescriptor.literalFoldCase
        return enginePathOptions.shiftDfa
                && !literalSupersedesShiftDfa && prog.numCaptures() == 1 && astAnalysis.minMatchLength < ShiftDfa.MAX_STATES && !prog.hasGraphemeSemantics() && !prog.hasWordBoundary() && !prog.anchorEnd() && !prog.dollarAnchorEnd() && prog.start() != 0
    }

    private fun shiftDfaAnalysis(): ShiftDfaAnalysis {
        var analysis = shiftDfaAnalysis
        if (analysis == null) {
            synchronized(this) {
                analysis = shiftDfaAnalysis
                if (analysis == null) {
                    val compiled: ShiftDfa? = if (mayUseShiftDfa()) ShiftDfa.compile(prog) else null
                    analysis = if (compiled != null) ShiftDfaAnalysis(compiled) else ShiftDfaAnalysis.DISABLED
                    shiftDfaAnalysis = analysis
                }
            }
        }
        return analysis!!
    }

    internal fun shiftDfa(): ShiftDfa? {
        return shiftDfaAnalysis().shiftDfa
    }

    /** Returns whether the ShiftDfa analysis has been computed. For tests.  */
    internal fun shiftDfaAnalyzed(): Boolean {
        return shiftDfaAnalysis != null
    }

    /**
     * Returns whether OnePass can be used for submatch extraction in the sandwich path. This is true
     * when the pattern has user capturing groups, satisfies [.mayBuildOnePass], and compiles
     * to a deterministic one-pass automaton. Whole-pattern nullability without nullable alternation
     * (e.g. `(a)*`) remains eligible because match bounds are already determined by the DFA.
     */
    fun canOnePassSubmatch(): Boolean {
        return onePassAnalysis().canSubmatch
    }

    /**
     * Returns `true` when the DFA's leftmost-longest group(0) boundaries are guaranteed to
     * match RE2's leftmost-first semantics. The DFA uses POSIX leftmost-longest matching which can
     * disagree with Perl/RE2 leftmost-first semantics in three cases:
     * 
     * 
     *  1. Lazy quantifiers: prefer shortest match, but the DFA gives longest.
     *  1. Alternation: the DFA picks the longest branch, but RE2 picks the first matching branch.
     *  1. Bounded repetitions (`a{3,4}`): nested inside quantifiers, the DFA may find a
     * globally longer match by choosing fewer characters per iteration, while RE2 greedily
     * maximizes each iteration.
     * 
     * 
     * 
     * When this returns `false`, the DFA sandwich is skipped and the submatch engine
     * (BitState/NFA) determines the correct match boundaries.
     */
    fun dfaGroupZeroReliable(): Boolean {
        return prog.numLoopRegs() == 0
    }

    /**
     * Returns true if this pattern can participate in reverse DFA matching (e.g. unanchored find or
     * end-anchored reverse-first rejection).
     */
    fun canUseReverseDfa(): Boolean {
        return !prog.anchorStart() && !matchDescriptor.hasFindFastPath()
    }

    /**
     * Returns whether to build OnePass in the constructor. OnePass is built eagerly when `find()` can use it: directly for start-anchored patterns, and for capture extraction in `group()` when the pattern has capturing groups. For other patterns only `matches()` and
     * `lookingAt()` can use it, so it is built on first use.
     */
    private fun shouldEagerlyBuildOnePass(): Boolean {
        return !matchDescriptor.hasFindFastPath()
                && ((prog.anchorStart() && mayOnePassPrimary()) || mayOnePassSubmatch())
    }

    /**
     * Returns the literal prefix for this pattern, or `null` if the pattern has no fixed
     * literal prefix. Used for prefix acceleration in [Matcher.doFind].
     */
    internal fun prefix(): String? {
        return prefix
    }

    /** Returns whether the prefix should be matched case-insensitively.  */
    internal fun prefixFoldCase(): Boolean {
        return prefixFoldCase
    }

    /**
     * Returns a [CharClassScanInfo] of the character-class prefix, or `null` if the
     * pattern has no character-class prefix. Used for prefix acceleration in [Matcher.doFind]
     * when no literal prefix exists.
     */
    internal fun charClassPrefix(): CharClassScanInfo? {
        return charClassPrefix
    }

    internal fun anchoredPrefix(): String? {
        return anchoredPrefix
    }

    internal fun anchoredPrefixUtf8(): ByteArray? {
        return anchoredPrefixUtf8
    }

    internal fun anchoredCharClassPrefix(): CharClassScanInfo? {
        return anchoredCharClassPrefix
    }

    /** Returns the compiled UTF-8 start-position accelerator strategy, or `null`.  */
    internal fun utf8StartAccelerator(): Utf8StartAccelerator? {
        return utf8StartAccelerator
    }

    /** Returns the compiled String start-position accelerator strategy, or `null`.  */
    internal fun stringStartAccelerator(): StringStartAccelerator? {
        return stringStartAccelerator
    }

    internal fun matchDescriptor(): MatchDescriptor {
        return matchDescriptor
    }

    /** Returns case-insensitive keyword-alternation fast-path data, or `null`.  */
    internal fun keywordAlternation(): KeywordAlternation? {
        return matchDescriptor.keywordAlternation
    }

    /** Returns AST metadata for whole-input rejection.  */
    internal fun rejectPlan(): RejectPlan {
        return multiAnchor.rejectPlan()
    }

    /** Returns AST metadata for start-position acceleration.  */
    internal fun startPlan(): StartPlan {
        return multiAnchor.startPlan()
    }

    /** Returns whole-input rejection prefilter, or `null`.  */
    internal fun rejectPrefilter(): RejectPrefilter? {
        return rejectPrefilter
    }

    /** Applies bounded terminal rejection and records the rejecting diagnostic strategy.  */
    internal fun terminalPrefilterRejects(
        scanner: InputScanner?, searchFrom: Int, diagnostics: DiagnosticAccumulator?
    ): Boolean {
        if (rejectPrefilter == null) {
            return false
        }
        val rejection =
            rejectPrefilter.endAnchoredRejectionStrategy(scanner, searchFrom, enginePathOptions)
        if (rejection == null) {
            return false
        }
        if (diagnostics != null) {
            diagnostics.participate(rejection, StrategyRole.REJECT_PREFILTER)
            diagnostics.boundary(rejection)
        }
        return true
    }

    private fun rejectsWithDiagnostics(
        filter: RejectPrefilter,
        scanner: Utf8InputScanner?,
        searchFrom: Int,
        diagnostics: DiagnosticAccumulator
    ): Boolean {
        return reportRejection(
            filter.rejectionStrategy(null, scanner, searchFrom, enginePathOptions), diagnostics
        )
    }

    private fun rejectsTailWithDiagnostics(
        filter: RejectPrefilter,
        scanner: Utf8InputScanner?,
        searchFrom: Int,
        diagnostics: DiagnosticAccumulator
    ): Boolean {
        return reportRejection(
            filter.tailRejectionStrategy(null, scanner, searchFrom, enginePathOptions), diagnostics
        )
    }

    /** Forward DFA narrowing checks the suffix after failure; avoid an eager duplicate scan.  */
    internal fun findRejectPrefilter(): RejectPrefilter? {
        return if (innerLiteralSearch == null || !forwardDfaSupported) rejectPrefilter else null
    }

    /**
     * UTF-8 `find()` counterpart to [.findRejectPrefilter]: also omits [ ] when UTF-8 start acceleration already covers the search.
     */
    internal fun utf8FindRejectPrefilter(): RejectPrefilter? {
        return utf8FindRejectPrefilter
    }

    internal fun startPrecedesFindReject(): Boolean {
        return startPrecedesFindReject
    }

    /** Returns whether find() skips the reject prefilter in favor of the start accelerator.  */
    internal fun startSubsumesFindReject(): Boolean {
        return startSubsumesFindReject
    }

    internal fun multiAnchor(): MultiAnchorDescriptor {
        return multiAnchor
    }

    /**
     * Returns the reverse-compiled program for backward DFA matching, compiling it on first access.
     * 
     * 
     * Like the other lazily built artifacts, the volatile fast path serves initialized readers and
     * the synchronized slow path builds the program once under concurrent first use.
     */
    internal fun reverseProg(): Prog? {
        var rp = reverseProg
        if (rp == null) {
            synchronized(this) {
                rp = reverseProg
                if (rp == null) {
                    rp = Compiler.compile(ast, true)
                    reverseProg = rp
                }
            }
        }
        return rp
    }

    internal fun flatReverseProg(): Prog? {
        var frp = flatReverseProg
        if (frp == null) {
            synchronized(this) {
                frp = flatReverseProg
                if (frp == null) {
                    val rp = reverseProg()
                    if (rp != null) {
                        frp = rp.flattenedCopy()
                        flatReverseProg = frp
                    }
                }
            }
        }
        return frp
    }

    internal fun flatReverseDfaProg(): Prog? {
        var frp = flatReverseDfaProg
        if (frp == null) {
            // Build once, so that reverseDfaSetup always describes the published flatReverseDfaProg.
            synchronized(this) {
                frp = flatReverseDfaProg
                if (frp == null) {
                    // Prepare once under the lock rather than retaining the tree after construction.
                    val prepared: Compiler.Prepared? = Compiler.prepare(ast)
                    var rp = reverseProg
                    if (rp == null) {
                        rp = Compiler.compile(prepared, true)
                        reverseProg = rp
                    }
                    if (rp != null) {
                        if (rp.numLoopRegs() > 0) {
                            val dfaRp: Prog? = Compiler.compileForDfa(prepared, true)
                            if (dfaRp != null) {
                                frp = dfaRp.flattenedCopy()
                            } else {
                                frp = flatReverseProg()
                            }
                        } else {
                            frp = flatReverseProg()
                        }
                        reverseDfaSetup = Dfa.buildSetup(frp!!)
                        flatReverseDfaProg = frp
                    }
                }
            }
        }
        return frp
    }

    internal fun forwardDfaSetup(): Dfa.Setup {
        var setup = forwardDfaSetup
        if (setup == null) {
            synchronized(this) {
                setup = forwardDfaSetup
                if (setup == null) {
                    val flat = flatProg()
                    setup = Dfa.buildSetup(if (flat != null) flat else prog)
                    forwardDfaSetup = setup
                }
            }
        }
        return setup!!
    }

    internal fun reverseDfaSetup(): Dfa.Setup? {
        flatReverseDfaProg() // ensure flat reverse dfa prog and its setup are computed
        return reverseDfaSetup
    }

    /**
     * Returns the full literal string for patterns that are entirely literal (no metacharacters), or
     * `null` if the pattern is not fully literal. For case-insensitive patterns, returns the
     * lowercase version.
     */
    fun literalMatch(): String? {
        return matchDescriptor.literalMatch
    }

    fun literalFoldCase(): Boolean {
        return matchDescriptor.literalFoldCase
    }

    internal fun literalMatchUtf8(): ByteArray? {
        return literalMatchUtf8
    }

    fun literalMatchFailure(): IntArray? {
        return literalMatchFailure
    }

    fun literalMatchShifts(): IntArray? {
        return literalMatchShifts
    }

    internal fun prefixUtf8(): ByteArray? {
        return prefixUtf8
    }

    val isLiteral: Boolean
        /** Returns `true` if this pattern is a simple literal with no metacharacters.  */
        get() = literalMatch() != null

    fun startsWithGraphemeClusterBoundary(): Boolean {
        return startsWithGraphemeClusterBoundary
    }

    fun hasInternalGraphemeClusterBoundary(): Boolean {
        return hasInternalGraphemeClusterBoundary
    }

    /** Returns the parsed AST.  */
    internal fun ast(): Regexp {
        return ast
    }

    /**
     * Returns an unmodifiable map of named capturing groups to their 1-based group indices.
     * 
     * 
     * If the pattern has no named capturing groups, an empty map is returned.
     * 
     * @return an unmodifiable map from group names to group numbers
     * @since 20
     */
    fun namedGroups(): Map<String, Int> {
        return astAnalysis.namedGroups
    }

    internal fun astAnalysis(): AstAnalysis {
        return astAnalysis
    }

    /**
     * Returns this compiled pattern's opaque, positive diagnostics identity.
     * 
     * 
     * This is the same value reported by [PatternDescriptor.patternId] in diagnostics
     * events. It is assigned even when diagnostics are disabled and remains stable for this instance.
     * Reading it does not compute static analysis.
     * 
     * 
     * Identities are unique among patterns created by the same loaded `Pattern` class.
     * Separately compiling the same expression produces different identities. They are process-local,
     * not persistent identifiers; deserialization recompiles the pattern with a new identity.
     * 
     * @return this pattern's diagnostics identity
     */
    internal fun patternId(): Long {
        return patternId
    }

    /**
     * Returns immutable static diagnostics for this pattern.
     * 
     * 
     * Capabilities describe strategies that can participate for at least one supported operation
     * and input; runtime input length, operation shape, and engine budgets can still affect
     * selection.
     */
    fun analysis(): PatternAnalysis {
        var result = patternAnalysis
        if (result == null) {
            synchronized(this) {
                result = patternAnalysis
                if (result == null) {
                    result = buildPatternAnalysis()
                    patternAnalysis = result
                }
            }
        }
        return result!!
    }

    fun descriptor(): PatternDescriptor {
        var result = patternDescriptor
        if (result == null) {
            synchronized(this) {
                result = patternDescriptor
                if (result == null) {
                    result = PatternDescriptor(patternId, analysis())
                    patternDescriptor = result
                }
            }
        }
        return result!!
    }

    private fun buildPatternAnalysis(): PatternAnalysis {
        val features = EnumSet.noneOf<PatternFeature?>(PatternFeature::class.java)
        val capabilities = EnumSet.noneOf<PatternCapability?>(PatternCapability::class.java)
        val limitations = EnumSet.noneOf<PatternLimitation?>(PatternLimitation::class.java)

        if (literalMatch() != null) {
            features.add(PatternFeature.LITERAL)
            capabilities.add(PatternCapability.LITERAL_MATCH)
        }
        if (literalAlternation != null) {
            capabilities.add(PatternCapability.LITERAL_MATCH)
        }
        if (prog.numCaptures() > 1) {
            features.add(PatternFeature.CAPTURES)
        }
        if (astAnalysis.hasAlt) {
            features.add(PatternFeature.ALTERNATION)
        }
        if (astAnalysis.hasLazy) {
            features.add(PatternFeature.LAZY_QUANTIFIER)
            limitations.add(PatternLimitation.LAZY_SEMANTICS_LIMIT_ONE_PASS)
        }
        if (astAnalysis.canMatchEmpty) {
            features.add(PatternFeature.NULLABLE)
        }
        if (astAnalysis.hasNullableAlt) {
            features.add(PatternFeature.NULLABLE_ALTERNATION)
            limitations.add(PatternLimitation.NULLABLE_ALTERNATION_LIMITS_ONE_PASS)
        }
        if (prog.anchorStart()) {
            features.add(PatternFeature.ANCHOR)
            features.add(PatternFeature.START_ANCHOR)
        }
        if (prog.anchorEnd()) {
            features.add(PatternFeature.ANCHOR)
            features.add(PatternFeature.END_ANCHOR)
        }
        if (prog.hasWordBoundary()) {
            features.add(PatternFeature.WORD_BOUNDARY)
        }
        if (prog.hasGraphemeSemantics()) {
            features.add(PatternFeature.GRAPHEME)
            limitations.add(PatternLimitation.GRAPHEME_REQUIRES_EXACT_ENGINE)
        }
        if ((flags and CASE_INSENSITIVE) != 0) {
            features.add(PatternFeature.CASE_INSENSITIVE)
        }
        if ((flags and UNICODE_CHARACTER_CLASS) != 0) {
            features.add(PatternFeature.UNICODE_CHARACTER_CLASS)
        }
        addAstAnalysisFeatures(features)

        if (matchDescriptor.charClassMatch != null || matchDescriptor.singleCharClass != null) {
            capabilities.add(PatternCapability.CHARACTER_CLASS_MATCH)
        }
        if (matchDescriptor.keywordAlternation != null) {
            capabilities.add(PatternCapability.KEYWORD_MATCH)
        }
        if (canOnePassPrimary()) {
            capabilities.add(PatternCapability.ONE_PASS_PRIMARY)
        }
        if (canOnePassSubmatch()) {
            capabilities.add(PatternCapability.ONE_PASS_CAPTURE_EXTRACTION)
        }
        if (enginePathOptions.dfa && !prog.hasGraphemeSemantics()) {
            capabilities.add(PatternCapability.DFA_BOUNDARY_SEARCH)
            if (prog.numLoopRegs() > 0) {
                capabilities.add(PatternCapability.DFA_REJECT_PREFILTER)
            }
        }
        val maxBitStateText: Int = BitState.maxTextSize(prog)
        if (maxBitStateText >= 0) {
            capabilities.add(PatternCapability.BIT_STATE)
        } else {
            limitations.add(PatternLimitation.PROGRAM_TOO_LARGE_FOR_BIT_STATE)
        }
        capabilities.add(PatternCapability.NFA)

        if (prog.numLoopRegs() > 0) {
            features.add(PatternFeature.NESTED_NULLABLE_QUANTIFIER)
            features.add(PatternFeature.PROGRESS_CHECK)
            limitations.add(PatternLimitation.NULLABLE_LOOP_REQUIRES_EXACT_ENGINE)
        }
        if (features.contains(PatternFeature.CAPTURES)
            && (astAnalysis.hasAlt || astAnalysis.hasLazy || prog.numLoopRegs() > 0)
        ) {
            limitations.add(PatternLimitation.CAPTURE_PRIORITY_REQUIRES_EXACT_ENGINE)
        }

        return PatternAnalysis(
            features, capabilities, limitations, prog.size, prog.numCaptures() - 1
        )
    }

    private data class AstAnalysisNode(val regexp: Regexp, val insideQuantifier: Boolean)

    private fun addAstAnalysisFeatures(features: EnumSet<PatternFeature>) {
        val stack: Deque<AstAnalysisNode> = ArrayDeque<AstAnalysisNode>()
        stack.push(AstAnalysisNode(ast, false))
        while (!stack.isEmpty()) {
            val current = stack.pop()
            val node = current.regexp
            val quantifier =
                node.op == RegexpOp.STAR || node.op == RegexpOp.PLUS || node.op == RegexpOp.QUEST || node.op == RegexpOp.REPEAT
            if (node.op == RegexpOp.REPEAT) {
                features.add(PatternFeature.BOUNDED_REPEAT)
            }
            when (node.op) {
                RegexpOp.LITERAL, RegexpOp.LITERAL_STRING -> features.add(PatternFeature.LITERAL)
                RegexpOp.BEGIN_LINE, RegexpOp.BEGIN_TEXT -> {
                    features.add(PatternFeature.ANCHOR)
                    features.add(PatternFeature.START_ANCHOR)
                }

                RegexpOp.END_LINE, RegexpOp.END_TEXT -> {
                    features.add(PatternFeature.ANCHOR)
                    features.add(PatternFeature.END_ANCHOR)
                }

                RegexpOp.WORD_BOUNDARY, RegexpOp.NO_WORD_BOUNDARY -> features.add(PatternFeature.WORD_BOUNDARY)
                RegexpOp.GRAPHEME_CLUSTER, RegexpOp.GRAPHEME_CLUSTER_BOUNDARY -> features.add(PatternFeature.GRAPHEME)
                else -> {}
            }
            if (node.op == RegexpOp.CAPTURE && node.cap > 0 && current.insideQuantifier) {
                features.add(PatternFeature.CAPTURES_IN_QUANTIFIER)
            }
            if (node.subs != null) {
                for (sub in node.subs) {
                    stack.push(AstAnalysisNode(sub!!, current.insideQuantifier || quantifier))
                }
            }
        }
    }

    /**
     * Returns the number of capturing groups in this pattern, not counting the implicit group 0 for
     * the full match.
     */
    fun numGroups(): Int {
        return prog.numCaptures() - 1
    }

    init {
        this.patternId = nextPatternId()
        this.pattern = pattern
        this.flags = flags
        this.prog = prog
        this.ast = ast
        this.astAnalysis = astAnalysis
        this.startsWithGraphemeClusterBoundary = startsWithGraphemeClusterBoundary
        this.hasInternalGraphemeClusterBoundary = hasInternalGraphemeClusterBoundary
        this.enginePathOptions = enginePathOptions
        if (enginePathOptions.dfa && prog.numLoopRegs() > 0) {
            this.flatProg = prog.flattenedCopy()
            val dfaProg: Prog? = Compiler.compileForDfa(prepared, false)
            this.flatDfaProg = if (dfaProg != null) dfaProg.flattenedCopy() else this.flatProg
        }

        this.multiAnchor = if (multiAnchor != null) multiAnchor else MultiAnchorDescriptor.NONE
        this.prefix = this.multiAnchor.prefix()
        this.prefixFoldCase = this.multiAnchor.prefixFoldCase()
        this.prefixUtf8 =
            if (this.prefix == null || this.prefix.isEmpty())
                null
            else
                this.prefix.toByteArray(StandardCharsets.UTF_8)
        this.anchoredPrefix = this.multiAnchor.anchoredPrefix()
        this.anchoredPrefixUtf8 =
            if (this.anchoredPrefix == null || this.anchoredPrefix.isEmpty())
                null
            else
                this.anchoredPrefix.toByteArray(StandardCharsets.UTF_8)
        this.matchDescriptor = if (matchDescriptor != null) matchDescriptor else MatchDescriptor.NONE
        // Fold the option into prepared literal data so the search methods need no extra guard.
        // An extra guard there can perturb C2 compilation of their inlined byte-scanning loops.
        val literalMatch =
            if (enginePathOptions.literalFastPaths) this.matchDescriptor.literalMatch else null
        this.literalMatchUtf8 =
            if (literalMatch == null) null else literalMatch.toByteArray(StandardCharsets.UTF_8)
        this.literalMatchFailure = if (literalMatchUtf8 == null) null else literalFailure(literalMatchUtf8)
        this.literalMatchShifts = if (literalMatchUtf8 == null) null else literalShifts(literalMatchUtf8)
        this.literalMatchRareByteOffset =
            if (literalMatchUtf8 == null || this.matchDescriptor.literalFoldCase)
                -1
            else
                RarityOracle.rarestUtf8LiteralByteOffset(literalMatchUtf8)
        this.charClassPrefix = this.multiAnchor.charClassPrefix()
        this.anchoredCharClassPrefix = this.multiAnchor.anchoredCharClassPrefix()
        this.utf8StartAccelerator =
            Utf8StartAccelerator.create(this.multiAnchor, prog.hasWordBoundary())
        val startPlan = this.multiAnchor.startPlan()
        val wideLeadingExpansion =
            startPlan is StartPlan.LeadingExpansion
                    && MultiAnchorCompiler.isWideLeadingClass(startPlan.leadingClass!!)
        // A String program containing \X may start at the low half of a surrogate pair. A scalar
        // backward walk can see the excluded pair and miss a valid start at its low surrogate.
        this.stringStartAccelerator =
            if (wideLeadingExpansion && prog.hasGraphemeClusterInstruction())
                null
            else
                StringStartAccelerator.create(this.multiAnchor, prog.hasWordBoundary())
        // The wide expansion can walk backward over an entire line. Keep it out of the DFA loop,
        // which could otherwise repeat that walk at each position before the same inner hit.
        this.dfaStartAcceleration = !wideLeadingExpansion
        this.rejectPrefilter = RejectPrefilter.create(this.multiAnchor)
        // When find() runs a wide-class leading expansion, its inner literal search fails exactly when
        // the reject prefilter would reject, so the prefilter's scan would only repeat it.
        this.startSubsumesFindReject =
            enginePathOptions.startAcceleration
                    && !prog.anchorStart() && stringStartAccelerator != null && utf8StartAccelerator != null && this.multiAnchor.startSubsumesFindReject()
        this.innerLiteralSearch =
            if (enginePathOptions.startAcceleration
                && enginePathOptions.literalFastPaths
                && this.matchDescriptor.literalMatch == null && !prog.anchorStart() && startPlan
                        is StartPlan.Literal
                && !startPlan.foldCase
            )
                InnerLiteralSearch.compile(ast, rejectPrefilter)
            else
                null
        this.startPrecedesFindReject =
            enginePathOptions.startAcceleration
                    && !prog.anchorStart() && findRejectPrefilter() != null && stringStartAccelerator != null && utf8StartAccelerator != null && this.multiAnchor.startPrecedesFindReject()
        // The literal-alternation runner skips canReject on its initial String find().
        // The filter's type still selects its window policy; fallback operations retain the filter.
        this.literalAlternation =
            if (numGroups() == 0 && this.matchDescriptor.literalMatch == null && this.multiAnchor.startPlan()
                        is StartPlan.MultiLiteral
                && (rejectPrefilter == null
                        || rejectPrefilter is RejectPrefilter.DisjointLiterals)
            )
                LiteralAlternation.compile(
                    ast,
                    rejectPrefilter is RejectPrefilter.DisjointLiterals,
                    if (utf8StartAccelerator is Utf8StartAccelerator.CharClass)
                        utf8StartAccelerator
                    else
                        null
                )
            else
                null
        // Eagerly prepare the artifacts that find() and group() may need, to avoid latency spikes on
        // first use. Artifacts that only specific operations can use are built on first use instead.
        if (shouldEagerlyBuildOnePass()) {
            onePassAnalysis()
        }
        this.defaultPreparedMatchRunner = createPreparedRunner(false)
        this.regionPreparedMatchRunner = createPreparedRunner(true)
        // Literal runners answer String find() without the forward DFA, so its program and setup
        // are built on first use by another operation.
        if (!(defaultPreparedMatchRunner is LiteralPreparedRunner
                    || defaultPreparedMatchRunner is LiteralAlternationPreparedRunner)
        ) {
            forwardDfaSetup()
        }
        // Flattening removes unreachable instructions, so eager paths must inspect their actual
        // prepared program. Only literal runners defer this work; their source has no grapheme or
        // loop-register instructions, and therefore already establishes support.
        val forwardProgram = (if (flatDfaProg != null) flatDfaProg else if (flatProg != null) flatProg else prog)!!
        this.forwardDfaSupported = enginePathOptions.dfa && supportsForwardDfa(forwardProgram)
        val baseFindReject =
            if (this.innerLiteralSearch == null || !this.forwardDfaSupported) this.rejectPrefilter else null
        this.utf8FindRejectPrefilter =
            if (baseFindReject != null && enginePathOptions.startAcceleration
                && this.utf8StartAccelerator != null && !prog.anchorStart()
            )
                RejectPrefilter.withoutDisjointLiterals(baseFindReject)
            else
                baseFindReject

        val listener: SafeReMatchDiagnostics = diagnostics()
        if (SafeReMatchDiagnostics.isEnabled(listener)) {
            listener.onPatternCompiled(PatternCompiledEvent(descriptor()))
        }
    }

    private data class GraphemeBoundaryContext(
        val canMatchEmpty: Boolean,
        val canConsume: Boolean,
        val startsWithExplicitBoundary: Boolean,
        val endsWithExplicitBoundary: Boolean,
        val hasInternalExplicitBoundary: Boolean
    ) {


        companion object {
            fun noMatch(): GraphemeBoundaryContext {
                return GraphemeBoundaryContext(false, false, false, false, false)
            }

            fun empty(): GraphemeBoundaryContext {
                return GraphemeBoundaryContext(true, false, false, false, false)
            }

            fun consuming(): GraphemeBoundaryContext {
                return GraphemeBoundaryContext(false, true, false, false, false)
            }

            fun explicitBoundary(): GraphemeBoundaryContext {
                return GraphemeBoundaryContext(true, false, true, true, false)
            }
        }
    }

    private class GraphemeBoundaryContextWalker : Walker<GraphemeBoundaryContext?>() {
        override fun shortVisit(re: Regexp, parentArg: GraphemeBoundaryContext?): GraphemeBoundaryContext {
            return GraphemeBoundaryContext.noMatch()
        }

        override fun copy(arg: GraphemeBoundaryContext?): GraphemeBoundaryContext? {
            return arg
        }

        override fun postVisit(
            re: Regexp,
            parentArg: GraphemeBoundaryContext?,
            preArg: GraphemeBoundaryContext?,
            childArgs: MutableList<GraphemeBoundaryContext?>
        ): GraphemeBoundaryContext? {
            return when (re!!.op) {
                RegexpOp.NO_MATCH -> GraphemeBoundaryContext.noMatch()
                RegexpOp.EMPTY_MATCH, RegexpOp.BEGIN_LINE, RegexpOp.END_LINE, RegexpOp.BEGIN_TEXT, RegexpOp.END_TEXT, RegexpOp.WORD_BOUNDARY, RegexpOp.NO_WORD_BOUNDARY, RegexpOp.HAVE_MATCH -> GraphemeBoundaryContext.empty()
                RegexpOp.LITERAL, RegexpOp.LITERAL_STRING, RegexpOp.ANY_CHAR, RegexpOp.ANY_BYTE, RegexpOp.CHAR_CLASS, RegexpOp.GRAPHEME_CLUSTER -> GraphemeBoundaryContext.consuming()
                RegexpOp.GRAPHEME_CLUSTER_BOUNDARY -> GraphemeBoundaryContext.explicitBoundary()
                RegexpOp.CONCAT -> concatBoundaryContext(childArgs)
                RegexpOp.ALTERNATE -> alternateBoundaryContext(childArgs)
                RegexpOp.STAR, RegexpOp.QUEST -> optionalBoundaryContext(childArgs)
                RegexpOp.PLUS, RegexpOp.NON_CAPTURE, RegexpOp.CAPTURE -> childBoundaryContext(childArgs)
                RegexpOp.REPEAT -> repeatBoundaryContext(re, childArgs)
            }
        }

        companion object {
            private fun concatBoundaryContext(
                childArgs: MutableList<GraphemeBoundaryContext?>
            ): GraphemeBoundaryContext {
                var canMatchEmpty = true
                var canConsume = false
                var startsWithExplicitBoundary = false
                var canStillStartAtChild = true
                var hasInternalExplicitBoundary = false
                var hasConsumingPrefix = false
                val consumingSuffix = BooleanArray(childArgs.size)
                var hasConsumingSuffix = false
                for (i in childArgs.indices.reversed()) {
                    consumingSuffix[i] = hasConsumingSuffix
                    hasConsumingSuffix = hasConsumingSuffix || childArgs[i]!!.canConsume
                }
                for (i in childArgs.indices) {
                    val child = childArgs[i]!!
                    canMatchEmpty = canMatchEmpty && child.canMatchEmpty
                    canConsume = canConsume || child.canConsume
                    if (canStillStartAtChild && child.startsWithExplicitBoundary) {
                        startsWithExplicitBoundary = true
                    }
                    canStillStartAtChild = canStillStartAtChild && child.canMatchEmpty
                    hasInternalExplicitBoundary = hasInternalExplicitBoundary || child.hasInternalExplicitBoundary
                    if ((child.startsWithExplicitBoundary || child.endsWithExplicitBoundary)
                        && hasConsumingPrefix
                        && consumingSuffix[i]
                    ) {
                        hasInternalExplicitBoundary = true
                    }
                    hasConsumingPrefix = hasConsumingPrefix || child.canConsume
                }

                var endsWithExplicitBoundary = false
                var canStillEndAtChild = true
                for (i in childArgs.indices.reversed()) {
                    val child = childArgs[i]!!
                    if (canStillEndAtChild && child.endsWithExplicitBoundary) {
                        endsWithExplicitBoundary = true
                    }
                    canStillEndAtChild = canStillEndAtChild && child.canMatchEmpty
                }
                return GraphemeBoundaryContext(
                    canMatchEmpty,
                    canConsume,
                    startsWithExplicitBoundary,
                    endsWithExplicitBoundary,
                    hasInternalExplicitBoundary
                )
            }

            private fun alternateBoundaryContext(
                childArgs: MutableList<GraphemeBoundaryContext?>
            ): GraphemeBoundaryContext {
                var canMatchEmpty = childArgs.isEmpty()
                var canConsume = false
                var startsWithExplicitBoundary = false
                var endsWithExplicitBoundary = false
                var hasInternalExplicitBoundary = false
                for (nullableChild in childArgs) {
                    val child = nullableChild!!
                    canMatchEmpty = canMatchEmpty || child.canMatchEmpty
                    canConsume = canConsume || child.canConsume
                    startsWithExplicitBoundary = startsWithExplicitBoundary || child.startsWithExplicitBoundary
                    endsWithExplicitBoundary = endsWithExplicitBoundary || child.endsWithExplicitBoundary
                    hasInternalExplicitBoundary = hasInternalExplicitBoundary || child.hasInternalExplicitBoundary
                }
                return GraphemeBoundaryContext(
                    canMatchEmpty,
                    canConsume,
                    startsWithExplicitBoundary,
                    endsWithExplicitBoundary,
                    hasInternalExplicitBoundary
                )
            }

            private fun optionalBoundaryContext(
                childArgs: MutableList<GraphemeBoundaryContext?>
            ): GraphemeBoundaryContext {
                val child = childBoundaryContext(childArgs)
                return GraphemeBoundaryContext(
                    true,
                    child.canConsume,
                    child.startsWithExplicitBoundary,
                    child.endsWithExplicitBoundary,
                    child.hasInternalExplicitBoundary
                )
            }

            private fun repeatBoundaryContext(
                re: Regexp, childArgs: MutableList<GraphemeBoundaryContext?>
            ): GraphemeBoundaryContext {
                val child = childBoundaryContext(childArgs)
                val canMatchEmpty = re.min == 0 || child.canMatchEmpty
                val canConsume = re.max != 0 && child.canConsume
                return GraphemeBoundaryContext(
                    canMatchEmpty,
                    canConsume,
                    child.startsWithExplicitBoundary,
                    child.endsWithExplicitBoundary,
                    child.hasInternalExplicitBoundary
                )
            }

            private fun childBoundaryContext(
                childArgs: MutableList<GraphemeBoundaryContext?>
            ): GraphemeBoundaryContext {
                return if (childArgs.isEmpty()) GraphemeBoundaryContext.noMatch() else childArgs.first()!!
            }
        }
    }

    /**
     * Conservative start-position accelerator.
     * 
     * 
     * Every match must start at a multiline `^` position, optionally with the first consumed
     * ASCII character in `asciiStart`. The accelerator only advances the initial search
     * position before handing off to the normal linear engine pipeline.
     */
    internal class StartAcceleration(requireLineStart: Boolean, allowLineStart: Boolean, asciiStart: AsciiBitmap?) {
        val requireLineStart: Boolean
        val allowLineStart: Boolean
        val asciiStart: AsciiBitmap?

        init {
            this.requireLineStart = requireLineStart
            this.allowLineStart = allowLineStart
            this.asciiStart = asciiStart
        }
    }

    internal class FixedOffsetLiteral(literal: String, minOffset: Int, maxOffset: Int, discreteOffsets: IntArray?) {
        private val literal: String?
        private val minOffset: Int
        private val maxOffset: Int
        private val discreteOffsets: IntArray?
        private val utf8: ByteArray
        private val failure: IntArray
        private val shifts: IntArray?

        constructor(literal: String, offset: Int) : this(literal, offset, offset, intArrayOf(offset))

        init {
            this.literal = literal
            this.minOffset = minOffset
            this.maxOffset = maxOffset
            this.discreteOffsets = discreteOffsets
            this.utf8 = literal.toByteArray(StandardCharsets.UTF_8)
            this.failure = literalFailure(utf8)
            this.shifts = literalShifts(utf8)
        }

        fun literal(): String? {
            return literal
        }

        fun offset(): Int {
            return minOffset
        }

        fun minOffset(): Int {
            return minOffset
        }

        fun maxOffset(): Int {
            return maxOffset
        }

        fun discreteOffsets(): IntArray? {
            return discreteOffsets
        }

        val isExactOffset: Boolean
            get() = minOffset == maxOffset

        fun utf8(): ByteArray {
            return utf8
        }

        fun failure(): IntArray {
            return failure
        }

        fun shifts(): IntArray? {
            return shifts
        }
    }

    /** Fast-path data for `(?i)\b(keyword|...)\b` and its greedy whole-input form.  */
    internal class KeywordAlternation(
        keywords: Array<String>,
        firstAscii: AsciiBitmap,
        captureGroup: Int,
        unicodeWordBoundary: Boolean,
        greedyWholeInput: Boolean
    ) {
        val keywords: Array<String>
        val firstAscii: AsciiBitmap?
        val firstAsciiTable: BooleanArray
        val captureGroup: Int
        val unicodeWordBoundary: Boolean
        val greedyWholeInput: Boolean

        init {
            this.keywords = keywords
            this.firstAscii = firstAscii
            this.firstAsciiTable = firstAscii.toBooleanArray()
            this.captureGroup = captureGroup
            this.unicodeWordBoundary = unicodeWordBoundary
            this.greedyWholeInput = greedyWholeInput
        }

        fun find(scanner: InputScanner, startPos: Int): Long {
            val matchStart = Math.max(0, startPos)
            if (greedyWholeInput) {
                for (position in scanner.length - 1 downTo matchStart) {
                    val match = matchAt(scanner, position)
                    if (match >= 0) {
                        return match
                    }
                }
                return -1
            }
            var position = matchStart
            while (position < scanner.length) {
                val match = matchAt(scanner, position)
                if (match >= 0) {
                    return match
                }
                val ascii = scanner.asciiAt(position)
                position =
                    if (ascii >= 0) position + 1 else InputScanner.position(scanner.decodeForward(position))
            }
            return -1
        }

        fun matchAt(scanner: InputScanner, position: Int): Long {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            val first = scanner.asciiAt(position)
            if (first < 0 || !firstAsciiTable[Ascii.toLowerCase(first)] || !isWordBoundaryAt(scanner, position)) {
                return -1
            }
            for (keyword in keywords) {
                val end: Int = position + keyword.length
                if (end <= scanner.length && matchesAsciiIgnoreCase(scanner, position, keyword)
                    && isWordBoundaryAt(scanner, end)
                ) {
                    return (position.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)
                }
            }
            return -1
        }

        private fun isWordBoundaryAt(scanner: InputScanner, position: Int): Boolean {
            val previousWord =
                position > 0
                        && isBoundaryWordChar(scanner.codePointBefore(position), unicodeWordBoundary)
            val nextWord =
                position < scanner.length
                        && isBoundaryWordChar(scanner.codePointAt(position), unicodeWordBoundary)
            return previousWord != nextWord
        }

        companion object {
            private fun matchesAsciiIgnoreCase(
                scanner: InputScanner, position: Int, keyword: String
            ): Boolean {
                for (index in 0..<keyword.length) {
                    val input = scanner.asciiAt(position + index)
                    if (input < 0 || Ascii.toLowerCase(input) != keyword[index].code) {
                        return false
                    }
                }
                return true
            }

            private fun isBoundaryWordChar(codePoint: Int, unicodeWordBoundary: Boolean): Boolean {
                return if (unicodeWordBoundary) Nfa.isUnicodeWordChar(codePoint) else Nfa.isWordChar(
                    codePoint
                )
            }

            fun matchStart(match: Long): Int {
                return (match ushr 32).toInt()
            }

            fun matchEnd(match: Long): Int {
                return match.toInt()
            }
        }
    }

    /** Holds precomputed data for the character-class-match fast path.  */ // TODO(#98): Replace int[] with Guava ImmutableIntArray to get proper value semantics.
    internal data class CharClassMatchInfo(
        val ranges: IntArray?,
        val bitmap0: Long,
        val bitmap1: Long,
        val allowEmpty: Boolean
    )

    internal data class SuffixInfo(
        val suffix: String?,
        val wasDollar: Boolean,
        val unixLines: Boolean,
        val foldCase: Boolean
    ) {
        constructor(suffix: String?, wasDollar: Boolean) : this(suffix, wasDollar, false, false)

        constructor(suffix: String?, wasDollar: Boolean, unixLines: Boolean) : this(suffix, wasDollar, unixLines, false)


    }

    internal data class EndAnchoredCharClassInfo(
        val bitmap: AsciiBitmap?,
        val wasDollar: Boolean,
        val unixLines: Boolean
    ) {
        constructor(bitmap: AsciiBitmap?, wasDollar: Boolean) : this(bitmap, wasDollar, false)


    }

    private data class LiteralResult(val literal: String?, val foldCase: Boolean) {


        companion object {
            val NONE: LiteralResult = LiteralResult(null, false)
        }
    }

    /** Deserialization: recompile the pattern from the stored string and flags.  */
    private fun readResolve(): Any {
        return compile(pattern(), flags())
    }

    companion object {
        private const val serialVersionUID = 1L
        private val END_ANCHORED_SEARCH_FALLBACK = -2
        val END_ANCHORED_SEARCH_DEFERRED: Int = -3
        private const val MIN_END_ANCHORED_PROBE_UNITS = 64

        // Short inputs retain the forward path to avoid reverse-search setup overhead.
        private const val MIN_REVERSE_FIRST_LEN = 1024

        // A competitive literal start accelerator runs before the find reject prefilter only on inputs
        // shorter than this, where the separate prefilter call dominates. Longer inputs keep the
        // single-pass reject prefilter first.
        const val MAX_LITERAL_START_FIRST_LENGTH: Int = 512

        // After a short-input start scan, the reject prefilter re-runs from the start candidate only when
        // at least this many units remain. On a shorter tail the DFA's check costs less than the call.
        const val MIN_REJECT_AFTER_START_REMAINING: Int = 64
        private val NEXT_PATTERN_ID = AtomicLong(1)
        private val DIAGNOSTICS_TYPE: MethodType = MethodType.methodType(SafeReMatchDiagnostics::class.java)
        private val DIAGNOSTICS_SITE: MutableCallSite = MutableCallSite(DIAGNOSTICS_TYPE)
        private val DIAGNOSTICS_INVOKER: MethodHandle = DIAGNOSTICS_SITE.dynamicInvoker()

        init {
            setDiagnosticsTarget(SafeReMatchDiagnostics.NONE)
        }

        /**
         * Enables Unix lines mode. In this mode, only `'\n'` is recognized as a line terminator.
         */
        val UNIX_LINES: Int = java.util.regex.Pattern.UNIX_LINES // 1

        /**
         * Enables case-insensitive matching. By default, case-insensitive matching assumes only US-ASCII
         * characters. Unicode-aware case folding can be enabled with [.UNICODE_CASE].
         */
        val CASE_INSENSITIVE: Int = java.util.regex.Pattern.CASE_INSENSITIVE // 2

        /**
         * Permits whitespace and comments in the pattern. Whitespace is ignored, and comments starting
         * with `#` run to end-of-line.
         */
        val COMMENTS: Int = java.util.regex.Pattern.COMMENTS // 4

        /**
         * Enables multiline mode. In multiline mode, `^` and `$` match at the start and end
         * of each line, not just the start and end of the entire input.
         */
        val MULTILINE: Int = java.util.regex.Pattern.MULTILINE // 8

        /**
         * Enables literal parsing of the pattern. Metacharacters and escape sequences have no special
         * meaning.
         */
        val LITERAL: Int = java.util.regex.Pattern.LITERAL // 16

        /**
         * Enables dotall mode. In dotall mode, `.` matches any character including line
         * terminators.
         */
        val DOTALL: Int = java.util.regex.Pattern.DOTALL // 32

        /**
         * Enables Unicode-aware case folding. When used with [.CASE_INSENSITIVE], matching is done
         * in a manner consistent with the Unicode Standard.
         */
        val UNICODE_CASE: Int = java.util.regex.Pattern.UNICODE_CASE // 64

        /**
         * Enables Unicode-aware character classes. When enabled, predefined character classes such as
         * `\w`, `\d`, and `\s` match Unicode characters instead of only ASCII.
         */
        val UNICODE_CHARACTER_CLASS: Int = java.util.regex.Pattern.UNICODE_CHARACTER_CLASS // 256

        private fun canUseLiteralPreparedRunner(
            literal: String?, numCaptures: Int, enginePathOptions: EnginePathOptions
        ): Boolean {
            return enginePathOptions.literalFastPaths && literal != null && numCaptures == 1
        }

        private fun extractMatchDescriptor(
            metadataAst: Regexp, astAnalysis: AstAnalysis, flags: Int
        ): MatchDescriptor {
            val literalMatchResult: LiteralResult = extractLiteralMatch(metadataAst)
            val literalMatch = literalMatchResult.literal
            val literalFoldCase = literalMatchResult.foldCase
            val hasUserCaptures = astAnalysis.hasUserCaptures
            val singleCharClass: CharClassScanInfo? = extractSingleCharClass(metadataAst, hasUserCaptures)
            val keywordAlternation: KeywordAlternation? = extractKeywordAlternation(metadataAst, flags)
            val ccMatch: CharClassMatchInfo? = extractCharClassMatch(metadataAst, hasUserCaptures)
            val minMatchLength = astAnalysis.minMatchLength
            if (literalMatch == null && singleCharClass == null && keywordAlternation == null && ccMatch == null && minMatchLength <= 0) {
                return MatchDescriptor.NONE
            }
            return MatchDescriptor(
                literalMatch,
                literalFoldCase,
                singleCharClass,
                keywordAlternation,
                ccMatch,
                minMatchLength
            )
        }

        private fun nextPatternId(): Long {
            val id: Long = NEXT_PATTERN_ID.getAndIncrement()
            check(id > 0) { "pattern diagnostics identifier space exhausted" }
            return id
        }

        /**
         * Installs the process-wide synchronous match diagnostics listener.
         * 
         * 
         * The listener is invoked on compiling and matching threads and remains installed until
         * replaced. Use [SafeReMatchDiagnostics.NONE] to disable diagnostics.
         * 
         * @param listener the listener to install
         */
        fun setDiagnostics(listener: SafeReMatchDiagnostics?) {
            setDiagnosticsTarget(Objects.requireNonNull<SafeReMatchDiagnostics?>(listener, "listener"))
            MutableCallSite.syncAll(arrayOf<MutableCallSite?>(DIAGNOSTICS_SITE))
        }

        /**
         * Returns the currently installed process-wide match diagnostics listener.
         * 
         * @return the installed listener
         */
        internal fun diagnostics(): SafeReMatchDiagnostics {
            try {
                return DIAGNOSTICS_INVOKER.invokeExact() as SafeReMatchDiagnostics
            } catch (impossible: Throwable) {
                throw AssertionError(impossible)
            }
        }

        private fun setDiagnosticsTarget(listener: SafeReMatchDiagnostics?) {
            DIAGNOSTICS_SITE.setTarget(MethodHandles.constant(SafeReMatchDiagnostics::class.java, listener))
        }

        /**
         * Compiles the given regular expression into a pattern with the given flags.
         * 
         * @param regex the expression to be compiled
         * @param flags match flags, a bit mask of [.CASE_INSENSITIVE], [.MULTILINE], [     ][.DOTALL], [.UNICODE_CHARACTER_CLASS], [.LITERAL], [.COMMENTS], [     ][.UNIX_LINES], and [.UNICODE_CASE]
         * @return the compiled pattern
         * @throws PatternSyntaxException if the expression's syntax is invalid
         * @throws IllegalArgumentException if the flags contain unsupported bits (e.g., `CANON_EQ`)
         */
        /**
         * Compiles the given regular expression into a pattern with default flags.
         * 
         * @param regex the expression to be compiled
         * @return the compiled pattern
         * @throws PatternSyntaxException if the expression's syntax is invalid
         */
        @JvmOverloads
        internal fun compile(regex: String?, flags: Int = 0): Pattern {
            return compile(regex, flags, EnginePathOptions.allEnabled())
        }

        internal fun compile(regex: String?, flags: Int, enginePathOptions: EnginePathOptions?): Pattern {
            validateFlags(flags)
            Objects.requireNonNull<EnginePathOptions?>(enginePathOptions, "enginePathOptions")
            val effectiveFlags: Int = effectiveFlags(flags)
            val parseFlags: Int = toParseFlags(effectiveFlags)
            val re: Regexp = Parser.parse(regex, parseFlags)!!
            val prepared: Compiler.Prepared? = Compiler.prepare(re)
            val compiled: Prog = Compiler.compile(prepared, false)!!
            if (compiled == null) {
                throw PatternSyntaxException("compiled program too large", regex, -1)
            }
            compiled.setUnixLines((effectiveFlags and UNIX_LINES) != 0)
            // Language-shape accelerators should see through source-only grouping. Correctness guards
            // below still inspect the source AST because source quantifiers carry matching semantics that
            // simplification deliberately lowers away.
            val metadataAst = prepared!!.simplifiedSource()
            val astAnalysis: AstAnalysis = AstAnalysis.analyze(re)
            val multiAnchor = MultiAnchorCompiler.compile(metadataAst, effectiveFlags)
            val matchDescriptor: MatchDescriptor = extractMatchDescriptor(metadataAst!!, astAnalysis, flags)
            val startsWithGcb =
                compiled.hasGraphemeSemantics() && startsWithGraphemeClusterBoundary(metadataAst)
            val hasInternalGcb =
                compiled.hasGraphemeSemantics() && hasInternalExplicitGraphemeBoundary(re)
            // The constructor eagerly prepares the applicable engine analysis and setup.
            return Pattern(
                regex,
                effectiveFlags,
                compiled,
                prepared,
                re,
                astAnalysis,
                matchDescriptor,
                startsWithGcb,
                hasInternalGcb,
                multiAnchor,
                enginePathOptions!!
            )
        }

        /**
         * Compiles the given regular expression and attempts to match the given input against it. This is
         * equivalent to `Pattern.compile(regex).matcher(input).matches()`.
         * 
         * @param regex the expression to be compiled
         * @param input the character sequence to be matched
         * @return `true` if the entire input matches the pattern
         * @throws PatternSyntaxException if the expression's syntax is invalid
         */
        @JvmStatic

        fun matches(regex: String?, input: CharSequence?): Boolean {
            return compile(regex).matcher(input).matches()
        }

        /**
         * Materializes a [CharSequence] by reading through `charAt()`, so custom
         * implementations that do not override `toString()` are handled correctly.
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

        /**
         * Returns a literal pattern string for the specified string. Metacharacters and escape sequences
         * in the returned string will have no special meaning.
         * 
         * @param s the string to be literalized
         * @return a literal pattern string
         */
        fun quote(s: String): String {
            // Use \Q...\E quoting. If the string contains \E, split around it.
            if (!s.contains("\\E")) {
                return "\\Q" + s + "\\E"
            }
            val sb = StringBuilder()
            sb.append("\\Q")
            var last = 0
            var idx: Int
            while ((s.indexOf("\\E", last).also { idx = it }) != -1) {
                sb.append(s, last, idx)
                sb.append("\\E\\\\E\\Q")
                last = idx + 2
            }
            sb.append(s, last, s.length)
            sb.append("\\E")
            return sb.toString()
        }

        fun literalFailure(literal: ByteArray): IntArray {
            val failure = IntArray(literal.size)
            var matched = 0
            for (index in 1..<literal.size) {
                while (matched > 0 && literal[index] != literal[matched]) {
                    matched = failure[matched - 1]
                }
                if (literal[index] == literal[matched]) {
                    matched++
                }
                failure[index] = matched
            }
            return failure
        }

        fun literalShifts(literal: ByteArray): IntArray? {
            if (literal.size < 2) {
                return null
            }
            val shifts = IntArray(256)
            Arrays.fill(shifts, literal.size)
            for (index in 0..<literal.size - 1) {
                shifts[literal[index].toInt() and 0xFF] = literal.size - index - 1
            }
            return shifts
        }

        internal fun borrowBitState(cache: ThreadCache): BitState? {
            val bs = cache.bitState
            cache.bitState = null // take ownership
            return bs
        }

        internal fun returnBitState(cache: ThreadCache, bs: BitState) {
            bs.releaseInput()
            cache.bitState = bs
        }

        internal fun borrowNfa(cache: ThreadCache): Nfa? {
            val nfa = cache.nfa
            cache.nfa = null
            return nfa
        }

        internal fun returnNfa(cache: ThreadCache, nfa: Nfa) {
            nfa.releaseInputContext()
            cache.nfa = nfa
        }

        /** Maximum number of DFA states before the DFA bails out.  */
        const val MAX_DFA_STATES: Int = 10000

        private fun supportsForwardDfa(p: Prog?): Boolean {
            return p != null && !p.hasGraphemeSemantics() && p.numLoopRegs() == 0
        }

        private fun reportRejection(
            rejection: MatchStrategy?, diagnostics: DiagnosticAccumulator
        ): Boolean {
            if (rejection == null) {
                return false
            }
            diagnostics.participate(rejection, StrategyRole.REJECT_PREFILTER)
            diagnostics.boundary(rejection)
            return true
        }

        // ---------------------------------------------------------------------------
        // Flag mapping
        // ---------------------------------------------------------------------------
        /** The set of all flag bits we support.  */
        private val SUPPORTED_FLAGS: Int = (UNIX_LINES
                or CASE_INSENSITIVE
                or COMMENTS
                or MULTILINE
                or LITERAL
                or DOTALL
                or UNICODE_CASE
                or UNICODE_CHARACTER_CLASS)

        /** Validates that no unsupported flag bits are set.  */
        private fun validateFlags(flags: Int) {
            val unsupported = flags and SUPPORTED_FLAGS.inv()
            require(unsupported == 0) {
                ("Unsupported flags: 0x"
                        + Integer.toHexString(unsupported)
                        + ". CANON_EQ is not supported by SafeRE.")
            }
        }

        /** Returns JDK-compatible effective flags after applying implied flags.  */
        private fun effectiveFlags(flags: Int): Int {
            var flags = flags
            if ((flags and UNICODE_CHARACTER_CLASS) != 0) {
                flags = flags or UNICODE_CASE
            }
            return flags
        }

        /**
         * Converts `java.util.regex.Pattern` flags to internal [ParseFlags].
         * 
         * 
         * The baseline is [ParseFlags.LIKE_PERL], which includes `ONE_LINE` (single-line
         * mode where `^` and `$` match only at the start/end of the entire input).
         */
        fun toParseFlags(flags: Int): Int {
            // Start with LIKE_PERL as the baseline.
            var pf = ParseFlags.LIKE_PERL

            if ((flags and CASE_INSENSITIVE) != 0) {
                pf = pf or ParseFlags.FOLD_CASE
            }
            if ((flags and MULTILINE) != 0) {
                // Multiline mode: ^ and $ match at line boundaries.
                // Remove ONE_LINE so that ^ and $ are per-line.
                pf = pf and ParseFlags.ONE_LINE.inv()
            }
            if ((flags and DOTALL) != 0) {
                pf = pf or ParseFlags.DOT_NL
            }
            if ((flags and LITERAL) != 0) {
                pf = pf or ParseFlags.LITERAL
            }
            if ((flags and COMMENTS) != 0) {
                pf = pf or ParseFlags.COMMENTS
            }
            if ((flags and UNICODE_CASE) != 0) {
                pf = pf or ParseFlags.UNICODE_CASE
            }
            if ((flags and UNICODE_CHARACTER_CLASS) != 0) {
                pf = pf or (ParseFlags.UNICODE_GROUPS or ParseFlags.UNICODE_CHAR_CLASS)
            }
            if ((flags and UNIX_LINES) != 0) {
                pf = pf or ParseFlags.UNIX_LINES
            }
            return pf
        }

        // ---------------------------------------------------------------------------
        // Grapheme cluster boundary analysis
        // ---------------------------------------------------------------------------
        private fun startsWithGraphemeClusterBoundary(re: Regexp?): Boolean {
            val first: Regexp? = firstMeaningfulNode(re)
            return first != null && first.op == RegexpOp.GRAPHEME_CLUSTER_BOUNDARY
        }

        private fun hasInternalExplicitGraphemeBoundary(re: Regexp?): Boolean {
            return GraphemeBoundaryContextWalker()
                .walk(re, GraphemeBoundaryContext.noMatch())!!
                .hasInternalExplicitBoundary
        }

        private fun extractKeywordAlternation(re: Regexp?, patternFlags: Int): KeywordAlternation? {
            if ((patternFlags and UNICODE_CASE) != 0) {
                return null
            }

            val node: Regexp? = unwrapImplicitCapture(re)
            if (node == null || node.op != RegexpOp.CONCAT) {
                return null
            }
            var greedyWholeInput = false
            var coreOffset = 0
            val nodeSubs = node.subs!!
            if (node.nsub() == 5 && isGreedyAnyCharStar(nodeSubs.first())
                && isGreedyAnyCharStar(nodeSubs.last())
            ) {
                greedyWholeInput = true
                coreOffset = 1
            } else if (node.nsub() != 3) {
                return null
            }
            val before: Regexp? = unwrapImplicitCapture(nodeSubs.get(coreOffset))
            var middle: Regexp? = unwrapImplicitCapture(nodeSubs.get(coreOffset + 1))
            val after: Regexp? = unwrapImplicitCapture(nodeSubs.get(coreOffset + 2))
            if (before == null || before.op != RegexpOp.WORD_BOUNDARY || after == null || after.op != RegexpOp.WORD_BOUNDARY) {
                return null
            }

            var captureGroup = -1
            if (middle != null && middle.op == RegexpOp.CAPTURE && middle.cap > 0) {
                captureGroup = middle.cap
                middle = unwrapImplicitCapture(middle.sub())
            }
            val middleSubs = middle?.subs
            if (middle == null || middle.op != RegexpOp.ALTERNATE || middleSubs == null || middleSubs.isEmpty()) {
                return null
            }
            if (hasOtherUserCaptures(middle, captureGroup)) {
                return null
            }

            val keywords = arrayOfNulls<String>(middleSubs.size)
            val firstAscii = AsciiBitmap.Builder()
            for (i in middleSubs.indices) {
                val keyword: String? = extractAsciiCaseInsensitiveLiteral(middleSubs.get(i))
                if (keyword == null || keyword.isEmpty()) {
                    return null
                }
                keywords[i] = keyword
                firstAscii.add(keyword[0].code)
            }

            val beforeUnicodeWordBoundary = (before.flags and ParseFlags.UNICODE_CHAR_CLASS) != 0
            val afterUnicodeWordBoundary = (after.flags and ParseFlags.UNICODE_CHAR_CLASS) != 0
            if (beforeUnicodeWordBoundary != afterUnicodeWordBoundary) {
                return null
            }
            return KeywordAlternation(
                keywords.requireNoNulls(), firstAscii.build(), captureGroup, beforeUnicodeWordBoundary, greedyWholeInput
            )
        }

        private fun isGreedyAnyCharStar(re: Regexp?): Boolean {
            val node: Regexp? = unwrapImplicitCapture(re)
            return node != null && node.op == RegexpOp.STAR && !node.nonGreedy() && node.sub().op == RegexpOp.ANY_CHAR
        }

        private fun hasOtherUserCaptures(re: Regexp?, allowedCapture: Int): Boolean {
            val stack: Deque<Regexp> = ArrayDeque<Regexp>()
            stack.push(re)
            while (!stack.isEmpty()) {
                val node = stack.pop()
                if (node.op == RegexpOp.CAPTURE && node.cap > 0 && node.cap != allowedCapture) {
                    return true
                }
                val subs = node.subs
                if (subs != null) {
                    for (sub in subs) {
                        if (sub != null) {
                            stack.push(sub)
                        }
                    }
                }
            }
            return false
        }

        private fun unwrapImplicitCapture(re: Regexp?): Regexp? {
            var node: Regexp? = re
            while (node != null && node.op == RegexpOp.CAPTURE && node.cap == 0) {
                node = node.sub()
            }
            return node
        }

        private fun extractAsciiCaseInsensitiveLiteral(re: Regexp?): String? {
            val node: Regexp? = unwrapImplicitCapture(re)
            if (node == null) {
                return null
            }
            val sb = StringBuilder()
            if (!appendAsciiCaseInsensitiveLiteral(node, sb)) {
                return null
            }
            return sb.toString()
        }

        private fun appendAsciiCaseInsensitiveLiteral(node: Regexp?, sb: StringBuilder): Boolean {
            var node = node
            node = unwrapImplicitCapture(node)
            if (node == null) {
                return false
            }
            return when (node.op) {
                RegexpOp.CONCAT -> {
                    val subs = node.subs
                    if (subs == null || subs.isEmpty()) {
                        return false
                    }
                    for (sub in subs) {
                        if (!appendAsciiCaseInsensitiveLiteral(sub, sb)) {
                            return false
                        }
                    }
                    true
                }

                RegexpOp.LITERAL -> {
                    val cp = node.rune
                    if (cp < 0 || cp >= 128 || !isAsciiLiteralKeywordChar(cp)) {
                        return false
                    }
                    if ((node.flags and ParseFlags.FOLD_CASE) == 0) {
                        return false
                    }
                    sb.append(Ascii.toLowerCase(cp).toChar())
                    true
                }

                RegexpOp.LITERAL_STRING -> {
                    val runes = node.runes
                    if (runes == null || runes.size == 0) {
                        return false
                    }
                    if ((node.flags and ParseFlags.FOLD_CASE) == 0) {
                        return false
                    }
                    for (cp in runes) {
                        if (cp < 0 || cp >= 128 || !isAsciiLiteralKeywordChar(cp)) {
                            return false
                        }
                        sb.append(Ascii.toLowerCase(cp).toChar())
                    }
                    true
                }

                RegexpOp.CHAR_CLASS -> {
                    val cp: Int = asciiFoldedLiteralChar(node.charClass)
                    if (cp < 0) {
                        return false
                    }
                    sb.append(cp.toChar())
                    true
                }

                else -> false
            }
        }

        private fun isAsciiLiteralKeywordChar(cp: Int): Boolean {
            return Ascii.isWordChar(cp)
        }

        private fun asciiFoldedLiteralChar(cc: CharClass?): Int {
            if (cc == null || cc!!.numRunes() != 2) {
                return -1
            }
            var cp = 'a'.code
            while (cp <= 'z'.code) {
                if (cc.contains(cp) && cc.contains(Ascii.toUpperCase(cp))) {
                    return cp
                }
                cp++
            }
            return -1
        }

        private fun firstMeaningfulNode(re: Regexp?): Regexp? {
            val node: Regexp? = unwrapCaptures(re)
            if (node == null) {
                return null
            }
            if (node.op == RegexpOp.CONCAT && node.nsub() > 0) {
                return unwrapCaptures(node.subs!!.get(0))
            }
            return node
        }

        private fun unwrapCaptures(re: Regexp?): Regexp? {
            var node: Regexp? = re
            while (node != null && (node.op == RegexpOp.CAPTURE || node.op == RegexpOp.NON_CAPTURE)) {
                node = node.sub()!!
            }
            return node
        }

        /**
         * Detects patterns that are structurally a single character class under a quantifier covering the
         * entire string — e.g., `[a-zA-Z]+`, `\d*`, `\w{1,}`, `[0-9]+`. When
         * detected, `matches()` can use a tight character-scanning loop with precomputed bitmaps
         * instead of the full engine cascade.
         * 
         * 
         * Sees through the implicit group-0 CAPTURE wrapper. Returns `null` if the pattern has
         * any user capture groups (the fast path only produces group 0).
         */
        private fun extractCharClassMatch(re: Regexp, hasUserCaptures: Boolean): CharClassMatchInfo? {
            var node: Regexp = re

            // Unwrap implicit group-0 capture.
            if (node.op == RegexpOp.CAPTURE && node.cap == 0) {
                node = node.sub()!!
            }

            // Must be a quantifier: PLUS (min=1), STAR (min=0), or REPEAT (min >= 0, max = -1).
            val allowEmpty: Boolean
            when (node.op) {
                RegexpOp.PLUS -> allowEmpty = false
                RegexpOp.STAR -> allowEmpty = true
                RegexpOp.REPEAT -> {
                    if (node.max != -1) {
                        return null // bounded repeat like {3,5} — not a "cover entire string" pattern
                    }
                    if (node.min > 1) {
                        return null // {2,} or higher — would need code point counting; not worth optimizing
                    }
                    allowEmpty = (node.min == 0)
                }

                else -> {
                    return null
                }
            }

            val inner = node.sub()!!

            // Reject if the original pattern has user capture groups — the fast path only produces
            // group 0, so it can't provide group(1) etc.
            if (hasUserCaptures) {
                return null
            }

            val cc: CharClass?
            if (inner.op == RegexpOp.CHAR_CLASS && inner.charClass != null) {
                cc = inner.charClass
            } else if (inner.op == RegexpOp.LITERAL) {
                cc = literalCharClass(inner.rune, inner.flags)
            } else {
                return null
            }
            if (cc!!.isEmpty()) {
                return null
            }

            // Build flat ranges array and precompute ASCII bitmaps.
            val numRanges = cc.numRanges()
            val ranges = IntArray(numRanges * 2)
            var b0: Long = 0
            var b1: Long = 0
            for (i in 0..<numRanges) {
                val lo = cc.lo(i)
                val hi = cc.hi(i)
                ranges[i * 2] = lo
                ranges[i * 2 + 1] = hi
                val start = Math.max(lo, 0)
                val end = Math.min(hi, 127)
                for (cp in start..end) {
                    if (cp < 64) {
                        b0 = b0 or (1L shl cp)
                    } else {
                        b1 = b1 or (1L shl (cp - 64))
                    }
                }
            }
            return CharClassMatchInfo(ranges, b0, b1, allowEmpty)
        }

        /**
         * Detects patterns that are exactly one character class, such as `[a-z]` or `\p{javaLetter}`. The fast path only produces group 0, so patterns with user capture groups are
         * excluded.
         */
        private fun extractSingleCharClass(re: Regexp, hasUserCaptures: Boolean): CharClassScanInfo? {
            var node: Regexp = re
            if (node.op == RegexpOp.CAPTURE && node.cap == 0) {
                node = node.sub()!!
            }
            if (node.op != RegexpOp.CHAR_CLASS || node.charClass == null || hasUserCaptures) {
                return null
            }
            val cc = node.charClass!!
            if (cc.isEmpty()) {
                return null
            }
            return CharClassScanInfo.fromCharClass(cc)
        }

        private fun literalCharClass(cp: Int, flags: Int): CharClass {
            val ccb = CharClassBuilder()
            if ((flags and ParseFlags.FOLD_CASE) == 0) {
                ccb.addRange(cp, cp)
            } else if ((flags and ParseFlags.UNICODE_CASE) == 0) {
                UnicodeCaseFolding.addAsciiFoldedRange(ccb, cp, cp)
            } else {
                UnicodeCaseFolding.addUnicodeFoldedRange(ccb, cp, cp)
            }
            return ccb.build()
        }

        /**
         * Extracts the full literal string if this pattern is entirely literal (no metacharacters, no
         * quantifiers, no alternation). Transparent groups (e.g. non-capturing groups) are unwrapped.
         * 
         * 
         * Returns [LiteralResult.NONE] if the pattern is not fully literal. For case-insensitive
         * patterns, returns the lowercase version and foldCase=true.
         */
        private fun extractLiteralMatch(re: Regexp?): LiteralResult {
            var node: Regexp? = re

            // Unwrap outer CAPTURE (group 0).
            while (node != null && node.op == RegexpOp.CAPTURE) {
                node = node.sub()!!
            }
            if (node == null) {
                return LiteralResult.NONE
            }

            var foldCase = false
            var foldCaseInitialized = false
            val sb = StringBuilder()

            when (node.op) {
                RegexpOp.LITERAL -> {
                    if (!Utils.isValidRune(node.rune)) {
                        return LiteralResult.NONE
                    }
                    foldCase = (node.flags and ParseFlags.FOLD_CASE) != 0
                    sb.appendCodePoint(node.rune)
                }

                RegexpOp.LITERAL_STRING -> {
                    foldCase = (node.flags and ParseFlags.FOLD_CASE) != 0
                    if (node.runes != null) {
                        for (r in node.runes) {
                            if (!Utils.isValidRune(r)) {
                                return LiteralResult.NONE
                            }
                            sb.appendCodePoint(r)
                        }
                    }
                }

                RegexpOp.CONCAT -> {
                    for (child in node.subs!!) {
                        // Each child must be LITERAL or LITERAL_STRING (not wrapped in CAPTURE etc.)
                        var c = child
                        while (c != null && (c.op == RegexpOp.CAPTURE || c.op == RegexpOp.NON_CAPTURE)) {
                            c = c.sub()
                        }
                        if (c == null) {
                            return LiteralResult.NONE
                        }
                        if (c.op == RegexpOp.BEGIN_TEXT) {
                            if (sb.length > 0) {
                                return LiteralResult.NONE
                            }
                            continue
                        }
                        val childFoldCase = (c.flags and ParseFlags.FOLD_CASE) != 0
                        when (c.op) {
                            RegexpOp.LITERAL -> {
                                if (!Utils.isValidRune(c.rune)) {
                                    return LiteralResult.NONE
                                }
                                if (Inst.simpleFold(c.rune) != c.rune) {
                                    if (!foldCaseInitialized) {
                                        foldCase = childFoldCase
                                        foldCaseInitialized = true
                                    } else if (childFoldCase != foldCase) {
                                        return LiteralResult.NONE
                                    }
                                }
                                sb.appendCodePoint(c.rune)
                            }

                            RegexpOp.LITERAL_STRING -> {
                                if (c.runes == null) {
                                    return LiteralResult.NONE
                                }
                                for (r in c.runes) {
                                    if (!Utils.isValidRune(r)) {
                                        return LiteralResult.NONE
                                    }
                                    if (Inst.simpleFold(r) != r) {
                                        if (!foldCaseInitialized) {
                                            foldCase = childFoldCase
                                            foldCaseInitialized = true
                                        } else if (childFoldCase != foldCase) {
                                            return LiteralResult.NONE
                                        }
                                    }
                                    sb.appendCodePoint(r)
                                }
                            }

                            else -> {
                                return LiteralResult.NONE
                            }
                        }
                    }
                }

                RegexpOp.EMPTY_MATCH -> {
                    // Empty pattern matches empty string.
                    return LiteralResult("", false)
                }

                else -> {
                    return LiteralResult.NONE
                }
            }

            if (sb.isEmpty()) {
                return LiteralResult("", false)
            }
            return LiteralResult(
                if (foldCase) sb.toString().lowercase(Locale.ROOT) else sb.toString(), foldCase
            )
        }
    }
}
