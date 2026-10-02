package io.github.yuroyami.kitepdf.core.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [ReaderTheme] / [ThemedCanvas]: content colours are remapped, paper follows the theme. */
class ThemedCanvasTest {

    private fun rect() = KitePath.Builder().apply { rectangle(0.0, 0.0, 10.0, 10.0) }.build()

    private fun fillColorThrough(theme: ReaderTheme, input: RgbColor): RgbColor {
        val rec = RecordingCanvas()
        theme.wrap(rec).fillPath(rect(), KiteMatrix.IDENTITY, input, evenOdd = false)
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
        ReaderTheme.Dark.wrap(rec).strokePath(rect(), KiteMatrix.IDENTITY, RgbColor.WHITE, lineWidth = 1.0)
        val stroke = rec.calls.single() as RecordingCanvas.Call.Stroke
        assertTrue(stroke.color.r < 0.2, "white stroke themed dark, got ${stroke.color}")
    }

    @Test
    fun sepia_paper_is_warm() {
        val bg = ReaderTheme.Sepia.background
        assertTrue(bg.r > bg.b, "sepia paper warm (r>b): $bg")
    }

    @Test
    fun sepia_keeps_light_fills_light_and_ink_brown() {
        // A white or light grey box behind dark text must not turn dark (#253).
        assertEquals(ReaderTheme.Sepia.background, fillColorThrough(ReaderTheme.Sepia, RgbColor.WHITE))
        val light = fillColorThrough(ReaderTheme.Sepia, RgbColor.gray(0.93))
        assertTrue(light.r > 0.8 && light.g > 0.75 && light.b > 0.65, "a light fill stays light, got $light")
        val ink = fillColorThrough(ReaderTheme.Sepia, RgbColor.BLACK)
        assertEquals(RgbColor(0.30, 0.24, 0.18), ink)
    }

    /** WCAG relative luminance of an sRGB colour. */
    private fun luminance(c: RgbColor): Double {
        fun lin(v: Double) = if (v <= 0.04045) v / 12.92 else kotlin.math.exp(2.4 * kotlin.math.ln((v + 0.055) / 1.055))
        return 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b)
    }

    /** The WCAG contrast ratio of [a] against [b]. */
    private fun contrast(a: RgbColor, b: RgbColor): Double {
        val (hi, lo) = luminance(a).let { la -> luminance(b).let { lb -> maxOf(la, lb) to minOf(la, lb) } }
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun sepia_keeps_readable_grey_text_readable() {
        // #606060 has contrast 6.3 on white paper; normal text needs 4.5 (#456).
        val grey = RgbColor.gray(96.0 / 255.0)
        assertTrue(contrast(grey, RgbColor.WHITE) > 4.5)
        val mapped = fillColorThrough(ReaderTheme.Sepia, grey)
        val ratio = contrast(mapped, ReaderTheme.Sepia.background)
        assertTrue(ratio >= 4.5, "grey text on sepia paper has contrast $ratio, mapped to $mapped")
        assertTrue(mapped.r > mapped.b, "the grey stays warm: $mapped")
    }

    @Test
    fun dark_keeps_saturated_blue_text_readable() {
        // Pure blue has contrast 8.6 on white paper and kept 2.0 on dark paper (#457).
        val blue = RgbColor(0.0, 0.0, 1.0)
        val mapped = fillColorThrough(ReaderTheme.Dark, blue)
        val ratio = contrast(mapped, ReaderTheme.Dark.background)
        assertTrue(ratio >= 4.5, "blue text on dark paper has contrast $ratio, mapped to $mapped")
        assertTrue(mapped.b > mapped.r && mapped.b > mapped.g, "the blue stays blue: $mapped")
    }

    @Test
    fun every_theme_keeps_text_readable_where_light_paper_does() {
        val inks = listOf(
            RgbColor.BLACK, RgbColor.gray(0.25), RgbColor.gray(96.0 / 255.0), RgbColor.gray(0.45),
            RgbColor(0.0, 0.0, 1.0), RgbColor(0.0, 0.0, 0.5), RgbColor(0.8, 0.0, 0.0), RgbColor(0.0, 0.5, 0.0),
            RgbColor(0.5, 0.0, 0.5), RgbColor(0.6, 0.3, 0.0),
        )
        for (theme in listOf(ReaderTheme.Dark, ReaderTheme.Sepia)) for (ink in inks) {
            val before = contrast(ink, RgbColor.WHITE)
            val after = contrast(fillColorThrough(theme, ink), theme.background)
            assertTrue(before < 4.5 || after >= 4.5, "$theme: $ink had contrast $before on white paper and has $after")
        }
    }

    @Test
    fun text_on_an_author_painted_box_stays_readable() {
        // Dark text on a light box, and light text on a dark box, as a book paints a call-out or a header.
        val pairs = listOf(
            RgbColor.BLACK to RgbColor.gray(0.93),
            RgbColor.gray(0.2) to RgbColor(1.0, 1.0, 0.8),
            RgbColor.WHITE to RgbColor(0.0, 0.0, 0.5),
            RgbColor.WHITE to RgbColor(0.2, 0.2, 0.2),
        )
        for (theme in listOf(ReaderTheme.Dark, ReaderTheme.Sepia)) for ((ink, box) in pairs) {
            val ratio = contrast(fillColorThrough(theme, ink), fillColorThrough(theme, box))
            assertTrue(ratio >= 4.5, "$theme: $ink on $box has contrast $ratio")
        }
    }

    @Test
    fun a_theme_keeps_the_order_of_lightness() {
        // A lighter colour stays lighter, so a box never swaps places with the text on it.
        for (theme in listOf(ReaderTheme.Dark, ReaderTheme.Sepia)) {
            val mapped = (0..20).map { luminance(fillColorThrough(theme, RgbColor.gray(it / 20.0))) }
            val ordered = if (theme == ReaderTheme.Dark) mapped.zipWithNext { a, b -> a >= b } else mapped.zipWithNext { a, b -> a <= b }
            assertTrue(ordered.all { it }, "$theme: luminances $mapped")
        }
    }

    @Test
    fun light_wrap_is_identity_passthrough() {
        val rec = RecordingCanvas()
        assertSame(rec, ReaderTheme.Light.wrap(rec), "Light must not allocate a wrapper")
    }
}
