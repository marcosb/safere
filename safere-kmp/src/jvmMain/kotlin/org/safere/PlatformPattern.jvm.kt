// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere

/** JVM actual delegates to the public [Pattern] / [Matcher] API without changing it. */
actual class PlatformPattern internal constructor(private val delegate: Pattern) {
  actual fun pattern(): String = delegate.pattern()

  actual fun flags(): Int = delegate.flags()

  actual fun matcher(input: CharSequence): PlatformMatcher =
    PlatformMatcher(delegate.matcher(input))

  actual companion object {
    actual fun compile(regex: String): PlatformPattern = PlatformPattern(Pattern.compile(regex))

    actual fun compile(regex: String, flags: Int): PlatformPattern =
      PlatformPattern(Pattern.compile(regex, flags))

    actual fun matches(regex: String, input: CharSequence): Boolean =
      Pattern.matches(regex, input)

    actual fun quote(s: String): String = Pattern.quote(s)
  }
}

actual class PlatformMatcher internal constructor(private val delegate: Matcher) {
  actual fun pattern(): PlatformPattern = PlatformPattern(delegate.pattern())

  actual fun reset(): PlatformMatcher {
    delegate.reset()
    return this
  }

  actual fun reset(input: CharSequence): PlatformMatcher {
    delegate.reset(input)
    return this
  }

  actual fun matches(): Boolean = delegate.matches()

  actual fun lookingAt(): Boolean = delegate.lookingAt()

  actual fun find(): Boolean = delegate.find()

  actual fun find(start: Int): Boolean = delegate.find(start)

  actual fun group(): String? = delegate.group()

  actual fun group(group: Int): String? = delegate.group(group)

  actual fun groupCount(): Int = delegate.groupCount()

  actual fun start(): Int = delegate.start()

  actual fun start(group: Int): Int = delegate.start(group)

  actual fun end(): Int = delegate.end()

  actual fun end(group: Int): Int = delegate.end(group)
}
