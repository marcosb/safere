// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.List
import java.util.regex.Pattern

internal class Utf8MatcherStateMachineTest {
    @Test
    @DisabledForCrosscheck("Opaque grapheme regions follow the independent-substring model")
    fun graphemeContextFollowsOpaqueUtf8RegionChanges() {
        val input = "x\uD83C\uDDE6\uD83C\uDDE7\uD83C\uDDE8y"
        val bytes = input.toByteArray(StandardCharsets.UTF_8)
        for (regex in mutableListOf<String?>("\\b{g}\\X\\b{g}", "\\b{g}\\X\\b{g}($)?")) {
            val pattern = Pattern.compile(regex)
            val matcher = pattern.matcher(Utf8Input.validated(bytes))
            for (start in intArrayOf(0, 1, 5, 9, 13)) {
                for (end in intArrayOf(start, 13, 14)) {
                    matcher.region(start, end)
                    val substring =
                        pattern.matcher(Utf8Input.validated(Arrays.copyOfRange(bytes, start, end)))
                    val expected: MutableList<Int?> = ArrayList<Int?>()
                    while (substring.find()) {
                        expected.add(substring.start())
                        expected.add(substring.end())
                    }
                    val actual: MutableList<Int?> = ArrayList<Int?>()
                    while (matcher.find()) {
                        actual.add(matcher.start() - start)
                        actual.add(matcher.end() - start)
                    }
                    Assertions.assertThat<Int?>(actual).`as`("region %s:%s", start, end).isEqualTo(expected)
                }
            }
        }
    }

    @Test
    fun failedFindPreservesEmptyAnchoredMatchEnd() {
        val matcher: Utf8Matcher = matcher("^[ab]*", "😀!a")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.lookingAt()).isTrue()
        for (i in 0..2) {
            Assertions.assertThat(matcher.find()).isFalse()
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.start()).isZero()
            Assertions.assertThat(matcher.end()).isZero()
        }
    }

    @Test
    fun stringSearchInitializationDoesNotCorruptExistingUtf8Matcher() {
        val pattern = Pattern.compile("XXXXXX")
        val stringMatcher = pattern.matcher("..XXXXXX")
        val utf8Matcher = pattern.matcher(Utf8Input.validated("..XXXXXX".toByteArray(StandardCharsets.UTF_8)))

        Assertions.assertThat(stringMatcher.find()).isTrue()
        Assertions.assertThat(utf8Matcher.find()).isTrue()
        Assertions.assertThat(utf8Matcher.start()).isEqualTo(2)
        Assertions.assertThat(utf8Matcher.end()).isEqualTo(8)
    }

    @ParameterizedTest
    @ValueSource(strings = ["X", "XX", "XXX", "XXXX", "XXXXX", "XXXXXX", "XXXXXXXX", "ééé"])
    fun unanchoredLiteralSearchMatchesStringApiAcrossOffsets(literal: String) {
        for (prefixLength in 0..8) {
            val text = ".".repeat(prefixLength) + literal + "!"
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            val expectedStart = ".".repeat(prefixLength).toByteArray(StandardCharsets.UTF_8).size
            val expectedEnd = expectedStart + literal.toByteArray(StandardCharsets.UTF_8).size

            val captureFreePattern = Pattern.compile(literal)
            val captureFreeStringMatcher = captureFreePattern.matcher(text)
            Assertions.assertThat(captureFreeStringMatcher.find())
                .`as`("String prefix length %s", prefixLength)
                .isTrue()
            Assertions.assertThat(captureFreePattern.find(Utf8Input.validated(bytes)))
                .`as`("capture-free UTF-8 prefix length %s", prefixLength)
                .isTrue()

            val matcherPattern = Pattern.compile(literal)
            val stringMatcher = matcherPattern.matcher(text)
            Assertions.assertThat(stringMatcher.find()).`as`("String matcher prefix length %s", prefixLength).isTrue()
            val matcher = matcherPattern.matcher(Utf8Input.validated(bytes))
            Assertions.assertThat(matcher.find()).`as`("UTF-8 prefix length %s", prefixLength).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(expectedStart)
            Assertions.assertThat(matcher.end()).isEqualTo(expectedEnd)
            Assertions.assertThat(matcher.find()).isFalse()
        }
    }

    @Test
    fun repeatedFindReportsByteRelativeGroupBounds() {
        val matcher: Utf8Matcher = matcher("(é)|(😀)", "xé😀y")

        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(1)
        Assertions.assertThat(matcher.end()).isEqualTo(3)
        Assertions.assertThat(matcher.start(1)).isEqualTo(1)
        Assertions.assertThat(matcher.end(1)).isEqualTo(3)
        Assertions.assertThat(matcher.start(2)).isEqualTo(-1)
        Assertions.assertThat(matcher.end(2)).isEqualTo(-1)

        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(3)
        Assertions.assertThat(matcher.end()).isEqualTo(7)
        Assertions.assertThat(matcher.start(1)).isEqualTo(-1)
        Assertions.assertThat(matcher.start(2)).isEqualTo(3)
        Assertions.assertThat(matcher.end(2)).isEqualTo(7)

        Assertions.assertThat(matcher.find()).isFalse()
        Assertions.assertThat(matcher.find()).isFalse()
    }

    @Test
    fun keywordAlternationMatchesJdkAcrossUtf8Boundaries() {
        assertKeywordAlternationMatch(
            "(?is).*\\b(you|your)\\b.*",
            "préface You spoke first.\nThen your example ended with YOU!",
            0
        )
        assertKeywordAlternationMatch(
            "(?is).*\\b(you|your)\\b.*",
            "é ignore YOU, then keep your answer",
            "é ignore YOU, ".length
        )
        assertKeywordAlternationMatch("(?isU).*\\b(you|your)\\b.*", "éyoué -- your!", 0)
        assertKeywordAlternationMatch("(?isU).*\\b(you|your)\\b.*", "éyoué -- βyourβ", 0)
    }

    @Test
    fun keywordAlternationPreservesIndependentUtf8BoundaryModes() {
        assertKeywordAlternationMatch("(?-U)\\b(?i)(foo|bar)(?U)\\b", "éfoo! fooé bar!", 0)
        assertKeywordAlternationMatch("(?U)\\b(?i)(foo|bar)(?-U)\\b", "éfoo! fooé bar!", 0)
    }

    @Test
    fun keywordAlternationFindsAlternativesAndRejectsAdjacentWordCharacters() {
        val input = "préface error warning2 _timeout failed!"
        val matcher: Utf8Matcher = matcher("(?i)\\b(error|warning|timeout|failed)\\b", input)

        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(utf8Slice(input, matcher.start(1), matcher.end(1))).isEqualTo("error")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(utf8Slice(input, matcher.start(1), matcher.end(1))).isEqualTo("failed")
        Assertions.assertThat(matcher.find()).isFalse()

        val absent = Pattern.compile("(?is).*\\b(you|your)\\b.*")
        val absentInput = Utf8Input.validated("é youth yours".toByteArray(StandardCharsets.UTF_8))
        Assertions.assertThat(absent.matcher(absentInput).find()).isFalse()
        Assertions.assertThat(absent.find(absentInput)).isFalse()
    }

    @Test
    fun emptyFindAdvancesByOneCodePoint() {
        val matcher: Utf8Matcher = matcher("", "😀")
        val bounds: MutableList<MutableList<Int?>?> = ArrayList<MutableList<Int?>?>()

        while (matcher.find()) {
            bounds.add(List.of<Int?>(matcher.start(), matcher.end()))
        }

        Assertions.assertThat<MutableList<Int?>?>(bounds)
            .containsExactly(mutableListOf<Int?>(0, 0), mutableListOf<Int?>(4, 4))
    }

    @Test
    fun failedFindInvalidatesPreviousResult() {
        val matcher: Utf8Matcher = matcher("a", "a")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.find()).isFalse()

        Assertions.assertThatThrownBy(ThrowingCallable { matcher.start() })
            .isInstanceOf(IllegalStateException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.end() })
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun wholeInputAndPrefixMatchesUseUtf8Input() {
        val whole: Utf8Matcher = matcher("é😀", "é😀")
        val prefix: Utf8Matcher = matcher("é", "é😀")

        Assertions.assertThat(whole.matches()).isTrue()
        Assertions.assertThat(whole.start()).isZero()
        Assertions.assertThat(whole.end()).isEqualTo(6)
        Assertions.assertThat(prefix.matches()).isFalse()
        Assertions.assertThat(prefix.lookingAt()).isTrue()
        Assertions.assertThat(prefix.start()).isZero()
        Assertions.assertThat(prefix.end()).isEqualTo(2)
    }

    @Test
    fun resetRestoresWholeInputAndInvalidatesPreviousResult() {
        val matcher: Utf8Matcher = matcher("é|😀", "é😀")

        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isZero()
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(2)

        Assertions.assertThat<Utf8Matcher?>(matcher.reset()).isSameAs(matcher)
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.start() })
            .isInstanceOf(IllegalStateException::class.java)
        Assertions.assertThat(matcher.regionStart()).isZero()
        Assertions.assertThat(matcher.regionEnd()).isEqualTo(6)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isZero()
    }

    @Test
    fun regionUsesRelativeByteOffsetsAndResetRestoresWholeInput() {
        val matcher: Utf8Matcher = matcher("😀", "xé😀y")

        Assertions.assertThat<Utf8Matcher?>(matcher.region(3, 7)).isSameAs(matcher)
        Assertions.assertThat(matcher.regionStart()).isEqualTo(3)
        Assertions.assertThat(matcher.regionEnd()).isEqualTo(7)
        Assertions.assertThat(matcher.matches()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(3)
        Assertions.assertThat(matcher.end()).isEqualTo(7)

        matcher.region(3, 8)
        Assertions.assertThat(matcher.lookingAt()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(3)
        Assertions.assertThat(matcher.end()).isEqualTo(7)

        matcher.region(3, 8)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(3)
        Assertions.assertThat(matcher.end()).isEqualTo(7)

        matcher.reset()
        Assertions.assertThat(matcher.regionStart()).isZero()
        Assertions.assertThat(matcher.regionEnd()).isEqualTo(8)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(3)
    }

    @Test
    fun regionMatchOperationsPreserveBoundarySensitiveCaptures() {
        data class Scenario(val regex: String?, val input: String?, val regionStart: Int, val regionEnd: Int)

        for (scenario in List.of<Scenario?>(
            Scenario("(\\b(?:a|aa))", "xaa", 1, 3),
            Scenario("((?:a|aa)\\b)", "aax", 0, 2)
        )) {
            for (operation in mutableListOf<String?>("matches", "lookingAt", "find")) {
                val oracle =
                    Pattern.compile(scenario.regex).matcher(scenario.input)
                oracle.region(scenario.regionStart, scenario.regionEnd)
                val expected =
                    when (operation) {
                        "matches" -> oracle.matches()
                        "lookingAt" -> oracle.lookingAt()
                        "find" -> oracle.find()
                        else -> throw AssertionError(operation)
                    }

                val actual: Utf8Matcher = Companion.matcher(scenario.regex, scenario.input!!)
                actual.region(scenario.regionStart, scenario.regionEnd)
                val matched =
                    when (operation) {
                        "matches" -> actual.matches()
                        "lookingAt" -> actual.lookingAt()
                        "find" -> actual.find()
                        else -> throw AssertionError(operation)
                    }

                Assertions.assertThat(matched).`as`("%s for /%s/", operation, scenario.regex).isEqualTo(expected)
                Assertions.assertThat(actual.start(1))
                    .`as`("%s capture start for /%s/", operation, scenario.regex)
                    .isEqualTo(oracle.start(1))
                Assertions.assertThat(actual.end(1))
                    .`as`("%s capture end for /%s/", operation, scenario.regex)
                    .isEqualTo(oracle.end(1))
            }
        }
    }

    @Test
    fun regionRejectsInvalidByteRanges() {
        val matcher: Utf8Matcher = matcher("é", "xéy")

        Assertions.assertThatThrownBy(ThrowingCallable { matcher.region(-1, 1) })
            .isInstanceOf(IndexOutOfBoundsException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.region(0, 5) })
            .isInstanceOf(IndexOutOfBoundsException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.region(3, 2) })
            .isInstanceOf(IndexOutOfBoundsException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.region(2, 3) })
            .isInstanceOf(IndexOutOfBoundsException::class.java)
            .hasMessageContaining("code-point boundaries")
    }

    @Test
    fun observationBeforeMatchAndInvalidGroupsThrow() {
        val matcher: Utf8Matcher = matcher("(a)", "a")

        Assertions.assertThatThrownBy(ThrowingCallable { matcher.start() })
            .isInstanceOf(IllegalStateException::class.java)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.groupCount()).isEqualTo(1)
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.start(-1) })
            .isInstanceOf(IndexOutOfBoundsException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.end(2) })
            .isInstanceOf(IndexOutOfBoundsException::class.java)
    }

    @Test
    fun patternEntryPointsRejectNullInput() {
        val pattern = org.safere.Pattern.compile("a")

        Assertions.assertThatThrownBy(ThrowingCallable { pattern.matcher(null as Utf8Input?) })
            .isInstanceOf(NullPointerException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { pattern.find(null as Utf8Input?) })
            .isInstanceOf(NullPointerException::class.java)
    }

    @Test
    fun wholeArrayAndWindowViewsProduceTheSameFindTrace() {
        val logical = "xé😀y".toByteArray(StandardCharsets.UTF_8)
        val storage = ByteArray(logical.size + 6)
        System.arraycopy(logical, 0, storage, 3, logical.size)
        val pattern = org.safere.Pattern.compile("é|😀")

        Assertions.assertThat<MutableList<Int?>?>(findTrace(pattern.matcher(Utf8Input.validated(logical))))
            .isEqualTo(findTrace(pattern.matcher(Utf8Input.validated(storage, 3, logical.size))))
    }

    @Test
    fun inputWindowHidesBackingStorageAndKeepsRelativeBounds() {
        val logical = "xéy".toByteArray(StandardCharsets.UTF_8)
        val storage = "before--------after".toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(logical, 0, storage, 7, logical.size)
        val input = Utf8Input.validated(storage, 7, logical.size)

        val matcher = org.safere.Pattern.compile("é").matcher(input)

        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(1)
        Assertions.assertThat(matcher.end()).isEqualTo(3)
        Assertions.assertThat(matcher.find()).isFalse()
    }

    @Test
    fun patternBooleanSearchUsesUtf8Input() {
        val pattern = org.safere.Pattern.compile("😀$")

        Assertions.assertThat(pattern.find(Utf8Input.validated("x😀".toByteArray(StandardCharsets.UTF_8)))).isTrue()
        Assertions.assertThat(pattern.find(Utf8Input.validated("😀x".toByteArray(StandardCharsets.UTF_8)))).isFalse()
        Assertions.assertThat(
            org.safere.Pattern.compile("(?<letter>é)|😀")
                .find(Utf8Input.validated("xé".toByteArray(StandardCharsets.UTF_8)))
        )
            .isTrue()
        Assertions.assertThat(
            org.safere.Pattern.compile("\\Xz").find(Utf8Input.validated("👩‍💻x".toByteArray(StandardCharsets.UTF_8)))
        )
            .isFalse()
    }

    @Test
    fun patternBooleanSearchUsesFixedOffsetLiteralCandidates() {
        val phone = org.safere.Pattern.compile("\\d{3}/\\d{3}/\\d{4}")

        Assertions.assertThat(phone.find(Utf8Input.validated("path /api/42".toByteArray(StandardCharsets.UTF_8))))
            .isFalse()
        Assertions.assertThat(phone.find(Utf8Input.validated("path /api then 123/456/7890".toByteArray(StandardCharsets.UTF_8))))
            .isTrue()
        Assertions.assertThat(phone.find(Utf8Input.validated("digits 1234567890".toByteArray(StandardCharsets.UTF_8))))
            .isFalse()
    }

    @Test
    fun asciiPrefixClassesAgreeAcrossStringAndUtf8SearchState() {
        val text = "ébbxqbybbzbb5qbb7bb@qbb"
        val input = Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8))

        for (regex in mutableListOf<String?>("[x]q?", "[xy]q?", "[xz]q?", "[0-9]q?", "[ace@]q?")) {
            val pattern = org.safere.Pattern.compile(regex)
            val stringMatcher = pattern.matcher(text)
            val utf8Matcher = pattern.matcher(input)
            val expected: MutableList<MutableList<Int?>?> = ArrayList<MutableList<Int?>?>()
            val actual: MutableList<MutableList<Int?>?> = ArrayList<MutableList<Int?>?>()

            while (stringMatcher.find()) {
                expected.add(
                    List.of<Int?>(
                        utf8Offset(text, stringMatcher.start()), utf8Offset(text, stringMatcher.end())
                    )
                )
            }
            while (utf8Matcher.find()) {
                actual.add(List.of<Int?>(utf8Matcher.start(), utf8Matcher.end()))
            }

            Assertions.assertThat(pattern.find(input)).`as`("direct UTF-8 find for /%s/", regex).isTrue()
            Assertions.assertThat<MutableList<Int?>?>(actual).`as`("repeated UTF-8 find for /%s/", regex)
                .isEqualTo(expected)

            utf8Matcher.region(2, input.length)
            Assertions.assertThat(utf8Matcher.find()).`as`("region find for /%s/", regex).isTrue()
            utf8Matcher.reset()
            Assertions.assertThat<MutableList<Int?>?>(findTrace(utf8Matcher)).`as`("reset find trace for /%s/", regex)
                .isEqualTo(expected)
        }
    }

    @Test
    fun requiredNonAsciiClassRejectsAsciiInputAcrossUtf8EntryPoints() {
        val pattern = org.safere.Pattern.compile("[一-龥]{3,}")
        val absent = Utf8Input.trusted("ordinary ASCII text".toByteArray(StandardCharsets.UTF_8))
        val present = Utf8Input.validated("prefix中文测试suffix".toByteArray(StandardCharsets.UTF_8))

        Assertions.assertThat(pattern.find(absent)).isFalse()
        Assertions.assertThat(pattern.matcher(absent).find()).isFalse()
        Assertions.assertThat(pattern.find(present)).isTrue()
        Assertions.assertThat(pattern.matcher(present).find()).isTrue()
    }

    @Test
    fun graphemePatternDoesNotSplitMultibyteCodePointInUnanchoredFind() {
        val input = "\u1e99| 5  \u8ed6"
        val utf8Matcher =
            org.safere.Pattern.compile(".\\X| /0? ?5-")
                .matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
        val actualMatches: MutableList<MutableList<Int?>?> = ArrayList<MutableList<Int?>?>()
        while (utf8Matcher.find()) {
            actualMatches.add(List.of<Int?>(utf8Matcher.start(), utf8Matcher.end()))
        }

        val stringMatcher = org.safere.Pattern.compile(".\\X| /0? ?5-").matcher(input)
        val expectedMatches: MutableList<MutableList<Int?>?> = ArrayList<MutableList<Int?>?>()
        while (stringMatcher.find()) {
            expectedMatches.add(
                List.of<Int?>(
                    utf8Offset(input, stringMatcher.start()), utf8Offset(input, stringMatcher.end())
                )
            )
        }

        Assertions.assertThat<MutableList<Int?>?>(actualMatches).isEqualTo(expectedMatches)
    }

    @Test
    fun graphemePatternsWorkAcrossUtf8Scalars() {
        for (input in mutableListOf<String?>("a\r\nb", "a\u0301b", "👩‍💻x", "🇺🇸x")) {
            Companion.assertGraphemePatternsWorkAcrossUtf8Scalars(input!!)
        }
    }

    @Test
    @DisabledForCrosscheck("SafeRE uses Unicode 17 grapheme rule GB9c while JDK 21 uses Unicode 15.0")
    fun indicConjunctGraphemePatternsWorkAcrossUtf8ScalarsOnEverySupportedJdk() {
        assertGraphemePatternsWorkAcrossUtf8Scalars("क्‍षx")
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_22)
    fun indicConjunctGraphemePatternsMatchJdk22AndLaterAcrossUtf8Scalars() {
        assertGraphemePatternsWorkAcrossUtf8Scalars("क्‍षx")
    }

    @Test
    fun caseInsensitiveMultiCharPrefixMatchesAcrossVectorBoundaries() {
        val pattern = "(?i)http_query"
        val input =
            ("http_first http_second http_third "
                    + "HTTP_other HTTP_next "
                    + "hTtP_almost "
                    + "HTTP_QUERY_final")
        val p = org.safere.Pattern.compile(pattern)
        val matcher = p.matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))

        Assertions.assertThat(matcher.find()).isTrue()
        val expectedStart = input.indexOf("HTTP_QUERY")
        Assertions.assertThat(matcher.start()).isEqualTo(expectedStart)
        Assertions.assertThat(matcher.end()).isEqualTo(expectedStart + "http_query".length)
        Assertions.assertThat(matcher.find()).isFalse()
    }

    @Test
    fun caseInsensitivePrefixWithRareAnchorHandlesDenseFalseCandidates() {
        // 't' is very common, 'z' is rare.
        val pattern = "(?i)the_zone"
        val noise = "the_alpha the_beta the_gamma the_delta "
        val input = noise.repeat(5) + "THE_ZONE"
        val p = org.safere.Pattern.compile(pattern)
        val matcher = p.matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))

        Assertions.assertThat(matcher.find()).isTrue()
        val expectedStart = input.indexOf("THE_ZONE")
        Assertions.assertThat(matcher.start()).isEqualTo(expectedStart)
        Assertions.assertThat(matcher.end()).isEqualTo(expectedStart + "the_zone".length)
    }

    companion object {
        private fun assertGraphemePatternsWorkAcrossUtf8Scalars(input: String) {
            val bytes = input.toByteArray(StandardCharsets.UTF_8)
            val clusters = org.safere.Pattern.compile("\\X").matcher(Utf8Input.validated(bytes))
            val actualClusters: MutableList<MutableList<Int?>?> = ArrayList<MutableList<Int?>?>()
            while (clusters.find()) {
                actualClusters.add(List.of<Int?>(clusters.start(), clusters.end()))
            }

            val stringClusters = org.safere.Pattern.compile("\\X").matcher(input)
            val expectedClusters: MutableList<MutableList<Int?>?> = ArrayList<MutableList<Int?>?>()
            while (stringClusters.find()) {
                expectedClusters.add(
                    List.of<Int?>(
                        utf8Offset(input, stringClusters.start()), utf8Offset(input, stringClusters.end())
                    )
                )
            }
            Assertions.assertThat<MutableList<Int?>?>(actualClusters).`as`("grapheme clusters for %s", input)
                .isEqualTo(expectedClusters)

            val boundaries = org.safere.Pattern.compile("\\b{g}").matcher(Utf8Input.validated(bytes))
            val actualBoundaries: MutableList<Int?> = ArrayList<Int?>()
            while (boundaries.find()) {
                actualBoundaries.add(boundaries.start())
            }
            val stringBoundaries = org.safere.Pattern.compile("\\b{g}").matcher(input)
            val expectedBoundaries: MutableList<Int?> = ArrayList<Int?>()
            while (stringBoundaries.find()) {
                expectedBoundaries.add(utf8Offset(input, stringBoundaries.start()))
            }
            Assertions.assertThat<Int?>(actualBoundaries)
                .`as`("grapheme boundaries for %s", input)
                .isEqualTo(expectedBoundaries)
        }

        private fun matcher(pattern: String?, input: String): Utf8Matcher {
            return org.safere.Pattern.compile(pattern)
                .matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
        }

        private fun assertKeywordAlternationMatch(regex: String, input: String, regionStart: Int) {
            val pattern = org.safere.Pattern.compile(regex)
            val jdkMatcher =
                Pattern.compile(regex).matcher(input).region(regionStart, input.length)
            val byteRegionStart: Int = utf8Offset(input, regionStart)
            val utf8Input = Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))
            val utf8Matcher =
                pattern.matcher(utf8Input).region(byteRegionStart, utf8Input.size)

            val jdkFound = jdkMatcher.find()
            Assertions.assertThat(pattern.find(utf8Input)).isEqualTo(jdkFound)
            Assertions.assertThat(utf8Matcher.find()).isEqualTo(jdkFound)
            if (!jdkFound) {
                return
            }
            for (group in 0..jdkMatcher.groupCount()) {
                val expectedStart =
                    if (jdkMatcher.start(group) < 0) -1 else utf8Offset(input, jdkMatcher.start(group))
                val expectedEnd = if (jdkMatcher.end(group) < 0) -1 else utf8Offset(input, jdkMatcher.end(group))
                Assertions.assertThat(utf8Matcher.start(group)).`as`("group %s start", group).isEqualTo(expectedStart)
                Assertions.assertThat(utf8Matcher.end(group)).`as`("group %s end", group).isEqualTo(expectedEnd)
            }
        }

        private fun utf8Slice(input: String, start: Int, end: Int): String {
            val bytes = input.toByteArray(StandardCharsets.UTF_8)
            return String(bytes, start, end - start, StandardCharsets.UTF_8)
        }

        private fun findTrace(matcher: Utf8Matcher): MutableList<MutableList<Int?>?> {
            val trace: MutableList<MutableList<Int?>?> = ArrayList<MutableList<Int?>?>()
            while (matcher.find()) {
                trace.add(List.of<Int?>(matcher.start(), matcher.end()))
            }
            return trace
        }

        private fun utf8Offset(text: String, utf16Offset: Int): Int {
            return text.substring(0, utf16Offset).toByteArray(StandardCharsets.UTF_8).size
        }
    }
}
