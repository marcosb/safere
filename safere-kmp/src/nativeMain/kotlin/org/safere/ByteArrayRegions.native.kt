// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/** Native actual: scalar region compare (no `java.util.Arrays`). */
internal actual object ByteArrayRegions {
  actual fun regionEquals(a: ByteArray, aFrom: Int, b: ByteArray, bFrom: Int, length: Int): Boolean {
    for (i in 0 until length) {
      if (a[aFrom + i] != b[bFrom + i]) {
        return false
      }
    }
    return true
  }
}
