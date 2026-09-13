package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Indexed colour spaces: the operand is a raw palette index (ISO 32000-1, 8.6.6.3). */
class IndexedColourTest {

    private fun fills(pdf: ByteArray) = TestPdf.calls(pdf).filterIsInstance<RecordingCanvas.Call.Fill>()

    @Test
    fun each_index_selects_its_own_palette_entry() {
        val pdf = TestPdf.onePage(
            content = "/CS0 cs 0 sc 0 150 200 50 re f 1 sc 0 100 200 50 re f 2 sc 0 50 200 50 re f 3 sc 0 0 200 50 re f",
            resources = "/ColorSpace << /CS0 [/Indexed /DeviceRGB 3 <FF000000FF000000FF000000>] >>",
        )
        assertEquals(
            listOf(RgbColor(1.0, 0.0, 0.0), RgbColor(0.0, 1.0, 0.0), RgbColor(0.0, 0.0, 1.0), RgbColor.BLACK),
            fills(pdf).map { it.color },
        )
    }

    @Test
    fun selecting_the_space_sets_palette_entry_zero() {
        // ISO 32000-1, 8.6.8: the initial colour is index 0, green in this palette.
        val pdf = TestPdf.onePage(
            content = "/CS0 cs 0 0 200 200 re f",
            resources = "/ColorSpace << /CS0 [/Indexed /DeviceRGB 1 <00FF0000FF00>] >>",
        )
        assertEquals(RgbColor(0.0, 1.0, 0.0), fills(pdf).single().color)
    }

    @Test
    fun a_lab_palette_spans_the_lab_ranges() {
        // Entry 0 is 255, 128, 128: lightness 100 and neutral, so white, not near black.
        val pdf = TestPdf.onePage(
            content = "/CS0 cs 0 0 200 200 re f",
            resources = "/ColorSpace << /CS0 [/Indexed [/Lab << /WhitePoint [0.9505 1 1.089] /Range [-128 127 -128 127] >>] 0 <FF8080>] >>",
        )
        val c = fills(pdf).single().color
        assertTrue(c.r > 0.95 && c.g > 0.95 && c.b > 0.95, "L=100 is white, got $c")
    }
}
