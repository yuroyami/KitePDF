package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ScrollPositionSceneTest {
    private fun document() = KitePDF.open(PdfBuilder().apply {
        repeat(5) { page(width = 200.0, height = 200.0) {
            setFillRgb(0.2 * it, 0.4, 0.7)
            rectangle(0.0, 0.0, 200.0, 200.0)
            fill()
        } }
    }.build())

    @Test
    fun continuous_offsets_round_trip_in_both_axes_and_layout_directions() {
        for (orientation in listOf(Orientation.Vertical, Orientation.Horizontal)) {
            for (rtl in listOf(false, true)) {
                val doc = document()
                val saved = KiteScrollPosition(KiteLocation(0, 1), 137)
                val width = if (orientation == Orientation.Vertical) 200 else 320
                val height = if (orientation == Orientation.Vertical) 320 else 200
                val state = KiteDocViewState(doc, saved)
                lateinit var scope: CoroutineScope
                ImageComposeScene(width, height, Density(1f)) {
                    scope = rememberCoroutineScope()
                    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        KiteDocView(state, Modifier.fillMaxSize(), layout = KiteDocLayout.Continuous(orientation))
                    }
                }.use { scene ->
                    val driver = SceneTestDriver(scene)
                    driver.pumpUntilState { state.currentScrollPosition == saved && state.hitTest(Offset(width / 2f, height / 2f)) != null }
                    assertEquals(saved, state.currentScrollPosition)
                    assertNotEquals(saved.location, state.currentLocation, "save the leading page, not the centred page")
                    val hit = state.hitTest(Offset(width / 2f, height / 2f))
                    var moved = false
                    scope.launch { state.scrollToPage(3); moved = true }
                    driver.pumpUntilState { moved }
                    assertEquals(0, state.currentScrollPosition.offsetPx)
                    var restored = false
                    scope.launch { state.scrollTo(saved); restored = true }
                    driver.pumpUntilState { restored && state.currentScrollPosition == saved }
                    assertEquals(saved, state.currentScrollPosition)
                    assertEquals(hit, state.hitTest(Offset(width / 2f, height / 2f)))
                }
                assertEquals(saved, state.currentScrollPosition, "detaching keeps the offset")
            }
        }
    }

    @Test
    fun a_position_requested_before_composition_survives_attachment() {
        val saved = KiteScrollPosition(KiteLocation(0, 2), 79)
        val state = KiteDocViewState(document())
        runBlocking { state.scrollTo(saved) }
        assertEquals(saved, state.currentScrollPosition)
        ImageComposeScene(200, 320, Density(1f)) {
            KiteDocView(state, Modifier.fillMaxSize(), layout = KiteDocLayout.Continuous())
        }.use { scene ->
            SceneTestDriver(scene).pumpUntilState { state.currentScrollPosition == saved }
            assertEquals(saved, state.currentScrollPosition)
        }
    }

    @Test
    fun non_continuous_layouts_drop_offsets_on_open_and_detach() {
        for (layout in listOf(KiteDocLayout.Paged(), KiteDocLayout.Spread(), KiteDocLayout.SinglePage(1))) {
            val state = KiteDocViewState(document(), KiteScrollPosition(KiteLocation(0, 1), 137))
            ImageComposeScene(200, 320, Density(1f)) {
                KiteDocView(state, Modifier.fillMaxSize(), layout = layout)
            }.use { scene ->
                SceneTestDriver(scene).pumpUntilState { state.currentScrollPosition.offsetPx == 0 }
                assertEquals(0, state.currentScrollPosition.offsetPx)
            }
            assertEquals(0, state.currentScrollPosition.offsetPx)
        }
    }

    @Test
    fun negative_offsets_are_rejected() {
        assertFailsWith<IllegalArgumentException> { KiteScrollPosition(KiteLocation.START, -1) }
    }
}
