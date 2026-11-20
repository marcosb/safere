// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Native actual: BMP code points use Kotlin [Char] case mapping; supplementary code points are
 * identity-mapped until a shared native Unicode case table is available.
 */
internal actual object UnicodeCaseMapping {
  actual fun toUpperCase(cp: Int): Int =
    if (cp in 0..0xFFFF) cp.toChar().uppercaseChar().code else cp

  actual fun toLowerCase(cp: Int): Int =
    if (cp in 0..0xFFFF) cp.toChar().lowercaseChar().code else cp

  actual fun toTitleCase(cp: Int): Int =
    if (cp in 0..0xFFFF) cp.toChar().titlecaseChar().code else cp
}
