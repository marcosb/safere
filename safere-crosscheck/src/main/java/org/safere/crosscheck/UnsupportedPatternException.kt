// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.crosscheck

import java.util.regex.PatternSyntaxException

/**
 * Thrown when SafeRE rejects a pattern that `java.util.regex` accepts. This is expected for
 * features that SafeRE intentionally does not support (backreferences, lookahead, lookbehind,
 * possessive quantifiers) because they violate linear-time guarantees.
 * 
 * 
 * This exception extends [PatternSyntaxException] so callers that catch `PatternSyntaxException` will handle it naturally.
 */
class UnsupportedPatternException
/**
 * Creates a new unsupported pattern exception.
 * 
 * @param description description of the unsupported feature
 * @param pattern the pattern that was rejected
 * @param index the index in the pattern where the error occurred
 */
    (description: String?, pattern: String?, index: Int) : PatternSyntaxException(description, pattern, index) {
    companion object {
        private const val serialVersionUID = 1L

        /**
         * Creates a new unsupported pattern exception from a SafeRE [PatternSyntaxException].
         * 
         * @param cause the original exception thrown by SafeRE
         * @return a new `UnsupportedPatternException`
         */
        fun fromCause(cause: PatternSyntaxException): UnsupportedPatternException {
            val ex =
                UnsupportedPatternException(
                    "SafeRE does not support this pattern (linear-time constraint): "
                            + cause.getDescription(),
                    cause.getPattern(),
                    cause.getIndex()
                )
            ex.initCause(cause)
            return ex
        }
    }
}
