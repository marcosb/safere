// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/** Searching through a required inner literal preserves match priority and captures.  */
@DisabledForCrosscheck("compares the JDK and forced SafeRE engine paths directly")
internal class InnerLiteralSearchTest {
    @Test
    fun literalGapSearchPreservesFindSequencesAndRegions() {
        for (regex in mutableListOf<String>(
            "record:[^;\\r\\n]*val=200",
            "(record:)([^;\\r\\n]*?)(val=200)",
            "record:[^;]{0,64}val=200",
            "record:[a-z_]*val=200",
            "record:.*val=200",
            "record:.*?val=200",
            "aa[^;]*aba",
            "aa[^;]*?aba",
            "a;[^;]*;b",
            "clé:[^;]*fin",
            "😀[^;]*終",
            "record:[^😀]*val=200",
            "\\brecord:[^;]*val=200",
            "(?i:record:[^;]*val=200)",
            "^record:[^;]*val=200",
            "(?m:^record:[^;\\r\\n]*val=200$)",
            "record:[^;]*val=200|earlier"
        )) {
            val reference = java.util.regex.Pattern.compile(regex)
            for (options in java.util.List.of<EnginePathOptions?>(
                EnginePathOptions.allEnabled(),
                EnginePathOptions.builder().startAcceleration(false).build()
            )) {
                val pattern = org.safere.Pattern.compile(regex, 0, options)
                for (body in mutableListOf<String>(
                    "record:x;val=200;record:target_val=200",
                    "record:val=200_record:later_val=200",
                    "record:val=200;record:later_val=200",
                    "record:val=200;record:x;val=200",
                    "aaabaaba;aaaba",
                    "clé:x;fin;clé:é😀fin",
                    "😀x;終;😀é終",
                    "a;x;b;a;later;b",
                    "record:x😀val=200😀record:target_val=200",
                    "RECORD:abcVAL=200;record:val=200",
                    "earlier;record:target_val=200",
                    "\nrecord:target_val=200\n",
                    "record:no_match;val=200"
                )) {
                    val input = "noise;".repeat(128) + body
                    assertFindSequence(pattern, reference, input, 0, input.length)
                    assertFindSequence(pattern, reference, "«" + input + "»", 1, input.length + 1)
                }
            }
        }
    }

    @Test
    fun bulkOperationsAndBooleanSearchPreserveResults() {
        for (regex in mutableListOf<String>(
            "record:[^;\\r\\n]*val=200",
            "(record:)([^;]*?)(val=200)",
            "aa[^;]*aba",
            "clé:[^;]*fin",
            "record:[^😀]*val=200"
        )) {
            val pattern = org.safere.Pattern.compile(regex)
            val expected = java.util.regex.Pattern.compile(regex)
            for (input in java.util.List.of<String?>(
                "record:x;".repeat(512) + "record:val=200;record:later_val=200",
                "record:" + "x".repeat(1024) + "val=200",
                "clé:x;".repeat(512) + "clé:é😀fin;clé:fin",
                "noise;".repeat(512) + "aaabaaba;aaaba",
                "record:bad;val=200;record:good_val=200",
                "record:x😀val=200😀record:target_val=200"
            )) {
                Assertions.assertThat(pattern.find(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))))
                    .isEqualTo(expected.matcher(input).find())
                Assertions.assertThat(pattern.asPredicate().test(input)).isEqualTo(expected.matcher(input).find())
                Assertions.assertThat(pattern.matcher(input).replaceFirst("X"))
                    .isEqualTo(expected.matcher(input).replaceFirst("X"))
                Assertions.assertThat(pattern.matcher(input).replaceAll("X"))
                    .isEqualTo(expected.matcher(input).replaceAll("X"))
                Assertions.assertThat<String?>(pattern.split(input, -1)).containsExactly(*expected.split(input, -1))
            }
        }
    }

    companion object {
        private fun assertFindSequence(
            pattern: org.safere.Pattern, reference: java.util.regex.Pattern, input: String, start: Int, end: Int
        ) {
            val expected = reference.matcher(input).region(start, end)
            val actual = pattern.matcher(input).region(start, end)
            val bytes =
                pattern
                    .matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
                    .region(byteOffset(input, start), byteOffset(input, end))
            while (true) {
                val found = expected.find()
                Assertions.assertThat(actual.find()).`as`("String /%s/ in %s", pattern, input).isEqualTo(found)
                Assertions.assertThat(bytes.find()).`as`("UTF-8 /%s/ in %s", pattern, input).isEqualTo(found)
                if (!found) {
                    return
                }
                for (group in 0..expected.groupCount()) {
                    Assertions.assertThat(actual.start(group)).isEqualTo(expected.start(group))
                    Assertions.assertThat(actual.end(group)).isEqualTo(expected.end(group))
                    Assertions.assertThat(actual.group(group)).isEqualTo(expected.group(group))
                    Assertions.assertThat(bytes.start(group)).isEqualTo(byteOffset(input, expected.start(group)))
                    Assertions.assertThat(bytes.end(group)).isEqualTo(byteOffset(input, expected.end(group)))
                }
            }
        }

        private fun byteOffset(input: String, offset: Int): Int {
            return if (offset < 0) -1 else input.substring(0, offset).toByteArray(StandardCharsets.UTF_8).size
        }
    }
}
