// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import org.safere.Pattern
import java.util.List
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.regex.MatchResult

/** Exact comparison of valid patterns: no compile skips or semantic waivers.  */
internal object StrictDifferential {
    fun check(regex: String, input: String): Boolean {
        // Always execute SafeRE first, including all observations, even if the JDK is unavailable.
        val actual = Pattern.compile(regex).matcher(input)
        val safe =
            trace(
                actual,
                BooleanSupplier { actual.matches() },
                BooleanSupplier { actual.lookingAt() },
                BooleanSupplier { actual.find() },
                Runnable { actual.reset() },
                input.length
            )
        val jdk: MutableList<Step?>?
        val observed: MutableList<Step?> = ArrayList<Step?>()
        try {
            val expected = java.util.regex.Pattern.compile(regex).matcher(StrictOracle.input(input))
            jdk =
                trace(
                    expected,
                    BooleanSupplier { expected.matches() },
                    BooleanSupplier { expected.lookingAt() },
                    BooleanSupplier { expected.find() },
                    Runnable { expected.reset() },
                    input.length,
                    Consumer { step: Step? ->
                        observed.add(step)
                        val length = observed.size
                        if (length > safe.size || safe.get(length - 1) != step) {
                            assertSame(regex, input, safe, observed)
                        }
                    })
        } catch (unavailable: Unavailable) {
            StrictOracle.unavailable()
            return false
        } catch (unavailable: StackOverflowError) {
            StrictOracle.unavailable()
            return false
        }
        assertSame(regex, input, safe, jdk)
        StrictOracle.completed()
        return true
    }

    fun trace(
        matcher: MatchResult,
        matches: BooleanSupplier,
        lookingAt: BooleanSupplier,
        find: BooleanSupplier,
        reset: Runnable,
        length: Int
    ): MutableList<Step?> {
        return trace(matcher, matches, lookingAt, find, reset, length, Consumer { ignored: Step? -> })
    }

    private fun trace(
        matcher: MatchResult,
        matches: BooleanSupplier,
        lookingAt: BooleanSupplier,
        find: BooleanSupplier,
        reset: Runnable,
        length: Int,
        observer: Consumer<Step?>
    ): MutableList<Step?> {
        val result: MutableList<Step?> = ArrayList<Step?>()
        val append =
            Consumer { value: Step? ->
                result.add(value)
                observer.accept(value)
            }
        append.accept(step("matches", matches.getAsBoolean(), matcher))
        reset.run()
        append.accept(step("lookingAt", lookingAt.getAsBoolean(), matcher))
        reset.run()
        for (attempt in 0..length + 1) {
            val found = find.getAsBoolean()
            append.accept(step("find", found, matcher))
            if (!found) {
                return List.copyOf<Step?>(result)
            }
        }
        throw AssertionError("find did not make bounded progress")
    }

    private fun step(operation: String?, matched: Boolean, matcher: MatchResult): Step {
        val groups: MutableList<Group?> = ArrayList<Group?>()
        if (matched) {
            for (group in 0..matcher.groupCount()) {
                groups.add(Group(matcher.start(group), matcher.end(group), matcher.group(group)))
            }
        }
        return Step(operation, matched, List.copyOf<Group?>(groups))
    }

    fun assertSame(regex: String?, input: String?, safe: MutableList<Step?>, jdk: MutableList<Step?>) {
        if (safe != jdk) {
            throw FuzzFinding.failure(
                regex,
                0,
                input,
                "strict differential",
                "default bounds",
                "matches;reset;lookingAt;reset;find until exhausted",
                safe.toString(),
                jdk.toString()
            )
        }
    }

    @JvmRecord
    internal data class Step(val operation: String?, val matched: Boolean, val groups: MutableList<Group?>?)

    @JvmRecord
    internal data class Group(val start: Int, val end: Int, val text: String?)
}
