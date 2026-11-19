// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*
import kotlin.jvm.JvmStatic

/**
 * A borrowed, immutable-bounds view of caller-owned UTF-8 text storage.
 * 
 * 
 * All positions are byte offsets relative to this logical view. The caller must not mutate the
 * covered bytes while the view or a matcher retaining it is in use. Metadata is safe to share
 * between threads under that precondition; a [Utf8Matcher] is not thread-safe.
 * 
 * 
 * Patterns use SafeRE's documented Java-oriented syntax and semantics. Match coordinates are
 * always UTF-8 byte offsets and never positions inside a well-formed Unicode scalar.
 */
interface Utf8Input {
    /**
     * Returns the length of the logical input in UTF-8 bytes.
     * 
     * @return the relative logical end position
     */
    val length: Int

    companion object {
        /**
         * Returns a trusted view over an entire byte array without validating its UTF-8.
         * 
         * 
         * Exact match results for malformed input are unspecified, but matching remains bounded,
         * memory-safe, and monotonic.
         * 
         * @param bytes caller-owned UTF-8 storage
         * @return the borrowed input view
         * @throws NullPointerException if `bytes` is null
         */
        @JvmStatic
        fun trusted(bytes: ByteArray?): Utf8Input {
            Objects.requireNonNull<ByteArray?>(bytes, "bytes")
            return trusted(bytes!!, 0, bytes!!.size)
        }

        /**
         * Returns a trusted view over a byte-array window without validating its UTF-8.
         * 
         * @param bytes caller-owned UTF-8 storage
         * @param offset physical array offset of the logical view
         * @param length logical view length in bytes
         * @return the borrowed input view
         * @throws NullPointerException if `bytes` is null
         * @throws IndexOutOfBoundsException if the window is outside `bytes`
         */
        @JvmStatic
        fun trusted(bytes: ByteArray?, offset: Int, length: Int): Utf8Input {
            requireNotNull(bytes) { "bytes" }
            return ArrayUtf8Input(bytes!!, offset, length, false)
        }

        /**
         * Returns a view over an entire byte array after strict RFC 3629 UTF-8 validation.
         * 
         * @param bytes caller-owned UTF-8 storage
         * @return the borrowed, validated input view
         * @throws NullPointerException if `bytes` is null
         * @throws IllegalArgumentException if the first malformed sequence is encountered
         */
        @JvmStatic
        fun validated(bytes: ByteArray?): Utf8Input {
            Objects.requireNonNull<ByteArray?>(bytes, "bytes")
            return validated(bytes!!, 0, bytes!!.size)
        }

        /**
         * Returns a byte-array window after strict RFC 3629 UTF-8 validation.
         * 
         * @param bytes caller-owned UTF-8 storage
         * @param offset physical array offset of the logical view
         * @param length logical view length in bytes
         * @return the borrowed, validated input view
         * @throws NullPointerException if `bytes` is null
         * @throws IndexOutOfBoundsException if the window is outside `bytes`
         * @throws IllegalArgumentException if malformed UTF-8 occurs, with a relative byte offset
         */
        @JvmStatic
        fun validated(bytes: ByteArray?, offset: Int, length: Int): Utf8Input {
            requireNotNull(bytes) { "bytes" }
            Utf8InputScanner.validate(bytes!!, offset, length)
            return ArrayUtf8Input(bytes!!, offset, length, true)
        }
    }
}
