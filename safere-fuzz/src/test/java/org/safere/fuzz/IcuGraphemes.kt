// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UProperty
import com.ibm.icu.text.BreakIterator
import com.ibm.icu.util.ULocale
import java.util.function.Supplier

/** ICU4J property data and character segmentation for grapheme conformance tests and fuzzing.  */
object IcuGraphemes {
    private val PROPERTIES: Array<Properties?> = loadProperties()
    private val REPRESENTATIVES: IntArray = buildRepresentatives()

    // ICU iterators are mutable; each test/fuzz worker owns its iterator.
    private val ICU_ITERATOR: ThreadLocal<BreakIterator> =
        ThreadLocal.withInitial<BreakIterator?>(Supplier { BreakIterator.getCharacterInstance(ULocale.ROOT) })

    /** Returns the Unicode properties for a code point.  */
    fun properties(cp: Int): Properties? {
        return PROPERTIES[cp]
    }

    /** One scalar representative of every distinct property tuple, in code point order.  */
    fun representatives(): IntArray? {
        return REPRESENTATIVES.clone()
    }

    private fun buildRepresentatives(): IntArray {
        val representatives: MutableMap<Properties?, Int?> = LinkedHashMap<Properties?, Int?>()
        for (cp in PROPERTIES.indices) {
            if (cp < 0xD800 || cp > 0xDFFF) {
                representatives.putIfAbsent(PROPERTIES[cp], cp)
            }
        }
        return representatives.values.stream().mapToInt { obj: Int? -> obj!!.toInt() }.toArray()
    }

    /** Segments scalars using ICU's root-locale character break iterator.  */
    fun clusters(codePoints: IntArray): MutableList<String?> {
        val text = String(codePoints, 0, codePoints.size)
        val iterator = ICU_ITERATOR.get()
        iterator.setText(text)
        val clusters: MutableList<String?> = ArrayList<String?>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            clusters.add(text.substring(start, end))
            start = end
            end = iterator.next()
        }
        return clusters
    }

    private fun loadProperties(): Array<Properties?> {
        val gcbNames: MutableMap<Int?, String?> = HashMap<Int?, String?>()
        val incbNames: MutableMap<Int?, String?> = HashMap<Int?, String?>()
        val tuples: MutableMap<Properties?, Properties?> = HashMap<Properties?, Properties?>()
        val properties = arrayOfNulls<Properties>(Character.MAX_CODE_POINT + 1)
        for (cp in properties.indices) {
            val gcb = UCharacter.getIntPropertyValue(cp, UProperty.GRAPHEME_CLUSTER_BREAK)
            val incb = UCharacter.getIntPropertyValue(cp, UProperty.INDIC_CONJUNCT_BREAK)
            val pictograph = UCharacter.hasBinaryProperty(cp, UProperty.EXTENDED_PICTOGRAPHIC)
            val gcbName =
                gcbNames.computeIfAbsent(
                    gcb
                ) { value: Int? ->
                    UCharacter.getPropertyValueName(
                        UProperty.GRAPHEME_CLUSTER_BREAK, value!!, UProperty.NameChoice.LONG
                    )
                }
            val incbName =
                incbNames.computeIfAbsent(
                    incb
                ) { value: Int? ->
                    UCharacter.getPropertyValueName(
                        UProperty.INDIC_CONJUNCT_BREAK, value!!, UProperty.NameChoice.LONG
                    )
                }
            val tuple = Properties(gcbName, incbName, pictograph)
            properties[cp] = tuples.computeIfAbsent(tuple) { key: Properties? -> key }
        }
        return properties
    }

    /** ICU property tuple, including overlaps used by GB9c and GB11.  */
    @JvmRecord
    data class Properties(val gcb: String?, val incb: String?, val pictograph: Boolean)
}
