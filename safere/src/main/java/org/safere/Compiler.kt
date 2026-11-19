// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*

/**
 * Compiles a [Regexp] AST into a [Prog] (bytecode program) via Thompson NFA
 * construction. Each node in the AST is compiled into a [Frag] (instruction fragment) and
 * fragments are combined using standard NFA construction rules.
 * 
 * 
 * This is a port of RE2's `compile.cc`, adapted for Java's Unicode code point model
 * (instead of UTF-8 byte ranges).
 * 
 * 
 * Usage:
 * 
 * <pre>
 * Regexp re = Parser.parse("a(b|c)*d", flags);
 * Prog prog = Compiler.compile(re);
</pre> * 
 */
internal class Compiler private constructor(private val maxInst: Int = DEFAULT_MAX_INST) : Walker<Compiler.Frag>() {
    private val prog: Prog
    private var failed = false
    private var reversed = false
    private var forDfa = false

    private var nextLoopReg = 0

    init {
        this.prog = Prog()
        // Instruction 0 is always the fail instruction (sentinel for PatchList).
        val fail = prog.allocInst()
        prog.mutableInst(fail).initFail()
    }

    /**
     * Direction-independent preparation shared by capture-aware and DFA compilers.
     * 
     * 
     * The prepared tree is encapsulated and read-only during compilation. Instruction arrays,
     * patch lists, and loop registers belong to each individual compilation. Pattern construction
     * releases this preparation after building the programs needed at construction time.
     */
    internal class Prepared private constructor(
        private val root: Regexp?,
        private val simplifiedSource: Regexp?,
        private val numCaptures: Int,
        private val anchorStart: Boolean,
        private val anchorEnd: Boolean,
        private val dollarEnd: Boolean,
        private val dollarUnixLines: Boolean
    ) {
        /** Returns `Simplifier.simplify(source)`, before anchor stripping.  */
        fun simplifiedSource(): Regexp? {
            return simplifiedSource
        }
    }

    // ---------------------------------------------------------------------------
    // PatchList — linked list threaded through unused instruction out fields
    // ---------------------------------------------------------------------------
    /**
     * A patch list is a linked list of instruction output fields that need to be filled in
     * ("patched") to point to the next instruction. The list is threaded through the unused out/out1
     * fields of the instructions themselves.
     * 
     * 
     * A patch list is packed into a primitive `long`: upper 32 bits are `head`, lower
     * 32 bits are `tail`.
     */
    private object PatchList {
        const val EMPTY: Long = 0L

        fun mk(encoded: Int): Long {
            return (encoded.toLong() shl 32) or (encoded.toLong() and 0xFFFFFFFFL)
        }

        fun head(l: Long): Int {
            return (l ushr 32).toInt()
        }

        fun tail(l: Long): Int {
            return l.toInt()
        }

        fun patch(prog: Prog, l: Long, target: Int) {
            var current: Int = head(l)
            while (current != 0) {
                val ip = prog.mutableInst(current shr 1)
                if ((current and 1) != 0) {
                    current = ip.out1
                    ip.out1 = target
                } else {
                    current = ip.out
                    ip.out = target
                }
            }
        }

        fun append(prog: Prog, l1: Long, l2: Long): Long {
            val h1: Int = head(l1)
            if (h1 == 0) {
                return l2
            }
            val h2: Int = head(l2)
            if (h2 == 0) {
                return l1
            }
            val t1: Int = tail(l1)
            val ip = prog.mutableInst(t1 shr 1)
            if ((t1 and 1) != 0) {
                ip.out1 = h2
            } else {
                ip.out = h2
            }
            val t2: Int = tail(l2)
            return (h1.toLong() shl 32) or (t2.toLong() and 0xFFFFFFFFL)
        }
    }

    // ---------------------------------------------------------------------------
    // Frag — compiled program fragment
    // ---------------------------------------------------------------------------
    /**
     * A compiled program fragment with a beginning instruction, a patch list of unfilled outputs, and
     * a nullable flag.
     */
    @JvmRecord
    internal data class Frag(val begin: Int, val end: Long, val nullable: Boolean) {
        companion object {
            val NO_MATCH: Frag = Frag(0, PatchList.EMPTY, false)
        }
    }

    // ---------------------------------------------------------------------------
    // Fragment combinators
    // ---------------------------------------------------------------------------
    private fun allocInst(): Int {
        if (failed || prog.size() >= maxInst) {
            failed = true
            return -1
        }
        return prog.allocInst()
    }

    private fun cat(a: Frag, b: Frag): Frag {
        if (isNoMatch(a) || isNoMatch(b)) {
            return Frag.Companion.NO_MATCH
        }

        // Elide no-op.
        val begin = prog.mutableInst(a.begin)
        if (begin.op == InstOp.NOP && PatchList.head(a.end) == (a.begin shl 1) && begin.out == 0) {
            PatchList.patch(prog, a.end, b.begin)
            return b
        }

        if (reversed) {
            PatchList.patch(prog, b.end, a.begin)
            return Frag(b.begin, a.end, b.nullable && a.nullable)
        }

        PatchList.patch(prog, a.end, b.begin)
        return Frag(a.begin, b.end, a.nullable && b.nullable)
    }

    private fun alt(a: Frag, b: Frag): Frag {
        if (isNoMatch(a)) {
            return b
        }
        if (isNoMatch(b)) {
            return a
        }

        val id = allocInst()
        if (id < 0) {
            return Frag.Companion.NO_MATCH
        }

        prog.mutableInst(id).initAlt(a.begin, b.begin)
        return Frag(id, PatchList.append(prog, a.end, b.end), a.nullable || b.nullable)
    }

    private fun plus(a: Frag, nongreedy: Boolean): Frag {
        // When the body is nullable (can match zero characters), insert a PROGRESS_CHECK at the
        // loop entry to implement the JDK "zero-width match breaks repetition" rule.
        //
        // Fresh entry resets the loop register; the body end loops back to PROGRESS_CHECK directly.
        // An enclosing repetition can re-enter this loop at the same position where it last exited,
        // which must not be mistaken for a zero-width iteration of this loop.
        // On progress, PROGRESS_CHECK acts like ALT(body, exit) with greediness preference.
        // On zero-width, PROGRESS_CHECK forces exit only (terminates the loop).
        if (a.nullable && !forDfa) {
            val pcId = allocInst()
            if (pcId < 0) {
                return Frag.Companion.NO_MATCH
            }
            val entryId = allocInst()
            if (entryId < 0) {
                return Frag.Companion.NO_MATCH
            }
            val loopReg = nextLoopReg++
            prog.mutableInst(entryId).initLoopEnter(loopReg, pcId)
            prog.mutableInst(pcId).initProgressCheck(loopReg, a.begin, 0, nongreedy)
            val pl: Long = PatchList.mk((pcId shl 1) or 1) // patch out1 (exit)

            // Patch the body end to loop back to pcId.
            PatchList.patch(prog, a.end, pcId)
            return Frag(entryId, pl, true)
        }

        val id = allocInst()
        if (id < 0) {
            return Frag.Companion.NO_MATCH
        }
        val pl: Long
        if (nongreedy) {
            prog.mutableInst(id).initAlt(0, a.begin)
            pl = PatchList.mk(id shl 1)
        } else {
            prog.mutableInst(id).initAlt(a.begin, 0)
            pl = PatchList.mk((id shl 1) or 1)
        }
        PatchList.patch(prog, a.end, id)
        return Frag(a.begin, pl, a.nullable)
    }

    private fun star(a: Frag, nongreedy: Boolean): Frag? {
        if (a.nullable) {
            return quest(plus(a, nongreedy), nongreedy)
        }

        val id = allocInst()
        if (id < 0) {
            return Frag.Companion.NO_MATCH
        }
        val pl: Long
        if (nongreedy) {
            prog.mutableInst(id).initAlt(0, a.begin)
            pl = PatchList.mk(id shl 1)
        } else {
            prog.mutableInst(id).initAlt(a.begin, 0)
            pl = PatchList.mk((id shl 1) or 1)
        }
        PatchList.patch(prog, a.end, id)
        return Frag(id, pl, true)
    }

    private fun quest(a: Frag, nongreedy: Boolean): Frag {
        if (isNoMatch(a)) {
            return nop()
        }
        val id = allocInst()
        if (id < 0) {
            return Frag.Companion.NO_MATCH
        }
        val pl: Long
        if (nongreedy) {
            prog.mutableInst(id).initAlt(0, a.begin)
            pl = PatchList.mk(id shl 1)
        } else {
            prog.mutableInst(id).initAlt(a.begin, 0)
            pl = PatchList.mk((id shl 1) or 1)
        }
        return Frag(id, PatchList.append(prog, pl, a.end), true)
    }

    /** Compiles the copies of a nullable finite repetition as one minimum-respecting invocation.  */
    private fun finiteRepeat(copies: MutableList<Frag>, minimum: Int, nongreedy: Boolean): Frag {
        val maximum: Int = copies.size()
        if (forDfa) {
            // Unguarded copies preserve full-match existence, but not ordered paths or captures.
            // matches() can fix group zero to the whole region and defer guarded capture extraction;
            // prefix/search matching uses these copies only as a rejection filter.
            var suffix = quest(copies.get(maximum - 1), nongreedy)
            for (i in maximum - 2 downTo minimum) {
                suffix = quest(cat(copies.get(i), suffix), nongreedy)
            }
            for (i in minimum - 1 downTo 0) {
                suffix = cat(copies.get(i), suffix)
            }
            return suffix
        }

        val first = Math.max(0, minimum - 1)
        val loopReg = nextLoopReg++
        var exits = copies.get(maximum - 1).end
        var firstCheck = 0
        for (i in first..<maximum) {
            val check = allocInst()
            if (check < 0) {
                return Frag.Companion.NO_MATCH
            }
            prog.mutableInst(check).initProgressCheck(loopReg, copies.get(i).begin, 0, nongreedy)
            exits = PatchList.append(prog, exits, PatchList.mk((check shl 1) or 1))
            if (i == first) {
                firstCheck = check
            } else {
                PatchList.patch(prog, copies.get(i - 1).end, check)
            }
        }
        val entry = allocInst()
        if (entry < 0) {
            return Frag.Companion.NO_MATCH
        }
        prog.mutableInst(entry).initLoopEnter(loopReg, firstCheck)
        var suffix = Frag(entry, exits, true)
        if (minimum == 0) {
            return quest(suffix, nongreedy)
        }
        for (i in first - 1 downTo 0) {
            suffix = cat(copies.get(i), suffix)
        }
        return suffix
    }

    private fun charRange(lo: Int, hi: Int, foldCase: Boolean): Frag {
        val id = allocInst()
        if (id < 0) {
            return Frag.Companion.NO_MATCH
        }
        prog.mutableInst(id).initCharRange(lo, hi, foldCase, 0)
        return Frag(id, PatchList.mk(id shl 1), false)
    }

    private fun graphemeCluster(): Frag {
        val id = allocInst()
        if (id < 0) {
            return Frag.Companion.NO_MATCH
        }
        prog.mutableInst(id).initGraphemeCluster(0)
        return Frag(id, PatchList.mk(id shl 1), false)
    }

    private fun nop(): Frag {
        val id = allocInst()
        if (id < 0) {
            return Frag.Companion.NO_MATCH
        }
        prog.mutableInst(id).initNop(0)
        return Frag(id, PatchList.mk(id shl 1), true)
    }

    private fun match(matchId: Int): Frag {
        val id = allocInst()
        if (id < 0) {
            return Frag.Companion.NO_MATCH
        }
        prog.mutableInst(id).initMatch(matchId)
        return Frag(id, PatchList.EMPTY, false)
    }

    private fun emptyWidth(emptyFlags: Int): Frag {
        val id = allocInst()
        if (id < 0) {
            return Frag.Companion.NO_MATCH
        }
        prog.mutableInst(id).initEmptyWidth(emptyFlags, 0)
        return Frag(id, PatchList.mk(id shl 1), true)
    }

    private fun capture(a: Frag, cap: Int): Frag {
        if (isNoMatch(a)) {
            return Frag.Companion.NO_MATCH
        }
        // Need two instructions: one for capture-start, one for capture-end.
        val id = allocInst()
        if (id < 0) {
            return Frag.Companion.NO_MATCH
        }
        val id2 = allocInst()
        if (id2 < 0) {
            return Frag.Companion.NO_MATCH
        }
        prog.mutableInst(id).initCapture(2 * cap, a.begin)
        prog.mutableInst(id2).initCapture(2 * cap + 1, 0)
        PatchList.patch(prog, a.end, id2)
        return Frag(id, PatchList.mk(id2 shl 1), a.nullable)
    }

    private fun literal(rune: Int, flags: Int): Frag {
        var foldCase = (flags and ParseFlags.FOLD_CASE) != 0
        if (foldCase && (flags and ParseFlags.UNICODE_CASE) == 0) {
            if (isAsciiLetter(rune)) {
                // Parser keeps these as folded literals so literal/prefix accelerators can see them.
                // Lower to an explicit ASCII class here to avoid Unicode simple-fold matching.
                return asciiFoldedLiteral(rune)
            }
            foldCase = false
        }
        // In our code-point model, a literal is just a single CHAR_RANGE.
        return charRange(rune, rune, foldCase)
    }

    private fun asciiFoldedLiteral(rune: Int): Frag {
        val lower = Ascii.toLowerCase(rune)
        val upper = Ascii.toUpperCase(rune)
        val id = allocInst()
        if (id < 0) {
            return Frag.Companion.NO_MATCH
        }
        prog.mutableInst(id).initCharClass(0, intArrayOf(upper, upper, lower, lower))
        return Frag(id, PatchList.mk(id shl 1), false)
    }

    private fun literalString(runes: IntArray?, flags: Int): Frag {
        if (runes == null || runes.length == 0) {
            return nop()
        }
        if (runes.length == 1) {
            return literal(runes[0], flags)
        }
        val first = literal(runes[0], flags)
        if (isNoMatch(first)) {
            return Frag.Companion.NO_MATCH
        }
        var current = first
        for (i in 1..<runes.length) {
            val next = literal(runes[i], flags)
            if (isNoMatch(next)) {
                return Frag.Companion.NO_MATCH
            }
            current = cat(current, next)
        }
        return current
    }

    /** Compiles an "any code point" fragment.  */
    private fun anyCodePoint(): Frag {
        return charRange(0, Utils.MAX_RUNE, false)
    }

    /**
     * Creates a `.*?` fragment for unanchored matching. The DotStar loops over any code point
     * (non-greedy).
     */
    private fun dotStar(): Frag? {
        return star(anyCodePoint(), true)
    }

    // ---------------------------------------------------------------------------
    // Walker overrides
    // ---------------------------------------------------------------------------
    override fun repeatVisits(re: Regexp): Int {
        return re.max
    }

    override fun preVisit(re: Regexp?, parentArg: Frag?, stop: BooleanArray): Frag {
        if (failed) {
            stop[0] = true
        }
        return Frag.Companion.NO_MATCH
    }

    override fun postVisit(re: Regexp, parentArg: Frag?, preArg: Frag?, childArgs: MutableList<Frag>): Frag? {
        if (failed) {
            return Frag.Companion.NO_MATCH
        }

        return when (re.op) {
            RegexpOp.REPEAT -> finiteRepeat(childArgs, re.min, re.nonGreedy())
            RegexpOp.NO_MATCH -> Frag.Companion.NO_MATCH
            RegexpOp.EMPTY_MATCH -> nop()
            RegexpOp.HAVE_MATCH -> match(re.matchId)
            RegexpOp.CONCAT -> {
                var f = childArgs.get(0)
                for (i in 1..<childArgs.size()) {
                    f = cat(f, childArgs.get(i))
                }
                f
            }

            RegexpOp.ALTERNATE -> {
                var f = childArgs.get(0)
                for (i in 1..<childArgs.size()) {
                    f = alt(f, childArgs.get(i))
                }
                f
            }

            RegexpOp.NON_CAPTURE -> childArgs.get(0)
            RegexpOp.STAR -> star(childArgs.get(0), re.nonGreedy())
            RegexpOp.PLUS -> plus(childArgs.get(0), re.nonGreedy())
            RegexpOp.QUEST -> quest(childArgs.get(0), re.nonGreedy())
            RegexpOp.LITERAL -> literal(re.rune, re.flags)
            RegexpOp.LITERAL_STRING -> literalString(re.runes, re.flags)
            RegexpOp.ANY_CHAR -> anyCodePoint()
            RegexpOp.GRAPHEME_CLUSTER -> graphemeCluster()
            RegexpOp.CHAR_CLASS -> compileCharClass(re)
            RegexpOp.CAPTURE -> {
                if (re.cap < 0) {
                    childArgs.get(0)
                }
                capture(childArgs.get(0), re.cap)
            }

            RegexpOp.BEGIN_LINE -> emptyWidth(
                if ((re.flags and ParseFlags.UNIX_LINES) != 0)
                    (if (reversed) EmptyOp.UNIX_END_LINE else EmptyOp.UNIX_BEGIN_LINE)
                else
                    (if (reversed) EmptyOp.END_LINE else EmptyOp.BEGIN_LINE)
            )

            RegexpOp.END_LINE -> emptyWidth(
                if ((re.flags and ParseFlags.UNIX_LINES) != 0)
                    (if (reversed) EmptyOp.UNIX_BEGIN_LINE else EmptyOp.UNIX_END_LINE)
                else
                    (if (reversed) EmptyOp.BEGIN_LINE else EmptyOp.END_LINE)
            )

            RegexpOp.BEGIN_TEXT -> emptyWidth(if (reversed) EmptyOp.END_TEXT else EmptyOp.BEGIN_TEXT)
            RegexpOp.END_TEXT -> {
                if ((re.flags and ParseFlags.WAS_DOLLAR) != 0) {
                    // Keep the assertion's line mode and position semantics in either scan direction.
                    emptyWidth(
                        if ((re.flags and ParseFlags.UNIX_LINES) != 0)
                            EmptyOp.UNIX_DOLLAR_END
                        else
                            EmptyOp.DOLLAR_END
                    )
                }
                emptyWidth(if (reversed) EmptyOp.BEGIN_TEXT else EmptyOp.END_TEXT)
            }

            RegexpOp.WORD_BOUNDARY -> {
                if ((re.flags and ParseFlags.UNICODE_CHAR_CLASS) != 0) {
                    emptyWidth(EmptyOp.UNICODE_WORD_BOUNDARY)
                }
                emptyWidth(EmptyOp.WORD_BOUNDARY)
            }

            RegexpOp.NO_WORD_BOUNDARY -> {
                if ((re.flags and ParseFlags.UNICODE_CHAR_CLASS) != 0) {
                    emptyWidth(EmptyOp.UNICODE_NON_WORD_BOUNDARY)
                }
                emptyWidth(EmptyOp.NON_WORD_BOUNDARY)
            }

            RegexpOp.GRAPHEME_CLUSTER_BOUNDARY -> emptyWidth(EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY)
            else -> {
                failed = true
                Frag.Companion.NO_MATCH
            }
        }
    }

    override fun shortVisit(re: Regexp?, parentArg: Frag?): Frag {
        failed = true
        return Frag.Companion.NO_MATCH
    }

    // ---------------------------------------------------------------------------
    // Character class compilation
    // ---------------------------------------------------------------------------
    private fun compileCharClass(re: Regexp): Frag? {
        val cc = re.charClass
        if (cc == null || cc.isEmpty()) {
            return Frag.Companion.NO_MATCH
        }

        val numRanges = cc.numRanges()

        // Single range: use a simple CHAR_RANGE instruction (no ALT overhead).
        if (numRanges == 1) {
            return charRange(cc.lo(0), cc.hi(0), false)
        }

        // Multi-range: emit a single CHAR_CLASS instruction with all ranges and an ASCII bitmap.
        val ranges = IntArray(numRanges * 2)
        for (i in 0..<numRanges) {
            ranges[i * 2] = cc.lo(i)
            ranges[i * 2 + 1] = cc.hi(i)
        }
        val id = allocInst()
        if (id < 0) {
            return Frag.Companion.NO_MATCH
        }
        prog.mutableInst(id).initCharClass(0, ranges)
        return Frag(id, PatchList.mk(id shl 1), false)
    }

    companion object {
        /** Maximum number of instructions allowed by default.  */
        private const val DEFAULT_MAX_INST = 1000000

        /**
         * Compiles the given regexp into a program.
         * 
         * @param re the regexp to compile
         * @return the compiled program, or null if compilation fails
         */
        fun compile(re: Regexp): Prog? {
            return compile(prepare(re), false, false)
        }

        /**
         * Compiles the given regexp into a program.
         * 
         * @param re the regexp to compile
         * @param reversed if true, compile for backward matching
         * @return the compiled program, or null if compilation fails
         */
        fun compile(re: Regexp, reversed: Boolean): Prog? {
            return compile(prepare(re), reversed, false)
        }

        fun compileForDfa(re: Regexp): Prog? {
            return compile(prepare(re), false, true)
        }

        fun compileForDfa(re: Regexp, reversed: Boolean): Prog? {
            return compile(prepare(re), reversed, true)
        }

        /** Simplifies a source AST once, preserving capture writes in every repeat copy.  */
        fun prepare(re: Regexp): Prepared? {
            val numCaptures: Int = maxCapture(re) + 1
            // Repeat copies share source capture identities. Later accepted copies must still write
            // their captures; retaining an earlier value is the engine's omission/rollback behavior.
            val sre = Simplifier.simplify(re)
            if (sre == null) {
                return null
            }

            // Detect anchoring, stripping the anchor nodes.
            val isAnchorStart: Boolean = isAnchorStart(sre)
            var stripped: Regexp? = stripAnchorStart(sre)
            val isAnchorEnd: Boolean = isAnchorEnd(stripped)
            val isDollarEnd = isAnchorEnd && isDollarAnchorEnd(stripped)
            val dollarUnixLines = isDollarEnd && isUnixDollarAnchorEnd(stripped)
            stripped = stripAnchorEnd(stripped)
            return Compiler.Prepared(
                stripped, sre, numCaptures, isAnchorStart, isAnchorEnd, isDollarEnd, dollarUnixLines
            )
        }

        fun compile(prepared: Prepared?, reversed: Boolean): Prog? {
            return compile(prepared, reversed, false)
        }

        fun compileForDfa(prepared: Prepared?, reversed: Boolean): Prog? {
            return compile(prepared, reversed, true)
        }

        private fun compile(prepared: Prepared?, reversed: Boolean, forDfa: Boolean): Prog? {
            if (prepared == null) {
                return null
            }
            val c = Compiler()
            c.reversed = reversed
            c.forDfa = forDfa

            // Walk the AST to produce fragments.
            var all = c.walkExponential(prepared.root, Frag.Companion.NO_MATCH, 2 * c.maxInst)
            if (c.failed) {
                return null
            }

            // Append Match(0).
            c.reversed = false
            all = c.cat(all, c.match(0))
            if (c.failed) {
                return null
            }

            c.prog.setReversed(reversed)
            if (reversed) {
                c.prog.setAnchorStart(prepared.anchorEnd)
                c.prog.setAnchorEnd(prepared.anchorStart)
            } else {
                c.prog.setAnchorStart(prepared.anchorStart)
                c.prog.setAnchorEnd(prepared.anchorEnd)
                c.prog.setDollarAnchorEnd(prepared.dollarEnd)
                c.prog.setDollarAnchorUnixLines(prepared.dollarUnixLines)
            }

            c.prog.setStart(all.begin)

            if (!c.prog.anchorStart()) {
                // Prepend .*? loop for unanchored matching.
                all = c.cat(c.dotStar()!!, all)
            }
            c.prog.setStartUnanchored(all.begin)

            // Freeze the instruction list into a flat array for fast indexed access.
            c.prog.freeze()

            c.prog.setNumCaptures(prepared.numCaptures)
            c.prog.setNumLoopRegs(c.nextLoopReg)

            return c.prog
        }

        private fun maxCapture(re: Regexp): Int {
            var max = 0
            val stack = ArrayDeque<Regexp>()
            stack.push(re)
            while (!stack.isEmpty()) {
                val node = stack.pop()
                if (node.op == RegexpOp.CAPTURE && node.cap > max) {
                    max = node.cap
                }
                if (node.subs != null) {
                    for (sub in node.subs) {
                        stack.push(sub)
                    }
                }
            }
            return max
        }

        // ---------------------------------------------------------------------------
        // Anchor detection — approximate, like RE2's IsAnchorStart/IsAnchorEnd
        // ---------------------------------------------------------------------------
        fun isAnchorStart(re: Regexp?): Boolean {
            return isAnchorStartImpl(re, 0)
        }

        private fun isAnchorStartImpl(re: Regexp?, depth: Int): Boolean {
            if (re == null || depth >= 4) {
                return false
            }
            return when (re.op) {
                RegexpOp.BEGIN_TEXT -> true
                RegexpOp.CONCAT -> re.nsub() > 0 && isAnchorStartImpl(re.subs.getFirst(), depth + 1)
                RegexpOp.NON_CAPTURE, RegexpOp.CAPTURE -> isAnchorStartImpl(re.sub(), depth + 1)
                else -> false
            }
        }

        fun stripAnchorStart(re: Regexp?): Regexp? {
            return stripAnchorStartImpl(re, 0)
        }

        private fun stripAnchorStartImpl(re: Regexp?, depth: Int): Regexp? {
            if (re == null || depth >= 4) {
                return re
            }
            return when (re.op) {
                RegexpOp.BEGIN_TEXT -> Regexp.Companion.emptyMatch(re.flags)
                RegexpOp.CONCAT -> {
                    if (re.nsub() > 0 && isAnchorStartImpl(re.subs.getFirst(), depth + 1)) {
                        val newSubs: MutableList<Regexp?> = ArrayList<Regexp?>(re.subs)
                        newSubs.set(0, stripAnchorStartImpl(re.subs.getFirst(), depth + 1))
                        Regexp.Companion.concat(newSubs, re.flags)
                    }
                    re
                }

                RegexpOp.NON_CAPTURE -> {
                    if (isAnchorStartImpl(re.sub(), depth + 1)) {
                        Regexp.Companion.nonCapture(stripAnchorStartImpl(re.sub(), depth + 1), re.flags)
                    }
                    re
                }

                RegexpOp.CAPTURE -> {
                    if (isAnchorStartImpl(re.sub(), depth + 1)) {
                        Regexp.Companion.capture(
                            stripAnchorStartImpl(re.sub(), depth + 1), re.flags, re.cap, re.name
                        )
                    }
                    re
                }

                else -> re
            }
        }

        fun isAnchorEnd(re: Regexp?): Boolean {
            return isAnchorEndImpl(re, 0)
        }

        /**
         * Returns true if the trailing end anchor is `$` (WAS_DOLLAR), meaning the match may end
         * before a trailing newline. Must only be called when [.isAnchorEnd] is true.
         */
        fun isDollarAnchorEnd(re: Regexp?): Boolean {
            return isDollarAnchorEndImpl(re, 0)
        }

        private fun isUnixDollarAnchorEnd(re: Regexp?): Boolean {
            // Follow exactly the bounded path used by end-anchor detection and stripping.
            var re = re
            var depth = 0
            while (re != null && depth < 4) {
                when (re.op) {
                    RegexpOp.END_TEXT -> {
                        return (re.flags and ParseFlags.UNIX_LINES) != 0
                    }

                    RegexpOp.CONCAT -> re = if (re.nsub() == 0) null else re.subs.getLast()
                    RegexpOp.NON_CAPTURE, RegexpOp.CAPTURE -> re = re.sub()
                    else -> {
                        return false
                    }
                }
                depth++
            }
            return false
        }

        private fun isDollarAnchorEndImpl(re: Regexp?, depth: Int): Boolean {
            if (re == null || depth >= 4) {
                return false
            }
            return when (re.op) {
                RegexpOp.END_TEXT -> (re.flags and ParseFlags.WAS_DOLLAR) != 0
                RegexpOp.CONCAT -> re.nsub() > 0 && isDollarAnchorEndImpl(re.subs.get(re.nsub() - 1), depth + 1)
                RegexpOp.NON_CAPTURE, RegexpOp.CAPTURE -> isDollarAnchorEndImpl(re.sub(), depth + 1)
                else -> false
            }
        }

        private fun isAnchorEndImpl(re: Regexp?, depth: Int): Boolean {
            if (re == null || depth >= 4) {
                return false
            }
            return when (re.op) {
                RegexpOp.END_TEXT -> true
                RegexpOp.CONCAT -> re.nsub() > 0 && isAnchorEndImpl(re.subs.get(re.nsub() - 1), depth + 1)
                RegexpOp.NON_CAPTURE, RegexpOp.CAPTURE -> isAnchorEndImpl(re.sub(), depth + 1)
                else -> false
            }
        }

        fun stripAnchorEnd(re: Regexp?): Regexp? {
            return stripAnchorEndImpl(re, 0)
        }

        private fun stripAnchorEndImpl(re: Regexp?, depth: Int): Regexp? {
            if (re == null || depth >= 4) {
                return re
            }
            return when (re.op) {
                RegexpOp.END_TEXT -> Regexp.Companion.emptyMatch(re.flags)
                RegexpOp.CONCAT -> {
                    if (re.nsub() > 0 && isAnchorEndImpl(re.subs.get(re.nsub() - 1), depth + 1)) {
                        val newSubs: MutableList<Regexp?> = ArrayList<Regexp?>(re.subs)
                        val last = re.nsub() - 1
                        newSubs.set(last, stripAnchorEndImpl(re.subs.get(last), depth + 1))
                        Regexp.Companion.concat(newSubs, re.flags)
                    }
                    re
                }

                RegexpOp.NON_CAPTURE -> {
                    if (isAnchorEndImpl(re.sub(), depth + 1)) {
                        Regexp.Companion.nonCapture(stripAnchorEndImpl(re.sub(), depth + 1), re.flags)
                    }
                    re
                }

                RegexpOp.CAPTURE -> {
                    if (isAnchorEndImpl(re.sub(), depth + 1)) {
                        Regexp.Companion.capture(stripAnchorEndImpl(re.sub(), depth + 1), re.flags, re.cap, re.name)
                    }
                    re
                }

                else -> re
            }
        }

        private fun isNoMatch(f: Frag): Boolean {
            return f.begin == 0
        }

        private fun isAsciiLetter(rune: Int): Boolean {
            return Ascii.isAlpha(rune)
        }
    }
}
