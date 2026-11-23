// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Native actual for [PlatformPattern]: compiles and matches through [PlatformRegexEngine]
 * (Parser → Compiler → BitState/Nfa) without the JVM [Pattern] / [Matcher] façade.
 */
actual class PlatformPattern
internal constructor(
  private val compiled: PlatformRegexEngine.Compiled,
) {
  actual fun pattern(): String = compiled.regex

  actual fun flags(): Int = compiled.flags

  actual fun matcher(input: CharSequence): PlatformMatcher = PlatformMatcher(this, input)

  internal fun engine(): PlatformRegexEngine.Compiled = compiled

  actual companion object {
    actual fun compile(regex: String): PlatformPattern = compile(regex, 0)

    actual fun compile(regex: String, flags: Int): PlatformPattern =
      PlatformPattern(PlatformRegexEngine.compile(regex, flags))

    actual fun matches(regex: String, input: CharSequence): Boolean =
      compile(regex).matcher(input).matches()

    actual fun quote(s: String): String = PlatformRegexEngine.quote(s)
  }
}

actual class PlatformMatcher
internal constructor(
  private val platformPattern: PlatformPattern,
  input: CharSequence,
) {
  private var text: String = input.toString()
  private var captures: IntArray? = null
  private var searchFrom: Int = 0
  private var lastMatchEmpty: Boolean = false

  actual fun pattern(): PlatformPattern = platformPattern

  actual fun reset(): PlatformMatcher {
    captures = null
    searchFrom = 0
    lastMatchEmpty = false
    return this
  }

  actual fun reset(input: CharSequence): PlatformMatcher {
    text = input.toString()
    return reset()
  }

  actual fun matches(): Boolean {
    val result = PlatformRegexEngine.matches(platformPattern.engine(), text)
    return accept(result, nextSearchFrom = text.length)
  }

  actual fun lookingAt(): Boolean {
    val result = PlatformRegexEngine.lookingAt(platformPattern.engine(), text)
    if (result == null) {
      return accept(null, nextSearchFrom = 0)
    }
    return accept(result, nextSearchFrom = result[1])
  }

  actual fun find(): Boolean {
    val from =
      if (captures != null && lastMatchEmpty) {
        searchFrom + 1
      } else {
        searchFrom
      }
    if (from > text.length) {
      captures = null
      return false
    }
    return find(from)
  }

  actual fun find(start: Int): Boolean {
    val result = PlatformRegexEngine.find(platformPattern.engine(), text, start)
    if (result == null) {
      captures = null
      searchFrom = text.length
      lastMatchEmpty = false
      return false
    }
    val end = result[1]
    return accept(result, nextSearchFrom = end)
  }

  actual fun group(): String? = group(0)

  actual fun group(group: Int): String? {
    val caps = captures ?: return null
    requireGroup(group, caps)
    val start = caps[group * 2]
    val end = caps[group * 2 + 1]
    if (start < 0 || end < 0) {
      return null
    }
    return text.substring(start, end)
  }

  actual fun groupCount(): Int = platformPattern.engine().groupCount

  actual fun start(): Int = start(0)

  actual fun start(group: Int): Int {
    val caps = captures ?: return -1
    requireGroup(group, caps)
    return caps[group * 2]
  }

  actual fun end(): Int = end(0)

  actual fun end(group: Int): Int {
    val caps = captures ?: return -1
    requireGroup(group, caps)
    return caps[group * 2 + 1]
  }

  private fun accept(result: IntArray?, nextSearchFrom: Int): Boolean {
    if (result == null) {
      captures = null
      lastMatchEmpty = false
      return false
    }
    captures = result
    searchFrom = nextSearchFrom
    lastMatchEmpty = result[0] == result[1]
    return true
  }

  private fun requireGroup(group: Int, caps: IntArray) {
    require(group >= 0 && group * 2 + 1 < caps.size) { "No group $group" }
  }
}
