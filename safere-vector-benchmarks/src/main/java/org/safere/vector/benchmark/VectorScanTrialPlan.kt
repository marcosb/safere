// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.vector.benchmark

/** Prints the data-declared JMH trial parameter for the requested measurement profile.  */
object VectorScanTrialPlan {
    /** Prints a comma-separated trial list for `smoke` or `standard`.  */
    fun main(args: Array<String?>) {
        kotlin.require(args.length == 1) { "Usage: VectorScanTrialPlan <smoke|standard>" }
        System.out.println(String.join(",", VectorScanConfiguration.trials(args[0])))
    }
}
