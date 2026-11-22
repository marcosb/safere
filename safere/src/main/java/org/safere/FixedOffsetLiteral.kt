// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/** Exact UTF-8 literal constrained to one or more relative start offsets.  */
internal class FixedOffsetLiteral(
  literal: String,
  minOffset: Int,
  maxOffset: Int,
  discreteOffsets: IntArray?,
) {
  private val literal: String?
  private val minOffset: Int
  private val maxOffset: Int
  private val discreteOffsets: IntArray?
  private val utf8: ByteArray
  private val failure: IntArray
  private val shifts: IntArray?

  constructor(literal: String, offset: Int) : this(literal, offset, offset, intArrayOf(offset))

  init {
    this.literal = literal
    this.minOffset = minOffset
    this.maxOffset = maxOffset
    this.discreteOffsets = discreteOffsets
    this.utf8 = literal.encodeToByteArray()
    this.failure = ByteLiteralScan.literalFailure(utf8)
    this.shifts = ByteLiteralScan.literalShifts(utf8)
  }

  fun literal(): String? = literal

  fun offset(): Int = minOffset

  fun minOffset(): Int = minOffset

  fun maxOffset(): Int = maxOffset

  fun discreteOffsets(): IntArray? = discreteOffsets

  val isExactOffset: Boolean
    get() = minOffset == maxOffset

  fun utf8(): ByteArray = utf8

  fun failure(): IntArray = failure

  fun shifts(): IntArray? = shifts
}
