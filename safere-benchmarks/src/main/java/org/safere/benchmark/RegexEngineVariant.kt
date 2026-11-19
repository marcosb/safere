// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import org.openjdk.jmh.infra.Blackhole
import org.safere.*
import org.safere.benchmark.DeclarativeBenchmarkPlan.EngineDeclaration
import org.safere.benchmark.RegexEngineVariant.Companion.string
import org.safere.benchmark.RegexEngineVariant.Companion.utf8
import org.safere.crosscheck.Pattern.Companion.compile
import org.safere.re2ffm.RE2FfmPattern
import java.lang.AutoCloseable
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.List
import java.util.Map

/** Java-side regex execution variants and their representation/timing boundaries.  */
internal enum class RegexEngineVariant(
    private val id: String,
    reportEngine: String,
    patternProfile: String,
    inputRepresentation: InputRepresentation,
    capabilities: EnumSet<EngineCapability>
) {
    SAFERE_STRING(
        "safere-string",
        "safere",
        "java",
        InputRepresentation.JAVA_STRING,
        RegexEngineVariant.Companion.allCapabilities()
    ) {
        override fun compile(regex: String?): CompiledRegex {
            val pattern: Pattern = compile(regex)
            return object : CompiledRegex {
                override fun find(input: RegexInput): Boolean {
                    return pattern.matcher(RegexEngineVariant.Companion.string(input)).find()
                }

                override fun matches(input: RegexInput): Boolean {
                    return pattern.matcher(RegexEngineVariant.Companion.string(input)).matches()
                }

                override fun matcher(input: RegexInput): MatchCursor {
                    val matcher: Matcher = pattern.matcher(RegexEngineVariant.Companion.string(input))
                    return object : MatchCursor {
                        override fun find(): Boolean {
                            return matcher.find()
                        }

                        override fun matches(): Boolean {
                            return matcher.matches()
                        }

                        override fun lookingAt(): Boolean {
                            return matcher.lookingAt()
                        }

                        override fun group(group: Int): String? {
                            return matcher.group(group)
                        }

                        override fun start(group: Int): Int {
                            return matcher.start(group)
                        }

                        override fun end(group: Int): Int {
                            return matcher.end(group)
                        }

                        override fun reset() {
                            matcher.reset()
                        }

                        override fun region(start: Int, end: Int) {
                            matcher.region(start, end)
                        }

                        override fun appendReplacement(result: StringBuilder, replacement: String?) {
                            matcher.appendReplacement(result, replacement)
                        }

                        override fun appendTail(result: StringBuilder) {
                            matcher.appendTail(result)
                        }
                    }
                }

                override fun replaceFirst(input: RegexInput, replacement: PreparedReplacement): ReplacementOutput {
                    return StringReplacementOutput(
                        pattern.matcher(RegexEngineVariant.Companion.string(input))
                            .replaceFirst(RegexEngineVariant.Companion.string(replacement))
                    )
                }

                override fun replaceAll(input: RegexInput, replacement: PreparedReplacement): ReplacementOutput {
                    return StringReplacementOutput(
                        pattern.matcher(RegexEngineVariant.Companion.string(input))
                            .replaceAll(RegexEngineVariant.Companion.string(replacement))
                    )
                }

                override fun split(input: RegexInput, limit: Int): Array<String?>? {
                    return pattern.split(RegexEngineVariant.Companion.string(input), limit)
                }
            }
        }
    },
    SAFERE_UTF8(
        "safere-utf8",
        "safere_utf8",
        "java",
        InputRepresentation.PREEXISTING_UTF8,
        EnumSet.of<EngineCapability?>(
            EngineCapability.FIND,
            EngineCapability.MATCHES,
            EngineCapability.LOOKING_AT,
            EngineCapability.GROUP_PARTICIPATION,
            EngineCapability.GROUP_TEXT,
            EngineCapability.REPLACE,
            EngineCapability.APPEND_REPLACEMENT,
            EngineCapability.MATCHER_RESET,
            EngineCapability.REGIONS
        )
    ) {
        override fun compile(regex: String?): CompiledRegex {
            val pattern: Pattern = compile(regex)
            return object : CompiledRegex {
                override fun find(input: RegexInput): Boolean {
                    return pattern.find(RegexEngineVariant.Companion.utf8(input))
                }

                override fun matches(input: RegexInput): Boolean {
                    return pattern.matcher(RegexEngineVariant.Companion.utf8(input)).matches()
                }

                override fun matcher(input: RegexInput): MatchCursor {
                    val matcher: Utf8Matcher = pattern.matcher(RegexEngineVariant.Companion.utf8(input))
                    return object : MatchCursor {
                        override fun find(): Boolean {
                            return matcher.find()
                        }

                        override fun matches(): Boolean {
                            return matcher.matches()
                        }

                        override fun lookingAt(): Boolean {
                            return matcher.lookingAt()
                        }

                        override fun group(group: Int): String? {
                            val start = matcher.start(group)
                            if (start < 0) {
                                return null
                            }
                            val source: Utf8RegexInput = input as Utf8RegexInput
                            return String(
                                source.bytes,
                                source.offset + start,
                                matcher.end(group) - start,
                                StandardCharsets.UTF_8
                            )
                        }

                        override fun start(group: Int): Int {
                            return matcher.start(group)
                        }

                        override fun end(group: Int): Int {
                            return matcher.end(group)
                        }

                        override fun reset() {
                            matcher.reset()
                        }

                        override fun region(start: Int, end: Int) {
                            matcher.region(start, end)
                        }
                    }
                }

                override fun replaceFirst(input: RegexInput, replacement: PreparedReplacement): ReplacementOutput {
                    val sink = ByteArraySink()
                    pattern.matcher(RegexEngineVariant.Companion.utf8(input))
                        .replaceFirst(sink, RegexEngineVariant.Companion.utf8(replacement))
                    return sink.output()
                }

                override fun replaceAll(input: RegexInput, replacement: PreparedReplacement): ReplacementOutput {
                    val sink = ByteArraySink()
                    pattern.matcher(RegexEngineVariant.Companion.utf8(input))
                        .replaceAll(sink, RegexEngineVariant.Companion.utf8(replacement))
                    return sink.output()
                }

                override fun manualReplaceAll(
                    input: RegexInput, replacement: PreparedReplacement
                ): ReplacementOutput {
                    return RegexEngineVariant.Companion.manualReplaceUtf8(
                        pattern.matcher(
                            RegexEngineVariant.Companion.utf8(
                                input
                            )
                        ), RegexEngineVariant.Companion.utf8(replacement)
                    )
                }
            }
        }
    },
    SAFERE_UTF8_VECTOR(
        "safere-utf8-vector",
        "safere_utf8_vector",
        "java",
        InputRepresentation.PREEXISTING_UTF8,
        RegexEngineVariant.SAFERE_UTF8.capabilities()
    ) {
        override fun compile(regex: String?): CompiledRegex? {
            return RegexEngineVariant.SAFERE_UTF8.compile(regex)
        }
    },
    JDK_STRING(
        "jdk-string",
        "jdk",
        "java",
        InputRepresentation.JAVA_STRING,
        RegexEngineVariant.Companion.allCapabilities()
    ) {
        override fun compile(regex: String): CompiledRegex {
            val pattern = java.util.regex.Pattern.compile(regex)
            return object : CompiledRegex {
                override fun find(input: RegexInput): Boolean {
                    return pattern.matcher(RegexEngineVariant.Companion.string(input)).find()
                }

                override fun matches(input: RegexInput): Boolean {
                    return pattern.matcher(RegexEngineVariant.Companion.string(input)).matches()
                }

                override fun matcher(input: RegexInput): MatchCursor {
                    val matcher = pattern.matcher(RegexEngineVariant.Companion.string(input))
                    return object : MatchCursor {
                        override fun find(): Boolean {
                            return matcher.find()
                        }

                        override fun matches(): Boolean {
                            return matcher.matches()
                        }

                        override fun lookingAt(): Boolean {
                            return matcher.lookingAt()
                        }

                        override fun group(group: Int): String? {
                            return matcher.group(group)
                        }

                        override fun start(group: Int): Int {
                            return matcher.start(group)
                        }

                        override fun end(group: Int): Int {
                            return matcher.end(group)
                        }

                        override fun reset() {
                            matcher.reset()
                        }

                        override fun region(start: Int, end: Int) {
                            matcher.region(start, end)
                        }

                        override fun appendReplacement(result: StringBuilder, replacement: String) {
                            matcher.appendReplacement(result, replacement)
                        }

                        override fun appendTail(result: StringBuilder) {
                            matcher.appendTail(result)
                        }
                    }
                }

                override fun replaceFirst(input: RegexInput, replacement: PreparedReplacement): ReplacementOutput {
                    return StringReplacementOutput(
                        pattern.matcher(RegexEngineVariant.Companion.string(input))
                            .replaceFirst(RegexEngineVariant.Companion.string(replacement))
                    )
                }

                override fun replaceAll(input: RegexInput, replacement: PreparedReplacement): ReplacementOutput {
                    return StringReplacementOutput(
                        pattern.matcher(RegexEngineVariant.Companion.string(input))
                            .replaceAll(RegexEngineVariant.Companion.string(replacement))
                    )
                }

                override fun split(input: RegexInput, limit: Int): Array<String?>? {
                    return pattern.split(RegexEngineVariant.Companion.string(input), limit)
                }
            }
        }
    },
    RE2J_STRING(
        "re2j-string",
        "re2j",
        "re2",
        InputRepresentation.JAVA_STRING,
        RegexEngineVariant.Companion.re2jCapabilities()
    ) {
        override fun compile(regex: String): CompiledRegex {
            val pattern = com.google.re2j.Pattern.compile(regex)
            return object : CompiledRegex {
                override fun find(input: RegexInput): Boolean {
                    return pattern.matcher(RegexEngineVariant.Companion.string(input)).find()
                }

                override fun matches(input: RegexInput): Boolean {
                    return pattern.matcher(RegexEngineVariant.Companion.string(input)).matches()
                }

                override fun matcher(input: RegexInput): MatchCursor {
                    val matcher: com.google.re2j.Matcher = pattern.matcher(RegexEngineVariant.Companion.string(input))
                    return object : MatchCursor {
                        override fun find(): Boolean {
                            return matcher.find()
                        }

                        override fun matches(): Boolean {
                            return matcher.matches()
                        }

                        override fun lookingAt(): Boolean {
                            return matcher.lookingAt()
                        }

                        override fun group(group: Int): String? {
                            return matcher.group(group)
                        }

                        override fun start(group: Int): Int {
                            return matcher.start(group)
                        }

                        override fun end(group: Int): Int {
                            return matcher.end(group)
                        }

                        override fun reset() {
                            matcher.reset()
                        }

                        override fun appendReplacement(result: StringBuilder?, replacement: String) {
                            matcher.appendReplacement(result, replacement)
                        }

                        override fun appendTail(result: StringBuilder) {
                            matcher.appendTail(result)
                        }
                    }
                }

                override fun replaceFirst(input: RegexInput, replacement: PreparedReplacement): ReplacementOutput {
                    return StringReplacementOutput(
                        pattern.matcher(RegexEngineVariant.Companion.string(input))
                            .replaceFirst(RegexEngineVariant.Companion.string(replacement))
                    )
                }

                override fun replaceAll(input: RegexInput, replacement: PreparedReplacement): ReplacementOutput {
                    return StringReplacementOutput(
                        pattern.matcher(RegexEngineVariant.Companion.string(input))
                            .replaceAll(RegexEngineVariant.Companion.string(replacement))
                    )
                }

                override fun split(input: RegexInput, limit: Int): Array<String?>? {
                    return pattern.split(RegexEngineVariant.Companion.string(input), limit)
                }
            }
        }
    },
    RE2_FFM_STRING_CONVERSION(
        "re2-ffm-string-conversion",
        "re2_ffm",
        "re2",
        InputRepresentation.JAVA_STRING_WITH_TIMED_UTF8_CONVERSION,
        RegexEngineVariant.Companion.ffmCapabilities()
    ) {
        override fun compile(regex: String?): CompiledRegex {
            val pattern: RE2FfmPattern = compile(regex)
            return object : CompiledRegex {
                override fun find(input: RegexInput): Boolean {
                    return pattern.matcher(RegexEngineVariant.Companion.string(input)).find()
                }

                override fun matches(input: RegexInput): Boolean {
                    return pattern.matcher(RegexEngineVariant.Companion.string(input)).matches()
                }

                override fun matcher(input: RegexInput): MatchCursor {
                    val matcher = pattern.matcher(RegexEngineVariant.Companion.string(input))
                    return object : MatchCursor {
                        override fun find(): Boolean {
                            return matcher.find()
                        }

                        override fun matches(): Boolean {
                            return matcher.matches()
                        }

                        override fun lookingAt(): Boolean {
                            return matcher.lookingAt()
                        }

                        override fun region(start: Int, end: Int) {
                            matcher.region(start, end)
                        }

                        override fun group(group: Int): String? {
                            return matcher.group(group)
                        }

                        override fun start(group: Int): Int {
                            return matcher.start(group)
                        }

                        override fun end(group: Int): Int {
                            return matcher.end(group)
                        }

                        override fun reset() {
                            matcher.reset()
                        }
                    }
                }

                override fun replaceFirst(input: RegexInput, replacement: PreparedReplacement): ReplacementOutput {
                    return StringReplacementOutput(
                        pattern.matcher(RegexEngineVariant.Companion.string(input))
                            .replaceFirst(RegexEngineVariant.Companion.string(replacement))
                    )
                }

                override fun replaceAll(input: RegexInput, replacement: PreparedReplacement): ReplacementOutput {
                    return StringReplacementOutput(
                        pattern.matcher(RegexEngineVariant.Companion.string(input))
                            .replaceAll(RegexEngineVariant.Companion.string(replacement))
                    )
                }

                override fun split(input: RegexInput, limit: Int): Array<String?> {
                    return pattern.split(RegexEngineVariant.Companion.string(input), limit)
                }
            }
        }
    };

    private val reportEngine: String?
    private val patternProfile: String?
    private val inputRepresentation: InputRepresentation?
    private val capabilities: Long

    init {
        this.reportEngine = reportEngine
        this.patternProfile = patternProfile
        this.inputRepresentation = inputRepresentation
        var capabilityBits: Long = 0
        for (capability in capabilities) {
            capabilityBits = capabilityBits or capability.bit().toLong()
        }
        this.capabilities = capabilityBits
    }

    fun id(): String {
        return id
    }

    fun reportEngine(): String? {
        return reportEngine
    }

    fun scanProvider(): String {
        return if (this === RegexEngineVariant.SAFERE_UTF8_VECTOR) "vector" else "default"
    }

    fun validateScanProvider() {
        if (this !== RegexEngineVariant.SAFERE_UTF8 && this !== RegexEngineVariant.SAFERE_UTF8_VECTOR) {
            return
        }
        val property = "org.safere.experimental.vectorScanProvider"
        val requested = System.getProperty(property, "").trim { it <= ' ' }
        val matches =
            if (this === RegexEngineVariant.SAFERE_UTF8_VECTOR) (requested == "vector") else requested.isEmpty()
        check(matches) {
            if (this === RegexEngineVariant.SAFERE_UTF8_VECTOR)
                id + " requires -D" + property + "=vector"
            else
                id + " requires default provider selection; leave -D" + property + " unset"
        }
    }

    fun patternProfile(): String? {
        return patternProfile
    }

    fun inputRepresentation(): InputRepresentation? {
        return inputRepresentation
    }

    fun capabilities(): EnumSet<EngineCapability> {
        val result = EnumSet.noneOf<EngineCapability?>(EngineCapability::class.java)
        for (capability in EngineCapability.entries) {
            if ((capabilities and capability.bit().toLong()) != 0L) {
                result.add(capability)
            }
        }
        return result
    }

    fun declaration(): EngineDeclaration {
        val features =
            EnumSet.noneOf<DeclarativeBenchmarkPlan.Feature?>(DeclarativeBenchmarkPlan.Feature::class.java)
        for (capability in capabilities()) {
            when (capability) {
                EngineCapability.COMPILE -> {}
                EngineCapability.FIND -> features.add(DeclarativeBenchmarkPlan.Feature.FIND)
                EngineCapability.MATCHES -> features.add(DeclarativeBenchmarkPlan.Feature.MATCHES)
                EngineCapability.LOOKING_AT -> features.add(DeclarativeBenchmarkPlan.Feature.LOOKING_AT)
                EngineCapability.GROUP_PARTICIPATION -> features.add(DeclarativeBenchmarkPlan.Feature.CAPTURE_PARTICIPATION)
                EngineCapability.GROUP_TEXT -> features.add(DeclarativeBenchmarkPlan.Feature.CAPTURE_TEXT)
                EngineCapability.REPLACE -> {
                    features.add(DeclarativeBenchmarkPlan.Feature.REPLACE)
                    features.add(DeclarativeBenchmarkPlan.Feature.NUMBERED_REPLACEMENT)
                }

                EngineCapability.APPEND_REPLACEMENT -> {
                    features.add(DeclarativeBenchmarkPlan.Feature.APPEND_REPLACEMENT)
                    features.add(DeclarativeBenchmarkPlan.Feature.NUMBERED_REPLACEMENT)
                }

                EngineCapability.SPLIT -> features.add(DeclarativeBenchmarkPlan.Feature.SPLIT)
                EngineCapability.MATCHER_RESET -> features.add(DeclarativeBenchmarkPlan.Feature.MATCHER_STATE)
                EngineCapability.REGIONS -> {
                    features.add(DeclarativeBenchmarkPlan.Feature.MATCHER_STATE)
                    features.add(DeclarativeBenchmarkPlan.Feature.REGIONS)
                }
            }
        }
        when (this) {
            RegexEngineVariant.SAFERE_STRING -> {
                features.add(DeclarativeBenchmarkPlan.Feature.DFA_CACHE)
                features.add(DeclarativeBenchmarkPlan.Feature.DIAGNOSTICS)
                features.add(DeclarativeBenchmarkPlan.Feature.FLAGGED_COMPILE)
                features.add(DeclarativeBenchmarkPlan.Feature.JAVA_CHARACTER_CLASS)
                features.add(DeclarativeBenchmarkPlan.Feature.LINEAR_TIME)
                features.add(DeclarativeBenchmarkPlan.Feature.PATTERN_SET)
                features.add(DeclarativeBenchmarkPlan.Feature.RETAINED_HEAP)
            }

            RegexEngineVariant.SAFERE_UTF8, RegexEngineVariant.SAFERE_UTF8_VECTOR -> {
                features.add(DeclarativeBenchmarkPlan.Feature.DIAGNOSTICS)
                features.add(DeclarativeBenchmarkPlan.Feature.LINEAR_TIME)
                features.add(DeclarativeBenchmarkPlan.Feature.MATCHER_STATE)
                features.add(DeclarativeBenchmarkPlan.Feature.REGIONS)
                features.add(DeclarativeBenchmarkPlan.Feature.UTF8_INPUT)
                features.add(DeclarativeBenchmarkPlan.Feature.UTF8_REPLACEMENT)
            }

            RegexEngineVariant.JDK_STRING -> {
                features.add(DeclarativeBenchmarkPlan.Feature.FLAGGED_COMPILE)
                features.add(DeclarativeBenchmarkPlan.Feature.JAVA_CHARACTER_CLASS)
                features.add(DeclarativeBenchmarkPlan.Feature.RETAINED_HEAP)
            }

            RegexEngineVariant.RE2J_STRING -> {
                features.add(DeclarativeBenchmarkPlan.Feature.FLAGGED_COMPILE)
                features.add(DeclarativeBenchmarkPlan.Feature.LINEAR_TIME)
                features.add(DeclarativeBenchmarkPlan.Feature.RETAINED_HEAP)
            }

            RegexEngineVariant.RE2_FFM_STRING_CONVERSION -> features.add(DeclarativeBenchmarkPlan.Feature.LINEAR_TIME)
        }
        val representation =
            when (inputRepresentation) {
                InputRepresentation.JAVA_STRING -> DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING
                InputRepresentation.PREEXISTING_UTF8 -> DeclarativeBenchmarkPlan.InputRepresentation.PREEXISTING_UTF8
                InputRepresentation.JAVA_STRING_WITH_TIMED_UTF8_CONVERSION -> DeclarativeBenchmarkPlan.InputRepresentation.JAVA_STRING_WITH_TIMED_UTF8_CONVERSION
            }
        return EngineDeclaration(
            id,
            representation,
            features,
            EnumSet.noneOf<DeclarativeBenchmarkPlan.Flag?>(DeclarativeBenchmarkPlan.Flag::class.java),
            true
        )
    }

    fun prepareInputs(data: BenchmarkData, inputKeys: MutableList<String?>): MutableList<RegexInput?> {
        val inputs: MutableList<RegexInput?> = ArrayList<RegexInput?>(inputKeys.size)
        for (inputKey in inputKeys) {
            if (inputRepresentation == InputRepresentation.PREEXISTING_UTF8) {
                inputs.add(utf8Input(data.getInputBytes(inputKey)))
            } else {
                inputs.add(StringRegexInput(data.getInputString(inputKey)))
            }
        }
        return List.copyOf<RegexInput?>(inputs)
    }

    fun prepareReplacement(replacement: String?): PreparedReplacement? {
        if (replacement == null) {
            return null
        }
        if (inputRepresentation == InputRepresentation.PREEXISTING_UTF8) {
            return Utf8Replacement(
                Utf8Input.Companion.trusted(replacement.toByteArray(StandardCharsets.UTF_8))
            )
        }
        return StringReplacement(replacement)
    }

    abstract fun compile(regex: String?): CompiledRegex?

    fun compileForBenchmark(regex: String, flagSet: String?): Any {
        val flags = if (flagSet == null) 0 else BenchmarkFlags.parse(flagSet)
        return when (this) {
            RegexEngineVariant.SAFERE_STRING, RegexEngineVariant.SAFERE_UTF8, RegexEngineVariant.SAFERE_UTF8_VECTOR -> Pattern.Companion.compile(
                regex,
                flags
            )

            RegexEngineVariant.JDK_STRING -> java.util.regex.Pattern.compile(regex, flags)
            RegexEngineVariant.RE2J_STRING -> {
                // The plan excludes rows whose Java flag semantics differ from RE2/J. Map identities,
                // never Java integer flags: Java CASE_INSENSITIVE has RE2/J's DOTALL value.
                val re2jFlags: Int =
                    RegexEngineVariant.Companion.RE2J_COMPILE_FLAGS.get(if (flagSet == null) "0" else flagSet)
                if (re2jFlags == null) {
                    throw java.lang.UnsupportedOperationException("RE2/J does not support flag set " + flagSet)
                }
                com.google.re2j.Pattern.compile(regex, re2jFlags)
            }

            RegexEngineVariant.RE2_FFM_STRING_CONVERSION -> {
                if (flags != 0) {
                    throw java.lang.UnsupportedOperationException("RE2-FFM flagged compilation is unsupported")
                }
                compile(regex)
            }
        }!!
    }

    internal interface RegexInput {
        fun nativeLength(): Int {
            return when (this) {
                -> string.value.length
                -> utf8.value().length()
            }
        }
    }

    @JvmRecord
    internal data class StringRegexInput(val value: String?) : RegexInput

    private class Utf8RegexInput(// Borrow immutable fixture storage; keep native view offsets for timed capture decoding.
        private val bytes: ByteArray, private val offset: Int, length: Int
    ) : RegexInput {
        private val value: Utf8Input

        init {
            this.value = Utf8Input.Companion.trusted(bytes, offset, length)
        }

        fun value(): Utf8Input {
            return value
        }
    }

    internal interface PreparedReplacement

    @JvmRecord
    internal data class StringReplacement(val value: String?) : PreparedReplacement

    @JvmRecord
    internal data class Utf8Replacement(val value: Utf8Input?) : PreparedReplacement

    internal interface ReplacementOutput {
        fun nativeLength(): Int

        fun validationValue(): String?

        fun consume(blackhole: Blackhole?)
    }

    @JvmRecord
    internal data class StringReplacementOutput(val value: String?) : ReplacementOutput {
        override fun nativeLength(): Int {
            return value.length()
        }

        override fun validationValue(): String? {
            return value
        }

        override fun consume(blackhole: Blackhole) {
            blackhole.consume(value)
        }
    }

    private class Utf8ReplacementOutput(private val bytes: ByteArray, private val length: Int) : ReplacementOutput {
        override fun nativeLength(): Int {
            return length
        }

        override fun validationValue(): String {
            return String(bytes, 0, length, StandardCharsets.UTF_8)
        }

        override fun consume(blackhole: Blackhole) {
            blackhole.consume(bytes)
            blackhole.consume(length)
        }
    }

    internal interface MatchCursor {
        fun find(): Boolean {
            throw UnsupportedOperationException()
        }

        fun matches(): Boolean {
            throw UnsupportedOperationException()
        }

        fun lookingAt(): Boolean {
            throw UnsupportedOperationException()
        }

        fun group(group: Int): String? {
            throw UnsupportedOperationException()
        }

        fun start(group: Int): Int {
            throw UnsupportedOperationException()
        }

        fun end(group: Int): Int {
            throw UnsupportedOperationException()
        }

        fun groupParticipated(group: Int): Boolean {
            return start(group) >= 0
        }

        fun reset() {
            throw UnsupportedOperationException()
        }

        fun region(start: Int, end: Int) {
            throw UnsupportedOperationException()
        }

        fun appendReplacement(result: StringBuilder?, replacement: String?) {
            throw UnsupportedOperationException()
        }

        fun appendTail(result: StringBuilder?) {
            throw UnsupportedOperationException()
        }
    }

    internal interface CompiledRegex : AutoCloseable {
        fun find(input: RegexInput?): Boolean {
            throw UnsupportedOperationException()
        }

        fun matches(input: RegexInput?): Boolean {
            throw UnsupportedOperationException()
        }

        fun matcher(input: RegexInput?): MatchCursor {
            throw UnsupportedOperationException()
        }

        fun replaceAll(input: RegexInput?, replacement: PreparedReplacement?): ReplacementOutput? {
            throw UnsupportedOperationException()
        }

        fun replaceFirst(input: RegexInput?, replacement: PreparedReplacement?): ReplacementOutput? {
            throw UnsupportedOperationException()
        }

        fun manualReplaceAll(input: RegexInput?, replacement: PreparedReplacement): ReplacementOutput {
            val matcher = matcher(input)
            val result = StringBuilder()
            while (matcher.find()) {
                matcher.appendReplacement(result, string(replacement))
            }
            matcher.appendTail(result)
            return StringReplacementOutput(result.toString())
        }

        fun split(input: RegexInput?, limit: Int): Array<String?>? {
            throw UnsupportedOperationException()
        }

        override fun close() {}
    }

    internal enum class InputRepresentation {
        JAVA_STRING,
        PREEXISTING_UTF8,
        JAVA_STRING_WITH_TIMED_UTF8_CONVERSION
    }

    private class ByteArraySink : Utf8Sink {
        private var bytes = ByteArray(256)
        private var length = 0

        override fun append(source: ByteArray, offset: Int, rangeLength: Int) {
            val requiredLength = length + rangeLength
            if (requiredLength > bytes.length) {
                bytes = Arrays.copyOf(bytes, Math.max(requiredLength, bytes.length * 2))
            }
            System.arraycopy(source, offset, bytes, length, rangeLength)
            length = requiredLength
        }

        fun output(): ReplacementOutput {
            return Utf8ReplacementOutput(bytes, length)
        }
    }

    companion object {
        private val RE2J_COMPILE_FLAGS: MutableMap<String?, Int> = Map.of<String?, Int?>(
            "0", 0,
            "CASE_INSENSITIVE", com.google.re2j.Pattern.CASE_INSENSITIVE,
            "CASE_INSENSITIVE_UNICODE_CASE", com.google.re2j.Pattern.CASE_INSENSITIVE,
            "CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS", com.google.re2j.Pattern.CASE_INSENSITIVE
        )

        fun re2jCompileFlagSets(): MutableSet<String?> {
            return RE2J_COMPILE_FLAGS.keySet()
        }

        fun fromId(id: String?): RegexEngineVariant {
            for (variant in entries) {
                if (variant.id == id) {
                    return variant
                }
            }
            throw IllegalArgumentException("Unknown cross-engine execution variant: " + id)
        }

        private fun allCapabilities(): EnumSet<EngineCapability?> {
            return EnumSet.allOf<EngineCapability?>(EngineCapability::class.java)
        }

        private fun ffmCapabilities(): EnumSet<EngineCapability?> {
            return EnumSet.of<EngineCapability?>(
                EngineCapability.COMPILE,
                EngineCapability.FIND,
                EngineCapability.MATCHES,
                EngineCapability.LOOKING_AT,
                EngineCapability.REGIONS,
                EngineCapability.GROUP_PARTICIPATION,
                EngineCapability.GROUP_TEXT,
                EngineCapability.REPLACE,
                EngineCapability.SPLIT,
                EngineCapability.MATCHER_RESET
            )
        }

        private fun re2jCapabilities(): EnumSet<EngineCapability?> {
            val capabilities: EnumSet<EngineCapability?> = allCapabilities()
            capabilities.remove(EngineCapability.REGIONS)
            return capabilities
        }

        private fun string(input: RegexInput): String? {
            return (input as StringRegexInput).value
        }

        private fun utf8(input: RegexInput): Utf8Input {
            return (input as Utf8RegexInput).value()
        }

        private fun string(replacement: PreparedReplacement): String? {
            return (replacement as StringReplacement).value
        }

        private fun utf8(replacement: PreparedReplacement): Utf8Input? {
            return (replacement as Utf8Replacement).value
        }

        private fun manualReplaceUtf8(
            matcher: Utf8Matcher, replacement: Utf8Input?
        ): ReplacementOutput {
            val sink = ByteArraySink()
            while (matcher.find()) {
                matcher.appendReplacement(sink, replacement)
            }
            matcher.appendTail(sink)
            return sink.output()
        }

        @JvmOverloads
        fun utf8Input(bytes: ByteArray, offset: Int = 0, length: Int = bytes.length): RegexInput {
            return Utf8RegexInput(bytes, offset, length)
        }
    }
}
