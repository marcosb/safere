// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Factory helpers for [Utf8Input] companions.
 *
 * Pure Kotlin over [ArrayUtf8Input] and [Utf8InputScanner], so Maven and every KMP target compile
 * this same file (no expect/actual overlay).
 */
internal object Utf8InputPlatform {
  fun trusted(bytes: ByteArray, offset: Int, length: Int): Utf8Input {
    return ArrayUtf8Input(bytes, offset, length, false)
  }

  fun validated(bytes: ByteArray, offset: Int, length: Int): Utf8Input {
    Utf8InputScanner.validate(bytes, offset, length)
    return ArrayUtf8Input(bytes, offset, length, true)
  }
}
