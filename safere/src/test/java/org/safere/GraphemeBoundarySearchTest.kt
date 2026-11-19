// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Candidate-start policy for scalar programs containing grapheme-boundary assertions.  */
internal class GraphemeBoundarySearchTest {
    @ParameterizedTest
    @ValueSource(strings = ["*", "*?", "+", "+?"])
    fun leadingRepetitionPreservesExplicitLowSurrogateCursor(quantifier: String) {
        val regex = "(\\uDC4D" + quantifier + ")([ab])(\\b{g})"
        val matcher = Pattern.compile(regex).matcher("\uD83D\uDC4Da\uD83D\uDC4Db")
        Assertions.assertThat(matcher.find(1)).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(1)
        Assertions.assertThat(matcher.end()).isEqualTo(3)
        Assertions.assertThat(matcher.group(1)).isEqualTo("\uDC4D")
        Assertions.assertThat(matcher.start(1)).isEqualTo(1)
        Assertions.assertThat(matcher.end(1)).isEqualTo(2)
        Assertions.assertThat(matcher.group(2)).isEqualTo("a")
        Assertions.assertThat(matcher.group(3)).isEmpty()

        // Later candidates must still advance by code point past the intact second pair.
        val nullable = quantifier.startsWith("*")
        Assertions.assertThat(matcher.find()).isEqualTo(nullable)
        if (nullable) {
            Assertions.assertThat(matcher.start()).isEqualTo(5)
            Assertions.assertThat(matcher.end()).isEqualTo(6)
            Assertions.assertThat(matcher.group(1)).isEmpty()
            Assertions.assertThat(matcher.group(2)).isEqualTo("b")
            Assertions.assertThat(matcher.find()).isFalse()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["*", "*?", "+", "+?"])
    fun leadingRepetitionPreservesLowSurrogateRegionStarts(quantifier: String) {
        val regex = "(\\uDC4D" + quantifier + ")([ab])(\\b{g})"
        for (transparent in booleanArrayOf(false, true)) {
            for (anchoring in booleanArrayOf(false, true)) {
                val matcher =
                    Pattern.compile(regex)
                        .matcher("\uD83D\uDC4Da\uD83D\uDC4Db")
                        .region(1, 5)
                        .useTransparentBounds(transparent)
                        .useAnchoringBounds(anchoring)
                Assertions.assertThat(matcher.find()).isTrue()
                Assertions.assertThat(matcher.start()).isEqualTo(1)
                Assertions.assertThat(matcher.end()).isEqualTo(3)
                Assertions.assertThat(matcher.group(1)).isEqualTo("\uDC4D")
                Assertions.assertThat(matcher.group(2)).isEqualTo("a")
                Assertions.assertThat(matcher.group(3)).isEmpty()
                Assertions.assertThat(matcher.find()).isFalse()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["\\p{Cs}", "[\\uDC00-\\uDFFF]", "\\uDC4D"])
    fun trailingBoundaryDoesNotIntroduceStartsInsideSurrogatePairs(scalar: String?) {
        val input = "\uD83D\uDC4D\uD83D\uDC4D"
        val matcher = Pattern.compile("(" + scalar + ")(\\b{g})").matcher(input)
        Assertions.assertThat(matcher.find()).isFalse()

        // An explicitly requested UTF-16 search position can still begin at a low surrogate.
        Assertions.assertThat(matcher.find(1)).isTrue()
        Assertions.assertThat(matcher.start(1)).isEqualTo(1)
        Assertions.assertThat(matcher.end(1)).isEqualTo(2)
        Assertions.assertThat(matcher.start(2)).isEqualTo(2)
        Assertions.assertThat(matcher.end(2)).isEqualTo(2)
        Assertions.assertThat(matcher.find()).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["\\p{Cs}", "[\\uDC00-\\uDFFF]", "\\uDC4D"])
    fun trailingBoundaryPreservesStandaloneSurrogatesAndRegionStarts(scalar: String) {
        val regex = "(" + scalar + ")(\\b{g})"
        var matcher = Pattern.compile(regex).matcher("\uD83D\uDC4D\uDC4D")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(2)
        Assertions.assertThat(matcher.end()).isEqualTo(3)
        Assertions.assertThat(matcher.group(1)).isEqualTo("\uDC4D")
        Assertions.assertThat(matcher.group(2)).isEmpty()
        Assertions.assertThat(matcher.find()).isFalse()

        for (transparent in booleanArrayOf(false, true)) {
            matcher =
                Pattern.compile(regex)
                    .matcher("\uD83D\uDC4D\uD83D\uDC4D")
                    .region(1, 4)
                    .useTransparentBounds(transparent)
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(1)
            Assertions.assertThat(matcher.end()).isEqualTo(2)
            Assertions.assertThat(matcher.group(1)).isEqualTo("\uDC4D")
            Assertions.assertThat(matcher.group(2)).isEmpty()
            Assertions.assertThat(matcher.find()).isFalse()
        }
    }
}
