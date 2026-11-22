// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.safere.MultiAnchorDescriptor.RejectPlan.RequiredLiteral
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmRecord

/**
 * Immutable descriptor carrying the prefilter and start-acceleration plans extracted from a regular
 * expression AST: a [StartPlan] describing where a match may begin and a [RejectPlan]
 * describing what the input must contain for any match to be possible.
 */
internal class MultiAnchorDescriptor @JvmOverloads constructor(
  startAnchored: Boolean,
  startPlan: StartPlan?,
  rejectPlan: RejectPlan?,
  anchoredPrefix: String? = null,
  anchoredCharClassPrefix: CharClassScanInfo? = null,
) {
  private val startAnchored: Boolean
  private val startPlan: StartPlan
  private val rejectPlan: RejectPlan
  private val anchoredPrefix: String?
  private val anchoredCharClassPrefix: CharClassScanInfo?

  init {
    this.startAnchored = startAnchored
    this.startPlan = requireNotNull(startPlan) { "startPlan" }
    this.rejectPlan = requireNotNull(rejectPlan) { "rejectPlan" }
    this.anchoredPrefix = anchoredPrefix
    this.anchoredCharClassPrefix = anchoredCharClassPrefix
  }

  fun startPlan(): StartPlan = startPlan

  fun rejectPlan(): RejectPlan = rejectPlan

  fun anchoredPrefix(): String? = anchoredPrefix

  fun anchoredCharClassPrefix(): CharClassScanInfo? = anchoredCharClassPrefix

  fun prefix(): String? {
    if (startAnchored) {
      return null
    }
    return if (startPlan is StartPlan.Literal) startPlan.prefix else null
  }

  fun prefixFoldCase(): Boolean {
    if (startAnchored) {
      return false
    }
    return startPlan is StartPlan.Literal && startPlan.foldCase
  }

  fun charClassPrefix(): CharClassScanInfo? {
    if (startAnchored) {
      return null
    }
    return if (startPlan is StartPlan.CharClass) startPlan.scanInfo else null
  }

  /**
   * Returns whether `find()` should run the start accelerator before `findRejectPrefilter`.
   *
   *
   * Requires an exact-case literal-driven [StartPlan] ([StartPlan.Literal] with
   * `!foldCase` or [StartPlan.FixedOffset]) so slower case-folded or non-exact start
   * kernels never preempt an exact-case [RejectPrefilter], and requires the start literal's
   * anchor rarity to be competitive with any [RejectPlan.RequiredLiteral]. O(1) end-anchored
   * boundary checks still run first when present.
   */
  fun startPrecedesFindReject(): Boolean {
    if (startAnchored || rejectPlan !is RequiredLiteral) {
      return false
    }
    val startLiteral: String? =
      when (startPlan) {
        is StartPlan.Literal -> if (startPlan.foldCase) null else startPlan.prefix
        is StartPlan.FixedOffset -> startPlan.fol!!.literal()
        else -> null
      }
    return startLiteral != null &&
      startLiteral.isNotEmpty() &&
      RarityOracle.startLiteralCompetitiveWithReject(startLiteral, rejectPlan.literal)
  }

  /**
   * Returns whether a failed start search already rejects the input, so that `find()` can
   * skip `findRejectPrefilter`. Holds for a leading expansion over a wide class whose inner
   * plan is an exact literal that contains every literal and some member of every class the reject
   * plan requires: the expansion's literal search then reports no candidate exactly when the reject
   * prefilter would reject, and running both scans the same text twice.
   */
  fun startSubsumesFindReject(): Boolean {
    if (startAnchored ||
      (startPlan !is StartPlan.LeadingExpansion) ||
      !LeadingClassWidth.isWideLeadingClass(startPlan.leadingClass!!) ||
      (startPlan.innerPlan !is StartPlan.Literal) ||
      startPlan.innerPlan.foldCase
    ) {
      return false
    }
    val lit = startPlan.innerPlan
    return when (val plan = rejectPlan) {
      is RejectPlan.Composite -> {
        for (part in plan.plans!!) {
          if (!impliedByLiteral(part, lit.prefix!!)) {
            return false
          }
        }
        true
      }
      else -> impliedByLiteral(plan, lit.prefix!!)
    }
  }

  internal sealed interface StartPlan {
    class None private constructor() : StartPlan {
      companion object {
        val INSTANCE: None = None()
      }
    }

    @JvmRecord
    data class Literal(val prefix: String?, val foldCase: Boolean) : StartPlan {
      init {
        requireNotNull(prefix) { "prefix" }
      }
    }

    @JvmRecord
    data class CharClass(val scanInfo: CharClassScanInfo?) : StartPlan {
      init {
        requireNotNull(scanInfo) { "scanInfo" }
      }
    }

    @JvmRecord
    data class FixedOffset(val fol: FixedOffsetLiteral?, val leadingClass: CharClassScanInfo?) : StartPlan {
      init {
        requireNotNull(fol) { "fol" }
      }
    }

    /** A required ASCII class following an exact-width ASCII prefix.  */
    @JvmRecord
    data class FixedOffsetClass(
      val scanInfo: CharClassScanInfo?,
      val offset: Int,
      val leadingClass: CharClassScanInfo?,
    ) : StartPlan {
      init {
        requireNotNull(scanInfo) { "scanInfo" }
        requireNotNull(leadingClass) { "leadingClass" }
      }
    }

    @JvmRecord
    data class MultiLiteral(val literals: Array<String?>?, val fallbackClass: CharClassScanInfo?) : StartPlan {
      init {
        requireNotNull(literals) { "literals" }
      }
    }

    @JvmRecord
    data class LeadingExpansion(
      val leadingClass: CharClassScanInfo?,
      val minRepetition: Int,
      val maxRepetition: Int,
      val hasLeadingAssertions: Boolean,
      val innerPlan: StartPlan?,
    ) : StartPlan {
      init {
        requireNotNull(leadingClass) { "leadingClass" }
        requireNotNull(innerPlan) { "innerPlan" }
      }
    }

    @JvmRecord
    data class LineAnchor(val acceleration: StartAcceleration?) : StartPlan {
      init {
        requireNotNull(acceleration) { "acceleration" }
      }
    }
  }

  internal interface RejectPlan {
    class None private constructor() : RejectPlan {
      companion object {
        val INSTANCE: None = None()
      }
    }

    @JvmRecord
    data class RequiredLiteral(val literal: String?) : RejectPlan {
      init {
        requireNotNull(literal) { "literal" }
      }
    }

    @JvmRecord
    data class RequiredCharClass(val scanInfo: CharClassScanInfo?) : RejectPlan {
      init {
        requireNotNull(scanInfo) { "scanInfo" }
      }
    }

    @JvmRecord
    data class DisjointLiterals(val literals: Array<String?>?) : RejectPlan {
      init {
        requireNotNull(literals) { "literals" }
      }
    }

    @JvmRecord
    data class EndAnchoredSuffix(val suffix: SuffixInfo?) : RejectPlan {
      init {
        requireNotNull(suffix) { "suffix" }
      }
    }

    @JvmRecord
    data class EndAnchoredCharClass(val charClass: EndAnchoredCharClassInfo?) : RejectPlan {
      init {
        requireNotNull(charClass) { "charClass" }
      }
    }

    @JvmRecord
    data class Composite(val plans: Array<RejectPlan>?) : RejectPlan {
      init {
        requireNotNull(plans) { "plans" }
      }
    }
  }

  companion object {
    val NONE: MultiAnchorDescriptor =
      MultiAnchorDescriptor(false, StartPlan.None.INSTANCE, RejectPlan.None.INSTANCE)

    private fun impliedByLiteral(plan: RejectPlan, literal: String): Boolean {
      return when (plan) {
        is RequiredLiteral -> literal.contains(plan.literal!!)
        is RejectPlan.RequiredCharClass -> {
          var i = 0
          while (i < literal.length) {
            val cp = CodePoints.codePointAt(literal, i)
            if (plan.scanInfo!!.contains(cp)) {
              return true
            }
            i += CodePoints.charCount(cp)
          }
          false
        }
        else -> false
      }
    }
  }
}
