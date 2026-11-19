// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.Map

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class AstAnalysisTest {
    @Test
    fun nullRegexpYieldsSafeDefaults() {
        val analysis = AstAnalysis.analyze(null)
        Assertions.assertThat(analysis.hasLazy).isFalse()
        Assertions.assertThat(analysis.hasAlt).isFalse()
        Assertions.assertThat(analysis.hasNullableAlt).isFalse()
        Assertions.assertThat(analysis.canMatchEmpty).isTrue()
        Assertions.assertThat(analysis.hasUserCaptures).isFalse()
        Assertions.assertThat(analysis.minMatchLength).isEqualTo(0)
        Assertions.assertThat<String?, Int?>(analysis.namedGroups).isEmpty()
    }

    @ParameterizedTest
    @CsvSource(
        "'a+b', false, false, false, false, 2",
        "'a+?b', true, false, false, false, 2",
        "'a|b', false, true, false, false, 1",
        "'a|', false, true, true, true, 0",
        "'(a|b)?', false, true, false, true, 0",
        "'(?:get|post)', false, true, false, false, 3",
        "'(?:get|post|)', false, true, true, true, 0",
        "'a{3,5}', false, false, false, false, 3",
        "'a{3,5}?', true, false, false, false, 3",
        "'.*', false, false, false, true, 0",
        "'.+', false, false, false, false, 1",
        "'^$', false, false, false, true, 0",
        "'\\b', false, false, false, true, 0"
    )
    fun structuralAnalysisFlags(
        regex: String?,
        hasLazy: Boolean,
        hasAlt: Boolean,
        hasNullableAlt: Boolean,
        canMatchEmpty: Boolean,
        minMatchLength: Int
    ) {
        val re = Parser.parse(regex, Pattern.toParseFlags(0))
        val analysis = AstAnalysis.analyze(re)

        Assertions.assertThat(analysis.hasLazy).isEqualTo(hasLazy)
        Assertions.assertThat(analysis.hasAlt).isEqualTo(hasAlt)
        Assertions.assertThat(analysis.hasNullableAlt).isEqualTo(hasNullableAlt)
        Assertions.assertThat(analysis.canMatchEmpty).isEqualTo(canMatchEmpty)
        Assertions.assertThat(analysis.minMatchLength).isEqualTo(minMatchLength)
    }

    @Test
    fun namedGroupsAndUserCaptures() {
        val re =
            Parser.parse("(?<year>\\d{4})-(?<month>\\d{2})-(?:\\d{2})", Pattern.toParseFlags(0))
        val analysis = AstAnalysis.analyze(re)

        Assertions.assertThat(analysis.hasUserCaptures).isTrue()
        Assertions.assertThat<String?, Int?>(analysis.namedGroups)
            .isEqualTo(Map.of<String?, Int?>("year", 1, "month", 2))
    }

    @Test
    fun nulLiteralConsumesOneCodePoint() {
        val re = Parser.parse("\\x00", Pattern.toParseFlags(0))

        val analysis = AstAnalysis.analyze(re)

        Assertions.assertThat(analysis.canMatchEmpty).isFalse()
        Assertions.assertThat(analysis.minMatchLength).isEqualTo(1)

        val publicAnalysis = Pattern.compile("\\x00|a").analysis()
        Assertions.assertThat<PatternFeature?>(publicAnalysis.features)
            .doesNotContain(PatternFeature.NULLABLE, PatternFeature.NULLABLE_ALTERNATION)
        Assertions.assertThat<PatternCapability?>(publicAnalysis.capabilities)
            .contains(PatternCapability.ONE_PASS_PRIMARY)
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "(?:abc)", "a[0-9]c", "^abc$"])
    fun patternsWithoutUserCaptures(regex: String?) {
        val re = Parser.parse(regex, Pattern.toParseFlags(0))
        val analysis = AstAnalysis.analyze(re)

        Assertions.assertThat(analysis.hasUserCaptures).isFalse()
        Assertions.assertThat<String?, Int?>(analysis.namedGroups).isEmpty()
    }
}
