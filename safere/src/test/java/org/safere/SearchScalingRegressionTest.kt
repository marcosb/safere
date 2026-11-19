// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.function.IntFunction
import java.util.function.IntPredicate
import java.util.function.IntSupplier
import kotlin.math.max

@DisabledForCrosscheck("WorkCounter is an internal SafeRE API")
@Tag("work-counter")
internal class SearchScalingRegressionTest {
    @Test
    fun opaqueRequiredClassesReuseScansAcrossFindsAndAdvancingRegions() {
        for (advanceRegion in booleanArrayOf(false, true)) {
            val pattern = Pattern.compile("\\X?\\uDE00")
            assertFindAllWorkIsLinear(
                IntFunction { size: Int ->
                    val input = "a" + "😀".repeat(size) + "\uDE00!"
                    val matcher = pattern.matcher(input).region(1, input.length)
                    FindIterator {
                        val found = matcher.find()
                        if (found && advanceRegion) {
                            matcher.region(matcher.end(), input.length)
                        }
                        found
                    }
                },
                "opaque class scans, advancing region=" + advanceRegion
            )
        }
    }

    @Test
    fun shrinkingOpaqueRegionsReuseTheAbsentScalarInterior() {
        val pattern = Pattern.compile("\\X?\\uDE00")
        val work = LongArray(2)
        for (index in work.indices) {
            val pairs = if (index == 0) 200 else 1000
            val input = "a" + "😀".repeat(pairs) + "\uDE00!"
            val matcher = pattern.matcher(input)
            work[index] =
                WorkCounter.countForTesting(
                    Runnable {
                        for (end in input.length downTo 1) {
                            matcher.region(1, end)
                            Assertions.assertThat(matcher.find()).isEqualTo(end >= input.length - 1)
                        }
                    })
        }
        Assertions.assertThat(work[0]).isPositive()
        Assertions.assertThat(work[1]).`as`("shrinking regions must not repeat tail scans").isLessThan(work[0] * 6)
    }

    @Test
    fun backwardMovingOpaqueRegionsReuseTheAbsentScalarInterior() {
        for (regex in arrayOf<String>("\\X?\\p{Cs}", "\\X?[\\uD83D\\uDE00]")) {
            val pattern = Pattern.compile(regex)
            val work = LongArray(2)
            for (index in work.indices) {
                val pairs = if (index == 0) 200 else 1000
                val input = "a" + "😀".repeat(pairs) + "\uD83Dxy"
                val matcher = pattern.matcher(input)
                work[index] =
                    WorkCounter.countForTesting(
                        Runnable {
                            var start = 2 * pairs - 1
                            while (start >= 1) {
                                matcher.region(start, input.length)
                                Assertions.assertThat(matcher.find()).isTrue()
                                start -= 2
                            }
                        })
            }
            Assertions.assertThat(work[0]).isPositive()
            Assertions.assertThat(work[1])
                .`as`("backward region starts must not repeat tail scans: %s", regex)
                .isLessThan(work[0] * 6)
        }
    }

    @Test
    fun exposedRegionEndWitnessDoesNotDelayTheFirstGraphemeMatch() {
        val pattern = Pattern.compile("\\X?\\p{Cs}")
        val work = LongArray(2)
        for (index in work.indices) {
            val pairs = if (index == 0) 128 else 8192
            val input = "a" + "😀".repeat(pairs + 1) + "!"
            val matcher = pattern.matcher(input).region(1, 2 * pairs + 2)
            work[index] =
                WorkCounter.countForTesting(
                    Runnable {
                        Assertions.assertThat(matcher.find()).isTrue()
                        Assertions.assertThat(matcher.start()).isEqualTo(2)
                        Assertions.assertThat(matcher.end()).isEqualTo(3)
                    })
        }
        Assertions.assertThat(work[0]).isPositive()
        Assertions.assertThat(work[1]).`as`("first result must not scan the tail").isEqualTo(work[0])
    }

    @Test
    fun nestedNullableLoopEntryResetsKeepFindWorkLinear() {
        for (options in arrayOf<EnginePathOptions>(
            EnginePathOptions.builder().onePass(false).bitState(false).build(),
            EnginePathOptions.builder().dfa(false).onePass(false).build(),
            EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build()
        )) {
            val pattern = Pattern.compile("(?:(?:x|)+?)*", 0, options)
            assertFindAllWorkIsLinear(IntFunction { size: Int ->
                FindIterator {
                    pattern.matcher("x".repeat(size)).find()
                }
            }, "String loops")
            assertFindAllWorkIsLinear(
                IntFunction { size: Int ->
                    FindIterator {
                        pattern.matcher(
                            Utf8Input.trusted(
                                "x".repeat(size).toByteArray(
                                    StandardCharsets.UTF_8
                                )
                            )
                        ).find()
                    }
                },
                "UTF-8 loops"
            )
        }
    }

    @Test
    fun nullableLoopEpsilonRevisitsKeepFindWorkLinear() {
        val nfaOnly =
            EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build()
        for (regex in arrayOf<String>("(?:a?(?:|.))+", "(?:a?(?:\\b|.))+")) {
            val pattern = Pattern.compile(regex, 0, nfaOnly)
            assertFindAllWorkIsLinear(
                IntFunction { size: Int -> FindIterator { pattern.matcher("!a!".repeat(size)).find() } },
                "String epsilon revisits"
            )
            assertFindAllWorkIsLinear(
                IntFunction { size: Int ->
                    FindIterator {
                        pattern.matcher(
                            Utf8Input.trusted(
                                "!a!".repeat(size).toByteArray(
                                    StandardCharsets.UTF_8
                                )
                            )
                        ).find()
                    }
                },
                "UTF-8 epsilon revisits"
            )
        }
    }

    @Test
    fun nestedNullableEpsilonRevisitsKeepFindWorkLinear() {
        val nfaOnly =
            EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build()
        for (body in arrayOf<String>("(?:a?(?:|.))", "(?:a?(?:\\b|.))")) {
            for (firstQuantifier in arrayOf<String>("+", "+?")) {
                var regex = body
                var quantifier = firstQuantifier
                for (depth in 1..7) {
                    regex = "(?:" + regex + ")" + quantifier
                    quantifier = if (quantifier == "+") "+?" else "+"
                    val pattern = Pattern.compile(regex, 0, nfaOnly)
                    assertFindAllWorkIsLinear(
                        IntFunction { size: Int -> FindIterator { pattern.matcher("!a!".repeat(size) + "z").find() } },
                        "String nested epsilon revisits " + regex
                    )
                    assertFindAllWorkIsLinear(
                        IntFunction { size: Int ->
                            FindIterator {
                                pattern.matcher(
                                    Utf8Input.trusted(
                                        ("!a!".repeat(size) + "z").toByteArray(
                                            StandardCharsets.UTF_8
                                        )
                                    )
                                )
                                    .find()
                            }
                        },
                        "UTF-8 nested epsilon revisits " + regex
                    )
                }
            }
        }
    }

    @Test
    fun optionalEpsilonChainsKeepWorkLinearInPatternSize() {
        for (template in arrayOf<String>("(?:\\b?){%d}a", "(?:(?:|b)?){%d}a")) {
            val work = LongArray(2)
            for (index in work.indices) {
                val pattern = Pattern.compile(template.formatted(8 * (index + 1)))
                val matcher = pattern.matcher("a!").region(0, 1).useTransparentBounds(true)
                work[index] =
                    WorkCounter.countForTesting(Runnable { Assertions.assertThat(matcher.lookingAt()).isTrue() })
            }
            Assertions.assertThat(work[0]).`as`("epsilon work must be observed for %s", template).isPositive()
            Assertions.assertThat(work[1])
                .`as`("doubling optional epsilon chains must not enumerate their paths: %s", template)
                .isLessThan(work[0] * 4)
        }
    }

    @Test
    fun alternatingNestedNullableLoopWorkIsPolynomialInDepth() {
        // Alternating lazy and greedy nullable loops do not simplify away. Each fresh entry must be
        // expanded at most once per position, or every enclosing iteration re-expands the inner loops
        // and per-position work grows exponentially with the nesting depth.
        val nfaOnly =
            EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build()
        val input = "x".repeat(64)
        val work = LongArray(13)
        var depth = 4
        while (depth <= 12) {
            val pattern = Pattern.compile(alternatingNullableLoops(depth), 0, nfaOnly)
            work[depth] =
                WorkCounter.countForTesting(
                    Runnable {
                        val matcher = pattern.matcher(input)
                        while (matcher.find()) {
                        }
                    })
            depth += 4
        }
        // Measured at 4,296 / 21,780 / 96,624 units for depths 4 / 8 / 12. Re-expanding each fresh
        // entry once per enclosing iteration measured 8,768 / 1,220,228 / 1,162,878,680.
        Assertions.assertThat(work[12])
            .`as`("depth 4/8/12 work %d/%d/%d", work[4], work[8], work[12])
            .isLessThan(8 * work[8])
    }

    @Test
    fun endAnchoredSearchSkipsUnrelatedPrefix() {
        for (regex in arrayOf<String>("abc$", "(ab)c\\z", "[YZ]abc$", "a+b$", "(?i:abc)$")) {
            val pattern = Pattern.compile(regex)
            val tail = if (regex.startsWith("[YZ]")) "Yabc" else if (regex == "a+b$") "aaab" else "abc"
            for (prefix in arrayOf<String>("x", "é😀")) {
                val small = prefix.repeat(2000) + tail
                val large = prefix.repeat(20000) + tail
                val smallUtf8 = Utf8Input.trusted(small.toByteArray(StandardCharsets.UTF_8))
                val largeUtf8 = Utf8Input.trusted(large.toByteArray(StandardCharsets.UTF_8))

                assertBoundedEndSearchWork(
                    Runnable { Assertions.assertThat(pattern.matcher(small).find()).isTrue() },
                    Runnable { Assertions.assertThat(pattern.matcher(large).find()).isTrue() },
                    regex + " String"
                )
                assertBoundedEndSearchWork(
                    Runnable { Assertions.assertThat(pattern.matcher(smallUtf8).find()).isTrue() },
                    Runnable { Assertions.assertThat(pattern.matcher(largeUtf8).find()).isTrue() },
                    regex + " UTF-8 matcher"
                )
                assertBoundedEndSearchWork(
                    Runnable { Assertions.assertThat(pattern.find(smallUtf8)).isTrue() },
                    Runnable { Assertions.assertThat(pattern.find(largeUtf8)).isTrue() },
                    regex + " UTF-8 existence"
                )
            }
        }
    }

    @Test
    fun longLiteralSuffixFitsPatternSizedProbe() {
        for (suffix in arrayOf<String>("a".repeat(100) + "z", "é😀".repeat(50))) {
            val pattern = Pattern.compile(suffix + "$")
            val small = "x".repeat(2000) + suffix
            val large = "x".repeat(20000) + suffix
            val smallUtf8 = Utf8Input.trusted(small.toByteArray(StandardCharsets.UTF_8))
            val largeUtf8 = Utf8Input.trusted(large.toByteArray(StandardCharsets.UTF_8))
            assertBoundedEndSearchWork(
                Runnable { Assertions.assertThat(pattern.matcher(small).find()).isTrue() },
                Runnable { Assertions.assertThat(pattern.matcher(large).find()).isTrue() },
                "long literal String suffix"
            )
            assertBoundedEndSearchWork(
                Runnable { Assertions.assertThat(pattern.find(smallUtf8)).isTrue() },
                Runnable { Assertions.assertThat(pattern.find(largeUtf8)).isTrue() },
                "long literal UTF-8 suffix"
            )
        }
    }

    @Test
    fun reverseProbeWorkStaysBoundedWithCachedAsciiTransitions() {
        for (size in intArrayOf(2000, 20000)) {
            val text = "x".repeat(size)
            for (scanner in arrayOf<InputScanner>(
                StringInputScanner(text), Utf8InputScanner(
                    text.toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
            )) {
                val reverse = Pattern.compile("needle.*$").reverseDfa()
                for (pass in 0..1) {
                    val work =
                        WorkCounter.countForTesting(
                            Runnable {
                                val result =
                                    reverse.doSearchReverse(scanner, scanner.length(), 0, true, true, 64)
                                Assertions.assertThat<Dfa.SearchResult?>(result).isNotNull()
                                Assertions.assertThat(result!!.scanLimitReached).isTrue()
                            })
                    Assertions.assertThat(work).isBetween(64L, 256L)
                }
            }
        }
    }

    @Test
    fun endAnchoredExistenceSearchStopsAtVerifiedSuffix() {
        val pattern = Pattern.compile("(?s:.*)abc$")
        for (prefix in arrayOf<String>("x", "é😀")) {
            for (terminator in arrayOf<String>("", "\n", "\r\n")) {
                val small =
                    Utf8Input.trusted((prefix.repeat(2000) + "abc" + terminator).toByteArray(StandardCharsets.UTF_8))
                val large =
                    Utf8Input.trusted((prefix.repeat(20000) + "abc" + terminator).toByteArray(StandardCharsets.UTF_8))
                assertBoundedEndSearchWork(
                    Runnable { Assertions.assertThat(pattern.find(small)).isTrue() },
                    Runnable { Assertions.assertThat(pattern.find(large)).isTrue() },
                    "UTF-8 existence with greedy prefix"
                )
            }
        }
    }

    @Test
    fun endAnchoredLeadingExpansionNoMatchWorkIsIndependentOfInputLength() {
        // A leading expansion (\\s*) gives these patterns a start accelerator. A failing search must
        // still be decided by the reverse-first search from the end of the input, not by a forward scan
        // from the first candidate.
        for (regex in arrayOf<String>("(?i)\\s*(foo|fax)$", "\\s*(foo|fax)\\z")) {
            val pattern = Pattern.compile(regex)
            val text = IntFunction { size: Int -> "fine food offer ".repeat(size / 16) + "end" }
            val smallerString =
                WorkCounter.countForTesting(
                    Runnable { Assertions.assertThat(pattern.matcher(text.apply(2000)).find()).isFalse() })
            val largerString =
                WorkCounter.countForTesting(
                    Runnable { Assertions.assertThat(pattern.matcher(text.apply(64000)).find()).isFalse() })
            val smallerUtf8 =
                WorkCounter.countForTesting(
                    Runnable {
                        Assertions.assertThat(
                            pattern
                                .matcher(Utf8Input.trusted(text.apply(2000)!!.toByteArray(StandardCharsets.UTF_8)))
                                .find()
                        )
                            .isFalse()
                    })
            val largerUtf8 =
                WorkCounter.countForTesting(
                    Runnable {
                        Assertions.assertThat(
                            pattern
                                .matcher(Utf8Input.trusted(text.apply(64000)!!.toByteArray(StandardCharsets.UTF_8)))
                                .find()
                        )
                            .isFalse()
                    })

            Assertions.assertThat(largerString).`as`("%s String", regex).isLessThan(smallerString * 2 + 64)
            Assertions.assertThat(largerUtf8).`as`("%s UTF-8", regex).isLessThan(smallerUtf8 * 2 + 64)
        }
    }

    @Test
    fun utf8FindEndAnchoredNoMatchWorkIsIndependentOfInputLength() {
        for (regex in arrayOf<String>("(foo|fax)$", "(?i)(foo|fax)\\z")) {
            val pattern = Pattern.compile(regex)
            val text =
                IntFunction { size: Int -> ("fine food offer ".repeat(size / 16) + "end").toByteArray(StandardCharsets.UTF_8) }
            val smaller =
                WorkCounter.countForTesting(
                    Runnable { Assertions.assertThat(pattern.find(Utf8Input.trusted(text.apply(2000)))).isFalse() })
            val larger =
                WorkCounter.countForTesting(
                    Runnable { Assertions.assertThat(pattern.find(Utf8Input.trusted(text.apply(64000)))).isFalse() })

            Assertions.assertThat(larger).`as`(regex).isLessThan(smaller * 2 + 64)
        }
    }

    @Test
    fun guardedGapRetriesReuseDelimiterScanWork() {
        val pattern = Pattern.compile("AAAA[^;]*RAREBBB")

        assertGuardedGapRetryWorkIsLinear(
            IntFunction { size: Int -> FindIterator { pattern.matcher("A".repeat(size) + ";RAREBBB").find() } },
            "String"
        )
        assertGuardedGapRetryWorkIsLinear(
            IntFunction { size: Int ->
                FindIterator {
                    pattern.matcher(
                        Utf8Input.trusted(
                            ("A".repeat(size) + ";RAREBBB").toByteArray(
                                StandardCharsets.UTF_8
                            )
                        )
                    )
                        .find()
                }
            },
            "UTF-8"
        )
    }

    @Test
    fun guardedGapRetriesReuseSuccessfulSliceValidation() {
        val pattern = Pattern.compile("AAAA[^;]*RAREBBB[^;]X")

        assertGuardedGapRetryWorkIsLinear(
            IntFunction { size: Int ->
                FindIterator {
                    pattern.matcher("X" + "AAAA".repeat(size) + "RAREBBBy").find()
                }
            }, "String"
        )
        assertGuardedGapRetryWorkIsLinear(
            IntFunction { size: Int ->
                FindIterator {
                    pattern.matcher(
                        Utf8Input.trusted(("X" + "AAAA".repeat(size) + "RAREBBBy").toByteArray(StandardCharsets.UTF_8))
                    )
                        .find()
                }
            },
            "UTF-8"
        )
    }

    @Test
    fun reluctantGuardedTrailingGapFindAllWorkIsLinear() {
        val pattern = Pattern.compile("AAA[^;]*?")

        assertFindAllWorkIsLinear(
            IntFunction { size: Int ->
                val matcher = pattern.matcher("AAA".repeat(size))
                FindIterator { matcher.find() }
            },
            "String"
        )
        assertFindAllWorkIsLinear(
            IntFunction { size: Int ->
                val matcher =
                    pattern.matcher(Utf8Input.trusted("AAA".repeat(size).toByteArray(StandardCharsets.UTF_8)))
                FindIterator { matcher.find() }
            },
            "UTF-8"
        )
    }

    @Test
    fun reluctantGuardedAnchorSearchStopsAtDelimiter() {
        val pattern = Pattern.compile("AAA[^;]*?BBB")

        assertGuardedGapRetryWorkIsLinear(
            IntFunction { size: Int ->
                FindIterator {
                    pattern.matcher(("AAA" + "x".repeat(100) + ";").repeat(size) + "BBB").find()
                }
            },
            "String"
        )
        assertGuardedGapRetryWorkIsLinear(
            IntFunction { size: Int ->
                FindIterator {
                    pattern.matcher(
                        Utf8Input.trusted(
                            (("AAA" + "x".repeat(100) + ";").repeat(size) + "BBB").toByteArray(StandardCharsets.UTF_8)
                        )
                    )
                        .find()
                }
            },
            "UTF-8"
        )
    }

    /**
     * Searching a two-character class as one `String.indexOf` per member must not rescan the
     * rest of the input for a member that never occurs, on each `find()` or DFA restart.
     */
    @Test
    fun smallSetFindAllWorkIsLinearWhenOneMemberIsAbsent() {
        val ascii = Pattern.compile("[ab][0-9]")
        assertFindAllWorkIsLinear(IntFunction { size: Int ->
            FindIterator {
                ascii.matcher("b1 ".repeat(size)).find()
            }
        }, "ASCII pair")

        val citation = Pattern.compile(" ?[\\[\\uFF3B](?:(?:\\d+\\.){2,}\\d+(?:, )?)+[\\]\\uFF3D]")
        assertFindAllWorkIsLinear(
            IntFunction { size: Int -> FindIterator { citation.matcher("x \uFF3B1.2.3\uFF3D ".repeat(size)).find() } },
            "full-width citation"
        )
    }

    @Test
    fun smallSetReplaceAllWorkIsLinearWhenOneMemberIsAbsent() {
        val citation = Pattern.compile(" ?[\\[\\uFF3B](?:(?:\\d+\\.){2,}\\d+(?:, )?)+[\\]\\uFF3D]")
        val smallerWork =
            WorkCounter.countForTesting(
                Runnable { citation.matcher("x \uFF3B1.2.3\uFF3D ".repeat(200)).replaceAll("") })
        val largerWork =
            WorkCounter.countForTesting(
                Runnable { citation.matcher("x \uFF3B1.2.3\uFF3D ".repeat(1000)).replaceAll("") })

        Assertions.assertThat(smallerWork).isPositive()
        Assertions.assertThat(largerWork)
            .`as`("replaceAll over a two-character leading class should scale linearly")
            .isLessThan(smallerWork * 6)
    }

    /**
     * Like the tests above, but with candidates spaced farther apart than the scalar prologue of
     * `StringInputScanner.indexOfSmallSet`, so every search falls through to the per-member
     * searches. Unless those are window-bounded, each rescans the rest of the input for a member that
     * is absent or occurs only at the end.
     */
    @Test
    fun smallSetWorkIsLinearWhenCandidatesAreBeyondTheProbe() {
        val gap = "x".repeat(64)

        val ascii = Pattern.compile("[ab][0-9]")
        assertFindAllWorkIsLinear(
            IntFunction { size: Int -> FindIterator { ascii.matcher(("b1" + gap).repeat(size)).find() } },
            "ASCII pair, member absent"
        )
        assertFindAllWorkIsLinear(
            IntFunction { size: Int -> FindIterator { ascii.matcher(("b1" + gap).repeat(size) + "a").find() } },
            "ASCII pair, member late"
        )

        val citation = Pattern.compile(" ?[\\[\\uFF3B](?:(?:\\d+\\.){2,}\\d+(?:, )?)+[\\]\\uFF3D]")
        val fullWidth = "\uFF3B1.2.3\uFF3D" + gap
        assertFindAllWorkIsLinear(
            IntFunction { size: Int -> FindIterator { citation.matcher(fullWidth.repeat(size)).find() } },
            "full-width citation, '[' absent"
        )
        assertFindAllWorkIsLinear(
            IntFunction { size: Int -> FindIterator { citation.matcher(fullWidth.repeat(size) + "[").find() } },
            "full-width citation, '[' late"
        )
        // Full-width brackets with no digits: every candidate is rejected after the bracket.
        assertFindAllWorkIsLinear(
            IntFunction { size: Int -> FindIterator { citation.matcher(("\uFF3Bx\uFF3D" + gap).repeat(size)).find() } },
            "full-width brackets, no match"
        )

        for (suffix in arrayOf<String>("", "[")) {
            val smallerWork =
                WorkCounter.countForTesting(
                    Runnable { citation.matcher(fullWidth.repeat(200) + suffix).replaceAll("") })
            val largerWork =
                WorkCounter.countForTesting(
                    Runnable { citation.matcher(fullWidth.repeat(1000) + suffix).replaceAll("") })
            Assertions.assertThat(smallerWork).isPositive()
            Assertions.assertThat(largerWork)
                .`as`("replaceAll with spaced candidates and suffix '%s' should scale linearly", suffix)
                .isLessThan(smallerWork * 6)
        }
    }

    @Test
    fun variableGapCandidateFailuresRemainLinear() {
        val pattern = Pattern.compile("AAA[A-Z]+RAREST_TOKEN")

        val smallerWork =
            WorkCounter.countForTesting(
                Runnable {
                    Assertions.assertThat(pattern.matcher("AAA".repeat(2000) + "xRAREST_TOKEN").find())
                        .isFalse()
                })
        val largerWork =
            WorkCounter.countForTesting(
                Runnable {
                    Assertions.assertThat(pattern.matcher("AAA".repeat(10000) + "xRAREST_TOKEN").find())
                        .isFalse()
                })

        Assertions.assertThat(smallerWork).isPositive()
        Assertions.assertThat(largerWork)
            .`as`("variable-gap candidate failures must not rescan overlapping suffixes")
            .isLessThan(smallerWork * 6)
    }

    @Test
    fun multiAnchorCompilationDoesNotRepeatAstAnalysis() {
        val regexp = Parser.parse("foo.*bar.*baz", Pattern.toParseFlags(0))

        val analysisWork = WorkCounter.countForTesting(Runnable { MultiAnchorCompiler.analyze(regexp) })
        val compilationWork =
            WorkCounter.countForTesting(Runnable { MultiAnchorCompiler.compile(regexp, 0) })

        Assertions.assertThat(analysisWork).isPositive()
        Assertions.assertThat(compilationWork)
            .`as`(
                "Compilation work includes one analysis and descriptor assembly, "
                        + "analysisWork=%d compilationWork=%d",
                analysisWork, compilationWork
            )
            .isLessThan(analysisWork * 3)
    }

    @Test
    fun shiftDfaTransitionsAreCountedForStringInput() {
        val pattern = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*")
        val input = "a".repeat(10000)

        val work =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input).matches()).isTrue() })

        Assertions.assertThat(work).isGreaterThanOrEqualTo(input.length.toLong())
    }

    @Test
    fun shiftDfaTransitionsAreCountedForUtf8Input() {
        val pattern = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*")
        val input = "a".repeat(10000).toByteArray(StandardCharsets.UTF_8)

        val work =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher(Utf8Input.trusted(input)).matches()).isTrue() })

        Assertions.assertThat(work).isGreaterThanOrEqualTo(input.size.toLong())
    }

    @Test
    fun reverseDfaSuffixFailureIsConstantWorkForStringInput() {
        val pattern = Pattern.compile("[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$")
        assertReverseDfaSuffixFailureIsConstantWork(IntPredicate { size: Int ->
            pattern.matcher("a".repeat(size)).find()
        })
    }

    @Test
    fun reverseDfaSuffixFailureIsConstantWorkForUtf8Input() {
        val pattern = Pattern.compile("[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$")
        assertReverseDfaSuffixFailureIsConstantWork(
            IntPredicate { size: Int ->
                pattern.matcher(
                    Utf8Input.trusted(
                        "a".repeat(size).toByteArray(StandardCharsets.UTF_8)
                    )
                ).find()
            })
    }

    @Test
    fun greedyKeywordAlternationSuccessNearEndIsConstantWorkForUtf8Input() {
        val pattern = Pattern.compile("(?is).*\\b(you|your)\\b.*")
        assertConstantWork(
            IntPredicate { size: Int ->
                pattern
                    .matcher(Utf8Input.trusted(("a".repeat(size) + " YOUR tail").toByteArray(StandardCharsets.UTF_8)))
                    .find()
            },
            "UTF-8 keyword search"
        )
    }

    @Test
    fun disjointRequiredLiteralPrefilterIsLinearAcrossStringFindIteration() {
        val pattern = Pattern.compile("(?:banana\\d|apple\\d)")
        assertRepeatedFindWorkIsLinear(IntFunction { size: Int ->
            FindIterator {
                pattern.matcher("apple0 ".repeat(size)).find()
            }
        }, "String")
    }

    @Test
    fun disjointRequiredLiteralPrefilterIsLinearAcrossUtf8FindIteration() {
        val pattern = Pattern.compile("(?:banana\\d|apple\\d)")
        assertRepeatedFindWorkIsLinear(
            IntFunction { size: Int ->
                FindIterator {
                    pattern.matcher(
                        Utf8Input.trusted(
                            "apple0 ".repeat(size).toByteArray(
                                StandardCharsets.UTF_8
                            )
                        )
                    ).find()
                }
            },
            "UTF-8"
        )
    }

    @Test
    fun replaceAllWithoutCaptureReferencesSkipsCaptureResolutionWork() {
        val pattern = Pattern.compile("x(a+)y([0-9]+)z")
        val input = "xay1z xay2z"

        Assertions.assertThat(pattern.innerCapturesObserved()).isFalse()
        val replacedLiteral = pattern.matcher(input).replaceAll("REPLACED")
        Assertions.assertThat(replacedLiteral).isEqualTo("REPLACED REPLACED")
        Assertions.assertThat(pattern.innerCapturesObserved())
            .`as`("Literal replacement must not mark inner captures as observed")
            .isFalse()

        val replacedWithCaptures = pattern.matcher(input).replaceAll("$1-$2")
        Assertions.assertThat(replacedWithCaptures).isEqualTo("a-1 a-2")
        Assertions.assertThat(pattern.innerCapturesObserved())
            .`as`("Replacement with capture references must mark inner captures as observed")
            .isTrue()
    }

    @Test
    fun observedCaptureDemandAvoidsRepeatingExactFallbackSearch() {
        val input = "a".repeat(1024) + "y"
        for (options in arrayOf<EnginePathOptions>(
            EnginePathOptions.allEnabled(),
            EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build()
        )) {
            for (utf8 in booleanArrayOf(false, true)) {
                val pattern = Pattern.compile("(a?)*y", 0, options)
                val priming = pattern.matcher(input)
                Assertions.assertThat(priming.find()).isTrue()
                Assertions.assertThat(priming.group(1)).isEmpty()

                val captureStart: IntSupplier?
                val captureEnd: IntSupplier?
                if (utf8) {
                    val matcher = pattern.matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))
                    Assertions.assertThat(matcher.find()).isTrue()
                    captureStart = IntSupplier { matcher.start(1) }
                    captureEnd = IntSupplier { matcher.end(1) }
                } else {
                    val matcher = pattern.matcher(input)
                    Assertions.assertThat(matcher.find()).isTrue()
                    captureStart = IntSupplier { matcher.start(1) }
                    captureEnd = IntSupplier { matcher.end(1) }
                }
                val captureWork =
                    WorkCounter.countForTesting(
                        Runnable {
                            Assertions.assertThat(captureStart.getAsInt()).isEqualTo(1024)
                            Assertions.assertThat(captureEnd.getAsInt()).isEqualTo(1024)
                        })
                Assertions.assertThat(captureWork)
                    .`as`("Known-demand captures should already be available after exact fallback")
                    .isZero()
            }
        }
    }

    @Test
    fun observedCaptureDemandDoesNotMakeFindWalkTheInputWithTheCaptureEngine() {
        val regex = "(error:\\[)[A-Z](\\] code:500)"
        val input =
            ("2026-08-27 12:00:00 error:[x] code:500 msg:ok\n".repeat(9)
                    + "2026-08-27 12:00:00 error:[C] code:500 msg:crash\n")

        val withoutDemand = Pattern.compile(regex)
        val deferredWork =
            WorkCounter.countForTesting(Runnable {
                Assertions.assertThat(withoutDemand.matcher(input).find()).isTrue()
            })

        val withDemand = Pattern.compile(regex)
        val priming = withDemand.matcher(input)
        Assertions.assertThat(priming.find()).isTrue()
        Assertions.assertThat(priming.group(1)).isEqualTo("error:[")
        Assertions.assertThat(withDemand.innerCapturesObserved()).isTrue()

        val eagerWork =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(withDemand.matcher(input).find()).isTrue() })

        Assertions.assertThat(deferredWork).isPositive()
        Assertions.assertThat(eagerWork)
            .`as`(
                "Preferring the capture engine must not cost more than a bounded speculation on the"
                        + " accelerated start, deferredWork=%d eagerWork=%d",
                deferredWork, eagerWork
            )
            .isLessThan(deferredWork * 2)
    }

    @Test
    fun literalReplaceWithGroupZeroReferenceUsesFastPathWithLinearWork() {
        val pattern = Pattern.compile("(abc)")
        val input = "abc ".repeat(1000)

        val work =
            WorkCounter.countForTesting(
                Runnable {
                    val replaced = pattern.matcher(input).replaceAll("[$0]")
                    Assertions.assertThat(replaced).isNotNull()
                })

        Assertions.assertThat(work)
            .`as`("Literal replacement with group zero reference must scan the input only once")
            .isLessThanOrEqualTo(input.length.toLong())
    }

    @Test
    fun literalReplaceFirstReusesLatePreflightMatch() {
        val pattern = Pattern.compile("(needle)")
        val input = "x".repeat(10000) + "needle"

        val work =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher(input).replaceFirst("[$0]")).endsWith("[needle]") })

        Assertions.assertThat(work)
            .`as`("Literal replaceFirst must not rescan the prefix after finding the first match")
            .isLessThanOrEqualTo(input.length.toLong())
    }

    @Test
    fun literalSplitWithParenthesesUsesFastPathWithoutDfaWork() {
        val pattern = Pattern.compile("(delim)")
        val input = "item delim ".repeat(1000)

        val work =
            WorkCounter.countForTesting(
                Runnable {
                    val parts = pattern.split(input)
                    Assertions.assertThat<String?>(parts).hasSize(1001)
                })

        Assertions.assertThat(work)
            .`as`("Literal split on parenthesized pattern should execute on fast path without DFA work")
            .isEqualTo(0)
    }

    @Test
    fun caseInsensitivePrefixRepeatedFindIsLinearAcrossString() {
        val pattern = Pattern.compile("(?i)keyword_to_find")
        assertRepeatedFindWorkIsLinear(
            IntFunction { size: Int -> FindIterator { pattern.matcher("KEYWORD_TO_FIND ".repeat(size)).find() } },
            "String"
        )
    }

    @Test
    fun caseInsensitiveSingleCharacterRepeatedFindIsLinearAcrossString() {
        val pattern = Pattern.compile("(?i)z")
        assertRepeatedFindWorkIsLinear(IntFunction { size: Int ->
            FindIterator {
                pattern.matcher("z".repeat(size)).find()
            }
        }, "String")
    }

    @Test
    fun caseInsensitiveSparseFalseCandidatesAreLinearAcrossString() {
        val pattern = Pattern.compile("(?i)zq")
        val input = IntFunction { size: Int -> ("zX" + "a".repeat(32)).repeat(size) + "Zq" }

        val smallerWork: Long = countAllMatches(FindIterator { pattern.matcher(input.apply(100)).find() }, 1)
        val largerWork: Long = countAllMatches(FindIterator { pattern.matcher(input.apply(400)).find() }, 1)

        Assertions.assertThat(largerWork)
            .`as`("String sparse false-candidate work should scale linearly")
            .isLessThanOrEqualTo(smallerWork * 6)
    }

    @Test
    fun sparseLongLiteralFalseCandidatesAreLinearAcrossString() {
        val smallerWork: Long = sparseLongLiteralFalseCandidateWork(64)
        val largerWork: Long = sparseLongLiteralFalseCandidateWork(256)

        Assertions.assertThat(largerWork)
            .`as`("Sparse long-literal false-candidate work should scale linearly")
            .isLessThanOrEqualTo(smallerWork * 6)
    }

    @Test
    fun caseInsensitivePrefixRepeatedFindIsLinearAcrossUtf8() {
        val pattern = Pattern.compile("(?i)keyword_to_find")
        assertRepeatedFindWorkIsLinear(
            IntFunction { size: Int ->
                FindIterator {
                    pattern.matcher(
                        Utf8Input.trusted(
                            "KEYWORD_TO_FIND ".repeat(size).toByteArray(
                                StandardCharsets.UTF_8
                            )
                        )
                    )
                        .find()
                }
            },
            "UTF-8"
        )
    }

    @Test
    fun caseInsensitiveDensePrefixFailureIsLinearForStringInput() {
        val pattern =
            Pattern.compile("(?i)aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab")
        val input = "a".repeat(10000)
        val work =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input).find()).isFalse() })
        Assertions.assertThat(work)
            .`as`("Dense false candidate prefix verification on String must remain linearly bounded")
            .isLessThan(input.length * 3L)
    }

    @Test
    fun caseInsensitiveDensePrefixFailureIsLinearForUtf8Input() {
        val pattern =
            Pattern.compile("(?i)aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab")
        val input = "a".repeat(10000)
        val work =
            WorkCounter.countForTesting(
                Runnable {
                    Assertions.assertThat(
                        pattern.matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8))).find()
                    )
                        .isFalse()
                })
        Assertions.assertThat(work)
            .`as`("Dense false candidate prefix verification on UTF-8 must remain linearly bounded")
            .isLessThan(input.length * 3L)
    }

    @Test
    fun disjointRequiredLiteralOptimizationDoesNotAddRedundantUtf8Scans() {
        val regex = "(?:banana\\d|apple\\d)"
        val defaultPattern = Pattern.compile(regex)
        val withoutLiteralFastPaths =
            Pattern.compile(regex, 0, EnginePathOptions.builder().literalFastPaths(false).build())
        val input = Utf8Input.trusted("x".repeat(32768).toByteArray(StandardCharsets.UTF_8))

        val defaultWork: Long = countAllMatches(FindIterator { defaultPattern.matcher(input).find() }, 0)
        val fallbackWork: Long = countAllMatches(FindIterator { withoutLiteralFastPaths.matcher(input).find() }, 0)

        Assertions.assertThat(defaultWork)
            .`as`("UTF-8 literal optimization should not add full-input scans before the DFA")
            .isLessThanOrEqualTo(fallbackWork)
    }

    @Test
    fun disjointRequiredLiteralOptimizationDoesNotScanStartAnchoredInput() {
        val pattern = Pattern.compile("^(?:banana\\d|apple\\d)")

        val work =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher("x".repeat(32768)).find()).isFalse() })

        Assertions.assertThat(work)
            .`as`("start-anchored rejection should inspect only the viable start position")
            .isLessThan(100)
    }

    @Test
    fun startAnchoredLiteralFindRejectionIsConstantWorkForStringInput() {
        val pattern = Pattern.compile("^target")
        assertConstantRejectionWork(
            IntPredicate { size: Int -> pattern.matcher("x".repeat(size) + "target").find() }, "String literal"
        )
    }

    @Test
    fun startAnchoredLiteralFindRejectionIsConstantWorkForUtf8Input() {
        val pattern = Pattern.compile("^target")
        assertConstantRejectionWork(
            IntPredicate { size: Int ->
                pattern
                    .matcher(Utf8Input.trusted(("x".repeat(size) + "target").toByteArray(StandardCharsets.UTF_8)))
                    .find()
            },
            "UTF-8 literal"
        )
    }

    @Test
    fun startAnchoredCharClassFindRejectionIsConstantWorkForStringInput() {
        val pattern = Pattern.compile("^[a-z]")
        assertConstantRejectionWork(
            IntPredicate { size: Int -> pattern.matcher("9".repeat(size) + "a").find() }, "String char-class"
        )
    }

    @Test
    fun startAnchoredCharClassFindRejectionIsConstantWorkForUtf8Input() {
        val pattern = Pattern.compile("^[a-z]")
        assertConstantRejectionWork(
            IntPredicate { size: Int ->
                pattern.matcher(
                    Utf8Input.trusted(
                        ("9".repeat(size) + "a").toByteArray(
                            StandardCharsets.UTF_8
                        )
                    )
                ).find()
            },
            "UTF-8 char-class"
        )
    }

    @Test
    fun startAnchoredKeywordAlternationFindRejectionIsConstantWorkForStringInput() {
        val pattern = Pattern.compile("(?i)^(?:apple|banana)")
        assertConstantRejectionWork(
            IntPredicate { size: Int -> pattern.matcher("x".repeat(size) + "apple").find() }, "String keyword"
        )
    }

    @Test
    fun startAnchoredKeywordAlternationFindRejectionIsConstantWorkForUtf8Input() {
        val pattern = Pattern.compile("(?i)^(?:apple|banana)")
        assertConstantRejectionWork(
            IntPredicate { size: Int ->
                pattern.matcher(
                    Utf8Input.trusted(
                        ("x".repeat(size) + "apple").toByteArray(
                            StandardCharsets.UTF_8
                        )
                    )
                ).find()
            },
            "UTF-8 keyword"
        )
    }

    @Test
    fun startAnchoredFindFromNonZeroIsConstantWork() {
        val pattern = Pattern.compile("^abc")
        assertConstantRejectionWork(
            IntPredicate { size: Int -> pattern.matcher("abc" + "x".repeat(size)).find(1) }, "find(1) on ^abc"
        )
    }

    @Test
    fun startAnchoredRepeatedFindTerminatesInConstantWork() {
        val pattern = Pattern.compile("^abc")
        val work2000 =
            WorkCounter.countForTesting(
                Runnable {
                    val m = pattern.matcher("abc" + "x".repeat(2000))
                    var matches = 0
                    while (m.find()) {
                        matches++
                    }
                    Assertions.assertThat(matches).isEqualTo(1)
                })
        val work10000 =
            WorkCounter.countForTesting(
                Runnable {
                    val m = pattern.matcher("abc" + "x".repeat(10000))
                    var matches = 0
                    while (m.find()) {
                        matches++
                    }
                    Assertions.assertThat(matches).isEqualTo(1)
                })

        Assertions.assertThat(work2000).`as`("Short input repeated find work").isLessThan(50)
        Assertions.assertThat(work10000).`as`("Long input repeated find work").isLessThan(50)
        Assertions.assertThat(work10000)
            .`as`("Subsequent find() on start-anchored pattern must not scale with input size")
            .isLessThanOrEqualTo(max(10, work2000 * 2))
    }

    @Test
    fun disjointRequiredLiteralCandidateIsScannedOnlyOnce() {
        val pattern = Pattern.compile(".*(?:apple|banana|cherry).*")
        val input = "x".repeat(32768) + "cherry"

        val work =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input).find()).isTrue() })

        Assertions.assertThat(work)
            .`as`("a positive disjoint-literal candidate should not repeat every full-input scan")
            .isLessThan(input.length * 5L)
    }

    @Test
    fun fixedOffsetLiteralSelectsRareTokenToAvoidCandidateVerificationWork() {
        // "____" has length 4 with common underscores.
        // "zq" has length 2 with rare letters 'z' and 'q'.
        val pattern = Pattern.compile("[0-9]{2}____[a-z]zq[a-z]")
        val input = "user_name_field_data____common_suffix\n".repeat(1000)

        val work =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input).find()).isFalse() })

        Assertions.assertThat(work)
            .`as`("RarityOracle selection must avoid false candidate verification work on common tokens")
            .isLessThanOrEqualTo((input.length + 100).toLong())
    }

    @Test
    fun requiredLiteralSelectsRareTokenToRejectNoiseWithMinimalWork() {
        // "____________" has length 12 with common underscores.
        // "404_ERR" has length 7 with high-rarity digits and uppercase letters.
        val pattern = Pattern.compile(".*(____________).*?(404_ERR).*")
        val input = "log_entry_line_with____________separators_and_delimiters\n".repeat(1000)

        val work =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input).find()).isFalse() })

        Assertions.assertThat(work)
            .`as`("Required literal prefilter must reject on the selective token with minimal work")
            .isLessThanOrEqualTo((input.length + 100).toLong())
    }

    @Test
    fun exactCaseFixedOffsetSelectsUppercaseAnchorToAvoidCandidateWork() {
        // "AbstractBeanFactory" contains uppercase 'B' and 'F'.
        // The input is filled with lowercase vowels and common consonants ('e', 'a', 't', 'r', 's',
        // 'c').
        val pattern = Pattern.compile("[0-9]{3}AbstractBeanFactory[0-9]{3}")
        val input = "the create starter test transaction context service\n".repeat(1000)

        val work =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input).find()).isFalse() })

        Assertions.assertThat(work)
            .`as`(
                "Exact-case RarityOracle must anchor on uppercase letters to avoid false candidates in"
                        + " lowercase text"
            )
            .isLessThanOrEqualTo((input.length + 100).toLong())
    }

    @Test
    fun caseInsensitiveStartAccelerationIsInvariantToPatternCapitalization() {
        val pLower = Pattern.compile("(?i)userid")
        val pUpper = Pattern.compile("(?i)USERID")
        val pMixed = Pattern.compile("(?i)UserId")
        val input = "the_quick_brown_fox_jumps_over_the_lazy_dog\n".repeat(500)

        val workLower =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pLower.matcher(input).find()).isFalse() })
        val workUpper =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pUpper.matcher(input).find()).isFalse() })
        val workMixed =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pMixed.matcher(input).find()).isFalse() })

        Assertions.assertThat(workLower)
            .`as`(
                "Case-folded RarityOracle must produce identical work counts regardless of pattern"
                        + " casing"
            )
            .isEqualTo(workUpper)
            .isEqualTo(workMixed)
    }

    @Test
    fun requiredInfixLiteralRejectsDensePrefixNoiseWithSinglePassWork() {
        // Prefix "{Link:" is common (appears 1,000 times).
        // Infix "<<!nav>>" is rare and absent.
        val pattern = Pattern.compile("(\\{Link:[^}]*?)<<!nav>>([^}]*?\\})")
        val input = "{Link: target=home_page, category=general, priority=high}\n".repeat(1000)
        val utf8 = Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8))

        Assertions.assertThat(
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input).find()).isFalse() })
        )
            .`as`("String Matcher.find must reject dense-prefix noise in a single pass")
            .isLessThanOrEqualTo((input.length + 100).toLong())
        Assertions.assertThat(
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(utf8).find()).isFalse() })
        )
            .`as`("Utf8Matcher.find must reject dense-prefix noise in a single pass")
            .isLessThanOrEqualTo((input.length + 100).toLong())
        Assertions.assertThat(WorkCounter.countForTesting(Runnable {
            Assertions.assertThat(pattern.find(utf8)).isFalse()
        }))
            .`as`("Pattern.find(Utf8Input) must reject dense-prefix noise in a single pass")
            .isLessThanOrEqualTo((input.length + 100).toLong())
        Assertions.assertThat(
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.asPredicate().test(input)).isFalse() })
        )
            .`as`("Pattern.asPredicate must reject dense-prefix noise in a single pass")
            .isLessThanOrEqualTo((input.length + 100).toLong())
    }

    @Test
    fun literalStartAcceleratorAvoidsRedundantInfixPrefilterScan() {
        for (regex in arrayOf<String>("status=3\\d\\d took=(\\d+)ms", "[ab]status=3\\d\\d took=(\\d+)ms")) {
            val pattern = Pattern.compile(regex)
            // On a short input, the required infix literal (" took=") appears halfway through while the
            // start literal ("status=3") is absent. Running the infix prefilter before start
            // acceleration would scan [0, 200] for " took=" and then rescan the whole input for
            // "status=3".
            val missingPrefix = "x".repeat(200) + " took=12ms\n" + "y".repeat(200)
            val missingPrefixUtf8 = Utf8Input.trusted(missingPrefix.toByteArray(StandardCharsets.UTF_8))

            Assertions.assertThat(
                WorkCounter.countForTesting(
                    Runnable { Assertions.assertThat(pattern.matcher(missingPrefix).find()).isFalse() })
            )
                .`as`("%s String Matcher.find should scan once when start literal is absent", regex)
                .isLessThanOrEqualTo((missingPrefix.length + 64).toLong())
            Assertions.assertThat(
                WorkCounter.countForTesting(
                    Runnable { Assertions.assertThat(pattern.matcher(missingPrefixUtf8).find()).isFalse() })
            )
                .`as`("%s Utf8Matcher.find should scan once when start literal is absent", regex)
                .isLessThanOrEqualTo((missingPrefix.length + 64).toLong())
            Assertions.assertThat(
                WorkCounter.countForTesting(
                    Runnable { Assertions.assertThat(pattern.find(missingPrefixUtf8)).isFalse() })
            )
                .`as`("%s Pattern.find(Utf8Input) should scan once when start literal is absent", regex)
                .isLessThanOrEqualTo((missingPrefix.length + 64).toLong())
            Assertions.assertThat(
                WorkCounter.countForTesting(
                    Runnable { Assertions.assertThat(pattern.asPredicate().test(missingPrefix)).isFalse() })
            )
                .`as`("%s Pattern.asPredicate should scan once when start literal is absent", regex)
                .isLessThanOrEqualTo((missingPrefix.length + 64).toLong())

            // Longer inputs keep the single-pass infix prefilter first: when the start literal appears at
            // 4,000 and repeats densely without the infix literal, the prefilter rejects in one pass.
            val lateDensePrefix = "x".repeat(4000) + "astatus=300 nope\n".repeat(250)
            val lateDensePrefixUtf8 = Utf8Input.trusted(lateDensePrefix.toByteArray(StandardCharsets.UTF_8))

            Assertions.assertThat(
                WorkCounter.countForTesting(
                    Runnable { Assertions.assertThat(pattern.matcher(lateDensePrefix).find()).isFalse() })
            )
                .`as`("%s String Matcher.find should reject long dense prefix noise in one pass", regex)
                .isLessThanOrEqualTo((lateDensePrefix.length + 64).toLong())
            Assertions.assertThat(
                WorkCounter.countForTesting(
                    Runnable { Assertions.assertThat(pattern.matcher(lateDensePrefixUtf8).find()).isFalse() })
            )
                .`as`("%s Utf8Matcher.find should reject long dense prefix noise in one pass", regex)
                .isLessThanOrEqualTo((lateDensePrefix.length + 64).toLong())
            Assertions.assertThat(
                WorkCounter.countForTesting(
                    Runnable { Assertions.assertThat(pattern.find(lateDensePrefixUtf8)).isFalse() })
            )
                .`as`("%s Pattern.find(Utf8Input) should reject long dense prefix noise in one pass", regex)
                .isLessThanOrEqualTo((lateDensePrefix.length + 64).toLong())
            Assertions.assertThat(
                WorkCounter.countForTesting(
                    Runnable { Assertions.assertThat(pattern.asPredicate().test(lateDensePrefix)).isFalse() })
            )
                .`as`("%s Pattern.asPredicate should reject long dense prefix noise in one pass", regex)
                .isLessThanOrEqualTo((lateDensePrefix.length + 64).toLong())
        }

        // Overlapping start and required literals across short and long windows.
        val overlapping = Pattern.compile("abc[0-9]?bcd")
        for (prefix in arrayOf<String>("", "z".repeat(1000))) {
            for (tail in arrayOf<String>("", "z".repeat(1000))) {
                for (hit in arrayOf<String>("abcbcd", "abc7bcd")) {
                    val s = prefix + hit + tail
                    val u = Utf8Input.trusted(s.toByteArray(StandardCharsets.UTF_8))
                    Assertions.assertThat(overlapping.matcher(s).find()).isTrue()
                    Assertions.assertThat(overlapping.matcher(u).find()).isTrue()
                    Assertions.assertThat(overlapping.find(u)).isTrue()
                    Assertions.assertThat(overlapping.asPredicate().test(s)).isTrue()
                }
                val miss = prefix + "abc7bcz" + tail
                val uMiss = Utf8Input.trusted(miss.toByteArray(StandardCharsets.UTF_8))
                Assertions.assertThat(overlapping.matcher(miss).find()).isFalse()
                Assertions.assertThat(overlapping.matcher(uMiss).find()).isFalse()
                Assertions.assertThat(overlapping.find(uMiss)).isFalse()
                Assertions.assertThat(overlapping.asPredicate().test(miss)).isFalse()
            }
        }

        // When the start literal is case-folded or has a much weaker anchor than the required infix
        // literal, RejectPrefilter runs first so frequent false start-anchor hits do not add work.
        val weakPrefix = Pattern.compile("er\\d+<<!nav>>\\d+")
        val denseR = "re ra ri ro ru rt rg ".repeat(400)
        val denseRUtf8 = Utf8Input.trusted(denseR.toByteArray(StandardCharsets.UTF_8))
        Assertions.assertThat(
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(weakPrefix.matcher(denseR).find()).isFalse() })
        )
            .isLessThanOrEqualTo((denseR.length + 32).toLong())
        Assertions.assertThat(
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(weakPrefix.matcher(denseRUtf8).find()).isFalse() })
        )
            .isLessThanOrEqualTo((denseR.length + 32).toLong())
        Assertions.assertThat(WorkCounter.countForTesting(Runnable {
            Assertions.assertThat(weakPrefix.find(denseRUtf8)).isFalse()
        }))
            .isLessThanOrEqualTo((denseR.length + 32).toLong())
        Assertions.assertThat(
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(weakPrefix.asPredicate().test(denseR)).isFalse() })
        )
            .isLessThanOrEqualTo((denseR.length + 32).toLong())
    }

    @Test
    fun requiredInfixLiteralRejectsDensePrefixNoiseInSplitWithSinglePassWork() {
        val pattern = Pattern.compile("(\\{Link:[^}]*?)<<!nav>>([^}]*?\\})")
        val input = "{Link: target=home_page, category=general, priority=high}\n".repeat(1000)

        val work =
            WorkCounter.countForTesting(Runnable {
                Assertions.assertThat<String?>(pattern.split(input)).containsExactly(input)
            })

        Assertions.assertThat(work)
            .`as`("Split must reject dense-prefix noise in a single pass without DFA churn")
            .isLessThanOrEqualTo((input.length + 100).toLong())
    }

    @Test
    fun literalSelectivityScoringIsLinearInPatternSize() {
        val smallerWork = WorkCounter.countForTesting(Runnable { Pattern.compile(selectivityPattern(100)) })
        val largerWork = WorkCounter.countForTesting(Runnable { Pattern.compile(selectivityPattern(400)) })

        Assertions.assertThat(largerWork)
            .`as`("Literal selectivity scoring should scale linearly with pattern size")
            .isLessThanOrEqualTo(smallerWork * 6)
    }

    @Test
    fun cachedDriverSelectionCompilationIsLinearInSegmentCount() {
        val smallerWork =
            WorkCounter.countForTesting(Runnable { Pattern.compile(driverSelectionPattern(50)) })
        val largerWork =
            WorkCounter.countForTesting(Runnable { Pattern.compile(driverSelectionPattern(200)) })

        Assertions.assertThat(largerWork)
            .`as`("Cached driver selection should scale linearly with the number of segments")
            .isLessThanOrEqualTo(smallerWork * 6)
    }

    @Test
    fun caseInsensitiveSingleCharRejectionIsSinglePass() {
        val pattern = Pattern.compile("(?i)z")
        val size = 10000
        val input = "a".repeat(size)

        val work =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input).find()).isFalse() })

        Assertions.assertThat(work)
            .`as`("Case-insensitive single char search must inspect text in a single pass")
            .isEqualTo(size.toLong())
    }

    @Test
    fun caseInsensitiveDenseFalseCandidatesScaleLinearly() {
        // Pattern has anchor 'a' matching every position, but candidate fails on second char 'b'
        val pattern = Pattern.compile("(?i)ab")

        val work2000 =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher("a".repeat(2000)).find()).isFalse() })
        val work10000 =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher("a".repeat(10000)).find()).isFalse() })

        // Without KMP fallback, work would be 2 * N. With KMP fallback after work exhaustion,
        // it must remain strictly linear (work(10000) <= work(2000) * 6).
        Assertions.assertThat(work10000)
            .`as`("Dense false candidate verification must fall back to linear KMP")
            .isLessThanOrEqualTo(work2000 * 6)
    }

    @Test
    fun caseInsensitivePairDenseFalseCandidatesScaleLinearlyForUtf8Input() {
        val pattern = Pattern.compile("(?i)keyword_to_find")
        val input2000 = "kind knowledge token lock keep track ".repeat(50).toByteArray(StandardCharsets.UTF_8)
        val input10000 = "kind knowledge token lock keep track ".repeat(250).toByteArray(StandardCharsets.UTF_8)

        val work2000 =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher(Utf8Input.trusted(input2000)).find()).isFalse() })
        val work10000 =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher(Utf8Input.trusted(input10000)).find()).isFalse() })

        Assertions.assertThat(work10000)
            .`as`("UTF-8 case-insensitive false candidate search must remain linearly bounded")
            .isLessThanOrEqualTo(work2000 * 6)
    }

    @Test
    fun caseInsensitiveLiteralFindWorkIsLinear() {
        val pattern = Pattern.compile("(?i)keyword_to_find")

        assertRepeatedFindWorkIsLinear(
            IntFunction { size: Int -> FindIterator { pattern.matcher("KEYWORD_TO_FIND ".repeat(size)).find() } },
            "Case-insensitive literal find"
        )
    }

    @Test
    fun utf8RareByteLiteralPrefixFindWorkIsLinearAcrossFalseCandidates() {
        val pattern = Pattern.compile("aaaaQaaaa[0-9]+")
        val smaller = "aaaaQaaaaX".repeat(500).toByteArray(StandardCharsets.UTF_8)
        val larger = "aaaaQaaaaX".repeat(2000).toByteArray(StandardCharsets.UTF_8)

        val smallerWork =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher(Utf8Input.trusted(smaller)).find()).isFalse() })
        val largerWork =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher(Utf8Input.trusted(larger)).find()).isFalse() })

        Assertions.assertThat(largerWork)
            .`as`("UTF-8 literal-prefix search must scale with repeated false candidates")
            .isLessThanOrEqualTo(smallerWork * 5)
    }

    @Test
    fun preselectedUtf8DfaCandidateSkipsRedundantStartScan() {
        val pattern = Pattern.compile("\\d{3}/\\d{3}/\\d{4}")
        val bytes = ("123/456/7890" + "x".repeat(100)).toByteArray(StandardCharsets.UTF_8)
        val scanner = Utf8InputScanner(bytes, 0, bytes.size)
        val candidate =
            Utf8StartAccelerator.findNextCandidate(pattern.utf8StartAccelerator(), scanner, 0)
        val dfa = pattern.forwardFirstMatchDfa()

        Assertions.assertThat(candidate).isZero()
        dfa.doSearch(scanner, candidate, false, false, false)
        val ordinaryWork =
            WorkCounter.countForTesting(
                Runnable {
                    Assertions.assertThat(dfa.doSearch(scanner, candidate, false, false, false).matched)
                        .isTrue()
                })
        val preselectedWork =
            WorkCounter.countForTesting(
                Runnable {
                    Assertions.assertThat(dfa.doSearch(scanner, candidate, false, false, true).matched)
                        .isTrue()
                })

        Assertions.assertThat(preselectedWork)
            .`as`("DFA must trust a candidate already selected by the caller")
            .isLessThan(ordinaryWork)
    }

    private fun interface FindIterator {
        fun find(): Boolean
    }

    @Test
    fun stateAcceleratorEscapedQuoteScanIsLinearForStringInput() {
        val pattern = Pattern.compile("\"[^\"]*\"")
        val input2000 = "\"" + "a".repeat(2000) + "\""
        val input10000 = "\"" + "a".repeat(10000) + "\""

        val work2000 =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input2000).find()).isTrue() })
        val work10000 =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input10000).find()).isTrue() })

        Assertions.assertThat(work10000)
            .`as`("DFA self-loop state accelerator on String should scale linearly with input size")
            .isLessThan(work2000 * 6)
    }

    @Test
    fun stateAcceleratorEscapedQuoteScanIsLinearForUtf8Input() {
        val pattern = Pattern.compile("\"[^\"]*\"")
        val input2000 = ("\"" + "a".repeat(2000) + "\"").toByteArray(StandardCharsets.UTF_8)
        val input10000 = ("\"" + "a".repeat(10000) + "\"").toByteArray(StandardCharsets.UTF_8)

        val work2000 =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher(Utf8Input.trusted(input2000)).find()).isTrue() })
        val work10000 =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher(Utf8Input.trusted(input10000)).find()).isTrue() })

        Assertions.assertThat(work10000)
            .`as`("DFA self-loop state accelerator on UTF-8 should scale linearly with input size")
            .isLessThan(work2000 * 6)
    }

    @Test
    fun stateAcceleratorEscapedNewlineScanIsLinearForStringInput() {
        val pattern = Pattern.compile("[^,\\n]*\\n")
        val input2000 = "a".repeat(2000) + "\n"
        val input10000 = "a".repeat(10000) + "\n"

        val work2000 =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input2000).find()).isTrue() })
        val work10000 =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input10000).find()).isTrue() })

        Assertions.assertThat(work10000)
            .`as`("DFA self-loop state accelerator with newline on String should scale linearly")
            .isLessThan(work2000 * 6)
    }

    @Test
    fun dfaReacceleratesStartStateAfterFalseCandidateWorkIsLinear() {
        val pattern = Pattern.compile("fo[0-9]+")
        val input2000 = "foox" + "a".repeat(2000) + "fo123"
        val input10000 = "foox" + "a".repeat(10000) + "fo123"

        val work2000 =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input2000).find()).isTrue() })
        val work10000 =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input10000).find()).isTrue() })

        Assertions.assertThat(work10000)
            .`as`("DFA start state re-acceleration work must scale linearly with non-matching span")
            .isLessThan(work2000 * 6)
    }

    @Test
    fun dfaInteriorSelfLoopEscapeAccelerationIsLinearOnWarmDfa() {
        val pattern = Pattern.compile("\"[^\"]*\"")
        val input2000 = "\"" + "a".repeat(2000) + "\""
        val input10000 = "\"" + "a".repeat(10000) + "\""
        val dfa = pattern.forwardFirstMatchDfa()

        // Warm up DFA transitions
        dfa.doSearch(StringInputScanner(input2000), 0, false, false)

        val work2000 =
            WorkCounter.countForTesting(
                Runnable {
                    Assertions.assertThat(
                        dfa.doSearch(StringInputScanner(input2000), 0, false, false).matched
                    )
                        .isTrue()
                })
        val work10000 =
            WorkCounter.countForTesting(
                Runnable {
                    Assertions.assertThat(
                        dfa.doSearch(StringInputScanner(input10000), 0, false, false).matched
                    )
                        .isTrue()
                })

        Assertions.assertThat(work10000)
            .`as`("Warm DFA should scale linearly on interior self-loop escape")
            .isLessThan(work2000 * 6)
    }

    @Test
    fun singleCharClassFindFastPathIsLinearForStringInput() {
        val pattern = Pattern.compile("\\d")
        val input2000 = "a".repeat(2000)
        val input10000 = "a".repeat(10000)

        val work2000 =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input2000).find()).isFalse() })
        val work10000 =
            WorkCounter.countForTesting(Runnable {
                Assertions.assertThat(pattern.matcher(input10000).find()).isFalse()
            })

        Assertions.assertThat(work10000)
            .`as`("Single character class find on String should scale linearly")
            .isLessThan(work2000 * 6)
    }

    @Test
    fun singleCharClassFindFastPathIsLinearForUtf8Input() {
        val pattern = Pattern.compile("\\d")
        val input2000 = "a".repeat(2000).toByteArray(StandardCharsets.UTF_8)
        val input10000 = "a".repeat(10000).toByteArray(StandardCharsets.UTF_8)

        val work2000 =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher(Utf8Input.trusted(input2000)).find()).isFalse() })
        val work10000 =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher(Utf8Input.trusted(input10000)).find()).isFalse() })

        Assertions.assertThat(work10000)
            .`as`("Single character class find on UTF-8 should scale linearly")
            .isLessThan(work2000 * 6)
    }

    @Test
    fun negatedSingleCharClassFindFastPathIsLinearForStringInput() {
        val pattern = Pattern.compile("[^a-z]")
        val input2000 = "a".repeat(2000)
        val input10000 = "a".repeat(10000)

        val work2000 =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input2000).find()).isFalse() })
        val work10000 =
            WorkCounter.countForTesting(Runnable {
                Assertions.assertThat(pattern.matcher(input10000).find()).isFalse()
            })

        Assertions.assertThat(work10000)
            .`as`("Negated character class find on String should scale linearly")
            .isLessThan(work2000 * 6)
    }

    @Test
    fun searchScalingMaintainsParityAcrossLatin1Utf16AndUtf8() {
        val pattern = Pattern.compile("[0-9]{3}-[A-Z]{3}")
        val size = 5000
        val latin1 = "abc ".repeat(size / 4)
        // Include a non-Latin1 character at the start so coder becomes UTF16
        val utf16 = "\u4e2d" + latin1.substring(1)
        val utf8 = latin1.toByteArray(StandardCharsets.UTF_8)

        val workLatin1 =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(latin1).find()).isFalse() })
        val workUtf16 =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(utf16).find()).isFalse() })
        val workUtf8 =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher(Utf8Input.trusted(utf8)).find()).isFalse() })

        Assertions.assertThat(workLatin1).`as`("Latin-1 search work").isLessThan(size * 4L)
        Assertions.assertThat(workUtf16)
            .`as`("UTF-16 search work should remain within a reasonable factor of Latin-1")
            .isLessThan(workLatin1 * 4L + 100)
        Assertions.assertThat(workUtf8)
            .`as`("UTF-8 search work should remain within a reasonable factor of Latin-1")
            .isLessThan(workLatin1 * 4L + 100)
    }

    @Test
    fun vectorAndSwarScansFindMatchesAcrossChunkBoundaries() {
        val ranges = intArrayOf('0'.code, '9'.code)
        for (len in 1..65) {
            val absent = "a".repeat(len).toByteArray(StandardCharsets.UTF_8)
            val matchAtEnd = absent.clone()
            matchAtEnd[len - 1] = '5'.code.toByte()

            Assertions.assertThat(ByteSwarScan.indexOfAsciiClass(absent, 0, len, ranges, 0))
                .`as`("SWAR absent result for length %d", len)
                .isEqualTo(-1)
            Assertions.assertThat(ByteSwarScan.indexOfAsciiClass(matchAtEnd, 0, len, ranges, 0))
                .`as`("SWAR end match for length %d", len)
                .isEqualTo(len - 1)
            Assertions.assertThat(ByteSwarScan.indexOfBytePair(absent, 0, len, 'y'.code.toByte(), 'z'.code.toByte(), 0))
                .`as`("SWAR pair absent result for length %d", len)
                .isEqualTo(-1)
            Assertions.assertThat(
                ByteSwarScan.indexOfByteTriple(
                    absent,
                    0,
                    len,
                    'x'.code.toByte(),
                    'y'.code.toByte(),
                    'z'.code.toByte(),
                    0
                )
            )
                .`as`("SWAR triple absent result for length %d", len)
                .isEqualTo(-1)

            if (isVectorApiAvailable) {
                Assertions.assertThat(ByteVectorScan.indexOfAsciiClass(absent, 0, len, ranges, 0))
                    .`as`("vector absent result for length %d", len)
                    .isEqualTo(-1)
                Assertions.assertThat(ByteVectorScan.indexOfAsciiClass(matchAtEnd, 0, len, ranges, 0))
                    .`as`("vector end match for length %d", len)
                    .isEqualTo(len - 1)
                Assertions.assertThat(
                    ByteVectorScan.indexOfBytePair(
                        absent,
                        0,
                        len,
                        'y'.code.toByte(),
                        'z'.code.toByte(),
                        0
                    )
                )
                    .`as`("vector pair absent result for length %d", len)
                    .isEqualTo(-1)
                Assertions.assertThat(
                    ByteVectorScan.indexOfByteTriple(
                        absent, 0, len, 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte(), 0
                    )
                )
                    .`as`("vector triple absent result for length %d", len)
                    .isEqualTo(-1)
            }
        }
    }

    @Test
    fun startAnchoredLiteralRejectsDisplacedCandidateWithConstantWorkForString() {
        val pattern = Pattern.compile("^abc")
        val displaced = "x".repeat(10000) + "abc"
        val work =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(displaced).find()).isFalse() })
        Assertions.assertThat(work)
            .`as`("Start-anchored pattern with displaced candidate must reject in constant work")
            .isLessThan(20)
    }

    @Test
    fun startAnchoredLiteralRejectsDisplacedCandidateWithConstantWorkForUtf8() {
        val pattern = Pattern.compile("^abc")
        val displaced = ("x".repeat(10000) + "abc").toByteArray(StandardCharsets.UTF_8)
        val work =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher(Utf8Input.trusted(displaced)).find()).isFalse() })
        Assertions.assertThat(work)
            .`as`("Start-anchored UTF-8 pattern with displaced candidate must reject in constant work")
            .isLessThan(20)
    }

    @Test
    fun startAnchoredCharClassRejectsDisplacedCandidateWithConstantWork() {
        val pattern = Pattern.compile("^[0-9]")
        val displaced = "a".repeat(10000) + "5"
        val work =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(displaced).find()).isFalse() })
        Assertions.assertThat(work)
            .`as`("Start-anchored char-class with displaced match must reject in constant work")
            .isLessThan(20)
    }

    @Test
    fun repeatedFindWithAlternatingCaseIsLinearForString() {
        val pattern = Pattern.compile("(?i)needle")
        assertRepeatedFindWorkIsLinear(
            IntFunction { size: Int ->
                val sb = StringBuilder()
                val variants = arrayOf<String?>("Needle ", "needle ", "NEEDLE ", "nEeDlE ")
                for (i in 0..<size) {
                    sb.append(variants[i % variants.size])
                }
                FindIterator { pattern.matcher(sb.toString()).find() }
            },
            "String"
        )
    }

    @Test
    fun repeatedFindWithAlternatingCaseIsLinearForUtf8() {
        val pattern = Pattern.compile("(?i)needle")
        assertRepeatedFindWorkIsLinear(
            IntFunction { size: Int ->
                val sb = StringBuilder()
                val variants = arrayOf<String?>("Needle ", "needle ", "NEEDLE ", "nEeDlE ")
                for (i in 0..<size) {
                    sb.append(variants[i % variants.size])
                }
                FindIterator {
                    pattern.matcher(Utf8Input.trusted(sb.toString().toByteArray(StandardCharsets.UTF_8))).find()
                }
            },
            "UTF-8"
        )
    }

    @Test
    fun repeatedFindWithMultiBranchAlternationIsLinear() {
        val pattern = Pattern.compile("(?:apple|banana|cherry|date)")
        assertRepeatedFindWorkIsLinear(
            IntFunction { size: Int ->
                val sb = StringBuilder()
                val variants = arrayOf<String?>("apple ", "banana ", "cherry ", "date ")
                for (i in 0..<size) {
                    sb.append(variants[i % variants.size])
                }
                FindIterator { pattern.matcher(sb.toString()).find() }
            },
            "String"
        )
    }

    @Test
    fun classHashChainAchievesSublinearWorkOnCaseInsensitiveLiteralForStringInput() {
        val chc = ClassHashChain.compileCaseInsensitive("content_length_header")
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(5) // 225 chars
        val work =
            WorkCounter.countForTesting(
                Runnable {
                    Assertions.assertThat(chc.search(text, 0, WorkLimit.forRemaining(text.length)))
                        .isEqualTo(-1)
                })

        // 225 / 6 = ~37 operations (vs 225 operations for linear scan)
        Assertions.assertThat(work)
            .`as`(
                "Class-HashChain must perform sublinear work on case-insensitive patterns for String"
                        + " input"
            )
            .isLessThanOrEqualTo((text.length / 6 + 10).toLong())
    }

    @Test
    fun classHashChainAchievesSublinearWorkOnNonAsciiCaseInsensitiveLiteralForStringInput() {
        val chc = ClassHashChain.compileCaseInsensitive("конфигурация_сервера") // M = 20
        val text = "текст_без_совпадений_для_проверки_производительности_".repeat(5) // 270 chars
        val work =
            WorkCounter.countForTesting(
                Runnable {
                    Assertions.assertThat(chc.search(text, 0, WorkLimit.forRemaining(text.length)))
                        .isEqualTo(-1)
                })

        // Sublinear bound: 270 / 19 = ~14 operations (vs 270 for linear scan)
        Assertions.assertThat(work)
            .`as`(
                "ClassHashChain must perform sublinear work on non-ASCII case-insensitive patterns"
                        + " for String input"
            )
            .isLessThanOrEqualTo((text.length / 15 + 10).toLong())
    }

    @Test
    fun unicodeCaseInsensitiveUtf8LiteralFilterIsLinearOnDenseFalseCandidates() {
        val pattern = Pattern.compile("(?iu)Шерлок Холмс")
        for (falseCandidate in arrayOf<String>("шЕРЛОК ХолмX ", "ШЕРЛОК ХОЛМX ")) {
            val shortInput = Utf8Input.validated(falseCandidate.repeat(500).toByteArray(StandardCharsets.UTF_8))
            val longInput = Utf8Input.validated(falseCandidate.repeat(2500).toByteArray(StandardCharsets.UTF_8))

            val shortWork =
                WorkCounter.countForTesting(
                    Runnable { Assertions.assertThat(pattern.matcher(shortInput).find()).isFalse() })
            val longWork =
                WorkCounter.countForTesting(
                    Runnable { Assertions.assertThat(pattern.matcher(longInput).find()).isFalse() })

            Assertions.assertThat(longWork)
                .`as`("Unicode-folded UTF-8 filtering should scale linearly for %s", falseCandidate)
                .isLessThan(shortWork * 6)
        }
    }

    @Test
    fun unicodeCaseInsensitiveUtf8LiteralFilterIsLinearAcrossSuccessfulFinds() {
        val pattern = Pattern.compile("(?iu)Шx[0-9]")
        for (match in arrayOf<String>("Шx1 ", "шx1 ")) {
            assertRepeatedFindWorkIsLinear(
                IntFunction { size: Int ->
                    FindIterator {
                        pattern.matcher(
                            Utf8Input.validated(
                                match.repeat(size).toByteArray(
                                    StandardCharsets.UTF_8
                                )
                            )
                        ).find()
                    }
                },
                "Unicode-folded UTF-8 " + match
            )
        }
    }

    @Test
    fun hybridCaseInsensitiveSearchIsImmuneToFalseAnchorStormsForStringInput() {
        val pattern = Pattern.compile("(?i)keyword_to_find") // anchor is 'k' / 'K'
        val text = "k_other_words_".repeat(20) // 280 chars with 20 'k' false anchors
        val work =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(text).find()).isFalse() })

        // shiftAt skips forward on false anchors, keeping work well below quadratic O(N * M)
        Assertions.assertThat(work)
            .`as`("Hybrid search with shiftAt must avoid quadratic work on false anchor floods")
            .isLessThanOrEqualTo((text.length * 2).toLong())
    }

    @Test
    fun unicodeCaseInsensitiveLinearChainUsesOnePassForSubmatchExtraction() {
        // (?iu) triggers inst.foldCase = true on literal runes (e.g. Kelvin sign K <-> K <-> k)
        val pattern = Pattern.compile("(?iu)key:([0-9]+)")
        Assertions.assertThat(pattern.canOnePassSubmatch()).isTrue()
        Assertions.assertThat<OnePass?>(pattern.onePass()).isNotNull()

        val input = "prefix noise KEY:98765 trailing text"
        val matcher = pattern.matcher(input)
        Assertions.assertThat(matcher.find()).isTrue()
        val groupReadWork =
            WorkCounter.countForTesting(
                Runnable {
                    Assertions.assertThat(matcher.group(0)).isEqualTo("KEY:98765")
                    Assertions.assertThat(matcher.group(1)).isEqualTo("98765")
                })
        Assertions.assertThat(groupReadWork)
            .`as`(
                "Unicode case-insensitive linear chain submatch extraction must run in OnePass with"
                        + " zero NFA allocations"
            )
            .isLessThanOrEqualTo(30)
    }

    @Test
    fun unanchoredLinearChainSubmatchWorkIsBoundedByMatchSlice() {
        val pattern = Pattern.compile("([a-z]+)@([a-z]+)\\.com")
        val prefix = "noise ".repeat(500)
        val match = "alice@google.com"
        val suffix = " trailing".repeat(500)
        val input = prefix + match + suffix
        val matcher = pattern.matcher(input)
        Assertions.assertThat(matcher.find()).isTrue()
        val groupReadWork =
            WorkCounter.countForTesting(
                Runnable {
                    Assertions.assertThat(matcher.group(1)).isEqualTo("alice")
                    Assertions.assertThat(matcher.group(2)).isEqualTo("google")
                })
        Assertions.assertThat(groupReadWork)
            .`as`(
                "Submatch extraction work must be strictly bounded by match slice length, not haystack"
                        + " length"
            )
            .isLessThanOrEqualTo(match.length * 2L + 20)
    }

    @Test
    fun directDfaStartStateAcceleratesUnanchoredAlternationOnString() {
        val prog = Compiler.compile(Parser.parse("apple|banana|cherry", ParseFlags.MATCH_NL))
        val dfa = Dfa(prog, 1000, Dfa.buildSetup(prog), false)
        val input = "x".repeat(10000) + "cherry"
        val work =
            WorkCounter.countForTesting(
                Runnable {
                    val res = dfa.doSearch(input, false, false)
                    Assertions.assertThat<Dfa.SearchResult?>(res).isNotNull()
                    Assertions.assertThat(res!!.matched).isTrue()
                })
        Assertions.assertThat(work)
            .`as`("Direct DFA start state acceleration must scan input linearly")
            .isLessThanOrEqualTo((input.length + 20).toLong())
    }

    @Test
    fun multiLiteralPrefilterOnDenseCandidateNoiseIsBoundedByWorkLimit() {
        // Dense first bytes would select the UTF-8 literal-alternation search, which scans once per
        // literal (see LiteralAlternationWorkTest); this test covers the start accelerator instead.
        val pattern =
            Pattern.compile(
                "APPLE|BANANA|CHERRY", 0, EnginePathOptions.builder().literalFastPaths(false).build()
            )
        val noise = "A B C A B C ".repeat(10000).toByteArray(StandardCharsets.UTF_8)

        val work =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher(Utf8Input.trusted(noise)).find()).isFalse() })

        Assertions.assertThat(work)
            .`as`("Multi-literal candidate verification must be bounded by WorkLimit on dense noise")
            .isLessThan(500)
    }

    @Test
    fun matchesWithCapturesDefersNfaWorkUntilGroupIsRead() {
        // Non-OnePass pattern due to ambiguous repetition where b is part of [a-z]
        val pattern = Pattern.compile("([a-z]+)b([a-z]+)")
        val input = "helloworldbwelcome"
        val matcher = pattern.matcher(input)
        val matchWork = WorkCounter.countForTesting(Runnable { Assertions.assertThat(matcher.matches()).isTrue() })
        Assertions.assertThat(matcher.group(0)).isEqualTo(input)
        val groupReadWork =
            WorkCounter.countForTesting(
                Runnable {
                    Assertions.assertThat(matcher.group(1)).isEqualTo("helloworld")
                    Assertions.assertThat(matcher.group(2)).isEqualTo("welcome")
                })
        Assertions.assertThat(matchWork)
            .`as`("matches() must only run forward DFA work without eager NFA state building")
            .isLessThanOrEqualTo(input.length * 2L + 20)
        Assertions.assertThat(groupReadWork)
            .`as`("Submatch extraction happens on-demand when group(k) is read")
            .isLessThanOrEqualTo(input.length * 5L + 20)
    }

    @Test
    fun matchesWithCapturesRejectsNonMatchingInputInLinearWork() {
        val pattern = Pattern.compile("([a-z]+)b([a-z]+)")
        val input = "x".repeat(10000)
        val matcher = pattern.matcher(input)
        val work = WorkCounter.countForTesting(Runnable { Assertions.assertThat(matcher.matches()).isFalse() })
        Assertions.assertThat(work)
            .`as`("Non-matching input in matches() must reject in linear DFA steps without NFA work")
            .isLessThanOrEqualTo((input.length + 20).toLong())
    }

    @Test
    fun directDfaStartStateAcceleratesUnanchoredAlternationOnUtf8() {
        val prog = Compiler.compile(Parser.parse("apple|banana|cherry", ParseFlags.MATCH_NL))
        val dfa = Dfa(prog, 1000, Dfa.buildSetup(prog), false)
        val input = ("x".repeat(10000) + "cherry").toByteArray(StandardCharsets.UTF_8)
        val work =
            WorkCounter.countForTesting(
                Runnable {
                    val res =
                        dfa.doSearch(Utf8InputScanner(input, 0, input.size), 0, false, false)
                    Assertions.assertThat<Dfa.SearchResult?>(res).isNotNull()
                    Assertions.assertThat(res!!.matched).isTrue()
                })
        Assertions.assertThat(work)
            .`as`("Direct DFA start state acceleration on UTF-8 must scan input linearly")
            .isLessThanOrEqualTo((input.size + 20).toLong())
    }

    @Test
    fun lookingAtWithCapturesDefersNfaWorkUntilGroupIsRead() {
        // Non-OnePass pattern due to overlapping alternation branches
        val pattern = Pattern.compile("(?:apple|application|apply):([0-9]+)")
        val input = "application:12345 " + "x".repeat(10000)
        val matcher = pattern.matcher(input)
        val lookingAtWork =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(matcher.lookingAt()).isTrue() })
        Assertions.assertThat(lookingAtWork)
            .`as`(
                "lookingAt() must only execute DFA prefix scan bounded by matched prefix, not trailing"
                        + " 10k chars"
            )
            .isLessThanOrEqualTo(50)
        Assertions.assertThat(matcher.group(0)).isEqualTo("application:12345")
        Assertions.assertThat(matcher.group(1)).isEqualTo("12345")
    }

    @Test
    fun multiLiteralPrefilterDoesNotRestartScalarVerificationAfterLateDenseCandidates() {
        val literalLength = 512
        val pattern =
            Pattern.compile("A".repeat(literalLength - 1) + "B|" + "B".repeat(literalLength - 1) + "C")
        val noise = ("x".repeat(256) + "A".repeat(8192)).toByteArray(StandardCharsets.UTF_8)

        val work =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher(Utf8Input.trusted(noise)).find()).isFalse() })

        Assertions.assertThat(work)
            .`as`("WorkLimit exhaustion must resume with the normal matcher without a scalar rescan")
            .isLessThan(noise.size.toLong() * 10)
    }

    @Test
    fun leadingWhitespaceCharClassExpansionIsLinearForStringInput() {
        val pattern = Pattern.compile("\\s*[\\[\\uff3b]\\d+[\\]\\uff3d]")
        val input2000 = "a".repeat(2000)
        val input10000 = "a".repeat(10000)

        val work2000 =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input2000).find()).isFalse() })
        val work10000 =
            WorkCounter.countForTesting(Runnable {
                Assertions.assertThat(pattern.matcher(input10000).find()).isFalse()
            })

        Assertions.assertThat(work10000)
            .`as`("Leading expansion char class find on String should scale linearly")
            .isLessThan(work2000 * 6)

        assertRepeatedFindWorkIsLinear(
            IntFunction { size: Int -> FindIterator { pattern.matcher("   [123] ".repeat(size)).find() } }, "String"
        )
    }

    @Test
    fun leadingWhitespaceCharClassExpansionIsLinearForUtf8Input() {
        val pattern = Pattern.compile("\\s*[\\[\\uff3b]\\d+[\\]\\uff3d]")
        val input2000 = "a".repeat(2000).toByteArray(StandardCharsets.UTF_8)
        val input10000 = "a".repeat(10000).toByteArray(StandardCharsets.UTF_8)

        val work2000 =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher(Utf8Input.trusted(input2000)).find()).isFalse() })
        val work10000 =
            WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(pattern.matcher(Utf8Input.trusted(input10000)).find()).isFalse() })

        Assertions.assertThat(work10000)
            .`as`("Leading expansion char class find on UTF-8 should scale linearly")
            .isLessThan(work2000 * 6)

        assertRepeatedFindWorkIsLinear(
            IntFunction { size: Int ->
                FindIterator {
                    pattern.matcher(
                        Utf8Input.trusted(
                            "   [123] ".repeat(size).toByteArray(
                                StandardCharsets.UTF_8
                            )
                        )
                    ).find()
                }
            },
            "UTF-8"
        )
    }

    @Test
    fun leadingWhitespaceLiteralExpansionIsLinearForStringInput() {
        val pattern = Pattern.compile("\\s+https?://\\w+")
        val input2000 = "a".repeat(2000)
        val input10000 = "a".repeat(10000)

        val work2000 =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input2000).find()).isFalse() })
        val work10000 =
            WorkCounter.countForTesting(Runnable {
                Assertions.assertThat(pattern.matcher(input10000).find()).isFalse()
            })

        Assertions.assertThat(work10000)
            .`as`("Leading expansion literal find on String should scale linearly")
            .isLessThan(work2000 * 6)

        assertRepeatedFindWorkIsLinear(
            IntFunction { size: Int -> FindIterator { pattern.matcher("  http://example ".repeat(size)).find() } },
            "String"
        )
    }

    @Test
    fun leadingWhitespaceMultiLiteralExpansionIsLinearForStringInput() {
        val pattern = Pattern.compile("\\s*(?:apple|banana|cherry)")
        val input2000 = "x".repeat(2000)
        val input10000 = "x".repeat(10000)

        val work2000 =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input2000).find()).isFalse() })
        val work10000 =
            WorkCounter.countForTesting(Runnable {
                Assertions.assertThat(pattern.matcher(input10000).find()).isFalse()
            })

        Assertions.assertThat(work10000)
            .`as`("Leading expansion multi-literal find on String should scale linearly")
            .isLessThan(work2000 * 6)
    }

    @Test
    fun leadingUnicodeExpansionIsLinearForStringAndUtf8Input() {
        val pattern = Pattern.compile("[\\u00e9\\u00e8]+:target")
        val input2000 = "x".repeat(2000)
        val input10000 = "x".repeat(10000)

        val work2000 =
            WorkCounter.countForTesting(Runnable { Assertions.assertThat(pattern.matcher(input2000).find()).isFalse() })
        val work10000 =
            WorkCounter.countForTesting(Runnable {
                Assertions.assertThat(pattern.matcher(input10000).find()).isFalse()
            })

        Assertions.assertThat(work10000)
            .`as`("Leading expansion unicode find on String should scale linearly")
            .isLessThan(work2000 * 6)

        assertRepeatedFindWorkIsLinear(
            IntFunction { size: Int -> FindIterator { pattern.matcher("  \u00e9\u00e9:target ".repeat(size)).find() } },
            "String"
        )
        assertRepeatedFindWorkIsLinear(
            IntFunction { size: Int ->
                FindIterator {
                    pattern.matcher(
                        Utf8Input.trusted("  \u00e9\u00e9:target ".repeat(size).toByteArray(StandardCharsets.UTF_8))
                    )
                        .find()
                }
            },
            "UTF-8"
        )
    }

    companion object {
        private fun alternatingNullableLoops(depth: Int): String {
            val regex = StringBuilder("(?:x|)")
            for (i in 0..<depth) {
                regex.insert(0, "(?:").append(')').append(if (i % 2 == 0) "+?" else "*")
            }
            return regex.toString()
        }

        private fun assertBoundedEndSearchWork(small: Runnable?, large: Runnable?, label: String?) {
            val smallWork = WorkCounter.countForTesting(small)
            val largeWork = WorkCounter.countForTesting(large)
            Assertions.assertThat(smallWork).`as`("%s work must be observed", label).isPositive()
            Assertions.assertThat(largeWork)
                .`as`("%s should inspect only the matching suffix", label)
                .isLessThanOrEqualTo(smallWork * 2)
        }

        private fun assertFindAllWorkIsLinear(
            matcher: IntFunction<FindIterator?>, inputKind: String?
        ) {
            val smallerWork = WorkCounter.countForTesting(Runnable { Companion.consumeMatches(matcher.apply(200)!!) })
            val largerWork = WorkCounter.countForTesting(Runnable { Companion.consumeMatches(matcher.apply(1000)!!) })

            Assertions.assertThat(smallerWork).`as`("%s find-all work must be observed", inputKind).isPositive()
            Assertions.assertThat(largerWork)
                .`as`("%s find-all iteration should scale linearly", inputKind)
                .isLessThan(smallerWork * 6)
        }

        private fun consumeMatches(matcher: FindIterator) {
            while (matcher.find()) {
                // Consume every match so repeated find() work is included.
            }
        }

        private fun assertGuardedGapRetryWorkIsLinear(
            matcher: IntFunction<FindIterator?>, inputKind: String?
        ) {
            val smallerWork =
                WorkCounter.countForTesting(Runnable { Assertions.assertThat(matcher.apply(2000)!!.find()).isFalse() })
            val largerWork =
                WorkCounter.countForTesting(Runnable { Assertions.assertThat(matcher.apply(10000)!!.find()).isFalse() })

            Assertions.assertThat(smallerWork).`as`("%s guarded-gap work must be observed", inputKind).isPositive()
            Assertions.assertThat(largerWork)
                .`as`("%s guarded-gap retries should scale linearly", inputKind)
                .isLessThan(smallerWork * 6)
        }

        private fun sparseLongLiteralFalseCandidateWork(blocks: Int): Long {
            val block = "q" + "a".repeat(63)
            val literal = StringBuilder(block.repeat(blocks / 2))
            literal.setCharAt(literal.length - 2, 'e')
            val pattern = Pattern.compile("[xy]" + literal)
            val input = block.repeat(blocks)
            return WorkCounter.countForTesting(Runnable {
                Assertions.assertThat(pattern.matcher(input).find()).isFalse()
            })
        }

        private fun assertConstantRejectionWork(find: IntPredicate, description: String?) {
            val work2000 = WorkCounter.countForTesting(Runnable { Assertions.assertThat(find.test(2000)).isFalse() })
            val work10000 = WorkCounter.countForTesting(Runnable { Assertions.assertThat(find.test(10000)).isFalse() })

            Assertions.assertThat(work2000).`as`("%s rejection on short input", description).isLessThan(100)
            Assertions.assertThat(work10000).`as`("%s rejection on long input", description).isLessThan(100)
            Assertions.assertThat(work10000)
                .`as`("%s rejection work should not scale with trailing input size", description)
                .isLessThanOrEqualTo(max(10, work2000 * 2))
        }

        private fun assertRepeatedFindWorkIsLinear(
            matcherFactory: IntFunction<FindIterator?>, description: String?
        ) {
            val smallerWork: Long = Companion.countAllMatches(matcherFactory.apply(500)!!, 500)
            val largerWork: Long = Companion.countAllMatches(matcherFactory.apply(2000)!!, 2000)

            Assertions.assertThat(largerWork)
                .`as`("%s repeated find work should scale linearly", description)
                .isLessThanOrEqualTo(max(10, smallerWork * 6))
        }

        private fun selectivityPattern(size: Int): String {
            val pattern = StringBuilder("[0-9]").append("z".repeat(size))
            for (i in 0..<size) {
                pattern.append("[0-9]aa")
            }
            return pattern.toString()
        }

        private fun driverSelectionPattern(segments: Int): String {
            return "(?:aa|bb)[0-9]".repeat(segments)
        }

        private fun countAllMatches(matcher: FindIterator, expectedMatches: Int): Long {
            return WorkCounter.countForTesting(
                Runnable {
                    var matches = 0
                    while (matcher.find()) {
                        matches++
                    }
                    Assertions.assertThat(matches).isEqualTo(expectedMatches)
                })
        }

        private fun assertReverseDfaSuffixFailureIsConstantWork(find: IntPredicate) {
            val work2000 =
                WorkCounter.countForTesting(
                    Runnable {
                        val matched = find.test(2000)
                        Assertions.assertThat(matched).isFalse()
                    })

            val work10000 =
                WorkCounter.countForTesting(
                    Runnable {
                        val matched = find.test(10000)
                        Assertions.assertThat(matched).isFalse()
                    })

            // If a required-content prefilter runs first, it scans the entire input, resulting in at least
            // 2,000 and 10,000 operations respectively.
            //
            // Under reverse DFA suffix acceleration, it rejects after inspecting only a few characters
            // from the end of the text, executing in constant time independent of text size.
            Assertions.assertThat(work2000)
                .`as`("Short text failing scan should run in constant-time reverse DFA setup")
                .isGreaterThanOrEqualTo(0)
                .isLessThan(200)

            Assertions.assertThat(work10000)
                .`as`("Long text failing scan should also run in constant-time reverse DFA setup")
                .isGreaterThanOrEqualTo(0)
                .isLessThan(200)

            // Assert that scaling is sub-linear (effectively constant)
            Assertions.assertThat(work10000)
                .`as`("Work scaling should be flat, not linear with input size increase")
                .isLessThanOrEqualTo(max(10, work2000 * 2))
        }

        private fun assertConstantWork(find: IntPredicate, description: String?) {
            val work2000 = WorkCounter.countForTesting(Runnable { Assertions.assertThat(find.test(2000)).isTrue() })
            val work10000 = WorkCounter.countForTesting(Runnable { Assertions.assertThat(find.test(10000)).isTrue() })

            Assertions.assertThat(work2000).`as`("%s on short input", description).isPositive().isLessThan(200)
            Assertions.assertThat(work10000).`as`("%s on long input", description).isPositive().isLessThan(200)
            Assertions.assertThat(work10000)
                .`as`("%s should not scale with the prefix", description)
                .isLessThan(work2000 * 2)
        }

        private val isVectorApiAvailable: Boolean
            get() {
                try {
                    Class.forName("jdk.incubator.vector.ByteVector")
                    return true
                } catch (e: ClassNotFoundException) {
                    return false
                }
            }
    }
}
