// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.nio.ByteBuffer
import kotlin.math.min

/**
 * JVM-only [ByteBuffer] sink helpers for [Utf8Sink].
 *
 * Kept in a separate file so the KMP sidecar can park it on `jvmMain` while promoting the core
 * [Utf8Sink] interface into `commonMain`.
 */
fun Utf8Sink.append(buffer: ByteBuffer) {
  val source = buffer.duplicate()
  if (source.hasArray()) {
    append(source.array(), source.arrayOffset() + source.position(), source.remaining())
    return
  }
  val chunk = ByteArray(min(source.remaining(), 8192))
  while (source.hasRemaining()) {
    val length = min(source.remaining(), chunk.size)
    source.get(chunk, 0, length)
    append(chunk, 0, length)
  }
}
