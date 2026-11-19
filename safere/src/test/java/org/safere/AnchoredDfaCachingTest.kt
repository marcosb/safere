// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.function.IntConsumer

/** Work-bound tests for DFA transition caching in anchored patterns.  */
@DisabledForCrosscheck("WorkCounter and the UTF-8 input API are SafeRE-specific")
@Tag("work-counter")
internal class AnchoredDfaCachingTest {
    @Test
    fun stringAnchorsKeepOrdinaryDfaTransitionsCached() {
        val pattern = Pattern.compile(FRUIT_MARKUP_PATTERN)
        assertCachedTransitionWork(
            IntConsumer { size: Int ->
                val input: String = nonMatchingInput(size)
                Assertions.assertThat(pattern.matcher(input).replaceAll("$1")).isEqualTo(input)
            })
    }

    @Test
    fun utf8AnchorsKeepOrdinaryDfaTransitionsCached() {
        val prog = Compiler.compile(Parser.parse(FRUIT_MARKUP_PATTERN, FLAGS))
        val dfa = Dfa(prog, 10000, Dfa.buildSetup(prog), false)
        assertCachedTransitionWork(
            IntConsumer { size: Int ->
                val input = nonMatchingInput(size).toByteArray(StandardCharsets.UTF_8)
                val result = dfa.doSearch(Utf8InputScanner(input), true, false)
                Assertions.assertThat<Dfa.SearchResult?>(result).isNotNull()
                Assertions.assertThat(result!!.matched).isFalse()
            })
    }

    @Test
    fun multilineBeginningAnchorKeepsStringTransitionsCached() {
        val pattern = Pattern.compile(MULTILINE_LINE_PATTERN)
        assertCachedTransitionWork(
            IntConsumer { size: Int ->
                Assertions.assertThat(pattern.matcher(multilineNonMatchingInput(size)).find()).isFalse()
            })
    }

    @Test
    fun multilineBeginningAnchorKeepsUtf8TransitionsCached() {
        val prog = Compiler.compile(Parser.parse(MULTILINE_LINE_PATTERN, FLAGS))
        val dfa = Dfa(prog, 10000, Dfa.buildSetup(prog), false)
        assertCachedTransitionWork(
            IntConsumer { size: Int ->
                val input = multilineNonMatchingInput(size).toByteArray(StandardCharsets.UTF_8)
                val result = dfa.doSearch(Utf8InputScanner(input), false, false)
                Assertions.assertThat<Dfa.SearchResult?>(result).isNotNull()
                Assertions.assertThat(result!!.matched).isFalse()
            })
    }

    @Test
    fun multilineEndingAnchorKeepsStringLineFeedTransitionsCached() {
        val pattern = Pattern.compile("(?m)(?:x+$)y")
        // The leading "y" cannot complete a match, but it keeps the required-class reject prefilter
        // for [y] from deciding the call before the DFA runs.
        assertCachedTransitionWork(
            IntConsumer { size: Int ->
                Assertions.assertThat(pattern.matcher("y" + "xxx\n".repeat(size)).find()).isFalse()
            })
    }

    @Test
    fun multilineEndingAnchorKeepsUtf8LineFeedTransitionsCached() {
        val prog = Compiler.compile(Parser.parse("(?m)(?:x+$)y", FLAGS))
        val dfa = Dfa(prog, 10000, Dfa.buildSetup(prog), false)
        assertCachedTransitionWork(
            IntConsumer { size: Int ->
                val input = "xxx\n".repeat(size).toByteArray(StandardCharsets.UTF_8)
                val result = dfa.doSearch(Utf8InputScanner(input), false, false)
                Assertions.assertThat<Dfa.SearchResult?>(result).isNotNull()
                Assertions.assertThat(result!!.matched).isFalse()
            })
    }

    companion object {
        private val FLAGS =
            ParseFlags.PERL_X or ParseFlags.PERL_CLASSES or ParseFlags.PERL_B or ParseFlags.UNICODE_GROUPS
        private const val FRUIT_MARKUP_PATTERN = "^\\s*<(\\QApple\\E|\\QBanana\\E|\\QCherry\\E)>\\s*$"
        private const val MULTILINE_LINE_PATTERN = "(?m)(?:^|,)(?:\"(target)\"|(target))"

        private fun assertCachedTransitionWork(operation: IntConsumer) {
            operation.accept(1000)
            operation.accept(10000)

            val smallerWork = WorkCounter.countForTesting(Runnable { operation.accept(1000) })
            val largerWork = WorkCounter.countForTesting(Runnable { operation.accept(10000) })

            Assertions.assertThat(largerWork)
                .`as`("ordinary anchored transitions should use the DFA cache independent of input length")
                .isLessThanOrEqualTo(smallerWork * 2 + 10)
        }

        private fun nonMatchingInput(size: Int): String {
            val tag = "<Orange>"
            val leadingSpaces = (size - tag.length) / 2
            return " ".repeat(leadingSpaces) + tag + " ".repeat(size - tag.length - leadingSpaces)
        }

        private fun multilineNonMatchingInput(size: Int): String {
            return "value,other\n".repeat((size + 11) / 12).substring(0, size)
        }
    }
}
