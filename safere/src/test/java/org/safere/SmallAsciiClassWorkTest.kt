// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.safere.CharClassScanInfo.UnicodeSmallSet
import java.util.function.IntConsumer

@DisabledForCrosscheck("WorkCounter and input scanners are internal SafeRE APIs")
@Tag("work-counter")
internal class SmallAsciiClassWorkTest {
    @Test
    fun failedSearchAccountsForEachMemberSearch() {
        val text = "a".repeat(8192)
        val scanner: InputScanner = StringInputScanner(text)
        val info: CharClassScanInfo? = smallSet("XZ_")
        val work =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(scanner.indexOfCharClass(info, 0)).isEqualTo(-1) })
        // The bitmap prologue inspects each unit once; each absent member searches the remainder.
        Assertions.assertThat(work).isEqualTo(16L + 3L * (text.length - 16))
    }

    @Test
    fun repeatedSearchWorkIsBoundedByDistanceAdvanced() {
        for (members in arrayOf<String>("YZ", "XZ_")) {
            val info: CharClassScanInfo? = smallSet(members)
            for (noise in arrayOf<String>("a", "é", "中")) {
                for (gap in intArrayOf(17, 65, 200, 5000)) {
                    for (matches in intArrayOf(16, 128)) {
                        val text = (noise.repeat(gap - 1) + "Z").repeat(matches)
                        val scanner: InputScanner = StringInputScanner(text)
                        val work =
                            WorkCounter.countForTesting(
                                Runnable {
                                    var from = 0
                                    for (i in 0..<matches) {
                                        val found = scanner.indexOfCharClass(info, from)
                                        Assertions.assertThat(found).isEqualTo((i + 1) * gap - 1)
                                        from = found + 1
                                    }
                                    Assertions.assertThat(scanner.indexOfCharClass(info, from)).isEqualTo(-1)
                                })
                        Assertions.assertThat(work).`as`("observed %s gap %s", members, gap).isPositive()
                        Assertions.assertThat(work)
                            .`as`("%s gap %s matches %s", members, gap, matches)
                            .isLessThanOrEqualTo(members.length * (2L * text.length + 64L * matches))
                    }
                }
            }
        }
    }

    @Test
    fun matcherIterationUsesTheInstrumentedWindowSearch() {
        val pattern = Pattern.compile("[XZ_]")
        for (gap in intArrayOf(17, 65, 200, 5000)) {
            val text = ("a".repeat(gap - 1) + "Z").repeat(128)
            val work =
                WorkCounter.countForTesting(
                    Runnable {
                        val matcher = pattern.matcher(text)
                        var matches = 0
                        while (matcher.find()) {
                            matches++
                        }
                        Assertions.assertThat(matches).isEqualTo(128)
                    })
            Assertions.assertThat(work).isGreaterThan(text.length.toLong())
            Assertions.assertThat(work).isLessThanOrEqualTo(4L * (2L * text.length + 64L * 128))
        }
    }

    @Test
    fun nonAsciiMembersKeepTheSameBound() {
        // Mixed and all-non-ASCII pairs; the second member is absent or appears only at the end.
        for (members in arrayOf<String>("[\uFF3B", "\u3010\uFF3B")) {
            val info: CharClassScanInfo? = unicodeSmallSet(members)
            Assertions.assertThat<CharClassScanInfo?>(info).isInstanceOf(UnicodeSmallSet::class.java)
            val present = members.get(1)
            val other = members.get(0)
            for (noise in arrayOf<String>("a", "\u00e9", "\u4e2d")) {
                for (gap in intArrayOf(17, 65, 200, 5000)) {
                    for (suffix in arrayOf<String>("", other.toString())) {
                        val matches = 128
                        val text = (noise.repeat(gap - 1) + present).repeat(matches) + suffix
                        val scanner: InputScanner = StringInputScanner(text)
                        val work =
                            WorkCounter.countForTesting(
                                Runnable {
                                    var from = 0
                                    for (i in 0..<matches) {
                                        val found = scanner.indexOfCharClass(info, from)
                                        Assertions.assertThat(found).isEqualTo((i + 1) * gap - 1)
                                        from = found + 1
                                    }
                                    Assertions.assertThat(scanner.indexOfCharClass(info, from))
                                        .isEqualTo(if (suffix.isEmpty()) -1 else text.length - 1)
                                })
                        Assertions.assertThat(work)
                            .`as`("%s noise %s gap %s suffix '%s'", members, noise, gap, suffix)
                            .isLessThanOrEqualTo(2L * (2L * text.length + 64L * (matches + 1)))
                    }
                }
            }
        }
    }

    companion object {
        private fun unicodeSmallSet(members: String): CharClassScanInfo? {
            val builder = CharClassBuilder()
            members.chars().forEach(IntConsumer { r: Int -> builder.addRune(r) })
            return CharClassScanInfo.fromCharClass(builder.build())
        }

        private fun smallSet(members: String): CharClassScanInfo? {
            val builder = AsciiBitmap.Builder()
            for (i in 0..<members.length) {
                builder.add(members.get(i).code)
            }
            return CharClassScanInfo.fromAsciiBitmap(builder.build())
        }
    }
}
