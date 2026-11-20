// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.jvm.JvmRecord

/**
 * Simplifies a [Regexp] AST using basic operators such as [RegexpOp.STAR], [ ][RegexpOp.PLUS], [RegexpOp.QUEST], and [RegexpOp.CONCAT]. Nullable finite [ ][RegexpOp.REPEAT] nodes with optional iterations retain their invocation boundaries for the
 * compiler. Also simplifies character classes (empty → NO_MATCH, full → ANY_CHAR).
 * 
 * 
 * The simplification is done in two passes:
 * 
 * 
 *  1. **Coalesce**: Merges adjacent repetitions of the same sub-expression in CONCAT nodes
 * (e.g., `a*a+` → `REPEAT(a, 1, -1)`).
 *  1. **Simplify**: Lowers copy-equivalent REPEAT nodes into STAR/PLUS/QUEST/CONCAT, preserves
 * progress-sensitive counts, and simplifies character classes.
 * 
 * 
 * 
 * This is a port of RE2's `simplify.cc`.
 */
internal object Simplifier {
    /** Returns a simplified copy of the given regexp.  */
    fun simplify(re: Regexp?): Regexp? {
        // Pass 1: coalesce adjacent repeats.
        val cw = CoalesceWalker()
        val cre = cw.walk(re, null)
        if (cre == null || cw.stoppedEarly()) {
            return null
        }
        // Pass 2: simplify repeats and char classes.
        val sw = SimplifyWalker()
        val sre = sw.walk(cre, null)
        if (sre == null || sw.stoppedEarly()) {
            return null
        }
        return sre
    }

    // ---------------------------------------------------------------------------
    // Structural equality (port of Regexp::Equal from regexp.cc)
    // ---------------------------------------------------------------------------
    /** Returns true if the two regexps are structurally equal.  */ // Switches mirror the C++ RE2 Regexp::Equal structure and are clearer as statement switches.
    fun equal(a: Regexp?, b: Regexp?): Boolean {
        var a = a
        var b = b
        if (a == b) {
            return true
        }
        if (a == null || b == null) {
            return a == b
        }
        if (!topEqual(a, b)) {
            return false
        }

        // For leaf nodes, topEqual is sufficient.
        when (a.op) {
            RegexpOp.ALTERNATE, RegexpOp.CONCAT, RegexpOp.STAR, RegexpOp.PLUS, RegexpOp.QUEST, RegexpOp.REPEAT, RegexpOp.NON_CAPTURE, RegexpOp.CAPTURE -> {}
            else -> return true
        }

        // Recursively compare children using an explicit stack.
        val stk = ArrayDeque<Regexp>()
        while (true) {
            when (a!!.op) {
                RegexpOp.ALTERNATE, RegexpOp.CONCAT -> {
                    for (i in 0 until a.subs!!.size) {
                        val a2 = a.subs!![i]!!
                        val b2 = b!!.subs!![i]!!
                        if (!topEqual(a2, b2)) {
                            return false
                        }
                        stk.addLast(a2)
                        stk.addLast(b2)
                    }
                }

                RegexpOp.STAR, RegexpOp.PLUS, RegexpOp.QUEST, RegexpOp.REPEAT, RegexpOp.NON_CAPTURE, RegexpOp.CAPTURE -> {
                    val a2 = a.subs!![0]!!
                    val b2 = b!!.subs!![0]!!
                    if (!topEqual(a2, b2)) {
                        return false
                    }
                    a = a2
                    b = b2
                    continue
                }

                else -> {}
            }

            if (stk.isEmpty()) {
                break
            }
            b = stk.removeLast()
            a = stk.removeLast()
        }
        return true
    }

    /** Compares top-level structure of two Regexp nodes (not their children).  */ // Switch mirrors the C++ RE2 structure and is clearer as a statement switch.
    private fun topEqual(a: Regexp, b: Regexp): Boolean {
        if (a.op != b.op) {
            return false
        }
        when (a.op) {
            RegexpOp.NO_MATCH, RegexpOp.EMPTY_MATCH, RegexpOp.ANY_CHAR, RegexpOp.ANY_BYTE, RegexpOp.BEGIN_LINE, RegexpOp.END_LINE, RegexpOp.WORD_BOUNDARY, RegexpOp.NO_WORD_BOUNDARY, RegexpOp.GRAPHEME_CLUSTER_BOUNDARY, RegexpOp.BEGIN_TEXT -> return true

            RegexpOp.END_TEXT -> return ((a.flags xor b.flags) and ParseFlags.WAS_DOLLAR) == 0

            RegexpOp.LITERAL -> return a.rune == b.rune && ((a.flags xor b.flags) and ParseFlags.FOLD_CASE) == 0

            RegexpOp.LITERAL_STRING -> return a.runes.contentEquals(b.runes) && ((a.flags xor b.flags) and ParseFlags.FOLD_CASE) == 0

            RegexpOp.ALTERNATE, RegexpOp.CONCAT -> return a.subs!!.size == b.subs!!.size

            RegexpOp.STAR, RegexpOp.PLUS, RegexpOp.QUEST -> return ((a.flags xor b.flags) and ParseFlags.NON_GREEDY) == 0

            RegexpOp.REPEAT -> return ((a.flags xor b.flags) and ParseFlags.NON_GREEDY) == 0 && a.min == b.min && a.max == b.max

            RegexpOp.NON_CAPTURE -> return true

            RegexpOp.CAPTURE -> return a.cap == b.cap && a.name == b.name

            RegexpOp.HAVE_MATCH -> return a.matchId == b.matchId

            RegexpOp.CHAR_CLASS -> return a.charClass!!.flatRanges().contentEquals(b.charClass!!.flatRanges())

            else -> return false
        }
    }

    /**
     * Removes unobservable chunk boundaries around a greedily repeated consuming atom.
     * 
     * 
     * With inner minimum zero or one, every remaining atom can start another chunk, so both greedy
     * loops try the same distinct consumed prefixes in descending order. Captures around the atom are
     * written at the same positions regardless of partition. A capture around a chunk, nullable or
     * ambiguous operands, reluctant ordering, or a higher inner minimum invalidates that argument:
     * for example, `(?:a{3,4})*` first consumes four characters on six a's.
     */
    private fun coalesceGreedyAtomRepeat(outer: Regexp, sub: Regexp): Regexp? {
        var sub = sub
        if ((outer.op != RegexpOp.STAR && outer.op != RegexpOp.PLUS) || outer.nonGreedy()) {
            return null
        }
        while (sub.op == RegexpOp.NON_CAPTURE) {
            sub = sub.sub()
        }
        if (sub.op != RegexpOp.REPEAT || sub.nonGreedy() || sub.min > 1 || sub.max == 0) {
            return null
        }
        val atom = sub.sub()
        var leaf = atom
        while (leaf.op == RegexpOp.NON_CAPTURE || leaf.op == RegexpOp.CAPTURE) {
            leaf = leaf.sub()
        }
        val consumingAtom =
            leaf.op == RegexpOp.LITERAL || (leaf.op == RegexpOp.LITERAL_STRING && leaf.runes.size > 0)
                    || leaf.op == RegexpOp.CHAR_CLASS || leaf.op == RegexpOp.ANY_CHAR
        if (!consumingAtom) {
            return null
        }
        val op = if (outer.op == RegexpOp.PLUS && sub.min == 1) RegexpOp.PLUS else RegexpOp.STAR
        return Regexp.rawQuantifier(op, atom, outer.flags)
    }

    /** Returns true if the child args differ from the original subs.  */
    private fun childArgsChanged(re: Regexp, childArgs: MutableList<Regexp?>): Boolean {
        for (i in childArgs.indices) {
            if (childArgs[i] !== re.subs!![i]) {
                return true
            }
        }
        return false
    }

    /** Creates a copy of `re` with new sub-expressions, preserving op-specific fields.  */ // Switch mirrors the C++ RE2 structure and is clearer as a statement switch.
    private fun copyWithNewSubs(re: Regexp, newSubs: MutableList<Regexp?>): Regexp {
        when (re.op) {
            RegexpOp.REPEAT -> return Regexp.repeat(newSubs[0]!!, re.flags, re.min, re.max)
            RegexpOp.NON_CAPTURE -> return Regexp.nonCapture(newSubs[0]!!, re.flags)
            RegexpOp.CAPTURE -> return Regexp.capture(newSubs[0]!!, re.flags, re.cap, re.name)
            RegexpOp.STAR -> return Regexp.star(newSubs[0]!!, re.flags)
            RegexpOp.PLUS -> return Regexp.plus(newSubs[0]!!, re.flags)
            RegexpOp.QUEST -> return Regexp.quest(newSubs[0]!!, re.flags)
            RegexpOp.ALTERNATE -> return Regexp.alternate(newSubs, re.flags)
            RegexpOp.CONCAT -> return Regexp.concat(newSubs, re.flags)
            else -> throw IllegalArgumentException("Unexpected op: " + re.op)
        }
    }

    /**
     * Returns true if r1 and r2 can be coalesced. r1 must be a quantifier (STAR/PLUS/QUEST/REPEAT) of
     * a LITERAL, CHAR_CLASS, ANY_CHAR, or ANY_BYTE, and r2 must be a compatible quantifier of the
     * same operand, or the operand itself, or a LITERAL_STRING beginning with the same literal.
     */
    private fun canCoalesce(r1: Regexp, r2: Regexp): Boolean {
        if (r1.op != RegexpOp.STAR && r1.op != RegexpOp.PLUS && r1.op != RegexpOp.QUEST && r1.op != RegexpOp.REPEAT) {
            return false
        }
        val r1sub: Regexp = r1.subs!![0]!!
        if (r1sub.op != RegexpOp.LITERAL && r1sub.op != RegexpOp.CHAR_CLASS && r1sub.op != RegexpOp.ANY_CHAR && r1sub.op != RegexpOp.ANY_BYTE) {
            return false
        }

        // r2 is a quantifier of the same thing.
        if ((r2.op == RegexpOp.STAR || r2.op == RegexpOp.PLUS || r2.op == RegexpOp.QUEST || r2.op == RegexpOp.REPEAT)
            && equal(r1sub, r2.subs!!.first())
            && ((r1.flags and ParseFlags.NON_GREEDY) == (r2.flags and ParseFlags.NON_GREEDY))
        ) {
            return true
        }

        // r2 is the operand itself.
        if (equal(r1sub, r2)) {
            return true
        }

        // r2 is a literal string beginning with r1's literal.
        if (r1sub.op == RegexpOp.LITERAL && r2.op == RegexpOp.LITERAL_STRING && r2.runes.size > 0 && r2.runes[0] == r1sub.rune && ((r1sub.flags and ParseFlags.FOLD_CASE) == (r2.flags and ParseFlags.FOLD_CASE))) {
            return true
        }

        return false
    }

    /**
     * Coalesces r1 and r2 into a single repeat. Returns a [CoalescePair]: the first element
     * replaces r1's position, the second replaces r2's position. One of them will typically be
     * EMPTY_MATCH.
     */
    // Switches assign min/max from two separate enum dispatches; clearer as statement switches.
    private fun doCoalesce(r1: Regexp, r2: Regexp): CoalescePair {
        var min: Int
        var max: Int
        val operand: Regexp? = r1.subs!!.first()

        when (r1.op) {
            RegexpOp.STAR -> {
                min = 0
                max = -1
            }

            RegexpOp.PLUS -> {
                min = 1
                max = -1
            }

            RegexpOp.QUEST -> {
                min = 0
                max = 1
            }

            RegexpOp.REPEAT -> {
                min = r1.min
                max = r1.max
            }

            else -> throw IllegalArgumentException("Bad r1 op: " + r1.op)
        }

        when (r2.op) {
            RegexpOp.STAR -> {
                max = -1
                return leaveEmpty(operand, r1.flags, min, max)
            }

            RegexpOp.PLUS -> {
                min++
                max = -1
                return leaveEmpty(operand, r1.flags, min, max)
            }

            RegexpOp.QUEST -> {
                if (max != -1) {
                    max++
                }
                return leaveEmpty(operand, r1.flags, min, max)
            }

            RegexpOp.REPEAT -> {
                min += r2.min
                if (r2.max == -1) {
                    max = -1
                } else if (max != -1) {
                    max += r2.max
                }
                return leaveEmpty(operand, r1.flags, min, max)
            }

            RegexpOp.LITERAL, RegexpOp.CHAR_CLASS, RegexpOp.ANY_CHAR, RegexpOp.ANY_BYTE -> {
                min++
                if (max != -1) {
                    max++
                }
                return leaveEmpty(operand, r1.flags, min, max)
            }

            RegexpOp.LITERAL_STRING -> {
                val r: Int = r1.subs!![0]!!.rune
                var n = 1
                while (n < r2.runes.size && r2.runes[n] == r) {
                    n++
                }
                min += n
                if (max != -1) {
                    max += n
                }
                if (n == r2.runes.size) {
                    return leaveEmpty(operand, r1.flags, min, max)
                }
                // Partial match: the repeat goes first, remainder of literal string second.
                val nre: Regexp = Regexp.repeat(operand, r1.flags, min, max)
                val remainRunes = r2.runes.copyOfRange(n, r2.runes.size)
                val remainder: Regexp = Regexp.literalString(remainRunes, r2.flags)
                return CoalescePair(nre, remainder)
            }

            else -> throw IllegalArgumentException("Bad r2 op: " + r2.op)
        }
    }

    /**
     * Returns a [CoalescePair] of [EMPTY_MATCH, REPEAT(operand, min, max)]. The first element
     * replaces r1 (empty placeholder), and the second replaces r2 (the coalesced repeat).
     */
    private fun leaveEmpty(operand: Regexp?, flags: Int, min: Int, max: Int): CoalescePair {
        return CoalescePair(
            Regexp.emptyMatch(ParseFlags.NONE), Regexp.repeat(operand, flags, min, max)
        )
    }

    /**
     * Determines whether a Regexp is already "simple" (no REPEAT, no empty/full char classes, no
     * nested quantifiers on quantifiers).
     */
    private fun computeSimple(re: Regexp): Boolean {
        val stack = ArrayDeque<Regexp>()
        stack.addLast(re)
        while (!stack.isEmpty()) {
            val node = stack.removeLast()
            when (node.op) {
                RegexpOp.NO_MATCH, RegexpOp.EMPTY_MATCH, RegexpOp.LITERAL, RegexpOp.LITERAL_STRING, RegexpOp.BEGIN_LINE, RegexpOp.END_LINE, RegexpOp.BEGIN_TEXT, RegexpOp.WORD_BOUNDARY, RegexpOp.NO_WORD_BOUNDARY, RegexpOp.GRAPHEME_CLUSTER_BOUNDARY, RegexpOp.END_TEXT, RegexpOp.ANY_CHAR, RegexpOp.ANY_BYTE, RegexpOp.HAVE_MATCH -> {}
                RegexpOp.CONCAT, RegexpOp.ALTERNATE -> for (sub in node.subs!!) {
                    stack.addLast(sub!!)
                }

                RegexpOp.CHAR_CLASS -> if (node.charClass!!.isEmpty() || isFull(node.charClass!!)) {
                    return false
                }

                RegexpOp.CAPTURE -> stack.addLast(node.subs!![0]!!)
                RegexpOp.STAR, RegexpOp.PLUS, RegexpOp.QUEST -> {
                    val sub: Regexp = node.subs!![0]!!
                    val subOp = sub.op
                    if (subOp == RegexpOp.STAR || subOp == RegexpOp.PLUS || subOp == RegexpOp.QUEST || subOp == RegexpOp.EMPTY_MATCH || subOp == RegexpOp.NO_MATCH) {
                        return false
                    }
                    stack.addLast(sub)
                }

                RegexpOp.NON_CAPTURE, RegexpOp.REPEAT -> return false
                else -> return false
            }
        }
        return true
    }

    /**
     * Creates a STAR/PLUS/QUEST node without any cross-op squashing. Used by the SimplifyWalker's
     * postVisit to match the C++ behavior (which also creates nodes directly).
     */
    private fun rawQuantifier(op: RegexpOp, sub: Regexp, flags: Int): Regexp {
        return Regexp.rawQuantifier(op, sub, flags)
    }

    /** Returns true if the character class matches every Java string code point.  */
    private fun isFull(cc: CharClass): Boolean {
        return cc.numRanges() == 1 && cc.lo(0) == 0 && cc.hi(0) == Utils.MAX_RUNE
    }

    /**
     * Simplifies `re{min,max}`, retaining nullable finite counts that need progress checks.
     * 
     * 
     * Zero-width operands have already been reduced to at most one iteration by the caller.
     */
    private fun simplifyRepeat(re: Regexp, min: Int, max: Int, flags: Int): Regexp {
        if (max > min && max > 1 && AstAnalysis.analyze(re).canMatchEmpty) {
            // The final required iteration and optional iterations share one empty-stop invocation.
            // Preserve its bounds for the compiler instead of turning it into independent copies.
            return Regexp.repeat(re, flags, min, max)
        }

        // x{n,} means at least n matches.
        if (max == -1) {
            if (min == 0) {
                return starPlusOrQuest(RegexpOp.STAR, re, flags)
            }
            if (min == 1) {
                return starPlusOrQuest(RegexpOp.PLUS, re, flags)
            }
            // General case: x{4,} is (?:x)(?:x)(?:x)(x)+. Keep captures in every
            // copy: the language-only optimization of stripping captures from the
            // mandatory prefix changes JDK-visible group state for quantified captures.
            val subs: MutableList<Regexp> = ArrayList<Regexp>(min)
            for (i in 0 until min - 1) {
                subs.add(re)
            }
            subs.add(starPlusOrQuest(RegexpOp.PLUS, re, flags))
            return Regexp.concat(subs, flags)
        }

        // x{0} → empty match
        if (min == 0 && max == 0) {
            return Regexp.emptyMatch(flags)
        }

        // x{1} → x
        if (min == 1 && max == 1) {
            return re
        }

        // General case: x{n,m} means n copies of x and (m-n) nested x?
        // x{2,5} = xx(x(x(x)?)?)?
        var nre: Regexp? = null
        if (min > 0) {
            val subs: MutableList<Regexp> = ArrayList<Regexp>(min)
            for (i in 0 until min) {
                subs.add(re)
            }
            nre = Regexp.concat(subs, flags)
        }

        if (max > min) {
            // Each nested suffix is built only from copies of re, so it has a visible capture exactly
            // when re does. Checking once keeps x{0,n} linear instead of rescanning the growing suffix.
            val reHasVisibleCapture = hasVisibleCapture(re)
            var suf = starPlusOrQuest(RegexpOp.QUEST, re, flags, reHasVisibleCapture)
            for (i in min + 1 until max) {
                suf =
                    starPlusOrQuest(
                        RegexpOp.QUEST,
                        Regexp.concat(listOf(re, suf), flags),
                        flags,
                        reHasVisibleCapture,
                    )
            }
            if (nre == null) {
                nre = suf
            } else {
                nre = Regexp.concat(listOf(nre, suf), flags)
            }
        }

        if (nre == null) {
            return Regexp.noMatch(flags)
        }
        return nre
    }

    /** Simplifies a character class: empty → NO_MATCH, full → ANY_CHAR.  */
    private fun simplifyCharClass(re: Regexp): Regexp {
        if (re.charClass!!.isEmpty()) {
            return Regexp.noMatch(re.flags)
        }
        if (isFull(re.charClass!!)) {
            return Regexp.anyChar(re.flags)
        }
        return re
    }

    /**
     * Like [.starPlusOrQuest], with the caller supplying whether `sub` contains a visible capture.
     */
    // Switch mirrors the C++ RE2 structure and is clearer as a statement switch.
    /**
     * Creates a STAR, PLUS, or QUEST node, squashing nested quantifiers when possible. This mirrors
     * RE2's `StarPlusOrQuest()` factory method.
     * 
     * 
     *  * Same op + same flags → return sub unchanged (e.g., STAR(STAR(x)) → STAR(x))
     *  * Different quantifier ops + same flags → return STAR(x)
     * 
     */
    private fun starPlusOrQuest(
        op: RegexpOp, sub: Regexp, flags: Int, subHasVisibleCapture: Boolean = hasVisibleCapture(sub)
    ): Regexp {
        if (subHasVisibleCapture) {
            return rawQuantifier(op, sub, flags)
        }
        // Squash identical: **, ++, ??
        if (op == sub.op && flags == sub.flags) {
            return sub
        }
        // Squash mixed: *+, *?, +*, +?, ?*, ?+ all → *
        if ((sub.op == RegexpOp.STAR || sub.op == RegexpOp.PLUS || sub.op == RegexpOp.QUEST)
            && flags == sub.flags
        ) {
            if (sub.op == RegexpOp.STAR) {
                return sub
            }
            return Regexp.star(sub.subs!!.first(), flags)
        }
        when (op) {
            RegexpOp.STAR -> return Regexp.star(sub, flags)
            RegexpOp.PLUS -> return Regexp.plus(sub, flags)
            RegexpOp.QUEST -> return Regexp.quest(sub, flags)
            else -> throw IllegalArgumentException("Bad op: " + op)
        }
    }

    private fun hasVisibleCapture(re: Regexp?): Boolean {
        return HasVisibleCaptureWalker().walk(re, false)!!
    }

    // ---------------------------------------------------------------------------
    // CoalesceWalker
    // ---------------------------------------------------------------------------
    /**
     * Merges adjacent repetitions of the same sub-expression in CONCAT nodes. For example, `a*a+` becomes `REPEAT(a, 1, -1)`.
     */
    private class CoalesceWalker : Walker<Regexp?>() {
        override fun copy(re: Regexp?): Regexp? {
            return re // No ref-counting in Java.
        }

        override fun shortVisit(re: Regexp, parentArg: Regexp?): Regexp {
            return re
        }

        override fun postVisit(
            re: Regexp,
            parentArg: Regexp?,
            preArg: Regexp?,
            childArgs: MutableList<Regexp?>
        ): Regexp {
            if (childArgs.isEmpty()) {
                return re
            }

            val repeatedAtom = coalesceGreedyAtomRepeat(re, childArgs[0]!!)
            if (repeatedAtom != null) {
                return repeatedAtom
            }

            if (re.op != RegexpOp.CONCAT) {
                if (!childArgsChanged(re, childArgs)) {
                    return re
                }
                // Something changed. Build a new node.
                return copyWithNewSubs(re, childArgs)
            }

            // CONCAT: check if any adjacent pair can coalesce.
            var canCoalesce = false
            var i = 0
            while (i + 1 < childArgs.size) {
                if (canCoalesce(childArgs[i]!!, childArgs[i + 1]!!)) {
                    canCoalesce = true
                    break
                }
                i++
            }

            if (!canCoalesce) {
                if (!childArgsChanged(re, childArgs)) {
                    return re
                }
                return Regexp.concat(childArgs, re.flags)
            }

            // Do the coalescing in-place on childArgs.
            i = 0
            while (i + 1 < childArgs.size) {
                if (canCoalesce(childArgs[i]!!, childArgs[i + 1]!!)) {
                    val pair = doCoalesce(childArgs[i]!!, childArgs[i + 1]!!)
                    childArgs[i] = pair.first!!
                    childArgs[i + 1] = pair.second!!
                }
                i++
            }

            // Build new CONCAT without the empty matches left by coalescing.
            val newSubs: MutableList<Regexp> = ArrayList<Regexp>(childArgs.size)
            for (j in childArgs.indices) {
                val cj = childArgs[j]!!
                if (cj.op != RegexpOp.EMPTY_MATCH) {
                    newSubs.add(cj)
                }
            }
            if (newSubs.size == 1) {
                return newSubs.first()
            }
            return Regexp.concat(newSubs, re.flags)
        }
    }

    /** A pair of coalesced regexp results.  */
    @JvmRecord
    private data class CoalescePair(val first: Regexp?, val second: Regexp?)

    // ---------------------------------------------------------------------------
    // SimplifyWalker
    // ---------------------------------------------------------------------------
    /** Converts REPEAT nodes to STAR/PLUS/QUEST/CONCAT and simplifies character classes.  */
    private class SimplifyWalker : Walker<Regexp?>() {
        private var zeroWidth: ZeroWidthWalker? = null

        override fun copy(re: Regexp?): Regexp? {
            return re
        }

        override fun shortVisit(re: Regexp, parentArg: Regexp?): Regexp {
            return re
        }

        override fun preVisit(re: Regexp, parentArg: Regexp?, stop: BooleanArray): Regexp? {
            if (computeSimple(re)) {
                stop[0] = true
                return re
            }
            return null
        }

        override fun postVisit(
            re: Regexp,
            parentArg: Regexp?,
            preArg: Regexp?,
            childArgs: MutableList<Regexp?>
        ): Regexp? {
            when (re.op) {
                RegexpOp.NO_MATCH, RegexpOp.EMPTY_MATCH, RegexpOp.LITERAL, RegexpOp.LITERAL_STRING, RegexpOp.BEGIN_LINE, RegexpOp.END_LINE, RegexpOp.BEGIN_TEXT, RegexpOp.WORD_BOUNDARY, RegexpOp.NO_WORD_BOUNDARY, RegexpOp.GRAPHEME_CLUSTER_BOUNDARY, RegexpOp.END_TEXT, RegexpOp.ANY_CHAR, RegexpOp.ANY_BYTE, RegexpOp.HAVE_MATCH -> return re

                RegexpOp.CONCAT, RegexpOp.ALTERNATE -> {
                    if (!childArgsChanged(re, childArgs)) {
                        return re
                    }
                    if (re.op == RegexpOp.CONCAT) {
                        return Regexp.concat(childArgs, re.flags)
                    } else {
                        return Regexp.alternate(childArgs, re.flags)
                    }
                }

                RegexpOp.NON_CAPTURE -> return childArgs.get(0)

                RegexpOp.CAPTURE -> {
                    val newsub: Regexp = childArgs[0]!!
                    if (newsub === re.subs!![0]) {
                        return re
                    }
                    return Regexp.capture(newsub, re.flags, re.cap, re.name)
                }

                RegexpOp.STAR, RegexpOp.PLUS, RegexpOp.QUEST -> {
                    val newsub: Regexp = childArgs[0]!!
                    // Repeat of empty string is still empty string.
                    if (newsub.op == RegexpOp.EMPTY_MATCH) {
                        return newsub
                    }
                    if (newsub === re.subs!![0]) {
                        return re
                    }
                    // Idempotent squashing: e.g. STAR(STAR(x)) → STAR(x).
                    if (re.op == newsub.op && re.flags == newsub.flags) {
                        return newsub
                    }
                    return starPlusOrQuest(re.op, newsub, re.flags)
                }

                RegexpOp.REPEAT -> {
                    val newsub: Regexp = childArgs[0]!!
                    if (newsub.op == RegexpOp.EMPTY_MATCH) {
                        return newsub
                    }
                    if (re.max != 0 && (re.min > 1 || re.max > 1)) {
                        if (zeroWidth == null) {
                            zeroWidth = ZeroWidthWalker()
                        }
                        if (zeroWidth!!.walk(newsub, false) == true) {
                            // At a fixed position, another zero-width iteration makes the same choices and
                            // writes the same capture bounds. Keep one required or optional iteration, so
                            // reluctant zero-minimum repeats can still omit it and failed assertions can
                            // still reject a required iteration. Never suppress the iteration's captures.
                            return if (re.min == 0) rawQuantifier(RegexpOp.QUEST, newsub, re.flags) else newsub
                        }
                    }
                    return simplifyRepeat(newsub, re.min, re.max, re.flags)
                }

                RegexpOp.CHAR_CLASS -> return simplifyCharClass(re)

                else -> return re
            }
        }
    }

    /** Classifies zero-width operands without rescanning shared or nested simplified subtrees.  */
    private class ZeroWidthWalker : Walker<Boolean?>() {
        // Regexp does not override equals/hashCode; HashMap is identity-keyed here.
        private val known = HashMap<Regexp?, Boolean?>()

        override fun preVisit(re: Regexp, parentArg: Boolean?, stop: BooleanArray): Boolean? {
            val result = known.get(re)
            if (result != null) {
                stop[0] = true
                return result
            }
            return when (re.op) {
                RegexpOp.EMPTY_MATCH, RegexpOp.BEGIN_LINE, RegexpOp.END_LINE, RegexpOp.BEGIN_TEXT, RegexpOp.END_TEXT, RegexpOp.WORD_BOUNDARY, RegexpOp.NO_WORD_BOUNDARY, RegexpOp.GRAPHEME_CLUSTER_BOUNDARY, RegexpOp.CAPTURE, RegexpOp.NON_CAPTURE, RegexpOp.CONCAT, RegexpOp.ALTERNATE, RegexpOp.STAR, RegexpOp.PLUS, RegexpOp.QUEST -> null
                else -> {
                    stop[0] = true
                    known.put(re, false)
                    false
                }
            }
        }

        override fun postVisit(
            re: Regexp, parentArg: Boolean?, preArg: Boolean?, childArgs: MutableList<Boolean?>
        ): Boolean {
            val result = !childArgs.contains(false)
            known.put(re, result)
            return result
        }

        override fun shortVisit(re: Regexp, parentArg: Boolean?): Boolean {
            return false
        }
    }

    private class HasVisibleCaptureWalker : Walker<Boolean?>() {
        override fun shortVisit(re: Regexp, parentArg: Boolean?): Boolean {
            return false
        }

        override fun postVisit(
            re: Regexp, parentArg: Boolean?, preArg: Boolean?, childArgs: MutableList<Boolean?>
        ): Boolean {
            if (re.op == RegexpOp.CAPTURE && re.cap > 0) {
                return true
            }
            for (childHasCapture in childArgs) {
                if (childHasCapture == true) {
                    return true
                }
            }
            return false
        }
    }
}
