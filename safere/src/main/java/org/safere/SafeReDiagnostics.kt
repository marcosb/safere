// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.math.*
/**
 * Diagnostic export helpers for verifier fixtures and bytecode inspection.
 * 
 * 
 * This API is intended for tests and verification tooling. The JSON schema is versioned, but
 * this class is not part of SafeRE's regex-matching API.
 * 
 * 
 * In bytecode fixture results, each group record includes `matched`. Nonparticipating
 * captures are exported with `matched: false` and Java/SafeRE `-1` start/end offsets.
 */
object SafeReDiagnostics {
    private const val SCHEMA = "safere-bytecode-v1"
    // Public java.util.regex.Pattern flag bits (same values Pattern aliases).
    private const val FLAG_UNIX_LINES = 0x01
    private const val FLAG_CASE_INSENSITIVE = 0x02
    private const val FLAG_COMMENTS = 0x04
    private const val FLAG_MULTILINE = 0x08
    private const val FLAG_LITERAL = 0x10
    private const val FLAG_DOTALL = 0x20
    private const val FLAG_UNICODE_CASE = 0x40
    private const val FLAG_UNICODE_CHARACTER_CLASS = 0x100
    private val BYTECODE_MATCH_KIND = Nfa.MatchKind.FIRST_MATCH

    /**
     * Exports one verifier fixture JSONL record using SafeRE's NFA-facing compiled program.
     * 
     * 
     * The exported program is `Pattern.prog()`, not the DFA-oriented flattened program. The
     * result object is produced by directly invoking SafeRE's NFA on that same program.
     * 
     * @param caseId stable fixture case identifier
     * @param regex source regular expression to record in the fixture
     * @param flags source flags value to record in the fixture
     * @param input input text to record and match
     * @param mode match mode for result metadata; currently only [BytecodeMatchMode.ANCHORED]
     * @param engine engine for result metadata; currently only [BytecodeResultEngine.NFA]
     * @param prog compiled NFA program to export
     * @return one complete JSON object followed by no trailing newline
     */
    internal fun bytecodeCaseToJsonLine(
        caseId: String?,
        regex: String?,
        flags: Int,
        input: String?,
        mode: BytecodeMatchMode?,
        engine: BytecodeResultEngine?,
        prog: Prog
    ): String {
        requireNotNull(caseId) { "caseId" }
        requireNotNull(regex) { "regex" }
        requireNotNull(input) { "input" }
        requireNotNull(mode) { "mode" }
        requireNotNull(engine) { "engine" }
        require(mode == BytecodeMatchMode.ANCHORED) { "Unsupported match mode: " + mode }
        require(engine == BytecodeResultEngine.NFA) { "Unsupported diagnostic engine: " + engine }
        require(!prog.didFlatten()) { "NFA bytecode export requires an unflattened program" }

        val json = StringBuilder()
        json.append('{')
        appendStringField(json, "schema", SCHEMA)
        json.append(',')
        appendProducer(json)
        json.append(',')
        appendCase(json, caseId!!, regex!!, flags, input!!, mode, engine, BYTECODE_MATCH_KIND)
        json.append(',')
        appendResult(json, prog, input, BYTECODE_MATCH_KIND)
        json.append(',')
        appendProgram(json, prog)
        json.append('}')
        return json.toString()
    }

    internal fun bytecodeCaseToJsonLine(
        caseId: String?,
        regex: String?,
        flags: Int,
        input: String?,
        mode: BytecodeMatchMode?,
        prog: Prog
    ): String {
        return bytecodeCaseToJsonLine(
            caseId, regex, flags, input, mode, BytecodeResultEngine.NFA, prog
        )
    }

    private fun appendProducer(json: StringBuilder) {
        json.append("\"producer\":{")
        appendStringField(json, "name", "safere")
        json.append('}')
    }

    private fun appendCase(
        json: StringBuilder,
        caseId: String,
        regex: String,
        flags: Int,
        input: String,
        mode: BytecodeMatchMode,
        engine: BytecodeResultEngine,
        matchKind: Nfa.MatchKind
    ) {
        json.append("\"case\":{")
        appendStringField(json, "id", caseId)
        json.append(',')
        appendStringField(json, "pattern", regex)
        json.append(',')
        appendFlags(json, flags)
        json.append(',')
        appendNumberField(json, "flagsValue", flags)
        json.append(',')
        appendStringField(json, "input", input)
        json.append(',')
        appendStringField(json, "mode", mode.jsonName)
        json.append(',')
        appendStringField(json, "engine", engine.name)
        json.append(',')
        appendStringField(json, "matchKind", matchKind.name)
        json.append('}')
    }

    private fun appendFlags(json: StringBuilder, flags: Int) {
        json.append("\"flags\":[")
        var needsComma = false
        needsComma = appendFlag(json, needsComma, flags, FLAG_UNIX_LINES, "UNIX_LINES")
        needsComma = appendFlag(json, needsComma, flags, FLAG_CASE_INSENSITIVE, "CASE_INSENSITIVE")
        needsComma = appendFlag(json, needsComma, flags, FLAG_COMMENTS, "COMMENTS")
        needsComma = appendFlag(json, needsComma, flags, FLAG_MULTILINE, "MULTILINE")
        needsComma = appendFlag(json, needsComma, flags, FLAG_LITERAL, "LITERAL")
        needsComma = appendFlag(json, needsComma, flags, FLAG_DOTALL, "DOTALL")
        needsComma = appendFlag(json, needsComma, flags, FLAG_UNICODE_CASE, "UNICODE_CASE")
        appendFlag(json, needsComma, flags, FLAG_UNICODE_CHARACTER_CLASS, "UNICODE_CHARACTER_CLASS")
        json.append(']')
    }

    private fun appendFlag(
        json: StringBuilder, needsComma: Boolean, flags: Int, flag: Int, name: String
    ): Boolean {
        if ((flags and flag) == 0) {
            return needsComma
        }
        if (needsComma) {
            json.append(',')
        }
        appendString(json, name)
        return true
    }

    private fun appendResult(
        json: StringBuilder, prog: Prog, input: String, matchKind: Nfa.MatchKind?
    ) {
        val groups: IntArray? =
            Nfa.search(
                prog,
                input,
                0,
                input.length,
                input.length,
                0,
                Nfa.Anchor.ANCHORED,
                matchKind,
                prog.numCaptures(),
                null
            )
        json.append("\"result\":{")
        appendBooleanField(json, "matched", groups != null)
        json.append(',')
        json.append("\"groups\":[")
        if (groups != null) {
            for (group in 0 until prog.numCaptures()) {
                if (group > 0) {
                    json.append(',')
                }
                json.append('{')
                appendNumberField(json, "group", group)
                json.append(',')
                appendBooleanField(json, "matched", groups[2 * group] >= 0)
                json.append(',')
                appendNumberField(json, "start", groups[2 * group])
                json.append(',')
                appendNumberField(json, "end", groups[2 * group + 1])
                json.append('}')
            }
        }
        json.append("]}")
    }

    private fun appendProgram(json: StringBuilder, prog: Prog) {
        json.append("\"program\":{")
        appendStringField(json, "shape", "nfa-prog")
        json.append(',')
        appendBooleanField(json, "didFlatten", prog.didFlatten())
        json.append(',')
        appendNumberField(json, "size", prog.size)
        json.append(',')
        appendNumberField(json, "start", prog.start())
        json.append(',')
        appendNumberField(json, "startUnanchored", prog.startUnanchored())
        json.append(',')
        appendNumberField(json, "numCaptures", prog.numCaptures())
        json.append(',')
        appendNumberField(json, "numCaptureSlots", 2 * max(prog.numCaptures(), 1))
        json.append(',')
        appendNumberField(json, "numLoopRegs", prog.numLoopRegs())
        json.append(',')
        appendBooleanField(json, "anchorStart", prog.anchorStart())
        json.append(',')
        appendBooleanField(json, "anchorEnd", prog.anchorEnd())
        json.append(',')
        appendBooleanField(json, "dollarAnchorEnd", prog.dollarAnchorEnd())
        json.append(',')
        appendBooleanField(json, "reversed", prog.reversed())
        json.append(',')
        appendBooleanField(json, "unixLines", prog.unixLines())
        json.append(',')
        // This constant is retained only for safere-bytecode-v1 schema compatibility; it no longer
        // describes a live property of the program after removing capture-retention lowering.
        appendBooleanField(json, "requiresPikeNfaCaptureSemantics", false)
        json.append(',')
        appendBooleanField(json, "hasGraphemeSemantics", prog.hasGraphemeSemantics())
        json.append(',')
        appendBooleanField(json, "hasGraphemeClusterInstruction", prog.hasGraphemeClusterInstruction())
        json.append(',')
        appendInstructions(json, prog)
        json.append('}')
    }

    private fun appendInstructions(json: StringBuilder, prog: Prog) {
        json.append("\"instructions\":[")
        for (id in 0 until prog.size) {
            if (id > 0) {
                json.append(',')
            }
            val inst = prog.inst(id)
            json.append('{')
            appendNumberField(json, "id", id)
            json.append(',')
            appendStringField(json, "op", inst.op!!.name)
            appendInstructionFields(json, inst)
            json.append('}')
        }
        json.append(']')
    }

    private fun appendInstructionFields(json: StringBuilder, inst: Inst) {
        when (inst.op) {
            InstOp.FAIL -> {}
            InstOp.MATCH -> {
                json.append(',')
                appendNumberField(json, "arg", inst.arg)
            }

            InstOp.NOP, InstOp.CAPTURE, InstOp.LOOP_ENTER, InstOp.EMPTY_WIDTH, InstOp.GRAPHEME_CLUSTER -> appendArgOutFields(
                json,
                inst
            )

            InstOp.ALT, InstOp.ALT_MATCH -> appendOutOut1Fields(json, inst)
            InstOp.CHAR_RANGE -> appendCharRangeFields(json, inst)
            InstOp.CHAR_CLASS -> appendCharClassFields(json, inst)
            InstOp.PROGRESS_CHECK -> appendProgressCheckFields(json, inst)
            else -> {}
        }
    }

    private fun appendArgOutFields(json: StringBuilder, inst: Inst) {
        if (inst.op == InstOp.CAPTURE || inst.op == InstOp.LOOP_ENTER || inst.op == InstOp.EMPTY_WIDTH) {
            json.append(',')
            appendNumberField(json, "arg", inst.arg)
        }
        json.append(',')
        appendNumberField(json, "out", inst.out)
    }

    private fun appendOutOut1Fields(json: StringBuilder, inst: Inst) {
        json.append(',')
        appendNumberField(json, "out", inst.out)
        json.append(',')
        appendNumberField(json, "out1", inst.out1)
    }

    private fun appendCharRangeFields(json: StringBuilder, inst: Inst) {
        json.append(',')
        appendNumberField(json, "out", inst.out)
        json.append(',')
        appendNumberField(json, "lo", inst.lo)
        json.append(',')
        appendNumberField(json, "hi", inst.hi)
        json.append(',')
        appendBooleanField(json, "foldCase", inst.foldCase)
    }

    private fun appendCharClassFields(json: StringBuilder, inst: Inst) {
        json.append(',')
        appendNumberField(json, "out", inst.out)
        json.append(',')
        json.append("\"ranges\":[")
        var i = 0
        while (i < inst.ranges.size) {
            if (i > 0) {
                json.append(',')
            }
            json.append('[').append(inst.ranges[i]).append(',').append(inst.ranges[i + 1]).append(']')
            i += 2
        }
        json.append(']')
    }

    private fun appendProgressCheckFields(json: StringBuilder, inst: Inst) {
        json.append(',')
        appendNumberField(json, "arg", inst.arg)
        json.append(',')
        appendNumberField(json, "out", inst.out)
        json.append(',')
        appendNumberField(json, "out1", inst.out1)
        json.append(',')
        appendBooleanField(json, "nonGreedy", inst.foldCase)
    }

    private fun appendStringField(json: StringBuilder, name: String, value: String) {
        appendString(json, name)
        json.append(':')
        appendString(json, value)
    }

    private fun appendNumberField(json: StringBuilder, name: String, value: Int) {
        appendString(json, name)
        json.append(':').append(value)
    }

    private fun appendBooleanField(json: StringBuilder, name: String, value: Boolean) {
        appendString(json, name)
        json.append(':').append(value)
    }

    private fun appendString(json: StringBuilder, value: String) {
        json.append('"')
        for (i in 0 until value.length) {
            when (val ch = value[i]) {
                '"' -> json.append("\\\"")
                '\\' -> json.append("\\\\")
                '\b' -> json.append("\\b")
                '\u000C' -> json.append("\\f")
                '\n' -> json.append("\\n")
                '\r' -> json.append("\\r")
                '\t' -> json.append("\\t")
                else -> appendJsonChar(json, ch)
            }
        }
        json.append('"')
    }

    private fun appendJsonChar(json: StringBuilder, ch: Char) {
        if (ch.code < 0x20 || ch.isSurrogate()) {
            appendUnicodeEscape(json, ch)
        } else {
            json.append(ch)
        }
    }

    private fun appendUnicodeEscape(json: StringBuilder, ch: Char) {
        json.append("\\u")
        val hex = ch.code.toString(16)
        for (i in hex.length until 4) {
            json.append('0')
        }
        json.append(hex)
    }

    /** Match mode for exported result metadata.  */
    enum class BytecodeMatchMode(val jsonName: String) {
        /** Run an NFA search anchored at the beginning of the input.  */
        ANCHORED("anchored");
    }

    /** Engine used to produce bytecode fixture result metadata.  */
    enum class BytecodeResultEngine {
        /** SafeRE's Pike VM NFA engine.  */
        NFA
    }
}
