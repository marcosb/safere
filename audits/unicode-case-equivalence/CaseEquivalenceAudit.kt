import java.nio.file.Files

/** Black-box audit of case equivalence; run with SafeRE on the class path.  */
object CaseEquivalenceAudit {
    private val LIMIT: Int = Character.MAX_CODE_POINT + 1

    /** Takes the Unicode 17 CaseFolding.txt path and an output directory.  */
    @kotlin.Throws(Exception::class)
    fun main(args: Array<String?>) {
        val data: Path? = Path.of(args[0])
        val output: Path = Path.of(args[1])
        Files.createDirectories(output)
        val parent = IntArray(LIMIT)
        val fold = IntArray(LIMIT)
        for (cp in 0..<LIMIT) {
            parent[cp] = cp
            fold[cp] = cp
        }
        for (line in Files.readAllLines(data)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue
            }
            val fields: Array<String?> = line.split(";")
            if (fields[1].trim().equals("C") || fields[1].trim().equals("S")) {
                val source: Int = Integer.parseInt(fields[0].trim(), 16)
                val target: Int = Integer.parseInt(fields[2].trim(), 16)
                fold[source] = target
                join(parent, source, target)
            }
        }
        for (cp in 0..<LIMIT) {
            join(parent, cp, Character.toUpperCase(cp))
            join(parent, cp, Character.toLowerCase(cp))
            join(parent, cp, Character.toTitleCase(cp))
        }
        val families: Map<Integer?, List<Integer?>?> = TreeMap()
        for (cp in 0..<LIMIT) {
            val root = root(parent, cp)
            if (root != cp) {
                families.computeIfAbsent(root, { key -> ArrayList(List.of(key)) }).add(cp)
            }
        }
        val participants: List<Integer?> = families.values().stream().flatMap(List::stream).sorted().toList()
        val familyLines: List<String?> = ArrayList()
        val extraLinks: List<String?> = ArrayList()
        for (family in families.values()) {
            val formatted: String? = family.stream().map({ obj: CaseEquivalenceAudit?, cp: Int -> hex(cp) })
                .reduce({ a, b -> a.toString() + " " + b }).orElseThrow()
            familyLines.add(formatted)
            if (family.stream().map({ cp -> fold[cp] }).distinct().count() > 1) {
                extraLinks.add(formatted)
            }
        }
        Files.write(output.resolve("families.txt"), familyLines)
        Files.write(output.resolve("broader-than-simple-fold.txt"), extraLinks)
        val differences: List<String?> = ArrayList()
        differences.add("engine\tsyntax\tpattern\tinput\texpected\tactual")
        var pairs: Long = 0
        var jdkDifferences: Long = 0
        var safeDifferences: Long = 0
        val flags: Int = java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE
        for (source in participants) {
            val literal = "\\x{" + Integer.toHexString(source) + "}"
            val regexes = arrayOf<String?>(
                literal, "[" + literal + "]", "[" + literal + "-" + literal + "]",
                "[^" + literal + "]", "[^" + literal + "-" + literal + "]"
            )
            for (syntax in regexes.indices) {
                val jdk: java.util.regex.Matcher = java.util.regex.Pattern.compile(regexes[syntax], flags)
                    .matcher("")
                val safe: org.safere.Matcher = org.safere.Pattern.compile(regexes[syntax], flags).matcher("")
                for (target in participants) {
                    var expected = root(parent, source) == root(parent, target)
                    if (syntax >= 3) {
                        expected = !expected
                    }
                    val input = String(Character.toChars(target))
                    val jdkActual: Boolean = jdk.reset(input).matches()
                    val safeActual: Boolean = safe.reset(input).matches()
                    pairs++
                    if (jdkActual != expected) {
                        jdkDifferences++
                        differences.add(
                            ("JDK\t" + syntax + "\t" + hex(source) + "\t" + hex(target)
                                    + "\t" + expected + "\t" + jdkActual)
                        )
                    }
                    if (safeActual != expected) {
                        safeDifferences++
                        differences.add(
                            ("SafeRE\t" + syntax + "\t" + hex(source) + "\t" + hex(target)
                                    + "\t" + expected + "\t" + safeActual)
                        )
                    }
                }
            }
        }
        Files.write(output.resolve("differences.tsv"), differences)
        val summary = ("java.runtime.version=" + System.getProperty("java.runtime.version")
                + "\ncaseFolding.sha256=" + HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(data))
        )
                + "\nfamilies=" + families.size() + "\nparticipants=" + participants.size()
                + "\nbroaderFamilies=" + extraLinks.size() + "\npairsPerEngine=" + pairs
                + "\njdkDifferences=" + jdkDifferences + "\nsafeDifferences=" + safeDifferences + "\n")
        Files.writeString(output.resolve("summary.txt"), summary)
        System.out.print(summary)
    }

    private fun hex(cp: Int): String {
        return "U+%04X".formatted(cp)
    }

    private fun root(parent: IntArray, cp: Int): Int {
        var cp = cp
        while (parent[cp] != cp) {
            parent[cp] = parent[parent[cp]]
            cp = parent[cp]
        }
        return cp
    }

    private fun join(parent: IntArray, a: Int, b: Int) {
        val first = root(parent, a)
        val second = root(parent, b)
        parent[Math.max(first, second)] = Math.min(first, second)
    }
}
