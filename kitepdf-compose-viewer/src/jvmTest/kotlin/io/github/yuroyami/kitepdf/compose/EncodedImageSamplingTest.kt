package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An encoded image that the core cannot decode goes to the platform decoder, which shrinks a
 * JPEG inside the decoder when the image draws small, and the canvas averages only the rest
 * (#381). KiteImage refuses arithmetic coding, so this JPEG takes that path.
 */
class EncodedImageSamplingTest {

    /** 64 by 48, arithmetic coded by `cjpeg -arithmetic -quality 90`: red grows to the right, green downwards, blue 128. */
    private val arithmeticJpeg = (
        "ffd8ffe000104a46494600010100000100010000ffdb0043000302020302020303030304030304050805050404050a070706080c0a0c0c0b0a0b0b0d" +
        "0e12100d0e110e0b0b1016101113141515150c0f171816141812141514ffdb00430103040405040509050509140d0b0d141414141414141414141414" +
        "1414141414141414141414141414141414141414141414141414141414141414141414141414ffc90011080030004003012200021101031101ffcc00" +
        "0a0010100501101105ffda000c03010002110311003f00ff00ec33a5bbc682e89363d1364f45f60f645e922977e9f016b7d5b552c9be20db7168bcd2" +
        "b91431e8545dcefcc751ee6805f527a59d21919cd1161b8e54fcf740cb19257b013494a0b9d6c2122f8598a4860c4b7e86f9deecef1030ff00a9b2d8" +
        "3bfe3558071f031cf1861d8e2d948b4ee14de61010c076e6b54750ddf6ddf89c9a2b05bdc4b34a02b1c5be0b63f9a88dd5bb2549500858fe43bb2eef" +
        "52b3c105d1423af2b091635af4b770d2e636d9a5b3cc71276d3716247832a194590ccc14fe558b5de2bc6feb10099e582a8ba584351f0234ecdeb122" +
        "a1467ced33367649d96b2360855dcb892bb5582065cc408090f6d69f7ba35ce54ae61a14de428d4bed34ffd9"
    ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun render(w: Int, h: Int): PixelMap {
        val image = KiteImageData.fromEncodedImage(arithmeticJpeg) ?: error("no image")
        assertEquals(KiteImageData.Kind.JPEG, image.kind, "KiteImage must refuse arithmetic coding for this test to mean anything")
        val bitmap = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
            ComposeCanvas(this, TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr))
                .drawImage(image, KiteMatrix(w.toDouble(), 0.0, 0.0, -h.toDouble(), 0.0, h.toDouble()), 1.0)
        }
        return bitmap.toPixelMap()
    }

    @Test
    fun a_jpeg_drawn_at_a_quarter_or_an_eighth_keeps_its_gradient() {
        for (f in listOf(4, 8)) {
            val w = 64 / f
            val h = 48 / f
            val map = render(w, h)
            var worst = 0f
            for (y in 0 until h) for (x in 0 until w) {
                // The mean of the block that the pixel covers.
                val red = (f * x + (f - 1) / 2f) / 64f
                val green = (f * y + (f - 1) / 2f) / 48f
                worst = maxOf(worst, abs(map[x, y].red - red), abs(map[x, y].green - green), abs(map[x, y].blue - 128 / 255f))
            }
            assertTrue(worst < 0.04f, "drawn at 1/$f: worst difference $worst")
        }
    }

    @Test
    fun the_canvas_draws_the_platform_decoders_own_reduced_pixels() {
        // Drawn at a quarter, the bitmap is the decoder's 1/4 decode, pixel for pixel. A full decode
        // averaged down by the canvas differs from it, because a reduced IDCT is not a box filter.
        val map = render(16, 12)
        val (reduced, done) = decodeSampled(arithmeticJpeg, 4) ?: error("decodeSampled returned null")
        assertEquals(4, done)
        val expected = reduced.toPixelMap()
        for (y in 0 until 12) for (x in 0 until 16) {
            val a = map[x, y]
            val b = expected[x, y]
            val d = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))
            assertTrue(d <= 1.5f / 255, "pixel ($x, $y): drawn $a, decoded $b")
        }
    }
}
