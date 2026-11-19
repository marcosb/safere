// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

@DisabledForCrosscheck("uses the package-private array adapter to verify borrowed storage")
internal class Utf8InputTest {
    @Test
    fun wholeArrayFactoriesExposeLogicalLength() {
        val bytes = "aé😀".toByteArray(StandardCharsets.UTF_8)

        Assertions.assertThat(Utf8Input.trusted(bytes).length).isEqualTo(bytes.size)
        Assertions.assertThat(Utf8Input.validated(bytes).length).isEqualTo(bytes.size)
    }

    @Test
    fun windowFactoriesUseRelativeCoordinates() {
        val logical = "é😀".toByteArray(StandardCharsets.UTF_8)
        val storage = ByteArray(logical.size + 4)
        System.arraycopy(logical, 0, storage, 2, logical.size)

        val trusted = Utf8Input.trusted(storage, 2, logical.size)
        val validated = Utf8Input.validated(storage, 2, logical.size)

        Assertions.assertThat(trusted.length).isEqualTo(logical.size)
        Assertions.assertThat(validated.length).isEqualTo(logical.size)
    }

    @Test
    fun factoriesRejectNullAndInvalidBounds() {
        Assertions.assertThatNullPointerException().isThrownBy(ThrowingCallable { Utf8Input.trusted(null) })
        Assertions.assertThatNullPointerException().isThrownBy(ThrowingCallable { Utf8Input.validated(null) })
        Assertions.assertThatThrownBy(ThrowingCallable { Utf8Input.trusted(ByteArray(1), -1, 1) })
            .isInstanceOf(IndexOutOfBoundsException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { Utf8Input.trusted(ByteArray(1), 0, Int.Companion.MAX_VALUE) })
            .isInstanceOf(IndexOutOfBoundsException::class.java)
    }

    @Test
    fun validatedFactoryRejectsMalformedInputAtRelativeOffset() {
        val storage = byteArrayOf('x'.code.toByte(), 'a'.code.toByte(), 0xC0.toByte(), 0x80.toByte(), 'z'.code.toByte())

        Assertions.assertThatThrownBy(ThrowingCallable { Utf8Input.validated(storage, 1, 3) })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("byte 1")
    }

    @Test
    fun trustedFactoryAcceptsMalformedInput() {
        val malformed = byteArrayOf(0xC0.toByte(), 0x80.toByte())

        Assertions.assertThat(Utf8Input.trusted(malformed).length).isEqualTo(2)
    }

    @Test
    fun factoriesBorrowCallerStorageRatherThanCopyingIt() {
        val storage = "borrowed".toByteArray(StandardCharsets.UTF_8)

        val input = Utf8Input.validated(storage) as ArrayUtf8Input

        Assertions.assertThat(input.scanner().bytes()).isSameAs(storage)
    }

    @Test
    fun mutationIsAnExplicitBorrowedStoragePreconditionBoundary() {
        val storage = "a".toByteArray(StandardCharsets.UTF_8)
        val input = Utf8Input.trusted(storage)

        storage[0] = 'b'.code.toByte()

        Assertions.assertThat(Pattern.compile("b").find(input)).isTrue()
    }
}
