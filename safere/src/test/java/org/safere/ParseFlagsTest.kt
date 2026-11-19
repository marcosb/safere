// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

/** Tests for [ParseFlags].  */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class ParseFlagsTest {
    @Test
    fun flagsArePowersOfTwo() {
        Assertions.assertThat(ParseFlags.NONE).isEqualTo(0)
        Assertions.assertThat(ParseFlags.FOLD_CASE).isEqualTo(1)
        Assertions.assertThat(ParseFlags.LITERAL).isEqualTo(2)
        Assertions.assertThat(ParseFlags.CLASS_NL).isEqualTo(4)
        Assertions.assertThat(ParseFlags.DOT_NL).isEqualTo(8)
        Assertions.assertThat(ParseFlags.ONE_LINE).isEqualTo(16)
        Assertions.assertThat(ParseFlags.LATIN1).isEqualTo(32)
        Assertions.assertThat(ParseFlags.NON_GREEDY).isEqualTo(64)
        Assertions.assertThat(ParseFlags.PERL_CLASSES).isEqualTo(128)
        Assertions.assertThat(ParseFlags.PERL_B).isEqualTo(256)
        Assertions.assertThat(ParseFlags.PERL_X).isEqualTo(512)
        Assertions.assertThat(ParseFlags.UNICODE_GROUPS).isEqualTo(1024)
        Assertions.assertThat(ParseFlags.COMMENTS).isEqualTo(2048)
        Assertions.assertThat(ParseFlags.UNICODE_CHAR_CLASS).isEqualTo(4096)
        Assertions.assertThat(ParseFlags.NEVER_NL).isEqualTo(8192)
        Assertions.assertThat(ParseFlags.NEVER_CAPTURE).isEqualTo(16384)
        Assertions.assertThat(ParseFlags.WAS_DOLLAR).isEqualTo(32768)
        Assertions.assertThat(ParseFlags.UNIX_LINES).isEqualTo(65536)
        Assertions.assertThat(ParseFlags.UNICODE_CASE).isEqualTo(131072)
    }

    @Test
    fun matchNlCombinesClassNlAndDotNl() {
        Assertions.assertThat(ParseFlags.MATCH_NL).isEqualTo(ParseFlags.CLASS_NL or ParseFlags.DOT_NL)
    }

    @Test
    fun likePerlCombinesExpectedFlags() {
        val expected =
            (ParseFlags.CLASS_NL
                    or ParseFlags.ONE_LINE
                    or ParseFlags.PERL_CLASSES
                    or ParseFlags.PERL_B
                    or ParseFlags.PERL_X
                    or ParseFlags.UNICODE_GROUPS)
        Assertions.assertThat(ParseFlags.LIKE_PERL).isEqualTo(expected)
    }

    @Test
    fun allFlagsCoversAllBits() {
        Assertions.assertThat(ParseFlags.ALL_FLAGS).isEqualTo((1 shl 18) - 1)
    }

    @Test
    fun likePerlIncludesPerlFeatures() {
        Assertions.assertThat((ParseFlags.LIKE_PERL and ParseFlags.PERL_CLASSES) != 0).isTrue()
        Assertions.assertThat((ParseFlags.LIKE_PERL and ParseFlags.PERL_B) != 0).isTrue()
        Assertions.assertThat((ParseFlags.LIKE_PERL and ParseFlags.PERL_X) != 0).isTrue()
        Assertions.assertThat((ParseFlags.LIKE_PERL and ParseFlags.UNICODE_GROUPS) != 0).isTrue()
    }
}
