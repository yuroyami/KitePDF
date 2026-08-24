package io.github.yuroyami.kitepdf.document

import io.github.yuroyami.kitepdf.cbz.CbzDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class KiteDocCbzTest {

    /** 2x1 24-bit BMP, fully valid, same bytes CbzFixtures builds. */
    private fun bmp(): ByteArray {
        val header = ByteArray(54)
        header[0] = 'B'.code.toByte(); header[1] = 'M'.code.toByte()
        fun le32(o: Int, v: Int) { var s = 0; var i = o; while (s < 32) { header[i++] = ((v ushr s) and 0xFF).toByte(); s += 8 } }
        fun le16(o: Int, v: Int) { header[o] = (v and 0xFF).toByte(); header[o + 1] = ((v ushr 8) and 0xFF).toByte() }
        le32(2, 62); le32(10, 54); le32(14, 40); le32(18, 2); le32(22, 1)
        le16(26, 1); le16(28, 24); le32(34, 8)
        return header + byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0)
    }

    @Test
    fun a_zip_of_images_is_cbz() {
        val cbz = storedZip(listOf("p1.bmp" to bmp(), "p2.bmp" to bmp()))
        assertEquals(KiteDocFormat.Cbz, KiteDoc.formatOf(cbz))
        assertIs<CbzDocument>(KiteDoc.open(cbz))
    }

    @Test
    fun junk_beside_images_still_reads_as_cbz() {
        val cbz = storedZip(
            listOf(
                "p1.bmp" to bmp(),
                "Thumbs.db" to ByteArray(4),
                "ComicInfo.xml" to "<ComicInfo/>".encodeToByteArray(),
            )
        )
        assertEquals(KiteDocFormat.Cbz, KiteDoc.formatOf(cbz))
    }

    @Test
    fun a_zip_with_a_non_image_file_is_not_cbz() {
        val zip = storedZip(listOf("p1.bmp" to bmp(), "readme.txt" to "hi".encodeToByteArray()))
        assertNull(KiteDoc.formatOf(zip))
    }

    @Test
    fun an_epub_with_images_inside_is_still_epub() {
        assertEquals(KiteDocFormat.Epub, KiteDoc.formatOf(sampleEpub()))
    }
}
