// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.vector.benchmark

import java.util.Arrays

/** Measures complete UTF-8 find operations with the provider selected at JVM startup.  */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
class Utf8VectorEndToEndProviderBenchmark {
    /** Data-declared first-hit trial in shape/traversal/density/length/offset form.  */
    @Param([])
    var vectorScanTrial: String? = null

    /** Scanner provider expected to have been selected by the benchmark runner.  */
    @Param(["swar", "vector"])
    var scanProvider: String? = null

    private var pattern: Pattern? = null
    private var input: Utf8Input? = null
    private var matcher: Utf8Matcher? = null
    private var scanAll = false
    private var expected = 0

    /** Materializes input and verifies the selected provider and complete result.  */
    @Setup(Level.Trial)
    fun setup() {
        val fields: Array<String?> = vectorScanTrial.split("/", -1)
        kotlin.require(fields.size == 5) { "Malformed Vector scan trial: " + vectorScanTrial }
        scanAll = fields[1]!!.equals("all")
        kotlin.require(!(!scanAll && !fields[1]!!.equals("first"))) { "Unknown traversal: " + fields[1] }
        var inputProfile = ""
        val regex =
            when (fields[0].toLowerCase(Locale.ROOT)) {
                "singleton" -> throw IllegalArgumentException(
                    "Singleton patterns use literal scanning, not the character-class provider"
                )

                "pair" -> "[xy]"
                "range" -> "[0-9]"
                "alnum3" -> {
                    inputProfile = "multi."
                    "[0-9A-Za-z]"
                }

                "mixed4" -> {
                    inputProfile = "multi."
                    "[!#0-9A-Z]"
                }

                else -> throw IllegalArgumentException("Unknown shape: " + fields[0])
            }
        val length: Int = Integer.parseInt(fields[3])
        val offset: Int = Integer.parseInt(fields[4])
        val configuredProvider: String? =
            System.getProperty("org.safere.experimental.vectorScanProvider", "swar").trim()
        kotlin.check(scanProvider!!.equals(configuredProvider)) { "Expected provider " + scanProvider + " but JVM selected " + configuredProvider }
        val source: ByteArray = VectorScanConfiguration.input(inputProfile, fields[2], length)
        val storage = ByteArray(offset + source.size + ByteVector.SPECIES_PREFERRED.length)
        Arrays.fill(storage, 0x7f.toByte())
        System.arraycopy(source, 0, storage, offset, source.size)

        pattern = Pattern.compile(regex)
        input = Utf8Input.trusted(storage, offset, length)
        matcher = pattern.matcher(input)
        expected = expectedResult(fields[0]!!, source)
        if (runSafeRe() != expected) {
            throw AssertionError("Unexpected result for " + vectorScanTrial)
        }
    }

    /** Runs the complete direct UTF-8 find operation.  */
    @Benchmark
    fun safeReFind(): Int {
        return runSafeRe()
    }

    private fun runSafeRe(): Int {
        if (!scanAll) {
            return if (pattern.find(input)) 1 else 0
        }
        matcher.reset()
        var checksum = 0
        while (matcher.find()) {
            checksum += matcher.start()
        }
        return checksum
    }

    private fun expectedResult(shape: String, source: ByteArray): Int {
        var checksum = 0
        for (position in source.indices) {
            if (org.safere.vector.benchmark.Utf8VectorEndToEndProviderBenchmark.Companion.matches(
                    shape,
                    source[position]
                )
            ) {
                if (!scanAll) {
                    return 1
                }
                checksum += position
            }
        }
        return checksum
    }

    companion object {
        private fun matches(shape: String, value: Byte): Boolean {
            return when (shape) {
                "pair" -> value == 'x'.code.toByte() || value == 'y'.code.toByte()
                "range" -> value >= '0'.code.toByte() && value <= '9'.code.toByte()
                "alnum3" -> ((value >= '0'.code.toByte() && value <= '9'.code.toByte())
                        || (value >= 'A'.code.toByte() && value <= 'Z'.code.toByte())
                        || (value >= 'a'.code.toByte() && value <= 'z'.code.toByte()))

                "mixed4" -> (value == '!'.code.toByte() || value == '#'.code.toByte() || (value >= '0'.code.toByte() && value <= '9'.code.toByte())
                        || (value >= 'A'.code.toByte() && value <= 'Z'.code.toByte()))

                else -> throw IllegalArgumentException("Unknown shape: " + shape)
            }
        }
    }
}
