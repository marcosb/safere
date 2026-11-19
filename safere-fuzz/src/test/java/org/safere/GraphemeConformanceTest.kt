// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.util.VersionInfo
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.safere.fuzz.IcuGraphemes
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.*

()
import kotlin.text.offsetByCodePoints
import kotlin.text.split
import kotlin.text.startsWith
import kotlin.text.substring
import kotlin.text.toByteArray
import kotlin.text.toInt
import kotlin.text.toRegex
import kotlin.text.trim

/** Unicode property and boundary conformance against the pinned ICU4J version.  */
internal class GraphemeConformanceTest {
    @Test
    fun generatedPropertiesAgreeWithIcuUnicode17ForEveryCodePoint() {
        Assertions.assertThat(UnicodeGeneratedTables.UNICODE_DATA_VERSION).isEqualTo("17.0.0")
        Assertions.assertThat<VersionInfo?>(UCharacter.getUnicodeVersion())
            .isEqualTo(VersionInfo.getInstance(UnicodeGeneratedTables.UNICODE_DATA_VERSION))
        for (entry in UnicodeGeneratedTables.GRAPHEME_PROPERTIES.entries) {
            val name = entry.key
            val actual = BooleanArray(Character.MAX_CODE_POINT + 1)
            for (range in entry.value) {
                Arrays.fill(actual, range[0], range[1] + 1, true)
            }
            for (cp in actual.indices) {
                val property = IcuGraphemes.properties(cp)
                val expected =
                    if (name.startsWith("InCB_"))
                        (property.incb == name.substring(5))
                    else
                        if (name == "Control")
                            mutableListOf<String?>("CR", "LF", "Control").contains(property.gcb)
                        else
                            (property.gcb == name)
                if (actual[cp] != expected) {
                    Assertions.assertThat(actual[cp]).`as`("%s U+%04X", name, cp).isEqualTo(expected)
                }
            }
        }
        val pictographs = BooleanArray(Character.MAX_CODE_POINT + 1)
        for (range in UnicodeProperties.lookupBinaryProperty("Extended_Pictographic")) {
            Arrays.fill(pictographs, range[0], range[1] + 1, true)
        }
        for (cp in pictographs.indices) {
            val property = IcuGraphemes.properties(cp)
            if (pictographs[cp] != property.pictograph) {
                Assertions.assertThat(pictographs[cp])
                    .`as`("Extended_Pictographic U+%04X", cp)
                    .isEqualTo(property.pictograph)
            }
            // Production has dedicated scalar predicates for these two GCB values.
            Assertions.assertThat(property.gcb == "ZWJ").isEqualTo(cp == 0x200D)
            Assertions.assertThat(property.gcb == "Regional_Indicator")
                .isEqualTo(cp >= 0x1F1E6 && cp <= 0x1F1FF)
        }
    }

    @Test
    @Throws(IOException::class)
    fun icuSegmentationAgreesWithOfficialBreakTest() {
        BufferedReader(
            InputStreamReader(
                Objects.requireNonNull<InputStream?>(javaClass.getResourceAsStream("GraphemeBreakTest.txt")),
                StandardCharsets.UTF_8
            )
        ).use { reader ->
            var line: String?
            var cases = 0
            while ((reader.readLine().also { line = it }) != null) {
                if (!line!!.startsWith("÷")) {
                    continue
                }
                val expected: MutableList<String?> = ArrayList<String?>()
                val cluster = StringBuilder()
                for (token in line.split("#".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()[0].trim { it <= ' ' }.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()) {
                    if (token == "÷") {
                        if (!cluster.isEmpty()) {
                            expected.add(cluster.toString())
                            cluster.setLength(0)
                        }
                    } else if (token != "×") {
                        cluster.appendCodePoint(token.toInt(16))
                    }
                }
                val cps = String.join("", expected).codePoints().toArray()
                Assertions.assertThat<String?>(IcuGraphemes.clusters(cps)).`as`(line).isEqualTo(expected)
                cases++
            }
            Assertions.assertThat(cases).isPositive()
        }
    }

    @Test
    fun cachedBoundariesAndOpaqueRegionsAgreeWithIcu() {
        val alphabet = IcuGraphemes.representatives()
        val random = Random(954)
        for (sample in 0..299) {
            val cps = IntArray(random.nextInt(1, 40))
            for (i in cps.indices) {
                cps[i] = alphabet[random.nextInt(alphabet.size)]
            }
            val text = String(cps, 0, cps.size)
            val context = GraphemeSupport.Context.create(text, true)
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            val utf8: InputScanner = Utf8InputScanner(bytes)
            val utf8Context = GraphemeSupport.Context.create(utf8, true)
            for (startCp in cps.indices) {
                val start = text.offsetByCodePoints(0, startCp)
                val suffix = text.substring(start)
                val expected = IcuGraphemes.clusters(suffix.codePoints().toArray())
                val ends: MutableList<Int?> = ArrayList<Int?>()
                var end = start
                for (cluster in expected) {
                    end += cluster.length
                    ends.add(end)
                }
                // Query in reverse to ensure answers do not depend on a forward matcher traversal.
                for (atCp in cps.size downTo startCp + 1) {
                    val at = text.offsetByCodePoints(0, atCp)
                    val byteAt = text.substring(0, at).toByteArray(StandardCharsets.UTF_8).size
                    val byteStart = text.substring(0, start).toByteArray(StandardCharsets.UTF_8).size
                    val boundary = ends.contains(at)
                    Assertions.assertThat(GraphemeSupport.isGraphemeClusterBoundary(text, at, start, context))
                        .`as`("UTF-16 %s at %s, start %s", cps.contentToString(), at, start)
                        .isEqualTo(boundary)
                    Assertions.assertThat(
                        GraphemeSupport.isGraphemeClusterBoundary(utf8, byteAt, byteStart, utf8Context)
                    )
                        .isEqualTo(boundary)
                }
            }
        }
    }

    @Test
    fun publicRegionsFollowIcuBoundaryVisibility() {
        for (text in mutableListOf<String?>(
            "a\u0300\u0903z",
            "\r\n\u0000\u0300",
            "\u0600\u0915\u094D\u200D\u0915",
            "\u0600👩\u0300\u200D👩",
            "©\u0300\u200D©",
            "©\u200D\u200D©",
            "\u0600©\u200D©",
            "🇦🇧🇦🇧🇦",
            "\u1100\u1160\u11A8\u0300\u11A8"
        )) {
            val fullClusters = IcuGraphemes.clusters(text!!.codePoints().toArray())
            val fullBoundaries: MutableList<Int?> = boundaries(fullClusters, 0)
            val cpCount = text.codePointCount(0, text.size)
            for (from in 0..cpCount) {
                val start = text.offsetByCodePoints(0, from)
                for (to in from..cpCount) {
                    val end = text.offsetByCodePoints(0, to)
                    val local =
                        IcuGraphemes.clusters(text.substring(start, end).codePoints().toArray())
                    for (transparent in mutableListOf<Boolean?>(false, true)) {
                        val expectedBoundaries =
                            (if (transparent) fullBoundaries else boundaries(local, start))
                                .stream().filter { pos: Int? -> pos!! >= start && pos <= end }.toList()
                        var composed: MutableList<String> = ArrayList<String>()
                        if (transparent) {
                            var clusterStart = 0
                            for (cluster in fullClusters) {
                                val clusterEnd = clusterStart + cluster.length
                                if (clusterStart >= start && clusterEnd <= end) {
                                    composed.add(cluster)
                                }
                                clusterStart = clusterEnd
                            }
                        } else {
                            composed = local
                        }
                        val boundary =
                            Pattern.compile("\\b{g}")
                                .matcher(text)
                                .region(start, end)
                                .useTransparentBounds(transparent!!)
                        val actual: MutableList<Int> = ArrayList<Int>()
                        while (boundary.find()) {
                            actual.add(boundary.start())
                        }
                        Assertions.assertThat<Int?>(actual)
                            .`as`("boundary %s [%s,%s], transparent=%s", text, start, end, transparent)
                            .isEqualTo(expectedBoundaries)
                        for (regex in mutableListOf<String?>("\\X", "\\b{g}(\\X)\\b{g}")) {
                            val pattern = Pattern.compile(regex)
                            val matcher =
                                pattern.matcher(text).region(start, end).useTransparentBounds(transparent)
                            val actualClusters: MutableList<String> = ArrayList<String>()
                            while (matcher.find()) {
                                actualClusters.add(matcher.group())
                                if (matcher.groupCount() > 0) {
                                    Assertions.assertThat(matcher.group(1)).isEqualTo(matcher.group())
                                }
                            }
                            val expected: MutableList<String?> = if (regex == "\\X") local else composed
                            Assertions.assertThat<String?>(actualClusters)
                                .`as`("%s on %s [%s,%s], transparent=%s", regex, text, start, end, transparent)
                                .isEqualTo(expected)
                            // UTF-8 exposes opaque regions; it has no transparent/anchoring bounds API.
                            if (!transparent) {
                                val bytes = text.toByteArray(StandardCharsets.UTF_8)
                                val byteStart = text.substring(0, start).toByteArray(StandardCharsets.UTF_8).size
                                val byteEnd = text.substring(0, end).toByteArray(StandardCharsets.UTF_8).size
                                val utf8 =
                                    pattern.matcher(Utf8Input.validated(bytes)).region(byteStart, byteEnd)
                                actualClusters.clear()
                                while (utf8.find()) {
                                    actualClusters.add(
                                        String(bytes, utf8.start(), utf8.end() - utf8.start(), StandardCharsets.UTF_8)
                                    )
                                }
                                Assertions.assertThat<String?>(actualClusters)
                                    .`as`(
                                        "UTF-8 %s on %s [%s,%s], transparent=%s",
                                        regex, text, start, end, transparent
                                    )
                                    .isEqualTo(expected)
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private fun boundaries(clusters: MutableList<String>, start: Int): MutableList<Int?> {
            val result: MutableList<Int?> = ArrayList<Int?>()
            result.add(start)
            var end = start
            for (cluster in clusters) {
                end += cluster.length
                result.add(end)
            }
            return result
        }
    }
}
