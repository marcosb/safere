// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

import com.google.gson.JsonArray
import java.io.*
import java.lang.AutoCloseable
import java.lang.Byte
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.List
import java.util.function.ToLongFunction
import kotlin.Array
import kotlin.Boolean
import kotlin.Comparator
import kotlin.Error
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.Long
import kotlin.LongArray
import kotlin.RuntimeException
import kotlin.String
import kotlin.Throwable
import kotlin.Throws
import kotlin.addSuppressed
import kotlin.arrayOfNulls
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.indices
import kotlin.plus
import kotlin.require

/** Durable fixed-size logs for divergences found by deterministic indexed sweeps.  */
internal class CompactDivergenceLogs(
    private val outputDir: Path,
    private val sweepName: String?,
    private val rangeStartInclusive: Long,
    private val rangeEndExclusive: Long,
    private val totalCases: Long,
    threads: Int,
    classifications: MutableList<String?>,
    classificationStatuses: MutableList<DivergenceStatus?>
) : AutoCloseable {
    private val indicesDir: Path
    private val classifications: MutableList<String?>
    private val classificationStatuses: MutableList<DivergenceStatus>
    private val workers: Array<WorkerLog>
    private var closed = false

    init {
        this.indicesDir = outputDir.resolve("divergence-indices")
        this.classifications = List.copyOf<String?>(classifications)
        this.classificationStatuses = List.copyOf<DivergenceStatus?>(classificationStatuses)
        require(classifications.size == classificationStatuses.size) { "classification names and statuses must have equal sizes" }
        require(
            !(Files.exists(outputDir.resolve("run-manifest.json"))
                    || Files.exists(outputDir.resolve("progress.json"))
                    || Files.exists(indicesDir))
        ) { "output directory already contains a compact sweep archive: " + outputDir }
        Files.createDirectories(indicesDir)
        this.workers = arrayOfNulls<WorkerLog>(threads)
        for (i in 0..<threads) {
            workers[i] =
                WorkerLog(
                    indicesDir.resolve(String.format(Locale.ROOT, "worker-%02d.bin", i)),
                    classifications.size
                )
        }
        writeManifest()
        checkpoint(0)
    }

    fun record(workerIndex: Int, caseIndex: Long, classificationId: Int) {
        require(!(classificationId < 0 || classificationId >= classifications.size)) { "invalid classification id: " + classificationId }
        try {
            workers[workerIndex].write(caseIndex, classificationId)
        } catch (e: IOException) {
            throw IllegalStateException("failed to write compact divergence record", e)
        }
    }

    fun updateWorkerNextCaseIndex(workerIndex: Int, nextCaseIndex: Long) {
        workers[workerIndex].updateNextCaseIndex(nextCaseIndex)
    }

    @Synchronized
    fun checkpoint(checked: Long) {
        if (closed) {
            return
        }
        try {
            val workerSnapshots: MutableList<WorkerSnapshot> = ArrayList<WorkerSnapshot>(workers.size)
            for (worker in workers) {
                workerSnapshots.add(worker.flushAndSnapshot())
            }
            val `object` = SweepJson.`object`()
            `object`.addProperty("formatVersion", FORMAT_VERSION)
            `object`.addProperty("sweep", sweepName)
            `object`.addProperty("checked", checked)
            var divergences: Long = 0
            for (snapshot in workerSnapshots) {
                divergences += snapshot.durableBytes / RECORD_SIZE
            }
            `object`.addProperty("divergences", divergences)
            val classCounts = SweepJson.`object`()
            for (i in classifications.indices) {
                var count: Long = 0
                for (snapshot in workerSnapshots) {
                    count += snapshot.classCounts!![i]
                }
                classCounts.addProperty(classifications.get(i), count)
            }
            `object`.add("classCounts", classCounts)
            val workerStates = JsonArray()
            for (i in workers.indices) {
                val workerObject = SweepJson.`object`()
                workerObject.addProperty("worker", i)
                workerObject.addProperty("nextCaseIndex", workerSnapshots.get(i).nextCaseIndex)
                workerObject.addProperty("durableBytes", workerSnapshots.get(i).durableBytes)
                workerStates.add(workerObject)
            }
            `object`.add("workers", workerStates)
            atomicWrite(outputDir.resolve("progress.json"), SweepJson.toJson(`object`))
        } catch (e: IOException) {
            throw IllegalStateException("failed to checkpoint compact divergence logs", e)
        }
    }

    fun classifications(): MutableList<String?> {
        return classifications
    }

    @Throws(IOException::class)
    private fun writeManifest() {
        val `object` = SweepJson.`object`()
        `object`.addProperty("formatVersion", FORMAT_VERSION)
        `object`.addProperty("recordSize", RECORD_SIZE)
        `object`.addProperty("sweep", sweepName)
        `object`.addProperty("rangeStart", rangeStartInclusive)
        `object`.addProperty("rangeEnd", rangeEndExclusive)
        `object`.addProperty("totalCases", totalCases)
        `object`.addProperty("threads", workers.size)
        val classes = JsonArray()
        for (classification in classifications) {
            classes.add(classification)
        }
        `object`.add("classifications", classes)
        val statuses = JsonArray()
        for (status in classificationStatuses) {
            statuses.add(status.name)
        }
        `object`.add("classificationStatuses", statuses)
        atomicWrite(outputDir.resolve("run-manifest.json"), SweepJson.toJson(`object`))
    }

    @Synchronized
    @Throws(IOException::class)
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        var failure: IOException? = null
        for (worker in workers) {
            try {
                worker.close()
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

    @JvmRecord
    internal data class Manifest(
        val formatVersion: Int,
        val recordSize: Int,
        val sweep: String?,
        val totalCases: Long,
        val threads: Int,
        val classifications: MutableList<String?>?,
        val classificationStatuses: MutableList<DivergenceStatus?>?
    )

    @JvmRecord
    internal data class DivergenceRecord(@JvmField val caseIndex: Long, val classificationId: Int)

    internal interface RecordConsumer {
        @Throws(IOException::class)
        fun accept(record: DivergenceRecord?)
    }

    private class WorkerInput(path: Path) : AutoCloseable {
        private val input: DataInputStream
        var record: DivergenceRecord? = null

        init {
            input = DataInputStream(BufferedInputStream(Files.newInputStream(path)))
        }

        @Throws(IOException::class)
        fun advance(): Boolean {
            try {
                record = DivergenceRecord(input.readLong(), input.readUnsignedByte())
                return true
            } catch (e: EOFException) {
                record = null
                return false
            }
        }

        @Throws(IOException::class)
        override fun close() {
            input.close()
        }
    }

    private class WorkerLog(path: Path, classificationCount: Int) : AutoCloseable {
        private val output: DataOutputStream
        private val classCounts: LongArray
        private var bytesWritten: Long = 0
        private var nextCaseIndex: Long = 0

        init {
            output =
                DataOutputStream(
                    BufferedOutputStream(
                        Files.newOutputStream(
                            path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
                        )
                    )
                )
            classCounts = LongArray(classificationCount)
        }

        @Synchronized
        @Throws(IOException::class)
        fun write(caseIndex: Long, classificationId: Int) {
            output.writeLong(caseIndex)
            output.writeByte(classificationId)
            bytesWritten += RECORD_SIZE.toLong()
            classCounts[classificationId]++
        }

        @Synchronized
        fun updateNextCaseIndex(nextCaseIndex: Long) {
            this.nextCaseIndex = nextCaseIndex
        }

        @Synchronized
        @Throws(IOException::class)
        fun flushAndSnapshot(): WorkerSnapshot {
            output.flush()
            return WorkerSnapshot(nextCaseIndex, bytesWritten, classCounts.clone())
        }

        @Synchronized
        @Throws(IOException::class)
        override fun close() {
            output.close()
        }
    }

    // classCounts is a private snapshot buffer; record value semantics are not used.
    @JvmRecord
    private data class WorkerSnapshot(val nextCaseIndex: Long, val durableBytes: Long, val classCounts: LongArray?)
    companion object {
        const val FORMAT_VERSION: Int = 2

        @JvmField
        val RECORD_SIZE: Int = java.lang.Long.BYTES + Byte.BYTES

        @Throws(IOException::class)
        private fun atomicWrite(path: Path, value: String?) {
            val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.writeString(
                temporary,
                value + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            Files.move(
                temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        }

        @JvmStatic
        @Throws(IOException::class)
        fun readManifest(inputDir: Path): Manifest {
            val `object` = SweepJson.parseObject(Files.readString(inputDir.resolve("run-manifest.json")))
            val formatVersion = SweepJson.integer(`object`, "formatVersion")
            val recordSize = SweepJson.integer(`object`, "recordSize")
            require(!(formatVersion != FORMAT_VERSION || recordSize != RECORD_SIZE)) { "unsupported compact divergence log format" }
            val classifications: MutableList<String?> = ArrayList<String?>()
            for (element in SweepJson.array(`object`, "classifications")) {
                classifications.add(element.asString)
            }
            val classificationStatuses: MutableList<DivergenceStatus?> = ArrayList<DivergenceStatus?>()
            for (element in SweepJson.array(`object`, "classificationStatuses")) {
                classificationStatuses.add(DivergenceStatus.valueOf(element.asString))
            }
            require(classifications.size == classificationStatuses.size) { "classification names and statuses must have equal sizes" }
            return Manifest(
                formatVersion,
                recordSize,
                SweepJson.string(`object`, "sweep"),
                SweepJson.longInteger(`object`, "totalCases"),
                SweepJson.integer(`object`, "threads"),
                classifications,
                classificationStatuses
            )
        }

        @JvmStatic
        @Throws(IOException::class)
        fun readRecords(inputDir: Path, manifest: Manifest, consumer: RecordConsumer) {
            require(!(manifest.formatVersion != FORMAT_VERSION || manifest.recordSize != RECORD_SIZE)) { "unsupported compact divergence log format" }
            val indicesDir = inputDir.resolve("divergence-indices")
            for (worker in 0..<manifest.threads) {
                val path = indicesDir.resolve(String.format(Locale.ROOT, "worker-%02d.bin", worker))
                if (!Files.exists(path)) {
                    continue
                }
                DataInputStream(BufferedInputStream(Files.newInputStream(path))).use { input ->
                    while (true) {
                        try {
                            val caseIndex = input.readLong()
                            val classificationId = input.readUnsignedByte()
                            consumer.accept(DivergenceRecord(caseIndex, classificationId))
                        } catch (e: EOFException) {
                            break
                        }
                    }
                }
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun readRecordsSorted(inputDir: Path, manifest: Manifest, consumer: RecordConsumer) {
            require(!(manifest.formatVersion != FORMAT_VERSION || manifest.recordSize != RECORD_SIZE)) { "unsupported compact divergence log format" }
            val inputs: MutableList<WorkerInput> = ArrayList<WorkerInput>()
            val queue: PriorityQueue<WorkerInput> =
                PriorityQueue<WorkerInput>(Comparator.comparingLong<WorkerInput?>(ToLongFunction { input: WorkerInput? -> input!!.record!!.caseIndex }))
            try {
                val indicesDir = inputDir.resolve("divergence-indices")
                for (worker in 0..<manifest.threads) {
                    val path = indicesDir.resolve(String.format(Locale.ROOT, "worker-%02d.bin", worker))
                    if (!Files.exists(path)) {
                        continue
                    }
                    val input = WorkerInput(path)
                    inputs.add(input)
                    if (input.advance()) {
                        queue.add(input)
                    }
                }
                while (!queue.isEmpty()) {
                    val input = queue.remove()
                    consumer.accept(input.record)
                    if (input.advance()) {
                        queue.add(input)
                    }
                }
            } catch (e: IOException) {
                closeInputs(inputs, e)
                throw e
            } catch (e: RuntimeException) {
                closeInputs(inputs, e)
                throw e
            } catch (e: Error) {
                closeInputs(inputs, e)
                throw e
            }
            closeInputs(inputs, null)
        }

        @Throws(IOException::class)
        private fun closeInputs(inputs: MutableList<WorkerInput>, primary: Throwable?) {
            var failure: IOException? = null
            for (input in inputs) {
                try {
                    input.close()
                } catch (e: IOException) {
                    if (failure == null) {
                        failure = e
                    } else {
                        failure.addSuppressed(e)
                    }
                }
            }
            if (failure != null) {
                if (primary != null) {
                    primary.addSuppressed(failure)
                } else {
                    throw failure
                }
            }
        }
    }
}
