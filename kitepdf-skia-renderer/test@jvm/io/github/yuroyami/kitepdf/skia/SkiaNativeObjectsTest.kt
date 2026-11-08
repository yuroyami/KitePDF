package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.PdfDocument
import org.jetbrains.skia.impl.Stats
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A render closes the native objects each draw makes, its paints, path effects, fonts, text
 * blobs and shaders, when the draw returns, so none waits for Skiko's cleaner (#881).
 */
class SkiaNativeObjectsTest {

    private fun pdf(content: String): ByteArray {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R " +
                "/Resources << /Font << /F1 5 0 R >> /Shading << /Sh 6 0 R >> >> >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [0 0 100 0] /Function << /FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [0 0 1] /N 1 >> /Extend [true true] >>",
        )
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, body -> sb.length.also { sb.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun a_render_leaves_no_paint_font_blob_or_shader_to_the_cleaner() {
        val content = buildString {
            for (i in 0 until 200) append("0.2 0.4 0.6 rg ${i % 20 * 30} ${i / 20 * 30} 10 10 re f\n")
            for (i in 0 until 40) append("BT /F1 9 Tf 20 ${400 + i * 9} Td (Hello world $i) Tj ET\n")
            for (i in 0 until 20) append("[3 2] 0 d 0 0 0 RG 1 w 300 ${400 + i * 10} m 500 ${400 + i * 10} l S\n")
            append("q 300 300 100 50 re W n /Sh sh Q\n")
        }
        val page = PdfDocument.open(pdf(content)).pages[0]
        val watched = listOf("Paint", "PathEffect", "Font", "TextBlob", "TextBlobBuilder", "Shader", "ColorFilter")
        val was = Stats.enabled
        Stats.enabled = true
        try {
            val before = HashMap(Stats.allocated)
            repeat(3) { PdfPageRasterizer.renderToImage(page, 1.0, 0xFFFFFFFF.toInt()).close() }
            val after = HashMap(Stats.allocated)
            val grown = watched.associateWith { (after[it] ?: 0) - (before[it] ?: 0) }.filterValues { it > 0 }
            assertTrue(grown.isEmpty(), "native objects left open by three renders: $grown (all: $after)")
            // The run did draw these kinds, or the check above is empty.
            assertTrue(Stats.allocated.keys.isNotEmpty(), "Skiko counted nothing")
        } finally {
            Stats.enabled = was
        }
    }
}
