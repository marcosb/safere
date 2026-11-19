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
import java.util.function.Function
import java.util.regex.MatchResult
import java.util.stream.Stream

/** Captures and match priority belong to the accepted path, including every repeat copy.  */
@DisabledForCrosscheck("exercises engine controls and intentional accepted-path capture semantics")
internal class AcceptedPathCaptureTest {
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("acceptedPaths")
    fun everyRepeatCopyPublishesItsAcceptedCaptures(testCase: CaptureCase, engine: EnginePathOptions?) {
        val pattern = Pattern.compile(testCase.regex, 0, engine)
        for (region in booleanArrayOf(false, true)) {
            val prefix = if (region) "😀#" else ""
            val input = prefix + testCase.input + (if (region) "!" else "")
            val offset = prefix.length
            for (operation in 0..2) {
                if (testCase.fullMatchOnly && operation != 1) {
                    continue
                }
                val matcher = pattern.matcher(input)
                val utf8 = pattern.matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))
                if (region) {
                    matcher.region(offset, offset + testCase.input.length)
                    utf8.region(
                        byteOffset(input, offset), byteOffset(input, offset + testCase.input.length)
                    )
                }
                Assertions.assertThat(match(matcher, operation))
                    .`as`("operation %d for %s", operation, testCase)
                    .isTrue()
                Assertions.assertThat(match(utf8, operation)).isTrue()
                assertCaptures(matcher, input, offset, testCase.groups!!)
                Assertions.assertThat(utf8.groupCount()).isEqualTo(testCase.groups.size - 1)
                for (group in testCase.groups.indices) {
                    val bounds = testCase.groups.get(group)
                    val start = if (bounds.start < 0) -1 else byteOffset(input, offset + bounds.start)
                    val end = if (bounds.end < 0) -1 else byteOffset(input, offset + bounds.end)
                    Assertions.assertThat(utf8.start(group))
                        .`as`("UTF-8 group %d for %s", group, testCase)
                        .isEqualTo(start)
                    Assertions.assertThat(utf8.end(group)).isEqualTo(end)
                }
            }
        }
    }

    @Test
    fun fullMatchAcceptanceCanSelectADifferentRepeatPartition() {
        val pattern = Pattern.compile("(?:(a){3,4})*")
        val matcher = pattern.matcher("aaaaaa")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.end()).isEqualTo(4)
        Assertions.assertThat(matcher.start(1)).isEqualTo(3)
        Assertions.assertThat(matcher.end(1)).isEqualTo(4)
        matcher.reset()
        Assertions.assertThat(matcher.matches()).isTrue()
        Assertions.assertThat(matcher.end()).isEqualTo(6)
        Assertions.assertThat(matcher.start(1)).isEqualTo(5)
        Assertions.assertThat(matcher.end(1)).isEqualTo(6)
    }

    @Test
    fun laterFindsAndSnapshotsKeepTheirOwnLatestCapture() {
        val matcher = Pattern.compile("(?:(a){1,2})+b").matcher("zzaaaabxxaaaab")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(2)
        Assertions.assertThat(matcher.start(1)).isEqualTo(5)
        val first = matcher.toMatchResult()
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(9)
        Assertions.assertThat(matcher.start(1)).isEqualTo(12)
        val second = matcher.toMatchResult()
        Assertions.assertThat(matcher.find()).isFalse()
        matcher.reset()
        Assertions.assertThat(first.start(1)).isEqualTo(5)
        Assertions.assertThat(first.end(1)).isEqualTo(6)
        Assertions.assertThat(second.start(1)).isEqualTo(12)
        Assertions.assertThat(second.end(1)).isEqualTo(13)
    }

    @Test
    fun emptyAndConsumingFindsKeepTheirOwnNamedCapturesAndSnapshots() {
        for (operand in mutableListOf<String>("(?<word>a)", "(?:(?<word>a))")) {
            val matcher = Pattern.compile("(?:" + operand + "{0,2})*").matcher("yy" + "a".repeat(26))
            for (position in 0..1) {
                Assertions.assertThat(matcher.find()).isTrue()
                Assertions.assertThat(matcher.start()).isEqualTo(position)
                Assertions.assertThat(matcher.end()).isEqualTo(position)
                Assertions.assertThat(matcher.group("word")).isNull()
            }
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(2)
            Assertions.assertThat(matcher.end()).isEqualTo(28)
            Assertions.assertThat(matcher.start("word")).isEqualTo(27)
            Assertions.assertThat(matcher.end("word")).isEqualTo(28)
            val snapshot = matcher.toMatchResult()
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(28)
            Assertions.assertThat(matcher.group("word")).isNull()
            Assertions.assertThat(matcher.find()).isFalse()
            Assertions.assertThat(snapshot.start(1)).isEqualTo(27)
            Assertions.assertThat(snapshot.end(1)).isEqualTo(28)
        }
    }

    @Test
    fun failedMinimumIterationCannotOverwriteTheAcceptedCapture() {
        // A final one-character attempt cannot satisfy the inner minimum of two. Its writes must
        // neither create a capture on an empty match nor replace an earlier accepted capture.
        for (operand in mutableListOf<String>("(a)", "(?:(a))")) {
            val pattern = Pattern.compile("(?:" + operand + "{2,3})*")
            for (length in mutableListOf<Int>(1, 4, 7)) {
                val matcher = pattern.matcher("a".repeat(length!!))
                Assertions.assertThat(matcher.find()).isTrue()
                val end = length - 1
                Assertions.assertThat(matcher.start()).isZero()
                Assertions.assertThat(matcher.end()).isEqualTo(end)
                Assertions.assertThat(matcher.start(1)).isEqualTo(if (end == 0) -1 else end - 1)
                Assertions.assertThat(matcher.end(1)).isEqualTo(if (end == 0) -1 else end)
            }
        }
    }

    @Test
    fun replacementObservesCapturesFirstWrittenInLaterIterations() {
        val pattern = Pattern.compile("(?:(?:a(?<last>b)?){1,2})+")
        val input = "aaab"
        Assertions.assertThat(pattern.matcher(input).replaceAll("[$1]")).isEqualTo("[b]")
        Assertions.assertThat(pattern.matcher(input).replaceFirst("[\${last}]")).isEqualTo("[b]")
        Assertions.assertThat(
            pattern.matcher(input).replaceAll(Function { result: MatchResult? -> "[" + result!!.start(1) + "]" })
        )
            .isEqualTo("[3]")
        val matcher = pattern.matcher(input)
        val output = StringBuilder()
        while (matcher.find()) {
            matcher.appendReplacement(output, "[\${last}]")
        }
        matcher.appendTail(output)
        Assertions.assertThat(output.toString()).isEqualTo("[b]")
    }

    @JvmRecord
    internal data class Bounds(val start: Int, val end: Int)

    @JvmRecord
    internal data class CaptureCase(
        val regex: String?,
        val input: String,
        val groups: MutableList<Bounds>?,
        val fullMatchOnly: Boolean
    )

    companion object {
        private val UNMATCHED = Bounds(-1, -1)

        @JvmStatic
        private fun acceptedPaths(): Stream<Arguments?>? {
            // Expected results are independent of the JDK's wrapper-dependent nested-repeat captures.
            val cases: MutableList<CaptureCase?> =
                java.util.List.of<CaptureCase?>(
                    capture("(?:(a) {0,2})*", "aaaa", Bounds(3, 4)),
                    capture("(?:(?:(a)){0,2})*", "aaaa", Bounds(3, 4)),
                    capture("(?:(a){1,2}b)*", "aabab", Bounds(3, 4)),
                    capture("(?:(a)+){2}", "aaa", Bounds(2, 3)),
                    capture("(?:(a){1,}){2,3}", "aaaaa", Bounds(4, 5)),
                    fullMatch("(?:(a){0,2})*?", "aaa", Bounds(2, 3)),
                    fullMatch("(?:(?:(a){0,2})*?)", "aaa", Bounds(2, 3)),
                    fullMatch("(?:(a){1,2})*?", "aaa", Bounds(2, 3)),
                    fullMatch("(?:(a){1,2})+?", "aaa", Bounds(2, 3)),
                    capture("(?:(a){1,2}){2,}?", "aaa", Bounds(2, 3)),
                    capture("[x](?:(a){1,2})*", "xaaa", Bounds(3, 4)),
                    capture("(?:(a){1,2}){2}", "aaa", Bounds(2, 3)),
                    capture("((a)+){2}", "aaa", Bounds(2, 3), Bounds(2, 3)),
                    capture("(?:((a))+){2}", "aaa", Bounds(2, 3), Bounds(2, 3)),
                    capture("(?:((a)){1,}){2}", "aaa", Bounds(2, 3), Bounds(2, 3)),
                    capture("(?:((a)){0,2})*", "aaa", Bounds(2, 3), Bounds(2, 3)),
                    capture("x(?:(a){1,2})*y", "xaaay", Bounds(3, 4)),
                    capture("x(?:(a){1,}){2}y", "xaaay", Bounds(3, 4)),
                    capture("(?:(a){0,2})*?b", "aaaab", Bounds(3, 4)),
                    capture("(?:(a){1,2})+?b", "aaaab", Bounds(3, 4)),
                    capture("(?:(?:a(b)?){1,2})*", "aaab", Bounds(3, 4)),
                    capture("(?:(?:a(b)?){0,2})+", "aaab", Bounds(3, 4)),
                    capture("(?:(?<last>😀){0,2})*", "😀😀😀😀", Bounds(6, 8)),
                    capture("((a){0,2})*", "aaaa", Bounds(4, 4), Bounds(3, 4)),
                    capture("(?:(a){0,2}(a){0,2})+?", "aa", Bounds(1, 2), UNMATCHED),
                    capture("(?:(ab?)+(a){0,2})+", "aaab", Bounds(2, 4), UNMATCHED),
                    capture("(?:(ab?){0,2}(a){0,2})*", "aab", Bounds(1, 3), UNMATCHED),
                    capture("(a(b)?)+", "aba", Bounds(2, 3), Bounds(1, 2)),
                    capture("(a)b|ac", "ac", UNMATCHED)
                )
            val engines =
                java.util.List.of<EnginePathOptions?>(
                    EnginePathOptions.allEnabled(),
                    EnginePathOptions.builder().lazyCaptureExtraction(false).build(),
                    EnginePathOptions.builder()
                        .literalFastPaths(false)
                        .charClassMatchFastPaths(false)
                        .keywordAlternationFastPath(false)
                        .startAcceleration(false)
                        .shiftDfa(false)
                        .onePass(false)
                        .dfa(false)
                        .reverseDfa(false)
                        .bitState(false)
                        .lazyCaptureExtraction(false)
                        .build()
                )
            return cases.stream()
                .flatMap<Arguments?> { testCase: CaptureCase? ->
                    engines.stream().map<Arguments?> { engine: EnginePathOptions? -> Arguments.of(testCase, engine) }
                }
        }

        private fun assertCaptures(
            matcher: Matcher, input: String, offset: Int, groups: MutableList<Bounds>
        ) {
            val matchStart = matcher.start()
            val matchEnd = matcher.end()
            Assertions.assertThat(matcher.groupCount()).isEqualTo(groups.size - 1)
            val snapshot = matcher.toMatchResult()
            for (group in groups.indices) {
                val bounds = groups.get(group)
                val start = if (bounds.start < 0) -1 else offset + bounds.start
                val end = if (bounds.end < 0) -1 else offset + bounds.end
                val value = if (start < 0) null else input.substring(start, end)
                Assertions.assertThat(matcher.start(group)).`as`("group %d", group).isEqualTo(start)
                Assertions.assertThat(matcher.end(group)).isEqualTo(end)
                Assertions.assertThat(matcher.group(group)).isEqualTo(value)
                Assertions.assertThat(snapshot.start(group)).isEqualTo(start)
                Assertions.assertThat(snapshot.end(group)).isEqualTo(end)
                Assertions.assertThat(snapshot.group(group)).isEqualTo(value)
            }
            Assertions.assertThat(matcher.start()).isEqualTo(matchStart)
            Assertions.assertThat(matcher.end()).isEqualTo(matchEnd)
        }

        private fun match(matcher: Matcher, operation: Int): Boolean {
            return when (operation) {
                0 -> matcher.find()
                1 -> matcher.matches()
                else -> matcher.lookingAt()
            }
        }

        private fun match(matcher: Utf8Matcher, operation: Int): Boolean {
            return when (operation) {
                0 -> matcher.find()
                1 -> matcher.matches()
                else -> matcher.lookingAt()
            }
        }

        private fun byteOffset(input: String, offset: Int): Int {
            return input.substring(0, offset).toByteArray(StandardCharsets.UTF_8).size
        }

        private fun capture(regex: String?, input: String, vararg captures: Bounds): CaptureCase {
            val groups: ArrayList<Bounds> = ArrayList<Bounds>()
            groups.add(Bounds(0, input.length))
            groups.addAll(java.util.List.of<Bounds>(*captures))
            return CaptureCase(regex, input, groups.toList().toMutableList(), false)
        }

        private fun fullMatch(regex: String?, input: String, vararg captures: Bounds): CaptureCase {
            val testCase: CaptureCase = capture(regex, input, *captures)
            return CaptureCase(regex, input, testCase.groups, true)
        }
    }
}
