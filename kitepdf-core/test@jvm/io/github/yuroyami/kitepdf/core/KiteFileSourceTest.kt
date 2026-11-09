package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.zip.ZipReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** A [KiteFileSource] reads a file at any offset, as the readers on a source need (#906). */
class KiteFileSourceTest {

    @Test
    fun it_reads_the_bytes_at_each_offset_and_ends_with_the_file() {
        val bytes = ByteArray(100_000) { (it * 31 + 7).toByte() }
        val file = File.createTempFile("kite-source", ".bin").apply { deleteOnExit(); writeBytes(bytes) }
        KiteFileSource(file).use { source ->
            assertEquals(bytes.size.toLong(), source.size)
            for (at in listOf(0, 1, 16_383, 16_384, 99_990)) {
                val into = ByteArray(32)
                val n = source.read(at.toLong(), into, 4, 20)
                val want = minOf(20, bytes.size - at)
                assertEquals(want, n, "at $at")
                assertContentEquals(bytes.copyOfRange(at, at + want), into.copyOfRange(4, 4 + want), "at $at")
            }
            assertEquals(-1, source.read(bytes.size.toLong(), ByteArray(4), 0, 4))
        }
    }

    @Test
    fun a_zip_reader_on_it_reads_every_entry() {
        val out = ByteArrayOutputStream()
        val entries = (1..20).associate { "e$it.txt" to "entry $it ".repeat(it * 300).encodeToByteArray() }
        ZipOutputStream(out).use { zip ->
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name)); zip.write(body); zip.closeEntry()
            }
        }
        val file = File.createTempFile("kite-source", ".zip").apply { deleteOnExit(); writeBytes(out.toByteArray()) }
        KiteFileSource(file).use { source ->
            val zip = ZipReader(source)
            assertEquals(entries.keys, zip.names)
            for ((name, body) in entries) assertContentEquals(body, zip.read(name), name)
        }
    }
}
