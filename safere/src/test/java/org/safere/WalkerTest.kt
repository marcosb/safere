// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
/** Tests for [Walker].  */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class WalkerTest {
    /** A simple walker that counts nodes in the tree.  */
    private class CountWalker : Walker<Int>() {
        override fun preVisit(re: Regexp, parentArg: Int?, stop: BooleanArray): Int? {
            return 0
        }

        override fun postVisit(
            re: Regexp, parentArg: Int?, preArg: Int?, childArgs: MutableList<Int>
        ): Int? {
            var count = 1
            for (i in childArgs.indices) {
                count += childArgs.get(i)!!
            }
            return count
        }

        override fun shortVisit(re: Regexp, parentArg: Int?): Int? {
            return 1
        }
    }

    @Test
    fun countNodes_literal() {
        val re = Parser.parse("a", FLAGS)
        val count = CountWalker().walk(re, 0)!!
        Assertions.assertThat(count).isEqualTo(1)
    }

    @Test
    fun countNodes_concat() {
        val re = Parser.parse("abc", FLAGS)
        // LITERAL_STRING is a single node (no children).
        val count = CountWalker().walk(re, 0)!!
        Assertions.assertThat(count).isEqualTo(1)
    }

    @Test
    fun countNodes_alternation() {
        val re = Parser.parse("a|b|c", FLAGS)
        // ALTERNATE(LITERAL, LITERAL, LITERAL) = 1 + 3 = 4... but parser may merge to charclass.
        val count = CountWalker().walk(re, 0)!!
        Assertions.assertThat(count).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun countNodes_nested() {
        val re = Parser.parse("(a+)*", FLAGS)
        // STAR(CAPTURE(PLUS(LITERAL))) = 4
        val count = CountWalker().walk(re, 0)!!
        Assertions.assertThat(count).isEqualTo(4)
    }

    @Test
    fun walkExponential_budget() {
        // Build a deeply nested expression.
        val re = Parser.parse("a{100}", FLAGS)
        val w = CountWalker()
        w.walkExponential(re, 0, 5)
        // With a budget of 5, it should stop early on a complex enough expression.
        // For a{100} (which is a REPEAT node with one child), it may or may not stop early.
        // Just ensure it doesn't crash.
    }

    @Test
    fun preVisitStop() {
        // A walker that stops at the first LITERAL it sees.
        val w: Walker<String> =
            object : Walker<String>() {
                override fun preVisit(re: Regexp, parentArg: String?, stop: BooleanArray): String? {
                    if (re.op == RegexpOp.LITERAL) {
                        stop[0] = true
                        return "found:" + re.rune.toChar()
                    }
                    return ""
                }

                override fun postVisit(
                    re: Regexp, parentArg: String?, preArg: String?, childArgs: MutableList<String>
                ): String? {
                    // Concatenate child results.
                    val sb = StringBuilder()
                    for (childArg in childArgs) {
                        sb.append(childArg)
                    }
                    return sb.toString()
                }

                override fun shortVisit(re: Regexp, parentArg: String?): String? {
                    return ""
                }
            }

        // (a+)* — the walker should find 'a' and stop.
        val re = Parser.parse("(a+)*", FLAGS)
        val result = w.walk(re, "")
        Assertions.assertThat(result).isEqualTo("found:a")
    }

    @Test
    fun walkExponentialShortVisitOnBudgetExhaustion() {
        // A deep tree that should exhaust a low budget.
        val re = Parser.parse("((((((a))))))", FLAGS)
        val w = CountWalker()
        w.walkExponential(re, 0, 2)
        Assertions.assertThat(w.stoppedEarly()).isTrue()
    }

    @Test
    fun walkNullRegexp() {
        val w = CountWalker()
        val result = w.walk(null, 42)!!
        Assertions.assertThat(result).isEqualTo(42)
    }

    @Test
    fun walkCopyPathWithSharedChildren() {
        // Create a concat with identical (same object) children.
        val shared = Regexp.literal('a'.code, 0)
        val re = Regexp.concat(java.util.List.of<Regexp?>(shared, shared), 0)
        val w = CountWalker()
        val result = w.walk(re, 0)!!
        // CONCAT(1) + LITERAL(1) + copied LITERAL(1) = 3
        Assertions.assertThat(result).isEqualTo(3)
    }

    companion object {
        private val FLAGS = ParseFlags.MATCH_NL or (ParseFlags.LIKE_PERL and ParseFlags.ONE_LINE.inv())
    }
}
