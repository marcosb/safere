// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.iterable.ThrowingExtractor
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.safere.Utf8EnginePathEquivalenceTest.Companion.trace
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.stream.Stream

/** Forced-path equivalence coverage for matching directly over UTF-8 storage.  */
@DisabledForCrosscheck("uses package-private engine-path controls to compare SafeRE internals")
internal class Utf8EnginePathEquivalenceTest {
    @ParameterizedTest
    @ValueSource(
        strings = ["a*", "a+", "[é😀]*", "[^a]+", "(?i)é*", "[aé😀]{0,}", "a*?", "[é😀]+?", "(a*)", "^a*", "a*$"
        ]
    )
    fun repeatedClassSearchAndReplacementAgreeWithGenericUtf8Engines(regex: String?) {
        val canonical = Pattern.compile(regex)
        val generic =
            Pattern.compile(
                regex, 0, EnginePathOptions.builder().charClassMatchFastPaths(false).build()
            )
        for (source in mutableListOf<String>("", "abc", "aaaa", "é😀aé", "Éé\na", "👩‍💻a")) {
            val bytes = ("prefix" + source + "suffix").toByteArray(StandardCharsets.UTF_8)
            val input = Utf8Input.validated(bytes, 6, source!!.toByteArray(StandardCharsets.UTF_8).size)
            Assertions.assertThat<MatchTrace?>(trace(canonical, input))
                .`as`("/%s/ on %s", regex, source)
                .isEqualTo(trace(generic, input))
            for (replacement in mutableListOf<String>("x", "<$0>", "\\$", "前😀後", "")) {
                for (all in booleanArrayOf(false, true)) {
                    Assertions.assertThat(Companion.replacement(canonical, input, replacement!!, all))
                        .`as`("/%s/ on %s replacing with %s, all=%s", regex, source, replacement, all)
                        .isEqualTo(replacement(generic, input, replacement, all))
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forcedPaths")
    @DisplayName("forced UTF-8 engine paths preserve byte-coordinate find traces")
    fun forcedEnginePathsPreserveFindTraces(
        description: String?, regex: String?, input: String, forced: EnginePathOptions?
    ) {
        val canonical = Pattern.compile(regex)
        val forcedPattern = Pattern.compile(regex, 0, forced)

        Assertions.assertThat<MatchTrace?>(trace(forcedPattern, input))
            .`as`("%s: /%s/ on %s", description, regex, input)
            .isEqualTo(trace(canonical, input))
        Assertions.assertThat<MatchTrace?>(trace(canonical, input))
            .`as`("String/UTF-8 oracle: /%s/ on %s", regex, input)
            .isEqualTo(stringOracleTrace(canonical, input))
    }

    @Test
    @DisplayName("empty UTF-8 matches advance by Unicode scalar boundaries across engines")
    fun emptyMatchesAdvanceByUnicodeScalarBoundariesAcrossEngines() {
        val regex = "(?<empty>)(?:é)?"
        val input = "😀éx"
        val canonical = Pattern.compile(regex)
        val nfa = Pattern.compile(regex, 0, canonicalFallback())

        Assertions.assertThat<MatchTrace?>(trace(nfa, input)).isEqualTo(trace(canonical, input))
        Assertions.assertThat<MatchTrace?>(trace(canonical, input))
            .extracting<Int?, RuntimeException?>(ThrowingExtractor { match: MatchTrace? -> match!!.groups!!.first()!!.start })
            .containsExactly(0, 4, 6, 7)
    }

    @Test
    @DisplayName("UTF-8 convenience find supports discrete and non-ASCII fixed offsets")
    fun utf8ConvenienceFindSupportsGeneralizedFixedOffsets() {
        for (regex in mutableListOf<String>(
            "(^|a)bc",
            "(^|[^#])(#!customTag)",
            "[aé]bc",
            "[a😀]bc",
            "(aq|b[a-z]{9})z"
        )) {
            val input =
                when (regex) {
                    "(^|a)bc" -> "abc"
                    "(^|[^#])(#!customTag)" -> "é#!customTag"
                    "[aé]bc" -> "ébc"
                    "[a😀]bc" -> "😀bc"
                    "(aq|b[a-z]{9})z" -> "bxxaxzxxxxz"
                    else -> throw AssertionError(regex)
                }
            val pattern = Pattern.compile(regex)
            val utf8Input = Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))

            Assertions.assertThat(pattern.find(utf8Input)).`as`("/%s/ on %s", regex, input).isTrue()
        }
    }

    @Test
    @DisplayName("UTF-8 convenience find works with the DFA disabled")
    fun utf8ConvenienceFindWorksWithDfaDisabled() {
        val noDfa = EnginePathOptions.builder().dfa(false).build()
        for (regex in mutableListOf<String>("a*[bc]d", "[a-z]+[0-9]", "(?:foo|fax)$", "é[ab]+c", "^[0-9]+ items")) {
            for (input in mutableListOf<String>("zzaabdzz", "xyz12", "a fax", "zzéabczz", "42 items", "none")) {
                val expected = Pattern.compile(regex).matcher(input).find()
                Assertions.assertThat(
                    Pattern.compile(regex, 0, noDfa)
                        .find(Utf8Input.validated(input!!.toByteArray(StandardCharsets.UTF_8)))
                )
                    .`as`("/%s/ on %s", regex, input)
                    .isEqualTo(expected)
            }
        }
    }

    @Test
    @DisplayName("malformed trusted input is equivalent across UTF-8 fallback engines")
    fun malformedTrustedInputIsEquivalentAcrossFallbackEngines() {
        val bytes = byteArrayOf(
            'a'.code.toByte(),
            0x80.toByte(),
            0xc2.toByte(),
            'b'.code.toByte(),
            0xf0.toByte(),
            0x9f.toByte(),
            'c'.code.toByte()
        )
        val regexes = mutableListOf<String>(".", ".*?", "[^a]", "(?:a|.)+", "^.*$", "\\X")

        for (regex in regexes) {
            val canonical = Pattern.compile(regex)
            val nfa = Pattern.compile(regex, 0, canonicalFallback())
            Assertions.assertThat<MatchTrace?>(trace(nfa, Utf8Input.trusted(bytes)))
                .`as`("malformed trusted input for /%s/", regex)
                .isEqualTo(trace(canonical, Utf8Input.trusted(bytes)))
        }
    }

    @Test
    @DisplayName("DFA caches do not mix UTF-8 input views")
    fun dfaCachesDoNotMixInputViews() {
        val pattern = Pattern.compile("(?<value>[\\p{L}]+)([0-9]+)")
        val first = "padding-élan42-tail".toByteArray(StandardCharsets.UTF_8)
        val second = "padding-β7-tail".toByteArray(StandardCharsets.UTF_8)

        Assertions.assertThat<MatchTrace?>(
            trace(
                pattern, Utf8Input.validated(
                    first, 8, "élan42".toByteArray(
                        StandardCharsets.UTF_8
                    ).size
                )
            )
        )
            .containsExactly(
                MatchTrace(java.util.List.of<GroupTrace?>(group("élan42", 0, 7), group("élan", 0, 5), group("42", 5, 7)))
            )
        Assertions.assertThat<MatchTrace?>(
            trace(
                pattern, Utf8Input.validated(
                    second, 8, "β7".toByteArray(
                        StandardCharsets.UTF_8
                    ).size
                )
            )
        )
            .containsExactly(
                MatchTrace(java.util.List.of<GroupTrace?>(group("β7", 0, 3), group("β", 0, 2), group("7", 2, 3)))
            )
    }

    @Test
    @DisplayName("pattern engine caches do not retain UTF-8 input views")
    @Throws(ReflectiveOperationException::class)
    fun patternEngineCachesDoNotRetainUtf8InputViews() {
        val pattern =
            Pattern.compile(
                "(?:é|éé)*x", 0, EnginePathOptions.builder().dfa(false).onePass(false).build()
            )
        Assertions.assertThat(pattern.matcher(Utf8Input.validated("ééx".toByteArray(StandardCharsets.UTF_8))).find())
            .isTrue()

        val cached = pattern.borrowBitState()
        Assertions.assertThat<BitState?>(cached).isNotNull()
        val text = BitState::class.java.getDeclaredField("text")
        text.setAccessible(true)
        val graphemeContext = BitState::class.java.getDeclaredField("graphemeContext")
        graphemeContext.setAccessible(true)
        Assertions.assertThat<Any?>(text.get(cached)).isNull()
        Assertions.assertThat<Any?>(graphemeContext.get(cached)).isNull()
        pattern.returnBitState(cached!!)
    }

    @ParameterizedTest
    @MethodSource("booleanFindStartBoundaryCases")
    fun booleanFindPreservesStartBoundary(
        regex: String, repeated: String, terminal: String?, reverseDfa: Boolean
    ) {
        val pattern =
            Pattern.compile(regex, 0, EnginePathOptions.builder().reverseDfa(reverseDfa).build())
        val jdk = java.util.regex.Pattern.compile(regex)
        for (length in mutableListOf<Int>(0, 1, 30, 31, 59, 61, 62, 63, 64, 65, 127)) {
            val body = repeated.repeat(length!!)
            for (text in java.util.List.of<String?>(
                body + terminal,
                body + "!" + repeated + terminal,
                "!" + body + terminal,
                body + terminal + "\r\n",
                body + "!\n" + repeated + terminal,
                body + terminal + "!"
            )) {
                val expected = jdk.matcher(text).find()
                Assertions.assertThat(pattern.matcher(text).find())
                    .`as`("String /%s/ length %s", regex, length)
                    .isEqualTo(expected)
                Assertions.assertThat(pattern.asPredicate().test(text))
                    .`as`("String predicate /%s/ length %s", regex, length)
                    .isEqualTo(expected)
                for (padding in mutableListOf<String>("", "é😀padding")) {
                    val bytes = (padding + text + "outside").toByteArray(StandardCharsets.UTF_8)
                    val input =
                        Utf8Input.validated(
                            bytes,
                            padding!!.toByteArray(StandardCharsets.UTF_8).size,
                            text.toByteArray(StandardCharsets.UTF_8).size
                        )
                    Assertions.assertThat(pattern.matcher(input).find())
                        .`as`("UTF-8 matcher /%s/ length %s on %s", regex, length, text)
                        .isEqualTo(expected)
                    Assertions.assertThat(pattern.find(input))
                        .`as`("UTF-8 boolean /%s/ length %s on %s", regex, length, text)
                        .isEqualTo(expected)
                }
            }
        }
    }

    @JvmRecord
    private data class MatchTrace(val groups: MutableList<GroupTrace?>?)

    @JvmRecord
    private data class GroupTrace(val value: String?, val start: Int, val end: Int)
    companion object {
        private fun replacement(
            pattern: Pattern, input: Utf8Input?, replacement: String, all: Boolean
        ): String? {
            val output = ByteArrayOutputStream()
            val matcher = pattern.matcher(input)
            val template = Utf8Input.validated(replacement.toByteArray(StandardCharsets.UTF_8))
            if (all) {
                matcher.replaceAll(
                    Utf8Sink { b: ByteArray?, off: Int, len: Int -> output.write(b, off, len) },
                    template
                )
            } else {
                matcher.replaceFirst(
                    Utf8Sink { b: ByteArray?, off: Int, len: Int -> output.write(b, off, len) },
                    template
                )
            }
            return output.toString(StandardCharsets.UTF_8)
        }

        @JvmStatic
        private fun forcedPaths(): Stream<Arguments?> {
            val longPrefix = "x".repeat(600)
            return Stream.of<Arguments?>(
                Arguments.of(
                    "OnePass versus DFA/fallback",
                    "^(?<word>[\\p{L}]+):([0-9]+)$",
                    "élan:42",
                    EnginePathOptions.builder().onePass(false).build()
                ),
                Arguments.of(
                    "forward, reverse, and anchored DFA versus Pike NFA",
                    "([\\p{L}]+?)([0-9]+)",
                    "😀élan42 fin7",
                    EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build()
                ),
                Arguments.of(
                    "reverse-first end-anchored DFA versus forward search",
                    "(?<tail>élan[0-9]+)$",
                    longPrefix + "élan42",
                    EnginePathOptions.builder().reverseDfa(false).build()
                ),
                Arguments.of(
                    "BitState versus Pike NFA",
                    "((?:é|éé)*)(😀)?",
                    "ééé😀xé",
                    EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build()
                ),
                Arguments.of(
                    "deferred versus eager capture extraction",
                    "(?<letters>[\\p{L}]+)(?<digits>[0-9]+)",
                    "😀élan42 fin7",
                    EnginePathOptions.builder().lazyCaptureExtraction(false).build()
                ),
                Arguments.of(
                    "line anchors and CRLF", "(?m)^(?<line>[^\\r\\n]*)$", "α\r\nβ\n", canonicalFallback()
                ),
                Arguments.of(
                    "Unicode word boundaries", "\\b(?<word>élan|β)\\b", "😀 élan,β!", canonicalFallback()
                ),
                Arguments.of(
                    "grapheme boundaries", "(?<cluster>\\X)\\b{g}", "á👩‍💻β", canonicalFallback()
                ),
                Arguments.of(
                    "literal fast path is safely guarded",
                    "élan",
                    "😀élanélan",
                    EnginePathOptions.builder().literalFastPaths(false).build()
                ),
                Arguments.of(
                    "ASCII literal supersedes ShiftDfa unless literal paths are disabled",
                    "hello",
                    "😀hellohello",
                    EnginePathOptions.builder().literalFastPaths(false).build()
                ),
                Arguments.of(
                    "literal prefix acceleration is safely guarded",
                    "élan[0-9]+",
                    "😀xxélan42 yyélan7",
                    EnginePathOptions.builder().startAcceleration(false).build()
                ),
                Arguments.of(
                    "character-class acceleration is safely guarded",
                    "[α-ω][0-9]+",
                    "😀xxβ42 yyγ7",
                    EnginePathOptions.builder()
                        .charClassMatchFastPaths(false)
                        .startAcceleration(false)
                        .build()
                ),
                Arguments.of(
                    "mixed ASCII and non-ASCII class does not use byte-offset acceleration",
                    "[aé]bc",
                    "xébc abc",
                    EnginePathOptions.builder().startAcceleration(false).build()
                ),
                Arguments.of(
                    "non-ASCII range does not use byte-offset acceleration",
                    "[a-ÿ]bc",
                    "xébc abc",
                    EnginePathOptions.builder().startAcceleration(false).build()
                ),
                Arguments.of(
                    "supplementary class does not use byte-offset acceleration",
                    "[a😀]bc",
                    "x😀bc abc",
                    EnginePathOptions.builder().startAcceleration(false).build()
                ),
                Arguments.of(
                    "negated class does not use byte-offset acceleration",
                    "[^x]bc",
                    "xébc abc",
                    EnginePathOptions.builder().startAcceleration(false).build()
                ),
                Arguments.of(
                    "multi-offset acceleration preserves earlier starts from later literals",
                    "(aq|b[a-z]{9})z",
                    "bxxaxzxxxxz",
                    EnginePathOptions.builder().startAcceleration(false).build()
                )
            )
        }

        @JvmStatic
        private fun booleanFindStartBoundaryCases(): Stream<Arguments?> {
            val cases: MutableList<Arguments?> = ArrayList<Arguments?>()
            for (start in mutableListOf<String>("^", "\\A", "(?m)^", "")) {
                for (end in mutableListOf<String>("$", "\\z", "\\Z", "")) {
                    for (terminal in mutableListOf<String>("b", "END", "[0-9]")) {
                        val suffix: String = (if (terminal == "[0-9]") "5" else terminal)!!
                        for (reverseDfa in mutableListOf<Boolean>(true, false)) {
                            cases.add(Arguments.of(start + "(a)*" + terminal + end, "a", suffix, reverseDfa))
                            cases.add(Arguments.of(start + "([a-z]+)" + terminal + end, "a", suffix, reverseDfa))
                            cases.add(
                                Arguments.of(start + "([aé😀]+)" + terminal + end, "é😀a", suffix, reverseDfa)
                            )
                        }
                    }
                }
            }
            return cases.stream()
        }

        private fun canonicalFallback(): EnginePathOptions {
            return EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .keywordAlternationFastPath(false)
                .startAcceleration(false)
                .onePass(false)
                .dfa(false)
                .reverseDfa(false)
                .bitState(false)
                .lazyCaptureExtraction(false)
                .build()
        }

        private fun trace(pattern: Pattern, input: String): MutableList<MatchTrace?> {
            return trace(pattern, Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
        }

        private fun trace(pattern: Pattern, input: Utf8Input?): MutableList<MatchTrace?> {
            val array = input as ArrayUtf8Input
            val matcher = pattern.matcher(input)
            val matches: MutableList<MatchTrace?> = ArrayList<MatchTrace?>()
            while (matcher.find()) {
                val groups: MutableList<GroupTrace?> = ArrayList<GroupTrace?>()
                for (group in 0..matcher.groupCount()) {
                    val start = matcher.start(group)
                    val end = matcher.end(group)
                    val value =
                        if (start < 0)
                            null
                        else String(
                            array.scanner().bytes(),
                            array.scanner().offset() + start,
                            end - start,
                            StandardCharsets.UTF_8
                        )
                    groups.add(group(value, start, end))
                }
                matches.add(MatchTrace(groups))
            }
            return matches
        }

        private fun stringOracleTrace(pattern: Pattern, input: String): MutableList<MatchTrace?> {
            val matcher = pattern.matcher(input)
            val matches: MutableList<MatchTrace?> = ArrayList<MatchTrace?>()
            while (matcher.find()) {
                val groups: MutableList<GroupTrace?> = ArrayList<GroupTrace?>()
                for (group in 0..matcher.groupCount()) {
                    val start = matcher.start(group)
                    val end = matcher.end(group)
                    groups.add(
                        group(
                            matcher.group(group),
                            utf8Offset(input, start),
                            if (end < 0) -1 else utf8Offset(input, end)
                        )
                    )
                }
                matches.add(MatchTrace(groups))
            }
            return matches
        }

        private fun utf8Offset(input: String, utf16Offset: Int): Int {
            return if (utf16Offset < 0) -1 else input.substring(0, utf16Offset).toByteArray(StandardCharsets.UTF_8).size
        }

        private fun group(value: String?, start: Int, end: Int): GroupTrace {
            return GroupTrace(value, start, end)
        }
    }
}
