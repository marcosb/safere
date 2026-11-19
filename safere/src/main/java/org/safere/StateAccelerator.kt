// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Fast-forward scanner for DFA self-loop states.
 * 
 * 
 * When a DFA state transitions to itself on almost all characters, this accelerator uses SWAR /
 * vector intrinsics to scan directly to the next escape character that could cause a state
 * transition change.
 */
internal sealed interface StateAccelerator {
    /**
     * Finds the next index at or after `fromIndex` and strictly less than `limit`
     * containing an escape character.
     * 
     * @return the index of the escape character, or `-1` if no escape character occurs before
     * `limit`.
     */
    fun findEscape(text: InputScanner?, fromIndex: Int, limit: Int): Int

    /** Returns the accelerator policy for this state accelerator.  */
    fun policy(): AcceleratorPolicy?

    /** Accelerator for a single escape character (e.g. quote `'"'` or newline `'\n'`).  */
    data class SingleAsciiEscape(val escape: Int) : StateAccelerator {
        override fun findEscape(text: InputScanner?, fromIndex: Int, limit: Int): Int {
            return text!!.indexOfAscii(escape, fromIndex, limit)
        }

        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.LITERAL
        }


    }

    /** Accelerator for two escape characters (e.g. quote `'"'` and backslash `'\\'`).  */
    data class AsciiPairEscape(val c1: Int, val c2: Int) : StateAccelerator {
        override fun findEscape(text: InputScanner?, fromIndex: Int, limit: Int): Int {
            return text!!.indexOfAsciiPair(c1, c2, fromIndex, limit)
        }

        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.CHAR_CLASS
        }


    }

    /** Accelerator for three escape characters (e.g. comma, semicolon, newline).  */
    data class AsciiTripleEscape(val c1: Int, val c2: Int, val c3: Int) : StateAccelerator {
        override fun findEscape(text: InputScanner?, fromIndex: Int, limit: Int): Int {
            return text!!.indexOfAsciiTriple(c1, c2, c3, fromIndex, limit)
        }

        override fun policy(): AcceleratorPolicy {
            return AcceleratorPolicy.CHAR_CLASS
        }


    }

    companion object {
        /**
         * Fast-forwards to the next escape character in a self-loop state using pattern-matched
         * devirtualization.
         * 
         * 
         * Direct sealed-type pattern matching avoids `invokeinterface` dispatch overhead on hot
         * matching loops. HotSpot C2 does not automatically devirtualize megamorphic interface calls with
         *  3 implementations across the JVM lifecycle; switching over the sealed record subtypes here
         * allows C2 to inline the underlying [InputScanner] search primitives directly into caller
         * loops.
         */
        fun findNextEscape(
            accelerator: StateAccelerator, text: InputScanner, fromIndex: Int, limit: Int
        ): Int {
            return when (accelerator) {
                is SingleAsciiEscape -> text.indexOfAscii(accelerator.escape, fromIndex, limit)
                is AsciiPairEscape -> text.indexOfAsciiPair(accelerator.c1, accelerator.c2, fromIndex, limit)
                is AsciiTripleEscape ->
                    text.indexOfAsciiTriple(accelerator.c1, accelerator.c2, accelerator.c3, fromIndex, limit)
            }
        }

        /**
         * Fast-forwards to the next ASCII escape or non-ASCII input unit for an ASCII-only automaton.
         * 
         * 
         * Unlike [.findNextEscape], this must stop at non-ASCII input because the caller has not
         * modeled non-ASCII transitions in its transition table.
         */
        fun findNextAsciiOrNonAsciiEscape(
            accelerator: StateAccelerator, text: InputScanner, fromIndex: Int, limit: Int
        ): Int {
            return when (accelerator) {
                is SingleAsciiEscape -> text.indexOfAsciiOrNonAscii(accelerator.escape, fromIndex, limit)
                is AsciiPairEscape ->
                    text.indexOfAsciiPairOrNonAscii(accelerator.c1, accelerator.c2, fromIndex, limit)

                is AsciiTripleEscape ->
                    text.indexOfAsciiTripleOrNonAscii(
                        accelerator.c1, accelerator.c2, accelerator.c3, fromIndex, limit
                    )
            }
        }
    }
}
