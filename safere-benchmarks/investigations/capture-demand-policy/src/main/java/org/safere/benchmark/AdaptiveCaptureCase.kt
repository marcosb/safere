// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.gson.JsonArray

/** Prepared schedules and independent capture oracle for adaptive-policy experiments.  */
internal class AdaptiveCaptureCase(
    config: JsonObject,
    scenario: String?,
    representation: String,
    trials: Function<String?, CrossEngineBenchmarkPlan.Trial?>,
    source: Function<String?, Input?>
) {
    private val regex: String?
    private val inputs: Array<Input?>
    private val groups: IntArray
    private val readEvery: IntArray
    private val order: Array<IntArray>
    private val lifetime: String
    private val utf8: Boolean
    private val offsets: Boolean

    init {
        val definition: JsonObject = config.getAsJsonObject("scenarios").getAsJsonObject(scenario)
        kotlin.require(!(definition == null || !(representation.equals("string") || representation.equals("utf8")))) { "Unknown adaptive scenario or representation" }
        utf8 = representation.equals("utf8")
        val trial = definition.get("reference").getAsString() + "@safere-" + representation
        regex = trials.apply(trial).workload().patterns().first()
        groups =
            definition.getAsJsonArray("groups").asList().stream().mapToInt({ v -> v.getAsInt() }).toArray()
        inputs = arrayOfNulls<Input>(2)
        val keys = arrayOf<String?>(
            definition.get("hitInput").getAsString(), definition.get("missInput").getAsString()
        )
        for (i in inputs.indices) {
            inputs[i] = source.apply(keys[i])
        }
        offsets = definition.get("consumption").getAsString().equals("offset")
        lifetime = definition.get("lifetime").getAsString()
        val phases: JsonArray = definition.getAsJsonArray("phases")
        kotlin.require(phases.size === 3) { "Expected exactly three adaptive phases" }
        readEvery = IntArray(phases.size)
        order = arrayOfNulls<IntArray>(phases.size)
        for (phase in 0..<phases.size) {
            val phaseConfig: JsonObject = phases.get(phase).getAsJsonObject()
            readEvery[phase] = phaseConfig.get("readEvery").getAsInt()
            kotlin.require(readEvery[phase] >= 0) { "Capture-read period must be nonnegative" }
            order[phase] =
                org.safere.benchmark.AdaptiveCaptureCase.Companion.schedule(
                    config.get("documentsPerPhase").getAsInt(),
                    phaseConfig.get("hitEvery").getAsInt(),
                    definition.get("order").getAsString(),
                    config.get("scheduleWidth").getAsInt(),
                    config.get("scheduleSeed").getAsLong() + phase
                )
        }
    }

    fun task(booleanOnly: Boolean): Task {
        return org.safere.benchmark.AdaptiveCaptureCase.Task(Pattern.compile(regex), booleanOnly)
    }

    fun shared(): Shared {
        return org.safere.benchmark.AdaptiveCaptureCase.Shared(Pattern.compile(regex))
    }

    internal inner class Shared private constructor(pattern: Pattern) {
        private val pattern: Pattern


        fun task(booleanOnly: Boolean): Task {
            return org.safere.benchmark.AdaptiveCaptureCase.Task(pattern, booleanOnly)
        }
    }

    fun validate() {
        val expected = oracle(false)
        val single = task(false)
        var sampledChecksum: Long = 0
        for (phase in order) {
            for (i in phase.indices) {
                sampledChecksum += single.runOne()
            }
        }
        kotlin.check(
            !(task(false).run(null) != expected || sampledChecksum != expected || task(true).run(null) != oracle(
                true
            ))
        ) { "Adaptive capture protocol differs from its JDK oracle" }
    }

    fun oracle(booleanOnly: Boolean): Long {
        val compiled: java.util.regex.Pattern = java.util.regex.Pattern.compile(regex)
        var sum: Long = 0
        for (phase in order.indices) {
            var hits = 0
            for (slot in order[phase]) {
                val matcher: java.util.regex.Matcher = compiled.matcher(inputs[slot].text)
                while (matcher.find()) {
                    sum++
                    if (!booleanOnly && readEvery[phase] != 0 && hits % readEvery[phase] == 0) {
                        for (group in groups) {
                            val value: String? = matcher.group(group)
                            sum +=
                                (
                                        if (value == null)
                                            0
                                        else
                                            if (offsets)
                                                (if (utf8) value.getBytes(StandardCharsets.UTF_8).length else value.length)
                                            else
                                                value.hashCode()).toLong()
                        }
                    }
                    hits++
                }
            }
        }
        return sum
    }

    internal inner class Task private constructor(pattern: Pattern, booleanOnly: Boolean) {
        private val pattern: Pattern
        private val booleanOnly: Boolean
        private val retained: Array<Cursor>?
        private val rotating: Cursor?
        private var stepPhase = 0
        private var stepDocument = 0
        private var stepHits = 0

        init {
            this.pattern = pattern
            this.booleanOnly = booleanOnly
            retained =
                if (lifetime.equals("retained")) arrayOf<Cursor>(cursor(0, false), cursor(1, false)) else null
            rotating = if (lifetime.equals("rotating")) cursor(0, true) else null
            kotlin.require(!(retained == null && rotating == null && !lifetime.equals("fresh"))) { "Unknown adaptive cursor lifetime" }
        }

        fun run(counters: AdaptivePhaseCounters?): Long {
            var sum: Long = 0
            for (phase in order.indices) {
                val started: Long = if (counters == null) 0 else System.nanoTime()
                var hits = 0
                for (slot in order[phase]) {
                    val matcher =
                        if (retained != null) retained[slot] else if (rotating != null) rotating else cursor(
                            slot,
                            false
                        )
                    if (retained != null || rotating != null) {
                        matcher.reset(slot)
                    }
                    while (matcher.find()) {
                        sum++
                        if (!booleanOnly && readEvery[phase] != 0 && hits % readEvery[phase] == 0) {
                            for (group in groups) {
                                sum +=
                                    (
                                            if (offsets)
                                                matcher.end(group) - matcher.start(group)
                                            else
                                                org.safere.benchmark.AdaptiveCaptureCase.Companion.hash(
                                                    matcher.group(
                                                        group
                                                    )
                                                )).toLong()
                            }
                        }
                        hits++
                    }
                }
                if (counters != null) {
                    counters.record(phase, System.nanoTime() - started, order[phase].size)
                }
            }
            return sum
        }

        fun runOne(): Long {
            val slot = order[stepPhase][stepDocument]
            val matcher =
                if (retained != null) retained[slot] else if (rotating != null) rotating else cursor(slot, false)
            if (retained != null || rotating != null) {
                matcher.reset(slot)
            }
            var sum: Long = 0
            while (matcher.find()) {
                sum++
                if (!booleanOnly && readEvery[stepPhase] != 0 && stepHits % readEvery[stepPhase] == 0) {
                    for (group in groups) {
                        sum += (if (offsets) matcher.end(group) - matcher.start(group) else org.safere.benchmark.AdaptiveCaptureCase.Companion.hash(
                            matcher.group(group)
                        )).toLong()
                    }
                }
                stepHits++
            }
            stepDocument++
            if (stepDocument == order[stepPhase].size) {
                stepDocument = 0
                stepHits = 0
                stepPhase = (stepPhase + 1) % order.size
            }
            return sum
        }

        private fun cursor(slot: Int, rotate: Boolean): Cursor {
            if (!utf8) {
                val matcher: Matcher = pattern.matcher(inputs[slot].text)
                return object : Cursor {
                    @Override
                    override fun reset(next: Int) {
                        if (rotate) {
                            matcher.reset(inputs[next].text)
                        } else {
                            matcher.reset()
                        }
                    }

                    @Override
                    override fun find(): Boolean {
                        return matcher.find()
                    }

                    @Override
                    override fun start(group: Int): Int {
                        return matcher.start(group)
                    }

                    @Override
                    override fun end(group: Int): Int {
                        return matcher.end(group)
                    }

                    @Override
                    override fun group(group: Int): String {
                        return matcher.group(group)
                    }
                }
            }
            val bytes: ByteArray?
            val split: Int
            if (rotate) {
                split = inputs[0].bytes.size
                bytes = ByteArray(split + inputs[1].bytes.size)
                System.arraycopy(inputs[0].bytes, 0, bytes, 0, split)
                System.arraycopy(inputs[1].bytes, 0, bytes, split, inputs[1].bytes.size)
            } else {
                bytes = inputs[slot].bytes
                split = 0
            }
            val matcher: Utf8Matcher = pattern.matcher(Utf8Input.trusted(bytes))
            return object : Cursor {
                @Override
                override fun reset(next: Int) {
                    if (rotate) {
                        matcher.region(if (next == 0) 0 else split, if (next == 0) split else bytes!!.size)
                    } else {
                        matcher.reset()
                    }
                }

                @Override
                override fun find(): Boolean {
                    return matcher.find()
                }

                @Override
                override fun start(group: Int): Int {
                    return matcher.start(group)
                }

                @Override
                override fun end(group: Int): Int {
                    return matcher.end(group)
                }

                @Override
                override fun group(group: Int): String? {
                    val begin: Int = matcher.start(group)
                    return if (begin < 0)
                        null
                    else
                        String(bytes, begin, matcher.end(group) - begin, StandardCharsets.UTF_8)
                }
            }
        }
    }

    private interface Cursor {
        fun reset(slot: Int)

        fun find(): Boolean

        fun start(group: Int): Int

        fun end(group: Int): Int

        fun group(group: Int): String?
    }

    companion object {
        fun load(scenario: String?, representation: String): AdaptiveCaptureCase {
            val data: BenchmarkData = BenchmarkData.get()
            return org.safere.benchmark.AdaptiveCaptureCase(
                data.investigationConfiguration().getAsJsonObject("adaptive"),
                scenario,
                representation,
                CrossEngineBenchmarkPlan.load()::resolve,
                Function { key -> Input(data.getInputString(key), data.getInputBytes(key)) })
        }

        fun schedule(count: Int, hitEvery: Int, ordering: String, width: Int, seed: Long): IntArray {
            kotlin.require(!(count < 1 || hitEvery < 0 || width < 1 || count % width != 0 || (hitEvery > 0 && width % hitEvery != 0))) { "Invalid adaptive document schedule" }
            val slots = IntArray(count)
            for (i in 0..<count) {
                val hit =
                    hitEvery != 0
                            && (if (ordering.equals("blocked")) i % width < width / hitEvery else i % hitEvery == 0)
                slots[i] = if (hit) 0 else 1
            }
            if (ordering.equals("shuffled")) {
                val random: Random = Random(seed)
                for (i in slots.size - 1 downTo 1) {
                    val other: Int = random.nextInt(i + 1)
                    val swap = slots[i]
                    slots[i] = slots[other]
                    slots[other] = swap
                }
            } else kotlin.require(!(!ordering.equals("periodic") && !ordering.equals("blocked"))) { "Unknown document ordering" }
            return slots
        }

        private fun hash(value: String?): Int {
            return if (value == null) 0 else value.hashCode()
        }
    }
}
