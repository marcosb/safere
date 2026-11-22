// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/** Native actual: portable parse errors without `java.util.regex.PatternSyntaxException`. */
internal actual object PatternSyntaxExceptions {
  actual fun create(
    description: String?,
    pattern: String?,
    index: Int,
    cause: Throwable?,
  ): RuntimeException {
    return NativePatternSyntaxException(description, pattern, index, classError = false, cause = cause)
  }

  actual fun createClass(description: String?, pattern: String?, index: Int): RuntimeException {
    return NativePatternSyntaxException(description, pattern, index, classError = true, cause = null)
  }

  actual fun isClassSyntaxError(error: Throwable?): Boolean {
    return error is NativePatternSyntaxException && error.classError
  }

  actual fun isSyntaxError(error: Throwable?): Boolean {
    return error is NativePatternSyntaxException
  }

  private class NativePatternSyntaxException(
    description: String?,
    pattern: String?,
    index: Int,
    val classError: Boolean,
    cause: Throwable?,
  ) : IllegalArgumentException(format(description, pattern, index), cause) {
    companion object {
      private fun format(description: String?, pattern: String?, index: Int): String {
        return "Syntax error near index $index: ${description ?: ""} in ${pattern ?: ""}"
      }
    }
  }
}
