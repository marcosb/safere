// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import java.lang.reflect.*
import java.util.*

/** Checks that generation exclusions do not disable the original SafeRE tests.  */
@DisabledForCrosscheck("tests the original execution condition and package-private internals")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
internal class DisabledForCrosscheckConditionTest {
    @Test
    @Throws(Exception::class)
    fun originalTestsStayEnabledAndGeneratedMembersAreDisabled() {
        val property = "org.safere.crosscheck.generatedTests"
        val constructor: Constructor<*> =
            Class.forName("org.safere.DisabledForCrosscheckCondition").getDeclaredConstructor()
        constructor.setAccessible(true)
        val condition = constructor.newInstance() as ExecutionCondition
        val previous = System.getProperty(property)
        try {
            for (element in arrayOf<AnnotatedElement>(
                DisabledForCrosscheckConditionTest::class.java,
                Nested::class.java,
                Nested::class.java.getDeclaredMethod("disabledMethod")
            )) {
                val context =
                    Proxy.newProxyInstance(
                        ExtensionContext::class.java.getClassLoader(),
                        arrayOf<Class<*>>(ExtensionContext::class.java),
                        InvocationHandler { proxy: Any?, method: Method?, args: Array<Any?>? ->
                            if (method!!.getName() == "getElement") {
                                Optional.of<AnnotatedElement?>(element)
                            } else {
                                throw UnsupportedOperationException(method.getName())
                            }
                        }) as ExtensionContext
                System.clearProperty(property)
                Assertions.assertThat(condition.evaluateExecutionCondition(context).isDisabled()).isFalse()
                System.setProperty(property, "true")
                Assertions.assertThat(condition.evaluateExecutionCondition(context).isDisabled()).isTrue()
            }
        } finally {
            if (previous == null) {
                System.clearProperty(property)
            } else {
                System.setProperty(property, previous)
            }
        }
    }

    @DisabledForCrosscheck("nested")
    internal class Nested {
        @DisabledForCrosscheck("method")
        fun disabledMethod() {
        }
    }
}
