// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Static helpers for the start-acceleration give-up rule, over state packed into one `long`.
 * 
 * 
 * The state records three values, all in input units (chars or bytes):
 * 
 * 
 *  * the *deficit* (bits 0-23): how much DFA work recent accelerator calls cost beyond
 * what they saved;
 *  * the *backoff shift* (bits 24-31): how many consecutive defeats have doubled the
 * quarantine window since the deficit was last repaid;
 *  * the *resume position* (bits 32-63): the position before which acceleration is
 * quarantined.
 * 
 * 
 * 
 * [.NEUTRAL] (zero) is the state of an accelerator with no history. Every state is safe to
 * pass to any search: the state only decides whether to call the accelerator, never what a search
 * matches. A [Matcher] carries the state across the searches of one sequence of `find()` calls, reads it once per search into a local, and clears it when the input, region or
 * pattern changes. Tuning constants come from [AcceleratorPolicy].
 * 
 * 
 * The state is a primitive rather than a holder object so that the DFA loop can keep it in a
 * local, and the helpers are small static methods so that the hot call sites stay small.
 */
internal object AdaptiveBackoff {
    /** The state of an accelerator with no deficit, no backoff and no quarantine.  */
    const val NEUTRAL: Long = 0L

    /** Largest deficit the state can represent.  */
    val MAX_DEFICIT: Int = (1 shl 24) - 1

    private val DEFICIT_MASK = MAX_DEFICIT.toLong()
    private const val SHIFT_OFFSET = 24
    private const val RESUME_OFFSET = 32

    /** Caps the shift so that it always fits its eight bits, whatever the policy's windows are.  */
    private const val MAX_SHIFT = 30

    fun deficit(state: Long): Int {
        return (state and DEFICIT_MASK).toInt()
    }

    fun shift(state: Long): Int {
        return (state.toInt()) ushr SHIFT_OFFSET
    }

    /** Returns the first position at which the accelerator may be called again.  */
    fun resumePos(state: Long): Int {
        return (state ushr RESUME_OFFSET).toInt()
    }

    fun pack(deficit: Int, shift: Int, resumePos: Int): Long {
        return (resumePos.toLong() shl RESUME_OFFSET) or (shift.toLong() shl SHIFT_OFFSET) or deficit.toLong()
    }

    /** Returns the quarantine window for the given backoff shift.  */
    fun quarantineWindow(shift: Int, initialWindow: Int, maxWindow: Int): Int {
        return Math.min(initialWindow.toLong() shl shift, maxWindow.toLong()).toInt()
    }

    /**
     * Charges one accelerator call that moved the search from `pos` to `nextPos`.
     * 
     * @see .recordSkip
     */
    fun recordSkip(state: Long, pos: Int, nextPos: Int, textLen: Int, policy: AcceleratorPolicy): Long {
        val minSkip = policy.minProfitableSkip
        return recordSkip(
            state,
            nextPos - pos,
            nextPos,
            textLen,
            minSkip,
            policy.strikeBudget * minSkip,
            policy.initialQuarantineWindow,
            policy.maxQuarantineWindow
        )
    }

    /**
     * Charges one accelerator call that skipped `skipped` units and returned `nextPos`.
     * 
     * 
     * A skip shorter than `minSkip` adds the shortfall to the deficit. When the deficit
     * reaches `lossLimit`, acceleration is quarantined for the current window starting at
     * `nextPos`, the window doubles (up to `maxWindow`), and the deficit restarts at half
     * the limit. A longer skip repays the deficit by its excess over `minSkip`; repaying it in
     * full resets the window to `initialWindow`.
     */
    fun recordSkip(
        state: Long,
        skipped: Int,
        nextPos: Int,
        textLen: Int,
        minSkip: Int,
        lossLimit: Int,
        initialWindow: Int,
        maxWindow: Int
    ): Long {
        var deficit = deficit(state)
        if (skipped < minSkip) {
            deficit += minSkip - skipped
            if (deficit < lossLimit) {
                return (state and DEFICIT_MASK.inv()) or deficit.toLong()
            }
            return quarantine(state, nextPos, textLen, lossLimit, initialWindow, maxWindow)
        }
        if (deficit == 0) {
            return state
        }
        deficit = Math.max(0, deficit + minSkip - skipped)
        // Callers only charge calls made at or after the resume position, so a repaid deficit leaves
        // nothing to remember.
        return if (deficit == 0) NEUTRAL else (state and DEFICIT_MASK.inv()) or deficit.toLong()
    }

    private fun quarantine(
        state: Long, nextPos: Int, textLen: Int, lossLimit: Int, initialWindow: Int, maxWindow: Int
    ): Long {
        val shift = shift(state)
        val window = quarantineWindow(shift, initialWindow, maxWindow)
        if (WorkCounterConfig.ENABLED) {
            WorkCounter.recordStartQuarantine(window)
        }
        val resumePos = nextPos + Math.min(window, textLen - nextPos)
        val nextShift = if (window < maxWindow && shift < MAX_SHIFT) shift + 1 else shift
        return pack(lossLimit ushr 1, nextShift, resumePos)
    }
}
