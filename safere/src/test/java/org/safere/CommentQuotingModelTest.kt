// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.regex.PatternSyntaxException
import java.util.stream.Stream

/**
 * Preserves SafeRE's interpretation that ignored comment text cannot activate quoting.
 * 
 * 
 * See issue #858 and INTENTIONAL_DIVERGENCES.md. JDK 26 activates quotes inside comments; these
 * tests intentionally assert SafeRE's chosen comment semantics instead. The JDK documentation does
 * not explicitly specify precedence between quoting and comment removal.
 */
@DisabledForCrosscheck("Quoting constructs inside ignored comments must not affect subsequent lines")
internal class CommentQuotingModelTest {
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("commentContexts")
    fun commentQuotesDoNotChangeFollowingSyntax(
        description: String?, prefix: String?, suffix: String?, flags: Int, newline: String?
    ) {
        val comment = prefix + "#\\Q" + newline

        val wildcard = Pattern.compile(comment + "." + suffix, flags)
        Assertions.assertThat(wildcard.matcher("x").matches()).isTrue()
        Assertions.assertThat(wildcard.matcher(".").matches()).isTrue()

        val capture = Pattern.compile(comment + "(a+)" + suffix, flags).matcher("aaa")
        Assertions.assertThat(capture.matches()).isTrue()
        Assertions.assertThat(capture.groupCount()).isEqualTo(1)
        Assertions.assertThat(capture.group(1)).isEqualTo("aaa")

        val range = Pattern.compile(prefix + "[a#\\Q" + newline + "-z]" + suffix, flags)
        Assertions.assertThat(range.matcher("m").matches()).isTrue()
        Assertions.assertThat(range.matcher("-").matches()).isFalse()

        val quote = Pattern.compile(comment + "\\Q(\\E" + suffix, flags)
        Assertions.assertThat(quote.matcher("(").matches()).isTrue()
        Assertions.assertThat(quote.matcher("Q(").matches()).isFalse()
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("commentContexts")
    fun commentQuotesDoNotHideSyntaxErrors(
        description: String?, prefix: String?, suffix: String?, flags: Int, newline: String?
    ) {
        for (invalid in arrayOf<String>("(", "*", ".\\E")) {
            val regex = prefix + "#\\Q" + newline + invalid + suffix
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile(regex, flags) })
                .`as`("%s: syntax after ignored comment: %s", description, invalid)
                .isInstanceOf(PatternSyntaxException::class.java)
        }
    }

    companion object {
        @JvmStatic
        private fun commentContexts(): Stream<Arguments?>? {
            return Stream.of<String?>("\n", "\r", "\r\n")
                .flatMap<Arguments?> { newline: String? ->
                    val label = newline!!.replace("\r", "CR").replace("\n", "LF")
                    Stream.of<Arguments?>(
                        Arguments.of("compile flag / " + label, "", "", Pattern.COMMENTS, newline),
                        Arguments.of("inline flag / " + label, "(?x)", "", 0, newline),
                        Arguments.of("scoped flag / " + label, "(?x:", ")", 0, newline)
                    )
                }
        }
    }
}
