package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.KiteRasterCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A shape's own opacity applies to the shape as a whole, fill and stroke together (#720). */
class SvgShapeOpacityTest {

    private fun render(svg: String): KiteRaster {
        val canvas = KiteRasterCanvas(100, 100)
        SvgDocument.open(svg.encodeToByteArray()).pages.single().renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.toRaster()
    }

    private fun KiteRaster.alpha(x: Int, y: Int) = (this[x, y] ushr 24) and 0xFF
    private fun KiteRaster.blue(x: Int, y: Int) = this[x, y] and 0xFF
    private fun KiteRaster.red(x: Int, y: Int) = (this[x, y] shr 16) and 0xFF

    @Test
    fun theStrokeOverTheFillCompositesOnceAtTheShapesOpacity() {
        val rect = """<rect x="10" y="10" width="80" height="80" fill="red" stroke="blue" stroke-width="20""""
        val onShape = render("""<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">$rect opacity="0.5"/></svg>""")
        val onGroup = render("""<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><g opacity="0.5">$rect/></g></svg>""")
        for ((x, y) in listOf(50 to 50, 15 to 50, 5 to 50)) {
            assertTrue(kotlin.math.abs(onShape.alpha(x, y) - onGroup.alpha(x, y)) <= 2, "alpha at ($x, $y): ${onShape.alpha(x, y)} and ${onGroup.alpha(x, y)}")
        }
        // Stroke over fill: the stroke alone, half transparent, not the two stacked to 192.
        assertTrue(onShape.alpha(15, 50) in 126..130, "alpha ${onShape.alpha(15, 50)}")
        assertEquals(0, onShape.red(15, 50), "no red shows through the stroke")
        assertTrue(onShape.blue(15, 50) > 200)
    }

    @Test
    fun aShapeWithOnePaintKeepsItsOpacityAsAnAlpha() {
        val r = render("""<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="100" height="100" fill="red" opacity="0.5"/></svg>""")
        assertTrue(r.alpha(50, 50) in 126..130)
    }
}
