// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import org.safere.Pattern
import org.safere.Utf8Input
import org.safere.Utf8Matcher
import org.safere.Utf8Sink
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.List

/** Shared representation properties on explicitly constructed valid Unicode inputs.  */
internal object RepresentationChecks {
    fun assertUtf8ReplacementMatchesAppendLoop(data: FuzzedDataProvider) {
        val groups = data.consumeInt(0, 8)
        val invalidGroup = data.consumeInt(groups + 1, 9)
        val invalidReplacement =
            (data.pickValue<String?>(mutableListOf<String?>("", "[", "$0", "é😀$0-"))
                    + "$"
                    + invalidGroup
                    + data.pickValue<String?>(mutableListOf<String?>("", "]", "0", "$0", "😀")))
        assertUtf8NumberedReferenceValidation(
            groups, invalidGroup, invalidReplacement, data.consumeBoolean(), data.consumeBoolean()
        )
        val regex =
            data.pickValue<String>(
                mutableListOf<String?>(
                    "",
                    "(?<word>[aé😀]+)([0-9]+)?",
                    "(a)?(é|😀)",
                    "^é",
                    "é$",
                    "(?m)^é",
                    "a*?",
                    "\\X",
                    "a*",
                    "a+",
                    "[é😀]*",
                    "[^a]+",
                    "(?i)é*",
                    "[aé😀]{0,}"
                )
            )
        val source = StringBuilder()
        val pieces = data.consumeInt(0, 32)
        for (i in 0..<pieces) {
            source.append(
                data.pickValue<String?>(
                    mutableListOf<String?>(
                        "a",
                        "b",
                        "é",
                        "😀",
                        "\n",
                        "9",
                        " ",
                        "á",
                        "👩‍💻"
                    )
                )
            )
        }
        val replacement =
            data.pickValue<String>(
                mutableListOf<String?>("", "X", "<$0>", "$0$0", "$1", "\${word}-$2", "\\$0\\\\", "$", "$9", "\\")
            )
        assertUtf8ReplacementMatchesAppendLoop(
            regex, source.toString(), replacement, data.consumeInt(0, 2), data.consumeBoolean()
        )
    }

    fun assertUtf8ReplacementMatchesAppendLoop(
        regex: String, source: String, replacement: String, initialState: Int, trusted: Boolean
    ) {
        val bytes = ("prefix" + source + "suffix").toByteArray(StandardCharsets.UTF_8)
        val length = source.toByteArray(StandardCharsets.UTF_8).size
        val input =
            if (trusted) Utf8Input.trusted(bytes, 6, length) else Utf8Input.validated(bytes, 6, length)
        val templateBytes = ("prefix" + replacement + "suffix").toByteArray(StandardCharsets.UTF_8)
        val template =
            Utf8Input.validated(templateBytes, 6, replacement.toByteArray(StandardCharsets.UTF_8).size)
        val pattern = Pattern.compile(regex)
        val context = "/" + regex + "/ on " + source + " replacing with " + replacement
        for (all in booleanArrayOf(false, true)) {
            val actual = pattern.matcher(input)
            val expected = pattern.matcher(input)
            for (matcher in List.of<Utf8Matcher>(actual, expected)) {
                if (initialState == 1) {
                    matcher.region(input.length(), input.length()).find()
                } else if (initialState == 2 && matcher.find()) {
                    matcher.appendReplacement(
                        Utf8Sink { storage: ByteArray?, offset: Int, size: Int -> },
                        templateLiteral()
                    )
                }
            }
            val actualOutput = ByteArrayOutputStream()
            val expectedOutput = ByteArrayOutputStream()
            val actualFailure =
                replacementFailure(
                    Runnable {
                        if (all) {
                            actual.replaceAll(Utf8Sink { b: ByteArray?, off: Int, len: Int ->
                                actualOutput.write(
                                    b,
                                    off,
                                    len
                                )
                            }, template)
                        } else {
                            actual.replaceFirst(Utf8Sink { b: ByteArray?, off: Int, len: Int ->
                                actualOutput.write(
                                    b,
                                    off,
                                    len
                                )
                            }, template)
                        }
                    })
            val expectedFailure =
                replacementFailure(
                    Runnable {
                        expected.reset()
                        if (expected.find()) {
                            do {
                                expected.appendReplacement(Utf8Sink { b: ByteArray?, off: Int, len: Int ->
                                    expectedOutput.write(
                                        b,
                                        off,
                                        len
                                    )
                                }, template)
                            } while (all && expected.find())
                        }
                        expected.appendTail(Utf8Sink { b: ByteArray?, off: Int, len: Int ->
                            expectedOutput.write(
                                b,
                                off,
                                len
                            )
                        })
                    })
            if (actualFailure != expectedFailure
                || !actualOutput.toByteArray().contentEquals(expectedOutput.toByteArray())
            ) {
                throw AssertionError("UTF-8 replacement output or exception differs: " + context)
            }
            if (actual.regionStart() != expected.regionStart() || actual.regionEnd() != expected.regionEnd() || (matchBounds(
                    actual
                ) != matchBounds(expected))
            ) {
                throw AssertionError("UTF-8 replacement final match state differs: " + context)
            }
            actualOutput.reset()
            expectedOutput.reset()
            val actualTailFailure = replacementFailure(Runnable {
                actual.appendTail(Utf8Sink { b: ByteArray?, off: Int, len: Int ->
                    actualOutput.write(
                        b,
                        off,
                        len
                    )
                })
            })
            val expectedTailFailure =
                replacementFailure(Runnable {
                    expected.appendTail(Utf8Sink { b: ByteArray?, off: Int, len: Int ->
                        expectedOutput.write(
                            b,
                            off,
                            len
                        )
                    })
                })
            if (actualTailFailure != expectedTailFailure
                || !actualOutput.toByteArray().contentEquals(expectedOutput.toByteArray())
            ) {
                throw AssertionError("UTF-8 replacement append state differs: " + context)
            }
            while (true) {
                val found = expected.find()
                if (actual.find() != found || matchBounds(actual) != matchBounds(expected)) {
                    throw AssertionError("UTF-8 replacement subsequent search differs: " + context)
                }
                if (!found) {
                    break
                }
            }
        }
    }

    fun assertUtf8NumberedReferenceValidation(
        groups: Int, invalidGroup: Int, replacement: String, hasMatch: Boolean, trusted: Boolean
    ) {
        val regex = "(".repeat(groups) + "b" + ")".repeat(groups)
        val source = "前é😀a" + (if (hasMatch) "bb" else "") + "後"
        val bytes = ("prefix" + source + "suffix").toByteArray(StandardCharsets.UTF_8)
        val length = source.toByteArray(StandardCharsets.UTF_8).size
        val input =
            if (trusted) Utf8Input.trusted(bytes, 6, length) else Utf8Input.validated(bytes, 6, length)
        val templateBytes = ("prefix" + replacement + "suffix").toByteArray(StandardCharsets.UTF_8)
        val template =
            Utf8Input.validated(templateBytes, 6, replacement.toByteArray(StandardCharsets.UTF_8).size)
        val pattern = Pattern.compile(regex)
        val expectedMessage = "No group " + invalidGroup + " (groupCount=" + groups + ")"
        for (operation in ReplacementOperation.entries) {
            if (!hasMatch && operation == ReplacementOperation.APPEND) {
                continue
            }
            val matcher = pattern.matcher(input)
            val output = ByteArrayOutputStream()
            val context = operation.toString() + " /" + regex + "/ replacing with " + replacement
            try {
                when (operation) {
                    ReplacementOperation.ALL -> matcher.replaceAll(Utf8Sink { b: ByteArray?, off: Int, len: Int ->
                        output.write(
                            b,
                            off,
                            len
                        )
                    }, template)

                    ReplacementOperation.FIRST -> matcher.replaceFirst(Utf8Sink { b: ByteArray?, off: Int, len: Int ->
                        output.write(
                            b,
                            off,
                            len
                        )
                    }, template)

                    ReplacementOperation.APPEND -> {
                        if (!matcher.find()) {
                            throw AssertionError("Missing generated match: " + context)
                        }
                        matcher.appendReplacement(Utf8Sink { b: ByteArray?, off: Int, len: Int ->
                            output.write(
                                b,
                                off,
                                len
                            )
                        }, template)
                    }
                }
            } catch (failure: IndexOutOfBoundsException) {
                if (!hasMatch || failure.javaClass != IndexOutOfBoundsException::class.java || (expectedMessage != failure.message) || output.size() != 0) {
                    throw AssertionError("Invalid reference validation differs: " + context, failure)
                }
                continue
            }
            if (hasMatch) {
                throw AssertionError("Invalid numbered reference was accepted: " + context)
            }
            if (!output.toByteArray().contentEquals(source.toByteArray(StandardCharsets.UTF_8))) {
                throw AssertionError("Unmatched replacement output differs: " + context)
            }
        }
    }

    private fun templateLiteral(): Utf8Input {
        return Utf8Input.validated(byteArrayOf('o'.code.toByte(), 'l'.code.toByte(), 'd'.code.toByte()))
    }

    private fun replacementFailure(operation: Runnable): Class<*>? {
        try {
            operation.run()
            return null
        } catch (failure: IllegalArgumentException) {
            return failure.javaClass
        } catch (failure: IndexOutOfBoundsException) {
            return failure.javaClass
        } catch (failure: IllegalStateException) {
            return failure.javaClass
        }
    }

    private fun matchBounds(matcher: Utf8Matcher): MutableList<Int?> {
        try {
            matcher.start()
        } catch (noMatch: IllegalStateException) {
            return mutableListOf<Int?>()
        }
        val bounds: MutableList<Int?> = ArrayList<Int?>()
        for (group in 0..matcher.groupCount()) {
            bounds.add(matcher.start(group))
            bounds.add(matcher.end(group))
        }
        return bounds
    }

    fun assertLiteralSearchMatchesString(regex: String?, input: String) {
        val pattern = Pattern.compile(regex)
        val stringMatcher = pattern.matcher(input)
        val bytes = input.toByteArray(StandardCharsets.UTF_8)
        val utf8Input = Utf8Input.validated(bytes)
        val utf8Matcher = pattern.matcher(utf8Input)

        val stringFound = stringMatcher.find()
        val utf8Found = utf8Matcher.find()
        if (stringFound != utf8Found || pattern.find(utf8Input) != stringFound) {
            throw AssertionError("UTF-8 literal search result differs from String search")
        }
        if (stringFound
            && ((stringMatcher.group() != decodeGroup(
                bytes,
                utf8Matcher
            )) || utf8Matcher.start() < 0 || utf8Matcher.end() > bytes.size)
        ) {
            throw AssertionError("UTF-8 literal search bounds differ from String search")
        }
    }

    fun assertKeywordAlternationMatchesString(data: FuzzedDataProvider) {
        val keyword = data.pickValue<String>(mutableListOf<String?>("you", "your", "error", "timeout"))
        val prefix = data.pickValue<String?>(mutableListOf<String?>("", "plain ", "é ", "β-", "word_"))
        val suffix = data.pickValue<String?>(mutableListOf<String?>("", "!", " 中", "2", "_word"))
        val greedy = data.consumeBoolean()
        val beforeBoundaryMode = data.pickValue<String?>(mutableListOf<String?>("", "(?U)", "(?-U)"))
        val afterBoundaryMode = data.pickValue<String?>(mutableListOf<String?>("", "(?U)", "(?-U)"))
        val core =
            beforeBoundaryMode + "\\b(?i)(you|your|error|timeout)" + afterBoundaryMode + "\\b"
        val regex = if (greedy) "(?s).*" + core + ".*" else core
        val input =
            prefix + data.pickValue<String>(List.of<String?>(keyword, keyword.uppercase())) + suffix
        val pattern = Pattern.compile(regex)
        val stringMatcher = pattern.matcher(input)
        val bytes = input.toByteArray(StandardCharsets.UTF_8)
        val utf8Input = Utf8Input.validated(bytes)
        val utf8Matcher = pattern.matcher(utf8Input)

        val stringFound = stringMatcher.find()
        if (utf8Matcher.find() != stringFound || pattern.find(utf8Input) != stringFound) {
            throw AssertionError("UTF-8 keyword alternation result differs from String search")
        }
        if (stringFound
            && (utf8Matcher.start() != utf8Offset(
                input,
                stringMatcher.start()
            ) || utf8Matcher.end() != utf8Offset(
                input,
                stringMatcher.end()
            ) || utf8Matcher.start(1) != utf8Offset(input, stringMatcher.start(1)) || utf8Matcher.end(1) != utf8Offset(
                input,
                stringMatcher.end(1)
            ))
        ) {
            throw AssertionError("UTF-8 keyword alternation bounds differ from String search")
        }
    }

    fun assertFixedOffsetAccelerationMatchesString(data: FuzzedDataProvider) {
        val leadingClass = data.pickValue<String?>(mutableListOf<String?>("[aé]", "[a-ÿ]", "[a😀]", "[^x]"))
        val leadingMember =
            when (leadingClass) {
                "[a😀]" -> "😀"
                else -> "é"
            }
        val literal = data.pickValue<String?>(mutableListOf<String?>("bc", "tag", "literal"))
        val input =
            data.pickValue<String?>(mutableListOf<String?>("", "x", "😀")) + leadingMember + literal + " a" + literal
        val pattern = Pattern.compile(leadingClass + literal)
        val stringMatcher = pattern.matcher(input)
        val bytes = input.toByteArray(StandardCharsets.UTF_8)
        val utf8Input = Utf8Input.validated(bytes)
        val utf8Matcher = pattern.matcher(utf8Input)

        val stringFound = stringMatcher.find()
        if (utf8Matcher.find() != stringFound || pattern.find(utf8Input) != stringFound) {
            throw AssertionError("UTF-8 fixed-offset search result differs from String search")
        }
        if (stringFound
            && (utf8Matcher.start() != utf8Offset(
                input,
                stringMatcher.start()
            ) || utf8Matcher.end() != utf8Offset(input, stringMatcher.end()) || (stringMatcher.group() != decodeGroup(
                bytes,
                utf8Matcher
            )))
        ) {
            throw AssertionError("UTF-8 fixed-offset search bounds differ from String search")
        }
    }

    fun assertGraphemeSearchMatchesString(data: FuzzedDataProvider) {
        val regex =
            data.pickValue<String?>(mutableListOf<String?>(".\\X|z", "(?:.\\X)|z", ".\\X| /0? ?5-", "(?:a|.)\\X|z"))
        val input =
            (data.pickValue<String?>(mutableListOf<String?>("é", "軖", "😀", "a\u0301", "👩‍💻", "🇺🇸"))
                    + data.pickValue<String>(mutableListOf<String?>("", "| 5  軖", "z", " β")))
        val pattern = Pattern.compile(regex)
        val stringMatcher = pattern.matcher(input)
        val bytes = input.toByteArray(StandardCharsets.UTF_8)
        val utf8Matcher = pattern.matcher(Utf8Input.validated(bytes))

        while (true) {
            val stringFound = stringMatcher.find()
            val utf8Found = utf8Matcher.find()
            if (utf8Found != stringFound) {
                throw AssertionError("UTF-8 grapheme search result differs from String search")
            }
            if (!stringFound) {
                return
            }
            if (utf8Matcher.start() != utf8Offset(
                    input,
                    stringMatcher.start()
                ) || utf8Matcher.end() != utf8Offset(
                    input,
                    stringMatcher.end()
                ) || (stringMatcher.group() != decodeGroup(bytes, utf8Matcher))
            ) {
                throw AssertionError("UTF-8 grapheme search bounds differ from String search")
            }
        }
    }

    fun assertMultibyteMultiAnchorReverseWindowMatchesString(data: FuzzedDataProvider) {
        val upstream = data.pickValue<String?>(mutableListOf<String?>("é", "軖", "😀")).repeat(data.consumeInt(2, 12))
        val downstream = "z".repeat(data.consumeInt(16, 40))
        val regex = upstream + "[0-9]" + downstream
        val input = upstream + data.consumeInt(0, 9) + downstream
        val pattern = Pattern.compile(regex)
        val stringMatcher = pattern.matcher(input)
        val bytes = input.toByteArray(StandardCharsets.UTF_8)
        val utf8Matcher = pattern.matcher(Utf8Input.validated(bytes))

        val stringFound = stringMatcher.find()
        val utf8Found = utf8Matcher.find()
        if (utf8Found != stringFound
            || (stringFound
                    && (utf8Matcher.start() != utf8Offset(input, stringMatcher.start())
                    || utf8Matcher.end() != utf8Offset(input, stringMatcher.end())))
        ) {
            throw AssertionError("UTF-8 multi-anchor reverse window differs from String matching")
        }
    }

    fun utf8Offset(input: String, utf16Offset: Int): Int {
        return input.substring(0, utf16Offset).toByteArray(StandardCharsets.UTF_8).size
    }

    fun decodeGroup(bytes: ByteArray, matcher: Utf8Matcher): String {
        return String(
            bytes, matcher.start(), matcher.end() - matcher.start(), StandardCharsets.UTF_8
        )
    }

    private enum class ReplacementOperation {
        ALL,
        FIRST,
        APPEND
    }
}
