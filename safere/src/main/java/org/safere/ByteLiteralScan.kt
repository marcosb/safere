// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/** KMP / Horspool tables for exact UTF-8 literal scans.  */
internal object ByteLiteralScan {
  fun literalFailure(literal: ByteArray): IntArray {
    val failure = IntArray(literal.size)
    var matched = 0
    for (index in 1..<literal.size) {
      while (matched > 0 && literal[index] != literal[matched]) {
        matched = failure[matched - 1]
      }
      if (literal[index] == literal[matched]) {
        matched++
      }
      failure[index] = matched
    }
    return failure
  }

  fun literalShifts(literal: ByteArray): IntArray? {
    if (literal.size < 2) {
      return null
    }
    val shifts = IntArray(256)
    shifts.fill(literal.size)
    for (index in 0..<literal.size - 1) {
      shifts[literal[index].toInt() and 0xFF] = literal.size - index - 1
    }
    return shifts
  }
}
