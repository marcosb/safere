// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere


/**
 * A node in the regular expression abstract syntax tree (AST). Each node has a [RegexpOp]
 * that determines what kind of regexp it represents, and operator-specific data stored in the
 * node's fields.
 * 
 * 
 * The AST is produced by the `Parser` and consumed by the `Compiler` to produce a
 * [Prog]. The AST can also be simplified by the `Simplifier` and converted back to a
 * string via [.toString].
 * 
 * 
 * Field usage by operator:
 * 
 * <table>
 * <tr><th>Operator</th><th>Fields used</th></tr>
 * <tr><td>[RegexpOp.LITERAL]</td><td>[.rune], [.flags]</td></tr>
 * <tr><td>[RegexpOp.LITERAL_STRING]</td><td>[.runes], [.flags]</td></tr>
 * <tr><td>[RegexpOp.CHAR_CLASS]</td><td>[.charClass]</td></tr>
 * <tr><td>[RegexpOp.GRAPHEME_CLUSTER]</td><td>[.flags]</td></tr>
 * <tr><td>[RegexpOp.CONCAT], [RegexpOp.ALTERNATE]</td><td>[.subs]</td></tr>
 * <tr><td>[RegexpOp.STAR], [RegexpOp.PLUS], [RegexpOp.QUEST]</td>
 * <td>[.subs] (length 1), [.flags]</td></tr>
 * <tr><td>[RegexpOp.REPEAT]</td><td>[.subs] (length 1), [.min],
 * [.max], [.flags]</td></tr>
 * <tr><td>[RegexpOp.CAPTURE]</td><td>[.subs] (length 1), [.cap],
 * [.name]</td></tr>
 * <tr><td>[RegexpOp.HAVE_MATCH]</td><td>[.matchId]</td></tr>
 * <tr><td>All others</td><td>No additional fields</td></tr>
</table> * 
 */
internal class Regexp(val op: RegexpOp, val flags: Int) {
    /**
     * Sub-expressions (children). Used by CONCAT, ALTERNATE, STAR, PLUS, QUEST, REPEAT, NON_CAPTURE,
     * and CAPTURE.
     */
    var subs: MutableList<Regexp?>? = null

    /**
     * A single Unicode code point. Used by LITERAL. The code point may be case-folded if [ ][ParseFlags.FOLD_CASE] is set in [.flags].
     */
    var rune: Int = 0

    /** An array of Unicode code points. Used by LITERAL_STRING.  */
    var runes: IntArray = EMPTY_RUNES

    /** The character class. Used by CHAR_CLASS.  */
    var charClass: CharClass? = null

    /** Minimum repetition count. Used by REPEAT.  */
    var min: Int = 0

    /** Maximum repetition count (-1 = unbounded). Used by REPEAT.  */
    var max: Int = 0

    /** Capture group index (1-based for user groups; 0 for full match). Used by CAPTURE.  */
    var cap: Int = 0

    /** Capture group name, or null for unnamed groups. Used by CAPTURE.  */
    var name: String? = null

    /** Match ID for multi-pattern matching. Used by HAVE_MATCH.  */
    var matchId: Int = 0
    // --- Accessors ---
    /** Returns the single sub-expression, or throws if there isn't exactly one.  */
    fun sub(): Regexp {
        val s = subs
        check(!(s == null || s.size != 1)) { op.toString() + " does not have exactly one sub-expression" }
        return s[0]!!
    }

    /** Returns the number of sub-expressions.  */
    fun nsub(): Int {
        val s = subs
        return if (s != null) s.size else 0
    }

    /** Returns true if the [ParseFlags.NON_GREEDY] flag is set.  */
    fun nonGreedy(): Boolean {
        return (flags and ParseFlags.NON_GREEDY) != 0
    }

    /** Returns true if the [ParseFlags.FOLD_CASE] flag is set.  */
    fun foldCase(): Boolean {
        return (flags and ParseFlags.FOLD_CASE) != 0
    }

    override fun toString(): String {
        val sb = StringBuilder()
        val w = ToStringWalker(sb)
        w.walkExponential(this, PREC_TOPLEVEL, 100000)
        if (w.stoppedEarly()) {
            sb.append(" [truncated]")
        }
        return sb.toString()
    }


    /** Walker that generates a string representation by accumulating into a StringBuilder.  */
    private class ToStringWalker(private val sb: StringBuilder) : Walker<Int?>() {


        override fun shortVisit(re: Regexp, parentArg: Int?): Int {
            return 0
        }

        override fun preVisit(re: Regexp, parentArg: Int?, stop: BooleanArray): Int {
            val prec = parentArg ?: PREC_TOPLEVEL
            val nprec: Int
            nprec =
                when (re.op) {
                    RegexpOp.NO_MATCH, RegexpOp.EMPTY_MATCH, RegexpOp.LITERAL, RegexpOp.ANY_CHAR, RegexpOp.ANY_BYTE, RegexpOp.BEGIN_LINE, RegexpOp.END_LINE, RegexpOp.BEGIN_TEXT, RegexpOp.END_TEXT, RegexpOp.WORD_BOUNDARY, RegexpOp.NO_WORD_BOUNDARY, RegexpOp.GRAPHEME_CLUSTER_BOUNDARY, RegexpOp.GRAPHEME_CLUSTER, RegexpOp.CHAR_CLASS, RegexpOp.HAVE_MATCH -> PREC_ATOM
                    RegexpOp.CONCAT, RegexpOp.LITERAL_STRING -> {
                        if (prec < PREC_CONCAT) {
                            sb.append("(?:")
                        }
                        PREC_CONCAT
                    }

                    RegexpOp.ALTERNATE -> {
                        if (prec < PREC_ALTERNATE) {
                            sb.append("(?:")
                        }
                        PREC_ALTERNATE
                    }

                    RegexpOp.NON_CAPTURE -> {
                        sb.append("(?:")
                        PREC_PAREN
                    }

                    RegexpOp.CAPTURE -> {
                        sb.append('(')
                        check(re.cap != 0) { "CAPTURE with cap == 0" }
                        if (re.name != null) {
                            sb.append("?<").append(re.name).append('>')
                        }
                        PREC_PAREN
                    }

                    RegexpOp.STAR, RegexpOp.PLUS, RegexpOp.QUEST, RegexpOp.REPEAT -> {
                        if (prec < PREC_UNARY) {
                            sb.append("(?:")
                        }
                        PREC_ATOM
                    }
                }
            return nprec
        }

        override fun postVisit(
            re: Regexp, parentArg: Int?, preArg: Int?, childArgs: MutableList<Int?>
        ): Int {
            val prec = parentArg ?: PREC_TOPLEVEL
            when (re.op) {
                RegexpOp.NO_MATCH -> sb.append("[^\\x00-\\x{10ffff}]")
                RegexpOp.EMPTY_MATCH -> {
                    if (prec < PREC_EMPTY) {
                        sb.append("(?:)")
                    }
                }

                RegexpOp.LITERAL -> appendLiteral(sb, re.rune, (re.flags and ParseFlags.FOLD_CASE) != 0)
                RegexpOp.LITERAL_STRING -> {
                    for (r in re.runes) {
                        appendLiteral(sb, r, (re.flags and ParseFlags.FOLD_CASE) != 0)
                    }
                    if (prec < PREC_CONCAT) {
                        sb.append(')')
                    }
                }

                RegexpOp.CONCAT -> {
                    if (prec < PREC_CONCAT) {
                        sb.append(')')
                    }
                }

                RegexpOp.ALTERNATE -> {
                    // Children appended '|' after themselves; remove the trailing one.
                    if (sb.length > 0 && sb[sb.length - 1] == '|') {
                        sb.setLength(sb.length - 1)
                    }
                    if (prec < PREC_ALTERNATE) {
                        sb.append(')')
                    }
                }

                RegexpOp.STAR -> {
                    sb.append('*')
                    if ((re.flags and ParseFlags.NON_GREEDY) != 0) {
                        sb.append('?')
                    }
                    if (prec < PREC_UNARY) {
                        sb.append(')')
                    }
                }

                RegexpOp.PLUS -> {
                    sb.append('+')
                    if ((re.flags and ParseFlags.NON_GREEDY) != 0) {
                        sb.append('?')
                    }
                    if (prec < PREC_UNARY) {
                        sb.append(')')
                    }
                }

                RegexpOp.QUEST -> {
                    sb.append('?')
                    if ((re.flags and ParseFlags.NON_GREEDY) != 0) {
                        sb.append('?')
                    }
                    if (prec < PREC_UNARY) {
                        sb.append(')')
                    }
                }

                RegexpOp.REPEAT -> {
                    if (re.max == -1) {
                        sb.append('{').append(re.min).append(",}")
                    } else if (re.min == re.max) {
                        sb.append('{').append(re.min).append('}')
                    } else {
                        sb.append('{').append(re.min).append(',').append(re.max).append('}')
                    }
                    if ((re.flags and ParseFlags.NON_GREEDY) != 0) {
                        sb.append('?')
                    }
                    if (prec < PREC_UNARY) {
                        sb.append(')')
                    }
                }

                RegexpOp.ANY_CHAR -> sb.append('.')
                RegexpOp.ANY_BYTE -> sb.append("\\C")
                RegexpOp.BEGIN_LINE -> sb.append('^')
                RegexpOp.END_LINE -> sb.append('$')
                RegexpOp.BEGIN_TEXT -> sb.append("(?-m:^)")
                RegexpOp.END_TEXT -> {
                    if ((re.flags and ParseFlags.WAS_DOLLAR) != 0) {
                        sb.append("(?-m:$)")
                    } else {
                        sb.append("\\z")
                    }
                }

                RegexpOp.WORD_BOUNDARY -> sb.append("\\b")
                RegexpOp.NO_WORD_BOUNDARY -> sb.append("\\B")
                RegexpOp.GRAPHEME_CLUSTER_BOUNDARY -> sb.append("\\b{g}")
                RegexpOp.GRAPHEME_CLUSTER -> sb.append("\\X")
                RegexpOp.CHAR_CLASS -> appendCharClass(sb, re.charClass!!)
                RegexpOp.NON_CAPTURE -> sb.append(')')
                RegexpOp.CAPTURE -> sb.append(')')
                RegexpOp.HAVE_MATCH -> sb.append("(?HaveMatch:").append(re.matchId).append(')')
            }

            // If the parent is an alternation, append | separator.
            if (prec == PREC_ALTERNATE) {
                sb.append('|')
            }
            return 0
        }
    }

    companion object {
        private val EMPTY_RUNES = IntArray(0)
        // --- Factory methods ---
        /** Creates a NO_MATCH node.  */
        fun noMatch(flags: Int): Regexp {
            return Regexp(RegexpOp.NO_MATCH, flags)
        }

        /** Creates an EMPTY_MATCH node.  */
        fun emptyMatch(flags: Int): Regexp {
            return Regexp(RegexpOp.EMPTY_MATCH, flags)
        }

        /** Creates a LITERAL node matching a single code point.  */
        fun literal(rune: Int, flags: Int): Regexp {
            val re = Regexp(RegexpOp.LITERAL, flags)
            re.rune = rune
            return re
        }

        /** Creates a LITERAL_STRING node matching a sequence of code points.  */
        fun literalString(runes: IntArray, flags: Int): Regexp {
            val re = Regexp(RegexpOp.LITERAL_STRING, flags)
            re.runes = runes.copyOf()
            return re
        }

        /** Creates a CONCAT node matching the concatenation of the given sub-expressions in order.  */
        fun concat(subs: Collection<Regexp?>, flags: Int): Regexp {
            val re = Regexp(RegexpOp.CONCAT, flags)
            re.subs = subs.toMutableList()
            return re
        }

        /** Creates an ALTERNATE node matching any one of the given sub-expressions.  */
        fun alternate(subs: Collection<Regexp?>, flags: Int): Regexp {
            val re = Regexp(RegexpOp.ALTERNATE, flags)
            re.subs = subs.toMutableList()
            return re
        }

        /** Creates a STAR node matching the sub-expression zero or more times.  */
        fun star(sub: Regexp?, flags: Int): Regexp {
            return starPlusOrQuest(RegexpOp.STAR, sub, flags)
        }

        /** Creates a PLUS node matching the sub-expression one or more times.  */
        fun plus(sub: Regexp?, flags: Int): Regexp {
            return starPlusOrQuest(RegexpOp.PLUS, sub, flags)
        }

        /** Creates a QUEST node matching the sub-expression zero or one times.  */
        fun quest(sub: Regexp?, flags: Int): Regexp {
            return starPlusOrQuest(RegexpOp.QUEST, sub, flags)
        }

        /**
         * Creates a STAR, PLUS, or QUEST node, squashing nested quantifiers when possible. Mirrors RE2's
         * `Regexp::StarPlusOrQuest()`.
         */
        private fun starPlusOrQuest(op: RegexpOp, sub: Regexp?, flags: Int): Regexp {
            val s = sub!!
            // Squash **, ++, ??
            if (op == s.op && flags == s.flags) {
                return s
            }
            // Squash *+, *?, +*, +?, ?*, ?+ — all become STAR.
            if ((s.op == RegexpOp.STAR || s.op == RegexpOp.PLUS || s.op == RegexpOp.QUEST)
                && flags == s.flags
            ) {
                if (s.op == RegexpOp.STAR) {
                    return s
                }
                val re = Regexp(RegexpOp.STAR, flags)
                re.subs = mutableListOf(s.sub())
                return re
            }
            return rawQuantifier(op, s, flags)
        }

        /**
         * Creates a STAR, PLUS, or QUEST node without any squashing. Used by the SimplifyWalker's
         * PostVisit which (like the C++ code) creates nodes directly rather than through the squashing
         * factory.
         */
        fun rawQuantifier(op: RegexpOp, sub: Regexp?, flags: Int): Regexp {
            val re = Regexp(op, flags)
            re.subs = mutableListOf(sub!!)
            return re
        }

        /**
         * Creates a REPEAT node matching the sub-expression between `min` and `max` times. A
         * max of -1 means unbounded.
         */
        fun repeat(sub: Regexp?, flags: Int, min: Int, max: Int): Regexp {
            val re = Regexp(RegexpOp.REPEAT, flags)
            re.subs = mutableListOf(sub)
            re.min = min
            re.max = max
            return re
        }

        /** Creates a NON_CAPTURE node preserving a source-level `(?:...)` group boundary.  */
        fun nonCapture(sub: Regexp?, flags: Int): Regexp {
            val re = Regexp(RegexpOp.NON_CAPTURE, flags)
            re.subs = mutableListOf(sub)
            return re
        }

        /**
         * Creates a CAPTURE node wrapping the sub-expression with capture group index `cap` and
         * optional name.
         */
        fun capture(sub: Regexp?, flags: Int, cap: Int, name: String?): Regexp {
            val re = Regexp(RegexpOp.CAPTURE, flags)
            re.subs = mutableListOf(sub)
            re.cap = cap
            re.name = name
            return re
        }

        /** Creates an ANY_CHAR node matching any character.  */
        fun anyChar(flags: Int): Regexp {
            return Regexp(RegexpOp.ANY_CHAR, flags)
        }

        /** Creates a BEGIN_LINE node.  */
        fun beginLine(flags: Int): Regexp {
            return Regexp(RegexpOp.BEGIN_LINE, flags)
        }

        /** Creates an END_LINE node.  */
        fun endLine(flags: Int): Regexp {
            return Regexp(RegexpOp.END_LINE, flags)
        }

        /** Creates a WORD_BOUNDARY node.  */
        fun wordBoundary(flags: Int): Regexp {
            return Regexp(RegexpOp.WORD_BOUNDARY, flags)
        }

        /** Creates a NO_WORD_BOUNDARY node.  */
        fun noWordBoundary(flags: Int): Regexp {
            return Regexp(RegexpOp.NO_WORD_BOUNDARY, flags)
        }

        /** Creates a GRAPHEME_CLUSTER_BOUNDARY node.  */
        fun graphemeClusterBoundary(flags: Int): Regexp {
            return Regexp(RegexpOp.GRAPHEME_CLUSTER_BOUNDARY, flags)
        }

        /** Creates a GRAPHEME_CLUSTER node.  */
        fun graphemeCluster(flags: Int): Regexp {
            return Regexp(RegexpOp.GRAPHEME_CLUSTER, flags)
        }

        /** Creates a BEGIN_TEXT node.  */
        fun beginText(flags: Int): Regexp {
            return Regexp(RegexpOp.BEGIN_TEXT, flags)
        }

        /** Creates an END_TEXT node.  */
        fun endText(flags: Int): Regexp {
            return Regexp(RegexpOp.END_TEXT, flags)
        }

        /** Creates a CHAR_CLASS node.  */
        fun charClass(cc: CharClass, flags: Int): Regexp {
            val re = Regexp(RegexpOp.CHAR_CLASS, flags)
            re.charClass = cc
            return re
        }

        /** Creates a HAVE_MATCH node with the given match ID.  */
        fun haveMatch(matchId: Int, flags: Int): Regexp {
            val re = Regexp(RegexpOp.HAVE_MATCH, flags)
            re.matchId = matchId
            return re
        }

        // Precedence levels for toString(), matching RE2's tostring.cc.
        private const val PREC_ATOM = 0
        private const val PREC_UNARY = 1
        private const val PREC_CONCAT = 2
        private const val PREC_ALTERNATE = 3
        private const val PREC_EMPTY = 4
        private const val PREC_PAREN = 5
        private const val PREC_TOPLEVEL = 6

        /** Appends a literal code point, escaping metacharacters as needed.  */
        private fun appendLiteral(sb: StringBuilder, r: Int, foldCase: Boolean) {
            if (r != 0 && r < 0x80 && "(){}[]*+?|.^$\\".indexOf(r.toChar()) >= 0) {
                sb.append('\\')
                sb.append(r.toChar())
            } else if (foldCase && Ascii.isLower(r)) {
                val upper = Ascii.toUpperCase(r)
                sb.append('[')
                sb.append(upper.toChar())
                sb.append(r.toChar())
                sb.append(']')
            } else {
                appendCCRange(sb, r, r)
            }
        }

        /** Appends a full character class, using negation heuristic based on 0xFFFE membership.  */
        private fun appendCharClass(sb: StringBuilder, cc: CharClass) {
            if (cc.numRanges() == 0) {
                sb.append("[^\\x00-\\x{10ffff}]")
                return
            }
            sb.append('[')
            val negated = cc.contains(0xFFFE)
            var display = cc
            if (negated) {
                display = cc.negate()
                sb.append('^')
            }
            for (i in 0..<display.numRanges()) {
                appendCCRange(sb, display.lo(i), display.hi(i))
            }
            sb.append(']')
        }

        /** Appends a character class range [lo, hi].  */
        private fun appendCCRange(sb: StringBuilder, lo: Int, hi: Int) {
            if (lo > hi) {
                return
            }
            appendCCChar(sb, lo)
            if (lo < hi) {
                sb.append('-')
                appendCCChar(sb, hi)
            }
        }

        /** Appends a single code point formatted for use inside a character class.  */
        private fun appendCCChar(sb: StringBuilder, r: Int) {
            if (0x20 <= r && r <= 0x7E) {
                if ("[]^-\\".indexOf(r.toChar()) >= 0) {
                    sb.append('\\')
                }
                sb.append(r.toChar())
                return
            }
            when (r) {
                0x0D -> {
                    sb.append("\\r")
                    return
                }

                0x09 -> {
                    sb.append("\\t")
                    return
                }

                0x0A -> {
                    sb.append("\\n")
                    return
                }

                0x0C -> {
                    sb.append("\\f")
                    return
                }

                else -> {}
            }
            if (r < 0x100) {
                sb.append("\\x").append(r.toString(16).padStart(2, '0'))
            } else {
                sb.append("\\x{").append(r.toString(16)).append('}')
            }
        }
    }
}
