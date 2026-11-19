// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.safere.Dfa.ManyMatchResult
import java.util.*
import java.util.List

/**
 * A compiled set of regular expression patterns that can be matched against a text simultaneously.
 * 
 * 
 * Unlike [Pattern], which compiles and matches one pattern at a time, `PatternSet`
 * compiles multiple patterns into a single automaton and tests all of them in a single pass over
 * the input text. This is useful for tasks such as URL routing, lexical analysis, or content
 * filtering where the input must be checked against many patterns.
 * 
 * 
 * Usage:
 * 
 * <pre>`PatternSet.Builder builder = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED); int id0 = builder.add("foo"); int id1 = builder.add("bar"); int id2 = builder.add("[0-9]+"); PatternSet set = builder.compile(); List<Integer> matches = set.match("foobar123"); // matches contains id0, id1, id2 `</pre>
 * 
 * 
 * This class is modeled on RE2's `RE2::Set`. The compiled automaton uses the DFA engine
 * for fast matching. If the DFA exhausts its state budget, the match falls back to running each
 * pattern individually through the NFA.
 */
class PatternSet private constructor(
    private val anchor: Anchor?,
    private val prog: Prog,
    private val size: Int,
    private val patterns: MutableList<String?>,
    private val fallbackPatterns: MutableList<Pattern>,
    private val maxDfaStates: Int,
) {
    /** Anchor mode for the pattern set. Determines how patterns are matched against the text.  */
    enum class Anchor {
        /** Patterns can match anywhere in the text.  */
        UNANCHORED,

        /** Patterns must match starting at the beginning of the text.  */
        ANCHOR_START,

        /** Patterns must match the entire text from start to end.  */
        ANCHOR_BOTH
    }
    /**
     * Returns the number of patterns in this set.
     * 
     * @return the number of patterns
     */
    fun size(): Int {
        return size
    }

    /**
     * Returns the pattern at the given index.
     * 
     * @param index the pattern index (as returned by [Builder.add])
     * @return the pattern string
     * @throws IndexOutOfBoundsException if index is out of range
     */
    fun pattern(index: Int): String? {
        return patterns.get(index)
    }

    /**
     * Tests whether any pattern in this set matches the text.
     * 
     * @param text the input text to match against
     * @return `true` if at least one pattern matches
     */
    fun matches(text: String?): Boolean {
        return !match(text).isEmpty()
    }

    /**
     * Matches the text against all patterns and returns the indices of the patterns that matched.
     * 
     * 
     * The returned list contains the 0-based indices (as returned by [Builder.add]) of all
     * patterns that matched the text. The list is sorted in ascending order. If no pattern matched,
     * an empty list is returned.
     * 
     * @param text the input text to match against
     * @return a sorted list of indices of matching patterns (may be empty)
     */
    fun match(text: String?): MutableList<Int> {
        val anchored = (anchor != Anchor.UNANCHORED)

        if (prog.hasGraphemeClusterInstruction()) {
            return matchFallback(text)
        }

        // Try DFA multi-match first.
        val dfaResult: ManyMatchResult? = Dfa.Companion.searchMany(prog, text, anchored, maxDfaStates)
        if (dfaResult != null) {
            if (!dfaResult.matched) {
                return Collections.emptyList<Int>()
            }
            val result: MutableList<Int> = ArrayList<Int>(dfaResult.matchIds!!.size)
            for (id in dfaResult.matchIds!!) {
                result.add(id)
            }
            result.sort()
            return Collections.unmodifiableList(result)
        }

        // DFA bailed out — fall back to running each pattern individually through NFA.
        return matchFallback(text)
    }

    /**
     * Fallback matching that runs each pattern individually through the NFA. Used when the DFA
     * exceeds its state budget.
     */
    private fun matchFallback(text: String?): MutableList<Int> {
        val result: MutableList<Int> = ArrayList<Int>()
        for (i in 0..<size) {
            val p = fallbackPatterns.get(i)
            val m = p.matcher(text)
            val matched =
                when (anchor) {
                    Anchor.UNANCHORED -> m.find()
                    Anchor.ANCHOR_START -> m.lookingAt()
                    Anchor.ANCHOR_BOTH -> m.matches()
                    null -> false
                }
            if (matched) {
                result.add(i)
            }
        }
        return Collections.unmodifiableList<Int?>(result)
    }

    /**
     * Builder for constructing a [PatternSet]. Patterns are added one at a time, then the set
     * is compiled into a single automaton.
     * 
     * 
     * Once [.compile] is called, no more patterns can be added.
     */
    class Builder(private val anchor: Anchor?) {
        private val patterns: MutableList<String?> = ArrayList<String?>()
        private var compiled = false

        /**
         * Creates a new builder with the given anchor mode.
         * 
         * @param anchor the anchor mode for matching
         */


        /**
         * Adds a pattern to the set.
         * 
         * @param pattern the regular expression pattern
         * @return the 0-based index assigned to this pattern
         * @throws PatternSyntaxException if the pattern is invalid
         * @throws IllegalStateException if [.compile] has already been called
         */
        fun add(pattern: String?): Int {
            check(!compiled) { "Cannot add patterns after compile()" }
            // Validate the pattern by parsing it.
            val parseFlags: Int = SET_PARSE_FLAGS
            Parser.Companion.parse(pattern, parseFlags)

            val index: Int = patterns.size
            patterns.add(pattern)
            return index
        }

        /**
         * Compiles all added patterns into a single automaton.
         * 
         * @return the compiled [PatternSet]
         * @throws IllegalStateException if no patterns have been added or if already compiled
         */
        fun compile(): PatternSet {
            return compile(DEFAULT_MAX_DFA_STATES)
        }

        /**
         * Compiles all added patterns with a custom DFA state budget. Package-private for testing.
         * 
         * @param maxDfaStates maximum number of DFA states before falling back to NFA
         * @return the compiled [PatternSet]
         */
        fun compile(maxDfaStates: Int): PatternSet {
            check(!compiled) { "compile() has already been called" }
            check(!patterns.isEmpty()) { "No patterns have been added" }
            compiled = true

            val parseFlags: Int = SET_PARSE_FLAGS
            val size: Int = patterns.size

            val tagged: MutableList<Regexp?> = ArrayList<Regexp?>(size)
            val fallbackPatterns: MutableList<Pattern?> = ArrayList<Pattern?>(size)
            for (i in 0..<size) {
                val pattern = patterns.get(i)
                val re: Regexp = Parser.Companion.parse(pattern, parseFlags)!!
                fallbackPatterns.add(Pattern.compile(pattern!!))
                val haveMatch: Regexp = Regexp.Companion.haveMatch(i, parseFlags)
                tagged.add(Regexp.Companion.concat(List.of<Regexp?>(re, haveMatch), parseFlags))
            }

            val combined: Regexp?
            if (size == 1) {
                combined = tagged.first()!!
            } else {
                combined = Regexp.Companion.alternate(tagged, parseFlags)
            }

            val prog: Prog = Compiler.Companion.compile(combined!!)!!

            if (anchor == Anchor.ANCHOR_START || anchor == Anchor.ANCHOR_BOTH) {
                prog.setAnchorStart(true)
            }
            if (anchor == Anchor.ANCHOR_BOTH) {
                prog.setAnchorEnd(true)
            }

            return PatternSet(
                anchor,
                prog,
                size,
                List.copyOf<String?>(patterns),
                List.copyOf<Pattern?>(fallbackPatterns),
                maxDfaStates
            )
        }
    }

    companion object {
        /** Parse flags for pattern set patterns: Perl-compatible without captures.  */
        private val SET_PARSE_FLAGS = ParseFlags.LIKE_PERL or ParseFlags.NEVER_CAPTURE

        /** Default maximum number of DFA states before falling back to NFA.  */
        const val DEFAULT_MAX_DFA_STATES: Int = 10000
    }
}
