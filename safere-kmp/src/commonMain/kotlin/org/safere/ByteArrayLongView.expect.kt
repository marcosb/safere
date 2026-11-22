// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/** KMP expect for [ByteArrayLongView]. */
internal expect object ByteArrayLongView {
  val LONG_BYTES: Int

  fun getNative(bytes: ByteArray, index: Int): Long

  fun getLittleEndian(bytes: ByteArray, index: Int): Long

  val isNativeLittleEndian: Boolean
}
