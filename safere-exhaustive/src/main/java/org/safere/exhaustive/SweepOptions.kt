// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.min

/** Shared command-line options for exhaustive sweep tools.  */
@JvmRecord
internal data class SweepOptions(
    @JvmField val rangeStartInclusive: Long,
    @JvmField val rangeEndExclusive: Long,
    @JvmField val outputDir: Path?,
    @JvmField val progressInterval: Long,
    @JvmField val threads: Int,
    @JvmField val replayFile: Path?,
    val jsonlFileName: String?
) {
    fun jsonlPath(): Path {
        return outputDir!!.resolve(jsonlFileName)
    }

    fun totalChecks(totalCases: Long): Long {
        val end = min(rangeEndExclusive, totalCases)
        if (rangeStartInclusive >= end) {
            return 0
        }
        return end - rangeStartInclusive
    }

    fun printStartup(sweepName: String?) {
        println("sweep=" + sweepName)
        println("mode=" + (if (replayFile == null) "sweep" else "replay"))
        println("range=" + rangeDescription())
        println("outputDir=" + outputDir)
        println("progressInterval=" + progressInterval)
        println("threads=" + threads)
        println("replayFile=" + (if (replayFile == null) "" else replayFile))
        if (replayFile != null) {
            println("jsonl=" + jsonlPath())
        }
    }

    private fun rangeDescription(): String {
        val end = if (rangeEndExclusive == Long.MAX_VALUE) "" else rangeEndExclusive.toString()
        return rangeStartInclusive.toString() + ":" + end
    }

    companion object {
        fun parse(
            args: Array<String>, defaultOutputDir: Path?, jsonlFileName: String?, defaultProgressInterval: Long
        ): SweepOptions {
            var rangeEndExclusive = Long.MAX_VALUE
            var outputDir = defaultOutputDir
            var progressInterval = defaultProgressInterval
            var threads = Runtime.getRuntime().availableProcessors()
            for (arg in args) {
                if (arg.startsWith("--range=")) {
                    val value = arg.substring("--range=".length)
                    val colon = value.indexOf(':')
                    require(colon >= 0) { "--range must use start:end syntax" }
                    val start = value.substring(0, colon)
                    val end = value.substring(colon + 1)
                    rangeStartInclusive = if (start.isEmpty()) 0 else start.toLong()
                    rangeEndExclusive = if (end.isEmpty()) Long.MAX_VALUE else end.toLong()
                } else if (arg.startsWith("--output-dir=")) {
                    outputDir = Path.of(arg.substring("--output-dir=".length))
                } else if (arg.startsWith("--progress-interval=")) {
                    progressInterval = arg.substring("--progress-interval=".length).toLong()
                } else if (arg.startsWith("--threads=")) {
                    threads = arg.substring("--threads=".length).toInt()
                } else if (arg.startsWith("--replay-file=")) {
                    replayFile = Path.of(arg.substring("--replay-file=".length))
                } else {
                    throw IllegalArgumentException("unknown argument: " + arg)
                }
            }
            require(!(rangeStartInclusive < 0 || rangeEndExclusive < 0)) { "--range bounds must be non-negative" }
            require(rangeEndExclusive >= rangeStartInclusive) { "--range end must be greater than or equal to start" }
            require(progressInterval >= 1) { "--progress-interval must be at least 1" }
            require(threads >= 1) { "--threads must be at least 1" }
            require(!(replayFile != null && !Files.isRegularFile(replayFile))) { "--replay-file must be a regular file" }
            return SweepOptions(
                rangeStartInclusive,
                rangeEndExclusive,
                outputDir,
                progressInterval,
                threads,
                replayFile,
                jsonlFileName
            )
        }
    }
}
