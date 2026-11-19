// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere


import kotlin.jvm.JvmRecord
/**
 * Immutable tuning policy and diagnostic metadata for start-position and DFA accelerators.
 * 
 * 
 * Callers compare the work an accelerator saves with the cost of the path it replaces. DFA start
 * acceleration uses `minProfitableSkip` to credit only positions bypassed by each scan,
 * excluding positions already consumed by the DFA. Literal-search anchor verification instead uses
 * `minDensityStride` to compare candidate density with a direct substring search. Repeated
 * unprofitable calls consume the strike budget and trigger bounded quarantine windows.
 * 
 * @param minProfitableSkip Minimum skip distance (in chars or bytes) required for this accelerator
 * to be profitable over direct scalar DFA execution.
 * @param strikeBudget Unsuccessful-call allowance before declaring adaptive defeat. DFA callers
 * scale this by `minProfitableSkip` and account for partial losses in input units.
 * @param minDensityStride Minimum candidate stride (in chars or bytes) for literal-search anchor
 * verification to be profitable over direct substring search.
 * @param initialQuarantineWindow Span of input to skip acceleration over on the first defeat.
 * @param maxQuarantineWindow Ceiling on the quarantine window under exponential backoff.
 * @param isExactMatchCandidate Whether this accelerator identifies an exact candidate match start
 * that can be validated directly.
 * @param strategy The diagnostic strategy associated with this accelerator, or `null` if
 * none.
 */
@JvmRecord
internal data class AcceleratorPolicy(
    val minProfitableSkip: Int,
    val strikeBudget: Int,
    val minDensityStride: Int,
    val initialQuarantineWindow: Int,
    val maxQuarantineWindow: Int,
    val isExactMatchCandidate: Boolean,
    val strategy: MatchStrategy?
) {
    /** Returns this policy carrying `strategy` for diagnostic attribution.  */
    fun withStrategy(strategy: MatchStrategy?): AcceleratorPolicy {
        return AcceleratorPolicy(
            minProfitableSkip,
            strikeBudget,
            minDensityStride,
            initialQuarantineWindow,
            maxQuarantineWindow,
            isExactMatchCandidate,
            strategy
        )
    }

    // TODO: Conduct systematic empirical micro-benchmarking across diverse CPU architectures (x86
    // AVX-512/AVX2, ARM Neon) to precisely tune minimum profitable skip thresholds.
    init {
        // DFA start acceleration keeps its deficit, which stays below the loss limit plus one call's
        // shortfall, in the packed AdaptiveBackoff state.
        require(!(minProfitableSkip < 0 || strikeBudget < 0 || strikeBudget.toLong() * minProfitableSkip + minProfitableSkip > AdaptiveBackoff.MAX_DEFICIT || initialQuarantineWindow <= 0 || maxQuarantineWindow < initialQuarantineWindow)) { "invalid accelerator policy" }
    }

    companion object {
        /**
         * Candidate strikes tolerated before quarantining an accelerator.
         * 
         * 
         * Allow a short run of unprofitable calls before falling back to scalar DFA execution or
         * direct substring search. Profitable calls can repay strikes, so brief changes in input density
         * do not immediately disable an otherwise useful accelerator.
         */
        private const val DEFAULT_STRIKE_BUDGET = 16

        /**
         * Cheap interior-hit rejections before switching to alternating class scans.
         * 
         * 
         * A small bounded allowance avoids a second scanner on short runs of nearby candidates. Longer
         * runs use the leading class to skip dense interior hits in bulk.
         */
        const val FIXED_OFFSET_CLASS_CHEAP_REJECTIONS: Int = 4

        /**
         * Candidate stride below which a candidate counts as a strike.
         * 
         * 
         * Break-even for a literal scan: anchoring on a single character saves roughly 0.1 ns/byte
         * over [String.indexOf] and spends one verification per anchor occurrence, so
         * it stops paying at roughly one candidate per 64-90 bytes.
         */
        private const val DEFAULT_MIN_DENSITY_STRIDE = 64

        /** Initial quarantine window (in bytes/chars) after repeated unprofitable calls.  */
        private const val DEFAULT_INITIAL_QUARANTINE_WINDOW = 2048

        /** Maximum quarantine window (in bytes/chars) under exponential backoff.  */
        private const val DEFAULT_MAX_QUARANTINE_WINDOW = 65536

        private fun of(
            minProfitableSkip: Int, isExactMatchCandidate: Boolean, strategy: MatchStrategy?
        ): AcceleratorPolicy {
            return AcceleratorPolicy(
                minProfitableSkip,
                DEFAULT_STRIKE_BUDGET,
                DEFAULT_MIN_DENSITY_STRIDE,
                DEFAULT_INITIAL_QUARANTINE_WINDOW,
                DEFAULT_MAX_QUARANTINE_WINDOW,
                isExactMatchCandidate,
                strategy
            )
        }

        /** Policy for vectorized literal and fixed-offset substring searches (AVX2 / SWAR).  */
        val LITERAL: AcceleratorPolicy = of(16, true, MatchStrategy.LITERAL)

        /** Policy for vectorized multi-literal and Teddy SIMD filters.  */
        val VECTOR_MULTI_LITERAL: AcceleratorPolicy = of(64, true, MatchStrategy.LITERAL)

        /** Policy for character class bitmap and range table scanning.  */
        val CHAR_CLASS: AcceleratorPolicy = of(24, false, MatchStrategy.CHARACTER_CLASS)

        /** Policy for line anchor ('^', '$') boundary acceleration.  */
        val LINE_ANCHOR: AcceleratorPolicy = of(16, false, null)

        /**
         * Policy for leading-expansion wrappers, which delegate the actual scan to an inner accelerator
         * and carry that accelerator's diagnostic strategy.
         * 
         * 
         * A call costs more than its inner scan: the wrapper expands the optional leading class
         * backward from each inner candidate, and the DFA restarts there. On dense citations such as
         * `citationScrubberFullWidth` (`?[\[\uFF3B]...` over text where every `\uFF3B`
         * starts a match), skips alternate between about 4 and 29 characters. At a break-even of 16 the
         * long skips repay the short ones, so the accelerator never quarantined there, and on aarch64 it
         * cost `FullWidth.2048` 1.26x and `FullWidth.65536` 1.15x of `main` (String).
         * At 32 those read 1.09x and 1.01x, x86 is neutral, and sparse citations and short inputs still
         * accelerate. The cost is `citationScrubberFullWidthNoMatch.2048` about 7% slower on both
         * ISAs, still faster than `main`.
         */
        val LEADING_EXPANSION: AcceleratorPolicy = of(32, false, null)

        /** Default fallback policy for generic or composite accelerators.  */
        val DEFAULT: AcceleratorPolicy = of(32, false, null)
    }
}
