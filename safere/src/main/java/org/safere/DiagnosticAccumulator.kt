// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*

/** Enabled-only, bounded diagnostics state for one public operation.  */
internal class DiagnosticAccumulator {
    private var boundaryStrategy: MatchStrategy? = MatchStrategy.NONE
    private var captureStrategy: MatchStrategy? = MatchStrategy.NONE
    private val orderedParticipation = IntArray(PARTICIPATION_COUNT)
    private var participationSize = 0
    private val seenParticipation = LongArray(wordCount(PARTICIPATION_COUNT))
    private val decisions = LongArray(wordCount(DECISION_COUNT))
    private var forwardDfaSearchCount = 0
    private var reverseDfaSearchCount = 0
    private var matchCount = 0
    private var intentionalDivergences: EnumSet<IntentionalDivergence>? = null

    fun intentionalDivergence(divergence: IntentionalDivergence?) {
        if (intentionalDivergences == null) {
            intentionalDivergences = EnumSet.noneOf(IntentionalDivergence::class.java)
        }
        intentionalDivergences!!.add(divergence)
    }

    fun reportIntentionalDivergences(listener: SafeReMatchDiagnostics) {
        if (intentionalDivergences != null) {
            for (divergence in intentionalDivergences) {
                listener.onIntentionalDivergence(divergence)
            }
        }
    }

    fun boundary(strategy: MatchStrategy?) {
        if (boundaryStrategy == MatchStrategy.NONE) {
            boundaryStrategy = strategy
        }
    }

    fun capture(strategy: MatchStrategy?) {
        if (captureStrategy == MatchStrategy.NONE) {
            captureStrategy = strategy
        }
    }

    fun participate(strategy: MatchStrategy, role: StrategyRole) {
        val token: Int = strategyIndex(strategy) * ROLE_COUNT + roleIndex(role)
        val word = token / java.lang.Long.SIZE
        val bit = 1L shl (token % java.lang.Long.SIZE)
        if ((seenParticipation[word] and bit) != 0L) {
            return
        }
        seenParticipation[word] = seenParticipation[word] or bit
        orderedParticipation[participationSize++] = token
    }

    fun decision(strategy: MatchStrategy, disposition: StrategyDisposition, reason: StrategyReason) {
        val token: Int =
            ((strategyIndex(strategy) * DISPOSITION_COUNT + dispositionIndex(disposition)) * REASON_COUNT
                    + reasonIndex(reason))
        decisions[token / java.lang.Long.SIZE] =
            decisions[token / java.lang.Long.SIZE] or (1L shl (token % java.lang.Long.SIZE))
    }

    fun incrementMatchCount() {
        matchCount++
    }

    fun incrementForwardDfaSearchCount() {
        if (forwardDfaSearchCount < Int.MAX_VALUE) {
            forwardDfaSearchCount++
        }
    }

    fun incrementReverseDfaSearchCount() {
        if (reverseDfaSearchCount < Int.MAX_VALUE) {
            reverseDfaSearchCount++
        }
    }

    fun matchCount(count: Int) {
        matchCount = count
    }

    fun matchCount(): Int {
        return matchCount
    }

    fun captured(): Boolean {
        return captureStrategy != MatchStrategy.NONE
    }

    fun toEvent(
        pattern: PatternDescriptor?,
        operation: MatchOperation?,
        outcome: MatchOutcome?,
        captureMode: CaptureMode?,
        inputLength: Int
    ): OperationDiagnostics {
        val participation: MutableList<StrategyParticipation?> = ArrayList<StrategyParticipation?>(participationSize)
        for (i in 0..<participationSize) {
            val token = orderedParticipation[i]
            participation.add(
                StrategyParticipation(STRATEGIES[token / ROLE_COUNT], ROLES[token % ROLE_COUNT])
            )
        }
        val decisionSet: MutableSet<StrategyDecision?> = LinkedHashSet<StrategyDecision?>()
        for (token in 0..<DECISION_COUNT) {
            if ((decisions[token / java.lang.Long.SIZE] and (1L shl (token % java.lang.Long.SIZE))) == 0L) {
                continue
            }
            val strategyOrdinal: Int = token / (DISPOSITION_COUNT * REASON_COUNT)
            val remainder: Int = token % (DISPOSITION_COUNT * REASON_COUNT)
            decisionSet.add(
                StrategyDecision(
                    STRATEGIES[strategyOrdinal],
                    DISPOSITIONS[remainder / REASON_COUNT],
                    REASONS[remainder % REASON_COUNT]
                )
            )
        }
        val effectiveCaptureStrategy =
            if (captureMode == CaptureMode.EAGER) captureStrategy else MatchStrategy.NONE
        return OperationDiagnostics(
            pattern,
            operation,
            outcome,
            boundaryStrategy,
            effectiveCaptureStrategy,
            participation,
            decisionSet,
            forwardDfaSearchCount,
            reverseDfaSearchCount,
            captureMode,
            inputLength,
            matchCount
        )
    }

    companion object {
        // Boundary-only outcomes such as LENGTH need no auxiliary participation or decision slots.
        private val STRATEGIES = arrayOf<MatchStrategy?>(
            MatchStrategy.NONE,
            MatchStrategy.LITERAL,
            MatchStrategy.CHARACTER_CLASS,
            MatchStrategy.KEYWORD,
            MatchStrategy.SHIFT_DFA,
            MatchStrategy.ONE_PASS,
            MatchStrategy.DFA,
            MatchStrategy.BIT_STATE,
            MatchStrategy.NFA,
            MatchStrategy.LITERAL_ALTERNATION
        )
        private val ROLES = arrayOf<StrategyRole?>(
            StrategyRole.START_ACCELERATION,
            StrategyRole.REJECT_PREFILTER,
            StrategyRole.CANDIDATE_VERIFICATION
        )
        private val DISPOSITIONS = arrayOf<StrategyDisposition?>(
            StrategyDisposition.INAPPLICABLE, StrategyDisposition.BYPASSED, StrategyDisposition.FALLBACK
        )
        private val REASONS = arrayOf<StrategyReason?>(
            StrategyReason.INPUT_TOO_SMALL,
            StrategyReason.INPUT_TOO_LARGE,
            StrategyReason.CAPTURES_REQUIRED,
            StrategyReason.AUTHORITATIVE_BOUNDS_REQUIRED,
            StrategyReason.EXACT_NULLABLE_LOOP_SEMANTICS_REQUIRED,
            StrategyReason.DFA_BUDGET_EXCEEDED,
            StrategyReason.WORK_BUDGET_EXCEEDED,
            StrategyReason.OPTIMIZED_PATH_DISABLED,
            StrategyReason.SPECULATIVE_WINDOW_EXCEEDED,
            StrategyReason.SPECULATIVE_BACKOFF
        )
        private val STRATEGY_COUNT: Int = STRATEGIES.size
        private val ROLE_COUNT: Int = ROLES.size
        private val DISPOSITION_COUNT: Int = DISPOSITIONS.size
        private val REASON_COUNT: Int = REASONS.size
        private val PARTICIPATION_COUNT: Int = STRATEGY_COUNT * ROLE_COUNT
        private val DECISION_COUNT: Int = STRATEGY_COUNT * DISPOSITION_COUNT * REASON_COUNT

        private fun wordCount(bits: Int): Int {
            return (bits + java.lang.Long.SIZE - 1) / java.lang.Long.SIZE
        }

        private fun strategyIndex(strategy: MatchStrategy): Int {
            return when (strategy) {
                MatchStrategy.NONE -> 0
                MatchStrategy.LITERAL -> 1
                MatchStrategy.CHARACTER_CLASS -> 2
                MatchStrategy.KEYWORD -> 3
                MatchStrategy.SHIFT_DFA -> 4
                MatchStrategy.ONE_PASS -> 5
                MatchStrategy.DFA -> 6
                MatchStrategy.BIT_STATE -> 7
                MatchStrategy.NFA -> 8
                MatchStrategy.LITERAL_ALTERNATION -> 9
                MatchStrategy.LENGTH -> throw IllegalArgumentException("LENGTH is a boundary-only strategy")
            }
        }

        private fun roleIndex(role: StrategyRole): Int {
            return when (role) {
                StrategyRole.START_ACCELERATION -> 0
                StrategyRole.REJECT_PREFILTER -> 1
                StrategyRole.CANDIDATE_VERIFICATION -> 2
            }
        }

        private fun dispositionIndex(disposition: StrategyDisposition): Int {
            return when (disposition) {
                StrategyDisposition.INAPPLICABLE -> 0
                StrategyDisposition.BYPASSED -> 1
                StrategyDisposition.FALLBACK -> 2
            }
        }

        private fun reasonIndex(reason: StrategyReason): Int {
            return when (reason) {
                StrategyReason.INPUT_TOO_SMALL -> 0
                StrategyReason.INPUT_TOO_LARGE -> 1
                StrategyReason.CAPTURES_REQUIRED -> 2
                StrategyReason.AUTHORITATIVE_BOUNDS_REQUIRED -> 3
                StrategyReason.EXACT_NULLABLE_LOOP_SEMANTICS_REQUIRED -> 4
                StrategyReason.DFA_BUDGET_EXCEEDED -> 5
                StrategyReason.WORK_BUDGET_EXCEEDED -> 6
                StrategyReason.OPTIMIZED_PATH_DISABLED -> 7
                StrategyReason.SPECULATIVE_WINDOW_EXCEEDED -> 8
                StrategyReason.SPECULATIVE_BACKOFF -> 9
            }
        }
    }
}
