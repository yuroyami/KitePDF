package io.github.yuroyami.kitepdf.core.zip

import io.github.yuroyami.kitepdf.core.KiteByteSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A reader on a [KiteByteSource] reads what a reader on the whole array reads, and fetches
 * only the central directory and the entries asked for.
 */
class ZipByteSourceTest {

    private val hello = "hello zip".encodeToByteArray()
    private val long = ByteArray(40_000) { (it % 251).toByte() }

    /** Counts what the reader fetches, and fails reads that touch [failing], as a damaged disk would. */
    private class CountingSource(private val bytes: ByteArray, private val failing: LongRange? = null) :
        KiteByteSource {
        var fetched = 0L
        override val size: Long get() = bytes.size.toLong()

        override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(length.toLong(), bytes.size - position).toInt()
            if (failing != null && failing.first <= position + n - 1 && position <= failing.last) throw IllegalStateException("disk gone at $position")
            bytes.copyInto(into, offset, position.toInt(), position.toInt() + n)
            fetched += n
            return n
        }
    }

    @Test
    fun every_shape_reads_as_it_does_from_the_array() {
        val zip = ZipFixture.build(
            listOf(
                ZipFixture.Spec("s.bin", long),
                ZipFixture.Spec("d.bin", long, deflate = true),
                ZipFixture.Spec("sd.bin", long, dataDescriptor = true, hideSizes = true),
                ZipFixture.Spec("dd.bin", long, deflate = true, dataDescriptor = true, hideSizes = true),
                ZipFixture.Spec("z.bin", hello, zip64Extra = true),
                ZipFixture.Spec("bad.bin", hello, corruptData = true),
            ),
            zip64Eocd = true,
        )
        val fromArray = ZipReader(zip)
        val fromSource = ZipReader(CountingSource(zip))
        assertEquals(fromArray.names, fromSource.names)
        for (name in fromArray.names) {
            assertContentEquals(fromArray.read(name), fromSource.read(name), name)
            assertContentEquals(fromArray.readPrefix(name, 100), fromSource.readPrefix(name, 100), name)
            assertEquals(fromArray.verify(name), fromSource.verify(name), name)
        }
    }

    /** The case the source exists for: a large entry beside the one being read is never fetched. */
    @Test
    fun reading_one_entry_does_not_fetch_another() {
        val big = ByteArray(8 * 1024 * 1024) { (it * 31).toByte() }
        val zip = ZipFixture.build(listOf(ZipFixture.Spec("big.bin", big), ZipFixture.Spec("a.txt", hello)))
        val source = CountingSource(zip)
        val reader = ZipReader(source)
        assertContentEquals(hello, reader.read("a.txt"))
        assertContentEquals(big.copyOf(64), reader.readPrefix("big.bin", 64))
        assertTrue(source.fetched < 128 * 1024, "fetched ${source.fetched} bytes of ${zip.size}")
    }

    /** An entry sized only by its data descriptor ends at the next header, so the entry after it is not fetched. */
    @Test
    fun a_descriptor_entry_does_not_fetch_the_entries_after_it() {
        val big = ByteArray(8 * 1024 * 1024) { (it * 31).toByte() }
        val zip = ZipFixture.build(
            listOf(
                ZipFixture.Spec("d.bin", long, deflate = true, dataDescriptor = true, hideSizes = true),
                ZipFixture.Spec("s.bin", long, dataDescriptor = true, hideSizes = true),
                ZipFixture.Spec("big.bin", big),
            ),
        )
        val source = CountingSource(zip)
        val reader = ZipReader(source)
        assertContentEquals(long, reader.read("d.bin"))
        assertContentEquals(long, reader.read("s.bin"))
        assertContentEquals(long.copyOf(100), reader.readPrefix("d.bin", 100))
        assertTrue(source.fetched < 512 * 1024, "fetched ${source.fetched} bytes of ${zip.size}")
    }

    /**
     * Two directory records that name one local header, as a damaged or crafted archive has, must
     * not end the first entry where it starts. A bare binary search can land on either record and
     * bound the entry to zero bytes, and the array read the entry before the bound existed.
     */
    @Test
    fun two_records_that_share_a_local_header_still_read() {
        val zip = ZipFixture.build(
            listOf(
                ZipFixture.Spec("a.bin", long, deflate = true, dataDescriptor = true, hideSizes = true),
                ZipFixture.Spec("b.bin", hello, deflate = true),
            ),
        )
        // Point the second central record at the first local header.
        val eocd = zip.size - 22
        val cdStart = u32(zip, eocd + 16)
        val second = cdStart + 46 + "a.bin".length
        for (i in 0 until 4) zip[second + 42 + i] = 0
        for (reader in listOf(ZipReader(zip), ZipReader(CountingSource(zip)))) {
            assertContentEquals(long, reader.read("a.bin"))
            assertContentEquals(long.copyOf(100), reader.readPrefix("a.bin", 100))
        }
    }

    private fun u32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    @Test
    fun a_source_that_fails_makes_an_entry_unreadable_and_never_throws() {
        val zip = ZipFixture.build(listOf(ZipFixture.Spec("a.txt", hello), ZipFixture.Spec("b.bin", long)))
        // Part of the second entry's payload is unreadable; the first entry and the directory are not.
        val damaged = ZipReader(CountingSource(zip, failing = 30_000L..30_010L))
        assertEquals(setOf("a.txt", "b.bin"), damaged.names)
        assertContentEquals(hello, damaged.read("a.txt"))
        assertNull(damaged.read("b.bin"))
        assertNull(damaged.verify("b.bin"))

        // A source that fails everywhere opens as an empty archive.
        assertEquals(emptySet(), ZipReader(CountingSource(zip, failing = 0L..Long.MAX_VALUE)).names)
    }
}
