// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.gson.JsonParser
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

internal class BenchmarkDataSchemaTest {
    @Test
    @Throws(Exception::class)
    fun checkedInDocumentUsesOnlyTheAuthoritativeSchema() {
        val benchmarkData =
            if (Files.exists(Path.of("benchmark-data.json")))
                Path.of("benchmark-data.json")
            else
                Path.of("safere-benchmarks", "benchmark-data.json")
        val root = JsonParser.parseString(Files.readString(benchmarkData)).getAsJsonObject()

        BenchmarkDataSchema.validate(root)
        BenchmarkDataSchema.requireWorkloads(root)

        Assertions.assertThat<String?>(root.keySet())
            .containsExactlyInAnyOrder("schemaVersion", "configuration", "inputs", "workloads")
        Assertions.assertThat<String?>(root.getAsJsonObject("configuration").keySet())
            .containsExactlyInAnyOrder("collection", "crosscheckOverhead", "vectorScan")
    }

    @Test
    fun investigationOwnsItsProtocolConfiguration() {
        val root =
            JsonParser.parseString(
                """
                {"schemaVersion":2,"inputs":[],"workloads":[],
                 "configuration":{"investigation":{"rounds":32,"usages":{"text":{"readEvery":1}}}}}
                
                """.trimIndent()
            )
                .getAsJsonObject()
        BenchmarkDataSchema.validate(root)
        root.getAsJsonObject("configuration").addProperty("investigation", "invalid")
        Assertions.assertThatThrownBy(ThrowingCallable { BenchmarkDataSchema.validate(root) })
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun rejectsUnknownTopLevelFields() {
        val root =
            JsonParser.parseString(
                """
                {
                  "schemaVersion": 2,
                  "inputs": [],
                  "workloads": [],
                  "regex": {}
                }
                
                """.trimIndent()
            )
                .getAsJsonObject()

        Assertions.assertThatThrownBy(ThrowingCallable { BenchmarkDataSchema.validate(root) })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("benchmark-data.json has unknown field: regex")
    }

    @Test
    fun rejectsUnknownConfigurationFields() {
        val root =
            JsonParser.parseString(
                """
                {
                  "schemaVersion": 2,
                  "configuration": {"legacyWorkloads": {}},
                  "inputs": [],
                  "workloads": []
                }
                
                """.trimIndent()
            )
                .getAsJsonObject()

        Assertions.assertThatThrownBy(ThrowingCallable { BenchmarkDataSchema.validate(root) })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("benchmark-data.json configuration has unknown field: legacyWorkloads")
    }
}
