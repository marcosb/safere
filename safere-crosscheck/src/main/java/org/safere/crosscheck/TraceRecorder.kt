// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.crosscheck

import java.util.*

/**
 * Records a trace of API calls made through the crosscheck facade. Each call is recorded as a
 * [TraceEntry] containing the method name, arguments, and results from both engines.
 * 
 * 
 * The trace is included in [CrosscheckException] on divergence and can also be retrieved
 * manually via [Matcher.getTrace] for debugging.
 */
class TraceRecorder {
    private val entries: MutableList<TraceEntry> = ArrayList<TraceEntry>()

    /** A single recorded API call with results from both engines.  */
    @JvmRecord
    data class TraceEntry(
      val method: String?,
      val args: String?,
      val safereResult: String?,
      val jdkResult: String?,
      @JvmField val matched: Boolean
    ) {
        companion object {
            /** Creates a trace entry, converting results to strings.  */
            fun of(method: String?, args: String?, safereResult: Any?, jdkResult: Any?): TraceEntry {
                val sr = Objects.toString(safereResult)
                val jr = Objects.toString(jdkResult)
                return TraceEntry(method, args, sr, jr, sr == jr)
            }
        }
    }

    /** Records a call where both engines produced the same result.  */
    fun recordMatch(method: String?, args: String?, result: Any?) {
        val s = Objects.toString(result)
        entries.add(TraceEntry(method, args, s, s, true))
    }

    /** Records a call where the engines produced different results.  */
    fun recordDivergence(method: String?, args: String?, safereResult: Any?, jdkResult: Any?) {
        entries.add(TraceEntry.Companion.of(method, args, safereResult, jdkResult))
    }

    /** Records a call with explicit results from both engines.  */
    fun record(method: String?, args: String?, safereResult: Any?, jdkResult: Any?) {
        entries.add(TraceEntry.Companion.of(method, args, safereResult, jdkResult))
    }

    /** Returns an unmodifiable view of all recorded entries.  */
    fun getEntries(): MutableList<TraceEntry?> {
        return Collections.unmodifiableList<TraceEntry?>(entries)
    }

    /** Clears all recorded entries.  */
    fun clear() {
        entries.clear()
    }

    /**
     * Formats the trace as a human-readable string for inclusion in error messages and bug reports.
     */
    fun format(): String {
        if (entries.isEmpty()) {
            return "(empty trace)"
        }
        val sb = StringBuilder()
        sb.append("API call trace (").append(entries.size).append(" calls):\n")
        for (i in entries.indices) {
            val e = entries.get(i)
            sb.append(String.format(Locale.ROOT, "  [%d] %s(%s)", i + 1, e.method, e.args))
            if (e.matched) {
                sb.append(" → ").append(e.safereResult)
            } else {
                sb.append(" → DIVERGENCE\n")
                sb.append("        SafeRE: ").append(e.safereResult).append('\n')
                sb.append("        JDK:    ").append(e.jdkResult)
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}
