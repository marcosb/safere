// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.safere.MultiAnchorDescriptor.RejectPlan
import org.safere.MultiAnchorDescriptor.RejectPlan.RequiredCharClass
import org.safere.MultiAnchorDescriptor.RejectPlan.RequiredLiteral
import org.safere.MultiAnchorDescriptor.StartPlan
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmRecord
import kotlin.math.max
import kotlin.math.min
/**
 * Compiles AST representations of regular expressions into [MultiAnchorDescriptor], [ ], and [RejectDescriptor] instances in a single bottom-up traversal.
 */
internal object MultiAnchorCompiler {
    // Full case closures in the supported JDK casing data and SafeRE fold table have at most four
    // code points. A larger closure in future JDK casing data would only lose folded-prefix
    // acceleration; matching stays correct. MultiAnchorCompilerTest checks every closure and its
    // eligible folded prefix, catching growth beyond this bound.
    private const val MAX_UNICODE_FOLD_CLASS_RUNES = 4

    // The shortest skip, in input units, for which a leading expansion over a wide class finds its
    // start candidate. Its backward walk covers the rest of the line, so on shorter skips the
    // literal scan and the walk cost more than letting the engine scan from the search start.
    const val MIN_WIDE_LEADING_SKIP: Int = 256

    private const val MAX_LEADING_CLASS_COMPLEMENT = LeadingClassWidth.MAX_LEADING_CLASS_COMPLEMENT

    // Matches java.util.regex.Pattern.MULTILINE / org.safere.Pattern.MULTILINE.
    private const val MULTILINE = 8

    private fun runesToString(runes: IntArray): String {
        val sb = StringBuilder(runes.size)
        for (r in runes) {
            sb.append(Utils.runeToString(r))
        }
        return sb.toString()
    }

    private fun stringToCodePoints(literal: String): IntArray {
        val count = CodePoints.codePointCount(literal, 0, literal.length)
        val codePoints = IntArray(count)
        var i = 0
        var index = 0
        while (i < literal.length) {
            val cp = CodePoints.codePointAt(literal, i)
            codePoints[index++] = cp
            i += CodePoints.charCount(cp)
        }
        return codePoints
    }

    fun analyze(re: Regexp?): NodeAnalysis {
        return analyze(re, 0).root
    }

    private fun analyze(re: Regexp?, flags: Int): Analysis {
        if (re == null) {
            return Analysis(NodeAnalysis.EMPTY, ArrayList())
        }
        val walker = MultiAnchorWalker(re, flags)
        val root = walker.walk(re, NodeAnalysis.EMPTY)!!
        return Analysis(root, walker.rootChildren)
    }

    fun compile(re: Regexp?, flags: Int): MultiAnchorDescriptor? {
        if (re == null) {
            return null
        }
        val factored = factorAlternations(re)
        val node = unwrapCaptures(factored)
        if (node == null) {
            return null
        }

        val anchorStart =
            node.op == RegexpOp.CONCAT && node.subs != null && !node.subs!!.isEmpty() && node.subs!!.get(0)!!.op == RegexpOp.BEGIN_TEXT

        val analysis = analyze(factored, flags)
        val startPlan = extractStartPlan(factored, true, analysis.root!!)
        val rejectPlan =
            extractRejectPlan(factored, startPlan, anchorStart, analysis)

        val anchoredPrefix = analysis.root!!.start.anchoredPrefix
        val anchoredLiteral = if (anchoredPrefix.foldCase) null else anchoredPrefix.prefix

        return MultiAnchorDescriptor(
            anchorStart,
            startPlan,
            rejectPlan,
            anchoredLiteral,
            analysis.root!!.start.anchoredCharClassPrefix
        )
    }

    @JvmOverloads
    fun extractStartPlan(
        metadataAst: Regexp?, allowLeadingExpansion: Boolean = true
    ): StartPlan {
        if (metadataAst == null) {
            return StartPlan.None.INSTANCE
        }
        return extractStartPlan(metadataAst, allowLeadingExpansion, analyze(metadataAst)!!)
    }

    private fun extractStartPlan(
        metadataAst: Regexp?, allowLeadingExpansion: Boolean, analysis: NodeAnalysis
    ): StartPlan {
        val start = analysis.start

        if (start.startAcceleration != null) {
            return StartPlan.LineAnchor(start.startAcceleration)
        }

        val prefix = start.prefix!!.prefix
        val prefixFoldCase = start.prefix.foldCase
        val fol = start.fixedOffsetLiteral

        val prefixPoisonous =
            prefix != null && RarityOracle.isPoisonousAnchor(prefix, prefixFoldCase)
        val folPoisonous = fol != null && RarityOracle.isPoisonousAnchor(fol.literal, false)

        // When a fixed-offset literal is available and not poisonous:
        //  (a) If the leading prefix is poisonous (e.g. single space or high-frequency letter),
        //      suppress it and prioritize the non-poisonous fixed-offset literal.
        //  (b) Otherwise prefer whichever of the two is more selective. Ties go to the leading
        //      prefix, which needs no offset arithmetic to verify.
        //
        // There is deliberately no length gate and no margin here. Both were tried and both cost
        // more than they saved: requiring `prefix.length <= 2` meant the two candidates were
        // never even compared unless the leading prefix was a single character or a pair, which
        // is the minority case, and a 2x margin then suppressed most of what survived. Together
        // they pinned `error:\[[A-Z]\] code:500` to the leading `error:[` -- a literal that occurs
        // on every line of a log -- in preference to the far rarer `] code:500`.
        if (fol != null && !folPoisonous) {
            if (prefix == null || prefixPoisonous) {
                return StartPlan.FixedOffset(
                    org.safere.FixedOffsetLiteral(
                        fol.literal!!, fol.minOffset, fol.maxOffset, fol.discreteOffsets
                    ),
                    start.charClassPrefix
                )
            }
            val prefixScore = RarityOracle.literalSelectivityScore(prefix, prefixFoldCase)
            val folScore: Int = RarityOracle.literalSelectivityScore(fol.literal!!)
            if (folScore > prefixScore) {
                return StartPlan.FixedOffset(
                    org.safere.FixedOffsetLiteral(
                        fol.literal!!, fol.minOffset, fol.maxOffset, fol.discreteOffsets
                    ),
                    start.charClassPrefix
                )
            }
        }

        if (prefix != null && !prefixPoisonous) {
            return StartPlan.Literal(prefix, prefixFoldCase)
        }

        val classAnchor = analysis.classAnchor
        val leadingClass = start.charClassPrefix
        if (classAnchor != null && classAnchor.offset > 0 && leadingClass != null && leadingClass.isAscii
            && !leadingClass.isSelective
        ) {
            return StartPlan.FixedOffsetClass(
                classAnchor.scanInfo, classAnchor.offset, leadingClass
            )
        }

        val altLiterals = start.literalAlternation
        if (altLiterals != null && altLiterals.size >= 2) {
            return StartPlan.MultiLiteral(
                altLiterals,
                start.charClassPrefix,
                MultiLiteralInfo.create(altLiterals),
            )
        }

        if (allowLeadingExpansion) {
            val leadingExpansion =
                extractStartLeadingExpansion(metadataAst)
            if (leadingExpansion != null) {
                return leadingExpansion
            }
        }

        if (start.charClassPrefix != null && !(prefixPoisonous && prefixFoldCase)) {
            return StartPlan.CharClass(start.charClassPrefix)
        }

        return StartPlan.None.INSTANCE
    }

    fun extractRejectPlan(
        metadataAst: Regexp?, flags: Int, startPlan: StartPlan?, anchorStart: Boolean
    ): RejectPlan? {
        if (metadataAst == null) {
            return RejectPlan.None.INSTANCE
        }
        return extractRejectPlan(metadataAst, startPlan, anchorStart, analyze(metadataAst, flags))
    }

    private fun extractRejectPlan(
        metadataAst: Regexp?, startPlan: StartPlan?, anchorStart: Boolean, analysis: Analysis
    ): RejectPlan? {
        val reject = analysis.root.reject

        val plans: MutableList<RejectPlan?> = ArrayList<RejectPlan?>()

        val endAnchoredSuffix = reject.endAnchoredSuffix
        if (endAnchoredSuffix != null) {
            plans.add(RejectPlan.EndAnchoredSuffix(endAnchoredSuffix))
        }

        val endAnchoredCharClass =
            if (endAnchoredSuffix == null) reject.endAnchoredCharClass else null
        if (endAnchoredCharClass != null) {
            plans.add(RejectPlan.EndAnchoredCharClass(endAnchoredCharClass))
        }

        val excludeStartLiterals: MutableSet<String> = LinkedHashSet<String>()
        val drivingLiteral = drivingLiteral(startPlan)
        if (drivingLiteral != null) {
            excludeStartLiterals.add(drivingLiteral)
            if (hasFixedOffset(startPlan)) {
                excludeStartLiterals.addAll(
                    extractFixedPrefixLiterals(
                        metadataAst,
                        analysis.children
                    )
                )
            }
        }
        val ccPrefix = drivingCharClass(startPlan)
        val skipRequiredCharClass = subsumesRequiredCharClass(startPlan)

        val suffixStr = if (endAnchoredSuffix != null) endAnchoredSuffix.suffix else null

        val requiredLiteral =
            if (!anchorStart) extractRequiredLiteral(metadataAst, excludeStartLiterals, suffixStr) else null
        if (requiredLiteral != null) {
            plans.add(RequiredLiteral(requiredLiteral))
        }

        var requiredMatchClass: CharClassScanInfo? = null
        if (!anchorStart && !skipRequiredCharClass && endAnchoredCharClass == null) {
            val reqClass = reject.bestRequiredClass
            if (reqClass != null) {
                if (ccPrefix == null) {
                    requiredMatchClass = CharClassScanInfo.fromCharClass(reqClass)
                } else {
                    val candidate: CharClassScanInfo? = CharClassScanInfo.fromCharClass(reqClass)
                    if (candidate != null && candidate.ranges != null && moreSelective(
                            RarityOracle.charClassFrequencyScore(reqClass),
                            reqClass.numRunes(),
                            RarityOracle.charClassFrequencyScore(ccPrefix),
                            runeCount(ccPrefix.ranges)
                        )
                    ) {
                        requiredMatchClass = candidate
                    }
                }
            }
        }
        if (requiredMatchClass != null) {
            plans.add(RequiredCharClass(requiredMatchClass))
        }

        val disjointLiterals =
            if (!anchorStart && !skipRequiredCharClass && requiredLiteral == null)
                reject.disjointRequiredLiterals
            else
                null
        if (disjointLiterals != null && disjointLiterals.size > 1) {
            plans.add(RejectPlan.DisjointLiterals(disjointLiterals))
        }

        if (plans.isEmpty()) {
            return RejectPlan.None.INSTANCE
        }
        if (plans.size == 1) {
            return plans.get(0)
        }
        return RejectPlan.Composite(plans.filterNotNull().toTypedArray())
    }

    /**
     * Returns whether a class with the given frequency score and rune count is a better reject
     * candidate than the incumbent: rarer by [RarityOracle.charClassFrequencyScore] when both
     * classes can be scored, or with fewer runes when the scores tie or either class scores `0`
     * (no ASCII members, or too many non-ASCII members to ignore). An empty class also scores `0` and has no runes, so it wins, which is harmless: it rejects every input, as it should.
     */
    private fun moreSelective(
        candidateScore: Long, candidateRunes: Int, incumbentScore: Long, incumbentRunes: Int
    ): Boolean {
        if (candidateScore == 0L || incumbentScore == 0L) {
            return candidateRunes < incumbentRunes
        }
        return candidateScore < incumbentScore
                || (candidateScore == incumbentScore && candidateRunes < incumbentRunes)
    }

    private fun moreSelective(candidate: CharClass, incumbent: CharClass): Boolean {
        return moreSelective(
            RarityOracle.charClassFrequencyScore(candidate),
            candidate.numRunes(),
            RarityOracle.charClassFrequencyScore(incumbent),
            incumbent.numRunes()
        )
    }

    private fun runeCount(ranges: IntArray): Int = LeadingClassWidth.runeCount(ranges)

    /**
     * Returns the literal the start accelerator will scan for, unwrapping a leading expansion, or
     * `null` if the start plan is not literal-driven.
     */
    private fun drivingLiteral(plan: StartPlan?): String? {
        if (plan == null) {
            return null
        }
        return when (plan) {
            is StartPlan.Literal -> plan.prefix
            is StartPlan.FixedOffset -> plan.fol!!.literal()
            is StartPlan.FixedOffsetClass -> null
            is StartPlan.LeadingExpansion ->
                if (isWideLeadingClass(plan.leadingClass!!)) null else drivingLiteral(plan.innerPlan)

            is StartPlan.CharClass -> null
            is StartPlan.MultiLiteral -> null
            is StartPlan.LineAnchor -> null
            is StartPlan.None -> null
        }
    }

    /**
     * Returns the character class the start accelerator will scan for, or `null` if the start
     * plan is not class-driven. A required class from reject analysis is only worth scanning for
     * separately if it is narrower than this one. See [.leadingExpansionDrivingClass] for
     * leading expansions.
     */
    private fun drivingCharClass(plan: StartPlan?): CharClassScanInfo? {
        if (plan == null) {
            return null
        }
        return when (plan) {
            is StartPlan.CharClass -> plan.scanInfo
            is StartPlan.FixedOffset -> plan.leadingClass
            is StartPlan.FixedOffsetClass -> plan.scanInfo
            is StartPlan.MultiLiteral -> plan.fallbackClass
            is StartPlan.LeadingExpansion -> leadingExpansionDrivingClass(plan)
            is StartPlan.Literal -> null
            is StartPlan.LineAnchor -> null
            is StartPlan.None -> null
        }
    }

    /**
     * Returns the driving class of a leading expansion for reject-plan selection.
     * 
     * 
     * Usually `null`, so the expansion's required class is kept as a reject prefilter even
     * when it is the class the inner accelerator scans for. The prefilter fails the whole call on
     * input without the class before any per-call setup, including once up front in `replaceAll`, whereas the leading-expansion accelerator only gets there through its own
     * candidate loop. On `UnicodePrefixBenchmark.cjk.absent`, `[\u4E00-\u9FFF]+\d+`
     * measured 2.8x faster with the `[0-9]` prefilter than without it.
     * 
     * 
     * The exception is an ASCII leading class in front of a non-selective inner class, such as
     * `\d` in `[A-Za-z]+\d+`. Such a class occurs in almost any input, so the prefilter
     * rarely rejects, and the accelerator already scans for it with an ASCII kernel; keeping the
     * prefilter made `MatcherApiBenchmark.resetAndFind@safere-utf8` 1.09x slower on aarch64.
     * Returning the inner class lets `extractRejectPlan` drop a required class that is no
     * narrower. A non-ASCII leading class keeps the prefilter, because there the prefilter's ASCII
     * scan is what makes non-matching input cheap.
     */
    private fun leadingExpansionDrivingClass(le: StartPlan.LeadingExpansion): CharClassScanInfo? {
        val inner = le.innerPlan
        if (le.leadingClass!!.isAscii
            && inner is StartPlan.CharClass
            && !inner.scanInfo!!.isSelective
        ) {
            return inner.scanInfo
        }
        return null
    }

    /**
     * Returns whether a leading class has more members than a backward walk could usually afford to
     * cross. Such a class drives a leading expansion only when it excludes very few code points; see
     * [.extractStartLeadingExpansion].
     */
    private fun isWideLeadingClass(numRunes: Long): Boolean =
        LeadingClassWidth.isWideLeadingClass(numRunes)

    fun isWideLeadingClass(leadingClass: CharClassScanInfo): Boolean =
        LeadingClassWidth.isWideLeadingClass(leadingClass)

    /**
     * Returns the code points a wide leading class excludes, in increasing order, or null when the
     * class is not wide or excludes more than [.MAX_LEADING_CLASS_COMPLEMENT] code points.
     */
    fun excludedCodePoints(leadingClass: CharClassScanInfo): IntArray? {
        if (!isWideLeadingClass(leadingClass)) {
            return null
        }
        val ranges = leadingClass.ranges
        val excluded = IntArray(MAX_LEADING_CLASS_COMPLEMENT)
        var count = 0
        var next = 0
        var i = 0
        while (i <= ranges.size) {
            val lo = if (i < ranges.size) ranges[i] else Utils.MAX_RUNE + 1
            for (cp in next..<lo) {
                if (count == excluded.size) {
                    return null
                }
                excluded[count++] = cp
            }
            if (i < ranges.size) {
                next = ranges[i + 1] + 1
            }
            i += 2
        }
        return (excluded).copyOf(count)
    }

    /** Returns whether the start plan scans for a literal at the match start.  */
    private fun hasFixedOffset(plan: StartPlan?): Boolean {
        if (plan == null) {
            return false
        }
        return when (plan) {
            is StartPlan.FixedOffset -> true
            is StartPlan.FixedOffsetClass -> false
            is StartPlan.LeadingExpansion -> hasFixedOffset(plan.innerPlan)
            is StartPlan.Literal -> false
            is StartPlan.CharClass -> false
            is StartPlan.MultiLiteral -> false
            is StartPlan.LineAnchor -> false
            is StartPlan.None -> false
        }
    }

    /**
     * Returns whether the start accelerator makes a required character class reject scan redundant.
     * 
     * 
     * This deliberately does not unwrap [StartPlan.LeadingExpansion], even though its inner
     * plan may be literal-driven. The reject prefilter runs before the match machinery is set up, so
     * it can pay for itself even when the accelerator would go on to search for the same literal.
     * Extending this to leading expansions regressed the UTF-8 arm of rebar's `04-ruff-noqa` by
     * 1.70x.
     */
    private fun subsumesRequiredCharClass(plan: StartPlan?): Boolean {
        if (plan == null) {
            return false
        }
        return when (plan) {
            is StartPlan.Literal -> true
            is StartPlan.FixedOffset -> true
            is StartPlan.FixedOffsetClass -> true
            is StartPlan.LeadingExpansion -> false
            is StartPlan.CharClass -> false
            is StartPlan.MultiLiteral -> false
            is StartPlan.LineAnchor -> false
            is StartPlan.None -> false
        }
    }

    // --- Helper methods for chain, gap, and anchor extraction ---
    fun factorAlternations(re: Regexp?): Regexp? {
        // These are whole-tree properties. Factoring introduces only literals and structural nodes,
        // so descendants and newly factored nodes cannot acquire captures or assertions.
        if (re == null || hasFactoringBarrier(re)) {
            return re
        }
        return factorAlternations(re, 0)
    }

    private const val MAX_FACTOR_DEPTH = 16

    private fun factorAlternations(re: Regexp?, depth: Int): Regexp {
        var re = re
        if (re == null || depth > MAX_FACTOR_DEPTH) {
            return re!!
        }
        if (re.subs != null && !re.subs!!.isEmpty()) {
            val newSubs: MutableList<Regexp?> = ArrayList<Regexp?>(re.subs!!.size)
            var changed = false
            for (sub in re.subs!!) {
                val factored = factorAlternations(sub, depth + 1)
                if (re.op == RegexpOp.CONCAT && factored.op == RegexpOp.CONCAT && factored != sub && sub!!.op == RegexpOp.ALTERNATE && factored.subs != null) {
                    for (child in factored.subs!!) {
                        appendAndCoalesce(newSubs, child)
                    }
                    changed = true
                } else if (re.op == RegexpOp.CONCAT) {
                    appendAndCoalesce(newSubs, factored)
                    if (factored != sub || newSubs.size != re.subs!!.size) {
                        changed = true
                    }
                } else {
                    newSubs.add(factored)
                    if (factored != sub) {
                        changed = true
                    }
                }
            }
            if (changed) {
                re = copyWithSubs(re, newSubs)
            }
        }
        if (re.op == RegexpOp.ALTERNATE && re.subs != null && re.subs!!.size >= 2) {
            return factorAlternate(re)
        }
        return re
    }

    private fun appendAndCoalesce(subs: MutableList<Regexp?>, node: Regexp?) {
        if (node == null) {
            return
        }
        if (!subs.isEmpty()) {
            val prev = subs.get(subs.size - 1)
            val prevRunes = extractRunes(prev)
            val currRunes = extractRunes(node)
            if (prevRunes != null && currRunes != null && (prev!!.flags and ParseFlags.FOLD_CASE) == (node.flags and ParseFlags.FOLD_CASE)) {
                val merged = IntArray(prevRunes.size + currRunes.size)
                prevRunes.copyInto(merged, 0, 0, 0 + prevRunes.size)
                currRunes.copyInto(merged, prevRunes.size, 0, 0 + currRunes.size)
                subs.set(subs.size - 1, Regexp.literalString(merged, prev.flags))
                return
            }
        }
        subs.add(node)
    }

    private fun extractRunes(re: Regexp?): IntArray? {
        if (re == null) {
            return null
        }
        if (re.op == RegexpOp.LITERAL) {
            return intArrayOf(re.rune)
        }
        if (re.op == RegexpOp.LITERAL_STRING && re.runes != null) {
            return re.runes
        }
        return null
    }

    private fun copyWithSubs(re: Regexp, newSubs: MutableList<Regexp?>): Regexp {
        val copy = Regexp(re.op, re.flags)
        copy.rune = re.rune
        copy.runes = re.runes
        copy.min = re.min
        copy.max = re.max
        copy.cap = re.cap
        copy.name = re.name
        // charClass is null for non-CHAR_CLASS ops; preserve that like the Java original.
        copy.charClass = re.charClass
        copy.subs = newSubs.toMutableList()
        return copy
    }

    private fun factorAlternate(re: Regexp?): Regexp {
        if (re == null || re.op != RegexpOp.ALTERNATE || re.subs == null || re.subs!!.size < 2) {
            return re!!
        }

        // 1. Common literal prefix factoring
        val leadingRunesList: MutableList<IntArray> = ArrayList<IntArray>(re.subs!!.size)
        var allHaveLeading = true
        for (sub in re.subs!!) {
            val r = extractLeadingRunes(sub)
            if (r == null || r.size == 0) {
                allHaveLeading = false
                break
            }
            leadingRunesList.add(r)
        }

        if (allHaveLeading) {
            val lcp = commonPrefixLength(leadingRunesList)
            if (lcp >= 2) {
                val prefixFlags = commonLeadingLiteralFlags(re.subs!!, lcp)
                if (prefixFlags == null) {
                    return re
                }
                val prefixRunes = (leadingRunesList.get(0)).copyOf(lcp)
                val remainders: MutableList<Regexp?> = ArrayList<Regexp?>(re.subs!!.size)
                for (sub in re.subs!!) {
                    remainders.add(stripLeadingRunes(sub!!, lcp))
                }
                val prefixNode: Regexp =
                    if (prefixRunes.size == 1)
                        Regexp.literal(prefixRunes[0], prefixFlags)
                    else
                        Regexp.literalString(prefixRunes, prefixFlags)
                val alternateNode: Regexp = Regexp.alternate(remainders, re.flags)
                return Regexp.concat(listOf(prefixNode, alternateNode), re.flags)
            }
        }

        return re
    }

    private fun commonLeadingLiteralFlags(alternatives: MutableList<Regexp?>, runeCount: Int): Int? {
        var commonFlags: Int? = null
        for (alternative in alternatives) {
            val flags = homogeneousLeadingLiteralFlags(alternative, runeCount)
            if (flags == null) {
                return null
            }
            if (commonFlags == null) {
                commonFlags = flags
            } else if (((commonFlags xor flags) and ParseFlags.FOLD_CASE) != 0) {
                return null
            }
        }
        return commonFlags
    }

    private fun homogeneousLeadingLiteralFlags(re: Regexp?, runeCount: Int): Int? {
        if (re == null) {
            return null
        }
        val leading = if (re.op == RegexpOp.CONCAT && re.subs != null) re.subs!! else listOf(re)
        var commonFlags: Int? = null
        var remaining = runeCount
        for (node in leading) {
            if (node == null) {
                break
            }
            val nodeLength = literalRunesLength(node)
            if (nodeLength == 0) {
                break
            }
            if (commonFlags == null) {
                commonFlags = node.flags
            } else if (((commonFlags xor node.flags) and ParseFlags.FOLD_CASE) != 0) {
                return null
            }
            remaining -= min(remaining, nodeLength)
            if (remaining == 0) {
                return commonFlags
            }
        }
        return null
    }

    private fun hasFactoringBarrier(re: Regexp?): Boolean {
        if (re == null) {
            return false
        }
        val pending= ArrayDeque<Regexp>()
        pending.addLast(re)
        while (!pending.isEmpty()) {
            val current = pending.removeLast()
            if (current.op == RegexpOp.CAPTURE || current.cap != 0) {
                return true
            }
            when (current.op) {
                RegexpOp.BEGIN_LINE, RegexpOp.END_LINE, RegexpOp.BEGIN_TEXT, RegexpOp.END_TEXT, RegexpOp.WORD_BOUNDARY, RegexpOp.NO_WORD_BOUNDARY -> {
                    return true
                }

                else -> {
                    if (current.subs != null) {
                        for (sub in current.subs!!) {
                            if (sub != null) {
                                pending.addLast(sub)
                            }
                        }
                    }
                }
            }
        }
        return false
    }

    private fun extractLeadingRunes(sub: Regexp?): IntArray? {
        if (sub == null) {
            return null
        }
        if (sub.op == RegexpOp.LITERAL) {
            return intArrayOf(sub.rune)
        }
        if (sub.op == RegexpOp.LITERAL_STRING && sub.runes != null) {
            return sub.runes
        }
        if (sub.op == RegexpOp.CONCAT && sub.subs != null && !sub.subs!!.isEmpty()) {
            val collected: MutableList<Int?> = ArrayList<Int?>()
            for (c in sub.subs!!) {
                if (c == null) {
                    break
                }
                if (c.op == RegexpOp.LITERAL) {
                    collected.add(c.rune)
                } else if (c.op == RegexpOp.LITERAL_STRING && c.runes != null) {
                    for (r in c.runes) {
                        collected.add(r)
                    }
                } else {
                    break
                }
            }
            if (!collected.isEmpty()) {
                val res = IntArray(collected.size)
                for (i in res.indices) {
                    res[i] = collected.get(i)!!
                }
                return res
            }
        }
        return null
    }

    private fun commonPrefixLength(list: MutableList<IntArray>): Int {
        if (list.isEmpty()) {
            return 0
        }
        val first = list.get(0)
        var minLen: Int = first.size
        for (i in 1..<list.size) {
            minLen = min(minLen, list.get(i)!!.size)
        }
        var len = 0
        for (col in 0..<minLen) {
            val r = first[col]
            for (i in 1..<list.size) {
                if (list.get(i)[col] != r) {
                    return len
                }
            }
            len++
        }
        return len
    }

    private fun literalRunesLength(sub: Regexp?): Int {
        if (sub == null) {
            return 0
        }
        if (sub.op == RegexpOp.LITERAL) {
            return 1
        }
        if (sub.op == RegexpOp.LITERAL_STRING && sub.runes != null) {
            return sub.runes.size
        }
        return 0
    }

    private fun stripLeadingRunes(re: Regexp, count: Int): Regexp? {
        if (count <= 0) {
            return re
        }
        if (re.op == RegexpOp.LITERAL) {
            return Regexp.emptyMatch(re.flags)
        }
        if (re.op == RegexpOp.LITERAL_STRING && re.runes != null) {
            if (count >= re.runes.size) {
                return Regexp.emptyMatch(re.flags)
            }
            val remainder = re.runes.copyOfRange(count, re.runes.size)
            return if (remainder.size == 1)
                Regexp.literal(remainder[0], re.flags)
            else
                Regexp.literalString(remainder, re.flags)
        }
        if (re.op == RegexpOp.CONCAT && re.subs != null) {
            val newSubs: MutableList<Regexp?> = ArrayList<Regexp?>()
            var remainingToStrip = count
            for (sub in re.subs!!) {
                if (remainingToStrip <= 0) {
                    newSubs.add(sub)
                    continue
                }
                val subLen = literalRunesLength(sub)
                if (subLen > 0 && subLen <= remainingToStrip) {
                    remainingToStrip -= subLen
                } else if (subLen > remainingToStrip) {
                    val strippedSub = stripLeadingRunes(sub!!, remainingToStrip)
                    if (strippedSub != null && strippedSub.op != RegexpOp.EMPTY_MATCH) {
                        newSubs.add(strippedSub)
                    }
                    remainingToStrip = 0
                } else {
                    newSubs.add(sub)
                }
            }
            if (newSubs.isEmpty()) {
                return Regexp.emptyMatch(re.flags)
            }
            if (newSubs.size == 1) {
                return newSubs.get(0)
            }
            return Regexp.concat(newSubs, re.flags)
        }
        return re
    }

    private fun extractStartLeadingExpansion(
        re: Regexp?
    ): StartPlan.LeadingExpansion? {
        var re = re
        if (re == null) {
            return null
        }
        re = unwrapCaptures(re)
        if (re == null || re.op != RegexpOp.CONCAT || re.nsub() < 2) {
            return null
        }

        var idx = 0
        while (idx < re.nsub() && isLeadingZeroWidth(re.subs!![idx]!!)) {
            idx++
        }
        if (idx >= re.nsub() - 1) {
            return null
        }

        val first = unwrapCaptures(re.subs!!.get(idx))
        if (first == null) {
            return null
        }

        val minRepetition: Int
        val maxRepetition: Int
        val repeated: Regexp?
        when (first.op) {
            RegexpOp.STAR -> {
                minRepetition = 0
                maxRepetition = Int.MAX_VALUE
                repeated = unwrapCaptures(first.sub())
            }

            RegexpOp.PLUS -> {
                minRepetition = 1
                maxRepetition = Int.MAX_VALUE
                repeated = unwrapCaptures(first.sub())
            }

            RegexpOp.REPEAT -> {
                minRepetition = first.min
                maxRepetition = if (first.max == -1) Int.MAX_VALUE else first.max
                repeated = unwrapCaptures(first.sub())
            }

            RegexpOp.QUEST -> {
                minRepetition = 0
                maxRepetition = 1
                repeated = unwrapCaptures(first.sub())
            }

            else -> {
                return null
            }
        }

        if (repeated == null || repeated.op == RegexpOp.ANY_CHAR || repeated.op == RegexpOp.ANY_BYTE) {
            return null
        }

        val leadingClass: CharClassScanInfo?
        if (repeated.op == RegexpOp.CHAR_CLASS && repeated.charClass != null && !repeated.charClass!!.isEmpty()) {
            leadingClass = CharClassScanInfo.fromCharClass(repeated.charClass!!)
        } else if (repeated.op == RegexpOp.LITERAL) {
            leadingClass =
                CharClassScanInfo.fromCharClass(literalCharClass(repeated.rune, repeated.flags))
        } else {
            return null
        }

        if (leadingClass == null) {
            return null
        }

        var numRunes = 0
        val ranges = leadingClass.ranges
        if (ranges != null) {
            var i = 0
            while (i < ranges.size) {
                numRunes += (ranges[i + 1] - ranges[i] + 1)
                i += 2
            }
        }
        // A large class usually means the backward walk would cross most of the input. The exception
        // is a class that excludes only a few code points, such as non-DOTALL `.` (the line
        // terminators) or `[^;]`: there the walk stops at the nearest excluded code point, so for
        // `.*error` the candidate is the start of the line holding the inner hit. Such a class has few
        // ranges, so the walk's membership test stays cheap. DOTALL `.` (ANY_CHAR) is still excluded
        // above: its walk never stops before the search start.
        val wideLeadingClass = isWideLeadingClass(numRunes.toLong())
        if (wideLeadingClass && Utils.MAX_RUNE + 1 - numRunes > MAX_LEADING_CLASS_COMPLEMENT) {
            return null
        }

        val tailSubs = re.subs!!.subList(idx + 1, re.nsub())
        val tail: Regexp? = if (tailSubs.size == 1) tailSubs.first() else Regexp.concat(tailSubs, 0)

        val inner = extractStartPlan(tail, false)
        if (inner == null || inner is StartPlan.None // Keep class offsets scoped to their exact ASCII prefix, without a backward-expansion
            // wrapper that can revisit variable-width leading context.
            || inner is StartPlan.FixedOffsetClass
            || inner is StartPlan.LeadingExpansion
        ) {
            return null
        }
        // Before a wide class, only an exact literal search finds inner candidates sparse enough to
        // pay for walking back to the line start. Class and multi-literal scans may report every
        // position, or decline and report the search start.
        if (wideLeadingClass
            && !(inner is StartPlan.Literal
                    || inner is StartPlan.FixedOffset)
        ) {
            return null
        }
        // An optional leading character in front of a class too common to scan for, such as `-?\d+`,
        // gets no start accelerator, and the expansion would only make extractRejectPlan drop the
        // inner class's reject prefilter. Leave such patterns with no start plan, as before `?` was
        // supported, so they keep the reject.
        if (first.op == RegexpOp.QUEST && inner is StartPlan.CharClass
            && !inner.scanInfo!!.isSelective
        ) {
            return null
        }

        return StartPlan.LeadingExpansion(
            leadingClass, minRepetition, maxRepetition, idx > 0, inner
        )
    }

    fun extractPrefix(re: Regexp?): PrefixResult {
        val direct = extractPrefixFromCandidate(firstPrefixCandidate(re))
        return if (direct.prefix != null) direct else extractUnicodeFoldedPrefix(re)
    }

    fun extractUnicodeFoldedPrefix(re: Regexp?): PrefixResult {
        val work= ArrayDeque<Regexp?>()
        work.add(re)
        val prefix = StringBuilder()
        var sawFoldClass = false

        while (!work.isEmpty()) {
            val node = unwrapCaptures(work.removeFirst())
            if (node == null) {
                continue
            }
            if (node.op == RegexpOp.CONCAT) {
                for (i in node.subs!!.indices.reversed()) {
                    work.addFirst(node.subs!!.get(i))
                }
                continue
            }
            if (isLeadingZeroWidth(node)) {
                continue
            }

            if (node.op == RegexpOp.CHAR_CLASS) {
                val representative = simpleFoldClassRepresentative(node.charClass)
                if (representative < 0) {
                    break
                }
                prefix.append(Utils.runeToString(representative))
                sawFoldClass = true
                continue
            }

            if (node.op == RegexpOp.LITERAL) {
                if (!appendFoldCompatibleLiteral(prefix, node.rune, node.flags)) {
                    break
                }
                continue
            }
            if (node.op == RegexpOp.LITERAL_STRING && node.runes != null) {
                var compatible = true
                for (rune in node.runes) {
                    if (!appendFoldCompatibleLiteral(prefix, rune, node.flags)) {
                        compatible = false
                        break
                    }
                }
                if (compatible) {
                    continue
                }
            }
            break
        }

        return if (sawFoldClass && !prefix.isEmpty())
            PrefixResult(prefix.toString().lowercase(), true)
        else
            PrefixResult(null, false)
    }

    private fun appendFoldCompatibleLiteral(prefix: StringBuilder, rune: Int, flags: Int): Boolean {
        if (!Utils.isValidRune(rune)) {
            return false
        }
        if ((flags and ParseFlags.FOLD_CASE) == 0 && Inst.simpleFold(rune) != rune) {
            return false
        }
        prefix.append(Utils.runeToString(rune))
        return true
    }

    private fun simpleFoldClassRepresentative(charClass: CharClass?): Int {
        if (charClass == null || charClass.isEmpty()
            || charClass.numRunes() > MAX_UNICODE_FOLD_CLASS_RUNES
        ) {
            return -1
        }
        val representative = charClass.lo(0)
        val utf8Width = utf8Width(representative)
        var folded: Int = Inst.simpleFold(representative)
        if (folded == representative) {
            return -1
        }
        // A folded class must contain the whole simple-fold cycle. Reject ordinary classes before
        // asking for the full Unicode closure, whose index is expensive to initialize.
        var cycleLength = 1
        while (folded != representative) {
            if (!charClass.contains(folded) || utf8Width(folded) != utf8Width) {
                return -1
            }
            cycleLength++
            folded = Inst.simpleFold(folded)
        }
        // Callers match the representative with simple-fold literal search, so the class must be
        // exactly the cycle. The Unicode closure of I and i also contains U+0130 and U+0131, which the
        // simple-fold cycle does not visit.
        if (charClass.numRunes() != cycleLength) {
            return -1
        }
        val expected =
            literalCharClass(representative, ParseFlags.FOLD_CASE or ParseFlags.UNICODE_CASE)
        if (expected.numRanges() != charClass.numRanges()) {
            return -1
        }
        for (i in 0..<expected.numRanges()) {
            if (expected.lo(i) != charClass.lo(i) || expected.hi(i) != charClass.hi(i)) {
                return -1
            }
        }
        return representative
    }

    private fun utf8Width(codePoint: Int): Int {
        if (codePoint <= 0x7F) {
            return 1
        }
        if (codePoint <= 0x7FF) {
            return 2
        }
        return if (codePoint <= 0xFFFF) 3 else 4
    }

    private fun extractPrefixFromCandidate(node: Regexp?): PrefixResult {
        if (node == null) {
            return PrefixResult(null, false)
        }

        val foldCase = (node.flags and ParseFlags.FOLD_CASE) != 0
        val sb = StringBuilder()
        if (node.op == RegexpOp.LITERAL) {
            if (!Utils.isValidRune(node.rune)) {
                return PrefixResult(null, false)
            }
            sb.append(Utils.runeToString(node.rune))
        } else if (node.op == RegexpOp.LITERAL_STRING && areAllValidRunes(node.runes)) {
            for (r in node.runes) {
                sb.append(Utils.runeToString(r))
            }
        } else {
            return PrefixResult(null, false)
        }

        if (sb.isEmpty()) {
            return PrefixResult(null, false)
        }

        val prefix = if (foldCase) sb.toString().lowercase() else sb.toString()
        return PrefixResult(prefix, foldCase)
    }

    fun firstPrefixCandidate(re: Regexp?): Regexp? {
        val stack= ArrayDeque<Regexp?>()
        stack.addFirst(re)
        while (!stack.isEmpty()) {
            val node = unwrapCaptures(stack.removeFirst())
            if (node == null || isLeadingZeroWidth(node)) {
                continue
            }
            if (node.op == RegexpOp.CONCAT) {
                for (i in node.subs!!.indices.reversed()) {
                    stack.addFirst(node.subs!!.get(i))
                }
            } else {
                return node
            }
        }
        return null
    }

    fun firstPrefixCandidateAfterTextAnchor(re: Regexp?): Regexp? {
        val stack= ArrayDeque<Regexp?>()
        stack.addFirst(re)
        var sawTextAnchor = false
        while (!stack.isEmpty()) {
            val node = unwrapCaptures(stack.removeFirst())
            if (node == null) {
                continue
            }
            if (node.op == RegexpOp.CONCAT && node.subs != null) {
                for (i in node.subs!!.indices.reversed()) {
                    stack.addFirst(node.subs!!.get(i))
                }
                continue
            }
            if (!sawTextAnchor) {
                if (node.op == RegexpOp.BEGIN_TEXT) {
                    sawTextAnchor = true
                    continue
                }
                if (isLeadingZeroWidth(node)) {
                    continue
                }
                return null
            }
            if (!isLeadingZeroWidth(node)) {
                return node
            }
        }
        return null
    }

    fun extractExactAsciiLiteral(re: Regexp?): String? {
        if (re == null) {
            return null
        }
        val literal = StringBuilder()
        val pending= ArrayDeque<Regexp?>()
        pending.addFirst(re)
        while (!pending.isEmpty()) {
            val node = unwrapCaptures(pending.removeFirst())
            if (node == null || (node.flags and ParseFlags.FOLD_CASE) != 0) {
                return null
            }
            if (node.op == RegexpOp.LITERAL && node.rune >= 0 && node.rune < 128) {
                literal.append(node.rune.toChar())
                continue
            }
            if (node.op == RegexpOp.LITERAL_STRING && node.runes != null && node.runes.size > 0) {
                for (rune in node.runes) {
                    if (rune < 0 || rune >= 128) {
                        return null
                    }
                    literal.append(rune.toChar())
                }
                continue
            }
            if (node.op == RegexpOp.CONCAT && node.subs != null) {
                for (index in node.subs!!.indices.reversed()) {
                    pending.addFirst(node.subs!!.get(index))
                }
                continue
            }
            return null
        }
        return if (literal.isEmpty()) null else literal.toString()
    }

    fun extractExactAsciiLiteralIgnoringCase(re: Regexp?): String? {
        if (re == null) {
            return null
        }
        val literal = StringBuilder()
        val pending= ArrayDeque<Regexp?>()
        pending.addFirst(re)
        while (!pending.isEmpty()) {
            val node = unwrapCaptures(pending.removeFirst())
            if (node == null) {
                return null
            }
            if (node.op == RegexpOp.LITERAL && node.rune >= 0 && node.rune < 128) {
                literal.append(node.rune.toChar())
                continue
            }
            if (node.op == RegexpOp.LITERAL_STRING && node.runes != null && node.runes.size > 0) {
                for (rune in node.runes) {
                    if (rune < 0 || rune >= 128) {
                        return null
                    }
                    literal.append(rune.toChar())
                }
                continue
            }
            if (node.op == RegexpOp.CONCAT && node.subs != null) {
                for (index in node.subs!!.indices.reversed()) {
                    pending.addFirst(node.subs!!.get(index))
                }
                continue
            }
            return null
        }
        return if (literal.isEmpty()) null else literal.toString()
    }

    fun extractCharClassPrefix(re: Regexp?): CharClassScanInfo? {
        val builder = CharClassBuilder()
        val work= ArrayDeque<Regexp?>()
        work.add(re)

        while (!work.isEmpty()) {
            var node = work.removeLast()

            while (true) {
                node = unwrapCaptures(node)
                if (node == null) {
                    return null
                }
                if (node.op == RegexpOp.CONCAT && node.nsub() > 0) {
                    var i = 0
                    while (i < node.nsub() && isLeadingZeroWidth(node.subs!![i]!!)) {
                        i++
                    }
                    if (i < node.nsub()) {
                        node = node.subs!!.get(i)
                        continue
                    }
                    return null
                }
                if (node.op == RegexpOp.PLUS || (node.op == RegexpOp.REPEAT && node.min >= 1)) {
                    node = node.sub()
                    continue
                }
                break
            }

            when (node.op) {
                RegexpOp.LITERAL -> {
                    builder.addCharClass(literalCharClass(node.rune, node.flags))
                }

                RegexpOp.LITERAL_STRING -> {
                    if (node.runes == null || node.runes.size == 0) {
                        return null
                    }
                    builder.addCharClass(literalCharClass(node.runes[0], node.flags))
                }

                RegexpOp.CHAR_CLASS -> {
                    if (node.charClass == null || node.charClass!!.isEmpty()) {
                        return null
                    }
                    builder.addCharClass(node.charClass!!)
                }

                RegexpOp.ALTERNATE -> {
                    if (node.nsub() == 0) {
                        return null
                    }
                    for (sub in node.subs!!) {
                        work.add(sub)
                    }
                }

                else -> {
                    return null
                }
            }
        }

        val cc = builder.build()
        if (cc.isEmpty()) {
            return null
        }
        if (cc.numRunes() > 0x80000) {
            return null
        }
        return CharClassScanInfo.fromCharClass(cc)
    }

    fun extractFixedOffsetLiteral(re: Regexp?): FixedOffsetLiteralSpan? {
        return extractFixedOffsetLiteral(re, null)
    }

    private fun extractFixedOffsetLiteral(
        re: Regexp?, children: MutableList<NodeAnalysis>?
    ): FixedOffsetLiteralSpan? {
        val node = unwrapCaptures(re)
        if (node == null || node.op != RegexpOp.CONCAT || node.subs == null) {
            return null
        }
        var best: FixedOffsetLiteralSpan? = null
        var bestScore = 0
        var prefixWidth = AsciiWidthRange.ZERO

        var index = 0
        while (index < node.subs!!.size) {
            val literalPart = extractExactAsciiLiteral(node.subs!!.get(index))
            if (literalPart != null) {
                val literal = StringBuilder(literalPart)
                var next = index + 1
                while (next < node.subs!!.size) {
                    val nextPart = extractExactAsciiLiteral(node.subs!!.get(next))
                    if (nextPart == null) {
                        break
                    }
                    literal.append(nextPart)
                    next++
                }
                if (index > 0 && (prefixWidth.minWidth > 0 || prefixWidth.maxWidth > 0)) {
                    val minimumLiteralLength = if (prefixWidth.discreteWidths != null) 1 else 2
                    if (literal.length >= minimumLiteralLength) {
                        val candidateScore: Int = RarityOracle.literalSelectivityScore(literal)
                        if (best == null || candidateScore > bestScore) {
                            best =
                                FixedOffsetLiteralSpan(
                                    literal.toString(),
                                    prefixWidth.minWidth,
                                    prefixWidth.maxWidth,
                                    prefixWidth.discreteWidths
                                )
                            bestScore = candidateScore
                        }
                    }
                }
                prefixWidth = AsciiWidthRange.concatenate(prefixWidth, AsciiWidthRange.exact(literal.length))
                if (!prefixWidth.isValid) {
                    break
                }
                index = next
                continue
            }

            val childWidth =
                (if (children != null)
                    children.get(index)!!.width
                else
                    computeAsciiWidthRange(node.subs!!.get(index)))!!
            prefixWidth = AsciiWidthRange.concatenate(prefixWidth, childWidth)
            if (!prefixWidth.isValid) {
                break
            }
            index++
        }
        return best
    }

    fun computeAsciiWidthRange(re: Regexp?): AsciiWidthRange {
        return AsciiWidthRangeWalker().walk(re, AsciiWidthRange.INVALID)
            ?: AsciiWidthRange.INVALID
    }

    private fun isLeadingZeroWidth(re: Regexp): Boolean {
        return when (re.op) {
            RegexpOp.EMPTY_MATCH, RegexpOp.WORD_BOUNDARY, RegexpOp.NO_WORD_BOUNDARY, RegexpOp.BEGIN_LINE, RegexpOp.BEGIN_TEXT -> true
            else -> false
        }
    }

    private fun extractLiteralAlternation(re: Regexp?): Array<String?>? {
        var re = re
        if (re == null) {
            return null
        }
        re = unwrapCaptures(re)
        if (re == null || re.op != RegexpOp.ALTERNATE || re.nsub() < 2) {
            return null
        }
        val literals = arrayOfNulls<String>(re.nsub())
        for (i in 0..<re.nsub()) {
            val lit = extractExactAsciiLiteral(re.subs!!.get(i))
            if (lit == null || lit.isEmpty()) {
                return null
            }
            literals[i] = lit
        }
        return literals
    }

    private fun extractStartAcceleration(re: Regexp?): StartAcceleration? {
        val node = unwrapCaptures(re)
        if (node == null) {
            return null
        }

        if (node.op == RegexpOp.CONCAT && node.nsub() > 0) {
            val first = unwrapCaptures(node.subs!!.get(0))
            if (first != null && first.op == RegexpOp.BEGIN_LINE) {
                var requiredStart: AsciiBitmap? = null
                if (node.nsub() > 1) {
                    requiredStart = requiredFirstAscii(node.subs!!.get(1))
                }
                return StartAcceleration(true, false, requiredStart)
            }
            return null
        }

        if (node.op == RegexpOp.BEGIN_LINE) {
            return StartAcceleration(true, false, null)
        }
        return null
    }

    private fun requiredFirstAscii(re: Regexp?): AsciiBitmap? {
        var node = unwrapCaptures(re)
        if (node == null) {
            return null
        }
        if (node.op == RegexpOp.CONCAT && node.nsub() > 0) {
            node = unwrapCaptures(node.subs!!.get(0))
        }
        if (node == null) {
            return null
        }
        if (node.op == RegexpOp.PLUS || (node.op == RegexpOp.REPEAT && node.min >= 1)) {
            node = unwrapCaptures(node.sub())
        }
        if (node == null) {
            return null
        }

        if (node.op == RegexpOp.LITERAL) {
            if ((node.flags and ParseFlags.FOLD_CASE) != 0 || node.rune >= 128) {
                return null
            }
            return AsciiBitmap.of(node.rune)
        }
        if (node.op == RegexpOp.LITERAL_STRING && node.runes != null && node.runes.size > 0) {
            if ((node.flags and ParseFlags.FOLD_CASE) != 0 || node.runes[0] >= 128) {
                return null
            }
            return AsciiBitmap.of(node.runes[0])
        }
        if (node.op == RegexpOp.CHAR_CLASS && node.charClass != null) {
            val cc = node.charClass!!
            if (cc.isEmpty()) {
                return null
            }
            for (i in 0..<cc.numRanges()) {
                if (cc.hi(i) >= 128) {
                    return null
                }
            }
            val builder = AsciiBitmap.Builder()
            for (i in 0..<cc.numRanges()) {
                builder.addRange(cc.lo(i), cc.hi(i))
            }
            return builder.build()
        }
        return null
    }

    private fun addAsciiCharClass(cc: CharClass?, builder: AsciiBitmap.Builder): Boolean {
        if (cc == null || cc.isEmpty()) {
            return false
        }
        for (i in 0..<cc.numRanges()) {
            if (cc.hi(i) >= 128) {
                return false
            }
        }
        for (i in 0..<cc.numRanges()) {
            builder.addRange(cc.lo(i), cc.hi(i))
        }
        return true
    }

    private fun extractEndAnchoredSuffix(metadataAst: Regexp?, flags: Int): SuffixInfo? {
        val node = unwrapCaptures(metadataAst)
        if (node == null || node.op != RegexpOp.CONCAT || node.nsub() < 2) {
            return null
        }
        val subs = node.subs!!
        val last = unwrapCaptures(subs.get(subs.size - 1))
        if (last == null || last.op != RegexpOp.END_TEXT) {
            return null
        }
        if ((flags and MULTILINE) != 0 && (last.flags and ParseFlags.WAS_DOLLAR) != 0) {
            return null
        }
        val wasDollar = (last.flags and ParseFlags.WAS_DOLLAR) != 0
        var foldCase = false

        val suffixParts = ArrayDeque<String?>()
        var suffixLength = 0
        for (i in subs.size - 2 downTo 0) {
            val sub = unwrapCaptures(subs.get(i))
            if (sub == null) {
                break
            }
            val subFold = (sub.flags and ParseFlags.FOLD_CASE) != 0
            if (sub.op == RegexpOp.LITERAL) {
                if (!Utils.isValidRune(sub.rune) || (subFold && sub.rune > 0x7F)) {
                    break
                }
                val part = Utils.runeToString(sub.rune)
                suffixParts.addFirst(part)
                suffixLength += part.length
                foldCase = foldCase or subFold
            } else if (sub.op == RegexpOp.LITERAL_STRING && areAllValidRunes(sub.runes)) {
                if (subFold && !isAllAscii(sub.runes)) {
                    break
                }
                val part = runesToString(sub.runes)
                suffixParts.addFirst(part)
                suffixLength += part.length
                foldCase = foldCase or subFold
            } else {
                break
            }
        }
        if (suffixParts.isEmpty()) {
            return null
        }
        val suffix = StringBuilder(suffixLength)
        for (str in suffixParts) {
            suffix.append(str)
        }
        return SuffixInfo(
            suffix.toString(), wasDollar, (last.flags and ParseFlags.UNIX_LINES) != 0, foldCase
        )
    }

    private fun areAllValidRunes(runes: IntArray?): Boolean {
        if (runes == null) {
            return false
        }
        for (r in runes) {
            if (!Utils.isValidRune(r)) {
                return false
            }
        }
        return true
    }

    private fun canRepresentAsCodePointString(runes: IntArray?): Boolean {
        if (runes == null) {
            return false
        }
        for (i in runes.indices) {
            val r = runes[i]
            if (r < Utils.MIN_RUNE || r > Utils.MAX_RUNE) {
                return false
            }
            if (CodePoints.isHighSurrogate(r) &&
                i + 1 < runes.size &&
                CodePoints.isLowSurrogate(runes[i + 1])
            ) {
                return false
            }
        }
        return true
    }

    private fun isAllAscii(runes: IntArray): Boolean {
        for (r in runes) {
            if (r > 0x7F) {
                return false
            }
        }
        return true
    }

    private fun extractEndAnchoredCharClass(
        metadataAst: Regexp?, flags: Int
    ): EndAnchoredCharClassInfo? {
        val node = unwrapCaptures(metadataAst)
        if (node == null || node.op != RegexpOp.CONCAT || node.nsub() < 2) {
            return null
        }
        val subs = node.subs!!
        val last = unwrapCaptures(subs.get(subs.size - 1))
        if (last == null || last.op != RegexpOp.END_TEXT) {
            return null
        }
        if ((flags and MULTILINE) != 0 && (last.flags and ParseFlags.WAS_DOLLAR) != 0) {
            return null
        }
        val wasDollar = (last.flags and ParseFlags.WAS_DOLLAR) != 0

        var sub = unwrapCaptures(subs.get(subs.size - 2))
        if (sub == null) {
            return null
        }
        while (sub!!.op == RegexpOp.PLUS || (sub.op == RegexpOp.REPEAT && sub.min >= 1)) {
            sub = unwrapCaptures(sub.sub())
            if (sub == null) {
                return null
            }
        }
        val builder = AsciiBitmap.Builder()
        if (sub.op == RegexpOp.CHAR_CLASS && addAsciiCharClass(sub.charClass, builder)) {
            val unixLines = (last.flags and ParseFlags.UNIX_LINES) != 0
            return EndAnchoredCharClassInfo(builder.build(), wasDollar, unixLines)
        }
        return null
    }

    private fun extractFixedPrefixLiterals(re: Regexp?, children: MutableList<NodeAnalysis>): MutableSet<String> {
        val node = unwrapCaptures(re)
        if (node == null || node.op != RegexpOp.CONCAT || node.subs == null) {
            return LinkedHashSet()
        }
        val literals: MutableSet<String> = LinkedHashSet()
        var prefixWidth = AsciiWidthRange.ZERO
        for (index in node.subs!!.indices) {
            val sub = node.subs!!.get(index)
            prefixWidth = AsciiWidthRange.concatenate(prefixWidth, children.get(index)!!.width)
            if (!prefixWidth.isValid) {
                break
            }
            collectAsciiLiterals(sub, literals)
        }
        return literals
    }

    private fun collectAsciiLiterals(re: Regexp?, literals: MutableSet<String>) {
        val exact = extractExactAsciiLiteral(re)
        if (exact != null && exact.length >= 2) {
            literals.add(exact)
        }

        val pending= ArrayDeque<Regexp?>()
        pending.addLast(re)
        while (!pending.isEmpty()) {
            val node = unwrapCaptures(pending.removeLast())
            if (node == null) {
                continue
            }
            if ((node.op == RegexpOp.LITERAL_STRING || node.op == RegexpOp.LITERAL)
                && (node.flags and ParseFlags.FOLD_CASE) == 0 && areAllValidRunes(node.runes)
                && node.runes.size >= 2
            ) {
                literals.add(runesToString(node.runes))
            }
            if (node.subs != null) {
                for (child in node.subs!!) {
                    pending.addLast(child)
                }
            }
        }
    }

    private fun extractRequiredLiteral(
        re: Regexp?, excludePrefixes: MutableSet<String>?, excludeSuffix: String?
    ): String? {
        var best: String? = null
        var bestScore = 0
        val pending = ArrayDeque<Regexp?>()
        pending.addLast(re)
        while (!pending.isEmpty()) {
            val node = pending.removeLast() ?: continue
            when (node.op) {
                RegexpOp.CAPTURE, RegexpOp.NON_CAPTURE, RegexpOp.PLUS -> pending.addLast(node.sub())
                RegexpOp.REPEAT -> {
                    if (node.min > 0) {
                        pending.addLast(node.sub())
                    }
                }

                RegexpOp.CONCAT -> {
                    if (node.subs != null) {
                        val exactAscii = extractExactAsciiLiteral(node)
                        if (exactAscii != null && exactAscii.length >= 2) {
                            if (!isLiteralSubsumed(exactAscii, excludePrefixes, excludeSuffix)) {
                                val score: Int = RarityOracle.literalSelectivityScore(exactAscii)
                                if (best == null || score > bestScore) {
                                    best = exactAscii
                                    bestScore = score
                                }
                            }
                        }
                        for (sub in node.subs!!) {
                            pending.addLast(sub)
                        }
                    }
                }

                RegexpOp.LITERAL_STRING -> {
                    if ((node.flags and ParseFlags.FOLD_CASE) == 0 && areAllValidRunes(node.runes)
                        && node.runes.size >= 2
                    ) {
                        val candidate = runesToString(node.runes)
                        if (!isLiteralSubsumed(candidate, excludePrefixes, excludeSuffix)) {
                            val candidateScore: Int = RarityOracle.literalSelectivityScore(candidate)
                            if (best == null || candidateScore > bestScore) {
                                best = candidate
                                bestScore = candidateScore
                            }
                        }
                    }
                }

                else -> {}
            }
        }
        return best
    }

    private fun isLiteralSubsumed(
        candidate: String?, excludedPrefixes: MutableSet<String>?, excludedSuffix: String?
    ): Boolean {
        if (candidate == null) {
            return false
        }
        if (excludedSuffix != null
            && (candidate == excludedSuffix || excludedSuffix.contains(candidate))
        ) {
            return true
        }
        if (excludedPrefixes != null) {
            for (excluded in excludedPrefixes) {
                if (candidate == excluded || excluded.contains(candidate)) {
                    return true
                }
            }
        }
        return false
    }

    private fun combineDisjointRequiredLiterals(children: MutableList<NodeAnalysis>?): Array<String?>? {
        if (children == null || children.size < 2) {
            return null
        }
        val literalSet: MutableSet<String?> = LinkedHashSet<String?>()
        for (branch in children) {
            val req = branch!!.reject.bestRequiredLiteral
            if (req == null || req.length < 2) {
                return null
            }
            literalSet.add(req)
            if (literalSet.size > 4) {
                return null
            }
        }
        val rawList = ArrayList<String>(literalSet.size)
        for (literal in literalSet) {
            if (literal != null) {
                rawList.add(literal)
            }
        }
        val rawCodePoints: MutableList<IntArray?> = ArrayList<IntArray?>(rawList.size)
        val rawFailures: MutableList<IntArray?> = ArrayList<IntArray?>(rawList.size)
        for (literal in rawList) {
            val codePoints = stringToCodePoints(literal)
            rawCodePoints.add(codePoints)
            rawFailures.add(literalFailure(codePoints))
        }
        val pruned: MutableSet<String?> = LinkedHashSet<String?>()
        for (i in rawList.indices) {
            val s1 = rawList.get(i)
            var subsumed = false
            for (j in rawList.indices) {
                if (i != j) {
                    val s2 = rawList.get(j)
                    if (containsCodePointSequence(
                            rawCodePoints.get(i)!!, rawCodePoints.get(j)!!, rawFailures.get(j)!!
                        )
                        && (s1.length > s2.length || (s1.length == s2.length && j < i))
                    ) {
                        subsumed = true
                        break
                    }
                }
            }
            if (!subsumed) {
                pruned.add(s1)
            }
        }
        if (pruned.size < 2) {
            return null
        }
        return pruned.toTypedArray()
    }

    private fun literalFailure(literal: IntArray): IntArray {
        val failure = IntArray(literal.size)
        var matched = 0
        for (index in 1..<literal.size) {
            while (matched > 0 && literal[index] != literal[matched]) {
                matched = failure[matched - 1]
            }
            if (literal[index] == literal[matched]) {
                matched++
            }
            failure[index] = matched
        }
        return failure
    }

    private fun containsCodePointSequence(value: IntArray, candidate: IntArray, failure: IntArray): Boolean {
        var matched = 0
        for (codePoint in value) {
            while (matched > 0 && codePoint != candidate[matched]) {
                matched = failure[matched - 1]
            }
            if (codePoint == candidate[matched]) {
                matched++
                if (matched == candidate.size) {
                    return true
                }
            }
        }
        return false
    }

    private fun literalCharClass(cp: Int, flags: Int): CharClass {
        val ccb = CharClassBuilder()
        if ((flags and ParseFlags.FOLD_CASE) == 0) {
            ccb.addRange(cp, cp)
        } else if ((flags and ParseFlags.UNICODE_CASE) == 0) {
            UnicodeCaseFolding.addAsciiFoldedRange(ccb, cp, cp)
        } else {
            UnicodeCaseFolding.addUnicodeFoldedRange(ccb, cp, cp)
        }
        return ccb.build()
    }

    private fun unwrapCaptures(re: Regexp?): Regexp? {
        var node = re
        while (node != null && (node.op == RegexpOp.CAPTURE || node.op == RegexpOp.NON_CAPTURE)) {
            node = node.sub()
        }
        return node
    }

    // The walker owns these child results; they are used only during this compilation and are
    // never modified after the root's postVisit. Captures retain the unwrapped root's children.
    @JvmRecord
    private data class Analysis(val root: NodeAnalysis, val children: MutableList<NodeAnalysis>)

    // --- Bottom-up MultiAnchorWalker ---
    private class MultiAnchorWalker(root: Regexp?, flags: Int) : Walker<NodeAnalysis>() {
        private val flags: Int
        private val fullAnalysisRoot: Regexp?

        // These results align with unwrapCaptures(root).subs!!; fixed-offset extraction indexes them
        // only when that unwrapped root is a CONCAT.
        var rootChildren: MutableList<NodeAnalysis> = ArrayList()

        init {
            this.flags = flags
            fullAnalysisRoot = unwrapCaptures(root)
        }

        override fun postVisit(
            node: Regexp, parentArg: NodeAnalysis?, preArg: NodeAnalysis?, childArgs: MutableList<NodeAnalysis>
        ): NodeAnalysis {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            var analysis =
                when (node.op) {
                    RegexpOp.CAPTURE, RegexpOp.NON_CAPTURE -> if (childArgs.isEmpty()) NodeAnalysis.UNKNOWN else childArgs.first()
                    RegexpOp.EMPTY_MATCH, RegexpOp.WORD_BOUNDARY, RegexpOp.NO_WORD_BOUNDARY, RegexpOp.BEGIN_TEXT, RegexpOp.END_TEXT, RegexpOp.END_LINE -> NodeAnalysis.EMPTY
                    RegexpOp.BEGIN_LINE -> NodeAnalysis(
                        AsciiWidthRange.ZERO,
                        StartFacets(
                            PrefixResult.NO_PREFIX,
                            null,
                            null,
                            StartAcceleration(true, false, null),
                            PrefixResult.NO_PREFIX,
                            null,
                            null
                        ),
                        RejectFacets.EMPTY,
                        null
                    )

                    RegexpOp.LITERAL -> visitLiteral(node)
                    RegexpOp.LITERAL_STRING -> visitLiteralString(node)
                    RegexpOp.CHAR_CLASS -> visitCharClass(node)
                    RegexpOp.CONCAT -> visitConcat(node, childArgs, node == fullAnalysisRoot)
                    RegexpOp.ALTERNATE -> visitAlternate(node, childArgs)
                    RegexpOp.QUEST -> visitQuest(childArgs)
                    RegexpOp.STAR -> visitStar()
                    RegexpOp.PLUS -> visitPlus(childArgs)
                    RegexpOp.REPEAT -> visitRepeat(node, childArgs)
                    else -> NodeAnalysis.UNKNOWN
                }
            if (node == fullAnalysisRoot) {
                rootChildren = childArgs
                // Concat already extracts the complete prefix, and atomic nodes build their own scanner.
                if (node.op != RegexpOp.CONCAT && node.op != RegexpOp.LITERAL && node.op != RegexpOp.LITERAL_STRING && node.op != RegexpOp.CHAR_CLASS) {
                    analysis = withRootCharClassPrefix(node, analysis)
                }
            }
            return NodeAnalysis(
                analysis.width,
                analysis.start,
                analysis.reject,
                analysis.atomicRequiredClass,
                classAnchor(node, analysis, childArgs)
            )
        }

        override fun shortVisit(re: Regexp, parentArg: NodeAnalysis?): NodeAnalysis {
            return NodeAnalysis.UNKNOWN
        }

        fun visitConcat(
            node: Regexp, children: MutableList<NodeAnalysis>, fullAnalysis: Boolean
        ): NodeAnalysis {
            if (children.isEmpty()) {
                return NodeAnalysis.EMPTY
            }
            var width = AsciiWidthRange.ZERO
            for (c in children) {
                width = AsciiWidthRange.concatenate(width, c.width)
                if (!width.isValid) {
                    break
                }
            }

            if (!fullAnalysis) {
                return visitNestedConcat(node, children, width)
            }

            // Prefix
            val prefix = extractPrefix(node)

            // Fixed-offset literal
            val fol = extractFixedOffsetLiteral(node, children)

            // Best required literal across concat
            val exactLit = extractExactAsciiLiteral(node)
            var bestReqLit = if (exactLit != null && exactLit.length >= 2) exactLit else null
            var bestReqScore = if (bestReqLit != null) RarityOracle.literalSelectivityScore(bestReqLit) else 0
            for (c in children) {
                val childReq = c.reject.bestRequiredLiteral
                if (childReq != null && childReq.length >= 2) {
                    val score = c.reject.bestRequiredLiteralScore
                    if (bestReqLit == null || score > bestReqScore) {
                        bestReqLit = childReq
                        bestReqScore = score
                    }
                }
            }

            // Best required class
            var bestReqClass: CharClass? = null
            for (c in children) {
                val childReqClass = c.reject.bestRequiredClass
                if (childReqClass != null) {
                    if (bestReqClass == null || moreSelective(childReqClass, bestReqClass)) {
                        bestReqClass = childReqClass
                    }
                }
            }

            // Disjoint required literals
            var disjoint: Array<String?>? = null
            if (node.subs != null && !node.subs!!.isEmpty()) {
                val first = unwrapCaptures(node.subs!!.get(0))
                if (first == null || (first.op != RegexpOp.BEGIN_TEXT && first.op != RegexpOp.BEGIN_LINE)) {
                    for (c in children) {
                        if (c.reject.disjointRequiredLiterals != null) {
                            disjoint = c.reject.disjointRequiredLiterals
                            break
                        }
                    }
                }
            }

            // End-anchored suffix & char class
            val endSuffix = extractEndAnchoredSuffix(node, flags)
            val endClass = extractEndAnchoredCharClass(node, flags)

            // Start acceleration
            val startAcc = extractStartAcceleration(node)

            // Anchored prefix
            val anchoredCandidate = firstPrefixCandidateAfterTextAnchor(node)
            val anchoredPrefix =
                if (anchoredCandidate != null)
                    extractPrefixFromCandidate(anchoredCandidate)
                else
                    PrefixResult.NO_PREFIX
            val anchoredCc =
                if (anchoredCandidate != null) extractCharClassPrefix(anchoredCandidate) else null

            val ccPrefix = extractCharClassPrefix(node)
            val start =
                StartFacets(prefix, ccPrefix, fol, startAcc, anchoredPrefix, anchoredCc, null)
            val reject =
                RejectFacets(bestReqLit, bestReqScore, bestReqClass, disjoint, endSuffix, endClass)

            return NodeAnalysis(width, start, reject, null)
        }

        companion object {
            /**
             * Carries one required class at an exact ASCII offset through the existing bottom-up walk.
             * Variable widths stop offset propagation; optional operands never contribute an anchor.
             */
            private fun classAnchor(
                node: Regexp, analysis: NodeAnalysis, children: MutableList<NodeAnalysis>
            ): ClassAnchor? {
                return when (node.op) {
                    RegexpOp.LITERAL, RegexpOp.LITERAL_STRING, RegexpOp.CHAR_CLASS -> {
                        val scanInfo = analysis.start.charClassPrefix
                        if (usefulClassAnchor(scanInfo)) ClassAnchor(scanInfo, 0) else null
                    }

                    RegexpOp.CAPTURE, RegexpOp.NON_CAPTURE, RegexpOp.PLUS -> if (children.isEmpty()) null else children.first()!!.classAnchor
                    RegexpOp.REPEAT -> if (node.min > 0 && !children.isEmpty()) children.first()!!.classAnchor else null
                    RegexpOp.CONCAT -> {
                        var best: ClassAnchor? = null
                        var offset = 0
                        for (child in children) {
                            val candidate = child.classAnchor
                            if (candidate != null) {
                                val combinedOffset = AsciiWidthRange.addWidth(offset, candidate.offset)
                                if (combinedOffset >= 0
                                    && (best == null
                                            || classSize(candidate.scanInfo!!) < classSize(best.scanInfo!!))
                                ) {
                                    best = ClassAnchor(candidate.scanInfo, combinedOffset)
                                }
                            }
                            if (!child.width.isExactAscii) {
                                break
                            }
                            offset = AsciiWidthRange.addWidth(offset, child.width.minWidth)
                            if (offset < 0) {
                                break
                            }
                        }
                        best
                    }

                    else -> null
                }
            }

            private fun classSize(scanInfo: CharClassScanInfo): Int {
                return scanInfo.bitmap0.countOneBits() + scanInfo.bitmap1.countOneBits()
            }

            private fun usefulClassAnchor(scanInfo: CharClassScanInfo?): Boolean {
                if (scanInfo == null || !scanInfo.isAscii || classSize(scanInfo) == 0 || classSize(scanInfo) > 16) {
                    return false
                }
                // Keep the filter small and exclude classes containing the most common anchor characters.
                // Unlike the leading-class policy, this also admits compact ranges such as ASCII digits.
                val ranges = scanInfo.ranges
                var i = 0
                while (i < ranges.size) {
                    for (ch in ranges[i]..ranges[i + 1]) {
                        if (RarityOracle.byteRarity(ch) <= RarityOracle.POISONOUS_ANCHOR_MAX_RARITY) {
                            return false
                        }
                    }
                    i += 2
                }
                return true
            }

            private fun visitLiteral(node: Regexp): NodeAnalysis {
                val foldCase = (node.flags and ParseFlags.FOLD_CASE) != 0
                val validRune = Utils.isValidRune(node.rune)
                val lit = if (validRune) Utils.runeToString(node.rune) else null
                val width =
                    if (node.rune >= 0 && node.rune < 128 && !foldCase)
                        AsciiWidthRange.ONE
                    else
                        AsciiWidthRange.INVALID
                val prefix =
                    if (!validRune)
                        PrefixResult.NO_PREFIX
                    else
                        if (foldCase)
                            PrefixResult(lit!!.lowercase(), true)
                        else
                            PrefixResult(lit, false)
                val reqClass = literalCharClass(node.rune, node.flags)
                val ccPrefix: CharClassScanInfo? = CharClassScanInfo.fromCharClass(reqClass)
                val reqLit = if (validRune && !foldCase) lit else null
                return NodeAnalysis(
                    width,
                    StartFacets(prefix, ccPrefix, null, null, prefix, ccPrefix, null),
                    RejectFacets(
                        reqLit,
                        if (reqLit != null) RarityOracle.literalSelectivityScore(reqLit) else 0,
                        reqClass,
                        null,
                        null,
                        null
                    ),
                    reqClass
                )
            }

            private fun visitLiteralString(node: Regexp): NodeAnalysis {
                val foldCase = (node.flags and ParseFlags.FOLD_CASE) != 0
                val validRunes = areAllValidRunes(node.runes)
                val lit = if (validRunes) runesToString(node.runes) else null
                val width = AsciiWidthRangeWalker.literalStringWidth(node)
                val prefix =
                    if (!validRunes)
                        PrefixResult.NO_PREFIX
                    else
                        if (foldCase)
                            PrefixResult(lit!!.lowercase(), true)
                        else
                            PrefixResult(lit, false)
                // bestRequiredLiteral only feeds DisjointLiterals. A lone surrogate is safe on String inputs
                // because any String match contains the same UTF-16 code unit, while DisjointLiterals.create
                // omits branches with unpaired surrogates from its UTF-8 table (UTF-8 inputs never decode
                // surrogate code points). Adjacent high/low surrogate runes are still excluded here because a
                // String literal would coalesce them into a supplementary code point.
                val reqLit =
                    if (!foldCase && canRepresentAsCodePointString(node.runes) && node.runes.size >= 1)
                        (lit ?: runesToString(node.runes))
                    else
                        null
                val reqClass =
                    if (node.runes != null && node.runes.size > 0)
                        literalCharClass(node.runes[0], node.flags)
                    else
                        null
                val ccPrefix: CharClassScanInfo? =
                    if (reqClass != null) CharClassScanInfo.fromCharClass(reqClass) else null
                return NodeAnalysis(
                    width,
                    StartFacets(prefix, ccPrefix, null, null, prefix, ccPrefix, null),
                    RejectFacets(
                        reqLit,
                        if (reqLit != null) RarityOracle.literalSelectivityScore(reqLit) else 0,
                        reqClass,
                        null,
                        null,
                        null
                    ),
                    reqClass
                )
            }

            private fun visitCharClass(node: Regexp): NodeAnalysis {
                val width = AsciiWidthRangeWalker.characterClassWidth(node)
                val cc = node.charClass
                val ccPrefix: CharClassScanInfo? =
                    if (cc != null && !cc.isEmpty() && cc.numRunes() <= 0x80000)
                        CharClassScanInfo.fromCharClass(cc)
                    else
                        null
                val rep = simpleFoldClassRepresentative(cc)
                val prefix =
                    if (rep >= 0)
                        PrefixResult(Utils.runeToString(rep).lowercase(), true)
                    else
                        PrefixResult.NO_PREFIX
                return NodeAnalysis(
                    width,
                    StartFacets(prefix, ccPrefix, null, null, prefix, ccPrefix, null),
                    RejectFacets(null, 0, if (cc != null && !cc.isEmpty()) cc else null, null, null, null),
                    if (cc != null && !cc.isEmpty()) cc else null
                )
            }

            private fun visitNestedConcat(
                node: Regexp, children: MutableList<NodeAnalysis>, width: AsciiWidthRange
            ): NodeAnalysis {
                var leading = StartFacets.EMPTY
                for (index in children.indices) {
                    if (isLeadingZeroWidth(node.subs!![index]!!)) {
                        continue
                    }
                    leading = children[index].start
                    break
                }

                var bestRequiredLiteral: String? = null
                var bestRequiredScore = 0
                var bestRequiredClass: CharClass? = null
                var disjointRequiredLiterals: Array<String?>? = null
                for (child in children) {
                    val candidateLiteral = child.reject.bestRequiredLiteral
                    if (candidateLiteral != null && candidateLiteral.length >= 2) {
                        val candidateScore = child.reject.bestRequiredLiteralScore
                        if (bestRequiredLiteral == null || candidateScore > bestRequiredScore) {
                            bestRequiredLiteral = candidateLiteral
                            bestRequiredScore = candidateScore
                        }
                    }
                    val candidateClass = child.reject.bestRequiredClass
                    if (candidateClass != null
                        && (bestRequiredClass == null || moreSelective(candidateClass, bestRequiredClass))
                    ) {
                        bestRequiredClass = candidateClass
                    }
                    if (disjointRequiredLiterals == null && child.reject.disjointRequiredLiterals != null) {
                        disjointRequiredLiterals = child.reject.disjointRequiredLiterals
                    }
                }

                val start =
                    StartFacets(
                        leading.prefix,
                        leading.charClassPrefix,
                        null,
                        leading.startAcceleration,
                        leading.anchoredPrefix,
                        leading.anchoredCharClassPrefix,
                        null
                    )
                val reject =
                    RejectFacets(
                        bestRequiredLiteral,
                        bestRequiredScore,
                        bestRequiredClass,
                        disjointRequiredLiterals,
                        null,
                        null
                    )
                return NodeAnalysis(width, start, reject, null)
            }

            private fun visitAlternate(node: Regexp?, children: MutableList<NodeAnalysis>): NodeAnalysis {
                if (children.isEmpty()) {
                    return NodeAnalysis.UNKNOWN
                }
                val widths = ArrayList<AsciiWidthRange>(children.size)
                for (c in children) {
                    widths.add(c.width)
                }
                val width = AsciiWidthRangeWalker.alternateWidth(widths)

                // Literal alternation
                val litAlt = extractLiteralAlternation(node)

                // Required match class across branches
                val reqClass = unionRequiredCharClasses(children)

                // Disjoint required literals from branches
                val disjoint = combineDisjointRequiredLiterals(children)

                val start =
                    StartFacets(
                        PrefixResult.NO_PREFIX,
                        null,
                        null,
                        null,
                        PrefixResult.NO_PREFIX,
                        null,
                        litAlt
                    )
                val reject = RejectFacets(null, 0, reqClass, disjoint, null, null)

                return NodeAnalysis(width, start, reject, null)
            }

            private fun withRootCharClassPrefix(node: Regexp?, analysis: NodeAnalysis): NodeAnalysis {
                val start = analysis.start
                val completed =
                    StartFacets(
                        start.prefix,
                        extractCharClassPrefix(node),
                        start.fixedOffsetLiteral,
                        start.startAcceleration,
                        start.anchoredPrefix,
                        start.anchoredCharClassPrefix,
                        start.literalAlternation
                    )
                return NodeAnalysis(
                    analysis.width, completed, analysis.reject, analysis.atomicRequiredClass
                )
            }

            private fun unionRequiredCharClasses(children: MutableList<NodeAnalysis>): CharClass? {
                val union = CharClassBuilder()
                for (child in children) {
                    val required = child.atomicRequiredClass
                    if (required == null) {
                        return null
                    }
                    union.addCharClass(required)
                }
                val result = union.build()
                return if (result.isEmpty()) null else result
            }

            private fun visitQuest(children: MutableList<NodeAnalysis>): NodeAnalysis {
                if (children.isEmpty()) {
                    return NodeAnalysis.UNKNOWN
                }
                val width = AsciiWidthRangeWalker.optionalWidth(children.first()!!.width)
                return NodeAnalysis(width, StartFacets.EMPTY, RejectFacets.EMPTY, null)
            }

            private fun visitStar(): NodeAnalysis {
                return NodeAnalysis(
                    AsciiWidthRange.INVALID,
                    StartFacets.EMPTY,
                    RejectFacets.EMPTY,
                    null
                )
            }

            private fun visitPlus(children: MutableList<NodeAnalysis>): NodeAnalysis {
                if (children.isEmpty()) {
                    return NodeAnalysis.UNKNOWN
                }
                val child: NodeAnalysis = children.first()
                val width = AsciiWidthRange.INVALID
                val start =
                    StartFacets(
                        child.start.prefix,
                        child.start.charClassPrefix,
                        null,
                        null,
                        child.start.anchoredPrefix,
                        child.start.anchoredCharClassPrefix,
                        null
                    )
                val reject =
                    RejectFacets(
                        child.reject.bestRequiredLiteral,
                        child.reject.bestRequiredLiteralScore,
                        child.reject.bestRequiredClass,
                        null,
                        null,
                        null
                    )
                return NodeAnalysis(width, start, reject, child.atomicRequiredClass)
            }

            private fun visitRepeat(node: Regexp, children: MutableList<NodeAnalysis>): NodeAnalysis {
                if (children.isEmpty()) {
                    return NodeAnalysis.UNKNOWN
                }
                val child: NodeAnalysis = children.first()
                val width = AsciiWidthRangeWalker.repeatWidth(node, child.width)
                val start =
                    if (node.min > 0)
                        StartFacets(
                            child.start.prefix,
                            child.start.charClassPrefix,
                            null,
                            null,
                            child.start.anchoredPrefix,
                            child.start.anchoredCharClassPrefix,
                            null
                        )
                    else
                        StartFacets.EMPTY
                val reject =
                    if (node.min > 0)
                        RejectFacets(
                            child.reject.bestRequiredLiteral,
                            child.reject.bestRequiredLiteralScore,
                            child.reject.bestRequiredClass,
                            null,
                            null,
                            null
                        )
                    else
                        RejectFacets.EMPTY
                return NodeAnalysis(
                    width, start, reject, if (node.min > 0) child.atomicRequiredClass else null
                )
            }
        }
    }
}
