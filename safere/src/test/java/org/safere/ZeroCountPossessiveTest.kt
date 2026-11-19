// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.regex.PatternSyntaxException

/** Possessive repetition of operands whose consuming descendants execute zero times.  */
@DisabledForCrosscheck("differential test already compares SafeRE with java.util.regex")
internal class ZeroCountPossessiveTest {
    @ParameterizedTest
    @ValueSource(
        strings = ["a{0}", "a{0,0}", "a{0}?", "a{0,0}?", "(?:(a)+){0}", "(?:(a){0}){2}", "(?:(a){0})*", "a{0}|b{0}", "a{0}b{0}", "(?:a|(){2}){0}?", "(?:.|(){2}){0}", "(?<word>a|aa){0}", "(?i:a){0}", "\\x{1F600}{0}", "a{0}|^", "a{0}\\b"
        ]
    )
    fun zeroCountOperandsMatchLikeJdk(operand: String) {
        // JDK 26 Pattern defines {0} as exactly zero executions. Refs #1130.
        // The outer quantifier's immediate operand is therefore statically zero-width.
        for (quantifier in mutableListOf<String>("*+", "?+", "++", "{0}+", "{1}+", "{2}+", "{0,2}+", "{1,}+")) {
            val repeated = "(?:" + operand + ")" + quantifier
            for (regex in java.util.List.of<String>(repeated, "x(" + repeated + ")(y)", "(" + repeated + ")|a")) {
                for (flags in java.util.List.of<Int?>(0, Pattern.COMMENTS)) {
                    for (input in mutableListOf<String>("", "a", "xy", "xay", "\uD83D\uDE00")) {
                        assertOperationsSame(regex, flags, input!!)
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["(?:x)?(?:(?:(?<word>a|aa)){0})*+", "(?:(?:.|(){2}){0})*+", "x(?:(?:a|(){2}){0}?)*+y", "x(?:(?:(?<word>a|aa)){0})*+", "x(?:(?:a|(?:()){2}){0}?)*+c?"
        ]
    )
    fun archivedZeroCountShapesMatchLikeJdk(regex: String) {
        for (input in mutableListOf<String>("", "x", "xy", "xc", "xaa", "xx")) {
            assertOperationsSame(regex, 0, input!!)
        }
    }

    @Test
    fun skippedCapturesKeepTheirNumbersAndRemainUnmatched() {
        val matcher = Pattern.compile("((?<skipped>a){0})++(b)").matcher("b")
        Assertions.assertThat(matcher.matches()).isTrue()
        Assertions.assertThat(matcher.groupCount()).isEqualTo(3)
        Assertions.assertThat(matcher.group(1)).isEmpty()
        Assertions.assertThat(matcher.group("skipped")).isNull()
        Assertions.assertThat(matcher.start("skipped")).isEqualTo(-1)
        Assertions.assertThat(matcher.end("skipped")).isEqualTo(-1)
        Assertions.assertThat(matcher.group(3)).isEqualTo("b")
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["a{0}+", "a{0,0}+", "(?:a{0}|b)*+", "(?:a{0}b)?+", "(?:a{0,1})++", "(?:a?){0}+", "(?:(a){0}b){1}+"
        ]
    )
    fun consumingImmediateOperandsStayUnsupported(regex: String) {
        java.util.regex.Pattern.compile(regex)
        Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
            .isInstanceOf(PatternSyntaxException::class.java)
            .hasMessageContaining("possessive quantifiers are not supported")
    }

    companion object {
        private fun assertOperationsSame(regex: String, flags: Int, input: String) {
            val safe = org.safere.Pattern.compile(regex, flags)
            val jdk = java.util.regex.Pattern.compile(regex, flags)
            val actual = safe.matcher(input)
            val expected = jdk.matcher(input)
            var matched = expected.matches()
            Assertions.assertThat(actual.matches()).`as`("matches /%s/ on %s", regex, input).isEqualTo(matched)
            if (matched) {
                assertGroupsSame(actual, expected)
            }
            actual.reset()
            expected.reset()
            matched = expected.lookingAt()
            Assertions.assertThat(actual.lookingAt()).`as`("lookingAt /%s/ on %s", regex, input).isEqualTo(matched)
            if (matched) {
                assertGroupsSame(actual, expected)
            }
            actual.reset()
            expected.reset()
            while (expected.find()) {
                Assertions.assertThat(actual.find()).`as`("find /%s/ on %s", regex, input).isTrue()
                assertGroupsSame(actual, expected)
            }
            Assertions.assertThat(actual.find()).isFalse()
        }

        private fun assertGroupsSame(actual: org.safere.Matcher, expected: java.util.regex.Matcher) {
            Assertions.assertThat(actual.groupCount()).isEqualTo(expected.groupCount())
            for (group in 0..expected.groupCount()) {
                Assertions.assertThat(actual.group(group)).`as`("group %s", group).isEqualTo(expected.group(group))
                Assertions.assertThat(actual.start(group)).`as`("start %s", group).isEqualTo(expected.start(group))
                Assertions.assertThat(actual.end(group)).`as`("end %s", group).isEqualTo(expected.end(group))
            }
        }
    }
}
