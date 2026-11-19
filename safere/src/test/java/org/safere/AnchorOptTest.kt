// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

@DisabledForCrosscheck("implementation-path assertions use package-private SafeRE internals")
internal class AnchorOptTest {
    @Test
    fun httpPatternIsOnePassAndAnchored() {
        val p = Pattern.compile("^(?:GET|POST) +([^ ]+) HTTP")
        // Access package-private methods
        Assertions.assertThat(p.prog().anchorStart()).isTrue()
        Assertions.assertThat<OnePass?>(p.onePass()).isNotNull()
    }

    @Test
    fun httpPatternFindWorks() {
        val p = Pattern.compile("^(?:GET|POST) +([^ ]+) HTTP")
        val m = p.matcher("GET /foo HTTP/1.1")
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.group(1)).isEqualTo("/foo")
    }

    @Test
    fun anchoredPatternSecondFindReturnsFalse() {
        val p = Pattern.compile("^(?:GET|POST) +([^ ]+) HTTP")
        val m = p.matcher("GET /foo HTTP/1.1")
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.find()).isFalse()
    }

    @Test
    fun anchoredPatternNoMatchAtNonZero() {
        val p = Pattern.compile("^hello")
        val m = p.matcher("say hello world")
        Assertions.assertThat(m.find()).isFalse()
    }

    @Test
    fun anchoredPatternMatchAtZero() {
        val p = Pattern.compile("^hello")
        val m = p.matcher("hello world")
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.group()).isEqualTo("hello")
    }
}
