package io.github.yuroyami.kitepdf.difftest

/**
 * One-page PDFs that each draw one transparency group, soft mask or blended image. The
 * tests of each backend render them and score the result against mutool, so a backend
 * that composites in its own way fails on its own.
 */
object GroupFixtures {

    fun all(): List<OracleFixture> = listOf(
        // Two overlapping red squares in a group at half alpha: the overlap is no darker than the rest.
        group("group-alpha", "/ca 0.5", budget = 0.005),
        // The same group multiplied onto a light blue backdrop.
        group("group-multiply", "/BM /Multiply", budget = 0.005),
        // Both at once.
        group("group-alpha-multiply", "/ca 0.5 /BM /Multiply", budget = 0.005),
        // An alpha soft mask whose group paints black at half alpha: the content shows at half alpha,
        // and the black of the mask group never reaches the page.
        oracleFixture(
            "soft-mask-alpha",
            "0.5 0.5 1 rg 0 0 200 200 re f /GS1 gs 1 0 0 rg 40 40 120 120 re f",
            "/ExtGState << /GS1 << /SMask << /S /Alpha /G 5 0 R >> >> >>",
            listOf(form("0 g /GH gs 0 0 200 200 re f", "/ExtGState << /GH << /ca 0.5 >> >>")),
            budget = 0.005,
        ),
        // A luminosity mask on a white backdrop: its group paints black in its own box, so the red
        // page shows everywhere except in that box (#68).
        oracleFixture(
            "soft-mask-backdrop",
            "/GS1 gs 1 0 0 rg 0 0 200 200 re f",
            "/ExtGState << /GS1 << /SMask << /S /Luminosity /BC [1] /G 5 0 R >> >> >>",
            listOf(
                pdfStream(
                    "0 g 40 40 80 80 re f".toByteArray(),
                    "/Type /XObject /Subtype /Form /BBox [40 40 120 120] /Group << /S /Transparency /CS /DeviceGray >>",
                ),
            ),
            budget = 0.005,
        ),
        // A luminosity mask with an inverting transfer function: its group paints white in a square,
        // so the red page shows everywhere except in that square (#68).
        oracleFixture(
            "soft-mask-transfer",
            "/GS1 gs 1 0 0 rg 0 0 200 200 re f",
            "/ExtGState << /GS1 << /SMask << /S /Luminosity /G 5 0 R $INVERTER >> >> >>",
            listOf(form("1 g 40 40 80 80 re f", "")),
            budget = 0.005,
        ),
        // An alpha mask with the same transfer function: the square of the group hides the page.
        oracleFixture(
            "soft-mask-alpha-transfer",
            "/GS1 gs 1 0 0 rg 0 0 200 200 re f",
            "/ExtGState << /GS1 << /SMask << /S /Alpha /G 5 0 R $INVERTER >> >> >>",
            listOf(form("0 g 40 40 80 80 re f", "")),
            budget = 0.005,
        ),
        // An image multiplied onto the light blue backdrop: no quadrant keeps its own colour (#113).
        image("image-multiply", "/BM /Multiply", budget = 0.005),
        // The same image at half alpha.
        image("image-alpha-multiply", "/ca 0.5 /BM /Multiply", budget = 0.005),
    )

    /** A /TR entry that inverts each mask value: 1 becomes 0 and 0 becomes 1. */
    private const val INVERTER = "/TR << /FunctionType 2 /Domain [0 1] /C0 [1] /C1 [0] /N 1 >>"

    /** Red, green, blue and yellow in a square of two by two pixels. */
    private val FOUR_COLOURS = byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, 0)

    /** A light blue page, then [FOUR_COLOURS] enlarged to 100 points under the graphics state [state]. */
    private fun image(name: String, state: String, budget: Double): OracleFixture = oracleFixture(
        name,
        "0.5 0.5 1 rg 0 0 200 200 re f q /GS1 gs 100 0 0 100 50 50 cm /Im1 Do Q",
        "/ExtGState << /GS1 << $state >> >> /XObject << /Im1 5 0 R >>",
        listOf(pdfStream(FOUR_COLOURS, "/Type /XObject /Subtype /Image /Width 2 /Height 2 /ColorSpace /DeviceRGB /BitsPerComponent 8")),
        budget,
    )

    /** A light blue page, then a group of two overlapping red squares drawn under the graphics state [state]. */
    private fun group(name: String, state: String, budget: Double): OracleFixture = oracleFixture(
        name,
        "0.5 0.5 1 rg 0 0 200 200 re f /GS1 gs /Fm1 Do",
        "/ExtGState << /GS1 << $state >> >> /XObject << /Fm1 5 0 R >>",
        listOf(form("1 0 0 rg 40 40 80 80 re f 80 80 80 80 re f", "")),
        budget,
    )

    /** A transparency group form over the whole page that paints [content] with [resources]. */
    private fun form(content: String, resources: String): ByteArray = pdfStream(
        content.toByteArray(),
        "/Type /XObject /Subtype /Form /BBox [0 0 200 200] /Group << /S /Transparency >> /Resources << $resources >>",
    )
}
