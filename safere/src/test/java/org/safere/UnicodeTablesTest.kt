// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

/** Tests for [UnicodeTables].  */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class UnicodeTablesTest {
    @Test
    fun generatedTables_haveExpectedMetadata() {
        Assertions.assertThat(UnicodeGeneratedTables.GENERATOR_JAVA_VERSION).isNotBlank()
        Assertions.assertThat(UnicodeGeneratedTables.UNICODE_DATA_VERSION).matches("\\d+\\.\\d+\\.\\d+")
    }

    @Test
    fun generatedCategories_includeUnassignedCategory() {
        Assertions.assertThat<String, Array<IntArray>>(UnicodeGeneratedTables.CATEGORIES).containsKey("Cn")
        Assertions.assertThat<String, Array<IntArray>>(UnicodeTables.UNICODE_GROUPS).containsKey("Cn")
    }

    @Test
    fun generatedAssignedIsComplementOfUnassignedCategory() {
        val assigned = UnicodeGeneratedTables.BINARY_PROPERTIES.get("Assigned")
        val unassigned = UnicodeGeneratedTables.CATEGORIES.get("Cn")
        Assertions.assertThat(assigned).isNotNull()
        Assertions.assertThat(unassigned).isNotNull()
        val differences: MutableList<String> = ArrayList<String>()
        var assignedIndex = 0
        var unassignedIndex = 0
        for (cp in 0..Character.MAX_CODE_POINT) {
            while (assignedIndex < assigned!!.size && assigned[assignedIndex][1] < cp) {
                assignedIndex++
            }
            while (unassignedIndex < unassigned!!.size && unassigned[unassignedIndex][1] < cp) {
                unassignedIndex++
            }
            val inAssigned = assignedIndex < assigned.size && assigned[assignedIndex][0] <= cp
            val inUnassigned =
                unassignedIndex < unassigned.size && unassigned[unassignedIndex][0] <= cp
            if (inAssigned == inUnassigned && differences.size < 10) {
                differences.add(String.format("U+%04X", cp))
            }
        }
        // This invariant is independent of runtime Unicode data, so a broken Assigned table cannot
        // silently disable all JDK-derived canaries through their repertoire assumption.
        Assertions.assertThat<String?>(differences).`as`("Assigned must be the complement of generated Cn").isEmpty()
    }

    @Test
    fun generatedOtherCategoryIncludesUnassignedCategory() {
        val cn = UnicodeGeneratedTables.CATEGORIES.get("Cn")
        val c = UnicodeGeneratedTables.CATEGORIES.get("C")

        Assertions.assertThat(cn).isNotNull()
        Assertions.assertThat(c).isNotNull()
        for (range in cn!!) {
            Assertions.assertThat(rangeContainedIn(c!!, range))
                .`as`("Cn range U+%04X..U+%04X should be included in C", range[0], range[1])
                .isTrue()
        }
    }

    @Test
    fun generatedUnassignedCategoryDoesNotOverlapAssignedLeafCategories() {
        val cn = UnicodeGeneratedTables.CATEGORIES.get("Cn")

        Assertions.assertThat(cn).isNotNull()
        for (entry in UnicodeGeneratedTables.CATEGORIES.entries) {
            val name = entry.key
            if (name.length != 2 || name == "Cn") {
                continue
            }
            Assertions.assertThat(overlaps(cn!!, entry.value))
                .`as`("Cn should not overlap generated category %s", name)
                .isFalse()
        }
    }

    // --- Perl groups ---
    @Test
    fun perlGroups_hasFiveEntries() {
        Assertions.assertThat(UnicodeTables.PERL_GROUPS.size).isEqualTo(5)
    }

    @Test
    fun perlDigit_matchesZeroToNine() {
        val ranges = UnicodeTables.PERL_GROUPS.get("\\d")
        Assertions.assertThat(ranges).isNotNull()
        Assertions.assertThat(ranges!!.size).isEqualTo(1)
        Assertions.assertThat(ranges[0]).isEqualTo(intArrayOf(0x30, 0x39))
    }

    @Test
    fun perlSpace_matchesWhitespace() {
        val ranges = UnicodeTables.PERL_GROUPS.get("\\s")
        Assertions.assertThat(ranges).isNotNull()
        Assertions.assertThat(ranges!!.size >= 2).isTrue()
        // Should include tab (0x09), vertical tab (0x0B), and space (0x20).
        Assertions.assertThat(containsCodePoint(ranges, 0x09)).isTrue()
        Assertions.assertThat(containsCodePoint(ranges, 0x0B)).isTrue()
        Assertions.assertThat(containsCodePoint(ranges, 0x20)).isTrue()
    }

    @Test
    fun perlWord_matchesWordChars() {
        val ranges = UnicodeTables.PERL_GROUPS.get("\\w")
        Assertions.assertThat(ranges).isNotNull()
        Assertions.assertThat(containsCodePoint(ranges!!, '0'.code)).isTrue()
        Assertions.assertThat(containsCodePoint(ranges, '9'.code)).isTrue()
        Assertions.assertThat(containsCodePoint(ranges, 'A'.code)).isTrue()
        Assertions.assertThat(containsCodePoint(ranges, 'Z'.code)).isTrue()
        Assertions.assertThat(containsCodePoint(ranges, '_'.code)).isTrue()
        Assertions.assertThat(containsCodePoint(ranges, 'a'.code)).isTrue()
        Assertions.assertThat(containsCodePoint(ranges, 'z'.code)).isTrue()
    }

    // --- POSIX property groups ---
    @Test
    fun posixPropertyGroups_hasThirteenEntries() {
        Assertions.assertThat(UnicodeTables.POSIX_PROPERTY_GROUPS.size).isEqualTo(13)
    }

    @Test
    fun posixPropertyDigit_matchesZeroToNine() {
        val ranges = UnicodeTables.POSIX_PROPERTY_GROUPS.get("Digit")
        Assertions.assertThat(ranges).isNotNull()
        Assertions.assertThat(ranges!!.size).isEqualTo(1)
        Assertions.assertThat(ranges[0]).isEqualTo(intArrayOf(0x30, 0x39))
    }

    @Test
    fun posixPropertyAscii_matchesFullRange() {
        val ranges = UnicodeTables.POSIX_PROPERTY_GROUPS.get("ASCII")
        Assertions.assertThat(ranges).isNotNull()
        Assertions.assertThat(ranges!!.size).isEqualTo(1)
        Assertions.assertThat(ranges[0]).isEqualTo(intArrayOf(0x00, 0x7F))
    }

    @Test
    fun posixPropertyUpper_matchesUppercase() {
        val ranges = UnicodeTables.POSIX_PROPERTY_GROUPS.get("Upper")
        Assertions.assertThat(ranges).isNotNull()
        Assertions.assertThat(ranges!!.size).isEqualTo(1)
        Assertions.assertThat(ranges[0]).isEqualTo(intArrayOf(0x41, 0x5A))
    }

    // --- Case folding ---
    @Test
    fun caseFold_hasExpectedSize() {
        Assertions.assertThat(UnicodeTables.CASE_FOLD.size).isEqualTo(380)
    }

    @Test
    fun caseFold_firstEntryIsUppercaseAscii() {
        // A-Z folds to a-z by adding 32
        Assertions.assertThat(UnicodeTables.CASE_FOLD[0]).isEqualTo(intArrayOf(65, 90, 32))
    }

    @Test
    fun caseFold_entriesHaveThreeElements() {
        for (entry in UnicodeTables.CASE_FOLD) {
            Assertions.assertThat(entry.size).`as`("CaseFold entry should have {lo, hi, delta}").isEqualTo(3)
        }
    }

    @Test
    fun caseFold_rangesAreOrdered() {
        for (i in 1..<UnicodeTables.CASE_FOLD.size) {
            Assertions.assertThat(UnicodeTables.CASE_FOLD[i][0] > UnicodeTables.CASE_FOLD[i - 1][1])
                .withFailMessage(
                    ("CaseFold ranges should be non-overlapping and ordered: entry "
                            + i
                            + " lo="
                            + UnicodeTables.CASE_FOLD[i][0]
                            + " <= prev hi="
                            + UnicodeTables.CASE_FOLD[i - 1][1])
                )
                .isTrue()
        }
    }

    // --- To-lower ---
    @Test
    fun toLower_hasExpectedSize() {
        Assertions.assertThat(UnicodeTables.TO_LOWER.size).isEqualTo(212)
    }

    @Test
    fun toLower_firstEntryIsUppercaseAscii() {
        // A-Z maps to a-z by adding 32
        Assertions.assertThat(UnicodeTables.TO_LOWER[0]).isEqualTo(intArrayOf(65, 90, 32))
    }

    // --- Unicode groups ---
    @Test
    fun unicodeGroups_hasAllExpectedEntries() {
        // 7 major categories + subcategories + all JDK scripts (minus UNKNOWN).
        // The exact count depends on the JDK's Unicode version, so we check minimum bounds.
        Assertions.assertThat(UnicodeTables.UNICODE_GROUPS.size)
            .`as`("UNICODE_GROUPS should have categories + scripts")
            .isGreaterThanOrEqualTo(199) // RE2's original count; JDK 25 has more
    }

    @Test
    fun unicodeGroups_containsMajorScripts() {
        Assertions.assertThat(UnicodeTables.UNICODE_GROUPS.get("Arabic")).isNotNull()
        Assertions.assertThat(UnicodeTables.UNICODE_GROUPS.get("Latin")).isNotNull()
        Assertions.assertThat(UnicodeTables.UNICODE_GROUPS.get("Greek")).isNotNull()
        Assertions.assertThat(UnicodeTables.UNICODE_GROUPS.get("Han")).isNotNull()
        Assertions.assertThat(UnicodeTables.UNICODE_GROUPS.get("Cyrillic")).isNotNull()
        Assertions.assertThat(UnicodeTables.UNICODE_GROUPS.get("Hiragana")).isNotNull()
        Assertions.assertThat(UnicodeTables.UNICODE_GROUPS.get("Katakana")).isNotNull()
    }

    @Test
    fun unicodeGroups_containsGeneralCategories() {
        // Major categories
        Assertions.assertThat(UnicodeTables.UNICODE_GROUPS.get("L")).isNotNull() // Letter
        Assertions.assertThat(UnicodeTables.UNICODE_GROUPS.get("N")).isNotNull() // Number
        Assertions.assertThat(UnicodeTables.UNICODE_GROUPS.get("P")).isNotNull() // Punctuation
        Assertions.assertThat(UnicodeTables.UNICODE_GROUPS.get("S")).isNotNull() // Symbol
        Assertions.assertThat(UnicodeTables.UNICODE_GROUPS.get("Z")).isNotNull() // Separator
        Assertions.assertThat(UnicodeTables.UNICODE_GROUPS.get("C")).isNotNull() // Other

        // Subcategories
        Assertions.assertThat(UnicodeTables.UNICODE_GROUPS.get("Lu")).isNotNull() // Uppercase Letter
        Assertions.assertThat(UnicodeTables.UNICODE_GROUPS.get("Ll")).isNotNull() // Lowercase Letter
        Assertions.assertThat(UnicodeTables.UNICODE_GROUPS.get("Nd")).isNotNull() // Decimal Digit
    }

    @Test
    fun unicodeGroups_latinContainsAsciiLetters() {
        val latin = UnicodeTables.UNICODE_GROUPS.get("Latin")
        Assertions.assertThat(latin).isNotNull()
        Assertions.assertThat(containsCodePoint(latin!!, 'A'.code)).isTrue()
        Assertions.assertThat(containsCodePoint(latin, 'Z'.code)).isTrue()
        Assertions.assertThat(containsCodePoint(latin, 'a'.code)).isTrue()
        Assertions.assertThat(containsCodePoint(latin, 'z'.code)).isTrue()
    }

    @Test
    fun unicodeGroups_rangesAreValid() {
        for (entry in UnicodeTables.UNICODE_GROUPS.entries) {
            for (range in entry.value) {
                Assertions.assertThat(range.size)
                    .withFailMessage("Range in " + entry.key + " should have {lo, hi}")
                    .isEqualTo(2)
                Assertions.assertThat(range[0] <= range[1])
                    .withFailMessage(
                        "Range in " + entry.key + ": lo=" + range[0] + " > hi=" + range[1]
                    )
                    .isTrue()
                Assertions.assertThat(range[0] >= 0)
                    .withFailMessage("Range in " + entry.key + ": lo=" + range[0] + " is negative")
                    .isTrue()
                Assertions.assertThat(range[1] <= 0x10FFFF)
                    .withFailMessage(
                        "Range in " + entry.key + ": hi=" + range[1] + " exceeds max code point"
                    )
                    .isTrue()
            }
        }
    }

    // --- Sentinel constants ---
    @Test
    fun sentinelValues() {
        Assertions.assertThat(UnicodeTables.EVEN_ODD).isEqualTo(1)
        Assertions.assertThat(UnicodeTables.ODD_EVEN).isEqualTo(-1)
        Assertions.assertThat(UnicodeTables.EVEN_ODD_SKIP).isEqualTo(1 shl 30)
        Assertions.assertThat(UnicodeTables.ODD_EVEN_SKIP).isEqualTo((1 shl 30) + 1)
    }

    // --- simpleFold (ported from RE2/J UnicodeTest) ---
    @Test
    fun simpleFold_asciiLettersCaseFold() {
        // Every lowercase ASCII letter should be in a fold orbit with its uppercase equivalent.
        var r = 'a'.code
        while (r <= 'z'.code) {
            val upper = r - ('a'.code - 'A'.code)
            // Follow the fold orbit from lowercase to find uppercase.
            var found = false
            var f = r
            for (i in 0..4) {
                f = Inst.simpleFold(f)
                if (f == upper) {
                    found = true
                    break
                }
            }
            Assertions.assertThat(found)
                .`as`("simpleFold orbit of '%c' should include '%c'", r.toChar(), upper.toChar())
                .isTrue()
            r++
        }
    }

    @Test
    fun simpleFold_nonLetterIsIdentity() {
        // Non-letter code points should fold to themselves.
        Assertions.assertThat(Inst.simpleFold('{'.code)).isEqualTo('{'.code)
        Assertions.assertThat(Inst.simpleFold('/'.code)).isEqualTo('/'.code)
        Assertions.assertThat(Inst.simpleFold('0'.code)).isEqualTo('0'.code)
    }

    @Test
    fun simpleFold_unicodePairs() {
        // é (U+00E9) should fold to É (U+00C9) or vice versa.
        val e_acute_lower = 0x00E9 // é
        val e_acute_upper = 0x00C9 // É
        Assertions.assertThat(Inst.simpleFold(e_acute_lower)).isEqualTo(e_acute_upper)
        Assertions.assertThat(Inst.simpleFold(e_acute_upper)).isEqualTo(e_acute_lower)
    }

    @Test
    fun simpleFold_kelvinSign() {
        // Kelvin sign (U+212A) is in the fold orbit with 'K' (U+004B) and 'k' (U+006B).
        val kelvin = 0x212A
        val upper_k = 'K'.code
        val lower_k = 'k'.code
        // Follow the orbit starting from kelvin.
        var r = kelvin
        var foundUpperK = false
        var foundLowerK = false
        for (i in 0..3) {
            r = Inst.simpleFold(r)
            if (r == upper_k) {
                foundUpperK = true
            }
            if (r == lower_k) {
                foundLowerK = true
            }
        }
        Assertions.assertThat(foundUpperK).`as`("Kelvin sign orbit should include 'K'").isTrue()
        Assertions.assertThat(foundLowerK).`as`("Kelvin sign orbit should include 'k'").isTrue()
    }

    @Test
    fun caseInsensitiveMatching_unicodePairs() {
        // Verify case-insensitive matching works for some interesting Unicode pairs.
        val flags = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        Assertions.assertThat(Pattern.compile("é", flags).matcher("É").matches()).isTrue()
        Assertions.assertThat(Pattern.compile("Ú", flags).matcher("ú").matches()).isTrue()
        Assertions.assertThat(Pattern.compile("k", flags).matcher("\u212A").matches()).isTrue()
        Assertions.assertThat(Pattern.compile("K", flags).matcher("\u212A").matches()).isTrue()
    }

    companion object {
        // --- Helper ---
        private fun containsCodePoint(ranges: Array<IntArray>, cp: Int): Boolean {
            for (range in ranges) {
                if (cp >= range[0] && cp <= range[1]) {
                    return true
                }
            }
            return false
        }

        private fun rangeContainedIn(ranges: Array<IntArray>, target: IntArray): Boolean {
            for (range in ranges) {
                if (target[0] >= range[0] && target[1] <= range[1]) {
                    return true
                }
            }
            return false
        }

        private fun overlaps(left: Array<IntArray>, right: Array<IntArray>): Boolean {
            var i = 0
            var j = 0
            while (i < left.size && j < right.size) {
                val a = left[i]
                val b = right[j]
                if (a[1] < b[0]) {
                    i++
                } else if (b[1] < a[0]) {
                    j++
                } else {
                    return true
                }
            }
            return false
        }
    }
}
