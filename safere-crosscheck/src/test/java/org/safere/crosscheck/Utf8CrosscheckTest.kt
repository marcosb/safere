// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.crosscheck

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.safere.Utf8Sink
import org.safere.crosscheck.Pattern.Companion.compile
import org.safere.crosscheck.Utf8Input.Companion.trusted
import org.safere.crosscheck.Utf8Input.Companion.validated
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

internal class Utf8CrosscheckTest {
    @Test
    fun validatedInputCrosschecksRepeatedFindAndCaptureByteBounds() {
        val matcher =
            compile("(é)|(😀)").matcher(validated("xé😀y".toByteArray(StandardCharsets.UTF_8)))

        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(1)
        Assertions.assertThat(matcher.end()).isEqualTo(3)
        Assertions.assertThat(matcher.start(1)).isEqualTo(1)
        Assertions.assertThat(matcher.end(1)).isEqualTo(3)
        Assertions.assertThat(matcher.start(2)).isEqualTo(-1)
        Assertions.assertThat(matcher.end(2)).isEqualTo(-1)

        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(3)
        Assertions.assertThat(matcher.end()).isEqualTo(7)
        Assertions.assertThat(matcher.start(1)).isEqualTo(-1)
        Assertions.assertThat(matcher.start(2)).isEqualTo(3)
        Assertions.assertThat(matcher.end(2)).isEqualTo(7)
        Assertions.assertThat(matcher.find()).isFalse()
    }

    @Test
    fun emptyFindSkipsJdkPositionsInsideSupplementaryScalars() {
        val matcher = compile("").matcher(validated("😀".toByteArray(StandardCharsets.UTF_8)))

        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isZero()
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(4)
        Assertions.assertThat(matcher.find()).isFalse()
    }

    @Test
    fun logicalWindowCoordinatesRemainRelative() {
        val logical = "xéy".toByteArray(StandardCharsets.UTF_8)
        val storage = ByteArray(logical.size + 8)
        System.arraycopy(logical, 0, storage, 4, logical.size)

        val matcher =
            compile("é").matcher(validated(storage, 4, logical.size))

        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(1)
        Assertions.assertThat(matcher.end()).isEqualTo(3)
    }

    @Test
    fun matchOperationsResetAndRegionsCrosscheckByteCoordinates() {
        val whole: Utf8Matcher = compile("é😀").matcher(input("é😀"))
        val prefix: Utf8Matcher = compile("é").matcher(input("é😀"))

        Assertions.assertThat(whole.matches()).isTrue()
        Assertions.assertThat(prefix.matches()).isFalse()
        Assertions.assertThat(prefix.lookingAt()).isTrue()

        val region: Utf8Matcher = compile("(\\b(?:a|aa))").matcher(input("xaa"))
        Assertions.assertThat<Utf8Matcher>(region.region(1, 3)).isSameAs(region)
        Assertions.assertThat(region.regionStart()).isEqualTo(1)
        Assertions.assertThat(region.regionEnd()).isEqualTo(3)
        Assertions.assertThat(region.matches()).isTrue()
        Assertions.assertThat(region.start(1)).isEqualTo(1)
        Assertions.assertThat(region.end(1)).isEqualTo(3)

        Assertions.assertThat<Utf8Matcher>(region.reset()).isSameAs(region)
        Assertions.assertThat(region.regionStart()).isZero()
        Assertions.assertThat(region.regionEnd()).isEqualTo(3)
        Assertions.assertThat(region.find()).isFalse()
    }

    @Test
    fun observationsCrosscheckSuccessAndExceptionStates() {
        val matcher: Utf8Matcher = compile("(a)").matcher(input("a"))

        Assertions.assertThatThrownBy(ThrowingCallable { matcher.start() })
            .isInstanceOf(IllegalStateException::class.java)
        Assertions.assertThat(matcher.groupCount()).isEqualTo(1)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.start(-1) })
            .isInstanceOf(IndexOutOfBoundsException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.end(2) })
            .isInstanceOf(IndexOutOfBoundsException::class.java)
        Assertions.assertThat(matcher.find()).isFalse()
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.end() })
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun replacementOutputIsCrosscheckedCallByCall() {
        val matcher: Utf8Matcher = compile("(?<letter>é)").matcher(input("xéyéz"))
        val output = ByteArrayOutputStream()

        while (matcher.find()) {
            matcher.appendReplacement(
                Utf8Sink { b: ByteArray?, off: Int, len: Int -> output.write(b, off, len) },
                input("\${letter}\${letter}")
            )
        }
        matcher.appendTail(org.safere.crosscheck.Utf8Sink { b: ByteArray?, off: Int, len: Int ->
            output.write(
                b,
                off,
                len
            )
        })

        Assertions.assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("xééyééz")
    }

    @Test
    fun captureFreeFindUsesDecodedOraclesForValidatedInput() {
        val pattern = compile("😀$")

        Assertions.assertThat(pattern.find(input("x😀"))).isTrue()
        Assertions.assertThat(pattern.find(input("😀x"))).isFalse()
    }

    @Test
    fun trustedMalformedInputRunsWithoutSemanticOracle() {
        val malformed = trusted(byteArrayOf('a'.code.toByte(), 0x80.toByte(), 'b'.code.toByte()))

        val matcher = compile(".").matcher(malformed)
        while (matcher.find()) {
            Assertions.assertThat(matcher.start()).isBetween(0, malformed.length)
            Assertions.assertThat(matcher.end()).isBetween(matcher.start(), malformed.length)
        }
        compile("a").find(malformed)
    }

    @Test
    fun graphemeClusterPatternDoesNotSplitUtf8MultibyteCodePointInUnanchoredFind() {
        val matcher: Utf8Matcher = compile(".\\X| /0? ?5-").matcher(input("\u1e99| 5  \u8ed6"))
        while (matcher.find()) {
        }
    }

    companion object {
        private fun input(text: String): Utf8Input {
            return validated(text.toByteArray(StandardCharsets.UTF_8))
        }
    }
}
