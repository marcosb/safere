// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.safere.Pattern.KeywordAlternation

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class MatchDescriptorTest {
    @Test
    fun noneDescriptorHasNoFastPath() {
        Assertions.assertThat(MatchDescriptor.NONE.hasFastPath()).isFalse()
        Assertions.assertThat(MatchDescriptor.NONE.literalMatch).isNull()
        Assertions.assertThat<CharClassScanInfo?>(MatchDescriptor.NONE.singleCharClass).isNull()
        Assertions.assertThat<KeywordAlternation?>(MatchDescriptor.NONE.keywordAlternation).isNull()
        Assertions.assertThat<CharClassMatchInfo?>(MatchDescriptor.NONE.charClassMatch).isNull()
    }

    @Test
    fun literalMatchPatternProducesLiteralDescriptor() {
        val p = Pattern.compile("hello")
        val desc = p.matchDescriptor()
        Assertions.assertThat(desc.hasFastPath()).isTrue()
        Assertions.assertThat(desc.literalMatch).isEqualTo("hello")
        Assertions.assertThat(p.literalMatch()).isEqualTo("hello")
    }

    @Test
    fun singleCharClassPatternProducesSingleCharClassDescriptor() {
        val p = Pattern.compile("[a-z]")
        val desc = p.matchDescriptor()
        Assertions.assertThat(desc.hasFastPath()).isTrue()
        Assertions.assertThat<CharClassScanInfo?>(desc.singleCharClass).isNotNull()
        Assertions.assertThat(desc.singleCharClass!!.isAscii).isTrue()
    }

    @Test
    fun repeatedCharClassPatternProducesCharClassMatchDescriptor() {
        val p = Pattern.compile("[a-z]+")
        val desc = p.matchDescriptor()
        Assertions.assertThat(desc.hasFastPath()).isTrue()
        Assertions.assertThat<CharClassMatchInfo?>(desc.charClassMatch).isNotNull()
        Assertions.assertThat(desc.charClassMatch!!.ranges).isNotNull()
    }

    @Test
    fun keywordAlternationPatternProducesKeywordAlternationDescriptor() {
        val p = Pattern.compile("foo|bar|baz", Pattern.CASE_INSENSITIVE)
        val desc = p.matchDescriptor()
        if (desc.keywordAlternation != null) {
            Assertions.assertThat(desc.hasFastPath()).isTrue()
            Assertions.assertThat<String?>(desc.keywordAlternation.keywords).isNotEmpty()
        }
    }

    @Test
    fun complexPatternHasNoMatchFastPath() {
        val p = Pattern.compile("a(b|c)*d")
        val desc = p.matchDescriptor()
        Assertions.assertThat(desc.literalMatch).isNull()
        Assertions.assertThat<CharClassScanInfo?>(desc.singleCharClass).isNull()
        Assertions.assertThat<CharClassMatchInfo?>(desc.charClassMatch).isNull()
    }
}
