// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.util.*

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class IntArrayListTest {
    @Test
    fun basicOperations() {
        val list = IntArrayList(4)
        Assertions.assertThat(list.isEmpty()).isTrue()
        Assertions.assertThat(list.size).isEqualTo(0)

        list.add(10)
        list.add(20)
        list.add(30)

        Assertions.assertThat(list.isEmpty()).isFalse()
        Assertions.assertThat(list.size).isEqualTo(3)
        Assertions.assertThat(list.get(0)).isEqualTo(10)
        Assertions.assertThat(list.get(1)).isEqualTo(20)
        Assertions.assertThat(list.get(2)).isEqualTo(30)

        Assertions.assertThat(list.removeLast()).isEqualTo(30)
        Assertions.assertThat(list.size).isEqualTo(2)

        Assertions.assertThat(list.toArray()).containsExactly(10, 20)

        list.clear()
        Assertions.assertThat(list.isEmpty()).isTrue()
        Assertions.assertThat(list.size).isEqualTo(0)
        Assertions.assertThat(list.toArray()).isEmpty()
    }

    @Test
    fun growthBeyondCapacity() {
        val list = IntArrayList(2)
        for (i in 0..99) {
            list.add(i)
        }
        Assertions.assertThat(list.size).isEqualTo(100)
        for (i in 0..99) {
            Assertions.assertThat(list.get(i)).isEqualTo(i)
        }
    }

    @Test
    fun toSortedUniqueArray() {
        val list = IntArrayList()
        Assertions.assertThat(list.toSortedUniqueArray()).isEmpty()

        list.add(42)
        Assertions.assertThat(list.toSortedUniqueArray()).containsExactly(42)

        list.clear()
        list.add(5)
        list.add(1)
        list.add(3)
        list.add(1)
        list.add(5)
        list.add(2)
        list.add(3)
        list.add(5)

        Assertions.assertThat(list.toSortedUniqueArray()).containsExactly(1, 2, 3, 5)
    }

    @Test
    fun toSortedUniqueArrayMatchesSortForAnyRunStructure() {
        val random = Random(0x5AFE292L)
        for (trial in 0..499) {
            val list = IntArrayList()
            val runs = 1 + random.nextInt(if (trial % 2 == 0) 8 else 200)
            for (run in 0..<runs) {
                var value = random.nextInt(1000)
                val length = random.nextInt(40)
                for (i in 0..<length) {
                    list.add(value)
                    value += random.nextInt(3)
                }
            }
            val expected = Arrays.stream(list.toArray()).sorted().distinct().toArray()
            Assertions.assertThat(list.toSortedUniqueArray()).containsExactly(*expected)
        }
    }
}
