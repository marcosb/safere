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
import org.safere.Matcher.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.stream.Stream

/**
 * Patterns whose default `find()` runner is a literal runner build the forward DFA on first
 * use. Each test compiles a fresh pattern and starts with an operation other than String `find()`, so that operation is the one that builds the DFA.
 */
@DisabledForCrosscheck("compares the optimized path with the JDK")
internal class LiteralRunnerLazyDfaTest {
    @Test
    fun patternsUseLiteralRunners() {
        for (regex in PATTERNS) {
            Assertions.assertThat<PreparedMatchRunner?>(Pattern.compile(regex).preparedMatchRunner(false))
                .`as`(regex)
                .isInstanceOfAny(
                    LiteralPreparedRunner::class.java, LiteralAlternationPreparedRunner::class.java
                )
        }
    }

    @Test
    fun firstOperationMatches() {
        for (regex in PATTERNS) {
            for (text in TEXTS) {
                Assertions.assertThat(Pattern.compile(regex).matcher(text).matches())
                    .`as`("%s matches %s", regex, text)
                    .isEqualTo(java.util.regex.Pattern.compile(regex).matcher(text).matches())
            }
        }
    }

    @Test
    fun firstOperationLookingAt() {
        for (regex in PATTERNS) {
            for (text in TEXTS) {
                val actual = org.safere.Pattern.compile(regex).matcher(text)
                val expected = java.util.regex.Pattern.compile(regex).matcher(text)
                Assertions.assertThat(actual.lookingAt())
                    .`as`("%s lookingAt %s", regex, text)
                    .isEqualTo(expected.lookingAt())
                if (expected.lookingAt()) {
                    Assertions.assertThat(actual.end()).isEqualTo(expected.end())
                }
            }
        }
    }

    @Test
    fun firstOperationUtf8() {
        for (regex in PATTERNS) {
            for (text in TEXTS) {
                val bytes = text.toByteArray(StandardCharsets.UTF_8)
                val jdk = java.util.regex.Pattern.compile(regex)
                Assertions.assertThat(org.safere.Pattern.compile(regex).find(Utf8Input.trusted(bytes)))
                    .`as`("%s find %s", regex, text)
                    .isEqualTo(jdk.matcher(text).find())
                Assertions.assertThat(org.safere.Pattern.compile(regex).matcher(Utf8Input.trusted(bytes)).matches())
                    .`as`("%s matches %s", regex, text)
                    .isEqualTo(jdk.matcher(text).matches())

                val actual = org.safere.Pattern.compile(regex).matcher(Utf8Input.trusted(bytes))
                val expected = jdk.matcher(text)
                while (expected.find()) {
                    Assertions.assertThat(actual.find()).`as`("%s find %s", regex, text).isTrue()
                    // Every test text is ASCII, so byte offsets equal char offsets.
                    Assertions.assertThat(actual.start()).isEqualTo(expected.start())
                    Assertions.assertThat(actual.end()).isEqualTo(expected.end())
                }
                Assertions.assertThat(actual.find()).`as`("%s find %s", regex, text).isFalse()
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun concurrentFirstUse() {
        val text = "x".repeat(1000) + "cherry"
        val threads = 8
        val executor = Executors.newFixedThreadPool(threads)
        try {
            for (round in 0..49) {
                val pattern = org.safere.Pattern.compile("apple|banana|cherry")
                val start = CountDownLatch(1)
                val results: MutableList<Future<Boolean?>> = ArrayList<Future<Boolean?>>()
                for (i in 0..<threads) {
                    results.add(
                        executor.submit<Boolean?>(
                            Callable {
                                start.await()
                                !pattern.matcher(text).matches() && !pattern.matcher(text)
                                    .lookingAt() && pattern.matcher("cherry").matches()
                                        && pattern.find(Utf8Input.trusted(text.toByteArray(StandardCharsets.UTF_8)))
                            })
                    )
                }
                start.countDown()
                for (result in results) {
                    Assertions.assertThat(result.get()).isTrue()
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @ParameterizedTest
    @MethodSource("sharedArtifacts")
    @Throws(Exception::class)
    fun concurrentFirstUseSharesPreparedArtifact(regex: String?, artifact: String?) {
        val pattern = org.safere.Pattern.compile(regex)
        Assertions.assertThat<PreparedMatchRunner?>(pattern.preparedMatchRunner(false))
            .isInstanceOfAny(
                LiteralPreparedRunner::class.java, LiteralAlternationPreparedRunner::class.java
            )
        val threads = 32
        val executor = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        try {
            val results: MutableList<Future<Any?>> = ArrayList<Future<Any?>>()
            for (i in 0..<threads) {
                results.add(
                    executor.submit<Any?>(
                        Callable {
                            ready.countDown()
                            start.await()
                            when (artifact) {
                                "program" -> pattern.flatProg()
                                "DFA program" -> pattern.flatDfaProg()
                                "setup" -> pattern.forwardDfaSetup()
                                else -> throw AssertionError(artifact)
                            }
                        })
                )
            }
            ready.await()
            start.countDown()
            val shared: Any? = results.first().get()
            Assertions.assertThat<Any?>(shared).isNotNull()
            for (result in results) {
                Assertions.assertThat<Any?>(result.get()).`as`(artifact).isSameAs(shared)
            }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    companion object {
        private val PATTERNS: MutableList<String> =
            mutableListOf<String>("needle", "apple|banana|cherry", "foo|barfoo", "red|green|blue")

        private val TEXTS: MutableList<String> = java.util.List.of<String>(
            "",
            "needle",
            "apple",
            "barfoo",
            "abc",
            "xxneedlexx",
            "x".repeat(5000) + "banana" + "x".repeat(100) + "foo",
            "needl",
            "ab",
            "xx red green blue"
        )

        @JvmStatic
        private fun sharedArtifacts(): Stream<Arguments?>? {
            return Stream.of<String?>("hello", "apple|banana|cherry", "needle".repeat(256))
                .flatMap<Arguments?> { regex: String? ->
                    Stream.of<String?>("program", "DFA program", "setup")
                        .map<Arguments?> { artifact: String? -> Arguments.of(regex, artifact) }
                }
        }
    }
}
