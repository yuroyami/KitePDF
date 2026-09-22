package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.SoftMask
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Pixel regressions for the AWT soft-mask defects in #78, #79 and #80. */
class AwtSoftMaskTest {
    @Test
    fun luminosity_mask_preserves_previously_painted_backdrop() {
        val pdf = pdf(
            "0 0 1 rg 10 10 60 60 re f /GS gs 1 0 0 rg 0 0 200 200 re f",
            "Luminosity", "1 g 40 90 80 60 re f",
        )
        for (image in renderSurfaces(pdf)) {
            assertPixel(image, 40, 160, Color.BLUE)
            assertPixel(image, 80, 80, Color.RED)
            assertPixel(image, 160, 80, Color.WHITE)
            assertPixel(image, 40, 40, Color.WHITE)
        }
    }

    @Test
    fun alpha_mask_uses_coverage_without_painting_its_blue_colour() {
        val pdf = pdf(
            "/GS gs 1 0 0 rg 20 20 160 160 re f",
            "Alpha", "0 0 1 rg 0 0 100 200 re f",
        )
        for (image in renderSurfaces(pdf)) {
            assertPixel(image, 50, 100, Color.RED)
            assertPixel(image, 150, 100, Color.WHITE)
            assertPixel(image, 10, 100, Color.WHITE)
        }
    }

    @Test
    fun luminosity_mask_has_identical_partial_coverage_on_rgb_and_argb() {
        val pdf = pdf(
            "/GS gs 1 0 0 rg 20 20 160 160 re f",
            "Luminosity", ".25 g 0 0 100 200 re f 1 g 100 0 100 200 re f",
        )
        val images = renderSurfaces(pdf)
        for (image in images) {
            assertPixel(image, 50, 100, Color(255, 192, 192))
            assertPixel(image, 150, 100, Color.RED)
            assertPixel(image, 10, 100, Color.WHITE)
        }
        assertEquals(images[0].getRGB(50, 100), images[1].getRGB(50, 100))
    }

    @Test
    fun host_transform_and_clip_align_content_and_mask() {
        val image = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, 100, 100)
            graphics.translate(11, 7)
            graphics.scale(2.0, 3.0)
            graphics.clip = Rectangle(1, 2, 20, 20)
            val originalTransform = graphics.transform
            val originalClip = graphics.clip.bounds
            val canvas = AwtCanvas(graphics)
            val maskCtm = KiteMatrix.translation(3.0, 4.0)
            canvas.applySoftMask(SoftMask.Kind.Alpha, KiteRectangle(0.0, 0.0, 10.0, 10.0), maskCtm,
                render = { fill(canvas, 0.0, 0.0, 30.0, 30.0, RgbColor(1.0, 0.0, 0.0)) },
                renderMask = { fill(it, 0.0, 0.0, 5.0, 10.0, RgbColor(0.0, 0.0, 1.0), maskCtm) },
            )
            assertPixel(image, 20, 25, Color.RED)
            assertPixel(image, 28, 25, Color.WHITE)
            assertPixel(image, 14, 25, Color.WHITE)
            assertPixel(image, 20, 15, Color.WHITE)
            assertEquals(originalTransform, graphics.transform)
            assertEquals(originalClip, graphics.clip.bounds)
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun nested_masks_intersect_without_erasing_outer_backdrop() {
        val image = BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.BLUE
            graphics.fillRect(0, 0, 40, 40)
            val canvas = AwtCanvas(graphics)
            val box = KiteRectangle(0.0, 0.0, 40.0, 40.0)
            canvas.applySoftMask(SoftMask.Kind.Alpha, box, KiteMatrix.IDENTITY,
                render = {
                    canvas.applySoftMask(SoftMask.Kind.Luminosity, box, KiteMatrix.IDENTITY,
                        render = { fill(canvas, 0.0, 0.0, 40.0, 40.0, RgbColor(1.0, 0.0, 0.0)) },
                        renderMask = { fill(it, 0.0, 0.0, 20.0, 40.0, RgbColor(1.0, 1.0, 1.0)) },
                    )
                },
                renderMask = { fill(it, 0.0, 0.0, 40.0, 20.0, RgbColor(0.0, 0.0, 0.0)) },
            )
            assertPixel(image, 10, 10, Color.RED)
            assertPixel(image, 30, 10, Color.BLUE)
            assertPixel(image, 10, 30, Color.BLUE)
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun failed_content_or_mask_restores_canvas_for_following_paints() {
        for (failInMask in listOf(false, true)) {
            val image = BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                val canvas = AwtCanvas(graphics)
                canvas.pushClip(rect(0.0, 0.0, 30.0, 40.0), KiteMatrix.IDENTITY, false)
                assertFailsWith<IllegalStateException> {
                    canvas.applySoftMask(SoftMask.Kind.Alpha, KiteRectangle(0.0, 0.0, 40.0, 40.0), KiteMatrix.IDENTITY,
                        render = {
                            canvas.pushClip(rect(0.0, 0.0, 5.0, 5.0), KiteMatrix.IDENTITY, false)
                            if (!failInMask) error("content failed")
                            canvas.popClip()
                        },
                        renderMask = { error("mask failed") },
                    )
                }
                fill(canvas, 0.0, 0.0, 40.0, 40.0, RgbColor(1.0, 0.0, 0.0))
                assertPixel(image, 20, 20, Color.RED)
                assertEquals(0, image.getRGB(35, 20))
                canvas.popClip()
                fill(canvas, 30.0, 0.0, 10.0, 40.0, RgbColor(0.0, 0.0, 1.0))
                assertPixel(image, 35, 20, Color.BLUE)
            } finally {
                graphics.dispose()
            }
        }
    }

    @Test
    fun mixed_blend_modes_see_backdrop_through_full_and_partial_masks() {
        for (type in listOf(BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB)) {
            for (maskAlpha in listOf(1.0, 0.5)) {
                val image = BufferedImage(80, 40, type)
                val graphics = image.createGraphics()
                try {
                    graphics.color = Color(128, 128, 255)
                    graphics.fillRect(0, 0, 80, 40)
                    val canvas = AwtCanvas(graphics)
                    canvas.applySoftMask(SoftMask.Kind.Alpha, KiteRectangle(0.0, 0.0, 80.0, 40.0), KiteMatrix.IDENTITY,
                        render = {
                            canvas.fillPath(rect(0.0, 0.0, 40.0, 40.0), KiteMatrix.IDENTITY, RgbColor(1.0, 0.0, 0.0), false, 1.0, KiteBlendMode.Multiply)
                            canvas.fillPath(rect(40.0, 0.0, 40.0, 40.0), KiteMatrix.IDENTITY, RgbColor(0.0, 1.0, 0.0), false, 1.0, KiteBlendMode.Screen)
                        },
                        renderMask = {
                            it.fillPath(rect(0.0, 0.0, 80.0, 40.0), KiteMatrix.IDENTITY, RgbColor(0.0, 0.0, 0.0), false, maskAlpha)
                        },
                    )
                    assertPixel(image, 20, 20, if (maskAlpha == 1.0) Color(128, 0, 0) else Color(128, 64, 127))
                    assertPixel(image, 60, 20, if (maskAlpha == 1.0) Color(128, 255, 255) else Color(128, 192, 255))
                } finally {
                    graphics.dispose()
                }
            }
        }
    }

    @Test
    fun partial_mask_mixes_premultiplied_colours_on_translucent_backdrop() {
        for (type in listOf(BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE)) {
            val image = BufferedImage(40, 40, type)
            val graphics = image.createGraphics()
            try {
                graphics.color = Color(0, 0, 255, 128)
                graphics.fillRect(0, 0, 40, 40)
                val canvas = AwtCanvas(graphics)
                canvas.applySoftMask(SoftMask.Kind.Alpha, KiteRectangle(0.0, 0.0, 40.0, 40.0), KiteMatrix.IDENTITY,
                    render = { fill(canvas, 0.0, 0.0, 40.0, 40.0, RgbColor(1.0, 0.0, 0.0)) },
                    renderMask = { it.fillPath(rect(0.0, 0.0, 40.0, 40.0), KiteMatrix.IDENTITY, RgbColor(0.0, 0.0, 0.0), false, 0.5) },
                )
                assertPixel(image, 20, 20, Color(170, 0, 85, 192))
            } finally {
                graphics.dispose()
            }
        }
    }

    @Test
    fun clipped_backdrop_transfer_keeps_indices_across_tile_boundaries() {
        val image = BufferedImage(2100, 10, BufferedImage.TYPE_3BYTE_BGR)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.BLUE
            graphics.fillRect(0, 0, 2100, 10)
            graphics.clip = Rectangle(1000, 0, 1050, 10)
            val canvas = AwtCanvas(graphics)
            canvas.applySoftMask(SoftMask.Kind.Alpha, KiteRectangle(0.0, 0.0, 2100.0, 10.0), KiteMatrix.IDENTITY,
                render = { fill(canvas, 0.0, 0.0, 2100.0, 10.0, RgbColor(1.0, 0.0, 0.0)) },
                renderMask = { fill(it, 1010.0, 0.0, 1030.0, 10.0, RgbColor(0.0, 0.0, 0.0)) },
            )
            assertPixel(image, 1005, 5, Color.BLUE)
            assertPixel(image, 1020, 5, Color.RED)
            assertPixel(image, 2020, 5, Color.RED)
            assertPixel(image, 2030, 5, Color.RED)
            assertPixel(image, 2045, 5, Color.BLUE)
            assertPixel(image, 2060, 5, Color.BLUE)
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun untouched_high_precision_destination_samples_are_preserved() {
        for (coverage in listOf(0.0, 1.0)) {
            val image = BufferedImage(4, 4, BufferedImage.TYPE_USHORT_GRAY)
            for (y in 0 until 4) for (x in 0 until 4) image.raster.setSample(x, y, 0, 12345)
            val graphics = image.createGraphics()
            try {
                val canvas = AwtCanvas(graphics)
                canvas.applySoftMask(SoftMask.Kind.Alpha, KiteRectangle(0.0, 0.0, 4.0, 4.0), KiteMatrix.IDENTITY,
                    render = { if (coverage == 0.0) fill(canvas, 0.0, 0.0, 4.0, 4.0, RgbColor(1.0, 0.0, 0.0)) },
                    renderMask = { it.fillPath(rect(0.0, 0.0, 4.0, 4.0), KiteMatrix.IDENTITY, RgbColor(0.0, 0.0, 0.0), false, coverage) },
                )
                assertEquals(12345, image.raster.getSample(2, 2, 0))
            } finally {
                graphics.dispose()
            }
        }
    }

    private fun fill(
        canvas: KiteCanvas, x: Double, y: Double, width: Double, height: Double,
        color: RgbColor, ctm: KiteMatrix = KiteMatrix.IDENTITY,
    ) = canvas.fillPath(rect(x, y, width, height), ctm, color, false)

    private fun rect(x: Double, y: Double, width: Double, height: Double): KitePath =
        KitePath.Builder().apply { rectangle(x, y, width, height) }.build()

    private fun assertPixel(image: BufferedImage, x: Int, y: Int, color: Color) =
        assertEquals(color.rgb, image.getRGB(x, y), "pixel ($x,$y), image type ${image.type}")

    private fun renderSurfaces(bytes: ByteArray): List<BufferedImage> {
        val page = KitePDF.open(bytes).pages[0]
        return listOf(BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_RGB).map { type ->
            val image = BufferedImage(200, 200, type)
            val graphics = image.createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, 200, 200)
                // Deliberately leave the host's user clip unset, as image graphics do.
                page.renderTo(AwtCanvas(graphics), KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, 200.0))
            } finally {
                graphics.dispose()
            }
            image
        }
    }

    private fun pdf(content: String, kind: String, mask: String): ByteArray {
        val output = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun write(text: String) = output.write(text.encodeToByteArray())
        fun obj(body: String) {
            offsets.add(output.size())
            write("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        fun stream(dictionary: String, body: String) =
            obj("<< $dictionary /Length ${body.encodeToByteArray().size} >>\nstream\n$body\nendstream")
        write("%PDF-1.4\n")
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>")
        obj("<< /Type /Page /Parent 2 0 R /Resources << /ExtGState << /GS 5 0 R >> >> /Contents 4 0 R >>")
        stream("", content)
        obj("<< /Type /ExtGState /SMask << /S /$kind /G 6 0 R /BC [0 0 0] >> >>")
        stream("/Type /XObject /Subtype /Form /BBox [0 0 200 200] /Resources << >> /Group << /S /Transparency /CS /DeviceRGB >>", mask)
        val xref = output.size()
        write("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) write("${offset.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return output.toByteArray()
    }
}
