// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
import java.util.function.Consumer
import java.util.function.Function
import java.util.regex.MatchResult
import java.util.stream.Stream

/** Capture consumption is a Pattern hint independent of how a result was produced.  */
@DisabledForCrosscheck("asserts SafeRE's internal adaptive capture policy")
internal class CaptureDemandTest {
    @ParameterizedTest
    @MethodSource("captureReads")
    @Throws(Exception::class)
    fun consumptionRecordsDemandForResolvedAndDeferredResults(read: Consumer<Matcher?>, eager: Boolean) {
        val pattern =
            Pattern.compile(
                "a\\s+(?<value>.+)",
                0,
                EnginePathOptions.builder()
                    .onePass(false)
                    .dfa(!eager)
                    .lazyCaptureExtraction(!eager)
                    .build()
            )
        val matcher = pattern.matcher("a value")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(resolved(matcher)).isEqualTo(eager)
        Assertions.assertThat(pattern.innerCapturesObserved()).isFalse()
        read.accept(matcher)
        Assertions.assertThat(pattern.innerCapturesObserved()).isTrue()
        Assertions.assertThat(matcher.group("value")).isEqualTo("value")
    }

    @ParameterizedTest
    @ValueSource(strings = ["matches", "lookingAt", "find"])
    fun eagerResultsPropagateDemandToOtherMatchers(operation: String) {
        val pattern =
            Pattern.compile(
                "(a+)(b+)", 0, EnginePathOptions.builder().lazyCaptureExtraction(false).build()
            )
        val matcher = pattern.matcher("aaabb")
        Assertions.assertThat(match(matcher, operation)).isTrue()
        Assertions.assertThat(pattern.innerCapturesObserved()).isFalse()
        Assertions.assertThat(matcher.group(1)).isEqualTo("aaa")
        Assertions.assertThat(pattern.innerCapturesObserved()).isTrue()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun utf8CaptureAccessRecordsDemandEvenWhenAlreadyResolved(eager: Boolean) {
        val pattern =
            Pattern.compile(
                "a\\s+(é+)",
                0,
                EnginePathOptions.builder()
                    .onePass(false)
                    .dfa(!eager)
                    .lazyCaptureExtraction(!eager)
                    .build()
            )
        val matcher = pattern.matcher(Utf8Input.trusted("a éé".toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start(1)).isEqualTo(2)
        Assertions.assertThat(matcher.end(1)).isEqualTo(6)
        Assertions.assertThat(pattern.innerCapturesObserved()).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["$1", "\${value}"])
    fun anchoredReplacementRecordsAlreadyResolvedCaptureConsumption(replacement: String?) {
        val pattern = Pattern.compile("^(?<value>a+)$")
        Assertions.assertThat(pattern.matcher("aaa").replaceAll(replacement)).isEqualTo("aaa")
        Assertions.assertThat(pattern.innerCapturesObserved()).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["$1", "\${value}"])
    fun utf8ReplacementRecordsAlreadyResolvedCaptureConsumption(replacement: String) {
        val pattern =
            Pattern.compile(
                "(?<value>a+)",
                0,
                EnginePathOptions.builder().dfa(false).lazyCaptureExtraction(false).build()
            )
        val matcher = pattern.matcher(Utf8Input.trusted("aaa".toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(matcher.find()).isTrue()
        matcher.appendReplacement(
            Utf8Sink { bytes: ByteArray?, offset: Int, length: Int -> },
            Utf8Input.trusted(replacement.toByteArray(StandardCharsets.UTF_8))
        )
        Assertions.assertThat(pattern.innerCapturesObserved()).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["$0", "\\$1", "literal"])
    @Throws(Exception::class)
    fun fallbackReplacementWithoutInnerReferencesDoesNotCreateDemand(replacement: String) {
        val pattern = Pattern.compile("x(a+)y", 0, EXACT)
        val matcher = pattern.matcher("xaay")
        val expected =
            java.util.regex.Pattern.compile(pattern.pattern())
                .matcher("xaay")
                .replaceFirst(replacement)
        Assertions.assertThat(matcher.replaceFirst(replacement)).isEqualTo(expected)
        Assertions.assertThat(pattern.innerCapturesObserved()).isFalse()
        matcher.reset()
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(resolved(matcher)).isFalse()
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    @Throws(Exception::class)
    fun exactFallbackUsesDemandFromOtherMatchers(utf8: Boolean, bitState: Boolean) {
        val pattern =
            Pattern.compile("(a?)*y", 0, EnginePathOptions.builder().bitState(bitState).build())
        val text = "a".repeat(1023) + "y"
        val first: Matcher = matcher(pattern, text, utf8)
        Assertions.assertThat(first.find()).isTrue()
        Assertions.assertThat(first.start(1)).isGreaterThanOrEqualTo(0)
        val second: Matcher = matcher(pattern, text, utf8)
        Assertions.assertThat(second.find()).isTrue()
        Assertions.assertThat(resolved(second)).isTrue()
        Assertions.assertThat(second.start(1)).isEqualTo(first.start(1))
        Assertions.assertThat(second.end(1)).isEqualTo(first.end(1))
    }

    @Test
    fun internalResolutionAndGroupZeroAccessDoNotCreateDemand() {
        val pattern = Pattern.compile("a\\s+(.+)")
        val matcher = pattern.matcher("a value")
        Assertions.assertThat(matcher.find()).isTrue()
        matcher.useAnchoringBounds(false)
        matcher.useTransparentBounds(true)
        Assertions.assertThat(matcher.group()).isEqualTo("a value")
        Assertions.assertThat(matcher.start()).isZero()
        Assertions.assertThat(matcher.end()).isEqualTo(7)
        Assertions.assertThat(pattern.innerCapturesObserved()).isFalse()
        Assertions.assertThat(matcher.group(1)).isEqualTo("value")
        Assertions.assertThat(pattern.innerCapturesObserved()).isTrue()
    }

    @Test
    fun invalidAccessAndSnapshotsWithoutSuccessfulCapturesDoNotCreateDemand() {
        val pattern = Pattern.compile("(a+)")
        val matcher = pattern.matcher("bbb")
        matcher.toMatchResult()
        Assertions.assertThat(matcher.find()).isFalse()
        matcher.toMatchResult()
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.group(1) })
            .isInstanceOf(IllegalStateException::class.java)
        Assertions.assertThat(pattern.innerCapturesObserved()).isFalse()
        matcher.reset("aaa")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.group(2) })
            .isInstanceOf(IndexOutOfBoundsException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.group("missing") })
            .isInstanceOf(IllegalArgumentException::class.java)
        Assertions.assertThat(pattern.innerCapturesObserved()).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun emptySnapshotsDoNotMakeFutureFallbackSearchesEager() {
        val pattern = Pattern.compile("x(a+)y", 0, EXACT)
        val matcher = pattern.matcher("xaay")
        matcher.toMatchResult()
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(resolved(matcher)).isFalse()
        Assertions.assertThat(matcher.find()).isFalse()
        matcher.toMatchResult()
        matcher.reset()
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(resolved(matcher)).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun unsuccessfulReplacementDoesNotCreateFutureDemand() {
        val pattern = Pattern.compile("x(a+)y", 0, EXACT)
        val matcher = pattern.matcher("no match")
        Assertions.assertThat(matcher.replaceAll("$1")).isEqualTo("no match")
        Assertions.assertThat(pattern.innerCapturesObserved()).isFalse()
        matcher.reset("xaay")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(resolved(matcher)).isFalse()
    }

    @Test
    fun snapshotsAndStreamsRecordConsumptionOfEagerCaptures() {
        val snapshot = Pattern.compile("(a+)")
        val matcher = snapshot.matcher("aaa")
        Assertions.assertThat(matcher.matches()).isTrue()
        Assertions.assertThat(matcher.toMatchResult().group(1)).isEqualTo("aaa")
        Assertions.assertThat(snapshot.innerCapturesObserved()).isTrue()
        val stream =
            Pattern.compile(
                "(a+)", 0, EnginePathOptions.builder().lazyCaptureExtraction(false).build()
            )
        Assertions.assertThat(stream.matcher("aaa").results().findFirst().orElseThrow()!!.group(1)).isEqualTo("aaa")
        Assertions.assertThat(stream.innerCapturesObserved()).isTrue()
    }

    @Test
    fun functionalReplacementPromisesSnapshotCapturesEvenForLiteralOutput() {
        val pattern =
            Pattern.compile(
                "(a+)", 0, EnginePathOptions.builder().lazyCaptureExtraction(false).build()
            )
        Assertions.assertThat(pattern.matcher("aaa").replaceAll(Function { result: MatchResult? -> "x" }))
            .isEqualTo("x")
        Assertions.assertThat(pattern.innerCapturesObserved()).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun demandSurvivesInputAndRegionChangesButBelongsToThePattern() {
        val first = Pattern.compile("a\\s+(.+)", 0, EXACT)
        val matcher = first.matcher("a first")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.group(1)).isEqualTo("first")
        matcher.reset("a second").region(0, 8).useAnchoringBounds(false)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(resolved(matcher)).isTrue()
        val second = Pattern.compile("a\\s+(.+)", 0, EXACT)
        matcher.usePattern(second).reset()
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(resolved(matcher)).isFalse()
        Assertions.assertThat(second.innerCapturesObserved()).isFalse()
        matcher.usePattern(first).reset()
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(resolved(matcher)).isTrue()
    }

    companion object {
        private val EXACT: EnginePathOptions = EnginePathOptions.builder().onePass(false).dfa(false).build()

        @JvmStatic
        fun captureReads(): Stream<Arguments?>? {
            return Stream.of<Consumer<Matcher?>?>(
                Consumer { m: Matcher? -> m!!.start(1) },
                Consumer { m: Matcher? -> m!!.end(1) },
                Consumer { m: Matcher? -> m!!.group(1) },
                Consumer { m: Matcher? -> m!!.start("value") },
                Consumer { m: Matcher? -> m!!.end("value") },
                Consumer { m: Matcher? -> m!!.group("value") },
                Consumer { obj: Matcher? -> obj!!.toMatchResult() })
                .flatMap<Arguments?> { read: Consumer<Matcher?>? ->
                    Stream.of<Boolean?>(false, true).map<Arguments?> { eager: Boolean? -> Arguments.of(read, eager) }
                }
        }

        private fun match(matcher: Matcher, operation: String): Boolean {
            return when (operation) {
                "matches" -> matcher.matches()
                "lookingAt" -> matcher.lookingAt()
                "find" -> matcher.find()
                else -> throw AssertionError(operation)
            }
        }

        @Throws(Exception::class)
        private fun matcher(pattern: Pattern, text: String, utf8: Boolean): Matcher {
            if (!utf8) {
                return pattern.matcher(text)
            }
            val matcher = pattern.matcher(Utf8Input.trusted(text.toByteArray(StandardCharsets.UTF_8)))
            val delegate = Utf8Matcher::class.java.getDeclaredField("delegate")
            delegate.setAccessible(true)
            return delegate.get(matcher) as Matcher
        }

        @Throws(Exception::class)
        private fun resolved(matcher: Matcher?): Boolean {
            val field = Matcher::class.java.getDeclaredField("capturesResolved")
            field.setAccessible(true)
            return field.getBoolean(matcher)
        }
    }
}
