// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Tests for [Pattern.LITERAL] flag behavior. When LITERAL is set, all metacharacters are
 * treated as literal characters and the pattern is not parsed as a regular expression.
 */
internal class LiteralFlagTest {
    @Test
    fun dotIsLiteral() {
        val p = Pattern.compile("a.b", Pattern.LITERAL)
        Assertions.assertThat(p.matcher("a.b").matches()).isTrue()
        Assertions.assertThat(p.matcher("axb").matches()).isFalse()
    }

    @Test
    fun starIsLiteral() {
        val p = Pattern.compile("a*", Pattern.LITERAL)
        Assertions.assertThat(p.matcher("a*").matches()).isTrue()
        Assertions.assertThat(p.matcher("aaa").matches()).isFalse()
        Assertions.assertThat(p.matcher("").matches()).isFalse()
    }

    @Test
    fun plusIsLiteral() {
        val p = Pattern.compile("a+", Pattern.LITERAL)
        Assertions.assertThat(p.matcher("a+").matches()).isTrue()
        Assertions.assertThat(p.matcher("aaa").matches()).isFalse()
    }

    @Test
    fun questionMarkIsLiteral() {
        val p = Pattern.compile("a?", Pattern.LITERAL)
        Assertions.assertThat(p.matcher("a?").matches()).isTrue()
        Assertions.assertThat(p.matcher("a").matches()).isFalse()
        Assertions.assertThat(p.matcher("").matches()).isFalse()
    }

    @Test
    fun bracketsAreLiteral() {
        val p = Pattern.compile("[abc]", Pattern.LITERAL)
        Assertions.assertThat(p.matcher("[abc]").matches()).isTrue()
        Assertions.assertThat(p.matcher("a").matches()).isFalse()
    }

    @Test
    fun parenthesesAreLiteral() {
        val p = Pattern.compile("(abc)", Pattern.LITERAL)
        Assertions.assertThat(p.matcher("(abc)").matches()).isTrue()
        Assertions.assertThat(p.matcher("abc").matches()).isFalse()
    }

    @Test
    fun pipeIsLiteral() {
        val p = Pattern.compile("a|b", Pattern.LITERAL)
        Assertions.assertThat(p.matcher("a|b").matches()).isTrue()
        Assertions.assertThat(p.matcher("a").matches()).isFalse()
        Assertions.assertThat(p.matcher("b").matches()).isFalse()
    }

    @Test
    fun caretIsLiteral() {
        val p = Pattern.compile("^abc", Pattern.LITERAL)
        Assertions.assertThat(p.matcher("^abc").matches()).isTrue()
        Assertions.assertThat(p.matcher("abc").matches()).isFalse()
    }

    @Test
    fun dollarIsLiteral() {
        val p = Pattern.compile("abc$", Pattern.LITERAL)
        Assertions.assertThat(p.matcher("abc$").matches()).isTrue()
        Assertions.assertThat(p.matcher("abc").matches()).isFalse()
    }

    @Test
    fun backslashNIsLiteral() {
        // Under LITERAL, \n is a literal backslash followed by 'n', not a newline.
        val p = Pattern.compile("\\n", Pattern.LITERAL)
        Assertions.assertThat(p.matcher("\\n").matches()).isTrue()
        Assertions.assertThat(p.matcher("\n").matches()).isFalse()
    }

    @Test
    fun backslashTIsLiteral() {
        val p = Pattern.compile("\\t", Pattern.LITERAL)
        Assertions.assertThat(p.matcher("\\t").matches()).isTrue()
        Assertions.assertThat(p.matcher("\t").matches()).isFalse()
    }

    @Test
    fun curlyBracesAreLiteral() {
        val p = Pattern.compile("a{2,3}", Pattern.LITERAL)
        Assertions.assertThat(p.matcher("a{2,3}").matches()).isTrue()
        Assertions.assertThat(p.matcher("aa").matches()).isFalse()
        Assertions.assertThat(p.matcher("aaa").matches()).isFalse()
    }

    @Test
    fun literalWithCaseInsensitive() {
        val p = Pattern.compile("Hello.World", Pattern.LITERAL or Pattern.CASE_INSENSITIVE)
        Assertions.assertThat(p.matcher("Hello.World").matches()).isTrue()
        Assertions.assertThat(p.matcher("hello.world").matches()).isTrue()
        Assertions.assertThat(p.matcher("HELLO.WORLD").matches()).isTrue()
        Assertions.assertThat(p.matcher("HelloXWorld").matches()).isFalse()
    }

    @Test
    fun literalWithComments() {
        // LITERAL takes precedence over COMMENTS — whitespace is preserved.
        val p = Pattern.compile("a b # comment", Pattern.LITERAL or Pattern.COMMENTS)
        Assertions.assertThat(p.matcher("a b # comment").matches()).isTrue()
        Assertions.assertThat(p.matcher("ab").matches()).isFalse()
    }

    @Test
    fun emptyLiteralPattern() {
        val p = Pattern.compile("", Pattern.LITERAL)
        Assertions.assertThat(p.matcher("").matches()).isTrue()
        Assertions.assertThat(p.matcher("a").find()).isTrue() // empty pattern finds everywhere
    }

    @Test
    fun unicodeCharsInLiteral() {
        val p = Pattern.compile("café☕", Pattern.LITERAL)
        Assertions.assertThat(p.matcher("café☕").matches()).isTrue()
        Assertions.assertThat(p.matcher("cafe☕").matches()).isFalse()
    }

    @Test
    fun findWithLiteral() {
        val p = Pattern.compile("a.b", Pattern.LITERAL)
        val m = p.matcher("xa.bya.bz")
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.group()).isEqualTo("a.b")
        Assertions.assertThat(m.start()).isEqualTo(1)
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(5)
        Assertions.assertThat(m.find()).isFalse()
    }

    @Test
    fun replaceAllWithLiteral() {
        val p = Pattern.compile("a+b", Pattern.LITERAL)
        Assertions.assertThat(p.matcher("xa+by").replaceAll("Z")).isEqualTo("xZy")
        Assertions.assertThat(p.matcher("xaaby").replaceAll("Z")).isEqualTo("xaaby")
    }

    @Test
    fun splitWithLiteral() {
        val p = Pattern.compile(".", Pattern.LITERAL)
        val parts = p.split("a.b.c")
        Assertions.assertThat<String?>(parts).containsExactly("a", "b", "c")
    }

    @Test
    fun backslashDIsLiteral() {
        // \d under LITERAL is literal backslash + d, not a digit class.
        val p = Pattern.compile("\\d", Pattern.LITERAL)
        Assertions.assertThat(p.matcher("\\d").matches()).isTrue()
        Assertions.assertThat(p.matcher("5").matches()).isFalse()
    }

    @Test
    fun replaceAllWithLiteralCaseInsensitive() {
        val p = Pattern.compile("Foo", Pattern.LITERAL or Pattern.CASE_INSENSITIVE)
        val m = p.matcher("foo FOO Foo fOO bar")
        Assertions.assertThat(m.replaceAll("baz")).isEqualTo("baz baz baz baz bar")
        Assertions.assertThat(m.find()).isFalse()
    }

    @Test
    fun replaceFirstWithLiteralCaseInsensitive() {
        val p = Pattern.compile("Foo", Pattern.LITERAL or Pattern.CASE_INSENSITIVE)
        val m = p.matcher("foo FOO Foo")
        Assertions.assertThat(m.replaceFirst("baz")).isEqualTo("baz FOO Foo")
        Assertions.assertThat(m.start()).isEqualTo(0)
        Assertions.assertThat(m.end()).isEqualTo(3)
        Assertions.assertThat(m.group()).isEqualTo("foo")
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.group()).isEqualTo("FOO")
        Assertions.assertThat(m.start()).isEqualTo(4)
        Assertions.assertThat(m.end()).isEqualTo(7)
    }

    @Test
    fun replaceAllWithLiteralGroupZeroReference() {
        val p = Pattern.compile("foo", Pattern.LITERAL or Pattern.CASE_INSENSITIVE)
        val m = p.matcher("Foo FOO")
        Assertions.assertThat(m.replaceAll("[$0]")).isEqualTo("[Foo] [FOO]")
    }

    @Test
    fun splitWithLiteralCaseInsensitive() {
        val p = Pattern.compile("x", Pattern.LITERAL or Pattern.CASE_INSENSITIVE)
        val parts = p.split("aXbxcXd")
        Assertions.assertThat<String?>(parts).containsExactly("a", "b", "c", "d")
    }

    @Test
    fun splitWithDelimitersLiteralCaseInsensitive() {
        val p = Pattern.compile("SEP", Pattern.LITERAL or Pattern.CASE_INSENSITIVE)
        val parts = p.splitWithDelimiters("aSepBsepC", 0)
        Assertions.assertThat<String?>(parts).containsExactly("a", "Sep", "B", "sep", "C")
    }

    @Test
    fun matcherLifecycleStatePreservation() {
        val p = Pattern.compile("abc", Pattern.CASE_INSENSITIVE)
        val m = p.matcher("123 ABC 456 abc 789")
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(4)
        Assertions.assertThat(m.end()).isEqualTo(7)
        Assertions.assertThat(m.group()).isEqualTo("ABC")

        m.reset()
        Assertions.assertThat(m.replaceFirst("XYZ")).isEqualTo("123 XYZ 456 abc 789")
        Assertions.assertThat(m.start()).isEqualTo(4)
        Assertions.assertThat(m.end()).isEqualTo(7)
        Assertions.assertThat(m.group()).isEqualTo("ABC")

        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(12)
        Assertions.assertThat(m.end()).isEqualTo(15)
        Assertions.assertThat(m.group()).isEqualTo("abc")

        Assertions.assertThat(m.find()).isFalse()
    }

    @Test
    fun singleCharLiteralCaseInsensitive() {
        val p = Pattern.compile("a", Pattern.CASE_INSENSITIVE)
        val m = p.matcher("A b a B A")
        Assertions.assertThat(m.replaceAll("x")).isEqualTo("x b x B x")
    }
}
