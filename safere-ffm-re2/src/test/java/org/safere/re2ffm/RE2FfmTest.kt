// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.re2ffm

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.safere.crosscheck.Pattern.Companion.compile
import org.safere.crosscheck.Pattern.matcher
import java.util.regex.Pattern

internal class RE2FfmTest {
    @Test
    fun testSimpleSplit() {
        val p: RE2FfmPattern = compile(",")
        val parts = p.split("a,b,c")
        Assertions.assertThat<String?>(parts).isEqualTo(arrayOf<String>("a", "b", "c"))
    }

    @Test
    fun testSplitLimit() {
        val p: RE2FfmPattern = compile(",")
        val parts = p.split("a,b,c", 2)
        Assertions.assertThat<String?>(parts).isEqualTo(arrayOf<String>("a", "b,c"))
    }

    @Test
    fun testNonAsciiMapping() {
        // "hello, \u4e16\u754c" ("hello, 世界")
        // "世界" starts at char offset 7.
        // "世" in UTF-8 is 3 bytes: \xE4\xB8\x96.
        // "界" in UTF-8 is 3 bytes: \xE5\x85\x8C.
        val p: RE2FfmPattern = compile("世界")
        val m = p.matcher("hello, 世界!")

        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(7)
        Assertions.assertThat(m.end()).isEqualTo(9)
        Assertions.assertThat(m.group()).isEqualTo("世界")
    }

    @Test
    fun testSurrogatePairs() {
        // \uD83D\uDE00 is the emoji grinning face 😀 (1 character point, 2 Java chars, 4 UTF-8 bytes)
        val text = "hello \uD83D\uDE00 world"
        val p: RE2FfmPattern = compile("\uD83D\uDE00")
        val m = p.matcher(text)

        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(6)
        Assertions.assertThat(m.end()).isEqualTo(8)
        Assertions.assertThat(m.group()).isEqualTo("\uD83D\uDE00")

        // Split around surrogate
        val parts = p.split(text)
        Assertions.assertThat<String?>(parts).isEqualTo(arrayOf<String>("hello ", " world"))
    }

    @Test
    fun testReplaceAll() {
        val p: RE2FfmPattern = compile("(\\w+)")
        Assertions.assertThat(p.matcher("hello world").replaceAll("X")).isEqualTo("X X")
        Assertions.assertThat(p.matcher("hello world").replaceAll("[$1]")).isEqualTo("[hello] [world]")
    }

    @Test
    fun testReplaceFirst() {
        val p: RE2FfmPattern = compile("(\\w+)")
        Assertions.assertThat(p.matcher("hello world").replaceFirst("X")).isEqualTo("X world")
        Assertions.assertThat(p.matcher("hello world").replaceFirst("[$1]")).isEqualTo("[hello] world")
    }

    @Test
    fun testReplaceAllMultiDigitGroup() {
        val p: RE2FfmPattern = compile("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)")
        Assertions.assertThat(p.matcher("xabcdefghijy").replaceAll("$10")).isEqualTo("xjy")
    }

    @Test
    fun testReplaceFirstMultiDigitGroup() {
        val p: RE2FfmPattern = compile("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)")
        Assertions.assertThat(p.matcher("xabcdefghijy").replaceFirst("$10")).isEqualTo("xjy")
    }

    @Test
    fun testReplaceAllStateDiscrepancy() {
        val p: RE2FfmPattern = compile("a")
        val m = p.matcher("aba")
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.group()).isEqualTo("a")

        Assertions.assertThat(m.replaceAll("x")).isEqualTo("xbx")

        Assertions.assertThatThrownBy(ThrowingCallable { m.group() }).isInstanceOf(IllegalStateException::class.java)

        Assertions.assertThat(m.find()).isFalse()
    }

    @Test
    fun testReplaceAllStateDiscrepancyJavaFallback() {
        val p: RE2FfmPattern = compile("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)")
        val m = p.matcher("xabcdefghijy")
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.group()).isEqualTo("abcdefghij")

        Assertions.assertThat(m.replaceAll("x")).isEqualTo("xxy")

        Assertions.assertThatThrownBy(ThrowingCallable { m.group() }).isInstanceOf(IllegalStateException::class.java)

        Assertions.assertThat(m.find()).isFalse()
    }

    @Test
    fun testReplacementReferencesMatchJdk() {
        val regex = "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)"
        val input = "abcdefghij-abcdefghij"
        val replacements = arrayOf<String?>("$10", "$11", "$101", "$00", "$01", "$09", "\\$1", "\\\\$1")
        val actualPattern: RE2FfmPattern = compile(regex)
        val expectedPattern = Pattern.compile(regex)

        for (replacement in replacements) {
            Assertions.assertThat(actualPattern.matcher(input).replaceFirst(replacement))
                .`as`(replacement)
                .isEqualTo(expectedPattern.matcher(input).replaceFirst(replacement))
            Assertions.assertThat(actualPattern.matcher(input).replaceAll(replacement))
                .`as`(replacement)
                .isEqualTo(expectedPattern.matcher(input).replaceAll(replacement))
        }
    }

    @Test
    fun testZeroWidthUnicodeReplacementMatchesJdk() {
        val regex = "()()()()()()()()()()"
        val input = "é"
        val actualPattern: RE2FfmPattern = compile(regex)
        val expectedPattern = Pattern.compile(regex)

        Assertions.assertThat(actualPattern.matcher(input).replaceAll("x"))
            .isEqualTo(expectedPattern.matcher(input).replaceAll("x"))
    }

    @Test
    fun testReplaceAllExhaustsTerminalEmptyMatch() {
        val matcher: RE2FfmMatcher = compile("$").matcher("text")

        Assertions.assertThat(matcher.replaceAll("x")).isEqualTo("textx")

        Assertions.assertThat(matcher.find()).isFalse()
    }
}
