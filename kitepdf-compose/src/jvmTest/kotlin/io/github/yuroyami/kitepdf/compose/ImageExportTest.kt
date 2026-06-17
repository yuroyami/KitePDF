package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImageExportTest {
    @Test
    fun encodeToPng_produces_a_valid_png() {
        val bmp = ImageBitmap(8, 8)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(8f, 8f)) {
            drawRect(Color.Red, size = size)
        }
        val png = assertNotNull(bmp.encodeToPng(), "encodeToPng returned null")
        // PNG signature: 89 50 4E 47 0D 0A 1A 0A
        val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue(png.size > magic.size, "PNG suspiciously small (${png.size} bytes)")
        assertTrue(magic.indices.all { png[it] == magic[it] }, "missing PNG signature")
    }
}
