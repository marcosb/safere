// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import org.safere.Pattern
import org.safere.Utf8Input
import org.safere.crosscheck.Pattern.Companion.compile
import java.util.*

/** Checks that the requested UTF-8 scan provider can run from the benchmark artifact.  */
object BenchmarkProviderCheck {
    /** Exercises a long absent scan before measurement to fail clearly on provider loading errors.  */
    @JvmStatic
    fun main(args: Array<String>) {
        val provider = System.getProperty("org.safere.experimental.vectorScanProvider", "default")
        check(
            !(provider == "vector"
                    && ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty())
        ) {
            ("Could not enable the experimental Vector UTF-8 scanner; add "
                    + "--add-modules=jdk.incubator.vector")
        }
        val bytes = ByteArray(2048)
        Arrays.fill(bytes, 'b'.code.toByte())
        val pattern: Pattern = compile("(?:xyz|uvw)[0-9]+")
        if (pattern.find(Utf8Input.Companion.trusted(bytes))) {
            throw AssertionError("Unexpected match during provider startup check")
        }
        val suffix = byteArrayOf('x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte(), '7'.code.toByte())
        System.arraycopy(suffix, 0, bytes, bytes.size - suffix.size, suffix.size)
        if (!pattern.find(Utf8Input.Companion.trusted(bytes))) {
            throw AssertionError("Missing late match during provider startup check")
        }
        println("UTF-8 scan provider startup check passed: " + provider)
    }
}
