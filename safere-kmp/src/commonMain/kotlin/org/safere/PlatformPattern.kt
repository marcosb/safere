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

  fun matcher(input: Utf8Input): PlatformUtf8Matcher

  fun split(input: CharSequence, limit: Int = 0): Array<String>

  fun namedGroups(): Map<String, Int>

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

  fun usePattern(newPattern: PlatformPattern): PlatformMatcher

  fun matches(): Boolean

  fun lookingAt(): Boolean

  fun find(): Boolean

  fun find(start: Int): Boolean

  fun group(): String?

  fun group(group: Int): String?

  fun group(name: String): String?

  fun groupCount(): Int

  fun namedGroups(): Map<String, Int>

  fun start(): Int

  fun start(group: Int): Int

  fun start(name: String): Int

  fun end(): Int

  fun end(group: Int): Int

  fun end(name: String): Int

  fun region(start: Int, end: Int): PlatformMatcher

  fun regionStart(): Int

  fun regionEnd(): Int

  fun useTransparentBounds(b: Boolean): PlatformMatcher

  fun hasTransparentBounds(): Boolean

  fun replaceFirst(replacement: String): String

  fun replaceAll(replacement: String): String

  fun appendReplacement(sb: StringBuilder, replacement: String): PlatformMatcher

  fun appendTail(sb: StringBuilder): StringBuilder

  companion object {
    fun quoteReplacement(s: String): String
  }
}

/**
 * Portable UTF-8 matcher entry for [PlatformPattern.matcher].
 *
 * Offsets are reported in decoded UTF-16 char indices of the validated input (same model as
 * matching the decoded [String]); byte-offset [Utf8Matcher] remains JVM-only.
 */
expect class PlatformUtf8Matcher {
  fun matches(): Boolean

  fun lookingAt(): Boolean

  fun find(): Boolean

  fun groupCount(): Int

  fun start(): Int

  fun start(group: Int): Int

  fun end(): Int

  fun end(group: Int): Int

  fun reset(): PlatformUtf8Matcher
}
