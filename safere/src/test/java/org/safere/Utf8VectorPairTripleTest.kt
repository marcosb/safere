// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.safere.RarityOracle.AsciiPair
import org.safere.Utf8StartAccelerator.Teddy
import java.nio.charset.StandardCharsets
import java.util.*

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class Utf8VectorPairTripleTest {
    @Test
    fun tripleScannerRequiresThreeDisjointCharacters() {
        Assertions.assertThat(
            Utf8InputScanner.isAsciiTriple(
                intArrayOf(
                    'X'.code,
                    'X'.code,
                    'Z'.code,
                    'Z'.code,
                    '_'.code,
                    '_'.code
                )
            )
        ).isTrue()
        Assertions.assertThat(Utf8InputScanner.isAsciiTriple(intArrayOf('X'.code, 'Z'.code))).isFalse()
        Assertions.assertThat(Utf8InputScanner.isAsciiTriple(intArrayOf('X'.code, 'X'.code, 'Z'.code, 'Z'.code)))
            .isFalse()
    }

    @Test
    fun disjointTripleDispatchHonorsVectorCrossover() {
        val ranges = intArrayOf('X'.code, 'X'.code, 'Z'.code, 'Z'.code, '_'.code, '_'.code)
        if (VectorScanProviders.providerFor(ScanKind.TRIPLE, 64) != null) {
            Assertions.assertThat(Utf8InputScanner.useSpecializedAsciiTriple(ranges, 64)).isTrue()
            Assertions.assertThat(Utf8InputScanner.useSpecializedAsciiTriple(ranges, 10240)).isTrue()
        }
        if (VectorScanProviders.providerFor(ScanKind.CLASS, 10241) != null) {
            Assertions.assertThat(Utf8InputScanner.useSpecializedAsciiTriple(ranges, 10241)).isFalse()
        }
    }

    @Test
    fun unknownProviderDiagnosticIncludesRequestedValue() {
        Assertions.assertThat(VectorScanProviders.unknownProviderMessage("typo")).contains("typo")
    }

    @Test
    fun pairAndTripleScansHaveIndependentVectorCutoffs() {
        if (VectorScanProviders.providerFor(ScanKind.PAIR, 64) != null) {
            Assertions.assertThat<VectorScanProvider?>(VectorScanProviders.providerFor(ScanKind.CLASS, 64)).isNull()
            Assertions.assertThat<VectorScanProvider?>(VectorScanProviders.providerFor(ScanKind.TRIPLE, 64)).isNotNull()
            Assertions.assertThat<VectorScanProvider?>(VectorScanProviders.providerFor(ScanKind.TRIPLE, 10240))
                .isNotNull()
            Assertions.assertThat<VectorScanProvider?>(VectorScanProviders.providerFor(ScanKind.TRIPLE, 10241)).isNull()
            Assertions.assertThat<VectorScanProvider?>(VectorScanProviders.providerFor(ScanKind.CLASS, 1024))
                .isNotNull()
        }
    }

    @Test
    fun everyScanKindHasACoherentWindowRange() {
        val provider = VectorScanProviders.providerFor(ScanKind.PAIR, 64)
        if (provider == null) {
            return  // No Vector provider is installed, so no thresholds are observable.
        }
        for (kind in ScanKind.entries) {
            val minimum = provider.minimumWindowLength(kind)
            val maximum = provider.maximumWindowLength(kind)
            Assertions.assertThat(minimum).isGreaterThan(0)
            Assertions.assertThat(maximum).isGreaterThanOrEqualTo(minimum)
            Assertions.assertThat<VectorScanProvider?>(VectorScanProviders.providerFor(kind, minimum)).isNotNull()
            Assertions.assertThat<VectorScanProvider?>(VectorScanProviders.providerFor(kind, minimum - 1)).isNull()
            if (maximum != Int.MAX_VALUE) {
                Assertions.assertThat<VectorScanProvider?>(VectorScanProviders.providerFor(kind, maximum)).isNotNull()
                Assertions.assertThat<VectorScanProvider?>(VectorScanProviders.providerFor(kind, maximum + 1)).isNull()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 15, 16, 31, 32, 63, 64, 100, 128, 256, 500])
    fun testPairEquivalenceWithSwar(length: Int) {
        Assumptions.assumeTrue(isVectorApiAvailable, "Vector API not available on module path")

        val b0 = 'x'.code.toByte()
        val b1 = 'y'.code.toByte()
        val rnd = Random((1000 + length).toLong())

        for (trial in 0..49) {
            val bytes = ByteArray(length)
            for (i in 0..<length) {
                bytes[i] = ('a'.code + rnd.nextInt(20)).toByte() // 'a'..'t' (no 'x' or 'y')
            }
            val start = if (length == 0) 0 else rnd.nextInt(length)

            // Absent check
            val swarAbsent = ByteSwarScan.indexOfBytePair(bytes, 0, length, b0, b1, start)
            val vectorAbsent = ByteVectorScan.indexOfBytePair(bytes, 0, length, b0, b1, start)
            Assertions.assertThat(vectorAbsent).`as`("absent trial %d len %d", trial, length).isEqualTo(swarAbsent)

            // Present check
            if (length > start) {
                val pos = start + rnd.nextInt(length - start)
                bytes[pos] = if (rnd.nextBoolean()) b0 else b1

                val swarHit = ByteSwarScan.indexOfBytePair(bytes, 0, length, b0, b1, start)
                val vectorHit = ByteVectorScan.indexOfBytePair(bytes, 0, length, b0, b1, start)
                Assertions.assertThat(vectorHit).`as`("hit trial %d len %d", trial, length).isEqualTo(swarHit)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 15, 16, 31, 32, 63, 64, 100, 128, 256, 500])
    fun testTripleEquivalenceWithSwar(length: Int) {
        Assumptions.assumeTrue(isVectorApiAvailable, "Vector API not available on module path")

        val b0 = 'x'.code.toByte()
        val b1 = 'y'.code.toByte()
        val b2 = 'z'.code.toByte()
        val rnd = Random((2000 + length).toLong())

        for (trial in 0..49) {
            val bytes = ByteArray(length)
            for (i in 0..<length) {
                bytes[i] = ('a'.code + rnd.nextInt(20)).toByte() // 'a'..'t' (no 'x', 'y', 'z')
            }
            val start = if (length == 0) 0 else rnd.nextInt(length)

            // Absent check
            val swarAbsent = ByteSwarScan.indexOfByteTriple(bytes, 0, length, b0, b1, b2, start)
            val vectorAbsent = ByteVectorScan.indexOfByteTriple(bytes, 0, length, b0, b1, b2, start)
            Assertions.assertThat(vectorAbsent).`as`("absent trial %d len %d", trial, length).isEqualTo(swarAbsent)

            // Present check
            if (length > start) {
                val pos = start + rnd.nextInt(length - start)
                val choice = rnd.nextInt(3)
                bytes[pos] = if (choice == 0) b0 else if (choice == 1) b1 else b2

                val swarHit = ByteSwarScan.indexOfByteTriple(bytes, 0, length, b0, b1, b2, start)
                val vectorHit = ByteVectorScan.indexOfByteTriple(bytes, 0, length, b0, b1, b2, start)
                Assertions.assertThat(vectorHit).`as`("hit trial %d len %d", trial, length).isEqualTo(swarHit)
            }
        }
    }

    @Test
    fun testScannerPairAndTripleWithLimit() {
        val bytes = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toByteArray(StandardCharsets.UTF_8)
        val scanner = Utf8InputScanner(bytes)

        // 'a' is at 10, 'A' is at 36
        Assertions.assertThat(scanner.indexOfAsciiPair('a'.code, 'A'.code, 0, 10)).isEqualTo(-1)
        Assertions.assertThat(scanner.indexOfAsciiPair('a'.code, 'A'.code, 0, 11)).isEqualTo(10)
        Assertions.assertThat(scanner.indexOfAsciiPair('a'.code, 'A'.code, 11, 36)).isEqualTo(-1)
        Assertions.assertThat(scanner.indexOfAsciiPair('a'.code, 'A'.code, 11, 37)).isEqualTo(36)

        // 'b' at 11, 'm' at 22, 'Z' at 61
        Assertions.assertThat(scanner.indexOfAsciiTriple('b'.code, 'm'.code, 'Z'.code, 0, 11)).isEqualTo(-1)
        Assertions.assertThat(scanner.indexOfAsciiTriple('b'.code, 'm'.code, 'Z'.code, 0, 12)).isEqualTo(11)
        Assertions.assertThat(scanner.indexOfAsciiTriple('b'.code, 'm'.code, 'Z'.code, 12, 22)).isEqualTo(-1)
        Assertions.assertThat(scanner.indexOfAsciiTriple('b'.code, 'm'.code, 'Z'.code, 12, 23)).isEqualTo(22)
        Assertions.assertThat(scanner.indexOfAsciiTriple('b'.code, 'm'.code, 'Z'.code, 23, 61)).isEqualTo(-1)
        Assertions.assertThat(scanner.indexOfAsciiTriple('b'.code, 'm'.code, 'Z'.code, 23, 62)).isEqualTo(61)
    }

    @Test
    fun testStartAcceleratorSelection() {
        val pPairClass = Pattern.compile("[YZ]")
        Assertions.assertThat<Utf8StartAccelerator?>(pPairClass.utf8StartAccelerator())
            .isInstanceOf(Utf8StartAccelerator.CharClass::class.java)

        val pPairAlt = Pattern.compile("Y|Z")
        val expectedPairAltClass: Class<*> =
            if (VectorScanProviders.vectorProviderAvailable())
                Utf8StartAccelerator.MultiLiteral::class.java
            else
                (if (VectorScanProviders.vectorProviderAvailable())
                    Teddy::class.java
                else
                    Utf8StartAccelerator.CharClass::class.java)
        Assertions.assertThat<Utf8StartAccelerator?>(pPairAlt.utf8StartAccelerator()).isInstanceOf(expectedPairAltClass)

        val pConsecutivePair = Pattern.compile("[ab]")
        Assertions.assertThat<Utf8StartAccelerator?>(pConsecutivePair.utf8StartAccelerator())
            .isInstanceOf(Utf8StartAccelerator.CharClass::class.java)

        val pTripleClass = Pattern.compile("[XYZ]")
        Assertions.assertThat<Utf8StartAccelerator?>(pTripleClass.utf8StartAccelerator())
            .isInstanceOf(Utf8StartAccelerator.CharClass::class.java)

        val pTripleAlt = Pattern.compile("X|Y|Z")
        val expectedTripleAltClass: Class<*> =
            if (VectorScanProviders.vectorProviderAvailable())
                Utf8StartAccelerator.MultiLiteral::class.java
            else
                (if (VectorScanProviders.vectorProviderAvailable())
                    Teddy::class.java
                else
                    Utf8StartAccelerator.CharClass::class.java)
        Assertions.assertThat<Utf8StartAccelerator?>(pTripleAlt.utf8StartAccelerator())
            .isInstanceOf(expectedTripleAltClass)
    }

    @Test
    fun testPatternMatchingWithPairAndTriple() {
        val input = "the quick brown fox jumps over the lazy dog".toByteArray(StandardCharsets.UTF_8)
        val utf8 = Utf8Input.trusted(input)

        val pPair = Pattern.compile("[jx]")
        val mPair = pPair.matcher(utf8)
        Assertions.assertThat(mPair.find()).isTrue()
        Assertions.assertThat(mPair.start()).isEqualTo(18) // 'j' in jumps

        val pTriple = Pattern.compile("[xyz]")
        val mTriple = pTriple.matcher(utf8)
        Assertions.assertThat(mTriple.find()).isTrue()
        Assertions.assertThat(mTriple.start()).isEqualTo(18) // 'x' in fox is at 18? No, 'x' is at 18
    }

    @Test
    fun testDfaSelfLoopPairAndTriple() {
        val input = "START some arbitrary payload with delimiters Y and Z".toByteArray(StandardCharsets.UTF_8)
        val utf8 = Utf8Input.trusted(input)

        val pPair = Pattern.compile("(?s)START.*?[YZ]")
        val mPair = pPair.matcher(utf8)
        Assertions.assertThat(mPair.find()).isTrue()
        Assertions.assertThat(mPair.end()).isEqualTo(46) // index after 'Y'

        val pTriple = Pattern.compile("(?s)START.*?[XYZ]")
        val mTriple = pTriple.matcher(utf8)
        Assertions.assertThat(mTriple.find()).isTrue()
        Assertions.assertThat(mTriple.end()).isEqualTo(46)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 15, 16, 31, 32, 63, 64, 100, 128, 256, 500])
    fun testPairIgnoreCaseEquivalenceWithSwar(length: Int) {
        Assumptions.assumeTrue(isVectorApiAvailable, "Vector API not available on module path")

        val prefix = "content-type:"
        val prefixLen = prefix.length
        val pair = RarityOracle.rarestAsciiPairIgnoreCase(prefix, prefixLen)
        Assertions.assertThat<AsciiPair?>(pair).isNotNull()
        val rnd = Random((3000 + length).toLong())

        for (trial in 0..49) {
            val bytes = ByteArray(length)
            for (i in 0..<length) {
                bytes[i] = ('a'.code + rnd.nextInt(20)).toByte()
            }
            val start = if (length == 0) 0 else rnd.nextInt(length)

            // Absent check
            val swarAbsent =
                ByteSwarScan.indexOfPairIgnoreCase(
                    bytes,
                    0,
                    length,
                    prefix,
                    prefixLen,
                    pair!!.offset1,
                    pair.low1,
                    pair.high1,
                    pair.offset2,
                    pair.low2,
                    pair.high2,
                    start
                )
            val vectorAbsent =
                ByteVectorScan.indexOfPairIgnoreCase(
                    bytes,
                    0,
                    length,
                    prefix,
                    prefixLen,
                    pair.offset1,
                    pair.low1,
                    pair.high1,
                    pair.offset2,
                    pair.low2,
                    pair.high2,
                    start
                )
            Assertions.assertThat(vectorAbsent)
                .`as`("pair ignore case absent trial %d len %d", trial, length)
                .isEqualTo(swarAbsent)

            // Present check
            if (length >= start + prefixLen) {
                val pos = start + rnd.nextInt(length - start - prefixLen + 1)
                for (i in 0..<prefixLen) {
                    val c = prefix.get(i)
                    bytes[pos + i] =
                        (if (rnd.nextBoolean()) Ascii.toLowerCase(c) else Ascii.toUpperCase(c)).code.toByte()
                }

                val swarHit =
                    ByteSwarScan.indexOfPairIgnoreCase(
                        bytes,
                        0,
                        length,
                        prefix,
                        prefixLen,
                        pair.offset1,
                        pair.low1,
                        pair.high1,
                        pair.offset2,
                        pair.low2,
                        pair.high2,
                        start
                    )
                val vectorHit =
                    ByteVectorScan.indexOfPairIgnoreCase(
                        bytes,
                        0,
                        length,
                        prefix,
                        prefixLen,
                        pair.offset1,
                        pair.low1,
                        pair.high1,
                        pair.offset2,
                        pair.low2,
                        pair.high2,
                        start
                    )
                Assertions.assertThat(vectorHit)
                    .`as`("pair ignore case hit trial %d len %d", trial, length)
                    .isEqualTo(swarHit)
            }
        }
    }

    @Test
    fun testPatternMatchingWithCaseInsensitivePair() {
        val input = "header: some-value\r\nCoNtEnT-TyPe: application/json\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
        val utf8 = Utf8Input.trusted(input)

        val p = Pattern.compile("(?i)content-type:")
        Assertions.assertThat<Utf8StartAccelerator?>(p.utf8StartAccelerator())
            .isInstanceOf(Utf8StartAccelerator.CaseInsensitiveLiteral::class.java)

        val m = p.matcher(utf8)
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(20)
        Assertions.assertThat(m.end()).isEqualTo(33)
    }

    companion object {
        private val isVectorApiAvailable: Boolean
            get() {
                try {
                    Class.forName("jdk.incubator.vector.ByteVector")
                    return true
                } catch (e: ClassNotFoundException) {
                    return false
                } catch (e: LinkageError) {
                    return false
                }
            }
    }
}
