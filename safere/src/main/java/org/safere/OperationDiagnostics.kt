// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*
import java.util.List
import java.util.Set

/**
 * Immutable summary of one normally completed public matching operation.
 * 
 * 
 * DFA search counts include every attempted search, including attempts that exceed the DFA state
 * budget. Replacement operations aggregate searches across all matches into their single event.
 * 
 * @param inputLength input length in UTF-16 code units for [Matcher] operations or UTF-8
 * bytes for [Utf8Matcher] and [Pattern.find] operations
 */
@JvmRecord
data class OperationDiagnostics(
    pattern: PatternDescriptor?,
    operation: MatchOperation?,
    outcome: MatchOutcome?,
    boundaryStrategy: MatchStrategy?,
    captureStrategy: MatchStrategy?,
    auxiliaryStrategies: MutableList<StrategyParticipation?>?,
    strategyDecisions: MutableSet<StrategyDecision?>?,
    forwardDfaSearchCount: Int,
    reverseDfaSearchCount: Int,
    captureMode: CaptureMode?,
    inputLength: Int,
    matchCount: Int
) {
    val pattern: PatternDescriptor?
    val operation: MatchOperation?
    val outcome: MatchOutcome?
    val boundaryStrategy: MatchStrategy?
    val captureStrategy: MatchStrategy?
    val auxiliaryStrategies: MutableList<StrategyParticipation?>?
    val strategyDecisions: MutableSet<StrategyDecision?>?
    val forwardDfaSearchCount: Int
    val reverseDfaSearchCount: Int
    val captureMode: CaptureMode?
    val inputLength: Int
    val matchCount: Int

    /** Creates an immutable operation summary.  */
    init {
        this.matchCount = matchCount
        this.inputLength = inputLength
        this.captureMode = captureMode
        this.reverseDfaSearchCount = reverseDfaSearchCount
        this.forwardDfaSearchCount = forwardDfaSearchCount
        this.captureStrategy = captureStrategy
        this.boundaryStrategy = boundaryStrategy
        this.outcome = outcome
        this.operation = operation
        this.pattern = pattern
        var auxiliaryStrategies = auxiliaryStrategies
        var strategyDecisions = strategyDecisions
        Objects.requireNonNull<PatternDescriptor?>(pattern, "pattern")
        Objects.requireNonNull<MatchOperation?>(operation, "operation")
        Objects.requireNonNull<MatchOutcome?>(outcome, "outcome")
        Objects.requireNonNull<MatchStrategy?>(boundaryStrategy, "boundaryStrategy")
        Objects.requireNonNull<MatchStrategy?>(captureStrategy, "captureStrategy")
        auxiliaryStrategies = List.copyOf<StrategyParticipation?>(auxiliaryStrategies)
        strategyDecisions = Set.copyOf<StrategyDecision?>(strategyDecisions)
        Objects.requireNonNull<CaptureMode?>(captureMode, "captureMode")
        require(!(forwardDfaSearchCount < 0 || reverseDfaSearchCount < 0 || inputLength < 0 || matchCount < 0)) { "counts and lengths must be non-negative" }
        this.auxiliaryStrategies = auxiliaryStrategies
        this.strategyDecisions = strategyDecisions
    }
}
