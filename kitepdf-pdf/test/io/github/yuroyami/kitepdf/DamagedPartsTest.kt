package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteFormatException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Damaged parts of a file cost only themselves (#800, #801, #802, #811). */
class DamagedPartsTest {

    /** A one-page file with [catalogExtra] in its catalog and [extra] as objects 5 onwards, and [trailerExtra]. */
    private fun pdf(catalogExtra: String = "", extra: List<String> = emptyList(), trailerExtra: String = "", tail: String = ""): ByteArray {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R $catalogExtra >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 200] /Contents 4 0 R /Resources << /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> >> >>",
            "<< /Length 44 >>\nstream\nBT /F1 24 Tf 20 100 Td (Original text) Tj ET\nendstream",
        ) + extra
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, body -> sb.length.also { sb.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R $trailerExtra >>\nstartxref\n$xref\n%%EOF\n")
        sb.append(tail)
        return sb.toString().encodeToByteArray()
    }

    @Test
    fun metadataThatDoesNotDecodeReadsAsNone() {
        val doc = PdfDocument.open(pdf("/Metadata 5 0 R", listOf("<< /Type /Metadata /Subtype /XML /Filter /FlateDecode /Length 4 >>\nstream\nnope\nendstream")))
        assertNull(doc.xmpMetadataXml)
        doc.metadata
    }

    @Test
    fun anAttachmentThatDoesNotDecodeIsEmpty() {
        val doc = PdfDocument.open(
            pdf(
                "/Names << /EmbeddedFiles << /Names [(a.txt) 5 0 R] >> >>",
                listOf("<< /Type /Filespec /F (a.txt) /EF << /F 6 0 R >> >>", "<< /Type /EmbeddedFile /Filter /Nope /Length 4 >>\nstream\nnope\nendstream"),
            ),
        )
        assertContentEquals(ByteArray(0), doc.attachments.single().bytes)
    }

    private fun encrypted(length: Int) = pdf(
        extra = listOf("<< /Filter /Standard /V 2 /R 3 /Length $length /O <${"00".repeat(32)}> /U <${"00".repeat(32)}> /P -4 >>"),
        trailerExtra = "/Encrypt 5 0 R /ID [<00> <00>]",
    )

    @Test
    fun anEncryptionKeyLengthOutOfRangeIsAFormatError() {
        // /Length 5 made an empty key and threw ArithmeticException out of open (#800). Read as bytes
        // it is 40 bits, so the file is a plain wrong password here.
        assertFailsWith<io.github.yuroyami.kitepdf.core.KiteWrongPasswordException> { PdfDocument.open(encrypted(5)) }
        for (length in listOf(-8, 200, 44)) assertFailsWith<KiteFormatException>("/Length $length") { PdfDocument.open(encrypted(length)) }
    }

    @Test
    fun repairKeepsTheCompleteCopyOverALaterOneThatWasCutOff() {
        // An update cut off inside its new copy of the page, and a damaged startxref (#811).
        // Every byte of this file is ASCII, so its text and its bytes line up.
        val bytes = pdf(tail = "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 200] /Contents [4 0 R").decodeToString()
            .replace(Regex("startxref\n\\d+"), "startxref\n999999").encodeToByteArray()
        val doc = PdfDocument.open(bytes)
        assertEquals(1, doc.pageCount)
        assertTrue("Original text" in doc.pages[0].extractText(), doc.pages[0].extractText())
    }

    @Test
    fun anImageDecodeThatIsNotFiniteReadsWithItsDefaultRange() {
        // A real of 310 digits is Infinity, and the sample map rounded a NaN, failing the page (#805).
        val huge = "1" + "0".repeat(310) + ".0"
        val image = "<< /Type /XObject /Subtype /Image /Width 2 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8 /Decode [0 $huge] /Length 2 >>\nstream\nAB\nendstream"
        val bytes = pdf(extra = listOf(image)).decodeToString()
            .replace("/Contents 4 0 R /Resources << ", "/Contents 6 0 R /Resources << /XObject << /Im1 5 0 R >> ")
            .let { text ->
                // Append the content stream as object 6 by hand, after the image.
                val content = "q 100 0 0 100 50 50 cm /Im1 Do Q 0 0 1 rg 0 0 20 20 re f"
                text.replace("xref\n0 6", "6 0 obj\n<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\nxref\n0 6")
            }.encodeToByteArray()
        val doc = PdfDocument.open(bytes)
        val canvas = io.github.yuroyami.kitepdf.core.render.KiteRasterCanvas(200, 200)
        doc.pages[0].renderTo(canvas, io.github.yuroyami.kitepdf.core.render.KiteMatrix.IDENTITY)
    }
}
