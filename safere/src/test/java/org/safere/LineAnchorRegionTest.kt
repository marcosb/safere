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
import java.nio.charset.StandardCharsets
import java.util.List
import java.util.regex.MatchResult
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.stream.Stream

/** Line assertions must use the same region context in scalar and full-text execution.  */
@DisabledForCrosscheck("checks engine equivalence and the approved opaque CRLF context divergence")
internal class LineAnchorRegionTest {
    private enum class Operation {
        MATCHES,
        LOOKING_AT,
        FIND
    }

    @JvmRecord
    private data class InputRegion(val input: String?, val start: Int, val end: Int)

    @JvmRecord
    private data class Capture(val start: Int, val end: Int, val text: String?)

    @Test
    fun opaqueEndAnchorsTreatTheVisibleLfAsAStandaloneTerminator() {
        // The hidden CR is intentionally ignored, even though the JDK consults it (issue #1031).
        for (anchor in mutableListOf<String?>("$", "\\Z", "(?m:$)", "(?d:$)", "(?dm:$)", "(?d:\\Z)")) {
            for (regex in List.of<String?>("(.)?" + anchor, "(?:(.)?" + anchor + ")|(?:\\Xz)")) {
                val matcher = Pattern.compile(regex).matcher("\r\na").region(1, 2)
                Assertions.assertThat(matcher.lookingAt()).`as`(regex).isTrue()
                Assertions.assertThat<Capture?>(captures(matcher.toMatchResult()))
                    .`as`(regex)
                    .containsExactly(Capture(1, 1, ""), Capture(-1, -1, null))
            }
        }
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("lineAnchorSearchCases")
    fun unreachableGraphemeAlternativePreservesLineAssertions(
        region: InputRegion, regex: String?, operation: Operation
    ) {
        for (anchoring in mutableListOf<Boolean?>(false, true)) {
            for (transparent in mutableListOf<Boolean?>(false, true)) {
                val scalar: Matcher = Companion.configure(Pattern.compile(regex), region, anchoring!!, transparent!!)
                val mixedPattern = Pattern.compile("(?:" + regex + ")|(?:\\Xz)")
                val mixed: Matcher = Companion.configure(mixedPattern, region, anchoring, transparent)
                val expected: MutableList<MutableList<Capture?>?> = trace(scalar, operation)
                Assertions.assertThat<MutableList<Capture?>?>(trace(mixed, operation))
                    .`as`(
                        "%s %s %s anchoring=%s transparent=%s",
                        region, regex, operation, anchoring, transparent
                    )
                    .isEqualTo(expected)
                Companion.assertUtf8Trace(mixedPattern, region, anchoring, transparent, operation, expected)
            }
        }
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("lineAnchorCases")
    fun fullTextLineAssertionsMatchJdkOutsideTheApprovedCrLfDivergence(
        region: InputRegion, regex: String, operation: Operation
    ) {
        val mixedRegex = "(?:" + regex + ")|(?:\\Xz)"
        val pattern = Pattern.compile(mixedRegex)
        for (anchoring in mutableListOf<Boolean?>(false, true)) {
            for (transparent in mutableListOf<Boolean?>(false, true)) {
                // Opaque anchoring contexts starting inside CRLF have an explicit SafeRE oracle above.
                if (anchoring
                    && !transparent!! && region.start > 0 && region.input!!.get(region.start - 1) == '\r' && region.input.get(
                        region.start
                    ) == '\n'
                ) {
                    continue
                }
                val actual: Matcher = Companion.configure(pattern, region, anchoring!!, transparent!!)
                val expected =
                    Pattern.compile(mixedRegex)
                        .matcher(region.input)
                        .region(region.start, region.end)
                        .useAnchoringBounds(anchoring)
                        .useTransparentBounds(transparent)
                val expectedTrace: MutableList<MutableList<Capture?>?> = trace(expected, operation)
                Assertions.assertThat<MutableList<Capture?>?>(trace(actual, operation))
                    .`as`(
                        "%s %s %s anchoring=%s transparent=%s",
                        region, regex, operation, anchoring, transparent
                    )
                    .isEqualTo(expectedTrace)
                Companion.assertUtf8Trace(pattern, region, anchoring, transparent, operation, expectedTrace)
            }
        }
    }

    companion object {
        private fun lineAnchorSearchCases(): Stream<Arguments?>? {
            return cases(List.of<Operation?>(Operation.LOOKING_AT, Operation.FIND))
        }

        private fun lineAnchorCases(): Stream<Arguments?>? {
            return cases(List.of<Operation?>(*Operation.entries.toTypedArray()))
        }

        private fun cases(operations: MutableList<Operation?>): Stream<Arguments?>? {
            val regions: MutableList<InputRegion?> = ArrayList<InputRegion?>()
            regions.add(InputRegion("\r\na", 1, 2))
            regions.add(InputRegion("\r\n", 1, 2))
            for (terminator in mutableListOf<String?>("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029")) {
                regions.add(InputRegion(terminator + "a", 0, terminator!!.length))
                regions.add(InputRegion("é" + terminator + "b", 1, 1 + terminator.length))
                regions.add(InputRegion("a" + terminator, 0, 1 + terminator.length))
            }
            regions.add(InputRegion("\r\na", 0, 1))
            regions.add(InputRegion("\r\na", 1, 1))
            regions.add(InputRegion("abc", 1, 2))
            regions.add(InputRegion("abc", 1, 1))
            return regions.stream()
                .flatMap<Arguments?> { region: InputRegion? ->
                    Stream.of<String?>("", "(.)?", "([\\s\\S])?", "([\\s\\S])*?", "([\\s\\S])")
                        .flatMap<Arguments?> { prefix: String? ->
                            Stream.of<String?>(
                                "^",
                                "(?m:^)",
                                "(?dm:^)",
                                "$",
                                "\\Z",
                                "\\z",
                                "(?m:$)",
                                "(?dm:$)",
                                "(?d:$)",
                                "(?d:\\Z)",
                                "(?d:(?-d:$))"
                            )
                                .flatMap<Arguments?> { anchor: String? ->
                                    operations.stream()
                                        .map<Arguments?> { op: Operation? -> Arguments.of(region, prefix + anchor, op) }
                                }
                        }
                }
        }

        private fun configure(
            pattern: org.safere.Pattern, region: InputRegion, anchoring: Boolean, transparent: Boolean
        ): org.safere.Matcher {
            return pattern
                .matcher(region.input)
                .region(region.start, region.end)
                .useAnchoringBounds(anchoring)
                .useTransparentBounds(transparent)
        }

        private fun trace(matcher: org.safere.Matcher, operation: Operation): MutableList<MutableList<Capture?>?> {
            val result: MutableList<MutableList<Capture?>?> = ArrayList<MutableList<Capture?>?>()
            var matched: Boolean
            do {
                matched =
                    when (operation) {
                        Operation.MATCHES -> matcher.matches()
                        Operation.LOOKING_AT -> matcher.lookingAt()
                        Operation.FIND -> matcher.find()
                    }
                if (matched) {
                    result.add(captures(matcher.toMatchResult()))
                }
            } while (operation == Operation.FIND && matched)
            return result
        }

        private fun trace(matcher: Matcher, operation: Operation): MutableList<MutableList<Capture?>?> {
            val result: MutableList<MutableList<Capture?>?> = ArrayList<MutableList<Capture?>?>()
            var matched: Boolean
            do {
                matched =
                    when (operation) {
                        Operation.MATCHES -> matcher.matches()
                        Operation.LOOKING_AT -> matcher.lookingAt()
                        Operation.FIND -> matcher.find()
                    }
                if (matched) {
                    result.add(captures(matcher.toMatchResult()))
                }
            } while (operation == Operation.FIND && matched)
            return result
        }

        private fun captures(result: MatchResult): MutableList<Capture?> {
            val captures: MutableList<Capture?> = ArrayList<Capture?>()
            for (group in 0..result.groupCount()) {
                captures.add(Capture(result.start(group), result.end(group), result.group(group)))
            }
            return captures
        }

        private fun assertUtf8Trace(
            pattern: org.safere.Pattern,
            region: InputRegion,
            anchoring: Boolean,
            transparent: Boolean,
            operation: Operation,
            expected: MutableList<MutableList<Capture?>?>
        ) {
            val input = region.input!!.toByteArray(StandardCharsets.UTF_8)
            val matcher =
                Matcher(pattern, Utf8InputScanner(input))
                    .region(
                        Companion.byteOffset(region.input, region.start),
                        Companion.byteOffset(region.input, region.end)
                    )
                    .useAnchoringBounds(anchoring)
                    .useTransparentBounds(transparent)
            val actual: MutableList<MutableList<Capture?>?> = ArrayList<MutableList<Capture?>?>()
            var matched: Boolean
            do {
                matched =
                    when (operation) {
                        Operation.MATCHES -> matcher.matches()
                        Operation.LOOKING_AT -> matcher.lookingAt()
                        Operation.FIND -> matcher.find()
                    }
                if (matched) {
                    val captures: MutableList<Capture?> = ArrayList<Capture?>()
                    for (group in 0..matcher.groupCount()) {
                        val start = matcher.start(group)
                        val end = matcher.end(group)
                        captures.add(
                            Capture(
                                start,
                                end,
                                if (start < 0) null else String(input, start, end - start, StandardCharsets.UTF_8)
                            )
                        )
                    }
                    actual.add(captures)
                }
            } while (operation == Operation.FIND && matched)
            Assertions.assertThat<MutableList<Capture?>?>(actual)
                .`as`(
                    "UTF-8 %s %s %s anchoring=%s transparent=%s",
                    region, pattern, operation, anchoring, transparent
                )
                .isEqualTo(Companion.utf8Trace(region.input, expected))
        }

        private fun utf8Trace(
            input: String,
            trace: MutableList<MutableList<Capture?>?>
        ): MutableList<MutableList<Capture?>?> {
            return trace.stream()
                .map<MutableList<Capture?>?> { match: MutableList<Capture?>? ->
                    match!!.stream()
                        .map<Capture?> { capture: Capture? ->
                            Capture(
                                byteOffset(input, capture!!.start),
                                byteOffset(input, capture.end),
                                capture.text
                            )
                        }
                        .toList()
                }
                .toList()
        }

        private fun byteOffset(input: String, offset: Int): Int {
            return if (offset < 0) offset else input.substring(0, offset).toByteArray(StandardCharsets.UTF_8).size
        }
    }
}
