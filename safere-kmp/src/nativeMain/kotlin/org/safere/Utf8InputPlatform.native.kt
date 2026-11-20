// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Native actual: length-only byte views so [Utf8Input] companions compile without the JVM UTF-8
 * scanner stack. Full matching engines remain parked on jvmMain.
 */
internal actual object Utf8InputPlatform {
  actual fun trusted(bytes: ByteArray, offset: Int, length: Int): Utf8Input {
    checkWindow(bytes, offset, length)
    return NativeUtf8Input(length)
  }

  actual fun validated(bytes: ByteArray, offset: Int, length: Int): Utf8Input {
    // Validation stays with Utf8InputScanner on JVM; native accepts the window as trusted.
    return trusted(bytes, offset, length)
  }

  private fun checkWindow(bytes: ByteArray, offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset > bytes.size - length) {
      throw IndexOutOfBoundsException(
        "offset=$offset, length=$length, bytes.size=${bytes.size}"
      )
    }
  }

  private class NativeUtf8Input(override val length: Int) : Utf8Input
}
