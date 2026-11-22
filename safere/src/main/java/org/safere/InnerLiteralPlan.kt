// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Portable compile-time description of an inner-literal search: a fixed prefix, a consuming gap
 * class, and a required suffix with UTF-8 / String scan tables.
 */
internal class InnerLiteralPlan(
  val literal: String,
  val utf8: ByteArray,
  val failure: IntArray,
  val shifts: IntArray?,
  val anchorOffset: Int,
  val anchor: Char,
  val gap: CharClassScanInfo,
  val prefixCodePoints: Int,
) {
  companion object {
    fun compile(ast: Regexp?): InnerLiteralPlan? {
      if (ast == null) {
        return null
      }
      val pending = ArrayDeque<Regexp>()
      pending.addFirst(ast)
      val prefix = StringBuilder()
      val suffix = StringBuilder()
      var gap: CharClassScanInfo? = null
      while (!pending.isEmpty()) {
        val node = pending.removeFirst()
        when (node.op) {
          RegexpOp.CAPTURE, RegexpOp.NON_CAPTURE, RegexpOp.CONCAT -> {
            for (i in node.subs!!.indices.reversed()) {
              pending.addFirst(node.subs!!.get(i)!!)
            }
          }
          RegexpOp.EMPTY_MATCH,
          RegexpOp.BEGIN_TEXT,
          RegexpOp.END_TEXT,
          RegexpOp.BEGIN_LINE,
          RegexpOp.END_LINE,
          RegexpOp.WORD_BOUNDARY,
          RegexpOp.NO_WORD_BOUNDARY -> {
            // Assertions only restrict the language; ordinary matching still evaluates them.
          }
          RegexpOp.LITERAL, RegexpOp.LITERAL_STRING -> {
            if ((node.flags and ParseFlags.FOLD_CASE) != 0) {
              return null
            }
            val target = if (gap == null) prefix else suffix
            if (node.op == RegexpOp.LITERAL) {
              if (!appendScalar(target, node.rune)) {
                return null
              }
            } else {
              for (rune in node.runes) {
                if (!appendScalar(target, rune)) {
                  return null
                }
              }
            }
          }
          RegexpOp.STAR, RegexpOp.PLUS, RegexpOp.QUEST, RegexpOp.REPEAT -> {
            if (gap != null || prefix.isEmpty()) {
              return null
            }
            var operand: Regexp = node.subs!!.first()!!
            while (operand.op == RegexpOp.CAPTURE || operand.op == RegexpOp.NON_CAPTURE) {
              operand = operand.subs!!.first()!!
            }
            if (operand.op != RegexpOp.CHAR_CLASS || operand.charClass!!.isEmpty()) {
              return null
            }
            gap = CharClassScanInfo.fromCharClass(operand.charClass!!)
          }
          else -> return null
        }
      }
      if (gap == null || suffix.isEmpty()) {
        return null
      }
      val literal = suffix.toString()
      val utf8 = literal.encodeToByteArray()
      val anchorOffset = StringLiteralSearch.anchorOffset(literal)
      return InnerLiteralPlan(
        literal,
        utf8,
        ByteLiteralScan.literalFailure(utf8),
        ByteLiteralScan.literalShifts(utf8),
        anchorOffset,
        StringLiteralSearch.anchorAt(literal, anchorOffset),
        gap,
        CodePoints.codePointCount(prefix, 0, prefix.length),
      )
    }

    /**
     * Reuses UTF-8 / horspool tables from a reject literal that already matches [plan]'s suffix.
     */
    fun withSharedLiteralTables(
      plan: InnerLiteralPlan,
      literal: String,
      utf8: ByteArray,
      failure: IntArray,
      shifts: IntArray?,
      anchorOffset: Int,
      anchor: Char,
    ): InnerLiteralPlan {
      require(literal == plan.literal)
      return InnerLiteralPlan(
        plan.literal,
        utf8,
        failure,
        shifts,
        anchorOffset,
        anchor,
        plan.gap,
        plan.prefixCodePoints,
      )
    }

    private fun appendScalar(target: StringBuilder, rune: Int): Boolean {
      if (rune >= Utils.MIN_SURROGATE && rune <= Utils.MAX_SURROGATE) {
        return false
      }
      target.append(Utils.runeToString(rune))
      return true
    }
  }
}
