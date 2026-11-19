// This file is part of a Kotlin port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Kotlin port Copyright (c) 2026 Marcos Boyington.
// Licensed under the BSD 3-Clause License (see LICENSE file).

/**
 * SafeRE: a linear-time regular expression matching library for Java.
 */
module org.safere {
    requires kotlin.stdlib;
    // Compile-time only: Kotlin cannot resolve Vector API symbols via --add-reads alone the way
    // javac could. STATIC keeps the incubating module off consumers' required set at runtime.
    requires static jdk.incubator.vector;

    exports org.safere;
}
