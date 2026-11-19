// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*

/** A required suffix and a guarded gap can exclude a prefix of candidate match starts.  */
internal class InnerLiteralSearch private constructor(
    private val suffix: RejectPrefilter.Literal,
    private val gap: CharClassScanInfo,
    private val prefixCodePoints: Int
) {
    /**
     * Returns a safe lower bound for match starts, or `-1` when the suffix is absent.
     * 
     * 
     * No match can end before the first suffix occurrence. If the character before that suffix
     * cannot belong to the gap, any start whose fixed prefix ends before that character is also
     * impossible for every later suffix occurrence. Retreating by the prefix's code-point length
     * retains every possible start. The normal engine checks the remaining candidates, including
     * bounds, assertions, greediness, and captures.
     * 
     * 
     * This performs one literal scan and one bounded backward probe per search, without retries. A
     * successful match consumes the first suffix occurrence or a later one, so successive finds do
     * not repeatedly scan the same unconsumed suffix range.
     */
    fun searchStart(scanner: InputScanner, from: Int): Int {
        if (WorkCounterConfig.ENABLED) {
            WorkCounter.recordInnerLiteralProbe()
        }
        val inner: Int
        if (scanner is Utf8InputScanner) {
            inner = scanner.indexOf(suffix.utf8, suffix.failure, suffix.shifts, from)
        } else if (scanner is StringRegionInputScanner) {
            inner = scanner.indexOf(suffix.literal, from, scanner.length())
        } else {
            val text = (scanner as StringInputScanner).text()
            inner =
                StringLiteralSearch.indexOf(
                    text, suffix.literal, suffix.anchorOffset, suffix.anchor, from
                )
        }
        if (inner < 0) {
            return -1
        }
        var position = inner
        var count = 0
        while (count < MAX_BACKWARD_CODE_POINTS && position > from) {
            val decoded = scanner.decodeBackward(position)
            val previous: Int = InputScanner.Companion.position(decoded)
            if (previous < from) {
                break
            }
            if (WorkCounterConfig.ENABLED) {
                record()
            }
            if (!gap.contains(InputScanner.Companion.codePoint(decoded))) {
                return Math.max(from, scanner.retreatByCodePoints(position, prefixCodePoints))
            }
            position = previous
            count++
        }
        return from
    }

    companion object {
        // A failed probe must add bounded work even when the entire input belongs to the gap class.
        private const val MAX_BACKWARD_CODE_POINTS = 256

        // Pay for narrowing only after the native matcher has consumed this much failed input.
        // Prefix-accelerator skips do not contribute to the budget.
        const val MIN_FAILED_INPUT_UNITS: Int = 64

        fun compile(ast: Regexp?, rejectPrefilter: RejectPrefilter?): InnerLiteralSearch? {
            val pending: Deque<Regexp> = ArrayDeque<Regexp>()
            pending.push(ast)
            val prefix = StringBuilder()
            val suffix = StringBuilder()
            var gap: CharClassScanInfo? = null
            while (!pending.isEmpty()) {
                val node = pending.pop()
                when (node.op) {
                    RegexpOp.CAPTURE, RegexpOp.NON_CAPTURE, RegexpOp.CONCAT -> {
                        for (i in node.subs.indices.reversed()) {
                            pending.push(node.subs.get(i))
                        }
                    }

                    RegexpOp.EMPTY_MATCH, RegexpOp.BEGIN_TEXT, RegexpOp.END_TEXT, RegexpOp.BEGIN_LINE, RegexpOp.END_LINE, RegexpOp.WORD_BOUNDARY, RegexpOp.NO_WORD_BOUNDARY -> {
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
                        var operand: Regexp = node.subs.getFirst()
                        while (operand.op == RegexpOp.CAPTURE || operand.op == RegexpOp.NON_CAPTURE) {
                            operand = operand.subs.getFirst()
                        }
                        if (operand.op != RegexpOp.CHAR_CLASS || operand.charClass.isEmpty()) {
                            return null
                        }
                        gap = CharClassScanInfo.Companion.fromCharClass(operand.charClass)
                    }

                    else -> {
                        return null
                    }
                }
            }
            if (gap == null || suffix.isEmpty()) {
                return null
            }
            val literal = suffix.toString()
            val filter =
                if (rejectPrefilter is RejectPrefilter.Literal
                    && rejectPrefilter.literal == literal
                )
                    rejectPrefilter
                else
                    RejectPrefilter.Literal.Companion.create(literal)
            return InnerLiteralSearch(filter, gap, prefix.codePointCount(0, prefix.length()))
        }

        private fun appendScalar(target: StringBuilder, rune: Int): Boolean {
            if (rune >= Character.MIN_SURROGATE.code && rune <= Character.MAX_SURROGATE.code) {
                return false
            }
            target.appendCodePoint(rune)
            return true
        }
    }
}
