// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.assertj.core.api.ThrowingConsumer
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.openjdk.jmh.infra.Blackhole
import org.safere.benchmark.DeclarativeBenchmarkPlan.*
import org.safere.benchmark.RegexEngineVariant.CompiledRegex
import org.safere.benchmark.RegexEngineVariant.MatchCursor
import org.safere.benchmark.RegexEngineVariant.RegexInput
import org.safere.benchmark.RegexEngineVariant.StringRegexInput
import java.nio.charset.StandardCharsets
import java.util.List

internal class BenchmarkOperationTest {
    @Test
    fun utf8CaptureTextDecodesNativeBoundsIncludingBorrowedViewOffsets() {
        val storage = "!!é😀 tail??".toByteArray(StandardCharsets.UTF_8)
        val input: RegexInput =
            RegexEngineVariant.Companion.utf8Input(storage, 2, storage.size - 4)
        RegexEngineVariant.SAFERE_UTF8.compile("(é)(😀)(x)?()").use { pattern ->
            val cursor = pattern.matcher(input)
            Assertions.assertThatThrownBy(ThrowingCallable { cursor.group(1) })
                .isInstanceOf(IllegalStateException::class.java)
            Assertions.assertThat(cursor.find()).isTrue()
            Assertions.assertThat(cursor.group(0)).isEqualTo("é😀")
            Assertions.assertThat(cursor.group(1)).isEqualTo("é")
            Assertions.assertThat(cursor.group(2)).isEqualTo("😀")
            Assertions.assertThat(cursor.start(2)).isEqualTo(2)
            Assertions.assertThat(cursor.end(2)).isEqualTo(6)
            Assertions.assertThat(cursor.group(3)).isNull()
            Assertions.assertThat(cursor.group(4)).isEmpty()
            Assertions.assertThatThrownBy(ThrowingCallable { cursor.group(5) })
                .isInstanceOf(IndexOutOfBoundsException::class.java)
            cursor.reset()
            Assertions.assertThat(cursor.find()).isTrue()
            Assertions.assertThat(cursor.group(0)).isEqualTo("é😀")
        }
    }

    @ParameterizedTest
    @EnumSource(value = RegexEngineVariant::class, names = ["SAFERE_STRING", "SAFERE_UTF8", "JDK_STRING"])
    fun tokenizerAdvancesInNativeCoordinatesAndCanReuseItsMatcher(variant: RegexEngineVariant) {
        val input: RegexInput =
            if (variant === RegexEngineVariant.SAFERE_UTF8)
                utf8Input(
                    "é 12 é ".toByteArray(StandardCharsets.UTF_8)
                )
            else
                StringRegexInput("é 12 é ")
        variant.compile("[^ ]+| +").use { pattern ->
            val operation = BenchmarkOperation.MATCHER_REGION_TOKENIZE
            val lifecycle =
                DeclarativeBenchmarkPlan.MatcherLifecycle(
                    MatcherReuse.RETAINED, mutableListOf<LifecycleStep?>()
                )
            Assertions.assertThat<Any?>(
                operation.execute(
                    variant,
                    mutableListOf<String?>("[^ ]+| +"),
                    List.of<CompiledRegex?>(pattern),
                    List.of<RegexInput?>(input),
                    IntArray(0),
                    null,
                    0,
                    lifecycle,
                    null,
                    0,
                    0,
                    "integer"
                )
            )
                .isEqualTo(6)
            val task =
                operation.bind(
                    variant,
                    mutableListOf<String?>("[^ ]+| +"),
                    List.of<CompiledRegex?>(pattern),
                    List.of<RegexInput?>(input),
                    IntArray(0),
                    null,
                    0,
                    lifecycle,
                    null,
                    0,
                    0,
                    "integer"
                )
            val blackhole =
                Blackhole(
                    "Today's password is swordfish. I understand instantiating Blackholes directly is"
                            + " dangerous."
                )
            task.run(blackhole)
            task.run(blackhole)
        }
    }

    @ParameterizedTest
    @CsvSource(
        "MATCHER_REGION_MATCHES, RETAINED",
        "MATCHER_REGION_MATCHES, NEW_PER_INVOCATION",
        "MATCHER_REGION_FIND, RETAINED",
        "MATCHER_REGION_FIND, NEW_PER_INVOCATION",
        "MATCHER_RESET_FIND, RETAINED",
        "MATCHER_RESET_FIND, NEW_PER_INVOCATION"
    )
    fun matcherConstructionFollowsDeclaredLifecycle(
        operation: BenchmarkOperation, reuse: MatcherReuse?
    ) {
        val input: RegexInput = StringRegexInput("prefix needle")
        val matcherInputs: MutableList<RegexInput?> = ArrayList<RegexInput?>()
        val calls: MutableList<String?> = ArrayList<String?>()
        val pattern: CompiledRegex =
            object : CompiledRegex {
                override fun matcher(
                    preparedInput: RegexInput?
                ): MatchCursor {
                    matcherInputs.add(preparedInput)
                    val matcherId = matcherInputs.size
                    return object : MatchCursor {
                        private var found = false

                        override fun region(start: Int, end: Int) {
                            calls.add(matcherId.toString() + ":region:" + start + ":" + end)
                        }

                        override fun reset() {
                            calls.add(matcherId.toString() + ":reset")
                            found = false
                        }

                        override fun matches(): Boolean {
                            calls.add(matcherId.toString() + ":matches")
                            return true
                        }

                        override fun find(): Boolean {
                            calls.add(matcherId.toString() + ":find")
                            val result = !found
                            found = true
                            return result
                        }
                    }
                }
            }
        val region = operation != BenchmarkOperation.MATCHER_RESET_FIND
        val step =
            if (region)
                LifecycleStep(
                    LifecycleStepKind.REGION, 7, 13, null
                )
            else
                LifecycleStep(
                    LifecycleStepKind.RESET, null, null, null
                )
        val task =
            operation.bind(
                RegexEngineVariant.SAFERE_STRING,
                mutableListOf<String?>("needle"),
                List.of<CompiledRegex?>(pattern),
                List.of<RegexInput?>(input),
                IntArray(0),
                null,
                0,
                DeclarativeBenchmarkPlan.MatcherLifecycle(reuse, List.of<LifecycleStep?>(step)),
                null,
                0,
                0,
                "boolean"
            )
        val retained = reuse == MatcherReuse.RETAINED
        Assertions.assertThat<RegexInput?>(matcherInputs).hasSize(if (retained) 1 else 0)
        Assertions.assertThat<String?>(calls).isEmpty()
        val blackhole =
            Blackhole(
                "Today's password is swordfish. I understand instantiating Blackholes directly is"
                        + " dangerous."
            )

        for (invocation in 1..3) {
            task.run(blackhole)

            Assertions.assertThat<RegexInput?>(matcherInputs)
                .hasSize(if (retained) 1 else invocation)
                .allSatisfy(ThrowingConsumer { preparedInput: RegexInput? ->
                    Assertions.assertThat<RegexInput?>(
                        preparedInput
                    ).isSameAs(input)
                })
            val matcherId = if (retained) 1 else invocation
            Assertions.assertThat<String?>(calls)
                .containsExactlyElementsOf(
                    if (region)
                        List.of<String?>(
                            matcherId.toString() + ":region:7:13",
                            matcherId
                                .toString() + (if (operation == BenchmarkOperation.MATCHER_REGION_MATCHES)
                                ":matches"
                            else
                                ":find")
                        )
                    else
                        List.of<String?>(
                            matcherId.toString() + ":reset",
                            matcherId.toString() + ":find",
                            matcherId.toString() + ":find"
                        )
                )
            calls.clear()
        }
    }
}
