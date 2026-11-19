// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.function.Function
import java.util.regex.MatchResult
import java.util.stream.Collectors
import java.util.stream.Stream

/** Trace-based lifecycle tests for [Matcher].  */
@DisabledForCrosscheck("this test is itself a SafeRE-vs-JDK lifecycle crosscheck")
internal class MatcherStateMachineTraceTest {
    @Test
    fun nullableRepeatFindPreservesPriorityWithinTransparentRegions() {
        // OSS-Fuzz 575905869: the empty alternative must win before the dot alternative.
        for (regex in mutableListOf<String>(
            "(?:[a-c]{0,2}(?:|.))+",
            "(?:a{0,2}(?:|.))+",
            "(?:a?(?:|.))+",
            "(?:a*(?:|.))+",
            "(?:a{0,2}(?:|.))*",
            "(?:a{0,2}(?:|.)){1,3}",
            "(?:([a-c]{0,2})(?:|.))+"
        )) {
            for (body in mutableListOf<String>("!a!a!", "!a中a!", "!a😀a!", "!a\udc00a!")) {
                for (padding in mutableListOf<Int>(0, 1, 5)) {
                    val input = "x".repeat(padding!!) + body + "z"
                    for (anchoring in mutableListOf<Boolean>(false, true)) {
                        for (transparent in mutableListOf<Boolean>(false, true)) {
                            assertTrace(
                                regex!!,
                                input,
                                Step.region(padding, padding + body!!.length),
                                Step.useAnchoringBounds(anchoring!!),
                                Step.useTransparentBounds(transparent!!),
                                Step.lookingAt(),
                                Step.findWithBounds(),
                                Step.findWithBounds(),
                                Step.findWithBounds()
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun failedExplicitFindStartRetainsResetContinuation() {
        for (regex in mutableListOf<String>("^[ab]*", "\\A(a?)", "a")) {
            assertTrace(
                regex!!,
                "a!",
                Step.findFrom(1),
                Step.findWithBounds(),
                Step.findWithBounds(),
                Step.findWithBounds()
            )
        }
    }

    @Test
    @DisplayName("failed find after an empty anchored match preserves continuation like the JDK")
    fun emptyAnchoredMatchContinuation() {
        // JDK 26 does not specify empty-match advancement after failure precisely (issue #817).
        for (regex in mutableListOf<String>("^[ab]*", "\\A(a?)", "^(a|b)*", "^(?:a*)", "(?m)^[ab]*")) {
            for (input in java.util.List.of<String?>("", "!", "!a😀b!", "a!", "!".repeat(512), "!\na")) {
                for (region in mutableListOf<Boolean>(false, true)) {
                    for (anchoring in mutableListOf<Boolean>(false, true)) {
                        for (initial in java.util.List.of<Step?>(Step.find(), Step.lookingAt())) {
                            for (attempt in java.util.List.of<Step?>(
                                Step.find(),
                                Step.lookingAt(),
                                Step.matches()
                            )) {
                                val text = if (region) "xx" + input + "yy" else input
                                assertTrace(
                                    regex!!,
                                    text,
                                    Step.region(
                                        if (region) 2 else 0,
                                        if (region) input.length + 2 else input.length
                                    ),
                                    Step.useAnchoringBounds(anchoring!!),
                                    initial,
                                    attempt,
                                    Step.findWithBounds(),
                                    Step.findWithBounds(),
                                    Step.findWithBounds(),
                                    Step.findWithBounds()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("anchored attempts preserve find continuation after earlier matches")
    fun anchoredAttemptsPreserveFindContinuation() {
        // Covers issue #808, including empty matches and repeated failed attempts.
        for (regex in mutableListOf<String>("a", "(a+)", "a*", "(a|b)+", "😀*", "^a", "^[ab]")) {
            for (input in mutableListOf<String>("a a", "ba a", "a!", "!a", "😀!😀", "a")) {
                for (initial in java.util.List.of<Step?>(Step.find(), Step.lookingAt())) {
                    for (attempt in java.util.List.of<Step?>(Step.matches(), Step.lookingAt())) {
                        for (region in mutableListOf<Boolean>(false, true)) {
                            val text: String = (if (region) "xx" + input + "yy" else input)!!
                            assertTrace(
                                regex!!,
                                text,
                                Step.region(
                                    if (region) 2 else 0,
                                    if (region) input!!.length + 2 else input!!.length
                                ),
                                initial,
                                attempt,
                                Step.start(),
                                attempt,
                                Step.findWithBounds(),
                                Step.findWithBounds(),
                                Step.reset(),
                                Step.find(),
                                Step.start()
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("interleaved match attempts retain continuation through exhaustion and resets")
    fun interleavedAttemptsRetainContinuation() {
        val operations =
            java.util.List.of<Step?>(Step.findWithBounds(), Step.matches(), Step.lookingAt())
        for (regex in mutableListOf<String>("a", "a*", "(a|b)+", "^a", "^[ab]", "😀*")) {
            for (input in java.util.List.of<String?>("", "a", "a a", "!a", "😀!😀", "ba".repeat(300))) {
                for (first in operations) {
                    for (second in operations) {
                        for (third in operations) {
                            for (fourth in operations) {
                                assertTrace(
                                    regex!!,
                                    input,
                                    first,
                                    second,
                                    third,
                                    fourth,
                                    Step.findWithBounds(),
                                    Step.usePattern("a*"),
                                    Step.matches(),
                                    Step.findWithBounds(),
                                    Step.region(0, input.length),
                                    Step.matches(),
                                    Step.findWithBounds(),
                                    Step.findFrom(input.length),
                                    Step.matches(),
                                    Step.findWithBounds()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("failed matches() leaves the next find() sequence JDK-compatible")
    fun failedMatchesLeavesNextFindSequenceJdkCompatible() {
        assertTrace(
            "a",
            "ba",
            Step.matches(),
            Step.find(),
            Step.group(),
            Step.start(),
            Step.end(),
            Step.find()
        )
    }

    @Test
    @DisplayName("failed lookingAt() leaves the next find() sequence JDK-compatible")
    fun failedLookingAtLeavesNextFindSequenceJdkCompatible() {
        assertTrace(
            "a",
            "ba",
            Step.lookingAt(),
            Step.find(),
            Step.group(),
            Step.start(),
            Step.end(),
            Step.find()
        )
    }

    @Test
    @DisplayName("empty matches advance the following find() sequence like the JDK")
    fun emptyMatchesAdvanceFollowingFindSequenceLikeJdk() {
        assertTrace(
            "a*",
            "ba",
            Step.find(),
            Step.group(),
            Step.find(),
            Step.group(),
            Step.find(),
            Step.group(),
            Step.find()
        )
    }

    @Test
    @DisplayName("find(int) resets region and append position before searching")
    fun findStartResetsRegionAndAppendPositionBeforeSearching() {
        assertTrace(
            "\\d+",
            "xx11yy22",
            Step.region(2, 4),
            Step.find(),
            Step.appendReplacement("[$0]"),
            Step.findFrom(6),
            Step.regionStart(),
            Step.regionEnd(),
            Step.appendReplacement("[$0]"),
            Step.appendTail()
        )
    }

    @Test
    @DisplayName("usePattern preserves the JDK-compatible next search position")
    fun usePatternPreservesJdkCompatibleNextSearchPosition() {
        assertTrace(
            "a*",
            "ba",
            Step.find(),
            Step.group(),
            Step.usePattern("."),
            Step.find(),
            Step.group(),
            Step.find(),
            Step.group()
        )
    }

    @Test
    @DisplayName("bounds changes preserve observable match data")
    fun boundsChangesPreserveObservableMatchData() {
        assertTrace(
            "(a+)",
            "xxaaaa",
            Step.region(2, 6),
            Step.find(),
            Step.useAnchoringBounds(false),
            Step.group(1),
            Step.toMatchResult(),
            Step.useTransparentBounds(true),
            Step.start(1),
            Step.end(1)
        )
    }

    @Test
    @DisplayName("structural mutations during results() traversal match the JDK")
    fun structuralMutationsDuringResultsTraversalMatchJdk() {
        for (mutation in structuralMutations()) {
            val safere: String = runResultsMutationTrace(mutation, false)
            val jdk: String = runResultsMutationTrace(mutation, true)

            Assertions.assertThat(safere).`as`(mutation.name).isEqualTo(jdk)
        }
    }

    @Test
    @DisplayName("structural mutations during functional replacement match the JDK")
    fun structuralMutationsDuringFunctionalReplacementMatchJdk() {
        for (mutation in structuralMutations()) {
            val safereAll: String = runFunctionalReplacementMutationTrace(mutation, false, false)
            val jdkAll: String = runFunctionalReplacementMutationTrace(mutation, true, false)
            val safereFirst: String = runFunctionalReplacementMutationTrace(mutation, false, true)
            val jdkFirst: String = runFunctionalReplacementMutationTrace(mutation, true, true)

            Assertions.assertThat(safereAll).`as`("replaceAll %s", mutation.name).isEqualTo(jdkAll)
            Assertions.assertThat(safereFirst).`as`("replaceFirst %s", mutation.name).isEqualTo(jdkFirst)
        }
    }

    private interface TraceSubject {
        fun matches(): Boolean

        fun lookingAt(): Boolean

        fun find(): Boolean

        fun find(start: Int): Boolean

        fun group(): String?

        fun group(group: Int): String?

        fun start(): Int

        fun start(group: Int): Int

        fun end(): Int

        fun end(group: Int): Int

        fun reset()

        fun region(start: Int, end: Int)

        fun regionStart(): Int

        fun regionEnd(): Int

        fun usePattern(regex: String?)

        fun useAnchoringBounds(value: Boolean)

        fun useTransparentBounds(value: Boolean)

        fun toMatchResult(): MatchResult?

        fun appendReplacement(builder: StringBuilder?, replacement: String?)

        fun appendTail(builder: StringBuilder?)

        fun resultsWithMutation(mutation: Mutation?): Stream<String?>?

        fun replaceAllWithMutation(mutation: Mutation?): String?

        fun replaceFirstWithMutation(mutation: Mutation?): String
    }

    private class SafeReSubject(private val matcher: org.safere.Matcher) : TraceSubject {
        override fun matches(): Boolean {
            return matcher.matches()
        }

        override fun lookingAt(): Boolean {
            return matcher.lookingAt()
        }

        override fun find(): Boolean {
            return matcher.find()
        }

        override fun find(start: Int): Boolean {
            return matcher.find(start)
        }

        override fun group(): String? {
            return matcher.group()
        }

        override fun group(group: Int): String? {
            return matcher.group(group)
        }

        override fun start(): Int {
            return matcher.start()
        }

        override fun start(group: Int): Int {
            return matcher.start(group)
        }

        override fun end(): Int {
            return matcher.end()
        }

        override fun end(group: Int): Int {
            return matcher.end(group)
        }

        override fun reset() {
            matcher.reset()
        }

        override fun region(start: Int, end: Int) {
            matcher.region(start, end)
        }

        override fun regionStart(): Int {
            return matcher.regionStart()
        }

        override fun regionEnd(): Int {
            return matcher.regionEnd()
        }

        override fun usePattern(regex: String?) {
            matcher.usePattern(Pattern.compile(regex!!))
        }

        override fun useAnchoringBounds(value: Boolean) {
            matcher.useAnchoringBounds(value)
        }

        override fun useTransparentBounds(value: Boolean) {
            matcher.useTransparentBounds(value)
        }

        override fun toMatchResult(): MatchResult {
            return matcher.toMatchResult()
        }

        override fun appendReplacement(builder: StringBuilder?, replacement: String?) {
            matcher.appendReplacement(builder!!, replacement!!)
        }

        override fun appendTail(builder: StringBuilder?) {
            matcher.appendTail(builder!!)
        }

        override fun resultsWithMutation(mutation: Mutation?): Stream<String?>? {
            return matcher
                .results()
                .map<String?> { result: MatchResult? ->
                    mutation!!.apply(this)
                    result!!.group()
                }
        }

        override fun replaceAllWithMutation(mutation: Mutation?): String? {
            return matcher.replaceAll(
                Function { result: MatchResult? ->
                    mutation!!.apply(this)
                    "x"
                })
        }

        override fun replaceFirstWithMutation(mutation: Mutation?): String {
            return matcher.replaceFirst(
                Function { result: MatchResult? ->
                    mutation!!.apply(this)
                    "x"
                })!!
        }
    }

    private class JdkSubject(private val matcher: java.util.regex.Matcher) : TraceSubject {
        override fun matches(): Boolean {
            return matcher.matches()
        }

        override fun lookingAt(): Boolean {
            return matcher.lookingAt()
        }

        override fun find(): Boolean {
            return matcher.find()
        }

        override fun find(start: Int): Boolean {
            return matcher.find(start)
        }

        override fun group(): String? {
            return matcher.group()
        }

        override fun group(group: Int): String? {
            return matcher.group(group)
        }

        override fun start(): Int {
            return matcher.start()
        }

        override fun start(group: Int): Int {
            return matcher.start(group)
        }

        override fun end(): Int {
            return matcher.end()
        }

        override fun end(group: Int): Int {
            return matcher.end(group)
        }

        override fun reset() {
            matcher.reset()
        }

        override fun region(start: Int, end: Int) {
            matcher.region(start, end)
        }

        override fun regionStart(): Int {
            return matcher.regionStart()
        }

        override fun regionEnd(): Int {
            return matcher.regionEnd()
        }

        override fun usePattern(regex: String?) {
            matcher.usePattern(java.util.regex.Pattern.compile(regex!!))
        }

        override fun useAnchoringBounds(value: Boolean) {
            matcher.useAnchoringBounds(value)
        }

        override fun useTransparentBounds(value: Boolean) {
            matcher.useTransparentBounds(value)
        }

        override fun toMatchResult(): MatchResult {
            return matcher.toMatchResult()
        }

        override fun appendReplacement(builder: StringBuilder?, replacement: String?) {
            matcher.appendReplacement(builder!!, replacement)
        }

        override fun appendTail(builder: StringBuilder?) {
            matcher.appendTail(builder!!)
        }

        override fun resultsWithMutation(mutation: Mutation?): Stream<String?>? {
            return matcher
                .results()
                .map<String?> { result: MatchResult? ->
                    mutation!!.apply(this)
                    result!!.group()
                }
        }

        override fun replaceAllWithMutation(mutation: Mutation?): String? {
            return matcher.replaceAll(
                Function { result: MatchResult? ->
                    mutation!!.apply(this)
                    "x"
                })
        }

        override fun replaceFirstWithMutation(mutation: Mutation?): String {
            return matcher.replaceFirst(
                Function { result: MatchResult? ->
                    mutation!!.apply(this)
                    "x"
                })
        }
    }

    @JvmRecord
    private data class Mutation(val name: String?, val effect: Effect?) {
        fun apply(subject: TraceSubject?) {
            effect!!.apply(subject)
        }
    }

    @JvmRecord
    private data class Step(val name: String?, val operation: Operation?) {
        fun apply(subject: TraceSubject?): String {
            try {
                return name + "=" + operation!!.apply(subject)
            } catch (e: RuntimeException) {
                return name + " throws " + e.javaClass.getSimpleName()
            }
        }

        companion object {
            fun matches(): Step {
                return value(
                    "matches",
                    MatcherStateMachineTraceTest.Operation { subject: TraceSubject? -> subject!!.matches() })
            }

            fun lookingAt(): Step {
                return value(
                    "lookingAt",
                    MatcherStateMachineTraceTest.Operation { subject: TraceSubject? -> subject!!.lookingAt() })
            }

            fun find(): Step {
                return value(
                    "find",
                    MatcherStateMachineTraceTest.Operation { subject: TraceSubject? -> subject!!.find() })
            }

            fun findWithBounds(): Step {
                return value(
                    "findWithBounds",
                    MatcherStateMachineTraceTest.Operation { subject: TraceSubject? ->
                        if (subject!!.find()) subject.start().toString() + ":" + subject.end() else "false"
                    })
            }

            fun findFrom(start: Int): Step {
                return value(
                    "find(" + start + ")",
                    MatcherStateMachineTraceTest.Operation { subject: TraceSubject? -> subject!!.find(start) })
            }

            fun group(): Step {
                return value(
                    "group",
                    MatcherStateMachineTraceTest.Operation { subject: TraceSubject? -> subject!!.group() })
            }

            fun group(group: Int): Step {
                return value(
                    "group(" + group + ")",
                    MatcherStateMachineTraceTest.Operation { subject: TraceSubject? -> subject!!.group(group) })
            }

            fun start(): Step {
                return value(
                    "start",
                    MatcherStateMachineTraceTest.Operation { subject: TraceSubject? -> subject!!.start() })
            }

            fun start(group: Int): Step {
                return value(
                    "start(" + group + ")",
                    MatcherStateMachineTraceTest.Operation { subject: TraceSubject? -> subject!!.start(group) })
            }

            fun end(): Step {
                return value(
                    "end",
                    MatcherStateMachineTraceTest.Operation { subject: TraceSubject? -> subject!!.end() })
            }

            fun end(group: Int): Step {
                return value(
                    "end(" + group + ")",
                    MatcherStateMachineTraceTest.Operation { subject: TraceSubject? -> subject!!.end(group) })
            }

            fun reset(): Step {
                return effect("reset", MatcherStateMachineTraceTest.Effect { obj: TraceSubject? -> obj!!.reset() })
            }

            fun region(start: Int, end: Int): Step {
                return effect(
                    "region(" + start + "," + end + ")",
                    MatcherStateMachineTraceTest.Effect { subject: TraceSubject? -> subject!!.region(start, end) })
            }

            fun regionStart(): Step {
                return value(
                    "regionStart",
                    MatcherStateMachineTraceTest.Operation { subject: TraceSubject? -> subject!!.regionStart() })
            }

            fun regionEnd(): Step {
                return value(
                    "regionEnd",
                    MatcherStateMachineTraceTest.Operation { subject: TraceSubject? -> subject!!.regionEnd() })
            }

            fun usePattern(regex: String?): Step {
                return effect(
                    "usePattern(" + regex + ")",
                    MatcherStateMachineTraceTest.Effect { subject: TraceSubject? -> subject!!.usePattern(regex) })
            }

            fun useAnchoringBounds(value: Boolean): Step {
                return effect(
                    "useAnchoringBounds(" + value + ")",
                    MatcherStateMachineTraceTest.Effect { subject: TraceSubject? -> subject!!.useAnchoringBounds(value) })
            }

            fun useTransparentBounds(value: Boolean): Step {
                return effect(
                    "useTransparentBounds(" + value + ")",
                    MatcherStateMachineTraceTest.Effect { subject: TraceSubject? -> subject!!.useTransparentBounds(value) })
            }

            fun toMatchResult(): Step {
                return value(
                    "toMatchResult",
                    MatcherStateMachineTraceTest.Operation { subject: TraceSubject? -> snapshot(subject!!.toMatchResult()!!) })
            }

            fun appendReplacement(replacement: String?): Step {
                return Step(
                    "appendReplacement(" + replacement + ")",
                    MatcherStateMachineTraceTest.Operation { subject: TraceSubject? ->
                        val builder = StringBuilder()
                        subject!!.appendReplacement(builder, replacement)
                        builder.toString()
                    })
            }

            fun appendTail(): Step {
                return Step(
                    "appendTail",
                    MatcherStateMachineTraceTest.Operation { subject: TraceSubject? ->
                        val builder = StringBuilder()
                        subject!!.appendTail(builder)
                        builder.toString()
                    })
            }

            private fun value(name: String?, operation: Operation?): Step {
                return Step(name, operation)
            }

            private fun effect(name: String?, effect: Effect): Step {
                return Step(
                    name,
                    MatcherStateMachineTraceTest.Operation { subject: TraceSubject? ->
                        effect.apply(subject)
                        "ok"
                    })
            }

            private fun snapshot(result: MatchResult): String {
                val groups: MutableList<String> = ArrayList<String>()
                for (group in 0..result.groupCount()) {
                    try {
                        groups.add(
                            (group
                                .toString() + ":"
                                    + result.start(group)
                                    + "-"
                                    + result.end(group)
                                    + "="
                                    + result.group(group))
                        )
                    } catch (e: RuntimeException) {
                        groups.add(group.toString() + ":throws " + e.javaClass.getSimpleName())
                    }
                }
                return groups.toString()
            }
        }
    }

    private fun interface Operation {
        fun apply(subject: TraceSubject?): Any?
    }

    private fun interface Effect {
        fun apply(subject: TraceSubject?)
    }

    companion object {
        private fun structuralMutations(): MutableList<Mutation> {
            return java.util.List.of<Mutation?>(
                Mutation(
                    "useTransparentBounds(true)",
                    MatcherStateMachineTraceTest.Effect { subject: TraceSubject? -> subject!!.useTransparentBounds(true) }),
                Mutation(
                    "useTransparentBounds(false)",
                    MatcherStateMachineTraceTest.Effect { subject: TraceSubject? -> subject!!.useTransparentBounds(false) }),
                Mutation(
                    "useAnchoringBounds(true)",
                    MatcherStateMachineTraceTest.Effect { subject: TraceSubject? -> subject!!.useAnchoringBounds(true) }),
                Mutation(
                    "useAnchoringBounds(false)",
                    MatcherStateMachineTraceTest.Effect { subject: TraceSubject? -> subject!!.useAnchoringBounds(false) }),
                Mutation(
                    "appendReplacement", MatcherStateMachineTraceTest.Effect { subject: TraceSubject? ->
                        subject!!.appendReplacement(
                            StringBuilder(), "x"
                        )
                    }),
                Mutation("appendTail", MatcherStateMachineTraceTest.Effect { subject: TraceSubject? ->
                    subject!!.appendTail(
                        StringBuilder()
                    )
                })
            )
        }

        private fun runResultsMutationTrace(mutation: Mutation?, jdk: Boolean): String {
            val subject: TraceSubject = traceSubject("a", "aa", jdk)
            try {
                val groups = subject.resultsWithMutation(mutation)!!.collect(Collectors.toList())
                return "ok:" + groups
            } catch (e: RuntimeException) {
                return "throws " + e.javaClass.getSimpleName()
            }
        }

        private fun runFunctionalReplacementMutationTrace(
            mutation: Mutation?, jdk: Boolean, first: Boolean
        ): String {
            val subject: TraceSubject = traceSubject("a", "aa", jdk)
            try {
                val replaced =
                    (if (first)
                        subject.replaceFirstWithMutation(mutation)
                    else
                        subject.replaceAllWithMutation(mutation))!!
                return "ok:" + replaced
            } catch (e: RuntimeException) {
                return "throws " + e.javaClass.getSimpleName()
            }
        }

        private fun traceSubject(regex: String, input: String, jdk: Boolean): TraceSubject {
            if (jdk) {
                return JdkSubject(java.util.regex.Pattern.compile(regex).matcher(input))
            }
            return SafeReSubject(org.safere.Pattern.compile(regex).matcher(input))
        }

        private fun assertTrace(regex: String, input: String, vararg steps: Step) {
            val safere: TraceSubject = SafeReSubject(org.safere.Pattern.compile(regex).matcher(input))
            val jdk: TraceSubject = JdkSubject(java.util.regex.Pattern.compile(regex).matcher(input))

            val safereTrace: MutableList<String> = trace(safere, *steps)
            val jdkTrace: MutableList<String> = trace(jdk, *steps)

            Assertions.assertThat<String?>(safereTrace).`as`("trace for /%s/ on %s", regex, input).isEqualTo(jdkTrace)
        }

        private fun trace(subject: TraceSubject?, vararg steps: Step): MutableList<String> {
            val events: MutableList<String> = ArrayList<String>()
            for (step in steps) {
                events.add(step.apply(subject))
            }
            return events
        }
    }
}
