// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.safere.Utf8InputScanner

/** Benchmark-only access to the current UTF-8 scanner implementation.  */
class Utf8ScannerBenchmarkAccess(bytes: ByteArray?, offset: Int, length: Int, ranges: IntArray) {
    private val scanner: Utf8InputScanner
    private val ranges: IntArray?
    private val bitmap0: Long
    private val bitmap1: Long

    /** Creates a scanner over the requested borrowed-array window and code-point ranges.  */
    init {
        scanner = Utf8InputScanner(bytes, offset, length)
        this.ranges = ranges
        bitmap0 = org.safere.Utf8ScannerBenchmarkAccess.Companion.asciiBitmap(ranges, 0, 63)
        bitmap1 = org.safere.Utf8ScannerBenchmarkAccess.Companion.asciiBitmap(ranges, 64, 127)
    }

    /** Returns the first byte position in the configured code-point ranges.  */
    fun indexOfCodePointClass(start: Int): Int {
        return scanner.indexOfCodePointClass(ranges, bitmap0, bitmap1, start, scanner.length())
    }

    companion object {
        private fun asciiBitmap(ranges: IntArray, first: Int, last: Int): Long {
            var bitmap: Long = 0
            var index = 0
            while (index < ranges.length) {
                val low: Int = Math.max(first, ranges[index])
                val high: Int = Math.min(last, ranges[index + 1])
                for (value in low..high) {
                    bitmap = bitmap or (1L shl (value - first))
                }
                index += 2
            }
            return bitmap
        }
    }
}
