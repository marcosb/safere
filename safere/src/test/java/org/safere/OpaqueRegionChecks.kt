// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions

/** Shared substring oracle for opaque matching, captures, and find sequences.  */
internal object OpaqueRegionChecks {
    fun assertMatchesSubstring(regex: String?, input: String, start: Int, end: Int) {
        val pattern = Pattern.compile(regex)
        for (operation in 0..2) {
            Assertions.assertThat<String?>(trace(pattern.matcher(input).region(start, end), operation, start))
                .`as`("/%s/ region [%s,%s] operation=%s", regex, start, end, operation)
                .isEqualTo(trace(pattern.matcher(input.substring(start, end)), operation, 0))
        }
    }

    fun trace(matcher: Matcher, operation: Int, offset: Int): MutableList<String?> {
        val result: MutableList<String?> = ArrayList<String?>()
        val maximumSteps = matcher.regionEnd() - matcher.regionStart() + 2
        for (step in 0..<maximumSteps) {
            val matched =
                when (operation) {
                    0 -> matcher.matches()
                    1 -> matcher.lookingAt()
                    else -> matcher.find()
                }
            result.add(matched.toString())
            if (matched) {
                for (group in 0..matcher.groupCount()) {
                    val start = matcher.start(group)
                    val end = matcher.end(group)
                    result.add((if (start < 0) start else start - offset).toString() + ":" + (if (end < 0) end else end - offset))
                    result.add(matcher.group(group))
                }
            }
            if (operation != 2 || !matched) {
                return result
            }
        }
        throw AssertionError("Opaque find sequence failed to make progress: " + matcher.pattern())
    }
}
