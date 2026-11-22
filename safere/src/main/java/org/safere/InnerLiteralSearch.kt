// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.math.max

/** A required suffix and a guarded gap can exclude a prefix of candidate match starts.  */
internal class InnerLiteralSearch private constructor(private val plan: InnerLiteralPlan) {
  /**
   * Returns a safe lower bound for match starts, or `-1` when the suffix is absent.
   *
   *
   * No match can end before the first suffix occurrence. If the character before that suffix
   * cannot belong to the gap, any start whose fixed prefix ends before that character is also
   * impossible for every later suffix occurrence. Retreating by the prefix's code-point length
   * retains every possible start. The normal engine checks the remaining candidates, including
   * bounds, assertions, greediness, and captures.
   *
   *
   * This performs one literal scan and one bounded backward probe per search, without retries. A
   * successful match consumes the first suffix occurrence or a later one, so successive finds do
   * not repeatedly scan the same unconsumed suffix range.
   */
  fun searchStart(scanner: InputScanner, from: Int): Int {
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.recordInnerLiteralProbe()
    }
    val inner: Int
    if (scanner is Utf8InputScanner) {
      inner = scanner.indexOf(plan.utf8, plan.failure, plan.shifts, from)
    } else if (scanner is StringRegionInputScanner) {
      inner = scanner.indexOf(plan.literal, from, scanner.length)
    } else {
      val text = (scanner as StringInputScanner).text()!!
      inner =
        StringLiteralSearch.indexOf(
          text, plan.literal, plan.anchorOffset, plan.anchor, from
        )
    }
    if (inner < 0) {
      return -1
    }
    var position = inner
    var count = 0
    while (count < MAX_BACKWARD_CODE_POINTS && position > from) {
      val decoded = scanner.decodeBackward(position)
      val previous: Int = InputScanner.position(decoded)
      if (previous < from) {
        break
      }
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record()
      }
      if (!plan.gap.contains(InputScanner.codePoint(decoded))) {
        return max(from, scanner.retreatByCodePoints(position, plan.prefixCodePoints))
      }
      position = previous
      count++
    }
    return from
  }

  companion object {
    // A failed probe must add bounded work even when the entire input belongs to the gap class.
    private const val MAX_BACKWARD_CODE_POINTS = 256

    // Pay for narrowing only after the native matcher has consumed this much failed input.
    // Prefix-accelerator skips do not contribute to the budget.
    const val MIN_FAILED_INPUT_UNITS: Int = 64

    fun compile(ast: Regexp?, rejectPrefilter: RejectPrefilter?): InnerLiteralSearch? {
      val compiled = InnerLiteralPlan.compile(ast) ?: return null
      val plan =
        if (rejectPrefilter is RejectPrefilter.Literal &&
          rejectPrefilter.literal == compiled.literal
        ) {
          InnerLiteralPlan.withSharedLiteralTables(
            compiled,
            rejectPrefilter.literal!!,
            rejectPrefilter.utf8!!,
            rejectPrefilter.failure!!,
            rejectPrefilter.shifts,
            rejectPrefilter.anchorOffset,
            rejectPrefilter.anchor,
          )
        } else {
          compiled
        }
      return InnerLiteralSearch(plan)
    }
  }
}
