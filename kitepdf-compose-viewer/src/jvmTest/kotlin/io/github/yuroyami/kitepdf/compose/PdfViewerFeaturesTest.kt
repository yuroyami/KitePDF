package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feature tests for the full viewer API: paged layout + state-driven
 * navigation, the overlay/widget slot, and supersampling hairline
 * compensation in [PdfRasterizer].
 */
class PdfViewerFeaturesTest {

    private fun redBluePdf(): ByteArray = PdfBuilder()
        .page(width = 200.0, height = 200.0) {
            setFillRgb(1.0, 0.0, 0.0); rectangle(0.0, 0.0, 200.0, 200.0); fill()
        }
        .page(width = 200.0, height = 200.0) {
            setFillRgb(0.0, 0.0, 1.0); rectangle(0.0, 0.0, 200.0, 200.0); fill()
        }
        .build()

    @Test
    fun pagedLayout_navigates_via_state_and_updates_currentPage() {
        val doc = KitePDF.open(redBluePdf())
        var stateRef: PdfViewState? = null
        var navigate by mutableStateOf(false)

        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            val state = rememberPdfViewState(doc)
            stateRef = state
            PdfView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                layout = PdfLayout.Paged(),
                overlay = { s ->
                    // Widget smoke test: composes in the HUD slot without crashing.
                    PdfNavigationControls(s, Modifier.align(Alignment.BottomCenter))
                },
            )
            if (navigate) {
                LaunchedEffect(Unit) { state.scrollToPage(1) }
            }
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            var img = driver.pumpUntil { val p = it[100, 100]; p.red > 0.8f && p.blue < 0.2f }
            val first = img.toComposeImageBitmap().toPixelMap()[100, 100]
            assertTrue(first.red > 0.8f && first.blue < 0.2f, "page 0 not red in paged mode: $first")
            val state = checkNotNull(stateRef)
            assertEquals(0, state.currentPage)

            navigate = true
            Snapshot.sendApplyNotifications()
            img = driver.pumpUntil { val p = it[100, 100]; p.blue > 0.8f && p.red < 0.2f }
            val second = img.toComposeImageBitmap().toPixelMap()[100, 100]
            assertTrue(second.blue > 0.8f && second.red < 0.2f, "page 1 not shown after scrollToPage(1): $second")
            assertEquals(1, state.currentPage)
        }
    }

    @Test
    fun horizontal_continuous_layout_lays_pages_side_by_side() {
        val doc = KitePDF.open(redBluePdf())
        // Viewport wider than one page: 200px page + 8px gap + start of page 1.
        val frame = ImageComposeScene(width = 320, height = 200, density = Density(1f)) {
            PdfView(
                state = rememberPdfViewState(doc),
                modifier = Modifier.fillMaxSize(),
                layout = PdfLayout.Continuous(androidx.compose.foundation.gestures.Orientation.Horizontal),
            )
        }.use { scene ->
            SceneTestDriver(scene).pumpUntil { px ->
                px[100, 100].red > 0.8f && px[300, 100].blue > 0.8f
            }
        }
        val px = frame.toComposeImageBitmap().toPixelMap()
        val onPage0 = px[100, 100]
        assertTrue(onPage0.red > 0.8f, "page 0 not red at x=100: $onPage0")
        val onPage1 = px[300, 100] // past 200px page + 8px spacing
        assertTrue(onPage1.blue > 0.8f, "page 1 not blue at x=300: $onPage1")
    }

    @Test
    fun supersampled_raster_preserves_hairlines_when_compensated() {
        // A 0.05-width stroke: far below one pixel at ANY raster scale.
        val bytes = PdfBuilder()
            .page(width = 200.0, height = 200.0) {
                setStrokeRgb(0.0, 0.0, 0.0)
                setLineWidth(0.05)
                moveTo(10.0, 100.0); lineTo(190.0, 100.0)
                stroke()
            }
            .build()
        val page = KitePDF.open(bytes).pages[0]

        val density = Density(1f)
        val rasterizer = PdfRasterizer(
            density, LayoutDirection.Ltr,
            TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr),
        )

        // 4× supersample of a 200px-wide on-screen box.
        fun strokeThickness(hairlineWidthPx: Float): Int {
            val bmp = rasterizer.rasterize(page, 800, 800, hairlineWidthPx = hairlineWidthPx)
            val sk = bmp.asSkiaBitmap()
            var nonWhite = 0
            for (yy in 380..420) {
                val c = sk.getColor(400, yy)
                val r = (c shr 16) and 0xFF
                if (r < 220) nonWhite++
            }
            return nonWhite
        }

        val uncompensated = strokeThickness(1f)
        val compensated = strokeThickness(4f)
        assertTrue(uncompensated in 1..2, "expected ~1px floor without compensation, got $uncompensated")
        assertTrue(
            compensated >= 3,
            "hairline compensation ineffective: $compensated px thick at 4x supersample (uncompensated=$uncompensated)",
        )
    }
}
