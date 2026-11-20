// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/** Tests for deterministic work accounting.  */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class WorkCounterTest {
    @Test
    @DisplayName("countForTesting throws when work counters are disabled")
    fun countForTestingThrowsWhenCountersAreDisabled() {
        Assertions.assertThatThrownBy(ThrowingCallable { WorkCounter.countForTesting({}) })
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("WorkCounter is disabled")
    }

    @Test
    @DisplayName("production bytecode has no work counter method or field references when disabled")
    @Throws(IOException::class, URISyntaxException::class)
    fun productionBytecodeHasNoWorkCounterMethodOrFieldReferencesWhenDisabled() {
        val classesRoot =
            Path.of(WorkCounter::class.java.getProtectionDomain().codeSource.location.toURI())

        if (Files.isRegularFile(classesRoot)) {
            FileSystems.newFileSystem(classesRoot).use { jarFileSystem ->
                assertNoWorkCounterReferences(jarFileSystem.getPath("/"))
            }
        } else {
            assertNoWorkCounterReferences(classesRoot)
        }
    }

    private class ConstantPool(size: Int) {
        val refs: MutableList<Ref> = ArrayList<Ref>()
        private val utf8s: Array<String?>
        private val classNameIndexes: IntArray
        private val nameAndTypeNameIndexes: IntArray

        init {
            utf8s = arrayOfNulls<String>(size)
            classNameIndexes = IntArray(size)
            nameAndTypeNameIndexes = IntArray(size)
        }

        fun className(classIndex: Int): String {
            return utf8s[classNameIndexes[classIndex]]!!
        }

        fun name(ref: Ref): String {
            return utf8s[nameAndTypeNameIndexes[ref.nameAndTypeIndex]]!!
        }

        @JvmRecord
        data class Ref(val classIndex: Int, val nameAndTypeIndex: Int)
        companion object {
            @Throws(IOException::class)
            fun read(data: DataInputStream): ConstantPool {
                val constantPoolCount = data.readUnsignedShort()
                val constantPool = ConstantPool(constantPoolCount)
                var index = 1
                while (index < constantPoolCount) {
                    when (val tag = data.readUnsignedByte()) {
                        1 -> constantPool.utf8s[index] = data.readUTF()
                        3, 4 -> data.skipBytes(4)
                        5, 6 -> {
                            data.skipBytes(8)
                            index++
                        }

                        7 -> constantPool.classNameIndexes[index] = data.readUnsignedShort()
                        8, 16, 19, 20 -> data.skipBytes(2)
                        9, 10, 11 -> {
                            val classIndex = data.readUnsignedShort()
                            val nameAndTypeIndex = data.readUnsignedShort()
                            constantPool.refs.add(Ref(classIndex, nameAndTypeIndex))
                        }

                        12 -> {
                            constantPool.nameAndTypeNameIndexes[index] = data.readUnsignedShort()
                            data.readUnsignedShort()
                        }

                        15 -> data.skipBytes(3)
                        17, 18 -> data.skipBytes(4)
                        else -> throw IOException("Unsupported constant pool tag " + tag)
                    }
                    index++
                }
                return constantPool
            }
        }
    }

    companion object {
        @Throws(IOException::class)
        private fun assertNoWorkCounterReferences(classesRoot: Path) {
            val references: MutableList<String> = ArrayList<String>()

            Files.walk(classesRoot).use { classFiles ->
                val compiledClasses =
                    classFiles.filter { path: Path -> path.toString().endsWith(".class") }.toList()
                for (classFile in compiledClasses) {
                    val classFileName = classFile.fileName.toString()
                    if (classFileName.startsWith("WorkCounter")) {
                        continue
                    }
                    references.addAll(workCounterMethodOrFieldReferences(classFile))
                }
            }
            Assertions.assertThat<String?>(references).isEmpty()
        }

        @Throws(IOException::class)
        private fun workCounterMethodOrFieldReferences(classFile: Path): MutableList<String> {
            Files.newInputStream(classFile).use { input ->
                DataInputStream(input).use { data ->
                    val magic = data.readInt()
                    Assertions.assertThat(magic).isEqualTo(-0x35014542)
                    data.readUnsignedShort()
                    data.readUnsignedShort()

                    val constantPool: ConstantPool = ConstantPool.Companion.read(data)
                    val references: MutableList<String> = ArrayList<String>()
                    for (ref in constantPool.refs) {
                        val owner = constantPool.className(ref.classIndex)
                        if (owner != "org/safere/WorkCounter" && owner != "org/safere/WorkCounterConfig") {
                            continue
                        }
                        references.add(
                            classFile.fileName.toString() + " references " + owner + "." + constantPool.name(ref)
                        )
                    }
                    return references
                }
            }
        }
    }
}
