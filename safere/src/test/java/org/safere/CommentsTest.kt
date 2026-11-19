// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * Tests for [Pattern.COMMENTS] flag and `(?x)` inline verbose mode.
 * 
 * 
 * Verifies that SafeRE matches JDK [Pattern.COMMENTS] behavior: unescaped
 * whitespace is ignored, `#` starts a comment to end of line, and these rules apply both
 * outside and inside character classes.
 */
internal object CommentsTest {
    private fun assertMatchesSameAsJdk(regex: String, flags: Int, input: String) {
        val jdkMatches = Pattern.compile(regex, flags).matcher(input).matches()
        val safeMatches = org.safere.Pattern.compile(regex, flags).matcher(input).matches()

        Assertions.assertThat(safeMatches)
            .`as`("matches() for /%s/ (flags=%d) on \"%s\"", regex, flags, input)
            .isEqualTo(jdkMatches)
    }

    // ---------------------------------------------------------------------------
    // Pattern.COMMENTS compile flag
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Pattern.COMMENTS compile flag")
    internal inner class CompileFlag {
        @Test
        @DisplayName("whitespace in pattern is ignored")
        fun whitespaceIgnored() {
            val p = org.safere.Pattern.compile("a b c", org.safere.Pattern.COMMENTS)
            Assertions.assertThat(p.matcher("abc").matches()).isTrue()
            Assertions.assertThat(p.matcher("a b c").matches()).isFalse()
        }

        @Test
        @DisplayName("tabs and newlines are ignored")
        fun tabsAndNewlinesIgnored() {
            val p = org.safere.Pattern.compile("a\tb\nc", org.safere.Pattern.COMMENTS)
            Assertions.assertThat(p.matcher("abc").matches()).isTrue()
        }

        @Test
        @DisplayName("COMMENTS whitespace set matches JDK parser")
        fun commentsWhitespaceSetMatchesJdkParser() {
            val ignoredByJdkComments = intArrayOf('\t'.code, '\n'.code, 0x0B, '\f'.code, '\r'.code, ' '.code)
            for (cp in ignoredByJdkComments) {
                val pattern = "a" + String(Character.toChars(cp)) + "b"
                Assertions.assertThat(
                    org.safere.Pattern.compile(pattern, org.safere.Pattern.COMMENTS).matcher("ab").matches()
                )
                    .`as`("U+%04X should be ignored in COMMENTS mode", cp)
                    .isTrue()
            }

            val literalInJdkComments = intArrayOf(0x1C, 0x1D, 0x1E, 0x1F, 0x85, 0x1680, 0x2028, 0x2029)
            for (cp in literalInJdkComments) {
                val ch = String(Character.toChars(cp))
                val p = org.safere.Pattern.compile(
                    "a" + ch + "\\^]",
                    org.safere.Pattern.COMMENTS or org.safere.Pattern.MULTILINE
                )
                Assertions.assertThat(p.matcher("a" + ch + "^]").matches())
                    .`as`("U+%04X should remain literal in COMMENTS mode", cp)
                    .isTrue()
                Assertions.assertThat(p.matcher("a^]").matches())
                    .`as`("U+%04X should not be ignored in COMMENTS mode", cp)
                    .isFalse()
            }
        }

        @Test
        @DisplayName("# starts a comment to end of line")
        fun hashComment() {
            val p = org.safere.Pattern.compile("a # match 'a'\nb", org.safere.Pattern.COMMENTS)
            Assertions.assertThat(p.matcher("ab").matches()).isTrue()
            Assertions.assertThat(p.matcher("a # match 'a'\nb").matches()).isFalse()
        }

        @Test
        @DisplayName("# comment at end of pattern (no trailing newline)")
        fun hashCommentAtEnd() {
            val p = org.safere.Pattern.compile("ab # trailing comment", org.safere.Pattern.COMMENTS)
            Assertions.assertThat(p.matcher("ab").matches()).isTrue()
        }

        @Test
        @DisplayName("escaped space is literal")
        fun escapedSpaceLiteral() {
            val p = org.safere.Pattern.compile("a\\ b", org.safere.Pattern.COMMENTS)
            Assertions.assertThat(p.matcher("a b").matches()).isTrue()
            Assertions.assertThat(p.matcher("ab").matches()).isFalse()
        }

        @Test
        @DisplayName("escaped # is literal")
        fun escapedHashLiteral() {
            val p = org.safere.Pattern.compile("a\\#b", org.safere.Pattern.COMMENTS)
            Assertions.assertThat(p.matcher("a#b").matches()).isTrue()
        }

        @Test
        @DisplayName("multiline verbose pattern")
        fun multilineVerbosePattern() {
            val p =
                org.safere.Pattern.compile(
                    """
              ^           # start of string
              (\d{3})    # area code
              -           # separator
              (\d{4})    # number
              ${'$'}           # end of string
              
              """.trimIndent(),
                    org.safere.Pattern.COMMENTS
                )
            val m = p.matcher("555-1234")
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("555")
            Assertions.assertThat(m.group(2)).isEqualTo("1234")
        }

        @Test
        @DisplayName("verbose block-comment replacement preserves captured text")
        fun verboseBlockCommentReplacementPreservesCapturedText() {
            // Regression for issue #105.
            val p =
                org.safere.Pattern.compile(
                    """
              /[*]
              [\s*]*
              \bMOE:insert\b

              (.*?)

              [*]/
              
              """.trimIndent(),
                    org.safere.Pattern.DOTALL or org.safere.Pattern.COMMENTS
                )

            var content: String? =
                """
          @Deprecated
            /* MOE:insert public */ void trimToSize() {
          
          """.trimIndent()
            content = p.matcher(content).replaceAll("$1")
            Assertions.assertThat(content).isEqualTo("@Deprecated\n   public  void trimToSize() {\n")
        }
    }

    // ---------------------------------------------------------------------------
    // Character classes under COMMENTS
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Character classes under COMMENTS")
    internal inner class CharClasses {
        @Test
        @DisplayName("whitespace inside character class is ignored")
        fun whitespaceInCharClassIgnored() {
            val p = org.safere.Pattern.compile("[a b]", org.safere.Pattern.COMMENTS)
            Assertions.assertThat(p.matcher("a").matches()).isTrue()
            Assertions.assertThat(p.matcher("b").matches()).isTrue()
            Assertions.assertThat(p.matcher(" ").matches()).isFalse()
        }

        @Test
        @DisplayName("escaped space in character class is literal")
        fun escapedSpaceInCharClass() {
            val p = org.safere.Pattern.compile("[\\ ]", org.safere.Pattern.COMMENTS)
            Assertions.assertThat(p.matcher(" ").matches()).isTrue()
            Assertions.assertThat(p.matcher("a").matches()).isFalse()
        }

        @Test
        @DisplayName("COMMENTS character-class whitespace set matches JDK parser")
        fun commentsCharacterClassWhitespaceSetMatchesJdkParser() {
            val ignoredByJdkComments = intArrayOf('\t'.code, '\n'.code, 0x0B, '\f'.code, '\r'.code, ' '.code)
            for (cp in ignoredByJdkComments) {
                val pattern = "[a" + String(Character.toChars(cp)) + "b]"
                val p = org.safere.Pattern.compile(pattern, org.safere.Pattern.COMMENTS)
                Assertions.assertThat(p.matcher("a").matches()).isTrue()
                Assertions.assertThat(p.matcher("b").matches()).isTrue()
                Assertions.assertThat(p.matcher(String(Character.toChars(cp))).matches())
                    .`as`("U+%04X should be ignored inside COMMENTS character classes", cp)
                    .isFalse()
            }

            val literalInJdkComments = intArrayOf(0x1C, 0x1D, 0x1E, 0x1F, 0x85, 0x1680, 0x2028, 0x2029)
            for (cp in literalInJdkComments) {
                val ch = String(Character.toChars(cp))
                val p = org.safere.Pattern.compile("[a" + ch + "b]", org.safere.Pattern.COMMENTS)
                Assertions.assertThat(p.matcher(ch).matches())
                    .`as`("U+%04X should remain literal inside COMMENTS character classes", cp)
                    .isTrue()
            }
        }

        @Test
        @DisplayName("range in character class with whitespace")
        fun rangeWithWhitespace() {
            val p = org.safere.Pattern.compile("[ a - z ]", org.safere.Pattern.COMMENTS)
            Assertions.assertThat(p.matcher("m").matches()).isTrue()
            Assertions.assertThat(p.matcher("A").matches()).isFalse()
        }

        @Test
        @DisplayName("negated character class with whitespace")
        fun negatedCharClassWithWhitespace() {
            val p = org.safere.Pattern.compile("[^ a - z ]", org.safere.Pattern.COMMENTS)
            Assertions.assertThat(p.matcher("A").matches()).isTrue()
            Assertions.assertThat(p.matcher("m").matches()).isFalse()
        }
    }

    // ---------------------------------------------------------------------------
    // Inline (?x) flag
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Inline (?x) flag")
    internal inner class InlineFlag {
        @Test
        @DisplayName("(?x) enables comments mode")
        fun inlineXEnablesComments() {
            val p = org.safere.Pattern.compile("(?x) a b c")
            Assertions.assertThat(p.matcher("abc").matches()).isTrue()
        }

        @Test
        @DisplayName("(?-x) disables comments mode")
        fun inlineMinusXDisablesComments() {
            val p = org.safere.Pattern.compile("(?x)a b(?-x) c")
            Assertions.assertThat(p.matcher("ab c").matches()).isTrue()
            Assertions.assertThat(p.matcher("abc").matches()).isFalse()
        }

        @Test
        @DisplayName("(?x:...) scoped comments mode")
        fun scopedInlineX() {
            val p = org.safere.Pattern.compile("(?x: a b) c")
            Assertions.assertThat(p.matcher("ab c").matches()).isTrue()
            Assertions.assertThat(p.matcher("abc").matches()).isFalse()
        }

        @Test
        @DisplayName("(?x) with # comment")
        fun inlineXWithComment() {
            val p = org.safere.Pattern.compile("(?x)a # match a\nb # match b")
            Assertions.assertThat(p.matcher("ab").matches()).isTrue()
        }
    }

    // ---------------------------------------------------------------------------
    // COMMENTS combined with other flags
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("COMMENTS combined with other flags")
    internal inner class CombinedFlags {
        @Test
        @DisplayName("COMMENTS | DOTALL")
        fun commentsAndDotall() {
            val p = org.safere.Pattern.compile("a . b", org.safere.Pattern.COMMENTS or org.safere.Pattern.DOTALL)
            Assertions.assertThat(p.matcher("a\nb").matches()).isTrue()
        }

        @Test
        @DisplayName("COMMENTS | CASE_INSENSITIVE")
        fun commentsAndCaseInsensitive() {
            val p =
                org.safere.Pattern.compile("a b c", org.safere.Pattern.COMMENTS or org.safere.Pattern.CASE_INSENSITIVE)
            Assertions.assertThat(p.matcher("ABC").matches()).isTrue()
        }

        @Test
        @DisplayName("COMMENTS | MULTILINE")
        fun commentsAndMultiline() {
            val p = org.safere.Pattern.compile("^ a $", org.safere.Pattern.COMMENTS or org.safere.Pattern.MULTILINE)
            Assertions.assertThat(p.matcher("a").find()).isTrue()
            Assertions.assertThat(p.matcher("x\na\ny").find()).isTrue()
        }
    }

    // ---------------------------------------------------------------------------
    // Edge cases
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Edge cases")
    internal inner class EdgeCases {
        @Test
        @DisplayName("empty pattern with COMMENTS")
        fun emptyPattern() {
            val p = org.safere.Pattern.compile("", org.safere.Pattern.COMMENTS)
            Assertions.assertThat(p.matcher("").matches()).isTrue()
        }

        @Test
        @DisplayName("pattern of only whitespace with COMMENTS")
        fun onlyWhitespace() {
            val p = org.safere.Pattern.compile("   \t\n   ", org.safere.Pattern.COMMENTS)
            Assertions.assertThat(p.matcher("").matches()).isTrue()
        }

        @Test
        @DisplayName("pattern of only comment with COMMENTS")
        fun onlyComment() {
            val p = org.safere.Pattern.compile("# just a comment", org.safere.Pattern.COMMENTS)
            Assertions.assertThat(p.matcher("").matches()).isTrue()
        }

        @Test
        @DisplayName("\\Q...\\E quoting preserves whitespace under COMMENTS")
        fun quotingPreservesWhitespace() {
            val p = org.safere.Pattern.compile("\\Q a b \\E", org.safere.Pattern.COMMENTS)
            Assertions.assertThat(p.matcher(" a b ").matches()).isTrue()
            Assertions.assertThat(p.matcher("ab").matches()).isFalse()
        }

        @Test
        @DisplayName("empty quotes do not separate quantifiers from their operands")
        fun emptyQuotesDoNotSeparateQuantifiersFromTheirOperands() {
            val regexes = arrayOf<String?>("a\\Q\\E+", "a\\Q\\E*", "a\\Q\\E?", "a\\Q\\E{1,3}")
            val inputs = arrayOf<String?>("", "a", "aa", "aaa", "name")

            for (regex in regexes) {
                for (input in inputs) {
                    CommentsTest.assertMatchesSameAsJdk(regex!!, org.safere.Pattern.COMMENTS, input!!)
                }
            }
        }

        @Test
        @DisplayName("quantifiers with whitespace in COMMENTS mode")
        fun quantifiersWithWhitespace() {
            val p = org.safere.Pattern.compile("a +", org.safere.Pattern.COMMENTS)
            Assertions.assertThat(p.matcher("aaa").matches()).isTrue()
        }

        @Test
        @DisplayName("alternation with whitespace in COMMENTS mode")
        fun alternationWithWhitespace() {
            val p = org.safere.Pattern.compile("a | b", org.safere.Pattern.COMMENTS)
            Assertions.assertThat(p.matcher("a").matches()).isTrue()
            Assertions.assertThat(p.matcher("b").matches()).isTrue()
        }

        @Test
        @DisplayName("group with whitespace in COMMENTS mode")
        fun groupWithWhitespace() {
            val p = org.safere.Pattern.compile("( a ) ( b )", org.safere.Pattern.COMMENTS)
            val m = p.matcher("ab")
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("a")
            Assertions.assertThat(m.group(2)).isEqualTo("b")
        }

        @Test
        @DisplayName("COMMENTS has no effect with LITERAL flag")
        fun commentsWithLiteral() {
            val p = org.safere.Pattern.compile("a b", org.safere.Pattern.COMMENTS or org.safere.Pattern.LITERAL)
            // LITERAL takes precedence — the pattern is the literal string "a b"
            Assertions.assertThat(p.matcher("a b").matches()).isTrue()
            Assertions.assertThat(p.matcher("ab").matches()).isFalse()
        }

        @Test
        @DisplayName("find mode with COMMENTS")
        fun findModeWithComments() {
            val p = org.safere.Pattern.compile("\\d + \\. \\d +", org.safere.Pattern.COMMENTS)
            val m = p.matcher("price is 3.14 dollars")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("3.14")
        }

        @Test
        @DisplayName("replaceAll with COMMENTS")
        fun replaceAllWithComments() {
            val p = org.safere.Pattern.compile("\\s +", org.safere.Pattern.COMMENTS)
            val result = p.matcher("hello   world").replaceAll(" ")
            Assertions.assertThat(result).isEqualTo("hello world")
        }
    }
}
