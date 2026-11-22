// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/** End-anchored exact suffix metadata used by reject prefilters.  */
internal data class SuffixInfo(
  val suffix: String?,
  val wasDollar: Boolean,
  val unixLines: Boolean,
  val foldCase: Boolean,
) {
  constructor(suffix: String?, wasDollar: Boolean) : this(suffix, wasDollar, false, false)

  constructor(suffix: String?, wasDollar: Boolean, unixLines: Boolean) :
    this(suffix, wasDollar, unixLines, false)
}
