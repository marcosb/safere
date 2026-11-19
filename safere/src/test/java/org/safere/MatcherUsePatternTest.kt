// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Tests for [Matcher.usePattern].  */
internal class MatcherUsePatternTest {
    @Test
    @DisplayName("usePattern swaps pattern and preserves position")
    fun usePatternSwapsPattern() {
        val p1 = Pattern.compile("\\d+")
        val p2 = Pattern.compile("[a-z]+")
        val m = p1.matcher("123abc456def")
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.group()).isEqualTo("123")
        m.usePattern(p2)
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.group()).isEqualTo("abc")
    }

    @Test
    @DisplayName("usePattern continues searching after the previous match")
    fun usePatternContinuesAfterPreviousMatchEnd() {
        val m = Pattern.compile("a").matcher("ab")
        Assertions.assertThat(m.find()).isTrue()

        m.usePattern(Pattern.compile("."))

        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(1)
        Assertions.assertThat(m.group()).isEqualTo("b")
    }

    @Test
    @DisplayName("usePattern returns this matcher")
    fun usePatternReturnsSelf() {
        val p1 = Pattern.compile("a")
        val p2 = Pattern.compile("b")
        val m = p1.matcher("ab")
        Assertions.assertThat<Matcher?>(m.usePattern(p2)).isSameAs(m)
    }

    @Test
    @DisplayName("usePattern with null throws")
    fun usePatternNullThrows() {
        val p = Pattern.compile("a")
        val m = p.matcher("a")
        Assertions.assertThatThrownBy(ThrowingCallable { m.usePattern(null) })
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("pattern() reflects usePattern change")
    fun patternReflectsChange() {
        val p1 = Pattern.compile("a")
        val p2 = Pattern.compile("b")
        val m = p1.matcher("ab")
        Assertions.assertThat<Pattern?>(m.pattern()).isSameAs(p1)
        m.usePattern(p2)
        Assertions.assertThat<Pattern?>(m.pattern()).isSameAs(p2)
    }

    @Test
    @DisabledForCrosscheck("SafeRE keeps group-zero access coherent after usePattern; JDK clears indexed group zero")
    @DisplayName("usePattern preserves coherent group zero and clears inner groups")
    fun usePatternPreservesCoherentGroupZeroAndClearsInnerGroups() {
        val p1 = Pattern.compile("(a)(b)")
        val p2 = Pattern.compile("(x)(y)(z)")
        val m = p1.matcher("ab")
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.hasMatch()).isTrue()
        Assertions.assertThat(m.group()).isEqualTo("ab")
        Assertions.assertThat(m.start()).isEqualTo(0)
        Assertions.assertThat(m.end()).isEqualTo(2)
        Assertions.assertThat(m.group(1)).isEqualTo("a")
        Assertions.assertThat(m.group(2)).isEqualTo("b")

        m.usePattern(p2)
        Assertions.assertThat(m.hasMatch()).isTrue()
        Assertions.assertThat(m.groupCount()).isEqualTo(3)
        Assertions.assertThat(m.start()).isZero()
        Assertions.assertThat(m.start(0)).isEqualTo(m.start())
        Assertions.assertThat(m.end()).isEqualTo(2)
        Assertions.assertThat(m.end(0)).isEqualTo(m.end())
        Assertions.assertThat(m.group()).isEqualTo("ab")
        Assertions.assertThat(m.group(0)).isEqualTo(m.group())
        Assertions.assertThat(m.start(1)).isEqualTo(-1)
        Assertions.assertThat(m.end(1)).isEqualTo(-1)
        Assertions.assertThat(m.group(1)).isNull()
        Assertions.assertThat(m.start(2)).isEqualTo(-1)
        Assertions.assertThat(m.end(2)).isEqualTo(-1)
        Assertions.assertThat(m.group(2)).isNull()
        Assertions.assertThat(m.start(3)).isEqualTo(-1)
        Assertions.assertThat(m.end(3)).isEqualTo(-1)
        Assertions.assertThat(m.group(3)).isNull()
    }
}
