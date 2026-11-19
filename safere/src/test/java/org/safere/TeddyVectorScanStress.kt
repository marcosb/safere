// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.nio.charset.StandardCharsets
import java.util.*

/** Standalone C2 stress driver; see the Vector compiler stress protocol in docs/TESTING.md.  */
object TeddyVectorScanStress {
    /** Warms the three-byte model before introducing models with disabled vector stages.  */
    @JvmStatic
    fun main(args: Array<String>) {
        val models = arrayOf<TeddyModel>(
            TeddyModel.compile(arrayOf<String>("apple", "banana", "cherry"), 64),
            TeddyModel.compile(arrayOf<String>("ab", "cherry", "abc"), 64),
            TeddyModel.compile(arrayOf<String>("abc", "cherry", "ab"), 64),
            TeddyModel.compile(arrayOf<String>("abc", "bcd", "cde"), 64)
        )
        val data = ByteArray(4096)
        Arrays.fill(data, 'x'.code.toByte())
        val prefix = "a b c ".repeat(32).toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(prefix, 0, data, 0, prefix.size)
        val suffix = "abcde cherry".toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(suffix, 0, data, data.size - suffix.size, suffix.size)
        var sum: Long = 0
        for (phase in 0..3) {
            println("phase=" + phase)
            for (i in 0..2999) {
                val model = models[if (phase == 0) 0 else i % models.size]
                val found = TeddyVectorScan.indexOfTeddyUtf8(data, 0, data.size, model, i % 128)
                val expected = data.size - (if (model == models[0]) 6 else 12)
                if (found != expected) {
                    throw AssertionError("expected=" + expected + " actual=" + found)
                }
                sum += found.toLong()
            }
        }
        println("sum=" + sum)
    }
}
