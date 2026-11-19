// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import org.safere.Pattern

/** Shared parser for benchmark flag-set names.  */
internal object BenchmarkFlags {
    fun parse(flagSet: String): Int {
        return when (flagSet) {
            "0" -> 0
            "CASE_INSENSITIVE" -> Pattern.Companion.CASE_INSENSITIVE
            "UNICODE_CHARACTER_CLASS" -> Pattern.Companion.UNICODE_CHARACTER_CLASS
            "CASE_INSENSITIVE_UNICODE_CASE" -> Pattern.Companion.CASE_INSENSITIVE or Pattern.Companion.UNICODE_CASE
            "CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS" -> Pattern.Companion.CASE_INSENSITIVE or Pattern.Companion.UNICODE_CHARACTER_CLASS
            else -> throw IllegalArgumentException("Unknown flag set: " + flagSet)
        }
    }
}
