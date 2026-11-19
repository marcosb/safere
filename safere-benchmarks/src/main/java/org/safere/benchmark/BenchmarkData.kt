// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads resolved benchmark configuration and exact generated inputs from the materialized corpus.
 */
class BenchmarkData private constructor() {
    private val corpusDirectory: Path
    private val root: JsonObject
    private val executionPlan: JsonObject
    private val materializedInputs: JsonObject
    private val inputBytes: MutableMap<String?, ByteArray?> = ConcurrentHashMap<String?, ByteArray?>()
    private val inputStrings: MutableMap<String?, String> = ConcurrentHashMap<String?, String>()

    init {
        val configuredCorpus = System.getProperty(CORPUS_PROPERTY)
        require(!(configuredCorpus == null || configuredCorpus.isBlank())) {
            ("Missing -D"
                    + CORPUS_PROPERTY
                    + "=<directory>; run benchmarks through the project scripts")
        }
        corpusDirectory = Path.of(configuredCorpus)
        val manifestPath = corpusDirectory.resolve("manifest.json")
        try {
            Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8).use { reader ->
                val manifest = Gson().fromJson<JsonObject>(reader, JsonObject::class.java)
                require(
                    manifest.get("version").getAsInt() == 1
                ) { "Unsupported benchmark input manifest version: " + manifest.get("version") }
                root = manifest.getAsJsonObject("benchmarkData")
                executionPlan = manifest.getAsJsonObject("executionPlan")
                require(
                    !(executionPlan == null
                            || executionPlan.get("version").getAsInt() != ResolvedBenchmarkPlan.VERSION)
                ) { "Unsupported or missing benchmark execution plan" }
                materializedInputs = manifest.getAsJsonObject("inputs")
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to load materialized benchmark data: " + manifestPath, e)
        }
    }

    // Specialized protocols validate their own settings without adding regular workload operations.
    fun investigationConfiguration(): JsonObject {
        val configuration = root.getAsJsonObject("configuration")
        check(!(configuration == null || !configuration.has("investigation"))) { "Selected corpus has no investigation configuration" }
        return configuration.getAsJsonObject("investigation").deepCopy()
    }

    fun executionPlan(): JsonObject {
        return executionPlan.deepCopy()
    }

    /**
     * Gets a string value by dot-separated path.
     * 
     * @param path path within the resolved benchmark data
     * @return exact configured string
     */
    fun getString(path: String): String {
        val parts: Array<String?> = path.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        var element: JsonElement = root
        for (part in parts) {
            element = element.getAsJsonObject().get(part)
            requireNotNull(element) { "No value at path: " + path }
        }
        return element.getAsString()
    }

    /** Get a string array by dot-separated path.  */
    fun getStringList(path: String): MutableList<String?> {
        val parts: Array<String?> = path.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        var el: JsonElement = root
        for (part in parts) {
            el = el.getAsJsonObject().get(part)
            requireNotNull(el) { "No value at path: " + path }
        }
        val arr = el.getAsJsonArray()
        val result: MutableList<String?> = ArrayList<String?>(arr.size())
        for (item in arr) {
            result.add(item.getAsString())
        }
        return result
    }

    /**
     * Returns one materialized benchmark input decoded from its canonical UTF-8 bytes.
     * 
     * @param key logical input key from the materialized manifest
     * @return immutable decoded input
     */
    fun getInputString(key: String?): String {
        return inputStrings.computeIfAbsent(
            key
        ) { ignored: String? -> kotlin.text.String(loadInputBytes(key)!!, StandardCharsets.UTF_8) }
    }

    /**
     * Returns a copy of one canonical materialized UTF-8 benchmark input.
     * 
     * @param key logical input key from the materialized manifest
     * @return copy of the materialized bytes
     */
    fun getInputBytes(key: String?): ByteArray? {
        return loadInputBytes(key)!!.clone()
    }

    private fun loadInputBytes(key: String?): ByteArray? {
        return inputBytes.computeIfAbsent(key) { key: String? -> this.readAndVerifyInput(key) }
    }

    private fun readAndVerifyInput(key: String?): ByteArray {
        val entry = materializedInputs.getAsJsonObject(key)
        requireNotNull(entry) { "Unknown materialized benchmark input: " + key }
        val relativePath = entry.get("file").getAsString()
        val inputPath = corpusDirectory.resolve(relativePath)
        try {
            val bytes = Files.readAllBytes(inputPath)
            val expectedLength = entry.get("utf8Bytes").getAsInt()
            require(bytes.size == expectedLength) {
                ("Materialized benchmark input has wrong length: "
                        + key
                        + " expected "
                        + expectedLength
                        + " but was "
                        + bytes.size)
            }
            val expectedHash = entry.get("sha256").getAsString()
            val actualHash: String? = sha256(bytes)
            require(expectedHash == actualHash) {
                ("Materialized benchmark input has wrong SHA-256: "
                        + key
                        + " expected "
                        + expectedHash
                        + " but was "
                        + actualHash)
            }
            return bytes
        } catch (exception: Exception) {
            throw IllegalArgumentException(
                "Failed to load materialized benchmark input: " + key + " (" + inputPath + ")",
                exception
            )
        }
    }

    companion object {
        private const val CORPUS_PROPERTY = "safere.benchmark.corpus"
        private val INSTANCE = BenchmarkData()

        /** Returns the singleton instance.  */
        fun get(): BenchmarkData {
            return INSTANCE
        }

        private fun sha256(bytes: ByteArray?): String? {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            } catch (exception: NoSuchAlgorithmException) {
                throw AssertionError("SHA-256 must be available", exception)
            }
        }
    }
}
