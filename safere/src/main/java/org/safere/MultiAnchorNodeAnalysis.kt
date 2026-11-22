// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.jvm.JvmRecord

@JvmRecord
internal data class PrefixResult(val prefix: String?, val foldCase: Boolean) {
  companion object {
    val NO_PREFIX: PrefixResult = PrefixResult(null, false)
  }
}

@JvmRecord
internal data class FixedOffsetLiteralSpan(
  val literal: String?,
  val minOffset: Int,
  val maxOffset: Int,
  val discreteOffsets: IntArray?,
)

@JvmRecord
internal data class StartFacets(
  val prefix: PrefixResult?,
  val charClassPrefix: CharClassScanInfo?,
  val fixedOffsetLiteral: FixedOffsetLiteralSpan?,
  val startAcceleration: StartAcceleration?,
  val anchoredPrefix: PrefixResult,
  val anchoredCharClassPrefix: CharClassScanInfo?,
  val literalAlternation: Array<String?>?,
) {
  companion object {
    val EMPTY: StartFacets = StartFacets(
      PrefixResult.NO_PREFIX, null, null, null, PrefixResult.NO_PREFIX, null, null
    )
  }
}

@JvmRecord
internal data class RejectFacets(
  val bestRequiredLiteral: String?,
  val bestRequiredLiteralScore: Int,
  val bestRequiredClass: CharClass?,
  val disjointRequiredLiterals: Array<String?>?,
  val endAnchoredSuffix: SuffixInfo?,
  val endAnchoredCharClass: EndAnchoredCharClassInfo?,
) {
  companion object {
    val EMPTY: RejectFacets = RejectFacets(null, 0, null, null, null, null)
  }
}

@JvmRecord
internal data class ClassAnchor(val scanInfo: CharClassScanInfo?, val offset: Int)

@JvmRecord
internal data class NodeAnalysis(
  val width: AsciiWidthRange,
  val start: StartFacets,
  val reject: RejectFacets,
  val atomicRequiredClass: CharClass?,
  val classAnchor: ClassAnchor?,
) {
  constructor(
    width: AsciiWidthRange,
    start: StartFacets,
    reject: RejectFacets,
    atomicRequiredClass: CharClass?,
  ) : this(width, start, reject, atomicRequiredClass, null)

  companion object {
    val EMPTY: NodeAnalysis = NodeAnalysis(
      AsciiWidthRange.ZERO,
      StartFacets.EMPTY,
      RejectFacets.EMPTY,
      null,
    )

    // Cached widths must remain conservative, just as in the standalone width walker.
    val UNKNOWN: NodeAnalysis = NodeAnalysis(
      AsciiWidthRange.INVALID,
      StartFacets.EMPTY,
      RejectFacets.EMPTY,
      null,
    )
  }
}
