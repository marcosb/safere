// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated

/**
 * Tests for the optimized no-match path in `Matcher.replaceAll` and `replaceFirst`.
 * 
 * 
 * Pins the contract that when an input does not match, replace operations:
 * 
 * 
 *  * Return the exact same String instance, without constructing the replacement template.
 *  * Do not evaluate or throw on replacement templates containing references or syntax errors.
 *  * Leave the Matcher in the expected observable state (throwing [IllegalStateException]
 * on `group()`, `start()`, etc.).
 *  * Emit exactly one operation event with start-acceleration diagnostics and no nested find
 * events.
 *  * Produce results strictly identical to [java.util.regex.Matcher].
 * 
 */
@DisabledForCrosscheck("match diagnostics are a SafeRE-specific public API")
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
internal class ReplaceNoMatchOverheadTest {
    private val diagnostics = RecordingDiagnostics()

    @AfterEach
    fun disableDiagnostics() {
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
    }

    @Test
    fun noMatchReturnsSameStringInstance() {
        val patterns =
            java.util.List.of<String?>(
                PAREN_SOURCE,
                BRACKET_SOURCE,
                BRACKET_TAG,
                "exactLiteralNeedle",
                "[0-9]+",
                "a+b",
                "^(?:foo|bar)$"
            )
        val noMatchInput = "The quick brown fox jumps over the lazy dog in normal prose."

        val replacements = mutableListOf<String>("", "replacement", "$1", "\${foo}", "\\n", "\\$", "\\\\")

        for (patternStr in patterns) {
            val pattern = Pattern.compile(patternStr)
            for (rep in replacements) {
                val matcherAll = pattern.matcher(noMatchInput)
                val resultAll = matcherAll.replaceAll(rep)
                Assertions.assertThat(resultAll)
                    .`as`("replaceAll on %s with %s", patternStr, rep)
                    .isSameAs(noMatchInput)

                val matcherFirst = pattern.matcher(noMatchInput)
                val resultFirst = matcherFirst.replaceFirst(rep)
                Assertions.assertThat(resultFirst)
                    .`as`("replaceFirst on %s with %s", patternStr, rep)
                    .isSameAs(noMatchInput)
            }
        }
    }

    @Test
    @Throws(ReflectiveOperationException::class)
    fun literalReplacementDoesNotMaterializeInputScanner() {
        val scanner = Matcher::class.java.getDeclaredField("textScanner")
        scanner.setAccessible(true)
        for (regex in mutableListOf<String>("hello", "é😀", "a\\.b", "(?i)hello")) {
            val pattern = Pattern.compile(regex)
            val jdk = java.util.regex.Pattern.compile(regex)
            for (input in mutableListOf<String>("", "absent", "hello hello", "é😀 é😀", "a.b a.b", "HELLO")) {
                for (replacement in mutableListOf<String>("X", "")) {
                    val all = pattern.matcher(input)
                    Assertions.assertThat(all.replaceAll(replacement))
                        .isEqualTo(jdk.matcher(input).replaceAll(replacement))
                    Assertions.assertThat<Any?>(scanner.get(all)).`as`("replaceAll scanner for /%s/", regex).isNull()
                    val first = pattern.matcher(input)
                    Assertions.assertThat(first.replaceFirst(replacement))
                        .isEqualTo(jdk.matcher(input).replaceFirst(replacement))
                    Assertions.assertThat<Any?>(scanner.get(first)).`as`("replaceFirst scanner for /%s/", regex)
                        .isNull()
                }
            }
        }
    }

    @Test
    fun invalidReplacementTemplateDoesNotThrowOnNoMatch() {
        val invalidReplacements: MutableList<String> =
            mutableListOf<String>("\\", "$", "$99", "\${invalid", "$<unclosed>")
        val input = "No match here at all."

        for (regex in java.util.List.of<String?>(PAREN_SOURCE, BRACKET_SOURCE, BRACKET_TAG, "needle", "[0-9]+")) {
            val safere = Pattern.compile(regex)
            val jdk = java.util.regex.Pattern.compile(regex)

            for (rep in invalidReplacements) {
                // SafeRE must not throw and must return same instance
                Assertions.assertThat(safere.matcher(input).replaceAll(rep)).isSameAs(input)
                Assertions.assertThat(safere.matcher(input).replaceFirst(rep)).isSameAs(input)

                // JDK also does not throw on no-match
                Assertions.assertThat(safere.matcher(input).replaceAll(rep))
                    .isEqualTo(jdk.matcher(input).replaceAll(rep))
                Assertions.assertThat(safere.matcher(input).replaceFirst(rep))
                    .isEqualTo(jdk.matcher(input).replaceFirst(rep))
            }
        }
    }

    @Test
    fun invalidReplacementTemplateThrowsOnMatchIdenticalToJdk() {
        val input = "prefix (Source: 42) suffix"
        val safere = Pattern.compile(PAREN_SOURCE)
        val jdk = java.util.regex.Pattern.compile(PAREN_SOURCE)

        // Trailing backslash
        Assertions.assertThatThrownBy(ThrowingCallable { safere.matcher(input).replaceAll("\\") })
            .isInstanceOf(IllegalArgumentException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { jdk.matcher(input).replaceAll("\\") })
            .isInstanceOf(IllegalArgumentException::class.java)

        // Lone dollar sign
        Assertions.assertThatThrownBy(ThrowingCallable { safere.matcher(input).replaceAll("$") })
            .isInstanceOf(IllegalArgumentException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { jdk.matcher(input).replaceAll("$") })
            .isInstanceOf(IllegalArgumentException::class.java)

        // Out-of-bounds group reference
        Assertions.assertThatThrownBy(ThrowingCallable { safere.matcher(input).replaceAll("$99") })
            .isInstanceOf(IndexOutOfBoundsException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { jdk.matcher(input).replaceAll("$99") })
            .isInstanceOf(IndexOutOfBoundsException::class.java)
    }

    @Test
    fun nullReplacementThrowsNullPointerExceptionEvenOnNoMatch() {
        val pattern = Pattern.compile(PAREN_SOURCE)
        val matcher = pattern.matcher("no match")

        Assertions.assertThatThrownBy(ThrowingCallable { matcher.replaceAll(null as String?) })
            .isInstanceOf(NullPointerException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.replaceFirst(null as String?) })
            .isInstanceOf(NullPointerException::class.java)
    }

    @Test
    fun matcherStateAfterNoMatchThrowsIllegalStateException() {
        for (regex in java.util.List.of<String?>(PAREN_SOURCE, "literalMatch", "[0-9]+")) {
            val pattern = Pattern.compile(regex)
            val matcher = pattern.matcher("no matching text here")

            matcher.replaceAll("X")
            Assertions.assertThatThrownBy(ThrowingCallable { matcher.group() })
                .isInstanceOf(IllegalStateException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { matcher.start() })
                .isInstanceOf(IllegalStateException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { matcher.end() })
                .isInstanceOf(IllegalStateException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { matcher.group(0) })
                .isInstanceOf(IllegalStateException::class.java)

            matcher.reset("no matching text here")
            matcher.replaceFirst("X")
            Assertions.assertThatThrownBy(ThrowingCallable { matcher.group() })
                .isInstanceOf(IllegalStateException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { matcher.start() })
                .isInstanceOf(IllegalStateException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { matcher.end() })
                .isInstanceOf(IllegalStateException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { matcher.group(0) })
                .isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun matcherStateAfterMatchMatchesJdkContract() {
        val pattern = Pattern.compile(PAREN_SOURCE)
        val input = "first (Source: 1) second (Source: 2) end"

        // replaceFirst leaves MATCHED
        val matcherFirst = pattern.matcher(input)
        val resFirst = matcherFirst.replaceFirst("X")
        Assertions.assertThat(resFirst).isEqualTo("firstX second (Source: 2) end")
        Assertions.assertThat(matcherFirst.group()).isEqualTo(" (Source: 1)")
        Assertions.assertThat(matcherFirst.start()).isEqualTo(5)
        Assertions.assertThat(matcherFirst.end()).isEqualTo(17)

        // replaceAll leaves no match
        val matcherAll = pattern.matcher(input)
        val resAll = matcherAll.replaceAll("X")
        Assertions.assertThat(resAll).isEqualTo("firstX secondX end")
        Assertions.assertThatThrownBy(ThrowingCallable { matcherAll.group() })
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun differentialCheckAgainstJdk() {
        val patterns =
            java.util.List.of<String?>(
                PAREN_SOURCE,
                BRACKET_SOURCE,
                BRACKET_TAG,
                "fox",
                "[0-9]+",
                "([a-z]+):([0-9]+)",
                "^(?:start|begin)",
                "(?m)^line"
            )

        val inputs: MutableList<String> = mutableListOf<String>(
            "",
            "The quick brown fox jumps over the lazy dog.",
            "test (Source: 123) and (Source: 456, 789) end",
            "[Source: 99] beginning only",
            " [editor note] here",
            "items: 123, 456, 789",
            "line1\nline2\nline3",
            "foo:10 bar:20 baz:30"
        )

        val replacements: MutableList<String> = mutableListOf<String>("", "REPLACED", "[$0]", "-")

        for (pat in patterns) {
            val jdk = java.util.regex.Pattern.compile(pat)
            val safere = Pattern.compile(pat)

            for (`in` in inputs) {
                for (rep in replacements) {
                    Assertions.assertThat(safere.matcher(`in`).replaceAll(rep))
                        .`as`("replaceAll %s on '%s' with '%s'", pat, `in`, rep)
                        .isEqualTo(jdk.matcher(`in`).replaceAll(rep))

                    Assertions.assertThat(safere.matcher(`in`).replaceFirst(rep))
                        .`as`("replaceFirst %s on '%s' with '%s'", pat, `in`, rep)
                        .isEqualTo(jdk.matcher(`in`).replaceFirst(rep))
                }
            }
        }
    }

    @Test
    fun diagnosticsEmitsSingleEventWithStartAccelerationAndNoNestedFind() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile(PAREN_SOURCE)

        // 1. No match replaceAll
        val noMatchInput = "The quick brown fox jumps over the lazy dog without any source."
        val result = pattern.matcher(noMatchInput).replaceAll("")
        Assertions.assertThat(result).isSameAs(noMatchInput)

        var ops = operationsFor(pattern)
        Assertions.assertThat<OperationDiagnostics?>(ops).hasSize(1)
        var op = ops.get(0)
        Assertions.assertThat<MatchOperation?>(op.operation).isEqualTo(MatchOperation.REPLACE_ALL)
        Assertions.assertThat(op.matchCount).isEqualTo(0)
        Assertions.assertThat<MatchStrategy?>(op.boundaryStrategy).isEqualTo(MatchStrategy.LITERAL)
        Assertions.assertThat<StrategyParticipation?>(op.auxiliaryStrategies)
            .contains(
                StrategyParticipation(MatchStrategy.LITERAL, StrategyRole.START_ACCELERATION)
            )

        // 2. Matching replaceAll
        diagnostics.operations.clear()
        val matchInput = "citation (Source: 123) and more text"
        val matchResult = pattern.matcher(matchInput).replaceAll("")
        Assertions.assertThat(matchResult).isEqualTo("citation and more text")

        ops = operationsFor(pattern)
        Assertions.assertThat<OperationDiagnostics?>(ops).hasSize(1)
        op = ops.get(0)
        Assertions.assertThat<MatchOperation?>(op.operation).isEqualTo(MatchOperation.REPLACE_ALL)
        Assertions.assertThat(op.matchCount).isEqualTo(1)
        // Boundary strategy was DFA
        Assertions.assertThat<MatchStrategy?>(op.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
    }

    private fun operationsFor(pattern: Pattern): MutableList<OperationDiagnostics> {
        val patternId = pattern.descriptor().patternId
        return diagnostics.operations.stream()
            .filter { event: OperationDiagnostics? -> event!!.pattern!!.patternId == patternId }
            .toList()
            .requireNoNulls()
            .toMutableList()
    }

    private class RecordingDiagnostics : SafeReMatchDiagnostics() {
        val compilations: MutableList<PatternCompiledEvent?> = ArrayList<PatternCompiledEvent?>()
        val operations: MutableList<OperationDiagnostics?> = ArrayList<OperationDiagnostics?>()

        override fun onPatternCompiled(event: PatternCompiledEvent?) {
            compilations.add(event)
        }

        override fun onOperationCompleted(event: OperationDiagnostics?) {
            operations.add(event)
        }
    }

    companion object {
        private const val PAREN_SOURCE = " ?\\([Ss]ource:\\s*\\d+(?:\\s*,\\s*(?:[Ss]ource:\\s*)?\\d+)*\\s*\\)"
        private const val BRACKET_SOURCE = " ?\\[[Ss]ource:\\s*\\d+(?:\\s*,\\s*(?:[Ss]ource:\\s*)?\\d+)*\\s*\\]"
        private const val BRACKET_TAG = "\\s?(?i)\\[(?:editor note|reviewer comment|margin note)\\]"
    }
}
