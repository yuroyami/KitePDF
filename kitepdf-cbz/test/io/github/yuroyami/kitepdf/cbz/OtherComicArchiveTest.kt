package io.github.yuroyami.kitepdf.cbz

import io.github.yuroyami.archivekodec.archive.tar.TarWriter
import io.github.yuroyami.kitepdf.core.KiteByteSource
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A comic in a TAR, RAR or 7z archive opens through ArchiveKodec, as a CBZ does through the ZIP reader. */
class OtherComicArchiveTest {

    private fun cbt(): ByteArray = TarWriter.write(
        listOf(
            TarWriter.FileSpec("page10.bmp", CbzFixtures.bmp2x1()),
            TarWriter.FileSpec("page2.bmp", CbzFixtures.bmp2x1()),
            TarWriter.FileSpec("ComicInfo.xml", "<ComicInfo><Title>Tar Tale</Title></ComicInfo>".encodeToByteArray()),
        ),
    )

    private class ArraySource(private val bytes: ByteArray) : KiteByteSource {
        override val size: Long get() = bytes.size.toLong()

        override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(length.toLong(), bytes.size - position).toInt()
            bytes.copyInto(into, offset, position.toInt(), position.toInt() + n)
            return n
        }
    }

    @Test
    fun a_tar_comic_opens_from_bytes_and_from_a_source() {
        val bytes = cbt()
        assertEquals("TAR", CbzDocument.archiveKindOf(bytes))
        for (doc in listOf(CbzDocument.open(bytes), CbzDocument.open(ArraySource(bytes)))) {
            assertEquals(listOf("page2.bmp", "page10.bmp"), doc.entryNames)
            assertEquals("Tar Tale", doc.metadata.title)
            val calls = RecordingCanvas().also { doc.pages[1].renderTo(it) }.calls
            assertEquals(1, calls.filterIsInstance<RecordingCanvas.Call.Image>().size)
        }
    }

    @Test
    fun a_tar_without_images_says_so() {
        val bytes = TarWriter.write(listOf(TarWriter.FileSpec("notes.txt", "no pages".encodeToByteArray())))
        val e = assertFailsWith<KiteFormatException> { CbzDocument.open(bytes) }
        assertEquals("TAR archive holds no image entries", e.message)
    }

    @Test
    fun a_damaged_rar_or_7z_names_its_kind() {
        val rar5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00) + ByteArray(200)
        val sevenZip = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C) + ByteArray(200)
        for ((bytes, kind) in listOf(rar5 to "RAR", sevenZip to "7z")) {
            val e = assertFailsWith<KiteFormatException> { CbzDocument.open(bytes) }
            assertTrue(e.message!!.startsWith("not a readable $kind archive"), e.message)
            assertNull(CbzDocument.openOrNull(bytes))
        }
    }
}
