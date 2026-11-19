// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere

/** Native actual: no JDK Vector provider; scalar/SWAR-free paths only. */
internal actual object VectorScanProviders {
  actual val PROVIDER_PROPERTY: String = "org.safere.experimental.vectorScanProvider"

  actual fun providerFor(kind: ScanKind?, windowLength: Int): VectorScanProvider? = null

  actual fun providerForPolicy(kind: ScanKind?, windowLength: Int): VectorScanProvider? = null

  actual fun vectorProviderAvailable(): Boolean = false

  actual fun unknownProviderMessage(requested: String): String =
    "Unknown Vector scan provider $requested"
}
