package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.core.font.FontFamily
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.BlendMode
import io.github.yuroyami.kitepdf.core.render.Matrix
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemFontMetricScaleTest {

    @Test
    fun metric_scale_matches_document_advance_width() {
        val glyphs = glyphs("word", advanceWidth = 450.0)

        assertEquals(
            expected = 0.45f,
            actual = systemFontMetricScale(glyphs, renderedSize = 20.0, measuredWidthPx = 80.0),
            absoluteTolerance = 0.0001f,
        )
    }

    @Test
    fun invalid_dimensions_leave_the_run_unscaled() {
        val glyphs = glyphs("word", advanceWidth = 450.0)

        assertEquals(1f, systemFontMetricScale(glyphs, renderedSize = 0.0, measuredWidthPx = 80.0))
        assertEquals(1f, systemFontMetricScale(glyphs, renderedSize = 20.0, measuredWidthPx = 0.0))
        assertEquals(1f, systemFontMetricScale(emptyList(), renderedSize = 20.0, measuredWidthPx = 80.0))
    }

    @Test
    fun serif_fallback_respects_assigned_width_at_reader_sizes() {
        for (fontSize in listOf(21.0, 29.0)) {
            val targetWidth = fontSize * 2.0
            val secondOrigin = 10.0 + targetWidth + 10.0
            val bitmap = ImageBitmap(320, 100)
            val density = Density(1f)
            val textMeasurer = TextMeasurer(
                createFontFamilyResolver(),
                density,
                LayoutDirection.Ltr,
            )

            CanvasDrawScope().draw(
                density = density,
                layoutDirection = LayoutDirection.Ltr,
                canvas = Canvas(bitmap),
                size = Size(320f, 100f),
            ) {
                drawRect(Color.White)
                ComposeCanvas(this, textMeasurer).drawGlyphs(
                    glyphs = glyphs("MMMMMMMM", advanceWidth = 250.0),
                    fontSize = fontSize,
                    unitsPerEm = 1_000,
                    hasOutlines = false,
                    fontSpec = FontSpec(FontFamily.Serif, bold = false, italic = false),
                    textToDevice = Matrix(1.0, 0.0, 0.0, 1.0, 10.0, 60.0),
                    color = RgbColor(0.0, 0.0, 0.0),
                    alpha = 1.0,
                    blendMode = BlendMode.Normal,
                )
                ComposeCanvas(this, textMeasurer).drawGlyphs(
                    glyphs = glyphs("IIII", advanceWidth = 250.0),
                    fontSize = fontSize,
                    unitsPerEm = 1_000,
                    hasOutlines = false,
                    fontSpec = FontSpec(FontFamily.Serif, bold = false, italic = false),
                    textToDevice = Matrix(1.0, 0.0, 0.0, 1.0, secondOrigin, 60.0),
                    color = RgbColor(0.85, 0.0, 0.0),
                    alpha = 1.0,
                    blendMode = BlendMode.Normal,
                )
            }

            val raster = bitmap.asSkiaBitmap()
            val blackInkX = buildList {
                for (y in 0 until raster.height) {
                    for (x in 0 until raster.width) {
                        val color = raster.getColor(x, y)
                        if (red(color) < 80 && green(color) < 80 && blue(color) < 80) add(x)
                    }
                }
            }
            val redInkX = buildList {
                for (y in 0 until raster.height) {
                    for (x in 0 until raster.width) {
                        val color = raster.getColor(x, y)
                        if (red(color) > 150 && green(color) < 100 && blue(color) < 100) add(x)
                    }
                }
            }
            assertTrue(blackInkX.isNotEmpty(), "first serif run did not paint at ${fontSize.toInt()} px")
            assertTrue(redInkX.isNotEmpty(), "second serif run did not paint at ${fontSize.toInt()} px")
            val paintedWidth = blackInkX.max() - blackInkX.min() + 1
            assertTrue(
                paintedWidth <= targetWidth.toInt() + 3,
                "serif fallback exceeded its assigned width at ${fontSize.toInt()} px: " +
                    "painted=$paintedWidth target=$targetWidth",
            )
            assertTrue(
                paintedWidth >= (targetWidth * 0.65).toInt(),
                "serif fallback collapsed at ${fontSize.toInt()} px: " +
                    "painted=$paintedWidth target=$targetWidth",
            )
            assertTrue(
                blackInkX.max() + 2 < redInkX.min(),
                "adjacent serif runs collide at ${fontSize.toInt()} px: " +
                    "firstRight=${blackInkX.max()} secondLeft=${redInkX.min()}",
            )
        }
    }

    private fun red(color: Int): Int = (color shr 16) and 0xFF
    private fun green(color: Int): Int = (color shr 8) and 0xFF
    private fun blue(color: Int): Int = color and 0xFF

    private fun glyphs(text: String, advanceWidth: Double): List<TextGlyph> =
        text.mapIndexed { index, character ->
            TextGlyph(
                byteOffset = index,
                byteCount = 1,
                gid = -1,
                text = character.toString(),
                advanceWidth = advanceWidth,
                outline = null,
                isWordSpace = character == ' ',
            )
        }
}
