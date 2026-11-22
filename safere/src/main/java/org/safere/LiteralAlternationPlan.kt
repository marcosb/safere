// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Portable compile-time description of a bounded exact-ASCII literal alternation for the
 * String / UTF-8 search fast path.
 */
internal class LiteralAlternationPlan(
  val literals: Array<String>,
  val wholeInputRejection: Boolean,
) {
  companion object {
    private const val MAX_ALTERNATIVES = 8
    private const val MAX_LITERAL_LENGTH = 12

    /**
     * Compiles an alternation of exact ASCII literals, or returns null when the shape is not
     * eligible. Caller must have ruled out explicit captures.
     */
    fun compile(ast: Regexp, wholeInputRejection: Boolean): LiteralAlternationPlan? {
      var node = ast
      while (node.op == RegexpOp.NON_CAPTURE || (node.op == RegexpOp.CAPTURE && node.cap == 0)) {
        node = node.sub()!!
      }
      if (node.op != RegexpOp.ALTERNATE || node.nsub() < 2 || node.nsub() > MAX_ALTERNATIVES) {
        return null
      }
      val literals: Array<String?> = arrayOfNulls(node.nsub())
      for (i in literals.indices) {
        literals[i] = MultiAnchorCompiler.extractExactAsciiLiteral(node.subs!!.get(i))
        if (literals[i] == null) {
          return null
        }
        // indexOf can repeatedly compare long near-matching prefixes. Even a whole-tail rejection
        // filter may stop after its first successful alternative, whereas exact bounds require us
        // to inspect later alternatives too. MAX_LITERAL_LENGTH * MAX_ALTERNATIVES bounds the
        // comparison work per input position, keeping indexOf's O(n * m) worst case linear in n.
        // WorkCounter charges distance, not character comparisons, so it relies on these caps.
        // Keep the existing engine for longer needles.
        if (literals[i]!!.length > MAX_LITERAL_LENGTH) {
          return null
        }
      }
      return LiteralAlternationPlan(literals.requireNoNulls(), wholeInputRejection)
    }
  }
}
