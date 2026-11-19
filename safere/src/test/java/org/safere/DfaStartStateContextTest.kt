// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets

/** DFA start states memoized by word context must give the same results as the NFA.  */
@DisabledForCrosscheck("compares SafeRE engine paths through package-private EnginePathOptions")
internal class DfaStartStateContextTest {
    @ParameterizedTest
    @ValueSource(
        strings = ["\\bfoo", "foo\\b", "\\bfoo\\b", "\\Bfoo", "\\B\\w+", "(?U)\\bfoo", "(?U)\\w+\\b", "(?U)\\Bo", "\\b", "\\B", "(?U)\\b", "(?U)\\B", "(?U)\\ba?", "(?U)\\b\\W?", "(?U).\\b", "[a-z]+", "o+"
        ]
    )
    fun findFromEveryStartMatchesTheNfa(regex: String?) {
        val dfa = Pattern.compile(regex)
        val nfa = Pattern.compile(regex, 0, EnginePathOptions.builder().dfa(false).build())
        for (input in INPUTS) {
            for (from in 0..input.length) {
                Assertions.assertThat<String?>(trace(dfa.matcher(input), from))
                    .`as`("/%s/ on %s from %d", regex, input, from)
                    .isEqualTo(trace(nfa.matcher(input), from))
            }
            val bytes = input.toByteArray(StandardCharsets.UTF_8)
            Assertions.assertThat<String?>(trace(dfa.matcher(Utf8Input.trusted(bytes))))
                .`as`("UTF-8 /%s/ on %s", regex, input)
                .isEqualTo(trace(nfa.matcher(Utf8Input.trusted(bytes))))
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["(?U)\\b", "(?U)\\ba?", "(?U)\\ba??", "(?U)\\b(a)?", "(?U)\\b[a-z]*", "(?U)\\b\\w?", "(?U)\\b\\W?", "(?U).\\b"
        ]
    )
    fun wordAssertionsAfterSplitSurrogateCursorsMatchInputContext(regex: String) {
        val pattern = Pattern.compile(regex)
        val jdk = java.util.regex.Pattern.compile(regex)
        for (input in mutableListOf<String>(
            "\uD801\uDC00b",
            "\uD801\uDC00",
            "\uDC00b",
            "\uD801b",
            "\uD83D\uDE00b",  // Resuming after an empty match before a supplementary letter splits the pair; the
            // reverse DFA must still see the letter before "a" (no boundary) or " " (boundary).
            "\uD801\uDC00a",
            "-\uD801\uDC00a",
            "\uD801\uDC00 a",
            "\uD83D\uDE00\uD801\uDC00a",
            "1\uD801a",
            "1\uDC00a"
        )) {
            for (from in 0..input!!.length) {
                val expected = jdk.matcher(input)
                val bounds: MutableList<String> = ArrayList<String>()
                var found = expected.find(from)
                while (found) {
                    bounds.add(expected.start().toString() + ":" + expected.end())
                    found = expected.find()
                }
                Assertions.assertThat<String?>(trace(pattern.matcher(input), from))
                    .`as`("/%s/ input=%s from=%s", regex, input, from)
                    .isEqualTo(bounds)
            }
        }
    }

    companion object {
        private val INPUTS: MutableList<String> = mutableListOf<String>(
            "foo bar_baz foo",
            "éfoo foo-é föo_",
            "x\u00DFfoo\u00DF foo",
            "1foo2 _foo_ \u0661foo",
            "foo\u0301 foo\u200Dfoo",
            "\uD835\uDC00foo foo\uD835\uDC00",
            "\uD801\uDC00b",
            "\uD801\uDC00",
            "\uDC00b",
            "\uD801b",
            "\uD801\uDC00b\uD801\uDC00",
            "\uD801\uDC00a",
            " \uD801\uDC00a \uD801\uDC00-",
            ""
        )

        private fun trace(matcher: org.safere.Matcher, from: Int): MutableList<String> {
            val out: MutableList<String> = ArrayList<String>()
            var found = matcher.find(from)
            while (found) {
                out.add(matcher.start().toString() + ":" + matcher.end())
                found = matcher.find()
            }
            return out
        }

        private fun trace(matcher: Utf8Matcher): MutableList<String> {
            val out: MutableList<String> = ArrayList<String>()
            while (matcher.find()) {
                out.add(matcher.start().toString() + ":" + matcher.end())
            }
            return out
        }
    }
}
