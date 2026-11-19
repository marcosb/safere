// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.assertj.core.api.ThrowingConsumer
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.List
import java.util.function.Function
import java.util.regex.Pattern

internal class Utf8ReplacementTest {
    @ParameterizedTest
    @ValueSource(strings = ["literal", "前😀後", ""])
    fun validatedLiteralReplacementsBorrowTheirOriginalWindow(text: String) {
        val bytes = ("before" + text + "after").toByteArray(StandardCharsets.UTF_8)
        val replacement = Utf8Input.validated(bytes, 6, text.toByteArray(StandardCharsets.UTF_8).size)
        val ranges: MutableList<Range> = ArrayList<Range>()
        val matcher: Utf8Matcher = matcher("a", "a")

        matcher.replaceAll(
            Utf8Sink { storage: ByteArray?, offset: Int, length: Int -> ranges.add(Range(storage, offset, length)) },
            replacement
        )

        Assertions.assertThat<Range?>(ranges).hasSize(3)
        val literal = ranges.get(1)
        Assertions.assertThat(literal.bytes()).isSameAs(bytes)
        Assertions.assertThat(literal.offset()).isEqualTo(6)
        Assertions.assertThat(literal.length).isEqualTo(text.toByteArray(StandardCharsets.UTF_8).size)
    }

    @Test
    fun trustedLiteralReplacementsPreserveUnicodeAndMalformedDecoding() {
        for (bytes in List.of<ByteArray?>(
            "plain".toByteArray(StandardCharsets.UTF_8),
            "前😀後".toByteArray(StandardCharsets.UTF_8),
            byteArrayOf('x'.code.toByte(), 0x80.toByte(), 'y'.code.toByte())
        )) {
            val sink = CollectingSink()

            matcher("a", "a").replaceAll(sink, Utf8Input.trusted(bytes))

            Assertions.assertThat(sink.toString()).isEqualTo(String(bytes, StandardCharsets.UTF_8))
        }
    }

    @Test
    fun literalTemplateCanFollowACaptureTemplateOnTheSameMatcher() {
        val matcher: Utf8Matcher = matcher("(a)", "aa")
        val sink = CollectingSink()
        Assertions.assertThat(matcher.find()).isTrue()
        matcher.appendReplacement(sink, input("$1"))
        Assertions.assertThat(matcher.find()).isTrue()
        matcher.appendReplacement(sink, input("X"))
        matcher.appendTail(sink)

        Assertions.assertThat(sink.toString()).isEqualTo("aX")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun wholeInputReplacementMatchesJdkOutputAndSubsequentSearch(all: Boolean) {
        val cases = arrayOf<Array<String?>?>(
            arrayOf<String?>("(?<letter>é)(😀)?", "xé😀 éy", "\${letter}-$2-$0"),
            arrayOf<String?>("[0-9]+", "a12b34c", ""),
            arrayOf<String?>("a", "aaa", "\\$0\\\\"),
            arrayOf<String?>("^é", "éé", "前"),
            arrayOf<String?>("é$", "éé", "後"),
            arrayOf<String?>("(?m)^é", "é\né", "x"),
            arrayOf<String?>(".*mandatory-token.*", "no token here", "$"),
            arrayOf<String?>("z", "", "x")
        )
        for (testCase in cases) {
            val regex: String = testCase!![0]!!
            val source: String = testCase[1]!!
            val replacement: String = testCase[2]!!
            val matcher: Utf8Matcher = matcher(regex, source)
            val jdk = Pattern.compile(regex).matcher(source)
            val expected = if (all) jdk.replaceAll(replacement) else jdk.replaceFirst(replacement)
            val sink = CollectingSink()

            replace(matcher, sink, input(replacement), all)

            Assertions.assertThat(sink.toString()).`as`("/%s/ on %s, all=%s", regex, source, all).isEqualTo(expected)
            if (all) {
                Assertions.assertThatThrownBy(ThrowingCallable { matcher.start() })
                    .isInstanceOf(IllegalStateException::class.java)
            }
            val next = jdk.find()
            Assertions.assertThat(matcher.find()).isEqualTo(next)
            if (next) {
                Assertions.assertThat(matcher.start())
                    .isEqualTo(source.substring(0, jdk.start()).toByteArray(StandardCharsets.UTF_8).size)
                Assertions.assertThat(matcher.end()).isEqualTo(
                    source.substring(0, jdk.end()).toByteArray(
                        StandardCharsets.UTF_8
                    ).size
                )
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun wholeInputReplacementResetsRegionAndAppendPositionAndRetainsSinkContents(all: Boolean) {
        val matcher: Utf8Matcher = matcher("é", "é😀é").region(6, 8)
        val sink = CollectingSink()
        Assertions.assertThat(matcher.find()).isTrue()
        matcher.appendReplacement(sink, input("old"))

        replace(matcher, sink, input("new"), all)

        Assertions.assertThat(sink.toString()).isEqualTo(if (all) "é😀oldnew😀new" else "é😀oldnew😀é")
        Assertions.assertThat(matcher.regionStart()).isZero()
        Assertions.assertThat(matcher.regionEnd()).isEqualTo(8)
        if (!all) {
            Assertions.assertThat(matcher.start()).isZero()
            Assertions.assertThat(matcher.end()).isEqualTo(2)
        }
        val tail = CollectingSink()
        matcher.appendTail(tail)
        Assertions.assertThat(tail.toString()).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun wholeInputReplacementMakesScalarProgressForEmptyMatches(all: Boolean) {
        val sink = CollectingSink()
        val matcher: Utf8Matcher = matcher("", "é😀")

        replace(matcher, sink, input("x"), all)

        Assertions.assertThat(sink.toString()).isEqualTo(if (all) "xéx😀x" else "xé😀")
        if (!all) {
            Assertions.assertThat(matcher.start()).isZero()
            Assertions.assertThat(matcher.end()).isZero()
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(2)
        }
        val empty = CollectingSink()
        replace(matcher("", ""), empty, input("x"), all)
        Assertions.assertThat(empty.toString()).isEqualTo("x")
    }

    @Test
    @DisabledForCrosscheck("SafeRE intentionally invalidates match state after terminal empty matches")
    fun replaceAllInvalidatesTerminalEmptyMatch() {
        for (source in mutableListOf<String?>("", "é😀")) {
            val matcher: Utf8Matcher = matcher("", source!!)

            matcher.replaceAll(CollectingSink(), input("x"))

            Assertions.assertThatThrownBy(ThrowingCallable { matcher.start() })
                .isInstanceOf(IllegalStateException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { matcher.end() })
                .isInstanceOf(IllegalStateException::class.java)
            Assertions.assertThat(matcher.find()).isFalse()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun wholeInputReplacementUsesSubjectAndReplacementWindows(all: Boolean) {
        val source = "beforeé😀éafter".toByteArray(StandardCharsets.UTF_8)
        val replacement = "before[$0]後after".toByteArray(StandardCharsets.UTF_8)
        val matcher = org.safere.Pattern.compile("é").matcher(Utf8Input.validated(source, 6, 8))
        val sink = CollectingSink()

        replace(matcher, sink, Utf8Input.validated(replacement, 6, 7), all)

        Assertions.assertThat(sink.toString()).isEqualTo(if (all) "[é]後😀[é]後" else "[é]後😀é")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun wholeInputReplacementWithoutMatchesBorrowsOriginalRangeAndSkipsInvalidTemplate(all: Boolean) {
        val source = "beforeé😀after".toByteArray(StandardCharsets.UTF_8)
        val matcher = org.safere.Pattern.compile("z").matcher(Utf8Input.validated(source, 6, 6))
        val ranges: MutableList<Range> = ArrayList<Range>()

        replace(
            matcher,
            Utf8Sink { bytes: ByteArray?, offset: Int, length: Int -> ranges.add(Range(bytes, offset, length)) },
            input("\${missing}"),
            all
        )

        Assertions.assertThat<Range?>(ranges)
            .singleElement()
            .satisfies(ThrowingConsumer { range: Range? -> Assertions.assertThat(range!!.bytes()).isSameAs(source) })
        Assertions.assertThat<Range?>(ranges)
            .extracting(Function { obj: Range? -> obj!!.offset() }, Function { obj: Range? -> obj!!.length })
            .containsExactly(
                Assertions.tuple(6, 6)
            )
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.start() })
            .isInstanceOf(IllegalStateException::class.java)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun wholeInputReplacementRejectsInvalidTemplateBeforeWritingAndCanBeRetried(all: Boolean) {
        val matcher: Utf8Matcher = matcher("a", "xaxa")
        val sink = CollectingSink()

        Assertions.assertThatThrownBy(ThrowingCallable { replace(matcher, sink, input("\${missing}"), all) })
            .isInstanceOf(IllegalArgumentException::class.java)
        Assertions.assertThat(sink.toString()).isEmpty()
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.appendTail(sink) })
            .isInstanceOf(IllegalStateException::class.java)

        replace(matcher, sink, input("b"), all)

        Assertions.assertThat(sink.toString()).isEqualTo(if (all) "xbxb" else "xbxa")
    }

    @ParameterizedTest
    @EnumSource(ReplacementOperation::class)
    fun invalidNumberedReferencesFailBeforeWriting(operation: ReplacementOperation) {
        for (groupCount in intArrayOf(0, 1, 2, 8)) {
            val regex = "(".repeat(groupCount) + "b" + ")".repeat(groupCount)
            val invalidGroup = groupCount + 1
            for (replacement in List.of<String?>(
                "$" + invalidGroup,
                "[$" + invalidGroup + "]",
                "é😀$0-$" + invalidGroup
            )) {
                for (window in booleanArrayOf(false, true)) {
                    val subject = "前é😀ab後b"
                    val source: Utf8Input = windowedInput(subject, window)
                    val template: Utf8Input = windowedInput(replacement, window)
                    val matcher = org.safere.Pattern.compile(regex).matcher(source)
                    val sink = CollectingSink()

                    Assertions.assertThatThrownBy(ThrowingCallable { replace(matcher, sink, template, operation) })
                        .`as`("%s /%s/ template %s, window=%s", operation, regex, replacement, window)
                        .isExactlyInstanceOf(IndexOutOfBoundsException::class.java)
                        .hasMessage("No group %s (groupCount=%s)", invalidGroup, groupCount)
                    Assertions.assertThat(sink.toString()).isEmpty()
                    Assertions.assertThatThrownBy(ThrowingCallable { matcher.appendTail(sink) })
                        .isInstanceOf(IllegalStateException::class.java)
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun invalidNumberedReferencesRemainLazyWithoutMatches(all: Boolean) {
        for (replacement in mutableListOf<String?>("$1", "[$9]", "é😀$0-$2")) {
            val sink = CollectingSink()
            replace(matcher("b", "前é😀a後"), sink, input(replacement!!), all)
            Assertions.assertThat(sink.toString()).isEqualTo("前é😀a後")
        }
    }

    @ParameterizedTest
    @EnumSource(ReplacementOperation::class)
    fun numberedReferenceDigitsRespectAvailableGroups(operation: ReplacementOperation) {
        data class Case(val groups: Int, val replacement: String?)
        for (c in List.of<Case?>(
            Case(0, "$00"),
            Case(0, "$01"),
            Case(1, "$10"),
            Case(1, "$12"),
            Case(2, "$20"),
            Case(10, "$10"),
            Case(10, "$11")
        )) {
            val regex = "(".repeat(c.groups) + "b" + ")".repeat(c.groups)
            val subject = "前é😀ab後b"
            val stringMatcher = org.safere.Pattern.compile(regex).matcher(subject)
            val expected =
                if (operation == ReplacementOperation.ALL)
                    stringMatcher.replaceAll(c.replacement)
                else
                    stringMatcher.replaceFirst(c.replacement)
            val sink = CollectingSink()

            val utf8: Utf8Matcher = matcher(regex, subject)
            replace(utf8, sink, input(c.replacement!!), operation)
            if (operation == ReplacementOperation.APPEND) {
                utf8.appendTail(sink)
            }
            Assertions.assertThat(sink.toString()).isEqualTo(expected)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun wholeInputReplacementPreservesPartialOutputOnSinkFailure(all: Boolean) {
        val matcher: Utf8Matcher = matcher("a", "xaxa")
        val sink = CollectingSink()

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                replace(
                    matcher,
                    Utf8Sink { bytes: ByteArray?, offset: Int, length: Int ->
                        sink.append(bytes!!, offset, length)
                        throw SinkFailure()
                    },
                    input("b"),
                    all
                )
            })
            .isInstanceOf(SinkFailure()::class.java)
        Assertions.assertThat(sink.toString()).isEqualTo("x")
        Assertions.assertThat(matcher.start()).isEqualTo(1)
        Assertions.assertThat(matcher.end()).isEqualTo(2)
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.appendTail(sink) })
            .isInstanceOf(IllegalStateException::class.java)

        val retry = CollectingSink()
        replace(matcher, retry, input("b"), all)
        Assertions.assertThat(retry.toString()).isEqualTo(if (all) "xbxb" else "xbxa")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun wholeInputReplacementSinkCanObserveCurrentCaptureBounds(all: Boolean) {
        val matcher: Utf8Matcher = matcher("(é)", "xéyéz")
        val sink = CollectingSink()
        val captureStarts: MutableList<Int> = ArrayList<Int>()

        replace(
            matcher,
            Utf8Sink { bytes: ByteArray?, offset: Int, length: Int ->
                if (length == 1 && bytes!![offset] == '['.code.toByte()) {
                    captureStarts.add(matcher.start(1))
                }
                sink.append(bytes!!, offset, length)
            },
            input("[$1]"),
            all
        )

        Assertions.assertThat(sink.toString()).isEqualTo(if (all) "x[é]y[é]z" else "x[é]yéz")
        Assertions.assertThat<Int?>(captureStarts)
            .isEqualTo(if (all) mutableListOf<Int?>(1, 4) else mutableListOf<Int?>(1))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun wholeInputReplacementSinkCannotResetOrReplaceMatcher(all: Boolean) {
        for (hasMatch in booleanArrayOf(false, true)) {
            val matcher: Utf8Matcher = matcher(if (hasMatch) "a" else "z", "xaxa")
            val sink = CollectingSink()
            Assertions.assertThatThrownBy(
                ThrowingCallable {
                    replace(
                        matcher,
                        Utf8Sink { bytes: ByteArray?, offset: Int, length: Int ->
                            replace(
                                matcher,
                                sink,
                                input("nested"),
                                all
                            )
                        },
                        input("b"),
                        all
                    )
                })
                .isInstanceOf(ConcurrentModificationException::class.java)
            Assertions.assertThat(sink.toString()).isEmpty()
            Assertions.assertThatThrownBy(ThrowingCallable { matcher.appendTail(sink) }).isInstanceOf(
                IllegalStateException::class.java
            )
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun wholeInputReplacementRejectsNullArgumentsWithoutResettingMatcher(all: Boolean) {
        val matcher: Utf8Matcher = matcher("a", "aa")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.find()).isTrue()

        Assertions.assertThatThrownBy(ThrowingCallable { replace(matcher, null, input("b"), all) })
            .isInstanceOf(NullPointerException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { replace(matcher, CollectingSink(), null, all) })
            .isInstanceOf(NullPointerException::class.java)
        Assertions.assertThat(matcher.start()).isEqualTo(1)
        Assertions.assertThat(matcher.end()).isEqualTo(2)
    }

    @Test
    fun numberedAndNamedGroupsAreExpandedWithoutDecodingTheSubject() {
        Assertions.assertThat(replace("(?<letter>é)(😀)?", "xé😀 éy", "\${letter}-$2-$0"))
            .isEqualTo("xé-😀-é😀 é--éy")
    }

    @ParameterizedTest
    @ValueSource(strings = ["EMAIL", "<$0>", "$0$0", "$2", "\${user}@$2", "\\$1", "x\\\\y"])
    fun replacementsMatchStringReplaceAllWithInnerGroups(replacement: String) {
        val regex = "(?<user>\\w+)@(\\w+)"
        val source = "a@b café@hôte 😀 x@y"
        Assertions.assertThat(replace(regex, source, replacement))
            .isEqualTo(org.safere.Pattern.compile(regex).matcher(source).replaceAll(replacement))
    }

    @Test
    fun escapedReplacementCharactersAreLiteral() {
        Assertions.assertThat(replace("a", "a", "\\$0\\\\")).isEqualTo("$0\\")
    }

    @Test
    fun emptyMatchesMakeCodePointProgress() {
        Assertions.assertThat(replace("", "😀", "x")).isEqualTo("x😀x")
    }

    @Test
    fun malformedReplacementIsRejected() {
        val matcher: Utf8Matcher = matcher("(a)", "a")
        Assertions.assertThat(matcher.find()).isTrue()
        val sink = CollectingSink()

        Assertions.assertThatThrownBy(ThrowingCallable { matcher.appendReplacement(sink, input("\${missing}")) })
            .isInstanceOf(IllegalArgumentException::class.java)
        Assertions.assertThat(sink.toString()).isEmpty()
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.appendTail(sink) })
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun sinkFailureInvalidatesOnlyReplacementState() {
        val matcher: Utf8Matcher = matcher("(a)", "a")
        Assertions.assertThat(matcher.find()).isTrue()

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                matcher.appendReplacement(
                    Utf8Sink { bytes: ByteArray?, offset: Int, length: Int ->
                        throw SinkFailure()
                    },
                    input("$1")
                )
            })
            .isInstanceOf(SinkFailure()::class.java)
        Assertions.assertThat(matcher.start()).isZero()
        Assertions.assertThat(matcher.end()).isEqualTo(1)
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.appendTail(CollectingSink()) })
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun sinkCannotAdvanceMatcherReentrantly() {
        val matcher: Utf8Matcher = matcher("a", "aa")
        Assertions.assertThat(matcher.find()).isTrue()

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                matcher.appendReplacement(
                    Utf8Sink { bytes: ByteArray?, offset: Int, length: Int -> matcher.find() }, input("replacement")
                )
            })
            .isInstanceOf(ConcurrentModificationException::class.java)
        Assertions.assertThat(matcher.start()).isZero()
        Assertions.assertThat(matcher.end()).isEqualTo(1)
    }

    @Test
    fun replacementWorksOnNonzeroOffsetWindows() {
        val logical = "xéy".toByteArray(StandardCharsets.UTF_8)
        val storage = ByteArray(logical.size + 6)
        System.arraycopy(logical, 0, storage, 3, logical.size)
        val source = Utf8Input.validated(storage, 3, logical.size)
        val matcher = org.safere.Pattern.compile("(é)").matcher(source)
        val sink = CollectingSink()

        while (matcher.find()) {
            matcher.appendReplacement(sink, input("[$1]"))
        }
        matcher.appendTail(sink)

        Assertions.assertThat(sink.toString()).isEqualTo("x[é]y")
    }

    @Test
    fun unmatchedAndCapturedRangesUseOriginalSubjectStorage() {
        val logical = "xéy".toByteArray(StandardCharsets.UTF_8)
        val storage = ByteArray(logical.size + 6)
        System.arraycopy(logical, 0, storage, 3, logical.size)
        val matcher =
            org.safere.Pattern.compile("(é)").matcher(Utf8Input.validated(storage, 3, logical.size))
        val ranges: MutableList<Range> = ArrayList<Range>()
        val sink = Utf8Sink { bytes: ByteArray?, offset: Int, length: Int -> ranges.add(Range(bytes, offset, length)) }

        Assertions.assertThat(matcher.find()).isTrue()
        matcher.appendReplacement(sink, input("$1"))
        matcher.appendTail(sink)

        Assertions.assertThat<Range?>(ranges).hasSize(3)
        for (range in ranges) {
            Assertions.assertThat(range.bytes()).isSameAs(storage)
        }
        Assertions.assertThat<Range?>(ranges)
            .extracting(Function { obj: Range? -> obj!!.offset() }, Function { obj: Range? -> obj!!.length })
            .containsExactly(Assertions.tuple(3, 1), Assertions.tuple(4, 2), Assertions.tuple(6, 1))
    }

    @Test
    fun replacementWorksOnNonzeroOffsetReplacementWindow() {
        val logical = "前\${letter}後".toByteArray(StandardCharsets.UTF_8)
        val storage = ByteArray(logical.size + 8)
        System.arraycopy(logical, 0, storage, 4, logical.size)
        val replacement = Utf8Input.validated(storage, 4, logical.size)
        val matcher: Utf8Matcher = matcher("(?<letter>é)", "xéy")
        val sink = CollectingSink()

        Assertions.assertThat(matcher.find()).isTrue()
        matcher.appendReplacement(sink, replacement)
        matcher.appendTail(sink)

        Assertions.assertThat(sink.toString()).isEqualTo("x前é後y")
    }

    @ParameterizedTest
    @ValueSource(strings = ["\\", "$", "\${", "\${name", "\$x"])
    fun invalidReplacementSyntaxFailsBeforeWriting(replacement: String) {
        val matcher: Utf8Matcher = matcher("(a)", "xa")
        val sink = CollectingSink()
        Assertions.assertThat(matcher.find()).isTrue()

        Assertions.assertThatThrownBy(ThrowingCallable { matcher.appendReplacement(sink, input(replacement)) })
            .isInstanceOf(IllegalArgumentException::class.java)
        Assertions.assertThat(sink.toString()).isEmpty()
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.appendTail(sink) })
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun resetRecoversReplacementStateAndAppendPosition() {
        val matcher: Utf8Matcher = matcher("(a)", "xa")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThatThrownBy(ThrowingCallable {
            matcher.appendReplacement(
                CollectingSink(),
                input("\${missing}")
            )
        })
            .isInstanceOf(IllegalArgumentException::class.java)

        matcher.reset()
        val sink = CollectingSink()
        Assertions.assertThat(matcher.find()).isTrue()
        matcher.appendReplacement(sink, input("b"))
        matcher.appendTail(sink)

        Assertions.assertThat(sink.toString()).isEqualTo("xb")
    }

    @Test
    fun replacementTemplateIsCompiledOnceAcrossRepeatedMatches() {
        val matcher: Utf8Matcher = matcher("a", "aaa")
        val expected = "é".toByteArray(StandardCharsets.UTF_8)
        val literalStorages: MutableList<ByteArray> = ArrayList<ByteArray>()
        val sink =
            Utf8Sink { bytes: ByteArray?, offset: Int, length: Int ->
                if (length == expected.size
                    && ByteBuffer.wrap(bytes, offset, length).mismatch(ByteBuffer.wrap(expected)) == -1
                ) {
                    literalStorages.add(bytes!!)
                }
            }
        val replacement: Utf8Input = input("é")

        while (matcher.find()) {
            matcher.appendReplacement(sink, replacement)
        }
        matcher.appendTail(sink)

        Assertions.assertThat<ByteArray?>(literalStorages).hasSize(3)
        for (literalStorage in literalStorages) {
            Assertions.assertThat(literalStorage).isSameAs(literalStorages.first())
        }
    }

    @Test
    fun appendPositionIsSharedAcrossSinkInstances() {
        val matcher: Utf8Matcher = matcher("[0-9]", "a1b2c")
        val first = CollectingSink()
        val second = CollectingSink()

        Assertions.assertThat(matcher.find()).isTrue()
        matcher.appendReplacement(first, input("X"))
        Assertions.assertThat(matcher.find()).isTrue()
        matcher.appendReplacement(second, input("Y"))
        matcher.appendTail(second)

        Assertions.assertThat(first.toString()).isEqualTo("aX")
        Assertions.assertThat(second.toString()).isEqualTo("bYc")
    }

    @Test
    fun requiredCapturedClassPrefilterPreservesReplacementResults() {
        Assertions.assertThat(replace(".*(x|y).*", "aaaaaaaa", "X")).isEqualTo("aaaaaaaa")
        Assertions.assertThat(replace(".*(x|y).*", "aaaayaaa", "[$1]")).isEqualTo("[y]")
        Assertions.assertThat(replace(".*(cat|dog).*", "birds only", "X")).isEqualTo("birds only")
        Assertions.assertThat(replace(".*(cat|dog).*", "a dog appears", "[$1]")).isEqualTo("[dog]")
        Assertions.assertThat(replace(".*(α|β).*", "γδ", "X")).isEqualTo("γδ")
        Assertions.assertThat(replace(".*(α|β).*", "γβδ", "[$1]")).isEqualTo("[β]")
    }

    @Test
    fun requiredLiteralPrefilterPreservesReplacementResults() {
        Assertions.assertThat(replace(".*coolfunctionname.*", "cool-function-name", "X"))
            .isEqualTo("cool-function-name")
        Assertions.assertThat(replace(".*coolfunctionname.*", "before coolfunctionname after", "X"))
            .isEqualTo("X")
        Assertions.assertThat(replace(".*mandatory-token.*", "mandatory token", "X")).isEqualTo("mandatory token")
        Assertions.assertThat(replace(".*mandatory-token.*", "before mandatory-token after", "X")).isEqualTo("X")
        Assertions.assertThat(replace(".*必要語.*", "これは不要です", "X")).isEqualTo("これは不要です")
        Assertions.assertThat(replace(".*必要語.*", "これは必要語です", "X")).isEqualTo("X")
    }

    @Test
    @DisplayName("array and buffer sink dispatch preserve chunk boundaries and caller position")
    fun sinkDispatchPreservesChunksAndBufferPosition() {
        val sink = CollectingSink()
        val direct = ByteBuffer.allocateDirect(4).put("xéz".toByteArray(StandardCharsets.UTF_8))
        direct.flip()
        direct.position(1)
        val originalPosition = direct.position()

        sink.append("α".toByteArray(StandardCharsets.UTF_8), 0, "α".toByteArray(StandardCharsets.UTF_8).size)
        sink.append(direct)

        Assertions.assertThat(sink.toString()).isEqualTo("αéz")
        Assertions.assertThat(direct.position()).isEqualTo(originalPosition)
    }

    private enum class ReplacementOperation {
        ALL,
        FIRST,
        APPEND
    }

    private class CollectingSink : Utf8Sink {
        private val output = ByteArrayOutputStream()

        override fun append(bytes: ByteArray, offset: Int, length: Int) {
            output.write(bytes, offset, length)
        }

        override fun toString(): String {
            return output.toString(StandardCharsets.UTF_8)
        }
    }

    private class SinkFailure : RuntimeException() {
        private const val serialVersionUID = 1L
    }

    private class Range(private val bytes: ByteArray?, private val offset: Int, private val length: Int) {
        fun bytes(): ByteArray? {
            return bytes
        }

        fun offset(): Int {
            return offset
        }

        fun length(): Int {
            return length
        }
    }

    companion object {
        private fun replace(
            matcher: Utf8Matcher, sink: Utf8Sink?, replacement: Utf8Input?, operation: ReplacementOperation
        ) {
            when (operation) {
                ReplacementOperation.ALL -> matcher.replaceAll(sink, replacement)
                ReplacementOperation.FIRST -> matcher.replaceFirst(sink, replacement)
                ReplacementOperation.APPEND -> {
                    Assertions.assertThat(matcher.find()).isTrue()
                    matcher.appendReplacement(sink, replacement)
                }
            }
        }

        private fun windowedInput(text: String, window: Boolean): Utf8Input {
            if (!window) {
                return input(text)
            }
            val storage = ("before" + text + "after").toByteArray(StandardCharsets.UTF_8)
            return Utf8Input.validated(storage, 6, text.toByteArray(StandardCharsets.UTF_8).size)
        }

        private fun replace(
            matcher: Utf8Matcher, sink: Utf8Sink?, replacement: Utf8Input?, all: Boolean
        ) {
            if (all) {
                matcher.replaceAll(sink, replacement)
            } else {
                matcher.replaceFirst(sink, replacement)
            }
        }

        private fun replace(pattern: String?, source: String, replacement: String): String {
            val matcher: Utf8Matcher = matcher(pattern, source)
            val sink = CollectingSink()
            while (matcher.find()) {
                matcher.appendReplacement(sink, input(replacement))
            }
            matcher.appendTail(sink)
            return sink.toString()
        }

        private fun matcher(pattern: String?, input: String): Utf8Matcher {
            return org.safere.Pattern.compile(pattern).matcher(input(input))
        }

        private fun input(input: String): Utf8Input {
            return Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))
        }
    }
}
