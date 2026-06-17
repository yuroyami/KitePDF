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
    )

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
