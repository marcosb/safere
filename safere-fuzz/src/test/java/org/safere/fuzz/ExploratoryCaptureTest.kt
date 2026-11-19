// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.safere.Pattern
import org.safere.fuzz.FuzzSupport.MatcherPair
import java.util.function.Function
import java.util.regex.MatchResult

/** Intentional reproductions are model tests, not passing differential seeds.  */
internal class ExploratoryCaptureTest {
    @Test
    fun failedStartCapturesFollowSafeReModelAndRemainExploratoryFindings() {
        for (regex in arrayOf<String>("(?:(a){1})*$", "(?:(?<item>a){2})*$")) {
            val matcher = Pattern.compile(regex).matcher("aab")
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(3)
            Assertions.assertThat(matcher.group(1)).isNull()
            Assertions.assertThat(matcher.start(1)).isEqualTo(-1)
            Assertions.assertThat(matcher.end(1)).isEqualTo(-1)
            Assertions.assertThatThrownBy(ThrowingCallable {
                FuzzSupport.compileOrSkip(regex, 0).matcher("aab").find()
            })
                .isInstanceOf(AssertionError::class.java)
                .hasMessageContaining("divergence")
        }
    }

    @Test
    fun sameStartCaptureResidueRemainsAVisibleFinding() {
        val regex = "(?:(?:())*{0}{1}?{1}|a)."
        val matcher = Pattern.compile(regex).matcher("ab")
        Assertions.assertThat(matcher.matches()).isTrue()
        Assertions.assertThat(matcher.group(1)).isNull()
        Assertions.assertThatThrownBy(ThrowingCallable { FuzzSupport.compileOrSkip(regex, 0).matcher("ab").matches() })
            .isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun missingQuantifiedCaptureIsNoLongerABlindSpot() {
        val pair =
            MatcherPair(
                "(a)?b$",
                0,
                "ab",
                Pattern.compile("()??ab$").matcher("ab"),
                java.util.regex.Pattern.compile("(a)?b$").matcher("ab")
            )
        Assertions.assertThatThrownBy(ThrowingCallable { pair.matches() }).isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun replacementDifferencesAreNotWaived() {
        val regex = "(?:(?<item>a){1})*$"
        Assertions.assertThat(Pattern.compile(regex).matcher("ab").replaceAll("\${item}"))
            .isEqualTo("ab")
        Assertions.assertThatThrownBy(
            ThrowingCallable { FuzzSupport.compileOrSkip(regex, 0).matcher("ab").replaceAll("\${item}") })
            .isInstanceOf(AssertionError::class.java)
        Assertions.assertThatThrownBy(
            ThrowingCallable { FuzzSupport.compileOrSkip(regex, 0).matcher("ab").replaceFirst("\${item}") })
            .isInstanceOf(AssertionError::class.java)
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                FuzzSupport.compileOrSkip(regex, 0)
                    .matcher("ab")
                    .replaceAll(Function { result: MatchResult? -> if (result!!.group(1) == null) "empty" else "captured" })
            })
            .isInstanceOf(AssertionError::class.java)
    }
}
