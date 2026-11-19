// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.safere.Pattern.CharClassMatchInfo
import org.safere.Pattern.KeywordAlternation

/**
 * Immutable descriptor capturing pre-computed match-execution fast-path metadata extracted from a
 * regular expression AST (Tier 2/3 execution acceleration).
 * 
 * @param literalMatch full literal string for patterns that are entirely literal (no
 * metacharacters, no quantifiers, no alternation), or `null`
 * @param singleCharClass precomputed scan data for patterns that are exactly one character class
 * (e.g., `\p{javaLetter}`), allowing `find()` to scan directly without the full
 * engine cascade
 * @param keywordAlternation precomputed case-insensitive keyword-alternation fast-path data, or
 * `null`
 * @param charClassMatch precomputed character class data for the "repeated character class" fast
 * path in `matches()` (e.g., `[a-zA-Z]+`, `\d*`), allowing `matches()`
 * to bypass the engine cascade with a tight scanning loop
 */
@JvmRecord
internal data class MatchDescriptor(
    val literalMatch: String?,
    val literalFoldCase: Boolean,
    val singleCharClass: CharClassScanInfo?,
    val keywordAlternation: KeywordAlternation?,
    val charClassMatch: CharClassMatchInfo?,
    val minMatchLength: Int,
    val classHashChain: ClassHashChain?
) {
    constructor(
        literalMatch: String?,
        literalFoldCase: Boolean,
        singleCharClass: CharClassScanInfo?,
        keywordAlternation: KeywordAlternation?,
        charClassMatch: CharClassMatchInfo?,
        minMatchLength: Int
    ) : this(
        literalMatch,
        literalFoldCase,
        singleCharClass,
        keywordAlternation,
        charClassMatch,
        minMatchLength,
        compileClassHashChain(literalMatch, literalFoldCase)
    )

    fun hasFastPath(): Boolean {
        return literalMatch != null || singleCharClass != null || keywordAlternation != null || charClassMatch != null || minMatchLength > 0
    }

    fun hasFindFastPath(): Boolean {
        return literalMatch != null || singleCharClass != null || keywordAlternation != null
    }

    companion object {
        val NONE: MatchDescriptor = MatchDescriptor(null, false, null, null, null, 0, null)

        private fun compileClassHashChain(literal: String?, foldCase: Boolean): ClassHashChain? {
            return if (literal != null && foldCase) ClassHashChain.compileCaseInsensitive(literal) else null
        }
    }
}
