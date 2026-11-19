// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.*
import java.util.function.IntConsumer
import java.util.regex.PatternSyntaxException
import java.util.stream.Stream

/** Tests for [Pattern.asPredicate] and [Pattern.asMatchPredicate].  */
internal class PatternPredicateTest {
    @Test
    fun asPredicatePartialMatch() {
        val pred = Pattern.compile("\\d+").asPredicate()
        Assertions.assertThat(pred.test("abc123def")).isTrue()
        Assertions.assertThat(pred.test("abcdef")).isFalse()
    }

    @Test
    fun asMatchPredicateFullMatch() {
        val pred = Pattern.compile("\\d+").asMatchPredicate()
        Assertions.assertThat(pred.test("123")).isTrue()
        Assertions.assertThat(pred.test("abc123")).isFalse()
    }

    @ParameterizedTest
    @MethodSource("patterns")
    fun asPredicateAgreesWithMatcherFind(regex: String?, flags: Int) {
        val pattern = Pattern.compile(regex, flags)
        val predicate = pattern.asPredicate()
        for (input in INPUTS) {
            Assertions.assertThat(predicate.test(input))
                .`as`("/%s/ flags=%d on %s", regex, flags, escape(input))
                .isEqualTo(pattern.matcher(input).find())
        }
    }

    @Test
    @DisabledForCrosscheck("Randomized internal parity includes intentional scalar-boundary differences")
    fun asPredicateAgreesWithMatcherFindOnRandomPatterns() {
        val random = Random(1062)
        val failures: MutableList<String> = ArrayList<String>()
        var i = 0
        while (i < 3000 && failures.size < 10) {
            val regex = StringBuilder()
            val atoms = 1 + random.nextInt(5)
            for (j in 0..<atoms) {
                if (random.nextInt(6) == 0) {
                    regex.append('|')
                }
                regex.append(ATOMS[random.nextInt(ATOMS.size)])
                regex.append(QUANTIFIERS[random.nextInt(QUANTIFIERS.size)])
            }
            val pattern: Pattern?
            try {
                pattern = Pattern.compile(regex.toString())
            } catch (e: PatternSyntaxException) {
                i++
                continue
            }
            val predicate = pattern.asPredicate()
            for (k in 0..7) {
                val input = StringBuilder()
                val pieces = random.nextInt(8)
                for (j in 0..<pieces) {
                    input.append(RANDOM_INPUT_PIECES[random.nextInt(RANDOM_INPUT_PIECES.size)])
                }
                val text = input.toString()
                if (predicate.test(text) != pattern.matcher(text).find()) {
                    failures.add("/" + regex + "/ on " + escape(text))
                }
            }
            i++
        }
        Assertions.assertThat<String?>(failures).isEmpty()
    }

    companion object {
        val INPUTS: MutableList<String> = java.util.List.of<String>(
            "",
            "a",
            "hello",
            "say hello world",
            "HELLO there",
            "foo bar baz",
            "x foo",
            "x foo\n",
            "x foo\r\n",
            "foo\nbar",
            "line1\r\nline2\r\n",
            "  # noqa",
            " ".repeat(40) + "# noqaX",
            "GET /index.html",
            "2026-10-26",
            "on 2026-10-26 ok",
            "MyClassTest",
            "getValue",
            "a😀b",
            "😀",
            "\uD83D",
            "\uDE00",
            "x\uDE00y",
            "\uD83D\uDE00\uDE00",
            "éabc",
            "Straße",
            "ǅ titlecase",
            "abc123def"
        )

        /** Patterns chosen to reach each step of the matcher-free path and its matcher fallbacks.  */
        @JvmStatic
        fun patterns(): Stream<Arguments?> {
            return Stream.of<Arguments?>( // Literal fast path, unanchored and anchored.
                Arguments.of("hello", 0),
                Arguments.of("^hello", 0),
                Arguments.of("😀", 0),
                Arguments.of("hello", Pattern.CASE_INSENSITIVE),  // Keyword alternation.
                Arguments.of("\\b(?:foo|bar|baz)\\b", 0),
                Arguments.of("(?i)\\b(?:foo|bar|baz)\\b", 0),  // Anchored prefix and anchored character class.
                Arguments.of("^GET [^ ]+", 0),
                Arguments.of("^[0-9]{4}-", 0),
                Arguments.of(
                    "^[\\x{1F600}-\\x{1F64F}]",
                    0
                ),  // End-anchored search, including $ before a final line terminator.
                Arguments.of("(?:foo|fax)$", 0),
                Arguments.of("\\s*(?:foo|fax)\\z", 0),
                Arguments.of("(?i)\\s*(?:foo|fax)$", 0),
                Arguments.of("foo$", Pattern.MULTILINE),
                Arguments.of("^bar", Pattern.MULTILINE),
                Arguments.of("line\\d$", Pattern.MULTILINE),
                Arguments.of(
                    "line\\d$",
                    Pattern.MULTILINE or Pattern.UNIX_LINES
                ),  // Rejection on a required literal, and start acceleration.
                Arguments.of("[A-Z][a-z]+Test", 0),
                Arguments.of("\\d{4}-\\d{2}-\\d{2}", 0),
                Arguments.of("[a-z]+ing\\b", 0),
                Arguments.of("foo[a-z]{0,8}bar", 0),
                Arguments.of("(?i)straße", 0),
                Arguments.of("\\s*# noqa", 0),
                Arguments.of("\\s*# noqa[0-9]", 0),
                Arguments.of("a*[bc]d", 0),
                Arguments.of(
                    "é[ab]+c",
                    0
                ),  // Surrogates: candidates inside a pair must be skipped, lone halves still match.
                Arguments.of("\\uDE00", 0),
                Arguments.of("[\\uDC00-\\uDFFF]", 0),
                Arguments.of("x\\uDE00", 0),
                Arguments.of("\\uD83D", 0),
                Arguments.of(".\\uDE00", 0),  // Empty matches and minimum length.
                Arguments.of("", 0),
                Arguments.of("x*", 0),
                Arguments.of("[a-z]{6,}", 0),  // Fallbacks to the matcher: grapheme semantics and loop registers.
                Arguments.of("\\X\\b{g}a", 0),
                Arguments.of("(x?)*Q", 0),
                Arguments.of("(?:a|)+b", 0),  // Case folding and titlecase.
                Arguments.of("(?i)ǆ", 0),
                Arguments.of("(?u)(?i)STRASSE", 0)
            )
        }

        private val ATOMS = arrayOf<String?>(
            "a", "b", "x", ".", "[ab]", "[^a]", "\\d", "\\w", "\\s", "\\b", "\\B", "^", "$", "\\z", "é",
            "😀", "\\uDE00", "\\uD83D", "(?i)", "(?m)", "foo", "bar", "\\n", "\\r\\n", "#",
        )
        private val QUANTIFIERS = arrayOf<String?>("", "", "", "?", "*", "+", "{2}", "{0,3}", "+?")
        private val RANDOM_INPUT_PIECES = arrayOf<String?>(
            "a", "b", "x", "foo", "bar", " ", "\n", "\r\n", "1", "é", "😀", "\uDE00", "\uD83D", "#", "A",
        )

        fun escape(s: String): String {
            val sb = StringBuilder("\"")
            s.chars()
                .forEach(
                    IntConsumer { c: Int ->
                        if (c < 0x20 || c >= 0x7f) {
                            sb.append(String.format("\\u%04X", c))
                        } else {
                            sb.append(c.toChar())
                        }
                    })
            return sb.append('"').toString()
        }
    }
}
