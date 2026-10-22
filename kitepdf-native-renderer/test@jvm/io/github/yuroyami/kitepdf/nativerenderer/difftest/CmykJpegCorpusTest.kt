package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * The public sample of #470: a light blue figure in a DeviceCMYK JPEG with an Adobe YCCK
 * marker, on a page with a CMYK output intent. At 96 dpi, MuPDF 1.27.2 with ICC draws the
 * background as 255,253,250 and the figure as 184,211,231. PDFium draws 254,252,248 and
 * 168,203,229. Read as Photoshop ink, every pixel was black.
 */
class CmykJpegCorpusTest {

    @Test
    fun the_cmyk_jpeg_sample_draws_its_light_ink() {
        val file = Corpus.repoCorpus("pdf")?.resolve("public-verapdf-device-cmyk.pdf")
        assumeTrue("the public corpus is missing: $file", file != null && file.isFile)
        val image = AwtPdfRasterizer.renderToImage(PdfDocument.open(file!!.readBytes()).pages[0], scale = 96.0 / 72.0)
        assertNear(image, 85, 90, intArrayOf(255, 253, 250), "the background")
        assertNear(image, 180, 180, intArrayOf(184, 211, 231), "the figure")
    }

    /** Within 20 levels of MuPDF on each channel. PDFium is 16 away on the figure. */
    private fun assertNear(image: BufferedImage, x: Int, y: Int, want: IntArray, what: String) {
        val p = image.getRGB(x, y)
        val got = intArrayOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
        assertTrue((0..2).all { abs(got[it] - want[it]) <= 20 }, "$what at $x,$y: want ${want.toList()}, got ${got.toList()}")
    }
}
