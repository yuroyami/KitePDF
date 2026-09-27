package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Compose canvas applies a clip once when it is pushed. It used to apply every active clip
 * again for each paint, one recursion level per clip, so deep clip stacks overflowed the stack
 * and every paint paid for every clip (#335).
 */
class ComposeClipStackTest {

    private fun rect(x: Double, y: Double, w: Double, h: Double) = KitePath.Builder().apply { rectangle(x, y, w, h) }.build()

    private fun paint(body: ComposeCanvas.() -> Unit): ImageBitmap {
        val bitmap = ImageBitmap(100, 100)
        val density = Density(1f)
        val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(100f, 100f)) {
            drawRect(Color.White, size = size)
            val canvas = ComposeCanvas(this, measurer)
            canvas.beginPage(100.0, 100.0, KiteMatrix.IDENTITY)
            canvas.body()
            canvas.endPage()
        }
        return bitmap
    }

    private fun ComposeCanvas.fillRed(path: KitePath) =
        fillPath(path, KiteMatrix.IDENTITY, RgbColor(1.0, 0.0, 0.0), evenOdd = false, alpha = 1.0, blendMode = KiteBlendMode.Normal)

    @Test
    fun thirty_thousand_clips_do_not_overflow_the_stack() {
        val bitmap = paint {
            repeat(30_000) { pushClip(rect(0.0, 0.0, 100.0, 100.0), KiteMatrix.IDENTITY, evenOdd = false) }
            fillRed(rect(0.0, 0.0, 100.0, 100.0))
            repeat(30_000) { popClip() }
        }
        assertEquals(Color.Red, bitmap.toPixelMap()[50, 50])
    }

    @Test
    fun a_popped_clip_stops_clipping() {
        val bitmap = paint {
            pushClip(rect(0.0, 0.0, 50.0, 100.0), KiteMatrix.IDENTITY, evenOdd = false)
            popClip()
            fillRed(rect(0.0, 0.0, 100.0, 100.0))
        }
        assertEquals(Color.Red, bitmap.toPixelMap()[75, 50], "the clip was popped before the fill")
    }

    @Test
    fun nested_clips_intersect() {
        val bitmap = paint {
            pushClip(rect(0.0, 0.0, 60.0, 100.0), KiteMatrix.IDENTITY, evenOdd = false)
            pushClip(rect(40.0, 0.0, 60.0, 100.0), KiteMatrix.IDENTITY, evenOdd = false)
            fillRed(rect(0.0, 0.0, 100.0, 100.0))
            popClip()
            popClip()
        }
        val pixels = bitmap.toPixelMap()
        assertEquals(Color.White, pixels[20, 50])
        assertEquals(Color.Red, pixels[50, 50])
        assertEquals(Color.White, pixels[80, 50])
    }

    /** A clip opened inside a group and left open is closed with the group, not after it. */
    @Test
    fun a_group_closes_the_clips_left_open_inside_it() {
        val bitmap = paint {
            beginTransparencyGroup(
                io.github.yuroyami.kitepdf.core.KiteRectangle(0.0, 0.0, 100.0, 100.0), KiteMatrix.IDENTITY,
                isolated = true, knockout = false, alpha = 1.0, blendMode = KiteBlendMode.Normal,
            )
            pushClip(rect(0.0, 0.0, 10.0, 10.0), KiteMatrix.IDENTITY, evenOdd = false)
            endTransparencyGroup()
            fillRed(rect(0.0, 0.0, 100.0, 100.0))
        }
        assertEquals(Color.Red, bitmap.toPixelMap()[50, 50], "the clip inside the group did not outlive it")
    }
}
