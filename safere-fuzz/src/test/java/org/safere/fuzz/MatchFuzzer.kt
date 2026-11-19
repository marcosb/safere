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

class MatchFuzzer {
    @FuzzTest(maxDuration = "30s")
    fun match(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    @JvmRecord
    private data class RegressionCase(val regex: String?, val flags: Int, val inputs: MutableList<String>?)
    companion object {
        private val CI = Pattern.CASE_INSENSITIVE
        private val CI_U = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        private val CI_UCC = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CHARACTER_CLASS

        private val CASE_FOLDING_REGRESSIONS: MutableList<RegressionCase> = List.of<RegressionCase?>(
            RegressionCase("\\p{Lt}{4}", CI, List.of<String?>("abcd", "AAAA", "\u01C4".repeat(4))),
            RegressionCase("\\p{javaUpperCase}", CI, mutableListOf<String?>("\u00AA", "\u02B0", "A", "a", "1")),
            RegressionCase("[h-j]+", CI_U, mutableListOf<String?>("\u0130\u0131", "HIJ", "abc")),
            RegressionCase("[^h-j]", CI_U, mutableListOf<String?>("\u0130", "\u0131", "x")),
            RegressionCase("[\\x{17F}]", CI_U, mutableListOf<String?>("\u017F", "S", "s")),
            RegressionCase("[K]", CI, mutableListOf<String?>("\u212A", "K", "k")),
            RegressionCase("\\p{L}", CI_U, mutableListOf<String?>("\u0345", "\u0399", "A", "a")),
            RegressionCase("\\P{L}", CI_U, mutableListOf<String?>("\u0345", "\u0399", "A", "a")),
            RegressionCase("[A-Z]", CI_U, mutableListOf<String?>("\u017F", "K", "k")),
            RegressionCase("[K-K]", CI_U, mutableListOf<String?>("K", "k")),
            RegressionCase("\\p{Lower}", CI_U, mutableListOf<String?>("\u212A", "A", "a")),
            RegressionCase("\\p{Lower}", CI_UCC, mutableListOf<String?>("\u0345", "\u212A", "A", "a")),
            RegressionCase("\\x{49}", CI_U, mutableListOf<String?>("\u0130", "\u0131", "I", "i")),
            RegressionCase("\\x{69}", CI_U, mutableListOf<String?>("\u0130", "\u0131", "I", "i"))
        )

        private val CASE_FOLDING_MODEL_REGRESSIONS: MutableList<RegressionCase> = List.of<RegressionCase?>(
            RegressionCase("[A-Z]", CI_U, mutableListOf<String?>("\u0130", "\u212A")),
            RegressionCase("[I-I]", CI_U, mutableListOf<String?>("\u0130")),
            RegressionCase("[K-K]", CI_U, mutableListOf<String?>("\u212A")),
            RegressionCase("[İ-İ]", CI_U, mutableListOf<String?>("I", "i", "ı")),
            RegressionCase("[ı-ı]", CI_U, mutableListOf<String?>("I", "i", "İ")),
            RegressionCase("İ", CI_U, mutableListOf<String?>("I", "i", "ı")),
            RegressionCase("ı", CI_U, mutableListOf<String?>("I", "i", "İ")),
            RegressionCase("\uFB05", CI_U, mutableListOf<String?>("\uFB06"))
        )

        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            for (regression in CASE_FOLDING_REGRESSIONS) {
                FuzzSupport.assertFullMatchesJdk(regression.regex, regression.flags, regression.inputs)
            }
            for (regression in CASE_FOLDING_MODEL_REGRESSIONS) {
                assertFullMatchesSafeRe(regression.regex, regression.flags, regression.inputs!!)
            }
            assertAcceleratedRestartArrayGrowthMatchesJdk(data)
            CaseFamilyChecks.assertCaseFamilyClosureSafeRe(data)
            assertUnicodeBoundaryStartCacheMatchesJdk()
            assertTrailingLineTerminatorEndAnchorFindsMatchJdk()
            assertUnicodeLineStartAnchorsMatchJdk()
            assertMixedCarriageReturnLineStartsMatchJdk(data)
            assertZeroWidthAlternationFindsLeftmostStartJdk()
            assertZeroWidthPossessiveCaptureRetentionJdk()
            assertDfaSandwichLeftmostStartCasesMatchJdk()
            assertMixedAsciiAndExactUnicodeCaseFoldingMatchesJdk(data)
            assertUnicodeCaseFoldedUtf8LiteralMatchesJdk(data)
            assertScopedCaseFoldingMatchesJdk(data)
            assertMultiAnchorGapBoundsMatchJdk(data)
            assertLeadingClassAssertionsMatchJdk(data)
            assertFactoredPrefixCaseFlagsMatchJdk(data)
            assertAlternationAndQuantifierPriorityLookingAtMatchesJdk(data)

            val input: String
            if (data.consumeBoolean()) {
                val atom: String = distinctLiteralRun(data.consumeInt(16, 192))
                val repetitions = data.consumeInt(1, 16)
                regex = "(?:" + atom + "){" + repetitions + "}"
                flags = 0
                input = if (data.consumeBoolean()) atom.repeat(repetitions) else data.consumeString(512)
            } else {
                regex = data.consumeString(256)
                flags = FuzzSupport.consumeFlags(data)
                input = data.consumeString(2048)
            }
            val pattern = FuzzSupport.compileOrSkip(regex, flags)
            if (pattern != null) {
                val matcher = pattern.matcher(input)
                matcher.matches()
                matcher.reset()
                matcher.lookingAt()
                matcher.reset()
                matcher.find()
                matcher.reset()
                matcher.find(FuzzSupport.consumeIndex(data, input))
            }
            // Append new consumers so existing corpus inputs retain their original interpretation.
            assertNullableFindInsideSurrogatePairMatchesJdk(data)
            assertUnicodeIPrefixFindsMatchJdk(data)
            assertNestedNullableLoopEntriesMatchJdk(data)
            assertImpossibleCaptureAlternativesMatchJdk(data)
        }

        private fun assertImpossibleCaptureAlternativesMatchJdk(data: FuzzedDataProvider) {
            val regression =
                FuzzSupport.compileCompatibleOrSkip("(?:(a)|(b)|(c)|(d)|(e))[^\\s\\S]|x|xx", 0)
            if (regression != null) {
                val matcher = regression.matcher("xxy")
                while (matcher.find()) {
                }
            }

            // Discarded concatenations leave unreachable capture patch lists in the compiled program.
            val branches = data.consumeInt(2, 12)
            val alternatives = StringBuilder()
            for (i in 0..<branches) {
                if (i > 0) {
                    alternatives.append('|')
                }
                val atom = data.pickValue<String?>(mutableListOf<String?>("a", "b", "é", "😀"))
                alternatives.append('(').append(if (data.consumeBoolean()) "(" + atom + ")" else atom).append(')')
            }
            val impossible = data.pickValue<String?>(mutableListOf<String?>("[^\\s\\S]", "[a&&b]"))
            val deadBranch = "(?:" + alternatives + ")" + impossible
            val atom = data.pickValue<String>(mutableListOf<String?>("x", "é", "😀"))
            val liveBranches = atom + "|" + atom + atom
            for (regex in List.of<String?>(
                deadBranch,
                deadBranch + "|" + liveBranches,
                liveBranches + "|" + deadBranch
            )) {
                val pattern = FuzzSupport.compileCompatibleOrSkip(regex, 0)
                if (pattern != null) {
                    val matcher = pattern.matcher(atom.repeat(2) + "!")
                    while (matcher.find()) {
                    }
                    matcher.reset().lookingAt()
                    matcher.reset().matches()
                }
            }
        }

        private fun assertNestedNullableLoopEntriesMatchJdk(data: FuzzedDataProvider) {
            for (regex in mutableListOf<String?>("(?:(?:x|)+?)*", "(?:(?:x?)+?){1,}")) {
                val regression = FuzzSupport.compileCompatibleOrSkip(regex, 0)
                if (regression != null) {
                    val matcher = regression.matcher("xxx")
                    while (matcher.find()) {
                    }
                    matcher.reset().replaceAll("-")
                }
            }
            // Vary fresh entry into a nullable loop independently of its own back edge (#1005).
            val atom = data.pickValue<String>(mutableListOf<String?>("x", "xx", "é", "😀"))
            val body = if (data.consumeBoolean()) "(?:" + atom + "|)" else "(?:" + atom + "?)"
            val inner = data.pickValue<String?>(mutableListOf<String?>("+?", "*?", "+", "*"))
            val prefix = if (data.consumeBoolean()) "a?" else ""
            val suffix = if (data.consumeBoolean()) "b?" else ""
            val outer = data.pickValue<String?>(mutableListOf<String?>("*", "+", "{1,}", "{2}", "*?", "+?"))
            val continuation = data.pickValue<String>(mutableListOf<String?>("", "y", "$"))
            var regex = "(?:" + prefix + body + inner + suffix + ")" + outer + continuation
            if (data.consumeBoolean()) {
                regex = "(" + regex + ")"
            }
            var input = atom.repeat(data.consumeInt(0, 16))
            if (!suffix.isEmpty() && data.consumeBoolean()) {
                input += "b" + atom.repeat(data.consumeInt(0, 16))
            }
            if (continuation == "y") {
                input += "y"
            }
            val pattern = FuzzSupport.compileCompatibleOrSkip(regex, 0)
            if (pattern != null) {
                val matcher = pattern.matcher(input)
                while (matcher.find()) {
                }
                matcher.reset().lookingAt()
                matcher.reset().matches()
                matcher.reset().replaceAll("-")
            }
        }

        private fun assertAcceleratedRestartArrayGrowthMatchesJdk(data: FuzzedDataProvider) {
            // Vary both dimensions of the flat DFA arrays and restart after a context change (#882).
            val prefixLength = data.consumeInt(4, 32)
            val alphabetSize = data.consumeInt(32, 160)
            val tail = StringBuilder()
            for (i in 0..<alphabetSize) {
                tail.appendCodePoint(0x400 + i)
            }
            val prefix = "\u0000".repeat(prefixLength)
            val regex = "xbw|" + prefix + tail + "$"
            val separator = data.pickValue<String?>(mutableListOf<String?>("\n", "\r", "\r\n", "!", "a", "😀"))
            val suffix = data.pickValue<String?>(List.of<String?>("", "xbw", "XBW", prefix + tail))
            val input = "!" + prefix + "!" + separator + "\u0000" + "!".repeat(40) + suffix
            val pattern = FuzzSupport.compileCompatibleOrSkip(regex, CI_U)
            if (pattern != null) {
                for (pass in 0..1) {
                    pattern.matcher(input).find()
                }
            }
        }

        private fun assertNullableFindInsideSurrogatePairMatchesJdk(data: FuzzedDataProvider) {
            val regex = data.pickValue<String?>(mutableListOf<String?>(".|", "a|", ".?", "(?:.|)A?"))
            val codePoint = data.pickValue<Int?>(mutableListOf<Int?>(0x1F600, 0x2F802, 0x8D43F))
            val prefix = if (data.consumeBoolean()) "x" else ""
            val suffix = data.pickValue<String?>(mutableListOf<String?>("", "A", "AB"))
            val input = prefix + String(Character.toChars(codePoint)) + suffix
            val pattern = FuzzSupport.compileCompatibleOrSkip(regex, 0)
            if (pattern != null) {
                pattern.matcher(input).find(prefix.length + 1)
            }
        }

        private fun assertUnicodeIPrefixFindsMatchJdk(data: FuzzedDataProvider) {
            // Append this axis so existing corpus inputs keep their original consumer order. I/i sources
            // match every member below on the JDK and under SafeRE's approved Unicode closure (#866).
            val prefix = data.pickValue<String>(mutableListOf<String?>("A", "é", "Ж", ""))
            val source = data.pickValue<String>(mutableListOf<String?>("I", "i"))
            val target = data.pickValue<String?>(mutableListOf<String?>("ı", "İ", "I", "i"))
            val suffix = data.pickValue<String?>(mutableListOf<String?>("", "B", "終"))
            val literal = prefix + source + suffix
            val regex = if (data.consumeBoolean()) "(" + literal + ")" else literal
            val input = "!" + prefix + target + suffix + "!"
            val flags: Int = if (data.consumeBoolean()) CI_UCC else CI_U
            if (!java.util.regex.Pattern.compile(regex, flags).matcher(input).find()) {
                throw AssertionError("Unexpected JDK Unicode I prefix result for /" + regex + "/")
            }
            val pattern = Pattern.compile(regex, flags)
            val stringMatcher = pattern.matcher(input)
            val bytes = input.toByteArray(StandardCharsets.UTF_8)
            val utf8Matcher = pattern.matcher(Utf8Input.validated(bytes))
            if (!stringMatcher.find() || stringMatcher.start() != 1 || stringMatcher.end() != input.length - 1 || !utf8Matcher.find() || utf8Matcher.start() != 1 || utf8Matcher.end() != bytes.size - 1) {
                throw AssertionError("Unicode I prefix find divergence for /" + regex + "/ on " + input)
            }
        }

        private fun distinctLiteralRun(count: Int): String {
            val pattern = StringBuilder(count)
            for (i in 0..<count) {
                pattern.appendCodePoint(0x1000 + i * 2)
            }
            return pattern.toString()
        }

        private fun assertUnicodeBoundaryStartCacheMatchesJdk() {
            val regex = "\\b."
            val flags = Pattern.UNICODE_CHARACTER_CLASS
            val pattern = FuzzSupport.compileCompatibleOrSkip(regex, flags)
            if (pattern == null) {
                return
            }

            val boundary = "!\u00E9" + "x".repeat(300)
            val nonBoundary = "!!" + "x".repeat(300)
            val matcher = pattern.matcher(boundary)
            matcher.region(1, boundary.length).lookingAt()
            matcher.reset(nonBoundary).region(1, nonBoundary.length).lookingAt()
        }

        private fun assertLeadingClassAssertionsMatchJdk(data: FuzzedDataProvider) {
            val leadingClass = data.pickValue<String?>(mutableListOf<String?>("[a-z]", "[ ]", "[0-9]", "[^A-Z]"))
            val assertion = if (data.consumeBoolean()) "\\b" else "\\B"
            val suffix = if (data.consumeBoolean()) "AAA" else "AAA[0-9]RAREST_TOKEN"
            val regex = leadingClass + assertion + suffix
            val pattern = FuzzSupport.compileCompatibleOrSkip(regex, 0)
            if (pattern != null) {
                pattern.matcher(
                    data.pickValue<String?>(
                        mutableListOf<String?>(
                            "xAAA",
                            " AAA",
                            "11AAA",
                            "xAAA1RAREST_TOKEN"
                        )
                    )
                ).find()
            }
        }

        private fun assertFactoredPrefixCaseFlagsMatchJdk(data: FuzzedDataProvider) {
            val exactSuffix = data.pickValue<String>(mutableListOf<String?>("X", "Y", "Z"))
            val foldedSuffix = data.pickValue<String>(mutableListOf<String?>("M", "N", "P"))
            val foldedBranch = "(?i:abc)" + foldedSuffix
            val exactBranch = "abc" + exactSuffix
            val regex =
                if (data.consumeBoolean())
                    "(?:" + foldedBranch + "|" + exactBranch + ")"
                else
                    "(?:" + exactBranch + "|" + foldedBranch + ")"
            val pattern = FuzzSupport.compileCompatibleOrSkip(regex, 0)
            if (pattern != null) {
                pattern.matcher(if (data.consumeBoolean()) "ABC" + foldedSuffix else "abc" + exactSuffix).find()
            }
        }

        private fun assertTrailingLineTerminatorEndAnchorFindsMatchJdk() {
            val regexes = mutableListOf<String?>("^(?:\\n|\\n*)$", "^(?:a?|\\n)$", "(?:(?:(?:a?)|\\n))$")
            val inputs: MutableList<String> = mutableListOf<String?>("\n", "\n\n", "a\n", "aa\n", "\u2028\u2028")
            for (regex in regexes) {
                val pattern = FuzzSupport.compileCompatibleOrSkip(regex, 0)
                if (pattern == null) {
                    continue
                }
                for (input in inputs) {
                    pattern.matcher(input).find()
                }
            }
        }

        private fun assertUnicodeLineStartAnchorsMatchJdk() {
            val terminators = mutableListOf<String?>("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029")
            val regexes = mutableListOf<String?>("(?m)^\\s+at\\s+(\\w+)$", "(?m).+^")
            for (regex in regexes) {
                val pattern = FuzzSupport.compileCompatibleOrSkip(regex, 0)
                if (pattern == null) {
                    continue
                }
                for (terminator in terminators) {
                    val matcher =
                        pattern.matcher("header" + terminator + "\tat alpha" + terminator + "\tat beta")
                    while (matcher.find()) {
                    }
                }
            }
        }

        private fun assertMixedCarriageReturnLineStartsMatchJdk(data: FuzzedDataProvider) {
            val regressions: MutableList<RegressionCase> =
                List.of<RegressionCase?>(
                    RegressionCase("(?m)^.X", 0, List.of<String?>("a".repeat(500) + "\r\nqq\rqX")),
                    RegressionCase("(?m)^\\nX", 0, List.of<String?>("a".repeat(500) + "\rqY\r\nX"))
                )
            for (regression in regressions) {
                val pattern =
                    FuzzSupport.compileCompatibleOrSkip(regression.regex, regression.flags)
                if (pattern != null) {
                    for (input in regression.inputs!!) {
                        pattern.matcher(input).find()
                    }
                }
            }

            val firstTerminator = if (data.consumeBoolean()) "\r\n" else "\r"
            val secondTerminator = if (firstTerminator.size == 2) "\r" else "\r\n"
            val regex = if (data.consumeBoolean()) "(?m)^.X" else "(?m)^\\nX"
            val input =
                ("a".repeat(data.consumeInt(257, 1024))
                        + firstTerminator
                        + data.consumeString(8)
                        + secondTerminator
                        + data.consumeString(8))
            val generated = FuzzSupport.compileCompatibleOrSkip(regex, 0)
            if (generated != null) {
                generated.matcher(input).find()
            }
        }

        private fun assertZeroWidthAlternationFindsLeftmostStartJdk() {
            val regexes = mutableListOf<String?>("(?:\\B{1}|a).", "(?:a|\\B?).", "(?:a|(\\B)?).", "(?:(?:)\\B|a).")
            for (regex in regexes) {
                val pattern = FuzzSupport.compileCompatibleOrSkip(regex, 0)
                if (pattern != null) {
                    pattern.matcher("ab").find()
                }
            }
        }

        private fun assertZeroWidthPossessiveCaptureRetentionJdk() {
            val pattern =
                FuzzSupport.compileCompatibleOrSkip("(?m:^(\\B)*+$)", Pattern.MULTILINE)
            if (pattern == null) {
                return
            }
            pattern.matcher("\n").find()
            pattern.matcher("\na").find()
        }

        private fun assertDfaSandwichLeftmostStartCasesMatchJdk() {
            val regexes = mutableListOf<String?>(
                "\\B([^a])*[^a][^a]",
                "\\B(?:[^a])*[^a][^a]",
                "\\B[^a]*[^a][^a]",
                "\\B(?:b|bb)*bb",
                "(?:(?:\\ba?)|\\B|[^a])a?",
                "(?:(?:\\ba?)|\\B|b)a?"
            )
            for (regex in regexes) {
                val pattern = FuzzSupport.compileCompatibleOrSkip(regex, 0)
                if (pattern != null) {
                    pattern.matcher("bbbb").find()
                    pattern.matcher("ba").find()
                }
            }
        }

        private fun assertMixedAsciiAndExactUnicodeCaseFoldingMatchesJdk(
            data: FuzzedDataProvider
        ) {
            val exactCharacters = mutableListOf<String?>("\u00DF", "\uFB05")
            val unicodeFoldedCharacters = mutableListOf<String?>("\u1E9E", "\uFB06")
            val variant = data.consumeInt(0, exactCharacters.size - 1)
            val ascii = ('a'.code + data.consumeInt(0, 25)).toChar().toString()
            val separator = data.pickValue<String>(mutableListOf<String?>("", "-", "_", "0"))
            val regex = "(?i:" + ascii + separator + exactCharacters.get(variant) + ")"
            val input =
                ascii.get(0).uppercaseChar().toString() + separator + unicodeFoldedCharacters.get(variant)

            val pattern = FuzzSupport.compileCompatibleOrSkip(regex, 0)
            if (pattern != null) {
                pattern.matcher(input).find()
            }
        }

        private fun assertUnicodeCaseFoldedUtf8LiteralMatchesJdk(data: FuzzedDataProvider) {
            val family = data.pickValue<String>(mutableListOf<String?>("Iiİı", "KkK", "Ssſ", "Σσς", "Шш"))
            val members = family.codePoints().toArray()
            val source = members[data.consumeInt(0, members.size - 1)]
            val target = members[data.consumeInt(0, members.size - 1)]
            val prefix = data.pickValue<String>(mutableListOf<String?>("é", "Ж", "α", "A"))
            val suffix = data.pickValue<String?>(mutableListOf<String?>("", "x", "終"))
            val regex = prefix + String(Character.toChars(source)) + suffix
            val input = "!" + prefix + String(Character.toChars(target)) + suffix + "!"

            val expected = java.util.regex.Pattern.compile(regex, CI_U).matcher(input).find()
            val actual =
                Pattern.compile(regex, CI_U).matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
                    .find()
            if (actual != expected) {
                throw AssertionError(
                    "Unicode-folded UTF-8 find divergence for /" + regex + "/ on " + input
                )
            }
        }

        private fun assertScopedCaseFoldingMatchesJdk(data: FuzzedDataProvider) {
            val exact: String = distinctAsciiLiteral(data.consumeInt(3, 12))
            val folded: String = distinctAsciiLiteral(data.consumeInt(3, 12))
            val regex =
                if (data.consumeBoolean())
                    "(?-i:" + exact + ")[0-9]" + folded
                else
                    folded + "[0-9](?-i:" + exact + ")"
            val input =
                if (regex.startsWith("(?-i"))
                    exact.lowercase() + "1" + folded.lowercase()
                else
                    folded.lowercase() + "1" + exact.lowercase()

            val pattern = FuzzSupport.compileCompatibleOrSkip(regex, CI)
            if (pattern != null) {
                pattern.matcher(input).find()
            }
        }

        private fun distinctAsciiLiteral(count: Int): String {
            val literal = StringBuilder(count)
            for (i in 0..<count) {
                literal.append(('A'.code + i % 26).toChar())
            }
            return literal.toString()
        }

        private fun assertMultiAnchorGapBoundsMatchJdk(data: FuzzedDataProvider) {
            val repeatedDigits = data.consumeInt(3, 12)
            val driver: String = distinctAsciiLiteral(data.consumeInt(8, 16))
            val regex: String?
            val input: String?
            when (data.consumeInt(0, 11)) {
                0 -> {
                    regex = "111[0-9]+" + driver
                    input = "1".repeat(repeatedDigits) + "2" + driver
                }

                1 -> {
                    val maximum = data.consumeInt(1, 4)
                    regex = "(?s)TARGET.{1," + maximum + "}"
                    input = "TARGET" + "😀".repeat(maximum + 1)
                }

                2 -> {
                    val maximum = data.consumeInt(1, 4)
                    val interior = data.consumeBoolean()
                    regex = if (interior) "TARGET[^;]?" + driver else "TARGET[^;]{0," + maximum + "}"
                    input = "xxTARGET" + "😀".repeat(if (interior) 1 else maximum) + (if (interior) driver else "zz")
                }

                3 -> {
                    regex = "TARGET[^;]*" + driver
                    input = "TARGET" + driver + ";" + driver
                }

                4 -> {
                    val upstream = if (data.consumeBoolean()) "TARGET;" else "é;"
                    regex = upstream + "[^;]" + driver
                    input = "xx" + upstream + "x" + driver + "yy"
                }

                5 -> {
                    regex = "TARGET[^;]*" + driver + "[^;]X"
                    input = "X" + "TARGET".repeat(data.consumeInt(16, 128)) + driver + "y"
                }

                6 -> {
                    regex = "TARGET[^;]*MID[^;]*" + driver
                    input = "TARGETMID" + driver + "MID;" + driver
                }

                7 -> {
                    regex = "TARGET[^;]*?MID[^:]*" + driver
                    input = "TARGETMID:MID" + driver
                }

                8 -> {
                    regex = "TARGET[^;]*\\uDE00" + driver
                    input = "TARGET" + (if (data.consumeBoolean()) "😀" else "?") + driver
                }

                9 -> {
                    // A gap in leading position. Every other shape here opens with a literal the
                    // accelerator can lead with, so the match start is proposed rather than recovered.
                    val minimum = data.consumeInt(0, 4)
                    val maximum = data.consumeInt(minimum + 1, 6)
                    val lazy = if (data.consumeBoolean()) "?" else ""
                    regex = "(?s).{" + minimum + "," + maximum + "}" + lazy + "TARGET[^;]*" + driver
                    input =
                        (data.pickValue<String?>(mutableListOf<String?>("x", "é", "😀")).repeat(data.consumeInt(0, 7))
                                + "TARGET"
                                + driver)
                }

                10 -> {
                    // Inline scoped UNIX_LINES changes which characters a gap may span: CR, NEL and
                    // LINE SEPARATOR are line terminators by default but not under (?d).
                    val maximum = data.consumeInt(1, 4)
                    regex =
                        data.pickValue<String?>(
                            mutableListOf<String?>(
                                "",
                                "(?d)",
                                "(?d)(?s)"
                            )
                        ) + "TARGET.{1," + maximum + "}" + driver
                    input =
                        ("TARGET"
                                + data.pickValue<String?>(
                            mutableListOf<String?>(
                                "\r",
                                "\n",
                                "\r\n",
                                "\u0085",
                                "\u2028",
                                "x"
                            )
                        )
                                + driver)
                }

                else -> {
                    regex = "TARGET[^;]*?" + driver
                    input = "TARGETx;".repeat(data.consumeInt(1, 32)) + driver
                }
            }

            val pattern = FuzzSupport.compileCompatibleOrSkip(regex, 0)
            if (pattern != null) {
                val matcher = pattern.matcher(input)
                while (matcher.find()) {
                }
            }
        }

        private fun assertAlternationAndQuantifierPriorityLookingAtMatchesJdk(
            data: FuzzedDataProvider
        ) {
            val regression = PriorityCases.choose(data)
            val pattern =
                FuzzSupport.compileCompatibleOrSkip(regression.regex, 0)
            if (pattern != null) {
                pattern.matcher(data.pickValue<String?>(regression.inputs)).lookingAt()
            }
        }

        private fun assertFullMatchesSafeRe(regex: String?, flags: Int, inputs: MutableList<String>) {
            val pattern = Pattern.compile(regex, flags)
            for (input in inputs) {
                if (!pattern.matcher(input).matches()) {
                    throw AssertionError(
                        ("SafeRE model regression"
                                + "\nRegex: "
                                + regex
                                + "\nFlags: "
                                + flags
                                + "\nInput: "
                                + input)
                    )
                }
            }
        }
    }
}
