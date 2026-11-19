// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import java.util.concurrent.atomic.LongAdder

/** Synchronous JDK oracle with a deterministic text-access budget and no worker threads.  */
internal object StrictOracle {
    val COMPLETED: LongAdder = LongAdder()
    val UNAVAILABLE: LongAdder = LongAdder()

    init {
        Runtime.getRuntime()
            .addShutdownHook(Thread(Runnable { obj: StrictOracle? -> report() }, "safere-strict-oracle-summary"))
    }

    fun completed() {
        COMPLETED.increment()
        if (COMPLETED.sum() % 4096 == 0L) {
            report()
        }
    }

    fun unavailable() {
        UNAVAILABLE.increment()
        val count = UNAVAILABLE.sum()
        if ((count and (count - 1)) == 0L) {
            report()
        }
    }

    fun report() {
        System.err.println(
            ("Strict oracle: completed="
                    + COMPLETED.sum()
                    + " unavailable="
                    + UNAVAILABLE.sum()
                    + " excludedObservations="
                    + OracleDefects.EXCLUDED_OBSERVATIONS.sum())
        )
    }

    fun input(text: String): CharSequence {
        return BudgetText(text, Budget())
    }

    private class Budget {
        private var remaining = 100000

        fun access() {
            if (--remaining < 0) {
                throw Unavailable()
            }
        }
    }

    @JvmRecord
    private data class BudgetText(val text: String, val budget: Budget?) : CharSequence {
        override fun length(): Int {
            budget!!.access()
            return text.length
        }

        override fun charAt(index: Int): Char {
            budget!!.access()
            return text.get(index)
        }

        override fun subSequence(start: Int, end: Int): CharSequence {
            budget!!.access()
            return BudgetText(text.substring(start, end), budget)
        }

        override fun toString(): String {
            budget!!.access()
            return text
        }
    }

    internal class Unavailable : RuntimeException() {
        private const val serialVersionUID = 1L
    }
}
