// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.nio.charset.StandardCharsets

internal class ArrayUtf8Input(bytes: ByteArray?, offset: Int, length: Int, validated: Boolean) : Utf8Input {
    private val scanner: Utf8InputScanner
    private val validated: Boolean

    init {
        scanner = Utf8InputScanner(bytes, offset, length)
        this.validated = validated
    }

    override fun length(): Int {
        return scanner.length()
    }

    fun scanner(): Utf8InputScanner {
        return scanner
    }

    fun validated(): Boolean {
        return validated
    }

    fun decode(): String {
        return String(scanner.bytes(), scanner.offset(), scanner.length(), StandardCharsets.UTF_8)
    }

    fun appendRange(sink: Utf8Sink, start: Int, end: Int) {
        if (start < 0 || end < start || end > length()) {
            throw IndexOutOfBoundsException("start=" + start + ", end=" + end)
        }
        sink.append(scanner.bytes(), scanner.offset() + start, end - start)
    }
}
