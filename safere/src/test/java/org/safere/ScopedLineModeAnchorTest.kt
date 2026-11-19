// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.charset.StandardCharsets
import java.util.regex.MatchResult
import java.util.stream.Stream

/** Scoped line modes belong to each anchor, including when both modes occur in one program.  */
@DisabledForCrosscheck("differential matrix includes UTF-8 and internal engine coverage")
internal class ScopedLineModeAnchorTest {
    @ParameterizedTest(name = "[{index}] /{0}/ flags={1} terminator={3} padding={4}")
    @MethodSource("anchorCases")
    fun scopedAnchorsMatchJdk(
        regex: String, flags: Int, terminator: String?, description: String?, padding: Int
    ) {
        val input = "x".repeat(padding) + "foo" + terminator
        val jdk = java.util.regex.Pattern.compile(regex, flags)
        val safe = org.safere.Pattern.compile(regex, flags)
        val expected: MutableList<String> = ArrayList<String>()
        val reference = jdk.matcher(input)
        while (reference.find()) {
            expected.add(captureTrace(reference)!!)
        }
        val actual: MutableList<String> = ArrayList<String>()
        val matcher = safe.matcher(input)
        while (matcher.find()) {
            actual.add(captureTrace(matcher)!!)
        }
        Assertions.assertThat<String?>(actual).`as`("String find sequence").isEqualTo(expected)
        actual.clear()
        val utf8 = safe.matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
        while (utf8.find()) {
            val captures: MutableList<String> = ArrayList<String>()
            for (group in 0..utf8.groupCount()) {
                val start = utf8.start(group)
                val end = utf8.end(group)
                val value =
                    if (start < 0) null else String(
                        input.toByteArray(StandardCharsets.UTF_8),
                        start,
                        end - start,
                        StandardCharsets.UTF_8
                    )
                captures.add(start.toString() + ":" + end + ":" + value)
            }
            actual.add(captures.toString())
        }
        Assertions.assertThat<String?>(actual).`as`("UTF-8 find sequence").isEqualTo(expected)
        val prog = safe.prog()
        Assertions.assertThat(
            Nfa.search(
                prog,
                input,
                Nfa.Anchor.UNANCHORED,
                Nfa.MatchKind.FIRST_MATCH,
                prog.numCaptures()
            )
                    != null
        )
            .`as`("NFA")
            .isEqualTo(!expected.isEmpty())
        Assertions.assertThat(BitState.search(prog, input, false, false, false, prog.numCaptures()) != null)
            .`as`("BitState")
            .isEqualTo(!expected.isEmpty())
        val dfa = Dfa.search(prog, input, false, false)
        Assertions.assertThat<Dfa.SearchResult?>(dfa).`as`("DFA budget").isNotNull()
        Assertions.assertThat(dfa!!.matched).`as`("DFA").isEqualTo(!expected.isEmpty())
        val onePass = OnePass.build(prog)
        if (onePass != null) {
            Assertions.assertThat(onePass.search(input, false, prog.numCaptures()) != null)
                .`as`("OnePass")
                .isEqualTo(jdk.matcher(input).lookingAt())
        }
        Assertions.assertThat(safe.matcher(input).lookingAt()).isEqualTo(jdk.matcher(input).lookingAt())
        Assertions.assertThat(safe.matcher(input).matches()).isEqualTo(jdk.matcher(input).matches())
        Assertions.assertThat(safe.matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))).lookingAt())
            .isEqualTo(jdk.matcher(input).lookingAt())
        Assertions.assertThat(safe.matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))).matches())
            .isEqualTo(jdk.matcher(input).matches())
    }

    @Test
    fun mixedLineEndsRetainCrContextWithinAndAcrossSearches() {
        for (regex in mutableListOf<String>(
            "(?-d:(?m:$))(?dm:$)", "(?dm:$)(?-d:(?m:$))",
            "((?-d:(?m:$)))(?dm:$)", "(?-d:(?m:$))(?dm:$)a?"
        )) {
            for (flags in intArrayOf(0, org.safere.Pattern.UNIX_LINES)) {
                for (padding in intArrayOf(0, 512)) {
                    val inputs = mutableListOf<String>("\r\na\na", "\na\r\na", "\r\na", "a\na")
                    for (order in listOf(inputs, inputs.reversed())) {
                        val safe = org.safere.Pattern.compile(regex, flags)
                        val jdk = java.util.regex.Pattern.compile(regex, flags)
                        for (suffix in order) {
                            val input = "x".repeat(padding) + suffix
                            val expected: MutableList<String> = ArrayList<String>()
                            val reference = jdk.matcher(input)
                            while (reference.find()) {
                                expected.add(reference.start().toString() + ":" + reference.end())
                            }
                            val actual: MutableList<String> = ArrayList<String>()
                            val matcher = safe.matcher(input)
                            while (matcher.find()) {
                                actual.add(matcher.start().toString() + ":" + matcher.end())
                            }
                            Assertions.assertThat<String?>(actual)
                                .`as`("String /%s/ flags=%s input=%s", regex, flags, input)
                                .isEqualTo(expected)
                            actual.clear()
                            val utf8 = safe.matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
                            while (utf8.find()) {
                                actual.add(utf8.start().toString() + ":" + utf8.end())
                            }
                            Assertions.assertThat<String?>(actual)
                                .`as`("UTF-8 /%s/ flags=%s input=%s", regex, flags, input)
                                .isEqualTo(expected)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun scopedLineStartsAndCachedTransitionsMatchJdk() {
        for (flags in intArrayOf(0, org.safere.Pattern.UNIX_LINES)) {
            for (mode in mutableListOf<String>("d", "-d")) {
                for (regex in java.util.List.of<String?>(
                    "(?m)(?" + mode + ":^)(foo|bar)",
                    "(?m)(?" + mode + ":^foo$)|(?-d:^bar$)"
                )) {
                    val safe = org.safere.Pattern.compile(regex, flags)
                    val jdk = java.util.regex.Pattern.compile(regex, flags)
                    for (terminator in mutableListOf<String>("\n", "\r\n", "\r", "\u0085", "\u2028", "\u2029")) {
                        val input = "x".repeat(2048) + terminator + "foo" + terminator
                        val expected = jdk.matcher(input).find()
                        Assertions.assertThat(safe.matcher(input).find())
                            .`as`("String /%s/ flags=%s", regex, flags)
                            .isEqualTo(expected)
                        Assertions.assertThat(
                            safe.matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))).find()
                        )
                            .`as`("UTF-8 /%s/ flags=%s", regex, flags)
                            .isEqualTo(expected)
                    }
                }
            }
        }
    }

    @Test
    fun scopedEndAnchorsRespectUtf8RegionsAndBounds() {
        for (anchor in mutableListOf<String>("$", "\\Z", "(?m:$)")) {
            for (flags in intArrayOf(0, org.safere.Pattern.UNIX_LINES)) {
                for (mode in mutableListOf<String>("d", "-d")) {
                    val regex = "(foo|bar)(?" + mode + ":" + anchor + ")"
                    val safe = org.safere.Pattern.compile(regex, flags)
                    val jdk = java.util.regex.Pattern.compile(regex, flags)
                    for (terminator in mutableListOf<String>("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029")) {
                        val input = "xfoo" + terminator
                        for (end in 4..input.length) {
                            for (anchoring in booleanArrayOf(false, true)) {
                                for (transparent in booleanArrayOf(false, true)) {
                                    val reference =
                                        jdk.matcher(input)
                                            .region(1, end)
                                            .useAnchoringBounds(anchoring)
                                            .useTransparentBounds(transparent)
                                    val actual =
                                        Matcher(safe, Utf8InputScanner(input.toByteArray(StandardCharsets.UTF_8)))
                                            .region(1, input.substring(0, end).toByteArray(StandardCharsets.UTF_8).size)
                                            .useAnchoringBounds(anchoring)
                                            .useTransparentBounds(transparent)
                                    val expected = reference.find()
                                    Assertions.assertThat(actual.find())
                                        .`as`(
                                            "/%s/ flags=%s end=%s anchoring=%s transparent=%s",
                                            regex, flags, end, anchoring, transparent
                                        )
                                        .isEqualTo(expected)
                                    if (expected) {
                                        Assertions.assertThat(actual.start()).isEqualTo(reference.start())
                                        Assertions.assertThat(actual.end()).isEqualTo(reference.end())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private fun captureTrace(matcher: MatchResult): String? {
            val captures: MutableList<String> = ArrayList<String>()
            for (group in 0..matcher.groupCount()) {
                captures.add(matcher.start(group).toString() + ":" + matcher.end(group) + ":" + matcher.group(group))
            }
            return captures.toString()
        }

        @JvmStatic
        private fun anchorCases(): Stream<Arguments?> {
            val cases: MutableList<Arguments?> = ArrayList<Arguments?>()
            val terminators = arrayOf<String?>("", "\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029")
            val names = arrayOf<String?>("empty", "LF", "CR", "CRLF", "NEL", "LS", "PS")
            for (anchor in mutableListOf<String>("$", "\\Z", "\\z", "(?m:$)")) {
                for (flags in intArrayOf(0, org.safere.Pattern.UNIX_LINES)) {
                    for (body in mutableListOf<String>(
                        "foo",
                        "fo[op]",
                        "(?:foo|bar)",
                        "(foo|bar)",
                        "^foo",
                        "[a-z]+"
                    )) {
                        for (suffix in java.util.List.of<String?>(
                            "(?-d:" + anchor + ")",
                            "(?d:" + anchor + ")",
                            "(?d:(?-d:" + anchor + "))",
                            "(?-d:" + anchor + ")" + anchor,
                            "(?d:" + anchor + ")" + anchor,
                            "(?:(?d:" + anchor + ")|(?-d:" + anchor + "))",
                            "(?:(?-d:" + anchor + ")|(?d:" + anchor + "))"
                        )) {
                            for (i in terminators.indices) {
                                for (padding in intArrayOf(0, 500, 2048)) {
                                    cases.add(Arguments.of(body + suffix, flags, terminators[i], names[i], padding))
                                }
                            }
                        }
                    }
                }
            }
            return cases.stream()
        }
    }
}
