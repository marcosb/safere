// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/** Gradle JVM actual mirroring the Maven [UnicodeCaseMapping] implementation. */
internal actual object UnicodeCaseMapping {
  actual fun toUpperCase(cp: Int): Int = Character.toUpperCase(cp)

  actual fun toLowerCase(cp: Int): Int = Character.toLowerCase(cp)

  actual fun toTitleCase(cp: Int): Int = Character.toTitleCase(cp)
}
