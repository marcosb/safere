// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Pins the whole-input replacement property across its generated behavioral families.  */
internal class Utf8ReplacementPropertyTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun invalidNumberedReferencesHaveConsistentValidation(trusted: Boolean) {
        for (groups in intArrayOf(0, 1, 2, 8)) {
            for (invalidGroup in intArrayOf(groups + 1, 9)) {
                for (prefix in mutableListOf<String?>("", "[", "é😀$0-")) {
                    for (suffix in mutableListOf<String?>("", "]", "0")) {
                        for (hasMatch in booleanArrayOf(false, true)) {
                            RepresentationChecks.assertUtf8NumberedReferenceValidation(
                                groups, invalidGroup, prefix + "$" + invalidGroup + suffix, hasMatch, trusted
                            )
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun wholeInputReplacementsAgreeWithAppendLoops(trusted: Boolean) {
        val cases = arrayOf<Array<String?>?>(
            arrayOf<String?>("", ""),
            arrayOf<String?>("", "é😀"),
            arrayOf<String?>("(?<word>[aé😀]+)([0-9]+)?", "aé9 😀 b"),
            arrayOf<String?>("(a)?(é|😀)", "aé 😀"),
            arrayOf<String?>("^é", "é😀é"),
            arrayOf<String?>("^é", "aé😀é"),
            arrayOf<String?>("é$", "é😀é"),
            arrayOf<String?>("(?m)^é", "é\né"),
            arrayOf<String?>("a*?", "aaé😀"),
            arrayOf<String?>("a*", "aaé😀"),
            arrayOf<String?>("a+", "éaa😀a"),
            arrayOf<String?>("[é😀]*", "aé😀éa"),
            arrayOf<String?>("[^a]+", "aé😀a"),
            arrayOf<String?>("(?i)é*", "Ééa"),
            arrayOf<String?>("[aé😀]{0,}", "aé😀!"),
            arrayOf<String?>("\\X", "á👩‍💻")
        )
        for (testCase in cases) {
            for (replacement in mutableListOf<String?>(
                "",
                "X",
                "<$0>",
                "$0$0",
                "$1",
                "\${word}-$2",
                "\\$0\\\\",
                "$",
                "$9",
                "\\"
            )) {
                for (initialState in 0..2) {
                    RepresentationChecks.assertUtf8ReplacementMatchesAppendLoop(
                        testCase!![0], testCase[1], replacement, initialState, trusted
                    )
                }
            }
        }
    }
}
