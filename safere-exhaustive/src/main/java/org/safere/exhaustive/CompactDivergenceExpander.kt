// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

import org.safere.exhaustive.CompactDivergenceLogs.DivergenceRecord
import org.safere.exhaustive.CompactDivergenceLogs.RecordConsumer
import java.io.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

/** Expands compact indexed-sweep divergence logs into replayable JSONL reports.  */
object CompactDivergenceExpander {
    private const val DEFAULT_KNOWN_INTENTIONAL_AUDIT_SAMPLE_LIMIT = 1000

    @Throws(IOException::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val options: Options = Options.parse(args)
        val manifest: CompactDivergenceLogs.Manifest =
            CompactDivergenceLogs.Companion.readManifest(options.inputDir)
        val sweep = sweepDefinition(manifest.sweep)
        validateCompatibleSweep(manifest, sweep)
        val reservoirs: MutableMap<String?, Reservoir?> = LinkedHashMap<String?, Reservoir?>()
        for (i in manifest.classifications.indices) {
            val classification = manifest.classifications.get(i)
            reservoirs.put(
                classification,
                Reservoir(
                    reservoirLimit(options, manifest.classificationStatuses.get(i)),
                    classification.hashCode()
                )
            )
        }
        val expandedDir = options.inputDir!!.resolve("expanded")
        Files.createDirectories(expandedDir)
        deleteExampleFiles(expandedDir, manifest)
        if (options.sampleLimit == null) {
            expandSelected(
                options.inputDir,
                expandedDir,
                manifest,
                sweep,
                reservoirs,
                StatusPredicate { status: DivergenceStatus? -> status != DivergenceStatus.KNOWN_INTENTIONAL })
        } else if (options.sampleLimit < 0) {
            expandSelected(
                options.inputDir,
                expandedDir,
                manifest,
                sweep,
                reservoirs,
                StatusPredicate { unused: DivergenceStatus? -> true })
        } else {
            expandSampled(options.inputDir, expandedDir, manifest, sweep, reservoirs)
        }
        println("inputDir=" + options.inputDir)
        println("expandedDir=" + expandedDir)
        println("sampleLimit=" + options.displaySampleLimit())
        for (entry in reservoirs.entries) {
            println(entry.key + "=" + entry.value!!.count())
        }
    }

    private fun reservoirLimit(options: Options, status: DivergenceStatus?): Int {
        if (options.sampleLimit != null) {
            return options.sampleLimit
        }
        if (status == DivergenceStatus.KNOWN_INTENTIONAL) {
            return DEFAULT_KNOWN_INTENTIONAL_AUDIT_SAMPLE_LIMIT
        }
        return -1
    }

    @Throws(IOException::class)
    private fun expandSampled(
        inputDir: Path?,
        expandedDir: Path,
        manifest: CompactDivergenceLogs.Manifest,
        sweep: SweepDefinition,
        reservoirs: MutableMap<String?, Reservoir?>
    ) {
        CompactDivergenceLogs.Companion.readRecords(
            inputDir,
            manifest,
            RecordConsumer { record: DivergenceRecord? ->
                val classification = classification(manifest, record!!.classificationId)
                reservoirs.get(classification)!!.record(record.caseIndex)
            })

        writeCounts(expandedDir.resolve("class-counts.tsv"), reservoirs)
        for (entry in reservoirs.entries) {
            writeExamples(
                expandedDir.resolve(fileName(entry.key!!) + "-sample.jsonl"),
                entry.key,
                entry.value!!.samples(),
                sweep.exampleWriter!!
            )
        }
    }

    @Throws(IOException::class)
    private fun expandSelected(
        inputDir: Path?,
        expandedDir: Path,
        manifest: CompactDivergenceLogs.Manifest,
        sweep: SweepDefinition,
        reservoirs: MutableMap<String?, Reservoir?>,
        shouldExpand: StatusPredicate
    ) {
        val indexPaths: MutableMap<String, Path> = LinkedHashMap<String, Path>()
        val indexOutputs: MutableMap<String?, DataOutputStream?> = LinkedHashMap<String?, DataOutputStream?>()
        try {
            try {
                for (i in manifest.classifications.indices) {
                    val classification = manifest.classifications.get(i)
                    if (!shouldExpand.test(manifest.classificationStatuses.get(i))) {
                        continue
                    }
                    val path = expandedDir.resolve("." + fileName(classification) + "-indices.tmp")
                    indexPaths.put(classification, path)
                    indexOutputs.put(
                        classification,
                        DataOutputStream(BufferedOutputStream(Files.newOutputStream(path)))
                    )
                }
                CompactDivergenceLogs.Companion.readRecordsSorted(
                    inputDir,
                    manifest,
                    RecordConsumer { record: DivergenceRecord? ->
                        val classification = classification(manifest, record!!.classificationId)
                        reservoirs.get(classification)!!.record(record.caseIndex)
                        val output = indexOutputs.get(classification)
                        if (output != null) {
                            output.writeLong(record.caseIndex)
                        }
                    })
            } catch (e: IOException) {
                CompactDivergenceExpander.closeOutputs(indexOutputs.values, e)
                throw e
            } catch (e: RuntimeException) {
                CompactDivergenceExpander.closeOutputs(indexOutputs.values, e)
                throw e
            } catch (e: Error) {
                CompactDivergenceExpander.closeOutputs(indexOutputs.values, e)
                throw e
            }
            CompactDivergenceExpander.closeOutputs(indexOutputs.values, null)
            for (classification in indexPaths.keys) {
                Files.newBufferedWriter(
                    expandedDir.resolve(fileName(classification) + "-sample.jsonl"),
                    StandardCharsets.UTF_8
                ).use { writer ->
                    sweep.allExampleWriter!!.write(writer, classification, indexPaths.get(classification))
                }
            }
            for (i in manifest.classifications.indices) {
                if (manifest.classificationStatuses.get(i) != DivergenceStatus.KNOWN_INTENTIONAL) {
                    continue
                }
                val classification = manifest.classifications.get(i)
                writeExamples(
                    expandedDir.resolve(fileName(classification) + "-sample.jsonl"),
                    classification,
                    reservoirs.get(classification)!!.samples(),
                    sweep.exampleWriter!!
                )
            }
            writeCounts(expandedDir.resolve("class-counts.tsv"), reservoirs)
        } finally {
            for (path in indexPaths.values) {
                Files.deleteIfExists(path)
            }
        }
    }

    private fun classification(
        manifest: CompactDivergenceLogs.Manifest, classificationId: Int
    ): String? {
        require(!(classificationId < 0 || classificationId >= manifest.classifications.size)) { "invalid classification id in compact log" }
        return manifest.classifications.get(classificationId)
    }

    private fun validateCompatibleSweep(
        manifest: CompactDivergenceLogs.Manifest, sweep: SweepDefinition
    ) {
        // This is a best-effort guard against stale indices, not a proof that ordering is unchanged.
        require(manifest.totalCases == sweep.totalCases) {
            ("compact log case count does not match current sweep: archive="
                    + manifest.totalCases
                    + " current="
                    + sweep.totalCases)
        }
    }

    private fun sweepDefinition(sweep: String): SweepDefinition {
        return when (sweep) {
            "zero-width-quantifier" -> sweepDefinition(
                ZeroWidthQuantifierDivergenceSweep.totalCases(),
                CaseJson { obj: Long, caseIndex: String? ->
                    ZeroWidthQuantifierDivergenceSweep.compactReplayJson(
                        caseIndex
                    )
                })

            "grapheme-cluster" -> sweepDefinition(
                GraphemeClusterDivergenceSweep.totalCases(),
                CaseJson { obj: Long, caseIndex: String? -> GraphemeClusterDivergenceSweep.compactReplayJson(caseIndex) })

            "character-class" -> SweepDefinition(
                CharacterClassDivergenceSweep.totalCases(),
                CaseJson { obj: Long, caseIndex: String? -> CharacterClassDivergenceSweep.compactReplayJson(caseIndex) },
                ExampleWriter { obj: BufferedWriter?, writer: String?, classification: MutableList<Long?>? ->
                    CharacterClassDivergenceSweep.writeCompactReplayJson(
                        writer,
                        classification
                    )
                },
                AllExampleWriter { obj: BufferedWriter?, writer: String?, classification: Path? ->
                    CharacterClassDivergenceSweep.writeAllCompactReplayJson(
                        writer,
                        classification
                    )
                })

            "control-escape" -> sweepDefinition(
                ControlEscapeDivergenceSweep.totalCases(),
                CaseJson { obj: Long, caseIndex: String? -> ControlEscapeDivergenceSweep.compactReplayJson(caseIndex) })

            "unicode-character-class" -> sweepDefinition(
                UnicodeCharacterClassDivergenceSweep.totalCases(),
                CaseJson { obj: Long, caseIndex: String? ->
                    UnicodeCharacterClassDivergenceSweep.compactReplayJson(
                        caseIndex
                    )
                })

            "region-scalar" -> sweepDefinition(
                RegionScalarDivergenceSweep.totalCases(),
                CaseJson { obj: Long, caseIndex: String? -> RegionScalarDivergenceSweep.compactReplayJson(caseIndex) })

            "region-zero-width" -> sweepDefinition(
                RegionZeroWidthDivergenceSweep.totalCases(),
                CaseJson { obj: Long, caseIndex: String? -> RegionZeroWidthDivergenceSweep.compactReplayJson(caseIndex) })

            "dfa-sandwich-leftmost-start" -> sweepDefinition(
                DfaSandwichLeftmostStartDivergenceSweep.totalCases(),
                CaseJson { obj: Long, caseIndex: String? ->
                    DfaSandwichLeftmostStartDivergenceSweep.compactReplayJson(
                        caseIndex
                    )
                })

            "case-folding-character-class" -> sweepDefinition(
                CaseFoldingCharacterClassDivergenceSweep.totalCases(),
                CaseJson { obj: Long, caseIndex: String? ->
                    CaseFoldingCharacterClassDivergenceSweep.compactReplayJson(
                        caseIndex
                    )
                })

            else -> throw IllegalArgumentException("unsupported indexed sweep: " + sweep)
        }
    }

    private fun sweepDefinition(totalCases: Long, caseJson: CaseJson): SweepDefinition {
        return SweepDefinition(
            totalCases,
            caseJson,
            ExampleWriter { writer: BufferedWriter?, classification: String?, indices: MutableList<Long?>? ->
                for (caseIndex in indices!!) {
                    writer!!.write(caseJson.toJson(caseIndex!!, classification))
                    writer.newLine()
                }
            },
            AllExampleWriter { writer: BufferedWriter?, classification: String?, indicesPath: Path? ->
                DataInputStream(BufferedInputStream(Files.newInputStream(indicesPath))).use { input ->
                    while (true) {
                        try {
                            writer!!.write(caseJson.toJson(input.readLong(), classification))
                            writer.newLine()
                        } catch (e: EOFException) {
                            return@AllExampleWriter
                        }
                    }
                }
            })
    }

    @Throws(IOException::class)
    private fun writeCounts(path: Path, reservoirs: MutableMap<String?, Reservoir?>) {
        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { writer ->
            writer.write("classification\tcount")
            writer.newLine()
            for (entry in reservoirs.entries) {
                writer.write(entry.key)
                writer.write('\t'.code)
                writer.write(entry.value!!.count().toString())
                writer.newLine()
            }
        }
    }

    @Throws(IOException::class)
    private fun writeExamples(
        path: Path, classification: String?, indices: MutableList<Long?>, exampleWriter: ExampleWriter
    ) {
        indices.sort(Comparator { x: Long?, y: Long? -> java.lang.Long.compare(x!!, y!!) })
        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { writer ->
            exampleWriter.write(writer, classification, indices)
        }
    }

    private fun fileName(classification: String): String {
        return classification.lowercase().replace('_', '-')
    }

    @Throws(IOException::class)
    private fun deleteExampleFiles(expandedDir: Path, manifest: CompactDivergenceLogs.Manifest) {
        for (classification in manifest.classifications) {
            Files.deleteIfExists(expandedDir.resolve(fileName(classification) + "-sample.jsonl"))
        }
    }

    @Throws(IOException::class)
    private fun closeOutputs(outputs: Iterable<DataOutputStream>, primary: Throwable?) {
        var failure: IOException? = null
        for (output in outputs) {
            try {
                output.close()
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

    @JvmRecord
    private data class Options(val inputDir: Path?, val sampleLimit: Int?) {
        fun displaySampleLimit(): String {
            if (sampleLimit == null) {
                return "default-known-intentional-audit-" + DEFAULT_KNOWN_INTENTIONAL_AUDIT_SAMPLE_LIMIT
            }
            return if (sampleLimit < 0) "all" else sampleLimit.toString()
        }

        companion object {
            fun parse(args: Array<String>): Options {
                for (arg in args) {
                    if (arg.startsWith("--input-dir=")) {
                        inputDir = Path.of(arg.substring("--input-dir=".length))
                    } else if (arg.startsWith("--sample-limit=")) {
                        val value = arg.substring("--sample-limit=".length)
                        sampleLimit = if (value == "all") -1 else value.toInt()
                    } else {
                        throw IllegalArgumentException("unknown argument: " + arg)
                    }
                }
                requireNotNull(inputDir) { "--input-dir is required" }
                require(!(sampleLimit != null && sampleLimit < -1)) { "--sample-limit must be non-negative or all" }
                return Options(inputDir, sampleLimit)
            }
        }
    }

    private class Reservoir(private val limit: Int, seed: Int) {
        private val random: Random
        private val samples: MutableList<Long?> = ArrayList<Long?>()
        private var count: Long = 0

        init {
            this.random = Random(seed.toLong())
        }

        fun record(caseIndex: Long) {
            count++
            if (limit < 0) {
                return
            }
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

    internal interface CaseJson {
        fun toJson(caseIndex: Long, classification: String?): String?
    }

    internal interface ExampleWriter {
        @Throws(IOException::class)
        fun write(writer: BufferedWriter?, classification: String?, indices: MutableList<Long?>?)
    }

    internal interface AllExampleWriter {
        @Throws(IOException::class)
        fun write(writer: BufferedWriter?, classification: String?, indicesPath: Path?)
    }

    internal interface StatusPredicate {
        fun test(status: DivergenceStatus?): Boolean
    }

    @JvmRecord
    private data class SweepDefinition(
        val totalCases: Long,
        val caseJson: CaseJson?,
        val exampleWriter: ExampleWriter?,
        val allExampleWriter: AllExampleWriter?
    )
}
