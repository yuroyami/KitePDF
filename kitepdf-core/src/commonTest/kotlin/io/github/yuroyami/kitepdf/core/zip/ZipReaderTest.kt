package io.github.yuroyami.kitepdf.core.zip

import io.github.yuroyami.kitepdf.core.compression.Deflate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three ZIP shapes the reader used to refuse: ZIP64 records, entries whose
 * sizes live in a trailing data descriptor, and entries whose CRC lies.
 */
class ZipReaderTest {

    private val hello = "hello zip".encodeToByteArray()
    private val long = ByteArray(4000) { (it % 251).toByte() }

    @Test
    fun crc32_matches_the_standard_check_value() {
        assertEquals(0xCBF43926L, Crc32.of("123456789".encodeToByteArray()))
        assertEquals(0L, Crc32.of(ByteArray(0)))
    }

    @Test
    fun a_plain_stored_entry_still_reads() {
        val zip = ZipFixture.build(listOf(ZipFixture.Spec("a.txt", hello)))
        val r = ZipReader(zip)
        assertEquals(setOf("a.txt"), r.names)
        assertContentEquals(hello, r.read("a.txt"))
    }

    @Test
    fun a_deflated_entry_still_reads() {
        val zip = ZipFixture.build(listOf(ZipFixture.Spec("b.bin", long, deflate = true)))
        assertContentEquals(long, ZipReader(zip).read("b.bin"))
    }

    /* ─── ZIP64 ──────────────────────────────────────────────────────────── */

    @Test
    fun zip64_sizes_in_the_extra_field_are_used() {
        val zip = ZipFixture.build(listOf(ZipFixture.Spec("big.bin", long, zip64Extra = true)))
        assertContentEquals(long, ZipReader(zip).read("big.bin"))
    }

    @Test
    fun a_zip64_end_of_central_directory_is_followed() {
        val zip = ZipFixture.build(
            listOf(ZipFixture.Spec("a.txt", hello), ZipFixture.Spec("b.bin", long, deflate = true)),
            zip64Eocd = true,
        )
        val r = ZipReader(zip)
        assertEquals(listOf("a.txt", "b.bin"), r.names.toList())
        assertContentEquals(hello, r.read("a.txt"))
        assertContentEquals(long, r.read("b.bin"))
    }

    /* ─── Data descriptors ───────────────────────────────────────────────── */

    @Test
    fun a_stored_entry_sized_only_by_its_data_descriptor_reads() {
        val zip = ZipFixture.build(
            listOf(ZipFixture.Spec("s.txt", hello, dataDescriptor = true, hideSizes = true)),
        )
        assertContentEquals(hello, ZipReader(zip).read("s.txt"))
    }

    @Test
    fun a_deflated_entry_sized_only_by_its_data_descriptor_reads() {
        val zip = ZipFixture.build(
            listOf(ZipFixture.Spec("d.bin", long, deflate = true, dataDescriptor = true, hideSizes = true)),
        )
        assertContentEquals(long, ZipReader(zip).read("d.bin"))
    }

    @Test
    fun a_descriptor_entry_followed_by_another_entry_stops_at_its_own_data() {
        val zip = ZipFixture.build(
            listOf(
                ZipFixture.Spec("one.txt", hello, dataDescriptor = true, hideSizes = true),
                ZipFixture.Spec("two.txt", "second".encodeToByteArray()),
            ),
        )
        val r = ZipReader(zip)
        assertContentEquals(hello, r.read("one.txt"))
        assertContentEquals("second".encodeToByteArray(), r.read("two.txt"))
    }

    /* ─── CRC ────────────────────────────────────────────────────────────── */

    @Test
    fun a_good_entry_verifies() {
        val zip = ZipFixture.build(listOf(ZipFixture.Spec("a.txt", hello)))
        assertEquals(true, ZipReader(zip).verify("a.txt"))
        assertNull(ZipReader(zip).verify("missing.txt"))
    }

    @Test
    fun a_corrupt_entry_fails_verification_but_still_reads_by_default() {
        val zip = ZipFixture.build(listOf(ZipFixture.Spec("a.txt", hello, corruptData = true)))
        val r = ZipReader(zip)
        assertFalse(r.verify("a.txt")!!)
        assertTrue(r.read("a.txt") != null, "the lenient reader hands corrupt bytes back")
    }

    @Test
    fun a_strict_reader_refuses_a_corrupt_entry() {
        val zip = ZipFixture.build(listOf(ZipFixture.Spec("a.txt", hello, corruptData = true)))
        assertNull(ZipReader(zip, strictCrc = true).read("a.txt"))
    }

    @Test
    fun entry_metadata_is_exposed() {
        val zip = ZipFixture.build(listOf(ZipFixture.Spec("b.bin", long, deflate = true)))
        val e = ZipReader(zip).entry("b.bin")!!
        assertEquals("b.bin", e.name)
        assertEquals(long.size.toLong(), e.uncompressedSize)
        assertEquals(Crc32.of(long), e.crc32)
        assertEquals(8, e.method)
        assertTrue(e.compressedSize > 0)
    }
}

/** Builds ZIP archives in the exact shapes the reader has to survive. */
internal object ZipFixture {

    class Spec(
        val name: String,
        val data: ByteArray,
        /** Method 8 instead of 0. */
        val deflate: Boolean = false,
        /** Write a trailing data descriptor and set the local header's bit 3. */
        val dataDescriptor: Boolean = false,
        /** Leave the sizes and CRC zero in BOTH headers, as a streaming writer does. */
        val hideSizes: Boolean = false,
        /** Sentinel the central directory's sizes and put the truth in a ZIP64 extra field. */
        val zip64Extra: Boolean = false,
        /** Flip one payload byte after the CRC is computed. */
        val corruptData: Boolean = false,
    )

    fun build(specs: List<Spec>, zip64Eocd: Boolean = false): ByteArray {
        val out = Buf()
        class Placed(val spec: Spec, val offset: Int, val payload: ByteArray, val crc: Long)

        val placed = ArrayList<Placed>()
        for (s in specs) {
            var payload = if (s.deflate) Deflate.encode(s.data) else s.data
            val crc = Crc32.of(s.data)
            if (s.corruptData) {
                payload = payload.copyOf()
                payload[0] = (payload[0].toInt() xor 0xFF).toByte()
            }
            val offset = out.size
            val flags = if (s.dataDescriptor) 0x08 else 0
            val nb = s.name.encodeToByteArray()
            out.u32(0x04034b50); out.u16(if (s.zip64Extra) 45 else 20); out.u16(flags)
            out.u16(if (s.deflate) 8 else 0); out.u16(0); out.u16(0)
            if (s.hideSizes) { out.u32(0); out.u32(0); out.u32(0) }
            else { out.u32(crc); out.u32(payload.size.toLong()); out.u32(s.data.size.toLong()) }
            out.u16(nb.size); out.u16(0)
            out.raw(nb); out.raw(payload)
            if (s.dataDescriptor) {
                out.u32(0x08074b50); out.u32(crc); out.u32(payload.size.toLong()); out.u32(s.data.size.toLong())
            }
            placed.add(Placed(s, offset, payload, crc))
        }

        val cdStart = out.size
        for (p in placed) {
            val s = p.spec
            val nb = s.name.encodeToByteArray()
            val sentinel = 0xFFFFFFFFL
            val extra = Buf()
            if (s.zip64Extra) {
                extra.u16(0x0001); extra.u16(16)
                extra.u64(s.data.size.toLong()); extra.u64(p.payload.size.toLong())
            }
            out.u32(0x02014b50); out.u16(45); out.u16(if (s.zip64Extra) 45 else 20)
            out.u16(if (s.dataDescriptor) 0x08 else 0)
            out.u16(if (s.deflate) 8 else 0); out.u16(0); out.u16(0)
            if (s.hideSizes) { out.u32(0); out.u32(0); out.u32(0) }
            else if (s.zip64Extra) { out.u32(p.crc); out.u32(sentinel); out.u32(sentinel) }
            else { out.u32(p.crc); out.u32(p.payload.size.toLong()); out.u32(s.data.size.toLong()) }
            out.u16(nb.size); out.u16(extra.size); out.u16(0)
            out.u16(0); out.u16(0); out.u32(0)
            out.u32(p.offset.toLong())
            out.raw(nb); out.raw(extra.toByteArray())
        }
        val cdSize = out.size - cdStart

        if (zip64Eocd) {
            val z64 = out.size
            out.u32(0x06064b50); out.u64(44); out.u16(45); out.u16(45)
            out.u32(0); out.u32(0)
            out.u64(placed.size.toLong()); out.u64(placed.size.toLong())
            out.u64(cdSize.toLong()); out.u64(cdStart.toLong())
            out.u32(0x07064b50); out.u32(0); out.u64(z64.toLong()); out.u32(1)
            // The classic record keeps sentinels so a ZIP64-blind reader stops.
            out.u32(0x06054b50); out.u16(0); out.u16(0)
            out.u16(0xFFFF); out.u16(0xFFFF)
            out.u32(0xFFFFFFFFL); out.u32(0xFFFFFFFFL); out.u16(0)
        } else {
            out.u32(0x06054b50); out.u16(0); out.u16(0)
            out.u16(placed.size); out.u16(placed.size)
            out.u32(cdSize.toLong()); out.u32(cdStart.toLong()); out.u16(0)
        }
        return out.toByteArray()
    }

    private class Buf {
        private val b = ArrayList<Byte>()
        val size: Int get() = b.size
        fun raw(x: ByteArray) { for (v in x) b.add(v) }
        fun u16(v: Int) { b.add((v and 0xFF).toByte()); b.add(((v ushr 8) and 0xFF).toByte()) }
        fun u32(v: Long) { var s = 0; while (s < 32) { b.add(((v ushr s) and 0xFF).toByte()); s += 8 } }
        fun u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
        fun u64(v: Long) { var s = 0; while (s < 64) { b.add(((v ushr s) and 0xFF).toByte()); s += 8 } }
        fun u64(v: Int) = u64(v.toLong())
        fun toByteArray() = b.toByteArray()
    }
}
