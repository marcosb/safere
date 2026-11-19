// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.math.max

@DisabledForCrosscheck("package-private input scanner tests exercise SafeRE internals")
internal class InputScannerTest {
    @ParameterizedTest
    @ValueSource(strings = ["Y", "YZ", "XZ_", "\u0000?\u007F"])
    fun smallAsciiClassSearchReturnsEarliestMemberAcrossStringEncodings(members: String) {
        val bitmap = AsciiBitmap.Builder()
        for (i in 0..<members.length) {
            bitmap.add(members.get(i).code)
        }
        val info = CharClassScanInfo.fromAsciiBitmap(bitmap.build())
        val random = Random(20261001)
        for (wide in booleanArrayOf(false, true)) {
            val chars = CharArray(8197)
            for (i in chars.indices) {
                chars[i] = random.nextInt(if (wide) 65536 else 256).toChar()
            }
            for (position in intArrayOf(0, 15, 16, 31, 127, 4095, 4096, 8192, 8196)) {
                chars[position] = members.get(random.nextInt(members.length))
            }
            val text = String(chars)
            val scanner = StringInputScanner(text)
            for (start in intArrayOf(-1, 0, 1, 15, 16, 32, 128, 4095, 4096, 8193, 8197, 8198)) {
                var expected = -1
                for (i in max(0, start)..<text.length) {
                    if (members.indexOf(text.get(i)) >= 0) {
                        expected = i
                        break
                    }
                }
                Assertions.assertThat(scanner.indexOfCharClass(info, start))
                    .`as`("members %s, wide %s, start %s", members, wide, start)
                    .isEqualTo(expected)
            }
        }
    }

    @Test
    fun smallAsciiClassSearchSkipsUnicodeAndUnpairedSurrogates() {
        val bitmap = AsciiBitmap.Builder()
        bitmap.add('X'.code)
        bitmap.add('Z'.code)
        bitmap.add('_'.code)
        val info = CharClassScanInfo.fromAsciiBitmap(bitmap.build())
        val noise = "é中😀\uD800a\uDC00".repeat(1500)
        val scanner = StringInputScanner(noise + "_ZX")

        Assertions.assertThat(scanner.indexOfCharClass(info, 0)).isEqualTo(noise.length)
        Assertions.assertThat(scanner.indexOfCharClass(info, noise.length + 1)).isEqualTo(noise.length + 1)
        Assertions.assertThat(StringInputScanner(noise).indexOfCharClass(info, 0)).isEqualTo(-1)
    }

    @Test
    fun stringScannerReadsSingleCharCodePointsWithoutFullDecoding() {
        val scanner = StringInputScanner("Aé😀\uD800Z")

        Assertions.assertThat(scanner.singleUnitCodePointAt(0)).isEqualTo('A'.code)
        Assertions.assertThat(scanner.singleUnitCodePointAt(1)).isEqualTo('é'.code)
        Assertions.assertThat(scanner.singleUnitCodePointAt(2)).isEqualTo(-1)
        Assertions.assertThat(scanner.singleUnitCodePointAt(4)).isEqualTo('\uD800'.code)
        Assertions.assertThat(scanner.singleUnitCodePointBefore(6)).isEqualTo('Z'.code)
        Assertions.assertThat(scanner.singleUnitCodePointBefore(5)).isEqualTo('\uD800'.code)
        Assertions.assertThat(scanner.singleUnitCodePointBefore(4)).isEqualTo(-1)
    }

    @Test
    fun utf8ScannerReadsOnlyAsciiWithoutFullDecoding() {
        val scanner = Utf8InputScanner("Aé😀Z".toByteArray(StandardCharsets.UTF_8))

        Assertions.assertThat(scanner.singleUnitCodePointAt(0)).isEqualTo('A'.code)
        Assertions.assertThat(scanner.singleUnitCodePointAt(1)).isEqualTo(-1)
        Assertions.assertThat(scanner.singleUnitCodePointAt(3)).isEqualTo(-1)
        Assertions.assertThat(scanner.singleUnitCodePointBefore(scanner.length)).isEqualTo('Z'.code)
        Assertions.assertThat(scanner.singleUnitCodePointBefore(scanner.length - 1)).isEqualTo(-1)
    }

    @Test
    fun codePointClassSearchUsesRepresentationCoordinates() {
        val cjk = intArrayOf('中'.code, '中'.code)
        val supplementary = intArrayOf(0x1F600, 0x1F600)
        val text = "aé😀中z"
        val stringScanner = StringInputScanner(text)
        val utf8Scanner = Utf8InputScanner(text.toByteArray(StandardCharsets.UTF_8))

        Assertions.assertThat(stringScanner.indexOfCodePointClass(cjk, 0, 0, 0, stringScanner.length))
            .isEqualTo(4)
        Assertions.assertThat(stringScanner.indexOfCodePointClass(supplementary, 0, 0, 0, stringScanner.length))
            .isEqualTo(2)
        Assertions.assertThat(stringScanner.indexOfCodePointClass(cjk, 0, 0, 5, stringScanner.length))
            .isEqualTo(-1)
        Assertions.assertThat(utf8Scanner.indexOfCodePointClass(cjk, 0, 0, 0, utf8Scanner.length)).isEqualTo(7)
        Assertions.assertThat(utf8Scanner.indexOfCodePointClass(supplementary, 0, 0, 0, utf8Scanner.length))
            .isEqualTo(3)
        Assertions.assertThat(utf8Scanner.indexOfCodePointClass(cjk, 0, 0, 10, utf8Scanner.length))
            .isEqualTo(-1)

        val paddedUtf8Scanner =
            Utf8InputScanner(("a".repeat(24) + "中").toByteArray(StandardCharsets.UTF_8))
        Assertions.assertThat(paddedUtf8Scanner.indexOfCodePointClass(cjk, 0, 0, 0, paddedUtf8Scanner.length))
            .isEqualTo(24)
    }
}
