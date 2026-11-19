// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.safere.FuzzDivergenceEvidence
import org.safere.Matcher
import org.safere.Pattern
import java.util.*
import java.util.concurrent.*
import java.util.function.Function
import java.util.regex.MatchResult
import java.util.regex.PatternSyntaxException
import kotlin.math.max

internal object FuzzSupport {
    private const val JDK_ORACLE_TIMEOUT_PROPERTY = "safere.fuzz.jdkOracleTimeoutMillis"
    private const val DEFAULT_JDK_ORACLE_TIMEOUT_MILLIS: Long = 250
    private val JDK_ORACLE_EXECUTOR: ExecutorService = Executors.newCachedThreadPool(
        ThreadFactory { runnable: Runnable? ->
            val thread = Thread(runnable, "safere-jdk-regex-oracle")
            thread.setDaemon(true)
            thread
        })

    private val FLAGS = intArrayOf(
        Pattern.UNIX_LINES,
        Pattern.CASE_INSENSITIVE,
        Pattern.COMMENTS,
        Pattern.MULTILINE,
        Pattern.LITERAL,
        Pattern.DOTALL,
        Pattern.UNICODE_CASE,
        Pattern.UNICODE_CHARACTER_CLASS
    )

    fun compileCompatibleOrSkip(regex: String, flags: Int): CompiledPattern? {
        FuzzDivergenceEvidence.begin()
        var safeRePattern: Pattern? = null
        var jdkPattern: java.util.regex.Pattern? = null
        var safeReException: PatternSyntaxException? = null
        var jdkException: PatternSyntaxException? = null

        try {
            safeRePattern = Pattern.compile(regex, flags)
        } catch (e: PatternSyntaxException) {
            safeReException = e
        }
        try {
            jdkPattern = java.util.regex.Pattern.compile(regex, flags)
        } catch (e: PatternSyntaxException) {
            jdkException = e
        }

        if (safeRePattern != null && jdkPattern != null) {
            return CompiledPattern(regex, flags, safeRePattern, jdkPattern)
        }
        if (safeReException != null && jdkException != null) {
            return null
        }
        if (safeReException != null && isOverCompilerBudget(safeReException)) {
            return null
        }

        val safeRe =
            if (safeReException == null)
                "compiled successfully"
            else
                safeReException.javaClass.getSimpleName() + ": " + safeReException.message
        val jdk =
            if (jdkException == null)
                "compiled successfully"
            else
                jdkException.javaClass.getSimpleName() + ": " + jdkException.message
        throw FuzzFinding.failure(regex, flags, "", "compile", "", "", safeRe, jdk)
    }

    fun assertFullMatchesJdk(regex: String, flags: Int, inputs: MutableList<String>) {
        val pattern = compileCompatibleOrSkip(regex, flags)
        if (pattern == null) {
            return
        }
        for (input in inputs) {
            pattern.matcher(input).matches()
        }
    }

    fun compileOrSkip(regex: String, flags: Int): CompiledPattern? {
        return compileCompatibleOrSkip(regex, flags)
    }

    fun jdkOracleCompletesForTesting(regex: String, input: String): Boolean {
        val pattern = java.util.regex.Pattern.compile(regex)
        return runJdkOracle<Boolean?>("matches", JdkOracleOperation { pattern.matcher(interruptible(input)).matches() })
            .available
    }

    fun jdkOracleStackOverflowIsAvailableForTesting(): Boolean {
        return runJdkOracle<Any?>(
            "stack overflow",
            JdkOracleOperation {
                throw StackOverflowError()
            })
            .available
    }

    fun consumeFlags(data: FuzzedDataProvider): Int {
        var flags = 0
        for (flag in FLAGS) {
            if (data.consumeBoolean()) {
                flags = flags or flag
            }
        }
        return flags
    }

    fun consumeParserFlags(data: FuzzedDataProvider): Int {
        var flags = 0
        if (data.consumeBoolean()) {
            flags = flags or Pattern.COMMENTS
        }
        if (data.consumeBoolean()) {
            flags = flags or Pattern.CASE_INSENSITIVE
        }
        if (data.consumeBoolean()) {
            flags = flags or Pattern.UNICODE_CASE
        }
        if (data.consumeBoolean()) {
            flags = flags or Pattern.UNICODE_CHARACTER_CLASS
        }
        return flags
    }

    private fun <T> runJdkOracle(
        operation: String?, jdkOperation: JdkOracleOperation<T?>
    ): JdkOracleResult<T?> {
        val task = JDK_ORACLE_EXECUTOR.submit<T?>(Callable { jdkOperation.run() })

        try {
            return JdkOracleResult<T?>(true, task.get(jdkOracleTimeoutMillis(), TimeUnit.MILLISECONDS))
        } catch (e: TimeoutException) {
            task.cancel(true)
            return unavailableJdkOracle<T?>()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AssertionError(
                "interrupted while waiting for JDK regex oracle during " + operation, e
            )
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is JdkOracleInterruptedException) {
                return unavailableJdkOracle<T?>()
            }
            if (cause is StackOverflowError) {
                return unavailableJdkOracle<T?>()
            }
            if (cause is RuntimeException) {
                throw cause
            }
            if (cause is Error) {
                throw cause
            }
            throw AssertionError("JDK regex oracle failed with checked exception", cause)
        }
    }

    private fun <T> unavailableJdkOracle(): JdkOracleResult<T?> {
        return JdkOracleResult<T?>(false, null)
    }

    private fun jdkOracleTimeoutMillis(): Long {
        val configured = System.getProperty(JDK_ORACLE_TIMEOUT_PROPERTY)
        if (configured == null) {
            return DEFAULT_JDK_ORACLE_TIMEOUT_MILLIS
        }
        try {
            return max(1, configured.toLong())
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException(
                JDK_ORACLE_TIMEOUT_PROPERTY + " must be a positive integer", e
            )
        }
    }

    private fun interruptible(delegate: CharSequence): CharSequence {
        return if (delegate is InterruptibleCharSequence)
            delegate
        else
            InterruptibleCharSequence(delegate)
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw JdkOracleInterruptedException()
        }
    }

    private fun isExpectedReplacementException(exception: RuntimeException?): Boolean {
        return exception is IllegalArgumentException
                || exception is IndexOutOfBoundsException
    }

    private fun describeArray(values: Array<String?>): String? {
        return Arrays.stream<String?>(values).map<String?> { obj: String? -> FuzzSupport.javaStringLiteral() }.toList()
            .toString()
    }

    fun javaStringLiteral(value: String): String {
        val result = StringBuilder(value.length + 2)
        result.append('"')
        for (i in 0..<value.length) {
            val c = value.get(i)
            when (c) {
                '\\' -> result.append("\\\\")
                '"' -> result.append("\\\"")
                '\b' -> result.append("\\b")
                '\f' -> result.append("\\f")
                '\n' -> result.append("\\n")
                '\r' -> result.append("\\r")
                '\t' -> result.append("\\t")
                else -> {
                    if (c.code < 0x20 || Character.isSurrogate(c)) {
                        result.append(String.format("\\u%04x", c.code))
                    } else {
                        result.append(c)
                    }
                }
            }
        }
        result.append('"')
        return result.toString()
    }

    private fun isOverCompilerBudget(safeReException: PatternSyntaxException): Boolean {
        return safeReException.getDescription() == "compiled program too large"
    }

    fun consumeIndex(data: FuzzedDataProvider, input: String): Int {
        return data.consumeInt(0, input.length)
    }

    fun consumeRegion(data: FuzzedDataProvider, input: String): IntArray {
        val start = consumeIndex(data, input)
        val end = data.consumeInt(start, input.length)
        return intArrayOf(start, end)
    }

    fun consumeUnicodeHeavyString(data: FuzzedDataProvider, maxCodePoints: Int): String {
        val codePoints = data.consumeInt(0, maxCodePoints)
        val sb = StringBuilder(codePoints)
        for (i in 0..<codePoints) {
            when (data.consumeInt(0, 9)) {
                0 -> sb.append(data.consumeInt('a'.code, 'z'.code).toChar())
                1 -> sb.append(data.consumeInt('0'.code, '9'.code).toChar())
                2 -> sb.append('\n')
                3 -> sb.append('\r')
                4 -> sb.append('\u2028')
                5 -> sb.append('\u0301')
                6 -> sb.append(Character.toChars(data.consumeInt(0x10000, 0x10FFFF)))
                7 -> sb.append(data.consumeInt(0xD800, 0xDBFF).toChar())
                8 -> sb.append(data.consumeInt(0xDC00, 0xDFFF).toChar())
                9 -> sb.append(data.consumeInt(0x80, 0xFFFD).toChar())
                else -> throw AssertionError()
            }
        }
        return sb.toString()
    }

    @JvmRecord
    internal data class CompiledPattern(
        val regex: String?,
        val flags: Int,
        val safeRePattern: Pattern?,
        val jdkPattern: java.util.regex.Pattern?
    ) {
        fun matcher(input: CharSequence): MatcherPair {
            return MatcherPair(
                regex,
                flags,
                input.toString(),
                safeRePattern!!.matcher(input),
                jdkPattern!!.matcher(interruptible(input))
            )
        }

        fun split(input: CharSequence) {
            val inputText = input.toString()
            val jdk =
                runJdkOracle<Array<String?>?>("split", JdkOracleOperation { jdkPattern!!.split(interruptible(input)) })
            if (!jdk.available) {
                return
            }
            assertArrayEquals("split", inputText, safeRePattern!!.split(input), jdk.value!!)
        }

        fun split(input: CharSequence, limit: Int) {
            val inputText = input.toString()
            val jdk =
                runJdkOracle<Array<String?>?>(
                    "split(" + limit + ")",
                    JdkOracleOperation { jdkPattern!!.split(interruptible(input), limit) })
            if (!jdk.available) {
                return
            }
            assertArrayEquals(
                "split(" + limit + ")", inputText, safeRePattern!!.split(input, limit), jdk.value!!
            )
        }

        fun splitWithDelimiters(input: CharSequence) {
            val inputText = input.toString()
            val jdk =
                runJdkOracle<Array<String?>?>(
                    "splitWithDelimiters",
                    JdkOracleOperation { jdkPattern!!.splitWithDelimiters(interruptible(input), 0) })
            if (!jdk.available) {
                return
            }
            assertArrayEquals(
                "splitWithDelimiters", inputText, safeRePattern!!.splitWithDelimiters(input), jdk.value!!
            )
        }

        fun splitWithDelimiters(input: CharSequence, limit: Int) {
            val inputText = input.toString()
            val jdk =
                runJdkOracle<Array<String?>?>(
                    "splitWithDelimiters(" + limit + ")",
                    JdkOracleOperation { jdkPattern!!.splitWithDelimiters(interruptible(input), limit) })
            if (!jdk.available) {
                return
            }
            assertArrayEquals(
                "splitWithDelimiters(" + limit + ")",
                inputText,
                safeRePattern!!.splitWithDelimiters(input, limit),
                jdk.value!!
            )
        }

        private fun assertArrayEquals(operation: String?, input: String?, safeRe: Array<String?>, jdk: Array<String?>) {
            if (!safeRe.contentEquals(jdk)) {
                throw FuzzFinding.failure(
                    regex, flags, input, operation, "", "", describeArray(safeRe), describeArray(jdk)
                )
            }
        }
    }

    internal class MatcherPair(
        private val regex: String?,
        private val flags: Int,
        private var input: String,
        private val safeReMatcher: Matcher,
        private val jdkMatcher: java.util.regex.Matcher
    ) {
        private var lastReplacement: String? = null
        private var jdkOracleAvailable = true
        private val history = ArrayDeque<String?>()
        private var omittedEvents = 0

        private fun record(event: String) {
            if (history.size == 256) {
                history.removeFirst()
                omittedEvents++
            }
            history.addLast(if (event.length > 4096) event.substring(0, 4096) + " [truncated]" else event)
        }

        init {
            record("matcher(" + javaStringLiteral(input) + ")")
        }

        fun matches(): Boolean {
            val safeRe = safeReMatcher.matches()
            val jdk = runJdkOracle<Boolean?>("matches", safeRe, JdkOracleOperation { jdkMatcher.matches() })!!
            assertSame("matches", safeRe, jdk)
            if (safeRe) {
                assertMatchState("matches")
            }
            return safeRe
        }

        fun lookingAt(): Boolean {
            val safeRe = safeReMatcher.lookingAt()
            val jdk = runJdkOracle<Boolean?>("lookingAt", safeRe, JdkOracleOperation { jdkMatcher.lookingAt() })!!
            assertSame("lookingAt", safeRe, jdk)
            if (safeRe) {
                assertMatchState("lookingAt")
            }
            return safeRe
        }

        fun find(): Boolean {
            val safeRe = safeReMatcher.find()
            val jdk = runJdkOracle<Boolean?>("find", safeRe, JdkOracleOperation { jdkMatcher.find() })!!
            assertSame("find", safeRe, jdk)
            if (safeRe) {
                assertMatchState("find")
            }
            return safeRe
        }

        fun find(start: Int): Boolean {
            val safeRe = safeReMatcher.find(start)
            val jdk =
                runJdkOracle<Boolean?>("find(" + start + ")", safeRe, JdkOracleOperation { jdkMatcher.find(start) })!!
            assertSame("find(" + start + ")", safeRe, jdk)
            if (safeRe) {
                assertMatchState("find(" + start + ")")
            }
            return safeRe
        }

        fun reset(): MatcherPair {
            lastReplacement = null
            safeReMatcher.reset()
            runJdkOracle<java.util.regex.Matcher?>("reset", null, JdkOracleOperation { jdkMatcher.reset() })
            return this
        }

        fun reset(input: CharSequence): MatcherPair {
            this.input = input.toString()
            this.lastReplacement = null
            // Never re-enable the oracle: JDK configuration calls skipped while it was unavailable would
            // leave the two matchers out of sync.
            safeReMatcher.reset(input)
            runJdkOracle<java.util.regex.Matcher?>(
                "reset(" + javaStringLiteral(this.input) + ")",
                null,
                JdkOracleOperation { jdkMatcher.reset(interruptible(input)) })
            return this
        }

        fun hasMatch(): Boolean {
            val safeRe = safeReMatcher.hasMatch()
            val jdk = runJdkOracle<Boolean?>("hasMatch", safeRe, JdkOracleOperation { jdkMatcher.hasMatch() })!!
            // #931 also occurs after an explicit terminal find(), outside replacement loops.
            if (!safeRe && jdk && hasJdkExhaustedEmptyMatchState()) {
                assertSafeReExhaustedState()
                return false
            }
            assertSame("hasMatch", safeRe, jdk)
            return safeRe
        }

        fun groupCount(): Int {
            val safeRe = safeReMatcher.groupCount()
            val jdk = runJdkOracle<Int?>("groupCount", safeRe, JdkOracleOperation { jdkMatcher.groupCount() })!!
            assertSame("groupCount", safeRe, jdk)
            return safeRe
        }

        fun group(): String? {
            val safeRe = safeReMatcher.group()
            val jdk = runJdkOracle<String?>("group", safeRe, JdkOracleOperation { jdkMatcher.group() })
            assertSame("group", safeRe, jdk)
            return safeRe
        }

        fun group(group: Int): String? {
            val safeRe = safeReMatcher.group(group)
            val jdk =
                runJdkOracle<String?>("group(" + group + ")", safeRe, JdkOracleOperation { jdkMatcher.group(group) })
            assertSame("group(" + group + ")", safeRe, jdk)
            return safeRe
        }

        fun group(name: String): String? {
            val safeRe = safeReMatcher.group(name)
            val jdk =
                runJdkOracle<String?>("group(" + name + ")", safeRe, JdkOracleOperation { jdkMatcher.group(name) })
            assertSame("group(" + name + ")", safeRe, jdk)
            return safeRe
        }

        fun start(): Int {
            val safeRe = safeReMatcher.start()
            val jdk = runJdkOracle<Int?>("start", safeRe, JdkOracleOperation { jdkMatcher.start() })!!
            assertSame("start", safeRe, jdk)
            return safeRe
        }

        fun start(group: Int): Int {
            val safeRe = safeReMatcher.start(group)
            val jdk =
                runJdkOracle<Int?>("start(" + group + ")", safeRe, JdkOracleOperation { jdkMatcher.start(group) })!!
            assertSame("start(" + group + ")", safeRe, jdk)
            return safeRe
        }

        fun start(name: String): Int {
            val safeRe = safeReMatcher.start(name)
            val jdk = runJdkOracle<Int?>("start(" + name + ")", safeRe, JdkOracleOperation { jdkMatcher.start(name) })!!
            assertSame("start(" + name + ")", safeRe, jdk)
            return safeRe
        }

        fun end(): Int {
            val safeRe = safeReMatcher.end()
            val jdk = runJdkOracle<Int?>("end", safeRe, JdkOracleOperation { jdkMatcher.end() })!!
            assertSame("end", safeRe, jdk)
            return safeRe
        }

        fun end(group: Int): Int {
            val safeRe = safeReMatcher.end(group)
            val jdk = runJdkOracle<Int?>("end(" + group + ")", safeRe, JdkOracleOperation { jdkMatcher.end(group) })!!
            assertSame("end(" + group + ")", safeRe, jdk)
            return safeRe
        }

        fun end(name: String): Int {
            val safeRe = safeReMatcher.end(name)
            val jdk = runJdkOracle<Int?>("end(" + name + ")", safeRe, JdkOracleOperation { jdkMatcher.end(name) })!!
            assertSame("end(" + name + ")", safeRe, jdk)
            return safeRe
        }

        fun region(start: Int, end: Int): MatcherPair {
            safeReMatcher.region(start, end)
            runJdkOracle<java.util.regex.Matcher?>(
                "region(" + start + "," + end + ")",
                null,
                JdkOracleOperation { jdkMatcher.region(start, end) })
            return this
        }

        fun regionStart(): Int {
            val safeRe = safeReMatcher.regionStart()
            val jdk = runJdkOracle<Int?>("regionStart", safeRe, JdkOracleOperation { jdkMatcher.regionStart() })!!
            assertSame("regionStart", safeRe, jdk)
            return safeRe
        }

        fun regionEnd(): Int {
            val safeRe = safeReMatcher.regionEnd()
            val jdk = runJdkOracle<Int?>("regionEnd", safeRe, JdkOracleOperation { jdkMatcher.regionEnd() })!!
            assertSame("regionEnd", safeRe, jdk)
            return safeRe
        }

        fun useAnchoringBounds(value: Boolean): MatcherPair {
            safeReMatcher.useAnchoringBounds(value)
            runJdkOracle<java.util.regex.Matcher?>(
                "useAnchoringBounds(" + value + ")", null, JdkOracleOperation { jdkMatcher.useAnchoringBounds(value) })
            return this
        }

        fun useTransparentBounds(value: Boolean): MatcherPair {
            safeReMatcher.useTransparentBounds(value)
            runJdkOracle<java.util.regex.Matcher?>(
                "useTransparentBounds(" + value + ")",
                null,
                JdkOracleOperation { jdkMatcher.useTransparentBounds(value) })
            return this
        }

        fun hasAnchoringBounds(): Boolean {
            val safeRe = safeReMatcher.hasAnchoringBounds()
            val jdk =
                runJdkOracle<Boolean?>(
                    "hasAnchoringBounds",
                    safeRe,
                    JdkOracleOperation { jdkMatcher.hasAnchoringBounds() })!!
            assertSame("hasAnchoringBounds", safeRe, jdk)
            return safeRe
        }

        fun hasTransparentBounds(): Boolean {
            val safeRe = safeReMatcher.hasTransparentBounds()
            val jdk =
                runJdkOracle<Boolean?>(
                    "hasTransparentBounds",
                    safeRe,
                    JdkOracleOperation { jdkMatcher.hasTransparentBounds() })!!
            assertSame("hasTransparentBounds", safeRe, jdk)
            return safeRe
        }

        fun replaceAll(replacement: String?): Boolean {
            return assertSameReplacementOutcome(
                "replaceAll",
                replacement,
                StringOperation { safeReMatcher.replaceAll(replacement) },
                StringOperation {
                    runJdkOracle<String?>(
                        "replaceAll",
                        null,
                        JdkOracleOperation { jdkMatcher.replaceAll(replacement) })
                })
        }

        fun replaceAll(replacer: Function<MatchResult?, String?>): Boolean {
            return assertSameReplacementOutcome(
                "replaceAll(function)",
                null,
                StringOperation { safeReMatcher.replaceAll(replacer) },
                StringOperation {
                    runJdkOracle<String?>(
                        "replaceAll(function)",
                        null,
                        JdkOracleOperation { jdkMatcher.replaceAll(replacer) })
                })
        }

        fun replaceFirst(replacement: String): Boolean {
            return assertSameReplacementOutcome(
                "replaceFirst",
                replacement,
                StringOperation { safeReMatcher.replaceFirst(replacement) },
                StringOperation {
                    runJdkOracle<String?>(
                        "replaceFirst",
                        null,
                        JdkOracleOperation { jdkMatcher.replaceFirst(replacement) })
                })
        }

        fun hasReplacementMatchState(): Boolean {
            var safeRe: Boolean
            try {
                safeReMatcher.start()
                safeRe = true
            } catch (e: IllegalStateException) {
                safeRe = false
            }
            val jdk =
                runJdkOracle<Boolean?>(
                    "replacement match state",
                    safeRe,
                    JdkOracleOperation {
                        try {
                            val unused: Int = jdkMatcher.start()
                            return@runJdkOracle true
                        } catch (e: java.lang.IllegalStateException) {
                            return@runJdkOracle false
                        }
                    })!!
            assertSame("replacement match state", safeRe, jdk)
            if (safeRe) {
                assertMatchState("replacement match state")
            }
            return safeRe
        }

        fun appendReplacement(output: StringBuilder, replacement: String): Boolean {
            val safeRe = StringBuilder()
            val jdk = StringBuilder()
            lastReplacement = replacement
            val completed =
                assertSameReplacementOutcome(
                    "appendReplacement",
                    replacement,
                    StringOperation {
                        safeReMatcher.appendReplacement(safeRe, replacement)
                        safeRe.toString()
                    },
                    StringOperation {
                        runJdkOracle<java.util.regex.Matcher?>(
                            "appendReplacement",
                            null,
                            JdkOracleOperation { jdkMatcher.appendReplacement(jdk, replacement) })
                        jdk.toString()
                    })
            if (completed) {
                output.append(safeRe)
            }
            return completed
        }

        fun appendReplacement(output: StringBuffer, replacement: String): Boolean {
            val safeRe = StringBuffer()
            val jdk = StringBuffer()
            lastReplacement = replacement
            val completed =
                assertSameReplacementOutcome(
                    "appendReplacement",
                    replacement,
                    StringOperation {
                        safeReMatcher.appendReplacement(safeRe, replacement)
                        safeRe.toString()
                    },
                    StringOperation {
                        runJdkOracle<java.util.regex.Matcher?>(
                            "appendReplacement",
                            null,
                            JdkOracleOperation { jdkMatcher.appendReplacement(jdk, replacement) })
                        jdk.toString()
                    })
            if (completed) {
                output.append(safeRe)
            }
            return completed
        }

        fun appendTail(output: StringBuilder) {
            val safeRe = StringBuilder()
            val jdk = StringBuilder()
            safeReMatcher.appendTail(safeRe)
            runJdkOracle<StringBuilder?>("appendTail", null, JdkOracleOperation { jdkMatcher.appendTail(jdk) })
            if (!jdkOracleAvailable) {
                output.append(safeRe)
                return
            }
            assertSame("appendTail", safeRe.toString(), jdk.toString())
            output.append(safeRe)
        }

        fun appendTail(output: StringBuffer) {
            val safeRe = StringBuffer()
            val jdk = StringBuffer()
            safeReMatcher.appendTail(safeRe)
            runJdkOracle<StringBuffer?>("appendTail", null, JdkOracleOperation { jdkMatcher.appendTail(jdk) })
            if (!jdkOracleAvailable) {
                output.append(safeRe)
                return
            }
            assertSame("appendTail", safeRe.toString(), jdk.toString())
            output.append(safeRe)
        }

        fun toMatchResult() {
            val safeRe = safeReMatcher.toMatchResult()
            val jdk = runJdkOracle<MatchResult>(
                "toMatchResult",
                safeRe,
                JdkOracleOperation { jdkMatcher.toMatchResult() })!!
            assertMatchResult("toMatchResult", safeRe, jdk)
        }

        private fun assertMatchState(operation: String?) {
            assertSame(
                operation + ".start",
                safeReMatcher.start(),
                runJdkOracle<Int?>(
                    operation + ".start",
                    safeReMatcher.start(),
                    JdkOracleOperation { jdkMatcher.start() })
            )
            assertSame(
                operation + ".end",
                safeReMatcher.end(),
                runJdkOracle<Int?>(operation + ".end", safeReMatcher.end(), JdkOracleOperation { jdkMatcher.end() })
            )
            val groupCount = groupCount()
            for (i in 0..groupCount) {
                group(i)
                start(i)
                end(i)
            }
        }

        private fun assertMatchResult(operation: String?, safeRe: MatchResult, jdk: MatchResult) {
            assertSame(operation + ".start", safeRe.start(), jdk.start())
            assertSame(operation + ".end", safeRe.end(), jdk.end())
            val groupCount = safeRe.groupCount()
            assertSame(operation + ".groupCount", groupCount, jdk.groupCount())
            for (i in 0..groupCount) {
                assertSame(operation + ".group(" + i + ")", safeRe.group(i), jdk.group(i))
                assertSame(operation + ".start(" + i + ")", safeRe.start(i), jdk.start(i))
                assertSame(operation + ".end(" + i + ")", safeRe.end(i), jdk.end(i))
            }
        }

        private fun assertSameReplacementOutcome(
            operation: String,
            replacement: String?,
            safeReOperation: StringOperation,
            jdkOperation: StringOperation
        ): Boolean {
            val safeRe = OperationResult.Companion.capture(safeReOperation)
            if (!jdkOracleAvailable) {
                return false
            }
            val jdk = OperationResult.Companion.capture(jdkOperation)
            if (!jdkOracleAvailable) {
                return false
            }
            if (safeRe.throwable == null && jdk.throwable == null) {
                assertSame(operation, replacement, safeRe.value, jdk.value)
                if (operation == "replaceAll" || operation == "replaceAll(function)") {
                    assertExhaustedReplacementState()
                }
                return true
            }
            if (safeRe.throwable != null && jdk.throwable != null && isExpectedReplacementException(safeRe.throwable)
                && isExpectedReplacementException(jdk.throwable)
            ) {
                return false
            }
            throw divergence(operation, replacement, safeRe.describe(), jdk.describe())
        }

        private fun assertSafeReExhaustedState() {
            Assertions.assertThat(safeReMatcher.hasMatch()).`as`("exhausted state for %s", regex).isFalse()
            Assertions.assertThatThrownBy(ThrowingCallable { safeReMatcher.group() })
                .isInstanceOf(IllegalStateException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { safeReMatcher.start() })
                .isInstanceOf(IllegalStateException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { safeReMatcher.end() })
                .isInstanceOf(IllegalStateException::class.java)
            groupCount()
            for (group in 0..safeReMatcher.groupCount()) {
                val index = group
                Assertions.assertThatThrownBy(ThrowingCallable { safeReMatcher.group(index) })
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThatThrownBy(ThrowingCallable { safeReMatcher.start(index) })
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThatThrownBy(ThrowingCallable { safeReMatcher.end(index) })
                    .isInstanceOf(IllegalStateException::class.java)
            }
        }

        private fun hasJdkExhaustedEmptyMatchState(): Boolean {
            val defect = OracleDefects.exhaustedEmptyMatch(jdkMatcher)
            if (defect) OracleDefects.record()
            return defect
        }

        private fun assertExhaustedReplacementState() {
            assertSafeReExhaustedState()
            // #931: exclude only the JDK's half-cleared terminal-empty-match state, after a
            // successful replaceAll. SafeRE must still satisfy every exhausted-state assertion.
            // Do not waive replacement output, replaceFirst state, or other JDK state shapes.
            if (!hasJdkExhaustedEmptyMatchState()) {
                Assertions.assertThat(jdkMatcher.hasMatch()).isFalse()
                Assertions.assertThatThrownBy(ThrowingCallable { jdkMatcher.group() })
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThatThrownBy(ThrowingCallable { jdkMatcher.start() })
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThatThrownBy(ThrowingCallable { jdkMatcher.end() })
                    .isInstanceOf(IllegalStateException::class.java)
            }
        }

        private fun divergence(
            operation: String?, replacement: String?, safeRe: Any?, jdk: Any?
        ): AssertionError {
            return FuzzFinding.failure(
                regex,
                flags,
                input,
                operation,
                ("region="
                        + safeReMatcher.regionStart()
                        + ":"
                        + safeReMatcher.regionEnd()
                        + " anchoring="
                        + safeReMatcher.hasAnchoringBounds()
                        + " transparent="
                        + safeReMatcher.hasTransparentBounds()
                        + " replacement="
                        + (if (replacement == null) "null" else javaStringLiteral(replacement))),
                "omittedEvents=" + omittedEvents + " " + history,
                Objects.toString(safeRe),
                Objects.toString(jdk)
            )
        }

        private fun assertSame(operation: String?, safeRe: Any?, jdk: Any?) {
            assertSame(operation, lastReplacement, safeRe, jdk)
        }

        private fun assertSame(operation: String?, replacement: String?, safeRe: Any?, jdk: Any?) {
            record(
                (operation
                        + " SafeRE="
                        + javaStringLiteral(Objects.toString(safeRe))
                        + " JDK="
                        + javaStringLiteral(Objects.toString(jdk)))
            )
            if (safeRe != jdk) {
                throw divergence(operation, replacement, safeRe, jdk)
            }
        }

        private fun <T> runJdkOracle(operation: String, fallback: T?, jdkOperation: JdkOracleOperation<T?>): T? {
            record(operation)
            if (!jdkOracleAvailable) {
                return fallback
            }
            val result = runJdkOracle<T?>(operation, jdkOperation)
            if (!result.available) {
                jdkOracleAvailable = false
                return fallback
            }
            return result.value
        }
    }

    @JvmRecord
    private data class OperationResult<T>(val value: T?, val throwable: RuntimeException?) {
        fun describe(): String? {
            return if (throwable == null)
                Objects.toString(value)
            else
                throwable.javaClass.getSimpleName() + ": " + throwable.message
        }

        companion object {
            fun capture(operation: StringOperation): OperationResult<String?> {
                try {
                    return OperationResult<String?>(operation.run(), null)
                } catch (e: RuntimeException) {
                    return OperationResult<String?>(null, e)
                }
            }
        }
    }

    private interface StringOperation {
        fun run(): String?
    }

    private interface JdkOracleOperation<T> {
        fun run(): T?
    }

    @JvmRecord
    private data class JdkOracleResult<T>(val available: Boolean, val value: T?)

    @JvmRecord
    private data class InterruptibleCharSequence(val delegate: CharSequence?) : CharSequence {
        override fun length(): Int {
            checkInterrupted()
            return delegate!!.length
        }

        override fun charAt(index: Int): Char {
            checkInterrupted()
            return delegate!!.get(index)
        }

        override fun subSequence(start: Int, end: Int): CharSequence {
            checkInterrupted()
            return InterruptibleCharSequence(delegate!!.subSequence(start, end))
        }

        override fun toString(): String {
            return delegate.toString()
        }
    }

    private object JdkOracleInterruptedException : RuntimeException() {
        private const val serialVersionUID = 1L
    }
}
