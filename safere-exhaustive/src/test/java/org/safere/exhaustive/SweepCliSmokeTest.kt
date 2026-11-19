// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.safere.exhaustive.RegionScalarDivergenceSweep.classifyDivergenceShapeForTesting
import org.safere.exhaustive.RegionScalarDivergenceSweep.containsGeneratedCaseForTesting
import org.safere.exhaustive.RegionZeroWidthDivergenceSweep.classifyDivergenceShapeForTesting
import org.safere.exhaustive.RegionZeroWidthDivergenceSweep.containsGeneratedCaseForTesting
import org.safere.exhaustive.RegionZeroWidthDivergenceSweep.semanticallyEqualForTesting
import org.safere.exhaustive.ZeroWidthQuantifierDivergenceSweep.divergenceClassificationNames
import org.safere.exhaustive.ZeroWidthQuantifierDivergenceSweep.divergenceClassificationStatuses
import org.safere.exhaustive.ZeroWidthQuantifierDivergenceSweep.isDecomposedEAcuteOnlyTraceDifferenceForTesting
import org.safere.exhaustive.ZeroWidthQuantifierDivergenceSweep.isKnownGraphemeBoundaryAlternativeFindCursorForTesting
import org.safere.exhaustive.ZeroWidthQuantifierDivergenceSweep.isRepeatedGraphemeBoundaryCompositionTraceDifferenceForTesting
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

/** Smoke tests for the exhaustive sweep command-line entry points.  */
internal class SweepCliSmokeTest {
    @TempDir
    var tempDir: Path? = null

    @Test
    @Throws(Exception::class)
    fun characterClassSweepRunsTinyRange() {
        val outputDir = tempDir!!.resolve("character")

        CharacterClassDivergenceSweep.main(args(outputDir))

        Assertions.assertThat(Files.exists(outputDir.resolve("character-class-divergences.jsonl"))).isFalse()
        assertCompactOutput(outputDir)
    }

    @Test
    @Throws(Exception::class)
    fun graphemeClusterSweepRunsTinyRange() {
        val outputDir = tempDir!!.resolve("grapheme")

        GraphemeClusterDivergenceSweep.main(args(outputDir))

        Assertions.assertThat(Files.exists(outputDir.resolve("grapheme-cluster-divergences.jsonl"))).isFalse()
        Assertions.assertThat(Files.exists(outputDir.resolve("grapheme-cluster-class-counts.tsv"))).isFalse()
        Assertions.assertThat(Files.exists(outputDir.resolve("grapheme-cluster-unknown-stratified.jsonl")))
            .isFalse()
        assertCompactOutput(outputDir)
    }

    @Test
    @Throws(Exception::class)
    fun controlEscapeSweepRunsTinyRange() {
        val outputDir = tempDir!!.resolve("control")

        ControlEscapeDivergenceSweep.main(args(outputDir))

        Assertions.assertThat(Files.exists(outputDir.resolve("control-escape-divergences.jsonl"))).isFalse()
        assertCompactOutput(outputDir)
    }

    @Test
    @Throws(Exception::class)
    fun unicodeCharacterClassSweepRunsTinyRange() {
        val outputDir = tempDir!!.resolve("unicode-character")

        UnicodeCharacterClassDivergenceSweep.main(args(outputDir))

        Assertions.assertThat(Files.exists(outputDir.resolve("unicode-character-class-divergences.jsonl")))
            .isFalse()
        assertCompactOutput(outputDir)
    }

    @Test
    @Throws(Exception::class)
    fun regionScalarSweepRunsTinyRange() {
        val outputDir = tempDir!!.resolve("region-scalar")

        RegionScalarDivergenceSweep.main(args(outputDir))

        Assertions.assertThat(Files.exists(outputDir.resolve("region-scalar-divergences.jsonl"))).isFalse()
        assertCompactOutput(outputDir)
    }

    @Test
    @Throws(Exception::class)
    fun regionZeroWidthSweepRunsTinyRange() {
        val outputDir = tempDir!!.resolve("region-zero-width")

        RegionZeroWidthDivergenceSweep.main(args(outputDir))

        Assertions.assertThat(Files.exists(outputDir.resolve("region-zero-width-divergences.jsonl"))).isFalse()
        assertCompactOutput(outputDir)
    }

    @Test
    @Throws(Exception::class)
    fun caseFoldingCharacterClassSweepRunsTinyRange() {
        val outputDir = tempDir!!.resolve("case-folding")

        CaseFoldingCharacterClassDivergenceSweep.main(args(outputDir))

        Assertions.assertThat(Files.exists(outputDir.resolve("case-folding-character-class-divergences.jsonl")))
            .isFalse()
        assertCompactOutput(outputDir)
    }

    @Test
    @Throws(Exception::class)
    fun zeroWidthQuantifierSweepRunsTinyRange() {
        val outputDir = tempDir!!.resolve("zero-width")

        ZeroWidthQuantifierDivergenceSweep.main(args(outputDir))

        Assertions.assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-divergences.jsonl")))
            .isFalse()
        Assertions.assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-class-counts.tsv"))).isFalse()
        Assertions.assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-unknown-stratified.jsonl")))
            .isFalse()
        assertCompactOutput(outputDir)
    }

    @Test
    @Throws(Exception::class)
    fun zeroWidthQuantifierSweepHandlesEmptyHighStartRange() {
        val outputDir = tempDir!!.resolve("zero-width-empty-high-start")

        val output: String? =
            captureOutput(
                SweepCliSmokeTest.ThrowingRunnable {
                    ZeroWidthQuantifierDivergenceSweep.main(
                        arrayOf<String>(
                            "--range=" + Long.Companion.MAX_VALUE + ":", "--threads=2", "--output-dir=" + outputDir
                        )
                    )
                })

        Assertions.assertThat(output).contains("checked=0")
        assertCompactOutput(outputDir)
    }

    @Test
    @Throws(Exception::class)
    fun graphemeClusterSweepClampsGeneratedProgressToSelectedRange() {
        val outputDir = tempDir!!.resolve("grapheme-clamped-progress")

        val output: String? =
            captureOutput(
                SweepCliSmokeTest.ThrowingRunnable {
                    GraphemeClusterDivergenceSweep.main(
                        arrayOf<String>("--range=:10", "--threads=3", "--output-dir=" + outputDir)
                    )
                })

        Assertions.assertThat(output).contains("generated=10")
        Assertions.assertThat(Files.readString(outputDir.resolve("progress.json")))
            .doesNotContain("\"nextCaseIndex\":11", "\"nextCaseIndex\":12")
    }

    @Test
    fun characterClassCompactReplayJsonReconstructsIndexedCase() {
        Assertions.assertThat(CharacterClassDivergenceSweep.compactReplayJson(0, "UNKNOWN"))
            .contains("\"caseIndex\":0", "\"classification\":\"UNKNOWN\"", "\"case\"")
    }

    @Test
    fun regionScalarCompactReplayJsonReconstructsIndexedCase() {
        Assertions.assertThat(RegionScalarDivergenceSweep.compactReplayJson(0, "UNKNOWN"))
            .contains("\"caseIndex\":0", "\"classification\":\"UNKNOWN\"", "\"case\"")
    }

    @Test
    fun regionScalarSweepClassifiesRegionLocalSplitSurrogateConsumptionAsIntentional() {
        Assertions.assertThat(
            classifyDivergenceShapeForTesting(
                ".", "%s", Pattern.DOTALL, "\uD83D\uDE00", 0, 1
            )
        )
            .isEqualTo("REGION_LOCAL_SCALAR_CONSUMPTION_AT_SPLIT_SURROGATE_END")
        Assertions.assertThat(
            classifyDivergenceShapeForTesting(
                "[\\s\\S]", "%s*", 0, "a\uD83D\uDE00", 1, 2
            )
        )
            .isEqualTo("REGION_LOCAL_SCALAR_CONSUMPTION_AT_SPLIT_SURROGATE_END")
        Assertions.assertThat(
            classifyDivergenceShapeForTesting(
                ".", "^%s", 0, "\uD83D\uDE00", 0, 1
            )
        )
            .isEqualTo("UNKNOWN")
    }

    @Test
    fun regionScalarSweepIncludesSplitSurrogateOrdinaryAtomCases() {
        Assertions.assertThat(
            containsGeneratedCaseForTesting(
                ".", "%s", Pattern.DOTALL, "\uD83D\uDE00", 0, 1
            )
        )
            .isTrue()
        Assertions.assertThat(
            containsGeneratedCaseForTesting(
                "[^a]", "(%s)", 0, "a\uD83D\uDE00", 1, 2
            )
        )
            .isTrue()
        Assertions.assertThat(
            containsGeneratedCaseForTesting(
                "\\P{Cs}", "^%s$", 0, "\uD801\uDC00", 0, 1
            )
        )
            .isTrue()
    }

    @Test
    fun regionZeroWidthSweepIncludesSplitSurrogateEndAnchorCase() {
        Assertions.assertThat(
            containsGeneratedCaseForTesting(
                "$", 0, "\uD83D\uDE00", 0, 1
            )
        )
            .isTrue()
        Assertions.assertThat(
            containsGeneratedCaseForTesting(
                "\\B", Pattern.UNICODE_CHARACTER_CLASS, "a\uD83D\uDE00", 1, 2
            )
        )
            .isTrue()
        Assertions.assertThat(
            containsGeneratedCaseForTesting(
                "\\B|y", 0, "x\uD83D\uDE00y", 1, 4
            )
        )
            .isTrue()
        Assertions.assertThat(
            containsGeneratedCaseForTesting(
                "\\B|a", 0, "x\uD83D\uDE00y", 1, 4
            )
        )
            .isTrue()
        Assertions.assertThat(
            containsGeneratedCaseForTesting(
                "\\B.", Pattern.DOTALL, "\uD83D\uDE00", 0, 1
            )
        )
            .isTrue()
        Assertions.assertThat(
            containsGeneratedCaseForTesting(
                "\\B[\\s\\S]", 0, "\uD83D\uDE00", 0, 1
            )
        )
            .isTrue()
    }

    @Test
    fun regionZeroWidthSweepClassifiesKnownIntentionalDivergences() {
        Assertions.assertThat(
            classifyDivergenceShapeForTesting(
                "\\b", 0, "a\u0301", 0, 1, true, true
            )
        )
            .isEqualTo("ASCII_WORD_BOUNDARY_COMBINING_MARK")
        Assertions.assertThat(
            classifyDivergenceShapeForTesting(
                "$", 0, "\r\n", 1, 2, false, true
            )
        )
            .isEqualTo("OPAQUE_REGION_CRLF_PAIR_CONTEXT")
        Assertions.assertThat(
            classifyDivergenceShapeForTesting(
                "\\B", 0, "x\uD83D\uDE00y", 1, 4, true, true
            )
        )
            .isEqualTo("NON_WORD_BOUNDARY_SPLIT_SURROGATE_INTERIOR_POSITION")
        Assertions.assertThat(
            classifyDivergenceShapeForTesting(
                "\\B[\\s\\S]", 0, "x\uD83D\uDE00y", 1, 4, true, true
            )
        )
            .isEqualTo("BOUNDARY_ANY_CLASS_SPLIT_SURROGATE_SCALAR_COMPOSITION")
        Assertions.assertThat(
            classifyDivergenceShapeForTesting(
                "y|\\B.", Pattern.DOTALL, "x\uD83D\uDE00y", 1, 4, true, false
            )
        )
            .isEqualTo("NON_WORD_BOUNDARY_SPLIT_SURROGATE_INTERIOR_POSITION")
    }

    @Test
    fun regionZeroWidthSweepTreatsAcceptanceMismatchAsDivergence() {
        Assertions.assertThat(semanticallyEqualForTesting(false, "", true, "trace"))
            .isFalse()
        Assertions.assertThat(semanticallyEqualForTesting(true, "trace", false, ""))
            .isFalse()
    }

    @Test
    fun zeroWidthQuantifierSweepIncludesRepeatedQuantifierRegressions() {
        Assertions.assertThat(ZeroWidthQuantifierDivergenceSweep.containsQuantifierChainForTesting("*+")).isTrue()
        Assertions.assertThat(ZeroWidthQuantifierDivergenceSweep.containsQuantifierChainForTesting("?+")).isTrue()
        Assertions.assertThat(ZeroWidthQuantifierDivergenceSweep.containsQuantifierChainForTesting("{2}+"))
            .isTrue()
        Assertions.assertThat(ZeroWidthQuantifierDivergenceSweep.containsQuantifierChainForTesting("*??"))
            .isTrue()
        Assertions.assertThat(ZeroWidthQuantifierDivergenceSweep.containsQuantifierChainForTesting("*{1}+"))
            .isTrue()
        Assertions.assertThat(ZeroWidthQuantifierDivergenceSweep.containsQuantifierChainForTesting("*{0}+"))
            .isTrue()
        Assertions.assertThat(ZeroWidthQuantifierDivergenceSweep.containsQuantifierChainForTesting("{0,2}++"))
            .isTrue()
        Assertions.assertThat(
            ZeroWidthQuantifierDivergenceSweep.containsCartesianCaseForTesting(
                "()", "%s", "*{0}+", "%sa", "ba"
            )
        )
            .isTrue()
    }

    @Test
    fun zeroWidthQuantifierSweepIncludesMixedLeadingGraphemeBoundaryFindCases() {
        val trailingZwjGrapheme = "\uD83D\uDC69\u200D\uD83D\uDCBBx"
        val precededZwjGrapheme = "x\uD83D\uDC69\u200D\uD83D\uDCBBx"

        Assertions.assertThat(
            ZeroWidthQuantifierDivergenceSweep.containsCartesianCaseForTesting(
                "\\b{g}", "%s", "{1}{1}", "(?:%s|a).", trailingZwjGrapheme
            )
        )
            .isTrue()
        Assertions.assertThat(
            ZeroWidthQuantifierDivergenceSweep.containsCartesianCaseForTesting(
                "\\b{g}", "%s", "{1}{1}", "(?:a|%s).", trailingZwjGrapheme
            )
        )
            .isTrue()
        Assertions.assertThat(
            ZeroWidthQuantifierDivergenceSweep.containsCartesianCaseForTesting(
                "\\b{g}", "%s", "++", "(?:%s|a).", precededZwjGrapheme
            )
        )
            .isTrue()
    }

    @Test
    fun regexSweepTraceIncludesCapturesForZeroWidthPossessiveStar() {
        val outcome = RegexSweep.jdkTraceOutcome("()*+", 0, mutableListOf<String>(""), 1)

        Assertions.assertThat(outcome.accepted).isTrue()
        Assertions.assertThat(outcome.trace).contains(":matches=true@0-0{groups=1;g1=0-0:")
    }

    @Test
    fun zeroWidthQuantifierSweepIncludesPossessiveCaptureAlternationCases() {
        Assertions.assertThat(
            ZeroWidthQuantifierDivergenceSweep.containsCartesianCaseForTesting(
                "()", "%s", "*+", "(?:%s|a).", "ab"
            )
        )
            .isTrue()
        Assertions.assertThat(
            ZeroWidthQuantifierDivergenceSweep.containsCartesianCaseForTesting(
                "^", "(%s)", "*+", "%sa", "ba"
            )
        )
            .isTrue()
        Assertions.assertThat(
            ZeroWidthQuantifierDivergenceSweep.containsCartesianCaseForTesting(
                "()", "%s", "{0}+", "%sa", "ba"
            )
        )
            .isTrue()
        Assertions.assertThat(
            ZeroWidthQuantifierDivergenceSweep.containsGeneratedCaseForTesting(
                "((\\b{g})*+)a?", "a"
            )
        )
            .isTrue()

        val outcome = RegexSweep.jdkTraceOutcome("(?:()*+|a).", 0, mutableListOf<String>("ab"), 1)
        val capturedAnchor = RegexSweep.jdkTraceOutcome("(^)*+a", 0, mutableListOf<String>("ba"), 1)
        val zeroCount = RegexSweep.jdkTraceOutcome("(){0}+a", 0, mutableListOf<String>("ba"), 1)
        val repeatedFindZeroLength =
            RegexSweep.jdkTraceOutcome("((\\b{g})*+)a?", 0, mutableListOf<String>("a"), 2)

        Assertions.assertThat(outcome.accepted).isTrue()
        Assertions.assertThat(outcome.trace).contains("ab:matches=true@0-2{groups=1;g1=0-0:")
        Assertions.assertThat(capturedAnchor.accepted).isTrue()
        Assertions.assertThat(capturedAnchor.trace).contains("ba:find0=true@1-2{groups=1;g1=0-0:")
        Assertions.assertThat(zeroCount.accepted).isTrue()
        Assertions.assertThat(zeroCount.trace).contains("ba:find0=true@1-2{groups=1;g1=null}")
        Assertions.assertThat(repeatedFindZeroLength.accepted).isTrue()
        Assertions.assertThat(repeatedFindZeroLength.trace)
            .contains("a:find1=true@1-1{groups=2;g1=1-1:;g2=1-1:")
    }

    @Test
    fun zeroWidthQuantifierSweepIncludesDeepPossessiveCaptureStackSentinel() {
        val regex = "(?:".repeat(12000) + "()" + ")".repeat(12000) + "*+a"

        Assertions.assertThat(ZeroWidthQuantifierDivergenceSweep.containsGeneratedCaseForTesting(regex, "ba"))
            .isTrue()
    }

    @Test
    fun zeroWidthQuantifierSweepIncludesDeepRepeatedBoundaryMetadataStackSentinel() {
        var regex = "\\b{g}{2}"
        for (i in 0..11999) {
            regex = "(?:" + regex + "|a)"
        }
        regex = regex + "{1}{1}"

        Assertions.assertThat(ZeroWidthQuantifierDivergenceSweep.containsGeneratedCaseForTesting(regex, "a"))
            .isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun characterClassReplayUsesConfiguredThreads() {
        val replayFile = tempDir!!.resolve("character-replay.jsonl")
        val outputDir = tempDir!!.resolve("character-replay")
        Files.writeString(
            replayFile,
            """
        {"case":{"template":"replay","comments":false,"negated":false,"pieces":[{"label":"literalA","text":"a"}]}}
        
        """.trimIndent()
        )

        val output: String? =
            captureOutput(SweepCliSmokeTest.ThrowingRunnable {
                CharacterClassDivergenceSweep.main(
                    Companion.replayArgs(
                        outputDir,
                        replayFile
                    )
                )
            })

        Assertions.assertThat(output).contains("mode=replay", "checked=1", "generated=1", "threads=2")
        Assertions.assertThat(output).doesNotContain("threads=1")
    }

    @Test
    @Throws(Exception::class)
    fun controlEscapeReplayUsesConfiguredThreads() {
        val replayFile = tempDir!!.resolve("control-replay.jsonl")
        val outputDir = tempDir!!.resolve("control-replay")
        Files.writeString(
            replayFile,
            """
        {"case":{"target":65,"contextLabel":"bare","contextTemplate":"\\c%s","flagLabel":"none","flagPrefix":"","flags":0}}
        
        """.trimIndent()
        )

        val output: String? =
            captureOutput(SweepCliSmokeTest.ThrowingRunnable {
                ControlEscapeDivergenceSweep.main(
                    Companion.replayArgs(
                        outputDir,
                        replayFile
                    )
                )
            })

        Assertions.assertThat(output).contains("mode=replay", "checked=1", "generated=1", "threads=2")
        Assertions.assertThat(output).doesNotContain("threads=1")
    }

    @Test
    @Throws(Exception::class)
    fun unicodeCharacterClassReplayUsesConfiguredThreads() {
        val replayFile = tempDir!!.resolve("unicode-character-replay.jsonl")
        val outputDir = tempDir!!.resolve("unicode-character-replay")
        Files.writeString(
            replayFile,
            """
        {"case":{"label":"word","regex":"\\w","codePoint":65}}
        
        """.trimIndent()
        )

        val output: String? =
            captureOutput(
                SweepCliSmokeTest.ThrowingRunnable {
                    UnicodeCharacterClassDivergenceSweep.main(
                        Companion.replayArgs(
                            outputDir,
                            replayFile
                        )
                    )
                })

        Assertions.assertThat(output).contains("mode=replay", "checked=1", "generated=1", "threads=2")
        Assertions.assertThat(output).doesNotContain("threads=1")
    }

    @Test
    @Throws(Exception::class)
    fun caseFoldingCharacterClassReplayUsesConfiguredThreads() {
        val replayFile = tempDir!!.resolve("case-folding-replay.jsonl")
        val outputDir = tempDir!!.resolve("case-folding-replay")
        Files.writeString(
            replayFile,
            """
        {"case":{"patternLabel":"rangeLowerHJ","regex":"[h-j]","inputRepeat":1,"flagLabel":"unicodeCase","flags":66,"inputCodePoint":104}}
        
        """.trimIndent()
        )

        val output: String? =
            captureOutput(
                SweepCliSmokeTest.ThrowingRunnable {
                    CaseFoldingCharacterClassDivergenceSweep.main(
                        Companion.replayArgs(
                            outputDir,
                            replayFile
                        )
                    )
                })

        Assertions.assertThat(output).contains("mode=replay", "checked=1", "generated=1", "threads=2")
        Assertions.assertThat(output).doesNotContain("threads=1")
    }

    @Test
    @Throws(Exception::class)
    fun graphemeClusterReplayUsesConfiguredThreads() {
        val replayFile = tempDir!!.resolve("grapheme-replay.jsonl")
        val outputDir = tempDir!!.resolve("grapheme-replay")
        Files.writeString(
            replayFile,
            """
        {"case":{"regexLabel":"literal","regex":"a","inputLabel":"literal","input":"a","region":"full","prefix":"","suffix":"","regionStart":0,"regionEnd":1,"bounds":"opaqueAnchoring","operation":"freshTrace"}}
        
        """.trimIndent()
        )

        val output: String? =
            captureOutput(SweepCliSmokeTest.ThrowingRunnable {
                GraphemeClusterDivergenceSweep.main(
                    Companion.replayArgs(
                        outputDir,
                        replayFile
                    )
                )
            })

        Assertions.assertThat(output).contains("mode=replay", "checked=1", "generated=1", "threads=2")
        Assertions.assertThat(output).doesNotContain("threads=1")
    }

    @Test
    @Throws(Exception::class)
    fun zeroWidthQuantifierReplayUsesConfiguredThreads() {
        val replayFile = tempDir!!.resolve("zero-width-replay.jsonl")
        val outputDir = tempDir!!.resolve("zero-width-replay")
        Files.writeString(
            replayFile,
            """
        {"case":{"operandLabel":"beginLine","operandRegex":"^","wrapperLabel":"bare","wrapperTemplate":"%s","firstQuantifierLabel":"plus","firstQuantifier":"+","suffixQuantifierLabel":"plus","suffixQuantifier":"+","contextLabel":"bare","contextTemplate":"%s","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        
        """.trimIndent()
        )

        val output: String? =
            captureOutput(
                SweepCliSmokeTest.ThrowingRunnable {
                    ZeroWidthQuantifierDivergenceSweep.main(
                        Companion.replayArgs(
                            outputDir,
                            replayFile
                        )
                    )
                })

        Assertions.assertThat(output).contains("mode=replay", "checked=1", "generated=1", "threads=2")
        Assertions.assertThat(output).doesNotContain("threads=1")
    }

    @Test
    @Throws(Exception::class)
    fun zeroWidthQuantifierReplayCountsKnownIntentionalCaptureLeakage() {
        val replayFile = tempDir!!.resolve("zero-width-known-replay.jsonl")
        val outputDir = tempDir!!.resolve("zero-width-known-replay")
        Files.writeString(
            replayFile,
            """
        {"case":{"operandLabel":"atom:emptyCapturing","operandRegex":"()","wrapperLabel":"capturing","wrapperTemplate":"(%s)","quantifierChainLabel":"star","quantifierChain":"*","contextLabel":"mixedLeadingLiteralAlternative","contextTemplate":"(?:%s|a).","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        {"case":{"operandLabel":"atom:emptyCapturing","operandRegex":"()","wrapperLabel":"nonCapturing","wrapperTemplate":"(?:%s)","quantifierChainLabel":"star","quantifierChain":"*","contextLabel":"mixedLeadingLiteralAlternative","contextTemplate":"(?:%s|a).","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        
        """.trimIndent()
        )

        val output: String? =
            captureOutput(
                SweepCliSmokeTest.ThrowingRunnable {
                    ZeroWidthQuantifierDivergenceSweep.main(
                        Companion.replayArgs(
                            outputDir,
                            replayFile
                        )
                    )
                })

        Assertions.assertThat(output).contains("actionableDivergences=0", "unknownDivergences=0")
        Assertions.assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-divergences.jsonl")))
            .isFalse()
        Assertions.assertThat(Files.readString(outputDir.resolve("zero-width-quantifier-class-counts.tsv")))
            .contains("FAILED_PATH_CAPTURE_LEAKAGE\tKNOWN_INTENTIONAL\t2")
    }

    @Test
    @Throws(Exception::class)
    fun zeroWidthQuantifierReplayAcceptsPossessiveQuantifiersOverZeroWidthOperands() {
        val replayFile = tempDir!!.resolve("zero-width-possessive-replay.jsonl")
        val outputDir = tempDir!!.resolve("zero-width-possessive-replay")
        Files.writeString(
            replayFile,
            """
        {"case":{"operandLabel":"atom:beginLine","operandRegex":"^","wrapperLabel":"bare","wrapperTemplate":"%s","quantifierChainLabel":"star,plus","quantifierChain":"*+","contextLabel":"bare","contextTemplate":"%s","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        
        """.trimIndent()
        )

        val output: String? =
            captureOutput(
                SweepCliSmokeTest.ThrowingRunnable {
                    ZeroWidthQuantifierDivergenceSweep.main(
                        Companion.replayArgs(
                            outputDir,
                            replayFile
                        )
                    )
                })

        Assertions.assertThat(output).contains("divergences=0", "actionableDivergences=0")
        Assertions.assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-divergences.jsonl")))
            .isFalse()
        Assertions.assertThat(Files.readString(outputDir.resolve("zero-width-quantifier-class-counts.tsv")))
            .contains("ZERO_WIDTH_POSSESSIVE_QUANTIFIER_REJECTED\tEXPECTED_ZERO\t0")
    }

    @Test
    fun zeroWidthQuantifierRejectedPossessiveClassIsActionable() {
        Assertions.assertThat<String?>(divergenceClassificationNames())
            .contains("ZERO_WIDTH_POSSESSIVE_QUANTIFIER_REJECTED")
        val index =
            divergenceClassificationNames()
                .indexOf("ZERO_WIDTH_POSSESSIVE_QUANTIFIER_REJECTED")
        Assertions.assertThat<DivergenceStatus?>(divergenceClassificationStatuses().get(index))
            .isEqualTo(DivergenceStatus.EXPECTED_ZERO)
    }

    @Test
    @Throws(Exception::class)
    fun zeroWidthQuantifierReplayClassifiesKnownGraphemeAlternativeCursorDivergence() {
        val replayFile = tempDir!!.resolve("zero-width-grapheme-known-replay.jsonl")
        val outputDir = tempDir!!.resolve("zero-width-grapheme-known-replay")
        Files.writeString(
            replayFile,
            """
        {"case":{"operandLabel":"atom:graphemeBoundary","operandRegex":"\\b{g}","wrapperLabel":"bare","wrapperTemplate":"%s","quantifierChainLabel":"plus","quantifierChain":"+","contextLabel":"mixedLeadingLiteralAlternativeReversed","contextTemplate":"(?:a|%s).","flagLabel":"commentsEmbeddedComment","flagPrefix":"(?x)","flags":0,"trivia":"#q\
        "}}
        
        """.trimIndent()
        )

        val output: String? =
            captureOutput(
                SweepCliSmokeTest.ThrowingRunnable {
                    ZeroWidthQuantifierDivergenceSweep.main(
                        Companion.replayArgs(
                            outputDir,
                            replayFile
                        )
                    )
                })

        Assertions.assertThat(output).contains("divergences=1", "actionableDivergences=0")
        Assertions.assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-divergences.jsonl")))
            .isFalse()
        Assertions.assertThat(Files.readString(outputDir.resolve("zero-width-quantifier-class-counts.tsv")))
            .contains("GRAPHEME_BOUNDARY_ALTERNATIVE_FIND_CURSOR\tKNOWN_INTENTIONAL\t1")
    }

    @Test
    fun zeroWidthQuantifierGraphemeCursorClassifierRequiresFindOnlyTraceDifference() {
        Assertions.assertThat(
            isKnownGraphemeBoundaryAlternativeFindCursorForTesting(
                "a:matches=true@0-1,a:find0=true@0-1,a:find1=false",
                "a:matches=true@0-1,a:find0=true@0-1,a:find1=true@1-2,a:find2=false"
            )
        )
            .isTrue()
        Assertions.assertThat(
            isKnownGraphemeBoundaryAlternativeFindCursorForTesting(
                "a:matches=true@0-1,a:find0=true@0-1", "a:matches=false,a:find0=true@0-1"
            )
        )
            .isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun zeroWidthQuantifierReplayClassifiesRepeatedGraphemeBoundaryCompositionDivergence() {
        val replayFile = tempDir!!.resolve("zero-width-repeated-grapheme-known-replay.jsonl")
        val outputDir = tempDir!!.resolve("zero-width-repeated-grapheme-known-replay")
        Files.writeString(
            replayFile,
            """
        {"case":{"operandLabel":"atom:graphemeBoundary","operandRegex":"\\b{g}","wrapperLabel":"bare","wrapperTemplate":"%s","quantifierChainLabel":"repeatTwo","quantifierChain":"{2}","contextLabel":"bare","contextTemplate":"%s","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        {"case":{"operandLabel":"atom:graphemeBoundary","operandRegex":"\\b{g}","wrapperLabel":"bare","wrapperTemplate":"%s","quantifierChainLabel":"repeatTwo","quantifierChain":"{2}","contextLabel":"anchoredSurroundingLiterals","contextTemplate":"^a%sb${'$'}","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        {"case":{"operandLabel":"atom:graphemeBoundary","operandRegex":"\\b{g}","wrapperLabel":"nonCapturing","wrapperTemplate":"(?:%s)","quantifierChainLabel":"repeatTwo","quantifierChain":"{2}","contextLabel":"mixedLeadingLiteralAlternative","contextTemplate":"(?:%s|a).","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        
        """.trimIndent()
        )

        val output: String? =
            captureOutput(
                SweepCliSmokeTest.ThrowingRunnable {
                    ZeroWidthQuantifierDivergenceSweep.main(
                        Companion.replayArgs(
                            outputDir,
                            replayFile
                        )
                    )
                })

        Assertions.assertThat(output).contains("divergences=3", "actionableDivergences=0")
        Assertions.assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-divergences.jsonl")))
            .isFalse()
        Assertions.assertThat(Files.readString(outputDir.resolve("zero-width-quantifier-class-counts.tsv")))
            .contains("REPEATED_GRAPHEME_BOUNDARY_COMPOSITION\tKNOWN_INTENTIONAL\t3")
    }

    @Test
    fun zeroWidthQuantifierRepeatedGraphemeCompositionClassifierRequiresAdditiveSafeReTrace() {
        Assertions.assertThat(
            isRepeatedGraphemeBoundaryCompositionTraceDifferenceForTesting(
                "ab:matches=false,ab:lookingAt=true@0-0,ab:find0=true@0-0,ab:find1=false",
                "ab:matches=true@0-2,ab:lookingAt=true@0-0,ab:find0=true@0-0,"
                        + "ab:find1=true@1-1,ab:find2=false"
            )
        )
            .isTrue()
        Assertions.assertThat(
            isRepeatedGraphemeBoundaryCompositionTraceDifferenceForTesting(
                "ab:matches=true@0-1,ab:find0=true@0-0",
                "ab:matches=true@0-2,ab:find0=true@0-0"
            )
        )
            .isFalse()
        Assertions.assertThat(
            isRepeatedGraphemeBoundaryCompositionTraceDifferenceForTesting(
                "ab:matches=false,ab:find0=false", "ab:matches=false,ab:find0=false"
            )
        )
            .isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun zeroWidthQuantifierReplayClassifiesGraphemeAlternativeSegmentationDivergence() {
        val replayFile = tempDir!!.resolve("zero-width-grapheme-alternative-segmentation.jsonl")
        val outputDir = tempDir!!.resolve("zero-width-grapheme-alternative-segmentation")
        Files.writeString(
            replayFile,
            """
        {"case":{"operandLabel":"atom:graphemeBoundary","operandRegex":"\\b{g}","wrapperLabel":"capturing","wrapperTemplate":"(%s)","quantifierChainLabel":"plus,repeatZero,question,repeatZero","quantifierChain":"+{0}?{0}","contextLabel":"mixedLeadingLiteralAlternative","contextTemplate":"(?:%s|a).","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        
        """.trimIndent()
        )

        val output: String? =
            captureOutput(
                SweepCliSmokeTest.ThrowingRunnable {
                    ZeroWidthQuantifierDivergenceSweep.main(
                        Companion.replayArgs(
                            outputDir,
                            replayFile
                        )
                    )
                })

        Assertions.assertThat(output).contains("divergences=1", "actionableDivergences=0")
        Assertions.assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-divergences.jsonl")))
            .isFalse()
        Assertions.assertThat(Files.readString(outputDir.resolve("zero-width-quantifier-class-counts.tsv")))
            .contains("GRAPHEME_BOUNDARY_ALTERNATIVE_GRAPHEME_MODEL\tKNOWN_INTENTIONAL\t1")
    }

    @Test
    @Throws(Exception::class)
    fun zeroWidthQuantifierReplayClassifiesAsciiWordBoundaryCombiningMarkDivergence() {
        val replayFile = tempDir!!.resolve("zero-width-word-boundary-known-replay.jsonl")
        val outputDir = tempDir!!.resolve("zero-width-word-boundary-known-replay")
        Files.writeString(
            replayFile,
            """
        {"case":{"operandLabel":"atom:wordBoundary","operandRegex":"\\b","wrapperLabel":"bare","wrapperTemplate":"%s","quantifierChainLabel":"plus","quantifierChain":"+","contextLabel":"bare","contextTemplate":"%s","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        
        """.trimIndent()
        )

        val output: String? =
            captureOutput(
                SweepCliSmokeTest.ThrowingRunnable {
                    ZeroWidthQuantifierDivergenceSweep.main(
                        Companion.replayArgs(
                            outputDir,
                            replayFile
                        )
                    )
                })

        Assertions.assertThat(output).contains("divergences=1", "actionableDivergences=0")
        Assertions.assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-divergences.jsonl")))
            .isFalse()
        Assertions.assertThat(Files.readString(outputDir.resolve("zero-width-quantifier-class-counts.tsv")))
            .contains("ASCII_WORD_BOUNDARY_COMBINING_MARK\tKNOWN_INTENTIONAL\t1")
    }

    @Test
    @Throws(Exception::class)
    fun zeroWidthQuantifierReplayClassifiesCapturedPossessiveAsciiBoundaryAsKnownIntentional() {
        val replayFile = tempDir!!.resolve("zero-width-captured-word-boundary-possessive.jsonl")
        val outputDir = tempDir!!.resolve("zero-width-captured-word-boundary-possessive")
        Files.writeString(
            replayFile,
            """
        {"case":{"operandLabel":"atom:wordBoundary","operandRegex":"\\b","wrapperLabel":"capturing","wrapperTemplate":"(%s)","quantifierChainLabel":"star,plus","quantifierChain":"*+","contextLabel":"bare","contextTemplate":"%s","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        
        """.trimIndent()
        )

        val output: String? =
            captureOutput(
                SweepCliSmokeTest.ThrowingRunnable {
                    ZeroWidthQuantifierDivergenceSweep.main(
                        Companion.replayArgs(
                            outputDir,
                            replayFile
                        )
                    )
                })

        Assertions.assertThat(output).contains("divergences=1", "actionableDivergences=0")
        Assertions.assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-divergences.jsonl")))
            .isFalse()
        Assertions.assertThat(Files.readString(outputDir.resolve("zero-width-quantifier-class-counts.tsv")))
            .contains("ASCII_WORD_BOUNDARY_COMBINING_MARK\tKNOWN_INTENTIONAL\t1")
            .contains("ZERO_WIDTH_POSSESSIVE_CAPTURE_RETENTION\tEXPECTED_ZERO\t0")
    }

    @Test
    @Throws(Exception::class)
    fun zeroWidthQuantifierReplayClassifiesTargetedCapturedPossessiveGraphemeBoundaryAsKnown() {
        val replayFile = tempDir!!.resolve("zero-width-targeted-grapheme-possessive.jsonl")
        val outputDir = tempDir!!.resolve("zero-width-targeted-grapheme-possessive")
        Files.writeString(
            replayFile,
            """
        {"case":{"operandLabel":"targeted:capturedGraphemeBoundary","operandRegex":"\\b{g}","wrapperLabel":"capturing","wrapperTemplate":"(%s)","quantifierChainLabel":"star,plus","quantifierChain":"*+","contextLabel":"capturedThenOptionalLiteral","contextTemplate":"(%s)a?","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        
        """.trimIndent()
        )

        val output: String? =
            captureOutput(
                SweepCliSmokeTest.ThrowingRunnable {
                    ZeroWidthQuantifierDivergenceSweep.main(
                        Companion.replayArgs(
                            outputDir,
                            replayFile
                        )
                    )
                })

        Assertions.assertThat(output).contains("divergences=1", "actionableDivergences=0")
        Assertions.assertThat(Files.exists(outputDir.resolve("zero-width-quantifier-divergences.jsonl")))
            .isFalse()
        Assertions.assertThat(Files.readString(outputDir.resolve("zero-width-quantifier-class-counts.tsv")))
            .contains("GRAPHEME_BOUNDARY_CAPTURE_GRAPHEME_MODEL\tKNOWN_INTENTIONAL\t1")
            .contains("UNKNOWN\tUNKNOWN\t0")
    }

    @Test
    fun zeroWidthQuantifierAsciiWordBoundaryClassifierRequiresCombiningMarkOnlyDifference() {
        val common = ",a:matches=false,a:find0=false"

        Assertions.assertThat(
            isDecomposedEAcuteOnlyTraceDifferenceForTesting(
                "e\u0301:matches=false,e\u0301:find0=true@0-0" + common,
                "e\u0301:matches=true@0-0,e\u0301:find0=true@1-1" + common
            )
        )
            .isTrue()
        Assertions.assertThat(
            isDecomposedEAcuteOnlyTraceDifferenceForTesting(
                "e\u0301:matches=false,a:find0=false", "e\u0301:matches=true@0-0,a:find0=true@0-0"
            )
        )
            .isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun zeroWidthQuantifierReplayFailsForUnknownDivergence() {
        val replayFile = tempDir!!.resolve("zero-width-unknown-replay.jsonl")
        val outputDir = tempDir!!.resolve("zero-width-unknown-replay")
        Files.writeString(
            replayFile,
            """
        {"case":{"operandLabel":"atom:syntheticBoundary","operandRegex":"\\b","wrapperLabel":"bare","wrapperTemplate":"%s","quantifierChainLabel":"plus","quantifierChain":"+","contextLabel":"bare","contextTemplate":"%s","flagLabel":"none","flagPrefix":"","flags":0,"trivia":""}}
        
        """.trimIndent()
        )

        Assertions.assertThat<Throwable?>(
            Assertions.catchThrowable(
                ThrowingCallable {
                    ZeroWidthQuantifierDivergenceSweep.main(
                        Companion.replayArgs(
                            outputDir,
                            replayFile
                        )
                    )
                })
        )
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("unknown behavioral divergences")
        Assertions.assertThat(Files.readString(outputDir.resolve("zero-width-quantifier-class-counts.tsv")))
            .contains("UNKNOWN\tUNKNOWN\t1")
    }

    @Test
    @Throws(Exception::class)
    fun graphemeClusterReplaySuppressesKnownIntentionalDivergences() {
        val replayFile = tempDir!!.resolve("grapheme-known-replay.jsonl")
        val outputDir = tempDir!!.resolve("grapheme-known-replay")
        Files.writeString(
            replayFile,
            """
        {"case":{"regexLabel":"boundaryClusterBoundary","regex":"\\b{g}\\X\\b{g}","inputLabel":"twoAscii","input":"ab","region":"full","prefix":"","suffix":"","regionStart":0,"regionEnd":2,"bounds":"opaqueAnchoring","operation":"freshTrace"}}
        {"case":{"regexLabel":"boundaryClusterAlternativeAfterLiteral","regex":"b|\\b{g}\\X","inputLabel":"crlfTrace","input":"\r\r\
        \r","region":"wrapped","prefix":"#","suffix":"${'$'}","regionStart":1,"regionEnd":5,"bounds":"opaqueAnchoring","operation":"freshTrace"}}
        {"case":{"regexLabel":"boundaryClusterAlternativeAfterLiteral","regex":"b|\\b{g}\\X","inputLabel":"transparentPrependTrace","input":"\r؀a","region":"wrapped","prefix":"#","suffix":"${'$'}","regionStart":1,"regionEnd":4,"bounds":"transparentAnchoring","operation":"freshTrace"}}
        
        """.trimIndent()
        )

        val output: String? =
            captureOutput(SweepCliSmokeTest.ThrowingRunnable {
                GraphemeClusterDivergenceSweep.main(
                    Companion.replayArgs(
                        outputDir,
                        replayFile
                    )
                )
            })

        Assertions.assertThat(output).contains("divergences=3", "actionableDivergences=0", "unknownDivergences=0")
        Assertions.assertThat(Files.exists(outputDir.resolve("grapheme-cluster-divergences.jsonl"))).isFalse()
        Assertions.assertThat(Files.readString(outputDir.resolve("grapheme-cluster-class-counts.tsv")))
            .contains(
                "BOUNDARY_CLUSTER_BOUNDARY_COMPOSITION\tKNOWN_INTENTIONAL\t1",
                "BOUNDARY_CLUSTER_ALTERNATIVE_FIND_CURSOR\tKNOWN_INTENTIONAL\t2"
            )
    }

    @Test
    @Throws(Exception::class)
    fun graphemeClusterReplayReportsBoundedFirstUnknownDivergencesWithoutRawOutput() {
        val replayFile = tempDir!!.resolve("grapheme-unknown-replay.jsonl")
        val outputDir = tempDir!!.resolve("grapheme-unknown-replay")
        Files.writeString(
            replayFile,
            """
        {"case":{"regexLabel":"unclassifiedGraphemeAlternative","regex":"b|\\b{g}\\X","inputLabel":"crlfTrace","input":"\r\r\
        \r","region":"wrapped","prefix":"#","suffix":"${'$'}","regionStart":1,"regionEnd":5,"bounds":"opaqueAnchoring","operation":"freshTrace"}}
        
        """.trimIndent()
        )

        val output: String? =
            captureOutput(SweepCliSmokeTest.ThrowingRunnable {
                GraphemeClusterDivergenceSweep.main(
                    Companion.replayArgs(
                        outputDir,
                        replayFile
                    )
                )
            })

        Assertions.assertThat(output).contains("divergences=1", "actionableDivergences=0", "unknownDivergences=1")
        Assertions.assertThat(Files.exists(outputDir.resolve("grapheme-cluster-divergences.jsonl"))).isFalse()
        Assertions.assertThat(Files.readString(outputDir.resolve("grapheme-cluster-unknown-first.jsonl")))
            .contains("\"classification\":\"UNKNOWN\"")
        Assertions.assertThat(Files.exists(outputDir.resolve("grapheme-cluster-unknown-stratified.jsonl")))
            .isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun graphemeClusterRejectsRetiredUnknownStratifiedSampleOption() {
        val replayFile = tempDir!!.resolve("grapheme-retired-option-replay.jsonl")
        val outputDir = tempDir!!.resolve("grapheme-retired-option-replay")
        Files.writeString(
            replayFile,
            """
        {"case":{"regexLabel":"literal","regex":"a","inputLabel":"literal","input":"a","region":"full","prefix":"","suffix":"","regionStart":0,"regionEnd":1,"bounds":"opaqueAnchoring","operation":"freshTrace"}}
        
        """.trimIndent()
        )

        Assertions.assertThat<Throwable?>(
            Assertions.catchThrowable(
                ThrowingCallable {
                    GraphemeClusterDivergenceSweep.main(
                        replayArgs(outputDir, replayFile, "--unknown-stratified-samples=2")
                    )
                })
        )
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unknown argument: --unknown-stratified-samples=2")
    }

    private interface ThrowingRunnable {
        @Throws(Exception::class)
        fun run()
    }

    companion object {
        private fun args(outputDir: Path?): Array<String?> {
            return arrayOf<String>("--range=:10", "--threads=1", "--output-dir=" + outputDir)
        }

        private fun assertCompactOutput(outputDir: Path) {
            Assertions.assertThat(Files.exists(outputDir.resolve("run-manifest.json"))).isTrue()
            Assertions.assertThat(Files.exists(outputDir.resolve("progress.json"))).isTrue()
            Assertions.assertThat(Files.exists(outputDir.resolve("divergence-indices/worker-00.bin"))).isTrue()
        }

        private fun replayArgs(outputDir: Path?, replayFile: Path?): Array<String?> {
            return replayArgs(outputDir, replayFile, *arrayOfNulls<String>(0))
        }

        private fun replayArgs(outputDir: Path?, replayFile: Path?, vararg extraArgs: String?): Array<String?> {
            val args = arrayOfNulls<String>(3 + extraArgs.size)
            args[0] = "--threads=2"
            args[1] = "--output-dir=" + outputDir
            args[2] = "--replay-file=" + replayFile
            System.arraycopy(extraArgs, 0, args, 3, extraArgs.size)
            return args
        }

        @Throws(Exception::class)
        private fun captureOutput(runnable: ThrowingRunnable): String? {
            val output = ByteArrayOutputStream()
            val originalOut = System.out
            try {
                System.setOut(PrintStream(output, true, StandardCharsets.UTF_8))
                runnable.run()
            } finally {
                System.setOut(originalOut)
            }
            return output.toString(StandardCharsets.UTF_8)
        }
    }
}
