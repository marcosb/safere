// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowingConsumer
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.safere.MatcherTransitionInventory.DeferredCaptureEffect
import org.safere.MatcherTransitionInventory.ResultEffect
import org.safere.MatcherTransitionInventory.Transition
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.*
import java.util.List
import java.util.function.Predicate
import java.util.stream.Collectors

/** Tests for the package-private matcher transition inventory.  */
@DisabledForCrosscheck("package-private Matcher transition metadata is SafeRE-internal")
internal class MatcherTransitionInventoryTest {
    @Test
    @DisplayName("transition inventory covers every public Matcher method")
    fun transitionInventoryCoversPublicMatcherMethods() {
        val actualPublicMethods =
            Arrays.stream<Method>(Matcher::class.java.getDeclaredMethods())
                .filter { method: Method? -> Modifier.isPublic(method!!.modifiers) }
                .filter { method: Method? -> !method!!.isBridge }
                .filter { method: Method? -> !method!!.isSynthetic }
                .map<MatcherTransitionInventory.Signature?> { method: Method? -> signature(method!!) }
                .collect(Collectors.toSet())

        val inventoriedMethods =
            MatcherTransitionInventory.transitions().stream()
                .map<MatcherTransitionInventory.Signature?>(Transition::signature)
                .collect(Collectors.toSet())

        Assertions.assertThat<String?>(formatMissing(actualPublicMethods, inventoriedMethods))
            .`as`("public Matcher methods missing transition metadata")
            .isEmpty()
        Assertions.assertThat<String?>(formatMissing(inventoriedMethods, actualPublicMethods))
            .`as`("transition metadata entries with no public Matcher method")
            .isEmpty()
    }

    @Test
    @DisplayName("UTF-8 transition inventory covers every public Utf8Matcher method")
    fun utf8TransitionInventoryCoversPublicMatcherMethods() {
        val actualPublicMethods =
            Arrays.stream<Method>(Utf8Matcher::class.java.getDeclaredMethods())
                .filter { method: Method? -> Modifier.isPublic(method!!.modifiers) }
                .filter { method: Method? -> !method!!.isBridge }
                .filter { method: Method? -> !method!!.isSynthetic }
                .map<MatcherTransitionInventory.Signature?> { method: Method? -> signature(method!!) }
                .collect(Collectors.toSet())

        val inventoriedMethods =
            MatcherTransitionInventory.utf8Transitions().stream()
                .map<MatcherTransitionInventory.Signature?>(Transition::signature)
                .collect(Collectors.toSet())

        Assertions.assertThat<String?>(formatMissing(actualPublicMethods, inventoriedMethods))
            .`as`("public Utf8Matcher methods missing transition metadata")
            .isEmpty()
        Assertions.assertThat<String?>(formatMissing(inventoriedMethods, actualPublicMethods))
            .`as`("UTF-8 transition metadata entries with no public Utf8Matcher method")
            .isEmpty()
    }

    @Test
    @DisplayName("public instance transitions declare deferred capture behavior")
    fun publicInstanceTransitionsDeclareDeferredCaptureBehavior() {
        Assertions.assertThat<Transition?>(MatcherTransitionInventory.transitions())
            .filteredOn(Predicate { transition: Transition? -> !transition!!.signature.isStatic })
            .allSatisfy(
                ThrowingConsumer { transition: Transition? ->
                    Assertions.assertThat<DeferredCaptureEffect?>(transition!!.deferredCaptureEffect)
                        .`as`("%s", format(transition.signature))
                        .isNotNull()
                        .isNotEqualTo(DeferredCaptureEffect.NONE)
                })
    }

    @Test
    @DisplayName("static utility transitions are explicitly non-stateful")
    fun staticUtilityTransitionsAreExplicitlyNonStateful() {
        val quoteReplacement =
            MatcherTransitionInventory.transitionFor(
                MatcherTransitionInventory.Signature(
                    "quoteReplacement", List.of<Class<*>?>(String::class.java), true
                )
            )
                .orElseThrow()

        Assertions.assertThat<ResultEffect?>(quoteReplacement.resultEffect)
            .isEqualTo(ResultEffect.STATIC_UTILITY)
        Assertions.assertThat<DeferredCaptureEffect?>(quoteReplacement.deferredCaptureEffect)
            .isEqualTo(DeferredCaptureEffect.NONE)
    }

    companion object {
        private fun signature(method: Method): MatcherTransitionInventory.Signature {
            return MatcherTransitionInventory.Signature(
                method.name,
                List.of<Class<*>?>(*method.parameterTypes),
                Modifier.isStatic(method.modifiers)
            )
        }

        private fun formatMissing(
            expected: MutableSet<MatcherTransitionInventory.Signature?>,
            actual: MutableSet<MatcherTransitionInventory.Signature?>
        ): MutableList<String?> {
            return expected.stream()
                .filter { signature: MatcherTransitionInventory.Signature? -> !actual.contains(signature) }
                .map<String?> { signature: MatcherTransitionInventory.Signature? -> format(signature!!) }
                .sorted(Comparator.naturalOrder<String?>())
                .toList()
        }

        private fun format(signature: MatcherTransitionInventory.Signature): String {
            val params =
                signature.parameterTypes.stream()
                    .map<String?> { obj: Class<*>? -> obj!!.getSimpleName() }
                    .collect(Collectors.joining(", "))
            val prefix = if (signature.isStatic) "static " else ""
            return prefix + signature.name + "(" + params + ")"
        }
    }
}
