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
        // An image multiplied onto the light blue backdrop: no quadrant keeps its own colour (#113).
        image("image-multiply", "/BM /Multiply", budget = 0.005),
        // The same image at half alpha.
        image("image-alpha-multiply", "/ca 0.5 /BM /Multiply", budget = 0.005),
    )

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
