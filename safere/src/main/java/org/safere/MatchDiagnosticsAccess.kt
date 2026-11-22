// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Access to the process-wide match diagnostics listener.
 *
 * The Maven JVM build compiles this implementation. The KMP sidecar excludes it and supplies
 * expect/actual overlays so native targets compile without [Pattern]'s MethodHandle site.
 */
internal object MatchDiagnosticsAccess {
  fun current(): SafeReMatchDiagnostics = Pattern.diagnostics()
}
