package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.render.KiteBitmapCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A soft mask's transfer function maps each mask value before it gates the content (ISO
 * 32000-1, 11.6.5.2, Table 144). For `f(x) = x * x` a mask value of 0.5 gates at 0.25. The
 * Compose canvas used the straight line closest to the table and gated at about 0.33 (#414).
 */
class SoftMaskTransferTest {

    /**
     * The soft masks of the tests, each 0.5 everywhere: an alpha mask whose group fills black at
     * alpha 0.5, a luminosity mask whose group fills a grey of 0.5, and a luminosity mask whose
     * group fills white at alpha 0.5 over the black backdrop.
     */
    private val masks = listOf(
        "Alpha" to "/GS2 gs 0 g 0 0 100 100 re f",
        "Luminosity" to "0.5 g 0 0 100 100 re f",
        "Luminosity" to "/GS2 gs 1 g 0 0 100 100 re f",
    )

    /** A 100 x 100 page that fills black through a soft mask of [kind], whose group draws [group], with the transfer x * x. */
    private fun maskedPdf(kind: String, group: String): PdfDocument {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        add(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 4 0 R " +
                "/Resources << /ExtGState << /GS1 << /SMask << /Type /Mask /S /$kind /G 5 0 R /TR 6 0 R >> >> >> >> >>",
        )
        val content = "/GS1 gs 0 g 0 0 100 100 re f"
        add("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        add(
            "<< /Type /XObject /Subtype /Form /BBox [0 0 100 100] /Group << /S /Transparency /CS /DeviceRGB >> " +
                "/Resources << /ExtGState << /GS2 << /ca 0.5 >> >> >> /Length ${group.length} >>\nstream\n$group\nendstream",
        )
        add("<< /FunctionType 2 /Domain [0 1] /C0 [0] /C1 [1] /N 2 >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    private fun rasterizer(): KitePageRasterizer {
        val density = Density(1f)
        return KitePageRasterizer(density, LayoutDirection.Ltr, TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr))
    }

    @Test
    fun a_curved_transfer_gates_the_content_by_its_table() {
        for ((kind, group) in masks) {
            val page = maskedPdf(kind, group).pages[0]
            val pixel = onTestUiThread { rasterizer().rasterize(page, 100, 100) }.toPixelMap()[50, 50]
            // Black gated at 0.25 over white paper leaves 0.75. The line fit gave about 0.67.
            assertEquals(0.75f, pixel.red, 0.02f, "$kind mask of $group: $pixel")
        }
    }

    /**
     * Where no colour filter takes the whole table, as on Android, the canvas gates by the mask
     * group's pixels and gives the same page (#445).
     */
    @Test
    fun a_curved_transfer_gates_the_same_without_a_table_filter() {
        for ((kind, group) in masks) {
            val page = maskedPdf(kind, group).pages[0]
            val bitmap = androidx.compose.ui.graphics.ImageBitmap(100, 100)
            val density = Density(1f)
            val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().drawOnTestUiThread(
                density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap), androidx.compose.ui.geometry.Size(100f, 100f),
            ) {
                drawRect(androidx.compose.ui.graphics.Color.White)
                val canvas = ComposeCanvas(this, measurer, 1f, false, 1f, maskTables = false)
                page.renderTo(canvas, io.github.yuroyami.kitepdf.core.render.KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, 100.0))
            }
            val pixels = bitmap.toPixelMap()
            for ((x, y) in listOf(50 to 50, 5 to 5, 95 to 95)) {
                assertEquals(0.75f, pixels[x, y].red, 0.02f, "$kind mask of $group at ($x, $y): ${pixels[x, y]}")
            }
        }
    }

    /** The canvas that draws a gated mask shares the bitmaps of its page, so an image in many masks converts once (#371). */
    @Test
    fun a_gated_mask_converts_its_image_into_the_bitmaps_of_the_page() {
        val page = maskedPdf("Luminosity", "q 100 0 0 100 0 0 cm BI /W 2 /H 2 /CS /G /BPC 8 ID xxxx EI Q").pages[0]
        val bitmaps = KiteBitmapCache<ImageBitmap>()
        val density = Density(1f)
        val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().drawOnTestUiThread(
            density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(ImageBitmap(100, 100)), androidx.compose.ui.geometry.Size(100f, 100f),
        ) {
            val canvas = ComposeCanvas(this, measurer, 1f, false, 1f, maskTables = false, bitmaps = bitmaps)
            page.renderTo(canvas, io.github.yuroyami.kitepdf.core.render.KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, 100.0))
        }
        assertTrue(bitmaps.heldBytes > 0, "the mask converted its image into a cache of its own")
    }
}
