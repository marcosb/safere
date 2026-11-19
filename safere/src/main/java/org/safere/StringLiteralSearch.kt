// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere



import kotlin.math.*
/**
 * Exact-case literal search in a [String], anchored on the literal's rarest byte.
 * 
 * 
 * [String.indexOf] is intrinsified, but its multi-character form is several
 * times slower than its single-character form: on a 100 KB haystack a 3-character needle scans at
 * roughly 8 GB/s while `indexOf(char)` reaches roughly 50 GB/s. Searching for one rare
 * character of the literal and verifying the rest keeps the scan on the faster kernel and only pays
 * for verification where that character actually occurs.
 * 
 * 
 * This mirrors what the case-insensitive path already does. [ ] picks an anchor with [ ][RarityOracle.rarestAsciiOffset] and scans with [Matcher.indexOfIgnoreCase]; before this
 * class existed, the exact-case path — much the more common one — issued a bare `text.indexOf(literal)`.
 * 
 * 
 * The trade is only profitable while the anchor is sparse. `indexOf(String)` is not a slow
 * scan that verification improves on; it is a fused filter and verify that emits no false positives
 * at all, so an anchor that occurs every few bytes loses to it badly. Three defences apply:
 * 
 * 
 *  * Windows too short for the saving to cover a wrong guess are not anchored at all.
 *  * A false candidate is rejected on the literal's last character before [       ][String.startsWith] is called at all, which is what makes a false positive cheap enough to
 * tolerate. The first character is left to `startsWith`, which compares it first.
 *  * Literals are anchored only while their bounded verification cost remains a constant factor
 * of the candidate-spacing model.
 *  * Candidates arriving closer together than [AcceleratorPolicy.minDensityStride] are
 * counted as strikes, and once the budget is spent the search reverts to `indexOf(String)` — the behaviour that predates anchoring — for the rest of the scan.
 * 
 * 
 * 
 * The decision deliberately does not outlive a single call. Carrying it in an object shared
 * across the calls of one search was measured at roughly four times the cost of the entire search
 * on a candidate-dense input, without ever changing a decision, because the strike constants stop
 * being compile-time constants and the reference threads a field into the DFA's innermost loop.
 * Backing off across calls is already handled a layer up, by the quarantine in [Dfa] that
 * wraps these calls; what was missing, and what this class supplies, is protection within one.
 */
internal object StringLiteralSearch {
    /** Sentinel `anchorOffset` meaning "search with [String.indexOf]".  */
    val NO_ANCHOR: Int = -1

    /**
     * Sentinel [.singleAsciiChar] result meaning "this is not a one-character ASCII literal".
     */
    val NOT_SINGLE_ASCII: Int = -1

    /**
     * Adaptive-defeat tuning, read into `static final` slots so that the scan loop below sees
     * compile-time constants. Reading these through an object instead costs about four times the
     * runtime of the whole search on a candidate-dense input, for no change in behaviour.
     */
    private val STRIKE_BUDGET: Int = AcceleratorPolicy.LITERAL.strikeBudget

    private val MIN_DENSITY_STRIDE: Int = AcceleratorPolicy.LITERAL.minDensityStride

    /**
     * What one dense stride costs against [.STRIKE_BUDGET].
     * 
     * 
     * The budget is a *net* count, and a net count alone answers the wrong question. An anchor
     * that is mostly sparse and occasionally clumps should keep going, which is what the repayment
     * below is for; an anchor that is uniformly dense has shown everything it is going to show within
     * a handful of observations, and charging it one unit at a time makes it pay fifteen more before
     * conceding. `(error:\[)[A-Z](\] code:500)` anchors on `']'`, which recurs every 46
     * characters in a log line, so it strikes on every iteration, spends the whole budget, and then
     * scans the window again with [String.indexOf] — a measured fixed cost of
     * about 100 ns on every call. At the default budget of 16 this charge concedes after four such
     * observations, while still leaving four sparse strides able to buy back one dense one.
     * 
     * 
     * Conceding that early is deliberate, because the two directions are not symmetric. A winning
     * anchor never charges a strike at all — it returns from the verification without ever reaching
     * the accounting — so no amount of budget buys a winner anything, while every unit of it is spent
     * by a loser. Conceding early costs at most a few characters of a scan that was already fast;
     * conceding late costs a verification storm.
     * 
     * 
     * Weighting the existing counter rather than adding a second one is not a stylistic choice. A
     * separate consecutive counter needs its own local, its own increment, its own test and its own
     * reset, which took `indexOf` from 201 bytecodes to 216 — and 219 is a size at which this
     * method has already been measured to stop being inlined, costing more than the fix recovers.
     * This is also why the charge is a literal rather than `STRIKE_BUDGET / 4`: the budget is
     * read from [AcceleratorPolicy] and so is not a compile-time constant, and a derived charge
     * compiles to a static read and an add where a literal one folds into the `iinc` that was
     * already there.
     */
    private const val STRIKE_CHARGE = 4

    /** Keeps the anchored loop's per-candidate verification cost bounded by a constant.  */
    val MAX_ANCHORED_LITERAL_LENGTH: Int = MIN_DENSITY_STRIDE * 2

    /**
     * Shortest remaining window worth anchoring.
     * 
     * 
     * The bound comes from the cost model. Anchoring saves about 0.1 ns per character scanned and
     * risks one wasted verification per strike, so it is worth attempting only once the window is
     * long enough for the saving to cover the budget. Short windows are not a corner case: a `findAll` over a match-dense input issues one call per match, and those calls scan the gap
     * between neighbouring matches rather than the whole input.
     * 
     * 
     * It is expressed as the span a full budget covers at exactly the minimum stride, which is
     * conservative rather than exact: [.STRIKE_CHARGE] lets a uniformly dense anchor reach a
     * verdict four strides in, well inside this window. Lowering it to match would newly anchor every
     * window between the two, which is a much larger change than the strike rule and wants its own
     * measurement.
     * 
     * 
     * Package-private so that the differential tests can size their inputs past it; below it they
     * would exercise [String.indexOf] against itself.
     */
    val MIN_ANCHORED_WINDOW: Int = STRIKE_BUDGET * MIN_DENSITY_STRIDE

    /**
     * Chooses the offset within `literal` to anchor the scan on, or [.NO_ANCHOR] if this
     * literal is better served by [String.indexOf] directly.
     * 
     * 
     * Declines in four cases:
     * 
     * 
     *  * Literals shorter than two characters, where the anchor would be the literal itself, so
     * verification could never reject a candidate.
     *  * Literals longer than [.MAX_ANCHORED_LITERAL_LENGTH], so repeated full verification
     * cannot make the added anchored work grow with both input and literal length.
     *  * Literals containing no ASCII character, where the rarity model has nothing to say.
     *  * Literals whose rarest character is still common enough to be a poisonous anchor, where
     * verification would dominate the scan.
     * 
     * 
     * 
     * This is a prior, not a prediction: the rarest character of a literal is frequently not the
     * rarest character of the haystack, and can even be the worst available choice. Runtime feedback
     * in [.indexOf] is what bounds the cost of getting it wrong.
     */
    fun anchorOffset(literal: String?): Int {
        if (literal == null || literal.length < 2 || literal.length > MAX_ANCHORED_LITERAL_LENGTH) {
            return NO_ANCHOR
        }
        val offset: Int = RarityOracle.rarestAsciiOffset(literal, literal.length)
        val anchor: Char = literal[offset]
        if (anchor.code >= 128) {
            return NO_ANCHOR
        }
        if (RarityOracle.byteRarity(anchor.code) <= RarityOracle.POISONOUS_ANCHOR_MAX_RARITY) {
            return NO_ANCHOR
        }
        return offset
    }

    /** Returns the anchor character for an offset returned by [.anchorOffset].  */
    fun anchorAt(literal: String, anchorOffset: Int): Char {
        return if (anchorOffset == NO_ANCHOR) '\u0000' else literal[anchorOffset]
    }

    /**
     * Returns the index of the first occurrence of `literal` at or after `fromIndex`, or
     * `-1`.
     * 
     * 
     * The two bail-outs share one condition and one call site on purpose. This method sits close
     * enough to the inlining size limit that a couple of bytecodes decide whether a caller inlines it
     * and constant-folds the anchor out of its descriptor; splitting the scan into its own method, or
     * giving each bail-out its own `return`, each cost about a third of the runtime on one
     * caller or the other. See the notes in the B1 write-up before restructuring this.
     * 
     * @param anchorOffset an offset from [.anchorOffset], or [.NO_ANCHOR]
     * @param anchor the character at `anchorOffset`, precomputed at compile time
     */
    @MaxBytecodeSize(
        value = 244,
        min = 244,
        reason = ("Tuned: it is called from StringStartAccelerator's literal and fixed-offset scans"
                + " (through indexOfPlanned) and from RejectPrefilter.Literal.canReject. Variants"
                + " about 8 bytes smaller and 10 or 19 bytes larger, including ones that only added"
                + " bytecodes that never ran, each cost 25-35% on one of those callers. Kotlin emits"
                + " 244 bytes for the ported shape (BytecodeBudgetTest); re-benchmark those three"
                + " callers before changing size again.")
    )
    fun indexOf(text: String, literal: String, anchorOffset: Int, anchor: Char, fromIndex: Int): Int {
        var pos = max(0, fromIndex)
        val length: Int = text.length
        if (anchorOffset == NO_ANCHOR || length - pos < MIN_ANCHORED_WINDOW) {
            return indexOfDirect(text, literal, pos)
        }
        val literalLength: Int = literal.length
        val lastStart = length - literalLength
        val lastChar: Char = literal[literalLength - 1]
        val lastCharOffset = literalLength - 1
        var strikes = 0
        var lastCandidate = pos

        while (pos <= lastStart) {
            val searchFrom = pos + anchorOffset
            val hit: Int = text.indexOfCodePointBounded(anchor.code, searchFrom, length)
            if (WorkCounterConfig.ENABLED) {
                // The character at `hit` is inside the literal that the verification charge below already
                // accounts for, so it is not charged here as well. Counting it twice would make the work
                // recorded for a search depend on whether this method anchored or delegated, and the two
                // have to agree: `indexOfDirect` charges `idx - fromIndex + literalLength`, counting every
                // character it examines exactly once.
                WorkCounter.record((if (hit < 0) length - searchFrom else hit - searchFrom).toLong())
            }
            if (hit < 0) {
                return -1
            }
            val candidate = hit - anchorOffset
            if (candidate > lastStart) {
                return -1
            }
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record(literalLength.toLong())
            }
            // Reject on the literal's last character before paying for startsWith. The anchor is by
            // construction the rarest character of the literal, which is often not the most selective one
            // for this particular haystack: 'val=200' anchors on '=' where 'v' would have rejected every
            // false candidate outright. The literal's first character is deliberately not checked here,
            // because startsWith compares it first anyway.
            if (text[candidate + lastCharOffset] == lastChar
                && text.startsWith(literal, candidate)
            ) {
                return candidate
            }
            val stride = candidate - lastCandidate
            lastCandidate = candidate
            pos = candidate + 1
            if (stride < MIN_DENSITY_STRIDE) {
                if ((STRIKE_CHARGE.let { strikes += it; strikes }) >= STRIKE_BUDGET) {
                    return indexOfDirect(text, literal, pos)
                }
            } else if (strikes > 0) {
                strikes--
            }
        }
        return -1
    }

    /** Unanchored search, with the work accounting the accelerators previously applied inline.  */
    fun indexOfDirect(text: String, literal: String, fromIndex: Int): Int {
        val idx = text.indexOf(literal, fromIndex)
        if (WorkCounterConfig.ENABLED) {
            val scanned: Int = if (idx >= 0) idx - fromIndex + literal.length else text.length - fromIndex
            WorkCounter.record(max(0, scanned).toLong())
        }
        return idx
    }

    /**
     * Returns the single ASCII character `literal` consists of, or [.NOT_SINGLE_ASCII].
     * 
     * 
     * Callers hold the result in the compiled plan and branch on it, rather than asking here on
     * every call. [String.indexOf] with a one-character needle is not the same
     * kernel as [String.indexOf]: measured over the same 100,000-byte input on
     * x86_64, the string form runs at 0.098 ns/byte and the character form at 0.0155, a factor of
     * 6.3. Nothing between the accelerator and the intrinsic looks at the needle's length, so a
     * one-character needle does not reach the faster kernel by itself.
     * 
     * 
     * The test lives here, at plan time, because putting it in [.indexOfDirect] was measured
     * and rejected: it took that method from 8 bytecodes to 40 and charged every caller for a branch
     * only 8% of literals can take, including the anchored path that calls it as a fallback. On
     * aarch64, where the two kernels are the same speed to four digits and there is no win to offset
     * it, that cost alone moved two unrelated trials by 5–6%.
     * 
     * 
     * Declines on non-ASCII, where [String.indexOf] matches by code point and so
     * is a different search, and — via the callers, which only hold this for case-sensitive literals
     * — on anything folded.
     */
    fun singleAsciiChar(literal: String?): Int {
        return if (literal != null && literal.length == 1 && literal[0].code < 128)
            literal[0]
                .code
        else
            NOT_SINGLE_ASCII
    }

    /**
     * Searches for a single character, charging the same work a one-character [.indexOfDirect]
     * would. The accounting has to agree: a search must not record different work depending on which
     * kernel the plan chose for it.
     * 
     * 
     * That is also why `fromIndex` is clamped here. A one-character literal never has an
     * anchor, so the path this replaces is [.indexOf] clamping and then delegating to [ ][.indexOfDirect]; charging against an unclamped `fromIndex` would overcount by exactly the
     * amount it was negative by.
     */
    fun indexOfChar(text: String, ch: Int, fromIndex: Int): Int {
        val pos = max(0, fromIndex)
        val idx: Int = text.indexOfCodePointBounded(ch, pos, text.length)
        if (WorkCounterConfig.ENABLED) {
            val scanned = if (idx >= 0) idx - pos + 1 else text.length - pos
            WorkCounter.record(max(0, scanned).toLong())
        }
        return idx
    }

    /**
     * Searches with whichever kernel the plan chose, given a `singleAsciiChar` from [ ][.singleAsciiChar].
     * 
     * 
     * One method rather than the same conditional at both plan sites, and small enough that it
     * costs a caller nothing beyond the call it replaces. There are only two: the third caller of
     * [.indexOf], `RejectPrefilter.Literal`, cannot reach this at all, because `MultiAnchorCompiler.extractRequiredLiteral` will not return a literal shorter than two
     * characters.
     */
    fun indexOfPlanned(
        text: String,
        literal: String,
        anchorOffset: Int,
        anchor: Char,
        singleAsciiChar: Int,
        fromIndex: Int
    ): Int {
        return if (singleAsciiChar == NOT_SINGLE_ASCII)
            indexOf(text, literal, anchorOffset, anchor, fromIndex)
        else
            indexOfChar(text, singleAsciiChar, fromIndex)
    }
}
