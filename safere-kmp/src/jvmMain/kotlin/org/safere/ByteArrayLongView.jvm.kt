// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

/** Gradle JVM actual mirroring the Maven [ByteArrayLongView] implementation. */
internal actual object ByteArrayLongView {
  actual val LONG_BYTES: Int = java.lang.Long.BYTES

  private val NATIVE_VIEW: VarHandle =
    MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.nativeOrder())

  private val LITTLE_ENDIAN_VIEW: VarHandle =
    MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)

  actual fun getNative(bytes: ByteArray, index: Int): Long = NATIVE_VIEW.get(bytes, index) as Long

  actual fun getLittleEndian(bytes: ByteArray, index: Int): Long =
    LITTLE_ENDIAN_VIEW.get(bytes, index) as Long

  actual val isNativeLittleEndian: Boolean
    get() = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
}
