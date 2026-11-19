// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/** Selects the optional Vector scan provider once without loading it unless requested.  */
internal object VectorScanProviders {
    const val PROVIDER_PROPERTY: String = "org.safere.experimental.vectorScanProvider"
    private val SELECTED: VectorScanProvider? = loadSelected()

    /**
     * Returns the installed Vector provider when its crossover thresholds admit a `kind` scan
     * over a window of `windowLength` bytes, and `null` otherwise.
     * 
     * 
     * `windowLength` must be the length of the region the caller is about to scan, not the
     * length of the whole input. The thresholds describe the work of a single kernel invocation, so
     * sizing them by the input defeats them: one long input selects a provider that then serves every
     * scan against it, including the narrow probes a candidate verification loop issues thousands of
     * times, each paying vector setup for a window far below break-even.
     */
    fun providerFor(kind: ScanKind?, windowLength: Int): VectorScanProvider? {
        ScanAudit.recordConsultation(kind, windowLength)
        return providerForPolicy(kind, windowLength)
    }

    /** Checks admission when choosing a route, before any scan dispatch site is entered.  */
    fun providerForPolicy(kind: ScanKind?, windowLength: Int): VectorScanProvider? {
        val selected = SELECTED
        if (selected == null) {
            return null
        }
        return if (windowLength >= selected.minimumWindowLength(kind)
            && windowLength <= selected.maximumWindowLength(kind)
        )
            selected
        else
            null
    }

    /**
     * Returns whether a Vector provider is installed at all, ignoring crossover thresholds. Use this
     * only for compile-time decisions, where no search window exists yet.
     */
    fun vectorProviderAvailable(): Boolean {
        return SELECTED != null
    }

    private fun loadSelected(): VectorScanProvider? {
        val requested = System.getProperty(PROVIDER_PROPERTY, "").trim()
        if (requested.isEmpty() || requested == "swar") {
            return null
        }
        check(requested == "vector") { unknownProviderMessage(requested) }
        try {
            return VectorScanProviderFactory.create()
        } catch (e: RuntimeException) {
            throw IllegalStateException(
                "Could not enable the experimental Vector UTF-8 scanner; use JDK 21 or later and add "
                        + "--add-modules=jdk.incubator.vector",
                e
            )
        } catch (e: LinkageError) {
            throw IllegalStateException(
                "Could not enable the experimental Vector UTF-8 scanner; use JDK 21 or later and add "
                        + "--add-modules=jdk.incubator.vector",
                e
            )
        }
    }

    fun unknownProviderMessage(requested: String): String {
        return "Unknown Vector scan provider " + requested
    }
}
