// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.regex.PatternSyntaxException
import kotlin.math.min

/**
 * Random/fuzz testing of SafeRE against `java.util.regex`. Ported from RE2 C++ `random_test.cc`.
 * 
 * 
 * Generates random regular expressions and random strings, then verifies that SafeRE and `java.util.regex` agree on match/no-match results and matched text. Uses fixed seeds for
 * reproducibility.
 */
@DisabledForCrosscheck("random differential fuzzing already compares SafeRE with java.util.regex")
@DisplayName("Random Fuzz Tests (ported from RE2 C++ random_test.cc)")
internal class RandomTest {
    /** Small egrep-style patterns with literal alphabet.  */
    @Test
    fun smallEgrepLiterals() {
        randomTest(5, arrayOf<String>("a", "b", "c", "."), EGREP_OPS, 15, "abc")
    }

    /** Bigger egrep-style patterns.  */
    @Test
    fun bigEgrepLiterals() {
        randomTest(8, arrayOf<String>("a", "b", "c", "."), EGREP_OPS, 15, "abc")
    }

    /** Patterns with capturing groups.  */
    @Test
    fun smallEgrepCaptures() {
        randomTest(5, arrayOf<String>("a", "(b)", "."), EGREP_OPS, 15, "abc")
    }

    /** Complex patterns with character classes, anchors, and quantifiers.  */
    @Test
    fun complicated() {
        val atoms = arrayOf<String>(
            ".",
            "\\d",
            "\\D",
            "\\s",
            "\\S",
            "\\w",
            "\\W",
            "[^\\n]",
            "[^\\n\\r]",
            "a",
            "(a)",
            "b",
            "c",
            "-",
            "\\\\"
        )
        val ops = arrayOf<String>(
            "%s%s", "%s|%s", "%s*", "%s*?", "%s+", "%s+?", "%s?", "%s??", "%s{0}", "%s{0,}", "%s{1}",
            "%s{1,}", "%s{0,1}", "%s{0,2}", "%s{1,2}", "%s{2}", "%s{2,}", "%s{3,4}"
        )
        // Every Java line terminator appears, so that a gap which wrongly treats one as a barrier
        // diverges from java.util.regex: \n, \r, \u0085 (NEL) and \u2028 (LS).
        randomTest(8, atoms, ops, 20, "abc123\t\n\r\u0085\u2028")
    }

    /**
     * The same style of pattern over inputs long enough to reach the wide scan kernels.
     * 
     * 
     * Every other method here caps input at 20 characters, which is too short for any wide kernel
     * to be dispatched: `IncubatorVectorScanProvider` requires a 1024-byte window for CLASS,
     * IGNORE_CASE and TEDDY, and `ByteSwarScan.MIN_FILTER_LENGTH` is 64. Measured with [ ] over the UTF-8 domain, the short corpus produces 1,066 dispatches across only `BYTE` and `PAIR`, with a maximum window of 30 bytes and *zero* on the vector path.
     * This arm produces 41,960 dispatches with a maximum window of 2,313 bytes, reaching `CLASS` and `MULTI_LITERAL` as well, and takes the vector path 18,218 times. `TEDDY`
     * and `IGNORE_CASE` are still unreached; those need case-insensitive and
     * multi-literal-alternation patterns that this generator does not produce.
     * 
     * 
     * Fewer, larger strings keep the runtime in line with the other methods.
     */
    @Test
    fun complicatedLongInput() {
        val atoms = arrayOf<String>(".", "\\d", "\\s", "\\w", "[^\\n]", "a", "(a)", "b", "c", "-")
        val ops = arrayOf<String>("%s%s", "%s|%s", "%s*", "%s+", "%s?", "%s{2}", "%s{1,2}", "%s{3,4}")
        randomTest(6, atoms, ops, 2048, "abc123\t\n\r\u0085\u2028", 8)
    }

    // -----------------------------------------------------------------------
    // String versus UTF-8 domain comparison
    // -----------------------------------------------------------------------
    @Test
    fun domainComparisonDetectsDifferentFullMatchBounds() {
        val pattern = Pattern.compile("(a|ab)")
        val chars = pattern.matcher("ab")
        val bytes = pattern.matcher(Utf8Input.validated("a".toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(chars.matches()).isTrue()
        Assertions.assertThat(bytes.matches()).isTrue()
        Assertions.assertThat(compareMatchedGroups(0, byteOffsets("ab"), chars, bytes)).isNotNull()
    }

    companion object {
        private const val REGEXP_SEED = 404
        private const val STRING_SEED = 200
        private const val REGEXP_COUNT = 200
        private const val STRING_COUNT = 100

        // -----------------------------------------------------------------------
        // Egrep operators: concatenation, alternation, and quantifiers.
        // -----------------------------------------------------------------------
        private val EGREP_OPS = arrayOf<String>(
            "%s%s", "%s|%s", "%s*", "%s*?", "%s+", "%s+?", "%s?", "%s??"
        )

        // -----------------------------------------------------------------------
        // Core test logic
        // -----------------------------------------------------------------------
        private fun randomTest(
            maxOps: Int,
            atoms: Array<String>,
            ops: Array<String>,
            maxStrLen: Int,
            strAlphabet: String,
            stringCount: Int = STRING_COUNT
        ) {
            val regexpRng = Random(REGEXP_SEED.toLong())
            val stringRng = Random(STRING_SEED.toLong())

            // Generate random strings to test against.
            val testStrings: MutableList<String> = ArrayList<String>()
            testStrings.add("") // always include empty string
            val alphaChars = strAlphabet.toCharArray()
            for (i in 0..<stringCount) {
                val len = stringRng.nextInt(maxStrLen + 1)
                val sb = StringBuilder(len)
                for (j in 0..<len) {
                    sb.append(alphaChars[stringRng.nextInt(alphaChars.size)])
                }
                testStrings.add(sb.toString())
            }

            var totalTests = 0
            var skipped = 0
            val failures: MutableList<String> = ArrayList<String>()

            for (i in 0..<REGEXP_COUNT) {
                val pattern: String = generateRandomRegexp(regexpRng, atoms, ops, maxOps)

                // Try to compile with both engines.
                val saferePattern: org.safere.Pattern?
                val jdkPattern: java.util.regex.Pattern?
                try {
                    saferePattern = org.safere.Pattern.compile(pattern)
                } catch (e: PatternSyntaxException) {
                    skipped++
                    continue
                }
                try {
                    jdkPattern = java.util.regex.Pattern.compile(pattern)
                } catch (e: PatternSyntaxException) {
                    skipped++
                    continue
                }

                // Test each string.
                for (text in testStrings) {
                    totalTests++

                    // Compare find() results.
                    val safereMatcher = saferePattern.matcher(text)
                    val jdkMatcher = jdkPattern.matcher(text)

                    val safereFound = safereMatcher.find()
                    val jdkFound = jdkMatcher.find()

                    if (safereFound != jdkFound) {
                        failures.add(
                            String.format(
                                "find() disagree: pat=\"%s\" text=\"%s\" safere=%b jdk=%b",
                                escape(pattern), escape(text), safereFound, jdkFound
                            )
                        )
                        if (failures.size >= 50) {
                            break
                        }
                        continue
                    }

                    if (safereFound) {
                        val safereGroup = safereMatcher.group()!!
                        val jdkGroup = jdkMatcher.group()
                        if (safereGroup != jdkGroup) {
                            failures.add(
                                String.format(
                                    "find() group disagree: pat=\"%s\" text=\"%s\" safere=\"%s\" jdk=\"%s\"",
                                    escape(pattern), escape(text), escape(safereGroup), escape(jdkGroup)
                                )
                            )
                            if (failures.size >= 50) {
                                break
                            }
                        }
                    }

                    // Compare matches() results.
                    totalTests++
                    val safereMatches = saferePattern.matcher(text).matches()
                    val jdkMatches = jdkPattern.matcher(text).matches()
                    if (safereMatches != jdkMatches) {
                        failures.add(
                            String.format(
                                "matches() disagree: pat=\"%s\" text=\"%s\" safere=%b jdk=%b",
                                escape(pattern), escape(text), safereMatches, jdkMatches
                            )
                        )
                        if (failures.size >= 50) {
                            break
                        }
                    }

                    // Run the same pattern and text through the UTF-8 domain. The String and UTF-8 paths
                    // are mirrored implementations -- StringStartAccelerator/Utf8StartAccelerator,
                    // StringInputScanner/Utf8InputScanner, and both RejectPrefilter domains -- and the
                    // contract in docs/UTF8.md is that only the input representation and match coordinates
                    // change. The String side is the oracle here rather than the JDK, because the JDK has
                    // no byte-domain API; the loop above has already pinned the String side to the JDK.
                    totalTests++
                    val utf8Mismatch: String? = compareUtf8Domain(saferePattern, text)
                    if (utf8Mismatch != null) {
                        failures.add(
                            String.format(
                                "String/UTF-8 disagree: pat=\"%s\" text=\"%s\" %s",
                                escape(pattern), escape(text), utf8Mismatch
                            )
                        )
                        if (failures.size >= 50) {
                            break
                        }
                    }
                }

                if (failures.size >= 50) {
                    break
                }
            }

            System.err.printf(
                "Random: %,d patterns, %,d tests, %,d skipped, %,d failures%n",
                REGEXP_COUNT, totalTests, skipped, failures.size
            )

            if (!failures.isEmpty()) {
                val show = min(failures.size, 20)
                val sb = StringBuilder()
                sb.append(String.format("%d failures (showing first %d):%n", failures.size, show))
                for (i in 0..<show) {
                    sb.append("  ").append(failures.get(i)).append("\n")
                }
                Assertions.fail<Any?>(sb.toString())
            }

            Assertions.assertThat(totalTests).`as`("Should have run a meaningful number of tests").isGreaterThan(1000)
        }

        /**
         * Runs `pattern` over `text` in both the String and UTF-8 domains and returns a
         * description of the first disagreement, or `null` if they agree.
         * 
         * 
         * Compares the whole `find()` sequence and every capture group, not just the first
         * match: a byte-domain implementation can agree on the first match and still drift on iteration,
         * on how it advances past an empty match, or on group bounds.
         */
        private fun compareUtf8Domain(pattern: org.safere.Pattern, text: String): String? {
            val byteOffsets: IntArray = byteOffsets(text)
            val encoded = text.toByteArray(StandardCharsets.UTF_8)

            // Each operation needs fresh matchers because it may select different bounds and captures.
            val fullChars = pattern.matcher(text)
            val fullBytes = pattern.matcher(Utf8Input.validated(encoded))
            val charsMatch = fullChars.matches()
            val bytesMatch = fullBytes.matches()
            if (charsMatch != bytesMatch) {
                return "matches() differs"
            }
            if (charsMatch) {
                val mismatch: String? = compareMatchedGroups(0, byteOffsets, fullChars, fullBytes)
                if (mismatch != null) {
                    return "matches() " + mismatch
                }
            }

            val prefixChars = pattern.matcher(text)
            val prefixBytes = pattern.matcher(Utf8Input.validated(encoded))
            val charsLookingAt = prefixChars.lookingAt()
            val bytesLookingAt = prefixBytes.lookingAt()
            if (charsLookingAt != bytesLookingAt) {
                return "lookingAt() differs"
            }
            if (charsLookingAt) {
                val mismatch: String? = compareMatchedGroups(0, byteOffsets, prefixChars, prefixBytes)
                if (mismatch != null) {
                    return "lookingAt() " + mismatch
                }
            }

            val chars = pattern.matcher(text)
            val bytes = pattern.matcher(Utf8Input.validated(encoded))

            var n = 0
            while (true) {
                val charsFound = chars.find()
                val bytesFound = bytes.find()
                if (charsFound != bytesFound) {
                    return String.format("find() #%d: string=%b utf8=%b", n, charsFound, bytesFound)
                }
                if (!charsFound) {
                    return null
                }
                val mismatch: String? = compareMatchedGroups(n, byteOffsets, chars, bytes)
                if (mismatch != null) {
                    return mismatch
                }
                n++
            }
        }

        private fun compareMatchedGroups(
            match: Int, byteOffsets: IntArray, chars: Matcher, bytes: Utf8Matcher
        ): String? {
            if (chars.groupCount() != bytes.groupCount()) {
                return String.format(
                    "groupCount() #%d: string=%d utf8=%d", match, chars.groupCount(), bytes.groupCount()
                )
            }
            for (group in 0..chars.groupCount()) {
                val mismatch: String? =
                    compareBounds(
                        match,
                        group,
                        byteOffsets,
                        chars.start(group),
                        chars.end(group),
                        bytes.start(group),
                        bytes.end(group)
                    )
                if (mismatch != null) {
                    return mismatch
                }
            }
            return null
        }

        private fun compareBounds(
            match: Int,
            group: Int,
            byteOffsets: IntArray,
            charStart: Int,
            charEnd: Int,
            byteStart: Int,
            byteEnd: Int
        ): String? {
            // An unset group must be unset in both domains.
            if (charStart < 0 || charEnd < 0) {
                if (byteStart >= 0 || byteEnd >= 0) {
                    return String.format(
                        "match #%d group %d: string unset, utf8=[%d,%d)", match, group, byteStart, byteEnd
                    )
                }
                return null
            }
            val expectedStart = byteOffsets[charStart]
            val expectedEnd = byteOffsets[charEnd]
            if (byteStart != expectedStart || byteEnd != expectedEnd) {
                return String.format(
                    "match #%d group %d: string=[%d,%d) -> expected utf8=[%d,%d) but got [%d,%d)",
                    match, group, charStart, charEnd, expectedStart, expectedEnd, byteStart, byteEnd
                )
            }
            return null
        }

        /**
         * Maps each UTF-16 index in `text` to the number of UTF-8 bytes preceding it, so String
         * coordinates can be compared against byte coordinates.
         * 
         * 
         * Both halves of a surrogate pair map to the pair's starting byte offset; there is no legal
         * match boundary between them. Unpaired surrogates cannot occur here because every alphabet these
         * tests draw from is BMP-only.
         */
        private fun byteOffsets(text: String): IntArray {
            val length = text.length
            val offsets = IntArray(length + 1)
            var total = 0
            var i = 0
            while (i < length) {
                val codePoint = text.codePointAt(i)
                val charCount = Character.charCount(codePoint)
                offsets[i] = total
                if (charCount == 2) {
                    offsets[i + 1] = total
                }
                total += utf8Length(codePoint)
                i += charCount
            }
            offsets[length] = total
            return offsets
        }

        private fun utf8Length(codePoint: Int): Int {
            if (codePoint < 0x80) {
                return 1
            }
            if (codePoint < 0x800) {
                return 2
            }
            if (codePoint < 0x10000) {
                return 3
            }
            return 4
        }

        // -----------------------------------------------------------------------
        // Random regexp generation
        // -----------------------------------------------------------------------
        private fun generateRandomRegexp(rng: Random, atoms: Array<String>, ops: Array<String>, maxOps: Int): String {
            val numOps = rng.nextInt(maxOps) + 1
            // Start with a random atom.
            var expr = atoms[rng.nextInt(atoms.size)]

            for (i in 0..<numOps) {
                val op = ops[rng.nextInt(ops.size)]
                val placeholders: Int = countPlaceholders(op)
                if (placeholders == 1) {
                    expr = String.format(op, expr)
                } else if (placeholders == 2) {
                    val other: String = atoms[rng.nextInt(atoms.size)]
                    // Randomly decide which side gets the built-up expression.
                    if (rng.nextBoolean()) {
                        expr = String.format(op, expr, other)
                    } else {
                        expr = String.format(op, other, expr)
                    }
                }
            }
            return expr
        }

        private fun countPlaceholders(op: String): Int {
            var count = 0
            var idx = 0
            while ((op.indexOf("%s", idx).also { idx = it }) >= 0) {
                count++
                idx += 2
            }
            return count
        }

        private fun escape(s: String): String {
            val sb = StringBuilder()
            for (i in 0..<s.length) {
                val c = s.get(i)
                if (c.code >= 0x20 && c.code < 0x7F) {
                    sb.append(c)
                } else {
                    sb.append(String.format("\\x%02x", c.code))
                }
            }
            return sb.toString()
        }
    }
}
