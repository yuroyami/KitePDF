package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.font.FontSpec
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `hostGlyphOutline` gives the outline of host text in the face the canvas draws it in, at 1000
 * units to the em with y up, each character after the advance of the one before (#857, #893).
 * Text in a font without a program then strokes and clips by its letters.
 */
class CoreGraphicsHostGlyphOutlineTest {

    @Test
    fun a_letter_outlines_at_a_thousand_units_to_the_em_with_y_up() {
        var outline: io.github.yuroyami.kitepdf.core.render.KitePath? = null
        cgDraw(10, 10) { outline = it.hostGlyphOutline("H", FontSpec.SansSerif) }
        val box = assertNotNull(outline?.bounds(), "Helvetica outlines H")
        // Helvetica's H stands on the baseline and is about 718 units tall and 722 wide with its sides.
        assertTrue(box.bottom in -5.0..5.0 && box.top in 650.0..760.0, "the H runs from ${box.bottom} to ${box.top}")
        assertTrue(box.left >= 0.0 && box.right in 500.0..722.0, "the H spans ${box.left} to ${box.right}")
    }

    @Test
    fun each_letter_follows_the_advance_of_the_one_before() {
        var one: io.github.yuroyami.kitepdf.core.render.KitePath? = null
        var two: io.github.yuroyami.kitepdf.core.render.KitePath? = null
        cgDraw(10, 10) {
            one = it.hostGlyphOutline("H", FontSpec.SansSerif)
            two = it.hostGlyphOutline("HH", FontSpec.SansSerif)
        }
        val first = assertNotNull(one?.bounds())
        val both = assertNotNull(two?.bounds())
        // The second H starts one advance, 722 units, after the first.
        assertTrue(both.right - first.right in 700.0..744.0, "the second H ends at ${both.right}, the first at ${first.right}")
    }

    @Test
    fun text_the_face_cannot_draw_has_no_outline() {
        var empty: io.github.yuroyami.kitepdf.core.render.KitePath? = io.github.yuroyami.kitepdf.core.render.KitePath.Builder().build()
        var arabic: io.github.yuroyami.kitepdf.core.render.KitePath? = io.github.yuroyami.kitepdf.core.render.KitePath.Builder().build()
        cgDraw(10, 10) {
            empty = it.hostGlyphOutline("", FontSpec.SansSerif)
            // Helvetica has no Arabic letters, and the outline comes from that one face.
            arabic = it.hostGlyphOutline("ب", FontSpec.SansSerif)
        }
        assertNull(empty)
        assertNull(arabic)
    }
}
