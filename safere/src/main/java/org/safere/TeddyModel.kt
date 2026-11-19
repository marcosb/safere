// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.io.Serializable
import java.util.*
import kotlin.math.*
/**
 * Immutable precomputed lookup tables and metadata for Teddy SIMD vector-shuffle multi-literal
 * acceleration.
 * 
 * 
 * Teddy compresses up to 32 literal search patterns into parallel 16-byte nibble lookup tables,
 * enabling simultaneous multi-keyword matching in 3 SIMD instructions (2 `rearrange` + 1
 * `and`) on `ByteVector`.
 */
internal class TeddyModel private constructor(
    private val lutLo: ByteArray?,
    private val lutHi: ByteArray?,
    private val lutLo1: ByteArray?,
    private val lutHi1: ByteArray?,
    private val lutLo2: ByteArray?,
    private val lutHi2: ByteArray?,
    private val literals: Array<String?>?,
    private val literalBuckets: IntArray?,
    private val minLength: Int,
    private val is2Byte: Boolean,
    private val is3Byte: Boolean,
) : Serializable {
    fun lutLo(): ByteArray? {
        return lutLo
    }

    fun lutHi(): ByteArray? {
        return lutHi
    }

    fun lutLo1(): ByteArray? {
        return lutLo1
    }

    fun lutHi1(): ByteArray? {
        return lutHi1
    }

    fun lutLo2(): ByteArray? {
        return lutLo2
    }

    fun lutHi2(): ByteArray? {
        return lutHi2
    }

    fun literals(): Array<String?>? {
        return literals
    }

    fun literalBuckets(): IntArray? {
        return literalBuckets
    }

    fun minLength(): Int {
        return minLength
    }

    fun is2Byte(): Boolean {
        return is2Byte
    }

    fun is3Byte(): Boolean {
        return is3Byte
    }

    companion object {
        private const val serialVersionUID = 1L

        private const val MAX_BUCKETS = 8
        private const val NIBBLE_TABLE_SIZE = 16

        /** Compiles a Teddy model only when the optional vector provider is active.  */
        fun compileForSelectedProvider(literals: Array<String?>?): TeddyModel? {
            return if (VectorScanProviders.vectorProviderAvailable()) compile(literals, 64) else null
        }

        /**
         * Compiles up to 32 ASCII literal keywords into a [TeddyModel].
         * 
         * @param literals the array of literal keywords
         * @param vectorLength the preferred vector lane width (16, 32, or 64 bytes)
         * @return the compiled model, or `null` if inputs are ineligible for Teddy
         */
        fun compile(literals: Array<String?>?, vectorLength: Int): TeddyModel? {
            if (literals == null || literals.size < 2 || literals.size > 32) {
                return null
            }
            var minLen = Int.MAX_VALUE
            for (lit in literals) {
                if (lit == null || lit.isEmpty()) {
                    return null
                }
                for (i in 0 until lit.length) {
                    if (lit[i].code > 127) {
                        return null
                    }
                }
                minLen = min(minLen, lit.length)
            }

            val numBuckets: Int = min(literals.size, MAX_BUCKETS)
            val literalBuckets = IntArray(literals.size)
            val baseLutLo0 = ByteArray(NIBBLE_TABLE_SIZE)
            val baseLutHi0 = ByteArray(NIBBLE_TABLE_SIZE)

            for (i in 0 until literals.size) {
                val bucket = i % numBuckets
                literalBuckets[i] = bucket
                val mask = (1 shl bucket).toByte()

                val c0: Char = literals[i]!![0]
                val lo0 = c0.code and 0x0F
                val hi0 = (c0.code shr 4) and 0x0F
                baseLutLo0[lo0] = (baseLutLo0[lo0].toInt() or mask.toInt()).toByte()
                baseLutHi0[hi0] = (baseLutHi0[hi0].toInt() or mask.toInt()).toByte()
            }

            val is2Byte = minLen >= 2
            val baseLutLo1 = if (is2Byte) ByteArray(NIBBLE_TABLE_SIZE) else null
            val baseLutHi1 = if (is2Byte) ByteArray(NIBBLE_TABLE_SIZE) else null

            if (is2Byte) {
                for (i in 0 until literals.size) {
                    val bucket = literalBuckets[i]
                    val mask = (1 shl bucket).toByte()

                    val c1: Char = literals[i]!![1]
                    val lo1 = c1.code and 0x0F
                    val hi1 = (c1.code shr 4) and 0x0F
                    baseLutLo1!![lo1] = (baseLutLo1[lo1].toInt() or mask.toInt()).toByte()
                    baseLutHi1!![hi1] = (baseLutHi1[hi1].toInt() or mask.toInt()).toByte()
                }
            }

            val is3Byte = minLen >= 3
            val baseLutLo2 = if (is3Byte) ByteArray(NIBBLE_TABLE_SIZE) else null
            val baseLutHi2 = if (is3Byte) ByteArray(NIBBLE_TABLE_SIZE) else null

            if (is3Byte) {
                for (i in 0 until literals.size) {
                    val bucket = literalBuckets[i]
                    val mask = (1 shl bucket).toByte()

                    val c2: Char = literals[i]!![2]
                    val lo2 = c2.code and 0x0F
                    val hi2 = (c2.code shr 4) and 0x0F
                    baseLutLo2!![lo2] = (baseLutLo2[lo2].toInt() or mask.toInt()).toByte()
                    baseLutHi2!![hi2] = (baseLutHi2[hi2].toInt() or mask.toInt()).toByte()
                }
            }

            val tableSize = max(64, max(NIBBLE_TABLE_SIZE, vectorLength))
            val repeatedLo0: ByteArray = repeatTable(baseLutLo0, tableSize)
            val repeatedHi0: ByteArray = repeatTable(baseLutHi0, tableSize)
            val repeatedLo1: ByteArray? = if (is2Byte) repeatTable(baseLutLo1!!, tableSize) else null
            val repeatedHi1: ByteArray? = if (is2Byte) repeatTable(baseLutHi1!!, tableSize) else null
            val repeatedLo2: ByteArray? = if (is3Byte) repeatTable(baseLutLo2!!, tableSize) else null
            val repeatedHi2: ByteArray? = if (is3Byte) repeatTable(baseLutHi2!!, tableSize) else null

            return TeddyModel(
                repeatedLo0,
                repeatedHi0,
                repeatedLo1,
                repeatedHi1,
                repeatedLo2,
                repeatedHi2,
                Arrays.copyOf<String?>(literals, literals.size),
                literalBuckets,
                minLen,
                is2Byte,
                is3Byte
            )
        }

        private fun repeatTable(base16: ByteArray, totalSize: Int): ByteArray {
            val result = ByteArray(totalSize)
            for (i in 0 until totalSize step NIBBLE_TABLE_SIZE) {
                base16.copyInto(result, i, 0, 0 + min(NIBBLE_TABLE_SIZE, totalSize - i))
            }
            return result
        }
    }
}
