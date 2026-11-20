// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere



import kotlin.math.max

/**
 * Bounds checking and work-budget accounting for candidate-filtering string search kernels.
 * 
 * 
 * Enforces SafeRE's linear-time guarantee by bounding candidate verification work to `2 * remaining` bytes, falling back to linear Knuth-Morris-Pratt when false-candidate density exceeds
 * the budget.
 */
internal object WorkLimit {
    /** Computes the maximum verification work allowed for a search over `remaining` bytes.  */
    fun forRemaining(remaining: Int): Long {
        return max(1L, remaining.toLong() * 2)
    }

    /** Checks whether candidate work has exhausted the budget.  */
    fun isExhausted(work: Long, workLimit: Long): Boolean {
        return work >= workLimit
    }

    /** Accumulates verification work and returns the updated total.  */
    fun addCandidateWork(currentWork: Long, candidateCount: Int, matchLength: Int): Long {
        return currentWork + candidateCount.toLong() * matchLength + Long.SIZE_BYTES
    }

    /**
     * Checks whether a candidate match starting at `candidate` is within search bounds.
     * Overflow-safe for inputs approaching [Int.MAX_VALUE].
     */
    fun candidateInBounds(candidate: Int, start: Int, length: Int, matchLength: Int): Boolean {
        return candidate >= start && candidate <= length - matchLength
    }
}
