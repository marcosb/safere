// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import org.safere.benchmark.RegexEngineVariant.CompiledRegex
import org.safere.benchmark.RegexEngineVariant.MatchCursor
import org.safere.benchmark.RegexEngineVariant.RegexInput

/** Prepared native cursors and capture-consumption schedule for one cutoff trial. */
internal class CaptureCutoffTask(
    variant: RegexEngineVariant,
    regex: String?,
    inputs: List<RegexInput?>,
    operation: String?,
    documents: Int,
    hitEvery: Int,
    readEvery: Int,
) : AutoCloseable {
  private val pattern: CompiledRegex
  private val cursors: Array<MatchCursor>
  private val operation: String?
  private val documents: Int
  private val hitEvery: Int
  private val readEvery: Int

  init {
    require(documents >= 1 && hitEvery >= 0 && readEvery >= 0 && inputs.size == 2) {
      "Invalid cutoff protocol"
    }
    this.operation = operation
    this.documents = documents
    this.hitEvery = hitEvery
    this.readEvery = readEvery
    pattern = variant.compile(regex)
    cursors = Array(inputs.size) { pattern.matcher(inputs[it]) }
    // Steady state after actual text consumption, including Boolean/rejection controls.
    check(search(cursors[0]) && cursors[0].group(1) != null) { "Declared positive primer failed" }
    cursors[1].reset()
    check(!search(cursors[1])) { "Declared rejection input matched" }
  }

  private fun search(cursor: MatchCursor): Boolean {
    return when (operation) {
      "matches" -> cursor.matches()
      "lookingAt" -> cursor.lookingAt()
      "find" -> cursor.find()
      else -> throw IllegalArgumentException(operation)
    }
  }

  fun run(): Long {
    var checksum: Long = 0
    var hits = 0
    for (document in 0..<documents) {
      val index = if (hitEvery > 0 && document % hitEvery == 0) 0 else 1
      val cursor = cursors[index]
      cursor.reset()
      if (search(cursor)) {
        checksum++
        if (readEvery > 0 && hits++ % readEvery == 0) {
          checksum = checksum * 31 + cursor.group(1).hashCode()
        }
      }
    }
    return checksum
  }

  override fun close() {
    pattern.close()
  }
}
