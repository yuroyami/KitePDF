package io.github.yuroyami.kitepdf.difftest

/**
 * One-page PDFs, 200 by 200 points, whose colours depend on how a colour space is
 * read. The tests of each backend render them and score the result against mutool.
 */
object ColorFixtures {

    fun all(): List<OracleFixture> = listOf(
        // ISO 32000-1, 8.6.5.6: DefaultGray and DefaultRGB replace the device spaces that
        // a fill, an image and a shading select. Gamma 1 reads each level as linear light.
        oracleFixture(
            "default-colour-spaces",
            "0.5 g 10 110 80 80 re f 0.8 0.4 0.2 rg 110 110 80 80 re f " +
                "q 80 0 0 80 10 10 cm /Im1 Do Q q 110 10 80 80 re W n /Sh1 sh Q",
            "/ColorSpace << /DefaultGray [/CalGray << /WhitePoint [0.9505 1 1.089] /Gamma 1 >>] " +
                "/DefaultRGB [/CalRGB << /WhitePoint [0.9505 1 1.089] /Gamma [1 1 1] " +
                "/Matrix [0.4124 0.2126 0.0193 0.3576 0.7152 0.1192 0.1805 0.0722 0.9505] >>] >> " +
                "/XObject << /Im1 5 0 R >> /Shading << /Sh1 6 0 R >>",
            listOf(
                pdfStream(
                    "20 60 A0 E0>".toByteArray(),
                    "/Type /XObject /Subtype /Image /Width 2 /Height 2 /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /ASCIIHexDecode",
                ),
                ("<< /ShadingType 2 /ColorSpace /DeviceGray /Coords [110 0 190 0] /Extend [true true] " +
                    "/Function << /FunctionType 2 /Domain [0 1] /C0 [0] /C1 [1] /N 1 >> >>").toByteArray(),
            ),
            budget = 0.005,
        ),
        // ISO 32000-1, 8.6.7: under overprint mode 1, an ink that a DeviceCMYK paint sets to 0
        // keeps the backdrop's ink (#201). Cyan over yellow is green, for a fill and a stroke; a
        // magenta fill with /OPM 0 replaces the yellow under it.
        oracleFixture(
            "overprint-cmyk-mode-1",
            "0 0 1 0 k 10 10 180 180 re f " +
                "/GS1 gs 1 0 0 0 k 30 110 60 60 re f 1 0 0 0 K 12 w 140 100 m 140 190 l S " +
                "/GS2 gs 0 1 0 0 k 30 30 60 60 re f",
            "/ExtGState << /GS1 << /OP true /op true /OPM 1 >> /GS2 << /OP true /op true /OPM 0 >> >>",
            emptyList(),
            budget = 0.005,
        ),
        oracleFixture(
            "overprint-cmyk-host-text",
            "0 0 1 0 k 10 10 180 180 re f /GS1 gs 1 0 0 0 k BT /F1 40 Tf 30 70 Td (Ink) Tj ET",
            "/Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> " +
                "/ExtGState << /GS1 << /op true /OPM 1 >> >>", emptyList(), 0.005,
        ),
    ) + overprintText() + listOf(0, 1).flatMap { mode ->
        listOf(
            oracleFixture(
                "overprint-spot-fill-mode-$mode",
                "1 0 0 0 k 10 10 180 180 re f /GS1 gs /Spot cs 1 scn 30 110 60 60 re f " +
                    "0.5 scn 110 110 60 60 re f 0 scn 30 30 60 60 re f",
                "$SPOT /ExtGState << /GS1 << /OP true /op true /OPM $mode >> >>", emptyList(), 0.005,
            ),
            oracleFixture(
                "overprint-devicen-stroke-mode-$mode",
                "1 0 0 0 k 10 10 180 180 re f /GS1 gs /Spot CS 1 SCN 20 w 50 30 m 50 170 l S " +
                    "0.5 SCN 140 30 m 140 170 l S",
                SPOT.replace("/Separation /Orange", "/DeviceN [/Orange]") +
                    " /ExtGState << /GS1 << /OP true /op false /OPM $mode >> >>", emptyList(), 0.005,
            ),
            oracleFixture(
                "overprint-spot-image-mode-$mode",
                "1 0 0 0 k 10 10 180 180 re f /GS1 gs q 160 0 0 160 20 20 cm /Im1 Do Q",
                "$SPOT /XObject << /Im1 5 0 R >> /ExtGState << /GS1 << /op true /OPM $mode >> >>",
                listOf(pdfStream(byteArrayOf(0, 85, 170.toByte(), 255.toByte()),
                    "/Type /XObject /Subtype /Image /Width 2 /Height 2 /ColorSpace $SPOT_SPACE /BitsPerComponent 8 /Decode [1 0]")), 0.005,
            ),
            oracleFixture(
                "overprint-devicen-image-mode-$mode",
                "0 1 0 0 k 10 10 180 180 re f /GS1 gs q 160 0 0 160 20 20 cm /Im1 Do Q",
                "/XObject << /Im1 5 0 R >> /ExtGState << /GS1 << /op true /OPM $mode >> >>",
                listOf(
                    pdfStream(byteArrayOf(255.toByte(), 255.toByte(), 0, 85, 128.toByte(), 170.toByte(), 255.toByte(), 0),
                        "/Type /XObject /Subtype /Image /Width 2 /Height 2 /ColorSpace [/DeviceN [/Cyan /Orange] /DeviceCMYK 6 0 R] /BitsPerComponent 8"),
                    pdfStream("{ dup 0.5 mul exch 0 }".toByteArray(),
                        "/FunctionType 4 /Domain [0 1 0 1] /Range [0 1 0 1 0 1 0 1]"),
                ), 0.005,
            ),
        )
    }

    private const val SPOT_SPACE = "[/Separation /Orange /DeviceCMYK " +
        "<< /FunctionType 2 /Domain [0 1] /C0 [0 0 0 0] /C1 [0 0.5 1 0] /N 1 >>]"
    private const val SPOT = "/ColorSpace << /Spot $SPOT_SPACE >>"

    /** An embedded square glyph separates ink errors from differences between host fonts. */
    private fun overprintText(): List<OracleFixture> = listOf(
        Triple("overprint-cmyk-text-fill", "0 0 1 0 k", "1 0 0 0 k 0 Tr"),
        Triple("overprint-cmyk-text-stroke", "0 0 1 0 k", "1 0 0 0 K 6 w 1 Tr"),
        Triple("overprint-spot-text", "1 0 0 0 k", "/Spot cs 1 scn 0 Tr"),
    ).map { (name, background, ink) ->
        oracleFixture(
            name, "$background 10 10 180 180 re f /GS1 gs $ink BT /F1 100 Tf 30 60 Td (AA) Tj ET",
            "$SPOT /Font << /F1 5 0 R >> /ExtGState << /GS1 << /OP true /op true /OPM 1 >> >>",
            listOf(
                ("<< /Type /Font /Subtype /TrueType /BaseFont /Square /FirstChar 65 /LastChar 65 /Widths [600] " +
                    "/FontDescriptor 6 0 R /Encoding /WinAnsiEncoding >>").toByteArray(),
                ("<< /Type /FontDescriptor /FontName /Square /Flags 32 /FontBBox [0 0 500 500] /ItalicAngle 0 " +
                    "/Ascent 500 /Descent 0 /CapHeight 500 /StemV 80 /FontFile2 7 0 R >>").toByteArray(),
                pdfStream(GradientFixtures.squareFont()),
            ), 0.005,
        )
    }
}
