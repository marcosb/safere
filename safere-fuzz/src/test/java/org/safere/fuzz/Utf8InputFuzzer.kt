// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.safere.Pattern
import org.safere.Utf8Input
import java.nio.charset.StandardCharsets
import java.util.List
import java.util.regex.PatternSyntaxException

/** Exercises arbitrary UTF-8 storage, windows, and repeated matcher transitions.  */
class Utf8InputFuzzer {
    @JvmRecord
    private data class RegionCaptureCase(val regex: String?, val input: String?, val start: Int, val end: Int)

    @FuzzTest(maxDuration = "30s")
    fun arbitraryWindow(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    companion object {
        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            RepresentationChecks.assertLiteralSearchMatchesString("XXXXXX", "..XXXXXX")
            RepresentationChecks.assertLiteralSearchMatchesString(
                if (data.consumeBoolean()) "^XXXXXX" else "\\AXXXXXX", "..XXXXXX"
            )
            assertStartAnchoredAccelerationMatchesJdk(data)
            assertStartBoundaryMatchesJdk(data)
            assertBoundarySensitiveRegionCaptures()
            RepresentationChecks.assertKeywordAlternationMatchesString(data)
            RepresentationChecks.assertFixedOffsetAccelerationMatchesString(data)
            assertMultiOffsetLiteralOccurrencesMatchString(data)
            assertFinalLineTerminatorEndAnchorsMatchJdk(data)
            assertPositionDependentStartAccelerationMatchesJdk(data)
            assertLeadingExpansionMatchesJdk(data)
            assertInnerLiteralBooleanSearchMatchesJdk(data)
            RepresentationChecks.assertGraphemeSearchMatchesString(data)
            RepresentationChecks.assertMultibyteMultiAnchorReverseWindowMatchesString(data)
            assertCaseInsensitivePrefixSliceBounds(data)
            val repeatedLiteral =
                data.consumeInt('A'.code, 'Z'.code).toChar().toString().repeat(data.consumeInt(2, 32))
            val suffix = String(data.consumeBytes(data.consumeInt(0, 64)), StandardCharsets.UTF_8)
            val literalInput = ".".repeat(data.consumeInt(0, 64)) + repeatedLiteral + suffix
            RepresentationChecks.assertLiteralSearchMatchesString(repeatedLiteral, literalInput)

            val pattern: Pattern?
            try {
                pattern = Pattern.compile(data.consumeString(128), FuzzSupport.consumeFlags(data))
            } catch (ignored: PatternSyntaxException) {
                return
            }
            val bytes = data.consumeBytes(2048)
            val offset = data.consumeInt(0, bytes.size)
            val length = data.consumeInt(0, bytes.size - offset)

            Utf8RobustnessChecks.walk(pattern.matcher(Utf8Input.trusted(bytes, offset, length)), length)
            val valid = Utf8RobustnessChecks.isValidUtf8(bytes, offset, length)
            try {
                val validated = Utf8Input.validated(bytes, offset, length)
                if (!valid) {
                    throw AssertionError("strict validation accepted malformed UTF-8")
                }
                Utf8RobustnessChecks.walk(pattern.matcher(validated), length)
                pattern.find(validated)
            } catch (e: IllegalArgumentException) {
                if (valid) {
                    throw AssertionError("strict validation rejected valid UTF-8", e)
                }
            }
        }

        private fun assertInnerLiteralBooleanSearchMatchesJdk(data: FuzzedDataProvider) {
            val prefix = data.pickValue<String>(mutableListOf<String?>("zz", "qz", "jq"))
            val suffix = data.pickValue<String>(mutableListOf<String?>("qq", "zq", "qj"))
            val regex = prefix + data.pickValue<String>(mutableListOf<String?>("[^;]*", "[a-z]*", "\\w*")) + suffix
            val length = if (data.consumeBoolean()) data.consumeInt(0, 600) else 100
            val input =
                (data.pickValue<String?>(mutableListOf<String?>("", "😀"))
                        + prefix
                        + "x".repeat(length)
                        + data.pickValue<String?>(List.of<String?>("", suffix, ";" + suffix)))
            val pattern = Pattern.compile(regex)
            val expected = java.util.regex.Pattern.compile(regex).matcher(input).find()
            val content = input.toByteArray(StandardCharsets.UTF_8)
            val padded = ("pad" + input + "outside").toByteArray(StandardCharsets.UTF_8)
            val utf8 = Utf8Input.validated(padded, 3, content.size)
            if (pattern.asPredicate().test(input) != expected || pattern.find(utf8) != expected || pattern.matcher(utf8)
                    .find() != expected
            ) {
                throw AssertionError("inner-literal boolean search differs from JDK: " + regex)
            }
        }

        private fun assertCaseInsensitivePrefixSliceBounds(data: FuzzedDataProvider) {
            // Rare anchors near the start leave a long suffix to verify at the slice end (#881).
            val literal =
                data.pickValue<String?>(mutableListOf<String?>("qz", "jq", "xz")) + "a".repeat(data.consumeInt(8, 40))
            val candidate = if (data.consumeBoolean()) literal.uppercase() else literal
            val input = ".".repeat(data.consumeInt(0, 32)) + candidate
            val length = input.length - data.consumeInt(0, literal.size)
            val padding = ".".repeat(data.consumeInt(0, 7))
            val padded = data.consumeBoolean()
            val bytes =
                (padding + (if (padded) input else input.substring(0, length))).toByteArray(StandardCharsets.UTF_8)
            val pattern = Pattern.compile(literal, Pattern.CASE_INSENSITIVE)
            val expected =
                java.util.regex.Pattern.compile(literal, java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(input.substring(0, length))
            val utf8 = Utf8Input.validated(bytes, padding.length, length)
            val actual = pattern.matcher(utf8)
            val found = expected.find()
            if (actual.find() != found || pattern.find(utf8) != found || (found && (actual.start() != expected.start() || actual.end() != expected.end()))) {
                throw AssertionError("case-insensitive prefix search crossed UTF-8 slice bounds")
            }
        }

        private fun assertLeadingExpansionMatchesJdk(data: FuzzedDataProvider) {
            val leading = data.pickValue<String?>(mutableListOf<String?>("\\s", "[é😀]", "[ab]"))
            val regex =
                (leading
                        + data.pickValue<String>(mutableListOf<String?>("*", "*?", "+", "{0,3}", "{1,3}"))
                        + "# noqa"
                        + data.pickValue<String?>(mutableListOf<String?>("[0-9]", "\\b")))
            val input =
                (data.pickValue<String?>(mutableListOf<String?>(" ", "é😀", "ab")).repeat(data.consumeInt(0, 64))
                        + data.pickValue<String>(mutableListOf<String?>("# noqaX", "# noqa1", "# nopeX"))
                        + data.pickValue<String?>(mutableListOf<String?>("", "    # noqa2", "é😀# noqa3")))
            val pattern = Pattern.compile(regex)
            val utf8 = Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))
            val expected = java.util.regex.Pattern.compile(regex).matcher(input).find()
            if (pattern.find(utf8) != expected || pattern.matcher(utf8).find() != expected) {
                throw AssertionError(
                    "leading expansion search differs from JDK: " + regex + " on " + input
                )
            }
        }

        private fun assertBoundarySensitiveRegionCaptures() {
            for (testCase in List.of<RegionCaptureCase?>(
                RegionCaptureCase("(\\b(?:a|aa))", "xaa", 1, 3),
                RegionCaptureCase("((?:a|aa)\\b)", "aax", 0, 2)
            )) {
                for (operation in mutableListOf<String?>("matches", "lookingAt", "find")) {
                    val stringMatcher =
                        java.util.regex.Pattern.compile(testCase.regex).matcher(testCase.input)
                    stringMatcher.region(testCase.start, testCase.end)
                    val utf8Matcher =
                        Pattern.compile(testCase.regex)
                            .matcher(Utf8Input.validated(testCase.input!!.toByteArray(StandardCharsets.UTF_8)))
                            .region(testCase.start, testCase.end)

                    val stringMatched =
                        when (operation) {
                            "matches" -> stringMatcher.matches()
                            "lookingAt" -> stringMatcher.lookingAt()
                            "find" -> stringMatcher.find()
                            else -> throw AssertionError(operation)
                        }
                    val utf8Matched =
                        when (operation) {
                            "matches" -> utf8Matcher.matches()
                            "lookingAt" -> utf8Matcher.lookingAt()
                            "find" -> utf8Matcher.find()
                            else -> throw AssertionError(operation)
                        }
                    if (utf8Matched != stringMatched
                        || (utf8Matched
                                && (utf8Matcher.start(1) != stringMatcher.start(1)
                                || utf8Matcher.end(1) != stringMatcher.end(1)))
                    ) {
                        throw AssertionError(
                            "UTF-8 region capture bounds differ from String matcher for " + operation
                        )
                    }
                }
            }
        }

        private fun assertStartAnchoredAccelerationMatchesJdk(data: FuzzedDataProvider) {
            val regex =
                data.pickValue<String>(
                    mutableListOf<String?>("\\Ahttps://.*", "\\A\\bhttps://.*", "(\\A)https://.*", "\\A[0-9]+")
                )
            val input =
                data.pickValue<String>(
                    mutableListOf<String?>(
                        "https://example.com",
                        "http://example.com",
                        "prefix https://example.com",
                        "123abc",
                        "abc123"
                    )
                )
            val expected = java.util.regex.Pattern.compile(regex).matcher(input).find()
            val pattern = Pattern.compile(regex)
            val stringFound = pattern.matcher(input).find()
            val utf8Found = pattern.find(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
            if (stringFound != expected || utf8Found != expected) {
                throw AssertionError("start-anchored acceleration differs from JDK anchor semantics")
            }
        }

        private fun assertStartBoundaryMatchesJdk(data: FuzzedDataProvider) {
            val start = data.pickValue<String?>(mutableListOf<String?>("^", "\\A", "(?m)^", ""))
            val body = data.pickValue<String>(mutableListOf<String?>("(a)*", "([a-z]+)", "([aé😀]+)"))
            val terminal = data.pickValue<String>(mutableListOf<String?>("b", "END", "[0-9]"))
            val end = data.pickValue<String?>(mutableListOf<String?>("$", "\\z", "\\Z", ""))
            val regex = start + body + terminal + end
            val repeated = if (body.contains("é")) "é😀a" else "a"
            val text =
                (repeated.repeat(data.consumeInt(0, 256))
                        + data.pickValue<String>(mutableListOf<String?>("", "!", "!\n"))
                        + repeated
                        + (if (terminal == "[0-9]") "5" else terminal)
                        + data.pickValue<String?>(mutableListOf<String?>("", "\r\n", "!")))
            val pattern = Pattern.compile(regex)
            val expected = java.util.regex.Pattern.compile(regex).matcher(text).find()
            val bytes = ("padding" + text + "outside").toByteArray(StandardCharsets.UTF_8)
            val utf8 = Utf8Input.validated(bytes, 7, text.toByteArray(StandardCharsets.UTF_8).size)
            if (pattern.find(utf8) != expected || pattern.matcher(utf8).find() != expected || pattern.asPredicate()
                    .test(text) != expected
            ) {
                throw AssertionError(
                    "boolean search start boundary differs from JDK: " + regex + " on " + text
                )
            }
        }

        private fun assertFinalLineTerminatorEndAnchorsMatchJdk(data: FuzzedDataProvider) {
            val terminator =
                data.pickValue<String>(mutableListOf<String?>("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029"))
            val anchor = if (data.consumeBoolean()) "$" else "\\Z"
            val regex = if (data.consumeBoolean()) ".*\\.json" + anchor else ".*[0-9]" + anchor
            val body = if (regex.contains("json")) "config.json" else "item3"
            val input = body + terminator
            val flags = if (data.consumeBoolean()) 0 else Pattern.UNIX_LINES

            val safeRe = Pattern.compile(regex, flags)
            val expected = java.util.regex.Pattern.compile(regex, flags).matcher(input).find()
            val stringFound = safeRe.matcher(input).find()
            val utf8Found = safeRe.find(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
            if (stringFound != expected || utf8Found != expected) {
                throw AssertionError("end anchor differs from JDK for final line terminator")
            }
        }

        private fun assertPositionDependentStartAccelerationMatchesJdk(data: FuzzedDataProvider) {
            val terminator =
                data.pickValue<String?>(mutableListOf<String?>("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029"))
            val regex = "(b|(?m:^a))c[0-9]"
            val pattern = Pattern.compile(regex)
            for (input in List.of<String?>(
                terminator + "acz9y",
                " ycby ",
                "y0c" + terminator + "1bac19c1__19x y_",
                "bxc\rca9\né\nac9é\r0x\r9a0"
            )) {
                val expected = java.util.regex.Pattern.compile(regex).matcher(input).find()
                val utf8 = Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))
                if (pattern.matcher(input).find() != expected || pattern.find(utf8) != expected) {
                    throw AssertionError("DFA-loop start acceleration differs from JDK anchor semantics")
                }
            }
        }

        private fun assertMultiOffsetLiteralOccurrencesMatchString(data: FuzzedDataProvider) {
            val longWidth = data.consumeInt(6, 16)
            val literal = data.pickValue<String>(mutableListOf<String?>("z", "tag"))
            val regex = "(aq|b[a-z]{" + longWidth + "})" + literal
            val body = "xax" + literal + "x".repeat(longWidth - literal.size - 3)
            // The branches place the literal at two widely separated offsets, so the starts an
            // occurrence implies are not ordered by the occurrence: an earlier occurrence can imply
            // only a later start while a later one begins the leftmost match. A multibyte prefix keeps
            // the byte offsets distinct from the UTF-16 offsets, so the comparison below exercises the
            // accelerator's code-point retreat instead of comparing an index against itself.
            val prefix =
                data.pickValue<String?>(mutableListOf<String?>("", "é", "軖", "😀", "é😀")).repeat(data.consumeInt(0, 3))
            val input = prefix + "b" + body + literal
            val pattern = Pattern.compile(regex)
            val stringMatcher = pattern.matcher(input)
            val bytes = input.toByteArray(StandardCharsets.UTF_8)
            val utf8Input = Utf8Input.validated(bytes)
            val utf8Matcher = pattern.matcher(utf8Input)
            val jdkPattern = java.util.regex.Pattern.compile(regex)

            if (pattern.find(utf8Input) != jdkPattern.matcher(input).find()) {
                throw AssertionError("UTF-8 one-shot multi-offset find differs from JDK")
            }

            // Both accelerators are edited together, so comparing them only against each other cannot
            // see an error that moves the reported start the same way in both. The JDK is the oracle.
            val jdkMatcher = jdkPattern.matcher(input)
            while (true) {
                val expected = jdkMatcher.find()
                if (stringMatcher.find() != expected || utf8Matcher.find() != expected) {
                    throw AssertionError("UTF-8 multi-offset search result differs from JDK")
                }
                if (!expected) {
                    return
                }
                if (stringMatcher.start() != jdkMatcher.start() || stringMatcher.end() != jdkMatcher.end() || utf8Matcher.start() != RepresentationChecks.utf8Offset(
                        input,
                        jdkMatcher.start()
                    ) || utf8Matcher.end() != RepresentationChecks.utf8Offset(input, jdkMatcher.end())
                ) {
                    throw AssertionError("UTF-8 multi-offset search bounds differ from JDK")
                }
            }
        }
    }
}
