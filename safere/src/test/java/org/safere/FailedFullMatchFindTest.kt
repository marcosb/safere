// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test

/** Specification-based coverage for the first find after a failed full match.  */
@DisabledForCrosscheck("SafeRE follows the documented initial find position; JDK leaks failed full-match state")
internal class FailedFullMatchFindTest {
    @Test
    fun failedFullMatchesPreserveInitialFindPosition() {
        // Issue #818: Matcher.find() starts at the region beginning when no find has succeeded.
        // Preserve that documented behavior, even where JDK 26.0.1 skips the initial match.
        for (anchor in mutableListOf<String>("\\A", "^")) {
            for (atom in mutableListOf<String>("a", "😀")) {
                for (quantifier in mutableListOf<String>("?", "*", "{0,2}", "??", "*?")) {
                    for (empty in mutableListOf<Boolean>(false, true)) {
                        for (offset in mutableListOf<Int>(0, 2)) {
                            for (attempts in mutableListOf<Int>(1, 2)) {
                                val regex = anchor + "(" + atom + quantifier + ")"
                                val input = (if (empty) "" else atom) + "!"
                                val text = if (offset == 0) input else "xx" + input + "yy"
                                val matcher = Pattern.compile(regex).matcher(text)
                                if (offset != 0) {
                                    matcher.region(offset!!, offset + input.length)
                                }
                                for (attempt in 0..<attempts!!) {
                                    Assertions.assertThat(matcher.matches()).`as`("full match: %s", regex).isFalse()
                                    Assertions.assertThat(matcher.hasMatch()).isFalse()
                                    Assertions.assertThatThrownBy(ThrowingCallable { matcher.group() }).isInstanceOf(
                                        IllegalStateException::class.java
                                    )
                                }

                                val reluctant = quantifier == "??" || quantifier == "*?"
                                val expected: String = (if (empty || reluctant) "" else atom)!!
                                Assertions.assertThat(matcher.find())
                                    .`as`("first find: %s, input=%s, offset=%s", regex, input, offset)
                                    .isTrue()
                                Assertions.assertThat(matcher.start()).isEqualTo(offset)
                                Assertions.assertThat(matcher.end()).isEqualTo(offset + expected.length)
                                Assertions.assertThat(matcher.group()).isEqualTo(expected)
                                Assertions.assertThat(matcher.group(1)).isEqualTo(expected)
                                Assertions.assertThat(matcher.start(1)).isEqualTo(offset)
                                Assertions.assertThat(matcher.end(1)).isEqualTo(offset + expected.length)
                                Assertions.assertThat(matcher.find()).isFalse()
                            }
                        }
                    }
                }
            }
        }
    }
}
