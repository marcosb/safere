// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.regex.PatternSyntaxException

/** Compatibility at boundaries within lexical constructs, rather than only between atoms.  */
internal class LexicalCompatibilityTest {
    @Test
    fun literalRunsKeepAtomQuantifiersAndGroupBoundaries() {
        val inputs: MutableList<String> = mutableListOf<String>(
            "",
            "a",
            "ab",
            "abc",
            "abcc",
            "abccc",
            "abcabc",
            "abab",
            "ababab",
            "abcd",
            "abC",
            "aBCD",
            "ABCD",
            "ABC",
            "abefef",
            "xabcabccyabc",
            "a😀",
            "a😀😀",
            "a😀a😀",
            "a\uD83D\uD800\uDE00",
            "a\uD83D\uD800\uDE00\uD800\uDE00"
        )
        for (regex in mutableListOf<String>(
            "abc+",
            "abc{0,2}",
            "abc*?",
            "(?:abc)+",
            "(abc)+",
            "(ab)c+",
            "(?:ab)c+",
            "ab|cd+",
            "ab(?:cd|ef)+",
            "ab(?i:c+)d",
            "(?i:a)bcd+",
            "ab(?i)cd+",
            "a\\x42\\u0043+",
            "\\Qabc\\E+",
            "(?x)a #c\n b c+",
            "a😀+",
            "(?:a😀)+",
            "a\\uD83D\\uDE00+",
            "a\\uD83D\\uD800\\uDE00+",
            "abc\\Q\\E+?",
            "(a|ab)c+",
            "(ab){0}c+",
            "^abc+$"
        )) {
            for (flags in java.util.List.of<Int?>(
                0,
                Pattern.COMMENTS,
                Pattern.CASE_INSENSITIVE,
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            )) {
                assertSame(regex!!, flags, inputs)
            }
        }
    }

    @Test
    fun propertyComponentsCrossTriviaAndQuoteBoundaries() {
        for (introducer in mutableListOf<String>("\\p", "\\P")) {
            for (gap in mutableListOf<String>("", "\\Q\\E", "\\Q\\E\\Q\\E", " ", "#c\n", " \\Q\\E#c\n")) {
                for (payload in mutableListOf<String>("L", "{L}", "{ L}", "{L }", "\\QL\\E", "\\Q{L}\\E")) {
                    val escape = introducer + gap + payload
                    for (expression in java.util.List.of<String>(escape, "[" + escape + "]", "(" + escape + ")+")) {
                        assertSame(expression, 0)
                        assertSame(expression, Pattern.COMMENTS)
                        assertSame("(?x)" + expression, 0)
                        assertSame("(?x:" + expression + ")", 0)
                    }
                }
            }
        }
    }

    @Test
    fun controlAndNumericEscapesRespectTheirComponentBoundaries() {
        for (expression in mutableListOf<String>(
            "\\c\\Q\\Ea",
            "\\c\\Q\\E\\Q\\Ea",
            "\\c\\Qa\\E",
            "\\c\t\\Q\\E-z",
            "\\x\\Q41\\E",
            "\\x4\\Q1\\E",
            "\\x 41",
            "\\x4 1",
            "\\u00 41",
            "\\0 77",
            "\\x{ 41}",
            "\\x{4 1}",
            "\\u00\\Q41\\E",
            "\\p\\Q{L}\\E"
        )) {
            for (wrapped in java.util.List.of<String>(expression, "[" + expression + "]")) {
                assertSame(wrapped, 0)
                assertSame(wrapped, Pattern.COMMENTS)
            }
        }
    }

    @Test
    fun quantifierModifiersCrossEmptyQuotesWithoutChangingMatchPriority() {
        for (operand in mutableListOf<String>("a", "(a)", "(a?)", "()")) {
            for (repeat in mutableListOf<String>("?", "*", "+", "{1}", "{0,2}", "{1,3}")) {
                for (gap in mutableListOf<String>("\\Q\\E", "\\Q\\E\\Q\\E", " \\Q\\E#c\n")) {
                    assertSame(operand + repeat + gap + "?", Pattern.COMMENTS)
                    assertSame(operand + repeat + "?" + gap + "?", Pattern.COMMENTS)
                    assertSame(operand + repeat + "?" + gap + "+", Pattern.COMMENTS)
                    assertSame("(?x:" + operand + repeat + gap + "?)", 0)
                }
            }
        }
    }

    @Test
    fun operandFreeCountedRepeatsRemainTransparentToEmptyQuotes() {
        for (prefix in mutableListOf<String>("", "(?i)", "a+", "|", "(?:")) {
            for (repeat in mutableListOf<String>("{0}", "{1}", "{01,03}")) {
                for (modifier in mutableListOf<String>("?", "+")) {
                    var regex = prefix + repeat + "\\Q\\E" + modifier
                    if (prefix == "(?:") regex += ")"
                    assertSame(regex, 0)
                }
            }
        }
    }

    @Test
    @DisabledForCrosscheck("SafeRE rejects possessive repetition over consuming operands")
    fun consumingPossessiveRejectionHasOnePathForEquivalentSpellings() {
        for (operand in mutableListOf<String>("a", "(a)", "(?:a?)", "\\p{Lower}", "^\\p{Lower}")) {
            for (repeat in mutableListOf<String>("?", "*", "+", "{1}", "{0,2}", "{1,3}")) {
                for (gap in mutableListOf<String>("", "\\Q\\E", "\\Q\\E\\Q\\E", " \\Q\\E#c\n")) {
                    Assertions.assertThatThrownBy(ThrowingCallable {
                        Pattern.compile(
                            operand + repeat + gap + "+",
                            Pattern.COMMENTS
                        )
                    })
                        .isInstanceOf(PatternSyntaxException::class.java)
                        .hasMessageContaining("possessive")
                }
            }
        }
    }

    @Test
    fun zeroWidthPossessivesAndQuotedClassRangesKeepTheirSemantics() {
        for (regex in mutableListOf<String>(
            "()*\\Q\\E+",
            "(){2}\\Q\\E+",
            "(){0,2}\\Q\\E+",
            "[\\Qab\\E-z]",
            "[a-\\Qyz\\E]",
            "[\\Q-]&^# \\E]",
            "[a&\\Q\\E&[a-z]]"
        )) {
            assertSame(regex!!, 0)
            assertSame(regex, Pattern.COMMENTS)
        }
    }

    @Test
    fun quotedClassSpansKeepCodePointsAndRangeEndpointsAcrossLengths() {
        val inputs: MutableList<String> =
            mutableListOf<String>("", "a", "b", "z", "Z", "A", "~", "😀", "𠀀", "#", " ", "&", "-", "]")
        for (length in mutableListOf<Int>(3, 4, 5, 7, 8, 9, 15, 16, 17, 64, 65, 257)) {
            for (unit in mutableListOf<String>("a", "😀", "# &-]")) {
                val quote = "\\QZ" + unit!!.repeat(length!!) + "z\\E"
                for (regex in java.util.List.of<String?>(
                    "[" + quote + "]",
                    "[^" + quote + "]",
                    "[" + quote + "-~]",
                    "[A-" + quote + "]",
                    "[[" + quote + "]&&[^z]]",
                    "[\\Q\\E" + quote + "\\Qab\\E]"
                )) {
                    assertSame(regex, 0, inputs)
                    assertSame(regex, Pattern.COMMENTS, inputs)
                }
            }
        }
    }

    @Test
    fun rangeEndpointsConsumeOneAmpersandBeforeRecognizingIntersections() {
        for (lower in mutableListOf<String>("!", "%", "&", "\\Q&\\E")) {
            for (upperAndTail in mutableListOf<String>("&&", "\\Q&\\E&")) {
                val regex = "[" + lower + "-" + upperAndTail + "]"
                assertSame(regex, 0)
                assertSame(regex, Pattern.COMMENTS)
            }
        }
    }

    @Test
    fun rangeEndpointControlsKeepDelimiterRolesAndValidation() {
        val inputs: MutableList<String> = mutableListOf<String>("!", "-", "A", "]", "a", "!]", "-]", "A]", "]]")
        for (regex in mutableListOf<String>("[!-]]", "[!-\\Q\\E]]", "[!-\\]]", "[!-\\Q]\\E]")) {
            assertSame(regex!!, 0, inputs)
            assertSame(regex, Pattern.COMMENTS, inputs)
        }
    }

    @Test
    fun leadingClassTriviaKeepsCaretsLiteralWhileEmptyQuotesAreTransparent() {
        for (regex in mutableListOf<String>(
            "[ ^a]", "[#c\n^a]", "[[ ^a]]", "[ ^]", "[\\Q\\E^a]", "[ \\Q\\E^a]", "[a&&[ ^a]]"
        )) {
            assertSame(regex!!, Pattern.COMMENTS)
        }
    }

    @Test
    fun surrogateEscapePairsUseTheSameComponentRulesAsIndividualEscapes() {
        for (high in mutableListOf<String>("\\uD83D", "\\uD840")) {
            for (low in mutableListOf<String>("DE00", "DC00", "D800", "0041")) {
                for (gap in mutableListOf<String>("", " ", "#c\n", "\\Q\\E")) {
                    for (between in mutableListOf<String>("", " ", "\\Q\\E")) {
                        val regex = high + between + "\\u" + low!!.substring(0, 2) + gap + low.substring(2)
                        for (expression in java.util.List.of<String>(regex, "[" + regex + "]", regex + "\\uDE00")) {
                            assertSame(expression, 0)
                            assertSame(expression, Pattern.COMMENTS)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun flagChangesDoNotReuseLookaheadFromThePreviousMode() {
        for (regex in mutableListOf<String>(
            "(?x:a b) c",
            "(?x)( ?:a)",
            "(?x)(? x:a)",
            "(?x)(?<n ame>a)",
            "(?x)a{1 2}",
            "(?x)a{ 1}",
            "(?x)a{1, 2}",
            "(?x)a(?-x) b",
            "(?x:a(?-x: b)c) d",
            "(?x)a+\\Q\\E?(?-x) b",
            "(?x:a#x\n)b c",
            "(?x)[a #x\n b](?-x) c"
        )) {
            assertSame(regex!!, 0)
        }
        val regex = "(?x)\\Q #😀+ \\E"
        Assertions.assertThat(Pattern.compile(regex).pattern()).isEqualTo(regex)
        Assertions.assertThat(Pattern.compile(regex).matcher(" #😀+ ").matches()).isTrue()
        Assertions.assertThat(Pattern.compile(regex, Pattern.LITERAL).matcher(regex).matches()).isTrue()
    }

    @Test
    @DisabledForCrosscheck("Quoting delimiters in ignored comments cannot affect later syntax")
    fun ignoredCommentQuotesDoNotChangeLexicalState() {
        Assertions.assertThat(Pattern.compile("(?x)#\\Q\n.").matcher("x").matches()).isTrue()
        Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("(?x)#\\Q\n(") })
            .isInstanceOf(PatternSyntaxException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("(?x)#\\Q\n\\E") })
            .isInstanceOf(PatternSyntaxException::class.java)
    }

    @Test
    @DisabledForCrosscheck("Original UTF-16 coordinates are SafeRE's diagnostic contract")
    fun errorsKeepOriginalSourceAndUtf16Positions() {
        val regex = "😀\\Q\\E\\p{NotAProperty}"
        try {
            Pattern.compile(regex)
            throw AssertionError("expected syntax error")
        } catch (e: PatternSyntaxException) {
            Assertions.assertThat(e.pattern).isEqualTo(regex)
            Assertions.assertThat(e.index).isEqualTo(regex.indexOf("\\p"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["a", "ab", "😀", "ab😀# &-]abcdefghi"])
    @DisabledForCrosscheck("Compilation allocation is a SafeRE complexity invariant")
    @Throws(
        ReflectiveOperationException::class
    )
    fun manyShortQuotedClassFragmentsAllocateLinearly(span: String?) {
        // #1059: suffix-sized buffers made short quoted spans allocate quadratically. The streaming
        // lexer introduced in #1071 must keep allocation linear for every span length and code point.
        // ThreadMXBean is a supported public JDK management API, accessed reflectively so this
        // optional assertion does not add a jdk.management module dependency to the library.
        val bean =
            Class.forName("java.lang.management.ManagementFactory")
                .getMethod("getThreadMXBean")
                .invoke(null)
        val type = Class.forName("com.sun.management.ThreadMXBean")
        Assumptions.assumeTrue(type.isInstance(bean))
        Assumptions.assumeTrue(
            (type.getMethod("isThreadAllocatedMemorySupported").invoke(bean) as Boolean?)!!
        )
        type.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
        val allocated = type.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val patterns: Array<String> = arrayOfNulls<String>(3).let { raw ->
            for (i in raw.indices) {
                raw[i] = "[" + ("\\Q" + span + "\\E").repeat(128 shl (2 * i)) + "]"
            }
            raw.requireNoNulls()
        }
        for (i in 0..11) {
            for (regex in patterns) {
                Pattern.compile(regex)
            }
        }
        val thread = Thread.currentThread().threadId()
        var previous: Long = 0
        for (regex in patterns) {
            val before = allocated.invoke(bean, thread) as Long
            Pattern.compile(regex)
            val bytes = (allocated.invoke(bean, thread) as Long?)!! - before
            if (previous != 0L) {
                // Four times as many fixed-size spans may allocate proportionally, not quadratically.
                Assertions.assertThat(bytes)
                    .`as`("allocation for %s UTF-16 units", regex.length)
                    .isLessThan(previous * 6)
            }
            previous = bytes
        }
    }

    companion object {
        private val INPUTS: MutableList<String> = mutableListOf<String>(
            "",
            "a",
            "aa",
            "aaa",
            "aaaa",
            "A",
            "!",
            "&",
            "%",
            "!a",
            "mz",
            " ",
            "#",
            "+",
            "a+",
            "😀",
            "𠀀",
            "\uD83D",
            "\uD840",
            "\uDE00",
            "\uDC00",
            "\uD83D\uD800\uDE00"
        )

        private fun assertSame(regex: String, flags: Int, inputs: MutableList<String> = INPUTS) {
            val expected: java.util.regex.Pattern?
            try {
                expected = java.util.regex.Pattern.compile(regex, flags)
            } catch (e: PatternSyntaxException) {
                Assertions.assertThatThrownBy(ThrowingCallable { org.safere.Pattern.compile(regex, flags) })
                    .`as`("rejection of %s with flags %s", regex, flags)
                    .isInstanceOf(PatternSyntaxException::class.java)
                return
            }
            val actual = org.safere.Pattern.compile(regex, flags)
            for (input in inputs) {
                val jdk = expected.matcher(input)
                val safe = actual.matcher(input)
                while (true) {
                    val found = jdk.find()
                    Assertions.assertThat(safe.find())
                        .`as`("find: %s flags %s input %s", regex, flags, input)
                        .isEqualTo(found)
                    if (!found) break
                    for (group in 0..jdk.groupCount()) {
                        Assertions.assertThat(safe.start(group))
                            .`as`("start: %s group %s", regex, group)
                            .isEqualTo(jdk.start(group))
                        Assertions.assertThat(safe.end(group))
                            .`as`("end: %s group %s", regex, group)
                            .isEqualTo(jdk.end(group))
                        Assertions.assertThat(safe.group(group))
                            .`as`("capture: %s group %s", regex, group)
                            .isEqualTo(jdk.group(group))
                    }
                }
            }
        }
    }
}
