package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfString
import kotlin.test.Test
import kotlin.test.assertEquals

/** ISO 32000-1, 8.6.6.3: hival is 0 to 255, and a larger one is clamped, as MuPDF and PDFium do (#970). */
class IndexedHivalTest {

    private fun indexed(hival: Long): KiteColorSpace.Indexed = KiteColorSpace.resolve(
        PdfArray(listOf(PdfName("Indexed"), PdfName("DeviceRGB"), PdfInt(hival), PdfString(byteArrayOf(-1, 0, 0)))),
        IndirectResolver { null },
    ) as KiteColorSpace.Indexed

    @Test
    fun aHivalPastTheSpecIsClampedAndTheFirstEntryStillReads() {
        for (hival in listOf(2147483647L, 1_000_000_000L, 1L shl 40, -5L)) {
            val cs = indexed(hival)
            assertEquals(RgbColor(1.0, 0.0, 0.0), cs.defaultColor(), "hival $hival")
            assertEquals(RgbColor(1.0, 0.0, 0.0), cs.colorAt(0), "hival $hival")
        }
        assertEquals(255, indexed(1_000_000_000L).hival)
        // A space built directly with a huge hival does not size its table by it.
        assertEquals(RgbColor(1.0, 0.0, 0.0), KiteColorSpace.Indexed(KiteColorSpace.DeviceRGB, Int.MAX_VALUE, byteArrayOf(-1, 0, 0)).defaultColor())
    }
}
