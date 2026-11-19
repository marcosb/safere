// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.crosscheck

import org.safere.Pattern
import java.io.ObjectStreamException
import java.io.Serializable
import java.util.*
import java.util.function.Predicate
import java.util.regex.PatternSyntaxException
import java.util.stream.Stream

/**
 * A crosscheck wrapper that compiles and executes regular expressions on both SafeRE and `java.util.regex`, comparing results and throwing [CrosscheckException] on divergence.
 * 
 * 
 * This class has the same API as [Pattern] and [java.util.regex.Pattern],
 * so switching to crosscheck mode requires only changing the import:
 * 
 * <pre>
 * // Before:
 * import org.safere.Pattern;
 * // After:
 * import org.safere.crosscheck.Pattern;
</pre> * 
 * 
 * 
 * When both engines agree, SafeRE's result is returned. When they disagree, a [ ] is thrown with full details and an API call trace. When SafeRE rejects a
 * pattern that the JDK accepts (e.g., backreferences), an [UnsupportedPatternException] is
 * thrown.
 */
class Pattern private constructor(private val saferePattern: Pattern, private val jdkPattern: java.util.regex.Pattern) :
    Serializable {
    /** Creates a crosscheck [Matcher] for the given input.  */
    fun matcher(input: CharSequence?): Matcher {
        return Matcher(this, input)
    }

    /** Creates a UTF-8 matcher crosschecked against decoded SafeRE String and JDK matchers.  */
    fun matcher(input: Utf8Input?): Utf8Matcher {
        return Utf8Matcher(this, input)
    }

    /** Runs capture-free UTF-8 search and compares it with decoded String and JDK oracles.  */
    fun find(input: Utf8Input?): Boolean {
        Objects.requireNonNull<Utf8Input?>(input, "input")
        val result = saferePattern.find(input!!.delegate())
        Utf8Matcher.crosscheckFind(this, input, result)
        return result
    }

    /** Returns the flags this pattern was compiled with.  */
    fun flags(): Int {
        return saferePattern.flags()
    }

    /** Returns the regular expression from which this pattern was compiled.  */
    fun pattern(): String? {
        return saferePattern.pattern()
    }

    /**
     * Splits the input around matches of this pattern with the given limit, comparing results from
     * both engines.
     * 
     * @throws CrosscheckException if the engines produce different split results
     */
    /**
     * Splits the input around matches of this pattern, comparing results from both engines.
     * 
     * @throws CrosscheckException if the engines produce different split results
     */
    @JvmOverloads
    fun split(input: CharSequence?, limit: Int = 0): Array<String?> {
        val sr = saferePattern.split(input, limit)
        val jr = jdkPattern.split(input, limit)
        if (!sr.contentEquals(jr)) {
            throw CrosscheckException(
                "Pattern.split",
                quote(input) + ", " + limit,
                sr.contentToString(),
                jr.contentToString(),
                "(no trace — Pattern method)"
            )
        }
        return sr
    }

    /**
     * Splits the input around matches of this pattern with the given limit, including delimiters,
     * comparing results from both engines.
     * 
     * @throws CrosscheckException if the engines produce different split results
     */
    /**
     * Splits the input around matches of this pattern, including delimiters, comparing results from
     * both engines.
     * 
     * @throws CrosscheckException if the engines produce different split results
     */
    @JvmOverloads
    fun splitWithDelimiters(input: CharSequence?, limit: Int = 0): Array<String?> {
        val sr = saferePattern.splitWithDelimiters(input, limit)
        val jr = jdkPattern.splitWithDelimiters(input, limit)
        if (!sr.contentEquals(jr)) {
            throw CrosscheckException(
                "Pattern.splitWithDelimiters",
                quote(input) + ", " + limit,
                sr.contentToString(),
                jr.contentToString(),
                "(no trace — Pattern method)"
            )
        }
        return sr
    }

    /** Splits the input lazily around matches of this pattern.  */
    fun splitAsStream(input: CharSequence?): Stream<String?> {
        return saferePattern.splitAsStream(input)
    }

    /** Creates a predicate that returns true when this pattern is found in the input.  */
    fun asPredicate(): Predicate<String?> {
        return Predicate { input: String? -> matcher(input).find() }
    }

    /** Creates a predicate that returns true when this pattern matches the entire input.  */
    fun asMatchPredicate(): Predicate<String?> {
        return Predicate { input: String? -> matcher(input).matches() }
    }

    /** Returns the named capturing groups in this pattern.  */
    fun namedGroups(): MutableMap<String?, Int?> {
        val sr = saferePattern.namedGroups()
        val jr = jdkPattern.namedGroups()
        if (sr != jr) {
            throw CrosscheckException(
                "Pattern.namedGroups",
                "",
                sr.toString(),
                jr.toString(),
                "(no trace — Pattern method)"
            )
        }
        return sr
    }

    /** Returns the number of capturing groups in this pattern.  */
    fun numGroups(): Int {
        return saferePattern.matcher("").groupCount()
    }

    /** Returns the pattern string.  */
    override fun toString(): String {
        return saferePattern.toString()
    }

    @Throws(ObjectStreamException::class)
    private fun writeReplace(): Any {
        return SerializedForm(pattern(), flags())
    }

    /** Returns the underlying SafeRE pattern (package-private, used by Matcher).  */
    fun saferePattern(): Pattern {
        return saferePattern
    }

    /** Returns the underlying JDK pattern (package-private, used by Matcher).  */
    fun jdkPattern(): java.util.regex.Pattern {
        return jdkPattern
    }

    @JvmRecord
    private data class SerializedForm(val regex: String?, val flags: Int) : Serializable {
        @Throws(ObjectStreamException::class)
        fun readResolve(): Any {
            return compile(regex!!, flags)
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    companion object {
        private const val serialVersionUID = 1L

        // Flag constants — identical to java.util.regex.Pattern and org.safere.Pattern.
        val UNIX_LINES: Int = java.util.regex.Pattern.UNIX_LINES

        @JvmField
        val CASE_INSENSITIVE: Int = java.util.regex.Pattern.CASE_INSENSITIVE
        val COMMENTS: Int = java.util.regex.Pattern.COMMENTS
        val MULTILINE: Int = java.util.regex.Pattern.MULTILINE
        val LITERAL: Int = java.util.regex.Pattern.LITERAL
        val DOTALL: Int = java.util.regex.Pattern.DOTALL
        val UNICODE_CASE: Int = java.util.regex.Pattern.UNICODE_CASE
        val UNICODE_CHARACTER_CLASS: Int = java.util.regex.Pattern.UNICODE_CHARACTER_CLASS

        /**
         * Compiles the given regular expression with the given flags on both engines.
         * 
         * @throws UnsupportedPatternException if SafeRE rejects the pattern but the JDK accepts it
         * @throws CrosscheckException if the JDK rejects the pattern but SafeRE accepts it
         * @throws PatternSyntaxException if both engines reject the pattern
         */
        /**
         * Compiles the given regular expression on both engines.
         * 
         * @throws UnsupportedPatternException if SafeRE rejects the pattern but the JDK accepts it
         * @throws CrosscheckException if the JDK rejects the pattern but SafeRE accepts it
         * @throws PatternSyntaxException if both engines reject the pattern
         */
        @JvmStatic
        @JvmOverloads
        fun compile(regex: String, flags: Int = 0): org.safere.crosscheck.Pattern {
            var sp: Pattern? = null
            var jp: java.util.regex.Pattern? = null
            var safereEx: PatternSyntaxException? = null
            var jdkEx: PatternSyntaxException? = null

            try {
                sp = Pattern.compile(regex, flags)
            } catch (e: PatternSyntaxException) {
                safereEx = e
            }
            try {
                jp = java.util.regex.Pattern.compile(regex, flags)
            } catch (e: PatternSyntaxException) {
                jdkEx = e
            }

            if (sp != null && jp != null) {
                // Both compiled successfully.
                return Pattern(sp, jp)
            } else if (safereEx != null && jdkEx != null) {
                // Both rejected — rethrow SafeRE's exception.
                throw safereEx
            } else if (safereEx != null) {
                // SafeRE rejected, JDK accepted — unsupported feature.
                throw UnsupportedPatternException.fromCause(safereEx)
            } else {
                // JDK rejected, SafeRE accepted — unexpected divergence.
                throw CrosscheckException(
                    "Pattern.compile",
                    quote(regex) + ", " + flags,
                    "compiled successfully",
                    "PatternSyntaxException: " + jdkEx!!.message,
                    "(no trace — divergence during compilation)"
                )
            }
        }

        /**
         * Convenience method that compiles the regex and tests if it matches the entire input, on both
         * engines.
         */
        @JvmStatic
        fun matches(regex: String, input: CharSequence): Boolean {
            val sr = Pattern.matches(regex, input)
            val jr = java.util.regex.Pattern.matches(regex, input.toString())
            if (sr != jr) {
                throw CrosscheckException(
                    "Pattern.matches",
                    quote(regex) + ", " + quote(input),
                    sr.toString(),
                    jr.toString(),
                    "(no trace — static method)"
                )
            }
            return sr
        }

        /** Returns a literal pattern string for the given string. Delegates to SafeRE.  */
        @JvmStatic
        fun quote(s: String): String {
            return Pattern.quote(s)
        }

        private fun quote(s: CharSequence?): String {
            return "\"" + s + "\""
        }
    }
}
