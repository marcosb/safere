// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.regex.PatternSyntaxException

/** Gradle JVM actual mirroring the Maven [PatternSyntaxExceptions] implementation. */
internal actual object PatternSyntaxExceptions {
  actual fun create(
    description: String?,
    pattern: String?,
    index: Int,
    cause: Throwable?,
  ): RuntimeException {
    val error = PatternSyntaxException(description, pattern, index)
    if (cause != null) {
      error.initCause(cause)
    }
    return error
  }

  actual fun createClass(description: String?, pattern: String?, index: Int): RuntimeException {
    return ClassSyntaxException(description, pattern, index)
  }

  actual fun isClassSyntaxError(error: Throwable?): Boolean {
    return error is ClassSyntaxException
  }

  private class ClassSyntaxException(description: String?, pattern: String?, index: Int) :
    PatternSyntaxException(description, pattern, index)
}
