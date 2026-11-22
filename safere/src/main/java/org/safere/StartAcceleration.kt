// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Every match must start at a multiline `^` position, optionally with the first consumed ASCII
 * character in [asciiStart]. The accelerator only advances the initial search position before
 * handing off to the normal linear engine pipeline.
 */
internal class StartAcceleration(
  val requireLineStart: Boolean,
  val allowLineStart: Boolean,
  val asciiStart: AsciiBitmap?,
)
