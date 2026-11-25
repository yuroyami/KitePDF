package io.github.yuroyami.kitepdf.nativerenderer

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * A transparency group takes a layer of its own box on CoreGraphics, not of the whole clip
 * (#824), as `SkiaLayerBoundsTest` checks on Skia and `AndroidLayerBoundsTest` on Android.
 * A soft-masked paint renders its mask in the mask's box already.
 */
class CoreGraphicsLayerBoundsTest {

    /** A Letter page of 500 squares of 8 points on a grid, each drawn by [square]. */
    private fun page(square: String, resources: String, form: String): ByteArray {
        val content = StringBuilder()
        for (i in 0 until 500) {
            val x = 20 + (i % 25) * 22
            val y = 20 + (i / 25) * 36
            content.append("q 1 0 0 1 $x $y cm $square Q\n")
        }
        return cgTestPage(content.toString(), resources, listOf(form), width = 612, height = 792)
    }

    /** A form of the box of one square, a transparency group in [groupCs] that paints [content]. */
    private fun squareForm(content: String, groupCs: String = "") =
        "<< /Type /XObject /Subtype /Form /BBox [0 0 8 8] /Group << /S /Transparency $groupCs >> " +
            "/Length ${content.length} >>\nstream\n$content\nendstream"

    private fun check(name: String, pdf: ByteArray, colour: List<Int>) {
        val s = 300.0 / 72.0
        cgRender(pdf, s)
        lateinit var pixels: CgPixels
        val time = measureTime { pixels = cgRender(pdf, s) }
        println("CoreGraphicsLayerBoundsTest $name: $time")
        assertTrue(time.inWholeMilliseconds < 5_000, "$name: $time for 500 squares")
        // The centre of the first square, and the gap beside it.
        pixels.assertPixel((24 * s).toInt(), (24 * s).toInt(), colour)
        pixels.assertPixel((35 * s).toInt(), (24 * s).toInt(), listOf(255, 255, 255))
    }

    @Test
    fun small_groups_take_small_layers() {
        check(
            "groups",
            page("/GS1 gs /Fm0 Do", "/ExtGState << /GS1 << /ca 0.5 >> >> /XObject << /Fm0 5 0 R >>", squareForm("0 0 1 rg 0 0 8 8 re f")),
            listOf(127, 127, 255),
        )
    }

    @Test
    fun small_masked_paints_take_small_layers() {
        check(
            "masked",
            page(
                "/GS0 gs 0 0 1 rg 0 0 8 8 re f", "/ExtGState << /GS0 << /SMask << /S /Luminosity /G 5 0 R >> >> >>",
                squareForm("1 g 0 0 8 8 re f", "/CS /DeviceGray"),
            ),
            listOf(0, 0, 255),
        )
    }
}
