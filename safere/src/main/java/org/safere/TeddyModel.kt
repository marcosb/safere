// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.io.Serializable
import java.util.*

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
    lutLo: ByteArray?,
    lutHi: ByteArray?,
    lutLo1: ByteArray?,
    lutHi1: ByteArray?,
    lutLo2: ByteArray?,
    lutHi2: ByteArray?,
    literals: Array<String?>?,
    literalBuckets: IntArray?,
    minLength: Int,
    is2Byte: Boolean,
    is3Byte: Boolean
) : Serializable {
    private val lutLo: ByteArray?
    private val lutHi: ByteArray?
    private val lutLo1: ByteArray?
    private val lutHi1: ByteArray?
    private val lutLo2: ByteArray?
    private val lutHi2: ByteArray?
    private val literals: Array<String?>?
    private val literalBuckets: IntArray?
    private val minLength: Int
    private val is2Byte: Boolean
    private val is3Byte: Boolean

    init {
        this.lutLo = lutLo
        this.lutHi = lutHi
        this.lutLo1 = lutLo1
        this.lutHi1 = lutHi1
        this.lutLo2 = lutLo2
        this.lutHi2 = lutHi2
        this.literals = literals
        this.literalBuckets = literalBuckets
        this.minLength = minLength
        this.is2Byte = is2Byte
        this.is3Byte = is3Byte
    }

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
            if (literals == null || literals.length < 2 || literals.length > 32) {
                return null
            }
            var minLen = Integer.MAX_VALUE
            for (lit in literals) {
                if (lit == null || lit.isEmpty()) {
                    return null
                }
                /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
                minLen = Math.min(minLen, lit.length())
            }

            val numBuckets: Int = Math.min(literals.length, MAX_BUCKETS)
            val literalBuckets = IntArray(literals.length)
            val baseLutLo0 = ByteArray(NIBBLE_TABLE_SIZE)
            val baseLutHi0 = ByteArray(NIBBLE_TABLE_SIZE)

            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */

            val is2Byte = minLen >= 2
            val baseLutLo1 = if (is2Byte) ByteArray(NIBBLE_TABLE_SIZE) else null
            val baseLutHi1 = if (is2Byte) ByteArray(NIBBLE_TABLE_SIZE) else null

            if (is2Byte) {
                /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            }

            val is3Byte = minLen >= 3
            val baseLutLo2 = if (is3Byte) ByteArray(NIBBLE_TABLE_SIZE) else null
            val baseLutHi2 = if (is3Byte) ByteArray(NIBBLE_TABLE_SIZE) else null

            if (is3Byte) {
                /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            }

            val tableSize = Math.max(64, Math.max(NIBBLE_TABLE_SIZE, vectorLength))
            val repeatedLo0: ByteArray = repeatTable(baseLutLo0, tableSize)
            val repeatedHi0: ByteArray = repeatTable(baseLutHi0, tableSize)
            val repeatedLo1: ByteArray? = if (is2Byte) Companion.repeatTable(baseLutLo1!!, tableSize) else null
            val repeatedHi1: ByteArray? = if (is2Byte) Companion.repeatTable(baseLutHi1!!, tableSize) else null
            val repeatedLo2: ByteArray? = if (is3Byte) Companion.repeatTable(baseLutLo2!!, tableSize) else null
            val repeatedHi2: ByteArray? = if (is3Byte) Companion.repeatTable(baseLutHi2!!, tableSize) else null

            return TeddyModel(
                repeatedLo0,
                repeatedHi0,
                repeatedLo1,
                repeatedHi1,
                repeatedLo2,
                repeatedHi2,
                Arrays.copyOf<String?>(literals, literals.length),
                literalBuckets,
                minLen,
                is2Byte,
                is3Byte
            )
        }

        private fun repeatTable(base16: ByteArray, totalSize: Int): ByteArray {
            val result = ByteArray(totalSize)
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return result
        }
    }
}
