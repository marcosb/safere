// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UProperty
import com.ibm.icu.lang.UScript
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.*
import java.util.Map
import java.util.function.IntPredicate
import java.util.stream.Stream
import kotlin.collections.ArrayList
import kotlin.collections.HashSet
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.containsKey
import kotlin.collections.get

/**
 * Canary tests for SafeRE Unicode tables against pinned ICU4J data and the running JDK's [ ] implementation. Version-sensitive JDK comparisons require a matching Unicode
 * repertoire; pinned ICU4J checks preserve script and case-fold coverage on every runtime JDK.
 * 
 * 
 * See [#107](https://github.com/safere/safere/issues/107).
 */
@DisplayName("Unicode table consistency canary tests")
@DisabledForCrosscheck("Unicode table canary uses package-private SafeRE internals")
internal class UnicodeJdkConsistencyTest {
    // --- General category and pinned Unicode script coverage ---
    @Test
    @DisplayName("Every JDK general category is present in UNICODE_GROUPS")
    fun allGeneralCategoriesPresent() {
        // All 2-letter subcategory abbreviations that Character.getType() can return.
        val subcategories = arrayOf<String?>(
            "Lu", "Ll", "Lt", "Lm", "Lo",  // Letter
            "Mn", "Me", "Mc",  // Mark
            "Nd", "Nl", "No",  // Number
            "Zs", "Zl", "Zp",  // Separator
            "Cc", "Cf", "Cn", "Co", "Cs",  // Other
            "Pd", "Ps", "Pe", "Pc", "Po", "Pi", "Pf",  // Punctuation
            "Sm", "Sc", "Sk", "So",  // Symbol
        )
        val majorCategories = arrayOf<String?>("L", "M", "N", "P", "S", "Z", "C")

        val groups = UnicodeTables.UNICODE_GROUPS
        for (cat in subcategories) {
            Assertions.assertThat(groups.get(cat)).`as`("Subcategory %s", cat).isNotNull()
        }
        for (cat in majorCategories) {
            Assertions.assertThat(groups.get(cat)).`as`("Major category %s", cat).isNotNull()
        }
    }

    @Test
    @DisplayName("Every script in the pinned Unicode version is present in UNICODE_GROUPS")
    fun allPinnedUnicodeScriptsPresent() {
        assertPinnedUnicodeVersion()
        val scripts: MutableSet<Int?> = HashSet<Int?>()
        for (cp in 0..Character.MAX_CODE_POINT) {
            scripts.add(UScript.getScript(cp))
        }
        scripts.remove(UScript.UNKNOWN)
        for (script in scripts) {
            val name = UScript.getName(script!!)
            Assertions.assertThat<String, Array<IntArray>>(UnicodeTables.UNICODE_GROUPS)
                .`as`("Pinned Unicode script %s", name)
                .containsKey(name)
        }
    }

    @Test
    @DisplayName("Runtime-generated categories match Character.getType() for all code points")
    fun categoriesMatchCharacterGetType() {
        val groups = UnicodeTables.UNICODE_GROUPS

        // Spot-check a representative sample of code points across categories.
        data class Sample(val cp: Int, val expectedCategory: String?)

        val samples =
            java.util.List.of<Sample?>(
                Sample('A'.code, "Lu"),
                Sample('a'.code, "Ll"),
                Sample('\u01C5'.code, "Lt"),  // Dz with caron
                Sample('\u02B0'.code, "Lm"),  // Modifier letter small h
                Sample('\u00AA'.code, "Lo"),  // Feminine ordinal indicator
                Sample('\u0300'.code, "Mn"),  // Combining grave accent
                Sample('\u0488'.code, "Me"),  // Combining Cyrillic-Old Slavic
                Sample('\u0903'.code, "Mc"),  // Devanagari sign visarga
                Sample('0'.code, "Nd"),
                Sample('\u2160'.code, "Nl"),  // Roman numeral one
                Sample('\u00B2'.code, "No"),  // Superscript two
                Sample(' '.code, "Zs"),
                Sample('\u2028'.code, "Zl"),  // Line separator
                Sample('\u2029'.code, "Zp"),  // Paragraph separator
                Sample('\u0000'.code, "Cc"),
                Sample('\u00AD'.code, "Cf"),  // Soft hyphen
                Sample('-'.code, "Pd"),
                Sample('('.code, "Ps"),
                Sample(')'.code, "Pe"),
                Sample('_'.code, "Pc"),
                Sample('!'.code, "Po"),
                Sample('+'.code, "Sm"),
                Sample('$'.code, "Sc"),
                Sample('^'.code, "Sk"),
                Sample('\u00A9'.code, "So"),  // Copyright sign
                Sample('\u00AB'.code, "Pi"),  // Left-pointing double angle
                Sample('\u00BB'.code, "Pf")
            ) // Right-pointing double angle

        for (s in samples) {
            val ranges = groups.get(s.expectedCategory)
            Assertions.assertThat(ranges).`as`("Category %s should exist", s.expectedCategory).isNotNull()
            Assertions.assertThat(containsCodePoint(ranges!!, s.cp))
                .`as`(
                    "U+%04X should be in category %s (getType=%d)",
                    s.cp, s.expectedCategory, Character.getType(s.cp)
                )
                .isTrue()
        }
    }

    // --- Case folding canary ---
    @Test
    @DisplayName("CASE_FOLD covers all symmetric case pairs in the pinned Unicode version")
    fun caseFoldCoversAllPinnedUnicodePairs() {
        assertPinnedUnicodeVersion()
        // Scan all code points and find "clean" symmetric pairs where toLower and toUpper
        // are inverses: toLower(cp) != cp AND toUpper(toLower(cp)) == cp. This means
        // cp is uppercase and its lowercase form round-trips cleanly back to cp.
        //
        // This excludes asymmetric mappings like Turkish İ (U+0130) where
        // toLower(İ) = i but toUpper(i) = I ≠ İ. RE2 intentionally excludes these
        // from its fold orbits because they don't form a clean cycle.
        val missing: MutableList<String> = ArrayList<String>()

        for (cp in 0..Character.MAX_CODE_POINT) {
            val lower = UCharacter.toLowerCase(cp)
            if (lower != cp && UCharacter.toUpperCase(lower) == cp) {
                // cp is uppercase, lower is its lowercase, and the mapping is symmetric.
                if (!inFoldOrbit(cp, lower)) {
                    missing.add(
                        String.format(
                            "U+%04X <-> U+%04X (delta %d) not in CASE_FOLD orbit", cp, lower, lower - cp
                        )
                    )
                }
            }
        }

        Assertions.assertThat<String?>(missing).`as`("CASE_FOLD should cover pinned Unicode symmetric case pairs")
            .isEmpty()
    }

    // --- Unicode White_Space canary ---
    @Test
    @DisplayName("unicodeSpace() matches pinned Unicode White_Space")
    fun unicodeSpaceMatchesPinnedUnicode() {
        assertPinnedUnicodeVersion()
        assertTableMatches(
            "White_Space",
            UnicodeTables.unicodeSpace()!!,
            IntPredicate { cp: Int -> UCharacter.hasBinaryProperty(cp, UProperty.WHITE_SPACE) })
    }

    // --- Generated binary property canaries ---
    @ParameterizedTest(name = "Binary property {0} matches its JDK or Unicode oracle")
    @MethodSource("binaryPropertyNames")
    @EnabledForJreRange(min = JRE.JAVA_26)
    fun binaryPropertiesMatchJdkExceptUnicodeHexDigit(name: String) {
        val ranges = Objects.requireNonNull<Array<IntArray>>(UnicodeGeneratedTables.BINARY_PROPERTIES.get(name))
        if (name == "Extended_Pictographic" || name == "White_Space") {
            assertPinnedUnicodeVersion()
            val property = UCharacter.getPropertyEnum(name)
            assertTableMatches(name, ranges, IntPredicate { cp: Int -> UCharacter.hasBinaryProperty(cp, property) })
            return
        }
        if (name != "Hex_Digit") {
            // JDK-derived property tables are compatibility-checked only when the runtime JDK
            // and generator JDK use the same Unicode repertoire.
            Assumptions.assumeTrue(
                runtimeJdkUnicodeMatchesGeneratedTables(),
                "Runtime JDK Unicode repertoire differs from the generator JDK"
            )
        }
        val jdk =
            java.util.regex.Pattern.compile("\\p{Is" + name + "}").matcher("")
        val differences: MutableList<String> = ArrayList<String>()
        val jdkHexDifferences: MutableList<String> = ArrayList<String>()
        var rangeIndex = 0
        for (cp in 0..Character.MAX_CODE_POINT) {
            while (rangeIndex < ranges.size && ranges[rangeIndex]!![1] < cp) {
                rangeIndex++
            }
            val inTable = rangeIndex < ranges.size && ranges[rangeIndex]!![0] <= cp
            val jdkMatches = jdk.reset(Character.toString(cp)).matches()
            var expected = jdkMatches
            if (name == "Hex_Digit") {
                // Approved spec-based divergence: Unicode Hex_Digit excludes other decimal digits.
                expected =
                    (cp >= '0'.code && cp <= '9'.code)
                            || (cp >= 'A'.code && cp <= 'F'.code)
                            || (cp >= 'a'.code && cp <= 'f'.code)
                            || (cp >= 0xFF10 && cp <= 0xFF19)
                            || (cp >= 0xFF21 && cp <= 0xFF26)
                            || (cp >= 0xFF41 && cp <= 0xFF46)
                if (jdkMatches != (expected || Character.isDigit(cp)) && jdkHexDifferences.size < 10) {
                    jdkHexDifferences.add(String.format("U+%04X", cp))
                }
            }
            if (inTable != expected && differences.size < 10) {
                differences.add(String.format("U+%04X", cp))
            }
        }
        Assertions.assertThat<String?>(differences).`as`("%s table membership differences", name).isEmpty()
        Assertions.assertThat<String?>(jdkHexDifferences).`as`("JDK Hex_Digit implementation changed").isEmpty()
    }

    // --- Table-derived classes that used to scan Character at runtime ---
    @Test
    @DisplayName("(?U) Blank, Graph, and Print match their JDK Character definitions")
    @EnabledForJreRange(min = JRE.JAVA_26)
    fun unicodePosixDerivedClassesMatchJdk() {
        Assumptions.assumeTrue(
            runtimeJdkUnicodeMatchesGeneratedTables(),
            "Runtime JDK Unicode repertoire differs from the generator JDK"
        )
        val groups = UnicodeTables.unicodePosixPropertyGroups()
        Companion.assertTableMatches(
            "Blank",
            groups.get("Blank")!!,
            IntPredicate { cp: Int -> cp == '\t'.code || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt() })
        Companion.assertTableMatches("Graph", groups.get("Graph")!!, IntPredicate { cp: Int -> isJdkGraph(cp) })
        Companion.assertTableMatches(
            "Print",
            groups.get("Print")!!,
            IntPredicate { cp: Int -> isJdkGraph(cp) || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt() })
    }

    @Test
    @DisplayName("Case-insensitive cased properties match the JDK's cased predicates")
    @EnabledForJreRange(min = JRE.JAVA_26)
    fun caseInsensitiveCasedPropertiesMatchJdk() {
        Assumptions.assumeTrue(
            runtimeJdkUnicodeMatchesGeneratedTables(),
            "Runtime JDK Unicode repertoire differs from the generator JDK"
        )
        val m = Pattern.compile("(?i)\\p{IsLowercase}").matcher("")
        val differences: MutableList<String> = ArrayList<String>()
        for (cp in 0..Character.MAX_CODE_POINT) {
            val expected =
                Character.isUpperCase(cp) || Character.isLowerCase(cp) || Character.isTitleCase(cp)
            if (m.reset(Character.toString(cp)).matches() != expected && differences.size < 10) {
                differences.add(String.format("U+%04X", cp))
            }
        }
        Assertions.assertThat<String?>(differences).`as`("(?i)\\p{IsLowercase} membership differences").isEmpty()
    }

    @ParameterizedTest(name = "{0} matches its Character predicate")
    @MethodSource("javaClassNames")
    @EnabledForJreRange(min = JRE.JAVA_26)
    fun javaClassesMatchJdk(name: String) {
        Assumptions.assumeTrue(
            runtimeJdkUnicodeMatchesGeneratedTables(),
            "Runtime JDK Unicode repertoire differs from the generator JDK"
        )
        assertTableMatches(
            name,
            Objects.requireNonNull<Array<IntArray>?>(JavaCharacterClasses.lookup(name)),
            Objects.requireNonNull<IntPredicate?>(JAVA_CLASS_PREDICATES.get(name))
        )
    }

    companion object {
        private val JAVA_CLASS_PREDICATES: MutableMap<String?, IntPredicate?> = Map.ofEntries<String?, IntPredicate?>(
            Map.entry<String?, IntPredicate?>(
                "javaLowerCase",
                IntPredicate { codePoint: Int -> Character.isLowerCase(codePoint) }),
            Map.entry<String?, IntPredicate?>(
                "javaUpperCase",
                IntPredicate { codePoint: Int -> Character.isUpperCase(codePoint) }),
            Map.entry<String?, IntPredicate?>(
                "javaWhitespace",
                IntPredicate { codePoint: Int -> Character.isWhitespace(codePoint) }),
            Map.entry<String?, IntPredicate?>(
                "javaMirrored",
                IntPredicate { codePoint: Int -> Character.isMirrored(codePoint) }),
            Map.entry<String?, IntPredicate?>(
                "javaAlphabetic",
                IntPredicate { codePoint: Int -> Character.isAlphabetic(codePoint) }),
            Map.entry<String?, IntPredicate?>(
                "javaIdeographic",
                IntPredicate { codePoint: Int -> Character.isIdeographic(codePoint) }),
            Map.entry<String?, IntPredicate?>(
                "javaTitleCase",
                IntPredicate { codePoint: Int -> Character.isTitleCase(codePoint) }),
            Map.entry<String?, IntPredicate?>(
                "javaDigit",
                IntPredicate { codePoint: Int -> Character.isDigit(codePoint) }),
            Map.entry<String?, IntPredicate?>(
                "javaDefined",
                IntPredicate { codePoint: Int -> Character.isDefined(codePoint) }),
            Map.entry<String?, IntPredicate?>(
                "javaLetter",
                IntPredicate { codePoint: Int -> Character.isLetter(codePoint) }),
            Map.entry<String?, IntPredicate?>(
                "javaLetterOrDigit",
                IntPredicate { codePoint: Int -> Character.isLetterOrDigit(codePoint) }),
            Map.entry<String?, IntPredicate?>(
                "javaSpaceChar",
                IntPredicate { codePoint: Int -> Character.isSpaceChar(codePoint) }),
            Map.entry<String?, IntPredicate?>(
                "javaISOControl",
                IntPredicate { codePoint: Int -> Character.isISOControl(codePoint) }),
            Map.entry<String?, IntPredicate?>(
                "javaIdentifierIgnorable",
                IntPredicate { codePoint: Int -> Character.isIdentifierIgnorable(codePoint) }),
            Map.entry<String?, IntPredicate?>(
                "javaUnicodeIdentifierStart",
                IntPredicate { codePoint: Int -> Character.isUnicodeIdentifierStart(codePoint) }),
            Map.entry<String?, IntPredicate?>(
                "javaUnicodeIdentifierPart",
                IntPredicate { codePoint: Int -> Character.isUnicodeIdentifierPart(codePoint) }),
            Map.entry<String?, IntPredicate?>(
                "javaJavaIdentifierStart",
                IntPredicate { codePoint: Int -> Character.isJavaIdentifierStart(codePoint) }),
            Map.entry<String?, IntPredicate?>(
                "javaJavaIdentifierPart",
                IntPredicate { codePoint: Int -> Character.isJavaIdentifierPart(codePoint) })
        )

        @JvmStatic
        private fun javaClassNames(): Stream<String?>? {
            return JAVA_CLASS_PREDICATES.keys.stream().sorted()
        }

        private fun isJdkGraph(cp: Int): Boolean {
            val type = Character.getType(cp)
            return Character.isDefined(cp)
                    && type != Character.CONTROL.toInt() && type != Character.SURROGATE.toInt() && !Character.isSpaceChar(
                cp
            )
        }

        private fun assertTableMatches(name: String?, ranges: Array<IntArray>, expected: IntPredicate) {
            val differences: MutableList<String> = ArrayList<String>()
            var rangeIndex = 0
            for (cp in 0..Character.MAX_CODE_POINT) {
                while (rangeIndex < ranges.size && ranges[rangeIndex]!![1] < cp) {
                    rangeIndex++
                }
                val inTable = rangeIndex < ranges.size && ranges[rangeIndex]!![0] <= cp
                if (inTable != expected.test(cp) && differences.size < 10) {
                    differences.add(String.format("U+%04X", cp))
                }
            }
            Assertions.assertThat<String?>(differences).`as`("%s table membership differences", name).isEmpty()
        }

        // --- Helpers ---
        private fun assertPinnedUnicodeVersion() {
            Assertions.assertThat(UCharacter.getUnicodeVersion().toString())
                .`as`("ICU4J oracle must match the bundled Unicode data")
                .isEqualTo(UnicodeGeneratedTables.UNICODE_DATA_VERSION + ".0")
        }

        private fun runtimeJdkUnicodeMatchesGeneratedTables(): Boolean {
            val assigned =
                Objects.requireNonNull<Array<IntArray>>(UnicodeGeneratedTables.BINARY_PROPERTIES.get("Assigned"))
            var rangeIndex = 0
            for (cp in 0..Character.MAX_CODE_POINT) {
                while (rangeIndex < assigned.size && assigned[rangeIndex]!![1] < cp) {
                    rangeIndex++
                }
                val inTable = rangeIndex < assigned.size && assigned[rangeIndex]!![0] <= cp
                if (Character.isDefined(cp) != inTable) {
                    return false
                }
            }
            return true
        }

        @JvmStatic
        private fun binaryPropertyNames(): Stream<String?>? {
            return UnicodeGeneratedTables.BINARY_PROPERTIES.keys.stream().sorted()
        }

        /** Checks whether `target` is reachable from `start` via simpleFold orbits.  */
        private fun inFoldOrbit(start: Int, target: Int): Boolean {
            var r = start
            for (i in 0..9) {
                r = Inst.simpleFold(r)
                if (r == target) {
                    return true
                }
                if (r == start) {
                    break // completed the orbit
                }
            }
            return false
        }

        private fun containsCodePoint(ranges: Array<IntArray>, cp: Int): Boolean {
            for (range in ranges) {
                if (cp >= range[0] && cp <= range[1]) {
                    return true
                }
            }
            return false
        }
    }
}
