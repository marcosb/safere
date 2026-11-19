// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test

/** Tests for [SweepJson].  */
internal class SweepJsonTest {
    @Test
    fun writesAndReadsJsonFieldsWithEscapedCharacters() {
        val value = "quote\" slash\\ newline\n tab\t surrogate\uD83D"
        val `object` = SweepJson.`object`()
        `object`.addProperty("field", value)
        `object`.addProperty("other", "x")

        val line = SweepJson.toJson(`object`)

        Assertions.assertThat(SweepJson.field(line, "field")).isEqualTo(value)
        Assertions.assertThat(SweepJson.field(line, "missing")).isNull()
    }

    @Test
    fun toJsonEscapesUnpairedSurrogates() {
        val `object` = SweepJson.`object`()
        `object`.addProperty("high", "\uD83D")
        `object`.addProperty("low", "\uDE00")
        `object`.addProperty("pair", "\uD83D\uDE00")

        val line = SweepJson.toJson(`object`)

        Assertions.assertThat(line).contains("\"high\":\"\\ud83d\"", "\"low\":\"\\ude00\"")
        Assertions.assertThat(line).contains("😀")
    }

    @Test
    fun fieldReturnsNullForLegacyRawReplayLine() {
        Assertions.assertThat(SweepJson.field("\\Qabc\\E", "regex")).isNull()
    }

    @Test
    fun readsStructuredJsonFields() {
        val `object` = SweepJson.`object`()
        val nested = SweepJson.`object`()
        nested.addProperty("text", "abc")
        nested.addProperty("enabled", true)
        nested.addProperty("count", 7)
        `object`.add("nested", nested)

        val parsed = SweepJson.parseObject(SweepJson.toJson(`object`))
        val parsedNested = SweepJson.`object`(parsed, "nested")

        Assertions.assertThat(SweepJson.string(parsedNested, "text")).isEqualTo("abc")
        Assertions.assertThat(SweepJson.bool(parsedNested, "enabled")).isTrue()
        Assertions.assertThat(SweepJson.integer(parsedNested, "count")).isEqualTo(7)
    }

    @Test
    fun parseObjectRejectsLegacyRawReplayLine() {
        Assertions.assertThatThrownBy(ThrowingCallable { SweepJson.parseObject("\\Qabc\\E") })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("expected JSON object")
    }
}
