// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.safere.MultiAnchorDescriptor.RejectPlan.RequiredLiteral
import org.safere.Pattern.*
import java.util.*
import java.util.function.IntPredicate

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
    anchoredCharClassPrefix: CharClassScanInfo? = null
) {
    private val startAnchored: Boolean
    private val startPlan: StartPlan
    private val rejectPlan: RejectPlan
    private val anchoredPrefix: String?
    private val anchoredCharClassPrefix: CharClassScanInfo?

    init {
        this.startAnchored = startAnchored
        this.startPlan = Objects.requireNonNull<StartPlan>(startPlan, "startPlan")
        this.rejectPlan = Objects.requireNonNull<RejectPlan>(rejectPlan, "rejectPlan")
        this.anchoredPrefix = anchoredPrefix
        this.anchoredCharClassPrefix = anchoredCharClassPrefix
    }

    fun startPlan(): StartPlan {
        return startPlan
    }

    fun rejectPlan(): RejectPlan {
        return rejectPlan
    }

    fun anchoredPrefix(): String? {
        return anchoredPrefix
    }

    fun anchoredCharClassPrefix(): CharClassScanInfo? {
        return anchoredCharClassPrefix
    }

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
                -> if (lit.foldCase) null else lit.prefix
                -> fo.fol.literal()
                else -> null
            }
        return startLiteral != null && !startLiteral.isEmpty() && RarityOracle.startLiteralCompetitiveWithReject(
            startLiteral,
            rejectPlan.literal
        )
    }

    /**
     * Returns whether a failed start search already rejects the input, so that `find()` can
     * skip `findRejectPrefilter`. Holds for a leading expansion over a wide class whose inner
     * plan is an exact literal that contains every literal and some member of every class the reject
     * plan requires: the expansion's literal search then reports no candidate exactly when the reject
     * prefilter would reject, and running both scans the same text twice.
     */
    fun startSubsumesFindReject(): Boolean {
        if (startAnchored
            || (startPlan !is StartPlan.LeadingExpansion) || !MultiAnchorCompiler.isWideLeadingClass(startPlan.leadingClass) || (startPlan.innerPlan !is StartPlan.Literal) || lit.foldCase
        ) {
            return false
        }
        return when (rejectPlan) {
            -> {
                for (plan in composite.plans) {
                    if (!impliedByLiteral(plan, lit.prefix)) {
                        false
                    }
                }
                true
            }

            else -> impliedByLiteral(rejectPlan, lit.prefix)
        }
    }

    internal interface StartPlan {
        @JvmRecord
        data class None : StartPlan {
            companion object {
                val INSTANCE: None = None()
            }
        }

        @JvmRecord
        data class Literal(prefix: String?, foldCase: Boolean) : StartPlan {
            val prefix: String?
            val foldCase: Boolean

            init {
                this.foldCase = foldCase
                this.prefix = prefix
                Objects.requireNonNull<String?>(prefix, "prefix")
            }
        }

        @JvmRecord
        data class CharClass(scanInfo: CharClassScanInfo?) : StartPlan {
            val scanInfo: CharClassScanInfo?

            init {
                this.scanInfo = scanInfo
                Objects.requireNonNull<CharClassScanInfo?>(scanInfo, "scanInfo")
            }
        }

        @JvmRecord
        data class FixedOffset(fol: Pattern.FixedOffsetLiteral?, leadingClass: CharClassScanInfo?) : StartPlan {
            val fol: Pattern.FixedOffsetLiteral?
            val leadingClass: CharClassScanInfo?

            init {
                this.leadingClass = leadingClass
                this.fol = fol
                Objects.requireNonNull<Pattern.FixedOffsetLiteral?>(fol, "fol")
            }
        }

        /** A required ASCII class following an exact-width ASCII prefix.  */
        @JvmRecord
        data class FixedOffsetClass(scanInfo: CharClassScanInfo?, offset: Int, leadingClass: CharClassScanInfo?) :
            StartPlan {
            val scanInfo: CharClassScanInfo?
            val offset: Int
            val leadingClass: CharClassScanInfo?

            init {
                this.leadingClass = leadingClass
                this.offset = offset
                this.scanInfo = scanInfo
                Objects.requireNonNull<CharClassScanInfo?>(scanInfo, "scanInfo")
                Objects.requireNonNull<CharClassScanInfo?>(leadingClass, "leadingClass")
            }
        }

        @JvmRecord
        data class MultiLiteral(literals: Array<String?>?, fallbackClass: CharClassScanInfo?) : StartPlan {
            val literals: Array<String?>?
            val fallbackClass: CharClassScanInfo?

            init {
                this.fallbackClass = fallbackClass
                this.literals = literals
                Objects.requireNonNull<Array<String?>?>(literals, "literals")
            }
        }

        @JvmRecord
        data class LeadingExpansion(
            leadingClass: CharClassScanInfo?,
            minRepetition: Int,
            maxRepetition: Int,
            hasLeadingAssertions: Boolean,
            innerPlan: StartPlan?
        ) : StartPlan {
            val leadingClass: CharClassScanInfo?
            val minRepetition: Int
            val maxRepetition: Int
            val hasLeadingAssertions: Boolean
            val innerPlan: StartPlan?

            init {
                this.innerPlan = innerPlan
                this.hasLeadingAssertions = hasLeadingAssertions
                this.maxRepetition = maxRepetition
                this.minRepetition = minRepetition
                this.leadingClass = leadingClass
                Objects.requireNonNull<CharClassScanInfo?>(leadingClass, "leadingClass")
                Objects.requireNonNull<StartPlan?>(innerPlan, "innerPlan")
            }
        }

        @JvmRecord
        data class LineAnchor(acceleration: StartAcceleration?) : StartPlan {
            val acceleration: StartAcceleration?

            init {
                this.acceleration = acceleration
                Objects.requireNonNull<StartAcceleration?>(acceleration, "acceleration")
            }
        }
    }

    internal interface RejectPlan {
        @JvmRecord
        data class None : RejectPlan {
            companion object {
                val INSTANCE: None = None()
            }
        }

        @JvmRecord
        data class RequiredLiteral(literal: String?) : RejectPlan {
            val literal: String?

            init {
                this.literal = literal
                Objects.requireNonNull<String?>(literal, "literal")
            }
        }

        @JvmRecord
        data class RequiredCharClass(scanInfo: CharClassScanInfo?) : RejectPlan {
            val scanInfo: CharClassScanInfo?

            init {
                this.scanInfo = scanInfo
                Objects.requireNonNull<CharClassScanInfo?>(scanInfo, "scanInfo")
            }
        }

        @JvmRecord
        data class DisjointLiterals(literals: Array<String?>?) : RejectPlan {
            val literals: Array<String?>?

            init {
                this.literals = literals
                Objects.requireNonNull<Array<String?>?>(literals, "literals")
            }
        }

        @JvmRecord
        data class EndAnchoredSuffix(suffix: SuffixInfo?) : RejectPlan {
            val suffix: SuffixInfo?

            init {
                this.suffix = suffix
                Objects.requireNonNull<SuffixInfo?>(suffix, "suffix")
            }
        }

        @JvmRecord
        data class EndAnchoredCharClass(charClass: EndAnchoredCharClassInfo?) : RejectPlan {
            val charClass: EndAnchoredCharClassInfo?

            init {
                this.charClass = charClass
                Objects.requireNonNull<EndAnchoredCharClassInfo?>(charClass, "charClass")
            }
        }

        @JvmRecord
        data class Composite(plans: Array<RejectPlan>?) : RejectPlan {
            val plans: Array<RejectPlan>?

            init {
                this.plans = plans
                Objects.requireNonNull<Array<RejectPlan?>?>(plans, "plans")
            }
        }
    }

    companion object {
        val NONE: MultiAnchorDescriptor =
            MultiAnchorDescriptor(false, StartPlan.None.Companion.INSTANCE, RejectPlan.None.Companion.INSTANCE)

        private fun impliedByLiteral(plan: RejectPlan, literal: String): Boolean {
            return when (plan) {
                -> literal.contains(rl.literal)
                -> literal.codePoints().anyMatch(IntPredicate { cp: Int -> rc.scanInfo.contains(cp) })
                else -> false
            }
        }
    }
}
