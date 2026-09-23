package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/** Renders the first page of [bytes] at 72 dpi through [ComposeCanvas], as the viewer does. */
internal fun renderWithCompose(bytes: ByteArray): BufferedImage {
    val page = PdfDocument.open(bytes).pages[0]
    val w = page.width.toInt()
    val h = page.height.toInt()
    val bmp = ImageBitmap(w, h)
    val density = Density(1f)
    val tm = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
        drawRect(Color.White, size = size)
        page.renderTo(ComposeCanvas(this, tm), KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, h.toDouble()))
    }
    val png = Image.makeFromBitmap(bmp.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes
    return ImageIO.read(ByteArrayInputStream(png))
}
