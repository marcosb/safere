// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere

/**
 * Platform entry for optional Vector scan providers.
 *
 * The Maven JVM sources keep the full [org.safere.VectorScanProviders] implementation under
 * `safere/src/main/java`. The Gradle sidecar excludes that file from `commonMain` and supplies
 * this expect plus JVM/Native actuals so portable callers (e.g. [TeddyModel]) compile everywhere.
 */
internal expect object VectorScanProviders {
  val PROVIDER_PROPERTY: String

  fun providerFor(kind: ScanKind?, windowLength: Int): VectorScanProvider?

  fun providerForPolicy(kind: ScanKind?, windowLength: Int): VectorScanProvider?

  fun vectorProviderAvailable(): Boolean

  fun unknownProviderMessage(requested: String): String
}
