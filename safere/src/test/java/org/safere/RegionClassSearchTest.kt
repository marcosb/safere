// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

@DisabledForCrosscheck("Exercises an internal scalar-class existence cache")
internal class RegionClassSearchTest {
    @Test
    fun cachedExistenceAgreesWithIndependentScalarScansAcrossChangingEdges() {
        for (regex in mutableListOf<String>("\\X?\\p{Cs}", "\\X?\\uDE00", "\\X?[a\\uDE00]")) {
            val cc =
                Pattern.compile(regex).findRejectPrefilter() as RejectPrefilter.CharClass?
            for (text in mutableListOf<String>("a😀😀!", "😀\uDE00😀!", "a😀\uD83D!", "\uD83Dx\uDE00😀")) {
                val searches = RegionClassSearch()
                val scanner: InputScanner = StringInputScanner(text)
                for (direction in intArrayOf(1, -1, 1)) {
                    for (step in 0..text!!.length) {
                        val end = if (direction == 1) step else text.length - step
                        for (start in 0..end) {
                            val region = StringRegionInputScanner(text, start, end)
                            val expected =
                                (region.indexOfCodePointClass(
                                    cc!!.ranges, cc.bitmap0, cc.bitmap1, 0, region.length
                                )
                                        >= 0)
                            Assertions.assertThat(searches.contains(cc, scanner, start, end))
                                .`as`("%s input=%s range=%s:%s", regex, text, start, end)
                                .isEqualTo(expected)
                        }
                    }
                }
            }
        }
    }
}
