// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.assertj.core.api.Assertions
import java.util.regex.Pattern

/** Exercises deferred NFA captures with independently varied region and match boundaries.  */
class DeferredRegionCaptureFuzzer {
    @FuzzTest(maxDuration = "30s")
    fun deferredRegionCaptures(data: FuzzedDataProvider) {
        val atom: String = pick(data, "a", "b", "\uD801\uDC00", "[ab]")
        val quantifier: String = pick(data, "+?", "+", "*?", "*", "{1,3}?", "{1,3}")
        val outer: String = pick(data, "?", "??", "", "{0,1}")
        val regex: String =
            (pick(data, "", "^", "\\b")
                    + "("
                    + atom
                    + quantifier
                    + ")"
                    + outer
                    + pick(data, "", "$", "\\z", "\\b"))
        val prefix: String = pick(data, "x", "", "\uD801\uDC00", "\n")
        val suffix: String = pick(data, "", "x", "\uD801\uDC00", "\n")
        val body = pick(data, "a", "b", "\uD801\uDC00", "ab").repeat(data.consumeInt(1, 16))
        val anchoring = data.consumeBoolean()
        val transparent = data.consumeBoolean()
        val pattern =
            Pattern.compile(
                regex, 0, EnginePathOptions.builder().onePass(false).bitState(false).build()
            )
        val jdkPattern = Pattern.compile(regex)
        // A fresh matcher exercises the Pattern's NFA pool; resets vary capture participation and size.
        for (fresh in 0..1) {
            val actual = pattern.matcher("")
            val expected = jdkPattern.matcher("")
            for (content in arrayOf<String>(body, "", "!", body + "!", body)) {
                val input = prefix + content + suffix
                val start = prefix.length
                val end = start + content.size
                for (operation in 0..2) {
                    actual
                        .reset(input)
                        .region(start, end)
                        .useAnchoringBounds(anchoring)
                        .useTransparentBounds(transparent)
                    expected
                        .reset(input)
                        .region(start, end)
                        .useAnchoringBounds(anchoring)
                        .useTransparentBounds(transparent)
                    val context =
                        (regex
                                + " input="
                                + input
                                + " region="
                                + start
                                + ":"
                                + end
                                + " anchoring="
                                + anchoring
                                + " transparent="
                                + transparent
                                + " operation="
                                + operation)
                    var matched: Boolean
                    do {
                        matched =
                            when (operation) {
                                0 -> actual.matches()
                                1 -> actual.lookingAt()
                                else -> actual.find()
                            }
                        val jdkMatched =
                            when (operation) {
                                0 -> expected.matches()
                                1 -> expected.lookingAt()
                                else -> expected.find()
                            }
                        Assertions.assertThat(matched).`as`(context).isEqualTo(jdkMatched)
                        if (matched) {
                            for (group in 0..expected.groupCount()) {
                                Assertions.assertThat(actual.start(group)).`as`(context)
                                    .isEqualTo(expected.start(group))
                                Assertions.assertThat(actual.end(group)).`as`(context).isEqualTo(expected.end(group))
                                Assertions.assertThat(actual.group(group)).`as`(context)
                                    .isEqualTo(expected.group(group))
                            }
                        }
                    } while (operation == 2 && matched)
                }
            }
        }
    }

    companion object {
        private fun pick(data: FuzzedDataProvider, vararg values: String): String {
            return values[data.consumeInt(0, values.length - 1)]
        }
    }
}
