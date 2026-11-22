// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.Arrays

/**
 * Bounded byte-array region comparison.
 *
 * The Maven JVM build uses [Arrays.equals], a HotSpot intrinsic. The KMP sidecar excludes this
 * file and supplies expect/actual overlays so native targets compile without `java.util.Arrays`.
 */
internal object ByteArrayRegions {
  fun regionEquals(a: ByteArray, aFrom: Int, b: ByteArray, bFrom: Int, length: Int): Boolean {
    return Arrays.equals(a, aFrom, aFrom + length, b, bFrom, bFrom + length)
  }
}
