package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Inline resource colour spaces and sample boundaries (ISO 32000-1, 8.9.7, #114). */
class InlineImageColorSpaceTest {
    private val palette = "[/Indexed /DeviceRGB 2 <FF000000FF000000FF>]"

    private fun image(pdf: ByteArray) = TestPdf.calls(pdf)
        .filterIsInstance<RecordingCanvas.Call.Image>().single().image

    @Test
    fun indirect_named_palette_decodes_all_three_colours() {
        val pdf = TestPdf.onePage(
            "BI /W 3 /H 1 /BPC 8 /CS /CS0 /F /AHx ID 000102> EI",
            resources = "/ColorSpace << /CS0 5 0 R >>",
            extra = listOf(palette),
        )
        assertContentEquals(
            byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1, 0, 0, -1, -1),
            image(pdf).toRgbaBytes(),
        )
    }

    @Test
    fun an_unfiltered_named_space_keeps_embedded_ei_and_trailing_whitespace_samples() {
        val samples = byteArrayOf(32, 69, 73, 32, 9)
        // Lab and CalRGB are resource names here, not reserved inline names.
        for (name in listOf("CS0", "Lab", "CalRGB")) {
            val content = "BI /W 5 /H 1 /BPC 8 /CS /$name ID ".encodeToByteArray() + samples +
                " EI 1 0 0 rg 0 0 10 10 re f".encodeToByteArray()
            val pdf = TestPdf.build(listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << /ColorSpace << /$name /DeviceGray >> >> /Contents 4 0 R >>",
                TestPdf.Stream("", content),
            ))
            assertContentEquals(samples, image(pdf).pixelBytes, name)
            assertEquals(1, TestPdf.calls(pdf).filterIsInstance<RecordingCanvas.Call.Fill>().size, name)
        }
    }

    @Test
    fun a_form_uses_its_own_named_palette() {
        val pdf = TestPdf.onePage(
            "/Fm Do",
            resources = "/ColorSpace << /CS0 $palette >> /XObject << /Fm 5 0 R >>",
            extra = listOf(TestPdf.stream(
                "BI /W 1 /H 1 /BPC 8 /CS /CS0 /F /AHx ID 00> EI",
                "/Type /XObject /Subtype /Form /BBox [0 0 200 200] /Resources << /ColorSpace << /CS0 [/Indexed /DeviceRGB 0 <00FF00>] >> >>",
            )),
        )
        assertContentEquals(byteArrayOf(0, -1, 0, -1), image(pdf).toRgbaBytes())
    }

    @Test
    fun a_named_separation_space_uses_its_tint_transform() {
        val pdf = TestPdf.onePage(
            "BI /W 1 /H 1 /BPC 8 /CS /Spot /F /AHx ID FF> EI",
            resources = "/ColorSpace << /Spot [/Separation /SpotGreen /DeviceRGB << /FunctionType 2 /Domain [0 1] /C0 [1 1 1] /C1 [0 1 0] /N 1 >>] >>",
        )
        assertContentEquals(byteArrayOf(0, -1, 0, -1), image(pdf).toRgbaBytes())
    }

    @Test
    fun missing_named_space_still_salvages_the_rest_of_the_page() {
        val pdf = TestPdf.onePage(
            "BI /W 1 /H 1 /BPC 8 /CS /Missing /F /AHx ID 00> EI 0 0 10 10 re f",
        )
        assertEquals(1, TestPdf.calls(pdf).filterIsInstance<RecordingCanvas.Call.Fill>().size)
    }

    @Test
    fun a_wrong_component_count_rescans_from_the_data_start() {
        // An ICCBased stream without /N reads as three components, so the exact
        // length overshoots the real EI. The rest of the page must survive (#254).
        val samples = ByteArray(400) { 'A'.code.toByte() }
        val tail = buildString { append(" EI Q"); repeat(100) { append(" 0 0 1 1 re f") } }
        val content = "q 20 0 0 20 0 0 cm BI /W 20 /H 20 /BPC 8 /CS /CS0 ID ".encodeToByteArray() +
            samples + tail.encodeToByteArray()
        val pdf = TestPdf.build(listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << /ColorSpace << /CS0 [/ICCBased 5 0 R] >> >> /Contents 4 0 R >>",
            TestPdf.Stream("", content),
            TestPdf.Stream("", ByteArray(16)),
        ))
        val calls = TestPdf.calls(pdf)
        assertEquals(1, calls.filterIsInstance<RecordingCanvas.Call.Image>().size)
        assertEquals(100, calls.filterIsInstance<RecordingCanvas.Call.Fill>().size)
    }

    @Test
    fun text_extraction_ends_an_inline_image_where_the_renderer_does() {
        // The samples hold " EI (": a scan would stop there and read the string
        // opener into the following text operators (#266).
        val samples = byteArrayOf(32, 69, 73, 32, 40)
        val content = "BI /W 5 /H 1 /BPC 8 /CS /CS0 ID ".encodeToByteArray() + samples +
            " EI BT /F1 12 Tf 10 10 Td (secret) Tj ET".encodeToByteArray()
        val pdf = TestPdf.build(listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << /ColorSpace << /CS0 /DeviceGray >> " +
                "/Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> >> /Contents 4 0 R >>",
            TestPdf.Stream("", content),
        ))
        val page = PdfDocument.open(pdf).pages[0]
        assertTrue("secret" in io.github.yuroyami.kitepdf.text.TextExtractor.extract(page))
    }
}
