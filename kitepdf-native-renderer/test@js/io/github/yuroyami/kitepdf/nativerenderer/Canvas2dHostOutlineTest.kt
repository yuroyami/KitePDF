package io.github.yuroyami.kitepdf.nativerenderer

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Text in a font without a program clips by its letters on Canvas2D, from the bundled standard
 * face, not by one box per glyph (ISO 32000-1, 9.3.6; #857). Mode 7 adds an O to the clip, and
 * the blue fill then shows through its ring and not through its hole.
 */
class Canvas2dHostOutlineTest {

    @Test
    fun clipping_text_in_a_font_without_a_program_exposes_its_letters() {
        val ctx = renderOnCanvas(
            testPage(
                "BT /F1 150 Tf 7 Tr 30 40 Td (O) Tj ET 0 0 1 rg 0 0 200 200 re f",
                "/Font << /F1 5 0 R >>",
                listOf("<< /Type /Font /Subtype /TrueType /BaseFont /Verdana >>"),
            ),
        )
        val data = ctx.getImageData(0.0, 0.0, SIDE.toDouble(), SIDE.toDouble()).data.asDynamic()
        var blue = 0
        var left = SIDE; var right = -1; var top = SIDE; var bottom = -1
        for (y in 0 until SIDE) for (x in 0 until SIDE) {
            val i = 4 * (y * SIDE + x)
            if ((data[i + 2] as Int) > 200 && (data[i] as Int) < 60) {
                blue++
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        assertTrue(blue > 200, "the clip shows some of the fill: $blue")
        // The middle of the O's bounds is its hole, which a box would fill.
        val mid = 4 * (((top + bottom) / 2) * SIDE + (left + right) / 2)
        assertTrue((data[mid + 2] as Int) > 200 && (data[mid] as Int) > 200, "the hole of the O stays white")
    }
}
