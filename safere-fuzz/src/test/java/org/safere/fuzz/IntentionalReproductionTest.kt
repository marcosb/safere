// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.function.Executable
import org.safere.Pattern
import org.safere.fuzz.FuzzSupport.CompiledPattern
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.List
import java.util.regex.MatchResult
import java.util.regex.PatternSyntaxException
import java.util.stream.Stream
import kotlin.streams.asStream

/** Exact reviewed reproductions; never used to suppress a fuzz finding.  */
internal class IntentionalReproductionTest {
    @TestFactory
    @Throws(IOException::class)
    fun replayReviewedCases(): Stream<DynamicTest?>? {
        var ids: MutableList<String?>?
        resource("index.txt").use { stream ->
            ids =
                String(stream.readAllBytes(), StandardCharsets.UTF_8).lineSequence().asStream()
                    .filter { line: String? -> !line!!.isBlank() && !line.startsWith("#") }
                    .toList()
        }
        val selected = System.getProperty("safere.fuzz.reproduction")
        if (selected != null) {
            Assertions.assertThat<String?>(ids).`as`("unknown reproduction ID").contains(selected)
            ids = List.of<String?>(selected)
        }
        return ids!!.stream()
            .map<DynamicTest?> { id: String? -> DynamicTest.dynamicTest(id, Executable { replay(id) }) }
    }

    companion object {
        private fun resource(name: String?): InputStream {
            return Objects.requireNonNull<InputStream>(
                IntentionalReproductionTest::class.java.getResourceAsStream("/intentional-reproductions/" + name),
                name
            )
        }

        @Throws(IOException::class)
        private fun replay(id: String?) {
            val spec = Properties()
            InputStreamReader(resource(id + ".properties"), StandardCharsets.UTF_8).use { reader ->
                spec.load(reader)
            }
            Assertions.assertThat(spec.getProperty("id")).isEqualTo(id)
            Assertions.assertThat(spec.getProperty("rule")).startsWith("INTENTIONAL_DIVERGENCES.md#")
            Assertions.assertThat(spec.getProperty("modelTest")).isNotBlank()
            val regex: String = required(spec, "regex")
            val input: String = required(spec, "input")
            val operation: String = required(spec, "operation")
            val flags = required(spec, "flags").toInt()
            val safe: String = safeOutcome(regex, flags, input, operation)
            val jdk: String = jdkOutcome(regex, flags, input, operation)
            Assertions.assertThat(safe)
                .`as`("%s: SafeRE model changed; reclassify this reproduction", id)
                .isEqualTo(required(spec, "safeRe"))
            Assertions.assertThat(jdk)
                .`as`("%s: JDK outcome changed; reclassify this reproduction", id)
                .isEqualTo(required(spec, "jdk"))
            Assertions.assertThat(safe).isNotEqualTo(jdk)
            // The registry classifies only this exact case. Broad comparison must still expose it.
            Assertions.assertThatThrownBy(
                ThrowingCallable {
                    val compiled = FuzzSupport.compileCompatibleOrSkip(regex, flags)
                    Assertions.assertThat<CompiledPattern?>(compiled).isNotNull()
                    if (operation == "matches") {
                        compiled!!.matcher(input).matches()
                    } else if (operation == "find") {
                        compiled!!.matcher(input).find()
                    } else require(operation == "compile") { operation }
                })
                .isInstanceOf(AssertionError::class.java)
                .hasMessageContaining("divergence")
        }

        private fun required(spec: Properties, key: String?): String {
            return Objects.requireNonNull<String>(spec.getProperty(key), key)
        }

        private fun safeOutcome(regex: String?, flags: Int, input: String?, operation: String): String {
            val pattern: Pattern?
            try {
                pattern = Pattern.compile(regex, flags)
            } catch (expected: PatternSyntaxException) {
                return "rejected"
            }
            if (operation == "compile") {
                return "accepted"
            }
            val matcher = pattern.matcher(input)
            val found =
                when (operation) {
                    "matches" -> matcher.matches()
                    "find" -> matcher.find()
                    else -> throw IllegalArgumentException(operation)
                }
            return snapshot(found, matcher)
        }

        private fun jdkOutcome(regex: String, flags: Int, input: String, operation: String): String {
            val pattern: java.util.regex.Pattern?
            try {
                pattern = java.util.regex.Pattern.compile(regex, flags)
            } catch (expected: PatternSyntaxException) {
                return "rejected"
            }
            if (operation == "compile") {
                return "accepted"
            }
            val matcher = pattern.matcher(input)
            val found =
                when (operation) {
                    "matches" -> matcher.matches()
                    "find" -> matcher.find()
                    else -> throw IllegalArgumentException(operation)
                }
            return snapshot(found, matcher)
        }

        private fun snapshot(found: Boolean, matcher: MatchResult): String {
            val result = StringBuilder(found.toString())
            if (found) {
                for (group in 0..matcher.groupCount()) {
                    val text = matcher.group(group)
                    result
                        .append('|')
                        .append(group)
                        .append(':')
                        .append(matcher.start(group))
                        .append(':')
                        .append(matcher.end(group))
                        .append(':')
                        .append(if (text == null) "null" else FuzzSupport.javaStringLiteral(text))
                }
            }
            return result.toString()
        }
    }
}
