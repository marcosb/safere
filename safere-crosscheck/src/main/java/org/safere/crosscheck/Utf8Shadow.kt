// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.crosscheck

import org.safere.Matcher
import org.safere.Pattern
import org.safere.Utf8Input
import org.safere.Utf8Matcher
import org.safere.Utf8Sink
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** A UTF-8 matcher shadow for String crosscheck operations with equivalent UTF-8 APIs.  */
internal class Utf8Shadow private constructor(
    private val pattern: Pattern,
    private val bytes: ByteArray,
    private val coordinates: Utf8Coordinates
) {
    private val matcher: Utf8Matcher

    init {
        matcher = newMatcher()
    }

    fun find(): Boolean {
        return matcher.find()
    }

    fun groupCount(): Int {
        return matcher.groupCount()
    }

    fun start(group: Int): Int {
        return toUtf16(matcher.start(group))
    }

    fun end(group: Int): Int {
        return toUtf16(matcher.end(group))
    }

    fun canRepresent(stringMatcher: Matcher): Boolean {
        for (group in 0..stringMatcher.groupCount()) {
            val start = stringMatcher.start(group)
            val end = stringMatcher.end(group)
            if (start >= 0
                && (!coordinates.isUtf16Boundary(start) || !coordinates.isUtf16Boundary(end))
            ) {
                return false
            }
        }
        return true
    }

    fun group(group: Int): String? {
        val start = matcher.start(group)
        if (start < 0) {
            return null
        }
        val end = matcher.end(group)
        coordinates.toUtf16(start)
        coordinates.toUtf16(end)
        return String(bytes, start, end - start, StandardCharsets.UTF_8)
    }

    fun canEncode(text: String): Boolean {
        return Utf8Coordinates.Companion.create(text) != null
    }

    fun appendReplacement(replacement: String): String? {
        val output = ByteArrayOutputStream()
        matcher.appendReplacement(
            Utf8Sink { b: ByteArray?, off: Int, len: Int -> output.write(b, off, len) }, Utf8Input.validated(
                replacement.toByteArray(
                    StandardCharsets.UTF_8
                )
            )
        )
        return output.toString(StandardCharsets.UTF_8)
    }

    fun appendTail(): String? {
        val output = ByteArrayOutputStream()
        matcher.appendTail(Utf8Sink { b: ByteArray?, off: Int, len: Int -> output.write(b, off, len) })
        return output.toString(StandardCharsets.UTF_8)
    }

    fun replaceFirst(replacement: String): String? {
        val replacementMatcher = newMatcher()
        val output = ByteArrayOutputStream()
        if (replacementMatcher.find()) {
            replacementMatcher.appendReplacement(
                Utf8Sink { b: ByteArray?, off: Int, len: Int -> output.write(b, off, len) }, Utf8Input.validated(
                    replacement.toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
            )
        }
        replacementMatcher.appendTail(Utf8Sink { b: ByteArray?, off: Int, len: Int -> output.write(b, off, len) })
        return output.toString(StandardCharsets.UTF_8)
    }

    fun replaceAll(replacement: String): String? {
        val replacementMatcher = newMatcher()
        val utf8Replacement =
            Utf8Input.validated(replacement.toByteArray(StandardCharsets.UTF_8))
        val output = ByteArrayOutputStream()
        while (replacementMatcher.find()) {
            replacementMatcher.appendReplacement(Utf8Sink { b: ByteArray?, off: Int, len: Int ->
                output.write(
                    b,
                    off,
                    len
                )
            }, utf8Replacement)
        }
        replacementMatcher.appendTail(Utf8Sink { b: ByteArray?, off: Int, len: Int -> output.write(b, off, len) })
        return output.toString(StandardCharsets.UTF_8)
    }

    private fun newMatcher(): Utf8Matcher {
        return pattern.matcher(Utf8Input.validated(bytes))
    }

    private fun toUtf16(utf8Offset: Int): Int {
        return if (utf8Offset < 0) -1 else coordinates.toUtf16(utf8Offset)
    }

    companion object {
        fun create(pattern: org.safere.crosscheck.Pattern, input: CharSequence?): Utf8Shadow? {
            if (input !is String) {
                return null
            }
            val coordinates: Utf8Coordinates? = Utf8Coordinates.Companion.create(input)
            if (coordinates == null) {
                return null
            }
            val bytes = input.toByteArray(StandardCharsets.UTF_8)
            return Utf8Shadow(pattern.saferePattern(), bytes, coordinates)
        }
    }
}
