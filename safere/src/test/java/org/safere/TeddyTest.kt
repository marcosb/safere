// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

internal class TeddyTest {
    @Test
    fun smallMultiLiteralStringMatch() {
        val p = Pattern.compile("INFO|WARN|ERROR")
        val text = "2026-08-18 [WARN] system running normally, no ERROR reported"

        val m = p.matcher(text)
        val matches: MutableList<String?> = ArrayList<String?>()
        val starts: MutableList<Int?> = ArrayList<Int?>()
        while (m.find()) {
            matches.add(m.group())
            starts.add(m.start())
        }

        Assertions.assertThat<String?>(matches).containsExactly("WARN", "ERROR")
        Assertions.assertThat<Int?>(starts).containsExactly(12, 46)
    }

    @Test
    fun smallMultiLiteralUtf16Match() {
        val p = Pattern.compile("東京|大阪|京都|福岡")
        val text = "日本の都市: 東京と京都と大阪を訪問しました。"

        val m = p.matcher(text)
        val matches: MutableList<String?> = ArrayList<String?>()
        val starts: MutableList<Int?> = ArrayList<Int?>()
        while (m.find()) {
            matches.add(m.group())
            starts.add(m.start())
        }

        Assertions.assertThat<String?>(matches).containsExactly("東京", "京都", "大阪")
        Assertions.assertThat<Int?>(starts).containsExactly(7, 10, 13)
    }

    @Test
    fun smallMultiLiteralUtf8Match() {
        val p = Pattern.compile("cat|dog|bird")
        val text = "the quick brown dog jumped over the lazy bird".toByteArray(StandardCharsets.UTF_8)

        val m = p.matcher(Utf8Input.validated(text))
        val matches: MutableList<String?> = ArrayList<String?>()
        while (m.find()) {
            matches.add(String(text, m.start(), m.end() - m.start(), StandardCharsets.UTF_8))
        }

        Assertions.assertThat<String?>(matches).containsExactly("dog", "bird")
    }

    @Test
    fun smallMultiLiteralAbsent() {
        val p = Pattern.compile("true|false")
        val text = "none of the boolean values are present in this long sentence of text"

        val m = p.matcher(text)
        Assertions.assertThat(m.find()).isFalse()

        val utf8Matcher = p.matcher(Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(utf8Matcher.find()).isFalse()
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 14, 15, 16, 31, 32, 63, 64, 127, 128, 255])
    fun smallMultiLiteralAtVariableOffsets(padding: Int) {
        val p = Pattern.compile("INFO|WARN|ERROR")
        val pad = "x".repeat(padding)
        val text = pad + "ERROR" + pad + "WARN"

        val m = p.matcher(text)
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(padding)
        Assertions.assertThat(m.group()).isEqualTo("ERROR")

        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(padding + 5 + padding)
        Assertions.assertThat(m.group()).isEqualTo("WARN")

        Assertions.assertThat(m.find()).isFalse()
    }

    @Test
    fun teddyUsStatesMatch() {
        val p = Pattern.compile(US_STATES)
        val text = "Packages shipped from CA and NY arrived in TX safely."

        val m = p.matcher(text)
        val matches: MutableList<String?> = ArrayList<String?>()
        val starts: MutableList<Int?> = ArrayList<Int?>()
        while (m.find()) {
            matches.add(m.group())
            starts.add(m.start())
        }

        Assertions.assertThat<String?>(matches).containsExactly("CA", "NY", "TX")
        Assertions.assertThat<Int?>(starts).containsExactly(22, 29, 43)
    }

    @Test
    fun teddyUsStatesUtf8Match() {
        val p = Pattern.compile(US_STATES)
        val text = "Orders processed in WA, OR, and NV yesterday.".toByteArray(StandardCharsets.UTF_8)

        val m = p.matcher(Utf8Input.validated(text))
        val matches: MutableList<String?> = ArrayList<String?>()
        while (m.find()) {
            matches.add(String(text, m.start(), m.end() - m.start(), StandardCharsets.UTF_8))
        }

        Assertions.assertThat<String?>(matches).containsExactly("WA", "OR", "NV")
    }

    @Test
    fun teddyHttpVerbsLongText() {
        val p = Pattern.compile("GET|POST|PUT|DELETE|HEAD|OPTIONS|PATCH|TRACE")
        val text = "200 OK after receiving POST /api/v1/resource followed by GET /api/v1/resource"

        val m = p.matcher(text)
        val matches: MutableList<String?> = ArrayList<String?>()
        while (m.find()) {
            matches.add(m.group())
        }

        Assertions.assertThat<String?>(matches).containsExactly("POST", "GET")
    }

    @Test
    fun sevenAlternationsMatch() {
        val p = Pattern.compile("MON|TUE|WED|THU|FRI|SAT|SUN")
        val text = "Schedule: MON at 9am, WED at 2pm, and SUN at 8pm"

        val m = p.matcher(text)
        val matches: MutableList<String?> = ArrayList<String?>()
        val starts: MutableList<Int?> = ArrayList<Int?>()
        while (m.find()) {
            matches.add(m.group())
            starts.add(m.start())
        }

        Assertions.assertThat<String?>(matches).containsExactly("MON", "WED", "SUN")
        Assertions.assertThat<Int?>(starts).containsExactly(10, 22, 38)

        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val utf8Matcher = p.matcher(Utf8Input.validated(bytes))
        val utf8Matches: MutableList<String?> = ArrayList<String?>()
        while (utf8Matcher.find()) {
            utf8Matches.add(
                String(bytes, utf8Matcher.start(), utf8Matcher.end() - utf8Matcher.start(), StandardCharsets.UTF_8)
            )
        }
        Assertions.assertThat<String?>(utf8Matches).containsExactly("MON", "WED", "SUN")
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 15, 16, 31, 32, 63, 64, 127, 128, 255])
    fun teddyMatchAtVariableOffsets(padding: Int) {
        val p = Pattern.compile("GET|POST|PUT|DELETE|HEAD|OPTIONS|PATCH|TRACE")
        val pad = "-".repeat(padding)
        val text = pad + "DELETE" + pad + "POST"

        val m = p.matcher(text)
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(padding)
        Assertions.assertThat(m.group()).isEqualTo("DELETE")

        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(padding + 6 + padding)
        Assertions.assertThat(m.group()).isEqualTo("POST")

        Assertions.assertThat(m.find()).isFalse()

        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val utf8Matcher = p.matcher(Utf8Input.validated(bytes))
        Assertions.assertThat(utf8Matcher.find()).isTrue()
        Assertions.assertThat(utf8Matcher.start()).isEqualTo(padding)
        Assertions.assertThat(utf8Matcher.find()).isTrue()
        Assertions.assertThat(utf8Matcher.start()).isEqualTo(padding + 6 + padding)
        Assertions.assertThat(utf8Matcher.find()).isFalse()
    }

    @Test
    fun teddyAbsentLongText() {
        val p = Pattern.compile(US_STATES)
        val text = "abcdefghijklmnopqrstuvwxyz ".repeat(100)

        val m = p.matcher(text)
        Assertions.assertThat(m.find()).isFalse()

        val utf8Matcher = p.matcher(Utf8Input.validated(text.toByteArray(StandardCharsets.UTF_8)))
        Assertions.assertThat(utf8Matcher.find()).isFalse()
    }

    @Test
    fun teddyEquivalenceWithJdk() {
        val testPatterns = arrayOf<String?>(
            "cat|dog|bird",
            "INFO|WARN|ERROR",
            "GET|POST|PUT|DELETE|HEAD|OPTIONS|PATCH",
            "foo|foobar|foot|foothold|football",
            US_STATES
        )

        val testInputs = arrayOf<String?>(
            "hello world CA and NY are states",
            "POST /index.html HTTP/1.1",
            "nothing matching here whatsoever",
            "cat dog bird",
            "12345 ERROR at line 42 with WARN warning",
            "playing football on foot with a foothold"
        )

        for (pat in testPatterns) {
            val safeRe = Pattern.compile(pat)
            val jdk = Pattern.compile(pat)

            for (input in testInputs) {
                val m1 = safeRe.matcher(input)
                val m2 = jdk.matcher(input)

                while (m1.find()) {
                    Assertions.assertThat(m2.find()).isTrue()
                    Assertions.assertThat(m1.group()).isEqualTo(m2.group())
                    Assertions.assertThat(m1.start()).isEqualTo(m2.start())
                    Assertions.assertThat(m1.end()).isEqualTo(m2.end())
                }
                Assertions.assertThat(m2.find()).isFalse()
            }
        }
    }

    @Test
    fun teddyUtf16NonLatin1StringMatch() {
        val p = org.safere.Pattern.compile("GET|POST|PUT|DELETE|HEAD|OPTIONS|PATCH|TRACE")
        // Non-Latin-1 string (UTF-16 coder = 1) containing Japanese text and emojis
        val text = "リクエストログ: POST /api/v1/user 📦 レスポンス受信後に GET /api/v1/user 🚀 完了"

        val m = p.matcher(text)
        val matches: MutableList<String?> = ArrayList<String?>()
        val starts: MutableList<Int?> = ArrayList<Int?>()
        while (m.find()) {
            matches.add(m.group())
            starts.add(m.start())
        }

        Assertions.assertThat<String?>(matches).containsExactly("POST", "GET")
        Assertions.assertThat<Int?>(starts).containsExactly(9, 40)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 15, 16, 31, 32, 63, 64, 127, 128])
    fun teddyUtf16WithNonAsciiPadding(padding: Int) {
        val p = org.safere.Pattern.compile(US_STATES)
        val nonAsciiPad = "あ".repeat(padding)
        val text = nonAsciiPad + "CA" + nonAsciiPad + "NY" + nonAsciiPad + "TX"

        val m = p.matcher(text)
        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(padding)
        Assertions.assertThat(m.group()).isEqualTo("CA")

        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(padding + 2 + padding)
        Assertions.assertThat(m.group()).isEqualTo("NY")

        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.start()).isEqualTo(padding + 2 + padding + 2 + padding)
        Assertions.assertThat(m.group()).isEqualTo("TX")

        Assertions.assertThat(m.find()).isFalse()
    }

    @Test
    fun teddyUtf16AbsentWithNonAsciiText() {
        val p = org.safere.Pattern.compile(US_STATES)
        val text = "こんにちは世界！これは日本語のテキストです。".repeat(50)

        val m = p.matcher(text)
        Assertions.assertThat(m.find()).isFalse()
    }

    companion object {
        private val US_STATES =
            ("AL|AK|AZ|AR|CA|CO|CT|DE|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO|MT|NE|NV|NH|NJ"
                    + "|NM|NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY")
    }
}
