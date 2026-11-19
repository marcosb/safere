// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere

/**
 * Multiplatform pattern façade separate from the Maven JVM [Pattern] public API.
 *
 * The shipping `org.safere.Pattern` / `Matcher` classes remain JVM-only in
 * `safere/src/main/java` (Gradle `jvmMain`) so binary/source compatibility is preserved.
 * Native and common code that needs compile/match entry points use this expect API instead.
 */
expect class PlatformPattern {
  fun pattern(): String

  fun flags(): Int

  fun matcher(input: CharSequence): PlatformMatcher

  companion object {
    fun compile(regex: String): PlatformPattern

    fun compile(regex: String, flags: Int): PlatformPattern

    fun matches(regex: String, input: CharSequence): Boolean

    fun quote(s: String): String
  }
}

expect class PlatformMatcher {
  fun pattern(): PlatformPattern

  fun reset(): PlatformMatcher

  fun reset(input: CharSequence): PlatformMatcher

  fun matches(): Boolean

  fun lookingAt(): Boolean

  fun find(): Boolean

  fun find(start: Int): Boolean

  fun group(): String?

  fun group(group: Int): String?

  fun groupCount(): Int

  fun start(): Int

  fun start(group: Int): Int

  fun end(): Int

  fun end(group: Int): Int
}
