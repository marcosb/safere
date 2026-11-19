// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

/** Ownership and engine preparation invariants for shared compiler preparation.  */
@DisabledForCrosscheck("uses package-private compiler and engine preparation internals")
internal class CompilerPreparationTest {
    @ParameterizedTest
    @ValueSource(
        strings = ["(a){0}(b)", "((a){1,2})+b", "((a)+){2}", "(a?)*", "(?i:é𐐀)+", "\\b\\p{L}+\\B", "\\X+"
        ]
    )
    fun preparationCanBeReusedWithoutMutatingSourceOrPrograms(regex: String?) {
        val source = Parser.parse(regex, FLAGS)!!
        val sourceForm = source.toString()
        val prepared = Compiler.prepare(source)
        Assertions.assertThat<Compiler.Prepared?>(prepared).isNotNull()
        val first = Compiler.compile(prepared, false)!!
        val instructions = first.dump()

        // Compile the other variants before reusing the same tree for the original variant.
        Assertions.assertThat<Prog?>(Compiler.compile(prepared, true)).isNotNull()
        Assertions.assertThat<Prog?>(Compiler.compileForDfa(prepared, false)).isNotNull()
        Assertions.assertThat<Prog?>(Compiler.compileForDfa(prepared, true)).isNotNull()
        val flattened = Prog(first)
        flattened.flatten()
        flattened.freeze()
        val second = Compiler.compile(prepared, false)!!

        Assertions.assertThat<Prog?>(second).isNotSameAs(first)
        Assertions.assertThat<Inst?>(second.inst(second.start())).isNotSameAs(first.inst(first.start()))
        Assertions.assertThat(first.dump()).isEqualTo(instructions)
        Assertions.assertThat(second.dump()).isEqualTo(instructions)
        Assertions.assertThat(source.toString()).isEqualTo(sourceForm)
    }

    @ParameterizedTest
    @MethodSource("anchors")
    fun variantsPreserveSourceCaptureCountAndDirectionalAnchors(
        regex: String?, start: Boolean, end: Boolean, dollar: Boolean
    ) {
        for (unixLines in booleanArrayOf(false, true)) {
            val prepared =
                Compiler.prepare(Parser.parse(regex, FLAGS or (if (unixLines) ParseFlags.UNIX_LINES else 0))!!)
            for (reversed in booleanArrayOf(true, false)) {
                for (forDfa in booleanArrayOf(true, false)) {
                    val prog =
                        (if (forDfa)
                            Compiler.compileForDfa(prepared, reversed)
                        else
                            Compiler.compile(prepared, reversed))!!
                    Assertions.assertThat(prog.numCaptures()).isEqualTo(3)
                    Assertions.assertThat(prog.reversed()).isEqualTo(reversed)
                    Assertions.assertThat(prog.anchorStart()).isEqualTo(if (reversed) end else start)
                    Assertions.assertThat(prog.anchorEnd()).isEqualTo(if (reversed) start else end)
                    Assertions.assertThat(prog.dollarAnchorEnd()).isEqualTo(!reversed && dollar)
                    Assertions.assertThat(prog.dollarAnchorUnixLines()).isEqualTo(!reversed && dollar && unixLines)
                }
            }
        }
    }

    @Test
    fun captureAndDfaVariantsKeepTheirOwnLoopRegisters() {
        val prepared = Compiler.prepare(Parser.parse("(a?)*b", FLAGS)!!)
        for (reversed in booleanArrayOf(false, true)) {
            val captures = Compiler.compile(prepared, reversed)!!
            val dfa = Compiler.compileForDfa(prepared, reversed)!!
            Assertions.assertThat(captures.numLoopRegs()).isPositive()
            Assertions.assertThat(dfa.numLoopRegs()).isZero()
            Assertions.assertThat(captures.numCaptures()).isEqualTo(2)
            Assertions.assertThat(dfa.numCaptures()).isEqualTo(2)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["([0-9]{4})-[0-9]{2}", "(foo|bar|baz|quux)", "((a)+){2}b"])
    @Throws(
        ReflectiveOperationException::class
    )
    fun findAndCaptureProgramsAndSetupsAreReadyWhenCompileReturns(regex: String?) {
        val pattern = Pattern.compile(regex)
        for (name in arrayOf<String>("onePassAnalysis", "forwardDfaSetup")) {
            Assertions.assertThat<Any?>(field(pattern, name)).`as`("%s for %s", name, regex).isNotNull()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["(a?)*b", "(a?){0,2}b", "(a?){1,3}b"])
    @Throws(
        ReflectiveOperationException::class
    )
    fun progressChecksPrepareDfaWithoutUnsupportedOnePassAnalysis(regex: String?) {
        val pattern = Pattern.compile(regex)
        Assertions.assertThat(pattern.prog().numLoopRegs()).isPositive()
        Assertions.assertThat<Any?>(field(pattern, "forwardDfaSetup")).isNotNull()
        // OnePass does not execute progress checks; only the supported DFA rejection setup is eager.
        Assertions.assertThat<Any?>(field(pattern, "onePassAnalysis")).isNull()
    }

    @ParameterizedTest
    @CsvSource(
        "'\\w+z', 'hello abcz', 6, 10",
        "'[a-z]+ing\\b', 'the running dog', 4, 11",
        "'\\d+\\.\\d+', 'pi is 3.14 ok', 6, 10",
        "'((a)+){2}b', 'xxaab', 2, 5"
    )
    @Throws(ReflectiveOperationException::class)
    fun reverseDfaProgramIsBuiltOnFirstUse(regex: String?, text: String?, start: Int, end: Int) {
        val pattern = Pattern.compile(regex)
        Assertions.assertThat(pattern.canUseReverseDfa()).isTrue()
        assertReverseDfaPrepared(pattern, regex, false)

        val miss = pattern.matcher("")
        Assertions.assertThat(miss.find()).isFalse()
        Assertions.assertThat(pattern.matcher(text).matches()).isFalse()
        assertReverseDfaPrepared(pattern, regex, false)

        val matcher = pattern.matcher(text)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(start)
        Assertions.assertThat(matcher.end()).isEqualTo(end)
        assertReverseDfaPrepared(pattern, regex, true)
    }

    /**
     * The `CompileBenchmark.compileAndFind{WordZ,Ing,Decimal}{Match,NoMatch}` workloads in
     * `benchmark-data.json` measure both sides of building the reverse DFA program on first
     * use: the matching inputs need it to recover the match start, and the others never reach it.
     */
    @ParameterizedTest
    @CsvSource(
        "'[a-z]+z', 'hello abcz', true",
        "'[a-z]+z', 'hello world', false",
        "'[a-z]+ing\\b', 'the running dog', true",
        "'[a-z]+ing\\b', 'the dog sat', false",
        "'[0-9]+\\.[0-9]+', 'pi is 3.14 ok', true",
        "'[0-9]+\\.[0-9]+', 'pi is three', false"
    )
    @Throws(ReflectiveOperationException::class)
    fun compileAndFindBenchmarkInputsCoverBothReverseDfaOutcomes(
        regex: String?, text: String?, match: Boolean
    ) {
        val pattern = Pattern.compile(regex)
        Assertions.assertThat(pattern.canUseReverseDfa()).isTrue()
        Assertions.assertThat(pattern.matcher(text).find()).isEqualTo(match)
        assertReverseDfaPrepared(pattern, regex, match)
    }

    @Test
    fun reverseDfaSetupBelongsToTheDfaProgram() {
        val pattern = Pattern.compile("(a?)*b")
        val dfa = pattern.flatReverseDfaProg()!!
        val setup = pattern.reverseDfaSetup()!!
        Assertions.assertThat(dfa.numLoopRegs()).isZero()
        Assertions.assertThat(pattern.flatReverseProg()!!.numLoopRegs()).isPositive()
        Assertions.assertThat<Dfa.Setup?>(pattern.reverseDfaSetup()).isSameAs(setup)
        val expected = Dfa.buildSetup(dfa)
        Assertions.assertThat(setup.boundaries).containsExactly(*expected.boundaries)
        Assertions.assertThat(setup.asciiClassMap).containsExactly(*expected.asciiClassMap)
        Assertions.assertThat(setup.numClasses).isEqualTo(expected.numClasses)
        Assertions.assertThat(setup.splitUnicodeWordClasses).isEqualTo(expected.splitUnicodeWordClasses)
    }

    companion object {
        private val FLAGS = ParseFlags.LIKE_PERL

        @JvmStatic
        private fun anchors(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of("(a){0}(b)", false, false, false),
                Arguments.of("\\A(a){0}(b)", true, false, false),
                Arguments.of("(a){0}(b)\\z", false, true, false),
                Arguments.of("\\A(a){0}(b)$", true, true, true)
            )
        }

        @Throws(ReflectiveOperationException::class)
        private fun assertReverseDfaPrepared(pattern: Pattern?, regex: String?, prepared: Boolean) {
            for (name in arrayOf<String>("reverseProg", "flatReverseDfaProg", "reverseDfaSetup")) {
                Assertions.assertThat(field(pattern, name) != null).`as`("%s for %s", name, regex).isEqualTo(prepared)
            }
        }

        @Throws(ReflectiveOperationException::class)
        private fun field(pattern: Pattern?, name: String): Any? {
            // Inspect fields directly: accessors would build deferred artifacts.
            val field = Pattern::class.java.getDeclaredField(name)
            field.setAccessible(true)
            return field.get(pattern)
        }
    }
}
