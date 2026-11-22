// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/** KMP expect for [PatternSyntaxExceptions]. */
internal expect object PatternSyntaxExceptions {
  fun create(
    description: String?,
    pattern: String?,
    index: Int,
    cause: Throwable? = null,
  ): RuntimeException

  fun createClass(description: String?, pattern: String?, index: Int): RuntimeException

  fun isClassSyntaxError(error: Throwable?): Boolean
}
