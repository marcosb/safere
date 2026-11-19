// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.charset.StandardCharsets
import java.util.stream.Stream

/** Public searches must tolerate an unavailable standalone reject filter.  */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@DisabledForCrosscheck("Exercises SafeRE-specific matcher-free UTF-8 and diagnostics APIs")
internal class MatcherFreeFindTest {
    @AfterEach
    fun clearDiagnostics() {
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
    }

    @ParameterizedTest
    @MethodSource("innerLiteralSearchCases")
    fun optionalRejectFilterPreservesPublicFindResults(
        diagnostics: Boolean, regex: String, input: String
    ) {
        Pattern.setDiagnostics(
            if (diagnostics) object : SafeReMatchDiagnostics() {} else SafeReMatchDiagnostics.NONE
        )
        val expected = java.util.regex.Pattern.compile(regex).matcher(input).find()
        val pattern = Pattern.compile(regex)
        val utf8 = Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))
        Assertions.assertThat(pattern.asPredicate().test(input)).isEqualTo(expected)
        Assertions.assertThat(pattern.find(utf8)).isEqualTo(expected)
        Assertions.assertThat(pattern.matcher(input).find()).isEqualTo(expected)
        Assertions.assertThat(pattern.matcher(utf8).find()).isEqualTo(expected)
    }

    companion object {
        @JvmStatic
        fun innerLiteralSearchCases(): Stream<Arguments?>? {
            return Stream.of<Boolean?>(false, true)
                .flatMap<Arguments?> { diagnostics: Boolean? ->
                    Stream.of<String?>("zz[^;]*qq", "zz[a-z]*qq")
                        .flatMap<Arguments?> { regex: String? ->
                            Stream.of<Int?>(61, 62, 63, 100, 509, 510, 511)
                                .flatMap<Arguments?> { length: Int? ->
                                    Stream.of<String?>("", "qq", ";qq")
                                        .flatMap<Arguments?> { suffix: String? ->
                                            Stream.of<String?>("", "\uD83D\uDE00")
                                                .map<Arguments?> { prefix: String? ->
                                                    Arguments.of(
                                                        diagnostics,
                                                        regex,
                                                        (prefix
                                                                + "zz"
                                                                + "x".repeat(length!!)
                                                                + suffix)
                                                    )
                                                }
                                        }
                                }
                        }
                }
        }
    }
}
