// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import java.lang.Boolean
import java.lang.reflect.AnnotatedElement
import java.util.function.Function
import java.util.function.Supplier
import kotlin.String

/**
 * Controls inclusion and execution of generated crosscheck tests.
 * 
 * 
 * On a top-level test class, the annotation excludes the entire source file from generation. On
 * a method or nested class, it disables only that element in generated crosscheck runs. Original
 * SafeRE tests remain enabled.
 * 
 * 
 * The annotation is active only when the generated crosscheck test profile sets the `org.safere.crosscheck.generatedTests` system property. Use an issue reference in the reason for
 * fixable SafeRE/JDK divergences, and a plain reason for tests that are intentionally not relevant
 * to crosscheck.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.CLASS
)
@ExtendWith(DisabledForCrosscheckCondition::class)
internal annotation class DisabledForCrosscheck(
    /** Explains why this test is disabled in generated crosscheck coverage.  */
    val value: String
)

/** JUnit condition backing [DisabledForCrosscheck].  */
internal class DisabledForCrosscheckCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        if (!Boolean.getBoolean(GENERATED_TESTS_PROPERTY)) {
            return ConditionEvaluationResult.enabled("not a generated crosscheck test run")
        }

        return context
            .getElement()
            .map<DisabledForCrosscheck?>(Function { element: AnnotatedElement? ->
                element!!.getAnnotation<DisabledForCrosscheck?>(
                    DisabledForCrosscheck::class.java
                )
            })
            .map<ConditionEvaluationResult>(Function { annotation: DisabledForCrosscheck? ->
                ConditionEvaluationResult.disabled(
                    annotation!!.value
                )
            })
            .orElseGet(Supplier { ConditionEvaluationResult.enabled("not disabled for crosscheck") })
    }

    companion object {
        const val GENERATED_TESTS_PROPERTY: String = "org.safere.crosscheck.generatedTests"
    }
}
