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
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
import java.util.regex.MatchResult
import java.util.regex.Pattern
import java.util.stream.Stream

/** Differential coverage for captures resolved against temporary region views (see issue #880).  */
@DisabledForCrosscheck("uses engine controls and already compares against java.util.regex")
internal class DeferredRegionCaptureTest {
    @Test
    fun regionFindDefersCapturesUntilRequested() {
        for (regex in mutableListOf<String?>("(a+?)b", "(a+)b", "\\b(a+?)b\\b")) {
            val pattern = Pattern.compile(regex)
            val matcher = pattern.matcher("xaabx").region(1, 4)
            Assertions.assertThat(matcher.find()).`as`(regex).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(1)
            Assertions.assertThat(matcher.end()).isEqualTo(4)
            Assertions.assertThat(matcher.capturesResolved()).`as`(regex).isFalse()
            // Changing bounds must preserve the result produced by the original opaque region.
            matcher.useTransparentBounds(true).useAnchoringBounds(false)
            Assertions.assertThat(matcher.group(1)).isEqualTo("aa")
            Assertions.assertThat(matcher.start(1)).isEqualTo(1)
            Assertions.assertThat(matcher.end(1)).isEqualTo(3)
            Assertions.assertThat(matcher.toMatchResult().group(1)).isEqualTo("aa")
            Assertions.assertThat(matcher.find()).isFalse()
        }
    }

    @Test
    fun anchoredAttemptsDiscardPreviousDeferredRegionContext() {
        for (fullMatch in mutableListOf<Boolean?>(false, true)) {
            val matcher = Pattern.compile("(a+?)b").matcher("xaabx").region(1, 4)
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.capturesResolved()).isFalse()
            Assertions.assertThat(if (fullMatch) matcher.matches() else matcher.lookingAt()).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(1)
            Assertions.assertThat(matcher.end()).isEqualTo(4)
            Assertions.assertThat(matcher.group(1)).isEqualTo("aa")
            Assertions.assertThat(matcher.start(1)).isEqualTo(1)
            Assertions.assertThat(matcher.end(1)).isEqualTo(3)
        }
    }

    @Test
    fun exactFallbackRegionFindDefersCaptures() {
        val pattern = Pattern.compile("(a+?)b", 0, EnginePathOptions.builder().dfa(false).build())
        val matcher = pattern.matcher("xaabx").region(1, 4)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(1)
        Assertions.assertThat(matcher.end()).isEqualTo(4)
        Assertions.assertThat(matcher.capturesResolved()).isFalse()
        Assertions.assertThat(matcher.group(1)).isEqualTo("aa")
        Assertions.assertThat(matcher.start(1)).isEqualTo(1)
        Assertions.assertThat(matcher.end(1)).isEqualTo(3)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\\b(a+?)(z)?b\\b", "(?m)^(a+?)(z)?b$", "\\A(a+?)(z)?b\\z"])
    fun freshNfaFindReplaysBoundarySensitiveCapturesAgainstTheRetainedRegion(regex: String) {
        for (changeBounds in mutableListOf<Boolean?>(false, true)) {
            // Keep the Pattern fresh: an earlier capture read would make the exact fallback eager.
            val pattern =
                Pattern.compile(
                    regex,
                    0,
                    EnginePathOptions.builder().onePass(false).bitState(false).dfa(false).build()
                )
            val actual = pattern.matcher("qaabq").region(1, 4)
            val expected =
                Pattern.compile(regex).matcher("qaabq").region(1, 4)

            Assertions.assertThat(actual.find()).isEqualTo(expected.find()).isTrue()
            Assertions.assertThat(actual.capturesResolved()).isFalse()
            Assertions.assertThat(actual.start()).isEqualTo(1)
            Assertions.assertThat(actual.end()).isEqualTo(4)
            Assertions.assertThat(actual.capturesResolved()).isFalse()
            if (changeBounds) {
                actual.useTransparentBounds(true).useAnchoringBounds(false)
                expected.useTransparentBounds(true).useAnchoringBounds(false)
            }
            Assertions.assertThat<Capture?>(captures(actual)).isEqualTo(captures(expected))
            Assertions.assertThat<Capture?>(captures(actual.toMatchResult()))
                .isEqualTo(captures(expected.toMatchResult()))
            Assertions.assertThat(actual.group(1)).isEqualTo("aa")
            Assertions.assertThat(actual.start(2)).isEqualTo(-1)
            Assertions.assertThat(actual.regionStart()).isEqualTo(1)
            Assertions.assertThat(actual.regionEnd()).isEqualTo(4)
        }
    }

    private enum class Engine {
        DEFAULT,
        DEFERRED_NFA,
        EAGER_NFA
    }

    private enum class Operation {
        MATCHES,
        LOOKING_AT,
        FIND
    }

    @JvmRecord
    private data class Capture(val start: Int, val end: Int, val text: String?)

    @Test
    fun defaultEnginePreservesCapturesBeyondTheBitStateLimit() {
        val pattern = org.safere.Pattern.compile("(a+?)?")
        val body = "a".repeat(BitState.maxTextSize(pattern.prog()) + 1)
        val matcher = pattern.matcher("x" + body + "!").region(1, 1 + body.length)

        Assertions.assertThat(matcher.matches()).isTrue()
        Assertions.assertThat(matcher.group(1)).isEqualTo(body)
        Assertions.assertThat(matcher.start(1)).isEqualTo(1)
        Assertions.assertThat(matcher.end(1)).isEqualTo(1 + body.length)
    }

    @ParameterizedTest
    @ValueSource(strings = ["a", "é", "\uD801\uDC00"])
    fun deferredUtf8RegionCapturesUseByteCoordinates(atom: String) {
        val pattern =
            org.safere.Pattern.compile(
                "(" + atom + "+?)?",
                0,
                EnginePathOptions.builder().onePass(false).bitState(false).build()
            )
        for (prefix in mutableListOf<String?>("x", "é", "\uD801\uDC00")) {
            val body = atom.repeat(2)
            val input = (prefix + body + "!").toByteArray(StandardCharsets.UTF_8)
            val start = prefix!!.toByteArray(StandardCharsets.UTF_8).size
            val end = start + body.toByteArray(StandardCharsets.UTF_8).size
            val matcher = pattern.matcher(Utf8Input.validated(input))
            for (reuse in 0..1) {
                matcher.reset().region(start, end)
                Assertions.assertThat(matcher.matches()).isTrue()
                Assertions.assertThat(matcher.start(1)).isEqualTo(start)
                Assertions.assertThat(matcher.end(1)).isEqualTo(end)
            }
        }
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("regionCaptureCases")
    fun capturesUseTheActiveRegionCoordinates(regex: String, engine: Engine, operation: Operation?) {
        val options =
            when (engine) {
                Engine.DEFAULT -> EnginePathOptions.allEnabled()
                Engine.DEFERRED_NFA -> EnginePathOptions.builder().onePass(false).bitState(false).build()
                Engine.EAGER_NFA -> EnginePathOptions.builder().onePass(false).bitState(false).dfa(false).build()
            }
        val pattern = org.safere.Pattern.compile(regex, 0, options)
        val jdkPattern = Pattern.compile(regex)
        for (prefix in mutableListOf<String?>("", "x", "\uD801\uDC00")) {
            for (suffix in mutableListOf<String?>("", "x", "\n")) {
                for (anchoring in mutableListOf<Boolean?>(false, true)) {
                    for (transparent in mutableListOf<Boolean?>(false, true)) {
                        // New matchers borrow pooled engines; reset also exercises reuse after successes and
                        // failures with different region lengths and capture participation.
                        val actual = pattern.matcher("")
                        val expected = jdkPattern.matcher("")
                        for (body in mutableListOf<String?>("a", "", "aa", "b", "aaa", "aa!", "a")) {
                            val input = prefix + body + suffix
                            val start = prefix!!.length
                            val end = start + body!!.length
                            actual
                                .reset(input)
                                .region(start, end)
                                .useAnchoringBounds(anchoring!!)
                                .useTransparentBounds(transparent!!)
                            expected
                                .reset(input)
                                .region(start, end)
                                .useAnchoringBounds(anchoring)
                                .useTransparentBounds(transparent)
                            val context =
                                ("input="
                                        + input
                                        + " region="
                                        + start
                                        + ":"
                                        + end
                                        + " anchoring="
                                        + anchoring
                                        + " transparent="
                                        + transparent)
                            var matched: Boolean
                            do {
                                matched =
                                    when (operation) {
                                        Operation.MATCHES -> actual.matches()
                                        Operation.LOOKING_AT -> actual.lookingAt()
                                        Operation.FIND -> actual.find()
                                    }
                                val jdkMatched =
                                    when (operation) {
                                        Operation.MATCHES -> expected.matches()
                                        Operation.LOOKING_AT -> expected.lookingAt()
                                        Operation.FIND -> expected.find()
                                    }
                                Assertions.assertThat(matched).`as`(context).isEqualTo(jdkMatched)
                                if (matched) {
                                    Assertions.assertThat<Capture?>(captures(actual)).`as`(context)
                                        .isEqualTo(captures(expected))
                                    Assertions.assertThat<Capture?>(captures(actual.toMatchResult()))
                                        .`as`(context)
                                        .isEqualTo(captures(expected.toMatchResult()))
                                    if (regex.contains("?<word>")) {
                                        Assertions.assertThat(actual.group("word")).`as`(context)
                                            .isEqualTo(expected.group("word"))
                                    }
                                }
                            } while (operation == Operation.FIND && matched)
                            Assertions.assertThat(actual.regionStart()).`as`(context).isEqualTo(start)
                            Assertions.assertThat(actual.regionEnd()).`as`(context).isEqualTo(end)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private fun regionCaptureCases(): Stream<Arguments?>? {
            return Stream.of<String?>(
                "(a+?)?",
                "(a+)?",
                "(a*?)?",
                "(a+)??",
                "(a+?)??",
                "(a{1,3}?)?",
                "(a+?)",
                "(a+?)|",
                "((a)+?)?",
                "(?<word>a+?)?",
                "(a+?)\\b",
                "\\b(a+?)",
                "(a+?)$",
                "^(a+?)$",
                "(a+?)\\z"
            )
                .flatMap<Arguments?> { regex: String? ->
                    Stream.of<Engine?>(*Engine.entries.toTypedArray())
                        .flatMap<Arguments?> { engine: Engine? ->
                            Stream.of<Operation?>(*Operation.entries.toTypedArray())
                                .map<Arguments?> { operation: Operation? -> Arguments.of(regex, engine, operation) }
                        }
                }
        }

        private fun captures(result: MatchResult): MutableList<Capture?> {
            val captures: MutableList<Capture?> = ArrayList<Capture?>()
            for (group in 0..result.groupCount()) {
                captures.add(Capture(result.start(group), result.end(group), result.group(group)))
            }
            return captures
        }
    }
}
