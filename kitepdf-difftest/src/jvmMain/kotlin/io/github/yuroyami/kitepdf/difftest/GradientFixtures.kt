package io.github.yuroyami.kitepdf.difftest

import java.io.ByteArrayOutputStream

/**
 * One-page PDFs, 200 by 200 points, that each paint one axial or radial shading,
 * with `sh` or as the pattern colour of text. The tests of each backend render
 * them and score the result against mutool, so a backend that draws a gradient
 * wrong fails on its own.
 */
object GradientFixtures {

    /** A named fixture and the most mean absolute error a backend may score on it. */
    data class Fixture(val name: String, val bytes: ByteArray, val budget: Double)

    /** Red to blue, linear in the shading parameter. */
    private const val RED_TO_BLUE = "<< /FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [0 0 1] /N 1 >>"

    fun all(): List<Fixture> = listOf(
        // A skewed CTM tilts the bands of an axial shading.
        fixture("axial-skewed", "1 0 1 1 20 0 cm", axial("0 0 100 0"), budget = 0.005),
        // A non-uniform CTM stretches the circles of a radial shading into ellipses.
        fixture("radial-stretched", "2 0 0 1 0 0 cm", radial("50 100 0 50 100 40"), budget = 0.005),
        // A scale along the axis only, which a backend that maps just the two end points also draws right.
        fixture("axial-scaled-along-axis", "2 0 0 1 0 0 cm", axial("0 0 100 0"), budget = 0.005),
        // A small start circle off the centre of the end circle: the highlight of a sphere.
        fixture("radial-offset-highlight", "", radial("70 130 5 100 100 85"), budget = 0.005),
        // Two circles with one centre: the colour of the start circle fills its inside.
        fixture("radial-concentric", "", radial("100 100 10 100 100 90"), budget = 0.005),
        // A start circle larger than the end circle, so the colours run inwards.
        fixture("radial-shrinking", "", radial("100 100 90 100 100 10"), budget = 0.005),
        // An end whose extend flag is false paints nothing past it: a band, and then one side only.
        fixture("axial-extend-none", "", axial("70 0 130 0", "false false"), budget = 0.015),
        fixture("axial-extend-start", "", axial("70 0 130 0", "true false"), budget = 0.015),
        fixture("axial-extend-end", "", axial("70 0 130 0", "false true"), budget = 0.015),
        // A ring, then everything outside the start circle, then the disc of the end circle.
        fixture("radial-extend-none", "", radial("100 100 20 100 100 80", "false false"), budget = 0.015),
        fixture("radial-extend-start", "", radial("100 100 20 100 100 80", "true false"), budget = 0.015),
        fixture("radial-extend-end", "", radial("100 100 20 100 100 80", "false true"), budget = 0.015),
        // Text in a shading pattern colour shows the gradient inside its glyphs (ISO 32000-1, 9.3.6).
        patternText("text-shading-pattern", budget = 0.005),
    )

    private fun axial(coords: String, extend: String = "true true") =
        "<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [$coords] /Function $RED_TO_BLUE /Extend [$extend] >>"

    private fun radial(coords: String, extend: String = "true true") =
        "<< /ShadingType 3 /ColorSpace /DeviceRGB /Coords [$coords] /Function $RED_TO_BLUE /Extend [$extend] >>"

    /** A page that paints [shading] under [cm] over the whole page. */
    private fun fixture(name: String, cm: String, shading: String, budget: Double): Fixture = page(
        name, "q 0 0 200 200 re W n $cm /Sh1 sh Q", "/Shading << /Sh1 5 0 R >>",
        listOf(shading.toByteArray()), budget,
    )

    /**
     * Three glyphs in an axial shading pattern. The font is embedded, so mutool and
     * every backend fill the same glyph shapes: squares 30 points wide, 36 points apart.
     */
    private fun patternText(name: String, budget: Double): Fixture = page(
        name, "/Pattern cs /P1 scn BT /F1 60 Tf 20 80 Td (AAA) Tj ET",
        "/Font << /F1 5 0 R >> /Pattern << /P1 8 0 R >>",
        listOf(
            ("<< /Type /Font /Subtype /TrueType /BaseFont /Square /FirstChar 65 /LastChar 65 /Widths [600] " +
                "/FontDescriptor 6 0 R /Encoding /WinAnsiEncoding >>").toByteArray(),
            ("<< /Type /FontDescriptor /FontName /Square /Flags 32 /FontBBox [0 0 500 500] /ItalicAngle 0 " +
                "/Ascent 500 /Descent 0 /CapHeight 500 /StemV 80 /FontFile2 7 0 R >>").toByteArray(),
            stream(squareFont()),
            "<< /PatternType 2 /Shading ${axial("20 0 130 0")} >>".toByteArray(),
        ),
        budget,
    )

    /** One page drawing [content] with [resources]. The [extra] objects are numbered from 5. */
    private fun page(name: String, content: String, resources: String, extra: List<ByteArray>, budget: Double): Fixture {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(),
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".toByteArray(),
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << $resources >> /Contents 4 0 R >>".toByteArray(),
            stream(content.toByteArray()),
        ) + extra
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n".toByteArray())
        val offsets = objects.mapIndexed { i, body ->
            out.size().also {
                out.write("${i + 1} 0 obj\n".toByteArray())
                out.write(body)
                out.write("\nendobj\n".toByteArray())
            }
        }
        val xref = out.size()
        out.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".toByteArray())
        for (o in offsets) out.write("${o.toString().padStart(10, '0')} 00000 n \n".toByteArray())
        out.write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray())
        return Fixture(name, out.toByteArray(), budget)
    }

    private fun stream(data: ByteArray): ByteArray =
        "<< /Length ${data.size} >>\nstream\n".toByteArray() + data + "\nendstream".toByteArray()

    /** A TrueType font of 1000 units per em whose `A` (glyph 1) is a 500-unit square. */
    private fun squareFont(): ByteArray {
        fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
        fun u32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        // cmap format 4 with two segments: A to glyph 1, and the closing 0xFFFF segment.
        val format4 = u16(4) + u16(32) + u16(0) + u16(4) + u16(4) + u16(1) + u16(0) +
            u16(0x41) + u16(0xFFFF) + u16(0) +
            u16(0x41) + u16(0xFFFF) +
            u16(1 - 0x41) + u16(1) +
            u16(0) + u16(0)
        val cmap = u16(0) + u16(1) + u16(3) + u16(1) + u32(12) + format4
        // Glyph 1: one contour of four on-curve points, each coordinate a 16-bit delta. Glyph 0 is empty.
        val glyf = u16(1) + u16(0) + u16(0) + u16(500) + u16(500) + u16(3) + u16(0) +
            byteArrayOf(1, 1, 1, 1) +
            u16(0) + u16(500) + u16(0) + u16(-500) +
            u16(0) + u16(0) + u16(500) + u16(0)
        val head = ByteArray(54).also { it[18] = 0x03; it[19] = 0xE8.toByte() } // 1000 units per em, short loca
        val maxp = u32(0x00010000) + u16(2) + ByteArray(26)
        val hhea = ByteArray(36).also { it[35] = 2 }                            // two horizontal metrics
        val hmtx = u16(500) + u16(0) + u16(600) + u16(0)
        val loca = u16(0) + u16(0) + u16(glyf.size / 2)
        val tables = listOf(
            "cmap" to cmap, "glyf" to glyf, "head" to head, "hhea" to hhea, "hmtx" to hmtx, "loca" to loca, "maxp" to maxp,
        )
        var offset = 12 + 16 * tables.size
        val directory = ByteArrayOutputStream().apply { write(u32(0x00010000) + u16(tables.size) + u16(0) + u16(0) + u16(0)) }
        val bodies = ByteArrayOutputStream()
        for ((tag, body) in tables) {
            directory.write(tag.toByteArray() + u32(0) + u32(offset) + u32(body.size))
            val padded = (body.size + 3) and 3.inv()
            bodies.write(body + ByteArray(padded - body.size))
            offset += padded
        }
        return directory.toByteArray() + bodies.toByteArray()
    }
}
