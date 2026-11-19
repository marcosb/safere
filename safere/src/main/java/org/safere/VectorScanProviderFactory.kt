// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.function.Supplier

/** Constructs the optional incubator Vector API provider.  */
internal object VectorScanProviderFactory {
    private const val VECTOR_MODULE_NAME = "jdk.incubator.vector"

    fun create(): VectorScanProvider {
        val safeReModule = VectorScanProviderFactory::class.java.getModule()
        if (safeReModule.isNamed()) {
            val vectorModule =
                ModuleLayer.boot()
                    .findModule(VECTOR_MODULE_NAME)
                    .orElseThrow<IllegalStateException?>(
                        Supplier { IllegalStateException(VECTOR_MODULE_NAME + " is not in the boot layer") })
            safeReModule.addReads(vectorModule)
        }
        return IncubatorVectorScanProvider()
    }
}
