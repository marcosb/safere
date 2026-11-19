// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.util.List
import java.util.function.IntConsumer

/** Unicode 17 rule interactions, independently of JDK implementation exceptions (issue #954).  */
internal class GraphemeRuleInteractionTest {
    @Test
    @DisabledForCrosscheck("Unicode rule oracle, including intentional grapheme differences")
    fun prependDoesNotHideIndicOrRegionalIndicatorContext() {
        for (prepend in mutableListOf<String?>("", "\u0600", "\u0600\u0600")) {
            assertClusters(prepend + "\u0915\u094D\u0915", List.of<String?>(prepend + "\u0915\u094D\u0915"))
            val ri = "\uD83C\uDDE6"
            assertClusters(prepend + ri.repeat(3), List.of<String?>(prepend + ri.repeat(2), ri))
        }
    }

    @Test
    @DisabledForCrosscheck("Unicode rule oracle, including intentional grapheme differences")
    fun indicContextUsesIncbRatherThanGraphemeExtend() {
        val consonant = "\u0915"
        val linker = "\u094D"
        for (extend in mutableListOf<String?>("", "\u0300", "\u200D", "\u0300\u200D\u0300")) {
            val conjunct = consonant + extend + linker + extend + consonant
            assertClusters(conjunct, List.of<String?>(conjunct))
            for (interrupt in mutableListOf<String?>("\u200C", "\u0903")) {
                // ZWNJ is GCB=Extend but InCB=None. SpacingMark also interrupts GB9c.
                val prefix = consonant + linker + extend + interrupt + linker
                assertClusters(prefix + consonant, List.of<String?>(prefix, consonant))
            }
        }
    }

    @Test
    @DisabledForCrosscheck("Unicode rule oracle, including intentional grapheme differences")
    fun hangulAndControlRulesPrecedeAttachmentRules() {
        for (hangul in mutableListOf<String?>("\u1100\u1160\u11A8", "\uAC00\u1160\u11A8", "\uAC01\u11A8")) {
            assertClusters("\u0600" + hangul + "\u0300", List.of<String?>("\u0600" + hangul + "\u0300"))
            assertClusters(hangul + "\u0300\u11A8", List.of<String?>(hangul + "\u0300", "\u11A8"))
        }
        for (control in mutableListOf<String?>("\r", "\n", "\r\n", "\u0000", "\u2028")) {
            assertClusters("\u0600" + control + "\u0300", List.of<String?>("\u0600", control, "\u0300"))
        }
    }

    @Test
    fun explicitBoundaryAfterScalarDoesNotInventBreaksInsideClusters() {
        for (cluster in mutableListOf<String?>("\r\n", "a\u0300", "a\u0903", "\u0600a")) {
            val lastScalar = Character.toString(cluster!!.codePointBefore(cluster.length))
            for (prefix in mutableListOf<String?>("", "xy", "\u0000\u0000")) {
                val input = prefix + cluster
                val matcher = Pattern.compile("(?s).\\b{g}").matcher(input)
                val actual: MutableList<String?> = ArrayList<String?>()
                while (matcher.find()) {
                    actual.add(matcher.group())
                }
                val expected: MutableList<String?> = ArrayList<String?>()
                prefix!!.codePoints().forEach(IntConsumer { cp: Int -> expected.add(Character.toString(cp)) })
                expected.add(lastScalar)
                Assertions.assertThat<String?>(actual).`as`("scalar boundaries on %s", input).isEqualTo(expected)
            }
        }
    }

    @Test
    @DisabledForCrosscheck("Transparent boundaries must see the following extender; JDK invents a break")
    fun transparentTrailingBoundarySeesExtendersAfterConsumedPrefix() {
        val matcher =
            Pattern.compile("\\b{g}a\\u0300\\b{g}")
                .matcher("aa\u0300\u0300")
                .region(1, 3)
                .useTransparentBounds(true)
        Assertions.assertThat(matcher.matches()).isFalse()
        Assertions.assertThat(matcher.lookingAt()).isFalse()
        Assertions.assertThat(matcher.find()).isFalse()
        Assertions.assertThat(matcher.useTransparentBounds(false).reset().region(1, 3).matches()).isTrue()
    }

    companion object {
        private fun assertClusters(text: String?, expected: MutableList<String?>?) {
            for (regex in mutableListOf<String?>("\\X", "(\\X)", "\\b{g}\\X\\b{g}")) {
                val matcher = Pattern.compile(regex).matcher(text)
                val actual: MutableList<String?> = ArrayList<String?>()
                while (matcher.find()) {
                    actual.add(matcher.group())
                }
                Assertions.assertThat<String?>(actual).`as`("/%s/ on %s", regex, text).isEqualTo(expected)
            }
        }
    }
}
