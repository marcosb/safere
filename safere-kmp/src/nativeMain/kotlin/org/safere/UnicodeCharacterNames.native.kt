// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Native actual: `\N{...}` name tables are unavailable; every name is treated as unknown so the
 * lexer follows the existing IllegalArgumentException failure path.
 */
internal actual object UnicodeCharacterNames {
  actual fun codePointOf(name: String): Int {
    throw IllegalArgumentException("unknown Unicode character name: $name")
  }
}
