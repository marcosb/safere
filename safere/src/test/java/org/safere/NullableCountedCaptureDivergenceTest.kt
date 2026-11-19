// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.regex.Pattern
import java.util.stream.Stream

/** Capture differences from Java under the minimum-respecting empty-stop policy.  */
@DisabledForCrosscheck("intentional divergence in nullable counted-repeat capture boundaries")
internal class NullableCountedCaptureDivergenceTest {
    @ParameterizedTest
    @MethodSource("captureCases")
    fun nullableRepeatCapturesFollowTheChosenSuccessfulPath(regex: String, diverges: Boolean) {
        for (prefix in arrayOf<String>(" ", "😀")) {
            val input = prefix + "y"
            for (operation in 0..2) {
                val actual = Pattern.compile(regex).matcher(input)
                val oracle = Pattern.compile(regex).matcher(input)
                val actualMatched =
                    when (operation) {
                        0 -> actual.find()
                        1 -> actual.matches()
                        else -> actual.lookingAt()
                    }
                val oracleMatched =
                    when (operation) {
                        0 -> oracle.find()
                        1 -> oracle.matches()
                        else -> oracle.lookingAt()
                    }
                Assertions.assertThat(actualMatched).`as`("/%s/ on %s", regex, input).isTrue()
                Assertions.assertThat(oracleMatched).isTrue()
                Assertions.assertThat(actual.start()).isZero()
                Assertions.assertThat(actual.end()).isEqualTo(input.length)
                Assertions.assertThat(oracle.start()).isEqualTo(actual.start())
                Assertions.assertThat(oracle.end()).isEqualTo(actual.end())
                Assertions.assertThat(actual.group(1)).isEmpty()
                Assertions.assertThat(oracle.group(1)).isEmpty()
                Assertions.assertThat(actual.start(1)).isEqualTo(if (diverges) 0 else prefix.length)
                Assertions.assertThat(actual.end(1)).isEqualTo(actual.start(1))
                Assertions.assertThat(oracle.start(1)).isEqualTo(prefix.length)
                Assertions.assertThat(oracle.end(1)).isEqualTo(oracle.start(1))
            }
        }
    }

    @Test
    fun reluctantOuterLoopsRetainTheirLastConsumingCapture() {
        for (outer in arrayOf<String>("*?", "+?")) {
            val regex = "^(?:(\\b|a){2,3})" + outer + "$"
            val matcher = org.safere.Pattern.compile(regex).matcher("aa")
            Assertions.assertThat(matcher.find()).`as`("/%s/", regex).isTrue()
            Assertions.assertThat(matcher.start()).isZero()
            Assertions.assertThat(matcher.end()).isEqualTo(2)
            // The first required iteration is empty; the next two consume a, exhausting the bound.
            // The reluctant outer loop can then stop without an extra empty capture at the end.
            Assertions.assertThat(matcher.group(1)).isEqualTo("a")
            Assertions.assertThat(matcher.start(1)).isEqualTo(1)
            Assertions.assertThat(matcher.end(1)).isEqualTo(2)
            val snapshot = matcher.toMatchResult()
            Assertions.assertThat(matcher.find()).isFalse()
            Assertions.assertThat(snapshot.group(1)).isEqualTo("a")
            Assertions.assertThat(snapshot.start(1)).isEqualTo(1)
            Assertions.assertThat(snapshot.end(1)).isEqualTo(2)
        }
    }

    @Test
    fun repeatedFindsAndSnapshotsRetainTheirOwnEmptyCapturePositions() {
        val matcher = org.safere.Pattern.compile("(?:x)?(?:()|\\W+){0,2}y").matcher("aca cybb yyaaaaa")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(5)
        Assertions.assertThat(matcher.start(1)).isEqualTo(5)
        val first = matcher.toMatchResult()
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(8)
        Assertions.assertThat(matcher.end()).isEqualTo(10)
        Assertions.assertThat(matcher.start(1)).isEqualTo(9)
        Assertions.assertThat(matcher.end(1)).isEqualTo(9)
        val second = matcher.toMatchResult()
        matcher.reset()
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(first.start(1)).isEqualTo(5)
        Assertions.assertThat(first.end(1)).isEqualTo(5)
        Assertions.assertThat(second.start(1)).isEqualTo(9)
        Assertions.assertThat(second.end(1)).isEqualTo(9)
    }

    companion object {
        private fun captureCases(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of("(?:()|\\W+){0,2}y", false),
                Arguments.of("(?:()|\\W+){1,2}y", false),
                Arguments.of("(?:(?<empty>)|\\W+){0,2}y", false),
                Arguments.of("(?:()|\\W+){2}y", true),
                Arguments.of("(?:()|\\W+)*y", false),
                Arguments.of("(?:\\W+|()){0,2}y", false)
            )
        }
    }
}
