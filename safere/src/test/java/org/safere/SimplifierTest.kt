// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Tests for [Simplifier], ported from RE2's `simplify_test.cc`. Tests parse each
 * regexp, simplify it, then compare the toString() output against the expected simplified form.
 */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class SimplifierTest {
    @Test
    fun greedyNestedAtomRepeatsKeepTheLatestCaptureBeforeTheSuffix() {
        val atoms: MutableList<String> = mutableListOf<String?>("(?<g>[ab])", "(?<g>ab)", "(?<g>😀)", "((?<g>a))")
        val units = mutableListOf<String?>("a", "ab", "😀", "a")
        for (atom in atoms.indices) {
            for (min in mutableListOf<Int?>(0, 1)) {
                for (max in mutableListOf<Int?>(2, 3, 8)) {
                    for (outer in mutableListOf<String?>("*", "+")) {
                        val regex =
                            "\\A(?:(?:" + atoms.get(atom) + "){" + min + "," + max + "})" + outer + "b\\z"
                        for (count in mutableListOf<Int?>(0, 1, 2, 5, 8)) {
                            val body = units.get(atom)!!.repeat(count!!)
                            val matcher = Pattern.compile(regex).matcher(body + "b")
                            val matched = count != 0 || min == 0 || outer == "*"
                            Assertions.assertThat(matcher.matches()).`as`("/%s/ on %s", regex, body + "b")
                                .isEqualTo(matched)
                            if (matched) {
                                Assertions.assertThat(matcher.start("g"))
                                    .isEqualTo(if (count == 0) -1 else body.size - units.get(atom)!!.length)
                                Assertions.assertThat(matcher.end("g")).isEqualTo(if (count == 0) -1 else body.size)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun nestedRepeatGroupingRemainsObservableForHigherMinimaAndLazyOrder() {
        val minimum = Pattern.compile("(?:(?:(a)){3,4})*").matcher("aaaaaa")
        Assertions.assertThat(minimum.find()).isTrue()
        Assertions.assertThat(minimum.end()).isEqualTo(4)
        Assertions.assertThat(minimum.start(1)).isEqualTo(3)

        val lazyOuter = Pattern.compile("(?:(?:(?<g>[ab])){0,2})*?b").matcher("abb")
        Assertions.assertThat(lazyOuter.find()).isTrue()
        Assertions.assertThat(lazyOuter.end()).isEqualTo(3)
        Assertions.assertThat(lazyOuter.start("g")).isEqualTo(1)

        val lazyInner = Pattern.compile("(?:(?:(?<g>[ab])){0,2}?)*b").matcher("abb")
        Assertions.assertThat(lazyInner.find()).isTrue()
        Assertions.assertThat(lazyInner.end()).isEqualTo(2)
        Assertions.assertThat(lazyInner.start("g")).isEqualTo(0)

        val chunk = Pattern.compile("((a){0,2})*").matcher("aaaa")
        Assertions.assertThat(chunk.find()).isTrue()
        Assertions.assertThat(chunk.start(1)).isEqualTo(4)
        Assertions.assertThat(chunk.end(1)).isEqualTo(4)
        Assertions.assertThat(chunk.start(2)).isEqualTo(3)
    }

    @Test
    fun greedyNestedAtomRepeatProgramsDoNotGrowWithChunkLimit() {
        for (atom in mutableListOf<String?>("(a)", "((a))", "([ab])", "(ab)", "(😀)", "(.)")) {
            for (minimum in mutableListOf<Int?>(0, 1)) {
                for (outer in mutableListOf<String?>("*", "+")) {
                    val flat = if (minimum == 1 && outer == "+") "+" else "*"
                    val expected =
                        Pattern.compile("^(?:" + atom + ")" + flat + "b$").analysis().programSize
                    for (maximum in mutableListOf<Int?>(2, 32, 1000)) {
                        val nested =
                            Pattern.compile(
                                "^(?:" + atom + "{" + minimum + "," + maximum + "})" + outer + "b$"
                            )
                        Assertions.assertThat(nested.analysis().programSize)
                            .`as`("/%s/ has no observable chunk boundaries", nested)
                            .isEqualTo(expected)
                    }
                }
            }
        }
    }

    @ParameterizedTest(name = "[{index}] simplify({0}) = {1}")
    @MethodSource("simplifyTests")
    fun simplifyRegexp(input: String?, expected: String?) {
        Assertions.assertThat(simplify(input)).isEqualTo(expected)
    }

    @Test
    fun alreadySimpleReturnsSameObject() {
        val re = Parser.parse("a", FLAGS)
        val sre = Simplifier.simplify(re)
        // Already-simple regexps should return the same object.
        Assertions.assertThat<Regexp?>(sre).isSameAs(re)
    }

    @Test
    fun deeplyNestedCapturesRemainStackSafeDuringCompileSimplification() {
        val regex = "(".repeat(10000) + "a" + ")".repeat(10000) + "*"

        Assertions.assertThatNoException().isThrownBy(ThrowingCallable { Pattern.compile(regex) })
    }

    @Test
    fun deeplyNestedSimpleGroupsRemainStackSafeDuringSimplification() {
        val re = Parser.parse("(?:".repeat(10000) + "a" + ")".repeat(10000), FLAGS)

        Assertions.assertThat(Simplifier.simplify(re).toString()).isEqualTo("a")
    }

    @Test
    fun equalBasic() {
        val a = Parser.parse("a+b*", FLAGS)
        val b = Parser.parse("a+b*", FLAGS)
        Assertions.assertThat(Simplifier.equal(a, b)).isTrue()
    }

    @Test
    fun equalDifferent() {
        val a = Parser.parse("a+b*", FLAGS)
        val b = Parser.parse("a+c*", FLAGS)
        Assertions.assertThat(Simplifier.equal(a, b)).isFalse()
    }

    // ---------------------------------------------------------------------------
    // Simplify repeat edge cases
    // ---------------------------------------------------------------------------
    @Test
    fun simplifyRepeatExactZero() {
        // a{0} should simplify to empty match.
        val re = Parser.parse("a{0}", FLAGS)
        val simplified = Simplifier.simplify(re)
        Assertions.assertThat<RegexpOp?>(simplified!!.op).isEqualTo(RegexpOp.EMPTY_MATCH)
    }

    @Test
    fun simplifyRepeatFourOrMore() {
        // a{4,} should simplify to aaaa+
        val re = Parser.parse("a{4,}", FLAGS)
        val simplified = Simplifier.simplify(re)
        val s = simplified.toString()
        Assertions.assertThat(s).contains("a").contains("+")
    }

    @Test
    fun simplifyRepeatRangeMinMax() {
        // a{2,5} should simplify without REPEAT node.
        val re = Parser.parse("a{2,5}", FLAGS)
        val simplified = Simplifier.simplify(re)
        Assertions.assertThat(simplified.toString()).doesNotContain("{")
    }

    @Test
    fun simplifyRepeatZeroToN() {
        // a{0,3} should simplify to (a(a(a)?)?)?
        val re = Parser.parse("a{0,3}", FLAGS)
        val simplified = Simplifier.simplify(re)
        Assertions.assertThat(simplified.toString()).doesNotContain("{")
        Assertions.assertThat(simplified.toString()).contains("?")
    }

    @Test
    fun simplifyRepeatExactOne() {
        // a{1} should simplify to just a
        Assertions.assertThat(simplify("a{1}")).isEqualTo("a")
    }

    @Test
    fun simplifyRepeatEmptyWidthOp() {
        // Repeating a required zero-width assertion has the same effect as checking it once.
        val result: String = simplify("^{3,}")
        Assertions.assertThat(result).isEqualTo("^")
    }

    @Test
    fun simplifyRepeatEmptyWidthOpZero() {
        // ^{0,5} — empty-width op capped to {0,1} → ^?
        val result: String = simplify("^{0,5}")
        Assertions.assertThat(result).isEqualTo("^?")
    }

    @Test
    fun simplifyQuantifierOfEmptyMatch() {
        // (?:){3} — repeat of empty match simplifies to empty match
        val re = Parser.parse("(?:){3}", FLAGS)
        val simplified = Simplifier.simplify(re)
        Assertions.assertThat<RegexpOp?>(simplified!!.op).isEqualTo(RegexpOp.EMPTY_MATCH)
    }

    @Test
    fun simplifyCharClassEmpty() {
        // [^\x00-\x{10ffff}] — empty char class → NO_MATCH
        // We can't directly construct this through the parser, but we can verify
        // that full char class simplifies to ANY_CHAR through a pattern.
        val result: String = simplify("[\\s\\S]")
        Assertions.assertThat(result).isEqualTo(".")
    }

    @Test
    fun simplifyRepeatOfNonGreedy() {
        // a{2,3}? — non-greedy repeat
        val result: String = simplify("a{2,3}?")
        Assertions.assertThat(result).doesNotContain("{")
        Assertions.assertThat(result).contains("?")
    }

    companion object {
        private val FLAGS = ParseFlags.MATCH_NL or (ParseFlags.LIKE_PERL and ParseFlags.ONE_LINE.inv())

        private fun simplify(pattern: String?): String {
            val re = Parser.parse(pattern, FLAGS)
            val sre = Simplifier.simplify(re)
            return sre.toString()
        }

        fun simplifyTests(): Stream<Arguments?> {
            return Stream.of<Arguments?>( // Already-simple constructs
                Arguments.of("a", "a"),
                Arguments.of("ab", "ab"),
                Arguments.of("a|b", "a|b"),
                Arguments.of("ab|cd", "ab|cd"),
                Arguments.of("(ab)*", "(ab)*"),
                Arguments.of("(ab)+", "(ab)+"),
                Arguments.of("(ab)?", "(ab)?"),
                Arguments.of(".", "."),
                Arguments.of("^", "^"),
                Arguments.of("$", "$"),
                Arguments.of("[ac]", "[ac]"),
                Arguments.of(
                    "[^ac]",
                    "[^ac]"
                ),  // JDK treats POSIX bracket-class spelling as ordinary character-class text.

                Arguments.of("[[:alnum:]]", "[:al-nu]"),
                Arguments.of("[[:alpha:]]", "[:ahlp]"),
                Arguments.of("[[:blank:]]", "[:a-bk-ln]"),
                Arguments.of("[[:cntrl:]]", "[:clnrt]"),
                Arguments.of("[[:digit:]]", "[:dgit]"),
                Arguments.of("[[:graph:]]", "[:ag-hpr]"),
                Arguments.of("[[:lower:]]", "[:elorw]"),
                Arguments.of("[[:print:]]", "[:inprt]"),
                Arguments.of("[[:punct:]]", "[:cnpt-u]"),
                Arguments.of("[[:space:]]", "[:aceps]"),
                Arguments.of("[[:upper:]]", "[:epru]"),
                Arguments.of("[[:xdigit:]]", "[:dgitx]"),  // Perl character classes

                Arguments.of("\\d", "[0-9]"),
                Arguments.of("\\s", "[\\t-\\r ]"),
                Arguments.of("\\w", "[0-9A-Z_a-z]"),
                Arguments.of("\\D", "[^0-9]"),
                Arguments.of("\\S", "[^\\t-\\r ]"),
                Arguments.of("\\W", "[^0-9A-Z_a-z]"),
                Arguments.of("[\\d]", "[0-9]"),
                Arguments.of("[\\s]", "[\\t-\\r ]"),
                Arguments.of("[\\w]", "[0-9A-Z_a-z]"),
                Arguments.of("[\\D]", "[^0-9]"),
                Arguments.of("[\\S]", "[^\\t-\\r ]"),
                Arguments.of("[\\W]", "[^0-9A-Z_a-z]"),  // Posix repetitions

                Arguments.of("a{1}", "a"),
                Arguments.of("a{2}", "aa"),
                Arguments.of("a{5}", "aaaaa"),
                Arguments.of("a{0,1}", "a?"),
                Arguments.of("(a){0,2}", "(?:(a)(a)?)?"),
                Arguments.of("(a){0,4}", "(?:(a)(?:(a)(?:(a)(a)?)?)?)?"),
                Arguments.of("(a){2,6}", "(a)(a)(?:(a)(?:(a)(?:(a)(a)?)?)?)?"),
                Arguments.of("a{0,2}", "(?:aa?)?"),
                Arguments.of("a{0,4}", "(?:a(?:a(?:aa?)?)?)?"),
                Arguments.of("a{2,6}", "aa(?:a(?:a(?:aa?)?)?)?"),
                Arguments.of("a{0,}", "a*"),
                Arguments.of("a{1,}", "a+"),
                Arguments.of("a{2,}", "aa+"),
                Arguments.of("a{5,}", "aaaaa+"),  // Test that operators simplify their arguments.

                Arguments.of("(?:a{1,}){1,}", "a+"),
                Arguments.of("(a{1,}b{1,})", "(a+b+)"),
                Arguments.of("a{1,}|b{1,}", "a+|b+"),
                Arguments.of("(?:a{1,})*", "a*"),
                Arguments.of("(?:a{1,})+", "a+"),
                Arguments.of("(?:a{1,})?", "a*"),
                Arguments.of("a{0}", ""),  // Character class simplification

                Arguments.of("[ab]", "[a-b]"),
                Arguments.of("[a-za-za-z]", "[a-z]"),
                Arguments.of("[A-Za-zA-Za-z]", "[A-Za-z]"),
                Arguments.of("[ABCDEFGH]", "[A-H]"),
                Arguments.of("[AB-CD-EF-GH]", "[A-H]"),
                Arguments.of("[W-ZP-XE-R]", "[E-Z]"),
                Arguments.of("[a-ee-gg-m]", "[a-m]"),
                Arguments.of("[a-ea-ha-m]", "[a-m]"),
                Arguments.of("[a-ma-ha-e]", "[a-m]"),
                Arguments.of("[a-zA-Z0-9 -~]", "[ -~]"),  // Negated literal text character classes

                Arguments.of("[^[:cntrl:][:^cntrl:]]", "[^:\\^clnrt]"),  // Literal text character classes

                Arguments.of("[[:cntrl:][:^cntrl:]]", "[:\\^clnrt]"),  // Unicode case folding

                Arguments.of("(?i)A", "A"),
                Arguments.of("(?i)a", "[Aa]"),
                Arguments.of("(?i)K", "K"),
                Arguments.of("(?i)k", "[Kk]"),
                Arguments.of("(?i)\\x{212a}", "\\x{212a}"),
                Arguments.of("(?i)[a-z]", "[A-Za-z]"),
                Arguments.of("(?iu)K", "[Kk\\x{212a}]"),
                Arguments.of("(?iu)k", "[Kk\\x{212a}]"),
                Arguments.of("(?iu)\\x{212a}", "[Kk\\x{212a}]"),
                Arguments.of("(?iu)[a-z]", "[A-Za-z\\x{130}-\\x{131}\\x{17f}\\x{212a}]"),
                Arguments.of("(?i)[\\x00-\\x{FFFD}]", "[\\x00-\\x{fffd}]"),
                Arguments.of("(?i)[\\x00-\\x{10ffff}]", "."),  // Empty string as a regular expression.

                Arguments.of("(a|b|)", "(a|b|(?:))"),
                Arguments.of("(|)", "((?:)|(?:))"),
                Arguments.of("a()", "a()"),
                Arguments.of("(()|())", "(()|())"),
                Arguments.of("(a|)", "(a|(?:))"),
                Arguments.of("ab()cd()", "ab()cd()"),
                Arguments.of("()", "()"),
                Arguments.of("()*", "()*"),
                Arguments.of("()+", "()+"),
                Arguments.of("()?", "()?"),
                Arguments.of("(){0}", ""),
                Arguments.of("(){1}", "()"),
                Arguments.of("(){1,}", "()+"),
                Arguments.of("(){0,2}", "()?"),
                Arguments.of("(?:()){0,2}", "()?"),  // Empty-width ops: repetition count capped at 1

                Arguments.of("(?:^){0,}", "^*"),
                Arguments.of("(?:$){28,}", "$"),
                Arguments.of("(?-m:^){0,30}", "(?-m:^)?"),
                Arguments.of("(?-m:$){28,30}", "(?-m:$)"),
                Arguments.of("\\b(?:\\b\\B){999}\\B", "\\b\\b\\B\\B"),
                Arguments.of("\\b(?:\\b|\\B){999}\\B", "\\b(?:\\b|\\B)\\B"),  // NonGreedy should also be handled.
                Arguments.of("(?:^){0,}?", "^*?"),
                Arguments.of("(?:$){28,}?", "$"),
                Arguments.of("(?-m:^){0,30}?", "(?-m:^)??"),
                Arguments.of("(?-m:$){28,30}?", "(?-m:$)"),
                Arguments.of("\\b(?:\\b\\B){999}?\\B", "\\b\\b\\B\\B"),
                Arguments.of(
                    "\\b(?:\\b|\\B){999}?\\B",
                    "\\b(?:\\b|\\B)\\B"
                ),  // Coalescing: two-op combinations with a literal

                Arguments.of("a*a*", "a*"),
                Arguments.of("a*a+", "a+"),
                Arguments.of("a*a?", "a*"),
                Arguments.of("a*a{2}", "aa+"),
                Arguments.of("a*a{2,}", "aa+"),
                Arguments.of("a*a{2,3}", "aa+"),
                Arguments.of("a+a*", "a+"),
                Arguments.of("a+a+", "aa+"),
                Arguments.of("a+a?", "a+"),
                Arguments.of("a+a{2}", "aaa+"),
                Arguments.of("a+a{2,}", "aaa+"),
                Arguments.of("a+a{2,3}", "aaa+"),
                Arguments.of("a?a*", "a*"),
                Arguments.of("a?a+", "a+"),
                Arguments.of("a?a?", "(?:aa?)?"),
                Arguments.of("a?a{2}", "aaa?"),
                Arguments.of("a?a{2,}", "aa+"),
                Arguments.of("a?a{2,3}", "aa(?:aa?)?"),
                Arguments.of("a{2}a*", "aa+"),
                Arguments.of("a{2}a+", "aaa+"),
                Arguments.of("a{2}a?", "aaa?"),
                Arguments.of("a{2}a{2}", "aaaa"),
                Arguments.of("a{2}a{2,}", "aaaa+"),
                Arguments.of("a{2}a{2,3}", "aaaaa?"),
                Arguments.of("a{2,}a*", "aa+"),
                Arguments.of("a{2,}a+", "aaa+"),
                Arguments.of("a{2,}a?", "aa+"),
                Arguments.of("a{2,}a{2}", "aaaa+"),
                Arguments.of("a{2,}a{2,}", "aaaa+"),
                Arguments.of("a{2,}a{2,3}", "aaaa+"),
                Arguments.of("a{2,3}a*", "aa+"),
                Arguments.of("a{2,3}a+", "aaa+"),
                Arguments.of("a{2,3}a?", "aa(?:aa?)?"),
                Arguments.of("a{2,3}a{2}", "aaaaa?"),
                Arguments.of("a{2,3}a{2,}", "aaaa+"),
                Arguments.of(
                    "a{2,3}a{2,3}",
                    "aaaa(?:aa?)?"
                ),  // With a char class and any char (skipping \C which is not supported in Java):
                Arguments.of("\\d*\\d*", "[0-9]*"),
                Arguments.of(".*.*", ".*"),  // FoldCase works, but must be consistent:
                Arguments.of("(?i)A*a*", "A*[Aa]*"),
                Arguments.of("(?i)a+A+", "[Aa]+A+"),
                Arguments.of("(?i)A*(?-i)a*", "A*a*"),
                Arguments.of("(?i)a+(?-i)A+", "[Aa]+A+"),  // NonGreedy works, but must be consistent:
                Arguments.of("a*?a*?", "a*?"),
                Arguments.of("a+?a+?", "aa+?"),
                Arguments.of("a*?a*", "a*?a*"),
                Arguments.of("a+a+?", "a+a+?"),  // The second element is the literal, char class, any char:
                Arguments.of("a*a", "a+"),
                Arguments.of("\\d*\\d", "[0-9]+"),
                Arguments.of(".*.", ".+"),  // FoldCase consistent:
                Arguments.of("(?i)A*a", "A*[Aa]"),
                Arguments.of("(?i)a+A", "[Aa]+A"),
                Arguments.of("(?i)A*(?-i)a", "A*a"),
                Arguments.of(
                    "(?i)a+(?-i)A",
                    "[Aa]+A"
                ),  // The second element is a literal string beginning with the literal:
                Arguments.of("a*aa", "aa+"),
                Arguments.of("a*aab", "aa+b"),  // FoldCase consistent:
                Arguments.of("(?i)a*aa", "[Aa][Aa]+"),
                Arguments.of("(?i)a*aab", "[Aa][Aa]+[Bb]"),
                Arguments.of("(?i)a*(?-i)aa", "[Aa]*aa"),
                Arguments.of("(?i)a*(?-i)aab", "[Aa]*aab"),  // Negative tests with mismatching ops:
                Arguments.of("a*b*", "a*b*"),
                Arguments.of("\\d*\\D*", "[0-9]*[^0-9]*"),
                Arguments.of("a+b", "a+b"),
                Arguments.of("\\d+\\D", "[0-9]+[^0-9]"),
                Arguments.of("a?bb", "a?bb"),  // Negative tests with capturing groups:
                Arguments.of("(a*)a*", "(a*)a*"),
                Arguments.of("a+(a)", "a+(a)"),
                Arguments.of("(a?)(aa)", "(a?)(aa)"),  // Just for fun:
                Arguments.of(
                    "aa*aa+aa?aa{2}aaa{2,}aaa{2,3}a",
                    "aaaaaaaaaaaaaaaa+"
                ),  // Repeat min/max must be preserved during coalescing.

                Arguments.of("(?:a*aab){2}", "aa+baa+b"),  // Capture cap must be preserved during coalescing.

                Arguments.of("(a*aab)", "(aa+b)"),  // Squashing **, ++, ?? etc.

                Arguments.of("(?:(?:a){0,}){0,}", "a*"),
                Arguments.of("(?:(?:a){1,}){1,}", "a+"),
                Arguments.of("(?:(?:a){0,1}){0,1}", "a?"),
                Arguments.of("(?:(?:a){0,}){1,}", "a*"),
                Arguments.of("(?:(?:a){0,}){0,1}", "a*"),
                Arguments.of("(?:(?:a){1,}){0,}", "a*"),
                Arguments.of("(?:(?:a){1,}){0,1}", "a*"),
                Arguments.of("(?:(?:a){0,1}){0,}", "a*"),
                Arguments.of("(?:(?:a){0,1}){1,}", "a*")
            )
        }
    }
}
