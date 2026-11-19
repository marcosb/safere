// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.assertj.core.api.Assertions
import org.safere.Pattern
import org.safere.Utf8Input
import org.safere.fuzz.FuzzSupport.CompiledPattern
import org.safere.fuzz.FuzzSupport.MatcherPair
import java.nio.charset.StandardCharsets
import java.util.function.Function
import java.util.regex.MatchResult
import kotlin.math.max

class CaptureSemanticsFuzzer {
    @FuzzTest(maxDuration = "30s")
    fun quantifiedCaptures(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    @FuzzTest(maxDuration = "30s")
    fun acceptedPaths(data: FuzzedDataProvider) {
        val atom: String = pick(data, "a", "ab", "é", "😀")
        val count = data.consumeInt(3, 64)
        val maximum = data.consumeInt(2, 32)
        val minimum = if (data.consumeBoolean()) 0 else 1
        val captured = if (data.consumeBoolean()) "(?<last>" + atom + ")" else "(?:(?<last>" + atom + "))"
        val outer = if (data.consumeBoolean()) "*" else "+"
        val reluctant = if (data.consumeBoolean()) "?" else ""
        val run = atom.repeat(count)

        // These oracles describe the accepted path directly. JDK nested-repeat captures can retain
        // an earlier iteration, so a differential oracle cannot establish this invariant (#1079).
        assertAcceptedPath(
            "(?:" + captured + "{" + minimum + "," + maximum + "})" + outer + reluctant + "!",
            run + "!",
            run.length - atom.length,
            run.length
        )
        assertAcceptedPath(
            "(?:(?:" + atom + "(?<last>b)?){1," + maximum + "})+",
            run + "b",
            run.length,
            run.length + 1
        )
        assertAcceptedPath(
            "(?:(?<last>" + atom + "b?)+(" + atom + "){0," + maximum + "})+",
            run + "b",
            run.length - atom.length,
            run.length + 1
        )
    }

    @FuzzTest(maxDuration = "30s")
    fun minimumRespectingEmptyStop(data: FuzzedDataProvider) {
        val atom: String = pick(data, "a", "ab", "é", "😀")
        val prefix: String = pick(data, "", "x", "😀")
        val count = data.consumeInt(0, 8)
        val minimum = data.consumeInt(0, 4)
        val maximum = if (data.consumeBoolean()) -1 else data.consumeInt(minimum, 6)
        val greedy = data.consumeBoolean()
        val quantifier = "{" + minimum + "," + (if (maximum < 0) "" else maximum) + "}"
        var operand = "(?:(?<empty>)|" + Pattern.quote(atom) + ")"
        val wrappers = data.consumeInt(0, 3)
        operand = "(?:".repeat(wrappers) + operand + ")".repeat(wrappers)
        val pattern =
            Pattern.compile(
                "\\A" + Pattern.quote(prefix) + operand + quantifier + (if (greedy) "" else "?") + "!"
            )
        val input = prefix + atom.repeat(count) + "!"
        // A bounded independent execution tree, never a production result-repair strategy. Every
        // consuming iteration advances one atom; at most minimum + count + 1 iterations are needed.
        val expected: Int? =
            emptyStopCapture(
                minimum, if (maximum < 0) minimum + count + 1 else maximum, greedy, count, 0, 0, -1
            )
        for (operation in 0..2) {
            val matcher = pattern.matcher(input)
            val matched =
                when (operation) {
                    0 -> matcher.matches()
                    1 -> matcher.find()
                    else -> matcher.lookingAt()
                }
            Assertions.assertThat(matched).`as`("/%s/ on %s", pattern, input).isEqualTo(expected != null)
            if (matched) {
                val position = if (expected!! < 0) -1 else prefix.length + expected * atom.length
                Assertions.assertThat(matcher.start()).isZero()
                Assertions.assertThat(matcher.end()).isEqualTo(input.length)
                Assertions.assertThat(matcher.start(1)).isEqualTo(position)
                Assertions.assertThat(matcher.end(1)).isEqualTo(position)
                Assertions.assertThat(matcher.group(1)).isEqualTo(if (expected < 0) null else "")
                val snapshot = matcher.toMatchResult()
                matcher.reset()
                Assertions.assertThat(snapshot.start(1)).isEqualTo(position)
            }
        }
        // A fresh pattern has no capture-demand history, so matches() exercises DFA recognition
        // before deferred capture extraction. Search still exercises the guarded selection rule.
        val bytePattern = Pattern.compile(pattern.pattern(), pattern.flags())
        val bytes = bytePattern.matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(bytes.matches()).isEqualTo(expected != null)
        if (expected != null) {
            val position =
                if (expected < 0)
                    -1
                else
                    prefix.toByteArray(StandardCharsets.UTF_8).size + expected * atom.toByteArray(StandardCharsets.UTF_8).size
            Assertions.assertThat(bytes.start(1)).isEqualTo(position)
            Assertions.assertThat(bytes.end(1)).isEqualTo(position)
        }
        bytes.reset()
        Assertions.assertThat(bytes.find()).isEqualTo(expected != null)
        if (expected != null) {
            val position =
                if (expected < 0)
                    -1
                else
                    prefix.toByteArray(StandardCharsets.UTF_8).size + expected * atom.toByteArray(StandardCharsets.UTF_8).size
            Assertions.assertThat(bytes.start(1)).isEqualTo(position)
            Assertions.assertThat(bytes.end(1)).isEqualTo(position)
        }
        Assertions.assertThat(
            pattern
                .matcher(input)
                .replaceAll(Function { result: MatchResult? -> if (result!!.group(1) == null) "omitted" else "empty" })
        )
            .isEqualTo(if (expected == null) input else if (expected < 0) "omitted" else "empty")
        fullMatchTerminatorCaptures(data)
    }

    @FuzzTest(maxDuration = "30s")
    fun emptyCaptures(data: FuzzedDataProvider) {
        val minimum = data.consumeInt(0, 4)
        var maximum = if (data.consumeBoolean()) -1 else data.consumeInt(max(2, minimum), 8)
        if (minimum <= 1 && data.consumeBoolean()) {
            maximum = data.consumeInt(minimum, 1)
        }
        if (maximum > 1 && data.consumeBoolean()) {
            maximum = intArrayOf(16, 256, 1000)[data.consumeInt(0, 2)]
        }
        var quantifier = "{" + minimum + "," + (if (maximum < 0) "" else maximum) + "}"
        if (data.consumeBoolean() && maximum < 0 && minimum <= 1) {
            quantifier = if (minimum == 0) "*" else "+"
        }
        val mode: String = pick(data, "", "?", "+")
        var operand: String = pick(data, "(?<empty>)", "(?<empty>(?:))", "((?<empty>))")
        val wrappers = data.consumeInt(0, 3)
        operand = "(?:".repeat(wrappers) + operand + ")".repeat(wrappers)
        val prefix: String = pick(data, "", "a", "é", "😀")
        val suffix: String = pick(data, "", "b", "é", "😀")
        val pattern =
            Pattern.compile(
                Pattern.quote(prefix) + operand + quantifier + mode + Pattern.quote(suffix)
            )
        val input = prefix + suffix
        val participates = maximum != 0 && (minimum > 0 || mode != "?")
        val position = if (participates) prefix.length else -1

        // Empty iterations count and publish captures; redundant wrappers do not select another
        // participation policy. JDK's direct-empty suppression is intentionally not the oracle.
        for (operation in 0..2) {
            val matcher = pattern.matcher(input)
            val matched =
                when (operation) {
                    0 -> matcher.find()
                    1 -> matcher.matches()
                    else -> matcher.lookingAt()
                }
            Assertions.assertThat(matched).`as`("/%s/ on %s", pattern, input).isTrue()
            Assertions.assertThat(matcher.start()).isZero()
            Assertions.assertThat(matcher.end()).isEqualTo(input.length)
            for (group in 1..matcher.groupCount()) {
                Assertions.assertThat(matcher.start(group)).isEqualTo(position)
                Assertions.assertThat(matcher.end(group)).isEqualTo(position)
                Assertions.assertThat(matcher.group(group)).isEqualTo(if (participates) "" else null)
            }
            Assertions.assertThat(matcher.start("empty")).isEqualTo(position)
            val snapshot = matcher.toMatchResult()
            matcher.reset()
            Assertions.assertThat(snapshot.start(1)).isEqualTo(position)
            Assertions.assertThat(snapshot.end(1)).isEqualTo(position)
        }
        val utf8 = pattern.matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(utf8.find()).isTrue()
        Assertions.assertThat(utf8.start()).isZero()
        Assertions.assertThat(utf8.end()).isEqualTo(input.toByteArray(StandardCharsets.UTF_8).size)
        val bytePosition = if (participates) prefix.toByteArray(StandardCharsets.UTF_8).size else -1
        for (group in 1..utf8.groupCount()) {
            Assertions.assertThat(utf8.start(group)).isEqualTo(bytePosition)
            Assertions.assertThat(utf8.end(group)).isEqualTo(bytePosition)
        }
        Assertions.assertThat(
            pattern
                .matcher(input)
                .replaceAll(Function { result: MatchResult? -> if (result!!.group(1) == null) "omitted" else "empty" })
        )
            .isEqualTo(if (participates) "empty" else "omitted")
    }

    companion object {
        private val ATOMS = arrayOf<String>(
            "()",
            "(a)",
            "(a?)",
            "(a)?",
            "(\\b)",
            "(a|aa)",
            "()|\\W+",
            "(a)|b",
            "b|(a)",
            "a|(b)|()",
            "a|(){2}",
            "a|(?:()){2}",
            ".|(){2}",
            "((a))",
            "(?:|(a))",
            "(?:a(b)?){1,2}",
            "(ab?)+(a){0,2}",
            "(?<word>a)",
            "(?<word>a|aa)"
        )
        private val QUANTIFIERS = arrayOf<String>(
            "*", "+", "?", "{0,2}", "{1,2}", "*?", "+?", "??", "{0,2}?", "{1,2}?", "*+", "?+", "{0,2}+"
        )
        private val CHAINED_QUANTIFIERS = arrayOf<String>(
            "{0}", "{1}", "{0,2}", "{1}?", "{0}?", "{0}+", "{1}+", "{0,2}+"
        )
        private val PREFIXES = arrayOf<String>("", "x", "(?:x)?")
        private val SUFFIXES = arrayOf<String>("", "y", "c?")

        private fun fullMatchTerminatorCaptures(data: FuzzedDataProvider) {
            val atom: String = pick(data, "a", "ab", "é", "😀")
            val prefix: String = pick(data, "", "x", "😀")
            val terminator: String = pick(data, "\n", "\r\n", "\r", "\u0085", "\u2028", "\u2029")
            val minimum = data.consumeInt(0, 4)
            val maximum = if (data.consumeBoolean()) -1 else data.consumeInt(minimum, 8)
            val count = data.consumeInt(0, 10)
            val greedy = data.consumeBoolean()
            val flags = if (data.consumeBoolean()) Pattern.UNIX_LINES else 0
            val regex =
                ("^"
                        + Pattern.quote(prefix)
                        + "(?<last>(?:"
                        + Pattern.quote(atom)
                        + ")?){"
                        + minimum
                        + ","
                        + (if (maximum < 0) "" else maximum)
                        + "}"
                        + (if (greedy) "" else "?")
                        + "((?:"
                        + Pattern.quote(terminator)
                        + ")??)$")
            val input = prefix + atom.repeat(count) + terminator
            val expected = maximum < 0 || count <= maximum
            val participates = minimum > 0 || count > 0 || (greedy && maximum != 0)
            val empty = minimum > count || (greedy && (maximum < 0 || maximum > count))
            val pattern = Pattern.compile(regex, flags)
            val matcher = pattern.matcher(input)
            Assertions.assertThat(matcher.matches()).`as`("/%s/ on %s", regex, input).isEqualTo(expected)
            if (expected) {
                val start =
                    if (!participates) -1 else prefix.length + (if (empty) count else count - 1) * atom.length
                val end = if (!participates) -1 else prefix.length + count * atom.length
                Assertions.assertThat(matcher.start(1)).isEqualTo(start)
                Assertions.assertThat(matcher.end(1)).isEqualTo(end)
                Assertions.assertThat(matcher.group(2)).isEqualTo(terminator)
                Assertions.assertThat(matcher.end()).isEqualTo(input.length)
                val snapshot = matcher.toMatchResult()
                Assertions.assertThat(snapshot.group()).isEqualTo(input)
                Assertions.assertThat(snapshot.group(2)).isEqualTo(terminator)
            }
            // Keep the UTF-8 pattern's capture-demand history independent of String group access.
            val bytes =
                Pattern.compile(regex, flags).matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))
            Assertions.assertThat(bytes.matches()).isEqualTo(expected)
            if (expected) {
                val start =
                    if (!participates)
                        -1
                    else
                        (prefix.toByteArray(StandardCharsets.UTF_8).size
                                + (if (empty) count else count - 1) * atom.toByteArray(StandardCharsets.UTF_8).size)
                val end =
                    if (!participates) -1 else prefix.toByteArray(StandardCharsets.UTF_8).size + count * atom.toByteArray(
                        StandardCharsets.UTF_8
                    ).size
                Assertions.assertThat(bytes.start(1)).isEqualTo(start)
                Assertions.assertThat(bytes.end(1)).isEqualTo(end)
                Assertions.assertThat(bytes.start(2))
                    .isEqualTo(input.toByteArray(StandardCharsets.UTF_8).size - terminator.toByteArray(StandardCharsets.UTF_8).size)
                Assertions.assertThat(bytes.end(2)).isEqualTo(input.toByteArray(StandardCharsets.UTF_8).size)
                Assertions.assertThat(bytes.end()).isEqualTo(input.toByteArray(StandardCharsets.UTF_8).size)
            }
        }

        private fun emptyStopCapture(
            minimum: Int,
            maximum: Int,
            greedy: Boolean,
            length: Int,
            iterations: Int,
            position: Int,
            capture: Int
        ): Int? {
            if (iterations == maximum) {
                return if (position == length) capture else null
            }
            if (!greedy && iterations >= minimum && position == length) {
                return capture
            }
            val empty =
                if (iterations + 1 >= minimum)
                    (if (position == length) position else null)
                else
                    emptyStopCapture(
                        minimum, maximum, greedy, length, iterations + 1, position, position
                    )
            if (empty != null) {
                return empty
            }
            if (position < length) {
                val consuming: Int? =
                    emptyStopCapture(minimum, maximum, greedy, length, iterations + 1, position + 1, capture)
                if (consuming != null) {
                    return consuming
                }
            }
            return if (greedy && iterations >= minimum && position == length) capture else null
        }

        private fun assertAcceptedPath(regex: String?, input: String, start: Int, end: Int) {
            val pattern = Pattern.compile(regex)
            for (operation in 0..2) {
                val matcher = pattern.matcher(input)
                val matched =
                    when (operation) {
                        0 -> matcher.find()
                        1 -> matcher.matches()
                        else -> matcher.lookingAt()
                    }
                Assertions.assertThat(matched).`as`("/%s/ on %s", regex, input).isTrue()
                Assertions.assertThat(matcher.start()).isZero()
                Assertions.assertThat(matcher.end()).isEqualTo(input.length)
                Assertions.assertThat(matcher.start("last")).isEqualTo(start)
                Assertions.assertThat(matcher.end("last")).isEqualTo(end)
                if (matcher.groupCount() > 1) {
                    Assertions.assertThat(matcher.start(2)).isEqualTo(-1)
                    Assertions.assertThat(matcher.end(2)).isEqualTo(-1)
                }
                val snapshot = matcher.toMatchResult()
                matcher.reset()
                Assertions.assertThat(snapshot.start(1)).isEqualTo(start)
                Assertions.assertThat(snapshot.end(1)).isEqualTo(end)
            }
            val utf8 = pattern.matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))
            Assertions.assertThat(utf8.find()).isTrue()
            Assertions.assertThat(utf8.end()).isEqualTo(input.toByteArray(StandardCharsets.UTF_8).size)
            Assertions.assertThat(utf8.start(1))
                .isEqualTo(input.substring(0, start).toByteArray(StandardCharsets.UTF_8).size)
            Assertions.assertThat(utf8.end(1))
                .isEqualTo(input.substring(0, end).toByteArray(StandardCharsets.UTF_8).size)
            Assertions.assertThat(pattern.matcher(input).replaceAll("[\${last}]"))
                .isEqualTo("[" + input.substring(start, end) + "]")
        }

        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            val named = data.consumeBoolean()
            val regex: String = consumeRegex(data, named)
            val input: String = consumeCaptureInput(data)
            val pattern = FuzzSupport.compileOrSkip(regex, 0)
            if (pattern == null) {
                return
            }

            assertMatchOperations(pattern, input, named)
            assertReplacementOperations(pattern, input, named)
            assertZeroCountPossessive(data)
        }

        private fun assertZeroCountPossessive(data: FuzzedDataProvider) {
            // Keep zero-count operands as a separate axis so existing corpus inputs retain their decoding.
            // Refs #1130: descendants below {0} cannot consume, even if they contain consuming branches.
            val named = data.consumeBoolean()
            val atom: String = if (named) pick(data, "(?<word>a)", "(?<word>a|aa)") else pick(data, *ATOMS)
            val zero = "(?:" + atom + ")" + pick(data, "{0}", "{0,0}", "{0}?", "{0,0}?")
            val operand =
                when (data.consumeInt(0, 5)) {
                    0 -> zero
                    1 -> "(" + zero + ")"
                    2 -> zero + "(b){0}"
                    3 -> zero + "|(b){0}"
                    4 -> "(?:" + zero + "){2}"
                    5 -> "(?:" + zero + ")*"
                    else -> throw AssertionError()
                }
            val regex: String =
                (pick(data, *PREFIXES)
                        + "(?:"
                        + operand
                        + ")"
                        + pick(data, "*+", "?+", "++", "{0}+", "{1}+", "{2}+", "{0,2}+", "{1,}+")
                        + pick(data, *SUFFIXES))
            val input: String = consumeCaptureInput(data)
            val pattern = FuzzSupport.compileOrSkip(regex, 0)
            if (pattern != null) {
                assertMatchOperations(pattern, input, named)
                assertReplacementOperations(pattern, input, named)
            }
        }

        private fun consumeRegex(data: FuzzedDataProvider, named: Boolean): String {
            val atom: String = if (named) pick(data, "(?<word>a)", "(?<word>a|aa)") else pick(data, *ATOMS)
            var regex = "(?:" + atom + ")"
            if (!data.consumeBoolean()) {
                regex += pick(data, *QUANTIFIERS)
            }
            if (data.consumeBoolean()) {
                regex += pick(data, *CHAINED_QUANTIFIERS)
            }
            if (data.consumeBoolean()) {
                regex = "(?:" + regex + ")" + pick(data, *QUANTIFIERS)
            }
            return pick(data, *PREFIXES) + regex + pick(data, *SUFFIXES)
        }

        private fun consumeCaptureInput(data: FuzzedDataProvider): String {
            val length = data.consumeInt(0, 64)
            val input = StringBuilder(length)
            for (i in 0..<length) {
                input.append(
                    when (data.consumeInt(0, 5)) {
                        0 -> 'a'
                        1 -> 'b'
                        2 -> 'c'
                        3 -> 'x'
                        4 -> 'y'
                        5 -> ' '
                        else -> throw AssertionError()
                    }
                )
            }
            return input.toString()
        }

        private fun assertMatchOperations(
            pattern: CompiledPattern, input: String, named: Boolean
        ) {
            var matcher = pattern.matcher(input)
            observeGroups(matcher, matcher.matches(), named)

            matcher = pattern.matcher(input)
            observeGroups(matcher, matcher.lookingAt(), named)

            matcher = pattern.matcher(input)
            var matches = 0
            while (matcher.find() && matches < 128) {
                observeGroups(matcher, true, named)
                matches++
            }
        }

        private fun observeGroups(
            matcher: MatcherPair, matched: Boolean, named: Boolean
        ) {
            matcher.groupCount()
            if (!matched) {
                return
            }
            for (group in 0..matcher.groupCount()) {
                matcher.group(group)
                matcher.start(group)
                matcher.end(group)
            }
            matcher.toMatchResult()
            if (named) {
                matcher.group("word")
                matcher.start("word")
                matcher.end("word")
            }
        }

        private fun assertReplacementOperations(
            pattern: CompiledPattern, input: String, named: Boolean
        ) {
            pattern.matcher(input).replaceAll("[$1]")
            pattern.matcher(input).replaceFirst("[$1]")
            pattern.matcher(input).replaceAll(Function { match: MatchResult? -> "[" + match!!.group(1) + "]" })

            val matcher = pattern.matcher(input)
            val builder = StringBuilder()
            while (matcher.find()) {
                matcher.appendReplacement(builder, "[$1]")
            }
            matcher.appendTail(builder)

            if (named) {
                pattern.matcher(input).replaceAll("[\${word}]")
            }
        }

        private fun pick(data: FuzzedDataProvider, vararg values: String): String {
            return values[data.consumeInt(0, values.size - 1)]
        }
    }
}
