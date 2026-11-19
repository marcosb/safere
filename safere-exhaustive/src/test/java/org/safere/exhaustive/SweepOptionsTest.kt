// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Tests for [SweepOptions].  */
internal class SweepOptionsTest {
    @TempDir
    var tempDir: Path? = null

    @Test
    fun defaultsUseRuntimeProcessorsAndSweepDefaults() {
        val options =
            SweepOptions.parse(arrayOfNulls<String>(0), tempDir!!.resolve("out"), "divergences.jsonl", 123)

        Assertions.assertThat(options.rangeStartInclusive).isZero()
        Assertions.assertThat(options.rangeEndExclusive).isEqualTo(Long.Companion.MAX_VALUE)
        Assertions.assertThat(options.outputDir).isEqualTo(tempDir!!.resolve("out"))
        Assertions.assertThat(options.progressInterval).isEqualTo(123)
        Assertions.assertThat(options.threads).isEqualTo(Runtime.getRuntime().availableProcessors())
        Assertions.assertThat(options.replayFile).isNull()
        Assertions.assertThat(options.jsonlPath()).isEqualTo(tempDir!!.resolve("out").resolve("divergences.jsonl"))
    }

    @Test
    @Throws(Exception::class)
    fun parsesAllSupportedOptions() {
        val replayFile = tempDir!!.resolve("replay.jsonl")
        Files.writeString(replayFile, "{}\n")

        val options =
            SweepOptions.parse(
                arrayOf<String>(
                    "--range=5:10",
                    "--output-dir=" + tempDir!!.resolve("custom"),
                    "--progress-interval=99",
                    "--threads=3",
                    "--replay-file=" + replayFile
                ),
                tempDir!!.resolve("out"),
                "divergences.jsonl",
                123
            )

        Assertions.assertThat(options.rangeStartInclusive).isEqualTo(5)
        Assertions.assertThat(options.rangeEndExclusive).isEqualTo(10)
        Assertions.assertThat(options.outputDir).isEqualTo(tempDir!!.resolve("custom"))
        Assertions.assertThat(options.progressInterval).isEqualTo(99)
        Assertions.assertThat(options.threads).isEqualTo(3)
        Assertions.assertThat(options.replayFile).isEqualTo(replayFile)
    }

    @Test
    fun parsesOpenEndedRange() {
        val options =
            SweepOptions.parse(
                arrayOf<String>("--range=5:"), tempDir!!.resolve("out"), "divergences.jsonl", 123
            )

        Assertions.assertThat(options.rangeStartInclusive).isEqualTo(5)
        Assertions.assertThat(options.rangeEndExclusive).isEqualTo(Long.Companion.MAX_VALUE)
    }

    @Test
    fun rejectsInvalidOptions() {
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                SweepOptions.parse(
                    arrayOf<String>("--range=10:5"),
                    tempDir!!.resolve("out"),
                    "divergences.jsonl",
                    123
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--range end")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                SweepOptions.parse(
                    arrayOf<String>("--threads=0"), tempDir!!.resolve("out"), "divergences.jsonl", 123
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--threads")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                SweepOptions.parse(
                    arrayOf<String>("--replay-file=" + tempDir!!.resolve("missing.jsonl")),
                    tempDir!!.resolve("out"),
                    "divergences.jsonl",
                    123
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--replay-file")
    }
}
