// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*
import java.util.Map

/**
 * Package-private engine-path controls for forced-path equivalence tests.
 * 
 * 
 * Production code uses [.allEnabled]. Tests in this package can compile a pattern with
 * selected shortcuts disabled to compare observable API traces against the default engine cascade.
 */
@JvmRecord
internal data class EnginePathOptions(
    val literalFastPaths: Boolean,
    val charClassMatchFastPaths: Boolean,
    val charClassReplacementFastPath: Boolean,
    val keywordAlternationFastPath: Boolean,
    val startAcceleration: Boolean,
    val shiftDfa: Boolean,
    val onePass: Boolean,
    val dfa: Boolean,
    val reverseDfa: Boolean,
    val bitState: Boolean,
    val lazyCaptureExtraction: Boolean
) {
    internal interface OptionAccessor {
        fun enabled(options: EnginePathOptions?): Boolean
    }

    internal class Builder {
        private var literalFastPaths = true
        private var charClassMatchFastPaths = true
        private var charClassReplacementFastPath = true
        private var keywordAlternationFastPath = true
        private var startAcceleration = true
        private var shiftDfa = true
        private var onePass = true
        private var dfa = true
        private var reverseDfa = true
        private var bitState = true
        private var lazyCaptureExtraction = true

        fun literalFastPaths(enabled: Boolean): Builder {
            literalFastPaths = enabled
            return this
        }

        fun charClassMatchFastPaths(enabled: Boolean): Builder {
            charClassMatchFastPaths = enabled
            return this
        }

        fun charClassReplacementFastPath(enabled: Boolean): Builder {
            charClassReplacementFastPath = enabled
            return this
        }

        fun keywordAlternationFastPath(enabled: Boolean): Builder {
            keywordAlternationFastPath = enabled
            return this
        }

        fun startAcceleration(enabled: Boolean): Builder {
            startAcceleration = enabled
            return this
        }

        fun shiftDfa(enabled: Boolean): Builder {
            shiftDfa = enabled
            return this
        }

        fun onePass(enabled: Boolean): Builder {
            onePass = enabled
            return this
        }

        fun dfa(enabled: Boolean): Builder {
            dfa = enabled
            return this
        }

        fun reverseDfa(enabled: Boolean): Builder {
            reverseDfa = enabled
            return this
        }

        fun bitState(enabled: Boolean): Builder {
            bitState = enabled
            return this
        }

        fun lazyCaptureExtraction(enabled: Boolean): Builder {
            lazyCaptureExtraction = enabled
            return this
        }

        fun build(): EnginePathOptions {
            return EnginePathOptions(
                literalFastPaths,
                charClassMatchFastPaths,
                charClassReplacementFastPath,
                keywordAlternationFastPath,
                startAcceleration,
                shiftDfa,
                onePass,
                dfa,
                reverseDfa,
                bitState,
                lazyCaptureExtraction
            )
        }
    }

    companion object {
        private val ALL_ENABLED: EnginePathOptions = builder().build()
        private val ACCESSORS: MutableMap<EnginePath?, OptionAccessor?> = buildAccessors()

        fun allEnabled(): EnginePathOptions {
            return ALL_ENABLED
        }

        fun builder(): Builder {
            return Builder()
        }

        fun accessors(): MutableMap<EnginePath?, OptionAccessor?> {
            return ACCESSORS
        }

        private fun buildAccessors(): MutableMap<EnginePath?, OptionAccessor?> {
            val accessors: EnumMap<EnginePath?, OptionAccessor?> =
                EnumMap<EnginePath?, OptionAccessor?>(EnginePath::class.java)
            accessors.put(EnginePath.LITERAL_FAST_PATHS, EnginePathOptions::literalFastPaths)
            accessors.put(
                EnginePath.CHAR_CLASS_MATCH_FAST_PATHS, EnginePathOptions::charClassMatchFastPaths
            )
            accessors.put(
                EnginePath.CHAR_CLASS_REPLACEMENT_FAST_PATH,
                EnginePathOptions::charClassReplacementFastPath
            )
            accessors.put(
                EnginePath.KEYWORD_ALTERNATION_FAST_PATH, EnginePathOptions::keywordAlternationFastPath
            )
            accessors.put(EnginePath.START_ACCELERATION, EnginePathOptions::startAcceleration)
            accessors.put(EnginePath.SHIFT_DFA, EnginePathOptions::shiftDfa)
            accessors.put(EnginePath.ONE_PASS, EnginePathOptions::onePass)
            accessors.put(EnginePath.DFA, EnginePathOptions::dfa)
            accessors.put(EnginePath.REVERSE_DFA, EnginePathOptions::reverseDfa)
            accessors.put(EnginePath.BIT_STATE, EnginePathOptions::bitState)
            accessors.put(EnginePath.LAZY_CAPTURE_EXTRACTION, EnginePathOptions::lazyCaptureExtraction)
            return Map.copyOf<EnginePath?, OptionAccessor?>(accessors)
        }
    }
}
