// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.safere.CharClassScanInfo.AsciiRanges
import org.safere.CharClassScanInfo.AsciiSmallSet
import org.safere.MultiAnchorDescriptor.RejectPlan
import org.safere.MultiAnchorDescriptor.RejectPlan.RequiredCharClass
import org.safere.MultiAnchorDescriptor.RejectPlan.RequiredLiteral
import org.safere.Pattern.EndAnchoredCharClassInfo
import org.safere.Pattern.SuffixInfo
import java.nio.charset.StandardCharsets

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class RejectPrefilterTest {
    @Test
    fun terminalRejectionDoesNotSearchForMissingInteriorContent() {
        val pattern = Pattern.compile("^.*missing.*END\\z")
        val input = "x".repeat(4096) + "END"
        Assertions.assertThat(pattern.terminalPrefilterRejects(StringInputScanner(input), 0, null)).isFalse()
        Assertions.assertThat(pattern.terminalPrefilterRejects(utf8Scanner(input), 0, null)).isFalse()
        Assertions.assertThat(pattern.matcher(input).matches()).isFalse()
        Assertions.assertThat(pattern.terminalPrefilterRejects(StringInputScanner(input + "X"), 0, null))
            .isTrue()
        Assertions.assertThat(pattern.terminalPrefilterRejects(utf8Scanner(input + "X"), 0, null)).isTrue()
    }

    @Test
    fun terminalRejectionRetainsAnchorAndOptionEligibility() {
        for (regex in arrayOf<String>("missing", "^(a|b)*$")) {
            val pattern = Pattern.compile(regex)
            Assertions.assertThat(pattern.terminalPrefilterRejects(StringInputScanner("c"), 0, null))
                .`as`(regex)
                .isFalse()
            Assertions.assertThat(pattern.terminalPrefilterRejects(utf8Scanner("c"), 0, null)).`as`(regex).isFalse()
        }
        val dollar = Pattern.compile("^.*END$")
        Assertions.assertThat(dollar.terminalPrefilterRejects(StringInputScanner("END\r\n"), 0, null))
            .isFalse()
        Assertions.assertThat(dollar.terminalPrefilterRejects(utf8Scanner("END\r\n"), 0, null)).isFalse()
        val disabled = EnginePathOptions.builder().literalFastPaths(false).build()
        val pattern = Pattern.compile("^.*END\\z", 0, disabled)
        Assertions.assertThat(pattern.terminalPrefilterRejects(StringInputScanner("ENX"), 0, null)).isFalse()
        Assertions.assertThat(pattern.terminalPrefilterRejects(utf8Scanner("ENX"), 0, null)).isFalse()
    }

    @Test
    fun nullAndNoneDescriptorsProduceNullPrefilter() {
        Assertions.assertThat<RejectPrefilter?>(RejectPrefilter.create(null as MultiAnchorDescriptor?)).isNull()
        Assertions.assertThat<RejectPrefilter?>(RejectPrefilter.create(null as RejectPlan?)).isNull()
        Assertions.assertThat<RejectPrefilter?>(RejectPrefilter.create(RejectPlan.None.INSTANCE)).isNull()
    }

    @Test
    fun literalTailRejectionAgreesWithWholeInputRejection() {
        val prefilter: RejectPrefilter = RejectPrefilter.Literal.create("</block>")
        val options = EnginePathOptions.allEnabled()
        val present = "This is a <block>" + "content bytes here... blah ".repeat(4) + "</block> x"
        val absent = present.replace("</block>", "<block/>")
        for (text in arrayOf<String>(present, absent)) {
            for (searchFrom in 0..text.length) {
                val expected = text.indexOf("</block>", searchFrom) < 0
                Assertions.assertThat(prefilter.canRejectTail(text, null, searchFrom, options))
                    .`as`("String from %d", searchFrom)
                    .isEqualTo(expected)
                Assertions.assertThat(prefilter.canRejectTail(null, utf8Scanner(text), searchFrom, options))
                    .`as`("UTF-8 from %d", searchFrom)
                    .isEqualTo(expected)
                Assertions.assertThat<MatchStrategy?>(
                    RejectPrefilter.Composite(arrayOf<RejectPrefilter>(prefilter))
                        .tailRejectionStrategy(null, utf8Scanner(text), searchFrom, options)
                )
                    .`as`("composite UTF-8 from %d", searchFrom)
                    .isEqualTo(if (expected) MatchStrategy.LITERAL else null)
            }
        }
        val disabled = EnginePathOptions.builder().literalFastPaths(false).build()
        Assertions.assertThat(prefilter.canRejectTail(null, utf8Scanner(absent), 0, disabled)).isFalse()
    }

    @Test
    fun literalRejectPrefilterRejectsMissingLiteral() {
        val plan: RejectPlan =
            RequiredLiteral("needle")

        val prefilter = RejectPrefilter.create(plan)
        Assertions.assertThat<RejectPrefilter?>(prefilter).isInstanceOf(RejectPrefilter.Literal::class.java)
        val lit = prefilter as RejectPrefilter.Literal
        Assertions.assertThat<MatchStrategy?>(lit.strategy()).isEqualTo(MatchStrategy.LITERAL)
        Assertions.assertThat(lit.literal).isEqualTo("needle")
        Assertions.assertThat(lit.utf8).isEqualTo("needle".toByteArray(StandardCharsets.UTF_8))
        Assertions.assertThat(lit.failure).isNotNull()
        Assertions.assertThat(lit.shifts).isNotNull()

        val options = EnginePathOptions.allEnabled()

        // String input
        Assertions.assertThat(prefilter.canReject("haystack with needle in it", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("haystack without keyword in it", null, 0, options)).isTrue()
        Assertions.assertThat(prefilter.canReject("needle is at start", null, 7, options)).isTrue()

        // UTF-8 scanner
        val matchingScanner: Utf8InputScanner = utf8Scanner("haystack with needle in it")
        val nonMatchingScanner: Utf8InputScanner = utf8Scanner("haystack without keyword in it")
        Assertions.assertThat(prefilter.canReject(null, matchingScanner, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, nonMatchingScanner, 0, options)).isTrue()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("needle is at start"), 7, options)).isTrue()

        // Disabled option
        val disabled = EnginePathOptions.builder().literalFastPaths(false).build()
        Assertions.assertThat(prefilter.canReject("haystack without keyword in it", null, 0, disabled)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, nonMatchingScanner, 0, disabled)).isFalse()
    }

    @Test
    fun charClassRejectPrefilterRejectsMissingClass() {
        // Digit class [0-9]
        val ranges = intArrayOf('0'.code, '9'.code)
        val b0 = 0x03FF000000000000L // digits 0-9
        val b1 = 0L
        val scanInfo: CharClassScanInfo = AsciiRanges(ranges, b0, b1)

        val plan: RejectPlan =
            RequiredCharClass(scanInfo)

        val prefilter = RejectPrefilter.create(plan)
        Assertions.assertThat<RejectPrefilter?>(prefilter).isInstanceOf(RejectPrefilter.CharClass::class.java)
        val cc = prefilter as RejectPrefilter.CharClass
        Assertions.assertThat<MatchStrategy?>(cc.strategy()).isEqualTo(MatchStrategy.CHARACTER_CLASS)
        Assertions.assertThat(cc.ranges).isEqualTo(ranges)
        Assertions.assertThat(cc.bitmap0).isEqualTo(b0)
        Assertions.assertThat(cc.bitmap1).isEqualTo(b1)

        val options = EnginePathOptions.allEnabled()

        Assertions.assertThat(prefilter.canReject("item-42-test", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("item-no-digits", null, 0, options)).isTrue()

        val matchingScanner: Utf8InputScanner = utf8Scanner("item-42-test")
        val nonMatchingScanner: Utf8InputScanner = utf8Scanner("item-no-digits")
        Assertions.assertThat(prefilter.canReject(null, matchingScanner, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, nonMatchingScanner, 0, options)).isTrue()

        // Disabled option
        val disabled = EnginePathOptions.builder().charClassMatchFastPaths(false).build()
        Assertions.assertThat(prefilter.canReject("item-no-digits", null, 0, disabled)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, nonMatchingScanner, 0, disabled)).isFalse()
    }

    @Test
    fun compositeRejectPrefilterRejectsIfAnyFilterRejects() {
        val ranges = intArrayOf('0'.code, '9'.code)
        val b0 = 0x03FF000000000000L
        val b1 = 0L
        val scanInfo: CharClassScanInfo = AsciiRanges(ranges, b0, b1)

        val plan: RejectPlan =
            RejectPlan.Composite(
                arrayOf<RejectPlan>(
                    RequiredLiteral("token"),
                    RequiredCharClass(scanInfo)
                )
            )
        val prefilter = RejectPrefilter.create(plan)

        Assertions.assertThat<RejectPrefilter?>(prefilter).isInstanceOf(RejectPrefilter.Composite::class.java)
        val composite = prefilter as RejectPrefilter.Composite
        Assertions.assertThat<RejectPrefilter?>(composite.filters).hasSize(2)

        val options = EnginePathOptions.allEnabled()

        // Has both token and digits -> not rejected
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("prefix token 123 suffix"), 0, options))
            .isFalse()
        Assertions.assertThat<MatchStrategy?>(
            prefilter.rejectionStrategy(null, utf8Scanner("prefix token 123 suffix"), 0, options)
        )
            .isNull()

        // Missing token -> rejected by LITERAL
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("prefix missing 123 suffix"), 0, options))
            .isTrue()
        Assertions.assertThat<MatchStrategy?>(
            prefilter.rejectionStrategy(null, utf8Scanner("prefix missing 123 suffix"), 0, options)
        )
            .isEqualTo(MatchStrategy.LITERAL)

        // Missing digits -> rejected by CHARACTER_CLASS
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("prefix token no-digits suffix"), 0, options))
            .isTrue()
        Assertions.assertThat<MatchStrategy?>(
            prefilter.rejectionStrategy(
                null, utf8Scanner("prefix token no-digits suffix"), 0, options
            )
        )
            .isEqualTo(MatchStrategy.CHARACTER_CLASS)
    }

    @Test
    fun rejectionStrategyReportsExactRejectingStrategy() {
        val plan: RejectPlan =
            RequiredLiteral("needle")
        val prefilter = RejectPrefilter.create(plan)

        val scanner: Utf8InputScanner = utf8Scanner("no match here")
        val strategy =
            prefilter.rejectionStrategy(null, scanner, 0, EnginePathOptions.allEnabled())
        Assertions.assertThat<MatchStrategy?>(strategy).isEqualTo(MatchStrategy.LITERAL)

        val accumulator = DiagnosticAccumulator()
        accumulator.participate(strategy, StrategyRole.REJECT_PREFILTER)
        accumulator.boundary(strategy)

        val pattern = Pattern.compile("needle")
        val event =
            accumulator.toEvent(
                pattern.descriptor(),
                MatchOperation.FIND,
                MatchOutcome.NO_MATCH,
                CaptureMode.NONE,
                scanner.length
            )
        Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
            .contains(StrategyParticipation(MatchStrategy.LITERAL, StrategyRole.REJECT_PREFILTER))
        Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.LITERAL)
    }

    @Test
    fun disjointLiteralsRejectPrefilterRejectsWhenAllMissing() {
        val literals: Array<String?> = arrayOf<String>("apple", "banana", "orange")
        val plan: RejectPlan =
            RejectPlan.DisjointLiterals(literals)

        val prefilter = RejectPrefilter.create(plan)
        Assertions.assertThat<RejectPrefilter?>(prefilter).isInstanceOf(RejectPrefilter.DisjointLiterals::class.java)
        val d = prefilter as RejectPrefilter.DisjointLiterals
        Assertions.assertThat<MatchStrategy?>(d.strategy()).isEqualTo(MatchStrategy.LITERAL)
        Assertions.assertThat<String?>(d.literals).isEqualTo(literals)
        Assertions.assertThat(d.utf8Literals).hasNumberOfRows(3)

        val options = EnginePathOptions.allEnabled()

        // String input
        Assertions.assertThat(prefilter.canReject("I like banana smoothie", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("I like apple pie", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("I like grape juice", null, 0, options)).isTrue()
        Assertions.assertThat(prefilter.canReject("I like grape juice", null, 1, options)).isFalse()

        // UTF-8 input
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("I like orange juice"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("I like apple pie"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("I like grape juice"), 0, options)).isTrue()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("I like grape juice"), 1, options)).isFalse()

        // Disabled option
        val disabled = EnginePathOptions.builder().literalFastPaths(false).build()
        Assertions.assertThat(prefilter.canReject("I like grape juice", null, 0, disabled)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("I like grape juice"), 0, disabled)).isFalse()
    }

    @Test
    fun disjointLiteralsOmitsUnpairedSurrogateBranchesFromUtf8Table() {
        val options = EnginePathOptions.allEnabled()

        val mixed: Array<String?> = arrayOf<String>("a\uD83D", "za\uD83D\uDE00", "banana")
        val mixedFilter =
            RejectPrefilter.create(RejectPlan.DisjointLiterals(mixed)) as RejectPrefilter.DisjointLiterals
        Assertions.assertThat(mixedFilter.utf8Literals).hasNumberOfRows(2)
        // String.getBytes(UTF_8) turns lone surrogates into '?', so "a?" must still be rejected.
        Assertions.assertThat(mixedFilter.canReject(null, utf8Scanner("a?x"), 0, options)).isTrue()
        Assertions.assertThat(mixedFilter.canReject(null, utf8Scanner("prefix za\uD83D\uDE00"), 0, options))
            .isFalse()
        Assertions.assertThat(mixedFilter.canReject(null, utf8Scanner("prefix banana"), 0, options)).isFalse()
        Assertions.assertThat(mixedFilter.canReject("prefix a\uD83D", null, 0, options)).isFalse()

        // Malformed CESU-8 surrogate bytes decode as U+FFFD in Utf8InputScanner, so they cannot match
        // the unpaired-surrogate branch either.
        val cesu8HighSurrogate = byteArrayOf('a'.code.toByte(), 0xED.toByte(), 0xA0.toByte(), 0xBD.toByte())
        val cesu8Scanner = Utf8InputScanner(cesu8HighSurrogate, 0, 4)
        Assertions.assertThat(mixedFilter.canReject(null, cesu8Scanner, 0, options)).isTrue()
        val pattern = Pattern.compile("(?:a\uD83D|za\uD83D\uDE00|banana)")
        Assertions.assertThat(pattern.matcher(Utf8Input.trusted(cesu8HighSurrogate)).matches()).isFalse()
        Assertions.assertThat(pattern.matcher(Utf8Input.trusted(cesu8HighSurrogate)).find()).isFalse()

        val allSurrogates: Array<String?> = arrayOf<String>("a\uD83D", "\uDE00b")
        val allSurrogateFilter =
            RejectPrefilter.create(
                RejectPlan.DisjointLiterals(allSurrogates)
            ) as RejectPrefilter.DisjointLiterals
        Assertions.assertThat(allSurrogateFilter.utf8Literals).isEmpty()
        Assertions.assertThat(allSurrogateFilter.canReject(null, utf8Scanner("a? ?b"), 0, options)).isTrue()
        Assertions.assertThat(allSurrogateFilter.canReject("xxa\uD83Dyy", null, 0, options)).isFalse()
    }

    @Test
    fun withoutDisjointLiteralsPreservesRemainingFilters() {
        val disjoint =
            RejectPrefilter.create(
                RejectPlan.DisjointLiterals(arrayOf<String>("foo", "bar"))
            )
        val literal =
            RejectPrefilter.create(RequiredLiteral("foo"))
        val suffix =
            RejectPrefilter.create(
                RejectPlan.EndAnchoredSuffix(
                    SuffixInfo(".json", true, false)
                )
            )

        Assertions.assertThat<RejectPrefilter?>(RejectPrefilter.withoutDisjointLiterals(null)).isNull()
        Assertions.assertThat<RejectPrefilter?>(RejectPrefilter.withoutDisjointLiterals(disjoint)).isNull()
        Assertions.assertThat<RejectPrefilter?>(RejectPrefilter.withoutDisjointLiterals(literal)).isSameAs(literal)

        val compositeWithDisjoint: RejectPrefilter =
            RejectPrefilter.Composite(arrayOf<RejectPrefilter?>(disjoint, suffix))
        Assertions.assertThat<RejectPrefilter?>(RejectPrefilter.withoutDisjointLiterals(compositeWithDisjoint))
            .isSameAs(suffix)

        val compositeWithoutDisjoint: RejectPrefilter =
            RejectPrefilter.Composite(arrayOf<RejectPrefilter?>(literal, suffix))
        Assertions.assertThat<RejectPrefilter?>(RejectPrefilter.withoutDisjointLiterals(compositeWithoutDisjoint))
            .isSameAs(compositeWithoutDisjoint)
    }

    @Test
    fun endAnchoredSuffixRejectPrefilterRejectsMismatchedSuffix() {
        val info = SuffixInfo(".json", true, false)
        val plan: RejectPlan =
            RejectPlan.EndAnchoredSuffix(info)

        val prefilter = RejectPrefilter.create(plan)
        Assertions.assertThat<RejectPrefilter?>(prefilter).isInstanceOf(RejectPrefilter.EndAnchoredSuffix::class.java)
        Assertions.assertThat<MatchStrategy?>(prefilter.strategy()).isEqualTo(MatchStrategy.LITERAL)

        val options = EnginePathOptions.allEnabled()

        // String input
        Assertions.assertThat(prefilter.canReject("config.json", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("config.json\n", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("config.json\r\n", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("config.json\r", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("config.json\u0085", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("config.json\u2028", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("config.json\u2029", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("config.yaml", null, 0, options)).isTrue()
        Assertions.assertThat(prefilter.canReject("config.yaml\n", null, 0, options)).isTrue()
        Assertions.assertThat(prefilter.canReject("config.yaml\u0085", null, 0, options)).isTrue()

        // UTF-8 input
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("config.json"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("config.json\n"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("config.json\r\n"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("config.json\r"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("config.json\u0085"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("config.json\u2028"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("config.json\u2029"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("config.yaml"), 0, options)).isTrue()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("config.yaml\n"), 0, options)).isTrue()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("config.yaml\u0085"), 0, options)).isTrue()

        // UNIX_LINES suffix prefilter
        val unixInfo = SuffixInfo(".json", true, true)
        val unixPrefilter =
            RejectPrefilter.create(RejectPlan.EndAnchoredSuffix(unixInfo))
        Assertions.assertThat(unixPrefilter.canReject("config.json\n", null, 0, options)).isFalse()
        Assertions.assertThat(unixPrefilter.canReject("config.json\r", null, 0, options)).isTrue()
        Assertions.assertThat(unixPrefilter.canReject("config.json\u0085", null, 0, options)).isTrue()
        Assertions.assertThat(unixPrefilter.canReject(null, utf8Scanner("config.json\n"), 0, options)).isFalse()
        Assertions.assertThat(unixPrefilter.canReject(null, utf8Scanner("config.json\r"), 0, options)).isTrue()
        Assertions.assertThat(unixPrefilter.canReject(null, utf8Scanner("config.json\u0085"), 0, options))
            .isTrue()

        // End-anchored rejection strategy
        val scanner: Utf8InputScanner = utf8Scanner("config.yaml")
        Assertions.assertThat<MatchStrategy?>(prefilter.endAnchoredRejectionStrategy(scanner, 0, options))
            .isEqualTo(MatchStrategy.LITERAL)
    }

    @Test
    fun endAnchoredCaseInsensitiveSuffixRejectPrefilterRejectsMismatchedSuffix() {
        val info = SuffixInfo(".json", true, false, true)
        val plan: RejectPlan =
            RejectPlan.EndAnchoredSuffix(info)

        val prefilter = RejectPrefilter.create(plan)
        Assertions.assertThat<RejectPrefilter?>(prefilter).isInstanceOf(RejectPrefilter.EndAnchoredSuffix::class.java)
        Assertions.assertThat<MatchStrategy?>(prefilter.strategy()).isEqualTo(MatchStrategy.LITERAL)

        val options = EnginePathOptions.allEnabled()

        // String input
        Assertions.assertThat(prefilter.canReject("config.json", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("config.JSON", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("config.JsOn\n", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("config.JSON\r\n", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("config.yaml", null, 0, options)).isTrue()
        Assertions.assertThat(prefilter.canReject("config.YAML\n", null, 0, options)).isTrue()

        // UTF-8 input
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("config.json"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("config.JSON"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("config.JsOn\n"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("config.JSON\r\n"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("config.yaml"), 0, options)).isTrue()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("config.YAML\n"), 0, options)).isTrue()
    }

    @Test
    fun endAnchoredCharClassRejectPrefilterRejectsMismatchedInputs() {
        val builder = AsciiBitmap.Builder()
        builder.addRange('0'.code, '9'.code)
        val info =
            EndAnchoredCharClassInfo(builder.build(), true, false)
        val plan: RejectPlan =
            RejectPlan.EndAnchoredCharClass(info)

        val prefilter = RejectPrefilter.create(plan)
        Assertions.assertThat<RejectPrefilter?>(prefilter)
            .isInstanceOf(RejectPrefilter.EndAnchoredCharClass::class.java)
        Assertions.assertThat<MatchStrategy?>(prefilter.strategy()).isEqualTo(MatchStrategy.CHARACTER_CLASS)

        val options = EnginePathOptions.allEnabled()

        // String input
        Assertions.assertThat(prefilter.canReject("item123", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("item123\n", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("item123\r\n", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("item123\r", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("item123\u0085", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("item123\u2028", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("item123\u2029", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("item123abc", null, 0, options)).isTrue()
        Assertions.assertThat(prefilter.canReject("item123abc\u0085", null, 0, options)).isTrue()

        // UTF-8 input
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("item123"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("item123\n"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("item123\r\n"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("item123\r"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("item123\u0085"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("item123\u2028"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("item123\u2029"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("item123abc"), 0, options)).isTrue()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("item123abc\u0085"), 0, options)).isTrue()

        // UNIX_LINES char class prefilter
        val unixInfo =
            EndAnchoredCharClassInfo(builder.build(), true, true)
        val unixPrefilter =
            RejectPrefilter.create(RejectPlan.EndAnchoredCharClass(unixInfo))
        Assertions.assertThat(unixPrefilter.canReject("item123\n", null, 0, options)).isFalse()
        Assertions.assertThat(unixPrefilter.canReject("item123\r", null, 0, options)).isTrue()
        Assertions.assertThat(unixPrefilter.canReject("item123\u0085", null, 0, options)).isTrue()
        Assertions.assertThat(unixPrefilter.canReject(null, utf8Scanner("item123\n"), 0, options)).isFalse()
        Assertions.assertThat(unixPrefilter.canReject(null, utf8Scanner("item123\r"), 0, options)).isTrue()
        Assertions.assertThat(unixPrefilter.canReject(null, utf8Scanner("item123\u0085"), 0, options)).isTrue()
    }

    @Test
    fun requiredInfixLiteralRetainedWhenPrefixPresent() {
        val p = Pattern.compile("(\\{Link:[^}]*?)<<!nav>>([^}]*?\\})")
        Assertions.assertThat(p.prefix()).isEqualTo("{Link:")
        Assertions.assertThat<RejectPlan?>(p.rejectPlan()).isNotNull()
        Assertions.assertThat<RejectPrefilter?>(p.rejectPrefilter()).isNotNull()

        // Negative text with {Link:...} but missing <<!nav>> should reject in Tier 0
        val textNoNav = "{Link: home_page}{Link: about_page}{Link: contact_page}"
        Assertions.assertThat(p.rejectPrefilter().canReject(textNoNav, null, 0, EnginePathOptions.allEnabled()))
            .isTrue()
        Assertions.assertThat(p.find(Utf8Input.validated(textNoNav.toByteArray(StandardCharsets.UTF_8)))).isFalse()

        // Positive text with <<!nav>> should match and capture properly
        val textWithNav = "{Link: home_page}{Link: section<<!nav>>item}"
        Assertions.assertThat(p.rejectPrefilter().canReject(textWithNav, null, 0, EnginePathOptions.allEnabled()))
            .isFalse()
        val m = p.matcher(textWithNav)
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.group(1)).isEqualTo("{Link: section")
        Assertions.assertThat(m.group(2)).isEqualTo("item}")
    }

    @Test
    fun requiredLiteralPrefersDistinctCandidateOverPrefix() {
        val p = Pattern.compile("(?s)<meta_start>.*?<meta_end>")
        Assertions.assertThat(p.prefix()).isEqualTo("<meta_start>")
        // Even though "<meta_start>" is longer than "<meta_end>", extractRequiredLiteral
        // should skip the prefix and select "<meta_end>".
        Assertions.assertThat<RejectPlan?>(p.rejectPlan()).isNotNull()
        Assertions.assertThat<RejectPrefilter?>(p.rejectPrefilter()).isNotNull()

        val inputNoEnd = "<meta_start>Thinking process: analyzing query...\n".repeat(1000)
        Assertions.assertThat(p.rejectPrefilter().canReject(inputNoEnd, null, 0, EnginePathOptions.allEnabled()))
            .isTrue()
        Assertions.assertThat(p.matcher(inputNoEnd).replaceAll("")).isEqualTo(inputNoEnd)
    }

    @Test
    fun identicalPrefixAndRequiredLiteralDeduplicated() {
        val p = Pattern.compile("abc[0-9]+")
        Assertions.assertThat(p.prefix()).isEqualTo("abc")
        Assertions.assertThat<RejectPlan?>(p.rejectPlan()).isInstanceOf(RejectPlan.None::class.java)
    }

    @Test
    fun oneCharacterAsciiClassScansAsACharacterSearch() {
        val scanInfo =
            CharClassScanInfo.fromCharClass(CharClassBuilder().addRune('#'.code).build())
        Assertions.assertThat<CharClassScanInfo?>(scanInfo).isInstanceOf(AsciiSmallSet::class.java)

        val prefilter =
            RejectPrefilter.create(RequiredCharClass(scanInfo))
        Assertions.assertThat<RejectPrefilter?>(prefilter).isInstanceOf(RejectPrefilter.CharClass::class.java)
        Assertions.assertThat((prefilter as RejectPrefilter.CharClass).singleAscii).isEqualTo('#'.code)

        val options = EnginePathOptions.allEnabled()
        val hit = "x = 1  # noqa"
        val miss = "x = 1  no comment"

        // String.
        Assertions.assertThat(prefilter.canReject(hit, null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(miss, null, 0, options)).isTrue()
        // searchFrom is respected: the only '#' is behind it.
        Assertions.assertThat(prefilter.canReject(hit, null, hit.indexOf('#') + 1, options)).isTrue()

        // UTF-8.
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner(hit), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner(miss), 0, options)).isTrue()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner(hit), hit.indexOf('#') + 1, options)).isTrue()

        // A '#' beyond the BMP-adjacent boundary is still found, and a non-ASCII
        // haystack does not confuse the character search.
        val wide = "\u00e9\u4e2d\ud83d\ude00#"
        Assertions.assertThat(prefilter.canReject(wide, null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner(wide), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("\u00e9\u4e2d\ud83d\ude00", null, 0, options)).isTrue()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("\u00e9\u4e2d\ud83d\ude00"), 0, options))
            .isTrue()
    }

    @Test
    @Tag("work-counter")
    fun singleAsciiCharClassUtf8RejectStopsAtEarlyHitWithoutDecodingPrefix() {
        val scanInfo =
            CharClassScanInfo.fromCharClass(CharClassBuilder().addRune('#'.code).build())
        val prefilter =
            RejectPrefilter.create(RequiredCharClass(scanInfo))
        val options = EnginePathOptions.allEnabled()

        val earlyHit: Utf8InputScanner = utf8Scanner("\u4E00#" + "\u4E00".repeat(10000))
        val work = WorkCounter.countForTesting(Runnable { prefilter.canReject(null, earlyHit, 0, options) })
        Assertions.assertThat(work).isLessThan(16L)
    }

    @Test
    fun multiCharacterAsciiClassKeepsTheClassScan() {
        val scanInfo =
            CharClassScanInfo.fromCharClass(CharClassBuilder().addRune('#'.code).addRune('%'.code).build())
        Assertions.assertThat<CharClassScanInfo?>(scanInfo).isInstanceOf(AsciiSmallSet::class.java)

        val prefilter =
            RejectPrefilter.create(RequiredCharClass(scanInfo))
        Assertions.assertThat((prefilter as RejectPrefilter.CharClass).singleAscii).isEqualTo(-1)

        val options = EnginePathOptions.allEnabled()
        Assertions.assertThat(prefilter.canReject("a # b", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("a % b", null, 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject("a + b", null, 0, options)).isTrue()
    }

    @Test
    fun nonAsciiCharClassRejectsUtf8OnlyFromStart() {
        val scanInfo =
            CharClassScanInfo.fromCharClass(
                CharClassBuilder().addRune(']'.code).addRune('\uFF3D'.code).build()
            )
        val prefilter =
            RejectPrefilter.create(RequiredCharClass(scanInfo))
        val options = EnginePathOptions.allEnabled()

        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("no brackets here"), 0, options)).isTrue()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("has \uFF3D here"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("has ] here"), 0, options)).isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("\u00e9\u4E00 ascii then ] late"), 0, options))
            .isFalse()
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("\u00e9\u4E00\uFF3B no closing"), 0, options))
            .isTrue()
        // Each member on either side of the first non-ASCII byte, including inputs long enough to
        // reach the word-at-a-time scans.
        for (prefix in intArrayOf(0, 7, 8, 9, 300)) {
            val ascii = "x".repeat(prefix)
            Assertions.assertThat(prefilter.canReject(null, utf8Scanner(ascii), 0, options)).isTrue()
            Assertions.assertThat(prefilter.canReject(null, utf8Scanner(ascii + "]"), 0, options)).isFalse()
            Assertions.assertThat(prefilter.canReject(null, utf8Scanner(ascii + "\u4E00" + ascii), 0, options))
                .isTrue()
            Assertions.assertThat(prefilter.canReject(null, utf8Scanner(ascii + "]\u4E00"), 0, options)).isFalse()
            Assertions.assertThat(prefilter.canReject(null, utf8Scanner(ascii + "\u4E00" + ascii + "]"), 0, options))
                .isFalse()
            Assertions.assertThat(prefilter.canReject(null, utf8Scanner(ascii + "\uFF3D" + ascii), 0, options))
                .isFalse()
            Assertions.assertThat(
                prefilter.canReject(
                    null, utf8Scanner(ascii + "\u4E00" + ascii + "\uFF3D"), 0, options
                )
            )
                .isFalse()
        }
        // Non-ASCII UTF-8 class scans have no memo and use scalar decoding, so they only run once from
        // index 0.
        Assertions.assertThat(prefilter.canReject(null, utf8Scanner("] tail without brackets"), 2, options))
            .isFalse()
    }

    @Test
    @Tag("work-counter")
    fun mixedPairUtf8RejectStopsAtEarlyNonAsciiMember() {
        val scanInfo =
            CharClassScanInfo.fromCharClass(
                CharClassBuilder().addRune(']'.code).addRune('\uFF3D'.code).build()
            )
        val prefilter =
            RejectPrefilter.create(RequiredCharClass(scanInfo))
        val options = EnginePathOptions.allEnabled()

        // When the non-ASCII member appears near the start of a long non-ASCII input that lacks the
        // ASCII member, the single-pass mixed-pair scan stops at that early member instead of scanning
        // the entire input for the absent ASCII member first.
        val earlyFullWidth: Utf8InputScanner = utf8Scanner("\u4E00\uFF3D" + "\u4E00".repeat(10000))
        val work =
            WorkCounter.countForTesting(Runnable { prefilter.canReject(null, earlyFullWidth, 0, options) })
        Assertions.assertThat(work).isLessThan(64L)
    }

    @Test
    fun twoMemberSmallSetRejectsOnlyFromInputStart() {
        val scanInfo =
            CharClassScanInfo.fromCharClass(
                CharClassBuilder().addRune(']'.code).addRune('\uFF3D'.code).build()
            )
        val prefilter =
            RejectPrefilter.create(RequiredCharClass(scanInfo))
        val options = EnginePathOptions.allEnabled()

        // From the start of the input, each member is found on either side of the near-window edge
        // and at the end of the input.
        for (member in charArrayOf(']', '\uFF3D')) {
            for (index in intArrayOf(0, 14, 78, 79, 80, 81, 200)) {
                val text = "x".repeat(index) + member + "x".repeat(20)
                Assertions.assertThat(prefilter.canReject(text, null, 0, options))
                    .`as`("member %s at %d", member, index)
                    .isFalse()
            }
            val atEnd = "x".repeat(300) + member
            Assertions.assertThat(prefilter.canReject(atEnd, null, 0, options))
                .`as`("member %s at end", member)
                .isFalse()
        }
        for (length in intArrayOf(0, 1, 79, 80, 81, 300)) {
            val text = "x".repeat(length)
            Assertions.assertThat(prefilter.canReject(text, null, 0, options))
                .`as`("no member, length %d", length)
                .isTrue()
        }

        // Later find() positions are left to the start accelerator and DFA, even when no member
        // remains.
        val text = "x".repeat(40) + "]" + "x".repeat(40)
        for (searchFrom in 1..text.length) {
            Assertions.assertThat(prefilter.canReject(text, null, searchFrom, options))
                .`as`("from %d", searchFrom)
                .isFalse()
        }
    }

    companion object {
        private fun utf8Scanner(text: String): Utf8InputScanner {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            return Utf8InputScanner(bytes, 0, bytes.size)
        }
    }
}
