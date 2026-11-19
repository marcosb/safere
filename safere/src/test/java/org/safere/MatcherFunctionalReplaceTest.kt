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
import java.util.function.Function
import java.util.regex.MatchResult

/** Tests for [Matcher] functional replacement methods.  */
internal class MatcherFunctionalReplaceTest {
    @Test
    @DisplayName("replaceAll(Function) expands group references in returned replacement")
    fun replaceAllFunctionExpandsGroupReferences() {
        val p = Pattern.compile("(\\w+)")
        val m = p.matcher("hello world")
        Assertions.assertThat(m.replaceAll(Function { result: MatchResult? -> "[$1]" })).isEqualTo("[hello] [world]")
    }

    @Test
    @DisplayName("replaceFirst(Function) expands group references in returned replacement")
    fun replaceFirstFunctionExpandsGroupReferences() {
        val p = Pattern.compile("(\\w+)")
        val m = p.matcher("hello world")
        Assertions.assertThat(m.replaceFirst(Function { result: MatchResult? -> "[$1]" })).isEqualTo("[hello] world")
    }

    @Test
    @DisplayName("replaceAll(Function) rejects null replacement result")
    fun replaceAllFunctionRejectsNullReplacementResult() {
        val p = Pattern.compile("a")
        val m = p.matcher("a")
        Assertions.assertThatThrownBy(ThrowingCallable { m.replaceAll(Function { result: MatchResult? -> null }) })
            .isInstanceOf(
                NullPointerException::class.java
            )
    }

    @Test
    @DisplayName("replaceFirst(Function) rejects null replacement result")
    fun replaceFirstFunctionRejectsNullReplacementResult() {
        val p = Pattern.compile("a")
        val m = p.matcher("a")
        Assertions.assertThatThrownBy(ThrowingCallable { m.replaceFirst(Function { result: MatchResult? -> null }) })
            .isInstanceOf(NullPointerException::class.java)
    }

    @Test
    @DisplayName("replaceAll(Function) replaces all matches using function")
    fun replaceAllFunction() {
        val p = Pattern.compile("\\d+")
        val m = p.matcher("a1b22c333")
        val result = m.replaceAll(Function { mr: MatchResult? -> "[" + mr!!.group() + "]" })
        Assertions.assertThat(result).isEqualTo("a[1]b[22]c[333]")
    }

    @Test
    @DisplayName("replaceFirst(Function) replaces only first match using function")
    fun replaceFirstFunction() {
        val p = Pattern.compile("\\d+")
        val m = p.matcher("a1b22c333")
        val result = m.replaceFirst(Function { mr: MatchResult? -> "[" + mr!!.group() + "]" })
        Assertions.assertThat(result).isEqualTo("a[1]b22c333")
    }

    @Test
    @DisplayName("replaceAll(Function) with no matches returns original")
    fun replaceAllFunctionNoMatch() {
        val p = Pattern.compile("\\d+")
        val m = p.matcher("no digits")
        val result = m.replaceAll(Function { mr: MatchResult? -> "X" })
        Assertions.assertThat(result).isEqualTo("no digits")
    }

    @Test
    @DisplayName("replaceFirst(Function) with no matches returns original")
    fun replaceFirstFunctionNoMatch() {
        val p = Pattern.compile("\\d+")
        val m = p.matcher("no digits")
        val result = m.replaceFirst(Function { mr: MatchResult? -> "X" })
        Assertions.assertThat(result).isEqualTo("no digits")
    }

    @Test
    @DisplayName("replaceAll(Function) can access capture groups")
    fun replaceAllFunctionWithGroups() {
        val p = Pattern.compile("(\\w+)=(\\w+)")
        val m = p.matcher("a=1 b=2")
        val result = m.replaceAll(Function { mr: MatchResult? -> mr!!.group(2) + ":" + mr.group(1) })
        Assertions.assertThat(result).isEqualTo("1:a 2:b")
    }

    @Test
    @DisplayName("replaceAll(Function) throws on null replacer")
    fun replaceAllFunctionNullThrows() {
        val p = Pattern.compile("x")
        val m = p.matcher("x")
        Assertions.assertThatThrownBy(ThrowingCallable { m.replaceAll(null as Function<MatchResult?, String?>?) })
            .isInstanceOf(NullPointerException::class.java)
    }

    @Test
    @DisplayName("replaceFirst(Function) throws on null replacer")
    fun replaceFirstFunctionNullThrows() {
        val p = Pattern.compile("x")
        val m = p.matcher("x")
        Assertions.assertThatThrownBy(ThrowingCallable { m.replaceFirst(null as Function<MatchResult?, String?>?) })
            .isInstanceOf(NullPointerException::class.java)
    }

    @Test
    @DisabledForCrosscheck(
        ("callback mutation is covered by MatcherStateMachineTraceTest; generated wrapper cannot "
                + "bind mutation separately to each engine")
    )
    @DisplayName("replaceAll(Function) detects matcher mutation from replacer")
    fun replaceAllFunctionDetectsMatcherMutation() {
        val p = Pattern.compile("a")
        val m = p.matcher("aa")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                m.replaceAll(
                    Function { result: MatchResult? ->
                        m.find()
                        "x"
                    })
            })
            .isInstanceOf(ConcurrentModificationException::class.java)
    }

    @Test
    @DisabledForCrosscheck(
        ("callback mutation is covered by MatcherStateMachineTraceTest; generated wrapper cannot "
                + "bind mutation separately to each engine")
    )
    @DisplayName("replaceFirst(Function) detects matcher mutation from replacer")
    fun replaceFirstFunctionDetectsMatcherMutation() {
        val p = Pattern.compile("a")
        val m = p.matcher("aa")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                m.replaceFirst(
                    Function { result: MatchResult? ->
                        m.find()
                        "x"
                    })
            })
            .isInstanceOf(ConcurrentModificationException::class.java)
    }
}
