package io.github.yuroyami.kitepdf.core.render

import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [ReaderTheme] / [ThemedCanvas]: content colours are remapped, paper follows the theme. */
class ThemedCanvasTest {

    private fun rect() = KitePath.Builder().apply { rectangle(0.0, 0.0, 10.0, 10.0) }.build()

    private fun fillColorThrough(theme: ReaderTheme, input: RgbColor): RgbColor {
        val rec = RecordingCanvas()
        theme.wrap(rec).fillPath(rect(), Matrix.IDENTITY, input, evenOdd = false)
        return (rec.calls.single() as RecordingCanvas.Call.Fill).color
    }

    @Test
    fun dark_maps_white_paper_to_dark() {
        val c = fillColorThrough(ReaderTheme.Dark, RgbColor.WHITE)
        assertTrue(c.r < 0.2 && c.g < 0.2 && c.b < 0.2, "white -> dark, got $c")
    }

    @Test
    fun dark_maps_black_ink_to_light() {
        val c = fillColorThrough(ReaderTheme.Dark, RgbColor.BLACK)
        assertTrue(c.r > 0.8 && c.g > 0.8 && c.b > 0.8, "black -> light, got $c")
    }

    @Test
    fun dark_keeps_a_saturated_hue_instead_of_flipping_it() {
        // A pure blue link should stay blue-ish (b highest), not become yellow.
        val c = fillColorThrough(ReaderTheme.Dark, RgbColor(0.0, 0.0, 1.0))
        assertTrue(c.b >= c.r && c.b >= c.g, "blue stays blue-dominant, got $c")
    }

    @Test
    fun stroke_colour_is_also_themed() {
        val rec = RecordingCanvas()
        ReaderTheme.Dark.wrap(rec).strokePath(rect(), Matrix.IDENTITY, RgbColor.WHITE, lineWidth = 1.0)
        val stroke = rec.calls.single() as RecordingCanvas.Call.Stroke
        assertTrue(stroke.color.r < 0.2, "white stroke themed dark, got ${stroke.color}")
    }

    @Test
    fun sepia_paper_is_warm() {
        val bg = ReaderTheme.Sepia.background
        assertTrue(bg.r > bg.b, "sepia paper warm (r>b): $bg")
    }

    @Test
    fun light_wrap_is_identity_passthrough() {
        val rec = RecordingCanvas()
        assertSame(rec, ReaderTheme.Light.wrap(rec), "Light must not allocate a wrapper")
    }
}
