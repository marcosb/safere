// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Coverage for the portable [PlatformPattern] / [PlatformMatcher] façade. */
class PlatformPatternTest {
  @Test
  fun matchesWholeInput() {
    val m = PlatformPattern.compile("foo").matcher("foo")
    assertTrue(m.matches())
    assertEquals("foo", m.group())
    assertEquals(0, m.start())
    assertEquals(3, m.end())
  }

  @Test
  fun lookingAtPrefix() {
    val m = PlatformPattern.compile("foo").matcher("foobar")
    assertTrue(m.lookingAt())
    assertEquals("foo", m.group())
    assertFalse(m.matches())
  }

  @Test
  fun findSuccessiveMatches() {
    val m = PlatformPattern.compile("a+").matcher("aabaaa")
    assertTrue(m.find())
    assertEquals("aa", m.group())
    assertTrue(m.find())
    assertEquals("aaa", m.group())
    assertFalse(m.find())
  }

  @Test
  fun captureGroup() {
    val m = PlatformPattern.compile("(a+)(b+)").matcher("aaabbb")
    assertTrue(m.matches())
    assertEquals(2, m.groupCount())
    assertEquals("aaa", m.group(1))
    assertEquals("bbb", m.group(2))
    assertEquals(0, m.start(1))
    assertEquals(3, m.end(1))
  }

  @Test
  fun namedCaptureGroup() {
    val m = PlatformPattern.compile("(?<first>a+)(?<second>b+)").matcher("aaabbb")
    assertTrue(m.matches())
    assertEquals("aaa", m.group("first"))
    assertEquals("bbb", m.group("second"))
    assertEquals(0, m.start("first"))
    assertEquals(6, m.end("second"))
    assertEquals(mapOf("first" to 1, "second" to 2), m.namedGroups())
  }

  @Test
  fun caseInsensitiveFlag() {
    val m =
      PlatformPattern.compile("foo", PatternFlagMapping.CASE_INSENSITIVE).matcher("FOO")
    assertTrue(m.matches())
  }

  @Test
  fun quoteEscapesMetacharacters() {
    val quoted = PlatformPattern.quote("a.b+")
    assertEquals("\\Qa.b+\\E", quoted)
    assertTrue(PlatformPattern.compile(quoted).matcher("a.b+").matches())
    assertFalse(PlatformPattern.compile(quoted).matcher("axb+").matches())
  }

  @Test
  fun regionLimitsFind() {
    val m = PlatformPattern.compile("a+").matcher("xxaaayy")
    m.region(2, 5)
    assertTrue(m.find())
    assertEquals("aaa", m.group())
    assertEquals(2, m.start())
    assertEquals(5, m.end())
    assertFalse(m.find())
  }

  @Test
  fun replaceAllAndQuoteReplacement() {
    val m = PlatformPattern.compile("a+").matcher("xaaay")
    assertEquals("xby", m.replaceAll("b"))
    assertEquals("\\$1", PlatformMatcher.quoteReplacement("$1"))
  }

  @Test
  fun replaceFirstWithGroupReference() {
    val m = PlatformPattern.compile("(a+)(b+)").matcher("aaabbbaaa")
    assertEquals("bbbaaa", m.replaceFirst("$2"))
  }

  @Test
  fun appendReplacementAndTail() {
    val m = PlatformPattern.compile("a+").matcher("a1a2")
    val sb = StringBuilder()
    assertTrue(m.find())
    m.appendReplacement(sb, "X")
    assertTrue(m.find())
    m.appendReplacement(sb, "Y")
    m.appendTail(sb)
    assertEquals("X1Y2", sb.toString())
  }

  @Test
  fun splitOnComma() {
    val p = PlatformPattern.compile(",")
    assertEquals(listOf("a", "b", "c"), p.split("a,b,c").toList())
  }

  @Test
  fun splitZeroWidthAndLimit() {
    val p = PlatformPattern.compile("x*")
    // Zero-width delimiters split between characters; leading empty at 0 is skipped.
    assertEquals(listOf("a", "b", "c"), p.split("abc").toList())
    assertEquals(listOf("a", "bc"), p.split("abc", 2).toList())
    assertEquals(listOf("a", "b", "c"), PlatformPattern.compile("").split("abc").toList())
  }

  @Test
  fun findStartResetsRegion() {
    val m = PlatformPattern.compile("a+").matcher("xxaaayy")
    m.region(2, 5)
    assertTrue(m.find())
    assertEquals("aaa", m.group())
    // find(start) restores the full input region, matching Matcher.find(int).
    assertTrue(m.find(0))
    assertEquals(0, m.regionStart())
    assertEquals(7, m.regionEnd())
    assertEquals("aaa", m.group())
    assertEquals(2, m.start())
  }

  @Test
  fun unicodeCharacterNameEscape() {
    val m = PlatformPattern.compile("\\N{LATIN CAPITAL LETTER A}").matcher("A")
    assertTrue(m.matches())
  }

  @Test
  fun unicodeCaseInsensitiveSupplementary() {
    // U+10400 DESERET CAPITAL LETTER LONG I ↔ U+10428 DESERET SMALL LETTER LONG I
    val m =
      PlatformPattern.compile(
          "\uD801\uDC00",
          PatternFlagMapping.CASE_INSENSITIVE or PatternFlagMapping.UNICODE_CASE,
        )
        .matcher("\uD801\uDC28")
    assertTrue(m.matches())
  }

  @Test
  fun utf8MatcherFindsLiteral() {
    val bytes = "hello".encodeToByteArray()
    val input = Utf8Input.validated(bytes)
    val m = PlatformPattern.compile("ell").matcher(input)
    assertTrue(m.find())
    assertEquals(1, m.start())
    assertEquals(4, m.end())
  }

  @Test
  fun unknownCharacterNameFails() {
    assertFailsWith<IllegalArgumentException> {
      PlatformPattern.compile("\\N{NO SUCH CHARACTER}")
    }
  }
}
