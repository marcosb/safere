// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Native actual: scalar unaligned loads.
 *
 * All current safere-kmp host targets are little-endian, so native-order loads match
 * little-endian packing.
 */
internal actual object ByteArrayLongView {
  actual val LONG_BYTES: Int = 8

  actual fun getNative(bytes: ByteArray, index: Int): Long = getLittleEndian(bytes, index)

  actual val isNativeLittleEndian: Boolean = true

  actual fun getLittleEndian(bytes: ByteArray, index: Int): Long {
    return (bytes[index].toLong() and 0xffL) or
      ((bytes[index + 1].toLong() and 0xffL) shl 8) or
      ((bytes[index + 2].toLong() and 0xffL) shl 16) or
      ((bytes[index + 3].toLong() and 0xffL) shl 24) or
      ((bytes[index + 4].toLong() and 0xffL) shl 32) or
      ((bytes[index + 5].toLong() and 0xffL) shl 40) or
      ((bytes[index + 6].toLong() and 0xffL) shl 48) or
      ((bytes[index + 7].toLong() and 0xffL) shl 56)
  }
}
