// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Word-character predicates for `\b` / keyword-boundary fast paths.
 *
 * Unicode word membership uses SafeRE's bundled `\w` tables under UNICODE_CHARACTER_CLASS rather
 * than `Character.isAlphabetic` / `getType`, so commonMain can compile without JDK Character APIs.
 */
internal object WordCharacters {
  private val UNICODE_WORD_FLAT: IntArray by lazy { flatten(UnicodeTables.unicodeWord()) }

  fun isWordChar(codePoint: Int): Boolean = Ascii.isWordChar(codePoint)

  fun isUnicodeWordChar(codePoint: Int): Boolean =
    CharClass.containsInFlatRanges(UNICODE_WORD_FLAT, codePoint)

  private fun flatten(tables: Array<IntArray>): IntArray {
    val flat = IntArray(tables.size * 2)
    for (i in tables.indices) {
      flat[i * 2] = tables[i][0]
      flat[i * 2 + 1] = tables[i][1]
    }
    return flat
  }
}
