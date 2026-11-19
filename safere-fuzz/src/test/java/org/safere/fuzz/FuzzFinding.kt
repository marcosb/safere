// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import org.safere.FuzzDivergenceEvidence
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*

/** Lossless finding context, separate from Jazzer's raw reproducer and run metadata.  */
internal object FuzzFinding {
    fun failure(
        regex: String?,
        flags: Int,
        input: String?,
        operation: String?,
        state: String?,
        history: String?,
        safeRe: String?,
        jdk: String?
    ): AssertionError {
        val fields: MutableMap<String?, String?> = LinkedHashMap<String?, String?>()
        fields.put("target", System.getProperty("safere.fuzz.target", "local-test"))
        fields.put("commit", System.getProperty("safere.fuzz.commit", "unrecorded"))
        fields.put("rawReproducerDirectory", System.getProperty("jazzer.reproducer_path", "default"))
        fields.put("divergenceSignals", FuzzDivergenceEvidence.observed())
        fields.put("regex", regex)
        fields.put("flags", flags.toString())
        fields.put("input", input)
        fields.put("operation", operation)
        fields.put("state", state)
        fields.put("history", history)
        fields.put("safeRe", safeRe)
        fields.put("jdk", jdk)
        fields.put("javaVersion", System.getProperty("java.runtime.version"))
        val json = json(fields)
        val finding = AssertionError(operation + " divergence\n" + json)
        val directory = System.getProperty("safere.fuzz.findingsDir")
        if (directory != null) {
            try {
                val output = Path.of(directory)
                Files.createDirectories(output)
                val bytes = json.toByteArray(StandardCharsets.UTF_8)
                val digest =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                Files.writeString(output.resolve(digest + ".json"), json + "\n")
            } catch (exception: IOException) {
                // Preserve the semantic finding even if evidence cannot be persisted.
                finding.addSuppressed(exception)
            } catch (exception: NoSuchAlgorithmException) {
                finding.addSuppressed(exception)
            }
        }
        return finding
    }

    fun json(fields: MutableMap<String?, String?>): String {
        val json = StringBuilder("{")
        var separator = ""
        for (entry in fields.entries) {
            json.append(separator)
                .append(FuzzSupport.javaStringLiteral(entry.key))
                .append(":")
                .append(FuzzSupport.javaStringLiteral(entry.value))
            separator = ","
        }
        return json.append('}').toString()
    }
}
