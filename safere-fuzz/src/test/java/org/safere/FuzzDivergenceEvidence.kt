// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.lang.Boolean
import java.util.*
import java.util.function.Supplier
import kotlin.String
import kotlin.toString

/** Bounded per-thread diagnostic buckets for exploratory fuzzing; never suppress comparisons.  */
object FuzzDivergenceEvidence {
    private val EVENTS: ThreadLocal<MutableSet<IntentionalDivergence?>?> =
        ThreadLocal.withInitial<MutableSet<IntentionalDivergence?>?>(Supplier {
            EnumSet.noneOf<IntentionalDivergence?>(IntentionalDivergence::class.java)
        })
    private val LISTENER: SafeReMatchDiagnostics = object : SafeReMatchDiagnostics() {
        override fun onIntentionalDivergence(divergence: IntentionalDivergence?) {
            EVENTS.get()!!.add(divergence)
        }
    }

    /** Starts a case; evidence collection is opt-in and leaves an existing listener unchanged.  */
    fun begin() {
        EVENTS.get()!!.clear()
        if (Boolean.getBoolean("safere.fuzz.divergenceEvidence")
            && !SafeReMatchDiagnostics.isEnabled(Pattern.diagnostics())
        ) {
            Pattern.setDiagnostics(LISTENER)
        }
    }

    /** Returns execution evidence; an empty set means no instrumented signal was observed.  */
    fun observed(): String {
        return EVENTS.get().toString()
    }
}
