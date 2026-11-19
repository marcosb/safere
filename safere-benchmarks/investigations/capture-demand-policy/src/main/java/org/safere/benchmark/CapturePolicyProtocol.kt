// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import java.nio.charset.StandardCharsets

/** Capture-consumption cycles used only by this investigation.  */
internal object CapturePolicyProtocol {
    fun compile(regex: String?, variant: RegexEngineVariant?): Compiled {
        val pattern: Pattern = Pattern.compile(regex)
        if (variant === RegexEngineVariant.SAFERE_STRING) {
            return org.safere.benchmark.CapturePolicyProtocol.Compiled { input: Input? ->
                val matcher: Matcher = pattern.matcher(input!!.text)
                object : Cursor {
                    @Override
                    override fun find(): Boolean {
                        return@Compiled matcher.find()
                    }

                    @Override
                    override fun reset() {
                        matcher.reset()
                    }

                    @Override
                    override fun start(group: Int): Int {
                        return@Compiled matcher.start(group)
                    }

                    @Override
                    override fun end(group: Int): Int {
                        return@Compiled matcher.end(group)
                    }

                    @Override
                    override fun group(group: Int): String {
                        return@Compiled matcher.group(group)
                    }
                }
            }
        }
        kotlin.require(variant === RegexEngineVariant.SAFERE_UTF8) { "Capture policy investigation supports String and UTF-8" }
        return org.safere.benchmark.CapturePolicyProtocol.Compiled { input: Input? ->
            val matcher: Utf8Matcher = pattern.matcher(Utf8Input.trusted(input!!.bytes))
            object : Cursor {
                @Override
                override fun find(): Boolean {
                    return@Compiled matcher.find()
                }

                @Override
                override fun reset() {
                    matcher.reset()
                }

                @Override
                override fun start(group: Int): Int {
                    return@Compiled matcher.start(group)
                }

                @Override
                override fun end(group: Int): Int {
                    return@Compiled matcher.end(group)
                }

                @Override
                override fun group(group: Int): String? {
                    val start: Int = matcher.start(group)
                    val end: Int = matcher.end(group)
                    return@Compiled if (start < 0)
                        null
                    else
                        String(input.bytes, start, end - start, StandardCharsets.UTF_8)
                }
            }
        }
    }

    fun bind(
        pattern: Compiled, inputs: List<Input?>, group: Int, reuse: Boolean, rounds: Int, usage: Usage
    ): Task {
        kotlin.require(!(inputs.isEmpty() || group < 0 || rounds < 1)) { "Expected inputs, a nonnegative group and positive rounds" }
        val retained: List<Cursor?>? =
            if (reuse) inputs.stream().map({ input: Input? -> pattern.matcher(input) }).toList() else null
        return org.safere.benchmark.CapturePolicyProtocol.Task {
            org.safere.benchmark.CapturePolicyProtocol.cycle(
                pattern,
                inputs,
                retained,
                group,
                rounds,
                usage
            )
        }
    }

    private fun cycle(
        pattern: Compiled,
        inputs: List<Input?>,
        retained: List<Cursor?>?,
        group: Int,
        rounds: Int,
        usage: Usage
    ): Int {
        var sum = 0
        var hits = 0
        for (phase in 0..2) {
            for (round in 0..<rounds) {
                for (i in 0..<inputs.size()) {
                    val matcher: Cursor = (if (retained == null) pattern.matcher(inputs.get(i)) else retained.get(i))!!
                    if (retained != null) {
                        matcher.reset()
                    }
                    while (matcher.find()) {
                        sum++
                        val read = hits++ % usage.readEvery == 0 && !usage.mode!!.equals("boolean")
                        if (read && !(usage.mode.equals("phase") && phase == 1)) {
                            if (usage.mode.equals("offset")) {
                                sum += matcher.end(group) - matcher.start(group)
                            } else {
                                val text = matcher.group(group)
                                sum += if (text == null) 0 else text.hashCode()
                            }
                        }
                    }
                }
            }
        }
        return sum
    }

    internal class Input(val text: String, val bytes: ByteArray) {
        constructor(bytes: ByteArray) : this(String(bytes, StandardCharsets.UTF_8), bytes)
    }

    internal interface Cursor {
        fun find(): Boolean

        fun reset()

        fun start(group: Int): Int

        fun end(group: Int): Int

        fun group(group: Int): String?
    }

    internal interface Compiled {
        fun matcher(input: Input?): Cursor?
    }

    internal interface Task {
        fun run(): Int
    }

    @kotlin.jvm.JvmRecord
    internal data class Usage(val mode: String?, val readEvery: Int) {
        init {
            kotlin.require(
                !(!List.of("text", "offset", "phase", "boolean").contains(mode) || readEvery < 1)
            ) { "Invalid capture consumption mode or read period" }
        }
    }
}
