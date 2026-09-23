package io.github.yuroyami.kitepdf.difftest

import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs

/**
 * A page of runs in Helvetica, which the page does not embed, so every backend draws
 * them in a host face. The pen must still advance by the document's own numbers
 * (ISO 32000-1, 9.4.4). The lines are a plain run, the same run under `6 Tc`, then
 * under `6 Tc 20 Tw`, then a run under `6 Tc` whose /Widths give every glyph 1000
 * units. Each backend must end each line where mutool ends it.
 *
 * The check is how far right each line reaches, not a whole-page score: a few
 * lines on a white page barely move a page-wide mean even when the text is far
 * out of place. A right edge measures the pen advance itself, whatever face the
 * backend draws the glyphs in.
 */
object TextSpacingFixture {

    /** The baseline of each line, in points from the bottom of the 612 by 792 page. */
    private val baselines = listOf(700, 640, 580, 520)

    /** The most points a line may end away from mutool's end: the host face draws its own last glyph. */
    const val TOLERANCE: Int = 6

    val bytes: ByteArray by lazy { pdf() }

    /**
     * Renders the page with [render] and compares the right edge of each line with
     * mutool's at 72 dpi. Returns one line for each line of text out of place. Needs
     * mutool, so check [MuPdfOracle.binary] first.
     */
    fun check(backend: String, render: (ByteArray) -> BufferedImage): List<String> {
        val kite = render(bytes)
        val pdf = File.createTempFile("kite-spacing", ".pdf").apply { deleteOnExit(); writeBytes(bytes) }
        val reference = MuPdfOracle.render(pdf, page = 1, dpi = 72) ?: error("mutool did not render the spacing page")
        val failures = ArrayList<String>()
        for ((index, baseline) in baselines.withIndex()) {
            // A band around the baseline: 18-point text, so a few points either way.
            val top = 792 - baseline - 16
            val bottom = 792 - baseline + 6
            val ours = rightmostInk(kite, top, bottom)
            val theirs = rightmostInk(reference, top, bottom)
            println("$backend line $index right edge: kite=$ours mutool=$theirs")
            if (ours < 0 || theirs < 0 || abs(ours - theirs) > TOLERANCE) {
                failures += "line $index reaches x=$ours, mutool reaches x=$theirs"
            }
        }
        return failures
    }

    /** The rightmost column that holds ink within the rows from [top] to [bottom], or -1. */
    private fun rightmostInk(image: BufferedImage, top: Int, bottom: Int): Int {
        val y0 = top.coerceAtLeast(0)
        val y1 = bottom.coerceAtMost(image.height - 1)
        for (x in image.width - 1 downTo 0) {
            for (y in y0..y1) {
                val rgb = image.getRGB(x, y)
                if ((rgb shr 16) and 0xFF < 200 || (rgb shr 8) and 0xFF < 200 || rgb and 0xFF < 200) return x
            }
        }
        return -1
    }

    private fun pdf(): ByteArray {
        val content = buildString {
            append("BT /F1 18 Tf 1 0 0 1 40 700 Tm (Kite spacing check) Tj ET\n")
            append("BT /F1 18 Tf 6 Tc 1 0 0 1 40 640 Tm (Kite spacing check) Tj ET\n")
            append("BT /F1 18 Tf 6 Tc 20 Tw 1 0 0 1 40 580 Tm (Kite spacing check) Tj ET\n")
            append("BT /F2 18 Tf 6 Tc 0 Tw 1 0 0 1 40 520 Tm (Hamburgefonstiv) Tj ET\n")
        }
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 612 792] >>",
            "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> /Contents 4 0 R >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /FirstChar 32 /LastChar 126 " +
                "/Widths [${List(95) { 1000 }.joinToString(" ")}] >>",
        )
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, body ->
            sb.length.also { sb.append("${i + 1} 0 obj\n$body\nendobj\n") }
        }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }
}
