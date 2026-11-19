// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.regex.MatchResult

/**
 * Behavioral tests for patterns that start with a repetition of a class excluding only a few code
 * points, such as non-DOTALL `.*`, `.+` and `[^\n]*`. Their start candidates come
 * from walking back from an inner literal to the nearest excluded code point (usually a line
 * terminator), so these tests compare matches, bounds and groups with `java.util.regex`
 * across line terminators, regions, supplementary characters and UTF-8 input.
 */
internal class LeadingLineExpansionTest {
    @Test
    fun utf8ExistenceAgreesWithJdkAcrossLeadingRepeatConstraints() {
        val regexes: MutableList<String> = mutableListOf<String>(
            ".*error: \\w+",
            ".*?error: \\w+",
            "(.*)(error): (\\w+)",
            "[^;]*error: \\w+",
            "[^😀]*error: \\w+",
            ".{0,3}error: \\w+",
            ".+error: \\w+",
            ".{1,3}error: \\w+",
            "^.*error: \\w+",
            "\\A.*error: \\w+",
            "\\b.*error: \\w+",
            ".*\\berror: \\w+",
            ".*error: \\w+$",
            ".*[ab]{2}error: \\w+",
            "(?i).*ERROR: \\w+",
            ".*error\\X"
        )
        val inputs = mutableListOf<String>(
            "",
            "error: disk",
            "no marker",
            "error: -",
            "error: -\nerror: disk",
            "error: -\r\nnext error: disk",
            "error: -;next error: disk",
            "é😀error: -\u0085中 error: disk",
            "error: -\u2028error: -\u2029error: disk",
            "aaerror: disk",
            "erroré\u0301"
        )
        for (regex in regexes) {
            for (flags in intArrayOf(0, Pattern.UNIX_LINES, Pattern.MULTILINE)) {
                val pattern = Pattern.compile(regex, flags)
                val jdk = java.util.regex.Pattern.compile(regex, flags)
                for (tail in inputs) {
                    for (prefix in java.util.List.of<String?>("", PAD, PAD + "\n")) {
                        val input = prefix + tail
                        val expected = jdk.matcher(input).find()
                        // A nonzero byte window must retain assertion context relative to the window.
                        val bytes = input.toByteArray(StandardCharsets.UTF_8)
                        val wordPadded = ("before" + input + "after").toByteArray(StandardCharsets.UTF_8)
                        val linePadded = ("\n" + input + "\n").toByteArray(StandardCharsets.UTF_8)
                        for (utf8 in java.util.List.of<Utf8Input?>(
                            Utf8Input.trusted(bytes),
                            Utf8Input.trusted(wordPadded, 6, bytes.size),
                            Utf8Input.trusted(linePadded, 1, bytes.size)
                        )) {
                            Assertions.assertThat(pattern.find(utf8))
                                .`as`("/%s/ flags=%s on %s", regex, flags, input)
                                .isEqualTo(expected)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun findAgreesWithJdk() {
        for (flags in intArrayOf(0, org.safere.Pattern.UNIX_LINES, org.safere.Pattern.MULTILINE)) {
            for (regex in REGEXES) {
                val pattern = org.safere.Pattern.compile(regex, flags)
                val jdk = java.util.regex.Pattern.compile(regex, flags)
                for (input in paddedInputs()) {
                    val what: String = "%s (flags %d) on %s".format(regex, flags, input)
                    Assertions.assertThat<String?>(findAll(pattern.matcher(input)))
                        .`as`(what)
                        .isEqualTo(findAll(jdk.matcher(input)))
                    Assertions.assertThat<String?>(findAllUtf8(pattern, input))
                        .`as`("UTF-8 " + what)
                        .isEqualTo(jdkUtf8(jdk, input))
                    // Within the padding, every start behaves alike, so sample it sparsely.
                    val padded: Int = padLength(input)
                    var from = 0
                    while (from <= input.length) {
                        val expected = jdk.matcher(input)
                        val actual = pattern.matcher(input)
                        val found = expected.find(from)
                        Assertions.assertThat(actual.find(from)).`as`("%s from %d", what, from).isEqualTo(found)
                        if (found) {
                            Assertions.assertThat(groups(actual)).`as`("%s from %d", what, from)
                                .isEqualTo(groups(expected))
                        }
                        from += if (from < padded - 8) 31 else 1
                    }
                }
            }
        }
    }

    @Test
    fun regionsAgreeWithJdk() {
        val lines = "aa error: one\nbb error: two\r\ncc error: three"
        regionsAgreeWithJdk(lines, 3, 4)
        regionsAgreeWithJdk(PAD + "\n" + lines, 13, 17)
        regionsAgreeWithJdk(PAD + lines, 13, 17)
    }

    @Test
    fun failedAttemptAtLineStartMovesOnToLaterLine() {
        // The first line holds the literal, but its tail cannot match, so the start candidate at that
        // line's start fails and the search must find the match on the third line.
        val pattern = org.safere.Pattern.compile(".*error: (\\d+)")
        val input: String = PAD + " x error: none\nnothing\n" + PAD + " y error: 42\n"
        val thirdLine = input.indexOf("nothing\n") + "nothing\n".length
        val matcher = pattern.matcher(input)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(thirdLine)
        Assertions.assertThat(matcher.group(1)).isEqualTo("42")
        Assertions.assertThat(matcher.find()).isFalse()

        val utf8 = pattern.matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(utf8.find()).isTrue()
        Assertions.assertThat(utf8.start()).isEqualTo(thirdLine)
        Assertions.assertThat(utf8.find()).isFalse()
    }

    @Test
    fun plusNeedsOneCharacterBeforeTheLiteral() {
        val pattern = org.safere.Pattern.compile(".+error")
        val input: String = PAD + "\nerror\nerror\nxerror"
        val matcher = pattern.matcher(input)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(input.length - 6)
        Assertions.assertThat(matcher.end()).isEqualTo(input.length)
    }

    @Test
    fun supplementaryCharactersBeforeTheLiteral() {
        val pattern = org.safere.Pattern.compile("(.*)error")
        val input: String = PAD + "\n" + "😀".repeat(150) + "b😀error"
        val matcher = pattern.matcher(input)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(PAD.length + 1)
        Assertions.assertThat(matcher.group(1)).isEqualTo("😀".repeat(150) + "b😀")
        // A search starting between the halves of a pair still sees a valid match, as in the JDK.
        val jdk = java.util.regex.Pattern.compile("(.*)error").matcher(input)
        Assertions.assertThat(matcher.find(PAD.length + 2)).isEqualTo(jdk.find(PAD.length + 2))
        Assertions.assertThat(groups(matcher)).isEqualTo(groups(jdk))
    }

    companion object {
        // Start candidates are only chosen this way for skips of at least
        // MultiAnchorCompiler.MIN_WIDE_LEADING_SKIP units. Inputs are also run behind this much padding,
        // either as a separate first line or joined to the first line.
        private val PAD = "p".repeat(300)

        private val REGEXES: MutableList<String> = mutableListOf<String>(
            ".*error: \\w+",
            ".+error: \\w+",
            "[^\\n]*error: \\w+",
            "[^\\n]+error: \\w+",
            "(.*)(error): (\\w+)",
            "(.+?)error",
            ".*?error",
            "[^;]*error",
            "[^\\r\\n]*error(\\d)?",
            "(?:.*)error$",
            "(?m).*error$",
            "(?i).*ERROR: \\w+"
        )

        private val INPUTS = mutableListOf<String>(
            "",
            "error: x",
            "error: ",
            "no match here\nnor here\n",
            "first line\nsecond error: one\nthird",
            "error: at start\nmiddle error: two\n\nerror: three",
            "\n\n\nerror: after empty lines\n\n",
            "a error: \nb error: c",
            "crlf\r\nline error: one\r\nnext error: two\r\n",
            "lone cr\rthen error: x\rerror: y",
            "nel\u0085x error: a\u2028y error: b\u2029z error: c",
            "semi;colon error: x;y error: z",
            "😀😀 error: smile\n😀error: 😀\né error: é",
            "error: error: error: x",
            "errorerror: x error1 error",
            "ERROR: upper\nError: mixed",
            """
          2026-11-07 INFO starting
          2026-11-07 WARN error: nope
          2026-11-07 INFO error: ${'\t'}
          2026-11-07 ERROR error: disk
          
          """.trimIndent()
        )

        private fun regionsAgreeWithJdk(input: String, startStep: Int, endStep: Int) {
            for (regex in REGEXES) {
                val pattern = org.safere.Pattern.compile(regex)
                val jdk = java.util.regex.Pattern.compile(regex)
                var start = 0
                while (start <= input.length) {
                    var end = start
                    while (end <= input.length) {
                        for (transparent in booleanArrayOf(false, true)) {
                            val expected = jdk.matcher(input)
                            expected.region(start, end).useTransparentBounds(transparent)
                            val actual = pattern.matcher(input)
                            actual.region(start, end).useTransparentBounds(transparent)
                            Assertions.assertThat<String?>(findAll(actual))
                                .`as`("%s region [%d, %d) transparent=%s", regex, start, end, transparent)
                                .isEqualTo(findAll(expected))
                        }
                        end += endStep
                    }
                    start += startStep
                }
            }
        }

        private fun paddedInputs(): MutableList<String> {
            val inputs: MutableList<String> = ArrayList<String>()
            for (input in INPUTS) {
                inputs.add(input!!)
                inputs.add(PAD + "\n" + input)
                inputs.add(PAD + input)
            }
            return inputs
        }

        private fun padLength(input: String): Int {
            return if (input.startsWith(PAD)) PAD.length else 0
        }

        private fun findAll(matcher: org.safere.Matcher): MutableList<String> {
            val out: MutableList<String> = ArrayList<String>()
            while (matcher.find()) {
                out.add(groups(matcher))
            }
            return out
        }

        private fun findAll(matcher: java.util.regex.Matcher): MutableList<String> {
            val out: MutableList<String> = ArrayList<String>()
            while (matcher.find()) {
                out.add(groups(matcher))
            }
            return out
        }

        private fun groups(result: MatchResult): String {
            val sb = StringBuilder()
            for (group in 0..result.groupCount()) {
                sb.append(result.start(group)).append(':').append(result.end(group)).append(' ')
            }
            return sb.toString()
        }

        private fun findAllUtf8(pattern: org.safere.Pattern, input: String): MutableList<String> {
            val matcher = pattern.matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))
            val out: MutableList<String> = ArrayList<String>()
            while (matcher.find()) {
                val sb = StringBuilder()
                for (group in 0..matcher.groupCount()) {
                    sb.append(matcher.start(group)).append(':').append(matcher.end(group)).append(' ')
                }
                out.add(sb.toString())
            }
            return out
        }

        /** Returns the JDK's matches with bounds converted to UTF-8 byte offsets.  */
        private fun jdkUtf8(jdk: java.util.regex.Pattern, input: String): MutableList<String> {
            val matcher = jdk.matcher(input)
            val out: MutableList<String> = ArrayList<String>()
            while (matcher.find()) {
                val sb = StringBuilder()
                for (group in 0..matcher.groupCount()) {
                    val start = matcher.start(group)
                    sb.append(if (start < 0) -1 else byteOffset(input, start))
                        .append(':')
                        .append(if (start < 0) -1 else byteOffset(input, matcher.end(group)))
                        .append(' ')
                }
                out.add(sb.toString())
            }
            return out
        }

        private fun byteOffset(input: String, charIndex: Int): Int {
            return input.substring(0, charIndex).toByteArray(StandardCharsets.UTF_8).size
        }
    }
}
