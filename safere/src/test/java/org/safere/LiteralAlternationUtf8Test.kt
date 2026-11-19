// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import org.safere.Matcher.LiteralAlternationPreparedRunner
import org.safere.Matcher.PreparedMatchRunner
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.List
import java.util.regex.Pattern

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@DisabledForCrosscheck("compares UTF-8 diagnostics and the forced fallback path with the JDK")
internal class LiteralAlternationUtf8Test {
    private val operations: MutableList<OperationDiagnostics> = ArrayList<OperationDiagnostics>()
    private val recorder: SafeReMatchDiagnostics = object : SafeReMatchDiagnostics() {
        override fun onOperationCompleted(event: OperationDiagnostics?) {
            operations.add(event!!)
        }
    }

    @AfterEach
    fun disableDiagnostics() {
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
    }

    @Test
    fun findAgreesWithJdkStringAndFallback() {
        for (regex in PATTERNS) {
            val pattern = Pattern.compile(regex)
            val fallback = Pattern.compile(regex, 0, NO_LITERAL_FAST_PATHS)
            Assertions.assertThat<PreparedMatchRunner?>(pattern.preparedMatchRunner(false))
                .`as`(regex)
                .isInstanceOf(LiteralAlternationPreparedRunner::class.java)
            for (filler in FILLERS) {
                for (repeat in intArrayOf(0, 1, 7, 128, 1000, 3000)) {
                    for (text in List.of<String?>(
                        filler.repeat(repeat),
                        filler.repeat(repeat) + "barfoo banana gardens abc red 😀éclair apple",
                        filler.repeat(repeat) + "APPLE Banana cherrY abd",
                        "cherry" + filler.repeat(repeat) + "abc",
                        filler.repeat(repeat) + "ab" + filler.repeat(repeat) + "cherry"
                    )) {
                        val expected: MutableList<String?> = jdkFinds(regex, text)
                        Assertions.assertThat<String?>(utf8Finds(pattern, text)).`as`("/%s/ %s", regex, filler)
                            .isEqualTo(expected)
                        Assertions.assertThat<String?>(utf8Finds(fallback, text)).isEqualTo(expected)
                        Assertions.assertThat<String?>(stringFinds(pattern, text)).isEqualTo(expected)
                        // The matcher-free existence check uses the same search.
                        Assertions.assertThat(pattern.find(Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8))))
                            .isEqualTo(!expected.isEmpty())
                    }
                }
            }
        }
    }

    @Test
    fun windowBoundariesPreserveLeftmostMatchAndAlternativePriority() {
        for (regex in mutableListOf<String?>("ab|cherry|abc", "abc|cherry|ab", "abc|bcd|cde", "cherry|banana|apple")) {
            val pattern = Pattern.compile(regex)
            for (boundary in intArrayOf(1024, 3072, 4096, 5120, 7168, 15360, 31744)) {
                for (delta in -14..2) {
                    // Dense prefix of first bytes that matches none of the patterns, so the UTF-8 search
                    // runs and the first find reaches the boundary; then filler with no first bytes.
                    val prefix = "a b c ".repeat(32)
                    val text =
                        (prefix
                                + "é".repeat((boundary - prefix.length + delta) / 2)
                                + "abcde"
                                + "x".repeat(70000)
                                + "cherry")
                    Assertions.assertThat<String?>(utf8Finds(pattern, text))
                        .`as`("/%s/ boundary %s%+d", regex, boundary, delta)
                        .isEqualTo(Companion.jdkFinds(regex!!, text))
                }
            }
        }
    }

    @Test
    fun firstFindOnDenseInputReportsLiteralAlternation() {
        Pattern.setDiagnostics(recorder)
        for (regex in mutableListOf<String?>("apple|banana|cherry", "blossom|sparkling|gardens")) {
            val pattern = Pattern.compile(regex)
            val text = "a bright sunlit cobbled street ".repeat(64) + "cherry gardens"
            operations.clear()
            val utf8 = pattern.matcher(Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8)))
            Assertions.assertThat(utf8.find()).isTrue()
            val utf8Event: OperationDiagnostics = operations.last()
            operations.clear()
            Assertions.assertThat(pattern.find(Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8)))).isTrue()
            val matcherFreeEvent: OperationDiagnostics = operations.last()
            operations.clear()
            Assertions.assertThat(pattern.matcher(text).find()).isTrue()
            val stringEvent: OperationDiagnostics = operations.last()
            if (VectorScanProviders.vectorProviderAvailable()) {
                // The vector multi-literal start search is faster than one byte scan per literal.
                Assertions.assertThat<MatchStrategy?>(utf8Event.boundaryStrategy)
                    .`as`(regex)
                    .isNotEqualTo(MatchStrategy.LITERAL_ALTERNATION)
            } else {
                Assertions.assertThat<MatchStrategy?>(utf8Event.boundaryStrategy)
                    .`as`(regex)
                    .isEqualTo(MatchStrategy.LITERAL_ALTERNATION)
                    .isEqualTo(stringEvent.boundaryStrategy)
                Assertions.assertThat<MatchStrategy?>(matcherFreeEvent.boundaryStrategy)
                    .isEqualTo(MatchStrategy.LITERAL_ALTERNATION)
                Assertions.assertThat(utf8Event.forwardDfaSearchCount).isZero()
            }
        }
    }

    @Test
    fun sparseFirstBytesUseTheLiteralSearchAndManyFirstBytesKeepTheFallback() {
        Pattern.setDiagnostics(recorder)
        for (regexAndText in arrayOf<Array<String?>?>(
            arrayOf<String?>("APPLE|BANANA|CHERRY", "the quick brown fox ".repeat(64) + "CHERRY"),
            arrayOf<String?>("apple|banana|cherry", "日本語のテキスト".repeat(64) + "cherry"),
            arrayOf<String?>("red|green|blue|yellow|orange|purple", "rgbyop ".repeat(64) + "purple"),
        )) {
            val pattern = Pattern.compile(regexAndText!![0])
            operations.clear()
            Assertions.assertThat(
                pattern.matcher(Utf8Input.validated(regexAndText[1]!!.toByteArray(StandardCharsets.UTF_8))).find()
            )
                .isTrue()
            // Six first bytes are too many for a first-byte scan, so the existing DFA path runs.
            val literalSearch =
                !regexAndText[0]!!.startsWith("red") && !VectorScanProviders.vectorProviderAvailable()
            Assertions.assertThat(operations.last().boundaryStrategy == MatchStrategy.LITERAL_ALTERNATION)
                .`as`(regexAndText[0])
                .isEqualTo(literalSearch)
        }
    }

    @Test
    fun regionsUseByteBounds() {
        for (regex in mutableListOf<String?>("apple|banana|cherry", "foo|barfoo", "ab|cherry|abc", "abc|cherry|ab")) {
            val pattern = Pattern.compile(regex)
            for (padding in mutableListOf<String?>("abc ", "x", "é", "😀")) {
                val text = padding!!.repeat(300) + "barfoo banana apple abc cherry" + padding
                val bytes = text.toByteArray(StandardCharsets.UTF_8)
                val middle = padding.repeat(300).length + 3
                for (startChar in intArrayOf(0, 1, middle)) {
                    for (endChar in intArrayOf(text.length - 1, middle + 10, middle)) {
                        if (startChar > endChar || !isBoundary(text, startChar) || !isBoundary(text, endChar)) {
                            continue
                        }
                        val startByte: Int = byteOffset(text, startChar)
                        val endByte: Int = byteOffset(text, endChar)
                        val expected =
                            Pattern.compile(regex).matcher(text).region(startChar, endChar)
                        val actual =
                            pattern.matcher(Utf8Input.validated(bytes)).region(startByte, endByte)
                        while (expected.find()) {
                            Assertions.assertThat(actual.find()).`as`("/%s/ region %s", regex, startChar).isTrue()
                            Assertions.assertThat(actual.start()).isEqualTo(byteOffset(text, expected.start()))
                            Assertions.assertThat(actual.end()).isEqualTo(byteOffset(text, expected.end()))
                        }
                        Assertions.assertThat(actual.find()).isFalse()
                    }
                }
            }
        }
    }

    @Test
    fun malformedTrustedInputAgreesWithFallback() {
        val garbage = byteArrayOf(
            0x80.toByte(),
            'a'.code.toByte(),
            0xED.toByte(),
            0xA0.toByte(),
            0x80.toByte(),
            0xC3.toByte(),
            'b'.code.toByte()
        )
        for (regex in mutableListOf<String?>("apple|banana|cherry", "ab|cherry|abc", "abc|cherry|ab")) {
            val pattern = org.safere.Pattern.compile(regex)
            val fallback = org.safere.Pattern.compile(regex, 0, NO_LITERAL_FAST_PATHS)
            for (literal in mutableListOf<String?>("", "apple", "abc", "cherry")) {
                val out = ByteArrayOutputStream()
                for (i in 0..63) {
                    out.writeBytes(garbage)
                    out.writeBytes("abc ".toByteArray(StandardCharsets.UTF_8))
                }
                out.writeBytes(literal!!.toByteArray(StandardCharsets.UTF_8))
                out.writeBytes(garbage)
                val bytes = out.toByteArray()
                Assertions.assertThat<String?>(bytesFinds(pattern, bytes))
                    .`as`("/%s/ + %s", regex, literal)
                    .isEqualTo(bytesFinds(fallback, bytes))
            }
        }
    }

    @Test
    fun fullAndPrefixMatchesAgreeWithJdk() {
        for (regex in PATTERNS) {
            val pattern = org.safere.Pattern.compile(regex)
            for (text in mutableListOf<String?>("", "apple", "APPLE", "Apple", "banana", "abc", "ab", "abcé")) {
                val expected = Pattern.compile(regex).matcher(text)
                val actual = pattern.matcher(Utf8Input.validated(text!!.toByteArray(StandardCharsets.UTF_8)))
                Assertions.assertThat(actual.matches()).`as`("/%s/ %s", regex, text).isEqualTo(expected.matches())
                Assertions.assertThat(actual.lookingAt()).isEqualTo(expected.lookingAt())
            }
        }
    }

    companion object {
        private val NO_LITERAL_FAST_PATHS: EnginePathOptions =
            EnginePathOptions.builder().literalFastPaths(false).build()

        private val PATTERNS: MutableList<String> = mutableListOf<String?>(
            "apple|banana|cherry",
            "APPLE|BANANA|CHERRY",
            "blossom|sparkling|gardens",
            "foo|barfoo",
            "ab|bcd",
            "ab|cherry|abc",
            "abc|cherry|ab",
            "red|green|blue|yellow|orange|purple"
        )

        // Dense fillers switch the UTF-8 search to per-literal scans; sparse ones keep the first-byte
        // hops.
        private val FILLERS: MutableList<String> =
            mutableListOf<String?>("abc rgb Abc ", "x", "é", "日本", "😀", "the quick brown fox ")

        private fun jdkFinds(regex: String, text: String): MutableList<String?> {
            val finds: MutableList<String?> = ArrayList<String?>()
            val matcher = Pattern.compile(regex).matcher(text)
            while (matcher.find()) {
                finds.add(byteOffset(text, matcher.start()).toString() + "-" + byteOffset(text, matcher.end()))
            }
            return finds
        }

        private fun utf8Finds(pattern: org.safere.Pattern, text: String): MutableList<String?> {
            return bytesFinds(pattern, text.toByteArray(StandardCharsets.UTF_8))
        }

        private fun bytesFinds(pattern: org.safere.Pattern, bytes: ByteArray?): MutableList<String?> {
            val finds: MutableList<String?> = ArrayList<String?>()
            val matcher = pattern.matcher(Utf8Input.trusted(bytes))
            while (matcher.find()) {
                finds.add(matcher.start().toString() + "-" + matcher.end())
            }
            return finds
        }

        private fun stringFinds(pattern: org.safere.Pattern, text: String): MutableList<String?> {
            val finds: MutableList<String?> = ArrayList<String?>()
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                finds.add(byteOffset(text, matcher.start()).toString() + "-" + byteOffset(text, matcher.end()))
            }
            return finds
        }

        private fun isBoundary(text: String, index: Int): Boolean {
            return index == 0 || index == text.length || !Character.isLowSurrogate(text.get(index))
        }

        private fun byteOffset(text: String, charIndex: Int): Int {
            return text.substring(0, charIndex).toByteArray(StandardCharsets.UTF_8).size
        }
    }
}
