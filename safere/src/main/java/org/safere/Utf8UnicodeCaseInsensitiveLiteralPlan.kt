// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.math.max
import kotlin.math.min

/** One UTF-8 encoding of a Unicode-folded code point with its KMP failure table. */
internal class Utf8EncodedFold(val bytes: ByteArray, val failure: IntArray)

/**
 * Portable Unicode case-insensitive literal tables for
 * [Utf8StartAccelerator.UnicodeCaseInsensitiveLiteral].
 */
internal class Utf8UnicodeCaseInsensitiveLiteralPlan(
  val prefixFoldVariants: List<IntArray>,
  val anchorByteOffset: Int,
  val anchorVariants: List<Utf8EncodedFold>,
) {
  companion object {
    private const val MAX_FOLD_VARIANTS = 4

    fun from(prefix: String): Utf8UnicodeCaseInsensitiveLiteralPlan? {
      val codePoints = CodePoints.toCodePointArray(prefix)
      var stablePrefixBytes = 0
      var bestRarity = -1
      var bestOffset = 0
      var bestVariants: MutableList<Utf8EncodedFold>? = null
      val prefixFoldVariants = ArrayList<IntArray>(codePoints.size)
      var stablePrefixWidth = true
      for (codePoint in codePoints) {
        if (CodePoints.isSurrogate(codePoint)) {
          return null
        }
        val foldVariants = foldVariants(codePoint) ?: return null
        prefixFoldVariants.add(foldVariants)
        if (!stablePrefixWidth) {
          continue
        }
        val variants = encodedFoldVariants(foldVariants)
        var rarity = 255
        val width = variants.first().bytes.size
        var stableWidth = true
        for (variant in variants) {
          var variantRarity = 0
          for (value in variant.bytes) {
            variantRarity = max(variantRarity, RarityOracle.exactByteRarity(value.toInt() and 0xFF))
          }
          rarity = min(rarity, variantRarity)
          stableWidth = stableWidth and (variant.bytes.size == width)
        }
        if (rarity > bestRarity) {
          bestRarity = rarity
          bestOffset = stablePrefixBytes
          bestVariants = variants
        }
        // A later anchor has no fixed byte offset if an earlier fold changes UTF-8 width.
        if (!stableWidth) {
          stablePrefixWidth = false
        } else {
          stablePrefixBytes += width
        }
      }
      return if (bestVariants == null) {
        null
      } else {
        Utf8UnicodeCaseInsensitiveLiteralPlan(prefixFoldVariants, bestOffset, bestVariants)
      }
    }

    private fun foldVariants(codePoint: Int): IntArray? {
      val builder = CharClassBuilder()
      UnicodeCaseFolding.addUnicodeFoldedRange(builder, codePoint, codePoint)
      val closure = builder.build()
      if (closure.numRunes() > MAX_FOLD_VARIANTS) {
        return null
      }
      val variants = IntArray(closure.numRunes())
      var count = 0
      for (i in 0..<closure.numRanges()) {
        for (cp in closure.lo(i)..closure.hi(i)) {
          variants[count++] = cp
        }
      }
      return variants
    }

    private fun encodedFoldVariants(foldVariants: IntArray): MutableList<Utf8EncodedFold> {
      val variants = ArrayList<Utf8EncodedFold>()
      for (folded in foldVariants) {
        val bytes = CodePoints.toChars(folded).concatToString().encodeToByteArray()
        variants.add(Utf8EncodedFold(bytes, ByteLiteralScan.literalFailure(bytes)))
      }
      return variants
    }
  }
}
