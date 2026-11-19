// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.safere.Pattern
import org.safere.fuzz.FuzzSupport.CompiledPattern

internal class ExploratoryGraphemeTest {
    @Test
    fun intentionalGraphemeDifferenceRemainsAVisibleFinding() {
        val input = "\uda5f\udc2e\u0301"
        Assertions.assertThat(Pattern.compile("\\X").matcher(input).matches()).isTrue()
        val pair = FuzzSupport.compileOrSkip("\\X", 0).matcher(input)
        Assertions.assertThatThrownBy(ThrowingCallable { pair.find() }).isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun intentionalCrLfSplitDifferenceRemainsAVisibleFinding() {
        val regex = "\\r\\b{g}\\n"
        val input = "#\r\n\r\n$"
        Assertions.assertThat<String?>(Pattern.compile(regex).split(input)).containsExactly(input)
        val pattern = FuzzSupport.compileOrSkip(regex, 0)
        Assertions.assertThat<CompiledPattern?>(pattern).isNotNull()
        Assertions.assertThatThrownBy(ThrowingCallable { pattern!!.split(input) })
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("split divergence")
        Assertions.assertThatThrownBy(ThrowingCallable { pattern!!.splitWithDelimiters(input) })
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("splitWithDelimiters divergence")
    }
}
