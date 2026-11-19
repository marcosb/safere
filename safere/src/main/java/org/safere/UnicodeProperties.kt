// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*

/**
 * Unicode property lookups for JDK-compatible `\p{...}` syntax extensions.
 * 
 * 
 * SafeRE-owned Unicode property tables are generated from the maintainer JDK and checked into
 * the repository. They do not depend on the JDK running SafeRE.
 */
internal object UnicodeProperties {
    /**
     * Looks up a binary Unicode property by name (e.g., "Alphabetic", "Lowercase"). The lookup is
     * loose per UTS#18: case, underscores, hyphens, and spaces are ignored.
     * 
     * @return the range table, or `null` if not a recognized binary property
     */
    fun lookupBinaryProperty(name: String): Array<IntArray>? {
        val table = UnicodeGeneratedTables.BINARY_PROPERTIES.get(name)
        if (table != null) {
            return table
        }
        val canonicalKey: String? = BinaryNormalizedHolder.NORMALIZED_KEYS.get(normalize(name))
        return if (canonicalKey == null) null else UnicodeGeneratedTables.BINARY_PROPERTIES.get(canonicalKey)
    }

    /**
     * Looks up a Unicode block by name. The lookup is case-insensitive and ignores spaces, hyphens,
     * and underscores.
     * 
     * @return the range table, or `null` if not a recognized block
     */
    fun lookupBlock(name: String): Array<IntArray>? {
        val table = UnicodeGeneratedTables.BLOCKS.get(name)
        if (table != null) {
            return table
        }
        val canonicalKey: String? = BlockNormalizedHolder.NORMALIZED_KEYS.get(normalize(name))
        if (canonicalKey != null) {
            return UnicodeGeneratedTables.BLOCKS.get(canonicalKey)
        }
        try {
            val runtimeJdkName = toTitleSnake(Character.UnicodeBlock.forName(name).toString())
            return UnicodeGeneratedTables.BLOCKS.get(runtimeJdkName)
        } catch (e: IllegalArgumentException) {
            return null
        }
    }

    /**
     * Looks up a Unicode script by name. The lookup is case-insensitive and ignores spaces, hyphens,
     * and underscores.
     * 
     * @return the range table, or `null` if not a recognized script
     */
    fun lookupScript(name: String): Array<IntArray>? {
        val table = UnicodeGeneratedTables.SCRIPTS.get(name)
        if (table != null) {
            return table
        }
        val canonicalKey: String? = ScriptNormalizedHolder.NORMALIZED_KEYS.get(normalize(name))
        if (canonicalKey != null) {
            return UnicodeGeneratedTables.SCRIPTS.get(canonicalKey)
        }
        try {
            val runtimeJdkName = scriptName(Character.UnicodeScript.forName(name))
            return UnicodeGeneratedTables.SCRIPTS.get(runtimeJdkName)
        } catch (e: IllegalArgumentException) {
            return null
        }
    }

    /**
     * Looks up a Unicode general category by its one- or two-letter abbreviation.
     * 
     * @return the range table, or `null` if not a recognized category
     */
    fun lookupCategory(name: String): Array<IntArray>? {
        return when (name) {
            "L", "M", "N", "P", "S", "Z", "C", "Lu", "Ll", "Lt", "Lm", "Lo", "Mn", "Me", "Mc", "Nd", "Nl", "No", "Zs", "Zl", "Zp", "Cc", "Cn", "Cf", "Co", "Cs", "Pd", "Ps", "Pe", "Pc", "Po", "Pi", "Pf", "Sm", "Sc", "Sk", "So" -> UnicodeGeneratedTables.CATEGORIES.get(
                name
            )

            else -> null
        }
    }

    /**
     * Case-insensitive lookup of a script or category name in [UnicodeTables.UNICODE_GROUPS].
     * Spaces and hyphens are normalized to underscores.
     * 
     * @return the range table, or `null` if not found
     */
    fun lookupScriptOrCategory(name: String): Array<IntArray>? {
        val table = UnicodeTables.UNICODE_GROUPS.get(name)
        if (table != null) {
            return table
        }
        val canonicalKey: String? = ScriptOrCategoryNormalizedHolder.NORMALIZED_KEYS.get(normalize(name))
        return if (canonicalKey == null) null else UnicodeTables.UNICODE_GROUPS.get(canonicalKey)
    }

    /**
     * Normalizes a property name for loose matching per UTS#18: uppercases and removes underscores,
     * hyphens, and spaces.
     */
    private fun normalize(name: String): String {
        return name.uppercase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "")
    }

    private fun buildNormalizedKeys(tables: Map<String, out Array<IntArray>?>): MutableMap<String?, String?> {
        val map: MutableMap<String?, String?> = HashMap<String?, String?>()
        for (key in tables.keys) {
            map.put(normalize(key), key)
        }
        return map
    }

    private fun scriptName(script: Character.UnicodeScript): String {
        if (script == Character.UnicodeScript.SIGNWRITING) {
            return "SignWriting"
        }
        return toTitleSnake(script.name)
    }

    private fun toTitleSnake(upperSnake: String): String {
        val result: StringBuilder = StringBuilder(upperSnake.length)
        for (part in upperSnake.split("_")) {
            if (!result.isEmpty()) {
                result.append('_')
            }
            result.append(Character.toUpperCase(part[0]))
            if (part.length > 1) {
                result.append(part.substring(1).lowercase(Locale.ROOT))
            }
        }
        return result.toString()
    }

    private object ScriptOrCategoryNormalizedHolder {
        val NORMALIZED_KEYS: MutableMap<String?, String?> = buildNormalizedKeys(UnicodeTables.UNICODE_GROUPS)
    }

    private object ScriptNormalizedHolder {
        val NORMALIZED_KEYS: MutableMap<String?, String?> = buildNormalizedKeys(UnicodeGeneratedTables.SCRIPTS)
    }

    private object BlockNormalizedHolder {
        val NORMALIZED_KEYS: MutableMap<String?, String?> = buildNormalizedKeys(UnicodeGeneratedTables.BLOCKS)
    }

    private object BinaryNormalizedHolder {
        val NORMALIZED_KEYS: MutableMap<String?, String?> =
            buildNormalizedKeys(UnicodeGeneratedTables.BINARY_PROPERTIES)
    }
}
