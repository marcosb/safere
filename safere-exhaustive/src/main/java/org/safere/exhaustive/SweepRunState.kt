// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

import java.io.BufferedWriter
import java.io.IOException
import java.lang.AutoCloseable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder
import java.util.function.LongSupplier
import kotlin.math.max
import kotlin.math.min

/** Shared state and reporting for exhaustive sweep runs.  */
internal class SweepRunState @JvmOverloads constructor(
    options: SweepOptions,
    totalChecks: Long,
    nanoTime: LongSupplier = LongSupplier { System.nanoTime() }
) : AutoCloseable {
    val options: SweepOptions
    val totalChecks: Long
    @JvmField
    val checked: LongAdder = LongAdder()
    @JvmField
    val divergences: LongAdder = LongAdder()
    private var jsonlWriter: BufferedWriter? = null
    private val nanoTime: LongSupplier
    private val startNanos: Long
    private var compactLogs: CompactDivergenceLogs? = null
    private var nextCompactCheckpointNanos: Long
    @JvmField
    var generated: Long = 0
    var nextCheckedProgressReport: Long

    init {
        require(totalChecks >= 0) { "totalChecks must be non-negative" }
        this.options = options
        this.totalChecks = totalChecks
        this.nanoTime = nanoTime
        this.startNanos = nanoTime.getAsLong()
        this.nextCompactCheckpointNanos = startNanos + COMPACT_CHECKPOINT_INTERVAL_NANOS
        this.nextCheckedProgressReport = options.progressInterval
    }

    @Synchronized
    fun recordGenerated(workerGenerated: Long) {
        if (workerGenerated > generated) {
            generated = workerGenerated
        }
    }

    @Synchronized
    fun reportProgressIfNeeded(workerGenerated: Long) {
        recordGenerated(workerGenerated)
        val checkedCount = checked.sum()
        if (checkedCount < nextCheckedProgressReport) {
            return
        }
        val progress = if (totalChecks == 0L) 100.0 else checkedCount * 100.0 / totalChecks
        val elapsedNanos = max(0, nanoTime.getAsLong() - startNanos)
        val remainingNanos = estimatedRemainingNanos(elapsedNanos, checkedCount)
        System.out.printf(
            Locale.ROOT,
            "progress=%.1f%% elapsed=%s eta=%s total=%,d checked=%,d divergences=%,d%n",
            progress,
            formatDuration(elapsedNanos),
            formatDuration(remainingNanos),
            totalChecks,
            checkedCount,
            divergences.sum()
        )
        while (nextCheckedProgressReport <= checkedCount) {
            nextCheckedProgressReport += options.progressInterval
        }
        checkpointCompactLogs()
    }

    private fun estimatedRemainingNanos(elapsedNanos: Long, checkedCount: Long): Long {
        if (checkedCount == 0L || checkedCount >= totalChecks) {
            return 0
        }
        val remainingChecks = totalChecks - checkedCount
        return (elapsedNanos * (remainingChecks.toDouble() / checkedCount)).toLong()
    }

    fun recordDivergence() {
        divergences.increment()
    }

    @Synchronized
    @Throws(IOException::class)
    fun enableCompactLogs(
        sweepName: String?,
        totalCases: Long,
        classifications: MutableList<String?>?,
        classificationStatuses: MutableList<DivergenceStatus?>?
    ) {
        check(compactLogs == null) { "compact divergence logs already enabled" }
        compactLogs =
            CompactDivergenceLogs(
                options.outputDir,
                sweepName,
                options.rangeStartInclusive,
                min(options.rangeEndExclusive, totalCases),
                totalCases,
                options.threads,
                classifications,
                classificationStatuses
            )
    }

    fun recordCompactDivergence(workerIndex: Int, caseIndex: Long, classificationId: Int) {
        recordDivergence()
        val logs = compactLogs
        checkNotNull(logs) { "compact divergence logs are not enabled" }
        logs.record(workerIndex, caseIndex, classificationId)
    }

    fun updateWorkerNextCaseIndex(workerIndex: Int, nextCaseIndex: Long) {
        val logs = compactLogs
        if (logs != null) {
            logs.updateWorkerNextCaseIndex(workerIndex, nextCaseIndex)
        }
    }

    @Synchronized
    fun checkpointCompactLogs() {
        if (compactLogs != null) {
            compactLogs!!.checkpoint(checked.sum())
            nextCompactCheckpointNanos = nanoTime.getAsLong() + COMPACT_CHECKPOINT_INTERVAL_NANOS
        }
    }

    @Synchronized
    fun checkpointCompactLogsIfNeeded() {
        if (compactLogs != null && nanoTime.getAsLong() >= nextCompactCheckpointNanos) {
            checkpointCompactLogs()
        }
    }

    @Synchronized
    fun appendJsonl(line: String) {
        try {
            if (jsonlWriter == null) {
                jsonlWriter =
                    Files.newBufferedWriter(
                        options.jsonlPath(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                    )
            }
            jsonlWriter!!.write(line)
            jsonlWriter!!.newLine()
        } catch (e: IOException) {
            throw IllegalStateException("failed to write divergence report", e)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    override fun close() {
        var failure: IOException? = null
        checkpointCompactLogs()
        if (compactLogs != null) {
            try {
                compactLogs!!.close()
            } catch (e: IOException) {
                failure = e
            }
        }
        if (jsonlWriter != null) {
            try {
                jsonlWriter!!.close()
            } catch (e: IOException) {
                if (failure == null) {
                    failure = e
                } else {
                    failure.addSuppressed(e)
                }
            }
        }
        if (failure != null) {
            throw failure
        }
    }

    companion object {
        private val COMPACT_CHECKPOINT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30)

        private fun formatDuration(nanos: Long): String {
            val totalSeconds = TimeUnit.NANOSECONDS.toSeconds(nanos)
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            if (hours > 0) {
                return String.format(Locale.ROOT, "%dh%02dm%02ds", hours, minutes, seconds)
            }
            if (minutes > 0) {
                return String.format(Locale.ROOT, "%dm%02ds", minutes, seconds)
            }
            return seconds.toString() + "s"
        }
    }
}
