// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import kotlin.math.min

class ParserStackSafetyFuzzer {
    @FuzzTest(maxDuration = "30s")
    fun parserStackSafety(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    companion object {
        private val INPUTS = mutableListOf<String?>("", "a", "b")

        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            for (depth in mutableListOf<Int?>(1, 8, 64, 512)) {
                FuzzSupport.assertFullMatchesJdk(Companion.nestedCharacterClass(depth!!), 0, INPUTS)
                FuzzSupport.assertFullMatchesJdk(Companion.nestedGroups(depth), 0, INPUTS)
                FuzzSupport.assertFullMatchesJdk(Companion.quantifiedNestedCaptures(depth), 0, INPUTS)
                FuzzSupport.assertFullMatchesJdk(Companion.nestedCountedRepeat(depth, "{0,2}"), 0, INPUTS)
                FuzzSupport.assertFullMatchesJdk(nestedQuantifiers(min(depth, 64)), 0, INPUTS)
                FuzzSupport.assertFullMatchesJdk(
                    homogeneousGapNesting(min(depth, 64), "[ab]"), 0, INPUTS
                )
                FuzzSupport.assertFullMatchesJdk(homogeneousGapNesting(min(depth, 64), "."), 0, INPUTS)
            }

            val depth = data.consumeInt(0, 512)
            when (data.consumeInt(0, 4)) {
                0 -> FuzzSupport.assertFullMatchesJdk(nestedCharacterClass(depth), 0, INPUTS)
                1 -> FuzzSupport.assertFullMatchesJdk(nestedGroups(depth), 0, INPUTS)
                2 -> FuzzSupport.assertFullMatchesJdk(quantifiedNestedCaptures(depth), 0, INPUTS)
                3 -> {
                    val quantifier = data.pickValue<String>(mutableListOf<String?>("{0}", "{1}", "{0,2}", "{1,2}"))
                    FuzzSupport.assertFullMatchesJdk(nestedCountedRepeat(depth, quantifier), 0, INPUTS)
                }

                else -> FuzzSupport.assertFullMatchesJdk(nestedQuantifiers(data.consumeInt(0, 64)), 0, INPUTS)
            }
        }

        private fun nestedCharacterClass(depth: Int): String {
            return "[".repeat(depth + 1) + "a" + "]".repeat(depth + 1)
        }

        private fun nestedGroups(depth: Int): String {
            return "(?:".repeat(depth) + "a" + ")".repeat(depth)
        }

        private fun quantifiedNestedCaptures(depth: Int): String {
            return "(".repeat(depth) + "a" + ")".repeat(depth) + "*"
        }

        private fun nestedCountedRepeat(depth: Int, quantifier: String): String {
            return nestedGroups(depth) + quantifier
        }

        private fun nestedQuantifiers(depth: Int): String {
            val regex = StringBuilder(depth * 6 + 1)
            regex.append("(?:".repeat(depth))
            regex.append('a')
            for (index in depth - 1 downTo 0) {
                regex.append(if (index % 2 == 0) ")?" else ")+")
            }
            return regex.toString()
        }

        private fun homogeneousGapNesting(depth: Int, atom: String?): String {
            return "foo" + "(?:".repeat(depth) + atom + (")?" + atom).repeat(depth) + "bar"
        }
    }
}
