// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.util.*
import java.util.List
import java.util.Map
import java.util.function.Consumer
import java.util.regex.Pattern

/** Explicit engine-profile alternatives for Java-canonical benchmark regex syntax.  */
internal class PatternProfiles private constructor(profiles: MutableMap<String?, MutableMap<String?, Selection?>?>?) {
    private val profiles: MutableMap<String?, MutableMap<String?, Selection?>?>

    init {
        this.profiles = Collections.unmodifiableMap<String?, MutableMap<String?, Selection?>?>(
            LinkedHashMap<String?, MutableMap<String?, Selection?>?>(profiles)
        )
    }

    fun withFallback(profileId: String?, fallbackProfileId: String?): PatternProfiles {
        val fallback = profiles.getOrDefault(fallbackProfileId, Map.of<String?, Selection?>())
        val overrides = profiles.get(profileId)
        val combined: MutableMap<String?, Selection?> = LinkedHashMap<String?, Selection?>(fallback)
        if (overrides != null) {
            combined.putAll(overrides)
        }
        val result: MutableMap<String?, MutableMap<String?, Selection?>?> =
            LinkedHashMap<String?, MutableMap<String?, Selection?>?>(profiles)
        result.put(profileId, Collections.unmodifiableMap<String?, Selection?>(combined))
        return PatternProfiles(result)
    }

    @JvmOverloads
    fun select(profileId: String?, javaPattern: String?, flagSet: String? = "0"): String? {
        val selection = resolve(profileId, javaPattern, flagSet)
        require(selection.unsupportedReason == null) {
            ("Profile "
                    + profileId
                    + " does not support Java syntax "
                    + javaPattern
                    + ": "
                    + selection.unsupportedReason)
        }
        return selection.value
    }

    @JvmOverloads
    fun resolve(profileId: String?, javaPattern: String?, flagSet: String? = "0"): Selection {
        val alternatives = profiles.get(profileId)
        val selection = if (alternatives == null) null else alternatives.get(javaPattern)
        return if (selection == null
            || (!selection.flagSets!!.isEmpty() && !selection.flagSets.contains(flagSet))
        )
            Selection(javaPattern, null, mutableSetOf<String?>())
        else
            selection
    }

    fun validateReferences(authoritativeValues: MutableSet<String?>, kind: String?) {
        for (profile in profiles.entries) {
            for (javaValue in profile.value!!.keys) {
                require(authoritativeValues.contains(javaValue)) { "Unreferenced " + kind + " profile value for " + profile.key + ": " + javaValue }
            }
        }
    }

    private enum class ValueKind(field: String) {
        PATTERN("pattern"),
        REPLACEMENT("replacement");

        private val field: String?

        init {
            this.field = field
        }

        fun field(): String? {
            return field
        }
    }

    @JvmRecord
    private data class NormalizationFrame(val element: JsonElement, val kind: ValueKind?)

    @JvmRecord
    internal data class Selection(
        val value: String?,
        val unsupportedReason: String?,
        val flagSets: MutableSet<String?>?
    )

    @JvmRecord
    private data class Alternate(val value: String?, val reason: String?, val flagSets: MutableSet<String?>?)
    companion object {
        private val PROFILE_ID: Pattern = Pattern.compile("[a-z][a-z0-9-]*")
        private val FLAG_SETS = mutableSetOf<String?>(
            "0",
            "CASE_INSENSITIVE",
            "CASE_INSENSITIVE_UNICODE_CASE",
            "UNICODE_CHARACTER_CLASS",
            "CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS"
        )

        fun normalizeInline(source: JsonObject): JsonObject {
            require(!(source.has("patternProfiles") || source.has("replacementProfiles"))) { "benchmark-data.json must define syntax alternates next to their Java values" }
            val normalized = source.deepCopy()
            val patternProfiles: MutableMap<String?, MutableMap<String?, Alternate?>?> =
                LinkedHashMap<String?, MutableMap<String?, Alternate?>?>()
            val replacementProfiles: MutableMap<String?, MutableMap<String?, Alternate?>?> =
                LinkedHashMap<String?, MutableMap<String?, Alternate?>?>()
            val pending: Deque<NormalizationFrame> = ArrayDeque<NormalizationFrame>()
            val syntaxRoot =
                if (normalized.has("schemaVersion")) normalized.getAsJsonArray("workloads") else normalized
            if (syntaxRoot != null) {
                pending.push(NormalizationFrame(syntaxRoot, null))
            }
            while (!pending.isEmpty()) {
                val frame = pending.pop()
                val element = frame.element
                if (element.isJsonObject()) {
                    val `object` = element.getAsJsonObject()
                    for (entry in List.copyOf<MutableMap.MutableEntry<String?, JsonElement?>?>(`object`.entrySet())) {
                        val child: JsonElement = entry.value!!
                        val childKind: ValueKind = Companion.valueKind(entry.key!!, frame.kind!!)
                        if (isInlinePattern(child)) {
                            `object`.add(
                                entry.key,
                                normalizePattern(
                                    child.getAsJsonObject(), childKind, patternProfiles, replacementProfiles
                                )
                            )
                        } else if (child.isJsonArray() || child.isJsonObject()) {
                            pending.push(NormalizationFrame(child, childKind))
                        }
                    }
                } else {
                    val array = element.getAsJsonArray()
                    for (index in 0..<array.size()) {
                        val child = array.get(index)
                        if (isInlinePattern(child)) {
                            array.set(
                                index,
                                Companion.normalizePattern(
                                    child.getAsJsonObject(), frame.kind!!, patternProfiles, replacementProfiles
                                )
                            )
                        } else if (child.isJsonArray() || child.isJsonObject()) {
                            pending.push(NormalizationFrame(child, frame.kind))
                        }
                    }
                }
            }
            normalized.add("patternProfiles", profileJson(patternProfiles))
            normalized.add("replacementProfiles", profileJson(replacementProfiles))
            return normalized
        }

        private fun isInlinePattern(element: JsonElement): Boolean {
            return element.isJsonObject()
                    && (element.getAsJsonObject().has("java") || element.getAsJsonObject().has("alternates"))
        }

        private fun valueKind(field: String, inherited: ValueKind): ValueKind {
            if (field == "pattern"
                || field == "patterns"
                || field == "regex"
                || field.endsWith("Pattern")
            ) {
                return ValueKind.PATTERN
            }
            return if (field == "replacement") ValueKind.REPLACEMENT else inherited
        }

        private fun normalizePattern(
            definition: JsonObject,
            expectedKind: ValueKind,
            patternProfiles: MutableMap<String?, MutableMap<String?, Alternate?>?>?,
            replacementProfiles: MutableMap<String?, MutableMap<String?, Alternate?>?>?
        ): JsonPrimitive {
            requireNotNull(expectedKind) { "Inline benchmark syntax definition is not inside a pattern or replacement field" }
            for (field in definition.keySet()) {
                require(!(field != "java" && field != "alternates")) { "Inline benchmark pattern has unknown field: " + field }
            }
            val javaPattern: String = requiredInlineString(definition, "java", "Inline benchmark pattern")
            val alternatesElement = definition.get("alternates")
            require(!(alternatesElement == null || !alternatesElement.isJsonObject())) { "Inline benchmark pattern requires object: alternates" }
            val alternates = alternatesElement.getAsJsonObject()
            require(!alternates.isEmpty()) { "Inline benchmark pattern alternates must not be empty" }
            var definitionKind: String? = null
            for (entry in alternates.entrySet()) {
                val profileId = entry.key
                require(
                    PROFILE_ID.matcher(profileId).matches()
                ) { "Invalid benchmark pattern profile ID: " + profileId }
                require(entry.value.isJsonObject()) { "Inline benchmark pattern alternate " + profileId + " must be an object" }
                val alternateObject = entry.value.getAsJsonObject()
                for (field in alternateObject.keySet()) {
                    require(!((field != "pattern") && (field != "replacement") && (field != "unsupported") && (field != "reason") && (field != "flagSets"))) { "Inline benchmark pattern alternate " + profileId + " has unknown field: " + field }
                }
                val hasPattern = alternateObject.has("pattern")
                val hasReplacement = alternateObject.has("replacement")
                val unsupported = alternateObject.has("unsupported")
                val selectionCount =
                    (if (hasPattern) 1 else 0) + (if (hasReplacement) 1 else 0) + (if (unsupported) 1 else 0)
                require(selectionCount == 1) {
                    ("Inline benchmark pattern alternate "
                            + profileId
                            + " requires exactly one of: pattern, replacement, unsupported")
                }
                require(
                    !(unsupported
                            && (!alternateObject.get("unsupported")
                        .isJsonPrimitive() || !alternateObject.getAsJsonPrimitive("unsupported")
                        .isBoolean() || !alternateObject.get("unsupported").getAsBoolean()))
                ) { "Inline benchmark pattern alternate " + profileId + " field unsupported must be true" }
                val valueField = if (hasPattern) "pattern" else if (hasReplacement) "replacement" else null
                val alternateKind =
                    if (hasPattern) ValueKind.PATTERN else if (hasReplacement) ValueKind.REPLACEMENT else expectedKind
                require(alternateKind == expectedKind) {
                    ("Inline benchmark "
                            + expectedKind.field()
                            + " definition contains "
                            + alternateKind.field()
                            + " alternate")
                }
                require(!(valueField != null && definitionKind != null && (definitionKind != valueField))) { "Inline benchmark definition mixes pattern and replacement alternates" }
                if (valueField != null) {
                    definitionKind = valueField
                }
                val alternate =
                    Alternate(
                        if (unsupported)
                            null
                        else
                            requiredInlineString(
                                alternateObject,
                                valueField,
                                "Inline benchmark pattern alternate " + profileId
                            ),
                        requiredInlineString(
                            alternateObject, "reason", "Inline benchmark pattern alternate " + profileId
                        ),
                        optionalFlagSets(alternateObject, "Inline benchmark pattern alternate " + profileId)
                    )
                val previous =
                    (if (expectedKind == ValueKind.PATTERN) patternProfiles else replacementProfiles)!!
                        .computeIfAbsent(profileId) { `_`: kotlin.String? -> LinkedHashMap<kotlin.String?, Alternate?>() }!!
                        .putIfAbsent(javaPattern, alternate)
                require(!(previous != null && previous != alternate)) {
                    ("Conflicting "
                            + profileId
                            + " alternates for Java "
                            + expectedKind.field()
                            + ": "
                            + javaPattern)
                }
            }
            return JsonPrimitive(javaPattern)
        }

        private fun requiredInlineString(`object`: JsonObject, field: String?, description: String?): String {
            val value = `object`.get(field)
            require(
                !(value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            ) { description + " requires string: " + field }
            val string = value.getAsString()
            require(!string.isBlank()) { description + " field must not be blank: " + field }
            return string
        }

        private fun profileJson(profiles: MutableMap<String?, MutableMap<String?, Alternate?>?>): JsonObject {
            val result = JsonObject()
            for (profile in profiles.entries) {
                val entries = JsonArray()
                for (alternate in profile.value!!.entries) {
                    val entry = JsonObject()
                    entry.addProperty("java", alternate.key)
                    if (alternate.value!!.value == null) {
                        entry.addProperty("unsupported", true)
                    } else {
                        entry.addProperty("alternate", alternate.value!!.value)
                    }
                    entry.addProperty("reason", alternate.value!!.reason)
                    if (!alternate.value!!.flagSets!!.isEmpty()) {
                        val flagSets = JsonArray()
                        alternate.value!!.flagSets!!.forEach(Consumer { string: String? -> flagSets.add(string) })
                        entry.add("flagSets", flagSets)
                    }
                    entries.add(entry)
                }
                result.add(profile.key, entries)
            }
            return result
        }

        fun parse(element: JsonElement?): PatternProfiles {
            if (element == null) {
                return PatternProfiles(Map.of<String?, MutableMap<String?, Selection?>?>())
            }
            require(element.isJsonObject()) { "patternProfiles must be an object" }
            val profiles: MutableMap<String?, MutableMap<String?, Selection?>?> =
                LinkedHashMap<String?, MutableMap<String?, Selection?>?>()
            for (profileEntry in element.getAsJsonObject().entrySet()) {
                val profileId = profileEntry.key
                require(
                    PROFILE_ID.matcher(profileId).matches()
                ) { "Invalid benchmark pattern profile ID: " + profileId }
                require(profileEntry.value.isJsonArray()) { "Pattern profile " + profileId + " must be an array" }
                val alternatives: MutableMap<String?, Selection?> =
                    parseProfile(profileId, profileEntry.value.getAsJsonArray())
                profiles.put(profileId, Collections.unmodifiableMap<String?, Selection?>(alternatives))
            }
            return PatternProfiles(profiles)
        }

        private fun parseProfile(profileId: String?, entries: JsonArray): MutableMap<String?, Selection?> {
            val alternatives: MutableMap<String?, Selection?> = LinkedHashMap<String?, Selection?>()
            for (element in entries) {
                require(element.isJsonObject()) { "Pattern profile " + profileId + " entries must be objects" }
                val entry = element.getAsJsonObject()
                for (field in entry.keySet()) {
                    require(!((field != "java") && (field != "alternate") && (field != "unsupported") && (field != "reason") && (field != "flagSets"))) { "Pattern profile " + profileId + " entry has unknown field: " + field }
                }
                val javaPattern: String = requiredString(entry, profileId, "java")
                val unsupported =
                    entry.has("unsupported")
                            && entry.get("unsupported").isJsonPrimitive()
                            && entry.getAsJsonPrimitive("unsupported").isBoolean()
                            && entry.get("unsupported").getAsBoolean()
                require(entry.has("alternate") != unsupported) {
                    ("Pattern profile "
                            + profileId
                            + " entry requires exactly one of alternate or unsupported=true")
                }
                val reason: String = requiredString(entry, profileId, "reason")
                val selection =
                    if (unsupported)
                        Selection(null, reason, optionalFlagSets(entry, "Pattern profile " + profileId))
                    else
                        Selection(
                            requiredString(entry, profileId, "alternate"),
                            null,
                            optionalFlagSets(entry, "Pattern profile " + profileId)
                        )
                require(
                    alternatives.put(
                        javaPattern,
                        selection
                    ) == null
                ) { "Pattern profile " + profileId + " repeats Java pattern: " + javaPattern }
            }
            return alternatives
        }

        private fun requiredString(entry: JsonObject, profileId: String?, field: String?): String {
            val value = entry.get(field)
            require(
                !(value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            ) { "Pattern profile " + profileId + " entry requires string field: " + field }
            val string = value.getAsString()
            require(!string.isBlank()) { "Pattern profile " + profileId + " entry field must not be blank: " + field }
            return string
        }

        private fun optionalFlagSets(entry: JsonObject, description: String?): MutableSet<String?> {
            val value = entry.get("flagSets")
            if (value == null) {
                return mutableSetOf<String?>()
            }
            require(
                !(!value.isJsonArray() || value.getAsJsonArray().isEmpty())
            ) { description + " field flagSets must be a nonempty array" }
            val result: MutableSet<String?> = LinkedHashSet<String?>()
            for (element in value.getAsJsonArray()) {
                require(
                    !(!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
                ) { description + " flagSets must contain strings" }
                val flagSet = element.getAsString()
                require(FLAG_SETS.contains(flagSet)) { description + " has unknown flag set: " + flagSet }
                require(result.add(flagSet)) { description + " repeats flag set: " + flagSet }
            }
            return Collections.unmodifiableSet<String?>(result)
        }
    }
}
