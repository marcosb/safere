// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
import java.util.function.Function
import java.util.regex.MatchResult

/** Failed inner candidates must be rejected before scanning the leading repetition backward.  */
@DisabledForCrosscheck("uses SafeRE work counters and diagnostics")
@Tag("work-counter")
@Isolated
@Execution(
    ExecutionMode.SAME_THREAD
)
internal class LeadingExpansionVerificationWorkTest {
    @AfterEach
    fun disableDiagnostics() {
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun failedInnerCandidatesCostComparableWorkAcrossEntryPoints(diagnostics: Boolean) {
        Pattern.setDiagnostics(
            if (diagnostics) object : SafeReMatchDiagnostics() {} else SafeReMatchDiagnostics.NONE)
        val failures: MutableList<String> = ArrayList<String>()
        for (regex in mutableListOf<String>("\\s*# noqa[0-9]", "\\s*# noqa\\b", "[é😀]*# noqa[0-9]")) {
            val pattern = Pattern.compile(regex)
            for (size in intArrayOf(2000, 20000)) {
                val input = (if (regex!!.startsWith("[é")) "é😀" else " ").repeat(size) + "# noqaX"
                val utf8 = Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8))
                val operations: MutableMap<String?, (() -> Any?)?> =
                    LinkedHashMap<String?, (() -> Any?)?>()
                operations.put(
                    "String find",
                    { Assertions.assertThat(pattern.matcher(input).find()).isFalse() })
                operations.put("UTF-8 find", { Assertions.assertThat(pattern.matcher(utf8).find()).isFalse() })
                operations.put("matcher-free find", { Assertions.assertThat(pattern.find(utf8)).isFalse() })
                operations.put(
                    "replaceAll",
                    { Assertions.assertThat(pattern.matcher(input).replaceAll("X")).isEqualTo(input) })
                operations.put(
                    "replaceFirst",
                    { Assertions.assertThat(pattern.matcher(input).replaceFirst("X")).isEqualTo(input) })
                operations.put(
                    "function replacement",
                    {
                        Assertions.assertThat(
                            pattern.matcher(input).replaceAll(Function { m: MatchResult? -> "X" })
                        ).isEqualTo(input)
                    })
                operations.put(
                    "split",
                    { Assertions.assertThat<String?>(pattern.split(input)).containsExactly(input) })
                val reference: Long = work(operations.get("String find")!!)
                operations.forEach { (name: String?, operation: (() -> Any?)?) ->
                    val actual: Long = work(operation!!)
                    if (actual > reference * 2 + 100) {
                        failures.add(
                            String.format(
                                "%s: %s at %s repetitions: %s units versus %s for find()",
                                regex, name, size, actual, reference
                            )
                        )
                    }
                }
            }
        }
        Assertions.assertThat<String?>(failures).`as`("diagnostics=%s", diagnostics).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun failedInnerCandidatesAfterSuccessfulReplacementHaveTheSameBound(diagnostics: Boolean) {
        Pattern.setDiagnostics(
            if (diagnostics) object : SafeReMatchDiagnostics() {} else SafeReMatchDiagnostics.NONE)
        val pattern = Pattern.compile("\\s*# noqa[0-9]")
        for (size in intArrayOf(2000, 20000)) {
            val input = "# noqa1;" + " ".repeat(size) + "# noqaX"
            val expected = "X;" + " ".repeat(size) + "# noqaX"
            val reference: Long =
                work({
                    Assertions.assertThat(
                        pattern.matcher(input).replaceAll(Function { m: MatchResult? -> "X" })
                    ).isEqualTo(expected)
                })
            val actual: Long =
                work({ Assertions.assertThat(pattern.matcher(input).replaceAll("X")).isEqualTo(expected) })
            Assertions.assertThat(actual)
                .`as`("%s leading spaces after a match, diagnostics=%s", size, diagnostics)
                .isLessThanOrEqualTo(reference * 2 + 100)
        }
    }

    companion object {
        private fun work(operation: () -> Any?): Long {
            operation()
            return WorkCounter.countForTesting(operation)
        }
    }
}
