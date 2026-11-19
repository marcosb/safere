// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.stream.Collectors

/** Tests for [Pattern.splitAsStream].  */
internal class PatternSplitAsStreamTest {
    private class LiteralCharSequence(private val value: String) : CharSequence {
        override val length: Int
            get() = value.length

        override fun get(index: Int): Char {
            return value.get(index)
        }

        override fun subSequence(start: Int, end: Int): CharSequence {
            return value.subSequence(start, end)
        }
    }

    private class MutableCharSequence(value: String) : CharSequence {
        private val value: StringBuilder

        init {
            this.value = StringBuilder(value)
        }

        fun set(newValue: String?) {
            value.setLength(0)
            value.append(newValue)
        }

        override val length: Int
            get() = value.length

        override fun get(index: Int): Char {
            return value.get(index)
        }

        override fun subSequence(start: Int, end: Int): CharSequence {
            return value.subSequence(start, end)
        }

        override fun toString(): String {
            return value.toString()
        }
    }

    @Test
    @DisplayName("splitAsStream splits input around matches")
    fun splitAsStreamBasic() {
        val p = Pattern.compile(",")
        val parts = p.splitAsStream("a,b,c").collect(Collectors.toList())
        Assertions.assertThat<String?>(parts).containsExactly("a", "b", "c")
    }

    @Test
    @DisplayName("splitAsStream with no match returns entire input")
    fun splitAsStreamNoMatch() {
        val p = Pattern.compile(",")
        val parts = p.splitAsStream("abc").collect(Collectors.toList())
        Assertions.assertThat<String?>(parts).containsExactly("abc")
    }

    @Test
    @DisplayName("splitAsStream with regex pattern")
    fun splitAsStreamRegex() {
        val p = Pattern.compile("\\s+")
        val parts = p.splitAsStream("hello  world\tfoo").collect(Collectors.toList())
        Assertions.assertThat<String?>(parts).containsExactly("hello", "world", "foo")
    }

    @Test
    @DisplayName("splitAsStream count() works without collecting")
    fun splitAsStreamCount() {
        val p = Pattern.compile(",")
        val count = p.splitAsStream("a,b,c,d").count()
        Assertions.assertThat(count).isEqualTo(4)
    }

    @Test
    @DisplayName("splitAsStream discards trailing empty strings")
    fun splitAsStreamTrailingEmpty() {
        val p = Pattern.compile(",")
        val parts = p.splitAsStream("a,b,").collect(Collectors.toList())
        Assertions.assertThat<String?>(parts).containsExactly("a", "b")
    }

    @Test
    @DisplayName("splitAsStream reads custom CharSequence content via charAt()")
    fun splitAsStreamCustomCharSequence() {
        val p = Pattern.compile(",")
        val parts =
            p.splitAsStream(LiteralCharSequence("a,b,c")).collect(Collectors.toList())
        Assertions.assertThat<String?>(parts).containsExactly("a", "b", "c")
    }

    @Test
    @DisplayName("splitAsStream reads input lazily when stream is consumed")
    fun splitAsStreamReadsInputLazily() {
        val p = Pattern.compile(",")
        val input = MutableCharSequence("a,b")
        val stream = p.splitAsStream(input)

        input.set("x,y,z")

        Assertions.assertThat<String?>(stream.collect(Collectors.toList())).containsExactly("x", "y", "z")
    }
}
