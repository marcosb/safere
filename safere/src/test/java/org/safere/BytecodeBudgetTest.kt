// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.objectweb.asm.*
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/**
 * Checks every [MaxBytecodeSize] method in SafeRE's compiled classes against its bounds.
 * 
 * 
 * Inlining depends on bytecode size, so these methods can lose much of their callers' throughput
 * to an edit that looks harmless in the source. Benchmarks catch that only if they exercise the
 * affected caller; this catches the size change itself.
 */
@DisabledForCrosscheck("Reads SafeRE's own class files")
internal class BytecodeBudgetTest {
    @JvmRecord
    private data class Budget(val method: String?, val max: Int, val min: Int, val reason: String?, val codeLength: Int)

    @Test
    @Throws(IOException::class, URISyntaxException::class)
    fun annotatedMethodsStayWithinTheirBytecodeBudgets() {
        assumeFalse(
            WorkCounterConfig.ENABLED,
            "-Pwork-counters compiles WorkCounter calls into the methods, which enlarges them"
        )
        val budgets: MutableList<Budget> = ArrayList<Budget>()
        forEachClassFile(ClassFileConsumer { bytes: ByteArray? -> budgets.addAll(budgets(ClassReader(bytes)).filterNotNull()) })

        Assertions.assertThat<Budget?>(budgets).hasSizeGreaterThanOrEqualTo(MIN_ANNOTATED_METHODS)
        val failures: MutableList<String> = ArrayList<String>()
        for (budget in budgets) {
            if (budget.codeLength > budget.max || budget.codeLength < budget.min) {
                failures.add(
                    String.format(
                        "%s has %d bytes of bytecode, outside its budget [%d, %d]: %s",
                        budget.method, budget.codeLength, budget.min, budget.max, budget.reason
                    )
                )
            }
        }
        Assertions.assertThat<String?>(failures).isEmpty()
    }

    private fun interface ClassFileConsumer {
        @Throws(IOException::class)
        fun accept(bytes: ByteArray?)
    }

    companion object {
        private val ANNOTATION: String = Type.getDescriptor(MaxBytecodeSize::class.java)

        /**
         * Guards against the test passing because it found no annotations, for example because the
         * annotation's retention changed or the classes were read from the wrong place.
         */
        private const val MIN_ANNOTATED_METHODS = 3

        /** Visits the class files of package `org.safere`, from a directory or a jar.  */
        @Throws(IOException::class, URISyntaxException::class)
        private fun forEachClassFile(consumer: ClassFileConsumer) {
            val location =
                Path.of(Pattern::class.java.getProtectionDomain().codeSource.location.toURI())
            if (Files.isDirectory(location)) {
                forEachClassFile(location.resolve("org/safere"), consumer)
            } else {
                FileSystems.newFileSystem(location).use { jar ->
                    forEachClassFile(jar.getPath("org/safere"), consumer)
                }
            }
        }

        @Throws(IOException::class)
        private fun forEachClassFile(packageDirectory: Path, consumer: ClassFileConsumer) {
            val classFiles: MutableList<Path>?
            Files.list(packageDirectory).use { files ->
                classFiles = files.filter { f: Path -> f.toString().endsWith(".class") }.toList()
            }
            for (classFile in classFiles!!) {
                consumer.accept(Files.readAllBytes(classFile))
            }
        }

        /** Returns the budgets of the annotated methods in one class.  */
        private fun budgets(reader: ClassReader): MutableList<Budget?> {
            val codeLengths: MutableMap<String?, Int?> = codeLengths(reader)
            val budgets: MutableList<Budget?> = ArrayList<Budget?>()
            reader.accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<String?>?
                    ): MethodVisitor {
                        val method = reader.className.replace('/', '.') + "." + name + descriptor
                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitAnnotation(annotation: String, visible: Boolean): AnnotationVisitor? {
                                if (annotation != ANNOTATION) {
                                    return null
                                }
                                return object : AnnotationVisitor(Opcodes.ASM9) {
                                    var max: Int = -1
                                    var min: Int = 0
                                    var reason: String? = ""

                                    override fun visit(element: String, value: Any?) {
                                        when (element) {
                                            "value" -> max = (value as Int?)!!
                                            "min" -> min = (value as Int?)!!
                                            "reason" -> reason = value as String?
                                            else -> throw AssertionError(element)
                                        }
                                    }

                                    override fun visitEnd() {
                                        budgets.add(
                                            Budget(
                                                method,
                                                max,
                                                min,
                                                reason,
                                                codeLengths.get(name + descriptor)!!
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES
            )
            return budgets
        }

        /**
         * Returns the length of each method's `Code` attribute, keyed by name and descriptor.
         * 
         * 
         * ASM's visitor API doesn't report this length, so this walks the `methods` table of the
         * class file (JVMS 4.1, 4.6, 4.7.3) with ASM's constant-pool-aware readers.
         */
        private fun codeLengths(reader: ClassReader): MutableMap<String?, Int?> {
            val buffer = CharArray(reader.maxStringLength)
            // Skip access_flags, this_class, super_class and the interfaces.
            var offset = reader.header + 6
            offset += 2 + 2 * reader.readUnsignedShort(offset)
            val fields = reader.readUnsignedShort(offset)
            offset += 2
            for (i in 0..<fields) {
                offset = skipAttributes(reader, offset + 6)
            }
            val codeLengths: MutableMap<String?, Int?> = HashMap<String?, Int?>()
            val methods = reader.readUnsignedShort(offset)
            offset += 2
            for (i in 0..<methods) {
                val key = reader.readUTF8(offset + 2, buffer) + reader.readUTF8(offset + 4, buffer)
                val attributes = reader.readUnsignedShort(offset + 6)
                offset += 8
                for (j in 0..<attributes) {
                    if (reader.readUTF8(offset, buffer) == "Code") {
                        // attribute_name_index, attribute_length, max_stack, max_locals, then code_length.
                        codeLengths.put(key, reader.readInt(offset + 10))
                    }
                    offset += 6 + reader.readInt(offset + 2)
                }
            }
            return codeLengths
        }

        /** Skips an `attributes_count` and the attributes that follow it.  */
        private fun skipAttributes(reader: ClassReader, offset: Int): Int {
            var offset = offset
            val attributes = reader.readUnsignedShort(offset)
            offset += 2
            for (i in 0..<attributes) {
                offset += 6 + reader.readInt(offset + 2)
            }
            return offset
        }
    }
}
