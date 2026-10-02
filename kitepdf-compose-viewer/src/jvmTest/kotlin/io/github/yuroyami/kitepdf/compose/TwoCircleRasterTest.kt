package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The software gradient between two circles draws what Skia's own draws, for a platform that has
 * none: Android before API 31 drew a gradient around the end circle only, so an off-centre
 * highlight moved and changed shape (#413).
 */
class TwoCircleRasterTest {

    private val navy = Color(0f, 0f, 0.5f)

    /** Skia's gradient between the two circles over a [size] by [size] image, in device space. */
    private fun skia(size: Int, circles: DoubleArray, stops: Array<Pair<Float, Color>>): PixelMap {
        val shader = twoCircleGradient(
            circles[0].toFloat(), circles[1].toFloat(), circles[2].toFloat(),
            circles[3].toFloat(), circles[4].toFloat(), circles[5].toFloat(),
            stops.map { it.second }, stops.map { it.first },
        )
        assertNotNull(shader, "the desktop has a gradient between two circles")
        val bitmap = ImageBitmap(size, size)
        CanvasDrawScope().drawOnTestUiThread(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(size.toFloat(), size.toFloat())) {
            drawRect(ShaderBrush(shader))
        }
        return bitmap.toPixelMap()
    }

    private fun raster(size: Int, circles: DoubleArray, stops: Array<Pair<Float, Color>>): PixelMap {
        val image = twoCircleImage(
            circles[0], circles[1], circles[2], circles[3], circles[4], circles[5],
            stops, KiteMatrix.IDENTITY, 0.0, 0.0, size, size,
        )
        assertNotNull(image)
        return image.toPixelMap()
    }

    /** The mean difference of all four channels, and the share of pixels that differ by more than [far]. */
    private fun compare(expected: PixelMap, actual: PixelMap, far: Float = 0.1f): Pair<Float, Float> {
        var sum = 0f
        var farPixels = 0
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            val e = expected[x, y]
            val a = actual[x, y]
            val d = maxOf(abs(e.red - a.red), abs(e.green - a.green), abs(e.blue - a.blue), abs(e.alpha - a.alpha))
            sum += (abs(e.red - a.red) + abs(e.green - a.green) + abs(e.blue - a.blue) + abs(e.alpha - a.alpha)) / 4f
            if (d > far) farPixels++
        }
        val n = expected.width * expected.height
        return sum / n to farPixels.toFloat() / n
    }

    @Test
    fun an_off_centre_gradient_matches_the_platform_gradient() {
        // The issue's spotlight: a small circle at (70, 130) inside a large one at (100, 100).
        val circles = doubleArrayOf(70.0, 130.0, 5.0, 100.0, 100.0, 85.0)
        val stops = arrayOf(0f to Color.White, 1f to navy)
        val (mean, far) = compare(skia(200, circles, stops), raster(200, circles, stops))
        assertTrue(mean < 0.01f && far < 0.01f, "mean difference $mean, share of far pixels $far")
    }

    /**
     * A 200 x 200 page that fills a triangle away from its corner, from (40, 40) to (160, 40) and
     * (100, 140), with a pattern of the issue's spotlight shading.
     */
    private fun spotlightPage(): io.github.yuroyami.kitepdf.PdfPage {
        val shading = "<< /ShadingType 3 /ColorSpace /DeviceRGB /Coords [70 130 5 100 100 85] " +
            "/Function << /FunctionType 2 /Domain [0 1] /C0 [1 1 1] /C1 [0 0 0.5] /N 1 >> /Extend [true true] >>"
        val content = "/Pattern cs /P1 scn 40 40 m 160 40 l 100 140 l f"
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R /Resources << /Pattern << /P1 << /PatternType 2 /Shading $shading >> >> >> >>")
        add("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return io.github.yuroyami.kitepdf.PdfDocument.open(sb.toString().encodeToByteArray()).pages[0]
    }

    /** [page] drawn through a canvas that has the platform gradient or, with [withoutPlatformGradient], none. */
    private fun render(page: io.github.yuroyami.kitepdf.PdfPage, withoutPlatformGradient: Boolean): PixelMap {
        val bitmap = ImageBitmap(200, 200)
        val density = Density(1f)
        val measurer = androidx.compose.ui.text.TextMeasurer(androidx.compose.ui.text.font.createFontFamilyResolver(), density, LayoutDirection.Ltr)
        CanvasDrawScope().drawOnTestUiThread(density, LayoutDirection.Ltr, Canvas(bitmap), Size(200f, 200f)) {
            drawRect(Color.White)
            val canvas = if (withoutPlatformGradient) {
                ComposeCanvas(this, measurer, 1f, false, 1f, twoCircleShader = { _, _, _, _, _, _, _, _ -> null })
            } else {
                ComposeCanvas(this, measurer)
            }
            page.renderTo(canvas, KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, 200.0))
        }
        return bitmap.toPixelMap()
    }

    /**
     * The spotlight filled into a triangle that the call names as its region, as an SVG gradient
     * fill does, drawn through a canvas that has the platform gradient or, with
     * [withoutPlatformGradient], none.
     */
    private fun fillTriangle(withoutPlatformGradient: Boolean): PixelMap {
        val shading = io.github.yuroyami.kitepdf.core.render.KiteShading.Radial(
            io.github.yuroyami.kitepdf.core.render.KiteColorSpace.DeviceRGB, null, null,
            doubleArrayOf(70.0, 130.0, 5.0, 100.0, 100.0, 85.0), doubleArrayOf(0.0, 1.0),
            io.github.yuroyami.kitepdf.core.render.KiteFunction.Type2(
                doubleArrayOf(0.0, 1.0), null, doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(0.0, 0.0, 0.5), 1.0,
            ),
            extendStart = true, extendEnd = true,
        )
        val triangle = io.github.yuroyami.kitepdf.core.render.KitePath.Builder().apply {
            moveTo(40.0, 40.0); lineTo(160.0, 40.0); lineTo(100.0, 140.0); close()
        }.build()
        val bitmap = ImageBitmap(200, 200)
        val density = Density(1f)
        val measurer = androidx.compose.ui.text.TextMeasurer(androidx.compose.ui.text.font.createFontFamilyResolver(), density, LayoutDirection.Ltr)
        CanvasDrawScope().drawOnTestUiThread(density, LayoutDirection.Ltr, Canvas(bitmap), Size(200f, 200f)) {
            drawRect(Color.White)
            val canvas = if (withoutPlatformGradient) {
                ComposeCanvas(this, measurer, 1f, false, 1f, twoCircleShader = { _, _, _, _, _, _, _, _ -> null })
            } else {
                ComposeCanvas(this, measurer)
            }
            canvas.fillShading(shading, KiteMatrix.IDENTITY, triangle, 1.0, io.github.yuroyami.kitepdf.core.render.KiteBlendMode.Normal)
        }
        return bitmap.toPixelMap()
    }

    @Test
    fun a_region_without_the_platform_gradient_draws_the_same_spotlight() {
        val (mean, far) = compare(fillTriangle(withoutPlatformGradient = false), fillTriangle(withoutPlatformGradient = true))
        assertTrue(mean < 0.01f && far < 0.01f, "mean difference $mean, share of far pixels $far")
    }

    @Test
    fun a_page_without_the_platform_gradient_draws_the_same_spotlight() {
        val page = spotlightPage()
        val (mean, far) = compare(render(page, withoutPlatformGradient = false), render(page, withoutPlatformGradient = true))
        assertTrue(mean < 0.01f && far < 0.01f, "mean difference $mean, share of far pixels $far")
    }

    @Test
    fun ends_that_do_not_extend_leave_the_page_alone() {
        // Transparent stops at both ends, as the canvas adds for ends that do not extend, and
        // circles that each lie outside the other, so the cone leaves most of the image unpainted.
        val circles = doubleArrayOf(50.0, 100.0, 20.0, 150.0, 100.0, 40.0)
        val stops = arrayOf(0f to Color.Transparent, 0f to Color.White, 1f to navy, 1f to Color.Transparent)
        val (mean, far) = compare(skia(200, circles, stops), raster(200, circles, stops))
        assertTrue(mean < 0.01f && far < 0.01f, "mean difference $mean, share of far pixels $far")
    }
}
