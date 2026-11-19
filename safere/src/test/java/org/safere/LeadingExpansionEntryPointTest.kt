// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.List
import java.util.regex.Pattern
import kotlin.collections.find
import kotlin.collections.mutableListOf
import kotlin.sequences.find
import kotlin.text.StringBuilder
import kotlin.text.find
import kotlin.text.repeat
import kotlin.text.substring
import kotlin.text.toByteArray

@DisabledForCrosscheck("compares String and UTF-8 entry points with diagnostics enabled and disabled")
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
internal class LeadingExpansionEntryPointTest {
    @AfterEach
    fun disableDiagnostics() {
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun leadingRepetitionPreservesMatchesCapturesReplacementAndSplit(diagnostics: Boolean) {
        Pattern.setDiagnostics(
            if (diagnostics) object : SafeReMatchDiagnostics() {} else SafeReMatchDiagnostics.NONE)
        for (regex in mutableListOf<String?>(
            "(\\s*)# noqa[0-9]",
            "(\\s*?)# noqa[0-9]",
            "(\\s+)# noqa[0-9]",
            "(\\s{0,3})# noqa[0-9]",
            "(\\s{1,3})# noqa[0-9]",
            "(\\s*)# noqa\\b",
            "([é😀]*)# noqa[0-9]",
            "(?i)(\\s*)# noqa[0-9]",
            "\\b([a-z]*)target[0-9]",
            "([ab]*)a[0-9]",
            "([ab]*)(?:a[0-9]|b[0-9])"
        )) {
            val pattern = Pattern.compile(regex)
            val jdk = Pattern.compile(regex)
            for (input in mutableListOf<String?>(
                "",
                "# noqaX",
                "    # noqaX",
                "    # noqa1",
                "# noqa1",
                "😀é    # noqaX    # noqa1    # noqa2 tail",
                "é😀é# noqa1é😀# noqaXé# noqa2",
                "    # NOQA1 tail",
                "prefix wordtarget1 targetX target2",
                "abbaX abba1 abbb2"
            )) {
                val utf8 = Utf8Input.trusted(input!!.toByteArray(StandardCharsets.UTF_8))
                Assertions.assertThat(pattern.find(utf8))
                    .`as`("%s on %s", regex, input)
                    .isEqualTo(jdk.matcher(input).find())
                val matcher = pattern.matcher(input)
                val utf8Matcher = pattern.matcher(utf8)
                val expected = jdk.matcher(input)
                while (true) {
                    val found = expected.find()
                    Assertions.assertThat(matcher.find()).`as`("%s on %s", regex, input).isEqualTo(found)
                    Assertions.assertThat(utf8Matcher.find()).`as`("UTF-8 %s on %s", regex, input).isEqualTo(found)
                    if (!found) {
                        break
                    }
                    for (group in 0..expected.groupCount()) {
                        Assertions.assertThat(matcher.start(group)).isEqualTo(expected.start(group))
                        Assertions.assertThat(matcher.end(group)).isEqualTo(expected.end(group))
                        Assertions.assertThat(matcher.group(group)).isEqualTo(expected.group(group))
                        Assertions.assertThat(utf8Matcher.start(group))
                            .isEqualTo(
                                input.substring(0, expected.start(group)).toByteArray(StandardCharsets.UTF_8).size
                            )
                        Assertions.assertThat(utf8Matcher.end(group))
                            .isEqualTo(
                                input.substring(0, expected.end(group)).toByteArray(StandardCharsets.UTF_8).size
                            )
                    }
                }
                for (replacement in mutableListOf<String?>("X", "<$0>", "<$1>")) {
                    Assertions.assertThat(pattern.matcher(input).replaceAll(replacement))
                        .`as`("%s replaceAll on %s", regex, input)
                        .isEqualTo(jdk.matcher(input).replaceAll(replacement))
                    Assertions.assertThat(pattern.matcher(input).replaceFirst(replacement))
                        .`as`("%s replaceFirst on %s", regex, input)
                        .isEqualTo(jdk.matcher(input).replaceFirst(replacement))
                }
                for (limit in intArrayOf(-1, 0, 1, 2)) {
                    Assertions.assertThat<String?>(pattern.split(input, limit))
                        .`as`("%s split on %s", regex, input)
                        .containsExactly(*jdk.split(input, limit))
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun exhaustedCandidateVerificationFallsBackToAuthoritativeMatching(diagnostics: Boolean) {
        org.safere.Pattern.setDiagnostics(
            if (diagnostics) object : SafeReMatchDiagnostics() {} else SafeReMatchDiagnostics.NONE)
        // Binary suffix windows require more DFA states than the cache can retain. The trailing
        // window still matches, so cache exhaustion must fall back rather than mean rejection.
        val input = StringBuilder(" ".repeat(2000) + "# noqa")
        val random = Random(1024)
        for (i in 0..30000 - 1) {
            input.append(if (random.nextBoolean()) 'a' else 'b')
        }
        input.append('a').append("b".repeat(14)).append('Z')
        val text = input.toString()
        val regex = "\\s*# noqa[ab]*a[ab]{14}Z"
        val pattern = org.safere.Pattern.compile(regex)
        Assertions.assertThat<Dfa.SearchResult?>(
            pattern
                .forwardFirstMatchDfa()
                .doSearch(StringInputScanner(text), 2000, false, false, true)
        )
            .isNull()
        Assertions.assertThat(pattern.matcher(text).replaceAll("X")).isEqualTo("X")
        Assertions.assertThat(pattern.matcher(text).replaceFirst("X")).isEqualTo("X")
        Assertions.assertThat(pattern.find(Utf8Input.trusted(text.toByteArray(StandardCharsets.UTF_8)))).isTrue()
        val earlyMatch = "# noqa" + "a" + "b".repeat(14) + "Z;"
        Assertions.assertThat(pattern.matcher(earlyMatch + text).replaceAll("X")).isEqualTo("X;X")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun replacementSearchDoesNotStartInsideAnIntactSurrogatePair(diagnostics: Boolean) {
        org.safere.Pattern.setDiagnostics(
            if (diagnostics) object : SafeReMatchDiagnostics() {} else SafeReMatchDiagnostics.NONE)
        for (regex in mutableListOf<String?>(
            "\\uDC00[bc]",
            "(\\uDC00)[bc]",
            "\\s*\\uDC00[bc]",
            "(\\s*)\\uDC00[bc]",
            "\\s*[\\uDC00-\\uDFFF][bc]",
            "\\s{0,3}\\uDC00[bc]",
            "\\s+\\uDC00[bc]",
            "\\s*\\uDC00[bc]\\b{g}"
        )) {
            val pattern = org.safere.Pattern.compile(regex)
            val jdk = Pattern.compile(regex)
            for (input in mutableListOf<String?>(
                "\uDC00b;\uD800\uDC00b",
                " \uDC00b; \uD800\uDC00b \uDC00c;",
                " \uDC00b;\uD800\uDC00b;\uD800\uDC00c; \uDC00c"
            )) {
                for (replacement in mutableListOf<String?>("X", "<$0>")) {
                    Assertions.assertThat(pattern.matcher(input).replaceAll(replacement))
                        .`as`("%s on %s", regex, input)
                        .isEqualTo(jdk.matcher(input).replaceAll(replacement))
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun repeatedLiteralPrefixReplacementPreservesMatches(diagnostics: Boolean) {
        org.safere.Pattern.setDiagnostics(
            if (diagnostics) object : SafeReMatchDiagnostics() {} else SafeReMatchDiagnostics.NONE)
        for (regex in mutableListOf<String?>(
            "tag[bc]",
            "(?i)tag[bc]",
            "(?iu)éx[bc]",
            "(?iu)İx[bc]",
            "😀[bc]",
            "^tag[bc]",
            "(?m)^tag[bc]"
        )) {
            val pattern = org.safere.Pattern.compile(regex)
            val jdk = Pattern.compile(regex)
            for (input in List.of<String?>(
                "",
                "tagX",
                "tagb TAGc tagX tagc;",
                "tagb\ntagc\nTAGb tagX;tagc",
                "éxb ÉXc éxX éxc",
                "İxb ixc ıxX İxc",
                "😀b;😀c 😀X 😀b",
                "x".repeat(1000) + "tagb;tagX;tagc"
            )) {
                for (replacement in mutableListOf<String?>("X", "<$0>")) {
                    Assertions.assertThat(pattern.matcher(input).replaceAll(replacement))
                        .`as`("%s replaceAll on %s", regex, input)
                        .isEqualTo(jdk.matcher(input).replaceAll(replacement))
                    Assertions.assertThat(pattern.matcher(input).replaceFirst(replacement))
                        .`as`("%s replaceFirst on %s", regex, input)
                        .isEqualTo(jdk.matcher(input).replaceFirst(replacement))
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun initialSurrogateInteriorCursorRemainsEligibleForLeadingExpansion(diagnostics: Boolean) {
        org.safere.Pattern.setDiagnostics(
            if (diagnostics) object : SafeReMatchDiagnostics() {} else SafeReMatchDiagnostics.NONE)
        val input = "\uD800\uDC00b; \uDC00c;\uD800\uDC00b"
        for (regex in mutableListOf<String?>("\\s*\\uDC00[bc]", "\\s{0,3}\\uDC00[bc]", "\\s*[\\uDC00-\\uDFFF][bc]")) {
            val expected = Pattern.compile(regex).matcher(input)
            val actual = org.safere.Pattern.compile(regex).matcher(input)
            Assertions.assertThat(actual.find(1)).isEqualTo(expected.find(1)).isTrue()
            Assertions.assertThat(actual.start()).isEqualTo(expected.start())
            Assertions.assertThat(actual.end()).isEqualTo(expected.end())
            actual.region(1, input.length)
            expected.region(1, input.length)
            Assertions.assertThat(actual.find()).isEqualTo(expected.find()).isTrue()
            Assertions.assertThat(actual.start()).isEqualTo(expected.start())
            Assertions.assertThat(actual.end()).isEqualTo(expected.end())
        }
    }
}
