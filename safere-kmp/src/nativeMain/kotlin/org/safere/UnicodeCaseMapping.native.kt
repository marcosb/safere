// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Native actual: full single-code-point case mapping via [UnicodeCaseMappingTables]
 * (JDK [Character]-extracted sparse maps).
 */
internal actual object UnicodeCaseMapping {
  actual fun toUpperCase(cp: Int): Int = UnicodeCaseMappingTables.toUpperCase(cp)

  actual fun toLowerCase(cp: Int): Int = UnicodeCaseMappingTables.toLowerCase(cp)

  actual fun toTitleCase(cp: Int): Int = UnicodeCaseMappingTables.toTitleCase(cp)
}
