// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import java.util.function.Function
import java.util.regex.MatchResult

/**
 * Tests for the start-anchored fast reject in `Matcher.replaceImpl`.
 * 
 * 
 * A start-anchored pattern skips the unanchored reject prefilter, so before this fast path such
 * patterns got no whole-input rejection at all from `replaceAll` and `replaceFirst`.
 * These tests pin the behaviour that the fast path must preserve exactly.
 */
internal class StartAnchoredReplaceRejectTest {
    @Test
    fun rejectedInputIsReturnedUnchangedAndIdenticalToTheJdk() {
        val inputs: MutableList<String> = mutableListOf<String>(
            "",
            "x",
            "(",
            "[",
            "No citation marker here at all.",
            "(Not a source marker)",
            "[Nope]",
            " (Source: 1) leading space defeats the anchor",
            "\n(Source: 1) leading newline defeats the anchor"
        )

        for (regex in java.util.List.of<String?>(CITATION_PARENS, CITATION_BRACKETS)) {
            val jdk = java.util.regex.Pattern.compile(regex)
            val safere = Pattern.compile(regex)
            for (input in inputs) {
                Assertions.assertThat(safere.matcher(input).replaceAll("X"))
                    .`as`("replaceAll %s on %s", regex, input)
                    .isEqualTo(jdk.matcher(input).replaceAll("X"))
                Assertions.assertThat(safere.matcher(input).replaceFirst("X"))
                    .`as`("replaceFirst %s on %s", regex, input)
                    .isEqualTo(jdk.matcher(input).replaceFirst("X"))
            }
        }
    }

    @Test
    fun matchingInputStillReplacesCorrectly() {
        val inputs: MutableList<String> = mutableListOf<String>(
            "(Source: 1) body",
            "(Source: 1, 2) body",
            "(Source: 1, Source: 2)   body",
            "(source: 42) body",
            "(Source: 1) (Source: 2) body"
        )

        val jdk = java.util.regex.Pattern.compile(CITATION_PARENS)
        val safere = Pattern.compile(CITATION_PARENS)
        for (input in inputs) {
            Assertions.assertThat(safere.matcher(input).replaceAll("X"))
                .`as`("replaceAll on %s", input)
                .isEqualTo(jdk.matcher(input).replaceAll("X"))
            Assertions.assertThat(safere.matcher(input).replaceFirst("X"))
                .`as`("replaceFirst on %s", input)
                .isEqualTo(jdk.matcher(input).replaceFirst("X"))
        }
    }

    @Test
    fun anchoredCharacterClassPrefixRejectsAndMatches() {
        val pattern = Pattern.compile("^[0-9]+ items")
        Assertions.assertThat(pattern.matcher("no digits").replaceAll("X")).isEqualTo("no digits")
        Assertions.assertThat(pattern.matcher("12 items left").replaceAll("X")).isEqualTo("X left")
    }

    @Test
    fun multilineStartAnchorIsUnaffected() {
        // (?m)^ is a begin-line assertion, not prog().anchorStart(); matches on later lines must
        // still be found and replaced.
        val pattern = Pattern.compile("(?m)^\\(Source: \\d+\\)\\s*")
        Assertions.assertThat(pattern.matcher("first\n(Source: 7) second").replaceAll(""))
            .isEqualTo("first\nsecond")
        Assertions.assertThat(pattern.matcher("first\nsecond").replaceAll("X")).isEqualTo("first\nsecond")
    }

    @Test
    fun rejectedReplaceLeavesTheMatcherWithNoMatch() {
        // The fast path must leave the matcher in the same observable state as the slow path: no
        // match available, and the same state the sibling unanchored reject-prefilter arm produces.
        val anchored = Pattern.compile(CITATION_PARENS).matcher("no marker")
        Assertions.assertThat(anchored.replaceAll("X")).isEqualTo("no marker")
        Assertions.assertThatThrownBy(ThrowingCallable { anchored.group() })
            .isInstanceOf(IllegalStateException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { anchored.start() })
            .isInstanceOf(IllegalStateException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { anchored.end() })
            .isInstanceOf(IllegalStateException::class.java)

        val unanchored = Pattern.compile("zqxjv[0-9]+").matcher("no marker")
        Assertions.assertThat(unanchored.replaceAll("X")).isEqualTo("no marker")
        Assertions.assertThatThrownBy(ThrowingCallable { unanchored.group() })
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun matcherRemainsReusableAfterARejectedReplace() {
        val matcher = Pattern.compile(CITATION_PARENS).matcher("no marker")
        Assertions.assertThat(matcher.replaceAll("X")).isEqualTo("no marker")

        // Still usable for a fresh search, and for a fresh input.
        Assertions.assertThat(matcher.find()).isFalse()
        matcher.reset("(Source: 3) body")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.group()).isEqualTo("(Source: 3) ")
        Assertions.assertThat(matcher.replaceAll("X")).isEqualTo("Xbody")
    }

    @Test
    fun regionIsResetByReplaceJustAsBefore() {
        // replaceImpl calls reset(), which clears any region, so a region set beforehand does not
        // narrow the anchored check. Pin that this is still true.
        val matcher = Pattern.compile(CITATION_PARENS).matcher("zz(Source: 1) body")
        matcher.region(2, matcher.regionEnd())
        Assertions.assertThat(matcher.replaceAll("X")).isEqualTo("zz(Source: 1) body")
    }

    @Test
    fun functionOverloadsAgreeWithStringOverloads() {
        // The Function overloads route through find(), which already had the anchored reject. The
        // String overloads bypassed find(); after the fix both families must agree.
        for (regex in java.util.List.of<String?>(CITATION_PARENS, CITATION_BRACKETS)) {
            for (input in mutableListOf<String>("no marker", "(Source: 1) body", "[Source: 2] body")) {
                val pattern = Pattern.compile(regex)
                Assertions.assertThat(pattern.matcher(input).replaceAll(Function { r: MatchResult? -> "X" }))
                    .`as`("replaceAll(Function) %s on %s", regex, input)
                    .isEqualTo(pattern.matcher(input).replaceAll("X"))
                Assertions.assertThat(pattern.matcher(input).replaceFirst(Function { r: MatchResult? -> "X" }))
                    .`as`("replaceFirst(Function) %s on %s", regex, input)
                    .isEqualTo(pattern.matcher(input).replaceFirst("X"))
            }
        }
    }

    @Test
    fun replacementIsStillValidatedBeforeRejection() {
        // Objects.requireNonNull(replacement) runs before the reject arm, so a null replacement must
        // still throw even when the input cannot possibly match.
        val matcher = Pattern.compile(CITATION_PARENS).matcher("no marker")
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.replaceAll(null as String?) })
            .isInstanceOf(NullPointerException::class.java)
    }

    companion object {
        /**
         * Representative start-anchored scrubbers: both compile to `anchorStart = true` with a
         * single-character `anchoredPrefix` and a null `rejectPrefilter`, which is the exact
         * shape the fast path targets.
         */
        private const val CITATION_PARENS = "^\\([Ss]ource:\\s*\\d+(?:\\s*,\\s*(?:[Ss]ource:\\s*)?\\d+)*\\s*\\)\\s*"

        private const val CITATION_BRACKETS = "^\\[[Ss]ource:\\s*\\d+(?:\\s*,\\s*(?:[Ss]ource:\\s*)?\\d+)*\\s*\\]\\s*"
    }
}
