// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.crosscheck

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.Set
import java.util.regex.MatchResult
import java.util.regex.Matcher
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory

/** Policy checks for generated public API crosscheck coverage.  */
internal class CrosscheckGenerationPolicyTest {
    @Test
    @DisplayName("@DisabledForCrosscheck reasons are non-empty")
    @Throws(IOException::class)
    fun disabledForCrosscheckReasonsAreNonEmpty() {
        val violations: MutableSet<String?> = TreeSet<String?>()
        for (file in testSources()) {
            val source = Files.readString(file)
            val matcher: Matcher = DISABLED_ANNOTATION.matcher(source)
            while (matcher.find()) {
                val reason: String = annotationReason(matcher.group(1))
                if (reason.isBlank()) {
                    violations.add(file.getFileName().toString() + " at offset " + matcher.start())
                }
            }
        }

        Assertions.assertThat<String?>(violations).isEmpty()
    }

    @Test
    @DisplayName("crosscheck generation has no manual filename exclusions")
    fun noManualFilenameExclusions() {
        val nodes: NodeList = pomDocument()!!.getElementsByTagName("exclude")
        Assertions.assertThat(nodes.getLength()).isZero()
    }

    @Test
    @EnabledIfSystemProperty(named = "org.safere.crosscheck.generatedTests", matches = "true")
    @Throws(IOException::class)
    fun generatedSourcesFollowAnnotationExclusions() {
        val generated =
            Path.of(
                "target",
                "generated-test-sources",
                "crosscheck",
                "org",
                "safere",
                "crosscheck",
                "generated"
            )
        val excluded =
            Set.copyOf<String?>(
                Files.readAllLines(
                    Path.of("target", "crosscheck-selection", "crosscheck-excludes.txt")
                )
            )
        for (source in testSources()) {
            val filename = source.getFileName().toString()
            Assertions.assertThat(Files.isRegularFile(generated.resolve(filename)))
                .`as`("generation of %s", filename)
                .isEqualTo(!excluded.contains(filename))
        }
        Assertions.assertThat<String?>(excluded)
            .contains("DisabledForCrosscheckConditionTest.java", "ParserTest.java")
            .doesNotContain("PatternTest.java", "BoundaryMatcherTest.java")
        Assertions.assertThat(Files.isRegularFile(generated.resolve("DisabledForCrosscheck.java"))).isTrue()
        Assertions.assertThat(Files.isRegularFile(generated.resolve("ExhaustiveUtils.java"))).isTrue()
    }

    companion object {
        private val SAFERE_TEST_DIR: Path = Path.of("..", "safere", "src", "test", "java", "org", "safere")
        private val POM: Path = Path.of("pom.xml")
        private val DISABLED_ANNOTATION: Pattern = Pattern.compile(
            "@DisabledForCrosscheck\\s*\\(((?:\\s*\"(?:\\\\.|[^\"\\\\])*\"\\s*\\+?)+)\\)",
            Pattern.DOTALL
        )
        private val STRING_LITERAL: Pattern = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"")

        @Throws(IOException::class)
        private fun testSources(): MutableList<Path> {
            Files.list(SAFERE_TEST_DIR).use { files ->
                return files
                    .filter { path: Path? -> path!!.getFileName().toString().endsWith("Test.java") }
                    .sorted()
                    .toList()
            }
        }

        private fun pomDocument(): Element? {
            try {
                val factory = DocumentBuilderFactory.newInstance()
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                factory.setExpandEntityReferences(false)
                return factory.newDocumentBuilder().parse(POM.toFile()).getDocumentElement()
            } catch (e: Exception) {
                throw AssertionError("Unable to parse " + POM, e)
            }
        }

        private fun annotationReason(annotationArgument: String): String {
            val literals: Matcher = STRING_LITERAL.matcher(annotationArgument)
            return literals
                .results()
                .map<String?> { obj: MatchResult? -> obj!!.group() }
                .map<String> { literal: String? -> literal!!.substring(1, literal.length - 1) }
                .reduce("") { obj: String?, str: String? -> obj + str }
        }
    }
}
