// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.List
import java.util.regex.Pattern

/**
 * Adversarial differential tests for DFA-loop start acceleration under position-dependent
 * assertions.
 */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class StartAcceleratorDifferentialTest {
    @ParameterizedTest
    @ValueSource(strings = ["😀", "🧪", "👍"])
    fun wideExpansionsPreserveGraphemeProgramLowSurrogateStarts(excluded: String) {
        val padding = "p".repeat(300)
        val text = padding + excluded + "error!"
        for (repetition in mutableListOf<String?>("+", "+?", "{1,3}")) {
            val regex = "([^" + excluded + "]" + repetition + ")error\\X"
            val accelerated = Pattern.compile(regex, 0, ACCELERATED)
            val control = Pattern.compile(regex, 0, UNACCELERATED)
            val expected = control.matcher(text)
            Assertions.assertThat(expected.find()).isTrue()
            Assertions.assertThat(expected.start()).isEqualTo(padding.length + 1)
            Assertions.assertThat(expected.group(1)).isEqualTo(excluded.substring(1))
            for (input in List.of(text, CharBuffer.wrap(text))) {
                val actual = accelerated.matcher(input)
                Assertions.assertThat(actual.find()).`as`("%s on %s", regex, input.javaClass).isTrue()
                Assertions.assertThat(actual.start()).isEqualTo(expected.start())
                Assertions.assertThat(actual.end()).isEqualTo(expected.end())
                Assertions.assertThat(actual.group(1)).isEqualTo(expected.group(1))
                Assertions.assertThat(actual.find()).isFalse()
            }
            Assertions.assertThat(accelerated.asPredicate().test(text)).isTrue()
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["(b|(?m:^a))cd[0-9]", "((?m:^first)|second)token", "(b|\\Aa)cd[0-9]", "(\\Aprefix|unanchored)token", "(b|\\ba)cd[0-9]", "(b|\\Ba)cd[0-9]", "(\\bword|regular)match", "(b|(?m:a$))cd", "(b|a\\z)cd"
        ]
    )
    @DisplayName("Ablation: accelerated DFA matches unaccelerated baseline on false candidates")
    fun acceleratedDfaMatchesBaselineOnFalseCandidates(regex: String) {
        val adversarialInputs =
            List.of<String?>(
                "x".repeat(200) + "0cb\r1bacd19c1__19x y_",
                "x".repeat(200) + "0cb1bacd19c1__19x y_",
                "x".repeat(200) + "foo_acd19bar",
                "x".repeat(200) + "foo acd19bar",
                "x".repeat(200) + "prefixfirsttoken_rest",
                "x".repeat(200) + "123firsttoken_rest",
                "x".repeat(200) + "nonwordmatch_rest",
                "x".repeat(200) + "wordmatch rest"
            )

        val accelerated = Pattern.compile(regex, 0, ACCELERATED)
        val control = Pattern.compile(regex, 0, UNACCELERATED)
        val jdk = Pattern.compile(regex)

        for (input in adversarialInputs) {
            val expected = control.matcher(input).find()
            val actual = accelerated.matcher(input).find()
            Assertions.assertThat(actual)
                .`as`("String find() divergence for /%s/ on input %s", regex, input)
                .isEqualTo(expected)

            val utf8 = Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))
            val actualUtf8 = accelerated.find(utf8)
            Assertions.assertThat(actualUtf8)
                .`as`("UTF-8 find() divergence for /%s/ on input %s", regex, input)
                .isEqualTo(expected)

            // Verify consistency against JDK regex engine
            val jdkExpected = jdk.matcher(input).find()
            Assertions.assertThat(actual)
                .`as`("SafeRE vs JDK divergence for /%s/ on input %s", regex, input)
                .isEqualTo(jdkExpected)
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["(b|(?m:^a))cd[0-9]", "((?m:^first)|second)token", "(b|\\Aa)cd[0-9]", "(b|\\ba)cd[0-9]", "(\\bword|regular)match"
        ]
    )
    @DisplayName("Ablation: accelerated DFA matches unaccelerated baseline on true candidates")
    fun acceleratedDfaMatchesBaselineOnTrueCandidates(regex: String?) {
        val matchingInputs =
            List.of<String?>(
                "x".repeat(200) + "\nacd19c1__19x y_",
                "x".repeat(200) + "\r\nfirsttoken rest",
                "firsttoken at start of text",
                "x".repeat(200) + " wordmatch rest",
                "x".repeat(200) + "\nwordmatch rest"
            )

        val accelerated = org.safere.Pattern.compile(regex, 0, ACCELERATED)
        val control = org.safere.Pattern.compile(regex, 0, UNACCELERATED)

        for (input in matchingInputs) {
            val expected = control.matcher(input).find()
            val actual = accelerated.matcher(input).find()
            Assertions.assertThat(actual)
                .`as`("String find() divergence for /%s/ on input %s", regex, input)
                .isEqualTo(expected)

            val utf8 = Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))
            val actualUtf8 = accelerated.find(utf8)
            Assertions.assertThat(actualUtf8)
                .`as`("UTF-8 find() divergence for /%s/ on input %s", regex, input)
                .isEqualTo(expected)
        }
    }

    @Test
    fun exactIssue711Reproducer() {
        val regex = "(b|(?m:^a))c[0-9]"
        val input = "0cb\r1bac19c1__19x y_"
        val accelerated = org.safere.Pattern.compile(regex, 0, ACCELERATED)
        val control = org.safere.Pattern.compile(regex, 0, UNACCELERATED)
        val utf8 = Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))

        Assertions.assertThat(control.find(utf8)).isFalse()
        Assertions.assertThat(accelerated.find(utf8)).isFalse()
        Assertions.assertThat(Pattern.compile(regex).matcher(input).find()).isFalse()
    }

    companion object {
        private val ACCELERATED: EnginePathOptions = EnginePathOptions.builder().startAcceleration(true).build()
        private val UNACCELERATED: EnginePathOptions = EnginePathOptions.builder().startAcceleration(false).build()
    }
}
