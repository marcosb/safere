// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.regex.PatternSyntaxException

/**
 * Factory for parse-time syntax errors.
 *
 * The Maven JVM build compiles this implementation. The KMP sidecar excludes it and supplies
 * expect/actual overlays so native targets compile without `java.util.regex.PatternSyntaxException`.
 */
internal object PatternSyntaxExceptions {
  fun create(
    description: String?,
    pattern: String?,
    index: Int,
    cause: Throwable? = null,
  ): PatternSyntaxException {
    val error = PatternSyntaxException(description, pattern, index)
    if (cause != null) {
      error.initCause(cause)
    }
    return error
  }

  fun createClass(description: String?, pattern: String?, index: Int): PatternSyntaxException {
    return ClassSyntaxException(description, pattern, index)
  }

  fun isClassSyntaxError(error: Throwable?): Boolean {
    return error is ClassSyntaxException
  }

  fun isSyntaxError(error: Throwable?): Boolean {
    return error is PatternSyntaxException
  }

  private class ClassSyntaxException(description: String?, pattern: String?, index: Int) :
    PatternSyntaxException(description, pattern, index) {
    companion object {
      private const val serialVersionUID = 1L
    }
  }
}
