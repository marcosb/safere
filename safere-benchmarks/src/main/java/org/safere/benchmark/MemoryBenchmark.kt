// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import org.safere.Pattern
import org.safere.benchmark.DeclarativeBenchmarkPlan.ExpandedWorkload
import org.safere.benchmark.DeclarativeBenchmarkPlan.RecipeString
import org.safere.crosscheck.Pattern.Companion.compile
import java.util.*
import java.util.function.Supplier
import java.util.stream.Collectors
import kotlin.math.max

/**
 * Measures the retained heap size of compiled regex patterns across engines (SafeRE, JDK, RE2/J).
 * 
 * 
 * Uses the heap-delta technique: allocate N instances of a compiled pattern, measure heap growth
 * before and after (with forced GC), and divide by N. Multiple trials are run and the median is
 * reported to reduce noise.
 * 
 * 
 * This is a standalone measurement tool, not a JMH benchmark, because we are measuring one-shot
 * object sizes rather than per-operation throughput.
 * 
 * 
 * Run with:
 * 
 * <pre>`java -Xms256m -Xmx256m -cp safere-benchmarks/target/benchmarks.jar \   org.safere.benchmark.MemoryBenchmark `</pre>
 */
object MemoryBenchmark {
    /** Number of pattern instances to allocate per measurement (amortizes GC noise).  */
    private const val INSTANCES = 500

    /** Number of independent measurement trials (take the median).  */
    private const val TRIALS = 7

    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val plan: SpecializedBenchmarkPlan = SpecializedBenchmarkPlan.load()
        val selected = Arrays.stream<String?>(args).collect(Collectors.toSet())
        println("=== Declarative retained-memory measurements (bytes) ===")
        for (trial in plan.retainedMemoryTrials()) {
            if (!selected.isEmpty() && !selected.contains(trial.id())) {
                continue
            }
            val workload = trial.workload
            val bytes =
                when (workload.operation) {
                    DeclarativeBenchmarkPlan.Operation.COMPILE -> measureRetainedSize(
                        Supplier {
                            trial
                                .variant
                                .compileForBenchmark(
                                    workload.patterns.first(), flagSet(workload)
                                )
                        })

                    DeclarativeBenchmarkPlan.Operation.DFA_CACHE_GROWTH -> measureDfaCacheGrowth(
                        workload.patterns.first(),
                        BenchmarkData.get().getInputString(workload.inputIds.first())
                    )

                    else -> throw IllegalArgumentException(
                        "Unsupported retained-memory operation: " + workload.operation
                    )
                }
            System.out.printf("%s %,d%n", trial.id(), bytes)
        }
    }

    private fun flagSet(workload: ExpandedWorkload): String? {
        val value = workload.arguments.get("flagSet")
        return if (value == null) null else (value as RecipeString).value
    }

    /**
     * Measures the retained heap size of an object produced by the given factory, using the
     * heap-delta technique. Creates [.INSTANCES] copies, measures heap growth, divides by
     * instance count. Runs [.TRIALS] trials and returns the median.
     */
    @Throws(Exception::class)
    private fun measureRetainedSize(factory: Supplier<Any?>): Long {
        val results = LongArray(TRIALS)

        for (trial in 0..<TRIALS) {
            // Warm up the factory (JIT compile, class loading).
            for (i in 0..19) {
                factory.get()
            }

            forceGc()
            val before = usedMemory()

            // Allocate many instances to amortize per-object GC noise.
            val holders = arrayOfNulls<Any>(INSTANCES)
            for (i in 0..<INSTANCES) {
                holders[i] = factory.get()
            }

            forceGc()
            val after = usedMemory()

            results[trial] = max(0, (after - before) / INSTANCES)

            // Keep holders alive past the measurement point.
            if (holders[INSTANCES - 1] == null) {
                throw AssertionError("holder should not be null")
            }
        }

        Arrays.sort(results)
        return results[TRIALS / 2] // median
    }

    /** Forces garbage collection as thoroughly as possible.  */
    @Throws(InterruptedException::class)
    private fun forceGc() {
        for (i in 0..4) {
            System.gc()
            Thread.sleep(50)
        }
    }

    /** Returns the currently used heap memory in bytes.  */
    private fun usedMemory(): Long {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    /**
     * Measures the DFA cache growth for a single compiled SafeRE pattern by measuring heap before and
     * after running the pattern against text. Uses multiple trials and returns the median.
     */
    @Throws(Exception::class)
    private fun measureDfaCacheGrowth(pattern: String?, text: String?): Long {
        val results = LongArray(TRIALS)

        for (trial in 0..<TRIALS) {
            // Compile the pattern (DFA cache starts empty).
            val p: Pattern = compile(pattern!!)

            // Warm up: run a short match so JIT compiles the matching path.
            p.matcher("warmup").find()

            forceGc()
            val before = usedMemory()

            // Run matching against large text to populate the DFA state cache.
            val m = p.matcher(text)
            while (m.find()) {
                // DFA states are lazily created during matching.
            }

            forceGc()
            val after = usedMemory()

            results[trial] = after - before

            // Keep pattern alive past the measurement.
            if (p.pattern() == null) {
                throw AssertionError("pattern should not be null")
            }
        }

        Arrays.sort(results)
        return results[TRIALS / 2] // median
    }
}
