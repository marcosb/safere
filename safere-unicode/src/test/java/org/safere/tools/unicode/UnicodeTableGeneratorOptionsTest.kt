// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.tools.unicode

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.BiConsumer

internal class UnicodeTableGeneratorOptionsTest {
    @Test
    fun defaultsToCheckedInLicense() {
        val options: UnicodeTableGenerator.Options = UnicodeTableGenerator.Options.Companion.parse()
        Assertions.assertThat(options.output)
            .isEqualTo(Path.of("safere/src/main/java/org/safere/UnicodeGeneratedTables.java"))
        Assertions.assertThat(options.unicodeLicense)
            .isEqualTo(Path.of("safere/src/main/resources/META-INF/LICENSE-Unicode.txt"))
    }

    @Test
    fun outputOnlyKeepsDefaultLicense() {
        val options: UnicodeTableGenerator.Options = UnicodeTableGenerator.Options.Companion.parse("out/Tables.java")
        Assertions.assertThat(options.output).isEqualTo(Path.of("out/Tables.java"))
        Assertions.assertThat(options.unicodeLicense)
            .isEqualTo(Path.of("safere/src/main/resources/META-INF/LICENSE-Unicode.txt"))
    }

    @Test
    fun licenseAndOutputOverrides() {
        val options: UnicodeTableGenerator.Options =
            UnicodeTableGenerator.Options.Companion.parse("--unicode-license=LICENSE", "out/Tables.java")
        Assertions.assertThat(options.output).isEqualTo(Path.of("out/Tables.java"))
        Assertions.assertThat(options.unicodeLicense).isEqualTo(Path.of("LICENSE"))
    }

    @Test
    fun rejectsUnknownOptionsMissingValuesAndExtraOutputs() {
        Assertions.assertThatIllegalArgumentException()
            .isThrownBy(ThrowingCallable { UnicodeTableGenerator.Options.Companion.parse("--unicode-data=ucd") })
            .withMessageContaining("Usage:")
        Assertions.assertThatIllegalArgumentException()
            .isThrownBy(ThrowingCallable { UnicodeTableGenerator.Options.Companion.parse("--unicode-license=") })
            .withMessageContaining("Usage:")
        Assertions.assertThatIllegalArgumentException()
            .isThrownBy(ThrowingCallable { UnicodeTableGenerator.Options.Companion.parse("a.java", "b.java") })
            .withMessageContaining("Usage:")
    }

    @Test
    @Throws(Exception::class)
    fun regenerationIncludesUnicodeIdentifierExceptions(@TempDir directory: Path) {
        val output = directory.resolve("UnicodeGeneratedTables.java")
        val license = directory.resolve("LICENSE")
        Files.writeString(license, "Generator regression fixture")
        UnicodeTableGenerator.main(arrayOf<String>("--unicode-license=" + license, output.toString()))
        val generated = Files.readString(output)
        for (name in arrayOf<String>("javaUnicodeIdentifierStartExtra", "javaUnicodeIdentifierPartExtra")) {
            Assertions.assertThat(generated.contains("\"" + name + "\" to "))
                .`as`("regenerated internal table %s", name)
                .isTrue()
        }
    }

    @Test
    fun buildsGraphemeTablesFromIcu4j() {
        val tables = UnicodeTableGenerator.buildGraphemeTables()
        Assertions.assertThat<String?, Array<IntArray?>?>(tables)
            .containsOnlyKeys(
                "Control",
                "Extend",
                "Prepend",
                "SpacingMark",
                "L",
                "V",
                "T",
                "LV",
                "LVT",
                "InCB_Linker",
                "InCB_Consonant",
                "InCB_Extend"
            )
        tables.forEach(
            BiConsumer { name: String?, ranges: Array<IntArray?>? ->
                Assertions.assertThat(ranges).`as`(name).isNotEmpty()
                for (i in ranges!!.indices) {
                    Assertions.assertThat(ranges[i]).hasSize(2)
                    Assertions.assertThat(ranges[i]!![0]).isBetween(0, ranges[i]!![1])
                    Assertions.assertThat(ranges[i]!![1]).isLessThanOrEqualTo(Character.MAX_CODE_POINT)
                    if (i > 0) {
                        Assertions.assertThat(ranges[i]!![0]).isGreaterThan(ranges[i - 1]!![1] + 1)
                    }
                }
            })
    }
}
