// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.List

internal class BenchmarkProviderCheckTest {
    @Test
    @Throws(Exception::class)
    fun startupChecksLoadBothProvidersAndRejectMissingVectorModule() {
        for (provider in mutableListOf<String?>("default", "vector")) {
            val command: MutableList<String?> = Companion.command(provider!!)
            if (provider == "vector") {
                command.add(1, "--add-modules=jdk.incubator.vector")
            }
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
            Assertions.assertThat(process.waitFor()).`as`(output).isZero()
            Assertions.assertThat(output).contains("startup check passed: " + provider)
        }
        val missing: Process = ProcessBuilder(command("vector")).redirectErrorStream(true).start()
        val output = String(missing.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
        Assertions.assertThat(missing.waitFor()).`as`(output).isNotZero()
        Assertions.assertThat(output).contains("Could not enable", "--add-modules=jdk.incubator.vector")
    }

    companion object {
        private fun command(provider: String): MutableList<String?> {
            val command: MutableList<String?> =
                ArrayList<String?>(
                    List.of<String?>(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        BenchmarkProviderCheck::class.java.getName()
                    )
                )
            if (provider == "vector") {
                command.add(1, "-Dorg.safere.experimental.vectorScanProvider=vector")
            }
            return command
        }
    }
}
