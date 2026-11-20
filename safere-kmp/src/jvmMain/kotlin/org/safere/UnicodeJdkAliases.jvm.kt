// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/** Gradle JVM actual mirroring the Maven [UnicodeJdkAliases] implementation. */
internal actual object UnicodeJdkAliases {
  actual fun resolveBlockTableKey(name: String): String? {
    return try {
      toTitleSnake(Character.UnicodeBlock.forName(name).toString())
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  actual fun resolveScriptTableKey(name: String): String? {
    return try {
      scriptName(Character.UnicodeScript.forName(name))
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  private fun scriptName(script: Character.UnicodeScript): String {
    if (script == Character.UnicodeScript.SIGNWRITING) {
      return "SignWriting"
    }
    return toTitleSnake(script.name)
  }

  private fun toTitleSnake(upperSnake: String): String {
    val result = StringBuilder(upperSnake.length)
    for (part in upperSnake.split("_")) {
      if (result.isNotEmpty()) {
        result.append('_')
      }
      result.append(part[0].uppercaseChar())
      if (part.length > 1) {
        result.append(part.substring(1).lowercase())
      }
    }
    return result.toString()
  }
}
