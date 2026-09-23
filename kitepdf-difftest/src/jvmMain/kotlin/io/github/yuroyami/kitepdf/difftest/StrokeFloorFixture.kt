package io.github.yuroyami.kitepdf.difftest

import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.max

/**
 * A page of black rules, one for each width in [WIDTHS], that every backend strokes with
 * the same floor (#109, #110). A width of 0 is one device pixel, as ISO 32000-1, 8.4.3.2
 * says. Any other width is at least a fifth of a pixel, the anti-alias unit that MuPDF
 * widens every stroke to. MuPDF also draws the width of 0 at a fifth, so this page is
 * measured against the rule and not scored against mutool.
 */
object StrokeFloorFixture {

    /** The line widths in user units, from the top rule down. */
    val WIDTHS: List<Double> = listOf(0.0, 0.05, 0.1, 0.15, 0.3, 0.6)

    /** The most a measured ink may differ from [expectedInk], as a fraction of it. */
    const val TOLERANCE: Double = 0.3

    /** The page: 200 by 200 points. Rule i runs from x = 10 to 190, centred on the pixel row 19 + 30 i at 72 dpi. */
    val bytes: ByteArray by lazy {
        val content = WIDTHS.withIndex().joinToString(" ", prefix = "0 0 0 RG ") { (i, w) ->
            val y = 180.5 - 30 * i
            "$w w 10 $y m 190 $y l S"
        }
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
        )
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, body ->
            sb.length.also { sb.append("${i + 1} 0 obj\n$body\nendobj\n") }
        }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /** The ink of a rule of [width] user units at 72 dpi: 255 for each device pixel of its width. */
    fun expectedInk(width: Double): Double = 255.0 * if (width <= 0.0) 1.0 else max(width, 0.2)

    /**
     * Measures [image], the page drawn at 72 dpi by [backend]. The ink of a rule is 255
     * minus the luma, summed down a column across it and averaged over three columns.
     * Returns one line for each rule whose ink is more than [TOLERANCE] from [expectedInk].
     */
    fun check(backend: String, image: BufferedImage): List<String> {
        val failures = ArrayList<String>()
        for ((i, width) in WIDTHS.withIndex()) {
            val row = 19 + 30 * i
            val ink = listOf(50, 100, 150).map { x ->
                (row - 10..row + 10).sumOf { y -> 255 - luma(image.getRGB(x, y)) }
            }.average()
            val expected = expectedInk(width)
            println("$backend ${width} w: ink ${"%.1f".format(ink)}, expected ${"%.1f".format(expected)}")
            if (abs(ink - expected) > TOLERANCE * expected) failures += "$width w has ink $ink, expected $expected"
        }
        return failures
    }

    private fun luma(rgb: Int): Int = ((rgb shr 16 and 0xFF) * 77 + (rgb shr 8 and 0xFF) * 150 + (rgb and 0xFF) * 29) shr 8
}
