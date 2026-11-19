// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets

@DisabledForCrosscheck("HotSpot allocation accounting checks SafeRE implementation behavior")
internal class Utf8AllocationScalingTest {
    @Test
    fun booleanSearchAndRepeatedFindDoNotAllocateWithInputSize() {
        val tracker: AllocationTracker = allocationTracker()
        val threadId = Thread.currentThread().threadId()
        val absent = Pattern.compile("not-present$")
        val repeated = Pattern.compile("z")
        val small = Utf8Input.trusted("a".repeat(10000).toByteArray(StandardCharsets.UTF_8))
        val large = Utf8Input.trusted("a".repeat(100000).toByteArray(StandardCharsets.UTF_8))

        measure(tracker, threadId, Runnable { absent.find(small) })
        measure(tracker, threadId, Runnable { absent.find(large) })
        val smallBoolean: Long = measure(tracker, threadId, Runnable { absent.find(small) })
        val largeBoolean: Long = measure(tracker, threadId, Runnable { absent.find(large) })
        val smallRepeated: Long = measure(tracker, threadId, Runnable { findAll(repeated, small) })
        val largeRepeated: Long = measure(tracker, threadId, Runnable { findAll(repeated, large) })

        Assertions.assertThat(largeBoolean - smallBoolean).isLessThan(32768)
        Assertions.assertThat(largeRepeated - smallRepeated).isLessThan(32768)
    }

    @JvmRecord
    private data class AllocationTracker(val threadBean: Any?, val allocatedBytesMethod: Method?)      {
        fun allocatedBytes(threadId: Long): Long {
            try {
                return allocatedBytesMethod!!.invoke(threadBean, threadId) as Long
            } catch (e: ReflectiveOperationException) {
                throw LinkageError(e.message, e)
            }
        }
    }

    companion object {
        private fun findAll(pattern: Pattern, input: Utf8Input?) {
            val matcher = pattern.matcher(input)
            while (matcher.find()) {
            }
        }

        private fun measure(tracker: AllocationTracker, threadId: Long, operation: Runnable): Long {
            val before = tracker.allocatedBytes(threadId)
            operation.run()
            return tracker.allocatedBytes(threadId) - before
        }

        private fun allocationTracker(): AllocationTracker {
            try {
                val managementFactoryClass = Class.forName("java.lang.management.ManagementFactory")
                val threadBean = managementFactoryClass.getMethod("getThreadMXBean").invoke(null)
                val allocationBeanClass = Class.forName("com.sun.management.ThreadMXBean")
                Assumptions.assumeTrue(allocationBeanClass.isInstance(threadBean))
                val supported = allocationBeanClass.getMethod("isThreadAllocatedMemorySupported")
                val enabled = allocationBeanClass.getMethod("isThreadAllocatedMemoryEnabled")
                val enable =
                    allocationBeanClass.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType)
                val allocated = allocationBeanClass.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
                Assumptions.assumeTrue(supported.invoke(threadBean) as Boolean)
                if ((enabled.invoke(threadBean) as Boolean?) != true) {
                    enable.invoke(threadBean, true)
                }
                return AllocationTracker(threadBean, allocated)
            } catch (e: ReflectiveOperationException) {
                Assumptions.abort<Any>("thread allocation tracking is unavailable: " + e)
                throw LinkageError(e.message, e)
            }
        }
    }
}
