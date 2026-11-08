package io.github.yuroyami.kitepdf

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** Poppler draws a flattened form with its fields and stamps where they were (#949). */
class FlattenPopplerTest {

    private fun box(color: String) = TestPdf.stream("$color rg 0 0 40 20 re f", "/Type /XObject /Subtype /Form /BBox [0 0 40 20]")

    /**
     * A page with a filled text field, a checked check box, a stamp, a hidden stamp and a stamp
     * that does not print, each drawn with a coloured box.
     */
    private fun form(): ByteArray = TestPdf.build(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R] /DA (/Helv 10 Tf 0 g) /DR << /Font << /Helv 13 0 R >> >> >> >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 6 0 R /Annots [4 0 R 5 0 R 7 0 R 8 0 R 9 0 R] >>",
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (Name) /Rect [10 150 110 170] /F 4 /DA (/Helv 10 Tf 0 g) /V (Ada) /AP << /N 10 0 R >> >>",
            "<< /Type /Annot /Subtype /Widget /FT /Btn /T (Agree) /Rect [10 110 50 130] /F 4 /V /Yes /AS /Yes /AP << /N << /Yes 11 0 R /Off 12 0 R >> >> >>",
            TestPdf.stream("0 0 1 rg 150 150 20 20 re f"),
            "<< /Type /Annot /Subtype /Stamp /Rect [10 60 50 80] /F 4 /AP << /N 14 0 R >> >>",
            "<< /Type /Annot /Subtype /Stamp /Rect [60 60 100 80] /F 6 /AP << /N 14 0 R >> >>",
            "<< /Type /Annot /Subtype /Stamp /Rect [110 60 150 80] /F 0 /AP << /N 14 0 R >> >>",
            box("0 1 0"),
            box("1 0 0"),
            box("1 1 1"),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            box("1 0 1"),
        ),
    )

    @Test
    fun popplerDrawsTheFlattenedPage() {
        assumeTrue("Poppler's pdftoppm is not installed", runCatching { ProcessBuilder("pdftoppm", "-v").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false))
        val doc = PdfDocument.open(form())
        val file = File.createTempFile("flat", ".pdf").apply { writeBytes(doc.edit().apply { flatten() }.saveRewritten()); deleteOnExit() }
        val prefix = File(file.parentFile, file.nameWithoutExtension)
        // Annotations hidden: what Poppler draws is the page content alone.
        val p = ProcessBuilder("pdftoppm", "-r", "72", "-png", "-hide-annotations", "-singlefile", file.path, prefix.path).redirectErrorStream(true).start()
        p.waitFor(60, TimeUnit.SECONDS)
        val image = ImageIO.read(File(prefix.path + ".png"))
        // Poppler's rows run down from the top of the 200 point page.
        fun at(x: Int, y: Int) = image.getRGB(x, 199 - y) and 0xFFFFFF
        assertEquals(0xFF0000, at(30, 120), "the checked state")
        assertEquals(0xFF00FF, at(30, 70), "the printing stamp")
        assertEquals(0xFFFFFF, at(80, 70), "the hidden stamp is left out")
        assertEquals(0x00FF00, at(105, 168), "the text field's background")
    }
}
