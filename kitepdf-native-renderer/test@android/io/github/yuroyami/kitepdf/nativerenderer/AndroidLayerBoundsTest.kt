package io.github.yuroyami.kitepdf.nativerenderer

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A transparency group and a soft-masked paint take a layer of their own box on the Android
 * canvas, not of the whole clip (#824), as `SkiaLayerBoundsTest` checks on Skia. Each small paint
 * cleared, painted and composited layers of the whole page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29, 35])
class AndroidLayerBoundsTest {

    /** A Letter page of 500 squares of 8 points on a grid, each drawn by [square]. */
    private fun page(square: String, resources: String, form: String): ByteArray {
        val content = StringBuilder()
        for (i in 0 until 500) {
            val x = 20 + (i % 25) * 22
            val y = 20 + (i / 25) * 36
            content.append("q 1 0 0 1 $x $y cm $square Q\n")
        }
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << $resources >> /Contents 4 0 R >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            form,
        )
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { i, body ->
            offsets += sb.length
            sb.append("${i + 1} 0 obj\n$body\nendobj\n")
        }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** A form of the box of one square, a transparency group in [groupCs] that paints [content]. */
    private fun squareForm(content: String, groupCs: String = "") =
        "<< /Type /XObject /Subtype /Form /BBox [0 0 8 8] /Group << /S /Transparency $groupCs >> " +
            "/Length ${content.length} >>\nstream\n$content\nendstream"

    private fun check(name: String, pdf: ByteArray, colour: Int) {
        val s = 300.0 / 72.0
        // The first render of a run also loads Robolectric's native graphics and warms the JIT, which
        // took a CI runner past the budget on its own, so only a second render is timed.
        renderOnBitmap(pdf, s)
        val start = System.nanoTime()
        val image = renderOnBitmap(pdf, s)
        val ms = (System.nanoTime() - start) / 1_000_000
        println("AndroidLayerBoundsTest $name: $ms ms")
        // Measured here at 300 dpi under Robolectric: 44 to 100 s for the masked squares and 7 s
        // for the groups before, and under 0.4 s for each after.
        assertTrue(ms < 5_000, "$name: $ms ms for 500 squares")
        // The centre of the first square, and the gap beside it.
        assertEquals(colour, image.getPixel((24 * s).toInt(), image.height - (24 * s).toInt()) and 0xFFFFFF, "$name: a square")
        assertEquals(0xFFFFFF, image.getPixel((35 * s).toInt(), image.height - (24 * s).toInt()) and 0xFFFFFF, "$name: the gap")
    }

    @Test
    fun small_masked_paints_take_small_layers() {
        check(
            "masked",
            page(
                "/GS0 gs 0 0 1 rg 0 0 8 8 re f", "/ExtGState << /GS0 << /SMask << /S /Luminosity /G 5 0 R >> >> >>",
                squareForm("1 g 0 0 8 8 re f", "/CS /DeviceGray"),
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
                squareForm("0 0 1 rg 0 0 8 8 re f"),
            ),
            0x7F7FFF,
        )
    }
}
