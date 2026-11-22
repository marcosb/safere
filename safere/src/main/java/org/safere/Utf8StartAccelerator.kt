// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.safere.CharClassScanInfo.UnicodeSmallSet
import org.safere.MultiAnchorDescriptor.StartPlan
import org.safere.Utf8StartAccelerator.LeadingExpansion.ExcludedSet
import kotlin.jvm.JvmRecord
import kotlin.math.*
/**
 * Encapsulates pre-computed start acceleration search strategies for finding candidate match
 * positions in a [Utf8InputScanner].
 */
internal interface Utf8StartAccelerator {
    /** A search-local cursor for accelerators that scan multiple folded byte variants.  */
    interface SearchCursor {
        fun findCandidate(scanner: Utf8InputScanner, fromIndex: Int): Int
    }

    /** Returns the tuning and diagnostic policy for this accelerator.  */
    fun policy(): AcceleratorPolicy {
        return AcceleratorPolicy.Companion.DEFAULT
    }

    // The arrays are immutable search metadata owned by this internal accelerator.
    @JvmRecord
    data class Literal(
        val prefixUtf8: ByteArray,
        val prefixUtf8Failure: IntArray,
        val prefixUtf8Shifts: IntArray?,
        val rareByteOffset: Int) : Utf8StartAccelerator {
        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.Companion.LITERAL
        }

        fun findCandidate(scanner: Utf8InputScanner, fromIndex: Int): Int {
            return scanner.indexOf(
                prefixUtf8, prefixUtf8Failure, prefixUtf8Shifts, fromIndex, rareByteOffset
            )
        }



        companion object {
            fun create(prefix: String): Literal {
                return from(Utf8LiteralStartPlan.from(prefix))
            }

            fun from(plan: Utf8LiteralStartPlan): Literal {
                return Literal(
                    plan.prefixUtf8,
                    plan.prefixUtf8Failure,
                    plan.prefixUtf8Shifts,
                    plan.rareByteOffset,
                )
            }
        }
    }

    @JvmRecord
    data class CaseInsensitiveLiteral(
        val prefix: String,
        val failure: IntArray,
        val anchorOffset: Int,
        val anchorLow: Byte,
        val anchorHigh: Byte,
        val anchorOffset2: Int,
        val anchorLow2: Byte,
        val anchorHigh2: Byte) : Utf8StartAccelerator {
        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.Companion.LITERAL
        }

        fun findCandidate(scanner: Utf8InputScanner, fromIndex: Int): Int {
            if (anchorOffset2 >= 0) {
                return scanner.indexOfPairIgnoreCase(
                    prefix,
                    failure,
                    anchorOffset,
                    anchorLow,
                    anchorHigh,
                    anchorOffset2,
                    anchorLow2,
                    anchorHigh2,
                    fromIndex
                )
            }
            return scanner.indexOfIgnoreCase(
                prefix, failure, anchorOffset, anchorLow, anchorHigh, fromIndex
            )
        }



        companion object {
            fun create(prefix: String): Utf8StartAccelerator? {
                val plan = Utf8CaseInsensitiveLiteralPlan.from(prefix) ?: return null
                return from(plan)
            }

            fun from(plan: Utf8CaseInsensitiveLiteralPlan): CaseInsensitiveLiteral {
                return CaseInsensitiveLiteral(
                    plan.prefix,
                    plan.failure,
                    plan.anchorOffset,
                    plan.anchorLow,
                    plan.anchorHigh,
                    plan.anchorOffset2,
                    plan.anchorLow2,
                    plan.anchorHigh2,
                )
            }
        }
    }

    /** A bounded byte filter for Unicode-folded literal prefixes on UTF-8 input.  */
    class UnicodeCaseInsensitiveLiteral private constructor(
        private val prefixFoldVariants: MutableList<IntArray>,
        private val anchorByteOffset: Int,
        private val anchorVariants: MutableList<EncodedFold>,
    ) : Utf8StartAccelerator {

        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.Companion.LITERAL
        }

        fun findCandidate(scanner: Utf8InputScanner, fromIndex: Int): Int {
            return SearchCursorImpl().findCandidate(scanner, fromIndex)
        }

        /** Keeps candidate verification work bounded across one forward search.  */
        internal inner class SearchCursorImpl : SearchCursor {
            private var exhausted = false
            private var verificationWork: Long = 0
            private var workLimit: Long = -1

            override fun findCandidate(scanner: Utf8InputScanner, fromIndex: Int): Int {
                val start = max(0, fromIndex)
                if (exhausted) {
                    return start
                }
                if (anchorByteOffset > scanner.length - start) {
                    return -1
                }
                if (workLimit < 0) {
                    workLimit = WorkLimit.forRemaining(scanner.length - start)
                }
                var searchFrom = start + anchorByteOffset
                while (searchFrom < scanner.length) {
                    val windowEnd =
                        searchFrom + min(ANCHOR_SCAN_WINDOW, scanner.length - searchFrom) - 1
                    var nextHit = Int.MAX_VALUE
                    for (variant in anchorVariants) {
                        val hit =
                            scanner.indexOfWithin(
                                variant.bytes, variant.failure!!, searchFrom, min(windowEnd, nextHit - 1)
                            )
                        if (hit >= 0) {
                            nextHit = hit
                            if (hit == searchFrom) {
                                break
                            }
                        }
                    }
                    if (nextHit == Int.MAX_VALUE) {
                        searchFrom = windowEnd + 1
                        continue
                    }
                    val candidate = nextHit - anchorByteOffset
                    if (candidate >= start && scanner.isCodePointBoundary(candidate)
                        && matchesPrefix(scanner, candidate)
                    ) {
                        return candidate
                    }
                    verificationWork += prefixFoldVariants.size.toLong()
                    if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                        // A search can call this cursor again at every subsequent byte. Keep the fallback
                        // sticky so candidate verification cannot restart from scratch.
                        exhausted = true
                        return start
                    }
                    searchFrom = nextHit + 1
                }
                return -1
            }
        }

        private fun matchesPrefix(scanner: Utf8InputScanner, candidate: Int): Boolean {
            var position = candidate
            for (foldVariants in prefixFoldVariants) {
                if (position >= scanner.length) {
                    return false
                }
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record()
                }
                val decoded = scanner.decodeForward(position)
                val actual: Int = InputScanner.Companion.codePoint(decoded)
                var equivalent = false
                for (variant in foldVariants) {
                    if (actual == variant) {
                        equivalent = true
                        break
                    }
                }
                if (!equivalent) {
                    return false
                }
                position = InputScanner.Companion.position(decoded)
            }
            return true
        }

        private class EncodedFold(val bytes: ByteArray, val failure: IntArray?)

        companion object {
            private const val ANCHOR_SCAN_WINDOW = 256

            fun create(prefix: String): Utf8StartAccelerator? {
                val plan = Utf8UnicodeCaseInsensitiveLiteralPlan.from(prefix) ?: return null
                return from(plan)
            }

            fun from(plan: Utf8UnicodeCaseInsensitiveLiteralPlan): UnicodeCaseInsensitiveLiteral {
                val anchorVariants = ArrayList<EncodedFold>(plan.anchorVariants.size)
                for (variant in plan.anchorVariants) {
                    anchorVariants.add(EncodedFold(variant.bytes, variant.failure))
                }
                return UnicodeCaseInsensitiveLiteral(
                    plan.prefixFoldVariants.toMutableList(),
                    plan.anchorByteOffset,
                    anchorVariants,
                )
            }
        }
    }

    @JvmRecord
    data class FixedOffset(val fixedOffset: FixedOffsetLiteral?, val charClassPrefix: CharClassScanInfo?) :
        Utf8StartAccelerator {
        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.Companion.LITERAL
        }

        fun findCandidate(scanner: Utf8InputScanner, fromIndex: Int): Int {
            return Companion.nextFixedOffsetCandidate(scanner, fixedOffset!!, charClassPrefix, fromIndex)
        }



        companion object {
            private fun nextFixedOffsetCandidate(
                scanner: Utf8InputScanner,
                fixedOffsetLiteral: FixedOffsetLiteral,
                charClassPrefix: CharClassScanInfo?,
                searchFrom: Int
            ): Int {
                var literalFrom = searchFrom + fixedOffsetLiteral.minOffset()
                val discreteOffsets = fixedOffsetLiteral.discreteOffsets()
                while (literalFrom <= scanner.length) {
                    val literalStart =
                        scanner.indexOf(
                            fixedOffsetLiteral.utf8(),
                            fixedOffsetLiteral.failure(),
                            fixedOffsetLiteral.shifts(),
                            literalFrom
                        )
                    if (literalStart < 0) {
                        return -1
                    }
                    if (charClassPrefix != null) {
                        if (discreteOffsets != null && discreteOffsets.size == 1) {
                            val candidateStart = literalStart - discreteOffsets[0]
                            if (candidateStart >= searchFrom) {
                                val first =
                                    if (candidateStart < scanner.length) scanner.codePointAt(candidateStart) else -1
                                if (first >= 0 && charClassPrefix.contains(first)) {
                                    return candidateStart
                                }
                            }
                            literalFrom = literalStart + 1
                            continue
                        } else if (discreteOffsets != null) {
                            val resolved: Int =
                                resolveMultiOffsetStart(
                                    scanner,
                                    fixedOffsetLiteral,
                                    discreteOffsets,
                                    charClassPrefix,
                                    literalStart,
                                    searchFrom
                                )
                            if (resolved >= 0) {
                                return resolved
                            }
                            literalFrom = literalStart + 1
                            continue
                        } else if (fixedOffsetLiteral.minOffset() == fixedOffsetLiteral.maxOffset()) {
                            val candidateStart: Int =
                                retreatedStartInClass(
                                    scanner, fixedOffsetLiteral, charClassPrefix, literalStart, searchFrom
                                )
                            if (candidateStart >= 0) {
                                return candidateStart
                            }
                            literalFrom = literalStart + 1
                            continue
                        }
                    }
                    return max(
                        searchFrom, scanner.retreatByCodePoints(literalStart, fixedOffsetLiteral.maxOffset())
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
             * 
             * 
             * Discrete offsets are only recorded for all-ASCII prefixes, so they are byte counts here.
             */
            private fun resolveMultiOffsetStart(
                scanner: InputScanner,
                fixedOffsetLiteral: FixedOffsetLiteral,
                discreteOffsets: IntArray,
                charClassPrefix: CharClassScanInfo,
                literalStart: Int,
                searchFrom: Int
            ): Int {
                for (i in discreteOffsets.size - 1 downTo 0) {
                    val start = literalStart - discreteOffsets[i]
                    if (start >= searchFrom
                        && start < scanner.length
                        && charClassPrefix.contains(scanner.codePointAt(start))
                    ) {
                        return max(
                            searchFrom, min(start, literalStart + 1 - fixedOffsetLiteral.maxOffset())
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
                scanner: InputScanner,
                fixedOffsetLiteral: FixedOffsetLiteral,
                charClassPrefix: CharClassScanInfo,
                literalStart: Int,
                searchFrom: Int
            ): Int {
                val candidateStart =
                    scanner.retreatByCodePoints(literalStart, fixedOffsetLiteral.maxOffset())
                if (candidateStart >= searchFrom) {
                    val first = if (candidateStart < scanner.length) scanner.codePointAt(candidateStart) else -1
                    if (first >= 0 && charClassPrefix.contains(first)) {
                        return candidateStart
                    }
                }
                return -1
            }
        }
    }

    // nonAsciiUtf8 is an internal precomputed UTF-8 byte sequence, never mutated or exposed.
    @JvmRecord
    data class CharClass(val scanInfo: CharClassScanInfo?, val nonAsciiUtf8: ByteArray?) : Utf8StartAccelerator {
        internal constructor(scanInfo: CharClassScanInfo?) :
            this(scanInfo, Utf8CharClassStartPlan.nonAsciiUtf8(scanInfo))

        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.Companion.CHAR_CLASS
        }

        fun findCandidate(scanner: Utf8InputScanner, fromIndex: Int): Int {
            if (scanInfo == null) {
                return fromIndex
            }
            return when (scanInfo) {
                is UnicodeSmallSet if nonAsciiUtf8 != null -> {
                    val chars: CharArray = scanInfo.chars!!
                    val length = scanner.length
                    if (chars.size == 1) {
                        scanner.indexOfUtf8Sequence(nonAsciiUtf8, fromIndex, length)
                    } else {
                        val ascii = chars[0].code
                        val first = scanner.indexOfAsciiOrNonAscii(ascii, fromIndex, length)
                        if (first < 0 || scanner.asciiAt(first) == ascii) {
                            first
                        } else {
                            scanner.indexOfMixedPair(ascii, nonAsciiUtf8, first, length)
                        }
                    }
                }

                is CharClassScanInfo.AsciiSmallSet -> {
                    val chars: CharArray = scanInfo.chars!!
                    when (chars.size) {
                        1 -> scanner.indexOfAscii(chars[0].code, fromIndex, scanner.length)
                        2 -> scanner.indexOfAsciiPair(chars[0].code, chars[1].code, fromIndex, scanner.length)
                        3 -> scanner.indexOfCodePointClass(
                            scanInfo.ranges,
                            scanInfo.bitmap0,
                            scanInfo.bitmap1,
                            fromIndex,
                            scanner.length
                        )

                        else -> scanner.indexOfCodePointClass(
                            scanInfo.ranges,
                            scanInfo.bitmap0,
                            scanInfo.bitmap1,
                            fromIndex,
                            scanner.length
                        )
                    }
                }

                else -> scanner.indexOfCodePointClass(
                    scanInfo.ranges, scanInfo.bitmap0, scanInfo.bitmap1, fromIndex, scanner.length
                )
            }
        }



        companion object {
            fun from(plan: Utf8CharClassStartPlan): CharClass {
                return CharClass(plan.scanInfo, plan.nonAsciiUtf8)
            }
        }
    }

    @JvmRecord
    data class Teddy(val model: TeddyModel?) : Utf8StartAccelerator {
        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.Companion.VECTOR_MULTI_LITERAL
        }

        fun findCandidate(scanner: Utf8InputScanner, fromIndex: Int): Int {
            val window = scanner.length - fromIndex
            val provider = VectorScanProviders.providerFor(ScanKind.TEDDY, window)
            if (provider == null) {
                ScanAudit.record(ScanKind.TEDDY, ScanDirection.FORWARD, window, ScanPath.DECLINED)
                return fromIndex
            }
            val idx =
                provider.indexOfTeddy(
                    scanner.bytes(), scanner.offset(), scanner.length, model, fromIndex
                )
            if (idx != VectorScanProvider.Companion.UNSUPPORTED) {
                ScanAudit.record(ScanKind.TEDDY, ScanDirection.FORWARD, window, ScanPath.VECTOR)
                return idx
            }
            ScanAudit.record(ScanKind.TEDDY, ScanDirection.FORWARD, window, ScanPath.SCALAR)
            val len = scanner.length
            val minLen = model!!.minLength()
            val bytes = scanner.bytes()
            val offset = scanner.offset()
            for (i in fromIndex..len - minLen) {
                for (rawLit in model.literals()!!) {
                    val lit = rawLit!!
                    if (i + lit.length <= len
                        && Ascii.regionMatches(bytes!!, offset + i, lit, lit.length)
                    ) {
                        return i
                    }
                }
            }
            return -1
        }


    }

    /**
     * Scans a required interior class following an exact-width ASCII prefix.
     * 
     * 
     * ASCII offsets are also byte offsets. After a bounded number of cheap leading-check failures,
     * scans jump to the next leading-class hit before resuming the interior scan. Both scans advance
     * monotonically, including when interior hits are dense and leading hits are absent. ASCII
     * leading hits exclude continuation-byte starts.
     */
    @JvmRecord
    data class FixedOffsetClass(val scanInfo: CharClassScanInfo?, val offset: Int, val leadingClass: CharClassScanInfo?) :
        Utf8StartAccelerator {
        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.Companion.CHAR_CLASS
        }

        fun findCandidate(scanner: Utf8InputScanner, fromIndex: Int): Int {
            val start = max(0, fromIndex)
            if (offset >= scanner.length - start) {
                return -1
            }
            var position = start + offset
            var cheapRejections = 0
            while (position < scanner.length) {
                val hit =
                    scanner.indexOfCodePointClass(
                        scanInfo!!.ranges,
                        scanInfo.bitmap0,
                        scanInfo.bitmap1,
                        position,
                        scanner.length
                    )
                if (hit < 0) {
                    return -1
                }
                val candidate = hit - offset
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record()
                }
                if (leadingClass!!.contains(scanner.asciiAt(candidate))) {
                    return candidate
                }
                if (cheapRejections < AcceleratorPolicy.Companion.FIXED_OFFSET_CLASS_CHEAP_REJECTIONS) {
                    cheapRejections++
                    position = hit + 1
                    continue
                }
                val leadingRanges = leadingClass.ranges
                // A singleton leading class can reuse the dedicated byte scanner.
                val nextLeading =
                    if (leadingRanges.size == 2 && leadingRanges[0] == leadingRanges[1])
                        scanner.indexOfAscii(leadingRanges[0], candidate + 1, scanner.length)
                    else
                        scanner.indexOfCharClass(leadingClass, candidate + 1)
                if (nextLeading < 0 || offset >= scanner.length - nextLeading) {
                    return -1
                }
                position = nextLeading + offset
            }
            return -1
        }


    }

    @JvmRecord
    data class LeadingExpansion(
        val leadingClass: CharClassScanInfo?,
        val minRepetition: Int,
        val maxRepetition: Int,
        val hasLeadingAssertions: Boolean,
        val wideLeadingClass: Boolean,
        val excludedSet: ExcludedSet?,
        val inner: Utf8StartAccelerator?) : Utf8StartAccelerator {
        /**
         * The code points a wide leading class excludes, split into ASCII members and the UTF-8
         * encodings of the others. Surrogates never occur in valid UTF-8 and are left out.
         */
        // Arrays are immutable, privately owned scanner metadata; array identity is never observed.
        @JvmRecord
        internal data class ExcludedSet(val ascii: IntArray?, val nonAscii: Array<ByteArray>?) {
            /**
             * Returns false only when `[from, to)` holds no excluded code point. With non-ASCII
             * members and up to three ASCII members, any non-ASCII byte also counts as a possible hit.
             */
            fun mayContain(scanner: Utf8InputScanner, from: Int, to: Int): Boolean {
                val anyNonAscii = nonAscii != null && nonAscii.size > 0
                val hit =
                    when (ascii!!.size) {
                        1 -> if (anyNonAscii)
                            scanner.indexOfAsciiOrNonAscii(ascii!![0], from, to)
                        else
                            scanner.indexOfAscii(ascii!![0], from, to)

                        2 -> if (anyNonAscii)
                            scanner.indexOfAsciiPairOrNonAscii(ascii!![0], ascii[1], from, to)
                        else
                            scanner.indexOfAsciiPair(ascii!![0], ascii[1], from, to)

                        3 -> if (anyNonAscii)
                            scanner.indexOfAsciiTripleOrNonAscii(ascii!![0], ascii[1], ascii[2], from, to)
                        else
                            scanner.indexOfAsciiTriple(ascii!![0], ascii[1], ascii[2], from, to)

                        else -> indexOfAny(scanner, from, to)
                    }
                return hit >= 0 && hit < to
            }

            private fun indexOfAny(scanner: Utf8InputScanner, from: Int, to: Int): Int {
                for (c in ascii!!) {
                    val hit = scanner.indexOfAscii(c, from, to)
                    if (hit >= 0) {
                        return hit
                    }
                }
                for (sequence in nonAscii!!) {
                    val hit = scanner.indexOfUtf8Sequence(sequence, from, to)
                    if (hit >= 0) {
                        return hit
                    }
                }
                return -1
            }



            companion object {
                /**
                 * Returns the excluded set when a forward search for it can stand in for the backward walk:
                 * the repetition must be unbounded with at most one required repetition. Otherwise null.
                 */
                fun of(le: StartPlan.LeadingExpansion): ExcludedSet? {
                    val plan = Utf8LeadingExpansionExcludedPlan.from(le) ?: return null
                    return from(plan)
                }

                fun from(plan: Utf8LeadingExpansionExcludedPlan): ExcludedSet {
                    return ExcludedSet(plan.ascii, plan.nonAscii)
                }
            }
        }

        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.Companion.LEADING_EXPANSION.withStrategy(inner!!.policy().strategy)
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

        fun findInnerCandidate(scanner: Utf8InputScanner, searchPos: Int): Int {
            if (wideLeadingClass && inner is Literal && inner.prefixUtf8 != null) {
                // Search the way the UTF-8 reject prefilter does, since find() skips that prefilter when
                // this search subsumes it (see Pattern.startSubsumesFindReject).
                return scanner.indexOfInTail(inner.prefixUtf8!!, inner.prefixUtf8Failure!!, searchPos)
            }
            return Companion.findNextCandidate(inner!!, scanner, searchPos)
        }

        fun expandBackward(scanner: Utf8InputScanner, innerMatch: Int, fromIndex: Int): Int {
            var start = innerMatch
            var count = 0
            while (start > fromIndex) {
                var cp = scanner.singleUnitCodePointBefore(start)
                val prevPos: Int
                if (cp >= 0) {
                    prevPos = start - 1
                } else {
                    val decoded = scanner.decodeBackward(start)
                    cp = InputScanner.Companion.codePoint(decoded)
                    prevPos = InputScanner.Companion.position(decoded)
                }
                if (!leadingClass!!.contains(cp)) {
                    break
                }
                if (count + 1 > maxRepetition) {
                    break
                }
                count++
                start = prevPos
            }
            return start
        }

        fun findCandidate(scanner: Utf8InputScanner, fromIndex: Int): Int {
            var searchPos = max(0, fromIndex)
            val textLen = scanner.length
            // A wide class's walk covers the rest of the line, so a skip shorter than
            // MIN_WIDE_LEADING_SKIP saves less than the literal scan and the walk cost. Such searches
            // start at the search start, as they would with no start plan.
            if (wideLeadingClass && textLen - searchPos < MultiAnchorCompiler.MIN_WIDE_LEADING_SKIP) {
                return searchPos
            }
            while (searchPos < textLen) {
                val innerMatch = findInnerCandidate(scanner, searchPos)
                if (innerMatch < 0) {
                    return -1
                }
                if (wideLeadingClass
                    && innerMatch - fromIndex < MultiAnchorCompiler.MIN_WIDE_LEADING_SKIP
                ) {
                    return max(0, fromIndex)
                }
                // A forward scan can show that the walk would reach the search start. Probe only the
                // first hit: an excluded code point before it will also precede every later hit.
                if (excludedSet != null && searchPos == fromIndex && innerMatch > fromIndex && !excludedSet.mayContain(
                        scanner,
                        fromIndex,
                        innerMatch
                    )
                ) {
                    return fromIndex
                }
                var start = innerMatch
                var count = 0
                while (start > fromIndex) {
                    var cp = scanner.singleUnitCodePointBefore(start)
                    val prevPos: Int
                    if (cp >= 0) {
                        prevPos = start - 1
                    } else {
                        val decoded = scanner.decodeBackward(start)
                        cp = InputScanner.Companion.codePoint(decoded)
                        prevPos = InputScanner.Companion.position(decoded)
                    }
                    if (!leadingClass!!.contains(cp)) {
                        break
                    }
                    if (count + 1 > maxRepetition) {
                        break
                    }
                    count++
                    start = prevPos
                }
                if (count >= minRepetition) {
                    return start
                }
                searchPos = innerMatch + 1
            }
            return -1
        }


    }

    @JvmRecord
    data class MultiLiteral(val info: MultiLiteralInfo?, val teddyModel: TeddyModel?) : Utf8StartAccelerator {
        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.Companion.VECTOR_MULTI_LITERAL
        }

        fun findCandidate(scanner: Utf8InputScanner, fromIndex: Int): Int {
            val window = scanner.length - fromIndex
            val provider = VectorScanProviders.providerFor(ScanKind.MULTI_LITERAL, window)
            if (provider != null) {
                val idx =
                    provider.indexOfMultiLiteral(
                        scanner.bytes(),
                        scanner.offset(),
                        scanner.length,
                        info!!.literals,
                        info.anchorChars,
                        info.anchorOffsets,
                        info.anchorRanges,
                        info.minLength,
                        teddyModel,
                        fromIndex
                    )
                if (idx != VectorScanProvider.Companion.UNSUPPORTED) {
                    ScanAudit.record(ScanKind.MULTI_LITERAL, ScanDirection.FORWARD, window, ScanPath.VECTOR)
                    return idx
                }
                ScanAudit.record(ScanKind.MULTI_LITERAL, ScanDirection.FORWARD, window, ScanPath.DECLINED)
                return fromIndex
            }
            ScanAudit.record(ScanKind.MULTI_LITERAL, ScanDirection.FORWARD, window, ScanPath.SCALAR)
            return findScalar(scanner, fromIndex)
        }

        private fun findScalar(scanner: Utf8InputScanner, fromIndex: Int): Int {
            val len = scanner.length
            val infoNonNull = info!!
            val minLen = infoNonNull.minLength
            val literals = infoNonNull.literals!!
            val anchorChars = infoNonNull.anchorChars!!
            val anchorOffsets = infoNonNull.anchorOffsets!!
            val bytes = scanner.bytes()!!
            val offset = scanner.offset()
            var verificationWork: Long = 0
            val workLimit = WorkLimit.forRemaining(len - fromIndex)

            for (i in fromIndex..len - minLen) {
                val value = bytes[offset + i].toInt() and 0xFF
                for (k in literals.indices) {
                    if (value == (anchorChars[k].code and 0xFF)) {
                        val lit = literals[k]!!
                        val start = i - anchorOffsets[k]
                        if (start >= fromIndex && start + lit.length <= len) {
                            if (WorkCounterConfig.ENABLED) {
                                WorkCounter.record(lit.length.toLong())
                            }
                            if (Ascii.regionMatches(bytes, offset + start, lit, lit.length)) {
                                return start
                            }
                            verificationWork += lit.length.toLong()
                            if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                                return fromIndex
                            }
                        }
                    }
                }
            }
            return -1
        }


    }

    companion object {
        /**
         * Creates a [Utf8StartAccelerator] for the given pattern descriptor, or `null` if no
         * acceleration strategy applies.
         */
        fun create(descriptor: MultiAnchorDescriptor?, hasWordBoundary: Boolean): Utf8StartAccelerator? {
            if (descriptor == null) {
                return null
            }
            return create(descriptor.startPlan(), hasWordBoundary)
        }

        fun create(
            plan: StartPlan?, hasWordBoundary: Boolean
        ): Utf8StartAccelerator? {
            if (plan == null || plan is StartPlan.None) {
                return null
            }
            return when (plan) {
                is StartPlan.None -> null
                is StartPlan.Literal -> if (plan.foldCase)
                    (if (Ascii.isAscii(plan.prefix))
                        CaseInsensitiveLiteral.create(plan.prefix!!)
                    else
                        UnicodeCaseInsensitiveLiteral.create(plan.prefix!!))
                else
                    Literal.create(plan.prefix!!)

                is StartPlan.CharClass ->
                    if (hasWordBoundary || plan.scanInfo == null || !plan.scanInfo.isSelective) null else CharClass(plan.scanInfo)
                is StartPlan.FixedOffset -> FixedOffset(plan.fol, plan.leadingClass)
                is StartPlan.FixedOffsetClass -> FixedOffsetClass(plan.scanInfo, plan.offset, plan.leadingClass)
                is StartPlan.MultiLiteral -> {
                    if (hasWordBoundary) {
                        return null
                    }
                    if (VectorScanProviders.vectorProviderAvailable()) {
                        val info: MultiLiteralInfo? = plan.multiLiteralInfo
                        if (info != null) {
                            val teddy: TeddyModel? =
                                if (VectorScanProviders.vectorProviderAvailable())
                                    TeddyModel.compileForSelectedProvider(plan.literals)
                                else
                                    null
                            return MultiLiteral(info, teddy)
                        }
                    }
                    if (VectorScanProviders.vectorProviderAvailable()) {
                        val model: TeddyModel? = TeddyModel.compileForSelectedProvider(plan.literals)
                        if (model != null) {
                            return Teddy(model)
                        }
                    }
                    if (plan.fallbackClass != null && plan.fallbackClass.isSelective) {
                        return CharClass(plan.fallbackClass!!)
                    }
                    null
                }

                is StartPlan.LeadingExpansion -> {
                    val inner: Utf8StartAccelerator? = create(plan.innerPlan, hasWordBoundary)
                    // This wrapper does not retain the folded filter's per-search scan cursor, and an optional
                    // single-character leading class before a non-ASCII inner class is faster in the UTF-8
                    // byte DFA than running an un-memoized scalar UTF-8 code-point scan plus inner DFA check.
                    if (inner is UnicodeCaseInsensitiveLiteral
                        || (plan.maxRepetition == 1 && inner is CharClass
                                && !inner.scanInfo!!.isAscii)
                    ) {
                        return null
                    }
                    if (inner != null)
                        LeadingExpansion(
                            plan.leadingClass,
                            plan.minRepetition,
                            plan.maxRepetition,
                            plan.hasLeadingAssertions,
                            MultiAnchorCompiler.isWideLeadingClass(plan.leadingClass!!),
                            ExcludedSet.of(plan),
                            inner
                        )
                    else
                        null
                }

                is StartPlan.LineAnchor -> null
                else -> null
            }
        }

        /**
         * Finds the next candidate match start position at or after `pos` using pattern-matched
         * devirtualization.
         * 
         * 
         * Direct sealed-type pattern matching avoids `invokeinterface` dispatch overhead on hot
         * matching loops. HotSpot C2 does not automatically devirtualize megamorphic interface calls with
         *  3 implementations across the JVM lifecycle; switching over the sealed record subtypes here
         * allows C2 to inline candidate searches directly into caller loops.
         */
        fun findNextCandidate(
            accelerator: Utf8StartAccelerator, scanner: Utf8InputScanner, pos: Int
        ): Int {
            return when (accelerator) {
                is Literal -> accelerator.findCandidate(scanner, pos)
                is CaseInsensitiveLiteral -> accelerator.findCandidate(scanner, pos)
                is UnicodeCaseInsensitiveLiteral -> accelerator.findCandidate(scanner, pos)
                is FixedOffset -> accelerator.findCandidate(scanner, pos)
                is FixedOffsetClass -> accelerator.findCandidate(scanner, pos)
                is CharClass -> accelerator.findCandidate(scanner, pos)
                is Teddy -> accelerator.findCandidate(scanner, pos)
                is MultiLiteral -> accelerator.findCandidate(scanner, pos)
                is LeadingExpansion -> accelerator.findCandidate(scanner, pos)
                else -> error("unknown accelerator")
            }
        }

        fun searchCursor(accelerator: Utf8StartAccelerator?): SearchCursor? {
            return if (accelerator is UnicodeCaseInsensitiveLiteral)
                accelerator.SearchCursorImpl()
            else
                null
        }
    }
}
