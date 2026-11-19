// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.safere.CharClassScanInfo.UnicodeGeneral
import org.safere.CharClassScanInfo.UnicodeSmallSet
import java.util.*
import kotlin.math.max
import kotlin.math.min

/** Tests for [CharClass] and [CharClassBuilder].  */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class CharClassTest {
    @Test
    fun emptyCharClass() {
        Assertions.assertThat(CharClass.EMPTY.numRanges()).isEqualTo(0)
        Assertions.assertThat(CharClass.EMPTY.numRunes()).isEqualTo(0)
        Assertions.assertThat(CharClass.EMPTY.isEmpty()).isTrue()
        Assertions.assertThat(CharClass.EMPTY.contains('a'.code)).isFalse()
    }

    @Test
    fun singleRange() {
        val cc = CharClassBuilder().addRange('a'.code, 'z'.code).build()
        Assertions.assertThat(cc.numRanges()).isEqualTo(1)
        Assertions.assertThat(cc.numRunes()).isEqualTo(26)
        Assertions.assertThat(cc.lo(0)).isEqualTo('a'.code)
        Assertions.assertThat(cc.hi(0)).isEqualTo('z'.code)
        Assertions.assertThat(cc.contains('a'.code)).isTrue()
        Assertions.assertThat(cc.contains('m'.code)).isTrue()
        Assertions.assertThat(cc.contains('z'.code)).isTrue()
        Assertions.assertThat(cc.contains('A'.code)).isFalse()
        Assertions.assertThat(cc.contains('0'.code)).isFalse()
    }

    @Test
    fun multipleDisjointRanges() {
        val cc =
            CharClassBuilder().addRange('0'.code, '9'.code).addRange('A'.code, 'Z'.code).addRange('a'.code, 'z'.code)
                .build()
        Assertions.assertThat(cc.numRanges()).isEqualTo(3)
        Assertions.assertThat(cc.numRunes()).isEqualTo(10 + 26 + 26)
        Assertions.assertThat(cc.contains('5'.code)).isTrue()
        Assertions.assertThat(cc.contains('G'.code)).isTrue()
        Assertions.assertThat(cc.contains('p'.code)).isTrue()
        Assertions.assertThat(cc.contains('!'.code)).isFalse()
        Assertions.assertThat(cc.contains('['.code)).isFalse()
    }

    @Test
    fun overlappingRangesMerge() {
        val cc = CharClassBuilder().addRange('a'.code, 'm'.code).addRange('k'.code, 'z'.code).build()
        Assertions.assertThat(cc.numRanges()).isEqualTo(1)
        Assertions.assertThat(cc.numRunes()).isEqualTo(26)
        Assertions.assertThat(cc.lo(0)).isEqualTo('a'.code)
        Assertions.assertThat(cc.hi(0)).isEqualTo('z'.code)
    }

    @Test
    fun adjacentRangesMerge() {
        val cc = CharClassBuilder().addRange('a'.code, 'f'.code).addRange('g'.code, 'z'.code).build()
        Assertions.assertThat(cc.numRanges()).isEqualTo(1)
        Assertions.assertThat(cc.numRunes()).isEqualTo(26)
    }

    @Test
    fun addRangeMergesOnlyNeighboringRanges() {
        val cc =
            CharClassBuilder()
                .addRange('a'.code, 'c'.code)
                .addRange('m'.code, 'p'.code)
                .addRange('x'.code, 'z'.code)
                .addRange('d'.code, 'n'.code)
                .build()

        Assertions.assertThat(cc.numRanges()).isEqualTo(2)
        Assertions.assertThat(cc.lo(0)).isEqualTo('a'.code)
        Assertions.assertThat(cc.hi(0)).isEqualTo('p'.code)
        Assertions.assertThat(cc.lo(1)).isEqualTo('x'.code)
        Assertions.assertThat(cc.hi(1)).isEqualTo('z'.code)
    }

    @Test
    fun addRangeKeepsEarlierDisjointRanges() {
        val cc =
            CharClassBuilder()
                .addRange('a'.code, 'c'.code)
                .addRange('m'.code, 'p'.code)
                .addRange('x'.code, 'z'.code)
                .addRange('q'.code, 'v'.code)
                .build()

        Assertions.assertThat(cc.numRanges()).isEqualTo(3)
        Assertions.assertThat(cc.lo(0)).isEqualTo('a'.code)
        Assertions.assertThat(cc.hi(0)).isEqualTo('c'.code)
        Assertions.assertThat(cc.lo(1)).isEqualTo('m'.code)
        Assertions.assertThat(cc.hi(1)).isEqualTo('v'.code)
        Assertions.assertThat(cc.lo(2)).isEqualTo('x'.code)
        Assertions.assertThat(cc.hi(2)).isEqualTo('z'.code)
    }

    @Test
    fun addRangeBeforeAllRanges() {
        val cc =
            CharClassBuilder().addRange(100, 110).addRange(200, 210).addRange(10, 20).build()

        assertRanges(cc, 10, 20, 100, 110, 200, 210)
    }

    @Test
    fun addRangeAfterAllRanges() {
        val cc =
            CharClassBuilder().addRange(10, 20).addRange(100, 110).addRange(200, 210).build()

        assertRanges(cc, 10, 20, 100, 110, 200, 210)
    }

    @Test
    fun addRangeMergesPreviousNeighbor() {
        val cc =
            CharClassBuilder().addRange(10, 20).addRange(100, 110).addRange(21, 30).build()

        assertRanges(cc, 10, 30, 100, 110)
    }

    @Test
    fun addRangeMergesNextNeighbor() {
        val cc =
            CharClassBuilder().addRange(10, 20).addRange(100, 110).addRange(90, 99).build()

        assertRanges(cc, 10, 20, 90, 110)
    }

    @Test
    fun addRangeMergesAcrossMultipleRanges() {
        val cc =
            CharClassBuilder()
                .addRange(10, 20)
                .addRange(30, 40)
                .addRange(50, 60)
                .addRange(21, 49)
                .build()

        assertRanges(cc, 10, 60)
    }

    @Test
    fun addRangeHandlesEmptyAndBoundaryRanges() {
        val cc =
            CharClassBuilder()
                .addRange(20, 10)
                .addRange(0, 0)
                .addRange(Utils.MAX_RUNE, Utils.MAX_RUNE)
                .build()

        assertRanges(cc, 0, 0, Utils.MAX_RUNE, Utils.MAX_RUNE)
    }

    @Test
    fun randomizedAddRangeMatchesBitSetReferenceModel() {
        val domainSize = 4096
        val random = Random(0x5AFE289L)

        for (trial in 0..199) {
            val builder = CharClassBuilder()
            val expected = BitSet(domainSize)

            for (step in 0..299) {
                val a = random.nextInt(domainSize)
                val b = random.nextInt(domainSize)
                if (random.nextInt(10) == 0) {
                    builder.addRange(a, b)
                    if (a <= b) {
                        expected.set(a, b + 1)
                    }
                } else {
                    val lo = min(a, b)
                    val hi = max(a, b)
                    builder.addRange(lo, hi)
                    expected.set(lo, hi + 1)
                }
            }

            assertMatchesReferenceModel(builder.build(), expected, domainSize)
        }
    }

    @Test
    fun randomizedOperationsInAnyOrderMatchBitSetReferenceModel() {
        val domainSize = 2048
        val random = Random(0x5AFE291L)

        for (trial in 0..199) {
            val builder = CharClassBuilder()
            val expected = BitSet(domainSize)

            for (step in 0..59) {
                val a = random.nextInt(domainSize)
                val b = random.nextInt(domainSize)
                val lo = min(a, b)
                val hi = min(max(a, b), lo + random.nextInt(64))
                when (random.nextInt(8)) {
                    0, 1, 2 -> {
                        builder.addRange(lo, hi)
                        expected.set(lo, hi + 1)
                    }

                    3 -> {
                        builder.removeRange(lo, hi)
                        expected.clear(lo, hi + 1)
                    }

                    4 -> {
                        val r = random.nextInt(domainSize)
                        Assertions.assertThat(builder.contains(r)).isEqualTo(expected.get(r))
                        Assertions.assertThat(builder.numRunes()).isEqualTo(expected.cardinality())
                    }

                    5 -> {
                        val other = CharClassBuilder()
                        val otherExpected = BitSet(domainSize)
                        for (i in 0..7) {
                            val olo = random.nextInt(domainSize)
                            val ohi = min(domainSize - 1, olo + random.nextInt(256))
                            other.addRange(olo, ohi)
                            otherExpected.set(olo, ohi + 1)
                        }
                        builder.intersect(other)
                        expected.and(otherExpected)
                    }

                    6 -> {
                        val other = CharClassBuilder().addRange(hi, hi).addRange(lo, lo)
                        builder.addCharClass(other)
                        expected.set(lo)
                        expected.set(hi)
                    }

                    else -> {
                        // Negate twice so the domain stays within the reference model's range.
                        builder.negate().negate()
                    }
                }
            }

            assertMatchesReferenceModel(builder.build(), expected, domainSize)
        }
    }

    @Test
    fun singleRune() {
        val cc = CharClassBuilder().addRune('X'.code).build()
        Assertions.assertThat(cc.numRanges()).isEqualTo(1)
        Assertions.assertThat(cc.numRunes()).isEqualTo(1)
        Assertions.assertThat(cc.contains('X'.code)).isTrue()
        Assertions.assertThat(cc.contains('Y'.code)).isFalse()
    }

    @Test
    fun addTable() {
        val table = arrayOf<IntArray>(intArrayOf(0x30, 0x39), intArrayOf(0x41, 0x5A), intArrayOf(0x61, 0x7A))
        val cc = CharClassBuilder().addTable(table).build()
        Assertions.assertThat(cc.numRanges()).isEqualTo(3)
        Assertions.assertThat(cc.contains('5'.code)).isTrue()
        Assertions.assertThat(cc.contains('G'.code)).isTrue()
        Assertions.assertThat(cc.contains('p'.code)).isTrue()
    }

    @Test
    fun negateAsciiDigits() {
        val digits = CharClassBuilder().addRange('0'.code, '9'.code).build()
        val notDigits = digits.negate()
        Assertions.assertThat(notDigits.contains('5'.code)).isFalse()
        Assertions.assertThat(notDigits.contains('a'.code)).isTrue()
        Assertions.assertThat(notDigits.contains(0)).isTrue()
        Assertions.assertThat(notDigits.contains(0x10FFFF)).isTrue()
    }

    @Test
    fun negateIncludesLoneSurrogates() {
        // java.util.regex character-class negation can match lone surrogate code units.
        val empty = CharClass.EMPTY.negate()
        Assertions.assertThat(empty.contains(0xD800)).isTrue()
        Assertions.assertThat(empty.contains(0xDBFF)).isTrue()
        Assertions.assertThat(empty.contains(0xDC00)).isTrue()
        Assertions.assertThat(empty.contains(0xDFFF)).isTrue()
        Assertions.assertThat(empty.contains(0xD7FF)).isTrue()
        Assertions.assertThat(empty.contains(0xE000)).isTrue()
    }

    @Test
    fun doubleNegateRestoresOriginal() {
        val cc = CharClassBuilder().addRange('a'.code, 'z'.code).build()
        val doubleNeg = cc.negate().negate()
        Assertions.assertThat(doubleNeg.numRanges()).isEqualTo(cc.numRanges())
        Assertions.assertThat(doubleNeg.numRunes()).isEqualTo(cc.numRunes())
        for (i in 0..<cc.numRanges()) {
            Assertions.assertThat(doubleNeg.lo(i)).isEqualTo(cc.lo(i))
            Assertions.assertThat(doubleNeg.hi(i)).isEqualTo(cc.hi(i))
        }
    }

    @Test
    fun removeRange() {
        val builder = CharClassBuilder()
        builder.addRange('a'.code, 'z'.code)
        builder.removeRange('m'.code, 'n'.code)
        val cc = builder.build()
        Assertions.assertThat(cc.numRanges()).isEqualTo(2)
        Assertions.assertThat(cc.contains('a'.code)).isTrue()
        Assertions.assertThat(cc.contains('l'.code)).isTrue()
        Assertions.assertThat(cc.contains('m'.code)).isFalse()
        Assertions.assertThat(cc.contains('n'.code)).isFalse()
        Assertions.assertThat(cc.contains('o'.code)).isTrue()
        Assertions.assertThat(cc.contains('z'.code)).isTrue()
    }

    @Test
    fun removeRangeBeforeAllRanges() {
        val cc =
            CharClassBuilder().addRange(100, 110).addRange(200, 210).removeRange(10, 20).build()

        assertRanges(cc, 100, 110, 200, 210)
    }

    @Test
    fun removeRangeAfterAllRanges() {
        val cc =
            CharClassBuilder().addRange(10, 20).addRange(100, 110).removeRange(200, 210).build()

        assertRanges(cc, 10, 20, 100, 110)
    }

    @Test
    fun removeRangeTrimsLeftSide() {
        val cc =
            CharClassBuilder().addRange(10, 20).addRange(100, 110).removeRange(5, 12).build()

        assertRanges(cc, 13, 20, 100, 110)
    }

    @Test
    fun removeRangeTrimsRightSide() {
        val cc =
            CharClassBuilder().addRange(10, 20).addRange(100, 110).removeRange(18, 30).build()

        assertRanges(cc, 10, 17, 100, 110)
    }

    @Test
    fun removeRangeRemovesAcrossMultipleRanges() {
        val cc =
            CharClassBuilder()
                .addRange(10, 20)
                .addRange(30, 40)
                .addRange(50, 60)
                .removeRange(15, 55)
                .build()

        assertRanges(cc, 10, 14, 56, 60)
    }

    @Test
    fun removeRangeHandlesEmptyAndBoundaryRanges() {
        val cc =
            CharClassBuilder()
                .addRange(0, 20)
                .addRange(Utils.MAX_RUNE - 20, Utils.MAX_RUNE)
                .removeRange(20, 10)
                .removeRange(0, 0)
                .removeRange(Utils.MAX_RUNE, Utils.MAX_RUNE)
                .build()

        assertRanges(cc, 1, 20, Utils.MAX_RUNE - 20, Utils.MAX_RUNE - 1)
    }

    @Test
    fun removeEntireRange() {
        val builder = CharClassBuilder()
        builder.addRange('a'.code, 'c'.code)
        builder.removeRange('a'.code, 'c'.code)
        Assertions.assertThat(builder.isEmpty()).isTrue()
    }

    @Test
    fun randomizedAddAndRemoveRangeMatchesBitSetReferenceModel() {
        val domainSize = 4096
        val random = Random(0x5AFE290L)

        for (trial in 0..199) {
            val builder = CharClassBuilder()
            val expected = BitSet(domainSize)

            for (step in 0..299) {
                val a = random.nextInt(domainSize)
                val b = random.nextInt(domainSize)
                val lo = min(a, b)
                val hi = max(a, b)

                if (random.nextBoolean()) {
                    builder.addRange(lo, hi)
                    expected.set(lo, hi + 1)
                } else {
                    builder.removeRange(lo, hi)
                    expected.clear(lo, hi + 1)
                }
            }

            assertMatchesReferenceModel(builder.build(), expected, domainSize)
        }
    }

    @Test
    fun builderContains() {
        val builder = CharClassBuilder()
        builder.addRange('a'.code, 'z'.code)
        Assertions.assertThat(builder.contains('a'.code)).isTrue()
        Assertions.assertThat(builder.contains('m'.code)).isTrue()
        Assertions.assertThat(builder.contains('z'.code)).isTrue()
        Assertions.assertThat(builder.contains('A'.code)).isFalse()
    }

    @Test
    fun addCharClassMerges() {
        val cc1 = CharClassBuilder().addRange('a'.code, 'f'.code).build()
        val cc2 = CharClassBuilder().addRange('d'.code, 'z'.code).build()
        val merged = CharClassBuilder().addCharClass(cc1).addCharClass(cc2).build()
        Assertions.assertThat(merged.numRanges()).isEqualTo(1)
        Assertions.assertThat(merged.numRunes()).isEqualTo(26)
    }

    @Test
    fun builderFromCharClass() {
        val original = CharClassBuilder().addRange('a'.code, 'z'.code).addRange('0'.code, '9'.code).build()
        val copy = CharClassBuilder(original)
        val rebuilt = copy.build()
        Assertions.assertThat(rebuilt.numRanges()).isEqualTo(original.numRanges())
        Assertions.assertThat(rebuilt.numRunes()).isEqualTo(original.numRunes())
    }

    @Test
    fun charClassToString() {
        val cc = CharClassBuilder().addRange('a'.code, 'z'.code).addRune('!'.code).build()
        val s = cc.toString()
        Assertions.assertThat(s).startsWith("[")
        Assertions.assertThat(s).endsWith("]")
        Assertions.assertThat(s).contains("0x61-0x7A") // a-z
        Assertions.assertThat(s).contains("0x21") // !
    }

    @Test
    fun supplementaryCodePoints() {
        // U+1F600 to U+1F64F (Emoticons)
        val cc = CharClassBuilder().addRange(0x1F600, 0x1F64F).build()
        Assertions.assertThat(cc.numRanges()).isEqualTo(1)
        Assertions.assertThat(cc.contains(0x1F600)).isTrue()
        Assertions.assertThat(cc.contains(0x1F64F)).isTrue()
        Assertions.assertThat(cc.contains(0x1F5FF)).isFalse()
    }

    @Test
    fun scanInfoRetainsPrecomputedRanges() {
        val smallSet =
            CharClassScanInfo.fromCharClass(CharClassBuilder().addRune('a'.code).addRune('z'.code).build())!!
        val bitmap =
            CharClassScanInfo.fromCharClass(
                CharClassBuilder().addRune('a'.code).addRune('c'.code).addRune('e'.code).addRune('g'.code).build()
            )!!

        Assertions.assertThat(smallSet.ranges).isSameAs(smallSet.ranges)
        Assertions.assertThat(bitmap.ranges).isSameAs(bitmap.ranges)
    }

    @Test
    fun unicodeSmallSetHoldsAtMostTwoNonSurrogateBmpChars() {
        Assertions.assertThat<CharClassScanInfo?>(scanInfo('['.code, 0xFF3B)).isInstanceOf(UnicodeSmallSet::class.java)
        Assertions.assertThat((scanInfo('['.code, 0xFF3B) as CharClassScanInfo.SmallSet).chars)
            .containsExactly('[', '\uFF3B')
        Assertions.assertThat<CharClassScanInfo?>(scanInfo(0xFF3B)).isInstanceOf(UnicodeSmallSet::class.java)

        // Three members would need three indexOf passes.
        Assertions.assertThat<CharClassScanInfo?>(scanInfo('['.code, 0xFF3B, 0x3010))
            .isInstanceOf(UnicodeGeneral::class.java)
        // A char search for a lone surrogate would also stop inside valid surrogate pairs.
        Assertions.assertThat<CharClassScanInfo?>(scanInfo(0xD83D)).isInstanceOf(UnicodeGeneral::class.java)
        Assertions.assertThat<CharClassScanInfo?>(scanInfo('['.code, 0xDC00)).isInstanceOf(UnicodeGeneral::class.java)
        // Supplementary code points are not single chars.
        Assertions.assertThat<CharClassScanInfo?>(scanInfo(0x1F600)).isInstanceOf(UnicodeGeneral::class.java)
    }

    companion object {
        private fun scanInfo(vararg runes: Int): CharClassScanInfo {
            val builder = CharClassBuilder()
            for (rune in runes) {
                builder.addRune(rune)
            }
            return CharClassScanInfo.fromCharClass(builder.build())!!
        }

        private fun assertRanges(cc: CharClass, vararg endpoints: Int) {
            Assertions.assertThat(endpoints.size).isEven()
            Assertions.assertThat(cc.numRanges()).isEqualTo(endpoints.size / 2)

            var expectedRunes = 0
            var i = 0
            while (i < endpoints.size) {
                val rangeIndex = i / 2
                Assertions.assertThat(cc.lo(rangeIndex)).isEqualTo(endpoints[i])
                Assertions.assertThat(cc.hi(rangeIndex)).isEqualTo(endpoints[i + 1])
                expectedRunes += endpoints[i + 1] - endpoints[i] + 1
                i += 2
            }
            Assertions.assertThat(cc.numRunes()).isEqualTo(expectedRunes)
        }

        private fun assertMatchesReferenceModel(cc: CharClass, expected: BitSet, domainSize: Int) {
            var expectedRunes = 0
            for (r in 0..<domainSize) {
                val shouldContain = expected.get(r)
                Assertions.assertThat(cc.contains(r)).isEqualTo(shouldContain)
                if (shouldContain) {
                    expectedRunes++
                }
            }

            Assertions.assertThat(cc.numRunes()).isEqualTo(expectedRunes)

            var rangeIndex = 0
            var lo = expected.nextSetBit(0)
            while (lo >= 0) {
                val hiExclusive = expected.nextClearBit(lo)
                Assertions.assertThat(rangeIndex).isLessThan(cc.numRanges())
                Assertions.assertThat(cc.lo(rangeIndex)).isEqualTo(lo)
                Assertions.assertThat(cc.hi(rangeIndex)).isEqualTo(hiExclusive - 1)
                rangeIndex++
                lo = expected.nextSetBit(hiExclusive)
            }
            Assertions.assertThat(rangeIndex).isEqualTo(cc.numRanges())
        }
    }
}
