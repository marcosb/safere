// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Bit flags for zero-width (empty) assertions. These are used by [InstOp.EMPTY_WIDTH]
 * instructions to test conditions at the current position without consuming input.
 * 
 * 
 * These correspond to RE2's EmptyOp enum in prog.h.
 */
internal object EmptyOp {
    /** `^` — beginning of line with the standard line terminator set.  */
    val BEGIN_LINE: Int = 1 shl 0

    /** `$` — end of line with the standard line terminator set.  */
    val END_LINE: Int = 1 shl 1

    /** `\A` — beginning of text.  */
    val BEGIN_TEXT: Int = 1 shl 2

    /** `\z` — end of text.  */
    val END_TEXT: Int = 1 shl 3

    /** `\b` — word boundary.  */
    val WORD_BOUNDARY: Int = 1 shl 4

    /** `\B` — not a word boundary.  */
    val NON_WORD_BOUNDARY: Int = 1 shl 5

    /**
     * `$` without `MULTILINE` — end of text or before a trailing line terminator. JDK's
     * `$` matches at end-of-input and also just before a final newline at the end of input.
     * This flag is set at both positions. Distinct from [.END_TEXT] (`\z`), which matches
     * only at the absolute end.
     */
    val DOLLAR_END: Int = 1 shl 6

    /** `\b` — Unicode word boundary (when UNICODE_CHARACTER_CLASS is set).  */
    val UNICODE_WORD_BOUNDARY: Int = 1 shl 7

    /** `\B` — Unicode not a word boundary (when UNICODE_CHARACTER_CLASS is set).  */
    val UNICODE_NON_WORD_BOUNDARY: Int = 1 shl 8

    /** `$` or `\Z` with UNIX_LINES: end of text or before a final LF.  */
    val UNIX_DOLLAR_END: Int = 1 shl 9

    /** Multiline `^` with UNIX_LINES.  */
    val UNIX_BEGIN_LINE: Int = 1 shl 10

    /** Multiline `$` with UNIX_LINES.  */
    val UNIX_END_LINE: Int = 1 shl 11

    /** `\b{g}` — Unicode extended grapheme cluster boundary.  */
    val GRAPHEME_CLUSTER_BOUNDARY: Int = 1 shl 12

    /** Explicit `\b{g}` boundary, including String surrogate-context compatibility.  */
    val EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY: Int = 1 shl 13

    /** All flags combined.  */
    val ALL_FLAGS: Int = (1 shl 14) - 1
}
