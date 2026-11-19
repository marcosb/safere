// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.crosscheck

import org.safere.Matcher
import java.util.*
import java.util.Spliterators.AbstractSpliterator
import java.util.function.Consumer
import java.util.function.Function
import java.util.regex.MatchResult
import java.util.stream.Stream
import java.util.stream.StreamSupport

/**
 * A crosscheck wrapper that delegates every matcher operation to both SafeRE and `java.util.regex`, comparing results and throwing [CrosscheckException] on divergence.
 * 
 * 
 * This class has the same API as [Matcher] and [java.util.regex.Matcher],
 * so switching to crosscheck mode requires only changing the import. Every method call is recorded
 * by a [TraceRecorder]; the trace is included in any [CrosscheckException] and can be
 * retrieved via [.getTrace].
 * 
 * 
 * Like [java.util.regex.Matcher], this class is not thread-safe.
 */
class Matcher internal constructor(private var crosscheckPattern: Pattern, input: CharSequence) : MatchResult {
    private val safereMatcher: Matcher
    private val jdkMatcher: java.util.regex.Matcher

    /** Returns the trace recorder for this matcher.  */
    @JvmField
    val trace: TraceRecorder = TraceRecorder()
    private var utf8ShadowInput: CharSequence? = null
    private var utf8Shadow: Utf8Shadow? = null

    init {
        this.safereMatcher = crosscheckPattern.saferePattern().matcher(input)
        this.jdkMatcher = crosscheckPattern.jdkPattern().matcher(input)
        resetUtf8Shadow(input)
    }

    // ---------------------------------------------------------------------------
    // Matching
    // ---------------------------------------------------------------------------
    /** Attempts to match the entire input against the pattern.  */
    fun matches(): Boolean {
        disableUtf8Shadow()
        val sr = safereMatcher.matches()
        val jr = jdkMatcher.matches()
        checkBoolean("matches", "", sr, jr)
        return sr
    }

    /** Attempts to match the input, starting at the beginning, against the pattern.  */
    fun lookingAt(): Boolean {
        disableUtf8Shadow()
        val sr = safereMatcher.lookingAt()
        val jr = jdkMatcher.lookingAt()
        checkBoolean("lookingAt", "", sr, jr)
        return sr
    }

    /** Finds the next subsequence that matches the pattern.  */
    fun find(): Boolean {
        val sr = safereMatcher.find()
        val jr = jdkMatcher.find()
        var ur = if (utf8Shadow == null) null else utf8Shadow!!.find()
        checkBoolean("find", "", sr, jr)
        if (ur != null && sr && !utf8Shadow!!.canRepresent(safereMatcher)) {
            disableUtf8Shadow()
            ur = null
        }
        if (ur != null) {
            checkUtf8Equal("find", "", sr, ur)
        }
        if (sr && jr) {
            checkMatchState("find")
            checkUtf8MatchState("find")
        }
        return sr
    }

    /** Resets and finds starting at the given index.  */
    fun find(start: Int): Boolean {
        disableUtf8Shadow()
        val sr = safereMatcher.find(start)
        val jr = jdkMatcher.find(start)
        checkBoolean("find", start.toString(), sr, jr)
        if (sr && jr) {
            checkMatchState("find(" + start + ")")
        }
        return sr
    }

    // ---------------------------------------------------------------------------
    // Groups
    // ---------------------------------------------------------------------------
    /** Returns the number of capturing groups.  */
    override fun groupCount(): Int {
        val sr = safereMatcher.groupCount()
        val jr = jdkMatcher.groupCount()
        checkEqual("groupCount", "", sr, jr)
        if (utf8Shadow != null) {
            checkUtf8Equal("groupCount", "", sr, utf8Shadow!!.groupCount())
        }
        return sr
    }

    /** Returns the input subsequence matched by the previous match (group 0).  */
    override fun group(): String? {
        val sr = safereMatcher.group()
        val jr = jdkMatcher.group()
        checkEqual("group", "", sr, jr)
        if (utf8Shadow != null) {
            checkUtf8Equal("group", "", sr, utf8Shadow!!.group(0))
        }
        return sr
    }

    /** Returns the input subsequence captured by the given group.  */
    override fun group(group: Int): String? {
        val sr = safereMatcher.group(group)
        val jr = jdkMatcher.group(group)
        checkEqual("group", group.toString(), sr, jr)
        if (utf8Shadow != null) {
            checkUtf8Equal("group", group.toString(), sr, utf8Shadow!!.group(group))
        }
        return sr
    }

    /** Returns the input subsequence captured by the given named group.  */
    override fun group(name: String): String? {
        val sr = safereMatcher.group(name)
        val jr = jdkMatcher.group(name)
        checkEqual("group", quote(name), sr, jr)
        if (utf8Shadow != null) {
            checkUtf8Equal(
                "group", quote(name), sr, utf8Shadow!!.group(crosscheckPattern.namedGroups().get(name)!!)
            )
        }
        return sr
    }

    /** Returns the start index of the previous match (group 0).  */
    override fun start(): Int {
        val sr = safereMatcher.start()
        val jr = jdkMatcher.start()
        checkEqual("start", "", sr, jr)
        if (utf8Shadow != null) {
            checkUtf8Equal("start", "", sr, utf8Shadow!!.start(0))
        }
        return sr
    }

    /** Returns the start index of the given group.  */
    override fun start(group: Int): Int {
        val sr = safereMatcher.start(group)
        val jr = jdkMatcher.start(group)
        checkEqual("start", group.toString(), sr, jr)
        if (utf8Shadow != null) {
            checkUtf8Equal("start", group.toString(), sr, utf8Shadow!!.start(group))
        }
        return sr
    }

    /** Returns the start index of the given named group.  */
    override fun start(name: String): Int {
        val sr = safereMatcher.start(name)
        val jr = jdkMatcher.start(name)
        checkEqual("start", quote(name), sr, jr)
        if (utf8Shadow != null) {
            checkUtf8Equal(
                "start", quote(name), sr, utf8Shadow!!.start(crosscheckPattern.namedGroups().get(name)!!)
            )
        }
        return sr
    }

    /** Returns the end index (exclusive) of the previous match (group 0).  */
    override fun end(): Int {
        val sr = safereMatcher.end()
        val jr = jdkMatcher.end()
        checkEqual("end", "", sr, jr)
        if (utf8Shadow != null) {
            checkUtf8Equal("end", "", sr, utf8Shadow!!.end(0))
        }
        return sr
    }

    /** Returns the end index (exclusive) of the given group.  */
    override fun end(group: Int): Int {
        val sr = safereMatcher.end(group)
        val jr = jdkMatcher.end(group)
        checkEqual("end", group.toString(), sr, jr)
        if (utf8Shadow != null) {
            checkUtf8Equal("end", group.toString(), sr, utf8Shadow!!.end(group))
        }
        return sr
    }

    /** Returns the end index (exclusive) of the given named group.  */
    override fun end(name: String): Int {
        val sr = safereMatcher.end(name)
        val jr = jdkMatcher.end(name)
        checkEqual("end", quote(name), sr, jr)
        if (utf8Shadow != null) {
            checkUtf8Equal(
                "end", quote(name), sr, utf8Shadow!!.end(crosscheckPattern.namedGroups().get(name)!!)
            )
        }
        return sr
    }

    /** Returns whether this matcher has a match.  */
    override fun hasMatch(): Boolean {
        val sr = safereMatcher.hasMatch()
        val jr = jdkMatcher.hasMatch()
        checkBoolean("hasMatch", "", sr, jr)
        return sr
    }

    // ---------------------------------------------------------------------------
    // Replacement
    // ---------------------------------------------------------------------------
    /** Replaces the first match with the given replacement string.  */
    fun replaceFirst(replacement: String): String? {
        val sr = safereMatcher.replaceFirst(replacement)
        val jr = jdkMatcher.replaceFirst(replacement)
        checkEqual("replaceFirst", quote(replacement), sr, jr)
        if (utf8Shadow != null) {
            if (utf8Shadow!!.canEncode(replacement)) {
                checkUtf8Equal(
                    "replaceFirst", quote(replacement), sr, utf8Shadow!!.replaceFirst(replacement)
                )
            }
            disableUtf8Shadow()
        }
        return sr
    }

    /** Replaces all matches with the given replacement string.  */
    fun replaceAll(replacement: String): String? {
        val sr = safereMatcher.replaceAll(replacement)
        val jr = jdkMatcher.replaceAll(replacement)
        checkEqual("replaceAll", quote(replacement), sr, jr)
        if (utf8Shadow != null) {
            if (utf8Shadow!!.canEncode(replacement)) {
                checkUtf8Equal("replaceAll", quote(replacement), sr, utf8Shadow!!.replaceAll(replacement))
            }
            disableUtf8Shadow()
        }
        return sr
    }

    /** Replaces the first match with a replacement computed from the match result.  */
    fun replaceFirst(replacer: Function<MatchResult?, String?>): String? {
        disableUtf8Shadow()
        val sr = safereMatcher.replaceFirst(replacer)
        val jr = jdkMatcher.replaceFirst(replacer)
        checkEqual("replaceFirst", "<function>", sr, jr)
        return sr
    }

    /** Replaces all matches with replacements computed from match results.  */
    fun replaceAll(replacer: Function<MatchResult?, String?>): String? {
        disableUtf8Shadow()
        val sr = safereMatcher.replaceAll(replacer)
        val jr = jdkMatcher.replaceAll(replacer)
        checkEqual("replaceAll", "<function>", sr, jr)
        return sr
    }

    /**
     * Implements a non-terminal append-and-replace step, applying to both engines and comparing the
     * appended fragment.
     * 
     * 
     * Note: the caller's `StringBuilder` receives SafeRE's output.
     */
    fun appendReplacement(sb: StringBuilder, replacement: String): org.safere.crosscheck.Matcher {
        val safeSb = StringBuilder()
        val jdkSb = StringBuilder()
        safereMatcher.appendReplacement(safeSb, replacement)
        jdkMatcher.appendReplacement(jdkSb, replacement)
        val sr = safeSb.toString()
        val jr = jdkSb.toString()
        checkEqual("appendReplacement", quote(replacement), sr, jr)
        if (utf8Shadow != null) {
            if (utf8Shadow!!.canEncode(replacement)) {
                checkUtf8Equal(
                    "appendReplacement", quote(replacement), sr, utf8Shadow!!.appendReplacement(replacement)
                )
            } else {
                disableUtf8Shadow()
            }
        }
        sb.append(sr)
        return this
    }

    /**
     * Implements a terminal append step. Compares the tail fragment from both engines.
     * 
     * @throws CrosscheckException if the appended tail fragments differ
     */
    fun appendTail(sb: StringBuilder): StringBuilder {
        val safeSb = StringBuilder()
        val jdkSb = StringBuilder()
        safereMatcher.appendTail(safeSb)
        jdkMatcher.appendTail(jdkSb)
        val sr = safeSb.toString()
        val jr = jdkSb.toString()
        checkEqual("appendTail", "", sr, jr)
        if (utf8Shadow != null) {
            checkUtf8Equal("appendTail", "", sr, utf8Shadow!!.appendTail())
        }
        sb.append(sr)
        return sb
    }

    /** Implements a non-terminal append-and-replace step using [StringBuffer].  */
    fun appendReplacement(sb: StringBuffer, replacement: String): org.safere.crosscheck.Matcher {
        val safeSbuf = StringBuffer()
        val jdkSbuf = StringBuffer()
        safereMatcher.appendReplacement(safeSbuf, replacement)
        jdkMatcher.appendReplacement(jdkSbuf, replacement)
        val sr = safeSbuf.toString()
        val jr = jdkSbuf.toString()
        checkEqual("appendReplacement", quote(replacement), sr, jr)
        if (utf8Shadow != null) {
            if (utf8Shadow!!.canEncode(replacement)) {
                checkUtf8Equal(
                    "appendReplacement", quote(replacement), sr, utf8Shadow!!.appendReplacement(replacement)
                )
            } else {
                disableUtf8Shadow()
            }
        }
        sb.append(sr)
        return this
    }

    /** Implements a terminal append step using [StringBuffer].  */
    fun appendTail(sb: StringBuffer): StringBuffer {
        val safeSbuf = StringBuffer()
        val jdkSbuf = StringBuffer()
        safereMatcher.appendTail(safeSbuf)
        jdkMatcher.appendTail(jdkSbuf)
        val sr = safeSbuf.toString()
        val jr = jdkSbuf.toString()
        checkEqual("appendTail", "", sr, jr)
        if (utf8Shadow != null) {
            checkUtf8Equal("appendTail", "", sr, utf8Shadow!!.appendTail())
        }
        sb.append(sr)
        return sb
    }

    // ---------------------------------------------------------------------------
    // State management
    // ---------------------------------------------------------------------------
    /** Resets this matcher.  */
    fun reset(): org.safere.crosscheck.Matcher {
        safereMatcher.reset()
        jdkMatcher.reset()
        resetUtf8Shadow(utf8ShadowInput)
        trace.recordMatch("reset", "", "void")
        return this
    }

    /** Resets this matcher with a new input.  */
    fun reset(input: CharSequence?): org.safere.crosscheck.Matcher {
        safereMatcher.reset(input)
        jdkMatcher.reset(input)
        resetUtf8Shadow(input)
        trace.recordMatch("reset", quote(input), "void")
        return this
    }

    /** Sets the region of the input for matching.  */
    fun region(start: Int, end: Int): org.safere.crosscheck.Matcher {
        disableUtf8Shadow()
        safereMatcher.region(start, end)
        jdkMatcher.region(start, end)
        trace.recordMatch("region", start.toString() + ", " + end, "void")
        return this
    }

    /** Returns the start of the matching region.  */
    fun regionStart(): Int {
        val sr = safereMatcher.regionStart()
        val jr = jdkMatcher.regionStart()
        checkEqual("regionStart", "", sr, jr)
        return sr
    }

    /** Returns the end of the matching region.  */
    fun regionEnd(): Int {
        val sr = safereMatcher.regionEnd()
        val jr = jdkMatcher.regionEnd()
        checkEqual("regionEnd", "", sr, jr)
        return sr
    }

    /** Returns whether this matcher uses transparent bounds.  */
    fun hasTransparentBounds(): Boolean {
        val sr = safereMatcher.hasTransparentBounds()
        val jr = jdkMatcher.hasTransparentBounds()
        checkBoolean("hasTransparentBounds", "", sr, jr)
        return sr
    }

    /** Sets whether this matcher uses transparent bounds.  */
    fun useTransparentBounds(b: Boolean): org.safere.crosscheck.Matcher {
        disableUtf8Shadow()
        safereMatcher.useTransparentBounds(b)
        jdkMatcher.useTransparentBounds(b)
        trace.recordMatch("useTransparentBounds", b.toString(), "void")
        return this
    }

    /** Returns whether this matcher uses anchoring bounds.  */
    fun hasAnchoringBounds(): Boolean {
        val sr = safereMatcher.hasAnchoringBounds()
        val jr = jdkMatcher.hasAnchoringBounds()
        checkBoolean("hasAnchoringBounds", "", sr, jr)
        return sr
    }

    /** Sets whether this matcher uses anchoring bounds.  */
    fun useAnchoringBounds(b: Boolean): org.safere.crosscheck.Matcher {
        disableUtf8Shadow()
        safereMatcher.useAnchoringBounds(b)
        jdkMatcher.useAnchoringBounds(b)
        trace.recordMatch("useAnchoringBounds", b.toString(), "void")
        return this
    }

    /** Returns the crosscheck pattern associated with this matcher.  */
    fun pattern(): Pattern {
        return crosscheckPattern
    }

    /** Changes the pattern used by this matcher.  */
    fun usePattern(newPattern: Pattern): org.safere.crosscheck.Matcher {
        requireNotNull(newPattern) { "Pattern must not be null" }
        safereMatcher.usePattern(newPattern.saferePattern())
        jdkMatcher.usePattern(newPattern.jdkPattern())
        disableUtf8Shadow()
        crosscheckPattern = newPattern
        trace.recordMatch("usePattern", newPattern.pattern(), "void")
        return this
    }

    /** Returns the named groups from the pattern.  */
    override fun namedGroups(): MutableMap<String?, Int?>? {
        return crosscheckPattern.namedGroups()
    }

    /** Returns a snapshot of the current match result.  */
    fun toMatchResult(): MatchResult {
        // Return SafeRE's result — the match state has already been crosschecked.
        return safereMatcher.toMatchResult()
    }

    /** Returns a stream of match-result snapshots.  */
    fun results(): Stream<MatchResult?> {
        disableUtf8Shadow()
        val safereResults = safereMatcher.results()
        val jdkResults = jdkMatcher.results()
        val safereIterator: MutableIterator<MatchResult> = safereResults.iterator()
        val jdkIterator: MutableIterator<MatchResult> = jdkResults.iterator()
        val spliterator: Spliterator<MatchResult?> =
            object : AbstractSpliterator<MatchResult?>(
                Long.Companion.MAX_VALUE, ORDERED or NONNULL
            ) {
                override fun tryAdvance(action: Consumer<in MatchResult?>): Boolean {
                    val srHasNext = safereIterator.hasNext()
                    val jrHasNext = jdkIterator.hasNext()
                    checkBoolean("results.hasNext", "", srHasNext, jrHasNext)
                    if (!srHasNext) {
                        return false
                    }

                    val sr = safereIterator.next()
                    val jr = jdkIterator.next()
                    checkMatchResult("results.next", sr, jr)
                    action.accept(sr)
                    return true
                }
            }
        return StreamSupport.stream<MatchResult?>(spliterator, false)
            .onClose(Runnable { safereResults.close() })
            .onClose(Runnable { jdkResults.close() })
    }

    // ---------------------------------------------------------------------------
    // Trace access
    // ---------------------------------------------------------------------------

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------
    /**
     * After a successful find/matches/lookingAt on both engines, verify that the match positions and
     * groups agree.
     */
    private fun checkMatchState(context: String?) {
        val srStart = safereMatcher.start()
        val jrStart = jdkMatcher.start()
        if (srStart != jrStart) {
            trace.recordDivergence(context + ".start", "", srStart, jrStart)
            throwDivergence(context + " → start()", "", srStart, jrStart)
        }

        val srEnd = safereMatcher.end()
        val jrEnd = jdkMatcher.end()
        if (srEnd != jrEnd) {
            trace.recordDivergence(context + ".end", "", srEnd, jrEnd)
            throwDivergence(context + " → end()", "", srEnd, jrEnd)
        }

        val srGroupCount = safereMatcher.groupCount()
        val jrGroupCount = jdkMatcher.groupCount()
        if (srGroupCount != jrGroupCount) {
            trace.recordDivergence(context + ".groupCount", "", srGroupCount, jrGroupCount)
            throwDivergence(context + " → groupCount()", "", srGroupCount, jrGroupCount)
        }

        for (i in 1..srGroupCount) {
            val srGroup = safereMatcher.group(i)
            val jrGroup = jdkMatcher.group(i)
            if (srGroup != jrGroup) {
                trace.recordDivergence(context + ".group", i.toString(), srGroup, jrGroup)
                throwDivergence(context + " → group(" + i + ")", "", srGroup, jrGroup)
            }
        }

        trace.recordMatch(context, "", "start=" + srStart + ", end=" + srEnd)
    }

    private fun checkUtf8MatchState(context: String?) {
        if (utf8Shadow == null) {
            return
        }
        val groupCount = safereMatcher.groupCount()
        checkUtf8Equal(context + " → groupCount()", "", groupCount, utf8Shadow!!.groupCount())
        for (group in 0..groupCount) {
            checkUtf8Equal(
                context + " → start(" + group + ")",
                "",
                safereMatcher.start(group),
                utf8Shadow!!.start(group)
            )
            checkUtf8Equal(
                context + " → end(" + group + ")", "", safereMatcher.end(group), utf8Shadow!!.end(group)
            )
            checkUtf8Equal(
                context + " → group(" + group + ")",
                "",
                safereMatcher.group(group),
                utf8Shadow!!.group(group)
            )
        }
    }

    private fun checkUtf8Equal(method: String?, args: String?, stringResult: Any?, utf8Result: Any?) {
        if (stringResult != utf8Result) {
            val traceMethod = method + " [SafeRE String vs SafeRE UTF-8]"
            trace.recordDivergence(traceMethod, args, stringResult, utf8Result)
            throw CrosscheckException.Companion.comparison(
                method,
                args,
                "SafeRE String",
                Objects.toString(stringResult),
                "SafeRE UTF-8",
                Objects.toString(utf8Result),
                trace.format()
            )
        }
    }

    private fun resetUtf8Shadow(input: CharSequence?) {
        utf8ShadowInput = input
        utf8Shadow = Utf8Shadow.Companion.create(crosscheckPattern, input)
    }

    private fun disableUtf8Shadow() {
        utf8Shadow = null
    }

    fun utf8ShadowActive(): Boolean {
        return utf8Shadow != null
    }

    /** Returns a string representation of this matcher's state.  */
    override fun toString(): String {
        return safereMatcher.toString()
    }

    private fun checkBoolean(method: String?, args: String?, sr: Boolean, jr: Boolean) {
        if (sr != jr) {
            trace.recordDivergence(method, args, sr, jr)
            throwDivergence(method, args, sr, jr)
        }
        trace.recordMatch(method, args, sr)
    }

    private fun checkEqual(method: String?, args: String?, sr: Any?, jr: Any?) {
        if (sr != jr) {
            trace.recordDivergence(method, args, sr, jr)
            throwDivergence(method, args, sr, jr)
        }
        trace.recordMatch(method, args, sr)
    }

    private fun checkMatchResult(context: String?, sr: MatchResult, jr: MatchResult) {
        checkEqual(context + ".start", "", sr.start(), jr.start())
        checkEqual(context + ".end", "", sr.end(), jr.end())
        val srGroupCount = sr.groupCount()
        val jrGroupCount = jr.groupCount()
        checkEqual(context + ".groupCount", "", srGroupCount, jrGroupCount)
        for (i in 0..srGroupCount) {
            checkEqual(context + ".group", i.toString(), sr.group(i), jr.group(i))
            checkEqual(context + ".start", i.toString(), sr.start(i), jr.start(i))
            checkEqual(context + ".end", i.toString(), sr.end(i), jr.end(i))
        }
        val srNamedGroups = sr.namedGroups()
        val jrNamedGroups = jr.namedGroups()
        checkEqual(context + ".namedGroups", "", srNamedGroups, jrNamedGroups)
        for (name in srNamedGroups.keys) {
            checkEqual(context + ".group", quote(name), sr.group(name), jr.group(name))
            checkEqual(context + ".start", quote(name), sr.start(name), jr.start(name))
            checkEqual(context + ".end", quote(name), sr.end(name), jr.end(name))
        }
    }

    private fun throwDivergence(method: String?, args: String?, sr: Any?, jr: Any?) {
        throw CrosscheckException(
            method, args, Objects.toString(sr), Objects.toString(jr), trace.format()
        )
    }

    companion object {
        /** Returns a literal replacement string.  */
        @JvmStatic
        fun quoteReplacement(s: String): String? {
            return java.util.regex.Matcher.quoteReplacement(s)
        }

        private fun quote(s: CharSequence?): String {
            return "\"" + s + "\""
        }
    }
}
