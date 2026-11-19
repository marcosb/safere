// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Method
import java.util.List

internal class StringRegionViewTest {
    @Test
    @DisabledForCrosscheck("Checks SafeRE bounded acceleration against independent text")
    fun boundedSearchPlansPreserveFindHistoriesAndCaptureOffsets() {
        for (regex in mutableListOf<String?>(
            "q[a-z]+k\\s+b",
            "[a-z]*q[a-z]+k\\s+b",
            "(q[a-z]+)(k\\s+b)",
            "(?i)qab[a-z]+k\\s+b",
            "(?iu:K)[a-z]+k\\s+b",
            "(?iu:𐐀)[a-z]+k\\s+b",
            ".{2}qab",
            "(?:a|bc){1,2}qab",
            "[ab]{2}[xyz]Q",
            "(?m:^qab)",
            "(q[a-z]+)(k\\s+b)$",
            "[a-z]*q[a-z]+k\\s+b\\z",
            "\\p{Cs}[a-z]+"
        )) {
            val pattern = Pattern.compile(regex)
            for (visible in List.of<String?>(
                "",
                "qabck b QABCK B aaxQ bcqab Kabck b 𐐨abck b\nqabck b",
                "x ".repeat(2048) + "qabck b qabck b",
                "\uDE00qabck b\uD83D"
            )) {
                for (prefix in mutableListOf<String?>("!", "\uD83D", "\r")) {
                    val input = prefix + visible + "qabck b\uDE00"
                    val actual =
                        pattern.matcher(input).region(prefix!!.length, prefix.length + visible.length)
                    val expected = pattern.matcher(visible)
                    while (true) {
                        val found = expected.find()
                        Assertions.assertThat(actual.find()).`as`("/%s/ on bounded input", regex).isEqualTo(found)
                        if (!found) {
                            break
                        }
                        for (group in 0..expected.groupCount()) {
                            Assertions.assertThat(actual.start(group))
                                .isEqualTo(
                                    if (expected.start(group) < 0) -1 else prefix.length + expected.start(group)
                                )
                            Assertions.assertThat(actual.end(group))
                                .isEqualTo(if (expected.end(group) < 0) -1 else prefix.length + expected.end(group))
                            Assertions.assertThat(actual.group(group)).isEqualTo(expected.group(group))
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisabledForCrosscheck("Allocation scaling checks SafeRE region implementation behavior")
    fun advancingRegionsAllocateLinearly() {
        val tracker: AllocationTracker = allocationTracker()
        val threadId = Thread.currentThread().threadId()
        val pattern = Pattern.compile("\\w+|\\s+")
        val small = "word 123 ".repeat(256)
        val large = small.repeat(4)
        for (i in 0..7) {
            tokenize(pattern, small)
            tokenize(pattern, large)
        }
        val smallBytes: Long = measure(tracker, threadId, Runnable { tokenize(pattern, small) })
        val largeBytes: Long = measure(tracker, threadId, Runnable { tokenize(pattern, large) })
        Assertions.assertThat(largeBytes).isLessThan(smallBytes * 6)
    }

    @ParameterizedTest
    @ValueSource(strings = ["matches", "lookingAt", "find"])
    @DisabledForCrosscheck("Allocation scaling checks SafeRE region implementation behavior")
    fun shortAttemptsDoNotAllocateWithRegionLength(operation: String?) {
        val tracker: AllocationTracker = allocationTracker()
        val threadId = Thread.currentThread().threadId()
        val pattern = Pattern.compile("z.*")
        val small = "a".repeat(1024)
        val large = "a".repeat(100000)
        val smallAttempts = Runnable { shortAttempts(pattern, small, operation) }
        val largeAttempts = Runnable { shortAttempts(pattern, large, operation) }
        for (i in 0..7) {
            smallAttempts.run()
            largeAttempts.run()
        }
        val smallBytes: Long = measure(tracker, threadId, smallAttempts)
        val largeBytes: Long = measure(tracker, threadId, largeAttempts)
        Assertions.assertThat(largeBytes - smallBytes).isLessThan(8192)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["a", "abc|ab", "abc|xyz", "(?i:abc)", "(?i:k)", "(?i:i)", "(?iu:k)", "[a-z]+", "(a+)(b*)", "(a+?)?", "^a+$", "^a+b$", "^a+[0-9]$", "(?i:^a+b$)", "^a+b\\z", "^a+b\\Z", "(?d:^a+b$)", "(?U:\\b[a-z]+\\b)", "\\X", "\\x{1f600}", ".", ""
        ]
    )
    @DisabledForCrosscheck("Opaque substring semantics intentionally differ from JDK region behavior")
    fun changingRegionsAgreeWithIndependentSubstrings(regex: String?) {
        val pattern = Pattern.compile(regex)
        for (input in mutableListOf<String?>(
            "!aaabb!",
            "!aaaab!",
            "!aaaab\n!",
            "!aaa1!",
            "!abc!",
            "!ABC!",
            "!Kıİſ!",
            "x😀y",
            "!a\u0301!",
            "!\r\n!"
        )) {
            val actual = pattern.matcher(input)
            for (start in 0..input!!.length) {
                for (end in start..input.length) {
                    val expected = pattern.matcher(input.substring(start, end))
                    for (op in 0..2) {
                        actual.region(start, end)
                        expected.reset()
                        var found: Boolean
                        do {
                            found =
                                when (op) {
                                    0 -> expected.matches()
                                    1 -> expected.lookingAt()
                                    else -> expected.find()
                                }
                            val actualFound =
                                when (op) {
                                    0 -> actual.matches()
                                    1 -> actual.lookingAt()
                                    else -> actual.find()
                                }
                            Assertions.assertThat(actualFound)
                                .`as`("%s input=%s region=%s:%s op=%s", regex, input, start, end, op)
                                .isEqualTo(found)
                            if (found) {
                                for (group in 0..expected.groupCount()) {
                                    val expectedStart = expected.start(group)
                                    val expectedEnd = expected.end(group)
                                    Assertions.assertThat(actual.start(group))
                                        .isEqualTo(if (expectedStart < 0) -1 else start + expectedStart)
                                    Assertions.assertThat(actual.end(group))
                                        .isEqualTo(if (expectedEnd < 0) -1 else start + expectedEnd)
                                    Assertions.assertThat(actual.group(group)).isEqualTo(expected.group(group))
                                }
                            }
                        } while (op == 2 && found)
                    }
                }
            }
        }
    }

    @JvmRecord
    private data class AllocationTracker(val threadBean: Any?, val allocatedBytesMethod: Method?) {
        fun allocatedBytes(threadId: Long): Long {
            try {
                return allocatedBytesMethod!!.invoke(threadBean, threadId) as Long
            } catch (e: ReflectiveOperationException) {
                throw LinkageError(e.message, e)
            }
        }
    }

    companion object {
        private fun shortAttempts(pattern: Pattern, input: String, operation: String?) {
            val matcher = pattern.matcher(input)
            for (start in 1..32) {
                matcher.region(start, input.length)
                val found =
                    when (operation) {
                        "matches" -> matcher.matches()
                        "lookingAt" -> matcher.lookingAt()
                        else -> matcher.find()
                    }
                if (found) {
                    throw AssertionError("unexpected match")
                }
            }
        }

        private fun tokenize(pattern: Pattern, input: String) {
            val matcher = pattern.matcher(input)
            var pos = 0
            while (pos < input.length) {
                matcher.region(pos, input.length)
                if (!matcher.lookingAt() || matcher.end() <= pos) {
                    throw AssertionError("tokenizer failed at " + pos)
                }
                pos = matcher.end()
            }
        }

        private fun measure(tracker: AllocationTracker, threadId: Long, operation: Runnable): Long {
            val before = tracker.allocatedBytes(threadId)
            operation.run()
            return tracker.allocatedBytes(threadId) - before
        }

        private fun allocationTracker(): AllocationTracker {
            try {
                val managementFactoryClass = Class.forName("java.lang.management.ManagementFactory")
                val threadBean = managementFactoryClass.getMethod("getThreadMXBean").invoke(null)
                val allocationBeanClass = Class.forName("com.sun.management.ThreadMXBean")
                Assumptions.assumeTrue(allocationBeanClass.isInstance(threadBean))
                val supported = allocationBeanClass.getMethod("isThreadAllocatedMemorySupported")
                val enabled = allocationBeanClass.getMethod("isThreadAllocatedMemoryEnabled")
                val enable =
                    allocationBeanClass.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType)
                val allocated = allocationBeanClass.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
                Assumptions.assumeTrue(supported.invoke(threadBean) as Boolean)
                if (!enabled.invoke(threadBean) as Boolean) {
                    enable.invoke(threadBean, true)
                }
                return AllocationTracker(threadBean, allocated)
            } catch (e: ReflectiveOperationException) {
                Assumptions.abort<Any?>("thread allocation tracking is unavailable: " + e)
                throw LinkageError(e.message, e)
            }
        }
    }
}
