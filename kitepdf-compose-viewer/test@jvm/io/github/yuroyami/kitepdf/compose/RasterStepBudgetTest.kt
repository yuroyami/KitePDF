package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.KitePDF
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The exact composite of a non-isolated group whose paints blend held about 36 bytes a pixel,
 * 227 MB for a 2160 by 3056 raster, and failed in a 192 MB heap (#789). A raster step now counts
 * its buffers against a budget of a quarter of the heap, and past it the group paints as a layer.
 */
class RasterStepBudgetTest {

    @AfterTest
    fun reset() {
        stepBudgetForTest = null
    }

    /** An A4 page: a red backdrop, then a page-sized non-isolated group that fills in Multiply, drawn at half opacity. */
    private fun page(): ByteArray {
        val objects = listOf<Any>(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /XObject << /G 5 0 R >> /ExtGState << /H << /ca 0.5 >> >> >> /Contents 4 0 R >>",
            "" to "1 0 0 rg 0 0 595 842 re f /H gs /G Do",
            ("/Type /XObject /Subtype /Form /BBox [0 0 595 842] /Group << /S /Transparency /I false >> " +
                "/Resources << /ExtGState << /M << /BM /Multiply >> >> >>") to "/M gs 0 0 1 rg 50 50 495 742 re f",
        )
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        for ((i, o) in objects.withIndex()) {
            offsets += sb.length
            sb.append("${i + 1} 0 obj\n")
            if (o is Pair<*, *>) {
                val data = o.second as String
                sb.append("<< ${o.first} /Length ${data.length} >>\nstream\n").append(data).append("\nendstream")
            } else {
                sb.append(o)
            }
            sb.append("\nendobj\n")
        }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** The bytes this thread allocates while [block] runs. */
    private fun allocated(block: () -> Unit): Long {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }

    @Test
    fun a_step_past_its_budget_paints_the_group_as_a_layer() {
        val density = Density(1f)
        val rasterizer = KitePageRasterizer(density, LayoutDirection.Ltr, TextMeasurer(testFontFamilyResolver(), density, LayoutDirection.Ltr))
        val page = KitePDF.open(page()).pages[0]
        val exact = onTestUiThread {
            var centre = 0
            val bytes = allocated { centre = rasterizer.rasterize(page, 2160, 3056).toPixelMap()[1080, 1528].let { (it.red * 255).toInt() shl 16 or ((it.blue * 255).toInt()) } }
            bytes to centre
        }
        // A 192 MB heap's quarter.
        stepBudgetForTest = 48L shl 20
        val layered = onTestUiThread {
            var centre = 0
            val bytes = allocated { centre = rasterizer.rasterize(page, 2160, 3056).toPixelMap()[1080, 1528].let { (it.red * 255).toInt() shl 16 or ((it.blue * 255).toInt()) } }
            bytes to centre
        }
        assertTrue(exact.first > 150L shl 20, "the exact composite allocated ${exact.first shr 20} MB")
        assertTrue(layered.first < 80L shl 20, "past the budget the raster allocated ${layered.first shr 20} MB")
        // Red multiplied by blue is black, at half opacity over red: about half red either way.
        for ((name, c) in listOf("exact" to exact.second, "layered" to layered.second)) {
            assertTrue((c ushr 16) in 110..145 && (c and 0xFF) < 20, "$name centre ${c.toString(16)}")
        }
    }
}
