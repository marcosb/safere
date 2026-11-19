// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.assertj.core.api.Assertions
import java.nio.charset.StandardCharsets
import java.util.List

/** Engine equivalence over a small grammar, Unicode subjects and bounded find sequences.  */
class EngineEquivalenceFuzzer {
    @FuzzTest(maxDuration = "30s")
    fun engines(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    companion object {
        private val PATHS: MutableList<EnginePathOptions?> = List.of<EnginePathOptions?>(
            EnginePathOptions.allEnabled(),
            EnginePathOptions.builder().startAcceleration(false).build(),
            options(true, true, true),
            options(false, true, false),
            options(false, false, true),
            options(false, false, false)
        )

        /** Shared local and standalone entry point.  */
        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            // Keep surrogate AST runes distinct from supplementary characters in literal plans (#1043).
            val high = "\\x{" + Integer.toHexString(data.consumeInt(0xD800, 0xDBFF)) + "}"
            val low = "\\x{" + Integer.toHexString(data.consumeInt(0xDC00, 0xDFFF)) + "}"
            val atom =
                data.pickValue<String>(
                    List.of<String?>(
                        "a",
                        ".",
                        "[ab]",
                        "[^a]",
                        "é",
                        "😀",
                        high,
                        low,
                        high + low,
                        high + "(?:" + low + ")"
                    )
                )
            val boundaryAtom =
                data.pickValue<String?>(List.of<String?>(atom, "\\p{Cs}", "\\p{So}", "[\\uDC00-\\uDFFF]", "\\uDC4D"))
            val leadingRepeat =
                boundaryAtom + data.pickValue<String>(
                    mutableListOf<String?>(
                        "*",
                        "*?",
                        "+",
                        "+?",
                        "?",
                        "??",
                        "{0,2}",
                        "{1,2}?"
                    )
                )
            val suffix = data.pickValue<String?>(mutableListOf<String?>("[ab]", "a", "(a|b)"))
            val regex =
                data.pickValue<String?>(
                    List.of<String?>(
                        atom,
                        atom + "|",
                        "(?:" + atom + ")+",
                        "(" + atom + ")?b",
                        "^" + atom + "+$",
                        "a" + atom + "b",
                        "(?iu)k" + atom,
                        ".*" + atom + "$",
                        "(?:" + atom + "|banana)",
                        boundaryAtom + "\\b{g}",
                        "(" + boundaryAtom + ")(\\b{g})",
                        "\\b{g}(" + boundaryAtom + ")",
                        leadingRepeat + suffix + "\\b{g}",
                        leadingRepeat,
                        "(" + leadingRepeat + ")(" + suffix + ")(\\b{g})",
                        "(?:(" + boundaryAtom + ")\\b{g}|(a))"
                    )
                )
            val input = StringBuilder()
            val length = data.consumeInt(0, 32)
            for (i in 0..<length) {
                input.append(
                    data.pickValue<String?>(
                        mutableListOf<String?>(
                            "a", "b", "?", "é", "中", "😀", "👍", "\ud800", "\udc00", "\uDC4D", "\uDBFB",
                            "\uDDBD", "\n", "\u0301"
                        )
                    )
                )
            }
            check(regex, input.toString(), data.consumeInt(0, input.length))
            val start = data.consumeInt(0, input.length)
            val end = data.consumeInt(start, input.length)
            checkRegion(regex, input.toString(), start, end, data.consumeBoolean(), data.consumeBoolean())
            if (data.consumeBoolean()) {
                checkWideLeadingGraphemeExpansion(data)
            }
            if (data.consumeBoolean()) {
                checkWideLeadingExistence(data)
            }
        }

        private fun checkWideLeadingExistence(data: FuzzedDataProvider) {
            val leading =
                data.pickValue<String?>(
                    mutableListOf<String?>(
                        ".*",
                        ".*?",
                        "[^;]*",
                        "[^😀]*",
                        ".+",
                        ".{0,3}",
                        "^.*",
                        "\\b.*"
                    )
                )
            val literal = data.pickValue<String?>(mutableListOf<String?>("error", "stop", "marker"))
            val tail = data.pickValue<String?>(mutableListOf<String?>(": \\w+", ": \\d+", ": [a-z]+\\b", ": \\w+$"))
            val separator = data.pickValue<String?>(mutableListOf<String?>("\n", "\r\n", "\u0085", "\u2028", ";", "😀"))
            val padding = "abcdefghijklmnopqrstuvwxyz".repeat(data.consumeInt(0, 32))
            val rejected = literal + ": -" + separator
            val input =
                (padding
                        + rejected.repeat(data.consumeInt(0, 8))
                        + literal
                        + ": "
                        + data.pickValue<String?>(mutableListOf<String?>("disk", "42", "é", "-")))
            check(leading + literal + tail, input, 0)
        }

        private fun checkWideLeadingGraphemeExpansion(data: FuzzedDataProvider) {
            val delimiter = data.pickValue<String>(mutableListOf<String?>("😀", "🧪", "👍"))
            val repetition = data.pickValue<String?>(mutableListOf<String?>("+", "+?", "{1,3}"))
            val literal = data.pickValue<String?>(mutableListOf<String?>("error", "stop", "marker"))
            val cluster = data.pickValue<String?>(mutableListOf<String?>("!", "e\u0301", "👩‍💻"))
            val regex = "([^" + delimiter + "]" + repetition + ")" + literal + "\\X"
            val input = "p".repeat(data.consumeInt(256, 320)) + delimiter + literal + cluster
            // String grapheme programs can start at a low surrogate; UTF-8 cannot represent that start.
            val expected: MutableList<String?> = trace(Pattern.compile(regex, 0, PATHS.last()), input, 0)
            for (path in PATHS) {
                Assertions.assertThat<String?>(trace(Pattern.compile(regex, 0, path), input, 0)).isEqualTo(expected)
            }
        }

        fun check(regex: String?, input: String, start: Int) {
            val expected: MutableList<String?> = trace(Pattern.compile(regex, 0, PATHS.last()), input, start)
            val expectedUtf8: MutableList<String?>? =
                if (validScalars(input)) utf8Trace(Pattern.compile(regex, 0, PATHS.last()), input) else null
            val utf8Input =
                if (expectedUtf8 == null) null else Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))
            val expectedUtf8Find =
                utf8Input != null && Pattern.compile(regex, 0, PATHS.last()).matcher(utf8Input).find()
            for (path in PATHS) {
                val pattern = Pattern.compile(regex, 0, path)
                Assertions.assertThat<String?>(trace(pattern, input, start))
                    .`as`("%s on %s at %s via %s", regex, escaped(input), start, path)
                    .isEqualTo(expected)
                if (expectedUtf8 != null) {
                    Assertions.assertThat(pattern.find(utf8Input))
                        .`as`("UTF-8 existence /%s/ on %s via %s", regex, escaped(input), path)
                        .isEqualTo(expectedUtf8Find)
                    val utf8: MutableList<String?> = utf8Trace(pattern, input)
                    Assertions.assertThat<String?>(utf8)
                        .`as`("UTF-8 %s on %s via %s", regex, escaped(input), path)
                        .isEqualTo(expectedUtf8)
                    // Empty-match continuation advances by UTF-16 unit versus UTF-8 code point.
                    // Fresh matches/lookingAt and the first find start at equivalent positions.
                    Assertions.assertThat<String?>(utf8.subList(0, 3)).isEqualTo(trace(pattern, input, 0).subList(0, 3))
                }
            }
        }

        fun checkRegion(
            regex: String?, input: String, start: Int, end: Int, transparent: Boolean, anchoring: Boolean
        ) {
            val expected: MutableList<String?> =
                regionTrace(
                    Pattern.compile(regex, 0, PATHS.last()), input, start, end, transparent, anchoring
                )
            for (path in PATHS) {
                Assertions.assertThat<String?>(
                    regionTrace(
                        Pattern.compile(regex, 0, path), input, start, end, transparent, anchoring
                    )
                )
                    .`as`(
                        "%s on %s region [%s,%s), transparent=%s, anchoring=%s via %s",
                        regex, escaped(input), start, end, transparent, anchoring, path
                    )
                    .isEqualTo(expected)
            }
        }

        private fun options(onePass: Boolean, dfa: Boolean, bitState: Boolean): EnginePathOptions {
            return EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .keywordAlternationFastPath(false)
                .startAcceleration(false)
                .shiftDfa(false)
                .onePass(onePass)
                .dfa(dfa)
                .bitState(bitState)
                .build()
        }

        private fun trace(pattern: Pattern, input: String, start: Int): MutableList<String?> {
            val matcher = pattern.matcher(input)
            val result: MutableList<String?> = ArrayList<String?>()
            result.add(snapshot(matcher.matches(), matcher))
            matcher.reset()
            result.add(snapshot(matcher.lookingAt(), matcher))
            matcher.reset()
            var found = matcher.find(start)
            for (count in 0..input.length + 1) {
                result.add(snapshot(found, matcher))
                if (!found) {
                    return result
                }
                found = matcher.find()
            }
            throw AssertionError("unbounded find sequence")
        }

        private fun snapshot(found: Boolean, matcher: Matcher): String {
            val value = StringBuilder(found.toString())
            if (found) for (g in 0..matcher.groupCount()) {
                value
                    .append('|')
                    .append(matcher.start(g))
                    .append(':')
                    .append(matcher.end(g))
                    .append(':')
                    .append(escaped(matcher.group(g)))
            }
            return value.toString()
        }

        private fun regionTrace(
            pattern: Pattern, input: String, start: Int, end: Int, transparent: Boolean, anchoring: Boolean
        ): MutableList<String?> {
            val matcher =
                pattern.matcher(input).useTransparentBounds(transparent).useAnchoringBounds(anchoring)
            val result: MutableList<String?> = ArrayList<String?>()
            matcher.region(start, end)
            result.add(snapshot(matcher.matches(), matcher))
            matcher.region(start, end)
            result.add(snapshot(matcher.lookingAt(), matcher))
            matcher.region(start, end)
            for (count in 0..input.length + 1) {
                val found = matcher.find()
                result.add(snapshot(found, matcher))
                if (!found) {
                    return result
                }
            }
            throw AssertionError("unbounded region find sequence")
        }

        private fun utf8Trace(pattern: Pattern, input: String): MutableList<String?> {
            val bytes = input.toByteArray(StandardCharsets.UTF_8)
            val matcher = pattern.matcher(Utf8Input.validated(bytes))
            val result: MutableList<String?> = ArrayList<String?>()
            result.add(utf8Snapshot(matcher.matches(), matcher, bytes))
            matcher.reset()
            result.add(utf8Snapshot(matcher.lookingAt(), matcher, bytes))
            matcher.reset()
            for (count in 0..bytes.size + 1) {
                val found = matcher.find()
                result.add(utf8Snapshot(found, matcher, bytes))
                if (!found) return result
            }
            throw AssertionError("unbounded UTF-8 find sequence")
        }

        private fun utf8Snapshot(found: Boolean, matcher: Utf8Matcher, bytes: ByteArray): String {
            val value = StringBuilder(found.toString())
            if (found) for (g in 0..matcher.groupCount()) {
                val start = matcher.start(g)
                val end = matcher.end(g)
                value
                    .append('|')
                    .append(utf16Offset(bytes, start))
                    .append(':')
                    .append(utf16Offset(bytes, end))
                    .append(':')
                    .append(
                        if (start < 0)
                            "null"
                        else
                            escaped(String(bytes, start, end - start, StandardCharsets.UTF_8))
                    )
            }
            return value.toString()
        }

        private fun utf16Offset(bytes: ByteArray, offset: Int): Int {
            return if (offset < 0) -1 else String(bytes, 0, offset, StandardCharsets.UTF_8).length
        }

        private fun validScalars(text: String): Boolean {
            var i = 0
            while (i < text.length) {
                val c = text.get(i)
                if (Character.isHighSurrogate(c)) {
                    if (++i == text.length || !Character.isLowSurrogate(text.get(i))) return false
                } else if (Character.isLowSurrogate(c)) return false
                i++
            }
            return true
        }

        private fun escaped(text: String?): String {
            if (text == null) return "null"
            val result = StringBuilder()
            for (i in 0..<text.length) result.append(String.format("\\u%04x", text.get(i).code))
            return result.toString()
        }
    }
}
