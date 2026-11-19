// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
import java.util.List

/** Guarded gaps allow a sparse required literal to bypass irrelevant dense start candidates.  */
@DisabledForCrosscheck("WorkCounter is an internal SafeRE API")
@Tag("work-counter")
internal class InnerLiteralSearchWorkTest {
    @JvmRecord
    private data class Scenario(val regex: String?, val noise: String?, val match: String)

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun forbiddenGapCharactersExcludeEarlierStartCandidates(utf8: Boolean) {
        for (scenario in List.of<Scenario?>(
            Scenario("record:[^;\\r\\n]*val=200", "record:x;\n", "record:target_val=200"),
            Scenario("(record:)([^;]*?)(val=200)", "record:x;", "record:target_val=200"),
            Scenario("key=[a-z_]*done", "key=x;", "key=target_done"),
            Scenario("clé:[^;]*fin", "clé:x;", "clé:é😀fin"),
            Scenario("record:[^😀]*val=200", "record:x😀", "record:target_val=200")
        )) {
            val pattern = Pattern.compile(scenario.regex)
            val input = scenario.noise!!.repeat(4096) + scenario.match
            val work: Long = findWork(pattern, input, scenario.match, utf8)
            val forward =
                Pattern.compile(
                    scenario.regex, 0, EnginePathOptions.builder().literalFastPaths(false).build()
                )
            Assertions.assertThat(findWork(forward, input, scenario.match, utf8))
                .`as`("The control must exercise a forward scan")
                .isGreaterThan(input.length / 4L)
            Assertions.assertThat(work)
                .`as`("/ %s / must bypass dense candidates before the final guarded gap", scenario.regex)
                .isLessThan(input.length / 16L)
        }
    }

    @Test
    fun eligibleSearchesScanOnlyOneUnboundedLiteralRange() {
        val pattern = Pattern.compile("record:[^;]*val=200")
        for (noise in mutableListOf<String?>("record:x;", "other:x;")) {
            val input = noise!!.repeat(2048) + "record:target_val=200"
            for (search in List.of<Runnable?>(
                Runnable { Assertions.assertThat(pattern.matcher(input).find()).isTrue() },
                Runnable { Assertions.assertThat(pattern.matcher(input).replaceAll("X")).endsWith("X") },
                Runnable { Assertions.assertThat<String?>(pattern.split(input, -1)).hasSize(2) })) {
                val work = WorkCounter.countForTesting(search)
                Assertions.assertThat(work).`as`("noise=%s", noise).isLessThan(input.length + 1024L)
            }
        }
    }

    @Test
    fun repeatedSuffixesAndBulkSearchesKeepWorkLinear() {
        val pattern = Pattern.compile("record:[^;]*val=200")
        for (count in intArrayOf(1024, 2048)) {
            for (unit in mutableListOf<String?>("record:x;val=200;", "record:xval=200;")) {
                val input = unit!!.repeat(count) + "record:target_val=200"
                for (search in List.of<Runnable?>(
                    Runnable {
                        val matcher = pattern.matcher(input)
                        while (matcher.find()) {
                            Assertions.assertThat(matcher.end()).isGreaterThan(matcher.start())
                        }
                    },
                    Runnable { Assertions.assertThat(pattern.matcher(input).replaceAll("X")).isNotNull() },
                    Runnable { Assertions.assertThat<String?>(pattern.split(input, -1)).isNotNull() })) {
                    val work = WorkCounter.countWithForwardDfaStepsForTesting(search)
                    Assertions.assertThat(work)
                        .`as`("count=%s, unit=%s", count, unit)
                        .isLessThan(16L * input.length + 4096)
                }
            }
        }
    }

    companion object {
        private fun findWork(pattern: Pattern, input: String, match: String, utf8: Boolean): Long {
            return WorkCounter.countForwardDfaStepsForTesting(
                Runnable {
                    if (utf8) {
                        val matcher = pattern.matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
                        Assertions.assertThat(matcher.find()).isTrue()
                        Assertions.assertThat(matcher.start())
                            .isEqualTo(
                                input.toByteArray(StandardCharsets.UTF_8).size - match.toByteArray(
                                    StandardCharsets.UTF_8
                                ).size
                            )
                    } else {
                        val matcher = pattern.matcher(input)
                        Assertions.assertThat(matcher.find()).isTrue()
                        Assertions.assertThat(matcher.start()).isEqualTo(input.length - match.length)
                    }
                })
        }
    }
}
