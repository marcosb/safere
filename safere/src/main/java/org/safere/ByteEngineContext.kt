// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere


/** Immutable execution bounds shared by raw byte matching engines.  */
internal class ByteEngineContext(
    text: ByteArray?,
    private val searchStart: Int,
    private val searchLimit: Int,
    private val endPos: Int,
    private val consumeRegionStart: Int,
    private val boundaryRegionStart: Int,
    private val boundaryEndPos: Int,
    private val anchorEndPos: Int,
    private val emptyAnchorStartPos: Int,
    private val emptyAnchorEndPos: Int
) {
    private val text: ByteArray

    init {
        this.text = requireNotNull(text) { "text" }
    }

    fun text(): ByteArray {
        return text
    }

    fun searchStart(): Int {
        return searchStart
    }

    fun searchLimit(): Int {
        return searchLimit
    }

    fun endPos(): Int {
        return endPos
    }

    fun consumeRegionStart(): Int {
        return consumeRegionStart
    }

    fun boundaryRegionStart(): Int {
        return boundaryRegionStart
    }

    fun boundaryEndPos(): Int {
        return boundaryEndPos
    }

    fun anchorEndPos(): Int {
        return anchorEndPos
    }

    fun emptyAnchorStartPos(): Int {
        return emptyAnchorStartPos
    }

    fun emptyAnchorEndPos(): Int {
        return emptyAnchorEndPos
    }

    companion object {
        fun create(
            prog: Prog?,
            text: ByteArray?,
            searchStart: Int,
            searchLimit: Int,
            endPos: Int,
            consumeRegionStart: Int,
            boundaryRegionStart: Int,
            boundaryEndPos: Int,
            anchorEndPos: Int,
            emptyAnchorStartPos: Int,
            emptyAnchorEndPos: Int
        ): ByteEngineContext {
            return ByteEngineContext(
                text,
                searchStart,
                searchLimit,
                endPos,
                consumeRegionStart,
                boundaryRegionStart,
                boundaryEndPos,
                anchorEndPos,
                emptyAnchorStartPos,
                emptyAnchorEndPos
            )
        }
    }
}
