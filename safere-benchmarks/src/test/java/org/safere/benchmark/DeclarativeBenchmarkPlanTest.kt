// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.gson.JsonParser
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.assertj.core.api.ThrowingConsumer
import org.assertj.core.api.iterable.ThrowingExtractor
import org.assertj.core.groups.Tuple
import org.junit.jupiter.api.Test
import org.safere.benchmark.DeclarativeBenchmarkPlan.*
import java.util.*
import java.util.List
import java.util.function.Function
import java.util.function.Predicate
import kotlin.collections.first

internal class DeclarativeBenchmarkPlanTest {
    @Test
    fun syntheticWorkloadAndEnginesExtendPlanWithoutPlannerBranches() {
        val plan: DeclarativeBenchmarkPlan =
            parse(
                """
            {
              "schemaVersion": 2,
              "inputs": [{
                "id": "generated.input.{size}",
                "axes": {"size": [8, 16]},
                "recipe": {
                  "kind": "repeatToLength",
                  "unit": "ab",
                  "length": "{size}"
                }
              }],
              "workloads": [{
                "id": "ScalingBenchmark.find.{size}.{match}",
                "operation": "find",
                "patterns": ["needle-{match}"],
                "inputs": ["generated.input.{size}"],
                "axes": {
                  "size": [8, 16],
                  "match": [true, false]
                },
                "requirements": ["find"],
                "resultConsumption": "boolean",
                "expected": {"type": "boolean", "axis": "match"},
                "measurement": {
                  "mode": "averageTime",
                  "timingUnit": "microseconds",
                  "constraints": ["prematerializedInput"]
                }
              }]
            }
            
            """.trimIndent()
            )

        val engine: EngineDeclaration =
            engine(
                "test-string",
                InputRepresentation.JAVA_STRING,
                Feature.FIND
            )
        val additionalEngine: EngineDeclaration =
            engine(
                "additional-string",
                InputRepresentation.JAVA_STRING,
                Feature.FIND
            )
        val first =
            plan.expand(
                List.of<EngineDeclaration?>(engine, additionalEngine),
                EnumSet.of<Operation?>(Operation.FIND)
            )
        val second =
            plan.expand(
                List.of<EngineDeclaration?>(engine, additionalEngine),
                EnumSet.of<Operation?>(Operation.FIND)
            )

        Assertions.assertThat<String?>(plan.inputs().keys).containsExactly("generated.input.8", "generated.input.16")
        Assertions.assertThat<RecipeValue?>(plan.inputs().get("generated.input.8")!!.recipe.arguments.get("length"))
            .isEqualTo(RecipeInteger(8))
        Assertions.assertThat<String?>(first.workloads.stream().map<String?>(ExpandedWorkload::id))
            .containsExactly(
                "ScalingBenchmark.find.8.true",
                "ScalingBenchmark.find.8.false",
                "ScalingBenchmark.find.16.true",
                "ScalingBenchmark.find.16.false"
            )
        Assertions.assertThat<String?>(first.trials.stream().map<String?> { id() })
            .containsExactlyElementsOf(
                second.trials.stream().map<String?> { id() }.toList()
            )
            .hasSize(8)
            .allMatch(Predicate { id: String? -> id!!.endsWith("@test-string") || id.endsWith("@additional-string") })
        Assertions.assertThat<Boolean?>(
            first.workloads.stream()
                .map<Boolean?> { workload: ExpandedWorkload? -> workload!!.expected.value().getAsBoolean() })
            .containsExactly(true, false, true, false)
    }

    @Test
    fun patternAxisSubstitutionPreservesRegexBraceSyntax() {
        val plan: DeclarativeBenchmarkPlan =
            parse(
                planJson(
                    """
                [{
                  "id": "Synthetic.braces.{suffix}",
                  "operation": "find",
                  "patterns": ["\\x{feff}{suffix}"],
                  "inputs": ["literal.input"],
                  "axes": {"suffix": ["x"]},
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                
                """.trimIndent()
                )
            )
        val engine: EngineDeclaration =
            engine(
                "string",
                InputRepresentation.JAVA_STRING,
                Feature.FIND
            )

        val expanded =
            plan.expand(
                List.of<EngineDeclaration?>(engine),
                EnumSet.of<Operation?>(Operation.FIND)
            )

        Assertions.assertThat<ExpandedWorkload?>(expanded.workloads)
            .singleElement()
            .extracting<String?>(Function { workload: ExpandedWorkload? -> workload!!.patterns.first() })
            .isEqualTo("\\x{feff}x")
    }

    @Test
    fun labeledAxisSeparatesStableIdentityFromPatternValue() {
        val plan: DeclarativeBenchmarkPlan =
            parse(
                """
            {
              "schemaVersion": 2,
              "inputs": [],
              "workloads": [{
                  "id": "Synthetic.unicode.{regex}",
                  "operation": "compile",
                  "patterns": ["{regex}"],
                  "axes": {
                    "regex": [{"id": "letter", "value": "\\p{L}+"}]
                  },
                  "resultConsumption": "compiledObject",
                  "measurement": {"mode": "compileOnly", "timingUnit": "microseconds"}
              }]
            }
            
            """.trimIndent()
            )
        val engine: EngineDeclaration =
            engine("string", InputRepresentation.JAVA_STRING)

        val expanded =
            plan.expand(
                List.of<EngineDeclaration?>(engine),
                EnumSet.of<Operation?>(Operation.COMPILE)
            )

        Assertions.assertThat<ExpandedWorkload?>(expanded.workloads)
            .singleElement()
            .satisfies(
                ThrowingConsumer { workload: ExpandedWorkload? ->
                    Assertions.assertThat(workload!!.id).isEqualTo("Synthetic.unicode.letter")
                    Assertions.assertThat<String?>(workload.patterns).containsExactly("\\p{L}+")
                })
    }

    @Test
    fun labeledAxisUsesRuntimeValueInRecipeAndOperationArguments() {
        val plan: DeclarativeBenchmarkPlan =
            parse(
                """
            {
              "schemaVersion": 2,
              "inputs": [{
                "id": "generated.{text}",
                "axes": {
                  "text": [{"id": "greeting", "value": "hello"}]
                },
                "recipe": {"kind": "literal", "text": "{text}"}
              }],
              "workloads": [{
                "id": "Synthetic.replace.{replacement}",
                "operation": "replaceAll",
                "patterns": ["x"],
                "inputs": ["generated.greeting"],
                "axes": {
                  "replacement": [{"id": "bang", "value": "${'$'}0!"}]
                },
                "arguments": {"replacement": "{replacement}"},
                "resultConsumption": "string",
                "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
              }]
            }
            
            """.trimIndent()
            )
        val engine: EngineDeclaration =
            engine(
                "string",
                InputRepresentation.JAVA_STRING,
                Feature.REPLACE
            )

        val expanded =
            plan.expand(
                List.of<EngineDeclaration?>(engine),
                EnumSet.of<Operation?>(Operation.REPLACE_ALL)
            )

        Assertions.assertThat<String?, InputDeclaration?>(plan.inputs())
            .containsKey("generated.greeting")
        Assertions.assertThat<RecipeValue?>(plan.inputs().get("generated.greeting")!!.recipe.arguments.get("text"))
            .isEqualTo(RecipeString("hello"))
        Assertions.assertThat<ExpandedWorkload?>(expanded.workloads)
            .singleElement()
            .satisfies(
                ThrowingConsumer { workload: ExpandedWorkload? ->
                    Assertions.assertThat(workload!!.id).isEqualTo("Synthetic.replace.bang")
                    Assertions.assertThat<RecipeValue?>(workload.arguments.get("replacement"))
                        .isEqualTo(RecipeString("$0!"))
                })
    }

    @Test
    fun omittedInputRepresentationsAcceptsEveryEngineRepresentation() {
        val plan: DeclarativeBenchmarkPlan =
            parse(
                planJson(
                    """
                [{
                  "id": "Synthetic.find",
                  "operation": "find",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "requirements": ["find"],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                
                """.trimIndent()
                )
            )

        val expanded =
            plan.expand(
                List.of<EngineDeclaration?>(
                    engine(
                        "string",
                        InputRepresentation.JAVA_STRING,
                        Feature.FIND
                    ),
                    engine(
                        "utf8",
                        InputRepresentation.PREEXISTING_UTF8,
                        Feature.FIND
                    ),
                    engine(
                        "conversion",
                        InputRepresentation.JAVA_STRING_WITH_TIMED_UTF8_CONVERSION,
                        Feature.FIND
                    )
                ),
                EnumSet.of<Operation?>(Operation.FIND)
            )

        Assertions.assertThat<String?>(expanded.trials.stream().map<String?> { id() })
            .containsExactly(
                "Synthetic.find@string", "Synthetic.find@utf8", "Synthetic.find@conversion"
            )
    }

    @Test
    fun explicitInputRepresentationRestrictionFiltersEnginesAndPreservesReason() {
        val plan: DeclarativeBenchmarkPlan =
            parse(
                planJson(
                    """
                [{
                  "id": "Synthetic.find",
                  "operation": "find",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "requirements": ["find"],
                  "inputRepresentations": ["javaString"],
                  "inputRepresentationReason":
                      "This workload measures an API whose input is a Java String.",
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                
                """.trimIndent()
                )
            )

        val expanded =
            plan.expand(
                List.of<EngineDeclaration?>(
                    engine(
                        "string",
                        InputRepresentation.JAVA_STRING,
                        Feature.FIND
                    ),
                    engine(
                        "utf8",
                        InputRepresentation.PREEXISTING_UTF8,
                        Feature.FIND
                    )
                ),
                EnumSet.of<Operation?>(Operation.FIND)
            )

        Assertions.assertThat<ExpandedWorkload?>(expanded.workloads)
            .singleElement()
            .extracting<String?>(ExpandedWorkload::inputRepresentationReason)
            .isEqualTo("This workload measures an API whose input is a Java String.")
        Assertions.assertThat<Trial?>(expanded.trials)
            .singleElement()
            .extracting<String?>(Function { id() })
            .isEqualTo("Synthetic.find@string")
        Assertions.assertThat<Exclusion?>(expanded.exclusions)
            .singleElement()
            .satisfies(
                ThrowingConsumer { exclusion: Exclusion? ->
                    Assertions.assertThat(exclusion!!.engineId).isEqualTo("utf8")
                    Assertions.assertThat<ExclusionKind?>(exclusion.kind)
                        .isEqualTo(
                            ExclusionKind.UNSUPPORTED_INPUT_REPRESENTATION
                        )
                })
    }

    @Test
    fun explicitInputRepresentationsRequireANonblankReason() {
        val workload =
            """
        [{
          "id": "Synthetic.find",
          "operation": "find",
          "patterns": ["x"],
          "inputs": ["literal.input"],
          "requirements": ["find"],
          "inputRepresentations": ["javaString"],
          "resultConsumption": "boolean",
          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
        }]
        
        """.trimIndent()

        Assertions.assertThatThrownBy(ThrowingCallable { parse(planJson(workload)) })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(
                "requires a nonblank inputRepresentationReason when inputRepresentations is set"
            )
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    planJson(
                        workload.replace(
                            "\"resultConsumption\"",
                            ("\"inputRepresentationReason\": \"  \","
                                    + System.lineSeparator()
                                    + "  \"resultConsumption\"")
                        )
                    )
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(
                "requires a nonblank inputRepresentationReason when inputRepresentations is set"
            )
    }

    @Test
    fun inputRepresentationReasonWithoutARestrictionIsRejected() {
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.find",
                          "operation": "find",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "requirements": ["find"],
                          "inputRepresentationReason": "There is no corresponding restriction.",
                          "resultConsumption": "boolean",
                          "measurement": {
                            "mode": "averageTime",
                            "timingUnit": "nanoseconds"
                          }
                        }]
                        
                        """.trimIndent()
                    )
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(
                "must not declare inputRepresentationReason without inputRepresentations"
            )
    }

    @Test
    fun explicitlyListingEveryInputRepresentationIsRejected() {
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.find",
                          "operation": "find",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "requirements": ["find"],
                          "inputRepresentations": [
                            "javaString",
                            "preexistingUtf8",
                            "javaStringWithTimedUtf8Conversion"
                          ],
                          "inputRepresentationReason":
                              "This fixture verifies that redundant all-value lists are rejected.",
                          "resultConsumption": "boolean",
                          "measurement": {
                            "mode": "averageTime",
                            "timingUnit": "nanoseconds"
                          }
                        }]
                        
                        """.trimIndent()
                    )
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("omit inputRepresentations instead")
    }

    @Test
    fun addingWorkloadAndEngineAxesProducesEveryCompatiblePair() {
        val plan: DeclarativeBenchmarkPlan =
            parse(
                planJson(
                    """
                [{
                  "id": "Synthetic.find",
                  "operation": "find",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "requirements": ["find"],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }, {
                  "id": "Synthetic.matches",
                  "operation": "matches",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "requirements": ["matches"],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                
                """.trimIndent()
                )
            )
        val stringEngine: EngineDeclaration =
            engine(
                "string",
                InputRepresentation.JAVA_STRING,
                Feature.FIND,
                Feature.MATCHES
            )
        val utf8Engine: EngineDeclaration =
            engine(
                "utf8",
                InputRepresentation.PREEXISTING_UTF8,
                Feature.FIND
            )

        val expanded =
            plan.expand(
                List.of<EngineDeclaration?>(stringEngine, utf8Engine),
                EnumSet.of<Operation?>(
                    Operation.FIND,
                    Operation.MATCHES
                )
            )

        Assertions.assertThat<String?>(expanded.trials.stream().map<String?> { id() })
            .containsExactly(
                "Synthetic.find@string", "Synthetic.find@utf8", "Synthetic.matches@string"
            )
        Assertions.assertThat<Exclusion?>(expanded.exclusions)
            .singleElement()
            .satisfies(
                ThrowingConsumer { exclusion: Exclusion? ->
                    Assertions.assertThat(exclusion!!.workloadId).isEqualTo("Synthetic.matches")
                    Assertions.assertThat(exclusion.engineId).isEqualTo("utf8")
                    Assertions.assertThat<ExclusionKind?>(exclusion.kind)
                        .isEqualTo(ExclusionKind.UNSUPPORTED_FEATURE)
                })
    }

    @Test
    fun operationsFlagsAndLifecycleDeriveEngineNeutralRequirements() {
        val plan: DeclarativeBenchmarkPlan =
            parse(
                planJson(
                    """
                [{
                  "id": "Synthetic.region",
                  "operation": "matcherRegionFind",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "flags": ["caseInsensitive"],
                  "resultConsumption": "boolean",
                  "lifecycle": {
                    "matcher": "retained",
                    "steps": [{"kind": "region", "start": 0, "end": 1}]
                  },
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                
                """.trimIndent()
                )
            )

        Assertions.assertThat<WorkloadDeclaration?>(plan.workloads())
            .singleElement()
            .satisfies(
                ThrowingConsumer { workload: WorkloadDeclaration? ->
                    Assertions.assertThat<Feature?>(workload!!.requirements())
                        .containsExactlyInAnyOrder(
                            Feature.FIND,
                            Feature.MATCHER_STATE,
                            Feature.REGIONS
                        )
                })
    }

    @Test
    fun schemaRepresentsOrdinaryStatefulPatternSetMemoryAndColdStartWorkloads() {
        val plan: DeclarativeBenchmarkPlan =
            parse(
                planJson(
                    """
                [{
                  "id": "Synthetic.ordinary",
                  "operation": "replaceAll",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "arguments": {"replacement": "y"},
                  "resultConsumption": "string",
                  "expected": {"type": "string", "value": "y"},
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }, {
                  "id": "Synthetic.patternSet",
                  "operation": "patternSetFind",
                  "patterns": ["x", "y"],
                  "inputs": ["literal.input"],
                  "arguments": {"anchor": "unanchored"},
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }, {
                  "id": "Synthetic.memory",
                  "operation": "find",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "retainedMemory", "timingUnit": "bytes"}
                }, {
                  "id": "Synthetic.compile",
                  "operation": "compile",
                  "patterns": ["x"],
                  "resultConsumption": "compiledObject",
                  "measurement": {"mode": "compileOnly", "timingUnit": "nanoseconds"}
                }, {
                  "id": "Synthetic.cold",
                  "operation": "compile",
                  "patterns": ["x"],
                  "resultConsumption": "compiledObject",
                  "measurement": {
                    "mode": "singleShotColdStart",
                    "timingUnit": "milliseconds",
                    "constraints": ["freshProcessPerInvocation"]
                  }
                }]
                
                """.trimIndent()
                )
            )

        Assertions.assertThat<WorkloadDeclaration?>(plan.workloads())
            .extracting<String?, RuntimeException?>(WorkloadDeclaration::idTemplate)
            .containsExactly(
                "Synthetic.ordinary",
                "Synthetic.patternSet",
                "Synthetic.memory",
                "Synthetic.compile",
                "Synthetic.cold"
            )
    }

    @Test
    fun exclusionsDistinguishMissingAdapterFromSupportedTrials() {
        val plan: DeclarativeBenchmarkPlan =
            parse(
                planJson(
                    """
                [{
                  "id": "Synthetic.find",
                  "operation": "find",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "requirements": ["find"],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                
                """.trimIndent()
                )
            )

        val supported: EngineDeclaration =
            engine(
                "supported",
                InputRepresentation.JAVA_STRING,
                Feature.FIND
            )
        val missingAdapter =
            EngineDeclaration(
                "missing-adapter",
                InputRepresentation.JAVA_STRING,
                EnumSet.of<Feature?>(Feature.FIND),
                EnumSet.allOf<Flag?>(Flag::class.java),
                false
            )
        val expanded =
            plan.expand(
                List.of<EngineDeclaration?>(supported, missingAdapter),
                EnumSet.of<Operation?>(Operation.FIND)
            )

        Assertions.assertThat<String?>(expanded.trials.stream().map<String?> { id() })
            .containsExactly("Synthetic.find@supported")
        Assertions.assertThat<Exclusion?>(expanded.exclusions)
            .extracting<ExclusionKind?, RuntimeException?>(Exclusion::kind)
            .containsExactly(ExclusionKind.MISSING_ENGINE_ADAPTER)

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                plan.expand(
                    List.of<EngineDeclaration?>(supported),
                    EnumSet.of<Operation?>(Operation.MATCHES)
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("produces no supported trials")
            .hasMessageContaining("MISSING_OPERATION_IMPLEMENTATION")
    }

    @Test
    fun trialExclusionsSelectExactExpandedEnginePairs() {
        val plan: DeclarativeBenchmarkPlan =
            parse(
                planJson(
                    """
                [{
                  "id": "RealWorldRegexBenchmark.runBenchmark.recitation.{matchLabel}.{size}",
                  "operation": "replaceAll",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "arguments": {"replacement": ""},
                  "axes": {
                    "matchLabel": ["match", "noMatch"],
                    "size": [1000, 10000, 100000]
                  },
                  "trialExclusions": [{
                    "engineIds": ["jdk-string"],
                    "when": {"size": [10000, 100000]},
                    "reason": "OpenJDK 26.0.2 throws StackOverflowError for the nested quantifiers"
                  }],
                  "resultConsumption": "string",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                
                """.trimIndent()
                )
            )

        val expanded =
            plan.expand(
                List.of<EngineDeclaration?>(
                    engine(
                        "safere-string",
                        InputRepresentation.JAVA_STRING,
                        Feature.REPLACE
                    ),
                    engine(
                        "jdk-string",
                        InputRepresentation.JAVA_STRING,
                        Feature.REPLACE
                    )
                ),
                EnumSet.of<Operation?>(Operation.REPLACE_ALL)
            )

        Assertions.assertThat<Exclusion?>(expanded.exclusions)
            .extracting(
                Function { exclusion: Exclusion? -> exclusion!!.workloadId + "@" + exclusion.engineId },
                Exclusion::kind,
                Exclusion::reason
            )
            .containsExactly(
                Tuple.tuple(
                    "RealWorldRegexBenchmark.runBenchmark.recitation.match.10000@jdk-string",
                    ExclusionKind.EXPLICIT_TRIAL_EXCLUSION,
                    "OpenJDK 26.0.2 throws StackOverflowError for the nested quantifiers"
                ),
                Tuple.tuple(
                    "RealWorldRegexBenchmark.runBenchmark.recitation.match.100000@jdk-string",
                    ExclusionKind.EXPLICIT_TRIAL_EXCLUSION,
                    "OpenJDK 26.0.2 throws StackOverflowError for the nested quantifiers"
                ),
                Tuple.tuple(
                    "RealWorldRegexBenchmark.runBenchmark.recitation.noMatch.10000@jdk-string",
                    ExclusionKind.EXPLICIT_TRIAL_EXCLUSION,
                    "OpenJDK 26.0.2 throws StackOverflowError for the nested quantifiers"
                ),
                Tuple.tuple(
                    "RealWorldRegexBenchmark.runBenchmark.recitation.noMatch.100000@jdk-string",
                    ExclusionKind.EXPLICIT_TRIAL_EXCLUSION,
                    "OpenJDK 26.0.2 throws StackOverflowError for the nested quantifiers"
                )
            )
        Assertions.assertThat<String?>(expanded.trials.stream().map<String?> { id() })
            .hasSize(8)
            .contains(
                "RealWorldRegexBenchmark.runBenchmark.recitation.match.1000@jdk-string",
                "RealWorldRegexBenchmark.runBenchmark.recitation.noMatch.1000@jdk-string"
            )
            .filteredOn(Predicate { id: String? -> id!!.endsWith("@safere-string") })
            .hasSize(6)
    }

    @Test
    fun trialExclusionsSupportConjunctiveLabeledSelectorsAndWorkloadWideRules() {
        val plan: DeclarativeBenchmarkPlan =
            parse(
                planJson(
                    """
                [{
                  "id": "Synthetic.find.{case}.{size}",
                  "operation": "find",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "axes": {
                    "case": [{"id": "hit", "value": true}, {"id": "miss", "value": false}],
                    "size": [8, 16]
                  },
                  "trialExclusions": [{
                    "engineIds": ["first", "second"],
                    "when": {"case": [{"id": "hit", "value": true}], "size": [16]},
                    "reason": "conjunctive selector"
                  }, {
                    "engineIds": ["third"],
                    "when": {},
                    "reason": "all workload expansions"
                  }],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                
                """.trimIndent()
                )
            )

        val expanded =
            plan.expand(
                List.of<EngineDeclaration?>(
                    engine(
                        "first",
                        InputRepresentation.JAVA_STRING,
                        Feature.FIND
                    ),
                    engine(
                        "second",
                        InputRepresentation.JAVA_STRING,
                        Feature.FIND
                    ),
                    engine(
                        "third",
                        InputRepresentation.JAVA_STRING,
                        Feature.FIND
                    ),
                    engine(
                        "unexcluded",
                        InputRepresentation.JAVA_STRING,
                        Feature.FIND
                    )
                ),
                EnumSet.of<Operation?>(Operation.FIND)
            )

        Assertions.assertThat<Exclusion?>(expanded.exclusions)
            .extracting<String?, RuntimeException?>(ThrowingExtractor { exclusion: Exclusion? -> exclusion!!.workloadId + "@" + exclusion.engineId })
            .containsExactly(
                "Synthetic.find.hit.8@third",
                "Synthetic.find.hit.16@first",
                "Synthetic.find.hit.16@second",
                "Synthetic.find.hit.16@third",
                "Synthetic.find.miss.8@third",
                "Synthetic.find.miss.16@third"
            )
    }

    @Test
    fun trialExclusionsRejectInvalidSelectorsAndOverlaps() {
        assertInvalidTrialExclusion("{}", "requires engineIds")
        assertInvalidTrialExclusion(
            "{\"engineIds\": [], \"reason\": \"reason\"}", "requires at least one engine ID"
        )
        assertInvalidTrialExclusion("{\"engineIds\": [\"jdk-string\"]}", "requires reason")
        assertInvalidTrialExclusion(
            "{\"engineIds\": [\"jdk-string\", \"jdk-string\"], \"reason\": \"reason\"}",
            "duplicate engine ID"
        )
        assertInvalidTrialExclusion(
            "{\"engineIds\": [\"jdk-string\"], \"reason\": \"  \"}", "reason must not be blank"
        )
        assertInvalidTrialExclusion(
            "{\"engineIds\": [\"jdk-string\"], \"when\": {\"unknown\": [8]},"
                    + " \"reason\": \"reason\"}",
            "references unknown axis"
        )
        assertInvalidTrialExclusion(
            "{\"engineIds\": [\"jdk-string\"], \"when\": {\"size\": []}," + " \"reason\": \"reason\"}",
            "selector must not be empty"
        )
        assertInvalidTrialExclusion(
            "{\"engineIds\": [\"jdk-string\"], \"when\": {\"size\": [32]},"
                    + " \"reason\": \"reason\"}",
            "selects undeclared axis value"
        )
        assertInvalidTrialExclusion(
            "{\"engineIds\": [\"jdk-string\"], \"when\": {\"size\": [8, 8]},"
                    + " \"reason\": \"reason\"}",
            "duplicate axis value"
        )
        assertInvalidTrialExclusion(
            "{\"engineIds\": [\"jdk-string\"], \"reason\": \"reason\"," + " \"unknown\": true}",
            "has unknown field: unknown"
        )
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.find",
                          "operation": "find",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "trialExclusions": {},
                          "resultConsumption": "boolean",
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        
                        """.trimIndent()
                    )
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("trialExclusions must be an array")
        assertInvalidTrialExclusion(
            "{\"engineIds\": [\"jdk-string\"], \"when\": [], \"reason\": \"reason\"}",
            "when must be an object"
        )

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.find",
                          "operation": "find",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "trialExclusions": [{
                            "engineIds": ["jdk-string"],
                            "reason": "reason"
                          }],
                          "disabledReason": "disabled",
                          "resultConsumption": "boolean",
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        
                        """.trimIndent()
                    )
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must not declare both disabledReason and trialExclusions")

        val unknownEngine: DeclarativeBenchmarkPlan =
            planWithTrialExclusions("{\"engineIds\": [\"jdk-string\"], \"reason\": \"reason\"}")
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                unknownEngine.expand(
                    List.of<EngineDeclaration?>(
                        engine(
                            "safere-string",
                            InputRepresentation.JAVA_STRING
                        )
                    ),
                    EnumSet.of<Operation?>(Operation.FIND)
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("references unknown engine ID: jdk-string")

        val overlap: DeclarativeBenchmarkPlan =
            planWithTrialExclusions(
                ("{\"engineIds\": [\"jdk-string\"], \"when\": {\"size\": [8]},"
                        + " \"reason\": \"first\"},"
                        + "{\"engineIds\": [\"jdk-string\"], \"reason\": \"second\"}")
            )
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                overlap.expand(
                    List.of<EngineDeclaration?>(
                        engine(
                            "jdk-string",
                            InputRepresentation.JAVA_STRING
                        )
                    ),
                    EnumSet.of<Operation?>(Operation.FIND)
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(
                "Synthetic.find.8 has overlapping trial exclusions for engine jdk-string"
            )
    }

    @Test
    fun unsupportedFlagsHaveAMachineReadableExclusion() {
        val plan: DeclarativeBenchmarkPlan =
            parse(
                planJson(
                    """
                [{
                  "id": "Synthetic.comments",
                  "operation": "find",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "flags": ["comments"],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                
                """.trimIndent()
                )
            )
        val engine =
            EngineDeclaration(
                "without-comments",
                InputRepresentation.JAVA_STRING,
                EnumSet.of<Feature?>(Feature.FIND),
                EnumSet.noneOf<Flag?>(Flag::class.java),
                true
            )

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                plan.expand(
                    List.of<EngineDeclaration?>(engine),
                    EnumSet.of<Operation?>(Operation.FIND)
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("UNSUPPORTED_FLAG")
    }

    @Test
    fun disabledReasonExplainsAnEmptyTrialMatrix() {
        val plan: DeclarativeBenchmarkPlan =
            parse(
                planJson(
                    """
                [{
                  "id": "Synthetic.disabled",
                  "operation": "find",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "requirements": ["utf8Input"],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"},
                  "disabledReason": "awaiting the dedicated UTF-8 runner"
                }]
                
                """.trimIndent()
                )
            )

        val expanded =
            plan.expand(
                List.of<EngineDeclaration?>(
                    engine(
                        "string",
                        InputRepresentation.JAVA_STRING,
                        Feature.FIND
                    )
                ),
                EnumSet.of<Operation?>(Operation.FIND)
            )

        Assertions.assertThat<Trial?>(expanded.trials).isEmpty()
        Assertions.assertThat<Exclusion?>(expanded.exclusions)
            .singleElement()
            .satisfies(
                ThrowingConsumer { exclusion: Exclusion? ->
                    Assertions.assertThat<ExclusionKind?>(exclusion!!.kind)
                        .isEqualTo(ExclusionKind.WORKLOAD_DISABLED)
                })
    }

    @Test
    fun unexplainedEmptyTrialMatrixIsRejected() {
        val plan: DeclarativeBenchmarkPlan =
            parse(
                planJson(
                    """
                [{
                  "id": "Synthetic.utf8Only",
                  "operation": "find",
                  "patterns": ["x"],
                  "inputs": ["literal.input"],
                  "requirements": ["utf8Input"],
                  "resultConsumption": "boolean",
                  "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                }]
                
                """.trimIndent()
                )
            )

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                plan.expand(
                    List.of<EngineDeclaration?>(
                        engine(
                            "string",
                            InputRepresentation.JAVA_STRING,
                            Feature.FIND
                        )
                    ),
                    EnumSet.of<Operation?>(Operation.FIND)
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("produces no supported trials")
            .hasMessageContaining("disabledReason")
    }

    @Test
    fun strictLoadingRejectsUnknownFieldsAndEnumValues() {
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    """
                    {
                      "schemaVersion": 1,
                      "inputs": [],
                      "workloads": []
                    }
                    
                    """.trimIndent()
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Unsupported benchmark plan schema version: 1")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    """
                    {
                      "schemaVersion": 3,
                      "inputs": [],
                      "workloads": []
                    }
                    
                    """.trimIndent()
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Unsupported benchmark plan schema version: 3")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    """
                    {
                      "schemaVersion": 2,
                      "inputs": [],
                      "workloads": [],
                      "family": "Regex"
                    }
                    
                    """.trimIndent()
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("plan has unknown field: family")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.unknown",
                          "operation": "familySpecificOperation",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "resultConsumption": "boolean",
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        
                        """.trimIndent()
                    )
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Unknown benchmark operation: familySpecificOperation")
    }

    @Test
    fun loadingRejectsDuplicateIdsInvalidReferencesAndUnstableAxes() {
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    """
                    {
                      "schemaVersion": 2,
                      "inputs": [
                        {"id": "same", "recipe": {"kind": "literal", "text": "a"}},
                        {"id": "same", "recipe": {"kind": "literal", "text": "b"}}
                      ],
                      "workloads": [{
                        "id": "Synthetic.find",
                        "operation": "find",
                        "patterns": ["x"],
                        "inputs": ["same"],
                        "resultConsumption": "boolean",
                        "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                      }]
                    }
                    
                    """.trimIndent()
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Duplicate benchmark input ID: same")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.find",
                          "operation": "find",
                          "patterns": ["x"],
                          "inputs": ["missing.input"],
                          "resultConsumption": "boolean",
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        
                        """.trimIndent()
                    )
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Synthetic.find references unknown expanded benchmark input: missing.input")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.find",
                          "operation": "find",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "axes": {"size": [8, 16]},
                          "resultConsumption": "boolean",
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        
                        """.trimIndent()
                    )
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ID omits expansion axes")
            .hasMessageContaining("size")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    """
                    {
                      "schemaVersion": 2,
                      "inputs": [{
                        "id": "orphaned.input",
                        "recipe": {"kind": "literal", "text": "x"}
                      }],
                      "workloads": [{
                        "id": "Synthetic.compile",
                        "operation": "compile",
                        "patterns": ["x"],
                        "resultConsumption": "compiledObject",
                        "measurement": {"mode": "compileOnly", "timingUnit": "nanoseconds"}
                      }]
                    }
                    
                    """.trimIndent()
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(
                "Benchmark input is not referenced by a workload and is not shared: orphaned.input"
            )
    }

    @Test
    fun loadingRejectsMalformedExpectedLifecycleAndMeasurementDeclarations() {
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.expected",
                          "operation": "find",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "resultConsumption": "boolean",
                          "expected": {"type": "integer", "value": 1},
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        
                        """.trimIndent()
                    )
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("requires expected type boolean")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.region",
                          "operation": "matcherRegionFind",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "resultConsumption": "boolean",
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        
                        """.trimIndent()
                    )
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("requires lifecycle")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.cold",
                          "operation": "compile",
                          "patterns": ["x"],
                          "resultConsumption": "compiledObject",
                          "measurement": {
                            "mode": "singleShotColdStart",
                            "timingUnit": "milliseconds"
                          }
                        }]
                        
                        """.trimIndent()
                    )
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("freshProcessPerInvocation")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.badConsumption",
                          "operation": "find",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "resultConsumption": "integer",
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        
                        """.trimIndent()
                    )
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("find cannot use result consumption integer")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.replace",
                          "operation": "replaceAll",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "resultConsumption": "string",
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        
                        """.trimIndent()
                    )
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("replaceAll requires arguments [replacement]")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    planJson(
                        """
                        [{
                          "id": "Synthetic.find",
                          "operation": "find",
                          "patterns": ["x"],
                          "inputs": ["literal.input"],
                          "arguments": {"family": "Regex"},
                          "resultConsumption": "boolean",
                          "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                        }]
                        
                        """.trimIndent()
                    )
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Synthetic.find operation find has unknown argument: family")
    }

    @Test
    fun boundedRecipeVocabularyRejectsUnknownKindsAndArguments() {
        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    """
                    {
                      "schemaVersion": 2,
                      "inputs": [{
                        "id": "bad.input",
                        "recipe": {"kind": "executeJava", "class": "FamilyGenerator"}
                      }],
                      "workloads": [{
                        "id": "Synthetic.find",
                        "operation": "find",
                        "patterns": ["x"],
                        "inputs": ["bad.input"],
                        "resultConsumption": "boolean",
                        "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                      }]
                    }
                    
                    """.trimIndent()
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Unknown input recipe kind: executeJava")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    """
                    {
                      "schemaVersion": 2,
                      "inputs": [{
                        "id": "bad.input",
                        "recipe": {
                          "kind": "literal",
                          "text": "x",
                          "family": "Regex"
                        }
                      }],
                      "workloads": [{
                        "id": "Synthetic.find",
                        "operation": "find",
                        "patterns": ["x"],
                        "inputs": ["bad.input"],
                        "resultConsumption": "boolean",
                        "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                      }]
                    }
                    
                    """.trimIndent()
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("bad.input recipe has unknown field: family")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                parse(
                    """
                    {
                      "schemaVersion": 2,
                      "inputs": [{
                        "id": "bad.input",
                        "recipe": {
                          "kind": "repeatToLength",
                          "unit": "",
                          "length": 1
                        }
                      }],
                      "workloads": [{
                        "id": "Synthetic.find",
                        "operation": "find",
                        "patterns": ["x"],
                        "inputs": ["bad.input"],
                        "resultConsumption": "boolean",
                        "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
                      }]
                    }
                    
                    """.trimIndent()
                )
            })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Recipe field unit must not be empty")
    }

    companion object {
        private fun parse(json: String): DeclarativeBenchmarkPlan {
            return DeclarativeBenchmarkPlan.parse(JsonParser.parseString(json).asJsonObject)
        }

        private fun assertInvalidTrialExclusion(exclusion: String?, message: String?) {
            Assertions.assertThatThrownBy(ThrowingCallable { planWithTrialExclusions(exclusion) })
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(message)
        }

        private fun planWithTrialExclusions(exclusions: String?): DeclarativeBenchmarkPlan {
            return parse(
                planJson(
                    """
            [{
              "id": "Synthetic.find.{size}",
              "operation": "find",
              "patterns": ["x"],
              "inputs": ["literal.input"],
              "axes": {"size": [8, 16]},
              "trialExclusions": [%s],
              "resultConsumption": "boolean",
              "measurement": {"mode": "averageTime", "timingUnit": "nanoseconds"}
            }]
            
            """
                        .trimIndent()
                        .formatted(exclusions)
                )
            )
        }

        private fun planJson(workloads: String?): String {
            return """
    {
      "schemaVersion": 2,
      "inputs": [{
        "id": "literal.input",
        "recipe": {"kind": "literal", "text": "x"}
      }],
      "workloads": %s
    }
    
    """
                .trimIndent()
                .formatted(workloads)
        }

        private fun engine(
            id: String?,
            representation: InputRepresentation?,
            vararg features: Feature?
        ): EngineDeclaration {
            val featureSet =
                EnumSet.noneOf<Feature?>(Feature::class.java)
            featureSet.addAll(List.of<Feature?>(*features))
            return EngineDeclaration(
                id,
                representation,
                featureSet,
                EnumSet.allOf<Flag?>(Flag::class.java),
                true
            )
        }
    }
}
