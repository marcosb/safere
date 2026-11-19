// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowingConsumer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.safere.MultiAnchorDescriptor.RejectPlan
import org.safere.MultiAnchorDescriptor.RejectPlan.RequiredCharClass
import org.safere.MultiAnchorDescriptor.RejectPlan.RequiredLiteral
import org.safere.MultiAnchorDescriptor.StartPlan
import org.safere.Pattern.*
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import java.net.URL
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.List
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.function.IntConsumer

/** Tests for package-private [Pattern] metadata.  */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class PatternInternalTest {
    @Test
    fun testOnePassEligibility() {
        val p1 =
            Pattern.compile(
                "\\s*[\\[\\x{FF3B}]\\s*((?:[0-9]+\\.?){3,4}(?:\\s*[,\\x{3001}]\\s*(?:[0-9]+\\.?){3,4})*)\\s*[\\]\\x{FF3D}]"
            )
        Assertions.assertThat<OnePass?>(p1.onePass()).isNull()
        Assertions.assertThat(p1.canOnePassPrimary()).isFalse()

        val p2 = Pattern.compile("\\b[Ff]ormer [Cc][Ee][Oo] ([Aa]lice\\b|\\*\\*[Aa]lice\\*\\*)")
        Assertions.assertThat<OnePass?>(p2.onePass()).isNotNull()
        Assertions.assertThat(p2.canOnePassPrimary()).isTrue()
        Assertions.assertThat(p2.canOnePassFind()).isFalse()
        Assertions.assertThat(p2.canOnePassSubmatch()).isTrue()
    }

    @ParameterizedTest
    @CsvSource("[a-z]+ing, x running y, running", "(a?)+b, xxaab, aab")
    @Throws(Exception::class)
    fun concurrentFirstUseSharesReverseDfaProgram(regex: String?, input: String?, match: String?) {
        val pattern = Pattern.compile(regex)

        val workers = 32
        val start = CyclicBarrier(workers)
        val futures = ArrayList<Future<MutableList<Any?>>>()
        Executors.newFixedThreadPool(workers).use { executor ->
            for (worker in 0..<workers) {
                futures.add(
                    executor.submit<MutableList<Any?>?>(
                        Callable {
                            start.await()
                            val reverseDfaProg = pattern.flatReverseDfaProg()
                            val matcher = pattern.matcher(input)
                            Assertions.assertThat(matcher.find()).isTrue()
                            Assertions.assertThat(matcher.group()).isEqualTo(match)
                            List.of<Any?>(pattern.reverseProg(), reverseDfaProg, pattern.reverseDfaSetup())
                        })
                )
            }
            val shared = futures.getFirst().get()
            for (future in futures) {
                val artifacts = future.get()
                for (i in shared.indices) {
                    Assertions.assertThat<Any?>(artifacts.get(i)).isSameAs(shared.get(i))
                }
            }
        }
    }

    @Test
    @Timeout(10)
    @Throws(Exception::class)
    fun reverseDfaPreparationWaitsForInitializationLock() {
        val pattern = Pattern.compile("((a?)+){2}b")
        val ast = pattern.ast()
        val children = ast.subs
        val sourceReads = AtomicInteger()
        // Observe source-tree traversal without retaining or exposing Compiler.Prepared.
        ast.subs =
            object : AbstractList<Regexp?>() {
                override fun get(index: Int): Regexp? {
                    sourceReads.incrementAndGet()
                    return children.get(index)
                }

                override fun size(): Int {
                    return children.size
                }
            }

        val result = FutureTask<Prog?>(Callable { pattern.flatReverseDfaProg() })
        val worker = Thread(result)
        try {
            synchronized(pattern) {
                worker.start()
                while (worker.isAlive() && worker.getState() != Thread.State.BLOCKED) {
                    Thread.sleep(1)
                }
                Assertions.assertThat<Thread.State?>(worker.getState()).isEqualTo(Thread.State.BLOCKED)
                Assertions.assertThat(sourceReads.get()).isZero()
            }
            Assertions.assertThat<Prog?>(result.get()).isNotNull()
            Assertions.assertThat(sourceReads.get()).isPositive()
        } finally {
            worker.join()
        }
    }

    @Test
    fun onePassIsBuiltOnFirstUseWhenOnlyMatchesCanUseIt() {
        val pattern = Pattern.compile("\\d{4}-\\d{2}-\\d{2}")
        Assertions.assertThat(pattern.onePassAnalyzed()).isFalse()

        Assertions.assertThat(pattern.matcher("on 2026-10-06").find()).isTrue()
        Assertions.assertThat(pattern.onePassAnalyzed()).isFalse()

        Assertions.assertThat(pattern.matcher("2026-10-06").matches()).isTrue()
        Assertions.assertThat(pattern.onePassAnalyzed()).isTrue()
        Assertions.assertThat(pattern.canOnePassPrimary()).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["^[a-z]+@[a-z]+\\.com", "([a-z]+)@([a-z]+)\\.com"])
    fun onePassIsBuiltEagerlyWhenFindCanUseIt(regex: String?) {
        Assertions.assertThat(Pattern.compile(regex).onePassAnalyzed()).isTrue()
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["^(a|)b", "(a|)b", "^(?:a|)b", "^[a-z]+?b", "([a-z]+?)b", "^\\b{g}a+", "\\b{g}(a+)", "(\\X)"
        ]
    )
    fun onePassIsNotBuiltForStaticallyIneligiblePatterns(regex: String?) {
        val pattern = Pattern.compile(regex)
        Assertions.assertThat(pattern.onePassAnalyzed()).isFalse()
        Assertions.assertThat<OnePass?>(pattern.onePass()).isNull()
        Assertions.assertThat(pattern.canOnePassPrimary()).isFalse()
        Assertions.assertThat(pattern.canOnePassFind()).isFalse()
        Assertions.assertThat(pattern.canOnePassSubmatch()).isFalse()
    }

    @Test
    fun onePassIsNotBuiltWhenCaptureCountExceedsLimitOrOptionDisabled() {
        val tooManyCaptures = Pattern.compile("(a)".repeat(OnePass.MAX_CAPTURE_GROUPS))
        Assertions.assertThat(tooManyCaptures.onePassAnalyzed()).isFalse()
        Assertions.assertThat<OnePass?>(tooManyCaptures.onePass()).isNull()
        Assertions.assertThat(tooManyCaptures.canOnePassSubmatch()).isFalse()

        val disabled =
            Pattern.compile("^([a-z]+)$", 0, EnginePathOptions.builder().onePass(false).build())
        Assertions.assertThat(disabled.onePassAnalyzed()).isFalse()
        Assertions.assertThat<OnePass?>(disabled.onePass()).isNull()
        Assertions.assertThat(disabled.canOnePassPrimary()).isFalse()
        Assertions.assertThat(disabled.canOnePassFind()).isFalse()
        Assertions.assertThat(disabled.canOnePassSubmatch()).isFalse()
    }

    @Test
    fun progressGuardedFiniteRepeatsRequireAnExactEngine() {
        for (regex in mutableListOf<String?>("(?:a?){0,2}b", "((?:a?)?){1,3}b")) {
            val pattern = Pattern.compile(regex)
            Assertions.assertThat(pattern.prog().numLoopRegs()).isPositive()
            Assertions.assertThat(pattern.canOnePassPrimary()).isFalse()
            Assertions.assertThat(pattern.canOnePassFind()).isFalse()
            Assertions.assertThat(pattern.canOnePassSubmatch()).isFalse()
            Assertions.assertThat(pattern.dfaGroupZeroReliable()).isFalse()
        }
    }

    @Test
    fun nullableQuantifierWithoutNullableAlternationRetainsOnePassSubmatch() {
        val withCapture = Pattern.compile("(a)*")
        Assertions.assertThat(withCapture.onePassAnalyzed()).isTrue()
        Assertions.assertThat(withCapture.canOnePassPrimary()).isFalse()
        Assertions.assertThat(withCapture.canOnePassSubmatch()).isTrue()

        val withoutCapture = Pattern.compile("^a*$")
        Assertions.assertThat(withoutCapture.canOnePassPrimary()).isFalse()
        Assertions.assertThat(withoutCapture.canOnePassSubmatch()).isFalse()
    }

    @ParameterizedTest
    @CsvSource(
        "\\d{4}-\\d{2}-\\d{2}, 2026-10-08", "\\p{L}+\\s+\\p{Lu}\\p{Ll}*, john Smith", "apple|banana|cherry, banana"
    )
    @Throws(Exception::class)
    fun concurrentFirstUseSharesOnePass(regex: String?, input: String) {
        val pattern = Pattern.compile(regex)
        Assertions.assertThat(pattern.onePassAnalyzed()).isFalse()

        val workers = 32
        val start = CyclicBarrier(workers)
        val futures = ArrayList<Future<OnePass?>>()
        Executors.newFixedThreadPool(workers).use { executor ->
            for (worker in 0..<workers) {
                futures.add(
                    executor.submit<OnePass?>(
                        Callable {
                            start.await()
                            val onePass = pattern.onePass()
                            Assertions.assertThat(pattern.matcher(input).matches()).isTrue()
                            Assertions.assertThat(pattern.matcher(input + " tail").lookingAt()).isTrue()
                            Assertions.assertThat(
                                pattern.matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8))).matches()
                            )
                                .isTrue()
                            onePass
                        })
                )
            }
            val shared = futures.getFirst().get()
            Assertions.assertThat<OnePass?>(shared).isNotNull()
            for (future in futures) {
                Assertions.assertThat<OnePass?>(future.get()).isSameAs(shared)
            }
            Assertions.assertThat<OnePass?>(pattern.onePass()).isSameAs(shared)
        }
    }

    @Test
    fun numGroupsCounting() {
        val p = Pattern.compile("(a)(b)(c)")
        Assertions.assertThat(p.numGroups()).isEqualTo(3)
    }

    @Test
    fun numGroupsNoCaptures() {
        val p = Pattern.compile("abc")
        Assertions.assertThat(p.numGroups()).isZero()
    }

    @Test
    fun transparentGroupsPreserveLiteralAccelerators() {
        val p = Pattern.compile("(?:abcdef)")

        Assertions.assertThat(p.literalMatch()).isEqualTo("abcdef")
        Assertions.assertThat(p.prefix()).isEqualTo("abcdef")
    }

    @Test
    fun transparentGroupsPreserveCharacterClassAccelerators() {
        val p = Pattern.compile("(?:[A-Z]+)")

        val prefix = p.charClassPrefix()
        Assertions.assertThat<CharClassScanInfo?>(prefix).isNotNull()
        Assertions.assertThat(prefix!!.contains('A'.code)).isTrue()
        Assertions.assertThat<CharClassMatchInfo?>(p.matchDescriptor().charClassMatch).isNotNull()
    }

    @Test
    fun textStartAnchorsPreservePrefixAccelerators() {
        Assertions.assertThat(Pattern.compile("^https://.*").anchoredPrefix()).isEqualTo("https://")
        Assertions.assertThat(Pattern.compile("\\Ahttps://.*").anchoredPrefix()).isEqualTo("https://")
        Assertions.assertThat(Pattern.compile("\\A\\bhttps://.*").anchoredPrefix()).isEqualTo("https://")
        Assertions.assertThat(Pattern.compile("(\\A)https://.*").anchoredPrefix()).isEqualTo("https://")

        val prefix = Pattern.compile("^[0-9]+").anchoredCharClassPrefix()
        Assertions.assertThat<CharClassScanInfo?>(prefix).isNotNull()
        Assertions.assertThat(prefix!!.contains('0'.code)).isTrue()
        Assertions.assertThat(prefix.contains('9'.code)).isTrue()
        Assertions.assertThat(prefix.contains('a'.code)).isFalse()

        for (regex in arrayOf<String>("\\A\\b[0-9]+", "(\\A)[0-9]+")) {
            val separatedPrefix = Pattern.compile(regex).anchoredCharClassPrefix()
            Assertions.assertThat<CharClassScanInfo?>(separatedPrefix).`as`(regex).isNotNull()
            Assertions.assertThat(separatedPrefix!!.contains('0'.code)).`as`(regex).isTrue()
            Assertions.assertThat(separatedPrefix.contains('9'.code)).`as`(regex).isTrue()
            Assertions.assertThat(separatedPrefix.contains('a'.code)).`as`(regex).isFalse()
        }
    }

    @Test
    fun asciiPrefixScanInfoHandlesMissingAndEmptyClasses() {
        Assertions.assertThat<CharClassScanInfo?>(CharClassScanInfo.fromAsciiBitmap(null)).isNull()
        Assertions.assertThat<CharClassScanInfo?>(CharClassScanInfo.fromAsciiBitmap(AsciiBitmap.EMPTY)).isNull()
    }

    @Test
    fun asciiPrefixScanInfoExactlyRepresentsCommonClassShapes() {
        assertAsciiScanInfo(intArrayOf('x'.code), intArrayOf('x'.code, 'x'.code))
        assertAsciiScanInfo(intArrayOf('x'.code, 'y'.code), intArrayOf('x'.code, 'y'.code))
        assertAsciiScanInfo(intArrayOf('x'.code, 'z'.code), intArrayOf('x'.code, 'x'.code, 'z'.code, 'z'.code))
        assertAsciiScanInfo(asciiRange('0'.code, '9'.code), intArrayOf('0'.code, '9'.code))
        assertAsciiScanInfo(
            intArrayOf('a'.code, 'c'.code, 'e'.code),
            intArrayOf('a'.code, 'a'.code, 'c'.code, 'c'.code, 'e'.code, 'e'.code)
        )
    }

    @Test
    fun asciiPrefixScanInfoPreservesMembersAcrossBitmapBoundary() {
        val info: CharClassScanInfo = assertAsciiScanInfo(intArrayOf(62, 63, 64, 65), intArrayOf(62, 65))

        Assertions.assertThat(info.bitmap0()).isEqualTo((1L shl 62) or (1L shl 63))
        Assertions.assertThat(info.bitmap1()).isEqualTo((1L shl 0) or (1L shl 1))
    }

    @Test
    fun transparentGroupsPreserveKeywordAlternationAccelerator() {
        val p = Pattern.compile("(?i)\\b(?:error|warning)\\b")

        Assertions.assertThat<KeywordAlternation?>(p.keywordAlternation()).isNotNull()
    }

    @Test
    fun greedyDotAllWrappersPreserveKeywordAlternationAccelerator() {
        val p = Pattern.compile("(?is).*\\b(you|your)\\b.*")

        Assertions.assertThat<KeywordAlternation?>(p.keywordAlternation()).isNotNull()
        Assertions.assertThat(p.keywordAlternation().greedyWholeInput).isTrue()
    }

    @Test
    fun replacementGroupConsumptionRecordsCaptureDemand() {
        val pattern = Pattern.compile("(qu|[b-df-hj-np-tv-z]*)([a-z]+)")

        pattern.matcher("the quick brown fox").replaceAll("$2$1ay")

        Assertions.assertThat(pattern.innerCapturesObserved()).isTrue()
    }

    @Test
    fun caseInsensitiveAsciiLiteralPreservesLiteralAccelerators() {
        val p = Pattern.compile("(?i)needle")

        Assertions.assertThat(p.literalMatch()).isEqualTo("needle")
        Assertions.assertThat(p.prefix()).isEqualTo("needle")
        Assertions.assertThat(p.prefixFoldCase()).isTrue()
    }

    @Test
    fun caseInsensitiveAsciiPrefixPreservesPrefixAccelerator() {
        val p = Pattern.compile("(?i)needle\\d+")

        Assertions.assertThat(p.literalMatch()).isNull()
        Assertions.assertThat(p.prefix()).isEqualTo("needle")
        Assertions.assertThat(p.prefixFoldCase()).isTrue()
    }

    @Test
    fun leadingWordBoundaryPreservesLiteralPrefixAccelerator() {
        val p = Pattern.compile("\\bSCRUB:begin_strip\\b(?s:.*?)\\bSCRUB:end_strip\\b")

        Assertions.assertThat(p.literalMatch()).isNull()
        Assertions.assertThat(p.prefix()).isEqualTo("SCRUB:begin_strip")
    }

    @Test
    fun leadingTextAnchorsDoNotExposeMovableLiteralPrefixAccelerator() {
        Assertions.assertThat(Pattern.compile("^SCRUB").prefix()).isNull()
        Assertions.assertThat(Pattern.compile("\\ASCRUB").prefix()).isNull()
    }

    @Test
    fun alternatePrefixAcceleration() {
        val p = Pattern.compile("(?:cat|dog|bird)s?")
        val prefix = p.charClassPrefix()
        Assertions.assertThat<CharClassScanInfo?>(prefix).isNotNull()
        Assertions.assertThat(prefix!!.contains('c'.code)).isTrue()
        Assertions.assertThat(prefix.contains('d'.code)).isTrue()
        Assertions.assertThat(prefix.contains('b'.code)).isTrue()
        Assertions.assertThat(prefix.contains('a'.code)).isFalse()
    }

    @Test
    fun alternatePrefixCaseInsensitiveAcceleration() {
        val p = Pattern.compile("(?i)(?:cat|dog|bird)s?")
        val prefix = p.charClassPrefix()
        Assertions.assertThat<CharClassScanInfo?>(prefix).isNotNull()
        Assertions.assertThat(prefix!!.contains('c'.code)).isTrue()
        Assertions.assertThat(prefix.contains('C'.code)).isTrue()
        Assertions.assertThat(prefix.contains('d'.code)).isTrue()
        Assertions.assertThat(prefix.contains('D'.code)).isTrue()
        Assertions.assertThat(prefix.contains('b'.code)).isTrue()
        Assertions.assertThat(prefix.contains('B'.code)).isTrue()
        Assertions.assertThat(prefix.contains('a'.code)).isFalse()
    }

    @Test
    fun unicodeCharacterClassPrefixAcceleration() {
        val p = Pattern.compile("[\\p{IsAlphabetic}]+")
        val prefix = p.charClassPrefix()
        Assertions.assertThat<CharClassScanInfo?>(prefix).isNotNull()
        Assertions.assertThat(prefix!!.isAscii).isFalse()
        Assertions.assertThat(prefix.contains('a'.code)).isTrue()
        Assertions.assertThat(prefix.contains('Z'.code)).isTrue()
        Assertions.assertThat(prefix.contains('\u00e9'.code)).isTrue() // é
        Assertions.assertThat(prefix.contains('\u03b1'.code)).isTrue() // α
        Assertions.assertThat(prefix.contains('1'.code)).isFalse()
        Assertions.assertThat(prefix.contains(' '.code)).isFalse()
    }

    @Test
    fun deeplyNestedRequiredQuantifierPrefixExtractionIsStackSafe() {
        val p = Pattern.compile(nestedRequiredPlusPattern(1000, "[ab]"))

        val prefix = p.charClassPrefix()
        Assertions.assertThat<CharClassScanInfo?>(prefix).isNotNull()
        Assertions.assertThat(prefix!!.contains('a'.code)).isTrue()
        Assertions.assertThat(prefix.contains('b'.code)).isTrue()
        Assertions.assertThat(prefix.contains('c'.code)).isFalse()
    }

    @Test
    fun deeplyNestedAlternationPrefixExtractionIsStackSafe() {
        val p = Pattern.compile(nestedAlternationPattern(1000))

        val prefix = p.charClassPrefix()
        Assertions.assertThat<CharClassScanInfo?>(prefix).isNotNull()
        Assertions.assertThat(prefix!!.contains('a'.code)).isTrue()
        Assertions.assertThat(prefix.contains('b'.code)).isTrue()
        Assertions.assertThat(prefix.contains('c'.code)).isFalse()
    }

    @Test
    fun deeplyNestedConcatPrefixExtractionIsStackSafe() {
        val p = Pattern.compile(nestedPrefixConcatPattern(1000))

        Assertions.assertThat(p.prefix()).isEqualTo("foo")
    }

    @Test
    fun deeplyNestedFixedOffsetWidthExtractionIsStackSafe() {
        val p = Pattern.compile(nestedFixedOffsetPattern(10000))

        Assertions.assertThat<StartPlan?>(p.startPlan()).isInstanceOf(StartPlan.FixedOffset::class.java)
    }

    @Test
    fun largeCapturedLiteralConcatenationRecordsMaximalSuffix() {
        val regex = StringBuilder("[ab]")
        for (i in 0..2000 - 1) {
            regex.append("(x)")
        }

        Assertions.assertThat<StartPlan?>(Pattern.compile(regex.toString()).startPlan())
            .isInstanceOfSatisfying<StartPlan.FixedOffset?>(
                StartPlan.FixedOffset::class.java,
                Consumer { plan: StartPlan.FixedOffset? ->
                    Assertions.assertThat(plan!!.fol.literal()).hasSize(2000)
                    Assertions.assertThat(plan.fol.minOffset()).isEqualTo(1)
                })
    }

    @Test
    fun caseInsensitiveAsciiLiteralUsesLiteralMatchMetadata() {
        val p = Pattern.compile("(?i)i")

        Assertions.assertThat(p.literalMatch()).isEqualTo("i")
        Assertions.assertThat(p.prefix()).isEqualTo("i")
        Assertions.assertThat(p.prefixFoldCase()).isTrue()
    }

    @Test
    fun dotStarAroundWhitespaceRecordsRequiredWhitespaceClass() {
        val p = Pattern.compile(".*\\s+.*")

        Assertions.assertThat<CharClassScanInfo?>(requiredCharClass(p)).isNotNull()
    }

    @ParameterizedTest
    @CsvSource(
        "'\\d+/\\d+/\\d+',    /, 0", "'[A-Z]+:[0-9]+',     :, A", "'\\w+#[a-f0-9]{8}',  #, a"
    )
    fun requiredCharacterClassPrefersTheMostSelectiveMandatoryAtom(
        regex: String?, expectedMember: Char, expectedNonMember: Char
    ) {
        val p = Pattern.compile(regex)

        Assertions.assertThat(requiredClassContains(p, expectedMember.code)).isTrue()
        Assertions.assertThat(requiredClassContains(p, expectedNonMember.code)).isFalse()
    }

    @ParameterizedTest
    @CsvSource(
        "'\\d{3}/\\d{3}/\\d{4}', /, 3", "'[A-Z][0-9]::[a-z]+',   ::, 2", "'[ab][cd]-xyz',          -xyz, 2"
    )
    fun fixedOffsetAsciiLiteralsAreRecorded(regex: String?, literal: String?, offset: Int) {
        Assertions.assertThat<StartPlan?>(Pattern.compile(regex).startPlan())
            .isInstanceOfSatisfying<StartPlan.FixedOffset?>(
                StartPlan.FixedOffset::class.java,
                Consumer { plan: StartPlan.FixedOffset? ->
                    Assertions.assertThat(plan!!.fol.literal()).isEqualTo(literal)
                    Assertions.assertThat(plan.fol.offset()).isEqualTo(offset)
                })
    }

    @ParameterizedTest
    @ValueSource(strings = ["\\d+/x", "[ab](?i:x)", "literal-prefix"])
    fun variableWidthUnicodeAndOrdinaryPrefixesDoNotRecordFixedOffsetLiterals(regex: String?) {
        Assertions.assertThat<StartPlan?>(Pattern.compile(regex).startPlan())
            .isNotInstanceOf(StartPlan.FixedOffset::class.java)
    }

    @Test
    fun unicodeClassOffsetsAreRecordedAsNonDiscreteCodePointRanges() {
        Assertions.assertThat<StartPlan?>(Pattern.compile("[αβ]/x").startPlan())
            .isInstanceOfSatisfying<StartPlan.FixedOffset?>(
                StartPlan.FixedOffset::class.java,
                Consumer { plan: StartPlan.FixedOffset? ->
                    Assertions.assertThat(plan!!.fol.minOffset()).isEqualTo(1)
                    Assertions.assertThat(plan.fol.maxOffset()).isEqualTo(1)
                    Assertions.assertThat(plan.fol.discreteOffsets()).isNull()
                })
    }

    @Test
    fun discreteMultiOffsetLiteralsAreRecorded() {
        Assertions.assertThat<StartPlan?>(Pattern.compile("(^|[a-z])(#!customTag)").startPlan())
            .isInstanceOfSatisfying<StartPlan.FixedOffset?>(
                StartPlan.FixedOffset::class.java,
                Consumer { plan: StartPlan.FixedOffset? ->
                    Assertions.assertThat(plan!!.fol.literal()).isEqualTo("#!customTag")
                    Assertions.assertThat(plan.fol.minOffset()).isZero()
                    Assertions.assertThat(plan.fol.maxOffset()).isEqualTo(1)
                    Assertions.assertThat(plan.fol.discreteOffsets()).containsExactly(0, 1)
                })
    }

    @Test
    fun boundedRangeOffsetLiteralsAreRecorded() {
        Assertions.assertThat<StartPlan?>(Pattern.compile("\\s{0,8}renderElement\\(").startPlan())
            .isInstanceOfSatisfying<StartPlan.FixedOffset?>(
                StartPlan.FixedOffset::class.java,
                Consumer { plan: StartPlan.FixedOffset? ->
                    Assertions.assertThat(plan!!.fol.literal()).isEqualTo("renderElement(")
                    Assertions.assertThat(plan.fol.minOffset()).isEqualTo(0)
                    Assertions.assertThat(plan.fol.maxOffset()).isEqualTo(8)
                })
    }

    @ParameterizedTest
    @CsvSource(
        "'.*(x|y).*',             xy, az",
        "'.*(?:m|n).*',           mn, xz",
        "'.*([0-2]|[7-9]).*',     08, 56",
        "'.*((?:α|β))+.*',        αβ, γδ",
        "'.*(?:ab|cd).*',         ac, bd"
    )
    fun mandatoryAlternativesRecordTheirRequiredCharacterUnion(
        regex: String?, members: String, nonMembers: String
    ) {
        val p = Pattern.compile(regex)

        Assertions.assertThat<CharClassScanInfo?>(requiredCharClass(p)).isNotNull()
        members
            .codePoints()
            .forEach(IntConsumer { codePoint: Int ->
                Assertions.assertThat(requiredClassContains(p, codePoint)).isTrue()
            })
        nonMembers
            .codePoints()
            .forEach(IntConsumer { codePoint: Int ->
                Assertions.assertThat(requiredClassContains(p, codePoint)).isFalse()
            })
    }

    @ParameterizedTest
    @ValueSource(strings = [".*(x|).*", ".*(?:x|y)?.*", ".*(?:x|y){0,3}.*", ".*(?:x|y|.*).*"])
    fun nullableAlternativesDoNotRecordRequiredCharacterClasses(regex: String?) {
        Assertions.assertThat<CharClassScanInfo?>(requiredCharClass(Pattern.compile(regex))).isNull()
    }

    @ParameterizedTest
    @CsvSource(
        "'.*coolfunctionname.*',       coolfunctionname",
        "'.*(needle).*',               needle",
        "'(?:ab)+.*',                  ab",
        "'.*short.*much-longer.*',     much-longer",
        "'.*前置.*かなり長い必要語.*', かなり長い必要語"
    )
    fun mandatoryCaseSensitiveLiteralsAreRecorded(regex: String?, expected: String?) {
        Assertions.assertThat(requiredLiteral(Pattern.compile(regex))).isEqualTo(expected)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [".*(?:needle)?.*", "(?i).*needle.*", ".*(?:needle|thread).*", ".*(?:needle){0,2}.*", "needle.*"
        ]
    )
    fun optionalCaseInsensitiveAndAlreadyPrefixedLiteralsAreNotRecorded(regex: String?) {
        Assertions.assertThat(requiredLiteral(Pattern.compile(regex))).isNull()
    }

    @ParameterizedTest
    @CsvSource(
        "'.*\\.json$',                         .json,                        false",
        "'(?i).*\\.json$',                     .json,                        true",
        "'.*report_2026\\.log$',               report_2026.log,              false",
        "'.*(foo)(bar)$',                      foobar,                       false",
        "'[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$',  ABCDEFGHIJKLMNOPQRSTUVWXYZ,   false",
        "'.*test\\z',                          test,                         false",
        "'.*(?i:test)\\z',                     test,                         true"
    )
    fun endAnchoredLiteralSuffixIsRecorded(regex: String?, expected: String?, foldCase: Boolean) {
        val info: SuffixInfo? = endAnchoredSuffix(Pattern.compile(regex))
        Assertions.assertThat(info!!.suffix).isEqualTo(expected)
        Assertions.assertThat(info.foldCase).isEqualTo(foldCase)
    }

    @ParameterizedTest
    @ValueSource(strings = [".*", ".*json", "(?m).*\\.json$"])
    fun unanchoredOrMultilineDollarDoNotRecordEndAnchoredSuffix(regex: String?) {
        Assertions.assertThat<SuffixInfo?>(endAnchoredSuffix(Pattern.compile(regex))).isNull()
    }

    @Test
    fun endAnchoredSuffixRejectsUtf8Input() {
        val p = Pattern.compile(".*\\.json$")
        Assertions.assertThat(p.find(Utf8Input.trusted("config.json".toByteArray(StandardCharsets.UTF_8)))).isTrue()
        Assertions.assertThat(p.find(Utf8Input.trusted("config.yaml".toByteArray(StandardCharsets.UTF_8)))).isFalse()
    }

    @Test
    fun disjointRequiredLiteralsAreRecordedForAlternations() {
        val p = Pattern.compile(".*(?:apple|banana|cherry).*")
        Assertions.assertThat<RejectPlan.DisjointLiterals?>(
            Companion.findRejectPlan<RejectPlan.DisjointLiterals?>(
                p,
                RejectPlan.DisjointLiterals::class.java
            )
        )
            .isNotNull()
            .satisfies(
                ThrowingConsumer { plan: RejectPlan.DisjointLiterals? ->
                    Assertions.assertThat<String?>(plan!!.literals).containsExactly("apple", "banana", "cherry")
                })
        Assertions.assertThat(requiredLiteral(p)).isNull()

        val p2 = Pattern.compile("(foo.*|bar.*|baz.*)")
        Assertions.assertThat<RejectPlan.DisjointLiterals?>(
            Companion.findRejectPlan<RejectPlan.DisjointLiterals?>(
                p2,
                RejectPlan.DisjointLiterals::class.java
            )
        )
            .isNotNull()
            .satisfies(ThrowingConsumer { plan: RejectPlan.DisjointLiterals? ->
                Assertions.assertThat<String?>(plan!!.literals).containsExactly("foo", "bar", "baz")
            })

        val p3 = Pattern.compile("(?:\\bfirstToken\\b|\\bsecondToken\\b)")
        Assertions.assertThat<RejectPlan.DisjointLiterals?>(
            Companion.findRejectPlan<RejectPlan.DisjointLiterals?>(
                p3,
                RejectPlan.DisjointLiterals::class.java
            )
        )
            .isNotNull()
            .satisfies(
                ThrowingConsumer { plan: RejectPlan.DisjointLiterals? ->
                    Assertions.assertThat<String?>(plan!!.literals).containsExactly("firstToken", "secondToken")
                })
    }

    @Test
    fun disjointRequiredLiteralsSubsumptionMinimization() {
        // pineapple contains apple, so pineapple is pruned and apple + banana are required.
        val p1 = Pattern.compile(".*(?:apple|pineapple|banana).*")
        Assertions.assertThat<RejectPlan.DisjointLiterals?>(
            Companion.findRejectPlan<RejectPlan.DisjointLiterals?>(
                p1,
                RejectPlan.DisjointLiterals::class.java
            )
        )
            .isNotNull()
            .satisfies(ThrowingConsumer { plan: RejectPlan.DisjointLiterals? ->
                Assertions.assertThat<String?>(plan!!.literals).containsExactly("apple", "banana")
            })

        // prefix_foo contains foo, bar_baz contains baz
        val p2 = Pattern.compile(".*(?:prefix_foo|foo|bar_baz|baz).*")
        Assertions.assertThat<RejectPlan.DisjointLiterals?>(
            Companion.findRejectPlan<RejectPlan.DisjointLiterals?>(
                p2,
                RejectPlan.DisjointLiterals::class.java
            )
        )
            .isNotNull()
            .satisfies(ThrowingConsumer { plan: RejectPlan.DisjointLiterals? ->
                Assertions.assertThat<String?>(plan!!.literals).containsExactly("foo", "baz")
            })

        // https contains http, ftp is distinct
        val p3 = Pattern.compile(".*(?:http|https|ftp).*")
        Assertions.assertThat<RejectPlan.DisjointLiterals?>(
            Companion.findRejectPlan<RejectPlan.DisjointLiterals?>(
                p3,
                RejectPlan.DisjointLiterals::class.java
            )
        )
            .isNotNull()
            .satisfies(ThrowingConsumer { plan: RejectPlan.DisjointLiterals? ->
                Assertions.assertThat<String?>(plan!!.literals).containsExactly("http", "ftp")
            })

        // A lone surrogate is not a code-point substring of a supplementary character.
        val p4 = Pattern.compile("(?:a\uD83D|za\uD83D\uDE00|banana)")
        Assertions.assertThat<RejectPlan.DisjointLiterals?>(
            Companion.findRejectPlan<RejectPlan.DisjointLiterals?>(
                p4,
                RejectPlan.DisjointLiterals::class.java
            )
        )
            .isNotNull()
            .satisfies(
                ThrowingConsumer { plan: RejectPlan.DisjointLiterals? ->
                    Assertions.assertThat<String?>(plan!!.literals)
                        .containsExactly("a\uD83D", "za\uD83D\uDE00", "banana")
                })
    }

    @Test
    fun tooManyRawDisjointLiteralsAreRejectedBeforeMinimization() {
        val regex = StringBuilder("(?:banana")
        for (i in 0..15) {
            regex.append('|').append("x".repeat(i)).append("apple")
        }
        regex.append(')')

        Assertions.assertThat<RejectPlan.DisjointLiterals?>(
            Companion.findRejectPlan<RejectPlan.DisjointLiterals?>(
                Pattern.compile(regex.toString()),
                RejectPlan.DisjointLiterals::class.java
            )
        )
            .isNull()
    }

    @Test
    fun disjointRequiredLiteralCountIsBoundedByMeasuredCrossover() {
        Assertions.assertThat<RejectPlan.DisjointLiterals?>(
            Companion.findRejectPlan<RejectPlan.DisjointLiterals?>(
                Pattern.compile("(?:apple|banana|cherry|orange)\\d"),
                RejectPlan.DisjointLiterals::class.java
            )
        )
            .isNotNull()
            .satisfies(
                ThrowingConsumer { plan: RejectPlan.DisjointLiterals? ->
                    Assertions.assertThat<String?>(plan!!.literals)
                        .containsExactly("apple", "banana", "cherry", "orange")
                })
        Assertions.assertThat<RejectPlan.DisjointLiterals?>(
            Companion.findRejectPlan<RejectPlan.DisjointLiterals?>(
                Pattern.compile("(?:apple|banana|cherry|orange|papaya)\\d"),
                RejectPlan.DisjointLiterals::class.java
            )
        )
            .isNull()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [".*(?:apple|banana)?.*", ".*(?:apple|.*).*", ".*(?:apple|a).*", "(?i).*(?:apple|banana).*"
        ]
    )
    fun invalidOrNullableAlternationsDoNotRecordDisjointLiterals(regex: String?) {
        val p = Pattern.compile(regex)
        if (p.prefix() == null && requiredLiteral(p) == null) {
            Assertions.assertThat<RejectPlan.DisjointLiterals?>(
                Companion.findRejectPlan<RejectPlan.DisjointLiterals?>(
                    p,
                    RejectPlan.DisjointLiterals::class.java
                )
            )
                .isNull()
        }
    }

    @Test
    fun boundaryPrefixedLiteralRecordsRequiredClass() {
        val p = Pattern.compile("\\b{g}z")

        Assertions.assertThat<CharClassScanInfo?>(requiredCharClass(p)).isNotNull()
    }

    @Test
    fun pureNullablePatternsDoNotRecordRequiredCharacterClasses() {
        val p = Pattern.compile(".*")

        Assertions.assertThat<CharClassScanInfo?>(requiredCharClass(p)).isNull()
    }

    @ParameterizedTest(name = "compile(\"{0}\").numGroups() == {1}")
    @CsvSource(
        "'',         0",
        "'.*',        0",
        "'abba',      0",
        "'ab(b)a',    1",
        "'ab(.*)a',   1",
        "'(.*)ab(.*)a',  2",
        "'(.*)(ab)(.*)a', 3",
        "'(.*)((a)b)(.*)a', 4",
        "'(.*)(\\(ab)(.*)a', 3",
        "'(.*)(\\(a\\)b)(.*)a', 3"
    )
    fun numGroups(pattern: String?, expected: Int) {
        Assertions.assertThat(Pattern.compile(pattern).numGroups()).isEqualTo(expected)
    }

    @Test
    fun prefixExtractionFromNestedCaptureInConcat() {
        val p1 = Pattern.compile("(foo bar)baz")
        Assertions.assertThat(p1.prefix()).isEqualTo("foo bar")

        val p2 = Pattern.compile("(<template name>.*)")
        Assertions.assertThat(p2.prefix()).isEqualTo("<template name>")

        // reproducing the actual templateTagMatch pattern structure
        val p3 = Pattern.compile("(<template name>.*)([^>])")
        Assertions.assertThat(p3.prefix()).isEqualTo("<template name>")
    }

    @Test
    fun fixedOffsetLiteralPrefersRareTokenOverLongCommonToken() {
        // "____" has length 4 with common underscores.
        // "zq" has length 2 with rare letters 'z' and 'q'.
        val pattern = Pattern.compile("[0-9]{2}____[a-z]zq[a-z]")
        Assertions.assertThat<StartPlan?>(pattern.startPlan())
            .isInstanceOfSatisfying<StartPlan.FixedOffset?>(
                StartPlan.FixedOffset::class.java,
                Consumer { plan: StartPlan.FixedOffset? ->
                    Assertions.assertThat(plan!!.fol.literal()).isEqualTo("zq")
                })
    }

    @Test
    fun requiredLiteralPrefersRareToken() {
        val pattern = Pattern.compile(".*(____).*?(404_ERROR).*")
        Assertions.assertThat(requiredLiteral(pattern)).isEqualTo("404_ERROR")
    }

    @Test
    fun requiredLiteralRetainsSelectivityFromLengthWhenCharactersAreCommon() {
        val spaces = " ".repeat(32)
        val pattern = Pattern.compile(".*(" + spaces + ").*?(ee).*")

        Assertions.assertThat(requiredLiteral(pattern)).isEqualTo(spaces)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["(?:[a&&b]\\X)|a+", "(?:[a&&b]\\b{g})|a+", "a+|(?:[a&&b]\\X)", "a+|(?:[a&&b]\\b{g})"
        ]
    )
    fun forwardDfaSupportsProgramsAfterUnreachableGraphemeInstructionsAreRemoved(regex: String?) {
        val pattern = Pattern.compile(regex)
        Assertions.assertThat(pattern.prog().hasGraphemeSemantics()).isTrue()
        Assertions.assertThat(pattern.flatDfaProg().hasGraphemeSemantics()).isFalse()
        Assertions.assertThat(pattern.forwardDfaSupported()).isTrue()
        Assertions.assertThat(pattern.matcher("a".repeat(5000)).matches()).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["\\X|a+", "\\b{g}a+", "a+\\X"])
    fun forwardDfaRejectsLiveGraphemeInstructions(regex: String?) {
        Assertions.assertThat(Pattern.compile(regex).forwardDfaSupported()).isFalse()
    }

    @Test
    @Throws(ReflectiveOperationException::class)
    fun threadCacheRebuildsAfterSoftReferenceIsCleared() {
        val dfaPattern = Pattern.compile("([a-z]+)[0-9]+")
        val firstDfa = dfaPattern.forwardFirstMatchDfa()
        val longestDfa = dfaPattern.forwardLongestMatchDfa()
        val reverseDfa = dfaPattern.reverseDfa()
        Assertions.assertThat<Dfa?>(dfaPattern.forwardFirstMatchDfa()).isSameAs(firstDfa)
        Assertions.assertThat<Dfa?>(dfaPattern.forwardLongestMatchDfa()).isSameAs(longestDfa)
        Assertions.assertThat<Dfa?>(dfaPattern.reverseDfa()).isSameAs(reverseDfa)

        clearThreadCacheReference(dfaPattern)

        Assertions.assertThat<Dfa?>(dfaPattern.cachedForwardFirstMatchDfa()).isNull()
        Assertions.assertThat<Dfa?>(dfaPattern.forwardFirstMatchDfa()).isNotNull().isNotSameAs(firstDfa)
        Assertions.assertThat<Dfa?>(dfaPattern.forwardLongestMatchDfa()).isNotNull().isNotSameAs(longestDfa)
        Assertions.assertThat<Dfa?>(dfaPattern.reverseDfa()).isNotNull().isNotSameAs(reverseDfa)
    }

    @Test
    @Throws(ReflectiveOperationException::class)
    fun bitStateAndNfaCachesRebuildAfterSoftReferenceIsCleared() {
        val bitStatePattern =
            Pattern.compile(
                "([a-z]+)([0-9]+)", 0, EnginePathOptions.builder().dfa(false).onePass(false).build()
            )
        val nfaPattern =
            Pattern.compile(
                "([a-z]+)([0-9]+)",
                0,
                EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build()
            )
        for (pattern in List.of<Pattern>(bitStatePattern, nfaPattern)) {
            Assertions.assertThat(pattern.matcher("abc123").find()).isTrue()
            clearThreadCacheReference(pattern)
            Assertions.assertThat<BitState?>(pattern.borrowBitState()).isNull()
            Assertions.assertThat<Nfa?>(pattern.borrowNfa()).isNull()

            val m = pattern.matcher("xyz789")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(2)).isEqualTo("789")
        }
        Assertions.assertThat<BitState?>(bitStatePattern.borrowBitState()).isNotNull()
        Assertions.assertThat<Nfa?>(nfaPattern.borrowNfa()).isNotNull()
    }

    @Test
    @Timeout(10)
    @Throws(Exception::class)
    fun isolatedClassLoaderIsNotStronglyRetainedByThreadLocalCache() {
        // Clears the soft references explicitly: System.gc() does not clear recently used soft
        // references, so this verifies that nothing but the soft referents retains the loader.
        val loaderRef: WeakReference<ClassLoader?> = createIsolatedLoaderAndPopulateCaches()
        var i = 0
        while (i < 50 && loaderRef.get() != null) {
            System.gc()
            Thread.sleep(10)
            i++
        }
        Assertions.assertThat<ClassLoader?>(loaderRef.get()).isNull()
    }

    object ChildLoaderPatternHolder {
        private val DFA_PATTERN: Pattern = Pattern.compile("([a-z]+)[0-9]+")
        private val BITSTATE_PATTERN: Pattern = Pattern.compile(
            "([a-z]+)([0-9]+)", 0, EnginePathOptions.builder().dfa(false).onePass(false).build()
        )
        private val NFA_PATTERN: Pattern = Pattern.compile(
            "([a-z]+)([0-9]+)",
            0,
            EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build()
        )

        @Throws(ReflectiveOperationException::class)
        fun populateThreadCaches(): MutableList<SoftReference<*>?> {
            if (DFA_PATTERN.forwardFirstMatchDfa() == null || DFA_PATTERN.forwardLongestMatchDfa() == null || DFA_PATTERN.reverseDfa() == null) {
                throw AssertionError("Expected DFAs to be initialized")
            }
            if (!BITSTATE_PATTERN.matcher("abc123").find() || !NFA_PATTERN.matcher("abc123").find()) {
                throw AssertionError("Expected matches to succeed")
            }
            val field = Pattern::class.java.getDeclaredField("threadCache")
            field.setAccessible(true)
            return List.of<SoftReference<*>?>(
                (field.get(DFA_PATTERN) as ThreadLocal<*>).get() as SoftReference<*>?,
                (field.get(BITSTATE_PATTERN) as ThreadLocal<*>).get() as SoftReference<*>?,
                (field.get(NFA_PATTERN) as ThreadLocal<*>).get() as SoftReference<*>?
            )
        }
    }

    companion object {
        private fun <T : RejectPlan?> findRejectPlan(
            p: Pattern, clazz: Class<T?>
        ): T? {
            if (clazz.isInstance(p.rejectPlan())) {
                return p.rejectPlan() as T
            }
            if (p.rejectPlan() is RejectPlan.Composite) {
                for (plan in comp.plans) {
                    if (clazz.isInstance(plan)) {
                        return plan as T
                    }
                }
            }
            return null
        }

        private fun requiredCharClass(p: Pattern): CharClassScanInfo? {
            val cc: RequiredCharClass? =
                Companion.findRejectPlan<RequiredCharClass?>(p, RequiredCharClass::class.java)
            return if (cc != null) cc.scanInfo else null
        }

        private fun endAnchoredSuffix(p: Pattern): SuffixInfo? {
            val s: RejectPlan.EndAnchoredSuffix? =
                Companion.findRejectPlan<RejectPlan.EndAnchoredSuffix?>(p, RejectPlan.EndAnchoredSuffix::class.java)
            return if (s != null) s.suffix else null
        }

        private fun requiredLiteral(p: Pattern): String? {
            val lit: RequiredLiteral? =
                Companion.findRejectPlan<RequiredLiteral?>(p, RequiredLiteral::class.java)
            return if (lit != null) lit.literal else null
        }

        private fun requiredClassContains(pattern: Pattern, codePoint: Int): Boolean {
            val info: CharClassScanInfo? = requiredCharClass(pattern)
            return info != null
                    && InputScanner.classContains(info.ranges(), info.bitmap0(), info.bitmap1(), codePoint)
        }

        private fun assertAsciiScanInfo(members: IntArray, expectedRanges: IntArray): CharClassScanInfo {
            val builder = AsciiBitmap.Builder()
            for (member in members) {
                builder.add(member)
            }
            val asciiClass = builder.build()

            val info = CharClassScanInfo.fromAsciiBitmap(asciiClass)

            Assertions.assertThat<CharClassScanInfo?>(info).isNotNull()
            Assertions.assertThat(info.ranges()).containsExactly(*expectedRanges)
            for (codePoint in 0..127) {
                Assertions.assertThat(
                    InputScanner.classContains(info.ranges(), info.bitmap0(), info.bitmap1(), codePoint)
                )
                    .`as`("ASCII member %s", codePoint)
                    .isEqualTo(asciiClass!!.containsAscii(codePoint))
            }
            return info
        }

        private fun asciiRange(low: Int, high: Int): IntArray {
            val members = IntArray(high - low + 1)
            for (index in members.indices) {
                members[index] = low + index
            }
            return members
        }

        private fun nestedRequiredPlusPattern(depth: Int, atom: String): String {
            val regex = StringBuilder(depth * 5 + atom.length)
            for (i in 0..<depth) {
                regex.append("(?:")
            }
            regex.append(atom)
            for (i in 0..<depth) {
                regex.append(")+")
            }
            return regex.toString()
        }

        private fun nestedAlternationPattern(depth: Int): String {
            val regex = StringBuilder(depth * 5 + 1)
            for (i in 0..<depth) {
                regex.append("(?:")
            }
            regex.append('a')
            for (i in 0..<depth) {
                regex.append("|b)")
            }
            return regex.toString()
        }

        private fun nestedPrefixConcatPattern(depth: Int): String {
            val regex = StringBuilder(depth * 3 + 3)
            for (i in 0..<depth) {
                regex.append('(')
            }
            regex.append("foo")
            for (i in 0..<depth) {
                regex.append(")x")
            }
            return regex.toString()
        }

        private fun nestedFixedOffsetPattern(depth: Int): String {
            val regex = StringBuilder(depth * 3 + 6)
            for (i in 0..<depth) {
                regex.append('(')
            }
            regex.append("[ab]")
            for (i in 0..<depth) {
                regex.append(")x")
            }
            regex.append("ZZ")
            return regex.toString()
        }

        @Throws(ReflectiveOperationException::class)
        private fun clearThreadCacheReference(pattern: Pattern?) {
            val threadCacheField = Pattern::class.java.getDeclaredField("threadCache")
            threadCacheField.setAccessible(true)
            val ref =
                (threadCacheField.get(pattern) as ThreadLocal<*>).get() as SoftReference<*>?
            Assertions.assertThat(ref).isNotNull()
            ref!!.clear()
        }

        @Throws(Exception::class)
        private fun createIsolatedLoaderAndPopulateCaches(): WeakReference<ClassLoader?> {
            val urls = arrayOf<URL?>(
                Pattern::class.java.getProtectionDomain().getCodeSource().getLocation(),
                PatternInternalTest::class.java.getProtectionDomain().getCodeSource().getLocation(),
            )
            object : URLClassLoader(urls, Pattern::class.java.getClassLoader()) {
                @Throws(ClassNotFoundException::class)
                override fun loadClass(name: String, resolve: Boolean): Class<*>? {
                    if (name.startsWith("org.safere.")) {
                        synchronized(getClassLoadingLock(name)) {
                            var c = findLoadedClass(name)
                            if (c == null) {
                                c = findClass(name)
                            }
                            if (resolve) {
                                resolveClass(c)
                            }
                            return c
                        }
                    }
                    return super.loadClass(name, resolve)
                }
            }.use { loader ->
                val holderClass = loader.loadClass(ChildLoaderPatternHolder::class.java.getName())
                Assertions.assertThat<ClassLoader?>(holderClass.getClassLoader()).isSameAs(loader)
                // populateThreadCaches() returns List<SoftReference<?>>.
                val refs = holderClass.getMethod("populateThreadCaches").invoke(null) as MutableList<*>?
                Assertions.assertThat(refs).hasSize(3)
                for (value in refs!!) {
                    Assertions.assertThat<Any?>(value).isInstanceOf(SoftReference::class.java)
                    val ref = value as SoftReference<*>
                    Assertions.assertThat<ClassLoader?>(ref.javaClass.getClassLoader()).isNull()
                    val cache: Any? = ref.get()
                    Assertions.assertThat<Any?>(cache).isNotNull()
                    Assertions.assertThat<ClassLoader?>(cache!!.javaClass.getClassLoader()).isSameAs(loader)
                    ref.clear()
                }
                return WeakReference<ClassLoader?>(loader)
            }
        }
    }
}
