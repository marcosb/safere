// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.lang.AutoCloseable
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import java.util.zip.GZIPInputStream
import kotlin.math.min

/**
 * Exhaustive tests parsed from RE2's `re2-exhaustive.txt.gz` test data file.
 * 
 * 
 * This file contains thousands of systematically generated (pattern, text, expected) tuples
 * covering all operator combinations. For each (regexp, string) pair, four semicolon-separated
 * result fields are given:
 * 
 * 
 *  1. Full match, first-match semantics
 *  1. Partial match (find), first-match semantics
 *  1. Full match, longest-match semantics
 *  1. Partial match (find), longest-match semantics
 * 
 * 
 * 
 * We test fields 0 and 1 (first-match semantics), verifying both match/no-match and submatch
 * group positions. Fields 2 and 3 (longest-match) are skipped because SafeRE uses leftmost-first
 * semantics.
 */
@DisplayName("RE2 Exhaustive Tests (from re2-exhaustive.txt.gz)")
internal class RE2ExhaustiveTest {
    @Test
    @DisabledForCrosscheck("checks raw engines and the approved earlier-capture oracle boundary")
    fun earlierCaptureAllowanceRequiresIndependentReferenceAgreement() {
        val pattern = "(?:(?:(a){1,}){2})"
        val jdk = Pattern.compile(pattern).matcher("aaa")
        Assertions.assertThat(jdk.matches()).isTrue()
        val jdkRetainsEarlierCapture = jdk.start(1) == 1 && jdk.end(1) == 2
        Assertions.assertThat(
            hasKnownJdkEarlierCaptureDivergence(
                pattern, "aaa", "matches", arrayOf<IntArray?>(intArrayOf(0, 3), intArrayOf(2, 3))
            )
        )
            .isEqualTo(jdkRetainsEarlierCapture)
        // Neither an incorrect reference capture nor an incorrect whole-match span can be exempted.
        Assertions.assertThat(
            hasKnownJdkEarlierCaptureDivergence(
                pattern, "aaa", "matches", arrayOf<IntArray?>(intArrayOf(0, 3), intArrayOf(1, 2))
            )
        )
            .isFalse()
        Assertions.assertThat(
            hasKnownJdkEarlierCaptureDivergence(
                pattern, "aaa", "matches", arrayOf<IntArray?>(intArrayOf(0, 2), intArrayOf(2, 3))
            )
        )
            .isFalse()
        Assertions.assertThat(hasKnownJdkEarlierCaptureDivergence(pattern, "aaa", "matches", null)).isFalse()
        Assertions.assertThat(
            hasKnownJdkEarlierCaptureDivergence(
                "(a){2}", "aa", "matches", arrayOf<IntArray?>(intArrayOf(0, 2), intArrayOf(1, 2))
            )
        )
            .isFalse()
        Assertions.assertThat(
            hasKnownJdkEarlierCaptureDivergence(
                "(a?){2}", "", "matches", arrayOf<IntArray?>(intArrayOf(0, 0), intArrayOf(0, 0))
            )
        )
            .isFalse()
    }

    @Test
    @Throws(IOException::class)
    fun testExhaustive() {
        val `is` = RE2ExhaustiveTest::class.java.getResourceAsStream("/re2-exhaustive.txt.gz")
        Assertions.assertThat(`is`).`as`("re2-exhaustive.txt.gz must be in test resources").isNotNull()

        var totalTests = 0
        var skipped = 0
        val failures: MutableList<String?> = ArrayList<String?>()

        UnixLineReader(
            InputStreamReader(GZIPInputStream(`is`), StandardCharsets.UTF_8)
        ).use { reader ->
            val strings: MutableList<String> = ArrayList<String>()
            var line: String?

            // Skip header to first "strings"
            while ((reader.readLine().also { line = it }) != null) {
                if (line == "strings") {
                    break
                }
            }
            outer@ while (line != null) {
                // Read strings section
                strings.clear()
                while ((reader.readLine().also { line = it }) != null) {
                    if (line == "regexps") {
                        break
                    }
                    if (line!!.startsWith("\"")) {
                        strings.add(unescapeString(line))
                    }
                }
                if (line == null) {
                    break
                }

                // Read regexps section
                while ((reader.readLine().also { line = it }) != null) {
                    if (line == "strings") {
                        break
                    }
                    if (!line!!.startsWith("\"")) {
                        continue
                    }

                    val pattern: String = unescapeString(line)

                    // Skip \C patterns (match-any-byte, not applicable to Java)
                    var skip = pattern.contains("\\C")

                    var compiledPat: org.safere.Pattern? = null
                    if (!skip) {
                        try {
                            compiledPat = org.safere.Pattern.compile(pattern)
                        } catch (e: PatternSyntaxException) {
                            skip = true
                        }
                    }

                    // Read one result line per test string
                    for (si in strings.indices) {
                        val resultLine = reader.readLine()
                        if (resultLine == null) {
                            line = null
                            break
                        }

                        if (skip) {
                            skipped++
                            continue
                        }

                        val fields: Array<String?> =
                            resultLine.split(";".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                        if (fields.size < 2) {
                            skipped++
                            continue
                        }

                        val text = strings.get(si)

                        // Skip \B on multibyte text: C++ RE2 sees byte boundaries within multi-byte
                        // characters, but Java operates on code points. Results differ by design.
                        if (pattern.contains("\\B") && !isSingleBytes(text)) {
                            skipped++
                            continue
                        }

                        // The generated crosscheck copy compares SafeRE against java.util.regex, but the
                        // exhaustive RE2 corpus includes known #52 cases where JDK leaks a capture from a
                        // failed start position. Keep the ordinary RE2 oracle coverage active, and skip only
                        // generated crosscheck cases whose raw engines prove this exact divergence.
                        if (isGeneratedCrosscheckRun
                            && hasKnownJdkFailedStartCaptureLeakageDivergence(pattern, text)
                        ) {
                            skipped++
                            continue
                        }

                        val fullExpected: Array<IntArray?>? = parseResult(fields[0]!!)
                        val findExpected: Array<IntArray?>? = parseResult(fields[1]!!)

                        // Test matches() against field 0 (full match, first-match semantics)
                        totalTests++
                        try {
                            val m = compiledPat!!.matcher(text)
                            val matched = m.matches()
                            if (matched != (fullExpected != null)) {
                                // Cross-check against JDK: RE2 expected values may differ for $ before
                                // trailing \n (RE2's $ = \z, but JDK/SafeRE's $ also matches before
                                // the final \n). If JDK agrees with SafeRE, this is a known difference.
                                if (jdkAgrees(pattern, text, "matches", matched, m)) {
                                    skipped++
                                } else {
                                    failures.add(
                                        String.format(
                                            "matches() pat=\"%s\" text=\"%s\": got %b, want %b",
                                            escape(pattern), escape(text), matched, fullExpected != null
                                        )
                                    )
                                }
                            } else if (matched && fullExpected != null) {
                                checkGroupsOrJdk(m, fullExpected, text, pattern, "matches", failures)
                            }
                        } catch (e: Exception) {
                            if (!isGeneratedEarlierCaptureDivergence(e, pattern, text, "matches", fullExpected)) {
                                failures.add(
                                    String.format(
                                        "matches() EXCEPTION pat=\"%s\" text=\"%s\": %s",
                                        escape(pattern), escape(text), e
                                    )
                                )
                            }
                        }

                        // Test find() against field 1 (partial match, first-match semantics)
                        totalTests++
                        try {
                            val m = compiledPat!!.matcher(text)
                            val found = m.find()
                            if (found != (findExpected != null)) {
                                if (jdkAgrees(pattern, text, "find", found, m)) {
                                    skipped++
                                } else {
                                    failures.add(
                                        String.format(
                                            "find() pat=\"%s\" text=\"%s\": got %b, want %b",
                                            escape(pattern), escape(text), found, findExpected != null
                                        )
                                    )
                                }
                            } else if (found && findExpected != null) {
                                checkGroupsOrJdk(m, findExpected, text, pattern, "find", failures)
                            }
                        } catch (e: Exception) {
                            if (!isGeneratedEarlierCaptureDivergence(e, pattern, text, "find", findExpected)) {
                                failures.add(
                                    String.format(
                                        "find() EXCEPTION pat=\"%s\" text=\"%s\": %s",
                                        escape(pattern), escape(text), e
                                    )
                                )
                            }
                        }

                        if (failures.size >= MAX_FAILURES) {
                            break@outer
                        }
                    }

                    if (line == null) {
                        break
                    }
                }

                if (line == null) {
                    break
                }
            }
        }
        System.err.printf(
            "RE2 Exhaustive: %,d tests, %,d skipped, %,d failures%n",
            totalTests, skipped, failures.size
        )

        if (!failures.isEmpty()) {
            val show = min(failures.size, 50)
            val sb = StringBuilder()
            sb.append(String.format("%d failures (showing first %d):%n", failures.size, show))
            for (i in 0..<show) {
                sb.append("  ").append(failures.get(i)).append("\n")
            }
            Assertions.fail<Any?>(sb.toString())
        }
    }

    /**
     * A line reader that uses only `\n` as the line terminator, not `\r` or `\r\n`.
     * This is necessary because the RE2 test data contains literal `\r` (0x0D) characters
     * inside quoted strings, and Java's `BufferedReader.readLine()` would incorrectly treat
     * those as line terminators.
     */
    private class UnixLineReader(private val `in`: Reader) : AutoCloseable {
        private val buf = CharArray(8192)
        private var pos = 0
        private var limit = 0

        @Throws(IOException::class)
        fun readLine(): String? {
            var sb: StringBuilder? = null
            while (true) {
                // Scan the buffer for a newline.
                for (i in pos..<limit) {
                    if (buf[i] == '\n') {
                        val line: String?
                        if (sb != null) {
                            sb.appendRange(buf, pos, pos + (i - pos))
                            line = sb.toString()
                        } else {
                            line = String(buf, pos, i - pos)
                        }
                        pos = i + 1
                        return line
                    }
                }
                // No newline found in buffer; accumulate and refill.
                if (sb == null) {
                    sb = StringBuilder()
                }
                sb.appendRange(buf, pos, pos + (limit - pos))
                pos = 0
                limit = `in`.read(buf, 0, buf.size)
                if (limit <= 0) {
                    return if (sb.length > 0) sb.toString() else null
                }
            }
        }

        @Throws(IOException::class)
        override fun close() {
            `in`.close()
        }
    }

    companion object {
        private const val MAX_FAILURES = 200

        /**
         * Cross-check SafeRE's result against JDK's java.util.regex when a mismatch with the RE2 expected
         * value is detected. Returns `true` if JDK agrees with SafeRE (known behavioral difference,
         * e.g., `$` matches before trailing `\n`).
         */
        private fun jdkAgrees(
            pattern: String, text: String, method: String, safeReResult: Boolean, safeReMatcher: Matcher
        ): Boolean {
            try {
                val jdkPat = Pattern.compile(pattern)
                val jdkMatcher = jdkPat.matcher(text)
                val jdkResult = if (method == "matches") jdkMatcher.matches() else jdkMatcher.find()
                if (jdkResult != safeReResult) {
                    return false
                }
                // Also check group(0) bounds if both found a match
                if (safeReResult && jdkResult) {
                    return safeReMatcher.start() == jdkMatcher.start()
                            && safeReMatcher.end() == jdkMatcher.end()
                }
                return true
            } catch (e: Exception) {
                return false // JDK can't compile or match; don't suppress the failure
            }
        }

        /**
         * Check submatch groups, falling back to JDK cross-validation when RE2 expected values disagree
         * with SafeRE. If JDK agrees with SafeRE's group positions, the mismatch is a known RE2
         * behavioral difference (not a bug).
         */
        private fun checkGroupsOrJdk(
            m: Matcher,
            expected: Array<IntArray?>,
            text: String,
            pattern: String,
            method: String,
            failures: MutableList<String?>
        ) {
            val numGroups = min(expected.size, m.groupCount() + 1)
            for (g in 0..<numGroups) {
                val expectStart: Int
                val expectEnd: Int
                if (expected[g]!![0] == -1 && expected[g]!![1] == -1) {
                    expectStart = -1
                    expectEnd = -1
                } else {
                    expectStart = utf8ByteToCharOffset(text, expected[g]!![0])
                    expectEnd = utf8ByteToCharOffset(text, expected[g]!![1])
                }

                val actualStart = m.start(g)
                val actualEnd = m.end(g)

                if (actualStart != expectStart || actualEnd != expectEnd) {
                    // Before reporting failure, cross-check against JDK
                    if (jdkAgreesOnGroups(pattern, text, method, m)) {
                        return  // JDK agrees with SafeRE; known RE2 difference
                    }
                    failures.add(
                        String.format(
                            "%s() group %d pat=\"%s\" text=\"%s\": got [%d,%d), want [%d,%d)",
                            method,
                            g,
                            escape(pattern),
                            escape(text),
                            actualStart,
                            actualEnd,
                            expectStart,
                            expectEnd
                        )
                    )
                    break
                }
            }
        }

        /** Check if JDK's group positions agree with SafeRE's for a given match.  */
        private fun jdkAgreesOnGroups(
            pattern: String, text: String, method: String, safeReMatcher: Matcher
        ): Boolean {
            try {
                val jdkPat = Pattern.compile(pattern)
                val jdkMatcher = jdkPat.matcher(text)
                val jdkResult = if (method == "matches") jdkMatcher.matches() else jdkMatcher.find()
                if (!jdkResult) {
                    return false
                }
                val numGroups = min(safeReMatcher.groupCount() + 1, jdkMatcher.groupCount() + 1)
                for (g in 0..<numGroups) {
                    if (safeReMatcher.start(g) != jdkMatcher.start(g)
                        || safeReMatcher.end(g) != jdkMatcher.end(g)
                    ) {
                        return false
                    }
                }
                return true
            } catch (e: Exception) {
                return false
            }
        }

        private val isGeneratedCrosscheckRun: Boolean
            get() = java.lang.Boolean.getBoolean("org.safere.crosscheck.generatedTests")

        private fun isGeneratedEarlierCaptureDivergence(
            exception: Exception, pattern: String, text: String, method: String, expected: Array<IntArray?>?
        ): Boolean {
            // This source also compiles in the library module, which cannot depend on the facade module.
            return isGeneratedCrosscheckRun
                    && exception.javaClass.getName() == "org.safere.crosscheck.CrosscheckException"
                    && hasKnownJdkEarlierCaptureDivergence(pattern, text, method, expected)
        }

        /**
         * Uses the independent RE2 oracle for the approved #1079 earlier-capture boundary. Every SafeRE
         * group must match the reference data, and the JDK may differ only by retaining the same nonempty
         * capture text at an earlier, disjoint position. Membership, group zero, missing captures, and
         * other capture differences remain failures.
         */
        private fun hasKnownJdkEarlierCaptureDivergence(
            pattern: String, text: String, method: String, expected: Array<IntArray?>?
        ): Boolean {
            if (expected == null) {
                return false
            }
            try {
                val safeRePattern = org.safere.Pattern.compile(pattern)
                val features = safeRePattern.analysis().features
                if (!features.contains(PatternFeature.BOUNDED_REPEAT) || !features.contains(PatternFeature.CAPTURES_IN_QUANTIFIER)) {
                    return false
                }
                val safeReMatcher = safeRePattern.matcher(text)
                val jdkMatcher = Pattern.compile(pattern).matcher(text)
                val safeReMatched =
                    if (method == "matches") safeReMatcher.matches() else safeReMatcher.find()
                val jdkMatched = if (method == "matches") jdkMatcher.matches() else jdkMatcher.find()
                if (!safeReMatched || !jdkMatched || expected.size != safeReMatcher.groupCount() + 1 || safeReMatcher.groupCount() != jdkMatcher.groupCount()) {
                    return false
                }
                var sawEarlierCapture = false
                for (g in expected.indices) {
                    val start = safeReMatcher.start(g)
                    val end = safeReMatcher.end(g)
                    if (start != utf8ByteToCharOffset(text, expected[g]!![0])
                        || end != utf8ByteToCharOffset(text, expected[g]!![1])
                    ) {
                        return false
                    }
                    val jdkStart = jdkMatcher.start(g)
                    val jdkEnd = jdkMatcher.end(g)
                    if (start == jdkStart && end == jdkEnd) {
                        continue
                    }
                    if (g == 0 || start < 0 || end <= start || jdkStart < 0 || jdkEnd > start || (safeReMatcher.group(g) != jdkMatcher.group(
                            g
                        ))
                    ) {
                        return false
                    }
                    sawEarlierCapture = true
                }
                return sawEarlierCapture
            } catch (e: RuntimeException) {
                return false
            }
        }

        private fun hasKnownJdkFailedStartCaptureLeakageDivergence(
            pattern: String, text: String
        ): Boolean {
            if (!mayExposeJdkFailedStartCaptureLeakage(pattern, text)) {
                return false
            }

            try {
                val safeReMatcher = org.safere.Pattern.compile(pattern).matcher(text)
                val jdkMatcher = Pattern.compile(pattern).matcher(text)
                val safeReFound = safeReMatcher.find()
                val jdkFound = jdkMatcher.find()
                if (safeReFound != jdkFound || !safeReFound) {
                    return false
                }
                if (safeReMatcher.start() != jdkMatcher.start() || safeReMatcher.end() != jdkMatcher.end()) {
                    return false
                }

                var sawLeakage = false
                val numGroups = min(safeReMatcher.groupCount(), jdkMatcher.groupCount())
                for (g in 1..numGroups) {
                    val safeReStart = safeReMatcher.start(g)
                    val safeReEnd = safeReMatcher.end(g)
                    val jdkStart = jdkMatcher.start(g)
                    val jdkEnd = jdkMatcher.end(g)
                    if (safeReStart == jdkStart && safeReEnd == jdkEnd) {
                        continue
                    }
                    if (safeReStart == -1 && safeReEnd == -1 && jdkStart >= 0 && jdkEnd >= jdkStart) {
                        sawLeakage = true
                        continue
                    }
                    return false
                }
                return sawLeakage
            } catch (e: RuntimeException) {
                return false
            }
        }

        private fun mayExposeJdkFailedStartCaptureLeakage(pattern: String, text: String): Boolean {
            return pattern.endsWith("$")
                    && pattern.contains("){")
                    && pattern.contains("(a)")
                    && text.indexOf('a') >= 0
        }

        /**
         * Parse a result field like `"0-5"`, `"0-5 2-3"`, `"0-5 - 2-3"`, or `"-"`.
         * 
         * 
         * Returns `null` for `"-"` (no match). Each `int[2]` pair is `[start, end]` as UTF-8 byte offsets. Unmatched subgroups appear as `[-1, -1]`.
         */
        fun parseResult(field: String): Array<IntArray?>? {
            var field = field
            field = field.trim { it <= ' ' }
            if (field == "-") {
                return null
            }
            val tokens: Array<String?> = field.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val result = Array<IntArray?>(tokens.size) { IntArray(2) }
            for (i in tokens.indices) {
                if (tokens[i] == "-") {
                    result[i]!![0] = -1
                    result[i]!![1] = -1
                } else {
                    val hyphen = tokens[i]!!.indexOf('-')
                    result[i]!![0] = tokens[i]!!.substring(0, hyphen).toInt()
                    result[i]!![1] = tokens[i]!!.substring(hyphen + 1).toInt()
                }
            }
            return result
        }

        /** Convert a UTF-8 byte offset to a Java char offset.  */
        fun utf8ByteToCharOffset(text: String, byteOffset: Int): Int {
            if (byteOffset < 0) {
                return -1
            }
            if (byteOffset == 0) {
                return 0
            }
            val utf8 = text.toByteArray(StandardCharsets.UTF_8)
            if (byteOffset >= utf8.size) {
                return text.length
            }
            return String(utf8, 0, byteOffset, StandardCharsets.UTF_8).length
        }

        /**
         * Unescape a Go-style double-quoted string from the test data.
         * 
         * 
         * Handles standard Go escape sequences: `\a`, `\b`, `\f`, `\n`, `\r`, `\t`, `\v`, `\\`, `\"`, `\xHH`, `\``uHHHH`,
         * `\UHHHHHHHH`, and octal `\OOO`.
         */
        fun unescapeString(s: String): String {
            var s = s
            if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length - 1)
            }
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s.get(i)
                if (c != '\\' || i + 1 >= s.length) {
                    sb.append(c)
                    i++
                    continue
                }
                when (val next = s.get(++i)) {
                    'a' -> sb.append('\u0007')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\f')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'v' -> sb.append('\u000B')
                    '\\' -> sb.append('\\')
                    '\'' -> sb.append('\'')
                    '"' -> sb.append('"')
                    'x' -> {
                        if (i + 2 < s.length) {
                            val `val` = s.substring(i + 1, i + 3).toInt(16)
                            sb.append(`val`.toChar())
                            i += 2
                        }
                    }

                    'u' -> {
                        if (i + 4 < s.length) {
                            val `val` = s.substring(i + 1, i + 5).toInt(16)
                            sb.append(`val`.toChar())
                            i += 4
                        }
                    }

                    'U' -> {
                        if (i + 8 < s.length) {
                            val `val` = s.substring(i + 1, i + 9).toInt(16)
                            sb.appendCodePoint(`val`)
                            i += 8
                        }
                    }

                    else -> {
                        if (next >= '0' && next <= '7') {
                            var `val` = next.code - '0'.code
                            if (i + 1 < s.length && s.get(i + 1) >= '0' && s.get(i + 1) <= '7') {
                                `val` = `val` * 8 + (s.get(++i).code - '0'.code)
                                if (i + 1 < s.length && s.get(i + 1) >= '0' && s.get(i + 1) <= '7') {
                                    `val` = `val` * 8 + (s.get(++i).code - '0'.code)
                                }
                            }
                            sb.append(`val`.toChar())
                        } else {
                            sb.append(next)
                        }
                    }
                }
                i++
            }
            return sb.toString()
        }

        /** Returns true if all characters in the string are single-byte in UTF-8 (i.e., ASCII).  */
        private fun isSingleBytes(s: String): Boolean {
            for (i in 0..<s.length) {
                if (s.get(i).code >= 0x80) {
                    return false
                }
            }
            return true
        }

        /** Escape non-printable characters for error messages.  */
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
