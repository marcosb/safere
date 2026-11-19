// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.safere.GraphemeBreakConformanceTest.Companion.collectEnds
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.function.IntFunction
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Conformance tests for Unicode Extended Grapheme Cluster segmentation (`\X` and `\b{g}`) against UAX #29's `GraphemeBreakTest.txt`, plus an exhaustive per-code-point
 * comparison with `java.util.regex` when the runtime JDK's Unicode data matches the
 * checked-in tables.
 */
@DisabledForCrosscheck(
    ("SafeRE pins Unicode 17 grapheme tables across runtime JDKs and treats unassigned Other code"
            + " points as bases; see INTENTIONAL_DIVERGENCES.md")
)
internal class GraphemeBreakConformanceTest {
    @Test
    @Throws(IOException::class)
    fun unicode17GraphemeBreakTestTable() {
        var count = 0
        Objects.requireNonNull<InputStream>(
            GraphemeBreakConformanceTest::class.java.getResourceAsStream("GraphemeBreakTest.txt")
        ).use { `in` ->
            BufferedReader(
                InputStreamReader(`in`, StandardCharsets.UTF_8)
            ).use { reader ->
                var line: String?
                while ((reader.readLine().also { line = it }) != null) {
                    if (!line!!.startsWith("÷")) {
                        continue
                    }
                    count++
                    val commentStart = line.indexOf('#')
                    val data = (if (commentStart >= 0) line.substring(0, commentStart) else line).trim { it <= ' ' }
                    val stringInput = StringBuilder()
                    val expectedClusters: MutableList<String?> = ArrayList<String?>()
                    val currentCluster = StringBuilder()
                    for (token in data.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
                        if (token == "÷") {
                            if (!currentCluster.isEmpty()) {
                                expectedClusters.add(currentCluster.toString())
                                currentCluster.setLength(0)
                            }
                        } else if (token != "×") {
                            val cp = token.toInt(16)
                            stringInput.appendCodePoint(cp)
                            currentCluster.appendCodePoint(cp)
                        }
                    }
                    val text = stringInput.toString()

                    val stringClusters: MutableList<String?> = ArrayList<String?>()
                    val matcher: Matcher = SAFERE_GRAPHEME.matcher(text)
                    while (matcher.find()) {
                        stringClusters.add(matcher.group())
                    }
                    Assertions.assertThat<String?>(stringClusters).`as`(line).isEqualTo(expectedClusters)

                    Assertions.assertThat<String?>(SAFERE_BOUNDARY.split(text))
                        .`as`(line)
                        .containsExactlyElementsOf(expectedClusters)

                    val utf8Bytes = text.toByteArray(StandardCharsets.UTF_8)
                    val utf8Clusters: MutableList<String?> = ArrayList<String?>()
                    val utf8Matcher: Utf8Matcher = SAFERE_GRAPHEME.matcher(Utf8Input.validated(utf8Bytes))
                    while (utf8Matcher.find()) {
                        utf8Clusters.add(
                            String(
                                utf8Bytes,
                                utf8Matcher.start(),
                                utf8Matcher.end() - utf8Matcher.start(),
                                StandardCharsets.UTF_8
                            )
                        )
                    }
                    Assertions.assertThat<String?>(utf8Clusters).`as`(line).isEqualTo(expectedClusters)
                }
            }
        }
        Assertions.assertThat(count).isEqualTo(766)
    }

    @Test
    fun assignedCodePointsMatchJdkAcrossGraphemeContexts() {
        Assumptions.assumeTrue(
            runtimeJdkUnicodeMatchesGeneratedTables(),
            "Runtime JDK Unicode repertoire differs from the generator JDK"
        )

        val contexts = arrayOf<Array<String?>?>(
            arrayOf<String?>("a", ""),
            arrayOf<String?>("", "a"),
            arrayOf<String?>("", "\u0301"),
            arrayOf<String?>("\u0915\u094D", ""),
            arrayOf<String?>("\u0915", "\u0915"),
            arrayOf<String?>("\u0915\u094D", "\u0915"),
            arrayOf<String?>("\uD83D\uDC4D", ""),
            arrayOf<String?>("\uD83D\uDC4D\u200D", ""),
            arrayOf<String?>("\u1100", ""),
            arrayOf<String?>("", "\u1161"),
            arrayOf<String?>("\n", ""),
            arrayOf<String?>("", "\n"),
        )

        // Each input has at most four code points, so at most four clusters.
        val safereEnds = IntArray(8)
        val jdkEnds = IntArray(8)
        for (cp in 0..Character.MAX_CODE_POINT) {
            val type = Character.getType(cp)
            // Unicode 14 removed GCB=SpacingMark for these Ahom signs. The JDK still uses it.
            // See INTENTIONAL_DIVERGENCES.md and the focused test below.
            if (type == Character.UNASSIGNED.toInt() || type == Character.SURROGATE.toInt() || cp == 0x11720 || cp == 0x11721) {
                continue
            }
            val mid = Character.toString(cp)
            for (ctx in contexts) {
                val text = ctx!![0] + mid + ctx[1]
                val safereCount: Int = collectEnds(SAFERE_GRAPHEME.matcher(text), safereEnds)
                val jdkCount: Int = collectEnds(JDK_GRAPHEME.matcher(text), jdkEnds)
                if (safereCount != jdkCount || !prefixEquals(safereEnds, jdkEnds, safereCount)) {
                    Assertions.assertThat<Int?>(slice(safereEnds, safereCount))
                        .`as`("U+%04X in [%s _ %s]", cp, Companion.hex(ctx[0]!!), Companion.hex(ctx[1]!!))
                        .isEqualTo(slice(jdkEnds, jdkCount))
                }
            }
        }
    }

    @Test
    fun ahomVowelSignsUseUnicode17GraphemeBoundaries() {
        for (cp in intArrayOf(0x11720, 0x11721)) {
            val sign = Character.toString(cp)
            val text = "a" + sign
            val matcher: Matcher = SAFERE_GRAPHEME.matcher(text)
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.group()).isEqualTo("a")
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.group()).isEqualTo(sign)
            Assertions.assertThat(matcher.find()).isFalse()
            Assertions.assertThat<String?>(SAFERE_BOUNDARY.split(text)).containsExactly("a", sign)
        }
    }

    companion object {
        private val SAFERE_GRAPHEME: Pattern = Pattern.compile("\\X")
        private val SAFERE_BOUNDARY: Pattern = Pattern.compile("\\b{g}")
        private val JDK_GRAPHEME: Pattern = Pattern.compile("\\X")

        private fun runtimeJdkUnicodeMatchesGeneratedTables(): Boolean {
            val assigned =
                Objects.requireNonNull<Array<IntArray?>>(UnicodeGeneratedTables.BINARY_PROPERTIES.get("Assigned"))
            var rangeIndex = 0
            for (cp in 0..Character.MAX_CODE_POINT) {
                while (rangeIndex < assigned.size && assigned[rangeIndex]!![1] < cp) {
                    rangeIndex++
                }
                val inTable = rangeIndex < assigned.size && assigned[rangeIndex]!![0] <= cp
                if (Character.isDefined(cp) != inTable) {
                    return false
                }
            }
            return true
        }

        private fun collectEnds(matcher: Matcher, out: IntArray): Int {
            var n = 0
            while (matcher.find()) {
                out[n++] = matcher.end()
            }
            return n
        }

        private fun collectEnds(matcher: Matcher, out: IntArray): Int {
            var n = 0
            while (matcher.find()) {
                out[n++] = matcher.end()
            }
            return n
        }

        private fun prefixEquals(a: IntArray, b: IntArray, len: Int): Boolean {
            for (i in 0..<len) {
                if (a[i] != b[i]) {
                    return false
                }
            }
            return true
        }

        private fun slice(a: IntArray, len: Int): MutableList<Int?> {
            val list: MutableList<Int?> = ArrayList<Int?>(len)
            for (i in 0..<len) {
                list.add(a[i])
            }
            return list
        }

        private fun hex(s: String): String? {
            return s.codePoints().mapToObj<String?>(IntFunction { cp: Int -> String.format("%04X", cp) }).toList()
                .toString()
        }
    }
}
