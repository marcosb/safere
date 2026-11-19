// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.safere.Matcher.*
import org.safere.StateAccelerator.SingleAsciiEscape
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

@DisabledForCrosscheck("ShiftDfa is an internal SafeRE engine")
internal class ShiftDfaTest {
    @Test
    @DisplayName("simple literals and short tokens")
    fun simpleLiteralsAndShortTokens() {
        val dfa: ShiftDfa = compile("abc")
        Assertions.assertThat<ShiftDfa?>(dfa).isNotNull()
        Assertions.assertThat(dfa.numStates()).isEqualTo(4)
        Assertions.assertThat(dfa.matches("abc", 0, 3)).isTrue()
        Assertions.assertThat(dfa.matches("ab", 0, 2)).isFalse()
        Assertions.assertThat(dfa.matches("abcd", 0, 4)).isFalse()
        Assertions.assertThat(dfa.matches("axc", 0, 3)).isFalse()

        val bytes = "abc".toByteArray(StandardCharsets.UTF_8)
        val scanner = Utf8InputScanner(bytes, 0, bytes.size)
        Assertions.assertThat(dfa.matches(scanner, 0, 3)).isTrue()
    }

    @Test
    @DisplayName("json boolean and null tokens")
    fun jsonBooleanAndNullTokens() {
        val boolDfa: ShiftDfa = compile("true|false")
        Assertions.assertThat<ShiftDfa?>(boolDfa).isNotNull()
        Assertions.assertThat(boolDfa.numStates()).isLessThanOrEqualTo(10)
        Assertions.assertThat(boolDfa.matches("true", 0, 4)).isTrue()
        Assertions.assertThat(boolDfa.matches("false", 0, 5)).isTrue()
        Assertions.assertThat(boolDfa.matches("tru", 0, 3)).isFalse()
        Assertions.assertThat(boolDfa.matches("truee", 0, 5)).isFalse()
        Assertions.assertThat(boolDfa.matches("null", 0, 4)).isFalse()

        val nullDfa: ShiftDfa = compile("null")
        Assertions.assertThat<ShiftDfa?>(nullDfa).isNotNull()
        Assertions.assertThat(nullDfa.numStates()).isEqualTo(5)
        Assertions.assertThat(nullDfa.matches("null", 0, 4)).isTrue()
        Assertions.assertThat(nullDfa.matches("nul", 0, 3)).isFalse()
    }

    @Test
    @DisplayName("UTF-8 matching honors the scanner backing-array offset")
    fun utf8MatchingHonorsScannerOffset() {
        val dfa: ShiftDfa = compile("true|false")
        val storage = "xxxxfalseyyyy".toByteArray(StandardCharsets.UTF_8)
        val scanner = Utf8InputScanner(storage, 4, 5)

        Assertions.assertThat(dfa.matches(scanner, 0, scanner.length())).isTrue()
    }

    @Test
    @DisplayName("boolean-token benchmark pattern selects ShiftDfa")
    fun booleanTokenBenchmarkPatternSelectsShiftDfa() {
        val pattern = Pattern.compile("(?:true|false) ?")

        Assertions.assertThat<PreparedMatchRunner?>(pattern.preparedMatchRunner(false))
            .isInstanceOf(LazyShiftDfaPreparedRunner::class.java)
        Assertions.assertThat(pattern.matcher("true ").matches()).isTrue()
        Assertions.assertThat<PreparedMatchRunner?>(pattern.preparedMatchRunner(false))
            .isInstanceOf(ShiftDfaPreparedRunner::class.java)
    }

    @Test
    @DisplayName("quantified digits and repetition ranges")
    fun quantifiedDigitsAndRepetitions() {
        val dfa: ShiftDfa = compile("[0-9]{1,3}")
        Assertions.assertThat<ShiftDfa?>(dfa).isNotNull()
        Assertions.assertThat(dfa.numStates()).isLessThanOrEqualTo(10)

        Assertions.assertThat(dfa.matches("1", 0, 1)).isTrue()
        Assertions.assertThat(dfa.matches("12", 0, 2)).isTrue()
        Assertions.assertThat(dfa.matches("123", 0, 3)).isTrue()
        Assertions.assertThat(dfa.matches("1234", 0, 4)).isFalse()
        Assertions.assertThat(dfa.matches("", 0, 0)).isFalse()
        Assertions.assertThat(dfa.matches("a", 0, 1)).isFalse()
    }

    @Test
    @DisplayName("c-style identifiers")
    fun identifiers() {
        val dfa: ShiftDfa = compile("[a-zA-Z_][a-zA-Z0-9_]*")
        Assertions.assertThat<ShiftDfa?>(dfa).isNotNull()
        Assertions.assertThat(dfa.numStates()).isLessThanOrEqualTo(10)

        Assertions.assertThat(dfa.matches("foo", 0, 3)).isTrue()
        Assertions.assertThat(dfa.matches("_var123", 0, 7)).isTrue()
        Assertions.assertThat(dfa.matches("123var", 0, 6)).isFalse()
        Assertions.assertThat(dfa.matches("", 0, 0)).isFalse()
    }

    @Test
    @DisplayName("year-month date pattern")
    fun yearMonthDatePattern() {
        val dfa: ShiftDfa = compile("[0-9]{4}-[0-9]{2}")
        Assertions.assertThat<ShiftDfa?>(dfa).isNotNull()
        Assertions.assertThat(dfa.numStates()).isLessThanOrEqualTo(10)

        Assertions.assertThat(dfa.matches("2026-08", 0, 7)).isTrue()
        Assertions.assertThat(dfa.matches("1999-12", 0, 7)).isTrue()
        Assertions.assertThat(dfa.matches("2026-8", 0, 6)).isFalse()
        Assertions.assertThat(dfa.matches("2026/08", 0, 7)).isFalse()
    }

    @Test
    @DisplayName("self-loop vector acceleration via StateAccelerator")
    fun stateAcceleratorSelfLoop() {
        val dfa: ShiftDfa = compile("[\\x00-\\x21\\x23-\\x7F]*\"")
        Assertions.assertThat<ShiftDfa?>(dfa).isNotNull()
        Assertions.assertThat<StateAccelerator?>(dfa.accelerators()).isNotNull()

        val longInput = "a".repeat(1000) + "\""
        Assertions.assertThat(dfa.matches(longInput, 0, longInput.length)).isTrue()

        val longNonMatching = "a".repeat(1000) + "x"
        Assertions.assertThat(dfa.matches(longNonMatching, 0, longNonMatching.length)).isFalse()
    }

    @Test
    @DisplayName("non-ASCII boundaries are specific to ASCII-only acceleration")
    fun nonAsciiBoundariesAreSpecificToAsciiOnlyAcceleration() {
        val accelerator: StateAccelerator = SingleAsciiEscape('"'.code)
        val scanner = StringInputScanner("aé\"")

        Assertions.assertThat(StateAccelerator.findNextEscape(accelerator, scanner, 0, scanner.length()))
            .isEqualTo(2)
        Assertions.assertThat(
            StateAccelerator.findNextAsciiOrNonAsciiEscape(
                accelerator, scanner, 0, scanner.length()
            )
        )
            .isEqualTo(1)
    }

    @Test
    @DisplayName("state acceleration stops at non-ASCII dead transitions")
    fun stateAccelerationStopsAtNonAsciiDeadTransitions() {
        val pattern = Pattern.compile("[\\x00-\\x21\\x23-\\x7F]*\"")
        for (nonAscii in mutableListOf<String?>("é", "Ω", "😀")) {
            for (prefixLength in intArrayOf(0, 15, 16, 20, 64)) {
                val input = "a".repeat(prefixLength) + nonAscii + "\""
                Assertions.assertThat(pattern.matcher(input).matches())
                    .`as`("String prefix=%s nonAscii=%s", prefixLength, nonAscii)
                    .isFalse()
                Assertions.assertThat(
                    pattern.matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8))).matches()
                )
                    .`as`("UTF-8 prefix=%s nonAscii=%s", prefixLength, nonAscii)
                    .isFalse()
            }
        }
    }

    @Test
    @DisplayName("rejects excessive state count (> 10 states)")
    fun rejectsExcessiveStates() {
        // 15 linear states
        val longPattern = "a".repeat(15)
        val dfa: ShiftDfa = compile(longPattern)
        Assertions.assertThat<ShiftDfa?>(dfa).isNull()
    }

    @Test
    fun asciiPartitionsPreserveEveryShortInput() {
        // Exercise overlapping classes, both bitmap words, epsilon cycles, and saved DFA frontiers.
        for (regex in mutableListOf<String?>(
            "[\\x00-\\x7f]{0,2}",
            "[\\x00-\\x3f]*[\\x40-\\x7f]?",
            "[\\x00\\x3f\\x40\\x7f]+",
            "(?:[a-f]|[d-j])*[h-m]?",
            "(?:a?|b?)*c?",
            "(?:ab|ba|a)*",
            "(?:[A-Z_][a-z0-9]*|[a-z]+)",
            "[a-c]*?[b-d]?"
        )) {
            val dfa: ShiftDfa = compile(regex)
            Assertions.assertThat<ShiftDfa?>(dfa).`as`(regex).isNotNull()
            val expected = Pattern.compile(regex)
            assertSameMatch(dfa, expected, "")
            for (first in 0..127) {
                assertSameMatch(dfa, expected, Character.toString(first))
                for (second in 0..127) {
                    assertSameMatch(dfa, expected, "" + first.toChar() + second.toChar())
                }
            }
            for (input in mutableListOf<String?>("abcabc", "bababa", "Az09", "\u0080", "é", "😀")) {
                Companion.assertSameMatch(dfa, expected, input!!)
            }
        }
    }

    @Test
    fun stateBudgetIncludesExactlyTenStates() {
        val dfa: ShiftDfa = compile("a".repeat(9))
        Assertions.assertThat<ShiftDfa?>(dfa).isNotNull()
        Assertions.assertThat(dfa.numStates()).isEqualTo(ShiftDfa.MAX_STATES)
        Assertions.assertThat(dfa.matches("a".repeat(9), 0, 9)).isTrue()
        Assertions.assertThat<ShiftDfa?>(compile("a".repeat(10))).isNull()
    }

    @Test
    fun caseSensitiveLiteralSupersedesShiftDfaOnlyWhenEnabled() {
        val literal = org.safere.Pattern.compile("hello")
        Assertions.assertThat(literal.shiftDfaAnalyzed()).isFalse()
        Assertions.assertThat<ShiftDfa?>(literal.shiftDfa()).isNull()
        Assertions.assertThat<PreparedMatchRunner?>(literal.preparedMatchRunner(false))
            .isInstanceOf(LiteralPreparedRunner::class.java)
        Assertions.assertThat<PreparedMatchRunner?>(literal.preparedMatchRunner(true))
            .isInstanceOf(LiteralPreparedRunner::class.java)

        val forcedShift =
            org.safere.Pattern.compile("hello", 0, EnginePathOptions.builder().literalFastPaths(false).build())
        Assertions.assertThat(forcedShift.shiftDfaAnalyzed()).isFalse()
        Assertions.assertThat<PreparedMatchRunner?>(forcedShift.preparedMatchRunner(false))
            .isInstanceOf(LazyShiftDfaPreparedRunner::class.java)
        Assertions.assertThat<ShiftDfa?>(forcedShift.shiftDfa()).isNotNull()
        Assertions.assertThat(forcedShift.shiftDfaAnalyzed()).isTrue()
    }

    @Test
    fun foldedLiteralRetainsUtf8Fallback() {
        val folded = org.safere.Pattern.compile("hello", org.safere.Pattern.CASE_INSENSITIVE)
        Assertions.assertThat(folded.shiftDfaAnalyzed()).isFalse()
        Assertions.assertThat(folded.matcher(Utf8Input.trusted("HELLO".toByteArray(StandardCharsets.UTF_8))).matches())
            .isTrue()
        Assertions.assertThat(folded.shiftDfaAnalyzed()).isTrue()
        Assertions.assertThat<ShiftDfa?>(folded.shiftDfa()).isNotNull()
    }

    @Test
    fun disabledShiftDfaIsNotConstructed() {
        val disabled =
            org.safere.Pattern.compile("(?:true|false) ?", 0, EnginePathOptions.builder().shiftDfa(false).build())
        Assertions.assertThat<ShiftDfa?>(disabled.shiftDfa()).isNull()
    }

    @Test
    fun unsupportedInstructionsStillRejectConstruction() {
        for (regex in mutableListOf<String?>("a\\b", "a\\p{L}", "\\X", "ab$", "(ab)")) {
            Assertions.assertThat<ShiftDfa?>(compile(regex)).`as`(regex).isNull()
        }
    }

    @Test
    @DisplayName("full pattern matcher integration")
    fun patternMatcherIntegration() {
        val pattern = org.safere.Pattern.compile("true|false")
        val matcher = pattern.matcher("true")
        Assertions.assertThat(matcher.matches()).isTrue()

        val matcherFalse = pattern.matcher("false")
        Assertions.assertThat(matcherFalse.matches()).isTrue()

        val matcherOther = pattern.matcher("other")
        Assertions.assertThat(matcherOther.matches()).isFalse()
    }

    @Test
    fun shiftDfaIsCompiledLazilyOnlyOnMatches() {
        val pattern = org.safere.Pattern.compile("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,4}")
        Assertions.assertThat(pattern.shiftDfaAnalyzed()).isFalse()

        val matcher = pattern.matcher("contact user@example.com for details")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(pattern.shiftDfaAnalyzed()).isFalse()

        Assertions.assertThat(matcher.reset("user@example.com rest").lookingAt()).isTrue()
        Assertions.assertThat(pattern.shiftDfaAnalyzed()).isFalse()

        Assertions.assertThat(matcher.reset("user@example.com").matches()).isTrue()
        Assertions.assertThat(pattern.shiftDfaAnalyzed()).isTrue()
        Assertions.assertThat<ShiftDfa?>(pattern.shiftDfa()).isNotNull()
    }

    @Test
    fun shiftDfaCachesDisabledWhenStateBudgetExceededOnMatches() {
        val pattern = org.safere.Pattern.compile("a{9}b?c?")
        Assertions.assertThat(pattern.shiftDfaAnalyzed()).isFalse()
        Assertions.assertThat<PreparedMatchRunner?>(pattern.preparedMatchRunner(false))
            .isInstanceOf(LazyShiftDfaPreparedRunner::class.java)

        Assertions.assertThat(pattern.matcher("aaaaaaaaabc").matches()).isTrue()
        Assertions.assertThat(pattern.shiftDfaAnalyzed()).isTrue()
        Assertions.assertThat<ShiftDfa?>(pattern.shiftDfa()).isNull()
        // The resolved fallback replaces the lazy runner, so later calls skip the wrapper.
        Assertions.assertThat<PreparedMatchRunner?>(pattern.preparedMatchRunner(false))
            .isInstanceOf(OnePassAnchoredPreparedRunner::class.java)
        Assertions.assertThat(pattern.matcher("aaaaaaaaax").matches()).isFalse()
    }

    @Test
    fun minMatchLengthAtLeastMaxStatesSkipsShiftDfaRunnerWithoutDeterminization() {
        val pattern = org.safere.Pattern.compile("a{10}b*")
        Assertions.assertThat(pattern.shiftDfaAnalyzed()).isFalse()
        Assertions.assertThat<PreparedMatchRunner?>(pattern.preparedMatchRunner(false))
            .isNotInstanceOf(ShiftDfaPreparedRunner::class.java)
        Assertions.assertThat(pattern.shiftDfaAnalyzed()).isFalse()
    }

    companion object {
        private fun compile(regex: String?, flags: Int): ShiftDfa? {
            val ast = Parser.parse(regex, flags)
            val prog = Compiler.compile(ast)
            return ShiftDfa.compile(prog)
        }

        private fun compile(regex: String?): ShiftDfa {
            return compile(regex, ParseFlags.LIKE_PERL)!!
        }

        private fun assertSameMatch(
            dfa: ShiftDfa, expected: Pattern, input: String
        ) {
            val matched = expected.matcher(input).matches()
            Assertions.assertThat(dfa.matches(input, 0, input.length)).`as`("String %s", expected).isEqualTo(matched)
            val bytes = input.toByteArray(StandardCharsets.UTF_8)
            val scanner = Utf8InputScanner(bytes, 0, bytes.size)
            Assertions.assertThat(dfa.matches(scanner, 0, bytes.size)).`as`("UTF-8 %s", expected).isEqualTo(matched)
        }
    }
}
