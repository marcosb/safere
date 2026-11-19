// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.safere.Matcher.LiteralPreparedRunner
import org.safere.Matcher.PreparedMatchRunner
import org.safere.RarityOracle.AsciiPair
import java.nio.charset.StandardCharsets

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class RarityOracleTest {
    @Test
    fun utf8LiteralAnchorAvoidsCommonLeadBytes() {
        val russian = "Шерлок Холмс".toByteArray(StandardCharsets.UTF_8)

        Assertions.assertThat(RarityOracle.rarestUtf8LiteralByteOffset(russian)).isEqualTo(1)
        Assertions.assertThat(RarityOracle.rarestUtf8LiteralByteOffset("Sherlock Holmes".toByteArray(StandardCharsets.UTF_8)))
            .isEqualTo(-1)
    }

    @Test
    fun spaceIsMostCommonAndRareLettersHaveHighRank() {
        Assertions.assertThat(RarityOracle.exactByteRarity(' '.code)).isEqualTo(0)
        Assertions.assertThat(RarityOracle.caseFoldedByteRarity(' '.code)).isEqualTo(0)
        Assertions.assertThat(RarityOracle.exactByteRarity('e'.code)).isLessThan(RarityOracle.exactByteRarity('z'.code))
        Assertions.assertThat(RarityOracle.exactByteRarity('t'.code)).isLessThan(RarityOracle.exactByteRarity('q'.code))
        Assertions.assertThat(RarityOracle.exactByteRarity('a'.code)).isLessThan(RarityOracle.exactByteRarity('x'.code))
    }

    @Test
    fun caseInsensitiveLettersShareIdenticalRanks() {
        Assertions.assertThat(RarityOracle.caseFoldedByteRarity('A'.code))
            .isEqualTo(RarityOracle.caseFoldedByteRarity('a'.code))
        Assertions.assertThat(RarityOracle.caseFoldedByteRarity('Z'.code))
            .isEqualTo(RarityOracle.caseFoldedByteRarity('z'.code))
        Assertions.assertThat(RarityOracle.caseFoldedByteRarity('E'.code))
            .isEqualTo(RarityOracle.caseFoldedByteRarity('e'.code))
    }

    @Test
    fun exactCaseDistinguishesUppercaseAndLowercaseRarity() {
        Assertions.assertThat(RarityOracle.exactByteRarity('A'.code))
            .isGreaterThan(RarityOracle.exactByteRarity('a'.code))
        Assertions.assertThat(RarityOracle.exactByteRarity('E'.code))
            .isGreaterThan(RarityOracle.exactByteRarity('e'.code))
        Assertions.assertThat(RarityOracle.exactByteRarity('Z'.code))
            .isGreaterThan(RarityOracle.exactByteRarity('z'.code))
    }

    @Test
    fun rarestAsciiOffsetFindsRarestCharacterCaseFolded() {
        // 't', 'h', 'e' are common, 'q' is rare
        val prefix = "the_query"
        val offset = RarityOracle.rarestAsciiOffset(prefix, prefix.length, true)
        Assertions.assertThat(offset).isEqualTo(prefix.indexOf('q'))

        // 'a' is common, 'z' is rare
        val zone = "authorization"
        Assertions.assertThat(RarityOracle.rarestAsciiOffset(zone, zone.length, true))
            .isEqualTo(zone.indexOf('z'))
    }

    @Test
    fun rarestAsciiOffsetFindsUppercaseAnchorForExactCase() {
        val bean = "AbstractBeanFactory"
        val offset = RarityOracle.rarestAsciiOffset(bean, bean.length, false)
        Assertions.assertThat(bean.get(offset)).isIn('B', 'F')

        val header = "Content-Type"
        val headerOffset = RarityOracle.rarestAsciiOffset(header, header.length, false)
        Assertions.assertThat(header.get(headerOffset)).isIn('C', 'T')
    }

    @Test
    fun literalSelectivityRewardsRareCharacters() {
        // "404_NOT_FOUND" contains digits, underscores, and rare letters
        val rareScore = RarityOracle.literalSelectivityScore("404_NOT_FOUND")
        // "              " (spaces of equal length) has very low score
        val commonScore = RarityOracle.literalSelectivityScore("             ")
        Assertions.assertThat(rareScore).isGreaterThan(commonScore * 3)
    }

    @Test
    fun literalSelectivityRetainsLengthForTheMostCommonCharacter() {
        Assertions.assertThat(RarityOracle.literalSelectivityScore(" ".repeat(32)))
            .isGreaterThan(RarityOracle.literalSelectivityScore("ee"))
    }

    @Test
    fun poisonousAnchorDetection() {
        Assertions.assertThat(RarityOracle.isPoisonousAnchor(" ", false)).isTrue()
        Assertions.assertThat(RarityOracle.isPoisonousAnchor("e", false)).isTrue()
        Assertions.assertThat(RarityOracle.isPoisonousAnchor("E", false)).isFalse()
        Assertions.assertThat(RarityOracle.isPoisonousAnchor("E", true)).isTrue()
        Assertions.assertThat(RarityOracle.isPoisonousAnchor("z", false)).isFalse()
        Assertions.assertThat(RarityOracle.isPoisonousAnchor("q", true)).isFalse()
        Assertions.assertThat(RarityOracle.isPoisonousAnchor("404", false)).isFalse()
        Assertions.assertThat(RarityOracle.isPoisonousAnchor("  ", false)).isFalse()
        Assertions.assertThat(RarityOracle.isPoisonousAnchor(null, false)).isFalse()
        Assertions.assertThat(RarityOracle.isPoisonousAnchor("", false)).isFalse()
    }

    @Test
    fun literalSelectivityDistinguishesExactAndFoldedScores() {
        val exactUpperScore = RarityOracle.literalSelectivityScore("ERROR", false)
        val exactLowerScore = RarityOracle.literalSelectivityScore("error", false)
        Assertions.assertThat(exactUpperScore).isGreaterThan(exactLowerScore)
    }

    @Test
    fun foldedSelectivityIsInvariantToPatternCapitalization() {
        Assertions.assertThat(RarityOracle.literalSelectivityScore("Xq", true))
            .isEqualTo(RarityOracle.literalSelectivityScore("xq", true))
        Assertions.assertThat(RarityOracle.literalSelectivityScore("Za", true))
            .isEqualTo(RarityOracle.literalSelectivityScore("za", true))
    }

    @Test
    fun foldedPreparedLiteralSelectsRarestCaseFoldedAnchor() {
        val runner = Pattern.compile("(?i)jq").preparedMatchRunner(false)

        Assertions.assertThat<PreparedMatchRunner?>(runner).isInstanceOf(LiteralPreparedRunner::class.java)
        val literalRunner = runner as LiteralPreparedRunner
        Assertions.assertThat(literalRunner.anchorOffset()).isEqualTo(1)
        Assertions.assertThat(literalRunner.anchorLow()).isEqualTo('q')
        Assertions.assertThat(literalRunner.anchorHigh()).isEqualTo('Q')
    }

    @Test
    fun nonAsciiLiteralsAreNotScoredAsUtf8ByteValues() {
        Assertions.assertThat(RarityOracle.literalSelectivityScore("é"))
            .isGreaterThan(RarityOracle.literalSelectivityScore("eee"))
        Assertions.assertThat(RarityOracle.literalSelectivityScore("é"))
            .isEqualTo(RarityOracle.literalSelectivityScore("Ā"))
    }

    @Test
    fun rarestAsciiOffsetIgnoresNonAsciiCharacters() {
        // '\u03B1' (Greek alpha, c >= 256) defaults to byteRarity 255, which would erroneously
        // preempt ASCII characters if not strictly filtered to c < 128.
        val mixedGreek = "c\u03B1b"
        Assertions.assertThat(RarityOracle.rarestAsciiOffset(mixedGreek, mixedGreek.length, false))
            .isEqualTo(2) // 'b' (offset 2, rank 39), not '\u03B1' (offset 1, rank 255)

        val mixedGreekFirst = "b\u03B1c"
        Assertions.assertThat(RarityOracle.rarestAsciiOffset(mixedGreekFirst, mixedGreekFirst.length, false))
            .isEqualTo(0) // 'b' (offset 0, rank 39), not '\u03B1' (offset 1, rank 255)

        val leadingNonAscii = "\u03B1the_query"
        Assertions.assertThat(RarityOracle.rarestAsciiOffset(leadingNonAscii, leadingNonAscii.length, true))
            .isEqualTo(leadingNonAscii.indexOf('q')) // 'q' (offset 5), not '\u03B1' (offset 0)

        // Latin-1 characters (c >= 128) such as '\u00E9' (0xE9 = 233) or '\u00A9' (0xA9 = 169)
        // must not be chosen as ASCII anchors.
        val mixedLatin1 = "caf\u00E9"
        Assertions.assertThat(RarityOracle.rarestAsciiOffset(mixedLatin1, mixedLatin1.length, false))
            .isEqualTo(2) // 'f' (offset 2), not '\u00E9' (offset 3)

        val highLatin1 = "the_\u00A9query"
        Assertions.assertThat(RarityOracle.rarestAsciiOffset(highLatin1, highLatin1.length, false))
            .isEqualTo(highLatin1.indexOf('q'))
    }

    @Test
    fun rarestAsciiOffsetDefaultsToZeroWhenAllCharactersNonAscii() {
        val allGreek = "\u03B1\u03B2\u03B3"
        Assertions.assertThat(RarityOracle.rarestAsciiOffset(allGreek, allGreek.length, false)).isEqualTo(0)
        Assertions.assertThat(RarityOracle.rarestAsciiOffset(allGreek, allGreek.length, true)).isEqualTo(0)

        val allLatin1 = "\u00E9\u00E8\u00EA"
        Assertions.assertThat(RarityOracle.rarestAsciiOffset(allLatin1, allLatin1.length, false)).isEqualTo(0)
        Assertions.assertThat(RarityOracle.rarestAsciiOffset(allLatin1, allLatin1.length, true)).isEqualTo(0)

        Assertions.assertThat(RarityOracle.rarestAsciiOffset("", 0, false)).isEqualTo(0)
        Assertions.assertThat(RarityOracle.rarestAsciiOffset("abc", 0, false)).isEqualTo(0)
    }

    @Test
    fun utf8LeadBytesClampedToZeroRarity() {
        // Bytes 0xC0..0xFF (192..255) are clamped to 0 (treated as frequent as space) to avoid
        // verification traps on UTF-8 continuation sequences in byte streams.
        for (b in 0xC0..0xFF) {
            Assertions.assertThat(RarityOracle.exactByteRarity(b))
                .`as`("exactByteRarity for UTF-8 lead byte 0x%02X", b)
                .isEqualTo(0)
            Assertions.assertThat(RarityOracle.caseFoldedByteRarity(b))
                .`as`("caseFoldedByteRarity for UTF-8 lead byte 0x%02X", b)
                .isEqualTo(0)
        }
        // Out-of-byte-range code units (c >= 256 or c < 0) default to 255.
        Assertions.assertThat(RarityOracle.exactByteRarity(256)).isEqualTo(255)
        Assertions.assertThat(RarityOracle.exactByteRarity(-1)).isEqualTo(255)
        Assertions.assertThat(RarityOracle.caseFoldedByteRarity(256)).isEqualTo(255)
        Assertions.assertThat(RarityOracle.caseFoldedByteRarity(-1)).isEqualTo(255)
    }

    @Test
    fun rarestAsciiPairIgnoreCaseReturnsNullForShortOrNonAscii() {
        Assertions.assertThat<AsciiPair?>(RarityOracle.rarestAsciiPairIgnoreCase("", 0)).isNull()
        Assertions.assertThat<AsciiPair?>(RarityOracle.rarestAsciiPairIgnoreCase("a", 1)).isNull()
        Assertions.assertThat<AsciiPair?>(RarityOracle.rarestAsciiPairIgnoreCase("a\u0080", 2)).isNull()
        Assertions.assertThat<AsciiPair?>(RarityOracle.rarestAsciiPairIgnoreCase("hello\u00FFworld", 12)).isNull()
    }

    @Test
    fun rarestAsciiPairIgnoreCaseFindsTwoRarestWithOrderedOffsets() {
        val prefix = "content-type"
        val pair = RarityOracle.rarestAsciiPairIgnoreCase(prefix, prefix.length)
        Assertions.assertThat<AsciiPair?>(pair).isNotNull()
        Assertions.assertThat(pair!!.offset1).isLessThan(pair.offset2)
        val c1 = prefix.get(pair.offset1)
        val c2 = prefix.get(pair.offset2)
        Assertions.assertThat(pair.low1).isEqualTo(Ascii.toLowerCase(c1).code.toByte())
        Assertions.assertThat(pair.high1).isEqualTo(Ascii.toUpperCase(c1).code.toByte())
        Assertions.assertThat(pair.low2).isEqualTo(Ascii.toLowerCase(c2).code.toByte())
        Assertions.assertThat(pair.high2).isEqualTo(Ascii.toUpperCase(c2).code.toByte())
    }

    @Test
    fun rarestAsciiPairIgnoreCaseHandlesIdenticalCharacters() {
        val prefix = "banana"
        val pair = RarityOracle.rarestAsciiPairIgnoreCase(prefix, prefix.length)
        Assertions.assertThat<AsciiPair?>(pair).isNotNull()
        Assertions.assertThat(pair!!.offset1).isLessThan(pair.offset2)
        val c1 = prefix.get(pair.offset1)
        val c2 = prefix.get(pair.offset2)
        Assertions.assertThat(pair.low1).isEqualTo(Ascii.toLowerCase(c1).code.toByte())
        Assertions.assertThat(pair.high1).isEqualTo(Ascii.toUpperCase(c1).code.toByte())
        Assertions.assertThat(pair.low2).isEqualTo(Ascii.toLowerCase(c2).code.toByte())
        Assertions.assertThat(pair.high2).isEqualTo(Ascii.toUpperCase(c2).code.toByte())
    }

    @Test
    fun charClassFrequencyScoreScoresByAsciiMembersOnly() {
        val asciiBracket = CharClassBuilder().addRune('['.code).build()
        val mixedBracket = CharClassBuilder().addRune('['.code).addRune(0xFF3B).build()
        val dot = CharClassBuilder().addRune('.'.code).build()
        val digits = CharClassBuilder().addRange('0'.code, '9'.code).build()
        val purelyNonAscii = CharClassBuilder().addRune(0xFF3B).addRune(0xFF3D).build()

        Assertions.assertThat(RarityOracle.charClassFrequencyScore(mixedBracket))
            .isEqualTo(RarityOracle.charClassFrequencyScore(asciiBracket))
            .isLessThan(RarityOracle.charClassFrequencyScore(dot))
            .isLessThan(RarityOracle.charClassFrequencyScore(digits))
        Assertions.assertThat(RarityOracle.charClassFrequencyScore(purelyNonAscii)).isZero()
        Assertions.assertThat(
            RarityOracle.charClassFrequencyScore(CharClassScanInfo.fromCharClass(purelyNonAscii))
        )
            .isZero()

        // Two non-ASCII members are ignored; a third, or a large range, makes the class unscorable.
        val twoNonAscii =
            CharClassBuilder().addRune('['.code).addRune(0xFF3B).addRune(0xFF3D).build()
        val threeNonAscii =
            CharClassBuilder().addRune('['.code).addRune(0xFF3B).addRune(0xFF3D).addRune(0x3010).build()
        val cjkRange = CharClassBuilder().addRune('-'.code).addRange(0x4E00, 0x9FFF).build()
        Assertions.assertThat(RarityOracle.charClassFrequencyScore(twoNonAscii))
            .isEqualTo(RarityOracle.charClassFrequencyScore(asciiBracket))
        Assertions.assertThat(RarityOracle.charClassFrequencyScore(threeNonAscii)).isZero()
        Assertions.assertThat(RarityOracle.charClassFrequencyScore(cjkRange)).isZero()
        Assertions.assertThat(RarityOracle.charClassFrequencyScore(CharClassScanInfo.fromCharClass(cjkRange)))
            .isZero()
    }
}
