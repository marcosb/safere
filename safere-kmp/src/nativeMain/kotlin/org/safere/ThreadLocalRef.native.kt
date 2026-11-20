// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Native actual for [ThreadLocalRef].
 *
 * SafeRE's work-counter tests do not run on native yet; a simple per-instance slot is enough for
 * nested [WorkCounter] accounting on a single worker.
 */
internal actual class ThreadLocalRef<T : Any> actual constructor() {
  private var value: T? = null

  actual fun get(): T? = value

  actual fun set(value: T?) {
    this.value = value
  }

  actual fun remove() {
    value = null
  }
}
