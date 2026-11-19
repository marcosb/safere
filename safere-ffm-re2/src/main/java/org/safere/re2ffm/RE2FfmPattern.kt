// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.re2ffm

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.ref.Cleaner
import java.nio.charset.StandardCharsets

/**
 * A compiled regular expression backed by C++ RE2 via the FFM API. This is a benchmark-oriented
 * wrapper — it supports enough of the `java.util.regex.Pattern` API to run the SafeRE
 * benchmark suite.
 */
class RE2FfmPattern private constructor(
    private val patternString: String?,
    private val flags: Int,
    private val nativeHandle: MemorySegment?
) {
    private val numGroups: Int

    init {
        this.numGroups = Re2Shim.numCapturingGroups(nativeHandle)

        // Register cleanup so the native handle is freed when this object is GC'd.
        val handle = nativeHandle
        CLEANER.register(this, Runnable { Re2Shim.free(handle) })
    }

    /**
     * Creates a matcher for this pattern against the given input.
     * 
     * @param input the input text
     * @return a new matcher
     */
    fun matcher(input: String?): RE2FfmMatcher {
        return RE2FfmMatcher(this, input)
    }

    /**
     * Splits the given input around matches of this pattern.
     * 
     * @param input the character sequence to be split
     * @param limit the result threshold
     * @return the array of strings computed by splitting the input around matches of this pattern
     */
    /**
     * Splits the given input around matches of this pattern. Trailing empty strings are discarded.
     * 
     * @param input the character sequence to be split
     * @return the array of strings computed by splitting the input around matches of this pattern
     */
    @JvmOverloads
    fun split(input: CharSequence, limit: Int = 0): Array<String?> {
        val text = input.toString()
        val inputUtf8: ByteArray = text.getBytes(StandardCharsets.UTF_8)

        val matcher = RE2FfmMatcher(this, text, inputUtf8)

        // Start with a buffer for the expected matches (or a default of 128).
        // The buffer size is doubled dynamically inside the loop if the text contains more matches.
        var maxMatches = if (limit > 0) limit else 128
        var numMatches: Int
        val byteOffsets: IntArray?

        Arena.ofConfined().use { arena ->
            val textSeg: MemorySegment? = arena.allocateFrom(ValueLayout.JAVA_BYTE, inputUtf8)
            while (true) {
                val matchesSeg: MemorySegment = arena.allocate(ValueLayout.JAVA_INT, 2L * maxMatches)

                numMatches =
                    Re2Shim.findAll(nativeHandle, textSeg, inputUtf8.size, matchesSeg, maxMatches)

                if (numMatches < maxMatches || limit > 0) {
                    byteOffsets = IntArray(2 * numMatches)
                    MemorySegment.copy(matchesSeg, ValueLayout.JAVA_INT, 0, byteOffsets, 0, 2 * numMatches)
                    break
                }
                maxMatches *= 2
            }
        }
        var parts: MutableList<String?> = ArrayList<String?>()
        var last = 0

        for (i in 0..<numMatches) {
            if (limit > 0 && parts.size >= limit - 1) {
                break
            }
            val startByte = byteOffsets!![2 * i]
            val endByte = byteOffsets[2 * i + 1]

            val startChar = matcher.byteOffsetToCharOffset(startByte)
            val endChar = matcher.byteOffsetToCharOffset(endByte)

            if (last == 0 && startChar == 0 && endChar == 0) {
                continue
            }
            parts.add(text.substring(last, startChar))
            last = endChar
        }

        if (last == 0) {
            return arrayOf<String>(text)
        }

        parts.add(text.substring(last))
        if (limit == 0) {
            var end: Int = parts.size
            while (end > 0 && parts.get(end - 1)!!.isEmpty()) {
                end--
            }
            parts = parts.subList(0, end)
        }
        return parts.toArray<String?>(arrayOfNulls<String>(0))
    }

    /** Returns the pattern string.  */
    fun pattern(): String? {
        return patternString
    }

    /** Returns the compilation flags.  */
    fun flags(): Int {
        return flags
    }

    /** Returns the native RE2 handle. Package-private for use by [RE2FfmMatcher].  */
    fun nativeHandle(): MemorySegment? {
        return nativeHandle
    }

    /** Returns the number of capturing groups.  */
    fun numGroups(): Int {
        return numGroups
    }

    companion object {
        /** Case-insensitive matching flag, compatible with `java.util.regex.Pattern`.  */
        const val CASE_INSENSITIVE: Int = 2

        private val CLEANER: Cleaner = Cleaner.create()

        /**
         * Compiles a regular expression with the given flags.
         * 
         * @param regex the regular expression
         * @param flags compilation flags (e.g., [.CASE_INSENSITIVE])
         * @return a compiled pattern
         * @throws IllegalArgumentException if the pattern is invalid
         */
        /**
         * Compiles a regular expression.
         * 
         * @param regex the regular expression
         * @return a compiled pattern
         * @throws IllegalArgumentException if the pattern is invalid
         */
        @JvmOverloads
        fun compile(regex: String, flags: Int = 0): RE2FfmPattern {
            val utf8: ByteArray = regex.getBytes(StandardCharsets.UTF_8)
            val handle: MemorySegment?
            Arena.ofConfined().use { arena ->
                val patternSeg: MemorySegment? = arena.allocateFrom(ValueLayout.JAVA_BYTE, utf8)
                if ((flags and CASE_INSENSITIVE) != 0) {
                    handle = Re2Shim.compileCaseInsensitive(patternSeg, utf8.size)
                } else {
                    handle = Re2Shim.compile(patternSeg, utf8.size)
                }
            }
            if (!Re2Shim.ok(handle)) {
                val error = Re2Shim.error(handle)
                Re2Shim.free(handle)
                throw IllegalArgumentException("RE2 compilation failed: " + error)
            }
            return RE2FfmPattern(regex, flags, handle)
        }
    }
}
