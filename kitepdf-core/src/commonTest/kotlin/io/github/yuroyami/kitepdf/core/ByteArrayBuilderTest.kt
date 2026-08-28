package io.github.yuroyami.kitepdf.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class ByteArrayBuilderTest {

    @Test
    fun append_validates_source_slices_before_copying() {
        val out = ByteArrayBuilder()
        assertFailsWith<IllegalArgumentException> { out.append(byteArrayOf(1), offset = -1, length = 0) }
        assertFailsWith<IllegalArgumentException> { out.append(byteArrayOf(1), offset = 1, length = 1) }
        assertFailsWith<IllegalArgumentException> { out.append(byteArrayOf(1), offset = 0, length = -1) }
        assertContentEquals(ByteArray(0), out.toByteArray())
    }

    @Test
    fun invalid_capacities_and_fill_counts_are_rejected() {
        assertFailsWith<IllegalArgumentException> { ByteArrayBuilder(-1) }
        assertFailsWith<IllegalArgumentException> { ByteArrayBuilder().appendFill(0, -1) }
    }

    @Test
    fun normal_growth_preserves_all_bytes() {
        val out = ByteArrayBuilder(0)
        repeat(1_000) { out.append((it and 0xFF).toByte()) }
        assertContentEquals(ByteArray(1_000) { (it and 0xFF).toByte() }, out.toByteArray())
    }
}
