// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

import com.google.gson.JsonObject
import org.safere.exhaustive.SweepWorkers.LineWorker
import org.safere.exhaustive.SweepWorkers.ProgressReporter
import java.io.IOException
import java.lang.String
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.List
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import kotlin.Array
import kotlin.Boolean
import kotlin.Char
import kotlin.Int
import kotlin.Long
import kotlin.Throws
import kotlin.check
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashMap
import kotlin.collections.LinkedHashSet
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.contains
import kotlin.collections.dropLastWhile
import kotlin.collections.toTypedArray
import kotlin.io.println
import kotlin.io.use
import kotlin.math.min
import kotlin.plus
import kotlin.require
import kotlin.use

/** Offline differential sweep for repeated quantifiers over zero-width operands.  */
object ZeroWidthQuantifierDivergenceSweep {
    private const val FIND_LIMIT = 16
    private const val FIRST_UNKNOWN_LIMIT = 100
    private const val ACTIONABLE_SAMPLE_LIMIT = 100
    private const val MAX_QUANTIFIER_CHAIN_LENGTH = 4
    private const val DEFAULT_PROGRESS_INTERVAL: Long = 10000000
    private val CAPTURE_FIELD: Pattern = Pattern.compile("g(\\d+)=(?:null|-?\\d+-\\d+:[^;}]*+)")
    private val FIND_TRACE_FIELD: Pattern = Pattern.compile("(?:^|,)[^,:]*+:find\\d+=(?:true@\\d+-\\d+|false)")
    private val NON_REPLACEMENT_TRACE_FIELD: Pattern =
        Pattern.compile("(?:^|,)([^,:]*+:(?:matches|lookingAt|find\\d++))=([^,]*+)")
    private val DECOMPOSED_E_ACUTE_TRACE_FIELD: Pattern = Pattern.compile("(?:^|,)e\u0301:[^,]*")

    private val ZERO_WIDTH_ATOMS: MutableList<Atom> = List.of<Atom?>(
        atom("emptyCapturing", "()"),
        atom("emptyNonCapturing", "(?:)"),
        atom("beginLine", "^"),
        atom("endLine", "$"),
        atom("beginText", "\\A"),
        atom("endTextBeforeFinalTerminator", "\\Z"),
        atom("endText", "\\z"),
        atom("wordBoundary", "\\b"),
        atom("nonWordBoundary", "\\B"),
        atom("graphemeBoundary", "\\b{g}")
    )

    private val OPERANDS: MutableList<Operand> = buildOperands()

    private val WRAPPERS: MutableList<Wrapper> = List.of<Wrapper?>(
        wrapper("bare", "%s"),
        wrapper("capturing", "(%s)"),
        wrapper("nonCapturing", "(?:%s)"),
        wrapper("nestedGroups", "(?:(%s))")
    )

    private val QUANTIFIER_TOKENS: MutableList<Quantifier?> = buildQuantifierTokens()
    private val QUANTIFIER_CHAINS: MutableList<QuantifierChain> = buildQuantifierChains()

    private val CONTEXTS: MutableList<Context> = List.of<Context?>(
        context("bare", "%s"),
        context("capturedWhole", "(%s)"),
        context("nonCapturedWhole", "(?:%s)"),
        context("scopedComments", "(?x:%s)"),
        context("prefixLiteral", "a%s"),
        context("suffixLiteral", "%sa"),
        context("mixedLeadingLiteralAlternative", "(?:%s|a)."),
        context("mixedLeadingLiteralAlternativeReversed", "(?:a|%s)."),
        context("surroundingLiterals", "a%sb"),
        context("anchoredSurroundingLiterals", "^a%sb$"),
        context("embeddedMultilineAnchored", "(?m:^%s$)")
    )

    private val FLAG_MODES: MutableList<FlagMode?> = List.of<FlagMode?>(
        FlagMode("none", "", 0, ""),
        FlagMode("commentsFlag", "", Pattern.COMMENTS, " "),
        FlagMode("commentsFlagTab", "", Pattern.COMMENTS, "\t"),
        FlagMode("commentsFlagComment", "", Pattern.COMMENTS, "#q\n"),
        FlagMode("commentsEmbedded", "(?x)", 0, " "),
        FlagMode("commentsEmbeddedComment", "(?x)", 0, "#q\n")
    )

    private val CARTESIAN_CASES = cartesianCases()
    private val TARGETED_CASES: MutableList<CaseSpec> = buildTargetedCases()

    @Throws(IOException::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val options = ZeroWidthQuantifierDivergenceSweep.parseOptions(args)
        Files.createDirectories(options.sweep.outputDir)
        if (options.sweep.replayFile != null) {
            Files.deleteIfExists(options.sweep.jsonlPath())
        }
        options.printStartup()

        if (options.sweep.replayFile != null) {
            runReplay(options)
            return
        }

        val result = runSweep(options)

        println("checked=" + result.checked)
        println("generated=" + result.generated)
        println("totalCases=" + totalCases())
        println("divergences=" + result.divergences)
        println("actionableDivergences=" + result.summary!!.actionableCount())
        println("unknownDivergences=" + result.summary.count(DivergenceClass.UNKNOWN))
        println("threads=" + options.sweep.threads)
    }

    private fun parseOptions(args: Array<String?>): ZeroWidthSweepOptions {
        val sweepOptions: SweepOptions =
            SweepOptions.Companion.parse(
                args,
                Path.of("target", "exhaustive-reports", "zero-width-quantifier-sweep"),
                "zero-width-quantifier-divergences.jsonl",
                DEFAULT_PROGRESS_INTERVAL
            )
        return ZeroWidthSweepOptions(sweepOptions)
    }

    @Throws(IOException::class)
    private fun runSweep(options: ZeroWidthSweepOptions): ZeroWidthRunResult {
        val sweepOptions = options.sweep
        val selectedCaseCount = sweepOptions.totalChecks(totalCases())
        val workerSummaries: MutableList<ClassifiedDivergenceSummary<DivergenceClass?>?> =
            ArrayList<ClassifiedDivergenceSummary<DivergenceClass?>?>()
        for (i in 0..<sweepOptions.threads) {
            workerSummaries.add(null)
        }
        SweepRunState(sweepOptions, selectedCaseCount).use { runState ->
            runState.enableCompactLogs(
                "zero-width-quantifier",
                totalCases(),
                divergenceClassificationNames(),
                divergenceClassificationStatuses()
            )
            SweepWorkers.run(
                sweepOptions.threads,
                "zero-width-quantifier-sweep-",
                SweepWorkers.Worker { workerIndex: Int ->
                    val workerSummary =
                        newZeroWidthDivergenceSummary()
                    val worker = SweepState(runState, workerSummary, workerIndex)
                    worker.run()
                    worker.finish()
                    workerSummaries.set(workerIndex, workerSummary)
                })
            val summary = newZeroWidthDivergenceSummary()
            for (workerSummary in workerSummaries) {
                summary.merge(workerSummary)
            }
            return ZeroWidthRunResult(
                runState.checked.sum(), runState.generated, runState.divergences.sum(), summary
            )
        }
    }

    @Throws(IOException::class)
    private fun runReplay(options: ZeroWidthSweepOptions) {
        val sweepOptions = options.sweep
        val summary = newZeroWidthDivergenceSummary()
        val replayCaseIndex = AtomicLong()
        Files.newBufferedReader(sweepOptions.replayFile, StandardCharsets.UTF_8).use { reader ->
            SweepRunState(sweepOptions, 0).use { runState ->
                val generated =
                    SweepWorkers.runStreamingLines(
                        sweepOptions.threads,
                        "zero-width-quantifier-replay-",
                        reader,
                        LineWorker { line: String? ->
                            val spec = replayCaseOrNull(line)
                            if (spec == null) {
                                return@runStreamingLines
                            }
                            runState.checked.increment()
                            evaluateCase(runState, summary, spec, replayCaseIndex.getAndIncrement(), -1)
                        })
                runState.recordGenerated(generated)
                summary.writeReports(sweepOptions.outputDir)
                println("checked=" + runState.checked.sum())
                println("generated=" + runState.generated)
                println("divergences=" + runState.divergences.sum())
                println("actionableDivergences=" + summary.actionableCount())
                println("unknownDivergences=" + summary.count(DivergenceClass.UNKNOWN))
                println("threads=" + sweepOptions.threads)
                println("jsonl=" + sweepOptions.jsonlPath())
                val unknownCount = summary.count(DivergenceClass.UNKNOWN)
                check(!(summary.actionableCount() > 0 || unknownCount > 0)) {
                    ("replay found "
                            + summary.actionableCount()
                            + " actionable and "
                            + unknownCount
                            + " unknown behavioral divergences")
                }
            }
        }
    }

    private fun replayCaseOrNull(line: String?): CaseSpec? {
        val `object` = SweepJson.parseObjectOrNull(line)
        if (`object` == null) {
            return null
        }
        val caseObject = SweepJson.`object`(`object`, "case")
        return CaseSpec(
            Operand(
                SweepJson.string(caseObject, "operandLabel"),
                SweepJson.string(caseObject, "operandRegex")
            ),
            Wrapper(
                SweepJson.string(caseObject, "wrapperLabel"),
                SweepJson.string(caseObject, "wrapperTemplate")
            ),
            replayQuantifierChain(caseObject),
            Context(
                SweepJson.string(caseObject, "contextLabel"),
                SweepJson.string(caseObject, "contextTemplate")
            ),
            FlagMode(
                SweepJson.string(caseObject, "flagLabel"),
                SweepJson.string(caseObject, "flagPrefix"),
                SweepJson.integer(caseObject, "flags"),
                SweepJson.string(caseObject, "trivia")
            )
        )
    }

    private fun replayQuantifierChain(caseObject: JsonObject): QuantifierChain {
        if (caseObject.has("quantifierChainLabel")) {
            return QuantifierChain(
                SweepJson.string(caseObject, "quantifierChainLabel"),
                SweepJson.string(caseObject, "quantifierChain")
            )
        }
        return QuantifierChain(
            (SweepJson.string(caseObject, "firstQuantifierLabel")
                    + ","
                    + SweepJson.string(caseObject, "suffixQuantifierLabel")),
            SweepJson.string(caseObject, "firstQuantifier")
                    + SweepJson.string(caseObject, "suffixQuantifier")
        )
    }

    fun totalCases(): Long {
        return CARTESIAN_CASES + TARGETED_CASES.size
    }

    private fun cartesianCases(): Long {
        return (OPERANDS.size.toLong() * WRAPPERS.size
                * QUANTIFIER_CHAINS.size
                * CONTEXTS.size
                * FLAG_MODES.size)
    }

    private fun caseAt(index: Long): CaseSpec {
        var index = index
        if (index >= CARTESIAN_CASES) {
            return TARGETED_CASES.get((index - CARTESIAN_CASES).toInt())
        }
        val flagIndex = (index % FLAG_MODES.size).toInt()
        index /= FLAG_MODES.size.toLong()
        val contextIndex = (index % CONTEXTS.size).toInt()
        index /= CONTEXTS.size.toLong()
        val chainIndex = (index % QUANTIFIER_CHAINS.size).toInt()
        index /= QUANTIFIER_CHAINS.size.toLong()
        val wrapperIndex = (index % WRAPPERS.size).toInt()
        index /= WRAPPERS.size.toLong()
        val operandIndex = index.toInt()
        return CaseSpec(
            OPERANDS.get(operandIndex),
            WRAPPERS.get(wrapperIndex),
            QUANTIFIER_CHAINS.get(chainIndex),
            CONTEXTS.get(contextIndex),
            FLAG_MODES.get(flagIndex)
        )
    }

    fun containsGeneratedCaseForTesting(regex: String?, input: String?): Boolean {
        for (spec in TARGETED_CASES) {
            if (spec.regex() == regex && spec.inputs().contains(input)) {
                return true
            }
        }
        return false
    }

    fun containsCartesianCaseForTesting(
        operandRegex: String?,
        wrapperTemplate: String?,
        quantifierChain: String?,
        contextTemplate: String?,
        input: String?
    ): Boolean {
        val operand = findOperandByRegex(operandRegex)
        val wrapper = findWrapperByTemplate(wrapperTemplate)
        val chain = findQuantifierChainByText(quantifierChain)
        val context = findContextByTemplate(contextTemplate)
        val flags = FlagMode("none", "", 0, "")
        if (operand == null || wrapper == null || chain == null || context == null) {
            return false
        }
        return CaseSpec(operand, wrapper, chain, context, flags).inputs().contains(input)
    }

    fun containsQuantifierChainForTesting(quantifierChain: String?): Boolean {
        return findQuantifierChainByText(quantifierChain) != null
    }

    fun cartesianCaseIndexForTesting(
        operandRegex: String?, wrapperTemplate: String?, quantifierChain: String?, contextTemplate: String?
    ): Long {
        val operand = findOperandByRegex(operandRegex)
        val wrapper = findWrapperByTemplate(wrapperTemplate)
        val chain = findQuantifierChainByText(quantifierChain)
        val context = findContextByTemplate(contextTemplate)
        require(!(operand == null || wrapper == null || chain == null || context == null)) { "case is not in the Cartesian generator" }
        var index = OPERANDS.indexOf(operand).toLong()
        index = index * WRAPPERS.size + WRAPPERS.indexOf(wrapper)
        index = index * QUANTIFIER_CHAINS.size + QUANTIFIER_CHAINS.indexOf(chain)
        index = index * CONTEXTS.size + CONTEXTS.indexOf(context)
        return index * FLAG_MODES.size // The first flag mode is "none".
    }

    @JvmStatic
    fun totalCasesForTesting(): Long {
        return totalCases()
    }

    fun compactReplayJson(caseIndex: Long, classification: String?): String {
        val spec = caseAt(caseIndex)
        val `object` = SweepJson.`object`()
        `object`.addProperty("caseIndex", caseIndex)
        `object`.addProperty("classification", classification)
        `object`.add("case", Divergence.Companion.caseJson(spec))
        return SweepJson.toJson(`object`)
    }

    private fun findOperandByRegex(regex: String?): Operand? {
        for (operand in OPERANDS) {
            if (operand.regex == regex) {
                return operand
            }
        }
        return null
    }

    private fun findWrapperByTemplate(template: String?): Wrapper? {
        for (wrapper in WRAPPERS) {
            if (wrapper.template == template) {
                return wrapper
            }
        }
        return null
    }

    private fun findQuantifierChainByText(text: String?): QuantifierChain? {
        for (chain in QUANTIFIER_CHAINS) {
            if (chain.text == text) {
                return chain
            }
        }
        return null
    }

    private fun findContextByTemplate(template: String?): Context? {
        for (context in CONTEXTS) {
            if (context.template == template) {
                return context
            }
        }
        return null
    }

    private fun bucketFor(
        spec: CaseSpec, jdk: RegexSweep.Outcome, safere: RegexSweep.Outcome
    ): String {
        val kind = if (jdk.accepted != safere.accepted) "compile" else "behavior"
        return String.join(
            "|",
            "kind=" + kind,
            "direction=" + direction(jdk, safere),
            "operand=" + spec.operand.label,
            "wrapper=" + spec.wrapper!!.label,
            "chain=" + spec.quantifierChain!!.label,
            "context=" + spec.context!!.label,
            "flags=" + spec.flagMode!!.label
        )
    }

    private fun direction(jdk: RegexSweep.Outcome, safere: RegexSweep.Outcome): kotlin.String {
        if (jdk.accepted && !safere.accepted) {
            return "jdk-accepts"
        }
        if (!jdk.accepted && safere.accepted) {
            return "safere-accepts"
        }
        return "matches"
    }

    private fun buildOperands(): MutableList<Operand> {
        val operands: MutableList<Operand?> = ArrayList<Operand?>()
        for (atom in ZERO_WIDTH_ATOMS) {
            operands.add(operand("atom:" + atom.label, atom.regex))
        }
        for (left in ZERO_WIDTH_ATOMS) {
            for (right in ZERO_WIDTH_ATOMS) {
                operands.add(
                    operand("concat:" + left.label + "+" + right.label, left.regex + right.regex)
                )
            }
        }
        for (left in ZERO_WIDTH_ATOMS) {
            for (right in ZERO_WIDTH_ATOMS) {
                operands.add(
                    operand(
                        "alternate:" + left.label + "|" + right.label,
                        "(?:" + left.regex + "|" + right.regex + ")"
                    )
                )
            }
        }
        return List.copyOf<Operand?>(operands)
    }

    private fun buildTargetedCases(): MutableList<CaseSpec> {
        return List.of<CaseSpec?>(
            CaseSpec(
                operand("targeted:capturedGraphemeBoundary", "\\b{g}"),
                wrapper("capturing", "(%s)"),
                quantifierChain(quantifier("star", "*"), quantifier("plus", "+")),
                context("capturedThenOptionalLiteral", "(%s)a?"),
                FlagMode("none", "", 0, "")
            ),
            CaseSpec(
                operand("targeted:deepEmptyCapturing", nestedNonCapturingGroups("()", 12000)),
                wrapper("bare", "%s"),
                quantifierChain(quantifier("star", "*"), quantifier("plus", "+")),
                context("suffixLiteral", "%sa"),
                FlagMode("none", "", 0, "")
            ),
            CaseSpec(
                operand(
                    "targeted:deepRepeatedBoundaryAlternation",
                    nestedAlternation("\\b{g}{2}", "a", 12000)
                ),
                wrapper("bare", "%s"),
                quantifierChain(quantifier("repeatOne", "{1}"), quantifier("repeatOne", "{1}")),
                context("bare", "%s"),
                FlagMode("none", "", 0, "")
            )
        )
    }

    private fun nestedNonCapturingGroups(inner: kotlin.String, depth: Int): kotlin.String {
        val regex = StringBuilder(inner.length + 4 * depth)
        regex.append("(?:".repeat(depth))
        regex.append(inner)
        regex.append(")".repeat(depth))
        return regex.toString()
    }

    private fun nestedAlternation(initial: kotlin.String?, otherBranch: kotlin.String?, depth: Int): kotlin.String? {
        var regex = initial
        for (i in 0..<depth) {
            regex = "(?:" + regex + "|" + otherBranch + ")"
        }
        return regex
    }

    private fun buildQuantifierTokens(): MutableList<Quantifier?> {
        val quantifiers: MutableList<Quantifier?> = ArrayList<Quantifier?>()
        val bases =
            List.of<Quantifier?>(
                quantifier("star", "*"),
                quantifier("plus", "+"),
                quantifier("question", "?"),
                quantifier("repeatZero", "{0}"),
                quantifier("repeatOne", "{1}"),
                quantifier("repeatTwo", "{2}"),
                quantifier("repeatRange", "{0,2}")
            )
        for (base in bases) {
            quantifiers.add(base)
            quantifiers.add(quantifier(base.label + "Reluctant", base.text + "?"))
            quantifiers.add(quantifier(base.label + "Possessive", base.text + "+"))
        }
        return List.copyOf<Quantifier?>(quantifiers)
    }

    private fun buildQuantifierChains(): MutableList<QuantifierChain> {
        val chains: MutableMap<kotlin.String?, QuantifierChain?> = LinkedHashMap<kotlin.String?, QuantifierChain?>()
        appendQuantifierChains(chains, ArrayList<Quantifier>(), 0)
        return List.copyOf<QuantifierChain?>(chains.values)
    }

    private fun appendQuantifierChains(
        chains: MutableMap<kotlin.String?, QuantifierChain?>, current: MutableList<Quantifier>, depth: Int
    ) {
        if (depth > 0) {
            val chain = quantifierChain(current)
            chains.putIfAbsent(chain.text, chain)
        }
        if (depth == MAX_QUANTIFIER_CHAIN_LENGTH) {
            return
        }
        for (token in QUANTIFIER_TOKENS) {
            current.add(token!!)
            appendQuantifierChains(chains, current, depth + 1)
            current.removeLast()
        }
    }

    private fun atom(label: kotlin.String?, regex: kotlin.String?): Atom {
        return Atom(label, regex)
    }

    private fun operand(label: kotlin.String, regex: kotlin.String?): Operand {
        return Operand(label, regex)
    }

    private fun wrapper(label: kotlin.String?, template: kotlin.String?): Wrapper {
        return Wrapper(label, template)
    }

    private fun quantifier(label: kotlin.String?, text: kotlin.String?): Quantifier {
        return Quantifier(label, text)
    }

    private fun quantifierChain(vararg quantifiers: Quantifier?): QuantifierChain {
        return quantifierChain(List.of<Quantifier?>(*quantifiers))
    }

    private fun quantifierChain(quantifiers: MutableList<Quantifier>): QuantifierChain {
        val label = StringBuilder()
        val text = StringBuilder()
        for (quantifier in quantifiers) {
            if (!label.isEmpty()) {
                label.append(',')
            }
            label.append(quantifier.label)
            text.append(quantifier.text)
        }
        return QuantifierChain(label.toString(), text.toString())
    }

    private fun context(label: kotlin.String?, template: kotlin.String?): Context {
        return Context(label, template)
    }

    private fun newZeroWidthDivergenceSummary(): ClassifiedDivergenceSummary<DivergenceClass?> {
        return ClassifiedDivergenceSummary<DivergenceClass?>(
            DivergenceClass::class.java,
            DivergenceClass.UNKNOWN,
            "zero-width-quantifier",
            FIRST_UNKNOWN_LIMIT,
            ACTIONABLE_SAMPLE_LIMIT
        )
    }

    @JvmStatic
    fun divergenceClassificationNames(): MutableList<kotlin.String?> {
        return Arrays.stream<DivergenceClass?>(DivergenceClass.entries.toTypedArray())
            .map<kotlin.String?> { obj: DivergenceClass? -> obj!!.name }.toList()
    }

    @JvmStatic
    fun divergenceClassificationStatuses(): MutableList<DivergenceStatus?> {
        return Arrays.stream<DivergenceClass?>(DivergenceClass.entries.toTypedArray())
            .map<DivergenceStatus?> { obj: DivergenceClass? -> obj!!.status() }.toList()
    }

    fun divergenceClassificationStatusMap(): MutableMap<kotlin.String?, DivergenceStatus?> {
        val statuses: MutableMap<kotlin.String?, DivergenceStatus?> = LinkedHashMap<kotlin.String?, DivergenceStatus?>()
        for (classification in DivergenceClass.entries) {
            statuses.put(classification.name, classification.status())
        }
        return statuses
    }

    fun auditClassificationName(caseIndex: Long): kotlin.String? {
        val classification = auditClassification(caseAt(caseIndex))
        return if (classification == null) null else classification.name
    }

    private fun classificationId(classification: DivergenceClass): Int {
        return when (classification) {
            DivergenceClass.STACK_OVERFLOW -> 0
            DivergenceClass.RELUCTANT_QUANTIFIER_MODIFIER -> 1
            DivergenceClass.COMMENTS_MODE_GRAPHEME_QUANTIFIER_TRIVIA -> 2
            DivergenceClass.INVALID_QUANTIFIER_CHAIN_ACCEPTED -> 3
            DivergenceClass.ZERO_WIDTH_POSSESSIVE_QUANTIFIER_REJECTED -> 4
            DivergenceClass.ZERO_WIDTH_POSSESSIVE_CAPTURE_RETENTION -> 5
            DivergenceClass.POSSESSIVE_QUANTIFIER_UNSUPPORTED -> 6
            DivergenceClass.GRAPHEME_BOUNDARY_ALTERNATIVE_FIND_CURSOR -> 7
            DivergenceClass.REPEATED_GRAPHEME_BOUNDARY_COMPOSITION -> 8
            DivergenceClass.GRAPHEME_BOUNDARY_ALTERNATIVE_GRAPHEME_MODEL -> 9
            DivergenceClass.GRAPHEME_BOUNDARY_CAPTURE_GRAPHEME_MODEL -> 10
            DivergenceClass.ASCII_WORD_BOUNDARY_COMBINING_MARK -> 11
            DivergenceClass.FAILED_PATH_CAPTURE_LEAKAGE -> 12
            DivergenceClass.UNKNOWN -> 13
        }
    }

    private fun evaluateCase(
        runState: SweepRunState,
        summary: ClassifiedDivergenceSummary<DivergenceClass?>,
        spec: CaseSpec,
        caseIndex: Long,
        workerIndex: Int
    ) {
        val jdk =
            RegexSweep.jdkTraceOutcome(
                spec.regex(), spec.flagMode!!.flags, spec.inputs(), FIND_LIMIT
            )
        val safere =
            RegexSweep.safeReTraceOutcome(
                spec.regex(), spec.flagMode.flags, spec.inputs(), FIND_LIMIT
            )
        val classification = classifyEvaluatedDivergence(spec, jdk, safere)
        if (classification == null) {
            return
        }
        val bucketName = bucketFor(spec, jdk, safere)
        recordDivergence(
            runState, summary, caseIndex, spec, jdk, safere, bucketName, classification, workerIndex
        )
    }

    private fun auditClassification(spec: CaseSpec): DivergenceClass? {
        val jdk =
            RegexSweep.jdkTraceOutcome(
                spec.regex(), spec.flagMode!!.flags, spec.inputs(), FIND_LIMIT
            )
        val safere =
            RegexSweep.safeReTraceOutcome(
                spec.regex(), spec.flagMode.flags, spec.inputs(), FIND_LIMIT
            )
        return classifyEvaluatedDivergence(spec, jdk, safere)
    }

    private fun classifyEvaluatedDivergence(
        spec: CaseSpec, jdk: RegexSweep.Outcome, safere: RegexSweep.Outcome
    ): DivergenceClass? {
        if (isStackSafetySentinel(spec)) {
            if (safere.error.contains("StackOverflowError")) {
                return DivergenceClass.STACK_OVERFLOW
            }
            return null
        }
        if (RegexSweep.semanticallyEqual(jdk, safere)) {
            return null
        }
        return classifyDivergence(spec, jdk, safere)
    }

    private fun recordDivergence(
        runState: SweepRunState,
        summary: ClassifiedDivergenceSummary<DivergenceClass?>,
        caseIndex: Long,
        spec: CaseSpec?,
        jdk: RegexSweep.Outcome?,
        safere: RegexSweep.Outcome?,
        bucketName: kotlin.String?,
        classification: DivergenceClass,
        workerIndex: Int
    ) {
        if (workerIndex >= 0) {
            runState.recordCompactDivergence(workerIndex, caseIndex, classificationId(classification))
            summary.record(classification, caseIndex, null)
            return
        }
        val divergence =
            Divergence(caseIndex, spec, jdk, safere, bucketName, classification)
        runState.recordDivergence()
        val json = divergence.toJson()
        summary.record(classification, caseIndex, json)
        if (classification.actionable()) {
            runState.appendJsonl(json)
        }
    }

    private fun classifyDivergence(
        spec: CaseSpec, jdk: RegexSweep.Outcome, safere: RegexSweep.Outcome
    ): DivergenceClass {
        if (isReluctantQuantifierModifierDivergence(spec, jdk, safere)) {
            return DivergenceClass.RELUCTANT_QUANTIFIER_MODIFIER
        }
        if (isCommentsModeGraphemeQuantifierTriviaDivergence(spec, jdk, safere)) {
            return DivergenceClass.COMMENTS_MODE_GRAPHEME_QUANTIFIER_TRIVIA
        }
        if (isInvalidQuantifierChainAccepted(jdk, safere)) {
            return DivergenceClass.INVALID_QUANTIFIER_CHAIN_ACCEPTED
        }
        if (isZeroWidthPossessiveQuantifierRejected(spec, jdk, safere)) {
            return DivergenceClass.ZERO_WIDTH_POSSESSIVE_QUANTIFIER_REJECTED
        }
        if (isPossessiveQuantifierUnsupported(spec, jdk, safere)) {
            return DivergenceClass.POSSESSIVE_QUANTIFIER_UNSUPPORTED
        }
        if (isKnownGraphemeBoundaryAlternativeFindCursor(spec, jdk, safere)) {
            return DivergenceClass.GRAPHEME_BOUNDARY_ALTERNATIVE_FIND_CURSOR
        }
        if (isKnownRepeatedGraphemeBoundaryComposition(spec, jdk, safere)) {
            return DivergenceClass.REPEATED_GRAPHEME_BOUNDARY_COMPOSITION
        }
        if (isKnownGraphemeBoundaryAlternativeGraphemeModel(spec, jdk, safere)) {
            return DivergenceClass.GRAPHEME_BOUNDARY_ALTERNATIVE_GRAPHEME_MODEL
        }
        if (isKnownGraphemeBoundaryCaptureGraphemeModel(spec, jdk, safere)) {
            return DivergenceClass.GRAPHEME_BOUNDARY_CAPTURE_GRAPHEME_MODEL
        }
        if (isKnownAsciiWordBoundaryCombiningMarkDivergence(spec, jdk, safere)) {
            return DivergenceClass.ASCII_WORD_BOUNDARY_COMBINING_MARK
        }
        if (isZeroWidthPossessiveCaptureRetentionDivergence(spec, jdk, safere)) {
            return DivergenceClass.ZERO_WIDTH_POSSESSIVE_CAPTURE_RETENTION
        }
        if (isKnownFailedPathCaptureLeakageDivergence(spec, jdk, safere)) {
            return DivergenceClass.FAILED_PATH_CAPTURE_LEAKAGE
        }
        return DivergenceClass.UNKNOWN
    }

    private fun isZeroWidthPossessiveQuantifierRejected(
        spec: CaseSpec, jdk: RegexSweep.Outcome, safere: RegexSweep.Outcome
    ): Boolean {
        return jdk.accepted
                && !safere.accepted && containsPossessiveQuantifierModifier(spec.quantifierChain!!.text)
    }

    private fun isPossessiveQuantifierUnsupported(
        spec: CaseSpec, jdk: RegexSweep.Outcome, safere: RegexSweep.Outcome
    ): Boolean {
        return jdk.accepted
                && !safere.accepted && containsPossessiveQuantifierModifier(spec.quantifierChain!!.text)
    }

    private fun isZeroWidthPossessiveCaptureRetentionDivergence(
        spec: CaseSpec, jdk: RegexSweep.Outcome, safere: RegexSweep.Outcome
    ): Boolean {
        return jdk.accepted
                && safere.accepted
                && containsPossessiveQuantifierModifier(spec.quantifierChain!!.text)
                && isZeroWidthPossessiveCaptureRetentionSpec(spec)
                && ZeroWidthQuantifierDivergenceSweep.isDirectZeroWidthPossessiveCaptureRetentionContext(spec.context!!)
                && normalizeCaptureFields(jdk.trace) == normalizeCaptureFields(safere.trace)
    }

    private fun isZeroWidthPossessiveCaptureRetentionSpec(spec: CaseSpec): Boolean {
        if (isCapturedConditionalZeroWidthAssertion(spec)) {
            return true
        }
        return when (spec.operand.label) {
            "atom:emptyCapturing", "atom:emptyNonCapturing", "alternate:emptyCapturing|emptyCapturing", "alternate:emptyCapturing|emptyNonCapturing", "alternate:emptyNonCapturing|emptyCapturing", "alternate:emptyNonCapturing|emptyNonCapturing" -> true
            else -> false
        }
    }

    private fun isCapturedConditionalZeroWidthAssertion(spec: CaseSpec): Boolean {
        return (spec.wrapper!!.label == "capturing"
                || spec.wrapper.label == "nestedGroups")
                && isConditionalZeroWidthOperand(spec.operand)
    }

    private fun isDirectZeroWidthPossessiveCaptureRetentionContext(context: Context): Boolean {
        return when (context.label) {
            "bare", "capturedWhole", "embeddedMultilineAnchored", "surroundingLiterals" -> true
            else -> false
        }
    }

    private fun containsPossessiveQuantifierModifier(quantifierChain: kotlin.String): Boolean {
        for (i in 1..<quantifierChain.length) {
            if (quantifierChain.get(i) == '+'
                && isQuantifierModifierPrefix(quantifierChain.get(i - 1))
            ) {
                return true
            }
        }
        return false
    }

    private fun isQuantifierModifierPrefix(c: Char): Boolean {
        return c == '*' || c == '+' || c == '?' || c == '}'
    }

    private fun isReluctantQuantifierModifierDivergence(
        spec: CaseSpec, jdk: RegexSweep.Outcome, safere: RegexSweep.Outcome
    ): Boolean {
        return jdk.accepted
                && !safere.accepted && containsReluctantQuantifierModifier(spec.quantifierChain!!.text)
    }

    private fun containsReluctantQuantifierModifier(quantifierChain: kotlin.String): Boolean {
        for (i in 1..<quantifierChain.length) {
            if (quantifierChain.get(i) == '?'
                && isQuantifierModifierPrefix(quantifierChain.get(i - 1))
            ) {
                return true
            }
        }
        return false
    }

    private fun isCommentsModeGraphemeQuantifierTriviaDivergence(
        spec: CaseSpec, jdk: RegexSweep.Outcome, safere: RegexSweep.Outcome
    ): Boolean {
        return jdk.accepted
                && !safere.accepted && spec.flagMode!!.label!!.contains("comments")
                && spec.operand.label.contains("graphemeBoundary")
                && safere.error == "missing argument to repetition operator"
    }

    private fun isInvalidQuantifierChainAccepted(
        jdk: RegexSweep.Outcome, safere: RegexSweep.Outcome
    ): Boolean {
        return !jdk.accepted && safere.accepted
                && (jdk.error.startsWith("Dangling meta character")
                || jdk.error == "Illegal repetition")
    }

    private fun isKnownGraphemeBoundaryAlternativeFindCursor(
        spec: CaseSpec, jdk: RegexSweep.Outcome, safere: RegexSweep.Outcome
    ): Boolean {
        return jdk.accepted
                && safere.accepted
                && spec.operand.label == "atom:graphemeBoundary"
                && spec.wrapper!!.label == "bare"
                && (spec.context!!.label == "mixedLeadingLiteralAlternative"
                || spec.context.label == "mixedLeadingLiteralAlternativeReversed")
                && isFindOnlyTraceDifference(jdk.trace, safere.trace)
    }

    @JvmStatic
    fun isKnownGraphemeBoundaryAlternativeFindCursorForTesting(
        jdkTrace: kotlin.String, safereTrace: kotlin.String
    ): Boolean {
        return isFindOnlyTraceDifference(jdkTrace, safereTrace)
    }

    private fun isKnownRepeatedGraphemeBoundaryComposition(
        spec: CaseSpec, jdk: RegexSweep.Outcome, safere: RegexSweep.Outcome
    ): Boolean {
        return jdk.accepted
                && safere.accepted
                && containsExplicitGraphemeBoundary(spec.operand)
                && isRepeatedGraphemeBoundaryCompositionTraceDifference(jdk.trace, safere.trace)
    }

    private fun isKnownGraphemeBoundaryAlternativeGraphemeModel(
        spec: CaseSpec, jdk: RegexSweep.Outcome, safere: RegexSweep.Outcome
    ): Boolean {
        return jdk.accepted
                && safere.accepted
                && containsExplicitGraphemeBoundary(spec.operand)
                && isGraphemeSensitiveAlternativeTraceDifference(jdk.trace, safere.trace)
    }

    private fun isKnownGraphemeBoundaryCaptureGraphemeModel(
        spec: CaseSpec, jdk: RegexSweep.Outcome, safere: RegexSweep.Outcome
    ): Boolean {
        return jdk.accepted
                && safere.accepted
                && containsExplicitGraphemeBoundary(spec.operand)
                && hasGraphemeSensitiveTrace(jdk.trace, safere.trace)
                && normalizeCaptureFields(jdk.trace) == normalizeCaptureFields(safere.trace)
    }

    private fun containsExplicitGraphemeBoundary(operand: Operand): Boolean {
        return operand.label.contains("graphemeBoundary")
                || operand.regex!!.contains("\\b{g}")
                || operand.regex.contains("\\\\b{g}")
    }

    private fun hasGraphemeSensitiveTrace(jdkTrace: kotlin.String, safereTrace: kotlin.String): Boolean {
        return jdkTrace.contains("\\uD83D")
                || safereTrace.contains("\\uD83D")
                || jdkTrace.indexOf('\u200D') >= 0 || safereTrace.indexOf('\u200D') >= 0
    }

    @JvmStatic
    fun isRepeatedGraphemeBoundaryCompositionTraceDifferenceForTesting(
        jdkTrace: kotlin.String, safereTrace: kotlin.String
    ): Boolean {
        return isRepeatedGraphemeBoundaryCompositionTraceDifference(jdkTrace, safereTrace)
    }

    private fun isRepeatedGraphemeBoundaryCompositionTraceDifference(
        jdkTrace: kotlin.String, safereTrace: kotlin.String
    ): Boolean {
        if (jdkTrace == safereTrace) {
            return false
        }
        return isRepeatedGraphemeBoundaryCompositionTraceDifference(
            nonReplacementTraceFields(
                ZeroWidthQuantifierDivergenceSweep.normalizeCaptureFields(
                    removeDecomposedEAcuteFields(jdkTrace)!!
                )
            ),
            nonReplacementTraceFields(
                ZeroWidthQuantifierDivergenceSweep.normalizeCaptureFields(removeDecomposedEAcuteFields(safereTrace)!!)
            )
        )
    }

    private fun isGraphemeSensitiveAlternativeTraceDifference(
        jdkTrace: kotlin.String, safereTrace: kotlin.String
    ): Boolean {
        if (jdkTrace == safereTrace) {
            return false
        }
        val jdkOperations =
            nonReplacementTraceFields(
                ZeroWidthQuantifierDivergenceSweep.normalizeCaptureFields(
                    removeDecomposedEAcuteFields(jdkTrace)!!
                )
            )
        val safeReOperations =
            nonReplacementTraceFields(
                ZeroWidthQuantifierDivergenceSweep.normalizeCaptureFields(removeDecomposedEAcuteFields(safereTrace)!!)
            )
        val nonSensitiveJdkOperations: MutableMap<kotlin.String?, kotlin.String?> =
            LinkedHashMap<kotlin.String?, kotlin.String?>()
        val nonSensitiveSafeReOperations: MutableMap<kotlin.String?, kotlin.String?> =
            LinkedHashMap<kotlin.String?, kotlin.String?>()
        var sawSensitiveDifference = false
        for (entry in jdkOperations.entries) {
            val safeReValue = safeReOperations.get(entry.key)
            if (ZeroWidthQuantifierDivergenceSweep.isGraphemeSensitiveTraceKey(entry.key!!)) {
                if (entry.value != safeReValue) {
                    sawSensitiveDifference = true
                }
            } else {
                nonSensitiveJdkOperations.put(entry.key, entry.value)
            }
        }
        for (entry in safeReOperations.entries) {
            if (ZeroWidthQuantifierDivergenceSweep.isGraphemeSensitiveTraceKey(entry.key!!)) {
                if (entry.value != jdkOperations.get(entry.key)) {
                    sawSensitiveDifference = true
                }
            } else {
                nonSensitiveSafeReOperations.put(entry.key, entry.value)
            }
        }
        return sawSensitiveDifference
                && (nonSensitiveJdkOperations == nonSensitiveSafeReOperations
                || isRepeatedGraphemeBoundaryCompositionTraceDifference(
            nonSensitiveJdkOperations, nonSensitiveSafeReOperations
        ))
    }

    private fun isGraphemeSensitiveTraceKey(key: kotlin.String): Boolean {
        val colon = key.lastIndexOf(':')
        val input = if (colon >= 0) key.substring(0, colon) else key
        return input.contains("\\uD83D") || input.indexOf('\u200D') >= 0
    }

    private fun isRepeatedGraphemeBoundaryCompositionTraceDifference(
        jdkOperations: MutableMap<kotlin.String?, kotlin.String?>,
        safeReOperations: MutableMap<kotlin.String?, kotlin.String?>
    ): Boolean {
        var sawCompositionalExtra = false
        for (entry in jdkOperations.entries) {
            if (ZeroWidthQuantifierDivergenceSweep.isFindTraceKey(entry.key!!)) {
                continue
            }
            val safeReValue = safeReOperations.get(entry.key)
            if (safeReValue == null) {
                return false
            }
            if (entry.value!!.startsWith("true@")) {
                if (entry.value != safeReValue) {
                    return false
                }
            } else if (safeReValue.startsWith("true@")) {
                sawCompositionalExtra = true
            } else if (entry.value != safeReValue) {
                return false
            }
        }
        val jdkFindSuccesses = findSuccessesByInput(jdkOperations)
        val safeReFindSuccesses = findSuccessesByInput(safeReOperations)
        for (entry in jdkFindSuccesses.entries) {
            val safeReSuccesses = safeReFindSuccesses.get(entry.key)
            if (safeReSuccesses == null || !safeReSuccesses.containsAll(entry.value!!)) {
                return false
            }
            if (safeReSuccesses.size > entry.value!!.size) {
                sawCompositionalExtra = true
            }
        }
        for (entry in safeReFindSuccesses.entries) {
            if (!jdkFindSuccesses.containsKey(entry.key) && !entry.value!!.isEmpty()) {
                sawCompositionalExtra = true
            }
        }
        for (entry in safeReOperations.entries) {
            if (ZeroWidthQuantifierDivergenceSweep.isFindTraceKey(entry.key!!)) {
                continue
            }
            if (!jdkOperations.containsKey(entry.key)) {
                if (entry.value!!.startsWith("true@")) {
                    sawCompositionalExtra = true
                } else if (!entry.key!!.contains(":find")) {
                    return false
                }
            }
        }
        return sawCompositionalExtra
    }

    private fun isFindTraceKey(key: kotlin.String): Boolean {
        val colon = key.lastIndexOf(':')
        return colon >= 0 && key.startsWith("find", colon + 1)
    }

    private fun findSuccessesByInput(fields: MutableMap<kotlin.String?, kotlin.String?>): MutableMap<kotlin.String?, MutableSet<kotlin.String?>?> {
        val successes: MutableMap<kotlin.String?, MutableSet<kotlin.String?>?> =
            LinkedHashMap<kotlin.String?, MutableSet<kotlin.String?>?>()
        for (entry in fields.entries) {
            if (!ZeroWidthQuantifierDivergenceSweep.isFindTraceKey(entry.key!!) || !entry.value!!.startsWith("true@")) {
                continue
            }
            val colon = entry.key!!.lastIndexOf(':')
            successes
                .computeIfAbsent(
                    entry.key!!.substring(
                        0,
                        colon
                    )
                ) { ignored: kotlin.String? -> LinkedHashSet<kotlin.String?>() }!!
                .add(entry.value)
        }
        return successes
    }

    private fun nonReplacementTraceFields(trace: kotlin.String): MutableMap<kotlin.String?, kotlin.String?> {
        val fields: MutableMap<kotlin.String?, kotlin.String?> = LinkedHashMap<kotlin.String?, kotlin.String?>()
        val matcher = NON_REPLACEMENT_TRACE_FIELD.matcher(trace)
        while (matcher.find()) {
            fields.put(matcher.group(1), matcher.group(2))
        }
        return fields
    }

    private fun isFindOnlyTraceDifference(jdkTrace: kotlin.String, safereTrace: kotlin.String): Boolean {
        return jdkTrace != safereTrace && (FIND_TRACE_FIELD
            .matcher(jdkTrace)
            .replaceAll("")
                == FIND_TRACE_FIELD.matcher(safereTrace).replaceAll(""))
    }

    private fun isKnownAsciiWordBoundaryCombiningMarkDivergence(
        spec: CaseSpec, jdk: RegexSweep.Outcome, safere: RegexSweep.Outcome
    ): Boolean {
        return jdk.accepted
                && safere.accepted
                && containsAsciiWordBoundary(spec.operand)
                && (isDecomposedEAcuteOnlyTraceDifference(jdk.trace, safere.trace)
                || isDecomposedEAcuteOnlyTraceDifference(
            normalizeCaptureFields(jdk.trace), normalizeCaptureFields(safere.trace)
        ))
    }

    private fun containsAsciiWordBoundary(operand: Operand): Boolean {
        return operand.label.contains("wordBoundary") || operand.label.contains("nonWordBoundary")
    }

    @JvmStatic
    fun isDecomposedEAcuteOnlyTraceDifferenceForTesting(
        jdkTrace: kotlin.String, safereTrace: kotlin.String
    ): Boolean {
        return isDecomposedEAcuteOnlyTraceDifference(jdkTrace, safereTrace)
    }

    private fun isDecomposedEAcuteOnlyTraceDifference(
        jdkTrace: kotlin.String, safereTrace: kotlin.String
    ): Boolean {
        return jdkTrace != safereTrace && removeDecomposedEAcuteFields(jdkTrace) == removeDecomposedEAcuteFields(
            safereTrace
        )
    }

    private fun removeDecomposedEAcuteFields(trace: kotlin.String): kotlin.String? {
        return DECOMPOSED_E_ACUTE_TRACE_FIELD.matcher(trace).replaceAll("")
    }

    private fun isKnownFailedPathCaptureLeakageDivergence(
        spec: CaseSpec, jdk: RegexSweep.Outcome, safere: RegexSweep.Outcome
    ): Boolean {
        val knownShape =
            isKnownFailedPathCaptureLeakageOperand(spec)
                    || isPossessiveZeroWidthFailedPathCaptureLeakage(spec)
        if (!knownShape) {
            return false
        }
        if (jdk.accepted != safere.accepted || jdk.error != safere.error) {
            return false
        }
        if (normalizeCaptureFields(jdk.trace) != normalizeCaptureFields(safere.trace)) {
            return false
        }
        return true
    }

    private fun isPossessiveZeroWidthFailedPathCaptureLeakage(spec: CaseSpec): Boolean {
        return containsPossessiveQuantifierModifier(spec.quantifierChain!!.text)
                && ZeroWidthQuantifierDivergenceSweep.isFailedPathCaptureLeakageContext(spec.context!!)
                && isEmptyCaptureLeakageShape(spec)
    }

    private fun isFailedPathCaptureLeakageContext(context: Context): Boolean {
        return when (context.label) {
            "mixedLeadingLiteralAlternative", "mixedLeadingLiteralAlternativeReversed", "suffixLiteral" -> true
            else -> false
        }
    }

    private fun isEmptyCaptureLeakageShape(spec: CaseSpec): Boolean {
        return spec.wrapper!!.label == "capturing"
                || spec.wrapper.label == "nestedGroups"
                || spec.operand.label.contains("emptyCapturing")
    }

    private fun isConditionalZeroWidthOperand(operand: Operand): Boolean {
        return when (operand.label) {
            "atom:beginLine", "atom:endLine", "atom:beginText", "atom:endTextBeforeFinalTerminator", "atom:endText", "atom:wordBoundary", "atom:nonWordBoundary", "atom:graphemeBoundary" -> true
            else -> false
        }
    }

    private fun isKnownFailedPathCaptureLeakageOperand(spec: CaseSpec): Boolean {
        val operand = spec.operand
        return isConditionalZeroWidthOperand(operand)
                || isEmptyCaptureThenConditionalZeroWidthOperand(operand)
                || isConditionalZeroWidthThenEmptyCaptureOperand(operand)
                || isEmptyNonCaptureThenConditionalZeroWidthOperand(operand)
                || isConditionalZeroWidthThenEmptyNonCaptureOperand(operand)
                || isConcatOfConditionalZeroWidthOperands(operand)
                || operand.label == "concat:emptyCapturing+emptyCapturing"
                || operand.label == "concat:emptyCapturing+emptyNonCapturing"
                || operand.label == "concat:emptyNonCapturing+emptyCapturing"
                || operand.label == "concat:emptyNonCapturing+emptyNonCapturing"
                || isAlternativeOfConditionalZeroWidthOperands(operand)
                || isGraphemeBoundaryEmptyCaptureAlternative(operand)
                || isWrappedEmptyCaptureFailedPathLeakage(spec)
                || isWrappedEmptyNonCaptureFailedPathLeakage(spec)
                || isEmptyCaptureAlternativeFailedPathLeakage(spec)
                || isGreedyRepeatedEmptyCaptureAlternativeFailedPathLeakage(spec)
                || isGreedyEmptyCaptureAlternativeFailedPathLeakage(spec)
    }

    private fun isWrappedEmptyCaptureFailedPathLeakage(spec: CaseSpec): Boolean {
        return spec.operand.label == "atom:emptyCapturing"
                && (spec.wrapper!!.label != "bare") && (spec.context!!.label == "suffixLiteral"
                || spec.context.label == "mixedLeadingLiteralAlternative"
                || spec.context.label == "mixedLeadingLiteralAlternativeReversed")
    }

    private fun isWrappedEmptyNonCaptureFailedPathLeakage(spec: CaseSpec): Boolean {
        return spec.operand.label == "atom:emptyNonCapturing"
                && spec.wrapper!!.label == "nestedGroups"
                && (spec.context!!.label == "suffixLiteral"
                || spec.context.label == "mixedLeadingLiteralAlternative"
                || spec.context.label == "mixedLeadingLiteralAlternativeReversed")
    }

    private fun isEmptyCaptureAlternativeFailedPathLeakage(spec: CaseSpec): Boolean {
        return (spec.context!!.label == "mixedLeadingLiteralAlternative"
                || spec.context.label == "mixedLeadingLiteralAlternativeReversed")
                && spec.operand.label == "atom:emptyCapturing"
    }

    private fun isGreedyRepeatedEmptyCaptureAlternativeFailedPathLeakage(spec: CaseSpec): Boolean {
        return spec.context!!.label == "mixedLeadingLiteralAlternative"
                && spec.wrapper!!.label == "capturing"
                && spec.operand.label == "atom:emptyCapturing"
                && spec.quantifierChain!!.label!!.startsWith("star")
    }

    private fun isGreedyEmptyCaptureAlternativeFailedPathLeakage(spec: CaseSpec): Boolean {
        return spec.context!!.label == "mixedLeadingLiteralAlternative"
                && isAlternativeOperand(spec.operand)
                && ((spec.quantifierChain!!.label!!.startsWith("star")
                && !spec.quantifierChain.label.startsWith("star,question"))
                || spec.quantifierChain.label.startsWith("repeatRange"))
    }

    private fun isAlternativeOperand(operand: Operand): Boolean {
        return operand.label.startsWith("alternate:")
    }

    private fun isAlternativeOfConditionalZeroWidthOperands(operand: Operand): Boolean {
        val prefix = "alternate:"
        if (!operand.label.startsWith(prefix)) {
            return false
        }
        val body = operand.label.substring(prefix.length)
        val separator = body.indexOf('|')
        if (separator < 0) {
            return false
        }
        return isConditionalZeroWidthPart(body.substring(0, separator))
                && isConditionalZeroWidthPart(body.substring(separator + 1))
    }

    private fun isGraphemeBoundaryEmptyCaptureAlternative(operand: Operand): Boolean {
        return operand.label == "alternate:graphemeBoundary|emptyCapturing"
                || operand.label == "alternate:emptyCapturing|graphemeBoundary"
    }

    private fun isEmptyCaptureThenConditionalZeroWidthOperand(operand: Operand): Boolean {
        return when (operand.label) {
            "concat:emptyCapturing+beginLine", "concat:emptyCapturing+endLine", "concat:emptyCapturing+beginText", "concat:emptyCapturing+endTextBeforeFinalTerminator", "concat:emptyCapturing+endText", "concat:emptyCapturing+wordBoundary", "concat:emptyCapturing+nonWordBoundary", "concat:emptyCapturing+graphemeBoundary" -> true
            else -> false
        }
    }

    private fun isConditionalZeroWidthThenEmptyCaptureOperand(operand: Operand): Boolean {
        return when (operand.label) {
            "concat:beginLine+emptyCapturing", "concat:endLine+emptyCapturing", "concat:beginText+emptyCapturing", "concat:endTextBeforeFinalTerminator+emptyCapturing", "concat:endText+emptyCapturing", "concat:wordBoundary+emptyCapturing", "concat:nonWordBoundary+emptyCapturing", "concat:graphemeBoundary+emptyCapturing" -> true
            else -> false
        }
    }

    private fun isConcatOfConditionalZeroWidthOperands(operand: Operand): Boolean {
        val prefix = "concat:"
        if (!operand.label.startsWith(prefix)) {
            return false
        }
        val body = operand.label.substring(prefix.length)
        val plus = body.indexOf('+')
        if (plus < 0) {
            return false
        }
        return isConditionalZeroWidthPart(body.substring(0, plus))
                && isConditionalZeroWidthPart(body.substring(plus + 1))
    }

    private fun isConditionalZeroWidthPart(label: kotlin.String): Boolean {
        return when (label) {
            "beginLine", "endLine", "beginText", "endTextBeforeFinalTerminator", "endText", "wordBoundary", "nonWordBoundary", "graphemeBoundary" -> true
            else -> false
        }
    }

    private fun isConditionalZeroWidthThenEmptyNonCaptureOperand(operand: Operand): Boolean {
        return when (operand.label) {
            "concat:beginLine+emptyNonCapturing", "concat:endLine+emptyNonCapturing", "concat:beginText+emptyNonCapturing", "concat:endTextBeforeFinalTerminator+emptyNonCapturing", "concat:endText+emptyNonCapturing", "concat:wordBoundary+emptyNonCapturing", "concat:nonWordBoundary+emptyNonCapturing", "concat:graphemeBoundary+emptyNonCapturing" -> true
            else -> false
        }
    }

    private fun isEmptyNonCaptureThenConditionalZeroWidthOperand(operand: Operand): Boolean {
        return when (operand.label) {
            "concat:emptyNonCapturing+beginLine", "concat:emptyNonCapturing+endLine", "concat:emptyNonCapturing+beginText", "concat:emptyNonCapturing+endTextBeforeFinalTerminator", "concat:emptyNonCapturing+endText", "concat:emptyNonCapturing+wordBoundary", "concat:emptyNonCapturing+nonWordBoundary", "concat:emptyNonCapturing+graphemeBoundary" -> true
            else -> false
        }
    }

    private fun normalizeCaptureFields(trace: kotlin.String): kotlin.String {
        return CAPTURE_FIELD.matcher(trace).replaceAll("g$1=<capture>").replace("[null]", "[]")
    }

    private fun isStackSafetySentinel(spec: CaseSpec): Boolean {
        return spec.operand.label == "targeted:deepEmptyCapturing"
                || spec.operand.label == "targeted:deepRepeatedBoundaryAlternation"
    }

    @JvmRecord
    private data class Atom(val label: kotlin.String?, val regex: kotlin.String?)

    @JvmRecord
    private data class Operand(val label: kotlin.String, val regex: kotlin.String?)

    @JvmRecord
    private data class Wrapper(val label: kotlin.String?, val template: kotlin.String?) {
        fun regex(operand: kotlin.String?): kotlin.String {
            return template.formatted(operand)
        }
    }

    @JvmRecord
    private data class Quantifier(val label: kotlin.String?, val text: kotlin.String?)

    @JvmRecord
    private data class QuantifierChain(val label: kotlin.String?, val text: kotlin.String)

    @JvmRecord
    private data class Context(val label: kotlin.String?, val template: kotlin.String?) {
        fun regex(repeated: kotlin.String?): kotlin.String {
            return template.formatted(repeated)
        }
    }

    @JvmRecord
    private data class FlagMode(
        val label: kotlin.String?,
        val prefix: kotlin.String?,
        val flags: Int,
        val trivia: kotlin.String?
    )

    @JvmRecord
    private data class ZeroWidthRunResult(
        val checked: Long,
        val generated: Long,
        val divergences: Long,
        val summary: ClassifiedDivergenceSummary<DivergenceClass?>?
    )

    @JvmRecord
    private data class ZeroWidthSweepOptions(val sweep: SweepOptions) {
        fun printStartup() {
            sweep.printStartup("zero-width-quantifier")
        }
    }

    private enum class DivergenceClass(status: DivergenceStatus, rationale: kotlin.String) : DivergenceClassification {
        STACK_OVERFLOW(
            DivergenceStatus.EXPECTED_ZERO,
            "Generated stack-safety sentinel cases must not throw StackOverflowError in SafeRE."
        ),
        RELUCTANT_QUANTIFIER_MODIFIER(
            DivergenceStatus.EXPECTED_ZERO,
            "JDK reluctant quantifier modifiers over zero-width operands are part of the supported"
                    + " linear-time syntax."
        ),
        COMMENTS_MODE_GRAPHEME_QUANTIFIER_TRIVIA(
            DivergenceStatus.EXPECTED_ZERO,
            "Comments-mode trivia around zero-width grapheme quantifiers must not make a valid JDK"
                    + " quantifier look argument-less."
        ),
        INVALID_QUANTIFIER_CHAIN_ACCEPTED(
            DivergenceStatus.EXPECTED_ZERO,
            "SafeRE must reject nested or dangling quantifier chains that java.util.regex rejects."
        ),
        ZERO_WIDTH_POSSESSIVE_QUANTIFIER_REJECTED(
            DivergenceStatus.EXPECTED_ZERO,
            "JDK-compatible possessive modifiers over zero-width operands are normalizable without"
                    + " introducing consuming possessive semantics."
        ),
        ZERO_WIDTH_POSSESSIVE_CAPTURE_RETENTION(
            DivergenceStatus.EXPECTED_ZERO,
            "JDK-compatible possessive modifiers over zero-width operands must preserve captures from"
                    + " the observed zero-width iteration."
        ),
        POSSESSIVE_QUANTIFIER_UNSUPPORTED(
            DivergenceStatus.KNOWN_INTENTIONAL,
            "SafeRE rejects possessive quantifiers over consuming operands to preserve the supported"
                    + " linear-time dialect."
        ),
        GRAPHEME_BOUNDARY_ALTERNATIVE_FIND_CURSOR(
            DivergenceStatus.KNOWN_INTENTIONAL,
            "Observed JDK repeated-find cursor behavior for alternatives involving a bare explicit"
                    + " \\b{g} is not part of SafeRE's compositional grapheme model."
        ),
        REPEATED_GRAPHEME_BOUNDARY_COMPOSITION(
            DivergenceStatus.KNOWN_INTENTIONAL,
            "Observed JDK traces for repeated explicit \\b{g} skip or reject compositions that"
                    + " SafeRE's compositional grapheme model preserves."
        ),
        GRAPHEME_BOUNDARY_ALTERNATIVE_GRAPHEME_MODEL(
            DivergenceStatus.KNOWN_INTENTIONAL,
            "Observed JDK traces for alternatives involving explicit \\b{g} expose grapheme"
                    + " segmentation details that conflict with SafeRE's documented grapheme model."
        ),
        GRAPHEME_BOUNDARY_CAPTURE_GRAPHEME_MODEL(
            DivergenceStatus.KNOWN_INTENTIONAL,
            "Observed JDK capture traces for explicit \\b{g} expose grapheme segmentation details"
                    + " that conflict with SafeRE's documented grapheme model."
        ),
        ASCII_WORD_BOUNDARY_COMBINING_MARK(
            DivergenceStatus.KNOWN_INTENTIONAL,
            "SafeRE follows the documented default ASCII word-character model for \\b and \\B"
                    + " without attaching combining marks to preceding base characters."
        ),
        FAILED_PATH_CAPTURE_LEAKAGE(
            DivergenceStatus.KNOWN_INTENTIONAL,
            "Observed JDK traces can preserve captures from failed backtracking paths; SafeRE does"
                    + " not preserve captures across failed NFA start/path attempts."
        ),
        UNKNOWN(DivergenceStatus.UNKNOWN, "Unclassified SafeRE/JDK zero-width quantifier divergence.");

        private val status: DivergenceStatus?
        private val rationale: kotlin.String?

        init {
            this.status = status
            this.rationale = rationale
        }

        override fun status(): DivergenceStatus? {
            return status
        }

        override fun rationale(): kotlin.String? {
            return rationale
        }
    }

    @JvmRecord
    private data class CaseSpec(
        val operand: Operand,
        val wrapper: Wrapper?,
        val quantifierChain: QuantifierChain?,
        val context: Context?,
        val flagMode: FlagMode?
    ) {
        fun regex(): kotlin.String {
            val quantified = wrapper!!.regex(operand.regex) + quantifiedTriviaChain()
            return flagMode!!.prefix + context!!.regex(quantified)
        }

        fun quantifiedTriviaChain(): kotlin.String {
            if (flagMode!!.trivia!!.isEmpty()) {
                return quantifierChain!!.text
            }
            val result = StringBuilder()
            for (i in 0..<quantifierChain!!.text.length) {
                val c = quantifierChain.text.get(i)
                if (c == '*' || c == '+' || c == '?' || c == '{') {
                    result.append(flagMode.trivia)
                }
                result.append(c)
            }
            return result.toString()
        }

        fun inputs(): MutableList<kotlin.String?> {
            val inputs: MutableSet<kotlin.String?> = LinkedHashSet<kotlin.String?>()
            inputs.add("")
            inputs.add("a")
            inputs.add("b")
            inputs.add("ab")
            inputs.add("aa")
            inputs.add("ba")
            inputs.add("\n")
            inputs.add("a\n")
            inputs.add("\na")
            inputs.add("\u00E9")
            inputs.add("e\u0301")
            inputs.add("\uD83D\uDC69\u200D\uD83D\uDCBB")
            inputs.add("\uD83D\uDC69\u200D\uD83D\uDCBBx")
            if (operand.label.contains("graphemeBoundary")
                && !operand.label.contains("wordBoundary") && !operand.label.contains("nonWordBoundary")
            ) {
                inputs.add("x\uD83D\uDC69\u200D\uD83D\uDCBBx")
            }
            return List.copyOf<kotlin.String?>(inputs)
        }

        fun labels(): kotlin.String {
            return ("operand="
                    + operand.label
                    + ",wrapper="
                    + wrapper!!.label
                    + ",chain="
                    + quantifierChain!!.label
                    + ",context="
                    + context!!.label
                    + ",flags="
                    + flagMode!!.label)
        }
    }

    @JvmRecord
    private data class Divergence(
        val caseIndex: Long,
        val spec: CaseSpec?,
        val jdk: RegexSweep.Outcome?,
        val safere: RegexSweep.Outcome?,
        val bucket: kotlin.String?,
        val classification: DivergenceClass?
    ) {
        fun toJson(): kotlin.String {
            val `object` = SweepJson.`object`()
            `object`.addProperty("caseIndex", caseIndex)
            `object`.add("case", Companion.caseJson(spec!!))
            `object`.addProperty("bucket", bucket)
            `object`.addProperty("classification", classification!!.name)
            `object`.addProperty("classificationStatus", classification.status()!!.name)
            `object`.addProperty("labels", spec.labels())
            `object`.addProperty("regex", spec.regex())
            `object`.addProperty("operand", spec.operand.label)
            `object`.addProperty("wrapper", spec.wrapper!!.label)
            `object`.addProperty("quantifierChain", spec.quantifierChain!!.label)
            `object`.addProperty(
                "quantifierChainLength",
                spec.quantifierChain.label!!.split(",".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray().size)
            `object`.addProperty("context", spec.context!!.label)
            `object`.addProperty("flags", spec.flagMode!!.label)
            `object`.addProperty("jdkAccepted", jdk!!.accepted)
            `object`.addProperty("safeReAccepted", safere!!.accepted)
            `object`.addProperty("jdkTrace", jdk.trace)
            `object`.addProperty("safeReTrace", safere.trace)
            `object`.addProperty("jdkError", jdk.error)
            `object`.addProperty("safeReError", safere.error)
            return SweepJson.toJson(`object`)
        }

        companion object {
            private fun caseJson(spec: CaseSpec): JsonObject {
                val `object` = SweepJson.`object`()
                `object`.addProperty("operandLabel", spec.operand.label)
                `object`.addProperty("operandRegex", spec.operand.regex)
                `object`.addProperty("wrapperLabel", spec.wrapper!!.label)
                `object`.addProperty("wrapperTemplate", spec.wrapper.template)
                `object`.addProperty("quantifierChainLabel", spec.quantifierChain!!.label)
                `object`.addProperty("quantifierChain", spec.quantifierChain.text)
                `object`.addProperty("contextLabel", spec.context!!.label)
                `object`.addProperty("contextTemplate", spec.context.template)
                `object`.addProperty("flagLabel", spec.flagMode!!.label)
                `object`.addProperty("flagPrefix", spec.flagMode.prefix)
                `object`.addProperty("flags", spec.flagMode.flags)
                `object`.addProperty("trivia", spec.flagMode.trivia)
                return `object`
            }
        }
    }

    private class SweepState(
        runState: SweepRunState,
        summary: ClassifiedDivergenceSummary<DivergenceClass?>,
        workerIndex: Int
    ) {
        val runState: SweepRunState?
        val summary: ClassifiedDivergenceSummary<DivergenceClass?>
        val options: SweepOptions
        val progressReporter: ProgressReporter
        val workerIndex: Int
        var generated: Long = 0

        init {
            this.runState = runState
            this.summary = summary
            this.options = runState.options
            this.progressReporter = ProgressReporter(runState, workerIndex)
            this.workerIndex = workerIndex
        }

        fun run() {
            val end = min(options.rangeEndExclusive, totalCases())
            generated = firstOwnedCaseIndex()
            while (generated < end) {
                val caseIndex = generated
                generated += options.threads.toLong()
                progressReporter.checked()
                evaluateCase(caseIndex, caseAt(caseIndex))
            }
        }

        fun firstOwnedCaseIndex(): Long {
            return SweepWorkers.firstOwnedCaseIndex(
                options.rangeStartInclusive, endExclusive(), options.threads, workerIndex
            )
        }

        fun evaluateCase(caseIndex: Long, spec: CaseSpec) {
            ZeroWidthQuantifierDivergenceSweep.evaluateCase(
                runState!!, summary, spec, caseIndex, workerIndex
            )
            reportProgressIfNeeded()
        }

        fun finish() {
            runState!!.recordGenerated(min(generated, endExclusive()))
            runState.updateWorkerNextCaseIndex(workerIndex, min(generated, endExclusive()))
        }

        fun reportProgressIfNeeded() {
            progressReporter.reportIfNeeded(min(generated, endExclusive()))
        }

        fun endExclusive(): Long {
            return min(options.rangeEndExclusive, totalCases())
        }
    }
}
