// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.assertj.core.api.iterable.ThrowingExtractor
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.safere.MultiAnchorCompiler.AsciiWidthRange
import org.safere.MultiAnchorDescriptor.RejectPlan
import org.safere.MultiAnchorDescriptor.RejectPlan.RequiredCharClass
import org.safere.MultiAnchorDescriptor.RejectPlan.RequiredLiteral
import org.safere.MultiAnchorDescriptor.StartPlan
import org.safere.Pattern.EndAnchoredCharClassInfo
import org.safere.Pattern.SuffixInfo
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.function.Consumer
import java.util.function.Predicate
import java.util.stream.Stream
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.math.max

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
@Isolated
internal class MultiAnchorCompilerTest {
    @Test
    fun cachedWidthsMatchTheStandaloneWalkerAcrossParsedAndSimplifiedTrees() {
        val visited = EnumSet.noneOf<RegexpOp?>(RegexpOp::class.java)
        for (regex in mutableListOf<String>(
            "hello.*world",
            "foo.*bar.*baz",
            "(?s)foo.*bar.*baz",
            "^foo.*bar$",
            "\\bfoo.*bar",
            "(?i)foo.*bar",
            "[0-9]{4}-[0-9]{2}-target",
            "([ab]{0,3})needle",
            "(?:a|bbb)?needle",
            "[é中]needle",
            "😀needle",
            "a{0,20}needle",
            "a*needle",
            "a+needle",
            ".needle",
            "(?s).needle",
            "[^\\s\\S]needle",
            "(?:)needle",
            "\\Xneedle"
        )) {
            val parsed = Parser.parse(regex, Pattern.toParseFlags(0))
            for (tree in java.util.List.of<Regexp>(parsed, Simplifier.simplify(parsed))) {
                val pending = ArrayDeque<Regexp>()
                pending.push(tree)
                while (!pending.isEmpty()) {
                    val node = pending.pop()
                    visited.add(node.op)
                    Assertions.assertThat<AsciiWidthRange?>(MultiAnchorCompiler.analyze(node).width)
                        .`as`("%s: %s", regex, node.op)
                        .usingRecursiveComparison()
                        .isEqualTo(MultiAnchorCompiler.computeAsciiWidthRange(node))
                    if (node.subs != null) {
                        for (child in node.subs) pending.push(child)
                    }
                }
            }
        }
        // Full and empty classes simplify to these operators, so the unknown-width cases are reachable.
        Assertions.assertThat<RegexpOp?>(visited).contains(RegexpOp.ANY_CHAR, RegexpOp.NO_MATCH)
    }

    @Test
    fun nullAstReturnsNull() {
        Assertions.assertThat<MultiAnchorDescriptor?>(MultiAnchorCompiler.compile(null, 0)).isNull()
        Assertions.assertThat<StartPlan?>(MultiAnchorCompiler.extractStartPlan(null))
            .isInstanceOf(StartPlan.None::class.java)
        Assertions.assertThat<RejectPlan?>(MultiAnchorCompiler.extractRejectPlan(null, 0, null, false))
            .isInstanceOf(RejectPlan.None::class.java)
    }

    @Test
    fun simplePrefixCompiledCorrectly() {
        val ast = Parser.parse("hello.*world", Pattern.toParseFlags(0))
        val actual = MultiAnchorCompiler.compile(ast, 0)!!

        Assertions.assertThat<StartPlan?>(actual.startPlan())
            .usingRecursiveComparison()
            .isEqualTo(StartPlan.Literal("hello", false))
        Assertions.assertThat<RejectPlan?>(actual.rejectPlan())
            .usingRecursiveComparison()
            .isEqualTo(RequiredLiteral("world"))
    }

    @Test
    fun multiAnchorPatternPrefersTheRarestRequiredLiteral() {
        val ast = Parser.parse("foo.*bar.*baz", Pattern.toParseFlags(0))
        val actual = MultiAnchorCompiler.compile(ast, 0)!!

        Assertions.assertThat<StartPlan?>(actual.startPlan())
            .usingRecursiveComparison()
            .isEqualTo(StartPlan.Literal("foo", false))
        Assertions.assertThat<RejectPlan?>(actual.rejectPlan())
            .usingRecursiveComparison()
            .isEqualTo(RequiredLiteral("baz"))
    }

    @Test
    fun dotallDoesNotChangeTheExtractedPlans() {
        val ast = Parser.parse("(?s)foo.*bar.*baz", Pattern.toParseFlags(0))
        val actual = MultiAnchorCompiler.compile(ast, 0)!!

        Assertions.assertThat<StartPlan?>(actual.startPlan())
            .usingRecursiveComparison()
            .isEqualTo(StartPlan.Literal("foo", false))
        Assertions.assertThat<RejectPlan?>(actual.rejectPlan())
            .usingRecursiveComparison()
            .isEqualTo(RequiredLiteral("baz"))
    }

    @Test
    fun boundaryAnchoredPatternStructure() {
        val textBoundaryAst = Parser.parse("^foo.*bar$", Pattern.toParseFlags(0))
        val actualText = MultiAnchorCompiler.compile(textBoundaryAst, 0)!!

        Assertions.assertThat<StartPlan?>(actualText.startPlan())
            .usingRecursiveComparison()
            .isEqualTo(StartPlan.Literal("foo", false))
        Assertions.assertThat<RejectPlan?>(actualText.rejectPlan())
            .usingRecursiveComparison()
            .isEqualTo(
                RejectPlan.EndAnchoredSuffix(SuffixInfo("bar", true, false, false))
            )
        Assertions.assertThat(actualText.anchoredPrefix()).isEqualTo("foo")
        Assertions.assertThat<CharClassScanInfo?>(actualText.anchoredCharClassPrefix())
            .usingRecursiveComparison()
            .isEqualTo(CharClassScanInfo.fromCharClass(CharClassBuilder().addRune('f'.code).build()))

        val wordBoundaryAst = Parser.parse("\\bfoo.*bar", Pattern.toParseFlags(0))
        val actualWord = MultiAnchorCompiler.compile(wordBoundaryAst, 0)!!

        Assertions.assertThat<StartPlan?>(actualWord.startPlan())
            .usingRecursiveComparison()
            .isEqualTo(StartPlan.Literal("foo", false))
        Assertions.assertThat<RejectPlan?>(actualWord.rejectPlan())
            .usingRecursiveComparison()
            .isEqualTo(RequiredLiteral("bar"))
        Assertions.assertThat(actualWord.anchoredPrefix()).isNull()
    }

    @Test
    fun caseFoldedAnchorsStructure() {
        val ast = Parser.parse("(?i)foo.*bar", Pattern.toParseFlags(0))
        val actual = MultiAnchorCompiler.compile(ast, 0)!!

        Assertions.assertThat<StartPlan?>(actual.startPlan())
            .usingRecursiveComparison()
            .isEqualTo(StartPlan.Literal("foo", true))
        Assertions.assertThat<RejectPlan?>(actual.rejectPlan())
            .usingRecursiveComparison()
            .isEqualTo(RequiredLiteral("bar"))
    }

    @Test
    fun unicodeFoldClassCardinalityPreservesEveryEligiblePrefix() {
        // Include every code point, including members reached only through inverse casing links.
        // This pins the cardinality bound against both the JDK's casing data and SafeRE's fold table.
        var largestClosure = 0
        for (cp in 0..Character.MAX_CODE_POINT) {
            val builder = CharClassBuilder()
            UnicodeCaseFolding.addUnicodeFoldedRange(builder, cp, cp)
            val closure = builder.build()
            largestClosure = max(largestClosure, closure.numRunes())

            val representative = closure.lo(0)
            if (Inst.simpleFold(representative) == representative) {
                continue
            }
            val width = Character.toString(representative).toByteArray(StandardCharsets.UTF_8).size
            var sameWidth = true
            for (range in 0..<closure.numRanges()) {
                if (Character.toString(closure.lo(range)).toByteArray(StandardCharsets.UTF_8).size != width
                    || Character.toString(closure.hi(range)).toByteArray(StandardCharsets.UTF_8).size != width
                ) {
                    sameWidth = false
                    break
                }
            }
            if (sameWidth) {
                val ast = Regexp(RegexpOp.CHAR_CLASS, 0)
                ast.charClass = closure
                val prefix =
                    MultiAnchorCompiler.extractUnicodeFoldedPrefix(ast)
                Assertions.assertThat(prefix.prefix).`as`("fold prefix for %s", closure).isNotNull()
                Assertions.assertThat(prefix.foldCase).`as`("fold prefix for %s", closure).isTrue()
            }
        }
        Assertions.assertThat(largestClosure).isEqualTo(4)
    }

    @Test
    fun fixedOffsetLiteralExtracted() {
        val ast = Parser.parse("[0-9]{4}-[0-9]{2}-target", Pattern.toParseFlags(0))
        val start = MultiAnchorCompiler.extractStartPlan(ast)

        Assertions.assertThat<StartPlan?>(start).isInstanceOf(StartPlan.FixedOffset::class.java)
        val fo =
            start as StartPlan.FixedOffset
        Assertions.assertThat<Pattern.FixedOffsetLiteral?>(fo.fol).isNotNull()
        Assertions.assertThat(fo.fol!!.literal()).isEqualTo("-target")
        Assertions.assertThat(fo.fol!!.minOffset()).isEqualTo(7)
        Assertions.assertThat(fo.fol!!.maxOffset()).isEqualTo(7)
    }

    @ParameterizedTest
    @MethodSource("boundedWidthPrefixes")
    fun fixedOffsetsPreserveDiscreteWidthBudgets(prefix: String?, min: Int, max: Int, offsets: IntArray?) {
        val ast = Parser.parse(prefix + "needle", Pattern.toParseFlags(0))
        val standalone =
            MultiAnchorCompiler.extractFixedOffsetLiteral(ast)!!
        Assertions.assertThat<MultiAnchorCompiler.FixedOffsetLiteral?>(standalone).isNotNull()
        Assertions.assertThat(standalone.literal).isEqualTo("needle")
        Assertions.assertThat(standalone.minOffset).isEqualTo(min)
        Assertions.assertThat(standalone.maxOffset).isEqualTo(max)
        Assertions.assertThat(standalone.discreteOffsets).isEqualTo(offsets)
        Assertions.assertThat<MultiAnchorCompiler.FixedOffsetLiteral?>(MultiAnchorCompiler.analyze(ast).start.fixedOffsetLiteral)
            .usingRecursiveComparison()
            .isEqualTo(standalone)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["[0-9]\\Xneedle", "[0-9]\\b{g}needle", "[0-9][^\\s\\S]needle", "[0-9](?i:a)needle", "[0-9]😀needle"
        ]
    )
    fun unsupportedWidthStopsFixedOffsetAnalysis(regex: String?) {
        val ast = Parser.parse(regex, Pattern.toParseFlags(0))
        Assertions.assertThat<MultiAnchorCompiler.FixedOffsetLiteral?>(MultiAnchorCompiler.extractFixedOffsetLiteral(ast))
            .isNull()
        Assertions.assertThat<MultiAnchorCompiler.FixedOffsetLiteral?>(MultiAnchorCompiler.analyze(ast).start.fixedOffsetLiteral)
            .isNull()
    }

    @Test
    fun zeroWidthRepetitionsDeduplicateOffsets() {
        val emptyRepeat = Regexp.repeat(Regexp.emptyMatch(0), 0, 0, 8)
        val ast =
            Regexp.concat(
                java.util.List.of<Regexp?>(
                    emptyRepeat,
                    Regexp.repeat(Regexp.literal('x'.code, 0), 0, 2, 2),
                    Regexp.literalString("needle".codePoints().toArray(), 0)
                ),
                0
            )
        Assertions.assertThat(MultiAnchorCompiler.extractFixedOffsetLiteral(ast)!!.discreteOffsets)
            .containsExactly(2)
        Assertions.assertThat<MultiAnchorCompiler.FixedOffsetLiteral?>(MultiAnchorCompiler.analyze(ast).start.fixedOffsetLiteral)
            .usingRecursiveComparison()
            .isEqualTo(MultiAnchorCompiler.extractFixedOffsetLiteral(ast))
    }

    @ParameterizedTest
    @MethodSource("zeroWidthRepeatBounds")
    fun zeroWidthRepeatsHaveOneDiscreteWidth(min: Int, max: Int) {
        val expected = MultiAnchorCompiler.analyze(Regexp.emptyMatch(0)).width
        for (operand in java.util.List.of<Regexp?>(
            Regexp.emptyMatch(0),
            Regexp.literalString(IntArray(0), 0),
            Regexp.capture(Regexp.emptyMatch(0), 0, 1, null),
            Regexp.rawQuantifier(RegexpOp.QUEST, Regexp.emptyMatch(0), 0)
        )) {
            val repeat = Regexp.repeat(operand, 0, min, max)
            Assertions.assertThat<AsciiWidthRange?>(MultiAnchorCompiler.analyze(repeat).width)
                .`as`("cached width for %s{%s,%s}", operand.op, min, max)
                .usingRecursiveComparison()
                .isEqualTo(expected)
            Assertions.assertThat<AsciiWidthRange?>(MultiAnchorCompiler.computeAsciiWidthRange(repeat))
                .`as`("standalone width for %s{%s,%s}", operand.op, min, max)
                .usingRecursiveComparison()
                .isEqualTo(expected)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["[0-9]{2}needle.*tail", "[ab]{0,4}[cd]{0,3}needle.*tail"])
    fun rootCaptureWrappersPreserveStartAndRejectPlans(regex: String?) {
        val expected =
            MultiAnchorCompiler.compile(Parser.parse(regex, Pattern.toParseFlags(0)), 0)
        for (wrapped in java.util.List.of<String?>("(" + regex + ")", "(?:(" + regex + "))")) {
            Assertions.assertThat<MultiAnchorDescriptor?>(
                MultiAnchorCompiler.compile(
                    Parser.parse(
                        wrapped,
                        Pattern.toParseFlags(0)
                    ), 0
                )
            )
                .usingRecursiveComparison()
                .isEqualTo(expected)
        }
    }

    @Test
    fun overflowingSuffixDoesNotWrapFixedOffsets() {
        val large = Regexp.repeat(Regexp.literal('x'.code, 0), 0, Int.MAX_VALUE, Int.MAX_VALUE)
        val ast =
            Regexp.concat(
                java.util.List.of<Regexp?>(
                    large,
                    Regexp.literal('y'.code, 0),
                    Regexp.literalString("needle".codePoints().toArray(), 0)
                ),
                0
            )
        // The literal run starts at a valid offset, but consuming it would overflow the next offset.
        val literal =
            MultiAnchorCompiler.extractFixedOffsetLiteral(ast)!!
        Assertions.assertThat(literal.minOffset).isEqualTo(Int.MAX_VALUE)
        Assertions.assertThat(literal.maxOffset).isEqualTo(Int.MAX_VALUE)
        Assertions.assertThat<MultiAnchorCompiler.FixedOffsetLiteral?>(MultiAnchorCompiler.analyze(ast).start.fixedOffsetLiteral)
            .usingRecursiveComparison()
            .isEqualTo(literal)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["^(?:prefixOne|prefixTwo)", "\\b(?:prefixOne|prefixTwo)", "(?:prefixOne|prefixTwo)$", "(?:prefixOne|prefixTwo)(capture)"
        ]
    )
    fun capturesAndAssertionsPreventFactoringAcrossTheWholeTree(regex: String?) {
        val ast = Parser.parse(regex, Pattern.toParseFlags(0))
        Assertions.assertThat<Regexp?>(MultiAnchorCompiler.factorAlternations(ast)).isSameAs(ast)
    }

    @Test
    fun rejectDescriptorRequiredLiteral() {
        val ast = Parser.parse(".*(important_keyword).*", Pattern.toParseFlags(0))
        val reject =
            MultiAnchorCompiler.extractRejectPlan(ast, 0, null, false)

        Assertions.assertThat<RejectPlan?>(reject).isNotNull()
        var lit: RequiredLiteral? = null
        if (reject is RequiredLiteral) {
            lit = reject
        } else if (reject is RejectPlan.Composite) {
            for (p in reject.plans!!) {
                if (p is RequiredLiteral) {
                    lit = p
                    break
                }
            }
        }
        Assertions.assertThat<RequiredLiteral?>(lit).isNotNull()
        Assertions.assertThat(lit!!.literal).isEqualTo("important_keyword")
    }

    @Test
    fun rejectDescriptorEndAnchoredSuffix() {
        val ast = Parser.parse(".*\\.json$", Pattern.toParseFlags(0))
        val reject =
            MultiAnchorCompiler.extractRejectPlan(ast, 0, null, false)

        Assertions.assertThat<RejectPlan?>(reject).isNotNull()
        var s: RejectPlan.EndAnchoredSuffix? = null
        if (reject is RejectPlan.EndAnchoredSuffix) {
            s = reject
        } else if (reject is RejectPlan.Composite) {
            for (p in reject.plans!!) {
                if (p is RejectPlan.EndAnchoredSuffix) {
                    s = p
                    break
                }
            }
        }
        Assertions.assertThat<RejectPlan.EndAnchoredSuffix?>(s).isNotNull()
        Assertions.assertThat<SuffixInfo?>(s!!.suffix).isNotNull()
        Assertions.assertThat(s.suffix!!.suffix).isEqualTo(".json")
    }

    @Test
    fun rejectDescriptorRequiredCharClass() {
        val ast = Parser.parse(".*\\d+.*", Pattern.toParseFlags(0))
        val reject =
            MultiAnchorCompiler.extractRejectPlan(ast, 0, null, false)

        Assertions.assertThat<RejectPlan?>(reject).isInstanceOf(RequiredCharClass::class.java)
    }

    @Test
    fun rejectDescriptorDisjointRequiredLiterals() {
        val ast = Parser.parse("(apple.*|banana.*|cherry.*)", Pattern.toParseFlags(0))
        val reject =
            MultiAnchorCompiler.extractRejectPlan(ast, 0, null, false)

        Assertions.assertThat<RejectPlan?>(reject).isInstanceOf(RejectPlan.DisjointLiterals::class.java)
        val d =
            reject as RejectPlan.DisjointLiterals
        Assertions.assertThat<String?>(d.literals).containsExactly("apple", "banana", "cherry")
    }

    @Test
    @Tag("work-counter")
    fun nestedConcatenationAnalysisScalesLinearly() {
        val smaller: Regexp = nestedConcatenation(1000)
        val larger: Regexp = nestedConcatenation(2000)
        MultiAnchorCompiler.analyze(smaller)
        MultiAnchorCompiler.analyze(larger)

        val smallerWork: Long = analysisWork(smaller)
        val largerWork: Long = analysisWork(larger)

        Assertions.assertThat(smallerWork).isPositive()
        Assertions.assertThat(largerWork)
            .withFailMessage("smallerWork=%s largerWork=%s", smallerWork, largerWork)
            .isLessThan(smallerWork * 3)
    }

    @Test
    @Tag("work-counter")
    fun nestedAlternationAnalysisScalesLinearly() {
        val smaller: Regexp = nestedAlternation(1000)
        val larger: Regexp = nestedAlternation(2000)
        MultiAnchorCompiler.analyze(smaller)
        MultiAnchorCompiler.analyze(larger)

        val smallerWork: Long = analysisWork(smaller)
        val largerWork: Long = analysisWork(larger)

        Assertions.assertThat(smallerWork).isPositive()
        Assertions.assertThat(largerWork)
            .withFailMessage("smallerWork=%s largerWork=%s", smallerWork, largerWork)
            .isLessThan(smallerWork * 3)
    }

    @Test
    @Tag("work-counter")
    fun nestedRequiredLiteralAnalysisScalesLinearly() {
        val smaller: Regexp = nestedRequiredLiteral(8000)
        val larger: Regexp = nestedRequiredLiteral(16000)
        MultiAnchorCompiler.analyze(smaller)
        MultiAnchorCompiler.analyze(larger)

        val smallerWork: Long = analysisWork(smaller)
        val largerWork: Long = analysisWork(larger)

        Assertions.assertThat(smallerWork).isPositive()
        Assertions.assertThat(largerWork)
            .withFailMessage("smallerWork=%s largerWork=%s", smallerWork, largerWork)
            .isLessThan(smallerWork * 3)
    }

    @Test
    fun endRejectPlansRetainUnixLinesMode() {
        val suffix =
            Pattern.compile(".*needle$", Pattern.UNIX_LINES).rejectPlan()
        val characterClass =
            Pattern.compile(".*[0-9]$", Pattern.UNIX_LINES).rejectPlan()

        Assertions.assertThat<SuffixInfo?>(endAnchoredSuffixes(suffix))
            .singleElement()
            .extracting<Boolean?>(SuffixInfo::unixLines)
            .isEqualTo(true)
        Assertions.assertThat<EndAnchoredCharClassInfo?>(endAnchoredCharacterClasses(characterClass))
            .singleElement()
            .extracting<Boolean?>(EndAnchoredCharClassInfo::unixLines)
            .isEqualTo(true)
    }

    @Test
    fun factorAlternationsWithSurroundingPrefixCoalescesLiteral() {
        val p =
            Pattern.compile(
                "/(?:api/v1/checkout|api/v1/payment|api/v1/orders)/[0-9a-f]{8} status=[0-9]+"
            )
        Assertions.assertThat<MultiAnchorDescriptor?>(p.multiAnchor()).isNotNull()
        Assertions.assertThat<StartPlan?>(p.multiAnchor().startPlan()).isInstanceOf(StartPlan.Literal::class.java)
        val literal = p.multiAnchor().startPlan() as StartPlan.Literal
        Assertions.assertThat(literal.prefix).isEqualTo("/api/v1/")
    }

    @Test
    fun factorAlternationsStandalonePrefixExtractsCommonPrefix() {
        // No literal follows the alternation, so the factored prefix is the only candidate and
        // this asserts the factoring itself rather than the outcome of a later comparison.
        val p = Pattern.compile("(?:https://api|https://stage|https://prod)[a-z0-9.]+")
        Assertions.assertThat<MultiAnchorDescriptor?>(p.multiAnchor()).isNotNull()
        Assertions.assertThat<StartPlan?>(p.multiAnchor().startPlan()).isInstanceOf(StartPlan.Literal::class.java)
        val literal = p.multiAnchor().startPlan() as StartPlan.Literal
        Assertions.assertThat(literal.prefix).isEqualTo("https://")
    }

    @Test
    fun factoredPrefixLosesToAMoreSelectiveFixedOffsetLiteral() {
        // The factored prefix is `https://`, but `.example.com/` is the rarer literal and wins.
        // Before the prefix-length gate was removed the comparison never ran, because the gate
        // only admitted leading prefixes of two characters or fewer.
        val p =
            Pattern.compile("(?:https://api|https://stage|https://prod)\\.example\\.com/[a-z0-9]+")
        Assertions.assertThat<MultiAnchorDescriptor?>(p.multiAnchor()).isNotNull()
        Assertions.assertThat<StartPlan?>(p.multiAnchor().startPlan()).isInstanceOf(StartPlan.FixedOffset::class.java)
        val fixedOffset = p.multiAnchor().startPlan() as StartPlan.FixedOffset
        Assertions.assertThat(fixedOffset.fol!!.literal()).isEqualTo(".example.com/")
    }

    @Test
    fun selectedFixedOffsetLiteralPreservesStringAndUtf8FindBounds() {
        val regex = "(?:https://api|https://stage|https://prod)\\.example\\.com/[a-z0-9]+"
        val text = "é https://api.example.com/x! https://prod.example.com/y"
        val pattern = Pattern.compile(regex)
        Assertions.assertThat<StartPlan?>(pattern.multiAnchor().startPlan())
            .isInstanceOf(StartPlan.FixedOffset::class.java)

        val expected = java.util.regex.Pattern.compile(regex).matcher(text)
        val stringMatcher = pattern.matcher(text)
        val utf8Matcher = pattern.matcher(Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8)))
        while (expected.find()) {
            Assertions.assertThat(stringMatcher.find()).isTrue()
            Assertions.assertThat(stringMatcher.start()).isEqualTo(expected.start())
            Assertions.assertThat(stringMatcher.end()).isEqualTo(expected.end())
            Assertions.assertThat(utf8Matcher.find()).isTrue()
            Assertions.assertThat(utf8Matcher.start())
                .isEqualTo(text.substring(0, expected.start()).toByteArray(StandardCharsets.UTF_8).size)
            Assertions.assertThat(utf8Matcher.end())
                .isEqualTo(text.substring(0, expected.end()).toByteArray(StandardCharsets.UTF_8).size)
        }
        Assertions.assertThat(stringMatcher.find()).isFalse()
        Assertions.assertThat(utf8Matcher.find()).isFalse()
    }

    @Test
    fun alternationFactoringIsStackSafeForDeepQuantifiers() {
        var nested = Regexp.literal('a'.code, 0)
        for (index in 0..100000 - 1) {
            val op = if (index % 2 == 0) RegexpOp.QUEST else RegexpOp.PLUS
            nested = Regexp.rawQuantifier(op, nested, 0)
        }

        val input = nested
        Assertions.assertThatCode(ThrowingCallable { MultiAnchorCompiler.factorAlternations(input) })
            .doesNotThrowAnyException()
    }

    @Test
    fun homogeneousGapExtractionIsStackSafeForDeepQuantifiers() {
        Assertions.assertThatCode(ThrowingCallable { Pattern.compile(deepHomogeneousGap("[ab]", 5000)) })
            .doesNotThrowAnyException()
        Assertions.assertThatCode(ThrowingCallable { Pattern.compile(deepHomogeneousGap(".", 5000)) })
            .doesNotThrowAnyException()
    }

    @Test
    fun poisonousSingleCharacterPrefixGatedFromLiteralStartPlan() {
        // Single space is poisonous; should not be emitted as StartPlan.Literal
        val spacePattern = Pattern.compile(" [0-9]+")
        Assertions.assertThat<StartPlan?>(spacePattern.startPlan())
            .isNotInstanceOf(StartPlan.Literal::class.java)

        // Single 'e' is high-frequency / poisonous; should not be emitted as StartPlan.Literal
        val ePattern = Pattern.compile("e[0-9]+")
        Assertions.assertThat<StartPlan?>(ePattern.startPlan()).isNotInstanceOf(StartPlan.Literal::class.java)

        // Single-character class matching space is unselective / poisonous
        val spaceClassPattern = Pattern.compile("[ ]\\d+")
        Assertions.assertThat<StartPlan?>(spaceClassPattern.startPlan())
            .isInstanceOfSatisfying<StartPlan.FixedOffsetClass?>(
                StartPlan.FixedOffsetClass::class.java,
                Consumer { plan: StartPlan.FixedOffsetClass? ->
                    Assertions.assertThat(plan!!.leadingClass!!.isSelective).isFalse()
                    Assertions.assertThat(plan.offset).isEqualTo(1)
                })
        Assertions.assertThat<StringStartAccelerator?>(spaceClassPattern.stringStartAccelerator()).isNotNull()

        // Multi-character prefix containing space is not poisonous (e.g. "  " or "id: ")
        val multiSpace = Pattern.compile("  [0-9]+")
        Assertions.assertThat<StartPlan?>(multiSpace.startPlan()).isInstanceOf(StartPlan.Literal::class.java)
        Assertions.assertThat((multiSpace.startPlan() as StartPlan.Literal).prefix)
            .isEqualTo("  ")

        val exactUppercase = Pattern.compile("E[0-9]+")
        Assertions.assertThat<StartPlan?>(exactUppercase.startPlan())
            .isInstanceOf(StartPlan.Literal::class.java)
        Assertions.assertThat<StringStartAccelerator?>(exactUppercase.stringStartAccelerator()).isNotNull()
        Assertions.assertThat<Utf8StartAccelerator?>(exactUppercase.utf8StartAccelerator()).isNotNull()

        val foldedCommonLetter = Pattern.compile("(?i:E)[0-9]+")
        Assertions.assertThat<StartPlan?>(foldedCommonLetter.startPlan())
            .isInstanceOf(StartPlan.None::class.java)
        Assertions.assertThat<StringStartAccelerator?>(foldedCommonLetter.stringStartAccelerator()).isNull()
        Assertions.assertThat<Utf8StartAccelerator?>(foldedCommonLetter.utf8StartAccelerator()).isNull()

        val foldedRareLetter = Pattern.compile("(?i:Q)[0-9]+")
        Assertions.assertThat<StartPlan?>(foldedRareLetter.startPlan())
            .isInstanceOf(StartPlan.Literal::class.java)
        Assertions.assertThat<StringStartAccelerator?>(foldedRareLetter.stringStartAccelerator()).isNotNull()
        Assertions.assertThat<Utf8StartAccelerator?>(foldedRareLetter.utf8StartAccelerator()).isNotNull()

        val latin1Singleton = Pattern.compile("é+(?:ab|cd)?")
        Assertions.assertThat<StartPlan?>(latin1Singleton.startPlan())
            .isInstanceOf(StartPlan.CharClass::class.java)
        Assertions.assertThat<StringStartAccelerator?>(latin1Singleton.stringStartAccelerator()).isNotNull()
        Assertions.assertThat<Utf8StartAccelerator?>(latin1Singleton.utf8StartAccelerator()).isNotNull()

        val unicodeSingleton = Pattern.compile("Ā+(?:ab|cd)?")
        Assertions.assertThat<StartPlan?>(unicodeSingleton.startPlan())
            .isInstanceOf(StartPlan.CharClass::class.java)
        Assertions.assertThat<StringStartAccelerator?>(unicodeSingleton.stringStartAccelerator()).isNotNull()
        Assertions.assertThat<Utf8StartAccelerator?>(unicodeSingleton.utf8StartAccelerator()).isNotNull()
    }

    @Test
    fun competitiveSelectivityPrefersFixedOffsetOverPoisonousOrWeakPrefix() {
        // Case 1: Poisonous leading space prefix eclipsed by selective fixed-offset literal
        val p1 = Pattern.compile(" [0-9]{2}404_NOT_FOUND")
        Assertions.assertThat<StartPlan?>(p1.startPlan()).isInstanceOf(StartPlan.FixedOffset::class.java)
        val fo1 =
            p1.startPlan() as StartPlan.FixedOffset
        Assertions.assertThat(fo1.fol!!.literal()).isEqualTo("404_NOT_FOUND")
        Assertions.assertThat(fo1.fol!!.minOffset()).isEqualTo(3)

        // Case 2: Short 2-character weak prefix eclipsed by much more selective fixed-offset literal
        val p2 = Pattern.compile("ab[0-9]{2}ERROR_CRITICAL_PAYLOAD")
        Assertions.assertThat<StartPlan?>(p2.startPlan()).isInstanceOf(StartPlan.FixedOffset::class.java)
        val fo2 =
            p2.startPlan() as StartPlan.FixedOffset
        Assertions.assertThat(fo2.fol!!.literal()).isEqualTo("ERROR_CRITICAL_PAYLOAD")

        // Case 3: Long selective prefix retains priority over fixed-offset literal
        val p3 = Pattern.compile("Content-Type:[0-9]{2}json")
        Assertions.assertThat<StartPlan?>(p3.startPlan()).isInstanceOf(StartPlan.Literal::class.java)
        Assertions.assertThat((p3.startPlan() as StartPlan.Literal).prefix)
            .isEqualTo("Content-Type:")
    }

    @Test
    fun fixedOffsetLiteralSubsumedFromRejectPlan() {
        // Single fixed-offset literal drives startPlan; should not be duplicated in rejectPlan
        val p1 = Pattern.compile("[0-9]{2}404_NOT_FOUND")
        Assertions.assertThat<StartPlan?>(p1.startPlan()).isInstanceOf(StartPlan.FixedOffset::class.java)
        val fo =
            p1.startPlan() as StartPlan.FixedOffset
        Assertions.assertThat(fo.fol!!.literal()).isEqualTo("404_NOT_FOUND")

        // The required literal "404_NOT_FOUND" is subsumed and excluded from rejectPlan
        val reqLits1: Stream<RequiredLiteral?>? =
            rejectPlans(p1.rejectPlan())
                .filter { obj: RejectPlan? -> RequiredLiteral::class.java.isInstance(obj) }
                .map<RequiredLiteral?> { obj: RejectPlan? -> RequiredLiteral::class.java.cast(obj) }
        Assertions.assertThat<RequiredLiteral?>(reqLits1)
            .noneMatch(Predicate { l: RequiredLiteral? -> l!!.literal == "404_NOT_FOUND" })

        // Multiple required literals: fixed-offset literal is excluded, but downstream required literal
        // is preserved
        val p2 = Pattern.compile("[0-9]{2}404_NOT_FOUND.*EXCEPTION_LOG")
        Assertions.assertThat<StartPlan?>(p2.startPlan()).isInstanceOf(StartPlan.FixedOffset::class.java)
        val reqLits2: Stream<RequiredLiteral?>? =
            rejectPlans(p2.rejectPlan())
                .filter { obj: RejectPlan? -> RequiredLiteral::class.java.isInstance(obj) }
                .map<RequiredLiteral?> { obj: RejectPlan? -> RequiredLiteral::class.java.cast(obj) }
        Assertions.assertThat<RequiredLiteral?>(reqLits2)
            .anyMatch(Predicate { l: RequiredLiteral? -> l!!.literal == "EXCEPTION_LOG" })

        // Multiple literals in fixed-offset prefix: all prefix literals subsumed, none leaked to
        // rejectPlan
        val p3 = Pattern.compile("[0-9]{2}____[a-z]zq[a-z]")
        Assertions.assertThat<StartPlan?>(p3.startPlan()).isInstanceOf(StartPlan.FixedOffset::class.java)
        val reqLits3: Stream<RequiredLiteral?>? =
            rejectPlans(p3.rejectPlan())
                .filter { obj: RejectPlan? -> RequiredLiteral::class.java.isInstance(obj) }
                .map<RequiredLiteral?> { obj: RejectPlan? -> RequiredLiteral::class.java.cast(obj) }
        Assertions.assertThat<RequiredLiteral?>(reqLits3).isEmpty()

        val variableWidthBoundary = Pattern.compile("[0-9]{2}____(?:ALPHABET)+")
        val boundaryLiterals: Stream<RequiredLiteral?>? =
            rejectPlans(variableWidthBoundary.rejectPlan())
                .filter { obj: RejectPlan? -> RequiredLiteral::class.java.isInstance(obj) }
                .map<RequiredLiteral?> { obj: RejectPlan? -> RequiredLiteral::class.java.cast(obj) }
        Assertions.assertThat<RequiredLiteral?>(boundaryLiterals)
            .extracting<String?, RuntimeException?>(RequiredLiteral::literal)
            .contains("ALPHABET")

        val repeatedPrefixToken = Pattern.compile("[0-9]{2}zz.*zzRARE")
        val repeatedTokenLiterals: Stream<RequiredLiteral?>? =
            rejectPlans(repeatedPrefixToken.rejectPlan())
                .filter { obj: RejectPlan? -> RequiredLiteral::class.java.isInstance(obj) }
                .map<RequiredLiteral?> { obj: RejectPlan? -> RequiredLiteral::class.java.cast(obj) }
        Assertions.assertThat<RequiredLiteral?>(repeatedTokenLiterals)
            .extracting<String?, RuntimeException?>(RequiredLiteral::literal)
            .contains("zzRARE")
    }

    @Test
    fun leadingExpansionKeepsItsRequiredCharClass() {
        // `\s*# noqa` is driven by the inner literal "# ", but the required class `['#']` is still
        // scanned for. The reject prefilter runs before the match machinery is set up, so it pays for
        // itself even though the accelerator would go on to find the same literal. Dropping it
        // regressed the UTF-8 arm of rebar's 04-ruff-noqa by 1.70x.
        val p =
            Pattern.compile("(\\s*)((?:# [Nn][Oo][Qq][Aa])(?::\\s?(([A-Z]+[0-9]+(?:[,\\s]+)?)+))?)")
        Assertions.assertThat<StartPlan?>(p.startPlan()).isInstanceOf(StartPlan.LeadingExpansion::class.java)
        val le = p.startPlan() as StartPlan.LeadingExpansion
        Assertions.assertThat<StartPlan?>(le.innerPlan).isInstanceOf(StartPlan.Literal::class.java)
        Assertions.assertThat<RejectPlan?>(p.rejectPlan()).isInstanceOf(RequiredCharClass::class.java)
    }

    @Test
    fun leadingExpansionOverFixedOffsetKeepsItsRequiredCharClass() {
        val p = Pattern.compile("\\s+[0-9]{2}404_NOT_FOUND")
        Assertions.assertThat<StartPlan?>(p.startPlan()).isInstanceOf(StartPlan.LeadingExpansion::class.java)
        val le = p.startPlan() as StartPlan.LeadingExpansion
        Assertions.assertThat<StartPlan?>(le.innerPlan).isInstanceOf(StartPlan.FixedOffset::class.java)
        Assertions.assertThat<RejectPlan?>(rejectPlans(p.rejectPlan()))
            .hasAtLeastOneElementOfType(RequiredCharClass::class.java)
    }

    @Test
    fun leadingExpansionWithDownstreamRequiredLiteralPreservesRequiredLiteral() {
        val p = Pattern.compile("\\s*foo.*RARE_KEYWORD")
        Assertions.assertThat<StartPlan?>(p.startPlan()).isInstanceOf(StartPlan.LeadingExpansion::class.java)
        val le = p.startPlan() as StartPlan.LeadingExpansion
        Assertions.assertThat((le.innerPlan as StartPlan.Literal).prefix).isEqualTo("foo")
        Assertions.assertThat<RejectPlan?>(rejectPlans(p.rejectPlan()))
            .filteredOn(Predicate { obj: RejectPlan? -> RequiredLiteral::class.java.isInstance(obj) })
            .extracting<String?, RuntimeException?>(ThrowingExtractor { plan: RejectPlan? -> (plan as RequiredLiteral).literal })
            .contains("RARE_KEYWORD")
    }

    @Test
    fun multiLiteralFallbackClassCompetesWithRequiredCharClass() {
        // `.*\d+.*` has a mandatory `\d` (10 runes) and no start plan of its own.
        val ast = Parser.parse(".*\\d+.*", Pattern.toParseFlags(0))
        val literals = arrayOf<String?>("apple", "banana")

        // A MultiLiteral with no fallback class behaves like any plan without a driving character
        // class: the mandatory `\d` is scanned for.
        val noFallback = StartPlan.MultiLiteral(literals, null)
        Assertions.assertThat<RejectPlan?>(MultiAnchorCompiler.extractRejectPlan(ast, 0, noFallback, false))
            .isInstanceOf(RequiredCharClass::class.java)

        // With a fallback class narrower than `\d`, the MultiLiteral accelerator's own scan is the
        // more selective of the two, so the redundant reject scan is dropped.
        val narrowFallback =
            StartPlan.MultiLiteral(literals, charClassOf("a-c"))
        Assertions.assertThat<RejectPlan?>(MultiAnchorCompiler.extractRejectPlan(ast, 0, narrowFallback, false))
            .isInstanceOf(RejectPlan.None::class.java)

        // With a fallback class wider than `\d`, the reject scan filters better and is kept.
        val wideFallback = StartPlan.MultiLiteral(literals, charClassOf("a-z"))
        Assertions.assertThat<RejectPlan?>(MultiAnchorCompiler.extractRejectPlan(ast, 0, wideFallback, false))
            .isInstanceOf(RequiredCharClass::class.java)
    }

    @Test
    fun requiredCharClassSelectionUsesAsciiMembersAndFallsBackToRuneCount() {
        // Mixed [\[\uFF3B] and [\]\uFF3D] are scored by their ASCII members, beating `.` and `\d`.
        val citation = Pattern.compile(" ?[\\[\uFF3B](?:(?:\\d+\\.){2,}\\d+(?:, )?)+[\\]\uFF3D]")
        Assertions.assertThat<RejectPlan?>(citation.rejectPlan()).isInstanceOf(RequiredCharClass::class.java)
        val citationReject =
            citation.rejectPlan() as RequiredCharClass
        Assertions.assertThat(citationReject.scanInfo!!.ranges).containsExactly(']'.code, ']'.code, 0xFF3D, 0xFF3D)

        // Purely non-ASCII classes fall back to rune count rather than claiming to be rarer than ASCII.
        val nonAsciiPairVsSingleAscii = Pattern.compile("[\uFF3B\uFF3D]+(?:a|aa)+")
        Assertions.assertThat<RejectPlan?>(nonAsciiPairVsSingleAscii.rejectPlan())
            .isInstanceOf(RequiredCharClass::class.java)
        val singleAsciiReject =
            nonAsciiPairVsSingleAscii.rejectPlan() as RequiredCharClass
        Assertions.assertThat(singleAsciiReject.scanInfo!!.ranges).containsExactly('a'.code, 'a'.code)

        // A 1-rune non-ASCII class still beats a 10-rune ASCII digit class by rune count.
        val singleNonAsciiVsDigits = Pattern.compile(".*[\uFF3B]+\\d+")
        Assertions.assertThat<RejectPlan?>(singleNonAsciiVsDigits.rejectPlan())
            .isInstanceOf(RequiredCharClass::class.java)
        val singleNonAsciiReject =
            singleNonAsciiVsDigits.rejectPlan() as RequiredCharClass
        Assertions.assertThat(singleNonAsciiReject.scanInfo!!.ranges).containsExactly(0xFF3B, 0xFF3B)

        // A mixed class with a large non-ASCII range is not scored by its ASCII member, so it does not
        // claim to be rarer than a digit on text in the language it covers.
        val digitsThenCjk = Pattern.compile("\\d+[\\-\u4E00-\u9FFF]+")
        Assertions.assertThat<RejectPlan?>(digitsThenCjk.rejectPlan()).isInstanceOf(RequiredCharClass::class.java)
        val digitsReject =
            digitsThenCjk.rejectPlan() as RequiredCharClass
        Assertions.assertThat(digitsReject.scanInfo!!.ranges).containsExactly('0'.code, '9'.code)
    }

    @Test
    fun optionalCharBeforeCommonClassKeepsRejectPrefilter() {
        // No start accelerator scans for a class this common, so a leading expansion would only drop
        // the reject that makes input without the class cheap.
        for (regex in mutableListOf<String>("-?\\d+", "[+-]?\\d+", "#?[0-9A-Fa-f]{6}")) {
            val pattern = Pattern.compile(regex)
            Assertions.assertThat<StartPlan?>(pattern.startPlan()).`as`(regex)
                .isNotInstanceOf(StartPlan.LeadingExpansion::class.java)
            Assertions.assertThat<RejectPlan?>(pattern.rejectPlan()).`as`(regex).isNotEqualTo(RejectPlan.None.INSTANCE)
        }
        // A selective inner class still gets the expansion.
        Assertions.assertThat<StartPlan?>(Pattern.compile(" ?[\\[\uFF3B]\\d+").startPlan())
            .isInstanceOf(StartPlan.LeadingExpansion::class.java)
    }

    @Test
    fun leadingClassExcludingFewCodePointsGetsLeadingExpansion() {
        // Non-DOTALL `.` excludes the five line terminators (only `\n` with UNIX_LINES), and `[^;]`
        // excludes one character. Walking back over such a class stops at the nearest excluded code
        // point, so the inner literal can drive the start plan.
        for (regex in mutableListOf<String>(
            ".*error: \\w+",
            ".+error: \\w+",
            "[^\\n]*error: \\w+",
            "(?d).*error: \\w+",
            "(.*)(error): (\\w+)",
            ".*?error",
            "[^;]*error"
        )) {
            val pattern = Pattern.compile(regex)
            Assertions.assertThat<StartPlan?>(pattern.startPlan()).`as`(regex)
                .isInstanceOf(StartPlan.LeadingExpansion::class.java)
            val le = pattern.startPlan() as StartPlan.LeadingExpansion
            Assertions.assertThat<StartPlan?>(le.innerPlan).`as`(regex).isInstanceOf(StartPlan.Literal::class.java)
        }
        // DOTALL `.` never stops before the search start, and a class excluding more than a handful
        // of code points is still too large to walk.
        for (regex in mutableListOf<String>("(?s).*error", "[^\\n\\r\\u0085\\u2028\\u2029abcd]*error")) {
            Assertions.assertThat<StartPlan?>(Pattern.compile(regex).startPlan())
                .`as`(regex)
                .isNotInstanceOf(StartPlan.LeadingExpansion::class.java)
        }
    }

    @Test
    fun wideLeadingExpansionSubsumesFindRejectWhenItsLiteralImpliesThePlan() {
        Assertions.assertThat(startSubsumesFindReject(".*error: \\w+")).isTrue()
        Assertions.assertThat(startSubsumesFindReject("[^\\n]*\\bSCRUB:strip_line\\b[^\\n]*\\n?")).isTrue()
        // The reject plan requires a different literal, which still filters on its own.
        Assertions.assertThat(startSubsumesFindReject(".*error:\\[[A-Z]+\\]\\s+code:500\\s+msg:crash")).isFalse()
        // Narrow leading classes and DOTALL keep the reject prefilter.
        Assertions.assertThat(startSubsumesFindReject("[a-z]+error")).isFalse()
        Assertions.assertThat(startSubsumesFindReject("(?s).*error: \\w+")).isFalse()
    }

    @Test
    fun excludedCodePointsOfWideLeadingClasses() {
        Assertions.assertThat(excludedCodePoints(".*error")).containsExactly('\n'.code, '\r'.code, 0x85, 0x2028, 0x2029)
        Assertions.assertThat(excludedCodePoints("(?d).*error")).containsExactly('\n'.code)
        Assertions.assertThat(excludedCodePoints("[^;]*error")).containsExactly(';'.code)
        Assertions.assertThat(excludedCodePoints("\\S+error"))
            .containsExactly('\t'.code, '\n'.code, 0x0B, 0x0C, '\r'.code, ' '.code)
        // A narrow leading class is walked as before and has no excluded set.
        Assertions.assertThat(excludedCodePoints("[a-z]+error")).isNull()
    }

    @Test
    fun surrogateLiteralsDoNotProduceStringFastPaths() {
        for (regex in mutableListOf<String>(
            "\"\\uDBFB",
            "\\uDBFB",
            "\\x{D800}\\x{DC00}",
            "\\uD800(?:\\uDC00)",
            "\\uDC00[a-z]",
            "[a-z]+\\x{D800}\\x{DC00}[a-z]+",
            ".*a\\uDBFB$"
        )) {
            val pattern = Pattern.compile(regex)
            Assertions.assertThat(pattern.matchDescriptor().literalMatch).`as`(regex).isNull()
            Assertions.assertThat(pattern.prefix()).`as`(regex).isNull()
            Assertions.assertThat(pattern.anchoredPrefix()).`as`(regex).isNull()
            Assertions.assertThat<StartPlan?>(pattern.startPlan()).`as`(regex)
                .isNotInstanceOf(StartPlan.Literal::class.java)
            Assertions.assertThat<RejectPlan?>(pattern.rejectPlan())
                .`as`(regex)
                .isNotInstanceOfAny(RequiredLiteral::class.java, RejectPlan.EndAnchoredSuffix::class.java)
        }

        Assertions.assertThat<RejectPlan?>(
            Pattern.compile("(?:a\\x{D800}\\x{DC00}|za\uD800\uDC00|banana)").rejectPlan()
        )
            .isNotInstanceOf(RejectPlan.DisjointLiterals::class.java)
    }

    companion object {
        @JvmStatic
        private fun boundedWidthPrefixes(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of("[ab]{2}", 2, 2, intArrayOf(2)),
                Arguments.of("([ab]{2})", 2, 2, intArrayOf(2)),
                Arguments.of("[ab]?", 0, 1, intArrayOf(0, 1)),
                Arguments.of("[ab]{0,8}", 0, 8, intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8)),
                Arguments.of("[ab]{0,9}", 0, 9, null),
                Arguments.of("[ab]{0,3}[cd]{0,3}", 0, 6, intArrayOf(0, 1, 2, 3, 4, 5, 6)),
                Arguments.of("[ab]{0,4}[cd]{0,3}", 0, 7, null),
                Arguments.of("[0-9].", 2, 2, null),
                Arguments.of(
                    "(?:a|bb|ccc|dddd|eeeee|ffffff|ggggggg|hhhhhhhh)",
                    1,
                    8,
                    intArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
                ),
                Arguments.of("(?:a|bb|ccc|dddd|eeeee|ffffff|ggggggg|hhhhhhhh|iiiiiiiii)", 1, 9, null),
                Arguments.of("[éê]{2}", 2, 2, null)
            )
        }

        @JvmStatic
        private fun zeroWidthRepeatBounds(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of(0, 0),
                Arguments.of(0, 2),
                Arguments.of(2, 2),
                Arguments.of(1, 8),
                Arguments.of(17, 25),
                Arguments.of(Int.MAX_VALUE - 2, Int.MAX_VALUE)
            )
        }

        private fun endAnchoredSuffixes(
            plan: RejectPlan?
        ): MutableList<SuffixInfo?> {
            return rejectPlans(plan)
                .filter { obj: RejectPlan? -> RejectPlan.EndAnchoredSuffix::class.java.isInstance(obj) }
                .map<RejectPlan.EndAnchoredSuffix?> { obj: RejectPlan? ->
                    RejectPlan.EndAnchoredSuffix::class.java.cast(
                        obj
                    )
                }
                .map<SuffixInfo?>(RejectPlan.EndAnchoredSuffix::suffix)
                .toList()
        }

        private fun endAnchoredCharacterClasses(
            plan: RejectPlan?
        ): MutableList<EndAnchoredCharClassInfo?> {
            return rejectPlans(plan)
                .filter { obj: RejectPlan? -> RejectPlan.EndAnchoredCharClass::class.java.isInstance(obj) }
                .map<RejectPlan.EndAnchoredCharClass?> { obj: RejectPlan? ->
                    RejectPlan.EndAnchoredCharClass::class.java.cast(
                        obj
                    )
                }
                .map<EndAnchoredCharClassInfo?>(RejectPlan.EndAnchoredCharClass::charClass)
                .toList()
        }

        private fun rejectPlans(
            plan: RejectPlan?
        ): Stream<RejectPlan?> {
            if (plan is RejectPlan.Composite) {
                return Arrays.stream<RejectPlan?>(plan.plans)
            }
            return Stream.of<RejectPlan?>(plan)
        }

        private fun nestedConcatenation(depth: Int): Regexp {
            var nested = Regexp.literal('z'.code, 0)
            for (index in 0..<depth) {
                nested =
                    Regexp.concat(
                        java.util.List.of<Regexp?>(Regexp.literal('a'.code, 0), Regexp.capture(nested, 0, index + 1, null)), 0
                    )
            }
            return nested
        }

        private fun nestedAlternation(depth: Int): Regexp {
            var nested = Regexp.literal(0x100, 0)
            for (index in 0..<depth) {
                nested =
                    Regexp.alternate(
                        java.util.List.of<Regexp?>(
                            Regexp.literal(0x102 + index * 2, 0), Regexp.capture(nested, 0, index + 1, null)
                        ),
                        0
                    )
            }
            return nested
        }

        private fun nestedRequiredLiteral(size: Int): Regexp {
            var nested = Regexp.literalString("q".repeat(size).codePoints().toArray(), 0)
            for (index in 0..<size) {
                nested =
                    Regexp.concat(
                        java.util.List.of<Regexp?>(
                            Regexp.capture(nested, 0, index + 1, null),
                            Regexp.quest(Regexp.literal('x'.code, 0), 0)
                        ),
                        0
                    )
            }
            return nested
        }

        private fun analysisWork(regexp: Regexp?): Long {
            return WorkCounter.countForTesting(Runnable { MultiAnchorCompiler.analyze(regexp) })
        }

        private fun startSubsumesFindReject(regex: String?): Boolean {
            return Pattern.compile(regex).multiAnchor().startSubsumesFindReject()
        }

        private fun excludedCodePoints(regex: String?): IntArray? {
            val le = Pattern.compile(regex).startPlan() as StartPlan.LeadingExpansion
            return MultiAnchorCompiler.excludedCodePoints(le.leadingClass!!)
        }

        /**
         * Returns the [CharClassScanInfo] a leading `[ranges]` character class compiles to.
         */
        private fun charClassOf(ranges: String?): CharClassScanInfo? {
            val plan = Pattern.compile("[" + ranges + "].*ZZQQ").startPlan()
            Assertions.assertThat<StartPlan?>(plan).isInstanceOf(StartPlan.CharClass::class.java)
            return (plan as StartPlan.CharClass).scanInfo
        }

        private fun deepHomogeneousGap(atom: String?, depth: Int): String {
            return "foo" + "(?:".repeat(depth) + atom + (")?" + atom).repeat(depth) + "bar"
        }
    }
}
