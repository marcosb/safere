// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.safere.MultiAnchorDescriptor.StartPlan
import java.nio.charset.StandardCharsets
import java.util.Map
import java.util.function.Consumer

@DisabledForCrosscheck("tests internal acceleration controls and compares directly with the JDK")
internal class FixedOffsetClassAccelerationTest {
    @Test
    fun interiorClassesAccelerateUnselectivePrefixes() {
        for (regex in mutableListOf<String>(
            " [0-9]{4,}",
            "  [0-9]+",
            "( )([0-9]+)",
            "[a-z]{2}[0-9]+",
            " {2}[A-F0-9]+"
        )) {
            val pattern = Pattern.compile(regex)
            Assertions.assertThat<StringStartAccelerator?>(pattern.stringStartAccelerator()).`as`(regex).isNotNull()
            Assertions.assertThat<Utf8StartAccelerator?>(pattern.utf8StartAccelerator()).`as`(regex).isNotNull()
        }
    }

    @Test
    fun exactOffsetsPropagateThroughCapturesAndRequiredRepetitions() {
        for (entry in Map.of<String?, Int?>(
            " [0-9]{4,}", 1,
            "( )([0-9]+)", 1,
            "(?:[a-z]{2})([0-9]+)", 2,
            "[ \\t\\r\\n]{3}[A-F0-9]+", 3,
            "(?:( )[0-9]+)+", 1
        )
            .entries) {
            Assertions.assertThat<StartPlan?>(Pattern.compile(entry.key).startPlan())
                .`as`(entry.key)
                .isInstanceOfSatisfying<StartPlan.FixedOffsetClass?>(
                    StartPlan.FixedOffsetClass::class.java,
                    Consumer { plan: StartPlan.FixedOffsetClass? ->
                        Assertions.assertThat(plan!!.offset).isEqualTo(entry.value)
                    })
        }
    }

    @Test
    fun variableOrNonAsciiPrefixWidthsDoNotProduceFixedOffsets() {
        for (regex in mutableListOf<String>(
            " ?[0-9]+",
            "[a-z]{1,2}[0-9]+",
            "[é😀][0-9]+",
            "(?: |é)[0-9]+",
            " (?U:\\d+)"
        )) {
            Assertions.assertThat<StartPlan?>(Pattern.compile(regex).startPlan())
                .`as`(regex)
                .isNotInstanceOf(StartPlan.FixedOffsetClass::class.java)
        }
    }

    @Test
    fun variableLeadingExpansionsDoNotWrapFixedClassOffsets() {
        for (regex in mutableListOf<String>("[0-9 ]+ [0-9]{4,}", "[0-9 ]* [0-9]+")) {
            val pattern = Pattern.compile(regex)
            Assertions.assertThat<StringStartAccelerator?>(pattern.stringStartAccelerator()).`as`(regex).isNull()
            Assertions.assertThat<Utf8StartAccelerator?>(pattern.utf8StartAccelerator()).`as`(regex).isNull()
            assertFindSequence(regex!!, " 1234 5678 ".repeat(200), false)
        }
    }

    @Test
    fun unicodeClassesInsideThePrefixPreserveCoordinateWidths() {
        // JDK 26 Pattern specifies concatenation and Unicode character-class membership. A class
        // consuming one code point need not consume one UTF-16 unit or one UTF-8 byte.
        for (prefix in mutableListOf<String>(" [é😀]", " [aé]", " [a-zé]", " [é😀]{2}", " (?:[aé]{2})")) {
            val regex = prefix + "([0-9]+)"
            for (input in mutableListOf<String>(" 😀1234 é5678", " aa1234 éé5678", " 😀😀1234 a😀5678")) {
                assertFindSequence(regex, input!!, false)
                assertFindSequence(regex, "noise é😀".repeat(100) + input, true)
            }
            Assertions.assertThat<StartPlan?>(Pattern.compile(regex).startPlan())
                .`as`(regex)
                .isNotInstanceOf(StartPlan.FixedOffsetClass::class.java)
        }
    }

    @Test
    fun consumingAtomsInsideThePrefixDoNotAcquireZeroWidthOffsets() {
        for (prefix in mutableListOf<String>(" .", " .{2}", " \\X")) {
            val regex = prefix + "([0-9]+)"
            for (input in mutableListOf<String>(" x1234", " 😀1234", " aé1234", " 😀😀1234", " 👩‍🔬1234")) {
                assertFindSequence(regex, input!!, false)
            }
            Assertions.assertThat<StartPlan?>(Pattern.compile(regex).startPlan())
                .`as`(regex)
                .isNotInstanceOf(StartPlan.FixedOffsetClass::class.java)
        }
    }

    @Test
    fun findSequencesPreservePriorityCapturesAndUnicodeCoordinates() {
        for (regex in mutableListOf<String>(
            " [0-9]{4,}",
            "e([0-9]+)",
            "(?i:e)([0-9]+)",
            " ([0-9]{4,}?)",
            "( )([0-9]{1,3})",
            " (?:([0-9]+)|([A-F]+))",
            "[a-z]{2}([0-9]+)",
            " {2}([A-F0-9]+)",
            "\\B ([0-9]+)",
            " ([0-9]+)$"
        )) {
            for (noise in java.util.List.of<String?>("", "words without digits ".repeat(100), "é😀 words ".repeat(100))) {
                for (suffix in mutableListOf<String>(
                    "",
                    "123456 12 1234 56789",
                    " 2026😀 12345",
                    " aa42 bb1234",
                    "  1A2B  FF",
                    " 1 2 3",
                    "e12 E345"
                )) {
                    val input = noise + suffix
                    for (region in mutableListOf<Boolean>(false, true)) {
                        assertFindSequence(regex!!, input, region!!)
                    }
                }
            }
        }
    }

    @Test
    fun denseFalseCandidatesAndFailedSuffixesPreserveTheLeftmostMatch() {
        for (regex in mutableListOf<String>(" ([0-9]{4,})", "[a-z]{2}([0-9]+)Z")) {
            for (unit in mutableListOf<String>("1111x", " 1x", "aa1234Y", "é😀1111x")) {
                assertFindSequence(regex!!, unit!!.repeat(2000) + " aa1234Z 56789", false)
            }
        }
    }

    @Test
    fun denseInteriorClassesPreserveMatchesAfterRareLeadingCharacters() {
        for (regex in mutableListOf<String>(
            " ([0-9]{4,})",
            "[a-z]{2}([0-9]+)",
            "[ \\t]{3}([A-F0-9]+)",
            " {32}([0-9]+)"
        )) {
            val digits = "1234567890".repeat(2000)
            for (tail in java.util.List.of<String?>("", " x", " aa1234Z", "   A1234Z", " ".repeat(32) + "12345")) {
                assertFindSequence(regex!!, digits + tail, false)
                assertFindSequence(regex, "é😀" + digits + tail, true)
            }
        }
    }

    @Test
    fun replacementsUseTheSameCapturesAndMatchSequence() {
        val input = "é😀 noise ".repeat(200) + " 12 12345 6789"
        for (regex in mutableListOf<String>(" ([0-9]{4,})", " ([0-9]{1,3}?)")) {
            val expected = java.util.regex.Pattern.compile(regex).matcher(input).replaceAll("<$1>")
            Assertions.assertThat(org.safere.Pattern.compile(regex).matcher(input).replaceAll("<$1>"))
                .isEqualTo(expected)
        }
    }

    @Test
    fun explicitFindStartsAndResetPreserveSearchState() {
        val input = "😀 1234x 56789"
        for (start in 0..input.length) {
            val safe = org.safere.Pattern.compile(" ([0-9]{4,})").matcher(input)
            val jdk = java.util.regex.Pattern.compile(" ([0-9]{4,})").matcher(input)
            val matched = jdk.find(start)
            Assertions.assertThat(safe.find(start)).isEqualTo(matched)
            if (matched) {
                Assertions.assertThat(safe.start()).isEqualTo(jdk.start())
                Assertions.assertThat(safe.group(1)).isEqualTo(jdk.group(1))
            }
            while (jdk.find()) {
                Assertions.assertThat(safe.find()).isTrue()
                Assertions.assertThat(safe.start()).isEqualTo(jdk.start())
                Assertions.assertThat(safe.group(1)).isEqualTo(jdk.group(1))
            }
            Assertions.assertThat(safe.find()).isFalse()
            Assertions.assertThat(safe.reset().find()).isTrue()
            Assertions.assertThat(safe.start()).isEqualTo(2)
        }
    }

    @Test
    fun interiorClassAnalysisIsStackSafeForDeepCaptures() {
        val regex = " " + "(".repeat(2000) + "[0-9]{4,}" + ")".repeat(2000)
        val pattern = org.safere.Pattern.compile(regex)
        Assertions.assertThat<StartPlan?>(pattern.startPlan())
            .isInstanceOf(StartPlan.FixedOffsetClass::class.java)
        Assertions.assertThat(pattern.matcher("noise 1234x").find()).isTrue()
    }

    companion object {
        private val WITHOUT_ACCELERATION: EnginePathOptions =
            EnginePathOptions.builder().startAcceleration(false).build()

        private fun assertFindSequence(regex: String, input: String, region: Boolean) {
            val pattern = org.safere.Pattern.compile(regex)
            val safe = pattern.matcher(input)
            val unaccelerated = org.safere.Pattern.compile(regex, 0, WITHOUT_ACCELERATION).matcher(input)
            val jdk = java.util.regex.Pattern.compile(regex).matcher(input)
            val padded = ("pad" + input + "tail").toByteArray(StandardCharsets.UTF_8)
            val bytes = Utf8Input.trusted(padded, 3, input.toByteArray(StandardCharsets.UTF_8).size)
            val utf8 = pattern.matcher(bytes)
            if (region && !input.isEmpty()) {
                val begin = input.offsetByCodePoints(0, 1)
                val end = input.offsetByCodePoints(input.length, -1)
                if (begin > end) {
                    return
                }
                safe.region(begin, end)
                unaccelerated.region(begin, end)
                jdk.region(begin, end)
                utf8.region(byteOffset(input, begin), byteOffset(input, end))
            }
            while (true) {
                val matched = jdk.find()
                Assertions.assertThat(safe.find()).`as`("%s on %s", regex, input).isEqualTo(matched)
                Assertions.assertThat(unaccelerated.find()).isEqualTo(matched)
                Assertions.assertThat(utf8.find()).isEqualTo(matched)
                if (!matched) {
                    break
                }
                for (group in 0..jdk.groupCount()) {
                    Assertions.assertThat(safe.start(group)).isEqualTo(jdk.start(group))
                    Assertions.assertThat(safe.end(group)).isEqualTo(jdk.end(group))
                    Assertions.assertThat(safe.group(group)).isEqualTo(jdk.group(group))
                    Assertions.assertThat(unaccelerated.start(group)).isEqualTo(jdk.start(group))
                    Assertions.assertThat(unaccelerated.end(group)).isEqualTo(jdk.end(group))
                    Assertions.assertThat(utf8.start(group)).isEqualTo(byteOffset(input, jdk.start(group)))
                    Assertions.assertThat(utf8.end(group)).isEqualTo(byteOffset(input, jdk.end(group)))
                }
            }
            if (!region) {
                Assertions.assertThat(pattern.find(bytes))
                    .isEqualTo(java.util.regex.Pattern.compile(regex).matcher(input).find())
            }
        }

        private fun byteOffset(input: String, offset: Int): Int {
            return if (offset < 0) -1 else input.substring(0, offset).toByteArray(StandardCharsets.UTF_8).size
        }
    }
}
