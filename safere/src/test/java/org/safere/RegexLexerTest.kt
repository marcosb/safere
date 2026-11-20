// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.safere.RegexLexer.RepetitionMode
import java.util.function.Consumer
import java.util.regex.PatternSyntaxException
import java.util.stream.IntStream

@DisabledForCrosscheck("Lexer source spans and lookahead are SafeRE implementation contracts")
internal class RegexLexerTest {
    @Test
    fun quotedLiteralsKeepSpansAndLeaveFollowingSyntaxAvailable() {
        for (rune in IntStream.concat(IntStream.range(0, 128), IntStream.of(0xe9, 0x1f600)).toArray()) {
            val quoted = "\\Q" + String(Character.toChars(rune)) + "\\E"
            for (flags in java.util.List.of<Int?>(ParseFlags.LIKE_PERL, ParseFlags.LIKE_PERL or ParseFlags.COMMENTS)) {
                for (inClass in mutableListOf<Boolean>(false, true)) {
                    val lexer = RegexLexer(quoted + "+", flags)
                    val literal = if (inClass) lexer.takeClass() else lexer.next()
                    Assertions.assertThat<RegexLexer.Kind?>(literal.kind).isEqualTo(RegexLexer.Kind.LITERAL)
                    Assertions.assertThat(literal.rune).isEqualTo(rune)
                    Assertions.assertThat(literal.start).isZero()
                    Assertions.assertThat(literal.end).isEqualTo(quoted.length - 2)
                    val following = if (inClass) lexer.takeClass() else lexer.next()
                    Assertions.assertThat<RegexLexer.Kind?>(following.kind)
                        .isEqualTo(if (inClass) RegexLexer.Kind.LITERAL else RegexLexer.Kind.REPEAT)
                    Assertions.assertThat(following.rune).isEqualTo('+'.code)
                    Assertions.assertThat(following.start).isEqualTo(quoted.length)
                    Assertions.assertThat(following.end).isEqualTo(quoted.length + 1)
                    Assertions.assertThat<RegexLexer.Kind?>(lexer.next().kind).isEqualTo(RegexLexer.Kind.END)
                }
            }
        }
    }

    @Test
    fun completeQuantifiersKeepOriginalUtf16Spans() {
        val regex = "😀a+\\Q\\E?"
        val lexer = RegexLexer(regex, ParseFlags.LIKE_PERL)
        var token = lexer.next()
        Assertions.assertThat(token.rune).isEqualTo(0x1f600)
        Assertions.assertThat(token.start).isZero()
        Assertions.assertThat(token.end).isEqualTo(2)
        lexer.next()
        token = lexer.next()
        Assertions.assertThat<RegexLexer.Kind?>(token.kind).isEqualTo(RegexLexer.Kind.REPEAT)
        Assertions.assertThat<RepetitionMode?>(token.mode).isEqualTo(RepetitionMode.RELUCTANT)
        Assertions.assertThat(token.start).isEqualTo(regex.indexOf('+'))
        Assertions.assertThat(token.end).isEqualTo(regex.length)
    }

    @Test
    fun unpairedUnicodeEscapeLookaheadKeepsSeparateSourceSpans() {
        val lexer = RegexLexer("\\uD800\\u0041", ParseFlags.LIKE_PERL)
        val first = lexer.next()
        Assertions.assertThat(first.rune).isEqualTo(0xd800)
        Assertions.assertThat(first.start).isZero()
        Assertions.assertThat(first.end).isEqualTo(6)
        val second = lexer.next()
        Assertions.assertThat(second.rune).isEqualTo('A'.code)
        Assertions.assertThat(second.start).isEqualTo(6)
        Assertions.assertThat(second.end).isEqualTo(12)
    }

    @Test
    fun graphemeBoundariesKeepSpansAcrossEmptyQuotes() {
        for (regex in mutableListOf<String>("😀\\b{g}a", "😀\\b{\\Q\\Eg}a")) {
            val lexer = RegexLexer(regex, ParseFlags.LIKE_PERL)
            Assertions.assertThat(lexer.next().rune).isEqualTo(0x1f600)
            val boundary = lexer.next()
            Assertions.assertThat<RegexLexer.Kind?>(boundary.kind).isEqualTo(RegexLexer.Kind.GRAPHEME_BOUNDARY)
            Assertions.assertThat(boundary.start).isEqualTo(2)
            Assertions.assertThat(boundary.end).isEqualTo(regex!!.length - 1)
            val literal = lexer.next()
            Assertions.assertThat<RegexLexer.Kind?>(literal.kind).isEqualTo(RegexLexer.Kind.LITERAL)
            Assertions.assertThat(literal.rune).isEqualTo('a'.code)
            Assertions.assertThat(literal.start).isEqualTo(regex.length - 1)
            Assertions.assertThat(literal.end).isEqualTo(regex.length)
            Assertions.assertThat<RegexLexer.Kind?>(lexer.next().kind).isEqualTo(RegexLexer.Kind.END)
        }
    }

    @Test
    fun unsuccessfulLookaheadLeavesTheFollowingQuantifierAvailable() {
        val lexer = RegexLexer("\\b{0}a", ParseFlags.LIKE_PERL)
        Assertions.assertThat<RegexLexer.Kind?>(lexer.next().kind).isEqualTo(RegexLexer.Kind.BOUNDARY)
        val repeat = lexer.next()
        Assertions.assertThat<RegexLexer.Kind?>(repeat.kind).isEqualTo(RegexLexer.Kind.REPEAT)
        Assertions.assertThat(repeat.min).isZero()
        Assertions.assertThat(repeat.max).isZero()
        Assertions.assertThat(repeat.start).isEqualTo(2)
        Assertions.assertThat(repeat.end).isEqualTo(5)
        Assertions.assertThat(lexer.next().rune).isEqualTo('a'.code)
    }

    @Test
    fun lookaheadAtEndKeepsMalformedRepeatCoordinates() {
        for (regex in mutableListOf<String>("\\b{", "\\b{g", "\\b{g\\Q\\E")) {
            val lexer = RegexLexer(regex, ParseFlags.LIKE_PERL)
            val boundary = lexer.next()
            Assertions.assertThat<RegexLexer.Kind?>(boundary.kind).isEqualTo(RegexLexer.Kind.BOUNDARY)
            Assertions.assertThat(boundary.end).isEqualTo(2)
            Assertions.assertThatThrownBy(ThrowingCallable { lexer.next() })
                .isInstanceOfSatisfying<PatternSyntaxException?>(
                    PatternSyntaxException::class.java,
                    Consumer { error: PatternSyntaxException? ->
                        Assertions.assertThat(error!!.pattern).isEqualTo(regex)
                        Assertions.assertThat(error.index).isEqualTo(2)
                    })
        }
    }

    @Test
    fun groupDelimitersLeaveTheNextModeToTheParser() {
        val lexer = RegexLexer("(?x:a b) c", ParseFlags.LIKE_PERL)
        val header = lexer.next()
        Assertions.assertThat<RegexLexer.Kind?>(header.kind).isEqualTo(RegexLexer.Kind.SCOPED_FLAGS)
        lexer.setFlags(header.flags)
        Assertions.assertThat(lexer.next().rune).isEqualTo('a'.code)
        Assertions.assertThat(lexer.next().rune).isEqualTo('b'.code)
        Assertions.assertThat<RegexLexer.Kind?>(lexer.next().kind).isEqualTo(RegexLexer.Kind.CLOSE)
        lexer.setFlags(ParseFlags.LIKE_PERL)
        Assertions.assertThat(lexer.next().rune).isEqualTo(' '.code)
        Assertions.assertThat(lexer.next().rune).isEqualTo('c'.code)
    }

    @Test
    fun rangeLookaheadKeepsRawBracketRolesAfterTrivia() {
        for (gap in mutableListOf<String>("", " ", "#c\n", "\\Q\\E", " \\Q\\E")) {
            for (bracket in mutableListOf<String>("[", "]")) {
                val lexer =
                    RegexLexer(gap + bracket, ParseFlags.LIKE_PERL or ParseFlags.COMMENTS)
                val token = lexer.peekClassRangeEndpoint()
                Assertions.assertThat<RegexLexer.Kind?>(token.kind)
                    .isEqualTo(
                        if (bracket == "[") RegexLexer.Kind.CLASS_OPEN else RegexLexer.Kind.CLASS_CLOSE
                    )
                Assertions.assertThat<RegexLexer.Token?>(lexer.peekClass()).isSameAs(token)
                Assertions.assertThat<RegexLexer.Token?>(lexer.takeClass()).isSameAs(token)
                Assertions.assertThat<RegexLexer.Kind?>(lexer.next().kind).isEqualTo(RegexLexer.Kind.END)
            }
        }
    }

    @Test
    fun modeChangesCannotInvalidateAnOutstandingClassToken() {
        val lexer = RegexLexer("a", ParseFlags.LIKE_PERL)
        lexer.peekClass()
        Assertions.assertThatThrownBy(ThrowingCallable { lexer.setFlags(ParseFlags.LIKE_PERL or ParseFlags.COMMENTS) })
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    @Tag("work-counter")
    fun surrogateLookaheadConsumesLongTriviaOnlyOnce() {
        val regex = "\\uD83D\\uDE" + " #c\n".repeat(512) + "00"
        val work =
            WorkCounter.countForTesting({
                    val lexer = RegexLexer(regex, ParseFlags.LIKE_PERL or ParseFlags.COMMENTS)
                    Assertions.assertThat(lexer.next().rune).isEqualTo(0x1f600)
                    Assertions.assertThat<RegexLexer.Kind?>(lexer.next().kind).isEqualTo(RegexLexer.Kind.END)
                })
        Assertions.assertThat(work).isPositive().isLessThan(regex.length * 2L)
    }

    @Test
    @Tag("work-counter")
    fun repeatedClassPeeksDoNotRescanUnboundedTrivia() {
        val regex = "\\Q\\E #ignored\\Q\n".repeat(512) + "a"
        val work =
            WorkCounter.countForTesting({
                    val lexer = RegexLexer(regex, ParseFlags.LIKE_PERL or ParseFlags.COMMENTS)
                    for (i in 0..999) {
                        Assertions.assertThat(lexer.peekClass().rune).isEqualTo('a'.code)
                    }
                    lexer.takeClass()
                    Assertions.assertThat<RegexLexer.Kind?>(lexer.next().kind).isEqualTo(RegexLexer.Kind.END)
                    Assertions.assertThat(lexer.sawCommentQuoting()).isTrue()
                })
        Assertions.assertThat(work).isPositive().isLessThan(regex.length * 2L)
    }
}
