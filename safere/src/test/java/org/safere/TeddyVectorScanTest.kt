// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.math.max

@DisabledForCrosscheck("implementation test invokes the experimental Teddy vector kernel directly")
internal class TeddyVectorScanTest {
    @Test
    fun alternatingStageCountsPreserveEarliestMatch() {
        Assumptions.assumeTrue(VectorScanProviders.vectorProviderAvailable(), "Vector provider is not enabled")
        val models: Array<TeddyModel?> = models()
        for (round in 0..7) {
            for (stage in intArrayOf(2, 1, 0, 1, 2, 0)) {
                for (text in arrayOf<String>(
                    "x".repeat(4096),
                    "a b c ".repeat(512) + "abc pqr",
                    "x".repeat(2048) + "pqr" + "x".repeat(2048) + "abc"
                )) {
                    val bytes = text.toByteArray(StandardCharsets.US_ASCII)
                    for (start in intArrayOf(0, 1, 31, 64, 2049, bytes.size)) {
                        Assertions.assertThat(
                            TeddyVectorScan.indexOfTeddyUtf8(bytes, 0, bytes.size, models[stage], start)
                        )
                            .`as`("stage %s, round %s, start %s", stage + 1, round, start)
                            .isEqualTo(Companion.expected(text, LITERALS[stage], start))
                    }
                }
            }
        }
    }

    @Test
    fun stageCountsRespectWindowAndVectorTailBoundaries() {
        Assumptions.assumeTrue(VectorScanProviders.vectorProviderAvailable(), "Vector provider is not enabled")
        val models: Array<TeddyModel?> = models()
        for (length in intArrayOf(15, 16, 17, 31, 32, 33, 63, 64, 65, 1024, 4096)) {
            for (stage in intArrayOf(2, 0, 1)) {
                for (position in intArrayOf(0, 1, 14, 15, 16, 30, 31, 32, 62, 63, 64, length - 3)) {
                    if (position < 0 || position + 3 > length) {
                        continue
                    }
                    val bytes = ByteArray(length + 14)
                    Arrays.fill(bytes, 'x'.code.toByte())
                    val literal = "abc".toByteArray(StandardCharsets.US_ASCII)
                    System.arraycopy(literal, 0, bytes, 0, literal.size)
                    System.arraycopy(literal, 0, bytes, 7 + position, literal.size)
                    System.arraycopy(literal, 0, bytes, 7 + length, literal.size)
                    val window = String(bytes, 7, length, StandardCharsets.US_ASCII)
                    for (start in intArrayOf(-1, 0, position, position + 1, length)) {
                        Assertions.assertThat(TeddyVectorScan.indexOfTeddyUtf8(bytes, 7, length, models[stage], start))
                            .`as`(
                                "stage %s, length %s, position %s, start %s",
                                stage + 1, length, position, start
                            )
                            .isEqualTo(Companion.expected(window, LITERALS[stage], start))
                    }
                }
            }
        }
    }

    companion object {
        private val LITERALS = arrayOf<Array<String?>?>(
            arrayOf<String?>("a", "pqr"),
            arrayOf<String?>("ab", "pqr"),
            arrayOf<String?>("abc", "pqr")
        )

        private fun models(): Array<TeddyModel?> {
            val models = arrayOfNulls<TeddyModel>(LITERALS.size)
            for (i in models.indices) {
                models[i] = TeddyModel.compile(LITERALS[i], 64)
                Assertions.assertThat<TeddyModel?>(models[i]).isNotNull()
                Assertions.assertThat(models[i]!!.is2Byte()).isEqualTo(i >= 1)
                Assertions.assertThat(models[i]!!.is3Byte()).isEqualTo(i >= 2)
            }
            return models
        }

        private fun expected(text: String, literals: Array<String>, start: Int): Int {
            var first = -1
            for (literal in literals) {
                val found = text.indexOfBounded(literal, max(0, start))
                if (found >= 0 && (first < 0 || found < first)) {
                    first = found
                }
            }
            return first
        }
    }
}
