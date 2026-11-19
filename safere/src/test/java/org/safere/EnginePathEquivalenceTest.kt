// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.function.Function
import java.util.regex.MatchResult
import kotlin.AssertionError
import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.arrayOf
import kotlin.booleanArrayOf
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableSet
import kotlin.collections.find
import kotlin.collections.mutableListOf
import kotlin.collections.plus
import kotlin.plus
import kotlin.sequences.find
import kotlin.sequences.plus
import kotlin.text.StringBuilder
import kotlin.text.find
import kotlin.text.repeat
import kotlin.text.replace
import kotlin.text.toByteArray

/** Forced-path equivalence coverage for package-private engine-path controls.  */
@DisabledForCrosscheck("uses package-private engine-path controls to compare SafeRE internals")
internal class EnginePathEquivalenceTest {
    @Test
    @DisplayName("every forced engine path has a machine-readable contract")
    fun everyForcedEnginePathHasContract() {
        val contracted: MutableSet<EnginePath?> = EnumSet.noneOf<EnginePath?>(EnginePath::class.java)
        for (contract in EnginePathContract.all().filterNotNull()) {
            contracted.add(contract.path)
            Assertions.assertThat<ResultAuthority?>(contract.authorities).`as`("authorities for %s", contract.path)
                .isNotEmpty()
            if (contract.role != EnginePathRole.FILTER) {
                Assertions.assertThat<SemanticGuard?>(contract.guards).`as`("guards for %s", contract.path).isNotEmpty()
            }
        }

        Assertions.assertThat<EnginePath?>(contracted)
            .containsExactlyInAnyOrderElementsOf(EnginePathOptions.accessors().keys)
        Assertions.assertThat<EnginePath?>(EnginePathOptions.accessors().keys)
            .containsExactlyInAnyOrderElementsOf(EnumSet.allOf<EnginePath?>(EnginePath::class.java))
    }

    @Test
    @DisplayName("engine path roles constrain declared result authority")
    fun enginePathRolesConstrainDeclaredResultAuthority() {
        for (contract in EnginePathContract.all().filterNotNull()) {
            if (contract.role == EnginePathRole.FILTER) {
                Assertions.assertThat<ResultAuthority?>(contract.authorities)
                    .`as`("filter authorities for %s", contract.path)
                    .doesNotContain(
                        ResultAuthority.GROUP_ZERO,
                        ResultAuthority.CAPTURES,
                        ResultAuthority.DEFERRED_CAPTURES,
                        ResultAuthority.REPLACEMENT_RESULT
                    )
            }
            if (contract.role == EnginePathRole.PARTIAL_PRODUCER) {
                Assertions.assertThat<ResultAuthority?>(contract.authorities)
                    .`as`("partial producer authorities for %s", contract.path)
                    .doesNotContain(ResultAuthority.CAPTURES, ResultAuthority.REPLACEMENT_RESULT)
            }
        }
    }

    @Test
    @DisplayName("engine path options disable only their declared path")
    fun enginePathOptionsDisableDeclaredPath() {
        val allEnabled = EnginePathOptions.allEnabled()
        for (entry in EnginePathOptions.accessors().entries) {
            Assertions.assertThat(entry.value.enabled(allEnabled))
                .`as`("default option for %s", entry.key)
                .isTrue()
        }
    }

    @Test
    @DisplayName("DFA sandwich reports ambiguous reverse starts")
    fun dfaSandwichReportsAmbiguousReverseStarts() {
        val regex = "(?:\\B{1}|a)."
        val input = "ab"
        val canonical = Pattern.compile(regex)
        val unguarded =
            Pattern.compile(
                regex, 0, EnginePathOptions.builder().onePass(false).bitState(false).build()
            )

        Assertions.assertThat<MatchTrace?>(findTrace(unguarded.matcher(input)))
            .`as`("DFA sandwich should not publish ambiguous reverse-DFA starts")
            .isEqualTo(findTrace(canonical.matcher(input)))
    }

    @Test
    @DisplayName("unguarded DFA paths preserve repeated $ find trace")
    fun unguardedDfaPathsPreserveRepeatedDollarFindTrace() {
        assertUnguardedDfaFindEquivalent("$", "x".repeat(2000) + "a\n")
        assertUnguardedDfaFindEquivalent("$", "x".repeat(2000) + "a\r\n")
        assertUnguardedDfaFindEquivalent("$", "x".repeat(2000) + "a\u2028")
    }

    @Test
    @DisplayName("unguarded DFA paths fall back for ambiguous reverse starts")
    fun unguardedDfaPathsFallBackForAmbiguousReverseStarts() {
        assertUnguardedDfaFindEquivalent("(?:\\B{1}|a).a?", "ab".repeat(600) + "c")
        assertUnguardedDfaFindEquivalent("(?:\\B{1}|a).a?$", "x".repeat(1100) + "ab")
    }

    @Test
    @DisplayName("unguarded DFA paths preserve end-anchored trailing terminator trace")
    fun unguardedDfaPathsPreserveEndAnchoredTrailingTerminatorTrace() {
        assertUnguardedDfaFindEquivalent("(?:a+?|(?:[^x])*)$", "x".repeat(1100) + "a\n")
    }

    @Test
    @DisplayName("unguarded DFA paths preserve boundary candidate priority")
    fun unguardedDfaPathsPreserveBoundaryCandidatePriority() {
        assertUnguardedDfaFindEquivalent("(?:a{2,}|(?:.|\\B){1,2}){1,2}", "baax")
    }

    @Test
    @DisplayName("literal fast paths match the canonical engine trace")
    fun literalFastPathsMatchCanonicalTrace() {
        for (regex in mutableListOf<String>("abc", "", "\\Qabc\\E", "(?:abc)")) {
            for (input in mutableListOf<String>("", "abc", "ab", "zzabcabc", "éabc😀")) {
                assertEquivalent(regex, input, EnginePathOptions.builder().literalFastPaths(false).build())
            }
        }
    }

    @Test
    @DisplayName("character-class replacement fast path matches canonical replacement")
    fun characterClassReplacementFastPathMatchesCanonicalReplacement() {
        val regex = "\\d+"
        val input = "a12b345c"
        val defaultPattern = Pattern.compile(regex)
        val canonicalPattern =
            Pattern.compile(
                regex, 0, EnginePathOptions.builder().charClassReplacementFastPath(false).build()
            )

        Assertions.assertThat(defaultPattern.matcher(input).replaceAll("X"))
            .isEqualTo(canonicalPattern.matcher(input).replaceAll("X"))
        assertEquivalent(
            regex,
            input,
            EnginePathOptions.builder()
                .charClassMatchFastPaths(false)
                .charClassReplacementFastPath(false)
                .build()
        )
    }

    @Test
    @DisplayName("start accelerators match the canonical engine trace")
    fun startAcceleratorsMatchCanonicalTrace() {
        assertEquivalent(
            "foo[0-9]+",
            "xxfoo123 yyfoo45",
            EnginePathOptions.builder().startAcceleration(false).build()
        )
    }

    @Test
    fun accelerationPreservesScalarAndBoundaryTracesAcrossRegions() {
        val unaccelerated = EnginePathOptions.builder().startAcceleration(false).build()
        for (scalar in mutableListOf<String>(
            "\\p{Cs}",
            "[\\uDC00-\\uDFFF]",
            "\\uDC4D",
            "\\p{So}",
            "\\P{L}",
            "[^\\p{Cs}]",
            ".",
            "[\\s\\S]",
            "\\x{1F44D}",
            "a"
        )) {
            for (regex in java.util.List.of<String?>(
                scalar + "\\b{g}",
                "(" + scalar + ")(\\b{g})",
                "\\b{g}(" + scalar + ")",
                "(?i:(" + scalar + "))\\b{g}",
                "(" + scalar + ")\\b{g}\\z",
                "(?:(" + scalar + ")+\\b{g})",
                "(?:(" + scalar + ")\\b{g}|())",
                "(?:(" + scalar + ")\\b{g}|(a))"
            )) {
                val accelerated = Pattern.compile(regex)
                val control = Pattern.compile(regex, 0, unaccelerated)
                for (input in mutableListOf<String>(
                    "\uD83D\uDC4D",
                    "a\uD83D\uDC4D\uD83D\uDC4Da",
                    "\uD83D\uDC4D\u0301\uD83D\uDC4D",
                    "\uD83D\uDC4D\uDC4Da\uD83D"
                )) {
                    for (start in 0..input!!.length) {
                        for (end in start..input.length) {
                            for (transparent in booleanArrayOf(false, true)) {
                                for (anchoring in booleanArrayOf(false, true)) {
                                    Assertions.assertThat<MatchTrace?>(
                                        Companion.regionTrace(
                                            accelerated,
                                            input,
                                            start,
                                            end,
                                            transparent,
                                            anchoring
                                        )
                                    )
                                        .`as`(
                                            "/%s/ region [%s,%s), transparent=%s, anchoring=%s",
                                            regex, start, end, transparent, anchoring
                                        )
                                        .isEqualTo(
                                            regionTrace(
                                                control,
                                                input,
                                                start,
                                                end,
                                                transparent,
                                                anchoring
                                            )
                                        )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun leadingExpansionPreservesExplicitStartsAndRegionTraces() {
        val unaccelerated = EnginePathOptions.builder().startAcceleration(false).build()
        for (scalar in mutableListOf<String>("\\uDC4D", "[\\uDC00-\\uDFFF]", "\\p{Cs}", ".")) {
            for (quantifier in mutableListOf<String>("*", "*?", "+", "+?", "?", "??", "{0,2}", "{1,2}?")) {
                for (regex in java.util.List.of<String?>(
                    scalar + quantifier + "[ab]\\b{g}",
                    "(" + scalar + ")" + quantifier + "([ab])\\b{g}",
                    "(" + scalar + quantifier + ")([ab])(\\b{g})"
                )) {
                    val accelerated = Pattern.compile(regex)
                    val control = Pattern.compile(regex, 0, unaccelerated)
                    for (input in mutableListOf<String>(
                        "\uD83D\uDC4Da",
                        "\uD83D\uDC4D\uDC4D\uDC4Da",
                        "\uDC4Da\uD83D\uDC4Db\uDC4Da\uD83D",
                        "\uD83D\uDC4Da\uD83D\uDC4Db",
                        "\uD83D\uDC4Da\u0301\uDC4Db"
                    )) {
                        for (start in 0..input!!.length) {
                            var fast = accelerated.matcher(input)
                            var slow = control.matcher(input)
                            Assertions.assertThat<MatchTrace?>(snapshot(fast, fast.find(start)))
                                .`as`("/%s/ find(%s) on %s", regex, start, input.chars().boxed().toList())
                                .isEqualTo(snapshot(slow, slow.find(start)))
                            Assertions.assertThat<MatchTrace?>(findTrace(fast)).isEqualTo(findTrace(slow))
                            if (start < input.length) {
                                fast = Pattern.compile("").matcher(input)
                                slow = Pattern.compile("").matcher(input)
                                Assertions.assertThat(fast.find(start)).isTrue()
                                Assertions.assertThat(slow.find(start)).isTrue()
                                fast.usePattern(accelerated)
                                slow.usePattern(control)
                                Assertions.assertThat<MatchTrace?>(findTrace(fast))
                                    .`as`("/%s/ after empty match at %s", regex, start)
                                    .isEqualTo(findTrace(slow))
                            }
                            for (end in start..input.length) {
                                for (transparent in booleanArrayOf(false, true)) {
                                    for (anchoring in booleanArrayOf(false, true)) {
                                        Assertions.assertThat<MatchTrace?>(
                                            Companion.regionTrace(
                                                accelerated,
                                                input,
                                                start,
                                                end,
                                                transparent,
                                                anchoring
                                            )
                                        )
                                            .`as`(
                                                "/%s/ region [%s,%s), transparent=%s, anchoring=%s",
                                                regex, start, end, transparent, anchoring
                                            )
                                            .isEqualTo(
                                                regionTrace(
                                                    control,
                                                    input,
                                                    start,
                                                    end,
                                                    transparent,
                                                    anchoring
                                                )
                                            )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("DFA-loop start acceleration preserves position-dependent state across inputs")
    fun dfaLoopStartAccelerationPreservesPositionDependentStateAcrossInputs() {
        val regex = "(b|(?m:^a))c[0-9]"
        for (lineTerminator in mutableListOf<String>("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029")) {
            val accelerated = Pattern.compile(regex)
            val control =
                Pattern.compile(regex, 0, EnginePathOptions.builder().startAcceleration(false).build())
            for (input in java.util.List.of<String?>(lineTerminator + "acz9y", " ycby ", "y0c\n1bac19c1__19x y_")) {
                val expected = java.util.regex.Pattern.compile(regex).matcher(input).find()
                val utf8 = Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))

                Assertions.assertThat(control.find(utf8)).`as`("control for %s", printable(input)).isEqualTo(expected)
                Assertions.assertThat(accelerated.find(utf8))
                    .`as`("accelerated search for %s", printable(input))
                    .isEqualTo(expected)
            }
        }

        val nonAsciiInput = "bxc\rca9\né\nac9é\r0x\r9a0"
        val accelerated = Pattern.compile(regex)
        val control =
            Pattern.compile(regex, 0, EnginePathOptions.builder().startAcceleration(false).build())
        val expected = java.util.regex.Pattern.compile(regex).matcher(nonAsciiInput).find()
        Assertions.assertThat(control.matcher(nonAsciiInput).find()).isEqualTo(expected)
        Assertions.assertThat(accelerated.matcher(nonAsciiInput).find()).isEqualTo(expected)
    }

    @Test
    @DisplayName("start-anchored fixed-offset plans preserve UTF-8 anchoring")
    fun startAnchoredFixedOffsetPlansPreserveUtf8Anchoring() {
        val lateCandidate = "x".repeat(300) + "1c"
        for (regex in mutableListOf<String>("\\A\\dc", "(?m:\\A\\d(?m:c))", "(\\A)[0-9]c")) {
            val accelerated = Pattern.compile(regex)
            val control =
                Pattern.compile(regex, 0, EnginePathOptions.builder().startAcceleration(false).build())

            Assertions.assertThat(
                accelerated.find(Utf8Input.validated(lateCandidate.toByteArray(StandardCharsets.UTF_8)))
            )
                .`as`("accelerated search for %s", regex)
                .isFalse()
            Assertions.assertThat(control.find(Utf8Input.validated(lateCandidate.toByteArray(StandardCharsets.UTF_8))))
                .`as`("control search for %s", regex)
                .isFalse()
            Assertions.assertThat(accelerated.find(Utf8Input.validated("1c".toByteArray(StandardCharsets.UTF_8))))
                .`as`("anchored match for %s", regex)
                .isTrue()
        }
    }

    @Test
    @DisplayName("disabled start acceleration is not installed in forward DFAs")
    fun disabledStartAccelerationIsNotInstalledInForwardDfas() {
        val pattern =
            Pattern.compile(
                "foo[0-9]+", 0, EnginePathOptions.builder().startAcceleration(false).build()
            )

        Assertions.assertThat(pattern.forwardFirstMatchDfa().hasStartAcceleration()).isFalse()
        Assertions.assertThat(pattern.forwardLongestMatchDfa().hasStartAcceleration()).isFalse()
    }

    @Test
    @DisplayName("OnePass paths match the canonical engine trace")
    fun onePassPathsMatchCanonicalTrace() {
        assertEquivalent(
            "^([A-Z]+):(\\d+)$", "ABC:123", EnginePathOptions.builder().onePass(false).build()
        )
    }

    @Test
    @DisplayName("DFA paths match the canonical engine trace")
    fun dfaPathsMatchCanonicalTrace() {
        assertEquivalent(
            "([a-z]+)([0-9]+)",
            "xxabc123yydef45",
            EnginePathOptions.builder().dfa(false).reverseDfa(false).build()
        )
    }

    @Test
    @DisplayName("forward-reverse DFA bounds match the capture-aware engine trace")
    fun forwardReverseDfaBoundsMatchCaptureAwareEngineTrace() {
        assertEquivalent(
            "\\b[a-z]+ing\\b",
            "walking past a thing while talking and singing",
            EnginePathOptions.builder().dfa(false).reverseDfa(false).build()
        )
    }

    @Test
    @DisplayName("BitState paths match the Pike NFA trace")
    fun bitStatePathsMatchPikeNfaTrace() {
        assertEquivalent(
            "(a|aa)*b",
            "aaaaab",
            EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build()
        )
    }

    @Test
    @DisplayName("lazy capture extraction matches eager capture extraction")
    fun lazyCaptureExtractionMatchesEagerCaptureExtraction() {
        assertEquivalent(
            "([a-z]+)([0-9]+)",
            "xxabc123yydef45",
            EnginePathOptions.builder().lazyCaptureExtraction(false).build()
        )
    }

    @Test
    @DisplayName("region traces are engine-path equivalent")
    fun regionTracesAreEnginePathEquivalent() {
        val forced =
            EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .startAcceleration(false)
                .onePass(false)
                .dfa(false)
                .bitState(false)
                .lazyCaptureExtraction(false)
                .build()
        val defaultPattern = Pattern.compile("^[a-z]+$")
        val forcedPattern = Pattern.compile("^[a-z]+$", 0, forced)
        val defaultMatcher = defaultPattern.matcher("00abc11").region(2, 5)
        val forcedMatcher = forcedPattern.matcher("00abc11").region(2, 5)

        Assertions.assertThat<MatchTrace?>(operationTrace(defaultMatcher, Operation.MATCHES))
            .isEqualTo(operationTrace(forcedMatcher, Operation.MATCHES))
    }

    @Test
    @DisplayName("transparent and anchoring bounds traces are engine-path equivalent")
    fun boundsTracesAreEnginePathEquivalent() {
        val forced = EnginePathOptions.builder().dfa(false).bitState(false).build()
        val defaultPattern = Pattern.compile("^abc$")
        val forcedPattern = Pattern.compile("^abc$", 0, forced)
        val defaultMatcher =
            defaultPattern.matcher("00abc11").region(2, 5).useAnchoringBounds(false)
        val forcedMatcher = forcedPattern.matcher("00abc11").region(2, 5).useAnchoringBounds(false)

        Assertions.assertThat<MatchTrace?>(operationTrace(defaultMatcher, Operation.MATCHES))
            .isEqualTo(operationTrace(forcedMatcher, Operation.MATCHES))
    }

    @Test
    @DisplayName("multiline CRLF anchor traces are engine-path equivalent")
    fun multilineCrLfAnchorTracesAreEnginePathEquivalent() {
        assertEquivalent(
            "(?m)^abc$",
            "xx\r\nabc\r\nyy",
            EnginePathOptions.builder()
                .startAcceleration(false)
                .onePass(false)
                .dfa(false)
                .reverseDfa(false)
                .bitState(false)
                .build()
        )
    }

    @Test
    @DisplayName("nested nullable loops are compiled for DFA and match correctly")
    fun nestedNullableLoopsDfaEquivalence() {
        val forcedDfa =
            EnginePathOptions.builder().onePass(false).bitState(false).build()

        // Case A: Alternation matching bug from dfa_nullable_loop_analysis.md
        assertEquivalent("(?:a?\\b?)*X|(?:a?b)*c", "bc", forcedDfa)

        // Case B: Greedy optional-matching bug from dfa_nullable_loop_analysis.md
        assertEquivalent("(?:a?\\b?)*X", "aaX", forcedDfa)
    }

    @Test
    @DisplayName("priority inversion boundary patterns match across all engine paths")
    fun priorityInversionEquivalence() {
        // 1. Alternation priority inversion
        val regex1 = "(?:\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')|(\\btype\\b)"
        val input1 = "x = \"type\""
        // Assert DFA vs canonical (NFA)
        assertEquivalent(
            regex1, input1, EnginePathOptions.builder().onePass(false).bitState(false).build()
        )
        // Assert BitState vs canonical (NFA)
        assertEquivalent(regex1, input1, EnginePathOptions.builder().dfa(false).onePass(false).build())

        // 2. Lazy quantifier priority inversion
        val regex2 = "\\[.*?\\]\\((.*?)\\)|(\\b\\w+\\.md\\b)"
        val input2 = "abc [def](xyz.md) ghi"
        // Assert DFA vs canonical (NFA)
        assertEquivalent(
            regex2, input2, EnginePathOptions.builder().onePass(false).bitState(false).build()
        )
        // Assert BitState vs canonical (NFA)
        assertEquivalent(regex2, input2, EnginePathOptions.builder().dfa(false).onePass(false).build())
    }

    @Test
    @DisplayName("reverse DFA preserves deferred starts at consuming boundaries")
    fun reverseDfaPreservesDeferredStartsAtConsumingBoundaries() {
        val regex = "(?:(?:\\ba?)|\\B|[^a])a?"
        val input = "ba"
        val forcedDfa =
            EnginePathOptions.builder().onePass(false).bitState(false).build()

        assertEquivalent(regex, input, forcedDfa)
    }

    @Test
    @DisplayName("Shift DFA matches and lookingAt equivalent to canonical engine")
    fun shiftDfaEquivalence() {
        val forcedShift =
            EnginePathOptions.builder()
                .shiftDfa(true)
                .dfa(false)
                .onePass(false)
                .bitState(false)
                .build()

        val disabledShift = EnginePathOptions.builder().shiftDfa(false).build()

        val patterns = arrayOf<String?>(
            "true|false",
            "null",
            "[0-9]{1,4}",
            "[a-zA-Z_][a-zA-Z0-9_]*",
            "[0-9]{4}-[0-9]{2}",
            "[\\x00-\\x21\\x23-\\x7F]*\"",
            "abc|abd|xyz"
        )

        val inputs = arrayOf<String?>(
            "true",
            "false",
            "null",
            "1234",
            "99",
            "_identifier123",
            "2026-08",
            "some_text\"",
            "abc",
            "xyz",
            "mismatch",
            "",
            "12345",
            "truee",
            "2026-8"
        )

        for (pattern in patterns) {
            for (input in inputs) {
                assertEquivalent(pattern, input, forcedShift)
                assertEquivalent(pattern, input, disabledShift)
            }
        }
    }

    private enum class Operation {
        MATCHES,
        LOOKING_AT
    }

    @JvmRecord
    private data class MatchTrace(val matched: Boolean, val groups: MutableList<GroupTrace?>?)

    @JvmRecord
    private data class GroupTrace(val value: String?, val start: Int, val end: Int)
    companion object {
        private fun assertEquivalent(regex: String?, input: String?, options: EnginePathOptions?) {
            val defaultPattern = Pattern.compile(regex)
            val forcedPattern = Pattern.compile(regex, 0, options)

            Assertions.assertThat<MatchTrace?>(operationTrace(defaultPattern.matcher(input), Operation.MATCHES))
                .`as`("matches trace for /%s/ on %s", regex, input)
                .isEqualTo(operationTrace(forcedPattern.matcher(input), Operation.MATCHES))
            Assertions.assertThat<MatchTrace?>(operationTrace(defaultPattern.matcher(input), Operation.LOOKING_AT))
                .`as`("lookingAt trace for /%s/ on %s", regex, input)
                .isEqualTo(operationTrace(forcedPattern.matcher(input), Operation.LOOKING_AT))
            Assertions.assertThat<MatchTrace?>(findTrace(defaultPattern.matcher(input)))
                .`as`("find trace for /%s/ on %s", regex, input)
                .isEqualTo(findTrace(forcedPattern.matcher(input)))
            Assertions.assertThat(defaultPattern.matcher(input).replaceAll("<$0>"))
                .`as`("replaceAll trace for /%s/ on %s", regex, input)
                .isEqualTo(forcedPattern.matcher(input).replaceAll("<$0>"))
            Assertions.assertThat(defaultPattern.matcher(input).replaceFirst("<$0>"))
                .`as`("replaceFirst trace for /%s/ on %s", regex, input)
                .isEqualTo(forcedPattern.matcher(input).replaceFirst("<$0>"))
            Assertions.assertThat(
                defaultPattern.matcher(input)
                    .replaceAll(Function { match: MatchResult? -> "<" + match!!.group() + ">" })
            )
                .`as`("functional replaceAll trace for /%s/ on %s", regex, input)
                .isEqualTo(
                    forcedPattern.matcher(input)
                        .replaceAll(Function { match: MatchResult? -> "<" + match!!.group() + ">" })
                )
            Assertions.assertThat(appendReplacementTrace(defaultPattern.matcher(input)))
                .`as`("appendReplacement trace for /%s/ on %s", regex, input)
                .isEqualTo(appendReplacementTrace(forcedPattern.matcher(input)))
        }

        private fun assertUnguardedDfaFindEquivalent(regex: String?, input: String) {
            val canonical = Pattern.compile(regex)
            val unguarded =
                Pattern.compile(
                    regex, 0, EnginePathOptions.builder().onePass(false).bitState(false).build()
                )

            assertFindPrefixEquivalent(
                unguarded.matcher(input), canonical.matcher(input), input.length + 2, regex, input
            )
        }

        private fun assertFindPrefixEquivalent(
            actual: Matcher, expected: Matcher, maxSteps: Int, regex: String?, input: String?
        ) {
            for (step in 0..<maxSteps) {
                val actualFound = actual.find()
                val expectedFound = expected.find()
                Assertions.assertThat(actualFound)
                    .`as`("find step %s found state for /%s/ on %s", step, regex, input)
                    .isEqualTo(expectedFound)
                if (!expectedFound) {
                    return
                }
                Assertions.assertThat<MatchTrace?>(snapshot(actual, true))
                    .`as`("find step %s trace for /%s/ on %s", step, regex, input)
                    .isEqualTo(snapshot(expected, true))
            }
            throw AssertionError("find trace exceeded " + maxSteps + " steps for /" + regex + "/")
        }

        private fun regionTrace(
            pattern: Pattern, input: String, start: Int, end: Int, transparent: Boolean, anchoring: Boolean
        ): MutableList<MatchTrace?> {
            val matcher =
                pattern.matcher(input).useTransparentBounds(transparent).useAnchoringBounds(anchoring)
            val result: MutableList<MatchTrace?> = ArrayList<MatchTrace?>()
            matcher.region(start, end)
            result.add(snapshot(matcher, matcher.matches()))
            matcher.region(start, end)
            result.add(snapshot(matcher, matcher.lookingAt()))
            matcher.region(start, end)
            for (step in 0..input.length + 1) {
                val found = matcher.find()
                result.add(snapshot(matcher, found))
                if (!found) {
                    return result
                }
            }
            throw AssertionError("find sequence did not terminate")
        }

        private fun operationTrace(matcher: Matcher, operation: Operation): MatchTrace {
            val matched =
                when (operation) {
                    Operation.MATCHES -> matcher.matches()
                    Operation.LOOKING_AT -> matcher.lookingAt()
                }
            return snapshot(matcher, matched)
        }

        private fun findTrace(matcher: Matcher): MutableList<MatchTrace?> {
            val traces: MutableList<MatchTrace?> = ArrayList<MatchTrace?>()
            while (matcher.find()) {
                traces.add(snapshot(matcher, true))
            }
            traces.add(snapshot(matcher, false))
            return traces
        }

        private fun printable(input: String): String {
            return input.replace("\r", "\\r").replace("\n", "\\n")
        }

        private fun appendReplacementTrace(matcher: Matcher): String {
            val builder = StringBuilder()
            while (matcher.find()) {
                matcher.appendReplacement(builder, "<$0>")
            }
            matcher.appendTail(builder)
            return builder.toString()
        }

        private fun snapshot(matcher: Matcher, matched: Boolean): MatchTrace {
            val groups: MutableList<GroupTrace?> = ArrayList<GroupTrace?>()
            if (matched) {
                for (group in 0..matcher.groupCount()) {
                    groups.add(GroupTrace(matcher.group(group), matcher.start(group), matcher.end(group)))
                }
            }
            return MatchTrace(matched, groups)
        }
    }
}
