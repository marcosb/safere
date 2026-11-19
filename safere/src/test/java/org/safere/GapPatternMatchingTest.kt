// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.safere.MultiAnchorDescriptor.RejectPlan
import org.safere.MultiAnchorDescriptor.RejectPlan.RequiredLiteral
import org.safere.MultiAnchorDescriptor.StartPlan
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class GapPatternMatchingTest {
    @ParameterizedTest
    @ValueSource(
        strings = ["[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z error", "[0-9]{2}TOKEN", "AAA[0-9]{2}TOKEN", "AAA[0-9]{2}TOKEN[a-z]{2}", "(?i)AAA[0-9]{2}TOKEN"
        ]
    )
    fun fixedChainsReuseCompiledMetadataAcrossSearches(regex: String) {
        val pattern = Pattern.compile(regex)
        val jdkPattern = Pattern.compile(regex)
        val valid = if (regex.startsWith("[0-9]{4}")) "2026-08-30T12:00:00Z error" else "AAA12TOKENab"
        val noise = "2026/08/30 12:00:00 [worker-42] info: normal periodic heartbeat\n"
        for (input in arrayOf<String>(
            "",
            noise.repeat(20),
            valid,
            noise + valid,
            valid + noise + valid,
            "Z error TOKEN AAAxxTOKEN " + noise + valid
        )) {
            val matcher = pattern.matcher(input)
            val utf8Matcher = pattern.matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
            for (repeat in 0..1) {
                val expected = jdkPattern.matcher(input)
                while (expected.find()) {
                    Assertions.assertThat(matcher.find()).`as`(regex).isTrue()
                    Assertions.assertThat(matcher.start()).isEqualTo(expected.start())
                    Assertions.assertThat(matcher.end()).isEqualTo(expected.end())
                    Assertions.assertThat(utf8Matcher.find()).`as`(regex).isTrue()
                    Assertions.assertThat(utf8Matcher.start()).isEqualTo(expected.start())
                    Assertions.assertThat(utf8Matcher.end()).isEqualTo(expected.end())
                }
                Assertions.assertThat(matcher.find()).isFalse()
                Assertions.assertThat(utf8Matcher.find()).isFalse()
                matcher.reset()
                utf8Matcher.reset()
            }
        }
    }

    @Test
    fun patternMultiAnchorIntegrationStructure() {
        val pattern = org.safere.Pattern.compile("header:.*body:.*footer")
        val actual = pattern.multiAnchor()

        Assertions.assertThat<StartPlan?>(actual.startPlan())
            .usingRecursiveComparison()
            .isEqualTo(StartPlan.Literal("header:", false))
        Assertions.assertThat<RejectPlan?>(actual.rejectPlan())
            .usingRecursiveComparison()
            .isEqualTo(RequiredLiteral("body:"))
    }

    @Test
    fun boundaryGapsRuntimeBehavior() {
        val anchoredPattern = org.safere.Pattern.compile("^START.*END$")
        Assertions.assertThat(anchoredPattern.matcher("START between END").find()).isTrue()
        Assertions.assertThat(anchoredPattern.matcher("prefix START between END").find()).isFalse()
        Assertions.assertThat(anchoredPattern.matcher("START between END suffix").find()).isFalse()

        val wordPattern = org.safere.Pattern.compile("\\bWORD.*TAIL")
        Assertions.assertThat(wordPattern.matcher("a WORD with TAIL").find()).isTrue()
        Assertions.assertThat(wordPattern.matcher("NO_WORD with TAIL").find()).isFalse()
    }

    @Test
    fun emptyGapsAndAdjacentAnchors() {
        val pattern = org.safere.Pattern.compile("ABC.*DEF")
        val text = "ABCDEF"
        val matcher = pattern.matcher(text)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.group(0)).isEqualTo("ABCDEF")
    }

    @Test
    fun deferredCaptureExtractionParity() {
        val regex = "A(?<grp1>\\d+)B(?<grp2>[a-z]+)C"
        val saferePattern = org.safere.Pattern.compile(regex)
        val jdkPattern = Pattern.compile(regex)

        val text = "noise A12345BqwertyC trailing"
        val safereMatcher = saferePattern.matcher(text)
        val jdkMatcher = jdkPattern.matcher(text)

        Assertions.assertThat(safereMatcher.find()).isTrue()
        Assertions.assertThat(jdkMatcher.find()).isTrue()
        Assertions.assertThat(safereMatcher.group("grp1")).isEqualTo(jdkMatcher.group("grp1"))
        Assertions.assertThat(safereMatcher.group("grp2")).isEqualTo(jdkMatcher.group("grp2"))
        Assertions.assertThat(safereMatcher.start()).isEqualTo(jdkMatcher.start())
        Assertions.assertThat(safereMatcher.end()).isEqualTo(jdkMatcher.end())
    }

    @Test
    fun multiInfixBasicMatch() {
        val regex = ".*foo.*bar.*baz.*"
        val pattern = org.safere.Pattern.compile(regex)

        val text = "prefix foo intermediate bar trailing baz suffix"
        val matcher = pattern.matcher(text)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(0)
        Assertions.assertThat(matcher.end()).isEqualTo(text.length)
    }

    @Test
    fun multiInfixPartialMatchNegativeRejection() {
        val regex = ".*foo.*bar.*baz.*"
        val pattern = org.safere.Pattern.compile(regex)

        // "foo" and "bar" present, but "baz" is absent -> instant rejection in Phase 1
        val text = "prefix foo intermediate bar trailing qux suffix"
        val matcher = pattern.matcher(text)
        Assertions.assertThat(matcher.find()).isFalse()
    }

    @Test
    fun multiInfixUtf8Input() {
        val regex = ".*foo.*bar.*baz.*"
        val pattern = org.safere.Pattern.compile(regex)

        val bytes = "prefix foo intermediate bar trailing baz suffix".toByteArray(StandardCharsets.UTF_8)
        val input = Utf8Input.validated(bytes)

        val matcher = pattern.matcher(input)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(0)
        Assertions.assertThat(matcher.end()).isEqualTo(bytes.size)
    }

    @Test
    fun structuredMultiClauseLog() {
        val regex = "error:\\[(\\w+)\\]\\s+code:(\\d+)\\s+msg:([^\n]+)"
        val pattern = org.safere.Pattern.compile(regex)

        val text = "2026-08-27 12:00:00 error:[CRITICAL] code:500 msg:Internal Server Error\n"
        val matcher = pattern.matcher(text)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.group(0)).isEqualTo("error:[CRITICAL] code:500 msg:Internal Server Error")
        Assertions.assertThat(matcher.group(1)).isEqualTo("CRITICAL")
        Assertions.assertThat(matcher.group(2)).isEqualTo("500")
        Assertions.assertThat(matcher.group(3)).isEqualTo("Internal Server Error")
    }

    @Test
    fun singleLineGapNewlineBoundary() {
        val regex = "START[^\n]*MIDDLE[^\n]*END"
        val pattern = org.safere.Pattern.compile(regex)

        // Fails when a newline is present between START and MIDDLE
        val multilineFail = "START some text\nMIDDLE some text END"
        val m1 = pattern.matcher(multilineFail)
        Assertions.assertThat(m1.find()).isFalse()

        // Succeeds when all tokens are on the same line
        val singleLineSuccess = "START some text MIDDLE some text END"
        val m2 = pattern.matcher(singleLineSuccess)
        Assertions.assertThat(m2.find()).isTrue()
        Assertions.assertThat(m2.group(0)).isEqualTo("START some text MIDDLE some text END")
    }

    @Test
    fun dotallMultiLineSuccess() {
        val regex = "(?s)START.*MIDDLE.*END"
        val pattern = org.safere.Pattern.compile(regex)

        val text = "START line 1\nMIDDLE line 2\nEND"
        val matcher = pattern.matcher(text)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.group(0)).isEqualTo(text)
    }

    @Test
    fun adversarialDenseNoiseStillFindsTheMatch() {
        // Pattern looking for A followed by B with bounded noise
        val regex = "A[0-9]{3}B"
        val pattern = org.safere.Pattern.compile(regex)

        // Dense stream of 5,000 'A's without digits, ending with valid match
        val sb = StringBuilder()
        for (i in 0..4999) {
            sb.append("Axx")
        }
        sb.append("A123B")

        val haystack = sb.toString()
        val matcher = pattern.matcher(haystack)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.group(0)).isEqualTo("A123B")
        Assertions.assertThat(matcher.start()).isEqualTo(haystack.size - 5)
    }

    @Test
    fun alternationAnchors() {
        val regex = ".*(GET|POST|PUT)\\s+/api/v1/(users|orders).*"
        val pattern = org.safere.Pattern.compile(regex)

        val text = "Incoming request: POST /api/v1/orders HTTP/1.1"
        val matcher = pattern.matcher(text)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(0)
        Assertions.assertThat(matcher.end()).isEqualTo(text.length)
    }

    @Test
    fun jdkEquivalenceAcrossOffsets() {
        val regex = "id:([a-z]+)\\s+count:(\\d+)"
        val saferePattern = org.safere.Pattern.compile(regex)
        val jdkPattern = Pattern.compile(regex)

        val text = "noise id:first count:100 intermediate id:second count:200 trailing"
        val safereMatcher = saferePattern.matcher(text)
        val jdkMatcher = jdkPattern.matcher(text)

        while (jdkMatcher.find()) {
            Assertions.assertThat(safereMatcher.find()).isTrue()
            Assertions.assertThat(safereMatcher.start()).isEqualTo(jdkMatcher.start())
            Assertions.assertThat(safereMatcher.end()).isEqualTo(jdkMatcher.end())
            Assertions.assertThat(safereMatcher.group(0)).isEqualTo(jdkMatcher.group(0))
            Assertions.assertThat(safereMatcher.group(1)).isEqualTo(jdkMatcher.group(1))
            Assertions.assertThat(safereMatcher.group(2)).isEqualTo(jdkMatcher.group(2))
        }
        Assertions.assertThat(safereMatcher.find()).isFalse()
    }

    @Test
    fun multiAnchorLogCaptureExtraction() {
        val regex = "error:\\[([A-Z]+)\\]\\s+code:(\\d+)\\s+msg:([a-z]+)"
        val saferePattern = org.safere.Pattern.compile(regex)
        val jdkPattern = Pattern.compile(regex)

        val text = "noise error:[CRITICAL] code:500 msg:crash trailing"
        val safereMatcher = saferePattern.matcher(text)
        val jdkMatcher = jdkPattern.matcher(text)

        Assertions.assertThat(safereMatcher.find()).isTrue()
        Assertions.assertThat(jdkMatcher.find()).isTrue()
        Assertions.assertThat(safereMatcher.start()).isEqualTo(jdkMatcher.start())
        Assertions.assertThat(safereMatcher.end()).isEqualTo(jdkMatcher.end())
        Assertions.assertThat(safereMatcher.group(0)).isEqualTo(jdkMatcher.group(0))
        Assertions.assertThat(safereMatcher.group(1)).isEqualTo("CRITICAL")
        Assertions.assertThat(safereMatcher.group(2)).isEqualTo("500")
        Assertions.assertThat(safereMatcher.group(3)).isEqualTo("crash")
    }

    @Test
    fun endAnchoredChainDoesNotAcceptAnEarlierAnchor() {
        assertFirstMatchEqualsJdk("AAA.*BB$", "AAABBxBB")
    }

    @Test
    fun greedyInternalGapChoosesTheLastCompatibleAnchor() {
        assertFirstMatchEqualsJdk("AAA.*BB", "AAABBxBB")
    }

    @Test
    fun lazyInternalGapRetriesAnAnchorThatCanCompleteTheChain() {
        assertFirstMatchEqualsJdk("AAA.*?BB.CC", "AAAxBBxxBBzCC")
    }

    @Test
    fun quantifiedInternalGapCountsUnicodeCodePoints() {
        assertFirstMatchEqualsJdk("AAA.BB", "AAA😀BB")

        val pattern = org.safere.Pattern.compile("AAA.BB")
        val matcher = pattern.matcher(Utf8Input.validated("AAA😀BB".toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(matcher.find()).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["\r", "", " ", " "])
    fun defaultDotRejectsEveryJdkLineTerminator(lineTerminator: String?) {
        assertFirstMatchEqualsJdk("AAA.*BB", "AAA" + lineTerminator + "BB")
    }

    @Test
    fun ambiguousInteriorWildcardMatchesCorrectly() {
        assertFirstMatchEqualsJdk("AAA.*BBB.*CCC", "AAA xxx BBB yyy CCC zzz BBB www")
    }

    @Test
    fun fixedAnchorChainMatches() {
        val pattern = org.safere.Pattern.compile("AAA[0-9]BB")
        val matcher = pattern.matcher("noise AAA1BB trailing")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.group()).isEqualTo("AAA1BB")

        val utf8Matcher =
            pattern.matcher(Utf8Input.validated("noise AAA1BB trailing".toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(utf8Matcher.find()).isTrue()
    }

    @Test
    fun fixedCompoundGapMatchesTheJdk() {
        assertFirstMatchEqualsJdk("AAA(?:[0-9]x){2}BB", "AAAaxaxBB")
    }

    @Test
    fun multipleTrailingConstraintsMatchTheJdk() {
        assertFirstMatchEqualsJdk("AAA[0-9]BB[0-9]\\b", "AAA1BB2x")
        assertFirstMatchEqualsJdk("AAA[0-9]BB[0-9][A-Za-z0-9_]", "x AAA1BB2!")
    }

    @Test
    fun variableLengthAlternationAnchorMatchesTheJdk() {
        assertFirstMatchEqualsJdk("(foo|foobar)[0-9]ZZ", "foobar1ZZ")
    }

    @Test
    fun foldedSupplementaryLiteralMatchesUtf8() {
        val pattern = org.safere.Pattern.compile("😀A[0-9]BB", org.safere.Pattern.CASE_INSENSITIVE)

        val matcher = pattern.matcher(Utf8Input.validated("😀A1BB".toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(matcher.find()).isTrue()
    }

    @Test
    fun apiCaseInsensitiveFlagRespectsScopedDisabling() {
        for (regex in arrayOf<String>("(?-i:AAA)[0-9]BB", "AAA[0-9](?-i:BB)", "(?-i:AAA)([0-9])BB")) {
            assertFirstMatchEqualsJdk(regex, org.safere.Pattern.CASE_INSENSITIVE, "aaa1bb")
        }
    }

    @Test
    fun rarestAnchorBidirectionalExecution() {
        // "security_alert_code" is rarest anchor at index 2
        val regex = "user:([a-z]+)\\s+action:([a-z]+)\\s+security_alert_code:(\\d+)"
        val saferePattern = org.safere.Pattern.compile(regex)
        val jdkPattern = Pattern.compile(regex)

        val text =
            "user:alice action:read status:200 user:bob action:write security_alert_code:999 trailing"
        val safereMatcher = saferePattern.matcher(text)
        val jdkMatcher = jdkPattern.matcher(text)

        Assertions.assertThat(safereMatcher.find()).isTrue()
        Assertions.assertThat(jdkMatcher.find()).isTrue()
        Assertions.assertThat(safereMatcher.start()).isEqualTo(jdkMatcher.start())
        Assertions.assertThat(safereMatcher.end()).isEqualTo(jdkMatcher.end())
        Assertions.assertThat(safereMatcher.group(0)).isEqualTo(jdkMatcher.group(0))
        Assertions.assertThat(safereMatcher.group(1)).isEqualTo("bob")
        Assertions.assertThat(safereMatcher.group(2)).isEqualTo("write")
        Assertions.assertThat(safereMatcher.group(3)).isEqualTo("999")
    }

    @Test
    fun rarestAnchorBoundedUpstreamVerification() {
        val regex = "PREFIX[0-9]MIDDLE[0-9]RAREST_TOKEN_XYZ[0-9]SUFFIX"
        val pattern = org.safere.Pattern.compile(regex)

        val text = "noise PREFIX1MIDDLE2RAREST_TOKEN_XYZ3SUFFIX trailing"
        val matcher = pattern.matcher(text)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.group(0)).isEqualTo("PREFIX1MIDDLE2RAREST_TOKEN_XYZ3SUFFIX")

        // Absent rarest token -> instant mismatch
        val absent = "noise PREFIX1MIDDLE2OTHER_TOKEN_1233SUFFIX trailing"
        val m2 = pattern.matcher(absent)
        Assertions.assertThat(m2.find()).isFalse()
    }

    @Test
    fun rarestAnchorUnboundedUpstreamVerification() {
        val regex = "START.*RAREST_ANCHOR_12345.*END"
        val saferePattern = org.safere.Pattern.compile(regex)
        val jdkPattern = Pattern.compile(regex)

        val text = "START noise intermediate RAREST_ANCHOR_12345 more noise END"
        val safereMatcher = saferePattern.matcher(text)
        val jdkMatcher = jdkPattern.matcher(text)

        Assertions.assertThat(safereMatcher.find()).isTrue()
        Assertions.assertThat(jdkMatcher.find()).isTrue()
        Assertions.assertThat(safereMatcher.start()).isEqualTo(jdkMatcher.start())
        Assertions.assertThat(safereMatcher.end()).isEqualTo(jdkMatcher.end())
        Assertions.assertThat(safereMatcher.group(0)).isEqualTo(jdkMatcher.group(0))
    }

    @Test
    fun rarestAnchorMultipleOccurrencesLeftmost() {
        val regex = "HEAD[0-9]{2}RAREST[0-9]{2}TAIL"
        val saferePattern = org.safere.Pattern.compile(regex)
        val jdkPattern = Pattern.compile(regex)

        val text = "HEAD11RAREST22TAIL noise HEAD33RAREST44TAIL"
        val safereMatcher = saferePattern.matcher(text)
        val jdkMatcher = jdkPattern.matcher(text)

        Assertions.assertThat(safereMatcher.find()).isTrue()
        Assertions.assertThat(jdkMatcher.find()).isTrue()
        Assertions.assertThat(safereMatcher.start()).isEqualTo(jdkMatcher.start())
        Assertions.assertThat(safereMatcher.end()).isEqualTo(jdkMatcher.end())
        Assertions.assertThat(safereMatcher.group(0)).isEqualTo("HEAD11RAREST22TAIL")

        Assertions.assertThat(safereMatcher.find()).isTrue()
        Assertions.assertThat(jdkMatcher.find()).isTrue()
        Assertions.assertThat(safereMatcher.start()).isEqualTo(jdkMatcher.start())
        Assertions.assertThat(safereMatcher.end()).isEqualTo(jdkMatcher.end())
        Assertions.assertThat(safereMatcher.group(0)).isEqualTo("HEAD33RAREST44TAIL")
    }

    @Test
    fun rarestAnchorUtf8Equivalence() {
        val regex = "tag:([a-z]+)\\s+RAREST_KEY_TOKEN=(\\d+)"
        val pattern = org.safere.Pattern.compile(regex)

        val bytes = "noise tag:alpha RAREST_KEY_TOKEN=42 trailing".toByteArray(StandardCharsets.UTF_8)
        val input = Utf8Input.validated(bytes)

        val matcher = pattern.matcher(input)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(6)
        Assertions.assertThat(matcher.end()).isEqualTo(35)
        Assertions.assertThat(matcher.start(1)).isEqualTo(10)
        Assertions.assertThat(matcher.end(1)).isEqualTo(15)
        Assertions.assertThat(matcher.start(2)).isEqualTo(33)
        Assertions.assertThat(matcher.end(2)).isEqualTo(35)
    }

    @Test
    fun reverseDriverPreservesLeftmostStartAcrossVariableGap() {
        assertFirstMatchEqualsJdk("111[0-9]+RAREST_TOKEN", "1111112RAREST_TOKEN")
    }

    @Test
    fun utf8ReverseWindowAllowsMultibyteUpstreamLiteral() {
        val regex = "é".repeat(10) + "[0-9]" + "z".repeat(30)
        val text = regex.replace("[0-9]", "7")
        val pattern = org.safere.Pattern.compile(regex)

        Assertions.assertThat(pattern.matcher(text).find()).isTrue()
        val matcher = pattern.matcher(Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isZero()
        Assertions.assertThat(matcher.end()).isEqualTo(text.toByteArray(StandardCharsets.UTF_8).size)
    }

    @Test
    fun overlappingReverseDriverCandidatesRetainUpstreamSearchRange() {
        val regex = "aaaaaaaaaa[zZ]zzzz"
        val text = "Xaaaaaaaaaazzzzz"
        assertFirstMatchEqualsJdk(regex, text)

        val pattern = org.safere.Pattern.compile(regex)
        val matcher = pattern.matcher(Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(1)
        Assertions.assertThat(matcher.end()).isEqualTo(16)
    }

    @Test
    fun finiteDotallWildcardGapsHonorTheirCodePointBounds() {
        assertFirstMatchEqualsJdk("(?s)TARGET.", "TARGETabc")
        assertFirstMatchEqualsJdk("(?s).TARGET", "abcTARGET")
        assertFirstMatchEqualsJdk("(?s)TARGET.{1,2}", "TARGETabc")

        val pattern = org.safere.Pattern.compile("(?s)TARGET.")
        val matcher = pattern.matcher(Utf8Input.validated("TARGET😀x".toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isZero()
        Assertions.assertThat(matcher.end()).isEqualTo("TARGET😀".toByteArray(StandardCharsets.UTF_8).size)
    }

    @Test
    fun variableLeadingAndInteriorGapsPreserveQuantifierPriority() {
        assertFirstMatchEqualsJdk(".*AAA", "AAA x AAA")
        assertFirstMatchEqualsJdk(".*?AAA", "xAAA")
        assertFirstMatchEqualsJdk("AAA[A-Z]+BBB", "AAAXBBB1BBB")
    }

    @Test
    fun boundedUnicodeClassGapsCountCodePoints() {
        val regex = "AAA[éê]{1,10}BBB"
        val text = "AAA" + "éê".repeat(5) + "BBB"
        assertFirstMatchEqualsJdk(regex, text)

        val pattern = org.safere.Pattern.compile(regex)
        val matcher = pattern.matcher(Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isZero()
        Assertions.assertThat(matcher.end()).isEqualTo(text.toByteArray(StandardCharsets.UTF_8).size)
    }

    @Test
    fun adjacentMixedFlavorGapsPreservePriority() {
        assertFirstMatchEqualsJdk("AAA.*?.*", "AAAxyz")
        assertFirstMatchEqualsJdk("AAA.*.*?", "AAAxyz")
    }

    @Test
    fun broadCharacterClassesRetainTheirExactMembership() {
        assertFirstMatchEqualsJdk("AAA[^\\nX]*", "AAAabXcd")
        assertFirstMatchEqualsJdk("AAA.*", org.safere.Pattern.UNIX_LINES, "AAAa\rnext")
    }

    @Test
    fun multipleLeadingWildcardsCoalesce() {
        assertFirstMatchEqualsJdk(".*.*AAA.*.*", "hello world AAA foo bar\nnext line")
    }

    @Test
    fun singleAnchorWithVariableLeadingAndTrailingGaps() {
        assertFirstMatchEqualsJdk(".*AAA.*", "noise AAA trailing\nsecond line")
        assertFirstMatchEqualsJdk("\\s+AAA\\s+", "hello   AAA   world")
    }

    @Test
    fun singleAnchorWithLeadingWildcardInUtf8() {
        val pattern = org.safere.Pattern.compile(".*TARGET_KEY")
        val bytes = "prefix data TARGET_KEY trailing".toByteArray(StandardCharsets.UTF_8)
        val input = Utf8Input.validated(bytes)
        val matcher = pattern.matcher(input)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(0)
        Assertions.assertThat(matcher.end()).isEqualTo(22)
    }

    @Test
    fun assertionsAfterLeadingCharacterClassMatchTheJdk() {
        for (testCase in arrayOf<Array<String?>?>(
            arrayOf<String?>("[a-z]\\bAAA", "xAAA"),
            arrayOf<String?>("[ ]\\BAAA", " AAA"),
            arrayOf<String?>("(?m)[a-z]^AAA", "xAAA"),
            arrayOf<String?>("(?m)[a-z]\$AAA", "xAAA"),
            arrayOf<String?>("[a-z]\\bAAA[0-9]RAREST_TOKEN", "xAAA1RAREST_TOKEN")
        )) {
            val regex: String = testCase!![0]!!
            val text: String = testCase[1]!!
            val pattern = org.safere.Pattern.compile(regex)

            assertFirstMatchEqualsJdk(regex, text)

            val utf8Matcher = pattern.matcher(Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8)))
            Assertions.assertThat(utf8Matcher.find())
                .`as`("UTF-8 membership for %s", regex)
                .isEqualTo(Pattern.compile(regex).matcher(text).find())
        }
    }

    @Test
    fun factoredAlternationPrefixesPreserveScopedCaseFlags() {
        for (testCase in arrayOf<Array<String?>?>(
            arrayOf<String?>("(?:(?i:abc)X|abcY)", "ABCX"),
            arrayOf<String?>("(?:abcX|(?i:abc)Y)", "ABCY"),
            arrayOf<String?>("(?i:(?-i:abc)X|abcY)", "ABCY"),
            arrayOf<String?>("(?i:(?-i:abc)X|abcY)", "ABCX"),
            arrayOf<String?>("(?:(?i:abc)X|abcY)[0-9]RAREST_TOKEN", "ABCX1RAREST_TOKEN")
        )) {
            assertFirstMatchEqualsJdk(testCase!![0]!!, testCase[1]!!)
        }
    }

    @Test
    fun alternationPrefixFactoring() {
        val regex = "(?:application/json|application/xml|application/pdf)"
        val pattern = org.safere.Pattern.compile(regex)

        val text = "Content-Type: application/json; charset=utf-8"
        val matcher = pattern.matcher(text)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.group(0)).isEqualTo("application/json")

        val xmlText = "Accept: application/xml"
        val mXml = pattern.matcher(xmlText)
        Assertions.assertThat(mXml.find()).isTrue()
        Assertions.assertThat(mXml.group(0)).isEqualTo("application/xml")

        val absentText = "Content-Type: text/plain"
        val mAbsent = pattern.matcher(absentText)
        Assertions.assertThat(mAbsent.find()).isFalse()
    }

    @Test
    fun alternationSuffixFactoring() {
        val regex = "(?:https?://|ftp://|sftp://)api/v1/[a-z]+"
        val pattern = org.safere.Pattern.compile(regex)

        val text = "Endpoint: https://api/v1/users and ftp://api/v1/files"
        val matcher = pattern.matcher(text)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.group(0)).isEqualTo("https://api/v1/users")

        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.group(0)).isEqualTo("ftp://api/v1/files")

        val absentText = "Endpoint: gopher://other/path"
        val mAbsent = pattern.matcher(absentText)
        Assertions.assertThat(mAbsent.find()).isFalse()
    }

    @Test
    fun fixedWidthRepetitionDateGap() {
        val regex = "\\d{4}-\\d{2}-\\d{2}"
        val pattern = org.safere.Pattern.compile(regex)

        val text = "Event logged at 2026-08-29 in system"
        val matcher = pattern.matcher(text)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.group(0)).isEqualTo("2026-08-29")

        // Invalid format (e.g. 3 digits instead of 4) -> mismatch
        val invalid = "Event logged at 202-08-29 in system"
        val mInv = pattern.matcher(invalid)
        Assertions.assertThat(mInv.find()).isFalse()
    }

    @Test
    fun fixedWidthRepetitionUuidGap() {
        val regex = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        val pattern = org.safere.Pattern.compile(regex)

        val text = "Request ID: 12345678-abcd-ef01-2345-6789abcdef01 processed"
        val matcher = pattern.matcher(text)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.group(0)).isEqualTo("12345678-abcd-ef01-2345-6789abcdef01")

        val text2 = "Malformed ID: 1234567-abcd-ef01-2345-6789abcdef01"
        val m2 = pattern.matcher(text2)
        Assertions.assertThat(m2.find()).isFalse()
    }

    @Test
    fun dynamicCoalescedWeakAnchorChain() {
        val regex = "PREFIX_START_[a-z0-9]{2}_MID_[a-z0-9]{2}_RAREST_FINAL_TOKEN"
        val pattern = org.safere.Pattern.compile(regex)

        val text = "noise PREFIX_START_ab_MID_cd_RAREST_FINAL_TOKEN trailing"
        val matcher = pattern.matcher(text)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.group(0)).isEqualTo("PREFIX_START_ab_MID_cd_RAREST_FINAL_TOKEN")

        val absent = "noise PREFIX_START_ab_MID_cd_OTHER_FINAL_TOKEN trailing"
        val mAbsent = pattern.matcher(absent)
        Assertions.assertThat(mAbsent.find()).isFalse()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun structuredHeaderGuardByteRejection(useUtf8: Boolean) {
        val pattern = org.safere.Pattern.compile("header:[^\\r\\n;]*val")

        val valid = "prefix header:custom-content-12345val suffix"
        Assertions.assertThat<String?>(findMatches(pattern, valid, useUtf8))
            .containsExactly("header:custom-content-12345val")

        // Newline in gap -> rejected
        val withNl = "prefix header:custom\ncontentval suffix"
        Assertions.assertThat<String?>(findMatches(pattern, withNl, useUtf8)).isEmpty()

        // Semicolon in gap -> rejected
        val withSemi = "prefix header:custom;contentval suffix"
        Assertions.assertThat<String?>(findMatches(pattern, withSemi, useUtf8)).isEmpty()

        // Carriage return in gap -> rejected
        val withCr = "prefix header:custom\rcontentval suffix"
        Assertions.assertThat<String?>(findMatches(pattern, withCr, useUtf8)).isEmpty()

        // Multiline runaway where false anchor "val" is on subsequent line
        val multiline = "header:some_header_text\nother_text_without_start val"
        Assertions.assertThat<String?>(findMatches(pattern, multiline, useUtf8)).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun quotedStringDelimiterGuardByteRejection(useUtf8: Boolean) {
        val pattern = org.safere.Pattern.compile("\"[^\"]*\"")

        val simple = "leading \"hello world\" trailing"
        Assertions.assertThat<String?>(findMatches(pattern, simple, useUtf8)).containsExactly("\"hello world\"")

        // Multiple quoted strings on one line: engine must stop at the first quote delimiter
        val multi = "first \"foo\" and second \"bar\" end"
        Assertions.assertThat<String?>(findMatches(pattern, multi, useUtf8)).containsExactly("\"foo\"", "\"bar\"")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun singleLineDelimiterReverseRejection(useUtf8: Boolean) {
        val pattern = org.safere.Pattern.compile("START[^\\n]*END")

        val sameLine = "noise START some content END trailing"
        Assertions.assertThat<String?>(findMatches(pattern, sameLine, useUtf8))
            .containsExactly("START some content END")

        // START on line 1, END on line 2 -> reverse driver must reject across newline
        val acrossLines = "START line one\nline two with END"
        Assertions.assertThat<String?>(findMatches(pattern, acrossLines, useUtf8)).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun guardedGreedyGapChoosesSuffixBeforeFirstGuard(useUtf8: Boolean) {
        val pattern = org.safere.Pattern.compile("AAA[^;]*BBB")

        Assertions.assertThat<String?>(findMatches(pattern, "AAABBB;BBB", useUtf8)).containsExactly("AAABBB")
        Assertions.assertThat<String?>(findMatches(pattern, "AAAxxBBB;BBB", useUtf8)).containsExactly("AAAxxBBB")
        Assertions.assertThat<String?>(findMatches(pattern, "AAA;BBB", useUtf8)).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun reverseGuardPruningRetainsUpstreamAnchorContainingGuard(useUtf8: Boolean) {
        val ascii = org.safere.Pattern.compile("AAA;[^;]RAREBBBB")
        val nonAscii = org.safere.Pattern.compile("é;[^;]RAREBBBBBBBB")

        Assertions.assertThat<String?>(findMatches(ascii, "xxAAA;xRAREBBBByy", useUtf8))
            .containsExactly("AAA;xRAREBBBB")
        Assertions.assertThat<String?>(findMatches(nonAscii, "é;xRAREBBBBBBBB", useUtf8))
            .containsExactly("é;xRAREBBBBBBBB")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun guardedGapWithFallibleContinuationMatches(useUtf8: Boolean) {
        val greedy = org.safere.Pattern.compile("AAA[^;]*BBB[^;]*CCC")
        val reluctant = org.safere.Pattern.compile("AAA[^;]*?BBB[^:]*CCC")

        Assertions.assertThat<String?>(findMatches(greedy, "AAABBBCCCBBB;CCC", useUtf8)).containsExactly("AAABBBCCC")
        Assertions.assertThat<String?>(findMatches(reluctant, "AAABBB:BBBCCC", useUtf8))
            .containsExactly("AAABBB:BBBCCC")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun guardedBoundedTrailingGapEndsOnCodePointBoundary(useUtf8: Boolean) {
        val optional = org.safere.Pattern.compile("AAA[^;]?")
        val bounded = org.safere.Pattern.compile("AAA[^;]{0,2}")
        val interior = org.safere.Pattern.compile("AAA[^;]?BBB")

        Assertions.assertThat<String?>(findMatches(optional, "xxAAA\ud83d\ude00zz", useUtf8))
            .containsExactly("AAA\ud83d\ude00")
        Assertions.assertThat<String?>(findMatches(bounded, "xxAAA\ud83d\ude00\ud83d\ude03z", useUtf8))
            .containsExactly("AAA\ud83d\ude00\ud83d\ude03")
        Assertions.assertThat<String?>(findMatches(interior, "xxAAA\ud83d\ude00BBBzz", useUtf8))
            .containsExactly("AAA\ud83d\ude00BBB")
        Assertions.assertThat<String?>(findMatches(optional, "xxAAA;zz", useUtf8)).containsExactly("AAA")
    }

    @Test
    fun guardedGapAnchorsStartOnlyAtCodePointBoundaries() {
        val text = "xxAAA\ud83d\ude00BBBzz"

        for (regex in arrayOf<String>("AAA[^;]*\\uDE00BBB", "AAA[^;]?\\uDE00BBB")) {
            Assertions.assertThat(Pattern.compile(regex).matcher(text).find()).isFalse()
            Assertions.assertThat(org.safere.Pattern.compile(regex).matcher(text).find()).`as`(regex).isFalse()
        }

        val laterValidSuffix = "AAA\ud83d\ude00BBB\ude00BBB"
        val regex = "AAA[^;]*\\uDE00BBB"
        val jdk = Pattern.compile(regex).matcher(laterValidSuffix)
        val safeRe = org.safere.Pattern.compile(regex).matcher(laterValidSuffix)
        Assertions.assertThat(jdk.find()).isTrue()
        Assertions.assertThat(safeRe.find()).isTrue()
        Assertions.assertThat(safeRe.group()).isEqualTo(jdk.group())

        Assertions.assertThat(org.safere.Pattern.compile("AAA\\uD83D[^;]*").matcher("AAA\ud83d\ude00").find()).isFalse()
        Assertions.assertThat(org.safere.Pattern.compile("AAA[^;]*BBB\\uD83D").matcher("AAABBB\ud83d\ude00").find())
            .isFalse()

        val utf8 =
            org.safere.Pattern.compile(regex)
                .matcher(Utf8Input.validated("AAA?BBB".toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(utf8.find()).isFalse()
    }

    @Test
    fun unixLinesDotMatchesCarriageReturnAndStopsAtNewline() {
        val pattern = org.safere.Pattern.compile("AAA.*BBB", org.safere.Pattern.UNIX_LINES)

        // Contains \r -> matches because \r is allowed under UNIX_LINES
        val withCr = "AAAcontent\rwith_crBBB"
        val m1 = pattern.matcher(withCr)
        Assertions.assertThat(m1.find()).isTrue()
        Assertions.assertThat(m1.group(0)).isEqualTo(withCr)

        // Contains \n -> rejected across newline boundary
        val withNl = "AAAcontent\nnewlineBBB"
        val m2 = pattern.matcher(withNl)
        Assertions.assertThat(m2.find()).isFalse()

        // UTF-8 input test
        val utf8m1 = pattern.matcher(Utf8Input.validated(withCr.toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(utf8m1.find()).isTrue()
        Assertions.assertThat(utf8m1.end() - utf8m1.start()).isEqualTo(withCr.toByteArray(StandardCharsets.UTF_8).size)
    }

    companion object {
        private fun assertFirstMatchEqualsJdk(regex: String, text: String) {
            assertFirstMatchEqualsJdk(regex, 0, text)
        }

        private fun assertFirstMatchEqualsJdk(regex: String, flags: Int, text: String) {
            val safere = org.safere.Pattern.compile(regex, flags).matcher(text)
            val jdk = Pattern.compile(regex, flags).matcher(text)

            val expected = jdk.find()
            Assertions.assertThat(safere.find()).isEqualTo(expected)
            if (expected) {
                Assertions.assertThat(safere.start()).isEqualTo(jdk.start())
                Assertions.assertThat(safere.end()).isEqualTo(jdk.end())
                Assertions.assertThat(safere.group()).isEqualTo(jdk.group())
            }
        }

        private fun findMatches(pattern: org.safere.Pattern, text: String, useUtf8: Boolean): MutableList<String?> {
            val matches: MutableList<String?> = ArrayList<String?>()
            if (useUtf8) {
                val bytes = text.toByteArray(StandardCharsets.UTF_8)
                val matcher = pattern.matcher(Utf8Input.validated(bytes))
                while (matcher.find()) {
                    matches.add(String(bytes, matcher.start(), matcher.end() - matcher.start(), StandardCharsets.UTF_8))
                }
            } else {
                val matcher = pattern.matcher(text)
                while (matcher.find()) {
                    matches.add(matcher.group(0))
                }
            }
            return matches
        }
    }
}
