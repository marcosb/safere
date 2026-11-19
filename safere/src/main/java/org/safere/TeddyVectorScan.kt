// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.VectorSpecies

/** Stateless SIMD Teddy multi-keyword vector-shuffle scanning kernels using the Vector API.  */
internal object TeddyVectorScan {
    private val SPECIES: VectorSpecies<Byte?> = ByteVector.SPECIES_PREFERRED

    fun indexOfTeddyUtf8(bytes: ByteArray, offset: Int, length: Int, model: TeddyModel, start: Int): Int {
        val minLen = model.minLength()
        var pos = Math.max(0, start)
        val vectorLen = SPECIES.length()
        val limit = length - vectorLen

        val lutLo0 = ByteVector.fromArray(SPECIES, model.lutLo(), 0)
        val lutHi0 = ByteVector.fromArray(SPECIES, model.lutHi(), 0)
        // Keep guarded vector operands non-null. On affected JDK 25 and 26 releases, C2 can
        // schedule a vector payload load before its guard and dereference a null vector box.
        // See JDK-8387012 and https://github.com/safere/safere/issues/1170.
        val disabledLut = ByteVector.zero(SPECIES)
        val is2Byte = model.is2Byte()
        val lutLo1 = if (is2Byte) ByteVector.fromArray(SPECIES, model.lutLo1(), 0) else disabledLut
        val lutHi1 = if (is2Byte) ByteVector.fromArray(SPECIES, model.lutHi1(), 0) else disabledLut
        val is3Byte = model.is3Byte()
        val lutLo2 = if (is3Byte) ByteVector.fromArray(SPECIES, model.lutLo2(), 0) else disabledLut
        val lutHi2 = if (is3Byte) ByteVector.fromArray(SPECIES, model.lutHi2(), 0) else disabledLut

        val literals = model.literals()
        val buckets = model.literalBuckets()

        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */

        val scalarLimit = length - minLen
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return -1
    }
}
