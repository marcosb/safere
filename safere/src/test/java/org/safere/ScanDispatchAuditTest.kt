// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.safere.Utf8StartAccelerator.Teddy
import java.nio.charset.StandardCharsets
import java.util.function.IntSupplier

/**
 * Asserts that each vector dispatch site sizes its crossover decision by the window it is about to
 * scan rather than by the whole input, per Invariant 4.41.
 * 
 * 
 * Every test here fails against the code as it stood before the window-sized dispatch fix: the
 * defect leaves the answer correct and changes only which kernel runs, so nothing but a dispatch
 * audit can see it.
 */
@DisabledForCrosscheck("implementation test observes package-private dispatch internals")
internal class ScanDispatchAuditTest {
    @Test
    fun teddyDispatchSizedByWindowNotInput() {
        val provider: VectorScanProvider? = installedProvider()
        if (provider == null) {
            return
        }
        val model = TeddyModel.compileForSelectedProvider(LITERALS)
        Assertions.assertThat<TeddyModel?>(model).isNotNull()
        val teddy = Teddy(model)
        val scanner = Utf8InputScanner(LONG_INPUT)
        val window = provider.minimumWindowLength(ScanKind.TEDDY) - 1
        val fromIndex: Int = LONG_INPUT.length - window

        val events =
            ScanAudit.captureForTesting(
                Runnable { Assertions.assertThat(teddy.findCandidate(scanner, fromIndex)).isEqualTo(fromIndex) })

        Assertions.assertThat<ScanEvent?>(events)
            .containsExactly(
                consulted(ScanKind.TEDDY, window),
                ScanEvent(ScanKind.TEDDY, ScanDirection.FORWARD, window, ScanPath.DECLINED)
            )
    }

    @Test
    fun multiLiteralDispatchSizedByWindow() {
        val provider: VectorScanProvider? = installedProvider()
        if (provider == null) {
            return
        }
        val info = MultiLiteralInfo.create(LITERALS)
        Assertions.assertThat<MultiLiteralInfo?>(info).isNotNull()
        val multiLiteral =
            Utf8StartAccelerator.MultiLiteral(
                info, TeddyModel.compileForSelectedProvider(LITERALS)
            )
        val scanner = Utf8InputScanner(LONG_INPUT)
        val window = provider.minimumWindowLength(ScanKind.MULTI_LITERAL) - 1
        val fromIndex: Int = LONG_INPUT.length - window

        val events =
            ScanAudit.captureForTesting(
                Runnable { Assertions.assertThat(multiLiteral.findCandidate(scanner, fromIndex)).isNegative() })

        Assertions.assertThat<ScanEvent?>(events)
            .containsExactly(
                consulted(ScanKind.MULTI_LITERAL, window),
                ScanEvent(ScanKind.MULTI_LITERAL, ScanDirection.FORWARD, window, ScanPath.SCALAR)
            )
    }

    @Test
    fun narrowClassScanStillReachesSwar() {
        val provider: VectorScanProvider? = installedProvider()
        if (provider == null) {
            return
        }
        // A window too narrow for the Vector class kernel but wide enough for the SWAR one. Declining
        // the Vector tier here must not cost the SWAR tier as well.
        val window = provider.minimumWindowLength(ScanKind.CLASS) - 1
        Assertions.assertThat(window).isGreaterThanOrEqualTo(64)
        val ranges = intArrayOf('0'.code, '9'.code, 'A'.code, 'F'.code, 'a'.code, 'f'.code)
        var low: Long = 0
        var high: Long = 0
        var i = 0
        while (i < ranges.size) {
            for (c in ranges[i]..ranges[i + 1]) {
                if (c < java.lang.Long.SIZE) {
                    low = low or (1L shl c)
                } else {
                    high = high or (1L shl (c - java.lang.Long.SIZE))
                }
            }
            i += 2
        }
        val bitmap0 = low
        val bitmap1 = high
        val scanner = Utf8InputScanner(LONG_INPUT)

        val events =
            ScanAudit.captureForTesting(
                Runnable {
                    Assertions.assertThat(scanner.indexOfCodePointClass(ranges, bitmap0, bitmap1, 0, window))
                        .isEqualTo(-1)
                })

        Assertions.assertThat<ScanEvent?>(events)
            .containsExactly(
                consulted(ScanKind.CLASS, window),
                ScanEvent(ScanKind.CLASS, ScanDirection.FORWARD, window, ScanPath.SWAR)
            )
    }

    @Test
    fun singleByteClassDoesNotRecordRoutingProbeAsDispatch() {
        val provider: VectorScanProvider? = installedProvider()
        if (provider == null) {
            return
        }
        val window = provider.minimumWindowLength(ScanKind.CLASS)
        val scanner = Utf8InputScanner(LONG_INPUT)

        val events: MutableList<ScanEvent?> =
            captureScan(
                IntSupplier {
                    scanner.indexOfCodePointClass(
                        intArrayOf('z'.code, 'z'.code), 0, 1L shl ('z'.code - java.lang.Long.SIZE), 0, window
                    )
                })

        Assertions.assertThat<ScanEvent?>(events).isEmpty()
    }

    @Test
    fun singletonTripleUsesEqualityScanWhenClassVectorIsUnavailable() {
        var window = 1024
        val classProvider =
            VectorScanProviders.providerForPolicy(ScanKind.CLASS, window)
        if (classProvider != null) {
            // Under Vector tests, use a window below the class kernel's admission threshold.
            window = classProvider.minimumWindowLength(ScanKind.CLASS) - 1
        }
        val scanWindow = window
        val scanner = Utf8InputScanner(LONG_INPUT)
        val bitmap = (1L shl ('X'.code - 64)) or (1L shl ('Z'.code - 64)) or (1L shl ('_'.code - 64))
        val events: MutableList<ScanEvent?> =
            captureScan(
                IntSupplier {
                    scanner.indexOfCodePointClass(
                        intArrayOf('X'.code, 'X'.code, 'Z'.code, 'Z'.code, '_'.code, '_'.code), 0, bitmap, 0, scanWindow
                    )
                })
        val path =
            if (VectorScanProviders.providerForPolicy(ScanKind.TRIPLE, window) == null)
                ScanPath.SWAR
            else
                ScanPath.VECTOR
        Assertions.assertThat<ScanEvent?>(events)
            .containsExactly(
                consulted(ScanKind.TRIPLE, window),
                ScanEvent(ScanKind.TRIPLE, ScanDirection.FORWARD, window, path)
            )
    }

    @Test
    fun tripleClassRecordsOnlyItsActualTripleScan() {
        val provider: VectorScanProvider? = installedProvider()
        if (provider == null) {
            return
        }
        val window = provider.minimumWindowLength(ScanKind.CLASS)
        val scanner = Utf8InputScanner(LONG_INPUT)

        val events: MutableList<ScanEvent?> =
            captureScan(
                IntSupplier {
                    scanner.indexOfCodePointClass(
                        intArrayOf('a'.code, 'a'.code, 'b'.code, 'b'.code, 'c'.code, 'c'.code), 0, 0, 0, window
                    )
                })

        Assertions.assertThat<ScanEvent?>(events)
            .containsExactly(
                consulted(ScanKind.TRIPLE, window),
                ScanEvent(ScanKind.TRIPLE, ScanDirection.FORWARD, window, ScanPath.VECTOR)
            )
    }

    @Test
    fun thresholdBoundaryRecordsExpectedPath() {
        val provider: VectorScanProvider? = installedProvider()
        if (provider == null) {
            return
        }
        val minimum = provider.minimumWindowLength(ScanKind.BYTE)
        val scanner = Utf8InputScanner(LONG_INPUT)

        Assertions.assertThat<ScanEvent?>(captureScan(IntSupplier { scanner.indexOfAscii('z'.code, 0, minimum - 1) }))
            .containsExactly(
                consulted(ScanKind.BYTE, minimum - 1),
                ScanEvent(ScanKind.BYTE, ScanDirection.FORWARD, minimum - 1, ScanPath.SWAR)
            )
        Assertions.assertThat<ScanEvent?>(captureScan(IntSupplier { scanner.indexOfAscii('z'.code, 0, minimum) }))
            .containsExactly(
                consulted(ScanKind.BYTE, minimum),
                ScanEvent(ScanKind.BYTE, ScanDirection.FORWARD, minimum, ScanPath.VECTOR)
            )
    }

    @Test
    fun allDispatchLaddersSatisfyPairingAcrossNarrowAndWideWindows() {
        val provider: VectorScanProvider? = installedProvider()
        if (provider == null) {
            return
        }
        val scanner = Utf8InputScanner(LONG_INPUT)

        // 1. BYTE
        val byteMin = provider.minimumWindowLength(ScanKind.BYTE)
        Assertions.assertThat<ScanEvent?>(captureScan(IntSupplier { scanner.indexOfAscii('z'.code, 0, byteMin - 1) }))
            .containsExactly(
                consulted(ScanKind.BYTE, byteMin - 1),
                ScanEvent(ScanKind.BYTE, ScanDirection.FORWARD, byteMin - 1, ScanPath.SWAR)
            )
        Assertions.assertThat<ScanEvent?>(captureScan(IntSupplier { scanner.indexOfAscii('z'.code, 0, byteMin) }))
            .containsExactly(
                consulted(ScanKind.BYTE, byteMin),
                ScanEvent(ScanKind.BYTE, ScanDirection.FORWARD, byteMin, ScanPath.VECTOR)
            )

        // 2. PAIR
        val pairMin = provider.minimumWindowLength(ScanKind.PAIR)
        Assertions.assertThat<ScanEvent?>(captureScan(IntSupplier {
            scanner.indexOfAsciiPair(
                'y'.code,
                'z'.code,
                0,
                pairMin - 1
            )
        }))
            .containsExactly(
                consulted(ScanKind.PAIR, pairMin - 1),
                ScanEvent(ScanKind.PAIR, ScanDirection.FORWARD, pairMin - 1, ScanPath.SWAR)
            )
        Assertions.assertThat<ScanEvent?>(captureScan(IntSupplier {
            scanner.indexOfAsciiPair(
                'y'.code,
                'z'.code,
                0,
                pairMin
            )
        }))
            .containsExactly(
                consulted(ScanKind.PAIR, pairMin),
                ScanEvent(ScanKind.PAIR, ScanDirection.FORWARD, pairMin, ScanPath.VECTOR)
            )

        // 3. TRIPLE
        val tripleMin = provider.minimumWindowLength(ScanKind.TRIPLE)
        Assertions.assertThat<ScanEvent?>(captureScan(IntSupplier {
            scanner.indexOfAsciiTriple(
                'u'.code,
                'v'.code,
                'w'.code,
                0,
                tripleMin - 1
            )
        }))
            .containsExactly(
                consulted(ScanKind.TRIPLE, tripleMin - 1),
                ScanEvent(ScanKind.TRIPLE, ScanDirection.FORWARD, tripleMin - 1, ScanPath.SWAR)
            )
        Assertions.assertThat<ScanEvent?>(captureScan(IntSupplier {
            scanner.indexOfAsciiTriple(
                'u'.code,
                'v'.code,
                'w'.code,
                0,
                tripleMin
            )
        }))
            .containsExactly(
                consulted(ScanKind.TRIPLE, tripleMin),
                ScanEvent(ScanKind.TRIPLE, ScanDirection.FORWARD, tripleMin, ScanPath.VECTOR)
            )

        // 4. CLASS
        val classMin = provider.minimumWindowLength(ScanKind.CLASS)
        val ranges = intArrayOf('0'.code, '9'.code)
        val bitmap0 = 0x03FF000000000000L
        Assertions.assertThat<ScanEvent?>(
            captureScan(IntSupplier { scanner.indexOfCodePointClass(ranges, bitmap0, 0, 0, classMin - 1) })
        )
            .containsExactly(
                consulted(ScanKind.CLASS, classMin - 1),
                ScanEvent(ScanKind.CLASS, ScanDirection.FORWARD, classMin - 1, ScanPath.SWAR)
            )
        Assertions.assertThat<ScanEvent?>(captureScan(IntSupplier {
            scanner.indexOfCodePointClass(
                ranges,
                bitmap0,
                0,
                0,
                classMin
            )
        }))
            .containsExactly(
                consulted(ScanKind.CLASS, classMin),
                ScanEvent(ScanKind.CLASS, ScanDirection.FORWARD, classMin, ScanPath.VECTOR)
            )

        // 5. IGNORE_CASE (single-anchor and pair-anchor ladders)
        val ignoreCaseMin = provider.minimumWindowLength(ScanKind.IGNORE_CASE)
        val failure = intArrayOf(0, 0, 0)
        val fromNarrow: Int = LONG_INPUT.length - (ignoreCaseMin - 1)
        Assertions.assertThat<ScanEvent?>(
            captureScan(
                IntSupplier {
                    scanner.indexOfIgnoreCase(
                        "abc", failure, 0, 'a'.code.toByte(), 'A'.code.toByte(), fromNarrow
                    )
                })
        )
            .containsExactly(
                consulted(ScanKind.IGNORE_CASE, ignoreCaseMin - 1),
                ScanEvent(
                    ScanKind.IGNORE_CASE, ScanDirection.FORWARD, ignoreCaseMin - 1, ScanPath.SWAR
                )
            )
        Assertions.assertThat<ScanEvent?>(
            captureScan(
                IntSupplier {
                    scanner.indexOfPairIgnoreCase(
                        "abc",
                        failure,
                        0,
                        'a'.code.toByte(),
                        'A'.code.toByte(),
                        1,
                        'b'.code.toByte(),
                        'B'.code.toByte(),
                        fromNarrow
                    )
                })
        )
            .containsExactly(
                consulted(ScanKind.IGNORE_CASE, ignoreCaseMin - 1),
                ScanEvent(
                    ScanKind.IGNORE_CASE, ScanDirection.FORWARD, ignoreCaseMin - 1, ScanPath.SWAR
                )
            )
        val fromWide: Int = LONG_INPUT.length - ignoreCaseMin
        Assertions.assertThat<ScanEvent?>(
            captureScan(
                IntSupplier {
                    scanner.indexOfIgnoreCase(
                        "abc",
                        failure,
                        0,
                        'a'.code.toByte(),
                        'A'.code.toByte(),
                        fromWide
                    )
                })
        )
            .containsExactly(
                consulted(ScanKind.IGNORE_CASE, ignoreCaseMin),
                ScanEvent(
                    ScanKind.IGNORE_CASE, ScanDirection.FORWARD, ignoreCaseMin, ScanPath.VECTOR
                )
            )
        Assertions.assertThat<ScanEvent?>(
            captureScan(
                IntSupplier {
                    scanner.indexOfPairIgnoreCase(
                        "abc",
                        failure,
                        0,
                        'a'.code.toByte(),
                        'A'.code.toByte(),
                        1,
                        'b'.code.toByte(),
                        'B'.code.toByte(),
                        fromWide
                    )
                })
        )
            .containsExactly(
                consulted(ScanKind.IGNORE_CASE, ignoreCaseMin),
                ScanEvent(
                    ScanKind.IGNORE_CASE, ScanDirection.FORWARD, ignoreCaseMin, ScanPath.VECTOR
                )
            )

        // 6. TEDDY
        val teddyModel = TeddyModel.compileForSelectedProvider(LITERALS)
        Assertions.assertThat<TeddyModel?>(teddyModel).isNotNull()
        val teddy = Teddy(teddyModel)
        val teddyMin = provider.minimumWindowLength(ScanKind.TEDDY)
        val teddyFromNarrow: Int = LONG_INPUT.length - (teddyMin - 1)
        val teddyNarrowEvents =
            ScanAudit.captureForTesting(
                Runnable {
                    Assertions.assertThat(teddy.findCandidate(scanner, teddyFromNarrow))
                        .isEqualTo(teddyFromNarrow)
                })
        Assertions.assertThat<ScanEvent?>(teddyNarrowEvents)
            .containsExactly(
                consulted(ScanKind.TEDDY, teddyMin - 1),
                ScanEvent(ScanKind.TEDDY, ScanDirection.FORWARD, teddyMin - 1, ScanPath.DECLINED)
            )
        val teddyFromWide: Int = LONG_INPUT.length - teddyMin
        Assertions.assertThat<ScanEvent?>(captureScan(IntSupplier { teddy.findCandidate(scanner, teddyFromWide) }))
            .containsExactly(
                consulted(ScanKind.TEDDY, teddyMin),
                ScanEvent(ScanKind.TEDDY, ScanDirection.FORWARD, teddyMin, ScanPath.VECTOR)
            )

        // 7. MULTI_LITERAL
        val multiLiteralInfo = MultiLiteralInfo.create(LITERALS)
        Assertions.assertThat<MultiLiteralInfo?>(multiLiteralInfo).isNotNull()
        val multiLiteral =
            Utf8StartAccelerator.MultiLiteral(multiLiteralInfo, teddyModel)
        val multiLiteralMin = provider.minimumWindowLength(ScanKind.MULTI_LITERAL)
        val mlFromNarrow: Int = LONG_INPUT.length - (multiLiteralMin - 1)
        Assertions.assertThat<ScanEvent?>(captureScan(IntSupplier {
            multiLiteral.findCandidate(
                scanner,
                mlFromNarrow
            )
        }))
            .containsExactly(
                consulted(ScanKind.MULTI_LITERAL, multiLiteralMin - 1),
                ScanEvent(
                    ScanKind.MULTI_LITERAL,
                    ScanDirection.FORWARD,
                    multiLiteralMin - 1,
                    ScanPath.SCALAR
                )
            )
        val mlFromWide: Int = LONG_INPUT.length - multiLiteralMin
        Assertions.assertThat<ScanEvent?>(captureScan(IntSupplier { multiLiteral.findCandidate(scanner, mlFromWide) }))
            .containsExactly(
                consulted(ScanKind.MULTI_LITERAL, multiLiteralMin),
                ScanEvent(
                    ScanKind.MULTI_LITERAL, ScanDirection.FORWARD, multiLiteralMin, ScanPath.VECTOR
                )
            )
    }

    @Test
    fun captureRejectsUnpairedConsultation() {
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                ScanAudit.captureForTesting(Runnable {
                    ScanAudit.recordConsultation(
                        ScanKind.BYTE,
                        64
                    )
                })
            })
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("odd number of events")
    }

    @Test
    fun captureRejectsPathWithoutConsultation() {
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                ScanAudit.captureForTesting(
                    Runnable {
                        ScanAudit.record(ScanKind.BYTE, ScanDirection.FORWARD, 64, ScanPath.SWAR)
                        ScanAudit.record(ScanKind.BYTE, ScanDirection.FORWARD, 64, ScanPath.SWAR)
                    })
            })
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("expected ScanPath.CONSULTED")
    }

    @Test
    fun captureRejectsConsecutiveConsultations() {
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                ScanAudit.captureForTesting(
                    Runnable {
                        ScanAudit.recordConsultation(ScanKind.BYTE, 64)
                        ScanAudit.recordConsultation(ScanKind.BYTE, 64)
                    })
            })
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("consecutive CONSULTED event")
    }

    @Test
    fun captureRejectsKindMismatchBetweenConsultationAndPath() {
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                ScanAudit.captureForTesting(
                    Runnable {
                        ScanAudit.recordConsultation(ScanKind.BYTE, 64)
                        ScanAudit.record(ScanKind.PAIR, ScanDirection.FORWARD, 64, ScanPath.SWAR)
                    })
            })
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("does not match path")
    }

    @Test
    fun captureRejectsWindowLengthMismatchBetweenConsultationAndPath() {
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                ScanAudit.captureForTesting(
                    Runnable {
                        ScanAudit.recordConsultation(ScanKind.BYTE, 64)
                        ScanAudit.record(ScanKind.BYTE, ScanDirection.FORWARD, 128, ScanPath.SWAR)
                    })
            })
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("does not match path")
    }

    /**
     * The work-counter branches replace each kernel with a scalar counting loop and return before
     * reaching any dispatch ladder, so a capture taken there would report that nothing but scalar
     * code ran. Refusing beats handing back a plausible-looking log.
     */
    @Test
    @Tag("work-counter")
    fun captureRefusesToRunUnderWorkCounters() {
        Assertions.assertThatThrownBy(ThrowingCallable { ScanAudit.captureForTesting(Runnable {}) })
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("work-counters")
    }

    companion object {
        /** A haystack long enough that gating on its length would admit every kernel.  */
        private val LONG_INPUT = "x".repeat(100 * 1024).toByteArray(StandardCharsets.UTF_8)

        private val LITERALS = arrayOf<String?>("INFO", "WARN", "ERROR")

        /**
         * Returns an installed provider, or `null` when the build has none.
         * 
         * 
         * Without a provider every threshold question answers the same way, so there is no dispatch
         * decision left to get wrong; these tests only have content under `-Pvector-tests`.
         */
        private fun installedProvider(): VectorScanProvider? {
            return VectorScanProviders.providerFor(ScanKind.PAIR, 64)
        }

        private fun consulted(kind: ScanKind?, window: Int): ScanEvent {
            return ScanEvent(kind, ScanDirection.UNSPECIFIED, window, ScanPath.CONSULTED)
        }

        /** Captures the dispatch events of one scan, asserting it finds nothing.  */
        private fun captureScan(scan: IntSupplier): MutableList<ScanEvent?> {
            return ScanAudit.captureForTesting(Runnable { Assertions.assertThat(scan.asInt).isEqualTo(-1) })
        }
    }
}
