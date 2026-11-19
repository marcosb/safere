// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.iterable.ThrowingExtractor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.lang.module.ModuleDescriptor
import java.lang.module.ModuleFinder
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Predicate
import java.util.function.Supplier

@DisabledForCrosscheck("requires SafeRE's named module and the JDK Vector module")
internal class ModuleDescriptorTest {
    @TempDir
    var temporaryDirectory: Path? = null

    @Test
    @Throws(URISyntaxException::class)
    fun doesNotExposeIncubatorModulesToConsumers() {
        val descriptor =
            ModuleFinder.of(safeReModulePath())
                .find("org.safere")
                .orElseThrow<AssertionError?>(Supplier { AssertionError("SafeRE module descriptor not found") })
                .descriptor()

        Assertions.assertThat<ModuleDescriptor.Requires?>(descriptor.requires())
            .extracting<String?, RuntimeException?>(ThrowingExtractor { ModuleDescriptor.Requires.name() })
            .noneMatch(Predicate { name: String? -> name!!.startsWith("jdk.incubator.") })
    }

    @Test
    @Throws(IOException::class, InterruptedException::class, URISyntaxException::class)
    fun strictModularConsumerCanEnableVectorProvider() {
        val sourceDirectory = temporaryDirectory!!.resolve("src")
        val packageDirectory = sourceDirectory.resolve("consumer")
        val classesDirectory = temporaryDirectory!!.resolve("classes")
        Files.createDirectories(packageDirectory)
        Files.createDirectories(classesDirectory)
        Files.writeString(
            sourceDirectory.resolve("module-info.java"),
            "module safere.consumer { requires org.safere; }\n",
            StandardCharsets.UTF_8
        )
        Files.writeString(
            packageDirectory.resolve("Main.java"),
            """
        package consumer;

        import java.util.Arrays;
        import org.safere.Pattern;
        import org.safere.Utf8Input;

        public final class Main {
          public static void main(String[] args) {
            byte[] input = new byte[2048];
            Arrays.fill(input, (byte) 'a');
            input[input.size - 1] = 'z';
            if (!Pattern.compile("[z]").find(Utf8Input.trusted(input))) {
              throw new AssertionError("Vector-enabled search did not find the final byte");
            }
          }
        }
        
        """.trimIndent(),
            StandardCharsets.UTF_8
        )

        val safeReModulePath: Path = safeReModulePath()
        val compilation: ProcessResult =
            run(
                javaTool("javac"),
                "-Werror",
                "--module-path",
                safeReModulePath.toString(),
                "-d",
                classesDirectory.toString(),
                sourceDirectory.resolve("module-info.java").toString(),
                packageDirectory.resolve("Main.java").toString()
            )
        Assertions.assertThat(compilation.exitCode).`as`(compilation.output).isZero()

        val execution: ProcessResult =
            run(
                javaTool("java"),
                "--add-modules",
                VECTOR_MODULE_NAME,
                "-Dorg.safere.experimental.vectorScanProvider=vector",
                "--module-path",
                safeReModulePath.toString() + System.getProperty("path.separator") + classesDirectory,
                "--module",
                "safere.consumer/consumer.Main"
            )
        Assertions.assertThat(execution.exitCode).`as`(execution.output).isZero()
    }

    @JvmRecord
    private data class ProcessResult(val exitCode: Int, val output: String?)
    companion object {
        private const val VECTOR_MODULE_NAME = "jdk.incubator.vector"

        private fun javaTool(name: String?): String {
            val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
            return Path.of(System.getProperty("java.home"), "bin", name + suffix).toString()
        }

        @Throws(URISyntaxException::class)
        private fun safeReModulePath(): Path {
            return Path.of(Pattern::class.java.getProtectionDomain().codeSource.location.toURI())
        }

        @Throws(IOException::class, InterruptedException::class)
        private fun run(vararg command: String?): ProcessResult {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = String(process.inputStream.readAllBytes(), StandardCharsets.UTF_8)
            return ProcessResult(process.waitFor(), output)
        }
    }
}
