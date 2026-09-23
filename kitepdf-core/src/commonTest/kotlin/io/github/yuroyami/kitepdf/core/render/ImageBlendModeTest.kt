package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A canvas that does not blend images itself still blends them: the default image
 * paint with a blend mode composites through an isolated group (ISO 32000-1, 11.3.5, #113).
 */
class ImageBlendModeTest {

    /** A canvas that knows only the plain image paint and groups, as a canvas written before the blend mode was. */
    private class PlainCanvas : KiteCanvas {
        val calls = ArrayList<String>()
        override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: KiteMatrix) {}
        override fun endPage() {}
        override fun fillPath(path: KitePath, ctm: KiteMatrix, color: RgbColor, evenOdd: Boolean, alpha: Double, blendMode: KiteBlendMode) {}
        override fun strokePath(
            path: KitePath, ctm: KiteMatrix, color: RgbColor, lineWidth: Double, alpha: Double, blendMode: KiteBlendMode,
            dashArray: List<Double>?, dashPhase: Double, lineCap: Int, lineJoin: Int, miterLimit: Double,
        ) {}
        override fun drawGlyphs(
            glyphs: List<TextGlyph>, fontSize: Double, unitsPerEm: Int, hasOutlines: Boolean, fontSpec: FontSpec,
            textToDevice: KiteMatrix, color: RgbColor, alpha: Double, blendMode: KiteBlendMode,
        ) {}
        override fun pushClip(path: KitePath, ctm: KiteMatrix, evenOdd: Boolean) {}
        override fun popClip() {}
        override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double) {
            calls += "image alpha=$alpha"
        }
        override fun beginTransparencyGroup(
            bbox: KiteRectangle, ctm: KiteMatrix, isolated: Boolean, knockout: Boolean, alpha: Double, blendMode: KiteBlendMode,
        ) {
            calls += "group $bbox isolated=$isolated knockout=$knockout alpha=$alpha $blendMode"
        }
        override fun endTransparencyGroup() {
            calls += "end"
        }
    }

    private val image = KiteImageData(
        width = 2, height = 2, bitsPerComponent = 8, colorSpace = "test", kind = KiteImageData.Kind.RAW,
        encodedBytes = ByteArray(0), pixelBytes = ByteArray(4), resolvedColorSpace = KiteColorSpace.DeviceGray,
    )

    private val ctm = KiteMatrix(100.0, 0.0, 0.0, 100.0, 50.0, 50.0)

    @Test
    fun a_normal_image_paints_directly() {
        val canvas = PlainCanvas()
        canvas.drawImage(image, ctm, 0.5, KiteBlendMode.Normal)
        assertEquals(listOf("image alpha=0.5"), canvas.calls)
    }

    @Test
    fun a_blended_image_paints_alone_in_an_isolated_group_with_the_blend_mode() {
        val canvas = PlainCanvas()
        canvas.drawImage(image, ctm, 0.5, KiteBlendMode.Multiply)
        assertEquals(
            listOf(
                "group ${KiteRectangle(0.0, 0.0, 1.0, 1.0)} isolated=true knockout=false alpha=1.0 Multiply",
                "image alpha=0.5",
                "end",
            ),
            canvas.calls,
        )
    }
}
