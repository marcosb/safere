// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*
import java.util.List
import kotlin.jvm.JvmRecord

/** Machine-readable engine-path contract metadata for package-private tests.  */
@JvmRecord
internal data class EnginePathContract(
    val path: EnginePath?,
    val role: EnginePathRole?,
    val authorities: MutableSet<ResultAuthority?>?,
    val guards: MutableSet<SemanticGuard?>?
) {
    companion object {
        private val ALL: MutableList<EnginePathContract?> = List.of<EnginePathContract?>(
            EnginePathContract(
                EnginePath.LITERAL_FAST_PATHS,
                EnginePathRole.AUTHORITATIVE_PRODUCER,
                EnumSet.of<ResultAuthority?>(
                    ResultAuthority.NO_MATCH,
                    ResultAuthority.GROUP_ZERO,
                    ResultAuthority.SEARCH_CURSOR
                ),
                EnumSet.of<SemanticGuard?>(SemanticGuard.NO_USER_CAPTURES)
            ),
            EnginePathContract(
                EnginePath.CHAR_CLASS_MATCH_FAST_PATHS,
                EnginePathRole.AUTHORITATIVE_PRODUCER,
                EnumSet.of<ResultAuthority?>(ResultAuthority.NO_MATCH, ResultAuthority.GROUP_ZERO),
                EnumSet.of<SemanticGuard?>(SemanticGuard.WHOLE_PATTERN_SHAPE)
            ),
            EnginePathContract(
                EnginePath.CHAR_CLASS_REPLACEMENT_FAST_PATH,
                EnginePathRole.AUTHORITATIVE_PRODUCER,
                EnumSet.of<ResultAuthority?>(ResultAuthority.REPLACEMENT_RESULT),
                EnumSet.of<SemanticGuard?>(
                    SemanticGuard.NON_NULLABLE_PATTERN,
                    SemanticGuard.SIMPLE_REPLACEMENT,
                    SemanticGuard.WHOLE_PATTERN_SHAPE
                )
            ),
            EnginePathContract(
                EnginePath.KEYWORD_ALTERNATION_FAST_PATH,
                EnginePathRole.GUARDED_OPTIMIZATION,
                EnumSet.of<ResultAuthority?>(
                    ResultAuthority.NO_MATCH,
                    ResultAuthority.GROUP_ZERO,
                    ResultAuthority.CAPTURES,
                    ResultAuthority.SEARCH_CURSOR
                ),
                EnumSet.of<SemanticGuard?>(
                    SemanticGuard.WHOLE_PATTERN_SHAPE, SemanticGuard.LEFTMOST_FIRST_EQUIVALENT
                )
            ),
            EnginePathContract(
                EnginePath.START_ACCELERATION,
                EnginePathRole.FILTER,
                EnumSet.of<ResultAuthority?>(ResultAuthority.CANDIDATE_START),
                EnumSet.of<SemanticGuard?>(SemanticGuard.CONSERVATIVE_START_SET)
            ),
            EnginePathContract(
                EnginePath.SHIFT_DFA,
                EnginePathRole.GUARDED_OPTIMIZATION,
                EnumSet.of<ResultAuthority?>(ResultAuthority.NO_MATCH, ResultAuthority.GROUP_ZERO),
                EnumSet.of<SemanticGuard?>(SemanticGuard.WHOLE_PATTERN_SHAPE, SemanticGuard.BOUNDED_STATE)
            ),
            EnginePathContract(
                EnginePath.ONE_PASS,
                EnginePathRole.GUARDED_OPTIMIZATION,
                EnumSet.of<ResultAuthority?>(
                    ResultAuthority.NO_MATCH,
                    ResultAuthority.GROUP_ZERO,
                    ResultAuthority.CAPTURES,
                    ResultAuthority.SEARCH_CURSOR
                ),
                EnumSet.of<SemanticGuard?>(
                    SemanticGuard.LEFTMOST_FIRST_EQUIVALENT, SemanticGuard.CAPTURE_EQUIVALENT
                )
            ),
            EnginePathContract(
                EnginePath.DFA,
                EnginePathRole.PARTIAL_PRODUCER,
                EnumSet.of<ResultAuthority?>(ResultAuthority.NO_MATCH, ResultAuthority.GROUP_ZERO),
                EnumSet.of<SemanticGuard?>(SemanticGuard.GROUP_ZERO_EQUIVALENT)
            ),
            EnginePathContract(
                EnginePath.REVERSE_DFA,
                EnginePathRole.FILTER,
                EnumSet.of<ResultAuthority?>(ResultAuthority.CANDIDATE_START, ResultAuthority.NO_MATCH),
                EnumSet.of<SemanticGuard?>(SemanticGuard.GROUP_ZERO_EQUIVALENT)
            ),
            EnginePathContract(
                EnginePath.BIT_STATE,
                EnginePathRole.GUARDED_OPTIMIZATION,
                EnumSet.of<ResultAuthority?>(
                    ResultAuthority.NO_MATCH,
                    ResultAuthority.GROUP_ZERO,
                    ResultAuthority.CAPTURES,
                    ResultAuthority.SEARCH_CURSOR
                ),
                EnumSet.of<SemanticGuard?>(SemanticGuard.BOUNDED_STATE, SemanticGuard.CAPTURE_EQUIVALENT)
            ),
            EnginePathContract(
                EnginePath.LAZY_CAPTURE_EXTRACTION,
                EnginePathRole.PARTIAL_PRODUCER,
                EnumSet.of<ResultAuthority?>(ResultAuthority.GROUP_ZERO, ResultAuthority.DEFERRED_CAPTURES),
                EnumSet.of<SemanticGuard?>(SemanticGuard.CAPTURE_DEFERABLE)
            )
        )

        fun all(): MutableList<EnginePathContract?> {
            return ALL
        }
    }
}
