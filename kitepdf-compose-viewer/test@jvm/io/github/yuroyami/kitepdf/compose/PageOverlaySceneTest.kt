package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.core.KiteRectangle
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An element of the viewer's `pageOverlay` covers the rectangle of the page it asks for, at any
 * zoom and in every layout, and [KiteDocViewState.pageRectToViewport] names the same pixels (#30).
 */
class PageOverlaySceneTest {

    /** The blue box of page 0 in its user space: 90..130 across, 95..115 up. */
    private val box = KiteRectangle(90.0, 95.0, 130.0, 115.0)

    /** The same box in display space, y down from the page's top. */
    private val displayBox = KiteRectangle(90.0, 85.0, 130.0, 105.0)

    /** Two 200 x 200 pages, the first with the blue [box] on white paper. */
    private fun boxPdf(): ByteArray {
        val content = "0 0 1 rg 90 95 40 20 re f"
        return minimalPdf(
            "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 200 200] >>",
            "<< /Type /Page /Parent 2 0 R /Resources << >> /Contents 5 0 R >>",
            "<< /Type /Page /Parent 2 0 R /Resources << >> >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
        )
    }

    /**
     * Two 200 x 200 pages whose crop box starts at (100, 100) of a 400 x 400 media box: the
     * first upright, the second turned a quarter clockwise.
     */
    private fun croppedPdf(): ByteArray = minimalPdf(
        "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 400 400] /CropBox [100 100 300 300] >>",
        "<< /Type /Page /Parent 2 0 R /Resources << >> >>",
        "<< /Type /Page /Parent 2 0 R /Rotate 90 /Resources << >> >>",
    )

    /** A PDF of a catalog and [objects], numbered from 2. */
    private fun minimalPdf(vararg objects: String): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        objects.forEach(::add)
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** The pixel bounds of every pixel that [match] accepts, or null for none. */
    private fun PixelMap.boundsOf(match: (Color) -> Boolean): Rect? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (!match(this[x, y])) continue
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
        return if (right < 0) null else Rect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
    }

    private val isBlue = { c: Color -> c.blue > 0.8f && c.red < 0.3f && c.green < 0.3f }
    private val isRed = { c: Color -> c.red > 0.8f && c.blue < 0.3f && c.green < 0.3f }

    private fun assertNear(expected: Rect, actual: Rect?, what: String, tolerance: Float = 1f) {
        assertNotNull(actual, "$what: nothing found")
        val off = listOf(expected.left - actual.left, expected.top - actual.top, expected.right - actual.right, expected.bottom - actual.bottom)
        assertTrue(off.all { abs(it) <= tolerance }, "$what: expected $expected, got $actual")
    }

    @Test
    fun an_overlay_covers_its_box_at_every_zoom_in_the_paged_and_the_continuous_layout() {
        for (layout in listOf(KiteDocLayout.Paged(), KiteDocLayout.Continuous())) {
            for (zoom in listOf(1f, 2.5f)) {
                forBothEffectOrders { queued ->
                    val doc = KitePDF.open(boxPdf())
                    lateinit var state: KiteDocViewState
                    var showOverlay by mutableStateOf(false)
                    val (scene, driver) = drivenScene(400, 400, queued) {
                        state = rememberKiteDocViewState(doc)
                        KiteDocView(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            layout = layout,
                            pageOverlay = {
                                if (showOverlay && pageIndex == 0) Box(Modifier.pageRect(box).background(Color.Red))
                            },
                        )
                    }
                    scene.use {
                        val what = "$layout at zoom $zoom"
                        // The page fills the 400 px viewport, two pixels a point, and zooms around the centre.
                        val expected = if (zoom == 1f) Rect(180f, 170f, 260f, 210f) else Rect(150f, 125f, 350f, 225f)
                        driver.pumpUntil { px -> px.boundsOf(isBlue) != null }
                        if (zoom != 1f) onTestUiThread { state.setZoom(zoom) }
                        var pixels: PixelMap? = null
                        driver.pumpUntil { px -> pixels = px; px.boundsOf(isBlue)?.let { abs(it.left - expected.left) <= 1f } == true }
                        val drawn = pixels!!.boundsOf(isBlue)
                        assertNear(expected, drawn, "$what: the page's box")

                        showOverlay = true
                        driver.pumpUntil { px -> pixels = px; px.boundsOf(isRed) != null }
                        assertNear(drawn!!, pixels!!.boundsOf(isRed), "$what: the overlay")

                        val mapped = state.pageRectToViewport(0, box)
                        assertNear(drawn, mapped, "$what: pageRectToViewport")
                        assertEquals(mapped, state.displayRectToViewport(0, displayBox), "$what: the display rectangle")

                        // The centre of the mapped rectangle hit-tests back into the box.
                        val hit = assertNotNull(state.hitTest(mapped!!.center), what)
                        assertEquals(0, hit.pageIndex, what)
                        assertTrue(hit.x in box.left..box.right && hit.y in box.bottom..box.top, "$what: hit $hit")
                        val display = assertNotNull(state.hitTestDisplay(mapped.center), what)
                        assertTrue(abs(display.x - 110.0) <= 0.5 && abs(display.y - 95.0) <= 0.5, "$what: display hit $display")
                        if (layout is KiteDocLayout.Paged) {
                            // A pager places only its current page.
                            assertNull(state.pageRectToViewport(1, box), "$what: the page next to it")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun a_page_rectangle_lands_on_a_turned_and_cropped_page_in_either_direction() {
        // The page is turned a quarter clockwise and cropped at (100, 100), so user x 120..140 and
        // y 140..170 is display x 40..70 and y 20..40: viewport (80, 40)..(140, 80) at two pixels a point.
        val userRect = KiteRectangle(120.0, 140.0, 140.0, 170.0)
        val expected = Rect(80f, 40f, 140f, 80f)
        for (direction in LayoutDirection.entries) {
            forBothEffectOrders { queued ->
                val doc = KitePDF.open(croppedPdf())
                lateinit var state: KiteDocViewState
                val (scene, driver) = drivenScene(400, 400, queued) {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        state = rememberKiteDocViewState(doc)
                        KiteDocView(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            layout = KiteDocLayout.SinglePage(1),
                            pageOverlay = { Box(Modifier.pageRect(userRect).background(Color.Red)) },
                        )
                    }
                }
                scene.use {
                    var pixels: PixelMap? = null
                    driver.pumpUntil { px -> pixels = px; px.boundsOf(isRed) != null }
                    assertNear(expected, pixels!!.boundsOf(isRed), "$direction: the overlay")
                    assertNear(expected, state.pageRectToViewport(1, userRect), "$direction: pageRectToViewport")
                }
            }
        }
    }

    @Test
    fun a_new_rectangle_moves_the_element_and_one_without_sits_at_the_corner() = forBothEffectOrders { queued ->
        val doc = KitePDF.open(boxPdf())
        var rect by mutableStateOf(KiteRectangle(10.0, 10.0, 30.0, 20.0))
        val (scene, driver) = drivenScene(400, 400, queued) {
            val state = rememberKiteDocViewState(doc)
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.SinglePage(1),
                pageOverlay = {
                    Box(Modifier.displayRect(rect).background(Color.Red))
                    Box(Modifier.background(Color.Green).fillMaxSize(0.05f))
                },
            )
        }
        scene.use {
            var pixels: PixelMap? = null
            driver.pumpUntil { px -> pixels = px; px.boundsOf(isRed) != null }
            assertNear(Rect(20f, 20f, 60f, 40f), pixels!!.boundsOf(isRed), "the first rectangle")
            val isGreen = { c: Color -> c.green > 0.8f && c.red < 0.3f && c.blue < 0.3f }
            assertNear(Rect(0f, 0f, 20f, 20f), pixels!!.boundsOf(isGreen), "the element without a rectangle")

            rect = KiteRectangle(100.0, 150.0, 120.0, 170.0)
            driver.pumpUntil { px -> pixels = px; px.boundsOf(isRed)?.let { it.left > 100f } == true }
            assertNear(Rect(200f, 300f, 240f, 340f), pixels!!.boundsOf(isRed), "the moved rectangle")
        }
    }
}
