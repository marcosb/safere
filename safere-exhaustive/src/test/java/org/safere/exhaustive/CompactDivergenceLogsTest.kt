// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.safere.exhaustive.CharacterClassDivergenceSweep.writeCompactReplayJson
import org.safere.exhaustive.CompactDivergenceLogs.DivergenceRecord
import org.safere.exhaustive.CompactDivergenceLogs.RecordConsumer
import org.safere.exhaustive.ZeroWidthQuantifierDivergenceSweep.divergenceClassificationNames
import org.safere.exhaustive.ZeroWidthQuantifierDivergenceSweep.divergenceClassificationStatuses
import org.safere.exhaustive.ZeroWidthQuantifierDivergenceSweep.totalCasesForTesting
import java.io.BufferedWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.List
import java.util.function.Predicate
import kotlin.streams.asStream

/** Tests for durable compact indexed-sweep divergence logs.  */
internal class CompactDivergenceLogsTest {
    @TempDir
    var tempDir: Path? = null

    @Test
    @Throws(Exception::class)
    fun writesFixedSizeRecordsAndAtomicProgress() {
        CompactDivergenceLogs(
            tempDir,
            "control-escape",
            0,
            100,
            100,
            2,
            mutableListOf<String?>("UNKNOWN"),
            List.of<DivergenceStatus?>(DivergenceStatus.UNKNOWN)
        ).use { logs ->
            logs.record(0, 12, 0)
            logs.record(1, 33, 0)
            logs.updateWorkerNextCaseIndex(0, 14)
            logs.updateWorkerNextCaseIndex(1, 35)
            logs.checkpoint(2)
        }
        Assertions.assertThat(Files.size(tempDir!!.resolve("divergence-indices/worker-00.bin")))
            .isEqualTo(CompactDivergenceLogs.RECORD_SIZE.toLong())
        Assertions.assertThat(Files.size(tempDir!!.resolve("divergence-indices/worker-01.bin")))
            .isEqualTo(CompactDivergenceLogs.RECORD_SIZE.toLong())
        Assertions.assertThat(Files.readString(tempDir!!.resolve("progress.json")))
            .contains(
                "\"checked\":2",
                "\"divergences\":2",
                "\"UNKNOWN\":2",
                "\"nextCaseIndex\":14",
                "\"durableBytes\":9"
            )
        Assertions.assertThat(Files.exists(tempDir!!.resolve("progress.json.tmp"))).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun ignoresIncompleteTrailingRecord() {
        CompactDivergenceLogs(
            tempDir,
            "control-escape",
            0,
            100,
            ControlEscapeDivergenceSweep.totalCases(),
            1,
            mutableListOf<String?>("UNKNOWN"),
            List.of<DivergenceStatus?>(DivergenceStatus.UNKNOWN)
        ).use { logs ->
            logs.record(0, 12, 0)
        }
        Files.write(
            tempDir!!.resolve("divergence-indices/worker-00.bin"),
            byteArrayOf(1, 2, 3),
            StandardOpenOption.APPEND
        )

        val records: MutableList<DivergenceRecord?> = ArrayList<DivergenceRecord?>()
        CompactDivergenceLogs.readRecords(
            tempDir,
            CompactDivergenceLogs.readManifest(tempDir),
            RecordConsumer { e: DivergenceRecord? -> records.add(e) })

        Assertions.assertThat<DivergenceRecord?>(records).containsExactly(DivergenceRecord(12, 0))
    }

    @Test
    @Throws(Exception::class)
    fun readsWorkerRecordsInCaseIndexOrder() {
        CompactDivergenceLogs(
            tempDir,
            "control-escape",
            0,
            100,
            100,
            2,
            mutableListOf<String?>("UNKNOWN"),
            List.of<DivergenceStatus?>(DivergenceStatus.UNKNOWN)
        ).use { logs ->
            logs.record(0, 0, 0)
            logs.record(0, 2, 0)
            logs.record(1, 1, 0)
            logs.record(1, 3, 0)
        }
        val records: MutableList<DivergenceRecord?> = ArrayList<DivergenceRecord?>()
        CompactDivergenceLogs.readRecordsSorted(
            tempDir,
            CompactDivergenceLogs.readManifest(tempDir),
            RecordConsumer { e: DivergenceRecord? -> records.add(e) })

        Assertions.assertThat<DivergenceRecord?>(records)
            .extracting(DivergenceRecord::caseIndex)
            .containsExactly(0L, 1L, 2L, 3L)
    }

    @Test
    @Throws(Exception::class)
    fun refusesToOverwriteExistingArchive() {
        CompactDivergenceLogs(
            tempDir,
            "control-escape",
            0,
            100,
            100,
            1,
            mutableListOf<String?>("UNKNOWN"),
            List.of<DivergenceStatus?>(DivergenceStatus.UNKNOWN)
        ).use { logs ->
            Assertions.assertThat<String?>(logs.classifications()).containsExactly("UNKNOWN")
        }
        Assertions.assertThat<Throwable?>(
            Assertions.catchThrowable(
                ThrowingCallable {
                    CompactDivergenceLogs(
                        tempDir,
                        "control-escape",
                        0,
                        100,
                        100,
                        1,
                        mutableListOf<String?>("UNKNOWN"),
                        List.of<DivergenceStatus?>(DivergenceStatus.UNKNOWN)
                    )
                })
        )
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("output directory already contains a compact sweep archive")
    }

    @Test
    @Throws(Exception::class)
    fun expanderWritesExactCountsAndSampledReplayJson() {
        CompactDivergenceLogs(
            tempDir,
            "control-escape",
            0,
            100,
            ControlEscapeDivergenceSweep.totalCases(),
            1,
            mutableListOf<String?>("UNKNOWN"),
            List.of<DivergenceStatus?>(DivergenceStatus.UNKNOWN)
        ).use { logs ->
            logs.record(0, 0, 0)
            logs.record(0, 1, 0)
            logs.record(0, 2, 0)
        }
        CompactDivergenceExpander.main(arrayOf<String>("--input-dir=" + tempDir, "--sample-limit=2"))

        Assertions.assertThat(Files.readString(tempDir!!.resolve("expanded/class-counts.tsv")))
            .contains("UNKNOWN\t3")
        val examples = Files.readAllLines(tempDir!!.resolve("expanded/unknown-sample.jsonl"))
        Assertions.assertThat<String?>(examples).hasSize(2)
        Assertions.assertThat<String?>(examples)
            .allMatch(Predicate { line: String? -> line!!.contains("\"classification\":\"UNKNOWN\"") })
        Assertions.assertThat<String?>(examples).allMatch(Predicate { line: String? -> line!!.contains("\"case\"") })
    }

    @Test
    @Throws(Exception::class)
    fun expanderRejectsStaleCaseCount() {
        CompactDivergenceLogs(
            tempDir,
            "control-escape",
            0,
            100,
            100,
            1,
            mutableListOf<String?>("UNKNOWN"),
            List.of<DivergenceStatus?>(DivergenceStatus.UNKNOWN)
        ).use { logs ->
            logs.record(0, 0, 0)
        }
        Assertions.assertThat<Throwable?>(
            Assertions.catchThrowable(
                ThrowingCallable {
                    CompactDivergenceExpander.main(
                        arrayOf<String>("--input-dir=" + tempDir, "--sample-limit=2")
                    )
                })
        )
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("compact log case count does not match current sweep")
    }

    @Test
    @Throws(Exception::class)
    fun expanderWritesAllReplayJsonWithoutSampling() {
        CompactDivergenceLogs(
            tempDir,
            "control-escape",
            0,
            100,
            ControlEscapeDivergenceSweep.totalCases(),
            1,
            mutableListOf<String?>("UNKNOWN"),
            List.of<DivergenceStatus?>(DivergenceStatus.UNKNOWN)
        ).use { logs ->
            logs.record(0, 0, 0)
            logs.record(0, 1, 0)
            logs.record(0, 2, 0)
        }
        CompactDivergenceExpander.main(arrayOf<String>("--input-dir=" + tempDir, "--sample-limit=all"))

        Assertions.assertThat<String>(Files.readAllLines(tempDir!!.resolve("expanded/unknown-sample.jsonl"))).hasSize(3)
    }

    @Test
    @Throws(Exception::class)
    fun characterClassBatchExpansionReconstructsSortedIndices() {
        val output = StringWriter()
        BufferedWriter(output).use { writer ->
            writeCompactReplayJson(writer, "UNKNOWN", mutableListOf<Long?>(0L, 1L, 2L))
        }
        Assertions.assertThat<String?>(output.toString().lineSequence().asStream()).hasSize(3)
    }

    @Test
    @Throws(Exception::class)
    fun expanderWritesAllCharacterClassReplayJsonWithSingleGeneratorWalk() {
        CompactDivergenceLogs(
            tempDir,
            "character-class",
            0,
            100,
            CharacterClassDivergenceSweep.totalCases(),
            2,
            mutableListOf<String?>("UNKNOWN"),
            List.of<DivergenceStatus?>(DivergenceStatus.UNKNOWN)
        ).use { logs ->
            logs.record(0, 0, 0)
            logs.record(0, 2, 0)
            logs.record(1, 1, 0)
        }
        CompactDivergenceExpander.main(arrayOf<String>("--input-dir=" + tempDir, "--sample-limit=all"))

        Assertions.assertThat<String>(Files.readAllLines(tempDir!!.resolve("expanded/unknown-sample.jsonl"))).hasSize(3)
    }

    @Test
    @Throws(Exception::class)
    fun expanderDefaultWritesUnknownExpectedZeroAndKnownIntentionalAuditSamples() {
        CompactDivergenceLogs(
            tempDir,
            "control-escape",
            0,
            100,
            ControlEscapeDivergenceSweep.totalCases(),
            1,
            mutableListOf<String?>("KNOWN", "EXPECTED", "UNKNOWN"),
            List.of<DivergenceStatus?>(
                DivergenceStatus.KNOWN_INTENTIONAL,
                DivergenceStatus.EXPECTED_ZERO,
                DivergenceStatus.UNKNOWN
            )
        ).use { logs ->
            logs.record(0, 0, 0)
            logs.record(0, 1, 1)
            logs.record(0, 2, 2)
        }
        CompactDivergenceExpander.main(arrayOf<String>("--input-dir=" + tempDir))

        Assertions.assertThat(Files.readString(tempDir!!.resolve("expanded/class-counts.tsv")))
            .contains("KNOWN\t1", "EXPECTED\t1", "UNKNOWN\t1")
        Assertions.assertThat<String>(Files.readAllLines(tempDir!!.resolve("expanded/known-sample.jsonl"))).hasSize(1)
        Assertions.assertThat<String>(Files.readAllLines(tempDir!!.resolve("expanded/expected-sample.jsonl")))
            .hasSize(1)
        Assertions.assertThat<String>(Files.readAllLines(tempDir!!.resolve("expanded/unknown-sample.jsonl"))).hasSize(1)

        CompactDivergenceExpander.main(arrayOf<String>("--input-dir=" + tempDir, "--sample-limit=1"))

        Assertions.assertThat<String>(Files.readAllLines(tempDir!!.resolve("expanded/known-sample.jsonl"))).hasSize(1)

        CompactDivergenceExpander.main(arrayOf<String>("--input-dir=" + tempDir))

        Assertions.assertThat<String>(Files.readAllLines(tempDir!!.resolve("expanded/known-sample.jsonl"))).hasSize(1)
    }

    @Test
    @Throws(Exception::class)
    fun auditSamplesKnownIntentionalBucketsAndReportsCurrentClassification() {
        val capturedEmptyRepeat =
            ZeroWidthQuantifierDivergenceSweep.cartesianCaseIndexForTesting("()", "%s", "*", "%s")
        val wrappedEmptyRepeat =
            ZeroWidthQuantifierDivergenceSweep.cartesianCaseIndexForTesting("()", "(?:%s)", "*", "%s")
        // ()* now publishes its empty capture under SafeRE's policy; the sweep still reports that
        // JDK difference as UNKNOWN. (?:())* agrees with the JDK and remains a no-divergence control.
        val sourceClass =
            divergenceClassificationNames()
                .indexOf("POSSESSIVE_QUANTIFIER_UNSUPPORTED")
        CompactDivergenceLogs(
            tempDir,
            "zero-width-quantifier",
            0,
            totalCasesForTesting(),
            totalCasesForTesting(),
            1,
            divergenceClassificationNames(),
            divergenceClassificationStatuses()
        ).use { logs ->
            logs.record(0, capturedEmptyRepeat, sourceClass)
            logs.record(0, wrappedEmptyRepeat, sourceClass)
        }
        CompactDivergenceAudit.main(arrayOf<String>("--input-dir=" + tempDir))

        Assertions.assertThat(Files.readString(tempDir!!.resolve("audit/source-counts.tsv")))
            .contains("POSSESSIVE_QUANTIFIER_UNSUPPORTED\t2\t2")
        Assertions.assertThat(Files.readString(tempDir!!.resolve("audit/transition-counts.tsv")))
            .contains(
                "POSSESSIVE_QUANTIFIER_UNSUPPORTED\tKNOWN_INTENTIONAL\tUNKNOWN\tUNKNOWN\t1",
                "POSSESSIVE_QUANTIFIER_UNSUPPORTED\tKNOWN_INTENTIONAL\tNO_DIVERGENCE\t"
                        + "NO_DIVERGENCE\t1"
            )
    }
}
