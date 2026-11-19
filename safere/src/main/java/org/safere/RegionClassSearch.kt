// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*

/** Scalar-class existence checks for opaque grapheme searches, in original-input coordinates.  */
internal class RegionClassSearch {
    // At most one entry per required class in the compiled pattern. Region changes retain entries;
    // input and pattern changes discard this object. Ordinary String scanners carry no memo state.
    private var scans: IdentityHashMap<RejectPrefilter.CharClass?, Scan?>? = null

    private class Scan {
        var start: Int = 0
        var stop: Int = 0
        var hit: Boolean = false
    }

    fun contains(cc: RejectPrefilter.CharClass, input: InputScanner, start: Int, end: Int): Boolean {
        if (start >= end) {
            return false
        }
        val first = input.decodeForward(start, end)
        if (contains(cc, InputScanner.Companion.codePoint(first))) {
            return true
        }
        // Existence does not require the leftmost occurrence. Probe the terminal code point before
        // scanning: an exposed high surrogate is a witness even when an early NFA match uses a low
        // half of a pair. This avoids delaying that first result with a scan of the entire region.
        val last = input.decodeBackward(end, start)
        if (contains(cc, InputScanner.Companion.codePoint(last))) {
            return true
        }
        var from: Int = InputScanner.Companion.position(first)
        val limit: Int = InputScanner.Companion.position(last)
        if (from >= limit) {
            return false
        }
        if (scans == null) {
            scans = IdentityHashMap<RejectPrefilter.CharClass?, Scan?>()
        }
        var scan = scans!!.get(cc)
        // The probed edge code points are excluded from the memo. Its interior scalar boundaries
        // remain valid when a later region exposes a different surrogate half at either edge.
        // A scan records an absent interval [start, stop), optionally followed by a hit at stop.
        if (scan != null && from < scan.start && limit > scan.start) {
            // Extend an overlapping absent interval leftward before reusing its tail. Both endpoints
            // are full-input scalar boundaries, so region-local surrogate decoding cannot change it.
            var pos = from
            while (pos < scan.start) {
                val decoded = input.decodeForward(pos, scan.start)
                if (contains(cc, InputScanner.Companion.codePoint(decoded))) {
                    scan.start = from
                    scan.stop = pos
                    scan.hit = true
                    return true
                }
                pos = InputScanner.Companion.position(decoded)
            }
            scan.start = from
        }
        var scanStart = from
        if (scan != null && from >= scan.start && from <= scan.stop) {
            if (limit <= scan.stop) {
                return false
            }
            if (scan.hit) {
                return true
            }
            scanStart = scan.start
            from = scan.stop
        }
        if (scan == null) {
            scan = Scan()
            scans!!.put(cc, scan)
        }
        scan.start = scanStart
        scan.stop = limit
        scan.hit = false
        var pos = from
        while (pos < limit) {
            val decoded = input.decodeForward(pos, limit)
            if (contains(cc, InputScanner.Companion.codePoint(decoded))) {
                scan.stop = pos
                scan.hit = true
                return true
            }
            pos = InputScanner.Companion.position(decoded)
        }
        return false
    }

    companion object {
        private fun contains(cc: RejectPrefilter.CharClass, codePoint: Int): Boolean {
            if (WorkCounterConfig.ENABLED) {
                record()
            }
            return InputScanner.Companion.classContains(cc.ranges, cc.bitmap0, cc.bitmap1, codePoint)
        }
    }
}
