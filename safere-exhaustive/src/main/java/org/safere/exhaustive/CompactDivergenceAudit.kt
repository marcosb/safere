// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

import org.safere.exhaustive.CompactDivergenceLogs.DivergenceRecord
import org.safere.exhaustive.CompactDivergenceLogs.RecordConsumer
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

/** Replays sampled compact known-divergence buckets through the current sweep classifier.  */
object CompactDivergenceAudit {
    private const val DEFAULT_SAMPLE_LIMIT = 1000
    private const val NO_DIVERGENCE = "NO_DIVERGENCE"
    private const val NO_DIVERGENCE_STATUS = "NO_DIVERGENCE"

    @Throws(IOException::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val options: Options = Options.Companion.parse(args)
        val manifest: CompactDivergenceLogs.Manifest =
            CompactDivergenceLogs.Companion.readManifest(options.inputDir)
        val sweep = auditSweep(manifest.sweep)
        validateCompatibleSweep(manifest, sweep)

        val sourceSamples = sampleSourceBuckets(options, manifest)
        val transitions: MutableMap<String, MutableMap<String?, Long?>?> =
            LinkedHashMap<String, MutableMap<String?, Long?>?>()
        for (entry in sourceSamples.entries) {
            val sourceClass = entry.key
            val replayCounts: MutableMap<String?, Long?> = LinkedHashMap<String?, Long?>()
            transitions.put(sourceClass!!, replayCounts)
            for (caseIndex in entry.value!!.samples()) {
                val replayClass = sweep.classify(caseIndex!!)
                replayCounts.merge(replayClass.name, 1L) { a: Long?, b: Long? -> java.lang.Long.sum(a!!, b!!) }
            }
        }

        val outputDir = options.outputDir
        Files.createDirectories(outputDir)
        writeSourceCounts(outputDir.resolve("source-counts.tsv"), sourceSamples)
        writeTransitions(outputDir.resolve("transition-counts.tsv"), manifest, sweep, transitions)

        println("inputDir=" + options.inputDir)
        println("outputDir=" + outputDir)
        println("sampleLimit=" + options.sampleLimit)
        for (entry in sourceSamples.entries) {
            println(entry.key + "=" + entry.value!!.count())
        }
    }

    @Throws(IOException::class)
    private fun sampleSourceBuckets(
        options: Options, manifest: CompactDivergenceLogs.Manifest
    ): MutableMap<String?, SourceSamples?> {
        val samples: MutableMap<String?, SourceSamples?> = LinkedHashMap<String?, SourceSamples?>()
        for (i in manifest.classifications.indices) {
            if (manifest.classificationStatuses.get(i) != DivergenceStatus.KNOWN_INTENTIONAL) {
                continue
            }
            val classification = manifest.classifications.get(i)
            samples.put(
                classification, SourceSamples(options.sampleLimit, classification.hashCode())
            )
        }
        CompactDivergenceLogs.Companion.readRecords(
            options.inputDir,
            manifest,
            RecordConsumer { record: DivergenceRecord? ->
                val classification = manifest.classifications.get(record!!.classificationId)
                val sourceSamples = samples.get(classification)
                if (sourceSamples != null) {
                    sourceSamples.record(record.caseIndex)
                }
            })
        return samples
    }

    @Throws(IOException::class)
    private fun writeSourceCounts(path: Path, sourceSamples: MutableMap<String?, SourceSamples?>) {
        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { writer ->
            writer.write("sourceClass\tsourceCount\tsampled")
            writer.newLine()
            for (entry in sourceSamples.entries) {
                writer.write(entry.key)
                writer.write('\t'.code)
                writer.write(entry.value!!.count().toString())
                writer.write('\t'.code)
                writer.write(entry.value!!.samples().size.toString())
                writer.newLine()
            }
        }
    }

    @Throws(IOException::class)
    private fun writeTransitions(
        path: Path,
        manifest: CompactDivergenceLogs.Manifest,
        sweep: AuditSweep,
        transitions: MutableMap<String, MutableMap<String?, Long?>?>
    ) {
        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { writer ->
            writer.write("sourceClass\tsourceStatus\treplayClass\treplayStatus\tcount")
            writer.newLine()
            for (sourceEntry in transitions.entries) {
                val sourceClass = sourceEntry.key
                val sourceStatus = sourceStatus(manifest, sourceClass)
                for (replayEntry in sourceEntry.value!!.entries) {
                    writer.write(sourceClass)
                    writer.write('\t'.code)
                    writer.write(sourceStatus)
                    writer.write('\t'.code)
                    writer.write(replayEntry.key)
                    writer.write('\t'.code)
                    writer.write(CompactDivergenceAudit.replayStatus(sweep, replayEntry.key!!))
                    writer.write('\t'.code)
                    writer.write(replayEntry.value.toString())
                    writer.newLine()
                }
            }
        }
    }

    private fun sourceStatus(manifest: CompactDivergenceLogs.Manifest, sourceClass: String?): String {
        val index = manifest.classifications.indexOf(sourceClass)
        require(index >= 0) { "source class is not in manifest: " + sourceClass }
        return manifest.classificationStatuses.get(index).name
    }

    private fun replayStatus(sweep: AuditSweep, replayClass: String): String {
        if (replayClass == NO_DIVERGENCE) {
            return NO_DIVERGENCE_STATUS
        }
        val status: DivergenceStatus = sweep.statuses!!.get(replayClass)!!
        requireNotNull(status) { "replay class is not in current sweep: " + replayClass }
        return status.name
    }

    private fun validateCompatibleSweep(
        manifest: CompactDivergenceLogs.Manifest, sweep: AuditSweep
    ) {
        // This is a best-effort guard against stale indices, not a proof that ordering is unchanged.
        require(manifest.totalCases == sweep.totalCases) {
            ("compact log case count does not match current sweep: archive="
                    + manifest.totalCases
                    + " current="
                    + sweep.totalCases)
        }
    }

    private fun auditSweep(sweep: String): AuditSweep {
        return when (sweep) {
            "zero-width-quantifier" -> AuditSweep(
                ZeroWidthQuantifierDivergenceSweep.totalCases(),
                ZeroWidthQuantifierDivergenceSweep.divergenceClassificationStatusMap(),
                AuditCaseClassifier { caseIndex: Long ->
                    val classification =
                        ZeroWidthQuantifierDivergenceSweep.auditClassificationName(caseIndex)
                    if (classification == null) {
                        return@AuditCaseClassifier AuditClassification(NO_DIVERGENCE)
                    }
                    AuditClassification(classification)
                })

            else -> throw IllegalArgumentException("unsupported compact audit sweep: " + sweep)
        }
    }

    @JvmRecord
    private data class Options(val inputDir: Path?, val outputDir: Path, val sampleLimit: Int) {
        companion object {
            fun parse(args: Array<String>): Options {
                var sampleLimit = DEFAULT_SAMPLE_LIMIT
                for (arg in args) {
                    if (arg.startsWith("--input-dir=")) {
                        inputDir = Path.of(arg.substring("--input-dir=".length))
                    } else if (arg.startsWith("--output-dir=")) {
                        outputDir = Path.of(arg.substring("--output-dir=".length))
                    } else if (arg.startsWith("--sample-limit=")) {
                        sampleLimit = arg.substring("--sample-limit=".length).toInt()
                    } else {
                        throw IllegalArgumentException("unknown argument: " + arg)
                    }
                }
                requireNotNull(inputDir) { "--input-dir is required" }
                if (outputDir == null) {
                    outputDir = inputDir.resolve("audit")
                }
                require(sampleLimit >= 0) { "--sample-limit must be non-negative" }
                return Options(inputDir, outputDir, sampleLimit)
            }
        }
    }

    private class SourceSamples(private val limit: Int, seed: Int) {
        private val random: Random
        private val samples: MutableList<Long?> = ArrayList<Long?>()
        private var count: Long = 0

        init {
            this.random = Random(seed.toLong())
        }

        fun record(caseIndex: Long) {
            count++
            if (samples.size < limit) {
                samples.add(caseIndex)
                return
            }
            val replacement = random.nextLong(count)
            if (replacement < limit) {
                samples.set(replacement.toInt(), caseIndex)
            }
        }

        fun count(): Long {
            return count
        }

        fun samples(): MutableList<Long?> {
            return samples
        }
    }

    @JvmRecord
    private data class AuditClassification(val name: String?)

    private interface AuditCaseClassifier {
        fun classify(caseIndex: Long): AuditClassification
    }

    @JvmRecord
    private data class AuditSweep(
        val totalCases: Long,
        val statuses: MutableMap<String?, DivergenceStatus>?,
        val classifier: AuditCaseClassifier?
    ) {
        fun classify(caseIndex: Long): AuditClassification {
            return classifier!!.classify(caseIndex)
        }
    }
}
