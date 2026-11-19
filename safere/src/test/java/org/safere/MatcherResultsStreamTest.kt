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
import java.util.regex.MatchResult
import java.util.stream.Collectors

/** Tests for [Matcher.results].  */
internal class MatcherResultsStreamTest {
    @Test
    @DisplayName("results() returns all matches as a stream")
    fun resultsStream() {
        val p = Pattern.compile("\\d+")
        val m = p.matcher("abc 123 def 456 ghi")
        val matches = m.results().map<String?> { obj: MatchResult? -> obj!!.group() }.collect(Collectors.toList())
        Assertions.assertThat<String?>(matches).containsExactly("123", "456")
    }

    @Test
    @DisplayName("results() returns empty stream when no matches")
    fun resultsNoMatch() {
        val p = Pattern.compile("\\d+")
        val m = p.matcher("no digits here")
        Assertions.assertThat(m.results().count()).isEqualTo(0)
    }

    @Test
    @DisplayName("results() match results have correct positions")
    fun resultsPositions() {
        val p = Pattern.compile("[a-z]+")
        val m = p.matcher("123 abc 456 def")
        val results = m.results().collect(Collectors.toList())
        Assertions.assertThat<MatchResult?>(results).hasSize(2)
        Assertions.assertThat(results.get(0)!!.start()).isEqualTo(4)
        Assertions.assertThat(results.get(0)!!.end()).isEqualTo(7)
        Assertions.assertThat(results.get(0)!!.hasMatch()).isTrue()
        Assertions.assertThat(results.get(1)!!.start()).isEqualTo(12)
        Assertions.assertThat(results.get(1)!!.end()).isEqualTo(15)
        Assertions.assertThat(results.get(1)!!.hasMatch()).isTrue()
    }

    @Test
    @DisabledForCrosscheck(
        ("callback mutation is covered by MatcherStateMachineTraceTest; generated wrapper cannot "
                + "bind mutation separately to each engine")
    )
    @DisplayName("results() detects matcher mutation during stream traversal")
    fun resultsDetectsMatcherMutation() {
        val p = Pattern.compile("a")
        val m = p.matcher("aa")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                m.results()
                    .map<String?> { result: MatchResult? ->
                        m.find()
                        result!!.group()
                    }
                    .collect(Collectors.toList())
            })
            .isInstanceOf(ConcurrentModificationException::class.java)
    }
}
