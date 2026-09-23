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
    )
}
