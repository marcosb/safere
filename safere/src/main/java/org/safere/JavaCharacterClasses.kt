// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*
import java.util.Map
import java.util.function.Supplier
import kotlin.concurrent.Volatile

/**
 * Character class tables for the `\p{java...}` property classes supported by `java.util.regex.Pattern`.
 * 
 * 
 * Each class matches the code points for which the corresponding `java.lang.Character`
 * predicate returns `true` (e.g., `\p{javaLowerCase}` matches code points where [ ][Character.isLowerCase] returns `true`), as of the Unicode version of SafeRE's bundled
 * tables ([UnicodeGeneratedTables.UNICODE_DATA_VERSION]). The tables are composed from the
 * bundled general categories and binary properties, so they don't depend on the running JDK: on a
 * JDK with an older Unicode version, they can disagree with `Character` about code points
 * assigned or changed since that version.
 */
internal object JavaCharacterClasses {
    /** `Character.isISOControl`: U+0000..U+001F and U+007F..U+009F.  */
    private val ISO_CONTROL = arrayOf<IntArray>(intArrayOf(0x00, 0x1F), intArrayOf(0x7F, 0x9F))

    /**
     * The non-whitespace ISO controls that `Character.isIdentifierIgnorable` accepts, in
     * addition to the format characters (Cf).
     */
    private val IGNORABLE_CONTROLS =
        arrayOf<IntArray>(intArrayOf(0x00, 0x08), intArrayOf(0x0E, 0x1B), intArrayOf(0x7F, 0x9F))

    /** The controls that `Character.isWhitespace` accepts, in addition to Zl, Zp, and Zs.  */
    private val WHITESPACE_CONTROLS = arrayOf<IntArray>(intArrayOf(0x09, 0x0D), intArrayOf(0x1C, 0x1F))

    /** The no-break space separators that `Character.isWhitespace` excludes.  */
    private val NO_BREAK_SPACES = intArrayOf(0x00A0, 0x2007, 0x202F)

    private fun generated(name: String): Array<IntArray> {
        return Objects.requireNonNull(UnicodeGeneratedTables.JAVA_CHARACTER_CLASSES.get(name), name)!!
    }

    private fun property(name: String): Array<IntArray> {
        return Objects.requireNonNull(UnicodeProperties.lookupBinaryProperty(name), name)!!
    }

    private fun category(name: String): Array<IntArray> {
        return Objects.requireNonNull(UnicodeProperties.lookupCategory(name), name)!!
    }

    /** `Character.isWhitespace`: Zl, Zp, and Zs other than the no-break spaces.  */
    private fun whitespace(): Array<IntArray> {
        return UnicodeTables.mergeRangeTables(
            category("Zl"),
            category("Zp"),
            withoutCodePoints(category("Zs")!!, NO_BREAK_SPACES),
            WHITESPACE_CONTROLS
        )
    }

    /** `Character.isIdentifierIgnorable`: Cf plus some ISO controls.  */
    private fun identifierIgnorable(): Array<IntArray> {
        return UnicodeTables.mergeRangeTables(category("Cf"), IGNORABLE_CONTROLS)
    }

    /** `Character.isJavaIdentifierStart`: L, Nl, Sc, and Pc.  */
    private fun javaIdentifierStart(): Array<IntArray> {
        return UnicodeTables.mergeRangeTables(
            property("Letter"), category("Nl"), category("Sc"), category("Pc")
        )
    }

    /** Returns `table` without the given sorted code points.  */
    private fun withoutCodePoints(table: Array<IntArray>, codePoints: IntArray): Array<IntArray> {
        var result = table
        for (cp in codePoints) {
            result = withoutCodePoint(result, cp)
        }
        return result
    }

    private fun withoutCodePoint(table: Array<IntArray>, cp: Int): Array<IntArray> {
        val result = arrayOfNulls<IntArray>(table.size + 1) as Array<IntArray>
        var count = 0
        for (range in table) {
            if (cp < range[0] || cp > range[1]) {
                result[count++] = range
                continue
            }
            if (range[0] < cp) {
                result[count++] = intArrayOf(range[0], cp - 1)
            }
            if (cp < range[1]) {
                result[count++] = intArrayOf(cp + 1, range[1])
            }
        }
        return Arrays.copyOf<IntArray?>(result, count)
    }

    /**
     * Returns the range table for the given java character class name, or `null` if the name is
     * not a java character class.
     */
    fun lookup(name: String): Array<IntArray>? {
        if (!name.startsWith("java")) {
            return null
        }
        val table = Holder.JAVA_GROUPS.get(name)
        return table?.ranges()
    }

    private class LazyTable(private val supplier: Supplier<Array<IntArray>?>) {
        @Volatile
        var ranges: Array<IntArray>? = null
        fun ranges(): Array<IntArray>? {
            var result = ranges
            if (result == null) {
                synchronized(this) {
                    result = ranges
                    if (result == null) {
                        result = supplier.get()
                        ranges = result
                    }
                }
            }
            return result
        }
    }

    // Lazy holder pattern: the name index is computed on first access, and each range table is
    // computed only when that specific java character class is requested.
    private object Holder {
        val JAVA_GROUPS: MutableMap<String?, LazyTable?> = buildIndex()

        fun buildIndex(): MutableMap<String?, LazyTable?> {
            return Map.ofEntries<String?, LazyTable?>(
                entry("javaLowerCase", Supplier { property("Lowercase") }),
                entry("javaUpperCase", Supplier { property("Uppercase") }),
                entry("javaTitleCase", Supplier { property("Titlecase") }),
                entry("javaAlphabetic", Supplier { property("Alphabetic") }),
                entry("javaIdeographic", Supplier { property("Ideographic") }),
                entry("javaDefined", Supplier { property("Assigned") }),
                entry("javaLetter", Supplier { property("Letter") }),
                entry("javaDigit", Supplier { property("Digit") }),
                entry(
                    "javaLetterOrDigit",
                    Supplier { UnicodeTables.mergeRangeTables(property("Letter"), property("Digit")) }),
                entry("javaSpaceChar", Supplier { category("Z") }),
                entry("javaWhitespace", Supplier { whitespace() }),
                entry("javaISOControl", Supplier { ISO_CONTROL }),
                entry("javaIdentifierIgnorable", Supplier { identifierIgnorable() }),
                entry("javaJavaIdentifierStart", Supplier { javaIdentifierStart() }),
                entry(
                    "javaJavaIdentifierPart",
                    Supplier {
                        UnicodeTables.mergeRangeTables(
                            javaIdentifierStart(),
                            category("Nd"),
                            category("Mc"),
                            category("Mn"),
                            identifierIgnorable()
                        )
                    }),
                entry(
                    "javaUnicodeIdentifierStart",
                    Supplier {
                        UnicodeTables.mergeRangeTables(
                            property("Letter"),
                            category("Nl"),
                            generated("javaUnicodeIdentifierStartExtra")
                        )
                    }),
                entry(
                    "javaUnicodeIdentifierPart",
                    Supplier {
                        UnicodeTables.mergeRangeTables(
                            property("Letter"),
                            category("Nl"),
                            category("Pc"),
                            category("Nd"),
                            category("Mc"),
                            category("Mn"),
                            identifierIgnorable(),
                            generated("javaUnicodeIdentifierPartExtra")
                        )
                    }),
                entry(
                    "javaMirrored",
                    Supplier {
                        Objects.requireNonNull(
                            UnicodeGeneratedTables.JAVA_CHARACTER_CLASSES.get("javaMirrored")
                        )
                    })
            )
        }

        fun entry(name: String, supplier: Supplier<Array<IntArray>?>): MutableMap.MutableEntry<String?, LazyTable?> {
            return Map.entry<String?, LazyTable?>(name, LazyTable(supplier))
        }
    }
}
