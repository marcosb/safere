// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.safere.Pattern
import java.util.List

class SplitFuzzer {
    @JvmRecord
    private data class CodePointRange(val first: Int, val last: Int)

    @FuzzTest(maxDuration = "30s")
    fun unassignedControlSplits(data: FuzzedDataProvider) {
        // Exercise GB4/GB5 around unassigned default-ignorable controls, including supplementary ones.
        // Ordinary unassigned Other code points intentionally differ from the JDK; see #925.
        val range: CodePointRange = data.pickValue<CodePointRange>(UNASSIGNED_GRAPHEME_CONTROLS)
        val control = String(Character.toChars(data.consumeInt(range.first, range.last)))
        val prefix = data.pickValue<String?>(mutableListOf<String?>("", "a", "\u0600"))
        val suffix = data.pickValue<String>(mutableListOf<String?>("\u07EF", "\u0301", "\u200D"))
        val input =
            (prefix + control + suffix.repeat(data.consumeInt(1, 4))).repeat(data.consumeInt(1, 4))
        val regex = data.pickValue<String?>(mutableListOf<String?>("\\X", "(\\X)", "\\b{g}"))
        val pattern = FuzzSupport.compileOrSkip(regex, 0)
        if (pattern == null) {
            return
        }
        val limit = data.consumeInt(-1, 8)
        pattern.split(input, limit)
        pattern.splitWithDelimiters(input, limit)
    }

    // Exercise cache reuse where a delimiter is also a member of the preceding repeated class.
    @FuzzTest(maxDuration = "30s")
    fun repeatedClassSplits(data: FuzzedDataProvider) {
        val alphabet = data.pickValue<String>(mutableListOf<String?>("01", "ab", "\u0000x", "😀😁"))
        val firstWidth = Character.charCount(alphabet.codePointAt(0))
        val delimiter = alphabet.substring(0, firstWidth)
        val continuation = alphabet.substring(firstWidth)
        val quantifier = data.pickValue<String?>(mutableListOf<String?>("*", "+", "*?", "{0,3}"))
        var regex = "[" + alphabet + "]" + quantifier + delimiter
        if (data.consumeBoolean()) {
            regex = "(" + regex + ")"
        }
        val input =
            (delimiter
                    + "~".repeat(data.consumeInt(1, 8))
                    + delimiter
                    + continuation.repeat(data.consumeInt(1, 32))
                    + "~".repeat(data.consumeInt(16, 600))
                    + delimiter)
        val pattern = FuzzSupport.compileOrSkip(regex, 0)
        if (pattern == null) {
            return
        }
        val limit = data.consumeInt(-1, 8)
        for (reuse in 0..1) {
            pattern.split(input)
            pattern.split(input, limit)
            pattern.splitWithDelimiters(input, limit)
        }
    }

    @FuzzTest(maxDuration = "30s")
    fun literalPrefixSplits(data: FuzzedDataProvider) {
        // Refs #1050. Generate short matching prefixes as well as complete and longer inputs.
        val literal = data.pickValue<String?>(mutableListOf<String?>("a", "Ab", "a😀b")).repeat(data.consumeInt(1, 16))
        val anchor = data.pickValue<String?>(mutableListOf<String?>("^", "\\A", ""))
        val flags =
            data.pickValue<Int?>(
                List.of<Int?>(Pattern.CASE_INSENSITIVE, 0, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            )
        val prefixLength = data.consumeInt(0, literal.codePointCount(0, literal.length))
        var input: String? = literal.substring(0, literal.offsetByCodePoints(0, prefixLength))
        if (data.consumeBoolean()) {
            input = input!!.uppercase()
        }
        input += data.pickValue<String?>(List.of<String?>("", "~", literal))
        val pattern = FuzzSupport.compileOrSkip(anchor + literal, flags)
        if (pattern == null) {
            return
        }
        val limit = data.consumeInt(-2, 8)
        pattern.split(input)
        pattern.split(input, limit)
        pattern.splitWithDelimiters(input)
        pattern.splitWithDelimiters(input, limit)
    }

    @FuzzTest(maxDuration = "30s")
    fun split(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    @Test
    fun graphemeBoundaryDelimitersCannotSplitCrLf() {
        // GB3 forbids a boundary inside CRLF. The JDK's consumed-prefix exception is
        // intentionally not the oracle here; broad differential comparisons remain unchanged.
        for (delimiter in GRAPHEME_CRLF_DELIMITERS) {
            val pattern = Pattern.compile(delimiter)
            for (input in ADJACENT_CRLF_INPUTS) {
                Assertions.assertThat<String?>(pattern.split(input)).`as`("/%s/ on %s", delimiter, input)
                    .containsExactly(input)
                Assertions.assertThat<String?>(pattern.splitWithDelimiters(input)).containsExactly(input)
                for (limit in SPLIT_LIMITS) {
                    Assertions.assertThat<String?>(pattern.split(input, limit!!)).containsExactly(input)
                    Assertions.assertThat<String?>(pattern.splitWithDelimiters(input, limit)).containsExactly(input)
                }
                for (limit in LARGE_POSITIVE_LIMITS) {
                    Assertions.assertThat<String?>(pattern.split(input, limit!!)).containsExactly(input)
                    Assertions.assertThat<String?>(pattern.splitWithDelimiters(input, limit)).containsExactly(input)
                }
            }
        }
    }

    companion object {
        private val GRAPHEME_CRLF_DELIMITERS =
            mutableListOf<String?>("\\r\\b{g}\\n", "(?:\\r)\\b{g}\\n", "\\r(?:\\b{g})\\n")
        private val ADJACENT_CRLF_INPUTS = mutableListOf<String?>("#\r\n\r\n$", "\r\n\r\n", "a\r\n\r\nb")
        private val SPLIT_LIMITS: MutableList<Int?> = List.of<Int?>(-1, 0, 2)
        private val LARGE_POSITIVE_LIMITS: MutableList<Int?> =
            List.of<Int?>(Int.Companion.MAX_VALUE, Int.Companion.MAX_VALUE / 2 + 1)

        private val UNASSIGNED_GRAPHEME_CONTROLS: MutableList<CodePointRange?> = List.of<CodePointRange?>(
            CodePointRange(0x2065, 0x2065),
            CodePointRange(0xFFF0, 0xFFF8),
            CodePointRange(0xE0000, 0xE0000),
            CodePointRange(0xE0002, 0xE001F),
            CodePointRange(0xE0080, 0xE00FF),
            CodePointRange(0xE01F0, 0xE0FFF)
        )

        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            val regex = data.consumeString(256)
            val flags = FuzzSupport.consumeFlags(data)
            val input = data.consumeString(2048)
            val limit = data.consumeInt(-16, 16)
            val pattern = FuzzSupport.compileOrSkip(regex, flags)
            if (pattern == null) {
                return
            }

            pattern.split(input)
            pattern.split(input, limit)
            pattern.splitWithDelimiters(input)
            pattern.splitWithDelimiters(input, limit)
            for (largeLimit in LARGE_POSITIVE_LIMITS) {
                pattern.split(input, largeLimit!!)
                pattern.splitWithDelimiters(input, largeLimit)
            }
        }
    }
}
