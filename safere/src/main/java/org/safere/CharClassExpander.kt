// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*

/**
 * Expands parsed character-class atoms according to SafeRE's regex flags and Unicode property
 * semantics.
 */
internal object CharClassExpander {
    fun lookupPerlGroup(name: String?, unicodeCharacterClass: Boolean): Group? {
        val table =
            if (unicodeCharacterClass)
                UnicodeTables.unicodePerlGroups().get(name)
            else
                UnicodeTables.PERL_GROUPS.get(name)
        return if (table == null) null else Group(table!!, CaseFoldPolicy.NONE)
    }

    fun lookupUnicodeGroup(name: String, unicodeCharacterClass: Boolean): Group? {
        var table: Array<IntArray>? = JavaCharacterClasses.lookup(name)
        val javaTable = table
        if (javaTable != null) {
            return Group(javaTable, javaClassCaseFoldPolicy(name))
        }
        table =
            if (unicodeCharacterClass)
                UnicodeTables.unicodePosixPropertyGroups().get(name)
            else
                UnicodeTables.POSIX_PROPERTY_GROUPS.get(name)
        if (table != null) {
            return Group(table!!, posixPropertyCaseFoldPolicy(name, unicodeCharacterClass))
        }

        val eq: Int = name.indexOf('=')
        if (eq >= 0) {
            return lookupKeywordProperty(name.substring(0, eq), name.substring(eq + 1))
        }

        if (name.startsWith("Is") && name.length > 2) {
            val stripped = name.substring(2)
            table = UnicodeProperties.lookupScriptOrCategory(stripped)
            if (table != null) {
                return Group(table!!, scriptOrCategoryCaseFoldPolicy(stripped))
            }
            table = UnicodeProperties.lookupBinaryProperty(stripped)
            return if (table == null) null else Group(table!!, binaryPropertyCaseFoldPolicy(stripped))
        }

        if (name.startsWith("In") && name.length > 2) {
            table = UnicodeProperties.lookupBlock(name.substring(2))
            return if (table == null) null else Group(table!!, CaseFoldPolicy.NONE)
        }

        table = UnicodeProperties.lookupCategory(name)
        return if (table == null) null else Group(table!!, categoryCaseFoldPolicy(name))
    }

    fun addPositiveGroup(ccb: CharClassBuilder, group: Group, parseFlags: Int) {
        if ((parseFlags and ParseFlags.FOLD_CASE) == 0) {
            addPropertyTableWithoutCaseFolding(ccb, group.table()!!, parseFlags)
            return
        }
        when (group.caseFoldPolicy()) {
            CaseFoldPolicy.ASCII_POSIX -> addAsciiFoldedPropertyTable(
                ccb,
                group.table()!!,
                parseFlags
            )

            CaseFoldPolicy.UNICODE_CASED_LETTER_CATEGORY -> addCasedLetterCategoryClosure(ccb)
            CaseFoldPolicy.JAVA_CASED_PROPERTY -> ccb.addTable(JavaCasedPropertyClosureHolder.TABLE)
            CaseFoldPolicy.NONE -> addPropertyTableWithoutCaseFolding(
                ccb,
                group.table()!!,
                parseFlags
            )

            else -> {}
        }
    }

    fun addNegatedGroup(ccb: CharClassBuilder, group: Group, parseFlags: Int) {
        if ((parseFlags and ParseFlags.FOLD_CASE) != 0) {
            val positive = CharClassBuilder()
            addPositiveGroup(positive, group, parseFlags)
            val cutnl =
                (parseFlags and ParseFlags.CLASS_NL) == 0 || (parseFlags and ParseFlags.NEVER_NL) != 0
            if (cutnl) {
                positive.addRune('\n'.code)
            }
            positive.negate()
            ccb.addCharClass(positive)
            return
        }
        var next = 0
        for (row in group.table()!!) {
            if (next < row[0]) {
                addRange(ccb, next, row[0] - 1, parseFlags)
            }
            next = row[1] + 1
        }
        if (next <= Utils.MAX_RUNE) {
            addRange(ccb, next, Utils.MAX_RUNE, parseFlags)
        }
    }

    fun addRange(ccb: CharClassBuilder, lo: Int, hi: Int, parseFlags: Int) {
        val cutnl =
            (parseFlags and ParseFlags.CLASS_NL) == 0 || (parseFlags and ParseFlags.NEVER_NL) != 0
        if (cutnl && lo <= '\n'.code && hi >= '\n'.code) {
            if (lo < '\n'.code) {
                addRange(ccb, lo, '\n'.code - 1, parseFlags)
            }
            if (hi > '\n'.code) {
                addRange(ccb, '\n'.code + 1, hi, parseFlags)
            }
            return
        }

        if ((parseFlags and ParseFlags.FOLD_CASE) != 0) {
            if ((parseFlags and ParseFlags.UNICODE_CASE) == 0) {
                UnicodeCaseFolding.addAsciiFoldedRange(ccb, lo, hi)
                return
            }
            UnicodeCaseFolding.addUnicodeFoldedRange(ccb, lo, hi)
        } else {
            ccb.addRange(lo, hi)
        }
    }

    private fun lookupKeywordProperty(key: String, value: String): Group? {
        val normalizedKey = normalizePropertyName(key)
        return when (normalizedKey) {
            "SCRIPT", "SC" -> {
                val table = UnicodeProperties.lookupScript(value)
                if (table == null) null else Group(table, CaseFoldPolicy.NONE)
            }

            "BLOCK", "BLK" -> {
                val table = UnicodeProperties.lookupBlock(value)
                if (table == null) null else Group(table, CaseFoldPolicy.NONE)
            }

            "GENERALCATEGORY", "GC" -> {
                val table = UnicodeProperties.lookupCategory(value)
                if (table == null) null else Group(table, categoryCaseFoldPolicy(value))
            }

            else -> null
        }
    }

    private fun addPropertyTableWithoutCaseFolding(
        ccb: CharClassBuilder, table: Array<IntArray>, parseFlags: Int
    ) {
        val noFoldFlags = parseFlags and ParseFlags.FOLD_CASE.inv()
        if ((noFoldFlags and ParseFlags.CLASS_NL) != 0 && (noFoldFlags and ParseFlags.NEVER_NL) == 0) {
            ccb.addTable(table)
            return
        }
        for (row in table) {
            addRange(ccb, row[0], row[1], noFoldFlags)
        }
    }

    private fun addAsciiFoldedPropertyTable(
        ccb: CharClassBuilder, table: Array<IntArray>, parseFlags: Int
    ) {
        val asciiFoldFlags = parseFlags and ParseFlags.UNICODE_CASE.inv()
        for (row in table) {
            addRange(ccb, row[0], row[1], asciiFoldFlags)
        }
    }

    private fun addCasedLetterCategoryClosure(ccb: CharClassBuilder) {
        ccb.addTable(UnicodeTables.UNICODE_GROUPS.get("Lu")!!)
        ccb.addTable(UnicodeTables.UNICODE_GROUPS.get("Ll")!!)
        ccb.addTable(UnicodeTables.UNICODE_GROUPS.get("Lt")!!)
    }

    private fun posixPropertyCaseFoldPolicy(
        name: String?, unicodeCharacterClass: Boolean
    ): CaseFoldPolicy {
        if (unicodeCharacterClass) {
            return when (name) {
                "Lower", "Upper" -> CaseFoldPolicy.JAVA_CASED_PROPERTY
                else -> CaseFoldPolicy.NONE
            }
        }
        return CaseFoldPolicy.ASCII_POSIX
    }

    private fun javaClassCaseFoldPolicy(name: String): CaseFoldPolicy {
        return when (name) {
            "javaUpperCase", "javaLowerCase", "javaTitleCase" -> CaseFoldPolicy.JAVA_CASED_PROPERTY
            else -> CaseFoldPolicy.NONE
        }
    }

    private fun scriptOrCategoryCaseFoldPolicy(name: String): CaseFoldPolicy {
        return if (isCasedLetterCategoryName(name))
            CaseFoldPolicy.UNICODE_CASED_LETTER_CATEGORY
        else
            CaseFoldPolicy.NONE
    }

    private fun categoryCaseFoldPolicy(name: String): CaseFoldPolicy {
        return when (name) {
            "Lu", "Ll", "Lt" -> CaseFoldPolicy.UNICODE_CASED_LETTER_CATEGORY
            else -> CaseFoldPolicy.NONE
        }
    }

    private fun binaryPropertyCaseFoldPolicy(name: String): CaseFoldPolicy {
        val normalized = normalizePropertyName(name)
        return when (normalized) {
            "UPPERCASE", "LOWERCASE", "TITLECASE" -> CaseFoldPolicy.JAVA_CASED_PROPERTY
            else -> CaseFoldPolicy.NONE
        }
    }

    private fun isCasedLetterCategoryName(name: String): Boolean {
        return when (normalizePropertyName(name)) {
            "LU", "LL", "LT" -> true
            else -> false
        }
    }

    private fun normalizePropertyName(name: String): String {
        return name.uppercase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "")
    }

    internal enum class CaseFoldPolicy {
        NONE,
        ASCII_POSIX,
        UNICODE_CASED_LETTER_CATEGORY,
        JAVA_CASED_PROPERTY
    }

    internal class Group(private val table: Array<IntArray>?, private val caseFoldPolicy: CaseFoldPolicy?) {
        fun table(): Array<IntArray>? {
            return table
        }

        fun caseFoldPolicy(): CaseFoldPolicy? {
            return caseFoldPolicy
        }
    }

    /**
     * The case-insensitive closure of the cased binary properties: every code point that is
     * uppercase, lowercase, or titlecase, matching `Character.isUpperCase`, `isLowerCase`, and `isTitleCase` in the pinned Unicode data.
     */
    private object JavaCasedPropertyClosureHolder {
        val TABLE: Array<IntArray> = UnicodeTables.mergeRangeTables(
            UnicodeProperties.lookupBinaryProperty("Uppercase"),
            UnicodeProperties.lookupBinaryProperty("Lowercase"),
            UnicodeProperties.lookupBinaryProperty("Titlecase")
        )
    }
}
