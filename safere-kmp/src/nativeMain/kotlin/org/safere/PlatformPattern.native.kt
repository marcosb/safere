// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.math.min

/**
 * Native actual for [PlatformPattern]: compiles and matches through [PlatformRegexEngine]
 * (Parser → Compiler → OnePass/BitState/DFA/accelerators/Nfa).
 */
actual class PlatformPattern
internal constructor(
  private val compiled: PlatformRegexEngine.Compiled,
) {
  actual fun pattern(): String = compiled.regex

  actual fun flags(): Int = compiled.flags

  actual fun matcher(input: CharSequence): PlatformMatcher = PlatformMatcher(this, input)

  actual fun matcher(input: Utf8Input): PlatformUtf8Matcher = PlatformUtf8Matcher(this, input)

  actual fun split(input: CharSequence, limit: Int): Array<String> {
    val text = input.toString()
    val textLen = text.length
    val matchStarts = ArrayList<Int>()
    val matchEnds = ArrayList<Int>()
    var searchFrom = 0
    var last = 0

    while (searchFrom <= textLen) {
      val caps = PlatformRegexEngine.find(compiled, text, searchFrom, 0, textLen) ?: break
      val start = caps[0]
      val end = caps[1]

      if (limit > 0 && matchStarts.size >= limit - 1) {
        break
      }

      // Skip the leading empty match at position 0 (JDK / Pattern.split behavior).
      if (last == 0 && start == 0 && end == 0) {
        searchFrom = 1
        continue
      }

      matchStarts.add(start)
      matchEnds.add(end)
      last = end
      searchFrom = if (start == end) end + 1 else end
    }

    if (matchStarts.isEmpty()) {
      return arrayOf(text)
    }

    val partsCount =
      if (limit > 0) min(limit, matchStarts.size + 1) else matchStarts.size + 1
    val parts = Array(partsCount) { "" }
    var prev = 0
    var i = 0
    while (i < partsCount - 1) {
      parts[i] = text.substring(prev, matchStarts[i])
      prev = matchEnds[i]
      i++
    }
    parts[i] = text.substring(prev)

    if (limit == 0) {
      var end = partsCount
      while (end > 0 && parts[end - 1].isEmpty()) {
        end--
      }
      if (end < partsCount) {
        return Array(end) { parts[it] }
      }
    }
    return parts
  }

  actual fun namedGroups(): Map<String, Int> = compiled.namedGroups

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
  private var platformPattern: PlatformPattern,
  input: CharSequence,
) {
  private var text: String = input.toString()
  private var captures: IntArray? = null
  private var searchFrom: Int = 0
  private var lastMatchEmpty: Boolean = false
  private var regionStart: Int = 0
  private var regionEnd: Int = text.length
  // Stored for API parity with Matcher; lookaround is unsupported so the flag is unused.
  private var transparentBounds: Boolean = false
  private var appendPosition: Int = 0

  actual fun pattern(): PlatformPattern = platformPattern

  actual fun reset(): PlatformMatcher {
    regionStart = 0
    regionEnd = text.length
    return clearMatchState(searchFrom = 0)
  }

  actual fun reset(input: CharSequence): PlatformMatcher {
    text = input.toString()
    return reset()
  }

  actual fun usePattern(newPattern: PlatformPattern): PlatformMatcher {
    platformPattern = newPattern
    captures = null
    lastMatchEmpty = false
    return this
  }

  actual fun matches(): Boolean {
    val result =
      PlatformRegexEngine.matches(platformPattern.engine(), text, regionStart, regionEnd)
    return accept(result, nextSearchFrom = regionEnd)
  }

  actual fun lookingAt(): Boolean {
    val result =
      PlatformRegexEngine.lookingAt(platformPattern.engine(), text, regionStart, regionEnd)
    if (result == null) {
      return accept(null, nextSearchFrom = regionStart)
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
    if (from > regionEnd) {
      captures = null
      return false
    }
    return findFrom(from)
  }

  actual fun find(start: Int): Boolean {
    val len = text.length
    if (start < 0 || start > len) {
      throw IndexOutOfBoundsException("start=$start, length=$len")
    }
    // Matcher.find(start) resets the region to the full input, then searches from start.
    regionStart = 0
    regionEnd = len
    clearMatchState(searchFrom = start)
    return findFrom(start)
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

  actual fun group(name: String): String? = group(platformPattern.engine().groupIndex(name))

  actual fun groupCount(): Int = platformPattern.engine().groupCount

  actual fun namedGroups(): Map<String, Int> = platformPattern.namedGroups()

  actual fun start(): Int = start(0)

  actual fun start(group: Int): Int {
    val caps = captures ?: return -1
    requireGroup(group, caps)
    return caps[group * 2]
  }

  actual fun start(name: String): Int = start(platformPattern.engine().groupIndex(name))

  actual fun end(): Int = end(0)

  actual fun end(group: Int): Int {
    val caps = captures ?: return -1
    requireGroup(group, caps)
    return caps[group * 2 + 1]
  }

  actual fun end(name: String): Int = end(platformPattern.engine().groupIndex(name))

  actual fun region(start: Int, end: Int): PlatformMatcher {
    if (start < 0 || end < start || end > text.length) {
      throw IndexOutOfBoundsException("region start=$start end=$end length=${text.length}")
    }
    regionStart = start
    regionEnd = end
    return clearMatchState(searchFrom = start)
  }

  actual fun regionStart(): Int = regionStart

  actual fun regionEnd(): Int = regionEnd

  actual fun useTransparentBounds(b: Boolean): PlatformMatcher {
    transparentBounds = b
    return this
  }

  actual fun hasTransparentBounds(): Boolean = transparentBounds

  actual fun replaceFirst(replacement: String): String = replace(replacement, limit = 1)

  actual fun replaceAll(replacement: String): String = replace(replacement, limit = Int.MAX_VALUE)

  actual fun appendReplacement(sb: StringBuilder, replacement: String): PlatformMatcher {
    val caps = captures ?: throw IllegalStateException("No match available")
    sb.append(text, appendPosition, caps[0])
    appendExpanded(sb, replacement, caps)
    appendPosition = caps[1]
    return this
  }

  actual fun appendTail(sb: StringBuilder): StringBuilder {
    sb.append(text, appendPosition, text.length)
    appendPosition = text.length
    return sb
  }

  actual companion object {
    actual fun quoteReplacement(s: String): String {
      var needs = false
      for (ch in s) {
        if (ch == '\\' || ch == '$') {
          needs = true
          break
        }
      }
      if (!needs) {
        return s
      }
      val sb = StringBuilder(s.length * 2)
      for (ch in s) {
        if (ch == '\\' || ch == '$') {
          sb.append('\\')
        }
        sb.append(ch)
      }
      return sb.toString()
    }
  }

  private fun findFrom(start: Int): Boolean {
    val result =
      PlatformRegexEngine.find(platformPattern.engine(), text, start, regionStart, regionEnd)
    if (result == null) {
      captures = null
      searchFrom = regionEnd
      lastMatchEmpty = false
      return false
    }
    return accept(result, nextSearchFrom = result[1])
  }

  private fun clearMatchState(searchFrom: Int): PlatformMatcher {
    captures = null
    this.searchFrom = searchFrom
    lastMatchEmpty = false
    appendPosition = 0
    return this
  }

  private fun replace(replacement: String, limit: Int): String {
    reset()
    val sb = StringBuilder()
    var replaced = 0
    while (replaced < limit && find()) {
      appendReplacement(sb, replacement)
      replaced++
    }
    appendTail(sb)
    return sb.toString()
  }

  private fun appendExpanded(sb: StringBuilder, replacement: String, caps: IntArray) {
    var i = 0
    while (i < replacement.length) {
      val ch = replacement[i]
      if (ch == '\\') {
        i++
        if (i >= replacement.length) {
          throw IllegalArgumentException("character to be escaped is missing")
        }
        sb.append(replacement[i])
        i++
        continue
      }
      if (ch == '$') {
        i++
        if (i >= replacement.length) {
          throw IllegalArgumentException("Illegal group reference: group index is missing")
        }
        if (replacement[i] == '{') {
          i++
          val nameStart = i
          while (i < replacement.length && replacement[i] != '}') {
            i++
          }
          if (i >= replacement.length) {
            throw IllegalArgumentException("named capturing group has missing trailing brace")
          }
          val name = replacement.substring(nameStart, i)
          val g = group(name)
          if (g != null) {
            sb.append(g)
          }
          i++
          continue
        }
        var group = 0
        val refStart = i
        while (i < replacement.length) {
          val d = replacement[i]
          if (d < '0' || d > '9') {
            break
          }
          val next = group * 10 + (d - '0')
          if (next > groupCount()) {
            break
          }
          group = next
          i++
        }
        if (i == refStart) {
          throw IllegalArgumentException("Illegal group reference")
        }
        val g = group(group)
        if (g != null) {
          sb.append(g)
        }
        continue
      }
      sb.append(ch)
      i++
    }
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

actual class PlatformUtf8Matcher
internal constructor(
  platformPattern: PlatformPattern,
  input: Utf8Input,
) {
  private val decoded: String =
    when (input) {
      is ArrayUtf8Input -> input.decode()
      else -> throw IllegalArgumentException("Utf8Input must be ArrayUtf8Input")
    }
  private val delegate = PlatformMatcher(platformPattern, decoded)

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
