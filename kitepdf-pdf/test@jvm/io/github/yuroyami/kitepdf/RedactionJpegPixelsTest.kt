package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRawApi
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.writer.RedactImages
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A JPEG scan redacted with [RedactImages.Pixels] becomes a Flate image of the same size with the band black (#926). */
@OptIn(KiteRawApi::class)
class RedactionJpegPixelsTest {

    @Test
    fun aJpegScanKeepsEverythingButTheBand() {
        val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 48) for (x in 0 until 64) image.setRGB(x, y, 0xC08040)
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
        val pdf = TestPdf.build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 64 48] /Contents 4 0 R /Resources << /XObject << /Im1 5 0 R >> >> >>",
                TestPdf.stream("q 64 0 0 48 0 0 cm /Im1 Do Q"),
                TestPdf.Stream("/Type /XObject /Subtype /Image /Width 64 /Height 48 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode", jpeg),
            ),
        )
        val doc = PdfDocument.open(pdf)
        // y 16 to 32 of 48 is rows 16 to 31 from the top.
        val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(0.0, 16.0, 64.0, 32.0), RedactImages.Pixels) }.saveRewritten()
        val after = PdfDocument.open(out)
        val xobjects = after.pages[0].resources!!.getDict("XObject", after)!!
        val copy = xobjects[xobjects.keys.single()]!!.resolve(after) as PdfStream
        assertEquals(PdfName("FlateDecode"), copy.dict["Filter"])
        val samples = FilterChain.decode(copy)
        assertEquals(64 * 48 * 3, samples.size)
        for (row in 0 until 48) {
            val red = samples[(row * 64 + 10) * 3].toInt() and 0xFF
            if (row in 16..31) assertEquals(0, red, "row $row") else assertTrue(red > 0xA0, "row $row keeps its colour: $red")
        }
        assertTrue(!out.decodeToString().contains("DCTDecode"), "the JPEG is gone")
    }
}
