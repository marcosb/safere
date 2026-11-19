// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.crosscheck

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.safere.crosscheck.Matcher.Companion.quoteReplacement
import org.safere.crosscheck.Pattern.Companion.compile
import org.safere.crosscheck.Pattern.Companion.matches
import org.safere.crosscheck.Pattern.Companion.quote
import org.safere.crosscheck.TraceRecorder.TraceEntry
import java.io.*
import java.util.regex.MatchResult
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Tests for the safere-crosscheck module.  */
internal object CrosscheckTest {
    @Throws(Exception::class)
    private fun roundTrip(pattern: Pattern?): Pattern {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { out ->
            out.writeObject(pattern)
        }
        ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { `in` ->
            return `in`.readObject() as Pattern
        }
    }

    // ---------------------------------------------------------------------------
    // Pattern.compile
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Pattern.compile")
    internal inner class CompileTests {
        @Test
        @DisplayName("compiles a simple pattern on both engines")
        fun simpleCompile() {
            val p = compile("abc")
            Assertions.assertThat(p.pattern()).isEqualTo("abc")
            Assertions.assertThat(p.flags()).isEqualTo(0)
        }

        @Test
        @DisplayName("compiles with flags")
        fun compileWithFlags() {
            val p = compile("abc", Pattern.CASE_INSENSITIVE)
            Assertions.assertThat(p.flags()).isEqualTo(Pattern.CASE_INSENSITIVE)
        }

        @Test
        @DisplayName("serialized pattern round-trips through regex and flags")
        @Throws(Exception::class)
        fun serializedPatternRoundTripsThroughRegexAndFlags() {
            val p = compile("(?<word>[a-z]+)-(\\d+)", Pattern.CASE_INSENSITIVE)

            val restored = roundTrip(p)

            Assertions.assertThat<Pattern?>(restored).isInstanceOf(Serializable::class.java)
            Assertions.assertThat(restored.pattern()).isEqualTo("(?<word>[a-z]+)-(\\d+)")
            Assertions.assertThat(restored.flags()).isEqualTo(Pattern.CASE_INSENSITIVE)
            val m = restored.matcher("Abc-123")
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group("word")).isEqualTo("Abc")
            Assertions.assertThat(m.group(2)).isEqualTo("123")
        }

        @Test
        @DisplayName("compiles escaped non-ASCII literals on both engines")
        fun escapedNonAsciiLiteral() {
            val p = compile("^\\©")
            Assertions.assertThat(p.matcher("©").matches()).isTrue()
        }

        @Test
        @DisplayName("throws UnsupportedPatternException for backreference")
        fun backreference() {
            Assertions.assertThatThrownBy(ThrowingCallable { compile("(a)\\1") })
                .isInstanceOf(UnsupportedPatternException::class.java)
                .hasCauseInstanceOf(PatternSyntaxException::class.java)
        }

        @Test
        @DisplayName("throws UnsupportedPatternException for lookahead")
        fun lookahead() {
            Assertions.assertThatThrownBy(ThrowingCallable { compile("a(?=b)") })
                .isInstanceOf(UnsupportedPatternException::class.java)
        }

        @Test
        @DisplayName("throws PatternSyntaxException when both engines reject")
        fun bothReject() {
            Assertions.assertThatThrownBy(ThrowingCallable { compile("[") })
                .isInstanceOf(PatternSyntaxException::class.java)
                .isNotInstanceOf(UnsupportedPatternException::class.java)
        }
    }

    // ---------------------------------------------------------------------------
    // Pattern.matches
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Pattern.matches")
    internal inner class PatternMatchesTests {
        @Test
        @DisplayName("static matches returns true when both agree")
        fun matchesTrue() {
            Assertions.assertThat(matches("\\d+", "123")).isTrue()
        }

        @Test
        @DisplayName("static matches returns false when both agree")
        fun matchesFalse() {
            Assertions.assertThat(matches("\\d+", "abc")).isFalse()
        }
    }

    // ---------------------------------------------------------------------------
    // Matcher — core matching
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Matcher matching")
    internal inner class MatcherMatchingTests {
        @Test
        @DisplayName("String find operations keep a UTF-8 shadow for scalar-valid input")
        fun utf8ShadowChecksFindAndCaptures() {
            val m = compile("(?<letter>é)|(😀)").matcher("xé😀y")

            Assertions.assertThat(m.utf8ShadowActive()).isTrue()
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group("letter")).isEqualTo("é")
            Assertions.assertThat(m.start("letter")).isEqualTo(1)
            Assertions.assertThat(m.end("letter")).isEqualTo(2)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(2)).isEqualTo("😀")
            Assertions.assertThat(m.start(2)).isEqualTo(2)
            Assertions.assertThat(m.end(2)).isEqualTo(4)
            Assertions.assertThat(m.find()).isFalse()
            Assertions.assertThat(m.utf8ShadowActive()).isTrue()
        }

        @Test
        @DisplayName("UTF-8 shadow excludes inputs that cannot round-trip through UTF-8")
        fun utf8ShadowExcludesUnpairedSurrogatesAndMutableInputs() {
            Assertions.assertThat(compile(".").matcher("a\ud800b").utf8ShadowActive()).isFalse()
            val mutableInput: CharSequence = StringBuffer("abc")
            Assertions.assertThat(compile(".").matcher(mutableInput).utf8ShadowActive()).isFalse()
        }

        @Test
        @DisplayName("reset re-enables the UTF-8 shadow after an unsupported operation")
        fun resetReenablesUtf8Shadow() {
            val m = compile("a").matcher("a")

            Assertions.assertThat(m.utf8ShadowActive()).isTrue()
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.utf8ShadowActive()).isFalse()
            m.reset()
            Assertions.assertThat(m.utf8ShadowActive()).isTrue()
            Assertions.assertThat(m.find()).isTrue()
        }

        @Test
        @DisplayName("String matches inside surrogate pairs disable the UTF-8 shadow")
        fun scalarInternalStringMatchDisablesUtf8Shadow() {
            val m = compile("\\X\\X").matcher("🇺🇸")

            Assertions.assertThat(m.utf8ShadowActive()).isTrue()
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(1)
            Assertions.assertThat(m.end()).isEqualTo(4)
            Assertions.assertThat(m.utf8ShadowActive()).isFalse()
        }

        @Test
        @DisplayName("matches() returns true for full match")
        fun matchesFull() {
            val m = compile("\\w+").matcher("hello")
            Assertions.assertThat(m.matches()).isTrue()
        }

        @Test
        @DisplayName("matches() returns false for partial")
        fun matchesPartial() {
            val m = compile("\\d+").matcher("abc123")
            Assertions.assertThat(m.matches()).isFalse()
        }

        @Test
        @DisplayName("lookingAt() matches prefix")
        fun lookingAt() {
            val m = compile("\\d+").matcher("123abc")
            Assertions.assertThat(m.lookingAt()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("123")
        }

        @Test
        @DisplayName("find() iterates all matches")
        fun findAll() {
            val m = compile("\\d+").matcher("a1b23c456")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("1")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("23")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("456")
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("find(int) starts at given index")
        fun findFromIndex() {
            val m = compile("\\d+").matcher("a1b23c456")
            Assertions.assertThat(m.find(3)).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("23")
        }
    }

    // ---------------------------------------------------------------------------
    // Matcher — groups
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Matcher groups")
    internal inner class MatcherGroupTests {
        @Test
        @DisplayName("capture groups match")
        fun captureGroups() {
            val m = compile("(\\w+)@(\\w+)").matcher("user@host")
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.groupCount()).isEqualTo(2)
            Assertions.assertThat(m.group(1)).isEqualTo("user")
            Assertions.assertThat(m.group(2)).isEqualTo("host")
            Assertions.assertThat(m.start(1)).isEqualTo(0)
            Assertions.assertThat(m.end(1)).isEqualTo(4)
        }

        @Test
        @DisplayName("named groups match")
        fun namedGroups() {
            val m = compile("(?<user>\\w+)@(?<host>\\w+)").matcher("foo@bar")
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group("user")).isEqualTo("foo")
            Assertions.assertThat(m.group("host")).isEqualTo("bar")
            Assertions.assertThat(m.start("user")).isEqualTo(0)
            Assertions.assertThat(m.end("host")).isEqualTo(7)
        }

        @Test
        @DisplayName("non-participating group returns null")
        fun nonParticipating() {
            val m = compile("(a)|(b)").matcher("b")
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group(1)).isNull()
            Assertions.assertThat(m.group(2)).isEqualTo("b")
        }
    }

    // ---------------------------------------------------------------------------
    // Matcher — replace
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Matcher replace")
    internal inner class MatcherReplaceTests {
        @Test
        @DisplayName("String replacement operations are checked against UTF-8 output")
        fun utf8ShadowChecksReplacement() {
            val incremental = compile("(?<letter>é)").matcher("xéyéz")
            val output = StringBuilder()

            while (incremental.find()) {
                incremental.appendReplacement(output, "\${letter}\${letter}")
            }
            incremental.appendTail(output)

            Assertions.assertThat(output).hasToString("xééyééz")
            Assertions.assertThat(incremental.utf8ShadowActive()).isTrue()
            Assertions.assertThat(compile("😀").matcher("a😀b😀c").replaceAll("é")).isEqualTo("aébéc")
        }

        @Test
        @DisplayName("replaceAll replaces all occurrences")
        fun replaceAll() {
            val m = compile("\\d").matcher("a1b2c3")
            Assertions.assertThat(m.replaceAll("X")).isEqualTo("aXbXcX")
        }

        @Test
        @DisplayName("replaceFirst replaces first occurrence")
        fun replaceFirst() {
            val m = compile("\\d").matcher("a1b2c3")
            Assertions.assertThat(m.replaceFirst("X")).isEqualTo("aXb2c3")
        }

        @Test
        @DisplayName("replaceAll with group reference")
        fun replaceAllGroupRef() {
            val m = compile("(\\w+)").matcher("hello world")
            Assertions.assertThat(m.replaceAll("[$1]")).isEqualTo("[hello] [world]")
        }

        @Test
        @DisplayName("appendReplacement/appendTail")
        fun appendReplacementTail() {
            val m = compile("\\d+").matcher("a1b2c3")
            val sb = StringBuilder()
            while (m.find()) {
                m.appendReplacement(sb, "X")
            }
            m.appendTail(sb)
            Assertions.assertThat(sb.toString()).isEqualTo("aXbXcX")
        }

        @Test
        @DisplayName("appendReplacement/appendTail with direct StringBuilder mutation")
        fun appendReplacementTailWithDirectStringBuilderMutation() {
            val m = compile("\\{\\{(.+?)\\}\\}").matcher("{{one}};{{two}};tail")
            val sb = StringBuilder("prefix:")
            while (m.find()) {
                val group = m.group(1)
                m.appendReplacement(sb, "")
                sb.append(group)
            }
            m.appendTail(sb)
            Assertions.assertThat(sb.toString()).isEqualTo("prefix:one;two;tail")
        }

        @Test
        @DisplayName("appendReplacement/appendTail with direct StringBuffer mutation")
        fun appendReplacementTailWithDirectStringBufferMutation() {
            val m = compile("\\{\\{(.+?)\\}\\}").matcher("{{one}};{{two}};tail")
            val sb = StringBuffer("prefix:")
            while (m.find()) {
                val group = m.group(1)
                m.appendReplacement(sb, "")
                sb.append(group)
            }
            m.appendTail(sb)
            Assertions.assertThat(sb.toString()).isEqualTo("prefix:one;two;tail")
        }
    }

    // ---------------------------------------------------------------------------
    // Matcher — results
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Matcher results")
    internal inner class MatcherResultsTests {
        @Test
        @DisplayName("results() compares match result snapshots")
        fun resultsComparesSnapshots() {
            val m = compile("(\\w+)").matcher("one two")

            Assertions.assertThat<String?>(
                m.results().map<String?> { result: MatchResult? -> result!!.group(1) + ":" + result.start() }
                    .toList())
                .containsExactly("one:0", "two:4")
        }

        @Test
        @DisplayName("results() compares named match result snapshots")
        fun resultsComparesNamedSnapshots() {
            val m = compile("(?<word>\\w+)-(?<digits>\\d+)").matcher("a-12 b-345")

            Assertions.assertThat<String?>(
                m.results()
                    .map<String?> { result: MatchResult? ->
                        (result!!.group("word")
                                + ":"
                                + result.start("digits")
                                + "-"
                                + result.end("digits")
                                + ":"
                                + result.namedGroups().get("word")
                                + ","
                                + result.namedGroups().get("digits"))
                    }
                    .toList())
                .containsExactly("a:2-4:1,2", "b:7-10:1,2")
        }

        @Test
        @DisplayName("results() reports match result divergence")
        fun resultsReportsMatchResultDivergence() {
            val m = compile("(?:(a))*$").matcher("ab")

            Assertions.assertThatThrownBy(ThrowingCallable { m.results().toList() })
                .isInstanceOf(CrosscheckException::class.java)
                .satisfies(
                    { ex: Throwable? ->
                        val ce = ex as CrosscheckException
                        Assertions.assertThat(ce.trace()).contains("DIVERGENCE")
                    })
        }
    }

    // ---------------------------------------------------------------------------
    // Pattern — split
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Pattern split")
    internal inner class PatternSplitTests {
        @Test
        @DisplayName("split with default limit")
        fun splitDefault() {
            val p = compile(",")
            Assertions.assertThat<String?>(p.split("a,b,c")).containsExactly("a", "b", "c")
        }

        @Test
        @DisplayName("split with limit")
        fun splitWithLimit() {
            val p = compile(",")
            Assertions.assertThat<String?>(p.split("a,b,c", 2)).containsExactly("a", "b,c")
        }

        @Test
        @DisplayName("split trailing empty strings")
        fun splitTrailingEmpty() {
            val p = compile(",")
            Assertions.assertThat<String?>(p.split("a,b,")).containsExactly("a", "b")
        }

        @Test
        @DisplayName("splitWithDelimiters")
        fun splitWithDelimiters() {
            val p = compile(",")
            Assertions.assertThat<String?>(p.splitWithDelimiters("a,b,c", -1)).containsExactly("a", ",", "b", ",", "c")
        }
    }

    // ---------------------------------------------------------------------------
    // Matcher — state
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Matcher state")
    internal inner class MatcherStateTests {
        @Test
        @DisplayName("reset allows re-matching")
        fun reset() {
            val m = compile("\\d+").matcher("123")
            Assertions.assertThat(m.matches()).isTrue()
            m.reset()
            Assertions.assertThat(m.matches()).isTrue()
        }

        @Test
        @DisplayName("reset(CharSequence) changes input")
        fun resetWithInput() {
            val m = compile("\\d+").matcher("abc")
            Assertions.assertThat(m.matches()).isFalse()
            m.reset("123")
            Assertions.assertThat(m.matches()).isTrue()
        }

        @Test
        @DisplayName("region constrains matching")
        fun region() {
            val m = compile("\\d+").matcher("abc123def")
            m.region(3, 6)
            Assertions.assertThat(m.regionStart()).isEqualTo(3)
            Assertions.assertThat(m.regionEnd()).isEqualTo(6)
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("123")
        }

        @Test
        @DisplayName("transparent bounds default and toggle")
        fun transparentBounds() {
            val m = compile("\\d+").matcher("123")
            Assertions.assertThat(m.hasTransparentBounds()).isFalse()
            m.useTransparentBounds(true)
            Assertions.assertThat(m.hasTransparentBounds()).isTrue()
        }

        @Test
        @DisplayName("anchoring bounds default and toggle")
        fun anchoringBounds() {
            val m = compile("\\d+").matcher("123")
            Assertions.assertThat(m.hasAnchoringBounds()).isTrue()
            m.useAnchoringBounds(false)
            Assertions.assertThat(m.hasAnchoringBounds()).isFalse()
        }
    }

    // ---------------------------------------------------------------------------
    // Trace recording
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Trace recording")
    internal inner class TraceTests {
        @Test
        @DisplayName("trace captures all API calls")
        fun traceCapture() {
            val m = compile("\\d+").matcher("a1b2")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("1")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("2")
            Assertions.assertThat(m.find()).isFalse()

            val trace = m.trace
            Assertions.assertThat<TraceEntry?>(trace.getEntries()).isNotEmpty()
            Assertions.assertThat<TraceEntry?>(trace.getEntries()).allMatch(TraceEntry::matched)
        }

        @Test
        @DisplayName("trace format produces readable output")
        fun traceFormat() {
            val m = compile("\\w+").matcher("hello")
            m.matches()

            val formatted = m.trace.format()
            Assertions.assertThat(formatted).contains("API call trace")
            Assertions.assertThat(formatted).contains("matches")
        }

        @Test
        @DisplayName("trace can be cleared")
        fun traceClear() {
            val m = compile("\\w+").matcher("hello")
            m.matches()
            Assertions.assertThat<TraceEntry?>(m.trace.getEntries()).isNotEmpty()
            m.trace.clear()
            Assertions.assertThat<TraceEntry?>(m.trace.getEntries()).isEmpty()
        }
    }

    // ---------------------------------------------------------------------------
    // CrosscheckException on known divergence
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("CrosscheckException on known divergence")
    internal inner class DivergenceTests {
        @Test
        @DisplayName("nested repetition with captures: (?:(a))*$ on 'ab' triggers divergence")
        fun nestedRepetitionCaptureDivergence() {
            // Known divergence (issue #52): JDK's backtracking engine leaks captures from failed
            // starting positions in patterns with captures inside zero-or-more repetition anchored
            // by $. For (?:(a))*$ on "ab", both engines find the empty match at position 2 (end
            // of string). SafeRE correctly reports group(1)=null (the * matched zero times), but
            // JDK leaks group(1)="a" from the failed attempt at position 0.
            val p = compile("(?:(a))*$")
            val m = p.matcher("ab")

            // The only match is the empty match at the end of the string.
            // SafeRE: group(1) = null (correct — * matched zero times at this position)
            // JDK:    group(1) = "a" (leaked from failed attempt at earlier position)
            Assertions.assertThatThrownBy(ThrowingCallable { m.find() })
                .isInstanceOf(CrosscheckException::class.java)
                .satisfies(
                    { ex: Throwable? ->
                        val ce = ex as CrosscheckException
                        Assertions.assertThat(ce.trace()).contains("DIVERGENCE")
                    })
        }
    }

    // ---------------------------------------------------------------------------
    // Other
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Other")
    internal inner class OtherTests {
        @Test
        @DisplayName("pattern() returns the crosscheck pattern")
        fun patternAccessor() {
            val p = compile("abc")
            val m = p.matcher("abc")
            Assertions.assertThat<Pattern>(m.pattern()).isSameAs(p)
        }

        @Test
        @DisplayName("toString() returns the regex string")
        fun toStringTest() {
            val p = compile("abc")
            Assertions.assertThat(p.toString()).isEqualTo("abc")
        }

        @Test
        @DisplayName("quote() escapes metacharacters")
        fun quoteTest() {
            val quoted = quote("a.b")
            val p = compile(quoted)
            val m = p.matcher("a.b")
            Assertions.assertThat(m.matches()).isTrue()
            m.reset("axb")
            Assertions.assertThat(m.matches()).isFalse()
        }

        @Test
        @DisplayName("quoteReplacement() escapes replacement chars")
        fun quoteReplacement() {
            Assertions.assertThat(quoteReplacement("$1")).isEqualTo("\\$1")
        }

        @Test
        @DisplayName("toMatchResult() returns snapshot")
        fun toMatchResult() {
            val m = compile("(\\d+)").matcher("abc123def")
            Assertions.assertThat(m.find()).isTrue()
            val mr = m.toMatchResult()
            Assertions.assertThat(mr.group()).isEqualTo("123")
            Assertions.assertThat(mr.group(1)).isEqualTo("123")
            Assertions.assertThat(mr.start()).isEqualTo(3)
            Assertions.assertThat(mr.end()).isEqualTo(6)
        }

        @Test
        @DisplayName("hasMatch() crosschecks matcher lifecycle state")
        fun hasMatch() {
            val m = compile("abc").matcher("abc")
            Assertions.assertThat(m.hasMatch()).isFalse()
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.hasMatch()).isTrue()
            m.usePattern(compile("xyz"))
            Assertions.assertThat(m.hasMatch()).isTrue()
            m.reset()
            Assertions.assertThat(m.hasMatch()).isFalse()
        }

        @Test
        @DisplayName("namedGroups() returns group mapping")
        fun namedGroupsMap() {
            val p = compile("(?<year>\\d{4})-(?<month>\\d{2})")
            Assertions.assertThat<String?, Int?>(p.namedGroups())
                .isEqualTo(
                    Pattern.compile("(?<year>\\d{4})-(?<month>\\d{2})").namedGroups()
                )
        }
    }
}
