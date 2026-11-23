// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * JDK-shaped pattern flag bits and mapping into internal [ParseFlags].
 *
 * Numeric values match `java.util.regex.Pattern` without referencing that type, so commonMain /
 * native can compile flags the same way as the Maven [Pattern] façade.
 */
internal object PatternFlagMapping {
  /** Enables Unix lines mode (`'\n'` only as a line terminator). */
  const val UNIX_LINES: Int = 0x01

  /** Enables case-insensitive matching (ASCII by default). */
  const val CASE_INSENSITIVE: Int = 0x02

  /** Permits whitespace and `#` comments in the pattern. */
  const val COMMENTS: Int = 0x04

  /** Enables multiline `^` / `$`. */
  const val MULTILINE: Int = 0x08

  /** Parses the pattern as a literal string. */
  const val LITERAL: Int = 0x10

  /** Enables dotall mode (`.` matches line terminators). */
  const val DOTALL: Int = 0x20

  /** Enables Unicode-aware case folding with [CASE_INSENSITIVE]. */
  const val UNICODE_CASE: Int = 0x40

  /** Enables Unicode character classes (`\w`, `\d`, `\s`, …). */
  const val UNICODE_CHARACTER_CLASS: Int = 0x100

  /** All flag bits SafeRE accepts. */
  val SUPPORTED_FLAGS: Int =
    (UNIX_LINES
      or CASE_INSENSITIVE
      or COMMENTS
      or MULTILINE
      or LITERAL
      or DOTALL
      or UNICODE_CASE
      or UNICODE_CHARACTER_CLASS)

  /** Validates that no unsupported flag bits are set. */
  fun validateFlags(flags: Int) {
    val unsupported = flags and SUPPORTED_FLAGS.inv()
    require(unsupported == 0) {
      ("Unsupported flags: 0x"
        + unsupported.toString(16)
        + ". CANON_EQ is not supported by SafeRE.")
    }
  }

  /** Returns JDK-compatible effective flags after applying implied flags. */
  fun effectiveFlags(flags: Int): Int {
    var result = flags
    if ((result and UNICODE_CHARACTER_CLASS) != 0) {
      result = result or UNICODE_CASE
    }
    return result
  }

  /**
   * Converts JDK-shaped pattern flags to internal [ParseFlags].
   *
   * The baseline is [ParseFlags.LIKE_PERL], which includes `ONE_LINE`.
   */
  fun toParseFlags(flags: Int): Int {
    var pf = ParseFlags.LIKE_PERL

    if ((flags and CASE_INSENSITIVE) != 0) {
      pf = pf or ParseFlags.FOLD_CASE
    }
    if ((flags and MULTILINE) != 0) {
      pf = pf and ParseFlags.ONE_LINE.inv()
    }
    if ((flags and DOTALL) != 0) {
      pf = pf or ParseFlags.DOT_NL
    }
    if ((flags and LITERAL) != 0) {
      pf = pf or ParseFlags.LITERAL
    }
    if ((flags and COMMENTS) != 0) {
      pf = pf or ParseFlags.COMMENTS
    }
    if ((flags and UNICODE_CASE) != 0) {
      pf = pf or ParseFlags.UNICODE_CASE
    }
    if ((flags and UNICODE_CHARACTER_CLASS) != 0) {
      pf = pf or (ParseFlags.UNICODE_GROUPS or ParseFlags.UNICODE_CHAR_CLASS)
    }
    if ((flags and UNIX_LINES) != 0) {
      pf = pf or ParseFlags.UNIX_LINES
    }
    return pf
  }

  /**
   * Returns a literal pattern string for [s] using `\Q...\E` quoting.
   */
  fun quote(s: String): String {
    if (!s.contains("\\E")) {
      return "\\Q" + s + "\\E"
    }
    val sb = StringBuilder()
    sb.append("\\Q")
    var last = 0
    var idx: Int
    while ((s.indexOf("\\E", last).also { idx = it }) != -1) {
      sb.append(s, last, idx)
      sb.append("\\E\\\\E\\Q")
      last = idx + 2
    }
    sb.append(s, last, s.length)
    sb.append("\\E")
    return sb.toString()
  }
}
