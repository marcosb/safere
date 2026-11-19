// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/** Investigation-only producer adaptation; supplied by an opt-in source patch.  */
internal class CapturePolicyExperiment {
    private var readHistory = 0
    private var successes = 0
    private var hitHistory = 0
    private var attempts = 0
    private var sticky = false
    private var demandPreference = false
    private var jointPreference = false
    private var replayCredit = 0
    private var dfaMean = 0
    private var captureMean = 0
    private var dfaSamples = 0
    private var captureSamples = 0
    private var uncertain = 0
    private var schedule = 0
    private var randomState: Long =
        org.safere.CapturePolicyExperiment.Companion.seed(org.safere.CapturePolicyExperiment.Companion.SEED)
    private var inFind = false
    private var pending = false
    private var read = false
    private var replay = false
    private var meaningful = false
    private var decisionMade = false
    private var captureSelected = false
    private var span = 0
    private var boundaryWork = 0
    private var replayWork = 0
    private var resolvingReplay = false
    private var choices: Long = 0
    private var captureChoices: Long = 0
    private var replayCount: Long = 0
    private var rejected: Long = 0
    private var totalWork: Long = 0
    private var userReplayWork: Long = 0

    fun begin(remaining: Int) {
        retire()
        inFind = true
        meaningful = remaining >= 16
        span = Math.max(1, remaining)
        decisionMade = false
        captureSelected = false
        boundaryWork = 0
        replayWork = 0
        replay = false
        read = false
    }

    fun finish(matched: Boolean) {
        inFind = false
        if (meaningful) {
            hitHistory = (hitHistory shl 1) or (if (matched) 1 else 0)
            attempts = Math.min(32, attempts + 1)
        }
        pending = matched
        if (!matched) {
            if (meaningful) {
                replayCredit = Math.max(-32, replayCredit - 4)
                rejected = org.safere.CapturePolicyExperiment.Companion.increment(rejected)
            }
            learnCost()
        }
    }

    fun read(deferred: Boolean) {
        if (pending) {
            read = true
            sticky = true
            if (deferred && !replay) {
                replay = true
                replayCount = org.safere.CapturePolicyExperiment.Companion.increment(replayCount)
            }
        }
    }

    fun retire() {
        if (!pending) {
            return
        }
        readHistory = (readHistory shl 1) or (if (read) 1 else 0)
        successes = Math.min(32, successes + 1)
        val reads: Int = Integer.bitCount(readHistory)
        if (successes >= 4) {
            if (4 * reads >= 3 * successes) {
                demandPreference = true
            } else if (2 * reads <= successes) {
                demandPreference = false
            }
            val numerator: Int = reads * Integer.bitCount(hitHistory)
            val denominator: Int = successes * Math.max(1, attempts)
            if (2 * numerator >= denominator) {
                jointPreference = true
            } else if (4 * numerator <= denominator) {
                jointPreference = false
            }
        }
        val credit = if (replay) 4 else if (captureSelected && read) 1 else if (!read) -1 else 0
        replayCredit = Math.max(-32, Math.min(32, replayCredit + credit))
        learnCost()
        pending = false
    }

    fun prefer(shared: Boolean, anchored: Boolean): Boolean {
        if (!inFind || anchored) {
            return shared
        }
        if (decisionMade) {
            return captureSelected
        }
        decisionMade = true
        captureSelected =
            when (org.safere.CapturePolicyExperiment.Companion.MODE) {
                "observe" -> shared
                "dfa" -> false
                "localSticky" -> sticky
                "fraction" -> demandPreference
                "joint" -> demandPreference && jointPreference
                "replay" -> replayCredit > 0
                "work", "random", "periodic" -> chooseWork()
                else -> throw IllegalArgumentException("Unknown investigation policy: " + org.safere.CapturePolicyExperiment.Companion.MODE)
            }
        choices = org.safere.CapturePolicyExperiment.Companion.increment(choices)
        if (captureSelected) {
            captureChoices = org.safere.CapturePolicyExperiment.Companion.increment(captureChoices)
        }
        return captureSelected
    }

    private fun chooseWork(): Boolean {
        // A producer cannot be justified by absent capture demand. This also avoids blind warmup
        // speculation on Boolean-only matchers. An existing estimate remains available for recovery.
        if (!sticky) {
            return false
        }
        if (captureSamples == 0) {
            return true
        }
        if (dfaSamples == 0) {
            return false
        }
        val best = captureMean < dfaMean
        if (org.safere.CapturePolicyExperiment.Companion.MODE.equals("work")) {
            return best
        }
        val high: Int = Math.max(captureMean, dfaMean)
        val close = 4L * Math.abs(captureMean - dfaMean) <= high
        val interval = if (captureSamples < 4 || dfaSamples < 4 || close || uncertain > 0) 4 else 32
        uncertain = Math.max(0, uncertain - 1)
        val explore: Boolean
        if (org.safere.CapturePolicyExperiment.Companion.MODE.equals("periodic")) {
            schedule = (schedule + 1) and 31
            explore = (schedule and (interval - 1)) == 0
        } else {
            var x = randomState
            x = x xor (x shl 13)
            x = x xor (x ushr 7)
            x = x xor (x shl 17)
            randomState = x
            explore = (x and (interval - 1).toLong()) == 0L
        }
        return if (explore) !best else best
    }

    fun work(units: Long) {
        val bounded: Long = Math.max(0L, Math.min(org.safere.CapturePolicyExperiment.Companion.MAX_WORK, units))
        totalWork = org.safere.CapturePolicyExperiment.Companion.add(totalWork, bounded)
        if (resolvingReplay && pending && read) {
            userReplayWork = org.safere.CapturePolicyExperiment.Companion.add(userReplayWork, bounded)
        }
        if (resolvingReplay) {
            replayWork = org.safere.CapturePolicyExperiment.Companion.saturatingWork(replayWork, units)
        } else {
            boundaryWork = org.safere.CapturePolicyExperiment.Companion.saturatingWork(boundaryWork, units)
        }
    }

    fun beginReplay() {
        resolvingReplay = true
    }

    fun endReplay() {
        resolvingReplay = false
    }

    private fun learnCost() {
        if (!org.safere.CapturePolicyExperiment.Companion.WORK || !decisionMade || !meaningful) {
            return
        }
        val cost = Math.min(
            org.safere.CapturePolicyExperiment.Companion.MAX_COST,
            (boundaryWork.toLong() + replayWork + 1) * 256 / span
        ) as Int
        if (captureSelected) {
            captureMean = update(captureMean, cost, captureSamples)
            captureSamples = Math.min(4096, captureSamples + 1)
        } else {
            dfaMean = update(dfaMean, cost, dfaSamples)
            dfaSamples = Math.min(4096, dfaSamples + 1)
        }
    }

    private fun update(mean: Int, cost: Int, samples: Int): Int {
        if (samples == 0) {
            return cost
        }
        if (2L * Math.abs(cost - mean) > Math.max(1, mean)) {
            uncertain = 32
        }
        return (7 * mean + cost + 4) / 8
    }

    fun resetPattern() {
        readHistory = 0
        successes = 0
        hitHistory = 0
        attempts = 0
        sticky = false
        demandPreference = false
        jointPreference = false
        replayCredit = 0
        dfaMean = 0
        captureMean = 0
        dfaSamples = 0
        captureSamples = 0
        uncertain = 0
        schedule = 0
        randomState =
            org.safere.CapturePolicyExperiment.Companion.seed(org.safere.CapturePolicyExperiment.Companion.SEED)
        pending = false
        inFind = false
        resolvingReplay = false
    }

    fun snapshot(): Snapshot {
        return org.safere.CapturePolicyExperiment.Snapshot(
            successes,
            Integer.bitCount(readHistory),
            attempts,
            Integer.bitCount(hitHistory),
            replayCredit,
            dfaMean,
            captureMean,
            dfaSamples,
            captureSamples,
            choices,
            captureChoices,
            replayCount,
            rejected,
            totalWork,
            userReplayWork
        )
    }

    @kotlin.jvm.JvmRecord
    internal data class Snapshot(
        val successes: Int,
        val reads: Int,
        val attempts: Int,
        val hits: Int,
        val credit: Int,
        val dfaMean: Int,
        val captureMean: Int,
        val dfaSamples: Int,
        val captureSamples: Int,
        val choices: Long,
        val captureChoices: Long,
        val replays: Long,
        val rejections: Long,
        val work: Long,
        val userReplayWork: Long
    )

    companion object {
        val MODE: String? = System.getProperty("safere.experimental.capturePolicy", "main")
        val ENABLED: Boolean = !org.safere.CapturePolicyExperiment.Companion.MODE.equals("main")
        val WORK: Boolean = Boolean.getBoolean("safere.experimental.captureWork")
                || org.safere.CapturePolicyExperiment.Companion.MODE.equals("work")
                || org.safere.CapturePolicyExperiment.Companion.MODE.equals("random")
                || org.safere.CapturePolicyExperiment.Companion.MODE.equals("periodic")
                || org.safere.CapturePolicyExperiment.Companion.MODE.equals("observe")
        val MAX_WORK: Int = 1 shl 24
        private val MAX_COST = 1 shl 20
        private val SEED: Long = Long.getLong("safere.experimental.captureSeed", 1L)

        init {
            System.setProperty(
                "safere.experimental.captureActualMode",
                org.safere.CapturePolicyExperiment.Companion.MODE
            )
            System.setProperty(
                "safere.experimental.captureActualSeed",
                Long.toString(org.safere.CapturePolicyExperiment.Companion.SEED)
            )
        }

        private fun seed(value: Long): Long {
            return if (value == 0L) -0x61c8864680b583ebL else value
        }

        private fun saturatingWork(current: Int, units: Long): Int {
            return Math.min(
                org.safere.CapturePolicyExperiment.Companion.MAX_WORK,
                current + Math.max(0L, Math.min(org.safere.CapturePolicyExperiment.Companion.MAX_WORK, units))
            ) as Int
        }

        private fun add(value: Long, extra: Long): Long {
            return if (value > Long.MAX_VALUE - extra) Long.MAX_VALUE else value + extra
        }

        private fun increment(value: Long): Long {
            return if (value == Long.MAX_VALUE) value else value + 1
        }
    }
}
