// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Field
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Tests internal deferred-capture lifecycle invariants for [Matcher].  */
@DisabledForCrosscheck("uses package-private reflection to assert SafeRE matcher internals")
internal class MatcherDeferredCaptureStateTest {
    @Test
    @DisplayName("reset clears stale deferred captures")
    @Throws(ReflectiveOperationException::class)
    fun resetClearsStaleDeferredCaptures() {
        val matcher: Matcher = matcherWithDeferredCaptures()

        matcher.reset()

        assertDeferredCaptureStateClear(matcher)
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.group() })
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    @DisplayName("reset(input) clears stale deferred captures")
    @Throws(ReflectiveOperationException::class)
    fun resetInputClearsStaleDeferredCaptures() {
        val matcher: Matcher = matcherWithDeferredCaptures()

        matcher.reset("name")

        assertDeferredCaptureStateClear(matcher)
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.group() })
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    @DisplayName("region clears stale deferred captures")
    @Throws(ReflectiveOperationException::class)
    fun regionClearsStaleDeferredCaptures() {
        val matcher: Matcher = matcherWithDeferredCaptures()

        matcher.region(0, 2)

        assertDeferredCaptureStateClear(matcher)
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.group() })
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    @DisplayName("failed find(start) clears stale deferred captures")
    @Throws(ReflectiveOperationException::class)
    fun failedFindStartClearsStaleDeferredCaptures() {
        val matcher: Matcher = matcherWithDeferredCaptures()

        Assertions.assertThat(matcher.find(CSV.length)).isFalse()

        assertDeferredCaptureStateClear(matcher)
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.group() })
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    @DisplayName("usePattern clears stale deferred captures")
    @Throws(ReflectiveOperationException::class)
    fun usePatternClearsStaleDeferredCaptures() {
        val matcher: Matcher = matcherWithDeferredCaptures()

        matcher.usePattern(Pattern.compile("name"))

        assertDeferredCaptureStateClear(matcher)
        Assertions.assertThat(matcher.hasMatch()).isTrue()
        Assertions.assertThat(matcher.group()).isEqualTo("id")
    }

    @Test
    @DisplayName("cached NFA does not retain input context")
    @Throws(ReflectiveOperationException::class)
    fun cachedNfaDoesNotRetainInputContext() {
        val pattern =
            Pattern.compile(
                "([a-z]+)([0-9]+)",
                0,
                EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build()
            )

        Assertions.assertThat(pattern.matcher("abc123").find()).isTrue()

        val nfa = pattern.borrowNfa()
        Assertions.assertThat<Nfa?>(nfa).isNotNull()
        Assertions.assertThat<Any?>(field(Nfa::class.java, "context").get(nfa)).isNull()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @Throws(Exception::class)
    fun resetInputThenHandoffBorrowsEngineFromExecutingThread(bitState: Boolean) {
        val pattern =
            Pattern.compile(
                "([a-z]+)([0-9]+)",
                0,
                EnginePathOptions.builder().dfa(false).onePass(false).bitState(bitState).build()
            )
        val engineField: Field = field(Matcher::class.java, if (bitState) "cachedBitState" else "cachedNfa")
        val transferred = pattern.matcher("abc123")
        Assertions.assertThat(transferred.find()).isTrue()
        val initialEngine = engineField.get(transferred)
        Assertions.assertThat<Any?>(initialEngine).isNotNull()
        transferred.reset("xyz789")
        Assertions.assertThat<Any?>(engineField.get(transferred)).isNull()

        val retained = pattern.matcher("abc123")
        Assertions.assertThat(retained.find()).isTrue()
        Assertions.assertThat<Any?>(engineField.get(retained)).isSameAs(initialEngine)
        Executors.newSingleThreadExecutor().use { worker ->
            val workerEngine =
                worker
                    .submit<Any?>(
                        Callable {
                            Assertions.assertThat(transferred.find()).isTrue()
                            val borrowedEngine = engineField.get(transferred)
                            Assertions.assertThat(transferred.group(2)).isEqualTo("789")
                            borrowedEngine
                        })
                    .get()
            Assertions.assertThat<Any?>(workerEngine).isNotNull().isNotSameAs(engineField.get(retained))
        }
    }

    @Test
    @DisplayName("inner capture demand makes later small full matches capture eagerly")
    @Throws(
        ReflectiveOperationException::class
    )
    fun innerCaptureDemandMakesLaterSmallFullMatchesCaptureEagerly() {
        val pattern = Pattern.compile("^a\\s+-\\s+(.+)$")
        val first = pattern.matcher("a - value")

        Assertions.assertThat(first.matches()).isTrue()
        Assertions.assertThat(booleanField(first, "capturesResolved")).isFalse()
        Assertions.assertThat(first.group(1)).isEqualTo("value")

        val second = pattern.matcher("a - another")
        Assertions.assertThat(second.matches()).isTrue()
        Assertions.assertThat(booleanField(second, "capturesResolved")).isTrue()
        Assertions.assertThat(second.group(1)).isEqualTo("another")
    }

    @Test
    @DisplayName("group zero access keeps later full matches on deferred captures")
    @Throws(
        ReflectiveOperationException::class
    )
    fun groupZeroAccessKeepsLaterFullMatchesOnDeferredCaptures() {
        val pattern = Pattern.compile("^a\\s+-\\s+(.+)$")
        val first = pattern.matcher("a - value")

        Assertions.assertThat(first.matches()).isTrue()
        Assertions.assertThat(first.group()).isEqualTo("a - value")

        val second = pattern.matcher("a - another")
        Assertions.assertThat(second.matches()).isTrue()
        Assertions.assertThat(booleanField(second, "capturesResolved")).isFalse()
    }

    @Test
    @DisplayName("inner capture demand keeps later small unanchored finds deferred")
    @Throws(
        ReflectiveOperationException::class
    )
    fun innerCaptureDemandKeepsLaterSmallUnanchoredFindsDeferred() {
        val pattern = Pattern.compile("a\\s+(.+)")
        val first = pattern.matcher("prefix a value")

        Assertions.assertThat(first.find()).isTrue()
        Assertions.assertThat(booleanField(first, "capturesResolved")).isFalse()
        Assertions.assertThat(first.group(1)).isEqualTo("value")

        val second = pattern.matcher("prefix a another")
        Assertions.assertThat(second.find()).isTrue()
        Assertions.assertThat(booleanField(second, "capturesResolved")).isFalse()
        Assertions.assertThat(second.group(1)).isEqualTo("another")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @Throws(ReflectiveOperationException::class)
    fun innerCaptureDemandStillCollectsCapturesInRequiredExactFallback(bitState: Boolean) {
        val pattern =
            Pattern.compile(
                "a\\s+(.+)",
                0,
                EnginePathOptions.builder().dfa(false).onePass(false).bitState(bitState).build()
            )
        val first = pattern.matcher("a first")
        Assertions.assertThat(first.find()).isTrue()
        Assertions.assertThat(first.group(1)).isEqualTo("first")

        val second = pattern.matcher("prefix a another")
        Assertions.assertThat(second.find()).isTrue()
        Assertions.assertThat(booleanField(second, "capturesResolved")).isTrue()
        Assertions.assertThat(second.group(1)).isEqualTo("another")
    }

    @Test
    @DisplayName("capture-demand adaptation remains bounded to small inputs")
    @Throws(ReflectiveOperationException::class)
    fun captureDemandAdaptationRemainsBoundedToSmallInputs() {
        val pattern = Pattern.compile("a\\s+(.+)")
        val first = pattern.matcher("a first")

        Assertions.assertThat(first.find()).isTrue()
        Assertions.assertThat(first.group(1)).isEqualTo("first")

        val capturedText = "x".repeat(600)
        val second = pattern.matcher("prefix a " + capturedText)
        Assertions.assertThat(second.find()).isTrue()
        Assertions.assertThat(booleanField(second, "capturesResolved")).isFalse()
        Assertions.assertThat(second.group(1)).isEqualTo(capturedText)
    }

    @Test
    @DisplayName("anchoring bounds change resolves stale deferred captures before clearing markers")
    @Throws(
        ReflectiveOperationException::class
    )
    fun anchoringBoundsChangeResolvesStaleDeferredCapturesBeforeClearingMarkers() {
        val matcher: Matcher = matcherWithDeferredCaptures()

        matcher.useAnchoringBounds(false)

        assertDeferredCaptureStateClear(matcher)
        Assertions.assertThat(matcher.group(1)).isNull()
        Assertions.assertThat(matcher.group(2)).isEqualTo("id")
    }

    @Test
    @DisplayName("transparent bounds change resolves stale deferred captures before clearing markers")
    @Throws(
        ReflectiveOperationException::class
    )
    fun transparentBoundsChangeResolvesStaleDeferredCapturesBeforeClearingMarkers() {
        val matcher: Matcher = matcherWithDeferredCaptures()

        matcher.useTransparentBounds(true)

        assertDeferredCaptureStateClear(matcher)
        Assertions.assertThat(matcher.group(1)).isNull()
        Assertions.assertThat(matcher.group(2)).isEqualTo("id")
    }

    companion object {
        private const val DEFERRED_CAPTURE_REGEX = "(?m)(?:^|,)(?:\"([^\"]*)\"|([^,\r\n]+))"
        private const val CSV = "id,name\n42,\"Ada Lovelace\""

        @Throws(ReflectiveOperationException::class)
        private fun matcherWithDeferredCaptures(): Matcher {
            // Patterns retain consumption history, so each fixture needs an independent Pattern.
            val matcher: Matcher = Pattern.compile(DEFERRED_CAPTURE_REGEX).matcher(CSV)
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.group()).isEqualTo("id")
            Assertions.assertThat(booleanField(matcher, "capturesResolved")).isFalse()
            return matcher
        }

        @Throws(ReflectiveOperationException::class)
        private fun assertDeferredCaptureStateClear(matcher: Matcher?) {
            Assertions.assertThat(booleanField(matcher, "capturesResolved")).isTrue()
            Assertions.assertThat(intField(matcher, "deferredMatchStart")).isZero()
            Assertions.assertThat(intField(matcher, "deferredMatchEnd")).isZero()
            Assertions.assertThat(booleanField(matcher, "deferredEndMatch")).isFalse()
        }

        @Throws(ReflectiveOperationException::class)
        private fun booleanField(matcher: Matcher?, name: String): Boolean {
            return field(name).get(matcher) as Boolean
        }

        @Throws(ReflectiveOperationException::class)
        private fun intField(matcher: Matcher?, name: String): Int {
            return field(name).get(matcher) as Int
        }

        @Throws(ReflectiveOperationException::class)
        private fun field(name: String): Field {
            return field(Matcher::class.java, name)
        }

        @Throws(ReflectiveOperationException::class)
        private fun field(type: Class<*>, name: String): Field {
            val field = type.getDeclaredField(name)
            field.setAccessible(true)
            return field
        }
    }
}
