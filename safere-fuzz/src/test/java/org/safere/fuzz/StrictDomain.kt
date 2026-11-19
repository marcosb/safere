// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import java.util.List
import java.util.function.IntUnaryOperator

/** Bounded ASCII grammar shared by enumeration and coverage-guided sampling.  */
internal object StrictDomain {
    val ATOMS: MutableList<String> = mutableListOf<String?>("", "a", "b", "ab", "[ab]", "[^a]", "[a-c]", "\\.", ".")
    val QUANTIFIERS: MutableList<String> = mutableListOf<String?>("?", "??", "*", "*?", "+", "+?", "{0,2}", "{1,3}?")
    const val MAX_DEPTH: Int = 3
    const val MAX_INPUT: Int = 24

    fun generate(data: FuzzedDataProvider): Sample {
        return generate(IntUnaryOperator { bound: Int -> data.consumeInt(0, bound - 1) })
    }

    fun generate(choice: IntUnaryOperator): Sample {
        return generate(choice, MAX_DEPTH)
    }

    // Recursion is statically bounded by MAX_DEPTH, independent of fuzz input.
    private fun generate(choice: IntUnaryOperator, depth: Int): Sample {
        val operation = if (depth == 0) 0 else choice.applyAsInt(5)
        if (operation == 0) {
            val atom = choice.applyAsInt(ATOMS.size)
            return Sample(
                ATOMS.get(atom), mutableListOf<String?>("", "a", "b", "ab", "a", "b", "a", ".", "a").get(atom)
            )
        }
        val left = generate(choice, depth - 1)
        if (operation == 3) {
            val quantifier = QUANTIFIERS.get(choice.applyAsInt(QUANTIFIERS.size))
            var copies = if (quantifier.startsWith("+") || quantifier.startsWith("{1")) 1 else 0
            if (!quantifier.startsWith("?")) {
                copies += choice.applyAsInt(2)
            }
            return Sample(StrictDomain.quantify(left.regex!!, quantifier), left.witness!!.repeat(copies))
        }
        val right = generate(choice, depth - 1)
        if (operation == 4) {
            // Exercise the final empty iteration after a consuming iteration, with a lower-priority
            // consuming alternative. In particular, NFA epsilon deduplication must retain the exit.
            val body = concatenate(StrictDomain.quantify(left.regex!!, "?"), alternate("", right.regex))
            return Sample(quantify(body, mutableListOf<String?>("+", "*", "+?").get(choice.applyAsInt(3))), "")
        }
        if (operation == 1) {
            return Sample(StrictDomain.concatenate(left.regex!!, right.regex), left.witness + right.witness)
        }
        return Sample(
            StrictDomain.alternate(left.regex!!, right.regex),
            if (choice.applyAsInt(2) == 0) left.witness else right.witness
        )
    }

    fun input(data: FuzzedDataProvider): String {
        val input = StringBuilder()
        val length = data.consumeInt(0, MAX_INPUT)
        for (index in 0..<length) {
            input.append(
                data.pickValue<String?>(
                    mutableListOf<String?>("a", "b", "c", ".", "!", "é", "中", "😀", "\ud800", "\udc00", "\n")
                )
            )
        }
        return input.toString()
    }

    fun concatenate(left: String, right: String?): String {
        return "(?:" + left + ")(?:" + right + ")"
    }

    fun alternate(left: String, right: String?): String {
        return "(?:" + left + "|" + right + ")"
    }

    fun quantify(expression: String, quantifier: String?): String {
        return "(?:" + expression + ")" + quantifier
    }

    fun smallPatterns(): MutableList<String?> {
        val result: MutableList<String?> = ArrayList<String?>(ATOMS)
        for (atom in ATOMS) {
            for (quantifier in QUANTIFIERS) {
                result.add(quantify(atom, quantifier))
            }
            for (right in ATOMS) {
                result.add(concatenate(atom, right))
                result.add(alternate(atom, right))
            }
        }
        return List.copyOf<String?>(result)
    }

    @JvmRecord
    internal data class Sample(val regex: String?, val witness: String?)
}
