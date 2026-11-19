// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Tests for [Compiler].  */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal object CompilerTest {
    private val DEFAULT_FLAGS =
        ParseFlags.PERL_X or ParseFlags.PERL_CLASSES or ParseFlags.PERL_B or ParseFlags.UNICODE_GROUPS

    private fun compile(pattern: String?, flags: Int = DEFAULT_FLAGS): Prog? {
        val re = Parser.parse(pattern, flags)
        return Compiler.compile(re)
    }

    private fun fullMatch(prog: Prog, text: String?): Boolean {
        val result =
            Nfa.search(prog, text, Nfa.Anchor.UNANCHORED, Nfa.MatchKind.FULL_MATCH, prog.numCaptures())
        return result != null
    }

    // ---------------------------------------------------------------------------
    // Helper methods
    // ---------------------------------------------------------------------------
    private fun assertHasInstOp(prog: Prog, op: InstOp?) {
        var found = false
        for (i in 0..<prog.size()) {
            if (prog.inst(i).op == op) {
                found = true
                break
            }
        }
        Assertions.assertThat(found)
            .withFailMessage("Expected instruction op %s not found in prog:\n%s", op, prog.dump())
            .isTrue()
    }

    private fun countInstOp(prog: Prog, op: InstOp?): Int {
        var count = 0
        for (i in 0..<prog.size()) {
            if (prog.inst(i).op == op) {
                count++
            }
        }
        return count
    }

    private fun assertHasCharRange(prog: Prog, lo: Int, hi: Int) {
        var found = false
        for (i in 0..<prog.size()) {
            val inst = prog.inst(i)
            if (inst.op == InstOp.CHAR_RANGE && inst.lo == lo && inst.hi == hi) {
                found = true
                break
            }
        }
        Assertions.assertThat(found)
            .withFailMessage(
                "Expected CHAR_RANGE [0x%X-0x%X] not found in prog:\n%s", lo, hi, prog.dump()
            )
            .isTrue()
    }

    // ---------------------------------------------------------------------------
    // Literals
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Literals")
    internal inner class Literals {
        @Test
        fun compileSingleLiteral() {
            val prog: Prog? = compile("a")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            Assertions.assertThat(prog!!.size()).isGreaterThan(1)
            CompilerTest.assertHasInstOp(prog, InstOp.CHAR_RANGE)
            CompilerTest.assertHasInstOp(prog, InstOp.MATCH)
        }

        @Test
        fun compileTwoLiterals() {
            val prog: Prog? = compile("ab")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            val charRangeCount = CompilerTest.countInstOp(prog!!, InstOp.CHAR_RANGE)
            Assertions.assertThat(charRangeCount).isGreaterThanOrEqualTo(2)
            CompilerTest.assertHasInstOp(prog, InstOp.MATCH)
        }

        @Test
        fun compileThreeLiterals() {
            val prog: Prog? = compile("abc")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            val charRangeCount = CompilerTest.countInstOp(prog!!, InstOp.CHAR_RANGE)
            Assertions.assertThat(charRangeCount).isGreaterThanOrEqualTo(3)
        }

        @Test
        fun compileEmptyPattern() {
            val prog: Prog? = compile("")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.MATCH)
        }
    }

    // ---------------------------------------------------------------------------
    // Alternation
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Alternation")
    internal inner class Alternation {
        @Test
        fun compileAlternation() {
            val prog: Prog? = compile("a|b")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.ALT)
            CompilerTest.assertHasInstOp(prog, InstOp.MATCH)
        }

        @Test
        fun compileMultiAlternation() {
            val prog: Prog? = compile("a|b|c")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.ALT)
        }
    }

    // ---------------------------------------------------------------------------
    // Quantifiers
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Quantifiers")
    internal inner class Quantifiers {
        @Test
        fun compileStar() {
            val prog: Prog? = compile("a*")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.ALT)
            CompilerTest.assertHasInstOp(prog, InstOp.CHAR_RANGE)
        }

        @Test
        fun compileStarNonGreedy() {
            val prog: Prog? = compile("a*?")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.ALT)
            CompilerTest.assertHasInstOp(prog, InstOp.CHAR_RANGE)
        }

        @Test
        fun compilePlus() {
            val prog: Prog? = compile("a+")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.ALT)
            CompilerTest.assertHasInstOp(prog, InstOp.CHAR_RANGE)
        }

        @Test
        fun compilePlusNonGreedy() {
            val prog: Prog? = compile("a+?")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.ALT)
            CompilerTest.assertHasInstOp(prog, InstOp.CHAR_RANGE)
        }

        @Test
        fun compileQuest() {
            val prog: Prog? = compile("a?")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.ALT)
            CompilerTest.assertHasInstOp(prog, InstOp.CHAR_RANGE)
        }

        @Test
        fun compileQuestNonGreedy() {
            val prog: Prog? = compile("a??")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.ALT)
            CompilerTest.assertHasInstOp(prog, InstOp.CHAR_RANGE)
        }

        @Test
        fun compileFixedRepeat() {
            val prog: Prog? = compile("a{3}")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            val charRangeCount = CompilerTest.countInstOp(prog!!, InstOp.CHAR_RANGE)
            Assertions.assertThat(charRangeCount).isGreaterThanOrEqualTo(3)
        }

        @Test
        fun compileBoundedRepeat() {
            val prog: Prog? = compile("a{2,4}")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.CHAR_RANGE)
            CompilerTest.assertHasInstOp(prog, InstOp.MATCH)
        }
    }

    // ---------------------------------------------------------------------------
    // Character classes
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Character classes")
    internal inner class CharClasses {
        @Test
        fun compileCharClassRange() {
            val prog: Prog? = compile("[a-z]")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.CHAR_RANGE)
            CompilerTest.assertHasCharRange(prog, 'a'.code, 'z'.code)
        }

        @Test
        fun compileCharClassEnumeration() {
            val prog: Prog? = compile("[abc]")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.CHAR_RANGE)
        }

        @Test
        fun compileNegatedCharClass() {
            val prog: Prog? = compile("[^a]")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.CHAR_RANGE)
            val count = CompilerTest.countInstOp(prog, InstOp.CHAR_RANGE)
            Assertions.assertThat(count).isGreaterThanOrEqualTo(1)
        }

        @Test
        fun compileAnyChar() {
            val prog: Prog? = compile(".")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.CHAR_RANGE)
        }

        @Test
        fun compileAnyCharWithDotNl() {
            val prog = compile(".", DEFAULT_FLAGS or ParseFlags.DOT_NL)
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.CHAR_RANGE)
        }
    }

    // ---------------------------------------------------------------------------
    // Captures
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Captures")
    internal inner class Captures {
        @Test
        fun compileSingleCapture() {
            val prog: Prog? = compile("(a)")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.CAPTURE)
            val captureCount = CompilerTest.countInstOp(prog, InstOp.CAPTURE)
            Assertions.assertThat(captureCount).isEqualTo(2)
            Assertions.assertThat(prog.numCaptures()).isEqualTo(2)
        }

        @Test
        fun compileMultiCapture() {
            val prog: Prog? = compile("(a)(b)")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            val captureCount = CompilerTest.countInstOp(prog!!, InstOp.CAPTURE)
            Assertions.assertThat(captureCount).isEqualTo(4)
            Assertions.assertThat(prog.numCaptures()).isEqualTo(3)
        }

        @Test
        fun compileNonCapturingGroup() {
            val prog: Prog? = compile("(?:a)")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            val captureCount = CompilerTest.countInstOp(prog!!, InstOp.CAPTURE)
            Assertions.assertThat(captureCount).isEqualTo(0)
        }

        @Test
        fun compileNestedGroups() {
            val prog: Prog? = compile("((a)(b))")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            Assertions.assertThat(prog!!.numCaptures()).isEqualTo(4)
        }
    }

    // ---------------------------------------------------------------------------
    // Anchors and empty-width assertions
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Anchors and assertions")
    internal inner class Anchors {
        @Test
        fun compileBeginLine() {
            val prog: Prog? = compile("^a")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.EMPTY_WIDTH)
        }

        @Test
        fun compileEndLine() {
            val prog: Prog? = compile("a$")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.EMPTY_WIDTH)
        }

        @Test
        fun compileWordBoundary() {
            val prog: Prog? = compile("\\ba\\b")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            val emptyCount = CompilerTest.countInstOp(prog!!, InstOp.EMPTY_WIDTH)
            Assertions.assertThat(emptyCount).isGreaterThanOrEqualTo(2)
        }

        @Test
        fun anchorFreeProgramsDoNotRequireTextAnchorContext() {
            Assertions.assertThat(compile("abc")!!.hasTextAnchor()).isFalse()
            Assertions.assertThat(compile("[a-z]+")!!.hasTextAnchor()).isFalse()
            Assertions.assertThat(compile("\\ba\\b")!!.hasTextAnchor()).isFalse()
        }

        @Test
        fun textAnchorProgramsRequireTextAnchorContext() {
            Assertions.assertThat(compile("^abc")!!.hasTextAnchor()).isTrue()
            Assertions.assertThat(compile("abc$")!!.hasTextAnchor()).isTrue()
            Assertions.assertThat(compile("\\Aabc")!!.hasTextAnchor()).isTrue()
            Assertions.assertThat(compile("abc\\z")!!.hasTextAnchor()).isTrue()
            Assertions.assertThat(compile("^abc", DEFAULT_FLAGS and ParseFlags.ONE_LINE.inv())!!.hasTextAnchor())
                .isTrue()
            Assertions.assertThat(compile("abc$", DEFAULT_FLAGS and ParseFlags.ONE_LINE.inv())!!.hasTextAnchor())
                .isTrue()
        }

        @Test
        fun anchoredStartPattern() {
            val prog: Prog? = compile("\\Aabc")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            Assertions.assertThat(prog!!.anchorStart()).isTrue()
            Assertions.assertThat(prog.start()).isEqualTo(prog.startUnanchored())
        }

        @Test
        fun anchoredEndPattern() {
            val prog: Prog? = compile("abc\\z")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            Assertions.assertThat(prog!!.anchorEnd()).isTrue()
        }

        @Test
        fun fullyAnchoredPattern() {
            val prog: Prog? = compile("\\Aabc\\z")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            Assertions.assertThat(prog!!.anchorStart()).isTrue()
            Assertions.assertThat(prog.anchorEnd()).isTrue()
        }

        @Test
        fun unanchoredPatternHasDotStar() {
            val prog: Prog? = compile("abc")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            Assertions.assertThat(prog!!.anchorStart()).isFalse()
            Assertions.assertThat(prog.startUnanchored()).isNotEqualTo(prog.start())
        }
    }

    // ---------------------------------------------------------------------------
    // Complex patterns, flags, and output
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Complex patterns and flags")
    internal inner class Complex {
        @Test
        fun compileComplexPattern() {
            val prog: Prog? = compile("a(b|c)*d")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.ALT)
            CompilerTest.assertHasInstOp(prog, InstOp.CHAR_RANGE)
            CompilerTest.assertHasInstOp(prog, InstOp.CAPTURE)
            CompilerTest.assertHasInstOp(prog, InstOp.MATCH)
        }

        @Test
        fun compileDigitsAndDot() {
            val prog: Prog? = compile("(\\d+\\.\\d+)")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.CAPTURE)
            CompilerTest.assertHasInstOp(prog, InstOp.MATCH)
        }

        @Test
        fun compileCaseInsensitive() {
            val prog: Prog? = compile("(?i)abc")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.CHAR_RANGE)
            Assertions.assertThat(CompilerTest.fullMatch(prog, "ABC")).isTrue()
            Assertions.assertThat(CompilerTest.fullMatch(prog, "ABD")).isFalse()
        }

        @Test
        fun compileUnicodeCharacter() {
            val prog: Prog? = compile("\\x{1F600}")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            CompilerTest.assertHasInstOp(prog!!, InstOp.CHAR_RANGE)
            CompilerTest.assertHasInstOp(prog, InstOp.MATCH)
        }

        @Test
        fun compileReversed() {
            val re = Parser.parse("ab", DEFAULT_FLAGS)
            val prog = Compiler.compile(re, true)
            Assertions.assertThat<Prog?>(prog).isNotNull()
            Assertions.assertThat(prog!!.reversed()).isTrue()
        }

        @Test
        fun reasonableInstructionCount() {
            val prog: Prog? = compile("a")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            Assertions.assertThat(prog!!.size()).isLessThan(20)
        }

        @Test
        fun largerPatternReasonableSize() {
            val prog: Prog? = compile("[a-zA-Z_][a-zA-Z0-9_]*")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            Assertions.assertThat(prog!!.size()).isLessThan(100)
        }

        @Test
        fun dumpContainsMeaningfulOutput() {
            val prog: Prog? = compile("a")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            val dump = prog!!.dump()
            Assertions.assertThat(dump).isNotEmpty()
            Assertions.assertThat(dump).contains("match")
        }

        @Test
        fun progToStringNotNull() {
            val prog: Prog? = compile("a+")
            Assertions.assertThat<Prog?>(prog).isNotNull()
            Assertions.assertThat(prog.toString()).isNotNull()
        }
    }
}
