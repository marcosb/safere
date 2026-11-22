// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Portable UTF-8 scan tables for a disjoint required-literal reject set
 * ([RejectPrefilter.DisjointLiterals]). Branches with unpaired surrogates are kept in
 * [literals] for String inputs but omitted from the UTF-8 tables.
 */
internal class RejectDisjointLiteralsPlan(
  val literals: Array<String?>,
  val utf8Literals: Array<ByteArray?>,
  val failures: Array<IntArray?>,
  val shifts: Array<IntArray?>,
) {
  companion object {
    fun from(literals: Array<String?>?): RejectDisjointLiteralsPlan? {
      if (literals == null || literals.isEmpty()) {
        return null
      }
      var utf8Count = 0
      for (literal in literals) {
        if (!hasUnpairedSurrogate(literal!!)) {
          utf8Count++
        }
      }
      val utf8Literals = arrayOfNulls<ByteArray>(utf8Count)
      val failures = arrayOfNulls<IntArray>(utf8Count)
      val shifts = arrayOfNulls<IntArray>(utf8Count)
      var idx = 0
      for (literal in literals) {
        val lit = literal!!
        if (!hasUnpairedSurrogate(lit)) {
          val utf8 = lit.encodeToByteArray()
          utf8Literals[idx] = utf8
          failures[idx] = ByteLiteralScan.literalFailure(utf8)
          shifts[idx] = ByteLiteralScan.literalShifts(utf8)
          idx++
        }
      }
      return RejectDisjointLiteralsPlan(literals, utf8Literals, failures, shifts)
    }

    fun hasUnpairedSurrogate(s: String): Boolean {
      val len = s.length
      var i = 0
      while (i < len) {
        val c = s[i]
        if (CodePoints.isHighSurrogate(c)) {
          if (i + 1 < len && CodePoints.isLowSurrogate(s[i + 1])) {
            i++
          } else {
            return true
          }
        } else if (CodePoints.isLowSurrogate(c)) {
          return true
        }
        i++
      }
      return false
    }
  }
}
