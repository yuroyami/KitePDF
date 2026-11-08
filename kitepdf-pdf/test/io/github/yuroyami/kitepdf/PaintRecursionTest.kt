package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.KiteRasterCanvas
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * A pattern cell or a soft mask group that paints with itself, and a pattern fill over a lattice of
 * more cells than an Int can count, end and leave the rest of the page drawn (#971, #972, #973).
 */
class PaintRecursionTest {

    private fun stream(dict: String, body: String) = "<< $dict /Length ${body.length} >>\nstream\n$body\nendstream"

    /** A 200 by 200 page with [resources] and [content]; [extra] are objects 5 onwards. */
    private fun pdf(resources: String, content: String, vararg extra: String): ByteArray {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << $resources >> /Contents 4 0 R >>",
            stream("", content),
        ) + extra
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, body -> sb.length.also { sb.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (off in offsets) sb.append("${off.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    private fun render(bytes: ByteArray): KiteRaster {
        val canvas = KiteRasterCanvas(200, 200)
        canvas.clear(RgbColor.WHITE)
        PdfDocument.open(bytes).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.toRaster()
    }

    /** The blue square from (10, 10) to (30, 30) that each content stream paints last. */
    private fun KiteRaster.blueAt20(): Boolean {
        val p = this[20, 20]
        return (p and 0xFF) > 200 && ((p shr 16) and 0xFF) < 60
    }

    private fun quickly(what: String, body: () -> Unit) {
        val start = TimeSource.Monotonic.markNow()
        body()
        assertTrue(start.elapsedNow().inWholeSeconds < 20, "$what took ${start.elapsedNow()}")
    }

    @Test
    fun aPatternWhoseCellPaintsWithItselfEnds() {
        val file = pdf(
            "/Pattern << /P1 5 0 R >>",
            "/Pattern cs /P1 scn 0 0 200 200 re f 0 0 1 rg 10 10 20 20 re f",
            stream(
                "/Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 20 20] /XStep 20 /YStep 20 /Resources << /Pattern << /P1 5 0 R >> >>",
                "/Pattern cs /P1 scn 0 0 10 10 re f",
            ),
        )
        quickly("render") { assertTrue(render(file).blueAt20(), "the blue square after the fill draws") }
        quickly("recording") { PdfDocument.open(file).pages[0].renderTo(RecordingCanvas(), KiteMatrix.IDENTITY) }
    }

    @Test
    fun aMaskGroupThatSetsItsOwnMaskEnds() {
        val file = pdf(
            "/ExtGState << /G1 5 0 R >>",
            "q /G1 gs 0 0 200 200 re f Q 0 0 1 rg 10 10 20 20 re f",
            "<< /Type /ExtGState /SMask << /S /Luminosity /G 6 0 R >> >>",
            stream(
                "/Type /XObject /Subtype /Form /BBox [0 0 200 200] /Group << /S /Transparency /CS /DeviceGray >> /Resources << /ExtGState << /G1 5 0 R >> >>",
                "/G1 gs 1 g 0 0 200 200 re f",
            ),
        )
        quickly("render") { assertTrue(render(file).blueAt20(), "the blue square after the masked fill draws") }
    }

    @Test
    fun aLatticePastAnIntOfCellsEnds() {
        val n = "-1" + "0".repeat(300) + ".0"
        val big = "2147483645.5"
        val file = pdf(
            "/Pattern << /P0 5 0 R >>",
            "/Pattern cs /P0 scn $n $n m $big $n l $big $big l $n $big l h f 0 0 1 rg 10 10 20 20 re f",
            stream(
                "/Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 1 1] /XStep 1 /YStep 1 /Resources << >>",
                "0 0 1 rg 0 0 1 1 re f",
            ),
        )
        quickly("render") { assertTrue(render(file).blueAt20(), "the blue square after the fill draws") }
        quickly("recording") { PdfDocument.open(file).pages[0].renderTo(RecordingCanvas(), KiteMatrix.IDENTITY) }
    }
}
