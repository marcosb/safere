// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.charset.StandardCharsets
import java.util.function.BiConsumer

/**
 * Checks the work of first-match searches for patterns with both a start literal and a required
 * literal, on inputs where each literal is present or absent.
 * 
 * 
 * Such patterns can scan for either literal first. Scanning for the required literal first
 * rejects inputs that lack it in one pass, but on an input that has it the start literal is scanned
 * for afterwards, so a short input is read twice (#1115). Scanning for the start literal first
 * avoids that, but is only safe when its scan is about as fast as the required literal's. Each
 * ordering is best in some quadrant, so a change that helps the quadrant its author measured can
 * double the work in another one. This test covers all four for each kind of start plan.
 */
@DisabledForCrosscheck("Uses SafeRE's internal work instrumentation")
@Tag("work-counter")
internal class PrefilterQuadrantWorkTest {
    /** A pattern with a start literal and a different required literal.  */
    internal enum class Shape(
        val regex: String?,
        /** Text containing the start literal but not a match.  */
        val start: String?,
        /** The required literal, which the pattern uses as a reject prefilter.  */
        val required: String,
        /** The shortest match.  */
        val match: String,
        /** Whether short inputs scan for the start literal first.  */
        val startFirst: Boolean,
        /** An upper bound on the work per input unit of scanning for the start literal.  */
        val startScanCost: Int
    ) {
        /** An exact start literal that is as selective as the required literal, as in #1115.  */
        COMPETITIVE_START(
            "status=3\\d\\d took=(\\d+)ms", "status=3xx ", " took=", "status=301 took=12ms", true, 1
        ),

        /** A start literal anchored on a common character: the required literal is scanned first.  */
        WEAK_START("er\\d+<<!nav>>\\d+", "ner ", "<<!nav>>", "er12<<!nav>>3", false, 1),

        /**
         * A case-insensitive start literal, whose scan is slower than an exact one and does about two
         * units of work per char on a String: the required literal is scanned first.
         */
        CASE_FOLDED_START(
            "(?i:status=3\\d\\d) took=(\\d+)ms",
            "STATUS=3xx ",
            " took=",
            "Status=301 took=12ms",
            false,
            2
        )
    }

    @JvmRecord
    private data class Case(val name: String?, val input: String?, val bound: Long)

    @ParameterizedTest
    @EnumSource(Shape::class)
    fun searchesScanOnceForEachLiteralTheyNeed(shape: Shape) {
        val pattern = Pattern.compile(shape.regex)
        Assertions.assertThat<RejectPrefilter?>(pattern.findRejectPrefilter())
            .isInstanceOf(RejectPrefilter.Literal::class.java)
        Assertions.assertThat<StringStartAccelerator?>(pattern.stringStartAccelerator()).isNotNull()
        Assertions.assertThat<Utf8StartAccelerator?>(pattern.utf8StartAccelerator()).isNotNull()

        val failures: MutableList<String> = ArrayList<String>()
        for (size in SIZES) {
            // Without the required literal, rejecting the input is a single pass.
            val rejectBound: Long = size.toLong() + SLACK
            // With it, the required literal's scan is followed by the start literal's, except on short
            // inputs for a start literal that is scanned first.
            val scanBound: Long =
                ((if (shape.startFirst && size < START_FIRST_LIMIT) size.toLong() else size * (1L + shape.startScanCost))
                        + SLACK)
            // A short input that has the start literal is scanned again for the required literal from
            // the first start candidate, so an input with both literals and no match is read up to twice.
            val bothBound = if (shape.startFirst && size < START_FIRST_LIMIT) 2L * size + SLACK else scanBound
            val cases =
                java.util.List.of<Case?>(
                    Case("neither literal", filler(size), rejectBound),
                    Case("start literal only", dense(size, shape.start, ""), rejectBound),
                    Case(
                        "required literal only",
                        filler(size - shape.required.length) + shape.required,
                        scanBound
                    ),
                    Case(
                        "both literals, no match", dense(size, shape.start, shape.required), bothBound
                    ),
                    Case(
                        "match at end", filler(size - shape.match.length) + shape.match, scanBound
                    ),
                    Case(
                        "match at start",
                        shape.match + filler(size - shape.match.length),
                        EARLY_HIT_BOUND.toLong()
                    )
                )
            for (c in cases) {
                ENTRY_POINTS.forEach { (name: String?, entryPoint: BiConsumer<Pattern?, String?>?) ->
                    val work: Long = work({ entryPoint!!.accept(pattern, c.input) })
                    if (work > c.bound) {
                        failures.add(
                            String.format(
                                "%s, %s, %d chars: %d units, bound %d",
                                name, c.name, size, work, c.bound
                            )
                        )
                    }
                }
            }
        }
        Assertions.assertThat<String?>(failures).`as`("/%s/", shape.regex).isEmpty()
    }

    companion object {
        /** Contains no character of any literal below that matters for these patterns.  */
        private const val FILLER = "a quick brown fox jumps past the lazy dog; "

        private val SIZES = intArrayOf(64, 400, 16384)

        /** Inputs shorter than this may scan for a competitive start literal first.  */
        private val START_FIRST_LIMIT = Pattern.MAX_LITERAL_START_FIRST_LENGTH

        /** Allowance for verifying candidates and for the scans' fixed costs.  */
        private const val SLACK = 32

        /** Most work a search may do when the match starts at the beginning of the input.  */
        private const val EARLY_HIT_BOUND = 64

        private val ENTRY_POINTS: MutableMap<String?, BiConsumer<Pattern?, String?>?> = entryPoints()

        private fun entryPoints(): MutableMap<String?, BiConsumer<Pattern?, String?>?> {
            val entryPoints: MutableMap<String?, BiConsumer<Pattern?, String?>?> =
                LinkedHashMap<String?, BiConsumer<Pattern?, String?>?>()
            entryPoints.put("Matcher.find", BiConsumer { p: Pattern?, s: String? -> consume(p!!.matcher(s).find()) })
            entryPoints.put(
                "Utf8Matcher.find",
                BiConsumer { p: Pattern?, s: String? -> consume(p!!.matcher(Companion.utf8(s!!)).find()) })
            entryPoints.put(
                "Pattern.find(Utf8Input)",
                BiConsumer { p: Pattern?, s: String? -> consume(p!!.find(Companion.utf8(s!!))) })
            entryPoints.put("asPredicate", BiConsumer { p: Pattern?, s: String? -> consume(p!!.asPredicate().test(s)) })
            return entryPoints
        }

        private fun filler(size: Int): String {
            return FILLER.repeat(size / FILLER.length + 1).substring(0, size)
        }

        /**
         * Returns `size` chars of filler with `piece` at every repetition, ending with `tail`.
         */
        private fun dense(size: Int, piece: String?, tail: String): String {
            val body = (piece + FILLER).repeat(size / FILLER.length + 1)
            return body.substring(0, size - tail.length) + tail
        }

        private fun work(task: () -> Any?): Long {
            task() // Build lazily prepared engines outside the measurement.
            return WorkCounter.countForTesting(task)
        }

        private fun utf8(s: String): Utf8Input {
            return Utf8Input.trusted(s.toByteArray(StandardCharsets.UTF_8))
        }

        private fun consume(unused: Any?) {}
    }
}
