// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Receives synchronous, low-cardinality diagnostics about pattern compilation and matching.
 * 
 * 
 * Callbacks run on the compiling or matching thread. Implementations are responsible for their
 * own thread safety, aggregation, storage, and cardinality policy. Public callback exceptions
 * propagate to the caller; operation callbacks run only after matcher state has been finalized.
 * 
 * 
 * When [.NONE] is installed, matching allocates no diagnostics objects. Enabled listeners
 * receive one bounded event per supported public operation, irrespective of internal engine passes
 * or replacement match count.
 * 
 * 
 * Supported operations are [Matcher.matches], [Matcher.lookingAt], [ ][Matcher.find], the [Matcher] replacement operations, the corresponding matching
 * operations on [Utf8Matcher], and [Pattern.find].
 */
open class SafeReMatchDiagnostics private constructor(enabled: Boolean) {
    private val enabled: Boolean

    /** Creates an enabled diagnostics listener.  */
    protected constructor() : this(true)

    init {
        this.enabled = enabled
    }

    /**
     * Called after a pattern is compiled while this listener is installed.
     * 
     * @param event immutable pattern compilation event
     */
    open fun onPatternCompiled(event: PatternCompiledEvent?) {}

    /**
     * Called after a supported public operation completes normally.
     * 
     * @param event immutable operation summary
     */
    open fun onOperationCompleted(event: OperationDiagnostics?) {}

    /**
     * Called when an explicitly instrumented intentional-divergence path is exercised.
     * 
     * 
     * This is evidence about execution, not proof that a JDK mismatch is intentional. Not all
     * documented divergences have a distinct runtime branch; absence is not proof of agreement.
     * Callbacks run synchronously after parsing or matcher state finalization. Matching signals are
     * coalesced per public operation. If parsing fails, notification failures are suppressed on the
     * original syntax exception; otherwise callback exceptions propagate to the caller.
     * 
     * @param divergence the path exercised
     */
    open fun onIntentionalDivergence(divergence: IntentionalDivergence?) {}

    companion object {
        /** Listener used when match diagnostics are disabled.  */
        val NONE: SafeReMatchDiagnostics = SafeReMatchDiagnostics(false)

        /** Returns whether the given listener is enabled.  */
        fun isEnabled(listener: SafeReMatchDiagnostics): Boolean {
            return listener.enabled
        }
    }
}
