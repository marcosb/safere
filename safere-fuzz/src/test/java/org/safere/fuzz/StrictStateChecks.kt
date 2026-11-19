// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.safere.Matcher
import org.safere.Pattern
import java.util.*
import java.util.function.Supplier
import java.util.regex.MatchResult

/** Strict comparisons of bounded state sequences; oracle defects affect observations only.  */
internal object StrictStateChecks {
    fun check(
        regex: String, input: String?, start: Int, end: Int, anchoring: Boolean, transparent: Boolean
    ): Boolean {
        var safe = Pattern.compile(regex).matcher(input)
        // Execute the entire SafeRE sequence independently even if the oracle later becomes
        // unavailable.
        safe.find(start)
        safe.region(start, end).useAnchoringBounds(anchoring).useTransparentBounds(transparent)
        safe.matches()
        safe.reset().region(start, end).useAnchoringBounds(anchoring).useTransparentBounds(transparent)
        safe.lookingAt()
        safe.find()
        safe.reset()
        safe.replaceAll("-")
        exhausted(safe)
        safe.reset()
        if (safe.find(start)) {
            val oldStart = safe.start()
            val oldEnd = safe.end()
            val oldGroup = safe.group()
            safe.usePattern(Pattern.compile("a"))
            Assertions.assertThat(safe.hasMatch()).isTrue()
            Assertions.assertThat(safe.start(0)).isEqualTo(oldStart)
            Assertions.assertThat(safe.end(0)).isEqualTo(oldEnd)
            Assertions.assertThat(safe.group()).isEqualTo(oldGroup)
        }
        safe.find()

        safe = Pattern.compile(regex).matcher(input)
        val actual = safe
        try {
            val jdk = java.util.regex.Pattern.compile(regex).matcher(StrictOracle.input(input))
            compare(
                regex,
                input,
                "find(" + start + ")",
                snapshot(actual.find(start), actual),
                StrictStateChecks.snapshot(StrictStateChecks.oracle<Boolean?>(Supplier { jdk.find(start) })!!, jdk)
            )
            actual.region(start, end).useAnchoringBounds(anchoring).useTransparentBounds(transparent)
            jdk.region(start, end).useAnchoringBounds(anchoring).useTransparentBounds(transparent)
            val region =
                "region=" + start + ":" + end + " anchoring=" + anchoring + " transparent=" + transparent
            compare(
                regex,
                input,
                region + ";matches",
                snapshot(actual.matches(), actual),
                StrictStateChecks.snapshot(StrictStateChecks.oracle<Boolean?>(Supplier { jdk.matches() })!!, jdk)
            )
            actual
                .reset()
                .region(start, end)
                .useAnchoringBounds(anchoring)
                .useTransparentBounds(transparent)
            jdk.reset()
                .region(start, end)
                .useAnchoringBounds(anchoring)
                .useTransparentBounds(transparent)
            val looking = actual.lookingAt()
            val expectedLooking = StrictStateChecks.oracle<Boolean?>(Supplier { jdk.lookingAt() })!!
            compare(
                regex,
                input,
                region + ";lookingAt",
                snapshot(looking, actual),
                snapshot(expectedLooking, jdk)
            )
            // Failed initial operations can leave pattern-dependent JDK search state (#818).
            // Restrict the strict oracle domain by resetting both sides, rather than waiving a result.
            if (!looking) {
                actual.reset().region(start, end)
                jdk.reset().region(start, end)
            }
            compare(
                regex,
                input,
                region + (if (looking) ";lookingAt;find" else ";lookingAt;reset;region;find"),
                snapshot(actual.find(), actual),
                StrictStateChecks.snapshot(StrictStateChecks.oracle<Boolean?>(Supplier { jdk.find() })!!, jdk)
            )
            actual.reset()
            jdk.reset()
            compare(
                regex,
                input,
                "reset;replaceAll(-)",
                actual.replaceAll("-"),
                oracle<String?>(Supplier { jdk.replaceAll("-") })
            )
            exhausted(actual)
            if (OracleDefects.exhaustedEmptyMatch(jdk)) {
                OracleDefects.record()
            } else {
                compare(regex, input, "replaceAll;hasMatch", actual.hasMatch(), jdk.hasMatch())
                compare(
                    regex,
                    input,
                    "replaceAll;group",
                    outcome(Supplier { actual.group() }),
                    outcome(Supplier { jdk.group() })
                )
                compare(
                    regex,
                    input,
                    "replaceAll;start",
                    outcome(Supplier { actual.start() }),
                    outcome(Supplier { jdk.start() })
                )
                compare(
                    regex,
                    input,
                    "replaceAll;end",
                    outcome(Supplier { actual.end() }),
                    outcome(Supplier { jdk.end() })
                )
            }
            actual.reset()
            jdk.reset()
            val found = actual.find(start)
            val expected = StrictStateChecks.oracle<Boolean?>(Supplier { jdk.find(start) })!!
            compare(regex, input, "reset;find(start)", snapshot(found, actual), snapshot(expected, jdk))
            if (found) {
                actual.usePattern(Pattern.compile("a"))
                jdk.usePattern(java.util.regex.Pattern.compile("a"))
                compare(regex, input, "usePattern;hasMatch", actual.hasMatch(), jdk.hasMatch())
                compare(regex, input, "usePattern;start", actual.start(), jdk.start())
                compare(regex, input, "usePattern;end", actual.end(), jdk.end())
                if (OracleDefects.clearedGroupsWithLiveMatch(jdk)) {
                    OracleDefects.record()
                } else {
                    compare(regex, input, "usePattern;groups", snapshot(true, actual), snapshot(true, jdk))
                }
            } else {
                actual.reset()
                jdk.reset()
            }
            compare(
                regex,
                input,
                if (found) "usePattern;find" else "failed find(start);reset;find",
                snapshot(actual.find(), actual),
                StrictStateChecks.snapshot(StrictStateChecks.oracle<Boolean?>(Supplier { jdk.find() })!!, jdk)
            )
            StrictOracle.completed()
            return true
        } catch (unavailable: Unavailable) {
            // Only JDK resource failures are unavailable; SafeRE above already ran the complete sequence.
            StrictOracle.unavailable()
            return false
        }
    }

    private fun <T> oracle(action: Supplier<T?>): T? {
        try {
            return action.get()
        } catch (unavailable: StackOverflowError) {
            throw Unavailable()
        }
    }

    fun exhausted(matcher: Matcher) {
        Assertions.assertThat(matcher.hasMatch()).isFalse()
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.group() })
            .isInstanceOf(IllegalStateException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.start() })
            .isInstanceOf(IllegalStateException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { matcher.end() })
            .isInstanceOf(IllegalStateException::class.java)
        Assertions.assertThat(matcher.toMatchResult().hasMatch()).isFalse()
    }

    fun snapshot(found: Boolean, matcher: MatchResult): String {
        val result = StringBuilder(found.toString())
        if (found) {
            result.append(" groups=").append(matcher.groupCount())
            for (g in 0..matcher.groupCount()) {
                result
                    .append('|')
                    .append(matcher.start(g))
                    .append(':')
                    .append(matcher.end(g))
                    .append(':')
                    .append(FuzzSupport.javaStringLiteral(Objects.toString(matcher.group(g))))
            }
        }
        return result.toString()
    }

    private fun outcome(operation: Supplier<*>): Any? {
        try {
            return operation.get()
        } catch (expected: IllegalStateException) {
            return IllegalStateException::class.java
        }
    }

    fun compare(regex: String?, input: String?, operation: String?, safe: Any?, jdk: Any?) {
        if (safe != jdk) {
            throw FuzzFinding.failure(
                regex,
                0,
                input,
                operation,
                operation,
                operation,
                Objects.toString(safe),
                Objects.toString(jdk)
            )
        }
    }
}
