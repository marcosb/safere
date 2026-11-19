// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.regex.MatchResult
import java.util.stream.Stream

/** Focused differential matrix for SafeRE's supported grapheme-region contract.  */
@DisabledForCrosscheck("differential matrix already compares SafeRE with java.util.regex")
internal class GraphemeRegionCompatibilityMatrixTest {
    @ParameterizedTest(name = "[{index}] {0} /{1}/")
    @MethodSource("supportedContractCases")
    @DisplayName("supported grapheme-region contract matches java.util.regex")
    fun supportedGraphemeRegionContractMatchesJdk(scenario: Scenario, regex: String) {
        assertMatchesJdk(scenario, regex)
    }

    @ParameterizedTest(name = "[{index}] {0} /{1}/")
    @MethodSource("indicConjunctCases")
    @EnabledForJreRange(min = JRE.JAVA_22)
    @DisplayName("Indic conjunct grapheme contract matches java.util.regex")
    fun indicConjunctGraphemeContractMatchesJdk(scenario: Scenario, regex: String) {
        assertMatchesJdk(scenario, regex)
    }

    @JvmRecord
    internal data class Scenario(
        val name: String,
        val invariant: String?,
        val input: String?,
        val start: Int,
        val end: Int,
        val transparentBounds: Boolean,
        val anchoringBounds: Boolean,
        val regexes: MutableList<String>?
    ) {
        override fun toString(): String {
            return name
        }
    }

    @JvmRecord
    private data class Trace(
        val groupCount: Int,
        val matches: String?,
        val lookingAt: String?,
        val finds: MutableList<String>?,
        val results: MutableList<String>?,
        val resultsAfterFind: MutableList<String>?
    )

    private enum class Operation {
        MATCHES,
        LOOKING_AT
    }

    companion object {
        private fun assertMatchesJdk(scenario: Scenario, regex: String) {
            val safeTrace: Trace = safeTrace(regex, scenario)
            val jdkTrace: Trace = jdkTrace(regex, scenario)

            Assertions.assertThat<Trace?>(safeTrace)
                .`as`(
                    "%s%nInvariant: %s%nInput length=%s region=[%s,%s] transparent=%s anchoring=%s",
                    scenario.name,
                    scenario.invariant,
                    scenario.input!!.length,
                    scenario.start,
                    scenario.end,
                    scenario.transparentBounds,
                    scenario.anchoringBounds
                )
                .isEqualTo(jdkTrace)
        }

        @JvmStatic
        private fun supportedContractCases(): Stream<Arguments?>? {
            return scenarios().stream()
                .flatMap<Arguments?> { scenario: Scenario? ->
                    scenario!!.regexes!!.stream().map<Arguments?> { regex: String? -> Arguments.of(scenario, regex) }
                }
        }

        @JvmStatic
        private fun indicConjunctCases(): Stream<Arguments?>? {
            val scenario =
                Scenario(
                    "indic conjunct remains one extended cluster",
                    "Indic conjunct segmentation follows the extended grapheme cluster rules rather than"
                            + " splitting at the virama linker.",
                    "\u0915\u094D\u0937",
                    0,
                    3,
                    false,
                    true,
                    mutableListOf<String>("\\X", "\\X+", "(\\X)+", "\\X\\b{g}", "\\b{g}\\X\\b{g}")
                )
            return scenario.regexes!!.stream().map<Arguments?> { regex: String? -> Arguments.of(scenario, regex) }
        }

        private fun scenarios(): MutableList<Scenario?> {
            // Split-surrogate cases use the independent substring oracle in OpaqueRegionContractTest.
            // JDK-8394894 identifies the JDK's region overrun as a bug, not a compatibility requirement.
            return java.util.List.of<Scenario?>(
                Scenario(
                    "interior non-anchoring bounds do not satisfy anchors",
                    "With non-anchoring bounds, region edges are not anchor positions for ^ or $ even when"
                            + " the consuming atom is a valid grapheme cluster.",
                    "#a\u0301$",
                    1,
                    3,
                    false,
                    false,
                    mutableListOf<String>("^\\X$", "\\X$")
                ),
                Scenario(
                    "non-anchoring grapheme matches remain region bounded",
                    "matches() still checks the logical matcher region when no end anchor needs full-text"
                            + " context.",
                    "#a$",
                    1,
                    2,
                    false,
                    false,
                    mutableListOf<String>("\\X", "\\X+", "(\\X)", "\\b{g}?")
                ),
                Scenario(
                    "non-anchoring bounds do not expose region-local line terminator anchors",
                    "With non-anchoring bounds, $ must not accept a position before a line terminator"
                            + " merely because that line terminator is inside the region.",
                    "\r\u0000\n\n",
                    0,
                    3,
                    false,
                    false,
                    mutableListOf<String>("^\\X\\X$", "\\X\\X$", "^(?:\\X){2}$", "^\\X{2}$")
                ),
                Scenario(
                    "non-anchoring dollar sees final crlf across region edge",
                    "With non-anchoring bounds, $ uses full-input trailing-line context, including a"
                            + " final CRLF terminator split by the matcher region end.",
                    "\r\r\r\n",
                    0,
                    3,
                    false,
                    false,
                    mutableListOf<String>("^\\X\\X$", "\\X\\X$", "^(?:\\X){2}$", "^\\X{2}$")
                ),
                Scenario(
                    "transparent non-anchoring dollar sees final crlf across region edge",
                    "Transparent bounds do not change $ anchor context: with non-anchoring bounds, the"
                            + " final CRLF terminator can still be split by the matcher region end.",
                    "\r\r\r\n",
                    0,
                    3,
                    true,
                    false,
                    mutableListOf<String>("^\\X\\X$", "\\X\\X$", "^(?:\\X){2}$", "^\\X{2}$")
                ),
                Scenario(
                    "transparent low surrogate sees preceding high surrogate",
                    "Transparent grapheme context may inspect outside the region, while candidate starts"
                            + " remain inside the region.",
                    "\uD83D\uDC4D\uD83C\uDFFB",
                    1,
                    3,
                    true,
                    true,  // \X\b{g} is covered by the bounded-consumption model, since the JDK overruns.
                    mutableListOf<String>("\\b{g}", "\\b{g}\\X\\b{g}")
                ),
                Scenario(
                    "opaque non-surrogate cluster edge",
                    "Opaque region edges are grapheme-boundary context edges even when they split a"
                            + " base-plus-mark cluster.",
                    "a\u0301b",
                    1,
                    2,
                    false,
                    true,
                    mutableListOf<String>("\\b{g}", "\\b{g}\\X", "\\b{g}\\X\\b{g}")
                ),
                Scenario(
                    "empty opaque region inside base-plus-mark cluster",
                    "An empty opaque region inside a grapheme cluster is still a local boundary context for"
                            + " zero-width grapheme predicates.",
                    "a\u0301",
                    1,
                    1,
                    false,
                    true,
                    mutableListOf<String>("\\b{g}", "(\\b{g})", "\\b{g}|a", "\\b{g}\\X?")
                ),
                Scenario(
                    "empty transparent region inside base-plus-mark cluster",
                    "An empty transparent region inside a grapheme cluster lets \\b{g} inspect the hidden"
                            + " base and extender context.",
                    "a\u0301",
                    1,
                    1,
                    true,
                    true,
                    mutableListOf<String>("\\b{g}", "(\\b{g})", "\\b{g}|a", "\\b{g}\\X?")
                ),
                Scenario(
                    "empty transparent region inside surrogate pair",
                    "An empty transparent region inside a valid surrogate pair lets grapheme-boundary"
                            + " predicates inspect both halves.",
                    "\uD83D\uDE00",
                    1,
                    1,
                    true,
                    true,
                    mutableListOf<String>("\\b{g}", "(\\b{g})", "\\b{g}|a")
                ),
                Scenario(
                    "transparent trailing boundary sees hidden extender",
                    "Transparent bounds let trailing grapheme-boundary predicates see a combining mark just"
                            + " outside the region.",
                    "a\u0301",
                    0,
                    1,
                    true,
                    true,
                    mutableListOf<String>("\\X\\b{g}", "^\\X\\b{g}", "\\b{g}\\X\\b{g}")
                ),
                Scenario(
                    "opaque regional-indicator parity starts at region",
                    "Regional-indicator parity is relative to the active opaque grapheme context, not the"
                            + " full input.",
                    "\uD83C\uDDE6\uD83C\uDDE6\uD83C\uDDE6",
                    2,
                    6,
                    false,
                    true,
                    mutableListOf<String>("\\X", "\\b{g}", "\\b{g}\\X\\b{g}")
                ),
                Scenario(
                    "opaque crlf remains atomic at region edge",
                    "CRLF segmentation remains atomic inside an opaque grapheme context.",
                    "#\r\n$",
                    1,
                    3,
                    false,
                    true,
                    mutableListOf<String>("\\X", "\\X\\b{g}")
                ),
                Scenario(
                    "control characters remain standalone before extenders",
                    "Control characters force grapheme boundaries before following combining marks.",
                    "a\u0000\u0301",
                    1,
                    3,
                    false,
                    true,
                    mutableListOf<String>("\\X", "\\X\\b{g}")
                ),
                Scenario(
                    "transparent crlf boundary sees hidden line feed",
                    "Transparent bounds let trailing grapheme-boundary predicates keep CRLF atomic across"
                            + " the region edge.",
                    "\r\n\u0301",
                    0,
                    1,
                    true,
                    true,
                    mutableListOf<String>("\\X\\b{g}", "\\r\\b{g}\\n?")
                ),
                Scenario(
                    "transparent hangul continuation sees suffix",
                    "Transparent grapheme context lets \\X and \\b{g} see Hangul continuation state outside"
                            + " the consumption range.",
                    "\u1100\u1161",
                    0,
                    1,
                    true,
                    true,
                    mutableListOf<String>("\\X\\b{g}")
                ),
                Scenario(
                    "opaque region inside zwj sequence starts at edge",
                    "Opaque bounds treat a region start inside a ZWJ sequence as a local grapheme"
                            + " boundary.",
                    "\uD83D\uDC69\u200D\uD83D\uDC69",
                    2,
                    5,
                    false,
                    true,
                    mutableListOf<String>("\\b{g}")
                ),
                Scenario(
                    "transparent prepend sees following cluster",
                    "Transparent grapheme context lets a Prepend character stay attached to the following"
                            + " hidden cluster.",
                    "\u0600a",
                    0,
                    1,
                    true,
                    true,
                    mutableListOf<String>("\\X\\b{g}", "\\b{g}\\X\\b{g}")
                ),
                Scenario(
                    "opaque prepend edge is region local",
                    "Opaque bounds let a region ending after a Prepend character form a local grapheme"
                            + " cluster.",
                    "\u0600a",
                    0,
                    1,
                    false,
                    true,
                    mutableListOf<String>("\\X", "\\X\\b{g}", "\\b{g}\\X\\b{g}")
                ),
                Scenario(
                    "transparent spacing mark sees preceding base",
                    "Transparent grapheme context lets a SpacingMark stay attached to the hidden preceding"
                            + " base.",
                    "a\u0903",
                    1,
                    2,
                    true,
                    true,
                    mutableListOf<String>("\\b{g}", "\\b{g}\\X", "\\X\\b{g}")
                ),
                Scenario(
                    "greedy grapheme quantifiers preserve priority",
                    "Repeated and quantified \\X atoms remain leftmost-first and greedy while preserving"
                            + " capture bounds.",
                    "a\u0301b",
                    0,
                    3,
                    false,
                    true,
                    mutableListOf<String>("(\\X)+", "(\\X)+?", "(\\X)(\\X)", "\\X{2}", "a|\\X", "\\X|a")
                ),
                Scenario(
                    "long end-anchored base-plus-mark input uses grapheme engine semantics",
                    "Long inputs exercise optimized engine selection while preserving grapheme-aware end"
                            + " anchors.",
                    "a\u0301".repeat(1000) + "z",
                    0,
                    2001,
                    false,
                    true,
                    mutableListOf<String>("\\Xz$", "\\X\\b{g}z$")
                ),
                Scenario(
                    "long regional-indicator parity remains context relative",
                    "Long regional-indicator runs keep bounded parity state across engine paths.",
                    "\uD83C\uDDE6".repeat(1001),
                    0,
                    2002,
                    false,
                    true,
                    mutableListOf<String>("\\X$")
                )
            )
        }

        private fun safeTrace(regex: String?, scenario: Scenario): Trace {
            val pattern = Pattern.compile(regex)
            val matcher: Matcher = configure(pattern.matcher(scenario.input), scenario)
            val groupCount = matcher.groupCount()

            val matches: String =
                matchTrace(configure(pattern.matcher(scenario.input), scenario), Operation.MATCHES)
            val lookingAt: String =
                matchTrace(configure(pattern.matcher(scenario.input), scenario), Operation.LOOKING_AT)
            val finds: MutableList<String> = findTrace(configure(pattern.matcher(scenario.input), scenario))
            val results: MutableList<String> = resultsTrace(configure(pattern.matcher(scenario.input), scenario))
            val resultsAfterFind: MutableList<String> =
                resultsAfterFindTrace(configure(pattern.matcher(scenario.input), scenario))

            return Trace(groupCount, matches, lookingAt, finds, results, resultsAfterFind)
        }

        private fun jdkTrace(regex: String, scenario: Scenario): Trace {
            val pattern = java.util.regex.Pattern.compile(regex)
            val matcher: java.util.regex.Matcher = configure(pattern.matcher(scenario.input), scenario)
            val groupCount = matcher.groupCount()

            val matches: String =
                matchTrace(configure(pattern.matcher(scenario.input), scenario), Operation.MATCHES)
            val lookingAt: String =
                matchTrace(configure(pattern.matcher(scenario.input), scenario), Operation.LOOKING_AT)
            val finds: MutableList<String> = findTrace(configure(pattern.matcher(scenario.input), scenario))
            val results: MutableList<String> = resultsTrace(configure(pattern.matcher(scenario.input), scenario))
            val resultsAfterFind: MutableList<String> =
                resultsAfterFindTrace(configure(pattern.matcher(scenario.input), scenario))

            return Trace(groupCount, matches, lookingAt, finds, results, resultsAfterFind)
        }

        private fun configure(matcher: Matcher, scenario: Scenario): Matcher {
            return matcher
                .region(scenario.start, scenario.end)
                .useTransparentBounds(scenario.transparentBounds)
                .useAnchoringBounds(scenario.anchoringBounds)
        }

        private fun configure(
            matcher: java.util.regex.Matcher, scenario: Scenario
        ): java.util.regex.Matcher {
            return matcher
                .region(scenario.start, scenario.end)
                .useTransparentBounds(scenario.transparentBounds)
                .useAnchoringBounds(scenario.anchoringBounds)
        }

        private fun matchTrace(matcher: Matcher, operation: Operation): String {
            val matched =
                when (operation) {
                    Operation.MATCHES -> matcher.matches()
                    Operation.LOOKING_AT -> matcher.lookingAt()
                }
            return if (matched) capturedMatchTrace(matcher) else "NO_MATCH"
        }

        private fun matchTrace(matcher: java.util.regex.Matcher, operation: Operation): String {
            val matched =
                when (operation) {
                    Operation.MATCHES -> matcher.matches()
                    Operation.LOOKING_AT -> matcher.lookingAt()
                }
            return if (matched) capturedMatchTrace(matcher) else "NO_MATCH"
        }

        private fun findTrace(matcher: Matcher): MutableList<String> {
            val trace: MutableList<String> = ArrayList<String>()
            while (matcher.find()) {
                trace.add(capturedMatchTrace(matcher))
            }
            return trace
        }

        private fun findTrace(matcher: java.util.regex.Matcher): MutableList<String> {
            val trace: MutableList<String> = ArrayList<String>()
            while (matcher.find()) {
                trace.add(capturedMatchTrace(matcher))
            }
            return trace
        }

        private fun resultsTrace(matcher: Matcher): MutableList<String> {
            return matcher.results().map<String?> { result: MatchResult? -> matchResultTrace(result!!) }
                .toList()
        }

        private fun resultsTrace(matcher: java.util.regex.Matcher): MutableList<String> {
            return matcher.results().map<String?> { result: MatchResult? -> matchResultTrace(result!!) }
                .toList()
        }

        private fun resultsAfterFindTrace(matcher: Matcher): MutableList<String> {
            if (!matcher.find()) {
                return mutableListOf<String>()
            }
            return resultsTrace(matcher)
        }

        private fun resultsAfterFindTrace(matcher: java.util.regex.Matcher): MutableList<String> {
            if (!matcher.find()) {
                return mutableListOf<String>()
            }
            return resultsTrace(matcher)
        }

        private fun capturedMatchTrace(matcher: Matcher): String {
            val builder = StringBuilder()
            builder.append(matcher.start()).append('-').append(matcher.end())
            for (group in 0..matcher.groupCount()) {
                appendGroup(builder, group, matcher.start(group), matcher.end(group), matcher.group(group))
            }
            return builder.toString()
        }

        private fun matchResultTrace(result: MatchResult): String {
            val builder = StringBuilder()
            builder.append(result.start()).append('-').append(result.end())
            for (group in 0..result.groupCount()) {
                appendGroup(builder, group, result.start(group), result.end(group), result.group(group))
            }
            return builder.toString()
        }

        private fun capturedMatchTrace(matcher: java.util.regex.Matcher): String {
            val builder = StringBuilder()
            builder.append(matcher.start()).append('-').append(matcher.end())
            for (group in 0..matcher.groupCount()) {
                appendGroup(builder, group, matcher.start(group), matcher.end(group), matcher.group(group))
            }
            return builder.toString()
        }

        private fun appendGroup(
            builder: StringBuilder, group: Int, start: Int, end: Int, value: String?
        ) {
            builder
                .append(";g")
                .append(group)
                .append('=')
                .append(start)
                .append('-')
                .append(end)
                .append(':')
                .append(if (value == null) "<null>" else value)
        }
    }
}
