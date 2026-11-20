// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/** Gradle JVM actual mirroring the Maven [ThreadLocalRef] implementation. */
internal actual class ThreadLocalRef<T : Any> actual constructor() {
  private val local = ThreadLocal<T?>()

  actual fun get(): T? = local.get()

  actual fun set(value: T?) {
    if (value == null) {
      local.remove()
    } else {
      local.set(value)
    }
  }

  actual fun remove() {
    local.remove()
  }
}
