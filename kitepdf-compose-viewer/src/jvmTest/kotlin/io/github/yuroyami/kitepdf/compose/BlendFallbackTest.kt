package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A platform that cannot paint Multiply, as Android before API 29, paints it with Modulate, so a
 * highlight keeps the text under it visible. It painted as Normal, and a highlight hid its words
 * (#412). A mode that the platform paints keeps its own twin.
 */
class BlendFallbackTest {

    /** The modes Android before API 29 paints: its Porter-Duff modes. */
    private val porterDuff = setOf(BlendMode.SrcOver, BlendMode.Screen, BlendMode.Overlay, BlendMode.Darken, BlendMode.Lighten, BlendMode.Modulate)

    @Test
    fun multiply_becomes_modulate_where_the_platform_cannot_paint_it() {
        assertEquals(BlendMode.Modulate, composeBlendMode(KiteBlendMode.Multiply) { it in porterDuff })
        assertEquals(BlendMode.Screen, composeBlendMode(KiteBlendMode.Screen) { it in porterDuff })
        // A mode with no stand-in stays as it is, and the platform paints it as it can.
        assertEquals(BlendMode.ColorBurn, composeBlendMode(KiteBlendMode.ColorBurn) { it in porterDuff })
        // Where every mode paints, Multiply is Multiply.
        assertEquals(BlendMode.Multiply, composeBlendMode(KiteBlendMode.Multiply) { true })
    }

    /** A 20 x 10 page: white, black on its left half, and a yellow highlight over all of it in [mode]. */
    private fun highlight(mode: BlendMode): List<Color> {
        val bitmap = ImageBitmap(20, 10)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(20f, 10f)) {
            drawRect(Color.White)
            drawRect(Color.Black, size = Size(10f, 10f))
            drawRect(Color.Yellow, topLeft = Offset.Zero, size = size, blendMode = mode)
        }
        val pixels = bitmap.toPixelMap()
        return listOf(pixels[5, 5], pixels[15, 5])
    }

    @Test
    fun an_opaque_modulate_over_an_opaque_page_paints_as_multiply() {
        val multiply = highlight(BlendMode.Multiply)
        assertEquals(listOf(Color.Black, Color.Yellow), multiply, "the text stays black and the paper turns yellow")
        assertEquals(multiply, highlight(BlendMode.Modulate))
    }
}
