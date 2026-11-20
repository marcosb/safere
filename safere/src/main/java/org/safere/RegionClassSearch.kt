// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/** Scalar-class existence checks for opaque grapheme searches, in original-input coordinates.  */
internal class RegionClassSearch {
  // At most one entry per required class in the compiled pattern. Region changes retain entries;
  // input and pattern changes discard this object. Ordinary String scanners carry no memo state.
  // Identity keyed: CharClassMembership implementors may be data classes with value equals.
  private var scanKeys: ArrayList<CharClassMembership>? = null
  private var scanValues: ArrayList<Scan>? = null

  private class Scan {
    var start: Int = 0
    var stop: Int = 0
    var hit: Boolean = false
  }

  fun contains(cc: CharClassMembership, input: InputScanner, start: Int, end: Int): Boolean {
    if (start >= end) {
      return false
    }
    val first = input.decodeForward(start, end)
    if (contains(cc, InputScanner.codePoint(first))) {
      return true
    }
    // Existence does not require the leftmost occurrence. Probe the terminal code point before
    // scanning: an exposed high surrogate is a witness even when an early NFA match uses a low
    // half of a pair. This avoids delaying that first result with a scan of the entire region.
    val last = input.decodeBackward(end, start)
    if (contains(cc, InputScanner.codePoint(last))) {
      return true
    }
    var from: Int = InputScanner.position(first)
    val limit: Int = InputScanner.position(last)
    if (from >= limit) {
      return false
    }
    if (scanKeys == null) {
      scanKeys = ArrayList()
      scanValues = ArrayList()
    }
    var scan = identityGet(cc)
    // The probed edge code points are excluded from the memo. Its interior scalar boundaries
    // remain valid when a later region exposes a different surrogate half at either edge.
    // A scan records an absent interval [start, stop), optionally followed by a hit at stop.
    if (scan != null && from < scan.start && limit > scan.start) {
      // Extend an overlapping absent interval leftward before reusing its tail. Both endpoints
      // are full-input scalar boundaries, so region-local surrogate decoding cannot change it.
      var pos = from
      while (pos < scan.start) {
        val decoded = input.decodeForward(pos, scan.start)
        if (contains(cc, InputScanner.codePoint(decoded))) {
          scan.start = from
          scan.stop = pos
          scan.hit = true
          return true
        }
        pos = InputScanner.position(decoded)
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
      identityPut(cc, scan)
    }
    scan.start = scanStart
    scan.stop = limit
    scan.hit = false
    var pos = from
    while (pos < limit) {
      val decoded = input.decodeForward(pos, limit)
      if (contains(cc, InputScanner.codePoint(decoded))) {
        scan.stop = pos
        scan.hit = true
        return true
      }
      pos = InputScanner.position(decoded)
    }
    return false
  }

  private fun identityGet(key: CharClassMembership): Scan? {
    val keys = scanKeys!!
    for (i in keys.indices) {
      if (keys[i] === key) {
        return scanValues!![i]
      }
    }
    return null
  }

  private fun identityPut(key: CharClassMembership, value: Scan) {
    val keys = scanKeys!!
    for (i in keys.indices) {
      if (keys[i] === key) {
        scanValues!![i] = value
        return
      }
    }
    keys.add(key)
    scanValues!!.add(value)
  }

  companion object {
    private fun contains(cc: CharClassMembership, codePoint: Int): Boolean {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record()
      }
      return InputScanner.classContains(cc.ranges!!, cc.bitmap0, cc.bitmap1, codePoint)
    }
  }
}
