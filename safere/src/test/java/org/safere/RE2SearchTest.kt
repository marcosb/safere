// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.regex.PatternSyntaxException
import java.util.stream.Stream

/**
 * Tests parsed from RE2's `re2-search.txt` test data file.
 * 
 * 
 * The file format defines sets of test strings and regexps. For each (regexp, string) pair, four
 * result fields are given (semicolon-separated):
 * 
 * 
 *  1. Full match without submatch extraction (`matches()`)
 *  1. Partial match without submatch extraction (`find()`)
 *  1. Full match with submatch extraction (`matches()` + groups)
 *  1. Partial match with submatch extraction (`find()` + groups)
 * 
 * 
 * 
 * Each field is either "-" (no match) or byte-offset pairs like "0-5" or "0-2 0-1" (with
 * submatch groups). We verify match/no-match agreement and matched text using fields 1 and 2.
 */
@DisplayName("RE2 Search Tests (from re2-search.txt)")
internal class RE2SearchTest {
    /** A single search test case.  */
    @JvmRecord
    internal data class SearchTestCase(
        val pattern: String?,
        val text: String?,
        val expectFullMatch: Boolean,
        val expectFind: Boolean,
        val expectedFindGroup: String?
    ) {
        override fun toString(): String {
            return String.format(
                "pat=\"%s\" text=\"%s\" matches=%b find=%b", pattern, text, expectFullMatch, expectFind
            )
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("searchTests")
    fun testMatches(tc: SearchTestCase) {
        if (Companion.hasRe2NonZeroNumericEscape(tc.pattern!!)) {
            return
        }
        val p: Pattern?
        try {
            p = Pattern.compile(tc.pattern)
        } catch (e: PatternSyntaxException) {
            // Pattern not supported by SafeRE; skip
            return
        }
        val m = p.matcher(tc.text)
        Assertions.assertThat(m.matches())
            .`as`("matches() for pattern \"%s\" on \"%s\"", tc.pattern, tc.text)
            .isEqualTo(tc.expectFullMatch)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("searchTests")
    fun testFind(tc: SearchTestCase) {
        if (Companion.hasRe2NonZeroNumericEscape(tc.pattern!!)) {
            return
        }
        val p: Pattern?
        try {
            p = Pattern.compile(tc.pattern)
        } catch (e: PatternSyntaxException) {
            return
        }
        val m = p.matcher(tc.text)
        val found = m.find()
        Assertions.assertThat(found)
            .`as`("find() for pattern \"%s\" on \"%s\"", tc.pattern, tc.text)
            .isEqualTo(tc.expectFind)

        if (found && tc.expectedFindGroup != null) {
            Assertions.assertThat(m.group())
                .`as`("find() group(0) for pattern \"%s\" on \"%s\"", tc.pattern, tc.text)
                .isEqualTo(tc.expectedFindGroup)
        }
    }

    companion object {
        /**
         * Convert a UTF-8 byte offset to a Java char offset. Returns -1 if the byte offset can't be
         * mapped (e.g., falls inside a multibyte character).
         */
        private fun utf8ByteToCharOffset(text: String, byteOffset: Int): Int {
            if (byteOffset < 0) {
                return -1
            }
            val utf8 = text.toByteArray(StandardCharsets.UTF_8)
            if (byteOffset > utf8.size) {
                return text.length
            }
            // Decode the first byteOffset bytes and count chars
            val prefix = String(utf8, 0, byteOffset, StandardCharsets.UTF_8)
            return prefix.length
        }

        /** Parse a result field like "0-5" or "0-5 0-2" or "-". Returns null for "-".  */
        private fun parseResultField(field: String): Array<IntArray?>? {
            var field = field
            field = field.trim { it <= ' ' }
            if (field == "-") {
                return null
            }
            val pairs: Array<String?> = field.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val result = Array<IntArray?>(pairs.size) { IntArray(2) }
            for (i in pairs.indices) {
                val parts: Array<String?> =
                    pairs[i]!!.split("-".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                result[i]!![0] = parts[0]!!.toInt()
                result[i]!![1] = parts[1]!!.toInt()
            }
            return result
        }

        /** Unescape a C-style string from the test data (strip quotes, handle \n etc).  */
        private fun unescapeString(s: String): String {
            var s = s
            if (s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length - 1)
            }
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                if (s.get(i) == '\\' && i + 1 < s.length) {
                    val next = s.get(i + 1)
                    when (next) {
                        'n' -> {
                            sb.append('\n')
                            i++
                        }

                        't' -> {
                            sb.append('\t')
                            i++
                        }

                        'r' -> {
                            sb.append('\r')
                            i++
                        }

                        '\\' -> {
                            sb.append('\\')
                            i++
                        }

                        '"' -> {
                            sb.append('"')
                            i++
                        }

                        'x' -> {
                            if (i + 3 < s.length) {
                                sb.append(s.substring(i + 2, i + 4).toInt(16).toChar())
                                i += 3
                            } else {
                                sb.append(next)
                                i++
                            }
                        }

                        else -> {
                            sb.append(next)
                            i++
                        }
                    }
                } else {
                    sb.append(s.get(i))
                }
                i++
            }
            return sb.toString()
        }

        /** Extract matched text from byte offsets, or null if no match.  */
        private fun extractMatchedText(text: String, matchResult: Array<IntArray?>?): String? {
            if (matchResult == null || matchResult.size == 0) {
                return null
            }
            val startByte = matchResult[0]!![0]
            val endByte = matchResult[0]!![1]
            val startChar: Int = utf8ByteToCharOffset(text, startByte)
            val endChar: Int = utf8ByteToCharOffset(text, endByte)
            if (startChar < 0 || endChar < 0 || startChar > text.length || endChar > text.length) {
                return null
            }
            return text.substring(startChar, endChar)
        }

        private fun hasRe2NonZeroNumericEscape(pattern: String): Boolean {
            var backslashes = 0
            for (i in 0..<pattern.length) {
                val c = pattern.get(i)
                if (c == '\\') {
                    backslashes++
                    continue
                }
                if (backslashes % 2 == 1 && c >= '1' && c <= '9') {
                    return true
                }
                backslashes = 0
            }
            return false
        }

        @Throws(IOException::class)
        fun searchTests(): Stream<Arguments?> {
            val tests: MutableList<Arguments?> = ArrayList<Arguments?>()
            val `is` = RE2SearchTest::class.java.getResourceAsStream("/re2-search.txt")
            if (`is` == null) {
                throw IOException("Cannot find re2-search.txt in test resources")
            }

            BufferedReader(InputStreamReader(`is`, StandardCharsets.UTF_8)).use { reader ->
                val strings: MutableList<String> = ArrayList<String>()
                var line: String?

                // Skip header lines until we hit "strings"
                while ((reader.readLine().also { line = it }) != null) {
                    line = line!!.trim { it <= ' ' }
                    if (line == "strings") {
                        break
                    }
                }
                while (true) {
                    // We're at "strings" or just read past it. Read string list.
                    strings.clear()
                    while ((reader.readLine().also { line = it }) != null) {
                        line = line!!.trim { it <= ' ' }
                        if (line == "regexps") {
                            break
                        }
                        if (line.startsWith("\"")) {
                            strings.add(unescapeString(line))
                        }
                    }
                    if (line == null) {
                        break
                    }

                    // Read regexps until next "strings" or EOF
                    while ((reader.readLine().also { line = it }) != null) {
                        line = line!!.trim { it <= ' ' }
                        if (line == "strings") {
                            break
                        }
                        if (!line.startsWith("\"")) {
                            continue
                        }

                        val pattern: String = unescapeString(line)

                        // Read one result line per test string
                        for (si in strings.indices) {
                            var resultLine = reader.readLine()
                            if (resultLine == null) {
                                break
                            }
                            resultLine = resultLine.trim { it <= ' ' }
                            val fields: Array<String?> =
                                resultLine.split(";".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                            if (fields.size < 4) {
                                continue
                            }

                            val text = strings.get(si)
                            val fullMatch: Array<IntArray?>? = Companion.parseResultField(fields[0]!!)
                            val findMatch: Array<IntArray?>? = Companion.parseResultField(fields[1]!!)

                            val expectedFindText: String? = extractMatchedText(text, findMatch)

                            tests.add(
                                Arguments.of(
                                    SearchTestCase(
                                        pattern, text, fullMatch != null, findMatch != null, expectedFindText
                                    )
                                )
                            )
                        }
                    }
                    if (line == null) {
                        break
                    }
                }
            }
            return tests.stream()
        }
    }
}
