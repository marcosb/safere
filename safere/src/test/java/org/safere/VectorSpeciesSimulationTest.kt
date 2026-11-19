// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.util.List

@DisabledForCrosscheck("implementation test uses package-private Vector API internals")
internal class VectorSpeciesSimulationTest {
    @JvmRecord
    private data class SpeciesHandle(val value: Any?)

    @Test
    fun asciiClassMatchingAcrossAllByteVectorSpecies() {
        Assumptions.assumeTrue(VECTOR_AVAILABLE, "Vector API not available on module path")
        val ranges = intArrayOf('0'.code, '9'.code, 'a'.code, 'z'.code)
        for (species in BYTE_SPECIES) {
            for (len in 0..256) {
                val absent = "A".repeat(len).toByteArray(StandardCharsets.UTF_8)
                for (start in 0..len) {
                    Assertions.assertThat(invokeByteIndexOfAsciiClass(species, absent, 0, len, ranges, start))
                        .`as`("Absent for species %s, len %d, start %d", species, len, start)
                        .isEqualTo(-1)
                }

                if (len > 0) {
                    // Match at start
                    val matchStart = absent.clone()
                    matchStart[0] = '5'.code.toByte()
                    Assertions.assertThat(invokeByteIndexOfAsciiClass(species, matchStart, 0, len, ranges, 0))
                        .`as`("Match start for species %s, len %d", species, len)
                        .isEqualTo(0)

                    // Match in middle
                    val mid = len / 2
                    val matchMid = absent.clone()
                    matchMid[mid] = 'b'.code.toByte()
                    Assertions.assertThat(invokeByteIndexOfAsciiClass(species, matchMid, 0, len, ranges, 0))
                        .`as`("Match mid for species %s, len %d, mid %d", species, len, mid)
                        .isEqualTo(mid)

                    // Match at end
                    val matchEnd = absent.clone()
                    matchEnd[len - 1] = 'z'.code.toByte()
                    Assertions.assertThat(invokeByteIndexOfAsciiClass(species, matchEnd, 0, len, ranges, 0))
                        .`as`("Match end for species %s, len %d", species, len)
                        .isEqualTo(len - 1)
                }
            }
        }
    }

    @Test
    fun ignoreCaseMatchingAcrossAllByteVectorSpecies() {
        Assumptions.assumeTrue(VECTOR_AVAILABLE, "Vector API not available on module path")
        val prefix = "needle"
        val prefixLen = prefix.length
        val anchorOffset = 0
        val low = 'n'.code.toByte()
        val high = 'N'.code.toByte()

        for (species in BYTE_SPECIES) {
            for (pad in 0..128) {
                val base = "x".repeat(pad) + "NeEdLe" + "y".repeat(64)
                val bytes = base.toByteArray(StandardCharsets.UTF_8)

                Assertions.assertThat(
                    invokeByteIndexOfIgnoreCase(
                        species, bytes, 0, bytes.size, prefix, prefixLen, anchorOffset, low, high, 0
                    )
                )
                    .`as`("IgnoreCase match for species %s, pad %d", species, pad)
                    .isEqualTo(pad)

                val absent = "x".repeat(pad + 64).toByteArray(StandardCharsets.UTF_8)
                Assertions.assertThat(
                    invokeByteIndexOfIgnoreCase(
                        species,
                        absent,
                        0,
                        absent.size,
                        prefix,
                        prefixLen,
                        anchorOffset,
                        low,
                        high,
                        0
                    )
                )
                    .`as`("IgnoreCase absent for species %s, pad %d", species, pad)
                    .isEqualTo(-1)
            }
        }
    }

    @Test
    fun charClassMatchingAcrossAllShortVectorSpecies() {
        Assumptions.assumeTrue(VECTOR_AVAILABLE, "Vector API not available on module path")
        val ranges = intArrayOf('0'.code, '9'.code, 'A'.code, 'Z'.code)
        for (species in SHORT_SPECIES) {
            for (len in 0..128) {
                val chars = "a".repeat(len).toCharArray()
                for (start in 0..len) {
                    Assertions.assertThat(invokeShortIndexOfCharClass(species, chars, 0, len, ranges, start))
                        .`as`("Short absent for species %s, len %d, start %d", species, len, start)
                        .isEqualTo(-1)
                }

                if (len > 0) {
                    val matchEnd = chars.clone()
                    matchEnd[len - 1] = '9'
                    Assertions.assertThat(invokeShortIndexOfCharClass(species, matchEnd, 0, len, ranges, 0))
                        .`as`("Short end match for species %s, len %d", species, len)
                        .isEqualTo(len - 1)
                }
            }
        }
    }

    companion object {
        private val VECTOR_AVAILABLE: Boolean = isVectorApiAvailable
        private val BYTE_SPECIES: MutableList<SpeciesHandle> =
            if (VECTOR_AVAILABLE) getSpeciesList("jdk.incubator.vector.ByteVector") else mutableListOf<SpeciesHandle?>()
        private val SHORT_SPECIES: MutableList<SpeciesHandle> =
            if (VECTOR_AVAILABLE) getSpeciesList("jdk.incubator.vector.ShortVector") else mutableListOf<SpeciesHandle?>()
        private val BYTE_INDEX_OF_ASCII_CLASS: Method? = if (VECTOR_AVAILABLE) resolveByteIndexOfAsciiClass() else null
        private val BYTE_INDEX_OF_IGNORE_CASE: Method? = if (VECTOR_AVAILABLE) resolveByteIndexOfIgnoreCase() else null
        private val SHORT_INDEX_OF_CHAR_CLASS: Method? = if (VECTOR_AVAILABLE) resolveShortIndexOfCharClass() else null

        private val isVectorApiAvailable: Boolean
            get() {
                try {
                    Class.forName("jdk.incubator.vector.ByteVector")
                    return true
                } catch (e: ClassNotFoundException) {
                    return false
                }
            }

        private fun getSpeciesList(vectorClassName: String?): MutableList<SpeciesHandle> {
            try {
                val vectorClass = Class.forName(vectorClassName)
                return List.of<SpeciesHandle?>(
                    SpeciesHandle(vectorClass.getField("SPECIES_64").get(null)),
                    SpeciesHandle(vectorClass.getField("SPECIES_128").get(null)),
                    SpeciesHandle(vectorClass.getField("SPECIES_256").get(null)),
                    SpeciesHandle(vectorClass.getField("SPECIES_512").get(null))
                )
            } catch (e: ReflectiveOperationException) {
                throw ExceptionInInitializerError(e)
            } catch (e: LinkageError) {
                throw ExceptionInInitializerError(e)
            }
        }

        private fun resolveByteIndexOfAsciiClass(): Method {
            try {
                val speciesClass = Class.forName("jdk.incubator.vector.VectorSpecies")
                val m =
                    ByteVectorScan::class.java.getDeclaredMethod(
                        "indexOfAsciiClass",
                        speciesClass,
                        ByteArray::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        IntArray::class.java,
                        Int::class.javaPrimitiveType
                    )
                m.setAccessible(true)
                return m
            } catch (e: ReflectiveOperationException) {
                throw ExceptionInInitializerError(e)
            } catch (e: LinkageError) {
                throw ExceptionInInitializerError(e)
            }
        }

        private fun resolveByteIndexOfIgnoreCase(): Method {
            try {
                val speciesClass = Class.forName("jdk.incubator.vector.VectorSpecies")
                val m =
                    ByteVectorScan::class.java.getDeclaredMethod(
                        "indexOfIgnoreCase",
                        speciesClass,
                        ByteArray::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Byte::class.javaPrimitiveType,
                        Byte::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                m.setAccessible(true)
                return m
            } catch (e: ReflectiveOperationException) {
                throw ExceptionInInitializerError(e)
            } catch (e: LinkageError) {
                throw ExceptionInInitializerError(e)
            }
        }

        private fun resolveShortIndexOfCharClass(): Method {
            try {
                val speciesClass = Class.forName("jdk.incubator.vector.VectorSpecies")
                val m =
                    ShortVectorScan::class.java.getDeclaredMethod(
                        "indexOfCharClass",
                        speciesClass,
                        CharArray::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        IntArray::class.java,
                        Int::class.javaPrimitiveType
                    )
                m.setAccessible(true)
                return m
            } catch (e: ReflectiveOperationException) {
                throw ExceptionInInitializerError(e)
            } catch (e: LinkageError) {
                throw ExceptionInInitializerError(e)
            }
        }

        private fun invokeByteIndexOfAsciiClass(
            species: SpeciesHandle, bytes: ByteArray?, offset: Int, length: Int, ranges: IntArray?, start: Int
        ): Int {
            try {
                return BYTE_INDEX_OF_ASCII_CLASS!!.invoke(
                    null, species.value, bytes, offset, length, ranges, start
                ) as Int
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }

        private fun invokeByteIndexOfIgnoreCase(
            species: SpeciesHandle,
            bytes: ByteArray?,
            offset: Int,
            length: Int,
            prefix: String?,
            prefixLen: Int,
            anchorOffset: Int,
            low: Byte,
            high: Byte,
            start: Int
        ): Int {
            try {
                return BYTE_INDEX_OF_IGNORE_CASE!!.invoke(
                    null,
                    species.value,
                    bytes,
                    offset,
                    length,
                    prefix,
                    prefixLen,
                    anchorOffset,
                    low,
                    high,
                    start
                ) as Int
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }

        private fun invokeShortIndexOfCharClass(
            species: SpeciesHandle, chars: CharArray?, offset: Int, length: Int, ranges: IntArray?, start: Int
        ): Int {
            try {
                return SHORT_INDEX_OF_CHAR_CLASS!!.invoke(
                    null, species.value, chars, offset, length, ranges, start
                ) as Int
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }
    }
}
