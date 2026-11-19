// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*
import java.util.List
import java.util.Set
import java.util.function.Function
import java.util.stream.Collectors

/**
 * Package-private inventory of public [Matcher] lifecycle transitions.
 * 
 * 
 * The inventory is deliberately close to [Matcher] so tests can verify that every public
 * Matcher method is assigned an explicit state-transition category when the API changes.
 */
internal object MatcherTransitionInventory {
    private val OBSERVER_ONLY: MutableSet<String?> = Set.of<String?>("observer-only")
    private val MATCH_RESULT: MutableSet<String?> = Set.of<String?>("observer-only", "match-result")
    private val REPLACEMENT: MutableSet<String?> = Set.of<String?>("observer-only", "replacement")
    private val STATIC_UTILITY: MutableSet<String?> = Set.of<String?>("static-utility")

    private val TRANSITIONS: MutableList<Transition?> = List.of<Transition?>(
        transition(
            signature("matches"),
            ResultEffect.MATCH_ATTEMPT,
            DeferredCaptureEffect.MAY_DEFER,
            CursorEffect.RESET_TO_REGION_START,
            ReplacementEffect.PRESERVE,
            StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
            CacheEffect.PRESERVE,
            OBSERVER_ONLY
        ),
        transition(
            signature("lookingAt"),
            ResultEffect.MATCH_ATTEMPT,
            DeferredCaptureEffect.MAY_DEFER,
            CursorEffect.RESET_TO_REGION_START,
            ReplacementEffect.PRESERVE,
            StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
            CacheEffect.PRESERVE,
            OBSERVER_ONLY
        ),
        transition(
            signature("find"),
            ResultEffect.MATCH_ATTEMPT,
            DeferredCaptureEffect.MAY_DEFER,
            CursorEffect.DERIVE_FROM_PREVIOUS_RESULT_THEN_SEARCH,
            ReplacementEffect.PRESERVE,
            StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
            CacheEffect.PRESERVE,
            OBSERVER_ONLY
        ),
        transition(
            signature("find", Int::class.javaPrimitiveType),
            ResultEffect.MATCH_ATTEMPT,
            DeferredCaptureEffect.CLEAR,
            CursorEffect.SET_FROM_ARGUMENT_THEN_SEARCH,
            ReplacementEffect.RESET_APPEND_POSITION,
            StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
            CacheEffect.PRESERVE,
            OBSERVER_ONLY
        ),
        transition(
            signature("results"),
            ResultEffect.MATCH_ATTEMPT,
            DeferredCaptureEffect.RESOLVE_FOR_SNAPSHOT,
            CursorEffect.DERIVE_FROM_PREVIOUS_RESULT_THEN_SEARCH,
            ReplacementEffect.PRESERVE,
            StructuralMutationEffect.DETECTS_CALLBACK_MUTATION,
            CacheEffect.PRESERVE,
            MATCH_RESULT
        ),
        transition(
            signature("groupCount"),
            ResultEffect.PRESERVE,
            DeferredCaptureEffect.PRESERVE,
            CursorEffect.PRESERVE,
            ReplacementEffect.PRESERVE,
            StructuralMutationEffect.NOT_STRUCTURAL,
            CacheEffect.PRESERVE,
            OBSERVER_ONLY
        ),
        matchObserver(signature("group")),
        matchObserver(signature("group", Int::class.javaPrimitiveType)),
        matchObserver(signature("group", String::class.java)),
        matchObserver(signature("start")),
        matchObserver(signature("start", Int::class.javaPrimitiveType)),
        matchObserver(signature("start", String::class.java)),
        matchObserver(signature("end")),
        matchObserver(signature("end", Int::class.javaPrimitiveType)),
        matchObserver(signature("end", String::class.java)),
        transition(
            staticSignature("quoteReplacement", String::class.java),
            ResultEffect.STATIC_UTILITY,
            DeferredCaptureEffect.NONE,
            CursorEffect.NONE,
            ReplacementEffect.NONE,
            StructuralMutationEffect.NONE,
            CacheEffect.NONE,
            STATIC_UTILITY
        ),
        replacementLoop(signature("replaceFirst", String::class.java)),
        replacementLoop(signature("replaceFirst", Function::class.java)),
        replacementLoop(signature("replaceAll", String::class.java)),
        replacementLoop(signature("replaceAll", Function::class.java)),
        appendReplacement(signature("appendReplacement", StringBuilder::class.java, String::class.java)),
        appendTail(signature("appendTail", StringBuilder::class.java)),
        appendReplacement(signature("appendReplacement", StringBuffer::class.java, String::class.java)),
        appendTail(signature("appendTail", StringBuffer::class.java)),
        transition(
            signature("reset"),
            ResultEffect.RESET_NO_ATTEMPT,
            DeferredCaptureEffect.CLEAR,
            CursorEffect.RESET_TO_INPUT_START,
            ReplacementEffect.RESET_APPEND_POSITION,
            StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
            CacheEffect.PRESERVE,
            OBSERVER_ONLY
        ),
        transition(
            signature("reset", CharSequence::class.java),
            ResultEffect.RESET_NO_ATTEMPT,
            DeferredCaptureEffect.CLEAR,
            CursorEffect.RESET_TO_INPUT_START,
            ReplacementEffect.RESET_APPEND_POSITION,
            StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
            CacheEffect.INVALIDATE_INPUT_CACHES,
            OBSERVER_ONLY
        ),
        transition(
            signature("region", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            ResultEffect.RESET_NO_ATTEMPT,
            DeferredCaptureEffect.CLEAR,
            CursorEffect.RESET_TO_REGION_START,
            ReplacementEffect.RESET_APPEND_POSITION,
            StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
            CacheEffect.INVALIDATE_REGION_ASSUMPTIONS,
            OBSERVER_ONLY
        ),
        observer(signature("regionStart")),
        observer(signature("regionEnd")),
        observer(signature("namedGroups")),
        observer(signature("pattern")),
        observer(signature("toString")),
        transition(
            signature("usePattern", Pattern::class.java),
            ResultEffect.PRESERVE,
            DeferredCaptureEffect.CLEAR,
            CursorEffect.DERIVE_FROM_PREVIOUS_RESULT_THEN_SEARCH,
            ReplacementEffect.PRESERVE,
            StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
            CacheEffect.INVALIDATE_PATTERN_CACHES,
            OBSERVER_ONLY
        ),
        boundsChange(signature("useTransparentBounds", Boolean::class.javaPrimitiveType)),
        observer(signature("hasTransparentBounds")),
        boundsChange(signature("useAnchoringBounds", Boolean::class.javaPrimitiveType)),
        observer(signature("hasAnchoringBounds")),
        observer(signature("hasMatch")),
        transition(
            signature("toMatchResult"),
            ResultEffect.REQUIRES_MATCH,
            DeferredCaptureEffect.RESOLVE_FOR_SNAPSHOT,
            CursorEffect.PRESERVE,
            ReplacementEffect.PRESERVE,
            StructuralMutationEffect.NOT_STRUCTURAL,
            CacheEffect.PRESERVE,
            MATCH_RESULT
        )
    )

    private val BY_SIGNATURE: MutableMap<Signature?, Transition?> = TRANSITIONS.stream()
        .collect(
            Collectors.toUnmodifiableMap(
                Transition::signature,
                Function { transition: Transition? -> transition })
        )

    private val UTF8_TRANSITIONS: MutableList<Transition?> = List.of<Transition?>(
        transition(
            signature("matches"),
            ResultEffect.MATCH_ATTEMPT,
            DeferredCaptureEffect.MAY_DEFER,
            CursorEffect.RESET_TO_REGION_START,
            ReplacementEffect.PRESERVE,
            StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
            CacheEffect.PRESERVE,
            OBSERVER_ONLY
        ),
        transition(
            signature("lookingAt"),
            ResultEffect.MATCH_ATTEMPT,
            DeferredCaptureEffect.MAY_DEFER,
            CursorEffect.RESET_TO_REGION_START,
            ReplacementEffect.PRESERVE,
            StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
            CacheEffect.PRESERVE,
            OBSERVER_ONLY
        ),
        transition(
            signature("find"),
            ResultEffect.MATCH_ATTEMPT,
            DeferredCaptureEffect.MAY_DEFER,
            CursorEffect.DERIVE_FROM_PREVIOUS_RESULT_THEN_SEARCH,
            ReplacementEffect.PRESERVE,
            StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
            CacheEffect.PRESERVE,
            OBSERVER_ONLY
        ),
        transition(
            signature("reset"),
            ResultEffect.RESET_NO_ATTEMPT,
            DeferredCaptureEffect.CLEAR,
            CursorEffect.RESET_TO_INPUT_START,
            ReplacementEffect.RESET_APPEND_POSITION,
            StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
            CacheEffect.PRESERVE,
            OBSERVER_ONLY
        ),
        transition(
            signature("region", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            ResultEffect.RESET_NO_ATTEMPT,
            DeferredCaptureEffect.CLEAR,
            CursorEffect.RESET_TO_REGION_START,
            ReplacementEffect.RESET_APPEND_POSITION,
            StructuralMutationEffect.INVALIDATES_ACTIVE_TRAVERSAL,
            CacheEffect.INVALIDATE_REGION_ASSUMPTIONS,
            OBSERVER_ONLY
        ),
        observer(signature("regionStart")),
        observer(signature("regionEnd")),
        observer(signature("groupCount")),
        matchObserver(signature("start")),
        matchObserver(signature("start", Int::class.javaPrimitiveType)),
        matchObserver(signature("end")),
        matchObserver(signature("end", Int::class.javaPrimitiveType)),
        replacementLoop(signature("replaceFirst", Utf8Sink::class.java, Utf8Input::class.java)),
        replacementLoop(signature("replaceAll", Utf8Sink::class.java, Utf8Input::class.java)),
        appendReplacement(signature("appendReplacement", Utf8Sink::class.java, Utf8Input::class.java)),
        appendTail(signature("appendTail", Utf8Sink::class.java))
    )

    fun transitions(): MutableList<Transition?> {
        return TRANSITIONS
    }

    fun utf8Transitions(): MutableList<Transition?> {
        return UTF8_TRANSITIONS
    }

    fun transitionFor(signature: Signature?): Optional<Transition?> {
        return Optional.ofNullable<Transition?>(BY_SIGNATURE.get(signature))
    }

    private fun signature(name: String?, vararg parameterTypes: Class<*>?): Signature {
        return Signature(name, false, *parameterTypes)
    }

    private fun staticSignature(name: String?, vararg parameterTypes: Class<*>?): Signature {
        return Signature(name, true, *parameterTypes)
    }

    private fun observer(signature: Signature?): Transition {
        return transition(
            signature,
            ResultEffect.PRESERVE,
            DeferredCaptureEffect.PRESERVE,
            CursorEffect.PRESERVE,
            ReplacementEffect.PRESERVE,
            StructuralMutationEffect.NOT_STRUCTURAL,
            CacheEffect.PRESERVE,
            OBSERVER_ONLY
        )
    }

    private fun matchObserver(signature: Signature?): Transition {
        return transition(
            signature,
            ResultEffect.REQUIRES_MATCH,
            DeferredCaptureEffect.RESOLVE_AS_NEEDED,
            CursorEffect.PRESERVE,
            ReplacementEffect.PRESERVE,
            StructuralMutationEffect.NOT_STRUCTURAL,
            CacheEffect.PRESERVE,
            MATCH_RESULT
        )
    }

    private fun replacementLoop(signature: Signature?): Transition {
        return transition(
            signature,
            ResultEffect.RESET_NO_ATTEMPT,
            DeferredCaptureEffect.MAY_DEFER,
            CursorEffect.DERIVE_FROM_PREVIOUS_RESULT_THEN_SEARCH,
            ReplacementEffect.MANAGED_BY_APPEND_OPERATIONS,
            StructuralMutationEffect.DETECTS_CALLBACK_MUTATION,
            CacheEffect.PRESERVE,
            REPLACEMENT
        )
    }

    private fun appendReplacement(signature: Signature?): Transition {
        return transition(
            signature,
            ResultEffect.REQUIRES_MATCH,
            DeferredCaptureEffect.RESOLVE_FOR_REPLACEMENT,
            CursorEffect.PRESERVE,
            ReplacementEffect.ADVANCE_APPEND_POSITION,
            StructuralMutationEffect.ORACLE_DEFINED,
            CacheEffect.PRESERVE,
            REPLACEMENT
        )
    }

    private fun appendTail(signature: Signature?): Transition {
        return transition(
            signature,
            ResultEffect.PRESERVE,
            DeferredCaptureEffect.PRESERVE,
            CursorEffect.PRESERVE,
            ReplacementEffect.PRESERVE,
            StructuralMutationEffect.ORACLE_DEFINED,
            CacheEffect.PRESERVE,
            REPLACEMENT
        )
    }

    private fun boundsChange(signature: Signature?): Transition {
        return transition(
            signature,
            ResultEffect.PRESERVE,
            DeferredCaptureEffect.RESOLVE_BEFORE_BOUNDS_CHANGE,
            CursorEffect.PRESERVE,
            ReplacementEffect.PRESERVE,
            StructuralMutationEffect.ORACLE_DEFINED,
            CacheEffect.INVALIDATE_REGION_ASSUMPTIONS,
            OBSERVER_ONLY
        )
    }

    private fun transition(
        signature: Signature?,
        resultEffect: ResultEffect?,
        deferredCaptureEffect: DeferredCaptureEffect?,
        cursorEffect: CursorEffect?,
        replacementEffect: ReplacementEffect?,
        structuralMutationEffect: StructuralMutationEffect?,
        cacheEffect: CacheEffect?,
        legalObservationGroups: MutableSet<String?>?
    ): Transition {
        return Transition(
            signature,
            resultEffect,
            deferredCaptureEffect,
            cursorEffect,
            replacementEffect,
            structuralMutationEffect,
            cacheEffect,
            legalObservationGroups
        )
    }

    internal enum class ResultEffect {
        RESET_NO_ATTEMPT,
        MATCH_ATTEMPT,
        REQUIRES_MATCH,
        PRESERVE,
        STATIC_UTILITY
    }

    internal enum class DeferredCaptureEffect {
        CLEAR,
        MAY_DEFER,
        RESOLVE_AS_NEEDED,
        RESOLVE_FOR_SNAPSHOT,
        RESOLVE_FOR_REPLACEMENT,
        RESOLVE_BEFORE_BOUNDS_CHANGE,
        PRESERVE,
        NONE
    }

    internal enum class CursorEffect {
        RESET_TO_INPUT_START,
        RESET_TO_REGION_START,
        SET_FROM_ARGUMENT_THEN_SEARCH,
        DERIVE_FROM_PREVIOUS_RESULT_THEN_SEARCH,
        PRESERVE,
        NONE
    }

    internal enum class ReplacementEffect {
        RESET_APPEND_POSITION,
        ADVANCE_APPEND_POSITION,
        MANAGED_BY_APPEND_OPERATIONS,
        PRESERVE,
        NONE
    }

    internal enum class StructuralMutationEffect {
        INVALIDATES_ACTIVE_TRAVERSAL,
        DETECTS_CALLBACK_MUTATION,
        ORACLE_DEFINED,
        NOT_STRUCTURAL,
        NONE
    }

    internal enum class CacheEffect {
        INVALIDATE_PATTERN_CACHES,
        INVALIDATE_INPUT_CACHES,
        INVALIDATE_REGION_ASSUMPTIONS,
        PRESERVE,
        NONE
    }

    @JvmRecord
    internal data class Signature(
        val name: String?,
        val parameterTypes: MutableList<Class<*>?>?,
        val isStatic: Boolean
    ) {
        constructor(name: String?, isStatic: Boolean, vararg parameterTypes: Class<*>?) : this(
            name,
            List.of<Class<*>?>(*parameterTypes),
            isStatic
        )
    }

    @JvmRecord
    internal data class Transition(
        val signature: Signature?,
        val resultEffect: ResultEffect?,
        val deferredCaptureEffect: DeferredCaptureEffect?,
        val cursorEffect: CursorEffect?,
        val replacementEffect: ReplacementEffect?,
        val structuralMutationEffect: StructuralMutationEffect?,
        val cacheEffect: CacheEffect?,
        val legalObservationGroups: MutableSet<String?>?
    )
}
