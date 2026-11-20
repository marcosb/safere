// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/** Holds precomputed data for the character-class-match fast path.  */
// TODO(#98): Replace int[] with Guava ImmutableIntArray to get proper value semantics.
internal data class CharClassMatchInfo(
  val ranges: IntArray?,
  val bitmap0: Long,
  val bitmap1: Long,
  val allowEmpty: Boolean,
)
