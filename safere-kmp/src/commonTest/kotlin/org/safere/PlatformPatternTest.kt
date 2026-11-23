// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Basic coverage for the portable [PlatformPattern] / [PlatformMatcher] façade. */
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
}
