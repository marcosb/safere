// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.crosscheck.build

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.List
import javax.tools.ToolProvider

/** Exercises the generation boundary with independently compiled source fixtures.  */
internal class ExclusionSelectionTest {
    @TempDir
    var temporary: Path? = null

    @Test
    @Throws(Exception::class)
    fun excludesOnlyAnnotatedTopLevelTests() {
        val sources = temporary!!.resolve("sources")
        Files.createDirectories(sources)
        source(
            sources,
            "DisabledForCrosscheck",
            """
        package org.safere;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.TYPE, ElementType.METHOD})
        @interface DisabledForCrosscheck { String value(); }
        
        """.trimIndent()
        )
        source(sources, "Internal", "package org.safere; class Internal {}")
        source(
            sources,
            "InternalTest",
            """
        package org.safere;
        @DisabledForCrosscheck("internal")
        class InternalTest { Internal value; }
        
        """.trimIndent()
        )
        source(
            sources,
            "QualifiedTest",
            """
        package org.safere;
        @org.safere.DisabledForCrosscheck(value = "qualified")
        class QualifiedTest {}
        
        """.trimIndent()
        )
        source(
            sources,
            "RetainedTest",
            """
        package org.safere;
        // @DisabledForCrosscheck("comment")
        class RetainedTest {
          String text = "@DisabledForCrosscheck";
          @DisabledForCrosscheck("method") void test() {}
          @DisabledForCrosscheck("nested") class Nested {}
        }
        
        """.trimIndent()
        )
        source(
            sources,
            "PlainTest",
            """
        package org.safere;
        class PlainTest {
          static { if (true) throw new AssertionError("must not initialize tests"); }
        }
        
        """.trimIndent()
        )
        source(
            sources,
            "TextBlockTest",
            "package org.safere; class TextBlockTest { String text = \"\"\"\n"
                    + "@DisabledForCrosscheck(\"fake\") class Fake {}\n\"\"\"; }"
        )
        val output = select(sources)
        Assertions.assertThat<String>(Files.readAllLines(output))
            .containsExactly("InternalTest.java", "QualifiedTest.java")
        Files.delete(sources.resolve("InternalTest.java"))
        source(sources, "QualifiedTest", "package org.safere; class QualifiedTest {}")
        Assertions.assertThat(Files.readString(select(sources))).isEmpty()
    }

    @Throws(Exception::class)
    private fun select(sources: Path): Path {
        val classes = temporary!!.resolve("classes")
        Files.createDirectories(classes)
        val compiler = ToolProvider.getSystemJavaCompiler()
        compiler.getStandardFileManager(null, null, null).use { manager ->
            val files: MutableList<Path?>?
            Files.list(sources).use { paths ->
                files = paths.filter { path: Path? -> path.toString().endsWith(".java") }.sorted().toList()
            }
            val options = ArrayList<String?>(List.of<String?>("-d", classes.toString()))
            options.add("-proc:only")
            val task =
                compiler.getTask(
                    null, manager, null, options, null, manager.getJavaFileObjectsFromPaths(files)
                )
            task.setProcessors(List.of<ExclusionProcessor?>(ExclusionProcessor()))
            Assertions.assertThat(task.call()).isTrue()
        }
        return classes.resolve("crosscheck-excludes.txt")
    }

    companion object {
        @Throws(IOException::class)
        private fun source(directory: Path, name: String?, text: String) {
            Files.writeString(directory.resolve(name + ".java"), text)
        }
    }
}
