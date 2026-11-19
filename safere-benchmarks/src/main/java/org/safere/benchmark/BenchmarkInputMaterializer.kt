// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import org.safere.benchmark.DeclarativeBenchmarkPlan.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*
import kotlin.math.max
import kotlin.math.min

()
import kotlin.text.repeat
import kotlin.text.substring
import kotlin.text.toByteArray

/**
 * Materializes resolved benchmark configuration and deterministic UTF-8 input files.
 * 
 * 
 * The bounded recipes in `benchmark-data.json` remain the human-editable source of truth.
 * Benchmark engines consume only the resulting byte-identical corpus.
 */
class BenchmarkInputMaterializer private constructor(
    data: JsonObject,
    benchmarkDataSha256: String?,
    benchmarkDirectory: Path
) {
    private val data: JsonObject
    private val benchmarkDataSha256: String?
    private val benchmarkDirectory: Path
    private val declarations: MutableMap<String?, InputDeclaration>
    private val inputs: MutableMap<String?, ByteArray?> = LinkedHashMap<String?, ByteArray?>()

    init {
        BenchmarkDataSchema.validate(data)
        this.data = PatternProfiles.normalizeInline(data)
        this.benchmarkDataSha256 = benchmarkDataSha256
        this.benchmarkDirectory = benchmarkDirectory.toAbsolutePath().normalize()
        require(
            !(!this.data.has("schemaVersion")
                    || this.data.get("schemaVersion").asInt != DeclarativeBenchmarkPlan.SCHEMA_VERSION)
        ) { "benchmark-data.json requires schemaVersion " + DeclarativeBenchmarkPlan.SCHEMA_VERSION }
        require(
            !(!this.data.has("inputs") || !this.data.get("inputs").isJsonArray)
        ) { "benchmark-data.json requires declarative inputs" }
        PatternProfiles.parse(this.data.get("patternProfiles"))
        PatternProfiles.parse(this.data.get("replacementProfiles"))
        declarations =
            DeclarativeBenchmarkPlan.parseInputDeclarations(this.data.getAsJsonArray("inputs"))
    }

    private fun generate() {
        for (inputId in declarations.keys) {
            materialize(inputId)
        }
        check(inputs.size == declarations.size) { "Materialized " + inputs.size + " inputs for " + declarations.size + " declarations" }
    }

    private fun materialize(inputId: String?): ByteArray? {
        val pending: Deque<MaterializationFrame> = ArrayDeque<MaterializationFrame>()
        val activeRecipes: MutableSet<String?> = LinkedHashSet<String?>()
        pending.push(MaterializationFrame(inputId, false))
        while (!pending.isEmpty()) {
            val frame = pending.pop()
            if (inputs.containsKey(frame.inputId)) {
                activeRecipes.remove(frame.inputId)
                continue
            }
            val declaration: InputDeclaration = declarations.get(frame.inputId)!!
            requireNotNull(declaration) { "Input recipe references unknown materialized input: " + frame.inputId }
            if (frame.dependenciesResolved) {
                val bytes = evaluateBytes(declaration.recipe)
                require(
                    inputs.put(
                        frame.inputId,
                        bytes
                    ) == null
                ) { "Duplicate materialized input key: " + frame.inputId }
                activeRecipes.remove(frame.inputId)
                continue
            }
            require(activeRecipes.add(frame.inputId)) {
                ("Cyclic materialized input recipe dependency: "
                        + String.join(" -> ", activeRecipes)
                        + " -> "
                        + frame.inputId)
            }
            pending.push(MaterializationFrame(frame.inputId, true))
            val dependency: String? = inputDependency(declaration.recipe)
            if (dependency != null && !inputs.containsKey(dependency)) {
                require(!activeRecipes.contains(dependency)) {
                    ("Cyclic materialized input recipe dependency: "
                            + String.join(" -> ", activeRecipes)
                            + " -> "
                            + dependency)
                }
                pending.push(MaterializationFrame(dependency, false))
            }
        }
        return inputs.get(inputId)
    }

    private fun evaluate(recipe: InputRecipe): String {
        val arguments = recipe.arguments
        return when (recipe.kind) {
            RecipeKind.LITERAL -> string(arguments, "text")
            RecipeKind.FILE -> throw IllegalStateException("File recipes are evaluated as bytes")
            RecipeKind.REPEAT -> string(arguments, "value").repeat(integer(arguments, "count"))
            RecipeKind.REPEAT_TO_LENGTH -> repeatToSize(string(arguments, "unit"), integer(arguments, "length"))
            RecipeKind.REPEAT_AT_LEAST_LENGTH -> repeatAtLeastSize(
                string(arguments, "unit"),
                integer(arguments, "minimumLength")
            )

            RecipeKind.DELIMITED_REPEAT_TO_LENGTH -> repeatedInput(
                string(arguments, "value"),
                integer(arguments, "length"),
                string(arguments, "delimiterAlphabet"),
                integer(arguments, "seed")
            )

            RecipeKind.APPEND_INPUT -> (String(
                inputs.get(string(arguments, "input"))!!,
                StandardCharsets.UTF_8
            ) + string(arguments, "suffix"))

            RecipeKind.RANDOM_CHARS -> randomChars(
                string(arguments, "alphabet"),
                integer(arguments, "length"),
                integer(arguments, "seed")
            )

            RecipeKind.RANDOM_CODE_POINTS -> randomCodePoints(
                integers(arguments, "codePoints")!!,
                integer(arguments, "minimumCodeUnits"),
                integer(arguments, "seed")
            )

            RecipeKind.SURROUND_TO_LENGTH -> surroundToSize(
                string(arguments, "prefix"),
                string(arguments, "unit"),
                string(arguments, "suffix"),
                integer(arguments, "length")
            )

            RecipeKind.SUFFIX_TO_LENGTH -> suffixMatchToSize(
                string(arguments, "prefixUnit"),
                string(arguments, "suffix"),
                integer(arguments, "length")
            )

            RecipeKind.PREFIXED_REPEAT_TO_LENGTH -> prefixedInput(
                string(arguments, "prefix"),
                string(arguments, "value"),
                integer(arguments, "length"),
                string(arguments, "delimiterAlphabet"),
                integer(arguments, "seed")
            )

            RecipeKind.SPARSE_MATCH_TO_LENGTH -> sparseInput(
                string(arguments, "match"),
                string(arguments, "nonMatch"),
                integer(arguments, "length"),
                integer(arguments, "seed"),
                integer(arguments, "nonMatchRepeats"),
                string(arguments, "delimiterAlphabet")
            )

            RecipeKind.CENTER_IN_SPACES -> surroundWithSpaces(string(arguments, "body"), integer(arguments, "length"))
            RecipeKind.SCALED_CENTER_IN_SPACES -> scaledSurroundWithSpaces(
                string(arguments, "bodyPrefix"),
                string(arguments, "bodySuffix"),
                string(arguments, "bodyFill"),
                integer(arguments, "bodyScalePercent"),
                integer(arguments, "length")
            )

            RecipeKind.LAZY_ALTERNATION_TO_LENGTH -> lazyAltInput(
                string(arguments, "prefixUnit"),
                string(arguments, "match"),
                string(arguments, "suffixUnit"),
                integer(arguments, "length")
            )

            RecipeKind.PERIODIC_ALTERNATION_TO_LENGTH -> altCaptureInput(
                string(arguments, "hitUnit"),
                string(arguments, "missUnit"),
                integer(arguments, "hitInterval"),
                integer(arguments, "length")
            )

            RecipeKind.OPTIONAL_REQUIRED_REPEAT_PATTERN -> ((string(arguments, "literal") + "?").repeat(
                integer(
                    arguments,
                    "count"
                )
            )
                    + string(arguments, "literal").repeat(integer(arguments, "count")))
        }
    }

    private fun evaluateBytes(recipe: InputRecipe): ByteArray? {
        if (recipe.kind == RecipeKind.FILE) {
            return readFile(recipe.arguments)
        }
        return evaluate(recipe)!!.toByteArray(StandardCharsets.UTF_8)
    }

    private fun readFile(arguments: MutableMap<String?, RecipeValue?>): ByteArray? {
        val declaredPath: String = string(arguments, "path")
        val relativePath = Path.of(declaredPath)
        require(
            !(relativePath.isAbsolute
                    || !relativePath.startsWith("data") || relativePath.nameCount < 2 || (relativePath != relativePath.normalize()))
        ) { "File recipe path must stay within data: " + declaredPath }

        val bytes: ByteArray?
        try {
            val root = benchmarkDirectory.toRealPath()
            val dataDirectory = root.resolve("data").toRealPath()
            val source = root.resolve(relativePath).toRealPath()
            require(
                !(!dataDirectory.startsWith(root) || !source.startsWith(dataDirectory) || !Files.isRegularFile(
                    source
                ))
            ) { "File recipe path must stay within data: " + declaredPath }
            bytes = Files.readAllBytes(source)
        } catch (exception: IOException) {
            throw IllegalArgumentException("Cannot read file recipe: " + declaredPath, exception)
        }

        val expectedSha256: String = string(arguments, "sha256")
        require(sha256(bytes) == expectedSha256) { "File recipe SHA-256 mismatch: " + declaredPath }
        try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
        } catch (exception: CharacterCodingException) {
            throw IllegalArgumentException(
                "File recipe is not valid UTF-8: " + declaredPath, exception
            )
        }
        return bytes
    }

    @JvmRecord
    private data class MaterializationFrame(val inputId: String?, val dependenciesResolved: Boolean)

    @Throws(IOException::class)
    private fun write(corpusDirectory: Path) {
        if (Files.exists(corpusDirectory)) {
            Files.walk(corpusDirectory).use { paths ->
                for (path in paths.sorted { left: Path, right: Path -> right.compareTo(left) }.toList()) {
                    if (path != corpusDirectory) {
                        Files.delete(path)
                    }
                }
            }
        }
        Files.createDirectories(corpusDirectory)

        val manifest = manifest()
        for (input in inputs.entries) {
            val relativePath =
                manifest
                    .getAsJsonObject("inputs")
                    .getAsJsonObject(input.key)
                    .get("file")
                    .asString
            val output = corpusDirectory.resolve(relativePath)
            Files.createDirectories(output.parent)
            Files.write(output, input.value)
        }
        Files.writeString(
            corpusDirectory.resolve(MANIFEST_FILE),
            GSON.toJson(manifest) + System.lineSeparator(),
            StandardCharsets.UTF_8
        )
        System.out.printf("Materialized %d benchmark inputs in %s%n", inputs.size, corpusDirectory)
    }

    private fun manifest(): JsonObject {
        val manifest = JsonObject()
        manifest.addProperty("version", 1)
        manifest.addProperty(
            "description", "Resolved benchmark configuration and generated UTF-8 inputs."
        )
        manifest.addProperty("benchmarkDataSha256", benchmarkDataSha256)
        manifest.add("benchmarkData", data.deepCopy())
        manifest.add("executionPlan", create(data))
        val entries = JsonObject()
        for (input in inputs.entries) {
            val bytes: ByteArray = input.value!!
            val text = String(bytes, StandardCharsets.UTF_8)
            val declaration: InputDeclaration = declarations.get(input.key)!!
            val entry = JsonObject()
            entry.addProperty("file", input.key!!.replace('.', '/') + ".txt")
            entry.addProperty("shared", declaration.shared)
            entry.addProperty("utf8Bytes", bytes.size)
            entry.addProperty("utf16CodeUnits", text.size)
            entry.addProperty("unicodeScalars", text.codePointCount(0, text.size))
            entry.addProperty("sha256", sha256(bytes))
            entries.add(input.key, entry)
        }
        manifest.add("inputs", entries)
        return manifest
    }

    companion object {
        private val GSON: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        private const val MANIFEST_FILE = "manifest.json"

        /**
         * Generates the resolved benchmark manifest and materialized input corpus.
         * 
         * @param args `[safere-benchmarks-directory] [output-directory]`
         * @throws Exception if materialization fails
         */
        @Throws(Exception::class)
        @JvmStatic
        fun main(args: Array<String>) {
            require(args.size <= 2) {
                ("Usage: BenchmarkInputMaterializer "
                        + "[safere-benchmarks-directory] [output-directory]")
            }
            var benchmarkDirectory = Path.of("safere-benchmarks")
            if (args.size >= 1) {
                benchmarkDirectory = Path.of(args[0])
            }
            var outputDirectory = benchmarkDirectory.resolve("target/benchmark-corpus")
            if (args.size == 2) {
                outputDirectory = Path.of(args[1])
            }

            val benchmarkDataBytes =
                Files.readAllBytes(benchmarkDirectory.resolve("benchmark-data.json"))
            val data: JsonObject =
                GSON.fromJson<JsonObject>(String(benchmarkDataBytes, StandardCharsets.UTF_8), JsonObject::class.java)

            val materializer =
                BenchmarkInputMaterializer(data, sha256(benchmarkDataBytes), benchmarkDirectory)
            BenchmarkDataSchema.requireWorkloads(data)
            materializer.generate()
            materializer.write(outputDirectory)
        }

        @JvmOverloads
        fun materialize(
            data: JsonObject,
            benchmarkDirectory: Path = Path.of(".")
        ): MutableMap<String?, ByteArray?> {
            val materializer =
                BenchmarkInputMaterializer(
                    data, sha256(GSON.toJson(data).toByteArray(StandardCharsets.UTF_8)), benchmarkDirectory
                )
            materializer.generate()
            val result: MutableMap<String?, ByteArray?> = LinkedHashMap<String?, ByteArray?>()
            materializer.inputs.forEach { (id: String?, bytes: ByteArray?) -> result.put(id, bytes!!.clone()) }
            return result
        }

        private fun inputDependency(recipe: InputRecipe): String? {
            if (recipe.kind != RecipeKind.APPEND_INPUT) {
                return null
            }
            return string(recipe.arguments, "input")
        }

        private fun randomChars(alphabet: String, size: Int, seed: Int): String {
            val random = Random(seed.toLong())
            val characters = CharArray(size)
            for (index in 0..<size) {
                characters[index] = alphabet.get(random.nextInt(alphabet.length))
            }
            return String(characters)
        }

        private fun randomCodePoints(codePoints: MutableList<Int?>, minimumSize: Int, seed: Int): String {
            val random = Random(seed.toLong())
            val result = StringBuilder(minimumSize)
            while (result.length < minimumSize) {
                result.appendCodePoint(codePoints.get(random.nextInt(codePoints.size))!!)
            }
            return result.toString()
        }

        private fun repeatAtLeastSize(unit: String, minimumSize: Int): String {
            if (minimumSize == 0) {
                return ""
            }
            val result = StringBuilder(minimumSize + unit.length)
            while (result.length < minimumSize) {
                result.append(unit)
            }
            return result.toString()
        }

        fun repeatToSize(unit: String, size: Int): String {
            if (size == 0) {
                return ""
            }
            require(!unit.isEmpty()) { "Repeat unit must not be empty when size is positive" }
            val result = StringBuilder(size + unit.length)
            while (result.length < size) {
                result.append(unit)
            }
            return result.substring(0, size)
        }

        private fun repeatedInput(
            template: String,
            size: Int,
            alphabet: String,
            seed: Int
        ): String {
            if (template.length >= size) {
                return template.substring(0, size)
            }
            val result = StringBuilder(size)
            var delimiterIndex = seed
            while (result.length < size) {
                result.append(template)
                if (result.length < size) {
                    result.append(alphabet.get(Math.floorMod(delimiterIndex, alphabet.length)))
                    delimiterIndex++
                }
            }
            return result.substring(0, size)
        }

        private fun prefixedInput(
            prefix: String, template: String, size: Int, alphabet: String, seed: Int
        ): String {
            if (prefix.length >= size) {
                return prefix.substring(0, size)
            }
            return prefix + repeatedInput(template, size - prefix.length, alphabet, seed)
        }

        private fun sparseInput(
            match: String?,
            nonMatch: String?,
            size: Int,
            seed: Int,
            nonMatchRepeats: Int,
            alphabet: String
        ): String {
            val result = StringBuilder(size)
            var delimiterIndex = seed
            while (result.length < size) {
                var index = 0
                while (index < nonMatchRepeats && result.length < size) {
                    result.append(nonMatch)
                    if (result.length < size) {
                        result.append(alphabet.get(Math.floorMod(delimiterIndex, alphabet.length)))
                        delimiterIndex++
                    }
                    index++
                }
                if (result.length < size) {
                    result.append(' ').append(match).append(' ')
                }
            }
            return result.substring(0, size)
        }

        private fun scaledSurroundWithSpaces(
            bodyPrefix: String, bodySuffix: String, bodyFill: String, scalePercent: Int, size: Int
        ): String {
            val fixedLength = bodyPrefix.length + bodySuffix.length
            var targetLength = max(fixedLength, size * scalePercent / 100)
            targetLength = min(targetLength, size)
            val body =
                bodyPrefix + repeatToSize(bodyFill, max(0, targetLength - fixedLength)) + bodySuffix
            return surroundWithSpaces(body, size)
        }

        private fun surroundWithSpaces(body: String, size: Int): String {
            if (body.length >= size) {
                return body.substring(0, size)
            }
            val padding = size - body.length
            val leading = padding / 2
            return " ".repeat(leading) + body + " ".repeat(padding - leading)
        }

        private fun surroundToSize(
            prefix: String,
            unit: String,
            suffix: String,
            size: Int
        ): String {
            val bodySize = max(0, size - prefix.length - suffix.length)
            return prefix + repeatToSize(unit, bodySize) + suffix
        }

        private fun suffixMatchToSize(prefixUnit: String, match: String, size: Int): String {
            return repeatToSize(prefixUnit, max(0, size - match.length)) + match
        }

        private fun lazyAltInput(
            prefixUnit: String,
            match: String,
            suffixUnit: String?,
            size: Int
        ): String {
            val result = StringBuilder(size + match.length + prefixUnit.length)
            val halfSize = size / 2
            while (result.length < halfSize) {
                result.append(prefixUnit)
            }
            result.append(match)
            while (result.length < size) {
                result.append(suffixUnit)
            }
            return result.substring(0, size)
        }

        private fun altCaptureInput(
            hitUnit: String, missUnit: String, hitInterval: Int, size: Int
        ): String {
            val result = StringBuilder(size + max(hitUnit.length, missUnit.length))
            var counter = 0
            while (result.length < size) {
                result.append(if (counter % hitInterval == 0) hitUnit else missUnit)
                counter++
            }
            return result.substring(0, size)
        }

        private fun string(
            arguments: MutableMap<String?, RecipeValue?>, name: String?
        ): String {
            return (arguments.get(name) as RecipeString).value
        }

        private fun integer(
            arguments: MutableMap<String?, RecipeValue?>, name: String?
        ): Int {
            return (arguments.get(name) as RecipeInteger).value
        }

        private fun integers(
            arguments: MutableMap<String?, RecipeValue?>, name: String?
        ): MutableList<Int?> {
            return (arguments.get(name) as RecipeIntegerList).values
        }

        private fun sha256(bytes: ByteArray?): String? {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            } catch (exception: NoSuchAlgorithmException) {
                throw AssertionError("SHA-256 must be available", exception)
            }
        }
    }
}
