// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere



import kotlin.math.*
/** Inclusive scan limits in bytes, relative to the logical input slice.  */
internal object ByteScanBounds {
    /** Last start at which a match of `matchLen` fits; negative if no match fits.  */
    fun lastMatchStart(length: Int, matchLen: Int): Int {
        return length - matchLen
    }

    /** Last start at which a `loadBytes`-wide load at `+anchorOffset` fits.  */
    fun lastLoadStart(length: Int, anchorOffset: Int, loadBytes: Int): Int {
        return length - anchorOffset - loadBytes
    }

    /**
     * Last start at which both the widest anchor load and the full match fit.
     * 
     * 
     * This bounds the match at the scan start. Candidates in later lanes still need their own
     * full-match bounds checks.
     */
    fun lastWideScanStart(length: Int, matchLen: Int, maxAnchorOffset: Int, loadBytes: Int): Int {
        return min(
            lastLoadStart(length, maxAnchorOffset, loadBytes), lastMatchStart(length, matchLen)
        )
    }
}
