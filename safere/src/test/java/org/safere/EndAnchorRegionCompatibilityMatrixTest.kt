// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.regex.MatchResult
import java.util.stream.Stream

/** Focused differential matrix for end anchors under matcher regions and bounds.  */
@DisabledForCrosscheck("differential matrix already compares SafeRE with java.util.regex")
internal class EndAnchorRegionCompatibilityMatrixTest {
    @ParameterizedTest(name = "[{index}] {0} /{1}/")
    @MethodSource("endAnchorRegionCases")
    @DisplayName("end-anchor region behavior matches java.util.regex")
    fun endAnchorRegionBehaviorMatchesJdk(scenario: Scenario, regexCase: RegexCase) {
        val safeTrace: Trace = safeTrace(regexCase, scenario)
        val jdkTrace: Trace = jdkTrace(regexCase, scenario)

        Assertions.assertThat<Trace?>(safeTrace)
            .`as`(
                "%s%nInput length=%s region=[%s,%s] transparent=%s anchoring=%s flags=%s",
                scenario.name,
                scenario.input!!.length,
                scenario.start,
                scenario.end,
                scenario.transparentBounds,
                scenario.anchoringBounds,
                regexCase.flags
            )
            .isEqualTo(jdkTrace)
    }

    @JvmRecord
    private data class InputRegion(val name: String?, val input: String?, val start: Int, val end: Int)

    @JvmRecord
    private data class BoundsCase(val name: String?, val transparentBounds: Boolean, val anchoringBounds: Boolean)

    @JvmRecord
    internal data class Scenario(
        val name: String,
        val input: String?,
        val start: Int,
        val end: Int,
        val transparentBounds: Boolean,
        val anchoringBounds: Boolean
    ) {
        override fun toString(): String {
            return name
        }
    }

    @JvmRecord
    internal data class RegexCase(val name: String, val regex: String?, val flags: Int) {
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
        private const val FIND_LIMIT = 16

        @JvmStatic
        private fun endAnchorRegionCases(): Stream<Arguments?>? {
            return scenarios().stream()
                .flatMap<Arguments?> { scenario: Scenario? ->
                    regexCases().stream().map<Arguments?> { regexCase: RegexCase? -> Arguments.of(scenario, regexCase) }
                }
        }

        private fun scenarios(): MutableList<Scenario?> {
            val scenarios: MutableList<Scenario?> = ArrayList<Scenario?>()
            for (inputRegion in inputRegions()) {
                for (boundsCase in boundsCases()) {
                    scenarios.add(
                        Scenario(
                            inputRegion.name + " " + boundsCase.name,
                            inputRegion.input,
                            inputRegion.start,
                            inputRegion.end,
                            boundsCase.transparentBounds,
                            boundsCase.anchoringBounds
                        )
                    )
                }
            }
            return scenarios
        }

        private fun inputRegions(): MutableList<InputRegion> {
            return java.util.List.of<InputRegion?>(
                InputRegion("before final lf", "ab\n", 0, 2),
                InputRegion("including final lf", "ab\n", 0, 3),
                InputRegion("before final cr", "ab\r", 0, 2),
                InputRegion("including final cr", "ab\r", 0, 3),
                InputRegion("before final nel", "ab\u0085", 0, 2),
                InputRegion("before final ls", "ab\u2028", 0, 2),
                InputRegion("before final ps", "ab\u2029", 0, 2),
                InputRegion("before final crlf", "ab\r\n", 0, 2),
                InputRegion("split final crlf", "ab\r\n", 0, 3),
                InputRegion("including final crlf", "ab\r\n", 0, 4),
                InputRegion("interior lf before suffix", "ab\nx", 0, 3),
                InputRegion("region end before full-text final lf", "a\u0000\n\n", 0, 3)
            )
        }

        private fun boundsCases(): MutableList<BoundsCase> {
            return java.util.List.of<BoundsCase?>(
                BoundsCase("opaque anchoring", false, true),
                BoundsCase("opaque non-anchoring", false, false),
                BoundsCase("transparent anchoring", true, true),
                BoundsCase("transparent non-anchoring", true, false)
            )
        }

        private fun regexCases(): MutableList<RegexCase?> {
            return java.util.List.of<RegexCase?>(
                regex("scoped standard dollar", "[\\s\\S][\\s\\S](?-d:$)", Pattern.UNIX_LINES),
                regex("scoped unix dollar", "[\\s\\S][\\s\\S](?d:$)", 0),
                regex("scoped standard Z", "[\\s\\S][\\s\\S](?-d:\\Z)", Pattern.UNIX_LINES),
                regex("scoped unix Z", "[\\s\\S][\\s\\S](?d:\\Z)", 0),
                regex("mixed end modes", "[\\s\\S][\\s\\S](?-d:$)(?d:$)", 0),
                regex("dollar only", "$", 0),
                regex("Z only", "\\Z", 0),
                regex("z only", "\\z", 0),
                regex("two chars dollar", "[\\s\\S][\\s\\S]$", 0),
                regex("anchored two chars dollar", "^[\\s\\S][\\s\\S]$", 0),
                regex("captured two chars dollar", "([\\s\\S])([\\s\\S])$", 0),
                regex("two chars Z", "[\\s\\S][\\s\\S]\\Z", 0),
                regex("anchored two chars Z", "^[\\s\\S][\\s\\S]\\Z", 0),
                regex("two chars z", "[\\s\\S][\\s\\S]\\z", 0),
                regex("anchored two chars z", "^[\\s\\S][\\s\\S]\\z", 0),
                regex("greedy chars dollar", "[\\s\\S]+$", 0),
                regex("dotall two chars dollar", "..$", Pattern.DOTALL),
                regex("unix lines dollar", "[\\s\\S][\\s\\S]$", Pattern.UNIX_LINES),
                regex("unix lines Z", "[\\s\\S][\\s\\S]\\Z", Pattern.UNIX_LINES),
                regex("multiline dollar", "[\\s\\S][\\s\\S]$", Pattern.MULTILINE),
                regex("multiline anchored dollar", "^[\\s\\S][\\s\\S]$", Pattern.MULTILINE)
            )
        }

        private fun regex(name: String, regex: String?, flags: Int): RegexCase {
            return RegexCase(name, regex, flags)
        }

        private fun safeTrace(regexCase: RegexCase, scenario: Scenario): Trace {
            val pattern = Pattern.compile(regexCase.regex, regexCase.flags)
            val matcher: org.safere.Matcher = configure(pattern.matcher(scenario.input), scenario)
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

        private fun jdkTrace(regexCase: RegexCase, scenario: Scenario): Trace {
            val pattern =
                java.util.regex.Pattern.compile(regexCase.regex, regexCase.flags)
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

        private fun configure(matcher: org.safere.Matcher, scenario: Scenario): org.safere.Matcher {
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

        private fun matchTrace(matcher: org.safere.Matcher, operation: Operation): String {
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

        private fun findTrace(matcher: org.safere.Matcher): MutableList<String> {
            val trace: MutableList<String> = ArrayList<String>()
            while (matcher.find()) {
                trace.add(capturedMatchTrace(matcher))
                if (trace.size > FIND_LIMIT) {
                    throw AssertionError("find() trace exceeded " + FIND_LIMIT + " matches")
                }
            }
            return trace
        }

        private fun findTrace(matcher: java.util.regex.Matcher): MutableList<String> {
            val trace: MutableList<String> = ArrayList<String>()
            while (matcher.find()) {
                trace.add(capturedMatchTrace(matcher))
                if (trace.size > FIND_LIMIT) {
                    throw AssertionError("find() trace exceeded " + FIND_LIMIT + " matches")
                }
            }
            return trace
        }

        private fun resultsTrace(matcher: org.safere.Matcher): MutableList<String> {
            return matcher.results().map<String?> { result: MatchResult? -> matchResultTrace(result!!) }
                .toList()
        }

        private fun resultsTrace(matcher: java.util.regex.Matcher): MutableList<String> {
            return matcher.results().map<String?> { result: MatchResult? -> matchResultTrace(result!!) }
                .toList()
        }

        private fun resultsAfterFindTrace(matcher: org.safere.Matcher): MutableList<String> {
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

        private fun capturedMatchTrace(matcher: org.safere.Matcher): String {
            val builder = StringBuilder()
            builder.append(matcher.start()).append('-').append(matcher.end())
            for (group in 0..matcher.groupCount()) {
                appendGroup(builder, group, matcher.start(group), matcher.end(group), matcher.group(group))
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

        private fun matchResultTrace(result: MatchResult): String {
            val builder = StringBuilder()
            builder.append(result.start()).append('-').append(result.end())
            for (group in 0..result.groupCount()) {
                appendGroup(builder, group, result.start(group), result.end(group), result.group(group))
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
