// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets

/** Tests Unicode grapheme segmentation for unassigned code points and controls.  */
internal class UnassignedGraphemeTest {
    @ParameterizedTest
    @ValueSource(ints = [0x0378, 0x0379, 0xFFFF, 0x8D43F, 0xE1000])
    @DisabledForCrosscheck("JDK classifies unassigned grapheme Other code points as Control except U+0378; see #925")
    fun unassignedOtherKeepsExtendersInOneCluster(codePoint: Int) {
        // GB9 applies to unassigned Other code points just as it does to assigned bases.
        // U+8D43F followed by U+07EF is the reproducer from #925.
        val base = String(Character.toChars(codePoint))
        for (suffix in mutableListOf<String>("\u07EF", "\u0301", "\u200D", "\u07EF\u0301\u200D")) {
            val cluster = base + suffix
            Assertions.assertThat(Pattern.compile("\\X").matcher(cluster).matches()).`as`(cluster).isTrue()
            Assertions.assertThat(Pattern.compile("\\X{2}").matcher(cluster).matches()).`as`(cluster).isFalse()

            val matcher = Pattern.compile("\\X").matcher(cluster + "a")
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.start()).isZero()
            Assertions.assertThat(matcher.end()).isEqualTo(cluster.length)
            Assertions.assertThat(matcher.group()).isEqualTo(cluster)
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(cluster.length)
            Assertions.assertThat(matcher.end()).isEqualTo(cluster.length + 1)
            Assertions.assertThat(matcher.group()).isEqualTo("a")
            Assertions.assertThat(matcher.find()).isFalse()

            Assertions.assertThat<String?>(Pattern.compile("\\b{g}").split(cluster + "a")).containsExactly(cluster, "a")
            Assertions.assertThat<String?>(Pattern.compile("\\X").split("a" + cluster + "b", -1))
                .containsExactly("", "", "", "")
            Assertions.assertThat(
                Pattern.compile("\\X")
                    .matcher(Utf8Input.validated(cluster.toByteArray(StandardCharsets.UTF_8)))
                    .matches()
            )
                .isTrue()
        }
    }

    @ParameterizedTest
    @ValueSource(
        ints = [0x0000, 0x000D, 0x000A, 0x2065, 0xFFF0, 0xFFF8, 0xE0000, 0xE0002, 0xE001F, 0xE0080, 0xE00FF, 0xE01F0, 0xE0FFF
        ]
    )
    fun controlBreakTakesPrecedenceOverExtenderJoining(codePoint: Int) {
        // Unassigned default-ignorable ranges have grapheme property Control (UAX #29).
        val control = String(Character.toChars(codePoint))
        for (suffix in mutableListOf<String>("\u07EF", "\u0301", "\u200D")) {
            val input = control + suffix
            Assertions.assertThat(Pattern.compile("\\X").matcher(input).matches()).isFalse()
            Assertions.assertThat(Pattern.compile("\\X{2}").matcher(input).matches()).isTrue()
            val matcher = Pattern.compile("\\X").matcher(input)
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.start()).isZero()
            Assertions.assertThat(matcher.end()).isEqualTo(control.length)
            Assertions.assertThat(matcher.group()).isEqualTo(control)
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(control.length)
            Assertions.assertThat(matcher.end()).isEqualTo(input.length)
            Assertions.assertThat(matcher.group()).isEqualTo(suffix)
            Assertions.assertThat(matcher.find()).isFalse()
            Assertions.assertThat<String?>(Pattern.compile("\\b{g}").split(input)).containsExactly(control, suffix)
            // GB5 must also break before a control even when GB9b would otherwise join Prepend.
            Assertions.assertThat(Pattern.compile("\\X").matcher("\u0600" + control).matches()).isFalse()

            val utf8 = Pattern.compile("\\X").matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
            Assertions.assertThat(utf8.matches()).isFalse()
            utf8.reset()
            Assertions.assertThat(utf8.find()).isTrue()
            Assertions.assertThat(utf8.start()).isZero()
            Assertions.assertThat(utf8.end()).isEqualTo(control.toByteArray(StandardCharsets.UTF_8).size)
            Assertions.assertThat(utf8.find()).isTrue()
            Assertions.assertThat(utf8.start()).isEqualTo(control.toByteArray(StandardCharsets.UTF_8).size)
            Assertions.assertThat(utf8.end()).isEqualTo(input.toByteArray(StandardCharsets.UTF_8).size)
            Assertions.assertThat(utf8.find()).isFalse()
        }
    }
}
