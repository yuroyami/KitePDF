package sample

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder

/**
 * Builds tiny in-memory PDFs that exercise the KitePDF pipeline end-to-end.
 *
 * Constructing the bytes programmatically (rather than committing a hex blob)
 * keeps xref offsets correct as we tweak content — and shows roughly what a
 * minimal PDF actually looks like on the wire.
 */
object DemoPdf {

    val helloWorld: ByteArray by lazy { buildHelloWorld() }
    val twoPages: ByteArray by lazy { buildTwoPages() }
    val rectanglesAndText: ByteArray by lazy { buildRectanglesAndText() }
    val multipleFonts: ByteArray by lazy { buildMultipleFonts() }
    val clippedShapes: ByteArray by lazy { buildClippedShapes() }
    val imagePlaceholder: ByteArray by lazy { buildImagePlaceholder() }
    val cmykAndAnnotations: ByteArray by lazy { buildCmykAndAnnotations() }
    val transparencyAndBlending: ByteArray by lazy { buildTransparency() }

    private fun buildHelloWorld(): ByteArray {
        val b = Builder("1.4")
        b.addObject("<< /Type /Catalog /Pages 2 0 R >>")
        b.addObject("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        b.addObject(
            """<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]
            | /Resources << /Font << /F1 4 0 R >> >>
            | /Contents 5 0 R >>""".trimMargin(),
        )
        b.addObject("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        b.addStream(
            "<< /Length %LEN% >>",
            """BT
              |/F1 24 Tf
              |72 720 Td
              |(Hello, KitePDF!) Tj
              |0 -36 Td
              |(Pure-Kotlin PDF parsing + rendering.) Tj
              |ET""".trimMargin(),
        )
        return b.finish(rootRef = "1 0 R")
    }

    private fun buildTwoPages(): ByteArray {
        val b = Builder("1.4")
        b.addObject("<< /Type /Catalog /Pages 2 0 R >>")
        b.addObject("<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>")
        b.addObject(
            """<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]
              | /Resources << /Font << /F1 7 0 R >> >>
              | /Contents 4 0 R >>""".trimMargin(),
        )
        b.addStream("<< /Length %LEN% >>", """BT /F1 18 Tf 72 720 Td (Page one of two.) Tj ET""")
        b.addObject(
            """<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]
              | /Resources << /Font << /F1 7 0 R >> >>
              | /Contents 6 0 R >>""".trimMargin(),
        )
        b.addStream("<< /Length %LEN% >>", """BT /F1 18 Tf 72 720 Td (Page two of two.) Tj ET""")
        b.addObject("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        return b.finish(rootRef = "1 0 R")
    }

    /** Exercises the graphics state stack, colour, and path painting. */
    private fun buildRectanglesAndText(): ByteArray {
        val b = Builder("1.4")
        b.addObject("<< /Type /Catalog /Pages 2 0 R >>")
        b.addObject("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        b.addObject(
            """<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]
              | /Resources << /Font << /F1 4 0 R >> >>
              | /Contents 5 0 R >>""".trimMargin(),
        )
        b.addObject("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>")
        b.addStream(
            "<< /Length %LEN% >>",
            """% Header
              |q
              |0.10 0.30 0.55 rg
              |50 700 512 60 re
              |f
              |Q
              |BT /F1 28 Tf 1.0 1.0 1.0 rg 72 720 Td (KitePDF) Tj ET
              |% Three colour swatches
              |q
              |0.86 0.20 0.27 rg
              |50 600 100 60 re f
              |0.20 0.66 0.33 rg
              |170 600 100 60 re f
              |0.20 0.40 0.86 rg
              |290 600 100 60 re f
              |Q
              |BT /F1 14 Tf 0 0 0 rg 50 580 Td (Red) Tj 120 0 Td (Green) Tj 120 0 Td (Blue) Tj ET
              |% Border
              |q
              |2 w
              |0.20 0.20 0.20 RG
              |40 40 532 530 re
              |S
              |Q
              |BT /F1 12 Tf 0.30 0.30 0.30 rg 50 50 Td (Rendered with KitePDF v0.0.2 - pure Kotlin.) Tj ET""".trimMargin(),
        )
        return b.finish(rootRef = "1 0 R")
    }

    /** Exercises font fallback: Helvetica, Times-Roman, Courier, italic, bold. */
    private fun buildMultipleFonts(): ByteArray {
        val b = Builder("1.4")
        b.addObject("<< /Type /Catalog /Pages 2 0 R >>")
        b.addObject("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        b.addObject(
            """<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]
              | /Resources << /Font <<
              |   /Helv 4 0 R /Bold 5 0 R /Italic 6 0 R /Mono 7 0 R /Times 8 0 R
              | >> >>
              | /Contents 9 0 R >>""".trimMargin(),
        )
        b.addObject("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        b.addObject("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>")
        b.addObject("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Oblique >>")
        b.addObject("<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>")
        b.addObject("<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman >>")
        b.addStream(
            "<< /Length %LEN% >>",
            """BT
              |/Bold 22 Tf 72 740 Td (Standard 14 in action) Tj
              |/Helv 14 Tf 0 -40 Td (Helvetica regular: The quick brown fox.) Tj
              |/Italic 14 Tf 0 -24 Td (Helvetica oblique: jumps over the lazy dog.) Tj
              |/Times 14 Tf 0 -24 Td (Times Roman: a different serif typeface.) Tj
              |/Mono 14 Tf 0 -24 Td (Courier: monospaced for code.) Tj
              |/Helv 12 Tf 0 -40 Td (Each one is just a system font - real PDF font) Tj
              |/Helv 12 Tf 0 -16 Td (parsing is a Session-3 deliverable.) Tj
              |ET""".trimMargin(),
        )
        return b.finish(rootRef = "1 0 R")
    }

    /** Two filled rectangles partially clipped by a smaller rectangle — exercises W operator. */
    private fun buildClippedShapes(): ByteArray {
        val b = Builder("1.4")
        b.addObject("<< /Type /Catalog /Pages 2 0 R >>")
        b.addObject("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        b.addObject(
            """<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]
              | /Resources << /Font << /F1 4 0 R >> >>
              | /Contents 5 0 R >>""".trimMargin(),
        )
        b.addObject("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        b.addStream(
            "<< /Length %LEN% >>",
            """BT /F1 18 Tf 0 0 0 rg 72 740 Td (Clipping demo: two squares clipped by a circle-ish path) Tj ET
              |% A diamond clip
              |q
              |200 400 m
              |400 200 l
              |600 400 l
              |400 600 l
              |h W n
              |% Now draw the two coloured squares; only the diamond-shaped area shows.
              |1 0.4 0.2 rg
              |150 350 200 200 re f
              |0.2 0.4 1 rg
              |400 350 200 200 re f
              |Q
              |BT /F1 12 Tf 0.3 0.3 0.3 rg 72 100 Td (The orange/blue squares are clipped to a diamond.) Tj ET""".trimMargin(),
        )
        return b.finish(rootRef = "1 0 R")
    }

    /** CMYK colours + annotations — exercises both v0.0.4 colour ops and the annotation layer. */
    private fun buildCmykAndAnnotations(): ByteArray {
        val b = Builder("1.4")
        b.addObject("<< /Type /Catalog /Pages 2 0 R >>")
        b.addObject("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        b.addObject(
            """<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]
              | /Resources << /Font << /F1 4 0 R >> >>
              | /Annots [6 0 R 7 0 R]
              | /Contents 5 0 R >>""".trimMargin(),
        )
        b.addObject("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>")
        b.addStream(
            "<< /Length %LEN% >>",
            """BT /F1 22 Tf 0 0 0 rg 72 720 Td (CMYK + Annotations demo) Tj ET
              |% CMYK fill ("k" operator) — cyan square
              |1 0 0 0 k
              |72 600 80 80 re f
              |% Magenta square
              |0 1 0 0 k
              |172 600 80 80 re f
              |% Yellow square
              |0 0 1 0 k
              |272 600 80 80 re f
              |% Black square
              |0 0 0 1 k
              |372 600 80 80 re f
              |BT /F1 12 Tf 0 g 72 570 Td (Cyan, magenta, yellow, black via the k operator.) Tj ET
              |BT /F1 14 Tf 0 g 72 500 Td (Click the link below \\(if your viewer is interactive\\):) Tj ET
              |BT /F1 14 Tf 0 0 0.8 rg 72 470 Td (https://github.com/yuroyami/KitePDF) Tj ET
              |BT /F1 14 Tf 0 g 72 400 Td (And the highlighted phrase below uses an annotation:) Tj ET
              |BT /F1 14 Tf 0 g 72 370 Td (THIS PHRASE IS HIGHLIGHTED) Tj ET""".trimMargin(),
        )
        // Link annotation over the URL line
        b.addObject(
            """<< /Type /Annot /Subtype /Link /Rect [72 465 350 485]
              | /A << /Type /Action /S /URI /URI (https://github.com/yuroyami/KitePDF) >>
              | /C [0 0 0.8] >>""".trimMargin(),
        )
        // Highlight annotation over the highlighted phrase
        b.addObject(
            """<< /Type /Annot /Subtype /Highlight /Rect [72 365 350 388]
              | /C [1 0.95 0.3]
              | /Contents (a yellow highlight overlay) >>""".trimMargin(),
        )
        return b.finish(rootRef = "1 0 R")
    }

    /**
     * Transparency + blend modes — exercises the `gs` operator with /ca alpha
     * and /BM blend modes from a /Resources /ExtGState dict.
     */
    private fun buildTransparency(): ByteArray {
        val b = Builder("1.4")
        b.addObject("<< /Type /Catalog /Pages 2 0 R >>")
        b.addObject("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        b.addObject(
            """<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]
              | /Resources <<
              |   /Font << /F1 4 0 R >>
              |   /ExtGState <<
              |     /GHalf << /ca 0.5 >>
              |     /GMult << /BM /Multiply >>
              |     /GScreen << /BM /Screen >>
              |   >>
              | >>
              | /Contents 5 0 R >>""".trimMargin(),
        )
        b.addObject("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>")
        b.addStream(
            "<< /Length %LEN% >>",
            """BT /F1 22 Tf 0 0 0 rg 72 740 Td (Transparency + blend modes) Tj ET
              |% Three overlapping circles with /ca 0.5 + RGB primaries.
              |/GHalf gs
              |1 0 0 rg
              |200 500 100 100 re f
              |0 1 0 rg
              |270 500 100 100 re f
              |0 0 1 rg
              |235 440 100 100 re f
              |% Multiply blend on a yellow rectangle over a cyan one.
              |q
              |0 1 1 rg
              |100 250 200 100 re f
              |/GMult gs
              |1 1 0 rg
              |150 220 200 100 re f
              |Q
              |BT /F1 12 Tf 0 g 100 200 Td (Multiply blend mode over solid cyan.) Tj ET
              |% Screen blend on dark backdrop.
              |q
              |0.1 0.1 0.3 rg
              |320 250 200 100 re f
              |/GScreen gs
              |1 0.6 0.4 rg
              |360 220 200 100 re f
              |Q
              |BT /F1 12 Tf 0.3 0.3 0.3 rg 320 200 Td (Screen blend lightens its backdrop.) Tj ET""".trimMargin(),
        )
        return b.finish(rootRef = "1 0 R")
    }

    /** A page with an XObject Image reference — exercises the Do operator + ImageXObject path. */
    private fun buildImagePlaceholder(): ByteArray {
        val b = Builder("1.4")
        b.addObject("<< /Type /Catalog /Pages 2 0 R >>")
        b.addObject("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        b.addObject(
            """<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]
              | /Resources <<
              |   /Font << /F1 4 0 R >>
              |   /XObject << /Im1 5 0 R >>
              | >>
              | /Contents 6 0 R >>""".trimMargin(),
        )
        b.addObject("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        // Fake JPEG XObject — the bytes don't have to be a valid JPEG, the
        // renderer just classifies the filter and draws a placeholder in
        // v0.0.3 (real decoding lands in Session 4).
        val fakeJpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        b.addStream(
            """<< /Type /XObject /Subtype /Image
              | /Width 400 /Height 300
              | /BitsPerComponent 8 /ColorSpace /DeviceRGB
              | /Filter /DCTDecode /Length %LEN% >>""".trimMargin(),
            fakeJpegBytes.decodeToString(),  // hack: streams are bytes, our builder takes strings
        )
        b.addStream(
            "<< /Length %LEN% >>",
            """BT /F1 18 Tf 0 0 0 rg 72 740 Td (Image XObject demo: the box below is a placeholder) Tj ET
              |q
              |400 0 0 300 100 380 cm
              |/Im1 Do
              |Q
              |BT /F1 12 Tf 0.3 0.3 0.3 rg 72 100 Td (The image XObject scaffolding is wired; pixel decoding is Session 4.) Tj ET""".trimMargin(),
        )
        return b.finish(rootRef = "1 0 R")
    }
}

/** Minimal PDF writer: tracks per-object byte offsets so the xref table is exact. */
private class Builder(version: String) {
    private val buf = ByteArrayBuilder(1024)
    private val offsets = mutableListOf<Int>()
    private var objCounter = 0

    init {
        write("%PDF-$version\n%Äå\n")
    }

    fun addObject(body: String): Int {
        objCounter++
        offsets.add(buf.size())
        write("$objCounter 0 obj\n$body\nendobj\n")
        return objCounter
    }

    fun addStream(dictTemplate: String, payload: String): Int {
        objCounter++
        offsets.add(buf.size())
        val payloadBytes = payload.encodeToByteArray()
        val dict = dictTemplate.replace("%LEN%", payloadBytes.size.toString())
        write("$objCounter 0 obj\n$dict\nstream\n")
        buf.append(payloadBytes)
        write("\nendstream\nendobj\n")
        return objCounter
    }

    fun finish(rootRef: String): ByteArray {
        val xrefOffset = buf.size()
        write("xref\n0 ${offsets.size + 1}\n")
        write("0000000000 65535 f \n")
        for (off in offsets) {
            val padded = off.toString().padStart(10, '0')
            write("$padded 00000 n \n")
        }
        write("trailer\n<< /Size ${offsets.size + 1} /Root $rootRef >>\nstartxref\n$xrefOffset\n%%EOF\n")
        return buf.toByteArray()
    }

    private fun write(s: String) = buf.append(s.encodeToByteArray())
}
