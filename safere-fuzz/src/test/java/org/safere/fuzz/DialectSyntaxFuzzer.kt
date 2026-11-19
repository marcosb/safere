// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.safere.Pattern
import org.safere.fuzz.FuzzSupport.CompiledPattern

class DialectSyntaxFuzzer {
    @FuzzTest(maxDuration = "30s")
    fun dialectSyntax(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    companion object {
        private val CONTEXT_PREFIXES = arrayOf<String?>("", "^", "(?:", "a|", "[", "[^")
        private val DIALECT_FRAGMENTS = arrayOf<String?>(
            "(?P=name)",
            "(?'name'a)",
            "\\g{name}",
            "\\g1",
            "\\p{Braille}",
            "\\P{Braille}",
            "\\p{Latin}",
            "\\p{^Braille}",
            "\\p{InBraille}",
            "\\p{IsLatin}",
            "\\p{IsWhite_Space}",
            "\\P{IsWhite_Space}",
            "[:lower:]",
            "[:alpha:]",
            "[:digit:]",
            "[:^space:]",
            "[.ch.]",
            "[=a=]",
            "\\C",
            "\\R",
            "\\X",
            "\\b{g}",
            "\\K"
        )
        private val CONTEXT_SUFFIXES = arrayOf<String?>("", "$", ")", "b", "]", "&&[a]]")
        private val INPUTS = mutableListOf<String?>(
            "", "a", "b", "a\u0300", "\r\n", "Braille", "Latin", ":", "[", "]", "l", "o", "w", "e",
            "r", "\u001B", "\u001C", "\u001D", "\u001E", "\u001F", "\u0084", "\u0085", "\u0086",
            "\u00A0", "\u2028", "\u2029", "\u3000"
        )
        private val REGRESSION_REGEXES = arrayOf<String?>(
            "\\p{Braille}",
            "\\p{Latin}",
            "\\p{IsWhite_Space}",
            "\\P{IsWhite_Space}",
            "[\\p{IsWhite_Space}&&[\\p{Cc}\\p{Z}]]",
            "[[:lower:]]",
            "[[:^space:]]",
            "a\\b{g}\\u0300",
            "\\r\\b{g}\\n",
            "\\b{g}a\\u0300\\b{g}",
            "\\Q{?\\E",
            "{?",
            "a{,2}",
            "a{2,1}"
        )
        private val SAFE_RE_EXTENSION_REGEXES = arrayOf<String?>("(?P<name>a)", "(?P<test_group>a)")
        private val HEX_DIGIT_CONTEXTS = arrayOf<String?>(
            "%s", "[%s]", "[^%s]", "[%s&&[\\p{Nd}]]", "(?:%s)+", "(?U)%s"
        )
        private const val HEX_DIGIT_RANGES = "0-9A-Fa-f\\uFF10-\\uFF19\\uFF21-\\uFF26\\uFF41-\\uFF46"
        private val HEX_DIGIT_INPUTS = intArrayOf(
            0x2F, 0x30, 0x39, 0x3A, 0x40, 0x41, 0x46, 0x47, 0x60, 0x61, 0x66, 0x67, 0xFF0F, 0xFF10, 0xFF19,
            0xFF1A, 0xFF20, 0xFF21, 0xFF26, 0xFF27, 0xFF40, 0xFF41, 0xFF46, 0xFF47, 0x0660, 0x0966, 0x1D7CE
        )

        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            for (regex in SAFE_RE_EXTENSION_REGEXES) {
                assertSafeRePythonNamedGroupExtension(regex)
            }
            for (regex in REGRESSION_REGEXES) {
                FuzzSupport.assertFullMatchesJdk(regex, 0, INPUTS)
            }

            val prefix: String = data.pickValue<String>(CONTEXT_PREFIXES)
            var suffix: String = data.pickValue<String>(CONTEXT_SUFFIXES)
            if (prefix.startsWith("[") && !suffix.contains("]")) {
                suffix = suffix + "]"
            }
            if (!prefix.startsWith("[") && suffix.startsWith("]")) {
                suffix = ""
            }
            if (prefix == "(?:" && !suffix.startsWith(")")) {
                suffix = ")" + suffix
            }

            val regex = prefix + data.pickValue<String>(DIALECT_FRAGMENTS) + suffix
            FuzzSupport.assertFullMatchesJdk(regex, FuzzSupport.consumeParserFlags(data), INPUTS)
            assertUnicodeHexDigit(data)
        }

        private fun assertUnicodeHexDigit(data: FuzzedDataProvider) {
            // Issue #985: use explicit Unicode ranges instead of JDK's broader IsHex_Digit property.
            val negated = data.consumeBoolean()
            val context: String = data.pickValue<String>(HEX_DIGIT_CONTEXTS)
            val regex: String = context.formatted(if (negated) "\\P{IsHex_Digit}" else "\\p{IsHex_Digit}")
            val reference: String = context.formatted("[" + (if (negated) "^" else "") + HEX_DIGIT_RANGES + "]")
            val pattern =
                CompiledPattern(
                    regex, 0, Pattern.compile(regex), java.util.regex.Pattern.compile(reference)
                )
            for (cp in HEX_DIGIT_INPUTS) {
                pattern.matcher(Character.toString(cp)).matches()
            }
            val cp = data.consumeInt(0, Character.MAX_CODE_POINT)
            pattern.matcher(Character.toString(cp)).matches()
            val input = FuzzSupport.consumeUnicodeHeavyString(data, 128)
            val matcher = pattern.matcher(input)
            while (matcher.find()) {
                // Compare the full match sequence, including quantified property runs.
            }
        }

        private fun assertSafeRePythonNamedGroupExtension(regex: String?) {
            val pattern = Pattern.compile(regex)
            val matcher = pattern.matcher("a")
            if (!matcher.matches()) {
                throw AssertionError("SafeRE Python-style named group did not match: " + regex)
            }
            if (matcher.group(1) != "a") {
                throw AssertionError("SafeRE Python-style named group captured wrong text: " + regex)
            }
        }
    }
}
