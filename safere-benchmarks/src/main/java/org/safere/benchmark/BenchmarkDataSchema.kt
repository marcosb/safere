// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Validates the complete checked-in `benchmark-data.json` document envelope.  */
internal object BenchmarkDataSchema {
    private val ROOT_FIELDS = mutableSetOf<String?>("schemaVersion", "configuration", "inputs", "workloads")
    private val CONFIGURATION_FIELDS =
        mutableSetOf<String?>("collection", "crosscheckOverhead", "vectorScan", "investigation")
    private val COLLECTION_FIELDS = mutableSetOf<String?>("allocationWorkloadPrefixes")
    private val CROSSCHECK_OVERHEAD_FIELDS = mutableSetOf<String?>("pattern", "replacement")
    private val VECTOR_SCAN_FIELDS = mutableSetOf<String?>("smokeTrials", "standardTrials")

    fun validate(source: JsonObject) {
        requireOnly(source, "benchmark-data.json", ROOT_FIELDS)
        required(source, "schemaVersion").asInt
        requiredArray(source, "inputs")
        if (source.has("workloads")) {
            requiredArray(source, "workloads")
        }
        if (source.has("configuration")) {
            validateConfiguration(requiredObject(source, "configuration"))
        }
    }

    fun requireWorkloads(source: JsonObject) {
        val workloads = requiredArray(source, "workloads")
        require(!workloads.isEmpty) { "benchmark-data.json workloads must not be empty" }
    }

    private fun validateConfiguration(configuration: JsonObject) {
        requireOnly(configuration, "benchmark-data.json configuration", CONFIGURATION_FIELDS)
        if (configuration.has("investigation")) {
            requiredObject(configuration, "investigation")
        }
        if (configuration.has("collection")) {
            val collection = requiredObject(configuration, "collection")
            requireOnly(collection, "benchmark-data.json configuration.collection", COLLECTION_FIELDS)
            val prefixes = requiredArray(collection, "allocationWorkloadPrefixes")
            for (prefix in prefixes) {
                require(
                    !(!prefix.isJsonPrimitive || !prefix.asJsonPrimitive.isString || prefix.asString
                        .isBlank())
                ) { "benchmark-data.json allocation workload prefixes must be nonblank strings" }
            }
        }
        if (configuration.has("crosscheckOverhead")) {
            val crosscheck = requiredObject(configuration, "crosscheckOverhead")
            requireOnly(
                crosscheck,
                "benchmark-data.json configuration.crosscheckOverhead",
                CROSSCHECK_OVERHEAD_FIELDS
            )
            requiredString(crosscheck, "pattern")
            requiredString(crosscheck, "replacement")
        }
        if (configuration.has("vectorScan")) {
            val vectorScan = requiredObject(configuration, "vectorScan")
            requireOnly(vectorScan, "benchmark-data.json configuration.vectorScan", VECTOR_SCAN_FIELDS)
            validateNonblankStrings(vectorScan, "smokeTrials", "Vector scan smoke trials")
            validateNonblankStrings(vectorScan, "standardTrials", "Vector scan standard trials")
        }
    }

    private fun validateNonblankStrings(`object`: JsonObject, field: String?, description: String?) {
        val values = requiredArray(`object`, field)
        require(!values.isEmpty) { description + " must not be empty" }
        for (value in values) {
            require(
                !(!value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString.isBlank())
            ) { description + " must be nonblank strings" }
        }
    }

    private fun requireOnly(`object`: JsonObject, context: String?, allowed: MutableSet<String?>) {
        for (field in `object`.keySet()) {
            require(allowed.contains(field)) { context + " has unknown field: " + field }
        }
    }

    private fun required(`object`: JsonObject, field: String?): JsonElement {
        val value = `object`.get(field)
        require(!(value == null || value.isJsonNull)) { "benchmark-data.json requires " + field }
        return value
    }

    private fun requiredObject(`object`: JsonObject, field: String?): JsonObject {
        val value = required(`object`, field)
        require(value.isJsonObject) { "benchmark-data.json field must be an object: " + field }
        return value.asJsonObject
    }

    private fun requiredArray(`object`: JsonObject, field: String?): JsonArray {
        val value = required(`object`, field)
        require(value.isJsonArray) { "benchmark-data.json field must be an array: " + field }
        return value.asJsonArray
    }

    private fun requiredString(`object`: JsonObject, field: String?): String? {
        val value = required(`object`, field)
        require(
            !(!value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString.isBlank())
        ) { "benchmark-data.json field must be a nonblank string: " + field }
        return value.asString
    }
}
