// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.jvm.JvmOverloads

/**
 * JVM Pattern-typed entry points for [SafeReDiagnostics].
 *
 * Kept on jvmMain so the common [SafeReDiagnostics] API can take [Prog] without naming
 * [Pattern].
 */
object SafeReDiagnosticsPattern {
  /**
   * Exports one verifier fixture JSONL record using SafeRE's NFA-facing compiled program.
   *
   * @param pattern compiled SafeRE pattern whose NFA program is exported
   */
  fun bytecodeCaseToJsonLine(
    caseId: String?,
    regex: String?,
    flags: Int,
    input: String?,
    mode: SafeReDiagnostics.BytecodeMatchMode?,
    engine: SafeReDiagnostics.BytecodeResultEngine?,
    pattern: Pattern?,
  ): String {
    requireNotNull(pattern) { "pattern" }
    return SafeReDiagnostics.bytecodeCaseToJsonLine(
      caseId,
      regex,
      flags,
      input,
      mode,
      engine,
      pattern.prog(),
    )
  }

  /**
   * Compiles a pattern (when omitted) and exports one verifier fixture JSONL record.
   */
  @JvmOverloads
  fun bytecodeCaseToJsonLine(
    caseId: String?,
    regex: String?,
    flags: Int,
    input: String?,
    mode: SafeReDiagnostics.BytecodeMatchMode?,
    pattern: Pattern? = Pattern.compile(regex, flags),
  ): String {
    return bytecodeCaseToJsonLine(
      caseId,
      regex,
      flags,
      input,
      mode,
      SafeReDiagnostics.BytecodeResultEngine.NFA,
      pattern,
    )
  }
}
