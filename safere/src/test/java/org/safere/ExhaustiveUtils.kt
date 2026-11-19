// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.regex.PatternSyntaxException
import kotlin.math.min

/**
 * Exhaustive test utilities ported from RE2's testing infrastructure.
 * 
 * 
 * Generates all possible regular expressions within given parameters (maxatoms, maxops, alphabet
 * of atoms, set of operators), then generates all possible strings of up to a given length over a
 * given alphabet, and compares SafeRE against JDK regex for each (regexp, string) pair.
 * 
 * 
 * This is the mechanical approach to finding bugs: enumerate all combinations and check that
 * SafeRE and JDK agree.
 */
internal object ExhaustiveUtils {
    // Standard egrep operators (like RE2's EgrepOps, minus \C*)
    val EGREP_OPS: MutableList<String> = mutableListOf<String>("%s%s", "%s|%s", "%s*", "%s+", "%s?")

    /**
     * Run an exhaustive test with the given configuration. Generates all regexps from atoms+ops, all
     * strings from alphabet, and compares SafeRE vs JDK for each pair. Regexps are tested in parallel
     * using the common ForkJoinPool.
     * 
     * @return the number of test cases executed
     */
    fun run(config: Config): Int {
        val allRegexps: MutableList<String> = ArrayList<String>()
        generateRegexps(
            config.maxAtoms,
            config.maxOps,
            config.atoms!!,
            config.ops!!,
            config.wrapper!!,
            Consumer { e: String? -> allRegexps.add(e!!) })

        val strings = generateStrings(config.maxStrLen, config.strAlphabet!!)
        val failures: MutableList<TestResult?> = Collections.synchronizedList<TestResult?>(ArrayList<TestResult?>())
        val testCount = AtomicInteger()

        allRegexps.parallelStream()
            .forEach { regexp: String? ->
                val localFailures: MutableList<TestResult?> = ArrayList<TestResult?>()
                for (text in strings) {
                    testCount.addAndGet(testPair(regexp!!, text, config.flags, localFailures))

                    if (config.testLongText) {
                        testCount.addAndGet(
                            testPair(
                                regexp, Config.LONG_TEXT_PREFIX + text, config.flags, localFailures
                            )
                        )
                    }
                }
                if (!localFailures.isEmpty()) {
                    failures.addAll(localFailures)
                }
            }

        if (!failures.isEmpty()) {
            val show = min(failures.size, 50)
            val sb = StringBuilder()
            sb.append(
                String.format(
                    "%d failures out of %d tests (showing first %d):%n",
                    failures.size, testCount.get(), show
                )
            )
            for (i in 0..<show) {
                sb.append("  ").append(failures.get(i)).append("\n")
            }
            Assertions.fail<Any?>(sb.toString())
        }

        return testCount.get()
    }

    /**
     * Test a single (regexp, text, flags) triple. Compares SafeRE vs JDK for matches() and find().
     * Returns the number of test cases executed.
     */
    fun testPair(regexp: String, text: String, flags: Int, failures: MutableList<TestResult?>): Int {
        var tests = 0

        // Skip patterns with nested repetition and capture groups — known behavioral difference.
        // NFA engines start a fresh thread at each position with captures initialized to -1.
        // When a {0,}/*  repetition matches zero times, captures from earlier (failed) starting
        // positions are not preserved. JDK's backtracking engine leaks captures from failed attempts
        // (and is itself inconsistent: `(a)*$` vs `(?:(a))*$` give different g1 results).
        // This is a suspected JDK bug, not a SafeRE bug.
        // See https://github.com/safere/safere/issues/42 (bug 2) and
        // https://github.com/safere/safere/issues/52 for detailed analysis.
        if (NESTED_REP_CAPTURE.matcher(regexp).find()) {
            return 0
        }

        // Try to compile with JDK first — if JDK rejects it, skip
        val jdkPat: java.util.regex.Pattern?
        try {
            jdkPat = java.util.regex.Pattern.compile(regexp, flags)
        } catch (e: PatternSyntaxException) {
            return 0
        }

        // Try to compile with SafeRE — if it rejects (unsupported feature), skip
        val saferePat: Pattern?
        try {
            saferePat = Pattern.compile(regexp, flags)
        } catch (e: PatternSyntaxException) {
            return 0
        }

        val utf8Coordinates: Utf8Coordinates = Utf8Coordinates.forText(text)
        val utf8 = text.toByteArray(StandardCharsets.UTF_8)

        // Compare matches()
        tests++
        try {
            val jdkM = jdkPat.matcher(text)
            val safereM = saferePat.matcher(text)

            val jdkMatches = jdkM.matches()
            val safereMatches = safereM.matches()

            if (jdkMatches != safereMatches) {
                failures.add(
                    TestResult(
                        regexp,
                        text,
                        flags,
                        "matches",
                        safereMatches.toString(),
                        jdkMatches.toString()
                    )
                )
            } else if (jdkMatches) {
                // Compare group positions (up to the shared group count)
                val sharedGroups = min(jdkM.groupCount(), safereM.groupCount())
                val jdkGroups = extractGroups(jdkM, sharedGroups)
                val safereGroups = extractGroups(safereM, sharedGroups)
                if (jdkGroups != safereGroups) {
                    failures.add(
                        TestResult(regexp, text, flags, "matches/groups", safereGroups, jdkGroups)
                    )
                }
            }
        } catch (e: Exception) {
            failures.add(
                TestResult(
                    regexp, text, flags, "matches", "EXCEPTION: " + e.message, "(expected)"
                )
            )
        }

        // Compare find()
        tests++
        try {
            val jdkM = jdkPat.matcher(text)
            val safereM = saferePat.matcher(text)
            val safereByteM = saferePat.matcher(Utf8Input.validated(utf8))

            val jdkFound = jdkM.find()
            val safereFound = safereM.find()
            val safereByteFound = safereByteM.find()

            if (jdkFound != safereFound) {
                failures.add(
                    TestResult(
                        regexp,
                        text,
                        flags,
                        "find",
                        safereFound.toString(),
                        jdkFound.toString()
                    )
                )
            } else if (jdkFound != safereByteFound) {
                failures.add(
                    TestResult(
                        regexp,
                        text,
                        flags,
                        "find(bytes)",
                        safereByteFound.toString(),
                        jdkFound.toString()
                    )
                )
            } else if (jdkFound) {
                // Compare group(0) positions
                val jdkMatch = String.format("[%d,%d)", jdkM.start(), jdkM.end())
                val safereMatch = String.format("[%d,%d)", safereM.start(), safereM.end())
                val byteStart = safereByteM.start()
                val byteEnd = safereByteM.end()
                val safereByteMatch = String.format(
                    "[%d,%d)", utf8Coordinates.toUtf16(byteStart), utf8Coordinates.toUtf16(byteEnd)
                )

                if (jdkMatch != safereMatch) {
                    failures.add(TestResult(regexp, text, flags, "find/pos", safereMatch, jdkMatch))
                } else if (jdkMatch != safereByteMatch) {
                    failures.add(
                        TestResult(regexp, text, flags, "find/pos(bytes)", safereByteMatch, jdkMatch)
                    )
                } else {
                    // Compare all group positions (up to shared count)
                    val sharedGroups = min(jdkM.groupCount(), safereM.groupCount())
                    val jdkGroups = extractGroups(jdkM, sharedGroups)
                    val safereGroups = extractGroups(safereM, sharedGroups)
                    val safereByteGroups =
                        extractGroupsForBytes(safereByteM, sharedGroups, utf8Coordinates)
                    if (jdkGroups != safereGroups) {
                        failures.add(
                            TestResult(regexp, text, flags, "find/groups", safereGroups, jdkGroups)
                        )
                    } else if (jdkGroups != safereByteGroups) {
                        failures.add(
                            TestResult(
                                regexp, text, flags, "find/groups(bytes)", safereByteGroups, jdkGroups
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            failures.add(
                TestResult(
                    regexp, text, flags, "find", "EXCEPTION: " + e.message, "(expected)"
                )
            )
        }

        // Compare find-all (all occurrences)
        tests++
        try {
            val jdkM = jdkPat.matcher(text)
            val safereM = saferePat.matcher(text)
            val safereByteM = saferePat.matcher(Utf8Input.validated(utf8))

            val jdkAll: MutableList<String> = ArrayList<String>()
            while (jdkM.find()) {
                jdkAll.add(String.format("[%d,%d)", jdkM.start(), jdkM.end()))
            }
            val safereAll: MutableList<String> = ArrayList<String>()
            while (safereM.find()) {
                safereAll.add(String.format("[%d,%d)", safereM.start(), safereM.end()))
            }
            val safereByteAll: MutableList<String> = ArrayList<String>()
            while (safereByteM.find()) {
                val byteStart = safereByteM.start()
                val byteEnd = safereByteM.end()
                safereByteAll.add(
                    String.format(
                        "[%d,%d)", utf8Coordinates.toUtf16(byteStart), utf8Coordinates.toUtf16(byteEnd)
                    )
                )
            }

            if (jdkAll != safereAll) {
                failures.add(
                    TestResult(
                        regexp, text, flags, "findAll", safereAll.toString(), jdkAll.toString()
                    )
                )
            } else if (jdkAll != safereByteAll) {
                failures.add(
                    TestResult(
                        regexp,
                        text,
                        flags,
                        "findAll(bytes)",
                        safereByteAll.toString(),
                        jdkAll.toString()
                    )
                )
            }
        } catch (e: Exception) {
            failures.add(
                TestResult(
                    regexp, text, flags, "findAll", "EXCEPTION: " + e.message, "(expected)"
                )
            )
        }

        return tests
    }

    /** Extract group positions from a matcher as a string like "g0=[0,5) g1=[1,3) g2=[-1,-1)".  */
    private fun extractGroups(m: java.util.regex.Matcher, maxGroups: Int): String {
        val count = min(m.groupCount(), maxGroups)
        val sb = StringBuilder()
        for (i in 0..count) {
            if (i > 0) {
                sb.append(' ')
            }
            sb.append(String.format("g%d=[%d,%d)", i, m.start(i), m.end(i)))
        }
        return sb.toString()
    }

    /** Extract group positions from a SafeRE matcher.  */
    private fun extractGroups(m: Matcher, maxGroups: Int): String {
        val count = min(m.groupCount(), maxGroups)
        val sb = StringBuilder()
        for (i in 0..count) {
            if (i > 0) {
                sb.append(' ')
            }
            sb.append(String.format("g%d=[%d,%d)", i, m.start(i), m.end(i)))
        }
        return sb.toString()
    }

    private fun extractGroupsForBytes(
        m: Utf8Matcher, maxGroups: Int, coordinates: Utf8Coordinates
    ): String {
        val count = min(m.groupCount(), maxGroups)
        val sb = StringBuilder()
        for (i in 0..count) {
            if (i > 0) {
                sb.append(' ')
            }
            val start = m.start(i)
            val end = m.end(i)
            val charStart = if (start < 0) -1 else coordinates.toUtf16(start)
            val charEnd = if (end < 0) -1 else coordinates.toUtf16(end)
            sb.append(String.format("g%d=[%d,%d)", i, charStart, charEnd))
        }
        return sb.toString()
    }

    /**
     * Generate all possible regular expressions from atoms and operators using RE2's postfix
     * approach. For each generated regexp, also generates anchored variants: ^(re)$, ^(re), (re)$.
     */
    fun generateRegexps(
        maxAtoms: Int,
        maxOps: Int,
        atoms: MutableList<String>,
        ops: MutableList<String>,
        wrapper: String,
        handler: Consumer<String?>
    ) {
        var maxAtoms = maxAtoms
        var maxOps = maxOps
        if (atoms.isEmpty()) {
            maxAtoms = 0
        }
        if (ops.isEmpty()) {
            maxOps = 0
        }
        val postfix: MutableList<String> = ArrayList<String>()
        generatePostfix(postfix, 0, 0, 0, maxAtoms, maxOps, atoms, ops, wrapper, handler)
    }

    private fun generatePostfix(
        post: MutableList<String>,
        nstk: Int,
        opsUsed: Int,
        atomsUsed: Int,
        maxAtoms: Int,
        maxOps: Int,
        atoms: MutableList<String>,
        ops: MutableList<String>,
        wrapper: String,
        handler: Consumer<String?>
    ) {
        if (nstk == 1) {
            runPostfix(post, wrapper, handler)
        }

        // Early out: can't get back to single expression
        if (opsUsed + nstk - 1 > maxOps) {
            return
        }

        // Add atoms
        if (atomsUsed < maxAtoms) {
            for (atom in atoms) {
                post.add(atom!!)
                generatePostfix(
                    post, nstk + 1, opsUsed, atomsUsed + 1, maxAtoms, maxOps, atoms, ops, wrapper, handler
                )
                post.removeLast()
            }
        }

        // Add operators
        if (opsUsed < maxOps) {
            for (op in ops) {
                val nargs = countArgs(op)
                if (nargs <= nstk) {
                    post.add(op)
                    generatePostfix(
                        post,
                        nstk - nargs + 1,
                        opsUsed + 1,
                        atomsUsed,
                        maxAtoms,
                        maxOps,
                        atoms,
                        ops,
                        wrapper,
                        handler
                    )
                    post.removeLast()
                }
            }
        }
    }

    /** Evaluate postfix sequence into a regexp string and emit it (plus anchored variants).  */
    private fun runPostfix(post: MutableList<String>, wrapper: String, handler: Consumer<String?>) {
        val regexps: Deque<String> = ArrayDeque<String>()
        for (cmd in post) {
            val nargs = countArgs(cmd)
            if (nargs == 0) {
                regexps.push(cmd)
            } else if (nargs == 1) {
                val a = regexps.pop()
                regexps.push("(?:" + substituteFirst(cmd, a) + ")")
            } else if (nargs == 2) {
                val b = regexps.pop()
                val a = regexps.pop()
                val result = substituteFirst(substituteFirst(cmd, a), b)
                regexps.push("(?:" + result + ")")
            }
        }
        if (regexps.size != 1) {
            return  // invalid sequence
        }

        var re = regexps.pop()
        if (!wrapper.isEmpty()) {
            re = wrapper.replace("%s", re)
        }

        // Emit the regexp and anchored variants (like RE2's RunPostfix)
        handler.accept(re)
        handler.accept("^(?:" + re + ")$")
        handler.accept("^(?:" + re + ")")
        handler.accept("(?:" + re + ")$")
    }

    /** Count occurrences of "%s" in a string.  */
    private fun countArgs(s: String): Int {
        var count = 0
        var idx = 0
        while ((s.indexOf("%s", idx).also { idx = it }) != -1) {
            count++
            idx += 2
        }
        return count
    }

    /** Replace the first occurrence of "%s" with the literal value (no regex interpretation).  */
    private fun substituteFirst(template: String, value: String): String {
        val idx = template.indexOf("%s")
        if (idx == -1) {
            return template
        }
        return template.substring(0, idx) + value + template.substring(idx + 2)
    }

    /** Generate all strings of length 0..maxLen over the given alphabet.  */
    fun generateStrings(maxLen: Int, alphabet: MutableList<String>): MutableList<String> {
        val result: MutableList<String> = ArrayList<String>()
        result.add("") // empty string
        generateStringsRecursive(result, StringBuilder(), maxLen, alphabet)
        return result
    }

    private fun generateStringsRecursive(
        result: MutableList<String>, current: StringBuilder, maxLen: Int, alphabet: MutableList<String>
    ) {
        if (current.length > 0) {
            result.add(current.toString())
        }
        if (current.length >= maxLen) {
            return
        }
        for (ch in alphabet) {
            current.append(ch)
            generateStringsRecursive(result, current, maxLen, alphabet)
            current.delete(current.length - ch.length, current.length)
        }
    }

    /**
     * Pattern detecting capture groups inside zero-or-more repetition (`*`, `+?`, `{N,}`, etc.) anchored by `$`. NFA engines start a fresh thread at each position with
     * captures initialized to -1. When a `{0,}`/`*` repetition matches zero times,
     * captures from earlier (failed) starting positions are not preserved. JDK's backtracking engine
     * leaks captures from failed attempts (and is itself inconsistent: `(a)*$` vs `(?:(a))*$` give different g1 results). This is an inherent NFA vs backtracking difference — a
     * suspected JDK bug, not a SafeRE bug.
     * 
     * 
     * See [issue #42](https://github.com/safere/safere/issues/42) (bug 2) and [issue #52](https://github.com/safere/safere/issues/52) for detailed analysis.
     */
    private val NESTED_REP_CAPTURE: java.util.regex.Pattern = java.util.regex.Pattern.compile(
        "\\([^)]*\\)[^)]*(?:\\{\\d+[,}]|[*+?]).*(?:\\{\\d+[,}]|[*+?])|"
                + "\\([^)]*\\)[^)]*(?:[*+?]|\\{\\d+[,}]).*\\$"
    )

    /** Escape non-printable characters for error messages.  */
    fun escape(s: String): String {
        val sb = StringBuilder()
        for (i in 0..<s.length) {
            val c = s.get(i)
            if (c == '\n') {
                sb.append("\\n")
            } else if (c == '\r') {
                sb.append("\\r")
            } else if (c == '\t') {
                sb.append("\\t")
            } else if (c.code >= 0x20 && c.code < 0x7F) {
                sb.append(c)
            } else {
                sb.append(String.format("\\u%04x", c.code))
            }
        }
        return sb.toString()
    }

    /** Result of a single regexp+string test.  */
    @JvmRecord
    internal data class TestResult(
        val regexp: String?,
        val text: String?,
        val flags: Int,
        val method: String?,
        val safereResult: String?,
        val jdkResult: String?
    ) {
        override fun toString(): String {
            return String.format(
                "%s(\"%s\", \"%s\", flags=%d): safere=%s, jdk=%s",
                method, escape(regexp!!), escape(text!!), flags, safereResult, jdkResult
            )
        }
    }

    /** Configuration for an exhaustive test run.  */
    @JvmRecord
    internal data class Config(
        val maxAtoms: Int,
        val maxOps: Int,
        val atoms: MutableList<String>?,
        val ops: MutableList<String>?,
        val maxStrLen: Int,
        val strAlphabet: MutableList<String>?,
        val wrapper: String?,
        val flags: Int,
        val testLongText: Boolean
    ) {
        companion object {
            /** Padding prefix used to push text beyond 256 chars (forcing DFA path).  */
            val LONG_TEXT_PREFIX: String = "#".repeat(260)
        }
    }

    /** Exact scalar-boundary mapping from relative UTF-8 offsets to UTF-16 String offsets.  */
    internal class Utf8Coordinates private constructor(private val utf16ByUtf8Offset: IntArray) {
        fun toUtf16(utf8Offset: Int): Int {
            if (utf8Offset < 0 || utf8Offset >= utf16ByUtf8Offset.size) {
                throw IndexOutOfBoundsException("UTF-8 offset: " + utf8Offset)
            }
            val utf16Offset = utf16ByUtf8Offset[utf8Offset]
            require(utf16Offset >= 0) { "UTF-8 offset is not a Unicode scalar boundary: " + utf8Offset }
            return utf16Offset
        }

        companion object {
            fun forText(text: String): Utf8Coordinates {
                val utf8 = text.toByteArray(StandardCharsets.UTF_8)
                val utf16ByUtf8Offset = IntArray(utf8.size + 1)
                Arrays.fill(utf16ByUtf8Offset, -1)
                var utf8Offset = 0
                var utf16Offset = 0
                while (utf16Offset < text.length) {
                    val first = text.get(utf16Offset)
                    require(
                        !(Character.isSurrogate(first)
                                && !(Character.isHighSurrogate(first)
                                && utf16Offset + 1 < text.length && Character.isLowSurrogate(text.get(utf16Offset + 1))))
                    ) { "String contains an unpaired surrogate at UTF-16 offset " + utf16Offset }
                    utf16ByUtf8Offset[utf8Offset] = utf16Offset
                    val codePoint = text.codePointAt(utf16Offset)
                    utf8Offset += utf8Length(codePoint)
                    utf16Offset += Character.charCount(codePoint)
                }
                utf16ByUtf8Offset[utf8Offset] = text.length
                check(utf8Offset == utf8.size) { "UTF-8 coordinate map length mismatch" }
                return Utf8Coordinates(utf16ByUtf8Offset)
            }

            private fun utf8Length(codePoint: Int): Int {
                if (codePoint <= 0x7f) {
                    return 1
                }
                if (codePoint <= 0x7ff) {
                    return 2
                }
                if (codePoint <= 0xffff) {
                    return 3
                }
                return 4
            }
        }
    }
}
