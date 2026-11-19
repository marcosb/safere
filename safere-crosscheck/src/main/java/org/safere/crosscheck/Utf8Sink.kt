// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.crosscheck

import org.safere.Utf8Sink

/** Synchronous destination for borrowed UTF-8 ranges emitted by a crosscheck matcher.  */
@FunctionalInterface
interface Utf8Sink : Utf8Sink {
    /** Consumes the supplied borrowed byte range before returning.  */
    override fun append(bytes: ByteArray?, offset: Int, length: Int)
}
