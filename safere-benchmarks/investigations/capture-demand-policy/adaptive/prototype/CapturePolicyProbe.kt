// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.io.IOException

/** Deterministic state/work trace, run separately from timing measurements.  */
object CapturePolicyProbe {
    /** Reads pattern/input files and externally declared phase schedules, writing a TSV trace.  */
    @kotlin.Throws(IOException::class)
    fun main(args: Array<String?>) {
        val regex: String? = Files.readString(Path.of(args[0]))
        val inputs = arrayOf<String?>(Files.readString(Path.of(args[1])), Files.readString(Path.of(args[2])))
        val groups: IntArray = org.safere.CapturePolicyProbe.integers(args[3])
        val utf8 = args[4]!!.equals("utf8")
        val hitEvery: IntArray = org.safere.CapturePolicyProbe.integers(args[5])
        val readEvery: IntArray = org.safere.CapturePolicyProbe.integers(args[6])
        val documents: Int = Integer.parseInt(args[7])
        val output: Path? = Path.of(args[8])
        val compiled: Pattern = Pattern.compile(regex)
        val first: ByteArray = inputs[0].getBytes(StandardCharsets.UTF_8)
        val second: ByteArray = inputs[1].getBytes(StandardCharsets.UTF_8)
        val joined = ByteArray(first.size + second.size)
        System.arraycopy(first, 0, joined, 0, first.size)
        System.arraycopy(second, 0, joined, first.size, second.size)
        val matcher: Matcher =
            if (utf8)
                Matcher(compiled, Utf8InputScanner(joined, 0, joined.size))
            else
                compiled.matcher(inputs[0])
        val trace: StringBuilder =
            StringBuilder(
                ("mode\trepresentation\tphase\tdocument\tfindCalls\tchecksum\tsuccesses\treads\tattempts"
                        + "\thits\tcredit\tdfaMean\tcaptureMean\tdfaSamples\tcaptureSamples\tchoices"
                        + "\tcaptureChoices\treplays\trejections\twork\tuserReplayWork\n")
            )
        var checksum: Long = 0
        var calls: Long = 0
        for (phase in hitEvery.indices) {
            var hits = 0
            for (document in 0..<documents) {
                val positive = hitEvery[phase] > 0 && document % hitEvery[phase] == 0
                if (utf8) {
                    matcher.region(if (positive) 0 else first.size, if (positive) first.size else joined.size)
                } else {
                    matcher.reset(inputs[if (positive) 0 else 1])
                }
                while (true) {
                    calls++
                    if (!matcher.find()) {
                        break
                    }
                    checksum++
                    if (readEvery[phase] > 0 && hits % readEvery[phase] == 0) {
                        for (group in groups) {
                            val value: String?
                            if (utf8) {
                                val start: Int = matcher.start(group)
                                value =
                                    if (start < 0)
                                        null
                                    else
                                        String(
                                            joined, start, matcher.end(group) - start, StandardCharsets.UTF_8
                                        )
                            } else {
                                value = matcher.group(group)
                            }
                            checksum += (if (value == null) 0 else value.hashCode()).toLong()
                        }
                    }
                    hits++
                }
                val done = document + 1
                if (done <= 8 || (done and (done - 1)) == 0 || done == documents) {
                    val s: CapturePolicyExperiment.Snapshot = matcher.capturePolicySnapshot()
                    kotlin.check(!(s.choices() > calls)) { "More producer choices than search attempts" }
                    trace
                        .append(CapturePolicyExperiment.MODE)
                        .append('\t')
                        .append(args[4])
                        .append('\t')
                        .append(phase)
                        .append('\t')
                        .append(done)
                        .append('\t')
                        .append(calls)
                        .append('\t')
                        .append(checksum)
                        .append('\t')
                        .append(s.successes())
                        .append('\t')
                        .append(s.reads())
                        .append('\t')
                        .append(s.attempts())
                        .append('\t')
                        .append(s.hits())
                        .append('\t')
                        .append(s.credit())
                        .append('\t')
                        .append(s.dfaMean())
                        .append('\t')
                        .append(s.captureMean())
                        .append('\t')
                        .append(s.dfaSamples())
                        .append('\t')
                        .append(s.captureSamples())
                        .append('\t')
                        .append(s.choices())
                        .append('\t')
                        .append(s.captureChoices())
                        .append('\t')
                        .append(s.replays())
                        .append('\t')
                        .append(s.rejections())
                        .append('\t')
                        .append(s.work())
                        .append('\t')
                        .append(s.userReplayWork())
                        .append('\n')
                }
            }
        }
        Files.writeString(output, trace)
    }

    private fun integers(text: String): IntArray {
        return Arrays.stream(text.split(",")).mapToInt(Integer::parseInt).toArray()
    }
}
