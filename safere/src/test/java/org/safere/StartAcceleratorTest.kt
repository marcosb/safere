// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.safere.CharClassScanInfo.AsciiSmallSet
import org.safere.MultiAnchorDescriptor.StartPlan
import org.safere.Utf8StartAccelerator.Teddy
import java.nio.charset.StandardCharsets

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class StartAcceleratorTest {
    @Test
    fun nullAndNoneDescriptorsProduceNullAccelerators() {
        Assertions.assertThat<StringStartAccelerator?>(
            StringStartAccelerator.create(
                null as MultiAnchorDescriptor?,
                false
            )
        ).isNull()
        Assertions.assertThat<StringStartAccelerator?>(StringStartAccelerator.create(null as StartPlan?, false))
            .isNull()
        Assertions.assertThat<StringStartAccelerator?>(StringStartAccelerator.create(StartPlan.None.INSTANCE, false))
            .isNull()
        Assertions.assertThat<Utf8StartAccelerator?>(Utf8StartAccelerator.create(null as MultiAnchorDescriptor?, false))
            .isNull()
        Assertions.assertThat<Utf8StartAccelerator?>(Utf8StartAccelerator.create(null as StartPlan?, false)).isNull()
        Assertions.assertThat<Utf8StartAccelerator?>(Utf8StartAccelerator.create(StartPlan.None.INSTANCE, false))
            .isNull()
    }

    @Test
    fun stringMultiLiteralPlanRetainsSelectiveCharacterClassFallback() {
        val plan = Pattern.compile("apple|banana|cherry").startPlan()

        Assertions.assertThat<StartPlan?>(plan).isInstanceOf(StartPlan.MultiLiteral::class.java)
        Assertions.assertThat<StringStartAccelerator?>(StringStartAccelerator.create(plan, false))
            .isInstanceOf(StringStartAccelerator.CharClass::class.java)
    }

    @Test
    fun utf8MultiLiteralPlanDoesNotUseNonselectiveCharacterClassFallback() {
        val plan = Pattern.compile("afoo|bfoo|cfoo|dfoo").startPlan()

        Assertions.assertThat<StartPlan?>(plan).isInstanceOf(StartPlan.MultiLiteral::class.java)
        val accelerator = Utf8StartAccelerator.create(plan, false)
        if (VectorScanProviders.vectorProviderAvailable()) {
            Assertions.assertThat<Utf8StartAccelerator?>(accelerator)
                .isInstanceOf(Utf8StartAccelerator.MultiLiteral::class.java)
        } else if (VectorScanProviders.vectorProviderAvailable()) {
            Assertions.assertThat<Utf8StartAccelerator?>(accelerator).isInstanceOf(Teddy::class.java)
        } else {
            Assertions.assertThat<Utf8StartAccelerator?>(accelerator).isNull()
        }
    }

    @Test
    fun literalPrefixAcceleratesStringAndUtf8() {
        val plan: StartPlan = plan("needle", false, null, null)

        val strAcc = StringStartAccelerator.create(plan, false)!!
        Assertions.assertThat<StringStartAccelerator?>(strAcc).isInstanceOf(StringStartAccelerator.Literal::class.java)
        Assertions.assertThat<AcceleratorPolicy?>(strAcc.policy()).isEqualTo(AcceleratorPolicy.LITERAL)
        Assertions.assertThat(
            StringStartAccelerator.findNextCandidate(strAcc, "haystack with needle here", 0, false)
        )
            .isEqualTo(14)
        Assertions.assertThat(
            StringStartAccelerator.findNextCandidate(
                strAcc, "haystack with needle here", 15, false
            )
        )
            .isEqualTo(-1)

        val utf8Acc = Utf8StartAccelerator.create(plan, false)!!
        Assertions.assertThat<Utf8StartAccelerator?>(utf8Acc).isInstanceOf(Utf8StartAccelerator.Literal::class.java)
        Assertions.assertThat<AcceleratorPolicy?>(utf8Acc.policy()).isEqualTo(AcceleratorPolicy.LITERAL)
        Assertions.assertThat(
            Utf8StartAccelerator.findNextCandidate(
                utf8Acc, utf8Scanner("haystack with needle here"), 0
            )
        )
            .isEqualTo(14)
        Assertions.assertThat(
            Utf8StartAccelerator.findNextCandidate(
                utf8Acc, utf8Scanner("haystack with needle here"), 15
            )
        )
            .isEqualTo(-1)
    }

    @Test
    fun utf8LiteralPrefixFindsCandidatesAfterRepeatedFalsePrefixes() {
        val asciiPrefix = "aaaaQaaaa"
        val ascii =
            Utf8StartAccelerator.create(plan(asciiPrefix, false, null, null), false) as Utf8StartAccelerator.Literal
        Assertions.assertThat(ascii.rareByteOffset).isEqualTo(4)
        val asciiScanner: Utf8InputScanner = utf8Scanner("aaaaYaaaa".repeat(100) + asciiPrefix + "1")
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(ascii, asciiScanner, 0)).isEqualTo(900)
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(ascii, asciiScanner, 901)).isEqualTo(-1)

        val unicodePrefix = "aaaaШaaaa"
        val unicode =
            Utf8StartAccelerator.create(plan(unicodePrefix, false, null, null), false) as Utf8StartAccelerator.Literal
        Assertions.assertThat(unicode.rareByteOffset).isGreaterThan(0)
        val unicodeScanner: Utf8InputScanner = utf8Scanner("aaaaЮaaaa".repeat(100) + unicodePrefix + "1")
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(unicode, unicodeScanner, 0))
            .isEqualTo("aaaaЮaaaa".repeat(100).toByteArray(StandardCharsets.UTF_8).size)
    }

    @Test
    fun caseInsensitiveLiteralAcceleratesStringAndUtf8() {
        val plan: StartPlan = plan("needle", true, null, null)

        val strAcc = StringStartAccelerator.create(plan, false)!!
        Assertions.assertThat<StringStartAccelerator?>(strAcc)
            .isInstanceOf(StringStartAccelerator.CaseInsensitiveLiteral::class.java)
        Assertions.assertThat(
            StringStartAccelerator.findNextCandidate(strAcc, "haystack with NEEDLE here", 0, false)
        )
            .isEqualTo(14)

        val utf8Acc = Utf8StartAccelerator.create(plan, false)!!
        Assertions.assertThat<Utf8StartAccelerator?>(utf8Acc)
            .isInstanceOf(Utf8StartAccelerator.CaseInsensitiveLiteral::class.java)
        Assertions.assertThat<MatchStrategy?>(utf8Acc.policy().strategy).isEqualTo(MatchStrategy.LITERAL)
        Assertions.assertThat(utf8Acc.policy().isExactMatchCandidate).isTrue()
        Assertions.assertThat(
            Utf8StartAccelerator.findNextCandidate(
                utf8Acc, utf8Scanner("haystack with NEEDLE here"), 0
            )
        )
            .isEqualTo(14)
        Assertions.assertThat(
            Utf8StartAccelerator.findNextCandidate(
                utf8Acc, utf8Scanner("haystack with nEeDlE here"), 0
            )
        )
            .isEqualTo(14)
        Assertions.assertThat(
            Utf8StartAccelerator.findNextCandidate(
                utf8Acc, utf8Scanner("haystack with needle here"), 15
            )
        )
            .isEqualTo(-1)

        // Single character case-insensitive prefix
        val singleDesc: StartPlan = plan("a", true, null, null)
        val singleUtf8 = Utf8StartAccelerator.create(singleDesc, false)!!
        Assertions.assertThat<Utf8StartAccelerator?>(singleUtf8)
            .isInstanceOf(Utf8StartAccelerator.CaseInsensitiveLiteral::class.java)
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(singleUtf8, utf8Scanner("xxxA"), 0))
            .isEqualTo(3)
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(singleUtf8, utf8Scanner("xxxa"), 0))
            .isEqualTo(3)

        // Non-ASCII case-insensitive prefixes use Unicode-aware accelerators for both inputs.
        val nonAsciiDesc: StartPlan = plan("café", true, null, null)
        val unicodeStr = StringStartAccelerator.create(nonAsciiDesc, false)!!
        Assertions.assertThat<StringStartAccelerator?>(unicodeStr)
            .isInstanceOf(StringStartAccelerator.UnicodeCaseInsensitiveLiteral::class.java)
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(unicodeStr, "prefix cafE CAFÉ", 0, false))
            .isEqualTo(12)
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(unicodeStr, "prefix CAFÉ", 0, false))
            .isEqualTo(7)

        val unicodeUtf8 = Utf8StartAccelerator.create(nonAsciiDesc, false)!!
        Assertions.assertThat<Utf8StartAccelerator?>(unicodeUtf8)
            .isInstanceOf(Utf8StartAccelerator.UnicodeCaseInsensitiveLiteral::class.java)
        Assertions.assertThat(
            Utf8StartAccelerator.findNextCandidate(
                unicodeUtf8, utf8Scanner("cafE CAFÉ caFé café"), 0
            )
        )
            .isEqualTo("cafE ".toByteArray(StandardCharsets.UTF_8).size)
    }

    @Test
    fun unicodeCaseInsensitiveUtf8LiteralFindsOnlyFoldEquivalentPrefixCandidates() {
        val accelerator: Utf8StartAccelerator =
            Utf8StartAccelerator.create(plan("Шерлок Холмс", true, null, null), false)!!
        Assertions.assertThat<Utf8StartAccelerator?>(accelerator)
            .isInstanceOf(Utf8StartAccelerator.UnicodeCaseInsensitiveLiteral::class.java)

        val text = "Шерлок ХолмX шЕРЛОК хОЛМС ШЕРЛОК ХОЛМС"
        val scanner: Utf8InputScanner = utf8Scanner(text)
        val expected = "Шерлок ХолмX ".toByteArray(StandardCharsets.UTF_8).size
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(accelerator, scanner, 0)).isEqualTo(expected)
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(accelerator, scanner, expected + 1))
            .isEqualTo(expected + "шЕРЛОК хОЛМС ".toByteArray(StandardCharsets.UTF_8).size)
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(accelerator, scanner, scanner.length))
            .isEqualTo(-1)
    }

    @Test
    fun unicodeCaseInsensitiveUtf8LiteralHandlesVariableWidthFoldBeforeLaterAnchor() {
        val accelerator: Utf8StartAccelerator =
            Utf8StartAccelerator.create(plan("Ké", true, null, null), false)!!
        Assertions.assertThat<Utf8StartAccelerator?>(accelerator)
            .isInstanceOf(Utf8StartAccelerator.UnicodeCaseInsensitiveLiteral::class.java)
        val scanner: Utf8InputScanner = utf8Scanner("x ké y KÉ z Ké")
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(accelerator, scanner, 0)).isEqualTo(2)
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(accelerator, scanner, 3))
            .isEqualTo("x ké y ".toByteArray(StandardCharsets.UTF_8).size)
    }

    @Test
    fun unicodeCaseInsensitiveUtf8LiteralFindsCandidatesAcrossScanWindows() {
        val accelerator: Utf8StartAccelerator =
            Utf8StartAccelerator.create(plan("Шx", true, null, null), false)!!
        val first = "x".repeat(255)
        val middle = "x".repeat(255)
        val scanner: Utf8InputScanner = utf8Scanner(first + "шx" + middle + "Шx")
        val second = (first + "шx" + middle).toByteArray(StandardCharsets.UTF_8).size

        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(accelerator, scanner, 0)).isEqualTo(255)
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(accelerator, scanner, 256)).isEqualTo(second)
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(accelerator, scanner, second + 1))
            .isEqualTo(-1)
    }

    @Test
    fun unicodeCaseInsensitiveUtf8LiteralPreservesFullFindSequence() {
        val patterns = arrayOf<String?>("(?iu)Шерлок Холмс", "(?iu)café", "(?iu)Ké", "(?iu)ϑϑ")
        val inputs = arrayOf<String?>(
            "шЕРЛОК хОЛМС Шерлок ХолмX ШЕРЛОК ХОЛМС", "CAFÉ cafe cAfÉ", "ké KÉ Ké", "ϑθ Θϑ ϑx"
        )
        val noAcceleration = EnginePathOptions.builder().startAcceleration(false).build()
        for (i in patterns.indices) {
            val accelerated = Pattern.compile(patterns[i])
            val control = Pattern.compile(patterns[i], 0, noAcceleration)
            if (i < 2) {
                Assertions.assertThat<Utf8StartAccelerator?>(accelerated.utf8StartAccelerator())
                    .`as`("UTF-8 accelerator for %s", patterns[i])
                    .isInstanceOf(Utf8StartAccelerator.UnicodeCaseInsensitiveLiteral::class.java)
            }
            val input = Utf8Input.validated(inputs[i]!!.toByteArray(StandardCharsets.UTF_8))
            val actual = accelerated.matcher(input)
            val expected = control.matcher(input)
            while (true) {
                val found = expected.find()
                Assertions.assertThat(actual.find()).`as`("find for %s", patterns[i]).isEqualTo(found)
                if (!found) {
                    break
                }
                Assertions.assertThat(actual.start()).`as`("start for %s", patterns[i]).isEqualTo(expected.start())
                Assertions.assertThat(actual.end()).`as`("end for %s", patterns[i]).isEqualTo(expected.end())
            }
        }
    }

    @Test
    fun unicodeCaseInsensitiveUtf8FindIncludesWholeCaseFamily() {
        val literals = arrayOf<String>("éİ", "éİ", "éİ", "éİ", "éı", "éı", "éı", "éı", "éK", "éS")
        val inputs = arrayOf<String?>("éİ", "éı", "éi", "éI", "éİ", "éı", "éi", "éI", "éK", "éſ")
        val noAcceleration = EnginePathOptions.builder().startAcceleration(false).build()
        for (i in literals.indices) {
            val regex = "(?iu)" + literals[i]
            Assertions.assertThat(java.util.regex.Pattern.compile(regex).matcher(inputs[i]).find())
                .`as`("JDK find for %s against %s", regex, inputs[i])
                .isTrue()
            val utf8 = Utf8Input.validated(inputs[i]!!.toByteArray(StandardCharsets.UTF_8))
            Assertions.assertThat(org.safere.Pattern.compile(regex, 0, noAcceleration).matcher(utf8).find())
                .`as`("unaccelerated UTF-8 find for %s against %s", regex, inputs[i])
                .isTrue()
            Assertions.assertThat(org.safere.Pattern.compile(regex).matcher(utf8).find())
                .`as`("accelerated UTF-8 find for %s against %s", regex, inputs[i])
                .isTrue()
        }
    }

    @Test
    fun fixedOffsetLiteralAcceleratesStringAndUtf8() {
        val fixed = org.safere.Pattern.FixedOffsetLiteral("token", 2, 2, intArrayOf(2))
        val plan: StartPlan = plan(null, false, fixed, null)

        val strAcc = StringStartAccelerator.create(plan, false)!!
        Assertions.assertThat<StringStartAccelerator?>(strAcc)
            .isInstanceOf(StringStartAccelerator.FixedOffset::class.java)
        Assertions.assertThat<AcceleratorPolicy?>(strAcc.policy()).isEqualTo(AcceleratorPolicy.LITERAL)
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(strAcc, "abtoken cd", 0, false))
            .isEqualTo(0)

        val utf8Acc = Utf8StartAccelerator.create(plan, false)!!
        Assertions.assertThat<Utf8StartAccelerator?>(utf8Acc).isInstanceOf(Utf8StartAccelerator.FixedOffset::class.java)
        Assertions.assertThat<AcceleratorPolicy?>(utf8Acc.policy()).isEqualTo(AcceleratorPolicy.LITERAL)
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner("abtoken cd"), 0))
            .isEqualTo(0)
    }

    @Test
    fun charClassPrefixAcceleratesStringAndUtf8() {
        val pairScanInfo = org.safere.Pattern.compile("[ab]").charClassPrefix()
        val descPair: StartPlan = plan(null, false, null, pairScanInfo)

        val strAcc = StringStartAccelerator.create(descPair, false)!!
        Assertions.assertThat<StringStartAccelerator?>(strAcc)
            .isInstanceOf(StringStartAccelerator.CharClass::class.java)
        Assertions.assertThat<AcceleratorPolicy?>(strAcc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS)
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(strAcc, "xxxa", 0, false)).isEqualTo(3)

        val utf8PairAcc = Utf8StartAccelerator.create(descPair, false)!!
        Assertions.assertThat<Utf8StartAccelerator?>(utf8PairAcc)
            .isInstanceOf(Utf8StartAccelerator.CharClass::class.java)
        Assertions.assertThat<CharClassScanInfo?>((utf8PairAcc as Utf8StartAccelerator.CharClass).scanInfo)
            .isInstanceOf(AsciiSmallSet::class.java)
        Assertions.assertThat<AcceleratorPolicy?>(utf8PairAcc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS)
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(utf8PairAcc, utf8Scanner("xxxb"), 0))
            .isEqualTo(3)

        val tripleScanInfo = org.safere.Pattern.compile("[abc]").charClassPrefix()
        val planTriple: StartPlan = plan(null, false, null, tripleScanInfo)
        val utf8TripleAcc = Utf8StartAccelerator.create(planTriple, false)!!
        Assertions.assertThat<Utf8StartAccelerator?>(utf8TripleAcc)
            .isInstanceOf(Utf8StartAccelerator.CharClass::class.java)
        Assertions.assertThat<AcceleratorPolicy?>(utf8TripleAcc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS)
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(utf8TripleAcc, utf8Scanner("xxxc"), 0))
            .isEqualTo(3)

        val multiScanInfo = org.safere.Pattern.compile("[abcd]").charClassPrefix()
        val descMulti: StartPlan = plan(null, false, null, multiScanInfo)
        Assertions.assertThat<Utf8StartAccelerator?>(Utf8StartAccelerator.create(descMulti, false)).isNull()
        Assertions.assertThat<StringStartAccelerator?>(StringStartAccelerator.create(descMulti, false)).isNull()

        val denseUnicodeScanInfo = org.safere.Pattern.compile("[0-9é]").charClassPrefix()
        val denseUnicode: StartPlan = plan(null, false, null, denseUnicodeScanInfo)
        Assertions.assertThat<Utf8StartAccelerator?>(Utf8StartAccelerator.create(denseUnicode, false)).isNull()
        Assertions.assertThat<StringStartAccelerator?>(StringStartAccelerator.create(denseUnicode, false)).isNull()

        val sparseUnicodeScanInfo = org.safere.Pattern.compile("[aé]").charClassPrefix()
        val sparseUnicode: StartPlan = plan(null, false, null, sparseUnicodeScanInfo)
        Assertions.assertThat<Utf8StartAccelerator?>(Utf8StartAccelerator.create(sparseUnicode, false)).isNotNull()
        Assertions.assertThat<StringStartAccelerator?>(StringStartAccelerator.create(sparseUnicode, false)).isNotNull()

        val nonAsciiScanInfo = org.safere.Pattern.compile("[éê]").charClassPrefix()
        val nonAscii: StartPlan = plan(null, false, null, nonAsciiScanInfo)
        Assertions.assertThat<Utf8StartAccelerator?>(Utf8StartAccelerator.create(nonAscii, false)).isNotNull()
        Assertions.assertThat<StringStartAccelerator?>(StringStartAccelerator.create(nonAscii, false)).isNotNull()
    }

    @Test
    fun unicodeCharClassPrefixAcceleratesStringAndUtf8() {
        val pattern = org.safere.Pattern.compile("[aéĀ]+")
        val strAcc = pattern.stringStartAccelerator()!!
        Assertions.assertThat<StringStartAccelerator?>(strAcc)
            .isInstanceOf(StringStartAccelerator.CharClass::class.java)
        Assertions.assertThat<AcceleratorPolicy?>(strAcc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS)
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(strAcc, "123\u00e945", 0, false))
            .isEqualTo(3)

        val utf8Acc = pattern.utf8StartAccelerator()!!
        Assertions.assertThat<Utf8StartAccelerator?>(utf8Acc).isInstanceOf(Utf8StartAccelerator.CharClass::class.java)
        Assertions.assertThat<AcceleratorPolicy?>(utf8Acc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS)
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner("123\u00e945"), 0))
            .isEqualTo(3)
    }

    @Test
    fun supplementaryUnicodeCharClassPrefixAcceleratesStringAndUtf8() {
        val pattern = org.safere.Pattern.compile("[(é)|(😀)]")
        val strAcc = pattern.stringStartAccelerator()!!
        Assertions.assertThat<StringStartAccelerator?>(strAcc)
            .isInstanceOf(StringStartAccelerator.CharClass::class.java)
        Assertions.assertThat<AcceleratorPolicy?>(strAcc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS)
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(strAcc, "x\uD83D\uDE00y", 0, false))
            .isEqualTo(1)

        val utf8Acc = pattern.utf8StartAccelerator()!!
        Assertions.assertThat<Utf8StartAccelerator?>(utf8Acc).isInstanceOf(Utf8StartAccelerator.CharClass::class.java)
        Assertions.assertThat<AcceleratorPolicy?>(utf8Acc.policy()).isEqualTo(AcceleratorPolicy.CHAR_CLASS)
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner("x😀y"), 0))
            .isEqualTo(1)
    }

    @Test
    fun unicodeCharClassPrefixResumesAsciiScanningAfterNonAsciiCodePoints() {
        val accelerator = org.safere.Pattern.compile("[aéĀ]+").stringStartAccelerator()!!

        Assertions.assertThat(StringStartAccelerator.findNextCandidate(accelerator, "000©000", 0, false))
            .isEqualTo(-1)
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(accelerator, "000©000Ā", 0, false))
            .isEqualTo(7)
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(accelerator, "000😀000a", 0, false))
            .isEqualTo(8)
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(accelerator, "000😀000a", 4, false))
            .isEqualTo(8)
    }

    @Test
    fun compiledPatternAcceleratorsInSync() {
        val testPatterns = arrayOf<String?>(
            "(?i)needle.*",
            "(?i)a.*",
            "(?i)HTTP://.*",
            "needle.*",
            "[a-z].*",
            "[0-9].*",
            "ab+c.*",
            "[\\[\uFF3B].*"
        )

        val testInputs = arrayOf<String?>(
            "prefix with NEEDLE in middle",
            "prefix with needle in middle",
            "prefix with nEeDlE in middle",
            "prefix with no match",
            "HTTP://EXAMPLE.COM",
            "http://example.com",
            "123 numbers",
            "letters abc",
            "prefix [1.2.3.4] suffix",
            "prefix ［1.2.3.4］ suffix",
            " [1.2.3.4]",
            "[1.2.3.4]"
        )

        for (patStr in testPatterns) {
            val pattern = org.safere.Pattern.compile(patStr)
            val strAcc = pattern.stringStartAccelerator()
            val utf8Acc = pattern.utf8StartAccelerator()

            if (strAcc != null) {
                Assertions.assertThat<Utf8StartAccelerator?>(utf8Acc)
                    .`as`(
                        "Utf8StartAccelerator should match StringStartAccelerator presence for pattern: %s",
                        patStr
                    )
                    .isNotNull()
                val presentUtf8Acc = utf8Acc!!
                Assertions.assertThat<MatchStrategy?>(presentUtf8Acc.policy().strategy)
                    .`as`("Strategies should match for pattern: %s", patStr)
                    .isEqualTo(strAcc.policy().strategy)

                for (input in testInputs) {
                    val strCandidate = StringStartAccelerator.findNextCandidate(strAcc, input, 0, false)
                    val utf8Candidate =
                        Utf8StartAccelerator.findNextCandidate(presentUtf8Acc, utf8Scanner(input!!), 0)
                    Assertions.assertThat(utf8Candidate)
                        .`as`("Candidate indices should match for pattern '%s' on input '%s'", patStr, input)
                        .isEqualTo(strCandidate)
                }
            }
        }
    }

    @Test
    fun questLeadingExpansionBeforeNonAsciiCharClassAcceleratesStringOnly() {
        val pattern = org.safere.Pattern.compile(" ?[\\[\uFF3B](?:(?:\\d+\\.){2,}\\d+(?:, )?)+[\\]\uFF3D]")
        Assertions.assertThat<StringStartAccelerator?>(pattern.stringStartAccelerator()).isNotNull()
        Assertions.assertThat<Utf8StartAccelerator?>(pattern.utf8StartAccelerator()).isNull()
    }

    @Test
    fun twoMemberSmallSetCandidateAcrossProbeWindowEdge() {
        val accelerator =
            StringStartAccelerator.CharClass.create(
                CharClassScanInfo.fromCharClass(
                    CharClassBuilder().addRune('['.code).addRune(0xFF3B).build()
                )!!
            )
        Assertions.assertThat<CharClassScanInfo?>(accelerator.scanInfo)
            .isInstanceOf(CharClassScanInfo.SmallSet::class.java)

        for (member in charArrayOf('[', 0xFF3B.toChar())) {
            for (from in intArrayOf(0, 5)) {
                for (offset in 14..18) {
                    val text = "x".repeat(from + offset) + member + "x".repeat(12)
                    Assertions.assertThat(accelerator.findCandidate(text, from, false))
                        .`as`("member %s at %d from %d", member, from + offset, from)
                        .isEqualTo(from + offset)
                }
            }
        }

        // Increasing positions: the nearer member wins at each step, whichever side of the scalar
        // prologue or the first search window it falls on.
        val fullWidth = 0xFF3B.toChar().toString()
        val text = "xxx" + fullWidth + "x".repeat(20) + "[" + "xxxx" + fullWidth
        var from = 0
        for (expected in intArrayOf(3, 24, 29)) {
            val candidate = accelerator.findCandidate(text, from, false)
            Assertions.assertThat(candidate).`as`("from %d", from).isEqualTo(expected)
            from = candidate + 1
        }
        Assertions.assertThat(accelerator.findCandidate(text, from, false)).isEqualTo(-1)
    }

    @Test
    fun compiledPatternPoliciesMatchExpectedAccelerators() {
        val literalPat = org.safere.Pattern.compile("abc")
        Assertions.assertThat<StringStartAccelerator?>(literalPat.stringStartAccelerator()).isNotNull()
        Assertions.assertThat<Utf8StartAccelerator?>(literalPat.utf8StartAccelerator()).isNotNull()
        Assertions.assertThat<AcceleratorPolicy?>(literalPat.stringStartAccelerator()!!.policy())
            .isEqualTo(AcceleratorPolicy.LITERAL)
        Assertions.assertThat<AcceleratorPolicy?>(literalPat.utf8StartAccelerator()!!.policy())
            .isEqualTo(AcceleratorPolicy.LITERAL)

        val caseInsensitivePat = org.safere.Pattern.compile("(?i)abc")
        Assertions.assertThat<StringStartAccelerator?>(caseInsensitivePat.stringStartAccelerator()).isNotNull()
        Assertions.assertThat<Utf8StartAccelerator?>(caseInsensitivePat.utf8StartAccelerator()).isNotNull()
        Assertions.assertThat<MatchStrategy?>(caseInsensitivePat.utf8StartAccelerator()!!.policy().strategy)
            .isEqualTo(MatchStrategy.LITERAL)

        val charClassPat = org.safere.Pattern.compile("[0-2][a-z]+")
        Assertions.assertThat<StringStartAccelerator?>(charClassPat.stringStartAccelerator()).isNotNull()
        Assertions.assertThat<Utf8StartAccelerator?>(charClassPat.utf8StartAccelerator()).isNotNull()
        Assertions.assertThat<AcceleratorPolicy?>(charClassPat.stringStartAccelerator()!!.policy())
            .isEqualTo(AcceleratorPolicy.CHAR_CLASS)
        Assertions.assertThat<AcceleratorPolicy?>(charClassPat.utf8StartAccelerator()!!.policy())
            .isEqualTo(AcceleratorPolicy.CHAR_CLASS)

        val broadCharClassPat = org.safere.Pattern.compile("[0-9][a-z]+")
        Assertions.assertThat<StringStartAccelerator?>(broadCharClassPat.stringStartAccelerator()).isNull()
        Assertions.assertThat<Utf8StartAccelerator?>(broadCharClassPat.utf8StartAccelerator()).isNull()

        val fixedOffsetPat = org.safere.Pattern.compile("..needle")
        Assertions.assertThat<StringStartAccelerator?>(fixedOffsetPat.stringStartAccelerator()).isNotNull()
        Assertions.assertThat<Utf8StartAccelerator?>(fixedOffsetPat.utf8StartAccelerator()).isNotNull()
        Assertions.assertThat<AcceleratorPolicy?>(fixedOffsetPat.stringStartAccelerator()!!.policy())
            .isEqualTo(AcceleratorPolicy.LITERAL)
        Assertions.assertThat<AcceleratorPolicy?>(fixedOffsetPat.utf8StartAccelerator()!!.policy())
            .isEqualTo(AcceleratorPolicy.LITERAL)

        val unacceleratedPat = org.safere.Pattern.compile(".*")
        Assertions.assertThat<StringStartAccelerator?>(unacceleratedPat.stringStartAccelerator()).isNull()
        Assertions.assertThat<Utf8StartAccelerator?>(unacceleratedPat.utf8StartAccelerator()).isNull()
    }

    @Test
    fun multiLiteralWithSharedPrefixReturnsNullAndFallsBackToTeddyOrNone() {
        val info = MultiLiteralInfo.create(arrayOf<String?>("cat", "car"))
        Assertions.assertThat<MultiLiteralInfo?>(info)
            .`as`("MultiLiteralInfo must reject colliding initial anchor chars").isNull()

        val distinctInfo = MultiLiteralInfo.create(arrayOf<String?>("cat", "dog", "fox"))!!
        Assertions.assertThat<MultiLiteralInfo?>(distinctInfo).isNotNull()
        Assertions.assertThat<String?>(distinctInfo.literals).containsExactly("cat", "dog", "fox")
    }

    @Test
    fun literalAlternationRetainsStringCharacterClassFallback() {
        val pattern = org.safere.Pattern.compile("apple|banana|cherry")

        Assertions.assertThat<StartPlan?>(pattern.startPlan())
            .isInstanceOf(StartPlan.MultiLiteral::class.java)
        Assertions.assertThat<StringStartAccelerator?>(pattern.stringStartAccelerator())
            .isInstanceOf(StringStartAccelerator.CharClass::class.java)
    }

    @Test
    fun multiLiteralScanReturnsUnsupportedWhenWorkLimitExhaustedOnNoise() {
        if (!isVectorApiAvailable) {
            return
        }
        val literals: Array<String?> = arrayOf<String?>("APPLE", "BANANA", "CHERRY")
        val info = MultiLiteralInfo.create(literals)!!
        Assertions.assertThat<MultiLiteralInfo?>(info).isNotNull()

        val denseNoise = "A B C A B C ".repeat(1000).toByteArray(StandardCharsets.UTF_8)

        val result =
            ByteVectorScan.indexOfMultiLiteral(
                denseNoise,
                0,
                denseNoise.size,
                info.literals!!.requireNoNulls(),
                info.anchorChars!!,
                info.anchorOffsets!!,
                info.anchorRanges!!,
                info.minLength,
                0
            )

        Assertions.assertThat(result)
            .`as`("Dense candidate false positives must exhaust WorkLimit and return UNSUPPORTED")
            .isEqualTo(VectorScanProvider.UNSUPPORTED)
    }

    @Test
    fun adaptiveTeddySelectionUsesEstimatedCandidateVerificationCost() {
        Assertions.assertThat(MultiLiteralSelectionPolicy.prefersTeddy(5L * 7, 64)).isTrue()
        Assertions.assertThat(MultiLiteralSelectionPolicy.prefersTeddy(4L * 7, 64)).isFalse()
        Assertions.assertThat(MultiLiteralSelectionPolicy.prefersTeddy(16L * 7, 256)).isFalse()
        Assertions.assertThat(MultiLiteralSelectionPolicy.prefersTeddy(19L * 7, 256)).isTrue()
        Assertions.assertThat(MultiLiteralSelectionPolicy.shouldObserve(255)).isTrue()
        Assertions.assertThat(MultiLiteralSelectionPolicy.shouldObserve(256)).isFalse()
    }

    @Test
    fun adaptiveTeddySelectionPreservesTheEarliestLiteralMatch() {
        if (!isVectorApiAvailable) {
            return
        }
        val literals: Array<String?> = arrayOf<String?>("blossom", "sparkling", "twilight")
        val info = MultiLiteralInfo.create(literals)!!
        val teddyModel = TeddyModel.compile(literals, 64)
        Assertions.assertThat<MultiLiteralInfo?>(info).isNotNull()
        Assertions.assertThat<TeddyModel?>(teddyModel).isNotNull()
        val prefix = "b s t ".repeat(12)
        val input = (prefix + "sparkling then blossom").toByteArray(StandardCharsets.UTF_8)

        val result =
            ByteVectorScan.indexOfMultiLiteral(
                input,
                0,
                input.size,
                info.literals!!.requireNoNulls(),
                info.anchorChars!!,
                info.anchorOffsets!!,
                info.anchorRanges!!,
                info.minLength,
                teddyModel,
                0
            )

        Assertions.assertThat(result).isEqualTo(prefix.length)
    }

    @Test
    fun discreteOffsetsResolveTheExactStartAgainstTheLeadingClass() {
        val plan = org.safere.Pattern.compile("https?://\\S*").startPlan()
        Assertions.assertThat<StartPlan?>(plan).isInstanceOf(StartPlan.FixedOffset::class.java)

        // `://` sits four characters after `h` in `http://` and five in `https://`. Retreating by the
        // larger offset alone lands on the space before `http://` and wakes the engine at a position
        // the leading class already excludes.
        val text = "see http://a and https://b"

        val strAcc = StringStartAccelerator.create(plan, false)!!
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(strAcc, text, 0, false)).isEqualTo(4)
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(strAcc, text, 5, false)).isEqualTo(17)

        val utf8Acc = Utf8StartAccelerator.create(plan, false)!!
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner(text), 0)).isEqualTo(4)
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner(text), 5)).isEqualTo(17)
    }

    @Test
    fun discreteOffsetResolutionStaysBelowStartsBelongingToLaterLiterals() {
        val plan = org.safere.Pattern.compile("(aq|b[a-z]{9})z").startPlan()
        Assertions.assertThat<StartPlan?>(plan).isInstanceOf(StartPlan.FixedOffset::class.java)

        // Offsets 2 and 10 are far apart, so the starts a literal occurrence implies are not ordered by
        // the occurrence itself: the `z` at index 5 admits only the start at 3, while the `z` at index
        // 10 starts the match at 0. The caller treats the result as a floor, so it has to report 0.
        val text = "bxxaxzxxxxz"

        val strAcc = StringStartAccelerator.create(plan, false)!!
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(strAcc, text, 0, false)).isZero()

        val utf8Acc = Utf8StartAccelerator.create(plan, false)!!
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner(text), 0)).isZero()

        // Past that start nothing can match, and the leading class is enough to prove it without
        // handing the engine a candidate.
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(strAcc, text, 5, false)).isEqualTo(-1)
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner(text), 5)).isEqualTo(-1)
    }

    @Test
    fun leadingWhitespaceCharClassExpansionAcceleratesStringAndUtf8() {
        val pattern = org.safere.Pattern.compile("\\s*[\\[\\uff3b]\\d+[\\]\\uff3d]")
        val plan = pattern.startPlan()
        Assertions.assertThat<StartPlan?>(plan).isInstanceOf(StartPlan.LeadingExpansion::class.java)

        val strAcc = pattern.stringStartAccelerator()!!
        Assertions.assertThat<StringStartAccelerator?>(strAcc)
            .isInstanceOf(StringStartAccelerator.LeadingExpansion::class.java)
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(strAcc, "hello   [123] world", 0, false))
            .isEqualTo(5)
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(strAcc, "hello [123] world", 0, false))
            .isEqualTo(5)
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(strAcc, "[123] world", 0, false))
            .isEqualTo(0)
        Assertions.assertThat(
            StringStartAccelerator.findNextCandidate(strAcc, "hello world without match", 0, false)
        )
            .isEqualTo(-1)

        val utf8Acc = pattern.utf8StartAccelerator()!!
        Assertions.assertThat<Utf8StartAccelerator?>(utf8Acc)
            .isInstanceOf(Utf8StartAccelerator.LeadingExpansion::class.java)
        Assertions.assertThat(
            Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner("hello   [123] world"), 0)
        )
            .isEqualTo(5)
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner("hello [123] world"), 0))
            .isEqualTo(5)
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner("[123] world"), 0))
            .isEqualTo(0)
        Assertions.assertThat(
            Utf8StartAccelerator.findNextCandidate(
                utf8Acc, utf8Scanner("hello world without match"), 0
            )
        )
            .isEqualTo(-1)
    }

    @Test
    fun leadingWhitespaceLiteralExpansionAcceleratesStringAndUtf8() {
        val pattern = org.safere.Pattern.compile("\\s+https?://\\w+")
        val plan = pattern.startPlan()
        Assertions.assertThat<StartPlan?>(plan).isInstanceOf(StartPlan.LeadingExpansion::class.java)

        val strAcc = pattern.stringStartAccelerator()!!
        Assertions.assertThat<StringStartAccelerator?>(strAcc)
            .isInstanceOf(StringStartAccelerator.LeadingExpansion::class.java)
        // Requires at least 1 leading whitespace
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(strAcc, "visit  http://example", 0, false))
            .isEqualTo(5)
        Assertions.assertThat(
            StringStartAccelerator.findNextCandidate(
                strAcc, "http://example without leading space", 0, false
            )
        )
            .isEqualTo(-1)

        val utf8Acc = pattern.utf8StartAccelerator()!!
        Assertions.assertThat<Utf8StartAccelerator?>(utf8Acc)
            .isInstanceOf(Utf8StartAccelerator.LeadingExpansion::class.java)
        Assertions.assertThat(
            Utf8StartAccelerator.findNextCandidate(
                utf8Acc, utf8Scanner("visit  http://example"), 0
            )
        )
            .isEqualTo(5)
        Assertions.assertThat(
            Utf8StartAccelerator.findNextCandidate(
                utf8Acc, utf8Scanner("http://example without leading space"), 0
            )
        )
            .isEqualTo(-1)
    }

    @Test
    fun leadingBoundedUnicodeExpansionAcceleratesStringAndUtf8() {
        val pattern = org.safere.Pattern.compile("[\\u00e9\\u00e8]+:target")
        val plan = pattern.startPlan()
        Assertions.assertThat<StartPlan?>(plan).isInstanceOf(StartPlan.LeadingExpansion::class.java)
        val le =
            plan as StartPlan.LeadingExpansion
        Assertions.assertThat(le.minRepetition).isEqualTo(1)
        Assertions.assertThat(le.maxRepetition).isEqualTo(Int.MAX_VALUE)

        val strAcc = pattern.stringStartAccelerator()!!
        Assertions.assertThat<StringStartAccelerator?>(strAcc)
            .isInstanceOf(StringStartAccelerator.LeadingExpansion::class.java)
        // 5 \u00e9 chars before :target
        Assertions.assertThat(
            StringStartAccelerator.findNextCandidate(
                strAcc, "prefix\u00e9\u00e9\u00e9\u00e9\u00e9:target", 0, false
            )
        )
            .isEqualTo(6)
        // 1 \u00e9 char
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(strAcc, "prefix\u00e9:target", 0, false))
            .isEqualTo(6)
        // 0 \u00e9 chars -> fails minRepetition check (1)
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(strAcc, "prefix:target", 0, false))
            .isEqualTo(-1)

        val utf8Acc = pattern.utf8StartAccelerator()!!
        Assertions.assertThat<Utf8StartAccelerator?>(utf8Acc)
            .isInstanceOf(Utf8StartAccelerator.LeadingExpansion::class.java)
        // In UTF-8, "prefix" is 6 bytes. Each \u00e9 is 2 bytes (0xC3 0xA9).
        // ":target" starts at byte 6 + (5 * 2) = 16.
        // Leftmost \u00e9 is at byte 6.
        Assertions.assertThat(
            Utf8StartAccelerator.findNextCandidate(
                utf8Acc, utf8Scanner("prefix\u00e9\u00e9\u00e9\u00e9\u00e9:target"), 0
            )
        )
            .isEqualTo(6)
        // 1 code point -> byte 6
        Assertions.assertThat(
            Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner("prefix\u00e9:target"), 0)
        )
            .isEqualTo(6)
        // 0 code points -> fails minRepetition
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner("prefix:target"), 0))
            .isEqualTo(-1)
    }

    @Test
    fun leadingSupplementaryUnicodeExpansionAcceleratesStringAndUtf8() {
        // Supplementary code point class: U+1F600, U+1F601 (Grinning Face, Beaming Face)
        val pattern = org.safere.Pattern.compile("[\\x{1F600}\\x{1F601}]+:target")
        val plan = pattern.startPlan()
        Assertions.assertThat<StartPlan?>(plan).isInstanceOf(StartPlan.LeadingExpansion::class.java)

        val emoji4 =
            StringBuilder("abc")
                .appendCodePoint(0x1F600)
                .appendCodePoint(0x1F601)
                .appendCodePoint(0x1F600)
                .appendCodePoint(0x1F601)
                .append(":target")
                .toString()
        val noEmoji = "abc:target"

        val strAcc = pattern.stringStartAccelerator()!!
        // In UTF-16, "abc" is 3 chars. Each emoji is 2 chars (surrogate pair).
        // "abc...:target" -> Leftmost emoji is at index 3.
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(strAcc, emoji4, 0, false)).isEqualTo(3)
        // 0 emoji -> fails minRepetition (1)
        Assertions.assertThat(StringStartAccelerator.findNextCandidate(strAcc, noEmoji, 0, false)).isEqualTo(-1)

        val utf8Acc = pattern.utf8StartAccelerator()!!
        // In UTF-8, "abc" is 3 bytes. Each emoji is 4 bytes.
        // Leftmost emoji is at byte 3.
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner(emoji4), 0))
            .isEqualTo(3)
        Assertions.assertThat(Utf8StartAccelerator.findNextCandidate(utf8Acc, utf8Scanner(noEmoji), 0))
            .isEqualTo(-1)
    }

    @Test
    fun consecutiveLeadingRepetitionsDoNotOverflowDuringCompilation() {
        val regex = StringBuilder()
        for (i in 0..5000 - 1) {
            regex.append(if ((i and 1) == 0) "[ab]*" else "[cd]*")
        }
        regex.append('z')

        Assertions.assertThatCode(ThrowingCallable { org.safere.Pattern.compile(regex.toString()) })
            .doesNotThrowAnyException()
    }

    @Test
    fun nullableLeadingExpansionVerifiesCandidateAtInnerMatch() {
        val pattern = org.safere.Pattern.compile("(\\s*)# [Nn][Oo][Qq][Aa]")
        val plan = pattern.startPlan()
        Assertions.assertThat<StartPlan?>(plan).isInstanceOf(StartPlan.LeadingExpansion::class.java)
        val le =
            plan as StartPlan.LeadingExpansion
        Assertions.assertThat(le.minRepetition).isEqualTo(0)
        Assertions.assertThat(le.hasLeadingAssertions).isFalse()

        val strAcc = pattern.stringStartAccelerator()
        Assertions.assertThat<StringStartAccelerator?>(strAcc)
            .isInstanceOf(StringStartAccelerator.LeadingExpansion::class.java)
        val strLe =
            strAcc as StringStartAccelerator.LeadingExpansion
        Assertions.assertThat(strLe.canVerifyAtInner()).isTrue()

        // Matching lines: verify correct leftmost start and capture groups
        val m1 = pattern.matcher("        # noqa: E501")
        Assertions.assertThat(m1.find()).isTrue()
        Assertions.assertThat(m1.start()).isEqualTo(0)
        Assertions.assertThat(m1.group(1)).isEqualTo("        ")

        // Non-matching indented comment: rejected
        val m2 = pattern.matcher("        # This is a comment, not noqa")
        Assertions.assertThat(m2.find()).isFalse()

        // Multiple candidates on same line: leftmost match found
        val m3 = pattern.matcher("    # foo    # noqa")
        Assertions.assertThat(m3.find()).isTrue()
        Assertions.assertThat(m3.start()).isEqualTo(9)
        Assertions.assertThat(m3.group(1)).isEqualTo("    ")

        // UTF-8 matching
        val u1 = pattern.matcher(Utf8Input.validated("        # noqa: E501".toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(u1.find()).isTrue()
        Assertions.assertThat(u1.start()).isEqualTo(0)
        Assertions.assertThat(u1.start(1)).isEqualTo(0)
        Assertions.assertThat(u1.end(1)).isEqualTo(8)

        val u2 =
            pattern.matcher(Utf8Input.validated("        # This is a comment".toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(u2.find()).isFalse()
    }

    @Test
    fun leadingExpansionWithLeadingBoundaryPreservesAssertions() {
        val pattern = org.safere.Pattern.compile("\\b[a-z]*target")
        val plan = pattern.startPlan()
        Assertions.assertThat<StartPlan?>(plan).isInstanceOf(StartPlan.LeadingExpansion::class.java)
        val le =
            plan as StartPlan.LeadingExpansion
        Assertions.assertThat(le.minRepetition).isEqualTo(0)
        Assertions.assertThat(le.hasLeadingAssertions).isTrue()

        val strAcc = pattern.stringStartAccelerator()
        Assertions.assertThat<StringStartAccelerator?>(strAcc)
            .isInstanceOf(StringStartAccelerator.LeadingExpansion::class.java)
        val strLe =
            strAcc as StringStartAccelerator.LeadingExpansion
        Assertions.assertThat(strLe.canVerifyAtInner()).isFalse()

        val m = pattern.matcher("prefix wordtarget suffix")
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(7) // "wordtarget" starts at word boundary index 7
    }

    @Test
    fun leadingExpansionExpandsBackwardAcrossMultiByteCharacters() {
        // `\s` is single-unit, so the other tests always take the singleUnitCodePointBefore fast
        // path. U+00E0..U+00FF are two bytes each in UTF-8, which forces decodeBackward.
        val pattern = org.safere.Pattern.compile("[\\u00e0-\\u00ff]*RARE")
        Assertions.assertThat<StartPlan?>(pattern.startPlan())
            .isInstanceOf(StartPlan.LeadingExpansion::class.java)
        val u8Acc = pattern.utf8StartAccelerator()
        Assertions.assertThat<Utf8StartAccelerator?>(u8Acc)
            .isInstanceOf(Utf8StartAccelerator.LeadingExpansion::class.java)
        Assertions.assertThat((u8Acc as Utf8StartAccelerator.LeadingExpansion).canVerifyAtInner()).isTrue()

        // Three two-byte characters precede the literal, so the match starts at byte 0 of 10.
        val m1 = pattern.matcher(Utf8Input.validated("\u00e0\u00e8\u00ecRARE".toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(m1.find()).isTrue()
        Assertions.assertThat(m1.start()).isEqualTo(0)
        Assertions.assertThat(m1.end()).isEqualTo(10)

        // Backward expansion stops at the ASCII 'x', which the leading class excludes.
        val m2 =
            pattern.matcher(Utf8Input.validated("x\u00e0\u00e8\u00ecRARE".toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(m2.find()).isTrue()
        Assertions.assertThat(m2.start()).isEqualTo(1)

        // A rejected candidate must not swallow the rest of the input: the real match is later.
        val m3 =
            pattern.matcher(Utf8Input.validated("\u00e0\u00e8\u00ecNOPE \u00e0RARE".toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(m3.find()).isTrue()
        Assertions.assertThat(m3.start()).isEqualTo(11)
        Assertions.assertThat(m3.end()).isEqualTo(17)

        val m4 = pattern.matcher(Utf8Input.validated("\u00e0\u00e8\u00ecNOPE".toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(m4.find()).isFalse()
    }

    @Test
    fun leadingExpansionWithUnicodeFoldedLiteralUsesScalarFallback() {
        val pattern = org.safere.Pattern.compile("(?iu)[0-9]*Шерлок Холмс")
        Assertions.assertThat<StartPlan?>(pattern.startPlan())
            .isInstanceOf(StartPlan.LeadingExpansion::class.java)
        Assertions.assertThat<Utf8StartAccelerator?>(pattern.utf8StartAccelerator()).isNull()

        val matcher = pattern.matcher(Utf8Input.validated("12шЕРЛОК ХОЛМС".toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isZero()
    }

    @Test
    fun leadingExpansionWithNonNullableRepetitionKeepsTheScalarPath() {
        val pattern = org.safere.Pattern.compile("\\s+# noqa")
        val plan = pattern.startPlan()
        Assertions.assertThat<StartPlan?>(plan).isInstanceOf(StartPlan.LeadingExpansion::class.java)
        val le =
            plan as StartPlan.LeadingExpansion
        Assertions.assertThat(le.minRepetition).isEqualTo(1)
        Assertions.assertThat(le.hasLeadingAssertions).isFalse()

        // minRepetition > 0 breaks the zero-repetition step of the rejection argument, so the
        // candidate must not be verified at the inner match even with no leading assertions.
        Assertions.assertThat(
            (pattern.stringStartAccelerator() as StringStartAccelerator.LeadingExpansion)
                .canVerifyAtInner()
        )
            .isFalse()
        Assertions.assertThat(
            (pattern.utf8StartAccelerator() as Utf8StartAccelerator.LeadingExpansion)
                .canVerifyAtInner()
        )
            .isFalse()

        val m1 = pattern.matcher("    # noqa")
        Assertions.assertThat(m1.find()).isTrue()
        Assertions.assertThat(m1.start()).isEqualTo(0)

        // Verifying at the inner match would accept this; the mandatory whitespace is absent.
        val m2 = pattern.matcher("# noqa")
        Assertions.assertThat(m2.find()).isFalse()
    }

    @Test
    fun nullableLeadingExpansionVerifiesCandidateAtInnerMatchWhenSplitting() {
        // Pattern.split reaches Matcher.findSplitPositions -> findNextMatchPacked, a third copy of
        // the candidate-verification block that the find() tests never execute.
        val pattern = org.safere.Pattern.compile("\\s*# noqa")

        Assertions.assertThat<String?>(pattern.split("alpha    # noqa beta")).containsExactly("alpha", " beta")

        // The `# nope` candidate must be rejected at the inner match and left in the output.
        Assertions.assertThat<String?>(pattern.split("alpha    # nope beta   # noqa gamma"))
            .containsExactly("alpha    # nope beta", " gamma")
    }

    companion object {
        private fun plan(
            prefix: String?,
            prefixFoldCase: Boolean,
            fixedOffsetLiteral: org.safere.Pattern.FixedOffsetLiteral?,
            charClassPrefix: CharClassScanInfo?
        ): StartPlan {
            if (prefix != null) {
                return StartPlan.Literal(prefix, prefixFoldCase)
            }
            if (fixedOffsetLiteral != null) {
                return StartPlan.FixedOffset(fixedOffsetLiteral, charClassPrefix)
            }
            if (charClassPrefix != null) {
                return StartPlan.CharClass(charClassPrefix)
            }
            return StartPlan.None.INSTANCE
        }

        private fun utf8Scanner(text: String): Utf8InputScanner {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            return Utf8InputScanner(bytes, 0, bytes.size)
        }

        private val isVectorApiAvailable: Boolean
            get() {
                try {
                    Class.forName("jdk.incubator.vector.ByteVector")
                    return true
                } catch (e: ClassNotFoundException) {
                    return false
                } catch (e: LinkageError) {
                    return false
                }
            }
    }
}
