// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere

/**
 * Gradle JVM actual for [VectorScanProviders].
 *
 * Mirrors the Maven-shipped implementation in `safere/src/main/java/.../VectorScanProviders.kt`
 * so the public/experimental Vector property contract stays aligned without marking that shared
 * file `actual` (Maven kotlin-maven-plugin is not multiplatform).
 */
internal actual object VectorScanProviders {
  actual val PROVIDER_PROPERTY: String = "org.safere.experimental.vectorScanProvider"

  private val SELECTED: VectorScanProvider? = loadSelected()

  actual fun providerFor(kind: ScanKind?, windowLength: Int): VectorScanProvider? {
    ScanAudit.recordConsultation(kind, windowLength)
    return providerForPolicy(kind, windowLength)
  }

  actual fun providerForPolicy(kind: ScanKind?, windowLength: Int): VectorScanProvider? {
    val selected = SELECTED ?: return null
    return if (windowLength >= selected.minimumWindowLength(kind)
      && windowLength <= selected.maximumWindowLength(kind)
    ) {
      selected
    } else {
      null
    }
  }

  actual fun vectorProviderAvailable(): Boolean = SELECTED != null

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

  actual fun unknownProviderMessage(requested: String): String =
    "Unknown Vector scan provider $requested"
}
