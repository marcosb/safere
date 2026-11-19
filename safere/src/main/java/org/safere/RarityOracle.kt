// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Empirical character and byte rarity oracle for regex literal and start acceleration.
 * 
 * 
 * Assigns empirical frequency ranks to bytes (0..255) to optimize SIMD anchor selection and
 * literal selectivity scoring. Higher rank indicates rarer characters (0 = most common, e.g. space;
 * 255 = rarest, e.g. out-of-range code points or rare control characters).
 * 
 * 
 * The frequency distribution is adapted from the `BYTE_FREQUENCIES` table used by `regex-automata`, `aho-corasick`, and `memchr` (licensed under MIT / Unlicense). The
 * table's original generator analyzed UTF-8 bytes from the CIA World Factbook, the Rust compiler
 * source, and the Septuagint. It normalized each corpus independently so that each contributed
 * equal total weight, summed those normalized frequencies, and converted the result to ordinal byte
 * ranks. SafeRE inverts those ranks so that higher values indicate rarer bytes.
 * 
 * 
 * **UTF-8 Lead-Byte Clamping and Character Domain Boundary:** In the original UTF-8 byte
 * distribution, bytes `192..255` (`0xC0..0xFF`) are clamped to maximum frequency
 * (assigned rarity rank `0`, equivalent to space). This is an essential heuristic for UTF-8
 * byte streams to prevent candidate verification storms on UTF-8 multibyte continuation lead bytes
 * (for example, `0xD0` in Cyrillic text or `0xE4` in CJK documents). In Java, [ ] and [CharSequence] inputs may represent Latin-1 characters (where `0xC0..0xFF` represent accented Latin-1 letters such as `'é'` or `'ü'`) or UTF-16 code
 * units (where `c >= 256` defaults to rarity rank `255`). To ensure semantic safety
 * when selecting broadcast anchors for ASCII-only SIMD and SWAR case-folding loops, [ ][.rarestAsciiOffset] strictly restricts anchor selection to the ASCII range (`c < 128`).
 * 
 * 
 * Two distinct frequency distributions are used:
 * 
 * 
 *  * **Exact-case rarity ([.exactByteRarity]):** Uses the inverted upstream byte
 * ranks directly. In exact matching, lowercase and uppercase letters retain their distinct
 * corpus-derived ranks.
 *  * **Case-folded rarity ([.caseFoldedByteRarity]):** For each ASCII letter pair,
 * assigns both cases the lower rarity rank (the more common member's rank). This conservative
 * proxy prevents a case-insensitive anchor from being scored as rarer than either case it can
 * match. For example, `'e'` and `'E'` share the same rank in this model.
 * 
 */
internal object RarityOracle {
    private const val MIN_LITERAL_BYTE_RARITY = 128
    private const val MIN_LITERAL_RARITY_ADVANTAGE = 32
    private val EXACT_BYTE_RARITY = byteArrayOf(
        200.toByte(), 203.toByte(), 204.toByte(), 205.toByte(), 206.toByte(), 207.toByte(), 208.toByte(),
        209.toByte(), 210.toByte(), 152.toByte(), 13.toByte(), 189.toByte(), 188.toByte(), 26.toByte(),
        211.toByte(), 212.toByte(),
        213.toByte(), 214.toByte(), 215.toByte(), 216.toByte(), 217.toByte(), 218.toByte(), 219.toByte(),
        220.toByte(), 221.toByte(), 222.toByte(), 199.toByte(), 223.toByte(), 224.toByte(), 225.toByte(),
        226.toByte(), 227.toByte(),
        0.toByte(), 107.toByte(), 91.toByte(), 106.toByte(), 119.toByte(), 95.toByte(), 100.toByte(), 82.toByte(),
        34.toByte(), 33.toByte(), 121.toByte(), 133.toByte(), 23.toByte(), 53.toByte(), 40.toByte(),
        31.toByte(),
        47.toByte(), 35.toByte(), 51.toByte(), 68.toByte(), 72.toByte(), 76.toByte(), 78.toByte(), 87.toByte(),
        77.toByte(), 55.toByte(), 29.toByte(), 60.toByte(), 101.toByte(), 71.toByte(), 81.toByte(),
        129.toByte(),
        135.toByte(), 64.toByte(), 98.toByte(), 61.toByte(), 85.toByte(), 66.toByte(), 93.toByte(), 94.toByte(),
        105.toByte(), 62.toByte(), 113.toByte(), 118.toByte(), 84.toByte(), 79.toByte(), 70.toByte(),
        88.toByte(),
        69.toByte(), 143.toByte(), 80.toByte(), 63.toByte(), 67.toByte(), 99.toByte(), 115.toByte(), 112.toByte(),
        132.toByte(), 122.toByte(), 127.toByte(), 108.toByte(), 117.toByte(), 109.toByte(), 141.toByte(),
        32.toByte(),
        104.toByte(), 6.toByte(), 39.toByte(), 17.toByte(), 19.toByte(), 2.toByte(), 28.toByte(), 37.toByte(),
        25.toByte(), 8.toByte(), 120.toByte(), 75.toByte(), 14.toByte(), 22.toByte(), 9.toByte(), 11.toByte(),
        24.toByte(), 116.toByte(), 10.toByte(), 12.toByte(), 4.toByte(), 20.toByte(), 54.toByte(), 59.toByte(),
        15.toByte(), 41.toByte(), 103.toByte(), 73.toByte(), 50.toByte(), 74.toByte(), 128.toByte(),
        228.toByte(),
        43.toByte(), 44.toByte(), 45.toByte(), 42.toByte(), 27.toByte(), 58.toByte(), 86.toByte(), 96.toByte(),
        124.toByte(), 83.toByte(), 150.toByte(), 175.toByte(), 157.toByte(), 159.toByte(), 158.toByte(),
        174.toByte(),
        48.toByte(), 110.toByte(), 139.toByte(), 140.toByte(), 111.toByte(), 125.toByte(), 102.toByte(),
        134.toByte(), 148.toByte(), 123.toByte(), 146.toByte(), 145.toByte(), 131.toByte(), 144.toByte(),
        173.toByte(), 147.toByte(),
        137.toByte(), 114.toByte(), 142.toByte(), 126.toByte(), 136.toByte(), 130.toByte(), 90.toByte(),
        138.toByte(), 163.toByte(), 149.toByte(), 172.toByte(), 183.toByte(), 156.toByte(), 162.toByte(),
        190.toByte(), 176.toByte(),
        89.toByte(), 18.toByte(), 92.toByte(), 56.toByte(), 65.toByte(), 30.toByte(), 46.toByte(), 52.toByte(),
        57.toByte(), 38.toByte(), 36.toByte(), 49.toByte(), 21.toByte(), 7.toByte(), 97.toByte(), 16.toByte(),
        0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
        0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
        0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
        0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
        0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
        0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
        0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
        0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
    )

    private val CASE_FOLDED_BYTE_RARITY = byteArrayOf(
        200.toByte(), 203.toByte(), 204.toByte(), 205.toByte(), 206.toByte(), 207.toByte(), 208.toByte(),
        209.toByte(), 210.toByte(), 152.toByte(), 13.toByte(), 189.toByte(), 188.toByte(), 26.toByte(),
        211.toByte(), 212.toByte(),
        213.toByte(), 214.toByte(), 215.toByte(), 216.toByte(), 217.toByte(), 218.toByte(), 219.toByte(),
        220.toByte(), 221.toByte(), 222.toByte(), 199.toByte(), 223.toByte(), 224.toByte(), 225.toByte(),
        226.toByte(), 227.toByte(),
        0.toByte(), 107.toByte(), 91.toByte(), 106.toByte(), 119.toByte(), 95.toByte(), 100.toByte(), 82.toByte(),
        34.toByte(), 33.toByte(), 121.toByte(), 133.toByte(), 23.toByte(), 53.toByte(), 40.toByte(),
        31.toByte(),
        47.toByte(), 35.toByte(), 51.toByte(), 68.toByte(), 72.toByte(), 76.toByte(), 78.toByte(), 87.toByte(),
        77.toByte(), 55.toByte(), 29.toByte(), 60.toByte(), 101.toByte(), 71.toByte(), 81.toByte(),
        129.toByte(),
        135.toByte(), 6.toByte(), 39.toByte(), 17.toByte(), 19.toByte(), 2.toByte(), 28.toByte(), 37.toByte(),
        25.toByte(), 8.toByte(), 113.toByte(), 75.toByte(), 14.toByte(), 22.toByte(), 9.toByte(), 11.toByte(),
        24.toByte(), 116.toByte(), 10.toByte(), 12.toByte(), 4.toByte(), 20.toByte(), 54.toByte(), 59.toByte(),
        15.toByte(), 41.toByte(), 103.toByte(), 108.toByte(), 117.toByte(), 109.toByte(), 141.toByte(),
        32.toByte(),
        104.toByte(), 6.toByte(), 39.toByte(), 17.toByte(), 19.toByte(), 2.toByte(), 28.toByte(), 37.toByte(),
        25.toByte(), 8.toByte(), 113.toByte(), 75.toByte(), 14.toByte(), 22.toByte(), 9.toByte(), 11.toByte(),
        24.toByte(), 116.toByte(), 10.toByte(), 12.toByte(), 4.toByte(), 20.toByte(), 54.toByte(), 59.toByte(),
        15.toByte(), 41.toByte(), 103.toByte(), 73.toByte(), 50.toByte(), 74.toByte(), 128.toByte(),
        228.toByte(),
        43.toByte(), 44.toByte(), 45.toByte(), 42.toByte(), 27.toByte(), 58.toByte(), 86.toByte(), 96.toByte(),
        124.toByte(), 83.toByte(), 150.toByte(), 175.toByte(), 157.toByte(), 159.toByte(), 158.toByte(),
        174.toByte(),
        48.toByte(), 110.toByte(), 139.toByte(), 140.toByte(), 111.toByte(), 125.toByte(), 102.toByte(),
        134.toByte(), 148.toByte(), 123.toByte(), 146.toByte(), 145.toByte(), 131.toByte(), 144.toByte(),
        173.toByte(), 147.toByte(),
        137.toByte(), 114.toByte(), 142.toByte(), 126.toByte(), 136.toByte(), 130.toByte(), 90.toByte(),
        138.toByte(), 163.toByte(), 149.toByte(), 172.toByte(), 183.toByte(), 156.toByte(), 162.toByte(),
        190.toByte(), 176.toByte(),
        89.toByte(), 18.toByte(), 92.toByte(), 56.toByte(), 65.toByte(), 30.toByte(), 46.toByte(), 52.toByte(),
        57.toByte(), 38.toByte(), 36.toByte(), 49.toByte(), 21.toByte(), 7.toByte(), 97.toByte(), 16.toByte(),
        0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
        0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
        0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
        0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
        0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
        0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
        0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
        0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
    )

    /**
     * Returns the exact byte frequency rank for an ASCII or UTF-8 byte (higher = rarer).
     * 
     * 
     * Distinguishes uppercase and lowercase frequencies for exact matching.
     */
    fun exactByteRarity(c: Int): Int {
        return if (c >= 0 && c < 256) (EXACT_BYTE_RARITY[c].toInt() and 0xFF) else 255
    }

    /**
     * Returns the case-folded byte frequency rank for an ASCII or UTF-8 byte (higher = rarer).
     * 
     * 
     * Assigns equal rank to `'a'` and `'A'` based on their combined frequency.
     */
    fun caseFoldedByteRarity(c: Int): Int {
        return if (c >= 0 && c < 256) (CASE_FOLDED_BYTE_RARITY[c].toInt() and 0xFF) else 255
    }

    /**
     * Returns the byte frequency rank for an ASCII or UTF-8 byte (higher = rarer).
     * 
     * @param c character or byte code point
     * @param caseFolded `true` to use case-folded combined letter ranks
     */
    fun byteRarity(c: Int, caseFolded: Boolean): Int {
        return if (caseFolded) caseFoldedByteRarity(c) else exactByteRarity(c)
    }

    /** Returns the exact-case byte frequency rank for an ASCII or UTF-8 byte (higher = rarer).  */
    fun byteRarity(c: Int): Int {
        return exactByteRarity(c)
    }

    /**
     * Returns a selective interior byte offset for an exact UTF-8 literal, or `-1` when the
     * existing endpoint filter is preferable. A substantial rank advantage avoids changing the search
     * path for literals whose interior bytes are only marginally rarer than their endpoints.
     */
    fun rarestUtf8LiteralByteOffset(literal: ByteArray?): Int {
        if (literal == null || literal.size < 3) {
            return -1
        }
        val endpointRank =
            Math.max(
                exactByteRarity(literal[0].toInt() and 0xFF),
                exactByteRarity(literal[literal.size - 1].toInt() and 0xFF)
            )
        var bestOffset = -1
        var bestRank =
            Math.max(MIN_LITERAL_BYTE_RARITY - 1, endpointRank + MIN_LITERAL_RARITY_ADVANTAGE - 1)
        for (offset in 1..<literal.size - 1) {
            val rank = exactByteRarity(literal[offset].toInt() and 0xFF)
            if (rank > bestRank) {
                bestRank = rank
                bestOffset = offset
            }
        }
        return bestOffset
    }

    private fun characterRarity(c: Int, caseFolded: Boolean): Int {
        return if (c >= 0 && c < 128) byteRarity(c, caseFolded) else 255
    }

    /**
     * Returns the offset of the rarest ASCII character in the prefix (up to `prefixLen`),
     * optionally applying case-folded frequency ratings. Only characters strictly within the ASCII
     * range (`c < 128`) are considered as candidate broadcast anchors. If all characters in the
     * evaluated prefix are non-ASCII or `prefixLen <= 0`, returns 0.
     * 
     * @param prefix the character sequence to scan
     * @param prefixLen length of prefix to evaluate
     * @param caseFolded `true` for case-insensitive matching
     * @return 0-based offset of rarest ASCII character
     */
    /**
     * Returns the offset of the rarest ASCII character in the prefix (up to `prefixLen`). If
     * all characters have identical rank or length is 0, returns 0.
     */
    @JvmOverloads
    fun rarestAsciiOffset(prefix: CharSequence, prefixLen: Int, caseFolded: Boolean = false): Int {
        var bestOffset = 0
        var maxRank = -1
        for (i in 0..<prefixLen) {
            val c = prefix[i].code
            if (c < 128) {
                val rank = byteRarity(c, caseFolded)
                if (rank > maxRank) {
                    maxRank = rank
                    bestOffset = i
                }
            }
        }
        return bestOffset
    }

    /**
     * Returns the two rarest ASCII characters in the prefix (up to prefixLen) with offset1 < offset2.
     * Returns null if prefixLen < 2 or if any character in the prefix is non-ASCII.
     */
    fun rarestAsciiPairIgnoreCase(prefix: CharSequence, prefixLen: Int): AsciiPair? {
        if (prefixLen < 2) {
            return null
        }
        for (i in 0..<prefixLen) {
            if (prefix[i].code > 127) {
                return null
            }
        }
        var best1 = 0
        var maxRank1 = caseFoldedByteRarity(prefix[0].code)
        for (i in 1..<prefixLen) {
            val c = prefix[i].code
            val rank = caseFoldedByteRarity(c)
            if (rank > maxRank1) {
                maxRank1 = rank
                best1 = i
            }
        }
        var best2 = -1
        var maxRank2 = -1
        for (i in 0..<prefixLen) {
            if (i == best1) {
                continue
            }
            val c = prefix[i].code
            val rank = caseFoldedByteRarity(c)
            if (rank > maxRank2) {
                maxRank2 = rank
                best2 = i
            }
        }
        val offset1 = Math.min(best1, best2)
        val offset2 = Math.max(best1, best2)
        val c1: Char = prefix[offset1]
        val c2: Char = prefix[offset2]
        return AsciiPair(
            offset1,
            Ascii.toLowerCase(c1).code.toByte(),
            Ascii.toUpperCase(c1).code.toByte(),
            offset2,
            Ascii.toLowerCase(c2).code.toByte(),
            Ascii.toUpperCase(c2).code.toByte()
        )
    }

    /**
     * Computes a selectivity score for a literal string, optionally using case-folded ratings.
     * 
     * @param s the literal candidate sequence
     * @param caseFolded `true` if matching will be case-insensitive
     * @return higher score indicates a more selective literal
     */
    /**
     * Computes a selectivity score for a literal string. Combines string length with individual
     * character rarity.
     */
    @JvmOverloads
    fun literalSelectivityScore(s: CharSequence?, caseFolded: Boolean = false): Int {
        if (s == null || s.isEmpty()) {
            return 0
        }
        var score = 0
        var maxCharRarity = 0
        for (i in 0..<s.length) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            val r = characterRarity(s[i].code, caseFolded)
            score += r + 1
            if (r > maxCharRarity) {
                maxCharRarity = r
            }
        }
        return score + maxCharRarity
    }

    /**
     * Maximum rarity rank considered unselective ("poisonous") for single-character start prefilters.
     * 
     * 
     * Single-character candidates with rarity  6 (such as spaces and high-frequency letters
     * like `'e'`, `'t'`, `'a'`) trigger excessive false-positive candidate
     * verification wakeups, making them counter-productive as standalone start accelerators.
     */
    const val POISONOUS_ANCHOR_MAX_RARITY: Int = 6

    /**
     * Returns `true` if the given literal candidate is considered a poisonous single-character
     * anchor that should not be attached as a standalone start prefilter.
     */
    fun isPoisonousAnchor(s: CharSequence?): Boolean {
        return isPoisonousAnchor(s, false)
    }

    /** Returns whether a single-character literal is poisonous in the requested matching mode.  */
    fun isPoisonousAnchor(s: CharSequence?, caseFolded: Boolean): Boolean {
        return s != null && s.length == 1 && characterRarity(
            s[0].code,
            caseFolded
        ) <= POISONOUS_ANCHOR_MAX_RARITY
    }

    /**
     * Returns whether scanning for exact-case `startLiteral` before exact-case `rejectLiteral` avoids trading a fast rare-byte reject scan for a substantially weaker or
     * unanchored start scan.
     */
    fun startLiteralCompetitiveWithReject(startLiteral: String?, rejectLiteral: String?): Boolean {
        val startRarity = maxExactAsciiAnchorRarity(startLiteral)
        val rejectRarity = maxExactAsciiAnchorRarity(rejectLiteral)
        if (rejectRarity > POISONOUS_ANCHOR_MAX_RARITY && startRarity <= POISONOUS_ANCHOR_MAX_RARITY) {
            return false
        }
        return startRarity + MIN_LITERAL_RARITY_ADVANTAGE >= rejectRarity
    }

    private fun maxExactAsciiAnchorRarity(literal: String?): Int {
        if (literal == null || literal.isEmpty()
            || literal.length > StringLiteralSearch.MAX_ANCHORED_LITERAL_LENGTH
        ) {
            return -1
        }
        var maxRank = -1
        for (i in 0..<literal.length) {
            val c: Char = literal[i]
            if (c.code < 128) {
                val rank = exactByteRarity(c.code)
                if (rank > maxRank) {
                    maxRank = rank
                }
            }
        }
        return maxRank
    }

    /**
     * The most non-ASCII members a class may have and still be scored by its ASCII members, matching
     * the [CharClassScanInfo.UnicodeSmallSet] cap.
     */
    private const val MAX_UNSCORED_NON_ASCII_MEMBERS = 2

    /**
     * Computes an empirical frequency score for the ASCII members of a character class (lower = rarer
     * / more selective), or `0` if the class cannot be scored.
     * 
     * 
     * Each ASCII character's occurrence frequency is modeled as `256 - exactByteRarity(c)`,
     * so smaller and rarer character classes have lower scores and are preferred for whole-input
     * rejection. Non-ASCII code points are not scored because the byte-rarity table has no data
     * beyond ASCII and non-ASCII frequency depends on document language. A class with at most {@value
     * * #MAX_UNSCORED_NON_ASCII_MEMBERS} non-ASCII members, such as `[\[\uFF3B]`, is scored by
     * its ASCII members alone. A class with no ASCII members or with more non-ASCII members, such as
     * `[\-\u4E00-\u9FFF]`, returns `0` so callers fall back to rune count (see `MultiAnchorCompiler`); scoring the latter as `-` would claim it is rare on text in the
     * language it covers.
     */
    fun charClassFrequencyScore(cc: CharClass?): Long {
        if (cc == null || cc.isEmpty()) {
            return 0
        }
        val numRanges = cc.numRanges()
        val ranges = IntArray(numRanges * 2)
        for (i in 0..<numRanges) {
            ranges[2 * i] = cc.lo(i)
            ranges[2 * i + 1] = cc.hi(i)
        }
        return asciiMemberScore(ranges)
    }

    /** Like [.charClassFrequencyScore], for a compiled scan class.  */
    fun charClassFrequencyScore(scanInfo: CharClassScanInfo?): Long {
        if (scanInfo == null) {
            return 0
        }
        val ranges = scanInfo.ranges
        if (ranges == null || ranges.size == 0) {
            return 0
        }
        return asciiMemberScore(ranges)
    }

    /** Scores sorted `[lo, hi]` pairs; see [.charClassFrequencyScore].  */
    private fun asciiMemberScore(ranges: IntArray): Long {
        var totalWeight: Long = 0
        var nonAsciiMembers: Long = 0
        var i = 0
        while (i < ranges.size) {
            val lo = ranges[i]
            val hi = ranges[i + 1]
            if (lo < 128) {
                totalWeight += asciiRangeFrequencyWeight(lo, Math.min(127, hi))
            }
            if (hi >= 128) {
                nonAsciiMembers += hi - Math.max(128, lo) + 1L
                if (nonAsciiMembers > MAX_UNSCORED_NON_ASCII_MEMBERS) {
                    return 0
                }
            }
            i += 2
        }
        return totalWeight
    }

    private fun asciiRangeFrequencyWeight(lo: Int, hi: Int): Long {
        var weight: Long = 0
        for (ch in lo..hi) {
            weight += (256 - exactByteRarity(ch)).toLong()
        }
        return weight
    }

    @JvmRecord
    internal data class AsciiPair(val offset1: Int, val low1: Byte, val high1: Byte, val offset2: Int, val low2: Byte, val high2: Byte)
}
