// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.safere.Matcher
import org.safere.Pattern
import org.safere.fuzz.FuzzSupport.MatcherPair
import java.util.List
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.min

class FindSequenceFuzzer {
    @Test
    fun deferredAssertionAlternativesPreserveSearchStartMatches() {
        for (context in SEARCH_START_CONTEXTS) {
            for (suffix in context.suffixes!!) {
                for (quantifier in mutableListOf<String?>("?", "*", "{0,2}")) {
                    for (captures in mutableListOf<Boolean?>(false, true)) {
                        for (assertionFirst in mutableListOf<Boolean?>(false, true)) {
                            for (padding in mutableListOf<String?>("", "zzzz")) {
                                assertSearchStartAssertionFinds(
                                    context, suffix, quantifier, captures!!, assertionFirst!!, padding!!
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun validTerminalEmptyMatchIsNotExcluded() {
        val safeRe = Pattern.compile("").matcher("")
        val jdk = java.util.regex.Pattern.compile("").matcher("")
        Assertions.assertThat(jdk.find()).isTrue()
        val pair = MatcherPair("", 0, "", safeRe, jdk)
        Assertions.assertThatThrownBy(ThrowingCallable { pair.hasMatch() }).isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun terminalEmptyFindInvalidatesMatchState() {
        for (regex in mutableListOf<String?>("x*", "(x?)", "", "$", "\\b")) {
            for (input in mutableListOf<String?>("", "x", "yxxy")) {
                for (useRegion in mutableListOf<Boolean?>(false, true)) {
                    val matcher = FuzzSupport.compileOrSkip(regex, 0).matcher(input)
                    if (useRegion) {
                        matcher.region(0, input!!.length / 2)
                    }
                    while (matcher.find()) {
                        Assertions.assertThat(matcher.hasMatch()).isTrue()
                    }
                    Assertions.assertThat(matcher.hasMatch()).isFalse()
                }
            }
        }
    }

    @Test
    fun deferredAssertionsPreserveFullMatchAlternatives() {
        for (assertion in mutableListOf<String?>("\\b", "\\B", "(?m:$)")) {
            for (atom in mutableListOf<String?>("a", "!", "\n", "α", "😀")) {
                val literal = java.util.regex.Pattern.quote(atom)
                val regex = "(?U)" + literal + "*(?:" + literal + literal + "|" + assertion + ")"
                FuzzSupport.compileOrSkip(regex, 0).matcher(atom!!.repeat(2)).matches()
            }
        }
    }

    @Test
    fun reusedMultilineDollarKeepsCrLfAtomic() {
        val pattern = FuzzSupport.compileOrSkip("\\W*(?m:$)", 0)
        pattern.matcher(" \na").find()
        val matcher = pattern.matcher("xb\r\n" + "a".repeat(30))
        while (matcher.find()) {
            matcher.start()
            matcher.end()
        }
    }

    @Test
    fun anchoredFailureAfterSuccessfulMatchRegression() {
        assertAnchoredContinuation("a", "a a", false, false)
        assertAnchoredContinuation("a*", "a!", true, false)
        assertAnchoredContinuation("a", "ba a", false, true)
        assertAnchoredContinuation("a*", "!a", false, false)
        assertAnchoredContinuation("^[ab]*", "!a😀b!", false, true)
        assertAnchoredContinuation("\\A(a?)", "!a", true, true)
    }

    @Test
    fun delimitedPrefixBeforeRequiredSuffixRegression() {
        val pattern =
            FuzzSupport.compileOrSkip("[^{']*(?:'[^']*'[^{']*)*\\{([^}]*)\\}", 0)

        pattern.matcher("Foo '{0}' Bar: {0}").find()
    }

    @Test
    fun dfaBoundSemanticsRegressions() {
        assertFindSequence("$", "x".repeat(512) + "a\n")
        assertFindSequence("$", "x".repeat(512) + "a\r\n")
        assertFindSequence("$", "x".repeat(512) + "a\u2028")
        assertFindSequence("(?:\\B{1}|a).a?", "ab".repeat(300) + "c")
        assertFindSequence("(?:\\B{1}|a).a?$", "x".repeat(512) + "ab")
        assertFindSequence("(?:a+?|(?:[^x])*)$", "x".repeat(512) + "a\n")
        assertFindSequence("(?:a{2,}|(?:.|\\B){1,2}){1,2}", "baax")
    }

    @Test
    fun unicodeBoundaryTransitionClassesRegression() {
        for (word in mutableListOf<String?>("α", "中", "\u0301", "\u0660", "\u200c")) {
            assertFindSequence("(?U).\\B", " " + word + "`\u180e")
            assertFindSequence("(?U)\\b.", "x".repeat(300) + " " + word + "`\u180e")
        }
    }

    @Test
    fun nullableLoopFindsInLargeInputsPreservePriorityAndCaptures() {
        for (atom in mutableListOf<String?>("a", "α", "😀")) {
            val literal = java.util.regex.Pattern.quote(atom)
            for (length in mutableListOf<Int?>(0, 4, 64, 200)) {
                val segment = "S" + atom!!.repeat(length!!) + "E"
                val input = segment + "x".repeat(32000) + segment + "x".repeat(32000)
                assertFindSequence("S((?:" + literal + "?)*)E", input)
                assertFindSequence("S((?:" + literal + "?)*?)E", input)
            }
        }
    }

    @Test
    fun leadingExpansionDoesNotCrossSplitSurrogateFindStart() {
        val pattern = FuzzSupport.compileOrSkip("[\\x{1F600}]+b", 0)

        pattern.matcher("😀bX😀b").find(1)
    }

    @Test
    fun interiorClassCandidatesPreserveFindSequences() {
        for (prefix in INTERIOR_CLASS_PREFIXES) {
            for (atom in mutableListOf<String?>("[0-9]", "[A-F0-9]")) {
                for (quantifier in mutableListOf<String?>("+", "{4,}", "{1,3}?")) {
                    for (noise in List.of<String?>(
                        "é😀 words 1111x 1x aa1234Y ".repeat(100),
                        "1234567890".repeat(1000)
                    )) {
                        assertFindSequence(
                            prefix + "(" + atom + quantifier + ")",
                            noise + "   A1234 e1234 aa12345 😀1234 éé12345 x1234 aé12345"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun scopedEndAnchorLineModes() {
        for (anchor in mutableListOf<String?>("$", "\\Z")) {
            for (terminator in mutableListOf<String?>("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029")) {
                for (mode in mutableListOf<String?>("d", "-d")) {
                    for (flags in intArrayOf(0, java.util.regex.Pattern.UNIX_LINES)) {
                        val pattern =
                            FuzzSupport.compileOrSkip("(?:foo|bar)(?" + mode + ":" + anchor + ")", flags)
                        val matcher = pattern.matcher("foo" + terminator)
                        while (matcher.find()) {
                            matcher.start()
                            matcher.end()
                            matcher.group()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun mixedCrLfAndStandaloneLfCacheRegressions() {
        for (regex in mutableListOf<String?>("(?-d:(?m:$))(?dm:$)", "(?dm:$)(?-d:(?m:$))")) {
            val pattern =
                FuzzSupport.compileOrSkip(regex, java.util.regex.Pattern.UNIX_LINES)
            for (input in mutableListOf<String?>("\r\na\na", "\na\r\na", "a\na", "\r\na\na")) {
                val matcher = pattern.matcher(input)
                while (matcher.find()) {
                    matcher.start()
                    matcher.end()
                }
            }
        }
    }

    @Test
    fun resetAndThreadHandoffKeepsNfaCachesSeparate() {
        for (regex in mutableListOf<String?>("\\X", "(\\X)", "\\X+", "\\X+?")) {
            assertResetHandoffCacheIsolation(regex!!, "aé😀", "e\u0301x")
        }
    }

    @FuzzTest(maxDuration = "30s")
    fun sequence(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    @JvmRecord
    private data class SearchStartContext(
        val atom: String,
        val leadingText: String,
        val consuming: String?,
        val assertion: String?,
        val suffixes: MutableList<String>?
    )

    companion object {
        private val LINE_TERMINATORS = mutableListOf<String?>("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029")

        private val INTERIOR_CLASS_PREFIXES =
            mutableListOf<String?>(" ", "e", "[a-z]{2}", "[ \\t\\r\\n]{3}", " [é😀]", " [aé]{2}", " .", " .{2}", " \\X")

        private val SEARCH_START_CONTEXTS: MutableList<SearchStartContext> = List.of<SearchStartContext?>(
            SearchStartContext("\\w", "1", "b", "\\b", mutableListOf<String?>("-", "!", "😀")),
            SearchStartContext("x", "x", "x", "\\B", mutableListOf<String?>("b", "c", "α")),
            SearchStartContext("\\n", "\n", "\n", "(?m:^)", mutableListOf<String?>("b", "c", "α"))
        )

        private fun assertAnchoredContinuation(
            regex: String?, input: String, initialLookingAt: Boolean, attemptLookingAt: Boolean
        ) {
            val matcher = FuzzSupport.compileOrSkip(regex, 0).matcher(input)
            if (initialLookingAt) {
                matcher.lookingAt()
            } else {
                matcher.find()
            }
            if (attemptLookingAt) {
                matcher.lookingAt()
            } else {
                matcher.matches()
            }
            matcher.find()
            matcher.find()
        }

        private fun assertResetHandoffCacheIsolation(
            regex: String, warmInput: String?, newInput: String
        ) {
            try {
                val pattern = Pattern.compile(regex)
                val transferred = pattern.matcher(warmInput)
                Assertions.assertThat(transferred.find()).isTrue()
                transferred.reset(newInput)
                val retained = pattern.matcher(warmInput)
                Assertions.assertThat(retained.find()).isTrue()
                // Check the ownership invariant deterministically rather than racing the two engines.
                val engine = Matcher::class.java.getDeclaredField("cachedNfa")
                engine.setAccessible(true)
                val retainedEngine = engine.get(retained)
                Assertions.assertThat<Any?>(retainedEngine).isNotNull()
                Executors.newSingleThreadExecutor().use { worker ->
                    val transferredEngine =
                        worker
                            .submit<Any?>(
                                Callable {
                                    Assertions.assertThat(transferred.find()).isTrue()
                                    val oracle = java.util.regex.Pattern.compile(regex).matcher(newInput)
                                    Assertions.assertThat(oracle.find()).isTrue()
                                    Assertions.assertThat(transferred.start()).isEqualTo(oracle.start())
                                    Assertions.assertThat(transferred.end()).isEqualTo(oracle.end())
                                    Assertions.assertThat(transferred.group()).isEqualTo(oracle.group())
                                    engine.get(transferred)
                                })
                            .get()
                    Assertions.assertThat<Any?>(transferredEngine).isNotNull().isNotSameAs(retainedEngine)
                }
            } catch (e: Exception) {
                if (e is InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                throw AssertionError("Reset/handoff cache isolation failed", e)
            }
        }

        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            val regex: String?
            val flags: Int
            var input: String
            var splitSurrogateFindStart = false
            var warmLineEndCache = false
            var warmInput: String? = null
            when (data.consumeInt(0, 18)) {
                0 -> {
                    regex = nestedCapturingGroups(data.consumeInt(0, 512)) + "*"
                    flags = 0
                    input = if (data.consumeBoolean()) "a" else "a!"
                }

                1 -> {
                    val suffix = if (data.consumeBoolean()) "\\{([^}]*)\\}" else "END"
                    regex = "[^']*(?:'[^']*'[^']*)*" + suffix
                    flags = 0
                    input = if (data.consumeBoolean()) "Foo '{0}' Bar: {0}" else "prefix 'not END' suffix END"
                }

                2 -> {
                    regex = data.consumeString(256)
                    flags = FuzzSupport.consumeFlags(data)
                    input = data.consumeString(2048)
                }

                3 -> {
                    regex = "$"
                    flags = 0
                    val terminator =
                        data.pickValue<String?>(
                            mutableListOf<String?>(
                                "\n",
                                "\r",
                                "\r\n",
                                "\u0085",
                                "\u2028",
                                "\u2029"
                            )
                        )
                    input = data.consumeString(64) + "a" + terminator
                }

                4 -> {
                    regex = "(?:\\B{1}|a).a?"
                    flags = 0
                    input = "ab".repeat(data.consumeInt(0, 700)) + data.consumeString(2)
                }

                5 -> {
                    regex = "(?:a+?|(?:[^x])*)$"
                    flags = 0
                    input = "x".repeat(data.consumeInt(0, 700)) + data.consumeString(4)
                }

                6 -> {
                    regex = "(?:a{2,}|(?:.|\\B){1,2}){1,2}"
                    flags = 0
                    input = if (data.consumeBoolean()) "baax" else data.consumeString(16)
                }

                7 -> {
                    val quantifier = data.pickValue<String?>(mutableListOf<String?>("*", "+", "{1,2}"))
                    regex = "[\\x{1F600}]" + quantifier + "b"
                    flags = 0
                    input = "😀b" + data.consumeString(16) + "😀b"
                    splitSurrogateFindStart = true
                }

                8 -> {
                    val anchor = data.pickValue<String?>(mutableListOf<String?>("$", "\\Z", "(?m:$)"))
                    val localMode = if (data.consumeBoolean()) "d" else "-d"
                    val scopedAnchor = "(?" + localMode + ":" + anchor + ")"
                    val body =
                        data.pickValue<String?>(mutableListOf<String?>("foo", "(?:foo|bar)", "(foo|bar)", "[a-z]+"))
                    regex = body + scopedAnchor + (if (data.consumeBoolean()) anchor else "")
                    flags = if (data.consumeBoolean()) java.util.regex.Pattern.UNIX_LINES else 0
                    val terminator =
                        data.pickValue<String?>(
                            mutableListOf<String?>(
                                "",
                                "\n",
                                "\r",
                                "\r\n",
                                "\u0085",
                                "\u2028",
                                "\u2029"
                            )
                        )
                    input = "x".repeat(data.consumeInt(0, 2048)) + "foo" + terminator
                }

                9 -> {
                    regex =
                        data.pickValue<String?>(
                            mutableListOf<String?>(
                                "(?-d:(?m:$))(?dm:$)",
                                "(?dm:$)(?-d:(?m:$))",
                                "((?-d:(?m:$)))(?dm:$)",
                                "(?-d:(?m:^))(?dm:^)[ab]"
                            )
                        )
                    flags = if (data.consumeBoolean()) java.util.regex.Pattern.UNIX_LINES else 0
                    val mixedLines = StringBuilder("x".repeat(data.consumeInt(0, 600)))
                    for (i in data.consumeInt(1, 16) downTo 1) {
                        mixedLines.append(
                            data.pickValue<String?>(
                                mutableListOf<String?>(
                                    "\r\na",
                                    "\na",
                                    "\rb",
                                    "\u0085a",
                                    "b"
                                )
                            )
                        )
                    }
                    input = mixedLines.toString()
                    warmLineEndCache = true
                }

                10 -> {
                    val atom = data.pickValue<String?>(mutableListOf<String?>("a", "😀", "[ab]", "(a|b)"))
                    val anchor = if (data.consumeBoolean()) "^" else ""
                    regex = anchor + atom + data.pickValue<String?>(mutableListOf<String?>("", "*", "+", "{1,3}"))
                    flags = 0
                    val prefix = if (data.consumeBoolean()) "!" else ""
                    input = prefix + "a😀b".repeat(data.consumeInt(1, 200)) + "!"
                    assertAnchoredContinuation(regex, input, data.consumeBoolean(), data.consumeBoolean())
                }

                12 -> {
                    val anchor = data.pickValue<String?>(mutableListOf<String?>("^", "\\A", "(?m)^"))
                    val body = data.pickValue<String?>(mutableListOf<String?>("[ab]*", "(a?)", "(a|b)*", "(?:😀)*"))
                    regex = anchor + body
                    flags = 0
                    input = "!" + data.consumeString(64)
                    val matcher = FuzzSupport.compileOrSkip(regex, flags).matcher(input)
                    if (data.consumeBoolean()) {
                        matcher.find(data.consumeInt(0, input.length))
                    } else if (data.consumeBoolean()) {
                        matcher.find()
                    } else {
                        matcher.lookingAt()
                    }
                    var i = 0
                    val steps = data.consumeInt(3, 8)
                    while (i < steps) {
                        matcher.find()
                        i++
                    }
                }

                11 -> {
                    val atom = data.pickValue<String?>(mutableListOf<String?>("\\W", "\\s", "[\\r\\n ]"))
                    val quantifier = data.pickValue<String?>(mutableListOf<String?>("*", "+", "?", "*?", "{0,3}"))
                    regex = atom + quantifier + "(?m:$)"
                    flags = 0
                    warmInput = " ".repeat(data.consumeInt(1, 8)) + data.pickValue<String?>(LINE_TERMINATORS) + "a"
                    input = "xb" + data.pickValue<String>(LINE_TERMINATORS) + "a".repeat(data.consumeInt(1, 700))
                }

                13 -> {
                    val boundary = data.pickValue<String?>(mutableListOf<String?>("\\b", "\\B"))
                    val atom = data.pickValue<String?>(mutableListOf<String?>(".", "(.)", "[\\s\\S]"))
                    regex = "(?U)" + (if (data.consumeBoolean()) atom + boundary else boundary + atom)
                    flags = 0
                    val word = data.pickValue<String?>(mutableListOf<String?>("α", "中", "\u0301", "\u0660", "\u200c"))
                    val nonWord = data.pickValue<String?>(mutableListOf<String?>("`", "\u180e", "!"))
                    input = "x".repeat(data.consumeInt(0, 512)) + " " + word + nonWord + nonWord
                }

                14 -> {
                    val atom = data.pickValue<String>(mutableListOf<String?>("a", "!", "α", "😀", "\n", "\r\n"))
                    val literal = java.util.regex.Pattern.quote(atom)
                    val assertion = data.pickValue<String?>(mutableListOf<String?>("\\b", "\\B", "(?m:$)"))
                    val consuming = literal.repeat(data.consumeInt(1, 4))
                    val alternatives =
                        if (data.consumeBoolean()) assertion + "|" + consuming else consuming + "|" + assertion
                    regex =
                        ("(?:"
                                + literal
                                + ")"
                                + data.pickValue<String?>(mutableListOf<String?>("*", "*?", "+", "{0,3}"))
                                + "(?:"
                                + alternatives
                                + ")")
                    flags = if (data.consumeBoolean()) java.util.regex.Pattern.UNICODE_CHARACTER_CLASS else 0
                    input = atom.repeat(data.consumeInt(0, 600)) + (if (data.consumeBoolean()) "x" else "")
                    FuzzSupport.compileOrSkip(regex, flags).matcher(input).matches()
                }

                15 -> {
                    val atom = data.pickValue<String>(mutableListOf<String?>("a", "α", "😀"))
                    val literal = java.util.regex.Pattern.quote(atom)
                    val body =
                        data.pickValue<String?>(List.of<String?>(literal + "?", "(" + literal + "?)", literal + "|"))
                    regex =
                        "S((?:" + body + ")" + data.pickValue<String?>(mutableListOf<String?>("*", "*?", "+")) + ")E"
                    flags = 0
                    val segment = "S" + atom.repeat(data.consumeInt(0, 200)) + "E"
                    val gap = "x".repeat(data.consumeInt(32000, 64000))
                    input =
                        if (data.consumeBoolean())
                            segment + gap + segment + gap
                        else
                            "S" + atom.repeat(data.consumeInt(0, 200)) + "Z" + gap + segment + gap
                }

                16 -> {
                    val prefix: String? = data.pickValue<String?>(INTERIOR_CLASS_PREFIXES)
                    val atom = data.pickValue<String?>(mutableListOf<String?>("[0-9]", "[A-F0-9]", "[A-F]"))
                    val quantifier = data.pickValue<String?>(mutableListOf<String?>("+", "{4,}", "{1,3}?"))
                    regex = prefix + "(" + atom + quantifier + ")" + data.pickValue<String?>(
                        mutableListOf<String?>(
                            "",
                            "Z",
                            "$"
                        )
                    )
                    flags = 0
                    val unit = data.pickValue<String>(
                        mutableListOf<String?>(
                            "1111x",
                            " 1x",
                            "aa1234Y",
                            "é😀1111x",
                            "1234567890"
                        )
                    )
                    input =
                        (unit.repeat(data.consumeInt(0, 1000))
                                + "   A1234Z e1234Z aa12345Z 😀1234Z éé12345Z x1234Z aé12345Z")
                }

                17 -> {
                    val grapheme = data.pickValue<String?>(mutableListOf<String?>("\\X", "(\\X)", "(?:\\X)"))
                    val quantifier = data.pickValue<String?>(mutableListOf<String?>("", "+", "+?", "{1,3}"))
                    val warm = "a" + data.consumeString(16)
                    val next =
                        data.pickValue<String?>(
                            mutableListOf<String?>(
                                "é",
                                "😀",
                                "e\u0301",
                                "\r\n"
                            )
                        ) + data.consumeString(16)
                    assertResetHandoffCacheIsolation(grapheme + quantifier, warm, next)
                    return
                }

                18 -> {
                    val context: SearchStartContext = data.pickValue<SearchStartContext>(SEARCH_START_CONTEXTS)
                    assertSearchStartAssertionFinds(
                        context,
                        data.pickValue<String?>(context.suffixes),
                        data.pickValue<String?>(mutableListOf<String?>("?", "*", "{0,2}")),
                        data.consumeBoolean(),
                        data.consumeBoolean(),
                        "z".repeat(data.consumeInt(0, 32))
                    )
                    return
                }

                else -> throw AssertionError()
            }
            val pattern = FuzzSupport.compileOrSkip(regex, flags)
            if (pattern == null) {
                return
            }
            if (warmLineEndCache) {
                val warmup = pattern.matcher(if (data.consumeBoolean()) "\r\na" else "a\na")
                while (warmup.find()) {
                    warmup.start()
                    warmup.end()
                }
            }
            if (warmInput != null) {
                pattern.matcher(warmInput).find()
            }
            if (splitSurrogateFindStart) {
                pattern.matcher(input).find(1)
            }

            val findWalker = pattern.matcher(input)
            val maxFinds = min(input.length + 2, 64)
            for (i in 0..<maxFinds) {
                val found = findWalker.find()
                if (!found) {
                    break
                }
            }

            val matcher = pattern.matcher(input)
            var canReadGroups = false
            val steps = data.consumeInt(1, 32)
            for (i in 0..<steps) {
                when (data.consumeInt(0, 10)) {
                    0 -> canReadGroups = matcher.matches()
                    1 -> canReadGroups = matcher.lookingAt()
                    2 -> canReadGroups = matcher.find()
                    3 -> canReadGroups = matcher.find(FuzzSupport.consumeIndex(data, input))
                    4 -> {
                        matcher.reset()
                        canReadGroups = false
                    }

                    5 -> {
                        input = data.consumeString(2048)
                        matcher.reset(input)
                        canReadGroups = false
                    }

                    6 -> matcher.groupCount()
                    7 -> {
                        if (canReadGroups) {
                            matcher.group()
                            matcher.start()
                            matcher.end()
                            val group = data.consumeInt(0, matcher.groupCount())
                            matcher.group(group)
                            matcher.start(group)
                            matcher.end(group)
                        }
                    }

                    8 -> {
                        val region = FuzzSupport.consumeRegion(data, input)
                        matcher.region(region[0], region[1])
                        canReadGroups = false
                    }

                    9 -> {
                        matcher.useAnchoringBounds(data.consumeBoolean())
                        matcher.useTransparentBounds(data.consumeBoolean())
                    }

                    10 -> matcher.hasMatch()
                    else -> throw AssertionError()
                }
            }
        }

        private fun assertSearchStartAssertionFinds(
            context: SearchStartContext,
            suffix: String,
            quantifier: String?,
            captures: Boolean,
            assertionFirst: Boolean,
            padding: String
        ) {
            val group = if (captures) "(" else "(?:"
            val consuming = java.util.regex.Pattern.quote(context.consuming)
            val asserted = context.assertion + java.util.regex.Pattern.quote(suffix)
            val alternatives = if (assertionFirst) asserted + "|" + consuming else consuming + "|" + asserted
            val regex = group + context.atom + quantifier + ")" + group + alternatives + ")?"
            val input = padding + context.leadingText + context.consuming + suffix
            val searchStart =
                padding.length + context.leadingText.length + context.consuming!!.length
            val pattern = FuzzSupport.compileOrSkip(regex, 0)

            // The shared matcher pair checks all captures after each successful find, warming the pattern.
            pattern.matcher(input).find(searchStart)
            val walker = pattern.matcher(input)
            for (i in 0..<input.length + 2) {
                if (!walker.find()) {
                    break
                }
            }
            for (transparent in mutableListOf<Boolean?>(false, true)) {
                val matcher = pattern.matcher(input)
                matcher.region(searchStart, input.length)
                matcher.useTransparentBounds(transparent!!)
                matcher.find()
            }
        }

        private fun nestedCapturingGroups(depth: Int): String {
            return "(".repeat(depth) + "a" + ")".repeat(depth)
        }

        private fun assertFindSequence(regex: String?, input: String) {
            val pattern = FuzzSupport.compileOrSkip(regex, 0)
            pattern.matcher(input).find()
            val matcher = pattern.matcher(input)
            val maxFinds = min(input.length + 2, 128)
            for (i in 0..<maxFinds) {
                if (!matcher.find()) {
                    return
                }
            }
        }
    }
}
