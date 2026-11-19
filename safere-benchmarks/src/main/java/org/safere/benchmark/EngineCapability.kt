// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

/** Native operations exposed by a cross-engine benchmark execution variant.  */
internal enum class EngineCapability {
    COMPILE,
    FIND,
    MATCHES,
    LOOKING_AT,
    GROUP_PARTICIPATION,
    GROUP_TEXT,
    REPLACE,
    APPEND_REPLACEMENT,
    SPLIT,
    MATCHER_RESET,
    REGIONS;

    fun bit(): Int {
        return when (this) {
            EngineCapability.COMPILE -> 1
            EngineCapability.FIND -> 1 shl 1
            EngineCapability.MATCHES -> 1 shl 2
            EngineCapability.LOOKING_AT -> 1 shl 3
            EngineCapability.GROUP_PARTICIPATION -> 1 shl 4
            EngineCapability.GROUP_TEXT -> 1 shl 5
            EngineCapability.REPLACE -> 1 shl 6
            EngineCapability.APPEND_REPLACEMENT -> 1 shl 7
            EngineCapability.SPLIT -> 1 shl 8
            EngineCapability.MATCHER_RESET -> 1 shl 9
            EngineCapability.REGIONS -> 1 shl 10
        }
    }
}
