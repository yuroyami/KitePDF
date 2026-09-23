package io.github.yuroyami.kitepdf.difftest

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * One-page PDFs, 200 by 200 points, that each paint one shading, with `sh` or as a
 * pattern colour. The tests of each backend render them and score the result against
 * mutool, so a backend that draws a gradient or a mesh wrong fails on its own.
 */
object GradientFixtures {

    /** Red to blue, linear in the shading parameter. */
    private const val RED_TO_BLUE = "<< /FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [0 0 1] /N 1 >>"

    fun all(): List<OracleFixture> = listOf(
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
        patternText("text-shading-pattern", "/Pattern cs /P1 scn BT /F1 60 Tf 20 80 Td (AAA) Tj ET", budget = 0.005),
        // A stroke in a pattern colour paints the pattern inside the stroke only (8.7.3.1).
        patternStroke("stroke-pattern-square", "10 w 50 50 100 100 re S", budget = 0.005),
        patternStroke("stroke-pattern-line", "10 w 20 100 m 180 100 l S", budget = 0.005),
        patternStroke("stroke-pattern-dashed-round", "10 w 1 J 1 j [20 15] 0 d 30 40 m 100 170 l 170 40 l S", budget = 0.005),
        // Glyph edges lie on whole points, so a 4-point stroke has whole-pixel edges at 72 dpi.
        patternText("text-stroke-shading-pattern", "/Pattern CS /P1 SCN 4 w BT 1 Tr /F1 60 Tf 20 80 Td (AAA) Tj ET", budget = 0.005),
        // A tensor-product patch whose interior points pull its middle towards the top right
        // corner. A renderer that reads it as a Coons patch draws the colours in other places (#196).
        mesh("mesh-tensor-interior", "q 0 0 200 200 re W n /Sh1 sh Q", "", tensorPatch(), budget = 0.005),
        // A mesh with a function interpolates t. Halfway from the red corner at t = 0 to the blue
        // diagonal at t = 1, the colour is green, not the purple that mixing red and blue gives
        // (ISO 32000-1, 8.7.4.5.5).
        mesh("mesh-function", "q 0 0 200 200 re W n /Sh1 sh Q", "", functionTriangles(), budget = 0.005),
        // A translucent mesh over a black band composites once, with no seams between its triangles.
        mesh(
            "mesh-translucent", "0 g 0 0 100 200 re f /GS1 gs /Sh1 sh", "/ExtGState << /GS1 << /ca 0.5 >> >>",
            coonsPatch(), budget = 0.005,
        ),
        // A Coons patch as the pattern colour of a disc, so the edge of the disc cuts the mesh.
        oracleFixture(
            "mesh-coons-pattern",
            "/Pattern cs /P1 scn 170 100 m 170 138.66 138.66 170 100 170 c 61.34 170 30 138.66 30 100 c " +
                "30 61.34 61.34 30 100 30 c 138.66 30 170 61.34 170 100 c f",
            "/Pattern << /P1 5 0 R >>",
            listOf("<< /PatternType 2 /Shading 6 0 R >>".toByteArray(), coonsPatch()),
            budget = 0.005,
        ),
    )

    /** A page that paints the mesh shading [stream] with [content] and [resources] besides it. */
    private fun mesh(name: String, content: String, resources: String, stream: ByteArray, budget: Double): OracleFixture =
        oracleFixture(name, content, "/Shading << /Sh1 5 0 R >> $resources", listOf(stream), budget)

    /** The entries of a mesh stream with 16-bit coordinates over the page and 8-bit colour values. */
    private fun meshEntries(type: Int, colours: Int) = "/ShadingType $type /ColorSpace /DeviceRGB /BitsPerCoordinate 16 " +
        "/BitsPerComponent 8 /BitsPerFlag 8 /Decode [0 200 0 200${" 0 1".repeat(colours)}]"

    private fun ByteArrayOutputStream.point(x: Double, y: Double) {
        for (v in listOf(x, y)) {
            val n = (v / 200 * 65535).roundToInt()
            write(n shr 8)
            write(n and 0xFF)
        }
    }

    /** Red, green, blue and yellow at the corners p00, p03, p33 and p30. */
    private fun ByteArrayOutputStream.cornerColours() {
        for (c in listOf(intArrayOf(255, 0, 0), intArrayOf(0, 255, 0), intArrayOf(0, 0, 255), intArrayOf(255, 255, 0))) {
            for (v in c) write(v)
        }
    }

    /** A square tensor-product patch from (20, 20) to (180, 180), all four interior points at (150, 150). */
    private fun tensorPatch(): ByteArray {
        val data = ByteArrayOutputStream()
        data.write(0)
        fun at(i: Int, j: Int) = data.point(20 + 160.0 * i / 3, 20 + 160.0 * j / 3)
        // p00 p01 p02 p03, p13 p23 p33, p32 p31 p30, p20 p10, then the interior p11 p12 p22 p21.
        at(0, 0); at(0, 1); at(0, 2); at(0, 3)
        at(1, 3); at(2, 3); at(3, 3)
        at(3, 2); at(3, 1); at(3, 0)
        at(2, 0); at(1, 0)
        repeat(4) { data.point(150.0, 150.0) }
        data.cornerColours()
        return pdfStream(data.toByteArray(), meshEntries(7, 3))
    }

    /** A Coons patch over the page square with bulging edges. */
    private fun coonsPatch(): ByteArray {
        val data = ByteArrayOutputStream()
        data.write(0)
        for ((x, y) in listOf(
            20.0 to 20.0, 70.0 to 5.0, 130.0 to 5.0, 180.0 to 20.0,
            195.0 to 70.0, 195.0 to 130.0, 180.0 to 180.0,
            130.0 to 195.0, 70.0 to 195.0, 20.0 to 180.0,
            5.0 to 130.0, 5.0 to 70.0,
        )) data.point(x, y)
        data.cornerColours()
        return pdfStream(data.toByteArray(), meshEntries(6, 3))
    }

    /**
     * Two triangles over the square from (20, 20) to (180, 180) whose one colour value is the t
     * of a function that runs red, green, blue. t is 0 at the bottom left, 1 at the top left and
     * the bottom right, and 0.5 at the top right.
     */
    private fun functionTriangles(): ByteArray {
        val data = ByteArrayOutputStream()
        for ((flag, x, y, t) in listOf(
            listOf(0.0, 20.0, 20.0, 0.0), listOf(0.0, 180.0, 20.0, 255.0), listOf(0.0, 20.0, 180.0, 255.0),
            listOf(1.0, 180.0, 180.0, 128.0),
        )) {
            data.write(flag.toInt())
            data.point(x, y)
            data.write(t.toInt())
        }
        val function = "<< /FunctionType 3 /Domain [0 1] /Bounds [0.5] /Encode [0 1 0 1] /Functions [" +
            "<< /FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [0 1 0] /N 1 >> " +
            "<< /FunctionType 2 /Domain [0 1] /C0 [0 1 0] /C1 [0 0 1] /N 1 >>] >>"
        return pdfStream(data.toByteArray(), meshEntries(4, 1) + " /Function $function")
    }

    private fun axial(coords: String, extend: String = "true true") =
        "<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [$coords] /Function $RED_TO_BLUE /Extend [$extend] >>"

    private fun radial(coords: String, extend: String = "true true") =
        "<< /ShadingType 3 /ColorSpace /DeviceRGB /Coords [$coords] /Function $RED_TO_BLUE /Extend [$extend] >>"

    /** A page that paints [shading] under [cm] over the whole page. */
    private fun fixture(name: String, cm: String, shading: String, budget: Double): OracleFixture = oracleFixture(
        name, "q 0 0 200 200 re W n $cm /Sh1 sh Q", "/Shading << /Sh1 5 0 R >>",
        listOf(shading.toByteArray()), budget,
    )

    /** A page that strokes with [content] in an axial shading pattern across the page. */
    private fun patternStroke(name: String, content: String, budget: Double): OracleFixture = oracleFixture(
        name, "/Pattern CS /P1 SCN $content", "/Pattern << /P1 5 0 R >>",
        listOf("<< /PatternType 2 /Shading ${axial("0 0 200 0")} >>".toByteArray()), budget,
    )

    /**
     * Glyphs shown by [content] in the axial shading pattern `P1`. The font `F1` is
     * embedded, so mutool and every backend paint the same glyph shapes: an `A` is a
     * square 30 points wide at a size of 60, and the next one starts 36 points along.
     */
    private fun patternText(name: String, content: String, budget: Double): OracleFixture = oracleFixture(
        name, content,
        "/Font << /F1 5 0 R >> /Pattern << /P1 8 0 R >>",
        listOf(
            ("<< /Type /Font /Subtype /TrueType /BaseFont /Square /FirstChar 65 /LastChar 65 /Widths [600] " +
                "/FontDescriptor 6 0 R /Encoding /WinAnsiEncoding >>").toByteArray(),
            ("<< /Type /FontDescriptor /FontName /Square /Flags 32 /FontBBox [0 0 500 500] /ItalicAngle 0 " +
                "/Ascent 500 /Descent 0 /CapHeight 500 /StemV 80 /FontFile2 7 0 R >>").toByteArray(),
            pdfStream(squareFont()),
            "<< /PatternType 2 /Shading ${axial("20 0 130 0")} >>".toByteArray(),
        ),
        budget,
    )

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
