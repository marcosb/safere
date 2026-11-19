// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*

/** A strategy-specific explanation of routing or fallback.  */
@JvmRecord
data class StrategyDecision(strategy: MatchStrategy?, disposition: StrategyDisposition?, reason: StrategyReason?) {
    val strategy: MatchStrategy?
    val disposition: StrategyDisposition?
    val reason: StrategyReason?

    /** Creates a strategy decision.  */
    init {
        this.reason = reason
        this.disposition = disposition
        this.strategy = strategy
        Objects.requireNonNull<MatchStrategy?>(strategy, "strategy")
        Objects.requireNonNull<StrategyDisposition?>(disposition, "disposition")
        Objects.requireNonNull<StrategyReason?>(reason, "reason")
    }
}
