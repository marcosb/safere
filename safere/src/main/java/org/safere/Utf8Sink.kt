// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.nio.ByteBuffer

/**
 * A synchronous destination for borrowed UTF-8 byte ranges.
 * 
 * 
 * An implementation must consume each range before returning and must not retain or mutate the
 * supplied storage.
 */
fun interface Utf8Sink {
    /**
     * Appends the specified borrowed array range before this method returns.
     * 
     * @param bytes borrowed storage
     * @param offset first byte to consume
     * @param length number of bytes to consume
     */
    fun append(bytes: ByteArray?, offset: Int, length: Int)

    /**
     * Appends the remaining bytes of a bounded duplicate before this method returns.
     * 
     * 
     * The default implementation does not change the caller's buffer position. It transfers
     * array-backed buffers directly and copies other buffers through bounded chunks.
     * 
     * @param buffer buffer whose remaining bytes are consumed synchronously
     */
    fun append(buffer: ByteBuffer) {
        val source = buffer.duplicate()
        if (source.hasArray()) {
            append(source.array(), source.arrayOffset() + source.position(), source.remaining())
            return
        }
        val chunk = ByteArray(Math.min(source.remaining(), 8192))
        while (source.hasRemaining()) {
            val length = Math.min(source.remaining(), chunk.size)
            source.get(chunk, 0, length)
            append(chunk, 0, length)
        }
    }
}
