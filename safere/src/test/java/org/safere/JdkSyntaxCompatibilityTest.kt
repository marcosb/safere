// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.regex.PatternSyntaxException
import java.util.stream.Stream

/**
 * Systematic test coverage for every syntax feature documented in the JDK 21 [ ] Javadoc.
 * 
 * 
 * For each feature, verifies that:
 * 
 * 
 *  * JDK accepts the pattern (sanity check)
 *  * SafeRE compiles it and produces the same match result, OR
 *  * SafeRE intentionally rejects it (for features that violate linear-time guarantees)
 * 
 * 
 * 
 * Refs: [#131](https://github.com/safere/safere/issues/131), [#127](https://github.com/safere/safere/issues/127)
 */
@DisplayName("JDK syntax compatibility")
internal class JdkSyntaxCompatibilityTest {
    @Test
    fun scopedUnixLinesControlsEndAnchorTerminators() {
        // JDK 26 Pattern specifies scoped flag restoration and the standard line terminator set.
        // Refs #708.
        for (anchor in mutableListOf<String>("$", "\\Z")) {
            for (terminator in mutableListOf<String>("\r", "\r\n", "\u0085", "\u2028", "\u2029")) {
                val input = "foo" + terminator
                Assertions.assertThat(
                    Pattern.compile("foo(?-d:" + anchor + ")", Pattern.UNIX_LINES)
                        .matcher(input)
                        .find()
                )
                    .isTrue()
                Assertions.assertThat(Pattern.compile("foo(?d:" + anchor + ")").matcher(input).find()).isFalse()
                Assertions.assertThat(
                    Pattern.compile("foo(?-d:" + anchor + ")" + anchor, Pattern.UNIX_LINES)
                        .matcher(input)
                        .find()
                )
                    .isFalse()
            }
        }
    }

    @Test
    fun scopedLineEndsDoNotReuseCrLfContextForStandaloneLf() {
        val pattern = Pattern.compile("(?-d:(?m:$))(?dm:$)", Pattern.UNIX_LINES)
        for (input in mutableListOf<String>("\r\na\na", "\na\r\na", "\r\na\na")) {
            val matcher = pattern.matcher(input)
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(if (input!!.get(0) == '\r') 3 else 0)
            Assertions.assertThat(matcher.end()).isEqualTo(matcher.start())
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(5)
            Assertions.assertThat(matcher.end()).isEqualTo(5)
            Assertions.assertThat(matcher.find()).isFalse()
        }
    }

    @JvmRecord
    internal data class SyntaxFamilyCase(
        val family: String?,
        val acceptedRegex: String?,
        val matchingInput: String?,
        val nonMatchingInput: String?
    )

    @JvmRecord
    internal data class DialectRejection(val family: String?, val regex: String?)

    @JvmRecord
    internal data class DialectLiteralCase(val family: String?, val regex: String?, val input: String?)

    @JvmRecord
    internal data class CharacterClassMembershipCase(val regex: String?, val inputs: MutableList<String>?)

    @JvmRecord
    internal data class EscapeMembershipCase(val regex: String?, val inputs: MutableList<String>?)

    @JvmRecord
    private data class CharacterClassMatrixOutcome(val accepted: Boolean, val matches: String?)

    @Nested
    @DisplayName("Syntax-family compatibility matrix")
    internal class SyntaxFamilyCompatibilityMatrix {
        @ParameterizedTest(name = "/{0}/")
        @MethodSource("quantifiedBareQuestionGroups")
        @DisplayName("bare question groups reject following quantifiers like JDK")
        fun bareQuestionGroupsRejectFollowingQuantifiersLikeJdk(regex: String) {
            assertRejectedByJdkAndSafeRe(regex)
        }

        @ParameterizedTest(name = "/{0}/ matches \"{1}\" = {2}")
        @MethodSource("countedBareQuestionGroups")
        @DisplayName("bare question groups accept counted repeats like JDK")
        fun bareQuestionGroupsAcceptCountedRepeatsLikeJdk(
            regex: String, input: String, expected: Boolean
        ) {
            assertMatchesFull(regex, input)
            Assertions.assertThat(Pattern.compile(regex).matcher(input).matches()).isEqualTo(expected)
        }

        @ParameterizedTest(name = "/{0}/ flags={1} matches \"{2}\"")
        @MethodSource("leadingCountedRepeats")
        @DisplayName("leading counted repeats apply to the empty expression like JDK")
        fun leadingCountedRepeatsApplyToEmptyExpressionLikeJdk(regex: String, flags: Int, input: String) {
            val jdkPattern = java.util.regex.Pattern.compile(regex, flags)
            val safeRePattern = org.safere.Pattern.compile(regex, flags)

            Assertions.assertThat(safeRePattern.matcher(input).find())
                .`as`("SafeRE find() result for /%s/ flags=%d on \"%s\"", regex, flags, input)
                .isEqualTo(jdkPattern.matcher(input).find())
            Assertions.assertThat(safeRePattern.matcher(input).matches())
                .`as`("SafeRE matches() result for /%s/ flags=%d on \"%s\"", regex, flags, input)
                .isEqualTo(jdkPattern.matcher(input).matches())
        }

        @ParameterizedTest(name = "/{0}/ flags={1} matches \"{2}\"")
        @MethodSource("possessiveCountedRepeatsWithoutOperand")
        @DisplayName("possessive counted repeats without an operand apply to the empty expression")
        fun possessiveCountedRepeatsWithoutOperandMatchLikeJdk(regex: String, flags: Int, input: String) {
            val jdkPattern = java.util.regex.Pattern.compile(regex, flags)
            val safeRePattern = org.safere.Pattern.compile(regex, flags)

            Assertions.assertThat(safeRePattern.matcher(input).matches())
                .`as`("SafeRE matches() result for /%s/ flags=%d on \"%s\"", regex, flags, input)
                .isEqualTo(jdkPattern.matcher(input).matches())
                .isTrue()
        }

        @ParameterizedTest(name = "/{0}/")
        @ValueSource(strings = ["a{1}+", "b\\Q\\E{1}+a", "(a){1,2}+"])
        @DisplayName("possessive counted repeats over a consuming operand stay rejected")
        fun possessiveCountedRepeatsOverConsumingOperandAreRejected(regex: String?) {
            assertRejectedBySafeRe(regex)
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("acceptedSyntaxFamilies")
        @DisplayName("accepted syntax families compile and match like JDK")
        fun acceptedSyntaxFamiliesCompileAndMatchLikeJdk(syntaxCase: SyntaxFamilyCase) {
            JdkSyntaxCompatibilityTest.Companion.assertMatchesFull(
                syntaxCase.acceptedRegex!!,
                syntaxCase.matchingInput!!
            )
            JdkSyntaxCompatibilityTest.Companion.assertMatchesFull(
                syntaxCase.acceptedRegex,
                syntaxCase.nonMatchingInput!!
            )
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("nonJdkDialectSpellings")
        @DisplayName("non-JDK dialect spellings are rejected")
        fun nonJdkDialectSpellingsAreRejected(rejection: DialectRejection) {
            JdkSyntaxCompatibilityTest.Companion.assertRejectedByJdkAndSafeRe(rejection.regex!!)
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("generatedNonJdkDialectSpellings")
        @DisplayName("generated non-JDK dialect spellings are rejected")
        fun generatedNonJdkDialectSpellingsAreRejected(rejection: DialectRejection) {
            JdkSyntaxCompatibilityTest.Companion.assertRejectedByJdkAndSafeRe(rejection.regex!!)
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dialectLookingLiteralSpellings")
        @DisplayName("dialect-looking bracket spellings match JDK literal semantics")
        fun dialectLookingBracketSpellingsMatchJdkLiteralSemantics(literalCase: DialectLiteralCase) {
            JdkSyntaxCompatibilityTest.Companion.assertMatchesFull(literalCase.regex!!, literalCase.input!!)
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("malformedJdkSyntax")
        @DisplayName("malformed JDK syntax is rejected")
        fun malformedJdkSyntaxIsRejected(rejection: DialectRejection) {
            JdkSyntaxCompatibilityTest.Companion.assertRejectedByJdkAndSafeRe(rejection.regex!!)
        }

        @ParameterizedTest(name = "/{0}/ flags={1}")
        @MethodSource("commentTerminatorCases")
        @DisplayName("comments-mode comment terminators expose malformed group syntax")
        fun commentsModeCommentTerminatorsExposeMalformedGroupSyntax(regex: String, flags: Int) {
            assertRejectedByJdkAndSafeRe(regex, flags)
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("unsupportedNonRegularJdkSyntax")
        @DisplayName("JDK syntax outside SafeRE's linear-time subset is rejected")
        fun unsupportedNonRegularJdkSyntaxIsRejected(rejection: DialectRejection) {
            Assertions.assertThatNoException()
                .`as`("JDK should accept: %s", rejection.regex)
                .isThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(rejection.regex) })
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(rejection.regex) })
                .`as`("SafeRE should reject unsupported syntax: %s", rejection.regex)
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        companion object {
            @JvmStatic
            fun quantifiedBareQuestionGroups(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of("(?)?"),
                    Arguments.of("(?)*"),
                    Arguments.of("(?)+"),
                    Arguments.of("()??(?)?"),
                    Arguments.of("()?(?)?"),
                    Arguments.of("()*?(?)?"),
                    Arguments.of("()+?(?)?"),
                    Arguments.of("(a)??(?)?"),
                    Arguments.of("a??(?)?"),
                    Arguments.of("()??|(?)?")
                )
            }

            @JvmStatic
            fun countedBareQuestionGroups(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of("(?){2}", "", true),
                    Arguments.of("a(?){2}", "a", true),
                    Arguments.of("a(?){2}", "aa", false),
                    Arguments.of("()??(?){2}", "", true),
                    Arguments.of("a(?){2}?", "a", true),
                    Arguments.of("(?){1}{1}", "", true)
                )
            }

            @JvmStatic
            fun leadingCountedRepeats(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of("{1}", 0, ""),
                    Arguments.of("{1,3}", 0, ""),
                    Arguments.of(
                        "{1,}$",
                        (org.safere.Pattern.CASE_INSENSITIVE
                                or org.safere.Pattern.COMMENTS
                                or org.safere.Pattern.UNICODE_CASE
                                or org.safere.Pattern.UNICODE_CHARACTER_CLASS),
                        "abc"
                    ),
                    Arguments.of(
                        "{1,3}$",
                        (org.safere.Pattern.CASE_INSENSITIVE
                                or org.safere.Pattern.COMMENTS
                                or org.safere.Pattern.UNICODE_CASE
                                or org.safere.Pattern.UNICODE_CHARACTER_CLASS),
                        "abc"
                    ),
                    Arguments.of("a|{1,3}", 0, ""),
                    Arguments.of("({1,3})", 0, ""),
                    Arguments.of("^{1,3}", 0, "")
                )
            }

            @JvmStatic
            fun possessiveCountedRepeatsWithoutOperand(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of("{1}+a", 0, "a"),
                    Arguments.of("{0}+a", 0, "a"),
                    Arguments.of("{1,2}+a", 0, "a"),
                    Arguments.of("\\Q\\E{1}+a", 0, "a"),
                    Arguments.of("(?i){1}+a", 0, "A"),
                    Arguments.of("({1}+a)", 0, "a"),
                    Arguments.of("(?:{2,3}+)a", 0, "a"),
                    Arguments.of("x|{1}+a", 0, "a"),
                    Arguments.of("a*{1}+", 0, "aa"),
                    Arguments.of("a*{1}+a", 0, "aa"),
                    Arguments.of("a*?{1}+a", 0, "aa"),
                    Arguments.of("a{2}{1}+", 0, "aa"),
                    Arguments.of("\\Q\\E\\Q\\E{1} #q\n +\\P{Lower}", org.safere.Pattern.COMMENTS, "A")
                )
            }

            @JvmStatic
            fun acceptedSyntaxFamilies(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of(SyntaxFamilyCase("literal characters", "abc", "abc", "ab")),
                    Arguments.of(SyntaxFamilyCase("quoting", "\\Q.+*\\E", ".+*", "abc")),
                    Arguments.of(SyntaxFamilyCase("escaped non-ASCII literals", "\\©", "©", "c")),
                    Arguments.of(SyntaxFamilyCase("control escapes", "\\t", "\t", "t")),
                    Arguments.of(SyntaxFamilyCase("octal escapes", "\\041", "!", "1")),
                    Arguments.of(SyntaxFamilyCase("hex escapes", "\\x41", "A", "x41")),
                    Arguments.of(SyntaxFamilyCase("Unicode escapes", "\\u0041", "A", "u0041")),
                    Arguments.of(
                        SyntaxFamilyCase("named characters", "\\N{LATIN SMALL LETTER A}", "a", "A")
                    ),
                    Arguments.of(SyntaxFamilyCase("predefined classes", "\\d", "7", "a")),
                    Arguments.of(
                        SyntaxFamilyCase("Java character classes", "\\p{javaLowerCase}", "a", "A")
                    ),
                    Arguments.of(SyntaxFamilyCase("POSIX property escapes", "\\p{Lower}", "a", "A")),
                    Arguments.of(SyntaxFamilyCase("POSIX bracket fragments", "[[:lower:]]", "l", "a")),
                    Arguments.of(SyntaxFamilyCase("Unicode scripts", "\\p{IsLatin}", "A", "1")),
                    Arguments.of(SyntaxFamilyCase("Unicode blocks", "\\p{InGreek}", "\u0391", "A")),
                    Arguments.of(SyntaxFamilyCase("Unicode categories", "\\p{Lu}", "A", "a")),
                    Arguments.of(
                        SyntaxFamilyCase("Unicode binary properties", "\\p{IsAlphabetic}", "a", "1")
                    ),
                    Arguments.of(SyntaxFamilyCase("class union", "[a-d[m-p]]", "m", "z")),
                    Arguments.of(SyntaxFamilyCase("class intersection", "[a-z&&[def]]", "d", "a")),
                    Arguments.of(SyntaxFamilyCase("class subtraction", "[a-z&&[^bc]]", "a", "b")),
                    Arguments.of(SyntaxFamilyCase("capturing groups", "(ab)+", "abab", "aba")),
                    Arguments.of(SyntaxFamilyCase("named groups", "(?<word>\\w+)", "abc", "!")),
                    Arguments.of(SyntaxFamilyCase("non-capturing groups", "(?:ab)+", "abab", "aba")),
                    Arguments.of(SyntaxFamilyCase("greedy quantifiers", "ab*c", "abbc", "abdc")),
                    Arguments.of(SyntaxFamilyCase("lazy quantifiers", "a.*?c", "abc", "ab")),
                    Arguments.of(SyntaxFamilyCase("bounded quantifiers", "a{2,4}", "aaa", "a")),
                    Arguments.of(SyntaxFamilyCase("boundary matchers", "\\bword\\b", "word", "sword")),
                    Arguments.of(SyntaxFamilyCase("linebreak matcher", "\\R", "\r\n", "a")),
                    Arguments.of(SyntaxFamilyCase("comments flag", "(?x)a b c # comment", "abc", "ab")),
                    Arguments.of(SyntaxFamilyCase("embedded flags", "(?i:abc)def", "ABCdef", "ABCDEF"))
                )
            }

            @JvmStatic
            fun nonJdkDialectSpellings(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of(DialectRejection("PCRE single-quoted named capture", "(?'word'a)")),
                    Arguments.of(DialectRejection("Python named backreference", "(?P=word)")),
                    Arguments.of(DialectRejection("PCRE subroutine call", "(?&word)")),
                    Arguments.of(DialectRejection("PCRE branch-reset group", "(?|a)")),
                    Arguments.of(DialectRejection("PCRE conditional group", "(?(1)a|b)")),
                    Arguments.of(DialectRejection("PCRE inline comment", "(?#comment)a")),
                    Arguments.of(DialectRejection("PCRE keep-out escape", "\\K")),
                    Arguments.of(DialectRejection("PCRE byte escape", "\\C")),
                    Arguments.of(DialectRejection("PCRE g-name backreference", "\\g{name}")),
                    Arguments.of(DialectRejection("PCRE g-number backreference", "\\g1")),
                    Arguments.of(DialectRejection("bare script property", "\\p{Latin}")),
                    Arguments.of(DialectRejection("bare binary property", "\\p{Alphabetic}")),
                    Arguments.of(DialectRejection("lowercase category", "\\p{lu}")),
                    Arguments.of(DialectRejection("PCRE assigned property", "\\p{Assigned}")),
                    Arguments.of(DialectRejection("PCRE any property", "\\p{Any}")),
                    Arguments.of(DialectRejection("PCRE letter-and-mark category", "\\p{L&}")),
                    Arguments.of(DialectRejection("invalid numeric class escape", "[\\123]"))
                )
            }

            @JvmStatic
            fun generatedNonJdkDialectSpellings(): Stream<Arguments?> {
                val groupSpellings: MutableList<DialectRejection?> =
                    java.util.List.of<DialectRejection?>(
                        DialectRejection("PCRE single-quoted named capture", "(?'name'a)"),
                        DialectRejection("Python named backreference", "(?P=name)"),
                        DialectRejection("PCRE subroutine call", "(?&name)"),
                        DialectRejection("PCRE branch-reset group", "(?|a)"),
                        DialectRejection("PCRE conditional group", "(?(1)a|b)"),
                        DialectRejection("PCRE inline comment", "(?#comment)a")
                    )
                val escapeSpellings: MutableList<DialectRejection?> =
                    java.util.List.of<DialectRejection?>(
                        DialectRejection("PCRE keep-out escape", "\\K"),
                        DialectRejection("PCRE byte escape", "\\C"),
                        DialectRejection("PCRE g-name backreference", "\\g{name}"),
                        DialectRejection("PCRE g-number backreference", "\\g1")
                    )
                val wrappers = mutableListOf<String>("%s", "(?:%s)", "%s|z", "z%s")

                val groups =
                    groupSpellings.stream()
                        .flatMap<Arguments?> { rejection: DialectRejection? ->
                            wrappers.stream()
                                .map<Arguments?> { wrapper: String? ->
                                    Arguments.of(
                                        DialectRejection(
                                            rejection!!.family,
                                            String.format(wrapper!!, rejection.regex)
                                        )
                                    )
                                }
                        }
                val escapes =
                    escapeSpellings.stream()
                        .flatMap<Arguments?> { rejection: DialectRejection? ->
                            wrappers.stream()
                                .map<Arguments?> { wrapper: String? ->
                                    Arguments.of(
                                        DialectRejection(
                                            rejection!!.family,
                                            String.format(wrapper!!, rejection.regex)
                                        )
                                    )
                                }
                        }
                return Stream.concat<Arguments?>(groups, escapes)
            }

            @JvmStatic
            fun dialectLookingLiteralSpellings(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of(DialectLiteralCase("POSIX collating element spelling", "[.ch.]", ".")),
                    Arguments.of(DialectLiteralCase("POSIX collating element spelling", "[.ch.]", "c")),
                    Arguments.of(DialectLiteralCase("POSIX equivalence class spelling", "[=a=]", "=")),
                    Arguments.of(DialectLiteralCase("POSIX equivalence class spelling", "[=a=]", "a")),
                    Arguments.of(DialectLiteralCase("POSIX word-start bracket spelling", "[[:<:]]", "<")),
                    Arguments.of(DialectLiteralCase("POSIX word-end bracket spelling", "[[:>:]]", ">"))
                )
            }

            @JvmStatic
            fun malformedJdkSyntax(): Stream<Arguments?> {
                // Trailing hyphens across COMMENTS trivia intentionally differ from the JDK (#1070).
                // CharacterClassRangeTriviaTest covers their acceptance and membership separately.
                return Stream.of<Arguments?>(
                    Arguments.of(DialectRejection("unterminated character class", "[abc")),
                    Arguments.of(DialectRejection("empty character class", "[]")),
                    Arguments.of(DialectRejection("negated empty character class", "[^]")),
                    Arguments.of(DialectRejection("unclosed named character escape", "\\N{A")),
                    Arguments.of(DialectRejection("unknown named character escape", "\\N{NO SUCH}")),
                    Arguments.of(DialectRejection("unclosed braced hex escape", "\\x{41")),
                    Arguments.of(
                        DialectRejection("braced hex escape above Unicode range", "\\x{110000}")
                    ),
                    Arguments.of(DialectRejection("malformed Unicode escape", "\\u123")),
                    Arguments.of(DialectRejection("unknown character property", "\\p{NoSuch}")),
                    Arguments.of(DialectRejection("unclosed character property", "\\p{Lower")),
                    Arguments.of(DialectRejection("empty character property", "\\p{}")),
                    Arguments.of(DialectRejection("unknown flag", "(?q)a")),
                    Arguments.of(DialectRejection("unterminated scoped flag group", "(?i:a")),
                    Arguments.of(DialectRejection("unmatched close group", "a)")),
                    Arguments.of(DialectRejection("unterminated group", "(a")),
                    Arguments.of(DialectRejection("dangling alternation group opener", "(|")),
                    Arguments.of(DialectRejection("unterminated group after alternation", "a|(")),
                    Arguments.of(DialectRejection("unterminated group after empty alternation", "|(")),
                    Arguments.of(
                        DialectRejection("unterminated group after nested alternation", "(a|b)|(")
                    ),
                    Arguments.of(DialectRejection("nothing to repeat star", "*a")),
                    Arguments.of(DialectRejection("malformed bounded quantifier", "a{2,1}")),
                    Arguments.of(DialectRejection("bare leading class intersection", "[&&]")),
                    Arguments.of(DialectRejection("bare negated leading class intersection", "[^&&]")),
                    Arguments.of(
                        DialectRejection(
                            "solitary ampersand after leading class intersection", "[&&&b]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "solitary ampersand after negated leading class intersection", "[^&&&b]"
                        )
                    ),
                    Arguments.of(DialectRejection("repeated leading class intersection", "[&&&&b]")),
                    Arguments.of(
                        DialectRejection("repeated negated leading class intersection", "[^&&&&b]")
                    ),
                    Arguments.of(
                        DialectRejection(
                            "comments-mode spaced bare leading class intersection", "(?x)[&&  ]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "comments-mode commented bare leading class intersection", "(?x)[&& #x\n ]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "comments-mode spaced solitary ampersand after leading class intersection",
                            "(?x)[&&  &b]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "comments-mode spaced repeated leading class intersection", "(?x)[&&  &&b]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "comments-mode odd leading intersection before nested class", "(?x)[&&& [a]]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "leading intersection followed by zero-width syntax only", "[&&\\Q\\E]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "leading intersection followed by zero-width syntax and repeated marker",
                            "[&&\\Q\\E&&a]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "comments-mode leading intersection followed by zero-width syntax "
                                    + "and repeated marker",
                            "(?x)[&& \\Q\\E&&a]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "comments-mode raw ampersand separator before class close",
                            "(?x)[a& &&&& -a& ]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "comments-mode range tail raw ampersand before class close", "(?x)[a&&&-a& ]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "comments-mode escaped ampersand range tail raw ampersand before class close",
                            "(?x)[\\&&&&-a& ]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "comments-mode zero-width range tail raw ampersand before class close",
                            "(?x)[a&&&-a\\Q\\E& ]"
                        )
                    ),
                    Arguments.of(DialectRejection("range ending at nested class opener", "[a-[]")),
                    Arguments.of(
                        DialectRejection(
                            "leading intersection range ending at nested class opener", "[&&--[]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "negated leading intersection range ending at nested class opener", "[^&&--[]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "leading intersection range ending before nested class", "[&&--[x]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "comments-mode leading intersection range ending at nested class opener",
                            "(?x)[&&  --[]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "comments-mode leading intersection range ending before nested class",
                            "(?x)[&& #x\n --[x]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "intersection rhs range ending at nested class opener", "[a&&b-[]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "odd ampersand intersection run before malformed range", "[\\d&&&-\\D]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "odd ampersand intersection run before zero-width malformed range",
                            "[\\d&&&\\Q\\E-\\D]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "intersection RHS raw ampersand before malformed zero-width range",
                            "[a\\Q\\E&&\\Q\\E\\Q\\E&-\\D]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "quoted ampersand before intersection RHS malformed range",
                            "[\\Q&\\E&&\\Q\\E&-\\D]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "raw ampersand separator before malformed zero-width range",
                            "[[^b]&\\Q\\E\\Q\\E&&&&\\Q\\E-\\D]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "negated raw ampersand separator before malformed zero-width range",
                            "[^[^b]&\\Q\\E\\Q\\E&&&&\\Q\\E-\\D]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "negated raw ampersand separator before immediate malformed range",
                            "[^[^b]&\\Q\\E&&-\\D]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "comments-mode hyphen before spaced intersection after raw ampersand separator",
                            "(?x)[a& &&&& -& &]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "comments-mode hyphen before quoted-empty intersection after raw ampersand"
                                    + " separator",
                            "(?x)[a& &&&& -&\\Q\\E&]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "comments-mode hyphen before commented intersection after raw ampersand"
                                    + " separator",
                            "(?x)[a& &&&& -& #x\n&]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "comments-mode hyphen tail before dangling raw ampersand separator",
                            "(?x)[a& &&&& -a& ]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "comments-mode quoted-empty hyphen tail before dangling raw ampersand separator",
                            "(?x)[a& &&&& -a\\Q\\E& ]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "ordinary literal before trailing class intersection after nested class",
                            "[[a]b&&]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "ordinary literal before trailing class intersection after predefined class",
                            "[\\d0&&]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "quoted literal before trailing class intersection after nested class",
                            "[[a]\\Qa\\E&&]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection(
                            "quoted ampersand before trailing class intersection after nested class",
                            "[[a]\\Q&\\E&&]"
                        )
                    ),
                    Arguments.of(
                        DialectRejection("empty quoted class item has no terminator", "[\\Q\\E]")
                    )
                )
            }

            @JvmStatic
            fun commentTerminatorCases(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of("a#\u0000|(", java.util.regex.Pattern.COMMENTS),
                    Arguments.of("#\u0000(", java.util.regex.Pattern.COMMENTS),
                    Arguments.of("a#\u0000b|(", java.util.regex.Pattern.COMMENTS),
                    Arguments.of("a#\u0000(?:b)|(", java.util.regex.Pattern.COMMENTS),
                    Arguments.of("a#\r(", java.util.regex.Pattern.COMMENTS),
                    Arguments.of("a#\u0085(", java.util.regex.Pattern.COMMENTS),
                    Arguments.of("a#\u2028(", java.util.regex.Pattern.COMMENTS),
                    Arguments.of("a#\u2029(", java.util.regex.Pattern.COMMENTS),
                    Arguments.of(
                        "a#\u0000|(", java.util.regex.Pattern.COMMENTS or java.util.regex.Pattern.UNIX_LINES
                    )
                )
            }

            @JvmStatic
            fun unsupportedNonRegularJdkSyntax(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of(DialectRejection("backreference", "(a)\\1")),
                    Arguments.of(DialectRejection("multi-digit backreference", "(a)(b)\\12")),
                    Arguments.of(DialectRejection("named backreference", "(?<name>a)\\k<name>")),
                    Arguments.of(DialectRejection("positive lookahead", "a(?=b)")),
                    Arguments.of(DialectRejection("negative lookahead", "a(?!b)")),
                    Arguments.of(DialectRejection("positive lookbehind", "(?<=a)b")),
                    Arguments.of(DialectRejection("negative lookbehind", "(?<!a)b")),
                    Arguments.of(DialectRejection("bounded positive lookbehind", "(?<=a{1,3})b")),
                    Arguments.of(DialectRejection("independent noncapturing group", "(?>ab|a)")),
                    Arguments.of(DialectRejection("possessive question quantifier", "a?+")),
                    Arguments.of(DialectRejection("possessive star quantifier", "a*+")),
                    Arguments.of(DialectRejection("possessive quantifier", "a++")),
                    Arguments.of(DialectRejection("possessive bounded quantifier", "a{2,4}+")),
                    Arguments.of(DialectRejection("atomic group", "(?>a+)"))
                )
            }
        }
    }

    // ===========================================================================
    // 1. Characters & Escapes
    // ===========================================================================
    @Nested
    @DisplayName("Characters and escapes")
    internal class CharactersAndEscapes {
        @Test
        @DisplayName("literal character")
        fun literalCharacter() {
            assertMatchesSame("x", "x")
        }

        @Test
        @DisplayName("escaped backslash: \\\\")
        fun escapedBackslash() {
            assertMatchesSame("\\\\", "a\\b")
        }

        @ParameterizedTest
        @ValueSource(strings = ["\\©", "\\о", "\\\uD83D\uDE00"])
        @DisplayName("escaped non-ASCII character is literal")
        fun escapedNonAsciiCharacterIsLiteral(regex: String) {
            val literal = regex.substring(1)
            assertMatchesFull(regex, literal)
            assertMatchesFull(regex, "x")
        }

        @ParameterizedTest
        @ValueSource(strings = ["[\\©]", "[\\о]", "[\\\uD83D\uDE00]"])
        @DisplayName("escaped non-ASCII character in class is literal")
        fun escapedNonAsciiCharacterInClassIsLiteral(regex: String) {
            val literal = regex.substring(2, regex.length - 1)
            assertMatchesFull(regex, literal)
            assertMatchesFull(regex, "x")
        }

        // -- Octal escapes --
        @Test
        @DisplayName("octal \\\\0n (single digit)")
        fun octalSingleDigit() {
            assertMatchesSame("\\07", "\u0007") // bell
        }

        @Test
        @DisplayName("octal \\\\0nn (two digits)")
        fun octalTwoDigits() {
            assertMatchesSame("\\041", "!") // 041 octal = 33 = '!'
        }

        @Test
        @DisplayName("octal \\\\0mnn (three digits)")
        fun octalThreeDigits() {
            assertMatchesSame("\\0101", "A") // 0101 octal = 65 = 'A'
        }

        @Test
        @DisplayName("octal \\\\0mnn above 0377 stops before overflow")
        fun octalStopsBeforeOverflow() {
            assertMatchesSame("\\0400", " 0")
        }

        @ParameterizedTest
        @ValueSource(strings = ["\\0", "\\08"])
        @DisplayName("malformed leading-zero octal escape is rejected")
        fun malformedLeadingZeroOctalEscapeRejected(regex: String) {
            Assertions.assertThatThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex) })
                .`as`("JDK should reject malformed octal escape: %s", regex)
                .isInstanceOf(PatternSyntaxException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
                .`as`("SafeRE should reject malformed octal escape: %s", regex)
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @ParameterizedTest
        @ValueSource(
            strings = ["\\1", "\\9", "\\12", "\\123", "\\400", "\\777", "\\1*", "\\12*", "\\123*", "\\2222*", "3*\\2222*"
            ]
        )
        @DisplayName("non-zero numeric escapes are unsupported backreference syntax")
        fun nonZeroNumericEscapesAreUnsupportedBackreferenceSyntax(regex: String) {
            Assertions.assertThatNoException()
                .`as`("JDK should accept numeric back reference syntax: %s", regex)
                .isThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex) })
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
                .`as`("SafeRE should reject numeric back reference syntax: %s", regex)
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @ParameterizedTest
        @ValueSource(strings = ["[\\123]", "[\\400]"])
        @DisplayName("non-zero numeric escapes in character classes are rejected")
        fun nonZeroNumericEscapesInCharacterClassesRejected(regex: String) {
            Assertions.assertThatThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex) })
                .`as`("JDK should reject non-zero numeric escape in class: %s", regex)
                .isInstanceOf(PatternSyntaxException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
                .`as`("SafeRE should reject non-zero numeric escape in class: %s", regex)
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("generatedNumericAndOctalEscapeCases")
        @DisplayName("generated numeric and octal escape cases match JDK")
        fun generatedNumericAndOctalEscapeCasesMatchJdk(escapeCase: EscapeMembershipCase) {
            JdkSyntaxCompatibilityTest.Companion.assertFullMatchesSameForAll(escapeCase.regex!!, escapeCase.inputs!!)
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("generatedMalformedEscapeCases")
        @DisplayName("generated malformed escape cases are rejected")
        fun generatedMalformedEscapeCasesAreRejected(rejection: DialectRejection) {
            JdkSyntaxCompatibilityTest.Companion.assertRejectedByJdkAndSafeRe(rejection.regex!!)
        }

        // -- Hex escapes --
        @Test
        @DisplayName("hex \\\\xhh")
        fun hexTwoDigit() {
            assertMatchesSame("\\x41", "A")
        }

        @Test
        @DisplayName("hex \\\\x{h...h} (BMP)")
        fun hexBracedBmp() {
            assertMatchesSame("\\x{41}", "A")
        }

        @Test
        @DisplayName("hex \\\\x{h...h} (supplementary)")
        fun hexBracedSupplementary() {
            // U+1F600 = grinning face emoji
            assertMatchesSame("\\x{1F600}", "\uD83D\uDE00")
        }

        // -- Unicode escape (backslash-u) --
        @Test
        @DisplayName("unicode escape \\\\uhhhh (BMP)")
        fun unicodeEscapeBmp() {
            assertMatchesSame("\\u0041", "A")
        }

        @Test
        @DisplayName("unicode escape \\\\uhhhh (Thai character)")
        fun unicodeEscapeThai() {
            assertMatchesSame("\\u0E01", "\u0E01")
        }

        @Test
        @DisplayName("unicode escape range in character class")
        fun unicodeEscapeRange() {
            assertMatchesSame("[\\u0E00-\\u0E7F]", "\u0E01")
        }

        @Test
        @DisplayName("unicode escape \\\\uhhhh (supplementary via surrogate pair)")
        fun unicodeEscapeSurrogatePair() {
            // JDK treats surrogate pair escapes as U+1F600
            assertMatchesSame("\\uD83D\\uDE00", "\uD83D\uDE00")
        }

        // -- Named Unicode character --
        @Test
        @DisplayName("named unicode character \\\\N{name}")
        fun namedUnicodeCharacter() {
            assertMatchesSame("\\N{WHITE SMILING FACE}", "\u263A")
        }

        @Test
        @DisplayName("named unicode character \\\\N{name} (Latin letter)")
        fun namedUnicodeCharacterLatin() {
            assertMatchesSame("\\N{LATIN SMALL LETTER A}", "a")
        }

        // -- C escapes --
        @Test
        @DisplayName("tab \\\\t")
        fun tabEscape() {
            assertMatchesSame("\\t", "\t")
        }

        @Test
        @DisplayName("newline \\\\n")
        fun newlineEscape() {
            assertMatchesSame("\\n", "\n")
        }

        @Test
        @DisplayName("carriage return \\\\r")
        fun crEscape() {
            assertMatchesSame("\\r", "\r")
        }

        @Test
        @DisplayName("form feed \\\\f")
        fun formFeedEscape() {
            assertMatchesSame("\\f", "\u000c")
        }

        @Test
        @DisplayName("alert/bell \\\\a")
        fun alertEscape() {
            assertMatchesSame("\\a", "\u0007")
        }

        @Test
        @DisplayName("escape \\\\e")
        fun escapeEscape() {
            assertMatchesSame("\\e", "\u001B")
        }

        // -- Control character --
        @Test
        @DisplayName("control character \\\\cA")
        fun controlCharA() {
            assertMatchesSame("\\cA", "\u0001")
        }

        @Test
        @DisplayName("control character \\\\cZ")
        fun controlCharZ() {
            assertMatchesSame("\\cZ", "\u001A")
        }

        @Test
        @DisplayName("control character \\\\cM (carriage return)")
        fun controlCharM() {
            assertMatchesSame("\\cM", "\r")
        }

        @ParameterizedTest(name = "/{0}/ matches \"{1}\"")
        @MethodSource("nonAsciiControlEscapes")
        @DisplayName("control escapes accept non-ASCII targets like JDK")
        fun controlEscapesAcceptNonAsciiTargetsLikeJdk(regex: String, input: String) {
            assertMatchesSame(regex, input)
        }

        @ParameterizedTest(name = "/{0}/ matches \"{1}\"")
        @MethodSource("commentsModeControlEscapesSkipTriviaBeforeTarget")
        @DisplayName("comments-mode control escapes skip trivia before target like JDK")
        fun commentsModeControlEscapesSkipTriviaBeforeTargetLikeJdk(regex: String, input: String) {
            assertMatchesFull(regex, input)
        }

        @ParameterizedTest
        @ValueSource(strings = ["(?x)\\c\t", "(?x)\\c#", "(?x)[\\c\t]", "(?x)[^\\c#]"])
        @DisplayName("comments-mode control escapes reject when skipped trivia leaves no valid target")
        fun commentsModeControlEscapesRejectWhenSkippedTriviaLeavesNoValidTarget(regex: String) {
            assertRejectedByJdkAndSafeRe(regex)
        }

        @ParameterizedTest(name = "/{0}/ matches \"{1}\" = {2}")
        @MethodSource("controlEscapeNegatedClassesIncludeLoneSurrogates")
        @DisplayName("control escape character classes handle lone surrogates like JDK")
        fun controlEscapeCharacterClassesHandleLoneSurrogatesLikeJdk(
            regex: String, input: String, expected: Boolean
        ) {
            val jdkM = java.util.regex.Pattern.compile(regex).matcher(input)
            Assertions.assertThat(jdkM.matches()).`as`("JDK sanity for /%s/", regex).isEqualTo(expected)

            val safeM = org.safere.Pattern.compile(regex).matcher(input)
            Assertions.assertThat(safeM.matches()).`as`("SafeRE matches() for /%s/", regex).isEqualTo(expected)
        }

        companion object {
            @JvmStatic
            fun generatedNumericAndOctalEscapeCases(): Stream<Arguments?> {
                val inputs: MutableList<String> = mutableListOf<String>(
                    "", "\u0000", "\u0001", "\u0007", "\b", "\t", "\n", " ", "!", "A", "S", "\u007f",
                    "\u00ff", "\u0100", "00", " 0", "?7", "123"
                )
                return Stream.of<Arguments?>(
                    Arguments.of(EscapeMembershipCase("\\00", inputs)),
                    Arguments.of(EscapeMembershipCase("\\000", inputs)),
                    Arguments.of(EscapeMembershipCase("\\01", inputs)),
                    Arguments.of(EscapeMembershipCase("\\001", inputs)),
                    Arguments.of(EscapeMembershipCase("\\07", inputs)),
                    Arguments.of(EscapeMembershipCase("\\077", inputs)),
                    Arguments.of(EscapeMembershipCase("\\0100", inputs)),
                    Arguments.of(EscapeMembershipCase("\\0377", inputs)),
                    Arguments.of(EscapeMembershipCase("\\0400", inputs)),
                    Arguments.of(EscapeMembershipCase("[\\00]", inputs)),
                    Arguments.of(EscapeMembershipCase("[\\000]", inputs)),
                    Arguments.of(EscapeMembershipCase("[\\01]", inputs)),
                    Arguments.of(EscapeMembershipCase("[\\07]", inputs)),
                    Arguments.of(EscapeMembershipCase("[\\077]", inputs)),
                    Arguments.of(EscapeMembershipCase("[\\0100]", inputs)),
                    Arguments.of(EscapeMembershipCase("[\\0377]", inputs)),
                    Arguments.of(EscapeMembershipCase("[\\0400]", inputs))
                )
            }

            @JvmStatic
            fun generatedMalformedEscapeCases(): Stream<Arguments?>? {
                return Stream.of<String?>(
                    "\\0",
                    "\\08",
                    "\\09",
                    "\\x",
                    "\\xG0",
                    "\\x{}",
                    "\\x{110000}",
                    "\\u",
                    "\\u0",
                    "\\u00",
                    "\\u000",
                    "\\u000G",
                    "\\N{}",
                    "\\N{NO SUCH CHARACTER}",
                    "[\\0]",
                    "[\\08]",
                    "[\\09]",
                    "[\\400]",
                    "[\\777]",
                    "[\\123]",
                    "[\\x]",
                    "[\\x{}]",
                    "[\\x{110000}]",
                    "[\\u000]",
                    "[\\N{}]"
                )
                    .map<Arguments?> { regex: String? -> Arguments.of(DialectRejection("malformed escape", regex)) }
            }

            @JvmStatic
            fun nonAsciiControlEscapes(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of("\\c!", "a"),
                    Arguments.of("[\\c!]", "a"),
                    Arguments.of("\\c\uf53f", "\uf57f"),
                    Arguments.of("^\\c\uf53f", "\uf57f"),
                    Arguments.of("[\\c\uf53f]", "\uf57f"),
                    Arguments.of("\\c\u0100", "\u0140"),
                    Arguments.of("[\\c\u0100]", "\u0140")
                )
            }

            @JvmStatic
            fun commentsModeControlEscapesSkipTriviaBeforeTarget(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of("(?x)\\c\ta", "!"),
                    Arguments.of("(?x)^\\c\t$", "d"),
                    Arguments.of("(?x)\\c\t?", "\u007f"),
                    Arguments.of("(?x)\\c#\na", "!")
                )
            }

            @JvmStatic
            fun controlEscapeNegatedClassesIncludeLoneSurrogates(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of("[^\\c\uD800]", "\uD840", false),
                    Arguments.of("[^\\c\uD800]", "\uD800", true),
                    Arguments.of("[^\\c\ud7bf]", "\uD800", true),
                    Arguments.of("[\\c\uD800]", "\uD840", true)
                )
            }
        }
    }

    // ===========================================================================
    // 2. Predefined Character Classes
    // ===========================================================================
    @Nested
    @DisplayName("Predefined character classes")
    internal inner class PredefinedCharacterClasses {
        @Test
        @DisplayName("dot matches non-newline")
        fun dot() {
            assertMatchesSame(".", "a")
            assertMatchesSame(".", "\r")
        }

        @Test
        @DisplayName("\\\\d matches digit")
        fun digitClass() {
            assertMatchesSame("\\d", "5")
            assertMatchesFull("\\d", "a") // should not match
        }

        @Test
        @DisplayName("\\\\D matches non-digit")
        fun nonDigitClass() {
            assertMatchesSame("\\D", "a")
            assertMatchesFull("\\D", "5") // should not match
        }

        @Test
        @DisplayName("\\\\h matches horizontal whitespace")
        fun horizontalWhitespace() {
            assertMatchesSame("\\h", " ")
            assertMatchesSame("\\h", "\t")
            assertMatchesFull("\\h", "a") // should not match
        }

        @Test
        @DisplayName("\\\\H matches non-horizontal whitespace")
        fun nonHorizontalWhitespace() {
            assertMatchesSame("\\H", "a")
            assertMatchesFull("\\H", " ") // should not match
        }

        @Test
        @DisplayName("\\\\s matches whitespace")
        fun whitespaceClass() {
            assertMatchesSame("\\s", " ")
            assertMatchesSame("\\s", "\n")
            assertMatchesSame("\\s", "\u000B")
            assertMatchesFull("\\s", "a") // should not match
        }

        @Test
        @DisplayName("\\\\S matches non-whitespace")
        fun nonWhitespaceClass() {
            assertMatchesSame("\\S", "a")
            assertMatchesFull("\\S", " ") // should not match
            assertMatchesFull("\\S", "\u000B") // should not match
        }

        @Test
        @DisplayName("\\\\v matches vertical whitespace")
        fun verticalWhitespace() {
            assertMatchesSame("\\v", "\n")
            assertMatchesSame("\\v", "\u000B")
            assertMatchesSame("\\v", "\u000c")
            assertMatchesSame("\\v", "\r")
            assertMatchesFull("\\v", " ") // should not match
        }

        @ParameterizedTest(name = "/{0}/")
        @ValueSource(
            strings = ["[\\v-z]", "[^\\v-z]", "[\\v-]", "[\\v-[a]]", "[\\v-\\v]", "[\\v-\\x0c]", "[\\t-\\v]", "[\\Q\\E\\v-z]", "[\\v\\Q\\E-z]", "[\\v-\\Q\\Ez]", "(?x)[\\v- z]", "(?x)[\\t- \\v]"
            ]
        )
        @DisplayName("\\\\v next to a class range hyphen is rejected (intentional divergence)")
        fun verticalTabNextToClassRangeHyphenIsRejected(regex: String) {
            // JDK Pattern reads \v as U+000B, not vertical whitespace, when the next character is '-' or
            // when \v ends a range. That rule is undocumented, so SafeRE rejects these spellings rather
            // than silently matching something different. See INTENTIONAL_DIVERGENCES.md.
            Assertions.assertThatNoException()
                .`as`("JDK should accept: %s", regex)
                .isThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex) })
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
                .isInstanceOf(PatternSyntaxException::class.java)
                .hasMessageContaining("\\x0B")
                .hasMessageContaining("\\-")
        }

        @ParameterizedTest(name = "/{0}/")
        @ValueSource(
            strings = ["[\\v]", "[a\\v]", "[\\v\\Q-\\Ez]", "(?x)[\\v -z]", "\\v-z", "[\\x0B-z]" // The spellings the error message suggests for keeping \v as vertical whitespace.
                , "[\\v\\-z]", "[\\v\\-]", "[\\t\\-\\v]"
            ]
        )
        @DisplayName("\\\\v away from a class range hyphen matches like JDK")
        fun verticalTabAwayFromClassRangeHyphenMatchesLikeJdk(regex: String) {
            Companion.assertFullMatchesSameForAll(
                regex, mutableListOf<String>("a", "z", "-", "\t", "\n", "\u000B", "\u000c", "\u0085", "\u2028", "\n-z")
            )
        }

        @Test
        @DisplayName("\\\\V matches non-vertical whitespace")
        fun nonVerticalWhitespace() {
            assertMatchesSame("\\V", "a")
            assertMatchesFull("\\V", "\n") // should not match
        }

        @Test
        @DisplayName("\\\\w matches word character")
        fun wordClass() {
            assertMatchesSame("\\w", "a")
            assertMatchesSame("\\w", "5")
            assertMatchesSame("\\w", "_")
            assertMatchesFull("\\w", "!") // should not match
        }

        @Test
        @DisplayName("\\\\W matches non-word character")
        fun nonWordClass() {
            assertMatchesSame("\\W", "!")
            assertMatchesFull("\\W", "a") // should not match
        }

        @Test
        @DisplayName("\\\\R matches linebreak sequence")
        fun linebreakMatcher() {
            assertMatchesSame("\\R", "\n")
            assertMatchesSame("\\R", "\r\n")
            assertMatchesSame("\\R", "\r")
            assertMatchesSame("\\R", "\u0085")
            assertMatchesSame("\\R", "\u2028")
            assertMatchesSame("\\R", "\u2029")
            assertMatchesFull("\\R", "a") // should not match
        }

        @Test
        @DisplayName("\\\\X matches extended grapheme cluster")
        fun extendedGraphemeCluster() {
            assertCompiles("\\X")
            // Basic: single character
            assertMatchesSame("\\X", "a")
            assertMatchesSame("\\X", "e\u0301")
            assertMatchesSame("\\X", "\uD83C\uDDFA\uD83C\uDDF8")
            assertMatchesSame("\\X", "\uD83D\uDC4D\uD83C\uDFFD")
            assertMatchesSame("\\X", "\uD83D\uDC69\u200D\uD83D\uDCBB")
            assertMatchesSame("\\X", "\uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDCBB")
            assertMatchesSame("\\X", "a\u200D")
            assertMatchesSame("\\X", "\u1100\u1161")
            assertMatchesSame("\\X", "\uAC00\u11A8")
            assertMatchesSame("\\X", "\u0600a")
        }
    }

    // ===========================================================================
    // 3. Character Classes
    // ===========================================================================
    @Nested
    @DisplayName("Character classes")
    internal class CharacterClasses {
        @Test
        @DisplayName("simple class [abc]")
        fun simpleClass() {
            assertMatchesSame("[abc]", "b")
            assertMatchesFull("[abc]", "d")
        }

        @Test
        @DisplayName("negation [^abc]")
        fun negation() {
            assertMatchesSame("[^abc]", "d")
            assertMatchesFull("[^abc]", "b")
        }

        @Test
        @DisplayName("range [a-zA-Z]")
        fun range() {
            assertMatchesSame("[a-zA-Z]", "m")
            assertMatchesFull("[a-zA-Z]", "5")
        }

        @Test
        @DisplayName("union [a-d[m-p]]")
        fun union() {
            assertMatchesSame("[a-d[m-p]]", "n")
            assertMatchesFull("[a-d[m-p]]", "f")
        }

        @Test
        @DisplayName("intersection [a-z&&[def]]")
        fun intersection() {
            assertMatchesSame("[a-z&&[def]]", "d")
            assertMatchesFull("[a-z&&[def]]", "a")
        }

        @ParameterizedTest
        @ValueSource(
            strings = ["(?x)[ab& &b]", "(?x)[ab&# intersection\n&b]", "(?x)[a-z& # intersection\n &[def]]", "[ab&\\Q\\E&b]", "[a-z&\\Q\\E\\Q\\E&[def]]", "(?x)[a-z& # intersection\n \\Q\\E&[def]]"
            ]
        )
        @DisplayName("comments-mode trivia between intersection ampersands is ignored")
        fun commentsModeTriviaBetweenIntersectionAmpersandsIsIgnored(regex: String) {
            JdkSyntaxCompatibilityTest.Companion.assertFullMatchesSameForAll(
                regex,
                mutableListOf<String>("", "&", "a", "b", "d", "z")
            )
        }

        @ParameterizedTest
        @ValueSource(
            strings = ["(?x)[a-\\Q\\E b]", "(?x)[a- \\Q\\E b]", "(?x)[a-\\Q\\E # endpoint\n b]", "(?x)[a-\\Q\\E\\Q\\E b]"
            ]
        )
        @DisplayName("comments-mode trivia and empty quotes before a range endpoint are ignored")
        fun commentsModeTriviaAndEmptyQuotesBeforeRangeEndpointAreIgnored(regex: String) {
            JdkSyntaxCompatibilityTest.Companion.assertFullMatchesSameForAll(
                regex,
                mutableListOf<String>("`", "a", "b", "c", " ")
            )
        }

        @ParameterizedTest
        @ValueSource(
            strings = ["(?x)[a&& &b]", "(?x)[a& &&b]", "(?x)[a& & &b]", "(?x)[a&# one\n&&b]", "[a&\\Q\\E&]", "[a&&\\Q\\E&b]"
            ]
        )
        @DisplayName("comments-mode split repeated intersection operators are rejected")
        fun commentsModeSplitRepeatedIntersectionOperatorsAreRejected(regex: String?) {
            assertRejectedBySafeRe(regex)
        }

        @ParameterizedTest
        @ValueSource(
            strings = ["[&&`+]˫]*", "[&&abc]", "[a&&&&b]", "[&&-a]", "[^&&-a]", "(?x)[&&  -a]", "(?x)[&& #x\n -a]", "[&&^a]", "[&&\\d]", "[&&[a]]", "[&&a-]", "[&&--a]", "[&&-[a]]", "[&&a&&b]", "[&&a&&[ab]]", "[\\Q\\E&&a]", "[a&&\\Q\\E&&a]", "[ab&&\\Q\\E&&b]", "[a&&\\Q\\E&&b]", "(?x)[ab&&  ]", "(?x)[ab&& #x\n ]", "[&&--[a]]"
            ]
        )
        @DisplayName("malformed character-class intersections are rejected by SafeRE")
        fun malformedCharacterClassIntersectionsRejected(regex: String?) {
            assertRejectedBySafeRe(regex)
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("generatedCharacterClassMembershipCases")
        @DisplayName("character-class edge syntax matches JDK over generated inputs")
        fun characterClassEdgeSyntaxMatchesJdkOverGeneratedInputs(
            membershipCase: CharacterClassMembershipCase
        ) {
            JdkSyntaxCompatibilityTest.Companion.assertFullMatchesSameForAll(
                membershipCase.regex!!,
                membershipCase.inputs!!
            )
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("generatedRepresentativeCharacterClassOperationCases")
        @DisplayName("generated representative character-class operation cases match JDK")
        fun generatedRepresentativeCharacterClassOperationCasesMatchJdk(
            membershipCase: CharacterClassMembershipCase
        ) {
            val jdk: CharacterClassMatrixOutcome = Companion.jdkCharacterClassOutcome(membershipCase.regex!!)
            val safere: CharacterClassMatrixOutcome = safeReCharacterClassOutcome(membershipCase.regex)
            Assertions.assertThat<CharacterClassMatrixOutcome?>(safere)
                .`as`("character-class outcome for /%s/", membershipCase.regex)
                .isEqualTo(jdk)
        }

        @ParameterizedTest
        @ValueSource(strings = ["(?x)[0&\\Q\\E\\Q\\E&&&& #x\n-&&]", "(?x)[0&\\Q\\E\\Q\\E&&&&&& #x\n-&&]"])
        @DisplayName("character-class ampersand runs with empty quotes reject malformed syntax")
        fun characterClassAmpersandRunsWithEmptyQuotesRejectMalformedSyntax(regex: String) {
            assertRejectedByJdkAndSafeRe(regex)
        }

        @ParameterizedTest
        @ValueSource(
            strings = ["[&&\\Q\\E&&&]", "[[a]a&&\\Q\\E&&&]", "(?x)[&&[a]&&& ]", "(?x)[&&[a]&&&#x\n]", "(?x)[&&[a]\\Q\\E&&& ]", "(?x)[&&[a][b]&&& ]"
            ]
        )
        @DisplayName("empty character-class intersection RHS rejects without parser crash")
        fun emptyCharacterClassIntersectionRhsRejectsWithoutParserCrash(regex: String) {
            assertRejectedByJdkAndSafeRe(regex)
        }

        @ParameterizedTest
        @ValueSource(
            strings = ["(?x)[a& &&&& & & ]", "(?x)[a& &&&& && ]", "(?x)[-[ab]&&& && ]", "(?x)[a&&&\\Q\\E&& -&&]", "(?x)[a&&&\\Q\\E&&&a-b-&&]"
            ]
        )
        @DisplayName("comments-mode split ampersand runs reject before class close")
        fun commentsModeSplitAmpersandRunsRejectBeforeClassClose(regex: String) {
            assertRejectedByJdkAndSafeRe(regex)
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("deferredCharacterClassExpressionParserCases")
        @DisplayName("character-class expression parser cases match JDK")
        fun characterClassExpressionParserCasesMatchJdk(membershipCase: CharacterClassMembershipCase) {
            JdkSyntaxCompatibilityTest.Companion.assertFullMatchesSameForAll(
                membershipCase.regex!!,
                membershipCase.inputs!!
            )
        }

        @Test
        @DisplayName("subtraction [a-z&&[^bc]]")
        fun subtraction() {
            assertMatchesSame("[a-z&&[^bc]]", "a")
            assertMatchesFull("[a-z&&[^bc]]", "b")
        }

        @Test
        @DisplayName("subtraction [a-z&&[^m-p]]")
        fun subtractionRange() {
            assertMatchesSame("[a-z&&[^m-p]]", "a")
            assertMatchesFull("[a-z&&[^m-p]]", "n")
        }

        @Test
        @DisplayName("surrogate pair range in character class")
        fun surrogatePairRange() {
            // From issue #127 comment: surrogate pairs encoding supplementary ranges
            assertCompiles("[\\uD800\\uDC00-\\uDBFF\\uDFFF]")
        }

        @Test
        @DisplayName("complex Unicode range with explicit surrogate endpoints")
        fun complexUnicodeRange() {
            // Pattern from issue #127 comment
            assertCompiles("([\\u0020-\\uD7FF\\uE000-\\uFFFD\\uD800\\uDC00-\\uDBFF\\uDFFF\\t]*)$")
        }

        companion object {
            @JvmStatic
            fun generatedCharacterClassMembershipCases(): Stream<Arguments?> {
                val inputs: MutableList<String> = mutableListOf<String>(
                    "", "&", "[", "]", ":", "^", "-", "a", "b", "c", "d", "e", "f", "m", "p", "z", "`",
                    "+"
                )
                return Stream.of<Arguments?>(
                    Arguments.of(CharacterClassMembershipCase("[\\Q\\E]]", inputs)),
                    Arguments.of(CharacterClassMembershipCase("[\\Q\\E-]", inputs)),
                    Arguments.of(CharacterClassMembershipCase("[\\Q\\E-a]", inputs)),
                    Arguments.of(CharacterClassMembershipCase("[ \\Q\\E-a]", inputs)),
                    Arguments.of(CharacterClassMembershipCase("[a\\Q\\E-b]", inputs)),
                    Arguments.of(CharacterClassMembershipCase("[a-z&&[def]]", inputs)),
                    Arguments.of(CharacterClassMembershipCase("[a-z&&[^bc]]", inputs)),
                    Arguments.of(CharacterClassMembershipCase("[a-\\Q\\E]", inputs)),
                    Arguments.of(CharacterClassMembershipCase("[a-\\Qb\\E]", inputs)),
                    Arguments.of(CharacterClassMembershipCase("[a-\\Qbc\\E]", inputs)),
                    Arguments.of(CharacterClassMembershipCase("[\\Qa\\E-b]", inputs)),
                    Arguments.of(CharacterClassMembershipCase("[\\Qab\\E-c]", inputs)),
                    Arguments.of(CharacterClassMembershipCase("[a-[a]]", inputs)),
                    Arguments.of(CharacterClassMembershipCase("[a-[x]]", inputs)),
                    Arguments.of(CharacterClassMembershipCase("[a-d[m-p]]", inputs)),
                    Arguments.of(CharacterClassMembershipCase("[[:lower:]]", inputs)),
                    Arguments.of(CharacterClassMembershipCase("[^[:lower:]]", inputs)),
                    Arguments.of(CharacterClassMembershipCase("[[:^space:]]", inputs))
                )
            }

            @JvmStatic
            fun generatedRepresentativeCharacterClassOperationCases(): Stream<Arguments?> {
                val inputs: MutableList<String> = characterClassMatrixInputs()
                val operands = mutableListOf<String>(
                    "a-c",
                    "\\d",
                    "\\w",
                    "\\Q-\\E",
                    "\\Q&\\E",
                    "[ab]",
                    "[^b]",
                    "[[:lower:]]",
                    "[[.ch.]]",
                    "[[=a=]]"
                )
                val operators = mutableListOf<String>("", "&&")
                val cases = Stream.builder<Arguments?>()
                for (negated in mutableListOf<Boolean>(false, true)) {
                    for (left in operands) {
                        cases.add(
                            Arguments.of(
                                CharacterClassMembershipCase(
                                    "[" + (if (negated) "^" else "") + left + "]", inputs
                                )
                            )
                        )
                        for (operator in operators) {
                            for (right in operands) {
                                cases.add(
                                    Arguments.of(
                                        CharacterClassMembershipCase(
                                            "[" + (if (negated) "^" else "") + left + operator + right + "]", inputs
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
                return cases.build()
            }

            @JvmStatic
            fun deferredCharacterClassExpressionParserCases(): Stream<Arguments?> {
                val inputs: MutableList<String> =
                    mutableListOf<String>("", "&", "[", "]", "-", "a", "b", "x", "z", "0", "1", " ", "\t", "Ā")
                return Stream.of<Arguments?>(
                    Arguments.of(CharacterClassMembershipCase("[\\Qab\\E&&[b]]", inputs)),
                    Arguments.of(CharacterClassMembershipCase("[[ab]&&[^b]]", inputs)),
                    Arguments.of(CharacterClassMembershipCase("[&\\Q\\E &&\\d]", inputs))
                )
            }

            private fun jdkCharacterClassOutcome(regex: String): CharacterClassMatrixOutcome {
                try {
                    return CharacterClassMatrixOutcome(
                        true, characterClassMatrixMatches(java.util.regex.Pattern.compile(regex))
                    )
                } catch (e: PatternSyntaxException) {
                    return CharacterClassMatrixOutcome(false, "")
                }
            }

            private fun safeReCharacterClassOutcome(regex: String?): CharacterClassMatrixOutcome {
                try {
                    return CharacterClassMatrixOutcome(
                        true, characterClassMatrixMatches(org.safere.Pattern.compile(regex))
                    )
                } catch (e: PatternSyntaxException) {
                    return CharacterClassMatrixOutcome(false, "")
                }
            }

            private fun characterClassMatrixMatches(pattern: java.util.regex.Pattern): String {
                val result = StringBuilder()
                for (input in characterClassMatrixInputs()) {
                    if (pattern.matcher(input).matches()) {
                        if (result.length > 0) {
                            result.append(',')
                        }
                        result.append(input)
                    }
                }
                return result.toString()
            }

            private fun characterClassMatrixMatches(pattern: org.safere.Pattern): String {
                val result = StringBuilder()
                for (input in characterClassMatrixInputs()) {
                    if (pattern.matcher(input).matches()) {
                        if (result.length > 0) {
                            result.append(',')
                        }
                        result.append(input)
                    }
                }
                return result.toString()
            }

            private fun characterClassMatrixInputs(): MutableList<String> {
                return mutableListOf<String>(
                    "",
                    "a",
                    "b",
                    "c",
                    "&",
                    "-",
                    "0",
                    "1",
                    "x",
                    "_",
                    " ",
                    "\t",
                    "Ā",
                    "é",
                    "\n"
                )
            }
        }
    }

    // ===========================================================================
    // 4. POSIX Character Classes (\p{...})
    // ===========================================================================
    @Nested
    @DisplayName("POSIX character classes")
    internal class PosixCharacterClasses {
        @ParameterizedTest(name = "{0}")
        @MethodSource("posixClasses")
        @DisplayName("POSIX class")
        fun posixClass(regex: String, shouldMatch: String, shouldNotMatch: String) {
            assertMatchesSame(regex, shouldMatch)
            assertMatchesFull(regex, shouldNotMatch)
        }

        @ParameterizedTest(name = "{0} on \"{1}\"")
        @MethodSource("posixBracketClassSyntax")
        @DisplayName("POSIX bracket class spelling is ordinary character-class text")
        fun posixBracketClassSyntaxIsOrdinaryText(regex: String, input: String, expectedMatch: Boolean) {
            val jdkMatches = java.util.regex.Pattern.compile(regex).matcher(input).matches()
            val safeMatches = org.safere.Pattern.compile(regex).matcher(input).matches()

            Assertions.assertThat(jdkMatches)
                .`as`("JDK baseline for /%s/ on \"%s\"", regex, input)
                .isEqualTo(expectedMatch)
            Assertions.assertThat(safeMatches)
                .`as`("SafeRE for /%s/ on \"%s\"", regex, input)
                .isEqualTo(expectedMatch)
        }

        companion object {
            @JvmStatic
            fun posixClasses(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of("\\p{Lower}", "a", "5"),
                    Arguments.of("\\p{Upper}", "A", "a"),
                    Arguments.of("\\p{ASCII}", "x", "\u0080"),
                    Arguments.of("\\p{Alpha}", "a", "5"),
                    Arguments.of("\\p{Digit}", "5", "a"),
                    Arguments.of("\\p{Alnum}", "a", "!"),
                    Arguments.of("\\p{Punct}", "!", "a"),
                    Arguments.of("\\p{Graph}", "a", " "),
                    Arguments.of("\\p{Print}", "a", "\u0000"),
                    Arguments.of("\\p{Blank}", " ", "a"),
                    Arguments.of("\\p{Cntrl}", "\u0000", "a"),
                    Arguments.of("\\p{XDigit}", "f", "g"),
                    Arguments.of("\\p{Space}", " ", "a")
                )
            }

            @JvmStatic
            fun posixBracketClassSyntax(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of("[[:lower:]]", "l", true),
                    Arguments.of("[[:lower:]]", "o", true),
                    Arguments.of("[[:lower:]]", "w", true),
                    Arguments.of("[[:lower:]]", "e", true),
                    Arguments.of("[[:lower:]]", "r", true),
                    Arguments.of("[[:lower:]]", "a", false),
                    Arguments.of("[[:lower:]]", ":", true),
                    Arguments.of("[[:alpha:]]", "p", true),
                    Arguments.of("[[:alpha:]]", "h", true),
                    Arguments.of("[[:alpha:]]", "Z", false),
                    Arguments.of("[[:digit:]]", "d", true),
                    Arguments.of("[[:digit:]]", "i", true),
                    Arguments.of("[[:digit:]]", "g", true),
                    Arguments.of("[[:digit:]]", "t", true),
                    Arguments.of("[[:digit:]]", "5", false),
                    Arguments.of("[[:^space:]]", "^", true),
                    Arguments.of("[[:^space:]]", "s", true),
                    Arguments.of("[[:^space:]]", "p", true),
                    Arguments.of("[[:^space:]]", "a", true),
                    Arguments.of("[[:^space:]]", "c", true),
                    Arguments.of("[[:^space:]]", "e", true),
                    Arguments.of("[[:^space:]]", " ", false),
                    Arguments.of("[[.ch.]]", ".", true),
                    Arguments.of("[[.ch.]]", "c", true),
                    Arguments.of("[[.ch.]]", "h", true),
                    Arguments.of("[[=a=]]", "=", true),
                    Arguments.of("[[=a=]]", "a", true),
                    Arguments.of("[^[:lower:]]", "a", true),
                    Arguments.of("[^[:lower:]]", "l", false),
                    Arguments.of("[^[:lower:]]", ":", false)
                )
            }
        }
    }

    // ===========================================================================
    // 5. java.lang.Character Classes
    // ===========================================================================
    @Nested
    @DisplayName("java.lang.Character classes")
    internal class JavaCharacterClasses {
        @ParameterizedTest(name = "{0}")
        @MethodSource("javaClasses")
        @DisplayName("java.lang.Character class")
        fun javaCharClass(regex: String, shouldMatch: String, shouldNotMatch: String) {
            assertMatchesSame(regex, shouldMatch)
            assertMatchesFull(regex, shouldNotMatch)
        }

        companion object {
            @JvmStatic
            fun javaClasses(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of("\\p{javaLowerCase}", "a", "A"),
                    Arguments.of("\\p{javaUpperCase}", "A", "a"),
                    Arguments.of("\\p{javaWhitespace}", " ", "a"),
                    Arguments.of("\\p{javaMirrored}", "(", "a")
                )
            }
        }
    }

    // ===========================================================================
    // 6. Unicode Scripts, Blocks, Categories, Binary Properties
    // ===========================================================================
    @Nested
    @DisplayName("Unicode scripts")
    internal inner class UnicodeScripts {
        @Test
        @DisplayName("\\\\p{IsLatin}")
        fun isLatinScript() {
            assertMatchesSame("\\p{IsLatin}", "A")
            assertMatchesFull("\\p{IsLatin}", "\u4E00") // CJK char
        }

        @Test
        @DisplayName("\\\\p{IsHiragana}")
        fun isHiraganaScript() {
            assertMatchesSame("\\p{IsHiragana}", "\u3042") // Hiragana 'a'
        }

        @Test
        @DisplayName("\\\\p{script=Hiragana}")
        fun scriptKeywordHiragana() {
            assertMatchesSame("\\p{script=Hiragana}", "\u3042")
        }

        @Test
        @DisplayName("\\\\p{sc=Hiragana}")
        fun scKeywordHiragana() {
            assertMatchesSame("\\p{sc=Hiragana}", "\u3042")
        }

        @Test
        @DisplayName("\\\\p{sc=Latin}")
        fun scKeywordLatin() {
            assertMatchesSame("\\p{sc=Latin}", "A")
        }

        @ParameterizedTest
        @ValueSource(strings = ["\\p{Latin}", "\\P{Latin}", "[\\p{Latin}]", "\\p{script=Lu}"])
        @DisplayName("rejects non-JDK script property spellings")
        fun rejectsNonJdkScriptPropertySpellings(regex: String) {
            assertRejectedByJdkAndSafeRe(regex)
        }
    }

    @Nested
    @DisplayName("Unicode blocks")
    internal inner class UnicodeBlocks {
        @Test
        @DisplayName("\\\\p{InGreek}")
        fun inGreek() {
            assertMatchesSame("\\p{InGreek}", "\u0391") // Alpha
        }

        @Test
        @DisplayName("\\\\p{InBasicLatin}")
        fun inBasicLatin() {
            assertMatchesSame("\\p{InBasicLatin}", "A")
        }

        @Test
        @DisplayName("\\\\p{block=Mongolian}")
        fun blockKeywordMongolian() {
            assertMatchesSame("\\p{block=Mongolian}", "\u1820")
        }

        @Test
        @DisplayName("\\\\p{blk=Greek}")
        fun blkKeywordGreek() {
            assertMatchesSame("\\p{blk=Greek}", "\u0391")
        }

        @Test
        @DisplayName("\\\\P{InGreek} (negated)")
        fun notInGreek() {
            assertMatchesSame("\\P{InGreek}", "A")
            assertMatchesFull("\\P{InGreek}", "\u0391")
        }

        @ParameterizedTest
        @ValueSource(
            strings = ["\\p{Braille}", "\\P{Braille}", "\\p{^Braille}", "[\\p{Braille}]", "\\p{general_category=Latin}"
            ]
        )
        @DisplayName("rejects non-JDK block property spellings")
        fun rejectsNonJdkBlockPropertySpellings(regex: String) {
            assertRejectedByJdkAndSafeRe(regex)
        }
    }

    @Nested
    @DisplayName("Unicode categories")
    internal inner class UnicodeCategories {
        @Test
        @DisplayName("\\\\p{Lu} (uppercase letter)")
        fun luCategory() {
            assertMatchesSame("\\p{Lu}", "A")
            assertMatchesFull("\\p{Lu}", "a")
        }

        @Test
        @DisplayName("\\\\p{Ll} (lowercase letter)")
        fun llCategory() {
            assertMatchesSame("\\p{Ll}", "a")
        }

        @Test
        @DisplayName("\\\\p{L} (letter)")
        fun lCategory() {
            assertMatchesSame("\\p{L}", "a")
            assertMatchesFull("\\p{L}", "5")
        }

        @Test
        @DisplayName("\\\\p{IsLu} (category with Is prefix)")
        fun isLuCategory() {
            assertMatchesSame("\\p{IsLu}", "A")
            assertMatchesFull("\\p{IsLu}", "a")
        }

        @Test
        @DisplayName("\\\\p{IsL} (category with Is prefix)")
        fun isLCategory() {
            assertMatchesSame("\\p{IsL}", "a")
            assertMatchesFull("\\p{IsL}", "5")
        }

        @Test
        @DisplayName("\\\\p{Sc} (currency symbol)")
        fun scCategory() {
            assertMatchesSame("\\p{Sc}", "$")
            assertMatchesFull("\\p{Sc}", "a")
        }

        @Test
        @DisplayName("\\\\p{Nd} (decimal digit number)")
        fun ndCategory() {
            assertMatchesSame("\\p{Nd}", "5")
        }

        @Test
        @DisplayName("\\\\p{general_category=Lu}")
        fun gcKeywordLu() {
            assertMatchesSame("\\p{general_category=Lu}", "A")
        }

        @Test
        @DisplayName("\\\\p{gc=Lu}")
        fun gcShortKeywordLu() {
            assertMatchesSame("\\p{gc=Lu}", "A")
        }

        @ParameterizedTest
        @ValueSource(strings = ["\\p{lu}", "\\p{gc=Latin}", "\\p{gc=Letter}"])
        @DisplayName("rejects non-JDK category property spellings")
        fun rejectsNonJdkCategoryPropertySpellings(regex: String) {
            assertRejectedByJdkAndSafeRe(regex)
        }

        @Test
        @DisplayName("[\\\\p{L}&&[^\\\\p{Lu}]] (category subtraction)")
        fun categorySubtraction() {
            assertMatchesSame("[\\p{L}&&[^\\p{Lu}]]", "a")
            assertMatchesFull("[\\p{L}&&[^\\p{Lu}]]", "A")
        }
    }

    @Nested
    @DisplayName("Unicode binary properties")
    internal class UnicodeBinaryProperties {
        @ParameterizedTest(name = "{0}")
        @MethodSource("binaryProperties")
        @DisplayName("binary property")
        fun binaryProperty(regex: String, shouldMatch: String, shouldNotMatch: String) {
            assertMatchesSame(regex, shouldMatch)
            assertMatchesFull(regex, shouldNotMatch)
        }

        @Test
        @DisplayName("\\\\p{IsWhiteSpace} property alias without underscore")
        fun isWhiteSpaceNoUnderscore() {
            // Regression for issue #127. JDK is flexible about underscores in property names.
            assertMatchesSame("\\p{IsWhiteSpace}", " ")
        }

        @Test
        @DisplayName("\\\\p{IsEmoji}")
        fun isEmoji() {
            assertMatchesSame("\\p{IsEmoji}", "\u263A") // white smiling face
        }

        @Test
        @DisplayName("\\\\p{IsEmoji_Presentation}")
        fun isEmojiPresentation() {
            assertCompiles("\\p{IsEmoji_Presentation}")
        }

        @Test
        @DisplayName("\\\\p{IsEmoji_Modifier}")
        fun isEmojiModifier() {
            assertCompiles("\\p{IsEmoji_Modifier}")
        }

        @Test
        @DisplayName("\\\\p{IsEmoji_Modifier_Base}")
        fun isEmojiModifierBase() {
            assertCompiles("\\p{IsEmoji_Modifier_Base}")
        }

        @Test
        @DisplayName("\\\\p{IsEmoji_Component}")
        fun isEmojiComponent() {
            assertCompiles("\\p{IsEmoji_Component}")
        }

        @Test
        @DisplayName("\\\\p{IsExtended_Pictographic}")
        fun isExtendedPictographic() {
            assertCompiles("\\p{IsExtended_Pictographic}")
        }

        @ParameterizedTest
        @ValueSource(strings = ["\\p{Alphabetic}", "\\P{Alphabetic}", "[\\p{Alphabetic}]"])
        @DisplayName("rejects binary properties without Is prefix")
        fun rejectsBinaryPropertiesWithoutIsPrefix(regex: String) {
            assertRejectedByJdkAndSafeRe(regex)
        }

        companion object {
            @JvmStatic
            fun binaryProperties(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of("\\p{IsAlphabetic}", "a", "5"),
                    Arguments.of("\\p{IsIdeographic}", "\u4E00", "a"),
                    Arguments.of("\\p{IsLetter}", "a", "5"),
                    Arguments.of("\\p{IsLowercase}", "a", "A"),
                    Arguments.of("\\p{IsUppercase}", "A", "a"),
                    Arguments.of("\\p{IsTitlecase}", "\u01C5", "a"),  // Dz with caron
                    Arguments.of("\\p{IsPunctuation}", "!", "a"),
                    Arguments.of("\\p{IsControl}", "\u0000", "a"),
                    Arguments.of("\\p{IsWhite_Space}", " ", "a"),
                    Arguments.of("\\p{IsDigit}", "5", "a"),
                    Arguments.of("\\p{IsHex_Digit}", "f", "g"),
                    Arguments.of("\\p{IsJoin_Control}", "\u200C", "a"),  // ZWNJ
                    Arguments.of("\\p{IsNoncharacter_Code_Point}", "\uFDD0", "a"),
                    Arguments.of("\\p{IsAssigned}", "a", "\uFFFF")
                )
            }
        }
    }

    // ===========================================================================
    // 7. Boundary Matchers
    // ===========================================================================
    @Nested
    @DisplayName("Boundary matchers")
    internal inner class BoundaryMatchers {
        @Test
        @DisplayName("^ beginning of line")
        fun beginLine() {
            assertMatchesSame("^abc", "abc")
        }

        @Test
        @DisplayName("$ end of line")
        fun endLine() {
            assertMatchesSame("abc$", "abc")
        }

        @Test
        @DisplayName("\\\\b word boundary")
        fun wordBoundary() {
            assertMatchesSame("\\bword\\b", "a word here")
        }

        @Test
        @DisplayName("\\\\B non-word boundary")
        fun nonWordBoundary() {
            assertMatchesSame("\\Bord", "word")
        }

        @Test
        @DisplayName("\\\\b{g} grapheme cluster boundary")
        fun graphemeClusterBoundary() {
            assertCompiles("\\b{g}")
        }

        @Test
        @DisplayName("\\\\A beginning of input")
        fun beginInput() {
            assertMatchesSame("\\Aabc", "abc")
        }

        @Test
        @DisplayName("\\\\Z end of input (before final terminator)")
        fun endInputBeforeTerminator() {
            assertMatchesSame("abc\\Z", "abc\n")
        }

        @Test
        @DisplayName("\\\\z end of input")
        fun endInput() {
            assertMatchesSame("abc\\z", "abc")
        }

        @Test
        @DisplayName("\\\\G end of previous match (should reject)")
        fun endPreviousMatch() {
            // \G requires state from previous matches; SafeRE should reject.
            // But JDK accepts it.
            Assertions.assertThatNoException()
                .`as`("JDK should accept \\G")
                .isThrownBy(ThrowingCallable { java.util.regex.Pattern.compile("\\G") })
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile("\\G") })
                .`as`("SafeRE should reject \\G")
                .isInstanceOf(PatternSyntaxException::class.java)
        }
    }

    // ===========================================================================
    // 8. Quantifiers
    // ===========================================================================
    @Nested
    @DisplayName("Quantifiers")
    internal class Quantifiers {
        // -- Greedy --
        @Test
        @DisplayName("greedy ? (zero or one)")
        fun greedyQuestion() {
            assertMatchesSame("ab?c", "ac")
            assertMatchesSame("ab?c", "abc")
        }

        @Test
        @DisplayName("greedy * (zero or more)")
        fun greedyStar() {
            assertMatchesSame("ab*c", "ac")
            assertMatchesSame("ab*c", "abbc")
        }

        @Test
        @DisplayName("greedy + (one or more)")
        fun greedyPlus() {
            assertMatchesSame("ab+c", "abc")
            assertMatchesSame("ab+c", "abbc")
        }

        @Test
        @DisplayName("greedy {n}")
        fun greedyExact() {
            assertMatchesSame("a{3}", "aaa")
        }

        @Test
        @DisplayName("greedy {n,}")
        fun greedyAtLeast() {
            assertMatchesSame("a{2,}", "aaaa")
        }

        @Test
        @DisplayName("greedy {n,m}")
        fun greedyRange() {
            assertMatchesSame("a{2,4}", "aaaaa")
        }

        @ParameterizedTest(name = "/{0}/ on \"{1}\"")
        @MethodSource("countedRepetitionBoundsWithLeadingZeros")
        @DisplayName("counted repetition bounds allow leading zeros")
        fun countedRepetitionBoundsAllowLeadingZeros(regex: String, input: String) {
            assertMatchesSame(regex, input)
        }

        @ParameterizedTest
        @ValueSource(
            strings = ["a{05,04}", "a{+5}", "a{-5}", "a{ 5}", "a{5 }", "a{5, 6}", "a{5,+6}", "a{5,-6}", "a{\u0665}", "a{\uff11\uff12}"
            ]
        )
        @DisplayName("counted repetition bounds reject invalid decimal forms")
        fun countedRepetitionBoundsRejectInvalidDecimalForms(regex: String) {
            assertRejectedByJdkAndSafeRe(regex)
        }

        @Test
        @DisplayName("counted repetition leading zeros do not bypass repeat limit")
        fun countedRepetitionLeadingZerosDoNotBypassRepeatLimit() {
            val regex = "a{0000000000000000000000000000001001}"
            Assertions.assertThatNoException()
                .`as`("JDK should accept repeat counts above SafeRE's expansion limit: %s", regex)
                .isThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex) })
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
                .`as`("SafeRE should still enforce its repeat limit after parsing leading zeros: %s", regex)
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @ParameterizedTest
        @ValueSource(strings = ["{", "{?", "a{?", "a{,2}", "a{x}", "\\\\Q{?\\\\E"])
        @DisplayName("malformed unescaped counted repetition")
        fun malformedUnescapedCountedRepetition(regex: String) {
            Assertions.assertThatThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex) })
                .`as`("JDK should reject malformed counted repetition: %s", regex)
                .isInstanceOf(PatternSyntaxException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
                .`as`("SafeRE should reject malformed counted repetition: %s", regex)
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        // -- Reluctant --
        @Test
        @DisplayName("reluctant ??")
        fun reluctantQuestion() {
            assertMatchesSame("ab??c", "ac")
        }

        @Test
        @DisplayName("reluctant *?")
        fun reluctantStar() {
            assertMatchesSame("a.*?c", "abcbc")
        }

        @Test
        @DisplayName("reluctant +?")
        fun reluctantPlus() {
            assertMatchesSame("a.+?c", "abcbc")
        }

        @Test
        @DisplayName("reluctant {n,m}?")
        fun reluctantRange() {
            assertMatchesSame("a{2,4}?", "aaaaa")
        }

        @ParameterizedTest
        @ValueSource(strings = ["^*?", "$+?", "\\b??", "\\b{g}{1}?", "(){0,2}?"])
        @DisplayName("reluctant quantifiers over zero-width operands")
        fun reluctantQuantifiersOverZeroWidthOperands(regex: String) {
            assertCompiles(regex)
        }

        @ParameterizedTest
        @ValueSource(strings = ["^ * ?", "\\b{g} # boundary\n * ?", "\\b{g} ? {0}"])
        @DisplayName("comments-mode trivia before reluctant quantifier modifiers")
        fun commentsModeTriviaBeforeReluctantQuantifierModifiers(regex: String) {
            Assertions.assertThatNoException()
                .`as`("JDK should accept: %s", regex)
                .isThrownBy(
                    ThrowingCallable { java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.COMMENTS) })
            Assertions.assertThatNoException()
                .`as`("SafeRE should accept: %s", regex)
                .isThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex, org.safere.Pattern.COMMENTS) })
        }

        @ParameterizedTest
        @ValueSource(strings = ["^*+", "^?+", "^{2}+", "$*+", "()*+", "\\b++", "\\b{g}{1}+"])
        @DisplayName("possessive quantifiers over zero-width operands")
        fun possessiveQuantifiersOverZeroWidthOperands(regex: String) {
            assertCompiles(regex)
        }

        @ParameterizedTest
        @ValueSource(strings = ["^ * +", "^ ? +", "^ {2} +", "$ * +", "() * +", "\\b + +"])
        @DisplayName("comments-mode possessive quantifiers over zero-width operands")
        fun commentsModePossessiveQuantifiersOverZeroWidthOperands(regex: String) {
            Assertions.assertThatNoException()
                .`as`("JDK should accept: %s", regex)
                .isThrownBy(
                    ThrowingCallable { java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.COMMENTS) })
            Assertions.assertThatNoException()
                .`as`("SafeRE should accept: %s", regex)
                .isThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex, org.safere.Pattern.COMMENTS) })
        }

        // -- Possessive (SafeRE should reject) --
        @ParameterizedTest
        @ValueSource(strings = ["a?+", "a*+", "a++", "a{2}+", "a{2,}+", "a{2,4}+"])
        @DisplayName("possessive quantifiers (should reject)")
        fun possessiveQuantifiers(regex: String) {
            // JDK accepts these.
            Assertions.assertThatNoException()
                .`as`("JDK should accept: %s", regex)
                .isThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex) })
            // SafeRE should reject — possessive quantifiers violate linear-time guarantees.
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
                .`as`("SafeRE should reject possessive quantifier: %s", regex)
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @ParameterizedTest
        @ValueSource(
            strings = ["a ? +", "a * +", "a + +", "a {1} +", "a + # modifier\n +", "a {1} # modifier\n +", "(?x)a + +"
            ]
        )
        @DisplayName("comments-mode trivia before possessive quantifier modifiers")
        fun commentsModeTriviaBeforePossessiveQuantifierModifiers(regex: String) {
            Assertions.assertThatNoException()
                .`as`("JDK should accept: %s", regex)
                .isThrownBy(
                    ThrowingCallable { java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.COMMENTS) })
            Assertions.assertThatThrownBy(ThrowingCallable {
                org.safere.Pattern.compile(
                    regex,
                    org.safere.Pattern.COMMENTS
                )
            })
                .`as`("SafeRE should reject possessive quantifier: %s", regex)
                .isInstanceOf(PatternSyntaxException::class.java)
                .hasMessageContaining("possessive quantifiers are not supported")
        }

        @ParameterizedTest
        @ValueSource(
            strings = ["()+++", "^+{1}+++?", "\\b++?{0,2}?", "\\b{g}+++", "()+{0}?+", "()+{0}?+{1}", "()+{1}{0}?+?", "()+{2}{2}+?"
            ]
        )
        @DisplayName("dangling quantifier modifier chains rejected")
        fun danglingQuantifierModifierChainsRejected(regex: String) {
            assertRejectedByJdkAndSafeRe(regex)
        }

        @ParameterizedTest
        @ValueSource(
            strings = ["a ? + +", "a * + +", "a + + +", "a {1} + +", "a + # modifier\n + +", "a {1} # modifier\n + +", "(?x)a + + +", "() + {0} ? +", "() + {0} ? + {1}", "() + {1} {0} ? + ?", "() + {2} {2} + ?"
            ]
        )
        @DisplayName("comments-mode trivia does not hide malformed quantifier modifier chains")
        fun commentsModeTriviaDoesNotHideMalformedQuantifierModifierChains(regex: String) {
            assertRejectedByJdkAndSafeRe(regex, org.safere.Pattern.COMMENTS)
        }

        // -- Nested repetitions --
        @Test
        @DisplayName("nested repetition {0,99} inside {0,5}")
        fun nestedRepetition() {
            // Regression for issue #127.
            assertCompiles("(?:a (?:b{0,99}|c{0,9})){0,5}")
        }

        @Test
        @DisplayName("simple nested repetition")
        fun simpleNestedRepetition() {
            assertMatchesSame("(ab{2,3}){2}", "abbbabb")
        }

        @Test
        @DisplayName("bounded repeat of concatenated bounded repeats")
        fun boundedRepeatOfConcatenatedBoundedRepeats() {
            assertMatchesSame("(?:a{0,63}b{0,99}){0,5}", "aaabbbb")
        }

        @ParameterizedTest(name = "/{0}/ on \"{1}\"")
        @MethodSource("boundedRepeatsOfQuantifiedAtoms")
        @DisplayName("bounded repeats of quantified atoms")
        fun boundedRepeatsOfQuantifiedAtoms(regex: String, input: String) {
            assertMatchesSame(regex, input)
        }

        @Test
        @DisplayName("comments-mode whitespace preserves zero-width possessive anchor quantifiers")
        fun commentsModeWhitespacePreservesZeroWidthPossessiveAnchorQuantifiers() {
            val regex = "^+\n\n\n+^"

            Assertions.assertThatNoException()
                .`as`("JDK should accept possessive anchor quantifier: %s", regex)
                .isThrownBy(
                    ThrowingCallable { java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.COMMENTS) })
            Assertions.assertThatNoException()
                .`as`("SafeRE should accept zero-width possessive anchor quantifier: %s", regex)
                .isThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex, org.safere.Pattern.COMMENTS) })
        }

        companion object {
            @JvmStatic
            fun countedRepetitionBoundsWithLeadingZeros(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of("a{05}", "aaaaa"),
                    Arguments.of("a{000}", "b"),
                    Arguments.of("a{00001}", "a"),
                    Arguments.of("a{01,02}", "aa"),
                    Arguments.of("a{01,}", "aaaaa"),
                    Arguments.of("a{0,05}", "aaaaa"),
                    Arguments.of("a{00,00}", "b"),
                    Arguments.of("a{00,01}", "a"),
                    Arguments.of("a{05}?", "aaaaa"),
                    Arguments.of("a{01}{02}", "aa"),
                    Arguments.of("a?{02}", "a"),
                    Arguments.of("^{02}", ""),
                    Arguments.of("{02}", ""),
                    Arguments.of("a|{02}", "")
                )
            }

            @JvmStatic
            fun boundedRepeatsOfQuantifiedAtoms(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of("\\S??{1,3}", ""),
                    Arguments.of("\\S??{1,3}", "a"),
                    Arguments.of("a?{1,3}", "a"),
                    Arguments.of("a*{1,3}", "aaa"),
                    Arguments.of("a+{1,3}", "aaa"),
                    Arguments.of("a{2}{3}", "aaaaaa"),
                    Arguments.of("a{2}?{3}", "aaaaaa")
                )
            }
        }
    }

    // ===========================================================================
    // 9. Logical Operators
    // ===========================================================================
    @Nested
    @DisplayName("Logical operators")
    internal inner class LogicalOperators {
        @Test
        @DisplayName("concatenation XY")
        fun concatenation() {
            assertMatchesSame("ab", "ab")
        }

        @Test
        @DisplayName("alternation X|Y")
        fun alternation() {
            assertMatchesSame("cat|dog", "I have a dog")
        }

        @Test
        @DisplayName("capturing group (X)")
        fun capturingGroup() {
            assertMatchesSame("(ab)+", "ababab")
        }
    }

    // ===========================================================================
    // 10. Back References (SafeRE should reject)
    // ===========================================================================
    @Nested
    @DisplayName("Back references (should reject)")
    internal inner class BackReferences {
        @ParameterizedTest
        @ValueSource(strings = ["(a)\\1", "(a)(b)\\2", "(a)\\123", "(a)(b)(c)(d)\\400", "(?<name>a)\\k<name>"])
        @DisplayName("back reference")
        fun backReference(regex: String) {
            // JDK accepts these.
            Assertions.assertThatNoException()
                .`as`("JDK should accept: %s", regex)
                .isThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex) })
            // SafeRE should reject — back references require backtracking.
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
                .`as`("SafeRE should reject back reference: %s", regex)
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @ParameterizedTest
        @ValueSource(strings = ["\\1", "\\9", "\\12", "\\12*", "\\2222*", "3*\\2222*"])
        @DisplayName("numeric back reference syntax without groups is rejected")
        fun numericBackReferenceSyntaxWithoutGroupsRejected(regex: String) {
            Assertions.assertThatNoException()
                .`as`("JDK should accept unresolved numeric back reference syntax: %s", regex)
                .isThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex) })
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
                .`as`("SafeRE should reject unresolved numeric back reference syntax: %s", regex)
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @ParameterizedTest
        @ValueSource(strings = ["(a)\\12", "(a)\\123", "(a)(b)\\12"])
        @DisplayName("numeric back reference shortened to an existing preceding group")
        fun numericBackReferenceShortenedToExistingPrecedingGroup(regex: String) {
            Assertions.assertThatNoException()
                .`as`("JDK should accept shortened numeric back reference: %s", regex)
                .isThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex) })
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
                .`as`("SafeRE should reject numeric back reference: %s", regex)
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        @DisplayName("multi-digit numeric back reference to an existing preceding group")
        fun multiDigitNumericBackReferenceToExistingPrecedingGroup() {
            val regex = "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)(l)\\12"

            Assertions.assertThatNoException()
                .`as`("JDK should accept numeric back reference to group 12")
                .isThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex) })
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
                .`as`("SafeRE should reject numeric back reference to group 12")
                .isInstanceOf(PatternSyntaxException::class.java)
        }
    }

    // ===========================================================================
    // 11. Quotation
    // ===========================================================================
    @Nested
    @DisplayName("Quotation")
    internal inner class Quotation {
        @Test
        @DisplayName("\\\\Q...\\\\E quotes metacharacters")
        fun quotation() {
            assertMatchesSame("\\Q.+*\\E", ".+*")
        }

        @Test
        @DisplayName("\\\\Q...\\\\E at end of pattern (no \\\\E)")
        fun quotationNoEnd() {
            assertMatchesSame("\\Q.+*", ".+*")
        }

        @Test
        @DisplayName("\\\\Q...\\\\E with normal regex after")
        fun quotationWithRegexAfter() {
            assertMatchesSame("\\Q.+\\E.+", ".+ab")
        }

        @Test
        @DisplayName("\\\\Q...\\\\E quotes braces")
        fun quotationWithBrace() {
            assertMatchesSame("\\Q{?\\E", "{?")
        }
    }

    // ===========================================================================
    // 12. Special Constructs
    // ===========================================================================
    @Nested
    @DisplayName("Special constructs")
    internal inner class SpecialConstructs {
        @Test
        @DisplayName("named capturing group (?<name>X)")
        fun namedCapturingGroup() {
            assertMatchesSame("(?<word>\\w+)", "hello")
        }

        @Test
        @DisplayName("Python-style named capturing group (?P<name>X) is accepted")
        @DisabledForCrosscheck("SafeRE supports Python-style named capturing groups")
        fun pythonStyleNamedCapturingGroupAccepted() {
            val regex = "(?" + "P<word>\\w+)"
            Assertions.assertThatThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex) })
                .isInstanceOf(PatternSyntaxException::class.java)
            val unused = org.safere.Pattern.compile(regex)
        }

        @Test
        @DisplayName("non-capturing group (?:X)")
        fun nonCapturingGroup() {
            assertMatchesSame("(?:ab)+", "ababab")
        }

        // -- Lookahead/Lookbehind (SafeRE should reject) --
        @ParameterizedTest
        @ValueSource(
            strings = ["a(?=b)" // positive lookahead
                , "a(?!b)" // negative lookahead
                , "(?<=a)b" // positive lookbehind
                , "(?<!a)b" // negative lookbehind
            ]
        )
        @DisplayName("lookahead/lookbehind (should reject)")
        fun lookaround(regex: String) {
            Assertions.assertThatNoException()
                .`as`("JDK should accept: %s", regex)
                .isThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex) })
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
                .`as`("SafeRE should reject lookaround: %s", regex)
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        @DisplayName("independent non-capturing group (?>X) (should reject)")
        fun atomicGroup() {
            val regex = "(?>a+)"
            Assertions.assertThatNoException()
                .`as`("JDK should accept: %s", regex)
                .isThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex) })
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
                .`as`("SafeRE should reject atomic group: %s", regex)
                .isInstanceOf(PatternSyntaxException::class.java)
        }
    }

    // ===========================================================================
    // 13. Inline Flags
    // ===========================================================================
    @Nested
    @DisplayName("Inline flags")
    internal inner class InlineFlags {
        @Test
        @DisplayName("(?i) case insensitive")
        fun flagI() {
            assertMatchesSame("(?i)abc", "ABC")
        }

        @Test
        @DisplayName("(?d) UNIX_LINES")
        fun flagD() {
            assertCompiles("(?d).")
            // With (?d), only \n is a line terminator; \r should be matched by dot.
            assertMatchesSame("(?d).", "\r")
        }

        @Test
        @DisplayName("(?m) multiline")
        fun flagM() {
            assertMatchesSame("(?m)^abc$", "xyz\nabc\ndef")
        }

        @Test
        @DisplayName("(?s) dotall")
        fun flagS() {
            assertMatchesSame("(?s)a.b", "a\nb")
        }

        @Test
        @DisplayName("(?u) unicode case")
        fun flagU() {
            assertCompiles("(?u)(?i)abc")
        }

        @Test
        @DisplayName("(?x) comments mode")
        fun flagX() {
            assertMatchesSame("(?x) a b c ", "abc")
        }

        @Test
        @DisplayName("(?U) UNICODE_CHARACTER_CLASS")
        fun flagBigU() {
            assertCompiles("(?U)\\w")
        }

        @Test
        @DisplayName("combined flags (?dm)")
        fun combinedFlags() {
            assertCompiles("(?dm)^test$")
        }

        @Test
        @DisplayName("negated flags (?-i)")
        fun negatedFlags() {
            assertCompiles("(?i)abc(?-i)def")
        }

        @Test
        @DisplayName("flags on non-capturing group (?i:abc)")
        fun flagsOnGroup() {
            assertMatchesSame("(?i:abc)def", "ABCdef")
        }

        @Test
        @DisplayName("(?d) combined with (?m)")
        fun flagDWithM() {
            // Regression for issue #127.
            assertCompiles("(?m)(?d)^(####? .+|---)$")
        }

        @Test
        @DisplayName("all JDK flags combined (?idmsuxU)")
        fun allFlags() {
            assertCompiles("(?idmsuxU)test")
        }

        @Test
        @DisplayName("all JDK flags negated (?-idmsuxU)")
        fun allFlagsNegated() {
            assertCompiles("(?idmsuxU)(?-idmsuxU)test")
        }

        @Test
        @DisplayName("flag group (?idmsuxU:X)")
        fun flagGroup() {
            assertCompiles("(?ims:abc)")
        }
    }

    // ===========================================================================
    // 14. API Compile Flags
    // ===========================================================================
    @Nested
    @DisplayName("API compile flags")
    internal inner class ApiCompileFlags {
        @Test
        @DisplayName("Pattern.CASE_INSENSITIVE")
        fun caseInsensitive() {
            assertMatchesSameWithFlags("abc", org.safere.Pattern.CASE_INSENSITIVE, "ABC")
        }

        @Test
        @DisplayName("Pattern.MULTILINE")
        fun multiline() {
            assertMatchesSameWithFlags("^abc$", org.safere.Pattern.MULTILINE, "xyz\nabc\ndef")
        }

        @Test
        @DisplayName("Pattern.DOTALL")
        fun dotall() {
            assertMatchesSameWithFlags("a.b", org.safere.Pattern.DOTALL, "a\nb")
        }

        @Test
        @DisplayName("Pattern.UNIX_LINES")
        fun unixLines() {
            assertMatchesSameWithFlags(".", org.safere.Pattern.UNIX_LINES, "\r")
        }

        @Test
        @DisplayName("Pattern.COMMENTS")
        fun comments() {
            assertMatchesSameWithFlags("a b c # comment", org.safere.Pattern.COMMENTS, "abc")
        }

        @Test
        @DisplayName("Pattern.UNICODE_CASE")
        fun unicodeCase() {
            assertMatchesSameWithFlags(
                "abc",
                org.safere.Pattern.CASE_INSENSITIVE or org.safere.Pattern.UNICODE_CASE,
                "ABC"
            )
        }

        @Test
        @DisplayName("Pattern.UNICODE_CHARACTER_CLASS")
        fun unicodeCharacterClass() {
            assertMatchesSameWithFlags("\\w", org.safere.Pattern.UNICODE_CHARACTER_CLASS, "\u00E9") // e-acute
        }
    }

    // ===========================================================================
    // 15. Linebreak and Grapheme (matching behavior)
    // ===========================================================================
    @Nested
    @DisplayName("Linebreak and grapheme matching")
    internal inner class LinebreakAndGrapheme {
        @Test
        @DisplayName("\\\\R prefers \\\\r\\\\n over bare \\\\r")
        fun linebreakCrLfPreference() {
            // \R should match \r\n as a single unit, not just \r
            val jdkM = java.util.regex.Pattern.compile("\\R").matcher("\r\n")
            Assertions.assertThat(jdkM.find()).isTrue()
            val jdkGroup = jdkM.group()

            val safeM = org.safere.Pattern.compile("\\R").matcher("\r\n")
            Assertions.assertThat(safeM.find()).isTrue()
            Assertions.assertThat(safeM.group()).isEqualTo(jdkGroup)
        }

        @Test
        @DisplayName("\\\\R does not match ordinary characters")
        fun linebreakNoOrdinary() {
            assertMatchesFull("\\R", "a")
        }

        @Test
        @DisplayName("dot respects line terminators by default")
        fun dotDefaultLineTerminators() {
            assertMatchesFull(".", "\n")
        }

        @Test
        @DisplayName("dot with DOTALL matches newline")
        fun dotDotallMatchesNewline() {
            assertMatchesSameWithFlags(".", org.safere.Pattern.DOTALL, "\n")
        }
    }

    // ===========================================================================
    // 16. Line terminators
    // ===========================================================================
    @Nested
    @DisplayName("Line terminators")
    internal class LineTerminators {
        @ParameterizedTest(name = "{0}")
        @MethodSource("lineTerminators")
        @DisplayName("dot does not match line terminator")
        fun dotDoesNotMatch(desc: String?, terminator: String) {
            // dot should NOT match line terminators (without DOTALL)
            val jdkM = java.util.regex.Pattern.compile(".").matcher(terminator)
            val safeM = org.safere.Pattern.compile(".").matcher(terminator)
            Assertions.assertThat(safeM.find()).`as`("dot on %s", desc).isEqualTo(jdkM.find())
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("lineTerminators")
        @DisplayName("(?m)$ matches before line terminator")
        fun multilineDollar(desc: String?, terminator: String) {
            val input = "abc" + terminator + "def"
            assertMatchesSameWithFlags("abc$", org.safere.Pattern.MULTILINE, input)
        }

        companion object {
            @JvmStatic
            fun lineTerminators(): Stream<Arguments?> {
                return Stream.of<Arguments?>(
                    Arguments.of("\\n (newline)", "\n"),
                    Arguments.of("\\r\\n (CRLF)", "\r\n"),
                    Arguments.of("\\r (carriage return)", "\r"),
                    Arguments.of("\\u0085 (next line)", "\u0085"),
                    Arguments.of("\\u2028 (line separator)", "\u2028"),
                    Arguments.of("\\u2029 (paragraph separator)", "\u2029")
                )
            }
        }
    }

    // ===========================================================================
    // 17. Escaped metacharacters
    // ===========================================================================
    @Nested
    @DisplayName("Escaped metacharacters")
    internal inner class EscapedMetacharacters {
        @ParameterizedTest
        @ValueSource(
            strings = ["\\.", "\\*", "\\+", "\\?", "\\(", "\\)", "\\[", "\\]", "\\{", "\\}", "\\|", "\\^", "\\$", "\\\\"
            ]
        )
        @DisplayName("escaped metacharacter is literal")
        fun escapedMetacharacter(regex: String) {
            assertCompiles(regex)
        }

        @ParameterizedTest
        @ValueSource(strings = ["\\©", "\\é", "\\Ā", "\\☃", "\\😀"])
        @DisplayName("escaped non-ASCII character is literal")
        fun escapedNonAsciiCharacter(regex: String) {
            val literal = regex.substring(1)
            assertMatchesFull(regex, literal)
            assertMatchesFull("^" + regex + "$", literal)
        }

        @ParameterizedTest
        @ValueSource(strings = ["[\\©]", "[\\é]", "[\\Ā]", "[\\☃]", "[\\😀]"])
        @DisplayName("escaped non-ASCII character in class is literal")
        fun escapedNonAsciiCharacterInClass(regex: String) {
            val literal = regex.substring(2, regex.length - 1)
            assertMatchesFull(regex, literal)
        }
    }

    // ===========================================================================
    // 18. Compatibility edge cases
    // ===========================================================================
    @Nested
    @DisplayName("Compatibility edge cases")
    internal inner class CompatibilityEdgeCases {
        @Test
        @DisplayName("(?m)(?d)^(####? .+|---)$")
        fun inlineFlagDWithMultiline() {
            // Regression for issue #127.
            assertMatchesSame("(?m)(?d)^(####? .+|---)$", "## Hello")
        }

        @Test
        @DisplayName("Thai character range with \\\\u escapes")
        fun thaiCharacterRange() {
            assertMatchesSame("([\\u0E00-\\u0E7F])([0-9a-zA-Z])", "\u0E01a")
        }

        @Test
        @DisplayName("nested repetition (?:a (?:b{0,99}|c{0,9})){0,5}")
        fun nestedRepetitionFromIssue() {
            // Regression for issue #127.
            assertMatchesSame("(?:a (?:b{0,99}|c{0,9})){0,5}", "a bbb")
        }

        @Test
        @DisplayName("\\\\p{IsWhiteSpace} (no underscore)")
        fun isWhiteSpaceNoUnderscore() {
            assertMatchesSame("\\p{IsWhiteSpace}", " ")
        }

        @Test
        @DisplayName("complex Unicode range with surrogate pairs")
        fun complexSurrogateRange() {
            // Regression for issue #127.
            assertCompiles("([\\u0020-\\uD7FF\\uE000-\\uFFFD\\uD800\\uDC00-\\uDBFF\\uDFFF\\t]*)$")
        }
    }

    // ===========================================================================
    // 19. Miscellaneous Patterns
    // ===========================================================================
    @Nested
    @DisplayName("Miscellaneous patterns")
    internal inner class MiscPatterns {
        @Test
        @DisplayName("empty pattern")
        fun emptyPattern() {
            assertMatchesSame("", "abc")
        }

        @Test
        @DisplayName("empty alternation branch")
        fun emptyAlternationBranch() {
            assertMatchesSame("a|", "b")
        }

        @Test
        @DisplayName("nested groups")
        fun nestedGroups() {
            assertMatchesSame("((a)(b(c)))", "abc")
        }

        @Test
        @DisplayName("complex real-world pattern: email-like")
        fun emailLike() {
            assertMatchesSame("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", "test@example.com")
        }

        @Test
        @DisplayName("complex real-world pattern: IPv4")
        fun ipv4() {
            assertMatchesSame("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "192.168.1.1")
        }

        @Test
        @DisplayName("large character class with union and intersection")
        fun largeCharClassOps() {
            assertMatchesSame("[a-z[A-Z]&&[^aeiouAEIOU]]", "b") // consonants only
        }
    }

    companion object {
        // ---- Helpers ----
        /** Asserts SafeRE compiles the pattern without error.  */
        private fun assertCompiles(regex: String) {
            // Sanity: JDK must accept it too.
            Assertions.assertThatNoException()
                .`as`("JDK should accept: %s", regex)
                .isThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex) })
            Assertions.assertThatNoException()
                .`as`("SafeRE should accept: %s", regex)
                .isThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
        }

        /** Asserts JDK and SafeRE both reject the pattern.  */
        private fun assertRejectedByJdkAndSafeRe(regex: String) {
            Assertions.assertThatThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex) })
                .`as`("JDK should reject: %s", regex)
                .isInstanceOf(PatternSyntaxException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
                .`as`("SafeRE should reject: %s", regex)
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        /** Asserts SafeRE rejects the pattern (intentional divergence).  */
        private fun assertRejectedBySafeRe(regex: String?) {
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex) })
                .`as`("SafeRE should reject: %s", regex)
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        /** Asserts JDK and SafeRE both reject the pattern with the given flags.  */
        private fun assertRejectedByJdkAndSafeRe(regex: String, flags: Int) {
            Assertions.assertThatThrownBy(ThrowingCallable { java.util.regex.Pattern.compile(regex, flags) })
                .`as`("JDK should reject: %s flags=%d", regex, flags)
                .isInstanceOf(PatternSyntaxException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex, flags) })
                .`as`("SafeRE should reject: %s flags=%d", regex, flags)
                .isInstanceOf(PatternSyntaxException::class.java)
        }

        /** Asserts SafeRE compiles and matches identically to JDK on the given input.  */
        private fun assertMatchesSame(regex: String, input: String) {
            // Sanity: JDK must accept it.
            val jdkM = java.util.regex.Pattern.compile(regex).matcher(input)
            val jdkFound = jdkM.find()

            val safeM = org.safere.Pattern.compile(regex).matcher(input)
            val safeFound = safeM.find()

            Assertions.assertThat(safeFound).`as`("find() for /%s/ on \"%s\"", regex, input).isEqualTo(jdkFound)

            if (jdkFound && safeFound) {
                Assertions.assertThat(safeM.group())
                    .`as`("group() for /%s/ on \"%s\"", regex, input)
                    .isEqualTo(jdkM.group())
            }
        }

        /** Asserts SafeRE compiles and full-matches identically to JDK on the given input.  */
        private fun assertMatchesFull(regex: String, input: String) {
            val jdkM = java.util.regex.Pattern.compile(regex).matcher(input)
            val jdkMatches = jdkM.matches()

            val safeM = org.safere.Pattern.compile(regex).matcher(input)
            val safeMatches = safeM.matches()

            Assertions.assertThat(safeMatches).`as`("matches() for /%s/ on \"%s\"", regex, input).isEqualTo(jdkMatches)
        }

        /** Asserts SafeRE full-match behavior agrees with the JDK for every input.  */
        private fun assertFullMatchesSameForAll(regex: String, inputs: MutableList<String>) {
            for (input in inputs) {
                assertMatchesFull(regex, input)
            }
        }

        /**
         * Asserts SafeRE compiles with the given flags and matches identically to JDK on the given input.
         */
        private fun assertMatchesSameWithFlags(regex: String, jdkFlags: Int, input: String) {
            val jdkM = java.util.regex.Pattern.compile(regex, jdkFlags).matcher(input)
            val jdkFound = jdkM.find()

            // Map JDK flags to SafeRE flags (same values by design).
            val safeM = org.safere.Pattern.compile(regex, jdkFlags).matcher(input)
            val safeFound = safeM.find()

            Assertions.assertThat(safeFound)
                .`as`("find() for /%s/ (flags=%d) on \"%s\"", regex, jdkFlags, input)
                .isEqualTo(jdkFound)

            if (jdkFound && safeFound) {
                Assertions.assertThat(safeM.group())
                    .`as`("group() for /%s/ (flags=%d) on \"%s\"", regex, jdkFlags, input)
                    .isEqualTo(jdkM.group())
            }
        }
    }
}
