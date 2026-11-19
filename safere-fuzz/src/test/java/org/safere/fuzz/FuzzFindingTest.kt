// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.safere.Pattern
import org.safere.fuzz.FuzzSupport.MatcherPair
import java.nio.file.Files
import java.nio.file.Path
import java.util.Map

internal class FuzzFindingTest {
    @TempDir
    var directory: Path? = null

    @Test
    fun jsonPreservesUnpairedSurrogatesAndControlCharacters() {
        Assertions.assertThat(FuzzFinding.json(Map.of<String?, String?>("input", "\ud800\n\"\\")))
            .isEqualTo("{\"input\":\"\\ud800\\n\\\"\\\\\"}")
    }

    @Test
    @Throws(Exception::class)
    fun preservesFailureAndPersistsContext() {
        val property = "safere.fuzz.findingsDir"
        val previous = System.getProperty(property)
        System.setProperty(property, directory.toString())
        try {
            val finding =
                FuzzFinding.failure("a", 0, "a", "find", "region=0:1", "reset,find", "true", "false")
            Assertions.assertThat<AssertionError?>(finding).hasMessageContaining("find divergence")
            Files.list(directory).use { files ->
                val paths = files.toList()
                Assertions.assertThat<Path>(paths).hasSize(1)
                Assertions.assertThat(Files.readString(paths.first()))
                    .contains("\"history\":\"reset,find\"", "\"jdk\":\"false\"")
            }
        } finally {
            if (previous == null) {
                System.clearProperty(property)
            } else {
                System.setProperty(property, previous)
            }
        }
    }

    @Test
    fun statefulFindingsIncludeBoundsAndPriorOperations() {
        val pair =
            MatcherPair(
                "(a)?b$",
                0,
                "xab",
                Pattern.compile("()??ab$").matcher("xab"),
                java.util.regex.Pattern.compile("(a)?b$").matcher("xab")
            )
        pair.region(1, 3).useTransparentBounds(true)
        Assertions.assertThatThrownBy(ThrowingCallable { pair.matches() })
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("region=1:3")
            .hasMessageContaining("transparent=true")
            .hasMessageContaining("region(1,3)")
            .hasMessageContaining("useTransparentBounds(true)")
    }
}
