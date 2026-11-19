// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

@DisabledForCrosscheck("Compares SafeRE and the JDK directly")
internal class InnerLiteralActivationTest {
    @Test
    fun repeatedNarrowingSurvivesCacheGrowthAndBudgetRecovery() {
        val suffix = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val regex = "record:[^;]*" + suffix
        val pattern = Pattern.compile(regex)
        val reference = java.util.regex.Pattern.compile(regex)
        val ast =
            Parser.parse(
                regex,
                (ParseFlags.PERL_X
                        or ParseFlags.PERL_CLASSES
                        or ParseFlags.PERL_B
                        or ParseFlags.UNICODE_GROUPS)
            )
        val plan = InnerLiteralSearch.compile(ast, pattern.rejectPrefilter())
        Assertions.assertThat<InnerLiteralSearch?>(plan).isNotNull()
        for (budget in intArrayOf(32, 48, 64)) {
            val dfa =
                Dfa(
                    pattern.flatDfaProg()!!,
                    budget,
                    Dfa.buildSetup(pattern.flatDfaProg()!!),
                    false,
                    pattern.utf8StartAccelerator(),
                    pattern.stringStartAccelerator(),
                    plan
                )
            var completedMatch = false
            for (pass in 0..3) {
                for (input in java.util.List.of<String?>(
                    "record:x;".repeat(256) + "record:" + "x".repeat(512) + suffix,
                    "record:x;".repeat(256),
                    "record:ok" + suffix,
                    "record:x;".repeat(256) + "record:ok" + suffix
                )) {
                    for (scanner in java.util.List.of<InputScanner?>(
                        StringInputScanner(input),
                        Utf8InputScanner(input.toByteArray(StandardCharsets.UTF_8))
                    )) {
                        val result = dfa.doSearch(scanner, 0, false, false)
                        if (result == null) {
                            Assertions.assertThat(dfa.lastSearchBailed()).isTrue()
                        } else {
                            Assertions.assertThat(result.matched).isEqualTo(reference.matcher(input).find())
                            completedMatch = completedMatch or result.matched
                        }
                    }
                }
            }
            Assertions.assertThat(completedMatch).`as`("state budget=%d", budget).isTrue()
        }
    }

    @Test
    fun mixedDenseAndFailedCandidatesPreserveEveryResult() {
        for (regex in mutableListOf<String>(
            "record:[^;\\r\\n]*val=200",
            "record:.*val=200",
            "record:[^;]{0,64}val=200",
            "\\brecord:[^;]*val=200",
            "(?m:^record:[^;\\r\\n]*val=200$)",
            "(record:)([^;]*?)(val=200)",
            "pre:[^;]*;done",
            "line:[^\\r\\n]*done",
            "clé:[^;]*fin",
            "😀[^;]*終"
        )) {
            val pattern = Pattern.compile(regex)
            val reference = java.util.regex.Pattern.compile(regex)
            for (body in java.util.List.of<String?>(
                "record:ok_val=200;".repeat(32) + "record:x;".repeat(128) + "record:target_val=200;",
                ("record:" + "x".repeat(128) + ";").repeat(128) + "record:target_val=200",
                "record:x;\n".repeat(128) + "record:target_val=200\n",
                "pre:x;bad;".repeat(128) + "pre:target;done",
                "line:x\n".repeat(128) + "line:target_done",
                "clé:x;".repeat(128) + "clé:é😀fin;clé:fin",
                "😀x;".repeat(128) + "😀é終;😀終",
                "record:" + "x".repeat(1024) + "val=200;record:val=200"
            )) {
                for (padding in intArrayOf(0, 1)) {
                    val input = if (padding == 0) body else "«" + body + "»"
                    val actual = pattern.matcher(input).region(padding, input.length - padding)
                    val expected =
                        reference.matcher(input).region(padding, input.length - padding)
                    val bytes = pattern.matcher(Utf8Input.validated(body.toByteArray(StandardCharsets.UTF_8)))
                    while (expected.find()) {
                        Assertions.assertThat(actual.find()).isTrue()
                        Assertions.assertThat(bytes.find()).isTrue()
                        for (group in 0..expected.groupCount()) {
                            Assertions.assertThat(actual.group(group)).isEqualTo(expected.group(group))
                            Assertions.assertThat(actual.start(group)).isEqualTo(expected.start(group))
                            Assertions.assertThat(actual.end(group)).isEqualTo(expected.end(group))
                            val start = expected.start(group) - padding
                            val end = expected.end(group) - padding
                            Assertions.assertThat(bytes.start(group))
                                .isEqualTo(body.substring(0, start).toByteArray(StandardCharsets.UTF_8).size)
                            Assertions.assertThat(bytes.end(group))
                                .isEqualTo(body.substring(0, end).toByteArray(StandardCharsets.UTF_8).size)
                        }
                    }
                    Assertions.assertThat(actual.find()).isFalse()
                    Assertions.assertThat(bytes.find()).isFalse()
                }
                Assertions.assertThat(pattern.matcher(body).replaceAll("X"))
                    .isEqualTo(reference.matcher(body).replaceAll("X"))
                Assertions.assertThat<String?>(pattern.split(body, -1)).containsExactly(*reference.split(body, -1))
            }
        }
    }
}
