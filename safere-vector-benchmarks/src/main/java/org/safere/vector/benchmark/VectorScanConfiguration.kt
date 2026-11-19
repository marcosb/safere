// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.vector.benchmark

import com.google.gson.JsonObject

/** Loads Vector benchmark trials from the shared materialized benchmark manifest.  */
internal object VectorScanConfiguration {
    private val CORPUS_PROPERTY = "safere.benchmark.corpus"

    fun trials(profile: String): List<String?> {
        val field =
            when (profile) {
                "smoke" -> "smokeTrials"
                "standard" -> "standardTrials"
                else -> throw IllegalArgumentException("Unknown Vector scan profile: " + profile)
            }
        val vectorScan: JsonObject =
            org.safere.vector.benchmark.VectorScanConfiguration.manifest()
                .getAsJsonObject("benchmarkData")
                .getAsJsonObject("configuration")
                .getAsJsonObject("vectorScan")
        val result: List<String?> = ArrayList()
        vectorScan.getAsJsonArray(field).forEach({ value -> result.add(value.getAsString()) })
        return List.copyOf(result)
    }

    fun input(profile: String?, density: String?, length: Int): ByteArray {
        val manifest: JsonObject = org.safere.vector.benchmark.VectorScanConfiguration.manifest()
        val inputId = "vectorScan." + profile + density + "." + length
        val file: String? =
            manifest.getAsJsonObject("inputs").getAsJsonObject(inputId).get("file").getAsString()
        try {
            return Files.readAllBytes(
                org.safere.vector.benchmark.VectorScanConfiguration.corpusDirectory().resolve(file)
            )
        } catch (exception: IOException) {
            throw RuntimeException("Failed to read Vector scan input " + inputId, exception)
        }
    }

    private fun manifest(): JsonObject {
        try {
            return JsonParser.parseString(
                Files.readString(
                    org.safere.vector.benchmark.VectorScanConfiguration.corpusDirectory().resolve("manifest.json")
                )
            )
                .getAsJsonObject()
        } catch (exception: IOException) {
            throw RuntimeException("Failed to read benchmark manifest", exception)
        }
    }

    private fun corpusDirectory(): Path {
        val value: String = System.getProperty(org.safere.vector.benchmark.VectorScanConfiguration.CORPUS_PROPERTY)
        kotlin.require(!(value == null || value.isBlank())) { "Missing -D" + org.safere.vector.benchmark.VectorScanConfiguration.CORPUS_PROPERTY + "=<directory>; use run-vector-benchmarks.sh" }
        return Path.of(value)
    }
}
