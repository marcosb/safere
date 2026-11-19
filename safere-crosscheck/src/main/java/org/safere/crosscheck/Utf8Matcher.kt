// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.crosscheck

import org.safere.Matcher
import org.safere.Utf8Matcher
import org.safere.Utf8Sink
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.regex.MatchResult
import kotlin.math.min

/** Crosscheck matcher over UTF-8 input with relative byte coordinates.  */
class Utf8Matcher internal constructor(pattern: Pattern?, input: Utf8Input?) {
    private val pattern: Pattern
    private val input: Utf8Input
    private val delegate: Utf8Matcher
    private val trace = TraceRecorder()
    private var oracles: Oracles? = null
    private var tailAppended = false

    init {
        this.pattern = Objects.requireNonNull<Pattern>(pattern, "pattern")
        this.input = Objects.requireNonNull<Utf8Input>(input, "input")
        delegate = pattern!!.saferePattern().matcher(input!!.delegate())
        if (input.validated()) {
            val decoded = input.decoded()
            val coordinates: Utf8Coordinates? = Utf8Coordinates.Companion.create(decoded)
            checkNotNull(coordinates) { "validated UTF-8 decoded to malformed UTF-16" }
            oracles =
                Oracles(
                    pattern.saferePattern().matcher(decoded),
                    pattern.jdkPattern().matcher(decoded),
                    coordinates
                )
        }
    }

    /** Attempts to match the entire input.  */
    fun matches(): Boolean {
        val actual = delegate.matches()
        if (oracles == null) {
            return actual
        }
        val stringResult = oracles!!.safereString!!.matches()
        val jdkResult = oracles!!.jdk!!.matches()
        compare("matches", "", "SafeRE UTF-8", actual, "SafeRE String", stringResult)
        compare("matches", "", "SafeRE String", stringResult, "JDK", jdkResult)
        if (actual) {
            checkMatchState("matches")
        }
        trace.recordMatch("matches", "", actual)
        return actual
    }

    /** Attempts to match a prefix of the input.  */
    fun lookingAt(): Boolean {
        val actual = delegate.lookingAt()
        if (oracles == null) {
            return actual
        }
        val stringResult = oracles!!.safereString!!.lookingAt()
        val jdkResult = oracles!!.jdk!!.lookingAt()
        compare("lookingAt", "", "SafeRE UTF-8", actual, "SafeRE String", stringResult)
        compare("lookingAt", "", "SafeRE String", stringResult, "JDK", jdkResult)
        if (actual) {
            checkMatchState("lookingAt")
        }
        trace.recordMatch("lookingAt", "", actual)
        return actual
    }

    /** Attempts to find the next matching subsequence.  */
    fun find(): Boolean {
        val actual = delegate.find()
        if (oracles == null) {
            return actual
        }
        val stringResult: Boolean = Companion.findNextScalarAligned(oracles!!.safereString!!, oracles!!.coordinates!!)
        val jdkResult: Boolean = Companion.findNextScalarAligned(oracles!!.jdk!!, oracles!!.coordinates!!)
        compare("find", "", "SafeRE UTF-8", actual, "SafeRE String", stringResult)
        compare("find", "", "SafeRE String", stringResult, "JDK", jdkResult)
        if (actual) {
            checkMatchState("find")
        }
        trace.recordMatch("find", "", actual)
        return actual
    }

    /** Resets this matcher to the whole retained input.  */
    fun reset(): org.safere.crosscheck.Utf8Matcher {
        delegate.reset()
        tailAppended = false
        if (oracles != null) {
            oracles!!.safereString!!.reset()
            oracles!!.jdk!!.reset()
            trace.recordMatch("reset", "", "this")
        }
        return this
    }

    /** Sets the byte-offset range searched by this matcher.  */
    fun region(start: Int, end: Int): org.safere.crosscheck.Utf8Matcher {
        delegate.region(start, end)
        tailAppended = false
        if (oracles != null) {
            val utf16Start = oracles!!.coordinates!!.toUtf16(start)
            val utf16End = oracles!!.coordinates!!.toUtf16(end)
            oracles!!.safereString!!.region(utf16Start, utf16End)
            oracles!!.jdk!!.region(utf16Start, utf16End)
            trace.recordMatch("region", start.toString() + ", " + end, "this")
        }
        return this
    }

    /** Returns the current region's relative byte start.  */
    fun regionStart(): Int {
        return observeRegionBoundary("regionStart", true)
    }

    /** Returns the current region's relative byte end.  */
    fun regionEnd(): Int {
        return observeRegionBoundary("regionEnd", false)
    }

    /** Returns the previous full match's relative byte start.  */
    fun start(): Int {
        return observeBoundary("start", "", 0, true)
    }

    /** Returns a numbered group's relative byte start.  */
    fun start(group: Int): Int {
        return observeBoundary("start", group.toString(), group, true)
    }

    /** Returns the previous full match's relative byte end.  */
    fun end(): Int {
        return observeBoundary("end", "", 0, false)
    }

    /** Returns a numbered group's relative byte end.  */
    fun end(group: Int): Int {
        return observeBoundary("end", group.toString(), group, false)
    }

    /** Returns the pattern's capturing-group count.  */
    fun groupCount(): Int {
        val actual = delegate.groupCount()
        if (oracles != null) {
            val stringResult = oracles!!.safereString!!.groupCount()
            val jdkResult = oracles!!.jdk!!.groupCount()
            compare("groupCount", "", "SafeRE UTF-8", actual, "SafeRE String", stringResult)
            compare("groupCount", "", "SafeRE String", stringResult, "JDK", jdkResult)
            trace.recordMatch("groupCount", "", actual)
        }
        return actual
    }

    /** Appends the whole input with every matching subsequence replaced.  */
    fun replaceAll(sink: Utf8Sink?, replacement: Utf8Input?) {
        replace(sink, replacement, true)
    }

    /** Appends the whole input with only the first matching subsequence replaced.  */
    fun replaceFirst(sink: Utf8Sink?, replacement: Utf8Input?) {
        replace(sink, replacement, false)
    }

    private fun replace(sink: Utf8Sink?, replacement: Utf8Input?, all: Boolean) {
        Objects.requireNonNull<Utf8Sink?>(sink, "sink")
        Objects.requireNonNull<Utf8Input?>(replacement, "replacement")
        val actualOutput = ByteArrayOutputStream()
        val recordingSink =
            Utf8Sink { bytes: ByteArray?, offset: Int, length: Int ->
                actualOutput.write(bytes, offset, length)
                sink!!.append(bytes, offset, length)
            }
        // Callbacks may observe the delegate's current match before the String oracles catch up.
        // Leave the oracles disabled on failure: they cannot reproduce a sink's partial output state.
        val savedOracles = oracles
        oracles = null
        if (all) {
            delegate.replaceAll(recordingSink, replacement!!.delegate())
        } else {
            delegate.replaceFirst(recordingSink, replacement!!.delegate())
        }
        oracles = savedOracles
        tailAppended = true
        if (oracles == null) {
            return
        }
        if (!replacement.validated()) {
            oracles = null
            return
        }

        val decodedReplacement = replacement.decoded()
        val stringResult = replaceScalarAligned(oracles!!.safereString!!, decodedReplacement, all)
        val jdkResult = replaceScalarAligned(oracles!!.jdk!!, decodedReplacement, all)
        val actual = actualOutput.toString(StandardCharsets.UTF_8)
        val method = if (all) "replaceAll" else "replaceFirst"
        compare(
            method, quote(decodedReplacement), "SafeRE UTF-8", actual, "SafeRE String", stringResult
        )
        compare(method, quote(decodedReplacement), "SafeRE String", stringResult, "JDK", jdkResult)
        trace.recordMatch(method, quote(decodedReplacement), actual)
    }

    private fun replaceScalarAligned(matcher: MatchResult, replacement: String, all: Boolean): String {
        if (matcher is Matcher) {
            matcher.reset()
        } else {
            (matcher as java.util.regex.Matcher).reset()
        }
        val output = StringBuilder()
        if (Companion.findNextScalarAligned(matcher, oracles!!.coordinates!!)) {
            do {
                if (matcher is Matcher) {
                    matcher.appendReplacement(output, replacement)
                } else {
                    (matcher as java.util.regex.Matcher).appendReplacement(output, replacement)
                }
            } while (all && Companion.findNextScalarAligned(matcher, oracles!!.coordinates!!))
        }
        if (matcher is Matcher) {
            matcher.appendTail(output)
        } else {
            (matcher as java.util.regex.Matcher).appendTail(output)
        }
        return output.toString()
    }

    /** Appends the unmatched prefix and expanded replacement for the current match.  */
    fun appendReplacement(sink: Utf8Sink?, replacement: Utf8Input?): org.safere.crosscheck.Utf8Matcher {
        Objects.requireNonNull<Utf8Sink?>(sink, "sink")
        Objects.requireNonNull<Utf8Input?>(replacement, "replacement")
        val actualOutput = ByteArrayOutputStream()
        delegate.appendReplacement(
            Utf8Sink { bytes: ByteArray?, offset: Int, length: Int ->
                actualOutput.write(bytes, offset, length)
                sink!!.append(bytes, offset, length)
            },
            replacement!!.delegate()
        )
        tailAppended = false
        if (oracles == null) {
            return this
        }
        if (!replacement.validated()) {
            oracles = null
            return this
        }

        val stringOutput = StringBuilder()
        val jdkOutput = StringBuilder()
        val decodedReplacement = replacement.decoded()
        oracles!!.safereString!!.appendReplacement(stringOutput, decodedReplacement)
        oracles!!.jdk!!.appendReplacement(jdkOutput, decodedReplacement)
        val actual = actualOutput.toString(StandardCharsets.UTF_8)
        compare(
            "appendReplacement",
            quote(decodedReplacement),
            "SafeRE UTF-8",
            actual,
            "SafeRE String",
            stringOutput
        )
        compare(
            "appendReplacement",
            quote(decodedReplacement),
            "SafeRE String",
            stringOutput,
            "JDK",
            jdkOutput
        )
        trace.recordMatch("appendReplacement", quote(decodedReplacement), actual)
        return this
    }

    /** Appends the subject tail following the last replacement.  */
    fun appendTail(sink: org.safere.crosscheck.Utf8Sink?) {
        Objects.requireNonNull<org.safere.crosscheck.Utf8Sink?>(sink, "sink")
        val actualOutput = ByteArrayOutputStream()
        delegate.appendTail(
            Utf8Sink { bytes: ByteArray?, offset: Int, length: Int ->
                actualOutput.write(bytes, offset, length)
                sink!!.append(bytes, offset, length)
            })
        if (oracles == null) {
            return
        }
        val stringOutput = StringBuilder()
        val jdkOutput = StringBuilder()
        // Unlike the String API, UTF-8 appendTail consumes the append position.
        if (!tailAppended) {
            oracles!!.safereString!!.appendTail(stringOutput)
            oracles!!.jdk!!.appendTail(jdkOutput)
        }
        tailAppended = true
        val actual = actualOutput.toString(StandardCharsets.UTF_8)
        compare("appendTail", "", "SafeRE UTF-8", actual, "SafeRE String", stringOutput)
        compare("appendTail", "", "SafeRE String", stringOutput, "JDK", jdkOutput)
        trace.recordMatch("appendTail", "", actual)
    }

    private fun observeRegionBoundary(method: String?, start: Boolean): Int {
        val actual = if (start) delegate.regionStart() else delegate.regionEnd()
        if (oracles == null) {
            return actual
        }
        val stringResult =
            if (start) oracles!!.safereString!!.regionStart() else oracles!!.safereString!!.regionEnd()
        val jdkResult = if (start) oracles!!.jdk!!.regionStart() else oracles!!.jdk!!.regionEnd()
        compare(method, "", "SafeRE String", stringResult, "JDK", jdkResult)
        compareOracleBoundary(method, "", actual, "SafeRE String", stringResult)
        compareOracleBoundary(method, "", actual, "JDK", jdkResult)
        trace.recordMatch(method, "", actual)
        return actual
    }

    private fun observeBoundary(method: String?, args: String?, group: Int, start: Boolean): Int {
        if (oracles == null) {
            return if (start) delegate.start(group) else delegate.end(group)
        }
        val actual: Observation = observe(IntOperation { if (start) delegate.start(group) else delegate.end(group) })
        val stringResult: Observation =
            observe(
                IntOperation { if (start) oracles!!.safereString!!.start(group) else oracles!!.safereString!!.end(group) })
        val jdkResult: Observation =
            observe(IntOperation { if (start) oracles!!.jdk!!.start(group) else oracles!!.jdk!!.end(group) })
        compareObservations(method, args, "SafeRE UTF-8", actual, "SafeRE String", stringResult)
        compareObservations(method, args, "SafeRE String", stringResult, "JDK", jdkResult)
        if (actual.failure != null) {
            throw actual.failure
        }

        val actualOffset = actual.value
        if (actualOffset >= 0) {
            oracles!!.coordinates!!.toUtf16(actualOffset)
        }
        compareOracleBoundary(method, args, actualOffset, "SafeRE String", stringResult.value)
        compareOracleBoundary(method, args, actualOffset, "JDK", jdkResult.value)
        trace.recordMatch(method, args, actualOffset)
        return actualOffset
    }

    private fun checkMatchState(context: String?) {
        val actualGroupCount = delegate.groupCount()
        compare(
            context + " -> groupCount",
            "",
            "SafeRE UTF-8",
            actualGroupCount,
            "SafeRE String",
            oracles!!.safereString!!.groupCount()
        )
        compare(
            context + " -> groupCount",
            "",
            "SafeRE String",
            oracles!!.safereString!!.groupCount(),
            "JDK",
            oracles!!.jdk!!.groupCount()
        )
        for (group in 0..actualGroupCount) {
            compareCurrentBoundary(context, group, true)
            compareCurrentBoundary(context, group, false)
        }
    }

    private fun compareCurrentBoundary(context: String?, group: Int, start: Boolean) {
        val actual = if (start) delegate.start(group) else delegate.end(group)
        if (actual >= 0) {
            oracles!!.coordinates!!.toUtf16(actual)
        }
        val stringResult =
            if (start) oracles!!.safereString!!.start(group) else oracles!!.safereString!!.end(group)
        val jdkResult = if (start) oracles!!.jdk!!.start(group) else oracles!!.jdk!!.end(group)
        val method = context + " -> " + (if (start) "start" else "end") + "(" + group + ")"
        compareOracleBoundary(method, "", actual, "SafeRE String", stringResult)
        compareOracleBoundary(method, "", actual, "JDK", jdkResult)
    }

    private fun compareOracleBoundary(
        method: String?, args: String?, actual: Int, oracleLabel: String?, utf16Offset: Int
    ) {
        if (utf16Offset < 0) {
            compare(method, args, "SafeRE UTF-8", actual, oracleLabel, -1)
        } else if (oracles!!.coordinates!!.isUtf16Boundary(utf16Offset)) {
            compare(
                method,
                args,
                "SafeRE UTF-8",
                actual,
                oracleLabel,
                oracles!!.coordinates!!.toUtf8(utf16Offset)
            )
        }
    }

    private fun compareObservations(
        method: String?,
        args: String?,
        firstLabel: String?,
        first: Observation,
        secondLabel: String?,
        second: Observation
    ) {
        if (first.failure == null && second.failure == null) {
            return
        }
        val firstResult: Any? =
            if (first.failure == null) first.value else "throws " + first.failure.javaClass.getName()
        val secondResult: Any? =
            if (second.failure == null)
                second.value
            else
                "throws " + second.failure.javaClass.getName()
        compare(method, args, firstLabel, firstResult, secondLabel, secondResult)
    }

    private fun compare(
        method: String?,
        args: String?,
        firstLabel: String?,
        first: Any?,
        secondLabel: String?,
        second: Any?
    ) {
        if (Objects.toString(first) == Objects.toString(second)) {
            return
        }
        trace.recordDivergence(method, args, first, second)
        throw CrosscheckException.Companion.comparison(
            method,
            args,
            firstLabel,
            Objects.toString(first),
            secondLabel,
            Objects.toString(second),
            trace.format() + "\n" + context()
        )
    }

    private fun context(): String {
        val shownLength = min(input.length(), 256)
        val shown = Arrays.copyOfRange(input.bytes(), input.offset(), input.offset() + shownLength)
        val suffix = if (shownLength == input.length()) "" else " ..."
        return ("pattern: /"
                + pattern.pattern()
                + "/\n"
                + "decoded input: "
                + quote(input.decoded())
                + "\n"
                + "logical byte window: offset="
                + input.offset()
                + ", length="
                + input.length()
                + "\n"
                + "logical bytes: "
                + shown.contentToString() + suffix
                + "\n"
                + "UTF-16->UTF-8 boundaries: "
                + oracles!!.coordinates!!.describe())
    }

    @JvmRecord
    private data class Oracles(
        val safereString: Matcher?,
        val jdk: java.util.regex.Matcher?,
        val coordinates: Utf8Coordinates?
    )

    @JvmRecord
    private data class Observation(val value: Int, val failure: RuntimeException?)

    private fun interface IntOperation {
        fun get(): Int
    }

    companion object {
        fun crosscheckFind(pattern: Pattern, input: Utf8Input, actual: Boolean) {
            if (!input.validated()) {
                return
            }
            val decoded = input.decoded()
            val coordinates: Utf8Coordinates? = Utf8Coordinates.Companion.create(decoded)
            checkNotNull(coordinates) { "validated UTF-8 decoded to malformed UTF-16" }
            val stringResult: Boolean =
                findNextScalarAligned(pattern.saferePattern().matcher(decoded), coordinates)
            val jdkResult: Boolean = findNextScalarAligned(pattern.jdkPattern().matcher(decoded), coordinates)
            if (actual != stringResult) {
                throw directDivergence(
                    pattern,
                    input,
                    coordinates,
                    "Pattern.find",
                    "SafeRE UTF-8",
                    actual,
                    "SafeRE String",
                    stringResult
                )
            }
            if (stringResult != jdkResult) {
                throw directDivergence(
                    pattern,
                    input,
                    coordinates,
                    "Pattern.find",
                    "SafeRE String",
                    stringResult,
                    "JDK",
                    jdkResult
                )
            }
        }

        private fun directDivergence(
            pattern: Pattern,
            input: Utf8Input,
            coordinates: Utf8Coordinates,
            method: String?,
            firstLabel: String?,
            first: Any?,
            secondLabel: String?,
            second: Any?
        ): CrosscheckException {
            return CrosscheckException.Companion.comparison(
                method,
                "",
                firstLabel,
                Objects.toString(first),
                secondLabel,
                Objects.toString(second),
                ("pattern: /"
                        + pattern.pattern()
                        + "/\n"
                        + "decoded input: "
                        + quote(input.decoded())
                        + "\nlogical byte window: offset="
                        + input.offset()
                        + ", length="
                        + input.length()
                        + "\nUTF-16->UTF-8 boundaries: "
                        + coordinates.describe())
            )
        }

        private fun findNextScalarAligned(matcher: MatchResult, coordinates: Utf8Coordinates): Boolean {
            while (find(matcher)) {
                if (coordinates.isUtf16Boundary(matcher.start())
                    && coordinates.isUtf16Boundary(matcher.end())
                ) {
                    return true
                }
            }
            return false
        }

        private fun find(matcher: MatchResult): Boolean {
            if (matcher is Matcher) {
                return matcher.find()
            }
            return (matcher as java.util.regex.Matcher).find()
        }

        private fun observe(operation: IntOperation): Observation {
            try {
                return Observation(operation.get(), null)
            } catch (failure: RuntimeException) {
                return Observation(0, failure)
            }
        }

        private fun quote(text: String): String {
            return "\"" + text + "\""
        }
    }
}
