// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

import java.util.*
import java.util.function.Function
import java.util.regex.MatchResult
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Shared regex comparison helpers for exhaustive sweeps.  */
internal object RegexSweep {
    fun jdkTraceOutcome(regex: String, flags: Int, inputs: MutableList<String>, findLimit: Int): Outcome {
        try {
            val pattern = Pattern.compile(regex, flags)
            return Outcome(true, operationTrace(pattern, inputs, findLimit), "")
        } catch (e: PatternSyntaxException) {
            return Outcome(false, "", e.description)
        } catch (e: StackOverflowError) {
            return Outcome(false, "", "StackOverflowError")
        } catch (e: RuntimeException) {
            return Outcome(false, "", e.javaClass.getSimpleName() + ": " + e.message)
        }
    }

    fun safeReTraceOutcome(regex: String?, flags: Int, inputs: MutableList<String>, findLimit: Int): Outcome {
        try {
            val pattern = org.safere.Pattern.compile(regex, flags)
            return Outcome(true, operationTrace(pattern, inputs, findLimit), "")
        } catch (e: PatternSyntaxException) {
            return Outcome(false, "", e.description)
        } catch (e: StackOverflowError) {
            return Outcome(false, "", "StackOverflowError")
        } catch (e: RuntimeException) {
            return Outcome(false, "", e.javaClass.getSimpleName() + ": " + e.message)
        }
    }

    fun semanticallyEqual(left: Outcome, right: Outcome): Boolean {
        if (left.accepted != right.accepted) {
            return false
        }
        return !left.accepted || left.trace == right.trace
    }

    private fun operationTrace(
        pattern: Pattern, inputs: MutableList<String>, findLimit: Int
    ): String {
        val result = StringBuilder()
        val groupCount = pattern.matcher("").groupCount()
        for (input in inputs) {
            val matcher = pattern.matcher(input)
            appendTrace(result, input, "matches", matcher.matches(), matcher)
            matcher.reset()
            appendTrace(result, input, "lookingAt", matcher.lookingAt(), matcher)
            matcher.reset()
            appendFindTrace(result, input, matcher, findLimit)
            if (groupCount > 0) {
                appendReplacementTrace(result, input, pattern, groupCount)
            }
        }
        return result.toString()
    }

    private fun operationTrace(
        pattern: org.safere.Pattern, inputs: MutableList<String>, findLimit: Int
    ): String {
        val result = StringBuilder()
        val groupCount = pattern.matcher("").groupCount()
        for (input in inputs) {
            val matcher = pattern.matcher(input)
            appendTrace(result, input, "matches", matcher.matches(), matcher)
            matcher.reset()
            appendTrace(result, input, "lookingAt", matcher.lookingAt(), matcher)
            matcher.reset()
            appendFindTrace(result, input, matcher, findLimit)
            if (groupCount > 0) {
                appendReplacementTrace(result, input, pattern, groupCount)
            }
        }
        return result.toString()
    }

    private fun appendReplacementTrace(
        result: StringBuilder, input: String, pattern: Pattern, groupCount: Int
    ) {
        for (group in 1..groupCount) {
            val replacement = "[$" + group + "]"
            val capturedGroup = group
            appendSeparator(result)
            result
                .append(escape(input))
                .append(":replaceAllG")
                .append(group)
                .append('=')
                .append(escape(pattern.matcher(input).replaceAll(replacement)))
            appendSeparator(result)
            result
                .append(escape(input))
                .append(":replaceFirstG")
                .append(group)
                .append('=')
                .append(escape(pattern.matcher(input).replaceFirst(replacement)))
            appendSeparator(result)
            result
                .append(escape(input))
                .append(":replaceFunctionG")
                .append(group)
                .append('=')
                .append(
                    escape(
                        pattern
                            .matcher(input)
                            .replaceAll(Function { match: MatchResult? -> "[" + match!!.group(capturedGroup) + "]" })
                    )
                )
        }
    }

    private fun appendReplacementTrace(
        result: StringBuilder, input: String, pattern: org.safere.Pattern, groupCount: Int
    ) {
        for (group in 1..groupCount) {
            val replacement = "[$" + group + "]"
            val capturedGroup = group
            appendSeparator(result)
            result
                .append(escape(input))
                .append(":replaceAllG")
                .append(group)
                .append('=')
                .append(escape(pattern.matcher(input).replaceAll(replacement)))
            appendSeparator(result)
            result
                .append(escape(input))
                .append(":replaceFirstG")
                .append(group)
                .append('=')
                .append(escape(pattern.matcher(input).replaceFirst(replacement)))
            appendSeparator(result)
            result
                .append(escape(input))
                .append(":replaceFunctionG")
                .append(group)
                .append('=')
                .append(
                    escape(
                        pattern
                            .matcher(input)
                            .replaceAll(Function { match: MatchResult? -> "[" + match!!.group(capturedGroup) + "]" })
                    )
                )
        }
    }

    private fun appendTrace(
        result: StringBuilder,
        input: String,
        operation: String?,
        matched: Boolean,
        matcher: Matcher
    ) {
        appendSeparator(result)
        result.append(escape(input)).append(':').append(operation).append('=').append(matched)
        if (matched) {
            result.append('@').append(matcher.start()).append('-').append(matcher.end())
            appendGroups(result, matcher)
        }
    }

    private fun appendTrace(
        result: StringBuilder,
        input: String,
        operation: String?,
        matched: Boolean,
        matcher: org.safere.Matcher
    ) {
        appendSeparator(result)
        result.append(escape(input)).append(':').append(operation).append('=').append(matched)
        if (matched) {
            result.append('@').append(matcher.start()).append('-').append(matcher.end())
            appendGroups(result, matcher)
        }
    }

    private fun appendFindTrace(
        result: StringBuilder, input: String, matcher: Matcher, findLimit: Int
    ) {
        var count = 0
        while (count < findLimit) {
            val matched = matcher.find()
            appendFindMatch(result, input, count, matched, matcher)
            if (!matched) {
                break
            }
            count++
        }
    }

    private fun appendFindTrace(
        result: StringBuilder, input: String, matcher: org.safere.Matcher, findLimit: Int
    ) {
        var count = 0
        while (count < findLimit) {
            val matched = matcher.find()
            appendFindMatch(result, input, count, matched, matcher)
            if (!matched) {
                break
            }
            count++
        }
    }

    private fun appendFindMatch(
        result: StringBuilder,
        input: String,
        count: Int,
        matched: Boolean,
        matcher: Matcher
    ) {
        appendSeparator(result)
        result.append(escape(input)).append(":find").append(count).append('=').append(matched)
        if (matched) {
            result.append('@').append(matcher.start()).append('-').append(matcher.end())
            appendGroups(result, matcher)
        }
    }

    private fun appendFindMatch(
        result: StringBuilder, input: String, count: Int, matched: Boolean, matcher: org.safere.Matcher
    ) {
        appendSeparator(result)
        result.append(escape(input)).append(":find").append(count).append('=').append(matched)
        if (matched) {
            result.append('@').append(matcher.start()).append('-').append(matcher.end())
            appendGroups(result, matcher)
        }
    }

    private fun appendGroups(result: StringBuilder, matcher: Matcher) {
        appendGroups(
            result,
            matcher.groupCount(),
            RegexSweep.GroupInt { group: Int -> matcher.start(group) },
            RegexSweep.GroupInt { group: Int -> matcher.end(group) },
            RegexSweep.GroupString { group: Int -> matcher.group(group) })
    }

    private fun appendGroups(result: StringBuilder, matcher: org.safere.Matcher) {
        appendGroups(
            result,
            matcher.groupCount(),
            RegexSweep.GroupInt { group: Int -> matcher.start(group) },
            RegexSweep.GroupInt { group: Int -> matcher.end(group) },
            RegexSweep.GroupString { group: Int -> matcher.group(group) })
    }

    private fun appendGroups(
        result: StringBuilder, groupCount: Int, start: GroupInt, end: GroupInt, groupText: GroupString
    ) {
        if (groupCount == 0) {
            return
        }
        result.append("{groups=").append(groupCount)
        for (group in 1..groupCount) {
            val text = groupText.get(group)
            result.append(";g").append(group).append('=')
            if (text == null) {
                result.append("null")
            } else {
                result
                    .append(start.get(group))
                    .append('-')
                    .append(end.get(group))
                    .append(':')
                    .append(escape(text))
            }
        }
        result.append('}')
    }

    private fun appendSeparator(result: StringBuilder) {
        if (result.length > 0) {
            result.append(',')
        }
    }

    private fun escape(value: String): String {
        val result = StringBuilder()
        for (i in 0..<value.length) {
            when (val c = value.get(i)) {
                '\\' -> result.append("\\\\")
                '\n' -> result.append("\\n")
                '\r' -> result.append("\\r")
                '\t' -> result.append("\\t")
                '"' -> result.append("\\\"")
                else -> {
                    if (Character.isISOControl(c) || Character.isSurrogate(c)) {
                        result.append(String.format(Locale.ROOT, "\\u%04X", c.code))
                    } else {
                        result.append(c)
                    }
                }
            }
        }
        return result.toString()
    }

    @JvmRecord
    internal data class Outcome(@JvmField val accepted: Boolean, @JvmField val trace: String?, val error: String?)

    private interface GroupInt {
        fun get(group: Int): Int
    }

    private interface GroupString {
        fun get(group: Int): String?
    }
}
