package io.github.yuroyami.kitepdf.nativerenderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.print.PageRange
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [KitePrintAdapter] writes the PDF that Android's print service takes (#951): one sheet of the
 * picked paper a page, each page fitted and centred inside the margins, in the order asked.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29, 35])
class KitePrintAdapterTest {

    private fun document(): PdfDocument = PdfDocument.open(
        PdfBuilder()
            .page(width = 612.0, height = 792.0) {
                setFillRgb(1.0, 0.0, 0.0)
                rectangle(72.0, 72.0, 200.0, 100.0)
                fill()
                text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "first page")
            }
            .page(width = 400.0, height = 200.0) { text(StandardFont.Helvetica, 18.0, 40.0, 100.0, "second page") }
            .page(width = 300.0, height = 300.0) { text(StandardFont.Helvetica, 18.0, 40.0, 100.0, "third page") }
            .build(),
    )

    /** Page [index] drawn on a white US Letter sheet with half-inch margins, as ARGB pixels. */
    private fun sheet(index: Int, fit: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        KitePrintAdapter.drawSheet(canvas, document().pages[index], KitePrintAdapter.Paper(612, 792, 36.0, 36.0, 36.0, 36.0), fit, null)
        return bitmap
    }

    @Test
    fun a_page_is_fitted_and_centred_inside_the_margins() {
        // The 612 x 792 page fits 540 x 720 at 540/612, so it is 540 wide and 698.8 high at x 36,
        // y 46.6. Its red box, x 72..272 and y 620..720 from the top, lands at x 99.5..276, y 593.6..681.8.
        val fitted = sheet(0, fit = true)
        assertEquals(Color.RED, fitted.getPixel(180, 640), "the box, fitted")
        assertEquals(Color.WHITE, fitted.getPixel(100, 700), "below the fitted box")
        assertEquals(Color.WHITE, fitted.getPixel(20, 400), "the left margin")
        // At 100% the page is centred and overflows the margins: its box is at x 72..272, y 620..720.
        val actual = sheet(0, fit = false)
        assertEquals(Color.RED, actual.getPixel(100, 700), "the box at 100%")
        assertEquals(Color.WHITE, actual.getPixel(180, 600), "above the box at 100%")
    }

    @Test
    fun a_wide_page_is_centred_vertically() {
        // 400 x 200 fits 540 x 720 at 1.35: 540 x 270, at y 36 + (720 - 270) / 2 = 261, so its text
        // inks rows between 261 and 531 and nothing above or below.
        val bitmap = sheet(1, fit = true)
        fun ink(rows: IntRange) = rows.sumOf { y -> (0 until 612).count { x -> bitmap.getPixel(x, y) != Color.WHITE } }
        assertTrue(ink(261..531) > 100, "the text of the wide page")
        assertEquals(0, ink(0..260) + ink(532..791), "ink outside the fitted page")
    }

    @Test
    fun page_ranges_are_the_fewest_that_cover_the_pages_written() {
        assertContentEquals(arrayOf(PageRange(0, 2), PageRange(5, 5), PageRange(7, 8)), KitePrintAdapter.ranges(listOf(0, 1, 2, 5, 7, 8)))
        assertContentEquals(emptyArray(), KitePrintAdapter.ranges(emptyList()))
    }
}
