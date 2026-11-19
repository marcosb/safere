// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*
import kotlin.jvm.JvmRecord

/** Immutable execution bounds shared by regex engines.  */
@JvmRecord
internal data class EngineContext(
    val text: InputScanner?,
    val searchStart: Int,
    val searchLimit: Int,
    val endPos: Int,
    val consumeRegionStart: Int,
    val boundaryRegionStart: Int,
    val boundaryEndPos: Int,
    val anchorEndPos: Int,
    val emptyAnchorStartPos: Int,
    val emptyAnchorEndPos: Int,
    val graphemeContext: GraphemeSupport.Context
) {
    init {
        requireNotNull(text) { "text" }
        requireNotNull(graphemeContext) { "graphemeContext" }
    }

    companion object {
        fun create(
            prog: Prog,
            text: InputScanner?,
            searchStart: Int,
            searchLimit: Int,
            endPos: Int,
            consumeRegionStart: Int,
            boundaryRegionStart: Int,
            boundaryEndPos: Int,
            anchorEndPos: Int,
            emptyAnchorStartPos: Int,
            emptyAnchorEndPos: Int,
            graphemeContext: GraphemeSupport.Context?
        ): EngineContext {
            val context =
                graphemeContext
                    ?: GraphemeSupport.Context.create(text, prog.hasGraphemeSemantics())
            return EngineContext(
                text,
                searchStart,
                searchLimit,
                endPos,
                consumeRegionStart,
                boundaryRegionStart,
                boundaryEndPos,
                anchorEndPos,
                emptyAnchorStartPos,
                emptyAnchorEndPos,
                context
            )
        }
    }
}
