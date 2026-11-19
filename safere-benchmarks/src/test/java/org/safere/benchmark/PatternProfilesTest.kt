// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.re2j.Pattern
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

internal class PatternProfilesTest {
    @Test
    fun selectsExplicitAlternateAndFallsBackToJava() {
        val profiles: PatternProfiles =
            PatternProfiles.Companion.parse(
                JsonParser.parseString(
                    """
                {
                  "alternate-engine": [{
                    "java": "\\Qfoo.bar\\E",
                    "alternate": "foo\\.bar",
                    "reason": "Alternate engine does not support Java quoted literals"
                  }]
                }
                
                """.trimIndent()
                )
            )

        Assertions.assertThat(profiles.select("alternate-engine", "\\Qfoo.bar\\E")).isEqualTo("foo\\.bar")
        Assertions.assertThat(profiles.select("alternate-engine", "unchanged")).isEqualTo("unchanged")
        Assertions.assertThat(profiles.select("go-regexp", "\\Qfoo.bar\\E")).isEqualTo("\\Qfoo.bar\\E")
    }

    @Test
    fun profileFallbackUsesSpecificOverridesAndInheritedAlternates() {
        val profiles: PatternProfiles =
            PatternProfiles.Companion.parse(
                JsonParser.parseString(
                    """
                    {
                      "re2": [
                        {"java": "shared", "alternate": "shared RE2", "reason": "shared"},
                        {"java": "specific", "alternate": "generic RE2", "reason": "generic"}
                      ],
                      "re2-cpp": [{"java": "specific", "alternate": "specific C++", "reason": "specific"}]
                    }
                    
                    """.trimIndent()
                )
            )
                .withFallback("re2-cpp", "re2")

        Assertions.assertThat(profiles.select("re2-cpp", "shared")).isEqualTo("shared RE2")
        Assertions.assertThat(profiles.select("re2-cpp", "specific")).isEqualTo("specific C++")
        Assertions.assertThat(profiles.select("re2-cpp", "unchanged")).isEqualTo("unchanged")
    }

    @Test
    fun missingFallbackProfileKeepsSpecificOverridesAndJavaValues() {
        val profiles: PatternProfiles =
            PatternProfiles.Companion.parse(
                JsonParser.parseString(
                    """
                    {
                      "re2-cpp": [{"java": "specific", "alternate": "specific C++", "reason": "specific"}]
                    }
                    
                    """.trimIndent()
                )
            )
                .withFallback("re2-cpp", "re2")

        Assertions.assertThat(profiles.select("re2-cpp", "specific")).isEqualTo("specific C++")
        Assertions.assertThat(profiles.select("re2-cpp", "unchanged")).isEqualTo("unchanged")
    }

    @Test
    fun rejectsMalformedAndDuplicateAlternates() {
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                PatternProfiles.Companion.parse(
                    JsonParser.parseString(
                        """
                        {"Rust": []}
                        
                        """.trimIndent()
                    )
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Invalid benchmark pattern profile ID: Rust")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                PatternProfiles.Companion.parse(
                    JsonParser.parseString(
                        """
                        {
                          "alternate-engine": [
                            {"java": "x", "alternate": "y", "reason": "first"},
                            {"java": "x", "alternate": "z", "reason": "second"}
                          ]
                        }
                        
                        """.trimIndent()
                    )
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Pattern profile alternate-engine repeats Java pattern: x")
    }

    @Test
    fun rejectsTopLevelAndConflictingInlineAlternates() {
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                PatternProfiles.Companion.normalizeInline(
                    JsonParser.parseString(
                        """
                            {"patternProfiles": {}}
                            
                            """.trimIndent()
                    )
                        .getAsJsonObject()
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("benchmark-data.json must define syntax alternates next to their Java values")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                PatternProfiles.Companion.normalizeInline(
                    JsonParser.parseString(
                        """
                            {
                              "first": {
                                "pattern": {
                                  "java": "x",
                                  "alternates": {
                                    "re2": {"pattern": "y", "reason": "first"}
                                  }
                                }
                              },
                              "second": {
                                "pattern": {
                                  "java": "x",
                                  "alternates": {
                                    "re2": {"pattern": "z", "reason": "second"}
                                  }
                                }
                              }
                            }
                            
                            """.trimIndent()
                    )
                        .getAsJsonObject()
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Conflicting re2 alternates for Java pattern: x")
    }

    @Test
    fun rejectsAlternateWhoseKindDoesNotMatchContainingField() {
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                PatternProfiles.Companion.normalizeInline(
                    JsonParser.parseString(
                        """
                            {
                              "pattern": {
                                "java": "x",
                                "alternates": {
                                  "rust-regex": {
                                    "replacement": "y",
                                    "reason": "wrong kind"
                                  }
                                }
                              }
                            }
                            
                            """.trimIndent()
                    )
                        .getAsJsonObject()
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Inline benchmark pattern definition contains replacement alternate")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                PatternProfiles.Companion.normalizeInline(
                    JsonParser.parseString(
                        """
                            {
                              "replacement": {
                                "java": "x",
                                "alternates": {
                                  "rust-regex": {
                                    "pattern": "y",
                                    "reason": "wrong kind"
                                  }
                                }
                              }
                            }
                            
                            """.trimIndent()
                    )
                        .getAsJsonObject()
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Inline benchmark replacement definition contains pattern alternate")
    }

    @Test
    fun separatesPatternAndReplacementAlternatesWithTheSameJavaValue() {
        val normalized: JsonObject =
            PatternProfiles.Companion.normalizeInline(
                JsonParser.parseString(
                    """
                    {
                      "pattern": {
                        "java": "same",
                        "alternates": {
                          "rust-regex": {"pattern": "pattern", "reason": "pattern syntax"}
                        }
                      },
                      "replacement": {
                        "java": "same",
                        "alternates": {
                          "rust-regex": {
                            "replacement": "replacement",
                            "reason": "replacement syntax"
                          }
                        }
                      }
                    }
                    
                    """.trimIndent()
                )
                    .getAsJsonObject()
            )

        val patterns: PatternProfiles = PatternProfiles.Companion.parse(normalized.get("patternProfiles"))
        val replacements: PatternProfiles = PatternProfiles.Companion.parse(normalized.get("replacementProfiles"))
        Assertions.assertThat(patterns.select("rust-regex", "same")).isEqualTo("pattern")
        Assertions.assertThat(replacements.select("rust-regex", "same")).isEqualTo("replacement")
    }

    @Test
    fun rejectsProfilesThatNoAuthoritativeWorkloadReferences() {
        val profiles: PatternProfiles =
            PatternProfiles.Companion.parse(
                JsonParser.parseString(
                    """
                {
                  "re2": [{
                    "java": "legacy-only",
                    "alternate": "alternate",
                    "reason": "Only a deleted legacy section used this value"
                  }]
                }
                
                """.trimIndent()
                )
            )

        Assertions.assertThatThrownBy(ThrowingCallable {
            profiles.validateReferences(
                mutableSetOf<String?>("used"),
                "pattern"
            )
        })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Unreferenced pattern profile value for re2: legacy-only")
    }

    @Test
    @Throws(Exception::class)
    fun checkedInProfilesContainReviewedDialectAlternates() {
        val benchmarkData =
            if (Files.exists(Path.of("benchmark-data.json")))
                Path.of("benchmark-data.json")
            else
                Path.of("safere-benchmarks", "benchmark-data.json")
        val root = JsonParser.parseString(Files.readString(benchmarkData)).getAsJsonObject()
        val normalized: JsonObject = PatternProfiles.Companion.normalizeInline(root)
        val patterns: PatternProfiles = PatternProfiles.Companion.parse(normalized.get("patternProfiles"))
        val replacements: PatternProfiles = PatternProfiles.Companion.parse(normalized.get("replacementProfiles"))

        Assertions.assertThat(patterns.select("re2", "\\p{script=Latin}+")).isEqualTo("\\p{Latin}+")
        Assertions.assertThat(patterns.select("re2", "[\\p{L}&&[^\\p{Lu}]]+"))
            .isEqualTo("[\\p{Ll}\\p{Lt}\\p{Lm}\\p{Lo}]+")
        Assertions.assertThat(patterns.select("pcre2", "\\p{block=BasicLatin}+")).isEqualTo("[\\x{0}-\\x{7F}]+")
        Assertions.assertThat(patterns.select("pcre2", "[\\p{L}&&[^\\p{Lu}]]+"))
            .isEqualTo("[\\p{Ll}\\p{Lt}\\p{Lm}\\p{Lo}]+")
        Assertions.assertThat(patterns.select("pcre2", "\\p{javaLetter}")).isEqualTo("\\p{L}")
        Assertions.assertThat(patterns.select("pcre2", "\\p{script=Latin}+")).isEqualTo("\\p{sc=Latin}+")
        Assertions.assertThat(patterns.resolve("re2", "\\p{IsAlphabetic}+", "0").unsupportedReason)
            .contains("IsAlphabetic")
        Assertions.assertThat(patterns.resolve("re2", "\\p{IsIdeographic}+", "0").unsupportedReason)
            .contains("IsIdeographic")
        Assertions.assertThat(patterns.select("re2", "\\s+", "0")).isEqualTo("[ \\t\\n\\x0B\\f\\r]+")
        Assertions.assertThat(patterns.select("rust-regex", "^\\s*<(\\QApple\\E|\\QBanana\\E|\\QCherry\\E)>\\s*$"))
            .isEqualTo("^[[:space:]]*<(Apple|Banana|Cherry)>[[:space:]]*$")
        Assertions.assertThat(patterns.select("rust-regex", "(\\d{4})-(\\d{2})-(\\d{2})"))
            .isEqualTo("([0-9]{4})-([0-9]{2})-([0-9]{2})")
        Assertions.assertThat(patterns.select("rust-regex", "\\b\\w+ing\\b"))
            .isEqualTo("(?-u:\\b)[A-Za-z0-9_]+ing(?-u:\\b)")
        Assertions.assertThat(replacements.select("re2-cpp", "$1=[$2]")).isEqualTo("\\1=[\\2]")
        Assertions.assertThat(replacements.select("re2-cpp", "\${key}=[\${value}]")).isEqualTo("\\1=[\\2]")
        Assertions.assertThat(replacements.select("re2-cpp", "$1<tag type=\"custom\""))
            .isEqualTo("\\1<tag type=\"custom\"")
        Assertions.assertThat(replacements.select("re2-cpp", "$2$1ay")).isEqualTo("\\2\\1ay")
        Assertions.assertThat(replacements.select("re2-cpp", "$1=REDACTED")).isEqualTo("\\1=REDACTED")
        Assertions.assertThat(replacements.select("re2-cpp", "$1")).isEqualTo("\\1")
        Assertions.assertThat(replacements.select("re2-cpp", "[$0]")).isEqualTo("[\\0]")
        Assertions.assertThat(replacements.select("re2-cpp", "[$1://$2-modified:$3]"))
            .isEqualTo("[\\1://\\2-modified:\\3]")
        Assertions.assertThat(replacements.select("go-regexp", "$2$1ay")).isEqualTo("\${2}\${1}ay")
        Assertions.assertThat(replacements.select("pcre2", "$1")).isEqualTo("$1")
        Assertions.assertThat(replacements.select("rust-regex", "$2$1ay")).isEqualTo("\${2}\${1}ay")
        Assertions.assertThat(patterns.select("dotnet", "^\\s*<(\\QApple\\E|\\QBanana\\E|\\QCherry\\E)>\\s*$"))
            .isEqualTo("^[ \\t\\n\\x0B\\f\\r]*<(Apple|Banana|Cherry)>[ \\t\\n\\x0B\\f\\r]*$")
        Assertions.assertThat(patterns.select("dotnet", "[😀-😇]")).isEqualTo("\\uD83D[\\uDE00-\\uDE07]")
        Assertions.assertThat(patterns.select("dotnet", "\\p{javaLetter}")).isEqualTo("\\p{L}")
        Assertions.assertThat(patterns.select("dotnet", "\\p{block=BasicLatin}+")).isEqualTo("[\\u0000-\\u007F]+")
        Assertions.assertThat(patterns.select("dotnet", "\\p{block=CJK_Unified_Ideographs}+"))
            .isEqualTo("[\\u4E00-\\u9FFF]+")
        Assertions.assertThat(patterns.select("dotnet", "[\\p{L}&&[^\\p{Lu}]]+")).isEqualTo("[\\p{L}-[\\p{Lu}]]+")
        Assertions.assertThat(patterns.select("dotnet", "\\w+", "0")).isEqualTo("[A-Za-z0-9_]+")
        Assertions.assertThat(patterns.select("dotnet", "\\w+", "UNICODE_CHARACTER_CLASS")).isEqualTo("\\w+")
        Assertions.assertThat(patterns.select("dotnet", "\\w+", "CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS"))
            .isEqualTo("\\w+")
        for (profileType in mutableListOf<String?>("patternProfiles", "replacementProfiles")) {
            for (profile in normalized.getAsJsonObject(profileType).asMap().values) {
                for (entry in profile.getAsJsonArray()) {
                    val javaValue = entry.getAsJsonObject().get("java").getAsString()
                    Assertions.assertThat(containsString(root.getAsJsonArray("workloads"), javaValue))
                        .`as`("checked-in workload uses canonical syntax value %s", javaValue)
                        .isTrue()
                }
            }
        }
        for (entry in normalized.getAsJsonObject("patternProfiles").getAsJsonArray("re2")) {
            val alt = entry.getAsJsonObject().get("alternate")
            if (alt != null && !alt.isJsonNull()) {
                Assertions.assertThat<Pattern?>(Pattern.compile(alt.getAsString())).isNotNull()
            }
        }
    }

    companion object {
        private fun containsString(element: JsonElement, expected: String?): Boolean {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                return element.getAsString() == expected
            }
            if (element.isJsonArray()) {
                for (child in element.getAsJsonArray()) {
                    if (containsString(child, expected)) {
                        return true
                    }
                }
            } else if (element.isJsonObject()) {
                for (child in element.getAsJsonObject().asMap().values) {
                    if (containsString(child, expected)) {
                        return true
                    }
                }
            }
            return false
        }
    }
}
