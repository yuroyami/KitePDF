package io.github.yuroyami.kitepdf.difftest

import java.io.ByteArrayOutputStream

/**
 * One-page PDFs, 200 by 200 points, that each paint one axial or radial shading
 * with `sh`. The tests of each backend render them and score the result against
 * mutool, so a backend that draws a gradient wrong fails on its own.
 */
object GradientFixtures {

    /** A named fixture and the most mean absolute error a backend may score on it. */
    data class Fixture(val name: String, val bytes: ByteArray, val budget: Double)

    /** Red to blue, linear in the shading parameter. */
    private const val RED_TO_BLUE = "<< /FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [0 0 1] /N 1 >>"

    fun all(): List<Fixture> = listOf(
        // A skewed CTM tilts the bands of an axial shading.
        fixture("axial-skewed", "1 0 1 1 20 0 cm", axial("0 0 100 0"), budget = 0.01),
        // A non-uniform CTM stretches the circles of a radial shading into ellipses.
        fixture("radial-stretched", "2 0 0 1 0 0 cm", radial("50 100 0 50 100 40"), budget = 0.01),
        // A scale along the axis only, which a backend that maps just the two end points also draws right.
        fixture("axial-scaled-along-axis", "2 0 0 1 0 0 cm", axial("0 0 100 0"), budget = 0.01),
    )

    private fun axial(coords: String, extend: String = "true true") =
        "<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [$coords] /Function $RED_TO_BLUE /Extend [$extend] >>"

    private fun radial(coords: String, extend: String = "true true") =
        "<< /ShadingType 3 /ColorSpace /DeviceRGB /Coords [$coords] /Function $RED_TO_BLUE /Extend [$extend] >>"

    /** A page that paints [shading] under [cm] over the whole page. */
    private fun fixture(name: String, cm: String, shading: String, budget: Double): Fixture {
        val content = "q 0 0 200 200 re W n $cm /Sh1 sh Q"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] " +
                "/Resources << /Shading << /Sh1 5 0 R >> >> /Contents 4 0 R >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            shading,
        )
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n".toByteArray())
        val offsets = objects.mapIndexed { i, body ->
            out.size().also { out.write("${i + 1} 0 obj\n$body\nendobj\n".toByteArray()) }
        }
        val xref = out.size()
        out.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".toByteArray())
        for (o in offsets) out.write("${o.toString().padStart(10, '0')} 00000 n \n".toByteArray())
        out.write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray())
        return Fixture(name, out.toByteArray(), budget)
    }
}
