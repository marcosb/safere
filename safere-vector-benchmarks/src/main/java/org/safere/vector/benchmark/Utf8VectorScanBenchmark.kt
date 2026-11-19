// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.vector.benchmark

import jdk.incubator.vector.VectorOperators.GE

/** Compares current UTF-8 SWAR scans with direct borrowed-array ByteVector prototypes.  */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
class Utf8VectorScanBenchmark {
    /** Data-declared trial in shape/traversal/density/length/offset form.  */
    @Param([])
    var vectorScanTrial: String? = null

    private var storage: ByteArray
    private var offset = 0
    private var length = 0
    private var shape: Shape? = null
    private var scanAll = false
    private var swarScanner: Utf8ScannerBenchmarkAccess? = null
    private var swarProvider: ScanProvider? = null
    private var vectorProvider: ScanProvider? = null
    private var lowerBounds: Array<ByteVector?>
    private var upperBounds: Array<ByteVector?>
    private var safeRePattern: Pattern? = null
    private var safeReInput: Utf8Input? = null
    private var safeReMatcher: Utf8Matcher? = null

    /** Materializes the declared input and verifies that both implementations agree.  */
    @Setup(Level.Trial)
    fun setup() {
        val configuredProvider: String =
            System.getProperty(org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.PROVIDER_PROPERTY, "")
                .trim()
        kotlin.check(!(!configuredProvider.isEmpty() && !configuredProvider.equals("swar"))) { "Low-level benchmarks require the SWAR production provider, not " + configuredProvider }
        val fields: Array<String?> = vectorScanTrial.split("/", -1)
        kotlin.require(fields.size == 5) { "Malformed Vector scan trial: " + vectorScanTrial }
        shape =
            org.safere.vector.benchmark.Utf8VectorScanBenchmark.Shape.valueOf(fields[0].toUpperCase(java.util.Locale.ROOT))
        scanAll =
            when (fields[1]) {
                "first" -> false
                "all" -> true
                else -> throw IllegalArgumentException("Unknown traversal: " + fields[1])
            }
        length = Integer.parseInt(fields[3])
        offset = Integer.parseInt(fields[4])
        val input: ByteArray = VectorScanConfiguration.input(shape.inputProfile, fields[2], length)
        storage =
            ByteArray(offset + input.size + org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.SPECIES.length)
        Arrays.fill(storage, 0x7f.toByte())
        System.arraycopy(input, 0, storage, offset, input.size)
        swarScanner = Utf8ScannerBenchmarkAccess(storage, offset, length, shape.ranges)
        swarProvider = org.safere.vector.benchmark.Utf8VectorScanBenchmark.SwarProvider()
        vectorProvider = org.safere.vector.benchmark.Utf8VectorScanBenchmark.VectorProvider()
        val rangeCount: Int = shape.ranges.size / 2
        lowerBounds = arrayOfNulls<ByteVector>(rangeCount)
        upperBounds = arrayOfNulls<ByteVector>(rangeCount)
        for (range in 0..<rangeCount) {
            lowerBounds[range] = ByteVector.broadcast(
                org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.SPECIES,
                shape.ranges[range * 2].toByte()
            )
            upperBounds[range] = ByteVector.broadcast(
                org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.SPECIES,
                shape.ranges[range * 2 + 1].toByte()
            )
        }
        safeRePattern = Pattern.compile(shape.pattern)
        safeReInput = Utf8Input.trusted(storage, offset, length)
        safeReMatcher = safeRePattern.matcher(safeReInput)
        val swarResult = runSwar()
        val swarProviderResult = runSwarProvider()
        val vectorResult = runVector()
        val vectorProviderResult = runVectorProvider()
        val vectorBoundsResult = runVectorBounds()
        val vectorCursorResult = runVectorCursor()
        if (swarResult != swarProviderResult || swarResult != vectorResult || swarResult != vectorProviderResult || swarResult != vectorBoundsResult || swarResult != vectorCursorResult) {
            throw AssertionError(
                ("Scanner disagreement for "
                        + vectorScanTrial
                        + ": SWAR="
                        + swarResult
                        + ", SWAR provider="
                        + swarProviderResult
                        + ", Vector="
                        + vectorResult
                        + ", Vector provider="
                        + vectorProviderResult
                        + ", Vector bounds="
                        + vectorBoundsResult
                        + ", Vector cursor="
                        + vectorCursorResult)
            )
        }
        val safeReResult = runSafeRe()
        val expectedSafeReResult = if (scanAll) swarResult else (if (swarResult >= 0) 1 else 0)
        if (safeReResult != expectedSafeReResult) {
            throw AssertionError(
                ("SafeRE disagreement for "
                        + vectorScanTrial
                        + ": expected="
                        + expectedSafeReResult
                        + ", actual="
                        + safeReResult)
            )
        }
    }

    /** Runs the current SafeRE SWAR implementation.  */
    @Benchmark
    fun swar(): Int {
        return runSwar()
    }

    /** Runs current SWAR through a stable benchmark-only provider interface.  */
    @Benchmark
    fun swarProvider(): Int {
        return runSwarProvider()
    }

    /** Runs the benchmark-only ByteVector implementation.  */
    @Benchmark
    fun vector(): Int {
        return runVector()
    }

    /** Runs ByteVector through a stable benchmark-only provider interface.  */
    @Benchmark
    fun vectorProvider(): Int {
        return runVectorProvider()
    }

    /** Runs ByteVector with range bounds broadcast once outside the input loop.  */
    @Benchmark
    fun vectorBounds(): Int {
        return runVectorBounds()
    }

    /** Runs a ByteVector implementation that retains and drains each complete match mask.  */
    @Benchmark
    fun vectorCursor(): Int {
        return runVectorCursor()
    }

    /** Runs the corresponding complete SafeRE UTF-8 find operation.  */
    @Benchmark
    fun safeRe(): Int {
        return runSafeRe()
    }

    private fun runSafeRe(): Int {
        if (!scanAll) {
            return if (safeRePattern.find(safeReInput)) 1 else 0
        }
        safeReMatcher.reset()
        var checksum = 0
        while (safeReMatcher.find()) {
            checksum += safeReMatcher.start()
        }
        return checksum
    }

    private fun runSwar(): Int {
        if (!scanAll) {
            return swarScanner.indexOfCodePointClass(0)
        }
        var checksum = 0
        var start = 0
        while (start < length) {
            val found: Int = swarScanner.indexOfCodePointClass(start)
            if (found < 0) {
                break
            }
            checksum += found
            start = found + 1
        }
        return checksum
    }

    private fun runSwarProvider(): Int {
        if (!scanAll) {
            return swarProvider!!.indexOf(0)
        }
        var checksum = 0
        var start = 0
        while (start < length) {
            val found = swarProvider!!.indexOf(start)
            if (found < 0) {
                break
            }
            checksum += found
            start = found + 1
        }
        return checksum
    }

    private fun runVector(): Int {
        if (!scanAll) {
            return indexOfVector(0)
        }
        var checksum = 0
        var start = 0
        while (start < length) {
            val found = indexOfVector(start)
            if (found < 0) {
                break
            }
            checksum += found
            start = found + 1
        }
        return checksum
    }

    private fun runVectorProvider(): Int {
        if (!scanAll) {
            return vectorProvider!!.indexOf(0)
        }
        var checksum = 0
        var start = 0
        while (start < length) {
            val found = vectorProvider!!.indexOf(start)
            if (found < 0) {
                break
            }
            checksum += found
            start = found + 1
        }
        return checksum
    }

    private fun runVectorBounds(): Int {
        if (!scanAll) {
            return indexOfVectorBounds(0)
        }
        var checksum = 0
        var start = 0
        while (start < length) {
            val found = indexOfVectorBounds(start)
            if (found < 0) {
                break
            }
            checksum += found
            start = found + 1
        }
        return checksum
    }

    private fun runVectorCursor(): Int {
        if (!scanAll) {
            return indexOfVector(0)
        }
        var checksum = 0
        var position = 0
        val limit: Int = org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.SPECIES.loopBound(length)
        while (position < limit) {
            val values: ByteVector? = ByteVector.fromArray(
                org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.SPECIES,
                storage,
                offset + position
            )
            val matches: VectorMask<Byte?> = shape.matches(values)
            if (!matches.anyTrue()) {
                position += org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.SPECIES.length
                continue
            }
            var matchBits: Long = matches.toLong()
            while (matchBits != 0L) {
                val lane: Int = Long.numberOfTrailingZeros(matchBits)
                checksum += position + lane
                matchBits = matchBits and matchBits - 1
            }
            position += org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.SPECIES.length
        }
        while (position < length) {
            if (shape!!.matches(storage[offset + position])) {
                checksum += position
            }
            position++
        }
        return checksum
    }

    private fun indexOfVector(start: Int): Int {
        var position: Int = Math.max(0, start)
        val limit: Int =
            position + org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.SPECIES.loopBound(length - position)
        while (position < limit) {
            val values: ByteVector? = ByteVector.fromArray(
                org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.SPECIES,
                storage,
                offset + position
            )
            val matches: VectorMask<Byte?> = shape.matches(values)
            if (matches.anyTrue()) {
                return position + matches.firstTrue()
            }
            position += org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.SPECIES.length
        }
        while (position < length) {
            if (shape!!.matches(storage[offset + position])) {
                return position
            }
            position++
        }
        return -1
    }

    private fun indexOfVectorBounds(start: Int): Int {
        var position: Int = Math.max(0, start)
        val limit: Int =
            position + org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.SPECIES.loopBound(length - position)
        while (position < limit) {
            val values: ByteVector = ByteVector.fromArray(
                org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.SPECIES,
                storage,
                offset + position
            )
            var matches: VectorMask<Byte?> =
                values.compare(GE, lowerBounds[0]).and(values.compare(LE, upperBounds[0]))
            for (range in 1..<lowerBounds.size) {
                matches =
                    matches.or(
                        values.compare(GE, lowerBounds[range]).and(values.compare(LE, upperBounds[range]))
                    )
            }
            if (matches.anyTrue()) {
                return position + matches.firstTrue()
            }
            position += org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.SPECIES.length
        }
        while (position < length) {
            if (shape!!.matches(storage[offset + position])) {
                return position
            }
            position++
        }
        return -1
    }

    private interface ScanProvider {
        fun indexOf(start: Int): Int
    }

    private inner class SwarProvider : ScanProvider {
        @Override
        override fun indexOf(start: Int): Int {
            return swarScanner.indexOfCodePointClass(start)
        }
    }

    private inner class VectorProvider : ScanProvider {
        @Override
        override fun indexOf(start: Int): Int {
            return indexOfVector(start)
        }
    }

    private enum class Shape(
        private val ranges: IntArray,
        private val pattern: String?,
        private val inputProfile: String?
    ) {
        SINGLETON(org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.SINGLETON_RANGES, "x") {
            @Override
            override fun matches(values: ByteVector): VectorMask<Byte?> {
                return values.eq('x'.code.toByte())
            }

            @Override
            override fun matches(value: Byte): Boolean {
                return value == 'x'.code.toByte()
            }
        },
        PAIR(org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.PAIR_RANGES, "[xy]") {
            @Override
            override fun matches(values: ByteVector): VectorMask<Byte?> {
                return values.eq('x'.code.toByte()).or(values.eq('y'.code.toByte()))
            }

            @Override
            override fun matches(value: Byte): Boolean {
                return value == 'x'.code.toByte() || value == 'y'.code.toByte()
            }
        },
        RANGE(org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.RANGE_RANGES, "[0-9]") {
            @Override
            override fun matches(values: ByteVector): VectorMask<Byte?> {
                return values.compare(GE, '0'.code.toByte()).and(values.compare(LE, '9'.code.toByte()))
            }

            @Override
            override fun matches(value: Byte): Boolean {
                return value >= '0'.code.toByte() && value <= '9'.code.toByte()
            }
        },
        RANGE2(org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.RANGE2_RANGES, "[0-9A-Z]", "multi.") {
            @Override
            override fun matches(values: ByteVector): VectorMask<Byte?> {
                val digits: VectorMask<Byte?> =
                    values.compare(GE, '0'.code.toByte()).and(values.compare(LE, '9'.code.toByte()))
                val upper: VectorMask<Byte?>? =
                    values.compare(GE, 'A'.code.toByte()).and(values.compare(LE, 'Z'.code.toByte()))
                return digits.or(upper)
            }

            @Override
            override fun matches(value: Byte): Boolean {
                return (value >= '0'.code.toByte() && value <= '9'.code.toByte()) || (value >= 'A'.code.toByte() && value <= 'Z'.code.toByte())
            }
        },
        ALNUM3(org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.ALNUM3_RANGES, "[0-9A-Za-z]", "multi.") {
            @Override
            override fun matches(values: ByteVector): VectorMask<Byte?> {
                val digits: VectorMask<Byte?> =
                    values.compare(GE, '0'.code.toByte()).and(values.compare(LE, '9'.code.toByte()))
                val upper: VectorMask<Byte?>? =
                    values.compare(GE, 'A'.code.toByte()).and(values.compare(LE, 'Z'.code.toByte()))
                val lower: VectorMask<Byte?>? =
                    values.compare(GE, 'a'.code.toByte()).and(values.compare(LE, 'z'.code.toByte()))
                return digits.or(upper).or(lower)
            }

            @Override
            override fun matches(value: Byte): Boolean {
                return (value >= '0'.code.toByte() && value <= '9'.code.toByte())
                        || (value >= 'A'.code.toByte() && value <= 'Z'.code.toByte())
                        || (value >= 'a'.code.toByte() && value <= 'z'.code.toByte())
            }
        },
        MIXED4(org.safere.vector.benchmark.Utf8VectorScanBenchmark.Companion.MIXED4_RANGES, "[!#0-9A-Z]", "multi.") {
            @Override
            override fun matches(values: ByteVector): VectorMask<Byte?> {
                val singletons: VectorMask<Byte?> = values.eq('!'.code.toByte()).or(values.eq('#'.code.toByte()))
                val digits: VectorMask<Byte?>? =
                    values.compare(GE, '0'.code.toByte()).and(values.compare(LE, '9'.code.toByte()))
                val upper: VectorMask<Byte?>? =
                    values.compare(GE, 'A'.code.toByte()).and(values.compare(LE, 'Z'.code.toByte()))
                return singletons.or(digits).or(upper)
            }

            @Override
            override fun matches(value: Byte): Boolean {
                return value == '!'.code.toByte() || value == '#'.code.toByte() || (value >= '0'.code.toByte() && value <= '9'.code.toByte())
                        || (value >= 'A'.code.toByte() && value <= 'Z'.code.toByte())
            }
        };

        constructor(ranges: IntArray, pattern: String?) : this(ranges, pattern, "")

        abstract fun matches(values: ByteVector?): VectorMask<Byte?>

        abstract fun matches(value: Byte): Boolean
    }

    companion object {
        private val PROVIDER_PROPERTY = "org.safere.experimental.vectorScanProvider"
        private val SPECIES: VectorSpecies<Byte?> = ByteVector.SPECIES_PREFERRED
        private val SINGLETON_RANGES = intArrayOf('x'.code, 'x'.code)
        private val PAIR_RANGES = intArrayOf('x'.code, 'x'.code, 'y'.code, 'y'.code)
        private val RANGE_RANGES = intArrayOf('0'.code, '9'.code)
        private val RANGE2_RANGES = intArrayOf('0'.code, '9'.code, 'A'.code, 'Z'.code)
        private val ALNUM3_RANGES = intArrayOf('0'.code, '9'.code, 'A'.code, 'Z'.code, 'a'.code, 'z'.code)
        private val MIXED4_RANGES =
            intArrayOf('!'.code, '!'.code, '#'.code, '#'.code, '0'.code, '9'.code, 'A'.code, 'Z'.code)
    }
}
