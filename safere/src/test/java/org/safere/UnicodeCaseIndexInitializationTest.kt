// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Path

@DisabledForCrosscheck("checks SafeRE's internal Unicode index initialization")
internal class UnicodeCaseIndexInitializationTest {
    @ParameterizedTest
    @ValueSource(
        strings = ["[h-j]+", "[0-9]+", "[Kk]", "[A-Za-z]+", "[AaBbCc]+", "\\p{L}+", "\\p{IsAlphabetic}+", "\\p{script=Latin}+", "\\p{L}[\\p{L}\\p{Nd}_]*"
        ]
    )
    @Throws(IOException::class, InterruptedException::class, URISyntaxException::class)
    fun ordinaryCharacterClassesDoNotBuildUnicodeCaseIndex(regex: String) {
        Assertions.assertThat(runFreshProcess(regex))
            .`as`("index initialization for %s", regex)
            .doesNotContain(INDEX_NAME)
    }

    @Test
    @Throws(IOException::class, InterruptedException::class, URISyntaxException::class)
    fun unicodeCaseClassBuildsIndexWithinSmallHeap() {
        Assertions.assertThat(runFreshProcess("(?iu)[K-K]")).contains(INDEX_NAME)
    }

    @ParameterizedTest
    @ValueSource(strings = ["C", "C.UTF-8"])
    @Throws(IOException::class, InterruptedException::class, URISyntaxException::class)
    fun fourMemberFoldClassStillBuildsIndexDuringCompilation(locale: String) {
        Assertions.assertThat(runFreshProcess("[Θθϑϴ]", locale)).contains(INDEX_NAME)
    }

    companion object {
        private const val INDEX_NAME = "org/safere/UnicodeCaseFolding\$UnicodeCaseClosureIndex"

        // Generated crosscheck tests copy this disabled test without its SafeRE-only probe class.
        private const val PROBE_CLASS_NAME = "org.safere.UnicodeCaseIndexInitializationProbe"

        @Throws(IOException::class, InterruptedException::class, URISyntaxException::class)
        private fun runFreshProcess(regex: String, locale: String = ""): String {
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val pathSeparator: String = java.lang.System.getProperty("path.separator")
            val classPath =
                (java.nio.file.Path.of(Pattern::class.java.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toString() + pathSeparator
                        + java.nio.file.Path.of(
                    UnicodeCaseIndexInitializationTest::class.java
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI()
                ))
            // Process arguments use the platform's native encoding, which may be ASCII in isolation.
            val encodedRegex: String? =
                java.util.Base64.getEncoder().encodeToString(regex.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            val processBuilder: ProcessBuilder =
                java.lang.ProcessBuilder(
                    java,
                    "-Xmx12m",
                    "-Xlog:class+init=info",
                    "-cp",
                    classPath,
                    PROBE_CLASS_NAME,
                    encodedRegex
                )
                    .redirectErrorStream(true)
            if (!locale.isEmpty()) {
                processBuilder.environment().put("LC_ALL", locale)
            }
            val process = processBuilder.start()
            val output = String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
            Assertions.assertThat(process.waitFor()).`as`(output).isZero()
            return output
        }
    }
}
