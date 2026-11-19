// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

import com.google.gson.*

/** JSON helpers for exhaustive sweep JSONL reports.  */
internal object SweepJson {
    private val GSON = Gson()

    @JvmStatic
    fun `object`(): JsonObject {
        return JsonObject()
    }

    @JvmStatic
    fun toJson(`object`: JsonObject?): String {
        return escapeUnpairedSurrogates(GSON.toJson(`object`))
    }

    @JvmStatic
    fun field(line: String, field: String?): String? {
        val `object` = parseObjectOrNull(line)
        if (`object` == null) {
            return null
        }
        val value = `object`.get(field)
        if (value == null || value.isJsonNull()) {
            return null
        }
        return value.getAsString()
    }

    @JvmStatic
    fun parseObject(line: String): JsonObject {
        val `object` = parseObjectOrNull(line)
        requireNotNull(`object`) { "expected JSON object: " + line }
        return `object`
    }

    @JvmStatic
    fun `object`(`object`: JsonObject, field: String?): JsonObject {
        val value = `object`.get(field)
        require(!(value == null || value.isJsonNull())) { "missing JSON object field: " + field }
        require(value.isJsonObject()) { "expected JSON object field: " + field }
        return value.getAsJsonObject()
    }

    fun array(`object`: JsonObject, field: String?): JsonArray? {
        val value = `object`.get(field)
        require(!(value == null || value.isJsonNull())) { "missing JSON array field: " + field }
        require(value.isJsonArray()) { "expected JSON array field: " + field }
        return value.getAsJsonArray()
    }

    @JvmStatic
    fun string(`object`: JsonObject, field: String?): String? {
        val value = `object`.get(field)
        require(!(value == null || value.isJsonNull())) { "missing JSON string field: " + field }
        return value.getAsString()
    }

    @JvmStatic
    fun bool(`object`: JsonObject, field: String?): Boolean {
        val value = `object`.get(field)
        require(!(value == null || value.isJsonNull())) { "missing JSON boolean field: " + field }
        return value.getAsBoolean()
    }

    @JvmStatic
    fun integer(`object`: JsonObject, field: String?): Int {
        val value = `object`.get(field)
        require(!(value == null || value.isJsonNull())) { "missing JSON integer field: " + field }
        return value.getAsInt()
    }

    fun longInteger(`object`: JsonObject, field: String?): Long {
        val value = `object`.get(field)
        require(!(value == null || value.isJsonNull())) { "missing JSON integer field: " + field }
        return value.getAsLong()
    }

    fun parseObjectOrNull(line: String): JsonObject {
        val element: JsonElement
        try {
            element = JsonParser.parseString(line)
        } catch (e: JsonParseException) {
            return null
        }
        if (!element.isJsonObject()) {
            return null
        }
        return element.getAsJsonObject()
    }

    private fun escapeUnpairedSurrogates(json: String): String {
        val result = StringBuilder(json.length)
        var i = 0
        while (i < json.length) {
            val c = json.get(i)
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < json.length && Character.isLowSurrogate(json.get(i + 1))) {
                    result.append(c)
                    result.append(json.get(i + 1))
                    i++
                } else {
                    appendUnicodeEscape(result, c)
                }
            } else if (Character.isLowSurrogate(c)) {
                appendUnicodeEscape(result, c)
            } else {
                result.append(c)
            }
            i++
        }
        return result.toString()
    }

    private fun appendUnicodeEscape(result: StringBuilder, c: Char) {
        result.append("\\u")
        val hex = Integer.toHexString(c.code)
        for (i in hex.size..3) {
            result.append('0')
        }
        result.append(hex)
    }
}
