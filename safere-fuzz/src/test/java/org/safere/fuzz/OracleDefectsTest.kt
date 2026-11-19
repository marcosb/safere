// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

internal class OracleDefectsTest {
    @Test
    fun usePatternCanaryRequiresRemovalWhenJdkIsFixed() {
        val jdk = Pattern.compile("a").matcher("a")
        Assertions.assertThat(jdk.find()).isTrue()
        Assertions.assertThat(OracleDefects.clearedGroupsWithLiveMatch(jdk)).isFalse()
        jdk.usePattern(Pattern.compile("b"))
        Assertions.assertThat(OracleDefects.clearedGroupsWithLiveMatch(jdk))
            .`as`("JDK-8390449 changed: review/remove observed-state exception")
            .isTrue()
    }

    @Test
    fun exhaustedFindCanaryRequiresReviewWhenJdkIsFixed() {
        val jdk = Pattern.compile("a*").matcher("ba")
        jdk.replaceAll("-")
        Assertions.assertThat(OracleDefects.exhaustedEmptyMatch(jdk))
            .`as`("SafeRE #931/#933 changed: review exhausted-state exception")
            .isTrue()
        jdk.reset()
        Assertions.assertThat(OracleDefects.exhaustedEmptyMatch(jdk)).isFalse()
    }

    @Test
    fun failedInitialOperationsRemainInTheStrictResetDomain() {
        for (regex in arrayOf<String>(
            "a", "(?:)a", "(?:)?a", "x*a", "(?:)*a", "(?:){0,2}a", "(?:(?:){0,2})(?:a)"
        )) {
            Assertions.assertThat(StrictStateChecks.check(regex, "ab", 1, 2, true, false)).isTrue()
        }
        Assertions.assertThat(
            StrictStateChecks.check(
                "(?:[a-c]|(?:(?:.)+)(?:(?:b|\\.)))", "\udc00a", 0, 2, true, false
            )
        )
            .isTrue()
    }

    @Test
    fun initialFailureCanaryShowsWhyStrictContinuationResets() {
        val jdk = Pattern.compile("(?:){0,2}a").matcher("ab")
        Assertions.assertThat(jdk.find(1)).isFalse()
        Assertions.assertThat(jdk.find())
            .`as`("SafeRE #818 changed: review strict reset-domain restriction")
            .isFalse()
        Assertions.assertThat(jdk.reset().find()).isTrue()
        Assertions.assertThat(jdk.start()).isZero()
    }

    @Test
    fun stateSequencesCoverSurrogatesRegionsAndReplacement() {
        for (regex in arrayOf<String>(".|", "a*", "(?:a|b)+", ".", "[^a]")) {
            for (input in arrayOf<String>("", "a", "é中", "\udb7d\udf02A", "\ud800x\udc00")) {
                for (start in 0..input.size) {
                    Assertions.assertThat(StrictStateChecks.check(regex, input, start, input.size, true, false))
                        .isTrue()
                }
            }
        }
    }
}
