// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import java.util.List

/** Finite priority domain extracted from the existing matching regressions.  */
internal object PriorityCases {
    val CASES: MutableList<Case?> = List.of<Case?>(
        Case("a+?b?", mutableListOf<String?>("aaab", "aab", "ab")),
        Case("(?:a|ab)c?", mutableListOf<String?>("abc", "ab", "ac")),
        Case("a??b?", mutableListOf<String?>("ab", "a", "b"))
    )

    fun choose(data: FuzzedDataProvider): Case? {
        return data.pickValue<Case?>(CASES)
    }

    @JvmRecord
    internal data class Case(val regex: String?, val inputs: MutableList<String?>?)
}
