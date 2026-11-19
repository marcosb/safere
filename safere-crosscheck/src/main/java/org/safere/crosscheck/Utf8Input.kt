// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.crosscheck

import org.safere.Utf8Input
import java.nio.charset.StandardCharsets
import java.util.*

/** Crosscheck view of caller-owned UTF-8 storage.  */
class Utf8Input private constructor(
    private val delegate: Utf8Input?,
    private val bytes: ByteArray?,
    private val offset: Int,
    private val length: Int,
    private val decoded: String?
) {
    /** Returns the logical input length in UTF-8 bytes.  */
    fun length(): Int {
        return length
    }

    fun delegate(): Utf8Input? {
        return delegate
    }

    fun bytes(): ByteArray? {
        return bytes
    }

    fun offset(): Int {
        return offset
    }

    fun validated(): Boolean {
        return decoded != null
    }

    fun decoded(): String {
        checkNotNull(decoded) { "trusted UTF-8 input has no semantic oracle" }
        return decoded
    }

    companion object {
        /** Creates a trusted whole-array input without semantic oracle comparison.  */
        @JvmStatic
        fun trusted(bytes: ByteArray?): org.safere.crosscheck.Utf8Input {
            Objects.requireNonNull<ByteArray?>(bytes, "bytes")
            return trusted(bytes, 0, bytes!!.size)
        }

        /** Creates a trusted logical window without semantic oracle comparison.  */
        fun trusted(bytes: ByteArray?, offset: Int, length: Int): org.safere.crosscheck.Utf8Input {
            val delegate = Utf8Input.trusted(bytes, offset, length)
            return Utf8Input(delegate, bytes, offset, length, null)
        }

        /** Creates a strictly validated whole-array input with String and JDK oracles.  */
        @JvmStatic
        fun validated(bytes: ByteArray?): org.safere.crosscheck.Utf8Input {
            Objects.requireNonNull<ByteArray?>(bytes, "bytes")
            return Companion.validated(bytes!!, 0, bytes.size)
        }

        /** Creates a strictly validated logical window with String and JDK oracles.  */
        @JvmStatic
        fun validated(bytes: ByteArray, offset: Int, length: Int): org.safere.crosscheck.Utf8Input {
            val delegate = Utf8Input.validated(bytes, offset, length)
            return Utf8Input(delegate, bytes, offset, length, String(bytes, offset, length, StandardCharsets.UTF_8))
        }
    }
}
