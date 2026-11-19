// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.charset.StandardCharsets
import java.util.stream.Stream

/** Fresh entries to nested nullable repetitions must not inherit a previous entry's progress.  */
@DisabledForCrosscheck("compares directly with the JDK using package-private engine controls")
internal class NestedNullableLoopTest {
    @ParameterizedTest(name = "[{index}] /{0}/")
    @MethodSource("nestedNullableEpsilonCases")
    fun nestedNullableEpsilonFindSequencesMatchJdk(regex: String) {
        val input = "!a!".repeat(300) + "z"
        val nfaOnly =
            EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build()
        val pattern = Pattern.compile(regex, 0, nfaOnly)
        val jdk = java.util.regex.Pattern.compile(regex).matcher(input)
        val safere = pattern.matcher(input)
        val utf8 = pattern.matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))
        // The ASCII input gives both representations the same offsets and empty-match advancement.
        while (jdk.find()) {
            Assertions.assertThat(safere.find()).isTrue()
            assertGroupsMatch(jdk, safere)
            Assertions.assertThat(utf8.find()).isTrue()
            Assertions.assertThat(utf8.start()).isEqualTo(jdk.start())
            Assertions.assertThat(utf8.end()).isEqualTo(jdk.end())
        }
        Assertions.assertThat(safere.find()).isFalse()
        Assertions.assertThat(utf8.find()).isFalse()
    }

    @ParameterizedTest(name = "[{index}] /{0}/ on {1}")
    @MethodSource("nestedNullableLoopCases")
    fun freshLoopEntriesMatchJdkAcrossEngines(regex: String, input: String) {
        val jdkPattern = java.util.regex.Pattern.compile(regex)
        for (options in ENGINE_OPTIONS) {
            val pattern = Pattern.compile(regex, 0, options)
            var jdk = jdkPattern.matcher(input)
            val safere = pattern.matcher(input)
            val matches = jdk.matches()
            Assertions.assertThat(safere.matches()).isEqualTo(matches)
            if (matches) {
                assertGroupsMatch(jdk, safere)
            }
            jdk.reset()
            safere.reset()
            val lookingAt = jdk.lookingAt()
            Assertions.assertThat(safere.lookingAt()).isEqualTo(lookingAt)
            if (lookingAt) {
                assertGroupsMatch(jdk, safere)
            }
            jdk.reset()
            safere.reset()
            while (jdk.find()) {
                Assertions.assertThat(safere.find()).isTrue()
                assertGroupsMatch(jdk, safere)
            }
            Assertions.assertThat(safere.find()).isFalse()
            Assertions.assertThat(pattern.matcher(input).replaceAll("-"))
                .isEqualTo(jdkPattern.matcher(input).replaceAll("-"))

            // Both representations use the same compiled loops. Compare a nonzero region as well,
            // mapping JDK UTF-16 coordinates to UTF-8 byte offsets. Only the first match is compared
            // because subsequent empty-match advancement has representation-specific semantics.
            val regionInput = "!" + input + "!"
            jdk = jdkPattern.matcher(regionInput).region(1, 1 + input.length)
            val bytes = regionInput.toByteArray(StandardCharsets.UTF_8)
            val utf8 = pattern.matcher(Utf8Input.validated(bytes)).region(1, bytes.size - 1)
            val found = jdk.find()
            Assertions.assertThat(utf8.find()).isEqualTo(found)
            if (found) {
                for (group in 0..jdk.groupCount()) {
                    Assertions.assertThat(utf8.start(group)).isEqualTo(byteOffset(regionInput, jdk.start(group)))
                    Assertions.assertThat(utf8.end(group)).isEqualTo(byteOffset(regionInput, jdk.end(group)))
                }
            }
        }
    }

    companion object {
        private val ENGINE_OPTIONS: MutableList<EnginePathOptions?> = java.util.List.of<EnginePathOptions?>(
            EnginePathOptions.builder().build(),
            EnginePathOptions.builder().onePass(false).bitState(false).build(),
            EnginePathOptions.builder().dfa(false).onePass(false).build(),
            EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build()
        )

        @JvmStatic
        private fun nestedNullableLoopCases(): Stream<Arguments?> {
            val cases: MutableList<Arguments?> = ArrayList<Arguments?>()
            for (atom in mutableListOf<String>("x", "xx", "é", "😀")) {
                for (body in java.util.List.of<String?>("(?:" + atom + "|)", "(?:" + atom + "?)")) {
                    for (inner in mutableListOf<String>("+?", "*?", "+", "*")) {
                        for (outer in mutableListOf<String>("*", "+", "{1,}", "{2}", "+?")) {
                            val regex = "(?:" + body + inner + ")" + outer
                            val input = atom!!.repeat(4)
                            cases.add(Arguments.of(regex, input))
                            cases.add(
                                Arguments.of("(?:a?" + body + inner + "b?)" + outer, atom + "b" + atom.repeat(2))
                            )
                            cases.add(Arguments.of(regex + "y", input + "y"))
                            cases.add(Arguments.of(regex + "$", input))
                            cases.add(Arguments.of("(" + regex + ")", input))
                        }
                    }
                }
            }
            cases.add(Arguments.of("(?:(?:x|)+?)*", "xx"))
            cases.add(Arguments.of("(?:(?:x|)+?b?)*", "xbx"))
            cases.add(Arguments.of("(?:a?(?:x|)+?)*", "xx"))
            cases.add(Arguments.of("(?:(?:xx|)+?)*", "xxxx"))
            cases.add(Arguments.of("(?:(?:x?)+?)*", "xx"))
            cases.add(Arguments.of("(?:(?:x|)+?){1,}", "xx"))
            // Captures within the repeated body also exercise retained and empty final captures.
            cases.add(Arguments.of("(?:(x|)+?)*", "xxx"))
            cases.add(Arguments.of("((x?)+?)*", "xxx"))
            cases.add(Arguments.of("(?:(?:x|)+?)*", ""))
            cases.add(Arguments.of("(?:(?:x|)+?)*", "!xx!xxx"))
            // Empty paths must be revisited when a loop's progress state changes, including NOPs
            // and assertions shared by a consuming iteration and the final zero-width iteration.
            for (empty in mutableListOf<String>("", "\\b", "\\B", "\\b\\b", "(?:)")) {
                for (quantifier in mutableListOf<String>("+", "*", "+?", "{1,3}")) {
                    for (atom in mutableListOf<String>("a", "é", "😀")) {
                        val regex = "(?:" + atom + "?(?:" + empty + "|.))" + quantifier
                        for (input in java.util.List.of<String?>(atom + "!" + atom + "!", "!" + atom + "!", atom!!.repeat(3))) {
                            cases.add(Arguments.of(regex, input))
                            cases.add(Arguments.of(regex + "!", input))
                        }
                    }
                }
            }
            return cases.stream()
        }

        @JvmStatic
        private fun nestedNullableEpsilonCases(): Stream<String?> {
            val cases: MutableList<String?> = ArrayList<String?>()
            for (body in mutableListOf<String>("(?:a?(?:|.))", "(?:a?(?:\\b|.))")) {
                for (firstQuantifier in mutableListOf<String>("+", "+?")) {
                    var regex: String = body!!
                    var quantifier: String = firstQuantifier!!
                    for (depth in 1..7) {
                        regex = "(?:" + regex + ")" + quantifier
                        cases.add(regex)
                        quantifier = if (quantifier == "+") "+?" else "+"
                    }
                }
            }
            return cases.stream()
        }

        private fun assertGroupsMatch(jdk: java.util.regex.Matcher, safere: Matcher) {
            for (group in 0..jdk.groupCount()) {
                Assertions.assertThat(safere.group(group)).isEqualTo(jdk.group(group))
                Assertions.assertThat(safere.start(group)).isEqualTo(jdk.start(group))
                Assertions.assertThat(safere.end(group)).isEqualTo(jdk.end(group))
            }
        }

        private fun byteOffset(input: String, index: Int): Int {
            return if (index < 0) -1 else input.substring(0, index).toByteArray(StandardCharsets.UTF_8).size
        }
    }
}
