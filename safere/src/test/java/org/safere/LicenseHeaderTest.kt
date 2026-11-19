// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Verifies that all Kotlin source files include the required license header.  */
@DisabledForCrosscheck("repository source-file validation is not regex API compatibility coverage")
internal class LicenseHeaderTest {
    @Test
    @Throws(IOException::class)
    fun allKotlinFilesHaveLicenseHeader() {
        val root = Paths.get("").toAbsolutePath()
        val srcDir = root.resolve("src")
        Assertions.assertThat(Files.isDirectory(srcDir))
            .withFailMessage("src/ directory not found at " + root)
            .isTrue()

        val violations: MutableList<Path?>?
        Files.walk(srcDir).use { files ->
            violations =
                files
                    .filter { p: Path? -> p.toString().endsWith(".kt") }
                    .filter { p: Path? -> !hasLicenseHeader(p!!) }
                    .toList()
        }
        Assertions.assertThat(violations!!.isEmpty())
            .withFailMessage(
                "The following files are missing the license header:\n"
                        + String.join(
                    "\n",
                    violations.stream().map<String?> { obj: Path? -> obj.toString() }.toList()
                )
            )
            .isTrue()
    }

    companion object {
        // Standard header (4 lines) — files derived from C++ RE2 only.
        private const val LINE_1 = "// This file is part of a Java port of RE2 (https://github.com/google/re2)."
        private const val LINE_2 = "// Original RE2 code is Copyright (c) 2009 The RE2 Authors."
        private const val LINE_3 = "// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian."
        private const val LINE_4 = "// Licensed under the BSD 3-Clause License (see LICENSE file)."

        // Extended header (6 lines) — files that also incorporate code from RE2/J.
        private const val RE2J_LINE_3 = "// Portions derived from RE2/J (https://github.com/google/re2j),"
        private const val RE2J_LINE_4 = "// Copyright (c) 2009 The Go Authors."
        private const val RE2J_LINE_5 = "// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian."
        private const val RE2J_LINE_6 = "// Licensed under the BSD 3-Clause License (see LICENSE file)."

        private fun hasLicenseHeader(path: Path): Boolean {
            try {
                val lines = Files.readAllLines(path)
                return hasStandardHeader(lines) || hasRe2jHeader(lines)
            } catch (e: IOException) {
                return false
            }
        }

        /** Standard 4-line header for files derived from C++ RE2 only.  */
        private fun hasStandardHeader(lines: MutableList<String?>): Boolean {
            return lines.size >= 4 && lines.get(0) == LINE_1
                    && lines.get(1) == LINE_2
                    && lines.get(2) == LINE_3
                    && lines.get(3) == LINE_4
        }

        /** Extended 6-line header for files that also incorporate code from RE2/J.  */
        private fun hasRe2jHeader(lines: MutableList<String?>): Boolean {
            return lines.size >= 6 && lines.get(0) == LINE_1
                    && lines.get(1) == LINE_2
                    && lines.get(2) == RE2J_LINE_3
                    && lines.get(3) == RE2J_LINE_4
                    && lines.get(4) == RE2J_LINE_5
                    && lines.get(5) == RE2J_LINE_6
        }
    }
}
