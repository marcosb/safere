// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import java.util.*
import java.util.List
import java.util.function.IntUnaryOperator
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf

internal class StrictDifferentialTest {
    @Test
    fun generatedWitnessesStayInDomainAndMatch() {
        val random = Random(872)
        for (index in 0..499) {
            val sample = StrictDomain.generate(IntUnaryOperator { bound: Int -> random.nextInt(bound) })
            Assertions.assertThat(sample.regex.size).isLessThan(256)
            Assertions.assertThat(sample.witness.size).isLessThanOrEqualTo(StrictDomain.MAX_INPUT)
            val expected = Pattern.compile(sample.regex).matcher(sample.witness)
            Assertions.assertThat(expected.groupCount()).isZero()
            Assertions.assertThat(expected.matches()).`as`(sample.regex).isTrue()
            Assertions.assertThat(StrictDifferential.check(sample.regex, sample.witness)).isTrue()
        }
    }

    @Test
    fun enumerateSmallDomainWithoutLegacyExclusions() {
        val inputs: MutableList<String?> =
            ArrayList<String?>(
                mutableListOf<String?>(
                    "", "a", "b", "c", ".", "!", "é", "中", "😀", "\ud800", "\udc00", "\udb7d\udf02A"
                )
            )
        for (left in mutableListOf<String?>("a", "b", "!")) {
            for (right in mutableListOf<String?>("a", "b", "!")) {
                inputs.add(left + right)
            }
        }
        for (regex in StrictDomain.smallPatterns()) {
            for (input in inputs) {
                Assertions.assertThat(StrictDifferential.check(regex, input)).`as`("%s on %s", regex, input).isTrue()
            }
        }
        for (left in StrictDomain.QUANTIFIERS) {
            for (right in StrictDomain.QUANTIFIERS) {
                val regex = StrictDomain.quantify(StrictDomain.quantify("a", left), right)
                for (input in inputs) {
                    Assertions.assertThat(StrictDifferential.check(regex, input)).isTrue()
                }
            }
        }
    }

    @Test
    fun nullableRepeatStateSequencesPreserveEmptyBranchPriority() {
        // OSS-Fuzz 575905869, plus a reduced ASCII case and nearby quantified forms.
        Assertions.assertThat(
            StrictStateChecks.check(
                "(?:(?:(?:[a-c]){0,2})(?:(?:|.)))+",
                "...ccéc\udc00c中é中中中cccccccccc",
                5,
                20,
                true,
                true
            )
        )
            .isTrue()
        for (atom in mutableListOf<String?>("a?", "[a-c]{0,2}", "a*")) {
            for (quantifier in mutableListOf<String?>("+", "*", "{1,3}")) {
                Assertions.assertThat(
                    StrictStateChecks.check(
                        "(?:" + atom + "(?:|.))" + quantifier, "!a!a!z", 0, 5, true, true
                    )
                )
                    .isTrue()
            }
        }
    }

    @Test
    fun rejectsInvalidPatternInsteadOfSkippingBothRejections() {
        Assertions.assertThatThrownBy(ThrowingCallable { StrictDifferential.check("[", "a") })
            .isInstanceOf(PatternSyntaxException::class.java)
    }

    @Test
    fun reportsWholeMatchCaptureAndSequenceDifferences() {
        val group = StrictDifferential.Group(0, 1, "a")
        val good = List.of<StrictDifferential.Step?>(
            StrictDifferential.Step(
                "find",
                true,
                List.of<StrictDifferential.Group?>(group)
            )
        )
        for (wrong in List.of<MutableList<StrictDifferential.Step?>?>(
            mutableListOf<StrictDifferential.Step?>(),
            List.of<StrictDifferential.Step?>(
                StrictDifferential.Step(
                    "find",
                    false,
                    mutableListOf<StrictDifferential.Group?>()
                )
            ),
            List.of<StrictDifferential.Step?>(
                StrictDifferential.Step(
                    "find", true, List.of<StrictDifferential.Group?>(StrictDifferential.Group(1, 2, "a"))
                )
            ),
            List.of<StrictDifferential.Step?>(
                StrictDifferential.Step(
                    "find",
                    true,
                    List.of<StrictDifferential.Group?>(group, group)
                )
            )
        )) {
            Assertions.assertThatThrownBy(ThrowingCallable { StrictDifferential.assertSame("a", "a", good, wrong) })
                .isInstanceOf(AssertionError::class.java)
        }
    }

    @Test
    fun knownCaptureDivergenceIsNotWaived() {
        Assertions.assertThatThrownBy(ThrowingCallable { StrictDifferential.check("(?:(a){1})*$", "ab") })
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("strict differential divergence")
    }

    @Test
    fun oracleBudgetIsReportedAsUnavailable() {
        val before = StrictOracle.UNAVAILABLE.sum()
        Assertions.assertThat(StrictDifferential.check("(?:(?:a|aa)+)+y", "a".repeat(24) + "x")).isFalse()
        Assertions.assertThat(StrictOracle.UNAVAILABLE.sum()).isEqualTo(before + 1)
        Assertions.assertThat(StrictDifferential.check("a", "a")).isTrue()
    }

    @Test
    fun enumerateEntireGraduatedPriorityDomain() {
        for (sample in PriorityCases.CASES) {
            for (input in sample.inputs) {
                Assertions.assertThat(StrictDifferential.check(sample.regex, input)).isTrue()
            }
        }
    }

    @Test
    fun historicalPriorityRegressionsRemainObservable() {
        for (regex in mutableListOf<String?>("a+?b?", "(?:a|ab)c?", "a??b?")) {
            for (input in mutableListOf<String?>("aaab", "abc", "ab", "b", "")) {
                Assertions.assertThat(StrictDifferential.check(regex, input)).isTrue()
            }
        }
    }
}
