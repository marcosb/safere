// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.vector.benchmark

import java.util.Arrays

/** Deterministic runtime smoke check for packaged UTF-8 scan-provider selection.  */
object VectorProviderSmoke {
    /** Checks absent and late matches on an input large enough to invoke the selected provider.  */
    fun main(args: Array<String?>?) {
        val bytes = ByteArray(2048)
        Arrays.fill(bytes, 'b'.code.toByte())
        val pattern: Pattern = Pattern.compile("x+")
        if (pattern.find(Utf8Input.trusted(bytes))) {
            throw AssertionError("Unexpected match in absent input")
        }
        bytes[bytes.length - 1] = 'x'.code.toByte()
        if (!pattern.find(Utf8Input.trusted(bytes))) {
            throw AssertionError("Missing late match")
        }
    }
}
