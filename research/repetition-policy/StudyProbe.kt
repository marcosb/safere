/** Evaluates the generated study through public JDK APIs.  */
object StudyProbe {
    private fun bounds(match: MatchResult, captures: Boolean): String {
        val result: StringBuilder = StringBuilder("[")
        for (group in 0..(if (captures) match.groupCount() else 0)) {
            if (group > 0) {
                result.append(',')
            }
            if (match.start(group) < 0) {
                result.append("null")
            } else {
                result
                    .append('[')
                    .append(match.start(group))
                    .append(',')
                    .append(match.end(group))
                    .append(']')
            }
        }
        return result.append(']').toString()
    }

    private fun operation(pattern: Pattern, text: String?, name: String, captures: Boolean): String? {
        val matcher: Matcher = pattern.matcher(text)
        val matched: Boolean = if (name.equals("lookingAt")) matcher.lookingAt() else matcher.matches()
        return if (matched) bounds(matcher, captures) else "null"
    }

    private fun operations(pattern: Pattern, text: String?, captures: Boolean): String {
        val matcher: Matcher = pattern.matcher(text)
        val history: List<String?> = ArrayList()
        while (matcher.find()) {
            val before = bounds(matcher, captures)
            matcher.group()
            val snapshot: MatchResult = matcher.toMatchResult()
            if (!before.equals(bounds(snapshot, captures)) || !before.equals(bounds(matcher, captures))) {
                throw AssertionError("Observing captures changed the match")
            }
            history.add(before)
        }
        return ("{\"first\":"
                + (if (history.isEmpty()) "null" else history.getFirst())
                + ",\"lookingAt\":"
                + operation(pattern, text, "lookingAt", captures)
                + ",\"matches\":"
                + operation(pattern, text, "matches", captures)
                + ",\"history\":["
                + String.join(",", history)
                + "]}")
    }

    private fun decode(value: String?): String {
        return String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
    }

    /** Runs the declared patterns and writes each result with its pattern and input identity.  */
    @kotlin.Throws(Exception::class)
    fun main(args: Array<String?>) {
        val root: Path = Path.of(args[0])
        Files.writeString(root.resolve("java-runtime.txt"), Runtime.version() + "\n")
        val inputs: List<String?> =
            Files.readAllLines(root.resolve("inputs.tsv")).stream()
                .map({ obj: StudyProbe?, value: String? -> decode(value) }).toList()
        Files.newBufferedWriter(root.resolve("jdk.jsonl")).use { output ->
            for (line in Files.readAllLines(root.resolve("patterns.tsv"))) {
                val fields: Array<String?> = line.split("\t", -1)
                val id: Int = Integer.parseInt(fields[0])
                val pattern: Pattern = Pattern.compile(decode(fields[1]))
                val validation: Pattern? = if (fields[3]!!.equals("1")) Pattern.compile(decode(fields[2])) else null
                for (index in 0..<inputs.size()) {
                    val text = inputs.get(index)
                    output.write(
                        ("{\"pattern\":"
                                + id
                                + ",\"input\":"
                                + index
                                + ",\"actual\":"
                                + operations(pattern, text, true)
                                + ",\"expandedValidation\":"
                                + (if (validation == null) "null" else operations(validation, text, false))
                                + "}\n")
                    )
                }
            }
        }
    }
}
