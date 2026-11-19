// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.re2ffm

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * A matcher for a [RE2FfmPattern] against an input string. This benchmark-oriented wrapper
 * exposes selected matcher operations while retaining RE2 match semantics.
 * 
 * 
 * Match offsets from RE2 are in UTF-8 byte positions. This class converts them to Java char
 * (UTF-16) positions for the public API.
 */
class RE2FfmMatcher {
    private val pattern: RE2FfmPattern
    private var inputString: String? = null
    private var inputUtf8: ByteArray

    private var charToByteMap: IntArray?

    // Match state: byte offsets from RE2. Length = 2 * (numGroups + 1).
    // [start0, end0, start1, end1, ...]
    private var matchByteOffsets: IntArray
    private var matched = false

    // Current search position in UTF-8 bytes.
    private var searchBytePos = 0
    private var regionStart = 0
    private var regionEnd = 0
    private var regionStartByte = 0
    private var regionEndByte = 0

    internal constructor(pattern: RE2FfmPattern, input: String) {
        this.pattern = pattern
        reset(input)
    }

    internal constructor(pattern: RE2FfmPattern, input: String, inputUtf8: ByteArray) {
        this.pattern = pattern
        this.inputString = input
        this.inputUtf8 = inputUtf8
        this.matchByteOffsets = IntArray(2 * (pattern.numGroups() + 1))
        buildCharToByteMap(inputString!!, inputUtf8)
        reset()
    }

    /**
     * Resets this matcher with a new input string.
     * 
     * @param input the new input text
     * @return this matcher
     */
    fun reset(input: String): RE2FfmMatcher {
        this.inputString = input
        this.inputUtf8 = input.getBytes(StandardCharsets.UTF_8)
        buildCharToByteMap(inputString!!, inputUtf8)
        this.matchByteOffsets = IntArray(2 * (pattern.numGroups() + 1))
        return reset()
    }

    /** Resets match state and restores the region to the entire current input.  */
    fun reset(): RE2FfmMatcher {
        this.matched = false
        this.searchBytePos = 0
        this.regionStart = 0
        this.regionEnd = inputString.length()
        this.regionStartByte = 0
        this.regionEndByte = inputUtf8.length
        return this
    }

    /**
     * Sets an opaque, anchoring region using UTF-16 indices into the original input.
     * 
     * 
     * The start is inclusive and the end is exclusive. Captures retain absolute UTF-16 offsets.
     * Region boundaries act as input boundaries for RE2 anchors and word boundaries. This operation
     * discards the previous match and restarts searching at the region start. Bounds that split a
     * surrogate pair are unsupported; RE2 consumes UTF-8 code points.
     * 
     * @param start the inclusive UTF-16 start
     * @param end the exclusive UTF-16 end
     * @return this matcher
     * @throws IndexOutOfBoundsException if the bounds are outside the input or start exceeds end
     * @throws IllegalArgumentException if either bound splits a surrogate pair
     */
    fun region(start: Int, end: Int): RE2FfmMatcher {
        if (start < 0 || end < start || end > inputString.length()) {
            throw IndexOutOfBoundsException("Invalid region: " + start + ", " + end)
        }
        checkCodePointBoundary(start)
        checkCodePointBoundary(end)
        regionStart = start
        regionEnd = end
        regionStartByte = charOffsetToByteOffset(start)
        regionEndByte = charOffsetToByteOffset(end)
        searchBytePos = regionStartByte
        matched = false
        return this
    }

    /** Returns the inclusive UTF-16 region start.  */
    fun regionStart(): Int {
        return regionStart
    }

    /** Returns the exclusive UTF-16 region end.  */
    fun regionEnd(): Int {
        return regionEnd
    }

    /**
     * Attempts to match the entire region against the pattern.
     * 
     * @return true if the entire region matches
     */
    fun matches(): Boolean {
        return match(regionStartByte, ANCHOR_BOTH)
    }

    /**
     * Matches a prefix of the region using RE2 start anchoring, without scanning later positions.
     * 
     * @return true if a prefix matches
     */
    fun lookingAt(): Boolean {
        return match(regionStartByte, ANCHOR_START)
    }

    /**
     * Finds the next match within the region.
     * 
     * 
     * Successful match operations advance the next search to the match end. Empty matches advance
     * by one Unicode code point, retaining RE2's UTF-8 boundary semantics rather than Java's UTF-16
     * empty-match progression. Repeated `find()` calls after a failed search remain exhausted.
     * Failed [.lookingAt] and [.matches] calls discard the current match without
     * changing the next search position. Both operations always restart at the region beginning.
     * 
     * @return true if a match was found
     */
    fun find(): Boolean {
        return match(searchBytePos, UNANCHORED)
    }

    private fun match(startByte: Int, anchor: Int): Boolean {
        if (startByte > regionEndByte) {
            matched = false
            return false
        }
        Arena.ofConfined().use { arena ->
            val textSeg: MemorySegment? = arena.allocateFrom(ValueLayout.JAVA_BYTE, inputUtf8)
            val matchesSeg = arena.allocate(ValueLayout.JAVA_INT, matchByteOffsets.length)
            matched =
                Re2Shim.match(
                    pattern.nativeHandle(),
                    textSeg,
                    inputUtf8.length,
                    regionStartByte,
                    regionEndByte,
                    startByte,
                    anchor,
                    matchesSeg,
                    pattern.numGroups()
                )
            if (matched) {
                MemorySegment.copy(
                    matchesSeg, ValueLayout.JAVA_INT, 0, matchByteOffsets, 0, matchByteOffsets.length
                )
                searchBytePos =
                    if (matchByteOffsets[0] == matchByteOffsets[1])
                        nextCodePointByteOffset(matchByteOffsets[1])
                    else
                        matchByteOffsets[1]
            } else if (anchor == UNANCHORED) {
                searchBytePos = regionEndByte + 1
            }
        }
        return matched
    }

    /**
     * Resets the matcher to the whole input and searches from the given UTF-16 position.
     * 
     * @param start the char position to start searching from
     * @return true if a match was found
     * @throws IndexOutOfBoundsException if start is outside the input
     * @throws IllegalArgumentException if start splits a surrogate pair
     */
    fun find(start: Int): Boolean {
        if (start < 0 || start > inputString.length()) {
            throw IndexOutOfBoundsException("Invalid start: " + start)
        }
        checkCodePointBoundary(start)
        reset()
        searchBytePos = charOffsetToByteOffset(start)
        return find()
    }

    /**
     * Returns the matched text for the given group.
     * 
     * @param group the group index (0 for the whole match)
     * @return the matched text, or null if the group didn't participate in the match
     */
    /**
     * Returns the entire matched text (group 0).
     * 
     * @return the matched text
     */
    @JvmOverloads
    fun group(group: Int = 0): String? {
        checkMatch()
        val startByte = matchByteOffsets[2 * group]
        val endByte = matchByteOffsets[2 * group + 1]
        if (startByte < 0) {
            return null
        }
        return String(inputUtf8, startByte, endByte - startByte, StandardCharsets.UTF_8)
    }

    /**
     * Returns the start char position of the given group.
     * 
     * @param group the group index
     * @return the start position, or -1 if the group didn't participate
     */
    /** Returns the start char position of the whole match.  */
    @JvmOverloads
    fun start(group: Int = 0): Int {
        checkMatch()
        val startByte = matchByteOffsets[2 * group]
        if (startByte < 0) return -1
        return byteOffsetToCharOffset(startByte)
    }

    /**
     * Returns the end char position of the given group.
     * 
     * @param group the group index
     * @return the end position, or -1 if the group didn't participate
     */
    /** Returns the end char position of the whole match.  */
    @JvmOverloads
    fun end(group: Int = 0): Int {
        checkMatch()
        val endByte = matchByteOffsets[2 * group + 1]
        if (endByte < 0) return -1
        return byteOffsetToCharOffset(endByte)
    }

    /** Returns the number of capturing groups in the pattern.  */
    fun groupCount(): Int {
        return pattern.numGroups()
    }

    /**
     * Replaces every match in the input with the given replacement string. Supports backreferences
     * ($1, $2, etc.) by converting them to RE2's \1, \2 syntax.
     * 
     * @param replacement the replacement string
     * @return the result string
     */
    fun replaceAll(replacement: String): String? {
        reset()
        val rewrite = translateToRE2Rewrite(replacement)
        if (rewrite != null) {
            val rewriteBytes: ByteArray = rewrite.getBytes(StandardCharsets.UTF_8)
            val result =
                Re2Shim.replaceAll(pattern.nativeHandle(), inputUtf8, inputString, rewriteBytes).result
            this.matched = false
            this.searchBytePos = inputUtf8.length + 1
            return result
        }
        return replaceAllJava(replacement)
    }

    /**
     * Replaces the first match in the input with the given replacement string.
     * 
     * @param replacement the replacement string
     * @return the result string
     */
    fun replaceFirst(replacement: String): String {
        reset()
        if (!find()) {
            return inputString!!
        }
        val sb = StringBuilder()
        // Append text before the match.
        val matchStartChar = start()
        sb.append(inputString, 0, matchStartChar)

        // Build the replacement from the rewrite template.
        appendReplacement(sb, replacement)

        // Append text after the match.
        val matchEndChar = end()
        sb.append(inputString, matchEndChar, inputString.length())

        return sb.toString()
    }

    // --- Private helpers ---
    /** Expands Java-style numeric group references and escapes against the current match.  */
    private fun appendReplacement(sb: StringBuilder, replacement: String) {
        val maxGroup = pattern.numGroups()
        var i = 0
        while (i < replacement.length()) {
            val c: Char = replacement.charAt(i)
            if (c == '\\') {
                require(++i < replacement.length()) { "character to be escaped is missing" }
                sb.append(replacement.charAt(i))
            } else if (c == '$') {
                require(!(++i >= replacement.length() || !Character.isDigit(replacement.charAt(i)))) { "Illegal group reference" }
                var groupNum = replacement.charAt(i).code - '0'.code
                if (groupNum > maxGroup) {
                    throw IndexOutOfBoundsException("No group " + groupNum)
                }
                while (i + 1 < replacement.length() && Character.isDigit(replacement.charAt(i + 1))) {
                    val candidate = groupNum * 10 + (replacement.charAt(i + 1).code - '0'.code)
                    if (candidate > maxGroup) {
                        break
                    }
                    groupNum = candidate
                    i++
                }
                val group = group(groupNum)
                if (group != null) {
                    sb.append(group)
                }
            } else {
                sb.append(c)
            }
            i++
        }
    }

    private fun translateToRE2Rewrite(replacement: String): String? {
        val numGroups = pattern.numGroups()
        if (numGroups > 9) {
            return null
        }
        if (replacement.indexOf('{'.code) >= 0) {
            return null
        }
        val sb = StringBuilder()
        var i = 0
        while (i < replacement.length()) {
            val c: Char = replacement.charAt(i)
            if (c == '\\') {
                if (++i >= replacement.length()) {
                    return null
                }
                val next: Char = replacement.charAt(i)
                if (next == '$') {
                    sb.append('$')
                } else if (next == '\\') {
                    sb.append("\\\\")
                } else {
                    sb.append(next)
                }
            } else if (c == '$') {
                if (++i >= replacement.length()) {
                    return null
                }
                val next: Char = replacement.charAt(i)
                if (next >= '0' && next <= '9') {
                    if (i + 1 < replacement.length() && Character.isDigit(replacement.charAt(i + 1))) {
                        return null
                    }
                    val groupIndex = next.code - '0'.code
                    if (groupIndex > numGroups) {
                        return null
                    }
                    sb.append('\\').append(next)
                } else {
                    return null
                }
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun replaceAllJava(replacement: String): String {
        val sb = StringBuilder()
        var lastAppend = 0
        while (find()) {
            val matchStartChar = start()
            sb.append(inputString, lastAppend, matchStartChar)
            appendReplacement(sb, replacement)
            lastAppend = end()
        }
        sb.append(inputString, lastAppend, inputString.length())
        return sb.toString()
    }

    private fun nextCodePointByteOffset(byteOffset: Int): Int {
        if (byteOffset >= regionEndByte) {
            return regionEndByte + 1
        }
        val charOffset = byteOffsetToCharOffset(byteOffset)
        val nextCharOffset = charOffset + Character.charCount(inputString!!.codePointAt(charOffset))
        return charOffsetToByteOffset(nextCharOffset)
    }

    private fun buildCharToByteMap(s: String, bytes: ByteArray) {
        if (bytes.length == s.length()) {
            var ok = true
            for (i in bytes.indices) {
                if (bytes[i] == '?'.code.toByte() && s.charAt(i) != '?') {
                    ok = false
                    break
                }
            }
            if (ok) {
                charToByteMap = null
                return
            }
        }

        charToByteMap = IntArray(s.length())
        var byteI = 0
        var charI = 0
        while (byteI < bytes.length) {
            val b = bytes[byteI]
            val len: Int
            if (b >= 0) {
                charToByteMap!![charI++] = byteI
                len = 1
            } else {
                len = Integer.numberOfLeadingZeros((b.toInt() shl 24).inv())
                if (charI < charToByteMap.length) {
                    charToByteMap!![charI++] = byteI
                }
                if (len == 4) {
                    if (charI < charToByteMap.length) {
                        charToByteMap!![charI++] = byteI + 2
                    }
                }
            }
            byteI += len
        }
    }

    fun byteOffsetToCharOffset(byteOffset: Int): Int {
        if (charToByteMap == null) {
            return byteOffset
        }
        if (byteOffset <= 0) {
            return 0
        }
        if (byteOffset >= inputUtf8.length) {
            return inputString.length()
        }

        var i = Arrays.binarySearch(charToByteMap, byteOffset)
        if (i < 0) {
            i = -(i + 1) // insertion point (round up)
        }
        return i
    }

    private fun charOffsetToByteOffset(charOffset: Int): Int {
        if (charToByteMap == null) {
            return charOffset
        }
        if (charOffset <= 0) {
            return 0
        }
        if (charOffset >= inputString.length()) {
            return inputUtf8.length
        }
        return charToByteMap!![charOffset]
    }

    private fun checkCodePointBoundary(offset: Int) {
        require(
            !(offset > 0 && offset < inputString.length() && Character.isHighSurrogate(inputString.charAt(offset - 1))
                    && Character.isLowSurrogate(inputString.charAt(offset)))
        ) { "UTF-16 offset splits a surrogate pair: " + offset }
    }

    private fun checkMatch() {
        check(matched) { "No match found" }
    }

    companion object {
        // The RE2::Anchor values accepted by re2_match.
        private const val UNANCHORED = 0
        private const val ANCHOR_START = 1
        private const val ANCHOR_BOTH = 2
    }
}
