// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.safere.Pattern
import org.safere.Utf8Input
import java.util.regex.PatternSyntaxException

/** Exercises arbitrary syntax, UTF-16 text, and UTF-8 windows without a JDK regex oracle.  */
class RobustnessFuzzer {
    @FuzzTest(maxDuration = "30s")
    fun robustness(data: FuzzedDataProvider) {
        fuzzerTestOneInput(data)
    }

    companion object {
        /** Shared entry point for local Jazzer and OSS-Fuzz.  */
        fun fuzzerTestOneInput(data: FuzzedDataProvider) {
            val regex: String?
            if (data.consumeBoolean()) {
                val depth = data.consumeInt(0, 512)
                regex = "(?:".repeat(depth) + "a" + ")".repeat(depth)
            } else {
                regex = data.consumeString(256)
            }
            val pattern: Pattern?
            try {
                pattern = Pattern.compile(regex, FuzzSupport.consumeFlags(data))
            } catch (expected: PatternSyntaxException) {
                return
            }
            val input = data.consumeString(512)
            val matcher = pattern.matcher(input)
            matcher.matches()
            matcher.reset().lookingAt()
            matcher.reset()
            var previousEnd = -1
            var attempts = 0
            while (matcher.find()) {
                if (matcher.start() < 0 || matcher.end() < matcher.start() || matcher.end() > input.size || matcher.end() < previousEnd || ++attempts > input.size + 1) {
                    throw AssertionError("invalid String match bounds or find progress")
                }
                previousEnd = matcher.end()
            }
            val bytes = data.consumeBytes(1024)
            val offset = data.consumeInt(0, bytes.size)
            val length = data.consumeInt(0, bytes.size - offset)
            Utf8RobustnessChecks.walk(pattern.matcher(Utf8Input.trusted(bytes, offset, length)), length)
            val valid = Utf8RobustnessChecks.isValidUtf8(bytes, offset, length)
            val validated: Utf8Input?
            try {
                validated = Utf8Input.validated(bytes, offset, length)
            } catch (exception: IllegalArgumentException) {
                if (valid) {
                    throw AssertionError("valid UTF-8 rejected", exception)
                }
                return
            }
            if (!valid) {
                throw AssertionError("malformed UTF-8 accepted")
            }
            // Keep matching outside the expected validation-exception handler.
            Utf8RobustnessChecks.walk(pattern.matcher(validated), length)
            pattern.find(validated)
        }
    }
}
