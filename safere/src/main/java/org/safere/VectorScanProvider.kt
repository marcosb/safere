// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * SIMD scan kernels over a UTF-8 byte array.
 * 
 * 
 * **Naming.** A kernel is named `Byte` when it compares for equality, which
 * is exact for all 256 byte values, and `Ascii` when it compares for *order*, which is
 * only correct below `0x80` because `byte` is signed. So [.indexOfBytePair] and
 * [.indexOfByteTriple] accept any bytes even though today's only caller passes ASCII, while
 * [.indexOfAsciiClass] takes ranges and genuinely requires them.
 * 
 * 
 * Match the name to the comparison, not to the caller.
 */
internal interface VectorScanProvider {
    /**
     * Returns the shortest search window, in bytes, for which the `kind` kernel is expected to
     * beat the SWAR and scalar fallbacks.
     * 
     * 
     * The quantity is a *window* length, not an input length: it is measured over the
     * region the caller is about to scan, so that a narrow search inside a large input is costed as a
     * narrow search.
     */
    fun minimumWindowLength(kind: ScanKind?): Int

    /**
     * Returns the longest search window, in bytes, for which the `kind` kernel is expected to
     * beat the SWAR and scalar fallbacks. Kernels that remain profitable on arbitrarily long windows
     * return [Int.MAX_VALUE].
     */
    fun maximumWindowLength(kind: ScanKind?): Int {
        return Int.MAX_VALUE
    }

    /** Returns a match position, `-1` when absent, or [.UNSUPPORTED].  */
    fun indexOfByte(bytes: ByteArray?, offset: Int, length: Int, target: Byte, start: Int): Int {
        return UNSUPPORTED
    }

    /** Returns a match position, `-1` when absent, or [.UNSUPPORTED].  */
    fun indexOfAsciiClass(bytes: ByteArray?, offset: Int, length: Int, ranges: IntArray?, start: Int): Int

    /** Returns a match position, `-1` when absent, or [.UNSUPPORTED].  */
    fun indexOfBytePair(bytes: ByteArray?, offset: Int, length: Int, b0: Byte, b1: Byte, start: Int): Int {
        return UNSUPPORTED
    }

    /** Returns a match position, `-1` when absent, or [.UNSUPPORTED].  */
    fun indexOfByteTriple(
        bytes: ByteArray?, offset: Int, length: Int, b0: Byte, b1: Byte, b2: Byte, start: Int
    ): Int {
        return UNSUPPORTED
    }

    /** Returns a match position, `-1` when absent, or [.UNSUPPORTED].  */
    fun indexOfTeddy(bytes: ByteArray?, offset: Int, length: Int, model: Any?, start: Int): Int

    /** Returns a match position, `-1` when absent, or [.UNSUPPORTED].  */
    fun indexOfMultiLiteral(
        bytes: ByteArray?,
        offset: Int,
        length: Int,
        literals: Array<String?>?,
        anchorChars: CharArray?,
        anchorOffsets: IntArray?,
        anchorRanges: IntArray?,
        minLength: Int,
        teddyModel: Any?,
        start: Int
    ): Int

    companion object {
        val UNSUPPORTED: Int = -2
    }
}
