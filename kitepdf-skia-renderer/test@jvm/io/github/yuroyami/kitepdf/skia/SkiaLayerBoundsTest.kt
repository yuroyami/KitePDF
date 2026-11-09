package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.KitePDF
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A transparency group and a soft-masked paint take a layer of their own box, not of the whole
 * clip (#824): 500 small masked squares on a Letter page took 76 s at 300 dpi on Skia, and 500
 * small groups 9 s, against a tenth of a second on AWT.
 */
class SkiaLayerBoundsTest {

    /** A Letter page of 500 squares of 8 points on a grid, each drawn by [square]. */
    private fun page(square: String, resources: String, extra: List<Pair<String, String>>): ByteArray {
        val content = StringBuilder()
        for (i in 0 until 500) {
            val x = 20 + (i % 25) * 22
            val y = 20 + (i / 25) * 36
            content.append("q 1 0 0 1 $x $y cm $square Q\n")
        }
        val objects = listOf<Any>(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << $resources >> /Contents 4 0 R >>",
            "" to content.toString(),
        ) + extra
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        for ((i, o) in objects.withIndex()) {
            offsets += sb.length
            sb.append("${i + 1} 0 obj\n")
            if (o is Pair<*, *>) {
                val data = o.second as String
                sb.append("<< ${o.first} /Length ${data.length} >>\nstream\n").append(data).append("\nendstream")
            } else {
                sb.append(o)
            }
            sb.append("\nendobj\n")
        }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    private fun render(pdf: ByteArray): Pair<Long, java.awt.image.BufferedImage> {
        val page = KitePDF.open(pdf).pages[0]
        val start = System.nanoTime()
        val png = PdfPageRasterizer.encodeToPng(page, 300.0 / 72.0)
        val ms = (System.nanoTime() - start) / 1_000_000
        return ms to javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(png))
    }

    private fun check(name: String, pdf: ByteArray, colour: Int) {
        val (ms, image) = render(pdf)
        // Measured here: 7.6 s for the groups before and 0.45 s after, 0.3 s for the masked squares.
        assertTrue(ms < 3_000, "$name: $ms ms for 500 squares")
        val s = 300.0 / 72.0
        // The centre of the first square, and the gap beside it.
        assertEquals(colour, image.getRGB((24 * s).toInt(), image.height - (24 * s).toInt()) and 0xFFFFFF, "$name: a square")
        assertEquals(0xFFFFFF, image.getRGB((35 * s).toInt(), image.height - (24 * s).toInt()) and 0xFFFFFF, "$name: the gap")
    }

    @Test
    fun small_masked_paints_take_small_layers() {
        check(
            "masked",
            page(
                "/GS0 gs 0 0 1 rg 0 0 8 8 re f", "/ExtGState << /GS0 << /SMask << /S /Luminosity /G 5 0 R >> >> >>",
                listOf("/Type /XObject /Subtype /Form /BBox [0 0 8 8] /Group << /S /Transparency /CS /DeviceGray >>" to "1 g 0 0 8 8 re f"),
            ),
            0x0000FF,
        )
    }

    @Test
    fun small_groups_take_small_layers() {
        check(
            "groups",
            page(
                "/GS1 gs /Fm0 Do", "/ExtGState << /GS1 << /ca 0.5 >> >> /XObject << /Fm0 5 0 R >>",
                listOf("/Type /XObject /Subtype /Form /BBox [0 0 8 8] /Group << /S /Transparency >>" to "0 0 1 rg 0 0 8 8 re f"),
            ),
            0x7F7FFF,
        )
    }
}
