// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Native actual mirroring the JVM [Utf8InputPlatform]: [ArrayUtf8Input] plus
 * [Utf8InputScanner.validate] (both already in commonMain).
 */
internal actual object Utf8InputPlatform {
  actual fun trusted(bytes: ByteArray, offset: Int, length: Int): Utf8Input {
    return ArrayUtf8Input(bytes, offset, length, false)
  }

  actual fun validated(bytes: ByteArray, offset: Int, length: Int): Utf8Input {
    Utf8InputScanner.validate(bytes, offset, length)
    return ArrayUtf8Input(bytes, offset, length, true)
  }
}
