package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An image that the shared decoders refuse draws as the placeholder, on this canvas as on every
 * other, so a placeholder marks a decoder gap of KitePDF itself (#184). KiteImage refuses
 * arithmetic coding, so this JPEG is such an image.
 */
class EncodedImagePlaceholderTest {

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

    @Test
    fun a_jpeg_the_shared_decoder_refuses_draws_as_the_placeholder() {
        val image = KiteImageData.fromEncodedImage(arithmeticJpeg) ?: error("no image")
        assertEquals(KiteImageData.Kind.JPEG, image.kind, "KiteImage must refuse arithmetic coding for this test to mean anything")
        val bitmap = ImageBitmap(64, 48)
        CanvasDrawScope().drawOnTestUiThread(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(64f, 48f)) {
            ComposeCanvas(this, TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr))
                .drawImage(image, KiteMatrix(64.0, 0.0, 0.0, -48.0, 0.0, 48.0), 1.0)
        }
        // A pixel inside the placeholder's grey fill, off its border and off both diagonals. The
        // JPEG's gradient has red near 0.1 and green near 0.5 there, so a decode would show.
        val inside = bitmap.toPixelMap()[6, 24]
        assertEquals(0xE0 / 255f, inside.red, 0.01f)
        assertEquals(0xE0 / 255f, inside.green, 0.01f)
        assertEquals(0xE0 / 255f, inside.blue, 0.01f)
    }
}
