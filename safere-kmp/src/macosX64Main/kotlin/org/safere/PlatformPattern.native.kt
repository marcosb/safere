// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere

/**
 * Native stub actual for [PlatformPattern].
 *
 * Matching semantics will use the portable engine once commonMain coverage grows; this stub
 * keeps the host-native target compiling without altering the Maven JVM public API.
 */
actual class PlatformPattern
internal constructor(
  private val regex: String,
  private val patternFlags: Int,
) {
  actual fun pattern(): String = regex

  actual fun flags(): Int = patternFlags

  actual fun matcher(input: CharSequence): PlatformMatcher = PlatformMatcher(this, input)

  actual companion object {
    actual fun compile(regex: String): PlatformPattern = PlatformPattern(regex, 0)

    actual fun compile(regex: String, flags: Int): PlatformPattern = PlatformPattern(regex, flags)

    actual fun matches(regex: String, input: CharSequence): Boolean =
      compile(regex).matcher(input).matches()

    actual fun quote(s: String): String {
      val sb = StringBuilder(s.length * 2)
      for (ch in s) {
        if ("\\.?*+()[]{}|^$".indexOf(ch) >= 0) {
          sb.append('\\')
        }
        sb.append(ch)
      }
      return sb.toString()
    }
  }
}

actual class PlatformMatcher
internal constructor(
  private val platformPattern: PlatformPattern,
  private var input: CharSequence,
) {
  private var matchStart: Int = -1
  private var matchEnd: Int = -1

  actual fun pattern(): PlatformPattern = platformPattern

  actual fun reset(): PlatformMatcher {
    matchStart = -1
    matchEnd = -1
    return this
  }

  actual fun reset(input: CharSequence): PlatformMatcher {
    this.input = input
    return reset()
  }

  actual fun matches(): Boolean {
    // Stub: only exact literal matches until the portable engine is wired.
    val p = platformPattern.pattern()
    return if (p == input.toString()) {
      matchStart = 0
      matchEnd = input.length
      true
    } else {
      matchStart = -1
      matchEnd = -1
      false
    }
  }

  actual fun lookingAt(): Boolean = matches()

  actual fun find(): Boolean = find(0)

  actual fun find(start: Int): Boolean {
    val p = platformPattern.pattern()
    val idx = input.toString().indexOf(p, start)
    return if (idx >= 0) {
      matchStart = idx
      matchEnd = idx + p.length
      true
    } else {
      matchStart = -1
      matchEnd = -1
      false
    }
  }

  actual fun group(): String? =
    if (matchStart >= 0) input.substring(matchStart, matchEnd) else null

  actual fun group(group: Int): String? = if (group == 0) group() else null

  actual fun groupCount(): Int = 0

  actual fun start(): Int = matchStart

  actual fun start(group: Int): Int = if (group == 0) start() else -1

  actual fun end(): Int = matchEnd

  actual fun end(group: Int): Int = if (group == 0) end() else -1
}
