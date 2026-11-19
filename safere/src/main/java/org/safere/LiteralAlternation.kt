// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.nio.charset.StandardCharsets

/** Complete, ordered literal alternatives for a bounded String and UTF-8 search fast path.  */
internal class LiteralAlternation private constructor(
    private val literals: Array<String>,
    private val wholeInputRejection: Boolean,
    utf8FirstByteScan: Utf8StartAccelerator.CharClass?
) {
    // The literals are ASCII, so their UTF-8 encodings are their chars as bytes.
    private val utf8Literals: Array<ByteArray> =
        Array(literals.size) { i -> literals[i].toByteArray(StandardCharsets.US_ASCII) }
    private val utf8Failures: Array<IntArray> =
        Array(literals.size) { i -> Pattern.literalFailure(utf8Literals[i]) }

    // The first-byte scan that UTF-8 searches without this plan start with, or null when they use
    // something else; the UTF-8 search is then off.
    private val utf8FirstByteScan: Utf8StartAccelerator.CharClass? = utf8FirstByteScan

    /**
     * Returns packed start/end bounds, NO_MATCH, or FALLBACK. Only the initial search uses this plan.
     * Later searches use the existing engine, avoiding probe overhead for dense matches.
     */
    fun find(text: String, from: Int): Long {
        if (from != 0) {
            return FALLBACK
        }
        val length: Int = text.length
        if (!wholeInputRejection) {
            // Preserve locality for patterns that did not already scan the whole input for rejection.
            var start = 0
            while (start < length) {
                val limit = start + Math.min(WINDOW_LENGTH, length - start)
                val match = search(text, start, limit)
                if (match >= 0) {
                    return match
                }
                start = limit
            }
            return NO_MATCH
        }
        if (length <= PROBE_LENGTH * 4) {
            return search(text, from, length)
        }
        val match = search(text, from, PROBE_LENGTH)
        return if (match >= 0) match else searchGrowingWindows(text, PROBE_LENGTH, PROBE_LENGTH * 2)
    }

    /** Searches an opaque String region without inspecting text beyond its bounds.  */
    fun find(text: StringRegionInputScanner, from: Int): Long {
        if (from != 0) {
            return FALLBACK
        }
        val length: Int = text.length
        var start = 0
        while (start < length) {
            val limit = start + Math.min(WINDOW_LENGTH, length - start)
            var bestStart = limit
            var bestEnd = -1
            for (literal in literals) {
                val end = bestStart + Math.min(literal.length - 1, length - bestStart)
                val candidate = text.indexOf(literal, start, end)
                if (candidate >= 0 && candidate < bestStart) {
                    bestStart = candidate
                    bestEnd = candidate + literal.length
                    if (candidate == start) {
                        break
                    }
                }
            }
            if (bestEnd >= 0) {
                return (bestStart.toLong() shl 32) or bestEnd.toLong()
            }
            start = limit
        }
        return NO_MATCH
    }

    /**
     * UTF-8 counterpart to [.find], returning byte offsets. ASCII literals only
     * match ASCII bytes, so a match never splits a code point, even in malformed input.
     * 
     * 
     * Every match starts with one of the literals' first bytes, so this hops between those bytes
     * with the fallback's own first-byte scan and checks the literals at each one, without starting
     * the DFA. When the first bytes are common, that stops at almost every word, so after [ ][.DENSE_CANDIDATES] candidates within [.DENSE_CANDIDATE_SPAN] bytes it switches to the
     * String path's strategy of one bounded scan per literal. Those scans start with [ ][.PROBE_LENGTH]-byte windows, long enough for the word-at-a-time literal filter.
     */
    fun find(text: Utf8InputScanner, from: Int): Long {
        if (from != 0 || utf8FirstByteScan == null) {
            return FALLBACK
        }
        val bytes = text.bytes()
        val offset = text.offset()
        val length = text.length
        var spanStart = 0
        var candidates = 0
        var position = 0
        while (true) {
            val candidate = utf8FirstByteScan.findCandidate(text, position)
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record(((if (candidate < 0) length else candidate + 1) - position).toLong())
            }
            if (candidate < 0) {
                return NO_MATCH
            }
            val match = matchAt(bytes, offset, length, candidate)
            if (match >= 0) {
                return match
            }
            position = candidate
            if (++candidates == DENSE_CANDIDATES) {
                if (position - spanStart < DENSE_CANDIDATE_SPAN) {
                    return searchFrom(text, position + 1)
                }
                candidates = 0
                spanStart = position
            }
            position++
        }
    }

    /** Returns the bounds of the highest-priority literal starting at `position`, or -1.  */
    private fun matchAt(bytes: ByteArray?, offset: Int, length: Int, position: Int): Long {
        for (literal in utf8Literals) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record(literal.size.toLong())
            }
            if (literal.size <= length - position
                && ByteSwarScan.matchesAt(bytes!!, offset, literal, position)
            ) {
                return (position.toLong() shl 32) or (position + literal.size).toLong()
            }
        }
        return NO_MATCH
    }

    /** Searches [start, length), where no literal starts before start.  */
    private fun searchFrom(text: Utf8InputScanner, start: Int): Long {
        val length = text.length
        if (length - start <= PROBE_LENGTH * 4) {
            return search(text, start, length)
        }
        val match = search(text, start, start + PROBE_LENGTH)
        return if (match >= 0) match else searchGrowingWindows(text, start + PROBE_LENGTH, PROBE_LENGTH * 2)
    }

    private fun searchGrowingWindows(text: Utf8InputScanner, start: Int, windowLength: Int): Long {
        var start = start
        var windowLength = windowLength
        val length = text.length
        while (start < length) {
            val remaining = length - start
            val width = Math.min(windowLength, remaining)
            val limit = if (remaining - width <= PROBE_LENGTH * 4) length else start + width
            val match = search(text, start, limit)
            if (match >= 0) {
                return match
            }
            start = limit
            windowLength = Math.min(2L * windowLength, (length - start).toLong()).toInt()
        }
        return NO_MATCH
    }

    /** Search literal starts in [from, limit), including literals crossing the upper boundary.  */
    private fun search(text: Utf8InputScanner, from: Int, limit: Int): Long {
        var bestStart = limit
        var bestEnd = -1
        for (i in utf8Literals.indices) {
            val literal = utf8Literals[i]
            // indexOfWithin charges WorkCounter for the bytes it scans.
            val idx = text.indexOfWithin(literal, utf8Failures[i], from, bestStart - 1)
            if (idx >= 0) {
                bestStart = idx
                bestEnd = idx + literal.size
                // Alternatives are visited in priority order; no later alternative can improve this.
                if (idx == from) {
                    break
                }
            }
        }
        return if (bestEnd < 0) NO_MATCH else (bestStart.toLong() shl 32) or bestEnd.toLong()
    }

    private fun searchGrowingWindows(text: String, start: Int, windowLength: Int): Long {
        var start = start
        var windowLength = windowLength
        val length: Int = text.length
        while (start < length) {
            val remaining = length - start
            val width = Math.min(windowLength, remaining)
            // Combine a small final remainder with this window, avoiding another range search.
            val limit = if (remaining - width <= PROBE_LENGTH * 4) length else start + width
            val match = search(text, start, limit)
            if (match >= 0) {
                return match
            }
            start = limit
            // Candidate-start ranges do not overlap. search() includes the literal-length overlap
            // needed for crossing matches; long arithmetic keeps growth safe at String's size limit.
            windowLength = Math.min(2L * windowLength, (length - start).toLong()).toInt()
        }
        return NO_MATCH
    }

    /** Search literal starts in [from, limit), including literals crossing the upper boundary.  */
    private fun search(text: String, from: Int, limit: Int): Long {
        var bestStart = limit
        var bestEnd = -1
        val length: Int = text.length
        for (literal in literals) {
            val end = bestStart + Math.min(literal.length - 1, length - bestStart)
            val idx = run {
                var hit = -1
                var i = from
                val lastStart = if (end == length) length - literal.length else end - 1
                while (i <= lastStart && i <= length - literal.length) {
                    if (text.startsWith(literal, i)) {
                        hit = i
                        break
                    }
                    i++
                }
                hit
            }
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record((if (idx >= 0) idx - from + literal.length else end - from).toLong())
            }
            if (idx >= 0 && idx < bestStart) {
                bestStart = idx
                bestEnd = idx + literal.length
                // Alternatives are visited in priority order; no later alternative can improve this.
                if (idx == from) {
                    break
                }
            }
        }
        return if (bestEnd < 0) NO_MATCH else (bestStart.toLong() shl 32) or bestEnd.toLong()
    }

    companion object {
        val NO_MATCH: Long = -1
        val FALLBACK: Long = -2
        private const val PROBE_LENGTH = 1024
        private const val MAX_ALTERNATIVES = 8
        private const val WINDOW_LENGTH = 256
        private const val MAX_LITERAL_LENGTH = 12

        // The UTF-8 search verifies first-byte candidates one at a time until it sees this many within
        // DENSE_CANDIDATE_SPAN bytes, then switches to one bounded scan per literal.
        private const val DENSE_CANDIDATES = 8
        private const val DENSE_CANDIDATE_SPAN = 256

        /**
         * Caller must have ruled out explicit captures. `utf8FirstByteScan` is the UTF-8 start
         * accelerator when it scans for the literals' first bytes, or null. Only then is the UTF-8 search
         * used: otherwise UTF-8 searches use either a vector multi-literal search, which is faster, or
         * the DFA from the start, which with several first bytes is about as fast as several literal
         * scans.
         */
        fun compile(
            ast: Regexp, wholeInputRejection: Boolean, utf8FirstByteScan: Utf8StartAccelerator.CharClass?
        ): LiteralAlternation? {
            var ast = ast
            while (ast.op == RegexpOp.NON_CAPTURE || (ast.op == RegexpOp.CAPTURE && ast.cap == 0)) {
                ast = ast.sub()!!
            }
            if (ast.op != RegexpOp.ALTERNATE || ast.nsub() < 2 || ast.nsub() > MAX_ALTERNATIVES) {
                return null
            }
            val literals: Array<String?> = arrayOfNulls<String>(ast.nsub())
            for (i in literals.indices) {
                literals[i] = MultiAnchorCompiler.extractExactAsciiLiteral(ast.subs!!.get(i))
                if (literals[i] == null) {
                    return null
                }
                // indexOf can repeatedly compare long near-matching prefixes. Even a whole-tail rejection
                // filter may stop after its first successful alternative, whereas exact bounds require us
                // to inspect later alternatives too. MAX_LITERAL_LENGTH * MAX_ALTERNATIVES bounds the
                // comparison work per input position, keeping indexOf's O(n * m) worst case linear in n.
                // WorkCounter charges distance, not character comparisons, so it relies on these caps.
                // Keep the existing engine for longer needles.
                if (literals[i]!!.length > MAX_LITERAL_LENGTH) {
                    return null
                }
            }
            return LiteralAlternation(literals.requireNoNulls(), wholeInputRejection, utf8FirstByteScan)
        }
    }
}
