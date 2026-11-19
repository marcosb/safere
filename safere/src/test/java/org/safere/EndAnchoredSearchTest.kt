// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.List
import java.util.regex.Pattern

/** End-first search must preserve exact matching priority and capture boundaries.  */
@DisabledForCrosscheck("compares the JDK and forced SafeRE engine paths directly")
internal class EndAnchoredSearchTest {
    @Test
    fun anchoredOnePassTerminalFiltersPreserveLineEndingsAndRegions() {
        for (regex in mutableListOf<String?>(
            "^(a)*b$",
            "(?d)^(a)*b$",
            "^(a)*b\\z",
            "(?i)^(a)*B$",
            "^([a-z]+)[0-9]$",
            "^([a-z]+)END\\z"
        )) {
            val pattern = Pattern.compile(regex)
            val jdk = Pattern.compile(regex)
            for (input in mutableListOf<String?>(
                "",
                "aaab",
                "aaac",
                "aaab\n",
                "aaab\r\n",
                "aaab\u0085",
                "aaab\u2028",
                "aaab\u2029",
                "aaa5",
                "aaa5\n",
                "aaaEND",
                "aaaEND\n"
            )) {
                val utf8 = Utf8Input.validated(input!!.toByteArray(StandardCharsets.UTF_8))
                Assertions.assertThat(pattern.matcher(input).matches())
                    .`as`("/%s/ on %s", regex, input)
                    .isEqualTo(jdk.matcher(input).matches())
                Assertions.assertThat(pattern.matcher(utf8).matches()).isEqualTo(jdk.matcher(input).matches())
                Companion.assertFindSequence(pattern, jdk, input, utf8)
                Assertions.assertThat(pattern.matcher(input).replaceAll("<$1>"))
                    .isEqualTo(jdk.matcher(input).replaceAll("<$1>"))
                for (anchoring in mutableListOf<Boolean?>(false, true)) {
                    for (transparent in mutableListOf<Boolean?>(false, true)) {
                        val surrounded = "x" + input + "y"
                        val actual =
                            pattern
                                .matcher(surrounded)
                                .region(1, 1 + input.length)
                                .useAnchoringBounds(anchoring!!)
                                .useTransparentBounds(transparent!!)
                        val expected =
                            jdk.matcher(surrounded)
                                .region(1, 1 + input.length)
                                .useAnchoringBounds(anchoring)
                                .useTransparentBounds(transparent)
                        Assertions.assertThat(actual.matches())
                            .`as`(
                                "matches /%s/ on %s, anchoring=%s, transparent=%s",
                                regex, input, anchoring, transparent
                            )
                            .isEqualTo(expected.matches())
                        // Reset the search position: initial find after failed matches intentionally diverges.
                        actual.region(1, 1 + input.length)
                        expected.region(1, 1 + input.length)
                        Assertions.assertThat(actual.find())
                            .`as`(
                                "find /%s/ on %s, anchoring=%s, transparent=%s",
                                regex, input, anchoring, transparent
                            )
                            .isEqualTo(expected.find())
                    }
                }
            }
        }
    }

    @Test
    fun endAnchoredSearchPreservesFindSequenceAndCaptures() {
        for (regex in mutableListOf<String?>(
            "abc$",
            "(a)(b)(c)$",
            "(?:abc|bc)$",
            "(?:bc|abc)$",
            "a+?b$",
            "(?:a+?|[^x]*)$",
            "(?s:abc.*?$)",
            "(?s:.*)abc$",
            "(?s:.*)abc\\z",
            "(?:abc\\r?|abc)$",
            "abc\\z",
            "abc\\Z",
            "(?d:abc\\Z)",
            "(?d:abc$)",
            "(?d:abc(?-d:$))",
            "(?-d:abc(?d:$))",
            "(?m:abc$)",
            "(?i:abc)$",
            "(?iu:é)😀$",
            "\\babc$",
            "^abc$",
            "$",
            "\\z",
            "((a)?b)*$",
            "\\X$"
        )) {
            val jdkPattern = Pattern.compile(regex)
            for (options in List.of<EnginePathOptions?>(
                EnginePathOptions.allEnabled(),
                EnginePathOptions.builder().startAcceleration(false).build(),
                EnginePathOptions.builder().reverseDfa(false).build()
            )) {
                val pattern = org.safere.Pattern.compile(regex, 0, options)
                for (prefix in List.of<String?>("", "x".repeat(1024), "é😀".repeat(400))) {
                    for (tail in mutableListOf<String?>(
                        "",
                        "abc",
                        "abc\n",
                        "abc\r",
                        "abc\r\n",
                        "abc\u0085",
                        "abc\u2028",
                        "abc\u2029",
                        "aaab",
                        "ABC",
                        "é😀",
                        "abca\n",
                        "abcd"
                    )) {
                        val input = prefix + tail
                        val bytes = input.toByteArray(StandardCharsets.UTF_8)
                        val storage = ByteArray(bytes.size + 7)
                        System.arraycopy(bytes, 0, storage, 3, bytes.size)
                        val utf8 = Utf8Input.trusted(storage, 3, bytes.size)
                        Assertions.assertThat(pattern.find(utf8))
                            .`as`(
                                "existence /%s/ with prefix length %s and tail %s",
                                regex, prefix.size, tail
                            )
                            .isEqualTo(jdkPattern.matcher(input).find())
                        assertFindSequence(pattern, jdkPattern, input, utf8)
                    }
                }
            }
        }
    }

    @Test
    fun unboundedEndAnchoredSearchPreservesResultsAcrossProbeBoundaries() {
        for (regex in mutableListOf<String?>(
            "needle.*$",
            "(needle)([a-z]*)\\z",
            "(?:needle.*|x+)$",
            "(?:needle|need).*?$",
            "\\bneedle[a-z]*$",
            "\\Bneedle[a-z]*$",
            "(?m:^needle)[a-z]*\\z",
            "(?s:.*)needle$"
        )) {
            val jdkPattern = Pattern.compile(regex)
            for (options in List.of<EnginePathOptions?>(
                EnginePathOptions.allEnabled(),
                EnginePathOptions.builder().reverseDfa(false).build(),
                EnginePathOptions.builder().literalFastPaths(false).build()
            )) {
                val pattern = org.safere.Pattern.compile(regex, 0, options)
                for (gap in mutableListOf<Int?>(0, 1, 7, 63, 64, 65, 127, 511, 1023)) {
                    for (prefix in List.of<String?>("x".repeat(1024), "é😀".repeat(400))) {
                        for (body in List.of<String?>(
                            "x".repeat(gap!!),
                            "needle" + "x".repeat(gap),
                            "needle" + "x".repeat(gap) + "needle",
                            "\nneedle" + "x".repeat(gap)
                        )) {
                            for (terminator in mutableListOf<String?>("", "\n", "\r\n")) {
                                val input = prefix + body + terminator
                                val bytes = input.toByteArray(StandardCharsets.UTF_8)
                                val storage = ByteArray(bytes.size + 7)
                                System.arraycopy(bytes, 0, storage, 3, bytes.size)
                                val utf8 = Utf8Input.trusted(storage, 3, bytes.size)
                                Assertions.assertThat(pattern.find(utf8))
                                    .`as`(
                                        "existence /%s/ with gap %s and prefix length %s",
                                        regex, gap, prefix.size
                                    )
                                    .isEqualTo(jdkPattern.matcher(input).find())
                                assertFindSequence(pattern, jdkPattern, input, utf8)
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private fun assertFindSequence(
            pattern: org.safere.Pattern, jdkPattern: Pattern, input: String, utf8: Utf8Input?
        ) {
            val matcher = pattern.matcher(input)
            val utf8Matcher = pattern.matcher(utf8)
            val jdkMatcher = jdkPattern.matcher(input)
            var found: Boolean
            do {
                found = jdkMatcher.find()
                Assertions.assertThat(matcher.find()).`as`("String /%s/", pattern).isEqualTo(found)
                Assertions.assertThat(utf8Matcher.find()).`as`("UTF-8 /%s/", pattern).isEqualTo(found)
                if (found) {
                    for (group in 0..matcher.groupCount()) {
                        Assertions.assertThat(matcher.start(group))
                            .`as`("start /%s/ group %s", pattern, group)
                            .isEqualTo(jdkMatcher.start(group))
                        Assertions.assertThat(matcher.end(group))
                            .`as`("end /%s/ group %s", pattern, group)
                            .isEqualTo(jdkMatcher.end(group))
                        Assertions.assertThat(matcher.group(group))
                            .`as`("capture /%s/ group %s", pattern, group)
                            .isEqualTo(jdkMatcher.group(group))
                        Assertions.assertThat(utf8Matcher.start(group))
                            .`as`("UTF-8 start /%s/ group %s", pattern, group)
                            .isEqualTo(byteOffset(input, jdkMatcher.start(group)))
                        Assertions.assertThat(utf8Matcher.end(group))
                            .`as`("UTF-8 end /%s/ group %s", pattern, group)
                            .isEqualTo(byteOffset(input, jdkMatcher.end(group)))
                    }
                }
            } while (found)
        }

        private fun byteOffset(input: String, index: Int): Int {
            return if (index < 0) -1 else input.substring(0, index).toByteArray(StandardCharsets.UTF_8).size
        }
    }
}
