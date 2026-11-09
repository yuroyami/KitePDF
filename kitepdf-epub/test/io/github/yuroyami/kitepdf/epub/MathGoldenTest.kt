package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.font.FontSpec
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

    // Whole hundredths: Double.toString writes 1.0 on the JVM and 1 on JS, which changed the hash.
    private fun r(v: Double): String = (v * 100).roundToLong().toString()

    private fun rgb(c: io.github.yuroyami.kitepdf.core.render.RgbColor): String = listOf(c.r, c.g, c.b).joinToString(",", transform = ::r)

    private fun m(t: KiteMatrix): String = listOf(t.a, t.b, t.c, t.d, t.e, t.f).joinToString(",", transform = ::r)

    /**
     * The spec as its data class printed it before it had a language, and the language after it
     * when there is one, so a new field of [FontSpec] does not change the hash of a formula that
     * draws the same.
     */
    private fun spec(s: FontSpec): String =
        "FontSpec(family=${s.family}, bold=${s.bold}, italic=${s.italic}, name=${s.name})" + (s.language?.let { " $it" } ?: "")

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
                is RecordingCanvas.Call.Glyphs -> "G ${c.text} ${r(c.fontSize)} ${m(c.textToDevice)} ${spec(c.fontSpec)}"
                is RecordingCanvas.Call.Fill -> "F ${box(c.path)} ${m(c.ctm)} ${rgb(c.color)}"
                is RecordingCanvas.Call.Stroke -> "S ${box(c.path)} ${r(c.lineWidth)} ${m(c.ctm)} ${rgb(c.color)}"
                else -> c::class.simpleName.orEmpty()
            }
        }
    }

    private fun sha1(text: String): String = Sha1.digest(text.encodeToByteArray()).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun theReferenceFormulasDrawAsWhenTheyWereChecked() {
        assertEquals("635b9b14c364c25b997ace91659b4923b2004448", sha1(stream(quadratic)), "the quadratic formula")
        assertEquals("f250f3a025bf95bb3e86819f8dde99f5b23f7c25", sha1(stream(nested)), "the nested fraction")
        assertEquals("7f05cf8e7a820dfb2bf3c3e2b4c4e9bb36cbc040", sha1(stream(matrix)), "the matrix")
    }
}
