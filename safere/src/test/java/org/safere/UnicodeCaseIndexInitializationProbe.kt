// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.nio.charset.StandardCharsets
import java.util.*

/** Compiles one pattern in a fresh JVM for the Unicode index initialization test.  */
object UnicodeCaseIndexInitializationProbe {
    /** Compiles the Base64-encoded UTF-8 pattern supplied as the first command-line argument.  */
    @JvmStatic
    fun main(args: Array<String>) {
        Pattern.compile(String(Base64.getDecoder().decode(args[0]), StandardCharsets.UTF_8))
    }
}
