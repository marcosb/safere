// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.regex.MatchResult
import java.util.stream.Stream

/** Differential coverage for JDK-visible captures across alternation branches.  */
@DisabledForCrosscheck("differential test already compares SafeRE with java.util.regex")
internal class AlternationCaptureSemanticsTest {
    @ParameterizedTest(name = "[{index}] /{0}/ flags={1} on \"{2}\"")
    @MethodSource("alternationCaptureCases")
    @DisplayName("compiled alternations preserve JDK-visible capture group count")
    fun compiledAlternationsPreserveCaptureGroupCount(regex: String, flags: Int, input: String) {
        val jdkPattern = java.util.regex.Pattern.compile(regex, flags)
        val saferePattern = Pattern.compile(regex, flags)

        Assertions.assertThat(saferePattern.matcher(input).groupCount())
            .`as`("groupCount before match for /%s/ on %s", regex, input)
            .isEqualTo(jdkPattern.matcher(input).groupCount())
    }

    @ParameterizedTest(name = "[{index}] /{0}/ flags={1} on \"{2}\"")
    @MethodSource("alternationCaptureCases")
    @DisplayName("find() exposes alternation captures like java.util.regex")
    fun findExposesAlternationCapturesLikeJdk(regex: String, flags: Int, input: String) {
        assertSingleOperationMatchesJdk(regex, flags, input, Operation.FIND)
    }

    @ParameterizedTest(name = "[{index}] /{0}/ flags={1} on \"{2}\"")
    @MethodSource("alternationCaptureCases")
    @DisplayName("lookingAt() exposes alternation captures like java.util.regex")
    fun lookingAtExposesAlternationCapturesLikeJdk(regex: String, flags: Int, input: String) {
        assertSingleOperationMatchesJdk(regex, flags, input, Operation.LOOKING_AT)
    }

    @ParameterizedTest(name = "[{index}] /{0}/ flags={1} on \"{2}\"")
    @MethodSource("alternationCaptureCases")
    @DisplayName("matches() exposes alternation captures like java.util.regex")
    fun matchesExposesAlternationCapturesLikeJdk(regex: String, flags: Int, input: String) {
        assertSingleOperationMatchesJdk(regex, flags, input, Operation.MATCHES)
    }

    private enum class Operation {
        FIND,
        LOOKING_AT,
        MATCHES
    }

    companion object {
        @JvmStatic
        private fun alternationCaptureCases(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of("()|\\W+", 0, " "),
                Arguments.of("(a)|b", 0, "b"),
                Arguments.of("b|(a)", 0, "b"),
                Arguments.of("(|a)(b)", 0, "ab"),
                Arguments.of("a(|b)|ac", 0, "ac"),
                Arguments.of("(?:|(a))b", 0, "b"),
                Arguments.of("(?:a|(b)|())", 0, "a"),
                Arguments.of(".|(){2}", org.safere.Pattern.MULTILINE or org.safere.Pattern.UNICODE_CASE, "a"),
                Arguments.of(".|(?:()){2}", 0, "a"),
                Arguments.of("a|(){2}", 0, "a"),
                Arguments.of("a|(?:(b)?){2}", 0, "a"),
                Arguments.of("(?:(a)|^)", 0, ""),
                Arguments.of(
                    "|#\\^@G\uE000\\^@\\^A\\^@\\b(..|^)|" + "k\\^N\uFBA1aaaaaaaaa0aaaaaaa\\^@\\^@\\^@m",
                    org.safere.Pattern.CASE_INSENSITIVE or org.safere.Pattern.COMMENTS,
                    ""
                )
            )
        }

        private fun assertSingleOperationMatchesJdk(
            regex: String, flags: Int, input: String, operation: Operation
        ) {
            val jdk = java.util.regex.Pattern.compile(regex, flags).matcher(input)
            val safere = Pattern.compile(regex, flags).matcher(input)

            val jdkMatched =
                when (operation) {
                    Operation.FIND -> jdk.find()
                    Operation.LOOKING_AT -> jdk.lookingAt()
                    Operation.MATCHES -> jdk.matches()
                }
            val safereMatched =
                when (operation) {
                    Operation.FIND -> safere.find()
                    Operation.LOOKING_AT -> safere.lookingAt()
                    Operation.MATCHES -> safere.matches()
                }

            Assertions.assertThat(safereMatched)
                .`as`("%s result for /%s/ on %s", operation, regex, input)
                .isEqualTo(jdkMatched)
            assertGroupsMatch(regex, input, jdkMatched, jdk, safere)
            if (jdkMatched) {
                assertMatchResultMatchesJdk(regex, input, jdk.toMatchResult(), safere.toMatchResult())
            }
        }

        private fun assertGroupsMatch(
            regex: String?, input: String?, matched: Boolean, jdk: java.util.regex.Matcher, safere: Matcher
        ) {
            Assertions.assertThat(safere.groupCount()).isEqualTo(jdk.groupCount())
            if (!matched) {
                return
            }
            for (group in 0..jdk.groupCount()) {
                Assertions.assertThat(safere.group(group))
                    .`as`("group(%d) for /%s/ on %s", group, regex, input)
                    .isEqualTo(jdk.group(group))
                Assertions.assertThat(safere.start(group))
                    .`as`("start(%d) for /%s/ on %s", group, regex, input)
                    .isEqualTo(jdk.start(group))
                Assertions.assertThat(safere.end(group))
                    .`as`("end(%d) for /%s/ on %s", group, regex, input)
                    .isEqualTo(jdk.end(group))
            }
        }

        private fun assertMatchResultMatchesJdk(
            regex: String?, input: String?, jdk: MatchResult, safere: MatchResult
        ) {
            Assertions.assertThat(safere.groupCount()).isEqualTo(jdk.groupCount())
            for (group in 0..jdk.groupCount()) {
                Assertions.assertThat(safere.group(group))
                    .`as`("toMatchResult group(%d) for /%s/ on %s", group, regex, input)
                    .isEqualTo(jdk.group(group))
                Assertions.assertThat(safere.start(group))
                    .`as`("toMatchResult start(%d) for /%s/ on %s", group, regex, input)
                    .isEqualTo(jdk.start(group))
                Assertions.assertThat(safere.end(group))
                    .`as`("toMatchResult end(%d) for /%s/ on %s", group, regex, input)
                    .isEqualTo(jdk.end(group))
            }
        }
    }
}
