// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/** Checks that the matcher-free [Pattern.asPredicate] path honors engine path options.  */
@DisabledForCrosscheck("uses package-private engine path options")
internal class PatternPredicateEngineOptionsTest {
    @ParameterizedTest
    @MethodSource("engineOptions")
    fun asPredicateHonorsEnginePathOptions(name: String?, options: EnginePathOptions.Builder) {
        val failures: MutableList<String> = ArrayList<String>()
        PatternPredicateTest.patterns()
            .forEach { arguments: Arguments? ->
                val regex = arguments!!.get()[0] as String?
                val flags = arguments.get()[1] as Int
                val pattern = Pattern.compile(regex, flags, options.build())
                for (input in PatternPredicateTest.INPUTS) {
                    if (pattern.asPredicate().test(input) != pattern.matcher(input).find()) {
                        failures.add(
                            ("/"
                                    + regex
                                    + "/ flags="
                                    + flags
                                    + " on "
                                    + PatternPredicateTest.escape(input))
                        )
                    }
                }
            }
        Assertions.assertThat<String?>(failures).`as`(name).isEmpty()
    }

    companion object {
        @JvmStatic
        private fun engineOptions(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of("literal fast paths off", EnginePathOptions.builder().literalFastPaths(false)),
                Arguments.of(
                    "start acceleration off", EnginePathOptions.builder().startAcceleration(false)
                ),
                Arguments.of(
                    "keyword fast path off", EnginePathOptions.builder().keywordAlternationFastPath(false)
                ),
                Arguments.of("DFA off", EnginePathOptions.builder().dfa(false))
            )
        }
    }
}
