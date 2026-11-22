// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.safere.MultiAnchorDescriptor.StartPlan
import org.safere.StartAcceleration
import kotlin.jvm.JvmRecord

/**
 * Encapsulates pre-computed start acceleration search strategies for finding candidate match
 * positions in a [String].
 */
internal interface StringStartAccelerator {
    /** Returns the tuning and diagnostic policy for this accelerator.  */
    fun policy(): AcceleratorPolicy {
        return AcceleratorPolicy.DEFAULT
    }

    /**
     * Scans for a case-sensitive literal prefix.
     * 
     * @param singleAsciiChar the one ASCII character `prefix` consists of, or [     ][StringLiteralSearch.NOT_SINGLE_ASCII]. Deciding this at plan time rather than per call is
     * the whole point; see [StringLiteralSearch.singleAsciiChar].
     */
    @JvmRecord
    data class Literal(val prefix: String?, val anchorOffset: Int, val anchor: Char, val singleAsciiChar: Int) :
        StringStartAccelerator {
        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.LITERAL
        }

        fun findCandidate(text: String?, fromIndex: Int, unixLines: Boolean): Int {
            return StringLiteralSearch.indexOfPlanned(
                text!!, prefix!!, anchorOffset, anchor, singleAsciiChar, fromIndex
            )
        }


        companion object {
            fun create(prefix: String?): Literal {
                val anchorOffset = StringLiteralSearch.anchorOffset(prefix)
                return Literal(
                    prefix,
                    anchorOffset,
                    StringLiteralSearch.anchorAt(prefix!!, anchorOffset),
                    StringLiteralSearch.singleAsciiChar(prefix)
                )
            }
        }
    }

    @JvmRecord
    data class CaseInsensitiveLiteral(
        val prefix: String?,
        val anchorOffset: Int,
        val anchorLow: Char,
        val anchorHigh: Char,
        val classHashChain: ClassHashChain?
    ) : StringStartAccelerator {
        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.LITERAL
        }

        fun findCandidate(text: String?, fromIndex: Int, unixLines: Boolean): Int {
            return Matcher.indexOfIgnoreCase(
                text!!, prefix!!, anchorOffset, anchorLow, anchorHigh, classHashChain, fromIndex
            )
        }


        companion object {
            fun create(prefix: String?): CaseInsensitiveLiteral {
                if (prefix == null || prefix.isEmpty()) {
                    return CaseInsensitiveLiteral(prefix, 0, '\u0000', '\u0000', null)
                }
                val anchorOffset = RarityOracle.rarestAsciiOffset(prefix, prefix.length, true)
                val anchor: Char = prefix[anchorOffset]
                val anchorLow = Ascii.toLowerCase(anchor)
                val anchorHigh = Ascii.toUpperCase(anchor)
                val chain: ClassHashChain? =
                    if (prefix.length >= 4) ClassHashChain.compileCaseInsensitive(prefix) else null
                return CaseInsensitiveLiteral(prefix, anchorOffset, anchorLow, anchorHigh, chain)
            }
        }
    }

    @JvmRecord
    data class UnicodeCaseInsensitiveLiteral(val prefix: String?, val classHashChain: ClassHashChain?) :
        StringStartAccelerator {
        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.LITERAL
        }

        fun findCandidate(text: String, fromIndex: Int, unixLines: Boolean): Int {
            if (prefix == null || prefix.isEmpty()) {
                return Math.min(Math.max(0, fromIndex), text.length)
            }
            val pos = Math.max(0, fromIndex)
            if (classHashChain != null) {
                val limit = WorkLimit.forRemaining(text.length - pos)
                val result = classHashChain.search(text, pos, limit)
                return if (result == -2) Utf16.indexOfUnicodeIgnoreCase(text, prefix, pos) else result
            }
            return Utf16.indexOfUnicodeIgnoreCase(text, prefix, pos)
        }


        companion object {
            fun create(prefix: String?): UnicodeCaseInsensitiveLiteral {
                if (prefix == null || prefix.isEmpty()) {
                    return UnicodeCaseInsensitiveLiteral(prefix, null)
                }
                val chain: ClassHashChain? =
                    if (prefix.length >= 4) ClassHashChain.compileCaseInsensitive(prefix) else null
                return UnicodeCaseInsensitiveLiteral(prefix, chain)
            }
        }
    }

    /**
     * Scans for a literal that sits a bounded distance into the match, then walks back to the start.
     * 
     * @param singleAsciiChar see [Literal.singleAsciiChar]. This plan gains more from the
     * choice than `Literal` does, because the loop below re-searches for the literal once
     * per rejected candidate rather than once per call.
     */
    @JvmRecord
    data class FixedOffset(
        val fixedOffset: FixedOffsetLiteral?,
        val firstCharClass: CharClassScanInfo?,
        val anchorOffset: Int,
        val anchor: Char,
        val singleAsciiChar: Int
    ) : StringStartAccelerator {
        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.LITERAL
        }

        fun findCandidate(text: CharSequence, fromIndex: Int, unixLines: Boolean): Int {
            return nextFixedOffsetCandidate(
                text, fixedOffset!!, firstCharClass, anchorOffset, anchor, singleAsciiChar, fromIndex
            )
        }


        companion object {
            fun create(fixedOffset: FixedOffsetLiteral, firstCharClass: CharClassScanInfo?): FixedOffset {
                val literal = fixedOffset.literal()
                val anchorOffset = StringLiteralSearch.anchorOffset(literal)
                return FixedOffset(
                    fixedOffset,
                    firstCharClass,
                    anchorOffset,
                    StringLiteralSearch.anchorAt(literal!!, anchorOffset),
                    StringLiteralSearch.singleAsciiChar(literal)
                )
            }

            private fun nextFixedOffsetCandidate(
                text: CharSequence,
                fixedOffsetLiteral: FixedOffsetLiteral,
                firstCharClass: CharClassScanInfo?,
                anchorOffset: Int,
                anchor: Char,
                singleAsciiChar: Int,
                fromIndex: Int
            ): Int {
                val minOffset = fixedOffsetLiteral.minOffset()
                if (minOffset > text.length - fromIndex) {
                    return -1
                }
                var literalFrom = fromIndex + minOffset
                val discreteOffsets = fixedOffsetLiteral.discreteOffsets()

                while (literalFrom <= text.length) {
                    val literalStart: Int =
                        indexOfPlanned(
                            text,
                            fixedOffsetLiteral.literal()!!,
                            anchorOffset,
                            anchor,
                            singleAsciiChar,
                            literalFrom
                        )
                    if (literalStart < 0) {
                        return -1
                    }
                    if (firstCharClass != null) {
                        if (discreteOffsets != null && discreteOffsets.size == 1) {
                            val candidateStart = literalStart - discreteOffsets[0]
                            if (candidateStart >= fromIndex) {
                                val first =
                                    if (candidateStart < text.length) Character.codePointAt(
                                        text,
                                        candidateStart
                                    ) else -1
                                if (first >= 0 && firstCharClass.contains(first)) {
                                    return candidateStart
                                }
                            }
                            literalFrom = literalStart + 1
                            continue
                        } else if (discreteOffsets != null) {
                            val resolved: Int =
                                resolveMultiOffsetStart(
                                    text,
                                    fixedOffsetLiteral,
                                    discreteOffsets,
                                    firstCharClass,
                                    literalStart,
                                    fromIndex
                                )
                            if (resolved >= 0) {
                                return resolved
                            }
                            literalFrom = literalStart + 1
                            continue
                        } else if (fixedOffsetLiteral.minOffset() == fixedOffsetLiteral.maxOffset()) {
                            val candidateStart: Int =
                                retreatedStartInClass(
                                    text, fixedOffsetLiteral, firstCharClass, literalStart, fromIndex
                                )
                            if (candidateStart >= 0) {
                                return candidateStart
                            }
                            literalFrom = literalStart + 1
                            continue
                        }
                    }
                    return Math.max(
                        fromIndex,
                        retreatByCodePoints(text, literalStart, fixedOffsetLiteral.maxOffset(), fromIndex)
                    )
                }
                return -1
            }

            /**
             * Resolves a literal occurrence at `literalStart` to a match start when the literal can
             * sit at more than one offset, or returns -1 when the leading class admits none of them and the
             * occurrence can be skipped.
             * 
             * 
             * The offsets are ascending, so the largest yields the earliest start; they are walked from
             * the back to find the leftmost start this occurrence admits. The result is then clamped to the
             * earliest start a *later* occurrence could imply, because the caller treats it as a
             * floor and will not look before it, and the next occurrence is at `literalStart + 1` at
             * the earliest. Without the clamp a wide offset span loses the leftmost match: on `(aq|b[a-z]{9})z` the `z` at index 5 admits only the start at 3, while the `z` at
             * index 10 starts the match at 0.
             */
            private fun resolveMultiOffsetStart(
                text: CharSequence,
                fixedOffsetLiteral: FixedOffsetLiteral,
                discreteOffsets: IntArray,
                firstCharClass: CharClassScanInfo,
                literalStart: Int,
                fromIndex: Int
            ): Int {
                for (i in discreteOffsets.size - 1 downTo 0) {
                    val start = literalStart - discreteOffsets[i]
                    if (start >= fromIndex
                        && start < text.length
                        && firstCharClass.contains(Character.codePointAt(text, start))
                    ) {
                        return Math.max(
                            fromIndex, Math.min(start, literalStart + 1 - fixedOffsetLiteral.maxOffset())
                        )
                    }
                }
                return -1
            }

            /**
             * Returns the start reached by retreating from `literalStart` over a fixed number of code
             * points when the leading class admits it, or -1 when the occurrence can be skipped.
             */
            private fun retreatedStartInClass(
                text: CharSequence,
                fixedOffsetLiteral: FixedOffsetLiteral,
                firstCharClass: CharClassScanInfo,
                literalStart: Int,
                fromIndex: Int
            ): Int {
                val candidateStart: Int =
                    retreatByCodePoints(text, literalStart, fixedOffsetLiteral.maxOffset(), fromIndex)
                if (candidateStart >= fromIndex) {
                    val first =
                        if (candidateStart < text.length) Character.codePointAt(text, candidateStart) else -1
                    if (first >= 0 && firstCharClass.contains(first)) {
                        return candidateStart
                    }
                }
                return -1
            }

            private fun retreatByCodePoints(text: CharSequence, index: Int, count: Int, minIndex: Int): Int {
                var count = count
                var pos = index
                while (count > 0 && pos > minIndex) {
                    pos--
                    if (pos > minIndex && Character.isLowSurrogate(text[pos])
                        && Character.isHighSurrogate(text[pos - 1])
                    ) {
                        pos--
                    }
                    count--
                }
                return Math.max(minIndex, pos)
            }
        }
    }

    /**
     * Scans a required interior class and checks its implied leading ASCII character.
     * 
     * 
     * The prefix's ASCII width is the same in both input representations. After a bounded number
     * of cheap leading-check failures, scans jump to the next leading-class hit before resuming the
     * interior scan. Both scans advance monotonically, including when interior hits are dense and
     * leading hits are absent. Full verification stays in the engine; ASCII leading hits exclude
     * starts inside surrogate pairs.
     */
    @JvmRecord
    data class FixedOffsetClass(val inner: CharClass?, val offset: Int, val leadingClass: CharClassScanInfo?) :
        StringStartAccelerator {
        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.CHAR_CLASS
        }

        fun findCandidate(text: CharSequence, fromIndex: Int, unixLines: Boolean): Int {
            val start = Math.max(0, fromIndex)
            if (offset >= text.length - start) {
                return -1
            }
            var position = start + offset
            var cheapRejections = 0
            while (position < text.length) {
                val hit: Int = findNextCandidate(inner!!, text, position, unixLines)
                if (hit < 0) {
                    return -1
                }
                val candidate = hit - offset
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record()
                }
                if (leadingClass!!.contains(text[candidate].code)) {
                    return candidate
                }
                if (cheapRejections < AcceleratorPolicy.FIXED_OFFSET_CLASS_CHEAP_REJECTIONS) {
                    cheapRejections++
                    position = hit + 1
                    continue
                }
                val nextLeading: Int = indexOfClass(text, leadingClass, candidate + 1)
                if (nextLeading < 0 || offset >= text.length - nextLeading) {
                    return -1
                }
                position = nextLeading + offset
            }
            return -1
        }


    }

    // The lookup table is immutable pattern metadata; array identity and value semantics are unused.
    @JvmRecord
    data class CharClass(val scanInfo: CharClassScanInfo?, val asciiTable: BooleanArray?) : StringStartAccelerator {
        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.CHAR_CLASS
        }

        fun findCandidate(text: String, fromIndex: Int, unixLines: Boolean): Int {
            // Three members cost three intrinsic passes over a gap, which has not been measured against
            // the table scan below, so only one- and two-member sets take the per-member search.
            if (scanInfo is CharClassScanInfo.SmallSet && scanInfo.chars.size <= 2) {
                return StringInputScanner.indexOfSmallSet(text, scanInfo.chars, fromIndex)
            }
            return indexOfCharClass(text, asciiTable!!, scanInfo!!.ranges, scanInfo.isAscii, fromIndex)
        }


        companion object {
            fun create(scanInfo: CharClassScanInfo): CharClass {
                return CharClass(scanInfo, buildAsciiTable(scanInfo))
            }

            private fun indexOfCharClass(
                text: String, asciiTable: BooleanArray, ranges: IntArray, isAscii: Boolean, fromIndex: Int
            ): Int {
                val length: Int = text.length
                var index = fromIndex
                while (index < length) {
                    val asciiResult: Int = scanAsciiRun(text, asciiTable, index)
                    if (asciiResult >= 0) {
                        return asciiResult
                    }
                    index = asciiResult.inv()
                    if (index >= length) {
                        return -1
                    }

                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.record()
                    }
                    val cp = text.codePointAt(index)
                    if (!isAscii && Matcher.binarySearchRanges(ranges, cp)) {
                        return index
                    }
                    index += Character.charCount(cp)
                }
                return -1
            }

            /**
             * Scans one contiguous ASCII run. A nonnegative result is a matching position; a negative
             * result is the complement of either the first non-ASCII position or the text length. Keeping
             * Unicode decoding and range lookup outside this loop allows HotSpot to optimize the common
             * ASCII path independently.
             */
            private fun scanAsciiRun(text: String, asciiTable: BooleanArray, fromIndex: Int): Int {
                val length: Int = text.length
                for (i in fromIndex until length) {
                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.record()
                    }
                    val ch: Char = text[i]
                    if (ch.code >= 128) {
                        return i.inv()
                    }
                    if (asciiTable[ch.code]) {
                        return i
                    }
                }
                return length.inv()
            }

            private fun buildAsciiTable(scanInfo: CharClassScanInfo): BooleanArray {
                val table = BooleanArray(128)
                for (i in 0 until 128) {
                    table[i] = scanInfo.contains(i)
                }
                return table
            }
        }
    }

    @JvmRecord
    data class LineAnchor(val startAcceleration: StartAcceleration?) : StringStartAccelerator {
        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.LINE_ANCHOR
        }

        fun findCandidate(text: CharSequence, fromIndex: Int, unixLines: Boolean): Int {
            return nextAcceleratedStart(text, startAcceleration!!, fromIndex, unixLines)
        }


        companion object {
            private fun nextAcceleratedStart(
                text: CharSequence, acceleration: StartAcceleration, fromIndex: Int, unixLines: Boolean
            ): Int {
                val start = Math.max(0, fromIndex)
                var i = start
                while (i < text.length) {
                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.record()
                    }
                    if (matchesStartAcceleration(text, i, acceleration, unixLines)) {
                        return i
                    }
                    val cp: Int = Character.codePointAt(text, i)
                    i += Character.charCount(cp) - 1
                    i++
                }
                return -1
            }

            private fun matchesStartAcceleration(
                text: CharSequence, pos: Int, acceleration: StartAcceleration, unixLines: Boolean
            ): Boolean {
                val lineStart: Boolean = isBeginLine(text, pos, unixLines)
                val asciiStart: Boolean = matchesAsciiStart(text, pos, acceleration.asciiStart)
                if (acceleration.requireLineStart) {
                    return lineStart && (acceleration.asciiStart == null || asciiStart)
                }
                return (acceleration.allowLineStart && lineStart) || asciiStart
            }

            private fun matchesAsciiStart(text: CharSequence, pos: Int, asciiStart: AsciiBitmap?): Boolean {
                if (asciiStart == null || pos >= text.length) {
                    return false
                }
                val ch: Char = text[pos]
                return asciiStart.contains(ch.code)
            }

            private fun isBeginLine(text: CharSequence, pos: Int, unixLines: Boolean): Boolean {
                if (pos == 0) {
                    return !text.isEmpty()
                }
                if (pos >= text.length) {
                    return false
                }
                val prev: Char = text[pos - 1]
                if (unixLines) {
                    return prev == '\n'
                }
                return prev == '\n' || prev == '\u0085' || prev == '\u2028' || prev == '\u2029' || (prev == '\r' && text[
                    pos
                ] != '\n')
            }
        }
    }

    // Arrays are immutable, privately owned scanner metadata; array identity is never observed.
    @JvmRecord
    data class LeadingExpansion(
        val leadingClass: CharClassScanInfo?,
        val minRepetition: Int,
        val maxRepetition: Int,
        val hasLeadingAssertions: Boolean,
        val wideLeadingClass: Boolean,
        val excludedChars: CharArray?,
        val inner: StringStartAccelerator?
    ) : StringStartAccelerator {
        /** Returns whether `text[from, to)` holds a code unit the leading class excludes.  */
        private fun containsExcluded(text: String, from: Int, to: Int): Boolean {
            for (c in excludedChars!!) {
                val hit: Int = text.indexOfCodePointBounded(c.code, from, to)
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record((if (hit < 0) to - from else hit - from + 1).toLong())
                }
                if (hit >= 0) {
                    return true
                }
            }
            return false
        }

        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.LEADING_EXPANSION.withStrategy(inner!!.policy().strategy)
        }

        /**
         * Returns whether an unanchored DFA probe from the inner candidate can reject every expanded
         * start at once. Not for a wide leading class such as non-DOTALL `.`: its expanded start
         * is the line start, and when the probe succeeds on a later line the search scans that gap a
         * second time from the expanded start. Searching from the expanded start directly is never
         * worse than the probe there.
         */
        fun canVerifyAtInner(): Boolean {
            return minRepetition == 0 && !hasLeadingAssertions && !wideLeadingClass
        }

        fun findInnerCandidate(text: CharSequence?, searchPos: Int, unixLines: Boolean): Int {
            return findNextCandidate(inner!!, text, searchPos, unixLines)
        }

        fun expandBackward(text: CharSequence, innerMatch: Int, fromIndex: Int): Int {
            var start = innerMatch
            var count = 0
            while (start > fromIndex) {
                var cp = Character.codePointBefore(text, start)
                var cpStart = start - Character.charCount(cp)
                if (cpStart < fromIndex) {
                    // Preserve an explicit low-surrogate cursor when its high surrogate precedes fromIndex.
                    cp = text[fromIndex].code
                    cpStart = fromIndex
                }
                if (!leadingClass!!.contains(cp)) {
                    break
                }
                if (count + 1 > maxRepetition) {
                    break
                }
                count++
                start = cpStart
            }
            return start
        }

        fun findCandidate(text: CharSequence, fromIndex: Int, unixLines: Boolean): Int {
            var searchPos = Math.max(0, fromIndex)
            val textLen: Int = text.length
            // A wide class's walk covers the rest of the line, so a skip shorter than
            // MIN_WIDE_LEADING_SKIP saves less than the literal scan and the walk cost. Such searches
            // start at the search start, as they would with no start plan.
            if (wideLeadingClass && textLen - searchPos < MultiAnchorCompiler.MIN_WIDE_LEADING_SKIP) {
                return searchPos
            }
            while (searchPos < textLen) {
                val innerMatch = findInnerCandidate(text, searchPos, unixLines)
                if (innerMatch < 0) {
                    return -1
                }
                if (wideLeadingClass
                    && innerMatch - fromIndex < MultiAnchorCompiler.MIN_WIDE_LEADING_SKIP
                ) {
                    return Math.max(0, fromIndex)
                }
                // A forward scan can show that the walk would reach the search start. Probe only the
                // first hit: an excluded code point before it will also precede every later hit.
                if (excludedChars != null && searchPos == fromIndex && innerMatch > fromIndex && text is String
                    && !containsExcluded(text, fromIndex, innerMatch)
                ) {
                    return fromIndex
                }
                var start = innerMatch
                var count = 0
                while (start > fromIndex) {
                    var cp = Character.codePointBefore(text, start)
                    var cpStart = start - Character.charCount(cp)
                    if (cpStart < fromIndex) {
                        // Preserve an explicit low-surrogate cursor when its high surrogate precedes fromIndex.
                        cp = text[fromIndex].code
                        cpStart = fromIndex
                    }
                    if (!leadingClass!!.contains(cp)) {
                        break
                    }
                    if (count + 1 > maxRepetition) {
                        break
                    }
                    count++
                    start = cpStart
                }
                if (count >= minRepetition) {
                    return start
                }
                searchPos = innerMatch + 1
            }
            return -1
        }


        companion object {
            /**
             * Returns the code units a wide leading class excludes, when a forward search for them can
             * stand in for the backward walk: the class must exclude only BMP non-surrogate code points,
             * and the repetition must be unbounded with at most one required repetition. Otherwise null.
             */
            fun forwardScanChars(le: StartPlan.LeadingExpansion): CharArray? {
                val excluded = MultiAnchorCompiler.excludedCodePoints(le.leadingClass!!)
                if (excluded == null || le.minRepetition > 1 || le.maxRepetition != Integer.MAX_VALUE) {
                    return null
                }
                val chars = CharArray(excluded.size)
                for (i in excluded.indices) {
                    val cp = excluded[i]
                    if (cp > Character.MAX_VALUE.code || Character.isSurrogate(cp.toChar())) {
                        return null
                    }
                    chars[i] = cp.toChar()
                }
                return chars
            }
        }
    }

    companion object {
        /**
         * Creates a [StringStartAccelerator] for the given pattern descriptor, or `null` if
         * no acceleration strategy applies.
         */
        fun create(descriptor: MultiAnchorDescriptor?, hasWordBoundary: Boolean): StringStartAccelerator? {
            if (descriptor == null) {
                return null
            }
            return create(descriptor.startPlan(), hasWordBoundary)
        }

        fun create(
            plan: StartPlan?, hasWordBoundary: Boolean
        ): StringStartAccelerator? {
            if (plan == null || plan is StartPlan.None) {
                return null
            }
            return when (plan) {
                is StartPlan.None -> null // already handled
                is StartPlan.Literal -> if (plan.foldCase)
                    (if (Ascii.isAscii(plan.prefix))
                        CaseInsensitiveLiteral.create(plan.prefix)
                    else
                        UnicodeCaseInsensitiveLiteral.create(plan.prefix))
                else
                    Literal.create(plan.prefix)

                is StartPlan.CharClass ->
                    if (hasWordBoundary || !plan.scanInfo!!.isSelective) null else CharClass.create(plan.scanInfo)

                is StartPlan.FixedOffset -> FixedOffset.create(plan.fol!!, plan.leadingClass)
                is StartPlan.FixedOffsetClass ->
                    FixedOffsetClass(CharClass.create(plan.scanInfo!!), plan.offset, plan.leadingClass!!)

                is StartPlan.MultiLiteral ->
                    if (hasWordBoundary || plan.fallbackClass == null || !plan.fallbackClass.isSelective)
                        null
                    else
                        CharClass.create(plan.fallbackClass)

                is StartPlan.LeadingExpansion -> {
                    val inner: StringStartAccelerator? = create(plan.innerPlan, hasWordBoundary)
                    if (inner != null)
                        LeadingExpansion(
                            plan.leadingClass,
                            plan.minRepetition,
                            plan.maxRepetition,
                            plan.hasLeadingAssertions,
                            MultiAnchorCompiler.isWideLeadingClass(plan.leadingClass!!),
                            LeadingExpansion.forwardScanChars(plan),
                            inner
                        )
                    else
                        null
                }

                is StartPlan.LineAnchor -> if (hasWordBoundary) null else LineAnchor(plan.acceleration)
                else -> throw AssertionError(plan)
            }
        }

        /**
         * Finds the next candidate match start position at or after `fromIndex` using
         * pattern-matched devirtualization.
         * 
         * 
         * Direct sealed-type pattern matching avoids `invokeinterface` dispatch overhead on hot
         * matching loops. HotSpot C2 does not automatically devirtualize megamorphic interface calls with
         *  3 implementations across the JVM lifecycle; switching over the sealed subtypes here allows
         * C2 to inline candidate searches directly into caller loops.
         */
        fun findNextCandidate(
            accelerator: StringStartAccelerator, text: String, fromIndex: Int, unixLines: Boolean
        ): Int {
            return findNextCandidateString(accelerator, text, fromIndex, unixLines)
        }

        /** Uses the same plans on a bounded, region-relative String view.  */
        fun findNextCandidate(
            accelerator: StringStartAccelerator,
            text: StringRegionInputScanner,
            fromIndex: Int,
            unixLines: Boolean
        ): Int {
            return findNextCandidateRegion(accelerator, text, fromIndex, unixLines)
        }

        /**
         * Dispatches composite plans without copying their input view.
         *
         *
         * Routes through distinctly named helpers so Kotlin overload resolution cannot recurse into
         * this CharSequence dispatcher when the argument is a [String] or
         * [StringRegionInputScanner] (both are [CharSequence] subtypes).
         */
        fun findNextCandidate(
            accelerator: StringStartAccelerator, text: CharSequence?, fromIndex: Int, unixLines: Boolean
        ): Int {
            return if (text is String)
                findNextCandidateString(accelerator, text, fromIndex, unixLines)
            else
                findNextCandidateRegion(accelerator, text as StringRegionInputScanner, fromIndex, unixLines)
        }

        private fun findNextCandidateString(
            accelerator: StringStartAccelerator, text: String, fromIndex: Int, unixLines: Boolean
        ): Int {
            return when (accelerator) {
                is Literal -> accelerator.findCandidate(text, fromIndex, unixLines)
                is CaseInsensitiveLiteral -> accelerator.findCandidate(text, fromIndex, unixLines)
                is UnicodeCaseInsensitiveLiteral -> accelerator.findCandidate(text, fromIndex, unixLines)
                is FixedOffset -> accelerator.findCandidate(text, fromIndex, unixLines)
                is FixedOffsetClass -> accelerator.findCandidate(text, fromIndex, unixLines)
                is CharClass -> accelerator.findCandidate(text, fromIndex, unixLines)
                is LineAnchor -> accelerator.findCandidate(text, fromIndex, unixLines)
                is LeadingExpansion -> accelerator.findCandidate(text, fromIndex, unixLines)
                else -> throw AssertionError(accelerator)
            }
        }

        private fun findNextCandidateRegion(
            accelerator: StringStartAccelerator,
            text: StringRegionInputScanner,
            fromIndex: Int,
            unixLines: Boolean
        ): Int {
            return when (accelerator) {
                is Literal -> text.indexOf(accelerator.prefix!!, fromIndex, text.length)
                is CaseInsensitiveLiteral -> text.indexOfIgnoreCase(
                    accelerator.prefix!!,
                    accelerator.anchorOffset,
                    accelerator.anchorLow,
                    accelerator.anchorHigh,
                    fromIndex
                )

                is UnicodeCaseInsensitiveLiteral -> {
                    val chain = accelerator.classHashChain
                    if (chain != null) {
                        val candidate: Int =
                            chain.search(text, fromIndex, WorkLimit.forRemaining(text.length - fromIndex))
                        if (candidate != -2) {
                            return candidate
                        }
                    }
                    Utf16.indexOfUnicodeIgnoreCase(text.toString(), accelerator.prefix!!, fromIndex)
                }

                is CharClass -> text.indexOfCharClass(accelerator.scanInfo!!, fromIndex)
                is FixedOffset -> accelerator.findCandidate(text, fromIndex, unixLines)
                is FixedOffsetClass -> accelerator.findCandidate(text, fromIndex, unixLines)
                is LineAnchor -> accelerator.findCandidate(text, fromIndex, unixLines)
                is LeadingExpansion -> accelerator.findCandidate(text, fromIndex, unixLines)
                else -> -1
            }
        }

        private fun indexOfPlanned(
            text: CharSequence,
            literal: String,
            anchorOffset: Int,
            anchor: Char,
            singleAsciiChar: Int,
            fromIndex: Int
        ): Int {
            return if (text is String)
                StringLiteralSearch.indexOfPlanned(
                    text, literal, anchorOffset, anchor, singleAsciiChar, fromIndex
                )
            else
                (text as StringRegionInputScanner).indexOf(literal, fromIndex, text.length)
        }

        private fun indexOfClass(text: CharSequence, info: CharClassScanInfo, fromIndex: Int): Int {
            return if (text is String)
                StringInputScanner.indexOfCharClass(text, info, fromIndex)
            else
                (text as StringRegionInputScanner).indexOfCharClass(info, fromIndex)
        }
    }
}
