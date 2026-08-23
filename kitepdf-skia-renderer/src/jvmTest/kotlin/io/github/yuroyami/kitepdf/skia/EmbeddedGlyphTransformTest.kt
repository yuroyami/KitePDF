package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RgbColor
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface

class EmbeddedGlyphTransformTest {

    @Test
    fun embedded_outline_scales_then_offsets_then_enters_device_space() {
        val surface = Surface.makeRasterN32Premul(128, 128)
        val image = try {
            surface.canvas.clear(Color.WHITE)
            val outline = KitePath.Builder().apply {
                rectangle(0.0, 0.0, 1_000.0, 1_000.0)
            }.build()
            val glyph = TextGlyph(
                byteOffset = 0,
                byteCount = 1,
                gid = 1,
                text = "A",
                advanceWidth = 1_000.0,
                outline = outline,
                isWordSpace = false,
                xOffset = 100.0,
                yOffset = 50.0,
            )

            SkiaCanvas(surface.canvas).drawGlyphs(
                glyphs = listOf(glyph),
                fontSize = 20.0,
                unitsPerEm = 1_000,
                hasOutlines = true,
                fontSpec = FontSpec.SansSerif,
                textToDevice = KiteMatrix(2.0, 0.0, 0.0, 2.0, 30.0, 40.0),
                color = RgbColor(0.0, 0.0, 0.0),
                alpha = 1.0,
                blendMode = KiteBlendMode.Normal,
            )
            surface.makeImageSnapshot()
        } finally {
            surface.close()
        }

        val png = try {
            val data = image.encodeToData(EncodedImageFormat.PNG)
                ?: error("Skia failed to encode glyph transform fixture")
            try {
                data.bytes
            } finally {
                data.close()
            }
        } finally {
            image.close()
        }
        val raster = ImageIO.read(ByteArrayInputStream(png))
        val ink = buildList {
            for (y in 0 until raster.height) for (x in 0 until raster.width) {
                if ((raster.getRGB(x, y) and 0xFFFFFF) != 0xFFFFFF) add(x to y)
            }
        }

        assertTrue(ink.isNotEmpty(), "embedded outline did not paint")
        // 1000 font units × 20/1000 × device scale 2 = 40 px.
        // Offset (100,50) becomes (4,2) px before the device translation (30,40).
        assertTrue(ink.minOf { it.first } in 33..35, "unexpected left edge: ${ink.minOf { it.first }}")
        assertTrue(ink.maxOf { it.first } in 73..75, "unexpected right edge: ${ink.maxOf { it.first }}")
        assertTrue(ink.minOf { it.second } in 41..43, "unexpected top edge: ${ink.minOf { it.second }}")
        assertTrue(ink.maxOf { it.second } in 81..83, "unexpected bottom edge: ${ink.maxOf { it.second }}")
    }
}
