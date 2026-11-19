// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.LongAdder

/** Accumulates classified exhaustive-sweep divergences and writes shared report files.  */
internal class ClassifiedDivergenceSummary<C>(
    private val classificationType: Class<C?>,
    private val unknownClass: C?,
    private val outputPrefix: String?,
    private val firstUnknownLimit: Int,
    private val actionableSampleLimit: Int
) where C : Enum<C?>?, C : DivergenceClassification? {
    private val counts: EnumMap<C?, LongAdder?>
    private val firstUnknownExamples: MutableList<DivergenceExample> = ArrayList<DivergenceExample>()
    private val actionableExamples: MutableList<DivergenceExample> = ArrayList<DivergenceExample>()

    init {
        this.counts = EnumMap<C?, LongAdder?>(classificationType)
        for (classification in classificationType.getEnumConstants()) {
            counts.put(classification, LongAdder())
        }
    }

    fun record(classification: C?, caseIndex: Long, json: String?) {
        counts.get(classification)!!.increment()
        if (json == null) {
            return
        }
        if (classification === unknownClass) {
            recordUnknown(json, caseIndex)
        } else if (classification!!.actionable()) {
            recordActionable(json, caseIndex)
        }
    }

    fun merge(other: ClassifiedDivergenceSummary<C?>?) {
        if (other == null) {
            return
        }
        for (classification in classificationType.getEnumConstants()) {
            counts.get(classification)!!.add(other.count(classification))
        }
        firstUnknownExamples.addAll(other.firstUnknownExamples)
        firstUnknownExamples.sort(Comparator.comparingLong<DivergenceExample?>(DivergenceExample::caseIndex))
        truncate(firstUnknownExamples, firstUnknownLimit)

        actionableExamples.addAll(other.actionableExamples)
        actionableExamples.sort(Comparator.comparingLong<DivergenceExample?>(DivergenceExample::caseIndex))
        truncate(actionableExamples, actionableSampleLimit)
    }

    fun count(classification: C?): Long {
        return counts.get(classification)!!.sum()
    }

    fun actionableCount(): Long {
        var total: Long = 0
        for (classification in classificationType.getEnumConstants()) {
            if (classification!!.actionable()) {
                total += count(classification)
            }
        }
        return total
    }

    @Throws(IOException::class)
    fun writeReports(outputDir: Path) {
        writeClassCounts(outputDir.resolve(outputPrefix + "-class-counts.tsv"))
        writeExamples(outputDir.resolve(outputPrefix + "-unknown-first.jsonl"), firstUnknownExamples)
        writeExamples(
            outputDir.resolve(outputPrefix + "-actionable-examples.jsonl"), actionableExamples
        )
    }

    private fun recordUnknown(json: String?, caseIndex: Long) {
        recordExample(firstUnknownExamples, DivergenceExample(caseIndex, json), firstUnknownLimit)
    }

    private fun recordActionable(json: String?, caseIndex: Long) {
        recordExample(
            actionableExamples, DivergenceExample(caseIndex, json), actionableSampleLimit
        )
    }

    @Throws(IOException::class)
    private fun writeClassCounts(path: Path) {
        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { writer ->
            writer.write("class\tstatus\tcount\trationale")
            writer.newLine()
            for (classification in classificationType.getEnumConstants()) {
                writer.write(classification!!.name)
                writer.write('\t'.code)
                writer.write(classification.status().name)
                writer.write('\t'.code)
                writer.write(count(classification).toString())
                writer.write('\t'.code)
                writer.write(classification.rationale())
                writer.newLine()
            }
        }
    }

    @JvmRecord
    private data class DivergenceExample(val caseIndex: Long, val json: String?)
    companion object {
        private fun recordExample(
            examples: MutableList<DivergenceExample>, example: DivergenceExample?, limit: Int
        ) {
            synchronized(examples) {
                if (examples.size < limit) {
                    examples.add(example!!)
                }
            }
        }

        @Throws(IOException::class)
        private fun writeExamples(path: Path, examples: MutableList<DivergenceExample>) {
            Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { writer ->
                for (example in examples) {
                    writer.write(example.json)
                    writer.newLine()
                }
            }
        }

        private fun truncate(list: MutableList<*>, limit: Int) {
            while (list.size > limit) {
                list.removeAt(list.size - 1)
            }
        }
    }
}
