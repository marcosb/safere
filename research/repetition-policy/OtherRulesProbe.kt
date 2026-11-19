/** Collects JDK-only policy observations through supported public APIs.  */
object OtherRulesProbe {
    private fun bounds(match: MatchResult, captures: Boolean): String {
        val result: StringBuilder = StringBuilder("[")
        for (group in 0..(if (captures) match.groupCount() else 0)) {
            if (group > 0) {
                result.append(',')
            }
            if (match.start(group) < 0) {
                result.append("null")
            } else {
                result.append('[').append(match.start(group)).append(',').append(match.end(group))
                result.append(']')
            }
        }
        return result.append(']').toString()
    }

    private fun anchored(pattern: Pattern, text: String?, full: Boolean): String? {
        val matcher: Matcher = pattern.matcher(text)
        return if (if (full) matcher.matches() else matcher.lookingAt()) bounds(matcher, true) else "null"
    }

    private fun history(pattern: Pattern, text: String?): List<MatchResult?> {
        val result: List<MatchResult?> = ArrayList()
        val matcher: Matcher = pattern.matcher(text)
        while (matcher.find()) {
            result.add(matcher.toMatchResult())
        }
        return result
    }

    private fun operations(pattern: Pattern, text: String?, matches: List<MatchResult?>): String {
        val spans: List<String?> = matches.stream().map({ m -> bounds(m, true) }).toList()
        return ("{\"first\":"
                + (if (spans.isEmpty()) "null" else spans.getFirst())
                + ",\"lookingAt\":"
                + anchored(pattern, text, false)
                + ",\"matches\":"
                + anchored(pattern, text, true)
                + ",\"history\":["
                + String.join(",", spans)
                + "]}")
    }

    private fun captureText(match: MatchResult): String {
        val result: StringBuilder = StringBuilder("<")
        for (group in 1..match.groupCount()) {
            result.append(if (match.group(group) == null) "" else match.group(group)).append('|')
        }
        return result.append('>').toString()
    }

    private fun replacement(matches: List<MatchResult?>, text: String, captures: Boolean): String {
        val result: StringBuilder = StringBuilder()
        var previous = 0
        for (match in matches) {
            result.append(text, previous, match.start())
            result.append(if (captures) captureText(match) else "X")
            previous = match.end()
        }
        return result.append(text, previous, text.length()).toString()
    }

    private fun split(matches: List<MatchResult?>, text: String, limit: Int): List<String?> {
        val result: List<String?> = ArrayList()
        var previous = 0
        for (match in matches) {
            if (limit > 0 && result.length() === limit - 1) {
                break
            }
            if (previous == 0 && match.start() === 0 && match.end() === 0) {
                continue
            }
            result.add(text.substring(previous, match.start()))
            previous = match.end()
        }
        if (previous == 0 && result.isEmpty()) {
            return List.of(text)
        }
        result.add(text.substring(previous))
        if (limit == 0) {
            while (!result.isEmpty() && result.getLast().isEmpty()) {
                result.removeLast()
            }
        }
        return result
    }

    private fun different(expected: Object, actual: Object?): Int {
        return if (expected.equals(actual)) 0 else 1
    }

    private fun checks(pattern: Pattern, text: String, matches: List<MatchResult?>): String {
        val observed: Matcher = pattern.matcher(text)
        val plain: Matcher = pattern.matcher(text)
        var observation = 0
        var index = 0
        while (plain.find()) {
            if (!observed.find()) {
                throw AssertionError("Observation changed find exhaustion")
            }
            observation += different(bounds(plain, false), bounds(observed, false))
            observation += different(bounds(matches.get(index++), true), bounds(observed, true))
            for (group in observed.groupCount() downTo 0) {
                observed.group(group)
            }
            observation += different(bounds(observed, true), bounds(observed.toMatchResult(), true))
        }
        if (observed.find()) {
            throw AssertionError("Observation added a result")
        }
        observed.reset()
        val first: Boolean = observed.find()
        var reset = if (first == !matches.isEmpty()) 0 else 1
        if (first) {
            reset += different(bounds(matches.getFirst(), true), bounds(observed, true))
        }
        val template: StringBuilder = StringBuilder("<")
        for (group in 1..observed.groupCount()) {
            template.append('$').append(group).append('|')
        }
        template.append('>')
        val expected = replacement(matches, text, true)
        val appended: StringBuilder = StringBuilder()
        val appendMatcher: Matcher = pattern.matcher(text)
        while (appendMatcher.find()) {
            appendMatcher.appendReplacement(appended, template.toString())
        }
        appendMatcher.appendTail(appended)
        return ("{\"observation\":"
                + observation
                + ",\"reset\":"
                + reset
                + ",\"literalReplacement\":"
                + different(replacement(matches, text, false), pattern.matcher(text).replaceAll("X"))
                + ",\"captureReplacement\":"
                + different(expected, pattern.matcher(text).replaceAll(template.toString()))
                + ",\"functionalReplacement\":"
                + different(
            expected,
            pattern.matcher(text).replaceAll({ obj: OtherRulesProbe?, match: MatchResult -> captureText(match) })
        )
                + ",\"appendReplacement\":"
                + different(expected, appended.toString())
                + ",\"splitNegative\":"
                + different(split(matches, text, -1), List.of(pattern.split(text, -1)))
                + ",\"splitZero\":"
                + different(split(matches, text, 0), List.of(pattern.split(text, 0)))
                + ",\"splitPositive\":"
                + different(split(matches, text, 2), List.of(pattern.split(text, 2)))
                + "}")
    }

    private fun decode(value: String?): String {
        return String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
    }

    private fun supplementaryControls(): String {
        val subjects: List<String?> = List.of("😀", "a😀", "😀a", "😀😀")
        val expressions: List<String?> = List.of("", "()", "(?:|a)", "(?:|.)")
        var utf16 = 0
        var scalar = 0
        var neither = 0
        for (text in subjects) {
            val units: List<String?> = ArrayList()
            for (position in 0..text.length()) {
                units.add("[[" + position + "," + position + "]]")
            }
            val points: List<String?> = ArrayList()
            var position = 0
            while (true) {
                points.add("[[" + position + "," + position + "]]")
                if (position == text.length()) {
                    break
                }
                position = text.offsetByCodePoints(position, 1)
            }
            for (expression in expressions) {
                val actual: List<String?> =
                    history(Pattern.compile(expression), text).stream()
                        .map({ match -> bounds(match, false) })
                        .toList()
                if (actual.equals(units)) {
                    utf16++
                } else if (actual.equals(points)) {
                    scalar++
                } else {
                    neither++
                }
            }
        }
        val starts: StringBuilder = StringBuilder()
        for (expression in List.of("(?:(a)){0,2}b", "(?:(ab)){0,2}b")) {
            val text = if (expression.contains("(ab)")) " ab" else "a b"
            val pattern: Pattern = Pattern.compile(expression)
            val find: Matcher = pattern.matcher(text)
            if (!find.find()) {
                throw AssertionError("Missing failed-start control match")
            }
            val fresh: Matcher = pattern.matcher(text).region(find.start(), text.length())
            if (!fresh.find()) {
                throw AssertionError("Missing isolated-start control match")
            }
            if (!starts.isEmpty()) {
                starts.append(',')
            }
            starts
                .append("{\"regex\":\"")
                .append(expression)
                .append("\",\"text\":\"")
                .append(text)
                .append("\",\"find\":")
                .append(bounds(find, true))
                .append(",\"isolatedStart\":")
                .append(bounds(fresh, true))
                .append('}')
        }
        return ("{\"unicodeEmptyCursor\":{\"utf16\":"
                + utf16
                + ",\"scalar\":"
                + scalar
                + ",\"neither\":"
                + neither
                + "},\"failedStartControls\":["
                + starts
                + "]}")
    }

    /** Runs a declared corpus and records explicit pattern/subject identities.  */
    @kotlin.Throws(Exception::class)
    fun main(args: Array<String?>) {
        val root: Path = Path.of(args[0])
        Files.writeString(root.resolve("java-runtime.txt"), Runtime.version() + "\n")
        Files.writeString(root.resolve("auxiliary.json"), supplementaryControls().toString() + "\n")
        val inputs: List<String?> =
            Files.readAllLines(root.resolve("inputs.tsv")).stream()
                .map({ obj: OtherRulesProbe?, value: String? -> decode(value) })
                .toList()
        Files.newBufferedWriter(root.resolve("jdk.jsonl")).use { output ->
            for (line in Files.readAllLines(root.resolve("patterns.tsv"))) {
                val fields: Array<String?> = line.split("\t", -1)
                val id: Int = Integer.parseInt(fields[0])
                val pattern: Pattern = Pattern.compile(decode(fields[1]))
                val pair: Pattern? = if (fields[2].isEmpty()) null else Pattern.compile(decode(fields[2]))
                for (index in 0..<inputs.size()) {
                    val text: String = inputs.get(index)!!
                    val matches: List<MatchResult?> = history(pattern, text)
                    output.write(
                        ("{\"pattern\":"
                                + id
                                + ",\"input\":"
                                + index
                                + ",\"actual\":"
                                + operations(pattern, text, matches)
                                + ",\"paired\":"
                                + (if (pair == null) "null" else operations(pair, text, history(pair, text)))
                                + ",\"checks\":"
                                + checks(pattern, text, matches)
                                + "}\n")
                    )
                }
            }
        }
    }
}
