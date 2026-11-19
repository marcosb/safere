// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*
import java.util.Map

/**
 * Encapsulates structural analysis of a regular expression AST in a single bottom-up pass using the
 * stack-safe [Walker].
 */
@JvmRecord
internal data class AstAnalysis(
    val hasLazy: Boolean,
    val hasAlt: Boolean,
    val hasNullableAlt: Boolean,
    val canMatchEmpty: Boolean,
    val hasUserCaptures: Boolean,
    val minMatchLength: Int,
    val namedGroups: MutableMap<String?, Int?>?
) {
    @JvmRecord
    private data class AnalysisNode(
        val hasLazy: Boolean,
        val hasAlt: Boolean,
        val hasNullableAlt: Boolean,
        val canMatchEmpty: Boolean,
        val hasUserCaptures: Boolean,
        val minMatchLength: Int
    )

    private class AstAnalysisWalker : Walker<AnalysisNode>() {
        private var named: MutableMap<String?, Int?>? = null

        override fun shortVisit(re: Regexp?, parentArg: AnalysisNode?): AnalysisNode {
            return AnalysisNode(false, false, false, true, false, 0)
        }

        override fun postVisit(
            re: Regexp, parentArg: AnalysisNode?, preArg: AnalysisNode?, childArgs: MutableList<AnalysisNode>
        ): AnalysisNode {
            if (WorkCounterConfig.ENABLED) {
                record()
            }
            if (re.op == RegexpOp.CAPTURE && re.name != null && !re.name.isEmpty()) {
                if (named == null) {
                    named = HashMap<String?, Int?>()
                }
                named!!.put(re.name, re.cap)
            }

            var anyLazy = re.nonGreedy()
            var anyAlt = re.op == RegexpOp.ALTERNATE
            var anyNullableAlt = false
            var anyUserCaptures = re.op == RegexpOp.CAPTURE && re.cap > 0

            for (child in childArgs) {
                if (child.hasLazy) {
                    anyLazy = true
                }
                if (child.hasAlt) {
                    anyAlt = true
                }
                if (child.hasNullableAlt) {
                    anyNullableAlt = true
                }
                if (child.hasUserCaptures) {
                    anyUserCaptures = true
                }
            }

            val canMatchEmpty: Boolean
            val minMatchLength: Int

            when (re.op) {
                RegexpOp.NO_MATCH -> {
                    canMatchEmpty = false
                    minMatchLength = Integer.MAX_VALUE / 2
                }

                RegexpOp.EMPTY_MATCH, RegexpOp.BEGIN_LINE, RegexpOp.END_LINE, RegexpOp.BEGIN_TEXT, RegexpOp.END_TEXT, RegexpOp.WORD_BOUNDARY, RegexpOp.NO_WORD_BOUNDARY, RegexpOp.GRAPHEME_CLUSTER_BOUNDARY, RegexpOp.HAVE_MATCH -> {
                    canMatchEmpty = true
                    minMatchLength = 0
                }

                RegexpOp.LITERAL -> {
                    canMatchEmpty = false
                    minMatchLength = Character.charCount(re.rune)
                }

                RegexpOp.LITERAL_STRING -> {
                    var count = 0
                    if (re.runes != null) {
                        for (r in re.runes) {
                            count += Character.charCount(r)
                        }
                    }
                    canMatchEmpty = count == 0
                    minMatchLength = count
                }

                RegexpOp.CHAR_CLASS, RegexpOp.ANY_CHAR, RegexpOp.ANY_BYTE, RegexpOp.GRAPHEME_CLUSTER -> {
                    canMatchEmpty = false
                    minMatchLength = 1
                }

                RegexpOp.STAR, RegexpOp.QUEST -> {
                    canMatchEmpty = true
                    minMatchLength = 0
                }

                RegexpOp.PLUS -> {
                    canMatchEmpty = !childArgs.isEmpty() && childArgs.getFirst().canMatchEmpty
                    minMatchLength = if (!childArgs.isEmpty()) childArgs.getFirst().minMatchLength else 0
                }

                RegexpOp.REPEAT -> {
                    val subMin = if (!childArgs.isEmpty()) childArgs.getFirst().minMatchLength else 0
                    minMatchLength =
                        Math.min(subMin.toLong() * Math.max(0, re.min), (Integer.MAX_VALUE / 2).toLong()).toInt()
                    canMatchEmpty =
                        re.min == 0 || (!childArgs.isEmpty() && childArgs.getFirst().canMatchEmpty)
                }

                RegexpOp.CAPTURE, RegexpOp.NON_CAPTURE -> {
                    canMatchEmpty = !childArgs.isEmpty() && childArgs.getFirst().canMatchEmpty
                    minMatchLength = if (!childArgs.isEmpty()) childArgs.getFirst().minMatchLength else 0
                }

                RegexpOp.CONCAT -> {
                    var allEmpty = true
                    var sum: Long = 0
                    for (child in childArgs) {
                        if (!child.canMatchEmpty) {
                            allEmpty = false
                        }
                        sum += child.minMatchLength.toLong()
                    }
                    canMatchEmpty = allEmpty
                    minMatchLength = Math.min(sum, (Integer.MAX_VALUE / 2).toLong()).toInt()
                }

                RegexpOp.ALTERNATE -> {
                    var anyChildEmpty = false
                    var min = Integer.MAX_VALUE / 2
                    for (child in childArgs) {
                        if (child.canMatchEmpty) {
                            anyChildEmpty = true
                        }
                        min = Math.min(min, child.minMatchLength)
                    }
                    canMatchEmpty = anyChildEmpty || childArgs.isEmpty()
                    minMatchLength = if (min == Integer.MAX_VALUE / 2) 0 else min
                    if (anyChildEmpty) {
                        anyNullableAlt = true
                    }
                }

                else -> {
                    canMatchEmpty = false
                    minMatchLength = 0
                }
            }

            return AnalysisNode(
                anyLazy, anyAlt, anyNullableAlt, canMatchEmpty, anyUserCaptures, minMatchLength
            )
        }
    }

    companion object {
        fun analyze(re: Regexp?): AstAnalysis {
            if (re == null) {
                return AstAnalysis(false, false, false, true, false, 0, Map.of<String?, Int?>())
            }
            val walker = AstAnalysisWalker()
            val root = walker.walk(re, AnalysisNode(false, false, false, true, false, 0))
            val named = walker.named
            return AstAnalysis(
                root.hasLazy,
                root.hasAlt,
                root.hasNullableAlt,
                root.canMatchEmpty,
                root.hasUserCaptures,
                root.minMatchLength,
                if (named == null) Map.of<String?, Int?>() else Collections.unmodifiableMap<String?, Int?>(named)
            )
        }
    }
}
