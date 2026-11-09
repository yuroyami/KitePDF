package io.github.yuroyami.kitepdf.writer

import kotlin.test.Test
import kotlin.test.assertEquals

/** The first capacity of an incremental save's output never overflows to a negative size. */
class IncrementalSaveCapacityTest {

    @Test
    fun a_file_near_two_gib_gets_the_largest_capacity_not_a_negative_one() {
        assertEquals(2024, incrementalSaveCapacity(1000))
        assertEquals(Int.MAX_VALUE - 8, incrementalSaveCapacity(Int.MAX_VALUE - 100))
        assertEquals(Int.MAX_VALUE - 8, incrementalSaveCapacity(Int.MAX_VALUE))
    }
}
