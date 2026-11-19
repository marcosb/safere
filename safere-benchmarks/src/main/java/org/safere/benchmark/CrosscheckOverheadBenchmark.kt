// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import org.openjdk.jmh.annotations.*
import org.safere.Pattern
import org.safere.crosscheck.Pattern.Companion.compile
import java.util.concurrent.TimeUnit

/**
 * Measures overhead added by the crosscheck facade for replacement loops that call `appendReplacement` once per match.
 * 
 * 
 * This is a diagnostic benchmark for crosscheck optimization work. The Java benchmark wrapper
 * excludes it from default no-argument runs; run this class explicitly when needed.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
class CrosscheckOverheadBenchmark {
    @Param("16", "128", "1024")
    var matches: Int = 0

    private var text: String? = null
    private var replacement: String? = null
    private var saferePattern: Pattern? = null
    private var jdkPattern: java.util.regex.Pattern? = null
    private var crosscheckPattern: org.safere.crosscheck.Pattern? = null

    @Setup
    fun setup() {
        val sb = StringBuilder(matches * 12)
        for (i in 0..<matches) {
            sb.append("item").append(i).append(';')
        }
        text = sb.toString()
        val data: BenchmarkData = BenchmarkData.Companion.get()
        replacement = data.getString("configuration.crosscheckOverhead.replacement")

        val regex = data.getString("configuration.crosscheckOverhead.pattern")
        saferePattern = compile(regex)
        jdkPattern = java.util.regex.Pattern.compile(regex)
        crosscheckPattern = compile(regex)
    }

    @Benchmark
    fun appendReplacement_safere(): String {
        val matcher = saferePattern!!.matcher(text)
        val sb = StringBuilder(text!!.length)
        while (matcher.find()) {
            matcher.appendReplacement(sb, replacement)
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    @Benchmark
    fun appendReplacement_jdk(): String {
        val matcher = jdkPattern!!.matcher(text)
        val sb = StringBuilder(text!!.length)
        while (matcher.find()) {
            matcher.appendReplacement(sb, replacement)
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    @Benchmark
    fun appendReplacement_crosscheck(): String {
        val matcher = crosscheckPattern!!.matcher(text)
        val sb = StringBuilder(text!!.length)
        while (matcher.find()) {
            matcher.appendReplacement(sb, replacement!!)
        }
        matcher.appendTail(sb)
        return sb.toString()
    }
}
