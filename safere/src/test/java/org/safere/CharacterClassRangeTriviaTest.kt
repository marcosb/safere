// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import java.util.regex.PatternSyntaxException

@DisabledForCrosscheck("Ignored range trivia preserves bracket roles; intentional divergence #1070")
internal class CharacterClassRangeTriviaTest {
    @Test
    fun delimiterRangesKeepTheirUnspacedMeanings() {
        // #1070: skipped trivia must not turn a raw bracket into a scalar endpoint.
        for (gap in GAPS) {
            assertMembers("[!-" + gap + "]]", mutableSetOf<String?>("!]", "-]"))
            assertMembers("[!-" + gap + "[A]]", mutableSetOf<String?>("!", "-", "A"))
            assertMembers("[a-" + gap + "]", mutableSetOf<String?>("a", "-"))
            assertMembers("[a-" + gap + "[z]]", mutableSetOf<String?>("a", "-", "z"))
            assertMembers("[[a]-" + gap + "z]", mutableSetOf<String?>("a", "-", "z"))
        }
    }

    @Test
    fun rangeTriviaPreservesClassNestingNegationAndFlagScopes() {
        for (lower in mutableListOf<String>("!", "a", "\\x41", "\\Q!\\E", "😀")) {
            for (tail in mutableListOf<String>("]", "]a]", "[A]]", "[a-z]]", "[^A]]", "[\\d]]")) {
                val canonical = "[" + lower + "-" + tail
                for (gap in GAPS) {
                    val candidate = "[" + lower + "-" + gap + tail
                    assertEquivalent(candidate, canonical)
                    assertEquivalent("[x" + candidate + "]", "[x" + canonical + "]")
                    assertEquivalent("[^" + candidate + "]", "[^" + canonical + "]")
                    assertEquivalent("([x" + candidate + "])+", "([x" + canonical + "])+")
                    assertEquivalent("[" + candidate + "&&[a-z]]", "[" + canonical + "&&[a-z]]")
                }
            }
        }
    }

    @Test
    fun escapedAndQuotedBracketsRemainScalarRangeEndpoints() {
        for (endpoint in mutableListOf<String>("\\]", "\\Q]\\E", "\\x5D", "\\[", "\\Q[\\E", "\\u005B")) {
            for (gap in GAPS) {
                assertEquivalent("[!-" + gap + endpoint + "]", "[!-" + endpoint + "]")
            }
        }
        Assertions.assertThat(Pattern.compile("(?x)[!- \\]]").matcher("A").matches()).isTrue()
        Assertions.assertThat(Pattern.compile("(?x)[!- \\[]").matcher("A").matches()).isTrue()
    }

    @Test
    fun invalidRangesAndUnclosedClassesStayInvalidAfterTrivia() {
        for (gap in GAPS) {
            for (suffix in mutableListOf<String>("\\d]", "\\p{Digit}]", "[A]", "[", "")) {
                Assertions.assertThatThrownBy(ThrowingCallable {
                    Pattern.compile(
                        "[!-" + gap + suffix,
                        Pattern.COMMENTS
                    )
                })
                    .isInstanceOf(PatternSyntaxException::class.java)
            }
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("[a-" + gap + "\\]]", Pattern.COMMENTS) })
                .isInstanceOf(PatternSyntaxException::class.java)
        }
    }

    @Test
    fun whitespaceIsSignificantWhenEscapedQuotedOrOutsideCommentsMode() {
        for (endpoint in mutableListOf<String>("\\ ", "\\x20", "\\Q \\E")) {
            Assertions.assertThatThrownBy(ThrowingCallable {
                Pattern.compile(
                    "[!-" + endpoint + "]",
                    Pattern.COMMENTS
                )
            })
                .isInstanceOf(PatternSyntaxException::class.java)
        }
        Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("[a- ]") })
            .isInstanceOf(PatternSyntaxException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("(?x)(?-x:[a- ])") })
            .isInstanceOf(PatternSyntaxException::class.java)
        Assertions.assertThat(Pattern.compile("(?x:[a- ]) ").matcher("a ").matches()).isTrue()
    }

    companion object {
        private val GAPS = mutableListOf<String>("", " ", "\t", "\r\n", "#c\n", "\\Q\\E", " \\Q\\E", "\\Q\\E #c\n")
        private val INPUTS: MutableList<String> = inputs()

        private fun assertMembers(regex: String, expected: MutableSet<String?>) {
            for (scoped in java.util.List.of<String?>(regex, "(?x)" + regex, "(?x:" + regex + ")")) {
                val pattern = Pattern.compile(scoped, Pattern.COMMENTS)
                for (input in INPUTS) {
                    Assertions.assertThat(pattern.matcher(input).matches())
                        .`as`("/%s/ on <%s>", scoped, input)
                        .isEqualTo(expected.contains(input))
                }
            }
        }

        private fun assertEquivalent(candidate: String?, canonical: String) {
            for (flags in java.util.List.of<Int?>(Pattern.COMMENTS, Pattern.COMMENTS or Pattern.UNIX_LINES)) {
                assertEquivalent(candidate, canonical, flags)
            }
            assertEquivalent("(?x)" + candidate, "(?x)" + canonical, 0)
            assertEquivalent("(?x:" + candidate + ")", "(?x:" + canonical + ")", 0)
        }

        private fun assertEquivalent(candidate: String?, canonical: String, flags: Int) {
            val expected: java.util.regex.Pattern?
            try {
                // Only the unspaced form is a JDK oracle; the spaced form intentionally diverges.
                expected = java.util.regex.Pattern.compile(canonical, flags)
            } catch (e: PatternSyntaxException) {
                Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile(candidate, flags) })
                    .`as`("rejection of /%s/", candidate)
                    .isInstanceOf(PatternSyntaxException::class.java)
                return
            }
            val actual = Pattern.compile(candidate, flags)
            for (input in INPUTS) {
                Assertions.assertThat(actual.matcher(input).matches())
                    .`as`("/%s/ equivalent to /%s/ on <%s>", candidate, canonical, input)
                    .isEqualTo(expected.matcher(input).matches())
            }
        }

        private fun inputs(): MutableList<String> {
            val inputs: MutableList<String> = ArrayList<String>()
            inputs.add("")
            for (rune in 0..127) {
                val scalar = Character.toString(rune)
                inputs.add(scalar)
                inputs.add(scalar + "]")
                inputs.add(scalar + "a]")
            }
            inputs.addAll(mutableListOf<String>("😀", "😀]", "Ā", "\uD83D", "\uDE00", "aa"))
            return inputs.toList().toMutableList()
        }
    }
}
