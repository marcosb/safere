// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere

/** JVM actual delegates to the public [Pattern] / [Matcher] API without changing it. */
actual class PlatformPattern internal constructor(private val delegate: Pattern) {
  actual fun pattern(): String = delegate.pattern()!!

  actual fun flags(): Int = delegate.flags()

  actual fun matcher(input: CharSequence): PlatformMatcher =
    PlatformMatcher(delegate.matcher(input))

  actual fun matcher(input: Utf8Input): PlatformUtf8Matcher =
    PlatformUtf8Matcher(delegate.matcher(input))

  actual fun split(input: CharSequence, limit: Int): Array<String> {
    val raw = delegate.split(input, limit)
    return Array(raw.size) { i -> raw[i] ?: "" }
  }

  actual fun namedGroups(): Map<String, Int> = delegate.namedGroups()

  internal fun jvmPattern(): Pattern = delegate

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

  actual fun usePattern(newPattern: PlatformPattern): PlatformMatcher {
    delegate.usePattern(newPattern.jvmPattern())
    return this
  }

  actual fun matches(): Boolean = delegate.matches()

  actual fun lookingAt(): Boolean = delegate.lookingAt()

  actual fun find(): Boolean = delegate.find()

  actual fun find(start: Int): Boolean = delegate.find(start)

  actual fun group(): String? = delegate.group()

  actual fun group(group: Int): String? = delegate.group(group)

  actual fun group(name: String): String? = delegate.group(name)

  actual fun groupCount(): Int = delegate.groupCount()

  actual fun namedGroups(): Map<String, Int> {
    val raw = delegate.namedGroups()
    val out = LinkedHashMap<String, Int>(raw.size)
    for ((k, v) in raw) {
      if (k != null && v != null) {
        out[k] = v
      }
    }
    return out
  }

  actual fun start(): Int = delegate.start()

  actual fun start(group: Int): Int = delegate.start(group)

  actual fun start(name: String): Int = delegate.start(name)

  actual fun end(): Int = delegate.end()

  actual fun end(group: Int): Int = delegate.end(group)

  actual fun end(name: String): Int = delegate.end(name)

  actual fun region(start: Int, end: Int): PlatformMatcher {
    delegate.region(start, end)
    return this
  }

  actual fun regionStart(): Int = delegate.regionStart()

  actual fun regionEnd(): Int = delegate.regionEnd()

  actual fun useTransparentBounds(b: Boolean): PlatformMatcher {
    delegate.useTransparentBounds(b)
    return this
  }

  actual fun hasTransparentBounds(): Boolean = delegate.hasTransparentBounds()

  actual fun replaceFirst(replacement: String): String = delegate.replaceFirst(replacement)!!

  actual fun replaceAll(replacement: String): String = delegate.replaceAll(replacement)!!

  actual fun appendReplacement(sb: StringBuilder, replacement: String): PlatformMatcher {
    delegate.appendReplacement(sb, replacement)
    return this
  }

  actual fun appendTail(sb: StringBuilder): StringBuilder = delegate.appendTail(sb)

  actual companion object {
    actual fun quoteReplacement(s: String): String = Matcher.quoteReplacement(s)
  }
}

actual class PlatformUtf8Matcher internal constructor(private val delegate: Utf8Matcher) {
  actual fun matches(): Boolean = delegate.matches()

  actual fun lookingAt(): Boolean = delegate.lookingAt()

  actual fun find(): Boolean = delegate.find()

  actual fun groupCount(): Int = delegate.groupCount()

  actual fun start(): Int = delegate.start()

  actual fun start(group: Int): Int = delegate.start(group)

  actual fun end(): Int = delegate.end()

  actual fun end(group: Int): Int = delegate.end(group)

  actual fun reset(): PlatformUtf8Matcher {
    delegate.reset()
    return this
  }
}
