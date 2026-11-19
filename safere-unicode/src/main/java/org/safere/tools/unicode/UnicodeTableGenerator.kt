// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.tools.unicode

import com.ibm.icu.lang.CharacterProperties
import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UCharacter.GraphemeClusterBreak
import com.ibm.icu.lang.UCharacter.IndicConjunctBreak
import com.ibm.icu.lang.UCharacterCategory
import com.ibm.icu.lang.UProperty
import com.ibm.icu.text.UnicodeSet
import java.io.IOException
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.function.Function
import java.util.function.IntFunction
import java.util.function.IntPredicate
import kotlin.Array
import kotlin.Comparator
import kotlin.IllegalArgumentException
import IntArray
import kotlin.arrayOf
import kotlin.check
import kotlin.code
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashMap
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.getValue
import kotlin.collections.indices
import kotlin.collections.minus
import kotlin.collections.sort
import kotlin.compareTo
import kotlin.getValue
import kotlin.require
import kotlin.sequences.minus
import kotlin.text.StringBuilder
import kotlin.text.indexOf
import kotlin.text.split
import kotlin.text.startsWith
import kotlin.text.substring
import kotlin.text.toLowerCase
import kotlin.text.trimIndent

/** Generates checked-in Unicode tables from public JDK APIs and ICU4J Unicode data.  */
object UnicodeTableGenerator {
    private val MAX_CODE_POINT = Character.MAX_CODE_POINT
    private const val DEFAULT_OUTPUT = "safere/src/main/java/org/safere/UnicodeGeneratedTables.java"
    private val DEFAULT_UNICODE_LICENSE: Path = Path.of("safere/src/main/resources/META-INF/LICENSE-Unicode.txt")
    private val USAGE: String = """
      Usage: UnicodeTableGenerator [options] [output-file]

      Options:
        --unicode-license=FILE             Unicode license text (default: %s)
      
      """
        .trimIndent()
        .formatted(DEFAULT_UNICODE_LICENSE)

    private val CATEGORY_ABBREVS = arrayOf<String?>(
        "Cn", "Lu", "Ll", "Lt", "Lm", "Lo", "Mn", "Me", "Mc", "Nd", "Nl", "No", "Zs", "Zl", "Zp", "Cc",
        "Cf", null, "Co", "Cs", "Pd", "Ps", "Pe", "Pc", "Po", "Sm", "Sc", "Sk", "So", "Pi", "Pf"
    )

    private val MAJOR_CATEGORY_TYPES = arrayOf<IntArray?>(
        intArrayOf(1, 2, 3, 4, 5),
        intArrayOf(6, 7, 8),
        intArrayOf(9, 10, 11),
        intArrayOf(20, 21, 22, 23, 24, 29, 30),
        intArrayOf(25, 26, 27, 28),
        intArrayOf(12, 13, 14),
        intArrayOf(0, 15, 16, 18, 19),
    )

    private val MAJOR_CATEGORY_NAMES = arrayOf<String?>("L", "M", "N", "P", "S", "Z", "C")

    @Throws(IOException::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val options: Options = Options.Companion.parse(*args)
        val tables = buildTables(options)
        val output = options.output!!.toAbsolutePath()
        Files.createDirectories(output.getParent())
        PrintWriter(Files.newBufferedWriter(output, StandardCharsets.UTF_8)).use { out ->
            writeJava(out, tables)
        }
    }

    @Throws(IOException::class)
    private fun buildTables(options: Options): GeneratedTables {
        val categoryTables = buildCategoryTables()
        val categories: MutableMap<String?, Array<IntArray?>?> = LinkedHashMap<String?, Array<IntArray?>?>()
        for (i in CATEGORY_ABBREVS.indices) {
            if (CATEGORY_ABBREVS[i] != null && categoryTables[i].length > 0) {
                categories.put(CATEGORY_ABBREVS[i], categoryTables[i])
            }
        }
        for (i in MAJOR_CATEGORY_NAMES.indices) {
            categories.put(
                MAJOR_CATEGORY_NAMES[i],
                UnicodeTableGenerator.mergeSubcategories(categoryTables, MAJOR_CATEGORY_TYPES[i]!!)
            )
        }

        return GeneratedTables(
            unicodeDataVersion(),
            Files.readAllLines(options.unicodeLicense, StandardCharsets.UTF_8),
            categories,
            buildScriptTables(),
            buildBlockTables(),
            buildBinaryPropertyTables(),
            buildGraphemeTables(),
            buildJavaClassTables()
        )
    }

    private fun unicodeDataVersion(): String {
        val version = UCharacter.getUnicodeVersion()
        return String.format(
            Locale.ROOT, "%d.%d.%d", version.getMajor(), version.getMinor(), version.getMilli()
        )
    }

    private fun buildCategoryTables(): Array<Array<IntArray?>?> {
        val builders: Array<RangeBuilder?> = arrayOfNulls<RangeBuilder>(CATEGORY_ABBREVS.size)
        for (i in builders.indices) {
            builders[i] = RangeBuilder()
        }

        for (cp in 0..MAX_CODE_POINT) {
            val type = Character.getType(cp)
            if (type >= 0 && type < builders.size) {
                builders[type]!!.add(cp)
            }
        }

        val tables = arrayOfNulls<Array<IntArray?>>(builders.size)
        for (i in builders.indices) {
            tables[i] = builders[i]!!.build()
        }
        return tables
    }

    private fun buildScriptTables(): MutableMap<String?, Array<IntArray?>?> {
        val builders: MutableMap<Character.UnicodeScript?, RangeBuilder?> =
            EnumMap<Character.UnicodeScript?, RangeBuilder?>(Character.UnicodeScript::class.java)
        for (cp in 0..MAX_CODE_POINT) {
            val script = Character.UnicodeScript.of(cp)
            builders.computeIfAbsent(script, Function { unused: Character.UnicodeScript? -> RangeBuilder() })!!.add(cp)
        }

        val tables: MutableMap<String?, Array<IntArray?>?> = LinkedHashMap<String?, Array<IntArray?>?>()
        for (script in Character.UnicodeScript.values()) {
            val builder = builders.get(script)
            if (builder != null) {
                tables.put(scriptName(script), builder.build())
            }
        }
        return tables
    }

    private fun buildBlockTables(): MutableMap<String?, Array<IntArray?>?> {
        val builders: MutableMap<Character.UnicodeBlock?, RangeBuilder?> =
            LinkedHashMap<Character.UnicodeBlock?, RangeBuilder?>()
        for (cp in 0..MAX_CODE_POINT) {
            val block = Character.UnicodeBlock.of(cp)
            if (block != null) {
                builders.computeIfAbsent(block, Function { unused: Character.UnicodeBlock? -> RangeBuilder() })!!
                    .add(cp)
            }
        }

        val tables: MutableMap<String?, Array<IntArray?>?> = LinkedHashMap<String?, Array<IntArray?>?>()
        val blocks: MutableList<Character.UnicodeBlock> = ArrayList<Character.UnicodeBlock>(builders.keySet())
        blocks.sort(Comparator { a: Character.UnicodeBlock?, b: Character.UnicodeBlock? ->
            UnicodeTableGenerator.blockName(
                a!!
            ).compareTo(UnicodeTableGenerator.blockName(b!!))
        })
        for (block in blocks) {
            tables.put(blockName(block), builders.get(block)!!.build())
        }
        return tables
    }

    private fun buildBinaryPropertyTables(): MutableMap<String?, Array<IntArray?>?> {
        val predicates: MutableMap<String?, IntPredicate?> = LinkedHashMap<String?, IntPredicate?>()
        predicates.put("Alphabetic", IntPredicate { codePoint: Int -> Character.isAlphabetic(codePoint) })
        predicates.put("Ideographic", IntPredicate { codePoint: Int -> Character.isIdeographic(codePoint) })
        predicates.put("Letter", IntPredicate { codePoint: Int -> Character.isLetter(codePoint) })
        predicates.put("Lowercase", IntPredicate { codePoint: Int -> Character.isLowerCase(codePoint) })
        predicates.put("Uppercase", IntPredicate { codePoint: Int -> Character.isUpperCase(codePoint) })
        predicates.put("Titlecase", IntPredicate { codePoint: Int -> Character.isTitleCase(codePoint) })
        predicates.put("Punctuation", IntPredicate { obj: Int -> UnicodeTableGenerator.isPunctuation() })
        predicates.put("Control", IntPredicate { cp: Int -> Character.getType(cp) == Character.CONTROL.toInt() })
        // Use Unicode data rather than Java's differing whitespace definition.
        predicates.put(
            "White_Space",
            IntPredicate { c: Int -> CharacterProperties.getBinaryPropertySet(UProperty.WHITE_SPACE).contains(c) })
        predicates.put("Digit", IntPredicate { codePoint: Int -> Character.isDigit(codePoint) })
        // JDK regex also accepts other decimal digits; preserve Unicode Hex_Digit.
        predicates.put(
            "Hex_Digit",
            IntPredicate { c: Int -> CharacterProperties.getBinaryPropertySet(UProperty.HEX_DIGIT).contains(c) })
        predicates.put("Join_Control", IntPredicate { cp: Int -> cp == 0x200C || cp == 0x200D })
        predicates.put(
            "Noncharacter_Code_Point",
            IntPredicate { obj: Int -> UnicodeTableGenerator.isNoncharacterCodePoint() })
        predicates.put("Assigned", IntPredicate { codePoint: Int -> Character.isDefined(codePoint) })
        predicates.put("Emoji", IntPredicate { codePoint: Int -> Character.isEmoji(codePoint) })
        predicates.put(
            "Emoji_Presentation",
            IntPredicate { codePoint: Int -> Character.isEmojiPresentation(codePoint) })
        predicates.put("Emoji_Modifier", IntPredicate { codePoint: Int -> Character.isEmojiModifier(codePoint) })
        predicates.put(
            "Emoji_Modifier_Base",
            IntPredicate { codePoint: Int -> Character.isEmojiModifierBase(codePoint) })
        predicates.put("Emoji_Component", IntPredicate { codePoint: Int -> Character.isEmojiComponent(codePoint) })

        val tables: MutableMap<String?, Array<IntArray?>?> = LinkedHashMap<String?, Array<IntArray?>?>()
        for (entry in predicates.entrySet()) {
            tables.put(entry.getKey(), buildRanges(entry.getValue()))
        }
        tables.put(
            "Extended_Pictographic",
            toRanges(CharacterProperties.getBinaryPropertySet(UProperty.EXTENDED_PICTOGRAPHIC))
        )
        return tables
    }

    /**
     * Builds the `\p{java...}` classes that can't be composed from the general categories and
     * binary properties. The rest are composed at runtime in `JavaCharacterClasses`.
     */
    private fun buildJavaClassTables(): MutableMap<String?, Array<IntArray?>?> {
        val tables: MutableMap<String?, Array<IntArray?>?> = LinkedHashMap<String?, Array<IntArray?>?>()
        // Bidi_Mirrored isn't exposed as a binary property, since java.util.regex doesn't support it.
        tables.put("javaMirrored", buildRanges(IntPredicate { codePoint: Int -> Character.isMirrored(codePoint) }))
        // Keep identifier exceptions in the generated data too: their membership can change when
        // Unicode changes categories. Subtract the categories already included by the Java rules.
        val idStart = CharacterProperties.getBinaryPropertySet(UProperty.ID_START)
        val idContinue = CharacterProperties.getBinaryPropertySet(UProperty.ID_CONTINUE)
        tables.put(
            "javaUnicodeIdentifierStartExtra",
            buildRanges(
                IntPredicate { cp: Int ->
                    idStart.contains(cp)
                            && !UCharacter.isLetter(cp) && UCharacter.getType(cp) != UCharacterCategory.LETTER_NUMBER.toInt()
                })
        )
        tables.put(
            "javaUnicodeIdentifierPartExtra",
            buildRanges(
                IntPredicate { cp: Int ->
                    idContinue.contains(cp)
                            && !UCharacter.isLetterOrDigit(cp) && UCharacter.getType(cp) != UCharacterCategory.LETTER_NUMBER.toInt() && UCharacter.getType(
                        cp
                    ) != UCharacterCategory.CONNECTOR_PUNCTUATION.toInt() && UCharacter.getType(cp) != UCharacterCategory.COMBINING_SPACING_MARK.toInt() && UCharacter.getType(
                        cp
                    ) != UCharacterCategory.NON_SPACING_MARK.toInt() && !UCharacter.isIdentifierIgnorable(cp)
                })
        )

        return tables
    }

    fun buildGraphemeTables(): MutableMap<String?, Array<IntArray?>?> {
        val tables: MutableMap<String?, Array<IntArray?>?> = LinkedHashMap<String?, Array<IntArray?>?>()
        val control =
            UnicodeSet()
                .applyIntPropertyValue(UProperty.GRAPHEME_CLUSTER_BREAK, GraphemeClusterBreak.CONTROL)
                .addAll(
                    UnicodeSet()
                        .applyIntPropertyValue(
                            UProperty.GRAPHEME_CLUSTER_BREAK, GraphemeClusterBreak.CR
                        )
                )
                .addAll(
                    UnicodeSet()
                        .applyIntPropertyValue(
                            UProperty.GRAPHEME_CLUSTER_BREAK, GraphemeClusterBreak.LF
                        )
                )
        tables.put("Control", toRanges(control))
        tables.put("Extend", graphemeBreakRanges(GraphemeClusterBreak.EXTEND))
        tables.put("Prepend", graphemeBreakRanges(GraphemeClusterBreak.PREPEND))
        tables.put("SpacingMark", graphemeBreakRanges(GraphemeClusterBreak.SPACING_MARK))
        tables.put("L", graphemeBreakRanges(GraphemeClusterBreak.L))
        tables.put("V", graphemeBreakRanges(GraphemeClusterBreak.V))
        tables.put("T", graphemeBreakRanges(GraphemeClusterBreak.T))
        tables.put("LV", graphemeBreakRanges(GraphemeClusterBreak.LV))
        tables.put("LVT", graphemeBreakRanges(GraphemeClusterBreak.LVT))
        tables.put("InCB_Linker", indicConjunctBreakRanges(IndicConjunctBreak.LINKER))
        tables.put("InCB_Consonant", indicConjunctBreakRanges(IndicConjunctBreak.CONSONANT))
        tables.put("InCB_Extend", indicConjunctBreakRanges(IndicConjunctBreak.EXTEND))
        return tables
    }

    private fun graphemeBreakRanges(value: Int): Array<IntArray?>? {
        return toRanges(
            UnicodeSet().applyIntPropertyValue(UProperty.GRAPHEME_CLUSTER_BREAK, value)
        )
    }

    // ICU's IndicConjunctBreak constants are declared in property-value order, and ICU's
    // PropNumbersTest checks that each ordinal equals the corresponding UProperty value.
    private fun indicConjunctBreakRanges(value: IndicConjunctBreak): Array<IntArray?>? {
        return toRanges(
            UnicodeSet().applyIntPropertyValue(UProperty.INDIC_CONJUNCT_BREAK, value.ordinal())
        )
    }

    private fun toRanges(set: UnicodeSet): Array<IntArray?>? {
        check(!set.isEmpty()) { "Empty UnicodeSet for property" }
        val ranges: MutableList<IntArray?> = ArrayList<IntArray?>()
        for (range in set.ranges()) {
            ranges.add(intArrayOf(range.codepoint, range.codepointEnd))
        }
        return ranges.toTypedArray()
    }

    @Throws(IOException::class)
    private fun writeJava(out: PrintWriter, tables: GeneratedTables) {
        val header =
            """
        // This file is part of a Java port of RE2 (https://github.com/google/re2).
        // Original RE2 code is Copyright (c) 2009 The RE2 Authors.
        // Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
        // Licensed under the BSD 3-Clause License (see LICENSE file).
        
        """.trimIndent()
        val declaration: String =
            """

        // WARNING: This file is automatically generated. Do not edit by hand.
        // To regenerate, run:
        //   ./safere-unicode/generate-unicode-tables.sh

        package org.safere;

        import java.util.Collections;
        import java.util.LinkedHashMap;
        import java.util.Map;

        /** Checked-in Unicode tables generated from public JDK APIs and pinned Unicode data. */
        final class UnicodeGeneratedTables {
          static final String GENERATOR_JAVA_VERSION = "%s";
          static final String UNICODE_DATA_VERSION = "%s";
        
        """
                .trimIndent()
                .formatted(javaVersion(), tables.unicodeDataVersion)
        out.print(header)
        out.println("// Unicode data copyright and permission notice:")
        for (line in tables.unicodeLicense!!) {
            out.println(if (line.isEmpty()) "//" else "// " + line)
        }
        out.println()
        out.println(declaration)
        out.println()

        UnicodeTableGenerator.writeInlineMap(out, "CATEGORIES", tables.categories!!, "category")
        UnicodeTableGenerator.writeInlineMap(out, "SCRIPTS", tables.scripts!!, "script")
        UnicodeTableGenerator.writeInlineMap(out, "BLOCKS", tables.blocks!!, "block")
        UnicodeTableGenerator.writeInlineMap(out, "BINARY_PROPERTIES", tables.binaryProperties!!, "property")
        out.println(
            "  // Grapheme segmentation classes; internal only, not exposed as \\p{...} names."
        )
        UnicodeTableGenerator.writeInlineMap(out, "GRAPHEME_PROPERTIES", tables.graphemeProperties!!, "grapheme")
        out.println(
            "  // java* classes that can't be composed from the tables above; internal only, looked up"
                    + " by JavaCharacterClasses."
        )
        UnicodeTableGenerator.writeInlineMap(out, "JAVA_CHARACTER_CLASSES", tables.javaClasses!!, "javaClass")

        val middle =
            """
          private UnicodeGeneratedTables() {}

          static Map<String, int[][]> unicodeGroups() {
            Map<String, int[][]> groups = new LinkedHashMap<>();
            groups.putAll(CATEGORIES);
            groups.putAll(SCRIPTS);
            return Collections.unmodifiableMap(groups);
          }
        
        """.trimIndent()
        out.println(middle)
        out.println()

        UnicodeTableGenerator.writeTableMethods(out, "category", tables.categories)
        UnicodeTableGenerator.writeTableMethods(out, "script", tables.scripts)
        UnicodeTableGenerator.writeTableMethods(out, "property", tables.binaryProperties)
        UnicodeTableGenerator.writeTableMethods(out, "grapheme", tables.graphemeProperties)
        UnicodeTableGenerator.writeTableMethods(out, "javaClass", tables.javaClasses)

        out.println("}")
    }

    private fun writeInlineMap(
        out: PrintWriter,
        name: String?,
        tables: MutableMap<String?, Array<IntArray?>?>,
        prefix: String
    ) {
        // Emit Kotlin mapOf / `to` pairs so generated tables compile on KMP commonMain.
        out.println("  val " + name + ": Map<String, Array<IntArray>> = mapOf(")
        var count = 0
        for (entry in tables.entrySet()) {
            val key: String = entry.getKey()
            val entryStr: String?
            if (prefix == "block") {
                val ranges: Array<IntArray?> = entry.getValue()
                entryStr =
                    String.format(
                        Locale.ROOT,
                        "    \"%s\" to arrayOf(intArrayOf(0x%x, 0x%x))",
                        key,
                        ranges[0]!![0],
                        ranges[0]!![1]
                    )
            } else {
                entryStr =
                    "    \"%s\" to %s_%s()".formatted(key, prefix, safeIdentifier(key))
            }
            out.print(entryStr)
            if (++count < tables.size) {
                out.println(",")
            } else {
                out.println()
            }
        }
        out.println("  )")
        out.println()
    }

    private fun writeTableMethods(
        out: PrintWriter, prefix: String?, tables: MutableMap<String?, Array<IntArray?>?>
    ) {
        for (entry in tables.entrySet()) {
            val name: String = entry.getKey()
            val ranges: Array<IntArray> = entry.getValue()
            out.println("  private static int[][] %s_%s() {".formatted(prefix, safeIdentifier(name)))
            out.println("    return new int[][] {")
            for (range in ranges) {
                out.println(String.format(Locale.ROOT, "      {0x%x, 0x%x},", range[0], range[1]))
            }
            out.println("    };")
            out.println("  }")
            out.println()
        }
    }

    private fun safeIdentifier(name: String): String {
        return name.replaceAll("[^a-zA-Z0-9_]", "_")
    }

    private fun buildRanges(predicate: IntPredicate): Array<IntArray?> {
        val builder = RangeBuilder()
        for (cp in 0..MAX_CODE_POINT) {
            if (predicate.test(cp)) {
                builder.add(cp)
            }
        }
        return builder.build()
    }

    private fun mergeSubcategories(allTables: Array<Array<IntArray?>?>, types: IntArray): Array<IntArray?> {
        val ranges: MutableList<IntArray> = ArrayList<IntArray>()
        for (type in types) {
            Collections.addAll<IntArray?>(ranges, *allTables[type])
        }
        ranges.sort(Comparator { a: IntArray, b: IntArray ->
            if (a[0] != b[0]) Integer.compare(
                a[0],
                b[0]
            ) else Integer.compare(a[1], b[1])
        })
        val builder = RangeBuilder()
        for (range in ranges) {
            builder.addRange(range[0], range[1])
        }
        return builder.build()
    }

    private fun isPunctuation(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.CONNECTOR_PUNCTUATION.toInt() || type == Character.DASH_PUNCTUATION.toInt() || type == Character.START_PUNCTUATION.toInt() || type == Character.END_PUNCTUATION.toInt() || type == Character.OTHER_PUNCTUATION.toInt() || type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || type == Character.FINAL_QUOTE_PUNCTUATION.toInt()
    }

    private fun isNoncharacterCodePoint(cp: Int): Boolean {
        return (cp >= 0xFDD0 && cp <= 0xFDEF)
                || ((cp and 0xFFFE) == 0xFFFE && cp <= Character.MAX_CODE_POINT)
    }

    private fun scriptName(script: Character.UnicodeScript): String {
        if (script == Character.UnicodeScript.SIGNWRITING) {
            return "SignWriting"
        }
        return toTitleSnake(script.name())
    }

    private fun blockName(block: Character.UnicodeBlock): String {
        return toTitleSnake(block.toString())
    }

    private fun toTitleSnake(upperSnake: String): String {
        val result: StringBuilder = StringBuilder(upperSnake.length)
        for (part in upperSnake.split("_", -1)) {
            if (!result.isEmpty()) {
                result.append('_')
            }
            result.append(Character.toUpperCase(part[0]))
            if (part.length > 1) {
                result.append(part.substring(1).toLowerCase(Locale.ROOT))
            }
        }
        return result.toString()
    }

    private fun javaVersion(): String {
        return Runtime.version().toString() + " (" + System.getProperty("java.vendor") + ")"
    }

    /** Command-line options for table generation.  */
    @JvmRecord
    internal data class Options(val output: Path?, val unicodeLicense: Path?) {
        companion object {
            fun parse(vararg args: String): Options {
                for (arg in args) {
                    if (!arg.startsWith("--")) {
                        require(output == null) { USAGE }
                        output = Path.of(arg)
                        continue
                    }
                    val equals: Int = arg.indexOf('='.code)
                    require(!(equals < 0 || equals == arg.length - 1)) { USAGE }
                    val value = arg.substring(equals + 1)
                    unicodeLicense =
                        when (arg.substring(0, equals)) {
                            "--unicode-license" -> Path.of(value)
                            else -> throw IllegalArgumentException(USAGE)
                        }
                }
                return Options(
                    if (output != null) output else Path.of(DEFAULT_OUTPUT),
                    if (unicodeLicense != null) unicodeLicense else DEFAULT_UNICODE_LICENSE
                )
            }
        }
    }

    @JvmRecord
    private data class GeneratedTables(
        val unicodeDataVersion: String?,
        val unicodeLicense: MutableList<String>?,
        val categories: MutableMap<String?, Array<IntArray?>?>?,
        val scripts: MutableMap<String?, Array<IntArray?>?>?,
        val blocks: MutableMap<String?, Array<IntArray?>?>?,
        val binaryProperties: MutableMap<String?, Array<IntArray?>?>?,
        val graphemeProperties: MutableMap<String?, Array<IntArray?>?>?,
        val javaClasses: MutableMap<String?, Array<IntArray?>?>?
    )

    private class RangeBuilder {
        private val ranges: MutableList<IntArray> = ArrayList<IntArray>()

        fun add(cp: Int) {
            addRange(cp, cp)
        }

        fun addRange(lo: Int, hi: Int) {
            if (!ranges.isEmpty()) {
                val last = ranges.get(ranges.size - 1)
                if (lo <= last[1] + 1) {
                    last[1] = Math.max(last[1], hi)
                    return
                }
            }
            ranges.add(intArrayOf(lo, hi))
        }

        fun build(): Array<IntArray?> {
            return ranges.toArray<IntArray?>(arrayOfNulls<IntArray>(0))
        }
    }
}
