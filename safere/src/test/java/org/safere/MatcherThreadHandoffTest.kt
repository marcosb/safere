// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Field
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Tests that a [Matcher] handed off to another thread stops using engines from the previous
 * thread's per-thread cache, since that thread may keep using the same [Pattern].
 */
@DisabledForCrosscheck("uses package-private reflection to assert SafeRE matcher internals")
internal class MatcherThreadHandoffTest {
    @Test
    @Throws(Exception::class)
    fun handoffUsesExecutingThreadsForwardAndReverseDfas() {
        val pattern = Pattern.compile("[a-q][^u-z]{3}x")
        val transferred = pattern.matcher("zz abcdx zz bcdex")
        Assertions.assertThat(transferred.find()).isTrue()
        val originalForward = field("cachedForwardFirstMatchDfa").get(transferred) as Dfa?
        val originalReverse = field("cachedReverseDfa").get(transferred) as Dfa?
        Assertions.assertThat<Dfa?>(originalForward).isNotNull().isSameAs(pattern.forwardFirstMatchDfa())
        Assertions.assertThat<Dfa?>(originalReverse).isNotNull().isSameAs(pattern.reverseDfa())

        val worker: Array<Dfa?> =
            MatcherThreadHandoffTest.Companion.onWorker<Array<Dfa?>?>(
                Callable {
                    org.assertj.core.api.Assertions.assertThat(transferred.find()).isTrue()
                    org.assertj.core.api.Assertions.assertThat(transferred.group()).isEqualTo("bcdex")
                    kotlin.arrayOf<Dfa?>(
                        MatcherThreadHandoffTest.Companion.field("cachedForwardFirstMatchDfa").get(transferred) as Dfa?,
                        MatcherThreadHandoffTest.Companion.field("cachedReverseDfa").get(transferred) as Dfa?,
                        pattern.forwardFirstMatchDfa(),
                        pattern.reverseDfa(),
                    )
                })!!
        Assertions.assertThat<Dfa?>(worker[0]).isNotSameAs(originalForward).isSameAs(worker[2])
        Assertions.assertThat<Dfa?>(worker[1]).isNotSameAs(originalReverse).isSameAs(worker[3])
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @Throws(Exception::class)
    fun handoffWithoutResetUsesExecutingThreadsEngine(bitState: Boolean) {
        val pattern =
            Pattern.compile(
                "([a-z]+)([0-9]+)",
                0,
                EnginePathOptions.builder().dfa(false).onePass(false).bitState(bitState).build()
            )
        val engineField: Field = field(if (bitState) "cachedBitState" else "cachedNfa")
        val transferred = pattern.matcher("abc123 xyz789")
        Assertions.assertThat(transferred.find()).isTrue()
        val originalEngine = engineField.get(transferred)
        Assertions.assertThat<Any?>(originalEngine).isNotNull()

        val workerEngine: Any? =
            onWorker<Any?>(
                Callable {
                    Assertions.assertThat(transferred.find()).isTrue()
                    // Read the engine before group(), which can rebuild it for capture resolution.
                    val engine = engineField.get(transferred)
                    Assertions.assertThat(transferred.group(2)).isEqualTo("789")
                    engine
                })
        Assertions.assertThat<Any?>(workerEngine).isNotNull().isNotSameAs(originalEngine)

        // The original thread keeps using its own cache, not the worker's engine.
        val retained = pattern.matcher("abc123 xyz789")
        Assertions.assertThat(retained.find()).isTrue()
        Assertions.assertThat<Any?>(engineField.get(retained)).isNotNull().isNotSameAs(workerEngine)
    }

    companion object {
        @Throws(Exception::class)
        private fun <T> onWorker(task: Callable<T?>): T? {
            Executors.newSingleThreadExecutor().use { worker ->
                return worker.submit<T?>(task).get()
            }
        }

        @Throws(ReflectiveOperationException::class)
        private fun field(name: String): Field {
            val field = Matcher::class.java.getDeclaredField(name)
            field.setAccessible(true)
            return field
        }
    }
}
