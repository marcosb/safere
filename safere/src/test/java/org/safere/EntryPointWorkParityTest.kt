// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.List
import java.util.Map
import java.util.function.BiConsumer

/**
 * Checks that no search entry point does much more work than [Matcher.find] on the same
 * pattern and input.
 * 
 * 
 * Each entry point has its own sequence of strategies. A strategy added to one sequence and not
 * the others leaves the others correct but slow, which no functional test notices. This compares
 * [WorkCounter] units instead of strategy names, so an entry point may answer with a
 * different engine as long as it doesn't cost more.
 */
@DisabledForCrosscheck("WorkCounter is an internal SafeRE API")
@Tag("work-counter")
internal class EntryPointWorkParityTest {
    /**
     * Pattern and the suffix appended to the filler; an empty suffix gives an input with no match.
     */
    @JvmRecord
    private data class Scenario(val regex: String, val tail: String) {
        override fun toString(): String {
            val leading = tail.substring(0, tail.length - tail.trimStart().length)
            val shown =
                if (leading.size > 8) "<" + leading.size + " spaces>" + tail.trimStart() else tail
            return "/" + regex + "/" + (if (tail.isEmpty()) " no match" else " + '" + shown + "'")
        }
    }

    @Test
    fun noEntryPointDoesMuchMoreWorkThanFind() {
        val failures: MutableList<String?> = ArrayList<String?>()
        for (scenario in SCENARIOS) {
            val pattern = Pattern.compile(scenario.regex)
            for (size in SIZES) {
                val input = FILLER.repeat(size / FILLER.length) + scenario.tail
                val find: Long = work(Runnable { consume(pattern.matcher(input).find()) })
                val findAll: Long = work(Runnable { findAll(pattern, input) })
                FIRST_MATCH.forEach { (name: String?, op: BiConsumer<Pattern?, String?>?) ->
                    check(
                        failures,
                        scenario,
                        size,
                        name,
                        find,
                        pattern,
                        input,
                        op!!
                    )
                }
                ALL_MATCHES.forEach { (name: String?, op: BiConsumer<Pattern?, String?>?) ->
                    check(
                        failures,
                        scenario,
                        size,
                        name,
                        findAll,
                        pattern,
                        input,
                        op!!
                    )
                }
            }
        }
        Assertions.assertThat<String?>(failures).isEmpty()
    }

    @Test
    fun referenceWorkIncludesForwardDfaTraversal() {
        val pattern =
            Pattern.compile(
                "[0-9]+",
                0,
                EnginePathOptions.builder()
                    .charClassMatchFastPaths(false)
                    .startAcceleration(false)
                    .shiftDfa(false)
                    .build()
            )
        for (size in SIZES) {
            val input = FILLER.repeat(size / FILLER.length)
            val search = Runnable { Assertions.assertThat(pattern.matcher(input).find()).isFalse() }
            val reference: Long = work(search)
            val dfaSteps = WorkCounter.countForwardDfaStepsForTesting(search)

            Assertions.assertThat(dfaSteps)
                .`as`("the reference must exercise the DFA")
                .isGreaterThanOrEqualTo(input.length.toLong())
            Assertions.assertThat(reference)
                .`as`("reference work must include DFA steps")
                .isGreaterThanOrEqualTo(dfaSteps)
        }
    }

    @Test
    fun guardedGapEntryPointsAvoidScanningDensePrefixCandidates() {
        val regex = "record:[^\\r\\n;]*val=200"
        val noise = "record:x;"
        for (size in SIZES) {
            val input = noise.repeat(size / noise.length) + "record:target_val=200"
            val pattern = Pattern.compile(regex)
            val forwardOnly =
                Pattern.compile(regex, 0, EnginePathOptions.builder().literalFastPaths(false).build())
            Assertions.assertThat(
                WorkCounter.countForwardDfaStepsForTesting(
                    Runnable { consume(forwardOnly.matcher(input).find()) })
            )
                .`as`("the control must scan the dense prefix candidates")
                .isGreaterThan(input.length / 4L)

            val check =
                BiConsumer { name: String?, task: Runnable? ->
                    task!!.run() // Prepare lazy engines outside the counted operation.
                    val steps = WorkCounter.countForwardDfaStepsForTesting(task)
                    Assertions.assertThat(steps)
                        .`as`("%s must narrow the guarded gap at %d chars", name, input.length)
                        .isLessThan(input.length / 16L)
                }
            check.accept("Matcher.find", Runnable { consume(pattern.matcher(input).find()) })
            FIRST_MATCH.forEach { (name: String?, operation: BiConsumer<Pattern?, String?>?) ->
                check.accept(
                    name,
                    Runnable { operation!!.accept(pattern, input) })
            }
            ALL_MATCHES.forEach { (name: String?, operation: BiConsumer<Pattern?, String?>?) ->
                check.accept(
                    name,
                    Runnable { operation!!.accept(pattern, input) })
            }
        }
    }

    companion object {
        private const val FILLER = "the quick brown fox jumps over the lazy dog "
        private val SIZES = intArrayOf(2000, 20000)

        /**
         * An entry point may do this many times the reference's work, plus [.SLACK] units.
         * 
         * 
         * This is below 2 so that an entry point that scans the whole input twice, for example by
         * running a prefilter whose answer it then ignores, fails. The worst ratio measured at the time
         * of writing was about 1.1, for the UTF-8 entry points on `(?i)\b(?:foo|bar|baz)\b`. UTF-8
         * entry points count bytes where [Matcher.find] counts chars, which only differ on the
         * non-ASCII scenarios, and those measured within 1% of the reference.
         */
        private const val FACTOR = 1.5

        private const val SLACK = 100

        private val SCENARIOS: MutableList<Scenario> = List.of<Scenario?>(
            Scenario("hello", ""),
            Scenario("hello", "hello"),
            Scenario("[0-9]", ""),
            Scenario("[0-9]+", ""),
            Scenario("(?i)\\b(?:foo|bar|baz)\\b", ""),
            Scenario("(?i)\\b(?:foo|bar|baz)\\b", " bar"),
            Scenario("apple|banana|cherry", ""),
            Scenario("(?:foo|fax)$", ""),
            Scenario("(?:foo|fax)$", " foo"),
            Scenario("\\s*(?:foo|fax)\\z", ""),
            Scenario("\\s*(?:foo|fax)\\z", " foo"),
            Scenario("(?i)\\s*(?:foo|fax)$", ""),
            Scenario("^GET [^ ]+", ""),
            Scenario("\\s*# noqa", ""),
            Scenario("\\s*# noqa", "  # noqa"),  // The inner candidate fails verification after a long leading run.
            Scenario("\\s*# noqa[0-9]", " ".repeat(20000) + "# noqaX"),
            Scenario("foo.*bar.*[0-9]$", ""),
            Scenario("a*[bc]d", ""),
            Scenario("é[ab]+c", ""),
            Scenario("é[ab]+c", "éabc"),
            Scenario("(x?)*Q", ""),
            Scenario("[a-z]+foo$", "")
        )

        /** Entry points that stop at the first match, compared with one [Matcher.find].  */
        private val FIRST_MATCH: MutableMap<String?, BiConsumer<Pattern?, String?>?> =
            Map.of<String?, BiConsumer<Pattern?, String?>?>(
                "Utf8Matcher.find",
                BiConsumer { p: Pattern?, s: String? -> consume(p!!.matcher(utf8(s!!)).find()) },
                "Pattern.find(Utf8Input)",
                BiConsumer { p: Pattern?, s: String? -> consume(p!!.find(utf8(s!!))) },
                "asPredicate",
                BiConsumer { p: Pattern?, s: String? -> consume(p!!.asPredicate().test(s)) },
                "replaceFirst",
                BiConsumer { p: Pattern?, s: String? -> consume(p!!.matcher(s).replaceFirst("X")) })

        /** Entry points that visit every match, compared with a [Matcher.find] loop.  */
        private val ALL_MATCHES: MutableMap<String?, BiConsumer<Pattern?, String?>?> =
            Map.of<String?, BiConsumer<Pattern?, String?>?>(
                "replaceAll",
                BiConsumer { p: Pattern?, s: String? -> consume(p!!.matcher(s).replaceAll("X")) },
                "split",
                BiConsumer { p: Pattern?, s: String? -> consume(p!!.split(s)) })

        /**
         * Known gaps, keyed by `scenario + " " + entry point`. Each is expected to exceed the
         * bound, so fixing one fails the test until its entry is removed.
         */
        private val KNOWN_GAPS: MutableMap<String?, String?> = knownGaps()

        private fun knownGaps(): MutableMap<String?, String?> {
            return Map.of<String?, String?>()
        }

        private fun check(
            failures: MutableList<String?>,
            scenario: Scenario?,
            size: Int,
            entryPoint: String?,
            reference: Long,
            pattern: Pattern?,
            input: String?,
            op: BiConsumer<Pattern?, String?>
        ) {
            val actual: Long = work(Runnable { op.accept(pattern, input) })
            val withinBound = actual <= FACTOR * reference + SLACK
            val key = scenario.toString() + " " + entryPoint
            val gap: String? = KNOWN_GAPS.get(key)
            if (gap == null && !withinBound) {
                failures.add(
                    String.format(
                        "%s at %d chars: %d units against %d for find()", key, size, actual, reference
                    )
                )
            } else if (gap != null && withinBound && size == SIZES[SIZES.size - 1]) {
                failures.add(
                    String.format(
                        "%s at %d chars is now within bounds (%d units against %d); remove its known gap: %s",
                        key, size, actual, reference, gap
                    )
                )
            }
        }

        private fun work(task: Runnable): Long {
            task.run() // Build lazily prepared engines outside the measurement.
            // Direct scanners record ordinary units; the DFA's ASCII loop records separate steps.
            return WorkCounter.countWithForwardDfaStepsForTesting(task)
        }

        private fun findAll(pattern: Pattern, input: String?) {
            val matcher = pattern.matcher(input)
            while (matcher.find()) {
                // Visit every match.
            }
        }

        private fun utf8(s: String): Utf8Input {
            return Utf8Input.trusted(s.toByteArray(StandardCharsets.UTF_8))
        }

        private fun consume(unused: Any?) {}
    }
}
