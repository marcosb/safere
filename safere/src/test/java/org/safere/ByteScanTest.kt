// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.stream.IntStream
import kotlin.math.min

@DisabledForCrosscheck("package-private byte scanner tests exercise SafeRE internals")
internal class ByteScanTest {
    @ParameterizedTest
    @CsvSource(
        "false, 0, false",
        "false, 3, false",
        "false, 0, true",
        "false, 3, true",
        "true, 0, false",
        "true, 3, false",
        "true, 0, true",
        "true, 3, true"
    )
    fun caseInsensitivePairSearchRespectsFullPrefixBounds(
        vector: Boolean, offset: Int, padded: Boolean
    ) {
        if (vector) {
            Assumptions.assumeTrue(isVectorApiAvailable, "Vector API not available on module path")
        }
        // Exercise physical array ends and logical slice ends with matching bytes beyond the slice.
        for (prefixLength in intArrayOf(8, 9, 10, 16, 24, 65, 80)) {
            val prefix = "abcdefghijklmnopqrstuvwxyz".repeat(4).substring(0, prefixLength)
            val upper = prefix.uppercase().toByteArray(StandardCharsets.UTF_8)
            for (anchorOffset in intArrayOf(1, prefixLength / 2, prefixLength - 1)) {
                // Cross word and vector boundaries, including loads with incomplete prefix candidates.
                for (position in IntStream.concat(
                    IntStream.range(0, 24), IntStream.of(31, 32, 63, 64, 65, 127, 128, 129)
                )
                    .toArray()) {
                    for (available in 0..prefixLength + 1) {
                        val length = position + available
                        val bytes = ByteArray(offset + length + (if (padded) prefixLength else 0))
                        Arrays.fill(bytes, '.'.code.toByte())
                        System.arraycopy(
                            upper,
                            0,
                            bytes,
                            offset + position,
                            min(prefixLength, bytes.size - offset - position)
                        )
                        for (start in intArrayOf(0, position, position + 1)) {
                            val expected = if (available >= prefixLength && start <= position) position else -1
                            val actual =
                                if (vector)
                                    ByteVectorScan.indexOfPairIgnoreCase(
                                        bytes,
                                        offset,
                                        length,
                                        prefix,
                                        prefixLength,
                                        0,
                                        'a'.code.toByte(),
                                        'A'.code.toByte(),
                                        anchorOffset,
                                        prefix.get(anchorOffset).code.toByte(),
                                        upper[anchorOffset],
                                        start
                                    )
                                else
                                    ByteSwarScan.indexOfPairIgnoreCase(
                                        bytes,
                                        offset,
                                        length,
                                        prefix,
                                        prefixLength,
                                        0,
                                        'a'.code.toByte(),
                                        'A'.code.toByte(),
                                        anchorOffset,
                                        prefix.get(anchorOffset).code.toByte(),
                                        upper[anchorOffset],
                                        start
                                    )
                            Assertions.assertThat(actual)
                                .`as`(
                                    "prefix %s, anchor %s, position %s, available %s, start %s",
                                    prefixLength, anchorOffset, position, available, start
                                )
                                .isEqualTo(expected)
                        }
                    }
                }
            }
        }
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
