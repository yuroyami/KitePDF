package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The draw stream of three reference formulas: a quadratic formula, a nested fraction and a
 * two-by-two matrix. Their AWT rasters were checked by eye once. A hash that changes means the
 * formula draws differently: look at its raster again before you update the hash (#32).
 */
class MathGoldenTest {

    private val ns = "xmlns=\"http://www.w3.org/1998/Math/MathML\""

    private val quadratic = "<math $ns display=\"block\"><mi>x</mi><mo>=</mo><mfrac><mrow><mo>−</mo><mi>b</mi><mo>±</mo>" +
        "<msqrt><msup><mi>b</mi><mn>2</mn></msup><mo>−</mo><mn>4</mn><mi>a</mi><mi>c</mi></msqrt></mrow><mrow><mn>2</mn><mi>a</mi></mrow></mfrac></math>"
    private val nested = "<math $ns display=\"block\"><mfrac><mn>1</mn><mrow><mn>1</mn><mo>+</mo><mfrac><mn>1</mn>" +
        "<mrow><mn>1</mn><mo>+</mo><mfrac><mn>1</mn><mi>x</mi></mfrac></mrow></mfrac></mrow></mfrac></math>"
    private val matrix = "<math $ns display=\"block\"><mi>A</mi><mo>=</mo><mrow><mo>(</mo><mtable><mtr><mtd><mi>a</mi></mtd>" +
        "<mtd><mi>b</mi></mtd></mtr><mtr><mtd><mi>c</mi></mtd><mtd><mi>d</mi></mtd></mtr></mtable><mo>)</mo></mrow></math>"

    private fun r(v: Double): String = ((v * 100).roundToLong() / 100.0).toString()

    private fun m(t: KiteMatrix): String = listOf(t.a, t.b, t.c, t.d, t.e, t.f).joinToString(",", transform = ::r)

    private fun box(path: KitePath): String = path.bounds()?.let { listOf(it.left, it.bottom, it.right, it.top).joinToString(",", transform = ::r) } ?: "-"

    /** The page's draw calls in text, coordinates to a hundredth of a point. */
    private fun stream(math: String): String {
        val doc = EpubDocument.open(
            EpubFixtures.epub("<div style=\"font-size:24px\">$math</div>"),
            EpubSettings(pageWidth = 400.0, pageHeight = 600.0, margin = 36.0),
        )
        val calls = RecordingCanvas().also { doc.page(KiteLocation(0, 0)).renderTo(it) }.calls
        return calls.joinToString("\n") { c ->
            when (c) {
                is RecordingCanvas.Call.Glyphs -> "G ${c.text} ${r(c.fontSize)} ${m(c.textToDevice)} ${c.fontSpec}"
                is RecordingCanvas.Call.Fill -> "F ${box(c.path)} ${m(c.ctm)} ${c.color}"
                is RecordingCanvas.Call.Stroke -> "S ${box(c.path)} ${r(c.lineWidth)} ${m(c.ctm)} ${c.color}"
                else -> c::class.simpleName.orEmpty()
            }
        }
    }

    private fun sha1(text: String): String = Sha1.digest(text.encodeToByteArray()).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun theReferenceFormulasDrawAsWhenTheyWereChecked() {
        assertEquals("1c3778e8257a2277be6e826497afb1371b43a3a4", sha1(stream(quadratic)), "the quadratic formula")
        assertEquals("fa6fb300fe30b7e6d337cc9d2aaff8645bca7162", sha1(stream(nested)), "the nested fraction")
        assertEquals("ffca4c4a4a21b919130359fbe00e9968525d9654", sha1(stream(matrix)), "the matrix")
    }
}
