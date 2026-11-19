// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import org.safere.Pattern

/** The documented case-equivalence model, independent of JDK regex outcomes.  */
internal object CaseFamilyChecks {
    fun assertCaseFamilyClosureSafeRe(data: FuzzedDataProvider) {
        val family =
            data.pickValue<String>(mutableListOf<String?>("Iiİı", "KkK", "Σσς", "ÅåÅ", "ΩωΩ", "ßẞ", "Θθϑϴ", "ﬅﬆ"))
        val members = family.codePoints().toArray()
        val source = members[data.consumeInt(0, members.size - 1)]
        val target = members[data.consumeInt(0, members.size - 1)]
        val literal = "\\x{" + Integer.toHexString(source) + "}"
        val regex =
            when (data.consumeInt(0, 4)) {
                0 -> literal
                1 -> "[" + literal + "]"
                2 -> "[" + literal + "-" + literal + "]"
                3 -> "[^" + literal + "]"
                else -> "[^" + literal + "-" + literal + "]"
            }
        val actual =
            Pattern.compile(regex, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
                .matcher(String(Character.toChars(target)))
                .matches()
        if (actual == regex.startsWith("[^")) {
            throw AssertionError(
                "SafeRE case family closure: " + regex + " U+" + Integer.toHexString(target)
            )
        }
    }
}
