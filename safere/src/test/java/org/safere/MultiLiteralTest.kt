// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.safere.MultiAnchorDescriptor.StartPlan
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.math.max
import kotlin.text.split
import kotlin.text.toByteArray
import kotlin.text.toCharArray
import kotlin.text.toRegex

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class MultiLiteralTest {
    @Test
    fun patternMetadataFollowsVectorProviderAvailability() {
        val pattern = Pattern.compile("apple|banana|cherry")
        Assertions.assertThat<StartPlan?>(pattern.startPlan())
            .isInstanceOf(StartPlan.MultiLiteral::class.java)

        if (VectorScanProviders.vectorProviderAvailable()) {
            Assertions.assertThat<VectorScanProvider?>(VectorScanProviders.providerFor(ScanKind.CLASS, 64)).isNull()
            Assertions.assertThat<VectorScanProvider?>(VectorScanProviders.providerFor(ScanKind.MULTI_LITERAL, 64))
                .isNotNull()
            Assertions.assertThat<VectorScanProvider?>(VectorScanProviders.providerFor(ScanKind.CLASS, 1024))
                .isNotNull()
        }
    }

    @Test
    fun testMultiLiteralInfoCreation() {
        val lits = arrayOf<String?>("apple", "banana", "cherry")
        val info = MultiLiteralInfo.create(lits)!!
        Assertions.assertThat<MultiLiteralInfo?>(info).isNotNull()
        Assertions.assertThat<String?>(info.literals).containsExactly("apple", "banana", "cherry")
        Assertions.assertThat(info.minLength).isEqualTo(5)
        Assertions.assertThat(info.anchorChars).containsExactly('a', 'b', 'c')
        Assertions.assertThat(info.anchorOffsets).containsExactly(0, 0, 0)
        Assertions.assertThat(info.anchorRanges).containsExactly('a'.code, 'c'.code)

        val disjoint =
            MultiLiteralInfo.create(arrayOf<String?>("apple", "banana", "watermelon"))!!
        Assertions.assertThat(disjoint.anchorRanges).containsExactly('a'.code, 'b'.code, 'w'.code, 'w'.code)

        Assertions.assertThat<MultiLiteralInfo?>(MultiLiteralInfo.create(arrayOf<String?>("single"))).isNull()
        Assertions.assertThat<MultiLiteralInfo?>(MultiLiteralInfo.create(arrayOf<String?>("1", "2", "3", "4", "5")))
            .isNull()
        Assertions.assertThat<MultiLiteralInfo?>(MultiLiteralInfo.create(arrayOf<String?>("valid", "café"))).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["foo|bar", "foo|bar|baz", "cat|dog|bird|fish"])
    fun testBasicMatching(regex: String) {
        val pattern = Pattern.compile(regex)

        val keywords = regex.split("\\|".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        for (kw in keywords) {
            val haystack = "prefix " + kw + " suffix"
            val bytes = haystack.toByteArray(StandardCharsets.UTF_8)
            val m = pattern.matcher(Utf8Input.validated(bytes))
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(7)
            Assertions.assertThat(m.end()).isEqualTo(7 + kw.length)
            Assertions.assertThat(String(bytes, m.start(), m.end() - m.start(), StandardCharsets.UTF_8)).isEqualTo(kw)
        }

        val noMatch = "completely unrelated text with no hits at all"
        val mNoMatch = pattern.matcher(Utf8Input.validated(noMatch.toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(mNoMatch.find()).isFalse()
    }

    @Test
    fun testFivePlusLiteralsFallbackToCharClass() {
        val pattern = Pattern.compile("apple|banana|cherry|date|fig")
        Assertions.assertThat<StartPlan?>(pattern.startPlan())
            .isInstanceOf(StartPlan.MultiLiteral::class.java)

        val haystack = "prefix date suffix"
        val m = pattern.matcher(Utf8Input.validated(haystack.toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(7)
        Assertions.assertThat(m.end()).isEqualTo(11)
    }

    @Test
    fun testLongHaystackWithMultipleMatches() {
        val pattern = Pattern.compile("apple|banana|cherry|durian")
        val sb = StringBuilder()
        for (i in 0..199) {
            sb.append("some padding text ")
        }
        val pos1 = sb.length
        sb.append("cherry ")
        for (i in 0..299) {
            sb.append("more padding text ")
        }
        val pos2 = sb.length
        sb.append("banana ")
        for (i in 0..199) {
            sb.append("final padding text ")
        }

        val bytes = sb.toString().toByteArray(StandardCharsets.UTF_8)
        val m = pattern.matcher(Utf8Input.validated(bytes))
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(pos1)
        Assertions.assertThat(String(bytes, m.start(), m.end() - m.start(), StandardCharsets.UTF_8)).isEqualTo("cherry")

        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(pos2)
        Assertions.assertThat(String(bytes, m.start(), m.end() - m.start(), StandardCharsets.UTF_8)).isEqualTo("banana")

        Assertions.assertThat(m.find()).isFalse()
    }

    @Test
    fun testFuzzEquivalenceWithJavaRegex() {
        val keywords = arrayOf<String>("alpha", "beta", "gamma", "delta")
        val regex = java.lang.String.join("|", *keywords)
        val safere = Pattern.compile(regex)
        Assertions.assertThat<StartPlan?>(safere.startPlan()).isInstanceOf(StartPlan.MultiLiteral::class.java)
        val jre = java.util.regex.Pattern.compile(regex)

        val rnd = Random(42)
        val alphabet = "abcdefghijklmnopqrstuvwxyz ".toCharArray()

        for (trial in 0..99) {
            val len = 64 + rnd.nextInt(500)
            val chars = CharArray(len)
            for (i in 0..<len) {
                chars[i] = alphabet[rnd.nextInt(alphabet.size)]
            }
            if (rnd.nextBoolean()) {
                val kw = keywords[rnd.nextInt(keywords.size)]
                val insertPos = rnd.nextInt(max(1, len - kw.length))
                for (i in 0..<kw.length) {
                    chars[insertPos + i] = kw.get(i)
                }
            }
            val text = String(chars)
            val bytes = text.toByteArray(StandardCharsets.UTF_8)

            val sm = safere.matcher(Utf8Input.validated(bytes))
            val jm = jre.matcher(text)

            val sFound = sm.find()
            val jFound = jm.find()
            Assertions.assertThat(sFound).`as`("text: %s", text).isEqualTo(jFound)
            if (sFound) {
                Assertions.assertThat(sm.start()).isEqualTo(jm.start())
                Assertions.assertThat(sm.end()).isEqualTo(jm.end())
            }
        }
    }
}
