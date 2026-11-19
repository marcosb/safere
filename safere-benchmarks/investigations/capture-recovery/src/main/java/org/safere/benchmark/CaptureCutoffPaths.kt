// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.ArrayList
import java.util.LinkedHashMap
import org.safere.OperationDiagnostics
import org.safere.Pattern
import org.safere.SafeReMatchDiagnostics

/** Separate-process path inspection; never installs diagnostics in a timing JVM. */
object CaptureCutoffPaths {
  /** Writes observed paths for trial, operation, and profile lists supplied by the runner. */
  @JvmStatic
  fun main(args: Array<String>) {
    require(args.size == 3) { "Expected comma-separated trials, operations, profiles" }
    val results: MutableList<MutableMap<String, Any>> = ArrayList()
    val config: JsonObject = BenchmarkData.get().investigationConfiguration()
    val documents: Int = config.get("documentsPerInvocation").asInt
    for (trialId in args[0].split(",")) {
      val trial = CrossEngineBenchmarkPlan.load().resolve(trialId)
      val workload = trial.workload!!
      val inputs = trial.variant!!.prepareInputs(BenchmarkData.get(), workload.inputKeys)
      for (operation in args[1].split(",")) {
        for (profile in args[2].split(",")) {
          val schedule: JsonObject = config.getAsJsonObject("profiles").getAsJsonObject(profile)
          val events: MutableList<OperationDiagnostics> = ArrayList()
          try {
            CaptureCutoffTask(
                    trial.variant!!,
                    workload.patterns!!.first(),
                    inputs,
                    operation,
                    documents,
                    schedule.get("hitEvery").asInt,
                    schedule.get("readEvery").asInt)
                .use { task ->
                  Pattern.setDiagnostics(
                      object : SafeReMatchDiagnostics() {
                        override fun onOperationCompleted(event: OperationDiagnostics?) {
                          events.add(event!!)
                        }
                      })
                  task.run()
                }
          } finally {
            Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
          }
          check(events.size == documents) { "Unexpected path-event count: $trialId" }
          val row: MutableMap<String, Any> = LinkedHashMap()
          row["trial"] = trialId
          row["operation"] = operation
          row["profile"] = profile
          row["paths"] =
              events
                  .map { event ->
                    val path: MutableMap<String, Any> = LinkedHashMap()
                    path["boundary"] = event.boundaryStrategy!!.name
                    path["outcome"] = event.outcome!!.name
                    path["forwardDfa"] = event.forwardDfaSearchCount
                    path["reverseDfa"] = event.reverseDfaSearchCount
                    path
                  }
                  .distinct()
          results.add(row)
        }
      }
    }
    println(Gson().toJson(results))
  }
}
