// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

/**
 * Unaligned 64-bit loads from a [ByteArray] for SWAR scanners.
 *
 * The Maven JVM build compiles this VarHandle implementation. The KMP sidecar excludes it and
 * supplies expect/actual overlays so native targets compile without `java.lang.invoke`.
 */
internal object ByteArrayLongView {
  const val LONG_BYTES: Int = java.lang.Long.BYTES

  private val NATIVE_VIEW: VarHandle =
    MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.nativeOrder())

  private val LITTLE_ENDIAN_VIEW: VarHandle =
    MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)

  fun getNative(bytes: ByteArray, index: Int): Long = NATIVE_VIEW.get(bytes, index) as Long

  fun getLittleEndian(bytes: ByteArray, index: Int): Long =
    LITTLE_ENDIAN_VIEW.get(bytes, index) as Long

  val isNativeLittleEndian: Boolean
    get() = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
}
