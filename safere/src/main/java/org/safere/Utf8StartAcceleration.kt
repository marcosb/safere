// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * CommonMain bridge for UTF-8 start acceleration used by [Dfa].
 *
 * The JVM [Utf8StartAccelerator] sealed implementations stay on jvmMain and implement this
 * interface so Dfa does not name [Utf8InputScanner].
 */
internal interface Utf8StartAcceleration {
  fun policy(): AcceleratorPolicy {
    return AcceleratorPolicy.DEFAULT
  }

  fun findNextCandidate(scanner: InputScanner, pos: Int): Int

  fun searchCursor(): Utf8StartSearchCursor? {
    return null
  }
}

/** Search-local cursor for accelerators that scan multiple folded byte variants.  */
internal fun interface Utf8StartSearchCursor {
  fun findCandidate(scanner: InputScanner, fromIndex: Int): Int
}
