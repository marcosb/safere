// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import org.safere.Utf8Matcher
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Byte validation and progress checks shared without a regex oracle.  */
internal object Utf8RobustnessChecks {
    fun walk(matcher: Utf8Matcher, length: Int) {
        var previousEnd = -1
        var attempts = 0
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            if (start < 0 || start > end || end > length || end < previousEnd) {
                throw AssertionError("non-monotonic or out-of-window match bounds")
            }
            for (group in 0..matcher.groupCount()) {
                val groupStart = matcher.start(group)
                val groupEnd = matcher.end(group)
                if ((groupStart < 0) != (groupEnd < 0) || groupStart > groupEnd || groupEnd > length) {
                    throw AssertionError("invalid capture bounds")
                }
            }
            previousEnd = end
            if (++attempts > length + 1) {
                throw AssertionError("find did not make bounded progress")
            }
        }
    }

    fun isValidUtf8(bytes: ByteArray, offset: Int, length: Int): Boolean {
        try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
            return true
        } catch (e: CharacterCodingException) {
            return false
        }
    }
}
