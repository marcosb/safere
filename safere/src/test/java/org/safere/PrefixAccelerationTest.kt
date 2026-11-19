// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/** Correctness tests for prefix and character-class start acceleration scanning.  */
internal class PrefixAccelerationTest {
    @Test
    fun testCaseInsensitivePrefixFind() {
        val p = Pattern.compile("(?i)hello[0-9]")

        // 1. Match at the end
        val text = "x".repeat(1100) + "Hello7, world!"
        val m = p.matcher(text)
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(1100)

        // 2. Mixed case match
        val text2 = "x".repeat(1050) + "hElLo7"
        val m2 = p.matcher(text2)
        Assertions.assertThat(m2.find()).isTrue()
        Assertions.assertThat(m2.start()).isEqualTo(1050)

        // 3. No match at all
        val text3 = "x".repeat(1200)
        val m3 = p.matcher(text3)
        Assertions.assertThat(m3.find()).isFalse()

        // 4. Exact 1024 boundary matching
        val text4 = "x".repeat(1024) + "hello7"
        val m4 = p.matcher(text4)
        Assertions.assertThat(m4.find()).isTrue()
        Assertions.assertThat(m4.start()).isEqualTo(1024)

        // 5. Match within body
        val text5 = "x".repeat(1015) + "hello7" + "x".repeat(100)
        val m5 = p.matcher(text5)
        Assertions.assertThat(m5.find()).isTrue()
        Assertions.assertThat(m5.start()).isEqualTo(1015)
    }

    @Test
    fun testNonAlphabeticFirstChar() {
        val p = Pattern.compile("(?i)-hello[0-9]")
        val text = "x".repeat(1100) + "-Hello7"
        val m = p.matcher(text)
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(1100)
    }

    @Test
    fun testCaseSensitivePrefixFind() {
        val p = Pattern.compile("foobar_xyz[0-9]")
        val text = "a".repeat(2000) + "foobar_xyz7" + "b".repeat(500)
        val m = p.matcher(text)
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(2000)
        Assertions.assertThat(m.end()).isEqualTo(2011)

        // UTF-8 input scanner test
        val mUtf8 = p.matcher(Utf8Input.trusted(text.toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(mUtf8.find()).isTrue()
        Assertions.assertThat(mUtf8.start()).isEqualTo(2000)
    }

    @Test
    fun testCharClassPrefixFind() {
        val p = Pattern.compile("[0-9][a-z]+")
        val text = "x".repeat(1500) + "7abc" + "1".repeat(100)
        val m = p.matcher(text)
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(1500)
        Assertions.assertThat(m.end()).isEqualTo(1504)

        val mUtf8 = p.matcher(Utf8Input.trusted(text.toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(mUtf8.find()).isTrue()
        Assertions.assertThat(mUtf8.start()).isEqualTo(1500)
        Assertions.assertThat(mUtf8.end()).isEqualTo(1504)
    }
}
