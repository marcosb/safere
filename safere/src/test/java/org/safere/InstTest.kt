// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

/** Tests for [Inst].  */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class InstTest {
    @Test
    fun defaultIssFail() {
        val inst = Inst()
        Assertions.assertThat<InstOp?>(inst.op).isEqualTo(InstOp.FAIL)
    }

    @Test
    fun initAlt() {
        val inst = Inst()
        inst.initAlt(3, 5)
        Assertions.assertThat<InstOp?>(inst.op).isEqualTo(InstOp.ALT)
        Assertions.assertThat(inst.out).isEqualTo(3)
        Assertions.assertThat(inst.out1).isEqualTo(5)
    }

    @Test
    fun initCharRange() {
        val inst = Inst()
        inst.initCharRange('a'.code, 'z'.code, false, 7)
        Assertions.assertThat<InstOp?>(inst.op).isEqualTo(InstOp.CHAR_RANGE)
        Assertions.assertThat(inst.lo).isEqualTo('a'.code)
        Assertions.assertThat(inst.hi).isEqualTo('z'.code)
        Assertions.assertThat(inst.foldCase).isFalse()
        Assertions.assertThat(inst.out).isEqualTo(7)
    }

    @Test
    fun initCapture() {
        val inst = Inst()
        inst.initCapture(2, 10)
        Assertions.assertThat<InstOp?>(inst.op).isEqualTo(InstOp.CAPTURE)
        Assertions.assertThat(inst.arg).isEqualTo(2)
        Assertions.assertThat(inst.out).isEqualTo(10)
    }

    @Test
    fun initEmptyWidth() {
        val inst = Inst()
        inst.initEmptyWidth(EmptyOp.BEGIN_LINE or EmptyOp.END_LINE, 4)
        Assertions.assertThat<InstOp?>(inst.op).isEqualTo(InstOp.EMPTY_WIDTH)
        Assertions.assertThat(inst.arg).isEqualTo(EmptyOp.BEGIN_LINE or EmptyOp.END_LINE)
        Assertions.assertThat(inst.out).isEqualTo(4)
    }

    @Test
    fun initMatch() {
        val inst = Inst()
        inst.initMatch(0)
        Assertions.assertThat<InstOp?>(inst.op).isEqualTo(InstOp.MATCH)
        Assertions.assertThat(inst.arg).isEqualTo(0)
    }

    @Test
    fun initNop() {
        val inst = Inst()
        inst.initNop(42)
        Assertions.assertThat<InstOp?>(inst.op).isEqualTo(InstOp.NOP)
        Assertions.assertThat(inst.out).isEqualTo(42)
    }

    @Test
    fun matchesChar_inRange() {
        val inst = Inst()
        inst.initCharRange('a'.code, 'z'.code, false, 0)
        Assertions.assertThat(inst.matchesChar('a'.code)).isTrue()
        Assertions.assertThat(inst.matchesChar('m'.code)).isTrue()
        Assertions.assertThat(inst.matchesChar('z'.code)).isTrue()
        Assertions.assertThat(inst.matchesChar('A'.code)).isFalse()
        Assertions.assertThat(inst.matchesChar('0'.code)).isFalse()
    }

    @Test
    fun matchesChar_foldCase() {
        val inst = Inst()
        inst.initCharRange('A'.code, 'Z'.code, true, 0)
        Assertions.assertThat(inst.matchesChar('A'.code)).isTrue()
        Assertions.assertThat(inst.matchesChar('a'.code)).isTrue() // case-folded match
    }

    @Test
    fun toStringFormats() {
        val alt = Inst()
        alt.initAlt(1, 2)
        Assertions.assertThat(alt.toString()).contains("alt")

        val match = Inst()
        match.initMatch(0)
        Assertions.assertThat(match.toString()).contains("match")

        val fail = Inst()
        Assertions.assertThat(fail.toString()).isEqualTo("fail")
    }
}
