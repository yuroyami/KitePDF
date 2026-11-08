package io.github.yuroyami.kitepdf.xps

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** XPS strokes, gradient stops and colours by ECMA-388. */
class XpsPaintTest {
    private fun render(content: String): RecordingCanvas =
        RecordingCanvas().also { XpsDocument.open(XpsFixtures.packageBytes(content)).pages.single().renderTo(it) }

    @Test fun aDashedStrokeTakesTheDashCap() {
        // ECMA-388, 11.1: XAML's dotted line is zero-length dashes with round dash caps (#747).
        val canvas = render("""<Path Data="M10,48 L180,48" Stroke="#FF000000" StrokeThickness="4" StrokeDashArray="0 2" StrokeDashCap="Round"/>""")
        val stroke = canvas.calls.filterIsInstance<RecordingCanvas.Call.Stroke>().single()
        assertEquals(1, stroke.lineCap)
    }

    @Test fun anEndCapThatDiffersFromTheStartCapIsDrawn() {
        val canvas = render("""<Path Data="M10,70 L180,70" Stroke="#FF000000" StrokeThickness="4" StrokeEndLineCap="Round"/>""")
        val stroke = canvas.calls.filterIsInstance<RecordingCanvas.Call.Stroke>().single()
        assertEquals(0, stroke.lineCap, "the start cap is flat")
        val cap = assertNotNull(canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().singleOrNull(), "the round end is filled")
        val b = cap.path.bounds()!!
        assertTrue(b.left >= 179.9 && b.right <= 182.1, "the cap sits at the end: $b")
    }

    @Test fun aContextColorPaintsByItsChannelCount() {
        // ECMA-388, 15.1: four values after the alpha are CMYK (#743).
        val canvas = render("""<Path Data="M10,10 L80,10 80,60 10,60 Z" Fill="ContextColor /Resources/swop.icc 1,0,1,1,0"/>""")
        val fill = canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().single()
        assertTrue(fill.color.r > 0.8 && fill.color.g < 0.3 && fill.color.b < 0.3, "CMYK 0,1,1,0 is red: ${fill.color}")
        assertEquals(RgbColor(0.5, 0.5, 0.5), xpsColor("ContextColor /p.icc 1,0.5")!!.color)
        assertEquals(0.5, xpsColor("ContextColor /p.icc 0.5,1,0,0")!!.alpha)
    }

    @Test fun twoStopsAtOneOffsetMakeAHardEdge() {
        // Stops at one offset stay, so 0.5 red then 0.5 blue is an edge, as in WPF and SVG 1.1, 13.2.4 (#749).
        val xml = """<Path Data="M0,0 L100,0 100,20 0,20 Z"><Path.Fill><LinearGradientBrush MappingMode="Absolute" StartPoint="0,0" EndPoint="100,0">
            <LinearGradientBrush.GradientStops><GradientStop Color="#FFFF0000" Offset="0"/><GradientStop Color="#FFFF0000" Offset="0.5"/>
            <GradientStop Color="#FF0000FF" Offset="0.5"/><GradientStop Color="#FF0000FF" Offset="1"/></LinearGradientBrush.GradientStops>
            </LinearGradientBrush></Path.Fill></Path>"""
        val rc = RecordingCanvas()
        var shading: io.github.yuroyami.kitepdf.core.render.KiteShading? = null
        val capture = object : io.github.yuroyami.kitepdf.core.render.KiteCanvas by rc {
            override fun fillShading(
                shading0: io.github.yuroyami.kitepdf.core.render.KiteShading, ctm: io.github.yuroyami.kitepdf.core.render.KiteMatrix,
                clipPath: io.github.yuroyami.kitepdf.core.render.KitePath?, alpha: Double, blendMode: io.github.yuroyami.kitepdf.core.render.KiteBlendMode,
            ) { shading = shading0 }
        }
        XpsDocument.open(XpsFixtures.packageBytes(xml)).pages.single().renderTo(capture, io.github.yuroyami.kitepdf.core.render.KiteMatrix.IDENTITY)
        val f = (assertNotNull(shading) as io.github.yuroyami.kitepdf.core.render.KiteShading.Axial).function
        val before = f.evaluate(doubleArrayOf(0.49))
        val after = f.evaluate(doubleArrayOf(0.51))
        assertTrue(before[0] > 0.99 && before[2] < 0.01, "red up to the edge: ${before.toList()}")
        assertTrue(after[2] > 0.99 && after[0] < 0.01, "blue after it: ${after.toList()}")
    }

    @Test fun aTiffDeclaresItsResolutionInItsTags() {
        // ECMA-388, 13.4.1: an image brush's Viewbox is in 1/96 inch at the image's own resolution (#752).
        val b = ByteArray(66)
        fun le16(at: Int, v: Int) { b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte() }
        fun le32(at: Int, v: Int) { le16(at, v and 0xFFFF); le16(at + 2, v ushr 16) }
        b[0] = 'I'.code.toByte(); b[1] = 'I'.code.toByte(); le16(2, 42); le32(4, 8)
        le16(8, 3)
        fun entry(at: Int, tag: Int, type: Int, value: Int) { le16(at, tag); le16(at + 2, type); le32(at + 4, 1); le32(at + 8, value) }
        entry(10, 282, 5, 50); entry(22, 283, 5, 58); entry(34, 296, 3, 2)
        le32(50, 300); le32(54, 1); le32(58, 300); le32(62, 1)
        assertEquals(300.0 to 300.0, imageDpi(b))
        b[34 + 8] = 3 // centimetres
        assertEquals(762.0, imageDpi(b).first, 1e-9)
    }
}
