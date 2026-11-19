// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
/** Rule-based GB11 coverage beyond Unicode's GraphemeBreakTest.txt; see issue #936.  */
@DisabledForCrosscheck(
    ("SafeRE follows UAX #29 GB11; JDK 27 and earlier mishandle interrupted and Prepend-prefixed"
            + " chains")
)
internal class EmojiZwjGraphemeTest {
    @ParameterizedTest
    @ValueSource(strings = ["", "\u0301", "\u0301\u0301", "\uD83C\uDFFD"])
    fun onlyExtendPreservesPictographBeforeZwj(extend: String) {
        val pictograph = "\uD83D\uDC4D"
        for (interrupt in mutableListOf<String>("", "\u0903", "\u200D", "\u200D\u0301", "\u0903\u200D")) {
            val first = pictograph + extend + interrupt + extend + "\u200D"
            val last = pictograph + "\u0301"
            val expected = if (interrupt!!.isEmpty()) java.util.List.of<String?>(first + last) else java.util.List.of<String?>(first, last)
            assertClusters(first + last, expected)
            // A subsequent pictograph starts a fresh GB11 chain, even after an interruption.
            assertClusters(
                first + last + "\u200D" + pictograph,
                if (interrupt.isEmpty())
                    java.util.List.of<String?>(first + last + "\u200D" + pictograph)
                else
                    java.util.List.of<String?>(first, last + "\u200D" + pictograph)
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "\u0301", "\uFE0F", "\uD83C\uDFFD"])
    fun prependBeforePictographDoesNotDisableZwjChain(extend: String) {
        val pictograph = "\uD83D\uDC69"
        for (prepend in mutableListOf<String>("\u0600", "\u0600\u0600", "\uD804\uDCBD")) {
            val chain = pictograph + extend + "\u200D" + pictograph
            assertClusters(prepend + chain, java.util.List.of<String?>(prepend + chain))
            assertClusters(
                prepend + chain + "\u200D" + pictograph,
                java.util.List.of<String?>(prepend + chain + "\u200D" + pictograph)
            )
            // Prepend inside the GB11 prefix interrupts it; GB9b attaches the following ZWJ.
            assertClusters(
                pictograph + prepend + "\u200D" + pictograph,
                java.util.List.of<String?>(pictograph, prepend + "\u200D", pictograph)
            )
        }
    }

    @Test
    fun regionClipsPictographContextButNotPrependBeforeVisiblePictograph() {
        val input = "\u0600\uD83D\uDC69\u0301\u200D\uD83D\uDC69"
        for (start in mutableListOf<Int>(0, 1, 3, 4)) {
            val matcher = Pattern.compile("\\X").matcher(input).region(start!!, input.length)
            val actual: MutableList<String> = ArrayList<String>()
            while (matcher.find()) {
                actual.add(matcher.group()!!)
            }
            Assertions.assertThat<String?>(actual)
                .`as`("region start=%s", start)
                .isEqualTo(
                    if (start <= 1)
                        java.util.List.of<String?>(input.substring(start))
                    else
                        java.util.List.of<String?>(input.substring(start, 5), input.substring(5))
                )
        }
    }

    companion object {
        private fun assertClusters(input: String, expected: MutableList<String>) {
            for (regex in mutableListOf<String>("\\X", "\\X\\b{g}", "(\\X)")) {
                val pattern = Pattern.compile(regex)
                for (transparent in mutableListOf<Boolean>(false, true)) {
                    val matcher =
                        pattern
                            .matcher("a" + input + "z")
                            .region(1, input.length + 1)
                            .useTransparentBounds(transparent!!)
                    val actual: MutableList<String> = ArrayList<String>()
                    while (matcher.find()) {
                        actual.add(matcher.group()!!)
                    }
                    Assertions.assertThat<String?>(actual)
                        .`as`("%s on %s, transparent=%s", regex, input, transparent)
                        .isEqualTo(expected)
                }
                val bytes = input.toByteArray(StandardCharsets.UTF_8)
                val matcher = pattern.matcher(Utf8Input.validated(bytes))
                val actual: MutableList<String> = ArrayList<String>()
                while (matcher.find()) {
                    actual.add(String(bytes, matcher.start(), matcher.end() - matcher.start(), StandardCharsets.UTF_8))
                }
                Assertions.assertThat<String?>(actual).`as`("UTF-8 %s on %s", regex, input).isEqualTo(expected)
            }
            Assertions.assertThat<String?>(Pattern.compile("\\b{g}").split(input)).containsExactlyElementsOf(expected)
            Assertions.assertThat(Pattern.compile("\\X{" + expected.size + "}").matcher(input).matches()).isTrue()
        }
    }
}
