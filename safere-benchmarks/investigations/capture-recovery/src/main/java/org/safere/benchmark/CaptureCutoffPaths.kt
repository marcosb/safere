// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.gson.Gson

/** Separate-process path inspection; never installs diagnostics in a timing JVM.  */
object CaptureCutoffPaths {
    /** Writes observed paths for trial, operation, and profile lists supplied by the runner.  */
    fun main(args: Array<String?>) {
        kotlin.require(args.size == 3) { "Expected comma-separated trials, operations, profiles" }
        val results: List<Map<String?, Object?>?> = ArrayList()
        val config: JsonObject = BenchmarkData.get().investigationConfiguration()
        val documents: Int = config.get("documentsPerInvocation").getAsInt()
        for (trialId in args[0].split(",")) {
            val trial: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                CrossEngineBenchmarkPlan.load().resolve(trialId)
            val workload: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                trial.workload()
            val inputs: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                trial.variant().prepareInputs(BenchmarkData.get(), workload.inputKeys())
            for (operation in args[1].split(",")) {
                for (profile in args[2].split(",")) {
                    val schedule: JsonObject = config.getAsJsonObject("profiles").getAsJsonObject(profile)
                    val events: List<OperationDiagnostics?> = ArrayList()
                    try {
                        CaptureCutoffTask(
                            trial.variant(),
                            workload.patterns().getFirst(),
                            inputs,
                            operation,
                            documents,
                            schedule.get("hitEvery").getAsInt(),
                            schedule.get("readEvery").getAsInt()
                        ).use { task ->
                            Pattern.setDiagnostics(
                                object : SafeReMatchDiagnostics() {
                                    @Override
                                    fun onOperationCompleted(event: OperationDiagnostics?) {
                                        events.add(event)
                                    }
                                })
                            task.run()
                        }
                    } finally {
                        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
                    }
                    kotlin.check(events.size() === documents) { "Unexpected path-event count: " + trialId }
                    val row: Map<String?, Object?> = LinkedHashMap()
                    row.put("trial", trialId)
                    row.put("operation", operation)
                    row.put("profile", profile)
                    row.put(
                        "paths",
                        events.stream()
                            .map(
                                { event ->
                                    val path: Map<String?, Object?> = LinkedHashMap()
                                    path.put("boundary", event.boundaryStrategy().name())
                                    path.put("outcome", event.outcome().name())
                                    path.put("forwardDfa", event.forwardDfaSearchCount())
                                    path.put("reverseDfa", event.reverseDfaSearchCount())
                                    path
                                })
                            .distinct()
                            .toList()
                    )
                    results.add(row)
                }
            }
        }
        System.out.println(Gson().toJson(results))
    }
}
