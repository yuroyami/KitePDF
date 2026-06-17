package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.math.cos
import kotlin.math.sin

/**
 * Feature-diverse fixtures produced by KitePDF's own write engine
 * ([PdfBuilder] + `ContentStreamBuilder`). They multiply the harness's
 * rendering coverage with content we control, and form a dogfooding loop: the
 * writer must produce them, MuPDF must accept them, and KitePDF must render
 * them ≈ MuPDF. CMYK / bezier / dash ops use the content builder's `raw`
 * escape hatch.
 */
object GeneratedPdfs {

    fun all(): List<SyntheticPdfs.Fixture> = listOf(
        fx("gen-standard14-fonts", standard14Fonts()),
        fx("gen-text-sizes", textSizes()),
        fx("gen-rgb-swatches", rgbSwatches()),
        fx("gen-gray-ramp", grayRamp()),
        fx("gen-cmyk-swatches", cmykSwatches()),
        fx("gen-vector-paths", vectorPaths()),
        fx("gen-transforms", transforms()),
        fx("gen-nested-gstate", nestedGState()),
        fx("gen-paragraph", paragraph()),
        fx("gen-multipage-mixed", multipageMixed()),
    )

    private fun fx(name: String, bytes: ByteArray) = SyntheticPdfs.Fixture(name, bytes)

    /** The 12 Latin standard-14 fonts, one labelled line each. */
    private fun standard14Fonts(): ByteArray = PdfBuilder().page {
        var y = 744.0
        for (f in StandardFont.entries) {
            if (f == StandardFont.Symbol || f == StandardFont.ZapfDingbats) continue
            text(f, 15.0, 56.0, y, "${f.baseFont}: The quick brown fox 0123")
            y -= 26.0
        }
    }.build()

    private fun textSizes(): ByteArray = PdfBuilder().page {
        var y = 740.0
        for (size in listOf(6.0, 8.0, 10.0, 12.0, 16.0, 20.0, 28.0, 40.0, 56.0)) {
            text(StandardFont.Helvetica, size, 56.0, y, "Size ${size.toInt()} — Hamburgefonstiv")
            y -= size + 12.0
        }
    }.build()

    private fun rgbSwatches(): ByteArray = PdfBuilder().page {
        val colors = listOf(
            Triple(1.0, 0.0, 0.0), Triple(0.0, 1.0, 0.0), Triple(0.0, 0.0, 1.0),
            Triple(1.0, 1.0, 0.0), Triple(1.0, 0.0, 1.0), Triple(0.0, 1.0, 1.0),
            Triple(1.0, 0.5, 0.0), Triple(0.5, 0.0, 1.0), Triple(0.2, 0.7, 0.4),
        )
        var x = 56.0
        var y = 640.0
        for ((i, c) in colors.withIndex()) {
            setFillRgb(c.first, c.second, c.third)
            rectangle(x, y, 120.0, 90.0)
            fill()
            x += 150.0
            if ((i + 1) % 3 == 0) { x = 56.0; y -= 120.0 }
        }
    }.build()

    private fun grayRamp(): ByteArray = PdfBuilder().page {
        var x = 40.0
        for (step in 0..10) {
            setFillGray(step / 10.0)
            rectangle(x, 400.0, 46.0, 200.0)
            fill()
            x += 48.0
        }
    }.build()

    private fun cmykSwatches(): ByteArray = PdfBuilder().page {
        // DeviceCMYK fills via the raw escape hatch (the builder is RGB/gray-typed).
        raw("1 0 0 0 k 56 600 120 120 re f")
        raw("0 1 0 0 k 200 600 120 120 re f")
        raw("0 0 1 0 k 344 600 120 120 re f")
        raw("0 0 0 1 k 56 460 120 120 re f")
        raw("0.6 0.1 0.0 0.1 k 200 460 120 120 re f")
        raw("0.1 0.7 0.9 0.0 k 344 460 120 120 re f")
    }.build()

    private fun vectorPaths(): ByteArray = PdfBuilder().page {
        setFillRgb(0.85, 0.2, 0.2)
        rectangle(56.0, 600.0, 180.0, 120.0)
        fill()
        setStrokeRgb(0.1, 0.4, 0.9)
        setLineWidth(6.0)
        moveTo(280.0, 600.0); lineTo(460.0, 640.0); lineTo(400.0, 720.0); closePath(); stroke()
        // Bezier + dashed stroke via raw.
        raw("0 0.6 0.2 rg 56 420 m 200 420 120 560 320 540 c f")
        raw("0.2 0.2 0.2 RG 3 w [10 5] 0 d 56 380 m 460 380 l S")
    }.build()

    private fun transforms(): ByteArray = PdfBuilder().page {
        val cx = 306.0
        val cy = 420.0
        for ((i, deg) in listOf(0, 20, 40, 60, 80).withIndex()) {
            val r = deg * 3.14159265 / 180.0
            save()
            // Rotate about (cx,cy): T(cx,cy) · R(deg) baked into one cm.
            transform(cos(r), sin(r), -sin(r), cos(r), cx, cy)
            val shade = i / 5.0
            setFillRgb(0.2 + shade * 0.6, 0.3, 0.9 - shade * 0.6)
            rectangle(0.0, 0.0, 200.0, 20.0)
            fill()
            text(StandardFont.HelveticaBold, 13.0, 6.0, 4.0, "rotated ${deg}deg")
            restore()
        }
    }.build()

    private fun nestedGState(): ByteArray = PdfBuilder().page {
        save()
        transform(1.0, 0.0, 0.0, 1.0, 80.0, 600.0)
        setFillRgb(0.9, 0.6, 0.1)
        rectangle(0.0, 0.0, 120.0, 80.0); fill()
        save()
        transform(0.7, 0.0, 0.0, 0.7, 140.0, -40.0)
        setFillRgb(0.1, 0.5, 0.8)
        rectangle(0.0, 0.0, 120.0, 80.0); fill()
        save()
        transform(0.7, 0.0, 0.0, 0.7, 140.0, -40.0)
        setFillRgb(0.4, 0.8, 0.3)
        rectangle(0.0, 0.0, 120.0, 80.0); fill()
        restore(); restore(); restore()
        text(StandardFont.Helvetica, 12.0, 56.0, 720.0, "nested q/Q + cm")
    }.build()

    private fun paragraph(): ByteArray = PdfBuilder().page {
        beginText()
        setFont(StandardFont.TimesRoman, 12.0)
        moveText(56.0, 700.0)
        setLeading(16.0)
        showText("Justified-ish paragraph rendered through the content builder,")
        raw("(exercising the apostrophe operator for line advances,) '")
        raw("(text leading, and multiple show operations in one BT block.) '")
        raw("(Final line of the paragraph sample.) '")
        endText()
    }.build()

    private fun multipageMixed(): ByteArray = PdfBuilder()
        .page { text(StandardFont.HelveticaBold, 26.0, 72.0, 700.0, "Page 1 — title text") }
        .page {
            setFillRgb(0.2, 0.5, 0.9); rectangle(72.0, 500.0, 300.0, 160.0); fill()
            text(StandardFont.Helvetica, 14.0, 72.0, 470.0, "Page 2 — a filled rectangle")
        }
        .page {
            setStrokeGray(0.0); setLineWidth(2.0)
            moveTo(72.0, 680.0); lineTo(520.0, 680.0); stroke()
            text(StandardFont.Courier, 12.0, 72.0, 700.0, "Page 3 — courier + a rule")
        }
        .page(width = 400.0, height = 600.0) {
            text(StandardFont.TimesItalic, 18.0, 40.0, 540.0, "Page 4 — a custom-size page")
        }
        .page { text(StandardFont.Helvetica, 10.0, 72.0, 700.0, "Page 5 — the end") }
        .build()
}
