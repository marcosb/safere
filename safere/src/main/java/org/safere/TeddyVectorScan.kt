// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.Vector
import jdk.incubator.vector.VectorMask
import jdk.incubator.vector.VectorOperators
import jdk.incubator.vector.VectorSpecies
import kotlin.math.*
/** Stateless SIMD Teddy multi-keyword vector-shuffle scanning kernels using the Vector API.  */
internal object TeddyVectorScan {
  private val SPECIES: VectorSpecies<Byte> = ByteVector.SPECIES_PREFERRED

  fun indexOfTeddyUtf8(bytes: ByteArray, offset: Int, length: Int, model: TeddyModel, start: Int): Int {
    val minLen = model.minLength()
    var pos = max(0, start)
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

    val literals = model.literals()!!
    val buckets = model.literalBuckets()!!
    val nibble = 0x0F.toByte()
    val zero = 0.toByte()

    while (pos <= limit) {
      // Stage 1: 2-byte primary SIMD filter (discards ~85% of non-matching blocks)
      val input0 = ByteVector.fromArray(SPECIES, bytes, offset + pos)
      val lo0 = input0.lanewise(VectorOperators.AND, nibble)
      val hi0 = input0.lanewise(VectorOperators.LSHR, 4L).lanewise(VectorOperators.AND, nibble)
      var match0 =
        lo0.selectFrom(lutLo0 as Vector<Byte>).lanewise(
          VectorOperators.AND,
          hi0.selectFrom(lutHi0 as Vector<Byte>),
        )

      if (is2Byte && pos + 1 <= limit) {
        val input1 = ByteVector.fromArray(SPECIES, bytes, offset + pos + 1)
        val lo1 = input1.lanewise(VectorOperators.AND, nibble)
        val hi1 = input1.lanewise(VectorOperators.LSHR, 4L).lanewise(VectorOperators.AND, nibble)
        val match1 =
          lo1.selectFrom(lutLo1 as Vector<Byte>).lanewise(
            VectorOperators.AND,
            hi1.selectFrom(lutHi1 as Vector<Byte>),
          )
        match0 = match0.lanewise(VectorOperators.AND, match1)
      }

      var matchMask: VectorMask<Byte> = match0.compare(VectorOperators.NE, zero)
      if (matchMask.anyTrue()) {
        // Stage 2: 3-byte confirmation filter (evaluated only on ~15% candidate blocks)
        if (is3Byte && pos + 2 <= limit) {
          val input2 = ByteVector.fromArray(SPECIES, bytes, offset + pos + 2)
          val lo2 = input2.lanewise(VectorOperators.AND, nibble)
          val hi2 = input2.lanewise(VectorOperators.LSHR, 4L).lanewise(VectorOperators.AND, nibble)
          val match2 =
            lo2.selectFrom(lutLo2 as Vector<Byte>).lanewise(
              VectorOperators.AND,
              hi2.selectFrom(lutHi2 as Vector<Byte>),
            )
          match0 = match0.lanewise(VectorOperators.AND, match2)
          matchMask = match0.compare(VectorOperators.NE, zero)
        }

        if (matchMask.anyTrue()) {
          // Stage 3: Candidate extraction and verification (< 0.1% of blocks)
          var activeLanes = matchMask.toLong()
          while (activeLanes != 0L) {
            val bit = java.lang.Long.numberOfTrailingZeros(activeLanes)
            val candidatePos = pos + bit
            val bucketMask = match0.lane(bit)

            for (litIdx in literals.indices) {
              val b = buckets[litIdx]
              if ((bucketMask.toInt() and (1 shl b)) != 0) {
                val lit = literals[litIdx]!!
                if (candidatePos + lit.length <= length
                  && Ascii.regionMatches(bytes, offset + candidatePos, lit, lit.length)
                ) {
                  return candidatePos
                }
              }
            }
            activeLanes = activeLanes and (activeLanes - 1)
          }
        }
      }
      pos += vectorLen
    }

    val scalarLimit = length - minLen
    while (pos <= scalarLimit) {
      for (rawLit in literals) {
        val lit = rawLit!!
        if (pos + lit.length <= length
          && Ascii.regionMatches(bytes, offset + pos, lit, lit.length)
        ) {
          return pos
        }
      }
      pos++
    }
    return -1
  }
}
