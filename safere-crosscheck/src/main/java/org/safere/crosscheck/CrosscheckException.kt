// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.crosscheck

/**
 * Thrown when SafeRE and `java.util.regex` produce different results for the same operation.
 * 
 * 
 * The exception message includes the divergent method call, both results, and the full API call
 * trace leading up to the divergence. This provides enough context to reproduce and report the bug.
 */
class CrosscheckException private constructor(
    method: String,
    args: String?,
    firstLabel: String?,
    firstResult: String?,
    secondLabel: String?,
    secondResult: String?,
    trace: String?
) : RuntimeException(formatMessage(method, args, firstLabel, firstResult, secondLabel, secondResult, trace)) {
    private val method: String?
    private val args: String?
    private val safereResult: String?
    private val jdkResult: String?
    private val trace: String?

    /**
     * Creates a new crosscheck exception.
     * 
     * @param method the method that diverged
     * @param args the arguments to the method
     * @param safereResult the result from SafeRE
     * @param jdkResult the result from java.util.regex
     * @param trace the formatted API call trace
     */
    constructor(method: String, args: String?, safereResult: String?, jdkResult: String?, trace: String?) : this(
        method,
        args,
        "SafeRE",
        safereResult,
        "JDK",
        jdkResult,
        trace
    )

    init {
        this.method = method
        this.args = args
        this.safereResult = firstResult
        this.jdkResult = secondResult
        this.trace = trace
    }

    /** Returns the name of the method that produced divergent results.  */
    fun method(): String? {
        return method
    }

    /** Returns the arguments passed to the divergent method.  */
    fun args(): String? {
        return args
    }

    /** Returns the result from SafeRE.  */
    fun safereResult(): String? {
        return safereResult
    }

    /** Returns the result from `java.util.regex`.  */
    fun jdkResult(): String? {
        return jdkResult
    }

    /** Returns the formatted API call trace leading up to the divergence.  */
    fun trace(): String? {
        return trace
    }

    companion object {
        private const val serialVersionUID = 1L

        fun comparison(
            method: String,
            args: String?,
            firstLabel: String?,
            firstResult: String?,
            secondLabel: String?,
            secondResult: String?,
            trace: String?
        ): CrosscheckException {
            return CrosscheckException(
                method, args, firstLabel, firstResult, secondLabel, secondResult, trace
            )
        }

        private fun formatMessage(
            method: String,
            args: String?,
            firstLabel: String?,
            firstResult: String?,
            secondLabel: String?,
            secondResult: String?,
            trace: String?
        ): String {
            return ("Crosscheck divergence in "
                    + method
                    + "("
                    + args
                    + "):\n"
                    + "  "
                    + firstLabel
                    + ": "
                    + firstResult
                    + "\n"
                    + "  "
                    + secondLabel
                    + ": "
                    + secondResult
                    + "\n\n"
                    + trace)
        }
    }
}
