// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*
import kotlin.jvm.JvmRecord

/**
 * A stateful matcher over UTF-8 input whose reported positions are relative byte offsets.
 * 
 * 
 * The matcher retains its borrowed input and is not thread-safe. The caller must not mutate the
 * input storage while this matcher is in use. Pattern and replacement behavior follows SafeRE's
 * documented Java-oriented semantics; coordinates are UTF-8 byte offsets.
 */
class Utf8Matcher internal constructor(pattern: Pattern?, input: Utf8Input?) {
    private val pattern: Pattern
    private val input: ArrayUtf8Input
    private val delegate: Matcher
    private var appendPosition = 0
    private var replacementFailed = false
    private var cachedReplacement: Utf8Input? = null
    private lateinit var cachedTemplate: Array<ReplacementSegment>
    private var cachedTemplateNeedsCaptures = false
    private var modCount = 0
    private var appending = false

    init {
        this.pattern = Objects.requireNonNull(pattern, "pattern")!!
        this.input = Objects.requireNonNull<Utf8Input?>(input, "input") as ArrayUtf8Input
        delegate = Matcher(pattern!!, this.input.scanner())
    }

    /**
     * Attempts to match the entire input against this pattern.
     * 
     * @return whether the entire input matches
     */
    fun matches(): Boolean {
        beginMatchOperation()
        return delegate.matches()
    }

    /**
     * Attempts to match a prefix of the input against this pattern.
     * 
     * @return whether an input prefix matches
     */
    fun lookingAt(): Boolean {
        beginMatchOperation()
        return delegate.lookingAt()
    }

    /**
     * Attempts to find the next subsequence matching this pattern.
     * 
     * @return whether a match was found
     */
    fun find(): Boolean {
        beginMatchOperation()
        return delegate.find()
    }

    /**
     * Resets this matcher to search the whole retained input from its beginning.
     * 
     * @return this matcher
     */
    fun reset(): Utf8Matcher {
        beginMatchOperation()
        delegate.reset()
        resetReplacementState()
        return this
    }

    /**
     * Sets the byte-offset range searched by this matcher and resets its match state.
     * 
     * 
     * Both offsets must be UTF-8 code-point boundaries in the retained input.
     * 
     * @param start relative byte offset at the start of the region
     * @param end relative byte offset after the end of the region
     * @return this matcher
     * @throws IndexOutOfBoundsException if the range is invalid or either offset is not a code-point
     * boundary
     */
    fun region(start: Int, end: Int): Utf8Matcher {
        if (start < 0 || end < start || end > input.length) {
            throw IndexOutOfBoundsException(
                "start=" + start + ", end=" + end + ", length=" + input.length
            )
        }
        val scanner = input.scanner()
        if (!scanner.isCodePointBoundary(start) || !scanner.isCodePointBoundary(end)) {
            throw IndexOutOfBoundsException(
                "Region offsets must be UTF-8 code-point boundaries: start=" + start + ", end=" + end
            )
        }
        beginMatchOperation()
        delegate.region(start, end)
        resetReplacementState()
        return this
    }

    /**
     * Returns the relative byte offset at the start of this matcher's region.
     * 
     * @return the region start
     */
    fun regionStart(): Int {
        return delegate.regionStart()
    }

    /**
     * Returns the relative byte offset after the end of this matcher's region.
     * 
     * @return the region end
     */
    fun regionEnd(): Int {
        return delegate.regionEnd()
    }

    /**
     * Returns the relative byte offset where the previous match started.
     * 
     * @return the start of group zero
     * @throws IllegalStateException if there is no current successful match
     */
    fun start(): Int {
        return delegate.start()
    }

    /**
     * Returns the relative byte offset where the specified group started.
     * 
     * @param group capture group index
     * @return the group start, or `-1` if the group did not participate
     * @throws IllegalStateException if there is no current successful match
     * @throws IndexOutOfBoundsException if `group` is invalid
     */
    fun start(group: Int): Int {
        return delegate.start(group)
    }

    /**
     * Returns the relative byte offset after the previous match.
     * 
     * @return the end of group zero
     * @throws IllegalStateException if there is no current successful match
     */
    fun end(): Int {
        return delegate.end()
    }

    /**
     * Returns the relative byte offset after the specified group.
     * 
     * @param group capture group index
     * @return the group end, or `-1` if the group did not participate
     * @throws IllegalStateException if there is no current successful match
     * @throws IndexOutOfBoundsException if `group` is invalid
     */
    fun end(group: Int): Int {
        return delegate.end(group)
    }

    /**
     * Returns the number of capturing groups in the pattern.
     * 
     * @return the capture group count
     */
    fun groupCount(): Int {
        return delegate.groupCount()
    }

    /**
     * Appends the whole input to `sink`, replacing every matching subsequence.
     * 
     * 
     * This method resets the matcher before searching, discarding any active region and prior
     * append position. Existing sink contents are retained. If there are no matches, the original
     * input is appended without parsing the replacement. Empty matches advance by a Unicode scalar.
     * On successful return, there is no current match.
     * 
     * 
     * Output is written synchronously and cannot be rolled back. If a sink callback throws,
     * replacement output may be partial and further append operations require a reset.
     * 
     * @param sink synchronous destination for output ranges
     * @param replacement UTF-8 replacement using SafeRE's String replacement syntax
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if a match is found and the replacement is malformed
     * @throws IndexOutOfBoundsException if a match is found and a numbered group reference is invalid
     * @throws ConcurrentModificationException if a sink callback advances this matcher
     */
    fun replaceAll(sink: Utf8Sink?, replacement: Utf8Input?) {
        replace(sink, replacement, true)
    }

    /**
     * Appends the whole input to `sink`, replacing only the first matching subsequence.
     * 
     * 
     * This method resets the matcher before searching, discarding any active region and prior
     * append position. Existing sink contents are retained. If there are no matches, the original
     * input is appended without parsing the replacement. On successful return, the replaced match
     * remains current if one was found, and a subsequent [.find] searches for the next match.
     * 
     * 
     * Output is written synchronously and cannot be rolled back. If a sink callback throws,
     * replacement output may be partial and further append operations require a reset.
     * 
     * @param sink synchronous destination for output ranges
     * @param replacement UTF-8 replacement using SafeRE's String replacement syntax
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if a match is found and the replacement is malformed
     * @throws IndexOutOfBoundsException if a match is found and a numbered group reference is invalid
     * @throws ConcurrentModificationException if a sink callback advances this matcher
     */
    fun replaceFirst(sink: Utf8Sink?, replacement: Utf8Input?) {
        replace(sink, replacement, false)
    }

    private fun replace(sink: Utf8Sink?, replacement: Utf8Input?, all: Boolean) {
        Objects.requireNonNull(sink, "sink")
        Objects.requireNonNull<Utf8Input?>(replacement, "replacement")
        reset()
        // The first find applies whole-input and anchored prefix/class rejection. This uses the
        // regular matcher pipeline; String has separate literal, character-class and DFA replacement
        // loops.
        if (find()) {
            do {
                appendReplacement(sink, replacement)
            } while (all && find())
        }
        appendTail(sink)
    }

    /**
     * Appends the unmatched prefix and expanded replacement for the current match.
     * 
     * @param sink synchronous destination for output ranges
     * @param replacement UTF-8 replacement using SafeRE's String replacement syntax
     * @return this matcher
     * @throws IllegalStateException if no match is current or replacement previously failed
     * @throws ConcurrentModificationException if a sink callback advances this matcher
     */
    fun appendReplacement(sink: Utf8Sink?, replacement: Utf8Input?): Utf8Matcher {
        Objects.requireNonNull(sink, "sink")
        Objects.requireNonNull<Utf8Input?>(replacement, "replacement")
        check(!replacementFailed) { "Replacement failed" }
        beginAppend()
        val expectedModCount = modCount
        try {
            val template = replacementTemplate(replacement)
            // Group 0 bounds don't require resolving inner captures, which can be much more expensive.
            val bounds = if (cachedTemplateNeedsCaptures) captureBounds() else intArrayOf(start(), end())
            appendRange(sink!!, appendPosition, bounds[0], expectedModCount)
            for (segment in template) {
                when (segment) {
                    is ReplacementSegment.Literal -> {
                        sink!!.append(segment.bytes(), segment.offset(), segment.length)
                        checkForConcurrentModification(expectedModCount)
                    }

                    is ReplacementSegment.GroupRef -> appendGroup(sink, bounds, segment.group, expectedModCount)
                    is ReplacementSegment.NamedGroupRef -> appendGroup(sink, bounds, segment.group, expectedModCount)
                }
            }
            appendPosition = bounds[1]
            return this
        } catch (e: RuntimeException) {
            replacementFailed = true
            throw e
        } catch (e: Error) {
            replacementFailed = true
            throw e
        } finally {
            appending = false
        }
    }

    /**
     * Appends the part of the input following the last replacement.
     * 
     * @param sink synchronous destination for the remaining input
     * @throws IllegalStateException if replacement previously failed
     * @throws ConcurrentModificationException if a sink callback advances this matcher
     */
    fun appendTail(sink: Utf8Sink?) {
        Objects.requireNonNull(sink, "sink")
        check(!replacementFailed) { "Replacement failed" }
        beginAppend()
        val expectedModCount = modCount
        try {
            appendRange(sink!!, appendPosition, input.length, expectedModCount)
            appendPosition = input.length
        } catch (e: RuntimeException) {
            replacementFailed = true
            throw e
        } catch (e: Error) {
            replacementFailed = true
            throw e
        } finally {
            appending = false
        }
    }

    private fun appendGroup(sink: Utf8Sink?, bounds: IntArray, group: Int, expectedModCount: Int) {
        val start = bounds[group * 2]
        if (start >= 0) {
            appendRange(sink!!, start, bounds[group * 2 + 1], expectedModCount)
        }
    }

    private fun captureBounds(): IntArray {
        val bounds = IntArray((groupCount() + 1) * 2)
        for (group in 0..groupCount()) {
            bounds[group * 2] = start(group)
            bounds[group * 2 + 1] = end(group)
        }
        return bounds
    }

    private fun appendRange(sink: Utf8Sink?, start: Int, end: Int, expectedModCount: Int) {
        input.appendRange(sink!!, start, end)
        checkForConcurrentModification(expectedModCount)
    }

    private fun beginAppend() {
        if (appending) {
            throw ConcurrentModificationException()
        }
        appending = true
    }

    private fun beginMatchOperation() {
        if (appending) {
            throw ConcurrentModificationException()
        }
        modCount++
    }

    private fun resetReplacementState() {
        appendPosition = 0
        replacementFailed = false
    }

    private fun checkForConcurrentModification(expectedModCount: Int) {
        if (modCount != expectedModCount) {
            throw ConcurrentModificationException()
        }
    }

    private fun replacementTemplate(replacement: Utf8Input?): Array<ReplacementSegment> {
        if (replacement !== cachedReplacement) {
            val replacementInput = replacement as ArrayUtf8Input
            if (isPlainLiteralReplacement(replacementInput)) {
                val scanner = replacementInput.scanner()
                cachedTemplate =
                    arrayOf<ReplacementSegment>(
                        ReplacementSegment.Literal(scanner.bytes(), scanner.offset(), scanner.length)
                    )
                cachedTemplateNeedsCaptures = false
                cachedReplacement = replacement
                return cachedTemplate
            }
            val text = replacementInput.decode()
            val groupCount = groupCount()
            val parsed = Matcher.Companion.compileReplacementTemplate(text, groupCount)
            val compiled: Array<ReplacementSegment?> = arrayOfNulls(parsed.size)
            val namedGroups = pattern.namedGroups()
            var needsCaptures = false
            for (index in 0 until parsed.size) {
                compiled[index] =
                    when (val segment = parsed[index]) {
                        is Matcher.ReplacementSegment.Literal ->
                            ReplacementSegment.Literal(segment.text.toByteArray(java.nio.charset.StandardCharsets.UTF_8))

                        is Matcher.ReplacementSegment.GroupRef -> {
                            val group = segment.groupNum
                            if (group > groupCount) {
                                throw IndexOutOfBoundsException(
                                    "No group " + group + " (groupCount=" + groupCount + ")"
                                )
                            }
                            needsCaptures = needsCaptures or (group != 0)
                            ReplacementSegment.GroupRef(group)
                        }

                        is Matcher.ReplacementSegment.NamedGroupRef -> {
                            val group = namedGroups[segment.name]
                                ?: throw IllegalArgumentException("No group with name <" + segment.name + ">")
                            needsCaptures = needsCaptures or (group != 0)
                            ReplacementSegment.NamedGroupRef(group)
                        }

                        else -> throw IllegalStateException()
                    }
            }
            cachedTemplate = compiled.requireNoNulls()
            cachedTemplateNeedsCaptures = needsCaptures
            cachedReplacement = replacement
        }
        return cachedTemplate
    }

    private interface ReplacementSegment {
        class Literal internal constructor(
            private val bytes: ByteArray?,
            private val offset: Int,
            val length: Int,
        ) : ReplacementSegment {
            internal constructor(bytes: ByteArray) : this(bytes, 0, bytes.size)

            fun bytes(): ByteArray? {
                return bytes
            }

            fun offset(): Int {
                return offset
            }
        }

        @JvmRecord
        data class GroupRef(val group: Int) : ReplacementSegment

        @JvmRecord
        data class NamedGroupRef(val group: Int) : ReplacementSegment
    }

    companion object {
        private fun isPlainLiteralReplacement(replacement: ArrayUtf8Input): Boolean {
            val scanner = replacement.scanner()
            val bytes = scanner.bytes()
            val end = scanner.offset() + scanner.length
            for (index in scanner.offset() until end) {
                val value = bytes!![index]
                // Keep malformed, trusted non-ASCII replacements on the decoding path so their existing
                // replacement-character behavior is preserved. ASCII is valid without a validation pass.
                if (value == '$'.code.toByte() || value == '\\'.code.toByte() || (value < 0 && !replacement.validated())) {
                    return false
                }
            }
            return true
        }
    }
}
