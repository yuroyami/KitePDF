package io.github.yuroyami.kitepdf.difftest

import java.awt.image.BufferedImage
import kotlin.math.abs

/**
 * A page that strokes under `8 0 0 1 cm`, so the round pen of user space is an
 * ellipse eight times wider than it is tall on the device (ISO 32000-1, 8.4.3.2, #108).
 * At 72 dpi, the `4 w` pen makes a vertical line 32 pixels wide and a horizontal line
 * 4 pixels tall. A `[2 2] 0 d` dash along x is 16 pixels on and 16 pixels off, because
 * dash lengths are in user units too. A backend that strokes with one device width
 * draws every line of the page 18 pixels thick.
 */
object StrokePenFixture {

    /** The most pixels a measured length may differ from the length that the spec gives. */
    const val TOLERANCE: Int = 1

    /** The page: 300 by 200 points, black strokes on white. */
    val bytes: ByteArray by lazy {
        val content = "q 8 0 0 1 0 0 cm 0 0 0 RG 4 w 5 20 m 5 180 l S 5 100 m 35 100 l S " +
            "[2 2] 0 d 5 50 m 35 50 l S Q"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R >>",
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

    /**
     * Measures [image], the page drawn at 72 dpi by [backend]. Returns one line for
     * each length that is more than [TOLERANCE] pixels from the length that the spec gives.
     */
    fun check(backend: String, image: BufferedImage): List<String> {
        val measured = listOf(
            // Row 60 crosses only the vertical line, which is centred on x = 40.
            "vertical line width" to 32 to run(image, 0, 60, dx = 1, dy = 0),
            // Column 200 crosses only the solid horizontal line, which is centred on y = 100.
            "horizontal line height" to 4 to run(image, 200, 80, dx = 0, dy = 1),
            // Row 150 runs along the dashed line. Its second dash starts at x = 72, past the vertical line.
            "dash length" to 16 to run(image, 60, 150, dx = 1, dy = 0),
            "dash gap" to 16 to gap(image, 60, 150),
        )
        val failures = ArrayList<String>()
        for ((nameAndExpected, actual) in measured) {
            val (name, expected) = nameAndExpected
            println("$backend $name: $actual px, expected $expected")
            if (abs(actual - expected) > TOLERANCE) failures += "$name is $actual px, expected $expected"
        }
        return failures
    }

    private fun inked(image: BufferedImage, x: Int, y: Int): Boolean {
        val rgb = image.getRGB(x, y)
        val luma = ((rgb shr 16 and 0xFF) * 77 + (rgb shr 8 and 0xFF) * 150 + (rgb and 0xFF) * 29) shr 8
        return luma < 128
    }

    /** The length of the first run of ink met from ([x], [y]) in the direction ([dx], [dy]). */
    private fun run(image: BufferedImage, x: Int, y: Int, dx: Int, dy: Int): Int {
        var px = x
        var py = y
        fun inside() = px in 0 until image.width && py in 0 until image.height
        while (inside() && !inked(image, px, py)) { px += dx; py += dy }
        var length = 0
        while (inside() && inked(image, px, py)) { length++; px += dx; py += dy }
        return length
    }

    /** The length of the first gap after the first run of ink met from ([x], [y]) going right. */
    private fun gap(image: BufferedImage, x: Int, y: Int): Int {
        var px = x
        while (px < image.width && !inked(image, px, y)) px++
        while (px < image.width && inked(image, px, y)) px++
        var length = 0
        while (px < image.width && !inked(image, px, y)) { length++; px++ }
        return length
    }
}
