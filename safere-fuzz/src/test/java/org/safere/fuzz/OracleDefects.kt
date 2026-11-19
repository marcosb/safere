// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import java.util.concurrent.atomic.LongAdder
import java.util.regex.Matcher

/** Observed oracle defects only; no regex recognition or SafeRE-result matching.  */
internal object OracleDefects {
    val EXCLUDED_OBSERVATIONS: LongAdder = LongAdder()

    // JDK-8390449: usePattern clears group zero without invalidating the whole match.
    // The exhausted-find variant is documented separately in SafeRE #931/#933.
    // Do not claim that the upstream ticket already covers that variant.
    fun clearedGroupsWithLiveMatch(matcher: Matcher): Boolean {
        if (!matcher.hasMatch() || matcher.start() < 0 || matcher.end() < matcher.start()) {
            return false
        }
        for (group in 0..matcher.groupCount()) {
            if (matcher.start(group) != -1 || matcher.end(group) != -1 || matcher.group(group) != null) {
                return false
            }
        }
        return matcher.group() == null
    }

    fun exhaustedEmptyMatch(matcher: Matcher): Boolean {
        return clearedGroupsWithLiveMatch(matcher)
                && matcher.start() == matcher.regionEnd() && matcher.end() == matcher.regionEnd()
    }

    fun record() {
        EXCLUDED_OBSERVATIONS.increment()
    }
}
