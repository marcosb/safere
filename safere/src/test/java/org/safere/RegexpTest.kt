// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import java.util.List

/** Tests for [Regexp].  */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class RegexpTest {
    @Test
    fun noMatch() {
        val re = Regexp.noMatch(0)
        Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.NO_MATCH)
    }

    @Test
    fun emptyMatch() {
        val re = Regexp.emptyMatch(0)
        Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.EMPTY_MATCH)
    }

    @Test
    fun literal() {
        val re = Regexp.literal('a'.code, 0)
        Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL)
        Assertions.assertThat(re.rune).isEqualTo('a'.code)
        Assertions.assertThat(re.toString()).isEqualTo("a")
    }

    @Test
    fun literalString() {
        val runes = intArrayOf('h'.code, 'e'.code, 'l'.code, 'l'.code, 'o'.code)
        val re = Regexp.literalString(runes, 0)
        Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.LITERAL_STRING)
        Assertions.assertThat(re.runes).isEqualTo(runes)
        Assertions.assertThat(re.toString()).isEqualTo("hello")
    }

    @Test
    fun concat() {
        val a = Regexp.literal('a'.code, 0)
        val b = Regexp.literal('b'.code, 0)
        val re = Regexp.concat(List.of<Regexp?>(a, b), 0)
        Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CONCAT)
        Assertions.assertThat(re.subs.size).isEqualTo(2)
        Assertions.assertThat(re.toString()).isEqualTo("ab")
    }

    @Test
    fun alternate() {
        val a = Regexp.literal('a'.code, 0)
        val b = Regexp.literal('b'.code, 0)
        val re = Regexp.alternate(List.of<Regexp?>(a, b), 0)
        Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.ALTERNATE)
        Assertions.assertThat(re.subs.size).isEqualTo(2)
        Assertions.assertThat(re.toString()).isEqualTo("a|b")
    }

    @Test
    fun star() {
        val a = Regexp.literal('a'.code, 0)
        val re = Regexp.star(a, 0)
        Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.STAR)
        Assertions.assertThat<Regexp?>(re.sub()).isEqualTo(a)
        Assertions.assertThat(re.toString()).isEqualTo("a*")
    }

    @Test
    fun plus() {
        val a = Regexp.literal('a'.code, 0)
        val re = Regexp.plus(a, 0)
        Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.PLUS)
        Assertions.assertThat(re.toString()).isEqualTo("a+")
    }

    @Test
    fun quest() {
        val a = Regexp.literal('a'.code, 0)
        val re = Regexp.quest(a, 0)
        Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.QUEST)
        Assertions.assertThat(re.toString()).isEqualTo("a?")
    }

    @Test
    fun repeat_bounded() {
        val a = Regexp.literal('a'.code, 0)
        val re = Regexp.repeat(a, 0, 2, 5)
        Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.REPEAT)
        Assertions.assertThat(re.min).isEqualTo(2)
        Assertions.assertThat(re.max).isEqualTo(5)
        Assertions.assertThat(re.toString()).isEqualTo("a{2,5}")
    }

    @Test
    fun repeat_unbounded() {
        val a = Regexp.literal('a'.code, 0)
        val re = Regexp.repeat(a, 0, 3, -1)
        Assertions.assertThat(re.min).isEqualTo(3)
        Assertions.assertThat(re.max).isEqualTo(-1)
        Assertions.assertThat(re.toString()).isEqualTo("a{3,}")
    }

    @Test
    fun capture_unnamed() {
        val a = Regexp.literal('a'.code, 0)
        val re = Regexp.capture(a, 0, 1, null)
        Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CAPTURE)
        Assertions.assertThat(re.cap).isEqualTo(1)
        Assertions.assertThat(re.name).isNull()
        Assertions.assertThat(re.toString()).isEqualTo("(a)")
    }

    @Test
    fun capture_named() {
        val a = Regexp.literal('a'.code, 0)
        val re = Regexp.capture(a, 0, 1, "foo")
        Assertions.assertThat(re.name).isEqualTo("foo")
    }

    @Test
    fun anyChar() {
        val re = Regexp.anyChar(0)
        Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.ANY_CHAR)
        Assertions.assertThat(re.toString()).isEqualTo(".")
    }

    @Test
    fun anchors() {
        Assertions.assertThat(Regexp.beginLine(0).toString()).isEqualTo("^")
        Assertions.assertThat(Regexp.endLine(0).toString()).isEqualTo("$")
        Assertions.assertThat(Regexp.beginText(0).toString()).isEqualTo("(?-m:^)")
        Assertions.assertThat(Regexp.endText(0).toString()).isEqualTo("\\z")
        Assertions.assertThat(Regexp.wordBoundary(0).toString()).isEqualTo("\\b")
        Assertions.assertThat(Regexp.noWordBoundary(0).toString()).isEqualTo("\\B")
    }

    @Test
    fun charClassNode() {
        val cc = CharClassBuilder().addRange('a'.code, 'z'.code).build()
        val re = Regexp.charClass(cc, 0)
        Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.CHAR_CLASS)
        Assertions.assertThat<CharClass?>(re.charClass).isNotNull()
        Assertions.assertThat(re.toString()).contains("a-z")
    }

    @Test
    fun haveMatch() {
        val re = Regexp.haveMatch(42, 0)
        Assertions.assertThat<RegexpOp?>(re.op).isEqualTo(RegexpOp.HAVE_MATCH)
        Assertions.assertThat(re.matchId).isEqualTo(42)
    }

    @Test
    fun sub_throwsForWrongArity() {
        val re = Regexp.concat(List.of<Regexp?>(Regexp.literal('a'.code, 0), Regexp.literal('b'.code, 0)), 0)
        Assertions.assertThatThrownBy(ThrowingCallable { re.sub() }).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun foldCase_flag() {
        val re = Regexp.literal('a'.code, ParseFlags.FOLD_CASE)
        Assertions.assertThat(re.foldCase()).isTrue()
        Assertions.assertThat(re.nonGreedy()).isFalse()
    }

    @Test
    fun nonGreedy_flag() {
        val re = Regexp.star(Regexp.literal('a'.code, 0), ParseFlags.NON_GREEDY)
        Assertions.assertThat(re.nonGreedy()).isTrue()
        Assertions.assertThat(re.foldCase()).isFalse()
    }

    // ---------------------------------------------------------------------------
    // toString coverage
    // ---------------------------------------------------------------------------
    @Test
    fun toStringNoMatch() {
        val re = Regexp.noMatch(0)
        Assertions.assertThat(re.toString()).contains("[^")
    }

    @Test
    fun toStringEmptyMatch() {
        val re = Regexp.emptyMatch(0)
        // At top-level precedence, EMPTY_MATCH renders as the empty string.
        Assertions.assertThat(re.toString()).isEmpty()
    }

    @Test
    fun toStringHaveMatch() {
        val re = Regexp.haveMatch(5, 0)
        Assertions.assertThat(re.toString()).contains("HaveMatch").contains("5")
    }

    @Test
    fun toStringEndTextWithDollarFlag() {
        val re = Regexp.endText(ParseFlags.WAS_DOLLAR)
        Assertions.assertThat(re.toString()).isEqualTo("(?-m:$)")
    }

    @Test
    fun toStringNonGreedyStar() {
        val re = Regexp.star(Regexp.literal('a'.code, 0), ParseFlags.NON_GREEDY)
        Assertions.assertThat(re.toString()).isEqualTo("a*?")
    }

    @Test
    fun toStringNonGreedyPlus() {
        val re = Regexp.plus(Regexp.literal('a'.code, 0), ParseFlags.NON_GREEDY)
        Assertions.assertThat(re.toString()).isEqualTo("a+?")
    }

    @Test
    fun toStringNonGreedyQuest() {
        val re = Regexp.quest(Regexp.literal('a'.code, 0), ParseFlags.NON_GREEDY)
        Assertions.assertThat(re.toString()).isEqualTo("a??")
    }

    @Test
    fun toStringNonGreedyRepeat() {
        val re = Regexp.repeat(Regexp.literal('a'.code, 0), ParseFlags.NON_GREEDY, 2, 5)
        Assertions.assertThat(re.toString()).isEqualTo("a{2,5}?")
    }

    @Test
    fun toStringExactRepeat() {
        val re = Regexp.repeat(Regexp.literal('a'.code, 0), 0, 3, 3)
        Assertions.assertThat(re.toString()).isEqualTo("a{3}")
    }

    @Test
    fun toStringConcatNeedsParen() {
        // Concat inside a quantifier needs (?:...) wrapping.
        val ab = Regexp.concat(List.of<Regexp?>(Regexp.literal('a'.code, 0), Regexp.literal('b'.code, 0)), 0)
        val star = Regexp.star(ab, 0)
        Assertions.assertThat(star.toString()).isEqualTo("(?:ab)*")
    }

    @Test
    fun toStringNamedCapture() {
        val re = Regexp.capture(Regexp.literal('a'.code, 0), 0, 1, "foo")
        Assertions.assertThat(re.toString()).isEqualTo("(?<foo>a)")
    }

    @Test
    fun toStringFoldCaseLiteral() {
        val re = Regexp.literal('a'.code, ParseFlags.FOLD_CASE)
        Assertions.assertThat(re.toString()).isEqualTo("[Aa]")
    }

    // ---------------------------------------------------------------------------
    // Quantifier squashing
    // ---------------------------------------------------------------------------
    @Test
    fun starOfStarIsIdentity() {
        val a = Regexp.literal('a'.code, 0)
        val star1 = Regexp.star(a, 0)
        val star2 = Regexp.star(star1, 0)
        Assertions.assertThat<Regexp?>(star2).isSameAs(star1)
    }

    @Test
    fun plusOfStarBecomesStar() {
        val a = Regexp.literal('a'.code, 0)
        val star = Regexp.star(a, 0)
        val result = Regexp.plus(star, 0)
        Assertions.assertThat<RegexpOp?>(result.op).isEqualTo(RegexpOp.STAR)
    }

    @Test
    fun questOfPlusBecomesStar() {
        val a = Regexp.literal('a'.code, 0)
        val plus = Regexp.plus(a, 0)
        val result = Regexp.quest(plus, 0)
        Assertions.assertThat<RegexpOp?>(result.op).isEqualTo(RegexpOp.STAR)
        Assertions.assertThat(result.sub().rune).isEqualTo('a'.code)
    }

    @Test
    fun plusOfPlusIsIdentity() {
        val a = Regexp.literal('a'.code, 0)
        val plus1 = Regexp.plus(a, 0)
        val plus2 = Regexp.plus(plus1, 0)
        Assertions.assertThat<Regexp?>(plus2).isSameAs(plus1)
    }

    @Test
    fun questOfQuestIsIdentity() {
        val a = Regexp.literal('a'.code, 0)
        val q1 = Regexp.quest(a, 0)
        val q2 = Regexp.quest(q1, 0)
        Assertions.assertThat<Regexp?>(q2).isSameAs(q1)
    }

    @Test
    fun nsubNullSubs() {
        val re = Regexp.literal('a'.code, 0)
        Assertions.assertThat(re.nsub()).isEqualTo(0)
    }

    @Test
    fun subNullSubsThrows() {
        val re = Regexp.literal('a'.code, 0)
        Assertions.assertThatThrownBy(ThrowingCallable { re.sub() }).isInstanceOf(IllegalStateException::class.java)
    }
}
