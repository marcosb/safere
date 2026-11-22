// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/** Experimental scan operations implemented with the incubating Vector API.  */
internal class IncubatorVectorScanProvider : VectorScanProvider {
    override fun minimumWindowLength(kind: ScanKind?): Int {
        return when (kind) {
            ScanKind.BYTE -> MINIMUM_BYTE_WINDOW_LENGTH
            ScanKind.PAIR -> MINIMUM_PAIR_WINDOW_LENGTH
            ScanKind.TRIPLE -> MINIMUM_TRIPLE_WINDOW_LENGTH
            ScanKind.CLASS -> MINIMUM_CLASS_WINDOW_LENGTH
            ScanKind.IGNORE_CASE -> MINIMUM_IGNORE_CASE_WINDOW_LENGTH
            ScanKind.TEDDY -> MINIMUM_TEDDY_WINDOW_LENGTH
            ScanKind.MULTI_LITERAL -> MINIMUM_MULTI_LITERAL_WINDOW_LENGTH
            null -> 0
            else -> 0
        }
    }

    override fun maximumWindowLength(kind: ScanKind?): Int {
        // TRIPLE is the only kind with a measured upper crossover: past this window the SWAR triple
        // scan wins again. Every other kernel stays profitable for arbitrarily long windows.
        return if (kind == ScanKind.TRIPLE) MAXIMUM_TRIPLE_WINDOW_LENGTH else Int.MAX_VALUE
    }

    override fun indexOfByte(bytes: ByteArray?, offset: Int, length: Int, target: Byte, start: Int): Int {
        return ByteVectorScan.indexOfByte(bytes!!, offset, length, target, start)
    }

    override fun indexOfAsciiClass(bytes: ByteArray?, offset: Int, length: Int, ranges: IntArray?, start: Int): Int {
        return ByteVectorScan.indexOfAsciiClass(bytes!!, offset, length, ranges!!, start)
    }

    override fun indexOfBytePair(bytes: ByteArray?, offset: Int, length: Int, b0: Byte, b1: Byte, start: Int): Int {
        return ByteVectorScan.indexOfBytePair(bytes!!, offset, length, b0, b1, start)
    }

    override fun indexOfByteTriple(
        bytes: ByteArray?, offset: Int, length: Int, b0: Byte, b1: Byte, b2: Byte, start: Int
    ): Int {
        return ByteVectorScan.indexOfByteTriple(bytes!!, offset, length, b0, b1, b2, start)
    }

    override fun indexOfTeddy(bytes: ByteArray?, offset: Int, length: Int, model: Any?, start: Int): Int {
        return TeddyVectorScan.indexOfTeddyUtf8(bytes!!, offset, length, model as TeddyModel, start)
    }

    override fun indexOfMultiLiteral(
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
    ): Int {
        return ByteVectorScan.indexOfMultiLiteral(
            bytes!!,
            offset,
            length,
            literals!!.requireNoNulls(),
            anchorChars!!,
            anchorOffsets!!,
            anchorRanges!!,
            minLength,
            teddyModel as TeddyModel?,
            start
        )
    }

    override fun indexOfIgnoreCase(
        bytes: ByteArray?,
        offset: Int,
        length: Int,
        prefix: String?,
        prefixLen: Int,
        anchorOffset: Int,
        low: Byte,
        high: Byte,
        start: Int,
    ): Int {
        return ByteVectorScan.indexOfIgnoreCase(
            bytes!!, offset, length, prefix!!, prefixLen, anchorOffset, low, high, start
        )
    }

    override fun indexOfPairIgnoreCase(
        bytes: ByteArray?,
        offset: Int,
        length: Int,
        prefix: String?,
        prefixLen: Int,
        offset1: Int,
        low1: Byte,
        high1: Byte,
        offset2: Int,
        low2: Byte,
        high2: Byte,
        start: Int,
    ): Int {
        return ByteVectorScan.indexOfPairIgnoreCase(
            bytes!!,
            offset,
            length,
            prefix!!,
            prefixLen,
            offset1,
            low1,
            high1,
            offset2,
            low2,
            high2,
            start,
        )
    }

    companion object {
        private const val MINIMUM_BYTE_WINDOW_LENGTH = 64
        private const val MINIMUM_PAIR_WINDOW_LENGTH = 64
        private const val MINIMUM_TRIPLE_WINDOW_LENGTH = 64
        private const val MAXIMUM_TRIPLE_WINDOW_LENGTH = 10240
        private const val MINIMUM_CLASS_WINDOW_LENGTH = 1024
        private const val MINIMUM_IGNORE_CASE_WINDOW_LENGTH = 1024
        private const val MINIMUM_TEDDY_WINDOW_LENGTH = 1024
        private const val MINIMUM_MULTI_LITERAL_WINDOW_LENGTH = 64
    }
}
