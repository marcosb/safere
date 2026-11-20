// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Thin thread-local holder used by [WorkCounter].
 *
 * The Maven JVM build compiles this implementation. The KMP sidecar excludes it and supplies
 * expect/actual overlays so native targets compile without `java.lang.ThreadLocal`.
 */
internal class ThreadLocalRef<T : Any> {
  private val local = ThreadLocal<T?>()

  fun get(): T? = local.get()

  fun set(value: T?) {
    if (value == null) {
      local.remove()
    } else {
      local.set(value)
    }
  }

  fun remove() {
    local.remove()
  }
}
