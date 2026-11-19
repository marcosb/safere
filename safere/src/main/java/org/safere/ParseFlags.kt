// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Bit flags controlling the behavior of the regular expression parser. These flags affect what
 * syntax is accepted and how certain constructs are interpreted.
 * 
 * 
 * These correspond to RE2's ParseFlags enum in regexp.h.
 */
internal object ParseFlags {
    /** No flags set.  */
    const val NONE: Int = 0

    /** Fold case during matching (case-insensitive).  */
    val FOLD_CASE: Int = 1 shl 0

    /** Treat the pattern as a literal string instead of a regexp.  */
    val LITERAL: Int = 1 shl 1

    /** Allow character classes like `[^a-z]`, `\D`, and `\s` to match newline.  */
    val CLASS_NL: Int = 1 shl 2

    /** Allow `.` to match newline.  */
    val DOT_NL: Int = 1 shl 3

    /** Combined: both [.CLASS_NL] and [.DOT_NL].  */
    val MATCH_NL: Int = CLASS_NL or DOT_NL

    /**
     * Treat `^` and `$` as only matching at beginning and end of text, not around
     * embedded newlines. This is Perl's default mode.
     */
    val ONE_LINE: Int = 1 shl 4

    /** Regexp and text are in Latin-1, not UTF-8/Unicode. Not used in Java.  */
    val LATIN1: Int = 1 shl 5

    /** Repetition operators (`*`, `+`, `?`) are non-greedy by default.  */
    val NON_GREEDY: Int = 1 shl 6

    /** Allow Perl character classes: `\d`, `\s`, `\w` and their negations.  */
    val PERL_CLASSES: Int = 1 shl 7

    /** Allow Perl's `\b` (word boundary) and `\B` (not word boundary).  */
    val PERL_B: Int = 1 shl 8

    /**
     * Perl extensions: non-capturing parens `(?:...)`, non-greedy quantifiers, flag editing,
     * etc.
     */
    val PERL_X: Int = 1 shl 9

    /**
     * Allow `\p{Han}` for Unicode script/category groups and `\P{Han}` for their
     * negation.
     */
    val UNICODE_GROUPS: Int = 1 shl 10

    /**
     * Verbose/comments mode: ignore unescaped whitespace and `#`-to-EOL comments. Corresponds
     * to Java's [java.util.regex.Pattern.COMMENTS] flag and Perl's `(?x)`.
     */
    val COMMENTS: Int = 1 shl 11

    /**
     * Unicode character classes mode: makes `\d`, `\w`, `\s`, and `\b` use
     * Unicode definitions instead of ASCII-only. Corresponds to Java's [ ][java.util.regex.Pattern.UNICODE_CHARACTER_CLASS] flag.
     */
    val UNICODE_CHAR_CLASS: Int = 1 shl 12

    /** Never match `\n`, even if the regexp mentions it explicitly.  */
    val NEVER_NL: Int = 1 shl 13

    /** Parse all parentheses as non-capturing.  */
    val NEVER_CAPTURE: Int = 1 shl 14

    /**
     * Composite flag matching Perl's default behavior. Includes [.CLASS_NL], [.ONE_LINE],
     * [.PERL_CLASSES], [.PERL_B], [.PERL_X], and [.UNICODE_GROUPS].
     */
    val LIKE_PERL: Int = CLASS_NL or ONE_LINE or PERL_CLASSES or PERL_B or PERL_X or UNICODE_GROUPS

    /**
     * Internal flag: on [RegexpOp.END_TEXT], indicates the original pattern used `$`
     * rather than `\z`.
     */
    val WAS_DOLLAR: Int = 1 shl 15

    /**
     * Unix lines mode: only `'\n'` is recognized as a line terminator in the behavior of `.`, `^`, and `$`. Without this flag, all JDK line terminators are recognized:
     * `'\n'`, `'\r'`, `"\r\n"`, `'\u0085'`, `'\u2028'`, and `'\u2029'`.
     */
    val UNIX_LINES: Int = 1 shl 16

    /** Use Unicode-aware case folding when [.FOLD_CASE] is active.  */
    val UNICODE_CASE: Int = 1 shl 17

    /** Mask of all valid parse flags.  */
    val ALL_FLAGS: Int = (1 shl 18) - 1
}
