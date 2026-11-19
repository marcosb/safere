// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/** Tests for [Prog].  */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class ProgTest {
    @Test
    fun greedyAndLazyLoopMetadataCoexistRegardlessOfFirstAccess() {
        for (greedyFirst in mutableListOf<Boolean>(false, true)) {
            val prog = Pattern.compile("([a-z]*)(.*?)(z)").prog()
            if (greedyFirst) {
                Assertions.assertThat<Inst?>(prog.greedyRunes()).isNotEmpty()
            } else {
                Assertions.assertThat<LazyRuneLoop?>(prog.lazyRuneLoops()).isNotEmpty()
            }
            val greedy = prog.greedyRunes()
            val lazy = prog.lazyRuneLoops()
            Assertions.assertThat<Inst?>(greedy).isNotEmpty()
            Assertions.assertThat<LazyRuneLoop?>(lazy).isNotEmpty()
            for (id in 0..<prog.size) {
                if (greedy!![id] != null) {
                    Assertions.assertThat<LazyRuneLoop?>(lazy!![id]).isNull()
                }
            }
            Assertions.assertThat<Inst?>(prog.greedyRunes()).isSameAs(greedy)
            Assertions.assertThat<LazyRuneLoop?>(prog.lazyRuneLoops()).isSameAs(lazy)
            val copy = Prog(prog)
            copy.freeze()
            Assertions.assertThat<Inst?>(copy.greedyRunes()).isNotSameAs(greedy).isNotEmpty()
            Assertions.assertThat<LazyRuneLoop?>(copy.lazyRuneLoops()).isNotSameAs(lazy).isNotEmpty()
        }
    }

    @Test
    fun greedyRuneMetadataRequiresAnImmediatePreferredConsumingCycle() {
        for (regex in mutableListOf<String>("a+", "[a-z]*b", "[é😀]+")) {
            val prog = Pattern.compile(regex).prog()
            val runes = prog.greedyRunes()
            Assertions.assertThat<Inst?>(runes).`as`(regex).isNotNull()
            Assertions.assertThat<Inst?>(prog.greedyRunes()).isSameAs(runes)
        }
        for (regex in mutableListOf<String>("a+?", "(a)+", "(a?)*", "abc")) {
            Assertions.assertThat<Inst?>(Pattern.compile(regex).prog().greedyRunes()).`as`(regex).isNull()
        }
    }

    @Test
    fun bitStateAnalysisIsSharedRegardlessOfFirstRequestedMetadata() {
        for (fusionFirst in booleanArrayOf(false, true)) {
            val prog = Pattern.compile("(a)b(c)").prog()
            val captures: Array<CaptureRune?>?
            val cycleAlts: BooleanArray?
            if (fusionFirst) {
                captures = prog.captureRunes()
                cycleAlts = prog.epsilonCycleAlts()
            } else {
                cycleAlts = prog.epsilonCycleAlts()
                captures = prog.captureRunes()
            }

            Assertions.assertThat<CaptureRune?>(captures).isNotEmpty()
            Assertions.assertThat<CaptureRune?>(prog.captureRunes()).isSameAs(captures)
            Assertions.assertThat(prog.epsilonCycleAlts()).isSameAs(cycleAlts)
        }
    }

    @Test
    fun bitStateAnalysisCachesTheAbsenceOfFusion() {
        val prog = Pattern.compile("a+").prog()
        val cycleAlts = prog.epsilonCycleAlts()

        Assertions.assertThat<CaptureRune?>(prog.captureRunes()).isNull()
        Assertions.assertThat<CaptureRune?>(prog.captureRunes()).isNull()
        Assertions.assertThat(prog.epsilonCycleAlts()).isSameAs(cycleAlts)
    }

    @Test
    fun copiedProgramsComputeTheirOwnBitStateAnalysis() {
        val original = Pattern.compile("(a)b(c)").prog()
        val originalCaptures = original.captureRunes()
        val originalCycleAlts = original.epsilonCycleAlts()
        val copy = Prog(original)
        copy.freeze()

        Assertions.assertThat<CaptureRune?>(copy.captureRunes()).isNotNull().isNotSameAs(originalCaptures)
        Assertions.assertThat(copy.epsilonCycleAlts()).isNotSameAs(originalCycleAlts)
    }

    @Test
    @Throws(Exception::class)
    fun concurrentFirstUsePublishesCompleteBitStateAnalysis() {
        Executors.newFixedThreadPool(2).use { executor ->
            for (iteration in 0..7) {
                val prog = Pattern.compile("(a)b(c)").prog()
                val start = CyclicBarrier(2)
                val search =
                    Callable {
                        start.await()
                        BitState.search(prog, "abc", true, false, true, prog.numCaptures())
                    }
                val results = executor.invokeAll<IntArray?>(java.util.List.of<Callable<IntArray?>?>(search, search))
                for (result in results) {
                    Assertions.assertThat(result.get()).containsExactly(0, 3, 0, 1, 2, 3)
                }
            }
        }
    }

    @Test
    fun epsilonCyclesAfterGraphemeConsumptionAreReachable() {
        val prog = Prog()
        for (i in 0..4) {
            prog.allocInst()
        }
        prog.mutableInst(0)!!.initFail()
        prog.mutableInst(1)!!.initGraphemeCluster(2)
        prog.mutableInst(2)!!.initAlt(3, 4)
        prog.mutableInst(3)!!.initNop(2)
        prog.mutableInst(4)!!.initMatch(0)
        prog.setStart(1)
        prog.setStartUnanchored(1)
        prog.freeze()

        Assertions.assertThat(prog.epsilonCycleAlts()).containsExactly(false, false, true, false, false)
    }

    @Test
    fun graphemeConsumptionBreaksEpsilonCycles() {
        val prog = Prog()
        for (i in 0..3) {
            prog.allocInst()
        }
        prog.mutableInst(0)!!.initFail()
        prog.mutableInst(1)!!.initAlt(2, 3)
        prog.mutableInst(2)!!.initGraphemeCluster(1)
        prog.mutableInst(3)!!.initMatch(0)
        prog.setStart(1)
        prog.setStartUnanchored(1)
        prog.freeze()

        Assertions.assertThat(prog.epsilonCycleAlts()).containsExactly(false, false, false, false)
    }

    @Test
    fun emptyProgram() {
        val prog = Prog()
        Assertions.assertThat(prog.size).isEqualTo(0)
    }

    @Test
    fun allocInst_growsAndReturnsIndex() {
        val prog = Prog()
        val i0 = prog.allocInst()
        val i1 = prog.allocInst()
        val i2 = prog.allocInst()
        Assertions.assertThat(i0).isEqualTo(0)
        Assertions.assertThat(i1).isEqualTo(1)
        Assertions.assertThat(i2).isEqualTo(2)
        Assertions.assertThat(prog.size).isEqualTo(3)
    }

    @Test
    fun instReturnsCorrectInstruction() {
        val prog = Prog()
        val idx = prog.allocInst()
        prog.mutableInst(idx)!!.initMatch(42)
        prog.freeze()
        Assertions.assertThat<InstOp?>(prog.inst(idx).op).isEqualTo(InstOp.MATCH)
        Assertions.assertThat(prog.inst(idx).arg).isEqualTo(42)
    }

    @Test
    fun startAndStartUnanchored() {
        val prog = Prog()
        prog.allocInst()
        prog.allocInst()
        prog.setStart(0)
        prog.setStartUnanchored(1)
        Assertions.assertThat(prog.start()).isEqualTo(0)
        Assertions.assertThat(prog.startUnanchored()).isEqualTo(1)
    }

    @Test
    fun numCaptures() {
        val prog = Prog()
        prog.setNumCaptures(3)
        Assertions.assertThat(prog.numCaptures()).isEqualTo(3)
    }

    @Test
    fun anchorFlags() {
        val prog = Prog()
        prog.setAnchorStart(true)
        prog.setAnchorEnd(true)
        Assertions.assertThat(prog.anchorStart()).isTrue()
        Assertions.assertThat(prog.anchorEnd()).isTrue()
    }

    @Test
    fun dump_formatsInstructions() {
        val prog = Prog()
        prog.setStart(0)
        val i0 = prog.allocInst()
        val i1 = prog.allocInst()
        prog.mutableInst(i0)!!.initCharRange('a'.code, 'a'.code, false, 1)
        prog.mutableInst(i1)!!.initMatch(0)
        prog.freeze()

        val dump = prog.dump()
        Assertions.assertThat(dump).contains("char")
        Assertions.assertThat(dump).contains("match")
    }

    @Test
    fun toStringShowsSummary() {
        val prog = Prog()
        prog.allocInst()
        prog.allocInst()
        prog.setStart(0)
        prog.setNumCaptures(1)
        val s = prog.toString()
        Assertions.assertThat(s).contains("size=2")
        Assertions.assertThat(s).contains("captures=1")
    }
}
