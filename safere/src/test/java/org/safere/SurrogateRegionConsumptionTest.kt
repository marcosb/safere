// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

@DisplayName("Surrogate consumption at region boundaries")
internal class SurrogateRegionConsumptionTest {
    @JvmRecord
    internal data class Case(
        val regex: String?,
        val input: String?,
        val start: Int,
        val end: Int,
        val expected: Boolean
    )      {
        override fun toString(): String {
            return regex + " on " + input!!.length + " code units, region [" + start + "," + end + ")"
        }
    }

    @ParameterizedTest
    @MethodSource
    @DisabledForCrosscheck("Region-local scalar decoding intentionally differs from JDK")
    fun ordinaryAtomsDecodeWithinTheRegion(c: Case) {
        val pattern = Pattern.compile(c.regex)
        for (transparent in booleanArrayOf(false, true)) {
            Assertions.assertThat(
                pattern
                    .matcher(c.input)
                    .region(c.start, c.end)
                    .useTransparentBounds(transparent)
                    .matches()
            )
                .`as`("matches: %s, transparent=%s", c, transparent)
                .isEqualTo(c.expected)

            if (c.expected) {
                val lookingAt =
                    pattern.matcher(c.input).region(c.start, c.end).useTransparentBounds(transparent)
                Assertions.assertThat(lookingAt.lookingAt()).`as`("lookingAt: %s", c).isTrue()
                Assertions.assertThat(lookingAt.start()).isEqualTo(c.start)
                Assertions.assertThat(lookingAt.end()).isEqualTo(c.end)

                val find =
                    pattern.matcher(c.input).region(c.start, c.end).useTransparentBounds(transparent)
                Assertions.assertThat(find.find()).`as`("find: %s", c).isTrue()
                Assertions.assertThat(find.start()).isEqualTo(c.start)
                Assertions.assertThat(find.end()).isEqualTo(c.end)
            }
        }
    }

    @Test
    @DisabledForCrosscheck("Opaque region decoding intentionally differs from JDK")
    fun unicodeWordBoundariesUseTheExposedSurrogateCategory() {
        val pair = "\uD801\uDC00" // A supplementary letter in the full input.
        for (input in arrayOf<String>(pair, "x" + pair)) {
            val start = input.length - 2
            Assertions.assertThat(Pattern.compile("(?U)\\b.").matcher(input).region(start, start + 1).matches())
                .isFalse()
            Assertions.assertThat(Pattern.compile("(?U)\\B.").matcher(input).region(start, start + 1).matches())
                .isTrue()
            Assertions.assertThat(Pattern.compile("(?U)\\b.").matcher("\uD801").matches()).isFalse()
            Assertions.assertThat(Pattern.compile("(?U)\\B.").matcher("\uD801").matches()).isTrue()
        }
    }

    @Test
    @DisabledForCrosscheck("Region-local ordinary atoms intentionally differ from JDK")
    fun ordinaryAlternativesRemainRegionLocalWithGraphemeConstructs() {
        val pair = "\uD83D\uDC4D"
        for (regex in arrayOf<String>("(?:\\X|.)", "(?:.|\\X)", "(?:\\X|[\\s\\S])", "(?:\\b{g}|.)")) {
            Assertions.assertThat(Pattern.compile(regex).matcher(pair).region(0, 1).matches())
                .`as`("%s on the split region", regex)
                .isTrue()
            Assertions.assertThat(Pattern.compile(regex).matcher("\uD83D").matches())
                .`as`("%s on the corresponding substring", regex)
                .isTrue()
        }
    }

    @Test
    @DisabledForCrosscheck("Region-local ordinary atoms intentionally differ from JDK")
    fun firstOrdinaryAlternativePrecedesCompletedGraphemeMatch() {
        val pattern = Pattern.compile("(.|(\\X))")
        val pair = "\uD83D\uDC4D"
        for (find in booleanArrayOf(false, true)) {
            val matcher = pattern.matcher(pair).region(0, 1)
            Assertions.assertThat(if (find) matcher.find() else matcher.lookingAt()).isTrue()
            Assertions.assertThat(matcher.start()).isZero()
            Assertions.assertThat(matcher.end()).isEqualTo(1)
            Assertions.assertThat(matcher.group(1)).isEqualTo("\uD83D")
            Assertions.assertThat(matcher.group(2)).isNull()
        }
    }

    companion object {
        fun ordinaryAtomsDecodeWithinTheRegion(): Stream<Case?> {
            val pair = "\uD83D\uDC4D"
            val loneSurrogatePatterns = arrayOf<String?>(
                ".", ".*", ".+", "[^a]", "[\\s\\S]", "[\\s\\S]*", "\\D", "\\p{Cs}"
            )
            val cases = Stream.builder<Case?>()
            for (regex in loneSurrogatePatterns) {
                cases.add(Case(regex, "\uD83D", 0, 1, true))
                cases.add(Case(regex, "\uD83Dx", 0, 1, true))
                cases.add(Case(regex, pair, 0, 1, true))
                cases.add(Case(regex, pair, 1, 2, true))
            }
            for (input in arrayOf<String>("\uD83D", "\uD83Dx", pair)) {
                cases.add(Case("\\P{Cs}", input, 0, 1, false))
                cases.add(Case("[^\\p{Cs}]", input, 0, 1, false))
            }
            cases.add(Case("\\P{Cs}", pair, 1, 2, false))
            cases.add(Case("\\p{Cs}", pair, 0, 2, false))
            cases.add(Case("\\P{Cs}", pair, 0, 2, true))
            cases.add(Case(".", pair, 0, 2, true))
            return cases.build()
        }
    }
}
