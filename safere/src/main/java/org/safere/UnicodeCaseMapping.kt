// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Single-code-point Unicode case mapping used by [UnicodeCaseFolding].
 *
 * The Maven JVM build compiles this implementation. The KMP sidecar excludes it and supplies
 * expect/actual overlays so native targets compile without `java.lang.Character` case APIs.
 */
internal object UnicodeCaseMapping {
  fun toUpperCase(cp: Int): Int = Character.toUpperCase(cp)

  fun toLowerCase(cp: Int): Int = Character.toLowerCase(cp)

  fun toTitleCase(cp: Int): Int = Character.toTitleCase(cp)
}
