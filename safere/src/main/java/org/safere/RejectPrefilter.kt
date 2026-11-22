// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.safere.MultiAnchorDescriptor.RejectPlan
import org.safere.EndAnchoredCharClassInfo
import org.safere.SuffixInfo
import kotlin.jvm.JvmRecord
import kotlin.math.*
/**
 * Whole-input rejection filter (Tier 0 acceleration).
 * 
 * 
 * Rejects match attempts in O(1) / fast linear scan before invoking automata when mandatory
 * tokens or character classes are absent anywhere in the input.
 */
internal interface RejectPrefilter {
    /**
     * Returns whether the input starting from `searchFrom` can be rejected. Callers pass either
     * a non-null `text` or a non-null `scanner`.
     */
    fun canReject(text: String?, scanner: InputScanner?, searchFrom: Int, options: EnginePathOptions?): Boolean

    /** Returns the strategy that rejected the input, or `null` if it cannot be rejected.  */
    fun rejectionStrategy(
        text: String?, scanner: InputScanner?, searchFrom: Int, options: EnginePathOptions?
    ): MatchStrategy? {
        return if (canReject(text, scanner, searchFrom, options)) strategy() else null
    }

    /**
     * Like [.canReject], for the tail of a short input after its first start candidate. On
     * success that tail is scanned again by the automaton, so filters may pick a search with a lower
     * fixed cost.
     */
    fun canRejectTail(
        text: String?, scanner: InputScanner?, searchFrom: Int, options: EnginePathOptions?
    ): Boolean {
        return canReject(text, scanner, searchFrom, options)
    }

    /** Like [.rejectionStrategy], using [.canRejectTail].  */
    fun tailRejectionStrategy(
        text: String?, scanner: InputScanner?, searchFrom: Int, options: EnginePathOptions?
    ): MatchStrategy? {
        return if (canRejectTail(text, scanner, searchFrom, options)) strategy() else null
    }

    fun strategy(): MatchStrategy?

    /** Restores scalar rejection that can suppress low-half starts in independent String input.  */
    fun regionCharClassRejectionStrategy(
        scanner: InputScanner?, start: Int, end: Int, searches: RegionClassSearch
    ): MatchStrategy? {
        if (this is CharClass) {
            return if (rejectsRegion(scanner, start, end, searches)) strategy() else null
        }
        if (this is Composite) {
            // Composite construction flattens children. Other filters cannot suppress an NFA match
            // solely because its candidate starts inside a surrogate pair.
            for (filter in filters!!) {
                if (filter is CharClass && filter.rejectsRegion(scanner, start, end, searches)) {
                    return filter.strategy()
                }
            }
        }
        return null
    }

    /** Checks only terminal filters, whose work is bounded by the pattern rather than the input.  */
    fun endAnchoredRejectionStrategy(
        scanner: InputScanner?, searchFrom: Int, options: EnginePathOptions?
    ): MatchStrategy? {
        val text = if (scanner is StringInputScanner) scanner.text() else null
        if (this is EndAnchoredSuffix || this is EndAnchoredCharClass) {
            return rejectionStrategy(text, scanner, searchFrom, options)
        }
        if (this is Composite) {
            // Composite construction flattens the filters, so this remains an iterative bounded walk.
            for (filter in filters!!) {
                if (filter is EndAnchoredSuffix || filter is EndAnchoredCharClass) {
                    val rejection = filter.rejectionStrategy(text, scanner, searchFrom, options)
                    if (rejection != null) {
                        return rejection
                    }
                }
            }
        }
        return null
    }

    @JvmRecord
    data class Literal(
        val literal: String?,
        val utf8: ByteArray?,
        val failure: IntArray?,
        val shifts: IntArray?,
        val anchorOffset: Int,
        val anchor: Char
    ) : RejectPrefilter {
        @MaxBytecodeSize(
            value = 37, reason = ("C2 always inlines methods of at most MaxInlineSize (35) bytes. find() runs this"
                    + " before searching for patterns with a required literal. Keep it a dispatcher"
                    + " and put new work in the String or scanner overloads. Kotlin emits 37 bytes for"
                    + " the same dispatcher shape (BytecodeBudgetTest); it may miss always-inline —"
                    + " re-measure reject/literal callers before shrinking or growing further.")
        )
        override fun canReject(
            text: String?, scanner: InputScanner?, searchFrom: Int, options: EnginePathOptions?
        ): Boolean {
            if (options == null || !options.literalFastPaths) {
                return false
            }
            return if (text != null) canReject(text, searchFrom) else canReject(scanner, searchFrom)
        }

        private fun canReject(text: String, searchFrom: Int): Boolean {
            return StringLiteralSearch.indexOf(text, literal!!, anchorOffset, anchor, searchFrom) < 0
        }

        override fun canRejectTail(
            text: String?, scanner: InputScanner?, searchFrom: Int, options: EnginePathOptions?
        ): Boolean {
            if (text == null && scanner != null && scanner.supportsUtf8ByteSearch()) {
                return options != null && options.literalFastPaths
                        && scanner.indexOfInTail(this.utf8!!, failure!!, searchFrom) < 0
            }
            return canReject(text, scanner, searchFrom, options)
        }

        private fun canReject(scanner: InputScanner?, searchFrom: Int): Boolean {
            if (scanner != null && scanner.supportsUtf8ByteSearch()) {
                return scanner.indexOf(this.utf8!!, failure!!, shifts, searchFrom) < 0
            }
            if (scanner is StringRegionInputScanner) {
                return scanner.indexOf(literal!!, searchFrom, scanner.length) < 0
            }
            return false
        }

        override fun strategy(): MatchStrategy {
            return MatchStrategy.LITERAL
        }


        companion object {
            fun create(literal: String): Literal {
                return from(RejectLiteralPlan.from(literal))
            }

            fun from(plan: RejectLiteralPlan): Literal {
                return Literal(
                    plan.literal,
                    plan.utf8,
                    plan.failure,
                    plan.shifts,
                    plan.anchorOffset,
                    plan.anchor,
                )
            }
        }
    }

    @JvmRecord
    data class CharClass(
        override val ranges: IntArray?,
        override val bitmap0: Long,
        override val bitmap1: Long,
        val singleAscii: Int,
        val smallChars: CharArray?,
        val nonAsciiUtf8: ByteArray?
    ) : RejectPrefilter, CharClassMembership {
        override fun canReject(
            text: String?, scanner: InputScanner?, searchFrom: Int, options: EnginePathOptions?
        ): Boolean {
            if (options == null || !options.charClassMatchFastPaths) {
                return false
            }
            if (text != null) {
                if (smallChars != null) {
                    return rejectsSmall(text, searchFrom)
                }
                val length: Int = text.length
                return ((if (singleAscii >= 0)
                    StringInputScanner.indexOfAscii(text, singleAscii, searchFrom, length)
                else
                    StringInputScanner.indexOfCodePointClass(
                        text, ranges, bitmap0, bitmap1, searchFrom, length
                    ))
                        < 0)
            }
            if (scanner != null && scanner.supportsUtf8ByteSearch()) {
                return canRejectUtf8(scanner, searchFrom)
            }
            if (scanner != null) {
                val limit = scanner.length
                return ((if (singleAscii >= 0)
                    scanner.indexOfAscii(singleAscii, searchFrom, limit)
                else
                    scanner.indexOfCodePointClass(ranges, bitmap0, bitmap1, searchFrom, limit))
                        < 0)
            }
            return false
        }

        internal fun rejectsRegion(
            scanner: InputScanner?, start: Int, end: Int, searches: RegionClassSearch
        ): Boolean {
            // Only low-surrogate membership distinguishes scalar rejection from the grapheme NFA's
            // UTF-16 candidate policy. Leave unrelated classes on the existing engine path.
            val ranges = ranges!!
            var i = 0
            while (i < ranges.size) {
                if (ranges[i] > Char.MAX_LOW_SURROGATE.code) {
                    break
                }
                if (ranges[i + 1] >= Char.MIN_LOW_SURROGATE.code) {
                    return !searches.contains(this, scanner!!, start, end)
                }
                i += 2
            }
            return false
        }

        /**
         * Returns whether no member of [.smallChars] occurs in the input, checked only from the
         * start of the input.
         * 
         * 
         * The check covers a short window for every member, then searches each member over the rest
         * of the input. That costs at most one pass per member per search sequence, and on input with
         * no member it rejects in as few intrinsic calls as possible. Later `find()` calls do not
         * reject: the start accelerator and DFA already bound the work for the rest of the input, and
         * re-searching an absent member from every `find()` position cost 2x on input with sparse
         * matches, even through bounded windows.
         */
        private fun rejectsSmall(haystack: String, searchFrom: Int): Boolean {
            if (searchFrom > 0) {
                return false
            }
            val length: Int = haystack.length
            val nearEnd = min(length, FIRST_FIND_NEAR_WINDOW)
            for (member in smallChars!!) {
                val index: Int = haystack.indexOfCodePointBounded(member.code, 0, nearEnd)
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record((if (index >= 0) index + 1 else nearEnd).toLong())
                }
                if (index >= 0) {
                    return false
                }
            }
            for (member in smallChars) {
                val index: Int = haystack.indexOfCodePointBounded(member.code, nearEnd, length)
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record((if (index >= 0) index - nearEnd + 1 else length - nearEnd).toLong())
                }
                if (index >= 0) {
                    return false
                }
            }
            return true
        }

        /**
         * Checks UTF-8 rejection.
         * 
         * 
         * A class with a non-ASCII member is only checked from the start of the input. The UTF-8
         * scanner has no memo, so repeating the check from every `find()` position would rescan
         * the rest of the input each time.
         * 
         * 
         * A mixed pair such as `[\]\uFF3D]` is first checked with one search for the ASCII
         * member or any non-ASCII byte. On ASCII text that one pass decides the check, as it did when
         * the class was searched as code points. Only from the first non-ASCII byte on is the class
         * checked as two searches: the ASCII member with UTF-8 indexOfAscii, and the
         * non-ASCII member as a byte sequence with UTF-8 indexOfUtf8Sequence. Both
         * use the byte search kernel. Decoding code points instead, as indexOfCodePointClass does, costs several nanoseconds per character on text
         * with no ASCII to skip, such as CJK.
         */
        private fun canRejectUtf8(scanner: InputScanner, searchFrom: Int): Boolean {
            if (searchFrom > 0 && ranges!![ranges.size - 1] >= 0x80) {
                return false
            }
            val length = scanner.length
            if (nonAsciiUtf8 != null) {
                val ascii = smallChars!![0].code
                val first = scanner.indexOfAsciiOrNonAscii(ascii, searchFrom, length)
                if (first < 0) {
                    return true
                }
                if (scanner.asciiAt(first) == ascii) {
                    return false
                }
                return scanner.indexOfMixedPair(ascii, nonAsciiUtf8, first, length) < 0
            }
            if (singleAscii >= 0) {
                return scanner.indexOfAscii(singleAscii, searchFrom, length) < 0
            }
            return scanner.indexOfCodePointClass(ranges, bitmap0, bitmap1, searchFrom, length) < 0
        }

        override fun strategy(): MatchStrategy {
            return MatchStrategy.CHARACTER_CLASS
        }


        companion object {
            fun create(scanInfo: CharClassScanInfo): CharClass {
                return from(RejectCharClassPlan.from(scanInfo))
            }

            fun from(plan: RejectCharClassPlan): CharClass {
                return CharClass(
                    plan.ranges,
                    plan.bitmap0,
                    plan.bitmap1,
                    plan.singleAscii,
                    plan.smallChars,
                    plan.nonAsciiUtf8,
                )
            }

            /**
             * Chars the first `find()` searches for every member before searching the rest of the
             * input one member at a time, so a member near the start is found without first scanning the
             * whole input for one that is absent.
             */
            private const val FIRST_FIND_NEAR_WINDOW = 80
        }
    }

    @JvmRecord
    data class DisjointLiterals(
        val literals: Array<String?>?,
        val utf8Literals: Array<ByteArray?>?,
        val failures: Array<IntArray?>?,
        val shifts: Array<IntArray?>?
    ) : RejectPrefilter {
        override fun canReject(
            text: String?, scanner: InputScanner?, searchFrom: Int, options: EnginePathOptions?
        ): Boolean {
            if (options == null || !options.literalFastPaths || searchFrom > 0) {
                return false
            }
            if (text != null) {
                for (literal in literals!!) {
                    val lit = literal!!
                    val idx = text.indexOf(lit)
                    if (WorkCounterConfig.ENABLED) {
                        val scanned: Int = if (idx >= 0) idx + lit.length else text.length
                        WorkCounter.record(scanned.toLong())
                    }
                    if (idx >= 0) {
                        return false
                    }
                }
                return true
            }
            if (scanner != null && scanner.supportsUtf8ByteSearch()) {
                for (i in utf8Literals!!.indices) {
                    if (scanner.indexOf(utf8Literals[i]!!, failures!![i]!!, shifts!![i], 0) >= 0) {
                        return false
                    }
                }
                return true
            }
            if (scanner is StringRegionInputScanner) {
                for (literal in literals!!) {
                    if (scanner.indexOf(literal!!, searchFrom, scanner.length) >= 0) {
                        return false
                    }
                }
                return true
            }
            return false
        }

        override fun strategy(): MatchStrategy {
            return MatchStrategy.LITERAL
        }


        companion object {
            fun create(literals: Array<String?>?): DisjointLiterals? {
                val plan = RejectDisjointLiteralsPlan.from(literals) ?: return null
                return from(plan)
            }

            fun from(plan: RejectDisjointLiteralsPlan): DisjointLiterals {
                return DisjointLiterals(
                    plan.literals, plan.utf8Literals, plan.failures, plan.shifts
                )
            }
        }
    }

    @JvmRecord
    data class EndAnchoredSuffix(
        val suffix: String?,
        val suffixUtf8: ByteArray?,
        val wasDollar: Boolean,
        val unixLines: Boolean,
        val foldCase: Boolean
    ) : RejectPrefilter {
        override fun canReject(
            text: String?, scanner: InputScanner?, searchFrom: Int, options: EnginePathOptions?
        ): Boolean {
            if (options == null || !options.literalFastPaths) {
                return false
            }
            if (text != null) {
                return !endsWith(text, suffix!!, wasDollar, unixLines, foldCase)
            }
            if (scanner != null && scanner.supportsUtf8ByteSearch()) {
                return !scanner.endsWith(suffixUtf8!!, wasDollar, unixLines, foldCase)
            }
            if (scanner is StringRegionInputScanner) {
                return !scanner.endsWith(suffix!!, wasDollar, unixLines, foldCase)
            }
            return false
        }

        override fun strategy(): MatchStrategy {
            return MatchStrategy.LITERAL
        }


        companion object {
            fun create(info: SuffixInfo?): EndAnchoredSuffix? {
                val plan = RejectEndAnchoredSuffixPlan.from(info) ?: return null
                return from(plan)
            }

            fun from(plan: RejectEndAnchoredSuffixPlan): EndAnchoredSuffix {
                return EndAnchoredSuffix(
                    plan.suffix,
                    plan.suffixUtf8,
                    plan.wasDollar,
                    plan.unixLines,
                    plan.foldCase,
                )
            }

            private fun endsWith(
                text: String, suffix: String, wasDollar: Boolean, unixLines: Boolean, foldCase: Boolean
            ): Boolean {
                val suffixLen: Int = suffix.length
                val textLen: Int = text.length
                if (textLen >= suffixLen
                    && (if (foldCase)
                        text.regionMatches(textLen - suffixLen, suffix, 0, suffixLen, true)
                    else
                        text.endsWith(suffix))
                ) {
                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.record(suffixLen.toLong())
                    }
                    return true
                }
                if (!wasDollar || text.isEmpty()) {
                    return false
                }
                val trailingStart: Int =
                    StringInputScanner.trailingLineTerminatorStart(text, unixLines, textLen)
                if (trailingStart >= suffixLen
                    && (if (foldCase)
                        text.regionMatches(trailingStart - suffixLen, suffix, 0, suffixLen, true)
                    else
                        text.startsWith(suffix, trailingStart - suffixLen))
                ) {
                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.record(suffixLen.toLong())
                    }
                    return true
                }
                return false
            }
        }
    }

    @JvmRecord
    data class EndAnchoredCharClass(val bitmap: AsciiBitmap?, val wasDollar: Boolean, val unixLines: Boolean) :
        RejectPrefilter {
        override fun canReject(
            text: String?, scanner: InputScanner?, searchFrom: Int, options: EnginePathOptions?
        ): Boolean {
            if (options == null || !options.charClassMatchFastPaths) {
                return false
            }
            if (text != null) {
                return canReject(text)
            }
            if (scanner != null
                && (scanner.supportsUtf8ByteSearch() || scanner is StringRegionInputScanner)
            ) {
                return canRejectTerminal(scanner)
            }
            return false
        }

        private fun canRejectTerminal(scanner: InputScanner): Boolean {
            val len = scanner.length
            if (len == 0) {
                return true
            }
            val ascii = scanner.asciiAt(len - 1)
            if (bitmap!!.contains(ascii)) {
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record(1)
                }
                return false
            }
            if (!wasDollar) {
                return true
            }
            val prevPos = scanner.trailingLineTerminatorStart(unixLines, len)
            if (prevPos > 0) {
                val prevAscii = scanner.asciiAt(prevPos - 1)
                if (bitmap.contains(prevAscii)) {
                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.record(1)
                    }
                    return false
                }
            }
            return true
        }

        private fun canReject(text: String): Boolean {
            val len: Int = text.length
            if (len == 0) {
                return true
            }
            val last: Char = text[len - 1]
            if (bitmap!!.contains(last.code)) {
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record(1)
                }
                return false
            }
            if (!wasDollar) {
                return true
            }
            val prevPos: Int = StringInputScanner.trailingLineTerminatorStart(text, unixLines, len)
            if (prevPos > 0) {
                val prev: Char = text[prevPos - 1]
                if (bitmap.contains(prev.code)) {
                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.record(1)
                    }
                    return false
                }
            }
            return true
        }

        override fun strategy(): MatchStrategy {
            return MatchStrategy.CHARACTER_CLASS
        }


        companion object {
            fun create(info: EndAnchoredCharClassInfo?): EndAnchoredCharClass? {
                if (info == null || info.bitmap == null) {
                    return null
                }
                return EndAnchoredCharClass(info.bitmap, info.wasDollar, info.unixLines)
            }
        }
    }

    @JvmRecord
    data class Composite(val filters: Array<RejectPrefilter>?) : RejectPrefilter {
        override fun canReject(
            text: String?, scanner: InputScanner?, searchFrom: Int, options: EnginePathOptions?
        ): Boolean {
            for (filter in filters!!) {
                if (filter.canReject(text, scanner, searchFrom, options)) {
                    return true
                }
            }
            return false
        }

        override fun rejectionStrategy(
            text: String?, scanner: InputScanner?, searchFrom: Int, options: EnginePathOptions?
        ): MatchStrategy? {
            for (filter in filters!!) {
                val strategy = filter.rejectionStrategy(text, scanner, searchFrom, options)
                if (strategy != null) {
                    return strategy
                }
            }
            return null
        }

        override fun canRejectTail(
            text: String?, scanner: InputScanner?, searchFrom: Int, options: EnginePathOptions?
        ): Boolean {
            for (filter in filters!!) {
                if (filter.canRejectTail(text, scanner, searchFrom, options)) {
                    return true
                }
            }
            return false
        }

        override fun tailRejectionStrategy(
            text: String?, scanner: InputScanner?, searchFrom: Int, options: EnginePathOptions?
        ): MatchStrategy? {
            for (filter in filters!!) {
                val strategy = filter.tailRejectionStrategy(text, scanner, searchFrom, options)
                if (strategy != null) {
                    return strategy
                }
            }
            return null
        }

        override fun strategy(): MatchStrategy? {
            return filters!![0].strategy()
        }


    }

    companion object {
        /**
         * Drops [DisjointLiterals] when UTF-8 start acceleration is already active, so `find()` does not add multi-literal full-input scans ahead of a single-pass start accelerator.
         */
        fun withoutDisjointLiterals(filter: RejectPrefilter?): RejectPrefilter? {
            if (filter is DisjointLiterals) {
                return null
            }
            if (filter is Composite) {
                val filters = filter.filters!!
                var kept: MutableList<RejectPrefilter>? = null
                for (i in filters.indices) {
                    if (filters[i] is DisjointLiterals) {
                        if (kept == null) {
                            kept = ArrayList(filters.size - 1)
                            for (j in 0 until i) {
                                kept.add(filters[j])
                            }
                        }
                    } else if (kept != null) {
                        kept.add(filters[i])
                    }
                }
                if (kept == null) {
                    return filter
                }
                if (kept.isEmpty()) {
                    return null
                }
                return if (kept.size == 1)
                    kept[0]
                else
                    Composite(kept.toTypedArray())
            }
            return filter
        }

        fun create(descriptor: MultiAnchorDescriptor?): RejectPrefilter? {
            if (descriptor == null) {
                return null
            }
            return create(descriptor.rejectPlan())
        }

        fun create(plan: RejectPlan?): RejectPrefilter? {
            if (plan == null || plan is RejectPlan.None) {
                return null
            }
            return when (plan) {
                is RejectPlan.None -> null
                is RejectPlan.RequiredLiteral -> Literal.create(plan.literal!!)
                is RejectPlan.RequiredCharClass -> CharClass.create(plan.scanInfo!!)
                is RejectPlan.DisjointLiterals -> DisjointLiterals.create(plan.literals!!)
                is RejectPlan.EndAnchoredSuffix -> EndAnchoredSuffix.create(plan.suffix)
                is RejectPlan.EndAnchoredCharClass ->
                    EndAnchoredCharClass.create(plan.charClass!!)

                is RejectPlan.Composite -> {
                    val list: MutableList<RejectPrefilter> = ArrayList()
                    for (p in plan.plans!!) {
                        val filter: RejectPrefilter? = create(p)
                        if (filter != null) {
                            if (filter is Composite) {
                                list.addAll(filter.filters!!)
                            } else {
                                list.add(filter)
                            }
                        }
                    }
                    if (list.isEmpty()) {
                        null
                    } else if (list.size == 1) {
                        list[0]
                    } else {
                        Composite(list.toTypedArray())
                    }
                }

                else -> null
            }
        }
    }
}
