package io.github.yuroyami.kitepdf.difftest

import java.util.Locale
import java.util.zip.Deflater
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * One-page PDFs that each draw one image smaller or larger than its pixels. The
 * tests of each backend render them and score the result against mutool, so a
 * backend that samples an image in its own way fails on its own.
 */
object ImageFixtures {

    fun all(): List<OracleFixture> = listOf(
        // Single-pixel squares drawn at a quarter of their size average to grey.
        image("image-checkerboard-shrunk", "64 0 0 64 68 68 cm", gray(256) { x, y -> if ((x + y) % 2 == 0) 0 else 255 }, budget = 0.005),
        // A one-pixel line every eight rows, drawn at a quarter: each line fades to grey instead of dropping out.
        image("image-thin-lines-shrunk", "64 0 0 64 68 68 cm", gray(256) { _, y -> if (y % 8 == 0) 0 else 255 }, budget = 0.006),
        // Four pixels enlarged eighty times keep hard edges.
        image("image-enlarged-hard-edges", "160 0 0 160 20 20 cm", FOUR_COLOURS, budget = 0.005),
        // A stencil mask of single-pixel squares enlarged twenty times keeps hard edges too.
        image("image-stencil-enlarged", "160 0 0 160 20 20 cm", STENCIL, budget = 0.005),
        // The same pixels with /Interpolate true blend into each other.
        image("image-interpolate", "160 0 0 160 20 20 cm", FOUR_COLOURS.copy(interpolate = true), budget = 0.005),
        // Squares of eight pixels enlarged one and a half times blend at their edges.
        image("image-enlarged-smooth", "96 0 0 96 52 52 cm", gray(64) { x, y -> if ((x / 8 + y / 8) % 2 == 0) 40 else 220 }, budget = 0.002),
        // The same squares turned by 30 degrees at their own size.
        image("image-rotated", rotated(64.0, 30.0), gray(64) { x, y -> if ((x / 8 + y / 8) % 2 == 0) 40 else 220 }, budget = 0.005),
    )

    /** Samples for an image XObject, row by row from the top. A null [colorSpace] makes a one-bit stencil mask. */
    private data class Samples(val width: Int, val height: Int, val colorSpace: String?, val data: ByteArray, val interpolate: Boolean = false)

    /** Red, green, blue and white in a square of two by two pixels. */
    private val FOUR_COLOURS = Samples(
        2, 2, "/DeviceRGB",
        byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, -1),
    )

    /** A stencil mask of eight by eight single-pixel squares. */
    private val STENCIL = Samples(8, 8, null, ByteArray(8) { y -> if (y % 2 == 0) 0xAA.toByte() else 0x55 })

    /** A square DeviceGray image [side] pixels wide whose grey level at (x, y) is [level]. */
    private fun gray(side: Int, level: (x: Int, y: Int) -> Int): Samples =
        Samples(side, side, "/DeviceGray", ByteArray(side * side) { i -> level(i % side, i / side).toByte() })

    /** A `cm` operator that draws the unit square [size] points wide, turned by [degrees] about the page centre. */
    private fun rotated(size: Double, degrees: Double): String {
        val t = degrees * PI / 180
        val a = size * cos(t)
        val b = size * sin(t)
        // The centre of the unit square lands on the page centre (100, 100).
        val e = 100 - (a - b) / 2
        val f = 100 - (b + a) / 2
        return String.format(Locale.ROOT, "%.4f %.4f %.4f %.4f %.4f %.4f cm", a, b, -b, a, e, f)
    }

    /** A page that draws [samples] under [cm], in blue when they are a stencil mask. */
    private fun image(name: String, cm: String, samples: Samples, budget: Double): OracleFixture {
        val deflater = Deflater()
        deflater.setInput(samples.data)
        deflater.finish()
        val buffer = ByteArray(samples.data.size + 64)
        val packed = buffer.copyOf(deflater.deflate(buffer))
        deflater.end()
        val format = samples.colorSpace?.let { "/ColorSpace $it /BitsPerComponent 8" } ?: "/ImageMask true /BitsPerComponent 1"
        val entries = "/Type /XObject /Subtype /Image /Width ${samples.width} /Height ${samples.height} $format /Filter /FlateDecode" +
            if (samples.interpolate) " /Interpolate true" else ""
        return oracleFixture(name, "q 0 0 1 rg $cm /Im1 Do Q", "/XObject << /Im1 5 0 R >>", listOf(pdfStream(packed, entries)), budget)
    }
}
