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
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.difftest.GradientFixtures
import io.github.yuroyami.kitepdf.difftest.ImageDiff
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Scores the Compose canvas on every [GradientFixtures] page against mutool,
 * each within its own budget. Skips without mutool.
 */
class ComposeGradientOracleTest {

    /** Renders the first page of [bytes] at 72 dpi through [ComposeCanvas], as the viewer does. */
    private fun render(bytes: ByteArray): BufferedImage {
        val page = KitePDF.open(bytes).pages[0]
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

    @Test
    fun every_gradient_fixture_is_within_budget() {
        assumeTrue("mutool not found, skipping.", MuPdfOracle.binary != null)
        val failures = ArrayList<String>()
        for (f in GradientFixtures.all()) {
            val kite = render(f.bytes)
            val pdf = File.createTempFile("kite-${f.name}", ".pdf").apply { deleteOnExit(); writeBytes(f.bytes) }
            val reference = assertNotNull(MuPdfOracle.render(pdf, page = 1, dpi = 72), "mutool rendered ${f.name}")
            val mae = ImageDiff.compare(kite, reference).meanAbsError
            println("compose ${f.name}: MAE=${"%.5f".format(mae)} (budget ${f.budget})")
            if (mae > f.budget) failures += "${f.name}: $mae > ${f.budget}"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
