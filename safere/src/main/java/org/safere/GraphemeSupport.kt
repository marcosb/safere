// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.math.max
import kotlin.math.min

/** Grapheme segmentation state and SafeRE's grapheme-boundary policy.  */
internal class GraphemeSupport private constructor() {
    /**
     * Per-input grapheme segmentation context.
     * 
     * 
     * UAX #29 boundary checks are finite-state, but some rules need state from earlier code
     * points. Keep that state here so engines can answer boundary questions in O(1) while scanning.
     */
    internal open class Context internal constructor(internal val text: InputScanner?) {
        private var regionalIndicatorStartsBefore: IntArray? = null
        private lateinit var regionalIndicatorRunStartBefore: IntArray
        private var extendedPictographicStartBefore: IntArray? = null
        private lateinit var indicConjunctSequenceStartBefore: IntArray
        private var indicConjunctLinkerStartBefore: IntArray? = null
        open val length: Int
            get() {
                return text!!.length
            }

        open fun decodeForward(pos: Int): Long {
            return text!!.decodeForward(pos)
        }

        fun hasEvenRegionalIndicatorsBefore(pos: Int, regionStart: Int): Boolean {
            if (text == null) {
                return true
            }
            val start = max(0, min(regionStart, length))
            if (pos <= start || pos > length) {
                return true
            }
            ensureRegionalIndicatorRunData()
            val runStart = regionalIndicatorRunStartBefore[pos]
            val countStart = max(start, runStart)
            val regionalIndicatorsBefore =
                regionalIndicatorStartsBefore!![pos] - regionalIndicatorStartsBefore!![countStart]
            return (regionalIndicatorsBefore and 1) == 0
        }

        fun hasExtendedPictographicBefore(pos: Int, regionStart: Int): Boolean {
            if (text == null) {
                return false
            }
            val start = max(0, min(regionStart, length))
            if (pos <= start || pos > length) {
                return false
            }
            ensureExtendedPictographicData()
            val pictographicStart = extendedPictographicStartBefore!![pos]
            return pictographicStart >= start
        }

        fun hasIndicConjunctLinkerBefore(pos: Int, regionStart: Int): Boolean {
            if (text == null) {
                return false
            }
            val start = max(0, min(regionStart, length))
            if (pos <= start || pos > length) {
                return false
            }
            ensureIndicConjunctData()
            return indicConjunctLinkerStartBefore!![pos] >= start
        }

        fun hasIndicConjunctSequenceBefore(pos: Int, regionStart: Int): Boolean {
            if (text == null) {
                return false
            }
            val start = max(0, min(regionStart, length))
            if (pos <= start || pos > length) {
                return false
            }
            ensureIndicConjunctData()
            return indicConjunctSequenceStartBefore[pos] >= start
        }

        private fun ensureRegionalIndicatorRunData() {
            if (regionalIndicatorStartsBefore != null) {
                return
            }
            regionalIndicatorStartsBefore = IntArray(length + 1)
            regionalIndicatorRunStartBefore = IntArray(length + 1)
            var runStart = 0
            var runLength = 0
            var pos = 0
            while (pos < length) {
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record()
                }
                val decoded = decodeForward(pos)
                val cp: Int = InputScanner.codePoint(decoded)
                val next: Int = InputScanner.position(decoded)
                if (isRegionalIndicator(cp)) {
                    if (runLength == 0) {
                        runStart = pos
                    }
                    runLength++
                    for (i in pos + 1..next) {
                        regionalIndicatorStartsBefore!![i] = runLength
                        regionalIndicatorRunStartBefore[i] = runStart
                    }
                } else {
                    runLength = 0
                }
                pos = next
            }
        }

        private fun ensureIndicConjunctData() {
            if (indicConjunctLinkerStartBefore != null) {
                return
            }
            indicConjunctSequenceStartBefore = IntArray(length + 1)
            indicConjunctLinkerStartBefore = IntArray(length + 1)
            indicConjunctSequenceStartBefore.fill(-1)
            indicConjunctLinkerStartBefore!!.fill(-1)

            var sequenceStart = -1
            var linkerSeen = false
            var pos = 0
            while (pos < length) {
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record()
                }
                val decoded = decodeForward(pos)
                val cp: Int = InputScanner.codePoint(decoded)
                val next: Int = InputScanner.position(decoded)
                if (isIndicConjunctConsonant(cp)) {
                    sequenceStart = pos
                    linkerSeen = false
                } else if (isIndicConjunctLinker(cp)) {
                    if (sequenceStart >= 0) {
                        linkerSeen = true
                    }
                } else if (!isIndicConjunctExtend(cp)) {
                    sequenceStart = -1
                    linkerSeen = false
                }
                val activeSequenceStart = sequenceStart
                val linkerStart = if (linkerSeen) sequenceStart else -1
                for (i in pos + 1..next) {
                    indicConjunctSequenceStartBefore[i] = activeSequenceStart
                    indicConjunctLinkerStartBefore!![i] = linkerStart
                }
                pos = next
            }
        }

        private fun ensureExtendedPictographicData() {
            if (extendedPictographicStartBefore != null) {
                return
            }
            extendedPictographicStartBefore = IntArray(length + 1)
            extendedPictographicStartBefore!!.fill(-1)

            var visiblePictographicStart = -1
            var pos = 0
            while (pos < length) {
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record()
                }
                val decoded = decodeForward(pos)
                val cp: Int = InputScanner.codePoint(decoded)
                val next: Int = InputScanner.position(decoded)
                if (containsCodePoint(EXTENDED_PICTOGRAPHIC!!, cp)) {
                    visiblePictographicStart = pos
                } else if (!containsCodePoint(GCB_EXTEND, cp)) {
                    // GB11 requires Extended_Pictographic Extend* before the ZWJ. GB9/GB9a
                    // keep ZWJ and SpacingMark in the cluster, but they interrupt this prefix.
                    visiblePictographicStart = -1
                }
                for (i in pos + 1..next) {
                    extendedPictographicStartBefore!![i] = visiblePictographicStart
                }
                pos = next
            }
        }

        companion object {
            private val EMPTY = Context(null)

            fun createRegion(text: InputScanner, start: Int, end: Int): Context {
                require(start >= 0 && end >= start && end <= text.length)
                if (text is StringInputScanner) {
                    return StringRegionContext(text, start, end)
                }
                return RegionContext(text.slice(start, end), start)
            }

            fun create(text: CharSequence, includeGraphemeClusterBoundary: Boolean): Context {
                if (!includeGraphemeClusterBoundary) {
                    return EMPTY
                }
                return Context(StringInputScanner(text.toString()))
            }

            fun create(text: InputScanner?, includeGraphemeClusterBoundary: Boolean): Context {
                if (!includeGraphemeClusterBoundary) {
                    return EMPTY
                }
                return Context(text)
            }
        }
    }

    /** A region context uses local coordinates while execution keeps original-input positions.  */
    private open class RegionContext(text: InputScanner?, val offset: Int) : Context(text)

    /** Immutable String region view; segmentation never copies or reads outside the region.  */
    private class StringRegionContext(text: StringInputScanner, start: Int, end: Int) : RegionContext(text, start),
        CharSequence {
        private val input: String
        private val regionLength: Int

        init {
            input = text.text()
            regionLength = end - start
        }

        override val length: Int
            get() {
                return regionLength
            }

        override fun get(index: Int): Char {
            require(index >= 0 && index < regionLength)
            return input[offset + index]
        }

        override fun subSequence(start: Int, end: Int): CharSequence {
            require(start >= 0 && end >= start && end <= regionLength)
            return input.subSequence(offset + start, offset + end)
        }

        override fun toString(): String {
            return input.substring(offset, offset + regionLength)
        }

        override fun decodeForward(pos: Int): Long {
            val cp: Int = graphemeCodePointAt(this, pos, regionLength)
            return InputScanner.decoded(cp, pos + CodePoints.charCount(cp))
        }
    }

    init {
        throw AssertionError("non-instantiable")
    }

    companion object {
        private val EXTENDED_PICTOGRAPHIC: Array<IntArray>? =
            UnicodeProperties.lookupBinaryProperty("Extended_Pictographic")

        // UAX #29 properties generated directly from the pinned Unicode Character Database.
        private val GCB_CONTROL: Array<IntArray> = graphemeTable("Control")
        private val GCB_EXTEND: Array<IntArray> = graphemeTable("Extend")
        private val GCB_PREPEND: Array<IntArray> = graphemeTable("Prepend")
        private val GCB_SPACING_MARK: Array<IntArray> = graphemeTable("SpacingMark")
        private val GCB_L: Array<IntArray> = graphemeTable("L")
        private val GCB_V: Array<IntArray> = graphemeTable("V")
        private val GCB_T: Array<IntArray> = graphemeTable("T")
        private val GCB_LV: Array<IntArray> = graphemeTable("LV")
        private val GCB_LVT: Array<IntArray> = graphemeTable("LVT")
        private val INCB_LINKER: Array<IntArray> = graphemeTable("InCB_Linker")
        private val INCB_CONSONANT: Array<IntArray> = graphemeTable("InCB_Consonant")
        private val INCB_EXTEND: Array<IntArray> = graphemeTable("InCB_Extend")

        private const val VISIT_KEY_VARIANT_BITS = 5
        private const val LOW_SURROGATE_PAIR_VISIBLE = 1
        private val EXTENDED_PICTOGRAPHIC_VISIBLE = 1 shl 1
        private val REGIONAL_INDICATOR_ODD = 1 shl 2
        private val INDIC_CONJUNCT_LINKER_VISIBLE = 1 shl 3
        private val INDIC_CONJUNCT_SEQUENCE_VISIBLE = 1 shl 4

        private fun codePointAt(text: CharSequence, pos: Int): Int {
            return CodePoints.codePointAt(text, pos)
        }

        private fun codePointBefore(text: CharSequence, pos: Int): Int {
            return CodePoints.codePointBefore(text, pos)
        }

        private fun codePointAt(text: InputScanner, pos: Int): Int {
            return text.codePointAt(pos)
        }

        private fun codePointBefore(text: InputScanner, pos: Int): Int {
            return text.codePointBefore(pos)
        }

        fun visitKeyVariantBits(): Int {
            return VISIT_KEY_VARIANT_BITS
        }

        fun visitVariant(
            text: CharSequence,
            pos: Int,
            graphemeStart: Int,
            consumeRegionStart: Int,
            endPos: Int,
            graphemeContext: Context
        ): Int {
            val start = max(consumeRegionStart, graphemeStart)
            if (pos < 0 || pos >= endPos || pos >= text.length) {
                return 0
            }

            var variant = 0
            val ch: Char = text[pos]
            if (ch.isLowSurrogate()
                && hasHighSurrogateBeforeLowSurrogateInRegion(text, pos, start)
            ) {
                variant = variant or LOW_SURROGATE_PAIR_VISIBLE
            }
            if (graphemeContext.hasExtendedPictographicBefore(pos, start)) {
                variant = variant or EXTENDED_PICTOGRAPHIC_VISIBLE
            }
            val cp: Int = graphemeCodePointAt(text, pos, endPos)
            val scalarEnd: Int = nextRegionLocalScalarEnd(text, pos, endPos)
            val nextCp: Int = graphemeCodePointAt(text, scalarEnd, endPos)
            if (graphemeContext.hasIndicConjunctSequenceBefore(pos, start)) {
                variant = variant or INDIC_CONJUNCT_SEQUENCE_VISIBLE
            }
            if ((isIndicConjunctConsonant(cp) && graphemeContext.hasIndicConjunctLinkerBefore(pos, start))
                || (isIndicConjunctConsonant(nextCp)
                        && graphemeContext.hasIndicConjunctLinkerBefore(scalarEnd, start))
            ) {
                variant = variant or INDIC_CONJUNCT_LINKER_VISIBLE
            }
            if (isRegionalIndicator(cp)
                && !graphemeContext.hasEvenRegionalIndicatorsBefore(scalarEnd, start)
            ) {
                variant = variant or REGIONAL_INDICATOR_ODD
            }
            return variant
        }

        fun visitVariant(
            text: InputScanner,
            pos: Int,
            graphemeStart: Int,
            consumeRegionStart: Int,
            endPos: Int,
            graphemeContext: Context
        ): Int {
            var text = text
            var pos = pos
            var graphemeStart = graphemeStart
            var consumeRegionStart = consumeRegionStart
            var endPos = endPos
            if (graphemeContext is StringRegionContext) {
                return visitVariant(
                    graphemeContext,
                    pos - graphemeContext.offset,
                    graphemeStart - graphemeContext.offset,
                    consumeRegionStart - graphemeContext.offset,
                    endPos - graphemeContext.offset,
                    graphemeContext
                )
            }
            if (graphemeContext is RegionContext) {
                text = graphemeContext.text!!
                pos -= graphemeContext.offset
                graphemeStart -= graphemeContext.offset
                consumeRegionStart -= graphemeContext.offset
                endPos -= graphemeContext.offset
            }
            if (text is StringInputScanner) {
                return visitVariant(
                    text.text(), pos, graphemeStart, consumeRegionStart, endPos, graphemeContext
                )
            }
            val start = max(consumeRegionStart, graphemeStart)
            if (pos < 0 || pos >= endPos || pos >= text.length) {
                return 0
            }
            var variant = 0
            if (graphemeContext.hasExtendedPictographicBefore(pos, start)) {
                variant = variant or EXTENDED_PICTOGRAPHIC_VISIBLE
            }
            val codePoint: Int = inputCodePointAt(text, pos, endPos)
            val scalarEnd: Int = inputNextPos(text, pos, endPos)
            val nextCodePoint: Int = inputCodePointAt(text, scalarEnd, endPos)
            if (graphemeContext.hasIndicConjunctSequenceBefore(pos, start)) {
                variant = variant or INDIC_CONJUNCT_SEQUENCE_VISIBLE
            }
            if ((isIndicConjunctConsonant(codePoint)
                        && graphemeContext.hasIndicConjunctLinkerBefore(pos, start))
                || (isIndicConjunctConsonant(nextCodePoint)
                        && graphemeContext.hasIndicConjunctLinkerBefore(scalarEnd, start))
            ) {
                variant = variant or INDIC_CONJUNCT_LINKER_VISIBLE
            }
            if (isRegionalIndicator(codePoint)
                && !graphemeContext.hasEvenRegionalIndicatorsBefore(scalarEnd, start)
            ) {
                variant = variant or REGIONAL_INDICATOR_ODD
            }
            return variant
        }

        private fun inputCodePointAt(text: InputScanner, pos: Int, endPos: Int): Int {
            return when (text) {
                is StringInputScanner -> graphemeCodePointAt(text.text(), pos, endPos)
                else -> {
                    if (pos < 0 || pos >= endPos || pos >= text.length) {
                        -1
                    } else {
                        val decoded = text.decodeForward(pos)
                        if (InputScanner.position(decoded) <= endPos) {
                            InputScanner.codePoint(decoded)
                        } else {
                            -1
                        }
                    }
                }
            }
        }

        private fun inputNextPos(text: InputScanner, pos: Int, endPos: Int): Int {
            return when (text) {
                is StringInputScanner -> graphemeNextPos(text.text(), pos, endPos)
                else -> {
                    if (pos < 0 || pos >= endPos || pos >= text.length) {
                        endPos + 1
                    } else {
                        val next = InputScanner.position(text.decodeForward(pos))
                        if (next <= endPos) next else endPos + 1
                    }
                }
            }
        }

        fun graphemeNextPos(text: InputScanner, pos: Int, endPos: Int): Int {
            return inputNextPos(text, pos, endPos)
        }

        fun graphemeCodePointAt(text: CharSequence, pos: Int, endPos: Int): Int {
            if (pos < 0 || pos >= endPos || pos >= text.length) {
                return -1
            }
            val ch: Char = text[pos]
            if (ch.isHighSurrogate()
                && pos + 1 < endPos && pos + 1 < text.length && text[pos + 1].isLowSurrogate()
            ) {
                return CodePoints.toCodePoint(ch, text[pos + 1])
            }
            return ch.code
        }

        fun graphemeNextPos(text: CharSequence, pos: Int, endPos: Int): Int {
            if (pos < 0 || pos >= endPos || pos >= text.length) {
                return endPos + 1
            }
            return nextRegionLocalScalarEnd(text, pos, endPos)
        }

        fun nextRegionLocalScalarEnd(text: CharSequence, pos: Int, endPos: Int): Int {
            if (pos < 0 || pos >= endPos || pos >= text.length) {
                return endPos + 1
            }
            val ch: Char = text[pos]
            if (ch.isHighSurrogate()
                && pos + 1 < endPos && pos + 1 < text.length && text[pos + 1].isLowSurrogate()
            ) {
                return pos + 2
            }
            return pos + 1
        }

        fun boundaryFlags(
            text: InputScanner,
            pos: Int,
            graphemeContext: Context?,
            matchStart: Int,
            regionStart: Int,
            consumedInput: Boolean,
            boundaryEndPos: Int
        ): Int {
            var text = text
            var pos = pos
            var matchStart = matchStart
            var regionStart = regionStart
            var boundaryEndPos = boundaryEndPos
            if (graphemeContext is StringRegionContext) {
                return boundaryFlags(
                    graphemeContext,
                    pos - graphemeContext.offset,
                    graphemeContext,
                    matchStart - graphemeContext.offset,
                    regionStart - graphemeContext.offset,
                    consumedInput,
                    boundaryEndPos - graphemeContext.offset
                )
            }
            if (graphemeContext is RegionContext) {
                text = graphemeContext.text!!
                pos -= graphemeContext.offset
                matchStart -= graphemeContext.offset
                regionStart -= graphemeContext.offset
                boundaryEndPos -= graphemeContext.offset
            }
            if (text !is StringInputScanner) {
                if (isGraphemeBoundaryContextEdge(pos, regionStart, boundaryEndPos)
                    || isGraphemeClusterBoundaryLocal(text, pos, regionStart, graphemeContext)
                ) {
                    return EmptyOp.GRAPHEME_CLUSTER_BOUNDARY or EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY
                }
                return 0
            }
            return boundaryFlags(
                text.text(),
                pos,
                graphemeContext,
                matchStart,
                regionStart,
                consumedInput,
                boundaryEndPos
            )
        }

        fun boundaryFlags(
            text: CharSequence,
            pos: Int,
            graphemeContext: Context?,
            matchStart: Int,
            regionStart: Int,
            consumedInput: Boolean,
            boundaryEndPos: Int
        ): Int {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            var flags = 0
            if (isGraphemeBoundaryContextEdge(pos, regionStart, boundaryEndPos)) {
                flags = flags or (EmptyOp.GRAPHEME_CLUSTER_BOUNDARY or EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY)
            } else if (isRegionStartSplitSurrogateBoundary(text, pos, regionStart)
                || isRegionEndSplitSurrogateBoundary(text, pos, boundaryEndPos)
            ) {
                flags = flags or (EmptyOp.GRAPHEME_CLUSTER_BOUNDARY or EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY)
            } else if (isAfterRegionStartSplitLowSurrogateBoundary(text, pos, regionStart)) {
                flags = flags or EmptyOp.GRAPHEME_CLUSTER_BOUNDARY
                if (!suppressesConsumedSplitLowSurrogateExplicitBoundary(
                        text, pos, boundaryEndPos, consumedInput
                    )
                ) {
                    flags = flags or EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY
                }
            } else if (consumedInput
                && isStandaloneZwjBeforePictographicBoundary(text, pos, regionStart, graphemeContext)
            ) {
                flags = flags or EmptyOp.GRAPHEME_CLUSTER_BOUNDARY
                if (!isAfterPairedExtendedPictographicZwj(text, pos, regionStart, graphemeContext)) {
                    flags = flags or EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY
                }
            } else if (isGraphemeClusterBoundary(text, pos, regionStart, graphemeContext)) {
                flags = flags or EmptyOp.GRAPHEME_CLUSTER_BOUNDARY
                if (!isAfterPairedExtendedPictographicZwj(text, pos, regionStart, graphemeContext)
                    || !isStandaloneZwjBeforePictographicBoundary(text, pos, regionStart, graphemeContext)
                ) {
                    flags = flags or EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY
                }
            } else if (isLowHighSurrogateBoundary(text, pos)
                && matchStart > 0 && pos > matchStart && !hasHighSurrogateBeforeLowSurrogateInRegion(
                    text,
                    pos - 1,
                    regionStart
                )
            ) {
                flags = flags or (EmptyOp.GRAPHEME_CLUSTER_BOUNDARY or EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY)
            } else if (startsAtLowSurrogate(text, matchStart)
                && !hasHighSurrogateBeforeLowSurrogateInRegion(text, matchStart, regionStart) && pos == matchStart + 1
            ) {
                flags = flags or EmptyOp.GRAPHEME_CLUSTER_BOUNDARY
                if (!hasGraphemeExtendAt(text, pos)
                    && !suppressesRegionStartSplitExplicitBoundary(
                        text, regionStart, matchStart, consumedInput
                    )
                ) {
                    flags = flags or EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY
                }
            } else if (startsAtStandaloneZwj(text, matchStart, regionStart)
                && pos == matchStart + 1 && !hasGraphemeExtendAt(text, pos)
            ) {
                flags = flags or EmptyOp.GRAPHEME_CLUSTER_BOUNDARY
                if (!consumedInput
                    && !suppressesRegionStartSplitExplicitBoundary(
                        text, regionStart, matchStart, consumedInput
                    )
                ) {
                    flags = flags or EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY
                }
            } else if (!consumedInput && (isLowSurrogateBeforeZwjBoundary(text, pos, matchStart, regionStart)
                        || isStandaloneZwjAfterLowSurrogateBoundary(text, pos, matchStart, regionStart))
                && !isAfterRegionStartSplitLowSurrogate(text, pos, regionStart) && !hasGraphemeExtendAt(text, pos)
            ) {
                flags = flags or EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY
            } else if (!consumedInput
                && isStandaloneZwjBeforePictographicBoundary(text, pos, regionStart, graphemeContext)
            ) {
                flags = flags or EmptyOp.EXPLICIT_GRAPHEME_CLUSTER_BOUNDARY
            }
            return flags
        }

        fun isGraphemeClusterBoundary(text: CharSequence, pos: Int): Boolean {
            return isGraphemeClusterBoundary(text, pos, 0, Context.create(text, true))
        }

        fun isGraphemeClusterBoundary(
            text: InputScanner, pos: Int, regionStart: Int, graphemeContext: Context?
        ): Boolean {
            var text = text
            var pos = pos
            var regionStart = regionStart
            if (graphemeContext is StringRegionContext) {
                return isGraphemeClusterBoundary(
                    graphemeContext, pos - graphemeContext.offset, regionStart - graphemeContext.offset, graphemeContext
                )
            }
            if (graphemeContext is RegionContext) {
                text = graphemeContext.text!!
                pos -= graphemeContext.offset
                regionStart -= graphemeContext.offset
            }
            return isGraphemeClusterBoundaryLocal(text, pos, regionStart, graphemeContext)
        }

        private fun isGraphemeClusterBoundaryLocal(
            text: InputScanner, pos: Int, regionStart: Int, graphemeContext: Context?
        ): Boolean {
            if (text is StringInputScanner) {
                return isGraphemeClusterBoundary(text.text(), pos, regionStart, graphemeContext)
            }
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            if (pos < 0 || pos > text.length || !text.isCodePointBoundary(pos)) {
                return false
            }
            if (pos == 0 || pos == text.length) {
                return true
            }
            val previous = codePointBefore(text, pos)
            val next = codePointAt(text, pos)
            if (previous == '\r'.code && next == '\n'.code) {
                return false
            }
            if (isGraphemeControl(previous) || isGraphemeControl(next)) {
                return true
            }
            if (isGraphemeExtend(next) || isGraphemePrepend(previous)) {
                return false
            }
            if (isHangulGraphemeContinuation(previous, next)) {
                return false
            }
            val context =
                (if (graphemeContext != null) graphemeContext else Context.create(
                    text,
                    true
                ))!!
            if (isIndicConjunctConsonant(next) && context.hasIndicConjunctLinkerBefore(pos, regionStart)) {
                return false
            }
            val previousStart: Int = InputScanner.position(text.decodeBackward(pos))
            if (previous == 0x200D && containsCodePoint(EXTENDED_PICTOGRAPHIC!!, next)
                && context.hasExtendedPictographicBefore(previousStart, regionStart)
            ) {
                return false
            }
            if (isRegionalIndicator(previous) && isRegionalIndicator(next)) {
                return context.hasEvenRegionalIndicatorsBefore(pos, regionStart)
            }
            return true
        }

        fun isGraphemeClusterBoundary(text: CharSequence, pos: Int, graphemeContext: Context?): Boolean {
            return isGraphemeClusterBoundary(text, pos, 0, graphemeContext)
        }

        fun isGraphemeClusterBoundary(
            text: CharSequence, pos: Int, regionStart: Int, graphemeContext: Context?
        ): Boolean {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            if (pos < 0 || pos > text.length) {
                return false
            }
            if (pos == 0 || pos == text.length) {
                return true
            }
            val prevChar: Char = text[pos - 1]
            val nextChar: Char = text[pos]
            if (prevChar.isHighSurrogate() && nextChar.isLowSurrogate()) {
                return false
            }
            if (prevChar == '\r' && nextChar == '\n') {
                return false
            }
            if (prevChar.isHighSurrogate() && !nextChar.isLowSurrogate()) {
                return true
            }
            if (prevChar.isLowSurrogate()
                && !hasHighSurrogateBeforeLowSurrogateInRegion(text, pos - 1, regionStart)
            ) {
                return true
            }
            val prev = codePointBefore(text, pos)
            val next = codePointAt(text, pos)
            if (isGraphemeControl(prev) || isGraphemeControl(next)) {
                return true
            }
            if (isGraphemePrepend(prev) && isUnpairedSurrogateAt(text, pos)) {
                return true
            }
            if (isGraphemeExtend(next) || isGraphemePrepend(prev)) {
                return false
            }
            if (isHangulGraphemeContinuation(prev, next)) {
                return false
            }
            val context =
                (if (graphemeContext != null) graphemeContext else Context.create(
                    text,
                    true
                ))!!
            if (isIndicConjunctConsonant(next) && context.hasIndicConjunctLinkerBefore(pos, regionStart)) {
                return false
            }
            if (prev == 0x200D && containsCodePoint(EXTENDED_PICTOGRAPHIC!!, next)
                && context.hasExtendedPictographicBefore(pos - 1, regionStart)
            ) {
                return false
            }
            if (isRegionalIndicator(prev) && isRegionalIndicator(next)) {
                return context.hasEvenRegionalIndicatorsBefore(pos, regionStart)
            }
            return true
        }

        fun hasGraphemeExtendAt(text: CharSequence, pos: Int): Boolean {
            return pos >= 0 && pos < text.length && isGraphemeExtend(codePointAt(text, pos))
        }

        fun isExtendedPictographicAt(text: CharSequence, pos: Int): Boolean {
            return pos >= 0 && pos < text.length && containsCodePoint(
                EXTENDED_PICTOGRAPHIC!!,
                codePointAt(text, pos)
            )
        }

        private fun isGraphemeBoundaryContextEdge(
            pos: Int, boundaryStart: Int, boundaryEnd: Int
        ): Boolean {
            return pos == boundaryStart || pos == boundaryEnd
        }

        private fun isLowHighSurrogateBoundary(text: CharSequence, pos: Int): Boolean {
            return pos > 0 && pos < text.length && text[pos - 1].isLowSurrogate()
                    && text[pos].isHighSurrogate()
        }

        private fun isRegionStartSplitSurrogateBoundary(
            text: CharSequence, pos: Int, regionStart: Int
        ): Boolean {
            return pos == regionStart && regionStart > 0 && regionStart < text.length && text[regionStart - 1].isHighSurrogate()
                    && text[regionStart].isLowSurrogate()
        }

        private fun isAfterRegionStartSplitLowSurrogateBoundary(
            text: CharSequence, pos: Int, regionStart: Int
        ): Boolean {
            return pos == regionStart + 1
                    && isRegionStartSplitSurrogateBoundary(text, regionStart, regionStart)
        }

        private fun isRegionEndSplitSurrogateBoundary(
            text: CharSequence, pos: Int, regionEnd: Int
        ): Boolean {
            return pos == regionEnd && regionEnd > 0 && regionEnd < text.length && text[regionEnd - 1].isHighSurrogate()
                    && text[regionEnd].isLowSurrogate()
        }

        private fun suppressesConsumedSplitLowSurrogateExplicitBoundary(
            text: CharSequence, pos: Int, boundaryEndPos: Int, consumedInput: Boolean
        ): Boolean {
            if (!consumedInput || pos >= boundaryEndPos) {
                return false
            }
            if (hasGraphemeExtendAt(text, pos)) {
                return true
            }
            // Region edges are handled before this interior assertion. Classify the next scalar in
            // the grapheme context, as for Extend above, independently of which atoms may consume it.
            return isRegionalIndicator(codePointAt(text, pos))
        }

        private fun isLowSurrogateBeforeZwjBoundary(
            text: CharSequence, pos: Int, matchStart: Int, regionStart: Int
        ): Boolean {
            return pos == matchStart && pos > 0 && pos < text.length && text[pos - 1].isLowSurrogate()
                    && text[pos].code == 0x200D && !hasHighSurrogateBeforeLowSurrogateInRegion(
                text,
                pos - 1,
                regionStart
            )
        }

        private fun suppressesRegionStartSplitExplicitBoundary(
            text: CharSequence, regionStart: Int, matchStart: Int, consumedInput: Boolean
        ): Boolean {
            return consumedInput
                    && matchStart == regionStart + 1 && isRegionStartSplitSurrogateBoundary(
                text,
                regionStart,
                regionStart
            )
        }

        private fun isStandaloneZwjAfterLowSurrogateBoundary(
            text: CharSequence, pos: Int, matchStart: Int, regionStart: Int
        ): Boolean {
            return pos == matchStart && pos > 1 && pos < text.length && text[pos - 1].code == 0x200D && text[pos - 2].isLowSurrogate()
                    && !hasHighSurrogateBeforeLowSurrogateInRegion(text, pos - 2, regionStart)
        }

        private fun isStandaloneZwjBeforePictographicBoundary(
            text: CharSequence, pos: Int, regionStart: Int, graphemeContext: Context?
        ): Boolean {
            val context =
                (if (graphemeContext != null) graphemeContext else Context.create(
                    text,
                    true
                ))!!
            return pos > 0 && pos < text.length && text[pos - 1].code == 0x200D && containsCodePoint(
                EXTENDED_PICTOGRAPHIC!!,
                codePointAt(text, pos)
            )
                    && !isAfterRegionStartSplitLowSurrogate(
                text,
                pos - 1,
                regionStart
            ) && !context.hasExtendedPictographicBefore(pos - 1, regionStart)
        }

        private fun isAfterPairedExtendedPictographicZwj(
            text: CharSequence, pos: Int, regionStart: Int, graphemeContext: Context?
        ): Boolean {
            val context =
                (if (graphemeContext != null) graphemeContext else Context.create(
                    text,
                    true
                ))!!
            val lowSurrogatePos = pos - 2
            if (regionStart <= lowSurrogatePos && regionStart > 0) {
                return false
            }
            return pos > 2 && text[pos - 1].code == 0x200D && text[lowSurrogatePos].isLowSurrogate()
                    && text[pos - 3].isHighSurrogate()
                    && context.hasExtendedPictographicBefore(pos - 1, regionStart)
        }

        private fun isAfterRegionStartSplitLowSurrogate(
            text: CharSequence, pos: Int, regionStart: Int
        ): Boolean {
            return pos == regionStart + 1
                    && isRegionStartSplitSurrogateBoundary(text, regionStart, regionStart)
        }

        private fun hasHighSurrogateBeforeLowSurrogateInRegion(
            text: CharSequence, lowSurrogatePos: Int, regionStart: Int
        ): Boolean {
            return lowSurrogatePos > regionStart && lowSurrogatePos > 0 && text[lowSurrogatePos - 1].isHighSurrogate()
        }

        private fun startsAtLowSurrogate(text: CharSequence, matchStart: Int): Boolean {
            return matchStart >= 0 && matchStart < text.length && text[matchStart].isLowSurrogate()
        }

        private fun startsAtStandaloneZwj(text: CharSequence, matchStart: Int, regionStart: Int): Boolean {
            return matchStart >= 0 && matchStart < text.length && text[matchStart].code == 0x200D && (matchStart == 0 || matchStart <= regionStart || !text[matchStart - 1].isLowSurrogate() || !hasHighSurrogateBeforeLowSurrogateInRegion(text, matchStart - 1, regionStart))
        }

        /** Returns whether `c` attaches to the preceding code point (GB9 and GB9a).  */
        private fun isGraphemeExtend(c: Int): Boolean {
            return c == 0x200D || containsCodePoint(GCB_EXTEND, c)
                    || containsCodePoint(GCB_SPACING_MARK, c)
        }

        private fun isUnpairedSurrogateAt(text: CharSequence, pos: Int): Boolean {
            val c: Char = text[pos]
            if (c.isLowSurrogate()) {
                return true
            }
            return c.isHighSurrogate()
                    && (pos + 1 >= text.length || !text[pos + 1].isLowSurrogate())
        }

        private fun isGraphemeControl(c: Int): Boolean {
            if (c < 0x7F) {
                return c < 0x20
            }
            return containsCodePoint(GCB_CONTROL, c)
        }

        private fun isGraphemePrepend(c: Int): Boolean {
            return containsCodePoint(GCB_PREPEND, c)
        }

        private fun isHangulGraphemeContinuation(prev: Int, next: Int): Boolean {
            return (isHangulL(prev)
                    && (isHangulL(next) || isHangulV(next) || isHangulLv(next) || isHangulLvt(next)))
                    || ((isHangulV(prev) || isHangulLv(prev)) && (isHangulV(next) || isHangulT(next)))
                    || ((isHangulT(prev) || isHangulLvt(prev)) && isHangulT(next))
        }

        private fun isHangulL(c: Int): Boolean {
            return containsCodePoint(GCB_L, c)
        }

        private fun isHangulV(c: Int): Boolean {
            return containsCodePoint(GCB_V, c)
        }

        private fun isHangulT(c: Int): Boolean {
            return containsCodePoint(GCB_T, c)
        }

        private fun isHangulLv(c: Int): Boolean {
            return containsCodePoint(GCB_LV, c)
        }

        private fun isHangulLvt(c: Int): Boolean {
            return containsCodePoint(GCB_LVT, c)
        }

        private fun isRegionalIndicator(c: Int): Boolean {
            return 0x1F1E6 <= c && c <= 0x1F1FF
        }

        private fun isIndicConjunctConsonant(c: Int): Boolean {
            return containsCodePoint(INCB_CONSONANT, c)
        }

        private fun isIndicConjunctLinker(c: Int): Boolean {
            return containsCodePoint(INCB_LINKER, c)
        }

        private fun isIndicConjunctExtend(c: Int): Boolean {
            return containsCodePoint(INCB_EXTEND, c)
        }

        private fun graphemeTable(name: String?): Array<IntArray> {
            return requireNotNull(UnicodeGeneratedTables.GRAPHEME_PROPERTIES.get(name)) { name!! }
        }

        private fun containsCodePoint(ranges: Array<IntArray>, c: Int): Boolean {
            var hi: Int = ranges.size - 1
            if (hi < 0 || c < ranges[0][0] || c > ranges[hi][1]) {
                return false
            }
            var lo = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val range = ranges[mid]
                if (c < range[0]) {
                    hi = mid - 1
                } else if (c > range[1]) {
                    lo = mid + 1
                } else {
                    return true
                }
            }
            return false
        }
    }
}
