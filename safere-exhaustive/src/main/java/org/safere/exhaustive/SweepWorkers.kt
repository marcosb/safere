// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/** Threading utilities for exhaustive sweep workers.  */
internal object SweepWorkers {
    @JvmStatic
    @Throws(IOException::class)
    fun run(threads: Int, threadNamePrefix: String?, worker: Worker) {
        if (threads == 1) {
            try {
                worker.run(0)
            } catch (t: Throwable) {
                propagate(t)
            }
            return
        }

        val failure = AtomicReference<Throwable?>()
        val workers: MutableList<Thread> = ArrayList<Thread>()
        for (i in 0..<threads) {
            val workerIndex = i
            val thread =
                Thread(
                    Runnable {
                        try {
                            worker.run(workerIndex)
                        } catch (t: Throwable) {
                            failure.compareAndSet(null, t)
                        }
                    },
                    threadNamePrefix + workerIndex
                )
            thread.start()
            workers.add(thread)
        }
        for (thread in workers) {
            try {
                thread.join()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("interrupted while waiting for sweep workers", e)
            }
        }
        val throwable = failure.get()
        if (throwable != null) {
            propagate(throwable)
        }
    }

    @Throws(IOException::class)
    private fun propagate(throwable: Throwable?) {
        if (throwable is Error) {
            throw throwable
        }
        if (throwable is RuntimeException) {
            throw throwable
        }
        if (throwable is IOException) {
            throw throwable
        }
        throw IOException("sweep worker failed", throwable)
    }

    @JvmStatic
    fun firstProgressAt(rangeStartInclusive: Long, progressInterval: Long): Long {
        if (rangeStartInclusive <= 0) {
            return progressInterval
        }
        val remainder = rangeStartInclusive % progressInterval
        if (remainder == 0L) {
            return rangeStartInclusive
        }
        return rangeStartInclusive + (progressInterval - remainder)
    }

    @JvmStatic
    fun progressProbeInterval(progressInterval: Long, threads: Int): Long {
        return max(1, min(10000, progressInterval / threads))
    }

    @JvmStatic
    fun firstOwnedCaseIndex(
        rangeStartInclusive: Long, rangeEndExclusive: Long, threads: Int, workerIndex: Int
    ): Long {
        if (rangeStartInclusive >= rangeEndExclusive) {
            return rangeEndExclusive
        }
        val remainder = rangeStartInclusive % threads
        val delta = (workerIndex - remainder + threads) % threads
        if (delta >= rangeEndExclusive - rangeStartInclusive) {
            return rangeEndExclusive
        }
        return rangeStartInclusive + delta
    }

    @JvmStatic
    @Throws(IOException::class)
    fun runStreamingLines(
        threads: Int, threadNamePrefix: String?, reader: BufferedReader, worker: LineWorker
    ): Long {
        val queue: BlockingQueue<QueuedLine?> = ArrayBlockingQueue<QueuedLine?>(max(128, threads * 32))
        val produced = AtomicLong()
        val failure = AtomicReference<Throwable?>()
        val producer =
            Thread(
                Runnable { produceLines(reader, threads, queue, produced, failure) },
                threadNamePrefix + "reader"
            )
        val workers: MutableList<Thread> = ArrayList<Thread>()
        producer.start()
        for (i in 0..<threads) {
            val thread = Thread(Runnable { consumeLines(queue, worker, failure) }, threadNamePrefix + i)
            thread.start()
            workers.add(thread)
        }
        join(producer, "interrupted while waiting for replay reader")
        for (thread in workers) {
            join(thread, "interrupted while waiting for replay workers")
        }
        val throwable = failure.get()
        if (throwable != null) {
            propagate(throwable)
        }
        return produced.get()
    }

    private fun produceLines(
        reader: BufferedReader,
        threads: Int,
        queue: BlockingQueue<QueuedLine?>,
        produced: AtomicLong,
        failure: AtomicReference<Throwable?>
    ) {
        try {
            var line: String?
            while (failure.get() == null && (reader.readLine().also { line = it }) != null) {
                val trimmed = line!!.trim { it <= ' ' }
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue
                }
                putQueuedLine(queue, QueuedLine(trimmed, false), failure)
                produced.incrementAndGet()
            }
        } catch (t: Throwable) {
            failure.compareAndSet(null, t)
        } finally {
            if (failure.get() != null) {
                queue.clear()
            }
            for (i in 0..<threads) {
                putQueuedLine(queue, QueuedLine("", true), failure)
            }
        }
    }

    private fun consumeLines(
        queue: BlockingQueue<QueuedLine?>, worker: LineWorker, failure: AtomicReference<Throwable?>
    ) {
        try {
            while (true) {
                val queuedLine = queue.poll(100, TimeUnit.MILLISECONDS)
                if (queuedLine == null) {
                    if (failure.get() != null) {
                        return
                    }
                    continue
                }
                if (queuedLine.poison) {
                    return
                }
                worker.run(queuedLine.line)
            }
        } catch (t: Throwable) {
            failure.compareAndSet(null, t)
        }
    }

    private fun putQueuedLine(
        queue: BlockingQueue<QueuedLine?>, queuedLine: QueuedLine?, failure: AtomicReference<Throwable?>
    ) {
        try {
            while (failure.get() == null && !queue.offer(queuedLine, 100, TimeUnit.MILLISECONDS)) {
                // Retry until a worker drains the bounded queue or another thread fails.
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            failure.compareAndSet(null, e)
        }
    }

    @Throws(IOException::class)
    private fun join(thread: Thread, message: String?) {
        try {
            thread.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException(message, e)
        }
    }

    internal class ProgressReporter(private val runState: SweepRunState, private val workerIndex: Int) {
        private val probeInterval: Long
        private var checkedByWorker: Long = 0
        private var nextProbe: Long

        init {
            this.probeInterval =
                progressProbeInterval(runState.options.progressInterval, runState.options.threads)
            this.nextProbe = probeInterval
        }

        fun checked() {
            checkedByWorker++
            runState.checked.increment()
        }

        fun reportIfNeeded(generated: Long) {
            runState.updateWorkerNextCaseIndex(workerIndex, generated)
            if (checkedByWorker < nextProbe) {
                return
            }
            runState.checkpointCompactLogsIfNeeded()
            runState.reportProgressIfNeeded(generated)
            while (nextProbe <= checkedByWorker) {
                nextProbe += probeInterval
            }
        }
    }

    internal interface Worker {
        @Throws(Exception::class)
        fun run(workerIndex: Int)
    }

    internal interface LineWorker {
        @Throws(Exception::class)
        fun run(line: String?)
    }

    @JvmRecord
    private data class QueuedLine(val line: String?, val poison: Boolean)
}
