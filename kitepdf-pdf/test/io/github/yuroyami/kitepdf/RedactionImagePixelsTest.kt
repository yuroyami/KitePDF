package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRawApi
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.writer.RedactImages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A redaction with [RedactImages.Pixels] blanks the pixels of an image under a region and keeps the rest (#926). */
@OptIn(KiteRawApi::class)
class RedactionImagePixelsTest {

    private val width = 600
    private val height = 800

    /** A grey sample that is never 0, different in each place. */
    private fun sample(row: Int, col: Int): Byte = ((row * 7 + col * 3) % 251 + 1).toByte()

    private val samples = ByteArray(width * height) { sample(it / width, it % width) }

    /** One page that draws the scan over the whole of it, a band of text above, and the scan's object last. */
    private fun scanned(sMask: Boolean = false, otherPage: Boolean = false): ByteArray {
        val kids = if (otherPage) "[3 0 R 8 0 R]" else "[3 0 R]"
        val objects = mutableListOf<Any>(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids $kids /Count ${if (otherPage) 2 else 1} >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Contents 4 0 R /Resources << /XObject << /Im1 5 0 R >> /Font << /F1 6 0 R >> >> >>",
            TestPdf.stream("q 600 0 0 800 0 0 cm /Im1 Do Q BT /F1 12 Tf 20 20 Td (Page footer) Tj ET"),
            TestPdf.Stream(
                "/Type /XObject /Subtype /Image /Width $width /Height $height /ColorSpace /DeviceGray /BitsPerComponent 8" +
                    (if (sMask) " /SMask 7 0 R" else ""),
                samples,
            ),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            TestPdf.Stream("/Type /XObject /Subtype /Image /Width $width /Height $height /ColorSpace /DeviceGray /BitsPerComponent 8", ByteArray(width * height) { -1 }),
        )
        if (otherPage) objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Contents 4 0 R /Resources << /XObject << /Im1 5 0 R >> /Font << /F1 6 0 R >> >> >>"
        return TestPdf.build(objects)
    }

    private val band = KiteRectangle(0.0, 300.0, 600.0, 400.0)

    /** The image XObjects the page draws, by name. */
    private fun images(doc: PdfDocument, page: Int = 0): Map<String, PdfStream> {
        val xobjects = doc.pages[page].resources?.getDict("XObject", doc) ?: return emptyMap()
        return xobjects.keys.mapNotNull { k -> (xobjects[k]?.resolve(doc) as? PdfStream)?.let { k to it } }.toMap()
    }

    @Test
    fun the_band_goes_black_and_the_rest_stays_byte_for_byte() {
        val doc = PdfDocument.open(scanned())
        val out = doc.edit().apply { redactRegion(doc.pages[0], band, RedactImages.Pixels) }.saveRewritten()
        val after = PdfDocument.open(out)
        val image = images(after).values.single()
        assertEquals(width.toLong(), image.dict.getInt("Width"))
        assertEquals(height.toLong(), image.dict.getInt("Height"))
        assertEquals(PdfName("FlateDecode"), image.dict["Filter"])
        val decoded = FilterChain.decode(image)
        assertEquals(samples.size, decoded.size)
        // The band covers y 300 to 400 of 800, which is rows 400 to 499 from the top.
        for (row in 0 until height) for (col in 0 until width) {
            val got = decoded[row * width + col]
            if (row in 400..499) assertEquals(0, got.toInt(), "row $row col $col")
            else if (got != samples[row * width + col]) throw AssertionError("row $row col $col changed")
        }
        assertTrue("Page footer" in after.pages[0].extractText())
        assertEquals(1, imageObjects(after).size, "no copy of the original scan remains")
    }

    @Test
    fun the_soft_mask_loses_the_same_pixels() {
        val doc = PdfDocument.open(scanned(sMask = true))
        val out = doc.edit().apply { redactRegion(doc.pages[0], band, RedactImages.Pixels) }.saveRewritten()
        val after = PdfDocument.open(out)
        val image = images(after).values.single()
        val mask = assertNotNull(image.dict["SMask"]?.resolve(after) as? PdfStream)
        val alpha = FilterChain.decode(mask)
        for (row in 0 until height) {
            val expected = if (row in 400..499) 0 else 255
            assertEquals(expected, alpha[row * width + 7].toInt() and 0xFF, "row $row")
        }
        assertEquals(2, imageObjects(after).size, "the copy and its mask, and neither original")
    }

    @Test
    fun the_default_still_takes_the_image_off_the_page() {
        val doc = PdfDocument.open(scanned())
        val out = doc.edit().apply { redactRegion(doc.pages[0], band) }.saveRewritten()
        val after = PdfDocument.open(out)
        assertEquals(emptyMap(), images(after))
        assertEquals(0, imageObjects(after).size)
    }

    @Test
    fun another_page_keeps_drawing_the_original() {
        val doc = PdfDocument.open(scanned(otherPage = true))
        val out = doc.edit().apply { redactRegion(doc.pages[0], band, RedactImages.Pixels) }.saveRewritten()
        val after = PdfDocument.open(out)
        val redacted = FilterChain.decode(images(after, 0).values.single())
        assertEquals(0, redacted[450 * width].toInt())
        val other = FilterChain.decode(images(after, 1).values.single())
        assertTrue(other.contentEquals(samples), "page 2 draws the scan as it was")
    }

    @Test
    fun an_image_that_misses_every_region_is_left_alone() {
        val doc = PdfDocument.open(scanned())
        val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(700.0, 900.0, 720.0, 920.0), RedactImages.Pixels) }.saveRewritten()
        val after = PdfDocument.open(out)
        val (name, image) = images(after).entries.single()
        assertEquals("Im1", name)
        assertNull(image.dict["Filter"])
        assertFalse(FilterChain.decode(image).any { it.toInt() == 0 })
    }

    @Test
    fun a_one_bit_image_keeps_its_packing() {
        // 10 pixels a row pack into 2 bytes, 6 bits of padding (ISO 32000-1, 8.9.3). All white.
        val pdf = TestPdf.build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 40] /Contents 4 0 R /Resources << /XObject << /Im1 5 0 R >> >> >>",
                TestPdf.stream("q 100 0 0 40 0 0 cm /Im1 Do Q"),
                TestPdf.Stream("/Type /XObject /Subtype /Image /Width 10 /Height 4 /ColorSpace /DeviceGray /BitsPerComponent 1", ByteArray(8) { -1 }),
            ),
        )
        val doc = PdfDocument.open(pdf)
        // x 25 to 45 is pixels 2 to 4; y 20 to 30 is row 1.
        val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(25.0, 20.0, 45.0, 30.0), RedactImages.Pixels) }.saveRewritten()
        val decoded = FilterChain.decode(images(PdfDocument.open(out)).values.single())
        assertEquals(listOf(0xFF, 0xFF, 0b11000111, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF), decoded.map { it.toInt() and 0xFF })
    }

    /** The image objects the file holds that something still reaches. */
    @OptIn(KiteRawApi::class)
    private fun imageObjects(doc: PdfDocument): List<Long> = doc.xref.keys.filter { n ->
        (runCatching { (doc as io.github.yuroyami.kitepdf.core.parser.IndirectResolver).resolve(PdfReference(n, 0)) }.getOrNull() as? PdfStream)?.dict?.getName("Subtype") == "Image"
    }
}
