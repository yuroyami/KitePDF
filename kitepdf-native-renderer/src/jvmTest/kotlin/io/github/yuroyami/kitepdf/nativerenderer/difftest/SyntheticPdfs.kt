package io.github.yuroyami.kitepdf.nativerenderer.difftest

import java.io.ByteArrayOutputStream

/**
 * Deterministic, in-memory PDF fixtures that exercise distinct rendering
 * paths (text, vector fills/strokes/curves, transparency, multi-page).
 *
 * They give the harness something to score with zero external files, and they
 * double as regression fixtures: both KitePDF and MuPDF render the *same*
 * bytes, so any divergence the harness reports is a genuine KitePDF gap.
 */
object SyntheticPdfs {

    data class Fixture(val name: String, val bytes: ByteArray)

    fun all(): List<Fixture> = listOf(
        Fixture("syn-text", text()),
        Fixture("syn-vector", vector()),
        Fixture("syn-transparency", transparency()),
        Fixture("syn-multipage", multipage()),
        Fixture("syn-cmyk", cmyk()),
        Fixture("syn-clip", clip()),
        Fixture("syn-blend-multiply", blendMultiply()),
        Fixture("syn-dash", dash()),
        Fixture("syn-shading1-function", shadingFunctionBased()),
        Fixture("syn-shading4-freeform", shadingFreeForm()),
        Fixture("syn-shading5-lattice", shadingLattice()),
        Fixture("syn-shading6-coons", shadingCoons()),
        Fixture("syn-shading7-tensor", shadingTensor()),
        Fixture("syn-type3-font", type3Font()),
    )

    /** T-42: a hand-written Type3 font (square + triangle glyphs, d1-style). */
    private fun type3Font(): ByteArray {
        val p = Pdf()
        p.obj("<< /Type /Catalog /Pages 2 0 R >>")                              // 1
        p.obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")                      // 2
        p.obj(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] " +
                "/Resources << /Font << /F3 5 0 R >> >> /Contents 4 0 R >>",
        )                                                                       // 3
        p.stream(
            "",
            (
                "0 0 1 rg BT /F3 48 Tf 30 100 Td (abab) Tj ET\n" +
                    "1 0 0 rg BT /F3 24 Tf 30 40 Td (ba) Tj ET"
                ).encodeToByteArray(),
        )                                                                       // 4
        p.obj(
            "<< /Type /Font /Subtype /Type3 /FontBBox [0 0 1000 1000] " +
                "/FontMatrix [0.001 0 0 0.001 0 0] " +
                "/CharProcs << /a 6 0 R /b 7 0 R >> " +
                "/Encoding << /Type /Encoding /Differences [97 /a /b] >> " +
                "/FirstChar 97 /LastChar 98 /Widths [1100 1100] >>",
        )                                                                       // 5
        p.stream("", "1100 0 0 0 1000 1000 d1 0 0 1000 1000 re f".encodeToByteArray())          // 6
        p.stream("", "1100 0 0 0 1000 1000 d1 0 0 m 1000 0 l 500 1000 l f".encodeToByteArray()) // 7
        return p.build(1)
    }

    /* ─── T-40 shading fixtures ─────────────────────────────────────────── */

    private fun u16(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    /** Coordinate scaled into a 16-bit raw against Decode [0 200]. */
    private fun coord16(out: java.io.ByteArrayOutputStream, v: Double) {
        u16(out, ((v / 200.0) * 65535.0).toInt().coerceIn(0, 65535))
    }

    private fun rgb8(out: java.io.ByteArrayOutputStream, r: Int, g: Int, b: Int) {
        out.write(r); out.write(g); out.write(b)
    }

    /** Type 1: colour = f(x, y) via a PostScript function (R=x, G=y, B=0.5). */
    private fun shadingFunctionBased(): ByteArray {
        val p = Pdf()
        p.obj("<< /Type /Catalog /Pages 2 0 R >>")                              // 1
        p.obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")                      // 2
        p.obj(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] " +
                "/Resources << /Shading << /Sh1 5 0 R >> >> /Contents 4 0 R >>",
        )                                                                       // 3
        p.stream("", "q 20 20 160 160 re W n /Sh1 sh Q".encodeToByteArray())    // 4
        p.obj(
            "<< /ShadingType 1 /ColorSpace /DeviceRGB /Domain [0 1 0 1] " +
                "/Matrix [160 0 0 160 20 20] /Function 6 0 R >>",
        )                                                                       // 5
        p.stream(
            "/FunctionType 4 /Domain [0 1 0 1] /Range [0 1 0 1 0 1]",
            "{ 0.5 }".encodeToByteArray(),
        )                                                                       // 6
        return p.build(1)
    }

    /** Type 4 free-form: two triangles sharing an edge (flags 0,0,0 then 1). */
    private fun shadingFreeForm(): ByteArray {
        val data = java.io.ByteArrayOutputStream()
        fun vertex(flag: Int, x: Double, y: Double, r: Int, g: Int, b: Int) {
            data.write(flag)
            coord16(data, x)
            coord16(data, y)
            rgb8(data, r, g, b)
        }
        vertex(0, 20.0, 20.0, 255, 0, 0)
        vertex(0, 180.0, 20.0, 0, 255, 0)
        vertex(0, 100.0, 180.0, 0, 0, 255)
        vertex(1, 180.0, 180.0, 255, 255, 0) // reuse edge (v2, v3)

        val p = Pdf()
        p.obj("<< /Type /Catalog /Pages 2 0 R >>")
        p.obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        p.obj(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] " +
                "/Resources << /Shading << /Sh1 5 0 R >> >> /Contents 4 0 R >>",
        )
        p.stream("", "q 0 0 200 200 re W n /Sh1 sh Q".encodeToByteArray())
        p.stream(
            "/ShadingType 4 /ColorSpace /DeviceRGB /BitsPerCoordinate 16 " +
                "/BitsPerComponent 8 /BitsPerFlag 8 " +
                "/Decode [0 200 0 200 0 1 0 1 0 1]",
            data.toByteArray(),
        )
        return p.build(1)
    }

    /** Type 5 lattice: a 2x2 vertex grid, four corner colours. */
    private fun shadingLattice(): ByteArray {
        val data = java.io.ByteArrayOutputStream()
        fun vertex(x: Double, y: Double, r: Int, g: Int, b: Int) {
            coord16(data, x)
            coord16(data, y)
            rgb8(data, r, g, b)
        }
        vertex(20.0, 20.0, 255, 0, 0)
        vertex(180.0, 20.0, 0, 255, 0)
        vertex(20.0, 180.0, 0, 0, 255)
        vertex(180.0, 180.0, 255, 255, 0)

        val p = Pdf()
        p.obj("<< /Type /Catalog /Pages 2 0 R >>")
        p.obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        p.obj(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] " +
                "/Resources << /Shading << /Sh1 5 0 R >> >> /Contents 4 0 R >>",
        )
        p.stream("", "q 0 0 200 200 re W n /Sh1 sh Q".encodeToByteArray())
        p.stream(
            "/ShadingType 5 /ColorSpace /DeviceRGB /BitsPerCoordinate 16 " +
                "/BitsPerComponent 8 /VerticesPerRow 2 " +
                "/Decode [0 200 0 200 0 1 0 1 0 1]",
            data.toByteArray(),
        )
        return p.build(1)
    }

    /** Type 6 Coons: one curved-edge patch, four corner colours. */
    private fun shadingCoons(): ByteArray {
        val data = java.io.ByteArrayOutputStream()
        data.write(0) // flag: new patch
        // p1..p12 counterclockwise from the bottom-left corner; bulging edges.
        val pts = listOf(
            20.0 to 20.0, 70.0 to 5.0, 130.0 to 5.0, 180.0 to 20.0,   // bottom
            195.0 to 70.0, 195.0 to 130.0, 180.0 to 180.0,             // right
            130.0 to 195.0, 70.0 to 195.0, 20.0 to 180.0,              // top (r->l)
            5.0 to 130.0, 5.0 to 70.0,                                  // left (t->b)
        )
        for ((x, y) in pts) {
            coord16(data, x)
            coord16(data, y)
        }
        rgb8(data, 255, 0, 0)
        rgb8(data, 0, 255, 0)
        rgb8(data, 0, 0, 255)
        rgb8(data, 255, 255, 0)

        val p = Pdf()
        p.obj("<< /Type /Catalog /Pages 2 0 R >>")
        p.obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        p.obj(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] " +
                "/Resources << /Shading << /Sh1 5 0 R >> >> /Contents 4 0 R >>",
        )
        p.stream("", "q 0 0 200 200 re W n /Sh1 sh Q".encodeToByteArray())
        p.stream(
            "/ShadingType 6 /ColorSpace /DeviceRGB /BitsPerCoordinate 16 " +
                "/BitsPerComponent 8 /BitsPerFlag 8 " +
                "/Decode [0 200 0 200 0 1 0 1 0 1]",
            data.toByteArray(),
        )
        return p.build(1)
    }

    /**
     * Type 7 tensor: a straight-edged bilinear patch whose 16 control points
     * are bilinear grid samples — the exact tensor surface then EQUALS the
     * Coons surface our parser approximates with, so the oracle diff isolates
     * the plumbing, not the (documented) interior-point approximation.
     */
    private fun shadingTensor(): ByteArray {
        val c00 = 30.0 to 20.0
        val c03 = 180.0 to 40.0
        val c33 = 170.0 to 180.0
        val c30 = 20.0 to 160.0
        fun bilerp(u: Double, v: Double): Pair<Double, Double> {
            val bx = c00.first + (c03.first - c00.first) * u
            val by = c00.second + (c03.second - c00.second) * u
            val tx = c30.first + (c33.first - c30.first) * u
            val ty = c30.second + (c33.second - c30.second) * u
            return (bx + (tx - bx) * v) to (by + (ty - by) * v)
        }
        // 4x4 grid g[row v][col u].
        val g = Array(4) { i -> Array(4) { j -> bilerp(j / 3.0, i / 3.0) } }
        val order = listOf(
            g[0][0], g[0][1], g[0][2], g[0][3],  // bottom
            g[1][3], g[2][3], g[3][3],           // right
            g[3][2], g[3][1], g[3][0],           // top (r->l)
            g[2][0], g[1][0],                    // left (t->b)
            g[1][1], g[1][2], g[2][2], g[2][1],  // interior
        )
        val data = java.io.ByteArrayOutputStream()
        data.write(0)
        for ((x, y) in order) {
            coord16(data, x)
            coord16(data, y)
        }
        rgb8(data, 255, 0, 0)
        rgb8(data, 0, 255, 0)
        rgb8(data, 0, 0, 255)
        rgb8(data, 255, 255, 0)

        val p = Pdf()
        p.obj("<< /Type /Catalog /Pages 2 0 R >>")
        p.obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        p.obj(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] " +
                "/Resources << /Shading << /Sh1 5 0 R >> >> /Contents 4 0 R >>",
        )
        p.stream("", "q 0 0 200 200 re W n /Sh1 sh Q".encodeToByteArray())
        p.stream(
            "/ShadingType 7 /ColorSpace /DeviceRGB /BitsPerCoordinate 16 " +
                "/BitsPerComponent 8 /BitsPerFlag 8 " +
                "/Decode [0 200 0 200 0 1 0 1 0 1]",
            data.toByteArray(),
        )
        return p.build(1)
    }

    private fun text(): ByteArray {
        val p = Pdf()
        p.obj("<< /Type /Catalog /Pages 2 0 R >>")                              // 1
        p.obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")                      // 2
        p.obj(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        )                                                                       // 3
        p.stream(
            "",
            (
                "BT /F1 28 Tf 72 720 Td (KitePDF differential harness) Tj ET\n" +
                    "BT /F1 15 Tf 72 690 Td (The quick brown fox jumps over the lazy dog.) Tj ET\n" +
                    "0 0 1 rg 72 650 220 18 re f\n"
                ).encodeToByteArray(),
        )                                                                       // 4
        p.obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")         // 5
        return p.build(1)
    }

    private fun vector(): ByteArray {
        val p = Pdf()
        p.obj("<< /Type /Catalog /Pages 2 0 R >>")                              // 1
        p.obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")                      // 2
        p.obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 400] /Resources << >> /Contents 4 0 R >>") // 3
        p.stream(
            "",
            (
                "1 0 0 rg 50 50 120 120 re f\n" +
                    "0 0.6 0 RG 6 w 200 200 m 350 220 l 300 350 l S\n" +
                    "0 0 1 rg 200 60 m 320 60 260 180 360 180 c f\n"
                ).encodeToByteArray(),
        )                                                                       // 4
        return p.build(1)
    }

    private fun transparency(): ByteArray {
        val p = Pdf()
        p.obj("<< /Type /Catalog /Pages 2 0 R >>")                              // 1
        p.obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")                      // 2
        p.obj(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 400] " +
                "/Resources << /ExtGState << /GS1 5 0 R >> >> /Contents 4 0 R >>",
        )                                                                       // 3
        p.stream(
            "",
            (
                "1 0 0 rg 60 60 200 200 re f\n" +
                    "/GS1 gs\n" +
                    "0 0 1 rg 140 140 200 200 re f\n"
                ).encodeToByteArray(),
        )                                                                       // 4
        p.obj("<< /Type /ExtGState /ca 0.5 /CA 0.5 >>")                         // 5
        return p.build(1)
    }

    private fun multipage(): ByteArray {
        val p = Pdf()
        p.obj("<< /Type /Catalog /Pages 2 0 R >>")                              // 1
        p.obj("<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>")                // 2
        p.obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << /Font << /F1 7 0 R >> >> /Contents 5 0 R >>") // 3
        p.obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << /Font << /F1 7 0 R >> >> /Contents 6 0 R >>") // 4
        p.stream("", "BT /F1 24 Tf 40 150 Td (Page One) Tj ET\n1 0 0 rg 40 40 100 60 re f\n".encodeToByteArray()) // 5
        p.stream("", "BT /F1 24 Tf 40 150 Td (Page Two) Tj ET\n0 0 1 rg 160 40 100 60 re f\n".encodeToByteArray()) // 6
        p.obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")         // 7
        return p.build(1)
    }

    /** DeviceCMYK fills via the `k` operator — exercises CMYK→RGB conversion. */
    private fun cmyk(): ByteArray {
        val p = Pdf()
        p.obj("<< /Type /Catalog /Pages 2 0 R >>")                              // 1
        p.obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")                      // 2
        p.obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << >> /Contents 4 0 R >>") // 3
        p.stream(
            "",
            (
                "0 1 1 0 k 40 40 110 110 re f\n" +   // CMYK red
                    "1 0 0 0 k 150 150 110 110 re f\n" + // CMYK cyan
                    "0 0 0 1 k 40 150 110 110 re f\n"    // CMYK black
                ).encodeToByteArray(),
        )                                                                       // 4
        return p.build(1)
    }

    /** Rectangular clip (`W n`) then a full-page fill — only the clip window should paint. */
    private fun clip(): ByteArray {
        val p = Pdf()
        p.obj("<< /Type /Catalog /Pages 2 0 R >>")                              // 1
        p.obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")                      // 2
        p.obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << >> /Contents 4 0 R >>") // 3
        p.stream(
            "",
            (
                "q 70 70 160 160 re W n\n" +
                    "1 0 0 rg 0 0 300 300 re f\n" +
                    "Q\n"
                ).encodeToByteArray(),
        )                                                                       // 4
        return p.build(1)
    }

    /** Multiply blend mode via ExtGState — exercises the non-Normal compositing path. */
    private fun blendMultiply(): ByteArray {
        val p = Pdf()
        p.obj("<< /Type /Catalog /Pages 2 0 R >>")                              // 1
        p.obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")                      // 2
        p.obj(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] " +
                "/Resources << /ExtGState << /GS1 5 0 R >> >> /Contents 4 0 R >>",
        )                                                                       // 3
        p.stream(
            "",
            (
                "1 1 0 rg 50 50 160 160 re f\n" +    // yellow
                    "/GS1 gs\n" +
                    "0 1 1 rg 120 120 160 160 re f\n" // cyan × yellow → green overlap
                ).encodeToByteArray(),
        )                                                                       // 4
        p.obj("<< /Type /ExtGState /BM /Multiply >>")                           // 5
        return p.build(1)
    }

    /** Dashed strokes (`d` operator) — checks whether dash patterns are honoured. */
    private fun dash(): ByteArray {
        val p = Pdf()
        p.obj("<< /Type /Catalog /Pages 2 0 R >>")                              // 1
        p.obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")                      // 2
        p.obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << >> /Contents 4 0 R >>") // 3
        p.stream(
            "",
            (
                "0 0 0 RG 5 w [14 8] 0 d 30 150 m 270 150 l S\n" +
                    "0 0 1 RG 3 w [4 4] 0 d 30 100 m 270 100 l S\n"
                ).encodeToByteArray(),
        )                                                                       // 4
        return p.build(1)
    }

    /** Minimal classic-xref PDF writer. Object numbers are assigned in insertion order, 1-based. */
    private class Pdf {
        private val objects = mutableListOf<ByteArray>()

        fun obj(body: String): Int = obj(body.encodeToByteArray())

        fun obj(body: ByteArray): Int {
            objects.add(body)
            return objects.size
        }

        fun stream(dict: String, data: ByteArray): Int {
            val b = ByteArrayOutputStream()
            b.write("<< $dict /Length ${data.size} >>\nstream\n".encodeToByteArray())
            b.write(data)
            b.write("\nendstream".encodeToByteArray())
            return obj(b.toByteArray())
        }

        fun build(root: Int): ByteArray {
            val out = ByteArrayOutputStream()
            fun w(s: String) = out.write(s.encodeToByteArray())
            w("%PDF-1.7\n")
            out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte()))
            w("\n")
            val offsets = IntArray(objects.size + 1)
            for (i in objects.indices) {
                offsets[i + 1] = out.size()
                w("${i + 1} 0 obj\n")
                out.write(objects[i])
                w("\nendobj\n")
            }
            val xref = out.size()
            w("xref\n0 ${objects.size + 1}\n")
            w("0000000000 65535 f \n")
            for (i in 1..objects.size) w("${offsets[i].toString().padStart(10, '0')} 00000 n \n")
            w("trailer\n<< /Size ${objects.size + 1} /Root $root 0 R >>\nstartxref\n$xref\n%%EOF\n")
            return out.toByteArray()
        }
    }
}
