// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

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
}
