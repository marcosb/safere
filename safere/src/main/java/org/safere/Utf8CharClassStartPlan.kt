// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.safere.CharClassScanInfo.UnicodeSmallSet

/**
 * Portable non-ASCII UTF-8 sequence for [Utf8StartAccelerator.CharClass] small Unicode sets
 * (singleton or ASCII+non-ASCII mixed pair).
 */
internal class Utf8CharClassStartPlan(
  val scanInfo: CharClassScanInfo?,
  val nonAsciiUtf8: ByteArray?,
) {
  companion object {
    fun from(scanInfo: CharClassScanInfo?): Utf8CharClassStartPlan {
      return Utf8CharClassStartPlan(scanInfo, nonAsciiUtf8(scanInfo))
    }

    fun nonAsciiUtf8(scanInfo: CharClassScanInfo?): ByteArray? {
      if (scanInfo is UnicodeSmallSet) {
        val chars = scanInfo.chars
        if (chars.size == 1) {
          return chars[0].toString().encodeToByteArray()
        }
        if (chars.size == 2 && chars[0].code < 0x80 && chars[1].code >= 0x80) {
          return chars[1].toString().encodeToByteArray()
        }
      }
      return null
    }
  }
}
