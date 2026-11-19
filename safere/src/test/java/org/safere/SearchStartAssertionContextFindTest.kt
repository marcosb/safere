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
import java.util.stream.Stream

/**
 * Finds that start after the beginning of the input, where the leftmost match starts at the search
 * start and depends on an assertion whose context is the character before the search start.
 */
internal class SearchStartAssertionContextFindTest {
    @ParameterizedTest
    @MethodSource("findAllCases")
    fun findAllKeepsMatchAtPreviousMatchEnd(regex: String?, input: String?, expected: MutableList<String?>?) {
        Assertions.assertThat<String?>(findAll(Pattern.compile(regex).matcher(input))).isEqualTo(expected)
    }

    @ParameterizedTest
    @MethodSource("findAllCases")
    fun findAllKeepsMatchAtPreviousMatchEndAfterCaptureUse(
        regex: String?, input: String?, expected: MutableList<String?>?
    ) {
        val pattern = Pattern.compile(regex)
        val warmup = pattern.matcher(input)
        Assertions.assertThat(warmup.find()).isTrue()
        if (warmup.groupCount() > 0) {
            Assertions.assertThat(warmup.group(1)).isNotNull()
        }
        Assertions.assertThat<String?>(findAll(pattern.matcher(input))).isEqualTo(expected)
    }

    @Test
    fun capturesOfMatchAtPreviousMatchEnd() {
        val matcher = Pattern.compile("(\\w?)(b|\\b-)?").matcher("1b-")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.group(2)).isEqualTo("b")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(2)
        Assertions.assertThat(matcher.group(1)).isEmpty()
        Assertions.assertThat(matcher.group(2)).isEqualTo("-")
    }

    @Test
    fun findFromOffset() {
        var matcher = Pattern.compile("\\w?(?:b|\\b-)?").matcher("1b-")
        Assertions.assertThat(matcher.find(2)).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(2)
        Assertions.assertThat(matcher.end()).isEqualTo(3)

        matcher = Pattern.compile("(\\w*)(o|\\b )?").matcher("foo bar")
        Assertions.assertThat(matcher.find(3)).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(3)
        Assertions.assertThat(matcher.end()).isEqualTo(4)
    }

    @Test
    fun transparentRegionSeesContextBeforeRegion() {
        val matcher = Pattern.compile("(\\w?)(b|\\b-)?").matcher("1b-")
        matcher.region(2, 3)
        matcher.useTransparentBounds(true)
        Assertions.assertThat<String?>(findAll(matcher)).containsExactly("2:3", "3:3")
    }

    @Test
    fun opaqueRegionHidesContextBeforeRegion() {
        val matcher = Pattern.compile("(\\w?)(b|\\b-)?").matcher("1b-")
        matcher.region(2, 3)
        Assertions.assertThat<String?>(findAll(matcher)).containsExactly("2:2", "3:3")
    }

    @Test
    fun replaceAll() {
        Assertions.assertThat(Pattern.compile("\\w?(?:b|\\b-)?").matcher("1b-").replaceAll("<$0>"))
            .isEqualTo("<1b><-><>")
        Assertions.assertThat(Pattern.compile("(?m)\\n?(?:\\n|^b)?").matcher("\n\nb").replaceAll("<$0>"))
            .isEqualTo("<\n\n><b><>")
    }

    companion object {
        fun findAllCases(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of("(\\w?)(b|\\b-)?", "1b-", mutableListOf<String?>("0:2", "2:3", "3:3")),
                Arguments.of("\\w?(?:b|\\b-)?", "1b-", mutableListOf<String?>("0:2", "2:3", "3:3")),
                Arguments.of("(x?)(x|\\Bb)?", "xxb", mutableListOf<String?>("0:2", "2:3", "3:3")),
                Arguments.of("(?m)\\n?(?:\\n|^b)?", "\n\nb", mutableListOf<String?>("0:2", "2:3", "3:3")),
                Arguments.of(
                    "(\\w?)(b|\\b\uD83D\uDE00)?",
                    "1b\uD83D\uDE00",
                    mutableListOf<String?>("0:2", "2:4", "4:4")
                )
            )
        }

        private fun findAll(matcher: Matcher): MutableList<String?> {
            val spans: MutableList<String?> = ArrayList<String?>()
            while (matcher.find()) {
                spans.add(matcher.start().toString() + ":" + matcher.end())
            }
            return spans
        }
    }
}
