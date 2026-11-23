// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Portable compile/match core for [PlatformPattern] on non-JVM targets.
 *
 * Uses Parser → Compiler → Prog, then BitState when eligible else Nfa. This is intentionally a
 * subset of Matcher's acceleration stack (no DFA / OnePass / start accelerators).
 */
internal object PlatformRegexEngine {
  class Compiled(
    val regex: String,
    val flags: Int,
    val prog: Prog,
    /** Capturing groups including group 0. */
    val numCaptures: Int,
  ) {
    /** Capturing groups excluding group 0 (Matcher.groupCount semantics). */
    val groupCount: Int
      get() = maxOf(0, numCaptures - 1)
  }

  fun compile(regex: String, flags: Int = 0): Compiled {
    PatternFlagMapping.validateFlags(flags)
    val effective = PatternFlagMapping.effectiveFlags(flags)
    val parseFlags = PatternFlagMapping.toParseFlags(effective)
    val re = Parser.parse(regex, parseFlags)
      ?: throw PatternSyntaxExceptions.create("error parsing regexp", regex, -1)
    val prepared =
      Compiler.prepare(re)
        ?: throw PatternSyntaxExceptions.create("compiled program too large", regex, -1)
    val prog =
      Compiler.compile(prepared, false)
        ?: throw PatternSyntaxExceptions.create("compiled program too large", regex, -1)
    prog.setUnixLines((effective and PatternFlagMapping.UNIX_LINES) != 0)
    return Compiled(regex, effective, prog, prepared.numCaptures)
  }

  fun quote(s: String): String = PatternFlagMapping.quote(s)

  fun matches(compiled: Compiled, text: String): IntArray? {
    return search(
      compiled,
      text,
      startPos = 0,
      anchored = true,
      longest = true,
      endMatch = true,
    )
  }

  fun lookingAt(compiled: Compiled, text: String): IntArray? {
    return search(
      compiled,
      text,
      startPos = 0,
      anchored = true,
      longest = false,
      endMatch = false,
    )
  }

  fun find(compiled: Compiled, text: String, startPos: Int): IntArray? {
    if (startPos < 0 || startPos > text.length) {
      throw IndexOutOfBoundsException("start=$startPos length=${text.length}")
    }
    return search(
      compiled,
      text,
      startPos = startPos,
      anchored = false,
      longest = false,
      endMatch = false,
    )
  }

  private fun search(
    compiled: Compiled,
    text: String,
    startPos: Int,
    anchored: Boolean,
    longest: Boolean,
    endMatch: Boolean,
  ): IntArray? {
    val prog = compiled.prog
    val nsubmatch = maxOf(1, compiled.numCaptures)
    val window = text.length - startPos
    val maxBit = BitState.maxTextSize(prog)
    if (maxBit >= 0 && window <= maxBit) {
      val bitResult =
        BitState.search(
          prog,
          text,
          startPos,
          anchored,
          longest,
          endMatch,
          nsubmatch,
        )
      if (bitResult != null) {
        return bitResult
      }
      // BitState reported no match; still fall through only when BitState was ineligible.
      // A definitive miss must not be re-searched with Nfa for the same bounds.
      return null
    }

    val anchor = if (anchored) Nfa.Anchor.ANCHORED else Nfa.Anchor.UNANCHORED
    val kind =
      when {
        endMatch -> Nfa.MatchKind.FULL_MATCH
        longest -> Nfa.MatchKind.LONGEST_MATCH
        else -> Nfa.MatchKind.FIRST_MATCH
      }
    return Nfa.search(prog, text, startPos, anchor, kind, nsubmatch)
  }
}
