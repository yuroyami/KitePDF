package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.KiteRasterCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals

/** The geometry of a `<clipPath>`: its own transform (#727), and the union of its children under their clip-rule (#731). */
class SvgClipPathTest {

    private fun render(clip: String): KiteRaster {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><defs>$clip</defs>""" +
            """<rect width="100" height="100" fill="red" clip-path="url(#c)"/></svg>"""
        val canvas = KiteRasterCanvas(100, 100)
        canvas.clear(RgbColor.WHITE)
        SvgDocument.open(svg.encodeToByteArray()).pages.single().renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.toRaster()
    }

    /** Whether the red rect shows at ([x], [y]): its green channel drops from white's 255. */
    private fun KiteRaster.red(x: Int, y: Int): Boolean = (this[x, y] shr 8) and 0xFF < 128

    @Test
    fun theClipPathsOwnTransformMovesTheClip() {
        val r = render("""<clipPath id="c" transform="translate(50,0)"><rect width="10" height="10"/></clipPath>""")
        assertEquals(true, r.red(55, 5), "the clip moved right by 50")
        assertEquals(false, r.red(5, 5), "nothing shows where the clip was before the transform")
    }

    @Test
    fun anEvenOddChildKeepsItsHole() {
        val r = render("""<clipPath id="c"><path clip-rule="evenodd" d="M0 0 H100 V100 H0 Z M25 25 H75 V75 H25 Z"/></clipPath>""")
        assertEquals(false, r.red(50, 50), "the hole")
        assertEquals(true, r.red(10, 10), "the ring")
    }

    @Test
    fun theChildrenAddUpWhateverTheirWinding() {
        // The second square runs counterclockwise; joined as one nonzero path the overlap cancelled.
        val r = render("""<clipPath id="c"><path d="M0 0 H60 V60 H0 Z"/><path d="M40 40 V100 H100 V40 Z"/></clipPath>""")
        assertEquals(true, r.red(50, 50), "in both")
        assertEquals(true, r.red(20, 20), "in the first only")
        assertEquals(true, r.red(80, 80), "in the second only")
        assertEquals(false, r.red(80, 20), "in neither")
    }

    @Test
    fun anEvenOddChildBesideAnotherKeepsItsHoleInTheUnion() {
        val r = render(
            """<clipPath id="c"><path clip-rule="evenodd" d="M0 0 H60 V60 H0 Z M15 15 H45 V45 H15 Z"/>""" +
                """<rect x="70" y="70" width="20" height="20"/></clipPath>""",
        )
        assertEquals(false, r.red(30, 30), "the hole of the even-odd child")
        assertEquals(true, r.red(5, 5), "its ring")
        assertEquals(true, r.red(80, 80), "the other child")
    }

    @Test
    fun clipRuleIsInheritedFromTheClipPath() {
        val r = render("""<clipPath id="c" clip-rule="evenodd"><path d="M0 0 H100 V100 H0 Z M25 25 H75 V75 H25 Z"/></clipPath>""")
        assertEquals(false, r.red(50, 50))
    }
}
